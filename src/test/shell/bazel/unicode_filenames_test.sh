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
#
# Verify handling of Unicode filenames.

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

if $is_windows; then
  export LC_ALL=C.utf8
elif [[ "$(uname -s)" == "Linux" ]]; then
  export LC_ALL=C.UTF-8
else
  export LC_ALL=en_US.UTF-8
fi

#### SETUP #############################################################

function unicode_filenames_test_setup() {
  touch WORKSPACE
  mkdir -p pkg/srcs

  cat >pkg/BUILD <<'EOF'
load(":rules.bzl", "ls_srcs")
ls_srcs(
  name = "ls_srcs",
  srcs = glob(["srcs/**/*"]),
)
filegroup(name = "filegroup", srcs = glob(["srcs/**/*"]))
EOF

  cat >pkg/rules.bzl <<'EOF'
def _ls_srcs(ctx):
  out = ctx.actions.declare_file(ctx.attr.name)
  ctx.actions.run_shell(
    inputs = ctx.files.srcs,
    outputs = [out],
    command = 'find -L . > "$1"',
    arguments = [out.path],
  )
  return DefaultInfo(
    files = depset(direct = [out]),
  )

ls_srcs = rule(
  _ls_srcs,
  attrs = {"srcs": attr.label_list(allow_files = True)},
)
EOF
}

function has_iso_8859_1_locale() {
  charmap="$(LC_ALL=en_US.ISO-8859-1 locale charmap 2>/dev/null)"
  [[ "${charmap}" == "ISO-8859-1" ]]
}

function test_utf8_source_artifact() {
  unicode_filenames_test_setup

  touch 'pkg/srcs/regular file.txt'

  mkdir pkg/srcs/subdir
  touch 'pkg/srcs/subdir/file.txt'

  # >>> u"pkg/srcs/ünïcödë fïlë.txt".encode("utf8")
  # 'pkg/srcs/\xc3\xbcn\xc3\xafc\xc3\xb6d\xc3\xab f\xc3\xafl\xc3\xab.txt'
  touch "$(printf '%b' 'pkg/srcs/\xc3\xbcn\xc3\xafc\xc3\xb6d\xc3\xab f\xc3\xafl\xc3\xab.txt')"

  bazel build //pkg:ls_srcs >$TEST_log 2>&1 || fail "Should build"

  assert_contains "pkg/srcs/regular file.txt" bazel-bin/pkg/ls_srcs
  assert_contains "pkg/srcs/subdir/file.txt" bazel-bin/pkg/ls_srcs
  assert_contains "pkg/srcs/ünïcödë fïlë.txt" bazel-bin/pkg/ls_srcs
}

function test_traditional_encoding_source_artifact() {
  # Windows and macOS require filesystem paths to be valid Unicode. Linux and
  # the traditional BSDs typically don't, so their paths can contain arbitrary
  # non-NUL bytes.
  case "$(uname -s | tr [:upper:] [:lower:])" in
  linux|freebsd)
    ;;
  *)
    echo "Skipping test." && return
    ;;
  esac

  # Bazel relies on the JVM for filename encoding, and can only support
  # traditional encodings if it can roundtrip through ISO-8859-1.
  if ! has_iso_8859_1_locale; then
    echo "Skipping test (no ISO-8859-1 locale)."
    echo "Available locales (need en_US.ISO-8859-1):"
    locale -a
    return
  fi

  unicode_filenames_test_setup

  # >>> u"pkg/srcs/TRADITIONAL ünïcödë fïlë.txt".encode("iso-8859-1")
  # 'pkg/srcs/TRADITIONAL \xfcn\xefc\xf6d\xeb f\xefl\xeb.txt'
  touch "$(printf '%b' 'pkg/srcs/TRADITIONAL \xfcn\xefc\xf6d\xeb f\xefl\xeb.txt')"

  bazel build //pkg:ls_srcs >$TEST_log 2>&1 || fail "Should build"
  assert_contains "pkg/srcs/TRADITIONAL " bazel-bin/pkg/ls_srcs
}

function test_utf8_source_artifact_in_bep() {
  unicode_filenames_test_setup

  touch 'pkg/srcs/regular file.txt'

  mkdir pkg/srcs/subdir
  touch 'pkg/srcs/subdir/file.txt'

  # >>> u"pkg/srcs/ünïcödë fïlë.txt".encode("utf8")
  # 'pkg/srcs/\xc3\xbcn\xc3\xafc\xc3\xb6d\xc3\xab f\xc3\xafl\xc3\xab.txt'
  touch "$(printf '%b' 'pkg/srcs/\xc3\xbcn\xc3\xafc\xc3\xb6d\xc3\xab f\xc3\xafl\xc3\xab.txt')"

  bazel build --build_event_json_file="$TEST_log" \
      //pkg:filegroup 2>&1 || fail "Should build"

  expect_log '"name":"pkg/srcs/regular file.txt"'
  expect_log '"name":"pkg/srcs/subdir/file.txt"'
  expect_log '"name":"pkg/srcs/ünïcödë fïlë.txt"'
}

function test_utf8_filename_in_java_test() {
  touch WORKSPACE
  mkdir pkg

  cat >pkg/BUILD <<'EOF'
java_test(
    name = "Test",
    srcs = ["Test.java"],
    main_class = "Test",
    use_testrunner = False,
)
EOF

  cat >pkg/Test.java <<'EOF'
import java.nio.file.Files;
import java.io.IOException;

class Test {
    public static void main(String[] args) throws IOException {
        Files.createTempFile("æøå", null);
    }
}
EOF

  bazel test //pkg:Test --test_output=errors 2>$TEST_log || fail "Test should pass"
}

function test_cc_dependency_with_utf8_filename() {
  # TODO: Find a way to get cl.exe to output Unicode when not running in a
  # console or migrate to /sourceDependencies.
  if $is_windows; then
    echo "Skipping test on Windows." && return
  fi

  local unicode="äöüÄÖÜß🌱"

  setup_module_dot_bazel

  mkdir pkg
  cat >pkg/BUILD <<EOF
cc_library(
    name = "lib",
    hdrs = ["${unicode}.h"],
)
cc_binary(
    name = "bin",
    srcs = ["bin.cc"],
    deps = [":lib"],
)
EOF
  cat >"pkg/${unicode}.h" <<EOF
#define MY_STRING "original"
EOF
  # MSVC requires UTF-8 source files to start with a BOM.
  printf '\xEF\xBB\xBF' > pkg/bin.cc
  cat >>pkg/bin.cc <<EOF
#include "${unicode}.h"

#include <iostream>

int main() {
  std::cout << MY_STRING << std::endl;
  return 0;
}
EOF
  bazel run //pkg:bin >$TEST_log 2>&1 || fail "Should build"
  expect_log "original"

  # Change the header file and rebuild
  cat >"pkg/${unicode}.h" <<EOF
#define MY_STRING "changed"
EOF
  bazel run //pkg:bin >$TEST_log 2>&1 || fail "Should build"
  expect_log "changed"
}

run_suite "Tests for handling of Unicode filenames"
