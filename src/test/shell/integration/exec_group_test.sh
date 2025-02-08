#!/bin/bash
#
# Copyright 2019 The Bazel Authors. All rights reserved.
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
# Test related to exec groups.
#

# --- begin runfiles.bash initialization ---
# Copy-pasted from Bazel's Bash runfiles library (tools/bash/runfiles/runfiles.bash).
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

# NOTE: All tests need to declare targets in a custom package, which is why they
# all use the pkg=${FUNCNAME[0]} variable.

function test_target_exec_properties_starlark() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/rules.bzl << EOF
def _impl(ctx):
  out_file = ctx.outputs.output
  ctx.actions.run_shell(inputs = [], outputs = [out_file], arguments=[out_file.path], progress_message = "Saying hello", command = "echo hello > \"\$1\"")

my_rule = rule(
  implementation = _impl,
  attrs = {
    "output": attr.output(),
  }
)
EOF
  cat > ${pkg}/BUILD << EOF
load("//${pkg}:rules.bzl", "my_rule")

constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
my_rule(
    name = "a",
    output = "out.txt",
    exec_properties = {"key3": "value3", "overridden": "child_value"},
    exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "key2": "value2",
        "overridden": "parent_value",
    },
    constraint_values = [":local"],
)
EOF

  bazel build --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt &> $TEST_log || fail "Build failed"
  grep "key2" out.txt || fail "Did not find the platform key"
  grep "key3" out.txt || fail "Did not find the target attribute key"
  grep "child_value" out.txt || fail "Did not find the overriding value"
}


function test_target_exec_properties_starlark_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  if "$is_windows"; then
    script_name="test_script.bat"
    script_content="@echo off\necho hello\n"
  else
    script_name="test_script.sh"
    script_content="#!/bin/bash\necho hello\n"
  fi
  cat > ${pkg}/rules.bzl <<EOF
def _impl(ctx):
  out_file = ctx.actions.declare_file("$script_name")
  ctx.actions.write(out_file, "$script_content", is_executable=True)
  return [DefaultInfo(executable = out_file)]

my_rule_test = rule(
  implementation = _impl,
  test = True,
)
EOF
  cat > ${pkg}/BUILD << EOF
load("//${pkg}:rules.bzl", "my_rule_test")

constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
my_rule_test(
    name = "a",
    exec_properties = {"key3": "value3", "overridden": "child_value"},
    exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "key2": "value2",
        "overridden": "parent_value",
    },
    constraint_values = [":local"],
)
EOF

  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt &> $TEST_log || fail "Build failed"
  grep "key2" out.txt || fail "Did not find the platform key"
  grep "key3" out.txt || fail "Did not find the target attribute key"
  grep "child_value" out.txt || fail "Did not find the overriding value"
}

function test_target_exec_properties_cc() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.cc <<EOF
#include <stdio.h>
int main() {
  printf("Hello\n");
}
EOF
  cat > ${pkg}/BUILD <<EOF
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
cc_binary(
  name = "a",
  srcs = ["a.cc"],
  exec_properties = {"key3": "value3", "overridden": "child_value"},
  exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "key2": "value2",
        "overridden": "parent_value",
    },
    constraint_values = [":local"],
)
EOF
  bazel build \
      --extra_execution_platforms="${pkg}:my_platform" \
      --toolchain_resolution_debug=.* \
       --execution_log_json_file out.txt \
      ${pkg}:a &> $TEST_log || fail "Build failed"
  grep "key3" out.txt || fail "Did not find the target attribute key"
  grep "child_value" out.txt || fail "Did not find the overriding value"
  grep "key2" out.txt || fail "Did not find the platform key"
}

function test_target_exec_properties_cc_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.cc <<EOF
#include <stdio.h>
int main() {
  printf("Hello\n");
}
EOF
  cat > ${pkg}/BUILD <<EOF
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
cc_test(
  name = "a",
  srcs = ["a.cc"],
  exec_properties = {"key3": "value3", "overridden": "child_value"},
  exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "key2": "value2",
        "overridden": "parent_value",
    },
    constraint_values = [":local"],
)
EOF
  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt &> $TEST_log || fail "Build failed"
  grep "key2" out.txt || fail "Did not find the platform key"
  grep "key3" out.txt || fail "Did not find the target attribute key"
  grep "child_value" out.txt || fail "Did not find the overriding value"
}

function test_target_test_properties_sh_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.sh <<EOF
#!/bin/bash
echo hello
EOF
  chmod u+x ${pkg}/a.sh
  cat > ${pkg}/BUILD <<EOF
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
sh_test(
  name = "a",
  srcs = ["a.sh"],
  exec_properties = {"key3": "value3", "overridden": "child_value"},
  exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "key2": "value2",
        "overridden": "parent_value",
    },
    constraint_values = [":local"],
)
EOF
  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt &> $TEST_log || fail "Build failed"
  grep "key2" out.txt || fail "Did not find the platform key"
  grep "key3" out.txt || fail "Did not find the target attribute key"
  grep "child_value" out.txt || fail "Did not find the overriding value"
}

function test_platform_execgroup_properties_cc_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.cc <<EOF
int main() {}
EOF
  cat > ${pkg}/BUILD <<EOF
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
cc_test(
  name = "a",
  srcs = ["a.cc"],
  exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "platform_key": "default_value",
        "test.platform_key": "test_value",
    },
    constraint_values = [":local"],
)
EOF
  bazel build --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt || fail "Build failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "default_value" out.txt || fail "Did not find the default value"
  grep "test_value" out.txt && fail "Used the test-action value when not testing"

  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt || fail "Test failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "test_value" out.txt || fail "Did not find the test-action value"
}

function test_starlark_test_has_test_execgroup_by_default() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"

  if "$is_windows"; then
    script_name="test_script.bat"
    script_content="@echo off\necho hello\n"
  else
    script_name="test_script.sh"
    script_content="#!/bin/bash\necho hello\n"
  fi

  cat > ${pkg}/defs.bzl <<EOF
def _impl(ctx):
    out_file = ctx.actions.declare_file("$script_name")
    ctx.actions.write(out_file, "$script_content", is_executable = True)
    return [DefaultInfo(executable = out_file)]

starlark_test = rule(
    implementation = _impl,
    test = True,
    # Do not define a "test" execgroup: it should exist by default.
)
EOF
  cat > ${pkg}/BUILD <<EOF
load("//$pkg:defs.bzl", "starlark_test")
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
starlark_test(
  name = "a_test",
  exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "platform_key": "default_value",
        "test.platform_key": "test_value",
    },
    constraint_values = [":local"],
)
EOF

  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a_test --execution_log_json_file out.txt || fail "Test failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "test_value" out.txt || fail "Did not find the test-action value"
}

function test_starlark_test_can_define_test_execgroup_manually() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"

  if "$is_windows"; then
    script_name="test_script.bat"
    script_content="@echo off\necho hello\n"
  else
    script_name="test_script.sh"
    script_content="#!/bin/bash\necho hello\n"
  fi

  cat > ${pkg}/defs.bzl <<EOF
def _impl(ctx):
    out_file = ctx.actions.declare_file("$script_name")
    ctx.actions.write(out_file, "$script_content", is_executable = True)
    return [DefaultInfo(executable = out_file)]

starlark_with_manual_test_exec_group_test = rule(
    implementation = _impl,
    test = True,
    exec_groups = {
        # Override the default "test" execgroup.
        "test": exec_group(),
    },
)
EOF
  cat > ${pkg}/BUILD <<EOF
load("//$pkg:defs.bzl", "starlark_with_manual_test_exec_group_test")
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")

starlark_with_manual_test_exec_group_test(
  name = "a_test",
  exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "platform_key": "default_value",
        "test.platform_key": "test_value",
    },
    constraint_values = [":local"],
)
EOF

  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a_test --execution_log_json_file out.txt || fail "Test failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "test_value" out.txt || fail "Did not find the test-action value"
}

function test_platform_execgroup_properties_nongroup_override_cc_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.cc <<EOF
int main() {}
EOF
  cat > ${pkg}/BUILD <<EOF
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
cc_library(name = "empty_lib")
cc_test(
  name = "a",
  srcs = ["a.cc"],
  exec_properties = {
    "platform_key": "override_value",
  },
  exec_compatible_with = [":local"],
  link_extra_lib = ":empty_lib",
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "platform_key": "default_value",
        "test.platform_key": "test_value",
    },
    constraint_values = [":local"],
)
EOF
  bazel build --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt || fail "Build failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "override_value" out.txt || fail "Did not find the overriding value"
  grep "default_value" out.txt && fail "Used the default value"

  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt || fail "Test failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "override_value" out.txt || fail "Did not find the overriding value"
}

function test_platform_execgroup_properties_group_override_cc_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.cc <<EOF
int main() {}
EOF
  cat > ${pkg}/BUILD <<EOF
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
cc_test(
  name = "a",
  srcs = ["a.cc"],
  exec_properties = {
    "test.platform_key": "test_override",
  },
  exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "platform_key": "default_value",
        "test.platform_key": "test_value",
    },
    constraint_values = [":local"],
)
EOF
  bazel build --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt || fail "Build failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "default_value" out.txt || fail "Used the default value"

  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt || fail "Test failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "test_override" out.txt || fail "Did not find the overriding test-action value"
}

function test_platform_execgroup_properties_override_group_and_default_cc_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.cc <<EOF
int main() {}
EOF
  cat > ${pkg}/BUILD <<EOF
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
cc_library(name = "empty_lib")
cc_test(
  name = "a",
  srcs = ["a.cc"],
  exec_properties = {
    "platform_key": "override_value",
    "test.platform_key": "test_override",
  },
  exec_compatible_with = [":local"],
  link_extra_lib = ":empty_lib",
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "platform_key": "default_value",
        "test.platform_key": "test_value",
    },
    constraint_values = [":local"],
)
EOF
  bazel build --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt || fail "Build failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "override_value" out.txt || fail "Did not find the overriding value"
  grep "default_value" out.txt && fail "Used the default value"

  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt || fail "Test failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "test_override" out.txt || fail "Did not find the overriding test-action value"
}

function test_platform_execgroup_properties_test_inherits_default() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.cc <<EOF
int main() {}
EOF
  cat > ${pkg}/BUILD <<EOF
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
cc_library(name = "empty_lib")
cc_test(
  name = "a",
  srcs = ["a.cc"],
  exec_compatible_with = [":local"],
  link_extra_lib = ":empty_lib",
)

# This platform should be first in --extra_execution_platforms.
# It has no constraints and only exists to detect if the correct platform is not
# used.
platform(
    name = "platform_no_constraint",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "exec_property": "no_constraint",
    },
)

# This platform should be second. The constraint means it will be used for
# the cc_test.
# The exec_property should be used for the actual test execution.
platform(
    name = "platform_with_constraint",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "exec_property": "requires_test_constraint",
    },
    constraint_values = [":local"],
)
EOF

  bazel test --extra_execution_platforms="${pkg}:platform_no_constraint,${pkg}:platform_with_constraint" ${pkg}:a --execution_log_json_file out.txt || fail "Test failed"
  grep --after=4 "platform" out.txt | grep "exec_property" || fail "Did not find the property key"
  grep --after=4 "platform" out.txt | grep "requires_test_constraint" || fail "Did not find the property value"
}

function test_platform_properties_only_applied_for_relevant_execgroups_cc_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.cc <<EOF
int main() {}
EOF
  cat > ${pkg}/BUILD <<EOF
constraint_setting(name = "setting")
constraint_value(name = "local", constraint_setting = ":setting")
cc_test(
  name = "a",
  srcs = ["a.cc"],
  exec_compatible_with = [":local"],
)

platform(
    name = "my_platform",
    parents = ["${default_host_platform}"],
    exec_properties = {
        "platform_key": "default_value",
        "unknown.platform_key": "unknown_value",
    },
    constraint_values = [":local"],
)
EOF
  bazel test --extra_execution_platforms="${pkg}:my_platform" ${pkg}:a --execution_log_json_file out.txt || fail "Build failed"
  grep "platform_key" out.txt || fail "Did not find the platform key"
  grep "default_value" out.txt || fail "Did not find the default value"
}

function test_cannot_set_properties_for_irrelevant_execgroup_on_target_cc_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"
  cat > ${pkg}/a.cc <<EOF
int main() {}
EOF
  cat > ${pkg}/BUILD <<EOF
cc_test(
  name = "a",
  srcs = ["a.cc"],
  exec_properties = {
    "platform_key": "default_value",
    "unknown.platform_key": "unknown_value",
  },
)
EOF
  bazel test ${pkg}:a &> $TEST_log && fail "Build passed when we expected an error"
  grep "Tried to set exec_properties for non-existent exec group" $TEST_log || fail "Did not complain about unknown exec group"
}

function write_toolchains_for_exec_group_tests() {
  mkdir -p ${pkg}/platform
  cat >> ${pkg}/platform/toolchain.bzl <<EOF
def _impl(ctx):
  toolchain = platform_common.ToolchainInfo(
      message = ctx.attr.message)
  return [toolchain]

test_toolchain = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    }
)
EOF

  cat >> ${pkg}/platform/BUILD <<EOF
package(default_visibility = ['//visibility:public'])
toolchain_type(name = 'toolchain_type')

constraint_setting(name = 'setting')
constraint_value(name = 'value_foo', constraint_setting = ':setting')
constraint_value(name = 'value_bar', constraint_setting = ':setting')

load(':toolchain.bzl', 'test_toolchain')

# Define the toolchains.
test_toolchain(
    name = 'test_toolchain_impl_foo',
    message = 'foo',
)
test_toolchain(
    name = 'test_toolchain_impl_bar',
    message = 'bar',
)

# Declare the toolchains.
toolchain(
    name = 'test_toolchain_foo',
    toolchain_type = ':toolchain_type',
    exec_compatible_with = [
        ':value_foo',
    ],
    target_compatible_with = [],
    toolchain = ':test_toolchain_impl_foo',
)
toolchain(
    name = 'test_toolchain_bar',
    toolchain_type = ':toolchain_type',
    exec_compatible_with = [
        ':value_bar',
    ],
    target_compatible_with = [],
    toolchain = ':test_toolchain_impl_bar',
)

# Define the platforms.
platform(
    name = 'platform_foo',
    constraint_values = [':value_foo'],
)
platform(
    name = 'platform_bar',
    constraint_values = [':value_bar'],
)
EOF

  cat >> ${TOOLCHAIN_REGISTRATION_FILE} <<EOF
register_toolchains('//${pkg}/platform:all')
register_execution_platforms('//${pkg}/platform:all')
EOF
}

function test_aspect_exec_groups_inherit_toolchains() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"

  write_toolchains_for_exec_group_tests

  # Add an aspect with exec groups.
  mkdir -p ${pkg}/aspect
  touch ${pkg}/aspect/BUILD
  cat >> ${pkg}/aspect/aspect.bzl <<EOF
def _impl(target, ctx):
    toolchain = ctx.toolchains['//${pkg}/platform:toolchain_type']
    print("hi from sample_aspect on %s, toolchain says %s" % (ctx.rule.attr.name, toolchain.message))

    extra_toolchain = ctx.exec_groups["extra"].toolchains["//${pkg}/platform:toolchain_type"]
    print("exec group extra: hi from sample_aspect on %s, toolchain says %s" % (ctx.rule.attr.name, extra_toolchain.message))

    return []

sample_aspect = aspect(
    implementation = _impl,
    exec_groups = {
        'extra': exec_group(
            exec_compatible_with = ['//${pkg}/platform:value_foo'],
            toolchains = ['//${pkg}/platform:toolchain_type']
        ),
    },
    exec_compatible_with = ['//${pkg}/platform:value_foo'],
    toolchains = ['//${pkg}/platform:toolchain_type'],
)
EOF

  # Define a simple rule to put the aspect on.
  mkdir -p ${pkg}/rule
  touch ${pkg}/rule/BUILD
  cat >> ${pkg}/rule/rule.bzl <<EOF
def _impl(ctx):
    pass

sample_rule = rule(
    implementation = _impl,
)
EOF

  # Use the aspect and check the results.
  mkdir -p ${pkg}/demo
  cat >> ${pkg}/demo/BUILD <<EOF
load('//${pkg}/rule:rule.bzl', 'sample_rule')

sample_rule(
    name = 'use',
)
EOF

  # Build the target, using debug messages to verify the correct toolchain was selected.
  bazel build --aspects=//${pkg}/aspect:aspect.bzl%sample_aspect //${pkg}/demo:use &> $TEST_log || fail "Build failed"
  expect_log "hi from sample_aspect on use, toolchain says foo"
  expect_log "exec group extra: hi from sample_aspect on use, toolchain says foo"
}

function test_aspect_exec_groups_different_toolchains() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"

  write_toolchains_for_exec_group_tests

  # Also write a new toolchain.
    mkdir -p ${pkg}/other
    cat >> ${pkg}/other/BUILD <<EOF
package(default_visibility = ['//visibility:public'])
toolchain_type(name = 'toolchain_type')

load('//${pkg}/platform:toolchain.bzl', 'test_toolchain')

# Define the toolchains.
test_toolchain(
    name = 'test_toolchain_impl_other',
    message = 'other',
)

# Declare the toolchains.
toolchain(
    name = 'test_toolchain_other',
    toolchain_type = ':toolchain_type',
    toolchain = ':test_toolchain_impl_other',
)
EOF

  cat >> ${TOOLCHAIN_REGISTRATION_FILE} <<EOF
register_toolchains('//${pkg}/other:all')
EOF

  # Add an aspect with exec groups.
  mkdir -p ${pkg}/aspect
  touch ${pkg}/aspect/BUILD
  cat >> ${pkg}/aspect/aspect.bzl <<EOF
def _impl(target, ctx):
    toolchain = ctx.toolchains['//${pkg}/platform:toolchain_type']
    print("hi from sample_aspect on %s, toolchain says %s" % (ctx.rule.attr.name, toolchain.message))

    other_toolchain = ctx.exec_groups["other"].toolchains["//${pkg}/other:toolchain_type"]
    print("exec group other: hi from sample_aspect on %s, toolchain says %s" % (ctx.rule.attr.name, other_toolchain.message))

    return []

sample_aspect = aspect(
    implementation = _impl,
    exec_groups = {
        # other defines new toolchain types.
        'other': exec_group(
            toolchains = ['//${pkg}/other:toolchain_type'],
        ),
    },
    toolchains = ['//${pkg}/platform:toolchain_type'],
)
EOF

  # Define a simple rule to put the aspect on.
  mkdir -p ${pkg}/rule
  touch ${pkg}/rule/BUILD
  cat >> ${pkg}/rule/rule.bzl <<EOF
def _impl(ctx):
    pass

sample_rule = rule(
    implementation = _impl,
)
EOF

  # Use the aspect and check the results.
  mkdir -p ${pkg}/demo
  cat >> ${pkg}/demo/BUILD <<EOF
load('//${pkg}/rule:rule.bzl', 'sample_rule')

sample_rule(
    name = 'use',
)
EOF

  # Build the target, using debug messages to verify the correct toolchain was selected.
  bazel build --aspects=//${pkg}/aspect:aspect.bzl%sample_aspect //${pkg}/demo:use &> $TEST_log || fail "Build failed"
  expect_log "hi from sample_aspect on use, toolchain says bar"
  expect_log "exec group other: hi from sample_aspect on use, toolchain says other"
}

# Test basic inheritance of constraints and toolchains on a single rule.
function test_exec_group_rule_constraint_inheritance() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"

  write_toolchains_for_exec_group_tests

  # Add a rule with default execution constraints.
  mkdir -p ${pkg}/demo
  cat >> ${pkg}/demo/rule.bzl <<EOF
def _impl(ctx):
    toolchain = ctx.toolchains['//${pkg}/platform:toolchain_type']
    out_file_main = ctx.actions.declare_file("%s.log" % ctx.attr.name)
    ctx.actions.run_shell(
        outputs = [out_file_main],
        command = "echo 'hi from %s, toolchain says %s' > '%s'" %
            (ctx.attr.name, toolchain.message, out_file_main.path),
    )

    out_file_extra = ctx.actions.declare_file("%s_extra.log" % ctx.attr.name)
    extra_toolchain = ctx.exec_groups['extra'].toolchains['//${pkg}/platform:toolchain_type']
    ctx.actions.run_shell(
        outputs = [out_file_extra],
        command = "echo 'extra from %s, toolchain says %s' > '%s'" %
            (ctx.attr.name, extra_toolchain.message, out_file_extra.path),
    )

    return [DefaultInfo(files = depset([out_file_main, out_file_extra]))]

sample_rule = rule(
    implementation = _impl,
    exec_groups = {
        # extra should contain both the exec constraint and the toolchain.
        'extra': exec_group(
            exec_compatible_with = ['//${pkg}/platform:value_foo'],
            toolchains = ['//${pkg}/platform:toolchain_type']
        ),
    },
    exec_compatible_with = ['//${pkg}/platform:value_foo'],
    toolchains = ['//${pkg}/platform:toolchain_type'],
)
EOF

  # Use the new rule.
  cat >> ${pkg}/demo/BUILD <<EOF
load(':rule.bzl', 'sample_rule')

sample_rule(
    name = 'use',
)
EOF

  # Build the target, using debug messages to verify the correct platform was selected.
  bazel build //${pkg}/demo:use &> $TEST_log || fail "Build failed"
  cat bazel-bin/${pkg}/demo/use.log >> $TEST_log
  cat bazel-bin/${pkg}/demo/use_extra.log >> $TEST_log
  expect_log "hi from use, toolchain says foo"
  expect_log "extra from use, toolchain says foo"
}

# Test basic inheritance of constraints and toolchains with a target.
function test_exec_group_target_constraint_inheritance() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"

  write_toolchains_for_exec_group_tests

  # Add a rule with default execution constraints.
  mkdir -p ${pkg}/demo
  cat >> ${pkg}/demo/rule.bzl <<EOF
def _impl(ctx):
    toolchain = ctx.toolchains['//${pkg}/platform:toolchain_type']
    out_file_main = ctx.actions.declare_file("%s.log" % ctx.attr.name)
    ctx.actions.run_shell(
        outputs = [out_file_main],
        command = "echo 'hi from %s, toolchain says %s' > '%s'" %
            (ctx.attr.name, toolchain.message, out_file_main.path),
    )

    out_file_extra = ctx.actions.declare_file("%s_extra.log" % ctx.attr.name)
    extra_toolchain = ctx.exec_groups['extra'].toolchains['//${pkg}/platform:toolchain_type']
    ctx.actions.run_shell(
        outputs = [out_file_extra],
        command = "echo 'extra from %s, toolchain says %s' > '%s'" %
            (ctx.attr.name, extra_toolchain.message, out_file_extra.path),
    )

    return [DefaultInfo(files = depset([out_file_main, out_file_extra]))]

sample_rule = rule(
    implementation = _impl,
    exec_groups = {
        # extra should contain the toolchain, and the exec constraint from the target.
        'extra': exec_group(toolchains = ['//${pkg}/platform:toolchain_type']),
    },
    toolchains = ['//${pkg}/platform:toolchain_type'],
)
EOF

  # Use the new rule.
  cat >> ${pkg}/demo/BUILD <<EOF
load(':rule.bzl', 'sample_rule')

sample_rule(
    name = 'use',
    exec_compatible_with = ['//${pkg}/platform:value_bar'],
)
EOF

  # Build the target, using debug messages to verify the correct platform was selected.
  bazel build //${pkg}/demo:use &> $TEST_log || fail "Build failed"
  cat bazel-bin/${pkg}/demo/use.log >> $TEST_log
  cat bazel-bin/${pkg}/demo/use_extra.log >> $TEST_log
  expect_log "hi from use, toolchain says bar"
  expect_log "extra from use, toolchain says bar"
}

function test_override_exec_group_of_test() {
  local -r pkg=${FUNCNAME[0]}
  mkdir $pkg || fail "mkdir $pkg"

  if "$is_windows"; then
    script_name="test_script.bat"
    script_content="@echo off\necho hello\n"
  else
    script_name="test_script.sh"
    script_content="#!/bin/bash\necho hello\n"
  fi
  cat > ${pkg}/rules.bzl <<EOF
def _impl(ctx):
  script = ctx.actions.declare_file("${script_name}")
  ctx.actions.write(script, "${script_content}", is_executable = True)
  return [
    DefaultInfo(executable = script),
    testing.ExecutionInfo({}, exec_group = "foo"),
  ]

my_rule_test = rule(
  implementation = _impl,
  exec_groups = {"foo": exec_group()},
  test = True,
)
EOF
  cat > ${pkg}/BUILD << EOF
load("//${pkg}:rules.bzl", "my_rule_test")

my_rule_test(
    name = "a_test",
    exec_properties = {"foo.testkey": "testvalue"},
)
EOF

  bazel test ${pkg}:a_test --execution_log_json_file out.txt &> $TEST_log || fail "Test execution failed"
  grep "testkey" out.txt || fail "Did not find the platform key"
  grep "testvalue" out.txt || fail "Did not find the platform value"
}

run_suite "exec group test"
