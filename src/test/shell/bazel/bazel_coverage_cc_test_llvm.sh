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

COVERAGE_GENERATOR_DIR="$1"; shift
if [[ "${COVERAGE_GENERATOR_DIR}" != "released" ]]; then
  COVERAGE_GENERATOR_DIR="$(rlocation io_bazel/$COVERAGE_GENERATOR_DIR)"
  add_to_bazelrc "build --override_repository=remote_coverage_tools=${COVERAGE_GENERATOR_DIR}"
fi

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

# Returns the path of the code coverage report that was generated by Bazel by
# looking at the current $TEST_log. The method fails if TEST_log does not
# contain any coverage report for a passed test.
function get_coverage_file_path_from_test_log() {
  local ending_part="$(sed -n -e '/PASSED/,$p' "$TEST_log")"

  local coverage_file_path=$(grep -Eo "/[/a-zA-Z0-9\.\_\-]+\.dat$" <<< "$ending_part")
  [[ -e "$coverage_file_path" ]] || fail "Coverage output file does not exist!"
  echo "$coverage_file_path"
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
  local -r clang="/usr/bin/clang"
  if [[ ! -x ${clang} ]]; then
    return
  fi
  local -r clang_version=$(clang --version | grep -o "clang version [0-9]*" | cut -d " " -f 3)
  if [ "$clang_version" -lt 9 ] || [ "$clang_version" -eq 10 ] || [ "$clang_version" -eq 11 ]; then
    # No lcov produced with <9.0, no branch coverage with 10.0 and 11.0.
    echo "clang versions <9.0 as well as 10.0 and 11.0 are not supported." && return
  fi

  local -r llvm_profdata="/usr/bin/llvm-profdata"
  if [[ ! -x ${llvm_profdata} ]]; then
    return
  fi

  local -r llvm_cov="/usr/bin/llvm-cov"
  if [[ ! -x ${llvm_cov} ]]; then
    return
  fi

  setup_a_cc_lib_and_t_cc_test

  BAZEL_USE_LLVM_NATIVE_COVERAGE=1 GCOV=$llvm_profdata CC=$clang \
    BAZEL_LLVM_COV=$llvm_cov bazel coverage --experimental_generate_llvm_lcov \
      --test_output=all //:t &>$TEST_log || fail "Coverage for //:t failed"

  local expected_result="SF:a.cc
FN:3,_Z1ab
FNDA:1,_Z1ab
FNF:1
FNH:1
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

  assert_equals "$expected_result" "$(cat $(get_coverage_file_path_from_test_log))"
}

function test_cc_test_llvm_coverage_produces_lcov_report_with_cpp_toolchain() {
  local -r clang="/usr/bin/clang"
  if [[ ! -x ${clang} ]]; then
    return
  fi
  local -r clang_version=$(clang --version | grep -o "clang version [0-9]*" | cut -d " " -f 3)
  if [ "$clang_version" -lt 9 ] || [ "$clang_version" -eq 10 ] || [ "$clang_version" -eq 11 ]; then
    # No lcov produced with <9.0, no branch coverage with 10.0 and 11.0.
    echo "clang versions <9.0 as well as 10.0 and 11.0 are not supported." && return
  fi

  local -r llvm_profdata="/usr/bin/llvm-profdata"
  if [[ ! -x ${llvm_profdata} ]]; then
    return
  fi

  local -r llvm_cov="/usr/bin/llvm-cov"
  if [[ ! -x ${llvm_cov} ]]; then
    return
  fi

  setup_a_cc_lib_and_t_cc_test

  BAZEL_USE_LLVM_NATIVE_COVERAGE=1 BAZEL_USE_CPP_ONLY_TOOLCHAIN=1 GCOV=$llvm_profdata CC=$clang \
    BAZEL_LLVM_COV=$llvm_cov bazel coverage --experimental_generate_llvm_lcov \
      --test_output=all //:t &>$TEST_log || fail "Coverage for //:t failed"

  local expected_result="SF:a.cc
FN:3,_Z1ab
FNDA:1,_Z1ab
FNF:1
FNH:1
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

  assert_equals "$expected_result" "$(cat $(get_coverage_file_path_from_test_log))"
}

function test_cc_test_with_runtime_objects_not_in_runfiles() {
  local -r clang="/usr/bin/clang"
  if [[ ! -x ${clang} ]]; then
    return
  fi
  local -r clang_version=$(clang --version | grep -o "clang version [0-9]*" | cut -d " " -f 3)
  if [ "$clang_version" -lt 9 ] || [ "$clang_version" -eq 10 ] || [ "$clang_version" -eq 11 ]; then
    # No lcov produced with <9.0, no branch coverage with 10.0 and 11.0.
    echo "clang versions <9.0 as well as 10.0 and 11.0 are not supported." && return
  fi

  local -r llvm_profdata="/usr/bin/llvm-profdata"
  if [[ ! -x ${llvm_profdata} ]]; then
    return
  fi

  local -r llvm_cov="/usr/bin/llvm-cov"
  if [[ ! -x ${llvm_cov} ]]; then
    return
  fi

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


  BAZEL_USE_LLVM_NATIVE_COVERAGE=1 GCOV=$llvm_profdata CC=$clang \
    BAZEL_LLVM_COV=$llvm_cov bazel coverage --experimental_generate_llvm_lcov \
      --test_output=all --instrument_test_targets \
        //:main &>$TEST_log || fail "Coverage for //:main failed"

  local expected_result="SF:main.cpp
FN:4,main
FNDA:1,main
FNF:1
FNH:1
DA:4,1
DA:5,1
DA:6,1
DA:7,1
DA:8,1
LH:5
LF:5
end_of_record"

  assert_equals "$expected_result" "$(cat $(get_coverage_file_path_from_test_log))"
}

function setup_external_cc_target() {
  cat > WORKSPACE <<'EOF'
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
  touch other_repo/WORKSPACE

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
  local -r clang="/usr/bin/clang"
  if [[ ! -x ${clang} ]]; then
    return
  fi
  local -r clang_version=$(clang --version | grep -o "clang version [0-9]*" | cut -d " " -f 3)
  if [ "$clang_version" -lt 9 ] || [ "$clang_version" -eq 10 ] || [ "$clang_version" -eq 11 ]; then
    # No lcov produced with <9.0, no branch coverage with 10.0 and 11.0.
    echo "clang versions <9.0 as well as 10.0 and 11.0 are not supported." && return
  fi

  local -r llvm_profdata="/usr/bin/llvm-profdata"
  if [[ ! -x ${llvm_profdata} ]]; then
    return
  fi

  local -r llvm_cov="/usr/bin/llvm-cov"
  if [[ ! -x ${llvm_cov} ]]; then
    return
  fi

  setup_external_cc_target

  BAZEL_USE_LLVM_NATIVE_COVERAGE=1 GCOV=$llvm_profdata CC=$clang \
    BAZEL_LLVM_COV=$llvm_cov bazel coverage --experimental_generate_llvm_lcov \
      --combined_report=lcov --test_output=all \
        @other_repo//:t --instrumentation_filter=// &>$TEST_log || fail "Coverage for @other_repo//:t failed"

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
end_of_record
SF:external/other_repo/a.cc
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

  assert_equals "$expected_result" "$(cat $(get_coverage_file_path_from_test_log))"
  assert_equals "$expected_result" "$(cat bazel-out/_coverage/_coverage_report.dat)"
}

function test_external_cc_target_coverage_not_collected_by_default() {
  local -r clang="/usr/bin/clang"
  if [[ ! -x ${clang} ]]; then
    return
  fi
  local -r clang_version=$(clang --version | grep -o "clang version [0-9]*" | cut -d " " -f 3)
  if [ "$clang_version" -lt 9 ] || [ "$clang_version" -eq 10 ] || [ "$clang_version" -eq 11 ]; then
    # No lcov produced with <9.0, no branch coverage with 10.0 and 11.0.
    echo "clang versions <9.0 as well as 10.0 and 11.0 are not supported." && return
  fi

  local -r llvm_profdata="/usr/bin/llvm-profdata"
  if [[ ! -x ${llvm_profdata} ]]; then
    return
  fi

  local -r llvm_cov="/usr/bin/llvm-cov"
  if [[ ! -x ${llvm_cov} ]]; then
    return
  fi

  setup_external_cc_target

  BAZEL_USE_LLVM_NATIVE_COVERAGE=1 GCOV=$llvm_profdata CC=$clang \
    BAZEL_LLVM_COV=$llvm_cov bazel coverage --experimental_generate_llvm_lcov \
      --combined_report=lcov --test_output=all \
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

  assert_equals "$expected_result" "$(cat $(get_coverage_file_path_from_test_log))"
  assert_equals "$expected_result" "$(cat bazel-out/_coverage/_coverage_report.dat)"
}

run_suite "test tests"