#!/bin/bash

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

set -eu

# This is a script which can be installed as your "bazel" binary instead of the
# real Bazel binary. When called, it tries to determine and run the correct
# Bazel version for a given project and forwards all arguments to it.
#
# You can specify which Bazel version to use using these methods:
# 1. Set $USE_BAZEL_VERSION to a version number
#    (e.g. export USE_BAZEL_VERSION=1.0.0).
# 2. Add a .bazelversion file that contains a version number next to your
#    WORKSPACE[.bazel] file.
# 3. Otherwise, the latest Bazel version will be used.
#
# This wrapper only recognizes Bazel versions installed next to itself, thus
# if you install this wrapper as /usr/bin/bazel, you'll have to install binaries
# for individual Bazel binaries as e.g. /usr/bin/bazel-1.0.0.
#
# In addition, if an executable called "tools/bazel" is found in the current
# workspace, this script will not directly execute Bazel, but instead store
# the path to the real Bazel executable in the environment variable BAZEL_REAL
# and then execute the "tools/bazel" wrapper script. The location of the wrapper
# script relative to the workspace can be changed with the $BAZEL_WRAPPER
# environment variable.
#
# In contrast to Bazelisk, this script does not download anything from the
# internet and instead relies on the local system to provide Bazel binaries.

function color() {
      # Usage: color "31;5" "string"
      # Some valid values for color:
      # - 5 blink, 1 strong, 4 underlined
      # - fg: 31 red,  32 green, 33 yellow, 34 blue, 35 purple, 36 cyan, 37 white
      # - bg: 40 black, 41 red, 44 blue, 45 purple
      printf '\033[%sm%s\033[0m\n' "$@"
}

# `readlink -f` that works on OSX too.
function get_realpath() {
    if [ "$(uname -s)" == "Darwin" ]; then
        local queue="$1"
        if [[ "${queue}" != /* ]] ; then
            # Make sure we start with an absolute path.
            queue="${PWD}/${queue}"
        fi
        local current=""
        while [ -n "${queue}" ]; do
            # Removing a trailing /.
            queue="${queue#/}"
            # Pull the first path segment off of queue.
            local segment="${queue%%/*}"
            # If this is the last segment.
            if [[ "${queue}" != */* ]] ; then
                segment="${queue}"
                queue=""
            else
                # Remove that first segment.
                queue="${queue#*/}"
            fi
            local link="${current}/${segment}"
            if [ -h "${link}" ] ; then
                link="$(readlink "${link}")"
                queue="${link}/${queue}"
                if [[ "${link}" == /* ]] ; then
                    current=""
                fi
            else
                current="${link}"
            fi
        done

        echo "${current}"
    else
        readlink -f "$1"
    fi
}

function get_workspace_root() {
  workspace_dir="${PWD}"
  while [[ "${workspace_dir}" != / ]]; do
    if [[ -e "${workspace_dir}/WORKSPACE" || -e "${workspace_dir}/WORKSPACE.bazel" || -e "${workspace_dir}/MODULE.bazel" ]]; then
      readonly workspace_dir
      return
    fi
    workspace_dir="$(dirname "${workspace_dir}")"
  done
  readonly workspace_dir=""
}

get_workspace_root

readonly wrapper_dir="$(dirname "$(get_realpath "${BASH_SOURCE[0]}")")"
readonly os_arch_suffix="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"

function get_bazel_version() {
  bazel_version=""

  if [[ -n ${USE_BAZEL_VERSION:-} ]]; then
    reason="specified in \$USE_BAZEL_VERSION"
    bazel_version="${USE_BAZEL_VERSION}"
  elif [[ -e "${workspace_dir}/.bazelversion" ]]; then
    reason="specified in ${workspace_dir}/.bazelversion"
    # The "read" can fail if the .bazelversion file does not contain a final
    # newline character. It will still correctly assign the read data to the
    # variable. Of course it can also fail due to real I/O errors. Because we do
    # validation of the read data later, we can just ignore the error here.
    # Alternatives like 'bazel_version=$(head -1 ...)' are not better either,
    # because bash does not care about the exit code of processes used in
    # command substitution.
    read -r bazel_version < "${workspace_dir}/.bazelversion" || true
  fi

  # If we read an empty string or the magic word "latest" from .bazelversion or
  # $USE_BAZEL_VERSION, then we should use the latest available stable version.
  # This mimics the behavior of Bazelisk.
  if [[ -z $bazel_version || $bazel_version == "latest" ]]; then
    if [[ -x "${wrapper_dir}/bazel-real" ]]; then
      reason="${reason:-"automatically selected bazel-real"}"
      bazel_version="real"
    else
      # Find the latest Bazel version installed on the system.
      reason="${reason:-"automatically selected latest available version"}"
      bazel_version="$(basename "$(find -H "${wrapper_dir}" -maxdepth 1 -name 'bazel-[0-9]*-${os_arch_suffix}' -type f | sort -V | tail -n 1)")"
      if [[ -z $bazel_version ]]; then
        bazel_version="$(basename "$(find -H "${wrapper_dir}" -maxdepth 1 -name 'bazel-[0-9]*' -type f | sort -V | tail -n 1)")"
      fi
      # Remove the "bazel-" prefix from the file name.
      bazel_version="${bazel_version#"bazel-"}"
    fi
  fi

  readonly reason
  readonly bazel_version
}

get_bazel_version

BAZEL_REAL="${wrapper_dir}/bazel-${bazel_version}-${os_arch_suffix}"

# Try without the architecture suffix.
if [[ ! -x ${BAZEL_REAL} ]]; then
  BAZEL_REAL="${wrapper_dir}/bazel-${bazel_version}"
fi

# Last try: Maybe `bazel-real` is actually the requested correct version?
readonly bazel_real_path="${wrapper_dir}/bazel-real"
if [[ ! -x ${BAZEL_REAL} && -x ${bazel_real_path} ]]; then
  # Note that "bazel --version" is very fast and doesn't start the Bazel server,
  # as opposed to "bazel version".
  readonly bazel_real_version="$("${bazel_real_path}" --version | grep '^bazel ' | cut -d' ' -f2)"
  if [[ $bazel_real_version == $bazel_version ]]; then
    BAZEL_REAL="${bazel_real_path}"
  fi
fi

# If the repository contains a checked-in executable set by $BAZEL_WRAPPER
# (defaults to tools/bazel), we assume that they know what they're doing and
# have their own way of versioning Bazel. Thus, we don't have to print our
# helpful messages or error out in case we couldn't find a binary.
BAZEL_WRAPPER=${BAZEL_WRAPPER:-"tools/bazel"}
readonly wrapper="${workspace_dir}/${BAZEL_WRAPPER}"
if [[ -x "$wrapper" && -f "$wrapper" ]]; then
  export BAZEL_REAL
  exec -a "$0" "${wrapper}" "$@"
fi

if [[ -z $bazel_version ]]; then
  color "31" "ERROR: No installed Bazel version found, cannot continue."
  (echo ""
  echo "Bazel binaries have to be installed in ${wrapper_dir}, but none were found.") 2>&1
  exit 1
fi

if [[ ! -x $BAZEL_REAL ]]; then
  color "31" "ERROR: The project you're trying to build requires Bazel ${bazel_version} (${reason}), but it wasn't found in ${wrapper_dir}."

  long_binary_name="bazel-${bazel_version}-${os_arch_suffix}"

  if [[ -x $(command -v apt-get) && $wrapper_dir == "/usr/bin" ]]; then
    (echo ""
    echo "You can install the required Bazel version via apt:"
    echo "  sudo apt update && sudo apt install bazel-${bazel_version}"
    echo ""
    echo "If this doesn't work, check Bazel's installation instructions for help:"
    echo "  https://bazel.build/install/ubuntu") 2>&1
  else
    (echo ""
    echo "Bazel binaries for all official releases can be downloaded from here:"
    echo "  https://github.com/bazelbuild/bazel/releases") 2>&1

    if [[ -x $(command -v curl) && -w $wrapper_dir ]]; then
      (echo ""
      echo "You can download the required version directly using this command:"
      echo "  (cd \"${wrapper_dir}\" && curl -fLO https://releases.bazel.build/${bazel_version}/release/${long_binary_name} && chmod +x ${long_binary_name} && cd - )") 2>&1
    elif [[ -x $(command -v wget) && -w $wrapper_dir ]]; then
      (echo ""
      echo "You can download the required version directly using this command:"
      echo "  (cd \"${wrapper_dir}\" && wget https://releases.bazel.build/${bazel_version}/release/${long_binary_name} && chmod +x ${long_binary_name} && cd - )") 2>&1
    else
      (echo ""
      echo "Please put the downloaded Bazel binary into this location:"
      echo "  ${wrapper_dir}/${long_binary_name}") 2>&1
    fi
  fi
  exit 1
fi

exec -a "$0" "${BAZEL_REAL}" "$@"
