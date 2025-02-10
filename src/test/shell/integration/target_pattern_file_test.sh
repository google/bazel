#!/bin/bash
#
# Copyright 2020 The Bazel Authors. All rights reserved.
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

# `uname` returns the current platform, e.g "MSYS_NT-10.0" or "Linux".
# `tr` converts all upper case letters to lower case.
# `case` matches the result if the `uname | tr` expression to string prefixes
# that use the same wildcards as names do in Bash, i.e. "msys*" matches strings
# starting with "msys", and "*" matches everything (it's the default case).
case "$(uname -s | tr [:upper:] [:lower:])" in
msys*)
  # As of 2018-08-14, Bazel on Windows only supports MSYS Bash.
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

add_to_bazelrc "build --package_path=%workspace%"

#### SETUP #############################################################

function setup() {
  cat >BUILD <<'EOF'
genrule(name = "x", outs = ["x.out"], cmd = "echo true > $@", executable = True)
sh_test(name = "y", srcs = ["x.out"])
EOF

  cat >build.params <<'EOF'
# Test comment
//:x # Trailing comment
//:y
#
EOF
}

#### TESTS #############################################################

function test_target_pattern_file_build() {
  setup
  bazel build --target_pattern_file=build.params >& $TEST_log || fail "Expected success"
  expect_log "2 targets"
  test -f bazel-genfiles/x.out
}

function test_target_pattern_file_test() {
  setup
  echo //:y > test.params
  bazel test --target_pattern_file=test.params >& $TEST_log || fail "Expected success"
  expect_log "1 test passes"
}

function test_target_pattern_file_and_cli_pattern() {
  setup
  bazel build --target_pattern_file=build.params -- //:x >& $TEST_log && fail "Expected failure"
  expect_log "ERROR: Command-line target pattern and --target_pattern_file cannot both be specified"
}

run_suite "Tests for using target_pattern_file"
