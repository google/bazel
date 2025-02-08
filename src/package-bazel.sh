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

set -euo pipefail

# This script creates the Bazel archive that Bazel client unpacks and then
# starts the server from.

WORKDIR="$(pwd)"
OUT=$1; shift
EMBEDDED_TOOLS=$1; shift
DEPLOY_JAR=$1; shift
INSTALL_BASE_KEY=$1; shift
PLATFORMS_ARCHIVE=$1; shift

if [[ "$OUT" == *jdk_allmodules.zip ]]; then
  DEV_BUILD=1
else
  DEV_BUILD=0
fi

TMP_DIR=${TMPDIR:-/tmp}
ROOT="$(mktemp -d ${TMP_DIR%%/}/bazel.XXXXXXXX)"
RECOMP="$ROOT/recomp"
PACKAGE_DIR="$ROOT/pkg"
DEPLOY_UNCOMP="$ROOT/deploy-uncompressed.jar"
FILE_LIST="$ROOT/file.list"
mkdir -p "${PACKAGE_DIR}"
trap "rm -fr ${ROOT}" EXIT

cp $* ${PACKAGE_DIR}

if [[ $DEV_BUILD -eq 0 ]]; then
  # Unpack the deploy jar for postprocessing and for "re-compressing" to save
  # ~10% of final binary size.
  mkdir -p $RECOMP
  unzip -q -d $RECOMP ${DEPLOY_JAR}
  cd $RECOMP

  # Zero out timestamps and sort the entries to ensure determinism.
  find . -type f -print0 | xargs -0 touch -t 198001010000.00
  find . -type f | sort | zip -q0DX@ "$DEPLOY_UNCOMP"

  # While we're in the deploy jar, grab the label and pack it into the final
  # packaged distribution zip where it can be used to quickly determine version
  # info.
  bazel_label="$(\
    (grep '^build.label=' build-data.properties | cut -d'=' -f2- | tr -d '\n') \
        || echo -n 'no_version')"

  cd "$WORKDIR"

  DEPLOY_JAR="$DEPLOY_UNCOMP"
fi
echo -n "${bazel_label:-no_version}" > "${PACKAGE_DIR}/build-label.txt"

if [ -n "${EMBEDDED_TOOLS}" ]; then
  mkdir ${PACKAGE_DIR}/embedded_tools
  (cd ${PACKAGE_DIR}/embedded_tools && unzip -q "${WORKDIR}/${EMBEDDED_TOOLS}")
fi

(
  cd $PACKAGE_DIR
  tar -xf "$WORKDIR/$PLATFORMS_ARCHIVE" -C .
  # "platforms" is a well-known module, so no need to tamper with anything here.
)

# Make a list of the files in the order we want them inside the final zip.
(
  cd $PACKAGE_DIR
  # The server jar needs to be the first binary we extract.
  # This is how the Bazel client knows which .jar to pass to the JVM.
  echo A-server.jar
  find . -type f | sort
  # And install_base_key must be last.
  echo install_base_key
) > $FILE_LIST

# Move these after the 'find' above.
cp $DEPLOY_JAR $PACKAGE_DIR/A-server.jar
cp $INSTALL_BASE_KEY $PACKAGE_DIR/install_base_key

# Zero timestamps.
(cd $PACKAGE_DIR; xargs touch -t 198001010000.00) < $FILE_LIST

if [[ "$DEV_BUILD" -eq 1 ]]; then
  # Create output zip with lowest compression, but fast.
  ZIP_ARGS="-q1DX@"
else
  # Create output zip with highest compression, but slow.
  ZIP_ARGS="-q9DX@"
fi
(cd $PACKAGE_DIR; zip $ZIP_ARGS "$WORKDIR/$OUT") < $FILE_LIST
