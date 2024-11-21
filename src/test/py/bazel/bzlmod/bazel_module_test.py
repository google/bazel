# pylint: disable=g-backslash-continuation
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
# pylint: disable=g-long-ternary

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
from absl.testing import absltest
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry
from src.test.py.bazel.bzlmod.test_utils import integrity
from src.test.py.bazel.bzlmod.test_utils import read
from src.test.py.bazel.bzlmod.test_utils import scratchFile


class BazelModuleTest(test_base.TestBase):

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.registries_work_dir = tempfile.mkdtemp(dir=self._test_cwd)
    self.main_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'main'))
    self.main_registry.start()
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'aaa', '1.1'
    ).createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}, {'aaa': 'com_foo_aaa'}
    ).createCcModule(
        'bbb', '1.1', {'aaa': '1.1'}
    ).createCcModule(
        'ccc', '1.1', {'aaa': '1.1', 'bbb': '1.1'}
    )
    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            'build --noenable_workspace',
            'build --incompatible_disable_native_repo_rules',
            'build --registry=' + self.main_registry.getURL(),
            # We need to have BCR here to make sure built-in modules like
            # bazel_tools can work.
            'build --registry=https://bcr.bazel.build',
            'build --verbose_failures',
            # Set an explicit Java language version
            'build --java_language_version=8',
            'build --tool_java_language_version=8',
            'build --lockfile_mode=update',
            (  # fmt: skip pylint: disable=line-too-long
                'build'
                ' --extra_toolchains=@bazel_tools//tools/python:autodetecting_toolchain'
            ),
        ],
    )

  def tearDown(self):
    self.main_registry.stop()
    test_base.TestBase.tearDown(self)

  def writeMainProjectFiles(self):
    self.ScratchFile('aaa.patch', [
        '--- a/aaa.cc',
        '+++ b/aaa.cc',
        '@@ -1,6 +1,6 @@',
        ' #include <stdio.h>',
        ' #include "aaa.h"',
        ' void hello_aaa(const std::string& caller) {',
        '-    std::string lib_name = "aaa@1.0";',
        '+    std::string lib_name = "aaa@1.0 (locally patched)";',
        '     printf("%s => %s\\n", caller.c_str(), lib_name.c_str());',
        ' }',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = [',
        '    "@aaa//:lib_aaa",',
        '    "@bbb//:lib_bbb",',
        '  ],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        '#include "aaa.h"',
        '#include "bbb.h"',
        'int main() {',
        '    hello_aaa("main function");',
        '    hello_bbb("main function");',
        '}',
    ])

  def testSimple(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@aaa//:lib_aaa"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        '#include "aaa.h"',
        'int main() {',
        '    hello_aaa("main function");',
        '}',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => aaa@1.0', stdout)

  def testSimpleTransitive(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@bbb//:lib_bbb"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        '#include "bbb.h"',
        'int main() {',
        '    hello_bbb("main function");',
        '}',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => bbb@1.0', stdout)
    self.assertIn('bbb@1.0 => aaa@1.0', stdout)

  def testSimpleDiamond(self):
    self.writeMainProjectFiles()
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.1")',
            # bbb@1.0 has to depend on aaa@1.1 after MVS.
            'bazel_dep(name = "bbb", version = "1.0")',
        ])
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => aaa@1.1', stdout)
    self.assertIn('main function => bbb@1.0', stdout)
    self.assertIn('bbb@1.0 => aaa@1.1', stdout)

  def testRemotePatchForBazelDep(self):
    patch_file = self.ScratchFile('aaa.patch', [
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
    ])
    self.main_registry.createCcModule(
        'aaa', '1.1-1', patches=[patch_file], patch_strip=1)
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.1-1")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@aaa//:lib_aaa"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        '#include "aaa.h"',
        'int main() {',
        '    hello_aaa("main function");',
        '}',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => aaa@1.1-1 (remotely patched)', stdout)

  def testArchiveOverrideWithMainRepoLabelPatch(self):
    self.ScratchFile(
        'aaa.patch',
        [
            '--- a/aaa.cc',
            '+++ b/aaa.cc',
            '@@ -1,6 +1,6 @@',
            ' #include <stdio.h>',
            ' #include "aaa.h"',
            ' void hello_aaa(const std::string& caller) {',
            '-    std::string lib_name = "aaa@lol";',
            '+    std::string lib_name = "aaa@lol (locally patched)";',
            '     printf("%s => %s\\n", caller.c_str(), lib_name.c_str());',
            ' }',
        ],
    )
    self.main_registry.createCcModule('aaa', 'lol')
    integ = integrity(read(self.main_registry.archives.joinpath('aaa.lol.zip')))
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(repo_name="foo")',
            'bazel_dep(name = "aaa")',
            'archive_override(',
            '  module_name="aaa",',
            '  urls=["%s/archives/aaa.lol.zip"],' % self.main_registry.getURL(),
            '  integrity="%s",' % integ,
            '  patches=["@foo//:aaa.patch"],',
            '  patch_strip=1,',
            ')',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@aaa//:lib_aaa"],',
            ')',
        ],
    )
    self.ScratchFile(
        'main.cc',
        [
            '#include "aaa.h"',
            'int main() {',
            '    hello_aaa("main function");',
            '}',
        ],
    )
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => aaa@lol (locally patched)', stdout)

  def testArchiveOverrideWithBadLabelPatch(self):
    self.main_registry.createCcModule('aaa', '1')
    self.main_registry.createCcModule('bbb', '1')
    integ = integrity(read(self.main_registry.archives.joinpath('aaa.1.zip')))
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(repo_name="foo")',
            'bazel_dep(name = "aaa")',
            'bazel_dep(name = "bbb", version = "1")',
            'archive_override(',
            '  module_name="aaa",',
            '  urls=["%s/archives/aaa.1.zip"],' % self.main_registry.getURL(),
            '  integrity="%s",' % integ,
            '  patches=["@bbb//:aaa.patch"])',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@aaa//:lib_aaa"],',
            ')',
        ],
    )
    self.ScratchFile(
        'main.cc',
        [
            '#include "aaa.h"',
            'int main() {',
            '    hello_aaa("main function");',
            '}',
        ],
    )
    exit_code, _, stderr = self.RunBazel(['run', '//:main'], allow_failure=True)
    self.AssertNotExitCode(exit_code, 0, stderr)
    self.assertIn("@@[unknown repo 'bbb' requested from @@]", '\n'.join(stderr))

  def testRepoNameForBazelDep(self):
    self.writeMainProjectFiles()
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo_a_name")',
            # bbb should still be able to access aaa as com_foo_aaa
            'bazel_dep(name = "bbb", version = "1.0")',
        ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = [',
        '    "@my_repo_a_name//:lib_aaa",',
        '    "@bbb//:lib_bbb",',
        '  ],',
        ')',
    ])
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => aaa@1.0', stdout)
    self.assertIn('main function => bbb@1.0', stdout)
    self.assertIn('bbb@1.0 => aaa@1.0', stdout)

  def testCheckDirectDependencies(self):
    self.writeMainProjectFiles()
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0")',
        'bazel_dep(name = "bbb", version = "1.0")',
        'bazel_dep(name = "ccc", version = "1.1")',
    ])
    _, stdout, stderr = self.RunBazel(
        ['run', '//:main', '--check_direct_dependencies=warning']
    )
    stderr = '\n'.join(stderr)
    self.assertIn(
        'WARNING: For repository \'aaa\', the root module requires module version aaa@1.0, but got aaa@1.1 in the resolved dependency graph.',
        stderr)
    self.assertIn(
        'WARNING: For repository \'bbb\', the root module requires module version bbb@1.0, but got bbb@1.1 in the resolved dependency graph.',
        stderr)
    self.assertIn('main function => aaa@1.1', stdout)
    self.assertIn('main function => bbb@1.1', stdout)
    self.assertIn('bbb@1.1 => aaa@1.1', stdout)

    exit_code, _, stderr = self.RunBazel(
        ['run', '//:main', '--check_direct_dependencies=error'],
        allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    stderr = '\n'.join(stderr)
    self.assertIn(
        'ERROR: For repository \'aaa\', the root module requires module version aaa@1.0, but got aaa@1.1 in the resolved dependency graph.',
        stderr)
    self.assertIn(
        'ERROR: For repository \'bbb\', the root module requires module version bbb@1.0, but got bbb@1.1 in the resolved dependency graph.',
        stderr)

  def testRepositoryRuleErrorInModuleExtensionFailsTheBuild(self):
    self.ScratchFile('MODULE.bazel', [
        'module_ext = use_extension("//pkg:extension.bzl", "module_ext")',
        'use_repo(module_ext, "foo")',
    ])
    self.ScratchFile('pkg/BUILD.bazel')
    self.ScratchFile(
        'pkg/rules.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    pass',
            'repo_rule = repository_rule(implementation = _repo_rule_impl)',
        ],
    )
    self.ScratchFile('pkg/extension.bzl', [
        'load(":rules.bzl", "repo_rule")',
        'def _module_ext_impl(ctx):',
        '    repo_rule(name = "foo", invalid_attr = "value")',
        'module_ext = module_extension(implementation = _module_ext_impl)',
    ])
    exit_code, _, stderr = self.RunBazel(['run', '@foo//...'],
                                         allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    stderr = '\n'.join(stderr)
    self.assertIn(
        '/pkg/extension.bzl:3:14: //pkg:+module_ext+foo: no such attribute'
        " 'invalid_attr' in 'repo_rule' rule",
        stderr,
    )
    self.assertIn(
        '/pkg/extension.bzl", line 3, column 14, in _module_ext_impl',
        stderr,
    )

  def testDownload(self):
    data_path = self.ScratchFile('data.txt', ['some data'])
    data_url = pathlib.Path(data_path).resolve().as_uri()
    self.ScratchFile('MODULE.bazel', [
        'data_ext = use_extension("//:ext.bzl", "data_ext")',
        'use_repo(data_ext, "no_op")',
    ])
    self.ScratchFile('BUILD')
    self.ScratchFile('ext.bzl', [
        'def _no_op_impl(ctx):',
        '  ctx.file("BUILD", "filegroup(name=\\"no_op\\")")',
        'no_op = repository_rule(_no_op_impl)',
        'def _data_ext_impl(ctx):',
        '  if not ctx.download(url="%s", output="data.txt").success:' %
        data_url,
        '    fail("download failed")',
        '  if ctx.read("data.txt").strip() != "some data":',
        '    fail("unexpected downloaded content: %s" % ctx.read("data.txt").strip())',
        '  no_op(name="no_op")',
        'data_ext = module_extension(_data_ext_impl)',
    ])
    self.RunBazel(['build', '@no_op//:no_op'])

  def testNoRestart(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'data_ext = use_extension("//:ext.bzl", "data_ext")',
            'use_repo(data_ext, "no_op")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile('foo.txt', ['abc'])
    self.ScratchFile(
        'ext.bzl',
        [
            'def _no_op_impl(ctx):',
            '  ctx.file("BUILD", "filegroup(name=\\"no_op\\")")',
            'no_op = repository_rule(_no_op_impl)',
            'def _data_ext_impl(ctx):',
            '  print("I AM HERE")',
            '  ctx.watch(Label("//:foo.txt"))',
            '  no_op(name="no_op")',
            'data_ext = module_extension(_data_ext_impl)',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '@no_op//:no_op'])
    stderr = '\n'.join(stderr)
    self.assertIn('I AM HERE', stderr)
    self.assertEqual(
        stderr.count('I AM HERE'),
        1,
        'expected "I AM HERE" once, but got %s times:\n%s'
        % (stderr.count('I AM HERE'), stderr),
    )

  def setUpProjectWithLocalRegistryModule(self, dep_name, dep_version):
    self.main_registry.generateCcSource(dep_name, dep_version)
    self.main_registry.createLocalPathModule(dep_name, dep_version,
                                             dep_name + '/' + dep_version)
    self.writeCcProjectFiles(dep_name, dep_version)

  def writeCcProjectFiles(self, dep_name, dep_version):
    self.ScratchFile('main.cc', [
        '#include "%s.h"' % dep_name,
        'int main() {',
        '    hello_%s("main function");' % dep_name,
        '}',
    ])
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "%s", version = "%s")' % (dep_name, dep_version),
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@%s//:lib_%s"],' % (dep_name, dep_name),
        ')',
    ])

  def setUpProjectWithGitRegistryModule(self, dep_name, dep_version):
    src_dir = self.main_registry.generateCcSource(dep_name, dep_version)

    # Move the src_dir to a temp dir and make that temp dir a git repo.
    repo_dir = os.path.join(self.registries_work_dir, 'git_repo', dep_name)
    os.makedirs(repo_dir)
    shutil.move(
        # Workaround https://bugs.python.org/issue32689 for Python < 3.9
        str(src_dir),
        repo_dir,
    )
    repo_dir = os.path.join(repo_dir, os.path.basename(src_dir))
    subprocess.check_output(['git', 'init'], cwd=repo_dir)
    subprocess.check_output(
        ['git', 'config', 'user.email', 'example@bazel-dev.org'], cwd=repo_dir
    )
    subprocess.check_output(
        ['git', 'config', 'user.name', 'example'], cwd=repo_dir
    )
    subprocess.check_output(['git', 'add', '--all'], cwd=repo_dir)
    subprocess.check_output(['git', 'commit', '-m', 'Initialize'], cwd=repo_dir)
    commit = subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=repo_dir, universal_newlines=True
    ).strip()

    self.main_registry.createGitRepoModule(
        dep_name,
        dep_version,
        repo_dir,
        commit=commit,
        verbose=True,
        shallow_since='2000-01-02',
    )
    self.writeCcProjectFiles(dep_name, dep_version)

  def testLocalRepoInSourceJsonAbsoluteBasePath(self):
    self.main_registry.setModuleBasePath(str(self.main_registry.projects))
    self.setUpProjectWithLocalRegistryModule('sss', '1.3')
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => sss@1.3', stdout)

  def testLocalRepoInSourceJsonRelativeBasePath(self):
    self.main_registry.setModuleBasePath('projects')
    self.setUpProjectWithLocalRegistryModule('sss', '1.3')
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => sss@1.3', stdout)

  def testGitRepoAbsoluteBasePath(self):
    self.main_registry.setModuleBasePath(str(self.main_registry.projects))
    self.setUpProjectWithGitRegistryModule('sss', '1.3')
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => sss@1.3', stdout)

  def testNativePackageRelativeLabel(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name="foo")',
            'bazel_dep(name="bar")',
            'local_path_override(module_name="bar",path="bar")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'defs.bzl',
        [
            'def mac(name):',
            '  native.filegroup(name=name)',
            '  print("1st: " + str(native.package_relative_label(":bleb")))',
            '  print("2nd: " + str(native.package_relative_label('
            + '"//bleb:bleb")))',
            '  print("3rd: " + str(native.package_relative_label('
            + '"@bleb//bleb:bleb")))',
            '  print("4th: " + str(native.package_relative_label("//bleb")))',
            '  print("5th: " + str(native.package_relative_label('
            + '"@@bleb//bleb:bleb")))',
            '  print("6th: " + str(native.package_relative_label(Label('
            + '"//bleb"))))',
        ],
    )

    self.ScratchFile(
        'bar/MODULE.bazel',
        [
            'module(name="bar")',
            'bazel_dep(name="foo", repo_name="bleb")',
        ],
    )
    self.ScratchFile(
        'bar/quux/BUILD',
        [
            'load("@bleb//:defs.bzl", "mac")',
            'mac(name="book")',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '@bar//quux:book'])
    stderr = '\n'.join(stderr)
    self.assertIn('1st: @@bar+//quux:bleb', stderr)
    self.assertIn('2nd: @@bar+//bleb:bleb', stderr)
    self.assertIn('3rd: @@//bleb:bleb', stderr)
    self.assertIn('4th: @@bar+//bleb:bleb', stderr)
    self.assertIn('5th: @@bleb//bleb:bleb', stderr)
    self.assertIn('6th: @@//bleb:bleb', stderr)

  def testWorkspaceEvaluatedBzlCanSeeRootModuleMappings(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="aaa",version="1.0")',
            'bazel_dep(name="bbb",version="1.0")',
        ],
    )
    self.ScratchFile(
        'WORKSPACE.bzlmod',
        [
            (
                'load("@bazel_tools//tools/build_defs/repo:local.bzl",'
                ' "local_repository")'
            ),
            'local_repository(name="foo", path="foo", repo_mapping={',
            '  "@bar":"@baz",',
            '  "@my_aaa":"@aaa",',
            '})',
            'load("@foo//:test.bzl", "test")',
            'test()',
        ],
    )
    self.ScratchFile('foo/REPO.bazel')
    self.ScratchFile('foo/BUILD', ['filegroup(name="test")'])
    self.ScratchFile(
        'foo/test.bzl',
        [
            'def test():',
            '  print("1st: " + str(Label("@bar//:z")))',
            '  print("2nd: " + str(Label("@my_aaa//:z")))',
            '  print("3rd: " + str(Label("@bbb//:z")))',
            '  print("4th: " + str(Label("@blarg//:z")))',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '--enable_workspace', '@foo//:test'])
    stderr = '\n'.join(stderr)
    # @bar is mapped to @@baz, which Bzlmod doesn't recognize, so we leave it be
    self.assertIn('1st: @@baz//:z', stderr)
    # @my_aaa is mapped to @@aaa, which Bzlmod remaps to @@aaa+
    self.assertIn('2nd: @@aaa+//:z', stderr)
    # @bbb isn't mapped in WORKSPACE, but Bzlmod maps it to @@bbb+
    self.assertIn('3rd: @@bbb+//:z', stderr)
    # @blarg isn't mapped by WORKSPACE or Bzlmod
    self.assertIn('4th: @@blarg//:z', stderr)

  def testWorkspaceItselfCanSeeRootModuleMappings(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="hello")',
            'local_path_override(module_name="hello",path="hello")',
        ],
    )
    self.ScratchFile(
        'WORKSPACE.bzlmod',
        [
            'load("@hello//:world.bzl", "message")',
            'print(message)',
        ],
    )
    self.ScratchFile('BUILD', ['filegroup(name="a")'])
    self.ScratchFile('hello/BUILD')
    self.ScratchFile('hello/MODULE.bazel', ['module(name="hello")'])
    self.ScratchFile('hello/world.bzl', ['message="I LUV U!"'])

    _, _, stderr = self.RunBazel(['build', '--enable_workspace', ':a'])
    self.assertIn('I LUV U!', '\n'.join(stderr))

  def testNoModuleDotBazelAndFallbackToWorkspace(self):
    if os.path.exists(self.Path('MODULE.bazel')):
      os.remove(self.Path('MODULE.bazel'))
    self.ScratchFile(
        'WORKSPACE',
        [
            (
                'load("@bazel_tools//tools/build_defs/repo:local.bzl",'
                ' "local_repository")'
            ),
            'local_repository(name="hello", path="hello")',
            'load("@hello//:world.bzl", "message")',
            'print(message)',
        ],
    )
    self.ScratchFile('BUILD', ['filegroup(name="a")'])
    self.ScratchFile('hello/REPO.bazel')
    self.ScratchFile('hello/BUILD')
    self.ScratchFile('hello/world.bzl', ['message="I LUV U!"'])

    _, _, stderr = self.RunBazel(['build', '--enable_workspace', ':a'])
    self.assertIn('I LUV U!', '\n'.join(stderr))
    # MODULE.bazel file should be generated automatically
    self.assertTrue(os.path.exists(self.Path('MODULE.bazel')))

  def testArchiveWithArchiveType(self):
    # make the archive without the .zip extension
    self.main_registry.createCcModule(
        'aaa', '1.2', archive_pattern='%s.%s', archive_type='zip'
    )

    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.2")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@aaa//:lib_aaa"],',
            ')',
        ],
    )
    self.ScratchFile(
        'main.cc',
        [
            '#include "aaa.h"',
            'int main() {',
            '    hello_aaa("main function");',
            '}',
        ],
    )
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => aaa@1.2', stdout)

  def testNativeModuleNameAndVersion(self):
    self.main_registry.setModuleBasePath('projects')
    projects_dir = self.main_registry.projects

    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name="root",version="0.1")',
            'bazel_dep(name="foo",version="1.0")',
            'report_ext = use_extension("@foo//:ext.bzl", "report_ext")',
            'use_repo(report_ext, "report_repo")',
            'bazel_dep(name="bar")',
            'local_path_override(module_name="bar",path="bar")',
        ],
    )
    self.ScratchFile(
        'WORKSPACE.bzlmod',
        [
            (
                'load("@bazel_tools//tools/build_defs/repo:local.bzl",'
                ' "local_repository")'
            ),
            'local_repository(name="quux",path="quux")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'load("@foo//:report.bzl", "report")',
            'report()',
        ],
    )
    # foo: a repo defined by a normal Bazel module. Also hosts the extension
    #      `report_ext` which generates a repo `report_repo`.
    self.main_registry.createLocalPathModule('foo', '1.0', 'foo')
    projects_dir.joinpath('foo').mkdir(exist_ok=True)
    scratchFile(
        projects_dir.joinpath('foo', 'BUILD'),
        [
            'load(":report.bzl", "report")',
            'report()',
        ],
    )
    scratchFile(
        projects_dir.joinpath('foo', 'report.bzl'),
        [
            'def report():',
            '  repo = native.repository_name()',
            '  name = str(native.module_name())',
            '  version = str(native.module_version())',
            '  print("@" + repo + " reporting in: " + name + "@" + version)',
            '  native.filegroup(name="a")',
        ],
    )
    scratchFile(
        projects_dir.joinpath('foo', 'ext.bzl'),
        [
            'def _report_repo(rctx):',
            '  rctx.file("BUILD",',
            '    "load(\\"@foo//:report.bzl\\", \\"report\\")\\n" +',
            '    "report()")',
            'report_repo = repository_rule(_report_repo)',
            'report_ext = module_extension(',
            '  lambda mctx: report_repo(name="report_repo"))',
        ],
    )
    # bar: a repo defined by a Bazel module with a non-registry override
    self.ScratchFile(
        'bar/MODULE.bazel',
        [
            'module(name="bar", version="2.0")',
            'bazel_dep(name="foo",version="1.0")',
        ],
    )
    self.ScratchFile(
        'bar/BUILD',
        [
            'load("@foo//:report.bzl", "report")',
            'report()',
        ],
    )
    # quux: a repo defined by WORKSPACE
    self.ScratchFile('quux/REPO.bazel')
    self.ScratchFile(
        'quux/BUILD',
        [
            'load("@foo//:report.bzl", "report")',
            'report()',
        ],
    )

    _, _, stderr = self.RunBazel(
        [
            'build',
            '--enable_workspace',
            ':a',
            '@foo//:a',
            '@report_repo//:a',
            '@bar//:a',
            '@quux//:a',
        ],
    )
    stderr = '\n'.join(stderr)
    self.assertIn('@@ reporting in: root@0.1', stderr)
    self.assertIn('@@foo+ reporting in: foo@1.0', stderr)
    self.assertIn('@@foo++report_ext+report_repo reporting in: foo@1.0', stderr)
    self.assertIn('@@bar+ reporting in: bar@2.0', stderr)
    self.assertIn('@@quux reporting in: None@None', stderr)

  def testWorkspaceToolchainRegistrationWithPlatformsConstraint(self):
    """Regression test for https://github.com/bazelbuild/bazel/issues/17289."""
    self.ScratchFile('MODULE.bazel')
    self.ScratchFile(
        'WORKSPACE', ['register_toolchains("//:my_toolchain_toolchain")']
    )

    self.ScratchFile(
        'BUILD.bazel',
        [
            'load(":defs.bzl", "get_host_os", "my_consumer", "my_toolchain")',
            'toolchain_type(name = "my_toolchain_type")',
            'my_toolchain(',
            '    name = "my_toolchain",',
            '    my_value = "Hello, Bzlmod!",',
            ')',
            'toolchain(',
            '    name = "my_toolchain_toolchain",',
            '    toolchain = ":my_toolchain",',
            '    toolchain_type = ":my_toolchain_type",',
            '    target_compatible_with = [',
            '        "@platforms//os:" + get_host_os(),',
            '    ],',
            ')',
            'my_consumer(',
            '    name = "my_consumer",',
            ')',
        ],
    )

    self.ScratchFile(
        'defs.bzl',
        [
            (
                'load("@local_config_platform//:constraints.bzl",'
                ' "HOST_CONSTRAINTS")'
            ),
            'def _my_toolchain_impl(ctx):',
            '    return [',
            '        platform_common.ToolchainInfo(',
            '            my_value = ctx.attr.my_value,',
            '        ),',
            '    ]',
            'my_toolchain = rule(',
            '    implementation = _my_toolchain_impl,',
            '    attrs = {',
            '        "my_value": attr.string(),',
            '    },',
            ')',
            'def _my_consumer(ctx):',
            '    my_toolchain_info = ctx.toolchains["//:my_toolchain_type"]',
            '    out = ctx.actions.declare_file(ctx.attr.name)',
            (
                '    ctx.actions.write(out, "my_value ='
                ' {}".format(my_toolchain_info.my_value))'
            ),
            '    return [DefaultInfo(files = depset([out]))]',
            'my_consumer = rule(',
            '    implementation = _my_consumer,',
            '    attrs = {},',
            '    toolchains = ["//:my_toolchain_type"],',
            ')',
            'def get_host_os():',
            '    for constraint in HOST_CONSTRAINTS:',
            '        if constraint.startswith("@platforms//os:"):',
            '            return constraint.removeprefix("@platforms//os:")',
        ],
    )

    self.RunBazel([
        'build',
        '--enable_workspace',
        '//:my_consumer',
        '--toolchain_resolution_debug=//:my_toolchain_type',
    ])
    with open(self.Path('bazel-bin/my_consumer'), 'r') as f:
      self.assertEqual(f.read().strip(), 'my_value = Hello, Bzlmod!')

  def testModuleExtensionWithRuleError(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extensions.bzl", "ext")',
            'use_repo(ext, "ext")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'extensions.bzl',
        [
            'def _rule_impl(ctx):',
            '  print("RULE CALLED")',
            'init_rule = rule(_rule_impl)',
            'def ext_impl(module_ctx):',
            '  init_rule()',
            'ext = module_extension(implementation = ext_impl,)',
        ],
    )
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '@ext//:all'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Error in init_rule: Cannot instantiate a rule when loading a .bzl '
        'file. Rules may be instantiated only in a BUILD thread.',
        stderr,
    )

  def testLocationRoot(self):
    """Tests that the reported location of the MODULE.bazel file of the root module is as expected."""
    self.ScratchFile('MODULE.bazel', ['wat'])
    _, _, stderr = self.RunBazel(['build', '@what'], allow_failure=True)
    self.assertIn(
        'ERROR: ' + self.Path('MODULE.bazel').replace('\\', '/'),
        '\n'.join(stderr).replace('\\', '/'),
    )

  def testLocationRegistry(self):
    """Tests that the reported location of the MODULE.bazel file of a module from a registry is as expected."""
    self.ScratchFile('MODULE.bazel', ['bazel_dep(name="hello",version="1.0")'])
    self.main_registry.createCcModule(
        'hello', '1.0', extra_module_file_contents=['wat']
    )
    _, _, stderr = self.RunBazel(['build', '@what'], allow_failure=True)
    self.assertIn(
        'ERROR: '
        + self.main_registry.getURL()
        + '/modules/hello/1.0/MODULE.bazel',
        '\n'.join(stderr),
    )

  def testLocationNonRegistry(self):
    """Tests that the reported location of the MODULE.bazel file of a module with a non-registry override is as expected."""
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="hello")',
            'local_path_override(module_name="hello",path="hello")',
        ],
    )
    self.ScratchFile('hello/MODULE.bazel', ['wat'])
    _, _, stderr = self.RunBazel(['build', '@what'], allow_failure=True)
    self.assertIn('ERROR: @@hello+//:MODULE.bazel', '\n'.join(stderr))

  def testLoadRulesJavaSymbolThroughBazelTools(self):
    """Tests that loads from @bazel_tools that delegate to other modules resolve."""
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("//:ext.bzl", "ext")',
            'use_repo(ext, "data")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'ext.bzl',
        [
            (
                "load('@bazel_tools//tools/jdk:toolchain_utils.bzl',"
                " 'find_java_toolchain')"
            ),
            'def _repo_impl(ctx):',
            "  ctx.file('BUILD', 'exports_files([\"data.txt\"])')",
            "  ctx.file('data.txt', 'hi')",
            'repo = repository_rule(implementation = _repo_impl)',
            'def _ext_impl(ctx):',
            "  repo(name='data')",
            'ext = module_extension(implementation = _ext_impl)',
        ],
    )

    self.RunBazel(['build', '@data//:data.txt'])

  def testHttpJar(self):
    """Tests that using http_jar does not require a bazel_dep on rules_java."""

    my_jar_path = self.ScratchFile('my_jar.jar')
    my_jar_uri = pathlib.Path(my_jar_path).as_uri()

    self.ScratchFile(
        'MODULE.bazel',
        [
            (
                'http_jar ='
                ' use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl",'
                ' "http_jar")'
            ),
            'http_jar(',
            '  name = "my_jar",',
            '  url = "%s",' % my_jar_uri,
            (
                '  sha256 ='
                ' "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",'
            ),
            ')',
        ],
    )

    self.RunBazel(['build', '@my_jar//jar'])

  def testNoEnableNativeRepoRules(self):
    self.ScratchFile('MODULE.bazel')
    self.ScratchFile(
        'WORKSPACE.bzlmod', ['local_repository(name="foo",path="foo")']
    )
    self.ScratchFile('BUILD', ['filegroup(name="bar")'])
    self.ScratchFile('foo/REPO.bazel')
    self.ScratchFile('foo/BUILD', ['filegroup(name="foo")'])

    self.RunBazel([
        'build',
        '--enable_workspace',
        '--noincompatible_disable_native_repo_rules',
        '@foo',
    ])
    _, _, stderr = self.RunBazel(
        [
            'build',
            '--enable_workspace',
            '--incompatible_disable_native_repo_rules',
            '@foo',
        ],
        allow_failure=True,
    )
    self.assertIn(
        'Native repo rule local_repository is disabled', '\n'.join(stderr)
    )
    # a build that doesn't use the defined @foo should be ok.
    self.RunBazel([
        'build',
        '--enable_workspace',
        '--incompatible_disable_native_repo_rules',
        ':bar',
    ])

  def testInclude(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name="foo")',
            'bazel_dep(name="bbb", version="1.0")',
            'include("//java:java.MODULE.bazel")',
        ],
    )
    self.ScratchFile('java/BUILD')
    self.ScratchFile(
        'java/java.MODULE.bazel',
        [
            'bazel_dep(name="aaa", version="1.0", repo_name="lol")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@lol//:lib_aaa"],',
            ')',
        ],
    )
    self.ScratchFile(
        'main.cc',
        [
            '#include "aaa.h"',
            'int main() {',
            '    hello_aaa("main function");',
            '}',
        ],
    )
    _, stdout, _ = self.RunBazel(['run', '//:main'])
    self.assertIn('main function => aaa@1.0', stdout)

  def testLabelDebugPrint(self):
    """Tests that print emits labels in display form."""
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name="other_module", version="1.0")',
            (
                'local_path_override(module_name="other_module",'
                ' path="other_module")'
            ),
            'ext = use_extension("//:defs.bzl", "my_ext")',
            'use_repo(ext, "repo")',
        ],
    )

    self.ScratchFile(
        'WORKSPACE.bzlmod',
        [
            'load("//:defs.bzl", "my_repo")',
            'my_repo(name = "workspace_repo", type = "workspace")',
        ],
    )

    self.ScratchFile(
        'other_module/MODULE.bazel',
        ['module(name="other_module", version="1.0")'],
    )

    self.ScratchFile(
        'defs.bzl',
        [
            (
                'print("toplevel", Label("//:foo"),'
                ' Label("@other_module//:bar"),'
                ' Label("@@canonical_name//:baz"))'
            ),
            'def _repo_impl(ctx):',
            (
                '  print("repo:" + ctx.attr.type, Label("//:foo"),'
                ' Label("@other_module//:bar"),'
                ' Label("@@canonical_name//:baz"))'
            ),
            '  ctx.file("BUILD")',
            '  ctx.file("data.bzl", "repo_data = \\"repo\\"")',
            (
                'my_repo = repository_rule(implementation=_repo_impl,'
                ' attrs={"type":attr.string()})'
            ),
            'def _ext_impl(ctx):',
            (
                '  print("ext", Label("//:foo"), Label("@other_module//:bar"),'
                ' Label("@@canonical_name//:baz"))'
            ),
            '  my_repo(name = "repo", type = "module")',
            'my_ext = module_extension(implementation=_ext_impl)',
            'def _rule_impl(ctx):',
            (
                '  print("rule", Label("//:foo"), Label("@other_module//:bar"),'
                ' Label("@@canonical_name//:baz"))'
            ),
            'my_rule = rule(implementation=_rule_impl)',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'load("@repo//:data.bzl", "repo_data")',
            'load("@workspace_repo//:data.bzl", "repo_data")',
            'load(":defs.bzl", "my_rule")',
            'my_rule(name = "my_rule")',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '--enable_workspace', '//:my_rule'])
    stderr = '\n'.join(stderr)
    self.assertIn(
        'toplevel //:foo @other_module//:bar @@canonical_name//:baz', stderr
    )
    self.assertIn(
        'repo:module //:foo @other_module//:bar @@canonical_name//:baz', stderr
    )
    self.assertIn(
        'repo:workspace //:foo @other_module//:bar @@canonical_name//:baz',
        stderr,
    )
    self.assertIn(
        'ext //:foo @other_module//:bar @@canonical_name//:baz', stderr
    )
    self.assertIn(
        'rule //:foo @other_module//:bar @@canonical_name//:baz', stderr
    )

  def testPendingDownloadDetected(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extensions.bzl", "ext")',
            'use_repo(ext, "ext")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'extensions.bzl',
        [
            'repo_rule = repository_rule(lambda _: None)',
            'def ext_impl(module_ctx):',
            '  repo_rule(name = "ext")',
            (
                '  module_ctx.download(url = "https://bcr.bazel.build", output'
                ' = "download", block = False)'
            ),
            'ext = module_extension(implementation = ext_impl)',
        ],
    )
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '@ext//:all'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'ERROR: Pending asynchronous work after module extension ext in'
        ' @@//:extensions.bzl finished execution',
        stderr,
    )

  def testRegression22754(self):
    """Regression test for issue #22754."""
    self.ScratchFile('BUILD.bazel', ['print(glob(["testdata/**"]))'])
    self.ScratchFile('testdata/WORKSPACE')
    self.RunBazel(['build', ':all'])

  def testUnicodePaths(self):
    if sys.getfilesystemencoding() != 'utf-8':
      self.skipTest('Test requires UTF-8 by default (Python 3.7+)')

    unicode_dir = 'äöüÄÖÜß'
    self.ScratchFile(unicode_dir + '/MODULE.bazel', ['module(name = "module")'])
    self.ScratchFile(
        unicode_dir + '/BUILD',
        [
            'filegroup(name = "choose_me")',
        ],
    )
    self.writeMainProjectFiles()
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "module")',
            'local_path_override(',
            '  module_name = "module",',
            '  path = "%s",' % unicode_dir,
            ')',
        ],
    )
    self.RunBazel(['build', '@module//:choose_me'])

  def testUnicodeTags(self):
    unicode_str = 'äöüÄÖÜß'
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extensions.bzl", "ext")',
            'ext.tag(attr = "%s")' % unicode_str,
            'use_repo(ext, "ext")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'extensions.bzl',
        [
            'def repo_rule_impl(ctx):',
            '  ctx.file("BUILD")',
            '  print("DATA: " + ctx.attr.tag)',
            'repo_rule = repository_rule(',
            '  implementation = repo_rule_impl,',
            '  attrs = {',
            '    "tag": attr.string(),',
            '  },',
            ')',
            'def ext_impl(module_ctx):',
            '  repo_rule(',
            '    name = "ext",',
            '    tag = module_ctx.modules[0].tags.tag[0].attr,',
            '  )',
            'tag = tag_class(',
            '  attrs = {',
            '    "attr": attr.string(),',
            '  },',
            ')',
            'ext = module_extension(  implementation = ext_impl,',
            '  tag_classes = {',
            '    "tag": tag,',
            '  },',
            ')',
        ],
    )
    _, _, stderr = self.RunBazel(['build', '@ext//:all'])
    self.assertIn('DATA: ' + unicode_str, '\n'.join(stderr))


if __name__ == '__main__':
  absltest.main()
