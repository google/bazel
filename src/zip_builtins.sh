#!/usr/bin/env bash

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

# Packages the builtins bzl files as a zip, while preserving their directory
# structure and expanding any symlinks.
#
# Usage:
#     zip_builtins.sh zip output builtins_root files...

set -euo pipefail

origdir="$(pwd)"

# the "zip" command to use; system "zip" if empty
if [ -z "$1" ]; then
    zip="zip"
else
    zip=$origdir/$1;
fi; shift
output=$origdir/$1; shift     # zip file to write
builtins_root=$1; shift       # root-relative path to builtins_bzl source dir
# "$@" contains the paths of the files to archive. They must all be under the
# source root (no generated files as inputs).

TMPDIR="$(mktemp -d "${TMPDIR:-/tmp}/tmp.XXXXXXXXXX")"
trap "rm -rf $TMPDIR" EXIT

# Do the cp in origdir so $@ resolves. We'd like to use cp --parents, but macOS
# doesn't have that flag, so break it out into a loop.
mkdir -p "$TMPDIR/staging"
for src in "$@"; do
    src_dir=$(dirname $src)
    src_file=$(basename $src)
    # Strip 'zip.' from file names (present to allow BUILD files)
    dst="$TMPDIR/staging/$src_dir/${src_file#zip.}"
    mkdir -p $(dirname "$dst")
    # Make certain to expand any symlinked files (-L).
    cp -L "$src" "$dst"
done

cd "$TMPDIR"

# Strip the dir prefix leading up to the builtins root.
mv "staging/$builtins_root" builtins_bzl

# The zip step must take place while cwd is tmpdir, so the paths in the zipfile
# are relative to tmpdir.
#
# For determinism, sort the files and zero out their timestamps before zipping.
find builtins_bzl -type f -print0 | xargs -0 touch -t 198001010000.00
find builtins_bzl -type f | sort | "$zip" -qDX0r@ "$output" builtins_bzl
