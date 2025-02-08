#!/bin/bash
#
# Copyright 2021 The Bazel Authors. All rights reserved.
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
# Copy-pasted from Bazel's Bash runfiles library (tools/bash/runfiles/runfiles.bash).
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

cd "$TEST_TMPDIR"

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
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

function set_up() {
    cd ${WORKSPACE_DIR}
}

function tear_down() {
  # Start with a fresh bazel server.
  bazel shutdown
}

function helper() {
  command_option="$1"
  # Name of the calling function to end up using distinct packages.
  local -r pkg=${FUNCNAME[1]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > "$pkg/BUILD" << 'EOF'
genrule(
    name = "foo",
    srcs = ["whocares.in"],
    outs = ["foo.out"],
    cmd = "touch $@",
)
EOF
  touch "$pkg/whocares.in"
  bazel build $command_option "//$pkg:foo" &> "$TEST_log" \
    || fail "Expected success."
  bazel build $command_option "//$pkg:foo" \
    --record_full_profiler_data \
    --noslim_profile \
    --profile=/tmp/profile.log &> "$TEST_log" || fail "Expected success."
  cat /tmp/profile.log | grep "VFS stat" > "$TEST_log" \
    || fail "Missing profile file."
}

function test_nowatchfs() {
  helper ""
  local -r pkg=${FUNCNAME[0]}
  expect_log "VFS stat.*${pkg}/whocares.in"
}

function test_command() {
  helper "--watchfs"
  local -r pkg=${FUNCNAME[0]}
  expect_not_log "VFS stat.*${pkg}/whocares.in"
}

function test_special_chars() {
  bazel info
  mkdir -p empty || fail "mkdir empty"
  touch empty/BUILD
  bazel build --watchfs //empty/... &> "$TEST_log" || fail "Expected success."
  expect_not_log "Hello, Unicode!"

  mkdir pkg || fail "mkdir pkg"
  cat > pkg/BUILD << 'EOF'
print("Hello, Unicode!")

genrule(
    name = "foo",
    srcs = ["foo 🌱.in"],
    outs = ["foo.out"],
    output_to_bindir = True,
    cmd = "cp '$<' $@",
)
EOF
  cat > 'pkg/foo 🌱.in' << 'EOF'
foo
EOF

  sleep 5
  bazel build --watchfs //pkg/... &> "$TEST_log" || fail "Expected success."
  expect_not_log "WARNING:.*falling back to manually"
  expect_log "Hello, Unicode!"
  assert_contains "foo" "${PRODUCT_NAME}-bin/pkg/foo.out"

  cat > 'pkg/foo 🌱.in' << 'EOF'
bar
EOF

  sleep 5
  bazel build --watchfs //pkg/... &> "$TEST_log" || fail "Expected success."
  expect_not_log "WARNING:.*falling back to manually"
  expect_not_log "Hello, Unicode!"
  assert_contains "bar" "${PRODUCT_NAME}-bin/pkg/foo.out"
}

function test_large_number_of_files() {
  if [[ "${PLATFORM-}" == "darwin" ]]; then
    # Tests Linux-specific JVM flags.
    return 0
  fi

  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > "$pkg/BUILD" << 'EOF'
genrule(
    name = "gen",
    outs = ["out"],
    srcs = [":srcs"],
    cmd = "touch $@",
)

filegroup(
    name = "srcs",
    srcs = glob(["*.txt"]),
)
EOF
  touch "$pkg/1.txt"
  bazel build --watchfs "//$pkg:gen" &> "$TEST_log" || fail "Expected success."

  # By default, the JVM will only report up to 500 changed files per directory before it overflows.
  # https://github.com/openjdk/jdk/blob/2faf8b8d582183275b1fdc92313a1c63c1753e80/src/java.base/share/classes/sun/nio/fs/AbstractWatchKey.java#L40
  touch "$pkg/{1..600}.txt"
  bazel build --watchfs "//$pkg:gen" &> "$TEST_log" || fail "Expected success."
  expect_not_log "WARNING:"
}

run_suite "Integration tests for --watchfs."
