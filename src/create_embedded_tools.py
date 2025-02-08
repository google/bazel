# pylint: disable=g-direct-third-party-import
# pylint: disable=g-bad-file-header
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
"""Creates the embedded_tools.zip that is part of the Bazel binary."""

import contextlib
import fnmatch
import os
import os.path
import re
import sys
import zipfile

from src.create_embedded_tools_lib import copy_tar_to_zip
from src.create_embedded_tools_lib import copy_zip_to_zip
from src.create_embedded_tools_lib import is_executable

output_paths = [
    ('*MODULE.tools', lambda x: 'MODULE.bazel'),
    ('*tools/jdk/BUILD.tools', lambda x: 'tools/jdk/BUILD'),
    (
        '*tools/build_defs/repo/BUILD.repo',
        lambda x: 'tools/build_defs/repo/BUILD',
    ),
    (
        '*tools/build_defs/build_info/BUILD.tools',
        lambda x: 'tools/build_defs/build_info/BUILD',
    ),
    (
        '*tools/build_defs/build_info/templates/BUILD.tools',
        lambda x: 'tools/build_defs/build_info/templates/BUILD',
    ),
    ('*tools/j2objc/BUILD.tools', lambda x: 'tools/j2objc/BUILD'),
    ('*tools/platforms/*', lambda x: 'platforms/' + os.path.basename(x)),
    ('*tools/cpp/BUILD.tools', lambda x: 'tools/cpp/BUILD'),
    ('*launcher.exe', lambda x: 'tools/launcher/launcher.exe'),
    ('*launcher_maker.exe', lambda x: 'tools/launcher/launcher_maker.exe'),
    ('*def_parser.exe', lambda x: 'tools/def_parser/def_parser.exe'),
    ('*zipper.exe', lambda x: 'tools/zip/zipper/zipper.exe'),
    ('*zipper', lambda x: 'tools/zip/zipper/zipper'),
    ('*xcode*xcode-locator', lambda x: 'tools/objc/xcode-locator'),
    ('*src/tools/xcode/*', lambda x: 'tools/objc/' + os.path.basename(x)),
    # --experimental_sibling_repository_layout=false
    ('*external/openjdk_*/file/*.tar.gz', lambda x: 'jdk.tar.gz'),
    ('*external/openjdk_*/file/*.zip', lambda x: 'jdk.zip'),
    # --experimental_sibling_repository_layout=true
    ('*openjdk_*/file/*.tar.gz', lambda x: 'jdk.tar.gz'),
    ('*openjdk_*/file/*.zip', lambda x: 'jdk.zip'),
    ('*src/minimal_jdk.tar.gz', lambda x: 'jdk.tar.gz'),
    ('*src/minimal_jdk.zip', lambda x: 'jdk.zip'),
    ('*.bzl.tools', lambda x: x[:-6]),
    ('*', lambda x: re.sub(r'^.*bazel-out/[^/]*/bin/', '', x, count=1)),
]


def get_output_path(path):
  for pattern, transformer in output_paths:
    if fnmatch.fnmatch(path.replace('\\', '/'), pattern):
      # BUILD.tools are stored as BUILD files.
      return transformer(path).replace('/BUILD.tools', '/BUILD')


def get_input_files(argsfile):
  """Returns a dict of archive_file to input_file.

  This describes the files that should be put into the generated archive.

  Args:
    argsfile: The file containing the list of input files.

  Raises:
    ValueError: When two input files map to the same output file.
  """
  with open(argsfile, 'r') as f:
    input_files = sorted(set(x.strip() for x in f.readlines()))

    result = {}
    for input_file in input_files:
      # If we have both a BUILD and a BUILD.tools file, take the latter only.
      if (os.path.basename(input_file) == 'BUILD' and
          input_file + '.tools' in input_files):
        continue

      # It's an error to have two files map to the same output file, because the
      # result is hard to predict and can easily be wrong.
      output_path = get_output_path(input_file)
      if output_path in result:
        raise ValueError(
            'Duplicate output file: Both {} and {} map to {}'.format(
                result[output_path], input_file, output_path))
      result[output_path] = input_file

  return result


def copy_jdk_into_archive(output_zip, archive_file, input_file):
  """Extract the JDK and adds it to the archive under jdk/*."""

  def _replace_dirname(filename):
    # Rename the first folder to 'jdk', because Bazel looks for a
    # bundled JDK in the embedded tools using that folder name.
    return 'jdk/' + '/'.join(filename.split('/')[1:])

  # The JDK is special - it's extracted instead of copied.
  if archive_file.endswith('.tar.gz'):
    copy_tar_to_zip(output_zip, input_file, _replace_dirname)
  elif archive_file.endswith('.zip'):
    copy_zip_to_zip(output_zip, input_file, _replace_dirname)


def main():
  output_zip = os.path.join(os.getcwd(), sys.argv[1])
  input_files = get_input_files(sys.argv[2])

  # Copy all the input_files into output_zip.
  # Adding contextlib.closing to be python 2.6 (for centos 6.7) compatible
  with contextlib.closing(
      zipfile.ZipFile(output_zip, 'w', zipfile.ZIP_DEFLATED)) as output_zip:
    zipinfo = zipfile.ZipInfo('WORKSPACE', (1980, 1, 1, 0, 0, 0))
    zipinfo.external_attr = 0o644 << 16
    output_zip.writestr(zipinfo, 'workspace(name = "bazel_tools")\n')

    # By sorting the file list, the resulting ZIP file will be reproducible and
    # deterministic.
    for archive_file, input_file in sorted(input_files.items()):
      if os.path.basename(archive_file) in ('jdk.tar.gz', 'jdk.zip'):
        copy_jdk_into_archive(output_zip, archive_file, input_file)
      else:
        zipinfo = zipfile.ZipInfo(archive_file, (1980, 1, 1, 0, 0, 0))
        zipinfo.external_attr = 0o755 << 16 if is_executable(
            input_file) else 0o644 << 16
        zipinfo.compress_type = zipfile.ZIP_DEFLATED
        with open(input_file, 'rb') as f:
          output_zip.writestr(zipinfo, f.read())


if __name__ == '__main__':
  main()
