#!/bin/bash
#
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
#
# Tests Java toolchains, configured using flags or the default_java_toolchain macro.
#

set -euo pipefail

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

# Java source files version shall match --java_language_version_flag version.
# Output class files shall be created in corresponding version (JDK 8, class version is 52).
function test_default_java_toolchain_target_version() {
  mkdir -p java/main
  cat >java/main/BUILD <<EOF
java_binary(
    name = 'JavaBinary',
    srcs = ['JavaBinary.java'],
    main_class = 'JavaBinary',
)
load(
    "@bazel_tools//tools/jdk:default_java_toolchain.bzl",
    "default_java_toolchain",
)
default_java_toolchain(
  name = "default_toolchain",
  source_version = "8",
  target_version = "8",
  visibility = ["//visibility:public"],
)
EOF

   cat >java/main/JavaBinary.java <<EOF
public class JavaBinary {
   public static void main(String[] args) {
    System.out.println("Successfully executed JavaBinary!");
  }
}
EOF
  bazel run java/main:JavaBinary \
      --java_language_version=8 \
      --java_runtime_version=11 \
      --extra_toolchains=//java/main:default_toolchain_definition \
      --verbose_failures -s &>"${TEST_log}" \
      || fail "Building with //java/main:default_toolchain failed"
  expect_log "Successfully executed JavaBinary!"
  javap -verbose -cp bazel-bin/java/main/JavaBinary.jar JavaBinary | grep major &>"${TEST_log}"
  expect_log "major version: 52"
}

# Java source files version shall match --java_language_version_flag version.
# Output class files shall be created in corresponding version (JDK 11, class version is 55).
function test_java_language_version_output_classes() {
  mkdir -p java/main
  cat >java/main/BUILD <<EOF
java_binary(
    name = 'JavaBinary',
    srcs = ['JavaBinary.java'],
    main_class = 'JavaBinary',
)
EOF

   cat >java/main/JavaBinary.java <<EOF
public class JavaBinary {
   public static void main(String[] args) {
    // Java 11 new String methods.
    String myString = "   strip_trailing_java11   ";
    System.out.println(myString.stripLeading().stripTrailing());
  }
}
EOF
  bazel run java/main:JavaBinary --java_language_version=11 --java_runtime_version=11 \
      --verbose_failures -s &>"${TEST_log}" \
      || fail "Building with --java_language_version=11 failed"
  expect_log "strip_trailing_java11"
  javap -verbose -cp bazel-bin/java/main/JavaBinary.jar JavaBinary | grep major &>"${TEST_log}"
  expect_log "major version: 55"

  bazel run java/main:JavaBinary --java_language_version=17 --java_runtime_version=17 \
      --verbose_failures -s &>"${TEST_log}" \
      || fail "Building with --java_language_version=17 failed"
  expect_log "strip_trailing_java11"
  javap -verbose -cp bazel-bin/java/main/JavaBinary.jar JavaBinary | grep major &>"${TEST_log}"
  expect_log "major version: 61"
}

# When coverage is requested with no Jacoco configured, an error shall be reported.
function test_tools_jdk_toolchain_nojacocorunner() {
  mkdir -p java/main
  cat >java/main/BUILD <<EOF
java_binary(
    name = 'JavaBinary',
    srcs = ['JavaBinary.java'],
    main_class = 'JavaBinary',
)
load(
    "@bazel_tools//tools/jdk:default_java_toolchain.bzl",
    "default_java_toolchain",
)
default_java_toolchain(
  name = "default_toolchain",
  jacocorunner = None,
  visibility = ["//visibility:public"],
)
EOF

   cat >java/main/JavaBinary.java <<EOF
public class JavaBinary {
   public static void main(String[] args) {
    System.out.println("Successfully executed JavaBinary!");
  }
}
EOF
  bazel coverage java/main:JavaBinary \
      --java_runtime_version=11 \
      --extra_toolchains=//java/main:all \
      --verbose_failures -s &>"${TEST_log}" \
      && fail "Coverage succeeded even when jacocorunner not set"
  expect_log "jacocorunner not set in java_toolchain:"
}

# Specific toolchain attributes can be overridden.
function test_default_java_toolchain_manualConfiguration() {
  cat > BUILD <<EOF
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain")
default_java_toolchain(
  name = "vanilla",
  javabuilder = ["//:VanillaJavaBuilder"],
  jvm_opts = [],
)
EOF

  bazel build //:vanilla || fail "default_java_toolchain target failed to build"
  bazel cquery --output=build //:vanilla >& $TEST_log || fail "failed to query //:vanilla"

  expect_log 'jvm_opts = \[\]'
  expect_log 'javabuilder = "//:VanillaJavaBuilder"'
}

# DEFAULT_TOOLCHAIN_CONFIGURATION shall use JavaBuilder and override Java 9+ internal compiler classes.
function test_default_java_toolchain_javabuilderToolchain() {
  cat > BUILD <<EOF
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain", "DEFAULT_TOOLCHAIN_CONFIGURATION")
default_java_toolchain(
  name = "javabuilder_toolchain",
  configuration = DEFAULT_TOOLCHAIN_CONFIGURATION,
)
EOF

  bazel build //:javabuilder_toolchain || fail "default_java_toolchain target failed to build"
  bazel cquery 'deps(//:javabuilder_toolchain)' >& $TEST_log || fail "failed to query //:javabuilder_toolchain"

  expect_log ":JavaBuilder"
  expect_not_log ":VanillaJavaBuilder"
}

# VANILLA_TOOLCHAIN_CONFIGURATION shall use VanillaJavaBuilder and not override any JDK internal compiler classes.
function test_default_java_toolchain_vanillaToolchain() {
  add_rules_java MODULE.bazel
  cat >> MODULE.bazel <<EOF
java_toolchains = use_extension("@rules_java//java:extensions.bzl", "toolchains")
use_repo(java_toolchains, "local_jdk")
EOF
  cat > BUILD <<EOF
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain", "VANILLA_TOOLCHAIN_CONFIGURATION")
default_java_toolchain(
  name = "vanilla_toolchain",
  configuration = VANILLA_TOOLCHAIN_CONFIGURATION,
  java_runtime = "@local_jdk//:jdk",
)
EOF

  bazel build //:vanilla_toolchain || fail "default_java_toolchain target failed to build"
  bazel cquery 'deps(//:vanilla_toolchain)' >& $TEST_log || fail "failed to query //:vanilla_toolchain"

  expect_log ":VanillaJavaBuilder"
  expect_not_log ":JavaBuilder"
}

# NONPREBUILT_TOOLCHAIN_CONFIGURATION shall compile ijar and singlejar from sources.
function test_default_java_toolchain_nonprebuiltToolchain() {
  cat > BUILD <<EOF
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain", "NONPREBUILT_TOOLCHAIN_CONFIGURATION")
default_java_toolchain(
  name = "nonprebuilt_toolchain",
  configuration = NONPREBUILT_TOOLCHAIN_CONFIGURATION,
)
EOF

  bazel build //:nonprebuilt_toolchain || fail "default_java_toolchain target failed to build"
  bazel cquery 'deps(//:nonprebuilt_toolchain)' >& $TEST_log || fail "failed to query //:nonprebuilt_toolchain"

  expect_log "ijar/ijar.cc"
  expect_log "singlejar/singlejar_main.cc"
  expect_not_log "ijar/ijar\(.exe\)\? "
  expect_not_log "singlejar/singlejar_local"
}

function test_executable_java_binary_compiles_for_platform_without_cc_toolchain() {
  cat > MODULE.bazel <<'EOF'
# This version should always be at most as high as the version in MODULE.tools.
bazel_dep(name = "rules_java", version = "7.3.2")
java_toolchains = use_extension("@rules_java//java:extensions.bzl", "toolchains")
use_repo(java_toolchains, "remotejdk17_linux")
register_toolchains(
  "//pkg:runtime",
  "//pkg:bootstrap_runtime",
)
EOF
  mkdir -p pkg
# Choose a platform with a registered JDK, but for which no registered C++ toolchain can compile, to
# verify that java_binary doesn't have a mandatory dependency on a C++ toolchain. The particular
# architecture of the fake registered JDK doesn't matter as it won't be executed.
  cat > pkg/BUILD.bazel <<'EOF'
constraint_setting(
  name = "exotic_constraint",
)
constraint_value(
  name = "exotic_value",
  constraint_setting = ":exotic_constraint",
)
platform(
  name = "exotic_platform",
  constraint_values = [":exotic_value"],
)
toolchain(
  name = "runtime",
  target_compatible_with = [":exotic_value"],
  toolchain_type = "@bazel_tools//tools/jdk:runtime_toolchain_type",
  toolchain = "@remotejdk17_linux//:jdk",
)
toolchain(
  name = "bootstrap_runtime",
  target_compatible_with = [":exotic_value"],
  toolchain_type = "@bazel_tools//tools/jdk:runtime_toolchain_type",
  toolchain = "@remotejdk17_linux//:jdk",
)
java_binary(
  name = "foo",
  srcs = ["Foo.java"],
  main_class = "com.example.Foo",
)
cc_binary(
  name = "cc",
)
EOF

    cat > pkg/Foo.java <<'EOF'
package com.example;
public class Foo {
  public static void main(String[] args) {
    System.out.println("Hello World!");
  }
}
EOF

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=remotejdk_17 \
    //pkg:cc &>"$TEST_log" && fail "C++ build should fail"
  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=remotejdk_17 \
    //pkg:foo &>"$TEST_log" || fail "Build should succeed"
  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=remotejdk_17 \
    //pkg:foo_deploy.jar &>"$TEST_log" || fail "Build should succeed"
}

function test_java_library_compiles_for_any_platform_with_local_jdk() {
  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
platform(name = "exotic_platform")
java_library(
  name = "foo",
  srcs = ["Foo.java"],
)
EOF

    cat > pkg/Foo.java <<'EOF'
package com.example;
public class Foo {
  public static void main(String[] args) {
    System.out.println("Hello World!");
  }
}
EOF

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=local_jdk \
    //pkg:foo &>"$TEST_log" || fail "Build should succeed"
}

function test_java_library_compiles_for_any_platform_with_remote_jdk() {
  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
platform(name = "exotic_platform")
java_library(
  name = "foo",
  srcs = ["Foo.java"],
)
EOF

    cat > pkg/Foo.java <<'EOF'
package com.example;
public class Foo {
  public static void main(String[] args) {
    System.out.println("Hello World!");
  }
}
EOF

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=remotejdk_11 \
    //pkg:foo &>"$TEST_log" || fail "Build should succeed"
}

function test_non_executable_java_binary_compiles_for_any_platform_with_local_jdk() {
  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
platform(name = "exotic_platform")
java_binary(
  name = "foo",
  srcs = ["Foo.java"],
  create_executable = False,
)
EOF

    cat > pkg/Foo.java <<'EOF'
package com.example;
public class Foo {
  public static void main(String[] args) {
    System.out.println("Hello World!");
  }
}
EOF

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=local_jdk \
    //pkg:foo &>"$TEST_log" || fail "Build should succeed"
  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=local_jdk \
    //pkg:foo_deploy.jar &>"$TEST_log" || fail "Build should succeed"
}

function test_non_executable_java_binary_compiles_for_any_platform_with_remote_jdk() {
  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
platform(name = "exotic_platform")
java_binary(
  name = "foo",
  srcs = ["Foo.java"],
  create_executable = False,
)
EOF

    cat > pkg/Foo.java <<'EOF'
package com.example;
public class Foo {
  public static void main(String[] args) {
    System.out.println("Hello World!");
  }
}
EOF

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=remotejdk_11 \
    //pkg:foo &>"$TEST_log" || fail "Build should succeed"

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=remotejdk_11 \
    //pkg:foo_deploy.jar &>"$TEST_log" || fail "Build should succeed"
}

function test_executable_java_binary_fails_without_runtime_with_local_jdk() {
  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
platform(name = "exotic_platform")
java_binary(
  name = "foo",
  srcs = ["Foo.java"],
  main_class = "com.example.Foo",
)
EOF

    cat > pkg/Foo.java <<'EOF'
package com.example;
public class Foo {
  public static void main(String[] args) {
    System.out.println("Hello World!");
  }
}
EOF

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=local_jdk \
    //pkg:foo &>"$TEST_log" && fail "Build should fail"
  expect_log "While resolving toolchains for target //pkg:foo ([0-9a-f]*): No matching toolchains found for types:$"
  expect_log "^  @@bazel_tools//tools/jdk:runtime_toolchain_type$"

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=local_jdk \
    //pkg:foo_deploy.jar &>"$TEST_log" && fail "Build should fail"
  expect_log "While resolving toolchains for target //pkg:foo_deploy.jar ([0-9a-f]*): No matching toolchains found for types:$"
  expect_log "^  @@bazel_tools//tools/jdk:runtime_toolchain_type$"
}

function test_executable_java_binary_fails_without_runtime_with_remote_jdk() {
  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
platform(name = "exotic_platform")
java_binary(
  name = "foo",
  srcs = ["Foo.java"],
  main_class = "com.example.Foo",
)
EOF

  cat > pkg/Foo.java <<'EOF'
package com.example;
public class Foo {
  public static void main(String[] args) {
    System.out.println("Hello World!");
  }
}
EOF

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=remotejdk_11 \
    //pkg:foo &>"$TEST_log" && fail "Build should fail"
  expect_log "While resolving toolchains for target //pkg:foo ([0-9a-f]*): No matching toolchains found for types:$"
  expect_log "^  @@bazel_tools//tools/jdk:runtime_toolchain_type$"

  bazel build --platforms=//pkg:exotic_platform --java_runtime_version=remotejdk_11 \
    //pkg:foo_deploy.jar &>"$TEST_log" && fail "Build should fail"
  expect_log "While resolving toolchains for target //pkg:foo_deploy.jar ([0-9a-f]*): No matching toolchains found for types:$"
  expect_log "^  @@bazel_tools//tools/jdk:runtime_toolchain_type$"
}

run_suite "Java toolchains tests, configured using flags or the default_java_toolchain macro."
