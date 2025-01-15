#!/bin/bash

# Copyright 2023 The Bazel Authors. All rights reserved.
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

set -euo pipefail
# --- begin runfiles.bash initialization v3 ---
# Copy-pasted from the Bazel Bash runfiles library v3.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
source "$0.runfiles/$f" 2>/dev/null || \
source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
{ echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v3 ---

function cleanup() {
  rm -rf "$1"
}

function generate_lock_file() {
  tmpdir=$(mktemp -d -t bazel.XXXXXX)
  trap 'cleanup $tmpdir' EXIT

  cd "${tmpdir}"
  touch MODULE.bazel
  echo 'common --incompatible_use_plus_in_repo_names' > .bazelrc
  echo 'common --registry=http://localhost:8000/' >> .bazelrc
  echo 'common --registry=https://bcr.bazel.build/' >> .bazelrc
  bazel=$(rlocation io_bazel/src/bazel)

  echo "Running: $bazel mod deps $@"
  $bazel mod deps "$@"
  cp ./MODULE.bazel.lock $BUILD_WORKSPACE_DIRECTORY/src/test/tools/bzlmod/MODULE.bazel.lock
}

generate_lock_file "$@"
