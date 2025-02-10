#!/bin/bash
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
# Integration tests for Bazel client.
#

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

LOCK_HELPER="$(rlocation io_bazel/src/test/java/com/google/devtools/build/lib/testutil/external_file_system_lock_helper)"

function test_product_name_with_bazel_info() {
  touch MODULE.bazel

  bazel info >& "$TEST_log" || fail "Expected zero exit"

  expect_log "^bazel-bin:.*/execroot/_main/bazel-out/.*/bin\$"
  expect_log "^bazel-genfiles:.*/execroot/_main/bazel-out/.*/bin\$"
  expect_log "^bazel-testlogs:.*/execroot/_main/bazel-out/.*/testlogs\$"
  expect_log "^output_path:.*/execroot/_main/bazel-out\$"
  expect_log "^execution_root:.*/execroot/_main\$"
  expect_log "^server_log:.*/java\.log.*\$"
}

# This test is for Bazel only and not for Google's internal version (Blaze),
# because Bazel uses a different way to compute the workspace name.
function test_server_process_name_has_workspace_name() {
  mkdir foobarspace
  cd foobarspace
  touch MODULE.bazel
  ps -o args "$(bazel info server_pid)" &>"$TEST_log"
  expect_log "^bazel(foobarspace)"
  bazel shutdown
}

function test_install_base_lock() {
  # Use a custom install base location to ensure that it's not shared with other
  # server instances (e.g. when running multiple tests in parallel on BazelCI).
  local -r install_base="$TEST_TMPDIR/test_install_base_lock"

  # Start the server.
  bazel --install_base="${install_base}" info || fail "Expected success"

  # Try to get an exclusive lock on the install base, which should fail.
  "$LOCK_HELPER" "${install_base}.lock" exclusive exit && fail "Expected failure"

  # Shut down the server.
  bazel --install_base="${install_base}" shutdown || fail "Expected success"

  # Try to get an exclusive lock on the install base, which should succeed.
  "$LOCK_HELPER" "${install_base}.lock" exclusive exit || fail "Expected success"
}

function test_install_base_garbage_collection() {
  local -r install_user_root="$TEST_TMPDIR/test_install_base_garbage_collection"
  local -r install_base="${install_user_root}/abcdefabcdefabcdefabcdefabcdefab"

  local -r stale="${install_user_root}/12345678901234567890123456789012"
  mkdir -p "${stale}"
  touch "${stale}/A-server.jar"
  touch -t 200102030405 "${stale}"

  local -r fresh="${install_user_root}/98765432109876543210987654321098"
  mkdir -p "${fresh}"
  touch "${fresh}/A-server.jar"

  bazel --install_base="${install_base}" info \
      --experimental_install_base_gc_max_age=1d \
      &> "$TEST_log" || fail "Expected success"

  sleep 1

  if ! [[ -d "${fresh}" ]]; then
    fail "Expected ${fresh} to still exist"
  fi

  if [[ -d "${stale}" ]]; then
    fail "Expected ${stale} to no longer exist"
  fi
}

run_suite "client_test"
