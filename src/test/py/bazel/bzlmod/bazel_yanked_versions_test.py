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


class BazelYankedVersionsTest(test_base.TestBase):

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
    ).createCcModule(
        'ddd', '1.0', {'yanked1': '1.0', 'yanked2': '1.0'}
    ).createCcModule(
        'eee', '1.0', {'yanked1': '1.0'}
    ).createCcModule(
        'fff', '1.0'
    ).createCcModule(
        'yanked1', '1.0'
    ).createCcModule(
        'yanked2', '1.0'
    ).addMetadata(
        'yanked1', yanked_versions={'1.0': 'dodgy'}
    ).addMetadata(
        'yanked2', yanked_versions={'1.0': 'sketchy'}
    )
    self.writeBazelrcFile()

  def tearDown(self):
    self.main_registry.stop()
    test_base.TestBase.tearDown(self)

  def writeBazelrcFile(self, allow_yanked_versions=True):
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
        ]
        + (
            [
                # Disable yanked version check so we are not affected BCR
                # changes.
                'build --allow_yanked_versions=all',
            ]
            if allow_yanked_versions
            else []
        ),
    )

  def testNonRegistryOverriddenModulesIgnoreYanked(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    src_yanked1 = self.main_registry.projects.joinpath('yanked1', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "yanked1", version = "1.0")',
            'local_path_override(',
            '  module_name = "yanked1",',
            '  path = "%s",' % str(src_yanked1.resolve()).replace('\\', '/'),
            ')',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@yanked1//:lib_yanked1"],',
            ')',
        ],
    )
    self.RunBazel(['build', '--nobuild', '//:main'])

  def testContainingYankedDepFails(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "yanked1", version = "1.0")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@ddd//:lib_ddd"],',
            ')',
        ],
    )
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '//:main'], allow_failure=True
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: '
        + 'yanked1@1.0, for the reason: dodgy.',
        ''.join(stderr),
    )

  def testAllowedYankedDepsSuccessByFlag(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "ddd", version = "1.0")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@ddd//:lib_ddd"],',
            ')',
        ],
    )
    self.RunBazel(
        [
            'build',
            '--nobuild',
            '--allow_yanked_versions=yanked1@1.0,yanked2@1.0',
            '//:main',
        ],
    )

  def testAllowedYankedDepsByEnvVar(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "ddd", version = "1.0")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@ddd//:lib_ddd"],',
            ')',
        ],
    )
    self.RunBazel(
        ['build', '--nobuild', '//:main'],
        env_add={'BZLMOD_ALLOW_YANKED_VERSIONS': 'yanked1@1.0,yanked2@1.0'},
    )

    # Test changing the env var, the build should fail again.
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '//:main'],
        env_add={'BZLMOD_ALLOW_YANKED_VERSIONS': 'yanked2@1.0'},
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: '
        + 'yanked1@1.0, for the reason: dodgy.',
        ''.join(stderr),
    )

  def testAllowedYankedDepsByEnvVarErrorMode(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "ddd", version = "1.0")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@ddd//:lib_ddd"],',
            ')',
        ],
    )
    self.RunBazel(
        ['build', '--nobuild', '//:main'],
        env_add={'BZLMOD_ALLOW_YANKED_VERSIONS': 'yanked1@1.0,yanked2@1.0'},
    )

    # Test changing the env var, the build should fail again.
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '--lockfile_mode=error', '//:main'],
        env_add={'BZLMOD_ALLOW_YANKED_VERSIONS': 'yanked2@1.0'},
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: '
        + 'yanked1@1.0, for the reason: dodgy.',
        ''.join(stderr),
    )

  def testAllowedYankedDepsSuccessMix(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "ddd", version = "1.0")',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["@ddd//:lib_ddd"],',
            ')',
        ],
    )
    self.RunBazel(
        [
            'build',
            '--nobuild',
            '--allow_yanked_versions=yanked1@1.0',
            '//:main',
        ],
        env_add={'BZLMOD_ALLOW_YANKED_VERSIONS': 'yanked2@1.0'},
    )

  def testYankedVersionsFetchedIncrementally(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
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
    self.RunBazel(['build', '--nobuild', '//:main'])

    # Yank aaa@1.0 and aaa@1.1.
    self.main_registry.addMetadata(
        'aaa', yanked_versions={'1.0': 'already dodgy', '1.1': 'still dodgy'}
    )

    # Without any changes, both a cold and a warm build still pass.
    self.RunBazel(['build', '--nobuild', '//:main'])
    self.RunBazel(['shutdown'])
    self.RunBazel(['build', '--nobuild', '//:main'])

    # Adding an unrelated dependency should not cause yanked versions to be
    # fetched again.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'bazel_dep(name = "fff", version = "1.0")',
        ],
    )
    self.RunBazel(['build', '--nobuild', '//:main'])
    self.RunBazel(['shutdown'])
    self.RunBazel(['build', '--nobuild', '//:main'])

    # If a new version of aaa is selected, yanked versions should be fetched
    # again.
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'bazel_dep(name = "fff", version = "1.0")',
            # Depends on aaa@1.1.
            'bazel_dep(name = "bbb", version = "1.1")',
        ],
    )
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '//:main'], allow_failure=True
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: '
        + 'aaa@1.1, for the reason: still dodgy.',
        ''.join(stderr),
    )

  def testYankedVersionsRefreshedOnModeSwitch(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
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

    # Verify that when switching to refresh mode, yanked version information is
    # always updated immediately, even if it was fetched previously.
    self.RunBazel(['build', '--nobuild', '--lockfile_mode=refresh', '//:main'])
    self.RunBazel(['build', '--nobuild', '//:main'])

    # Yank aaa@1.0.
    self.main_registry.addMetadata(
        'aaa', yanked_versions={'1.0': 'already dodgy'}
    )

    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '--lockfile_mode=refresh', '//:main'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: '
        + 'aaa@1.0, for the reason: already dodgy.',
        ''.join(stderr),
    )

  def testYankedVersionsRefreshedAfterShutdown(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
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

    self.RunBazel(['build', '--nobuild', '--lockfile_mode=refresh', '//:main'])

    # Yank aaa@1.0.
    self.main_registry.addMetadata(
        'aaa', yanked_versions={'1.0': 'already dodgy'}
    )
    self.RunBazel(['shutdown'])

    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '--lockfile_mode=refresh', '//:main'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: '
        + 'aaa@1.0, for the reason: already dodgy.',
        ''.join(stderr),
    )

  def testYankedVersionsRefreshedAfterAllowed(self):
    self.writeBazelrcFile(allow_yanked_versions=False)
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
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
    self.RunBazel(['build', '--nobuild', '//:main'])

    # Yank aaa@1.0.
    self.main_registry.addMetadata(
        'aaa', yanked_versions={'1.0': 'already dodgy'}
    )

    # Without any changes, even a warm build should fail.
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '--lockfile_mode=refresh', '//:main'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: '
        + 'aaa@1.0, for the reason: already dodgy.',
        ''.join(stderr),
    )

    # If the yanked version is allowed, the build should pass.
    self.RunBazel(
        ['build', '--nobuild', '--lockfile_mode=refresh', '//:main'],
        env_add={'BZLMOD_ALLOW_YANKED_VERSIONS': 'aaa@1.0'},
    )

    # Yank aaa@1.0 with a different message.
    self.main_registry.addMetadata(
        'aaa', yanked_versions={'1.0': 'even more dodgy'}
    )

    # After temporarily allowing a yanked version, the yanked info is
    # still refreshed.
    exit_code, _, stderr = self.RunBazel(
        ['build', '--nobuild', '--lockfile_mode=refresh', '//:main'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 48, stderr)
    self.assertIn(
        'Yanked version detected in your resolved dependency graph: '
        + 'aaa@1.0, for the reason: even more dodgy.',
        ''.join(stderr),
    )


if __name__ == '__main__':
  absltest.main()
