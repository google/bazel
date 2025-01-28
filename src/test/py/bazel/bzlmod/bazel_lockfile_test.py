# pylint: disable=g-backslash-continuation
# Copyright 2023 The Bazel Authors. All rights reserved.
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
# pylint: disable=g-long-ternary

import json
import os
import pathlib
import tempfile

from absl.testing import absltest
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry
from src.test.py.bazel.bzlmod.test_utils import scratchFile


class BazelLockfileTest(test_base.TestBase):

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.registries_work_dir = tempfile.mkdtemp(dir=self._test_cwd)
    self.main_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'main')
    )
    self.main_registry.start()
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'aaa', '1.1'
    ).createCcModule('bbb', '1.0', {'aaa': '1.0'}).createCcModule(
        'bbb', '1.1', {'aaa': '1.1'}
    )
    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            'build --noenable_workspace',
            'build --experimental_isolated_extension_usages',
            'build --registry=' + self.main_registry.getURL(),
            # We need to have BCR here to make sure built-in modules like
            # bazel_tools can work.
            'build --registry=https://bcr.bazel.build',
            'build --verbose_failures',
            # Set an explicit Java language version
            'build --java_language_version=8',
            'build --tool_java_language_version=8',
            'build --lockfile_mode=update',
        ],
    )
    # TODO(pcloudy): investigate why this is needed, MODULE.bazel.lock is not
    # deterministic?
    os.remove(self.Path('MODULE.bazel.lock'))

  def tearDown(self):
    self.main_registry.stop()
    test_base.TestBase.tearDown(self)

  def testInvalidLockfile(self):
    self.ScratchFile('BUILD', ['filegroup(name = "hello")'])
    self.RunBazel(['build', '//:all'])

    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      lockfile['registryFileHashes'] = 'I am a string'

    with open(self.Path('MODULE.bazel.lock'), 'w') as f:
      f.write(json.dumps(lockfile))

    exit_code, _, stderr = self.RunBazel(
        ['build', '//:all'], allow_failure=True
    )
    stderr = '\n'.join(stderr)
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertRegex(
        stderr,
        (
            'ERROR: Error computing the main repository mapping: Failed to read'
            ' and parse the MODULE\\.bazel\\.lock file with error:'
            ' java\\.lang\\.IllegalStateException: Expected BEGIN_OBJECT but'
            ' was STRING .*\\. Try deleting it and rerun the build.'
        ),
    )

  def testChangeModuleInRegistryWithoutLockfile(self):
    # Add module 'sss' to the registry with dep on 'aaa'
    self.main_registry.createCcModule('sss', '1.3', {'aaa': '1.1'})
    # Create a project with deps on 'sss'
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "sss", version = "1.3")',
        ],
    )
    self.ScratchFile('BUILD', ['filegroup(name = "hello")'])
    self.RunBazel(
        [
            'build',
            '--nobuild',
            '--lockfile_mode=off',
            '//:all',
        ],
    )

    # Change registry -> update 'sss' module file (corrupt it)
    module_dir = self.main_registry.root.joinpath('modules', 'sss', '1.3')
    scratchFile(module_dir.joinpath('MODULE.bazel'), ['whatever!'])

    # Shutdown bazel to empty any cache of the deps tree
    self.RunBazel(['shutdown'])
    # Runing again will try to get 'sss' which should produce an error
    exit_code, _, stderr = self.RunBazel(
        [
            'build',
            '--nobuild',
            '--lockfile_mode=off',
            '//:all',
        ],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        (
            'ERROR: Error computing the main repository mapping: in module '
            'dependency chain <root> -> sss@1.3: error parsing MODULE.bazel '
            'file for sss@1.3'
        ),
        stderr,
    )

  def testChangeModuleInRegistryWithLockfile(self):
    # Add module 'sss' to the registry with dep on 'aaa'
    self.main_registry.createCcModule('sss', '1.3', {'aaa': '1.1'})
    # Create a project with deps on 'sss'
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "sss", version = "1.3")',
        ],
    )
    self.ScratchFile('BUILD', ['filegroup(name = "hello")'])
    self.RunBazel(['build', '--nobuild', '//:all'])

    # Change registry -> update 'sss' module file (corrupt it)
    module_dir = self.main_registry.root.joinpath('modules', 'sss', '1.3')
    scratchFile(module_dir.joinpath('MODULE.bazel'), ['whatever!'])

    # Shutdown bazel to empty any cache of the deps tree
    self.RunBazel(['shutdown'])
    # Running with the lockfile, should not recognize the registry changes
    # hence find no errors
    self.RunBazel(['build', '--nobuild', '//:all'])

    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "sss", version = "1.3")',
            'bazel_dep(name = "bbb", version = "1.1")',
        ],
    )
    # Shutdown bazel to empty any cache of the deps tree
    self.RunBazel(['shutdown'])
    # Even adding a new dependency should not fail due to the registry change
    self.RunBazel(['build', '--nobuild', '//:all'])

  def testChangeModuleInRegistryWithLockfileInRefreshMode(self):
    # Add module 'sss' to the registry with dep on 'aaa'
    self.main_registry.createCcModule('sss', '1.3', {'aaa': '1.1'})
    # Create a project with deps on 'sss'
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "sss", version = "1.3")',
        ],
    )
    self.ScratchFile('BUILD', ['filegroup(name = "hello")'])
    self.RunBazel(['build', '--nobuild', '//:all'])

    # Change registry -> update 'sss' module file (corrupt it)
    module_dir = self.main_registry.root.joinpath('modules', 'sss', '1.3')
    scratchFile(module_dir.joinpath('MODULE.bazel'), ['whatever!'])

    # Shutdown bazel to empty any cache of the deps tree
    self.RunBazel(['shutdown'])
    # Running with the lockfile, should not recognize the registry changes
    # hence find no errors
    self.RunBazel(['build', '--nobuild', '--lockfile_mode=refresh', '//:all'])

    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "sss", version = "1.3")',
            'bazel_dep(name = "bbb", version = "1.1")',
        ],
    )
    # Shutdown bazel to empty any cache of the deps tree
    self.RunBazel(['shutdown'])
    # Even adding a new dependency should not fail due to the registry change
    self.RunBazel(['build', '--nobuild', '--lockfile_mode=refresh', '//:all'])

  def testAddModuleToRegistryWithLockfile(self):
    # Create a project with deps on the BCR's 'platforms' module
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "platforms", version = "0.0.9")',
        ],
    )
    self.ScratchFile('BUILD', ['filegroup(name = "hello")'])
    self.RunBazel(['build', '--nobuild', '//:all'])

    # Add a broken 'platforms' module to the first registry
    module_dir = self.main_registry.root.joinpath(
        'modules', 'platforms', '0.0.9'
    )
    scratchFile(module_dir.joinpath('MODULE.bazel'), ['whatever!'])

    # Shutdown bazel to empty any cache of the deps tree
    self.RunBazel(['shutdown'])
    # Running with the lockfile, should not recognize the registry changes
    # hence find no errors
    self.RunBazel(['build', '--nobuild', '//:all'])

    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "platforms", version = "0.0.9")',
            'bazel_dep(name = "bbb", version = "1.1")',
        ],
    )
    # Shutdown bazel to empty any cache of the deps tree
    self.RunBazel(['shutdown'])
    # Even adding a new dependency should not fail due to the registry change
    self.RunBazel(['build', '--nobuild', '//:all'])

  def testAddModuleToRegistryWithLockfileInRefreshMode(self):
    # Create a project with deps on the BCR's 'platforms' module
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "platforms", version = "0.0.9")',
        ],
    )
    self.ScratchFile('BUILD', ['filegroup(name = "hello")'])
    self.RunBazel(['build', '--nobuild', '//:all'])

    # Add a broken 'platforms' module to the first registry
    module_dir = self.main_registry.root.joinpath(
        'modules', 'platforms', '0.0.9'
    )
    scratchFile(module_dir.joinpath('MODULE.bazel'), ['whatever!'])

    # Shutdown bazel to empty any cache of the deps tree
    self.RunBazel(['shutdown'])
    # Running with the lockfile in refresh mode should recognize the addition
    # to the first registry
    exit_code, _, stderr = self.RunBazel(
        [
            'build',
            '--nobuild',
            '--lockfile_mode=refresh',
            '//:all',
        ],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        (
            'ERROR: Error computing the main repository mapping: in module '
            'dependency chain <root> -> platforms@0.0.9: error parsing '
            'MODULE.bazel file for platforms@0.0.9'
        ),
        stderr,
    )

  def testChangeFlagWithLockfile(self):
    # Create a project with an outdated direct dep on aaa
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'bazel_dep(name = "bbb", version = "1.1")',
        ],
    )
    self.ScratchFile('BUILD', ['filegroup(name = "hello")'])
    self.RunBazel(['build', '--nobuild', '//:all'])

    # Shutdown bazel to empty any cache of the deps tree
    self.RunBazel(['shutdown'])
    # Running with the lockfile, but the changed flag value should be honored
    exit_code, _, stderr = self.RunBazel(
        [
            'build',
            '--nobuild',
            '--check_direct_dependencies=error',
            '//:all',
        ],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        "ERROR: For repository 'aaa', the root module requires module version"
        ' aaa@1.0, but got aaa@1.1',
        '\n'.join(stderr),
    )

  def testLockfileErrorMode(self):
    self.ScratchFile('MODULE.bazel', [])
    self.ScratchFile('BUILD', ['filegroup(name = "hello")'])
    self.RunBazel(
        [
            'build',
            '--nobuild',
            '//:all',
        ],
    )

    # Run with updated module
    self.ScratchFile(
        'MODULE.bazel', ['bazel_dep(name="does_not_exist",version="0")']
    )
    exit_code, _, stderr = self.RunBazel(
        [
            'build',
            '--nobuild',
            '--lockfile_mode=error',
            '//:all',
        ],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        (
            'ERROR: Error computing the main repository mapping:'
            ' Missing checksum for registry file {}'.format(
                self.main_registry.getURL()
            )
            + '/modules/does_not_exist/0/MODULE.bazel not permitted with'
            ' --lockfile_mode=error. Please run'
            ' `bazel mod deps --lockfile_mode=update` to update your lockfile.'
        ),
        stderr,
    )

  def testModuleExtension(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'lockfile_ext.dep(name = "bmbm", versions = ["v1", "v2"])',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    print("Hello from the other side!")',
            '    repo_rule(name="hello")',
            '    for mod in ctx.modules:',
            '        for dep in mod.tags.dep:',
            '            print("Name:", dep.name, ", Versions:", dep.versions)',
            '',
            '_dep = tag_class(attrs={"name": attr.string(), "versions":',
            ' attr.string_list()})',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl,',
            '    tag_classes={"dep": _dep},',
            '    environ=["GREEN_TREES", "NOT_SET"],',
            '    os_dependent=True,',
            '    arch_dependent=True,',
            ')',
        ],
    )

    # Only set one env var, to make sure null variables don't crash
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'GREEN_TREES': 'In the city'}
    )
    self.assertIn('Hello from the other side!', ''.join(stderr))
    self.assertIn('Name: bmbm , Versions: ["v1", "v2"]', ''.join(stderr))

    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'GREEN_TREES': 'In the city'}
    )
    self.assertNotIn('Hello from the other side!', ''.join(stderr))

  def testIsolatedAndNonIsolatedModuleExtensions(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            (
                'lockfile_ext_1 = use_extension("extension.bzl",'
                ' "lockfile_ext", isolate = True)'
            ),
            'use_repo(lockfile_ext_1, hello_1 = "hello")',
            'lockfile_ext_2 = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext_2, hello_2 = "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    print("Hello from the other side, %s!" % ctx.is_isolated)',
            '    repo_rule(name="hello")',
            '',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl,',
            ')',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@hello_1//:all', '@hello_2//:all'])
    self.assertIn('Hello from the other side, True!', ''.join(stderr))
    self.assertIn('Hello from the other side, False!', ''.join(stderr))
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      self.assertIn(
          '//:extension.bzl%lockfile_ext%<root>~lockfile_ext_1',
          lockfile['moduleExtensions'],
      )
      self.assertIn(
          '//:extension.bzl%lockfile_ext', lockfile['moduleExtensions']
      )

    # Verify that the build succeeds using the lockfile.
    self.RunBazel(['shutdown'])
    self.RunBazel(['build', '@hello_1//:all', '@hello_2//:all'])

  def testUpdateIsolatedModuleExtension(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            (
                'lockfile_ext = use_extension("extension.bzl", "lockfile_ext",'
                ' isolate = True)'
            ),
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    print("Hello from the other side, %s!" % ctx.is_isolated)',
            '    repo_rule(name="hello")',
            '',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl,',
            ')',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    self.assertIn('Hello from the other side, True!', ''.join(stderr))
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      self.assertIn(
          '//:extension.bzl%lockfile_ext%<root>~lockfile_ext',
          lockfile['moduleExtensions'],
      )
      self.assertNotIn(
          '//:extension.bzl%lockfile_ext', lockfile['moduleExtensions']
      )

    # Verify that the build succeeds using the lockfile.
    self.RunBazel(['shutdown'])
    self.RunBazel(['build', '@hello//:all'])

    # Update extension usage to be non-isolated.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    self.assertIn('Hello from the other side, False!', ''.join(stderr))
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      self.assertNotIn(
          '//:extension.bzl%lockfile_ext%<root>~lockfile_ext',
          lockfile['moduleExtensions'],
      )
      self.assertIn(
          '//:extension.bzl%lockfile_ext', lockfile['moduleExtensions']
      )

    # Verify that the build succeeds using the lockfile.
    self.RunBazel(['shutdown'])
    self.RunBazel(['build', '@hello//:all'])

  def testModuleExtensionsInDifferentBuilds(self):
    # Test that the module extension stays in the lockfile (as long as it's
    # used in the module) even if it is not in the current build
    self.ScratchFile(
        'MODULE.bazel',
        [
            'extA = use_extension("extension.bzl", "extA")',
            'extB = use_extension("extension.bzl", "extB")',
            'use_repo(extA, "hello_ext_A")',
            'use_repo(extB, "hello_ext_B")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            'def _ext_a_impl(ctx):',
            '    repo_rule(name="hello_ext_A")',
            'def _ext_b_impl(ctx):',
            '    repo_rule(name="hello_ext_B")',
            'extA = module_extension(implementation=_ext_a_impl)',
            'extB = module_extension(implementation=_ext_b_impl)',
        ],
    )

    self.RunBazel(['build', '@hello_ext_A//:all'])
    self.RunBazel(['build', '@hello_ext_B//:all'])

    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      ext_keys = list(lockfile['moduleExtensions'].keys())
      self.assertIn('//:extension.bzl%extA', ext_keys)
      self.assertIn('//:extension.bzl%extB', ext_keys)

  def testUpdateModuleExtension(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\\"lala\\")")',
            'repo_rule = repository_rule(implementation = _repo_rule_impl)',
            'def _mod_ext_impl(ctx):',
            '    print("Hello from the other side!")',
            '    repo_rule(name= "hello")',
            'lockfile_ext = module_extension(implementation = _mod_ext_impl)',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    self.assertIn('Hello from the other side!', ''.join(stderr))
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      old_impl = lockfile['moduleExtensions']['//:extension.bzl%lockfile_ext'][
          'general'
      ]['bzlTransitiveDigest']

    # Run again to make sure the resolution value is cached. So even if module
    # resolution doesn't rerun (its event is null), the lockfile is still
    # updated with the newest extension eval results
    self.RunBazel(['build', '@hello//:all'])

    # Update extension. Make sure that it is executed and updated in the
    # lockfile without errors (since it's already in the lockfile)
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\\"lala\\")")',
            'repo_rule = repository_rule(implementation = _repo_rule_impl)',
            'def _mod_ext_impl(ctx):',
            '    print("Hello from the other town!")',
            '    repo_rule(name= "hello")',
            'lockfile_ext = module_extension(implementation = _mod_ext_impl)',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    self.assertIn('Hello from the other town!', ''.join(stderr))
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      new_impl = lockfile['moduleExtensions']['//:extension.bzl%lockfile_ext'][
          'general'
      ]['bzlTransitiveDigest']
      self.assertNotEqual(new_impl, old_impl)

  def testUpdateModuleExtensionErrorMode(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\\"lala\\")")',
            'repo_rule = repository_rule(implementation = _repo_rule_impl)',
            'def _mod_ext_impl(ctx):',
            '    print("Hello from the other side!")',
            '    repo_rule(name= "hello")',
            'lockfile_ext = module_extension(implementation = _mod_ext_impl)',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    self.assertIn('Hello from the other side!', ''.join(stderr))
    self.RunBazel(['shutdown'])

    # Update extension.
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\\"lalo\\")")',
            'repo_rule = repository_rule(implementation = _repo_rule_impl)',
            'def _mod_ext_impl(ctx):',
            '    print("Hello from the other town!")',
            '    repo_rule(name= "hello")',
            'lockfile_ext = module_extension(implementation = _mod_ext_impl)',
        ],
    )

    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '--lockfile_mode=error', '@hello//:all'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        (
            'ERROR: MODULE.bazel.lock is no longer up-to-date because: The '
            'implementation of the extension '
            "'ModuleExtensionId{bzlFileLabel=//:extension.bzl, "
            "extensionName=lockfile_ext, isolationKey=Optional.empty}' or one "
            'of its transitive .bzl files has changed. Please run'
            ' `bazel mod deps --lockfile_mode=update` to update your lockfile.'
        ),
        stderr,
    )

  def testRemoveModuleExtensionsNotUsed(self):
    # Test that the module extension is removed from the lockfile if it is not
    # used anymore
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            'def _ext_impl(ctx):',
            '    repo_rule(name="hello")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    self.RunBazel(['build', '@hello//:all'])
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      ext_keys = list(lockfile['moduleExtensions'].keys())
      self.assertIn('//:extension.bzl%ext', ext_keys)

    self.ScratchFile('MODULE.bazel', [])
    self.RunBazel(['build', '//:all'])
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      ext_keys = list(lockfile['moduleExtensions'].keys())
      self.assertNotIn('//:extension.bzl%ext', ext_keys)

  def testNoAbsoluteRootModuleFilePath(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'ext.dep(generate = True)',
            'use_repo(ext, ext_hello = "hello")',
            'other_ext = use_extension("extension.bzl", "other_ext")',
            'other_ext.dep(generate = False)',
            'use_repo(other_ext, other_ext_hello = "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    for mod in ctx.modules:',
            '        for dep in mod.tags.dep:',
            '            if dep.generate:',
            '                repo_rule(name="hello")',
            '',
            '_dep = tag_class(attrs={"generate": attr.bool()})',
            'ext = module_extension(',
            '    implementation=_module_ext_impl,',
            '    tag_classes={"dep": _dep},',
            ')',
            'other_ext = module_extension(',
            '    implementation=_module_ext_impl,',
            '    tag_classes={"dep": _dep},',
            ')',
        ],
    )

    # Paths to module files in error message always use forward slashes as
    # separators, even on Windows.
    module_file_path = self.Path('MODULE.bazel').replace('\\', '/')

    self.RunBazel(['build', '--nobuild', '@ext_hello//:all'])
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      self.assertNotIn(module_file_path, f.read())

    self.RunBazel(['shutdown'])
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '@other_ext_hello//:all'], allow_failure=True
    )
    self.AssertNotExitCode(exit_code, 0, stderr)
    self.assertIn(
        (
            'ERROR: module extension "other_ext" from "//:extension.bzl" does '
            'not generate repository "hello", yet it is imported as '
            '"other_ext_hello" in the usage at '
            + module_file_path
            + ':4:26'
        ),
        stderr,
    )

  def testModuleExtensionEnvVariable(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\\"lala\\")")',
            'repo_rule = repository_rule(implementation = _repo_rule_impl)',
            'def _module_ext_impl(ctx):',
            '    print("Hello from the other side!")',
            '    repo_rule(name= "hello")',
            'lockfile_ext = module_extension(',
            '    implementation = _module_ext_impl,',
            '    environ = ["SET_ME"]',
            ')',
        ],
    )
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'SET_ME': 'High in sky'}
    )
    self.assertIn('Hello from the other side!', ''.join(stderr))
    # Run with same value, no evaluated
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'SET_ME': 'High in sky'}
    )
    self.assertNotIn('Hello from the other side!', ''.join(stderr))
    # Run with different value, will be re-evaluated
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'SET_ME': 'Down to earth'}
    )
    self.assertIn('Hello from the other side!', ''.join(stderr))

  def testChangeEnvVariableInErrorMode(self):
    # If environ is set up in module extension, it should be re-evaluated if its
    # value changed
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\\"lala\\")")',
            'repo_rule = repository_rule(implementation = _repo_rule_impl)',
            'def _module_ext_impl(ctx):',
            '    print("Hello from the other side!")',
            '    repo_rule(name= "hello")',
            'lockfile_ext = module_extension(',
            '    implementation = _module_ext_impl,',
            '    environ = ["SET_ME"]',
            ')',
        ],
    )
    self.RunBazel(['build', '@hello//:all'], env_add={'SET_ME': 'High in sky'})
    exit_code, _, stderr = self.RunBazel(
        ['build', '--lockfile_mode=error', '@hello//:all'],
        env_add={'SET_ME': 'Down to earth'},
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        (
            'ERROR: MODULE.bazel.lock is no longer up-to-date because: The'
            ' environment variables the extension'
            " 'ModuleExtensionId{bzlFileLabel=//:extension.bzl,"
            " extensionName=lockfile_ext, isolationKey=Optional.empty}' depends"
            ' on (or their values) have changed. Please run'
            ' `bazel mod deps --lockfile_mode=update` to update your lockfile.'
        ),
        stderr,
    )

  def testModuleExtensionWithFile(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    print("I am running the extension")',
            '    print(ctx.read(Label("//:hello.txt")))',
            '    repo_rule(name="hello")',
            '',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl',
            ')',
        ],
    )

    self.ScratchFile('hello.txt', ['I will not stay the same.'])
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = ''.join(stderr)
    self.assertIn('I am running the extension', stderr)
    self.assertIn('I will not stay the same.', stderr)

    # Shutdown bazel to empty cache and run with no changes
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = ''.join(stderr)
    self.assertNotIn('I am running the extension', stderr)
    self.assertNotIn('I will not stay the same.', stderr)

    # Update file and rerun
    self.ScratchFile('hello.txt', ['I have changed now!'])
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = ''.join(stderr)
    self.assertIn('I am running the extension', stderr)
    self.assertIn('I have changed now!', stderr)

  def testOldVersion(self):
    self.ScratchFile('MODULE.bazel')
    self.ScratchFile('BUILD', ['filegroup(name = "hello")'])
    self.RunBazel(['build', '--nobuild', '//:all'])

    # Set version to old
    with open('MODULE.bazel.lock', 'r') as json_file:
      data = json.load(json_file)
      version = data['lockFileVersion']
    with open('MODULE.bazel.lock', 'w') as json_file:
      data['lockFileVersion'] = version - 1
      json.dump(data, json_file, indent=4)

    # Run in error mode
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '--lockfile_mode=error', '//:all'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        (
            'ERROR: Error computing the main repository mapping: The version'
            ' of MODULE.bazel.lock is not supported by this version of Bazel.'
            ' Please run `bazel mod deps --lockfile_mode=update` to update your'
            ' lockfile.'
        ),
        stderr,
    )

    # Run again with update
    self.RunBazel(['build', '--nobuild', '//:all'])
    with open('MODULE.bazel.lock', 'r') as json_file:
      data = json.load(json_file)
    self.assertEqual(data['lockFileVersion'], version)

  def testExtensionEvaluationDoesNotRerunOnChangedImports(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "dep", "indirect_dep", "invalid_dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    print("I am being evaluated")',
            '    repo_rule(name="dep")',
            '    repo_rule(name="missing_dep")',
            '    repo_rule(name="indirect_dep")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=["dep", "missing_dep"],',
            '        root_module_direct_dev_deps=[],',
            '    )',
            '',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl',
            ')',
        ],
    )

    # The build fails due to the "invalid_dep" import, which is not
    # generated by the extension.
    # Warnings should still be shown.
    _, _, stderr = self.RunBazel(['build', '@dep//:all'], allow_failure=True)
    stderr = '\n'.join(stderr)
    self.assertIn('I am being evaluated', stderr)
    self.assertIn(
        'Imported, but not created by the extension (will cause the build to'
        ' fail):\ninvalid_dep',
        stderr,
    )
    self.assertIn(
        'Not imported, but reported as direct dependencies by the extension'
        ' (may cause the build to fail):\nmissing_dep',
        stderr,
    )
    self.assertIn(
        'Imported, but reported as indirect dependencies by the'
        ' extension:\nindirect_dep',
        stderr,
    )
    self.assertIn(
        'ERROR: module extension "lockfile_ext" from "//:extension.bzl" does'
        ' not generate repository "invalid_dep"',
        stderr,
    )

    # Shut down bazel to empty cache and run with no changes to verify
    # that the warnings are still shown.
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', '@dep//:all'], allow_failure=True)
    stderr = '\n'.join(stderr)
    self.assertNotIn('I am being evaluated', stderr)
    self.assertIn(
        'Imported, but not created by the extension (will cause the build to'
        ' fail):\ninvalid_dep',
        stderr,
    )
    self.assertIn(
        'Not imported, but reported as direct dependencies by the extension'
        ' (may cause the build to fail):\nmissing_dep',
        stderr,
    )
    self.assertIn(
        'Imported, but reported as indirect dependencies by the'
        ' extension:\nindirect_dep',
        stderr,
    )
    self.assertIn(
        'ERROR: module extension "lockfile_ext" from "//:extension.bzl" does'
        ' not generate repository "invalid_dep"',
        stderr,
    )

    # Fix the imports, which should not trigger a rerun of the extension
    # even though imports and locations changed.
    self.ScratchFile(
        'MODULE.bazel',
        [
            '# This is a comment that changes the location of the usage below.',
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "dep", "missing_dep")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '@dep//:all'])
    stderr = '\n'.join(stderr)
    self.assertNotIn('I am being evaluated', stderr)
    self.assertNotIn(
        'Not imported, but reported as direct dependencies by the extension'
        ' (may cause the build to fail):\nmissing_dep',
        stderr,
    )
    self.assertNotIn(
        'Imported, but reported as indirect dependencies by the'
        ' extension:\nindirect_dep',
        stderr,
    )
    self.assertNotIn(
        'Imported, but reported as indirect dependencies by the'
        ' extension:\nindirect_dep',
        stderr,
    )
    self.assertNotIn(
        'ERROR: module extension "lockfile_ext" from "//:extension.bzl" does'
        ' not generate repository "invalid_dep"',
        stderr,
    )

  def testLockfileRecreatedAfterDeletion(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    repo_rule(name="hello")',
            '',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl,',
            ')',
        ],
    )

    self.RunBazel(['build', '@hello//:all'])

    # Return the lockfile to the state it had before the
    # previous build: it didn't exist.
    with open('MODULE.bazel.lock', 'r') as lock_file:
      old_data = lock_file.read()
    os.remove('MODULE.bazel.lock')

    self.RunBazel(['build', '@hello//:all'])

    with open('MODULE.bazel.lock', 'r') as lock_file:
      new_data = lock_file.read()

    self.assertEqual(old_data, new_data)

  def testLockfileRecreationUsesPreviousBuildResults(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext1 = use_extension("extension.bzl", "lockfile_ext1")',
            'use_repo(lockfile_ext1, "hello1")',
            'lockfile_ext2 = use_extension("extension.bzl", "lockfile_ext2")',
            'use_repo(lockfile_ext2, "hello2")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext1_impl(ctx):',
            '    repo_rule(name="hello1")',
            '',
            'lockfile_ext1 = module_extension(',
            '    implementation=_module_ext1_impl,',
            ')',
            '',
            'def _module_ext2_impl(ctx):',
            '    repo_rule(name="hello2")',
            '',
            'lockfile_ext2 = module_extension(',
            '    implementation=_module_ext2_impl,',
            ')',
        ],
    )

    self.RunBazel(['build', '@hello1//:all'])
    # Return the lockfile to the state it had before the build: it didn't exist.
    os.remove('MODULE.bazel.lock')

    # Perform an intermediate build that evaluates more extensions.
    self.RunBazel(['build', '@hello1//:all', '@hello2//:all'])
    with open('MODULE.bazel.lock', 'r') as lock_file:
      old_data = lock_file.read()
    # Return the lockfile to the state it had before the build: it didn't exist.
    os.remove('MODULE.bazel.lock')

    # Rerun the original build with just ext1 and verify that extension results
    # from intermediate builds are reused.
    self.RunBazel(['build', '@hello1//:all'])
    with open('MODULE.bazel.lock', 'r') as lock_file:
      new_data = lock_file.read()

    self.assertEqual(old_data, new_data)

  def testLockfileRecreationOnlyUsesUpToDateBuildResults(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext1 = use_extension("extension.bzl", "lockfile_ext1")',
            'use_repo(lockfile_ext1, "hello1")',
            'lockfile_ext2 = use_extension("extension.bzl", "lockfile_ext2")',
            'use_repo(lockfile_ext2, "hello2")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext1_impl(ctx):',
            '    repo_rule(name="hello1")',
            '',
            'lockfile_ext1 = module_extension(',
            '    implementation=_module_ext1_impl,',
            ')',
            '',
            'def _module_ext2_impl(ctx):',
            '    repo_rule(name="hello2")',
            '',
            'lockfile_ext2 = module_extension(',
            '    environ = ["FOO"],    implementation=_module_ext2_impl,',
            ')',
        ],
    )

    self.RunBazel(['build', '@hello1//:all'])
    with open('MODULE.bazel.lock', 'r') as lock_file:
      old_data = lock_file.read()
    # Return the lockfile to the state it had before the build: it didn't exist.
    os.remove('MODULE.bazel.lock')

    # Perform an intermediate build that evaluates more extensions.
    self.RunBazel(['build', '@hello1//:all', '@hello2//:all'])
    # Return the lockfile to the state it had before the build: it didn't exist.
    os.remove('MODULE.bazel.lock')

    # Rerun the original build with just ext1, but invalidate ext2. The
    # intermediate build result should not be reused.
    self.RunBazel(['build', '--repo_env=FOO=invalidate_ext2', '@hello1//:all'])
    with open('MODULE.bazel.lock', 'r') as lock_file:
      new_data = lock_file.read()

    self.assertEqual(old_data, new_data)

  def testLockfileExtensionsUpdatedIncrementally(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext1 = use_extension("extension.bzl", "lockfile_ext1")',
            'use_repo(lockfile_ext1, "hello1")',
            'lockfile_ext2 = use_extension("extension.bzl", "lockfile_ext2")',
            'use_repo(lockfile_ext2, "hello2")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext1_impl(ctx):',
            '    print("Hello from ext1!")',
            '    repo_rule(name="hello1")',
            '',
            'lockfile_ext1 = module_extension(',
            '    implementation=_module_ext1_impl,',
            ')',
            '',
            'def _module_ext2_impl(ctx):',
            '    print("Hello from ext2!")',
            '    repo_rule(name="hello2")',
            '',
            'lockfile_ext2 = module_extension(',
            '    implementation=_module_ext2_impl,',
            ')',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@hello1//:all'])
    stderr = '\n'.join(stderr)
    self.assertIn('Hello from ext1!', stderr)
    self.assertNotIn('Hello from ext2!', stderr)

    self.RunBazel(['shutdown'])

    _, _, stderr = self.RunBazel(['build', '@hello1//:all', '@hello2//:all'])
    stderr = '\n'.join(stderr)
    self.assertNotIn('Hello from ext1!', stderr)
    self.assertIn('Hello from ext2!', stderr)

    self.RunBazel(['shutdown'])

    _, _, stderr = self.RunBazel(['build', '@hello1//:all', '@hello2//:all'])
    stderr = '\n'.join(stderr)
    self.assertNotIn('Hello from ext1!', stderr)
    self.assertNotIn('Hello from ext2!', stderr)

  def testExtensionOsAndArch(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    repo_rule(name="hello")',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl',
            ')',
        ],
    )

    # build to generate lockfile with this extension
    self.RunBazel(['build', '@hello//:all'])

    # validate an extension named 'general' is created
    general_key = 'general'
    ext_key = '//:extension.bzl%lockfile_ext'
    with open('MODULE.bazel.lock', 'r') as json_file:
      lockfile = json.load(json_file)
      self.assertIn(ext_key, lockfile['moduleExtensions'])
      extension_map = lockfile['moduleExtensions'][ext_key]
      self.assertIn(general_key, extension_map)

    # replace general extension with one depend on os and arch
    win_key = 'os:WinWin,arch:arch32'
    with open('MODULE.bazel.lock', 'w') as json_file:
      extension_map[win_key] = extension_map.pop(general_key)
      json.dump(lockfile, json_file, indent=4)

    # update extension to depend on OS and arch.
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    repo_rule(name="hello")',
            'lockfile_ext = module_extension(',
            (
                'implementation=_module_ext_impl, os_dependent=True, '
                'arch_dependent=True'
            ),
            ')',
        ],
    )

    # build again to update the file
    self.RunBazel(['build', '@hello//:all'])
    # assert win_key still exists and another one was added
    with open('MODULE.bazel.lock', 'r') as json_file:
      lockfile = json.load(json_file)
      extension_map = lockfile['moduleExtensions'][ext_key]
      self.assertIn(win_key, extension_map)
      self.assertEqual(len(extension_map), 2)
      added_key = ''
      for key in extension_map.keys():
        if key != win_key:
          added_key = key

    # update extension to only depend on os only
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    repo_rule(name="hello")',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl, os_dependent=True',
            ')',
        ],
    )

    # build again to update the file
    self.RunBazel(['build', '@hello//:all'])
    # assert both win_key and the added_key before are deleted,
    # and a new one without arch exists
    with open('MODULE.bazel.lock', 'r') as json_file:
      lockfile = json.load(json_file)
      extension_map = lockfile['moduleExtensions'][ext_key]
      self.assertNotIn(win_key, extension_map)
      self.assertNotIn(added_key, extension_map)
      self.assertEqual(len(extension_map), 1)

  def testExtensionEvaluationOnlyRerunOnRelevantUsagesChanges(self):
    self.main_registry.createCcModule('aaa', '1.0')

    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext_1 = use_extension("extension.bzl", "ext_1")',
            'ext_1.tag()',
            'use_repo(ext_1, ext_1_dep = "dep")',
            'ext_2 = use_extension("extension.bzl", "ext_2")',
            'ext_2.tag()',
            'use_repo(ext_2, ext_2_dep = "dep")',
            'ext_3 = use_extension("extension.bzl", "ext_3")',
            'use_repo(ext_3, ext_3_dep = "dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "exports_files([\\"data.txt\\"])")',
            '    ctx.file("data.txt", ctx.attr.value)',
            '    print(ctx.attr.value)',
            '',
            'repo_rule = repository_rule(',
            '    implementation=_repo_rule_impl,',
            '    attrs = {"value": attr.string()},)',
            '',
            'def _ext_1_impl(ctx):',
            '    print("Ext 1 is being evaluated")',
            (
                '    num_tags = len([tag for mod in ctx.modules for tag in'
                ' mod.tags.tag])'
            ),
            '    repo_rule(name="dep", value="Ext 1 saw %s tags" % num_tags)',
            '',
            'ext_1 = module_extension(',
            '    implementation=_ext_1_impl,',
            '    tag_classes={"tag": tag_class()}',
            ')',
            '',
            'def _ext_2_impl(ctx):',
            '    print("Ext 2 is being evaluated")',
            (
                '    num_tags = len([tag for mod in ctx.modules for tag in'
                ' mod.tags.tag])'
            ),
            '    repo_rule(name="dep", value="Ext 2 saw %s tags" % num_tags)',
            '',
            'ext_2 = module_extension(',
            '    implementation=_ext_2_impl,',
            '    tag_classes={"tag": tag_class()}',
            ')',
            '',
            'def _ext_3_impl(ctx):',
            '    print("Ext 3 is being evaluated")',
            (
                '    num_tags = len([tag for mod in ctx.modules for tag in'
                ' mod.tags.tag])'
            ),
            '    repo_rule(name="dep", value="Ext 3 saw %s tags" % num_tags)',
            '',
            'ext_3 = module_extension(',
            '    implementation=_ext_3_impl,',
            '    tag_classes={"tag": tag_class()}',
            ')',
        ],
    )

    # Trigger evaluation of all extensions.
    _, _, stderr = self.RunBazel(
        ['build', '@ext_1_dep//:all', '@ext_2_dep//:all', '@ext_3_dep//:all']
    )
    stderr = '\n'.join(stderr)

    self.assertIn('Ext 1 is being evaluated', stderr)
    self.assertIn('Ext 1 saw 1 tags', stderr)
    self.assertIn('Ext 2 is being evaluated', stderr)
    self.assertIn('Ext 2 saw 1 tags', stderr)
    self.assertIn('Ext 3 is being evaluated', stderr)
    self.assertIn('Ext 3 saw 0 tags', stderr)
    ext_1_key = '//:extension.bzl%ext_1'
    ext_2_key = '//:extension.bzl%ext_2'
    ext_3_key = '//:extension.bzl%ext_3'
    with open('MODULE.bazel.lock', 'r') as json_file:
      lockfile = json.load(json_file)
    self.assertIn(ext_1_key, lockfile['moduleExtensions'])
    self.assertIn(
        'Ext 1 saw 1 tags',
        lockfile['moduleExtensions'][ext_1_key]['general'][
            'generatedRepoSpecs'
        ]['dep']['attributes']['value'],
    )
    self.assertIn(ext_2_key, lockfile['moduleExtensions'])
    self.assertIn(
        'Ext 2 saw 1 tags',
        lockfile['moduleExtensions'][ext_2_key]['general'][
            'generatedRepoSpecs'
        ]['dep']['attributes']['value'],
    )
    self.assertIn(ext_3_key, lockfile['moduleExtensions'])
    self.assertIn(
        'Ext 3 saw 0 tags',
        lockfile['moduleExtensions'][ext_3_key]['general'][
            'generatedRepoSpecs'
        ]['dep']['attributes']['value'],
    )

    # Shut down bazel to empty the cache, modify the MODULE.bazel
    # file in a way that does not affect the resolution of ext_1,
    # but requires rerunning module resolution and removes ext_3, then
    # trigger module resolution without evaluating any of the extensions.
    self.RunBazel(['shutdown'])
    self.ScratchFile(
        'MODULE.bazel',
        [
            '# Added a dep to force rerunning module resolution.',
            'bazel_dep(name = "aaa", version = "1.0")',
            (
                '# The usage of ext_1 is unchanged except for locations and'
                ' imports.'
            ),
            'ext_1 = use_extension("extension.bzl", "ext_1")',
            'ext_1.tag()',
            'use_repo(ext_1, ext_1_dep_new_name = "dep")',
            '# The usage of ext_2 has a new tag.',
            'ext_2 = use_extension("extension.bzl", "ext_2")',
            'ext_2.tag()',
            'ext_2.tag()',
            'use_repo(ext_2, ext_2_dep = "dep")',
            '# The usage of ext_3 has been removed.',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '//:all'])
    stderr = '\n'.join(stderr)

    self.assertNotIn('Ext 1 is being evaluated', stderr)
    self.assertNotIn('Ext 2 is being evaluated', stderr)
    self.assertNotIn('Ext 3 is being evaluated', stderr)
    with open('MODULE.bazel.lock', 'r') as json_file:
      lockfile = json.load(json_file)
    # The usages of ext_1 did not change.
    self.assertIn(ext_1_key, lockfile['moduleExtensions'])
    self.assertIn(
        'Ext 1 saw 1 tags',
        lockfile['moduleExtensions'][ext_1_key]['general'][
            'generatedRepoSpecs'
        ]['dep']['attributes']['value'],
    )
    # The only usage of ext_3 was removed.
    self.assertNotIn(ext_3_key, lockfile['moduleExtensions'])

    # Trigger evaluation of all remaining extensions.
    _, _, stderr = self.RunBazel(
        ['build', '@ext_1_dep_new_name//:all', '@ext_2_dep//:all']
    )
    stderr = '\n'.join(stderr)

    self.assertNotIn('Ext 1 is being evaluated', stderr)
    self.assertIn('Ext 2 is being evaluated', stderr)
    self.assertIn('Ext 2 saw 2 tags', stderr)
    ext_1_key = '//:extension.bzl%ext_1'
    ext_2_key = '//:extension.bzl%ext_2'
    with open('MODULE.bazel.lock', 'r') as json_file:
      lockfile = json.load(json_file)
    self.assertIn(ext_1_key, lockfile['moduleExtensions'])
    self.assertIn(
        'Ext 1 saw 1 tags',
        lockfile['moduleExtensions'][ext_1_key]['general'][
            'generatedRepoSpecs'
        ]['dep']['attributes']['value'],
    )
    self.assertIn(ext_2_key, lockfile['moduleExtensions'])
    self.assertIn(
        'Ext 2 saw 2 tags',
        lockfile['moduleExtensions'][ext_2_key]['general'][
            'generatedRepoSpecs'
        ]['dep']['attributes']['value'],
    )
    self.assertNotIn(ext_3_key, lockfile['moduleExtensions'])

  def testLockfileWithNoUserSpecificPath(self):
    self.my_registry = BazelRegistry(os.path.join(self._test_cwd, 'registry'))
    self.my_registry.start()
    try:
      self.my_registry.setModuleBasePath('projects')
      patch_file = self.ScratchFile(
          'ss.patch',
          [
              '--- a/aaa.cc',
              '+++ b/aaa.cc',
              '@@ -1,6 +1,6 @@',
              ' #include <stdio.h>',
              ' #include "aaa.h"',
              ' void hello_aaa(const std::string& caller) {',
              '-    std::string lib_name = "aaa@1.1-1";',
              '+    std::string lib_name = "aaa@1.1-1 (remotely patched)";',
              '     printf("%s => %s\\n", caller.c_str(), lib_name.c_str());',
              ' }',
          ],
      )
      # Module with a local patch & extension
      self.my_registry.createCcModule(
          'ss',
          '1.3-1',
          {'ext': '1.0'},
          patches=[patch_file],
          patch_strip=1,
          extra_module_file_contents=[
              'my_ext = use_extension("@ext//:ext.bzl", "ext")',
              'use_repo(my_ext, "justRepo")',
          ],
      )
      ext_src = [
          'def _repo_impl(ctx): ctx.file("BUILD")',
          'repo = repository_rule(_repo_impl)',
          'def _ext_impl(ctx): repo(name=justRepo)',
          'ext=module_extension(_ext_impl)',
      ]
      self.my_registry.createLocalPathModule('ext', '1.0', 'ext')
      scratchFile(self.my_registry.projects.joinpath('ext', 'BUILD'))
      scratchFile(self.my_registry.projects.joinpath('ext', 'ext.bzl'), ext_src)

      self.ScratchFile(
          'MODULE.bazel',
          [
              'bazel_dep(name = "ss", version = "1.3-1")',
          ],
      )
      self.ScratchFile('BUILD.bazel', ['filegroup(name = "lala")'])
      self.RunBazel(
          ['build', '--registry=file:///%workspace%/registry', '//:lala']
      )

      with open('MODULE.bazel.lock', 'r') as f:
        self.assertNotIn(self.my_registry.getURL(), f.read())

    finally:
      self.my_registry.stop()

  def testExtensionEvaluationRerunsIfDepGraphOrderChanges(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name = "root", version = "1.0")',
            'bazel_dep(name = "aaa", version = "1.0")',
            'bazel_dep(name = "bbb", version = "1.0")',
            'ext = use_extension("extension.bzl", "ext")',
            'ext.tag(value = "root")',
            'use_repo(ext, "dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "exports_files([\\"data.txt\\"])")',
            '    ctx.file("data.txt", ctx.attr.value)',
            '    print(ctx.attr.value)',
            '',
            'repo_rule = repository_rule(',
            '    implementation=_repo_rule_impl,',
            '    attrs = {"value": attr.string()},)',
            '',
            'def _ext_impl(ctx):',
            '    print("Ext is being evaluated")',
            (
                '    values = ",".join([tag.value for mod in ctx.modules for'
                ' tag in mod.tags.tag])'
            ),
            '    repo_rule(name="dep", value="Ext saw values: " + values)',
            '',
            'ext = module_extension(',
            '    implementation=_ext_impl,',
            (
                '    tag_classes={"tag": tag_class(attrs={"value":'
                ' attr.string()})}'
            ),
            ')',
        ],
    )
    self.main_registry.createCcModule(
        'aaa',
        '1.0',
        extra_module_file_contents=[
            'bazel_dep(name = "root", version = "1.0")',
            'ext = use_extension("@root//:extension.bzl", "ext")',
            'ext.tag(value = "aaa")',
        ],
    )
    self.main_registry.createCcModule(
        'bbb',
        '1.0',
        extra_module_file_contents=[
            'bazel_dep(name = "root", version = "1.0")',
            'ext = use_extension("@root//:extension.bzl", "ext")',
            'ext.tag(value = "bbb")',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@dep//:all'])
    stderr = '\n'.join(stderr)

    self.assertIn('Ext is being evaluated', stderr)
    self.assertIn('Ext saw values: root,aaa,bbb', stderr)
    ext_key = '//:extension.bzl%ext'
    with open('MODULE.bazel.lock', 'r') as json_file:
      lockfile = json.load(json_file)
    self.assertIn(ext_key, lockfile['moduleExtensions'])
    self.assertIn(
        'Ext saw values: root,aaa,bbb',
        lockfile['moduleExtensions'][ext_key]['general']['generatedRepoSpecs'][
            'dep'
        ]['attributes']['value'],
    )

    # Shut down bazel to empty the cache, modify the MODULE.bazel
    # file in a way that only changes the order of the bazel_deps
    # on aaa and bbb, which results in their usages being ordered
    # differently in module_ctx.modules, which is visible to the
    # extension. Rerun a build that does not trigger evaluation
    # of the extension.
    self.RunBazel(['shutdown'])
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name = "root", version = "1.0")',
            'bazel_dep(name = "bbb", version = "1.0")',
            'bazel_dep(name = "aaa", version = "1.0")',
            'ext = use_extension("extension.bzl", "ext")',
            'ext.tag(value = "root")',
            'use_repo(ext, "dep")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '//:all'])
    self.assertNotIn('Ext is being evaluated', '\n'.join(stderr))

    # Trigger evaluation of the extension.
    _, _, stderr = self.RunBazel(['build', '@dep//:all'])
    stderr = '\n'.join(stderr)

    self.assertIn('Ext is being evaluated', stderr)
    self.assertIn('Ext saw values: root,bbb,aaa', stderr)
    ext_key = '//:extension.bzl%ext'
    with open('MODULE.bazel.lock', 'r') as json_file:
      lockfile = json.load(json_file)
    self.assertIn(ext_key, lockfile['moduleExtensions'])
    self.assertIn(
        'Ext saw values: root,bbb,aaa',
        lockfile['moduleExtensions'][ext_key]['general']['generatedRepoSpecs'][
            'dep'
        ]['attributes']['value'],
    )

  def testExtensionRepoMappingChange(self):
    # Regression test for #20721
    self.main_registry.createCcModule('foo', '1.0')
    self.main_registry.createCcModule('bar', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0",repo_name="repo_name")',
            'bazel_dep(name="bar",version="1.0")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile(
        'BUILD.bazel',
        [
            'load("@repo//:defs.bzl", "STR")',
            'print("STR="+STR)',
            'filegroup(name="lol")',
        ],
    )
    self.ScratchFile(
        'ext.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD")',
            '  rctx.file("defs.bzl", "STR = " + repr(str(rctx.attr.value)))',
            'repo = repository_rule(_repo_impl,attrs={"value":attr.label()})',
            'def _ext_impl(mctx):',
            '  print("ran the extension!")',
            '  repo(name = "repo", value = Label("@repo_name//:lib_foo"))',
            'ext = module_extension(_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertIn('STR=@@foo~//:lib_foo', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertNotIn('ran the extension!', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    # Now, for something spicy: let repo_name point to bar and change nothing
    # else. The extension should rerun despite the lockfile being present, and
    # no usages or .bzl files having changed.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0")',
            'bazel_dep(name="bar",version="1.0",repo_name="repo_name")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', ':lol'])
    stderr = '\n'.join(stderr)
    self.assertIn('ran the extension!', stderr)
    self.assertIn('STR=@@bar~//:lib_foo', stderr)

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    # More spicy! change the repo_name of foo, but nothing else.
    # The extension should NOT rerun, since it never used the @other_name repo
    # mapping.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0",repo_name="other_name")',
            'bazel_dep(name="bar",version="1.0",repo_name="repo_name")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', ':lol'])
    stderr = '\n'.join(stderr)
    self.assertNotIn('ran the extension!', stderr)
    self.assertIn('STR=@@bar~//:lib_foo', stderr)

  def testExtensionRepoMappingChange_BzlInit(self):
    # Regression test for #20721; same test as above, except that the call to
    # Label() in ext.bzl is now done at bzl load time.
    self.main_registry.createCcModule('foo', '1.0')
    self.main_registry.createCcModule('bar', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0",repo_name="repo_name")',
            'bazel_dep(name="bar",version="1.0")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile(
        'BUILD.bazel',
        [
            'load("@repo//:defs.bzl", "STR")',
            'print("STR="+STR)',
            'filegroup(name="lol")',
        ],
    )
    self.ScratchFile(
        'ext.bzl',
        [
            'constant = Label("@repo_name//:lib_foo")',
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD")',
            '  rctx.file("defs.bzl", "STR = " + repr(str(rctx.attr.value)))',
            'repo = repository_rule(_repo_impl,attrs={"value":attr.label()})',
            'def _ext_impl(mctx):',
            '  print("ran the extension!")',
            '  repo(name = "repo", value = constant)',
            'ext = module_extension(_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertIn('STR=@@foo~//:lib_foo', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertNotIn('ran the extension!', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    # Now, for something spicy: let repo_name point to bar and change nothing
    # else. The extension should rerun despite the lockfile being present, and
    # no usages or .bzl files having changed.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0")',
            'bazel_dep(name="bar",version="1.0",repo_name="repo_name")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', ':lol'])
    stderr = '\n'.join(stderr)
    self.assertIn('ran the extension!', stderr)
    self.assertIn('STR=@@bar~//:lib_foo', stderr)

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    # More spicy! change the repo_name of foo, but nothing else.
    # The extension should NOT rerun, since it never used the @other_name repo
    # mapping.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0",repo_name="other_name")',
            'bazel_dep(name="bar",version="1.0",repo_name="repo_name")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', ':lol'])
    stderr = '\n'.join(stderr)
    self.assertNotIn('ran the extension!', stderr)
    self.assertIn('STR=@@bar~//:lib_foo', stderr)

  def testExtensionRepoMappingChange_tag(self):
    # Regression test for #20721
    self.main_registry.createCcModule('foo', '1.0')
    self.main_registry.createCcModule('bar', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0",repo_name="repo_name")',
            'bazel_dep(name="bar",version="1.0")',
            'ext = use_extension(":ext.bzl", "ext")',
            'ext.tag(label = "@repo_name//:lib_foo")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile(
        'BUILD.bazel',
        [
            'load("@repo//:defs.bzl", "STR")',
            'print("STR="+STR)',
            'filegroup(name="lol")',
        ],
    )
    self.ScratchFile(
        'ext.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD")',
            '  rctx.file("defs.bzl", "STR = " + repr(str(rctx.attr.value)))',
            'repo = repository_rule(_repo_impl,attrs={"value":attr.label()})',
            'def _ext_impl(mctx):',
            '  print("ran the extension!")',
            '  repo(name = "repo", value = mctx.modules[0].tags.tag[0].label)',
            'tag = tag_class(',
            '  attrs = {',
            '    "label": attr.label(),',
            '  },',
            ')',
            'ext = module_extension(_ext_impl, tag_classes={"tag": tag})',
        ],
    )

    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertIn('STR=@@foo+//:lib_foo', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertNotIn('ran the extension!', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    # Now, for something spicy: let repo_name point to bar and change nothing
    # else. The extension should rerun despite the lockfile being present, and
    # no usages or .bzl files having changed.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0")',
            'bazel_dep(name="bar",version="1.0",repo_name="repo_name")',
            'ext = use_extension(":ext.bzl", "ext")',
            'ext.tag(label = "@repo_name//:lib_foo")',
            'use_repo(ext, "repo")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', ':lol'])
    stderr = '\n'.join(stderr)
    self.assertIn('ran the extension!', stderr)
    self.assertIn('STR=@@bar+//:lib_foo', stderr)

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    # More spicy! change the repo_name of foo, but nothing else.
    # The extension should NOT rerun, since it never used the @other_name repo
    # mapping.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0",repo_name="other_name")',
            'bazel_dep(name="bar",version="1.0",repo_name="repo_name")',
            'ext = use_extension(":ext.bzl", "ext")',
            'ext.tag(label = "@repo_name//:lib_foo")',
            'use_repo(ext, "repo")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', ':lol'])
    stderr = '\n'.join(stderr)
    self.assertNotIn('ran the extension!', stderr)
    self.assertIn('STR=@@bar+//:lib_foo', stderr)

  def testExtensionRepoMappingChange_loadsAndRepoRelativeLabels(self):
    # Regression test for #20721; same test as above, except that the call to
    # Label() in ext.bzl is now moved to @{foo,bar}//:defs.bzl and doesn't
    # itself use repo mapping (ie. is a repo-relative label).
    self.main_registry.setModuleBasePath('projects')
    projects_dir = self.main_registry.projects

    self.main_registry.createLocalPathModule('foo', '1.0', 'foo')
    scratchFile(projects_dir.joinpath('foo', 'BUILD'))
    scratchFile(
        projects_dir.joinpath('foo', 'defs.bzl'),
        ['constant=Label("//:BUILD")'],
    )
    self.main_registry.createLocalPathModule('bar', '1.0', 'bar')
    scratchFile(projects_dir.joinpath('bar', 'BUILD'))
    # Exactly the same as foo!
    scratchFile(
        projects_dir.joinpath('bar', 'defs.bzl'),
        ['constant=Label("//:BUILD")'],
    )

    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0",repo_name="repo_name")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile(
        'BUILD.bazel',
        [
            'load("@repo//:defs.bzl", "STR")',
            'print("STR="+STR)',
            'filegroup(name="lol")',
        ],
    )
    self.ScratchFile(
        'ext.bzl',
        [
            'load("@repo_name//:defs.bzl", "constant")',
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD")',
            '  rctx.file("defs.bzl", "STR = " + repr(str(rctx.attr.value)))',
            'repo = repository_rule(_repo_impl,attrs={"value":attr.label()})',
            'def _ext_impl(mctx):',
            '  print("ran the extension!")',
            '  repo(name = "repo", value = constant)',
            'ext = module_extension(_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertIn('STR=@@foo~//:BUILD', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertNotIn('ran the extension!', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    # Now, for something spicy: change repo_name to point to bar.
    # The extension should rerun despite the lockfile being present, and no
    # usages or .bzl files having changed.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="bar",version="1.0",repo_name="repo_name")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertIn('STR=@@bar~//:BUILD', '\n'.join(stderr))

  def testExtensionRepoMappingChange_sourceRepoNoLongerExistent(self):
    # Regression test for #20721; verify that an old recorded repo mapping entry
    # with a source repo that doesn't exist anymore doesn't cause a crash.
    self.main_registry.createCcModule('foo', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile(
        'BUILD.bazel',
        [
            'load("@repo//:defs.bzl", "STR")',
            'print("STR="+STR)',
            'filegroup(name="lol")',
        ],
    )
    self.ScratchFile(
        'ext.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD")',
            '  rctx.file("defs.bzl", "STR = " + repr(str(rctx.attr.value)))',
            'repo = repository_rule(_repo_impl,attrs={"value":attr.label()})',
            'def _ext_impl(mctx):',
            '  print("ran the extension!")',
            '  repo(name = "repo", value = Label("@foo//:lib_foo"))',
            'ext = module_extension(_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertIn('STR=@@foo~//:lib_foo', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', ':lol'])
    self.assertNotIn('ran the extension!', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    # Now, for something spicy: edit the lockfile to add a recorded repo mapping
    # with a source repo that doesn't exist. Editing the lockfile is much easier
    # than setting up this situation with an actual dep graph.
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      self.assertIn('//:ext.bzl%ext', lockfile['moduleExtensions'])
      extension = lockfile['moduleExtensions']['//:ext.bzl%ext']['general']
      self.assertIn('recordedRepoMappingEntries', extension)
      extension['recordedRepoMappingEntries'].append(
          ['_unknown_source_repo', 'other_name', 'bar~']
      )

    with open(self.Path('MODULE.bazel.lock'), 'w') as f:
      json.dump(lockfile, f, indent=2)

    # Verify that the extension is reevaluated without a crash.
    _, _, stderr = self.RunBazel(['build', ':lol'])
    stderr = '\n'.join(stderr)
    self.assertIn('ran the extension!', stderr)
    self.assertIn('STR=@@foo~//:lib_foo', stderr)

  def testExtensionRepoMappingChange_mainRepoEvalCycleWithWorkspace(self):
    # Regression test for #20942
    self.main_registry.createCcModule('foo', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="foo",version="1.0")',
            'ext = use_extension(":ext.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile(
        'BUILD.bazel',
        [
            'load("@repo//:defs.bzl", "STR")',
            'print("STR="+STR)',
            'filegroup(name="lol")',
        ],
    )
    self.ScratchFile(
        'ext.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD")',
            '  rctx.file("defs.bzl", "STR = " + repr(str(rctx.attr.value)))',
            'repo = repository_rule(_repo_impl,attrs={"value":attr.label()})',
            'def _ext_impl(mctx):',
            '  print("ran the extension!")',
            '  repo(name = "repo", value = Label("@foo//:lib_foo"))',
            'ext = module_extension(_ext_impl)',
        ],
    )
    # any `load` in WORKSPACE should trigger the bug
    self.ScratchFile('WORKSPACE.bzlmod', ['load("@repo//:defs.bzl","STR")'])

    _, _, stderr = self.RunBazel(['build', '--enable_workspace', ':lol'])
    self.assertIn('STR=@@foo~//:lib_foo', '\n'.join(stderr))

    # Shutdown bazel to make sure we rely on the lockfile and not skyframe
    self.RunBazel(['shutdown'])
    # Build again. This should _NOT_ trigger a failure!
    _, _, stderr = self.RunBazel(['build', '--enable_workspace', ':lol'])
    self.assertNotIn('ran the extension!', '\n'.join(stderr))

  def testReproducibleExtensionsIgnoredInLockfile(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext1 = use_extension("extension.bzl", "ext1")',
            'ext2 = use_extension("extension.bzl", "ext2")',
            'use_repo(ext1, "repo1")',
            'use_repo(ext2, "repo2")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _should_lock_impl(ctx): repo_rule(name="repo1")',
            'def _no_lock_impl(ctx):',
            '    repo_rule(name="repo2")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=[],',
            '        root_module_direct_dev_deps=[],',
            '        reproducible=True',
            '    )',
            'ext1 = module_extension(implementation=_should_lock_impl)',
            'ext2 = module_extension(implementation=_no_lock_impl)',
        ],
    )

    self.RunBazel(['build', '@repo1//:all', '@repo2//:all'])
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      self.assertIn('//:extension.bzl%ext1', lockfile['moduleExtensions'])
      self.assertNotIn('//:extension.bzl%ext2', lockfile['moduleExtensions'])

    # Update extensions implementations to the opposite
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _should_lock_impl(ctx): repo_rule(name="repo2")',
            'def _no_lock_impl(ctx):',
            '    repo_rule(name="repo1")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=[],',
            '        root_module_direct_dev_deps=[],',
            '        reproducible=True',
            '    )',
            'ext1 = module_extension(implementation=_no_lock_impl)',
            'ext2 = module_extension(implementation=_should_lock_impl)',
        ],
    )

    # Assert updates in the lockfile
    self.RunBazel(['build', '@repo1//:all', '@repo2//:all'])
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      self.assertNotIn('//:extension.bzl%ext1', lockfile['moduleExtensions'])
      self.assertIn('//:extension.bzl%ext2', lockfile['moduleExtensions'])

  def testReproducibleExtensionInLockfileErrorMode(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="repo")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=[],',
            '        root_module_direct_dev_deps=[],',
            '        reproducible=True',
            '    )',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    self.RunBazel(['build', '@repo//:all'])
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = json.loads(f.read().strip())
      self.assertNotIn('//:extension.bzl%ext', lockfile['moduleExtensions'])

    # Assert ext does NOT fail in error mode
    self.RunBazel(['build', '@repo//:all', '--lockfile_mode=error'])

    # Update extension to not be reproducible
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx): repo_rule(name="repo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Assert ext does FAIL in error mode
    _, _, stderr = self.RunBazel(
        ['build', '@repo//:all', '--lockfile_mode=error'], allow_failure=True
    )
    self.assertIn(
        'ERROR: The module extension '
        "'ModuleExtensionId{bzlFileLabel=//:extension.bzl, "
        "extensionName=ext, isolationKey=Optional.empty}' does "
        'not exist in the lockfile',
        stderr,
    )

  def testWatchingFileUnderWorkingDirectoryFails(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    ctx.file("hello", "repo")',
            '    repo_rule(name=ctx.read("hello", watch="yes"))',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@repo'], allow_failure=True)
    self.assertIn(
        'attempted to watch path under working directory', '\n'.join(stderr)
    )

  def testWatchingFileOutsideWorkspaceFails(self):
    outside_file = pathlib.Path(tempfile.mkdtemp(dir=self._temp)).joinpath(
        'hello'
    )
    scratchFile(outside_file, ['repo'])
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name=ctx.read(%s, watch="yes"))'
            % repr(str(outside_file)),
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@repo'], allow_failure=True)
    self.assertIn(
        'attempted to watch path outside workspace', '\n'.join(stderr)
    )

  def testPathReaddirWatchesDirents(self):
    self.ScratchFile('some_dir/foo')
    self.ScratchFile('some_dir/bar')
    self.ScratchFile('some_dir/baz')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'repo\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    print("I see: " + ",".join(sorted([e.basename for e in'
            ' ctx.path(%s).readdir()])))'
            % repr(self.Path('some_dir')),
            '    repo_rule(name="repo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@repo'])
    self.assertIn('I see: bar,baz,foo', '\n'.join(stderr))

    # adding and removing entries should cause a reevaluation.
    os.remove(self.Path('some_dir/baz'))
    self.ScratchFile('some_dir/quux')
    _, _, stderr = self.RunBazel(['build', '@repo'])
    self.assertIn('I see: bar,foo,quux', '\n'.join(stderr))

    # but changing file contents shouldn't.
    self.ScratchFile('some_dir/bar', ['hulloooo'])
    _, _, stderr = self.RunBazel(['build', '@repo'])
    self.assertNotIn('I see:', '\n'.join(stderr))

  def testPathReaddirOutsideWorkspaceDoesNotWatchDirents(self):
    outside_dir = pathlib.Path(tempfile.mkdtemp(dir=self._temp))
    scratchFile(outside_dir.joinpath('whatever'), ['repo'])
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'repo\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    print("I see: " + ",".join(sorted([e.basename for e in'
            ' ctx.path(%s).readdir()])))'
            % repr(str(outside_dir)),
            '    repo_rule(name="repo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@repo'])
    self.assertIn('I see: whatever', '\n'.join(stderr))

    # since the directory in question is outside the workspace, adding and
    # removing entries shouldn't cause a reevaluation.
    os.remove(outside_dir.joinpath('whatever'))
    scratchFile(outside_dir.joinpath('anything'), ['kek'])
    _, _, stderr = self.RunBazel(['build', '@repo'])
    self.assertNotIn('I see:', '\n'.join(stderr))

  def testForceWatchingDirentsOutsideWorkspaceFails(self):
    outside_dir = pathlib.Path(tempfile.mkdtemp(dir=self._temp))
    scratchFile(outside_dir.joinpath('whatever'), ['repo'])
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "repo")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'repo\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    print("I see: " + ",".join(sorted([e.basename for e in'
            ' ctx.path(%s).readdir(watch="yes")])))'
            % repr(str(outside_dir)),
            '    repo_rule(name="repo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@repo'], allow_failure=True)
    self.assertIn(
        'attempted to watch path outside workspace', '\n'.join(stderr)
    )

  def testModuleExtensionRerunsOnNameChange(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            (
                'module(name = "old_name", version = "1.2.3", repo_name ='
                ' "fixed_name")'
            ),
            'lockfile_ext = use_extension("//:extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            '    print("I am running the extension: " + ctx.modules[0].name)',
            '    repo_rule(name="hello")',
            '',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl',
            ')',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = ''.join(stderr)
    self.assertIn('I am running the extension: old_name', stderr)

    # Shutdown bazel to empty cache and run with no changes
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = ''.join(stderr)
    self.assertNotIn('I am running the extension: old_name', stderr)

    # Update module name and rerun
    self.RunBazel(['shutdown'])
    self.ScratchFile(
        'MODULE.bazel',
        [
            (
                'module(name = "new_name", version = "1.2.3", repo_name ='
                ' "fixed_name")'
            ),
            'lockfile_ext = use_extension("//:extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = ''.join(stderr)
    self.assertIn('I am running the extension: new_name', stderr)

  def testModuleExtensionRerunsOnVersionChange(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name = "old_name", version = "1.2.3")',
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            (
                '    print("I am running the extension: " +'
                ' ctx.modules[0].version)'
            ),
            '    repo_rule(name="hello")',
            '',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl',
            ')',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = ''.join(stderr)
    self.assertIn('I am running the extension: 1.2.3', stderr)

    # Shutdown bazel to empty cache and run with no changes
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = ''.join(stderr)
    self.assertNotIn('I am running the extension: 1.2.3', stderr)

    # Update module name and rerun
    self.RunBazel(['shutdown'])
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name = "old_name", version = "4.5.6")',
            'lockfile_ext = use_extension("extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = ''.join(stderr)
    self.assertIn('I am running the extension: 4.5.6', stderr)

  def testModuleExtensionRerunsOnGetenvChanges(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name = "old_name", version = "1.2.3")',
            'lockfile_ext = use_extension("//:extension.bzl", "lockfile_ext")',
            'use_repo(lockfile_ext, "hello")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _module_ext_impl(ctx):',
            (
                '    print("UNDECLARED_KEY=%s" %'
                ' ctx.os.environ.get("UNDECLARED_KEY"))'
            ),
            (
                '    print("PREDECLARED_KEY=%s" %'
                ' ctx.os.environ.get("PREDECLARED_KEY"))'
            ),
            '    print("LAZYEVAL_KEY=%s" % ctx.getenv("LAZYEVAL_KEY"))',
            '    repo_rule(name="hello")',
            '',
            'lockfile_ext = module_extension(',
            '    implementation=_module_ext_impl,',
            '    environ = ["PREDECLARED_KEY"],',
            ')',
        ],
    )

    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'UNDECLARED_KEY': 'val1'}
    )
    stderr = '\n'.join(stderr)
    self.assertIn('UNDECLARED_KEY=val1', stderr)

    # No reevaluation if undeclared env var changes
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'UNDECLARED_KEY': 'val2'}
    )
    stderr = '\n'.join(stderr)
    self.assertNotIn('UNDECLARED_KEY', stderr)

    # Reevaluation if predeclared env var is first set
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'PREDECLARED_KEY': 'val1'}
    )
    stderr = '\n'.join(stderr)
    self.assertIn('PREDECLARED_KEY=val1', stderr)

    # No reevaluation if predeclared env var does not change
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'PREDECLARED_KEY': 'val1'}
    )
    stderr = '\n'.join(stderr)
    self.assertNotIn('PREDECLARED_KEY', stderr)

    # Reevaluation if predeclared env var changes
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'PREDECLARED_KEY': 'val2'}
    )
    stderr = '\n'.join(stderr)
    self.assertIn('PREDECLARED_KEY=val2', stderr)

    # Reevaluation if predeclared env var becomes unset
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = '\n'.join(stderr)
    self.assertIn('PREDECLARED_KEY=None', stderr)

    # Reevaluation if lazily declared env var is first set
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'LAZYEVAL_KEY': 'val1'}
    )
    stderr = '\n'.join(stderr)
    self.assertIn('LAZYEVAL_KEY=val1', stderr)

    # No reevaluation if lazily declared env var does not change
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'LAZYEVAL_KEY': 'val1'}
    )
    stderr = '\n'.join(stderr)
    self.assertNotIn('LAZYEVAL_KEY', stderr)

    # Reevaluation if lazily declared env var changes
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all'], env_add={'LAZYEVAL_KEY': 'val2'}
    )
    stderr = '\n'.join(stderr)
    self.assertIn('LAZYEVAL_KEY=val2', stderr)

    # Reevaluation if lazily declared env var changes due to --repo_env.
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(
        ['build', '@hello//:all', '--repo_env=LAZYEVAL_KEY=val3'],
        env_add={'LAZYEVAL_KEY': 'val2'},
    )
    stderr = '\n'.join(stderr)
    self.assertIn('LAZYEVAL_KEY=val3', stderr)

    # Reevaluation if lazily declared env var becomes unset
    self.RunBazel(['shutdown'])
    _, _, stderr = self.RunBazel(['build', '@hello//:all'])
    stderr = '\n'.join(stderr)
    self.assertIn('LAZYEVAL_KEY=None', stderr)

  def testLockFileVersionIsCorrectWithUsePlusFlag(self):
    self.RunBazel(['mod', 'graph', '--incompatible_use_plus_in_repo_names'])
    self.RunBazel(['clean', '--expunge'])
    self.RunBazel(['mod', 'graph', '--incompatible_use_plus_in_repo_names',
                   '--lockfile_mode=error'])
  
  def testModuleExtensionRerunsOnUniqueNameChange(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'my_ext_1 = use_extension("//:ext1.bzl","my_ext")',
            'use_repo(my_ext_1, "foo")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'ext1.bzl',
        [
            'def _my_repo_impl(ctx):',
            '  ctx.file("REPO.bazel")',
            '  ctx.file("BUILD")',
            '  ctx.file("hi.txt", "hi")',
            '  if ctx.attr.label:',
            '    print("label: {}".format(ctx.attr.label))',
            'my_repo = repository_rule(',
            '  implementation=_my_repo_impl,',
            '  attrs={"label": attr.label()}',
            ')',
            'def _my_ext_impl(ctx):',
            '  my_repo(name="foo",label="@bar")',
            '  my_repo(name="bar")',
            'my_ext = module_extension(implementation=_my_ext_impl)',
        ],
    )
    self.ScratchFile(
        'ext2.bzl',
        [
            'load(":ext1.bzl", "my_repo")',
            'def _my_ext_impl(ctx):',
            '  my_repo(name="foo")',
            'my_ext = module_extension(implementation=_my_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@foo//:all'])
    stderr = '\n'.join(stderr)
    self.assertIn('label: @@_main~my_ext~bar//:bar\n', stderr)

    self.ScratchFile(
        'MODULE.bazel',
        [
            'my_ext_2 = use_extension("//:ext2.bzl","my_ext")',
            'use_repo(my_ext_2, other_foo = "foo")',
            'my_ext_1 = use_extension("//:ext1.bzl","my_ext")',
            'use_repo(my_ext_1, "foo")',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@foo//:all'])
    stderr = '\n'.join(stderr)
    self.assertIn('label: @@_main~my_ext2~bar//:bar\n', stderr)


if __name__ == '__main__':
  absltest.main()
