# Copyright 2016 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Functionality to create a test sandbox for python code under test.

The purpose is to not allow any side-effects as normal test code with mocks don't detect this.
Side effects can be anything from opening a file to importing a library that alters any state,
both outside and inside the Python environment.
"""

import sys
import importlib
import importlib.util
import inspect
import unittest.mock

# TODO: Investigate if sandboxing needs sys.module to be replaced;
# otherwise, there can be some cleanups in the sandbox

if '_sys_modules' not in globals():
    _sys_modules = sys.modules


class InfoBase:

    def __init__(self, **kwargs):
        self.__dict__.update(**kwargs)

    def __repr__(self):
        return "%s(%s)" % (self.__class__.__name__, ", ".join("%s=%s" % x for x in self.__dict__.items()))


class ModulesUnderTestSandBox:

    """
    Class that acts as a sandbox environment for modules for code under test. Once started,
    it overrides sys.modules and builtin.import.
    To achieve this:

    1. Create an instance with the modules that should have a sandboxed environment
       Example:
          env = ModulesUnderTestSandBox(['module.under.test'])

    2. Create modules and module content as needed to test the sandboxed code.
         Let it fail and consider the proper need first. Submodules need to be set
         as attributes in parent module.
         builtin module will be created on initialize and populated with __import__,
         __build_class__ and __name__

       Example:
           env['builtin'].Exception = Exception
           env['builtin'].open = my_mocked_open_function
           env['os'] = env.new_module('os')
           env['os'].rename = my_mocked_rename_function
           env['os'].path = env.new_module('os.path')
           env['os.path'].exists = my_mocked_exists_function

    3. Start sandbox with either start() or __enter__, perform tests and stop it with stop() or __exit__
       - start will clear all modules under test (given to constructor) so they will be reloaded
         with a fresh global state for each test
       - stop will restore original sys.modules and builtin.__import__

       Example test method:
            with env:
                function_to_test = env['module.under.test'].function
                result = function_to_test(argument)
                assert(result)

    New module will create an empty module with provided name and store info for the import
    system find_spec (see python doc for info).

    Start will:
    1. patch the __import__ function with the mock framework and provide a custom import
       function that will check which code is calling the import function. If it's code under test it will only
       provide modules added to the sandbox (sse example in step 3 above). Test code will be allowed to
       import anything. The sandbox will walk the backtrace (frames) to check if caller is code under
       test.
    2. add itself to sys.meta_path to provide python with modules through the import mechanisms
       (find_spec, create_module, load_module, exec_module, ...). See python documentation for API details.
    3. replace sys.modules to get expected behavior for code that uses sys.modules directly.

    If code under test imports something that is not allowed, an exception will be raised.
    """

    CallerInfo = type('CallerInfo', (InfoBase,), {})
    ModuleInfo = type('ModuleInfo', (InfoBase,), {})

    def __init__(self, modules_under_test):
        self._started = False
        self._allowed_modules = {}
        self._modules = None
        self._import_patcher = None
        self._modules_under_test = {}
        self._module_fullname_from_path = {}
        self._code_under_test_sandbox_path = "<under_test>"
        self._allowed_modules_sandbox_path = "<sandbox>"
        self._importer_function = "_import"
        builtins = self.new_module('builtins')
        builtins.__import__ = getattr(self, self._importer_function)
        builtins.__build_class__ = __builtins__['__build_class__']
        builtins.__name__ = __builtins__['__name__']
        for fullname in modules_under_test:
            info = self._create_module_info(fullname, sandbox_path=self._code_under_test_sandbox_path)
            self._modules_under_test[fullname] = info

    def __enter__(self):
        self.start()

    def __exit__(self, *args):
        self.stop()

    def _is_self_frame(self, frame):
        return 'self' in frame.f_locals and frame.f_locals['self'] == self

    def _is_importer_frame(self, frame, module):
        if frame.f_code.co_name == self._importer_function:
            return self._is_self_frame(frame)
        return module.startswith('importlib')

    def get_caller_info(self, skip_test_module=False):
        info = self.CallerInfo()
        skip_nearest_caller = True
        frame = inspect.currentframe().f_back
        module = self._get_module_name_from_frame(frame)
        info.backtrace = [(module, frame.f_code.co_name, frame.f_lineno)]
        inside_self = self._is_self_frame(frame)
        inside_test_module = module == __name__
        from_importer = self._is_importer_frame(frame, module)
        info.from_importer = from_importer
        while frame.f_back and (skip_nearest_caller or inside_self or from_importer or
                                (skip_test_module and inside_test_module)):
            skip_nearest_caller = False
            if from_importer:
                info.from_importer = True
            frame = frame.f_back
            module = self._get_module_name_from_frame(frame)
            inside_self = self._is_self_frame(frame)
            inside_test_module = module == __name__
            from_importer = self._is_importer_frame(frame, module)
            info.backtrace += [(module, frame.f_code.co_name, frame.f_lineno)]
        info.module = module
        info.co_filename = frame.f_code.co_filename
        info.co_name = frame.f_code.co_name
        info.f_lineno = frame.f_lineno
        info.frame = frame
        info.is_under_test = module in self._modules_under_test
        return info

    def is_module_under_test(self, module):
        return module.__file__.split('/')[0] == self._code_under_test_sandbox_path

    def _create_module_from_info(self, info):
        kw = {'__file__': info.spec.origin, '__name__': info.spec.name, '__loader__': self,
              '__package__': info.package}
        return type(info.spec.name, (), kw)()

    def get_module_under_test(self, fullname):
        info = self._modules_under_test[fullname]
        if info.module is None:  # execute on demand
            info.module = self._create_module_from_info(info)
            self.exec_module(info.module)
            assert(info.module is not None)
            assert(info.executed is True)
        return info.module

    def _get_module_name_from_frame(self, frame):
        if '__name__' in frame.f_globals:
            module = frame.f_globals["__name__"]
        else:
            module = self._module_fullname_from_path[frame.f_code.co_filename]  # under construction
        return module

    def _import(self, name, globals_=None, locals_=None, fromlist=(), level=0):
        module = self._get_module(name)
        if module is None:
            module = importlib.__import__(name, globals_, locals_, fromlist, level)
        return module

    def start(self):
        """
        Start the sandbox. Call code under test after this point.
        """
        assert(not self._started)
        for fullname in self._modules_under_test:
            info = self._modules_under_test[fullname]
            info.module = None
            info.executed = False
        self._modules = {}
        self._import_patcher = unittest.mock.patch('builtins.__import__', getattr(self, self._importer_function))
        sys.modules = self
        sys.meta_path.insert(0, self)
        self._import_patcher.start()
        self._started = True

    def stop(self):
        """
        Stop the sandbox and restore original state.
        """
        assert self._started
        self._import_patcher.stop()
        sys.meta_path.remove(self)
        sys.modules = _sys_modules
        self._started = False

    def _create_module_info(self, fullname, sandbox_path=None):
        orig_spec = importlib.util.find_spec(fullname)
        if orig_spec is None:
            raise Exception("Cannot find module %r" % fullname)
        is_package = orig_spec.loader.is_package(orig_spec.name)
        name_parts = fullname.split('.')
        if sandbox_path is not None:  # Module to sandbox
            if orig_spec.origin:  # origin exists, e.g. code under test, existing modules to mock
                path = orig_spec.origin.replace('\\', '/')
                package_path = "/".join(name_parts)
                if is_package:
                    origin = "%s/%s/__init__.py" % (sandbox_path, package_path)
                else:
                    origin = "%s/%s.py" % (sandbox_path, package_path)
            else:  # origin doesn't exist, e.g. sys, os, ...
                path = None
                origin = None
            spec = importlib.machinery.ModuleSpec(
                name=fullname,
                loader=self,
                origin=origin,
                is_package=is_package,
            )
        else:  # Module given unmodified to the sandboxed code, e.g. pdb for debug
            spec = orig_spec
            path = orig_spec.origin
        package = None
        if len(name_parts) > 1:
            package = ".".join(name_parts[:-1])
        self._module_fullname_from_path[path] = fullname
        info = self.ModuleInfo(
            spec=spec,
            path=path,
            module=None,
            is_package=is_package,
            executed=False,
            parent_updated=False,
            name=name_parts[-1],
            package=package,
        )
        return info

    def new_module(self, fullname):
        """
        Use this function to create a new module. It will make sure python
        importlib will find it through find_spec.
        """
        if fullname in self._allowed_modules:
            raise Exception('Module %r already exists' % fullname)
        info = self._create_module_info(fullname, sandbox_path=self._allowed_modules_sandbox_path)
        self._allowed_modules[fullname] = info
        info.executed = True
        info.module = importlib.util.module_from_spec(info.spec)
        return info.module

    def create_module(self, spec):
        """
        Called from python importlib. Do not change argument list.
        See python documentation for more info.
        """
        info = self._allowed_modules[spec.name]
        info.module = self._create_module_from_info(info)
        name_parts = spec.name.split('.')
        if len(name_parts) > 1:
            parent_info = self._allowed_modules[spec.parent]
            setattr(parent_info.module, name_parts[-1], info.module)
        return info.module

    def find_spec(self, fullname, path, target=None):
        """
        Called from python importlib. Do not change argument list.
        See python documentation for more info.
        """
        caller_info = self.get_caller_info()
        if caller_info.is_under_test:
            if fullname in self._allowed_modules:
                spec = self._allowed_modules[fullname].spec
            else:
                raise Exception("find_spec of %r not allowed" % fullname)
        elif fullname in self._modules_under_test:
            spec = self._modules_under_test[fullname].spec
        else:
            spec = None  # handover to next finder
        return spec

    def load_module(self, fullname):
        """
        Called from python importlib. Do not change argument list.
        See python documentation for more info.
        """
        assert(fullname not in self._modules_under_test)
        assert(fullname in self._allowed_modules)
        assert(self._allowed_modules[fullname] is not None)
        self._modules[fullname] = self._allowed_modules[fullname].module

    def get_code(self, fullname):
        """
        Called from python importlib. Do not change argument list.
        See python documentation for more info.
        """
        if fullname not in self._modules_under_test:
            raise Exception("get_code on module not under test: %r." % fullname)
        info = self._modules_under_test[fullname]
        with open(info.path, 'rb') as fr:
            code = fr.read()
        return code

    def exec_module(self, module):
        """
        Called from python importlib. Do not change argument list.
        See python documentation for more info.
        """
        fullname = module.__name__
        if not self.is_module_under_test(module):
            raise Exception("exec_module on module not under test: %r %r." % (fullname, module.__file__))
        info = self._modules_under_test[module.__name__]
        if not info.executed:
            module.__dict__['__builtins__'] = self._allowed_modules['builtins'].module.__dict__
            code = self.get_code(module.__name__)
            gvars = module.__dict__
            eval(compile(code, info.path, 'exec'), gvars)
            for attr in gvars:
                if hasattr(gvars[attr], '__module__') and gvars[attr].__module__ == 'builtins':
                    try:
                        gvars[attr].__module__ = fullname  # TODO: find out why wrong __module__ is set and fix
                    except Exception:
                        pass  # some code copies "int" from builtins as "long" which cannot modify attributes
            info.executed = True

    def _get_module(self, fullname):
        caller_info = self.get_caller_info()
        module = None
        if caller_info.is_under_test and fullname not in self._allowed_modules:
            raise Exception("Code under test %r not allowed to access module %r." % (caller_info.module, fullname))
        elif caller_info.is_under_test or not caller_info.from_importer:
            if fullname not in self._allowed_modules and 'pydevd' in fullname:
                module = _sys_modules[fullname]
            else:
                module = self._allowed_modules[fullname].module
        elif caller_info.from_importer and fullname in _sys_modules:
            module = _sys_modules[fullname]
        else:
            module = None
        return module

    def __getitem__(self, fullname):
        """
        Called when getting an item in sys.modules after sandbox is started
        """
        module = self._get_module(fullname)
        if module is None:
            raise KeyError(fullname)
        return module

    def __setitem__(self, fullname, module):
        """
        Called when setting an item in sys.modules after sandbox is started
        """
        caller_info = self.get_caller_info()
        if caller_info.is_under_test:
            raise Exception("Code under test %r tries to add module %s" % (caller_info.module, fullname))
        elif caller_info.from_importer:
            _sys_modules[fullname] = module
        else:
            if fullname not in self._allowed_modules:
                info = self._create_module_info(module.__name__)
                self._allowed_modules[fullname] = info
                info.executed = True
            else:
                info = self._allowed_modules[fullname]
                if info.module is not None and module != info.module:
                    raise Exception('Module %r already exist' % fullname)
            info.module = module

    def __contains__(self, fullname):
        """
        Called when checking existance of an item in sys.modules after sandbox is started
        """
        caller_info = self.get_caller_info()
        if caller_info.from_importer:
            contains = fullname in _sys_modules
        else:
            contains = fullname in self._allowed_modules
        return contains

    def __delitem__(self, fullname):
        """
        Called when deleting an item in sys.modules after sandbox is started
        """
        caller_info = self.get_caller_info()
        if caller_info.from_importer:
            del _sys_modules[fullname]
        elif caller_info.is_under_test:
            raise Exception("Code under test %r tries to delete module %s" % (caller_info.module, fullname))
        else:
            del self._allowed_modules[fullname]

    def get(self, fullname, default=None):
        """
        Get function in sys.modules after sandbox is started
        """
        return self.__getitem__(fullname) if fullname in self else default

    def pop(self, key):
        """
        Called when checking existance of an item in sys.modules after sandbox is started
        """
        caller_info = self.get_caller_info()
        if caller_info.from_importer:
            item = _sys_modules.pop(key)
        else:
            raise Exception("Code under test %r tries to pop module" % caller_info.module)
        return item
