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
# Test the providers and rules related to toolchains.
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

function set_up() {
  create_new_workspace
  # Clean out the MODULE.bazel file.
  rm -f $TOOLCHAIN_REGISTRATION_FILE
  setup_module_dot_bazel "MODULE.bazel"

  # Create shared report rule for printing toolchain info.
  mkdir -p report
  touch report/BUILD
  cat > report/report.bzl <<EOF
def _report_impl(ctx):
  toolchain = ctx.attr.toolchain[platform_common.ToolchainInfo]
  for field in ctx.attr.fields:
    value = getattr(toolchain, field)
    if type(value) == 'Target':
      value = value.label
    print('%s = "%s"' % (field, value))

report_toolchain = rule(
    implementation = _report_impl,
    attrs = {
        'fields': attr.string_list(),
        'toolchain': attr.label(providers = [platform_common.ToolchainInfo]),
    }
)
EOF
}

function write_test_toolchain() {
  local pkg="${1}"
  local toolchain_name="${2:-test_toolchain}"

  mkdir -p "${pkg}/toolchain"
  cat >> "${pkg}/toolchain/toolchain_${toolchain_name}.bzl" <<EOF
def _impl(ctx):
  toolchain = platform_common.ToolchainInfo(
      extra_label = ctx.attr.extra_label,
      extra_str = ctx.attr.extra_str)
  return [toolchain]

${toolchain_name} = rule(
    implementation = _impl,
    attrs = {
        'extra_label': attr.label(),
        'extra_str': attr.string(),
    }
)
EOF

  if [[ ! -e "${pkg}/toolchain/BUILD" ]]; then
    cat > "${pkg}/toolchain/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])
EOF
  fi

  cat >> "${pkg}/toolchain/BUILD" <<EOF
toolchain_type(name = '${toolchain_name}')
EOF
}

function write_test_rule() {
  local pkg="${1}"
  local rule_name="${2:-use_toolchain}"
  local toolchain_name="${3:-test_toolchain}"

  mkdir -p "${pkg}/toolchain"
  cat >> "${pkg}/toolchain/rule_${rule_name}.bzl" <<EOF
def _impl(ctx):
  if '//${pkg}/toolchain:${toolchain_name}' not in ctx.toolchains:
    fail('Toolchain type //${pkg}/toolchain:${toolchain_name} not found')
  toolchain = ctx.toolchains['//${pkg}/toolchain:${toolchain_name}']
  message = ctx.attr.message
  print(
      'Using toolchain: rule message: "%s", toolchain extra_str: "%s"' %
         (message, toolchain.extra_str))
  return []

${rule_name} = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = ['//${pkg}/toolchain:${toolchain_name}'],
)
EOF
}

function write_test_aspect() {
  local pkg="${1}"
  local aspect_name="${2:-use_toolchain}"
  local toolchain_name="${3:-test_toolchain}"

  mkdir -p "${pkg}/toolchain"
  cat >> "${pkg}/toolchain/aspect_${aspect_name}.bzl" <<EOF
def _impl(target, ctx):
  toolchain = ctx.toolchains['//${pkg}/toolchain:${toolchain_name}']
  message = ctx.rule.attr.message
  print(
      'Using toolchain in aspect: rule message: "%s", toolchain extra_str: "%s"' %
          (message, toolchain.extra_str))
  return []

${aspect_name} = aspect(
    implementation = _impl,
    attrs = {},
    toolchains = ['//${pkg}/toolchain:${toolchain_name}'],
    apply_to_generating_rules = True,
)
EOF
}

function write_register_toolchain() {
  local pkg="${1}"
  local toolchain_name="${2:-test_toolchain}"
  local exec_compatible_with="${3:-"[]"}"
  local target_compatible_with="${4:-"[]"}"

  cat >> $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//register/${pkg}:${toolchain_name}_1')
EOF

  mkdir -p "register/${pkg}"

  if [[ ! -e "register/${pkg}/BUILD" ]]; then
    cat > "register/${pkg}/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])
EOF
  fi
  cat >> "register/${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_${toolchain_name}.bzl', '${toolchain_name}')

# Define the toolchain.
filegroup(name = 'dep_rule_${toolchain_name}')
${toolchain_name}(
    name = '${toolchain_name}_impl_1',
    extra_label = ':dep_rule_${toolchain_name}',
    extra_str = 'foo from ${toolchain_name}',
)

# Declare the toolchain.
toolchain(
    name = '${toolchain_name}_1',
    toolchain_type = '//${pkg}/toolchain:${toolchain_name}',
    exec_compatible_with = ${exec_compatible_with},
    target_compatible_with = ${target_compatible_with},
    toolchain = ':${toolchain_name}_impl_1',
)
EOF
}

function test_toolchain_provider() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"

  mkdir -p "${pkg}"
  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')
load('//report:report.bzl', 'report_toolchain')

package(default_visibility = ["//visibility:public"])

filegroup(name = 'dep_rule')
test_toolchain(
    name = 'linux_toolchain',
    extra_label = ':dep_rule',
    extra_str = 'bar',
)
report_toolchain(
  name = 'report',
  fields = ['extra_label', 'extra_str'],
  toolchain = ':linux_toolchain',
)
EOF

  bazel build "//${pkg}:report" &> $TEST_log || fail "Build failed"
  expect_log "extra_label = \"@@\?//${pkg}:dep_rule\""
  expect_log 'extra_str = "bar"'
}

function test_toolchain_use_in_rule {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"
  write_register_toolchain "${pkg}"

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')
package(default_visibility = ["//visibility:public"])
# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from test_toolchain"'
}

function test_toolchain_alias_use_in_rule {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  mkdir -p "${pkg}"
  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])

# Define the toolchain.
filegroup(name = 'dep_rule_test_toolchain')
test_toolchain(
    name = 'test_toolchain_impl_1',
    extra_label = ':dep_rule_test_toolchain',
    extra_str = 'foo from test_toolchain',
)
alias(
    name = 'test_toolchain_impl_1_alias',
    actual = ':test_toolchain_impl_1',
)

# Declare the toolchain.
toolchain(
    name = 'test_toolchain_1',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    exec_compatible_with = [],
    target_compatible_with = [],
    toolchain = ':test_toolchain_impl_1_alias',
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build \
    "--extra_toolchains=//${pkg}:test_toolchain_1" \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from test_toolchain"'
}

function test_toolchain_alias_chain_use_in_rule {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  mkdir -p "${pkg}"
  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])

# Define the toolchain.
filegroup(name = 'dep_rule_test_toolchain')
test_toolchain(
    name = 'test_toolchain_impl_1',
    extra_label = ':dep_rule_test_toolchain',
    extra_str = 'foo from test_toolchain',
)
alias(
    name = 'test_toolchain_impl_1_alias_alpha',
    actual = ':test_toolchain_impl_1',
)
alias(
    name = 'test_toolchain_impl_1_alias_beta',
    actual = ':test_toolchain_impl_1_alias_alpha',
)

# Declare the toolchain.
toolchain(
    name = 'test_toolchain_1',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    exec_compatible_with = [],
    target_compatible_with = [],
    toolchain = ':test_toolchain_impl_1_alias_beta',
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build \
    "--extra_toolchains=//${pkg}:test_toolchain_1" \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from test_toolchain"'
}

function test_toolchain_type_alias_use_in_toolchain {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  # Create an alias for the toolchain type.
  mkdir -p "${pkg}/alias"
  cat > "${pkg}/alias/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

alias(
    name = 'toolchain_type',
    actual = '//${pkg}/toolchain:test_toolchain',
)
EOF

  # Use the alias.
  mkdir -p "${pkg}"
  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])

# Define the toolchain.
filegroup(name = 'dep_rule_test_toolchain')
test_toolchain(
    name = 'test_toolchain_impl_1',
    extra_label = ':dep_rule_test_toolchain',
    extra_str = 'foo from test_toolchain',
)

# Declare the toolchain.
toolchain(
    name = 'test_toolchain_1',
    toolchain_type = '//${pkg}/alias:toolchain_type',
    exec_compatible_with = [],
    target_compatible_with = [],
    toolchain = ':test_toolchain_impl_1',
)
EOF

  # The rule uses the original, non-aliased type.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build \
    "--extra_toolchains=//${pkg}:test_toolchain_1" \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from test_toolchain"'
}

function test_toolchain_type_alias_use_in_rule {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_register_toolchain "${pkg}"

  # Create an alias for the toolchain type.
  mkdir -p "${pkg}/alias"
  cat > "${pkg}/alias/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

alias(
    name = 'toolchain_type',
    actual = '//${pkg}/toolchain:test_toolchain',
)
EOF

  # Use the alias in a rule.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/aliased_rule.bzl" <<EOF
def _impl(ctx):
  toolchain = ctx.toolchains['//${pkg}/alias:toolchain_type']
  message = ctx.attr.message
  print(
      'Using toolchain: rule message: "%s", toolchain extra_str: "%s"' %
         (message, toolchain.extra_str))
  return []

aliased_rule = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = ['//${pkg}/alias:toolchain_type'],
)
EOF

  cat > "${pkg}/demo/BUILD" <<EOF
load(':aliased_rule.bzl', 'aliased_rule')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
aliased_rule(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from test_toolchain"'
}

function test_toolchain_use_in_rule_missing {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"
  #rite_register_toolchain
  # Do not register test_toolchain to trigger the error.

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "While resolving toolchains for target //${pkg}/demo:use[^:]*: No matching toolchains found for types:$"
  expect_log "  //${pkg}/toolchain:test_toolchain$"
}

function test_toolchain_use_in_rule_missing_with_custom_error {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}" test_toolchain_with_message
  cat > "${pkg}/toolchain/BUILD" <<EOF
toolchain_type(
    name = 'test_toolchain_with_message',
    no_match_error = 'Go register a toolchain!',
)
EOF
  write_test_rule "${pkg}" use_toolchain test_toolchain_with_message
  # Do not register test_toolchain_with_message to trigger the error.

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "While resolving toolchains for target //${pkg}/demo:use[^:]*: No matching toolchains found for types:$"
  expect_log "^  //${pkg}/toolchain:test_toolchain_with_message: Go register a toolchain!$"
}

function test_multiple_toolchain_use_in_rule {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}" test_toolchain_1
  write_test_toolchain "${pkg}" test_toolchain_2

  write_register_toolchain "${pkg}" test_toolchain_1
  write_register_toolchain "${pkg}" test_toolchain_2

  # The rule uses two separate toolchains.
  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/rule_use_toolchains.bzl" <<EOF
def _impl(ctx):
  toolchain_1 = ctx.toolchains['//${pkg}/toolchain:test_toolchain_1']
  toolchain_2 = ctx.toolchains['//${pkg}/toolchain:test_toolchain_2']
  message = ctx.attr.message
  print(
      'Using toolchain: rule message: "%s", toolchain 1 extra_str: "%s", toolchain 2 extra_str: "%s"' %
         (message, toolchain_1.extra_str, toolchain_2.extra_str))
  return []

use_toolchains = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = [
        '//${pkg}/toolchain:test_toolchain_1',
        '//${pkg}/toolchain:test_toolchain_2',
    ],
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchains.bzl', 'use_toolchains')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchains(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain 1 extra_str: "foo from test_toolchain_1", toolchain 2 extra_str: "foo from test_toolchain_2"'
}

function test_multiple_toolchain_use_in_rule_with_optional_missing {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}" test_toolchain_1
  write_test_toolchain "${pkg}" test_toolchain_2

  write_register_toolchain "${pkg}" test_toolchain_1

  # The rule uses two separate toolchains.
  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/rule_use_toolchains.bzl" <<EOF
def _impl(ctx):
  toolchain_1 = ctx.toolchains['//${pkg}/toolchain:test_toolchain_1']
  toolchain_2 = ctx.toolchains['//${pkg}/toolchain:test_toolchain_2']
  message = ctx.attr.message
  print(
      'Using toolchain: rule message: "%s", toolchain 1 extra_str: "%s", toolchain 2 is none: %s' %
         (message, toolchain_1.extra_str, toolchain_2 == None))
  return []

use_toolchains = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = [
        '//${pkg}/toolchain:test_toolchain_1',
        config_common.toolchain_type('//${pkg}/toolchain:test_toolchain_2', mandatory = False),
    ],
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchains.bzl', 'use_toolchains')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchains(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain 1 extra_str: "foo from test_toolchain_1", toolchain 2 is none: True'
}

function test_multiple_toolchain_use_in_rule_one_missing {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}" test_toolchain_1
  write_test_toolchain "${pkg}" test_toolchain_2

  write_register_toolchain "${pkg}" test_toolchain_1
  # Do not register test_toolchain_2 to cause the error.

  # The rule uses two separate toolchains.
  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/rule_use_toolchains.bzl" <<EOF
def _impl(ctx):
  toolchain_1 = ctx.toolchains['//${pkg}/toolchain:test_toolchain_1']
  toolchain_2 = ctx.toolchains['//${pkg}/toolchain:test_toolchain_2']
  message = ctx.attr.message
  print(
      'Using toolchain: rule message: "%s", toolchain 1 extra_str: "%s", toolchain 2 extra_str: "%s"' %
         (message, toolchain_1.extra_str, toolchain_2.extra_str))
  return []

use_toolchains = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = [
        '//${pkg}/toolchain:test_toolchain_1',
        '//${pkg}/toolchain:test_toolchain_2',
    ],
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchains.bzl', 'use_toolchains')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchains(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "While resolving toolchains for target //${pkg}/demo:use[^:]*: No matching toolchains found for types:$"
  expect_log "^  //${pkg}/toolchain:test_toolchain_2$"
}

function test_toolchain_use_in_rule_non_required_toolchain {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_register_toolchain "${pkg}"

  # The rule argument toolchains requires one toolchain, but the implementation requests a different
  # one.
  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/rule_use_toolchain.bzl" <<EOF
def _impl(ctx):
  toolchain = ctx.toolchains['//${pkg}/toolchain:wrong_toolchain']
  message = ctx.attr.message
  print(
      'Using toolchain: rule message: "%s", toolchain extra_str: "%s"' %
         (message, toolchain.extra_str))
  return []

use_toolchain = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = ['//${pkg}/toolchain:test_toolchain'],
)
EOF

  # Trigger the wrong toolchain.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "In use_toolchain rule //${pkg}/demo:use, toolchain type //${pkg}/toolchain:wrong_toolchain was requested but only types \[//${pkg}/toolchain:test_toolchain\] are configured"
}

function test_toolchain_debug_messages {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"
  write_register_toolchain "${pkg}"

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])

# Define a toolchain that can't be used due to the target_settings
test_toolchain(
    name = 'toolchain_impl_invalid',
)
config_setting(
    name = "optimized",
    values = {"compilation_mode": "opt"}
)
toolchain(
    name = 'toolchain_invalid',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    target_settings = [":optimized"],
    toolchain = ':toolchain_impl_invalid')

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build \
    --extra_toolchains="//${pkg}/demo:toolchain_invalid" \
    --toolchain_resolution_debug=toolchain:test_toolchain \
    --platform_mappings= \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log "Performing resolution of //${pkg}/toolchain:test_toolchain for target platform ${default_host_platform}"
  expect_log "Rejected toolchain //${pkg}/demo:toolchain_invalid; mismatching config settings: optimized"
  expect_log "Toolchain //register/${pkg}:test_toolchain_1 (resolves to //register/${pkg}:test_toolchain_impl_1) is compatible with target platform, searching for execution platforms:"
  expect_log "Compatible execution platform ${default_host_platform}"
  expect_log "Recap of selected //${pkg}/toolchain:test_toolchain toolchains for target platform ${default_host_platform}:"
  expect_log "Selected //register/${pkg}:test_toolchain_impl_1 to run on execution platform ${default_host_platform}"
  expect_log "Target platform ${default_host_platform}: Selected execution platform ${default_host_platform}, type //${pkg}/toolchain:test_toolchain -> toolchain //register/${pkg}:test_toolchain_impl_1"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from test_toolchain"'
}

function test_toolchain_debug_messages_target {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"
  write_register_toolchain "${pkg}"

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build \
    --toolchain_resolution_debug=demo:use \
    --platform_mappings= \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log "Performing resolution of //${pkg}/toolchain:test_toolchain for target platform ${default_host_platform}"
  expect_log "Toolchain //register/${pkg}:test_toolchain_1 (resolves to //register/${pkg}:test_toolchain_impl_1) is compatible with target platform, searching for execution platforms:"
  expect_log "Compatible execution platform ${default_host_platform}"
  expect_log "Recap of selected //${pkg}/toolchain:test_toolchain toolchains for target platform ${default_host_platform}:"
  expect_log "Selected //register/${pkg}:test_toolchain_impl_1 to run on execution platform ${default_host_platform}"
  expect_log "ToolchainResolution: Target platform ${default_host_platform}: Selected execution platform ${default_host_platform}, type //${pkg}/toolchain:test_toolchain -> toolchain //register/${pkg}:test_toolchain_impl_1"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from test_toolchain"'
}

function test_toolchain_use_in_aspect {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_aspect "${pkg}"
  write_register_toolchain "${pkg}"

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/demo.bzl" <<EOF
def _impl(ctx):
  return []

demo = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    }
)
EOF
  cat > "${pkg}/demo/BUILD" <<EOF
load(':demo.bzl', 'demo')

package(default_visibility = ["//visibility:public"])

demo(
    name = 'use',
    message = 'bar from demo')
EOF

  bazel build \
    --aspects //${pkg}/toolchain:aspect_use_toolchain.bzl%use_toolchain \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain in aspect: rule message: "bar from demo", toolchain extra_str: "foo from test_toolchain"'
}

function test_toolchain_use_in_aspect_with_output_file {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_aspect "${pkg}"
  write_register_toolchain "${pkg}"

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/demo.bzl" <<EOF
def _impl(ctx):
    output = ctx.outputs.out
    ctx.actions.write(output = output, content = ctx.attr.message)

demo = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
        'out': attr.output(),
    }
)
EOF
  cat > "${pkg}/demo/BUILD" <<EOF
load(':demo.bzl', 'demo')

package(default_visibility = ["//visibility:public"])

demo(
    name = 'use',
    message = 'bar from demo',
    out = 'use.log',
)
EOF

  # Also test aspects executing on an output file.
  bazel build \
    --aspects //${pkg}/toolchain:aspect_use_toolchain.bzl%use_toolchain \
    "//${pkg}/demo:use.log" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain in aspect: rule message: "bar from demo", toolchain extra_str: "foo from test_toolchain"'
}

function test_toolchain_use_in_aspect_non_required_toolchain {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_register_toolchain "${pkg}"

  # The aspect argument toolchains requires one toolchain, but the implementation requests a
  # different one.
  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/aspect_use_toolchain.bzl" <<EOF
def _impl(target, ctx):
  toolchain = ctx.toolchains['//${pkg}/toolchain:wrong_toolchain']
  message = ctx.rule.attr.message
  print(
      'Using toolchain in aspect: rule message: "%s", toolchain extra_str: "%s"' %
          (message, toolchain.extra_str))
  return []

use_toolchain = aspect(
    implementation = _impl,
    attrs = {},
    toolchains = ['//${pkg}/toolchain:test_toolchain'],
)
EOF

  # Trigger the wrong toolchain.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/demo.bzl" <<EOF
def _impl(ctx):
  return []

demo = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    }
)
EOF
  cat > "${pkg}/demo/BUILD" <<EOF
load(':demo.bzl', 'demo')

package(default_visibility = ["//visibility:public"])

demo(
    name = 'use',
    message = 'bar from demo')
EOF

  bazel build \
    --aspects "//${pkg}/toolchain:aspect_use_toolchain.bzl%use_toolchain" \
    "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "In aspect //${pkg}/toolchain:aspect_use_toolchain.bzl%use_toolchain applied to demo rule //${pkg}/demo:use, toolchain type //${pkg}/toolchain:wrong_toolchain was requested but only types \[//${pkg}/toolchain:test_toolchain\] are configured"
}

function test_toolchain_constraints() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}:toolchain_1')
register_toolchains('//${pkg}:toolchain_2')
EOF

  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])


# Define constraints.
constraint_setting(name = 'setting')
constraint_value(name = 'value1', constraint_setting = ':setting')
constraint_value(name = 'value2', constraint_setting = ':setting')

platform(
    name = 'platform1',
    constraint_values = [':value1'],
)
platform(
    name = 'platform2',
    constraint_values = [':value2'],
)

# Define the toolchain.
filegroup(name = 'dep_rule')
test_toolchain(
    name = 'toolchain_impl_1',
    extra_label = ':dep_rule',
    extra_str = 'foo from 1',
)
test_toolchain(
    name = 'toolchain_impl_2',
    extra_label = ':dep_rule',
    extra_str = 'foo from 2',
)

# Declare the toolchain.
toolchain(
    name = 'toolchain_1',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    exec_compatible_with = [':value1'],
    target_compatible_with = [':value2'],
    toolchain = ':toolchain_impl_1')
toolchain(
    name = 'toolchain_2',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    exec_compatible_with = [':value2'],
    target_compatible_with = [':value1'],
    toolchain = ':toolchain_impl_2')
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  # This should use toolchain_1.
  bazel build \
    --host_platform="//${pkg}:platform1" \
    --platforms="//${pkg}:platform2" \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from 1"'

  # This should use toolchain_2.
  bazel build \
    --host_platform="//${pkg}:platform2" \
    --platforms="//${pkg}:platform1" \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from 2"'

  # This should not match any toolchains.
  bazel build \
    --host_platform="//${pkg}:platform1" \
    --platforms="//${pkg}:platform1" \
    "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "While resolving toolchains for target //${pkg}/demo:use[^:]*: No matching toolchains found for types:$"
  expect_log "^  //${pkg}/toolchain:test_toolchain$"
  expect_not_log 'Using toolchain: rule message:'
}

function test_register_toolchain_error_invalid_label() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"
  write_register_toolchain "${pkg}"

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('/:invalid:label:syntax')
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"

  expect_log "Expected absolute target patterns (must begin with '//'"
  # Bazel's error message has an extra "or '@'" here
  expect_log ") for 'register_toolchains' argument, but got '/:invalid:label:syntax'"
}

function test_register_toolchain_error_invalid_target() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}/demo:not_a_target')
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "While resolving toolchains for target //${pkg}/demo:use[^:]*: invalid registered toolchain '//${pkg}/demo:not_a_target': no such target '//${pkg}/demo:not_a_target': target 'not_a_target' not declared in package '${pkg}/demo'"
}

function test_register_toolchain_error_target_not_a_toolchain() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}/demo:invalid')
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/out.log" <<EOF
INVALID
EOF
  cat > "${pkg}/demo/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

filegroup(
    name = "invalid",
    srcs = ["out.log"],
)

load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')
# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "While resolving toolchains for target //${pkg}/demo:use[^:]*: invalid registered toolchain '//${pkg}/demo:invalid': target does not provide the DeclaredToolchainInfo provider"
}


function test_register_toolchain_error_invalid_pattern() {
  local -r pkg="${FUNCNAME[0]}"
  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}:bad1')
register_toolchains('//${pkg}:bad2')
EOF

  mkdir -p "${pkg}"
  cat >"${pkg}/rules.bzl" <<EOF
def _impl(ctx):
  toolchain = ctx.toolchains['//${pkg}:dummy']
  return []

foo = rule(
  implementation = _impl,
  toolchains = ['//${pkg}:dummy'],
)
EOF

  cat > "${pkg}/BUILD" <<EOF
load(":rules.bzl", "foo")

package(default_visibility = ["//visibility:public"])

toolchain_type(name = 'dummy')
foo(name = "foo")
EOF

  bazel build "//${pkg}:foo" &> $TEST_log && fail "Build failure expected"
  # It's uncertain which error will happen first, so handle either.
  expect_log "While resolving toolchains for target //${pkg}:foo[^:]*: invalid registered toolchain '//${pkg}:bad[12]': no such target"
}


function test_toolchain_error_invalid_target() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  # Write toolchain with an invalid target.
  mkdir -p "${pkg}/invalid"
  cat > "${pkg}/invalid/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

toolchain(
    name = 'invalid_toolchain',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    exec_compatible_with = [],
    target_compatible_with = [],
    toolchain = '//${pkg}/toolchain:does_not_exist',
)
EOF

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}/invalid:invalid_toolchain')
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "Target '//${pkg}/demo:use' depends on toolchain '//${pkg}/toolchain:does_not_exist', which cannot be found: no such target '//${pkg}/toolchain:does_not_exist': target 'does_not_exist' not declared in package '${pkg}/toolchain'"
}


function test_platforms_options_error_invalid_target() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"
  write_register_toolchain "${pkg}"

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  # Write an invalid rule to be the platform.
  mkdir -p "${pkg}/platform"
  cat > "${pkg}/platform/BUILD" <<EOF

package(default_visibility = ["//visibility:public"])

filegroup(name = 'not_a_platform')
EOF

  bazel build \
    --platforms="//${pkg}/platform:not_a_platform" \
    "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "Target //${pkg}/platform:not_a_platform was referenced as a platform, but does not provide PlatformInfo"

  bazel build \
    --host_platform="//${pkg}/platform:not_a_platform" \
    "//${pkg}/demo:use" &> $TEST_log && fail "Build failure expected"
  expect_log "Target //${pkg}/platform:not_a_platform was referenced as a platform, but does not provide PlatformInfo"
}


function test_native_rule_target_exec_constraints() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p "${pkg}/platform"
  cat > "${pkg}/platform/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

constraint_setting(name = "test")

constraint_value(
    name = "test_enabled",
    constraint_setting = ":test",
)

platform(
    name = "test_platform",
    constraint_values = [
        ":test_enabled",
    ],
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

genrule(
    name = "target",
    outs = ["out.txt"],
    cmd = """
      echo "platform" > \$@
    """,
    exec_compatible_with = [
        "//${pkg}/platform:test_enabled",
    ],
)
EOF

  # When no platform has the constraint, an error
  bazel build \
    --toolchain_resolution_debug=.* \
    "//${pkg}/demo:target" &> $TEST_log && fail "Build failure expected"
    expect_log "While resolving toolchains for target //${pkg}/demo:target[^:]*: .* from available execution platforms \[\]"

  # When the platform exists, it is used.
  bazel build \
    --extra_execution_platforms="//${pkg}/platform:test_platform" \
    --toolchain_resolution_debug=.* \
    "//${pkg}/demo:target" &> $TEST_log || fail "Build failed"
  expect_log "Selected execution platform //${pkg}/platform:test_platform"
}


function test_rule_with_default_execution_constraints() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_register_toolchain "${pkg}"

  # Add test platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

constraint_setting(name = 'setting')
constraint_value(name = 'value1', constraint_setting = ':setting')
constraint_value(name = 'value2', constraint_setting = ':setting')

platform(
    name = 'platform1',
    constraint_values = [':value1'],
)
platform(
    name = 'platform2',
    constraint_values = [':value2'],
)
EOF

  # Add a rule with default execution constraints.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/rule.bzl" <<EOF
def _impl(ctx):
  return []

sample_rule = rule(
  implementation = _impl,
  attrs = {},
  exec_compatible_with = [
    '//${pkg}/platforms:value2',
  ],
  toolchains = ['//${pkg}/toolchain:test_toolchain'],
)
EOF

  # Use the new rule.
  cat > "${pkg}/demo/BUILD" <<EOF
load(':rule.bzl', 'sample_rule')

package(default_visibility = ["//visibility:public"])

sample_rule(name = 'use')
EOF

  # Build the target, using debug messages to verify the correct platform was selected.
  bazel build \
    --extra_execution_platforms="//${pkg}/platforms:all" \
    --toolchain_resolution_debug=toolchain:test_toolchain \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log "Selected execution platform //${pkg}/platforms:platform2"
}


function test_target_with_execution_constraints() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_register_toolchain "${pkg}"

  # Add test platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ['//visibility:public'])

constraint_setting(name = 'setting')
constraint_value(name = 'value1', constraint_setting = ':setting')
constraint_value(name = 'value2', constraint_setting = ':setting')

platform(
    name = 'platform1',
    constraint_values = [':value1'],
)
platform(
    name = 'platform2',
    constraint_values = [':value2'],
)
EOF

  # Add a rule with default execution constraints.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/rule.bzl" <<EOF
def _impl(ctx):
  return []

sample_rule = rule(
  implementation = _impl,
  attrs = {},
  toolchains = ['//${pkg}/toolchain:test_toolchain'],
)
EOF

  # Use the new rule.
  cat > "${pkg}/demo/BUILD" <<EOF
load(':rule.bzl', 'sample_rule')

package(default_visibility = ["//visibility:public"])

sample_rule(
  name = 'use',
  exec_compatible_with = [
    '//${pkg}/platforms:value2',
  ],
)
EOF

  # Build the target, using debug messages to verify the correct platform was selected.
  bazel build \
    --extra_execution_platforms="//${pkg}/platforms:all" \
    --toolchain_resolution_debug=toolchain:test_toolchain \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log "Selected execution platform //${pkg}/platforms:platform2"
}

function test_rule_and_target_with_execution_constraints() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_register_toolchain "${pkg}"

  # Add test platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ['//visibility:public'])

constraint_setting(name = 'setting1')
constraint_value(name = 'value1', constraint_setting = ':setting1')
constraint_value(name = 'value2', constraint_setting = ':setting1')

constraint_setting(name = 'setting2')
constraint_value(name = 'value3', constraint_setting = ':setting2')
constraint_value(name = 'value4', constraint_setting = ':setting2')

platform(
    name = 'platform1_3',
    constraint_values = [':value1', ':value3'],
)
platform(
    name = 'platform1_4',
    constraint_values = [':value1', ':value4'],
)
platform(
    name = 'platform2_3',
    constraint_values = [':value2', ':value3'],
)
platform(
    name = 'platform2_4',
    constraint_values = [':value2', ':value4'],
)
EOF

  # Add a rule with default execution constraints.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/rule.bzl" <<EOF
def _impl(ctx):
  return []

sample_rule = rule(
  implementation = _impl,
  attrs = {},
  exec_compatible_with = [
    '//${pkg}/platforms:value2',
  ],
  toolchains = ['//${pkg}/toolchain:test_toolchain'],
)
EOF

  # Use the new rule.
  cat > "${pkg}/demo/BUILD" <<EOF
load(':rule.bzl', 'sample_rule')

package(default_visibility = ["//visibility:public"])

sample_rule(
  name = 'use',
  exec_compatible_with = [
    '//${pkg}/platforms:value4',
  ],
)
EOF

  # Build the target, using debug messages to verify the correct platform was selected.
  bazel build \
    --extra_execution_platforms="//${pkg}/platforms:all" \
    --toolchain_resolution_debug=toolchain:test_toolchain \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log "Selected execution platform //${pkg}/platforms:platform2_4"
}

function test_target_setting() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}:toolchain_1')
register_toolchains('//${pkg}:toolchain_2')
EOF

  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])

# Define the toolchain.
filegroup(name = 'dep_rule')
test_toolchain(
    name = 'toolchain_impl_1',
    extra_label = ':dep_rule',
    extra_str = 'foo from 1',
)
test_toolchain(
    name = 'toolchain_impl_2',
    extra_label = ':dep_rule',
    extra_str = 'foo from 2',
)

# Define config setting
config_setting(
    name = "optimized",
    values = {"compilation_mode": "opt"}
)

# Declare the toolchain.
toolchain(
    name = 'toolchain_1',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    target_settings = [":optimized"],
    toolchain = ':toolchain_impl_1')
toolchain(
    name = 'toolchain_2',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    toolchain = ':toolchain_impl_2')
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  # This should use toolchain_2.
  bazel build \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from 2"'

  # This should use toolchain_1.
  bazel build \
    --compilation_mode=opt \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from 1"'

  # This should match toolchain_2.
  bazel build \
    --compilation_mode=fastbuild \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from 2"'
}

function test_target_setting_with_transition() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}:toolchain_1')
register_toolchains('//${pkg}:toolchain_2')
EOF

  mkdir -p "{$pkg}"

  cat > "${pkg}/flags.bzl" <<EOF
def _impl(ctx):
  pass

string_flag = rule(
    implementation = _impl,
    build_setting = config.string(flag = True),
)
EOF

  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])

# Define the toolchain.
filegroup(name = 'dep_rule')
test_toolchain(
    name = 'toolchain_impl_1',
    extra_label = ':dep_rule',
    extra_str = 'foo from 1',
)
test_toolchain(
    name = 'toolchain_impl_2',
    extra_label = ':dep_rule',
    extra_str = 'foo from 2',
)

# Define config setting
config_setting(
    name = "prod_version",
    flag_values = {"//${pkg}/demo:version": "production"}
)

# Declare the toolchain.
toolchain(
    name = 'toolchain_1',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    target_settings = [":prod_version"],
    toolchain = ':toolchain_impl_1')
toolchain(
    name = 'toolchain_2',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    toolchain = ':toolchain_impl_2')
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/rule.bzl" <<EOF
def _sample_rule_impl(ctx):
  return []

sample_rule = rule(
  implementation = _sample_rule_impl,
  attrs = {
    "dep": attr.label(cfg = 'exec'),
  },
)
EOF

  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')
load('//${pkg}:flags.bzl', 'string_flag')
load(':rule.bzl', 'sample_rule')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')

# Use the toolchain in exec configuration
sample_rule(
    name = 'sample',
    dep = ':use',
)

string_flag(
  name = 'version',
  build_setting_default = 'production'
)
EOF

  # This should use toolchain_1 (because default --//${pkg}/demo:version=production).
  # Pass --experimental_propagate_custom_flag to make sure the flag is
  # propagated to the exec configuration.
  bazel build \
    "//${pkg}/demo:sample" "--experimental_propagate_custom_flag=//${pkg}/demo:version" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from 1"'

  # This should use toolchain_2.
  bazel build \
    "//${pkg}/demo:sample" "--experimental_propagate_custom_flag=//${pkg}/demo:version" \
    --//${pkg}/demo:version=dev &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from 2"'
}

function test_default_constraint_values {
  local -r pkg="${FUNCNAME[0]}"
  # Add test constraints and platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ['//visibility:public'])

constraint_setting(name = 'setting1', default_constraint_value = ':value_foo')
constraint_value(name = 'value_foo', constraint_setting = ':setting1')
constraint_value(name = 'value_bar', constraint_setting = ':setting1')

# Default constraint values don't block toolchain resolution.
constraint_setting(name = 'setting2', default_constraint_value = ':value_unused')
constraint_value(name = 'value_unused', constraint_setting = ':setting2')

platform(
    name = 'platform_default',
    constraint_values = [
      ':value_unused',
    ])
platform(
    name = 'platform_no_default',
    constraint_values = [
      ':value_bar',
      ':value_unused',
    ])
EOF

  # Add test toolchains using the constraints.
  write_test_toolchain "${pkg}"
  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])

# Define the toolchains.
test_toolchain(
    name = 'test_toolchain_impl_foo',
    extra_str = 'foo',
)

test_toolchain(
    name = 'test_toolchain_impl_bar',
    extra_str = 'bar',
)

# Declare the toolchains.
toolchain(
    name = 'test_toolchain_foo',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    exec_compatible_with = [],
    target_compatible_with = [
      '//${pkg}/platforms:value_foo',
    ],
    toolchain = ':test_toolchain_impl_foo',
)
toolchain(
    name = 'test_toolchain_bar',
    toolchain_type = '//${pkg}/toolchain:test_toolchain',
    exec_compatible_with = [],
    target_compatible_with = [
      '//${pkg}/platforms:value_bar',
    ],
    toolchain = ':test_toolchain_impl_bar',
)
EOF

  # Register the toolchains
  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}:test_toolchain_foo', '//${pkg}:test_toolchain_bar')
EOF

  write_test_rule "${pkg}"
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF

  # Test some builds and verify which was used.
  # This should use the default value.
  bazel build \
    --platforms="//${pkg}/platforms:platform_default" \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'toolchain extra_str: "foo"'

  # This should use the explicit value.
  bazel build \
    --platforms="//${pkg}/platforms:platform_no_default" \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'toolchain extra_str: "bar"'
}

function test_make_variables_custom_rule() {
  local -r pkg="${FUNCNAME[0]}"
  # Create a toolchain rule that also exposes make variables.
  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

toolchain_type(name = 'toolchain_var',
)
EOF
  cat > "${pkg}/toolchain/toolchain_var.bzl" <<EOF
def _impl(ctx):
  toolchain = platform_common.ToolchainInfo()
  value = ctx.attr.value
  templates = platform_common.TemplateVariableInfo({'VALUE': value})
  return [toolchain, templates]

toolchain_var = rule(
    implementation = _impl,
    attrs = {
        'value': attr.string(mandatory = True),
    }
)
EOF

  # Create a rule that consumes the toolchain.
  cat > "${pkg}/toolchain/rule_var.bzl" <<EOF
def _impl(ctx):
  toolchain = ctx.toolchains['//${pkg}/toolchain:toolchain_var']
  value = ctx.var['VALUE']
  print('Using toolchain: value "%s"' % value)
  return []

rule_var = rule(
    implementation = _impl,
    toolchains = ['//${pkg}/toolchain:toolchain_var'],
)
EOF

  # Create and register a toolchain
  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}:toolchain_var_1')
EOF

  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_var.bzl', 'toolchain_var')

package(default_visibility = ["//visibility:public"])

# Define the toolchain.
toolchain_var(
    name = 'toolchain_var_impl_1',
    value = 'foo',
)

# Declare the toolchain.
toolchain(
    name = 'toolchain_var_1',
    toolchain_type = '//${pkg}/toolchain:toolchain_var',
    exec_compatible_with = [],
    target_compatible_with = [],
    toolchain = ':toolchain_var_impl_1',
)
EOF

  # Instantiate the rule and verify the output.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_var.bzl', 'rule_var')

package(default_visibility = ["//visibility:public"])

rule_var(name = 'demo')
EOF

  bazel build "//${pkg}/demo:demo" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: value "foo"'
}

function test_local_config_platform() {
  if [ "${PRODUCT_NAME}" != "bazel" ]; then
    # Tests of external repositories only work under bazel.
    return 0
  fi
  bazel query @local_config_platform//... &> $TEST_log || fail "Build failed"
  expect_log '@local_config_platform//:host'
}

# Test cycles in registered toolchains, which can only happen when
# registered_toolchains is called for something that is not actually
# using the "toolchain" rule.
function test_registered_toolchain_cycle() {
  local -r pkg="${FUNCNAME[0]}"

  # Set up two sets of rules and toolchains, one depending on the other.
  mkdir -p "${pkg}"
  cat > "${pkg}/lower.bzl" <<EOF
def _lower_toolchain_impl(ctx):
  message = ctx.attr.message
  toolchain = platform_common.ToolchainInfo(
      message=message)
  return [toolchain]

lower_toolchain = rule(
    implementation = _lower_toolchain_impl,
    attrs = {
        'message': attr.string(),
    },
)

def _lower_library_impl(ctx):
  toolchain = ctx.toolchains['//${pkg}:lower']
  print('lower library: %s' % toolchain.message)
  return []

lower_library = rule(
    implementation = _lower_library_impl,
    attrs = {},
    toolchains = ['//${pkg}:lower'],
)
EOF

  cat >"${pkg}/upper.bzl" <<EOF
def _upper_toolchain_impl(ctx):
  tool_message = ctx.toolchains['//${pkg}:lower'].message
  message = ctx.attr.message
  toolchain = platform_common.ToolchainInfo(
      tool_message=tool_message,
      message=message)
  return [toolchain]

upper_toolchain = rule(
    implementation = _upper_toolchain_impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = ['//${pkg}:lower'],
)

def _upper_library_impl(ctx):
  toolchain = ctx.toolchains['//${pkg}:upper']
  print('upper library: %s (%s)' % (toolchain.message, toolchain.tool_message))
  return []

upper_library = rule(
    implementation = _upper_library_impl,
    attrs = {},
    toolchains = ['//${pkg}:upper'],
)
EOF

  # Define the actual targets using these.
  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}:lower.bzl', 'lower_toolchain', 'lower_library')
load('//${pkg}:upper.bzl', 'upper_toolchain', 'upper_library')

package(default_visibility = ["//visibility:public"])

toolchain_type(name = 'lower')
toolchain_type(name = 'upper')

lower_library(
    name = 'lower_lib',
)

lower_toolchain(
    name = 'lower_toolchain',
    message = 'hi from lower',
)
toolchain(
    name = 'lower_toolchain_impl',
    toolchain_type = '//${pkg}:lower',
    toolchain = ':lower_toolchain',
)

upper_library(
    name = 'upper_lib',
)

upper_toolchain(
    name = 'upper_toolchain',
    message = 'hi from upper',
)
toolchain(
    name = 'upper_toolchain_impl',
    toolchain_type = '//${pkg}:upper',
    toolchain = ':upper_toolchain',
)
EOF

  # Finally, set up the misconfigured MODULE.bazel file.
  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains(
    '//${pkg}:upper_toolchain', # Not a toolchain() target!
    '//${pkg}:lower_toolchain_impl',
    )
EOF

  # Execute the build and check the error message.
  bazel build "//${pkg}:upper_lib" &> $TEST_log && fail "Build succeeded unexpectedly"
  expect_not_log "java.lang.IllegalStateException"
  expect_log "Misconfigured toolchains: //${pkg}:upper_toolchain is declared as a toolchain but has inappropriate dependencies"
}


# Catch the error when a target platform requires a configuration which contains the same target platform.
# This can only happen when the target platform is not actually a platform.
function test_target_platform_cycle() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p "${pkg}"
  cat > "${pkg}/hello.sh" <<EOF
  #!/bin/sh
  echo "Hello world"
EOF
  cat > "${pkg}/target.sh" <<EOF
  #!/bin/sh
  echo "Hello target"
EOF
  chmod +x "${pkg}/hello.sh"
  chmod +x "${pkg}/target.sh"
  cat > "${pkg}/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

sh_binary(
  name = "hello",
  srcs = ["hello.sh"],
)
sh_binary(
  name = "target",
  srcs = ["target.sh"],
)
EOF

  echo "START DEBUGGING"
  bazel build \
    --platforms="//${pkg}:hello" \
    "//${pkg}:target" &> $TEST_log && fail "Build succeeded unexpectedly"
  expect_log "Target //${pkg}:hello was referenced as a platform, but does not provide PlatformInfo"
}


function test_platform_duplicate_constraint_error() {
  local -r pkg="${FUNCNAME[0]}"
  # Write a platform with duplicate constraint values for the same setting.
  mkdir -p "${pkg}/platform"
  cat > "${pkg}/platform/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

constraint_setting(name = 'foo')
constraint_value(name = 'val1', constraint_setting = ':foo')
constraint_value(name = 'val2', constraint_setting = ':foo')
platform(
    name = 'test',
    constraint_values = [
        ':val1',
        ':val2',
    ],
)
EOF

  bazel build "//${pkg}/platform:test" &> $TEST_log && fail "Build failure expected"
  expect_log "Duplicate constraint values detected"
}

function test_toolchain_duplicate_constraint_error() {
  local -r pkg="${FUNCNAME[0]}"
  # Write a toolchain with duplicate constraint values for the same setting.
  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

constraint_setting(name = 'foo')
constraint_value(name = 'val1', constraint_setting = ':foo')
constraint_value(name = 'val2', constraint_setting = ':foo')
constraint_setting(name = 'bar')
constraint_value(name = 'val3', constraint_setting = ':bar')
constraint_value(name = 'val4', constraint_setting = ':bar')
toolchain_type(name = 'toolchain_type')
filegroup(name = 'toolchain')
toolchain(
    name = 'test',
    toolchain_type = ':toolchain_type',
    exec_compatible_with = [
        ':val1',
        ':val2',
    ],
    target_compatible_with = [
        ':val3',
        ':val4',
    ],
    toolchain = ':toolchain',
)
EOF

  bazel build "//${pkg}/toolchain:test" &> $TEST_log && fail "Build failure expected"
  expect_not_log "java.lang.IllegalArgumentException"
  expect_log "in exec_compatible_with attribute of toolchain rule //${pkg}/toolchain:test: Duplicate constraint values detected: constraint_setting //${pkg}/toolchain:foo has \[//${pkg}/toolchain:val1, //${pkg}/toolchain:val2\]"
  expect_log "in target_compatible_with attribute of toolchain rule //${pkg}/toolchain:test: Duplicate constraint values detected: constraint_setting //${pkg}/toolchain:bar has \[//${pkg}/toolchain:val3, //${pkg}/toolchain:val4\]"
}


function test_exec_transition() {
  local -r pkg="${FUNCNAME[0]}"
  # Add test platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ['//visibility:public'])

constraint_setting(name = 'setting')
constraint_value(name = 'value1', constraint_setting = ':setting')
constraint_value(name = 'value2', constraint_setting = ':setting')

platform(
    name = 'platform1',
    constraint_values = [':value1'],
)
platform(
    name = 'platform2',
    constraint_values = [':value2'],
)
EOF

  # Add a rule with default execution constraints.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/rule.bzl" <<EOF
def _sample_rule_impl(ctx):
  return []

sample_rule = rule(
  implementation = _sample_rule_impl,
  attrs = {
    "dep": attr.label(cfg = 'exec'),
  },
)

def _display_platform_impl(ctx):
  print("%s target platform: %s" % (ctx.label, ctx.fragments.platform.platforms[0]))
  return []

display_platform = rule(
  implementation = _display_platform_impl,
  attrs = {},
  fragments = ['platform'],
)
EOF

  # Use the new rule.
  cat > "${pkg}/demo/BUILD" <<EOF
load(':rule.bzl', 'sample_rule', 'display_platform')

package(default_visibility = ["//visibility:public"])

sample_rule(
  name = 'use',
  dep = ":dep",
  exec_compatible_with = [
    '//${pkg}/platforms:value2',
  ],
)

display_platform(name = 'dep')
EOF

  # Build the target, using debug messages to verify the correct platform was selected.
  bazel build \
    --extra_execution_platforms="//${pkg}/platforms:all" \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log "@@\?//${pkg}/demo:dep target platform: @@\?//${pkg}/platforms:platform2"
}

function test_config_setting_with_constraints {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p "${pkg}"
  cat > "${pkg}/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

constraint_setting(name = "setting1")
constraint_value(name = "value1", constraint_setting = ":setting1")
constraint_value(name = "value2", constraint_setting = ":setting1")
platform(name = "platform1",
  constraint_values = [":value1"],
)
platform(name = "platform2",
  constraint_values = [":value2"],
)

config_setting(name = "config1",
  constraint_values = [":value1"],
)
config_setting(name = "config2",
  constraint_values = [":value2"],
)

genrule(name = "demo",
  outs = ["demo.log"],
  cmd = select({
    ":config1": "echo 'config1 selected' > \$@",
    ":config2": "echo 'config2 selected' > \$@",
  }),
)
EOF

  bazel build \
    --platforms="//${pkg}:platform1" \
    "//${pkg}:demo" &> $TEST_log || fail "Build failed"
  cat "bazel-genfiles/${pkg}/demo.log" >> $TEST_log
  expect_log "config1 selected"

  bazel build \
    --platforms="//${pkg}:platform2" \
    "//${pkg}:demo" &> $TEST_log || fail "Build failed"
  cat "bazel-genfiles/${pkg}/demo.log" >> $TEST_log
  expect_log "config2 selected"
}

function test_config_setting_with_constraints_alias {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p "${pkg}"
  cat > "${pkg}/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

constraint_setting(name = "setting1")
constraint_value(name = "value1", constraint_setting = ":setting1")
constraint_value(name = "value2", constraint_setting = ":setting1")
platform(name = "platform1",
  constraint_values = [":value1"],
)
platform(name = "platform2",
  constraint_values = [":value2"],
)

alias(name = "alias1", actual = ":value1")
alias(name = "alias1a", actual = ":alias1")
alias(name = "alias2", actual = ":value2")
alias(name = "alias2a", actual = ":alias2")

config_setting(name = "config1",
  constraint_values = [":alias1a"],
)
config_setting(name = "config2",
  constraint_values = [":alias2a"],
)

genrule(name = "demo",
  outs = ["demo.log"],
  cmd = select({
    ":config1": "echo 'config1 selected' > \$@",
    ":config2": "echo 'config2 selected' > \$@",
  }),
)
EOF

  bazel build \
    --platforms="//${pkg}:platform1" \
    "//${pkg}:demo" &> $TEST_log || fail "Build failed"
  cat "bazel-genfiles/${pkg}/demo.log" >> $TEST_log
  expect_log "config1 selected"
  bazel build \
    --platforms="//${pkg}:platform2" \
    "//${pkg}:demo" &> $TEST_log || fail "Build failed"
  cat "bazel-genfiles/${pkg}/demo.log" >> $TEST_log
  expect_log "config2 selected"
}

function test_toolchain_modes {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}" foo_toolchain
  write_test_rule "${pkg}" test_rule foo_toolchain

  mkdir -p "${pkg}/project"
  cat > "${pkg}/project/flags.bzl" <<EOF
def _impl(ctx):
  pass

string_flag = rule(
    implementation = _impl,
    build_setting = config.string(flag = True),
)
EOF

  cat > "${pkg}/project/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_foo_toolchain.bzl', 'foo_toolchain')
load('//${pkg}/toolchain:rule_test_rule.bzl', 'test_rule')
load('//${pkg}/project:flags.bzl', 'string_flag')

package(default_visibility = ["//visibility:public"])

string_flag(
  name = 'version',
  build_setting_default = 'production'
)

config_setting(
  name = 'production',
  flag_values = {
    ':version': 'production'
  }
)

config_setting(
  name = 'unstable',
  flag_values = {
    ':version': 'unstable'
  }
)

filegroup(name = 'dep')
foo_toolchain(
    name = 'production_toolchain',
    extra_label = ':dep',
    extra_str = 'production',
)

foo_toolchain(
    name = 'unstable_toolchain',
    extra_label = ':dep',
    extra_str = 'unstable',
)

toolchain(
    name = 'toolchain',
    toolchain_type = '//${pkg}/toolchain:foo_toolchain',
    toolchain = select({
      ':production': ':production_toolchain',
      ':unstable': ':unstable_toolchain',
    })
)

test_rule(
  name = 'test',
  message = 'hello',
)
EOF

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}/project:toolchain')
EOF

  bazel build \
    "//${pkg}/project:test" &> "${TEST_log}" || fail "Build failed"
  expect_log 'Using toolchain: rule message: "hello", toolchain extra_str: "production"'

  bazel build \
    "--//${pkg}/project:version=unstable" \
    "//${pkg}/project:test" &> "${TEST_log}" || fail "Build failed"
  expect_log 'Using toolchain: rule message: "hello", toolchain extra_str: "unstable"'
}

function test_add_exec_constraints_to_targets() {
  local -r pkg="${FUNCNAME[0]}"
  # Add test platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ['//visibility:public'])

constraint_setting(name = 'setting')
constraint_value(name = 'value1', constraint_setting = ':setting')
constraint_value(name = 'value2', constraint_setting = ':setting')

platform(
    name = 'platform1',
    constraint_values = [':value1'],
)
platform(
    name = 'platform2',
    constraint_values = [':value2'],
)
EOF

  # Add a rule with default execution constraints.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/rule.bzl" <<EOF
def _sample_rule_impl(ctx):
  return []

sample_rule = rule(
  implementation = _sample_rule_impl,
  attrs = {
    "tool": attr.label(cfg = 'exec'),
  }
)

def _display_platform_impl(ctx):
  print("%s target platform: %s" % (ctx.label, ctx.fragments.platform.platforms[0]))
  return []

display_platform = rule(
  implementation = _display_platform_impl,
  attrs = {},
  fragments = ['platform'],
)
EOF

  # Use the new rule.
  cat > "${pkg}/demo/BUILD" <<EOF
load(':rule.bzl', 'sample_rule', 'display_platform')

package(default_visibility = ["//visibility:public"])

sample_rule(
  name = 'sample',
  tool = ":tool",
)

display_platform(name = 'tool')
EOF

  bazel build \
    --extra_execution_platforms="//${pkg}/platforms:platform1,//${pkg}/platforms:platform2" \
    "//${pkg}/demo:sample" &> $TEST_log || fail "Build failed"
  expect_log "@@\?//${pkg}/demo:tool target platform: @@\?//${pkg}/platforms:platform1"

  bazel build \
      --extra_execution_platforms="//${pkg}/platforms:platform1,//${pkg}/platforms:platform2" \
      --experimental_add_exec_constraints_to_targets "//${pkg}/demo:sample=//${pkg}/platforms:value2" \
      "//${pkg}/demo:sample" &> $TEST_log || fail "Build failed"
  expect_log "@@\?//${pkg}/demo:tool target platform: @@\?//${pkg}/platforms:platform2"
}

function test_deps_includes_exec_group_toolchain() {
  local -r pkg="${FUNCNAME[0]}"
  write_register_toolchain "${pkg}"
  write_test_toolchain "${pkg}"

  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/rule_use_toolchain.bzl" <<EOF

def _impl(ctx):
  print(ctx.exec_groups)
  print(ctx.exec_groups['group'].toolchains)
  return []

use_toolchain = rule(
  implementation = _impl,
  exec_groups = {
    "group": exec_group(
      toolchains = ["//${pkg}/toolchain:test_toolchain"],
    ),
  },
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load("//${pkg}/toolchain:rule_use_toolchain.bzl", "use_toolchain")

package(default_visibility = ["//visibility:public"])

use_toolchain(name = "use")
EOF

  bazel cquery "deps(//${pkg}/demo:use, 1)" &> $TEST_log || fail "Build failed"
  expect_log "<toolchain_context.resolved_labels: //${pkg}/toolchain:test_toolchain"
  expect_log "<ctx.exec_groups: group>"
  expect_log "//register/${pkg}:test_toolchain_impl_1"
  expect_log "//${pkg}/toolchain:test_toolchain"
}

function test_two_toolchain_types_resolve_to_same_label() {
  local -r pkg="${FUNCNAME[0]}"
  write_test_toolchain "${pkg}"

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}:toolchain_1')
register_toolchains('//${pkg}:toolchain_2')
EOF

  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

toolchain_type(
    name = 'test_toolchain_1',
)
toolchain_type(
    name = 'test_toolchain_2',
)
EOF

  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])

# Define the toolchain.
test_toolchain(
    name = 'toolchain_impl_1',
)

# Declare the toolchain.
toolchain(
    name = 'toolchain_1',
    toolchain_type = '//${pkg}/toolchain:test_toolchain_1',
    toolchain = ':toolchain_impl_1')
toolchain(
    name = 'toolchain_2',
    toolchain_type = '//${pkg}/toolchain:test_toolchain_2',
    toolchain = ':toolchain_impl_1')
EOF

  cat > "${pkg}/toolchain/rule_use_toolchains.bzl" <<EOF
def _impl(ctx):
  toolchain1 = ctx.toolchains['//${pkg}/toolchain:test_toolchain_1']
  toolchain2 = ctx.toolchains['//${pkg}/toolchain:test_toolchain_2']
  message = ctx.attr.message
  print(
      'Using toolchain1: rule message: "%s", toolchain extra_str: "%s"' %
         (message, toolchain1.extra_str))
  print(
      'Using toolchain2: rule message: "%s", toolchain extra_str: "%s"' %
         (message, toolchain2.extra_str))
  return []

use_toolchains = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = ['//${pkg}/toolchain:test_toolchain_1', '//${pkg}/toolchain:test_toolchain_2'],
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchains.bzl', 'use_toolchains')

package(default_visibility = ["//visibility:public"])

# Use both toolchains.
use_toolchains(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain1: rule message: "this is the rule"'
  expect_log 'Using toolchain2: rule message: "this is the rule"'
}


function test_invalid_toolchain_type() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load(":rule.bzl", "sample_rule")

package(default_visibility = ["//visibility:public"])

sample_rule(name = "demo")
EOF
  cat > "${pkg}/demo/rule.bzl" <<EOF
def _sample_impl(ctx):
    pass

sample_rule = rule(
    implementation = _sample_impl,
    toolchains = ["//${pkg}/demo:toolchain_type"],
)
EOF

  bazel build "//${pkg}/demo" &> $TEST_log && fail "Expected build to fail"
  expect_log "target 'toolchain_type' not declared in package '${pkg}/demo'"
  expect_not_log "does not provide ToolchainTypeInfo"
}

# Tests for the case where a toolchain requires a different toolchain type.
# Regression test for https://github.com/bazelbuild/bazel/issues/13243
function test_toolchain_requires_toolchain() {
  local -r pkg="${FUNCNAME[0]}"
  # Create an inner toolchain.
  mkdir -p "${pkg}/inner"
  cat > "${pkg}/inner/toolchain.bzl" <<EOF
InnerToolchain = provider(fields = ["msg"])

def _impl(ctx):
    inner = InnerToolchain(msg = "Inner toolchain %s" % ctx.label)
    return [
        platform_common.ToolchainInfo(inner = inner)
    ]

inner_toolchain = rule(
    implementation = _impl,
)
EOF
  cat > "${pkg}/inner/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

load(":toolchain.bzl", "inner_toolchain")
toolchain_type(name = "toolchain_type")

inner_toolchain(name = "impl")
toolchain(
    name = "toolchain",
    toolchain_type = ":toolchain_type",
    toolchain = ":impl",
)
EOF

  # Create an outer toolchain the uses the inner.
  mkdir -p "${pkg}/outer"
  cat > "${pkg}/outer/toolchain.bzl" <<EOF
OuterToolchain = provider(fields = ["msg"])

def _impl(ctx):
    toolchain_info = ctx.toolchains["//${pkg}/inner:toolchain_type"]
    inner = toolchain_info.inner
    outer = OuterToolchain(msg = "Outer toolchain %s using inner: %s" % (ctx.label, inner.msg))
    return [
        platform_common.ToolchainInfo(outer = outer)
    ]

outer_toolchain = rule(
    implementation = _impl,
    toolchains = ["//${pkg}/inner:toolchain_type"],
)
EOF
  cat > "${pkg}/outer/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

load(":toolchain.bzl", "outer_toolchain")
toolchain_type(name = "toolchain_type")

outer_toolchain(name = "impl")
toolchain(
    name = "toolchain",
    toolchain_type = ":toolchain_type",
    toolchain = ":impl",
)
EOF

  # Register all the toolchains.
  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains("//${pkg}/inner:all")
register_toolchains("//${pkg}/outer:all")
EOF

  # Write a rule that uses the outer toolchain.
  mkdir -p "${pkg}/rule"
  cat > "${pkg}/rule/rule.bzl" <<EOF
def _impl(ctx):
    toolchain_info = ctx.toolchains["//${pkg}/outer:toolchain_type"]
    outer = toolchain_info.outer
    print("Demo rule: outer toolchain says: %s" % outer.msg)
    return []

demo_rule = rule(
    implementation = _impl,
    toolchains = ["//${pkg}/outer:toolchain_type"],
)
EOF
  cat > "${pkg}/rule/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

exports_files(["rule.bzl"])
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/rule:rule.bzl', 'demo_rule')

package(default_visibility = ["//visibility:public"])

demo_rule(name = "demo")
EOF

  bazel build "//${pkg}/demo:demo" &> $TEST_log || fail "Build failed"
  expect_log "Inner toolchain @@\?//${pkg}/inner:impl"
}

# Test that toolchain type labels are correctly resolved relative to the
# enclosing file, regardless of the repository name.
# See http://b/183060658 for details.
function test_repository_relative_toolchain_type() {
  if [ "${PRODUCT_NAME}" != "bazel" ]; then
    # Tests of external repositories only work under bazel.
    return 0
  fi
  local -r pkg="${FUNCNAME[0]}"
  # Create a repository that defines a toolchain type and simple rule.
  # The toolchain type used in the repository is relative to the repository.
  mkdir -p "${pkg}/external/rules_foo"
  touch "${pkg}/external/rules_foo/REPO.bazel"
  mkdir -p "${pkg}/external/rules_foo/rule"
  touch "${pkg}/external/rules_foo/rule/BUILD"
  cat > "${pkg}/external/rules_foo/rule/rule.bzl" <<EOF
def _foo_impl(ctx):
    print(ctx.toolchains["//toolchain:foo_toolchain_type"])
    return []

foo_rule = rule(
    implementation = _foo_impl,
    toolchains = ["//toolchain:foo_toolchain_type"],
)
EOF
  mkdir -p "${pkg}/external/rules_foo/toolchain/"
  cat > "${pkg}/external/rules_foo/toolchain/BUILD" <<EOF
load(":toolchain.bzl", "foo_toolchain")

package(default_visibility = ["//visibility:public"])

toolchain_type(
  name = "foo_toolchain_type",
)

foo_toolchain(
    name = "foo_toolchain",
)

toolchain(
    name = "foo_default_toolchain",
    toolchain = ":foo_toolchain",
    toolchain_type = ":foo_toolchain_type",
)
EOF
  cat > "${pkg}/external/rules_foo/toolchain/toolchain.bzl" <<EOF
_ATTRS = dict(
  foo_tool = attr.label(
      allow_files = True,
      default = "//foo_tools:foo_tool",
  ),
)

def _impl(ctx):
    return [platform_common.ToolchainInfo(
        **{name: getattr(ctx.attr, name) for name in _ATTRS.keys()}
    )]

foo_toolchain = rule(
    implementation = _impl,
    attrs = _ATTRS,
)
EOF
  mkdir -p "${pkg}/external"/rules_foo/foo_tools
  cat > "${pkg}/external/rules_foo/foo_tools/BUILD" <<EOF
package(default_visibility = ["//visibility:public"])

sh_binary(
  name = "foo_tool",
  srcs = ["foo_tool.sh"],
)
EOF
  cat > "${pkg}/external/rules_foo/foo_tools/foo_tool.sh" <<EOF
echo creating \$1
touch \$1
EOF
  chmod +x "${pkg}/external/rules_foo/foo_tools/foo_tool.sh"

  # Create a target that uses the rule.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load("@rules_foo//rule:rule.bzl", "foo_rule")

package(default_visibility = ["//visibility:public"])

foo_rule(name = "demo")
EOF

  # Set up the MODULE.bazel.
  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "rules_foo",
  path = "${pkg}/external/rules_foo",
)

register_toolchains(
  "@rules_foo//toolchain:foo_default_toolchain",
)
EOF

  # Test the build.
  bazel build \
    "//${pkg}/demo:demo" &> $TEST_log || fail "Build failed"
  expect_log "foo_tool = <target @@+_repo_rules+rules_foo//foo_tools:foo_tool>"
}

function test_exec_platform_order_with_mandatory_toolchains {
  local -r pkg="${FUNCNAME[0]}"

  # Add two possible execution platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ['//visibility:public'])

constraint_setting(name = 'setting')
constraint_value(name = 'value1', constraint_setting = ':setting')
constraint_value(name = 'value2', constraint_setting = ':setting')

platform(
    name = 'platform1',
    constraint_values = [':value1'],
)
platform(
    name = 'platform2',
    constraint_values = [':value2'],
)
EOF
  # Register them in order.
  cat >> $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_execution_platforms("//${pkg}/platforms:platform1", "//${pkg}/platforms:platform2")
EOF

  # Create a toolchain that only works with platform2
  write_test_toolchain "${pkg}" test_toolchain
  write_register_toolchain "${pkg}" test_toolchain "['//${pkg}/platforms:value2']"

  # The rule must receive the toolchain.
  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/rule_use_toolchains.bzl" <<EOF
def _impl(ctx):
  toolchain = ctx.toolchains['//${pkg}/toolchain:test_toolchain']
  message = ctx.attr.message
  print(
      'Using toolchain: rule message: "%s", toolchain is none: %s' %
         (message, toolchain == None))
  return []

use_toolchains = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = [
        config_common.toolchain_type('//${pkg}/toolchain:test_toolchain', mandatory = True),
    ],
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchains.bzl', 'use_toolchains')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchains(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  bazel build \
    --toolchain_resolution_debug=.* \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  # Verify that a toolchain was provided.
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain is none: False'
  # Verify that the exec platform is platform2.
  expect_log "Selected execution platform //${pkg}/platforms:platform2"
}

function test_exec_platform_order_with_optional_toolchains {
  local -r pkg="${FUNCNAME[0]}"

  # Add two possible execution platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ['//visibility:public'])

constraint_setting(name = 'setting')
constraint_value(name = 'value1', constraint_setting = ':setting')
constraint_value(name = 'value2', constraint_setting = ':setting')

platform(
    name = 'platform1',
    constraint_values = [':value1'],
)
platform(
    name = 'platform2',
    constraint_values = [':value2'],
)
EOF
  # Register them in order.
  cat >> $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_execution_platforms("//${pkg}/platforms:platform1", "//${pkg}/platforms:platform2")
EOF

  # Create a toolchain that only works with platform2
  write_test_toolchain "${pkg}" test_toolchain
  write_register_toolchain "${pkg}" test_toolchain "['//${pkg}/platforms:value2']"

  # The rule can optionally use the toolchain.
  mkdir -p "${pkg}/toolchain"
  cat > "${pkg}/toolchain/rule_use_toolchains.bzl" <<EOF
def _impl(ctx):
  toolchain = ctx.toolchains['//${pkg}/toolchain:test_toolchain']
  message = ctx.attr.message
  print(
      'Using toolchain: rule message: "%s", toolchain is none: %s' %
         (message, toolchain == None))
  return []

use_toolchains = rule(
    implementation = _impl,
    attrs = {
        'message': attr.string(),
    },
    toolchains = [
        config_common.toolchain_type('//${pkg}/toolchain:test_toolchain', mandatory = False),
    ],
)
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchains.bzl', 'use_toolchains')

package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchains(
    name = 'use',
    message = 'this is the rule')
EOF

  bazel build "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  bazel build \
    --toolchain_resolution_debug=.* \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"

  # Verify that the exec platform is platform2.
  expect_log "Selected execution platform //${pkg}/platforms:platform2"
}

function setup_toolchain_precedence_tests() {
  local pkg="$1"
  shift

  write_test_toolchain "${pkg}"
  write_test_rule "${pkg}"

  cat > $TOOLCHAIN_REGISTRATION_FILE <<EOF
register_toolchains('//${pkg}:toolchain_1')
EOF

  cat > "${pkg}/BUILD" <<EOF
load('//${pkg}/toolchain:toolchain_test_toolchain.bzl', 'test_toolchain')

package(default_visibility = ["//visibility:public"])

# Define and declare four identical toolchains.
[
  [
    test_toolchain(
      name = 'toolchain_impl_' + str(i),
      extra_str = 'foo from toolchain_' + str(i),
    ),
    toolchain(
      name = 'toolchain_' + str(i),
      toolchain_type = '//${pkg}/toolchain:test_toolchain',
      toolchain = ':toolchain_impl_' + str(i)
    ),
  ]
  for i in range(1, 5)
]
EOF

  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/BUILD" <<EOF
load('//${pkg}/toolchain:rule_use_toolchain.bzl', 'use_toolchain')
package(default_visibility = ["//visibility:public"])

# Use the toolchain.
use_toolchain(
    name = 'use',
    message = 'this is the rule')
EOF
}

# Regression tests for https://github.com/bazelbuild/bazel/issues/19945 and https://github.com/bazelbuild/bazel/issues/22912
function test_extra_toolchain_precedence_basic {
  local -r pkg="${FUNCNAME[0]}"
  setup_toolchain_precedence_tests "${pkg}"

  # Even with other toolchains defined, only one is registered
  bazel \
    build \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from toolchain_1"'
}

function test_extra_toolchain_precedence_from_bazelrc {
  local -r pkg="${FUNCNAME[0]}"
  setup_toolchain_precedence_tests "${pkg}"

  cat > "${pkg}/toolchain_rc" <<EOF
import ${bazelrc}
build --extra_toolchains=//${pkg}:toolchain_2
EOF

  # Test that bazelrc options take precedence over registered toolchains
  bazel \
    --${PRODUCT_NAME}rc="${pkg}/toolchain_rc" \
    build \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from toolchain_2"'
}

function test_extra_toolchain_precedence_from_flag {
  local -r pkg="${FUNCNAME[0]}"
  setup_toolchain_precedence_tests "${pkg}"

  # Test that command-line options take precedence over other toolchains
  bazel \
    build \
    --extra_toolchains=//${pkg}:toolchain_3 \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from toolchain_3"'
}

function test_extra_toolchain_precedence_multiple {
  local -r pkg="${FUNCNAME[0]}"
  setup_toolchain_precedence_tests "${pkg}"

  # Test that the last --extra_toolchains takes precedence
  bazel \
    build \
    --extra_toolchains=//${pkg}:toolchain_3,//${pkg}:toolchain_4 \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from toolchain_4"'
}

function test_extra_toolchain_precedence_multiple_repeated {
  local -r pkg="${FUNCNAME[0]}"
  setup_toolchain_precedence_tests "${pkg}"

  # Test that the last --extra_toolchains takes precedence even when repeated
  bazel \
    build \
    --extra_toolchains=//${pkg}:toolchain_3,//${pkg}:toolchain_4,//${pkg}:toolchain_3 \
    "//${pkg}/demo:use" &> $TEST_log || fail "Build failed"
  # Since toolchain_3 is last, it should still have highest precedence
  expect_log 'Using toolchain: rule message: "this is the rule", toolchain extra_str: "foo from toolchain_3"'
}

# TODO(katre): Test using toolchain-provided make variables from a genrule.

function test_invalid_cpu() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p "${pkg}"
  cat > "${pkg}/BUILD" <<EOF
filegroup(name = "demo")
EOF
  bazel \
    build \
    --allowed_cpu_values=foo,bar \
    --cpu=quux \
    "//${pkg}:demo" &> $TEST_log && fail "Build failure expected"
  expect_log "Invalid --cpu value \"quux\": allowed values are bar, foo."
}

function write_exec_platform_required_setting {
  local pkg="$1"

  # Add test platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ['//visibility:public'])

config_setting(
    name = "optimized",
    values = {"compilation_mode": "opt"}
)

# Define a platform that requires a specific configuration to be eligible as an
# execution platform.
platform(
    name = 'platform_opt',
    required_settings = [
        ":optimized",
    ],
)
platform(
    name = 'platform_basic',
)
EOF

  # Define a build target.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/hello.sh" <<EOF
echo hello
EOF
  chmod +x "${pkg}/demo/hello.sh"
  cat > "${pkg}/demo/BUILD" <<EOF
sh_binary(
  name = 'sample',
  srcs = ["hello.sh"],
)
EOF
}

function test_exec_platform_required_setting {
  local -r pkg="${FUNCNAME[0]}"
  write_exec_platform_required_setting "${pkg}"

  # Use the new exec platforms, with the reqired_settings version first.
  # Do not enable the config_setting.
  bazel build \
    --extra_execution_platforms="//${pkg}/platforms:platform_opt,//${pkg}/platforms:platform_basic" \
    --toolchain_resolution_debug="//${pkg}/demo:sample" \
    --compilation_mode=fastbuild \
    "//${pkg}/demo:sample" &> $TEST_log || fail "Build failed"
  expect_log "Selected execution platform //${pkg}/platforms:platform_basic"

  # Verify the debug log.
  expect_log "Rejected execution platform //${pkg}/platforms:platform_opt; mismatching config settings: optimized"

  # Use the new exec platforms, with the reqired_settings version first.
  # Enable the config_setting.
  bazel build \
    --extra_execution_platforms="//${pkg}/platforms:platform_opt,//${pkg}/platforms:platform_basic" \
    --toolchain_resolution_debug="//${pkg}/demo:sample" \
    --compilation_mode=opt\
    "//${pkg}/demo:sample" &> $TEST_log || fail "Build failed"
  expect_log "Selected execution platform //${pkg}/platforms:platform_opt"

  # Verify the debug log.
  expect_not_log "Rejected execution platform"
}

function test_exec_platform_required_setting_cycle {
  local -r pkg="${FUNCNAME[0]}"

  # Add test platforms.
  mkdir -p "${pkg}/platforms"
  cat > "${pkg}/platforms/BUILD" <<EOF
package(default_visibility = ['//visibility:public'])

# Create a cycle by using a constraint_value instead of a flag.
constraint_setting(name = "cycle_setting")
constraint_value(name = "cycle_value", constraint_setting = ":cycle_setting")
config_setting(
    name = "cycle",
    constraint_values = [":cycle_value"],
)

platform(
    name = 'platform_cycle',
    required_settings = [
        ":cycle",
    ],
)
platform(
    name = 'platform_basic',
)
EOF

  # Define a build target.
  mkdir -p "${pkg}/demo"
  cat > "${pkg}/demo/hello.sh" <<EOF
echo hello
EOF
  chmod +x "${pkg}/demo/hello.sh"
  cat > "${pkg}/demo/BUILD" <<EOF
sh_binary(
  name = 'sample',
  srcs = ["hello.sh"],
)
EOF
  # Use the new exec platforms, with the reqired_settings version first.
  bazel build \
    --extra_execution_platforms="//${pkg}/platforms:platform_cycle,//${pkg}/platforms:platform_basic" \
    --toolchain_resolution_debug="//${pkg}/demo:sample" \
    --compilation_mode=fastbuild \
    "//${pkg}/demo:sample" &> $TEST_log && fail "Unexpected success"
  expect_not_log "Cycle detected but could not be properly displayed due to an internal problem"
  expect_log "Misconfigured execution platforms: //${pkg}/platforms:platform_cycle is declared as a platform but has inappropriate dependencies"
}


run_suite "toolchain tests"
