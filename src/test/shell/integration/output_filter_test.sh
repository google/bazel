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
# output_filter_test.sh: a couple of end to end tests for the warning
# filter functionality.

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

function set_up() {
  add_rules_java MODULE.bazel
}

function test_output_filter_cc() {
  # "test warning filter for C compilation"
  local -r pkg=$FUNCNAME

  if $is_windows; then
    local -r copts=\"/W3\"
  else
    local -r copts=""
  fi

  mkdir -p $pkg/cc/main
  cat > $pkg/cc/main/BUILD <<EOF
cc_library(
    name = "cc",
    srcs = ["main.c"],
    copts = [$copts],
    nocopts = "-Werror",
)
EOF

  cat >$pkg/cc/main/main.c <<EOF
#include <stdio.h>

int main(void)
{
#ifdef _WIN32
  // MSVC does not support the #warning directive.
  int unused_variable__triggers_a_warning;  // triggers C4101
#else  // not COMPILER_MSVC
  // GCC/Clang support #warning.
#warning("triggers_a_warning")
#endif  // COMPILER_MSVC
  printf("%s", "Hello, World!\n");
  return 0;
}
EOF

  bazel build --output_filter="dummy" --noincompatible_disable_nocopts \
      $pkg/cc/main:cc >&"$TEST_log" || fail "build failed"
  expect_not_log "triggers_a_warning"

  echo "/* adding a comment forces recompilation */" >> $pkg/cc/main/main.c
  bazel build --noincompatible_disable_nocopts $pkg/cc/main:cc >&"$TEST_log" \
      || fail "build failed"
  expect_log "triggers_a_warning"
}

function test_output_filter_java() {
  # "test warning filter for Java compilation"
  local -r pkg=$FUNCNAME

  mkdir -p $pkg/java/main
  cat >$pkg/java/main/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(name = 'main',
    deps = ['//$pkg/java/hello_library'],
    srcs = ['Main.java'],
    javacopts = ['-Xlint:deprecation'],
    main_class = 'main.Main')
EOF

  cat >$pkg/java/main/Main.java <<EOF
package main;
import hello_library.HelloLibrary;
public class Main {
  public static void main(String[] args) {
    HelloLibrary.funcHelloLibrary();
    System.out.println("Hello, World!");
  }
}
EOF

  mkdir -p $pkg/java/hello_library
  cat >$pkg/java/hello_library/BUILD <<EOF
load("@rules_java//java:java_library.bzl", "java_library")
package(default_visibility=['//visibility:public'])
java_library(name = 'hello_library',
             srcs = ['HelloLibrary.java'],
             javacopts = ['-Xlint:deprecation']);
EOF

  cat >$pkg/java/hello_library/HelloLibrary.java <<EOF
package hello_library;
public class HelloLibrary {
  /** @deprecated */
  @Deprecated
  public static void funcHelloLibrary() {
    System.out.print("Hello, Library!;");
  }
}
EOF

  # check that we do get a deprecation warning
  bazel build //$pkg/java/main:main >&"$TEST_log" || fail "build failed"
  expect_log "has been deprecated"
  # check that we do get a deprecation warning if we select the target

  echo "// add comment to trigger recompilation" >> $pkg/java/hello_library/HelloLibrary.java
  echo "// add comment to trigger recompilation" >> $pkg/java/main/Main.java
  bazel build --output_filter=$pkg/java/main //$pkg/java/main:main >&"$TEST_log" \
    || fail "build failed"
  expect_log "has been deprecated"

  # check that we do not get a deprecation warning if we select another target
  echo "// add another comment" >> $pkg/java/hello_library/HelloLibrary.java
  echo "// add another comment" >> $pkg/java/main/Main.java
  bazel build --output_filter=$pkg/java/hello_library //$pkg/java/main:main >&"$TEST_log" \
    || fail "build failed"
  expect_not_log "has been deprecated"
}

function test_test_output_printed() {
  # "test that test output is printed if warnings are disabled"
  local -r pkg=$FUNCNAME

  mkdir -p $pkg/foo/bar
  cat >$pkg/foo/bar/BUILD <<EOF
sh_test(name='test',
        srcs=['test.sh'])
EOF

  cat >$pkg/foo/bar/test.sh <<EOF
#!/bin/sh
exit 0
EOF

  chmod +x $pkg/foo/bar/test.sh

  bazel test --experimental_ui_debug_all_events --output_filter="dummy" \
      $pkg/foo/bar:test >&"$TEST_log" || fail
  expect_log "PASS.*: //$pkg/foo/bar:test"
}

function test_output_filter_does_not_apply_to_test_output() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg/geflugel
  cat >$pkg/geflugel/BUILD <<EOF
sh_test(name='mockingbird', srcs=['mockingbird.sh'])
sh_test(name='hummingbird', srcs=['hummingbird.sh'])
EOF

  cat >$pkg/geflugel/mockingbird.sh <<EOF
#!$(which bash)
echo "To kill -9 a mockingbird"
exit 1
EOF

  cat >$pkg/geflugel/hummingbird.sh <<EOF
#!$(which bash)
echo "To kill -9 a hummingbird"
exit 1
EOF

  chmod +x $pkg/geflugel/*.sh

  bazel test //$pkg/geflugel:all --test_output=errors --output_filter=mocking &> $TEST_log \
    && fail "expected tests to fail"

  expect_log "To kill -9 a mockingbird"
  expect_log "To kill -9 a hummingbird"
}

function test_filters_deprecated_targets() {
  local -r pkg=$FUNCNAME
  init_test "test that deprecated target warnings are filtered"

  mkdir -p $pkg/{relativity,ether}
  cat > $pkg/relativity/BUILD <<EOF
cc_binary(name = 'relativity', srcs = ['relativity.cc'], deps = ['//$pkg/ether'])
EOF

  cat > $pkg/ether/BUILD <<EOF
cc_library(name = 'ether', srcs = ['ether.cc'], deprecation = 'Disproven',
           visibility = ['//visibility:public'])
EOF

  bazel build --nobuild //$pkg/relativity &> $TEST_log || fail "Expected success"
  expect_log_once "WARNING:.*target '//$pkg/relativity:relativity' depends on \
deprecated target '//$pkg/ether:ether': Disproven"

  bazel build --nobuild --output_filter="^//pizza" \
      //$pkg/relativity &> $TEST_log || fail "Expected success"
  expect_not_log "WARNING:.*target '//$pkg/relativity:relativity' depends on \
deprecated target '//$pkg/ether:ether': Disproven"
}

function test_workspace_status_command_error_output_printed() {
  if type try_with_timeout >&/dev/null; then
    # TODO(bazel-team): Hack to disable test since Bazel's
    # workspace_status_cmd's stderr isn't reported. Determine if this a bug or
    # a feature.
    return
  fi

  local -r pkg="$FUNCNAME"

  mkdir -p "$pkg"
  cat >"$pkg/BUILD" <<EOF
genrule(name = 'foo', outs = ['foo.txt'], cmd = 'touch \$@')
EOF

  local status_cmd="$TEST_TMPDIR/status_cmd.sh"

  cat >"$status_cmd" <<EOF
#!/bin/bash

echo 'STATUS_COMMAND_RAN' >&2
EOF
  chmod +x "$status_cmd" || fail "Failed to mark $status_cmd executable"

  bazel build --workspace_status_command="$status_cmd" \
      --auto_output_filter=none \
      "//$pkg:foo" >&"$TEST_log" \
      || fail "Expected success"
  expect_log STATUS_COMMAND_RAN

  bazel build --workspace_status_command="$status_cmd" \
      --auto_output_filter=packages \
      "//$pkg:foo" >&"$TEST_log" \
      || fail "Expected success"
  expect_log STATUS_COMMAND_RAN

  bazel build --workspace_status_command="$status_cmd" \
      --auto_output_filter=subpackages \
      "//$pkg:foo" >&"$TEST_log" \
      || fail "Expected success"
  expect_log STATUS_COMMAND_RAN

  bazel build --workspace_status_command="$status_cmd" \
      --auto_output_filter=all \
      "//$pkg:foo" >&"$TEST_log" \
      || fail "Expected success"
  expect_not_log STATUS_COMMAND_RAN
}

run_suite "Warning Filter tests"
