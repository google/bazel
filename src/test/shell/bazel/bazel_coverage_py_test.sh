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

# TODO: Fix tests that fail without this flag
add_to_bazelrc "test --test_env=IGNORE_COVERAGE_COLLECTION_FAILURES=1"

# Returns 0 if gcov is not installed or if a version before 7.0 was found.
# Returns 1 otherwise.
function is_gcov_missing_or_wrong_version() {
  local -r gcov_location=$(which gcov)
  if [[ ! -x ${gcov_location:-/usr/bin/gcov} ]]; then
    echo "gcov not installed."
    return 0
  fi

  "$gcov_location" -version | grep "LLVM" && \
      echo "gcov LLVM version not supported." && return 0
  # gcov -v | grep "gcov" outputs a line that looks like this:
  # gcov (Debian 7.3.0-5) 7.3.0
  local gcov_version="$(gcov -v | grep "gcov" | cut -d " " -f 4 | cut -d "." -f 1)"
  [ "$gcov_version" -lt 7 ] \
      && echo "gcov versions before 7.0 is not supported." && return 0
  return 1
}

function set_up_py_test_coverage() {
  cat <<EOF > BUILD
py_test(
    name = "orange_test",
    srcs = ["orange_test.py"],
    data = ["//java/com/google/orange:orange-bin"],
)

py_test(
    name = "orange_indirect_test",
    srcs = ["orange_test.py"],
    main = "orange_test.py",
    deps = [":orange_indirect_lib"],
)

py_library(
    name = "orange_indirect_lib",
    data = ["//java/com/google/orange:orange-bin"],
)
EOF
  cat <<EOF > orange_test.py
import subprocess
subprocess.Popen(["java/com/google/orange/orange-bin"]).wait()
EOF

  mkdir -p java/com/google/orange

  cat <<EOF > java/com/google/orange/BUILD
package(default_visibility = ["//visibility:public"])

java_binary(
    name = "orange-bin",
    srcs = ["orangeBin.java"],
    main_class = "com.google.orange.orangeBin",
    deps = [":orange-lib"],
)

java_library(
    name = "orange-lib",
    srcs = ["orangeLib.java"],
)
EOF

  cat <<EOF > java/com/google/orange/orangeLib.java
package com.google.orange;

public class orangeLib {

  public void print() {
    System.out.println("orange prints a message!");
  }
}
EOF

  cat <<EOF > java/com/google/orange/orangeBin.java
package com.google.orange;

public class orangeBin {
  public static void main(String[] args) {
    orangeLib orange = new orangeLib();
    orange.print();
  }
}
EOF

  cat <<EOF > expected.dat
SF:java/com/google/orange/orangeBin.java
FN:3,com/google/orange/orangeBin::<init> ()V
FN:5,com/google/orange/orangeBin::main ([Ljava/lang/String;)V
FNDA:0,com/google/orange/orangeBin::<init> ()V
FNDA:1,com/google/orange/orangeBin::main ([Ljava/lang/String;)V
FNF:2
FNH:1
DA:3,0
DA:5,1
DA:6,1
DA:7,1
LH:3
LF:4
end_of_record
SF:java/com/google/orange/orangeLib.java
FN:3,com/google/orange/orangeLib::<init> ()V
FN:6,com/google/orange/orangeLib::print ()V
FNDA:1,com/google/orange/orangeLib::<init> ()V
FNDA:1,com/google/orange/orangeLib::print ()V
FNF:2
FNH:2
DA:3,1
DA:6,1
DA:7,1
LH:3
LF:3
end_of_record
EOF
}

function test_py_test_coverage() {
  set_up_py_test_coverage
  bazel coverage --test_output=all //:orange_test &>$TEST_log || fail "Coverage for //:orange_test failed"
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"
  diff expected.dat "$coverage_file_path" >> $TEST_log
  cmp expected.dat "$coverage_file_path" || fail "Coverage output file is different than the expected file for data dep of py_binary"
}

function test_py_test_coverage_indirect() {
  set_up_py_test_coverage
  bazel coverage --test_output=all //:orange_indirect_test &>$TEST_log || fail "Coverage for //:orange_indirect_test failed"
  coverage_file_path="$( get_coverage_file_path_from_test_log )"
  diff expected.dat "$coverage_file_path" >> $TEST_log
  cmp expected.dat "$coverage_file_path" || fail "Coverage output file is different than the expected file for data dep of py_library"
}

function test_py_test_coverage_cc_binary() {
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  ########### Setup source files and BUILD file ###########
  cat <<EOF > BUILD
py_test(
    name = "num_test",
    srcs = ["num_test.py"],
    data = ["//examples/cpp:num-world"]
)
EOF
  cat <<EOF > num_test.py
import subprocess
subprocess.Popen(["examples/cpp/num-world"]).wait()
EOF

  mkdir -p examples/cpp

  cat <<EOF > examples/cpp/BUILD
package(default_visibility = ["//visibility:public"])

cc_binary(
    name = "num-world",
    srcs = ["num-world.cc"],
    deps = [":num-lib"],
)

cc_library(
    name = "num-lib",
    srcs = ["num-lib.cc"],
    hdrs = ["num-lib.h"]
)
EOF

  cat <<EOF > examples/cpp/num-world.cc
#include "examples/cpp/num-lib.h"

using num::NumLib;

int main(int argc, char** argv) {
  NumLib lib(30);
  int value = 42;
  if (argc > 1) {
    value = 43;
  }
  lib.add_number(value);
  return 0;
}
EOF

  cat <<EOF > examples/cpp/num-lib.h
#ifndef EXAMPLES_CPP_NUM_LIB_H_
#define EXAMPLES_CPP_NUM_LIB_H_

namespace num {

class NumLib {
 public:
  explicit NumLib(int number);

  int add_number(int value);

 private:
  int number_;
};

}  // namespace num

#endif  // EXAMPLES_CPP_NUM_LIB_H_
EOF

  cat <<EOF > examples/cpp/num-lib.cc
#include "examples/cpp/num-lib.h"

namespace num {

NumLib::NumLib(int number) : number_(number) {
}

int NumLib::add_number(int value) {
  return number_ + value;
}

}  // namespace num
EOF

  ########### Run bazel coverage ###########
  bazel coverage  --test_output=all \
      //:num_test &>$TEST_log || fail "Coverage for //:num_test failed"

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
LH:4
LF:4
end_of_record"
  assert_coverage_result "$expected_result_num_lib" "$coverage_file_path"
  local coverage_result_num_lib_header="SF:examples/cpp/num-world.cc
FN:5,main
FNDA:1,main
FNF:1
FNH:1
BRDA:6,0,0,1
BRDA:6,0,1,0
BRDA:8,0,0,0
BRDA:8,0,1,1
BRDA:11,0,0,1
BRDA:11,0,1,0
BRF:6
BRH:3
DA:5,1
DA:6,1
DA:7,1
DA:8,1
DA:9,0
DA:11,1
DA:12,1
LH:6
LF:7
end_of_record"
  assert_cc_coverage_result "$coverage_result_num_lib_header" "$coverage_file_path"
}

function test_py_test_coverage_cc_binary_and_java_binary() {
   if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  ########### Setup source files and BUILD file ###########
  cat <<EOF > BUILD
py_test(
    name = "num_test",
    srcs = ["num_test.py"],
    data = [
        "//examples/cpp:num-world",
        "//java/com/google/orange:orange-bin",
    ],
)
EOF
  cat <<EOF > num_test.py
import subprocess
subprocess.Popen(["examples/cpp/num-world"]).wait()
subprocess.Popen(["java/com/google/orange/orange-bin"]).wait()
EOF

  mkdir -p examples/cpp

  cat <<EOF > examples/cpp/BUILD
package(default_visibility = ["//visibility:public"])

cc_binary(
    name = "num-world",
    srcs = ["num-world.cc"],
    deps = [":num-lib"],
)

cc_library(
    name = "num-lib",
    srcs = ["num-lib.cc"],
    hdrs = ["num-lib.h"]
)
EOF

  cat <<EOF > examples/cpp/num-world.cc
#include "examples/cpp/num-lib.h"

using num::NumLib;

int main(int argc, char** argv) {
  NumLib lib(30);
  int value = 42;
  if (argc > 1) {
    value = 43;
  }
  lib.add_number(value);
  return 0;
}
EOF

  cat <<EOF > examples/cpp/num-lib.h
#ifndef EXAMPLES_CPP_NUM_LIB_H_
#define EXAMPLES_CPP_NUM_LIB_H_

namespace num {

class NumLib {
 public:
  explicit NumLib(int value);

  int add_number(int value);

 private:
  int number_;
};

}  // namespace num

#endif  // EXAMPLES_CPP_NUM_LIB_H_
EOF

  cat <<EOF > examples/cpp/num-lib.cc
#include "examples/cpp/num-lib.h"

namespace num {

NumLib::NumLib(int number) : number_(number) {
}

int NumLib::add_number(int value) {
  return number_ + value;
}

}  // namespace num
EOF

  ########### Setup Java sources ###########
  mkdir -p java/com/google/orange

  cat <<EOF > java/com/google/orange/BUILD
package(default_visibility = ["//visibility:public"])
java_binary(
    name = "orange-bin",
    srcs = ["orangeBin.java"],
    main_class = "com.google.orange.orangeBin",
    deps = [":orange-lib"],
)
java_library(
    name = "orange-lib",
    srcs = ["orangeLib.java"],
)
EOF

  cat <<EOF > java/com/google/orange/orangeLib.java
package com.google.orange;
public class orangeLib {
  public void print() {
    System.out.println("orange prints a message!");
  }
}
EOF

  cat <<EOF > java/com/google/orange/orangeBin.java
package com.google.orange;
public class orangeBin {
  public static void main(String[] args) {
    orangeLib orange = new orangeLib();
    orange.print();
  }
}
EOF

  ########### Run bazel coverage ###########
  bazel coverage  --test_output=all \
      //:num_test &>$TEST_log || fail "Coverage for //:num_test failed"

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
LH:4
LF:4
end_of_record"
  assert_cc_coverage_result "$expected_result_num_lib" "$coverage_file_path"

  local coverage_result_num_lib_header="SF:examples/cpp/num-world.cc
FN:5,main
FNDA:1,main
FNF:1
FNH:1
BRDA:6,0,0,1
BRDA:6,0,1,0
BRDA:8,0,0,0
BRDA:8,0,1,1
BRDA:11,0,0,1
BRDA:11,0,1,0
BRF:6
BRH:3
DA:5,1
DA:6,1
DA:7,1
DA:8,1
DA:9,0
DA:11,1
DA:12,1
LH:6
LF:7
end_of_record"
  assert_cc_coverage_result "$coverage_result_num_lib_header" "$coverage_file_path"


  ############# Assert Java code coverage results

  local coverage_result_orange_bin="SF:java/com/google/orange/orangeBin.java
FN:2,com/google/orange/orangeBin::<init> ()V
FN:4,com/google/orange/orangeBin::main ([Ljava/lang/String;)V
FNDA:0,com/google/orange/orangeBin::<init> ()V
FNDA:1,com/google/orange/orangeBin::main ([Ljava/lang/String;)V
FNF:2
FNH:1
DA:2,0
DA:4,1
DA:5,1
DA:6,1
LH:3
LF:4
end_of_record"
  assert_coverage_result "$coverage_result_orange_bin" "$coverage_file_path"

  local coverage_result_orange_lib="SF:java/com/google/orange/orangeLib.java
FN:2,com/google/orange/orangeLib::<init> ()V
FN:4,com/google/orange/orangeLib::print ()V
FNDA:1,com/google/orange/orangeLib::<init> ()V
FNDA:1,com/google/orange/orangeLib::print ()V
FNF:2
FNH:2
DA:2,1
DA:4,1
DA:5,1
LH:3
LF:3
end_of_record"
  assert_coverage_result "$coverage_result_orange_lib" "$coverage_file_path"
}

run_suite "test tests"
