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
# Test related to @platforms embedded repository
#

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

function test_platforms_repository_builds_itself() {
  # We test that a built-in @platforms repository is buildable.
  bazel build @@platforms//:all &> $TEST_log \
      || fail "Build failed unexpectedly"
}

function test_platform_accessor() {
  add_platforms "MODULE.bazel"
  cat > rules.bzl <<'EOF'
def _impl(ctx):
  platform = ctx.attr.platform[platform_common.PlatformInfo]
  label = platform.label
  print("The label is:", label)
  return []

print_props = rule(
  implementation = _impl,
  attrs = {
      'platform': attr.label(providers = [platform_common.PlatformInfo]),
  }
)
EOF
  cat > BUILD << 'EOF'
load("//:rules.bzl", "print_props")

print_props(
    name = "a",
    platform = ":my_platform",
)

platform(
    name = "my_platform",
)
EOF

  bazel build --experimental_platforms_api=true :a &> $TEST_log || fail "Build failed"
  expect_log 'The label is: //:my_platform'
}

run_suite "platform repo test"

