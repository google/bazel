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
# Integration tests for "bazel run"

NO_SIGNAL_OVERRIDE=1

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

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

if $is_windows; then
  export LC_ALL=C.utf8
elif [[ "$(uname -s)" == "Linux" ]]; then
  export LC_ALL=C.UTF-8
else
  export LC_ALL=en_US.UTF-8
fi

#### HELPER FUNCTIONS ##################################################

function write_py_files() {
  add_rules_python "MODULE.bazel"
  mkdir -p py || fail "mkdir py failed"

  cat > py/BUILD <<'EOF'
load("@rules_python//python:py_binary.bzl", "py_binary")
load("@rules_python//python:py_test.bzl", "py_test")

py_binary(name = "binary", srcs = ["binary.py"])
py_test(name = "test", srcs = ["test.py"])
EOF

  echo "print('Hello, Python World!')" >py/py.py
  chmod +x py/py.py

  ln -sf py.py py/binary.py
  ln -sf py.py py/test.py
}

function write_cc_source_files() {
  mkdir -p cc
  cat > cc/kitty.cc <<EOF
#include <stdio.h>

int main(void) {
  FILE* f;
  char buf[256];

  f = fopen("cc/hello_kitty.txt", "r");
  if (f == NULL) {
    f = fopen("cc/pussycat.txt", "r");
  }
  if (f == NULL) {
    return 1;
  }

  fgets(buf, 255, f);
  fclose(f);
  printf("%s", buf);
  return 0;
}
EOF

  cat > cc/BUILD <<EOF
cc_binary(name='kitty',
          srcs=['kitty.cc'],
          data=glob(['*.txt'], allow_empty = True))
EOF
}

#### TESTS #############################################################

function test_run_py_binary() {
  write_py_files
  bazel run //py:binary >& $TEST_log || fail "Expected success"
  expect_log_once 'Hello, Python World'
}

function test_run_py_test() {
  write_py_files
  bazel run //py:test >& $TEST_log || fail "Expected success"
  expect_log_once 'Hello, Python World'
}

function test_runfiles_present_cc_binary() {
  if "$is_windows"; then
    # TODO(laszlocsomor): fix this test on Windows, and enable it.
    return
  fi
  write_cc_source_files

  cat > cc/hello_kitty.txt <<EOF
Hello, kitty.
EOF

  bazel run --nobuild_runfile_links //cc:kitty > output \
    || fail "${PRODUCT_NAME} run failed."
  assert_contains "Hello, kitty" output || fail "Output is not OK."

  bazel run --nobuild_runfile_links //cc:kitty > output2 \
    || fail "Second ${PRODUCT_NAME} run failed."
  assert_contains "Hello, kitty" output2 || fail "Output is not OK."
}

function test_runfiles_updated_correctly_with_nobuild_runfile_links {
  if "$is_windows"; then
    # TODO(laszlocsomor): fix this test on Windows, and enable it.
    return
  fi
  write_cc_source_files

  cat > cc/hello_kitty.txt <<EOF
Hello, kitty.
EOF

  bazel run --nobuild_runfile_links //cc:kitty > output \
    || fail "${PRODUCT_NAME} run failed."
  assert_contains "Hello, kitty" output || fail "Output is not OK."

  rm cc/hello_kitty.txt
  cat > cc/pussycat.txt <<EOF
A pussycat.
EOF

  bazel run --nobuild_runfile_links //cc:kitty > output \
    || fail "${PRODUCT_NAME} run failed."
  assert_contains "pussycat" output || fail "Output is not OK."
}

function test_run_with_no_build_runfile_manifests {
  write_cc_source_files

  bazel run --nobuild_runfile_manifests //cc:kitty >& $TEST_log && fail "should have failed"
  expect_log_once "--nobuild_runfile_manifests is incompatible with the \"run\" command"
}

function test_script_file_generation {
  if "$is_windows"; then
    # TODO(laszlocsomor): fix this test on Windows, and enable it.
    return
  fi
  mkdir -p fubar || fail "mkdir fubar failed"
  echo 'sh_binary(name = "fubar", srcs = ["fubar.sh"])' > fubar/BUILD
  echo 'for t in "$@"; do echo "arg: $t"; done' > fubar/fubar.sh
  chmod +x fubar/fubar.sh

  bazel run --script_path=$(pwd)/fubar/output.sh //fubar \
     || fail "${PRODUCT_NAME} run failed (--script_path)."
  grep "fubar \"\$\@\"" ./fubar/output.sh \
     || fail "${PRODUCT_NAME} run --script_path output was incorrect."

  $(pwd)/fubar/output.sh a "b c" d > ./fubar/fubar.output \
     || fail "Generated script exited with an error."
  grep "arg: b c" ./fubar/fubar.output \
     || fail "Generated script did not handle arguments correctly."
}

function test_consistent_command_line_encoding {
  local -r arg="äöüÄÖÜß🌱"

  mkdir -p foo || fail "mkdir foo failed"
  echo 'sh_binary(name = "foo", srcs = ["foo.sh"])' > foo/BUILD
  echo 'sh_test(name = "foo_test", srcs = ["foo.sh"])' >> foo/BUILD
  cat > foo/foo.sh <<EOF
echo "got : \$1"
echo "want: $arg"
test "\$1" = '$arg'
EOF
  chmod +x foo/foo.sh

  bazel run //foo -- "$arg" > $TEST_log 2>&1 \
    || fail "${PRODUCT_NAME} run failed."

  bazel test //foo:foo_test --test_arg="$arg" --test_output=errors \
    || fail "${PRODUCT_NAME} test failed"

  bazel --batch run //foo -- "$arg" > $TEST_log 2>&1 \
    || fail "${PRODUCT_NAME} run failed (--batch)."

  bazel --batch test //foo:foo_test --test_arg="$arg" --test_output=errors \
    || fail "${PRODUCT_NAME} test failed (--batch)"
}

function test_consistent_env_var_encoding {
  local -r env="äöüÄÖÜß🌱"

  mkdir -p foo || fail "mkdir foo failed"
  cat > foo/BUILD <<EOF
sh_test(
    name = "foo_test",
    srcs = ["foo_test.sh"],
    env = {
        "FIXED_KEY": "fixed_value_$env",
    },
    env_inherit = [
        "INHERITED_KEY",
    ],
)
EOF
  cat > foo/foo_test.sh <<EOF
echo "FIXED_KEY:"
echo "got : \${FIXED_KEY}"
echo "want: fixed_value_$env"
test "\${FIXED_KEY}" = "fixed_value_$env"
echo "INHERITED_KEY:"
echo "got : \${INHERITED_KEY}"
echo "want: inherited_value_$env"
test "\${INHERITED_KEY}" = "inherited_value_$env"
EOF
  chmod +x foo/foo_test.sh

  env INHERITED_KEY="inherited_value_$env" \
    bazel run //foo:foo_test > $TEST_log 2>&1 \
    || fail "${PRODUCT_NAME} run failed."

  env INHERITED_KEY="inherited_value_$env" \
    bazel test //foo:foo_test --test_output=errors \
    || fail "${PRODUCT_NAME} test failed"

  env INHERITED_KEY="inherited_value_$env" \
    bazel --batch test //foo:foo_test --test_output=errors \
    || fail "${PRODUCT_NAME} test failed (--batch)"
}

function test_consistent_working_directory_encoding {
  local -r unicode_string="äöüÄÖÜß🌱"

  mkdir -p foo || fail "mkdir foo failed"
  cat > foo/BUILD <<EOF
sh_binary(
    name = "foo",
    srcs = ["foo.sh"],
)
EOF
  cat > foo/foo.sh <<EOF
echo "        got: \${BUILD_WORKING_DIRECTORY}"
echo "want suffix: /foo/subdir_$unicode_string"
[[ "\${BUILD_WORKING_DIRECTORY}" == *"/foo/subdir_$unicode_string" ]]
EOF
  chmod +x foo/foo.sh

  mkdir -p "foo/subdir_$unicode_string"
  cd "foo/subdir_$unicode_string" || fail "cd foo/subdir_$unicode_string failed"

  bazel run //foo > $TEST_log 2>&1 \
    || fail "${PRODUCT_NAME} run failed."

  bazel --batch run //foo \
    || fail "${PRODUCT_NAME} run failed (--batch)."
}

# Tests bazel run with --color=no on a failed build does not produce color.
function test_no_color_on_failed_run() {
  mkdir -p x || fail "mkdir failed"
  echo "cc_binary(name = 'x', srcs = ['x.cc'])" > x/BUILD
  cat > x/x.cc <<EOF
int main(int, char**) {
  // Missing semicolon
  return 0
}
EOF

  bazel run //x:x &>$TEST_log --color=no && fail "expected failure"
  cat $TEST_log
  # Verify that the failure is a build failure.
  if $is_windows; then
    expect_log "missing ';'"
  else
    expect_log "expected ';'"
  fi
  # Hack to make up for grep -P not being supported.
  grep $(echo -e '\x1b') $TEST_log && fail "Expected colorless output"
  true
}


function test_no_ansi_stripping_in_stdout_or_stderr() {
  if $is_windows; then
    # TODO(laszlocsomor): fix this test on Windows, and enable it.
    return
  fi
  mkdir -p x || fail "mkdir failed"
  echo "cc_binary(name = 'x', srcs = ['x.cc'])" > x/BUILD
  cat > x/x.cc <<EOF
#include <unistd.h>
#include <stdio.h>
int main(int, char**) {
  fprintf(stderr, "\nRUN START\n");
  const char out[] = {'<', 0x1B, '[', 'a', ',', 0x1B, '[', '1', '>', 0x0A};
  const char err[] = {'<', 0x1B, '[', 'b', ',', 0x1B, '[', '2', '>', 0x0A};
  write(1, out, 10);
  write(2, err, 10);
  return 0;
}
EOF
  out1color=$(mktemp x/XXXXXX)
  out1nocolor=$(mktemp x/XXXXXX)
  out2=$(mktemp x/XXXXXX)
  err1raw_color=$(mktemp x/XXXXXX)
  err1raw_nocolor=$(mktemp x/XXXXXX)
  err1color=$(mktemp x/XXXXXX)
  err1nocolor=$(mktemp x/XXXXXX)
  err2=$(mktemp x/XXXXXX)
  err2raw=$(mktemp x/XXXXXX)


  # TODO(katre): Figure out why progress rate limiting is required for this on darwin.
  add_to_bazelrc common --show_progress_rate_limit=0.03
  bazel run //x:x --color=yes >$out1color 2>$err1raw_color || fail "expected success"
  bazel run //x:x --color=no >$out1nocolor 2>$err1raw_nocolor || fail "expected success"
  echo >> $err1raw_color
  echo >> $err1raw_nocolor

  ${PRODUCT_NAME}-bin/x/x >$out2 2>$err2raw
  echo >> $err2raw

  # Remove the first newline that is printed so that the output of Bazel can be
  # separated from the output of the test binary
  tail -n +2 $err2raw > $err2

  # Extract the binary's stderr from the raw stderr, which also contains bazel's
  # stderr; if present, remove a trailing ^[[0m (reset terminal to defaults).
  bazel_stderr_line_count_color=$(cat $err1raw_color \
    | grep -n 'RUN START' \
    | awk -F ':' '{print $1}')
  start="$bazel_stderr_line_count_color"
  tail -n +$start $err1raw_color | sed -e 's/.\[0m$//' >$err1color

  cat $err1raw_nocolor
  bazel_stderr_line_count_nocolor=$(cat $err1raw_nocolor \
    | grep -n 'RUN START' \
    | awk -F ':' '{print $1}')
  start="$bazel_stderr_line_count_nocolor"
  tail -n +$start $err1raw_nocolor >$err1nocolor

  diff $out1color $out2 >&$TEST_log || fail "stdout with --color=yes differs"
  diff $out1nocolor $out2 >&$TEST_log || fail "stdout with --color=no differs"
  diff $err1color $err2 >&$TEST_log || fail "stderr with --color=yes differs"
  diff $err1nocolor $err2 >&$TEST_log || fail "stderr with --color=no differs"

  rm -rf x
}

# Test for $(location) in args list of sh_binary
function test_location_in_args() {
  mkdir -p some/testing
  cat > some/testing/BUILD <<'EOF'
genrule(
    name = "generated",
    cmd = "echo 2 > $@",
    outs = ["generated.txt"],
)

sh_binary(
    name = "testing",
    srcs = ["test.sh"],
    data = ["data", ":generated"],
    args = ["$(location :data)", "$(location :generated)"],
)
EOF

  cat > some/testing/test.sh <<'EOF'
#!/usr/bin/env bash
set -ex
echo "Got $@"
i=1
for arg in $@; do
  [[ $((i++)) = $(cat $arg) ]]
done
EOF
  chmod +x some/testing/test.sh

  echo "1" >some/testing/data

  # Arguments are only provided through bazel run, we cannot test it
  # with bazel-bin/some/testing/testing
  bazel run --enable_runfiles=yes //some/testing >$TEST_log || fail "Expected success"
  expect_log "Got .*some/testing/data.*some/testing/generated.txt"
}

function test_run_for_alias() {
  mkdir -p a
  cat > a/BUILD <<EOF
sh_binary(name='a', srcs=['a.sh'])
alias(name='b', actual='a')
EOF

  cat > a/a.sh <<EOF
#!/bin/sh
echo "Dancing with wolves"
exit 0
EOF

  chmod +x a/a.sh
  bazel run //a:b >"$TEST_log" || fail "Expected success"
  expect_log "Dancing with wolves"
}

function test_run_for_custom_executable() {
  mkdir -p a
  if "$is_windows"; then
    local -r IsWindows=True
  else
    local -r IsWindows=False
  fi
  cat > a/x.bzl <<EOF
def _impl(ctx):
    if $IsWindows:
        f = ctx.actions.declare_file("x.bat")
        content = "@if '%1' equ '' (echo Run Forest run) else (echo>%1 Run Forest run)"
    else:
        f = ctx.actions.declare_file("x.sh")
        content = ("#!/bin/sh\n" +
                   "if [ -z \$1 ]; then\\n" +
                   "   echo Run Forest run\\n" +
                   "else\\n" +
                   "   echo Run Forest run > \$1\\n" +
                   "fi")
    ctx.actions.write(f, content, is_executable = True)
    return [DefaultInfo(executable = f)]

my_rule = rule(_impl, executable = True)

def _tool_impl(ctx):
  f = ctx.actions.declare_file("output")
  ctx.actions.run(executable = ctx.executable.tool,
    inputs = [],
    outputs = [f],
    arguments = [f.path]
  )
  return DefaultInfo(files = depset([f]))
my_tool_rule = rule(_tool_impl, attrs = { 'tool' : attr.label(executable = True, cfg = "exec") })
EOF

cat > a/BUILD <<EOF
load(":x.bzl", "my_rule", "my_tool_rule")
my_rule(name = "zzz")
my_tool_rule(name = "kkk", tool = ":zzz")
EOF
  bazel run //a:zzz > "$TEST_log" || fail "Expected success"
  expect_log "Run Forest run"
  bazel build //a:kkk > "$TEST_log" || fail "Expected success"
  grep "Run Forest run" bazel-bin/a/output || fail "Output file wrong"
}

# Integration test for https://github.com/bazelbuild/bazel/pull/8322
# "bazel run" forwards input from stdin to the test binary, to support interactive test re-execution
# (when running browser-based tests) and to support debugging tests.
# See also test_a_test_rule_with_input_from_stdin() in //src/test/shell/integration:test_test
function test_run_a_test_and_a_binary_rule_with_input_from_stdin() {
  if "$is_windows"; then
    # TODO(laszlocsomor): fix this test on Windows, and enable it.
    return
  fi
  mkdir -p a
  cat > a/BUILD <<'eof'
sh_test(name = "x", srcs = ["x.sh"])
sh_binary(name = "control", srcs = ["x.sh"])
eof
  cat > a/x.sh <<'eof'
#!/bin/bash
read -n5 FOO
echo "foo=($FOO)"
eof
  chmod +x a/x.sh
  echo helloworld | bazel run //a:control > "$TEST_log" || fail "Expected success"
  expect_log "foo=(hello)"
  echo hallowelt | bazel run //a:x > "$TEST_log" || fail "Expected success"
  expect_log "foo=(hallo)"
}

function test_default_test_tmpdir() {
  local -r pkg="pkg${LINENO}"
  mkdir -p ${pkg}
  echo "echo \${TEST_TMPDIR} > ${TEST_TMPDIR}/tmpdir_value" > ${pkg}/write.sh
  chmod +x ${pkg}/write.sh

  cat > ${pkg}/BUILD <<'EOF'
sh_test(name="a", srcs=["write.sh"])
EOF

  bazel run //${pkg}:a
  local tmpdir_value
  tmpdir_value="$(cat "${TEST_TMPDIR}/tmpdir_value")"
  expected_prefix="${TEST_TMPDIR}"
  if ${is_windows}; then
    # Work-around replacing the path with a short DOS path.
    tmpdir_value="$(cygpath -m -l "${tmpdir_value}")"
    expected_prefix="${bazel_root}"
  fi
  assert_starts_with "${expected_prefix}/" "${tmpdir_value}"
}

function test_blaze_run_with_custom_test_tmpdir() {
  local -r pkg="pkg${LINENO}"
  mkdir -p ${pkg}
  local tmpdir
  tmpdir="$(mktemp -d)"
  if "${is_windows}"; then
    # Translate from `/*` to a windows path.
    tmpdir="$(cygpath -m "${tmpdir}")"
  fi
  # Check that we execute the intended scenario.
  if [[ "${tmpdir}" == "${TEST_TMPDIR}"* ]]; then
    fail "Temp folder potentially overlaps with the exec root"
  fi
  echo "echo \${TEST_TMPDIR} > ${TEST_TMPDIR}/tmpdir_value" > ${pkg}/write.sh
  chmod +x ${pkg}/write.sh

  cat > ${pkg}/BUILD <<'EOF'
sh_test(name="a", srcs=["write.sh"])
EOF

  bazel run --test_tmpdir="${tmpdir}/test_bazel_run_with_custom_tmpdir" //${pkg}:a
  assert_starts_with "${tmpdir}/test_bazel_run_with_custom_tmpdir" "$(cat "${TEST_TMPDIR}/tmpdir_value")"
}

function test_run_binary_with_env_attribute() {
  local -r pkg="pkg${LINENO}"
  mkdir -p ${pkg}
  cat > $pkg/BUILD <<'EOF'
sh_binary(
  name = 't',
  srcs = [':t.sh'],
  data = [':t.dat'],
  env = {
    "ENV_A": "not_inherited",
    "ENV_C": "no_surprise",
    "ENV_DATA": "$(location :t.dat)",
  },
)
EOF
  cat > $pkg/t.sh <<'EOF'
#!/bin/sh
env
cat $ENV_DATA
exit 0
EOF
  touch $pkg/t.dat
  chmod +x $pkg/t.sh
  ENV_B=surprise ENV_C=surprise bazel run //$pkg:t > $TEST_log \
      || fail "expected test to pass"
  expect_log "ENV_A=not_inherited"
  expect_log "ENV_B=surprise"
  expect_log "ENV_C=no_surprise"
  expect_log "ENV_DATA=$pkg/t.dat"
}

function test_run_under_script() {
  local -r pkg="pkg${LINENO}"
  mkdir -p ${pkg}
  cat > $pkg/BUILD <<'EOF'
sh_binary(
  name = 'greetings',
  srcs = [':greetings.sh'],
)
EOF
  cat > $pkg/greetings.sh <<'EOF'
#!/bin/sh
echo "hello there $@"
EOF
  chmod +x $pkg/greetings.sh
  bazel run --run_under="echo -n 'why ' &&" -- "//$pkg:greetings" friend \
      >$TEST_log || fail "expected test to pass"
  expect_log "why hello there friend"
}

function test_run_under_script_script_path() {
  if $is_windows; then
    # TODO(https://github.com/bazelbuild/bazel/issues/22148): Fix --run_under
    # paths under windows.
    return
  fi
  local -r pkg="pkg${LINENO}"
  mkdir -p "$pkg"
  cat > $pkg/BUILD <<'EOF'
sh_binary(
  name = 'greetings',
  srcs = [':greetings.sh'],
)
EOF
  cat > "$pkg/greetings.sh" <<'EOF'
#!/bin/sh
echo "hello there $@"
EOF
  chmod +x "$pkg/greetings.sh"
  bazel run --script_path="${TEST_TMPDIR}/script.sh" \
      --run_under="echo -n 'why ' &&" \
      -- "//$pkg:greetings" friend \
      >"$TEST_log" || fail "expected build to succeed"
  "${TEST_TMPDIR}/script.sh" >"$TEST_log" || fail "expected run script to succeed"
  expect_log "why hello there friend"
}

function test_run_under_label() {
  local -r pkg="pkg${LINENO}"
  mkdir -p "${pkg}"
  cat > "$pkg/BUILD" <<'EOF'
sh_binary(
  name = 'greetings',
  srcs = ['greetings.sh'],
)

sh_binary(
  name = 'farewell',
  srcs = ['farewell.sh']
)
EOF
  cat > "$pkg/greetings.sh" <<'EOF'
#!/bin/sh
echo "hello there $@"
EOF
  chmod +x "$pkg/greetings.sh"
  cat > "$pkg/farewell.sh" <<'EOF'
#!/bin/sh
echo "goodbye $@"
EOF
  chmod +x "$pkg/farewell.sh"

  bazel run --run_under="//$pkg:greetings friend && unset RUNFILES_MANIFEST_FILE &&" -- "//$pkg:farewell" buddy \
      >$TEST_log || fail "expected test to pass"
  # TODO(https://github.com/bazelbuild/bazel/issues/22148): bazel-team - This is
  # just demonstrating how things are, it's probably not how we want them to be.
  # "unset RUNFILES_MANIFEST_FILE" is necessary because the environment
  # variables set by //pkg:greetings are otherwise passed to //pkg:farewell and
  # break its runfiles discovery.
  if "$is_windows"; then
    expect_log "hello there friend"
    expect_log "goodbye buddy"
  else
    expect_log "hello there friend && unset RUNFILES_MANIFEST_FILE && .*bin/$pkg/farewell buddy"
    expect_not_log "goodbye"
  fi
}

function test_run_under_command_change_preserves_cache() {
  if $is_windows; then
    echo "This test requires --run_under to be able to run echo."
    return
  fi

  local -r pkg="pkg${LINENO}"
  mkdir -p "${pkg}"
  cat > "$pkg/BUILD" <<'EOF'
load(":defs.bzl", "my_rule")
my_rule(
  name = "my_rule",
)
EOF
  cat > "$pkg/defs.bzl" <<'EOF'
def _my_rule_impl(ctx):
  print("my_rule is being analyzed")
  out = ctx.actions.declare_file(ctx.label.name)
  ctx.actions.write(out, "echo -n world", is_executable = True)
  return [DefaultInfo(executable = out)]

my_rule = rule(
  implementation = _my_rule_impl,
  executable = True,
)
EOF

  bazel run "${pkg}:my_rule" >$TEST_log 2>&1 \
   || fail "expected run to pass"
  expect_log "my_rule is being analyzed"
  expect_not_log "hello"
  expect_log "world"

  bazel run --run_under="echo -n hello &&" "${pkg}:my_rule" >$TEST_log 2>&1 \
   || fail "expected run to pass"
  expect_not_log "my_rule is being analyzed"
  expect_log "hello"
  expect_log "world"
}

function test_build_id_env_var() {
  local -r pkg="pkg${LINENO}"
  mkdir -p "${pkg}"
  cat > "$pkg/BUILD" <<'EOF'
sh_binary(
  name = "foo",
  srcs = ["foo.sh"],
)
EOF
  cat > "$pkg/foo.sh" <<'EOF'
#!/bin/bash
echo build_id=\"${BUILD_ID}\"
EOF

  chmod +x "$pkg/foo.sh"
  bazel run "//$pkg:foo" --build_event_text_file=bep.txt >& "$TEST_log" || fail "run failed"
  cat "$TEST_log" | grep "^build_id=" > actual.txt
  cat bep.txt | grep '^  uuid: "' | sed 's/^  uuid: /build_id=/' > expected.txt

  if ! cmp expected.txt actual.txt; then
    fail "BUILD_ID env var not set correctly: expected '$(cat expected.txt)', got '$(cat actual.txt)'"
  fi
}

function test_execroot_env_var() {
  local -r pkg="pkg${LINENO}"
  mkdir -p "${pkg}"
  cat > "$pkg/BUILD" <<'EOF'
sh_binary(
  name = "foo",
  srcs = ["foo.sh"],
)
EOF
  cat > "$pkg/foo.sh" <<'EOF'
#!/bin/bash
echo execroot=\"${BUILD_EXECROOT}\"
EOF

  chmod +x "$pkg/foo.sh"
  echo "execroot=\"$(bazel info execution_root)\"" > expected.txt
  bazel run "//$pkg:foo" >& "$TEST_log" || fail "run failed"
  cat "$TEST_log" | grep "^execroot=" > actual.txt

  if ! cmp expected.txt actual.txt; then
    fail "BUILD_EXECROOT env var not set correctly: expected '$(cat expected.txt)', got '$(cat actual.txt)'"
  fi
}

function test_run_env() {
  local -r pkg="pkg${LINENO}"
  mkdir -p "${pkg}"
  cat > "$pkg/BUILD" <<'EOF'
sh_binary(
  name = "foo",
  srcs = ["foo.sh"],
  env = {
    "FROMBUILD": "1",
    "OVERRIDDEN_RUN_ENV": "2",
  }
)
EOF
  cat > "$pkg/foo.sh" <<'EOF'
#!/bin/bash

set -euo pipefail

echo "FROMBUILD: '$FROMBUILD'"
echo "OVERRIDDEN_RUN_ENV: '$OVERRIDDEN_RUN_ENV'"
echo "RUN_ENV_ONLY: '$RUN_ENV_ONLY'"
EOF

  chmod +x "$pkg/foo.sh"

  bazel run --run_env=OVERRIDDEN_RUN_ENV=FOO --run_env=RUN_ENV_ONLY=BAR "//$pkg:foo" >"$TEST_log" || fail "expected run to succeed"

  expect_log "FROMBUILD: '1'"
  expect_log "OVERRIDDEN_RUN_ENV: 'FOO'"
  expect_log "RUN_ENV_ONLY: 'BAR'"
}

function test_run_env_script_path() {
  local -r pkg="pkg${LINENO}"
  mkdir -p "${pkg}"
  cat > "$pkg/BUILD" <<'EOF'
sh_binary(
  name = "foo",
  srcs = ["foo.sh"],
  env = {
    "FROMBUILD": "1",
    "OVERRIDDEN_RUN_ENV": "2",
  }
)
EOF
  cat > "$pkg/foo.sh" <<'EOF'
#!/bin/bash

set -euo pipefail

echo "FROMBUILD: '$FROMBUILD'"
echo "OVERRIDDEN_RUN_ENV: '$OVERRIDDEN_RUN_ENV'"
echo "RUN_ENV_ONLY: '$RUN_ENV_ONLY'"
EOF

  chmod +x "$pkg/foo.sh"

  bazel run --script_path=script.bat --run_env=OVERRIDDEN_RUN_ENV=FOO --run_env=RUN_ENV_ONLY=BAR "//$pkg:foo" || fail "expected run to succeed"

  ./script.bat >"$TEST_log" || fail "expected script to succeed"

  expect_log "FROMBUILD: '1'"
  expect_log "OVERRIDDEN_RUN_ENV: 'FOO'"
  expect_log "RUN_ENV_ONLY: 'BAR'"
}

# Usage: assert_starts_with PREFIX STRING_TO_CHECK.
# Asserts that `$1` is a prefix of `$2`.
function assert_starts_with() {
  if [[ "${2}" != "${1}"* ]]; then
    fail "${2} does not start with ${1}"
  fi
}

run_suite "'${PRODUCT_NAME} run' integration tests"
