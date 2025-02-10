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
"""Tests for types.py."""
import unittest
from frozendict import frozendict
from tools.ctexplain.types import Configuration


class TypesTest(unittest.TestCase):

  def testConfigurationIsHashable(self):
    options = frozendict({'o1': frozendict({'k1': 'v1'})})
    c = Configuration(fragments=('F1'), options=options)
    some_dict = {}
    some_dict[c] = 4

  def testConfigurationHashAccuracy(self):
    d = {}

    options1 = frozendict({'o1': frozendict({'k1': 'v1'})})
    d[Configuration(fragments=('F1'), options=options1)] = 4
    self.assertEqual(len(d), 1)

    options2 = frozendict({'o1': frozendict({'k1': 'v1'})})
    d[Configuration(fragments=('F1'), options=options2)] = 4
    self.assertEqual(len(d), 1)

    options3 = frozendict({'o1': frozendict({'k1': 'v1'})})
    d[Configuration(fragments=('F2'), options=options3)] = 4
    self.assertEqual(len(d), 2)

    options4 = frozendict({'o2': frozendict({'k1': 'v1'})})
    d[Configuration(fragments=('F2'), options=options4)] = 4
    self.assertEqual(len(d), 3)

    options5 = frozendict({'o2': frozendict({'k2': 'v1'})})
    d[Configuration(fragments=('F2'), options=options5)] = 4
    self.assertEqual(len(d), 4)

    options6 = frozendict({'o2': frozendict({'k2': 'v2'})})
    d[Configuration(fragments=('F2'), options=options6)] = 4
    self.assertEqual(len(d), 5)

  def testConfigurationEquality(self):
    c1 = Configuration(fragments=('F1'), options={'o1': {'k1': 'v1'}})
    c2 = Configuration(fragments=('F1'), options={'o1': {'k1': 'v1'}})
    c3 = Configuration(fragments=('F2'), options={'o1': {'k1': 'v1'}})
    c4 = Configuration(fragments=('F1'), options={'o2': {'k2': 'v2'}})

    self.assertEqual(c1, c2)
    self.assertNotEqual(c1, c3)
    self.assertNotEqual(c1, c4)
    self.assertNotEqual(c3, c4)


if __name__ == '__main__':
  unittest.main()
