#!/bin/bash
#
# Copyright 2024 The Bazel Authors. All Rights Reserved.
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
# Tests that the SystemExitDetectingShutdownHook correctly detects a call to
# System.exit and prints a stack trace.

[ -z "$TEST_SRCDIR" ] && { echo "TEST_SRCDIR not set!" >&2; exit 1; }

# Load the unit-testing framework
source "$1" || \
  { echo "Failed to load unit-testing framework $1" >&2; exit 1; }

# --- begin runfiles.bash initialization v3 ---
# Copy-pasted from the Bazel Bash runfiles library v3.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
# shellcheck disable=SC1090
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v3 ---

set +o errexit

PROGRAM_THAT_CALLS_SYSTEM_EXIT_JAR="$2"
readonly PROGRAM_THAT_CALLS_SYSTEM_EXIT_JAR
JAVABASE="$3"
if [[ "$TEST_WORKSPACE" == "_main" ]]; then
  # For Bazel
  RUNFILES_JAVABASE=${JAVABASE#external/}
else
  # For Blaze
  RUNFILES_JAVABASE=${TEST_WORKSPACE}/${JAVABASE}
fi
EXPECTED_STACK_FILE="$4"
readonly EXPECTED_STACK_FILE

function test_prints_stack_trace_on_system_exit() {
  local output_file="${TEST_TMPDIR}/output.txt"

  JAVA=$(rlocation "${RUNFILES_JAVABASE}/bin/java")
  "${JAVA}" -jar "${PROGRAM_THAT_CALLS_SYSTEM_EXIT_JAR}" \
      2> "${output_file}"
  assert_equals 121 $?

  # We expect the output to be a stack trace that ends with the main method of
  # ProgramThatCallsSystemExit. We use sed to avoid hardcoding the exact line
  # numbers in the stack trace.
  sed -i 's/:[0-9][0-9]*/:XXX/' "${output_file}"
  diff -u "${output_file}" "${EXPECTED_STACK_FILE}" || \
    fail "Stack trace does not match expected stack trace"
}

run_suite "system_exit_detecting_test"
