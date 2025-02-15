#!/bin/bash
#
# Copyright 2018 The Bazel Authors. All rights reserved.
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
# starlark_configuration_test.sh: integration tests for starlark build configurations

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

function set_up() {
  write_default_bazelrc
  add_to_bazelrc "build --package_path=%workspace%"
}

#### HELPER FXNS #######################################################

function write_build_setting_bzl() {
 cat > $pkg/build_setting.bzl <<EOF
BuildSettingInfo = provider(fields = ['name', 'value'])

def _build_setting_impl(ctx):
  return [BuildSettingInfo(name = ctx.attr.name, value = ctx.build_setting_value)]

drink_attribute = rule(
  implementation = _build_setting_impl,
  build_setting = config.string(flag = True),
)
EOF

  cat > $pkg/rules.bzl <<EOF
load("//$pkg:build_setting.bzl", "BuildSettingInfo")

def _impl(ctx):
  _type_name = ctx.attr._type[BuildSettingInfo].name
  _type_setting = ctx.attr._type[BuildSettingInfo].value
  print(_type_name + "=" + str(_type_setting))
  _temp_name = ctx.attr._temp[BuildSettingInfo].name
  _temp_setting = ctx.attr._temp[BuildSettingInfo].value
  print(_temp_name + "=" + str(_temp_setting))
  print("strict_java_deps=" + ctx.fragments.java.strict_java_deps)

drink = rule(
  implementation = _impl,
  attrs = {
    "_type":attr.label(default = Label("//$pkg:type")),
    "_temp":attr.label(default = Label("//$pkg:temp")),
  },
  fragments = ["java"],
)
EOF

  cat > $pkg/BUILD <<EOF
load("//$pkg:build_setting.bzl", "drink_attribute")
load("//$pkg:rules.bzl", "drink")

drink(name = 'my_drink')

drink_attribute(name = 'type', build_setting_default = 'unknown')
drink_attribute(name = 'temp', build_setting_default = 'unknown')
EOF
}

#### TESTS #############################################################

function test_default_flag() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  write_build_setting_bzl

  bazel build //$pkg:my_drink > output 2>"$TEST_log" || fail "Expected success"

  expect_log "type=unknown"
}

function test_set_flag() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  write_build_setting_bzl

  bazel build //$pkg:my_drink --//$pkg:type="coffee" \
    > output 2>"$TEST_log" || fail "Expected success"

  expect_log "type=coffee"
}

function test_starlark_and_native_flag() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  write_build_setting_bzl

  bazel build //$pkg:my_drink --//$pkg:type=coffee --strict_java_deps=off \
    > output 2>"$TEST_log" \
    || fail "Expected success"

  expect_log "type=coffee"
  expect_log "strict_java_deps=off"
}

function test_dont_parse_flags_after_dash_dash() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  write_build_setting_bzl

  bazel build //$pkg:my_drink --//$pkg:type=coffee \
    -- --//$pkg:temp=iced > output 2>"$TEST_log" \
    && fail "Expected failure"

  expect_log "invalid package name '-//test_dont_parse_flags_after_dash_dash'"
}

function test_multiple_starlark_flags() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  write_build_setting_bzl

  bazel build //$pkg:my_drink --//$pkg:type="coffee" --//$pkg:temp="iced" \
    > output 2>"$TEST_log" || fail "Expected success"

  expect_log "type=coffee"
  expect_log "temp=iced"

  bazel clean 2>"$TEST_log" || fail "Clean failed"

  # Ensure that order doesn't matter.
  bazel build //$pkg:my_drink --//$pkg:temp="iced" --//$pkg:type="coffee" \
    > output 2>"$TEST_log" || fail "Expected success"

  expect_log "type=coffee"
  expect_log "temp=iced"
}

function test_flag_default_change() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  write_build_setting_bzl

  bazel build //$pkg:my_drink > output 2>"$TEST_log" || fail "Expected success"

  expect_log "type=unknown"

  cat > $pkg/BUILD <<EOF
load("//$pkg:build_setting.bzl", "drink_attribute")
load("//$pkg:rules.bzl", "drink")

drink(name = 'my_drink')

drink_attribute(name = 'type', build_setting_default = 'cowabunga')
drink_attribute(name = 'temp', build_setting_default = 'cowabunga')
EOF

  bazel build //$pkg:my_drink > output 2>"$TEST_log" || fail "Expected success"

  expect_log "type=cowabunga"
}

# Regression tests for b/134580627
# Make sure package incrementality works during options parsing
function test_incremental_delete_build_setting() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  cat > $pkg/rules.bzl <<EOF
def _impl(ctx):
  return []

my_flag = rule(
  implementation = _impl,
  build_setting = config.string(flag = True)
)
simple_rule = rule(implementation = _impl)
EOF

  cat > $pkg/BUILD <<EOF
load("//$pkg:rules.bzl", "my_flag", "simple_rule")

my_flag(name = "my_flag", build_setting_default = "default")
simple_rule(name = "simple_rule")
EOF

  bazel build //$pkg:simple_rule --//$pkg:my_flag=cowabunga \
    > output 2>"$TEST_log" \
    || fail "Expected success"

  cat > $pkg/BUILD <<EOF
load("//$pkg:rules.bzl", "simple_rule")

simple_rule(name = "simple_rule")
EOF

  bazel build //$pkg:simple_rule --//$pkg:my_flag=cowabunga \
    > output 2>"$TEST_log" \
    && fail "Expected failure" || true

  expect_log "no such target '//$pkg:my_flag'"
}

#############################

function test_incremental_delete_build_setting_in_different_package() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  cat > $pkg/rules.bzl <<EOF
def _impl(ctx):
  return []

my_flag = rule(
  implementation = _impl,
  build_setting = config.string(flag = True)
)
simple_rule = rule(implementation = _impl)
EOF

  cat > $pkg/BUILD <<EOF
load("//$pkg:rules.bzl", "my_flag")
my_flag(name = "my_flag", build_setting_default = "default")
EOF

  mkdir -p pkg2

  cat > pkg2/BUILD <<EOF
load("//$pkg:rules.bzl", "simple_rule")
simple_rule(name = "simple_rule")
EOF

  bazel build //pkg2:simple_rule --//$pkg:my_flag=cowabunga \
    > output 2>"$TEST_log" || fail "Expected success"

  cat > $pkg/BUILD <<EOF
EOF

  bazel build //pkg2:simple_rule --//$pkg:my_flag=cowabunga \
    > output 2>"$TEST_log" && fail "Expected failure" || true

  expect_log "no such target '//$pkg:my_flag'"
}

#############################


function test_incremental_add_build_setting() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  cat > $pkg/rules.bzl <<EOF
def _impl(ctx):
  return []

my_flag = rule(
  implementation = _impl,
  build_setting = config.string(flag = True)
)
simple_rule = rule(implementation = _impl)
EOF

  cat > $pkg/BUILD <<EOF
load("//$pkg:rules.bzl", "simple_rule")

simple_rule(name = "simple_rule")
EOF

  bazel build //$pkg:simple_rule \
    > output 2>"$TEST_log" || fail "Expected success"

  cat > $pkg/BUILD <<EOF
load("//$pkg:rules.bzl", "my_flag", "simple_rule")

my_flag(name = "my_flag", build_setting_default = "default")
simple_rule(name = "simple_rule")
EOF

  bazel build //$pkg:simple_rule --//$pkg:my_flag=cowabunga \
    > output 2>"$TEST_log" || fail "Expected success"
}

function test_incremental_change_build_setting() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  cat > $pkg/rules.bzl <<EOF
MyProvider = provider(fields = ["value"])

def _flag_impl(ctx):
  return MyProvider(value = ctx.build_setting_value)

my_flag = rule(
  implementation = _flag_impl,
  build_setting = config.string(flag = True)
)

def _rule_impl(ctx):
  print("flag = " + ctx.attr.flag[MyProvider].value)

simple_rule = rule(
  implementation = _rule_impl,
  attrs = {"flag" : attr.label()}
)
EOF

  cat > $pkg/BUILD <<EOF
load("//$pkg:rules.bzl", "my_flag", "simple_rule")

my_flag(name = "my_flag", build_setting_default = "default")
simple_rule(name = "simple_rule", flag = ":my_flag")
EOF

  bazel build //$pkg:simple_rule --//$pkg:my_flag=yabadabadoo \
    > output 2>"$TEST_log" || fail "Expected success"

  expect_log "flag = yabadabadoo"

# update the flag to return a different value
  cat > $pkg/rules.bzl <<EOF
MyProvider = provider(fields = ["value"])

def _flag_impl(ctx):
  return MyProvider(value = "scoobydoobydoo")

my_flag = rule(
  implementation = _flag_impl,
  build_setting = config.string(flag = True)
)

def _rule_impl(ctx):
  print("flag = " + ctx.attr.flag[MyProvider].value)

simple_rule = rule(
  implementation = _rule_impl,
  attrs = {"flag" : attr.label()}
)
EOF

  bazel build //$pkg:simple_rule --//$pkg:my_flag=yabadabadoo \
    > output 2>"$TEST_log" || fail "Expected success"

  expect_log "flag = scoobydoobydoo"
}

# Test that label-typed build setting has access to providers of the
# target it points to.
function test_label_flag() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  cat > $pkg/BUILD <<EOF
load("//$pkg:rules.bzl", "my_rule", "simple_rule")

my_rule(name = "my_rule")

simple_rule(name = "default", value = "default_val")

simple_rule(name = "command_line", value = "command_line_val")

label_flag(
    name = "my_label_build_setting",
    build_setting_default = ":default"
)
EOF

  cat > $pkg/rules.bzl <<EOF
def _impl(ctx):
    _setting = ctx.attr._label_flag[SimpleRuleInfo].value
    print("value=" + _setting)

my_rule = rule(
    implementation = _impl,
    attrs = {
        "_label_flag": attr.label(default = Label("//$pkg:my_label_build_setting")),
    },
)

SimpleRuleInfo = provider(fields = ['value'])

def _simple_rule_impl(ctx):
    return [SimpleRuleInfo(value = ctx.attr.value)]

simple_rule = rule(
    implementation = _simple_rule_impl,
    attrs = {
        "value":attr.string(),
    },
)
EOF

  bazel build //$pkg:my_rule > output 2>"$TEST_log" || fail "Expected success"

  expect_log "value=default_val"

  bazel build //$pkg:my_rule --//$pkg:my_label_build_setting=//$pkg:command_line > output \
    2>"$TEST_log" || fail "Expected success"

  expect_log "value=command_line_val"
}

function write_label_flag_invalidation_transition() {
  local pkg="$1"
  local value="$2"

  cat > $pkg/transition.bzl <<EOF
def _impl(settings, attr):
    # buildifier: disable=unused-variable
    _ignore = settings, attr
    return {
        "//$pkg:my_label_build_setting": "//$pkg:$value",
    }

label_flag_transition = transition(
    implementation = _impl,
    inputs = [],
    outputs = ["//$pkg:my_label_build_setting"],
)
EOF
}

# Regression test for https://github.com/bazelbuild/bazel/issues/23097
function test_label_flag_invalidation() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  cat > $pkg/BUILD <<EOF
load("//$pkg:rules.bzl", "my_rule", "simple_rule")

my_rule(name = "my_rule")

simple_rule(name = "default", value = "default_val")

simple_rule(name = "first", value = "first_val")

simple_rule(name = "second", value = "second_val")

label_flag(
    name = "my_label_build_setting",
    build_setting_default = ":default"
)
EOF

  cat > $pkg/rules.bzl <<EOF
load("//$pkg:transition.bzl", "label_flag_transition")

def _impl(ctx):
    _setting = ctx.attr._label_flag[SimpleRuleInfo].value
    print("value=" + _setting)

my_rule = rule(
    implementation = _impl,
    cfg = label_flag_transition,
    attrs = {
        "_label_flag": attr.label(default = Label("//$pkg:my_label_build_setting")),
    },
)

SimpleRuleInfo = provider(fields = ['value'])

def _simple_rule_impl(ctx):
    return [SimpleRuleInfo(value = ctx.attr.value)]

simple_rule = rule(
    implementation = _simple_rule_impl,
    attrs = {
        "value":attr.string(),
    },
)
EOF

  # Write the transition to set the flag value to `first`.
  write_label_flag_invalidation_transition "$pkg" "first"
  bazel build //$pkg:my_rule > output 2>"$TEST_log" || fail "Expected success"
  expect_log "value=first_val"

  # Now rewrite the transition to change the value to second, and ensure the
  # target is re-run.
  write_label_flag_invalidation_transition "$pkg" "second"
  bazel build //$pkg:my_rule > output 2>"$TEST_log" || fail "Expected success"
  expect_log "value=second_val"
  expect_not_log "value=first_val"
}

function test_output_same_config_as_generating_target() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  rm -rf tools/allowlists/function_transition_allowlist
  mkdir -p tools/allowlists/function_transition_allowlist
  cat > tools/allowlists/function_transition_allowlist/BUILD <<EOF
package_group(
    name = "function_transition_allowlist",
    packages = [
        "//...",
    ],
)
EOF

  cat > $pkg/rules.bzl <<EOF
def _rule_class_transition_impl(settings, attr):
    return {
        "//command_line_option:platform_suffix": "blah"
    }

_rule_class_transition = transition(
    implementation = _rule_class_transition_impl,
    inputs = [],
    outputs = [
        "//command_line_option:platform_suffix",
    ],
)

def _rule_class_transition_rule_impl(ctx):
    ctx.actions.write(ctx.outputs.artifact, "hello\n")
    return [DefaultInfo(files = depset([ctx.outputs.artifact]))]

rule_class_transition_rule = rule(
    _rule_class_transition_rule_impl,
    cfg = _rule_class_transition,
    outputs = {"artifact": "%{name}.output"},
)
EOF

  cat > $pkg/BUILD <<EOF
load("//$pkg:rules.bzl", "rule_class_transition_rule")

rule_class_transition_rule(name = "with_rule_class_transition")
EOF

  bazel build //$pkg:with_rule_class_transition.output > output 2>"$TEST_log" || fail "Expected success"

  bazel cquery "deps(//$pkg:with_rule_class_transition.output, 1)" > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:with_rule_class_transition.output " output
  assert_contains "//$pkg:with_rule_class_transition " output

  # Find the lines of output for the output target and the generating target
  OUTPUT=$(grep "//$pkg:with_rule_class_transition.output " output)
  TARGET=$(grep "//$pkg:with_rule_class_transition " output)

  # Trim to just configuration
  OUTPUT_CONFIG=${OUTPUT#* }
  TARGET_CONFIG=${TARGET#* }

  # Confirm same configuration
  assert_equals $OUTPUT_CONFIG $TARGET_CONFIG
}

function test_starlark_flag_change_causes_action_rerun() {
  local -r pkg="$FUNCNAME"
  mkdir -p "$pkg"

  cat > "$pkg/rules.bzl" <<EOF
BuildSettingInfo = provider(fields = ["value"])

def _impl(ctx):
    return BuildSettingInfo(value = ctx.build_setting_value)

bool_flag = rule(
    implementation = _impl,
    build_setting = config.bool(flag = True),
)
EOF

  cat > "$pkg/BUILD" <<EOF
load(":rules.bzl", "bool_flag")

bool_flag(
    name = "myflag",
    build_setting_default = True,
)

config_setting(
    name = "myflag_selectable",
    flag_values = {":myflag": "True"},
)

genrule(
    name = "test_flag",
    outs = ["out-flag.txt"],
    cmd = "echo Value=" + select({
        ":myflag_selectable": "True",
        "//conditions:default": "False",
    }) + " | tee \$@",
)
EOF

  bazel shutdown

  # Setting --noexperimental_check_output_files is required to reproduce the
  # issue: The bug this test covers was caused by starlark flag changes not
  # invalidating the analysis cache, resulting in Bazel not re-running the
  # genrule action during the third invocation (because it sees the action in
  # skyframe as already executed from the first invocation). Bazel will by
  # default also invalidate actions by checking all output files for
  # modification *unless* an output service is registered (which can -
  # correctly - indicate that the output hasn't changed since the last build,
  # here between the second and third runs). As we don't have an output service
  # available in this test we disable output file checking entirely instead.
  bazel build "//$pkg:test_flag" "--//$pkg:myflag=true" \
      --noexperimental_check_output_files \
      2>>"$TEST_log" || fail "Expected build to succeed"
  assert_equals "Value=True" "$(cat bazel-genfiles/$pkg/out-flag.txt)"

  bazel build "//$pkg:test_flag" "--//$pkg:myflag=false" \
      --noexperimental_check_output_files \
      2>>"$TEST_log" || fail "Expected build to succeed"
  assert_equals "Value=False" "$(cat bazel-genfiles/$pkg/out-flag.txt)"

  bazel build "//$pkg:test_flag" "--//$pkg:myflag=true" \
      --noexperimental_check_output_files \
      2>>"$TEST_log" || fail "Expected build to succeed"
  assert_equals "Value=True" "$(cat bazel-genfiles/$pkg/out-flag.txt)"
}

# Integration test for an invalid output directory from a mnemonic via a
# transition. Integration test required because error is emitted in BuildTool.
# Unit test for dep transition in
# StarlarkRuleTransitionProviderTest#invalidMnemonicFromDepTransition.
function test_invalid_mnemonic_from_transition_top_level() {
  mkdir -p tools/allowlists/function_transition_allowlist test
  cat > tools/allowlists/function_transition_allowlist/BUILD <<'EOF'
package_group(
    name = 'function_transition_allowlist',
    packages = [
        '//test/...',
    ],
)
EOF
  cat > test/rule.bzl <<'EOF'
def _trans_impl(settings, attr):
  return {'//command_line_option:cpu': '//bad:cpu'}

my_transition = transition(implementation = _trans_impl, inputs = [],
  outputs = ['//command_line_option:cpu'])

def _impl(ctx):
  return []

my_rule = rule(
  implementation = _impl,
  cfg = my_transition,
)
EOF
  cat > test/BUILD <<'EOF'
load('//test:rule.bzl', 'my_rule')
my_rule(name = 'test')
EOF
  bazel build //test:test >& "$TEST_log" || exit_code="$?"
  assert_equals 1 "$exit_code" || fail "Expected exit code 1"
  expect_log "CPU/Platform descriptor '//bad:cpu'"
  expect_log "is invalid as part of a path: must not contain /"
}

function test_rc_flag_alias_canonicalizes() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  add_to_bazelrc "build --flag_alias=drink=//$pkg:type"

  write_build_setting_bzl

  bazel canonicalize-flags -- --drink=coffee \
    >& "$TEST_log" || fail "Expected success"

  expect_log "--//$pkg:type=coffee"
}

function test_rc_flag_alias_supported_under_common_command() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  add_to_bazelrc "common --flag_alias=drink=//$pkg:type"
  write_build_setting_bzl

  bazel canonicalize-flags -- --drink=coffee \
    >& "$TEST_log" || fail "Expected success"
  expect_log "--//$pkg:type=coffee"

  bazel build //$pkg:my_drink --drink=coffee \
    >& "$TEST_log" || fail "Expected success"
  expect_log "type=coffee"
}

function test_rc_flag_alias_with_usage_supported_under_common_command() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  add_to_bazelrc "common --flag_alias=drink=//$pkg:type"
  add_to_bazelrc "common --drink=coffee"
  write_build_setting_bzl

  # canonicalize-flags does not see the common flag in .bazelrc.
  bazel canonicalize-flags -- --drink=coffee >& "$TEST_log" || fail "Expected success"
  expect_log "--//$pkg:type=coffee"

  bazel build //$pkg:my_drink >& "$TEST_log" || fail "Expected success"
  expect_log "type=coffee"

  # Does not support Starlark flags.
  bazel query //$pkg:my_drink >& "$TEST_log" || fail "Expected success"
}

function test_rc_flag_alias_unsupported_under_test_command() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  add_to_bazelrc "test --flag_alias=drink=//$pkg:type"
  write_build_setting_bzl

  bazel canonicalize-flags -- --drink=coffee \
    >& "$TEST_log" && fail "Expected failure"
  expect_log "--flag_alias=drink=//$pkg:type\" disallowed. --flag_alias only "\
"supports these commands: build, common, always"

  bazel build //$pkg:my_drink >& "$TEST_log" && fail "Expected failure"
  expect_log "--flag_alias=drink=//$pkg:type\" disallowed. --flag_alias only "\
"supports these commands: build, common, always"

  # Post-test cleanup_workspace() calls "bazel clean", which would also fail
  # unless we reset the bazelrc.
  write_default_bazelrc
}

function test_rc_flag_alias_unsupported_under_conditional_build_command() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  add_to_bazelrc "build:foo --flag_alias=drink=//$pkg:type"
  write_build_setting_bzl

  bazel canonicalize-flags -- --drink=coffee \
>& "$TEST_log" && fail "Expected failure"
  expect_log "--flag_alias=drink=//$pkg:type\" disallowed. --flag_alias only "\
"supports these commands: build, common, always"

  bazel build //$pkg:my_drink >& "$TEST_log" && fail "Expected failure"
  expect_log "--flag_alias=drink=//$pkg:type\" disallowed. --flag_alias only "\
"supports these commands: build, common, always"

  # Post-test cleanup_workspace() calls "bazel clean", which would also fail
  # unless we reset the bazelrc.
  write_default_bazelrc
}

function test_rc_flag_alias_unsupported_with_space_assignment_syntax() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  add_to_bazelrc "test --flag_alias drink=//$pkg:type"
  write_build_setting_bzl

  bazel canonicalize-flags -- --drink=coffee \
    >& "$TEST_log" && fail "Expected failure"
  expect_log "--flag_alias\" disallowed. --flag_alias only "\
"supports these commands: build, common, always"

  bazel build //$pkg:my_drink >& "$TEST_log" && fail "Expected failure"
  expect_log "--flag_alias\" disallowed. --flag_alias only "\
"supports these commands: build, common, always"

  # Post-test cleanup_workspace() calls "bazel clean", which would also fail
  # unless we reset the bazelrc.
  write_default_bazelrc
}

# Regression test for https://github.com/bazelbuild/bazel/issues/13751.
function test_rule_exec_transition_warning() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  cat > "${pkg}/rules.bzl" <<EOF
def _impl(ctx):
    pass

demo = rule(
    implementation = _impl,
    cfg = config.exec(),
)
EOF

  cat > "${pkg}/BUILD" <<EOF
load(":rules.bzl", "demo")

demo(name = "demo")
EOF


  bazel build //$pkg:demo >& "$TEST_log" && fail "Expected failure"
  expect_not_log "crashed due to an internal error"
  expect_log '`cfg` must be set to a transition object initialized by the transition() function'
}

function write_attr_list_transition() {
  local pkg="${1}"
  mkdir -p "${pkg}"

  # Define starlark flag rules.
  mkdir -p settings
  touch settings/BUILD
  cat > settings/flag.bzl <<EOF
BuildSettingInfo = provider(fields = ["value"])

def _flag_impl(ctx):
    return [BuildSettingInfo(value = ctx.build_setting_value)]

bool_flag = rule(
    implementation = _flag_impl,
    build_setting = config.bool(flag = True)
)

string_list_flag = rule(
    implementation = _flag_impl,
    build_setting = config.string_list(),
)
EOF

  # Create transition definition
  mkdir -p "${pkg}/rule"
  touch "${pkg}/rule/BUILD"
  cat > "${pkg}/rule/def.bzl" <<EOF
load("//settings:flag.bzl", "BuildSettingInfo")

example_package = "${pkg}"

# Transition that checks a list-typed attribute.
def _transition_impl(settings, attr):
    values = getattr(attr, "values")
    sorted_values = sorted(values)
    print("From transition: values = %s" % str(values))
    print("From transition: sorted values = %s" % str(sorted_values))
    return {"//%s/flag:transition_output_flag" % example_package: sorted_values}

example_transition = transition(
    implementation = _transition_impl,
    inputs = [],
    outputs = ["//%s/flag:transition_output_flag" % example_package],
)

def _parent_rule_impl(ctx):
    attr_values = ctx.attr.values
    print("From parent rule attributes: values = %s" % str(attr_values))

parent = rule(
    implementation = _parent_rule_impl,
    cfg = example_transition,
    attrs = {
        "values": attr.string_list(),
        "child": attr.label(allow_single_file = True),
    },
)

def _child_rule_impl(ctx):
    flag_values = ctx.attr._transition_output_flag[BuildSettingInfo].value
    print("From child rule flag: values = %s" % str(flag_values))

    log = ctx.outputs.log
    content = "flag: %s" % str(flag_values)
    ctx.actions.write(
        output = log,
        content = content,
    )

child = rule(
    implementation = _child_rule_impl,
    attrs = {
        "_transition_output_flag": attr.label(default = "//%s/flag:transition_output_flag" % example_package),
        "log": attr.output(),
    },
)
EOF

  mkdir -p "${pkg}/flag"

  # Define the flags that are used.
  cat > "${pkg}/flag/BUILD" <<EOF
load("//settings:flag.bzl", "bool_flag", "string_list_flag")

package(
    default_visibility = ["//visibility:public"],
)

bool_flag(
    name = "select_flag",
    build_setting_default = True,
)


string_list_flag(
    name = "transition_output_flag",
    build_setting_default = [],
)
EOF
}

# Regression test for b/338660045
function test_inspect_attribute_list_direct() {
  local -r pkg="${FUNCNAME[0]}"

  write_attr_list_transition "${pkg}"

  # create rules with transition attached
  cat > "${pkg}/BUILD" <<EOF
load(
    "//${pkg}/rule:def.bzl",
    "parent", "child",
)

config_setting(
    name = "select_setting",
    flag_values = {"//${pkg}/flag:select_flag": "True"},
)

FRUITS = [
    # Deliberately not sorted.
    "banana",
    "grape",
    "apple",
]
ROCKS = [
    # Deliberately not sorted.
    "marble",
    "granite",
    "sandstone",
]

child(
    name = "child",
    log = "child.log",
)

parent(
    name = "top_level",
    child = ":child",
    values = select({
        ":select_setting": FRUITS,
        "//conditions:default": ROCKS,
    }),
)
EOF
  bazel build "--//${pkg}/flag:select_flag" "//${pkg}:top_level" &> $TEST_log || fail "Build failed"
  expect_log 'From parent rule attributes: values = \["banana", "grape", "apple"\]'
  expect_log 'From child rule flag: values = \["apple", "banana", "grape"\]'

  bazel build "--//${pkg}/flag:select_flag=false" "//${pkg}:top_level" &> $TEST_log || fail "Build failed"
  expect_log 'From parent rule attributes: values = \["marble", "granite", "sandstone"\]'
  expect_log 'From child rule flag: values = \["granite", "marble", "sandstone"\]'
}

function test_inspect_attribute_list_via_output() {
  local -r pkg="${FUNCNAME[0]}"

  write_attr_list_transition "${pkg}"

  # create rules with transition attached
  cat > "${pkg}/BUILD" <<EOF
load(
    "//${pkg}/rule:def.bzl",
    "parent", "child",
)

config_setting(
    name = "select_setting",
    flag_values = {"//${pkg}/flag:select_flag": "True"},
)

FRUITS = [
    # Deliberately not sorted.
    "banana",
    "grape",
    "apple",
]
ROCKS = [
    # Deliberately not sorted.
    "marble",
    "granite",
    "sandstone",
]

child(
    name = "child",
    log = "child.log",
)

parent(
    name = "top_level",
    # Here the dependency is via an output file instead of a direct target
    child = ":child.log",
    values = select({
        ":select_setting": FRUITS,
        "//conditions:default": ROCKS,
    }),
)
EOF
  bazel build "--//${pkg}/flag:select_flag" "//${pkg}:top_level" &> $TEST_log || fail "Build failed"
  expect_log 'From parent rule attributes: values = \["banana", "grape", "apple"\]'
  expect_log 'From child rule flag: values = \["apple", "banana", "grape"\]'

  bazel build "--//${pkg}/flag:select_flag=false" "//${pkg}:top_level" &> $TEST_log || fail "Build failed"
  expect_log 'From parent rule attributes: values = \["marble", "granite", "sandstone"\]'
  expect_log 'From child rule flag: values = \["granite", "marble", "sandstone"\]'
}

function test_transitions_baseline_options_affect_mnemonic() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p ${pkg}
  cat > "${pkg}/BUILD" <<EOF
load(":my_transition.bzl", "my_rule", "apply_transition", "int_flag")
int_flag(name = "my_int_flag", build_setting_default = 42)
my_rule(name = "A_rule")
apply_transition(name = "A_transitioner", dep = ":A_rule")
EOF
  cat > "${pkg}/my_transition.bzl" <<EOF
BuildSettingInfo = provider(fields = ['value'])
def _int_flag_impl(ctx):
     return BuildSettingInfo(value = ctx.build_setting_value)
int_flag = rule(
    implementation = _int_flag_impl,
    build_setting = config.int(flag = True),
)

def _my_transition_impl(settings, attr):
    return {"//${pkg}:my_int_flag": 9000}

my_transition = transition(
    implementation = _my_transition_impl,
    inputs = [],
    outputs = ["//${pkg}:my_int_flag"],
)

def _apply_transition_impl(ctx):
    pass

apply_transition = rule(
    implementation = _apply_transition_impl,
    attrs = { "dep": attr.label(cfg = my_transition) },
)

def _my_rule_impl(ctx):
    output = ctx.actions.declare_file(ctx.attr.name + ".out")
    ctx.actions.write(output, "unused")
    return DefaultInfo(files = depset([output]))

my_rule = rule(
    implementation = _my_rule_impl,
)
EOF

   # The cfg hash is static in both invocations below, because the configuration
   # of the target will be unchanged.
   cfg_hash=$(bazel cquery "filter(//$pkg, deps(//$pkg:A_transitioner))" | grep "//$pkg:A_rule" | cut -d'(' -f2 | cut -d ')' -f1)

   # This should be something like k8-fastbuild-ST-deadbeef, because the
   # checksum is derived from the delta of the build options (with
   # my_int_flag=9000 transition) to the baseline options (no mention of
   # my_int_flag).
   cfg_path_fragment_from_transition=$(bazel config | grep ${cfg_hash} | cut -d ' ' -f 2)

   bazel cquery //${pkg}:A_rule --//${pkg}:my_int_flag=9000
   # This should be something like k8-fastbuild without a checksum, because the
   # build options has no delta to the baseline options (my_int_flag=9000 is
   # also set in the command line).
   cfg_path_fragment_from_top_level=$(bazel config | grep ${cfg_hash} | cut -d ' ' -f 2)

   assert_not_equals ${cfg_path_fragment_from_top_level} ${cfg_path_fragment_from_transition}
}


run_suite "${PRODUCT_NAME} starlark configurations tests"
