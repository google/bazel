#!/bin/bash -eu
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
source "$(rlocation "io_bazel/src/main/starlark/tests/builtins_bzl/builtin_test_setup.sh")" \
  || { echo "builtin_test_setup.sh not found!" >&2; exit 1; }

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

function test_starlark_cc() {
  setup_tests src/main/starlark/tests/builtins_bzl/cc
  mkdir -p "src/conditions"
  cp "$(rlocation "io_bazel/src/conditions/BUILD")" "src/conditions/BUILD"

  add_rules_cc "MODULE.bazel"
  cat >> MODULE.bazel<<EOF
bazel_dep(name = "test_repo", repo_name = "my_test_repo")
local_path_override(
    module_name = "test_repo",
    path = "src/main/starlark/tests/builtins_bzl/cc/cc_shared_library/test2",
)
EOF
  if "$is_windows"; then
    START_OPTS='--output_user_root=C:/tmp'
  else
    START_OPTS=''
  fi

  bazel $START_OPTS test --define=is_bazel=true --test_output=streamed \
    --experimental_cc_static_library \
    //src/main/starlark/tests/builtins_bzl/cc/... || fail "expected success"
}

function test_cc_static_library_duplicate_symbol() {
  mkdir -p pkg
  cat > pkg/BUILD<<'EOF'
cc_static_library(
    name = "static",
    deps = [
        ":direct1",
        ":direct2",
    ],
)
cc_library(
    name = "direct1",
    srcs = ["direct1.cc"],
)
cc_library(
    name = "direct2",
    srcs = ["direct2.cc"],
    deps = [":indirect"],
)
cc_library(
    name = "indirect",
    srcs = ["indirect.cc"],
)
EOF
  cat > pkg/direct1.cc<<'EOF'
int foo() { return 42; }
EOF
  cat > pkg/direct2.cc<<'EOF'
int bar() { return 21; }
EOF
  cat > pkg/indirect.cc<<'EOF'
int foo() { return 21; }
EOF

  bazel build --experimental_cc_static_library //pkg:static \
    &> $TEST_log && fail "Expected build to fail"
  if "$is_windows"; then
    expect_log "direct1.obj"
    expect_log "indirect.obj"
    expect_log " foo("
  elif is_darwin; then
    expect_log "Duplicate symbols found in .*/pkg/libstatic.a:"
    expect_log "direct1.o: T foo()"
    expect_log "indirect.o: T foo()"
  else
    expect_log "Duplicate symbols found in .*/pkg/libstatic.a:"
    expect_log "direct1.pic.o: T foo()"
    expect_log "indirect.pic.o: T foo()"
  fi

  bazel build --experimental_cc_static_library //pkg:static \
    --features=-symbol_check \
    &> $TEST_log || fail "Expected build to succeed"
}

function test_cc_static_library_duplicate_symbol_mixed_type() {
  mkdir -p pkg
  cat > pkg/BUILD<<'EOF'
cc_static_library(
    name = "static",
    deps = [
        ":direct1",
        ":direct2",
    ],
)
cc_library(
    name = "direct1",
    srcs = ["direct1.cc"],
)
cc_library(
    name = "direct2",
    srcs = ["direct2.cc"],
    deps = [":indirect"],
)
cc_library(
    name = "indirect",
    srcs = ["indirect.cc"],
)
EOF
  cat > pkg/direct1.cc<<'EOF'
int foo;
EOF
  cat > pkg/direct2.cc<<'EOF'
int bar = 21;
EOF
  cat > pkg/indirect.cc<<'EOF'
int foo = 21;
EOF

  bazel build --experimental_cc_static_library //pkg:static \
    &> $TEST_log && fail "Expected build to fail"
  if "$is_windows"; then
    expect_log "direct1.obj"
    expect_log "indirect.obj"
    expect_log " foo"
  elif is_darwin; then
    expect_log "Duplicate symbols found in .*/pkg/libstatic.a:"
    expect_log "direct1.o: S _foo"
    expect_log "indirect.o: D _foo"
  else
    expect_log "Duplicate symbols found in .*/pkg/libstatic.a:"
    expect_log "direct1.pic.o: B foo"
    expect_log "indirect.pic.o: D foo"
  fi

  bazel build --experimental_cc_static_library //pkg:static \
    --features=-symbol_check \
    &> $TEST_log || fail "Expected build to succeed"
}

function test_cc_static_library_protobuf() {
  if "$is_windows"; then
    # Fails on Windows due to long paths of the test workspace.
    return 0
  fi

  add_protobuf "MODULE.bazel"
  mkdir -p pkg
  cat > pkg/BUILD<<'EOF'
cc_static_library(
    name = "protobuf",
    deps = ["@com_google_protobuf//:protobuf"],
)
EOF

  # can be removed with protobuf v28.x onwards
  if $is_windows; then
    CXXOPTS=""
  else
    CXXOPTS="--cxxopt=-Wno-deprecated-declarations --host_cxxopt=-Wno-deprecated-declarations"
  fi
  bazel build $CXXOPTS --experimental_cc_static_library //pkg:protobuf \
    &> $TEST_log || fail "Expected build to fail"
}

run_suite "cc_* built starlark test"
