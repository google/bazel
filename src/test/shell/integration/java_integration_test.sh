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
# These are end to end tests for building Java.
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../shell_utils.sh" \
  || { echo "shell_utils.sh not found!" >&2; exit 1; }

# Load the test setup defined in the parent directory
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

# Load the helper utils.
source "${CURRENT_DIR}/java_integration_test_utils.sh" \
  || { echo "java_integration_test_utils.sh not found!" >&2; exit 1; }
set -eu

JAVABASE="$1"
if [[ "$TEST_WORKSPACE" == "_main" ]]; then
  # For Bazel
  RUNFILES_JAVABASE=${JAVABASE#external/}
  RUNFILES_JAVABASE="$(dirname $(dirname $(rlocation $RUNFILES_JAVABASE/bin/java)))"
else
  # For Blaze
  RUNFILES_JAVABASE=${BAZEL_RUNFILES}/${JAVABASE}
fi

add_to_bazelrc "build --package_path=%workspace%"

function set_up() {
  add_rules_java MODULE.bazel
}

#### HELPER FUNCTIONS ##################################################

function setup_local_jdk() {
  local -r dest="$1"
  local -r src="${RUNFILES_JAVABASE}"

  mkdir -p "$dest" || fail "mkdir -p $dest"
  cp -LR "${src}"/* "$dest" || fail "cp -LR \"${src}\"/* \"$dest\""
  chmod -R ug+rwX "$dest" || fail "chmod -R ug+rwX \"$dest\""
}

function write_hello_world_files() {
  local pkg="$1"
  mkdir -p $pkg/java/hello || fail "mkdir"
  cat >$pkg/java/hello/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(name = 'hello',
    srcs = ['Hello.java'],
    main_class = 'hello.Hello')
EOF

  cat >$pkg/java/hello/Hello.java <<EOF
package hello;
public class Hello {
  public static void main(String[] args) {
    System.out.println("Hello, World!");
  }
}
EOF
}

function write_hello_world_files_for_singlejar() {
  local -r pkg="$1"
  mkdir -p $pkg/java/hello || fail "mkdir"
  cat >$pkg/java/hello/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(name = 'hello',
    srcs = ['Hello.java'],
    main_class = 'hello.Hello')
EOF

  cat >$pkg/java/hello/Hello.java <<EOF
package hello;
import java.util.Properties;
public class Hello {
  private static void printMap(Properties p) {
    System.err.println("Available keys and values are:");
    for (Object key : p.keySet()) {
      System.err.printf("  '%s': '%s'%n", key, p.get(key));
    }
  }

  public static void main(String[] args) throws Exception {
    Properties properties = new Properties();
    properties.load(Hello.class.getResourceAsStream("/build-data.properties"));
    for (String arg : args) {
      String[] keyValue = arg.split("=", 2);
      Object value = properties.get(keyValue[0]);
      if (value == null) {
        System.err.println("Key '" + keyValue[0] + "' not found");
        printMap(properties);
        return;
      }
      if (keyValue.length > 1 && !keyValue[1].equals(value)) {
        System.err.println("Value for key '" + keyValue[0] + "' is '" + value
            + "' while it should be '" + keyValue[1] + "'");
        printMap(properties);
        return;
      }
    }
    System.out.println("Hello, World!");
  }
}
EOF
}

function write_hello_library_files() {
  local -r pkg="$1"
  mkdir -p $pkg/java/main || fail "mkdir"
  cat >$pkg/java/main/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(
    name = 'main',
    deps = ['//$pkg/java/hello_library'],
    srcs = ['Main.java'],
    main_class = 'main.Main',
    deploy_manifest_lines = ['k1: v1', 'k2: v2'])
EOF

  cat >$pkg/java/main/Main.java <<EOF
package main;
import hello_library.HelloLibrary;
public class Main {
  public static void main(String[] args) {
    HelloLibrary.funcHelloLibrary();
    System.out.println("Hello, World!");
  }
}
EOF

  mkdir -p $pkg/java/hello_library || fail "mkdir"
  cat >$pkg/java/hello_library/BUILD <<EOF
load("@rules_java//java:java_library.bzl", "java_library")
package(default_visibility=['//visibility:public'])
java_library(name = 'hello_library',
             srcs = ['HelloLibrary.java']);
EOF

  cat >$pkg/java/hello_library/HelloLibrary.java <<EOF
package hello_library;
public class HelloLibrary {
  public static void funcHelloLibrary() {
    System.out.print("Hello, Library!;");
  }
}
EOF
}

function write_hello_sailor_files() {
  local -r pkg="$1"
  mkdir -p $pkg/java/hellosailor || fail "mkdir"
  cat >$pkg/java/hellosailor/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(name = 'hellosailor',
    srcs = ['HelloSailor.java'],
    create_executable = 0)
EOF

  cat >$pkg/java/hellosailor/HelloSailor.java <<EOF
package hellosailor;
public class HelloSailor {
  public static int addtwoNumbers(int a, int b) {
    return a + b;
  }
}
EOF
}


#### TESTS #############################################################

# This test intentionally show some errors on the standard output.
function test_compiles_hello_world() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_world_files "$pkg"

  bazel clean
  bazel build //$pkg/java/hello:hello || fail "build failed"
  ${PRODUCT_NAME}-bin/$pkg/java/hello/hello | grep -q 'Hello, World!' \
      || fail "comparison failed"
  function check_deploy_jar_should_not_exist() {
    "$@" && fail "deploy jar should not exist"
    true  # reset the last exit code so the test won't be considered failed
  }
  function check_arglists() {
    check_deploy_jar_should_not_exist "$@" --singlejar
    check_deploy_jar_should_not_exist "$@" --wrapper_script_flag=--singlejar
    check_deploy_jar_should_not_exist "$@" REGULAR_ARG \
        --wrapper_script_flag=--singlejar
  }
  check_arglists bazel run //$pkg/java/hello:hello --
  check_arglists ${PRODUCT_NAME}-bin/$pkg/java/hello/hello
}

function test_compiles_hello_world_from_deploy_jar() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_world_files "$pkg"

  bazel build //$pkg/java/hello:hello_deploy.jar || fail "build failed"

  bazel run //$pkg/java/hello:hello -- --singlejar | grep -q 'Hello, World!' \
    || fail "comparison failed"
  ${PRODUCT_NAME}-bin/$pkg/java/hello/hello -- --singlejar | \
    grep -q 'Hello, World!' || fail "comparison failed"

  bazel run //$pkg/java/hello:hello -- --wrapper_script_flag=--singlejar \
    | grep -q 'Hello, World!' || fail "comparison failed"
  ${PRODUCT_NAME}-bin/$pkg/java/hello/hello -- \
    --wrapper_script_flag=--singlejar | grep -q 'Hello, World!' \
    || fail "comparison failed"

  bazel run //$pkg/java/hello:hello -- REGULAR_ARG \
    --wrapper_script_flag=--singlejar | grep -q 'Hello, World!' \
    || fail "comparison failed"
  ${PRODUCT_NAME}-bin/$pkg/java/hello/hello -- REGULAR_ARG \
    --wrapper_script_flag=--singlejar | grep -q 'Hello, World!' \
    || fail "comparison failed"
}

function test_explicit_bogus_wrapper_args_are_rejected() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_world_files "$pkg"

  bazel build //$pkg/java/hello:hello_deploy.jar || fail "build failed"
  function check_arg_rejected() {
    "$@" && fail "bogus arg should be rejected"
    true  # reset the last exit code so the test won't be considered failed
  }
  function check_arglists() {
    check_arg_rejected "$@" --wrapper_script_flag=--bogus
    check_arg_rejected "$@" REGULAR_ARG --wrapper_script_flag=--bogus
  }
  check_arglists bazel run //$pkg/java/hello:hello --
  check_arglists ${PRODUCT_NAME}-bin/$pkg/java/hello/hello
}

function assert_singlejar_works() {
  local -r pkg="$1"
  local -r copy_jdk="$2"
  local -r stamp_arg="$3"
  local -r embed_label="$4"
  local -r expected_build_data="$5"

  write_hello_world_files_for_singlejar "$pkg"

  if "$copy_jdk"; then
    local -r local_jdk="$pkg/my_jdk"
    setup_local_jdk "$local_jdk"

    ln -s "my_jdk" "$pkg/my_jdk.symlink"
    local -r javabase="$(get_real_path "$pkg/my_jdk.symlink")"
  else
    local -r javabase="${RUNFILES_JAVABASE}"
  fi

  mkdir -p "$pkg/jvm"
  cat > "$pkg/jvm/BUILD" <<EOF
load("@rules_java//java/toolchains:java_runtime.bzl", "java_runtime")
package(default_visibility=["//visibility:public"])
java_runtime(
    name='runtime',
    java_home='$javabase',
)
EOF
  create_java_test_platforms

  # Set javabase to an absolute path.
  bazel build //$pkg/java/hello:hello //$pkg/java/hello:hello_deploy.jar \
      "$stamp_arg" \
      --extra_toolchains="//tools/jdk:all" \
      --extra_toolchains="//$pkg/jvm:all" \
      --platforms="//$pkg/jvm:platform" \
      "$embed_label" >&"$TEST_log" \
      || fail "Build failed"

  mkdir $pkg/ugly/ || fail "mkdir failed"
  # The stub script follows symlinks, so copy the files.
  cp ${PRODUCT_NAME}-bin/$pkg/java/hello/hello $pkg/ugly/
  cp ${PRODUCT_NAME}-bin/$pkg/java/hello/hello_deploy.jar $pkg/ugly/
  $pkg/ugly/hello build.target build.time build.timestamp \
      main.class=hello.Hello "$expected_build_data" >> $TEST_log 2>&1
  expect_log 'Hello, World!'
}

function test_singlejar_with_default_jdk_with_stamp() {
  local -r pkg="${FUNCNAME[0]}"
  assert_singlejar_works "$pkg" true "--stamp" "--embed_label=toto" \
      "build.label=toto"
}

# Regression test for b/17658100, ensure that --nostamp generate correct
# build-info.properties file.
function test_singlejar_with_default_jdk_without_stamp() {
  local -r pkg="${FUNCNAME[0]}"
  assert_singlejar_works "$pkg" true "--nostamp" "--embed_label=" \
      "build.timestamp.as.int=0"
}

# Regression test for b/3244955, to ensure that running the deploy jar works
# even without the runfiles available.
function test_singlejar_with_custom_jdk_with_stamp() {
  local -r pkg="${FUNCNAME[0]}"
  assert_singlejar_works "$pkg" false "--stamp" "--embed_label=toto" \
      "build.label=toto"
}

function test_singlejar_with_custom_jdk_without_stamp() {
  local -r pkg="${FUNCNAME[0]}"
  assert_singlejar_works "$pkg" false "--nostamp" "--embed_label=" \
      "build.timestamp.as.int=0"
}

# Regression test for b/18191163: ensure that the build is deterministic when
# used with --nostamp.
function test_deterministic_nostamp_build() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_world_files "$pkg"

  bazel clean || fail "Clean failed"
  bazel build --nostamp //$pkg/java/hello:hello_deploy.jar \
      || fail "Build failed"
  # TODO(bazel-team) .a files (C/C++ static library file generated by
  # archive tool) on darwin OS only are not deterministic.
  # https://github.com/bazelbuild/bazel/issues/3156
  local -r first_run="$(md5_file $(find "${PRODUCT_NAME}-out/" -type f '!' \
      -name build-changelist.txt -a '!' -name volatile-status.txt \
      -a '!' -name 'stderr-*' -a '!' -name '*.a' \
      -a '!' -name __xcodelocatorcache -a '!' -name __xcruncache \
      | sort -u))"

  sleep 1  # Ensure the timestamp change between builds.

  bazel clean || fail "Clean failed"
  bazel build --nostamp //$pkg/java/hello:hello_deploy.jar \
      || fail "Build failed"
  local -r second_run="$(md5_file $(find "${PRODUCT_NAME}-out/" -type f '!' \
      -name build-changelist.txt -a '!' -name volatile-status.txt \
      -a '!' -name 'stderr-*' -a '!' -name '*.a' \
      -a '!' -name __xcodelocatorcache -a '!' -name __xcruncache \
      | sort -u))"

  assert_equals "$first_run" "$second_run"
}

function test_compiles_hello_library() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_library_files "$pkg"

  bazel clean
  bazel build //$pkg/java/main:main || fail "build failed"
  ${PRODUCT_NAME}-bin/$pkg/java/main/main \
      | grep -q "Hello, Library!;Hello, World!" || fail "comparison failed"
  bazel run //$pkg/java/main:main -- --singlejar && fail "deploy jar should not exist"

  true  # reset the last exit code so the test won't be considered failed
}

function test_compiles_hello_library_using_ijars() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_library_files "$pkg"

  bazel clean
  bazel build --use_ijars //$pkg/java/main:main || fail "build failed"
  ${PRODUCT_NAME}-bin/$pkg/java/main/main \
      | grep -q "Hello, Library!;Hello, World!" || fail "comparison failed"
}

function test_compiles_hello_library_from_deploy_jar() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_library_files "$pkg"

  bazel build //$pkg/java/main:{main,main_deploy.jar} || fail "build failed"
  ${PRODUCT_NAME}-bin/$pkg/java/main/main --singlejar \
      | grep -q "Hello, Library!;Hello, World!" || fail "comparison failed"

  unzip -p ${PRODUCT_NAME}-bin/$pkg/java/main/main_deploy.jar META-INF/MANIFEST.MF \
      | grep -q "k1: v1" || fail "missing manifest lines"
  unzip -p ${PRODUCT_NAME}-bin/$pkg/java/main/main_deploy.jar META-INF/MANIFEST.MF \
      | grep -q "k2: v2" || fail "missing manifest lines"
}

function test_building_deploy_jar_twice_does_not_rebuild() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_library_files "$pkg"

  bazel build //$pkg/java/main:main_deploy.jar || fail "build failed"
  touch -r ${PRODUCT_NAME}-bin/$pkg/java/main/main_deploy.jar old
  bazel build //$pkg/java/main:main_deploy.jar || fail "build failed"
  find ${PRODUCT_NAME}-bin/$pkg/java/main/main_deploy.jar -newer old \
    | grep -q . && fail "file was rebuilt"

  true  # reset the last exit code so the test won't be considered failed
}

function test_does_not_create_executable_when_not_asked_for() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_sailor_files "$pkg"

  bazel build //$pkg/java/hellosailor:hellosailor_deploy.jar \
      || fail "build failed"

  if [[ ! -e ${PRODUCT_NAME}-bin/$pkg/java/hellosailor/hellosailor.jar ]]; then
      fail "output jar does not exist";
  fi

  if [[ -e ${PRODUCT_NAME}-bin/$pkg/java/hellosailor/hellosailor ]]; then
      fail "output executable should not exist";
  fi

  if [[ ! -e ${PRODUCT_NAME}-bin/$pkg/java/hellosailor/hellosailor_deploy.jar ]]; then
    fail "output deploy jar does not exist";
  fi

}

# Assert that a deploy jar can be a dependency of another java_binary.
function test_building_deploy_jar_dependent_on_deploy_jar() {
 local -r pkg="${FUNCNAME[0]}"
  mkdir -p $pkg/java/deploy || fail "mkdir"
  cat > $pkg/java/deploy/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(name = 'Hello',
            srcs = ['Hello.java'],
            deps = ['Other_deploy.jar'],
            main_class = 'hello.Hello')
java_binary(name = 'Other',
            resources = ['//$pkg/hello:Test.txt'],
            main_class = 'none')
EOF

  cat >$pkg/java/deploy/Hello.java <<EOF
package deploy;
public class Hello {
  public static void main(String[] args) {
    System.out.println("Hello, World!");
  }
}
EOF

  mkdir -p $pkg/hello
  echo "exports_files(['Test.txt'])" >$pkg/hello/BUILD
  echo "Some other File" >$pkg/hello/Test.txt

  bazel build //$pkg/java/deploy:Hello_deploy.jar || fail "build failed"
  unzip -p ${PRODUCT_NAME}-bin/$pkg/java/deploy/Hello_deploy.jar \
      $pkg/hello/Test.txt | grep -q "Some other File" || fail "missing resource"
}

function test_wrapper_script_arg_handling() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p $pkg/java/hello/ || fail "Expected success"
  cat > $pkg/java/hello/Test.java <<EOF
package hello;
public class Test {
  public static void main(String[] args) {
    System.out.print("Args:");
    for (String arg : args) {
      System.out.print(" '" + arg + "'");
    }
    System.out.println();
  }
}
EOF

  cat > $pkg/java/hello/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(name='hello', srcs=['Test.java'], main_class='hello.Test')
EOF

  bazel run //$pkg/java/hello:hello -- '' foo '' '' 'bar quux' '' \
      >&$TEST_log || fail "Build failed"
  expect_log "Args: '' 'foo' '' '' 'bar quux' ''"
}

function test_srcjar_compilation() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p $pkg/java/hello/ || fail "Expected success"
  cat > $pkg/java/hello/Test.java <<EOF
package hello;
public class Test {
  public static void main(String[] args) {
    System.out.println("Hello World!");
  }
}
EOF
  cd $pkg
  zip -q java/hello/test.srcjar java/hello/Test.java || fail "zip failed"
  cd ..

  cat > $pkg/java/hello/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(name='hello', srcs=['test.srcjar'], main_class='hello.Test')
EOF
  bazel build //$pkg/java/hello:hello //$pkg/java/hello:hello_deploy.jar \
      >&$TEST_log || fail "Expected success"
  bazel run //$pkg/java/hello:hello -- --singlejar >&$TEST_log
  expect_log "Hello World!"
}

function test_private_initializers() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p $pkg/java/hello/ || fail "Expected success"

  cat > $pkg/java/hello/A.java <<EOF
package hello;
public class A { private B b; }
EOF

  cat > $pkg/java/hello/B.java <<EOF
package hello;
public class B { private C c; }
EOF

  cat > $pkg/java/hello/C.java <<EOF
package hello;
public class C {}
EOF

  # This definition is only to make sure that A's interface is built.
  cat > $pkg/java/hello/App.java <<EOF
package hello;
public class App { }
EOF

  cat > $pkg/java/hello/BUILD <<EOF
load("@rules_java//java:java_library.bzl", "java_library")
java_library(name = 'app',
             srcs = ['App.java'],
             deps = [':a'])

java_library(name = 'a',
             srcs = ['A.java'],
             deps = [':b'])

java_library(name = 'b',
             srcs = ['B.java'],
             deps = [':c'])

java_library(name = 'c',
             srcs = ['C.java'])
EOF

  bazel build //$pkg/java/hello:app || fail "Expected success"
}

function test_java_plugin() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p $pkg/java/test/processor || fail "mkdir"

  cat >$pkg/java/test/processor/BUILD <<EOF
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_java//java:java_plugin.bzl", "java_plugin")
package(default_visibility=['//visibility:public'])

java_library(name = 'annotation',
  srcs = [ 'TestAnnotation.java' ])

java_library(name = 'processor_dep',
  srcs = [ 'ProcessorDep.java' ])

java_plugin(name = 'processor',
  processor_class = 'test.processor.Processor',
  deps = [ ':annotation', ':processor_dep' ],
  srcs = [ 'Processor.java' ])
EOF

  cat >$pkg/java/test/processor/TestAnnotation.java <<EOF
package test.processor;
import java.lang.annotation.*;
@Target(value = {ElementType.TYPE})

public @interface TestAnnotation {
}
EOF

  cat >$pkg/java/test/processor/ProcessorDep.java <<EOF
package test.processor;

class ProcessorDep {
  static String value = "DependencyValue";
}
EOF

  cat >$pkg/java/test/processor/Processor.java <<EOF
package test.processor;
import java.util.*;
import java.io.*;
import javax.annotation.processing.*;
import javax.tools.*;
import javax.lang.model.*;
import javax.lang.model.element.*;
@SupportedAnnotationTypes(value= {"test.processor.TestAnnotation"})
public class Processor extends AbstractProcessor {
  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
  private static final String OUTFILE_CONTENT = "package test;\n"
      + "public class Generated {\n"
      + "  public static String value = \"" + ProcessorDep.value + "\";\n"
      + "}";
  private ProcessingEnvironment mainEnvironment;
  public void init(ProcessingEnvironment environment) {
    mainEnvironment = environment;
  }
  public boolean process(Set<? extends TypeElement> annotations,
      RoundEnvironment roundEnv) {
    Filer filer = mainEnvironment.getFiler();
    try {
      FileObject output = filer.createSourceFile("test.Generated");
      Writer writer = output.openWriter();
      writer.append(OUTFILE_CONTENT);
      writer.close();
    } catch (IOException ex) {
      return false;
    }
    return true;
  }
}
EOF

  mkdir -p $pkg/java/test/client
  cat >$pkg/java/test/client/BUILD <<EOF
load("@rules_java//java:java_library.bzl", "java_library")
java_library(name = 'client',
     srcs = [ 'ProcessorClient.java' ],
     deps = [ '//$pkg/java/test/processor:annotation' ],
  plugins = [ '//$pkg/java/test/processor:processor' ])
EOF

  cat >$pkg/java/test/client/ProcessorClient.java <<EOF
package test.client;
import test.processor.TestAnnotation;
@TestAnnotation()
class ProcessorClient { }
EOF

  bazel build //$pkg/java/test/client:client --use_ijars || fail "build failed"
  unzip -l ${PRODUCT_NAME}-bin/$pkg/java/test/client/libclient.jar > $TEST_log
  expect_log " test/Generated.class" "missing class file from annotation processing"

  bazel build //$pkg/java/test/client:libclient-src.jar --use_ijars \
    || fail "build failed"
  unzip -l ${PRODUCT_NAME}-bin/$pkg/java/test/client/libclient-src.jar > $TEST_log
  expect_log " test/Generated.java" "missing source file from annotation processing"
}

function test_jvm_flags_are_passed_verbatim() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p $pkg/java/com/google/jvmflags || fail "mkdir"
  cat >$pkg/java/com/google/jvmflags/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(
    name = 'foo',
    srcs = ['Foo.java'],
    main_class = 'com.google.jvmflags.Foo',
    toolchains = ['${TOOLS_REPOSITORY}//tools/jdk:current_java_runtime'],
    jvm_flags = [
        # test quoting
        '--a=\\'single_single\\'',
        '--b="single_double"',
        "--c='double_single'",
        "--d=\\"double_double\\"",
        '--e=no_quotes',
        # no escaping expected
        '--f=stuff\$\$to"escape\\\\',
    ],
)
EOF

  cat >$pkg/java/com/google/jvmflags/Foo.java <<EOF
package com.google.jvmflags;
public class Foo { public static void main(String[] args) {} }
EOF

  bazel build //$pkg/java/com/google/jvmflags:foo || fail "build failed"

  STUBSCRIPT=${PRODUCT_NAME}-bin/$pkg/java/com/google/jvmflags/foo
  [ -e $STUBSCRIPT ] || fail "$STUBSCRIPT not found"

  for flag in \
      " --a='single_single' " \
      " --b=\"single_double\" " \
      " --c='double_single' " \
      " --d=\"double_double\" " \
      ' --e=no_quotes ' \
      ' --f=stuff$to"escape\\ ' \
      ; do
    # NOTE: don't test the full path of the JDK, it's architecture-dependent.
    assert_contains $flag $STUBSCRIPT
  done
}

function test_classpath_fiddling() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_library_files "$pkg"

  mkdir -p $pkg/java/classpath
  cat >$pkg/java/classpath/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(name = 'classpath',
    deps = ['//$pkg/java/hello_library'],
    srcs = ['Classpath.java'],
    main_class = 'classpath.Classpath')
EOF

  cat >$pkg/java/classpath/Classpath.java <<'EOF'
package classpath;
public class Classpath {
  public static void main(String[] args) {
    String cp = System.getProperty("java.class.path");
    String[] jars = cp.split(":");  // TODO(bazel-team): this is ";" on Windows
    boolean singlejar
        = (args.length > 1 && args[1].equals("SINGLEJAR"));
    System.out.printf("CLASSPATH=%s%n", cp);
    if (jars.length != 2 && !singlejar) {
      throw new Error("Unexpected class path length");
    }
    String jarRegex = args[0];
    for (String jar : jars) {
      if (!jar.matches(jarRegex)) {
        throw new Error("No match for regex: " + jarRegex);
      }
      if (!new java.io.File(jar).exists()) {
        throw new Error("No such file: " + jar);
      }
    }
  }
}
EOF

  bazel clean
  bazel build //$pkg/java/classpath || fail "build failed"
  bazel run //$pkg/java/classpath -- \
    "^$pkg/java/(classpath|hello_library)/.*\.jar\$" || fail "bazel run"

  local PROG="${PRODUCT_NAME}-bin/$pkg/java/classpath/classpath"

  function check_classpath_invocations() {
    "$PROG" "^${PRODUCT_NAME}-bin/.*\.jar\$" "$@" \
      || fail "direct run relative classpath $*"
    "./$PROG" "^\./${PRODUCT_NAME}-bin/.*\.jar\$" "$@" \
      || fail "direct run '.'-relative classpath $*"
    "$PWD/$PROG" "^${PRODUCT_NAME}-bin/.*\.jar\$" "$@" \
      || fail "direct run absolute classpath $*"
    (PROG="$PWD/$PROG"; cd / && exec "$PROG" '^/.*\.jar$' "$@") \
      || fail "direct run from / absolute classpath $*"
  }

  check_classpath_invocations

  # Test --singlejar and --wrapper_script_flag
  bazel build //$pkg/java/classpath:classpath_deploy.jar || fail "build failed"
  for prog in "$PROG" "./$PROG" "$PWD/$PROG"; do
    "$prog" --singlejar '.*_deploy.jar$' "SINGLEJAR" \
      || fail "$prog --singlejar"
    "$prog" '.*_deploy.jar$' "SINGLEJAR" --wrapper_script_flag=--singlejar \
      || fail "$prog --wrapper_script_flag=--singlejar"
  done
}

function test_java7() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p $pkg/java/foo/ || fail "Expected success"
  cat > $pkg/java/foo/Foo.java <<EOF
package foo;
import java.lang.invoke.MethodHandle;   // In Java 7 class library only
import java.util.ArrayList;
public class Foo {
  public static void main(String[] args) {
    ArrayList<Object> list = new ArrayList<>(); // In Java 7 language only
    System.out.println("Success!");
  }
}
EOF

  cat > $pkg/java/foo/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
java_binary(name = 'foo',
    srcs = ['Foo.java'],
    main_class = 'foo.Foo')
EOF

  bazel run //$pkg/java/foo:foo | grep -q "Success!" || fail "Expected success"
}

function test_header_compilation() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir "$pkg" || fail "mkdir $pkg"
  write_hello_library_files "$pkg"

  bazel build -s --java_header_compilation=true \
      //$pkg/java/main:main || fail "build failed"
  unzip -l ${PRODUCT_NAME}-bin/$pkg/java/hello_library/libhello_library-hjar.jar \
    > $TEST_log
  expect_log " hello_library/HelloLibrary.class" \
    "missing class file from header compilation"
}

function test_header_compilation_errors() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p $pkg/java/test/ || fail "Expected success"
  cat > $pkg/java/test/A.java <<EOF
package test;
public class A {}
EOF
  cat > $pkg/java/test/B.java <<EOF
package test;
import missing.NoSuch;
@NoSuch
public class B {}
EOF
  cat > $pkg/java/test/BUILD <<EOF
load("@rules_java//java:java_library.bzl", "java_library")
java_library(
    name='a',
    srcs=['A.java'],
    deps=[':b'],
)
java_library(
    name='b',
    srcs=['B.java'],
)
EOF
  bazel build --java_header_compilation=true \
    //$pkg/java/test:liba.jar >& "$TEST_log" && fail "Unexpected success"
  expect_log "symbol not found missing.NoSuch"
}

function test_java_import_with_empty_jars_attribute() {
  local -r pkg="${FUNCNAME[0]}"
  mkdir -p $pkg/java/hello/ || fail "Expected success"
  cat > $pkg/java/hello/Hello.java <<EOF
package hello;
public class Hello {
  public static void main(String[] args) {
    System.out.println("Hello World!");
  }
}
EOF
  cat > $pkg/java/hello/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
load("@rules_java//java:java_import.bzl", "java_import")
java_import(
    name='empty_java_import',
    jars=[]
)
java_binary(
    name='hello',
    srcs=['Hello.java'],
    deps=[':empty_java_import'],
    main_class = 'hello.Hello'
)
EOF
  bazel build --incompatible_disallow_java_import_empty_jars=0 //$pkg/java/hello:hello //$pkg/java/hello:hello_deploy.jar >& "$TEST_log" \
      || fail "Expected success"
  bazel run --incompatible_disallow_java_import_empty_jars=0 //$pkg/java/hello:hello -- --singlejar >& "$TEST_log"
  expect_log "Hello World!"
}

function test_arg_compile_action() {
  local package="${FUNCNAME[0]}"
  mkdir -p "${package}"

  cat > "${package}/lib.bzl" <<EOF
def _actions_test_impl(target, ctx):
    javac_actions = [a for a in target.actions if a.mnemonic == "Javac"]
    if len(javac_actions) != 1:
      fail("Expected exactly one Javac action, but found", len(javac_actions))
    action = javac_actions[0]
    aspect_out = ctx.actions.declare_file('aspect_out')
    ctx.actions.run_shell(inputs = action.inputs,
                          outputs = [aspect_out],
                          command = "echo \$@ > " + aspect_out.path,
                          arguments = action.args)
    return [OutputGroupInfo(out=[aspect_out])]

actions_test_aspect = aspect(implementation = _actions_test_impl)
EOF

  touch "${package}/x.java"
  cat > "${package}/BUILD" <<EOF
load("@rules_java//java:java_library.bzl", "java_library")
java_library(
  name = "x",
  srcs = ["x.java"],
)
EOF

  bazel build "${package}:x" \
      --aspects="//${package}:lib.bzl%actions_test_aspect" \
      --output_groups=out

  cat "${PRODUCT_NAME}-bin/${package}/aspect_out" | grep "0.params .*1.params" \
      || fail "aspect Args do not contain both params files"
}

run_suite "Java integration tests"
