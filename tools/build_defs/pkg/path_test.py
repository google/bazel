# Copyright 2016 The Bazel Authors. All rights reserved.
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
"""Testing for helper functions."""

import importlib.machinery
import importlib.util
import unittest


loader = importlib.machinery.SourceFileLoader(
    'pkg_bzl', 'tools/build_defs/pkg/path.bzl')
spec = importlib.util.spec_from_loader(loader.name, loader)
pkg_bzl = importlib.util.module_from_spec(spec)
loader.exec_module(pkg_bzl)


class File(object):
  """Mock Starlark File class for testing."""

  def __init__(self, short_path):
    self.short_path = short_path


class ShortPathDirnameTest(unittest.TestCase):
  """Testing for _short_path_dirname."""

  def testShortPathDirname(self):
    path = pkg_bzl._short_path_dirname(File('foo/bar/baz'))
    self.assertEqual('foo/bar', path)

  def testTopLevel(self):
    path = pkg_bzl._short_path_dirname(File('baz'))
    self.assertEqual('', path)


class DestPathTest(unittest.TestCase):
  """Testing for _dest_path."""

  def testDestPath(self):
    path = pkg_bzl.dest_path(File('foo/bar/baz'), 'foo')
    self.assertEqual('/bar/baz', path)

  def testNoMatch(self):
    path = pkg_bzl.dest_path(File('foo/bar/baz'), 'qux')
    self.assertEqual('foo/bar/baz', path)

  def testNoStrip(self):
    path = pkg_bzl.dest_path(File('foo/bar/baz'), None)
    self.assertEqual('/baz', path)

  def testTopLevel(self):
    path = pkg_bzl.dest_path(File('baz'), None)
    self.assertEqual('baz', path)


class ComputeDataPathTest(unittest.TestCase):
  """Testing for _data_path_out."""

  def testComputeDataPath(self):
    path = pkg_bzl.compute_data_path(File('foo/bar/baz.tar'), 'a/b/c')
    self.assertEqual('foo/bar/a/b/c', path)

  def testAbsolute(self):
    path = pkg_bzl.compute_data_path(File('foo/bar/baz.tar'), '/a/b/c')
    self.assertEqual('a/b/c', path)

  def testRelative(self):
    path = pkg_bzl.compute_data_path(File('foo/bar/baz.tar'), './a/b/c')
    self.assertEqual('foo/bar/a/b/c', path)

  def testEmpty(self):
    path = pkg_bzl.compute_data_path(File('foo/bar/baz.tar'), './')
    self.assertEqual('foo/bar', path)
    path = pkg_bzl.compute_data_path(File('foo/bar/baz.tar'), './.')
    self.assertEqual('foo/bar', path)


if __name__ == '__main__':
  unittest.main()
