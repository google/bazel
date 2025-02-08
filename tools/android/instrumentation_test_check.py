# pylint: disable=g-direct-third-party-import
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
"""AndroidManifest checks for android_instrumentation_test.

Ensures that the targetPackage of the instrumentation APK references
the correct target package name.
"""

import os
import sys
import xml.etree.ElementTree as ET

from absl import app
from absl import flags

flags.DEFINE_string("instrumentation_manifest", None,
                    "AndroidManifest.xml of the instrumentation APK")
flags.DEFINE_string("target_manifest", None,
                    "AndroidManifest.xml of the target APK")
flags.DEFINE_string("output", None, "Output of the check")

FLAGS = flags.FLAGS


class ManifestError(Exception):
  """Raised when there is a problem with an AndroidManifest.xml."""


# There might be more than one <instrumentation> tag to use different
# test runners, so we need to extract the targetPackage attribute values
# from all of them and check that they are the same.
def _ExtractTargetPackageToInstrument(xml_content, path):
  """Extract the targetPackage value from the <instrumentation> tag."""

  # https://developer.android.com/guide/topics/manifest/manifest-element.html
  # xmlns:android is the required namespace in an Android manifest.
  tree = ET.ElementTree(ET.fromstring(xml_content))
  package_key = "{http://schemas.android.com/apk/res/android}targetPackage"
  instrumentation_elems = tree.iterfind(
      ".//instrumentation[@{0}]".format(package_key))

  package_names = set(e.attrib[package_key] for e in instrumentation_elems)

  if not package_names:
    raise ManifestError("No <instrumentation> tag containing "
                        "the targetPackage attribute is found in the "
                        "manifest at %s" % path)

  if len(package_names) > 1:
    raise ManifestError(
        "The <instrumentation> tags in the manifest at %s do not "
        "reference the same target package: %s" % (path, list(package_names)))

  return package_names.pop()


def _ExtractTargetPackageName(xml_content, path):
  """Extract the package name value from the root <manifest> tag."""
  tree = ET.ElementTree(ET.fromstring(xml_content))
  root = tree.getroot()
  if "package" in root.attrib:
    return root.attrib["package"]
  else:
    raise ManifestError("The <manifest> tag in the manifest at %s needs to "
                        "specify the package name using the 'package' "
                        "attribute." % path)


def _ValidateManifestPackageNames(instr_manifest_content, instr_manifest_path,
                                  target_manifest_content,
                                  target_manifest_path):
  """Diff the package names and throw a ManifestError if not identical."""
  target_package_to_instrument = _ExtractTargetPackageToInstrument(
      instr_manifest_content, instr_manifest_path)
  target_package_name = _ExtractTargetPackageName(target_manifest_content,
                                                  target_manifest_path)

  if target_package_to_instrument != target_package_name:
    raise ManifestError(
        "The targetPackage specified in the instrumentation manifest at "
        "{instr_manifest_path} ({target_package_to_instrument}) does not match "
        "the package name of the target manifest at {target_manifest_path} "
        "({target_package_name})".format(
            instr_manifest_path=instr_manifest_path,
            target_package_to_instrument=target_package_to_instrument,
            target_manifest_path=target_manifest_path,
            target_package_name=target_package_name))

  return target_package_to_instrument, target_package_name


def main(unused_argv):
  instr_manifest_path = FLAGS.instrumentation_manifest
  target_manifest_path = FLAGS.target_manifest
  output_path = FLAGS.output
  dirname = os.path.dirname(output_path)
  if not os.path.exists(dirname):
    os.makedirs(dirname)

  with open(instr_manifest_path, "rb") as f:
    instr_manifest = f.read()

  with open(target_manifest_path, "rb") as f:
    target_manifest = f.read()

  try:
    package_to_instrument, package_name = _ValidateManifestPackageNames(
        instr_manifest, instr_manifest_path, target_manifest,
        target_manifest_path)
  except ManifestError as e:
    sys.exit(str(e))

  with open(output_path, "w") as f:
    f.write("target_package={0}\n".format(package_to_instrument))
    f.write("package_name={0}\n".format(package_name))


if __name__ == "__main__":
  app.run(main)
