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
"""Tests using credentials to connect to the bzlmod registry."""

import base64
import os
import tempfile
from absl.testing import absltest
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry
from src.test.py.bazel.bzlmod.test_utils import StaticHTTPServer


class BzlmodCredentialsTest(test_base.TestBase):
  """Test class for using credentials to connect to the bzlmod registry."""

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.registries_work_dir = tempfile.mkdtemp(dir=self._test_cwd)
    self.registry_root = os.path.join(self.registries_work_dir, 'main')
    self.main_registry = BazelRegistry(self.registry_root)
    self.main_registry.createCcModule('aaa', '1.0')

    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            # Disable yanked version check so we are not affected BCR changes.
            'common --allow_yanked_versions=all',
        ],
    )
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
    self.ScratchFile(
        'main.cc',
        [
            '#include "aaa.h"',
            'int main() {',
            '    hello_aaa("main function");',
            '}',
        ],
    )
    self.ScratchFile(
        'credhelper',
        [
            '#!/usr/bin/env python3',
            'import sys',
            'if "127.0.0.1" in sys.stdin.read():',
            '    print("""{"headers":{"Authorization":["Bearer TOKEN"]}}""")',
            'else:',
            '    print("""{}""")',
        ],
        executable=True,
    )
    self.ScratchFile(
        '.netrc',
        [
            'machine 127.0.0.1',
            'login foo',
            'password bar',
        ],
    )

  def testUnauthenticated(self):
    with StaticHTTPServer(self.registry_root) as static_server:
      _, stdout, _ = self.RunBazel([
          'run',
          '--registry=' + static_server.getURL(),
          '--registry=https://bcr.bazel.build',
          '//:main',
      ])
      self.assertIn('main function => aaa@1.0', stdout)

  def testMissingCredentials(self):
    with StaticHTTPServer(
        self.registry_root, expected_auth='Bearer TOKEN'
    ) as static_server:
      _, _, stderr = self.RunBazel(
          [
              'run',
              '--registry=' + static_server.getURL(),
              '--registry=https://bcr.bazel.build',
              '//:main',
          ],
          allow_failure=True,
      )
      self.assertIn('GET returned 401 Unauthorized', '\n'.join(stderr))

  def testCredentialsFromHelper(self):
    with StaticHTTPServer(
        self.registry_root, expected_auth='Bearer TOKEN'
    ) as static_server:
      _, stdout, _ = self.RunBazel([
          'run',
          '--credential_helper=%workspace%/credhelper',
          '--registry=' + static_server.getURL(),
          '--registry=https://bcr.bazel.build',
          '//:main',
      ])
      self.assertIn('main function => aaa@1.0', stdout)

  def testCredentialsFromNetrc(self):
    expected_auth = 'Basic ' + base64.b64encode(b'foo:bar').decode('ascii')

    with StaticHTTPServer(
        self.registry_root, expected_auth=expected_auth
    ) as static_server:
      _, stdout, _ = self.RunBazel(
          [
              'run',
              '--registry=' + static_server.getURL(),
              '--registry=https://bcr.bazel.build',
              '//:main',
          ],
          env_add={'NETRC': self.Path('.netrc')},
      )
      self.assertIn('main function => aaa@1.0', stdout)

  def testCredentialsFromHelperOverrideNetrc(self):
    with StaticHTTPServer(
        self.registry_root, expected_auth='Bearer TOKEN'
    ) as static_server:
      _, stdout, _ = self.RunBazel(
          [
              'run',
              '--credential_helper=%workspace%/credhelper',
              '--registry=' + static_server.getURL(),
              '--registry=https://bcr.bazel.build',
              '//:main',
          ],
          env_add={'NETRC': self.Path('.netrc')},
      )
      self.assertIn('main function => aaa@1.0', stdout)


if __name__ == '__main__':
  absltest.main()
