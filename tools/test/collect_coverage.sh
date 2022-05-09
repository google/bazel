#!/bin/bash

# Copyright 2016 The Bazel Authors. All rights reserved.
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

# Wrapper script for collecting code coverage during test execution.
#
# Expected environment:
#   COVERAGE_MANIFEST - mandatory, location of the instrumented file manifest
#   LCOV_MERGER - mandatory, location of the LcovMerger
#   COVERAGE_DIR - optional, location of the coverage temp directory
#   COVERAGE_OUTPUT_FILE - optional, location of the final lcov file
#   VERBOSE_COVERAGE - optional, print debug info from the coverage scripts
#
# Script expects that it will be started in the execution root directory and
# not in the test's runfiles directory.

if [[ -n "$VERBOSE_COVERAGE" ]]; then
  set -x
fi

function resolve_links() {
  local name="$1"

  if [ -e "$name" ]; then
    # resolve all links, keep path absolute
    while [ -L "$name" ]; do
      local target=$(readlink "$name")
      if [ "$(echo "$target" | head -c1)" = "/" ]; then
        name="$target"
      else
        name="$(dirname "$name")/$target"
      fi
    done
    echo "$name"
  else
    false  # fail the function
  fi
}

if [[ -z "$COVERAGE_MANIFEST" ]]; then
  echo --
  echo Coverage runner: \$COVERAGE_MANIFEST is not set
  echo Current environment:
  env | sort
  exit 1
fi
# When collect_coverage.sh is used, test runner must be instructed not to cd
# to the test's runfiles directory.
export ROOT="$PWD"

if [[ "$COVERAGE_MANIFEST" != /* ]]; then
  # Canonicalize the path to coverage manifest so that tests can find it.
  export COVERAGE_MANIFEST="$ROOT/$COVERAGE_MANIFEST"
fi

# write coverage data outside of the runfiles tree
export COVERAGE_DIR=${COVERAGE_DIR:-"$ROOT/coverage"}
# make COVERAGE_DIR an absolute path
if ! [[ $COVERAGE_DIR == $ROOT* ]]; then
  COVERAGE_DIR=$ROOT/$COVERAGE_DIR
fi

mkdir -p "$COVERAGE_DIR"
COVERAGE_OUTPUT_FILE=${COVERAGE_OUTPUT_FILE:-"$COVERAGE_DIR/_coverage.dat"}
# make COVERAGE_OUTPUT_FILE an absolute path
if ! [[ $COVERAGE_OUTPUT_FILE == $ROOT* ]]; then
  COVERAGE_OUTPUT_FILE=$ROOT/$COVERAGE_OUTPUT_FILE
fi

# Java
# --------------------------------------
export JAVA_COVERAGE_FILE=$COVERAGE_DIR/jvcov.dat
# Let tests know that it is a coverage run
export COVERAGE=1
export BULK_COVERAGE_RUN=1

# Setting up the environment for executing the C++ tests.
if [[ -z "$GCOV_PREFIX_STRIP" ]]; then
  # TODO: GCOV_PREFIX_STRIP=3 is incorrect on MacOS in the default setup
  export GCOV_PREFIX_STRIP=3
fi
export GCOV_PREFIX="${COVERAGE_DIR}"
export LLVM_PROFILE_FILE="${COVERAGE_DIR}/%h-%p-%m.profraw"
if [[ -n "$LLVM_PROFILE_CONTINUOUS_MODE" ]]; then
  # %c enables continuous mode but expands out to nothing, so the position
  # within LLVM_PROFILE_FILE does not matter.
  export LLVM_PROFILE_FILE="${LLVM_PROFILE_FILE}%c"
fi

# In coverage mode for Java, we need to merge the runtime classpath before
# running the tests. JacocoCoverageRunner uses this merged jar in order
# to get coverage data.
#
# Merge the classpath using SingleJar and save it in the environment
# variable JACOCO_METADATA_JAR. The jars on the runtime classpath are listed
# in the file $JAVA_RUNTIME_CLASSPATH_FOR_COVERAGE.
#
# We need to merge the jars here because the merged jar can be an input
# too large (the combined merged jars for several big tests in a run
# can go over 10G). Not merging the jars and making
# JacocoCoverageRunner read every individual jar goes over the shutdown hook
# time limit in the coverage runner (~few seconds).
#
# SINGLE_JAR_TOOL                     Exec path of SingleJar.
#
# JAVA_RUNTIME_CLASSPATH_FOR_COVERAGE Exec path of a file that contains the
#                                     relative paths of the jars on the runtime
#                                     classpath delimited by newline.
if [[ ! -z "${JAVA_RUNTIME_CLASSPATH_FOR_COVERAGE}" ]]; then
  JAVA_RUNTIME_CLASSPATH_FOR_COVERAGE="${PWD}/${JAVA_RUNTIME_CLASSPATH_FOR_COVERAGE}"
  SINGLE_JAR_TOOL="${PWD}/${SINGLE_JAR_TOOL}"

  # Create a paramsfile for invoking SingleJar.
  mkdir -p "${COVERAGE_DIR}"
  single_jar_params_file="${COVERAGE_DIR}/runtime_classpath.paramsfile"
  touch "$single_jar_params_file"

  # Export JACOCO_METADATA_JAR in order for JacocoCoverageRunner to be able
  # to read it.
  export JACOCO_METADATA_JAR="${COVERAGE_DIR}/coverage-runtime_merged_instr.jar"

  echo -e "--output ${JACOCO_METADATA_JAR}\n--sources" >> "$single_jar_params_file"

  # Append the runfiles prefix to all the relative paths found in
  # JAVA_RUNTIME_CLASSPATH_FOR_COVERAGE, to invoke SingleJar with the
  # absolute paths.
  RUNFILES_PREFIX="$TEST_SRCDIR/"
  cat "$JAVA_RUNTIME_CLASSPATH_FOR_COVERAGE" | sed "s@^@$RUNFILES_PREFIX@" >> "$single_jar_params_file"

  # Invoke SingleJar. This will create JACOCO_METADATA_JAR.
  "${SINGLE_JAR_TOOL}" "@$single_jar_params_file"
fi

if [[ "$IS_COVERAGE_SPAWN" == "0" ]]; then
  # TODO(bazel-team): cd should be avoided.
  cd "$TEST_SRCDIR/$TEST_WORKSPACE"

  # Always create the coverage report.
  if [[ "$SPLIT_COVERAGE_POST_PROCESSING" == "0" ]]; then
    touch $COVERAGE_OUTPUT_FILE
  fi

  # Execute the test.
  "$@"
  TEST_STATUS=$?

  if [[ $TEST_STATUS -ne 0 ]]; then
    echo --
    echo Coverage runner: Not collecting coverage for failed test.
    echo The following commands failed with status $TEST_STATUS
    echo "$@"
    exit $TEST_STATUS
  fi
fi


# ------------------EXPERIMENTAL---------------------
# After this point we can run the code necessary for the coverage spawn

if [[ "$SPLIT_COVERAGE_POST_PROCESSING" == "1" && "$IS_COVERAGE_SPAWN" == "0" ]]; then
  exit 0
fi

if [[ "$SPLIT_COVERAGE_POST_PROCESSING" == "1" && "$IS_COVERAGE_SPAWN" == "1" ]]; then
  touch $COVERAGE_OUTPUT_FILE
fi
# TODO(bazel-team): cd should be avoided.
cd $ROOT
# Call the C++ code coverage collection script.
if [[ "$CC_CODE_COVERAGE_SCRIPT" ]]; then
    if ! eval "${CC_CODE_COVERAGE_SCRIPT}" && test -z "${IGNORE_COVERAGE_COLLECTION_FAILURES:-}"; then
      echo "error: coverage collection script failed" >&2
      exit 1
    fi
fi

if [[ -z "$LCOV_MERGER" ]]; then
  # this can happen if a rule returns an InstrumentedFilesInfo (which all do
  # following 5b216b2) but does not define an _lcov_merger attribute.
  # Unfortunately, we cannot simply stop this script being called in this case
  # due to conflicts with how things work within Google.
  # The file creation is required because TestActionBuilder has already declared
  # it.
  exit 0
fi

for name in "$LCOV_MERGER"; do
  if [[ ! -e $name ]]; then
    echo --
    echo Coverage runner: cannot locate file $name
    exit 1
  fi
done

# Export the command line that invokes LcovMerger with the flags:
# --coverage_dir          The absolute path of the directory where the
#                         intermediate coverage reports are located.
#                         CoverageOutputGenerator will search for files with
#                         the .dat and .gcov extension under this directory and
#                         will merge everything it found in the output report.
#
# --output_file           The absolute path of the merged coverage report.
#
# --filter_sources        Filters out the sources that match the given regexes
#                         from the final coverage report. This is needed
#                         because some coverage tools (e.g. gcov) do not have
#                         any way of specifying what sources to exclude when
#                         generating the code coverage report (in this case the
#                         syslib sources).
#
# --source_file_manifest  The absolute path of the coverage source file
#                         manifest. CoverageOutputGenerator uses this file to
#                         keep only the sources found in the manifest (that is,
#                         only the sources of targets matched by
#                         --instrumentation_filter, excluding test targets
#                         unless --instrument_test_targets).

if [[ "$IS_COVERAGE_SPAWN" == "1" ]]; then
  COVERAGE_DIR=$(resolve_links $COVERAGE_DIR)
  COVERAGE_MANIFEST=$(resolve_links $COVERAGE_MANIFEST)
fi

LCOV_MERGER_CMD="${LCOV_MERGER} --coverage_dir=${COVERAGE_DIR} \
  --output_file=${COVERAGE_OUTPUT_FILE} \
  --filter_sources=/usr/bin/.+ \
  --filter_sources=/usr/lib/.+ \
  --filter_sources=/usr/include.+ \
  --filter_sources=/Applications/.+ \
  --source_file_manifest=${COVERAGE_MANIFEST}"

if [[ $COVERAGE_REPORTED_TO_ACTUAL_SOURCES_FILE ]]; then
  LCOV_MERGER_CMD="$LCOV_MERGER_CMD\
  --sources_to_replace_file=$ROOT/$COVERAGE_REPORTED_TO_ACTUAL_SOURCES_FILE"
fi

if [[ $DISPLAY_LCOV_CMD ]] ; then
  echo "Running lcov_merger"
  echo $LCOV_MERGER_CMD
  echo "-----------------"
fi

# JAVA_RUNFILES is set to the runfiles of the test, which does not necessarily
# contain a JVM (it does only if the test has a Java binary somewhere). So let
# the LCOV merger discover where its own runfiles tree is.
JAVA_RUNFILES= exec $LCOV_MERGER_CMD
