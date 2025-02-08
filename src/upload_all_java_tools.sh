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

# A script to upload a given java_tools zip on GCS. Used by the java_tools_binaries
# Buildkite pipeline. It is not recommended to run this script manually.

# Script used by the "java_tools binaries" Buildkite pipeline to build the java tools archives
# and upload them on GCS.
#
# The script has to be executed directly without invoking bazel:
# $ src/upload_all_java_tools.sh
#
# The script cannot be invoked through a sh_binary using bazel because git
# cannot be used through a sh_binary.

set -euo pipefail

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r platform=windows
  ;;
linux*)
  declare -r platform=linux
  ;;
*)
  declare -r platform=other
  ;;
esac

echo Platform: $platform

if [[ "$platform" == "windows" ]]; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"

  # Enforce a UTF-8 code page for the JVM that runs GraalVM so that the
  # resulting native image of Turbine can handle UTF-8 characters.
  reg add "HKLM\SYSTEM\CurrentControlSet\Control\Nls\CodePage" /v ACP /t REG_SZ /d 65001 /f
elif [[ "$platform" == "linux" ]]; then
  # This locale is used by Java and GraalVM native image compilation actions.
  echo "C.UTF-8         en_US.UTF-8" | sudo tee -a /usr/share/locale/locale.alias > /dev/null
fi

commit_hash=$(git rev-parse HEAD)
timestamp=$(date +%s)
bazel_version=$(bazel info release | cut -d' ' -f2)

RELEASE_BUILD_OPTS="-c opt --tool_java_language_version=8 --java_language_version=8"

# Check that the build machine is set up for Unicode.
bazel run ${RELEASE_BUILD_OPTS} //src:CheckSunJnuEncoding

# Passing the same commit_hash and timestamp to all targets to mark all the artifacts
# uploaded on GCS with the same identifier.

bazel build ${RELEASE_BUILD_OPTS} //src:java_tools_zip
zip_path=${PWD}/bazel-bin/src/java_tools.zip

bazel build ${RELEASE_BUILD_OPTS} //src:java_tools_prebuilt_zip
prebuilt_zip_path=${PWD}/bazel-bin/src/java_tools_prebuilt.zip

# copy zips out of bazel-bin so we don't lose them on later bazel invocations
cp -f ${zip_path} ${prebuilt_zip_path} ./
zip_path=${PWD}/java_tools.zip
prebuilt_zip_path=${PWD}/java_tools_prebuilt.zip

if [[ "$platform" == "windows" ]]; then
    zip_path="$(cygpath -m "${zip_path}")"
    prebuilt_zip_path="$(cygpath -m "${prebuilt_zip_path}")"
fi

# Temporary workaround for https://github.com/bazelbuild/bazel/issues/20753
TEST_FLAGS=""
if [[ "$platform" == "linux" ]]; then
  TEST_FLAGS="--sandbox_tmpfs_path=/tmp"
fi

# Skip for now, as the test is broken on Windows.
# See https://github.com/bazelbuild/bazel/issues/12244 for details
if [[ "$platform" != "windows" ]]; then
    JAVA_VERSIONS=`cat src/test/shell/bazel/BUILD | grep '^JAVA_VERSIONS = ' | sed -e 's/JAVA_VERSIONS = //' | sed -e 's/["(),]//g'`
    TEST_TARGETS=""
    for java_version in $JAVA_VERSIONS; do
      TEST_TARGETS="$TEST_TARGETS //src/test/shell/bazel:bazel_java_test_local_java_tools_jdk${java_version}"
    done
    bazel test $TEST_FLAGS --verbose_failures --test_output=all --nocache_test_results \
        $TEST_TARGETS \
        --define=LOCAL_JAVA_TOOLS_ZIP_PATH="${zip_path}" \
        --define=LOCAL_JAVA_TOOLS_PREBUILT_ZIP_PATH="${prebuilt_zip_path}"
fi

bazel run ${RELEASE_BUILD_OPTS} //src:upload_java_tools_prebuilt -- \
    --commit_hash ${commit_hash} \
    --timestamp ${timestamp} \
    --bazel_version ${bazel_version}

if [[ "$platform" == "linux" ]]; then
    bazel run ${RELEASE_BUILD_OPTS} //src:upload_java_tools -- \
        --commit_hash ${commit_hash} \
        --timestamp ${timestamp} \
        --bazel_version ${bazel_version}

    bazel run ${RELEASE_BUILD_OPTS} //src:upload_java_tools_dist -- \
        --commit_hash ${commit_hash} \
        --timestamp ${timestamp} \
        --bazel_version ${bazel_version}
fi
