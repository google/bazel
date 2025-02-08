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

import os
import tempfile
from absl.testing import absltest
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry


class BazelFetchTest(test_base.TestBase):

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.registries_work_dir = tempfile.mkdtemp(dir=self._test_cwd)
    self.main_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'main')
    )
    self.main_registry.start()
    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            'common --experimental_isolated_extension_usages',
            'common --registry=' + self.main_registry.getURL(),
            'common --registry=https://bcr.bazel.build',
            'common --verbose_failures',
            # Set an explicit Java language version
            'common --java_language_version=8',
            'common --tool_java_language_version=8',
            'common --lockfile_mode=update',
        ],
    )
    self.ScratchFile('MODULE.bazel')
    self.generatBuiltinModules()

  def tearDown(self):
    self.main_registry.stop()
    test_base.TestBase.tearDown(self)

  def generatBuiltinModules(self):
    self.ScratchFile('platforms_mock/BUILD')
    self.ScratchFile(
        'platforms_mock/MODULE.bazel', ['module(name="local_config_platform")']
    )

    self.ScratchFile('tools_mock/BUILD')
    self.ScratchFile('tools_mock/MODULE.bazel', ['module(name="bazel_tools")'])
    self.ScratchFile('tools_mock/tools/build_defs/repo/BUILD')
    self.CopyFile(
        self.Rlocation('io_bazel/tools/build_defs/repo/cache.bzl'),
        'tools_mock/tools/build_defs/repo/cache.bzl',
    )
    self.CopyFile(
        self.Rlocation('io_bazel/tools/build_defs/repo/http.bzl'),
        'tools_mock/tools/build_defs/repo/http.bzl',
    )
    self.CopyFile(
        self.Rlocation('io_bazel/tools/build_defs/repo/local.bzl'),
        'tools_mock/tools/build_defs/repo/local.bzl',
    )
    self.CopyFile(
        self.Rlocation('io_bazel/tools/build_defs/repo/utils.bzl'),
        'tools_mock/tools/build_defs/repo/utils.bzl',
    )

  def useMockBuiltinModules(self):
    with open(self.Path('.bazelrc'), 'a', encoding='utf-8') as f:
      f.write('common --override_repository=bazel_tools=tools_mock\n')
      f.write(
          'common --override_repository=local_config_platform=platforms_mock\n'
      )

  def testFetchAll(self):
    self.useMockBuiltinModules()
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    )
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "hello")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="hello")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    self.RunBazel(['fetch'])
    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    repos_fetched = os.listdir(stdout[0] + '/external')
    self.assertIn('aaa+', repos_fetched)
    self.assertIn('bbb+', repos_fetched)
    self.assertIn('+ext+hello', repos_fetched)

  def testFetchConfig(self):
    self.useMockBuiltinModules()
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    )
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "notConfig")',
            'use_repo(ext, "IamConfig")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("BUILD")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            'repo_rule2 = repository_rule(implementation=_repo_rule_impl, ',
            'configure=True)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="notConfig")',
            '    repo_rule2(name="IamConfig")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    self.RunBazel(['fetch', '--configure'])
    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    repos_fetched = os.listdir(stdout[0] + '/external')
    self.assertNotIn('aaa+', repos_fetched)
    self.assertNotIn('+ext+notConfig', repos_fetched)
    self.assertIn('+ext+IamConfig', repos_fetched)

  def testFetchConfigForce(self):
    self.useMockBuiltinModules()
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    )
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "notConfig")',
            'use_repo(ext, "IamConfig")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    print("Fetching {}".format(ctx.attr.name))',
            '    if ctx.attr.name.endswith("IamConfig"):',
            (
                '       '
                ' ctx.path(Label("@notConfig//:whatever")).dirname.readdir()'
            ),
            '    ctx.file("BUILD")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            'repo_rule2 = repository_rule(implementation=_repo_rule_impl, ',
            'configure=True)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="notConfig")',
            '    repo_rule2(name="IamConfig")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['fetch', '--configure'])
    stderr = '\n'.join(stderr)
    self.assertIn('Fetching +ext+notConfig', stderr)
    self.assertIn('Fetching +ext+IamConfig', stderr)

    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    repos_fetched = os.listdir(stdout[0] + '/external')
    self.assertIn('+ext+notConfig', repos_fetched)
    self.assertIn('+ext+IamConfig', repos_fetched)

    _, stdout, stderr = self.RunBazel(['fetch', '--configure', '--force'])
    stderr = '\n'.join(stderr)
    self.assertNotIn('Fetching +ext+notConfig', stderr)
    self.assertIn('Fetching +ext+IamConfig', stderr)

  def testFetchFailsWithMultipleOptions(self):
    exit_code, _, stderr = self.RunBazel(
        ['fetch', '--all', '--configure'], allow_failure=True
    )
    self.AssertExitCode(exit_code, 2, stderr)
    self.assertIn(
        'ERROR: Only one fetch option can be provided for fetch command',
        stderr,
    )
    exit_code, _, stderr = self.RunBazel(
        ['fetch', '//sometarget', '--repo=@hello'], allow_failure=True
    )
    self.AssertExitCode(exit_code, 2, stderr)
    self.assertIn(
        'ERROR: Only one fetch option can be provided for fetch command',
        stderr,
    )

  def testFetchRepo(self):
    self.useMockBuiltinModules()
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    ).createCcModule('ccc', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
            'bazel_dep(name = "ccc", version = "1.0", repo_name = "my_repo")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    # Test canonical/apparent repo names & multiple repos
    self.RunBazel(['fetch', '--repo=@@bbb+', '--repo=@my_repo'])
    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    repos_fetched = os.listdir(stdout[0] + '/external')
    self.assertIn('bbb+', repos_fetched)
    self.assertIn('ccc+', repos_fetched)
    self.assertNotIn('aaa+', repos_fetched)

  def testFetchInvalidRepo(self):
    self.useMockBuiltinModules()
    # Invalid repo name (not canonical or apparent)
    exit_code, _, stderr = self.RunBazel(
        ['fetch', '--repo=hello'], allow_failure=True
    )
    self.AssertExitCode(exit_code, 8, stderr)
    self.assertIn(
        'ERROR: Invalid repo name: The repo value has to be either apparent'
        " '@repo' or canonical '@@repo' repo name",
        stderr,
    )
    # Repo does not exist
    self.ScratchFile(
        'MODULE.bazel',
        [
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    exit_code, _, stderr = self.RunBazel(
        ['fetch', '--repo=@@nono', '--repo=@nana'], allow_failure=True
    )
    self.AssertExitCode(exit_code, 8, stderr)
    self.assertIn(
        "ERROR: Fetching some repos failed with errors: Repository '@@nono' is "
        "not defined; No repository visible as '@nana' from main repository",
        stderr,
    )

  def testForceFetch(self):
    self.useMockBuiltinModules()
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "hello")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile('orange_juice.txt', ['Orange Juice'])
    file_path = self.Path('orange_juice.txt').replace('\\', '\\\\')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    file_content = ctx.read("' + file_path + '", watch="no")',
            '    print(file_content)',
            '    ctx.file("BUILD")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="hello")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['fetch', '--repo=@hello'])
    self.assertIn('Orange Juice', ''.join(stderr))

    # Change file content and run WITHOUT force, assert no fetching!
    self.ScratchFile('orange_juice.txt', ['No more Orange Juice!'])
    _, _, stderr = self.RunBazel(['fetch', '--repo=@hello'])
    self.assertNotIn('No more Orange Juice!', ''.join(stderr))

    # Run again WITH --force and assert fetching
    _, _, stderr = self.RunBazel(['fetch', '--repo=@hello', '--force'])
    self.assertIn('No more Orange Juice!', ''.join(stderr))

    # One more time to validate force is invoked and not cached by skyframe
    _, _, stderr = self.RunBazel(['fetch', '--repo=@hello', '--force'])
    self.assertIn('No more Orange Juice!', ''.join(stderr))

  def testFetchTarget(self):
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    )
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = [',
            '    "@bbb//:lib_bbb",',
            '  ],',
            ')',
        ],
    )
    self.ScratchFile(
        'main.cc',
        [
            '#include "aaa.h"',
            'int main() {',
            '    hello_aaa("Hello there!");',
            '}',
        ],
    )
    self.RunBazel(['fetch', '//:main'])
    # If we can run the target with --nofetch, this means we successfully
    # fetched all its needed repos
    _, stdout, _ = self.RunBazel(['run', '//:main', '--nofetch'])
    self.assertIn('Hello there! => aaa@1.0', stdout)

  def testFetchWithTargetPatternFile(self):
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    )
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = [',
            '    "@bbb//:lib_bbb",',
            '  ],',
            ')',
        ],
    )
    self.ScratchFile(
        'main.cc',
        [
            '#include "aaa.h"',
            'int main() {',
            '    hello_aaa("Hello there!");',
            '}',
        ],
    )
    self.ScratchFile('targets.params', ['//:main'])
    self.RunBazel(['fetch', '--target_pattern_file=targets.params'])
    # If we can run the target with --nofetch, this means we successfully
    # fetched all its needed repos
    _, stdout, _ = self.RunBazel(['run', '//:main', '--nofetch'])
    self.assertIn('Hello there! => aaa@1.0', stdout)


if __name__ == '__main__':
  absltest.main()
