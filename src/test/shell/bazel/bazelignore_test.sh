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
# An end-to-end test that Bazel's experimental UI produces reasonable output.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

#### SETUP #############################################################

set -e

test_ignores_comment_lines() {
    rm -rf work && mkdir work && cd work
    setup_module_dot_bazel
    mkdir -p ignoreme
    echo Not a valid BUILD file > ignoreme/BUILD
    mkdir -p '#foo/bar'
    cat > '#foo/bar/BUILD' <<'EOI'
genrule(
  name = "out",
  outs = ["out.txt"],
  cmd = "echo Hello World > $@",
)
EOI
    cat > .bazelignore <<'EOI'
# Some comment
#foo/bar
ignoreme
EOI
    bazel build '//#foo/bar/...' || fail "Could not build valid target"
}

test_does_not_glob_into_ignored_directory() {
    rm -rf work && mkdir work && cd work
    setup_module_dot_bazel

    echo 'filegroup(name="f", srcs=glob(["**"]))' > BUILD
    echo 'ignored' > .bazelignore
    mkdir -p ignored/pkg
    echo 'filegroup(name="f", srcs=glob(["**"]))' > ignored/pkg/BUILD
    touch ignored/pkg/a
    touch ignored/file
    bazel query //:all-targets > "$TEST_TMPDIR/targets"
    assert_not_contains "//:ignored/file" "$TEST_TMPDIR/targets"
    assert_not_contains "//:ignored/pkg/BUILD" "$TEST_TMPDIR/targets"
    assert_not_contains "//:ignored/pkg/a" "$TEST_TMPDIR/targets"

    # This weird line tests whether .bazelignore also stops the Skyframe-based
    # glob. Globbing (as of 2019 Oct) is done in a hybrid fashion: we do the
    # non-Skyframe globbing because it's faster and Skyframe globbing because
    # it's more incremental. In the first run, we get the results of the
    # non-Skyframe globbing, but if we invalidate the BUILD file, the result of
    # the non-Skyframe glob is invalidated and but the better incrementality
    # allows the result of the Skyframe glob to be cached.
    echo "# change" >> BUILD
    bazel query //:all-targets > "$TEST_TMPDIR/targets"
    assert_not_contains "//:ignored/file" "$TEST_TMPDIR/targets"
    assert_not_contains "//:ignored/pkg/BUILD" "$TEST_TMPDIR/targets"
    assert_not_contains "//:ignored/pkg/a" "$TEST_TMPDIR/targets"

    echo > .bazelignore
    bazel query //:all-targets > "$TEST_TMPDIR/targets"
    assert_contains "//:ignored/file" "$TEST_TMPDIR/targets"
    bazel query //ignored/pkg:all-targets > "$TEST_TMPDIR/targets"
    assert_contains "//ignored/pkg:a" "$TEST_TMPDIR/targets"
}

test_broken_BUILD_files_ignored() {
    rm -rf work && mkdir work && cd work
    setup_module_dot_bazel
    mkdir -p ignoreme/deep/reallydep/stillignoreme
    echo This is a broken BUILD file > ignoreme/BUILD
    echo This is a broken BUILD file > ignoreme/deep/BUILD
    echo This is a broken BUILD file > ignoreme/deep/reallydep/BUILD
    echo This is a broken BUILD file > ignoreme/deep/reallydep/stillignoreme/BUILD
    touch BUILD
    bazel build ... >& "$TEST_log" && fail "Expected failure" || :

    echo ignoreme > .bazelignore
    bazel build ... >& "$TEST_log" \
        || fail "directory mentioned in .bazelignore not ignored as it should"
}

test_broken_BUILD_files_ignored_subdir() {
    rm -rf work && mkdir work && cd work
    setup_module_dot_bazel
    mkdir -p ignoreme/deep || fail "Couldn't mkdir"
    ln -s deeper ignoreme/deep/deeper || fail "Couldn't create cycle"
    touch BUILD
    bazel build //ignoreme/deep/... >& "$TEST_log" && fail "Expected failure" \
        || :
    expect_log "circular symlinks detected"
    expect_log "ignoreme/deep/deeper"

    echo ignoreme > .bazelignore
    bazel build //ignoreme/deep/... >& "$TEST_log" || fail "Expected success"
    expect_log "WARNING: Pattern '//ignoreme/deep/...' was filtered out by ignored directory 'ignoreme'"
    expect_not_log "circular symlinks detected"
    expect_not_log "ignoreme/deep/deeper"

    bazel query //ignoreme/deep/... >& "$TEST_log" || fail "Expected success"
    expect_log "WARNING: Pattern '//ignoreme/deep/...' was filtered out by ignored directory 'ignoreme'"
    expect_not_log "circular symlinks detected"
    expect_not_log "ignoreme/deep/deeper"
    expect_log "Empty results"

    bazel query //ignoreme/deep/... --universe_scope=//ignoreme/deep/... \
        --order_output=no >& "$TEST_log" || fail "Expected success"
    expect_log "WARNING: Pattern '//ignoreme/deep/...' was filtered out by ignored directory 'ignoreme'"
    expect_not_log "circular symlinks detected"
    expect_not_log "ignoreme/deep/deeper"
    expect_log "Empty results"

    # Test patterns with exclude.
    bazel build -- //ignoreme/deep/... -//ignoreme/... >& "$TEST_log" \
        || fail "Expected success"
    expect_not_log "circular symlinks detected"
    expect_not_log "ignoreme/deep/deeper"

    bazel build -- //ignoreme/... -//ignoreme/deep/... >& "$TEST_log" \
        || fail "Expected success"
    expect_log "WARNING: Pattern '//ignoreme/...' was filtered out by ignored directory 'ignoreme'"
    expect_not_log "circular symlinks detected"
    expect_not_log "ignoreme/deep/deeper"
}

test_symlink_cycle_ignored() {
    rm -rf work && mkdir work && cd work
    setup_module_dot_bazel
    mkdir -p ignoreme/deep
    (cd ignoreme/deep && ln -s . loop)
    touch BUILD

    # This should really fail, but it does not:
    # https://github.com/bazelbuild/bazel/issues/12148
    bazel build ... >& $TEST_log || fail "Expected success"
    expect_log "Infinite symlink expansion"

    echo; echo
    echo ignoreme > .bazelignore
    bazel build ... >& $TEST_log || fail "Expected success"
    expect_not_log "Infinite symlink expansion"
}

test_build_specific_target() {
    rm -rf work && mkdir work && cd work
    setup_module_dot_bazel
    mkdir -p ignoreme
    echo Not a valid BUILD file > ignoreme/BUILD
    mkdir -p foo/bar
    cat > foo/bar/BUILD <<'EOI'
genrule(
  name = "out",
  outs = ["out.txt"],
  cmd = "echo Hello World > $@",
)
EOI
    echo ignoreme > .bazelignore
    bazel build //foo/bar/... || fail "Could not build valid target"
}

test_aquery_specific_target() {
    rm -rf work && mkdir work && cd work
    setup_module_dot_bazel
    mkdir -p foo/ignoreme
    cat > foo/ignoreme/BUILD <<'EOI'
genrule(
  name = "ignoreme",
  outs = ["ignore.txt"],
  cmd = "echo Hello World > $@",
)
EOI
    mkdir -p foo
    cat > foo/BUILD <<'EOI'
genrule(
  name = "out",
  outs = ["out.txt"],
  cmd = "echo Hello World > $@",
)
EOI
    bazel aquery ... > output 2> "$TEST_log" \
        || fail "Aquery should complete without error."
    cat output >> "$TEST_log"
    assert_contains "ignoreme" output

    echo foo/ignoreme > .bazelignore
    bazel aquery ... > output 2> "$TEST_log" \
        || fail "Aquery should complete without error."
    cat output >> "$TEST_log"
    assert_not_contains "ignoreme" output
}

test_invalid_path() {
    rm -rf work && mkdir work && cd work
    setup_module_dot_bazel
    echo -e "foo/\0/bar" > .bazelignore
    echo 'filegroup(name="f", srcs=glob(["**"]))' > BUILD
    if bazel build //... 2> "$TEST_log"; then
      fail "Bazel build should have failed"
    fi
    expect_log "Nul character not allowed"
}

test_target_patterns_with_wildcards_in_repo_bazel() {
  rm -rf work && mkdir work && cd work
  setup_module_dot_bazel
  cat >REPO.bazel <<'EOF'
ignore_directories(["**/sub", "foo/bar/.dot/*"])
EOF

  for pkg in foo foo/sub foo/sub/subsub foo/bar/.dot/pkg foo/notsub foo/bar/.dot; do
    mkdir -p "$pkg"
    echo 'filegroup(name="fg")' > "$pkg/BUILD.bazel"
  done

  bazel query //foo/... > "$TEST_TMPDIR/targets"
  assert_not_contains "//foo/sub:fg" "$TEST_TMPDIR/targets"
  assert_not_contains "//foo/sub/subsub:fg" "$TEST_TMPDIR/targets"
  assert_not_contains "//foo/bar/.dot/pkg:fg" "$TEST_TMPDIR/targets"
  assert_contains "//foo/notsub:fg" "$TEST_TMPDIR/targets"
}

test_globs_with_wildcards_in_repo_bazel() {
  rm -rf work && mkdir work && cd work
  setup_module_dot_bazel
  cat >REPO.bazel <<'EOF'
ignore_directories(["**/sub", "foo/.hidden*"])
EOF

  mkdir -p foo foo/bar sub bar/sub foo/.hidden_excluded foo/.included
  touch foo/foofile foo/bar/barfile  sub/subfile bar/sub/subfile
  touch foo/.hidden_excluded/file foo/.included/file

  cat > BUILD <<'EOF'
filegroup(name="fg", srcs=glob(["**"]))

EOF
  bazel query //:all-targets > "$TEST_TMPDIR/targets"
  assert_contains ":foo/foofile" "$TEST_TMPDIR/targets"
  assert_contains ":foo/bar/barfile" "$TEST_TMPDIR/targets"
  assert_contains ":foo/.included/file" "$TEST_TMPDIR/targets"
  assert_not_contains ":sub/subfile" "$TEST_TMPDIR/targets"
  assert_not_contains ":bar/sub/subfile" "$TEST_TMPDIR/targets"
  assert_not_contains ":foo/.hidden_excluded/file" "$TEST_TMPDIR/targets"
}


test_syntax_error_in_repo_bazel() {
  rm -rf work && mkdir work && cd work
  setup_module_dot_bazel
  cat >REPO.bazel <<'EOF'
SYNTAX ERROR
EOF

  touch BUILD.bazel
  bazel query //:all >& "$TEST_log" && fail "failure expected"
  if [[ $? != 7 ]]; then
    fail "expected an analysis failure"
  fi
}

test_ignore_directories_after_repo_in_repo_bazel() {
  rm -rf work && mkdir work && cd work
  setup_module_dot_bazel
  cat >REPO.bazel <<'EOF'
ignore_directories(["**/sub", "foo/.hidden*"])
repo(default_visibility=["//visibility:public"])
EOF

  touch BUILD.bazel
  bazel query //:all >& "$TEST_log" && fail "failure expected"
  expect_log "it must be called before any other functions"
}

test_absolute_path_in_bazelignore() {
  rm -rf work && mkdir work && cd work
  setup_module_dot_bazel

  cat > .bazelignore <<EOF
/a/ignored
EOF

  mkdir -p a/ignored
  touch a/ignored/ignored.txt

  cat > a/BUILD <<'EOF'
filegroup(name="fg", srcs=glob(["**/*.txt"]))
EOF

  bazel query //a:fg >& "$TEST_log" && fail "failure expected"
  if [[ $? != 7 ]]; then
    fail "expected an analysis failure"
  fi
}

run_suite "Integration tests for .bazelignore"
