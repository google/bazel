#!/bin/bash
#
# Copyright 2020 The Bazel Authors. All rights reserved.
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
# Test building targets that are declared as compatible only with certain
# platforms (see the "target_compatible_with" common build rule attribute).

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

# `uname` returns the current platform, e.g "MSYS_NT-10.0" or "Linux".
# `tr` converts all upper case letters to lower case.
# `case` matches the result if the `uname | tr` expression to string prefixes
# that use the same wildcards as names do in Bash, i.e. "msys*" matches strings
# starting with "msys", and "*" matches everything (it's the default case).
case "$(uname -s | tr [:upper:] [:lower:])" in
msys*)
  # As of 2019-01-15, Bazel on Windows only supports MSYS Bash.
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

function set_up() {
  add_platforms "MODULE.bazel"
  mkdir -p target_skipping || fail "couldn't create directory"
  cat > target_skipping/pass.sh <<'EOF'
#!/bin/bash
exit 0
EOF
  chmod +x target_skipping/pass.sh

  cat > target_skipping/fail.sh <<'EOF'
#!/bin/bash
exit 1
EOF
  chmod +x target_skipping/fail.sh
  # Not using 'EOF' because injecting default_host_platform
  cat > target_skipping/BUILD <<EOF
# We're not validating visibility here. Let everything access these targets.
package(default_visibility = ["//visibility:public"])

constraint_setting(name = "foo_version")

constraint_value(
    name = "foo1",
    constraint_setting = ":foo_version",
)

constraint_value(
    name = "foo2",
    constraint_setting = ":foo_version",
)

constraint_value(
    name = "foo3",
    constraint_setting = ":foo_version",
)

constraint_setting(name = "bar_version")

constraint_value(
    name = "bar1",
    constraint_setting = "bar_version",
)

constraint_value(
    name = "bar2",
    constraint_setting = "bar_version",
)

platform(
    name = "foo1_bar1_platform",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo1",
        ":bar1",
    ],
)

platform(
    name = "foo2_bar1_platform",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo2",
        ":bar1",
    ],
)

platform(
    name = "foo1_bar2_platform",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo1",
        ":bar2",
    ],
)

platform(
    name = "foo3_platform",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo3",
    ],
)

platform(
    name = "foo3_bar2_platform",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo3",
        ":bar2",
    ],
)

sh_test(
    name = "pass_on_foo1",
    srcs = ["pass.sh"],
    target_compatible_with = [":foo1"],
)

sh_test(
    name = "fail_on_foo2",
    srcs = ["fail.sh"],
    target_compatible_with = [":foo2"],
)

sh_test(
    name = "pass_on_foo1_bar2",
    srcs = ["pass.sh"],
    target_compatible_with = [
        ":foo1",
        ":bar2",
    ],
)

sh_binary(
    name = "some_foo3_target",
    srcs = ["pass.sh"],
    target_compatible_with = [
        ":foo3",
    ],
)

# Use this to let us change select() statements from the command line.
config_setting(
  name = "setting1",
  define_values = {
    "foo": "1",
  },
)
EOF
}

add_to_bazelrc "test --nocache_test_results"
add_to_bazelrc "build --incompatible_merge_genfiles_directory=true"

function tear_down() {
  bazel shutdown
}

function set_up_custom_toolchain() {
  mkdir -p target_skipping/custom_tools/
  cat > target_skipping/custom_tools/BUILD <<'EOF'
load(":toolchain.bzl", "custom_toolchain")

package(default_visibility = ["//visibility:public"])

toolchain_type(name = "toolchain_type")

custom_toolchain(
    name = "toolchain",
    compiler_path = "customc",
)

toolchain(
    name = "custom_foo3_toolchain",
    exec_compatible_with = [
        "//target_skipping:foo3",
    ],
    target_compatible_with = [
        "//target_skipping:foo3",
    ],
    toolchain = ":toolchain",
    toolchain_type = ":toolchain_type",
)
EOF

  cat > target_skipping/custom_tools/toolchain.bzl <<'EOF'
def _custom_binary_impl(ctx):
    info = ctx.toolchains["//target_skipping/custom_tools:toolchain_type"].custom_info
    out = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(out, "%s %s" % (info.compiler_path, ctx.file.src.short_path))

custom_binary = rule(
    implementation = _custom_binary_impl,
    attrs = {
        "src": attr.label(allow_single_file=True),
    },
    toolchains = ["//target_skipping/custom_tools:toolchain_type"],
)

CustomInfo = provider(
    fields = {
        "compiler_path": "The path to the compiler binary",
    },
)

def _custom_toolchain_impl(ctx):
    return [platform_common.ToolchainInfo(
        custom_info = CustomInfo(
            compiler_path = ctx.attr.compiler_path,
        ),
    )]

custom_toolchain = rule(
    implementation = _custom_toolchain_impl,
    attrs = {
        "compiler_path": attr.string(),
    },
)

def _compiler_flag_impl(ctx):
    toolchain = ctx.toolchains["//target_skipping/custom_tools:toolchain_type"].custom_info
    return [config_common.FeatureFlagInfo(value = toolchain.compiler_path)]

compiler_flag = rule(
    implementation = _compiler_flag_impl,
    toolchains = ["//target_skipping/custom_tools:toolchain_type"],
)
EOF
}

# Validates that we get a good error message when passing a config_setting into
# the target_compatible_with attribute. This is a regression test for
# https://github.com/bazelbuild/bazel/issues/13250.
function test_config_setting_in_target_compatible_with() {
  cat >> target_skipping/BUILD <<'EOF'
config_setting(
    name = "foo3_config_setting",
    constraint_values = [":foo3"],
)

sh_binary(
    name = "problematic_foo3_target",
    srcs = ["pass.sh"],
    target_compatible_with = [
        ":foo3_config_setting",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    ... &> "${TEST_log}" && fail "Bazel succeeded unexpectedly."

  expect_log "'//target_skipping:foo3_config_setting' does not have mandatory providers: 'ConstraintValueInfo'"
}

# Validates that the console log provides useful information to the user for
# builds.
function test_console_log_for_builds() {
  cd target_skipping || fail "couldn't cd into workspace"

  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    ... &> "${TEST_log}" || fail "Bazel failed the test."

  cp "${TEST_log}" "${TEST_UNDECLARED_OUTPUTS_DIR}"/

  expect_log 'Target //target_skipping:some_foo3_target up-to-date'
  expect_log 'Target //target_skipping:fail_on_foo2 was skipped'
  expect_log 'Target //target_skipping:pass_on_foo1 was skipped'
  expect_log 'Target //target_skipping:pass_on_foo1_bar2 was skipped'
  expect_not_log 'Target //target_skipping:fail_on_foo2 failed to build'
  expect_not_log 'Target //target_skipping:pass_on_foo1 failed to build'
  expect_not_log 'Target //target_skipping:pass_on_foo1_bar2 failed to build'
  expect_not_log 'Target //target_skipping:fail_on_foo2 up-to-date'
  expect_not_log 'Target //target_skipping:pass_on_foo1 up-to-date'
  expect_not_log 'Target //target_skipping:pass_on_foo1_bar2 up-to-date'
}

# Validates that the console log provides useful information to the user for
# tests.
function test_console_log_for_tests() {
  cd target_skipping || fail "couldn't cd into workspace"

  # Get repeatable results from this test.
  bazel clean --expunge

  bazel test \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    ... &> "${TEST_log}" || fail "Bazel failed unexpectedly."
  expect_log 'Executed 1 out of 3 tests: 1 test passes and 2 were skipped'
  expect_log '^//target_skipping:pass_on_foo1  *  PASSED in'
  expect_log '^//target_skipping:fail_on_foo2  *  SKIPPED$'
  expect_log '^//target_skipping:pass_on_foo1_bar2  *  SKIPPED$'

  bazel test \
    --host_platform=@//target_skipping:foo2_bar1_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    ... &> "${TEST_log}" && fail "Bazel passed unexpectedly."
  expect_log 'Executed 1 out of 3 tests: 1 fails .* and 2 were skipped.'
  expect_log '^//target_skipping:pass_on_foo1  *  SKIPPED$'
  expect_log '^//target_skipping:fail_on_foo2  *  FAILED in'
  expect_log '^//target_skipping:pass_on_foo1_bar2  *  SKIPPED$'

  # Use :all for this one to validate similar behaviour.
  bazel test \
    --host_platform=@//target_skipping:foo1_bar2_platform \
    --platforms=@//target_skipping:foo1_bar2_platform \
    :all &> "${TEST_log}" || fail "Bazel failed unexpectedly."
  expect_log 'Executed 2 out of 3 tests: 2 tests pass and 1 was skipped'
  expect_log '^//target_skipping:pass_on_foo1  *  PASSED in'
  expect_log '^//target_skipping:fail_on_foo2  *  SKIPPED$'
  expect_log '^//target_skipping:pass_on_foo1_bar2  *  PASSED in'
}

# Validates that filegroups and other rules that don't inherit from
# `NativeActionCreatingRule` can be marked with target_compatible_with. This is
# a regression test for https://github.com/bazelbuild/bazel/issues/12745.
function test_skipping_for_rules_that_dont_create_actions() {
  # Create a fake shared library for cc_import.
  echo > target_skipping/some_precompiled_library.so

  cat >> target_skipping/BUILD <<'EOF'
cc_import(
    name = "some_precompiled_library",
    shared_library = "some_precompiled_library.so",
    target_compatible_with = [
        ":foo3",
    ],
)

cc_binary(
    name = "some_binary",
    deps = [
        ":some_precompiled_library",
    ],
)

filegroup(
    name = "filegroup",
    srcs = [
        "some_precompiled_library.so",
    ],
    target_compatible_with = [
        ":foo3",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    :all &> "${TEST_log}" || fail "Bazel failed unexpectedly."
  expect_log 'Target //target_skipping:some_precompiled_library was skipped'
  expect_log 'Target //target_skipping:some_binary was skipped'
  expect_log 'Target //target_skipping:filegroup was skipped'

  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    :filegroup &> "${TEST_log}" && fail "Bazel passed unexpectedly."
  expect_log 'Target //target_skipping:filegroup is incompatible and cannot be built'
}

# Validates that incompatible target skipping errors behave nicely with
# --keep_going. In other words, even if there's an error in the target skipping
# (e.g. because the user explicitly requested an incompatible target) we still
# want Bazel to finish the rest of the build when --keep_going is specified.
function test_failure_on_incompatible_top_level_target() {
  cd target_skipping || fail "couldn't cd into workspace"

  # Validate a variety of ways to refer to the same target.
  local -r -a incompatible_targets=(
      :pass_on_foo1_bar2
      //target_skipping:pass_on_foo1_bar2
      @//target_skipping:pass_on_foo1_bar2
  )

  for incompatible_target in "${incompatible_targets[@]}"; do
    echo "Testing ${incompatible_target}"

    bazel test \
      --show_result=10 \
      --host_platform=@//target_skipping:foo1_bar1_platform \
      --platforms=@//target_skipping:foo1_bar1_platform \
      --build_event_text_file="${TEST_log}".build.json \
      "${incompatible_target}" &> "${TEST_log}" \
      && fail "Bazel passed unexpectedly."

    expect_log 'ERROR:.* Target //target_skipping:pass_on_foo1_bar2 is incompatible and cannot be built'
    expect_log '^ERROR: Build did NOT complete successfully'

    # Now look at the build event log.
    mv "${TEST_log}".build.json "${TEST_log}"

    expect_log '^    name: "PARSING_FAILURE"$'
    expect_log 'Target //target_skipping:pass_on_foo1_bar2 is incompatible and cannot be built.'
  done

  # Run an additional (passing) test and make sure we still fail the build.
  # This is intended to validate that --keep_going works as expected.
  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    --keep_going \
    //target_skipping:pass_on_foo1_bar2 \
    //target_skipping:pass_on_foo1 &> "${TEST_log}" && fail "Bazel passed unexpectedly."

  expect_log '^//target_skipping:pass_on_foo1  *  PASSED in'
  expect_log '^ERROR: command succeeded, but not all targets were analyzed'
  expect_log '^ERROR: Build did NOT complete successfully'
}

# Validates that incompatible target skipping works with top level targets when
# --skip_incompatible_explicit_targets is enabled.
function test_success_on_incompatible_top_level_target_with_skipping() {
  cd target_skipping || fail "couldn't cd into workspace"

  # Validate a variety of ways to refer to the same target.
  local -r -a incompatible_targets=(
      :pass_on_foo1_bar2
      //target_skipping:pass_on_foo1_bar2
      @//target_skipping:pass_on_foo1_bar2
  )

  for incompatible_target in "${incompatible_targets[@]}"; do
    echo "Testing ${incompatible_target}"

    bazel test \
      --show_result=10 \
      --host_platform=@//target_skipping:foo1_bar1_platform \
      --platforms=@//target_skipping:foo1_bar1_platform \
      --build_event_text_file="${TEST_log}".build.json \
      --skip_incompatible_explicit_targets \
      "${incompatible_target}" &> "${TEST_log}" \
      || fail "Bazel failed unexpectedly."

    expect_log '^//target_skipping:pass_on_foo1_bar2  *  SKIPPED$'
  done
}

# Crudely validates that the build event protocol contains useful information
# when targets are skipped due to incompatibilities.
function test_build_event_protocol() {
  cd target_skipping || fail "couldn't cd into workspace"

  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --build_event_text_file=$TEST_log \
    --platforms=@//target_skipping:foo1_bar1_platform \
    ... || fail "Bazel failed unexpectedly."
  expect_not_log 'Target //target_skipping:pass_on_foo1 build was skipped\.'
  expect_log_n 'reason: SKIPPED\>' 3
  expect_log 'Target //target_skipping:fail_on_foo2 build was skipped\.'
  expect_log 'Target //target_skipping:pass_on_foo1_bar2 build was skipped\.'
}

# Validates that incompatibilities are transitive. I.e. targets that depend on
# incompatible targets are themselves deemed incompatible and should therefore
# not be built.
function test_non_top_level_skipping() {
  touch target_skipping/foo_test.sh
  chmod +x target_skipping/foo_test.sh

  cat >> target_skipping/BUILD <<'EOF'
genrule(
    name = "genrule_foo1",
    target_compatible_with = [":foo1"],
    outs = ["foo1.sh"],
    cmd = "echo 'Should not be executed' >&2; exit 1",
)

sh_binary(
    name = "sh_foo2",
    srcs = ["foo1.sh"],
    target_compatible_with = [":foo2"],
)

# Make sure that using an incompatible target in Make variable substitution
# doesn't produce an unexpected error.
sh_test(
    name = "foo_test",
    srcs = ["foo_test.sh"],
    data = [":some_foo3_target"],
    args = ["$(location :some_foo3_target)"],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo2_bar1_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    //target_skipping:sh_foo2 &> "${TEST_log}" && fail "Bazel passed unexpectedly."
  expect_log 'ERROR:.* Target //target_skipping:sh_foo2 is incompatible and cannot be built, but was explicitly requested'
  expect_log 'ERROR: Build did NOT complete successfully'

  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo2_bar1_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    //target_skipping:foo_test &> "${TEST_log}" && fail "Bazel passed unexpectedly."
  expect_log 'ERROR:.* Target //target_skipping:foo_test is incompatible and cannot be built, but was explicitly requested'
  expect_log 'ERROR: Build did NOT complete successfully'
}

# Validate that targets are skipped when the implementation is in Starlark
# instead of in Java.
function test_starlark_skipping() {
  cat > target_skipping/rules.bzl <<'EOF'
def _echo_rule_impl(ctx):
    ctx.actions.write(ctx.outputs.out, ctx.attr.text)
    return []

_echo_rule = rule(
    implementation = _echo_rule_impl,
    attrs = {
        "text": attr.string(),
        "out": attr.output(),
    },
)

def echo_rule(name, **kwargs):
    _echo_rule(
        name = name,
        out = name,
        **kwargs
    )
EOF

  cat >> target_skipping/BUILD <<'EOF'
load("//target_skipping:rules.bzl", "echo_rule")

echo_rule(
    name = "hello_world",
    text = "Hello World",
    target_compatible_with = [
        "//target_skipping:foo1",
    ],
)

sh_binary(
    name = "hello_world_bin",
    srcs = ["pass.sh"],
    data = [":hello_world"],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping:hello_world_bin &> "${TEST_log}" && fail "Bazel passed unexpectedly."
  expect_log 'ERROR:.* Target //target_skipping:hello_world_bin is incompatible and cannot be built, but was explicitly requested'
  expect_log 'ERROR: Build did NOT complete successfully'
}

# Validates that rules with custom providers are skipped when incompatible.
# This is valuable because we use providers to convey incompatibility.
function test_dependencies_with_providers() {
  cat > target_skipping/rules.bzl <<'EOF'
DummyProvider = provider()

def _dummy_rule_impl(ctx):
    return [DummyProvider()]

dummy_rule = rule(
    implementation = _dummy_rule_impl,
    attrs = {
        "deps": attr.label_list(providers=[DummyProvider]),
    },
)
EOF

  cat >> target_skipping/BUILD <<'EOF'
load("//target_skipping:rules.bzl", "dummy_rule")

dummy_rule(
    name = "dummy1",
    target_compatible_with = [
        "//target_skipping:foo1",
    ],
)

dummy_rule(
    name = "dummy2",
    deps = [
        ":dummy1",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  pwd >&2
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping/... &> "${TEST_log}" || fail "Bazel failed unexpectedly."
  expect_log '^Target //target_skipping:dummy2 was skipped'
}

function test_dependencies_with_extensions() {
  cat > target_skipping/rules.bzl <<'EOF'
def _dummy_rule_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.name + ".cc")
    ctx.actions.write(out, "Dummy content")
    return DefaultInfo(files = depset([out]))

dummy_rule = rule(
    implementation = _dummy_rule_impl,
)
EOF

  cat >> target_skipping/BUILD <<'EOF'
load("//target_skipping:rules.bzl", "dummy_rule")

# Generates a dummy.cc file.
dummy_rule(
    name = "dummy_file",
    target_compatible_with = [":foo1"],
)

cc_library(
    name = "dummy_cc_lib",
    srcs = [
        "dummy_file",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  pwd >&2
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping/... &> "${TEST_log}" || fail "Bazel failed unexpectedly."
  expect_log '^Target //target_skipping:dummy_cc_lib was skipped'
}

# Validates the same thing as test_non_top_level_skipping, but with a cc_test
# and adding one more level of dependencies.
function test_cc_test() {
  cat > target_skipping/generator_tool.cc <<'EOF'
#include <cstdio>
int main() {
  printf("int main() { return 1; }\\n");
  return 0;
}
EOF

  cat >> target_skipping/BUILD <<'EOF'
cc_binary(
    name = "generator_tool",
    srcs = ["generator_tool.cc"],
    target_compatible_with = [":foo1"],
)

genrule(
    name = "generate_with_tool",
    outs = ["generated_test.cc"],
    cmd = "$(location :generator_tool) > $(OUTS)",
    tools = [":generator_tool"],
)

cc_test(
    name = "generated_test",
    srcs = ["generated_test.cc"],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  # Validate the generated file that makes up the test.
  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo2_bar1_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    --extra_execution_platforms= \
    //target_skipping:generated_test.cc &> "${TEST_log}" && fail "Bazel passed unexpectedly."
  expect_log "ERROR:.* Target //target_skipping:generated_test.cc is incompatible and cannot be built, but was explicitly requested"

  # Validate that we get the dependency chain printed out.
  expect_log '^Dependency chain:$'
  expect_log '^    //target_skipping:generate_with_tool (.*)$'
  expect_log "^    //target_skipping:generator_tool (.*)   <-- target platform (//target_skipping:foo2_bar1_platform) didn't satisfy constraint //target_skipping:foo1"
  expect_log 'ERROR: Build did NOT complete successfully'

  # Validate the test.
  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo2_bar1_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    --extra_execution_platforms= \
    //target_skipping:generated_test &> "${TEST_log}" && fail "Bazel passed unexpectedly."
  expect_log 'ERROR:.* Target //target_skipping:generated_test is incompatible and cannot be built, but was explicitly requested'

  # Validate that we get the dependency chain printed out.
  expect_log '^Dependency chain:$'
  expect_log '^    //target_skipping:generated_test (.*)$'
  expect_log '^    //target_skipping:generate_with_tool (.*)$'
  expect_log "^    //target_skipping:generator_tool (.*)   <-- target platform (//target_skipping:foo2_bar1_platform) didn't satisfy constraint //target_skipping:foo1"
  expect_log 'ERROR: Build did NOT complete successfully'
}

# Validates the same thing as test_cc_test, but with multiple violated
# constraints.
function test_cc_test_multiple_constraints() {
  cat > target_skipping/generator_tool.cc <<'EOF'
#include <cstdio>
int main() {
  printf("int main() { return 1; }\\n");
  return 0;
}
EOF

  cat >> target_skipping/BUILD <<'EOF'
cc_binary(
    name = "generator_tool",
    srcs = ["generator_tool.cc"],
    target_compatible_with = [":foo1", ":bar2"],
)

genrule(
    name = "generate_with_tool",
    outs = ["generated_test.cc"],
    cmd = "$(location :generator_tool) > $(OUTS)",
    tools = [":generator_tool"],
)

cc_test(
    name = "generated_test",
    srcs = ["generated_test.cc"],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo2_bar1_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    --extra_execution_platforms= \
    //target_skipping:generated_test &> "${TEST_log}" && fail "Bazel passed unexpectedly."
  expect_log 'ERROR:.* Target //target_skipping:generated_test is incompatible and cannot be built, but was explicitly requested'

  # Validate that we get the dependency chain and constraints printed out.
  expect_log '^Dependency chain:$'
  expect_log '^    //target_skipping:generated_test (.*)$'
  expect_log '^    //target_skipping:generate_with_tool (.*)$'
  expect_log "^    //target_skipping:generator_tool (.*)   <-- target platform (//target_skipping:foo2_bar1_platform) didn't satisfy constraints \[//target_skipping:bar2, //target_skipping:foo1\]"
  expect_log 'ERROR: Build did NOT complete successfully'
}

# Validates that we can express targets being compatible with A _or_ B.
function test_or_logic() {
  cat >> target_skipping/BUILD <<'EOF'
sh_test(
    name = "pass_on_foo1_or_foo2_but_not_on_foo3",
    srcs = [":pass.sh"],
    target_compatible_with = select({
        ":foo1": [],
        ":foo2": [],
        "//conditions:default": ["@platforms//:incompatible"],
    }),
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    --nocache_test_results \
    //target_skipping:pass_on_foo1_or_foo2_but_not_on_foo3 &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_log '^//target_skipping:pass_on_foo1_or_foo2_but_not_on_foo3  *  PASSED in'

  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo2_bar1_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    --nocache_test_results \
    //target_skipping:pass_on_foo1_or_foo2_but_not_on_foo3 &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_log '^//target_skipping:pass_on_foo1_or_foo2_but_not_on_foo3  *  PASSED in'

  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping:pass_on_foo1_or_foo2_but_not_on_foo3 &> "${TEST_log}" \
    && fail "Bazel passed unexpectedly."

  expect_log 'ERROR:.* Target //target_skipping:pass_on_foo1_or_foo2_but_not_on_foo3 is incompatible and cannot be built, but was explicitly requested'
  expect_log 'ERROR: Build did NOT complete successfully'
}

# Regression test for b/277371822.
function test_missing_default() {
  cat >> target_skipping/BUILD <<'EOF'
sh_test(
    name = "pass_on_foo1_or_foo2_but_not_on_foo3",
    srcs = [":pass.sh"],
    target_compatible_with = select({
        ":foo1": [],
        # No default branch.
    }),
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping:pass_on_foo1_or_foo2_but_not_on_foo3 &> "${TEST_log}" \
    && fail "Bazel passed unexpectedly."

  expect_log 'ERROR:.*configurable attribute "target_compatible_with" in //target_skipping:pass_on_foo1_or_foo2_but_not_on_foo3'
  expect_log 'ERROR: Build did NOT complete successfully'
  expect_not_log 'FATAL: bazel crashed'
}

# Validates that we can express targets being compatible with everything _but_
# A and B.
function test_inverse_logic() {
  add_bazel_skylib "MODULE.bazel"

  # Not using 'EOF' because injecting skylib_package
  cat >> target_skipping/BUILD <<EOF
load("${skylib_package}lib:selects.bzl", "selects")

sh_test(
    name = "pass_on_everything_but_foo1_and_foo2",
    srcs = [":pass.sh"],
    target_compatible_with = selects.with_or({
        (":foo1", ":foo2"): ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  # Try with :foo1. This should fail.
  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    //target_skipping:pass_on_everything_but_foo1_and_foo2  &> "${TEST_log}" \
    && fail "Bazel passed unexpectedly."
  expect_log 'ERROR:.* Target //target_skipping:pass_on_everything_but_foo1_and_foo2 is incompatible and cannot be built, but was explicitly requested'
  expect_log 'ERROR: Build did NOT complete successfully'

  # Try with :foo2. This should fail.
  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo2_bar1_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    //target_skipping:pass_on_everything_but_foo1_and_foo2 &> "${TEST_log}" \
    && fail "Bazel passed unexpectedly."
  expect_log 'ERROR:.* Target //target_skipping:pass_on_everything_but_foo1_and_foo2 is incompatible and cannot be built, but was explicitly requested'
  expect_log 'ERROR: Build did NOT complete successfully'

  # Now with :foo3. This should pass.
  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    --nocache_test_results \
    //target_skipping:pass_on_everything_but_foo1_and_foo2 &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_log '^//target_skipping:pass_on_everything_but_foo1_and_foo2  *  PASSED in'
}

# Validates that we can reference the same incompatible constraint in several,
# composed select() statements. This is useful for expressing compatibility for
# orthogonal constraints. It's also useful when a macro author wants to express
# incompatibility while also honouring the user-defined incompatibility.
function test_composition() {
  # The first select() statement might come from a macro. The second might come
  # from the user who's calling that macro.
  cat >> target_skipping/BUILD <<EOF
sh_test(
    name = "pass_on_foo3_and_bar2",
    srcs = [":pass.sh"],
    target_compatible_with = select({
        ":foo3": [],
        "//conditions:default": ["@platforms//:incompatible"],
    }) + select({
        ":bar2": [],
        "//conditions:default": ["@platforms//:incompatible"],
    }),
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_bar2_platform \
    --platforms=@//target_skipping:foo3_bar2_platform \
    --nocache_test_results \
    //target_skipping:pass_on_foo3_and_bar2 &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_log '^//target_skipping:pass_on_foo3_and_bar2  *  PASSED in'

  # Validate that we get an error about the target being incompatible. Make
  # sure that the ":incompatible" constraint is only listed once even though
  # it appears twice in the configured "target_compatible_with" attribute.
  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    //target_skipping:pass_on_foo3_and_bar2 &> "${TEST_log}" \
    && fail "Bazel passed unexpectedly."

  expect_log 'ERROR:.* Target //target_skipping:pass_on_foo3_and_bar2 is incompatible and cannot be built, but was explicitly requested'
  expect_log "^    //target_skipping:pass_on_foo3_and_bar2 (.*)   <-- target platform (//target_skipping:foo1_bar1_platform) didn't satisfy constraint @\?@platforms//:incompatible$"
  expect_log 'ERROR: Build did NOT complete successfully'
}

function test_incompatible_with_aliased_constraint() {
  cat >> target_skipping/BUILD <<'EOF'
alias(
    name = "also_foo3",
    actual = ":foo3",
)

alias(
    name = "again_foo3",
    actual = ":foo3",
)

cc_library(
    name = "some_library",
    target_compatible_with = select({
        ":also_foo3": [":again_foo3"],
        "//conditions:default": ["@platforms//:incompatible"],
    }),
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping/... &> "${TEST_log}" \
    || fail "Bazel build failed unexpectedly."
  expect_log 'Target //target_skipping:some_library up-to-date'
}

function test_incompatible_with_aliased_target() {
  cat >> target_skipping/BUILD <<'EOF'
alias(
    name = "also_some_foo3_target",
    actual = ":some_foo3_target",
)
EOF

  # Try with :foo1. This should fail.
  bazel test \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    //target_skipping:also_some_foo3_target  &> "${TEST_log}" \
    && fail "Bazel passed unexpectedly."
  expect_log 'ERROR:.* Target //target_skipping:also_some_foo3_target is incompatible and cannot be built, but was explicitly requested'
  expect_log 'ERROR: Build did NOT complete successfully'
}

# Validate what happens when setting `target_compatible_with` directly on an
# alias(). This is a regression test for
# https://github.com/bazelbuild/bazel/issues/17663.
function test_alias_incompatibility() {
  cat >> target_skipping/BUILD <<'EOF'
filegroup(
    name = "test_cc_filegroup",
    srcs = ["test.cc"],
)

alias(
    name = "test_cc_filegroup_alias",
    actual = ":test_cc_filegroup",
    target_compatible_with = [":foo3"],
)

cc_library(
    name = "test_cc",
    srcs = [":test_cc_filegroup_alias"],
)
EOF

  echo > target_skipping/test.cc

  cd target_skipping || fail "couldn't cd into workspace"
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    //target_skipping:test_cc &> "${TEST_log}" \
    && fail "Bazel passed unexpectedly"
  expect_log_once 'ERROR:.* Target //target_skipping:test_cc is incompatible and cannot be built, but was explicitly requested.'
}

# Validate that an incompatible target with a toolchain not available for the
# current platform will not cause an analysis error. This is a regression test
# for https://github.com/bazelbuild/bazel/issues/12897.
function test_incompatible_with_missing_toolchain() {
  set_up_custom_toolchain
  cat >> MODULE.bazel <<'EOF'
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = 'build_bazel_apple_support', path = 'build_bazel_apple_support')
EOF
  mkdir -p build_bazel_apple_support/platforms
  touch build_bazel_apple_support/REPO.bazel
  cat > build_bazel_apple_support/platforms/BUILD <<'EOF'
package(default_visibility=["//visibility:public"])
platform(
  name = "darwin_x86_64",
  constraint_values = ["@platforms//os:macos", "@platforms//cpu:x86_64"],
)
EOF

  cat >> target_skipping/BUILD <<'EOF'
load(
    "//target_skipping/custom_tools:toolchain.bzl",
    "compiler_flag",
    "custom_binary",
)

objc_library(
    name = "objc",
    target_compatible_with = select({
        ":setting1": ["//target_skipping:foo1"],
        "//conditions:default": ["//target_skipping:foo2"],
    }),
)

custom_binary(
    name = "custom1",
    src = "custom.txt",
    target_compatible_with = ["//target_skipping:foo1"],
)

compiler_flag(name = "compiler_flag")

config_setting(
    name = "using_custom_toolchain",
    flag_values = {
        ":compiler_flag": "customc",
    },
)

custom_binary(
    name = "custom2",
    src = "custom.txt",
    target_compatible_with = select({
        ":using_custom_toolchain": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
)
EOF

  cat > target_skipping/custom.txt <<EOF
This is a custom dummy file.
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel build --define=foo=1 \
    --show_result=10 \
    --extra_toolchains=//target_skipping/custom_tools:custom_foo3_toolchain \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping/... &> "${TEST_log}" \
    || fail "Bazel build failed unexpectedly."
  expect_log 'Target //target_skipping:objc was skipped'
  expect_log 'Target //target_skipping:custom1 was skipped'
  expect_log 'Target //target_skipping:custom2 was skipped'
}

# Validates that if a target is "directly incompatible" then its dependencies
# are not evaluated. I.e. there should be no need to guard the dependencies
# with a select() statement.
function test_invalid_deps_are_ignored_when_incompatible() {
  cat >> target_skipping/BUILD <<'EOF'
cc_binary(
    name = "incompatible_tool",
    deps = [
        "//nonexistent_dep",
    ],
    target_compatible_with = [
        ":foo1",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping/... &> "${TEST_log}" \
    || fail "Bazel build failed unexpectedly."
  expect_log 'Target //target_skipping:incompatible_tool was skipped'
}

# Validates that a tool compatible with the host platform, but incompatible
# with the target platform can still be used as a host tool.
function test_host_tool() {
  # Create an arbitrary host tool.
  cat > target_skipping/host_tool.cc <<'EOF'
#include <cstdio>
int main() {
  printf("Hello World\\n");
  return 0;
}
EOF

  cat >> target_skipping/BUILD <<'EOF'
cc_binary(
    name = "host_tool",
    srcs = ["host_tool.cc"],
    target_compatible_with = [
        ":foo1",
    ],
)

genrule(
    name = "use_host_tool",
    outs = ["host_tool_message.txt"],
    cmd = "$(location :host_tool) >> $(OUTS)",
    tools = [":host_tool"],
    target_compatible_with = [
        ":foo2",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  # Run with :foo1 in the host platform, but with :foo2 in the target platform.
  # Building the host tool should fail because it's incompatible with the
  # target platform.
  bazel build --show_result=10  \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    //target_skipping:host_tool &> "${TEST_log}" && fail "Bazel passed unexpectedly."
  expect_log 'ERROR:.* Target //target_skipping:host_tool is incompatible and cannot be built, but was explicitly requested'
  expect_log 'ERROR: Build did NOT complete successfully'

  # Run with :foo1 in the host platform, but with :foo2 in the target platform.
  # This should work fine because we're not asking for any constraints to be
  # violated.
  bazel build --host_platform=@//target_skipping:foo1_bar2_platform \
    --platforms=@//target_skipping:foo2_bar1_platform \
    --extra_execution_platforms= \
    //target_skipping:host_tool_message.txt &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_log " ${PRODUCT_NAME}-bin/target_skipping/host_tool_message.txt$"
  expect_log ' Build completed successfully, '

  # Make sure that the contents of the file are what we expect.
  cp ../${PRODUCT_NAME}-bin/target_skipping/host_tool_message.txt "${TEST_log}"
  expect_log 'Hello World'
}

# Validates that we successfully skip analysistest rule targets when they
# depend on incompatible targets.
function test_analysistest() {
  add_bazel_skylib "MODULE.bazel"

  # Not using 'EOF' because injecting skylib_package
  cat > target_skipping/analysistest.bzl <<EOF
load("${skylib_package}lib:unittest.bzl", "analysistest")

def _analysistest_test_impl(ctx):
    env = analysistest.begin(ctx)
    print("Running the test")
    return analysistest.end(env)

analysistest_test = analysistest.make(_analysistest_test_impl)
EOF

  cat >> target_skipping/BUILD <<'EOF'
load(":analysistest.bzl", "analysistest_test")

analysistest_test(
    name = "foo3_analysistest_test",
    target_under_test = ":some_foo3_target",
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel test --show_result=10  \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping:foo3_analysistest_test &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_log '^//target_skipping:foo3_analysistest_test  *  PASSED in'

  bazel test --show_result=10  \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    //target_skipping:all &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_log '^Target //target_skipping:foo3_analysistest_test was skipped'
}

function write_query_test_targets() {
  cat >> target_skipping/BUILD <<'EOF'
genrule(
    name = "genrule_foo1",
    target_compatible_with = [":foo1"],
    outs = ["foo1.sh"],
    cmd = "echo 'exit 0' > $(OUTS)",
)

sh_binary(
    name = "sh_foo1",
    srcs = ["foo1.sh"],
)
EOF
}

function test_query() {
  write_query_test_targets
  cd target_skipping || fail "couldn't cd into workspace"

  bazel query \
    'deps(//target_skipping:sh_foo1)' &> "${TEST_log}" \
    || fail "Bazel query failed unexpectedly."
  expect_log '^//target_skipping:sh_foo1'
  expect_log '^//target_skipping:genrule_foo1'
}

# Regression test for http://b/189071321: --notool_deps should exclude constraints.
function test_query_no_tools() {
  write_query_test_targets
  cd target_skipping || fail "couldn't cd into workspace"

  bazel query \
    --notool_deps \
    'deps(//target_skipping:sh_foo1)' &> "${TEST_log}" \
    || fail "Bazel query failed unexpectedly."

  if "$is_windows"; then
    sed -i 's/\r//g' "${TEST_log}"
  fi

  expect_log '^//target_skipping:sh_foo1$'
  expect_log '^//target_skipping:genrule_foo1$'
  expect_not_log '^//target_skipping:foo1$'
}

# Run a cquery on a target that is compatible. This should pass.
function test_cquery_compatible_target() {
  write_query_test_targets
  cd target_skipping || fail "couldn't cd into workspace"

  bazel cquery \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    'deps(//target_skipping:some_foo3_target)' &> "${TEST_log}" \
    || fail "Bazel cquery failed unexpectedly."
}

# Run a cquery with a glob. This should only pick up compatible targets and
# should therefore pass.
function test_cquery_with_glob() {
  write_query_test_targets
  cd target_skipping || fail "couldn't cd into workspace"

  bazel cquery \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    'deps(//target_skipping/...)' &> "${TEST_log}" \
    || fail "Bazel cquery failed unexpectedly."
  expect_log '^//target_skipping:some_foo3_target '
  expect_log '^//target_skipping:sh_foo1 '
  expect_log '^//target_skipping:genrule_foo1 '
}

# Run a cquery on an incompatible target. This should also pass. Incompatibility
# is more important for builds, where users want meaningful output. For queries,
# understanding target and dependency relationships is more important than
# erroring on unbuildable targets.
function test_cquery_incompatible_target() {
  mkdir -p target_skipping
  cat >> target_skipping/BUILD <<'EOF'
sh_binary(
    name = "depender",
    srcs = ["depender.sh"],
    data = [":never_compatible"],
    target_compatible_with = [":foo3"],
)
sh_binary(
    name = "never_compatible",
    srcs = [":never_used.sh"],
    target_compatible_with = ["@platforms//:incompatible"],
)
EOF

  bazel cquery \
    --platforms=@//target_skipping:foo3_platform \
    'somepath(//target_skipping:depender, //target_skipping:never_compatible)' \
    &> "${TEST_log}" \
    || fail "Bazel cquery failed unexpectedly."
  expect_log 'INFO: Found 2 targets'
  expect_log '//target_skipping:depender (.*)'
  expect_log "//target_skipping:never_compatible (.*)"
}

# Runs a cquery and makes sure that we can properly distinguish between
# incompatible targets and compatible targets.
function test_cquery_with_starlark_formatting() {
  cat > target_skipping/compatibility.cquery <<'EOF'
def format(target):
    if "IncompatiblePlatformProvider" in providers(target):
        result = "incompatible"
    else:
        result = "compatible"

    return "%s is %s" % (target.label, result)
EOF

  cd target_skipping || fail "couldn't cd into workspace"

  bazel cquery \
    --host_platform=//target_skipping:foo1_bar1_platform \
    --platforms=//target_skipping:foo1_bar1_platform \
    :all \
    --output=starlark --starlark:file=target_skipping/compatibility.cquery \
    &> "${TEST_log}"

  expect_log '^@@\?//target_skipping:pass_on_foo1 is compatible$'
  expect_log '^@@\?//target_skipping:fail_on_foo2 is incompatible$'
  expect_log '^@@\?//target_skipping:some_foo3_target is incompatible$'

  bazel cquery \
    --host_platform=//target_skipping:foo3_platform \
    --platforms=//target_skipping:foo3_platform \
    :all \
    --output=starlark --starlark:file=target_skipping/compatibility.cquery \
    &> "${TEST_log}"

  expect_log '^@@\?//target_skipping:pass_on_foo1 is incompatible$'
  expect_log '^@@\?//target_skipping:fail_on_foo2 is incompatible$'
  expect_log '^@@\?//target_skipping:some_foo3_target is compatible$'
}

# Run an aquery on a target that is compatible. This should pass.
function test_aquery_compatible_target() {
  write_query_test_targets
  cd target_skipping || fail "couldn't cd into workspace"

  bazel aquery \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    '//target_skipping:some_foo3_target' &> "${TEST_log}" \
    || fail "Bazel aquery failed unexpectedly."
}

# Run an aquery with a glob. This should only pick up compatible targets and
# should therefore pass.
function test_aquery_with_glob() {
  write_query_test_targets
  cd target_skipping || fail "couldn't cd into workspace"

  bazel aquery \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    '//target_skipping/...' &> "${TEST_log}" \
    || fail "Bazel aquery failed unexpectedly."
}

# Run an aquery on an incompatible target. This should fail.
function test_aquery_incompatible_target() {
  write_query_test_targets
  cd target_skipping || fail "couldn't cd into workspace"

  bazel aquery \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    '//target_skipping:sh_foo1' &> "${TEST_log}" \
    && fail "Bazel aquery passed unexpectedly."
  expect_log_once 'Target //target_skipping:sh_foo1 is incompatible and cannot be built, but was explicitly requested'
  expect_log_once "target platform (//target_skipping:foo3_platform) didn't satisfy constraint //target_skipping:foo1"
}

# Use aspects to interact with incompatible targets and validate the behaviour.
function test_aspect_skipping() {
  cat >> target_skipping/BUILD <<'EOF'
load(":defs.bzl", "basic_rule", "rule_with_aspect")

# This target is compatible with all platforms and configurations. This target
# exists to validate the behaviour of aspects running against incompatible
# targets. The expectation is that the aspect should _not_ propagate to this
# compatible target from an incompatible target. I.e. an aspect should _not_
# evaluate this target if "basic_foo3_target" is incompatible.
basic_rule(
    name = "basic_universal_target",
)
# An alias to validate that incompatible target skipping works as expected with
# aliases and aspects.
alias(
    name = "aliased_basic_universal_target",
    actual = ":basic_universal_target",
)
basic_rule(
    name = "basic_foo3_target",
    deps = [
        ":aliased_basic_universal_target",
    ],
    target_compatible_with = [
        ":foo3",
    ],
)
# This target is only compatible when "basic_foo3_target" is compatible. This
# target exists to validate the behaviour of aspects running against
# incompatible targets. The expectation is that the aspect should _not_
# evaluate this target when "basic_foo3_target" is incompatible.
basic_rule(
    name = "other_basic_target",
    deps = [
        ":basic_foo3_target",
    ],
)
alias(
    name = "aliased_other_basic_target",
    actual = ":other_basic_target",
)
rule_with_aspect(
    name = "inspected_foo3_target",
    inspect = ":aliased_other_basic_target",
)
basic_rule(
    name = "previously_inspected_basic_target",
    deps = [
        ":inspected_foo3_target",
    ],
)
rule_with_aspect(
    name = "twice_inspected_foo3_target",
    inspect = ":previously_inspected_basic_target",
)
genrule(
    name = "generated_file",
    outs = ["generated_file.txt"],
    cmd = "echo '' > $(OUTS)",
    target_compatible_with = [
        ":foo1",
    ],
)
rule_with_aspect(
    name = "inspected_generated_file",
    inspect = ":generated_file",
)
EOF
  cat > target_skipping/defs.bzl <<'EOF'
BasicProvider = provider()
def _basic_rule_impl(ctx):
    return [DefaultInfo(), BasicProvider()]
basic_rule = rule(
    implementation = _basic_rule_impl,
    attrs = {
        "deps": attr.label_list(
            providers = [BasicProvider],
        ),
    },
)
def _inspecting_aspect_impl(target, ctx):
    print("Running aspect on " + str(target))
    return []
_inspecting_aspect = aspect(
    implementation = _inspecting_aspect_impl,
    attr_aspects = ["deps"],
)
def _rule_with_aspect_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(out, "")
    return [
        DefaultInfo(files=depset([out])),
        BasicProvider(),
    ]
rule_with_aspect = rule(
    implementation = _rule_with_aspect_impl,
    attrs = {
        "inspect": attr.label(
            aspects = [_inspecting_aspect],
        ),
    },
)
EOF
  cd target_skipping || fail "couldn't cd into workspace"
  local debug_message1="Running aspect on <target //target_skipping:basic_universal_target>"
  local debug_message2="Running aspect on <target //target_skipping:basic_foo3_target>"
  local debug_message3="Running aspect on <target //target_skipping:other_basic_target>"
  local debug_message4="Running aspect on <target //target_skipping:previously_inspected_basic_target>"
  local debug_message5="Running aspect on <target //target_skipping:generated_file>"
  # Validate that aspects run against compatible targets.
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo3_platform \
    --platforms=@//target_skipping:foo3_platform \
    //target_skipping:all &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_log "${debug_message1}"
  expect_log "${debug_message2}"
  expect_log "${debug_message3}"
  expect_log "${debug_message4}"
  expect_not_log "${debug_message5}"
  # Invert the compatibility and validate that aspects run on the other targets
  # now.
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    //target_skipping:all &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_not_log "${debug_message1}"
  expect_not_log "${debug_message2}"
  expect_not_log "${debug_message3}"
  expect_not_log "${debug_message4}"
  expect_log "${debug_message5}"
  # Validate that explicitly trying to build a target with an aspect against an
  # incompatible target produces the normal error message.
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:foo1_bar1_platform \
    --platforms=@//target_skipping:foo1_bar1_platform \
    //target_skipping:twice_inspected_foo3_target &> "${TEST_log}" \
    && fail "Bazel passed unexpectedly."
  expect_log_once 'ERROR:.* Target //target_skipping:twice_inspected_foo3_target is incompatible and cannot be built, but was explicitly requested.'
  expect_log_once '^Dependency chain:$'
  expect_log_once '^    //target_skipping:twice_inspected_foo3_target '
  expect_log_once '^    //target_skipping:previously_inspected_basic_target '
  expect_log_once '^    //target_skipping:inspected_foo3_target '
  expect_log_once '^    //target_skipping:aliased_other_basic_target '
  expect_log_once '^    //target_skipping:other_basic_target '
  expect_log_once "    //target_skipping:basic_foo3_target .*  <-- target platform (//target_skipping:foo1_bar1_platform) didn't satisfy constraint //target_skipping:foo3$"
  expect_log 'ERROR: Build did NOT complete successfully'
  expect_not_log "${debug_message1}"
  expect_not_log "${debug_message2}"
  expect_not_log "${debug_message3}"
  expect_not_log "${debug_message4}"
  expect_not_log "${debug_message5}"
}

function test_skipping_with_missing_toolchain() {
  mkdir -p missing_toolchain

  cat > missing_toolchain/BUILD <<'EOF'
load(":rule.bzl", "my_rule")

toolchain_type(name = "my_toolchain_type")

my_rule(
    name = "my_rule",
    target_compatible_with = ["@platforms//:incompatible"],
)
EOF

  cat > missing_toolchain/rule.bzl <<'EOF'
def _my_rule_impl(ctx):
    pass

my_rule = rule(
    _my_rule_impl,
    toolchains = ["//missing_toolchain:my_toolchain_type"],
)
EOF

  bazel build --show_result=10 //missing_toolchain:all &> "${TEST_log}" \
    || fail "Bazel failed unexpectedly."
  expect_log "Target //missing_toolchain:my_rule was skipped"
}

# Regression test for https://github.com/bazelbuild/bazel/issues/23003.
function test_config_setting_on_label_flag_works_when_actual_is_incompatible() {
  # Not using 'EOF' because injecting default_host_platform
  cat > target_skipping/BUILD <<EOF
constraint_setting(name = "foo_version")
constraint_value(
    name = "foo1",
    constraint_setting = ":foo_version",
)
constraint_value(
    name = "foo2",
    constraint_setting = ":foo_version",
)
# The label_flag defaults to a target that's incompatible with this build.
label_flag(
    name = "my_label_flag",
    build_setting_default = ":foo1_target",
)
sh_library(
    name = "foo1_target",
    srcs = ["incompatible.sh"],
    target_compatible_with = [":foo1"],
)
config_setting(
    name = "mylabel_flag_points_to_foo1",
    flag_values = {
        ":my_label_flag": ":foo1_target",
    },
)
genrule(
    name = "mytarget",
    srcs = [],
    outs = ["mytarget.txt"],
    cmd = "echo " + select({
        ":mylabel_flag_points_to_foo1": "label flag matches",
    }) + " > \$(OUTS)",
)
platform(
    name = "platform_foo2",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo2",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:platform_foo2 \
    --platforms=@//target_skipping:platform_foo2 \
    //target_skipping:mytarget &> "${TEST_log}" || fail "Bazel failed unexpectedly."
  expect_log " ${PRODUCT_NAME}-bin/target_skipping/mytarget.txt$"
  expect_log 'Build completed successfully'
}

# Regression test for https://github.com/bazelbuild/bazel/issues/23003.
function test_dep_on_label_flag_is_incompatible_when_reference_is_incompatible() {
  # Not using 'EOF' because injecting default_host_platform
  cat > target_skipping/BUILD <<EOF
constraint_setting(name = "foo_version")
constraint_value(
    name = "foo1",
    constraint_setting = ":foo_version",
)
constraint_value(
    name = "foo2",
    constraint_setting = ":foo_version",
)
label_flag(
    name = "my_label_flag",
    build_setting_default = ":foo1_target",
)
sh_library(
    name = "foo1_target",
    srcs = ["incompatible.sh"],
    target_compatible_with = [":foo1"],
)
sh_binary(
    name = "mytarget",
    srcs = ["mytarget.sh"],
    deps = [":my_label_flag"]
)
platform(
    name = "platform_foo2",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo2",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"
  bazel build --nobuild \
    --show_result=10 \
    --host_platform=@//target_skipping:platform_foo2 \
    --platforms=@//target_skipping:platform_foo2 \
    //target_skipping:mytarget &> "${TEST_log}" && fail "Bazel succeeded unexpectedly."
    expect_log 'Target //target_skipping:mytarget is incompatible and cannot be built'
    expect_log '^ERROR: Build did NOT complete successfully'
}

# Regression test for https://github.com/bazelbuild/bazel/issues/23003.
function test_building_label_flag_with_incompatible_ref_fails_as_incompatible() {
  # Not using 'EOF' because injecting default_host_platform
  cat > target_skipping/BUILD <<EOF
constraint_setting(name = "foo_version")
constraint_value(
    name = "foo1",
    constraint_setting = ":foo_version",
)
constraint_value(
    name = "foo2",
    constraint_setting = ":foo_version",
)
label_flag(
    name = "my_label_flag",
    build_setting_default = ":foo1_target",
)
sh_library(
    name = "foo1_target",
    srcs = ["incompatible.sh"],
    target_compatible_with = [":foo1"],
)
platform(
    name = "platform_foo2",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo2",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:platform_foo2 \
    --platforms=@//target_skipping:platform_foo2 \
    //target_skipping:my_label_flag &> "${TEST_log}" && fail "Bazel succeeded unexpectedly."
    expect_log 'Target //target_skipping:my_label_flag is incompatible and cannot be built'
    expect_log '^ERROR: Build did NOT complete successfully'
}

# Regression test for https://github.com/bazelbuild/bazel/issues/23003.
function test_building_label_flag_with_compatible_ref_succeeds() {
  touch target_skipping/incompatible.sh
  # Not using 'EOF' because injecting default_host_platform
  cat > target_skipping/BUILD <<EOF
constraint_setting(name = "foo_version")
constraint_value(
    name = "foo1",
    constraint_setting = ":foo_version",
)
constraint_value(
    name = "foo2",
    constraint_setting = ":foo_version",
)
label_flag(
    name = "my_label_flag",
    build_setting_default = ":foo1_target",
)
sh_library(
    name = "foo1_target",
    srcs = ["incompatible.sh"],
    target_compatible_with = [":foo1"],
)
platform(
    name = "platform_foo1",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo1",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:platform_foo1 \
    --platforms=@//target_skipping:platform_foo1 \
    //target_skipping:my_label_flag &> "${TEST_log}" || fail "Bazel failed unexpectedly."
  expect_log 'Build completed successfully, '
}

# Regression test for https://github.com/bazelbuild/bazel/issues/23003.
function test_building_label_flag_with_incompatible_ref_implicity_is_skipped() {
  touch target_skipping/incompatible.sh
  touch target_skipping/mytarget.sh
  chmod u+x target_skipping/mytarget.sh

  # Not using 'EOF' because injecting default_host_platform
  cat > target_skipping/BUILD <<EOF
constraint_setting(name = "foo_version")
constraint_value(
    name = "foo1",
    constraint_setting = ":foo_version",
)
constraint_value(
    name = "foo2",
    constraint_setting = ":foo_version",
)
label_flag(
    name = "my_label_flag",
    build_setting_default = ":foo1_target",
)
sh_library(
    name = "foo1_target",
    srcs = ["incompatible.sh"],
    target_compatible_with = [":foo1"],
)
sh_binary(
    name = "mytarget",
    srcs = ["mytarget.sh"],
)
platform(
    name = "platform_foo2",
    parents = ["${default_host_platform}"],
    constraint_values = [
        ":foo2",
    ],
)
EOF

  cd target_skipping || fail "couldn't cd into workspace"
  bazel build \
    --show_result=10 \
    --host_platform=@//target_skipping:platform_foo2 \
    --platforms=@//target_skipping:platform_foo2 \
    //target_skipping:all &> "${TEST_log}" || fail "Bazel failed unexpectedly."
  expect_log 'Target //target_skipping:my_label_flag was skipped'
  expect_log 'Target //target_skipping:foo1_target was skipped'
  expect_log 'Target //target_skipping:mytarget up-to-date'
  expect_log 'Build completed successfully, '

}

run_suite "target_compatible_with tests"
