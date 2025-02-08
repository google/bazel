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
"""This tool build tar files from a list of inputs."""

import argparse
import os
import sys

from tools.build_defs.pkg import archive


class TarFile(object):
  """A class to generates a TAR file."""

  class DebError(Exception):
    pass

  def __init__(self, output, directory, root_directory):
    self.directory = directory
    self.output = output
    self.root_directory = root_directory

  def __enter__(self):
    self.tarfile = archive.TarFileWriter(self.output, self.root_directory)
    return self

  def __exit__(self, t, v, traceback):
    self.tarfile.close()

  def add_file(self, f, destfile):
    """Add a file to the tar file.

    Args:
       f: the file to add to the layer
       destfile: the name of the file in the layer
    """
    dest = destfile.lstrip('/')  # Remove leading slashes
    if self.directory and self.directory != '/':
      dest = self.directory.lstrip('/') + '/' + dest
    mode = 0o755 if os.access(f, os.X_OK) else 0o644
    dest = os.path.normpath(dest)
    self.tarfile.add_file(dest, file_content=f, mode=mode)


def unquote_and_split(arg, c):
  """Split a string at the first unquoted occurrence of a character.

  Split the string arg at the first unquoted occurrence of the character c.
  Here, in the first part of arg, the backslash is considered the
  quoting character indicating that the next character is to be
  added literally to the first part, even if it is the split character.

  Args:
    arg: the string to be split
    c: the character at which to split

  Returns:
    The unquoted string before the separator and the string after the
    separator.
  """
  head = ''
  i = 0
  while i < len(arg):
    if arg[i] == c:
      return (head, arg[i + 1:])
    elif arg[i] == '\\':
      i += 1
      if i == len(arg):
        # dangling quotation symbol
        return (head, '')
      else:
        head += arg[i]
    else:
      head += arg[i]
    i += 1
  # if we leave the loop, the character c was not found unquoted
  return (head, '')


def main():
  parser = argparse.ArgumentParser(
      description='Helper for building tar packages',
      fromfile_prefix_chars='@')
  parser.add_argument(
      '--output',
      required=True,
      help='The output file, mandatory.')
  parser.add_argument(
      '--file',
      action='append',
      help='A file to add to the layer')
  parser.add_argument(
      '--directory',
      help='Directory in which to store the file inside the layer')
  parser.add_argument(
      '--root_directory',
      default='./',
      help='Default root directory is named "."')
  opts = parser.parse_args()

  # Add objects to the tar file
  with TarFile(opts.output, opts.directory, opts.root_directory) as output:
    for f in opts.file:
      (inf, tof) = unquote_and_split(f, '=')
      output.add_file(inf, tof)


if __name__ == '__main__':
  print(sys.argv)
  main()
