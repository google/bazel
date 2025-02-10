# Copyright 2017 The Bazel Authors. All rights reserved.
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
"""Tests for TempJunction."""

import os
import unittest

from src.test.py.bazel import test_base
from tools.android import junction


class JunctionTest(test_base.TestBase):
  """Unit tests for junction.py."""

  def _AssertCreateJunctionWhenTargetsParentsDontExist(self, max_path=None):

    def tempdir():
      return self.ScratchDir("junc temp")

    target = self.Path("this directory/should not\\yet exist")
    self.assertFalse(os.path.exists(os.path.dirname(os.path.dirname(target))))
    # Make the `target` path a non-normalized Windows path with a space in it
    # which doesn't even exist.
    # TempJunction should still work; it should:
    # - normalize the path, and
    # - create all directories on the path
    # target = os.path.dirname(target) + "/junc target"
    juncpath = None
    with junction.TempJunction(
        target, testonly_mkdtemp=tempdir, testonly_maxpath=max_path) as j:
      juncpath = j
      # Ensure that `j` created the junction.
      self.assertTrue(os.path.exists(target))
      self.assertTrue(os.path.exists(juncpath))
      self.assertTrue(
          juncpath.endswith(os.path.join("junc temp", "j")))
      self.assertTrue(os.path.isabs(juncpath))
      # Create a file under the junction.
      filepath = os.path.join(juncpath, "some file.txt")
      with open(filepath, "w") as f:
        f.write("hello")
      # Ensure we can reach the file via the junction and the target directory.
      self.assertTrue(os.path.exists(os.path.join(target, "some file.txt")))
      self.assertTrue(os.path.exists(os.path.join(juncpath, "some file.txt")))
    # Ensure that after the `with` block the junction and temp directories no
    # longer exist, but we can still reach the file via the target directory.
    self.assertTrue(os.path.exists(os.path.join(target, "some file.txt")))
    self.assertFalse(os.path.exists(os.path.join(juncpath, "some file.txt")))
    self.assertFalse(os.path.exists(juncpath))
    self.assertFalse(os.path.exists(os.path.dirname(juncpath)))

  def testCreateJunctionWhenTargetsParentsDontExistAndPathIsShort(self):
    self._AssertCreateJunctionWhenTargetsParentsDontExist()

  def testCreateJunctionWhenTargetsParentsDontExistAndPathIsLong(self):
    self._AssertCreateJunctionWhenTargetsParentsDontExist(1)

  def testCannotCreateJunction(self):

    def tempdir():
      return self.ScratchDir("junc temp")

    target = self.ScratchDir("junc target")
    # Make the `target` path a non-normalized Windows path with a space in it.
    # TempJunction should still work.
    target = os.path.dirname(target) + "/junc target"
    with junction.TempJunction(target, testonly_mkdtemp=tempdir) as j:
      self.assertTrue(os.path.exists(j))
      if os.name != "nt":
        # Ensure that TempJunction raises a JunctionCreationError if it cannot
        # create a junction. In this case the junction already exists in that
        # directory.
        # On Windows, we error out in a different way, so we skip the test and
        # rely on this particular feature being correct on the other platforms
        # as sufficient.
        self.assertRaises(
            junction.JunctionCreationError,
            junction.TempJunction,
            target,
            testonly_mkdtemp=tempdir)


if __name__ == "__main__":
  unittest.main()
