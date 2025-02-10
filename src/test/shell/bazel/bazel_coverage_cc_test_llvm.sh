#!/bin/bash
#
# Copyright 2015 The Bazel Authors. All rights reserved.
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

set -eu

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }
source "${CURRENT_DIR}/coverage_helpers.sh" \
  || { echo "coverage_helpers.sh not found!" >&2; exit 1; }

COVERAGE_GENERATOR_DIR="$1"; shift
if [[ "${COVERAGE_GENERATOR_DIR}" != "released" ]]; then
  COVERAGE_GENERATOR_DIR="$(rlocation io_bazel/$COVERAGE_GENERATOR_DIR)"
  add_to_bazelrc "build --override_repository=remote_coverage_tools=${COVERAGE_GENERATOR_DIR}"
fi

# Configures Bazel to emit coverage using LLVM tools, returning a non-zero exit
# code if the tools are not available.
function setup_llvm_coverage_tools_for_lcov() {
  local -r clang=$(which clang || true)
  if [[ ! -x "${clang}" ]]; then
    echo "clang not installed. Skipping test."
    return 1
  fi
  local -r clang_version=$(clang --version | grep -o "clang version [0-9]*" | cut -d " " -f 3)
  if [ "$clang_version" -lt 9 ];  then
    # No lcov produced with <9.0.
    echo "clang versions <9.0 are not supported, got $clang_version. Skipping test."
    return 1
  fi

  local -r llvm_profdata=$(which llvm-profdata || true)
  if [[ ! -x "${llvm_profdata}" ]]; then
    echo "llvm-profdata not installed. Skipping test."
    return 1
  fi

  local -r llvm_cov=$(which llvm-cov || true)
  if [[ ! -x "${llvm_cov}" ]]; then
    echo "llvm-cov not installed. Skipping test."
    return 1
  fi

  add_to_bazelrc "common --repo_env=BAZEL_LLVM_COV=${llvm_cov}"
  add_to_bazelrc "common --repo_env=BAZEL_LLVM_PROFDATA=${llvm_profdata}"
  add_to_bazelrc "common --repo_env=BAZEL_USE_LLVM_NATIVE_COVERAGE=1"
  add_to_bazelrc "common --repo_env=CC=${clang}"
  add_to_bazelrc "common --repo_env=GCOV=${llvm_profdata}"
  add_to_bazelrc "common --experimental_generate_llvm_lcov"
}

# Writes the C++ source files and a corresponding BUILD file for which to
# collect code coverage. The sources are a.cc, a.h and t.cc.
function setup_a_cc_lib_and_t_cc_test() {
  cat << EOF > BUILD
cc_library(
    name = "a",
    srcs = ["a.cc"],
    hdrs = ["a.h"],
)

cc_test(
    name = "t",
    srcs = ["t.cc"],
    deps = [":a"],
)
EOF

  cat << EOF > a.h
int a(bool what);
EOF

  cat << EOF > a.cc
#include "a.h"

int a(bool what) {
  if (what) {
    return 1;
  } else {
    return 2;
  }
}
EOF

  cat << EOF > t.cc
#include <stdio.h>
#include "a.h"

int main(void) {
  a(true);
}
EOF
}

function test_cc_test_llvm_coverage_doesnt_fail() {
  local -r llvmprofdata=$(which llvm-profdata)
  if [[ ! -x ${llvmprofdata:-/usr/bin/llvm-profdata} ]]; then
    echo "llvm-profdata not installed. Skipping test."
    return
  fi

  local -r clang_tool=$(which clang++)
  if [[ ! -x ${clang_tool:-/usr/bin/clang_tool} ]]; then
    echo "clang++ not installed. Skipping test."
    return
  fi

  setup_a_cc_lib_and_t_cc_test

  # Only test that bazel coverage doesn't crash when invoked for llvm native
  # coverage.
  BAZEL_USE_LLVM_NATIVE_COVERAGE=1 GCOV=$llvmprofdata CC=$clang_tool \
      bazel coverage --test_output=all //:t &>$TEST_log \
      || fail "Coverage for //:t failed"

  # Check to see if the coverage output file was created. Cannot check its
  # contents because it's a binary.
  [ -f "$(get_coverage_file_path_from_test_log)" ] \
      || fail "Coverage output file was not created."
}

function test_cc_test_llvm_coverage_produces_lcov_report() {
  setup_llvm_coverage_tools_for_lcov || return 0
  setup_a_cc_lib_and_t_cc_test

  # Ensure that coverage succeeds even with lazily built runfiles trees for the
  # merger tool.
  bazel coverage --nobuild_runfile_links \
      --test_output=all //:t &>$TEST_log || fail "Coverage for //:t failed"

  local expected_result="SF:a.cc
FN:3,_Z1ab
FNDA:1,_Z1ab
FNF:1
FNH:1
BRDA:4,0,0,1
BRDA:4,0,1,0
BRF:2
BRH:1
DA:3,1
DA:4,1
DA:5,1
DA:6,1
DA:7,0
DA:8,0
DA:9,1
LH:5
LF:7
end_of_record"

  assert_llvm_cc_coverage_result "$expected_result" "$(get_coverage_file_path_from_test_log)"
}

function test_cc_test_llvm_coverage_produces_lcov_report_with_split_postprocessing() {
  setup_llvm_coverage_tools_for_lcov || return 0
  setup_a_cc_lib_and_t_cc_test

  # Ensure that coverage succeeds even with lazily built runfiles trees for the
  # merger tool.
  bazel coverage \
    --nobuild_runfile_links \
    --experimental_split_coverage_postprocessing --experimental_fetch_all_coverage_outputs \
      --test_env=VERBOSE_COVERAGE=1 --test_output=all //:t &>$TEST_log || fail "Coverage for //:t failed"

  local expected_result="SF:a.cc
FN:3,_Z1ab
FNDA:1,_Z1ab
FNF:1
FNH:1
BRDA:4,0,0,1
BRDA:4,0,1,0
BRF:2
BRH:1
DA:3,1
DA:4,1
DA:5,1
DA:6,1
DA:7,0
DA:8,0
DA:9,1
LH:5
LF:7
end_of_record"

  assert_llvm_cc_coverage_result "$expected_result" "$(get_coverage_file_path_from_test_log)"
}

function test_cc_test_with_runtime_objects_not_in_runfiles() {
  setup_llvm_coverage_tools_for_lcov || return 0

  cat << EOF > BUILD
cc_test(
    name = "main",
    srcs = ["main.cpp"],
    data = [":jar"],
)

java_binary(
    name = "jar",
    resources = [":shared_lib"],
    create_executable = False,
)

cc_binary(
    name = "shared_lib",
    linkshared = True,
)
EOF

  cat << EOF > main.cpp
#include <iostream>

int main(int argc, char const *argv[])
{
  if (argc < 5) {
    std::cout << "Hello World!" << std::endl;
  }
}
EOF


  bazel coverage --test_output=all --instrument_test_targets \
      //:main &>$TEST_log || fail "Coverage for //:main failed"

  local expected_result="SF:main.cpp
FN:4,main
FNDA:1,main
FNF:1
FNH:1
BRDA:5,0,0,1
BRDA:5,0,1,0
BRF:2
BRH:1
DA:4,1
DA:5,1
DA:6,1
DA:7,1
DA:8,1
LH:5
LF:5
end_of_record"

  assert_llvm_cc_coverage_result "$expected_result" "$(get_coverage_file_path_from_test_log)"
}

function setup_external_cc_target() {
  cat > MODULE.bazel <<'EOF'
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "other_repo",
    path = "other_repo",
)
EOF

  cat > BUILD <<'EOF'
cc_library(
    name = "b",
    srcs = ["b.cc"],
    hdrs = ["b.h"],
    visibility = ["//visibility:public"],
)
EOF

  cat > b.h <<'EOF'
int b(bool what);
EOF

  cat > b.cc <<'EOF'
int b(bool what) {
  if (what) {
    return 1;
  } else {
    return 2;
  }
}
EOF

  mkdir -p other_repo
  touch other_repo/REPO.bazel

  cat > other_repo/BUILD <<'EOF'
cc_library(
    name = "a",
    srcs = ["a.cc"],
    hdrs = ["a.h"],
    deps = ["@//:b"],
)

cc_test(
    name = "t",
    srcs = ["t.cc"],
    linkstatic = True,
    deps = [":a"],
)
EOF

  cat > other_repo/a.h <<'EOF'
int a(bool what);
EOF

  cat > other_repo/a.cc <<'EOF'
#include "a.h"
#include "b.h"

int a(bool what) {
  if (what) {
    return b(what);
  } else {
    return 1 + b(what);
  }
}
EOF

  cat > other_repo/t.cc <<'EOF'
#include <stdio.h>
#include "a.h"

int main(void) {
  a(true);
}
EOF
}

function test_external_cc_target_can_collect_coverage() {
  setup_llvm_coverage_tools_for_lcov || return 0
  setup_external_cc_target

  bazel coverage --combined_report=lcov --test_output=all \
    @other_repo//:t --instrumentation_filter=// &>$TEST_log || fail "Coverage for @other_repo//:t failed"

  local expected_b_cc='SF:b.cc
FN:1,_Z1bb
FNDA:1,_Z1bb
FNF:1
FNH:1
BRDA:2,0,0,1
BRDA:2,0,1,0
BRF:2
BRH:1
DA:1,1
DA:2,1
DA:3,1
DA:4,1
DA:5,0
DA:6,0
DA:7,1
LH:5
LF:7
end_of_record'

  local expected_a_cc='SF:external/+_repo_rules+other_repo/a.cc
FN:4,_Z1ab
FNDA:1,_Z1ab
FNF:1
FNH:1
BRDA:5,0,0,1
BRDA:5,0,1,0
BRF:2
BRH:1
DA:4,1
DA:5,1
DA:6,1
DA:7,1
DA:8,0
DA:9,0
DA:10,1
LH:5
LF:7
end_of_record'

  assert_llvm_cc_coverage_result "$expected_b_cc" "$(get_coverage_file_path_from_test_log)"
  assert_llvm_cc_coverage_result "$expected_a_cc" "$(get_coverage_file_path_from_test_log)"
  assert_llvm_cc_coverage_result "$expected_b_cc" "bazel-out/_coverage/_coverage_report.dat"
  assert_llvm_cc_coverage_result "$expected_a_cc" "bazel-out/_coverage/_coverage_report.dat"
}

function test_external_cc_target_coverage_not_collected_by_default() {
  setup_llvm_coverage_tools_for_lcov || return 0
  setup_external_cc_target

  bazel coverage --combined_report=lcov --test_output=all \
    @other_repo//:t &>$TEST_log || fail "Coverage for @other_repo//:t failed"

  local expected_result='SF:b.cc
FN:1,_Z1bb
FNDA:1,_Z1bb
FNF:1
FNH:1
BRDA:2,0,0,1
BRDA:2,0,1,0
BRF:2
BRH:1
DA:1,1
DA:2,1
DA:3,1
DA:4,1
DA:5,0
DA:6,0
DA:7,1
LH:5
LF:7
end_of_record'

  assert_llvm_cc_coverage_result "$expected_result" "$(get_coverage_file_path_from_test_log)"
  assert_llvm_cc_coverage_result "$expected_result" "bazel-out/_coverage/_coverage_report.dat"
}

function test_coverage_with_tmp_in_path() {
  setup_llvm_coverage_tools_for_lcov || return 0

  mkdir -p foo/tmp
  cat > foo/tmp/BUILD <<'EOF'
cc_library(
    name = "a",
    srcs = ["a.cc"],
    hdrs = ["a.h"],
)

cc_test(
    name = "t",
    srcs = ["t.cc"],
    linkstatic = True,
    deps = [":a"],
)
EOF

  cat > foo/tmp/a.h <<'EOF'
int a(bool what);
EOF

  cat > foo/tmp/a.cc <<'EOF'
#include "a.h"

int a(bool what) {
  if (what) {
    return 2;
  } else {
    return 1;
  }
}
EOF

  cat > foo/tmp/t.cc <<'EOF'
#include <stdio.h>
#include "a.h"

int main(void) {
  a(true);
}
EOF

  bazel coverage --combined_report=lcov --test_output=all \
    //foo/tmp:t --instrumentation_filter=// &>$TEST_log || fail "Coverage failed"

  local expected_result='SF:foo/tmp/a.cc
FN:3,_Z1ab
FNDA:1,_Z1ab
FNF:1
FNH:1
BRDA:4,0,0,1
BRDA:4,0,1,0
BRF:2
BRH:1
DA:3,1
DA:4,1
DA:5,1
DA:6,1
DA:7,0
DA:8,0
DA:9,1
LH:5
LF:7
end_of_record'

  assert_llvm_cc_coverage_result "$expected_result" "$(get_coverage_file_path_from_test_log)"
  assert_llvm_cc_coverage_result "$expected_result" "bazel-out/_coverage/_coverage_report.dat"
}

function test_coverage_for_header() {
  setup_llvm_coverage_tools_for_lcov || return 0

  cat << EOF > BUILD
cc_library(
  name = "foo",
  srcs = ["foo.cc"],
  hdrs = ["foo.h"],
)

cc_test(
  name = "foo_test",
  srcs = ["foo_test.cc"],
  deps = [":foo"],
)
EOF

cat << EOF > foo.h
template<typename T>
T fooify(T x) {
  if (x < 0) {
    return -1 * x;
  }
  return x + x*x;
}

int calc_foo(int x);
EOF

cat << EOF > foo.cc
#include "foo.h"

int calc_foo(int x) {
  return fooify<int>(x);
}
EOF

cat << EOF > foo_test.cc

#include "foo.h"

int main() {
  int f = calc_foo(4);
  return f == 20 ? 0 : 1;
}
EOF

  local expected_foo_h="SF:foo.h
FN:2,_Z6fooifyIiET_S0_
FNDA:1,_Z6fooifyIiET_S0_
FNF:1
FNH:1
BRDA:3,0,0,0
BRDA:3,0,1,1
BRF:2
BRH:1
DA:2,1
DA:3,1
DA:4,0
DA:5,0
DA:6,1
DA:7,1
LH:4
LF:6
end_of_record"

local expected_foo_cc="SF:foo.cc
FN:3,_Z8calc_fooi
FNDA:1,_Z8calc_fooi
FNF:1
FNH:1
DA:3,1
DA:4,1
DA:5,1
LH:3
LF:3
end_of_record"

  bazel coverage --nobuild_runfile_links --test_output=all //:foo_test \
    &>$TEST_log || fail "Coverage for //:foo_test failed"

  cov_file="$(get_coverage_file_path_from_test_log)"
  assert_llvm_cc_coverage_result "$expected_foo_h" "$cov_file"
  assert_llvm_cc_coverage_result "$expected_foo_cc" "$cov_file"
}

run_suite "test tests"
