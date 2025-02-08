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

# Script for building bazel from scratch without bazel

if [ -d derived/jars ]; then
  ADDITIONAL_JARS=derived/jars
else
  DISTRIBUTION=${BAZEL_DISTRIBUTION:-debian}
  ADDITIONAL_JARS="$(grep -o '".*\.jar"' tools/distributions/${DISTRIBUTION}/${DISTRIBUTION}_java.BUILD | sed 's/"//g' | sed 's|^|/usr/share/|g')"
fi

PROTO_FILES=$(find third_party/remoteapis third_party/pprof src/main/protobuf src/main/java/com/google/devtools/build/lib/buildeventstream/proto src/main/java/com/google/devtools/build/skyframe src/main/java/com/google/devtools/build/lib/skyframe/proto src/main/java/com/google/devtools/build/lib/bazel/debug src/main/java/com/google/devtools/build/lib/starlarkdebug/proto src/main/java/com/google/devtools/build/lib/packages/metrics/package_load_metrics.proto -name "*.proto")
# For protobuf jars, derived/jars/protobuf+/java/core/libcore.jar must be in front of derived/jars/protobuf+/java/core/liblite.jar, so we sort jars here
LIBRARY_JARS=$(find $ADDITIONAL_JARS -name '*.jar' | sort | grep -Fv JavaBuilder | tr "\n" " ")
MAVEN_JARS=$(find "derived/maven" -name '*.jar' | grep -Fv netty-tcnative | tr "\n" " ")
LIBRARY_JARS="${LIBRARY_JARS} ${MAVEN_JARS}"

DIRS=$(echo src/{java_tools/singlejar/java/com/google/devtools/build/zip,main/java,tools/starlark/java} tools/java/runfiles ${OUTPUT_DIR}/src)
# Exclude source files that are not needed for Bazel itself, which avoids dependencies like truth.
EXCLUDE_FILES="src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/testing/* src/main/java/com/google/devtools/build/lib/collect/nestedset/NestedSetCodecTestUtils.java"
# Exclude whole directories under the bazel src tree that bazel itself
# doesn't depend on.
EXCLUDE_DIRS="src/main/java/com/google/devtools/build/docgen src/main/java/com/google/devtools/build/lib/skyframe/serialization/testutils src/main/java/com/google/devtools/common/options/testing src/main/java/com/google/devtools/build/lib/testing"
for d in $EXCLUDE_DIRS ; do
  for f in $(find $d -type f) ; do
    EXCLUDE_FILES+=" $f"
  done
done

mkdir -p "${OUTPUT_DIR}/classes"
mkdir -p "${OUTPUT_DIR}/src"

# May be passed in from outside.
ZIPOPTS="$ZIPOPTS"

unset JAVA_TOOL_OPTIONS
unset _JAVA_OPTIONS

LDFLAGS=${LDFLAGS:-""}

MSYS_DLLS=""

function get_minor_java_version() {
  get_java_version
  java_minor_version=$(echo $JAVA_VERSION | sed 's/[^.][^.]*\.//' | sed 's/\..*$//')
  javac_minor_version=$(echo $JAVAC_VERSION | sed 's/[^.][^.]*\.//' | sed 's/\..*$//')
}

# Check that javac -version returns a upper version than $JAVA_VERSION.
get_minor_java_version
[ ${java_minor_version} -le ${javac_minor_version} ] || \
  fail "JDK version (${JAVAC_VERSION}) is lower than ${JAVA_VERSION}, please set \$JAVA_HOME."

JAR="${JAVA_HOME}/bin/jar"

# Ensures unzip won't create paths longer than 259 chars (MAX_PATH) on Windows.
function check_unzip_wont_create_long_paths() {
  output_path="$1"
  jars="$2"
  if [[ "${PLATFORM}" == "windows" ]]; then
    log "Checking if helper classes can be extracted..."
    max_path=$((259-${#output_path}))
    # Do not quote $jars, we rely on it being split on spaces.
    for f in $jars ; do
      # Get the zip entries. Match lines with a date: they have the paths.
      entries="$(unzip -l "$f" | grep '[0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}' | awk '{print $4}')"
      for e in $entries; do
        if [[ ${#e} -gt $max_path ]]; then
          fail "Cannot unzip \"$f\" because the output path is too long: extracting file \"$e\" under \"$output_path\" would create a path longer than 259 characters. To fix this, set a shorter TMP value and try again. Example: export TMP=/c/tmp/bzl"
        fi
      done
    done
  fi
}

# Compiles java classes.
function java_compilation() {
  local name=$1
  local directories=$2
  local excludes=$3
  local library_jars=$4
  local output=$5

  local classpath=${library_jars// /$PATHSEP}${PATHSEP}$5
  local sourcepath=${directories// /$PATHSEP}

  tempdir
  local tmp="${NEW_TMPDIR}"
  local paramfile="${tmp}/param"
  local filelist="${tmp}/filelist"
  local excludefile="${tmp}/excludefile"
  touch $paramfile

  mkdir -p "${output}/classes"

  # Compile .java files (incl. generated ones) using javac
  log "Compiling $name code..."
  find ${directories} -name "*.java" | sort > "$filelist"
  # Quotes around $excludes intentionally omitted in the for statement so that
  # it's split on spaces
  (for i in $excludes; do
    echo $i
  done) | sort > "$excludefile"

  comm -23 "$filelist" "$excludefile" > "$paramfile"

  if [ ! -z "$BAZEL_DEBUG_JAVA_COMPILATION" ]; then
    echo "directories=${directories}" >&2
    echo "classpath=${classpath}" >&2
    echo "sourcepath=${sourcepath}" >&2
    echo "libraries=${library_jars}" >&2
    echo "output=${output}/classes" >&2
    echo "List of compiled files:" >&2
    cat "$paramfile" >&2
  fi

  check_unzip_wont_create_long_paths "${output}/classes" "$library_jars"

  # Use BAZEL_JAVAC_OPTS to pass additional arguments to javac, e.g.,
  # export BAZEL_JAVAC_OPTS="-J-Xmx2g -J-Xms200m"
  # Useful if your system chooses too small of a max heap for javac.
  # We intentionally rely on shell word splitting to allow multiple
  # additional arguments to be passed to javac.
  run "${JAVAC}" -classpath "${classpath}" -sourcepath "${sourcepath}" \
      -d "${output}/classes" -source "$JAVA_VERSION" -target "$JAVA_VERSION" \
      -encoding UTF-8 --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
      ${BAZEL_JAVAC_OPTS} "@${paramfile}"

  log "Extracting helper classes for $name..."
  for f in ${library_jars} ; do
    run unzip -qn ${f} -d "${output}/classes"
  done
}

# Create the deploy JAR
function create_deploy_jar() {
  local name=$1
  local mainClass=$2
  local output=$3
  shift 3
  local packages=""
  # Only keep the services subdirectory of META-INF (needed for AutoService).
  for i in $output/classes/META-INF/*; do
    local package=$(basename $i)
    if [[ "$package" != "services" ]]; then
      rm -r "$i"
    fi
  done
  for i in $output/classes/*; do
    local package=$(basename $i)
    packages="$packages -C $output/classes $package"
  done

  log "Creating $name.jar..."
  echo "Main-Class: $mainClass" > $output/MANIFEST.MF
  run "$JAR" cmf $output/MANIFEST.MF $output/$name.jar $packages "$@"
}

HOW_TO_BOOTSTRAP='

--------------------------------------------------------------------------------
NOTE: This failure is likely occurring if you are trying to bootstrap bazel from
a developer checkout. Those checkouts do not include the generated output of
the protoc compiler (as we prefer not to version generated files).

* To build a developer version of bazel, do

    bazel build //src:bazel

* To bootstrap your first bazel binary, please download a dist archive from our
  release page at https://github.com/bazelbuild/bazel/releases and run
  compile.sh on the unpacked archive.

The full install instructions to install a release version of bazel can be found
at https://bazel.build/install/compile-source
For a rationale, why the bootstrap process is organized in this way, see
https://bazel.build/designs/2016/10/11/distribution-artifact.html
--------------------------------------------------------------------------------

'

if [ -z "${BAZEL_SKIP_JAVA_COMPILATION}" ]; then

    if [ -d derived/src/java ]
    then
        log "Using pre-generated java proto files"
        mkdir -p "${OUTPUT_DIR}/src"
        cp -r derived/src/java/* "${OUTPUT_DIR}/src"
    else

        [ -n "${PROTOC}" ] \
            || fail "Must specify PROTOC if not bootstrapping from the distribution artifact${HOW_TO_BOOTSTRAP}"

        [ -n "${GRPC_JAVA_PLUGIN}" ] \
            || fail "Must specify GRPC_JAVA_PLUGIN if not bootstrapping from the distribution artifact${HOW_TO_BOOTSTRAP}"

        [[ -x "${PROTOC-}" ]] \
            || fail "Protobuf compiler not found in ${PROTOC-}"

        [[ -x "${GRPC_JAVA_PLUGIN-}" ]] \
            || fail "gRPC Java plugin not found in ${GRPC_JAVA_PLUGIN-}"

        log "Compiling Java stubs for protocol buffers..."
        for f in $PROTO_FILES ; do
            run "${PROTOC}" \
                -I. \
                -Isrc/main/protobuf/ \
                -Isrc/main/java/com/google/devtools/build/lib/buildeventstream/proto/ \
                -Isrc/main/java/com/google/devtools/build/lib/skyframe/proto/ \
                -Isrc/main/java/com/google/devtools/build/skyframe/ \
                -Isrc/main/java/com/google/devtools/build/lib/bazel/debug/ \
                -Isrc/main/java/com/google/devtools/build/lib/starlarkdebug/proto/ \
                -Ithird_party/remoteapis/ \
                -Ithird_party/pprof/ \
                --java_out=${OUTPUT_DIR}/src \
                --plugin=protoc-gen-grpc="${GRPC_JAVA_PLUGIN-}" \
                --grpc_out=${OUTPUT_DIR}/src "$f"
        done

        # Recreate the derived directory
        mkdir -p derived/src/java/build
        mkdir -p derived/src/java/com
        link_children ${OUTPUT_DIR}/src build derived/src/java
        link_children ${OUTPUT_DIR}/src com derived/src/java
    fi

  java_compilation "Bazel Java" "$DIRS" "$EXCLUDE_FILES" "$LIBRARY_JARS" "${OUTPUT_DIR}"

  # help files: all non java and BUILD files in src/main/java.
  for i in $(find src/main/java -type f -a \! -name '*.java' -a \! -name 'BUILD' | sed 's|src/main/java/||'); do
    mkdir -p $(dirname ${OUTPUT_DIR}/classes/$i)
    cp src/main/java/$i ${OUTPUT_DIR}/classes/$i
  done

  # Create the bazel_tools repository.
  BAZEL_TOOLS_REPO=${OUTPUT_DIR}/archive/embedded_tools
  mkdir -p ${BAZEL_TOOLS_REPO}
  cat <<EOF >${BAZEL_TOOLS_REPO}/WORKSPACE
workspace(name = 'bazel_tools')
EOF

  # Set up the MODULE.bazel file for `bazel_tools`
  link_file "${PWD}/src/MODULE.tools" "${BAZEL_TOOLS_REPO}/MODULE.bazel"

  mkdir -p "${BAZEL_TOOLS_REPO}/src/conditions"
  link_file "${PWD}/src/conditions/BUILD.tools" \
      "${BAZEL_TOOLS_REPO}/src/conditions/BUILD"
  link_children "${PWD}" src/conditions "${BAZEL_TOOLS_REPO}"
  link_children "${PWD}" src "${BAZEL_TOOLS_REPO}"

  link_dir ${PWD}/third_party ${BAZEL_TOOLS_REPO}/third_party

  # Set up the function_transition_allowlist target, which needs to exist for
  # Starlark rules that use Starlark transitions.
  mkdir -p "${BAZEL_TOOLS_REPO}/tools/allowlists/function_transition_allowlist"
  link_file "${PWD}/tools/allowlists/function_transition_allowlist/BUILD.tools" \
      "${BAZEL_TOOLS_REPO}/tools/allowlists/function_transition_allowlist/BUILD"
  link_children "${PWD}" tools/allowlists "${BAZEL_TOOLS_REPO}"

  # Create @bazel_tools//tools/cpp/runfiles
  mkdir -p ${BAZEL_TOOLS_REPO}/tools/cpp/runfiles
  link_file "${PWD}/tools/cpp/runfiles/runfiles.h" \
      "${BAZEL_TOOLS_REPO}/tools/cpp/runfiles/runfiles.h"
  link_file "${PWD}/tools/cpp/runfiles/BUILD.tools" \
      "${BAZEL_TOOLS_REPO}/tools/cpp/runfiles/BUILD"

  # Create @bazel_tools//tools/cpp/modules_tools
  mkdir -p ${BAZEL_TOOLS_REPO}/tools/cpp/modules_tools
  link_file "${PWD}/tools/cpp/modules_tools/BUILD.tools" "${BAZEL_TOOLS_REPO}/tools/cpp/modules_tools/BUILD"

  # Create @bazel_tools//tools/sh
  mkdir -p ${BAZEL_TOOLS_REPO}/tools/sh
  link_file "${PWD}/tools/sh/sh_toolchain.bzl" "${BAZEL_TOOLS_REPO}/tools/sh/sh_toolchain.bzl"
  link_file "${PWD}/tools/sh/BUILD.tools" "${BAZEL_TOOLS_REPO}/tools/sh/BUILD"

  # Create @bazel_tools//tools/jdk
  mkdir -p ${BAZEL_TOOLS_REPO}/tools/jdk
  link_file "${PWD}/tools/jdk/BUILD.tools" "${BAZEL_TOOLS_REPO}/tools/jdk/BUILD"
  link_children "${PWD}" tools/jdk "${BAZEL_TOOLS_REPO}"

  # Create @bazel_tools//tools/java/runfiles
  mkdir -p ${BAZEL_TOOLS_REPO}/tools/java/runfiles
  link_file "${PWD}/tools/java/runfiles/BUILD.tools" "${BAZEL_TOOLS_REPO}/tools/java/runfiles/BUILD"

  # Create @bazel_tools/tools/python/BUILD
  mkdir -p ${BAZEL_TOOLS_REPO}/tools/python
  link_file "${PWD}/tools/python/BUILD.tools" "${BAZEL_TOOLS_REPO}/tools/python/BUILD"

  # Create the rest of @bazel_tools//tools/...
  link_children "${PWD}" tools/cpp "${BAZEL_TOOLS_REPO}"
  mv -f ${BAZEL_TOOLS_REPO}/tools/cpp/BUILD.tools ${BAZEL_TOOLS_REPO}/tools/cpp/BUILD
  link_children "${PWD}" tools/python "${BAZEL_TOOLS_REPO}"
  link_children "${PWD}" tools "${BAZEL_TOOLS_REPO}"

  # Set up @maven properly
  cp derived/maven/BUILD.vendor derived/maven/BUILD

  create_deploy_jar "libblaze" "com.google.devtools.build.lib.bazel.Bazel" \
      ${OUTPUT_DIR}
fi

log "Creating Bazel install base..."
ARCHIVE_DIR=${OUTPUT_DIR}/archive
mkdir -p ${ARCHIVE_DIR}

# Dummy build-runfiles (we can't compile C++ yet, so we can't have the real one)
if [ "${PLATFORM}" = "windows" ]; then
  # We don't rely on runfiles trees on Windows
  cat <<'EOF' >${ARCHIVE_DIR}/build-runfiles${EXE_EXT}
#!/bin/sh
# Skip over --allow_relative.
mkdir -p $3
cp $2 $3/MANIFEST
EOF
else
  cat <<'EOF' >${ARCHIVE_DIR}/build-runfiles${EXE_EXT}
#!/bin/sh
# This is bash implementation of build-runfiles: reads space-separated paths
# from each line in the file in $1, then creates a symlink under $2 for the
# first element of the pair that points to the second element of the pair.
#
# bash is a terrible tool for this job, but in this case, that's the only one
# we have (we could hand-compile a little .jar file like we hand-compile the
# bootstrap version of Bazel, but we'd still need a shell wrapper around it, so
# it's not clear whether that would be a win over a few lines of Lovecraftian
# code)
# Skip over --allow_relative.
MANIFEST="$2"
TREE="$3"

rm -fr "$TREE"
mkdir -p "$TREE"

# Read the lines in $MANIFEST. the usual "for VAR in $(cat FILE)" idiom won't do
# because the lines in FILE contain spaces.
while read LINE; do
  # Split each line into two parts on the first space
  SYMLINK_PATH="${LINE%% *}"
  TARGET_PATH="${LINE#* }"
  ABSOLUTE_SYMLINK_PATH="$TREE/$SYMLINK_PATH"
  mkdir -p "$(dirname $ABSOLUTE_SYMLINK_PATH)"
  ln -s "$TARGET_PATH" "$ABSOLUTE_SYMLINK_PATH"
done < "$MANIFEST"

cp "$MANIFEST" "$TREE/MANIFEST"
EOF
fi

chmod 0755 ${ARCHIVE_DIR}/build-runfiles${EXE_EXT}

function build_jni() {
  local -r output_dir=$1

  if [ "${PLATFORM}" = "windows" ]; then
    # We need JNI on Windows because some filesystem operations are not (and
    # cannot be) implemented in native Java.
    log "Building Windows JNI library..."

    local -r jni_lib_name="windows_jni.dll"
    local -r output="${output_dir}/${jni_lib_name}"
    local -r tmp_output="${NEW_TMPDIR}/jni/${jni_lib_name}"
    mkdir -p "$(dirname "$tmp_output")"
    mkdir -p "$(dirname "$output")"

    # Keep this `find` command in sync with the `srcs` of
    # //src/main/native/windows:windows_jni
    local srcs=$(find src/main/native/windows -name '*.cc' -o -name '*.h')
    [ -n "$srcs" ] || fail "Could not find sources for Windows JNI library"

    # do not quote $srcs because we need to expand it to multiple args
    src/main/native/windows/build_windows_jni.sh "$tmp_output" ${srcs}

    cp "$tmp_output" "$output"
    chmod 0555 "$output"

    JNI_FLAGS="-Djava.library.path=${output_dir}"
  else
    # We don't need JNI on other platforms. The Java NIO file system fallback is
    # sufficient.
    true
  fi
}

build_jni "${ARCHIVE_DIR}"

cp $OUTPUT_DIR/libblaze.jar ${ARCHIVE_DIR}

# TODO(b/28965185): Remove when xcode-locator is no longer required in embedded_binaries.
log "Compiling xcode-locator..."
if [[ $PLATFORM == "darwin" ]]; then
  run /usr/bin/xcrun --sdk macosx clang -mmacosx-version-min=10.13 -fobjc-arc -framework CoreServices -framework Foundation -o ${ARCHIVE_DIR}/xcode-locator tools/osx/xcode_locator.m
else
  cp tools/osx/xcode_locator_stub.sh ${ARCHIVE_DIR}/xcode-locator
fi

function get_cwd() {
  local result=${PWD}
  [ "$PLATFORM" = "windows" ] && result="$(cygpath -m "$result")"
  echo "$result"
}

function run_bazel_jar() {
  local command=$1
  shift
  local client_env=()
  # Propagate all environment variables to bootstrapped Bazel.
  # See https://stackoverflow.com/questions/41898503/loop-over-environment-variables-in-posix-sh
  local env_vars="$(awk 'END { for (name in ENVIRON) { if(name != "_" && name ~ /^[A-Za-z0-9_]*$/) print name; } }' </dev/null)"
  for varname in $env_vars; do
    eval value=\$$varname
    if [ "${PLATFORM}" = "windows" ] && echo "$varname" | grep -q -i "^\(path\|tmp\|temp\|tempdir\|systemroot\|systemdrive\)$" ; then
      varname="$(echo "$varname" | tr [:lower:] [:upper:])"
    fi
    if [ "${value}" ]; then
      client_env=("${client_env[@]}" --client_env="${varname}=${value}")
    fi
  done

  "${JAVA_HOME}/bin/java" \
      -XX:+HeapDumpOnOutOfMemoryError -Xverify:none -Dfile.encoding=ISO-8859-1 \
      -XX:HeapDumpPath=${OUTPUT_DIR} \
      -Djava.util.logging.config.file=${OUTPUT_DIR}/javalog.properties \
      --add-opens java.base/java.lang=ALL-UNNAMED \
      ${JNI_FLAGS} \
      -jar ${ARCHIVE_DIR}/libblaze.jar \
      --batch \
      --install_base=${ARCHIVE_DIR} \
      --output_base=${OUTPUT_DIR}/out \
      --failure_detail_out=${OUTPUT_DIR}/failure_detail.rawproto \
      --output_user_root=${OUTPUT_DIR}/user_root \
      --install_md5= \
      --default_system_javabase="${JAVA_HOME}" \
      --workspace_directory="$(get_cwd)" \
      --nofatal_event_bus_exceptions \
      ${BAZEL_DIR_STARTUP_OPTIONS} \
      ${BAZEL_BOOTSTRAP_STARTUP_OPTIONS:-} \
      $command \
      --ignore_unsupported_sandboxing \
      --startup_time=329 --extract_data_time=523 \
      --rc_source=/dev/null --isatty=1 \
      --build_python_zip \
      "${client_env[@]}" \
      --client_cwd="$(get_cwd)" \
      "${@}"
}
