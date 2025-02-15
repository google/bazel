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


function test_cc_test_coverage_gcov() {
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi
  setup_a_cc_lib_and_t_cc_test
  BEP=bep.txt
  bazel coverage  --test_output=all \
     --build_event_text_file="${BEP}" //:t &>"$TEST_log" \
     || fail "Coverage for //:t failed"

  local coverage_file_path="$( get_coverage_file_path_from_test_log )"

  # Check the expected coverage for a.cc in the coverage file.
  local expected_result_a_cc="SF:a.cc
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
DA:7,0
LH:3
LF:4
end_of_record"
  assert_cc_coverage_result "$expected_result_a_cc" "$coverage_file_path"
  # t.cc is not included in the coverage report because test targets are not
  # instrumented by default.
  assert_not_contains "SF:t\.cc" "$coverage_file_path"

  # Verify that this is also true for cached coverage actions.
  bazel coverage  --test_output=all \
      --build_event_text_file="${BEP}" \
      --legacy_important_outputs //:t \
      &>"$TEST_log" || fail "Coverage for //:t failed"
  expect_log '//:t.*cached'
  # Verify the files are reported correctly in the build event protocol.
  assert_contains 'name: "test.lcov"' "${BEP}"
    # Assert coverage output is reported in the right proto fields
  grep 'name: "test.lcov"' -B1 "${BEP}" | grep -q 'test_action_output'\
    || fail "File 'test.lcov' missing in test_action_output!"
}

# TODO(#6254): Enable this test when #6254 is fixed.
function test_cc_test_coverage_gcov_virtual_includes() {
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

 ########### Setup source files and BUILD file ###########
  mkdir -p examples/cpp
 cat << EOF > examples/cpp/BUILD
cc_library(
    name = "a_header",
    hdrs = ["foo/bar/baz/a_header.h"],
    strip_include_prefix = "foo",
    include_prefix = "yin/yang",
)

cc_library(
    name = "num-lib",
    srcs = ["num-lib.cc"],
    hdrs = ["num-lib.h"],
    deps = [":a_header"],
)

cc_test(
    name = "num-world_test",
    srcs = ["num-world.cc"],
    deps = [":num-lib"],
)
EOF
  mkdir -p examples/cpp/foo/bar/baz
  cat << EOF > examples/cpp/foo/bar/baz/a_header.h
class A {
public:
	int num_whatever() const {
		return 42;
	}
};
EOF

  cat << EOF > examples/cpp/num-lib.h
#ifndef EXAMPLES_CPP_NUM_LIB_H_
#define EXAMPLES_CPP_NUM_LIB_H_

#include "yin/yang/bar/baz/a_header.h"

namespace num {

class NumLib {
 public:
  explicit NumLib(int n);

  int add_number(int value);

  inline int add_number_inlined(int value) {
    return number + value;
  }
 private:
  int number;
};
}  // namespace number
#endif  // EXAMPLES_CPP_NUM_LIB_H_
EOF

  cat << EOF > examples/cpp/num-lib.cc
#include "examples/cpp/num-lib.h"

namespace num {

NumLib::NumLib(int n) : number(n) {
}

int NumLib::add_number(int value) {
  A* a = new A();
  return number + value + a->num_whatever();
}
}  // namespace num
EOF

  cat << EOF > examples/cpp/num-world.cc
#include "examples/cpp/num-lib.h"

using num::NumLib;

int main(int argc, char** argv) {
  NumLib lib(17);
  int value = 5;
  if (argc > 1) {
    ++value;
  }
  lib.add_number(value);
  lib.add_number_inlined(value);
  return 0;
}
EOF

  ########### Run bazel coverage ###########
  bazel coverage  --test_output=all //examples/cpp:num-world_test &>"$TEST_log" \
     || fail "Coverage for //examples/cpp:num-world_test failed"

  ########### Assert coverage results. ###########
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"
  local expected_result_num_lib="SF:examples/cpp/num-lib.cc
FN:8,_ZN3num6NumLib10add_numberEi
FN:5,_ZN3num6NumLibC2Ei
FNDA:1,_ZN3num6NumLib10add_numberEi
FNDA:1,_ZN3num6NumLibC2Ei
FNF:2
FNH:2
DA:5,1
DA:6,1
DA:8,1
DA:9,1
DA:10,1
LH:5
LF:5
end_of_record"
  assert_cc_coverage_result "$expected_result_num_lib" "$coverage_file_path"

  local expected_result_a_header="SF:examples/cpp/foo/bar/baz/a_header.h
FN:3,_ZNK1A12num_whateverEv
FNDA:1,_ZNK1A12num_whateverEv
FNF:1
FNH:1
DA:3,1
DA:4,1
LH:2
LF:2
end_of_record"
  assert_cc_coverage_result "$expected_result_a_header" "$coverage_file_path"

  local coverage_result_num_lib_header="SF:examples/cpp/num-lib.h
FN:14,_ZN3num6NumLib18add_number_inlinedEi
FNDA:1,_ZN3num6NumLib18add_number_inlinedEi
FNF:1
FNH:1
DA:14,1
DA:15,1
LH:2
LF:2
end_of_record"
  assert_cc_coverage_result "$coverage_result_num_lib_header" "$coverage_file_path"
}

function test_cc_test_gcov_multiple_headers() {
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  ############## Setting up the test sources and BUILD file ##############
  mkdir -p "coverage_srcs/"
  cat << EOF > BUILD
cc_library(
  name = "a",
  srcs = ["coverage_srcs/a.cc"],
  hdrs = ["coverage_srcs/a.h", "coverage_srcs/b.h"]
)

cc_test(
  name = "t",
  srcs = ["coverage_srcs/t.cc"],
  deps = [":a"]
)
EOF
  cat << EOF > "coverage_srcs/a.h"
int a(bool what);
EOF

  cat << EOF > "coverage_srcs/a.cc"
#include "a.h"
#include "b.h"

int a(bool what) {
  if (what) {
    return b(1);
  } else {
    return b(-1);
  }
}
EOF

  cat << EOF > "coverage_srcs/b.h"
int b(int what) {
  if (what > 0) {
    return 1;
  } else {
    return 2;
  }
}
EOF

  cat << EOF > "coverage_srcs/t.cc"
#include "a.h"

int main(void) {
  a(true);
  return 0;
}
EOF

  ############## Running bazel coverage ##############
  bazel coverage  --test_output=all //:t \
      &>"$TEST_log" || fail "Coverage for //:t failed"

  ##### Putting together the expected coverage results #####
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"
  local expected_result_a_cc="SF:coverage_srcs/a.cc
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
DA:8,0
LH:3
LF:4
end_of_record"
  local expected_result_b_h="SF:coverage_srcs/b.h
FN:1,_Z1bi
FNDA:1,_Z1bi
FNF:1
FNH:1
BRDA:2,0,0,1
BRDA:2,0,1,0
BRF:2
BRH:1
DA:1,1
DA:2,1
DA:3,1
DA:5,0
LH:3
LF:4
end_of_record"
  local expected_result_t_cc="SF:coverage_srcs/t.cc"

  ############## Asserting the coverage results ##############
  assert_cc_coverage_result "$expected_result_a_cc" "$coverage_file_path"
  assert_cc_coverage_result "$expected_result_b_h" "$coverage_file_path"
  # coverage_srcs/t.cc is not included in the coverage report because the test
  # targets are not instrumented by default.
  assert_not_contains "SF:coverage_srcs/t\.cc" "$coverage_file_path"
}

function test_cc_test_gcov_multiple_headers_instrument_test_target() {
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  ############## Setting up the test sources and BUILD file ##############
  mkdir -p "coverage_srcs/"
  cat << EOF > BUILD
cc_library(
  name = "a",
  srcs = ["coverage_srcs/a.cc"],
  hdrs = ["coverage_srcs/a.h", "coverage_srcs/b.h"]
)

cc_test(
  name = "t",
  srcs = ["coverage_srcs/t.cc"],
  deps = [":a"]
)
EOF
  cat << EOF > "coverage_srcs/a.h"
int a(bool what);
EOF

  cat << EOF > "coverage_srcs/a.cc"
#include "a.h"
#include "b.h"

int a(bool what) {
  if (what) {
    return b(1);
  } else {
    return b(-1);
  }
}
EOF

  cat << EOF > "coverage_srcs/b.h"
int b(int what) {
  if (what > 0) {
    return 1;
  } else {
    return 2;
  }
}
EOF

  cat << EOF > "coverage_srcs/t.cc"
#include <iostream>
#include "a.h"

int main(void) {
  a(true);
  std::cout << "Using system lib";
  return 0;
}
EOF

  ############## Running bazel coverage ##############
  bazel coverage  --instrument_test_targets \
      --test_output=all //:t &>"$TEST_log" || fail "Coverage for //:t failed"

  ##### Putting together the expected coverage results #####
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"
  local expected_result_a_cc="SF:coverage_srcs/a.cc
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
DA:8,0
LH:3
LF:4
end_of_record"
  local expected_result_b_h="SF:coverage_srcs/b.h
FN:1,_Z1bi
FNDA:1,_Z1bi
FNF:1
FNH:1
BRDA:2,0,0,1
BRDA:2,0,1,0
BRF:2
BRH:1
DA:1,1
DA:2,1
DA:3,1
DA:5,0
LH:3
LF:4
end_of_record"
  local expected_result_t_cc="SF:coverage_srcs/t.cc"

  ############## Asserting the coverage results ##############
  assert_cc_coverage_result "$expected_result_a_cc" "$coverage_file_path"
  assert_cc_coverage_result "$expected_result_b_h" "$coverage_file_path"
  # iostream should not be in the final coverage report because it is a syslib.
  assert_not_contains "iostream" "$coverage_file_path"
  # coverage_srcs/t.cc should be included in the coverage report. We don't check
  # for the full contents of the t.cc report because it might vary from system
  # to system depending on the system headers.
  assert_cc_coverage_result "$expected_result_t_cc" "$coverage_file_path"
}

function test_cc_test_gcov_same_header_different_libs() {
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  ############## Setting up the test sources and BUILD file ##############
  mkdir -p "coverage_srcs/"
  cat << EOF > BUILD
cc_library(
  name = "a",
  srcs = ["coverage_srcs/a.cc"],
  hdrs = ["coverage_srcs/a.h", "coverage_srcs/b.h"]
)

cc_library(
  name = "c",
  srcs = ["coverage_srcs/c.cc"],
  hdrs = ["coverage_srcs/c.h", "coverage_srcs/b.h"]
)

cc_test(
  name = "t",
  srcs = ["coverage_srcs/t.cc"],
  deps = [":a", ":c"]
)
EOF
  cat << EOF > "coverage_srcs/a.h"
int a(bool what);
EOF

  cat << EOF > "coverage_srcs/a.cc"
#include "a.h"
#include "b.h"

int a(bool what) {
  if (what) {
    return b_for_a(1);
  } else {
    return b_for_a(-1);
  }
}
EOF

  cat << EOF > "coverage_srcs/b.h"
// Lines 2-8 are covered by calling b_for_a from a.cc.
int b_for_a(int what) { // Line 2: executed once
  if (what > 0) { // Line 3: executed once
    return 1; // Line 4: executed once
  } else {
    return 2; // Line 6: not executed
  }
}

// Lines 11-17 are covered by calling b_for_a from a.cc.
int b_for_c(int what) { // Line 11: executed once
  if (what > 0) { // Line 12: executed once
    return 1; // Line 13: not executed
  } else {
    return 2; // Line 15: executed once
  }
}
EOF

  cat << EOF > "coverage_srcs/c.h"
int c(bool what);
EOF

  cat << EOF > "coverage_srcs/c.cc"
#include "c.h"
#include "b.h"

int c(bool what) {
  if (what) {
    return b_for_c(1);
  } else {
    return b_for_c(-1);
  }
}
EOF

  cat << EOF > "coverage_srcs/t.cc"
#include "a.h"
#include "c.h"

int main(void) {
  a(true);
  c(false);
}
EOF

  ############## Running bazel coverage ##############
  bazel coverage  --test_output=all //:t \
      &>"$TEST_log" || fail "Coverage for //:t failed"

  ##### Putting together the expected coverage results #####
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"
  local expected_result_a_cc="SF:coverage_srcs/a.cc
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
DA:8,0
LH:3
LF:4
end_of_record"
  local expected_result_b_h="SF:coverage_srcs/b.h
FN:2,_Z7b_for_ai
FN:11,_Z7b_for_ci
FNDA:1,_Z7b_for_ai
FNDA:1,_Z7b_for_ci
FNF:2
FNH:2
BRDA:3,0,0,1
BRDA:3,0,1,0
BRDA:12,0,0,0
BRDA:12,0,1,1
BRF:4
BRH:2
DA:2,1
DA:3,1
DA:4,1
DA:6,0
DA:11,1
DA:12,1
DA:13,0
DA:15,1
LH:6
LF:8
end_of_record"
  local expected_result_c_cc="SF:coverage_srcs/c.cc
FN:4,_Z1cb
FNDA:1,_Z1cb
FNF:1
FNH:1
BRDA:5,0,0,0
BRDA:5,0,1,1
BRF:2
BRH:1
DA:4,1
DA:5,1
DA:6,0
DA:8,1
LH:3
LF:4
end_of_record"

  ############## Asserting the coverage results ##############
  assert_cc_coverage_result "$expected_result_a_cc" "$coverage_file_path"
  assert_cc_coverage_result "$expected_result_b_h" "$coverage_file_path"
  assert_cc_coverage_result "$expected_result_c_cc" "$coverage_file_path"
  # coverage_srcs/t.cc is not included in the coverage report because the test
  # targets are not instrumented by default.
  assert_not_contains "SF:coverage_srcs/t\.cc" "$coverage_file_path"
}

function test_cc_test_gcov_same_header_different_libs_multiple_exec() {
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  ############## Setting up the test sources and BUILD file ##############
  mkdir -p "coverage_srcs/"
  cat << EOF > BUILD
cc_library(
  name = "a",
  srcs = ["coverage_srcs/a.cc"],
  hdrs = ["coverage_srcs/a.h", "coverage_srcs/b.h"]
)

cc_library(
  name = "c",
  srcs = ["coverage_srcs/c.cc"],
  hdrs = ["coverage_srcs/c.h", "coverage_srcs/b.h"]
)

cc_test(
  name = "t",
  srcs = ["coverage_srcs/t.cc"],
  deps = [":a", ":c"]
)
EOF
  cat << EOF > "coverage_srcs/a.h"
int a(bool what);
int a_redirect();
EOF

  cat << EOF > "coverage_srcs/a.cc"
#include "a.h"
#include "b.h"

int a(bool what) {
  if (what) {
    return b_for_a(1);
  } else {
    return b_for_a(-1);
  }
}

int a_redirect() {
  return b_for_all();
}
EOF

  cat << EOF > "coverage_srcs/b.h"
// Lines 2-8 are covered by calling b_for_a from a.cc.
int b_for_a(int what) { // Line 2: executed once
  if (what > 0) { // Line 3: executed once
    return 1; // Line 4: executed once
  } else {
    return 2; // Line 6: not executed
  }
}

// Lines 11-17 are covered by calling b_for_a from a.cc.
int b_for_c(int what) { // Line 11: executed once
  if (what > 0) { // Line 12: executed once
    return 1; // Line 13: not executed
  } else {
    return 2; // Line 15: executed once
  }
}

int b_for_all() { // Line 21: executed 3 times (2x from a.cc and 1x from c.cc)
  return 10; // Line 21: executed 3 times (2x from a.cc and 1x from c.cc)
}
EOF

  cat << EOF > "coverage_srcs/c.h"
int c(bool what);
int c_redirect();
EOF

  cat << EOF > "coverage_srcs/c.cc"
#include "c.h"
#include "b.h"

int c(bool what) {
  if (what) {
    return b_for_c(1);
  } else {
    return b_for_c(-1);
  }
}

int c_redirect() {
  return b_for_all();
}
EOF

  cat << EOF > "coverage_srcs/t.cc"
#include "a.h"
#include "c.h"

int main(void) {
  a(true);
  c(false);
  a_redirect();
  a_redirect();
  c_redirect();
}
EOF

  ############## Running bazel coverage ##############
  bazel coverage  --test_output=all //:t \
      &>"$TEST_log" || fail "Coverage for //:t failed"

  ##### Putting together the expected coverage results #####
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"
  local expected_result_a_cc="SF:coverage_srcs/a.cc
FN:12,_Z10a_redirectv
FN:4,_Z1ab
FNDA:2,_Z10a_redirectv
FNDA:1,_Z1ab
FNF:2
FNH:2
BRDA:5,0,0,1
BRDA:5,0,1,0
BRF:2
BRH:1
DA:4,1
DA:5,1
DA:6,1
DA:8,0
DA:12,2
DA:13,2
LH:5
LF:6
end_of_record"
  local expected_result_b_h="SF:coverage_srcs/b.h
FN:2,_Z7b_for_ai
FN:11,_Z7b_for_ci
FN:19,_Z9b_for_allv
FNDA:1,_Z7b_for_ai
FNDA:1,_Z7b_for_ci
FNDA:3,_Z9b_for_allv
FNF:3
FNH:3
BRDA:3,0,0,1
BRDA:3,0,1,0
BRDA:12,0,0,0
BRDA:12,0,1,1
BRF:4
BRH:2
DA:2,1
DA:3,1
DA:4,1
DA:6,0
DA:11,1
DA:12,1
DA:13,0
DA:15,1
DA:19,3
DA:20,3
LH:8
LF:10
end_of_record"
  local expected_result_c_cc="SF:coverage_srcs/c.cc
FN:12,_Z10c_redirectv
FN:4,_Z1cb
FNDA:1,_Z10c_redirectv
FNDA:1,_Z1cb
FNF:2
FNH:2
BRDA:5,0,0,0
BRDA:5,0,1,1
BRF:2
BRH:1
DA:4,1
DA:5,1
DA:6,0
DA:8,1
DA:12,1
DA:13,1
LH:5
LF:6
end_of_record"

  ############## Asserting the coverage results ##############
  assert_cc_coverage_result "$expected_result_a_cc" "$coverage_file_path"
  assert_cc_coverage_result "$expected_result_b_h" "$coverage_file_path"
  assert_cc_coverage_result "$expected_result_c_cc" "$coverage_file_path"
  # coverage_srcs/t.cc is not included in the coverage report because the test
  # targets are not instrumented by default.
  assert_not_contains "SF:coverage_srcs/t\.cc" "$coverage_file_path"
}

function test_failed_coverage() {
  local -r LCOV=$(which lcov)
  if [[ ! -x ${LCOV:-/usr/bin/lcov} ]]; then
    echo "lcov not installed. Skipping test."
    return
  fi

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
int a();
EOF

  cat << EOF > a.cc
#include "a.h"

int a() {
  return 1;
}
EOF

  cat << EOF > t.cc
#include <stdio.h>
#include "a.h"

int main(void) {
  return a();
}
EOF

  bazel coverage --test_output=all --build_event_text_file=bep.txt //:t \
      &>$TEST_log && fail "Expected test failure" || :

  # Verify that coverage data is still reported.
  assert_contains 'name: "test.lcov"' bep.txt
}

function test_coverage_with_experimental_split_coverage_postprocessing_only() {
  local -r LCOV=$(which lcov)
  if [[ ! -x ${LCOV:-/usr/bin/lcov} ]]; then
    echo "lcov not installed. Skipping test."
    return
  fi

  cat << EOF > BUILD
cc_test(
  name = "hello-test",
  srcs = ["hello-test.cc"],
)
EOF


  cat << EOF > hello-test.cc
int main() {
  return 0;
}
EOF
  bazel coverage --test_output=all --experimental_split_coverage_postprocessing //:hello-test \
                &>$TEST_log && fail "Expected test failure" || :

  assert_contains '--experimental_split_coverage_postprocessing depends on --experimental_fetch_all_coverage_outputs being enabled' $TEST_log
}

function test_coverage_doesnt_fail_on_empty_output() {
    if is_gcov_missing_or_wrong_version; then
        echo "Skipping test." && return
    fi
    mkdir empty_cov
    cat << EOF > empty_cov/t.cc
#include <stdio.h>
 int main(void) {
    return 0;
}
EOF
     cat << EOF > empty_cov/BUILD
cc_test(
    name = "empty-cov-test",
    srcs = ["t.cc"]
)
EOF
     bazel coverage  --test_output=all \
        //empty_cov:empty-cov-test  &>"$TEST_log" \
     || fail "Coverage for //empty_cov:empty-cov-test failed"
     expect_log "WARNING: There was no coverage found."
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
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  setup_external_cc_target

  bazel coverage --test_output=all --instrumentation_filter=// @other_repo//:t \
      &>"$TEST_log" || fail "Coverage for @other_repo//:t failed"

  local coverage_file_path="$(get_coverage_file_path_from_test_log)"
  local expected_result_a_cc='SF:external/+local_repository+other_repo/a.cc
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
DA:8,0
LH:3
LF:4
end_of_record'
  local expected_result_b_cc='SF:b.cc
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
DA:5,0
LH:3
LF:4
end_of_record'

  assert_cc_coverage_result "$expected_result_a_cc" "$coverage_file_path"
  assert_cc_coverage_result "$expected_result_b_cc" "$coverage_file_path"
}

function test_external_cc_target_coverage_not_collected_by_default() {
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  setup_external_cc_target

  bazel coverage --test_output=all @other_repo//:t \
      &>"$TEST_log" || fail "Coverage for @other_repo//:t failed"

  local coverage_file_path="$(get_coverage_file_path_from_test_log)"
  local expected_result_b_cc='SF:b.cc
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
DA:5,0
LH:3
LF:4
end_of_record'

  assert_cc_coverage_result "$expected_result_b_cc" "$coverage_file_path"
  assert_not_contains "SF:external/+local_repository+other_repo/a.cc" "$coverage_file_path"
}

run_suite "test tests"
