#!/bin/bash
#
# Copyright 2023 The Bazel Authors. All rights reserved.
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
# Test of Bazel's unicode i/o in actions

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

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

export LC_ALL="C.UTF-8"

function set_up {
  touch WORKSPACE
  cp -f "$(rlocation "io_bazel/src/test/shell/integration/unicode_test_BUILD")" BUILD
  cp -f "$(rlocation "io_bazel/src/test/shell/integration/unicode_test.bzl")" .
  cp -f "$(rlocation "io_bazel/src/test/shell/integration/unicode_test_expected.txt")" .
}

function test_unicode_genrule_cmd {
  local test_name="genrule_cmd"
  bazel build --genrule_strategy=local --spawn_strategy=local \
    --verbose_failures "//:${test_name}" >& "$TEST_log" \
    || fail "expected build to succeed"

  diff -u "${PRODUCT_NAME}-genfiles/${test_name}.out" \
    unicode_test_expected.txt \
    >>"${TEST_log}" 2>&1 || fail "Output not as expected"
}

function test_unicode_action_run_argument {
  local test_name="action_run_argument"
  bazel build --genrule_strategy=local --spawn_strategy=local \
    --verbose_failures "//:${test_name}" >& "$TEST_log" \
    || fail "expected build to succeed"

  diff -u "${PRODUCT_NAME}-bin/${test_name}.out" \
    unicode_test_expected.txt \
    >>"${TEST_log}" 2>&1 || fail "Output not as expected"
}

function test_unicode_action_write_content {
  local test_name="action_write_content"
  bazel build --genrule_strategy=local --spawn_strategy=local \
    --verbose_failures "//:${test_name}" >& "$TEST_log" \
    || fail "expected build to succeed"

  diff -u "${PRODUCT_NAME}-bin/${test_name}.out" \
    unicode_test_expected.txt \
    >>"${TEST_log}" 2>&1 || fail "Output not as expected"
}

function test_unicode_action_run_param_file {
  local test_name="action_run_param_file"
  bazel build --genrule_strategy=local --spawn_strategy=local \
      "//:${test_name}" >& "$TEST_log" \
      || fail "expected build to succeed"

  quoted_unicode_test_expected="'$(cat unicode_test_expected.txt)'"

  echo "Expected: ${quoted_unicode_test_expected}"

  cat_output=$(cat "${PRODUCT_NAME}-bin/${test_name}.out")
  assert_equals "${cat_output}" \
      "${quoted_unicode_test_expected}" \
      || fail "Output not as expected"

  param_file_output=$(cat "${PRODUCT_NAME}-bin/${test_name}.out-0.params")
  assert_equals "${param_file_output}" \
        "${quoted_unicode_test_expected}" \
        || fail "Output not as expected"
}

run_suite "Integration tests for ${PRODUCT_NAME}'s unicode i/o in actions"
