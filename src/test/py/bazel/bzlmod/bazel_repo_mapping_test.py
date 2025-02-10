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
from src.test.py.bazel.bzlmod.test_utils import scratchFile


class BazelRepoMappingTest(test_base.TestBase):

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
    ).createCcModule(
        'ccc', '1.1', {'aaa': '1.1', 'bbb': '1.1'}
    )
    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
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

  def testRunfilesRepoMappingManifest(self):
    self.main_registry.setModuleBasePath('projects')
    projects_dir = self.main_registry.projects

    # Set up a "bare_rule" module that contains the "bare_test" rule which
    # passes runfiles along
    self.main_registry.createLocalPathModule('bare_rule', '1.0', 'bare_rule')
    scratchFile(projects_dir.joinpath('bare_rule', 'BUILD'))
    # The working directory of a test is the subdirectory of the runfiles
    # directory corresponding to the main repository.
    scratchFile(
        projects_dir.joinpath('bare_rule', 'defs.bzl'),
        [
            'def _bare_test_impl(ctx):',
            '  exe = ctx.actions.declare_file(ctx.label.name)',
            '  ctx.actions.write(exe,',
            (
                '    "#/bin/bash\\nif [[ ! -f ../_repo_mapping || ! -s'
                ' ../_repo_mapping ]]; then\\necho >&2 \\"ERROR: cannot find'
                ' repo mapping manifest file\\"\\nexit 1\\nfi",'
            ),
            '    True)',
            '  runfiles = ctx.runfiles(files=ctx.files.data)',
            '  for data in ctx.attr.data:',
            '    runfiles = runfiles.merge(data[DefaultInfo].default_runfiles)',
            (
                '  return DefaultInfo(files=depset(direct=[exe]),'
                ' executable=exe, runfiles=runfiles)'
            ),
            'bare_test=rule(',
            '  implementation=_bare_test_impl,',
            '  attrs={"data":attr.label_list(allow_files=True)},',
            '  test=True,',
            ')',
        ],
    )

    # Now set up a project tree shaped like a diamond
    self.ScratchFile(
        'MODULE.bazel',
        [
            'module(name="me",version="1.0")',
            'bazel_dep(name="foo",version="1.0")',
            'bazel_dep(name="bar",version="2.0")',
            'bazel_dep(name="bare_rule",version="1.0")',
            'multiple_version_override(module_name="quux",versions=["1.0","2.0"])',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'load("@bare_rule//:defs.bzl", "bare_test")',
            'bare_test(name="me",data=["@foo"])',
        ],
    )
    self.main_registry.createLocalPathModule(
        'foo', '1.0', 'foo', {'quux': '1.0', 'bare_rule': '1.0'}
    )
    self.main_registry.createLocalPathModule(
        'bar', '2.0', 'bar', {'quux': '2.0', 'bare_rule': '1.0'}
    )
    self.main_registry.createLocalPathModule(
        'quux', '1.0', 'quux1', {'bare_rule': '1.0'}
    )
    self.main_registry.createLocalPathModule(
        'quux', '2.0', 'quux2', {'bare_rule': '1.0'}
    )
    for dir_name, build_file in [
        ('foo', 'bare_test(name="foo",data=["@quux"])'),
        ('bar', 'bare_test(name="bar",data=["@quux"])'),
        ('quux1', 'bare_test(name="quux")'),
        ('quux2', 'bare_test(name="quux")'),
    ]:
      scratchFile(
          projects_dir.joinpath(dir_name, 'BUILD'),
          [
              'load("@bare_rule//:defs.bzl", "bare_test")',
              'package(default_visibility=["//visibility:public"])',
              build_file,
          ],
      )

    # We use a shell script to check that the binary itself can see the repo
    # mapping manifest. This obviously doesn't work on Windows, so we just build
    # the target. TODO(wyv): make this work on Windows by using Batch.
    # On Linux and macOS, the script is executed in the sandbox, so we verify
    # that the repository mapping is present in it.
    bazel_command = 'build' if self.IsWindows() else 'test'

    # Finally we get to build stuff!
    self.RunBazel([bazel_command, '//:me', '--test_output=errors'])

    paths = ['bazel-bin/me.repo_mapping']
    if not self.IsWindows():
      paths.append('bazel-bin/me.runfiles/_repo_mapping')
    for path in paths:
      with open(self.Path(path), 'r') as f:
        self.assertEqual(
            f.read().strip(),
            """,foo,foo+
,me,_main
foo+,foo,foo+
foo+,quux,quux+1.0
quux+1.0,quux,quux+1.0""",
        )
    with open(self.Path('bazel-bin/me.runfiles_manifest')) as f:
      self.assertIn('_repo_mapping ', f.read())

    self.RunBazel([bazel_command, '@bar//:bar', '--test_output=errors'])

    paths = ['bazel-bin/external/bar+/bar.repo_mapping']
    if not self.IsWindows():
      paths.append('bazel-bin/external/bar+/bar.runfiles/_repo_mapping')
    for path in paths:
      with open(self.Path(path), 'r') as f:
        self.assertEqual(
            f.read().strip(),
            """bar+,bar,bar+
bar+,quux,quux+2.0
quux+2.0,quux,quux+2.0""",
        )
    with open(self.Path('bazel-bin/external/bar+/bar.runfiles_manifest')) as f:
      self.assertIn('_repo_mapping ', f.read())

  def testBashRunfilesLibraryRepoMapping(self):
    self.main_registry.setModuleBasePath('projects')
    projects_dir = self.main_registry.projects

    self.main_registry.createLocalPathModule('data', '1.0', 'data')
    scratchFile(projects_dir.joinpath('data', 'foo.txt'), ['hello'])
    scratchFile(
        projects_dir.joinpath('data', 'BUILD'), ['exports_files(["foo.txt"])']
    )

    self.main_registry.createLocalPathModule(
        'test', '1.0', 'test', {'data': '1.0'}
    )
    scratchFile(
        projects_dir.joinpath('test', 'BUILD'),
        [
            'sh_test(',
            '    name = "test",',
            '    srcs = ["test.sh"],',
            '    data = ["@data//:foo.txt"],',
            '    args = ["$(rlocationpath @data//:foo.txt)"],',
            '    deps = ["@bazel_tools//tools/bash/runfiles"],',
            ')',
        ],
    )
    test_script = projects_dir.joinpath('test', 'test.sh')
    scratchFile(
        test_script,
        """#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---
[[ -f  "$(rlocation $1)" ]] || exit 1
[[ -f  "$(rlocation data/foo.txt)" ]] || exit 2
""".splitlines(),
    )
    os.chmod(test_script, 0o755)

    self.ScratchFile('MODULE.bazel', ['bazel_dep(name="test",version="1.0")'])

    # Run sandboxed on Linux and macOS.
    self.RunBazel(
        [
            'test',
            '@test//:test',
            '--test_output=errors',
            '--test_env=RUNFILES_LIB_DEBUG=1',
        ],
    )
    # Run unsandboxed on all platforms.
    self.RunBazel(
        ['run', '@test//:test'],
        env_add={'RUNFILES_LIB_DEBUG': '1'},
    )

  def testLegacyCppRunfilesLibraryRepoMapping(self):
    self.main_registry.setModuleBasePath('projects')
    projects_dir = self.main_registry.projects

    self.main_registry.createLocalPathModule('data', '1.0', 'data')
    scratchFile(projects_dir.joinpath('data', 'foo.txt'), ['hello'])
    scratchFile(
        projects_dir.joinpath('data', 'BUILD'), ['exports_files(["foo.txt"])']
    )

    self.main_registry.createLocalPathModule(
        'test', '1.0', 'test', {'data': '1.0'}
    )
    scratchFile(
        projects_dir.joinpath('test', 'BUILD'),
        [
            'cc_test(',
            '    name = "test",',
            '    srcs = ["test.cpp"],',
            '    data = ["@data//:foo.txt"],',
            '    args = ["$(rlocationpath @data//:foo.txt)"],',
            '    deps = ["@bazel_tools//tools/cpp/runfiles"],',
            ')',
        ],
    )
    scratchFile(
        projects_dir.joinpath('test', 'test.cpp'),
        [
            '#include <cstdlib>',
            '#include <fstream>',
            '#include "tools/cpp/runfiles/runfiles.h"',
            'using bazel::tools::cpp::runfiles::Runfiles;',
            'int main(int argc, char** argv) {',
            (
                '  Runfiles* runfiles = Runfiles::Create(argv[0],'
                ' BAZEL_CURRENT_REPOSITORY);'
            ),
            '  std::ifstream f1(runfiles->Rlocation(argv[1]));',
            '  if (!f1.good()) std::exit(1);',
            '  std::ifstream f2(runfiles->Rlocation("data/foo.txt"));',
            '  if (!f2.good()) std::exit(2);',
            '}',
        ],
    )

    self.ScratchFile('MODULE.bazel', ['bazel_dep(name="test",version="1.0")'])

    # Run sandboxed on Linux and macOS.
    self.RunBazel(['test', '@test//:test', '--test_output=errors'])
    # Run unsandboxed on all platforms.
    self.RunBazel(['run', '@test//:test'])

  def testCppRunfilesLibraryRepoMapping(self):
    self.main_registry.setModuleBasePath('projects')
    projects_dir = self.main_registry.projects

    self.main_registry.createLocalPathModule('data', '1.0', 'data')
    scratchFile(projects_dir.joinpath('data', 'foo.txt'), ['hello'])
    scratchFile(
        projects_dir.joinpath('data', 'BUILD'), ['exports_files(["foo.txt"])']
    )

    self.main_registry.createLocalPathModule(
        'test', '1.0', 'test', {'data': '1.0', 'rules_cc': '0.0.17'}
    )
    scratchFile(
        projects_dir.joinpath('test', 'BUILD'),
        [
            'cc_test(',
            '    name = "test",',
            '    srcs = ["test.cpp"],',
            '    data = ["@data//:foo.txt"],',
            '    args = ["$(rlocationpath @data//:foo.txt)"],',
            '    deps = ["@rules_cc//cc/runfiles"],',
            ')',
        ],
    )
    scratchFile(
        projects_dir.joinpath('test', 'test.cpp'),
        [
            '#include <cstdlib>',
            '#include <fstream>',
            '#include "rules_cc/cc/runfiles/runfiles.h"',
            'using rules_cc::cc::runfiles::Runfiles;',
            'int main(int argc, char** argv) {',
            (
                '  Runfiles* runfiles = Runfiles::Create(argv[0],'
                ' BAZEL_CURRENT_REPOSITORY);'
            ),
            '  std::ifstream f1(runfiles->Rlocation(argv[1]));',
            '  if (!f1.good()) std::exit(1);',
            '  std::ifstream f2(runfiles->Rlocation("data/foo.txt"));',
            '  if (!f2.good()) std::exit(2);',
            '}',
        ],
    )

    self.ScratchFile('MODULE.bazel', ['bazel_dep(name="test",version="1.0")'])

    # Run sandboxed on Linux and macOS.
    self.RunBazel(['test', '@test//:test', '--test_output=errors'])
    # Run unsandboxed on all platforms.
    self.RunBazel(['run', '@test//:test'])

  def testJavaRunfilesLibraryRepoMapping(self):
    self.main_registry.setModuleBasePath('projects')
    projects_dir = self.main_registry.projects

    self.main_registry.createLocalPathModule('data', '1.0', 'data')
    scratchFile(projects_dir.joinpath('data', 'foo.txt'), ['hello'])
    scratchFile(
        projects_dir.joinpath('data', 'BUILD'), ['exports_files(["foo.txt"])']
    )

    self.main_registry.createLocalPathModule(
        'test', '1.0', 'test', {'data': '1.0'}
    )
    scratchFile(
        projects_dir.joinpath('test', 'BUILD'),
        [
            'java_test(',
            '    name = "test",',
            '    srcs = ["Test.java"],',
            '    main_class = "com.example.Test",',
            '    use_testrunner = False,',
            '    data = ["@data//:foo.txt"],',
            '    args = ["$(rlocationpath @data//:foo.txt)"],',
            '    deps = ["@bazel_tools//tools/java/runfiles"],',
            ')',
        ],
    )
    scratchFile(
        projects_dir.joinpath('test', 'Test.java'),
        [
            'package com.example;',
            '',
            'import com.google.devtools.build.runfiles.AutoBazelRepository;',
            'import com.google.devtools.build.runfiles.Runfiles;',
            '',
            'import java.io.File;',
            'import java.io.IOException;',
            '',
            '@AutoBazelRepository',
            'public class Test {',
            '  public static void main(String[] args) throws IOException {',
            '    Runfiles.Preloaded rp = Runfiles.preload();',
            '    if (!new File(rp.unmapped().rlocation(args[0])).exists()) {',
            '      System.exit(1);',
            '    }',
            (
                '    if (!new'
                ' File(rp.withSourceRepository(AutoBazelRepository_Test.NAME).rlocation("data/foo.txt")).exists()) {'
            ),
            '      System.exit(1);',
            '    }',
            '  }',
            '}',
        ],
    )

    self.ScratchFile('MODULE.bazel', ['bazel_dep(name="test",version="1.0")'])

    # Run sandboxed on Linux and macOS.
    self.RunBazel(
        [
            'test',
            '@test//:test',
            '--test_output=errors',
            '--test_env=RUNFILES_LIB_DEBUG=1',
        ],
    )
    # Run unsandboxed on all platforms.
    self.RunBazel(['run', '@test//:test'], env_add={'RUNFILES_LIB_DEBUG': '1'})


if __name__ == '__main__':
  absltest.main()
