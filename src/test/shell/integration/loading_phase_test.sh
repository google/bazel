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
#
# loading_phase_tests.sh: miscellaneous integration tests of Bazel,
# that use only the loading or analysis phases.
#

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

if "$is_windows"; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

# Our tests use the static crosstool, so make it the default.
# add_to_bazelrc "build --crosstool_top=@bazel_tools//tools/cpp:default-toolchain"

output_base=$TEST_TMPDIR/out
TEST_stderr=$(dirname $TEST_log)/stderr

#### HELPER FUNCTIONS ##################################################

if ! type try_with_timeout >&/dev/null; then
  # Bazel's testenv.sh defines try_with_timeout but the Google-internal version
  # uses a different testenv.sh.
  function try_with_timeout() { $* ; }
fi

function set_up() {
    cd ${WORKSPACE_DIR}
}

function tear_down() {
  try_with_timeout bazel shutdown
}

#### TESTS #############################################################

function test_query_buildfiles_with_load() {
    local -r pkg="${FUNCNAME}"
    mkdir -p "$pkg" || fail "could not create \"$pkg\""

    mkdir -p $pkg/x || fail "mkdir $pkg/x failed"
    echo "load('//$pkg/y:rules.bzl', 'a')" >$pkg/x/BUILD
    echo "cc_library(name='x')"   >>$pkg/x/BUILD
    mkdir -p $pkg/y || fail "mkdir $pkg/y failed"
    touch $pkg/y/BUILD
    echo "a=1" >$pkg/y/rules.bzl

    bazel query --noshow_progress "buildfiles(//$pkg/x)" >$TEST_log ||
        fail "Expected success"
    expect_log //$pkg/x:BUILD
    expect_log //$pkg/y:BUILD
    expect_log //$pkg/y:rules.bzl

    # null terminated:
    bazel query --noshow_progress --null "buildfiles(//$pkg/x)" >$pkg/null.log ||
        fail "Expected null success"
    printf "//$pkg/y:rules.bzl\0//$pkg/y:BUILD\0//$pkg/x:BUILD\0" >$pkg/null.ref.log
    cmp $pkg/null.ref.log $pkg/null.log || fail "Expected match"

    # Missing skylark file:
    rm -f $pkg/y/rules.bzl
    bazel query --noshow_progress "buildfiles(//$pkg/x)" 2>$TEST_log &&
        fail "Expected error"
    expect_log "Unable to load file '//$pkg/y:rules.bzl'"
}

# Regression test for:
# "Skyframe does not build targets that transitively depend on non-rule targets
# that live in packages with errors".
function test_non_error_target_in_bad_pkg() {
    local -r pkg="${FUNCNAME}"
    mkdir -p "$pkg" || fail "could not create \"$pkg\""

    mkdir -p $pkg/a || fail "mkdir $pkg/a failed"
    mkdir -p $pkg/b || fail "mkdir $pkg/b failed"

    echo "sh_library(name = 'a', data = ['//$pkg/b'])" > $pkg/a/BUILD
    echo "exports_files(['b'])" > $pkg/b/BUILD
    echo "genrule(name='r1', cmd = '', outs = ['conflict'])" >> $pkg/b/BUILD
    echo "genrule(name='r2', cmd = '', outs = ['conflict'])" >> $pkg/b/BUILD

    bazel build --nobuild -k //$pkg/a >& $TEST_log && fail "Expected failure"
    expect_log "'conflict' in rule"
    expect_not_log "Loading failed"
    expect_log "but there were loading phase errors"
    expect_not_log "Loading succeeded for only"
}

# This is a regression test to make sure that none of the bazel
# commands has an incompatible set of @Options annotations.  In the
# past, options have been declared multiple times, making a command
# unusable.
function test_options_errors() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  # Enumerate bazel commands...
  bazel help 2>/dev/null |
      grep  '^  [a-z]' |
      grep -v '^  '${PRODUCT_NAME}' ' |
      awk '{print $1}' |
  while read command; do
    bazel $command >$TEST_log 2>&1 || true
    # Mustn't crash in the options package:
    expect_not_log "Duplicate option name"
    expect_not_log "at com.google.devtools.build.lib"
    expect_not_log "lib.util.options.*Exception"
  done
}

function test_bazelrc_option() {
    local -r pkg="${FUNCNAME}"
    mkdir -p "$pkg" || fail "could not create \"$pkg\""

    cp "${bazelrc}" ".${PRODUCT_NAME}rc" || true

    echo "build --subcommands" >>".${PRODUCT_NAME}rc"    # default bazelrc
    $PATH_TO_BAZEL_BIN info --announce_rc >/dev/null 2>$TEST_log
    expect_log "Reading.*$pkg[/\\\\].${PRODUCT_NAME}rc:
.*--subcommands"

    cp .${PRODUCT_NAME}rc $pkg/foo
    echo "build --nosubcommands"   >>$pkg/foo         # non-default bazelrc
    $PATH_TO_BAZEL_BIN --${PRODUCT_NAME}rc=$pkg/foo info --announce_rc >/dev/null \
      2>$TEST_log
    expect_log "Reading.*$pkg[/\\\\]foo:
.*--nosubcommands"
}

# This exercises the production-code assertion in AbstractCommand.java
# that all help texts mention their %{options}.
function test_all_help_topics_succeed() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  topics=($(bazel help 2>/dev/null |
              grep '^  [a-z]' |
              grep -v '^  '${PRODUCT_NAME}' ' |
              awk '{print $1}') \
          startup_options \
          target-syntax)
  for topic in "${topics[@]}"; do
    bazel help $topic >$TEST_log 2>&1 || {
       fail "help $topic failed"
       expect_not_log .  # print the log
    }
  done
  [ ${#topics[@]} -gt 15 ] || fail "Hmmm: not many topics: ${topics[*]}."
}

# Regression for "Sticky error during analysis phase when input is cyclic".
function test_regress_cycle_during_analysis_phase() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  mkdir -p $pkg/cycle $pkg/main
  cat >$pkg/main/BUILD <<EOF
genrule(name='mygenrule', outs=['baz.h'], srcs=['//$pkg/cycle:foo.h'], cmd=':')
EOF
  cat >$pkg/cycle/BUILD <<EOF
genrule(name='foo.h', outs=['bar.h'], srcs=['foo.h'], cmd=':')
EOF
  bazel build --nobuild //$pkg/cycle:foo.h >$TEST_log 2>&1 || true
  expect_log "in genrule rule //$pkg/cycle:foo.h: .*dependency graph"
  expect_log "//$pkg/cycle:foo.h.*self-edge"

  bazel build --nobuild //$pkg/main:mygenrule >$TEST_log 2>&1 || true
  expect_log "in genrule rule //$pkg/cycle:foo.h: .*dependency graph"
  expect_log "//$pkg/cycle:foo.h.*self-edge"

  bazel build --nobuild //$pkg/cycle:foo.h >$TEST_log 2>&1 || true
  expect_log "in genrule rule //$pkg/cycle:foo.h: .*dependency graph"
  expect_log "//$pkg/cycle:foo.h.*self-edge"
}

# glob function should not return values that are outside the package
function test_glob_with_subpackage() {
    local -r pkg="${FUNCNAME}"
    mkdir -p "$pkg" || fail "could not create \"$pkg\""

    mkdir -p $pkg/p/subpkg || fail "mkdir $pkg/p/subpkg failed"
    mkdir -p $pkg/p/dir || fail "mkdir $pkg/p/dir failed"

    echo "exports_files(glob(['**/*.txt']))" >$pkg/p/BUILD
    echo "# Empty" >$pkg/p/subpkg/BUILD

    echo "$pkg/p/t1.txt" > $pkg/p/t1.txt
    echo "$pkg/p/dir/t2.txt" > $pkg/p/dir/t2.txt
    echo "$pkg/p/subpkg/t3.txt" > $pkg/p/subpkg/t3.txt

    bazel query "$pkg/p:*" >$TEST_log || fail "Expected success"
    expect_log "//$pkg/p:t1\.txt"
    expect_log "//$pkg/p:dir/t2\.txt"
    expect_log "//$pkg/p:BUILD"
    expect_not_log 't3\.txt'
    assert_equals "3" $(wc -l "$TEST_log")

    # glob returns an empty list, because t3.txt is outside the package
    echo "exports_files(glob(['subpkg/t3.txt']))" >$pkg/p/BUILD
    bazel query "$pkg/p:*" -k >$TEST_log || fail "Expected success"
    expect_log "//$pkg/p:BUILD"
    assert_equals "1" $(wc -l "$TEST_log")

    # same test, with a nonexisting file
    echo "exports_files(glob(['subpkg/no_glob.txt']))" >$pkg/p/BUILD
    bazel query "$pkg/p:*" -k >$TEST_log || fail "Expected success"
    expect_log "//$pkg/p:BUILD"
    assert_equals "1" $(wc -l "$TEST_log")

    # Non-recursive wildcard gives the same result as the recursive wildcard
    echo "exports_files(glob(['*.txt', '*/*.txt']))" >$pkg/p/BUILD
    bazel query "$pkg/p:*" >$TEST_log || fail "Expected success"
    expect_log "//$pkg/p:t1\.txt"
    expect_log "//$pkg/p:dir/t2\.txt"
    expect_log "//$pkg/p:BUILD"
    expect_not_log 't3\.txt'
    assert_equals "3" $(wc -l "$TEST_log")
}

function test_glob_with_subpackage2() {
    local -r pkg="${FUNCNAME}"
    mkdir -p "$pkg" || fail "could not create \"$pkg\""

    mkdir -p $pkg/p/q/subpkg || fail "mkdir $pkg/p/q/subpkg failed"
    mkdir -p $pkg/p/q/dir || fail "mkdir $pkg/p/q/dir failed"

    echo "exports_files(glob(['**/*.txt']))" >$pkg/p/q/BUILD
    echo "# Empty" >$pkg/p/q/subpkg/BUILD

    echo "$pkg/p/q/t1.txt" > $pkg/p/q/t1.txt
    echo "$pkg/p/q/dir/t2.txt" > $pkg/p/q/dir/t2.txt
    echo "$pkg/p/q/subpkg/t3.txt" > $pkg/p/q/subpkg/t3.txt

    bazel query "$pkg/p/q:*" >$TEST_log || fail "Expected success"
    expect_log "//$pkg/p/q:t1\.txt"
    expect_log "//$pkg/p/q:dir/t2\.txt"
    expect_log "//$pkg/p/q:BUILD"
    expect_not_log 't3\.txt'
    assert_equals "3" $(wc -l "$TEST_log")
}

# Regression test for bug "ASTFileLookupFunction has an unnoted
# dependency on the PathPackageLocator".
function test_incremental_deleting_package_roots() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  local other_root=$TEST_TMPDIR/other_root/${WORKSPACE_NAME}
  mkdir -p $other_root/$pkg/a
  touch $other_root/WORKSPACE
  echo 'sh_library(name="external")' > $other_root/$pkg/a/BUILD
  mkdir -p $pkg/a
  echo 'sh_library(name="internal")' > $pkg/a/BUILD

  bazel query --package_path=$other_root:. $pkg/a:all >& $TEST_log \
      || fail "Expected success"
  expect_log "//$pkg/a:external"
  expect_not_log "//$pkg/a:internal"
  rm -r $other_root
  bazel query --package_path=$other_root:. $pkg/a:all >& $TEST_log \
      || fail "Expected success"
  expect_log "//$pkg/a:internal"
  expect_not_log "//$pkg/a:external"
  mkdir -p $other_root
  bazel query --package_path=$other_root:. $pkg/a:all >& $TEST_log \
      || fail "Expected success"
  expect_log "//$pkg/a:internal"
  expect_not_log "//$pkg/a:external"
}

function test_no_package_loading_on_benign_workspace_file_changes() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  mkdir $pkg/foo

  echo 'workspace(name="wsname1")' > WORKSPACE
  echo 'sh_library(name="shname1")' > $pkg/foo/BUILD
  bazel query --experimental_ui_debug_all_events //$pkg/foo:all >& "$TEST_log" \
      || fail "Expected success"
  expect_log "Loading package: $pkg/foo"
  expect_log "//$pkg/foo:shname1"

  echo 'sh_library(name="shname2")' > $pkg/foo/BUILD
  bazel query --experimental_ui_debug_all_events //$pkg/foo:all >& "$TEST_log" \
      || fail "Expected success"
  expect_log "Loading package: $pkg/foo"
  expect_log "//$pkg/foo:shname2"

  # Test that comment changes do not cause package reloading
  echo '#benign comment' >> WORKSPACE
  bazel query --experimental_ui_debug_all_events //$pkg/foo:all >& "$TEST_log" \
      || fail "Expected success"
  expect_not_log "Loading package: $pkg/foo"
  expect_log "//$pkg/foo:shname2"

  echo 'workspace(name="wsname2")' > WORKSPACE
  bazel query --experimental_ui_debug_all_events //$pkg/foo:all >& "$TEST_log" \
      || fail "Expected success"
  expect_log "Loading package: $pkg/foo"
  expect_log "//$pkg/foo:shname2"
}

function test_disallow_load_labels_to_cross_package_boundaries() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  mkdir "$pkg"/foo
  echo "load(\"//$pkg/foo/a:b/b.bzl\", \"b\")" > "$pkg"/foo/BUILD
  mkdir -p "$pkg"/foo/a/b
  touch "$pkg"/foo/a/BUILD
  touch "$pkg"/foo/a/b/BUILD
  echo "b = 42" > "$pkg"/foo/a/b/b.bzl

  bazel query "$pkg/foo:BUILD" >& "$TEST_log" && fail "Expected failure"
  expect_log "Label '//$pkg/foo/a:b/b.bzl' is invalid because '$pkg/foo/a/b' is a subpackage"
}

function test_package_loading_errors_in_target_parsing() {
  mkdir bad || fail "mkdir failed"
  echo "nope" > bad/BUILD || fail "echo failed"

  for keep_going in "--keep_going" "--nokeep_going"
  do
    for target_pattern in "//bad:BUILD" "//bad:all" "//bad/..."
    do
      bazel build --nobuild "$keep_going" "$target_pattern" >& "$TEST_log" \
        && fail "Expected failure"
      expect_log "Build did NOT complete successfully"
    done
  done
}

function test_severe_package_loading_errors_via_test_suites_in_target_parsing() {

  mkdir bad || fail "mkdir failed"
  cat > bad/BUILD <<EOF
load("//bad:bad.bzl", "some_val")
sh_test(name = "some_test", srcs = ["test.sh"])
EOF

  cat > bad/bad.bzl <<EOF
fail()
EOF

  mkdir dependsonbad || fail "mkdir failed"
  cat > dependsonbad/BUILD <<EOF
test_suite(name = "suite", tests = ["//bad:some_test"])
EOF

  for keep_going in "--keep_going" "--nokeep_going"
  do
    bazel build --nobuild "$keep_going" //dependsonbad:suite >& "$TEST_log" \
      && fail "Expected failure"
    local exit_code=$?
    assert_equals 1 "$exit_code"
    expect_log "Build did NOT complete successfully"
    expect_not_log "Illegal"
  done
}

function test_illegal_glob_exclude_pattern_in_bzl() {
  mkdir badglob-bzl || fail "mkdir failed"
  cat > badglob-bzl/BUILD <<EOF
load("//badglob-bzl:badglob.bzl", "f")
f()
EOF
  cat > badglob-bzl/badglob.bzl  <<EOF
def f():
  return native.glob(include = ["BUILD"], exclude = ["a/**b/c"])
EOF

  bazel query //badglob-bzl:BUILD >& "$TEST_log" && fail "Expected failure"
  local exit_code=$?
  assert_equals 7 "$exit_code"
  expect_log "illegal argument in call to glob"
  expect_not_log "IllegalArgumentException"
}

# Regression test for https://github.com/bazelbuild/bazel/issues/9176
function test_windows_only__glob_with_junction() {
  if ! $is_windows; then
    echo "Skipping $FUNCNAME because execution platform is not Windows"
    return
  fi

  mkdir -p foo/bar foo2
  touch foo/bar/x.txt
  touch foo/a.txt
  touch foo2/b.txt
  cat >BUILD <<eof
filegroup(name = 'x', srcs = glob(["foo/**"]))
filegroup(name = 'y', srcs = glob(["foo2/**"]))
eof
  # Create junction foo2/bar2 -> foo/bar
  cmd.exe /C mklink /J foo2\\bar2 foo\\bar >NUL

  bazel query 'deps(//:x)' >& "$TEST_log"
  expect_log "//:x"
  expect_log "//:foo/a.txt"
  expect_log "//:foo/bar/x.txt"

  bazel query 'deps(//:y)' >& "$TEST_log"
  expect_log "//:y"
  expect_log "//:foo2/b.txt"
  expect_log "//:foo2/bar2/x.txt"
}

# Regression test for https://github.com/bazelbuild/bazel/pull/9269#issuecomment-531221290
# Verify that bazel-bin and the other bazel-* symlinks are not treated as
# packages when expanding the "//..." pattern.
function test_bazel_bin_is_not_a_package() {
  local -r pkg=${FUNCNAME[0]}
  mkdir "$pkg" || fail "Could not mkdir $pkg"

  echo 'workspace(name = "foo_ws")' > "$pkg/WORKSPACE"
  echo 'filegroup(name = "x")' > "$pkg/BUILD"

  # Ensure bazel-<pkg> is created.
  ( cd "$pkg"
    bazel build //:x >& "$TEST_log"
  )
  [[ -d "$pkg/bazel-bin" ]] || fail "bazel-bin was not created"
  [[ -d "$pkg/bazel-$pkg" ]] || fail "bazel-$pkg was not created"

  # Assert that "//..." expands only to "//:x".
  ( cd "$pkg"
    bazel query //... >& "$TEST_log"
    bazel shutdown
  )
  expect_log_once "//:x"
  expect_not_log "//bazel.*:x"
}

run_suite "Integration tests of ${PRODUCT_NAME} using loading/analysis phases."
