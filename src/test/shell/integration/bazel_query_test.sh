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
#
# bazel_query_test.sh: integration tests for bazel query

# --- begin runfiles.bash initialization ---
# Copy-pasted from Bazel's Bash runfiles library (tools/bash/runfiles/runfiles.bash).
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

# `uname` returns the current platform, e.g. "MSYS_NT-10.0" or "Linux".
# `tr` converts all upper case letters to lower case.
# `case` matches the result if the `uname | tr` expression to string prefixes
# that use the same wildcards as names do in Bash, i.e. "msys*" matches strings
# starting with "msys", and "*" matches everything (it's the default case).
case "$(uname -s | tr [:upper:] [:lower:])" in
msys*)
  # As of 2018-08-14, Bazel on Windows only supports MSYS Bash.
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

function set_up() {
  add_to_bazelrc "build --package_path=%workspace%"
  add_bazel_skylib "MODULE.bazel"
}

function tear_down() {
  bazel shutdown
}

#### TESTS #############################################################

function test_does_not_fail_horribly() {
  rm -rf peach
  mkdir -p peach
  cat > peach/BUILD <<EOF
sh_library(name='brighton', deps=[':harken'])
sh_library(name='harken')
EOF

  bazel query 'deps(//peach:brighton)' > $TEST_log

  expect_log "//peach:brighton"
  expect_log "//peach:harken"
}

function test_output_to_file() {
  rm -rf peach
  mkdir -p peach
  cat > peach/BUILD <<EOF
sh_library(name='brighton', deps=[':harken'])
sh_library(name='harken')
EOF

  bazel query 'deps(//peach:brighton)' --output_file=$TEST_log > $TEST_TMPDIR/query_stdout

  expect_log "//peach:brighton"
  expect_log "//peach:harken"

  assert_equals "" "$(<$TEST_TMPDIR/query_stdout)"
}

function test_invalid_query_fails_parsing() {
  bazel query 'deps("--bad_target_name_from_bad_script")' >& "$TEST_log" \
    && fail "Expected failure"
  expect_log "target literal must not begin with (-)"
}

function test_visibility_affects_xml_output() {
  rm -rf kiwi
  mkdir -p kiwi

  cat > kiwi/BUILD <<EOF
sh_library(name='kiwi', visibility=['//visibility:private'])
EOF
  bazel query --output=xml '//kiwi:kiwi' > output_private

  cat > kiwi/BUILD <<EOF
sh_library(name='kiwi', visibility=['//visibility:public'])
EOF
  bazel query --output=xml '//kiwi:kiwi' > output_public

  cat > kiwi/BUILD <<EOF
sh_library(name='kiwi')
EOF
  bazel query --output=xml '//kiwi:kiwi' > output_none

  cmp output_private output_public && fail "visibility does not affect XML output"
  cmp output_none output_private && fail "visibility does not affect XML output"
  cmp output_none output_public && fail "visibility does not affect XML output"

  assert_contains "//kiwi:kiwi" output_private
  assert_contains "//kiwi:kiwi" output_public
  assert_contains "//kiwi:kiwi" output_none

  assert_contains "//visibility:private" output_private
  assert_contains "//visibility:public" output_public
  assert_not_contains "//visibility:private" output_none
  assert_not_contains "//visibility:public" output_none
}

function test_visibility_affects_proto_output() {
  rm -rf kiwi
  mkdir -p kiwi

  cat > kiwi/BUILD <<EOF
sh_library(name='kiwi', visibility=['//visibility:private'])
EOF
  bazel query --output=proto '//kiwi:kiwi' > output_private

  cat > kiwi/BUILD <<EOF
sh_library(name='kiwi', visibility=['//visibility:public'])
EOF
  bazel query --output=proto '//kiwi:kiwi' > output_public

  # There is no check for unspecified visibility because proto output format
  # adds every attribute to the output, regardless of whether they are specified
  # or have the default value

  cmp output_private output_public && fail "visibility does not affect proto output"

  assert_contains "//kiwi:kiwi" output_private
  assert_contains "//kiwi:kiwi" output_public

  assert_contains "//visibility:private" output_private
  assert_contains "//visibility:public" output_public
}

function make_depth_tests() {
  rm -rf depth
  rm -rf depth2
  mkdir -p depth depth2 || die "Could not create test directory"
  cat > "depth/BUILD" <<EOF
sh_binary(name = 'one', srcs = ['one.sh'], deps = [':two'])
sh_library(name = 'two', srcs = ['two.sh'],
           deps = [':div2', ':three', '//depth2:three'])
sh_library(name = 'three', srcs = ['three.sh'], deps = [':four'])
sh_library(name = 'four', srcs = ['four.sh'], deps = [':div2', ':five'])
sh_library(name = 'five', srcs = ['five.sh'])
sh_library(name = 'div2', srcs = ['two.sh'])
EOF

  echo "sh_library(name = 'three', srcs = ['three.sh'])" > depth2/BUILD

  touch depth/{one,two,three,four,five}.sh depth2/three.sh
  chmod a+x depth/*.sh depth2/*.sh
}

# Running a deps query twice should return results in the same order
# if output is sorted, otherwise just the same results.
function assert_depth_query_idempotence() {
  order_results="$1"
  if $order_results ; then
    order_output_arg=--order_output=auto
    universe_arg=""
  else
    order_output_arg=--order_output=no
    universe_arg=--universe_scope=//depth:*
  fi
  make_depth_tests
  last_log="$TEST_log.last"
  for run in {1..5}; do
    # Only compare the output stream with the query results.
    mv -f $TEST_log $last_log
    bazel query 'deps(//depth:one, 4)' $order_output_arg $universe_arg \
        > $TEST_log || fail "Expected success"
    if [ $run -gt 1 ]; then
      if $order_results ; then
        diff $TEST_log $last_log || \
            fail "Lines differed between query results: $last_log"
      else
        diff <(sort $TEST_log) <(sort $last_log) || \
            fail "Lines differed between sorted query results"
      fi
    fi
  done
  rm -f $last_log || die "Could not remove $last_log"
}

function test_depth_query_idempotence_ordered() {
  assert_depth_query_idempotence true
}

function test_depth_query_idempotence_unordered() {
  assert_depth_query_idempotence false
}

function test_universe_scope_with_without_star() {
  rm -rf foo
  mkdir -p foo || fail "Couldn't mkdir"
  echo "sh_library(name = 'foo')" > foo/BUILD || fail "Couldn't write BUILD"
  bazel query --order_output=no \
      --universe_scope=//foo/... '//foo:BUILD' >& $TEST_log ||
      fail "Expected success"
  # This is documenting current behavior, rather than enforcing a contract. For
  # performance and code simplicity, we return targets if their package was
  # loaded, so //foo:BUILD exists as a target (although its deps and rdeps are
  # unknown).
  expect_log "//foo:BUILD"
  bazel query --order_output=no \
      --universe_scope=//foo/...:* '//foo:BUILD' >& $TEST_log ||
      fail "Expected success"
  expect_log "//foo:BUILD"
}

function test_outside_universe_ok() {
  rm -rf foo
  rm -rf bar
  mkdir -p foo bar || fail "Couldn't mkdir"
  echo "sh_library(name = 'foo', deps = ['//bar:bar'])" > foo/BUILD ||
      fail "Couldn't write BUILD"
  cat <<'EOF' > bar/BUILD || fail "Couldn't write BUILD"
sh_library(name = 'bar')
sh_library(name = 'dep')
sh_library(name = 'top', deps = [':dep'])
EOF
  bazel query --order_output=no \
      --universe_scope=//foo/...:* 'allrdeps(//bar:BUILD)' >& $TEST_log ||
      fail "Expected success"
  # This is documenting current behavior, rather than enforcing a contract. See
  # corresponding comment in test_universe_scope_with_without_star.
  expect_log "//bar:BUILD"
  bazel query --order_output=no \
      --universe_scope=//foo/...:* 'allrdeps(//bar:dep)' >& $TEST_log ||
      fail "Expected success"
  # This is documenting current behavior, rather than enforcing a contract. See
  # corresponding comment in test_universe_scope_with_without_star. In this
  # case, even though we return //bar:dep, we do not see its rdep //bar:top.
  expect_log "//bar:dep"
  expect_not_log "//bar:top"
}

# Since all targets in deps(..., n) are accessible n steps away, none should
# have a minrank greater than n.
function test_minrank_le_depth_bound() {
  make_depth_tests
  for depth in {5..0}; do
    bazel query "deps(//depth:one, $depth)" --output=minrank > $TEST_log \
      || fail "Expected success"
    for rank in $(cut -d' ' -f 1 $TEST_log); do
      [ $rank -le $depth ] || fail "Expected max minrank of $depth, was $rank"
    done
  done
}

function test_starlark_dep_in_sky_query() {
  rm -rf foo
  rm -rf bar
  mkdir -p foo bar || fail "Couldn't make directories"
  echo 'load("//bar:fakerule.bzl", "const")' > foo/BUILD || fail "Couldn't write"
  touch bar/BUILD || fail "Couldn't touch bar/BUILD"
  echo 'const = 2' > bar/fakerule.bzl || fail "Couldn't write fakerule"
  bazel query --universe_scope=//foo/...:* --order_output=no \
      'rbuildfiles(bar/fakerule.bzl)' >& $TEST_log || fail "Expected success"
  expect_log_once "//foo:BUILD"
  expect_not_log "//bar:BUILD"
  expect_not_log "fakerule\.bzl"
}

function test_starlark_regular_file_not_included_in_rbuildfiles() {
  rm -rf foo
  mkdir -p foo || fail "Couldn't make directories"
  echo "baz" > "foo/baz.bzl" || fail "Couldn't create baz.bzl"
  echo 'sh_library(name = "foo", srcs = ["baz.bzl"])' > foo/BUILD
  bazel query --universe_scope=//foo/...:* --order_output=no \
    'rbuildfiles(foo/baz.bzl)' >& $TEST_log || fail "Expected success"
  expect_not_log "//foo:BUILD"
  # TODO(bazel-team): Remove this once test clean-up is automated.
  # Clean up after ourselves.
  rm -rf foo
}

function test_starlark_symlink_source_not_included_in_rbuildfiles() {
  rm -rf foo
  mkdir -p foo || fail "Couldn't make directories"
  echo "moo" > "foo/moo" || fail "Couldn't create moo"
  ln -s "$PWD/foo/moo" "foo/baz.bzl" && [[ -f foo/baz.bzl ]] || fail "Couldn't create baz.bzl symlink"
  echo 'sh_library(name = "foo", srcs = ["baz.bzl"])' > foo/BUILD
  bazel query --universe_scope=//foo/...:* --order_output=no \
    'rbuildfiles(foo/baz.bzl)' >& $TEST_log || fail "Expected success"
  expect_not_log "//foo:BUILD"
  # TODO(bazel-team): Remove this once test clean-up is automated.
  # Clean up after ourselves.
  rm -rf foo
}

function test_starlark_symlink_target_not_included_in_rbuildfiles() {
  rm -rf foo
  mkdir -p foo || fail "Couldn't make directories"
  echo "baz" > "foo/baz.bzl" || fail "Couldn't create baz.bzl"
  ln -s "$PWD/foo/baz.bzl" "foo/Moo.java" && [[ -f foo/Moo.java ]] || fail "Couldn't create Moo.java symlink"
  echo 'sh_library(name = "foo", srcs = ["Moo.java"])' > foo/BUILD
  bazel query --universe_scope=//foo/...:* --order_output=no \
    'rbuildfiles(foo/baz.bzl)' >& $TEST_log || fail "Expected success"
  expect_not_log "//foo:BUILD"
  # TODO(bazel-team): Remove this once test clean-up is automated.
  # Clean up after ourselves.
  rm -rf foo
}

function test_starlark_glob_regular_file_not_included_in_rbuildfiles() {
  rm -rf foo
  mkdir -p foo || fail "Couldn't make directories"
  echo "baz" > "foo/baz.bzl" || fail "Couldn't create baz.bzl"
  echo 'sh_library(name = "foo", srcs = glob(["*.bzl"]))' > foo/BUILD
  bazel query --universe_scope=//foo/...:* --order_output=no \
    'rbuildfiles(foo/baz.bzl)' >& $TEST_log || fail "Expected success"
  expect_not_log "//foo:BUILD"
  # TODO(bazel-team): Remove this once test clean-up is automated.
  # Clean up after ourselves.
  rm -rf foo
}

function test_starlark_glob_symlink_source_not_included_in_rbuildfiles() {
  rm -rf foo
  mkdir -p foo || fail "Couldn't make directories"
  echo "moo" > "foo/moo" || fail "Couldn't create moo"
  ln -s "$PWD/foo/moo" "foo/baz.bzl" && [[ -f foo/baz.bzl ]] || fail "Couldn't create baz.bzl symlink"
  echo 'sh_library(name = "foo", srcs = glob(["*.bzl"]))' > foo/BUILD
  bazel query --universe_scope=//foo/...:* --order_output=no \
    'rbuildfiles(foo/baz.bzl)' >& $TEST_log || fail "Expected success"
  expect_not_log "//foo:BUILD"
  # TODO(bazel-team): Remove this once test clean-up is automated.
  # Clean up after ourselves.
  rm -rf foo
}

function test_starlark_glob_symlink_target_not_included_in_rbuildfiles() {
  rm -rf foo
  mkdir -p foo || fail "Couldn't make directories"
  echo "baz" > "foo/baz.bzl" || fail "Couldn't create baz.bzl"
  ln -s "$PWD/foo/baz.bzl" "foo/Moo.java" && [[ -f foo/Moo.java ]] || fail "Couldn't create Moo.java symlink"
  echo 'sh_library(name = "foo", srcs = glob(["*.java"]))' > foo/BUILD
  bazel query --universe_scope=//foo/...:* --order_output=no \
    'rbuildfiles(foo/baz.bzl)' >& $TEST_log || fail "Expected success"
  expect_not_log "//foo:BUILD"
  # TODO(bazel-team): Remove this once test clean-up is automated.
  # Clean up after ourselves.
  rm -rf foo
}

function test_starlark_recursive_glob_regular_file_not_included_in_rbuildfiles() {
  rm -rf foo
  mkdir -p foo/bar || fail "Couldn't make directories"
  echo "baz" > "foo/bar/baz.bzl" || fail "Couldn't create baz.bzl"
  echo 'sh_library(name = "foo", srcs = glob(["**/*.bzl"]))' > foo/BUILD
  bazel query --universe_scope=//foo/...:* --order_output=no \
    'rbuildfiles(foo/bar/baz.bzl)' >& $TEST_log || fail "Expected success"
  expect_not_log "//foo:BUILD"
  # TODO(bazel-team): Remove this once test clean-up is automated.
  # Clean up after ourselves.
  rm -rf foo
}

function test_starlark_recursive_glob_symlink_source_not_included_in_rbuildfiles() {
  rm -rf foo
  mkdir -p foo/bar || fail "Couldn't make directories"
  echo "moo" > "foo/moo" || fail "Couldn't create moo"
  ln -s "$PWD/foo/moo" "foo/bar/baz.bzl" && [[ -f foo/bar/baz.bzl ]] || fail "Couldn't create baz.bzl symlink"
  echo 'sh_library(name = "foo", srcs = glob(["**/*.bzl"]))' > foo/BUILD
  bazel query --universe_scope=//foo/...:* --order_output=no \
    'rbuildfiles(foo/bar/baz.bzl)' >& $TEST_log || fail "Expected success"
  expect_not_log "//foo:BUILD"
  # TODO(bazel-team): Remove this once test clean-up is automated.
  # Clean up after ourselves.
  rm -rf foo
}

function test_starlark_recursive_glob_symlink_target_not_included_in_rbuildfiles() {
  rm -rf foo
  mkdir -p foo/bar || fail "Couldn't make directories"
  echo "baz" > "foo/bar/baz.bzl" || fail "Couldn't create baz.bzl"
  ln -s "$PWD/foo/bar/baz.bzl" "foo/Moo.java" && [[ -f foo/Moo.java ]] || fail "Couldn't create Moo.java symlink"
  echo 'sh_library(name = "foo", srcs = glob(["**/*.java"]))' > foo/BUILD
  bazel query --universe_scope=//foo/...:* --order_output=no \
    'rbuildfiles(foo/bar/baz.bzl)' >& $TEST_log || fail "Expected success"
  expect_not_log "//foo:BUILD"
  # TODO(bazel-team): Remove this once test clean-up is automated.
  # Clean up after ourselves.
  rm -rf foo
}

function test_starlark_subdir_dep_in_sky_query() {
  rm -rf foo
  mkdir -p foo bar/baz || fail "Couldn't make directories"
  echo 'load("//bar:baz/fakerule.bzl", "const")' > foo/BUILD || fail "Couldn't write"
  touch bar/BUILD || fail "Couldn't touch bar/BUILD"
  echo 'const = 2' > bar/baz/fakerule.bzl || fail "Couldn't write fakerule"
  bazel query --universe_scope=//foo/...:* --order_output=no \
      'rbuildfiles(bar/baz/fakerule.bzl)' >& $TEST_log || fail "Expected success"
  expect_log_once "//foo:BUILD"
  expect_not_log "//bar:BUILD"
  expect_not_log "fakerule\.bzl"
}

function test_parent_independent_of_child() {
  rm -rf foo
  mkdir -p foo/subdir || fail "Couldn't make directories"
  echo 'sh_library(name = "sh", data = glob(["**"]))' > foo/BUILD ||
      fail "Couldn't write"
  touch foo/subdir/BUILD || fail "Couldn't touch foo/subdir/BUILD"
  bazel query --universe_scope=//foo/...:* --order_output=no \
      'rbuildfiles(foo/subdir/BUILD)' >& $TEST_log || fail "Expected success"
  expect_log_once "//foo/subdir:BUILD"
  expect_not_log "//foo:BUILD"
}

function test_does_not_fail_horribly_with_file() {
  rm -rf peach
  mkdir -p peach
  cat > peach/BUILD <<EOF
sh_library(name='brighton', deps=[':harken'])
sh_library(name='harken')
EOF

  echo "deps(//peach:brighton)" > query_file
  bazel query --query_file=query_file > $TEST_log

  expect_log "//peach:brighton"
  expect_log "//peach:harken"
}

function test_location_output_not_allowed_with_buildfiles_or_loadfiles() {
  rm -rf foo
  mkdir -p foo
  cat > foo/bzl.bzl <<EOF
x = 2
EOF
  cat > foo/BUILD <<EOF
load('//foo:bzl.bzl', 'x')
sh_library(name='foo')
EOF

  bazel query 'buildfiles(//foo)' >& $TEST_log || fail "Expected success"
  expect_log "//foo:bzl.bzl"
  bazel query 'loadfiles(//foo)' >& $TEST_log || fail "Expected success"
  expect_log "//foo:bzl.bzl"
  bazel query --output=location '//foo' >& $TEST_log || fail "Expected success"
  expect_log "//foo:foo"

  local expected_error_msg="Query expressions involving 'buildfiles' or 'loadfiles' cannot be used with --output=location"
  local expected_exit_code=2
  for query_string in 'buildfiles(//foo)' 'loadfiles(//foo)'
  do
    bazel query --output=location "$query_string" >& $TEST_log \
        && fail "Expected failure"
    exit_code=$?
    expect_log "$expected_error_msg"
    assert_equals "$expected_exit_code" "$exit_code"
  done
}

function test_location_output_relative_locations() {
  rm -rf foo
  mkdir -p foo
  cat > foo/BUILD <<EOF
sh_library(name='foo')
EOF

  bazel query --output=location '//foo' >& $TEST_log || fail "Expected success"
  expect_log "${TEST_TMPDIR}/.*/foo/BUILD"
  expect_log "//foo:foo"

  bazel query --output=location --relative_locations '//foo' >& $TEST_log || fail "Expected success"
  # Query with --relative_locations should not show full path
  expect_not_log "${TEST_TMPDIR}/.*/foo/BUILD"
  expect_log "^foo/BUILD"
  expect_log "//foo:foo"
}

function test_location_output_source_files() {
  add_rules_python "MODULE.bazel"
  rm -rf foo
  mkdir -p foo
  cat > foo/BUILD <<EOF
load('@rules_python//python:py_binary.bzl', 'py_binary')

py_binary(
  name = "main",
  srcs = ["main.py"],
)
EOF
  touch foo/main.py || fail "Could not touch foo/main.py"

  # Check that Bazel displays the location of line 1 of the actual source file
  bazel query \
    --output=location \
    '//foo:main.py' >& $TEST_log || fail "Expected success"
  expect_log "source file //foo:main.py"
  expect_log "^${TEST_TMPDIR}/.*/foo/main.py:1:1"
  expect_not_log "^${TEST_TMPDIR}/.*/foo/BUILD:[0-9]*:[0-9]*"

  # Location should still be affected by relative_locations flag to display the
  # relative location of the source file
  bazel query \
    --output=location \
    --relative_locations \
    '//foo:main.py' >& $TEST_log || fail "Expected success"
  expect_log "source file //foo:main.py"
  expect_log "^foo/main.py:1:1"
  expect_not_log "^${TEST_TMPDIR}/.*/foo/main.py:1:1"
}

function test_proto_output_source_files() {
  add_rules_python "MODULE.bazel"
  rm -rf foo
  mkdir -p foo
  cat > foo/BUILD <<EOF
load('@rules_python//python:py_binary.bzl', 'py_binary')

py_binary(
  name = "main",
  srcs = ["main.py"],
)
EOF
  touch foo/main.py || fail "Could not touch foo/main.py"

  bazel query --output=proto \
    '//foo:main.py' >& $TEST_log || fail "Expected success"

  expect_log "${TEST_TMPDIR}/.*/foo/main.py:1:1" $TEST_log
  expect_not_log "${TEST_TMPDIR}/.*/foo/BUILD:[0-9]*:[0-9]*" $TEST_log
}

function test_xml_output_source_files() {
  add_rules_python "MODULE.bazel"
  rm -rf foo
  mkdir -p foo
  cat > foo/BUILD <<EOF
load('@rules_python//python:py_binary.bzl', 'py_binary')

py_binary(
  name = "main",
  srcs = ["main.py"],
)
EOF
  touch foo/main.py || fail "Could not touch foo/main.py"

  bazel query --output=xml \
    '//foo:main.py' >& $TEST_log || fail "Expected success"
  expect_log "location=\"${TEST_TMPDIR}/.*/foo/main.py:1:1"
  expect_not_log "location=\"${TEST_TMPDIR}/.*/foo/BUILD:[0-9]*:[0-9]*"
}

function test_subdirectory_named_external() {
  mkdir -p foo/external foo/bar
  cat > foo/external/BUILD <<EOF
sh_library(name = 't1')
EOF
  cat > foo/bar/BUILD <<EOF
sh_library(name = 't2')
EOF

  bazel query foo/... >& $TEST_log || fail "Expected success"
  expect_log "//foo/external:t1"
  expect_log "//foo/bar:t2"
}

function test_buildfiles_with_build_bazel() {
  if [ "${PRODUCT_NAME}" != "bazel" ]; then
    return 0
  fi
  rm -rf foo
  mkdir -p foo
  cat > foo/bzl.bzl <<EOF
x = 2
EOF
  cat > foo/BUILD.bazel <<EOF
load('//foo:bzl.bzl', 'x')
sh_library(name='foo')
EOF

  bazel query 'buildfiles(//foo)' >& $TEST_log || fail "Expected success"
  expect_log "//foo:bzl.bzl$"
  expect_log "//foo:BUILD.bazel$"
  expect_not_log "//foo:BUILD$"
}

function test_buildfile_in_genquery() {
  mkdir -p papaya
  cat > papaya/BUILD <<EOF
exports_files(['papaya.bzl'])
EOF
  cat > papaya/papaya.bzl <<EOF
foo = 1
EOF
  mkdir -p honeydew
  cat > honeydew/BUILD <<EOF
load('//papaya:papaya.bzl', 'foo')
sh_library(name='honeydew', deps=[':pineapple'])
sh_library(name='pineapple')
genquery(name='q',
         scope=[':honeydew'],
         strict=0,
         expression='buildfiles(//honeydew:all)')
EOF

  bazel build //honeydew:q >& $TEST_log || fail "Expected success"
  cat bazel-bin/honeydew/q > $TEST_log
  expect_log_once "^//honeydew:BUILD$"
}

function test_genquery_bad_output_formatter() {
  mkdir -p starfruit
  cat > starfruit/BUILD <<EOF
sh_library(name = 'starfruit')
genquery(name='q',
         scope=['//starfruit'],
         expression='//starfruit',
         opts = ["--output=blargh"],)
EOF

  local expected_error_msg="in genquery rule //starfruit:q: Invalid output format 'blargh'. Valid values are: label, label_kind, build, minrank, maxrank, package, location, graph, xml, proto, streamed_jsonproto, "
  bazel build //starfruit:q >& $TEST_log && fail "Expected failure"
  expect_log "$expected_error_msg"
}

function test_graphless_genquery_somepath_output_in_dependency_order() {
  mkdir -p foo
  cat > foo/BUILD <<EOF
sh_library(name = "c", deps = [":b"])
sh_library(name = "b", deps = [":a"])
sh_library(name = "a")
genquery(name = "somepath",
         scope = ['//foo:c'],
         expression = "somepath(//foo:c, //foo:a)")
genquery(name = "allpaths",
         scope = ['//foo:c'],
         expression = "allpaths(//foo:c, //foo:a)")
EOF

  # Somepath in genquery needs to output in dependency order instead of
  # lexicographical order (which is the default for all other expressions)
  cat > foo/expected_sp_output <<EOF
//foo:c
//foo:b
//foo:a
EOF
  bazel build //foo:somepath >& $TEST_log || fail "Expected success"
  assert_equals "$(cat foo/expected_sp_output)" "$(cat bazel-bin/foo/somepath)"

  # Allpaths in genquery outputs in lexicographical order (just like all other
  # expressions) as the dependency order is not preserved during computation
  # in GraphlessBlazeQueryEnvironment
  cat > foo/expected_ap_output <<EOF
//foo:a
//foo:b
//foo:c
EOF
  bazel build //foo:allpaths >& $TEST_log || fail "Expected success"
  assert_equals "$(cat foo/expected_ap_output)" "$(cat bazel-bin/foo/allpaths)"
}

function test_graphless_query_matches_graphless_genquery_output() {
  rm -rf foo
  mkdir -p foo
  cat > foo/BUILD <<EOF
sh_library(name = "b", deps = [":c"])
sh_library(name = "c", deps = [":a"])
sh_library(name = "a")
genquery(
    name = "q",
    expression = "deps(//foo:b)",
    scope = ["//foo:b"],
)
EOF

  cat > foo/expected_lexicographical_result <<EOF
//foo:a
//foo:b
//foo:c
EOF

  # Genquery uses a graphless blaze environment by default.
  bazel build //foo:q || fail "Expected success"

  # The --incompatible_lexicographical_output flag is used to
  # switch order_output=auto to use graphless query and output in
  # lexicographical order.
  bazel query --incompatible_lexicographical_output \
      "deps(//foo:b)" | grep foo >& foo/query_output || fail "Expected success"

  # The outputs of graphless query and graphless genquery should be the same and
  # should both be in lexicographical order.
  assert_equals \
      "$(cat foo/expected_lexicographical_result)" "$(cat foo/query_output)"
  assert_equals \
      "$(cat foo/expected_lexicographical_result)" "$(cat bazel-bin/foo/q)"
}

function test_graphless_query_resilient_to_cycles() {
  rm -rf foo
  mkdir -p foo
  cat > foo/BUILD <<EOF
sh_library(name = "a", deps = [":b"])
sh_library(name = "b", deps = [":c"])
sh_library(name = "c", deps = [":a"])
sh_library(name = "d")
EOF

  for command in \
      "somepath(//foo:a, //foo:c)" \
      "somepath(//foo:a, //foo:d)" \
      "somepath(//foo:c, //foo:d)" \
      "allpaths(//foo:a, //foo:d)" \
      "deps(//foo:a)" \
      "rdeps(//foo:a, //foo:d)" \
      "same_pkg_direct_rdeps(//foo:b)"
  do
    bazel query --experimental_graphless_query=true \
        "$command" || fail "Expected success"
  done
}

function test_lexicographical_output_does_not_affect_order_output_no() {
  rm -rf foo
  mkdir -p foo
  cat > foo/BUILD <<EOF
sh_library(name = "b", deps = [":c"])
sh_library(name = "c", deps = [":a"])
sh_library(name = "a")
genquery(
    name = "q",
    expression = "deps(//foo:b)",
    scope = ["//foo:b"],
)
EOF

  bazel query --order_output=no \
      "deps(//foo:b)" | grep foo >& foo/query_output \
      || fail "Expected success"
  bazel query --order_output=no \
      --incompatible_lexicographical_output \
      "deps(//foo:b)" | grep foo >& foo/lex_query_output \
      || fail "Expected success"

  # The --incompatible_lexicographical_output flag should not affect query
  # order_output=no. Note that there is a chance it may output in
  # lexicographical order since it is unordered.
  assert_equals \
      "$(cat foo/query_output)" "$(cat foo/lex_query_output)"
}

function test_lexicographical_output_does_not_affect_somepath() {
  rm -rf foo
  mkdir -p foo
  cat > foo/BUILD <<EOF
sh_library(name = "b", deps = [":c"])
sh_library(name = "c", deps = [":a"])
sh_library(name = "a")
EOF

  cat > foo/expected_deps_output <<EOF
//foo:b
//foo:c
//foo:a
EOF

  bazel query --incompatible_lexicographical_output \
      "somepath(//foo:b, //foo:a)" | grep foo >& foo/query_output

  assert_equals \
      "$(cat foo/expected_deps_output)" "$(cat foo/query_output)"
}

# Regression test for https://github.com/bazelbuild/bazel/issues/8582.
function test_rbuildfiles_can_handle_non_loading_phase_edges() {
  mkdir -p foo
  # When we have a package //foo whose BUILD file
  cat > foo/BUILD <<EOF
  # Defines a target //foo:foo, with input file foo/foo.sh,
sh_library(name = 'foo', srcs = ['foo.sh'])
EOF
  # And foo/foo.sh has some initial contents.
  echo "bar" > foo/foo.sh

  # Then `rbuildfiles` correctly thinks //foo "depends" on foo/BUILD,
  bazel query \
    --universe_scope=//foo:foo \
    --order_output=no \
    "rbuildfiles(foo/BUILD)" >& $TEST_log || fail "Expected success"
  expect_log //foo:BUILD
  # And that no package "depends" on foo/foo.sh.
  bazel query \
    --universe_scope=//foo:foo \
    --order_output=no \
    "rbuildfiles(foo/foo.sh)" >& $TEST_log || fail "Expected success"
  expect_not_log //foo:BUILD

  # But then, after we *build* //foo:foo (thus priming the Skyframe graph with
  # a transitive dep path from the input ArtifactValue for foo/foo.sh to the
  # FileStateValue for foo/foo.sh),
  bazel build //foo:foo >& $TEST_log || fail "Expected success"

  # And we modify the contents of foo/foo.sh,
  echo "baz" > foo/foo.sh

  # And we again do a `rbuildfiles(foo/foo.sh)`, Bazel again correctly thinks
  # no package "depends" on foo/foo.sh.
  #
  # Historically, Bazel would crash here because it would first invalidate the
  # UTC of FileStateValue for foo/foo.sh (invalidating the ArtifactValue for
  # foo/foo.sh), and then evaluate the DTC of the *skyquery-land* universe of
  # //foo:foo (*not* evaluating that ArtifactValue), and then observe an rdep
  # edge on the not-done ArtifactValue, and then crash.
  bazel query \
    --universe_scope=//foo:foo \
    --order_output=no \
    "rbuildfiles(foo/foo.sh)" >& $TEST_log || fail "Expected success"
  expect_not_log //foo:BUILD
}

function test_infer_universe_scope_considers_only_target_patterns() {
  # When we have three targets //a:a, //b:b, //c:c, with //b:b depending
  # directly on //a:a, and //c:c depending directly on //b:b.
  mkdir -p a b c
  echo "sh_library(name = 'a')" > a/BUILD
  echo "sh_library(name = 'b', deps = ['//a:a'])" > b/BUILD
  echo "sh_library(name = 'c', deps = ['//b:b'])" > c/BUILD

  # And we run 'bazel query' with both --infer_universe_scope and
  # --order_output=no set (making this invocation eligible for SkyQuery), with
  # a query expression of "allrdeps(//a)",
  bazel query \
    --infer_universe_scope \
    --order_output=no \
    "allrdeps(//a)" >& $TEST_log || fail "Expected success"
  # Then the invocation succeeds (confirming SkyQuery mode was enabled),
  # And also the result contains //a:a
  expect_log //a:a
  # But it does not contain //b:c or //c:c, because they aren't contained in
  # the inferred universe scope.
  expect_not_log //b:b
  expect_not_log //c:c

  # And also, when we run 'bazel clean' (just to be sure, since the semantics
  # of SkyQuery depends on the state of the Bazel server)
  bazel clean >& $TEST_log || fail "Expected success"

  # And then we run 'bazel query' again, with the same options as last time,
  # but this time with a query expression that contains target patterns whose
  # DTC covers //b:b and //c:c too,
  bazel query \
    --infer_universe_scope --order_output=no \
    "allrdeps(//a) ^ deps(//c:c)" >& $TEST_log || fail "Expected success"
  # Then the invocation also succeeds (confirming SkyQuery mode was enabled),
  # But this time the result contains all three targets.
  expect_log //a:a
  expect_log //b:b
  expect_log //c:c
}

function test_bogus_visibility() {
  mkdir -p foo bar || fail "Couldn't make directories"
  cat <<'EOF' > foo/BUILD || fail "Couldn't write BUILD file"
sh_library(name = 'a', visibility = ['//bad:visibility', '//bar:__pkg__'])
sh_library(name = 'b', visibility = ['//visibility:public'])
sh_library(name = 'c', visibility = ['//bad:visibility'])
EOF
  touch bar/BUILD || fail "Couldn't write BUILD file"
  ! bazel query --keep_going --output=label_kind \
      'visible(//bar:BUILD, //foo:a + //foo:b + //foo:c)' \
      >& "$TEST_log" || fail "Expected failure"
  expect_log "no such package 'bad'"
  expect_log "keep_going specified, ignoring errors. Results may be inaccurate"
  expect_log "sh_library rule //foo:a"
  expect_log "sh_library rule //foo:b"
  expect_not_log "sh_library rule //foo:c"
}

function test_infer_universe_scope_defers_to_universe_scope_value() {
  # When we have two targets, in two different packages, that do not depend on
  # each other,
  mkdir -p a b
  echo "sh_library(name = 'a')" > a/BUILD
  echo "sh_library(name = 'b')" > b/BUILD

  # And we run 'bazel query' with a --universe_scope value that covers only one
  # of the targets but a query expression that has target patterns for both
  # targets, but also pass --infer_universe_scope,
  bazel query \
    --universe_scope=//a:a \
    --infer_universe_scope \
    --order_output=no \
    "//a:a + //b:b" >& $TEST_log && fail "Expected failure"
  # Then the query invocation fails, because of the missing target, thus
  # verifying that our value of --universe_scope was respected and
  # --infer_universe_scope was ignored.
  expect_log "Evaluation of subquery \"//b:b\" failed"

  # And then, when we run 'bazel clean' (just to be sure, since the semantics
  # of SkyQuery depends on the state of the Bazel server)
  bazel clean >& $TEST_log || fail "Expected success"

  # And we run 'bazel query', this time without setting --universe_scope, but
  # with --infer_universe_scope and the same query expression,
  bazel query \
    --infer_universe_scope \
    --order_output=no \
    "//a:a + //b:b" >& $TEST_log || fail "Expected success"
  # Then the query expression succeeds, because both targets are in the
  # inferred universe.
  expect_log //a:a
  expect_log //b:b
}

function test_query_failure_exit_code_behavior() {
  bazel query //targetdoesnotexist >& "$TEST_log" && fail "Expected failure"
  exit_code="$?"
  assert_equals 7 "$exit_code"
  bazel query --keep_going //targetdoesnotexist >& "$TEST_log" \
      && fail "Expected failure"
  exit_code="$?"
  assert_equals 3 "$exit_code"

  bazel query '$x' >& "$TEST_log" && fail "Expected failure"
  exit_code="$?"
  assert_equals 7 "$exit_code"
  bazel query --keep_going '$x' >& "$TEST_log" && fail "Expected failure"
  exit_code="$?"
  assert_equals 7 "$exit_code"
}

function test_query_environment_keep_going_does_not_fail() {
  rm -rf foo
  mkdir -p foo
  cat > foo/BUILD <<EOF
sh_library(name = "a", deps = [":b", "//other:doesnotexist"])
sh_library(name = "b")
EOF

  # Ensure that --keep_going works for both graphless and non-graphless blaze
  # query environments for each function.
  for incompatible in "--incompatible" "--noincompatible"
  do
    for command in \
        "somepath(//foo:a, //foo:b)" \
        "deps(//foo:a)" \
        "rdeps(//foo:a, //foo:b)" \
        "allpaths(//foo:a, //foo:b)"
    do
      bazel query "$incompatible"_lexicographical_output --keep_going \
        --output=label_kind "$command" \
        >& "$TEST_log" && fail "Expected failure"
      exit_code="$?"
      assert_equals 3 $exit_code
      expect_log "sh_library rule //foo:a"
      expect_log "sh_library rule //foo:b"
      expect_log "errors were encountered while computing transitive closure"
    done
  done
}

function test_unnecessary_external_workspaces_not_loaded() {
  cat > MODULE.bazel <<'EOF'
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "notthere",
    path = "/nope",
)
EOF
  cat > BUILD <<'EOF'
filegroup(
    name = "something",
    srcs = ["@notthere"],
)
EOF
  bazel query '//:*' || fail "Expected success"
}

function test_query_sees_aspect_hints_deps_on_starlark_rule() {
  local package="aspect_hints"
  mkdir -p "${package}"

  cat > "${package}/custom_rule.bzl" <<EOF

def _rule_impl(ctx):
    return []

custom_rule = rule(
    implementation = _rule_impl,
    attrs = {
        "deps": attr.label_list(),
    }
)
EOF

  cat > "${package}/BUILD" <<EOF
load("//${package}:custom_rule.bzl", "custom_rule")

custom_rule(name = "hint")

custom_rule(
    name = "foo",
    deps = [":bar"],
)
custom_rule(
    name = "bar",
    aspect_hints = [":hint"],
)
EOF

  bazel query "somepath(//${package}:foo, //${package}:hint)"  >& $TEST_log \
    || fail "Expected success"

  expect_log "//${package}:hint"
}

function test_same_pkg_direct_rdeps_loads_only_inputs_packages() {
  mkdir -p "pkg1"
  mkdir -p "pkg2"
  mkdir -p "pkg3"

  cat > "pkg1/BUILD" <<EOF
sh_library(name = "t1", deps = [":t2", "//pkg2:t3"])
sh_library(name = "t2")
EOF

  cat > "pkg2/BUILD" <<EOF
sh_library(name = "t3")
EOF

  cat > "pkg3/BUILD" <<EOF
sh_library(name = "t4", deps = [":t5"])
sh_library(name = "t5")
EOF

  bazel query --experimental_ui_debug_all_events \
     "same_pkg_direct_rdeps(//pkg1:t2+//pkg3:t5)"  >& $TEST_log \
    || fail "Expected success"

  expect_log "Loading package: pkg1"
  expect_log "Loading package: pkg3"
  # For graphless query mode, pkg2 should not be loaded because
  # same_pkg_direct_rdeps only cares about the targets in the same package
  # as its inputs.
  expect_not_log "Loading package: pkg2"
  # the result of "same_pkg_direct_rdeps(//pkg1:t2+//pkg3:t5)"
  expect_log "//pkg1:t1"
  expect_log "//pkg3:t4"
}

function test_basic_query_streamed_jsonproto() {
  local pkg="${FUNCNAME[0]}"
  mkdir -p "$pkg" || fail "mkdir -p $pkg"
  cat > "$pkg/BUILD" <<'EOF'
genrule(
    name = "bar",
    srcs = ["dummy.txt"],
    outs = ["bar_out.txt"],
    cmd = "echo unused > $(OUTS)",
)
genrule(
    name = "foo",
    srcs = ["dummy.txt"],
    outs = ["foo_out.txt"],
    cmd = "echo unused > $(OUTS)",
)
EOF
  bazel query --output=streamed_jsonproto --noimplicit_deps "//$pkg/..." > output 2> "$TEST_log" \
    || fail "Expected success"
  cat output >> "$TEST_log"

  # Verify that the appropriate attributes were included.

  foo_line_number=$(grep -n "foo" output | cut -d':' -f1)
  bar_line_number=$(grep -n "bar" output | cut -d':' -f1)

  foo_ndjson_line=$(sed -n "${foo_line_number}p" output)
  bar_ndjson_line=$(sed -n "${bar_line_number}p" output)

  echo "$foo_ndjson_line" > foo_ndjson_file
  echo "$bar_ndjson_line" > bar_ndjson_file

  assert_contains "\"ruleClass\":\"genrule\"" foo_ndjson_file
  assert_contains "\"name\":\"//$pkg:foo\"" foo_ndjson_file
  assert_contains "\"ruleInput\":\[\"//$pkg:dummy.txt\"\]" foo_ndjson_file
  assert_contains "\"ruleOutput\":\[\"//$pkg:foo_out.txt\"\]" foo_ndjson_file
  assert_contains "echo unused" foo_ndjson_file

  assert_contains "\"ruleClass\":\"genrule\"" bar_ndjson_file
  assert_contains "\"name\":\"//$pkg:bar\"" bar_ndjson_file
  assert_contains "\"ruleInput\":\[\"//$pkg:dummy.txt\"\]" bar_ndjson_file
  assert_contains "\"ruleOutput\":\[\"//$pkg:bar_out.txt\"\]" bar_ndjson_file
  assert_contains "echo unused" bar_ndjson_file
}

function test_query_factored_graph_output() {
  mkdir -p foo
  cat > foo/BUILD <<'EOF'
sh_binary(
    name = "a1",
    srcs = [
        "a.sh",
    ],
    deps = [
        ":b",
        ":c1",
        ":c2",
    ],
)

sh_binary(
    name = "a2",
    srcs = [
        "a.sh",
    ],
    deps = [
        ":b",
        ":c1",
        ":c2",
    ],
)

sh_binary(
    name = "b",
    srcs = ["b.sh"],
)

sh_binary(
    name = "c1",
    srcs = ["c.sh"],
)

sh_binary(
    name = "c2",
    srcs = ["c.sh"],
)
EOF
  bazel query --output=graph \
      --graph:factored \
      --notool_deps \
      "deps(//foo:a1 + //foo:a2)" > "$TEST_log" \
      || fail "Expected success"
  # Expected factored graph.
  #      (a1,a2)
  #     /   \   \
  #   a.sh   b   (c1,c2)
  #         /     \
  #       b.sh    c.sh
  expect_log "\"//foo:a[12]\\\\n//foo:a[12]\"$"
  expect_log "\"//foo:a[12]\\\\n//foo:a[12]\" -> \"//foo:a.sh\"$"
  expect_log "\"//foo:a[12]\\\\n//foo:a[12]\" -> \"//foo:b\"$"
  expect_log "\"//foo:a[12]\\\\n//foo:a[12]\" -> \"//foo:c[12]\\\n//foo:c[12]\"$"
  expect_log "\"//foo:a.sh\"$"
  expect_log "\"//foo:b\"$"
  expect_log "\"//foo:b\" -> \"//foo:b.sh\"$"
  expect_log "\"//foo:b.sh\"$"
  expect_log "\"//foo:c[12]\\\\n//foo:c[12]\" -> \"//foo:c.sh\"$"
  expect_log "\"//foo:c.sh\"$"
}

function test_query_non_factored_graph_output() {
  mkdir -p foo
  cat > foo/BUILD <<'EOF'
sh_binary(
    name = "a1",
    srcs = [
        "a.sh",
    ],
    deps = [
        ":b",
        ":c1",
        ":c2",
    ],
)

sh_binary(
    name = "a2",
    srcs = [
        "a.sh",
    ],
    deps = [
        ":b",
        ":c1",
        ":c2",
    ],
)

sh_binary(
    name = "b",
    srcs = ["b.sh"],
)

sh_binary(
    name = "c1",
    srcs = ["c.sh"],
)

sh_binary(
    name = "c2",
    srcs = ["c.sh"],
)
EOF
  bazel query --output=graph \
      --nograph:factored \
      --notool_deps \
      "deps(//foo:a1 + //foo:a2)" >& "$TEST_log" \
      || fail "Expected success"


  # Expected non-factored graph (combination of all the edges below):
  #   a1   a2    a1   a2
  #    \  /        \  /
  #    a.sh          b
  #                 /
  #     a1         b.sh
  #   / a2 \
  #  / /  \ \
  #  c1    c2
  #   \    /
  #    c.sh
  expect_log "\"//foo:a1\"$"
  expect_log "\"//foo:a2\"$"
  expect_log "\"//foo:a1\" -> \"//foo:a.sh\"$"
  expect_log "\"//foo:a2\" -> \"//foo:a.sh\"$"
  expect_log "\"//foo:a.sh\"$"
  expect_log "\"//foo:a1\" -> \"//foo:b\"$"
  expect_log "\"//foo:a2\" -> \"//foo:b\"$"
  expect_log "\"//foo:b\"$"
  expect_log "\"//foo:b\" -> \"//foo:b.sh\"$"

  expect_log "\"//foo:a1\" -> \"//foo:c1\"$"
  expect_log "\"//foo:a1\" -> \"//foo:c2\"$"
  expect_log "\"//foo:a2\" -> \"//foo:c1\"$"
  expect_log "\"//foo:a2\" -> \"//foo:c2\"$"
  expect_log "\"//foo:c1\"$"
  expect_log "\"//foo:c2\"$"
  expect_log "\"//foo:c1\" -> \"//foo:c.sh\"$"
  expect_log "\"//foo:c2\" -> \"//foo:c.sh\"$"
  expect_log "\"//foo:c.sh\"$"
}

function test_proto_non_ascii_attributes() {
  rm -rf foo
  mkdir -p foo
  cat > foo/defs.bzl <<'EOF'
def _impl(ctx): pass
r = rule(
  implementation = _impl,
  attrs = {
    "label": attr.label(),
    "label_keyed_string_dict": attr.label_keyed_string_dict(),
    "label_list": attr.label_list(),
    "output": attr.output(),
    "output_list": attr.output_list(),
    "string": attr.string(),
    "string_dict": attr.string_dict(),
    "string_keyed_label_dict": attr.string_keyed_label_dict(),
    "string_list": attr.string_list(),
    "string_list_dict": attr.string_list_dict(),
  },
)

def test_case(name, with_select):
  def maybe_select(x):
    if with_select:
      return select({"//conditions:default": x})
    return x

  r(
    name = name,
    label = maybe_select("leaf🌱"),
    label_keyed_string_dict = maybe_select({"fire🔥": "ice❄️"}),
    label_list = maybe_select(["star⭐"]),
    output = name + "flower🌸",
    output_list = [name + "party🎉"],
    string = maybe_select("ball⚽"),
    string_dict = maybe_select({"sun☀️": "moon🌙"}),
    string_keyed_label_dict = maybe_select({"heart❤️": "skull💀"}),
    string_list = maybe_select(["rocket🚀"]),
    string_list_dict = maybe_select({"dog🐶": ["cat🐱"]}),
  )
EOF
  cat > foo/BUILD <<'EOF'
load(":defs.bzl", "test_case")
test_case(name = "without_select", with_select = False)
test_case(name = "with_select", with_select = True)
EOF

  declare -a items=("leaf🌱" "fire🔥" "ice❄️" "star⭐" "flower🌸" "party🎉"
    "ball⚽" "sun☀️" "moon🌙" "heart❤️" "skull💀" "rocket🚀" "dog🐶" "cat🐱")

  bazel query --output=proto //foo:without_select >& $TEST_log \
      || fail "Expected success"

  for x in "${items[@]}"; do
    grep -q "$x" $TEST_log || fail "Expected $x in query output for //foo:without_select"
  done

  bazel query --output=proto //foo:with_select >& $TEST_log \
      || fail "Expected success"

  for x in "${items[@]}"; do
    grep -q "$x" $TEST_log || fail "Expected $x in query output for //foo:with_select"
  done
}

run_suite "${PRODUCT_NAME} query tests"
