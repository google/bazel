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

# Tests target's tags propagation with rules defined in Starlark.
# Tests for https://github.com/bazelbuild/bazel/issues/7766

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

PLATFORM="$(uname -s | tr 'A-Z' 'a-z')"

# Test a basic native rule which has tags, that should be propagated
function test_cc_library_tags_propagated() {
  mkdir -p test
  cat > test/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_library(
  name = 'test',
  srcs = [ 'test.cc' ],
  tags = ["no-cache", "no-remote", "local"]
)
EOF
  cat > test/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello test!" << std::endl; return 0; }
EOF

  bazel aquery --incompatible_allow_tags_propagation 'mnemonic("CppCompile", //test:test)' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 1 output1
  assert_contains_n "local:" 1 output1
  assert_contains_n "no-cache:" 1 output1
  assert_contains_n "no-remote:" 1 output1

  bazel aquery --incompatible_allow_tags_propagation 'mnemonic("CppArchive", outputs(".*/libtest.a", //test:test))' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 1 output1
  assert_contains_n "local:" 1 output1
  assert_contains_n "no-cache:" 1 output1
  assert_contains_n "no-remote:" 1 output1
  if [ "${PLATFORM}" != "darwin" ]; then
    # Darwin does not support implicit "nodeps" shared libraries.
    bazel aquery --incompatible_allow_tags_propagation 'mnemonic("CppLink", outputs(".*/libtest.so", //test:test))' > output1 2> $TEST_log \
        || fail "should have generated output successfully"

    assert_contains_n "Command Line:" 1 output1
    assert_contains_n "local:" 1 output1
    assert_contains_n "no-cache:" 1 output1
    assert_contains_n "no-remote:" 1 output1
  fi
}

function test_cc_binary_tags_propagated() {
 mkdir -p test
  cat > test/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_binary(
  name = "test",
  srcs = ["test.cc"],
  tags = ["no-cache", "no-remote", "local"]
)
EOF
  cat > test/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello test!" << std::endl; return 0; }
EOF

  bazel aquery --incompatible_allow_tags_propagation 'mnemonic("CppCompile", //test:test)' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 1 output1
  assert_contains_n "local:" 1 output1
  assert_contains_n "no-cache:" 1 output1
  assert_contains_n "no-remote:" 1 output1

  bazel aquery --incompatible_allow_tags_propagation 'mnemonic("CppLink", //test:test)' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 1 output1
  assert_contains_n "local:" 1 output1
  assert_contains_n "no-cache:" 1 output1
  assert_contains_n "no-remote:" 1 output1
}

function test_java_library_tags_propagated() {
  mkdir -p test
  cat > test/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
java_library(
  name = 'test',
  srcs = [ 'Hello.java' ],
  tags = ["no-cache", "no-remote", "local"]
)
EOF

	cat > test/Hello.java <<EOF
public class Main {
  public static void main(String[] args) {
    System.out.println("Hello there");
  }
}
EOF

  bazel aquery --incompatible_allow_tags_propagation '//test:test' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 3 output1
  assert_contains_n "local:" 3 output1
  assert_contains_n "no-cache:" 3 output1
  assert_contains_n "no-remote:" 3 output1
}

function test_java_binary_tags_propagated() {
  mkdir -p test
  cat > test/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
java_binary(
  name = 'test',
  srcs = [ 'Hello.java' ],
  main_class = 'main.Hello',
  tags = ["no-cache", "no-remote", "local"]
)
EOF

	cat > test/Hello.java <<EOF
public class Main {
  public static void main(String[] args) {
    System.out.println("Hello there");
  }
}
EOF

  bazel aquery --incompatible_allow_tags_propagation '//test:test' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 4 output1
  assert_contains_n "local:" 4 output1
  assert_contains_n "no-cache:" 4 output1
  assert_contains_n "no-remote:" 4 output1
}

function write_hello_library_files() {
  local -r pkg="$1"
  mkdir -p $pkg/java/main || fail "mkdir"
  cat >$pkg/java/main/BUILD <<EOF
java_binary(
    name = 'main',
    deps = ['//$pkg/java/hello_library'],
    srcs = ['Main.java'],
    main_class = 'main.Main',
    tags = ["no-cache", "no-remote", "local"],
    deploy_manifest_lines = ['k1: v1', 'k2: v2'])
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

  mkdir -p $pkg/java/hello_library || fail "mkdir"
  cat >$pkg/java/hello_library/BUILD <<EOF
package(default_visibility=['//visibility:public'])
java_library(name = 'hello_library',
             srcs = ['HelloLibrary.java']);
EOF

  cat >$pkg/java/hello_library/HelloLibrary.java <<EOF
package hello_library;
public class HelloLibrary {
  public static void funcHelloLibrary() {
    System.out.print("Hello, Library!;");
  }
}
EOF
}

function test_java_header_tags_propagated() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_library_files "$pkg"

  bazel aquery --incompatible_allow_tags_propagation --java_header_compilation=true //$pkg/java/main:main > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 4 output1
  assert_contains_n "local:" 4 output1
  assert_contains_n "no-cache:" 4 output1
  assert_contains_n "no-remote:" 4 output1
}

function test_genrule_tags_propagated() {
  mkdir -p test
  cat > test/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
genrule(
  name = 'test',
  outs = [ 'test.out' ],
  cmd = "echo hello > \$@",
  tags = ["no-cache", "no-remote", "local"]
)
EOF

  bazel aquery --incompatible_allow_tags_propagation '//test:test' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 1 output1
  assert_contains_n "local:" 1 output1
  assert_contains_n "no-cache:" 1 output1
  assert_contains_n "no-remote:" 1 output1
}

# Test a native test rule which has tags, that should be propagated (independent of flags)
function test_test_rules_tags_propagated() {
  mkdir -p test
  cat > test/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_test(
  name = 'test',
  srcs = [ 'test.cc' ],
  tags = ["no-cache", "no-remote", "local"]
)
EOF
  cat > test/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello test!" << std::endl; return 0; }
EOF

  bazel aquery --incompatible_allow_tags_propagation=false '//test:test' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 4 output1
  assert_contains_n "local:" 1 output1
  assert_contains_n "no-cache:" 1 output1
  assert_contains_n "no-remote:" 1 output1
}

# Test a basic native rule which has tags, that should not be propagated
# as --incompatible_allow_tags_propagation flag set to false
function test_cc_library_tags_not_propagated_when_incompatible_flag_off() {
  mkdir -p test
  cat > test/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_library(
  name = 'test',
  srcs = [ 'test.cc' ],
  tags = ["no-cache", "no-remote", "local"]
)
EOF
  cat > test/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello test!" << std::endl; return 0; }
EOF

  bazel aquery --incompatible_allow_tags_propagation=false '//test:test' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  if is_darwin; then
    # CppCompile, CppArchive
    assert_contains_n "Command Line:" 2 output1
  else
    # CppCompile, CppArchive, CppLink
    assert_contains_n "Command Line:" 3 output1
  fi
  assert_not_contains "local:" output1
  assert_not_contains "no-cache:" output1
  assert_not_contains "no-remote:" output1
}

function test_cc_binary_tags_not_propagated_when_incompatible_flag_off() {

 mkdir -p test
  cat > test/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
cc_binary(
  name = "test",
  srcs = ["test.cc"],
  tags = ["no-cache", "no-remote", "local"]
)
EOF
  cat > test/test.cc <<EOF
#include <iostream>
int main() { std::cout << "Hello test!" << std::endl; return 0; }
EOF

  bazel aquery --incompatible_allow_tags_propagation=false '//test:test' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 3 output1
  assert_not_contains "local:" output1
  assert_not_contains "no-cache:" output1
  assert_not_contains "no-remote:" output1
}

function test_java_tags_not_propagated_when_incompatible_flag_off() {
  mkdir -p test
  cat > test/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
java_library(
  name = 'test',
  srcs = [ 'Hello.java' ],
  tags = ["no-cache", "no-remote", "local"]
)
EOF

	cat > test/Hello.java <<EOF
public class Main {
  public static void main(String[] args) {
    System.out.println("Hello there");
  }
}
EOF

  bazel aquery --incompatible_allow_tags_propagation=false '//test:test' > output1 2> $TEST_log \
      || fail "should have generated output successfully"

  assert_contains_n "Command Line:" 3 output1
  assert_not_contains "local:" output1
  assert_not_contains "no-cache:" output1
  assert_not_contains "no-remote:" output1
}

run_suite "tags propagation: native rule tests"
