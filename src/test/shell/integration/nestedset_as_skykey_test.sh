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
# execution_phase_tests.sh: miscellaneous integration tests of Bazel for
# behaviors that affect the execution phase.
#

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

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

#### HELPER FUNCTIONS ##################################################

if ! type try_with_timeout >&/dev/null; then
  # Bazel's testenv.sh defines try_with_timeout but the Google-internal version
  # uses a different testenv.sh.
  function try_with_timeout() { $* ; }
fi

function set_up() {
  cd ${WORKSPACE_DIR}
  add_rules_java MODULE.bazel

  mkdir -p "foo"
  cat > foo/foocc.py <<'EOF'
import sys

if __name__ == "__main__":
  assert len(sys.argv) >= 1
  output = open(sys.argv[1], "wt")
  for path in sys.argv[2:]:
    input = open(path, "rt")
    output.write(input.read())
EOF

  cat > foo/foo.bzl <<'EOF'
FooFiles = provider(fields = ["transitive_sources"])
def get_transitive_srcs(srcs, deps):
  return depset(
        srcs,
        transitive = [dep[FooFiles].transitive_sources for dep in deps])

def _foo_library_impl(ctx):
  trans_srcs = get_transitive_srcs(ctx.files.srcs, ctx.attr.deps)
  return [FooFiles(transitive_sources=trans_srcs)]

foo_library = rule(
    implementation = _foo_library_impl,
    attrs = {
        "srcs": attr.label_list(allow_files=True),
        "deps": attr.label_list(),
    },
)
def _foo_binary_impl(ctx):
  foocc = ctx.executable._foocc
  out = ctx.outputs.out
  trans_srcs = get_transitive_srcs(ctx.files.srcs, ctx.attr.deps)
  srcs_list = trans_srcs.to_list()
  ctx.actions.run(executable = foocc,
                  arguments = [out.path] + [src.path for src in srcs_list],
                  inputs = srcs_list,
                  tools = [foocc],
                  outputs = [out])

foo_binary = rule(
    implementation = _foo_binary_impl,
    attrs = {
        "srcs": attr.label_list(allow_files=True),
        "deps": attr.label_list(),
        "_foocc": attr.label(default=Label("//foo:foocc"),
                             allow_files=True, executable=True, cfg="exec")
    },
    outputs = {"out": "%{name}.out"},
)
EOF

}

#### TESTS #############################################################

function test_dirty_file() {
  export DONT_SANITY_CHECK_SERIALIZATION=1
  cat > foo/BUILD <<EOF
load(":foo.bzl", "foo_library", "foo_binary")
load("@rules_python//python:py_binary.bzl", "py_binary")

py_binary(
    name = "foocc",
    srcs = ["foocc.py"],
)

foo_library(
    name = "a",
    srcs = ["1.a"],
)

foo_library(
    name = "b",
    srcs = ["1.b"],
    deps = [":a"],
)
foo_binary(
    name = "c",
    srcs = ["c.foo"],
    deps = [":b"],
)
EOF
  touch foo/1.a foo/1.b foo/c.foo

  bazel build //foo:c &> "$TEST_log" || fail "build failed"
  # Deliberately breaking the file.
  echo omgomgomg >> foo/foocc.py
  bazel build //foo:c &> "$TEST_log" && fail "Expected failure"

  true  # reset the last exit code so the test won't be considered failed
}

# Regression test for b/154716911.
function test_missing_file() {
  export DONT_SANITY_CHECK_SERIALIZATION=1
  cat > foo/BUILD <<EOF
genrule(
    name = "foo",
    outs = ["file.o"],
    cmd = ("touch $@"),
    tools = [":bar"],
)

cc_binary(
    name = "bar",
    srcs = [
        "bar.cc",
        "missing.a",
    ],
)
EOF
  touch foo/bar.cc

  bazel build //foo:foo &> "$TEST_log" && fail "Expected failure"

  exit_code=$?
  [[ $exit_code -eq 1 ]] || fail "Unexpected exit code: $exit_code"

  true  # reset the last exit code so the test won't be considered failed
}

# Regression test for b/155850727.
function test_incremental_err_reporting() {
  export DONT_SANITY_CHECK_SERIALIZATION=1
  cat > foo/BUILD <<EOF
load("@rules_java//java:java_library.bzl", "java_library")
genrule(
    name = "foo",
    outs = ["file.o"],
    cmd = ("touch $@"),
    tools = [":bar"],
)

java_library(
    name = "bar",
    srcs = [
        "bar.java",
    ],
)
EOF
  echo "randomstuffs" > foo/bar.java

  bazel build //foo:foo &> "$TEST_log" && fail "Expected failure"

  # Verify that the incremental run prints the expected failure message.
  bazel build //foo:foo &> "$TEST_log" && fail "Expected failure"


  expect_log "ERROR"
  expect_log "randomstuffs"
}

run_suite "Integration tests of ${PRODUCT_NAME} with NestedSet as SkyKey."
