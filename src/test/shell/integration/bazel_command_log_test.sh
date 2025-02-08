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

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

log="$(bazel --batch info command_log)"

function tear_down() {
  # Clean up after ourselves.
  bazel --nobatch shutdown
}

function strip_lines_from_bazel_cc() {
  # sed can't redirect back to its input file (it'll only generate an empty
  # file). In newer versions of gnu sed there is a -i option to edit in place.

  # different sandbox_root result in different startup options
  # TODO(b/5568649): Remove host_javabase from tests and stop removing the
  # warning from the stderr output.
  clean_log=$(\
    sed \
    -e "/^INFO: Reading 'startup' options from /d" \
    -e '/^\$TEST_TMPDIR defined, some defaults will be overridden/d' \
    -e '/^OpenJDK 64-Bit Server VM warning: ignoring option UseSeparateVSpacesInYoungGen; support was removed in 8.0/d' \
    -e '/^Starting local B[azel]* server (.*) and connecting to it\.\.\.\.*$/d' \
    -e '/^\.\.\. still trying to connect to local B[azel]* server ([1-9][0-9]*) after [1-9][0-9]* seconds \.\.\.\.*$/d' \
    -e '/^Killed non-responsive server process/d' \
    -e '/server needs to be killed, because the startup options are different/d' \
    -e '/^WARNING: Waiting for server process to terminate (waited 5 seconds, waiting at most 60)$/d' \
    -e '/^WARNING: The startup option --host_javabase is deprecated; prefer --server_javabase.$/d' \
    -e '/^WARNING: The home directory is not defined, no home_rc will be looked for.$/d' \
    -e '/^WARNING: ignoring JAVA_TOOL_OPTIONS in environment.$/d' \
    -e '/^WARNING: The following rc files are no longer being read, please transfer their contents or import their path into one of the standard rc files:$/d' \
    -e '/^\/etc\/bazel.bazelrc$/d' \
    -e '/Options -Xverify:none and -noverify were deprecated in JDK 13 and will likely be removed in a future release/d' \
    -e '/^E[0-9]* /d' \
    $TEST_log)

  echo "$clean_log" > $TEST_log
}

function strip_protobuf_unsafe_warning() {
  # TODO: Protobuf triggers illegal reflective access warning in JDK 9.
  # Remove this workaround when protobuf fixes this.
  # See https://github.com/google/protobuf/issues/3781
  clean_log=$(\
    sed \
    -e "/^WARNING: An illegal reflective access operation has occurred/d" \
    -e "/^WARNING: Illegal reflective access by com\.google\.protobuf\.UnsafeUtil /d" \
    -e "/^WARNING: Please consider reporting this to the maintainers of com\.google\.protobuf\.UnsafeUtil/d" \
    -e "/^WARNING: Use --illegal-access=warn to enable warnings of further illegal reflective access operations/d" \
    -e "/^WARNING: All illegal access operations will be denied in a future release/d" \
    $TEST_log)

  echo "$clean_log" > $TEST_log
}

function test_batch_mode() {
  # capture stdout/stderr in $TEST_log
  bazel --batch info >&$TEST_log || fail "Expected success"

  # strip extra lines printed by bazel.cc
  strip_lines_from_bazel_cc
  strip_protobuf_unsafe_warning

  # compare $TEST_log with command.log
  assert_equals "" "$(diff $TEST_log $log 2>&1)"
}

function test_batch_mode_with_logging_flag() {
  bazel --batch info --logging 6 >&$TEST_log || fail "Expected success"
  LOG_FILE="$(grep -E "^server_log: .*" "${TEST_log}" \
      | sed -e "s/server_log: //")" \
      || fail "grep for server_log path failed"

  # strip extra lines printed by bazel.cc
  strip_lines_from_bazel_cc
  strip_protobuf_unsafe_warning

  # compare $TEST_log with command.log
  assert_equals "" "$(diff $TEST_log $log 2>&1)"

  # verify logging output is captured
  assert_equals "1" $(grep -c "Log level: FINEST$" $LOG_FILE)
}

function test_client_server_mode() {
  # capture stdout/stderr in $TEST_log
  bazel info >&$TEST_log || fail "Expected success"

  # strip extra lines printed by bazel.cc
  strip_lines_from_bazel_cc

  # compare $TEST_log with command.log
  assert_equals "" "$(diff $TEST_log $log 2>&1)"
}

function test_client_server_mode_with_logging_flag() {
  # capture stdout/stderr in $TEST_log
  bazel info --logging 6 >&$TEST_log || fail "Expected success"

  # strip extra lines printed by bazel.cc
  strip_lines_from_bazel_cc

  # compare $TEST_log with command.log
  assert_equals "" "$(diff $TEST_log $log 2>&1)"
}

run_suite "Integration tests of ${PRODUCT_NAME} command log."
