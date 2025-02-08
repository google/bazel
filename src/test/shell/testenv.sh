#!/bin/bash
#
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
#
# Testing environment for the Bazel integration tests
#
# TODO(bazel-team): This file is currently an append of the old testenv.sh and
# test-setup.sh files. This must be cleaned up eventually.

# TODO(bazel-team): Factor each test suite's is-this-windows setup check to use
# this var instead, or better yet a common $IS_WINDOWS var.
PLATFORM="$(uname -s | tr [:upper:] [:lower:])"

function is_darwin() {
  [[ "${PLATFORM}" =~ darwin ]]
}

function is_windows() {
  [[ "${PLATFORM}" =~ msys ]]
}

function _log_base() {
  prefix=$1
  shift
  echo >&2 "${prefix}[$(basename "$0") $(date "+%Y-%m-%d %H:%M:%S (%z)")] $*"
}

function log_info() {
  _log_base "INFO" "$@"
}

function log_fatal() {
  _log_base "ERROR" "$@"
  exit 1
}

if ! type rlocation &> /dev/null; then
  log_fatal "rlocation() is undefined"
fi

# Set some environment variables needed on Windows.
if is_windows; then
  # TODO(philwo) remove this once we have a Bazel release that includes the CL
  # moving the Windows-specific TEST_TMPDIR into TestStrategy.
  TEST_TMPDIR_BASENAME="$(basename "$TEST_TMPDIR")"

  export JAVA_HOME="${JAVA_HOME:-$(ls -d C:/Program\ Files/Java/jdk* | sort | tail -n 1)}"
  export BAZEL_SH="$(cygpath -m /usr/bin/bash)"

  # Disable MSYS path conversion that converts path-looking command arguments to
  # Windows paths (even if they arguments are not in fact paths).
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

# Make the command "bazel" available for tests.
if [ -z "${BAZEL_SUFFIX:-}" ]; then
  PATH_TO_BAZEL_BIN=$(rlocation "io_bazel/src/bazel")
  PATH_TO_BAZEL_WRAPPER="$(dirname $(rlocation "io_bazel/src/test/shell/bin/bazel"))"
else
  DIR_OF_BAZEL_BIN="$(dirname $(rlocation "io_bazel/src/bazel${BAZEL_SUFFIX}"))"
  ln -s "${DIR_OF_BAZEL_BIN}/bazel${BAZEL_SUFFIX}" "${DIR_OF_BAZEL_BIN}/bazel"
  PATH_TO_BAZEL_WRAPPER="$(dirname $(rlocation "io_bazel/src/test/shell/bin/bazel${BAZEL_SUFFIX}"))"
  ln -s "${PATH_TO_BAZEL_WRAPPER}/bazel${BAZEL_SUFFIX}" "${PATH_TO_BAZEL_WRAPPER}/bazel"
  PATH_TO_BAZEL_BIN="${DIR_OF_BAZEL_BIN}/bazel"
fi
# Convert PATH_TO_BAZEL_WRAPPER to Unix path style on Windows, because it will be
# added into PATH. There's problem if PATH=C:/msys64/usr/bin:/usr/local,
# because ':' is used as both path separator and in C:/msys64/...
case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  PATH_TO_BAZEL_WRAPPER="$(cygpath -u "$PATH_TO_BAZEL_WRAPPER")"
esac
[ ! -f "${PATH_TO_BAZEL_WRAPPER}/bazel" ] \
  && log_fatal "Unable to find the Bazel binary at $PATH_TO_BAZEL_WRAPPER/bazel"
export PATH="$PATH_TO_BAZEL_WRAPPER:$PATH"

################### shell/bazel/testenv ##################################
# Setting up the environment for Bazel integration tests.
#
[ -z "$TEST_SRCDIR" ] && log_fatal "TEST_SRCDIR not set!"
BAZEL_RUNFILES="$TEST_SRCDIR/_main"

# WORKSPACE file
workspace_file="${BAZEL_RUNFILES}/WORKSPACE"

# Where to register toolchains
TOOLCHAIN_REGISTRATION_FILE="MODULE.bazel"

# Tools directory location
tools_dir="$(dirname $(rlocation io_bazel/tools/BUILD))"

# Platforms
default_host_platform="@@platforms//host:host"

# Sandbox tools
process_wrapper="${BAZEL_RUNFILES}/src/main/tools/process-wrapper"
linux_sandbox="${BAZEL_RUNFILES}/src/main/tools/linux-sandbox"

# Test data
testdata_path=${BAZEL_RUNFILES}/src/test/shell/bazel/testdata
python_server="$(rlocation io_bazel/src/test/shell/bazel/testing_server.py)"

# Third-party
protoc_compiler="${BAZEL_RUNFILES}/src/test/shell/integration/protoc"

# Skylib
skylib_package="@bazel_skylib//"

if [ -z ${RUNFILES_MANIFEST_ONLY+x} ]; then
  junit_jar="${BAZEL_RUNFILES}/src/test/shell/bazel/junit.jar"
  hamcrest_jar="${BAZEL_RUNFILES}/src/test/shell/bazel/hamcrest.jar"
else
  junit_jar=$(rlocation io_bazel/src/test/shell/bazel/junit.jar)
  hamcrest_jar=$(rlocation io_bazel/src/test/shell/bazel/hamcrest.jar)
fi


# This function copies the tools directory from Bazel.
function copy_tools_directory() {
  cp -RL ${tools_dir}/* tools
  if [ -f tools/jdk/BUILD ]; then
    chmod +w tools/jdk/BUILD
  fi
  if [ -f tools/build_defs/repo/BUILD.repo ]; then
      cp tools/build_defs/repo/BUILD.repo tools/build_defs/repo/BUILD
  fi

  chmod -R +w .
}

# Report whether a given directory name corresponds to a tools directory.
function is_tools_directory() {
  case "$1" in
    third_party|tools|src)
      true
      ;;
    *)
      false
      ;;
  esac
}

# Copy the examples of the base workspace
function copy_examples() {
  EXAMPLE="$(cd $(dirname $(rlocation io_bazel/examples/cpp/BUILD))/..; pwd)"
  cp -RL ${EXAMPLE} .
  chmod -R +w .
}

#
# Find a random unused TCP port
#
pick_random_unused_tcp_port () {
    perl -MSocket -e '
sub CheckPort {
  my ($port) = @_;
  socket(TCP_SOCK, PF_INET, SOCK_STREAM, getprotobyname("tcp"))
    || die "socket(TCP): $!";
  setsockopt(TCP_SOCK, SOL_SOCKET, SO_REUSEADDR, 1)
    || die "setsockopt(TCP): $!";
  return 0 unless bind(TCP_SOCK, sockaddr_in($port, INADDR_ANY));
  socket(UDP_SOCK, PF_INET, SOCK_DGRAM, getprotobyname("udp"))
    || die "socket(UDP): $!";
  return 0 unless bind(UDP_SOCK, sockaddr_in($port, INADDR_ANY));
  return 1;
}
for (1 .. 128) {
  my ($port) = int(rand() * 27000 + 32760);
  if (CheckPort($port)) {
    print "$port\n";
    exit 0;
  }
}
print "NO_FREE_PORT_FOUND\n";
exit 1;
'
}

#
# A uniform SHA-256 command that works across platforms.
#
# sha256sum is the fastest option, but may not be available on macOS (where it
# is usually called 'gsha256sum'), so we optionally fallback to shasum.
#
if hash sha256sum 2>/dev/null; then
  :
elif hash gsha256sum 2>/dev/null; then
  function sha256sum() {
    gsha256sum "$@"
  }
elif hash shasum 2>/dev/null; then
  function sha256sum() {
    shasum -a 256 "$@"
  }
else
  echo "testenv.sh: Could not find either sha256sum or gsha256sum or shasum in your PATH."
  exit 1
fi

################### shell/bazel/test-setup ###############################
# Setup bazel for integration tests
#

if [[ "$TEST_TMPDIR" =~ ^/tmp/bazel-working-directory/ ]]; then
  RUNNING_IN_BAZEL_SANDBOX=1
else
  RUNNING_IN_BAZEL_SANDBOX=0
fi

if [[ "$RUNNING_IN_BAZEL_SANDBOX" == 1 ]]; then
  # If we are running under the Bazel sandbox an output user root under
  # $TEST_TMPDIR is not quite enough because that's under
  # /tmp-bazel-working-directory, which is going to be overridden by the sandbox
  # in which the actions of the inner Bazel instance run.
  #
  # So put the output user root under /tmp instead, which requires an additional
  # --sandbox_add_mount_pair option but is the only place other than
  # $TEST_TMPDIR where we are guaranteed to be able to write.
  bazel_root="/tmp/output_user_root"
elif is_windows; then
  # Create a shorter bazel root on Windows to avoid long path issue.
  mkdir -p C:/tmp
  bazel_root=$(mktemp -d "C:/tmp/bazel_root_XXXXXX")
else
  # OS X has a limit in the pipe length, so force the root to a shorter one
  bazel_root="${TEST_TMPDIR}/root"
fi

# Delete stale installation directory from previously failed tests. On Windows
# we regularly get the same TEST_TMPDIR but a failed test may only partially
# clean it up, and the next time the test runs, Bazel reports a corrupt
# installation error. See https://github.com/bazelbuild/bazel/issues/3618
rm -rf "${bazel_root}"
mkdir -p "${bazel_root}"

log_info "bazel binary is at $PATH_TO_BAZEL_WRAPPER"

# Here we unset variable that were set by the invoking Blaze instance
unset JAVA_RUNFILES

# Runs a command, retrying if needed for a fixed timeout.
#
# Necessary to use it on Windows, typically when deleting directory trees,
# because the OS cannot delete open files, which we attempt to do when deleting
# workspaces where a Bazel server is still in the middle of shutting down.
# (Because "bazel shutdown" returns sooner than the server actually shuts down.)
function try_with_timeout() {
  for i in {1..120}; do
    if $* ; then
      break
    fi
    if (( i == 10 )) || (( i == 30 )) || (( i == 60 )) ; then
      log_info "try_with_timeout($*): no success after $i seconds" \
               "(timeout in $((120-i)) seconds)"
    fi
    sleep 1
  done
}

function setup_localjdk_javabase() {
  if is_windows; then
    jdk_binary=local_jdk/bin/java.exe
  else
    jdk_binary=local_jdk/bin/java
  fi
  jdk_binary_rlocation=$(rlocation ${jdk_binary})
  if [[ -z "${jdk_binary_rlocation}" ]]; then
    echo "error: failed to find $jdk_binary, make sure you have java \
installed or pass --java_runtime_verison=XX with the correct version" >&2
  fi
  if is_windows; then
    jdk_dir="$(cygpath -m $(cd ${jdk_binary_rlocation}/../..; pwd))"
  else
    jdk_dir="$(dirname $(dirname ${jdk_binary_rlocation}))"
  fi
  bazel_javabase="${jdk_dir}"
}

function setup_bazelrc() {
  cat >$TEST_TMPDIR/bazelrc <<EOF
# Set the user root properly for this test invocation.
startup --output_user_root=${bazel_root}

# Print all progress messages because we regularly grep the output in tests.
common --show_progress_rate_limit=-1

# Disable terminal-specific features.
common --color=no --curses=no

# Prevent SIGBUS during JVM actions.
build --sandbox_tmpfs_path=/tmp

build --incompatible_skip_genfiles_symlink=false

build --incompatible_use_toolchain_resolution_for_java_rules

# Enable Bzlmod in all shell integration tests
common --enable_bzlmod

# Disable WORKSPACE in all shell integration tests
common --noenable_workspace

# Verify compatibility before the flip (https://github.com/bazelbuild/bazel/issues/12821)
common --nolegacy_external_runfiles

# Support JDK 21, data dependencies that get compiled and used tools need to be
# run with 21 runtime.
build --java_runtime_version=21
build --tool_java_runtime_version=21

${EXTRA_BAZELRC:-}
EOF

  if [[ "$RUNNING_IN_BAZEL_SANDBOX" == 1 ]]; then
    # If both the outer and the inner Bazel instances use the Linux sandbox,
    # $TEST_TMPDIR for the outer one will be under /tmp/bazel-working-directory.
    # The inner one mounts a fresh empty directory under /tmp so we need to
    # explicitly tell it that it should make /tmp/output_user_root from the
    # outer sandbox available also in the inner sandbox also because a lot the
    # input files for the inner actions will be symlinks to there.
    cat >> "$TEST_TMPDIR/bazelrc" <<EOF
build --sandbox_add_mount_pair=${bazel_root}
EOF
  fi

  if [[ -n ${REPOSITORY_CACHE:-} ]]; then
    echo "testenv.sh: Using repository cache at $REPOSITORY_CACHE."
    echo "common --repository_cache=$REPOSITORY_CACHE" >> $TEST_TMPDIR/bazelrc
    # TODO: Remove this flag once all dependencies are mirrored.
    # See https://github.com/bazelbuild/bazel/pull/19549 for more context.
    echo "common --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0" >> $TEST_TMPDIR/bazelrc
    if is_darwin; then
      # For reducing SSD usage on our physical Mac machines.
      echo "testenv.sh: Enabling --experimental_repository_cache_hardlinks"
      echo "common --experimental_repository_cache_hardlinks" >> $TEST_TMPDIR/bazelrc
    fi
  fi

  if [[ -n ${TEST_INSTALL_BASE:-} ]]; then
    echo "testenv.sh: Using shared install base at $TEST_INSTALL_BASE."
    echo "startup --install_base=$TEST_INSTALL_BASE" >> $TEST_TMPDIR/bazelrc
  fi

  if is_darwin; then
    echo "Add flags to prefer ipv6 network"
    echo "startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true" >> $TEST_TMPDIR/bazelrc
    echo "build --jvmopt=-Djava.net.preferIPv6Addresses" >> $TEST_TMPDIR/bazelrc
  fi
}

function enable_disk_cache() {
  echo "common --disk_cache=$TEST_TMPDIR/disk_cache" >> $TEST_TMPDIR/bazelrc
}

function setup_android_sdk_support() {
  # Required for runfiles library on Windows, since $(rlocation) lookups
  # can't do directories. We use android-28's android.jar as the anchor
  # for the androidsdk location.
  local android_jar=$(rlocation androidsdk/platforms/android-28/android.jar)
  local android=$(dirname $android_jar)
  local platforms=$(dirname $android)
  local local_android_sdk=$(dirname $platforms)
  if [[ "$RUNNING_IN_BAZEL_SANDBOX" == 1 ]]; then
    ANDROID_SDK="/tmp/androidsdk"
    if [[ ! -d "$ANDROID_SDK" ]]; then
      # If this test is running in a Bazel sandbox, $local_android_sdk will be
      # under /tmp/bazel-working-directory which is going to replaced by the
      # working directory of the inner test, so we need to copy it somewhere
      # else. We can't use the outer /tmp/bazel-execroot, either, because the
      # same applies to it.

      # -L is to dereference symbolic links. Otherwise, we'd get symlinks to
      # /tmp/bazel-source-roots/ under $ANDROID_SDK, which reference the source
      # roots of the outer Bazel sandbox which are duly overriden by the inner
      # one.
      cp -LR "$local_android_sdk" "$ANDROID_SDK"
    fi

    # /tmp is overwritten in the inner sandbox by an empty directory so we need
    # to explicitly make it available there
    cat >>$TEST_TMPDIR/bazelrc <<EOF
build --sandbox_add_mount_pair="$ANDROID_SDK"
EOF
  else
    # No pesky outer sandbox, we can use the android SDK as this test sees it
    ANDROID_SDK="$local_android_sdk"
  fi

  cat >> WORKSPACE <<EOF
android_sdk_repository(
    name = "androidsdk",
    path = "$ANDROID_SDK",
)
register_toolchains("//tools/android:all")
EOF

  setup_android_platforms
}

# Sets up sufficient platform definitions to support Android shell tests using
# platform-based toolchain resolution.
#
# See resolve_android_toolchains in
# src/test/shell/bazel/android/android_helper.sh for how to platform-based
# toolchain resolution.
function setup_android_platforms() {
  mkdir -p test_android_platforms

  cat > test_android_platforms/BUILD <<EOF
package(default_visibility = ["//visibility:public"])

platform(
    name = "simple",
    constraint_values = [
        "@platforms//os:android",
        "@platforms//cpu:armv7",
    ],
)

platform(
    name = "armeabi-v7a",
    constraint_values = [
        "@platforms//os:android",
        "@platforms//cpu:armv7",
    ],
)

platform(
    name = "x86",
    constraint_values = [
        "@platforms//os:android",
        "@platforms//cpu:x86_32",
    ],
)
EOF

  add_to_bazelrc "build --platform_mappings=test_android_platforms/mappings"
  cat > test_android_platforms/mappings <<EOF
platforms:
  //test_android_platforms:simple
    --cpu=armeabi-v7a
flags:
  --cpu=armeabi-v7a
    //test_android_platforms:simple
EOF
}

function setup_javatest_common() {
  # TODO(bazel-team): we should use remote repositories.
  mkdir -p third_party
  if [ ! -f third_party/BUILD ]; then
    cat <<EOF >third_party/BUILD
package(default_visibility = ["//visibility:public"])
EOF
  fi

  [ -e third_party/junit.jar ] || ln -s ${junit_jar} third_party/junit.jar
  [ -e third_party/hamcrest.jar ] \
    || ln -s ${hamcrest_jar} third_party/hamcrest.jar
}

function setup_javatest_support() {
  setup_javatest_common
  grep -q 'name = "junit4"' third_party/BUILD \
    || cat <<EOF >>third_party/BUILD
java_import(
    name = "junit4",
    jars = [
        "junit.jar",
        "hamcrest.jar",
    ],
)
EOF
}

# If the current platform is Windows, defines a Python toolchain for our
# Windows CI machines. Otherwise does nothing.
#
# Our Windows CI machines have Python 2 and 3 installed at C:\Python2 and
# C:\Python3 respectively.
#
# Since the tools directory is not cleared between test cases, this only needs
# to run once per suite. However, the toolchain must still be registered
# somehow.
#
# TODO(#7844): Delete this custom (and machine-specific) test setup once we have
# an autodetecting Python toolchain for Windows.
function maybe_setup_python_windows_tools() {
  if [[ ! $PLATFORM =~ msys ]]; then
    return
  fi

  mkdir -p tools/python/windows
  cat > tools/python/windows/BUILD << EOF
load("@rules_python//python:py_runtime_pair.bzl", "py_runtime_pair")

package(default_visibility = ["//visibility:public"])

py_runtime(
  name = "py3_runtime",
  interpreter_path = r"C:\Python3\python.exe",
  python_version = "PY3",
)

py_runtime_pair(
  name = "py_runtime_pair",
  py3_runtime = ":py3_runtime",
)

toolchain(
  name = "py_toolchain",
  toolchain = ":py_runtime_pair",
  toolchain_type = "@bazel_tools//tools/python:toolchain_type",
  target_compatible_with = ["@platforms//os:windows"],
)
EOF
}

function setup_starlark_javatest_support() {
  setup_javatest_common
  grep -q "name = \"junit4-jars\"" third_party/BUILD \
    || cat <<EOF >>third_party/BUILD
filegroup(
    name = "junit4-jars",
    srcs = [
        "junit.jar",
        "hamcrest.jar",
    ],
)
EOF
}

# Sets up Objective-C tools. Mac only.
function setup_objc_test_support() {
  IOS_SDK_VERSION=$(xcrun --sdk iphoneos --show-sdk-version)
}

# Extract the module version used in the default lock file.
function get_version_from_default_lock_file() {
  lockfile=$(rlocation io_bazel/src/test/tools/bzlmod/MODULE.bazel.lock)
  module=$1
  local version=$(sed -n "s|.*modules/$module/\([^/]*\)/source\.json.*|\1|p" "$lockfile")
  if [[ -z $version ]]; then
      log_fatal "Version not found for module $module in $lockfile"
  else
      echo "$version"
  fi
}

function add_bazel_dep() {
  version=$(get_version_from_default_lock_file "$1")
  cat >> "$2" <<EOF
bazel_dep(name = "$1", version = "$version")
EOF
}

function add_platforms() {
  add_bazel_dep "platforms" "$1"
}

function add_bazel_skylib() {
  add_bazel_dep "bazel_skylib" "$1"
}

function add_rules_cc() {
  add_bazel_dep "rules_cc" "$1"
}

function add_rules_java() {
  add_bazel_dep "rules_java" "$1"
}

function add_rules_python() {
  add_bazel_dep "rules_python" "$1"
}

function add_rules_license() {
  add_bazel_dep "rules_license" "$1"
}

function add_protobuf() {
  version=$(get_version_from_default_lock_file "protobuf")
  cat >> "$1" <<EOF
bazel_dep(name = "protobuf", version = "$version", repo_name = "com_google_protobuf")
EOF
}

function add_rules_testing() {
  # Keep the version the same as the one in the root MODULE.bazel file.
  cat >> "$1" <<EOF
bazel_dep(name = "rules_testing", version = "0.6.0")
EOF
}

# Set up MODULE.bazel and MODULE.bazel.lock to avoid accessing BCR for tests with a clean workspace.
# Note: this function echos the MODULE.bazel file path.
function setup_module_dot_bazel() {
  module_dot_bazel=${1:-MODULE.bazel}
  cat > $module_dot_bazel <<EOF
module(name = 'test')
EOF
  cp -f $(rlocation io_bazel/src/test/tools/bzlmod/MODULE.bazel.lock) "$(dirname ${module_dot_bazel})/MODULE.bazel.lock"
  echo $module_dot_bazel
}

workspaces=()
# Set-up a new, clean workspace with only the tools installed.
function create_new_workspace() {
  new_workspace_dir=${1:-$(mktemp -d ${TEST_TMPDIR}/workspace.XXXXXXXX)}
  try_with_timeout rm -fr ${new_workspace_dir} > /dev/null 2>&1
  mkdir -p ${new_workspace_dir}
  workspaces+=(${new_workspace_dir})
  cd ${new_workspace_dir}
  mkdir tools

  copy_tools_directory

  # Suppress the echo from setup_module_dot_bazel
  setup_module_dot_bazel > /dev/null

  maybe_setup_python_windows_tools
}


# Set-up a clean default workspace.
function setup_clean_workspace() {
  export WORKSPACE_DIR=${TEST_TMPDIR}/workspace
  log_info "setting up client in ${WORKSPACE_DIR}" >> $TEST_log
  try_with_timeout rm -fr ${WORKSPACE_DIR}
  create_new_workspace ${WORKSPACE_DIR}
  [ "${new_workspace_dir}" = "${WORKSPACE_DIR}" ] \
    || log_fatal "Failed to create workspace"

  if is_windows; then
    export BAZEL_SH="$(cygpath --windows /bin/bash)"
  fi
}

# Clean up all files that are not in tools directories, to restart
# from a clean workspace
function cleanup_workspace() {
  if [ -d "${WORKSPACE_DIR:-}" ]; then
    log_info "Cleaning up workspace" >> $TEST_log
    cd ${WORKSPACE_DIR}

    if [[ ${TESTENV_DONT_BAZEL_CLEAN:-0} == 0 ]]; then
      bazel clean >> "$TEST_log" 2>&1
    fi

    for i in *; do
      if ! is_tools_directory "$i"; then
        try_with_timeout rm -fr "$i"
      fi
    done
    # Suppress the echo from setup_module_dot_bazel
    setup_module_dot_bazel > /dev/null
  fi
  for i in "${workspaces[@]}"; do
    if [ "$i" != "${WORKSPACE_DIR:-}" ]; then
      try_with_timeout rm -fr $i
    fi
  done
  workspaces=()
}

function testenv_tear_down() {
  cleanup_workspace
}

# This is called by unittest.bash upon eventual exit of the test suite.
function cleanup() {
  if [ -d "${WORKSPACE_DIR:-}" ]; then
    # Try to shutdown Bazel at the end to prevent a "Cannot delete path" error
    # on Windows when the outer Bazel tries to delete $TEST_TMPDIR.
    cd "${WORKSPACE_DIR}"
    try_with_timeout bazel shutdown || true
  fi
}

#
# Simples assert to make the tests more readable
#
function assert_build() {
  bazel build --verbose_failures $* || fail "Failed to build $*"
}

function assert_build_output() {
  local OUTPUT=$1
  shift
  assert_build "$*"
  test -f "$OUTPUT" || fail "Output $OUTPUT not found for target $*"
}

function assert_build_fails() {
  bazel build -s $1 >> $TEST_log 2>&1 \
    && fail "Test $1 succeed while expecting failure" \
    || true
  if [ -n "${2:-}" ]; then
    expect_log "$2"
  fi
}

function assert_test_ok() {
  bazel test --test_output=errors $* >> $TEST_log 2>&1 \
    || fail "Test $1 failed while expecting success"
}

function assert_test_fails() {
  bazel test --test_output=errors $* >> $TEST_log 2>&1 \
    && fail "Test $* succeed while expecting failure" \
    || true
  expect_log "$1.*FAILED"
}

function assert_binary_run() {
  $1 >> $TEST_log 2>&1 || fail "Failed to run $1"
  [ -z "${2:-}" ] || expect_log "$2"
}

function assert_bazel_run() {
  bazel run $1 >> $TEST_log 2>&1 || fail "Failed to run $1"
    [ -z "${2:-}" ] || expect_log "$2"

  assert_binary_run "./bazel-bin/$(echo "$1" | sed 's|^//||' | sed 's|:|/|')" "${2:-}"
}

setup_bazelrc

################### shell/integration/testenv ############################
# Setting up the environment for our legacy integration tests.
#
PRODUCT_NAME=bazel
TOOLS_REPOSITORY="@bazel_tools"
BUILTINS_PACKAGE_PATH_IN_SOURCE="src/main/starlark/builtins_bzl"
WORKSPACE_NAME=main
bazelrc=$TEST_TMPDIR/bazelrc

function put_bazel_on_path() {
  # do nothing as test-setup already does that
  true
}

function write_default_bazelrc() {
  setup_bazelrc
}

function add_to_bazelrc() {
  echo "$@" >> $bazelrc
}

function create_and_cd_client() {
  setup_clean_workspace
  touch .bazelrc
}

################### Extra ############################

# Functions that need to be called before each test.

create_and_cd_client

# Optional environment changes.

# Creates a fake Python default runtime that just outputs a marker string
# indicating which version was used, without executing any Python code.
function use_fake_python_runtimes_for_testsuite() {
  # The stub script template automatically appends ".exe" to the Python binary
  # name if it doesn't already end in ".exe", ".com", or ".bat".
  if is_windows; then
    PYTHON3_FILENAME="python3.bat"
  else
    PYTHON3_FILENAME="python3.sh"
  fi

  add_to_bazelrc "build --extra_toolchains=//tools/python:fake_python_toolchain"

  mkdir -p tools/python

  cat > tools/python/BUILD << EOF
load("@rules_python//python:py_runtime.bzl", "py_runtime")
load("@rules_python//python:py_runtime_pair.bzl", "py_runtime_pair")

package(default_visibility=["//visibility:public"])

sh_binary(
    name = '2to3',
    srcs = ['2to3.sh']
)

py_runtime(
    name = "fake_py3_interpreter",
    interpreter = ":${PYTHON3_FILENAME}",
    python_version = "PY3",
)

py_runtime_pair(
    name = "fake_py_runtime_pair",
    py3_runtime = ":fake_py3_interpreter",
)

toolchain(
    name = "fake_python_toolchain",
    toolchain = ":fake_py_runtime_pair",
    toolchain_type = "@bazel_tools//tools/python:toolchain_type",
)
EOF

  # Windows .bat has uppercase ECHO and no shebang.
  if is_windows; then
    cat > tools/python/$PYTHON3_FILENAME << EOF
@ECHO I am Python 3
EOF
  else
    cat > tools/python/$PYTHON3_FILENAME << EOF
#!/bin/sh
echo 'I am Python 3'
EOF
    chmod +x tools/python/$PYTHON3_FILENAME
  fi
}

function mock_rules_java_to_avoid_downloading() {
  rules_java_workspace="${TEST_TMPDIR}/rules_java_workspace"
  mkdir -p "${rules_java_workspace}/java"
  mkdir -p "${rules_java_workspace}/toolchains"
  touch "${rules_java_workspace}/WORKSPACE"
  touch "${rules_java_workspace}/toolchains/BUILD"
  cat > "${rules_java_workspace}/toolchains/local_java_repository.bzl" <<EOF
def local_java_repository(**attrs):
    pass
EOF
  cat > "${rules_java_workspace}/toolchains/jdk_build_file.bzl" <<EOF
JDK_BUILD_TEMPLATE = ''
EOF
  touch "${rules_java_workspace}/java/BUILD"
  cat > "${rules_java_workspace}/java/rules_java_deps.bzl" <<EOF
def rules_java_dependencies():
    pass
EOF
  cat > "${rules_java_workspace}/java/repositories.bzl" <<EOF
def rules_java_toolchains():
    pass
EOF
  cat > "${rules_java_workspace}/java/defs.bzl" <<EOF
def java_binary(**attrs):
    native.java_binary(**attrs)
def java_library(**attrs):
    native.java_library(**attrs)
def java_import(**attrs):
    native.java_import(**attrs)
def java_test(**attrs):
    native.java_test(**attrs)
EOF
  # Disable autoloads, because the Java mock isn't complete enough to support it
  add_to_bazelrc "common --incompatible_autoload_externally="
  add_to_bazelrc "common --override_repository=rules_java=${rules_java_workspace}"
  add_to_bazelrc "common --override_repository=rules_java_builtin=${rules_java_workspace}"
}

# overrides remote_java_tools repositories if not using "released"
function override_java_tools() {
  RULES_JAVA_REPO_NAME="$1"; shift
  JAVA_TOOLS_ZIP="$1"; shift
  JAVA_TOOLS_PREBUILT_ZIP="$1"; shift

  JAVA_TOOLS_REPO_PREFIX="${RULES_JAVA_REPO_NAME}+toolchains+"

  if [[ "${JAVA_TOOLS_ZIP}" != "released" ]]; then
    JAVA_TOOLS_ZIP_FILE="$(rlocation "${JAVA_TOOLS_ZIP}")"
    JAVA_TOOLS_DIR="$TEST_TMPDIR/_java_tools"
    unzip -q "${JAVA_TOOLS_ZIP_FILE}" -d "$JAVA_TOOLS_DIR"
    touch "$JAVA_TOOLS_DIR/WORKSPACE"
    add_to_bazelrc "build --override_repository=${JAVA_TOOLS_REPO_PREFIX}remote_java_tools=${JAVA_TOOLS_DIR}"
  fi

  if [[ "${JAVA_TOOLS_PREBUILT_ZIP}" != "released" ]]; then
    JAVA_TOOLS_PREBUILT_ZIP_FILE="$(rlocation "${JAVA_TOOLS_PREBUILT_ZIP}")"
    JAVA_TOOLS_PREBUILT_DIR="$TEST_TMPDIR/_java_tools_prebuilt"
    unzip -q "${JAVA_TOOLS_PREBUILT_ZIP_FILE}" -d "$JAVA_TOOLS_PREBUILT_DIR"
    touch "$JAVA_TOOLS_PREBUILT_DIR/WORKSPACE"
    add_to_bazelrc "build --override_repository=${JAVA_TOOLS_REPO_PREFIX}remote_java_tools_linux=${JAVA_TOOLS_PREBUILT_DIR}"
    add_to_bazelrc "build --override_repository=${JAVA_TOOLS_REPO_PREFIX}remote_java_tools_windows=${JAVA_TOOLS_PREBUILT_DIR}"
    add_to_bazelrc "build --override_repository=${JAVA_TOOLS_REPO_PREFIX}remote_java_tools_darwin_x86_64=${JAVA_TOOLS_PREBUILT_DIR}"
    add_to_bazelrc "build --override_repository=${JAVA_TOOLS_REPO_PREFIX}remote_java_tools_darwin_arm64=${JAVA_TOOLS_PREBUILT_DIR}"
  fi
}
