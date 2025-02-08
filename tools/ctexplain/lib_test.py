# Copyright 2020 The Bazel Authors. All rights reserved.
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
"""Tests for lib.py."""
import unittest
from src.test.py.bazel import test_base
import tools.ctexplain.bazel_api as bazel_api
import tools.ctexplain.lib as lib
from tools.ctexplain.types import Configuration
from tools.ctexplain.types import HostConfiguration
from tools.ctexplain.types import NullConfiguration


class LibTest(test_base.TestBase):

  _bazel: bazel_api.BazelApi = None

  def setUp(self):
    test_base.TestBase.setUp(self)
    self._bazel = bazel_api.BazelApi(self.RunBazel)
    self.ScratchFile('WORKSPACE')

  def tearDown(self):
    test_base.TestBase.tearDown(self)

  def testAnalyzeBuild(self):
    self.ScratchFile('testapp/defs.bzl', [
        'def _impl(ctx):',
        '    pass',
        'rule_with_host_dep = rule(',
        '    implementation = _impl,',
        '    attrs = { "host_deps": attr.label_list(cfg = "exec") })',
    ])
    self.ScratchFile('testapp/BUILD', [
        'load("//testapp:defs.bzl", "rule_with_host_dep")',
        'rule_with_host_dep(name = "a", host_deps = [":h"])',
        'filegroup(name = "h", srcs = ["h.src"])'
    ])
    cts = lib.analyze_build(self._bazel, ('//testapp:a',), ())
    # Remove boilerplate deps to focus on targets declared here.
    cts = [ct for ct in cts if ct.label.startswith('//testapp')]

    self.assertListEqual([ct.label for ct in cts],
                         ['//testapp:a', '//testapp:h', '//testapp:h.src'])
    # Don't use assertIsInstance because we don't want to match subclasses.
    self.assertEqual(Configuration, type(cts[0].config))
    self.assertEqual('HOST', cts[1].config_hash)
    self.assertIsInstance(cts[1].config, HostConfiguration)
    self.assertEqual('null', cts[2].config_hash)
    self.assertIsInstance(cts[2].config, NullConfiguration)

  def testAnalyzeBuildNoRepeats(self):
    self.ScratchFile('testapp/defs.bzl', [
        'def _impl(ctx):',
        '    pass',
        'rule_with_host_dep = rule(',
        '    implementation = _impl,',
        '    attrs = { "host_deps": attr.label_list(cfg = "exec") })',
    ])
    self.ScratchFile('testapp/BUILD', [
        'load("//testapp:defs.bzl", "rule_with_host_dep")',
        'rule_with_host_dep(name = "a", host_deps = [":h", ":other"])',
        'rule_with_host_dep(name = "other")',
        'filegroup(name = "h", srcs = ["h.src", ":other"])'
    ])
    cts = lib.analyze_build(self._bazel, ('//testapp:a',), ())
    # Remove boilerplate deps to focus on targets declared here.
    cts = [ct for ct in cts if ct.label.startswith('//testapp')]

    # Even though the build references //testapp:other twice, it only appears
    # once.
    self.assertListEqual(
        [ct.label for ct in cts],
        ['//testapp:a', '//testapp:h', '//testapp:other', '//testapp:h.src'])


if __name__ == '__main__':
  unittest.main()
