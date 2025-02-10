#!/bin/bash
#
# Copyright 2024 The Bazel Authors. All rights reserved.
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
# Tests the behaviour of --incompatible_autoload_externally flag.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

#### SETUP #############################################################

set -e

#### TESTS #############################################################

function mock_rules_android() {
  rules_android_workspace="${TEST_TMPDIR}/rules_android_workspace"
  mkdir -p "${rules_android_workspace}/rules"
  touch "${rules_android_workspace}/rules/BUILD"
  touch "${rules_android_workspace}/WORKSPACE"
  cat > "${rules_android_workspace}/MODULE.bazel" << EOF
module(name = "rules_android")
EOF
  cat > "${rules_android_workspace}/rules/rules.bzl" << EOF
def _impl(ctx):
  pass

aar_import = rule(
  implementation = _impl,
  attrs = {
    "aar": attr.label(allow_files = True),
    "deps": attr.label_list(),
  }
)
EOF

  cat >> MODULE.bazel << EOF
bazel_dep(
    name = "rules_android",
)
local_path_override(
    module_name = "rules_android",
    path = "${rules_android_workspace}",
)
EOF

  cat > WORKSPACE << EOF
workspace(name = "test")
local_repository(
    name = "rules_android",
    path = "${rules_android_workspace}",
)
EOF
}


function mock_rules_java() {
  rules_java_workspace="${TEST_TMPDIR}/rules_java_workspace"
  mkdir -p "${rules_java_workspace}/java"
  touch "${rules_java_workspace}/java/BUILD"
  cat > "${rules_java_workspace}/java/rules_java_deps.bzl" <<EOF
def rules_java_dependencies():
  pass
EOF
  cat > "${rules_java_workspace}/java/repositories.bzl" <<EOF
def rules_java_toolchains():
  pass
EOF
  touch "${rules_java_workspace}/WORKSPACE"
  cat > "${rules_java_workspace}/MODULE.bazel" << EOF
module(name = "rules_java")
EOF
  cat > MODULE.bazel << EOF
bazel_dep(
    name = "rules_java",
)
local_path_override(
    module_name = "rules_java",
    path = "${rules_java_workspace}",
)
EOF

  cat > WORKSPACE << EOF
workspace(name = "test")
local_repository(
    name = "rules_java",
    path = "${rules_java_workspace}",
)
EOF
}

function mock_apple_support() {
  apple_support_workspace="${TEST_TMPDIR}/apple_support_workspace"
  mkdir -p "${apple_support_workspace}/xcode"
  touch "${apple_support_workspace}/xcode/BUILD"
  touch "${apple_support_workspace}/WORKSPACE"
  cat > "${apple_support_workspace}/MODULE.bazel" << EOF
module(name = "apple_support", repo_name = "build_bazel_apple_support")
EOF
  cat > "${apple_support_workspace}/xcode/xcode_version.bzl" << EOF
def _impl(ctx):
  pass

xcode_version = rule(
  implementation = _impl,
  attrs = {
    "version": attr.string(),
  }
)
EOF

  cat >> MODULE.bazel << EOF
bazel_dep(
    name = "apple_support",
    repo_name = "build_bazel_apple_support",
)
local_path_override(
    module_name = "apple_support",
    path = "${apple_support_workspace}",
)
EOF

  cat > WORKSPACE << EOF
workspace(name = "test")
local_repository(
    name = "build_bazel_apple_support",
    path = "${apple_support_workspace}",
)
EOF
}

function test_missing_necessary_repo_fails() {
  # Intentionally not adding apple_support to MODULE.bazel (and it's not in MODULE.tools)
  cat > WORKSPACE << EOF
workspace(name = "test")
# TODO: protobuf's WORKSPACE macro has an unnecessary dependency on build_bazel_apple_support.
# __SKIP_WORKSPACE_SUFFIX__
EOF
  cat > BUILD << EOF
xcode_version(
    name = 'xcode_version',
    version = "5.1.2",
)
EOF
  bazel build --incompatible_autoload_externally=xcode_version :xcode_version >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "Couldn't auto load 'xcode_version' from '@build_bazel_apple_support//xcode:xcode_version.bzl'. Ensure that you have a 'bazel_dep(name = \"apple_support\", ...)' in your MODULE.bazel file or add an explicit load statement to your BUILD file."
}

function test_missing_unnecessary_repo_doesnt_fail() {
  # Intentionally not adding rules_android to MODULE.bazel (and it's not in MODULE.tools)
  cat > WORKSPACE << EOF
workspace(name = "test")
EOF
  cat > BUILD << EOF
filegroup(
    name = 'filegroup',
    srcs = [],
)
EOF
  bazel build --incompatible_autoload_externally=+@rules_android :filegroup >&$TEST_log 2>&1 || fail "build failed"
}

function test_removed_rule_loaded() {
  setup_module_dot_bazel
  mock_rules_android

  cat > BUILD << EOF
aar_import(
    name = 'aar',
    aar = 'aar.file',
    deps = [],
)
EOF

  bazel build --incompatible_autoload_externally=aar_import :aar >&$TEST_log 2>&1 || fail "build failed"
}

function test_removed_rule_loaded_from_legacy_repo_name() {
  setup_module_dot_bazel
  mock_apple_support

  cat > BUILD << EOF
xcode_version(
    name = 'xcode_version',
    version = "5.1.2",
)
EOF
  bazel build --incompatible_autoload_externally=xcode_version :xcode_version >&$TEST_log 2>&1 || fail "build failed"
}

function test_removed_rule_loaded_from_bzl() {
  setup_module_dot_bazel
  mock_rules_android

  cat > macro.bzl << EOF
def macro():
    native.aar_import(
        name = 'aar',
        aar = 'aar.file',
        deps = [],
    )
EOF

  cat > BUILD << EOF
load(":macro.bzl", "macro")
macro()
EOF

  bazel build --incompatible_autoload_externally=aar_import :aar >&$TEST_log 2>&1 || fail "build failed"

}

function test_removed_symbol_loaded() {
  cat > symbol.bzl << EOF
def symbol():
  a = ProtoInfo
EOF

  cat > BUILD << EOF
load(":symbol.bzl", "symbol")
symbol()
EOF

  bazel build --incompatible_autoload_externally=ProtoInfo,proto_common_do_not_use,java_binary :all >&$TEST_log 2>&1 || fail "build failed"
}

function test_proto_common_do_not_use() {
  cat > symbol.bzl << EOF
def symbol():
  print("\n".join(dir(proto_common_do_not_use)))
EOF

  cat > BUILD << EOF
load(":symbol.bzl", "symbol")
symbol()
EOF

  bazel build --incompatible_autoload_externally=proto_common_do_not_use :all >&$TEST_log 2>&1 || fail "build failed"
  expect_log INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION
  expect_not_log "compile"
}

function test_existing_rule_is_redirected() {
  cat > BUILD << EOF
sh_library(
    name = 'sh_library',
)
EOF
  bazel query --incompatible_autoload_externally=+sh_library ':sh_library' --output=build >&$TEST_log 2>&1 || fail "build failed"
  expect_log "rules_shell.\\?/shell/private/sh_library.bzl"
}

function test_existing_rule_is_redirected_in_bzl() {
  cat > macro.bzl << EOF
def macro():
    native.sh_library(
        name = 'sh_library',
    )
EOF

  cat > BUILD << EOF
load(":macro.bzl", "macro")
macro()
EOF

  bazel query --incompatible_autoload_externally=+sh_library ':sh_library' --output=build >&$TEST_log 2>&1 || fail "build failed"
  expect_log "rules_shell.\\?/shell/private/sh_library.bzl"
}

function test_removed_rule_not_loaded() {
  cat > BUILD << EOF
aar_import(
    name = 'aar',
    aar = 'aar.file',
    deps = [],
    visibility = ['//visibility:public'],
)
EOF

  bazel build --incompatible_autoload_externally= :aar >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "name 'aar_import' is not defined"
}

function test_removed_rule_not_loaded_in_bzl() {
  cat > macro.bzl << EOF
def macro():
    native.aar_import(
      name = 'aar',
      aar = 'aar.file',
      deps = [],
      visibility = ['//visibility:public'],
    )
EOF

  cat > BUILD << EOF
load(":macro.bzl", "macro")
macro()
EOF

  bazel build --incompatible_autoload_externally= :aar >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "no native function or rule 'aar_import'"
}

function test_removed_symbol_not_loaded_in_bzl() {
  setup_module_dot_bazel

  cat > symbol.bzl << EOF
def symbol():
    a = ProtoInfo
EOF

  cat > BUILD << EOF
load(":symbol.bzl", "symbol")
symbol()
EOF

  bazel build --incompatible_autoload_externally= :all >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "name 'ProtoInfo' is not defined"
}


function test_removing_existing_rule() {
  cat > BUILD << EOF
android_binary(
    name = "bin",
    srcs = [
        "MainActivity.java",
        "Jni.java",
    ],
    manifest = "AndroidManifest.xml",
    deps = [
        ":lib",
        ":jni"
    ],
)
EOF

  bazel build --incompatible_autoload_externally=-android_binary :bin >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "name 'android_binary' is not defined"
}

function test_removing_existing_rule_in_bzl() {
  cat > macro.bzl << EOF
def macro():
    native.android_binary(
        name = "bin",
        srcs = [
            "MainActivity.java",
            "Jni.java",
        ],
        manifest = "AndroidManifest.xml",
        deps = [
            ":lib",
            ":jni"
        ],
    )
EOF

  cat > BUILD << EOF
load(":macro.bzl", "macro")
macro()
EOF

  bazel build --incompatible_autoload_externally=-android_binary :bin >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "no native function or rule 'android_binary'"
}

function test_removing_symbol_incompletely() {
  cat > symbol.bzl << EOF
def symbol():
   a = CcInfo
EOF

  cat > BUILD << EOF
load(":symbol.bzl", "symbol")
symbol()
EOF

  bazel build --incompatible_autoload_externally=-CcInfo :all >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "Symbol 'CcInfo' can't be removed, because it's still used by:"
}

function test_removing_existing_symbol() {
  cat > symbol.bzl << EOF
def symbol():
   a = DebugPackageInfo
EOF

  cat > BUILD << EOF
load(":symbol.bzl", "symbol")
symbol()
EOF

  bazel build --incompatible_autoload_externally=-DebugPackageInfo,-cc_binary,-cc_test :all >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "name 'DebugPackageInfo' is not defined"
}

function test_removing_symbol_typo() {
  cat > bzl_file.bzl << EOF
def bzl_file():
    pass
EOF

  cat > BUILD << EOF
load(":bzl_file.bzl", "bzl_file")
EOF

  bazel build --incompatible_autoload_externally=-ProtozzzInfo :all >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "Undefined symbol in --incompatible_autoload_externally"
}

function test_removing_rule_typo() {
  touch BUILD

  bazel build --incompatible_autoload_externally=-androidzzz_binary :all >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "Undefined symbol in --incompatible_autoload_externally"
}

function test_redirecting_rule_with_bzl_typo() {
  # Bzl file is evaluated first, so this should cover bzl file support
  cat > bzl_file.bzl << EOF
def bzl_file():
    pass
EOF

  cat > BUILD << EOF
load(":bzl_file.bzl", "bzl_file")
EOF

  bazel build --incompatible_autoload_externally=pyzzz_library :all >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "Undefined symbol in --incompatible_autoload_externally"
}

function test_redirecting_rule_typo() {
  cat > BUILD << EOF
EOF


  bazel build --incompatible_autoload_externally=pyzzz_library :all >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "Undefined symbol in --incompatible_autoload_externally"
}

function test_redirecting_symbols_typo() {
  # Bzl file is evaluated first, so this should cover bzl file support
  cat > bzl_file.bzl << EOF
def bzl_file():
    pass
EOF

  cat > BUILD << EOF
load(":bzl_file.bzl", "bzl_file")
EOF

  bazel build --incompatible_autoload_externally=ProotoInfo :all >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
    expect_log "Undefined symbol in --incompatible_autoload_externally"
}

function test_bad_flag_value() {
  cat > BUILD << EOF
py_library(
    name = 'py_library',
)
EOF
  bazel query --incompatible_autoload_externally=py_library,-py_library ':py_library' --output=build >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "Duplicated symbol 'py_library' in --incompatible_autoload_externally"
}

function test_missing_symbol_error() {
  mock_rules_android
  rules_android_workspace="${TEST_TMPDIR}/rules_android_workspace"
  # emptying the file simulates a missing symbol
  cat > "${rules_android_workspace}/rules/rules.bzl" << EOF
EOF

  cat > BUILD << EOF
aar_import(
    name = 'aar',
    aar = 'aar.file',
    deps = [],
)
EOF
  bazel build --incompatible_autoload_externally=aar_import :aar >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "Failed to apply symbols loaded externally: The toplevel symbol 'aar_import' set by --incompatible_load_symbols_externally couldn't be loaded. 'aar_import' not found in auto loaded '@rules_android//rules:rules.bzl'."
}

function test_missing_bzlfile_error() {
  mock_rules_android
  rules_android_workspace="${TEST_TMPDIR}/rules_android_workspace"
  rm "${rules_android_workspace}/rules/rules.bzl"

  cat > BUILD << EOF
aar_import(
    name = 'aar',
    aar = 'aar.file',
    deps = [],
)
EOF
  bazel build --incompatible_autoload_externally=aar_import :aar >&$TEST_log 2>&1 && fail "build unexpectedly succeeded"
  expect_log "Failed to autoload external symbols: cannot load '@@\?rules_android+\?//rules:rules.bzl': no such file"
}


function test_whole_repo_flag() {
  cat > BUILD << EOF
sh_library(
    name = 'sh_library',
)
EOF
  bazel query --incompatible_autoload_externally=+@rules_shell ':sh_library' --output=build >&$TEST_log 2>&1 || fail "build failed"
}

function test_legacy_globals() {
  mock_rules_java

  rules_java_workspace="${TEST_TMPDIR}/rules_java_workspace"

  mkdir -p "${rules_java_workspace}/java/common"
  touch "${rules_java_workspace}/java/common/BUILD"
  cat > "${rules_java_workspace}/java/common/proguard_spec_info.bzl" << EOF
def _init(specs):
  return {"specs": specs}

def _proguard_spec_info():
  if hasattr(native, "legacy_globals"):
    if hasattr(native.legacy_globals, "ProguardSpecProvider"):
      print("Native provider")
      return native.legacy_globals.ProguardSpecProvider
  print("Starlark provider")
  return provider(fields = ["specs"], init = _init)[0]

ProguardSpecInfo = _proguard_spec_info()
EOF

  cat > BUILD << EOF
load("@rules_java//java/common:proguard_spec_info.bzl", "ProguardSpecInfo")
EOF

  bazel build --incompatible_autoload_externally=+ProguardSpecProvider :all >&$TEST_log 2>&1 || fail "build unexpectedly failed"
  expect_log "Native provider"


  bazel build --incompatible_autoload_externally=ProguardSpecProvider,-java_lite_proto_library,-java_import :all >&$TEST_log 2>&1 || fail "build unexpectedly failed"
  expect_log "Starlark provider"
}

run_suite "Tests for incompatible_autoload_externally flag"
