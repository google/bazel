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

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

export JAVA_RUNFILES=$BAZEL_RUNFILES

disable_bzlmod

function setup_repo() {
  mkdir -p $1
  touch $1/WORKSPACE
  echo $2 > $1/thing
  cat > $1/BUILD <<EOF
genrule(
    name = "x",
    srcs = ["thing"],
    cmd = "cat \$(location thing) > \$@",
    outs = ["out"],
)
EOF
}

function test_workspace_changes() {
  repo_a=$TEST_TMPDIR/a
  repo_b=$TEST_TMPDIR/b
  setup_repo $repo_a hi
  setup_repo $repo_b bye

  cat > WORKSPACE <<EOF
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "x",
    path = "$repo_a",
)
EOF

  bazel build @x//:x || fail "build failed"
  assert_contains "hi" bazel-genfiles/external/x/out

  cat > WORKSPACE <<EOF
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "x",
    path = "$repo_b",
)
EOF

  bazel build @x//:x || fail "build failed"
  assert_contains "bye" bazel-genfiles/external/x/out
}

function test_build_non_existing_repo() {
  bazel build @blah &>$TEST_log && fail "Failure expected" || true
  expect_log "Repository '@@blah' is not defined"
}

function test_path_with_spaces() {
  ws="a b"
  mkdir "$ws"
  cd "$ws"
  touch WORKSPACE

  bazel info &> $TEST_log || fail "Info failed"
  bazel help &> $TEST_log || fail "Help failed"
}

# Tests for middleman conflict when using workspace repository
function test_middleman_conflict() {
  local test_repo1=$TEST_TMPDIR/repo1
  local test_repo2=$TEST_TMPDIR/repo2

  mkdir -p $test_repo1
  mkdir -p $test_repo2
  echo "1" >$test_repo1/test.in
  echo "2" >$test_repo2/test.in
  echo 'filegroup(name="test", srcs=["test.in"], visibility=["//visibility:public"])' \
    >$test_repo1/BUILD
  echo 'filegroup(name="test", srcs=["test.in"], visibility=["//visibility:public"])' \
    >$test_repo2/BUILD
  touch $test_repo1/WORKSPACE
  touch $test_repo2/WORKSPACE

  cat > WORKSPACE <<EOF
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = 'repo1', path='$test_repo1')
local_repository(name = 'repo2', path='$test_repo2')
EOF

  cat > BUILD <<'EOF'
genrule(
  name = "test",
  srcs = ["@repo1//:test", "@repo2//:test"],
  outs = ["test.out"],
  cmd = "cat $(SRCS) >$@"
)
EOF
  bazel fetch //:test || fail "Fetch failed"
  bazel build //:test || echo "Expected build to succeed"
  check_eq "12" "$(cat bazel-genfiles/test.out | tr -d '[:space:]')"
}

# Regression test for issue #724: NullPointerException in WorkspaceFile
function test_error_in_workspace_file() {
  # Create a buggy workspace
  cat >WORKSPACE <<'EOF'
/
EOF

  # Try to refer to the workspace.
  bazel --batch build @r//:rfg &>$TEST_log \
      && fail "Failure expected" || true

  expect_not_log "Exception"
}

function test_no_select() {
  cat > WORKSPACE <<EOF
load("@bazel_tools//tools/build_defs/repo:local.bzl", "new_local_repository")
new_local_repository(
    name = "foo",
    path = "/path/to/foo",
    build_file = select({
        "//x:y" : "BUILD.1",
        "//conditions:default" : "BUILD.2"}),
)
EOF
  bazel build @foo//... &> $TEST_log && fail "Failure expected" || true
  expect_log "got value of type 'select' for attribute 'build_file' of new_local_repository rule 'foo'; select may not be used in repository rules"
}

function test_macro_select() {
  cat > WORKSPACE <<EOF
load('//:foo.bzl', 'foo_repo')
foo_repo()
EOF
  touch BUILD
  cat > foo.bzl <<EOF
load("@bazel_tools//tools/build_defs/repo:local.bzl", "new_local_repository")
def foo_repo():
  new_local_repository(
      name = "foo",
      path = "/path/to/foo",
      build_file = select({
          "//x:y" : "BUILD.1",
          "//conditions:default" : "BUILD.2"}),
  )
EOF

  bazel build @foo//... &> $TEST_log && fail "Failure expected" || true
  expect_log "got value of type 'select' for attribute 'build_file' of new_local_repository rule 'foo'"
}

function test_clean() {
  mkdir x
  cd x
  cat > WORKSPACE <<EOF
workspace(name = "y")
EOF
  cat > BUILD <<'EOF'
genrule(name = "z", cmd = "echo hi > $@", outs = ["x.out"], srcs = [])
EOF
  bazel build //:z &> $TEST_log || fail "Expected build to succeed"
  [ -L bazel-x ] || fail "bazel-x should be a symlink"
  bazel clean
  [ ! -L bazel-x ] || fail "bazel-x should have been removed"
}

function test_starlark_flags_affect_workspace() {
  cat > WORKSPACE <<EOF
load("//:macro.bzl", "macro")
print("In workspace: ")
macro()
EOF
  cat > macro.bzl <<EOF
def macro():
  print("In workspace macro: ")
EOF
  cat > BUILD <<'EOF'
genrule(name = "x", cmd = "echo hi > $@", outs = ["x.out"], srcs = [])
EOF

  MARKER="<== Starlark flag test ==>"

  # Initial check.
  bazel build //:x &>"$TEST_log" \
    || fail "Expected build to succeed"
  expect_log "In workspace: " "Did not find workspace print output"
  expect_log "In workspace macro: " "Did not find workspace macro print output"
  expect_not_log "$MARKER" \
    "Marker string '$MARKER' was seen even though \
    --internal_starlark_flag_test_canary wasn't passed"

  # Build with the special testing flag that appends a marker string to all
  # print() calls.
  bazel build //:x --internal_starlark_flag_test_canary &>"$TEST_log" \
    || fail "Expected build to succeed"
  expect_log "In workspace: $MARKER" \
    "Starlark flags are not propagating to workspace evaluation"
  expect_log "In workspace macro: $MARKER" \
    "Starlark flags are not propagating to workspace macro evaluation"
}

function test_workspace_name() {
  mkdir -p foo
  mkdir -p bar
  cat > foo/WORKSPACE <<EOF
workspace(name = "foo")

load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "bar",
    path = "$PWD/bar",
)
EOF
  cat > foo/BUILD <<EOF
exports_files(glob(["*"]))
EOF
  touch foo/baz
  cat > bar/WORKSPACE <<EOF
workspace(name = "bar")
EOF
  cat > bar/BUILD <<EOF
genrule(
    name = "depend-on-foo",
    srcs = ["@foo//:baz"],
    cmd = "cat \$(SRCS) > \$@",
    outs = ["baz.out"],
)
EOF
  cd foo
  bazel build @bar//:depend-on-foo || fail "Expected build to succeed"
}

function test_workspace_override() {
  mkdir -p original
  touch original/WORKSPACE
  cat > original/BUILD <<'EOF'
genrule(
    name = "gen",
    cmd = "echo 'original' > $@",
    outs = ["gen.out"],
)
EOF

  mkdir -p override
  touch override/WORKSPACE
  cat > override/BUILD <<'EOF'
genrule(
    name = "gen",
    cmd = "echo 'override' > $@",
    outs = ["gen.out"],
)
EOF

  cat > WORKSPACE <<EOF
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "o",
    path = "original",
)
EOF
  # Test absolute path
  bazel build --override_repository="o=$PWD/override" @o//:gen &> $TEST_log \
    || fail "Expected build to succeed"
  assert_contains "override" bazel-genfiles/external/o/gen.out
  # Test no override used
  bazel build @o//:gen &> $TEST_log \
    || fail "Expected build to succeed"
  assert_contains "original" bazel-genfiles/external/o/gen.out
  # Test relative path (should be relative to working directory)
  bazel build --override_repository="o=override" @o//:gen &> $TEST_log \
    || fail "Expected build to succeed"
  assert_contains "override" bazel-genfiles/external/o/gen.out

  # For multiple override options, the latest should win
  bazel build --override_repository=o=/ignoreme \
        --override_repository="o=$PWD/override" \
        @o//:gen &> $TEST_log \
    || fail "Expected build to succeed"
  assert_contains "override" bazel-genfiles/external/o/gen.out

  # Test workspace relative path
  mkdir -p dummy
  cd dummy
  bazel build --override_repository="o=%workspace%/override" @o//:gen &> $TEST_log \
    || fail "Expected build to succeed"
  assert_contains "override" ../bazel-genfiles/external/o/gen.out

}

function test_workspace_override_starlark(){
  mkdir -p original
  touch original/WORKSPACE
  cat > original/BUILD <<'EOF'
genrule(
    name = "gen",
    cmd = "echo 'original' > $@",
    outs = ["gen.out"],
)
EOF
  tar cvf original.tar original
  rm -rf original

  mkdir -p override
  touch override/WORKSPACE
  cat > override/BUILD <<'EOF'
genrule(
    name = "gen",
    cmd = "echo 'override' > $@",
    outs = ["gen.out"],
)
EOF

  cat > WORKSPACE <<EOF
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "o",
    url = "file://$PWD/original.tar",
    strip_prefix = "original",
)
EOF
  bazel build --override_repository="o=$PWD/override" @o//:gen &> $TEST_log \
    || fail "Expected build to succeed"
  assert_contains "override" bazel-genfiles/external/o/gen.out

  bazel build @o//:gen &> $TEST_log \
    || fail "Expected build to succeed"
  assert_contains "original" bazel-genfiles/external/o/gen.out

  bazel build --override_repository="o=$PWD/override" @o//:gen &> $TEST_log \
    || fail "Expected build to succeed"
  assert_contains "override" bazel-genfiles/external/o/gen.out

  bazel build @o//:gen &> $TEST_log \
    || fail "Expected build to succeed"
  assert_contains "original" bazel-genfiles/external/o/gen.out
}

function test_workspace_addition_change() {
  mkdir -p repo_one
  mkdir -p repo_two

  cat > repo_one/BUILD <<EOF
genrule(
    name = "gen",
    cmd = "echo 'This is repo_one' > \$@",
    outs = ["gen.out"],
)
EOF
  cat > repo_two/BUILD <<EOF
genrule(
    name = "gen",
    cmd = "echo 'This is repo_two' > \$@",
    outs = ["gen.out"],
)
EOF

  touch WORKSPACE
  cat > repo_one/WORKSPACE <<EOF
workspace(name = "new_repo")
EOF
  cat > repo_two/WORKSPACE <<EOF
workspace(name = "new_repo")
EOF

  bazel build @new_repo//:gen \
      && fail "Failure expected" || true

  bazel build --override_repository="new_repo=$PWD/repo_one" @new_repo//:gen \
      || fail "Expected build to succeed"
  assert_contains "This is repo_one" bazel-genfiles/external/new_repo/gen.out

  bazel build --override_repository="new_repo=$PWD/repo_two" @new_repo//:gen \
      || fail "Expected build to succeed"
  assert_contains "This is repo_two" bazel-genfiles/external/new_repo/gen.out
}

function test_package_loading_with_remapping_changes() {
  # structure is
  # workspace/
  #   WORKSPACE (name=main)
  #   flower/
  #     WORKSPACE (name=flower)
  #     daisy/
  #       BUILD (:daisy)
  #   tree/
  #     WORKSPACE (name=tree, local_repository(flower))
  #     oak/
  #       BUILD (:oak)

  echo 'workspace(name="main")' > WORKSPACE  # clean main workspace

  mkdir -p flower/daisy
  echo 'workspace(name="flower")' > flower/WORKSPACE
  echo 'filegroup(name="daisy")' > flower/daisy/BUILD

  mkdir -p tree/oak
  cat > tree/WORKSPACE <<EOF
workspace(name="tree")
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "flower",
    path="../flower",
    repo_mapping = {"@tulip" : "@rose"}
)
EOF
  echo 'filegroup(name="oak")' > tree/oak/BUILD

  cd tree

  # Do initial load of the packages
  bazel query --incompatible_autoload_externally= --experimental_ui_debug_all_events \
        //oak:all >& "$TEST_log" || fail "Expected success"
  expect_log "Loading package: oak"
  expect_log "//oak:oak"

  bazel query --incompatible_autoload_externally= --experimental_ui_debug_all_events \
        @flower//daisy:all >& "$TEST_log" || fail "Expected success"
  expect_log "Loading package: @@flower//daisy"
  expect_log "@flower//daisy:daisy"

  # Change mapping in tree/WORKSPACE
  cat > WORKSPACE <<EOF
workspace(name="tree")
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "flower",
    path="../flower",
    repo_mapping = {"@tulip" : "@daffodil"}
)
EOF

  # Test that packages in the tree workspace are not affected
  bazel query --incompatible_autoload_externally= --experimental_ui_debug_all_events \
        //oak:all >& "$TEST_log" || fail "Expected success"
  expect_not_log "Loading package: oak"
  expect_log "//oak:oak"

  # Test that packages in the flower workspace are reloaded
  bazel query --incompatible_autoload_externally= --experimental_ui_debug_all_events \
        @flower//daisy:all >& "$TEST_log" || fail "Expected success"
  expect_log "Loading package: @@flower//daisy"
  expect_log "@flower//daisy:daisy"
}

function test_repository_mapping_in_build_file_load() {
  # Main repo assigns @x to @y within @a
  mkdir -p main
  cat > main/WORKSPACE <<EOF
workspace(name = "main")

load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "a", path="../a", repo_mapping = {"@x" : "@y"})
local_repository(name = "y", path="../y")
EOF
  touch main/BUILD

  # Repository y is a substitute for x
  mkdir -p y
  touch y/WORKSPACE
  touch y/BUILD
  cat > y/symbol.bzl <<EOF
Y_SYMBOL = "y_symbol"
EOF

  # Repository a refers to @x
  mkdir -p a
  touch a/WORKSPACE
  cat > a/BUILD<<EOF
load("@x//:symbol.bzl", "Y_SYMBOL")
genrule(name = "a",
        outs = ["result.txt"],
        cmd = "echo %s > \$(location result.txt);" % (Y_SYMBOL)
)
EOF

  cd main
  bazel build @a//:a || fail "Expected build to succeed"
  cat bazel-genfiles/external/a/result.txt
  grep "y_symbol" bazel-genfiles/external/a/result.txt \
      || fail "expected 'y_symbol' in $(cat bazel-genfiles/external/a/result.txt)"
}

function test_remapping_from_bzl_file_load() {
  # Main repo assigns @x to @y within @a
  mkdir -p main
  cat > main/WORKSPACE <<EOF
workspace(name = "main")

load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "a", path="../a", repo_mapping = {"@x" : "@y"})
local_repository(name = "y", path="../y")
EOF
  touch main/BUILD

  # Repository y is a substitute for x
  mkdir -p y
  touch y/WORKSPACE
  touch y/BUILD
  cat > y/symbol.bzl <<EOF
Y_SYMBOL = "y_symbol"
EOF

  # Repository a refers to @x
  mkdir -p a
  touch a/WORKSPACE
  cat > a/BUILD<<EOF
load("//:foo.bzl", "foo_symbol")
genrule(name = "a",
        outs = ["result.txt"],
        cmd = "echo %s > \$(location result.txt);" % (foo_symbol)
)
EOF
  cat > a/foo.bzl<<EOF
load("@x//:symbol.bzl", "Y_SYMBOL")
foo_symbol = Y_SYMBOL
EOF

  cd main
  bazel build @a//:a || fail "Expected build to succeed"
  grep "y_symbol" bazel-genfiles/external/a/result.txt \
      || fail "expected 'y_symbol' in $(cat bazel-genfiles/external/a/result.txt)"
}

function test_repository_reassignment_label_in_build() {
  # Repository a refers to @x
  mkdir -p a
  touch a/WORKSPACE
  cat > a/BUILD<<EOF
genrule(name = "a",
        srcs = ["@x//:x.txt"],
        outs = ["result.txt"],
        cmd = "echo hello > \$(location result.txt)"
)
EOF

  # Repository b is a substitute for x
  mkdir -p b
  touch b/WORKSPACE
  cat >b/BUILD <<EOF
exports_files(srcs = ["x.txt"])
EOF
  echo "Hello from @b//:x.txt" > b/x.txt

  # Main repo assigns @x to @b within @a
  mkdir -p main
  cat > main/WORKSPACE <<EOF
workspace(name = "main")

load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "a", path="../a", repo_mapping = {"@x" : "@b"})
local_repository(name = "b", path="../b")
EOF
  touch main/BUILD

  cd main
  bazel query --output=build @a//:a | grep "@b//:x.txt" \
      || fail "Expected srcs to contain '@b//:x.txt'"
}

function test_repository_reassignment_location() {
  # Repository a refers to @x
  mkdir -p a
  touch a/WORKSPACE
  cat > a/BUILD<<EOF
genrule(name = "a",
        srcs = ["@x//:x.txt"],
        outs = ["result.txt"],
        cmd = "echo \$(location @x//:x.txt) > \$(location result.txt); \
            cat \$(location @x//:x.txt)>> \$(location result.txt);"
)
EOF

  # Repository b is a substitute for x
  mkdir -p b
  touch b/WORKSPACE
  cat >b/BUILD <<EOF
exports_files(srcs = ["x.txt"])
EOF
  echo "Hello from @b//:x.txt" > b/x.txt

  # Main repo assigns @x to @b within @a
  mkdir -p main
  cat > main/WORKSPACE <<EOF
workspace(name = "main")

load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "a", path="../a", repo_mapping = {"@x" : "@b"})
local_repository(name = "b", path="../b")
EOF
  touch main/BUILD

  cd main
  bazel build @a//:a || fail "Expected build to succeed"
  grep "external/b/x.txt" bazel-genfiles/external/a/result.txt \
      || fail "expected external/b/x.txt in $(cat bazel-genfiles/external/a/result.txt)"
}

function test_repo_mapping_starlark_rules() {
  EXTREPODIR=`pwd`

  mkdir -p a
  touch a/WORKSPACE
  cat > a/BUILD<<EOF
genrule(name = "a",
        srcs = ["@x//:x.txt"],
        outs = ["result.txt"],
        cmd = "echo hello > \$(location result.txt)"
)
EOF
  # turn a into a zip file to be consumed by http_archive rule
  zip a.zip a/*
  rm -rf a

  mkdir -p b
  touch b/WORKSPACE
  cat >b/BUILD <<EOF
exports_files(srcs = ["x.txt"])
EOF
  echo "Hello from @b//:x.txt" > b/x.txt

  # Main repo assigns @x to @b within @a
  mkdir -p main
  cat > main/WORKSPACE <<EOF
workspace(name = "main")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="a",
  strip_prefix="a",
  urls=["file://${EXTREPODIR}/a.zip"],
  repo_mapping = {"@x" : "@b"}
)
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "b", path="../b")
EOF
  touch main/BUILD

  cd main
  bazel query --output=build @a//:a | grep "@b//:x.txt" \
      || fail "Expected srcs to contain '@b//:x.txt'"
}

function test_remapping_with_label_relative() {
  # create foo repository
  mkdir foo
  touch foo/WORKSPACE
  cat >foo/foo.bzl <<EOF
x = Label("//blah:blah").relative("@a//:baz")
print(x)
EOF
  cat >foo/BUILD <<EOF
load(":foo.bzl", "x")
genrule(
  name = "bar",
  outs = ["xyz"],
  cmd = "touch \$(location xyz)",
  visibility = ["//visibility:public"]
)
EOF

  # Main repo assigns @a to @b within @foo
  mkdir -p main
  cat >main/WORKSPACE <<EOF
workspace(name = "main")
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "foo", path="../foo", repo_mapping = {"@a" : "@b"})
local_repository(name = "b", path="../b")
EOF
  touch main/BUILD

  cd main
  bazel build @foo//:bar \
      >& "$TEST_log" || fail "Expected build to succeed"
  expect_log "@b//:baz"
  expect_not_log "@a//:baz"
}

function test_remapping_label_constructor() {
  # create foo repository
  mkdir foo
  touch foo/WORKSPACE
  cat >foo/foo.bzl <<EOF
x = Label("@a//blah:blah")
print(x)
EOF
  cat >foo/BUILD <<EOF
load(":foo.bzl", "x")
genrule(
  name = "bar",
  outs = ["xyz"],
  cmd = "touch \$(location xyz)",
  visibility = ["//visibility:public"]
)
EOF

  # Main repo assigns @a to @b within @foo
  mkdir -p main
  cat >main/WORKSPACE <<EOF
workspace(name = "main")
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "foo", path="../foo", repo_mapping = {"@a" : "@b"})
local_repository(name = "b", path="../b")
EOF
  touch main/BUILD

  cd main
  bazel build @foo//:bar \
      >& "$TEST_log" || fail "Expected build to succeed"
  expect_log "@b//blah"
  expect_not_log "@a//blah"
}

function test_workspace_addition_change_aspect() {
  mkdir -p repo_one
  mkdir -p repo_two


  touch foo.c
  cat > BUILD <<EOF
cc_library(
    name = "lib",
    srcs = ["foo.c"],
)
EOF

  touch WORKSPACE
  touch repo_one/BUILD
  touch repo_two/BUILD

  cat > repo_one/WORKSPACE <<EOF
workspace(name = "new_repo")
EOF
  cat > repo_two/WORKSPACE <<EOF
workspace(name = "new_repo")
EOF


  cat > repo_one/aspects.bzl <<EOF
def _print_aspect_impl(target, ctx):
  # Make sure the rule has a srcs attribute.
  if hasattr(ctx.rule.attr, 'srcs'):
    # Output '1' for each file in srcs.
    for src in ctx.rule.attr.srcs:
      for f in src.files.to_list():
        print(1)
  return []

print_aspect = aspect(
    implementation = _print_aspect_impl,
    attr_aspects = ['deps'],
)
EOF
  cat > repo_two/aspects.bzl <<EOF
def _print_aspect_impl(target, ctx):
  # Make sure the rule has a srcs attribute.
  if hasattr(ctx.rule.attr, 'srcs'):
    print(ctx.rule.attr.srcs)
  return []

print_aspect = aspect(
    implementation = _print_aspect_impl,
    attr_aspects = ['deps'],
)
EOF

  bazel clean --expunge

  echo; echo "no repo"; echo
  bazel build //:lib --aspects @new_repo//:aspects.bzl%print_aspect \
      && fail "Failure expected" || true

  echo; echo "repo_one"; echo
  bazel build //:lib --override_repository="new_repo=$PWD/repo_one" \
      --aspects @new_repo//:aspects.bzl%print_aspect \
      || fail "Expected build to succeed"

  echo; echo "repo_two"; echo
  bazel build //:lib --override_repository="new_repo=$PWD/repo_two" \
      --aspects @new_repo//:aspects.bzl%print_aspect \
      || fail "Expected build to succeed"
}

function test_mainrepo_name_is_not_different_repo() {
  # Repository a refers to @x
  mkdir -p mainrepo
  echo "workspace(name = 'mainrepo')" > mainrepo/WORKSPACE
  cat > mainrepo/BUILD<<EOF
load("//:def.bzl", "a")
load("@mainrepo//:def.bzl", "a")
EOF
  cat > mainrepo/def.bzl<<EOF
print("def.bzl loaded")
a = 1
EOF

  cd mainrepo
  bazel query //... &>"$TEST_log" \
      || fail "Expected query to succeed"
  expect_log "def.bzl loaded"
  expect_not_log "//external"
}

function test_mainrepo_name_remapped_properly() {
  mkdir -p mainrepo
  touch mainrepo/BUILD
  cat > mainrepo/WORKSPACE<<EOF
workspace(name = "mainrepo")
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "a",
  path = "../a"
)
EOF
  cat > mainrepo/def.bzl<<EOF
print ("def.bzl loaded")
x = 10
EOF

  mkdir -p a
  touch a/WORKSPACE
  echo "load('@mainrepo//:def.bzl', 'x')"> a/BUILD

  # the bzl file should be loaded from the main workspace and
  # not as an external repository
  cd mainrepo
  bazel query @a//... &>"$TEST_log" \
      || fail "Expected query to succeed"
  expect_log "def.bzl loaded"
  expect_not_log "//external"

  cd ..
  cat > mainrepo/WORKSPACE<<EOF
workspace(name = "mainrepo")
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "a",
  path = "../a",
  repo_mapping = {"@mainrepo" : "@newname"}
)
EOF

  # now that @mainrepo doesn't exist within workspace "a",
  # the query should fail
  cd mainrepo
  bazel query @a//... &>"$TEST_log" \
      && fail "Failure expected" || true
}

function test_external_subpacakge() {
  # Verify that we do not crash on accessing an external repository
  # through the //external virtual package.
  # Regression test for the crash reported in #6725
  mkdir local
  touch local/BUILD
  mkdir main
  cd main
  cat > WORKSPACE <<EOF
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name="local", path="../local")
EOF
  bazel build //external:local > "${TEST_log}" 2>&1 \
      && fail "building a thing under //external shouldn't work"
  expect_not_log 'IllegalArgumentException'
  expect_log "Found reference to a workspace rule in a context where a build rule was expected"
}

function test_external_rule() {
  # The repository rule for an external repository is visible as target
  # under //external. Ensure we do not interpret it with a rule context
  # instead of a repository rule context.
  EXTREPODIR=`pwd`
  mkdir true
  echo 'int main(int argc, char **argv) { return 0;}' > true/main.c
  echo 'cc_library(name="true", srcs=["main.c"])' > true/BUILD
  tar cvf true.tar true
  rm -rf true
  mkdir extref
  echo 'cc_binary(name="it", deps=["//external:true"])' > extref/BUILD
  touch extref/WORKSPACE
  mkdir main
  cd main
  cat > WORKSPACE <<EOF
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
  name="true",
  urls=["${EXTREPODIR}/true.tar"],
  strip_prefix="true",
)

load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name="extref",
  path="../extref",
)
EOF
  touch BUILD
  bazel build @extref//:it >"${TEST_log}" 2>&1 && fail "expected failure" || :
  expect_not_log 'download_and_extract'
  expect_log 'http_archive.*workspace rule'
  expect_log '//external:true.*build rule.*expected'
}

function test_remap_execution_platform() {
    # Regression test for issue https://github.com/bazelbuild/bazel/issues/7773,
    # using the reproduction case as reported
    cat > WORKSPACE <<'EOF'
workspace(name = "my_ws")

register_execution_platforms("@my_ws//platforms:my_host_platform")
EOF
    mkdir platforms
    cat > platforms/BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])

constraint_setting(name = "machine_size")
constraint_value(name = "large_machine", constraint_setting = ":machine_size")
constraint_value(name = "small_machine", constraint_setting = ":machine_size")

platform(
    name = "my_host_platform",
    parents = ["@local_config_platform//:host"],
    constraint_values = [
        ":large_machine"
    ]
)
EOF
    mkdir code
    cat > code/BUILD <<'EOF'
genrule(
	name = "foo",
	cmd = "touch $@",
	exec_compatible_with = ["@my_ws//platforms:large_machine"],
	outs = ["foo.out"],
)
EOF

    bazel build //code/... \
          > "${TEST_log}" 2>&1 || fail "expected success"
}

function test_remap_toolchain_deps() {
    # Regression test for the registration pattern of toolchains used in
    # bazel-skylib, where the failure to handle it correctly caused the
    # roll back of the first attempt of enabling renaming in tool chains.
    cat > WORKSPACE <<'EOF'
workspace(name = "my_ws")

register_toolchains("@my_ws//toolchains:sample_toolchain")
EOF
    mkdir toolchains
    cat > toolchains/toolchain.bzl <<'EOF'
def _impl(ctx):
  return [platform_common.ToolchainInfo()]

mytoolchain = rule(
  implementation = _impl,
  attrs = {},
)
EOF
    cat > toolchains/rule.bzl <<'EOF'
def _impl(ctx):
  # Ensure the toolchain is available under the requested (non-canonical)
  # name
  print("toolchain is %s" %
        (ctx.toolchains["@my_ws//toolchains:my_toolchain_type"],))
  pass

testrule = rule(
  implementation = _impl,
  attrs = {},
  toolchains = ["@my_ws//toolchains:my_toolchain_type"],
)
EOF
    cat > toolchains/BUILD <<'EOF'
load(":toolchain.bzl", "mytoolchain")
load(":rule.bzl", "testrule")

toolchain_type(name = "my_toolchain_type")
mytoolchain(name = "thetoolchain")

toolchain(
  name = "sample_toolchain",
  toolchain = ":thetoolchain",
  toolchain_type = "@my_ws//toolchains:my_toolchain_type",
)

testrule(
  name = "emptytoolchainconsumer",
)
EOF

    bazel build //toolchains/... || fail "expected success"
}

test_remap_toolchains_from_qualified_load() {
    cat > WORKSPACE <<'EOF'
workspace(name = "my_ws")

register_toolchains("@my_ws//toolchains:sample_toolchain")
EOF
    mkdir toolchains
    cat > toolchains/toolchain.bzl <<'EOF'
def _impl(ctx):
  return [platform_common.ToolchainInfo()]

mytoolchain = rule(
  implementation = _impl,
  attrs = {},
)
EOF
    cat > toolchains/rule.bzl <<'EOF'
def _impl(ctx):
  # Ensure the toolchain is available under the requested (non-canonical)
  # name
  print("toolchain is %s" %
        (ctx.toolchains["@my_ws//toolchains:my_toolchain_type"],))
  pass

testrule = rule(
  implementation = _impl,
  attrs = {},
  toolchains = ["@my_ws//toolchains:my_toolchain_type"],
)
EOF
    cat > toolchains/BUILD <<'EOF'
load("@my_ws//toolchains:toolchain.bzl", "mytoolchain")
load("@my_ws//toolchains:rule.bzl", "testrule")

toolchain_type(name = "my_toolchain_type")
mytoolchain(name = "thetoolchain")

toolchain(
  name = "sample_toolchain",
  toolchain = "@my_ws//toolchains:thetoolchain",
  toolchain_type = "@my_ws//toolchains:my_toolchain_type",
)

testrule(
  name = "emptytoolchainconsumer",
)
EOF

    bazel build @my_ws//toolchains/... || fail "expected success"
}


function test_rename_visibility() {
    mkdir local_a
    touch local_a/WORKSPACE
    cat > local_a/BUILD <<'EOF'
genrule(
  name = "x",
  outs = ["x.txt"],
  cmd = "echo Hello World > $@",
  visibility = ["@foo//:__pkg__"],
)
EOF
    mkdir local_b
    touch local_b/WORKSPACE
    cat > local_b/BUILD <<'EOF'
genrule(
  name = "y",
  srcs = ["@source//:x"],
  cmd = "cp $< $@",
  outs = ["y.txt"],
)
EOF
    mkdir mainrepo
    cd mainrepo
    cat > WORKSPACE <<'EOF'
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "source",
  path = "../local_a",
)

local_repository(
  name = "foo",
  path = "../local_b",
)
EOF
   echo; echo Without renaming; echo
   bazel build @foo//:y || fail "Expected success"

   # Now, verify the same with for renamed to bar.
   cat > WORKSPACE <<'EOF'
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "source",
  path = "../local_a",
  repo_mapping = {"@foo" : "@bar"}
)

local_repository(
  name = "bar",
  path = "../local_b",
)
EOF
   echo; echo WITH renaming; echo
   bazel build @bar//:y || fail "Expected success"

   # Finally, verify the same with a renaming in the other repository
   cat > WORKSPACE <<'EOF'
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "origin",
  path = "../local_a",
)

local_repository(
  name = "foo",
  path = "../local_b",
  repo_mapping = {"@source" : "@origin"}
)
EOF
   echo; echo with renaming of the SOURCE; echo
   bazel build @foo//:y || fail "Expected success"
}

function test_renaming_visibility_main() {
    mkdir local_a
    touch local_a/WORKSPACE
    cat > local_a/BUILD <<'EOF'
genrule(
  name = "x",
  outs = ["x.txt"],
  cmd = "echo Hello World > $@",
  visibility = ["@foo//:__pkg__"],
)
EOF
    mkdir mainrepo
    cd mainrepo
   cat > WORKSPACE <<'EOF'
workspace(name="foo")
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "source",
  path = "../local_a",
)
EOF
    cat > BUILD <<'EOF'
genrule(
  name = "y",
  srcs = ["@source//:x"],
  cmd = "cp $< $@",
  outs = ["y.txt"],
)
EOF
    echo; echo remapping main repo; echo
    bazel build @foo//:y || fail "Expected success"

}

function test_renaming_visibility_via_default() {
    mkdir local_a
    touch local_a/WORKSPACE
    cat > local_a/BUILD <<'EOF'
genrule(
  name = "x",
  outs = ["x.txt"],
  cmd = "echo Hello World > $@",
  visibility = ["@foo//data:__pkg__"],
)
EOF
    mkdir mainrepo
    cd mainrepo
   cat > WORKSPACE <<'EOF'
workspace(name="foo")
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "source",
  path = "../local_a",
)
EOF
    mkdir data
    cat > data/BUILD <<'EOF'
genrule(
  name = "y",
  srcs = ["@source//:x"],
  cmd = "cp $< $@",
  outs = ["y.txt"],
  visibility = ["@foo//:__pkg__"],
)
EOF
    cat > datarule.bzl <<'EOF'
def _data_impl(ctx):
  output = ctx.actions.declare_file(ctx.label.name + ".txt")
  ctx.actions.run_shell(
    inputs = ctx.files._data,
    outputs = [output],
    command = "cp $1 $2",
    arguments = [ctx.files._data[0].path, output.path],
  )

data = rule(
         implementation = _data_impl,
         attrs = {
          "_data": attr.label(default="@foo//data:y"),
         },
         outputs = { "txt" : "%{name}.txt"},
)
EOF
    cat >BUILD <<'EOF'
load("//:datarule.bzl", "data")
data(name="it")
EOF
    echo; echo remapping main repo; echo
    bazel build @foo//:it || fail "Expected success"
}

run_suite "workspace tests"
