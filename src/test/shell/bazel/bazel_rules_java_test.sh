#!/bin/bash
#
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
#
# Tests the examples provided in Bazel
#

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

function test_rules_java_can_be_overridden() {
  # The bazelrc file might contain an --override_repository flag for rules_java,
  # which would cause this test to fail to override the repo via a WORKSPACE file.
  sed -i.bak '/override_repository=rules_java=/d' $TEST_TMPDIR/bazelrc

  # We test that a custom repository can override @platforms in their
  # WORKSPACE file.
  mkdir -p rules_java_can_be_overridden || fail "couldn't create directory"
  touch rules_java_can_be_overridden/BUILD || \ fail "couldn't touch BUILD file"
  cat > rules_java_can_be_overridden/WORKSPACE <<EOF
local_repository(
  name = 'rules_java',
  path = '../override',
)
EOF

  mkdir -p override/java || fail "couldn't create override directory"
  touch override/WORKSPACE || fail "couldn't touch override/WORKSPACE"
  cat > override/BUILD <<EOF
filegroup(name = 'yolo')
EOF
  touch override/java/BUILD || fail "couldn't touch override/java/BUILD"
  cat > override/java/rules_java_deps.bzl <<EOF
def rules_java_dependencies():
    pass
EOF
  cat > override/java/repositories.bzl <<EOF
def rules_java_toolchains():
    pass
EOF

  cd rules_java_can_be_overridden || fail "couldn't cd into workspace"
  bazel build --incompatible_autoload_externally= --noenable_bzlmod --enable_workspace @rules_java//:yolo &> $TEST_log || \
    fail "Bazel failed to build @rules_java"
}

function test_rules_java_repository_builds_itself() {
  add_rules_java "MODULE.bazel"
  write_default_bazelrc

  # We test that a built-in @rules_java repository is buildable.
  bazel build -- @rules_java//java/... &> $TEST_log \
      || fail "Build failed unexpectedly"
}

# Formerly --experimental_java_library_export
function test_java_library_extension_support() {
  add_rules_java "MODULE.bazel"
  write_default_bazelrc

  mkdir -p java
  cat >java/java_library.bzl <<EOF
load("@rules_java//java/common/rules/impl:bazel_java_library_impl.bzl", "bazel_java_library_rule")
load("@rules_java//java/common/rules:java_library.bzl", "JAVA_LIBRARY_ATTRS")
def _impl(ctx):
    return bazel_java_library_rule(
        ctx,
        ctx.files.srcs,
        ctx.attr.deps,
        ctx.attr.runtime_deps,
        ctx.attr.plugins,
        ctx.attr.exports,
        ctx.attr.exported_plugins,
        ctx.files.resources,
        ctx.attr.javacopts,
        ctx.attr.neverlink,
        ctx.files.proguard_specs,
        ctx.attr.add_exports,
        ctx.attr.add_opens,
    ).values()

java_library = rule(
  implementation = _impl,
  attrs = JAVA_LIBRARY_ATTRS,
  provides = [JavaInfo],
  outputs = {
      "classjar": "lib%{name}.jar",
      "sourcejar": "lib%{name}-src.jar",
  },
  fragments = ["java", "cpp"],
  toolchains = ["@bazel_tools//tools/jdk:toolchain_type"],
)
EOF
  cat >java/BUILD <<EOF
load(":java_library.bzl", "java_library")
package(default_visibility=['//visibility:public'])
java_library(name = 'hello_library',
             srcs = ['HelloLibrary.java']);
EOF
  cat >java/HelloLibrary.java <<EOF
package hello_library;
public class HelloLibrary {
  public static void funcHelloLibrary() {
    System.out.print("Hello, Library!;");
  }
}
EOF

  bazel build //java:hello_library &> $TEST_log || fail "build failed"
}

run_suite "rules_java tests"
