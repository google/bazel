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

set -eu

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function test_execroot_structure_with_bzlmod() {
  cat > WORKSPACE <<EOF
workspace(name = "whatever_doesnt_matter")
EOF
  cat > MODULE.bazel <<EOF
module(name="this_also_doesnt_matter")
EOF

  mkdir dir
  cat > dir/BUILD <<'EOF'
genrule(
  name = "use-srcs",
  srcs = ["BUILD"],
  cmd = "cp $< $@",
  outs = ["used-srcs"],
)
EOF

  bazel build --enable_bzlmod -s //dir:use-srcs &> $TEST_log \
      || fail "expected success"
  execroot="$(bazel info --enable_bzlmod execution_root)"
  test -e "$execroot/../_main"
  ls -l bazel-out | tee out
  assert_contains "$(dirname $execroot)/_main/bazel-out" out
}

function test_sibling_repository_layout() {
    mkdir -p external/foo
    cat > external/foo/BUILD <<'EOF'
genrule(
  name = "use-srcs",
  srcs = ["BUILD"],
  cmd = "cp $< $@",
  outs = ["used-srcs"],
)
EOF

    bazel build --experimental_sibling_repository_layout //external/foo:use-srcs \
        || fail "expected success"

    execroot="$(bazel info execution_root)"

    test -e "$execroot/external/foo/BUILD"

    test -e "$execroot/../bazel_tools/tools/genrule/genrule-setup.sh"
    test ! -e "$execroot/external/bazel_tools/tools/genrule/genrule-setup.sh"
}

# Regression test for b/149771751
function test_sibling_repository_layout_indirect_dependency() {
    mkdir external
    mkdir -p foo
    cat > BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])

filegroup(
    name = "srcs",
    srcs = ["BUILD"],
)
EOF
    cat > foo/BUILD <<'EOF'
# cc_library depends on //external:cc_toolchain
cc_library(
  name = "srcs",
  data = ["//:srcs"], # load from root package to trigger symlinking planting of the top level external dir
)
EOF

    bazel build --experimental_sibling_repository_layout //foo:srcs || fail "expected success"
}

# Regression test for b/149771751
function test_subdirectory_repository_layout_indirect_dependency() {
    mkdir external
    mkdir -p foo
    cat > BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])

filegroup(
    name = "srcs",
    srcs = ["BUILD"],
)
EOF
    cat > foo/BUILD <<'EOF'
# cc_library depends on //external:cc_toolchain
cc_library(
  name = "srcs",
  data = ["//:srcs"], # load from root package to trigger symlinking planting of the top level external dir
)
EOF

    bazel build --noexperimental_sibling_repository_layout //foo:srcs || fail "expected success"
}

function test_no_sibling_repository_layout() {
    mkdir -p external/foo
    cat > external/foo/BUILD <<'EOF'
genrule(
  name = "use-srcs",
  srcs = ["BUILD"],
  cmd = "cp $< $@",
  outs = ["used-srcs"],
)
EOF

    bazel build //external/foo:use-srcs --experimental_sibling_repository_layout=false \
        &> $TEST_log && fail "should have failed" || true
    expect_log "external/foo/BUILD.*: No such file or directory"

    execroot="$(bazel info execution_root)"

    test ! -e "$execroot/external/foo/BUILD"

    test ! -e "$execroot/../bazel_tools/tools/genrule/genrule-setup.sh"
    test -e "$execroot/external/bazel_tools/tools/genrule/genrule-setup.sh"

}

function test_external_directory_globs() {
  mkdir -p external/a external/c
  echo file_ab > external/a/b
  echo file_cd > external/c/d
  echo file_e > external/e
  touch external/a/b external/c/d external/e

  cat > BUILD <<'EOF'
filegroup(name='f', srcs=glob(["**/*"]))
genrule(name="g", srcs=[":f"], outs=["go"], cmd="cat $(locations :f) > $@")
EOF

  bazel build //:g \
    --experimental_disable_external_package \
    --experimental_sibling_repository_layout \
    || fail "build failed"
  assert_contains file_ab bazel-bin/go
  assert_contains file_cd bazel-bin/go
  assert_contains file_e bazel-bin/go
}

function test_cc_smoke_with_new_layouts() {
  mkdir -p external/a
  cat > external/a/BUILD <<EOF
cc_binary(name='a', srcs=['a.cc'])
EOF

  cat > external/a/a.cc <<EOF
int main(void) {
  return 0;
}
EOF

  bazel build //external/a:a \
    --experimental_disable_external_package \
    --experimental_sibling_repository_layout \
    || fail "build failed"
}

function test_java_smoke_with_new_layouts() {
  mkdir -p external/java/a
  cat > external/java/a/BUILD <<EOF
java_binary(name='a', srcs=['A.java'])
EOF

  cat > external/java/a/A.java << EOF
package a;
public class A {
  public static void main(String[] args) {
    System.out.println("hello world");
  }
}
EOF

  bazel build //external/java/a:a \
    --experimental_disable_external_package \
    --experimental_sibling_repository_layout \
    || fail "build failed"
}

run_suite "execution root tests"
