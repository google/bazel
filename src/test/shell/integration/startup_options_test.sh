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
# Test of Bazel's startup option handling.

# --- begin runfiles.bash initialization ---
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

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

function test_different_startup_options() {
  pid=$(bazel --nobatch info server_pid 2> $TEST_log)
  [[ -n $pid ]] || fail "Couldn't run ${PRODUCT_NAME}"
  newpid=$(bazel --batch info server_pid 2> $TEST_log)
  expect_log "WARNING: Running B\\(azel\\|laze\\) server needs to be killed, because the startup options are different."
  [[ "$newpid" != "$pid" ]] || fail "pid $pid was the same!"
  if ! "$is_windows"; then
    # On Windows: the kill command of MSYS doesn't work for Windows PIDs.
    kill -0 $pid 2> /dev/null && fail "$pid not dead" || true
    kill -0 $newpid 2> /dev/null && fail "$newpid not dead" || true
  fi
}

# Regression test for Issue #1659
function test_command_args_are_not_parsed_as_startup_args() {
  bazel info --bazelrc=bar &> $TEST_log && fail "Should fail"
  expect_log "Unrecognized option: --bazelrc=bar"
  expect_not_log "Error: Unable to read .bazelrc file"
}

# Test that normal bazel works with and without --autodetect_server_javabase
# because it has an embedded JRE.
function test_autodetect_server_javabase() {
  bazel --autodetect_server_javabase version &> $TEST_log || fail "Should pass"
  bazel --noautodetect_server_javabase version &> $TEST_log || fail "Should pass"
}

# Below are the regression tests for Issue #7489
function test_multiple_bazelrc_later_overwrites_earlier() {
  # Help message only visible with --help_verbosity=medium
  help_message_in_description="--${PRODUCT_NAME}rc (a string; may be used multiple times)"

  echo "help --help_verbosity=short" > 1.rc
  echo "help --help_verbosity=medium" > 2.rc
  bazel "--${PRODUCT_NAME}rc=1.rc" "--${PRODUCT_NAME}rc=2.rc" help startup_options &> $TEST_log || fail "Should pass"
  expect_log "$help_message_in_description"

  echo "help --help_verbosity=medium" > 1.rc
  echo "help --help_verbosity=short" > 2.rc
  bazel "--${PRODUCT_NAME}rc=1.rc" "--${PRODUCT_NAME}rc=2.rc" help startup_options &> $TEST_log || fail "Should pass"
  expect_not_log "$help_message_in_description"
}

function test_multiple_bazelrc_set_different_options() {
  echo "common --verbose_failures" > 1.rc
  echo "common --test_output=all" > 2.rc
  bazel "--${PRODUCT_NAME}rc=1.rc" "--${PRODUCT_NAME}rc=2.rc" info --announce_rc &> $TEST_log || fail "Should pass"
  expect_log "Inherited 'common' options: --verbose_failures"
  expect_log "Inherited 'common' options: --test_output=all"
}

function test_bazelrc_after_devnull_ignored() {
  echo "common --verbose_failures" > 1.rc
  echo "common --test_output=all" > 2.rc
  echo "common --definitely_invalid_config" > 3.rc

  bazel "--${PRODUCT_NAME}rc=1.rc" "--${PRODUCT_NAME}rc=2.rc" "--${PRODUCT_NAME}rc=/dev/null" \
   "--${PRODUCT_NAME}rc=3.rc" info --announce_rc &> $TEST_log || fail "Should pass"
  expect_log "Inherited 'common' options: --verbose_failures"
  expect_log "Inherited 'common' options: --test_output=all"
  expect_not_log "--definitely_invalid_config"
}

run_suite "${PRODUCT_NAME} startup options test"
