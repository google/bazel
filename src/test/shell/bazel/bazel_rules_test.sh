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
# Test rules provided in Bazel not tested by examples
#

set -euo pipefail
# --- begin runfiles.bash initialization ---
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
  # As of 2019-01-15, Bazel on Windows only supports MSYS Bash.
  declare -r is_windows=true
  declare -r exe_suffix=.exe
  ;;
*)
  declare -r is_windows=false
  declare -r exe_suffix=
  ;;
esac

function test_sh_test() {
  mkdir -p a
  cat > a/BUILD <<EOF
package(default_visibility = ["//visibility:public"])
sh_test(
name = 'success_test',
srcs = [ 'success_test.sh' ],
)

sh_test(
name = 'fail_test',
srcs = [ 'fail_test.sh' ],
)

EOF

  cat > a/success_test.sh <<EOF
#!/bin/sh
echo success-marker
exit 0
EOF

  cat > a/fail_test.sh <<EOF
#!/bin/sh
echo failure-message
exit 1
EOF

  chmod +x a/*.sh

  assert_test_ok //a:success_test
  assert_test_fails //a:fail_test
  expect_log 'failure-message'
}

function test_extra_action() {
  mkdir -p mypkg
  # Make a program to run on each action that just prints the path to the extra
  # action file. This file is a proto, but I don't want to bother implementing
  # a program that parses the proto here.
  cat > mypkg/echoer.sh << 'EOF'
#!/bin/bash
set -euo pipefail
# --- begin runfiles.bash initialization ---
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

if [[ ! -e "$(rlocation _main/mypkg/runfile)" ]]; then
  echo "ERROR: Runfile not found" >&2
  exit 1
fi
echo EXTRA ACTION FILE: \$1
EOF
  chmod +x mypkg/echoer.sh

  cat > mypkg/Hello.java <<EOF
public class Hello {
    public void sayHi() {
        System.out.println("hi");
    }
}
EOF

  touch mypkg/runfile

  cat > mypkg/BUILD <<EOF
package(default_visibility = ["//visibility:public"])

extra_action(
    name = "echo-filename",
    cmd = "\$(location :echoer) \$(EXTRA_ACTION_FILE)",
    tools = [":echoer"],
)

action_listener(
    name = "al",
    extra_actions = [":echo-filename"],
    mnemonics = ["Javac"],
)

sh_binary(
    name = "echoer",
    srcs = ["echoer.sh"],
    data = [
        "runfile",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

java_library(
    name = "hello",
    srcs = ["Hello.java"],
)
EOF

    bazel build --experimental_action_listener=//mypkg:al //mypkg:hello >& $TEST_log \
      || fail "Building with action listener failed"
    expect_log "EXTRA ACTION FILE"
}

function test_with_arguments() {
  mkdir -p mypkg
  cat > mypkg/BUILD <<EOF
sh_test(
    name = "expected_arg_test",
    srcs = ["check_expected_argument.sh"],
    args = ["expected_value"],
)

sh_test(
    name = "unexpected_arg_test",
    srcs = ["check_expected_argument.sh"],
    args = ["unexpected_value"],
)
EOF
  cat > mypkg/check_expected_argument.sh <<EOF
#!/bin/sh
[ "expected_value" = "\$1" ] || exit 1
EOF

  chmod +x mypkg/check_expected_argument.sh

  assert_test_ok //mypkg:expected_arg_test
  assert_test_fails //mypkg:unexpected_arg_test
}

function test_top_level_test() {
  cat > BUILD <<EOF
sh_test(
    name = "trivial_test",
    srcs = ["true.sh"],
)
EOF
  cat > true.sh <<EOF
#!/bin/sh
exit 0
EOF

  chmod +x true.sh

  assert_test_ok //:trivial_test
}

# Regression test for https://github.com/bazelbuild/bazel/issues/67
# C++ library depedending on C++ library fails to compile on Darwin
function test_cpp_libdeps() {
  mkdir -p pkg
  cat <<'EOF' >pkg/BUILD
cc_library(
  name = "a",
  srcs = ["a.cc"],
)

cc_library(
  name = "b",
  srcs = ["b.cc"],
  deps = [":a"],
)

cc_binary(
  name = "main",
  srcs = ["main.cc"],
  deps = [":b"],
)
EOF

  cat <<'EOF' >pkg/a.cc
#include <string>

std::string get_hello(std::string world) {
  return "Hello, " + world + "!";
}
EOF

  cat <<'EOF' >pkg/b.cc
#include <string>
#include <iostream>

std::string get_hello(std::string);

void print_hello(std::string world) {
  std::cout << get_hello(world) << std::endl;
}
EOF

  cat <<'EOF' >pkg/main.cc
#include <string>
void print_hello(std::string);

int main() {
   print_hello(std::string("World"));
}
EOF

  bazel build //pkg:a >& $TEST_log \
    || fail "Failed to build //pkg:a"
  bazel build //pkg:b >& $TEST_log \
    || fail "Failed to build //pkg:b"
  bazel run //pkg:main >& $TEST_log \
    || fail "Failed to run //pkg:main"
  expect_log "Hello, World!"
  ./bazel-bin/pkg/main >& $TEST_log \
    || fail "Failed to run //pkg:main"
  expect_log "Hello, World!"
}


function test_genrule_default_env() {
  mkdir -p pkg
  cat >pkg/BUILD  <<'EOF'
genrule(
    name = "test",
    outs = ["test.out"],
    cmd = "env > $@",
)
EOF

  local new_tmpdir="$(mktemp -d "${TEST_TMPDIR}/newfancytmpdirXXXXXX")"
  if $is_windows; then
    PATH="/random/path:$PATH" TMP="${new_tmpdir}" \
      bazel build //pkg:test --spawn_strategy=standalone --action_env=PATH \
      &> $TEST_log || fail "Failed to build //pkg:test"

    # Test that Bazel respects the client environment's TMP.
    # new_tmpdir is based on $TEST_TMPDIR which is not Unix-style -- convert it.
    assert_contains "TMP=$(cygpath -u "${new_tmpdir}")" bazel-bin/pkg/test.out
  else
    PATH="/random/path:$PATH" TMPDIR="${new_tmpdir}" \
      bazel build //pkg:test --spawn_strategy=standalone --action_env=PATH \
      &> $TEST_log || fail "Failed to build //pkg:test"

    # Test that Bazel respects the client environment's TMPDIR.
    assert_contains "TMPDIR=${new_tmpdir}" bazel-bin/pkg/test.out
  fi

  # Test that Bazel passed through the PATH from --action_env.
  assert_contains "PATH=/random/path" bazel-bin/pkg/test.out
}

function test_genrule_remote() {
  cat > MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "other_repo",
    path = "other_repo",
)
EOF
  mkdir other_repo && cd other_repo
  touch REPO.bazel
  mkdir package
  cat > package/BUILD <<EOF
genrule(
    name = "abs_dep",
    srcs = ["//package:in"],
    outs = ["abs_dep.out"],
    cmd = "echo '\$(locations //package:in)' > \$@",
)

sh_binary(
    name = "in",
    srcs = ["in.sh"],
)
EOF

  cat > package/in.sh << EOF
#!/bin/sh
echo "Hi"
EOF
  chmod +x package/in.sh
  cd ..

  bazel build @other_repo//package:abs_dep >$TEST_log 2>&1 || fail "Should build"
}

function test_genrule_remote_d() {
  cat > MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "other_repo",
    path = "other_repo",
)
EOF
  mkdir other_repo && cd other_repo
  touch REPO.bazel
  mkdir package
  cat > package/BUILD <<'EOF'
genrule(
    name = "hi",
    outs = [
        "a/b",
        "c/d"
    ],
    cmd = "echo 'hi' | tee $(@D)/a/b $(@D)/c/d",
)
EOF
  cd ..

  bazel build @other_repo//package:hi >$TEST_log 2>&1 || fail "Should build"
  expect_log "bazel-.*bin/external/+_repo_rules+other_repo/package/a/b"
  expect_log "bazel-.*bin/external/+_repo_rules+other_repo/package/c/d"
}

function test_genrule_toolchain_dependency {
  mkdir -p t
  cat > t/BUILD <<EOF
genrule(
    name = "toolchain_check",
    outs = ["version"],
    toolchains = ['@bazel_tools//tools/jdk:current_host_java_runtime'],
    cmd = "ls -al \$(JAVABASE) > \$@",
)
EOF
  bazel build //t:toolchain_check >$TEST_log 2>&1 || fail "Should build"
  expect_log "bazel-.*bin/t/version"
  expect_not_log "ls: cannot access"
}

function test_build_python_zip_with_python_binary_as_data() {
  mkdir py
  touch py/data.txt
  cat > py/BUILD <<EOF
py_binary(name = "bin", srcs = ["bin.py"], data = ["data.txt"])
py_binary(name = "bin2", srcs = ["bin2.py"], data = [":bin"])
EOF
  cat > py/bin.py <<EOF
print("hello")
EOF
  cat > py/bin2.py <<EOF
print("world")
EOF
  bazel build --build_python_zip //py:bin2 || fail "build failed"
  # `unzip` prints the right output but exits with non-zero, because the zip
  # file starts with a shebang line. Capture the output and swallow this benign
  # error, and only assert the output.
  local found=$(unzip -l ./bazel-bin/py/bin2 | grep "data.txt" || echo "")
  [[ -n "$found" ]] || fail "failed to zip data file"
}

function test_build_with_aliased_input_file() {
  mkdir -p a
  cat > a/BUILD <<EOF
exports_files(['f'])
alias(name='a', actual=':f')
EOF

  touch a/f
  bazel build //a:a || fail "build failed"
}

function test_executable_without_default_files() {
  mkdir pkg
  cat >pkg/BUILD <<'EOF'
load(":rules.bzl", "bin_rule", "out_rule")
bin_rule(name = "hello_bin")
out_rule(name = "hello_out")

genrule(
    name = "hello_gen",
    tools = [":hello_bin"],
    outs = ["hello_gen.txt"],
    cmd = "$(location :hello_bin) $@",
)
EOF

  # On Windows this file needs to be acceptable by CreateProcessW(), rather
  # than a Bourne script.
  if "$is_windows"; then
    cat >pkg/rules.bzl <<'EOF'
_SCRIPT_EXT = ".bat"
_SCRIPT_CONTENT = "@ECHO OFF\necho hello world > %1"
EOF
  else
    cat >pkg/rules.bzl <<'EOF'
_SCRIPT_EXT = ".sh"
_SCRIPT_CONTENT = "#!/bin/sh\necho 'hello world' > $@"
EOF
  fi

  cat >>pkg/rules.bzl <<'EOF'
def _bin_rule(ctx):
    out_sh = ctx.actions.declare_file(ctx.attr.name + _SCRIPT_EXT)
    ctx.actions.write(
        output = out_sh,
        content = _SCRIPT_CONTENT,
        is_executable = True,
    )
    return DefaultInfo(
        files = depset(direct = []),
        executable = out_sh,
    )

def _out_rule(ctx):
    out = ctx.actions.declare_file(ctx.attr.name + ".txt")
    ctx.actions.run(
        executable = ctx.executable._hello_bin,
        outputs = [out],
        arguments = [out.path],
        mnemonic = "HelloOut",
    )
    return DefaultInfo(
        files = depset(direct = [out]),
    )

bin_rule = rule(_bin_rule, executable = True)
out_rule = rule(_out_rule, attrs = {
    "_hello_bin": attr.label(
        default = ":hello_bin",
        executable = True,
        cfg = "exec",
    ),
})
EOF

  bazel build //pkg:hello_out //pkg:hello_gen >$TEST_log 2>&1 || fail "Should build"
  assert_contains "hello world" bazel-bin/pkg/hello_out.txt
  assert_contains "hello world" bazel-bin/pkg/hello_gen.txt
}

function test_starlark_test_with_test_environment() {
  mkdir pkg
  cat >pkg/BUILD <<'EOF'
load(":rules.bzl", "my_test")
my_test(
  name = "my_test",
)
EOF

  # On Windows this file needs to be acceptable by CreateProcessW(), rather
  # than a Bourne script.
  if "$is_windows"; then
    cat >pkg/rules.bzl <<'EOF'
_SCRIPT_EXT = ".bat"
_SCRIPT_CONTENT = """@ECHO OFF
if not "%FIXED_ONLY%" == "fixed" exit /B 1
if not "%FIXED_AND_INHERITED%" == "inherited" exit /B 1
if not "%FIXED_AND_INHERITED_BUT_NOT_SET%" == "fixed" exit /B 1
if not "%INHERITED_ONLY%" == "inherited" exit /B 1
if defined INHERITED_BUT_UNSET exit /B 1
"""
EOF
  else
    cat >pkg/rules.bzl <<'EOF'
_SCRIPT_EXT = ".sh"
_SCRIPT_CONTENT = """#!/bin/bash
[[ "$FIXED_ONLY" == "fixed" \
  && "$FIXED_AND_INHERITED" == "inherited" \
  && "$FIXED_AND_INHERITED_BUT_NOT_SET" == "fixed" \
  && "$INHERITED_ONLY" == "inherited" \
  && -z "${INHERITED_BUT_UNSET+default}" ]]
"""
EOF
  fi

  cat >>pkg/rules.bzl <<'EOF'
def _my_test_impl(ctx):
    test_sh = ctx.actions.declare_file(ctx.attr.name + _SCRIPT_EXT)
    ctx.actions.write(
        output = test_sh,
        content = _SCRIPT_CONTENT,
        is_executable = True,
    )
    test_env = testing.TestEnvironment(
      {
        "FIXED_AND_INHERITED": "fixed",
        "FIXED_AND_INHERITED_BUT_NOT_SET": "fixed",
        "FIXED_ONLY": "fixed",
      },
      [
        "FIXED_AND_INHERITED",
        "FIXED_AND_INHERITED_BUT_NOT_SET",
        "INHERITED_ONLY",
        "INHERITED_BUT_UNSET",
      ]
    )
    return [
        DefaultInfo(
            executable = test_sh,
        ),
        test_env,
    ]

my_test = rule(
    implementation = _my_test_impl,
    attrs = {},
    test = True,
)
EOF

  FIXED_AND_INHERITED=inherited INHERITED_ONLY=inherited \
    bazel test //pkg:my_test >$TEST_log 2>&1 || fail "Test should pass"
}

function test_starlark_rule_with_run_environment() {
  mkdir pkg
  cat >pkg/BUILD <<'EOF'
load(":rules.bzl", "my_executable")
my_executable(
  name = "my_executable",
)
EOF

  # On Windows this file needs to be acceptable by CreateProcessW(), rather
  # than a Bourne script.
  if "$is_windows"; then
    cat >pkg/rules.bzl <<'EOF'
_SCRIPT_EXT = ".bat"
_SCRIPT_CONTENT = """@ECHO OFF
if not "%FIXED_ONLY%" == "fixed" exit /B 1
if not "%FIXED_AND_INHERITED%" == "inherited" exit /B 1
if not "%FIXED_AND_INHERITED_BUT_NOT_SET%" == "fixed" exit /B 1
if not "%INHERITED_ONLY%" == "inherited" exit /B 1
if defined INHERITED_BUT_UNSET exit /B 1
"""
EOF
  else
    cat >pkg/rules.bzl <<'EOF'
_SCRIPT_EXT = ".sh"
_SCRIPT_CONTENT = """#!/bin/bash
set -x
env
[[ "$FIXED_ONLY" == "fixed" \
  && "$FIXED_AND_INHERITED" == "inherited" \
  && "$FIXED_AND_INHERITED_BUT_NOT_SET" == "fixed" \
  && "$INHERITED_ONLY" == "inherited" \
  && -z "${INHERITED_BUT_UNSET+default}" ]]
"""
EOF
  fi

  cat >>pkg/rules.bzl <<'EOF'
def _my_executable_impl(ctx):
    executable_sh = ctx.actions.declare_file(ctx.attr.name + _SCRIPT_EXT)
    ctx.actions.write(
        output = executable_sh,
        content = _SCRIPT_CONTENT,
        is_executable = True,
    )
    run_env = RunEnvironmentInfo(
      {
        "FIXED_AND_INHERITED": "fixed",
        "FIXED_AND_INHERITED_BUT_NOT_SET": "fixed",
        "FIXED_ONLY": "fixed",
      },
      [
        "FIXED_AND_INHERITED",
        "FIXED_AND_INHERITED_BUT_NOT_SET",
        "INHERITED_ONLY",
        "INHERITED_BUT_UNSET",
      ]
    )
    return [
        DefaultInfo(
            executable = executable_sh,
        ),
        run_env,
    ]

my_executable = rule(
    implementation = _my_executable_impl,
    attrs = {},
    executable = True,
)
EOF

  FIXED_AND_INHERITED=inherited INHERITED_ONLY=inherited \
    bazel run //pkg:my_executable >$TEST_log 2>&1 || fail "Binary should have exit code 0"
}

function setup_bash_runfiles_current_repository() {
  cat > MODULE.bazel <<'EOF'
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "other_repo",
  path = "other_repo",
)
EOF

  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
sh_library(
  name = "library",
  srcs = ["library.sh"],
  deps = ["@bazel_tools//tools/bash/runfiles"],
  visibility = ["//visibility:public"],
)
sh_binary(
  name = "binary",
  srcs = ["binary.sh"],
  deps = [
    ":library",
    "@other_repo//pkg:library2",
    "@bazel_tools//tools/bash/runfiles",
  ],
)
sh_test(
  name = "test",
  srcs = ["test.sh"],
  deps = [
    ":library",
    "@other_repo//pkg:library2",
    "@bazel_tools//tools/bash/runfiles",
  ],
)
EOF

  cat > pkg/library.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

function library() {
  echo "in pkg/library.sh: '$(runfiles_current_repository)'"
}
export -f library
EOF
  chmod +x pkg/library.sh

  cat > pkg/binary.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

echo "in pkg/binary.sh: '$(runfiles_current_repository)'"
source $(rlocation _main/pkg/library.sh)
library
source $(rlocation other_repo/pkg/library2.sh)
library2
EOF
  chmod +x pkg/binary.sh

  cat > pkg/test.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

echo "in pkg/test.sh: '$(runfiles_current_repository)'"
source $(rlocation _main/pkg/library.sh)
library
source $(rlocation other_repo/pkg/library2.sh)
library2
EOF
  chmod +x pkg/test.sh

  mkdir -p other_repo
  touch other_repo/REPO.bazel

  mkdir -p other_repo/pkg
  cat > other_repo/pkg/BUILD.bazel <<'EOF'
sh_library(
  name = "library2",
  srcs = ["library2.sh"],
  deps = ["@bazel_tools//tools/bash/runfiles"],
  visibility = ["//visibility:public"],
)
sh_binary(
  name = "binary",
  srcs = ["binary.sh"],
  deps = [
    "//pkg:library2",
    "@//pkg:library",
    "@bazel_tools//tools/bash/runfiles",
  ],
)
sh_test(
  name = "test",
  srcs = ["test.sh"],
  deps = [
    "//pkg:library2",
    "@//pkg:library",
    "@bazel_tools//tools/bash/runfiles",
  ],
)
EOF

  cat > other_repo/pkg/library2.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

function library2() {
  echo "in external/other_repo/pkg/library2.sh: '$(runfiles_current_repository)'"
}
export -f library2
EOF
  chmod +x pkg/library.sh

  cat > other_repo/pkg/binary.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

echo "in external/other_repo/pkg/binary.sh: '$(runfiles_current_repository)'"
source $(rlocation _main/pkg/library.sh)
library
source $(rlocation other_repo/pkg/library2.sh)
library2
EOF
  chmod +x other_repo/pkg/binary.sh

  cat > other_repo/pkg/test.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

echo "in external/other_repo/pkg/test.sh: '$(runfiles_current_repository)'"
source $(rlocation _main/pkg/library.sh)
library
source $(rlocation other_repo/pkg/library2.sh)
library2
EOF
  chmod +x other_repo/pkg/test.sh
}

# Runs the given command with all runfiles environment variables removed from
# the environment. Also enables debug logging for the runfiles library.
function clean_runfiles_run() {
  env -u RUNFILES_DIR -u RUNFILES_MANIFEST_FILE -u RUNFILES_MANIFEST_ONLY \
    RUNFILES_LIB_DEBUG=1 "$@"
}

function test_bash_runfiles_current_repository_binary_enable_runfiles() {
  setup_bash_runfiles_current_repository

  RUNFILES_LIB_DEBUG=1 bazel run --enable_bzlmod --enable_runfiles //pkg:binary \
    &>"$TEST_log" || fail "Run should succeed"
  expect_log "in pkg/binary.sh: ''"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"

  RUNFILES_LIB_DEBUG=1 bazel run --enable_bzlmod --enable_runfiles @other_repo//pkg:binary \
    &>"$TEST_log" || fail "Run should succeed"
  expect_log "in external/other_repo/pkg/binary.sh: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_binary_enable_runfiles_direct_run() {
  setup_bash_runfiles_current_repository

  bazel build --enable_bzlmod --enable_runfiles //pkg:binary \
    &>"$TEST_log" || fail "Build should succeed"
  clean_runfiles_run bazel-bin/pkg/binary$exe_suffix &>"$TEST_log" \
    || fail "Direct run should succeed"
  expect_log "in pkg/binary.sh: ''"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"

  bazel run --enable_bzlmod --enable_runfiles @other_repo//pkg:binary \
    &>"$TEST_log" || fail "Run should succeed"
  clean_runfiles_run bazel-bin/external/+_repo_rules+other_repo/pkg/binary$exe_suffix \
    &>"$TEST_log" || fail "Direct run should succeed"
  expect_log "in external/other_repo/pkg/binary.sh: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_test_enable_runfiles() {
  setup_bash_runfiles_current_repository

  bazel test --enable_bzlmod --enable_runfiles --test_env=RUNFILES_LIB_DEBUG=1 \
    --test_output=all //pkg:test &>"$TEST_log" || fail "Test should succeed"
  expect_log "in pkg/test.sh: ''"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"

  bazel test --enable_bzlmod --enable_runfiles --test_env=RUNFILES_LIB_DEBUG=1 \
    --test_output=all @other_repo//pkg:test &>"$TEST_log" || fail "Test should succeed"
  expect_log "in external/other_repo/pkg/test.sh: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_binary_noenable_runfiles() {
  setup_bash_runfiles_current_repository

  RUNFILES_LIB_DEBUG=1 bazel run --enable_bzlmod --noenable_runfiles //pkg:binary \
    &>"$TEST_log" || fail "Run should succeed"
  expect_log "in pkg/binary.sh: ''"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"

  RUNFILES_LIB_DEBUG=1 bazel run --enable_bzlmod --noenable_runfiles @other_repo//pkg:binary \
    &>"$TEST_log" || fail "Run should succeed"
  expect_log "in external/other_repo/pkg/binary.sh: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_binary_noenable_runfiles_direct_run() {
  setup_bash_runfiles_current_repository

  bazel build --enable_bzlmod --noenable_runfiles //pkg:binary \
    &>"$TEST_log" || fail "Build should succeed"
  clean_runfiles_run bazel-bin/pkg/binary$exe_suffix &>"$TEST_log" \
    || fail "Direct run should succeed"
  expect_log "in pkg/binary.sh: ''"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"

  bazel run --enable_bzlmod --noenable_runfiles @other_repo//pkg:binary \
    &>"$TEST_log" || fail "Run should succeed"
  clean_runfiles_run bazel-bin/external/+_repo_rules+other_repo/pkg/binary$exe_suffix \
    &>"$TEST_log" || fail "Direct run should succeed"
  expect_log "in external/other_repo/pkg/binary.sh: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_test_noenable_runfiles() {
  setup_bash_runfiles_current_repository

  bazel test --enable_bzlmod --noenable_runfiles --test_env=RUNFILES_LIB_DEBUG=1 \
    --test_output=all //pkg:test &>"$TEST_log" || fail "Test should succeed"
  expect_log "in pkg/test.sh: ''"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"

  bazel test --enable_bzlmod --noenable_runfiles --test_env=RUNFILES_LIB_DEBUG=1 \
    --test_output=all @other_repo//pkg:test &>"$TEST_log" || fail "Test should succeed"
  expect_log "in external/other_repo/pkg/test.sh: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_binary_nobuild_runfile_links() {
  setup_bash_runfiles_current_repository

  RUNFILES_LIB_DEBUG=1 bazel run --enable_bzlmod --nobuild_runfile_links //pkg:binary \
    &>"$TEST_log" || fail "Run should succeed"
  expect_log "in pkg/binary.sh: ''"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"

  RUNFILES_LIB_DEBUG=1 bazel run --enable_bzlmod --nobuild_runfile_links @other_repo//pkg:binary \
    &>"$TEST_log" || fail "Run should succeed"
  expect_log "in external/other_repo/pkg/binary.sh: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_binary_nobuild_runfile_links_direct_run() {
  setup_bash_runfiles_current_repository

  bazel build --enable_bzlmod --nobuild_runfile_links //pkg:binary \
    &>"$TEST_log" || fail "Build should succeed"
  clean_runfiles_run bazel-bin/pkg/binary$exe_suffix &>"$TEST_log" \
    || fail "Direct run should succeed"
  expect_log "in pkg/binary.sh: ''"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"

  bazel run --enable_bzlmod --nobuild_runfile_links @other_repo//pkg:binary \
    &>"$TEST_log" || fail "Run should succeed"
  clean_runfiles_run bazel-bin/external/+_repo_rules+other_repo/pkg/binary$exe_suffix \
    &>"$TEST_log" || fail "Direct run should succeed"
  expect_log "in external/other_repo/pkg/binary.sh: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_test_nobuild_runfile_links() {
  setup_bash_runfiles_current_repository

  bazel test --enable_bzlmod --noenable_runfiles --nobuild_runfile_links \
    --test_env=RUNFILES_LIB_DEBUG=1 --test_output=all //pkg:test \
    &>"$TEST_log" || fail "Test should succeed"
  expect_log "in pkg/test.sh: ''"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"

  bazel test --enable_bzlmod --noenable_runfiles --nobuild_runfile_links \
    --test_env=RUNFILES_LIB_DEBUG=1 --test_output=all @other_repo//pkg:test \
    &>"$TEST_log" || fail "Test should succeed"
  expect_log "in external/other_repo/pkg/test.sh: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.sh: ''"
  expect_log "in external/other_repo/pkg/library2.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_action_binary_main_repo() {
  setup_module_dot_bazel "MODULE.bazel"

  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
genrule(
  name = "gen",
  outs = ["out"],
  tools = [":binary"],
  cmd = "$(location :binary) && touch $@",
)

sh_binary(
  name = "binary",
  srcs = ["binary.sh"],
  deps = [
    "@bazel_tools//tools/bash/runfiles",
  ],
  visibility = ["//visibility:public"],
)
EOF

  cat > pkg/binary.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

echo "in pkg/binary.sh: '$(runfiles_current_repository)'"
EOF
  chmod +x pkg/binary.sh

  bazel build --enable_bzlmod //pkg:gen &>"$TEST_log" || fail "Build should succeed"
  expect_log "in pkg/binary.sh: ''"
}

function test_bash_runfiles_current_repository_action_generated_binary_main_repo() {
  setup_module_dot_bazel "MODULE.bazel"

  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
genrule(
  name = "gen",
  outs = ["out"],
  tools = [":binary"],
  cmd = "$(location :binary) && touch $@",
)

genrule(
  name = "copy_binary",
  outs = ["gen_binary.sh"],
  srcs = ["binary.sh"],
  cmd = "cp $(location binary.sh) $@",
)

sh_binary(
  name = "binary",
  srcs = ["binary.sh"],
  deps = [
    "@bazel_tools//tools/bash/runfiles",
  ],
  visibility = ["//visibility:public"],
)
EOF

  cat > pkg/binary.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

echo "in copy of pkg/gen_binary.sh: '$(runfiles_current_repository)'"
EOF
  chmod +x pkg/binary.sh

  bazel build --enable_bzlmod //pkg:gen &>"$TEST_log" || fail "Build should succeed"
  expect_log "in copy of pkg/gen_binary.sh: ''"
}

function test_bash_runfiles_current_repository_action_binary_external_repo() {
  cat > MODULE.bazel <<'EOF'
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "other_repo",
  path = "other_repo",
)
EOF

  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
genrule(
  name = "gen",
  outs = ["out"],
  tools = ["@other_repo//pkg:binary"],
  cmd = "$(location @other_repo//pkg:binary) && touch $@",
)
EOF

  mkdir -p other_repo
  touch other_repo/REPO.bazel

  mkdir -p other_repo/pkg
  cat > other_repo/pkg/BUILD.bazel <<'EOF'
sh_binary(
  name = "binary",
  srcs = ["binary.sh"],
  deps = [
    "@bazel_tools//tools/bash/runfiles",
  ],
  visibility = ["//visibility:public"],
)
EOF

  cat > other_repo/pkg/binary.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

echo "in external/other_repo/pkg/binary.sh: '$(runfiles_current_repository)'"
EOF
  chmod +x other_repo/pkg/binary.sh

  bazel build --enable_bzlmod //pkg:gen &>"$TEST_log" || fail "Build should succeed"
  expect_log "in external/other_repo/pkg/binary.sh: '+_repo_rules+other_repo'"
}

function test_bash_runfiles_current_repository_action_generated_binary_external_repo() {
  cat > MODULE.bazel <<'EOF'
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "other_repo",
  path = "other_repo",
)
EOF

  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
genrule(
  name = "gen",
  outs = ["out"],
  tools = ["@other_repo//pkg:binary"],
  cmd = "$(location @other_repo//pkg:binary) && touch $@",
)
EOF

  mkdir -p other_repo
  touch other_repo/REPO.bazel

  mkdir -p other_repo/pkg
  cat > other_repo/pkg/BUILD.bazel <<'EOF'
genrule(
  name = "copy_binary",
  outs = ["gen_binary.sh"],
  srcs = ["binary.sh"],
  cmd = "cp $(location binary.sh) $@",
)

sh_binary(
  name = "binary",
  srcs = ["gen_binary.sh"],
  deps = [
    "@bazel_tools//tools/bash/runfiles",
  ],
  visibility = ["//visibility:public"],
)
EOF

  cat > other_repo/pkg/binary.sh <<'EOF'
#!/usr/bin/env bash
# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

echo "in copy of external/other_repo/pkg/binary.sh: '$(runfiles_current_repository)'"
EOF
  chmod +x other_repo/pkg/binary.sh

  bazel build --enable_bzlmod //pkg:gen &>"$TEST_log" || fail "Build should succeed"
  expect_log "in copy of external/other_repo/pkg/binary.sh: '+_repo_rules+other_repo'"
}

run_suite "rules test"
