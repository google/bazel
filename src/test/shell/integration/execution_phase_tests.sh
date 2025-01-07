#!/bin/bash
#
# Copyright 2017 The Bazel Authors. All rights reserved.
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
# execution_phase_tests.sh: miscellaneous integration tests of Bazel for
# behaviors that affect the execution phase.
#

# --- begin runfiles.bash initialization v3 ---
# Copy-pasted from the Bazel Bash runfiles library v3.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v3 ---

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

# Looks for the last occurrence of a log message in a log file.
#
# This assumes the use of java.util.logging.SimpleFormatter, which splits
# the context of a log entry and the log message itself in two lines.
#
# TODO(jmmv): We should have functionality in unittest.bash to check the
# contents of the Bazel's client log in a way that allows us to test for
# only the messages printed by the last-run command.
function assert_last_log() {
  local context="${1}"; shift
  local message="${1}"; shift
  local log="${1}"; shift
  local fail_message="${1}"; shift

  if ! grep "${context}" "${log}" | grep -q "${message}" ; then
    cat "${log}" >>"${TEST_log}"  # Help debugging when we fail.
    fail "${fail_message}"
  fi
}

# Asserts that the last dump of cache stats in the log matches the given
# metric and value.
function assert_cache_stats() {
  local metric="${1}"; shift
  local exp_value="${1}"; shift

  local java_log
  java_log="$(bazel info server_log 2>/dev/null)" || fail "bazel info failed"
  grep "CacheFileDigestsModule" "${java_log}" >"${TEST_log}"
  [ -s "${TEST_log}" ] || fail "Could not find cache stats in log"
  expect_log "${metric}=${exp_value}"
}

#### TESTS #############################################################

function test_cache_computed_file_digests_behavior() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  mkdir -p $pkg/package || fail "mkdir failed"
  cat >$pkg/package/BUILD <<EOF
genrule(
  name = "foo",
  srcs = ["foo.in"],
  outs = ["foo.out"],
  cmd = "cat \$(location foo.in) >\$@",
)

genrule(
  name = "bar",
  srcs = ["bar.in", ":foo"],
  outs = ["bar.out"],
  cmd = "cat \$(location bar.in) \$(location :foo) >\$@",
)
EOF
  touch $pkg/package/foo.in $pkg/package/bar.in

  bazel build $pkg/package:bar >>"${TEST_log}" 2>&1 || fail "Should build"
  # We cannot make any robust assertions on the first run because of implicit
  # dependencies we have no control about.

  # Rebuilding without changes should yield hits for everything.  Run this
  # multiple times to ensure the reported statistics are not accumulated.
  for run in 1 2 3; do
    bazel build $pkg/package:bar >>"${TEST_log}" 2>&1 || fail "Should build"
    assert_cache_stats "hit count" 1  # stable-status.txt
    assert_cache_stats "miss count" 1  # volatile-status.txt
  done

  # Throw away the in-memory Skyframe state by flipping a flag.  We expect hits
  # for the previous outputs, which are used to query the action cache.
  bazel build --nocheck_visibility $pkg/package:bar >>"${TEST_log}" 2>&1 \
      || fail "Should build"
  assert_cache_stats "hit count" 3  # stable-status.txt foo.out bar.out
  assert_cache_stats "miss count" 1  # volatile-status.txt

  # Change the size of the cache and retry the same build.  We expect no hits
  # because resizing the cache invalidates all of its contents.
  bazel build --cache_computed_file_digests=100 $pkg/package:bar \
      >>"${TEST_log}" 2>&1 || fail "Should build"
  assert_cache_stats "hit count" 0
  assert_cache_stats "miss count" 4  # {stable,volatile}-status* {foo,bar}.out

  # Run a non-build command, which should not interfere with the cache.
  bazel info >>"${TEST_log}" 2>&1 || fail "Should run"
  assert_cache_stats "hit count" 0  # Same as previous command; unmodified.
  assert_cache_stats "miss count" 4  # Same as previous command; unmodified.

  # Rebuild without changes one more time with the new size of the cache to
  # ensure the cache is not reset across runs with the flag override.
  bazel build --nocheck_visibility --cache_computed_file_digests=100 \
      $pkg/package:bar >>"${TEST_log}" 2>&1 || fail "Should build"
  assert_cache_stats "hit count" 3  # stable-status.txt foo.out bar.out
  assert_cache_stats "miss count" 1  # volatile-status.txt
}

function DISABLED_test_cache_computed_file_digests_uncaught_changes() {
  # Does not work on Windows, https://github.com/bazelbuild/bazel/issues/6098
  local timestamp=201703151112.13  # Fixed timestamp to mark our file with.

  mkdir -p package || fail "mkdir failed"
  cat >package/BUILD <<EOF
genrule(
  name = "foo",
  srcs = ["foo.in"],
  outs = ["foo.out"],
  cmd = "echo foo >\$@ && touch -t ${timestamp} \$@",
)
EOF
  touch package/foo.in

  # Build the target once to populate the action cache, then update a file to a
  # known timestamp, and rebuild the target to recompute our internal digests
  # cache.
  bazel build package:foo >>"${TEST_log}" 2>&1 || fail "Should build"
  local output_file="$(find bazel-out/ -name foo.out)"
  touch -t "${timestamp}" "${output_file}"
  bazel build package:foo >>"${TEST_log}" 2>&1 || fail "Should build"

  # Modify the content of a file in the action cache in a way that bypasses the
  # logic to cache file digests: replace the file's content with new contents of
  # the same length; avoid modifying the inode number; and respect the previous
  # timestamp.
  function log_metadata_for_test_debugging() {
      echo "${1} ${2} modifying it in place:"
      stat "${output_file}"
      if which md5sum >/dev/null; then  # macOS and possibly others.
          md5sum "${output_file}"
      elif which md5 >/dev/null; then  # Linux and possibly others.
          md5 "${output_file}"
      fi
  }
  log_metadata_for_test_debugging "${output_file}" before >>"${TEST_log}"
  chmod +w "${output_file}"
  echo bar >"${output_file}"  # Contents must match length in genrule.
  chmod -w "${output_file}"
  touch -t "${timestamp}" "${output_file}"
  log_metadata_for_test_debugging "${output_file}" after >>"${TEST_log}"

  # Assert all hits after discarding the in-memory Skyframe state while
  # modifying the on-disk state in a way that bypasses the digests cache
  # functionality.
  bazel build --nocheck_visibility package:foo >>"${TEST_log}" 2>&1 \
      || fail "Should build"
  [[ "$(cat "${output_file}")" == bar ]] \
      || fail "External change to action cache misdetected"

  # For completeness, make the changes to the same output file visible and
  # ensure Blaze notices them.  This is to check that we actually modified the
  # right output file above.
  touch "${output_file}"
  bazel build package:foo >>"${TEST_log}" 2>&1 || fail "Should build"
  [[ "$(cat "${output_file}")" == foo ]] \
      || fail "External change to action cache not detected"
}

function test_cache_computed_file_digests_ui() {
  local -r pkg="${FUNCNAME}"
  mkdir -p "$pkg" || fail "could not create \"$pkg\""

  mkdir -p $pkg/package || fail "mkdir failed"
  echo "cc_library(name = 'foo', srcs = ['foo.cc'])" >$pkg/package/BUILD
  echo "int foo(void) { return 0; }" >$pkg/package/foo.cc

  local java_log
  java_log="$(bazel info server_log 2>/dev/null)" || fail "bazel info failed"

  bazel build $pkg/package:foo >>"${TEST_log}" 2>&1 || fail "Should build"
  assert_last_log "CacheFileDigestsModule" "Cache stats" "${java_log}" \
    "Digests cache not enabled by default"

  bazel build --cache_computed_file_digests=0 $pkg/package:foo >>"${TEST_log}" 2>&1 \
      || fail "Should build"
  assert_last_log "CacheFileDigestsModule" "Disabled cache" "${java_log}" \
      "Digests cache not disabled as requested"

  bazel build $pkg/package:foo >>"${TEST_log}" 2>&1 || fail "Should build"
  assert_last_log "CacheFileDigestsModule" "Cache stats" "${java_log}" \
      "Digests cache not reenabled"
}

function test_max_open_file_descriptors() {
  echo "nfiles: hard $(ulimit -H -n), soft $(ulimit -S -n)"

  local exp_nfiles="$(ulimit -H -n)"
  if [[ "$(uname -s)" == Darwin ]]; then
    local maxfiles="$(/usr/sbin/sysctl -n kern.maxfilesperproc)"
    if [[ "${exp_nfiles}" == "unlimited" || "${exp_nfiles}" -gt "${maxfiles}" ]]
    then
        exp_nfiles="${maxfiles}"
    fi
  elif "${is_windows}"; then
    # We do not implement the resources unlimiting feature on Windows at
    # the moment... so just expect the soft limit to remain unchanged.
    exp_nfiles="$(ulimit -S -n)"
  fi
  echo "Will expect soft nfiles to be ${exp_nfiles}"

  mkdir -p "pkg" || fail "Could not create directory"
  cat > pkg/BUILD <<'EOF' || fail "Could not create test file"
genrule(
    name = "nfiles",
    outs = ["nfiles-soft"],
    cmd = "mkdir -p pkg && ulimit -S -n >$(location nfiles-soft)",
)
EOF
  bazel build //pkg:nfiles >& "${TEST_log}" || fail "Expected success"
  local soft="$(cat bazel-genfiles/pkg/nfiles-soft)"

  # Make sure that the soft limit was raised to the expected hard value.
  # Our code doesn't touch the hard limit (even in the case "unlimited" case
  # handled above) and that's OK: if we were able to set the soft limit to a
  # high value, the hard limit must already be the same or higher.
  assert_equals "${exp_nfiles}" "${soft}"
}

function test_action_symlink_output_change_detected() {
  mkdir -p a
  WORKSPACE="$PWD"
  echo "same" > same1
  echo "same" > same2
  echo "different" > different

  cat > a/BUILD <<EOF
genrule(
  name = "a",
  srcs = [],
  outs = ["ao"],
  local = 1,
  cmd = "touch $WORKSPACE/arun && ln -s $WORKSPACE/same1 \$@",
)

genrule(
  name = "b",
  srcs = ["ao"],
  outs = ["bo"],
  local = 1,
  cmd = "touch $WORKSPACE/brun && touch \$@",
)
EOF

  bazel build //a:b || fail "build failed"
  [[ -r brun ]] || fail "b was not run"

  rm -f bazel-genfiles/a/ao arun brun
  bazel build //a:b || fail "build failed"
  [[ -r arun ]] || fail "a was not run"
  [[ -r brun ]] && fail "b was run"

  rm -fr bazel-genfiles/a/ao arun brun
  ln -s "$WORKSPACE/same2" bazel-genfiles/a/ao
  bazel build //a:b || fail "build failed"
  # Only the contents of target of the symlink should matter, where the symlink
  # points to should not
  [[ -r arun ]] && fail "a was run"
  [[ -r brun ]] && fail "b was run"

  rm -fr bazel-genfiles/a/ao arun brun
  ln -s "$WORKSPACE/different" bazel-genfiles/a/ao
  bazel build //a:b || fail "build failed"
  # If the symlink points to a file with different contents, the action should
  # be re-run
  [[ -r arun ]] || fail "a was not run"
  [[ -r brun ]] && fail "b was run"

  :  # So the exit code of the test is not inferred from that of "-r" above
}

# Trivial test to verify that the various flags that specify resource limits
# accept the same syntax.
function test_resource_flags_syntax() {
  local threads=HOST_CPUS*0.8
  local ram=HOST_RAM*0.8
  # TODO(jmmv): The IncludeScanningModule is present in Bazel but is not
  # part of the build, so this flag, which we should test here, isn't
  # available: --experimental_include_scanning_parallelism="${threads}"
  bazel build --nobuild \
      --experimental_fsvc_threads="${threads}" \
      --experimental_sandbox_async_tree_delete_idle_threads="${threads}" \
      --jobs="${threads}" \
      --legacy_globbing_threads="${threads}" \
      --loading_phase_threads="${threads}" \
      --local_resources=cpu="${threads}" \
      --local_resources=memory="${ram}" \
      --local_test_jobs="${threads}" \
      || fail "Empty build failed"
}

function test_track_directory_crossing_package() {
  mkdir -p foo/dir/subdir
  touch foo/dir/subdir/BUILD
  echo "filegroup(name = 'foo', srcs = ['dir'])" > foo/BUILD
  bazel --host_jvm_args=-DBAZEL_TRACK_SOURCE_DIRECTORIES=1 build //foo \
      >& "$TEST_log" && fail "Expected fail"
  expect_log "Directory artifact foo/dir crosses package boundary into"
}

# Regression test for https://github.com/bazelbuild/bazel/issues/14723
function test_fixed_mtime_move_detected_as_change() {
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF'
load("rules.bzl", "my_expand")

genrule(
    name = "my_templates",
    srcs = ["template_archive.tar"],
    outs = ["template1"],
    cmd = "tar -C $(RULEDIR) -xf $<",
)

my_expand(
    name = "expand1",
    input = "template1",
    output = "expanded1",
    to_sub = {"test":"foo"}
)
EOF
  cat > pkg/rules.bzl <<'EOF'
def _my_expand_impl(ctx):
    ctx.actions.expand_template(
        template = ctx.file.input,
        output = ctx.outputs.output,
        substitutions = ctx.attr.to_sub
    )

my_expand = rule(
    implementation = _my_expand_impl,
    attrs = {
        "input": attr.label(allow_single_file=True),
        "output": attr.output(),
        "to_sub" : attr.string_dict(),
    }
)
EOF

  echo "test : alpha" > template1
  touch -t 197001010000 template1
  tar -cf pkg/template_archive_alpha.tar template1

  echo "test : delta" > template1
  touch -t 197001010000 template1
  tar -cf pkg/template_archive_delta.tar template1

  mv pkg/template_archive_alpha.tar pkg/template_archive.tar
  bazel build //pkg:expand1 || fail "Expected success"
  assert_equals "foo : alpha" "$(cat bazel-bin/pkg/expanded1)"

  mv pkg/template_archive_delta.tar pkg/template_archive.tar
  bazel build //pkg:expand1 || fail "Expected success"
  assert_equals "foo : delta" "$(cat bazel-bin/pkg/expanded1)"
}

# Regression test for https://github.com/bazelbuild/bazel/issues/14723
function test_fixed_mtime_source_file() {
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF'
load("rules.bzl", "my_expand")

my_expand(
    name = "expand1",
    input = "template1",
    output = "expanded1",
    to_sub = {"test":"foo"}
)
EOF
  cat > pkg/rules.bzl <<'EOF'
def _my_expand_impl(ctx):
    ctx.actions.expand_template(
        template = ctx.file.input,
        output = ctx.outputs.output,
        substitutions = ctx.attr.to_sub
    )

my_expand = rule(
    implementation = _my_expand_impl,
    attrs = {
        "input": attr.label(allow_single_file=True),
        "output": attr.output(),
        "to_sub" : attr.string_dict(),
    }
)
EOF

  echo "test : alpha" > pkg/template1
  touch -t 197001010000 pkg/template1
  bazel build //pkg:expand1 || fail "Expected success"
  assert_equals "foo : alpha" "$(cat bazel-bin/pkg/expanded1)"

  echo "test : delta" > pkg/template1
  touch -t 197001010000 pkg/template1
  bazel build //pkg:expand1 || fail "Expected success"
  assert_equals "foo : delta" "$(cat bazel-bin/pkg/expanded1)"
}

run_suite "Integration tests of ${PRODUCT_NAME} using the execution phase."

