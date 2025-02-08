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

# `uname` returns the current platform, e.g "MSYS_NT-10.0" or "Linux".
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
  add_rules_java MODULE.bazel
}

#### TESTS #############################################################

function test_passing_test_is_reported_correctly() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg || fail "mkdir -p $pkg failed"
  cat >$pkg/BUILD <<'EOF'
sh_test(
    name = "success",
    size = "small",
    srcs = ["success.sh"],
)
EOF
  cat >$pkg/success.sh <<'EOF'
#!/bin/sh

echo "success.sh is successful"
exit 0
EOF
  chmod +x $pkg/success.sh

  bazel test --nocache_test_results //$pkg:success &>$TEST_log \
      || fail "expected success"
  expect_not_log "success.sh is successful"
  expect_log "^Executed 1 out of 1 test: 1 test passes."
}

function test_failing_test_is_reported_correctly() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg || fail "mkdir -p $pkg failed"
  cat >$pkg/BUILD <<'EOF'
sh_test(
    name = "fail",
    size = "small",
    srcs = ["fail.sh"],
)
EOF
  cat >$pkg/fail.sh <<'EOF'
#!/bin/sh

echo "fail.sh is failing"
exit 42
EOF
  chmod +x $pkg/fail.sh

  bazel test --nocache_test_results //$pkg:fail &>$TEST_log \
      && fail "expected failure" || true
  expect_log "^//$pkg:fail[[:space:]]\+FAILED in [[:digit:]]\+[\.,][[:digit:]]\+s"
  expect_log "^Executed 1 out of 1 test: 1 fails"
}

function test_build_fail_terse_summary() {
    local -r pkg=$FUNCNAME
    mkdir -p $pkg || fail "mkdir -p $pkg failed"
    cat > $pkg/BUILD <<EOF
genrule(
  name = "testsrc",
  outs = ["test.sh"],
  cmd = "false",
)
sh_test(
  name = "failstobuild1",
  srcs = ["test.sh"],
)
sh_test(
  name = "failstobuild2",
  srcs = ["test.sh"],
)
genrule(
  name = "slowtestsrc",
  outs = ["slowtest.sh"],
  cmd = "sleep 200 && echo '#!/bin/sh' > \$@ && echo '#${RANDOM} and ${RANDOM} to prevent caching' >> \$@ && echo 'true' >> \$@ && chmod 755 \$@",
)
sh_test(
  name = "willbeskipped",
  srcs = ["slowtest.sh"],
)
EOF
    # --build_tests_only is necessary in Skymeld mode to prevent the execution
    # error from being hit before any TestAnalyzedEvent.
    bazel test --test_summary=terse --build_tests_only //$pkg/... &>$TEST_log \
      && fail "expected failure" || :
    expect_not_log 'NO STATUS'
    expect_log 'testsrc'
    expect_log 'were skipped'
}

# Regression test for b/67463263: Tests that spawn subprocesses must not block
# if those subprocesses never finish. If this test fails, because the "my_test"
# test is timing out, it means that Bazel is waiting for the "sleep" to finish,
# which it shouldn't.
function test_process_spawned_by_test_doesnt_block_test_from_completing() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg || fail "mkdir -p $pkg failed"

  cat > $pkg/BUILD <<'EOF'
load("@rules_java//java:java_test.bzl", "java_test")
java_test(
    name = "my_test",
    main_class = "test.MyTest",
    srcs = ["MyTest.java"],
    timeout = "short",
    use_testrunner = 0,
)
EOF
  cat > $pkg/MyTest.java <<'EOF'
package test;
public class MyTest {
  public static void main(String[] args) throws Exception {
    new ProcessBuilder("sleep", "300").inheritIO().start();
  }
}
EOF

  bazel test //$pkg:my_test &> $TEST_log || fail "expected test to pass"
}

function test_test_suite_non_expansion() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg || fail "mkdir -p $pkg failed"
  cat > $pkg/BUILD <<'EOF'
sh_test(name = 'test_a',
        srcs = [':a.sh'],
)

sh_test(name = 'test_b',
        srcs = [':b.sh'],
)

test_suite(name = 'suite',
)
EOF
  cat > $pkg/a.sh <<'EOF'
#!/bin/sh
exit 0
EOF

  cat > $pkg/b.sh <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod +x $pkg/a.sh $pkg/b.sh
  bazel test --noexpand_test_suites //$pkg:suite &> $TEST_log || fail "expected test to pass"
  expect_log "//$pkg:test_a"
  expect_log "//$pkg:test_b"
}

function test_print_relative_test_log_paths() {
  local -r pkg="$FUNCNAME"
  mkdir -p "$pkg" || fail "mkdir -p $pkg failed"
  cat > "$pkg"/BUILD <<'EOF'
sh_test(name = 'fail', srcs = ['fail.sh'])
EOF
  cat > "$pkg"/fail.sh <<'EOF'
#!/bin/sh
exit 1
EOF
  chmod +x "$pkg"/fail.sh

  local testlogs_dir=$(bazel info ${PRODUCT_NAME}-testlogs 2> /dev/null)

  bazel test --print_relative_test_log_paths=false //"$pkg":fail &> $TEST_log \
    && fail "expected failure"
  expect_log "^  $testlogs_dir/$pkg/fail/test.log$"

  bazel test --print_relative_test_log_paths=true //"$pkg":fail &> $TEST_log \
    && fail "expected failure"
  expect_log "^  ${PRODUCT_NAME}-testlogs/$pkg/fail/test.log$"

  # Tell bazel to not create the symlinks where these relative logs would be,
  # but still pretend that they exist in logging.
  bazel test --print_relative_test_log_paths=true \
    --experimental_convenience_symlinks=log_only \
    //"$pkg":fail &> $TEST_log \
    && fail "expected failure"
  expect_log "^  ${PRODUCT_NAME}-testlogs/$pkg/fail/test.log$"
}

# Regression test for https://github.com/bazelbuild/bazel/pull/8322
# As of 2019-09-06, "bazel test" does not forward input from stdin to the test binary.
# Maybe Bazel will support that in the future, but until then this test guards the current status.
# See also test_run_a_test_and_a_binary_rule_with_input_from_stdin() in
# //src/test/shell/integration:run_test
function test_a_test_rule_with_input_from_stdin() {
  local -r pkg="$FUNCNAME"
  mkdir -p "$pkg" || fail "mkdir -p $pkg failed"
  echo 'sh_test(name = "x", srcs = ["x.sh"])' > "$pkg/BUILD"
  cat > "$pkg/x.sh" <<'eof'
#!/bin/bash
read -n5 FOO
echo "foo=($FOO)"
eof
  chmod +x "$pkg/x.sh"
  echo helloworld | bazel test "//$pkg:x" --test_output=all > "$TEST_log" || fail "Expected success"
  expect_log "foo=()"
}

# Runs a Bazel command, waits for the console output to contain a given message,
# and then interrupts Bazel's execution. The first argument to this function
# indicates the message to wait for, and all other arguments are passed to
# bazel. The output of the command is left in the TEST_log for inspection.
function run_bazel_and_interrupt() {
  local exp_message="${1}"; shift

  rm -f "${TEST_log}"
  bazel "${@}" >"${TEST_log}" 2>&1 &
  local bazel_pid="${!}"
  local timeout=60
  while ! grep -q "${exp_message}" "${TEST_log}"; do
    sleep 1
    timeout=$((timeout - 1))
    [[ "${timeout}" -gt 0 ]] || break
  done
  if [[ "${timeout}" -eq 0 ]]; then
    kill "${bazel_pid}" || true
    fail "Subtest failed to start on time"
  fi
  echo 'Sending SIGINT to Bazel and waiting for completion'
  kill -SIGINT "${bazel_pid}"
  if wait "${bazel_pid}"; then
    fail "Bazel reported success on interrupt"
  fi
  echo 'Bazel command terminated'
}

function do_test_interrupt_streamed_output() {
  # TODO(jmmv): --test_output=streamed, which we need below, doesn't seem to
  # work on Windows: we cannot find the expected test output as it makes
  # progresse. This feature had been broken before (#7392) for subtle reasons
  # and there are no tests for it, so it might be broken again. Investigate and
  # enable this test.
  [[ "$is_windows" == "true" ]] && return 0

  local strategy="${1}"; shift

  mkdir -p pkg
  cat >pkg/BUILD <<EOF
sh_test(
  name = "sleep",
  srcs = ["sleep.sh"],
)
EOF
  cat >pkg/sleep.sh <<'EOF'
#! /bin/sh
echo 'Ready for interrupt'
sleep 10000
EOF
  chmod +x pkg/sleep.sh

  # There used to be a bug that caused Bazel to crash after an interrupt when
  # using test streamed output. The interrupt wouldn't be handled properly by
  # the local strategies, and the callers wouldn't close the stream upon
  # interrupt. Try to do this a few times, checking after each interrupt if
  # Bazel died.
  bazel shutdown
  local jvm_out="$(bazel --max_idle_secs=600 info output_base)/server/jvm.out"
  for i in 1 2; do
    run_bazel_and_interrupt "Ready for interrupt" \
        --max_idle_secs=600 \
        test --test_output=streamed --spawn_strategy="${strategy}" //pkg:sleep

    # We need to give Blaze some time to actually crash and flush out the logs.
    # Otherwise we might not detect the error.
    sleep 5

    # If Bazel crashed at any point, we expect it to tell us it had to restart
    # and/or the jvm.out log contains an error message.
    cat "${jvm_out}" >>"${TEST_log}"
    expect_not_log "Starting local Blaze server"
    if grep -q 'crash in async thread' "${jvm_out}"; then
      fail "Bazel crashed"
    fi
  done
}

function test_interrupt_streamed_output_local() {
  do_test_interrupt_streamed_output local
}

function test_interrupt_streamed_output_sandboxed() {
  do_test_interrupt_streamed_output sandboxed
}

function do_sigint_test() {
  local strategy="${1}"; shift
  local tags="${1}"; shift

  mkdir -p pkg
  cat >pkg/BUILD <<EOF
sh_test(
  name = "test_with_cleanup",
  srcs = ["test_with_cleanup.sh"],
  tags = ${tags},
)
EOF
  cat >pkg/test_with_cleanup.sh <<'EOF'
#! /bin/sh
trap 'echo Caught SIGINT; exit' INT
trap 'echo Caught SIGTERM; sleep 1; echo Cleaned up; sleep 1; exit' TERM
echo 'Ready for interrupt'
for i in $(seq 10000); do
  # If the signal interrupts the sleep, keep sleeping so that the SIGTERM
  # cleanup can actually run.
  sleep 1
done
EOF
  chmod +x pkg/test_with_cleanup.sh

  run_bazel_and_interrupt "Ready for interrupt" \
      test --test_output=streamed --spawn_strategy="${strategy}" \
      //pkg:test_with_cleanup
}

function test_sigint_not_graceful_by_default_local() {
  [[ "$is_windows" == "true" ]] && return 0

  do_sigint_test local '[]'
  expect_not_log 'Caught SIGTERM'
  expect_not_log 'Cleaned up'
  expect_not_log 'Caught SIGINT'
}

function test_sigint_not_graceful_by_default_sandboxed() {
  [[ "$is_windows" == "true" ]] && return 0

  do_sigint_test sandboxed '[]'
  if [[ "$(uname -s)" == "Linux" ]]; then
    # TODO(jmmv): When using the linux-sandbox, interrupt termination is always
    # graceful. Should homogenize behavior with the process-wrapper.
    expect_log 'Caught SIGTERM'
  else
    expect_not_log 'Caught SIGTERM'
    expect_not_log 'Cleaned up'
    expect_not_log 'Caught SIGINT'
  fi
}

function do_test_sigint_with_graceful_termination() {
  local strategy="${1}"; shift

  [[ "$is_windows" == "true" ]] && return 0

  do_sigint_test "${strategy}" '["supports-graceful-termination"]'
  expect_log 'Caught SIGTERM'
  expect_log 'Cleaned up'
  expect_not_log 'Caught SIGINT'
}

function test_sigint_with_graceful_termination_local() {
  do_test_sigint_with_graceful_termination local
}

function test_sigint_with_graceful_termination_sandboxed() {
  do_test_sigint_with_graceful_termination sandboxed
}

function test_env_attribute() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg || fail "mkdir -p $pkg failed"
  cat > $pkg/BUILD <<'EOF'
sh_test(
  name = 't',
  srcs = [':t.sh'],
  data = [':t.dat'],
  env = {
    "ENV_A": "not_inherited",
    "ENV_C": "no_surprise",
    "ENV_DATA": "$(location :t.dat)",
  },
  env_inherit = [
    "ENV_B",
  ],
)
EOF
  cat > $pkg/t.sh <<'EOF'
#!/bin/sh
env
exit 0
EOF
  touch $pkg/t.dat
  chmod +x $pkg/t.sh
  ENV_B=surprise ENV_C=surprise ENV_D=surprise bazel test --test_output=streamed //$pkg:t &> $TEST_log \
      || fail "expected test to pass"
  expect_log "ENV_A=not_inherited"
  expect_log "ENV_B=surprise"
  expect_log "ENV_C=no_surprise"
  expect_not_log "ENV_D=surprise"
  expect_log "ENV_DATA=${pkg}/t.dat"
}

run_suite "test tests"
