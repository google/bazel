#!/bin/bash
#
# Copyright 2015 The Bazel Authors. All Rights Reserved.
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
# Integration testing of the test bridge protocol for JUnit4 tests.
# This is a test filter passed by "bazel test --test_filter".
#
# These tests operate by executing test methods in a testbed program
# and checking the output.
#

[ -z "$TEST_SRCDIR" ] && { echo "TEST_SRCDIR not set!" >&2; exit 1; }

# Load the unit-testing framework
source "$1" || \
  { echo "Failed to load unit-testing framework $1" >&2; exit 1; }

set +o errexit

TESTBED="${PWD}/$2"
SUITE_PARAMETER="$3"
SUITE_FLAG="-D${SUITE_PARAMETER}=com.google.testing.junit.runner.testbed.JUnit4TestbridgeExercises"
OOM_SUITE_FLAG="-D${SUITE_PARAMETER}=com.google.testing.junit.runner.testbed.JUnit4TestbridgeOomExercises"
XML_OUTPUT_FILE="${TEST_TMPDIR}/test.xml"

shift 3

#######################

function set_up() {
  # By default, the environment flag is unset.
  declare +x TESTBRIDGE_TEST_ONLY
}

# Test that we respond to TESTBRIDGE_TEST_ONLY in JUnit4 tests.
function test_Junit4() {
  cd "${TEST_TMPDIR}" || fail "Unexpected failure"

  # Run the test without environment flag; it should fail.
  declare +x TESTBRIDGE_TEST_ONLY
  "${TESTBED}" --jvm_flag="${SUITE_FLAG}" >& "${TEST_log}" && fail "Expected failure"
  expect_log 'Failures: 2'

  # Run the test with environment flag.
  declare -x TESTBRIDGE_TEST_ONLY="testPass"
  "${TESTBED}" --jvm_flag="${SUITE_FLAG}" >& "${TEST_log}" || fail "Expected success"
  expect_log 'OK.*1 test'

  # Finally, run the test once again without environment flag; it should fail.
  declare +x TESTBRIDGE_TEST_ONLY
  "${TESTBED}" --jvm_flag="${SUITE_FLAG}" >& "${TEST_log}" && fail "Expected failure again"
  expect_log 'Failures: 2'

  # Remove the XML output with failures, so it does not get picked up to
  # indicate a failure.
  rm -rf "${XML_OUTPUT_FILE}" || fail "failed to remove XML output"
}

# Test that the exit code reflects the success / failure reason.
function test_Junit4ExitCodes() {
  # only for Bazel
  [[ "${SUITE_PARAMETER}" -eq "bazel.test_suite" ]] || return
  cd "${TEST_TMPDIR}" || fail "Unexpected failure"

  # Run the test without environment flag; it should fail.
  declare +x TESTBRIDGE_TEST_ONLY
  "${TESTBED}" --jvm_flag="${OOM_SUITE_FLAG}" >& "${TEST_log}"
  assert_equals 137 $? || fail "Expected OOM failure"
  expect_log 'Failures: 2'

  # Run the test with environment flag and check the different expected exit codes.

  declare -x TESTBRIDGE_TEST_ONLY="testFailAssertion"
  "${TESTBED}" --jvm_flag="${OOM_SUITE_FLAG}" >& "${TEST_log}" && fail "Expected failure"
  assert_equals 1 $? || fail "Expected non-OOM failure"
  expect_log 'Failures: 1'

  declare -x TESTBRIDGE_TEST_ONLY="testFailWithOom"
  "${TESTBED}" --jvm_flag="${OOM_SUITE_FLAG}" >& "${TEST_log}" && fail "Expected failure"
  assert_equals 137 $? || fail "Expected OOM failure on single test case"
  expect_log 'Failures: 1'

  declare -x TESTBRIDGE_TEST_ONLY="testPass"
  "${TESTBED}" --jvm_flag="${OOM_SUITE_FLAG}" >& "${TEST_log}"
  assert_equals 0 $? || fail "Expected success"
  expect_log 'OK.*1 test'

  # Finally, run the test once again without environment flag; it should fail.
  declare +x TESTBRIDGE_TEST_ONLY
  "${TESTBED}" --jvm_flag="${OOM_SUITE_FLAG}" >& "${TEST_log}"
  assert_equals 137 $? || fail "Expected OOM failure again"
  expect_log 'Failures: 2'

  # Remove the XML output with failures, so it does not get picked up to
  # indicate a failure.
  rm -rf "${XML_OUTPUT_FILE}" || fail "failed to remove XML output"
}

# Test that TESTBRIDGE_TEST_ONLY is overridden by a direct flag.
function test_Junit4FlagOverridesEnv() {
  cd "${TEST_TMPDIR}" || fail "Unexpected failure"

  # Run the test with both environment and command line flags.
  declare -x TESTBRIDGE_TEST_ONLY="testFailOnce"
  "${TESTBED}" --jvm_flag="${SUITE_FLAG}" --test_filter testPass >& "${TEST_log}" || \
      fail "Expected success"
  expect_log 'OK.*1 test'

  declare -x TESTBRIDGE_TEST_ONLY="testPass"
  "${TESTBED}" --jvm_flag="${SUITE_FLAG}" --test_filter testFailOnce >& "${TEST_log}" && \
      fail "Expected failure"
  expect_log 'Failures: 1'
}

# Test that we respond to TESTBRIDGE_TEST_RUNNER_FAIL_FAST in JUnit4 tests.
function test_Junit4FailFast() {
  cd "${TEST_TMPDIR}" || fail "Unexpected failure"

  # Run the test without environment var.
  declare +x TESTBRIDGE_TEST_RUNNER_FAIL_FAST
  "${TESTBED}" --jvm_flag="${SUITE_FLAG}" &> "${TEST_log}" && fail "Expected failure"
  expect_log 'Failures: 2'

  # Run the test with environment var set to 0.
  declare -x TESTBRIDGE_TEST_RUNNER_FAIL_FAST="0"
  "${TESTBED}" --jvm_flag="${SUITE_FLAG}" &> "${TEST_log}" && fail "Expected failure"
  expect_log 'Failures: 2'

  # Run the test with environment var set to 1.
  declare -x TESTBRIDGE_TEST_RUNNER_FAIL_FAST="1"
  "${TESTBED}" --jvm_flag="${SUITE_FLAG}" &> "${TEST_log}" && fail "Expected failure"
  expect_log 'Failures: 1'

  # Run the test without environment var again.
  declare +x TESTBRIDGE_TEST_RUNNER_FAIL_FAST
  "${TESTBED}" --jvm_flag="${SUITE_FLAG}" &> "${TEST_log}" && fail "Expected failure"
  expect_log 'Failures: 2'
}

# Test that we fail on suite failures even if individual test cases pass
function test_JunitUndeclaredTestCaseFailures() {
  cd "${TEST_TMPDIR}" || fail "Unexpected failure"

  "${TESTBED}" \
  --jvm_flag="-D${SUITE_PARAMETER}=com.google.testing.junit.runner.testbed.Junit4UndeclaredTestCaseFailures" \
   &> "${TEST_log}" && fail "Expected failure"
  expect_log 'unnecessary Mockito stubbings'
  grep -q "tests='2' failures='1'" ${XML_OUTPUT_FILE} || \
    fail "Expected 1 failure in xml output: `cat ${XML_OUTPUT_FILE}`"
}

run_suite "junit4_testbridge_integration_test"
