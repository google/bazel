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
"""Tests bzlmod integration inside query (querying and external repo using the repo mapping)."""

import os
import re
import tempfile
from absl.testing import absltest
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry


class BzlmodQueryTest(test_base.TestBase):
  """Test class for bzlmod integration inside query (querying and external repo using the repo mapping)."""

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.registries_work_dir = tempfile.mkdtemp(dir=self._test_cwd)
    self.main_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'main'))
    self.main_registry.start()
    self.main_registry.createCcModule('aaa', '1.0', {'ccc': '1.2'}) \
      .createCcModule('aaa', '1.1') \
      .createCcModule('bbb', '1.0', {'aaa': '1.0'}, {'aaa': 'com_foo_bar_aaa'}) \
      .createCcModule('ccc', '1.2')

    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            'common --registry=' + self.main_registry.getURL(),
            # We need to have BCR here to make sure built-in modules like
            # bazel_tools can work.
            'common --registry=https://bcr.bazel.build',
            # Disable yanked version check so we are not affected BCR changes.
            'common --allow_yanked_versions=all',
        ],
    )

  def tearDown(self):
    self.main_registry.stop()
    test_base.TestBase.tearDown(self)

  def testQueryModuleRepoTargetsBelow(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    _, stdout, _ = self.RunBazel(['query', '@my_repo//...'])
    self.assertListEqual(['@my_repo//:lib_aaa'], stdout)

  def testQueryModuleRepoTransitiveDeps(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@my_repo//:lib_aaa"],',
        ')',
    ])
    _, stdout, _ = self.RunBazel([
        'query',
        'kind("cc_.* rule", deps(//:main))',
        '--noimplicit_deps',
        '--notool_deps',
    ])
    self.assertListEqual(
        ['//:main', '@my_repo//:lib_aaa', '@@ccc+//:lib_ccc'], stdout
    )

  def testQueryModuleRepoTransitiveDeps_consistentLabels(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@my_repo//:lib_aaa"],',
            ')',
        ],
    )
    _, stdout, _ = self.RunBazel([
        'query',
        'kind("cc_.* rule", deps(//:main))',
        '--noimplicit_deps',
        '--notool_deps',
        '--consistent_labels',
    ])
    self.assertListEqual(
        ['@@//:main', '@@aaa+//:lib_aaa', '@@ccc+//:lib_ccc'], stdout
    )

  def testQueryModuleRepoTransitiveDeps_consistentLabels_outputPackage(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        ],
    )
    self.ScratchFile(
        'pkg/BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@my_repo//:lib_aaa"],',
            ')',
        ],
    )
    _, stdout, _ = self.RunBazel([
        'query',
        'kind("cc_.* rule", deps(//pkg:main))',
        '--noimplicit_deps',
        '--notool_deps',
        '--consistent_labels',
        '--output=package',
    ])
    self.assertListEqual(['@@//pkg', '@@aaa+//', '@@ccc+//'], stdout)

  def testQueryModuleRepoTransitiveDeps_consistentLabels_outputBuild(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        ],
    )
    self.ScratchFile(
        'pkg/BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@my_repo//:lib_aaa"],',
            ')',
        ],
    )
    _, stdout, _ = self.RunBazel([
        'query',
        'kind("cc_.* rule", deps(//pkg:main))',
        '--noimplicit_deps',
        '--notool_deps',
        '--consistent_labels',
        '--output=build',
    ])
    # Verify that there are no non-canonical labels in the output.
    stdout = '\n'.join(stdout)
    self.assertEmpty(re.findall('(?<!@)@[a-z0-9.+]*//', stdout), stdout)

  def testAqueryModuleRepoTargetsBelow(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    _, stdout, _ = self.RunBazel(['aquery', '@my_repo//...'])
    # This label is stringified into a "purpose" in some action before it
    # reaches aquery code, so can't decanonicalize it.
    self.assertIn('Target: @my_repo//:lib_aaa', stdout)

  def testAqueryModuleRepoTransitiveDeps(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@my_repo//:lib_aaa"],',
        ')',
    ])
    _, stdout, _ = self.RunBazel([
        'aquery',
        'kind("cc_.* rule", deps(//:main))',
        '--noimplicit_deps',
        '--notool_deps',
    ])
    self.assertIn('Target: //:main', stdout)
    self.assertIn('Target: @my_repo//:lib_aaa', stdout)
    self.assertIn('Target: @@ccc+//:lib_ccc', stdout)

  def testAqueryModuleRepoTransitiveDeps_consistentLabels(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@my_repo//:lib_aaa"],',
            ')',
        ],
    )
    _, stdout, _ = self.RunBazel([
        'aquery',
        'kind("cc_.* rule", deps(//:main))',
        '--noimplicit_deps',
        '--notool_deps',
        '--consistent_labels',
    ])
    self.assertIn('Target: @@//:main', stdout)
    self.assertIn('Target: @@aaa+//:lib_aaa', stdout)
    self.assertIn('Target: @@ccc+//:lib_ccc', stdout)

  def testCqueryModuleRepoTargetsBelow(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    _, stdout, _ = self.RunBazel(['cquery', '@my_repo//...'])
    self.assertRegex(stdout[0], r'@my_repo//:lib_aaa \([\w\d]+\)')

  def testCqueryModuleRepoTransitiveDeps(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@my_repo//:lib_aaa"],',
        ')',
    ])
    _, stdout, _ = self.RunBazel([
        'cquery',
        'kind("cc_.* rule", deps(//:main))',
        '--noimplicit_deps',
        '--notool_deps',
    ])
    self.assertRegex(stdout[0], r'^//:main \([\w\d]+\)$')
    self.assertRegex(stdout[1], r'^@my_repo//:lib_aaa \([\w\d]+\)$')
    self.assertRegex(stdout[2], r'^@@ccc\+//:lib_ccc \([\w\d]+\)$')
    self.assertEqual(len(stdout), 3)

  def testCqueryModuleRepoTransitiveDeps_consistentLabels(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@my_repo//:lib_aaa"],',
            ')',
        ],
    )
    _, stdout, _ = self.RunBazel([
        'cquery',
        'kind("cc_.* rule", deps(//:main))',
        '--noimplicit_deps',
        '--notool_deps',
        '--consistent_labels',
    ])
    self.assertRegex(stdout[0], r'^@@//:main \([\w\d]+\)$')
    self.assertRegex(stdout[1], r'^@@aaa\+//:lib_aaa \([\w\d]+\)$')
    self.assertRegex(stdout[2], r'^@@ccc\+//:lib_ccc \([\w\d]+\)$')
    self.assertEqual(len(stdout), 3)

  def testFetchModuleRepoTargetsBelow(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    self.RunBazel(['fetch', '@my_repo//...'])

  def testGenQueryTargetLiteralInGenRule(self):
    self.ScratchFile('MODULE.bazel', [
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    self.ScratchFile('BUILD', [
        "genquery(name='rinne',", "scope= ['@my_repo//:lib_aaa'],",
        "expression = '@my_repo//:lib_aaa' )", "genrule(name='gen_rinne',",
        "srcs = [':rinne'],", "outs = ['gen_rinne.txt'],",
        "cmd = 'cat $(SRCS) > $@')"
    ])
    self.RunBazel(['build', '//:gen_rinne'])
    output_file = open('bazel-bin/gen_rinne.txt', 'r')
    self.assertIsNotNone(output_file)
    output = output_file.readlines()
    output_file.close()
    self.assertListEqual(['@@aaa+//:lib_aaa\n'], output)

  def testQueryCannotResolveRepoMapping_malformedModuleFile(self):
    self.ScratchFile('MODULE.bazel', [
        'module(namex="my_module", version = "1.0")',
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    exit_code, _, stderr = self.RunBazel(['query', '@my_repo//...'],
                                         allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'ERROR: Error computing the main repository mapping: error executing MODULE.bazel file for <root>',
        stderr)

  def testFetchCannotResolveRepoMapping_malformedModuleFile(self):
    self.ScratchFile('MODULE.bazel', [
        'module(namex="my_module", version = "1.0")',
        'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
        'bazel_dep(name = "bbb", version = "1.0")',
    ])
    exit_code, _, stderr = self.RunBazel(['fetch', '@my_repo//...'],
                                         allow_failure=True)
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'ERROR: Error computing the main repository mapping: error executing MODULE.bazel file for <root>',
        stderr)


if __name__ == '__main__':
  absltest.main()
