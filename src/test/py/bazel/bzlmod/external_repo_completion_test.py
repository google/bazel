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
"""Tests the bash completion for external repositories."""

import os
import subprocess
import tempfile
from absl.testing import absltest
import runfiles
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry
from src.test.py.bazel.bzlmod.test_utils import scratchFile


class ExternalRepoCompletionTest(test_base.TestBase):
  """Test class for bash completion for external."""

  def setUp(self):
    test_base.TestBase.setUp(self)
    r = runfiles.Create()
    self.completion_script = r.Rlocation('io_bazel/scripts/bazel-complete.bash')
    self.bazel_binary = r.Rlocation('io_bazel/src/bazel')

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
            # The command completion script invokes bazel with arguments we
            # don't control, so we need to import the default test .bazelrc
            # here.
            'import ' + self._test_bazelrc,
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            'common --registry=' + self.main_registry.getURL(),
            # We need to have BCR here to make sure built-in modules like
            # bazel_tools can work.
            'common --registry=https://bcr.bazel.build',
            # Disable yanked version check so we are not affected BCR changes.
            'common --allow_yanked_versions=all',
            # Make sure Bazel CI tests pass in all environments
            'common --charset=ascii',
        ],
    )

    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name = "my_project", version = "1.0")',
            '',
            'bazel_dep(name = "foo", version = "1.0", repo_name = "foo")',
            'bazel_dep(name = "foo", version = "2.0", repo_name = "foobar")',
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
    self.ScratchFile('pkg/BUILD', ['cc_library(name = "my_lib")'])
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
        '  for dep in deps:',
        '    data_repo(name=dep, data="requested repo")',
        'ext=module_extension(_ext_impl,',
        '  tag_classes={"dep":tag_class(attrs={"name":attr.string()})},',
        ')',
    ]

    self.main_registry.createLocalPathModule('ext', '1.0', 'ext')
    scratchFile(
        self.projects_dir.joinpath('ext', 'BUILD'),
        ['cc_library(name="lib_ext", visibility = ["//visibility:public"])'],
    )
    scratchFile(
        self.projects_dir.joinpath('ext', 'tools', 'BUILD'),
        ['cc_binary(name="tool")'],
    )
    scratchFile(
        self.projects_dir.joinpath('ext', 'tools', 'zip', 'BUILD'),
        ['cc_binary(name="zipper")'],
    )
    scratchFile(self.projects_dir.joinpath('ext', 'ext.bzl'), ext_src)
    self.main_registry.createLocalPathModule('ext2', '1.0', 'ext2')
    scratchFile(
        self.projects_dir.joinpath('ext2', 'BUILD'),
        ['cc_library(name="lib_ext2", visibility = ["//visibility:public"])'],
    )
    scratchFile(self.projects_dir.joinpath('ext2', 'ext.bzl'), ext_src)

  def tearDown(self):
    self.main_registry.stop()
    test_base.TestBase.tearDown(self)

  def complete(self, bazel_args):
    """Get the bash completions for the given "bazel" command line."""

    # The full command line to complete as typed by the user
    # (may end with a space).
    comp_line = 'bazel ' + bazel_args
    # The index of the cursor position relative to the beginning of COMP_LINE.
    comp_point = len(comp_line)
    # The index of the word to be completed in COMP_LINE.
    comp_cword = len(comp_line.split(' '))
    script = """
source {completion_script}
COMP_WORDS=({comp_line})
_bazel__complete
echo ${{COMPREPLY[*]}}
""".format(
        completion_script=self.completion_script,
        comp_line=comp_line,
    )
    env = os.environ.copy()
    env.update({
        # Have the completion script use the Bazel binary provided by the test
        # runner.
        'BAZEL': self.bazel_binary,
        'COMP_LINE': comp_line,
        'COMP_POINT': str(comp_point),
        'COMP_CWORD': str(comp_cword),
    })
    p = subprocess.Popen(
        ['bash', '-c', script],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    stdout, _ = p.communicate()
    return stdout.decode('utf-8').split()

  def testCompletions(self):
    # The completion requires an external repository to have been fetched to
    # complete its contents. We use RunProgram instead of RunBazel as the latter
    # would evaluate the test .bazelrc twice, which we explicitly import in the
    # test workspace's .bazelrc.
    self.RunProgram([self.bazel_binary, 'fetch', '@ext//...', '@foobar//...'])

    # Apparent repo names are completed.
    self.assertCountEqual(
        [
            '@',
            '@//',
            '@bazel_tools',
            '@local_config_platform',
            '@foo',
            '@foobar',
            '@my_project',
            '@myrepo',
            '@myrepo2',
            '@ext',
            '@ext2',
        ],
        self.complete('build @'),
    )
    self.assertCountEqual(['@foo', '@foobar'], self.complete('build @fo'))
    self.assertCountEqual(
        ['@foo', '@foo//', '@foobar'], self.complete('build @foo')
    )
    self.assertCountEqual(
        ['@foobar', '@foobar//'], self.complete('build @foobar')
    )
    self.assertCountEqual(['@my_project'], self.complete('build @my_'))
    self.assertCountEqual([], self.complete('build @does_not_exist'))

    # Canonical repo names are not completed.
    self.assertCountEqual([], self.complete('build @@'))
    self.assertCountEqual([], self.complete('build @@foo+2.'))

    # Packages are completed in external repos with apparent repo names.
    self.assertCountEqual(
        ['@ext//tools/', '@ext//tools:'], self.complete('build @ext//tool')
    )
    self.assertCountEqual(
        ['@ext//tools/zip/', '@ext//tools/zip:'],
        self.complete('build @ext//tools/zi'),
    )
    self.assertCountEqual(
        ['@my_project//pkg/', '@my_project//pkg:'],
        self.complete('build @my_project//p'),
    )
    self.assertCountEqual(['@//pkg/', '@//pkg:'], self.complete('build @//p'))
    self.assertCountEqual([], self.complete('build @does_not_exist//'))

    # Packages are completed in external repos with canonical repo names.
    self.assertCountEqual(
        ['@@ext+//tools/', '@@ext+//tools:'],
        self.complete('build @@ext+//tool'),
    )
    self.assertCountEqual(
        ['@@ext+//tools/zip/', '@@ext+//tools/zip:'],
        self.complete('build @@ext+//tools/zi'),
    )
    self.assertCountEqual(
        ['@@//pkg/', '@@//pkg:'], self.complete('build @@//p')
    )
    self.assertCountEqual([], self.complete('build @@does_not_exist//'))

    # Targets are completed in external repos with apparent repo names.
    self.assertCountEqual(['@foobar//:'], self.complete('build @foobar/'))
    self.assertCountEqual(['@foobar//:'], self.complete('build @foobar//'))
    # Completions operate on the last word, which is broken on ':'.
    self.assertCountEqual(['lib_foo'], self.complete('build @foobar//:'))
    self.assertCountEqual(
        ['zipper'], self.complete('build @ext//tools/zip:zipp')
    )
    self.assertCountEqual(
        ['my_lib'], self.complete('build @my_project//pkg:my_')
    )

    # Targets are completed in external repos with canonical repo names.
    self.assertCountEqual(['lib_foo'], self.complete('build @@foo+2.0//:'))
    self.assertCountEqual(
        ['zipper'], self.complete('build @@ext+//tools/zip:zipp')
    )
    self.assertCountEqual(['my_lib'], self.complete('build @@//pkg:my_'))


if __name__ == '__main__':
  absltest.main()
