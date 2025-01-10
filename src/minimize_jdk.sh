#!/bin/bash

# Copyright 2018 The Bazel Authors. All rights reserved.
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

# This script creates from the full JDK a minimized version that only contains
# the specified JDK modules.

# --- begin runfiles.bash initialization v3 ---
# Copy-pasted from the Bazel Bash runfiles library v3.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
# shellcheck disable=SC1090
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v3 ---

# Force a UTF-8 compatible locale for Java tools to operate under paths with
# Unicode characters.
if [[ $(locale charmap) != "UTF-8" ]]; then
  export LC_CTYPE=C.UTF-8
fi
if [[ $(locale charmap) != "UTF-8" ]]; then
  export LC_CTYPE=en_US.UTF-8
fi

if [ "$1" == "--allmodules" ]; then
  shift
  modules="ALL-MODULE-PATH"
else
  modules=$(cat "$2" | paste -sd "," - | tr -d '\r')
  # We have to add this module explicitly because jdeps doesn't find the
  # dependency on it but it is still necessary for TLSv1.3.
  modules="$modules,jdk.crypto.ec"
fi
fulljdk=$1
out=$3
ARCH=`uname -p`
if [[ "${ARCH}" == 'ppc64le'  ]] || [[ "${ARCH}" == 's390x' ]]; then
  FULL_JDK_DIR="jdk*"
  DOCS=""
else
  FULL_JDK_DIR="zulu*"
  DOCS="DISCLAIMER readme.txt"
fi

UNAME=$(uname -s | tr 'A-Z' 'a-z')

if [[ "$UNAME" =~ msys_nt* ]]; then
  set -x
  mkdir "tmp.$$"
  cd "tmp.$$"
  unzip -q "../$fulljdk"
  cd $FULL_JDK_DIR
  # We have to add this module explicitly because it is windows specific, it allows
  # the usage of the Windows truststore
  # e.g. -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
  modules="$modules,jdk.crypto.mscapi"
  ./bin/jlink --module-path ./jmods/ --add-modules "$modules" \
    --vm=server --strip-debug --no-man-pages \
    --output reduced
  "$(rlocation "io_bazel/src/patch_java_manifest_for_utf8.exe")" reduced/bin/java.exe
  cp $DOCS legal/java.base/ASSEMBLY_EXCEPTION \
    reduced/
  # These are necessary for --host_jvm_debug to work.
  cp bin/dt_socket.dll bin/jdwp.dll reduced/bin
  zip -q -X -r ../reduced.zip reduced/
  cd ../..
  mv "tmp.$$/reduced.zip" "$out"
  rm -rf "tmp.$$"
else
  # The --no-same-owner flag instructs tar to not try to chown extracted files
  # to the owner stored in the archive - it will try to do that when running as
  # root, but fail when running inside Docker, so we explicitly disable it.
  tar xf "$fulljdk" --no-same-owner
  cd $FULL_JDK_DIR
  ./bin/jlink --module-path ./jmods/ --add-modules "$modules" \
    --vm=server --strip-debug --no-man-pages \
    --output reduced
  cp $DOCS legal/java.base/ASSEMBLY_EXCEPTION \
    reduced/
  # These are necessary for --host_jvm_debug to work.
  if [[ "$UNAME" =~ darwin ]]; then
    cp lib/libdt_socket.dylib lib/libjdwp.dylib reduced/lib
  else
    cp lib/libdt_socket.so lib/libjdwp.so reduced/lib
  fi
  find reduced -exec touch -ht 198001010000 {} +
  zip -q -X -r ../reduced.zip reduced/
  cd ..
  mv reduced.zip "$out"
fi
