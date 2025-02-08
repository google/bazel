#!/bin/bash
#
# Copyright 2021 The Bazel Authors. All rights reserved.
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
# Tests that bazel runs projects with Java 17 features.

# --- begin runfiles.bash initialization ---
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
    if [[ -f "$0.runfiles_manifest" ]]; then
      export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
    elif [[ -f "$0.runfiles/MANIFEST" ]]; then
      export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
    elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
      export RUNFILES_DIR="$0.runfiles"
    fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

RULES_JAVA_REPO_NAME=$(cat "$(rlocation io_bazel/src/test/shell/bazel/RULES_JAVA_REPO_NAME)")
JAVA_TOOLS_ZIP="$1"; shift
JAVA_TOOLS_PREBUILT_ZIP="$1"; shift

override_java_tools "${RULES_JAVA_REPO_NAME}" "${JAVA_TOOLS_ZIP}" "${JAVA_TOOLS_PREBUILT_ZIP}"

# Override test globals that are defaulted to 21 in testenv.sh.tmpl for all
# other tests. For the purpose of this test, we want to run java_tools at
# runtime 11 to test for failures in incompatible system classpaths.
add_to_bazelrc "build --java_runtime_version=11"
add_to_bazelrc "build --tool_java_runtime_version=11"

add_protobuf "MODULE.bazel"

# Java source files version shall match --java_language_version_flag version.
function test_java17_text_block() {
  mkdir -p java/main
  cat >java/main/BUILD <<EOF
java_binary(
    name = 'Javac17Example',
    srcs = ['Javac17Example.java'],
    main_class = 'Javac17Example',
)
EOF

  cat >java/main/Javac17Example.java <<EOF
public class Javac17Example {
  static Object textBlock = """
              Hello,
              World
              """;

  static sealed class Foo permits Bar {}

  static final class Bar extends Foo {}

  public static void main(String[] args) {
    System.out.println(textBlock);
  }
}
EOF

  bazel run java/main:Javac17Example --java_language_version=11 --java_runtime_version=11 \
     --test_output=all --verbose_failures &>"${TEST_log}" \
     && fail "Running with --java_language_version=11 unexpectedly succeeded."

  bazel run java/main:Javac17Example --java_language_version=17 --java_runtime_version=17 \
     --test_output=all --verbose_failures &>"${TEST_log}" \
     || fail "Running with --java_language_version=17 failed"
  expect_log "^Hello,\$"
  expect_log "^World\$"
}

function test_incompatible_system_classpath() {
  add_rules_java MODULE.bazel
  mkdir -p pkg
  # This test defines a custom Java toolchain as it relies on the availability of a runtime that is
  # strictly newer than the one specified as the toolchain's java_runtime.
  cat >pkg/BUILD <<'EOF'
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain")
java_binary(
    name = "Main",
    srcs = ["Main.java"],
    main_class = "com.example.Main",
)
default_java_toolchain(
    name = "java_toolchain",
    source_version = "17",
    target_version = "17",
    java_runtime = "@rules_java//toolchains:remotejdk_17",
)
EOF

  cat >pkg/Main.java <<'EOF'
package com.example;
import java.net.URI;
public class Main {
  public static void main(String[] args) {
    System.out.println("Hello, world!");
  }
}
EOF

  bazel build //pkg:Main \
    --extra_toolchains=//pkg:java_toolchain_definition \
    --java_language_version=17 \
    --java_runtime_version=remotejdk_21 \
    &>"${TEST_log}" && fail "Expected build to fail"

  expect_log "error: \[BazelJavaConfiguration\] The Java 17 runtime used to run javac is not " \
    "recent enough to compile for the Java 21 runtime in external/remotejdk21_[a-z0-9]*\. Either " \
    "register a Java toolchain with a newer java_runtime or specify a lower " \
    "--java_runtime_version\."
}

function test_incompatible_tool_system_classpath() {
  mkdir -p pkg
  # This test defines a custom Java toolchain as it relies on the availability of a runtime that is
  # strictly newer than the one specified as the toolchain's java_runtime.
  cat >pkg/BUILD <<'EOF'
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain")
java_binary(
    name = "Main",
    srcs = ["Main.java"],
    main_class = "com.example.Main",
)
genrule(
    name = "gen",
    outs = ["gen.txt"],
    tools = [":Main"],
    cmd = "$(location :Main) > $@",
)
default_java_toolchain(
    name = "java_toolchain",
    source_version = "17",
    target_version = "17",
    java_runtime = "@bazel_tools//tools/jdk:remotejdk_17",
)
EOF

  cat >pkg/Main.java <<'EOF'
package com.example;
import java.net.URI;
public class Main {
  public static void main(String[] args) {
    System.out.println("Hello, world!");
  }
}
EOF

  bazel build //pkg:gen \
    --extra_toolchains=//pkg:java_toolchain_definition \
    --tool_java_language_version=17 \
    --tool_java_runtime_version=remotejdk_21 \
    &>"${TEST_log}" && fail "Expected build to fail"

  expect_log "error: \[BazelJavaConfiguration\] The Java 17 runtime used to run javac is not " \
    "recent enough to compile for the Java 21 runtime in external/remotejdk21_[a-z0-9]*\. Either " \
    "register a Java toolchain with a newer java_runtime or specify a lower " \
    "--tool_java_runtime_version\."
}

run_suite "Tests Java 17 language features"
