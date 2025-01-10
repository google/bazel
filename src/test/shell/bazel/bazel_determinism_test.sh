#!/bin/bash
#
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

#
# Test that bootstrapping bazel is a fixed point
#

# --- begin runfiles.bash initialization v3 ---
# Copy-pasted from the Bazel Bash runfiles library v3.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v3 ---

set -u
DISTFILE=$(rlocation io_bazel/${1#./})
shift 1

# Load the test setup defined in the parent directory
source $(rlocation io_bazel/src/test/shell/integration_test_setup.sh) \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

# sha1sum is easily twice as fast as shasum, but not always available on macOS.
if hash sha1sum 2>/dev/null; then
  shasum="sha1sum"
elif hash shasum 2>/dev/null; then
  shasum="shasum"
else
  fail "Could not find sha1sum or shasum on the PATH"
fi

function hash_outputs() {
  # runfiles/MANIFEST & runfiles_manifest contain absolute path, ignore.
  # ar on OS-X is non-deterministic, ignore .a files.
  find bazel-bin/ \
      -type f \
      -a \! -name MANIFEST \
      -a \! -name '*.runfiles_manifest' \
      -a \! -name '*.a' \
      -exec $shasum {} + \
      | awk '{print $2, $1}' \
      | sort \
      | sed "s://:/:"
}

function test_determinism()  {
    # Verify that Bazel can build itself under a path with spaces and non-ASCII
    # characters.
    local workdir="${TEST_TMPDIR}/wo🌱rk dir"
    mkdir "${workdir}" || fail "Could not create work directory"
    cd "${workdir}" || fail "Could not change to work directory"
    unzip -q "${DISTFILE}"

    # Set up the maven repository properly.
    cp derived/maven/BUILD.vendor derived/maven/BUILD

    # Build Bazel once.
    # Use an output base with spaces and non-ASCII characters to verify that
    # Bazel supports this. Characters with a 4-byte UTF-8 encoding would cause
    # Java compilation to fail due to
    # https://bugs.openjdk.org/browse/JDK-8258246.
    output_base_1="${TEST_TMPDIR}/ouäöüt 1"
    bazel \
      --output_base="${output_base_1}" \
      build \
      --extra_toolchains=@rules_python//python:autodetecting_toolchain \
      --enable_bzlmod \
      --check_direct_dependencies=error \
      --lockfile_mode=update \
      --override_repository=$(cat derived/maven/MAVEN_CANONICAL_REPO_NAME)=derived/maven \
      --nostamp \
      --verbose_failures \
      //src:bazel &> $TEST_log || fail "First build failed"
    assert_exists "${output_base_1}/java.log"
    expect_not_log ERROR
    expect_not_log "Exception:"
    expect_not_log "Error:"
    hash_outputs >"${TEST_TMPDIR}/sum1"

    # Build Bazel twice.
    output_base_2="${TEST_TMPDIR}/ouäöüt 2"
    bazel-bin/src/bazel \
      --bazelrc="${TEST_TMPDIR}/bazelrc" \
      --install_base="${TEST_TMPDIR}/install_baseäöüt 2" \
      --output_base="${output_base_2}" \
      build \
      --extra_toolchains=@rules_python//python:autodetecting_toolchain \
      --enable_bzlmod \
      --check_direct_dependencies=error \
      --lockfile_mode=update \
      --override_repository=$(cat derived/maven/MAVEN_CANONICAL_REPO_NAME)=derived/maven \
      --nostamp \
      --verbose_failures \
      //src:bazel &> $TEST_log || fail "Second build failed"
    assert_exists "${output_base_2}/java.log"
    expect_not_log ERROR
    expect_not_log "Exception:"
    expect_not_log "Error:"
    hash_outputs >"${TEST_TMPDIR}/sum2"

    if ! diff -U0 "${TEST_TMPDIR}/sum1" "${TEST_TMPDIR}/sum2" >$TEST_log; then
      fail "Non-deterministic outputs found!"
    fi
}

run_suite "determinism test"
