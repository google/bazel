#!/bin/bash
#
# Copyright 2017 The Bazel Authors. All rights reserved.
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
# An end-to-end test that Bazel's UI produces reasonable output.

set -euo pipefail
# --- begin runfiles.bash initialization ---
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
  # As of 2019-01-15, Bazel on Windows only supports MSYS Bash.
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

if $is_windows; then
  export LC_ALL=C.utf8
elif [[ "$(uname -s)" == "Linux" ]]; then
  export LC_ALL=C.UTF-8
else
  export LC_ALL=en_US.UTF-8
fi

#### SETUP #############################################################

add_to_bazelrc "build --genrule_strategy=local"
add_to_bazelrc "test --test_strategy=standalone"

function set_up() {
  mkdir -p pkg
  touch remote_file
  if $is_windows; then
    # The correct syntax for http_file on Windows is "file:///c:/foo/bar.txt"
    local -r cwd="/$(cygpath -m "$PWD")"
  else
    local -r cwd="$PWD"
  fi
  cat > MODULE.bazel <<EOF
http_file = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
http_file(name="remote", urls=["file://${cwd}/remote_file"])
EOF
  touch BUILD
}

#### TESTS #############################################################


function test_fetch {
  bazel clean --expunge
  bazel fetch @remote//... --curses=yes 2>$TEST_log || fail "bazel fetch failed"
  expect_log 'Fetching.*remote_file'
}

function expect_log_with_msys_unicode_fix() {
  if $is_windows; then
    # MSYS grep for some reason doesn't find Unicode characters, so we convert
    # both the pattern and the log to hex and search for the hex pattern.
    # https://github.com/msys2/MSYS2-packages/issues/5001
    local -r pattern_hex="$(echo -n "$1" | hexdump -ve '1/1 "%.2x"')"
    hexdump -ve '1/1 "%.2x"' $TEST_log | grep -q -F "$pattern_hex" ||
      fail "Could not find \"$1\" in \"$(cat $TEST_log)\" (via hexdump)"
  else
    expect_log "$1"
  fi
}

function test_unicode_output {
  local -r unicode_string="äöüÄÖÜß🌱"

  mkdir -p pkg
  cat > pkg/BUILD <<EOF
print("str_${unicode_string}")

genrule(
  name = "gen",
  outs = ["out_${unicode_string}"],
  cmd = "touch \$@",
)
EOF

  bazel build //pkg:gen 2>$TEST_log || fail "bazel build failed"
  expect_log_with_msys_unicode_fix "str_${unicode_string}"
  expect_log_with_msys_unicode_fix "out_${unicode_string}"
}

run_suite "Bazel-specific integration tests for the UI"
