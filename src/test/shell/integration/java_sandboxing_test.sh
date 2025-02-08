#!/bin/bash
#
# Copyright 2019 The Bazel Authors. All rights reserved.
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


# Tests sandboxing spawn strategy. These tests run Java targets, which require
# enough more analysis time that we want different settings for them. If
# possible, add new sandboxing tests to sandboxing_test.sh instead.

set -euo pipefail

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function set_up() {
  add_rules_java MODULE.bazel
}

function tear_down() {
  bazel clean --expunge
  bazel shutdown
  rm -rf pkg
}

function do_succeed_when_executor_not_initialized_test() {
  local extra_args=( "${@}" )

  mkdir pkg
  mkfifo pkg/BUILD

  bazel build --spawn_strategy=sandboxed --nobuild "${@}" //pkg:all \
    >"${TEST_log}" 2>&1 &
  local pid="${!}"

  echo "Waiting for Blaze to finish initializing all modules"
  while ! grep "currently loading: pkg" "${TEST_log}"; do
    sleep 1
  done

  echo "Interrupting Blaze before it gets to init the executor"
  kill "${pid}"

  echo "And now giving Blaze a chance to finalize all modules"
  echo "unblock fifo" >pkg/BUILD
  wait "${pid}" || true

  expect_log "Build did NOT complete successfully"
  # Disallow some common messages we might see during a crash.
  expect_not_log "Internal error"
  expect_not_log "stack trace"
  expect_not_log "NullPointerException"
}

# Builds a target with the given strategy and ensures that the actions require
# params files to be written in the output base.
function build_with_params() {
  local strategy="${1}"; shift

  # Build a Java binary during this test because the Java rules work well with
  # sandboxing and support workers.
  mkdir pkg
  cat >pkg/BUILD <<'EOF'
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(
    name = "java",
    srcs = ["Main.java"],
    main_class = "pkg.Main",
)
EOF
  cat >pkg/Main.java <<'EOF'
package pkg;
public class Main {
  public static void main(String[] args) {}
}
EOF

  # ReannotatingJlink doesn't support workers.
  bazel build \
    --strategy=Javac="${strategy}" \
    --strategy=JavaResourceJar="${strategy}" \
    --strategy=ReannotatingJlink=sandboxed \
    --strategy=CopyReannotatedJdk=sandboxed \
    --sandbox_debug \
    --min_param_file_size=100 \
    "${@}" \
    //pkg:java || fail "Build failed"
}

# Verifies that building a target that uses params files writes those params
# files to both the execroot and the sandbox.
function do_test_params_files() {
  local strategy="${1}"; shift

  local output_base
  output_base="$(bazel info output_base)" || fail "Cannot get output base"

  build_with_params "${strategy}" \
    --build  # Need a no-op flag to avoid set -u breakage on macOS.

  find -L "${output_base}" -name "*params" >files.txt || true
  grep -q "${output_base}/execroot" files.txt \
    || fail "Expected params files not found in execroot"
  grep -q "${output_base}/sandbox" files.txt \
    || fail "Expected params files not found in sandbox tree"
}

# We expect "sandboxed" to use the system-specific sandbox instead of
# the processwrapper-sandbox (tested below). But if that's not the case,
# there is not much we can do here.
function test_params_files_default_sandbox() {
  do_test_params_files sandboxed
}

function test_params_files_process_wrapper_sandbox() {
  do_test_params_files processwrapper-sandbox
}

# Worker tests do not really belong in this file, but as we are exercising
# the same code path used for the sandbox regarding virtual input artifact
# materialization, we keep them here to reuse the testing logic.
function test_params_files_worker() {
  local output_base
  output_base="$(bazel info output_base)" || fail "Cannot get output base"

  build_with_params worker \
    --build  # Need a no-op flag to avoid set -u breakage on macOS.

  find -L "${output_base}" -name "*params" >files.txt || true
  grep -q "${output_base}/execroot" files.txt \
    || fail "Expected params files not found in execroot"
}

run_suite "slow_sandboxing"
