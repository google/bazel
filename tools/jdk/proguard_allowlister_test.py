# Copyright 2015 The Bazel Authors. All rights reserved.
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

import os
import unittest

from tools.jdk import proguard_allowlister


class ProguardConfigValidatorTest(unittest.TestCase):

  def _CreateValidator(self, input_path, output_path):
    return proguard_allowlister.ProguardConfigValidator(input_path, output_path)

  def testValidConfig(self):
    input_path = os.path.join(
        os.path.dirname(__file__), "proguard_allowlister_test_input.pgcfg")
    tmpdir = os.environ["TEST_TMPDIR"]
    output_path = os.path.join(tmpdir, "proguard_allowlister_test_output.pgcfg")
    # This will raise an exception if the config is invalid.
    self._CreateValidator(input_path, output_path).ValidateAndWriteOutput()
    with open(output_path) as output:
      self.assertIn("# Merged from %s" % input_path, output.read())

  def _TestInvalidConfig(self, invalid_args, config):
    tmpdir = os.environ["TEST_TMPDIR"]
    input_path = os.path.join(tmpdir, "proguard_allowlister_test_input.pgcfg")
    with open(input_path, "w", encoding="utf-8") as f:
      f.write(config)
    output_path = os.path.join(tmpdir, "proguard_allowlister_test_output.pgcfg")
    validator = self._CreateValidator(input_path, output_path)
    with self.assertRaises(RuntimeError) as error:
      validator.ValidateAndWriteOutput()
    message = str(error.exception)
    for invalid_arg in invalid_args:
      self.assertIn(invalid_arg, message)

  def testInvalidNoteConfig(self):
    self._TestInvalidConfig(["-dontnote"], """\
# We don"t want libraries disabling notes globally.
-dontnote""")

  def testInvalidWarnConfig(self):
    self._TestInvalidConfig(["-dontwarn"], """\
# We don"t want libraries disabling warnings globally.
-dontwarn""")

  def testInvalidOptimizationConfig(self):
    self._TestInvalidConfig(["-optimizations"], """\
# We don"t want libraries disabling global optimizations.
-optimizations !class/merging/*,!code/allocation/variable""")

  def testMultipleInvalidArgs(self):
    self._TestInvalidConfig(["-optimizations", "-dontnote"], """\
# We don"t want libraries disabling global optimizations.
-optimizations !class/merging/*,!code/allocation/variable
-dontnote""")


if __name__ == "__main__":
  unittest.main()
