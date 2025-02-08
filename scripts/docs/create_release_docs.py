# Lint as: python3
# pylint: disable=g-direct-third-party-import
# Copyright 2022 The Bazel Authors. All rights reserved.
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
"""A tool for building the documentation for a Bazel release."""
import os
import shutil
import sys
import tarfile
import tempfile
import zipfile

from absl import app
from absl import flags

from scripts.docs import rewriter

FLAGS = flags.FLAGS

flags.DEFINE_string("version", None, "Name of the Bazel release.")
flags.DEFINE_string(
    "toc_path",
    None,
    "Path to the _toc.yaml file that contains the table of contents for the versions menu.",
)
flags.DEFINE_string(
    "buttons_path",
    None,
    "Path to the _buttons.html file that contains the version indicator.",
)
flags.DEFINE_string(
    "narrative_docs_path",
    None,
    "Path of the archive (zip or tar) that contains the narrative documentation.",
)
flags.DEFINE_string(
    "reference_docs_path",
    None,
    "Path of the archive (zip or tar) that contains the reference documentation.",
)
flags.DEFINE_string(
    "output_path", None,
    "Location where the zip'ed documentation should be written to.")

_ARCHIVE_FUNCTIONS = {".tar": tarfile.open, ".zip": zipfile.ZipFile}


def validate_flag(name):
  """Ensures that a flag is set, and returns its value (if yes).

  This function exits with an error if the flag was not set.

  Args:
    name: Name of the flag.

  Returns:
    The value of the flag, if set.
  """
  value = getattr(FLAGS, name, None)
  if value:
    return value

  print("Missing --{} flag.".format(name), file=sys.stderr)
  exit(1)


def create_docs_tree(
    version, toc_path, buttons_path, narrative_docs_path, reference_docs_path
):
  """Creates a directory tree containing the docs for the Bazel version.

  Args:
    version: Version of this Bazel release.
    toc_path: Absolute path to the _toc.yaml file that lists the most recent
      Bazel versions.
    buttons_path: Absolute path of the _buttons.html file that contains the
      version indicator.
    narrative_docs_path: Absolute path of an archive that contains the narrative
      documentation (can be .zip or .tar).
    reference_docs_path: Absolute path of an archive that contains the reference
      documentation (can be .zip or .tar).

  Returns:
    The absolute paths of the root of the directory tree and of
      the final _toc.yaml file.
  """
  root_dir = tempfile.mkdtemp()

  versions_dir = os.path.join(root_dir, "versions")
  os.makedirs(versions_dir)

  toc_dest_path = os.path.join(versions_dir, "_toc.yaml")
  shutil.copyfile(toc_path, toc_dest_path)

  release_dir = os.path.join(versions_dir, version)
  os.makedirs(release_dir)

  try_extract(narrative_docs_path, release_dir)
  try_extract(reference_docs_path, release_dir)

  buttons_dest_path = os.path.join(root_dir, "_buttons.html")
  shutil.copyfile(buttons_path, buttons_dest_path)

  return root_dir, toc_dest_path, release_dir


def try_extract(archive_path, output_dir):
  """Tries to extract the given archive into the given directory.

  This function will raise an error if the archive type is not supported.

  Args:
    archive_path: Absolute path of an archive that should be extracted. Can be
      .tar or .zip.
    output_dir: Absolute path of the directory into which the archive should be
      extracted

  Raises:
    ValueError: If the archive has an unsupported file type.
  """
  _, ext = os.path.splitext(archive_path)
  open_func = _ARCHIVE_FUNCTIONS.get(ext)
  if not open_func:
    raise ValueError("File {}: Invalid file extension '{}'. Allowed: {}".format(
        archive_path, ext, _ARCHIVE_FUNCTIONS.keys.join(", ")))

  with open_func(archive_path, "r") as archive:
    archive.extractall(output_dir)


def build_archive(version, root_dir, toc_path, output_path, release_dir):
  """Builds a documentation archive for the given Bazel release.

  This function reads all documentation files from the tree rooted in root_dir,
  fixes all links so that they point at versioned files, then builds a zip
  archive of all files.

  Args:
    version: Version of the Bazel release whose documentation is being built.
    root_dir: Absolute path of the directory that contains the documentation
      tree.
    toc_path: Absolute path of the _toc.yaml file.
    output_path: Absolute path where the archive should be written to.
    release_dir: Absolute path of the root directory for this version.
  """
  with zipfile.ZipFile(output_path, "w") as archive:
    for root, _, files in os.walk(root_dir):
      for f in files:
        src = os.path.join(root, f)
        dest = src[len(root_dir) + 1:]
        rel_path = os.path.relpath(src, release_dir)

        if src != toc_path and rewriter.can_rewrite(src):
          archive.writestr(dest, get_versioned_content(src, rel_path, version))
        else:
          archive.write(src, dest)


def get_versioned_content(path, rel_path, version):
  """Rewrites links in the given file to point at versioned docs.

  Args:
    path: Absolute path of the file that should be rewritten.
    rel_path: Relative path of the file that should be rewritten.
    version: Version of the Bazel release whose documentation is being built.

  Returns:
    The content of the given file, with rewritten links.
  """
  with open(path, "rt", encoding="utf-8") as f:
    content = f.read()

  return rewriter.rewrite_links(path, content, rel_path, version)


def main(unused_argv):
  version = validate_flag("version")
  output_path = validate_flag("output_path")
  root_dir, toc_path, release_dir = create_docs_tree(
      version=version,
      toc_path=validate_flag("toc_path"),
      buttons_path=validate_flag("buttons_path"),
      narrative_docs_path=validate_flag("narrative_docs_path"),
      reference_docs_path=validate_flag("reference_docs_path"),
  )

  build_archive(
      version=version,
      root_dir=root_dir,
      toc_path=toc_path,
      output_path=output_path,
      release_dir=release_dir,
  )


if __name__ == "__main__":
  FLAGS(sys.argv)
  app.run(main)
