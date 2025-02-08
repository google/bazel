# pylint: disable=invalid-name
# pylint: disable=g-long-ternary
# Copyright 2021 The Bazel Authors. All rights reserved.
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
"""Test utils for Bzlmod."""

import base64
import functools
import hashlib
import http.server
import json
import os
import pathlib
import shutil
import threading
import urllib.request
import zipfile


def download(url):
  """Download a file and return its content in bytes."""
  with urllib.request.urlopen(url) as response:
    return response.read()


def read(path):
  """Read a file and return its content in bytes."""
  with open(str(path), 'rb') as f:
    return f.read()


def integrity(data):
  """Calculate the integration value of the data with sha256."""
  hash_value = hashlib.sha256(data)
  return 'sha256-' + base64.b64encode(hash_value.digest()).decode()


def scratchFile(path, lines=None):
  """Creates a file at the given path with the given content."""
  os.makedirs(str(path.parent), exist_ok=True)
  with open(str(path), 'w') as f:
    if lines:
      for l in lines:
        f.write(l)
        f.write('\n')


class Module:
  """A class to represent information of a Bazel module."""

  def __init__(self, name, version):
    self.name = name
    self.version = version
    self.archive_url = None
    self.strip_prefix = ''
    self.module_dot_bazel = None
    self.patches = []
    self.patch_strip = 0
    self.archive_type = None

  def set_source(self, archive_url, strip_prefix=None):
    self.archive_url = archive_url
    self.strip_prefix = strip_prefix
    return self

  def set_module_dot_bazel(self, module_dot_bazel):
    self.module_dot_bazel = module_dot_bazel
    return self

  def set_patches(self, patches, patch_strip):
    self.patches = patches
    self.patch_strip = patch_strip
    return self

  def set_archive_type(self, archive_type):
    self.archive_type = archive_type
    return self


class BazelRegistry:
  """A class to help create a Bazel module project from scatch and add it into the registry."""

  def __init__(self, root, registry_suffix=''):
    self.root = pathlib.Path(root)
    self.projects = self.root.joinpath('projects')
    self.projects.mkdir(parents=True, exist_ok=True)
    self.archives = self.root.joinpath('archives')
    self.archives.mkdir(parents=True, exist_ok=True)
    self.registry_suffix = registry_suffix
    self.http_server = StaticHTTPServer(self.root)

  def setModuleBasePath(self, module_base_path):
    bazel_registry = {
        'module_base_path': (
            self.root.joinpath(module_base_path).resolve().as_posix()
        ),
    }
    with self.root.joinpath('bazel_registry.json').open('w') as f:
      json.dump(bazel_registry, f, indent=4, sort_keys=True)

  def start(self):
    """Start an HTTP server serving the registry."""
    self.http_server.__enter__()

  def stop(self):
    """Stop the HTTP server."""
    self.http_server.__exit__(None, None, None)

  def getURL(self):
    """Return the URL of this registry."""
    return self.http_server.getURL()

  def generateCcSource(
      self,
      name,
      version,
      deps=None,
      repo_names=None,
      extra_module_file_contents=None,
  ):
    """Generate a cc project with given dependency information.

    1. The cc projects implements a hello_<lib_name> function.
    2. The hello_<lib_name> function calls the same function of its
    dependencies.
    3. The hello_<lib_name> function prints "<caller name> =>
    <lib_name@version>".
    4. The BUILD file references the dependencies as their desired repo names.

    Args:
      name:  The module name.
      version: The module version.
      deps: The dependencies of this module.
      repo_names: The desired repository name for some dependencies.
      extra_module_file_contents: Extra lines to append to the MODULE.bazel
        file.

    Returns:
      The generated source directory.
    """

    src_dir = self.projects.joinpath(name, version)
    src_dir.mkdir(parents=True, exist_ok=True)
    if not deps:
      deps = {}
    if not repo_names:
      repo_names = {}
    for dep in deps:
      if dep not in repo_names:
        repo_names[dep] = dep
    if not extra_module_file_contents:
      extra_module_file_contents = []

    def calc_repo_name_str(dep):
      if dep == repo_names[dep]:
        return ''
      return ', repo_name = "%s"' % repo_names[dep]

    scratchFile(
        src_dir.joinpath('MODULE.bazel'),
        [
            'module(',
            '  name = "%s",' % name,
            '  version = "%s",' % version,
            '  compatibility_level = 1,',
            ')',
        ]
        + [
            'bazel_dep(name = "%s", version = "%s"%s)'
            % (dep, version, calc_repo_name_str(dep))
            for dep, version in deps.items()
        ]
        + extra_module_file_contents,
    )

    scratchFile(
        src_dir.joinpath(name.lower() + '.h'), [
            '#ifndef %s_H' % name.upper(),
            '#define %s_H' % name.upper(),
            '#include <string>',
            'void hello_%s(const std::string& caller);' % name.lower(),
            '#endif',
        ])
    scratchFile(
        src_dir.joinpath(name.lower() + '.cc'), [
            '#include <stdio.h>',
            '#include "%s.h"' % name.lower(),
        ] + ['#include "%s.h"' % dep.lower() for dep in deps] + [
            'void hello_%s(const std::string& caller) {' % name.lower(),
            '    std::string lib_name = "%s@%s%s";' %
            (name, version, self.registry_suffix),
            '    printf("%s => %s\\n", caller.c_str(), lib_name.c_str());',
        ] + ['    hello_%s(lib_name);' % dep.lower() for dep in deps] + [
            '}',
        ])
    scratchFile(
        src_dir.joinpath('BUILD'), [
            'package(default_visibility = ["//visibility:public"])',
            'cc_library(',
            '  name = "lib_%s",' % name.lower(),
            '  srcs = ["%s.cc"],' % name.lower(),
            '  hdrs = ["%s.h"],' % name.lower(),
        ] + ([
            '  deps = ["%s"],' % ('", "'.join([
                '@%s//:lib_%s' % (repo_names[dep], dep.lower()) for dep in deps
            ])),
        ] if deps else []) + [
            ')',
        ])
    return src_dir

  def createArchive(self, name, version, src_dir, filename_pattern='%s.%s.zip'):
    """Create an archive with a given source directory."""
    zip_path = self.archives.joinpath(filename_pattern % (name, version))
    zip_obj = zipfile.ZipFile(str(zip_path), 'w')
    for foldername, _, filenames in os.walk(str(src_dir)):
      for filename in filenames:
        filepath = os.path.join(foldername, filename)
        zip_obj.write(filepath,
                      str(pathlib.Path(filepath).relative_to(src_dir)))
    zip_obj.close()
    return zip_path

  def addModule(self, module):
    """Add a module into the registry."""
    module_dir = self.root.joinpath('modules', module.name, module.version)
    module_dir.mkdir(parents=True, exist_ok=True)

    # Copy MODULE.bazel to the registry
    module_dot_bazel = module_dir.joinpath('MODULE.bazel')
    shutil.copy(str(module.module_dot_bazel), str(module_dot_bazel))

    # Create source.json & copy patch files to the registry
    source = {
        'url': module.archive_url,
        'integrity': integrity(download(module.archive_url)),
    }
    if module.strip_prefix:
      source['strip_prefix'] = module.strip_prefix

    if module.patches:
      patch_dir = module_dir.joinpath('patches')
      patch_dir.mkdir()
      source['patches'] = {}
      source['patch_strip'] = module.patch_strip
      for patch_path in module.patches:
        patch = pathlib.Path(patch_path)
        source['patches'][patch.name] = integrity(read(patch))
        shutil.copy(str(patch), str(patch_dir))

    if module.archive_type:
      source['archive_type'] = module.archive_type

    with module_dir.joinpath('source.json').open('w') as f:
      json.dump(source, f, indent=4, sort_keys=True)

  def createCcModule(
      self,
      name,
      version,
      deps=None,
      repo_names=None,
      patches=None,
      patch_strip=0,
      archive_pattern=None,
      archive_type=None,
      extra_module_file_contents=None,
  ):
    """Generate a cc project and add it as a module into the registry."""
    src_dir = self.generateCcSource(
        name, version, deps, repo_names, extra_module_file_contents
    )
    if archive_pattern:
      archive = self.createArchive(
          name, version, src_dir, filename_pattern=archive_pattern
      )
    else:
      archive = self.createArchive(name, version, src_dir)
    module = Module(name, version)
    module.set_source(archive.resolve().as_uri())
    module.set_module_dot_bazel(src_dir.joinpath('MODULE.bazel'))
    if patches:
      module.set_patches(patches, patch_strip)
    if archive_type:
      module.set_archive_type(archive_type)

    self.addModule(module)
    return self

  def addMetadata(self,
                  name,
                  homepage=None,
                  maintainers=None,
                  versions=None,
                  yanked_versions=None):
    """Generate a module metadata file and add it to the registry."""
    if maintainers is None:
      maintainers = []
    if versions is None:
      versions = []
    if yanked_versions is None:
      yanked_versions = {}

    module_dir = self.root.joinpath('modules', name)
    module_dir.mkdir(parents=True, exist_ok=True)

    metadata = {
        'homepage': homepage,
        'maintainers': maintainers,
        'versions': versions,
        'yanked_versions': yanked_versions
    }

    with module_dir.joinpath('metadata.json').open('w') as f:
      json.dump(metadata, f, indent=4, sort_keys=True)

    return self

  def createLocalPathModule(self, name, version, path, deps=None):
    """Add a local module into the registry."""
    module_dir = self.root.joinpath('modules', name, version)
    module_dir.mkdir(parents=True, exist_ok=True)

    # Create source.json & copy patch files to the registry
    source = {
        'type': 'local_path',
        'path': path,
    }

    self._createModuleAndSourceJson(
        module_dir, name, version, path, deps, source
    )

  def createGitRepoModule(self, name, version, path, deps=None, **kwargs):
    """Add a git repo module into the registry."""
    module_dir = self.root.joinpath('modules', name, version)
    module_dir.mkdir(parents=True, exist_ok=True)

    # Create source.json & copy patch files to the registry
    source = {
        'type': 'git_repository',
        'remote': f'file://{path}',
    }
    source.update(**kwargs)

    self._createModuleAndSourceJson(
        module_dir, name, version, path, deps, source
    )

  def _createModuleAndSourceJson(
      self, module_dir, name, version, path, deps, source
  ):
    """Create the MODULE.bazel and source.json files for a module."""
    if deps is None:
      deps = {}

    module_file_lines = [
        'module(',
        '  name = "%s",' % name,
        '  version = "%s",' % version,
        ')',
    ] + ['bazel_dep(name="%s",version="%s")' % p for p in deps.items()]
    scratchFile(module_dir.joinpath('MODULE.bazel'), module_file_lines)
    self.projects.joinpath(path).mkdir(exist_ok=True)
    scratchFile(self.projects.joinpath(path, 'MODULE.bazel'), module_file_lines)

    with module_dir.joinpath('source.json').open('w') as f:
      json.dump(source, f, indent=4, sort_keys=True)


class StaticHTTPServer:
  """An HTTP server serving static files, optionally with authentication."""

  def __init__(self, root_directory, expected_auth=None):
    self.root_directory = root_directory
    self.expected_auth = expected_auth

  def __enter__(self):
    address = ('localhost', 0)  # assign random port
    handler = functools.partial(
        _Handler, self.root_directory, self.expected_auth
    )
    self.httpd = http.server.HTTPServer(address, handler)
    self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
    self.thread.start()
    return self

  def __exit__(self, exc_type, exc_value, traceback):
    self.httpd.shutdown()
    self.thread.join()

  def getURL(self):
    return 'http://{}:{}'.format(*self.httpd.server_address)


class _Handler(http.server.SimpleHTTPRequestHandler):
  """A SimpleHTTPRequestHandler with authentication."""

  # Note: until Python 3.6, SimpleHTTPRequestHandler was only able to serve
  # files from the working directory. A 'directory' parameter was added in
  # Python 3.7, but sadly our CI builds are stuck with Python 3.6. Instead,
  # we monkey-patch translate_path() to rewrite the path.

  def __init__(self, root_directory, expected_auth, *args, **kwargs):
    self.root_directory = root_directory
    self.expected_auth = expected_auth
    super().__init__(*args, **kwargs)

  def translate_path(self, path):
    abs_path = super().translate_path(path)
    rel_path = os.path.relpath(abs_path, os.getcwd())
    return os.path.join(self.root_directory, rel_path)

  def check_auth(self):
    auth_header = self.headers.get('Authorization', None)
    if auth_header != self.expected_auth:
      self.send_error(http.HTTPStatus.UNAUTHORIZED)
      return False
    return True

  def do_HEAD(self):
    if self.check_auth():
      return super().do_HEAD()

  def do_GET(self):
    if self.check_auth():
      return super().do_GET()
