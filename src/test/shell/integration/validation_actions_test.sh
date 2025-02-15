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

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

function setup_test_project() {
  mkdir -p validation_actions

  cat > validation_actions/defs.bzl <<'EOF'
def _rule_with_implicit_outs_and_validation_impl(ctx):

  ctx.actions.write(ctx.outputs.main, "main output\n")

  ctx.actions.write(ctx.outputs.implicit, "implicit output\n")

  validation_output = ctx.actions.declare_file(ctx.attr.name + ".validation")
  # The actual tool will be created in individual tests, depending on whether
  # validation should pass or fail.
  ctx.actions.run(
      outputs = [validation_output],
      executable = ctx.executable._validation_tool,
      arguments = [validation_output.path])

  return [
    DefaultInfo(files = depset([ctx.outputs.main])),
    OutputGroupInfo(_validation = depset([validation_output])),
  ]


rule_with_implicit_outs_and_validation = rule(
  implementation = _rule_with_implicit_outs_and_validation_impl,
  outputs = {
    "main": "%{name}.main",
    "implicit": "%{name}.implicit",
  },
  attrs = {
    "_validation_tool": attr.label(
        allow_single_file = True,
        default = Label("//validation_actions:validation_tool"),
        executable = True,
        cfg = "exec"),
  }
)

def _rule_with_implicit_and_host_deps_impl(ctx):
  return []

rule_with_implicit_and_host_deps = rule(
  implementation = _rule_with_implicit_and_host_deps_impl,
  attrs = {
    "_implicit_dep": attr.label(
        default = Label("//validation_actions:some_implicit_dep")),
    "host_dep": attr.label(
        allow_single_file = True,
        default = Label("//validation_actions:some_host_dep"),
        cfg = "exec"),
  }
)
EOF

  cat > validation_actions/BUILD <<'EOF'

load(
    ":defs.bzl",
    "rule_with_implicit_outs_and_validation",
    "rule_with_implicit_and_host_deps")

rule_with_implicit_outs_and_validation(name = "foo0", visibility = ["//visibility:public"])
rule_with_implicit_outs_and_validation(name = "foo1", visibility = ["//visibility:public"])
rule_with_implicit_outs_and_validation(name = "foo2", visibility = ["//visibility:public"])
rule_with_implicit_outs_and_validation(name = "foo3", visibility = ["//visibility:public"])

filegroup(
  name = "foo2_filegroup",
  srcs = [":foo2"],
)

filegroup(
  name = "foo3_filegroup_implicit",
  srcs = [":foo3.implicit"],
)

# Even when a target, or one of its implicit outputs, is depended upon, its
# validation actions should still be run.
genrule(
  name = "gen",
  srcs = [
    # Dependency on a target
    ":foo0",

    # Dependency on an implicit output
    ":foo1.implicit",

    # Dependency on a filegroup
    ":foo2_filegroup",

    # Dependency on a filegroup which contains an implicit output
    ":foo3_filegroup_implicit",
  ],
  outs = ["generated"],
  cmd = "touch $@",
)


rule_with_implicit_outs_and_validation(name = "some_implicit_dep")
rule_with_implicit_outs_and_validation(name = "some_host_dep")

# this uses the above two rule_with_implicit_outs_and_validation targets
rule_with_implicit_and_host_deps(name = "target_with_implicit_and_host_deps")

rule_with_implicit_outs_and_validation(name = "some_tool_dep")
genrule(
  name = "genrule_with_tool_deps",
  tools = [":some_tool_dep"],
  outs = ["genrule_with_tool_deps_out"],
  cmd = "touch $@",
)

sh_test(
  name = "test_with_rule_with_validation_in_deps",
  srcs = ["test_with_rule_with_validation_in_deps.sh"],
  data = [":some_implicit_dep"],
)

sh_test(
  name = "test_with_same_validation_in_deps",
  srcs = ["test_with_rule_with_validation_in_deps.sh"],
  data = [":some_implicit_dep"],
)

genquery(
  name = "genquery_with_validation_actions_somewhere",
  expression = "deps(//validation_actions:gen)",
  scope = ["//validation_actions:gen"],
)
EOF

cat > validation_actions/test_with_rule_with_validation_in_deps.sh <<'EOF'
exit 0
EOF
chmod +x validation_actions/test_with_rule_with_validation_in_deps.sh

}


function setup_passing_validation_action() {
    cat > validation_actions/validation_tool <<'EOF'
#!/bin/bash
echo "validation output" > $1
EOF
  chmod +x validation_actions/validation_tool
}


function setup_failing_validation_action() {
    cat > validation_actions/validation_tool <<'EOF'
#!/bin/bash
echo "validation failed!"
exit 1
EOF
  chmod +x validation_actions/validation_tool
}

function setup_slow_failing_validation_action() {
    cat > validation_actions/validation_tool <<'EOF'
#!/bin/bash
sleep 10
echo "validation failed!"
exit 1
EOF
  chmod +x validation_actions/validation_tool
}

#### Tests #####################################################################

function test_validation_actions_without_validation_aspect() {
  setup_test_project
  setup_passing_validation_action

  bazel build --run_validations --noexperimental_use_validation_aspect \
      //validation_actions:foo0 >& "$TEST_log" || fail "Expected build to succeed"

  expect_log "Target //validation_actions:foo0 up-to-date:"
  expect_log "validation_actions/foo0.main"
  assert_exists bazel-bin/validation_actions/foo0.validation
}

function test_validation_actions() {
  setup_test_project
  setup_passing_validation_action

  bazel build --run_validations \
      //validation_actions:foo0 >& "$TEST_log" || fail "Expected build to succeed"

  # Console printout as if no aspects were running
  expect_log "Target //validation_actions:foo0 up-to-date:"
  expect_log "validation_actions/foo0.main"
  expect_not_log "ValidateTarget"
  assert_exists bazel-bin/validation_actions/foo0.validation
}

function test_validation_actions_with_validation_and_another_aspect() {
  setup_test_project
  setup_passing_validation_action

  cat > validation_actions/simpleaspect.bzl <<'EOF'
def _simple_aspect_impl(target, ctx):
    aspect_out = ctx.actions.declare_file(ctx.label.name + ".aspect")
    ctx.actions.write(
        output=aspect_out,
        content = "Hello from aspect")
    return [OutputGroupInfo(aspect_out=depset([aspect_out]))]

simple_aspect = aspect(implementation=_simple_aspect_impl)
EOF

  bazel build --run_validations \
      --show_result=2 \
      --aspects=validation_actions/simpleaspect.bzl%simple_aspect \
      --output_groups=+aspect_out \
      //validation_actions:foo0 >& "$TEST_log" || fail "Expected build to succeed"

  expect_log "Target //validation_actions:foo0 up-to-date:"
  expect_log "validation_actions/foo0.main"
  # Console printout includes other aspect but not validation aspect
  expect_log "Aspect //validation_actions:simpleaspect.bzl%simple_aspect of //validation_actions:foo0 up-to-date:"
  expect_log "validation_actions/foo0.aspect"
  expect_not_log "ValidateTarget"
  assert_exists bazel-bin/validation_actions/foo0.validation
}

function test_validation_actions_implicit_output() {
  setup_test_project
  setup_passing_validation_action

  # Requesting an implicit output
  bazel build --run_validations \
      //validation_actions:foo0.implicit >& "$TEST_log" || fail "Expected build to succeed"

  assert_exists bazel-bin/validation_actions/foo0.validation
}

function test_validation_actions_through_deps() {
  setup_test_project
  setup_passing_validation_action

  bazel build --run_validations \
      //validation_actions:gen >& "$TEST_log" || fail "Expected build to succeed"

  assert_exists bazel-bin/validation_actions/foo0.validation
  assert_exists bazel-bin/validation_actions/foo1.validation
  assert_exists bazel-bin/validation_actions/foo2.validation
  assert_exists bazel-bin/validation_actions/foo3.validation
}

function test_failing_validation_action_fails_build() {
  setup_test_project
  setup_failing_validation_action

  bazel build --run_validations \
      //validation_actions:gen >& "$TEST_log" && fail "Expected build to fail"
  expect_log "validation failed!"
  expect_log "Target //validation_actions:gen failed to build"
}

function test_failing_validation_action_fails_build_manual_validation_aspect() {
  setup_test_project
  setup_failing_validation_action

  bazel build --run_validations --aspects=ValidateTarget \
      //validation_actions:gen >& "$TEST_log" && fail "Expected build to fail"
  expect_log "validation failed!"
}

function test_failing_validation_action_fails_build_implicit_output() {
  setup_test_project
  setup_failing_validation_action

  bazel clean
  bazel build --run_validations \
      //validation_actions:foo0.implicit >& "$TEST_log" && fail "Expected build to fail"
  expect_log "validation failed!"
  expect_log "Target //validation_actions:foo0.implicit failed to build"
}

function test_failing_validation_action_fails_build_genrule_output() {
  setup_test_project
  setup_failing_validation_action

  bazel build --run_validations \
      --aspects=ValidateTarget \
      //validation_actions:generated >& "$TEST_log" && fail "Expected build to fail"
  expect_log "validation failed!"
  expect_log "Target //validation_actions:generated failed to build"
}

function test_failing_validation_action_with_validations_off_does_not_fail_build() {
  setup_test_project
  setup_failing_validation_action

  bazel build --run_validations=false //validation_actions:gen >& "$TEST_log" || fail "Expected build to succeed"
  expect_not_log "validation failed!"
}

function test_failing_validation_action_for_host_dep_does_not_fail_build() {
  setup_test_project
  setup_failing_validation_action

  # Validation actions in the host configuration or from implicit deps should
  # not fail the overall build, since those dependencies should have their own
  # builds and tests that should surface any failing validations.
  bazel build --run_validations //validation_actions:target_with_implicit_and_host_deps >& "$TEST_log" || fail "Expected build to succeed"
  expect_not_log "validation failed!"
}

function test_failing_validation_action_for_tool_dep_does_not_fail_build() {
  setup_test_project
  setup_failing_validation_action

  # Validation actions in the exec configuration or from implicit deps should
  # not fail the overall build, since those dependencies should have their own
  # builds and tests that should surface any failing validations.
  bazel build --run_validations //validation_actions:genrule_with_tool_deps >& "$TEST_log" || fail "Expected build to succeed"
  expect_not_log "validation failed!"
}

function test_failing_validation_action_for_dep_from_test_fails_build() {
  setup_test_project
  setup_failing_validation_action

  # Validation actions in the deps of the test should fail the build when
  # run with bazel test.
  bazel test --run_validations //validation_actions:test_with_rule_with_validation_in_deps >& "$TEST_log" && fail "Expected build to fail"
  expect_log "validation failed!"
  expect_log "NO STATUS"
  expect_log "out of 1 test: 1 was skipped."
}

function test_slow_failing_validation_action_for_dep_from_test_fails_build() {
  setup_test_project
  setup_slow_failing_validation_action

  # Validation actions in the deps of the test should fail the build when run
  # with "bazel test", even though validation finishes after test
  bazel clean  # Clean out any previous test or validation outputs
  bazel test --run_validations \
      //validation_actions:test_with_rule_with_validation_in_deps >& "$TEST_log" \
      && fail "Expected build to fail"
  expect_log "validation failed!"
  expect_log "Target //validation_actions:test_with_rule_with_validation_in_deps failed to build"
  # --experimental_use_validation_aspect reports all affected tests NO STATUS
  # for simplicity, while one test is randomly reports FAILED TO BUILD without
  # that flag (absent -k).
  expect_log "NO STATUS"
  expect_log "out of 1 test: 1 was skipped."
}

function test_failing_validation_action_fails_multiple_tests() {
  setup_test_project
  setup_failing_validation_action

  # Validation actions in the deps of the test should fail the build when run
  # with bazel test.
  bazel test --run_validations \
      --noexperimental_use_validation_aspect \
      //validation_actions:test_with_rule_with_validation_in_deps \
      //validation_actions:test_with_same_validation_in_deps >& "$TEST_log" \
      && fail "Expected build to fail"
  expect_log "validation failed!"
  # One test is (randomly) marked as FAILED_TO_BUILD
  expect_log_once "FAILED TO BUILD"
  expect_log "out of 2 tests: 1 fails to build and 1 was skipped."
}

function test_slow_failing_validation_action_fails_multiple_tests() {
  setup_test_project
  setup_slow_failing_validation_action

  # Validation actions in the deps of the test should fail the build when run
  # with "bazel test", even though validation finishes after test
  bazel clean  # Clean out any previous test or validation outputs
  bazel test --run_validations \
      //validation_actions:test_with_rule_with_validation_in_deps \
      //validation_actions:test_with_same_validation_in_deps >& "$TEST_log" \
      && fail "Expected build to fail"
  expect_log "validation failed!"
  # --experimental_use_validation_aspect reports all affected tests NO STATUS
  # for simplicity, while one test is randomly reports FAILED TO BUILD without
  # that flag (absent -k).
  expect_log_n "NO STATUS" 2
  expect_log "out of 2 tests: 2 were skipped."
}

function test_slow_failing_validation_action_fails_multiple_tests_keep_going() {
  setup_test_project
  setup_slow_failing_validation_action

  # Validation actions in the deps of the test should fail the build when run
  # with "bazel test", even though validation finishes after test
  bazel clean  # Clean out any previous test or validation outputs
  bazel test -k --run_validations \
      //validation_actions:test_with_rule_with_validation_in_deps \
      //validation_actions:test_with_same_validation_in_deps >& "$TEST_log" \
      && fail "Expected build to fail"
  expect_log "validation failed!"
  expect_log_n "FAILED TO BUILD" 2
  expect_not_log "NO STATUS"
  expect_log "out of 2 tests: 2 fail to build."
}

function test_validation_actions_do_not_propagate_through_genquery() {
  setup_test_project
  setup_failing_validation_action

  # Validation action is set up to fail, but it shouldn't matter because it
  # shouldn't get propagated through the genquery target.
  bazel build --run_validations //validation_actions:genquery_with_validation_actions_somewhere >& "$TEST_log" || fail "Expected build to succeed"
  expect_not_log "validation failed!"
}

function test_validation_actions_flags() {
  setup_test_project
  setup_passing_validation_action

  bazel build --run_validations \
      //validation_actions:foo0 >& "$TEST_log" || fail "Expected build to succeed"

  expect_log "Target //validation_actions:foo0 up-to-date:"
  expect_log "validation_actions/foo0.main"
  assert_exists bazel-bin/validation_actions/foo0.validation
  rm -f bazel-bin/validation_actions/foo0.validation

  bazel build --run_validations \
      //validation_actions:foo0 >& "$TEST_log" || fail "Expected build to succeed"

  expect_log "Target //validation_actions:foo0 up-to-date:"
  expect_log "validation_actions/foo0.main"
  assert_exists bazel-bin/validation_actions/foo0.validation
  rm -f bazel-bin/validation_actions/foo0.validation

  setup_failing_validation_action

  bazel build --norun_validations \
      //validation_actions:foo0 >& "$TEST_log" || fail "Expected build to succeed"
  expect_log "Target //validation_actions:foo0 up-to-date:"

  bazel build --norun_validations \
      //validation_actions:foo0 >& "$TEST_log" || fail "Expected build to succeed"
  expect_log "Target //validation_actions:foo0 up-to-date:"
}

function setup_validation_tool_aspect() {
  mkdir -p aspect
  cat > aspect/BUILD <<'EOF'
exports_files(["aspect_validation_tool"])
EOF
  cat > aspect/def.bzl <<'EOF'
def _validation_aspect_impl(target, ctx):
  validation_output = ctx.actions.declare_file(ctx.rule.attr.name + ".aspect_validation")
  ctx.actions.run(
      outputs = [validation_output],
      executable = ctx.executable._validation_tool,
      arguments = [validation_output.path])
  return [
    OutputGroupInfo(_validation = depset([validation_output])),
  ]

validation_aspect = aspect(
  implementation = _validation_aspect_impl,
  attrs = {
    "_validation_tool": attr.label(
        allow_single_file = True,
        default = Label(":aspect_validation_tool"),
        executable = True,
        cfg = "exec"),
  },
)
EOF
  cat > aspect/aspect_validation_tool <<'EOF'
#!/bin/bash
echo "aspect validation output" > $1
EOF
  chmod +x aspect/aspect_validation_tool
}

function test_validation_actions_in_rule_and_aspect_no_use_validation_aspect() {
  setup_test_project
  setup_validation_tool_aspect
  setup_passing_validation_action

  bazel build --run_validations --aspects=//aspect:def.bzl%validation_aspect \
      --noexperimental_use_validation_aspect \
      //validation_actions:foo0 >& "$TEST_log" || fail "Expected build to succeed"
  expect_log "Target //validation_actions:foo0 up-to-date:"
  expect_log "validation_actions/foo0.main"
  assert_exists bazel-bin/validation_actions/foo0.validation
  assert_exists bazel-bin/validation_actions/foo0.aspect_validation

  cat > aspect/aspect_validation_tool <<'EOF'
#!/bin/bash
echo "aspect validation failed!"
exit 1
EOF

  bazel build --run_validations --aspects=//aspect:def.bzl%validation_aspect \
      --noexperimental_use_validation_aspect \
      //validation_actions:foo0 >& "$TEST_log" && fail "Expected build to fail"
  expect_log "aspect validation failed!"
}

function test_validation_actions_in_rule_and_aspect_use_validation_aspect() {
  setup_test_project
  setup_validation_tool_aspect
  setup_passing_validation_action

  bazel build --run_validations --aspects=//aspect:def.bzl%validation_aspect \
      //validation_actions:foo0 >& "$TEST_log" || fail "Expected build to succeed"
  expect_log "Target //validation_actions:foo0 up-to-date:"
  expect_log "validation_actions/foo0.main"
  assert_exists bazel-bin/validation_actions/foo0.validation
  assert_exists bazel-bin/validation_actions/foo0.aspect_validation

  cat > aspect/aspect_validation_tool <<'EOF'
#!/bin/bash
echo "aspect validation failed!"
exit 1
EOF

  bazel build --run_validations --aspects=//aspect:def.bzl%validation_aspect \
      //validation_actions:foo0 >& "$TEST_log" && fail "Expected build to fail"
  expect_log "aspect validation failed!"
}

function test_skip_validation_action_fails_without_allowlist() {
  setup_test_project
  setup_passing_validation_action
  mkdir -p skip_validations
  cat > skip_validations/defs.bzl <<'EOF'
def _rule_with_attrs_that_skips_validation_impl(ctx):
    return []

rule_with_attrs_that_skips_validation = rule(
    implementation = _rule_with_attrs_that_skips_validation_impl,
    attrs = {
        "skip_validations_label": attr.label(skip_validations = True),
        "skip_validations_label_list": attr.label_list(skip_validations = True),
    },
)
EOF
  cat > skip_validations/BUILD <<'EOF'
load( ":defs.bzl", "rule_with_attrs_that_skips_validation" )

rule_with_attrs_that_skips_validation(
    name = "target_with_attr_that_skips_validation",
    skip_validations_label = "//validation_actions:foo0",
    skip_validations_label_list =  ["//validation_actions:foo1"],
)
EOF

  mkdir -p tools/allowlists/skip_validations_allowlist
  cat > tools/allowlists/skip_validations_allowlist/BUILD <<'EOF'
package_group(
    name = "skip_validations_allowlist",
    packages = [],
)
EOF

  bazel build //skip_validations:target_with_attr_that_skips_validation \
    >& $TEST_log && fail "Expected build to fail without allowlist"
  expect_log "Non-allowlisted use of skip_validations"

  cat > tools/allowlists/skip_validations_allowlist/BUILD <<'EOF'
package_group(
    name = "skip_validations_allowlist",
    packages = ["//skip_validations/..."],
)
EOF

  bazel build //skip_validations:target_with_attr_that_skips_validation || \
    fail "Expected build to succeed"
}

function test_skip_validation_action_with_attribute_flag() {
  setup_test_project
  setup_passing_validation_action
  mkdir -p skip_validations
  cat > skip_validations/defs.bzl <<'EOF'
def _rule_with_attrs_that_skips_validation_impl(ctx):
    return []

rule_with_attrs_that_skips_validation = rule(
    implementation = _rule_with_attrs_that_skips_validation_impl,
    attrs = {
        "run_validations_label": attr.label(),
        "run_validations_label_list": attr.label_list(),
        "skip_validations_label": attr.label(skip_validations = True),
        "skip_validations_label_list": attr.label_list(skip_validations = True),
    },
)
EOF
  cat > skip_validations/BUILD <<'EOF'
load( ":defs.bzl", "rule_with_attrs_that_skips_validation" )

rule_with_attrs_that_skips_validation(
    name = "target_with_attr_that_skips_validation",
    run_validations_label = "//validation_actions:foo0",
    run_validations_label_list = ["//validation_actions:foo1"],
    skip_validations_label = "//validation_actions:foo2",
    skip_validations_label_list =  ["//validation_actions:foo3"],
)
EOF

  mkdir -p tools/allowlists/skip_validations_allowlist
  cat > tools/allowlists/skip_validations_allowlist/BUILD <<'EOF'
package_group(
    name = "skip_validations_allowlist",
    packages = ["//skip_validations/..."],
)
EOF

  bazel build //skip_validations:target_with_attr_that_skips_validation \
    >& $TEST_log || fail "Expected build to succeed"

  assert_exists bazel-bin/validation_actions/foo0.validation
  assert_exists bazel-bin/validation_actions/foo1.validation
  assert_not_exists bazel-bin/validation_actions/foo2.validation
  assert_not_exists bazel-bin/validation_actions/foo3.validation
}

run_suite "Validation actions integration tests"
