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
# configured_query_test.sh: integration tests for bazel configured query.
# This tests the command line ui of configured query while
# ConfiguredTargetQueryTest tests its internal functionality.

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

add_to_bazelrc "build --package_path=%workspace%"

#### TESTS #############################################################

function test_basic_query() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
sh_library(name='maple', deps=[':japanese'])
sh_library(name='japanese')
EOF

  bazel cquery "deps(//$pkg:maple)" > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:maple" output
  assert_contains "//$pkg:japanese" output
}

function test_basic_query_output_textproto() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
sh_library(name='maple', deps=[':japanese'])
sh_library(name='japanese')
EOF

  bazel cquery --output=textproto "deps(//$pkg:maple)" > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "name: \"//$pkg:maple\"" output
  assert_contains "name: \"//$pkg:japanese\"" output
}

function test_basic_query_output_labelkind() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
sh_library(name='maple', data=[':japanese'])
cc_binary(name='japanese', srcs = ['japanese.cc'])
EOF

  bazel cquery --output=label_kind "deps(//$pkg:maple)" > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "sh_library rule //$pkg:maple" output
  assert_contains "cc_binary rule //$pkg:japanese" output
  assert_contains "source file //$pkg:japanese.cc" output
}

function test_config_checksum_determinism() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
sh_library(name = 'lib')
EOF

  bazel cquery "$pkg:lib" > out1 2>"$TEST_log" || fail "Expected success"
  bazel shutdown >& "$TEST_log" || fail "Failed to shutdown"
  bazel cquery "$pkg:lib" > out2 2>"$TEST_log" || fail "Expected success"

  output1="$(cat out1)"
  output2="$(cat out2)"
  if [[ "$output1" != "$output2" ]]
  then
    fail "$(cat<<EOF
Nondeterminism in cquery:
${output1}
!=
${output2}
You may have added an option without a meaningful toString() implementation
EOF
)"
  fi
}

function test_respects_selects() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
sh_library(
    name = "ash",
    deps = select({
        ":excelsior": [":foo"],
        ":americana": [":bar"],
    }),
)
sh_library(name = "foo")
sh_library(name = "bar")
config_setting(
    name = "excelsior",
    values = {"define": "species=excelsior"},
)
config_setting(
    name = "americana",
    values = {"define": "species=americana"},
)
EOF

  bazel cquery "deps(//$pkg:ash)" --define species=excelsior  > output \
    2>"$TEST_log" || fail "Excepted success"
  assert_contains "//$pkg:foo" output
  assert_not_contains "//$pkg:bar" output
}

function test_empty_results_printed() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
sh_library(name='redwood', deps=[':sequoia',':sequoiadendron'])
sh_library(name='sequoia')
sh_library(name='sequoiadendron')
EOF

  bazel cquery "somepath(//$pkg:sequoia,//$pkg:sequoiadendron)" \
    > output 2>"$TEST_log" || fail "Expected success"

  expect_log "INFO: Empty query results"
  assert_not_contains "//$pkg:sequoiadendron" output
}

function test_universe_scope_specified() {
  local -r pkg=$FUNCNAME
  write_test_targets $pkg

  # The java_library rule has a host transition on its plugins attribute.
  bazel cquery //$pkg:target+//$pkg:host --universe_scope=//$pkg:main \
    > output 2>"$TEST_log" || fail "Excepted success"

  # Find the lines of output for //$pkg:plugin and //$pkg:dep.
  PKG_HOST=$(grep "//$pkg:host" output)
  PKG_TARGET=$(grep "//$pkg:target" output)
  # Trim to just configurations.
  HOST_CONFIG=${PKG_HOST/"//$pkg:host"}
  TARGET_CONFIG=${PKG_TARGET/"//$pkg:target"}
  # Ensure they are are not equal.
  assert_not_equals $HOST_CONFIG $TARGET_CONFIG
}

function test_transitions_lite() {
  local -r pkg=$FUNCNAME
  write_test_targets $pkg

  bazel cquery "deps(//$pkg:main)" --transitions=lite \
    > output 2>"$TEST_log" || fail "Excepted success"

  assert_contains "//$pkg:main" output
  assert_contains "host_dep#//$pkg:host#(exec + (TestTrimmingTransition + ConfigFeatureFlagTaggedTrimmingTransition))" output
}


function test_transitions_full() {
  local -r pkg=$FUNCNAME
  write_test_targets $pkg

  bazel cquery "deps(//$pkg:main)" --transitions=full \
    > output 2>"$TEST_log" || fail "Excepted success"

  assert_contains "//$pkg:main" output
  assert_contains "host_dep#//$pkg:host#(exec + (TestTrimmingTransition + ConfigFeatureFlagTaggedTrimmingTransition))" output
}

function test_transitions_incompatible_target() {
  local -r pkg=$FUNCNAME

  mkdir -p $pkg
  cat > $pkg/BUILD <<EOF
constraint_setting(name = "incompatible_setting")
constraint_value(
    name = "incompatible",
    constraint_setting = ":incompatible_setting",
)
genrule(
    name = "gr",
    srcs = [":input"],
    cmd = "touch $@",
    outs = ["out"],
    target_compatible_with = [":incompatible"],
)
genrule(
    name = "input",
    cmd = "echo hi > $@",
    outs = ["in"],
)
EOF

    bazel cquery "deps(//$pkg:gr)" --transitions=lite \
      > output 2>"$TEST_log" || fail "Excepted success"

    assert_contains "//$pkg:gr" output
    assert_not_contains "//$pkg:input" output
    expect_log "WARNING: Skipping dependencies of incompatible target //$pkg:gr"
}

function write_test_targets() {
  mkdir -p $pkg
  cat > $pkg/rule.bzl <<'EOF'
def _my_rule_impl(ctx):
    pass
my_rule = rule(
    implementation = _my_rule_impl,
    attrs = {
      "src_dep": attr.label(allow_single_file = True),
      "target_dep": attr.label(cfg = 'target'),
      "host_dep": attr.label(cfg = 'exec'),
    },
)
EOF
  cat > $pkg/BUILD <<'EOF'
load(':rule.bzl', 'my_rule')
filegroup(name = "target")
filegroup(name = "host")
my_rule(
    name = "main",
    src_dep = "file.txt",
    target_dep = ":target",
    host_dep = ":host",
)
EOF
  touch $pkg/file.txt
}

# TODO(gregce): --show_config_fragments and RequiredConfigFragmentsProvider
# (the native Java code that powers --show_config_fragments) were originally
# conceived as two pieces of the same functionality. But the former is just
# a provider, which means it can be consumed by other logic. Consider moving
# these tests out of cquery and into proper Java integration tests.

function test_show_transitive_config_fragments() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  add_rules_python "MODULE.bazel"
  cat > $pkg/BUILD <<'EOF'
load("@rules_python//python:py_library.bzl", "py_library")

cc_library(
    name = "cclib",
    srcs = ["mylib.cc"],
)

cc_library(
    name = "cclib_with_py_dep",
    srcs = ["mylib2.cc"],
    data = [":pylib"],
)

py_library(
    name = "pylib",
    srcs = ["pylib.py"],
)
EOF

  bazel cquery "//$pkg:*" --show_config_fragments=transitive > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:cclib .*CppConfiguration" output
  assert_not_contains "//$pkg:cclib .*PythonConfiguration" output

  assert_contains "//$pkg:cclib_with_py_dep .*CppConfiguration" output
  assert_contains "//$pkg:cclib_with_py_dep .*PythonConfiguration" output

  assert_not_contains "//$pkg:pylib .*CppConfiguration" output
  assert_contains "//$pkg:pylib .*PythonConfiguration" output

  assert_contains "//$pkg:mylib.cc (null) \[\]" output
}

function test_show_transitive_config_fragments_select() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
cc_library(
    name = "cclib",
    srcs = ["mylib.cc"],
    deps = [":cclib_with_select"]
)

config_setting(
    name = "py_reading_condition",
    values = {"build_python_zip": "1"})

cc_library(
    name = "cclib_with_select",
    srcs = select({
        ":py_reading_condition": ["version1.cc"],
        "//conditions:default": ["version2.cc"],
    })
)
EOF

  bazel cquery "//$pkg:all" --show_config_fragments=transitive > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:cclib .*CppConfiguration" output
  assert_contains "//$pkg:cclib .*PythonOptions" output

  assert_contains "//$pkg:py_reading_condition .*PythonOptions" output

  assert_contains "//$pkg:cclib_with_select .*CppConfiguration" output
  assert_contains "//$pkg:cclib_with_select .*PythonOptions" output
}

function test_show_transitive_config_fragments_alias() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  add_rules_python "MODULE.bazel"
  cat > $pkg/BUILD <<'EOF'
load("@rules_python//python:py_library.bzl", "py_library")

cc_library(
    name = "cclib_with_py_dep",
    srcs = ["mylib2.cc"],
    data = [":myalias"],
)

alias(
    name = "myalias",
    actual = ":pylib"
)

py_library(
    name = "pylib",
    srcs = ["pylib.py"],
)
EOF

  bazel cquery "//$pkg:all" --show_config_fragments=transitive > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:cclib_with_py_dep .*CppConfiguration" output
  assert_contains "//$pkg:cclib_with_py_dep .*PythonConfiguration" output
}

function test_direct_alias_requirements() {
  # Aliases delegate many read calls to their actual targets. This test
  # ensures we don't skip requirements that the alias has but its actual
  # target doesn't.
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
cc_library(
    name = "ccrule",
    srcs = ["ccrule.cc"],
)
config_setting(
    name = "cfg",
    define_values = {"abc": "1"},
)
alias(
    name = "al",
    actual = select({
        ":cfg": ":ccrule",
    }),
)
EOF

  bazel cquery "//$pkg:al" --show_config_fragments=direct --define=abc=1 \
    > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:al .*--define:abc" output
  # CppConfiguration is a transitive, not direct, requirement.
  assert_not_contains "//$pkg:al .*CppConfiguration" output
}

function test_show_transitive_config_fragments_host_deps() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  add_rules_python "MODULE.bazel"
  cat > $pkg/BUILD <<'EOF'
load("@rules_python//python:py_library.bzl", "py_library")

cc_library(
    name = "cclib_with_py_dep",
    srcs = ["mylib2.cc"],
    data = [":g"],
)

genrule(
    name = "g",
    srcs = [],
    outs = ["g.out"],
    cmd = "echo Hello! > $@",
    tools = [":pylib"])

py_library(
    name = "pylib",
    srcs = ["pylib.py"],
)
EOF

  bazel cquery "//$pkg:cclib_with_py_dep" --show_config_fragments=transitive > \
    output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:cclib_with_py_dep .*PythonConfiguration" output
}

function test_show_transitive_config_fragments_through_output_file() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  add_rules_python "MODULE.bazel"
  cat > $pkg/BUILD <<'EOF'
load("@rules_python//python:py_library.bzl", "py_library")

cc_library(
    name = "cclib_with_py_dep",
    srcs = ["mylib2.cc"],
    data = [":g.out"],    # Output file dependency declared here.
)

genrule(
    name = "g",
    srcs = [],
    outs = ["g.out"],
    cmd = "echo Hello! > $@",
    tools = [":pylib"])

py_library(
    name = "pylib",
    srcs = ["pylib.py"],
)
EOF

  bazel cquery "//$pkg:cclib_with_py_dep" --show_config_fragments=transitive > \
    output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:cclib_with_py_dep .*PythonConfiguration" output
}

function test_show_direct_config_fragments() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  add_rules_python "MODULE.bazel"
  cat > $pkg/BUILD <<'EOF'
load("@rules_python//python:py_library.bzl", "py_library")

cc_library(
    name = "cclib",
    srcs = ["mylib.cc"],
)

cc_library(
    name = "cclib_with_py_dep",
    srcs = ["mylib2.cc"],
    data = [":pylib"],
)

py_library(
    name = "pylib",
    srcs = ["pylib.py"],
)
EOF

  bazel cquery "//$pkg:all" --show_config_fragments=direct > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:cclib .*CppConfiguration" output
  assert_not_contains "//$pkg:cclib .*PythonConfiguration" output

  assert_contains "//$pkg:cclib_with_py_dep .*CppConfiguration" output
  assert_not_contains "//$pkg:cclib_with_py_dep .*PythonConfiguration" output
}

function test_show_direct_config_fragments_select() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
cc_library(
    name = "cclib",
    srcs = ["mylib.cc"],
    deps = [":cclib_with_select"]
)

config_setting(
    name = "py_reading_condition",
    values = {
      "build_python_zip": "1",
      "shell_executable": "foo"
    }
)

cc_library(
    name = "cclib_with_select",
    srcs = select({
        ":py_reading_condition": ["version1.cc"],
        "//conditions:default": ["version2.cc"],
    })
)
EOF

  bazel cquery "//$pkg:all" --show_config_fragments=direct > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:cclib .*CppConfiguration" output
  assert_not_contains "//$pkg:cclib .*PythonOptions" output

  assert_contains "//$pkg:py_reading_condition .*PythonOptions" output
  assert_contains "//$pkg:py_reading_condition .*ShellConfiguration\$Options" output

  assert_contains "//$pkg:cclib_with_select .*CppConfiguration" output
  assert_contains "//$pkg:cclib_with_select .*PythonOptions" output
  assert_contains "//$pkg:cclib_with_select .*ShellConfiguration\$Options" output
}

function test_show_config_fragments_select_on_starlark_option() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/defs.bzl <<'EOF'
def _string_flag_impl(ctx):
    pass

string_flag = rule(
    implementation = _string_flag_impl,
    build_setting = config.string()
)
EOF
  cat > $pkg/BUILD <<'EOF'
load(":defs.bzl", "string_flag")
string_flag(
    name = "my_flag",
    build_setting_default = "default_value"
)
config_setting(
    name = "is_my_flag_foo",
    flag_values = {":my_flag": "foo"}
)
cc_library(
    name = "cclib_with_select",
    srcs = select({
        ":is_my_flag_foo": ["version_foo.cc"],
        "//conditions:default": ["version_default.cc"],
    })
)
cc_library(
    name = "cclib_plain",
    srcs = ["version_plain.cc"]
)
EOF

  bazel cquery "//$pkg:all" --show_config_fragments=direct > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:my_flag .*//$pkg:my_flag" output
  assert_contains "//$pkg:is_my_flag_foo .*//$pkg:my_flag" output
  assert_contains "//$pkg:cclib_with_select .*//$pkg:my_flag" output

  assert_not_contains "//$pkg:cclib_plain .*//$pkg:my_flag" output
}

function test_show_config_fragments_starlark_rule_requires_starlark_option() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/defs.bzl <<EOF
def _string_flag_impl(ctx):
    pass

string_flag = rule(
    implementation = _string_flag_impl,
    build_setting = config.string()
)

def _rule_with_flag_dep_impl(ctx):
    pass

rule_with_flag_dep = rule(
    implementation = _rule_with_flag_dep_impl,
    attrs = {
        "_flagdep": attr.label(default = "//$pkg:my_flag")
    }
)
EOF
  cat > $pkg/BUILD <<'EOF'
load(":defs.bzl", "rule_with_flag_dep", "string_flag")
string_flag(
    name = "my_flag",
    build_setting_default = "default_value"
)
rule_with_flag_dep(
    name = "my_rule"
)
EOF

  bazel cquery "//$pkg:all" --show_config_fragments=direct > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:my_rule .*//$pkg:my_flag" output

  bazel cquery "//$pkg:all" --show_config_fragments=transitive > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:my_rule .*//$pkg:my_flag" output
}

function test_show_config_fragments_select_on_feature_flag_info_provider() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/defs.bzl <<'EOF'
def _feature_flag_provider_rule_impl(ctx):
    return [config_common.FeatureFlagInfo(value = "foo")]

feature_flag_provider_rule = rule(
    implementation = _feature_flag_provider_rule_impl,
)
EOF
  cat > $pkg/BUILD <<'EOF'
load(":defs.bzl", "feature_flag_provider_rule")
feature_flag_provider_rule(name = "foo_feature")

config_setting(
    name = "is_foo_feature",
    flag_values = {":foo_feature": "foo"},
)

cc_library(
    name = "cclib_select_on_foo_feature",
    srcs = ["hi.cc"],
    deps = select({
        ":is_foo_feature": [],
        "//conditions:default": [],
    }),
)

cc_library(
    name = "cclib_no_select",
    srcs = ["heya.cc"]
)
EOF

  bazel cquery "//$pkg:all" --show_config_fragments=direct > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:is_foo_feature .*//$pkg:foo_feature" output
  assert_contains "//$pkg:cclib_select_on_foo_feature .*//$pkg:foo_feature" output
  assert_not_contains "//$pkg:cclib_no_select .*//$pkg:foo_feature" output

  # Starlark rules exposing FeatureFlagInfo aren't really part of the
  # configuration. You can't "set" them as part of the configuration like you
  # can Starlark flags or Android feature flags. All they do is provide a new
  # interface for rules to select() over the existing configuration.
  #
  # Nevertheless, since you can select() on them as this test illustrates, it's
  # reasonable to "pretend" for the purposes of --show_config_fragments. At best
  # they still show interesting dependencies. At worst they provide a bit more
  # conceptual clutter to wade through when analyzing a build graph.
  #
  # If we're going to support them, it conceptually makes sense to consider
  # //$pkg:foo_feature a config dependency on itself. That doesn't currently
  # work. The only reason is because the most natural place to encode this
  # is to have lib.analysis.RuleConfiguredTargetBuilder check if the rule
  # provides lib.rules.config.ConfigFeatureFlagProvider. But that adds an
  # unwanted dependency from lib.analysis onto lib.rules.
  #
  # Given this use case's unimportance, we just leave things as-is for the sake
  # of simplicity in the wider code base. We can always re-evaluate if needed.
  assert_not_contains "//$pkg:foo_feature .*//$pkg:foo_feature" output
}

function test_show_config_fragments_on_define() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
config_setting(
    name = "is_a_on",
    define_values = {"a": "on"}
)

cc_library(
    name = "cclib_with_select",
    srcs = select({
        ":is_a_on": ["version1.cc"],
        "//conditions:default": ["version2.cc"],
    })
)
EOF

  bazel cquery "//$pkg:all" --show_config_fragments=direct --define a=on \
    --define b=on > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:cclib_with_select .*CppConfiguration" output
  assert_contains "//$pkg:cclib_with_select .*--define:a" output
  assert_not_contains "//$pkg:cclib_with_select .*--define:b" output
}

function test_show_config_fragments_on_starlark_required_fragments() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/defs.bzl <<'EOF'
def _impl(ctx):
  pass

java_requiring_rule = rule(
  implementation = _impl,
  fragments = ["java"],
  attrs = {}
)
EOF
  cat > $pkg/BUILD <<EOF
load("//$pkg:defs.bzl", "java_requiring_rule")
java_requiring_rule(
  name = "buildme"
)
EOF

  bazel cquery "//$pkg:all" --show_config_fragments=direct \
    > output 2>"$TEST_log" || fail "Expected success"
  assert_contains "//$pkg:buildme .*JavaConfiguration" output
}

# Starlark aspects can't directly require configuration fragments. But they can
# have implicit label dependencies on rules that do. This test ensures that
# gets factored in.
#
# Note that cquery doesn't support queries over aspects: `deps(//foo)` won't
# list aspects or traverse their dependencies. This makes it hard to understand
# *why* a rule requires a fragment if only through an aspect. That's an argument
# for making cquery generally aspect-aware.
function test_show_config_fragments_includes_starlark_aspects() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/defs.bzl <<EOF
def _aspect_impl(target, ctx):
    return []

cc_depending_aspect = aspect(
    attrs = {
        # This creates an implicit dependency on a C++ library that
        # only comes through the aspect.
        "_extra_dep": attr.label(default = "//$pkg:cclib"),
    },
    implementation = _aspect_impl,
)

def _impl(ctx):
    pass

simple_rule = rule(
  implementation = _impl,
  attrs = {
    "deps": attr.label_list(aspects = [cc_depending_aspect])
  }
)
EOF

  cat > $pkg/BUILD <<EOF
load("//$pkg:defs.bzl", "simple_rule")

simple_rule(
    name = "buildme",
    deps = [":simple_dep"],
)

simple_rule(
    name = "simple_dep",
)

cc_library(
    name = "cclib",
    srcs = ["cclib.cc"],
)
EOF

  # No direct requirement:
  bazel cquery "//$pkg:buildme" --show_config_fragments=direct \
    > output 2>"$TEST_log" || fail "Expected success"
  assert_not_contains "//$pkg:buildme .*CppConfiguration" output

  # But there is a transitive requirement through the aspect:
  bazel cquery "//$pkg:buildme" --show_config_fragments=transitive \
    > output 2>"$TEST_log" || fail "Expected success"
  assert_contains "//$pkg:buildme .*CppConfiguration" output
}

function test_manual_tagged_targets_always_included_for_queries() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
genrule(
  name = "always_build",
  srcs = [],
  outs = ["always_build.out"],
  cmd = "echo hi > $@")
genrule(
  name = "only_build_explicitly",
  tags = ["manual"],
  srcs = [],
  outs = ["only_build_explicitly.out"],
  cmd = "echo hi > $@")
EOF

  bazel cquery "//$pkg:all" > output 2>"$TEST_log" || fail "Expected success"
  assert_contains "//$pkg:always_build" output
  assert_contains "//$pkg:only_build_explicitly" output
}

function test_include_test_suites() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
test_suite(
  name = "my_suite",
  tests = [":my_test"])
cc_test(
  name = "my_test",
  srcs = ["my_test.cc"])
EOF

  bazel cquery "//$pkg:all" > output 2>"$TEST_log" || fail "Expected success"
  assert_contains "//$pkg:my_suite" output
  assert_contains "//$pkg:my_test" output
}

function test_build_tests_only_override() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
cc_binary(
  name = "not_a_test",
  srcs = ["not_a_test.cc"])
EOF

  bazel cquery --build_tests_only "//$pkg:all" > output 2>"$TEST_log" || \
    fail "Expected success"
  assert_contains "//$pkg:not_a_test" output
}

function test_label_output_shows_alias_labels() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
filegroup(name = "fg", srcs = [":the_alias"])
alias(name = "the_alias", actual = "some_file")
EOF

  bazel cquery "deps(//$pkg:fg)" > output 2>"$TEST_log" || fail "Expected
  success"
  assert_contains "//$pkg:the_alias" output
  assert_contains "//$pkg:some_file (null)" output
  assert_equals "$(grep some_file output | wc -l | egrep -o '[0-9]+')" "1"
}

function test_transitions_output_shows_alias_labels() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
filegroup(name = "fg", srcs = [":the_alias"])
alias(name = "the_alias", actual = "some_file")
EOF

  bazel cquery "deps(//$pkg:fg)" --transitions=lite > output 2>"$TEST_log" || fail "Expected
  success"
  assert_contains "//$pkg:the_alias" output
  assert_contains "//$pkg:some_file (null)" output
  assert_equals "$(grep some_file output | wc -l | egrep -o '[0-9]+')" "1"
}

function test_starlark_output_mode() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  add_rules_python "MODULE.bazel"
  cat > $pkg/BUILD <<'EOF'
load("@rules_python//python:py_library.bzl", "py_library")

py_library(
    name = "pylib",
    srcs = ["pylib.py"],
)

py_library(
    name = "pylibtwo",
    srcs = ["pylibtwo.py", "pylibtwo2.py",],
)
EOF

  bazel cquery "//$pkg:all" --output=starlark \
    --starlark:expr="str(target.label) + '%foo'" > output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "^@@\?//$pkg:pylib%foo$" output
  assert_contains "^@@\?//$pkg:pylibtwo%foo$" output

  bazel cquery "//$pkg:all" --output=starlark \
    --noincompatible_unambiguous_label_stringification \
    --starlark:expr="str(target.label) + '%foo'" > output \
    2>"$TEST_log" || fail "Expected success"

  # Verify use of the effective rather than default Starlark semantics.
  assert_contains "^//$pkg:pylib%foo$" output
  assert_contains "^//$pkg:pylibtwo%foo$" output

  # Test that the default for --starlark:expr str(target.label)
  bazel cquery "//$pkg:all" --output=starlark >output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:pylib" output
  assert_contains "//$pkg:pylibtwo" output

  bazel cquery "//$pkg:all" --output=starlark \
    --starlark:expr="str(target.label) + '%' + str(target.files.to_list()[1].is_directory)" \
    > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:pylibtwo%False" output
  # pylib evaluation will fail, as it has only one output file.
  assert_contains "Starlark evaluation error for //$pkg:pylib" "$TEST_log"

  cat > $pkg/outfunc.bzl <<'EOF'
SUFFIX='%foo_file'

def format(t):
    return str(t.label) + SUFFIX

UNUSED_THING_AT_END=1
EOF

  bazel cquery "//$pkg:all" --output=starlark --starlark:file="$pkg/outfunc.bzl" >output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:pylib%foo_file" output
  assert_contains "//$pkg:pylibtwo%foo_file" output


  cat > $pkg/outfunc_isdir.bzl <<'EOF'
def format(t):
    return str(t.label) + '%' + str(t.files.to_list()[1].is_directory)
EOF

  bazel cquery "//$pkg:all" --output=starlark --starlark:file="$pkg/outfunc_isdir.bzl" \
    >output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:pylibtwo%False" output
  # pylib evaluation will fail, as it has only one output file.
  assert_contains "Starlark evaluation error for //$pkg:pylib" "$TEST_log"
}

function test_starlark_build_options() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

cat > tools/allowlists/function_transition_allowlist/BUILD <<EOF
package_group(
    name = "function_transition_allowlist",
    packages = [
        "//...",
    ],
)
EOF

  cat > "$pkg/rules.bzl" <<EOF
BuildSettingInfo = provider(fields = ["value"])

def _flag_impl(ctx):
    return BuildSettingInfo(value = ctx.build_setting_value)

bool_flag = rule(
    implementation = _flag_impl,
    build_setting = config.bool(flag = True),
)

def _list_flag_impl(ctx):
    return BuildSettingInfo(value = ctx.build_setting_value)

list_flag = rule(
    implementation = _list_flag_impl,
    build_setting = config.string_list(flag = True),
)

def _dep_transition_impl(settings, attr):
    return {
        "//$pkg:myflag": True,
        "//$pkg:mylistflag": ["a", "b"],
        "//command_line_option:platform_suffix": "blah"
    }

_dep_transition = transition(
    implementation = _dep_transition_impl,
    inputs = [],
    outputs = [
        "//$pkg:myflag",
        "//$pkg:mylistflag",
        "//command_line_option:platform_suffix",
    ],
)

def _root_rule_impl(ctx):
    return []

root_rule = rule(
    _root_rule_impl,
    attrs = {
        "deps": attr.label_list(cfg = _dep_transition),
    },
)
EOF

  add_rules_python "MODULE.bazel"
  cat > $pkg/BUILD <<'EOF'
load(":rules.bzl", "bool_flag", "list_flag", "root_rule")
load("@rules_python//python:py_library.bzl", "py_library")

exports_files(["rules.bzl"])

bool_flag(
    name = "myflag",
    build_setting_default = False,
)

list_flag(
    name = "mylistflag",
    build_setting_default = ["c"],
)

py_library(
    name = "bar",
    srcs = ["pylib.py"],
)

root_rule(
    name = "foo",
    deps = ["bar"],
)
EOF

  cat > $pkg/expr.star <<EOF
def format(target):
  bo = build_options(target)
  print(bo)
  if bo == None:
    return str(target.label) + '%None'
  first = str(bo['//command_line_option:platform_suffix'])
  second = str(('//$pkg:myflag' in bo) and bo['//$pkg:myflag'])
  third = str(bo['//$pkg:mylistflag'] if '//$pkg:mylistflag' in bo else None)
  return str(target.label) + '%' + first + '%' + second + '%' + third
EOF

  bazel cquery "//$pkg:bar" --output=starlark \
    --starlark:file=$pkg/expr.star > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:bar%None%False%None" output

  bazel cquery "//$pkg:bar" --output=starlark \
    --//$pkg:myflag=True --//$pkg:mylistflag=c,d \
    --starlark:file=$pkg/expr.star > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:bar%None%True%\\[\"c\", \"d\"]" output

  bazel cquery "//$pkg:foo" --output=starlark \
    --starlark:file=$pkg/expr.star > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:foo%None%False%None" output

  bazel cquery "kind(rule, deps(//$pkg:foo))" --output=starlark \
    --starlark:file=$pkg/expr.star > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:foo%None%False%None" output
  assert_contains "//$pkg:bar%blah%True%\\[\"a\", \"b\"]" output

  bazel cquery "//$pkg:rules.bzl" --output=starlark \
    --starlark:file=$pkg/expr.star > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:rules.bzl%None" output
}

function test_starlark_build_options_invalid_arg() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  add_rules_python "MODULE.bazel"
  cat > $pkg/BUILD <<'EOF'
load("@rules_python//python:py_library.bzl", "py_library")

py_library(
    name = "foo",
    srcs = ["pylib.py"],
)
EOF

  bazel cquery "//$pkg:foo" --output=starlark \
    --starlark:expr="build_options(False)" > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "Error in build_options: in call to build_options()" "$TEST_log"

  bazel cquery "//$pkg:foo" --output=starlark \
    --starlark:expr="build_options()" > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "build_options() missing 1 required positional argument: target" "$TEST_log"

  bazel cquery "//$pkg:foo" --output=starlark \
    --starlark:expr="build_options(target, 'blah')" > output 2>"$TEST_log" || fail "Expected success"

  assert_contains "build_options() accepts no more than 1 positional argument but got 2" "$TEST_log"
}

function test_starlark_output_both_options() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  touch $pkg/BUILD

  bazel cquery "//$pkg:all" --output=starlark --starlark:expr=a --starlark:file=b \
    >output 2>"$TEST_log" && fail "Expected failure"

  assert_contains ".*You must not specify both --starlark:expr and --starlark:file" \
    "$TEST_log"
}

function test_starlark_output_invalid_expression() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  touch $pkg/BUILD

  bazel cquery "//$pkg:all" --output=starlark \
    --starlark:expr="no_symbol" \
    > output 2>"$TEST_log" && fail "Expected failure"

  assert_contains "invalid --starlark:expr: name 'no_symbol' is not defined" $TEST_log

  bazel cquery "//$pkg:all" --output=starlark \
    --starlark:expr="def foo(): return 5" \
    > output 2>"$TEST_log" && fail "Expected failure"

  assert_contains "syntax error at 'def': expected expression" $TEST_log
}

function test_starlark_output_missing_file() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  touch $pkg/BUILD

  bazel cquery "//$pkg:all" --output=starlark --starlark:file="$pkg/outfunc.bzl" >output \
    2>"$TEST_log" && fail "Expected failure"

  assert_contains "--starlark:file: failed to read $pkg.outfunc.bzl" $TEST_log
}

function test_starlark_output_missing_format() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  touch $pkg/BUILD
  cat > $pkg/outfunc.bzl <<'EOF'
def foo(t):
    return str(t.label) + '%foo_file'
EOF

  bazel cquery "//$pkg:all" --output=starlark --starlark:file="$pkg/outfunc.bzl" >output \
    2>"$TEST_log" && fail "Expected failure"

  assert_contains "invalid --starlark:file:.*does not define 'format'" $TEST_log
}

function test_starlark_output_format_wrong_number_args() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  touch $pkg/BUILD
  cat > $pkg/outfunc.bzl <<'EOF'
def format(t, not_allowed_to_have_a_second_arg):
    return str(t.label) + '%foo_file'
EOF

  bazel cquery "//$pkg:all" --output=starlark --starlark:file="$pkg/outfunc.bzl" >output \
    2>"$TEST_log" && fail "Expected failure"

  assert_contains "invalid --starlark:file:.* must take exactly 1 argument" $TEST_log
}

function test_starlark_output_format_not_function() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  touch $pkg/BUILD
  cat > $pkg/outfunc.bzl <<'EOF'
format = 1
EOF

  bazel cquery "//$pkg:all" --output=starlark --starlark:file="$pkg/outfunc.bzl" >output \
    2>"$TEST_log" && fail "Expected failure"

  assert_contains "invalid --starlark:file:.* for 'format', want function" $TEST_log
}

function test_starlark_output_cc_library_files() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
cc_library(
    name = "cclib",
    srcs = ["mylib.cc"],
)
EOF

  bazel cquery "//$pkg:all" --output=starlark \
    --starlark:expr="' '.join([f.basename for f in target.files.to_list()])" \
    > output 2>"$TEST_log" || fail "Expected failure"

  if "$is_windows"; then
    assert_contains "cclib.lib" output
  elif is_darwin; then
    assert_contains "libcclib.a" output
  else
    assert_contains "libcclib.a" output
    assert_contains "libcclib.so" output
  fi
}

function test_starlark_file_output() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
exports_files(srcs = ["foo"])
EOF

  bazel cquery "//$pkg:foo" --output=starlark \
    --starlark:expr="'path=' + target.files.to_list()[0].path" \
    > output 2>"$TEST_log" || fail "Expected failure"

  assert_contains "^path=$pkg/foo$" output
}

function test_starlark_common_libs() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
exports_files(srcs = ["foo"])
EOF

  bazel cquery "//$pkg:foo" --output=starlark \
    --starlark:expr="str(type(depset())) + ' ' + str(json.encode(struct(foo = 'bar')))" \
    > output 2>"$TEST_log" || fail "Unexpected failure"

  assert_contains "^depset {\"foo\":\"bar\"}$" output
}

function test_starlark_output_providers_function() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/defs.bzl <<'EOF'
def foo_impl(ctx):
    return [DefaultInfo()]

foo_library = rule(
    implementation = foo_impl,
)
EOF
  cat > $pkg/BUILD <<'EOF'
load(":defs.bzl", "foo_library")

foo_library(
    name = "lib",
)
exports_files(["srcfile.txt"])
EOF
  cat > $pkg/srcfile.txt <<'EOF'
hello, world
EOF
  cat > $pkg/outfunc.bzl <<'EOF'
def format(target):
    p = providers(target)
    if not p:
        return str(target.label) + ':no providers'
    ret = str(target.label) + ':providers=' + str(sorted(p.keys()))
    vis_info = p.get('VisibilityProvider')
    if vis_info:
        ret += '\n\tVisibilityProvider.label:' + str(vis_info.label)
    output_group_info = p.get('OutputGroupInfo')
    if output_group_info:
        ret += '\n\tOutputGroupInfo found'
    return ret
EOF
  bazel cquery "//$pkg:lib" --output=starlark --starlark:file="$pkg/outfunc.bzl" >output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "//$pkg:lib:providers=.*OutputGroupInfo" output
  assert_contains "OutputGroupInfo found" output

  # A file
  bazel cquery "//$pkg:srcfile.txt" --output=starlark --starlark:file="$pkg/outfunc.bzl" >output \
    2>"$TEST_log" || fail "Expected success"
  assert_contains "//$pkg:srcfile.txt:providers=.*FileProvider.*FilesToRunProvider.*LicensesProvider.*VisibilityProvider" \
    output
  assert_contains "VisibilityProvider.label:@@\?//$pkg:srcfile.txt" output
}

function test_starlark_output_providers_starlark_provider() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<EOF
load(":my_rule.bzl", "my_rule")
my_rule(name="myrule")
EOF
  cat > $pkg/my_rule.bzl <<'EOF'
# A no-op rule that manifests a provider
MyRuleInfo = provider(fields={"label": "a_rule_label"})

def _my_rule_impl(ctx):
    return [MyRuleInfo(label="some_value")]

my_rule = rule(
    implementation = _my_rule_impl,
    attrs = {},
)
EOF
  cat > $pkg/outfunc.bzl <<EOF
def format(target):
    p = providers(target)
    return p["//$pkg:my_rule.bzl%MyRuleInfo"].label
EOF
  bazel cquery "//$pkg:myrule" --output=starlark --starlark:file="$pkg/outfunc.bzl" >output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "some_value" output
}

function test_starlark_output_providers_starlark_provider_for_alias() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<EOF
load(":my_rule.bzl", "my_rule")
my_rule(name="myrule")
alias(name="myalias", actual="myrule")
EOF
  cat > $pkg/my_rule.bzl <<'EOF'
# A no-op rule that manifests a provider
MyRuleInfo = provider(fields={"label": "a_rule_label"})

def _my_rule_impl(ctx):
    return [MyRuleInfo(label="some_value")]

my_rule = rule(
    implementation = _my_rule_impl,
    attrs = {},
)
EOF
  cat > $pkg/outfunc.bzl <<EOF
def format(target):
    p = providers(target)
    return p["//$pkg:my_rule.bzl%MyRuleInfo"].label
EOF
  bazel cquery "//$pkg:myalias" --output=starlark --starlark:file="$pkg/outfunc.bzl" >output \
    2>"$TEST_log" || fail "Expected success"

  assert_contains "some_value" output
}

function test_bazelignore_error_cquery_nocrash() {
  local -r pkg=$FUNCNAME

  mkdir -p $pkg/repo
  touch $pkg/repo/REPO.bazel
  cat > $pkg/repo/BUILD <<EOF
  toolchain_type(name = "toolchain_type")
EOF

  cat > $pkg/MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "repo", path = "./repo")
EOF
  bazel cquery --output=starlark --starlark:expr 'target' @repo//:toolchain_type >output \
    2>"$TEST_log" && fail "Expected failure"

  # A Bazel crash would have exit codes 3x.
  assert_equals $? 1
}

# Regression test for https://github.com/bazelbuild/bazel/issues/13301
function test_external_repo_scope() {
  if [ "${PRODUCT_NAME}" != "bazel" ]; then
    # Tests of external repositories only work under bazel.
    return 0
  fi

  local -r dir=$FUNCNAME

  mkdir -p $dir/repo
  touch $dir/repo/REPO.bazel
  cat > $dir/repo/BUILD <<EOF
sh_library(name='maple', deps=[':japanese'])
sh_library(name='japanese')
EOF

  mkdir -p $dir/main
  setup_module_dot_bazel $dir/main/MODULE.bazel
  cat > $dir/main/MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "repo", path = "../repo")
EOF
  touch $dir/main/BUILD

  cd $dir/main
  bazel cquery @repo//... &>"$TEST_log" || fail "Unexpected failure"
  expect_not_log "no targets found beneath"
  expect_log "@repo//:maple"
  expect_log "@repo//:japanese"
}

function test_external_repo_scope_with_bazelignore() {
  if [ "${PRODUCT_NAME}" != "bazel" ]; then
    # Tests of external repositories only work under bazel.
    return 0
  fi

  local -r dir=$FUNCNAME

  mkdir -p $dir/repo
  touch $dir/repo/REPO.bazel
  cat > $dir/repo/BUILD <<EOF
sh_library(name='maple', deps=[':japanese'])
sh_library(name='japanese')
EOF

  mkdir -p $dir/main
  setup_module_dot_bazel $dir/main/MODULE.bazel
  cat > $dir/main/MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "repo", path = "../repo")
EOF
  touch $dir/main/BUILD
  echo does_not_exist > $dir/main/.bazelignore

  cd $dir/main
  bazel cquery @repo//... &>"$TEST_log" || fail "Unexpected failure"
  expect_not_log "no targets found beneath"
  expect_log "@repo//:maple"
  expect_log "@repo//:japanese"
}

function test_test_arg_in_bazelrc() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg

  cat >$pkg/BUILD <<EOF
sh_test(
    name = "test",
    srcs = ["test.sh"],
)
EOF

  touch $pkg/test.sh
  chmod +x $pkg/test.sh

  output_before="$(bazel cquery "//$pkg:test")"

  add_to_bazelrc "test --test_arg=foo"

  output_after="$(bazel cquery "//$pkg:test")"

  assert_not_equals "${output_before}" "${output_after}"
}

function set_up_config_test() {
  mkdir -p $pkg

  # Use a rule that has a configuration transition.
  cat > $pkg/rule.bzl <<EOF
def _impl(ctx):
    pass

demo_rule = rule(
    implementation = _impl,
    attrs = {
        "binary": attr.label(cfg = "exec"),
    },
)
EOF

  cat > $pkg/tool.sh <<EOF
echo "Hello"
exit 0
EOF
  chmod +x $pkg/tool.sh

  cat > $pkg/BUILD <<EOF
load("//$pkg:rule.bzl", "demo_rule")

sh_binary(name = "tool", srcs = ["tool.sh"])

demo_rule(
    name = 'demo',
    binary = ":tool",
)
EOF

  # Find out what the specific configurations are.
  bazel cquery "deps(//$pkg:demo)" &>"$TEST_log" || fail "Unexpected failure"

  # Find the target config.
  target_config="$(grep "^//$pkg:demo ([0-9a-f]\+)" $TEST_log | sed -e 's,.*(\([^)]*\)).*,\1,')"
  tool_config="$(grep "^//$pkg:tool ([0-9a-f]\+)" $TEST_log | sed -e 's,.*(\([^)]*\)).*,\1,')"

  assert_not_equals "$target_config" "$tool_config"
}

function test_config_function() {
  local -r pkg=$FUNCNAME

  set_up_config_test $pkg

  # Actually call config, verify output
  bazel cquery "config(//$pkg:demo, $target_config)" &>"$TEST_log" || fail "Unexpected failure"
  expect_log "^//$pkg:demo ($target_config)"
  bazel cquery "config(//$pkg:tool, $tool_config)" &>"$TEST_log" || fail "Unexpected failure"
  expect_log "^//$pkg:tool ($tool_config)"
}

function test_config_function_wrong_order() {
  local -r pkg=$FUNCNAME

  set_up_config_test $pkg

  # Actually call config, verify output
  bazel cquery "config($target_config, //$pkg:demo)" &>"$TEST_log" && fail "Expected to fail"
  expect_not_log "^//$pkg:demo ($target_config)"
  expect_log "couldn't determine target from filename '$target_config'"
}

function test_config_function_invalid_config() {
  local -r pkg=$FUNCNAME

  set_up_config_test $pkg

  # Actually call config, verify output
  bazel cquery "config(//$pkg:demo, notaconfighash)" &>"$TEST_log" && fail "Expected to fail"
  expect_not_log "^//$pkg:demo ($target_config)"
  expect_log "Unknown configuration ID 'notaconfighash'"
}

function test_error_keep_going() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
sh_library(name='maple', deps=[':japanese'])
sh_library(name='japanese')
EOF

  # This causes a failure in the cquery function, which should produce an
  # actionable error, not a stack trace.
  bazel cquery --keep_going "config(//$pkg:oak, notaconfighash)" > output 2>"$TEST_log" && fail "Expected error"
  expect_not_log "QueryException"
}

function test_does_not_fail_horribly_with_file() {
  rm -rf peach
  mkdir -p peach
  cat > peach/BUILD <<EOF
sh_library(name='brighton', deps=[':harken'])
sh_library(name='harken')
EOF

  echo "deps(//peach:brighton)" > query_file
  bazel cquery --query_file=query_file > $TEST_log

  expect_log "//peach:brighton"
  expect_log "//peach:harken"
}

function test_files_include_source_files() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
filegroup(name="files", srcs=["BUILD"])
alias(name="alias", actual="single_file")
EOF
  touch $pkg/single_file

  bazel cquery --output=files //$pkg:all > output 2>"$TEST_log" || fail "Unexpected failure"
  assert_contains "$pkg/BUILD" output
  assert_contains "$pkg/single_file" output
}

# Regression test for b/381071645.
function test_invalid_select_no_crash() {
  local -r pkg=$FUNCNAME
  mkdir -p $pkg
  cat > $pkg/BUILD <<'EOF'
platform(
    name = "platform",
    constraint_values = [],
)

filegroup(
    name = "demo",
    srcs = select({
        # :platform is not valid as a select key.
        ":platform": [],
    }),
)
EOF

  bazel cquery \
    --experimental_use_validation_aspect \
    "//$pkg:demo" &>"$TEST_log" && fail "Expected failure"
  expect_not_log "crashed due to an internal error"
  expect_log "//${pkg}:platform is not a valid select.. condition"
}

run_suite "${PRODUCT_NAME} configured query tests"
