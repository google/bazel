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

function test_execroot_structure() {
  ws_name="dooby_dooby_doo"
  cat > WORKSPACE <<EOF
workspace(name = "$ws_name")
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

  bazel build -s //dir:use-srcs &> $TEST_log || fail "expected success"
  execroot="$(bazel info execution_root)"
  test -e "$execroot/../${ws_name}"
  ls -l bazel-out | tee out
  assert_contains "$(dirname $execroot)/${ws_name}/bazel-out" out
}

function test_sibling_repository_layout() {
    touch WORKSPACE

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

function test_sibling_repository_layout_with_glob() {
    touch WORKSPACE

    mkdir -p external/foo
    cat > external/foo/input.txt <<'EOF'
12345
EOF
    cat > BUILD <<'EOF'
genrule(
  name = "use-srcs",
  srcs = glob(["**/**"]),
  cmd = "cp $< $@",
  outs = ["used-srcs"],
)
EOF

    bazel build --experimental_sibling_repository_layout //:use-srcs \
        || fail "expected success"

    cat "$(bazel info execution_root)/used-srcs"
}

function test_no_sibling_repository_layout() {
    touch WORKSPACE

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

run_suite "execution root tests"
