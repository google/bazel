#!/bin/bash
#
# Copyright 2024 The Bazel Authors. All rights reserved.
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
# An end-to-end test for flagsets.

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

function set_up_flags() {
  local -r pkg="$1"

  # Define common starlark flags.
  #mkdir -p "${pkg}"
  cat > "${pkg}"/flag.bzl <<EOF
BuildSettingInfo = provider(fields = ["value"])

def _sample_flag_impl(ctx):
    print ("Flag value for %s: %s" % (ctx.label.name, ctx.build_setting_value))
    return BuildSettingInfo(value = ctx.build_setting_value)

sample_flag = rule(
    implementation = _sample_flag_impl,
    build_setting = config.string(flag = True),
    attrs = {
      "scope": attr.string(default = "universal"),
    }
)
EOF
}

function create_transitions() {
  local pkg="${1}"
  local project_flag1_location="${2}"
  echo "project_flag1_location: ${project_flag1_location}"
  local project_flag2_location="${3}"
  echo "project_flag2_location: ${project_flag2_location}"
  local universal_flag_location="${4}"
  echo "universal_flag_location: ${universal_flag_location}"

  cat > "${pkg}/def.bzl" <<EOF
project_flag1_location = "${project_flag1_location}"
project_flag2_location = "${project_flag2_location}"
universal_flag_location = "${universal_flag_location}"

def _transition_impl(settings, attr):
    return {
      "//%s:project_flag" % project_flag1_location: "changed_value_project_flag1",
      "//%s:project_flag" % project_flag2_location: "changed_value_project_flag2",
      "//%s:universal_flag" % universal_flag_location: "changed_value_universal_flag"
    }

example_transition = transition(
    implementation = _transition_impl,
    inputs = [],
    outputs = [
      "//%s:project_flag" % project_flag1_location,
      "//%s:project_flag" % project_flag2_location,
      "//%s:universal_flag" % universal_flag_location
    ]
)

def _out_of_scope_transition_impl(settings, attr):
    return {
      "//out_of_scope:project_flag_baseline" : "baseline"
    }

out_of_scope_transition = transition(
    implementation = _out_of_scope_transition_impl,
    inputs = [],
    outputs = [
      "//out_of_scope:project_flag_baseline"
    ]
)

def _rule_impl(ctx):
    print("dummy")

transition_attached = rule(
    implementation = _rule_impl,
    cfg = example_transition,
)

def _aspect_impl(target, ctx):
    print("dummy")
    return []

simple_aspect = aspect(
    implementation = _aspect_impl,
    attr_aspects = [],
)

transition_on_dep = rule(
    implementation = _rule_impl,
    attrs = {
        "dep": attr.label(aspects = [simple_aspect], cfg = out_of_scope_transition),
    },
)

EOF
}

function test_flags_scoping_remove_out_of_scope_flags() {
  echo "testing test_flags_scoped_properly"
  local -r pkg="$FUNCNAME"
  local -r in_scope_flags_pkg="in_scope_flags"
  local -r out_of_scope_flags_pkg="out_of_scope_flags"
  local -r universal_flags_pkg="universal_flags"

  mkdir -p "${pkg}"
  mkdir -p "${in_scope_flags_pkg}"
  mkdir -p "${out_of_scope_flags_pkg}"
  mkdir -p "${universal_flags_pkg}"

  set_up_flags "${pkg}" "${in_scope_flags_pkg}" "${out_of_scope_flags_pkg}" "${universal_flags_pkg}"

# define flags that should be applied to the project
  cat > "${in_scope_flags_pkg}/BUILD" <<EOF
load("//${pkg}:flag.bzl", "sample_flag")

sample_flag(
    name = "project_flag",
    scope = "project",
    build_setting_default = "foo",
)
EOF

# define flags that are put of scope for the project
  #mkdir -p "${out_of_scope_flags_pkg}"
  cat > "${out_of_scope_flags_pkg}/BUILD" <<EOF
load("//${pkg}:flag.bzl", "sample_flag")

sample_flag(
    name = "project_flag",
    scope = "project",
    build_setting_default = "foo",
)

sample_flag(
    name = "project_flag_baseline",
    scope = "project",
    build_setting_default = "foo",
)
EOF

# define universal flag that should propagate everywhere
  #mkdir -p "${universal_flags_pkg}"
  cat > "${universal_flags_pkg}/BUILD" <<EOF
load("//${pkg}:flag.bzl", "sample_flag")

sample_flag(
    name = "universal_flag",
    build_setting_default = "foo",
)
EOF

  cat > "${in_scope_flags_pkg}/PROJECT.scl" <<EOF
project = {
  "active_directories": { "default": [ "//${pkg}"] }
}
EOF

  cat > "${out_of_scope_flags_pkg}/PROJECT.scl" <<EOF
project = {
  "active_directories": { "default": [ "//${out_of_scope_flags_pkg}"] }
}
EOF

# create transitions with project and universal flags and rules that attach transitions
  create_transitions "${pkg}" "${in_scope_flags_pkg}" "${out_of_scope_flags_pkg}" "${universal_flags_pkg}"

# targets belonging to the project
  cat > "${pkg}/BUILD" <<EOF
load("//${pkg}:def.bzl", "transition_attached")
transition_attached(
    name = "project_target",
)
EOF
  #echo "debugging under the influence of nyquil...wooo"
  bazel build //${pkg}:project_target --experimental_enable_scl_dialect --//${out_of_scope_flags_pkg}:project_flag_baseline=baseline || fail "bazel failed"

  for config in $(bazel config | tail -n +2 | cut -d ' ' -f 1); do
    bazel config "${config}" >> $TEST_log
  done

  cat $TEST_log

  expect_log "//in_scope_flags:project_flag: changed_value_project_flag1"
  expect_log "//universal_flags:universal_flag: changed_value_universal_flag"
  expect_log "//out_of_scope_flags:project_flag_baseline: baseline"
  expect_not_log "//out_of_scope_flags:project_flag: changed_value_project_flag2"
}

function test_flags_scoping_works_with_aspect_on_alias_chain() {
  echo "testing test_flags_scoping_works_with_aspect_on_alias_chain"
  local -r pkg="$FUNCNAME"
  local -r in_scope_pkg="in_scope"
  local -r out_of_scope_pkg="out_of_scope"
  local -r universal_pkg="universal"

  mkdir -p "${pkg}"
  mkdir -p "${in_scope_pkg}"
  mkdir -p "${out_of_scope_pkg}"
  mkdir -p "${universal_pkg}"

  set_up_flags "${pkg}" "${in_scope_pkg}" "${out_of_scope_pkg}" "${universal_pkg}"
  create_transitions "${pkg}" "${in_scope_pkg}" "${out_of_scope_pkg}" "${universal_pkg}"

# set up target that is in scope for the project
  cat > "${pkg}/BUILD" <<EOF
load("//${pkg}:def.bzl", "transition_on_dep")

transition_on_dep(
    name = "main",
    dep = ":in_scope_alias",
)

alias(
    name = "in_scope_alias",
    actual = "//${out_of_scope_pkg}:out_of_scope_alias",
)
EOF

  cat > "${out_of_scope_pkg}/PROJECT.scl" <<EOF
project = {
  "active_directories": { "default": [ "//${pkg}"] }
}
EOF

  # set up package with out of scope alias chain and actual target
  cat > "${out_of_scope_pkg}/BUILD" <<EOF
load("//${pkg}:def.bzl", "transition_on_dep")
load("//${pkg}:flag.bzl", "sample_flag")

alias(
    name = "out_of_scope_alias",
    actual = ":actual",
    visibility = ["//visibility:public"],
)

transition_on_dep(
    name = "actual",
)

sample_flag(
    name = "project_flag_baseline",
    scope = "project",
    build_setting_default = "foo",
)

EOF

  bazel build //${pkg}:main --experimental_enable_scl_dialect || fail "bazel failed"
}

run_suite "Integration tests for flags scoping"
