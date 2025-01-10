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
# An end-to-end test for Bazel's option handling

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

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

#### SETUP #############################################################

add_to_bazelrc "build --terminal_columns=6"

function create_pkg() {
  local -r pkg=$1
  mkdir -p $pkg
  # have test with a long name, to be able to test line breaking in the output
  cat > $pkg/xxxxxxxxxxxxxxxxxxxxxxxxxtrue.sh <<EOF
#!/bin/sh
exit 0
EOF
  chmod 755 $pkg/xxxxxxxxxxxxxxxxxxxxxxxxxtrue.sh
  cat > $pkg/BUILD <<EOF
sh_test(
  name = "xxxxxxxxxxxxxxxxxxxxxxxxxtrue",
  srcs = ["xxxxxxxxxxxxxxxxxxxxxxxxxtrue.sh"],
)
EOF
}

#### TESTS #############################################################

function test_terminal_columns_honored() {
  local -r pkg=$FUNCNAME
  create_pkg $pkg
  bazel test --curses=yes --color=yes --nocache_test_results \
      $pkg:xxxxxxxxxxxxxxxxxxxxxxxxxtrue \
      2>$TEST_log || fail "bazel test failed"
  # the lines are wrapped to 6 characters
  expect_log '^xxxx'
  expect_not_log '^xxxxxxx'
}

function test_options_override() {
  local -r pkg=$FUNCNAME
  create_pkg $pkg
  bazel test --curses=yes --color=yes --terminal_columns=10 \
      --nocache_test_results \
      $pkg:xxxxxxxxxxxxxxxxxxxxxxxxxtrue 2>$TEST_log || fail "bazel test failed"
  # the lines are wrapped to 10 characters
  expect_log '^xxxxxxxx'
  expect_not_log '^xxxxxxxxxxx'
}

function test_bep_option_source() {
  local -r build_event_text_file="$(pwd)/bep.txt"
  local -r pkg=$FUNCNAME
  create_pkg $pkg
  bazel build --build_event_text_file="$build_event_text_file" \
      $pkg:xxxxxxxxxxxxxxxxxxxxxxxxxtrue || fail "build failed"
  assert_contains "source: \"$bazelrc\"" "$build_event_text_file"
}

run_suite "Integration tests for rc options handling"
