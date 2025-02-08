# pylint: disable=g-backslash-continuation
# Copyright 2024 The Bazel Authors. All rights reserved.
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
import shutil
import stat
import tempfile
from absl.testing import absltest
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry


class BazelVendorTest(test_base.TestBase):

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
            'startup --windows_enable_symlinks' if self.IsWindows() else '',
        ],
    )
    self.ScratchFile('MODULE.bazel')
    self.generateBuiltinModules()

  def tearDown(self):
    self.main_registry.stop()
    test_base.TestBase.tearDown(self)

  def generateBuiltinModules(self):
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

  def testBasicVendoring(self):
    self.useMockBuiltinModules()
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    ).createCcModule('bbb', '2.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')

    self.RunBazel(['vendor', '--vendor_dir=vendor'])

    # Assert repos are vendored with marker files and VENDOR.bazel is created
    vendor_dir = self._test_cwd + '/vendor'
    repos_vendored = os.listdir(vendor_dir)
    self.assertIn('aaa+', repos_vendored)
    self.assertIn('bbb+', repos_vendored)
    self.assertIn('@aaa+.marker', repos_vendored)
    self.assertIn('@bbb+.marker', repos_vendored)
    self.assertIn('VENDOR.bazel', repos_vendored)

    # Update bbb to 2.0 and re-vendor
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "2.0")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('vendor/bbb+/foo')
    self.RunBazel(['vendor', '--vendor_dir=vendor'])
    bbb_module_bazel = os.path.join(vendor_dir, 'bbb+/MODULE.bazel')
    self.AssertFileContentContains(bbb_module_bazel, 'version = "2.0"')
    foo = os.path.join(vendor_dir, 'bbb+/foo')
    self.assertFalse(
        os.path.exists(foo)
    )  # foo should be removed due to re-vendor

  def testVendorFailsWithNofetch(self):
    self.useMockBuiltinModules()
    self.ScratchFile(
        'MODULE.bazel',
        [
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    # We need to fetch first so that it won't fail while creating the initial
    # repo mapping because of --nofetch
    self.RunBazel(['fetch', '--all'])
    _, _, stderr = self.RunBazel(
        ['vendor', '--vendor_dir=vendor', '--nofetch'], allow_failure=True
    )
    self.assertIn(
        'ERROR: You cannot run the vendor command with --nofetch', stderr
    )

  def testVendorAfterFetch(self):
    self.useMockBuiltinModules()
    self.main_registry.createCcModule('aaa', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')

    self.RunBazel(['fetch', '--repo=@@aaa+'])
    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@@aaa+'])

    repos_vendored = os.listdir(self._test_cwd + '/vendor')
    self.assertIn('aaa+', repos_vendored)

  def testVendoringMultipleTimes(self):
    self.useMockBuiltinModules()
    self.main_registry.createCcModule('aaa', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')

    self.RunBazel(['vendor', '--vendor_dir=vendor'])
    # Clean the external cache
    self.RunBazel(['clean', '--expunge'])
    # Re-vendoring should NOT re-fetch, but only create symlinks
    # We need to check this because the vendor logic depends on the fetch logic,
    # but we don't want to re-fetch if our vendored repo is already up-to-date!
    self.RunBazel(['vendor', '--vendor_dir=vendor'])

    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    repo_path = stdout[0] + '/external/aaa+'
    self.AssertPathIsSymlink(repo_path)

  def testVendorRepo(self):
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
    self.RunBazel(
        ['vendor', '--vendor_dir=vendor', '--repo=@@bbb+', '--repo=@my_repo']
    )
    repos_vendored = os.listdir(self._test_cwd + '/vendor')
    self.assertIn('bbb+', repos_vendored)
    self.assertIn('ccc+', repos_vendored)
    self.assertNotIn('aaa+', repos_vendored)

  def testVendorExistingRepo(self):
    self.useMockBuiltinModules()
    self.main_registry.createCcModule('aaa', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    # Test canonical/apparent repo names & multiple repos
    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@my_repo'])
    self.assertIn('aaa+', os.listdir(self._test_cwd + '/vendor'))

    # Delete repo from external cache
    self.RunBazel(['clean', '--expunge'])
    # Vendoring again should find that it is already up-to-date and exclude it
    # from vendoring not fail
    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@my_repo'])

  def testVendorInvalidRepo(self):
    self.useMockBuiltinModules()
    # Invalid repo name (not canonical or apparent)
    exit_code, _, stderr = self.RunBazel(
        ['vendor', '--vendor_dir=vendor', '--repo=hello'], allow_failure=True
    )
    self.AssertExitCode(exit_code, 8, stderr)
    self.assertIn(
        'ERROR: Invalid repo name: The repo value has to be either apparent'
        " '@repo' or canonical '@@repo' repo name",
        stderr
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
        ['vendor', '--vendor_dir=vendor', '--repo=@@nono', '--repo=@nana'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 8, stderr)
    self.assertIn(
        "ERROR: Vendoring some repos failed with errors: [Repository '@@nono'"
        " is not defined, No repository visible as '@nana' from main"
        ' repository]',
        stderr,
    )

  def testIgnoreFromVendoring(self):
    self.useMockBuiltinModules()
    # Repos should be excluded from vendoring:
    # 1.Local Repos, 2.Config Repos, 3.Repos declared in VENDOR.bazel file
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    )
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "regularRepo")',
            'use_repo(ext, "localRepo")',
            'use_repo(ext, "configRepo")',
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
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD")',
            '',
            'repo_rule1 = repository_rule(implementation=_repo_rule_impl)',
            'repo_rule2 = repository_rule(implementation=_repo_rule_impl, ',
            'local=True)',
            'repo_rule3 = repository_rule(implementation=_repo_rule_impl, ',
            'configure=True)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule1(name="regularRepo")',
            '    repo_rule2(name="localRepo")',
            '    repo_rule3(name="configRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    os.makedirs(self._test_cwd + '/vendor', exist_ok=True)
    with open(self._test_cwd + '/vendor/VENDOR.bazel', 'w') as f:
      f.write("ignore('@@+ext+regularRepo')\n")

    self.RunBazel(['vendor', '--vendor_dir=vendor'])
    repos_vendored = os.listdir(self._test_cwd + '/vendor')

    # Assert aaa & bbb are vendored with marker files
    self.assertIn('aaa+', repos_vendored)
    self.assertIn('bbb+', repos_vendored)
    self.assertIn('@bbb+.marker', repos_vendored)
    self.assertIn('@aaa+.marker', repos_vendored)

    # Assert regular repo (from VENDOR.bazel), local and config repos are
    # not vendored
    self.assertNotIn('bazel_tools', repos_vendored)
    self.assertNotIn('local_config_platform', repos_vendored)
    self.assertNotIn('+ext+localRepo', repos_vendored)
    self.assertNotIn('+ext+configRepo', repos_vendored)
    self.assertNotIn('+ext+regularRepo', repos_vendored)

  def testBuildingWithPinnedRepo(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "venRepo")',
        ],
    )
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="venRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )
    self.ScratchFile('BUILD')

    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@venRepo'])
    self.assertIn('+ext+venRepo', os.listdir(self._test_cwd + '/vendor'))
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'IhaveChanged\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="venRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Pin the repo then build, should build what is under vendor
    # directory with no warning
    with open(self._test_cwd + '/vendor/VENDOR.bazel', 'w') as f:
      f.write("pin('@@+ext+venRepo')\n")
    _, _, stderr = self.RunBazel(
        ['build', '@venRepo//:all', '--vendor_dir=vendor'],
    )
    self.assertNotIn(
        "Vendored repository '+ext+venRepo' is out-of-date.",
        '\n'.join(stderr),
    )
    self.assertIn(
        'Target @@+ext+venRepo//:lala up-to-date (nothing to build)',
        stderr,
    )

    # Unpin the repo, clean the cache and assert updates are applied
    with open(self._test_cwd + '/vendor/VENDOR.bazel', 'w') as f:
      f.write('')
    _, _, stderr = self.RunBazel(
        ['build', '@venRepo//:all', '--vendor_dir=vendor'],
    )
    self.assertIn(
        'Target @@+ext+venRepo//:IhaveChanged up-to-date (nothing to build)',
        stderr,
    )
    self.assertIn(
        "Vendored repository '+ext+venRepo' is out-of-date (repo hasn't been"
        ' fetched yet).',
        '\n'.join(stderr),
    )

    # Re-vendor & build make sure the repo is successfully updated
    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@venRepo'])
    _, _, stderr = self.RunBazel(
        ['build', '@venRepo//:all', '--vendor_dir=vendor'],
    )
    self.assertNotIn(
        "Vendored repository '+ext+venRepo' is out-of-date",
        '\n'.join(stderr),
    )

  def testBuildingOutOfDateVendoredRepo(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "justRepo")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="justRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Vendor, assert and build with no problems
    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@justRepo'])
    self.assertIn('+ext+justRepo', os.listdir(self._test_cwd + '/vendor'))
    _, _, stderr = self.RunBazel(
        ['build', '@justRepo//:all', '--vendor_dir=vendor']
    )
    stderr = '\n'.join(stderr)
    self.assertNotIn('out-of-date', stderr)

    # Make updates in repo definition
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'haha\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="justRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Clean cache, and re-build with vendor
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(
        ['build', '@justRepo//:all', '--vendor_dir=vendor']
    )
    # Assert repo in vendor is out-of-date, and the new one is fetched into
    # external and not a symlink
    self.assertIn(
        "WARNING: <builtin>: Vendored repository '+ext+justRepo' is out-of-date"
        ' (Bazel version, flags, repo rule definition or attributes changed).'
        ' The up-to-date version will be fetched into the external cache and'
        ' used. To update the repo in the vendor directory, run the bazel'
        ' vendor command',
        stderr,
    )
    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    self.assertFalse(os.path.islink(stdout[0] + '/external/bbb+'))

    # Assert vendoring again solves the problem
    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@justRepo'])
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(
        ['build', '@justRepo//:all', '--vendor_dir=vendor']
    )
    stderr = '\n'.join(stderr)
    self.assertNotIn('out-of-date', stderr)

  def testBuildingVendoredRepoWithNoFetch(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "venRepo")',
        ],
    )
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="venRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )
    self.ScratchFile('BUILD')

    # Vendor, assert and build with no problems
    self.RunBazel(['vendor', '--vendor_dir=vendor', '@venRepo//:all'])
    self.assertIn('+ext+venRepo', os.listdir(self._test_cwd + '/vendor'))

    # Make updates in repo definition
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "venRepo")',
            'use_repo(ext, "noVenRepo")',
        ],
    )
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'haha\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="venRepo")',
            '    repo_rule(name="noVenRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Building a repo that is not vendored in offline mode, should fail
    _, _, stderr = self.RunBazel(
        ['build', '@noVenRepo//:all', '--vendor_dir=vendor', '--nofetch'],
        allow_failure=True,
    )
    self.assertIn(
        'ERROR: Vendored repository +ext+noVenRepo not found under the'
        ' vendor directory and fetching is disabled. To fix, run the bazel'
        " vendor command or build without the '--nofetch'",
        stderr,
    )

    # Building out-of-date repo in offline mode, should build the out-dated one
    # and emit a warning
    _, _, stderr = self.RunBazel(
        ['build', '@venRepo//:all', '--vendor_dir=vendor', '--nofetch'],
    )
    self.assertIn(
        "WARNING: <builtin>: Vendored repository '+ext+venRepo' is out-of-date"
        ' (Bazel version, flags, repo rule definition or attributes changed)'
        " and fetching is disabled. Run build without the '--nofetch' option or"
        ' run the bazel vendor command to update it',
        stderr,
    )
    # Assert the out-dated repo is the one built with
    self.assertIn(
        'Target @@+ext+venRepo//:lala up-to-date (nothing to build)',
        stderr,
    )

  def testBasicVendorTarget(self):
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0'
    ).createCcModule('ccc', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'bazel_dep(name = "bbb", version = "1.0")',
            'bazel_dep(name = "ccc", version = "1.0")',
        ],
    )
    self.ScratchFile('BUILD')

    self.RunBazel(
        ['vendor', '@aaa//:lib_aaa', '@bbb//:lib_bbb', '--vendor_dir=vendor']
    )
    # Assert aaa & bbb and are vendored
    self.assertIn('aaa+', os.listdir(self._test_cwd + '/vendor'))
    self.assertIn('bbb+', os.listdir(self._test_cwd + '/vendor'))
    self.assertNotIn('ccc+', os.listdir(self._test_cwd + '/vendor'))

    # Delete vendor source and re-vendor should work without server restart
    def on_rm_error(func, path, exc_info):
      del exc_info  # Unused
      os.chmod(path, stat.S_IWRITE)
      func(path)

    shutil.rmtree(self._test_cwd + '/vendor', onerror=on_rm_error)
    self.RunBazel(
        ['vendor', '@aaa//:lib_aaa', '@bbb//:lib_bbb', '--vendor_dir=vendor']
    )
    self.assertIn('aaa+', os.listdir(self._test_cwd + '/vendor'))
    self.assertIn('bbb+', os.listdir(self._test_cwd + '/vendor'))
    self.assertNotIn('ccc+', os.listdir(self._test_cwd + '/vendor'))

  def testVendorWithTargetPatternFile(self):
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0'
    ).createCcModule('ccc', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'bazel_dep(name = "bbb", version = "1.0")',
            'bazel_dep(name = "ccc", version = "1.0")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'targets.params',
        [
            '@aaa//:lib_aaa',
            '@bbb//:lib_bbb',
        ],
    )

    self.RunBazel([
        'vendor',
        '--target_pattern_file=targets.params',
        '--vendor_dir=vendor',
    ])
    # Assert aaa & bbb and are vendored
    self.assertIn('aaa+', os.listdir(self._test_cwd + '/vendor'))
    self.assertIn('bbb+', os.listdir(self._test_cwd + '/vendor'))
    self.assertNotIn('ccc+', os.listdir(self._test_cwd + '/vendor'))

  def testVendorDirIsIgnored(self):
    self.main_registry.createCcModule('aaa', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        ['bazel_dep(name = "aaa", version = "1.0")'],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = [',
            '    "@aaa//:lib_aaa",',
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

    self.RunBazel([
        'vendor',
        '//...',
        '--vendor_dir=vendor',
    ])
    # Assert aaa is vendored
    self.assertIn('aaa+', os.listdir(self._test_cwd + '/vendor'))

    # bazel build //... should succeed because vendor dir is ignored.
    self.RunBazel([
        'build',
        '//...',
        '--vendor_dir=vendor',
    ])

  def testBuildVendoredTargetOffline(self):
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
            '#include "bbb.h"',
            'int main() {',
            '    hello_bbb("Hello there!");',
            '}',
        ],
    )

    self.RunBazel(['vendor', '//:main', '--vendor_dir=vendor'])

    # Build and run the target in a clean build with internet blocked and make
    # sure it works
    _, _, _ = self.RunBazel(['clean', '--expunge'])
    _, stdout, _ = self.RunBazel(
        ['run', '//:main', '--vendor_dir=vendor', '--repository_cache='],
        env_add={
            'HTTP_PROXY': 'internet_blocked',
            'HTTPS_PROXY': 'internet_blocked',
        },
    )
    self.assertIn('Hello there! => bbb@1.0', stdout)

    # Assert repos in {OUTPUT_BASE}/external are symlinks (junction on
    # windows, this validates it was created from vendor and not fetched)
    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    for repo in ['aaa+', 'bbb+']:
      repo_path = stdout[0] + '/external/' + repo
      self.AssertPathIsSymlink(repo_path)

  def testVendorConflictRegistryFile(self):
    self.useMockBuiltinModules()
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    )
    # The registry URLs of main_registry and another_registry only differ by the
    # port number
    another_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'MAIN'),
    )
    another_registry.start()
    another_registry.createCcModule('aaa', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
            'single_version_override(',
            '  module_name = "aaa",',
            '  registry = "%s",' % another_registry.getURL(),
            ')',
        ],
    )
    self.ScratchFile('BUILD')
    exit_code, _, stderr = self.RunBazel(
        ['vendor', '--vendor_dir=vendor'], allow_failure=True
    )
    self.AssertExitCode(exit_code, 8, stderr)
    self.assertIn(
        'ERROR: Error while vendoring repos: Vendor paths conflict detected for'
        ' registry URLs:',
        stderr,
    )

  def testVendorRepoWithSymlinks(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "foo", "bar")',
        ],
    )
    abs_foo = self.ScratchFile('abs', ['Hello from abs!']).replace('\\', '/')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_foo_impl(ctx):',
            '    ctx.file("REPO.bazel")',
            '    ctx.file("data", "Hello from foo!\\n")',
            # Symlink to an absolute path outside of external root
            f'    ctx.symlink("{abs_foo}", "sym_abs")',
            # Symlink to a file in the same repo
            '    ctx.symlink("data", "sym_foo")',
            # Symlink to a file in another repo
            '    ctx.symlink(ctx.path(Label("@bar//:data")), "sym_bar")',
            # Symlink to a directory in another repo
            '    ctx.symlink("../+ext+bar/pkg", "sym_pkg")',
            (
                '    ctx.file("BUILD", "exports_files([\'sym_abs\','
                " 'sym_foo','sym_bar', 'sym_pkg/data'])\")"
            ),
            'repo_foo = repository_rule(implementation=_repo_foo_impl)',
            '',
            'def _repo_bar_impl(ctx):',
            '    ctx.file("REPO.bazel")',
            '    ctx.file("data", "Hello from bar!\\n")',
            '    ctx.file("pkg/data", "Hello from pkg bar!\\n")',
            '    ctx.file("BUILD", "exports_files([\'data\'])")',
            'repo_bar = repository_rule(implementation=_repo_bar_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_foo(name="foo")',
            '    repo_bar(name="bar")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'genrule(',
            '  name = "print_paths",',
            (
                '  srcs = ["@foo//:sym_abs", "@foo//:sym_foo",'
                ' "@foo//:sym_bar", "@foo//:sym_pkg/data"],'
            ),
            '  outs = ["output.txt"],',
            '  cmd = "cat $(SRCS) > $@",',
            ')',
        ],
    )
    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@foo'])
    self.RunBazel(['clean', '--expunge'])
    self.AssertPathIsSymlink(self._test_cwd + '/vendor/bazel-external')

    # Move the vendor directory to a new location and use a new output base,
    # it should still work
    os.rename(self._test_cwd + '/vendor', self._test_cwd + '/vendor_new')
    output_base = tempfile.mkdtemp(dir=self._tests_root)
    self.RunBazel([
        f'--output_base={output_base}',
        'build',
        '//:print_paths',
        '--vendor_dir=vendor_new',
        '--verbose_failures',
    ])
    _, stdout, _ = self.RunBazel(
        [f'--output_base={output_base}', 'info', 'output_base']
    )
    self.AssertPathIsSymlink(stdout[0] + '/external/+ext+foo')
    output = os.path.join(self._test_cwd, './bazel-bin/output.txt')
    self.AssertFileContentContains(
        output,
        'Hello from abs!\nHello from foo!\nHello from bar!\nHello from pkg'
        ' bar!\n',
    )

  def testVendorAliasTarget(self):
    self.ScratchFile('foo/file.txt')
    self.ScratchFile('foo/BUILD', ['alias(name="foo", actual="file.txt")'])

    # This should not fail
    # Regression test for https://github.com/bazelbuild/bazel/issues/23300
    self.RunBazel(['vendor', '//foo/...', '--vendor_dir=vendor'])


if __name__ == '__main__':
  absltest.main()
