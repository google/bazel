#!/bin/bash
# Copyright 2018 The Bazel Authors. All rights reserved.
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

# Tests that we don't accidentally add more dependencies.
# The test uses jdeps from the embedded JDK to compute the module dependencies
# of all class files in A-server.jar.
#
# If the test fails:
# - with a removed dependency: just remove it from the jdeps_modules.golden file
# - with an additional dependency: think twice whether to add it to
#   jdeps_modules.golden since every additional dependency will contribute to
#   Bazel's binary size
# - with an NPE: jdeps crashes on a few class files, add new crashing class
#   files to jdeps_class_denylist.txt

# --- begin runfiles.bash initialization ---
# Copy-pasted from Bazel's Bash runfiles library (tools/bash/runfiles/runfiles.bash).
set -euo pipefail
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function test_jdeps() {
  mkdir jdk bazeljar
  cd jdk
  if is_darwin; then
    platform="macos"
  else
    platform="linux"
  fi
  cp $(rlocation io_bazel/src/allmodules_jdk.zip) .
  unzip allmodules_jdk.zip || fail "Failed to extract JDK."
  denylist=$(rlocation io_bazel/src/test/shell/bazel/jdeps_class_denylist.txt)
  deploy_jar=$(rlocation io_bazel/src/main/java/com/google/devtools/build/lib/bazel/BazelServer_deploy.jar)
  cd ../bazeljar
  unzip "$deploy_jar" || fail "Failed to extract Bazel's server.jar"

  # TODO(twerth): Replace --list-reduced-deps with --print-module-deps when
  # switching to JDK10.
  # If jdeps fails with a NPE, just add the class file to the list in
  # src/test/shell/bazel/jdeps_class_denylist.txt.
  find . -type f -iname \*class | \
    grep -vFf "$denylist" | \
    xargs -n1000 ../jdk/reduced/bin/jdeps --list-reduced-deps --ignore-missing-deps | \
    grep -v "unnamed module" > ../jdeps \
    || fail "Failed to run jdeps on non denylisted class files."
  cd ..

  # Keep java.instrument for allocation_instrumenter, which is supplied by the user.
  echo "java.instrument" >> jdeps

  # Make the list sorted and unique and compare it with expected results.
  cat jdeps | \
    sed -e 's|[[:space:]]*||g' -e 's|/.*||' | \
    grep -v "notfound" | \
    sort -u > jdeps-sorted
  expected=$(rlocation io_bazel/src/jdeps_modules.golden)
  diff -u "$expected" jdeps-sorted || fail "List of modules has changed."
}

run_suite "Tests included JDK modules."
