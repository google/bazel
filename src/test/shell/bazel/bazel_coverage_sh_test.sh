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


function set_up_sh_test_coverage() {
  cat <<EOF > BUILD
sh_test(
    name = "orange-sh",
    srcs = ["orange-test.sh"],
    data = ["//java/com/google/orange:orange-bin"],
)

sh_test(
    name = "orange-sh-indirect",
    srcs = ["orange-test.sh"],
    deps = [":orange-sh-lib"],
)

# This doesn't test all the combinations, it only exercises
# a deps dependency from sh_test and a data dependency from
# sh_library, but they use the same InstrumentedFilesSpec.
sh_library(
    name = "orange-sh-lib",
    data = ["//java/com/google/orange:orange-bin"],
)
EOF
  cat <<EOF > orange-test.sh
#!/bin/bash

java/com/google/orange/orange-bin
EOF
  chmod +x orange-test.sh

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

function test_sh_test_coverage() {
  set_up_sh_test_coverage
  bazel coverage --test_output=all //:orange-sh &>$TEST_log || fail "Coverage for //:orange-sh failed"
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"
  diff expected.dat "$coverage_file_path" >> $TEST_log
  cmp expected.dat "$coverage_file_path" || fail "Coverage output file is different than the expected file for data dep of sh_binary"
}

function test_sh_test_coverage_indirect() {
  set_up_sh_test_coverage
  bazel coverage --test_output=all //:orange-sh-indirect &>$TEST_log || fail "Coverage for //:orange-sh-indirect failed"
  coverage_file_path="$( get_coverage_file_path_from_test_log )"
  diff expected.dat "$coverage_file_path" >> $TEST_log
  cmp expected.dat "$coverage_file_path" || fail "Coverage output file is different than the expected file for data dep of sh_library"
}

function test_sh_test_coverage_cc_binary() {
  if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  ########### Setup source files and BUILD file ###########
  cat <<EOF > BUILD
sh_test(
    name = "num-sh",
    srcs = ["num-test.sh"],
    data = ["//examples/cpp:num-world"]
)
EOF
  cat <<EOF > num-test.sh
#!/bin/bash

examples/cpp/num-world
EOF
  chmod +x num-test.sh

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
      //:num-sh &>$TEST_log || fail "Coverage for //:orange-sh failed"

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

function test_sh_test_coverage_cc_binary_and_java_binary() {
   if is_gcov_missing_or_wrong_version; then
    echo "Skipping test." && return
  fi

  ########### Setup source files and BUILD file ###########
  cat <<EOF > BUILD
sh_test(
    name = "num-sh",
    srcs = ["num-test.sh"],
    data = [
      "//examples/cpp:num-world",
      "//java/com/google/orange:orange-bin"
    ]
)
EOF
  cat <<EOF > num-test.sh
#!/bin/bash

examples/cpp/num-world
java/com/google/orange/orange-bin
EOF
  chmod +x num-test.sh

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
      //:num-sh &>$TEST_log || fail "Coverage for //:orange-sh failed"

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

function test_coverage_as_tree_artifact() {
  cat <<'EOF' > BUILD
sh_test(
    name = "pull",
    srcs = ["pull-test.sh"],
)
EOF
  cat <<'EOF' > pull-test.sh
#!/bin/bash
touch $COVERAGE_DIR/foo.txt
# We need a non-empty coverage.dat file for the checks below to work.
echo "FN:2,com/google/orange/orangeLib::<init> ()V" > $COVERAGE_OUTPUT_FILE
exit 0
EOF
  chmod +x pull-test.sh

  bazel coverage --test_output=all --experimental_fetch_all_coverage_outputs --noexperimental_split_coverage_postprocessing //:pull &>$TEST_log \
      || fail "Coverage failed"

  local coverage_file_path="$(dirname $( get_coverage_file_path_from_test_log ))/_coverage/foo.txt"
  [[ -e $coverage_file_path ]] || fail "Cannot find extra file"
}


run_suite "test tests"
