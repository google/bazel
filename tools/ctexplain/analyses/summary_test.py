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
"""Tests for summary.py."""
import unittest

from frozendict import frozendict

import tools.ctexplain.analyses.summary as summary
from tools.ctexplain.types import Configuration
from tools.ctexplain.types import ConfiguredTarget
from tools.ctexplain.types import NullConfiguration


class SummaryTest(unittest.TestCase):

  def testAnalysis(self):
    config1 = Configuration(None, frozendict({'a': frozendict({'b': 'c'})}))
    config2 = Configuration(None, frozendict({'d': frozendict({'e': 'f'})}))

    ct1 = ConfiguredTarget('//foo', config1, 'hash1', None)
    ct2 = ConfiguredTarget('//foo', config2, 'hash2', None)
    ct3 = ConfiguredTarget('//foo', NullConfiguration(), 'null', None)
    ct4 = ConfiguredTarget('//bar', config1, 'hash1', None)

    res = summary.analyze((ct1, ct2, ct3, ct4))
    self.assertEqual(3, res.configurations)
    self.assertEqual(2, res.targets)
    self.assertEqual(4, res.configured_targets)
    self.assertEqual(1, res.repeated_targets)


if __name__ == '__main__':
  unittest.main()
