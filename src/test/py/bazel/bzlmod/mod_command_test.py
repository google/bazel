# pylint: disable=g-backslash-continuation
# Copyright 2022 The Bazel Authors. All rights reserved.
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
"""Tests the mod command."""

import json
import os
import tempfile
from absl.testing import absltest
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry
from src.test.py.bazel.bzlmod.test_utils import scratchFile


class ModCommandTest(test_base.TestBase):
  """Test class for the mod command."""

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.registries_work_dir = tempfile.mkdtemp(dir=self._test_cwd)
    self.main_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'main')
    )
    self.main_registry.start()
    self.main_registry.setModuleBasePath('projects')
    self.projects_dir = self.main_registry.projects
    self.maxDiff = None  # there are some long diffs in this test

    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            'mod --registry=' + self.main_registry.getURL(),
            # We need to have BCR here to make sure built-in modules like
            # bazel_tools can work.
            'mod --registry=https://bcr.bazel.build',
            # Disable yanked version check so we are not affected BCR changes.
            'mod --allow_yanked_versions=all',
            # Make sure Bazel CI tests pass in all environments
            'mod --charset=ascii',
        ],
    )

    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name = "my_project", version = "1.0")',
            '',
            'bazel_dep(name = "foo", version = "1.0", repo_name = "foo1")',
            'bazel_dep(name = "foo", version = "2.0", repo_name = "foo2")',
            'bazel_dep(name = "ext", version = "1.0")',
            'bazel_dep(name = "ext2", version = "1.0")',
            'multiple_version_override(',
            '  module_name= "foo",',
            '  versions = ["1.0", "2.0"],',
            ')',
            'ext = use_extension("@ext//:ext.bzl", "ext")',
            'use_repo(ext, myrepo="repo1")',
            'ext2 = use_extension("@ext2//:ext.bzl", "ext")',
            'ext2.dep(name="repo1")',
            'use_repo(ext2, myrepo2="repo1")',
        ],
    )
    self.main_registry.createCcModule(
        'foo',
        '1.0',
        {'bar': '1.0', 'ext': '1.0'},
        {'bar': 'bar_from_foo1'},
        extra_module_file_contents=[
            'my_ext = use_extension("@ext//:ext.bzl", "ext")',
            'my_ext.dep(name="repo1")',
            'my_ext.dep(name="repo2")',
            'my_ext.dep(name="repo5")',
            'use_repo(my_ext, my_repo1="repo1")',
        ],
    )
    self.main_registry.createCcModule(
        'foo',
        '2.0',
        {'bar': '2.0', 'ext': '1.0'},
        {'bar': 'bar_from_foo2', 'ext': 'ext_mod'},
        extra_module_file_contents=[
            'my_ext = use_extension("@ext_mod//:ext.bzl", "ext")',
            'my_ext.dep(name="repo4")',
            'use_repo(my_ext, my_repo3="repo3", my_repo4="repo4")',
        ],
    )
    self.main_registry.createCcModule('bar', '1.0', {'ext': '1.0'})
    self.main_registry.createCcModule(
        'bar',
        '2.0',
        {'ext': '1.0', 'ext2': '1.0'},
        extra_module_file_contents=[
            'my_ext = use_extension("@ext//:ext.bzl", "ext")',
            'my_ext.dep(name="repo3")',
            'use_repo(my_ext, my_repo3="repo3")',
            'my_ext2 = use_extension("@ext2//:ext.bzl", "ext")',
            'my_ext2.dep(name="repo3")',
            'use_repo(my_ext2, my_repo2="repo3")',
        ],
    )

    ext_src = [
        'def _data_repo_impl(ctx): ctx.file("BUILD")',
        'data_repo = repository_rule(_data_repo_impl,',
        '  attrs={"data":attr.string()},',
        ')',
        'def _ext_impl(ctx):',
        '  deps = {dep.name: 1 for mod in ctx.modules for dep in mod.tags.dep}',
        '  if "fail" in deps:',
        '    fail("ext failed")',
        '  for dep in deps:',
        '    data_repo(name=dep, data="requested repo")',
        'ext=module_extension(_ext_impl,',
        '  tag_classes={"dep":tag_class(attrs={"name":attr.string()})},',
        ')',
    ]

    self.main_registry.createLocalPathModule('ext', '1.0', 'ext')
    scratchFile(self.projects_dir.joinpath('ext', 'BUILD'))
    scratchFile(self.projects_dir.joinpath('ext', 'ext.bzl'), ext_src)
    self.main_registry.createLocalPathModule('ext2', '1.0', 'ext2')
    scratchFile(self.projects_dir.joinpath('ext2', 'BUILD'))
    scratchFile(self.projects_dir.joinpath('ext2', 'ext.bzl'), ext_src)

  def tearDown(self):
    self.main_registry.stop()
    test_base.TestBase.tearDown(self)

  def testGraph(self):
    _, stdout, _ = self.RunBazel(['mod', 'graph'], rstrip=True)
    self.assertListEqual(
        stdout,
        [
            '<root> (my_project@1.0)',
            '|___ext@1.0',
            '|___ext2@1.0',
            '|___foo@1.0',
            '|   |___ext@1.0 (*)',
            '|   |___bar@2.0',
            '|       |___ext@1.0 (*)',
            '|       |___ext2@1.0 (*)',
            '|___foo@2.0',
            '    |___bar@2.0 (*)',
            '    |___ext@1.0 (*)',
            '',
        ],
        'wrong output in graph query',
    )

  def testGraphWithExtensions(self):
    _, stdout, _ = self.RunBazel(
        ['mod', 'graph', '--extension_info=all'], rstrip=True
    )
    self.assertListEqual(
        stdout,
        [
            '<root> (my_project@1.0)',
            '|___$@@ext+//:ext.bzl%ext',
            '|   |___repo1',
            '|   |...repo2',
            '|   |...repo5',
            '|___$@@ext2+//:ext.bzl%ext',
            '|   |___repo1',
            '|___ext@1.0',
            '|___ext2@1.0',
            '|___foo@1.0',
            '|   |___$@@ext+//:ext.bzl%ext ...',
            '|   |   |___repo1',
            '|   |___ext@1.0 (*)',
            '|   |___bar@2.0',
            '|       |___$@@ext+//:ext.bzl%ext ...',
            '|       |   |___repo3',
            '|       |___$@@ext2+//:ext.bzl%ext ...',
            '|       |   |___repo3',
            '|       |___ext@1.0 (*)',
            '|       |___ext2@1.0 (*)',
            '|___foo@2.0',
            '    |___$@@ext+//:ext.bzl%ext ...',
            '    |   |___repo3',
            '    |   |___repo4',
            '    |___bar@2.0 (*)',
            '    |___ext@1.0 (*)',
            '',
        ],
        'wrong output in graph with extensions query',
    )

  def testGraphWithExtensionFilter(self):
    _, stdout, _ = self.RunBazel(
        [
            'mod',
            'graph',
            '--extension_info=repos',
            '--extension_filter=@ext//:ext.bzl%ext',
        ],
        rstrip=True,
    )
    self.assertListEqual(
        stdout,
        [
            '<root> (my_project@1.0)',
            '|___$@@ext+//:ext.bzl%ext',
            '|   |___repo1',
            '|___foo@1.0 #',
            '|   |___$@@ext+//:ext.bzl%ext',
            '|   |   |___repo1',
            '|   |___bar@2.0 #',
            '|       |___$@@ext+//:ext.bzl%ext',
            '|           |___repo3',
            '|___foo@2.0 #',
            '    |___$@@ext+//:ext.bzl%ext',
            '    |   |___repo3',
            '    |   |___repo4',
            '    |___bar@2.0 (*)',
            '',
        ],
        'wrong output in graph query with extension filter specified',
    )

  def testGraphWithFailingExtensions(self):
    # Force ext2 to fail.
    with open(self.Path('MODULE.bazel'), 'a+') as f:
      f.write("ext2.dep(name = 'repo2')\n")
      f.write("ext2.dep(name = 'fail')\n")

    exit_code, stdout, stderr = self.RunBazel(
        ['mod', 'graph', '--extension_info=all'],
        rstrip=True,
        allow_failure=True,
    )
    self.AssertNotExitCode(exit_code, 0, stderr)
    self.assertIn(
        'ERROR: Results may be incomplete as 1 extension failed.', stderr
    )
    self.assertIn('\t\tfail("ext failed")', stderr)
    self.assertIn('Error in fail: ext failed', stderr)
    self.assertListEqual(
        stdout,
        [
            '<root> (my_project@1.0)',
            '|___$@@ext+//:ext.bzl%ext',
            '|   |___repo1',
            '|   |...repo2',
            '|   |...repo5',
            '|___$@@ext2+//:ext.bzl%ext',
            '|   |___repo1',
            '|___ext@1.0',
            '|___ext2@1.0',
            '|___foo@1.0',
            '|   |___$@@ext+//:ext.bzl%ext ...',
            '|   |   |___repo1',
            '|   |___ext@1.0 (*)',
            '|   |___bar@2.0',
            '|       |___$@@ext+//:ext.bzl%ext ...',
            '|       |   |___repo3',
            '|       |___$@@ext2+//:ext.bzl%ext ...',
            '|       |   |___repo3',
            '|       |___ext@1.0 (*)',
            '|       |___ext2@1.0 (*)',
            '|___foo@2.0',
            '    |___$@@ext+//:ext.bzl%ext ...',
            '    |   |___repo3',
            '    |   |___repo4',
            '    |___bar@2.0 (*)',
            '    |___ext@1.0 (*)',
            '',
        ],
        'wrong output in graph with extensions query',
    )

  def testShowExtensionAllUsages(self):
    _, stdout, _ = self.RunBazel(
        ['mod', 'show_extension', '@ext//:ext.bzl%ext'], rstrip=True
    )
    self.assertRegex(
        stdout.pop(9), r'^## Usage in <root> from .*MODULE\.bazel:11$'
    )
    self.assertRegex(
        stdout.pop(14), r'^## Usage in foo@1.0 from .*MODULE\.bazel:8$'
    )
    self.assertRegex(
        stdout.pop(22), r'^## Usage in foo@2.0 from .*MODULE\.bazel:8$'
    )
    self.assertRegex(
        stdout.pop(29), r'^## Usage in bar@2.0 from .*MODULE\.bazel:8$'
    )
    self.assertListEqual(
        stdout,
        [
            '## @@ext+//:ext.bzl%ext:',
            '',
            'Fetched repositories:',
            '  - repo1 (imported by <root>, foo@1.0)',
            '  - repo3 (imported by bar@2.0, foo@2.0)',
            '  - repo4 (imported by foo@2.0)',
            '  - repo2',
            '  - repo5',
            '',
            # pop(9)
            'use_repo(',
            '  ext,',
            '  myrepo="repo1",',
            ')',
            '',
            # pop(14)
            'ext.dep(name="repo1")',
            'ext.dep(name="repo2")',
            'ext.dep(name="repo5")',
            'use_repo(',
            '  ext,',
            '  my_repo1="repo1",',
            ')',
            '',
            # pop(22)
            'ext.dep(name="repo4")',
            'use_repo(',
            '  ext,',
            '  my_repo3="repo3",',
            '  my_repo4="repo4",',
            ')',
            '',
            # pop(29)
            'ext.dep(name="repo3")',
            'use_repo(',
            '  ext,',
            '  my_repo3="repo3",',
            ')',
            '',
        ],
        'wrong output in show_extension query with all usages',
    )

  def testShowExtensionSomeExtensionsSomeUsages(self):
    _, stdout, _ = self.RunBazel(
        [
            'mod',
            'show_extension',
            '@ext//:ext.bzl%ext',
            '@ext2//:ext.bzl%ext',
            '--extension_usages=@foo2,bar@2.0',
        ],
        rstrip=True,
    )
    self.assertRegex(
        stdout.pop(9), r'^## Usage in foo@2.0 from .*MODULE\.bazel:8$'
    )
    self.assertRegex(
        stdout.pop(16), r'^## Usage in bar@2.0 from .*MODULE\.bazel:8$'
    )
    self.assertRegex(
        stdout.pop(28), r'^## Usage in bar@2.0 from .*MODULE\.bazel:11$'
    )
    self.assertListEqual(
        stdout,
        [
            '## @@ext+//:ext.bzl%ext:',
            '',
            'Fetched repositories:',
            '  - repo1 (imported by <root>, foo@1.0)',
            '  - repo3 (imported by bar@2.0, foo@2.0)',
            '  - repo4 (imported by foo@2.0)',
            '  - repo2',
            '  - repo5',
            '',
            # pop(9)
            'ext.dep(name="repo4")',
            'use_repo(',
            '  ext,',
            '  my_repo3="repo3",',
            '  my_repo4="repo4",',
            ')',
            '',
            # pop(16)
            'ext.dep(name="repo3")',
            'use_repo(',
            '  ext,',
            '  my_repo3="repo3",',
            ')',
            '',
            '## @@ext2+//:ext.bzl%ext:',
            '',
            'Fetched repositories:',
            '  - repo1 (imported by <root>)',
            '  - repo3 (imported by bar@2.0)',
            '',
            # pop(28)
            'ext.dep(name="repo3")',
            'use_repo(',
            '  ext,',
            '  my_repo2="repo3",',
            ')',
            '',
        ],
        'Wrong output in the show with some extensions and some usages query.',
    )

  def testShowExtensionWithUnknownRepo(self):
    _, _, stderr = self.RunBazel(
        [
            'mod',
            'show_extension',
            '@@unknown//foo:bar.bzl%x',
        ],
        allow_failure=True,
        rstrip=True,
    )
    self.assertIn(
        'ERROR: In extension argument @@unknown//foo:bar.bzl%x: No module with '
        'the canonical repo name @@unknown exists in the dependency graph.',
        '\n'.join(stderr),
    )

  def testShowExtensionWithUnknownExtension(self):
    _, _, stderr = self.RunBazel(
        [
            'mod',
            'show_extension',
            '@ext//foo:unknown.bzl%x',
        ],
        allow_failure=True,
        rstrip=True,
    )
    self.assertIn(
        'No extension @@ext+//foo:unknown.bzl%x exists in the dependency graph',
        '\n'.join(stderr),
    )

  def testShowModuleAndExtensionReposFromBaseModule(self):
    _, stdout, _ = self.RunBazel(
        [
            'mod',
            'show_repo',
            '--base_module=foo@2.0',
            '@bar_from_foo2',
            'ext@1.0',
            '@my_repo3',
            '@my_repo4',
            'bar',
        ],
        rstrip=True,
    )
    self.assertRegex(stdout.pop(4), r'^  urls = \[".*"\],$')
    self.assertRegex(stdout.pop(4), r'^  integrity = ".*",$')
    self.assertRegex(stdout.pop(19), r'^  path = ".*",$')
    # lines after '# Rule local_repository defined at (most recent call last):'
    stdout.pop(23)
    # lines after '# Rule data_repo defined at (most recent call last):'
    stdout.pop(34)
    stdout.pop(45)
    self.assertRegex(stdout.pop(50), r'^  urls = \[".*"\],$')
    self.assertRegex(stdout.pop(50), r'^  integrity = ".*",$')
    # lines after '# Rule http_archive defined at (most recent call last):'
    stdout.pop(13)
    stdout.pop(58)
    self.assertListEqual(
        stdout,
        [
            '## @bar_from_foo2:',
            '# <builtin>',
            'http_archive(',
            '  name = "bar+",',
            # pop(4) -- urls=[...]
            # pop(4) -- integrity=...
            '  strip_prefix = "",',
            '  remote_file_urls = {},',
            '  remote_file_integrity = {},',
            '  remote_patches = {},',
            '  remote_patch_strip = 0,',
            ')',
            '# Rule bar+ instantiated at (most recent call last):',
            '#   <builtin> in <toplevel>',
            '# Rule http_archive defined at (most recent call last):',
            # pop(13)
            '',
            '## ext@1.0:',
            '# <builtin>',
            'local_repository(',
            '  name = "ext+",',
            # pop(19) -- path=...
            ')',
            '# Rule ext+ instantiated at (most recent call last):',
            '#   <builtin> in <toplevel>',
            '# Rule local_repository defined at (most recent call last):',
            # pop(23)
            '',
            '## @my_repo3:',
            '# <builtin>',
            'data_repo(',
            '  name = "ext++ext+repo3",',
            '  _original_name = "repo3",',
            '  data = "requested repo",',
            ')',
            '# Rule ext++ext+repo3 instantiated at (most recent call last):',
            '#   <builtin> in <toplevel>',
            '# Rule data_repo defined at (most recent call last):',
            # pop(34)
            '',
            '## @my_repo4:',
            '# <builtin>',
            'data_repo(',
            '  name = "ext++ext+repo4",',
            '  _original_name = "repo4",',
            '  data = "requested repo",',
            ')',
            '# Rule ext++ext+repo4 instantiated at (most recent call last):',
            '#   <builtin> in <toplevel>',
            '# Rule data_repo defined at (most recent call last):',
            # pop(45)
            '',
            '## bar@2.0:',
            '# <builtin>',
            'http_archive(',
            '  name = "bar+",',
            # pop(50) -- urls=[...]
            # pop(50) -- integrity=...
            '  strip_prefix = "",',
            '  remote_file_urls = {},',
            '  remote_file_integrity = {},',
            '  remote_patches = {},',
            '  remote_patch_strip = 0,',
            ')',
            '# Rule bar+ instantiated at (most recent call last):',
            '#   <builtin> in <toplevel>',
            '# Rule http_archive defined at (most recent call last):',
            # pop(58)
            '',
        ],
        'wrong output in the show query for module and extension-generated'
        ' repos',
    )

  def testShowRepoThrowsUnusedModule(self):
    _, _, stderr = self.RunBazel(
        ['mod', 'show_repo', 'bar@1.0', '--base_module=@foo2'],
        allow_failure=True,
        rstrip=True,
    )
    self.assertIn(
        'ERROR: In repo argument bar@1.0: Module version bar@1.0 does not'
        ' exist, available versions: [bar@2.0]. (Note that unused modules'
        " cannot be used here). Type 'bazel help mod' for syntax and help.",
        stderr,
    )

  # fix for https://github.com/bazelbuild/bazel/issues/22587
  def testShowRepoThrowsNonexistentRepo(self):
    _, _, stderr = self.RunBazel(
        ['mod', 'show_repo', '<root>'],
        allow_failure=True,
        rstrip=True,
    )
    self.assertIn(
        "ERROR: In repo argument <root>: no such repo. Type 'bazel help mod' "
        'for syntax and help.',
        stderr,
    )

  def testDumpRepoMapping(self):
    _, stdout, _ = self.RunBazel(
        [
            'mod',
            'dump_repo_mapping',
            '',
            'foo+2.0',
        ],
    )
    root_mapping, foo_mapping = [json.loads(l) for l in stdout]

    self.assertContainsSubset(
        {
            'my_project': '',
            'foo1': 'foo+1.0',
            'foo2': 'foo+2.0',
            'myrepo2': 'ext2++ext+repo1',
            'bazel_tools': 'bazel_tools',
        }.items(),
        root_mapping.items(),
    )

    self.assertContainsSubset(
        {
            'foo': 'foo+2.0',
            'ext_mod': 'ext+',
            'my_repo3': 'ext++ext+repo3',
            'bazel_tools': 'bazel_tools',
        }.items(),
        foo_mapping.items(),
    )

  def testDumpRepoMappingThrowsNoRepos(self):
    _, _, stderr = self.RunBazel(
        ['mod', 'dump_repo_mapping'],
        allow_failure=True,
    )
    self.assertIn(
        "ERROR: No repository name(s) specified. Type 'bazel help mod' for"
        ' syntax and help.',
        stderr,
    )

  def testDumpRepoMappingThrowsInvalidRepoName(self):
    _, _, stderr = self.RunBazel(
        ['mod', 'dump_repo_mapping', '{}'],
        allow_failure=True,
    )
    self.assertIn(
        "ERROR: invalid repository name '{}': repo names may contain only A-Z,"
        " a-z, 0-9, '-', '_', '.' and '+'. Type 'bazel help mod' for syntax"
        ' and help.',
        stderr,
    )

  def testDumpRepoMappingThrowsUnknownRepoName(self):
    _, _, stderr = self.RunBazel(
        ['mod', 'dump_repo_mapping', 'does_not_exist'],
        allow_failure=True,
    )
    self.assertIn(
        "ERROR: Repositories not found: does_not_exist. Type 'bazel help mod'"
        ' for syntax and help.',
        stderr,
    )

  def testModTidy(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext1 = use_extension("//:extension.bzl", "ext1")',
            'use_repo(ext1, "dep", "indirect_dep")',
            'ext1_isolated = use_extension(',
            '    "//:extension.bzl",',
            '    "ext1",',
            '    isolate = True,',
            ')',
            'use_repo(',
            '    ext1_isolated,',
            '    my_dep = "dep",',
            '    my_missing_dep = "missing_dep",',
            '    my_indirect_dep = "indirect_dep",',
            ')',
            (
                'ext2 = use_extension("//:extension.bzl", "ext2",'
                ' dev_dependency = True)'
            ),
            'use_repo(ext2, "dev_dep", "indirect_dev_dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext1_impl(ctx):',
            '    print("ext1 is being evaluated")',
            '    repo_rule(name="dep")',
            '    repo_rule(name="missing_dep")',
            '    repo_rule(name="indirect_dep")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=["dep", "missing_dep"],',
            '        root_module_direct_dev_deps=[],',
            '    )',
            '',
            'ext1 = module_extension(implementation=_ext1_impl)',
            '',
            'def _ext2_impl(ctx):',
            '    print("ext2 is being evaluated")',
            '    repo_rule(name="dev_dep")',
            '    repo_rule(name="missing_dev_dep")',
            '    repo_rule(name="indirect_dev_dep")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=[],',
            (
                '        root_module_direct_dev_deps=["dev_dep",'
                ' "missing_dev_dep"],'
            ),
            '    )',
            '',
            'ext2 = module_extension(implementation=_ext2_impl)',
        ],
    )

    # Create a lockfile and let the extension evaluations emit fixup warnings.
    _, _, stderr = self.RunBazel([
        'mod',
        'deps',
        '--lockfile_mode=update',
        '--experimental_isolated_extension_usages',
    ])
    stderr = '\n'.join(stderr)
    self.assertIn('ext1 is being evaluated', stderr)
    self.assertIn('ext2 is being evaluated', stderr)
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

    # Run bazel mod tidy to fix the imports.
    _, stdout, stderr = self.RunBazel([
        'mod',
        'tidy',
        '--lockfile_mode=update',
        '--experimental_isolated_extension_usages',
    ])
    self.assertEqual([], stdout)
    stderr = '\n'.join(stderr)
    # The extensions should not be reevaluated by the command.
    self.assertNotIn('ext1 is being evaluated', stderr)
    self.assertNotIn('ext2 is being evaluated', stderr)
    # bazel mod tidy doesn't show fixup warnings.
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
    # Fixes are reported.
    self.assertIn(
        'INFO: Updated use_repo calls for @//:extension.bzl%ext1', stderr
    )
    self.assertIn(
        "INFO: Updated use_repo calls for isolated usage 'ext1_isolated' of"
        ' @//:extension.bzl%ext1',
        stderr,
    )
    self.assertIn(
        'INFO: Updated use_repo calls for @//:extension.bzl%ext2', stderr
    )

    # Rerun bazel mod deps to check that the fixup warnings are gone
    # and the lockfile is up-to-date.
    _, _, stderr = self.RunBazel([
        'mod',
        'deps',
        '--lockfile_mode=error',
        '--experimental_isolated_extension_usages',
    ])
    stderr = '\n'.join(stderr)
    self.assertNotIn('ext1 is being evaluated', stderr)
    self.assertNotIn('ext2 is being evaluated', stderr)
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

    # Verify that use_repo statements have been updated.
    with open('MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'ext1 = use_extension("//:extension.bzl", "ext1")',
              'use_repo(ext1, "dep", "missing_dep")',
              '',
              'ext1_isolated = use_extension(',
              '    "//:extension.bzl",',
              '    "ext1",',
              '    isolate = True,',
              ')',
              'use_repo(',
              '    ext1_isolated,',
              '    my_dep = "dep",',
              '    my_missing_dep = "missing_dep",',
              ')',
              '',
              (
                  'ext2 = use_extension("//:extension.bzl", "ext2",'
                  ' dev_dependency = True)'
              ),
              'use_repo(ext2, "dev_dep", "missing_dev_dep")',
              '',
          ],
          module_file.read().split('\n'),
      )

  def testModTidyAlwaysFormatsModuleFile(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext=use_extension("//:extension.bzl",                   "ext")',
            'use_repo(ext,  "dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="dep")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=["dep"],',
            '        root_module_direct_dev_deps=[],',
            '    )',
            '',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Verify that bazel mod tidy formats the MODULE.bazel file
    # even if there are no use_repos to fix.
    self.RunBazel(['mod', 'tidy'])

    with open('MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'ext = use_extension("//:extension.bzl", "ext")',
              'use_repo(ext, "dep")',
              # This newline is from ScratchFile.
              '',
          ],
          module_file.read().split('\n'),
      )

  def testModTidyNoop(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("//:extension.bzl", "ext")',
            'use_repo(ext, "dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="dep")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=["dep"],',
            '        root_module_direct_dev_deps=[],',
            '    )',
            '',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Verify that bazel mod tidy doesn't fail or change the file.
    self.RunBazel(['mod', 'tidy'])

    with open('MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'ext = use_extension("//:extension.bzl", "ext")',
              'use_repo(ext, "dep")',
              # This newline is from ScratchFile.
              '',
          ],
          module_file.read().split('\n'),
      )

  def testModTidyWithNonRegistryOverride(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "foo", version = "1.2.3")',
            'local_path_override(module_name = "foo", path = "foo")',
            'ext = use_extension("//:extension.bzl", "ext")',
            'use_repo(ext, "dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _ext_impl(ctx):',
            '    pass',
            '',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )
    self.ScratchFile(
        'foo/MODULE.bazel', ['module(name = "foo", version = "1.2.3")']
    )

    # Verify that bazel mod tidy doesn't fail without the lockfile.
    self.RunBazel(['mod', 'tidy'])

    with open('MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'bazel_dep(name = "foo", version = "1.2.3")',
              'local_path_override(',
              '    module_name = "foo",',
              '    path = "foo",',
              ')',
              '',
              'ext = use_extension("//:extension.bzl", "ext")',
              'use_repo(ext, "dep")',
              # This newline is from ScratchFile.
              '',
          ],
          module_file.read().split('\n'),
      )

    # Verify that bazel mod tidy doesn't fail with the lockfile.
    self.RunBazel(['mod', 'tidy'])

  def testModTidyWithoutUsages(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(  name = "foo", version = "1.2.3")',
        ],
    )

    self.RunBazel(['mod', 'tidy'])

    with open('MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'module(',
              '    name = "foo",',
              '    version = "1.2.3",',
              ')',
              # This newline is from ScratchFile.
              '',
          ],
          module_file.read().split('\n'),
      )

  def testModTidyFailsOnExtensionFailure(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("//:extension.bzl", "ext")',
            'use_repo(ext, "dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _ext_impl(ctx):',
            '    "foo"[3]',
            '',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Verify that bazel mod tidy fails if an extension fails to execute.
    exit_code, _, stderr = self.RunBazel(['mod', 'tidy'], allow_failure=True)

    self.AssertNotExitCode(exit_code, 0, stderr)
    stderr = '\n'.join(stderr)
    self.assertIn('//:extension.bzl', stderr)
    self.assertIn('Error: index out of range', stderr)
    self.assertNotIn('buildozer', stderr)

    with open('MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'ext = use_extension("//:extension.bzl", "ext")',
              'use_repo(ext, "dep")',
              '',
          ],
          module_file.read().split('\n'),
      )

  def testModTidyKeepGoing(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext1 = use_extension("//:extension.bzl", "ext1")',
            'use_repo(ext1, "dep", "indirect_dep")',
            'ext2 = use_extension("//:extension.bzl", "ext2")',
            'use_repo(ext2, "other_dep", "other_indirect_dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext1_impl(ctx):',
            '    print("ext1 is being evaluated")',
            '    repo_rule(name="dep")',
            '    repo_rule(name="missing_dep")',
            '    repo_rule(name="indirect_dep")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=["dep", "missing_dep"],',
            '        root_module_direct_dev_deps=[],',
            '    )',
            '',
            'ext1 = module_extension(implementation=_ext1_impl)',
            '',
            'def _ext2_impl(ctx):',
            '    print("ext2 is being evaluated")',
            '    fail("ext2 failed")',
            '',
            'ext2 = module_extension(implementation=_ext2_impl)',
        ],
    )

    # Create a lockfile and let the extension evaluations emit fixup warnings.
    exit_code, _, stderr = self.RunBazel(
        [
            'mod',
            'deps',
            '--lockfile_mode=update',
        ],
        allow_failure=True,
    )
    self.AssertNotExitCode(exit_code, 0, stderr)
    stderr = '\n'.join(stderr)
    self.assertIn('ext1 is being evaluated', stderr)
    self.assertIn('ext2 is being evaluated', stderr)
    self.assertIn('Error in fail: ext2 failed', stderr)
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

    # Run bazel mod tidy to fix the imports.
    exit_code, stdout, stderr = self.RunBazel(
        [
            'mod',
            'tidy',
            '--lockfile_mode=update',
        ],
        allow_failure=True,
    )
    self.AssertNotExitCode(exit_code, 0, stderr)
    self.assertEqual([], stdout)
    self.assertIn('ERROR: Failed to process 1 extension due to errors.', stderr)
    stderr = '\n'.join(stderr)
    # The passing extension should not be reevaluated by the command.
    self.assertNotIn('ext1 is being evaluated', stderr)
    self.assertIn('ext2 is being evaluated', stderr)
    # baze mod tidy doesn't show fixup warnings.
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
    # Fixes are reported.
    self.assertIn(
        'INFO: Updated use_repo calls for @//:extension.bzl%ext1', stderr
    )
    self.assertNotIn(
        'INFO: Updated use_repo calls for @//:extension.bzl%ext2', stderr
    )

    # Rerun bazel mod deps to check that the fixup warnings are gone
    # and the lockfile is up-to-date.
    exit_code, _, stderr = self.RunBazel(
        [
            'mod',
            'deps',
            '--lockfile_mode=error',
        ],
        allow_failure=True,
    )
    self.AssertNotExitCode(exit_code, 0, stderr)
    # The exit code if the build fails due to a lockfile that is not up-to-date.
    self.AssertNotExitCode(exit_code, 48, stderr)
    stderr = '\n'.join(stderr)
    self.assertNotIn('ext1 is being evaluated', stderr)
    self.assertIn('ext2 is being evaluated', stderr)
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

    # Verify that use_repo statements have been updated.
    with open('MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'ext1 = use_extension("//:extension.bzl", "ext1")',
              'use_repo(ext1, "dep", "missing_dep")',
              '',
              'ext2 = use_extension("//:extension.bzl", "ext2")',
              'use_repo(ext2, "other_dep", "other_indirect_dep")',
              '',
          ],
          module_file.read().split('\n'),
      )

  def testModTidyFixesInvalidImport(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("//:extension.bzl", "ext")',
            'use_repo(ext, "invalid_dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="dep")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=["dep"],',
            '        root_module_direct_dev_deps=[],',
            '    )',
            '',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Verify that bazel mod tidy fixes the MODULE.bazel file even though the
    # extension fails after evaluation.
    _, _, stderr = self.RunBazel(['mod', 'tidy'])
    stderr = '\n'.join(stderr)
    self.assertIn(
        'INFO: Updated use_repo calls for @//:extension.bzl%ext', stderr
    )

    with open('MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'ext = use_extension("//:extension.bzl", "ext")',
              'use_repo(ext, "dep")',
              '',
          ],
          module_file.read().split('\n'),
      )

  def testModTidyWithIncludes(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'include("//:firstProd.MODULE.bazel")',
            'include("//:second.MODULE.bazel")',
        ],
    )
    self.ScratchFile(
        'firstProd.MODULE.bazel',
        [
            'ext = use_extension("//:extension.bzl", "ext")',
            'use_repo(ext, "dep2", "bad_dep")',
            'include("//:firstDev.MODULE.bazel")',
        ],
    )
    self.ScratchFile(
        'firstDev.MODULE.bazel',
        [
            (
                'ext_dev = use_extension("//:extension.bzl", "ext",'
                ' dev_dependency = True)'
            ),
            'use_repo(ext_dev, "dev2", "bad_dev")',
        ],
    )
    self.ScratchFile(
        'second.MODULE.bazel',
        [
            'ext = use_extension("//:extension.bzl", "ext")',
            'use_repo(ext, "blad_dep")',
            (
                'ext_dev = use_extension("//:extension.bzl", "ext",'
                ' dev_dependency = True)'
            ),
            'use_repo(ext_dev, "blad_dev")',
            'ext2 = use_extension("//:extension.bzl", "ext2")',
            'use_repo(ext2, "blaad_dep")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            '',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="dep")',
            '    repo_rule(name="dep2")',
            '    repo_rule(name="dev")',
            '    repo_rule(name="dev2")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=["dep", "dep2"],',
            '        root_module_direct_dev_deps=["dev", "dev2"],',
            '    )',
            '',
            'ext = module_extension(implementation=_ext_impl)',
            '',
            'def _ext2_impl(ctx):',
            '    repo_rule(name="ext2_dep")',
            '    return ctx.extension_metadata(',
            '        root_module_direct_deps=["ext2_dep"],',
            '        root_module_direct_dev_deps=[],',
            '    )',
            '',
            'ext2 = module_extension(implementation=_ext2_impl)',
        ],
    )

    _, _, stderr = self.RunBazel(['mod', 'tidy'])
    stderr = '\n'.join(stderr)
    self.assertIn(
        'INFO: Updated use_repo calls for @//:extension.bzl%ext', stderr
    )

    with open('MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'include("//:firstProd.MODULE.bazel")',
              '',  # formatted despite no extension usages!
              'include("//:second.MODULE.bazel")',
              '',
          ],
          module_file.read().split('\n'),
      )
    with open('firstProd.MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'ext = use_extension("//:extension.bzl", "ext")',
              'use_repo(ext, "dep", "dep2")',
              '',
              'include("//:firstDev.MODULE.bazel")',
              '',
          ],
          module_file.read().split('\n'),
      )
    with open('firstDev.MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              (
                  'ext_dev = use_extension("//:extension.bzl", "ext",'
                  ' dev_dependency = True)'
              ),
              'use_repo(ext_dev, "dev", "dev2")',
              '',
          ],
          module_file.read().split('\n'),
      )
    with open('second.MODULE.bazel', 'r') as module_file:
      self.assertEqual(
          [
              'ext = use_extension("//:extension.bzl", "ext")',
              '',
              (
                  'ext_dev = use_extension("//:extension.bzl", "ext",'
                  ' dev_dependency = True)'
              ),
              '',
              'ext2 = use_extension("//:extension.bzl", "ext2")',
              'use_repo(ext2, "ext2_dep")',
              '',
          ],
          module_file.read().split('\n'),
      )


if __name__ == '__main__':
  absltest.main()
