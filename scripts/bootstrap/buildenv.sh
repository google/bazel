#!/bin/bash

# Copyright 2015 The Bazel Authors. All rights reserved.
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

# General purpose method and values for bootstrapping bazel.

set -o errexit

# Check if all necessary tools are available.
# List: https://github.com/bazelbuild/bazel/issues/7641#issuecomment-472344261
for tool in basename cat chmod comm cp dirname find grep ln ls mkdir mktemp \
            readlink rm sed sort tail touch tr uname unzip which; do
  if ! hash "$tool" >/dev/null; then
    echo >&2 "ERROR: cannot find \"$tool\"; check your PATH."
    echo >&2 "       You may need to run the following command or similar:"
    echo >&2 "         export PATH=\"/bin:/usr/bin:\$PATH\""
    exit 1
  fi
done

# Ensure Python is on the PATH on Windows, otherwise we would see
# "LAUNCHER ERROR" messages from py_binary exe launchers.
case "$(uname -s | tr "[:upper:]" "[:lower:]")" in
msys*|mingw*|cygwin*)
  # Ensure Python is on the PATH, otherwise the bootstrapping fails later.
  if ! hash python.exe >/dev/null; then
    echo >&2 "ERROR: cannot locate python.exe; check your PATH."
    echo >&2 "       You may need to run the following command, or something"
    echo >&2 "       similar, depending on where you installed Python:"
    echo >&2 "         export PATH=\"/c/Python27:\$PATH\""
    exit 1
  fi
  # Ensure TMPDIR uses the user-specified TMPDIR or TMP or TEMP.
  # This is necessary to avoid overly longs paths during bootstrapping, see for
  # example https://github.com/bazelbuild/bazel/issues/4536
  export TMPDIR="${TMPDIR:-${TMP:-${TEMP:-}}}"
esac

# If BAZEL_WRKDIR is set, default all variables to point into
# that directory

if [ -n "${BAZEL_WRKDIR}" ] ; then
 mkdir -p "${BAZEL_WRKDIR}/tmp"
 mkdir -p "${BAZEL_WRKDIR}/user_root"
 : ${TMPDIR:=${BAZEL_WRKDIR}/tmp}
 export TMPDIR
 : ${BAZEL_DIR_STARTUP_OPTIONS:="--output_user_root=${BAZEL_WRKDIR}/user_root"}
fi


# We define the fail function early so we can use it when detecting the JDK
# See https://github.com/bazelbuild/bazel/issues/2949,
function fail() {
  local exitCode=$?
  if [[ "$exitCode" = "0" ]]; then
    exitCode=1
  fi
  echo >&2
  echo "ERROR: $*" >&2
  exit $exitCode
}


# Set standard variables
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
WORKSPACE_DIR="$(dirname "$(dirname "${DIR}")")"

JAVA_VERSION=${JAVA_VERSION:-21}
BAZELRC=${BAZELRC:-"/dev/null"}
PLATFORM="$(uname -s | tr 'A-Z' 'a-z')"

PATHSEP=":"
case "${PLATFORM}" in
linux)
  # JAVA_HOME must point to a Java installation.
  JAVA_HOME="${JAVA_HOME:-$(readlink -f $(which javac) | sed 's_/bin/javac__')}"
  ;;

freebsd)
  # JAVA_HOME must point to a Java installation.
  JAVA_HOME="${JAVA_HOME:-/usr/local/openjdk11}"
  ;;

openbsd)
  # JAVA_HOME must point to a Java installation.
  JAVA_HOME="${JAVA_HOME:-/usr/local/jdk-11}"
  ;;

darwin)
  if [[ -z "$JAVA_HOME" ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v ${JAVA_VERSION}+ 2> /dev/null)" \
      || fail "Could not find JAVA_HOME, please ensure a JDK (version ${JAVA_VERSION}+) is installed."
  fi
  ;;

msys*|mingw*|cygwin*)
  # Use a simplified platform string.
  PLATFORM="windows"
  PATHSEP=";"
  # Find the latest available version of the SDK.
  JAVA_HOME="${JAVA_HOME:-$(ls -d C:/Program\ Files/Java/jdk* | sort | tail -n 1)}"
  # Replace backslashes with forward slashes.
  JAVA_HOME="${JAVA_HOME//\\//}"
esac

EXE_EXT=""
if [ "${PLATFORM}" == "windows" ]; then
  # Extension for executables.
  EXE_EXT=".exe"

  # Fix TMPDIR on windows
  default_tmp=${TMP:-$(cygpath -mO)/Temp}
  TMPDIR=$(cygpath -ml "${TMPDIR:-$default_tmp}")
fi

# Whether we display build messages or not.  We set this conditionally because
# the file including us or the user may already have defined VERBOSE to their
# liking.
: ${VERBOSE:=yes}

# List of functions to invoke on exit.
ATEXIT_HANDLERS=

# Registers a function to be invoked on exit.
#
# The handlers will be invoked at exit time in the order they were registered.
# See comments in run_atexit for more details.
function atexit() {
  local handler="${1}"; shift

  [ -n "${ATEXIT_HANDLERS}" ] || trap 'run_atexit_handlers $?' EXIT
  ATEXIT_HANDLERS="${ATEXIT_HANDLERS} ${handler}"
}

# Exit routine to run all registered atexit handlers.
#
# If the program exited with an error, this exit routine will also exit with the
# same error.  However, if the program exited successfully, this exit routine
# will only exit successfully if the atexit handlers succeed.
function run_atexit_handlers() {
  local exit_code="$?"

  local failed=no
  for handler in ${ATEXIT_HANDLERS}; do
    eval "${handler}" || failed=yes
  done

  trap - EXIT  # Reset exit handler to prevent double execution.
  if [ ${exit_code} -ne 0 ]; then
    exit ${exit_code}
  else
    if [ "${failed}" = yes ]; then
      echo "Program tried to exit successfully but atexit routines failed" 1>&2
      exit 1
    else
      exit 0
    fi
  fi
}

function tempdir() {
  local tmp=${TMPDIR:-/tmp}
  mkdir -p "${tmp}"
  local DIR="$(mktemp -d "${tmp%%/}/bazel_XXXXXXXX")"
  mkdir -p "${DIR}"
  local DIRBASE=$(basename "${DIR}")
  eval "cleanup_tempdir_${DIRBASE}() { rm -rf '${DIR}' >&/dev/null || true ; }"
  atexit cleanup_tempdir_${DIRBASE}
  NEW_TMPDIR="${DIR}"
}
tempdir
OUTPUT_DIR=${NEW_TMPDIR}
phasefile=${OUTPUT_DIR}/phase
function cleanup_phasefile() {
  if [ -f "${phasefile}" ]; then
    echo 1>&2;
    cat "${phasefile}" 1>&2;
  fi;
}

atexit cleanup_phasefile

# Executes a command respecting the current verbosity settings.
#
# If VERBOSE is yes, the command itself and its output are printed.
# If VERBOSE is no, the command's output is only displayed in case of failure.
#
# Exits the script if the command fails.
function run() {
  if [ "${VERBOSE}" = yes ]; then
    echo "${@}"
    "${@}" || exit $?
  else
    local errfile="${OUTPUT_DIR}/errors"

    echo "${@}" >"${errfile}"
    if ! "${@}" >>"${errfile}" 2>&1; then
      local exitcode=$?
      cat "${errfile}" 1>&2
      exit $exitcode
    fi
  fi
}

function display() {
  if [[ -z "${QUIETMODE}" ]]; then
    echo -e "$@" >&2
  fi
}

function log() {
  echo -n "." >&2
  echo "$1" >${phasefile}
}

function clear_log() {
  echo >&2
  rm -f ${phasefile}
}

LEAVES="\xF0\x9F\x8D\x83"
INFO="\033[32mINFO\033[0m:"
WARNING="\033[31mWARN\033[0m:"

first_step=1
function new_step() {
  rm -f ${phasefile}
  local new_line=
  if [ -n "${first_step}" ]; then
    first_step=
  else
    new_line="\n"
  fi
  if [ -t 2 ]; then
    display -n "$new_line$LEAVES  $1"
  else
    display -n "$new_line$1"
  fi
}

function git_sha1() {
  if [ -x "$(which git 2>/dev/null)" ] && [ -d .git ]; then
    git rev-parse --short HEAD 2>/dev/null || true
  fi
}

function git_date() {
  if [ -x "$(which git 2>/dev/null)" ] && [ -d .git ]; then
    git log -1 --pretty=%ai | cut -d " " -f 1 || true
  fi
}

# Get the latest release version and append the date of
# the last commit if any.
function get_last_version() {
  if [ -f "MODULE.bazel" ]; then
    local version=$(grep "version =" MODULE.bazel | head -n 1 | sed 's/.*version = "\(.*\)".*/\1/' | cut -d '"' -f2)
  else
    local version=""
  fi

  local date="$(git_date)"
  if [ -z "${version-}" ]; then
    version="unknown"
  fi
  if [ -n "${date-}" ]; then
    date="$(date +%Y-%m-%d)"
  fi
  echo "${version}-${date}"
}

if [[ ${PLATFORM} == "darwin" ]]; then
  function md5_file() {
    echo $(cat $1 | md5) $1
  }
else
  function md5_file() {
    md5sum $1
  }
fi

# Gets the java version from JAVA_HOME
# Sets JAVAC and JAVAC_VERSION with respectively the path to javac and
# the version of javac.
function get_java_version() {
  test -z "$JAVA_HOME" && fail "JDK not found, please set \$JAVA_HOME."
  JAVAC="${JAVA_HOME}/bin/javac"
  [[ -x "${JAVAC}" ]] \
    || fail "JAVA_HOME ($JAVA_HOME) is not a path to a working JDK."

  JAVAC_VERSION=$("${JAVAC}" -version 2>&1)
  if [[ "$JAVAC_VERSION" =~ javac\ ((1\.)?([789]|[1-9][0-9])).*$ ]]; then
    JAVAC_VERSION=1.${BASH_REMATCH[3]}
  else
    fail \
      "Cannot determine JDK version, please set \$JAVA_HOME.\n" \
      "\$JAVAC_VERSION is \"${JAVAC_VERSION}\""
  fi
}

# Return the target that a bind point to, using Bazel query.
function get_bind_target() {
  $BAZEL --bazelrc=${BAZELRC} ${BAZEL_DIR_STARTUP_OPTIONS} \
    query "deps($1, 1) - $1"
}

# Create a link for a directory on the filesystem
function link_dir() {
  local source=$1
  local dest=$2

  if [[ "${PLATFORM}" == "windows" ]]; then
    local -r s="$(cygpath -w "$source")"
    local -r d="$(cygpath -w "$dest")"
    powershell -command "New-Item -ItemType Junction -Path '$d' -Value '$s'"
  else
    ln -s "${source}" "${dest}"
  fi
}

function link_file() {
  local source=$1
  local dest=$2

  if [[ "${PLATFORM}" == "windows" ]]; then
    # Attempt creating a symlink to the file. This is supported without
    # elevation (Administrator privileges) on Windows 10 version 1709 when
    # Developer Mode is enabled.
    local -r s="$(cygpath -w "$source")"
    local -r d="$(cygpath -w "$dest")"
    if ! powershell -command "New-Item -ItemType SymbolicLink -Path '$d' -Value '$s'"; then
      # If the previous call failed to create a symlink, just copy the file.
      cp "$source" "$dest"
    fi
  else
    ln -s "${source}" "${dest}"
  fi
}

# Link direct children (subdirectories and files) of a directory.
# Usage:
#   link_children "$PWD" "tools" "${BAZEL_TOOLS_REPO}"
# This creates:
#   ${BAZEL_TOOLS_REPO}/tools/android -> $PWD/tools/android
#   ${BAZEL_TOOLS_REPO}/tools/bash -> $PWD/tools/bash
#   ... and so on for all files and directories directly under "tools".
function link_children() {
  local -r source_dir=${1%/}
  local -r source_subdir=${2%/}
  local -r dest_dir=${3%/}

  for e in $(find "${source_dir}/${source_subdir}" -mindepth 1 -maxdepth 1 -type d); do
    local dest_path="${dest_dir}/${e#$source_dir/}"
    if [[ ! -d "$dest_path" ]]; then
      link_dir "$e" "$dest_path"
    fi
  done
  for e in $(find "${source_dir}/${source_subdir}" -mindepth 1 -maxdepth 1 -type f); do
    local dest_path="${dest_dir}/${e#$source_dir/}"
    if [[ ! -f "$dest_path" ]]; then
      link_file "$e" "$dest_path"
    fi
  done
}
