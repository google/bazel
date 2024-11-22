#!/bin/bash -eu
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
# Tests the behavior of C++ rules.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function test_extra_action_for_compile() {
  mkdir -p ea
  cat > ea/BUILD <<EOF
action_listener(
    name = "al",
    extra_actions = [":ea"],
    mnemonics = ["CppCompile"],
    visibility = ["//visibility:public"],
)

extra_action(
    name = "ea",
    cmd = "if ! [[ -r ea/cc.cc ]]; then echo 'source file not in inputs'; exit 1; fi",
)

cc_library(
    name = "cc",
    srcs = ["cc.cc"],
)
EOF

  echo 'void cc() {}' > ea/cc.cc

  bazel build --experimental_action_listener=//ea:al //ea:cc || fail "expected success"
}

function test_cc_library_include_prefix_external_repository() {
  r="$TEST_TMPDIR/r"
  mkdir -p "$TEST_TMPDIR/r/foo/v1"
  touch "$TEST_TMPDIR/r/REPO.bazel"
  echo "#define FOO 42" > "$TEST_TMPDIR/r/foo/v1/foo.h"
  cat > "$TEST_TMPDIR/r/foo/BUILD" <<EOF
cc_library(
  name = "foo",
  hdrs = ["v1/foo.h"],
  include_prefix = "foolib",
  strip_include_prefix = "v1",
  visibility = ["//visibility:public"],
)
EOF
  cat >> MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "foo",
  path = "$TEST_TMPDIR/r",
)
EOF

  cat > BUILD <<EOF
cc_binary(
  name = "ok",
  srcs = ["ok.cc"],
  deps = ["@foo//foo"],
)

cc_binary(
  name = "still_ok",
  srcs = ["still_ok.cc"],
  deps = ["@foo//foo"],
)
EOF

  cat > ok.cc <<EOF
#include <stdio.h>
#include "foolib/foo.h"
int main() {
  printf("FOO is %d\n", FOO);
}
EOF

  cat > still_ok.cc <<EOF
#include <stdio.h>
#include "foo/v1/foo.h"
int main() {
  printf("FOO is %d\n", FOO);
}
EOF

  bazel build :ok || fail "Should have found include at synthetic path"
  bazel build :still_ok \
    || fail "Should have found include at repository-relative path"
}


function test_include_validation_sandbox_disabled() {
  local workspace="${FUNCNAME[0]}"
  mkdir -p "${workspace}"/lib

  setup_module_dot_bazel "${workspace}/MODULE.bazel"
  cat >> "${workspace}/BUILD" << EOF
cc_library(
    name = "foo",
    srcs = ["lib/foo.cc"],
    hdrs = ["lib/foo.h"],
    strip_include_prefix = "lib",
)
EOF
  cat >> "${workspace}/lib/foo.cc" << EOF
#include "foo.h"
EOF

  touch "${workspace}/lib/foo.h"

  cd "${workspace}"
  bazel build --spawn_strategy=standalone //:foo  &>"$TEST_log" \
    || fail "Build failed but should have succeeded"
}

function test_tree_artifact_headers_are_invalidated() {
  mkdir -p "ta_headers"
  cat > "ta_headers/BUILD" <<EOF
load(":mygen.bzl", "mygen")

sh_binary(
  name = "mygen_sh",
  srcs = ["mygen.sh"],
  visibility = ["//visibility:public"],
)

mygen(
    name="mylib_generated",
    srcs=[":mydef.txt"],
)

cc_library(
    name = "mylib",
    srcs = [":mylib_generated"],
    hdrs = [":mylib_generated"],
)

cc_binary(
    name = "myexec",
    srcs = [],
    deps = [":mylib"],
)
EOF
  cat > "ta_headers/mygen.sh" <<'EOF'
#!/bin/bash

set -euo pipefail

src_files=$1
hdr_files=$2

fc_name=$(cat ta_headers/mydef.txt)

mkdir -p ${src_files}
mkdir -p ${hdr_files}

cat > ${src_files}/main.c <<EOT
#include "ta_headers/files.h/another.h"
int main(void) {
    return MYFC();
}
EOT

cat > ${src_files}/another.c <<EOT
#include "ta_headers/files.h/another.h"
int ${fc_name}(void) {
    return 0;
}
EOT

cat > ${hdr_files}/another.h <<EOT
#define MYFC ${fc_name}
int ${fc_name}(void);
EOT
EOF
  chmod +x ta_headers/mygen.sh
  cat > "ta_headers/mygen.bzl" <<EOF
def _mygen_impl(ctx):
  args = ctx.actions.args()
  treeC = ctx.actions.declare_directory("files.c")
  treeH = ctx.actions.declare_directory("files.h")
  args.add(treeC.path)
  args.add(treeH.path)
  ctx.actions.run(
      inputs = ctx.files.srcs,
      outputs = [treeC, treeH],
      arguments = [args],
      executable = ctx.executable._mygen,
  )
  return [DefaultInfo(files=depset([treeC, treeH]))]

mygen = rule(
  implementation=_mygen_impl,
  attrs={
    "srcs": attr.label_list(allow_files=True),
    "_mygen": attr.label(
      cfg="exec",
      executable=True,
      allow_files=True,
      default=":mygen_sh",
    ),
  },
)
EOF

  # So we have another.h defining a macro that is used by both main.c and
  # another.c. :main depends on :another, and gets the header through the
  # tree artifact. First build is fine.
  echo "fc1" > "ta_headers/mydef.txt"
  bazel build //ta_headers:myexec || fail \
    "First build failed, something is wrong with the test."

  # Now we change the content of another.h to define a different macro.
  # This test verifies that not only another.c is recompiled, but also
  # main.c. This is a regression test for
  # https://github.com/bazelbuild/bazel/issues/5785.
  echo "fc2" > "ta_headers/mydef.txt"
  bazel build //ta_headers:myexec || fail \
    "Second build failed, tree artifact was not invalidated."
}

function test_save_feature_state() {
  mkdir -p ea
  cat > ea/BUILD <<EOF
cc_library(
    name = "cc",
    srcs = ["cc.cc", "cc1.cc"],
    features = ["test_feature"],
)
EOF

  echo 'void cc() {}' > ea/cc.cc
  echo 'void cc1() {}' > ea/cc1.cc

  bazel build --experimental_builtins_injection_override=+cc_library --experimental_save_feature_state //ea:cc || fail "expected success"
  ls bazel-bin/ea/cc_feature_state.txt || fail "cc_feature_state.txt not created"
  # This assumes "grep" is supported in any environment bazel is used.
  grep "test_feature" bazel-bin/ea/cc_feature_state.txt || fail "test_feature should have been found in feature_state."
}

# TODO: test include dirs and defines
function setup_cc_starlark_api_test() {
  local pkg="$1"

  touch "$pkg"/MODULE.bazel

  mkdir "$pkg"/include_dir
  touch "$pkg"/include_dir/include.h
  mkdir "$pkg"/system_include_dir
  touch "$pkg"/system_include_dir/system_include.h
  mkdir "$pkg"/quote_include_dir
  touch "$pkg"/quote_include_dir/quote_include.h



  cat > "$pkg"/BUILD << EOF
load("//${pkg}:cc_api_rules.bzl", "cc_lib", "cc_bin")

cc_lib(
    name = "a",
    srcs = [
        "a1.cc",
        "a2.cc",
    ],
    private_hdrs = [
      "a2.h",
      "include_dir/include.h",
      "system_include_dir/system_include.h",
      "quote_include_dir/quote_include.h"
    ],
    user_compile_flags = ["-DA_DEFINITION_LOCAL"],
    public_hdrs = ["a.h"],
    includes = ["$pkg/include_dir"],
    system_includes = ["$pkg/system_include_dir"],
    quote_includes = ["$pkg/quote_include_dir"],
    defines = ["A_DEFINITION"],
    deps = [
        ":b",
        ":d",
    ],
)

cc_lib(
    name = "b",
    srcs = [
        "b.cc",
    ],
    public_hdrs = ["b.h"],
    deps = [":c"],
)

cc_lib(
    name = "c",
    srcs = [
        "c.cc",
    ],
    public_hdrs = ["c.h"],
)

cc_lib(
    name = "d",
    srcs = ["d.cc"],
    public_hdrs = ["d.h"],
)

cc_bin(
    name = "e",
    srcs = ["e.cc"],
    data = [":f"],
    linkstatic = 1,
    user_link_flags = [
        "-ldl",
        "-lm",
        "-Wl,-rpath,bazel-bin/${pkg}",
    ],
    deps = [
        ":a",
    ],
)

cc_bin(
    name = "f",
    srcs = ["f.cc"],
    linkshared = 1,
    deps = [
        ":a",
    ],
)
EOF

  cat > $pkg/a1.cc << EOF
#include <system_include.h>
#include "include.h"

#include "quote_include.h"
#include "a.h"
#include "a2.h"

#ifdef A_DEFINITION_LOCAL
#include "b.h"
#include "d.h"
#endif

using namespace std;

string alongernamethanusual() { return "a1" + a2() + b() + d(); }
EOF

  cat > $pkg/a2.cc << EOF
#include <string>
using namespace std;

string a2() { return "a2"; }
EOF

  cat > $pkg/a.h << EOF
#ifndef HEADER_A
#define HEADER_A
#include <string>
using namespace std;
string alongernamethanusual();
#endif
EOF

  cat > $pkg/a2.h << EOF
#ifndef HEADER_A2
#define HEADER_A2
#include <string>
using namespace std;
string a2();
#endif
EOF

  cat > $pkg/b.cc << EOF
#include "b.h"
#include <string>
#include "c.h"
using namespace std;

string b() { return "b" + c(); }
EOF

  cat > $pkg/b.h << EOF
#ifndef HEADER_B
#define HEADER_B
#include <string>
using namespace std;
string b();
#endif
EOF

  cat > $pkg/c.cc << EOF
#include "c.h"
#include <algorithm>
#include <string>

using namespace std;

string c() { return "c"; }
EOF

  cat > $pkg/c.h << EOF
#ifndef HEADER_C
#define HEADER_C
#include <string>
using namespace std;
string c();
#endif
EOF

  cat > $pkg/d.cc << EOF
#include "d.h"
#include <string>
using namespace std;

string d() { return "d"; }
EOF

  cat > $pkg/d.h << EOF
#ifndef HEADER_D
#define HEADER_D
#include <string>
using namespace std;
string d();
#endif
EOF

  cat > $pkg/e.cc << EOF
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <iostream>

#ifdef A_DEFINITION
#include "a.h"
#endif

#ifdef A_DEFINITION_LOCAL
#include "thisdoesntexist.h"
#endif

using namespace std;

#ifdef __APPLE__
#define DYNAMIC_LIB_EXT "dylib"
#else
#define DYNAMIC_LIB_EXT "so"
#endif

int main() {
  void* handle = dlopen("libf." DYNAMIC_LIB_EXT, RTLD_LAZY);

  typedef string (*f_t)();

  f_t f = (f_t)dlsym(handle, "f");
  cout << alongernamethanusual() + f() << endl;
  return 0;
}

EOF

  cat > $pkg/f.cc << EOF
#include <algorithm>
#include <string>
#include "a.h"

using namespace std;

extern "C" string f() {
  string str = alongernamethanusual();
  reverse(str.begin(), str.end());
  return str;
}
EOF

  cat > $pkg/script.lds << EOF
VERS_42.0 {
  global:
    f;
};
EOF

  cp "$CURRENT_DIR"/cc_api_rules.bzl "$pkg"/cc_api_rules.bzl

}

function test_cc_starlark_api_default_values() {
  local pkg="${FUNCNAME[0]}"
  mkdir -p "$pkg"

  setup_cc_starlark_api_test "${FUNCNAME[0]}"

  bazel build --experimental_cc_skylark_api_enabled_packages=, --verbose_failures \
    //"$pkg":e  &>"$TEST_log" || fail "Build failed"

  nm -u bazel-bin/"$pkg"/e  | grep alongernamethanusual && \
    fail "alongernamethanusual is not defined"

  bazel-bin/"$pkg"/e | grep a1a2bcddcb2a1a || fail "output is incorrect"
}


function test_cc_starlark_api_link_static_false() {
  [ "$PLATFORM" != "darwin" ] || return 0

  local pkg="${FUNCNAME[0]}"
  mkdir -p "$pkg"

  setup_cc_starlark_api_test "${FUNCNAME[0]}"

  cat >> "$pkg"/BUILD << EOF
cc_bin(
    name = "g",
    srcs = ["e.cc"],
    data = [":f"],
    linkstatic = 0,
    user_link_flags = [
        "-ldl",
        "-lm",
        "-Wl,-rpath,bazel-bin/${pkg}",
    ],
    deps = [
        ":a",
    ],
)
EOF

  bazel build --experimental_cc_skylark_api_enabled_packages=, --verbose_failures \
    //"$pkg":g  &>"$TEST_log" || fail "Build failed"

  nm -u bazel-bin/"$pkg"/g  | grep alongernamethanusual || fail "alongernamethanusual is defined"

  bazel-bin/"$pkg"/g | grep a1a2bcddcb2a1a || fail "output is incorrect"
}

function test_cc_starlark_api_additional_inputs() {
  # This uses --version-script which isn't available on Mac linker.
  [ "$PLATFORM" != "darwin" ] || return 0

  local pkg="${FUNCNAME[0]}"
  mkdir -p "$pkg"

  setup_cc_starlark_api_test "${FUNCNAME[0]}"

  cat >> "$pkg"/BUILD << EOF
cc_bin(
    name = "g",
    srcs = ["f.cc"],
    linkshared = 1,
    additional_linker_inputs = ["script.lds"],
    user_link_flags = [
        "-ldl",
        "-lm",
        "-Wl,-rpath,bazel-bin/${pkg}",
        "-Wl,--version-script=\$(location script.lds)",
    ],
    deps = [
        ":a",
    ],
)
EOF

  bazel build --experimental_cc_skylark_api_enabled_packages=, --verbose_failures \
    //"$pkg":g  &>"$TEST_log" || fail "Build failed"

  nm -D bazel-bin/"$pkg"/libg.so  | grep VERS_42.0 || fail "VERS_42.0 not in binary"
}

function test_aspect_accessing_args_link_action_with_tree_artifact() {
  # This test assumes the presence of "nodeps" dynamic libraries, which do not
  # function on Apple platforms.
  [ "$PLATFORM" != "darwin" ] || return 0

  local package="${FUNCNAME[0]}"
  mkdir -p "${package}"
  cat > "${package}/makes_tree_artifacts.sh" <<EOF
#!/bin/bash
my_dir=\$1

echo "int a() { return 0; }" > \$my_dir/a.cc
echo "int b() { return 0; }" > \$my_dir/b.cc
echo "int c() { return 0; }" > \$my_dir/c.cc
EOF
  chmod 755 "${package}/makes_tree_artifacts.sh"

  cat > "${package}/write.sh" <<EOF
#!/bin/bash
output_file=\$1
shift;

echo "\$@" > \$output_file
EOF
  chmod 755 "${package}/write.sh"

  cat > "${package}/lib.bzl" <<EOF
def _tree_art_impl(ctx):
    my_dir = ctx.actions.declare_directory('dir.cc')
    ctx.actions.run(
        executable = ctx.executable._makes_tree,
        outputs = [my_dir],
        arguments = [my_dir.path])

    return [DefaultInfo(files=depset([my_dir]))]

tree_art_rule = rule(implementation = _tree_art_impl,
    attrs = {
        "_makes_tree" : attr.label(allow_single_file = True,
            cfg = "exec",
            executable = True,
            default = "//${package}:makes_tree_artifacts.sh"),
        "_write" : attr.label(allow_single_file = True,
            cfg = "exec",
            executable = True,
            default = "//${package}:write.sh")})

def _actions_test_impl(target, ctx):
    action = target.actions[0]
    if action.mnemonic != "CppArchive":
      fail("Expected the first action to be CppArchive.")
    aspect_out = ctx.actions.declare_file('aspect_out')
    ctx.actions.write(aspect_out, action.args[1])
    return [OutputGroupInfo(out=[aspect_out])]

actions_test_aspect = aspect(implementation = _actions_test_impl)
EOF

  cat > "${package}/BUILD" <<EOF
load(":lib.bzl", "tree_art_rule")

tree_art_rule(name = "tree")

cc_library(
  name = "x",
  srcs = [":tree"],
)
EOF

  bazel build "${package}:x" \
      --aspects="//${package}:lib.bzl%actions_test_aspect" \
      --output_groups=out

  cat "bazel-bin/${package}/aspect_out" | grep "\(ar\|libtool\)" \
      || fail "args didn't contain the tool path"

  cat "bazel-bin/${package}/aspect_out" | grep "/a.*o" \
      || fail "args didn't contain tree artifact paths"
  cat "bazel-bin/${package}/aspect_out" | grep "/b.*o" \
      || fail "args didn't contain tree artifact paths"
}

function test_argv_in_compile_action() {
  local package="${FUNCNAME[0]}"
  mkdir -p "${package}"

  cat > "${package}/lib.bzl" <<EOF
def _actions_test_impl(target, ctx):
    action = [a for a in target.actions if a.mnemonic == "CppCompile"][0]
    aspect_out = ctx.actions.declare_file('aspect_out')
    ctx.actions.run_shell(inputs = action.inputs,
                          outputs = [aspect_out],
                          command = "echo \$@ > " + aspect_out.path,
                          arguments = action.argv)
    return [OutputGroupInfo(out=[aspect_out])]

actions_test_aspect = aspect(implementation = _actions_test_impl)
EOF

  touch "${package}/x.cc"
  cat > "${package}/BUILD" <<EOF
cc_library(
  name = "x",
  srcs = ["x.cc"],
)
EOF

  bazel build "${package}:x" \
      --aspects="//${package}:lib.bzl%actions_test_aspect" \
      --output_groups=out

  cat "bazel-bin/${package}/aspect_out" | \
      grep "\(gcc\|clang\|clanc-cl.exe\|cl.exe\|cc_wrapper.sh\)" \
      || fail "args didn't contain the tool path"

  cat "bazel-bin/${package}/aspect_out" | grep "a.*o .*b.*o .*c.*o" \
      || fail "args didn't contain tree artifact paths"
}

function test_directory_arg_compile_action() {
  # This test assumes the presence of "nodeps" dynamic libraries, which do not
  # function on Apple platforms.
  [ "$PLATFORM" != "darwin" ] || return 0

  local package="${FUNCNAME[0]}"
  mkdir -p "${package}"

  cat > "${package}/lib.bzl" <<EOF
def _actions_test_impl(target, ctx):
    action = target.actions[0]
    if action.mnemonic != "CppCompile":
      fail("Expected the first action to be CppCompile.")
    aspect_out = ctx.actions.declare_file('aspect_out')
    ctx.actions.run_shell(inputs = action.inputs,
                          outputs = [aspect_out],
                          command = "echo \$@ > " + aspect_out.path,
                          arguments = action.args)
    return [OutputGroupInfo(out=[aspect_out])]

actions_test_aspect = aspect(implementation = _actions_test_impl)
EOF

  touch "${package}/x.cc"
  cat > "${package}/BUILD" <<EOF
cc_library(
  name = "x",
  srcs = ["x.cc"],
)
EOF

  bazel build "${package}:x" \
      --aspects="//${package}:lib.bzl%actions_test_aspect" \
      --output_groups=out

  cat "bazel-bin/${package}/aspect_out" | \
      grep "\(gcc\|clang\|clanc-cl.exe\|cl.exe\)" \
      || fail "args didn't contain the tool path"

  cat "bazel-bin/${package}/aspect_out" | grep "a.*o .*b.*o .*c.*o" \
      || fail "args didn't contain tree artifact paths"
}

function test_reconstructing_cpp_actions() {
  if is_darwin; then
    # Darwin toolchain uses env variables and those are not properly exported
    # to Starlark.
    # TODO(#10376): Remove once env vars on C++ actions are exported.
    return 0
  fi

  local package="${FUNCNAME[0]}"
  mkdir -p "${package}"

  cat > "${package}/lib.bzl" <<EOF
def _actions_test_impl(target, ctx):
    compile_action = None
    archive_action = None
    link_action = None

    for action in target.actions:
      if action.mnemonic == "CppCompile":
        compile_action = action
      if action.mnemonic == "CppArchive":
        archive_action = action
      if action.mnemonic == "CppLink":
        link_action = action

    if not compile_action or not archive_action or not link_action:
      fail("Couldn't find compile, archive, or link action.")

    cc_info = target[CcInfo]
    compile_action_outputs = compile_action.outputs.to_list()

    compile_args = ctx.actions.declare_file("compile_args")
    ctx.actions.run_shell(
        outputs = [compile_args],
        command = "echo \$@ > " + compile_args.path,
        arguments = compile_action.args,
    )

    inputs = depset(
        direct = [compile_args],
        transitive = [
            compile_action.inputs,
            # Because C++ compilation actions prune their headers in the
            # execution phase, and this code runs in analysis phase,
            # action.inputs is not processed yet. It doesn't contain
            # headers/module files yet. Let's add all unpruned headers
            # explicitly.
            cc_info.compilation_context.headers,
        ],
    )

    compile_out = ctx.actions.declare_file("compile_out.o")
    ctx.actions.run_shell(
        inputs = inputs,
        mnemonic = "RecreatedCppCompile",
        outputs = [compile_out],
        env = compile_action.env,
        command = "\$(cat %s | sed 's|%s|%s|g' | sed 's|%s|%s|g')" % (
            compile_args.path,
            # We need to replace the original output path with something else
            compile_action_outputs[0].path,
            compile_out.path,
            # We need to replace the original .d file output path with something
            # else
            compile_action_outputs[0].path.replace(".o", ".d"),
            compile_out.path + ".d",
        ),
    )

    archive_out = ctx.actions.declare_file("archive_out.a")
    ctx.actions.run_shell(
        inputs = archive_action.inputs,
        mnemonic = "RecreatedCppArchive",
        outputs = [archive_out],
        env = archive_action.env,
        command = "\$@ && cp %s %s" % (
            archive_action.outputs.to_list()[0].path,
            archive_out.path,
        ),
        arguments = archive_action.args,
    )

    link_out = ctx.actions.declare_file("link_out.so")
    ctx.actions.run_shell(
        inputs = link_action.inputs,
        mnemonic = "RecreatedCppLink",
        outputs = [link_out],
        env = link_action.env,
        command = "\$@ && cp %s %s" % (
            link_action.outputs.to_list()[0].path,
            link_out.path,
        ),
        arguments = link_action.args,
    )

    return [OutputGroupInfo(out = [
        compile_args,
        compile_out,
        archive_out,
        link_out,
    ])]

actions_test_aspect = aspect(implementation = _actions_test_impl)
EOF

  echo "inline int x() { return 42; }" > "${package}/x.h"
  cat > "${package}/a.cc" <<EOF
#include "${package}/x.h"

int a() { return x(); }
EOF
  cat > "${package}/BUILD" <<EOF
cc_library(
  name = "x",
  hdrs  = ["x.h"],
)

cc_library(
  name = "a",
  srcs = ["a.cc"],
  deps = [":x"],
)
EOF

  # Test that actions are reconstructible under default configuration
  bazel build "${package}:a" \
      --aspects="//${package}:lib.bzl%actions_test_aspect" \
      --output_groups=out || \
      fail "bazel build should've passed"

  # Test that compile actions are reconstructible when using param files
  bazel build "${package}:a" \
      --features=compiler_param_file \
      --aspects="//${package}:lib.bzl%actions_test_aspect" \
      --output_groups=out || \
      fail "bazel build should've passed with --features=compiler_param_file"
}

function test_disable_cc_toolchain_detection() {
  add_rules_cc "MODULE.bazel"
  cat >> MODULE.bazel <<'EOF'
cc_configure = use_extension("@rules_cc//cc:extensions.bzl", "cc_configure_extension")
use_repo(cc_configure, "local_config_cc")
EOF

  cat > ok.cc <<EOF
#include <stdio.h>
int main() {
  printf("Hello\n");
}
EOF

  cat > BUILD <<EOF
cc_binary(
  name = "ok",
  srcs = ["ok.cc"],
)
EOF
  # As long as the default workspace suffix runs cc_configure the local_config_cc toolchain suite will be evaluated.
  # Ensure the fake cc_toolchain_suite target doesn't have any errors.
  BAZEL_DO_NOT_DETECT_CPP_TOOLCHAIN=1 bazel build '@local_config_cc//:toolchain' &>/dev/null || \
    fail "Fake toolchain target causes analysis errors"

  BAZEL_DO_NOT_DETECT_CPP_TOOLCHAIN=1 bazel build  '//:ok' --toolchain_resolution_debug=@bazel_tools//tools/cpp:toolchain_type &>"$TEST_log" && \
    fail "Toolchains shouldn't be found"
  expect_log "ToolchainResolution: No @@bazel_tools//tools/cpp:toolchain_type toolchain found for target platform @@platforms//host:host."
}

function setup_workspace_layout_with_external_directory() {
  # Make the following layout to test builds in //external subpackages:
  #
  #├── baz
  #│   ├── binary.cc
  #│   └── BUILD
  #└── external
  #    └── foo
  #        ├── BUILD
  #        ├── lib.cc
  #        └── lib.h
  mkdir -p external/foo
  cat > external/foo/BUILD <<EOF
cc_library(
    name = "lib",
    srcs = ["lib.cc"],
    hdrs = ["lib.h"],
    visibility = ["//baz:__subpackages__"],
)
EOF
  cat > external/foo/lib.cc <<EOF
#include "external/foo/lib.h"
#include <iostream>

using std::cout;
using std::endl;
using std::string;

HelloLib::HelloLib(const string& greeting) : greeting_(new string(greeting)) {
}

void HelloLib::greet(const string& thing) {
  cout << *greeting_ << " " << thing << endl;
}
EOF

  cat > external/foo/lib.h <<EOF
#include <string>
#include <memory>

class HelloLib {
 public:
  explicit HelloLib(const std::string &greeting);
  void greet(const std::string &thing);

 private:
  std::unique_ptr<const std::string> greeting_;
};
EOF

  mkdir baz
  cat > baz/BUILD <<EOF
cc_binary(
    name = "binary",
    srcs = ["binary.cc"],
    deps = ["//external/foo:lib"],
)
EOF
  cat > baz/binary.cc <<EOF
#include "external/foo/lib.h"
#include <string>

int main(int argc, char** argv) {
  HelloLib lib("Hello");
  std::string thing = "world";
  if (argc > 1) {
    thing = argv[1];
  }
  lib.greet(thing);
  return 0;
}
EOF

}

function test_execroot_subdir_layout_fails_for_external_subpackages() {
  setup_workspace_layout_with_external_directory

  bazel build --experimental_sibling_repository_layout=false //baz:binary &> "$TEST_log" \
    && fail "build should have failed with sources in the external directory" || true
  expect_log "error:.*external/foo/lib.*"
  expect_log "Target //baz:binary failed to build"
}

function test_execroot_sibling_layout_null_build_for_external_subpackages() {
  setup_workspace_layout_with_external_directory
  bazel build --experimental_sibling_repository_layout //baz:binary \
    || fail "expected build success"

  # Null build.
  bazel build --experimental_sibling_repository_layout //baz:binary &> "$TEST_log" \
    || fail "expected build success"
  expect_log "INFO: 1 process: 1 internal"
}

function test_execroot_sibling_layout_header_scanning_in_external_subpackage() {
  setup_workspace_layout_with_external_directory
  cat << 'EOF' > external/foo/BUILD
cc_library(
    name = "lib",
    srcs = ["lib.cc"],
    # missing header declaration
    visibility = ["//baz:__subpackages__"],
)
EOF

  bazel build --experimental_sibling_repository_layout --spawn_strategy=standalone //external/foo:lib &> "$TEST_log" \
    && fail "build should not have succeeded with missing header file"

  expect_log "undeclared inclusion(s) in rule '//external/foo:lib'" \
     "could not find 'undeclared inclusion' error message in bazel output"
}

function test_sibling_repository_layout_include_external_repo_output() {
  mkdir test
  cat > test/BUILD <<'EOF'
cc_library(
  name = "foo",
  srcs = ["foo.cc"],
  deps = ["@bazel_tools//tools/jdk:jni"],
)
EOF
  cat > test/foo.cc <<'EOF'
#include <jni.h>
#include <stdio.h>

extern "C" JNIEXPORT void JNICALL Java_foo_App_f(JNIEnv *env, jclass clazz, jint x) {
  printf("hello %d\n", x);
}
EOF
  bazel build --experimental_sibling_repository_layout //test:foo > "$TEST_log" \
    || fail "expected build success"
}

# Test writing the exposed args of CPPCompileAction to parameters file
# This is needed to avoid too long commands when the args of one of the target's
# actions are used to run a new action from the aspect. Fixes b/168634763
function test_using_compile_action_args_params_file() {
  mkdir -p package

  cat > "package/lib.bzl" <<EOF
def _actions_test_impl(target, ctx):
    compile_action = None

    for action in target.actions:
      if action.mnemonic == "CppCompile":
        compile_action = action

    args = compile_action.args[0]
    aspect_out = ctx.actions.declare_file('aspect_out')

    # Passing compile_action.outputs as input to the aspect action to ensure
    # it gets the modified args value after executing the compile action.
    ctx.actions.run_shell(inputs = compile_action.outputs,
                          outputs = [aspect_out],
                          command = "for v in \$@; do echo \$v; done > " + aspect_out.path,
                          arguments = [args])
    return [OutputGroupInfo(out=[aspect_out])]

actions_test_aspect = aspect(implementation = _actions_test_impl)
EOF

  cat > "package/x.cc" <<EOF
#include <stdio.h>
int main() {
  printf("Hello\n");
}
EOF

  cat > "package/BUILD" <<EOF
cc_binary(
  name = "x",
  srcs = ["x.cc"],
)
EOF

  # The args should not be written to a file if the experimental flag is not set
  bazel build "package:x" \
      --aspects="//package:lib.bzl%actions_test_aspect" \
      --output_groups=out

  cat "bazel-bin/package/aspect_out" | grep ".params" \
      && fail "CPPCompileAction Args should not have used a params file"

  # Copy the args to be used for validating the params file contents
  cp "bazel-bin/package/aspect_out" "package/expected_args"

  # The args should be written to a file if the experimental flag is set
  bazel build "package:x" \
      --aspects="//package:lib.bzl%actions_test_aspect" \
      --output_groups=out \
      --experimental_use_cpp_compile_action_args_params_file

  cat "bazel-bin/package/aspect_out" | grep ".params" \
      || fail "CPPCompileAction Args should have used a params file"

  # Validate the contents of the params file (with unquoting)
  assert_equals "$(sed 's/\\//g' bazel-bin/package/aspect_out-0.params)" \
      "$(cat package/expected_args)"
}

function test_include_external_genrule_header() {
  REPO_PATH=$TEST_TMPDIR/repo
  mkdir -p "$REPO_PATH"
  touch "$REPO_PATH/REPO.bazel"
  mkdir "$REPO_PATH/foo"
  cat > "$REPO_PATH/foo/BUILD" <<'EOF'
cc_library(
  name = "bar",
  srcs = [
    "bar.cc",
    "inc.h",
  ],
)

genrule(
  name = "inc_h",
  srcs = ["inc.txt"],
  outs = ["inc.h"],
  cmd = "cp $< $@",
)
EOF
  cat > "$REPO_PATH/foo/bar.cc" <<'EOF'
#include "foo/inc.h"

int main() {
  sayhello();
}
EOF
  cat > "$REPO_PATH/foo/inc.txt" <<'EOF'
#include <stdio.h>

void sayhello() {
  printf("hello\n");
}
EOF

  cat >> MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = 'repo', path='$REPO_PATH')
EOF

  bazel build @repo//foo:bar \
    > "$TEST_log" || fail "expected build success"
  bazel build --experimental_sibling_repository_layout @repo//foo:bar \
    > "$TEST_log" || fail "expected build success"
}

function test_reconstructing_cpp_actions_using_shadowed_action() {
  local package="${FUNCNAME[0]}"
  mkdir -p "${package}"

  cat > "${package}/lib.bzl" <<EOF
def _actions_test_impl(target, ctx):
    compile_action = None
    archive_action = None
    link_action = None

    for action in target.actions:
      if action.mnemonic == "CppCompile":
        compile_action = action
      if action.mnemonic == "CppArchive":
        archive_action = action

    if not compile_action or not archive_action:
      fail("Couldn't find compile or archive action.")

    compile_action_outputs = compile_action.outputs.to_list()
    compile_args = ctx.actions.declare_file("compile_args")
    ctx.actions.run_shell(
        outputs = [compile_args],
        command = "echo \$@ > " + compile_args.path,
        arguments = compile_action.args,
    )

    compile_out = ctx.actions.declare_file("compile_out.o")
    ctx.actions.run_shell(
        inputs = [compile_args],
        shadowed_action = compile_action,
        mnemonic = "RecreatedCppCompile",
        outputs = [compile_out],
        command = "\$(cat %s | sed 's|%s|%s|g' | sed 's|%s|%s|g')" % (
            compile_args.path,
            # We need to replace the original output path with something else
            compile_action_outputs[0].path,
            compile_out.path,
            # We need to replace the original .d file output path with something
            # else
            compile_action_outputs[0].path.replace(".o", ".d"),
            compile_out.path + ".d",
        ),
    )

    archive_out = ctx.actions.declare_file("archive_out.a")
    ctx.actions.run_shell(
        shadowed_action = archive_action,
        mnemonic = "RecreatedCppArchive",
        outputs = [archive_out],
        command = "\$@ && cp %s %s" % (
            archive_action.outputs.to_list()[0].path,
            archive_out.path,
        ),
        arguments = archive_action.args,
    )

    return [OutputGroupInfo(out = [
        compile_args,
        compile_out,
        archive_out,
    ])]

actions_test_aspect = aspect(implementation = _actions_test_impl)
EOF

  echo "inline int x() { return 42; }" > "${package}/x.h"
  cat > "${package}/a.cc" <<EOF
#include "${package}/x.h"

int a() { return x(); }
EOF
  cat > "${package}/BUILD" <<EOF
cc_library(
  name = "x",
  hdrs  = ["x.h"],
)

cc_library(
  name = "a",
  srcs = ["a.cc"],
  deps = [":x"],
)
EOF

  # Test that actions are reconstructible under default configuration
  bazel build "${package}:a" \
      --aspects="//${package}:lib.bzl%actions_test_aspect" \
      --output_groups=out || \
      fail "bazel build should've succeeded"

  # Test that compile actions are reconstructible when using param files
  bazel build "${package}:a" \
      --features=compiler_param_file \
      --aspects="//${package}:lib.bzl%actions_test_aspect" \
      --output_groups=out || \
      fail "bazel build should've succeeded with --features=compiler_param_file"
}

function test_include_scanning_smoketest() {
  # Make sure there are no packages containing tools/cpp/INCLUDE_HINTS to exercise that case in
  # IncludeHintsFunction.
  rm -rf BUILD tools
  mkdir pkg
  cat > pkg/BUILD <<EOF
cc_binary(
  name = 'bin',
  srcs = ['bin.cc'],
  deps = [':spurious_dep'],
)

cc_library(
  name = 'spurious_dep',
  hdrs = ['dep.h'],
)
EOF

  cat > pkg/bin.cc <<EOF
#define NASTY "dep.h"
#include NASTY
int main() { return 0; }
EOF

  touch pkg/dep.h

  bazel build --experimental_unsupported_and_brittle_include_scanning --features=cc_include_scanning //pkg:bin &>"$TEST_log" && fail 'include scanning did not (wrongly) remove dependency' || true
  expect_log "Include scanning enabled. This feature is unsupported."
  expect_log "fatal error: '\?dep.h'\?"
}

function test_env_inherit_cc_test() {
  mkdir pkg
  cat > pkg/BUILD <<EOF
cc_test(
  name = 'foo_test',
  srcs = ['foo_test.cc'],
  env_inherit = ['FOO'],
)
EOF

  cat > pkg/foo_test.cc <<EOF
#include <stdlib.h>

int main() {
  auto foo = getenv("FOO");
  if (foo == nullptr) {
    return 1;
  }
  return 0;
}
EOF

  bazel test //pkg:foo_test &> "$TEST_log" && fail "Did not fail as expected. ENV leak?" || true
  FOO=1 bazel test //pkg:foo_test &> "$TEST_log" || fail "Should have inherited FOO env."
}

function test_env_attr_cc_binary() {
  mkdir pkg
  cat > pkg/BUILD <<EOF
cc_binary(
  name = 'foo_bin_with_env',
  srcs = ['foo_test.cc'],
  env = {'FOO': 'bar'},
)

cc_binary(
  name = 'foo_bin',
  srcs = ['foo_test.cc'],
)
EOF

  cat > pkg/foo_test.cc <<EOF
#include <stdlib.h>

int main() {
  auto foo = getenv("FOO");
  if (foo == nullptr) {
    return 1;
  }
  return 0;
}
EOF

  bazel run //pkg:foo_bin &> "$TEST_log" && fail "Did not fail as expected. ENV leak?" || true
  bazel run //pkg:foo_bin_with_env &> "$TEST_log" || fail "Should have used env attr."
}

function external_cc_test_setup() {
  cat >> MODULE.bazel <<'EOF'
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "other_repo",
  path = "other_repo",
)
EOF

  mkdir -p other_repo
  touch other_repo/REPO.bazel

  mkdir -p other_repo/lib
  cat > other_repo/lib/BUILD <<'EOF'
cc_library(
  name = "lib",
  srcs = ["lib.cpp"],
  hdrs = ["lib.h"],
  visibility = ["//visibility:public"],
)
EOF
  cat > other_repo/lib/lib.h <<'EOF'
void print_greeting();
EOF
  cat > other_repo/lib/lib.cpp <<'EOF'
#include <cstdio>
void print_greeting() {
  printf("Hello, world!\n");
}
EOF

  mkdir -p other_repo/test
  cat > other_repo/test/BUILD <<'EOF'
cc_test(
  name = "test",
  srcs = ["test.cpp"],
  deps = ["//lib"],
)
EOF
  cat > other_repo/test/test.cpp <<'EOF'
#include "lib/lib.h"
int main() {
  print_greeting();
}
EOF
}

function test_external_cc_test_sandboxed() {
  [ "$PLATFORM" != "windows" ] || return 0

  external_cc_test_setup

  bazel test \
      --test_output=errors \
      --strategy=sandboxed \
      @other_repo//test >& $TEST_log || fail "Test should pass"
}

function test_external_cc_test_sandboxed_sibling_repository_layout() {
  [ "$PLATFORM" != "windows" ] || return 0

  external_cc_test_setup

  bazel test \
      --test_output=errors \
      --strategy=sandboxed \
      --experimental_sibling_repository_layout \
      @other_repo//test >& $TEST_log || fail "Test should pass"
}

function test_external_cc_test_local() {
  external_cc_test_setup

  bazel test \
      --test_output=errors \
      --strategy=local \
      @other_repo//test >& $TEST_log || fail "Test should pass"
}

function test_external_cc_test_local_sibling_repository_layout() {
  external_cc_test_setup

  bazel test \
      --test_output=errors \
      --strategy=local \
      --experimental_sibling_repository_layout \
      @other_repo//test >& $TEST_log || fail "Test should pass"

  # Test cc compile action can hit the action cache. See
  # https://github.com/bazelbuild/bazel/issues/17819
  bazel shutdown

  bazel test \
      --test_output=errors \
      --strategy=local \
      --experimental_sibling_repository_layout \
      @other_repo//test >& $TEST_log || fail "Test should pass"
  expect_log "1 process: .*1 internal"
}

function test_bazel_current_repository_define() {
  cat >> MODULE.bazel <<'EOF'
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "other_repo",
  path = "other_repo",
)
EOF

  mkdir -p pkg
  cat > pkg/BUILD.bazel <<'EOF'
cc_library(
  name = "library",
  srcs = ["library.cpp"],
  hdrs = ["library.h"],
  implementation_deps = ["@bazel_tools//tools/cpp/runfiles"],
  visibility = ["//visibility:public"],
)

cc_binary(
  name = "binary",
  srcs = ["binary.cpp"],
  deps = [
    ":library",
    "@bazel_tools//tools/cpp/runfiles",
  ],
)

cc_test(
  name = "test",
  srcs = ["test.cpp"],
  deps = [
    ":library",
    "@bazel_tools//tools/cpp/runfiles",
  ],
)
EOF

  cat > pkg/library.cpp <<'EOF'
#include "library.h"
#include <iostream>
void print_repo_name() {
  std::cout << "in " << __FILE__ << ": '" << BAZEL_CURRENT_REPOSITORY << "'" << std::endl;
}
EOF

  cat > pkg/library.h <<'EOF'
void print_repo_name();
EOF

  cat > pkg/binary.cpp <<'EOF'
#include <iostream>
#include "library.h"
int main() {
  std::cout << "in " << __FILE__ << ": '" << BAZEL_CURRENT_REPOSITORY << "'" << std::endl;
  print_repo_name();
}
EOF

  cat > pkg/test.cpp <<'EOF'
#include <iostream>
#include "library.h"
int main() {
  std::cout << "in " << __FILE__ << ": '" << BAZEL_CURRENT_REPOSITORY << "'" << std::endl;
  print_repo_name();
}
EOF

  mkdir -p other_repo
  touch other_repo/REPO.bazel

  mkdir -p other_repo/pkg
  cat > other_repo/pkg/BUILD.bazel <<'EOF'
cc_binary(
  name = "binary",
  srcs = ["binary.cpp"],
  deps = [
    "@//pkg:library",
    "@bazel_tools//tools/cpp/runfiles",
  ],
)

cc_test(
  name = "test",
  srcs = ["test.cpp"],
  deps = [
    "@//pkg:library",
    "@bazel_tools//tools/cpp/runfiles",
  ],
)
EOF

  cat > other_repo/pkg/binary.cpp <<'EOF'
#include <iostream>
#include "pkg/library.h"
int main() {
  std::cout << "in " << __FILE__ << ": '" << BAZEL_CURRENT_REPOSITORY << "'" << std::endl;
  print_repo_name();
}
EOF

  cat > other_repo/pkg/test.cpp <<'EOF'
#include <iostream>
#include "pkg/library.h"
int main() {
  std::cout << "in " << __FILE__ << ": '" << BAZEL_CURRENT_REPOSITORY << "'" << std::endl;
  print_repo_name();
}
EOF

  bazel run //pkg:binary &>"$TEST_log" || fail "Run should succeed"
  expect_log "in pkg/binary.cpp: ''"
  expect_log "in pkg/library.cpp: ''"

  bazel test --test_output=streamed //pkg:test &>"$TEST_log" || fail "Test should succeed"
  expect_log "in pkg/test.cpp: ''"
  expect_log "in pkg/library.cpp: ''"

  bazel run @other_repo//pkg:binary &>"$TEST_log" || fail "Run should succeed"
  expect_log "in external/+_repo_rules+other_repo/pkg/binary.cpp: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.cpp: ''"

  bazel test --test_output=streamed \
    @other_repo//pkg:test &>"$TEST_log" || fail "Test should succeed"
  expect_log "in external/+_repo_rules+other_repo/pkg/test.cpp: '+_repo_rules+other_repo'"
  expect_log "in pkg/library.cpp: ''"
}

function test_compiler_flag_gcc() {
  # The default macOS toolchain always uses XCode's clang.
  [ "$PLATFORM" != "darwin" ] || return 0
  type -P gcc || return 0

  cat > BUILD.bazel <<'EOF'
config_setting(
    name = "gcc_compiler",
    flag_values = {"@bazel_tools//tools/cpp:compiler": "gcc"},
)

cc_binary(
  name = "main",
  srcs = select({":gcc_compiler": ["main.cc"]}),
)
EOF
  cat > main.cc <<'EOF'
int main() {}
EOF

  bazel build //:main --repo_env=CC=gcc || fail "Expected compiler flag to have value 'gcc'"
}

function test_compiler_flag_clang() {
  type -P clang || return 0

  cat > BUILD.bazel <<'EOF'
config_setting(
    name = "clang_compiler",
    flag_values = {"@bazel_tools//tools/cpp:compiler": "clang"},
)

cc_binary(
  name = "main",
  srcs = select({":clang_compiler": ["main.cc"]}),
)
EOF
  cat > main.cc <<'EOF'
int main() {}
EOF

  bazel build //:main --repo_env=CC=clang || fail "Expected compiler flag to have value 'clang'"
}

function test_bazel_cxxopts() {
  cat > BUILD.bazel <<'EOF'
cc_binary(
  name = "main_c",
  srcs = ["main.c"],
)
cc_binary(
  name = "main_cpp",
  srcs = ["main.cpp"],
)
EOF
  cat > main.c <<'EOF'
#include <stdlib.h>
int main() {
  exit(EXIT_CODE);
}
EOF
  cat > main.cpp <<'EOF'
#include <stdlib.h>
int main() {
  exit(EXIT_CODE);
}
EOF

  bazel build //:main_c \
    --repo_env=BAZEL_USE_CPP_ONLY_TOOLCHAIN=1 \
    --repo_env=BAZEL_CXXOPTS=-DEXIT_CODE=0 && fail "Expected C compilation to fail"
  bazel run //:main_cpp \
    --repo_env=BAZEL_USE_CPP_ONLY_TOOLCHAIN=1 \
    --repo_env=BAZEL_CXXOPTS=-DEXIT_CODE=0 || fail "Expected C++ compilation to pass"
}

function test_bazel_conlyopts() {
  cat > BUILD.bazel <<'EOF'
cc_binary(
  name = "main_c",
  srcs = ["main.c"],
)
cc_binary(
  name = "main_cpp",
  srcs = ["main.cpp"],
)
EOF
  cat > main.c <<'EOF'
#include <stdlib.h>
int main() {
  exit(EXIT_CODE);
}
EOF
  cat > main.cpp <<'EOF'
#include <stdlib.h>
int main() {
  exit(EXIT_CODE);
}
EOF

  bazel build //:main_cpp \
    --repo_env=BAZEL_USE_CPP_ONLY_TOOLCHAIN=1 \
    --repo_env=BAZEL_CONLYOPTS=-DEXIT_CODE=0 && fail "Expected C++ compilation to fail"
  bazel run //:main_c \
    --repo_env=BAZEL_USE_CPP_ONLY_TOOLCHAIN=1 \
    --repo_env=BAZEL_CONLYOPTS=-DEXIT_CODE=0 || fail "Expected C compilation to pass"
}

function test_cc_test_no_target_coverage_dep() {
  # Regression test for https://github.com/bazelbuild/bazel/issues/16961
  cat >> MODULE.bazel <<'EOF'
remote_coverage_tools_extension = use_extension("@bazel_tools//tools/test:extensions.bzl", "remote_coverage_tools_extension")
use_repo(remote_coverage_tools_extension, "remote_coverage_tools")
EOF

  local package="${FUNCNAME[0]}"
  mkdir -p "${package}"

  cat > "${package}"/BUILD.bazel <<'EOF'
cc_test(
  name = "test",
  srcs = ["test.cc"],
)
EOF
  touch "${package}"/test.cc

  out=$(bazel cquery --collect_code_coverage \
   "deps(//${package}:test) intersect config(@remote_coverage_tools//:all, target)")
  if [[ -n "$out" ]]; then
    fail "Expected no dependency on lcov_merger in the target configuration, but got: $out"
  fi
}

function test_cc_test_no_coverage_tools_dep_without_coverage() {

  cat >> MODULE.bazel <<'EOF'
remote_coverage_tools_extension = use_extension("@bazel_tools//tools/test:extensions.bzl", "remote_coverage_tools_extension")
use_repo(remote_coverage_tools_extension, "remote_coverage_tools")
EOF

  # Regression test for https://github.com/bazelbuild/bazel/issues/16961 and
  # https://github.com/bazelbuild/bazel/issues/15088.
  local package="${FUNCNAME[0]}"
  mkdir -p "${package}"

  cat > "${package}"/BUILD.bazel <<'EOF'
cc_test(
  name = "test",
  srcs = ["test.cc"],
)
EOF
  touch "${package}"/test.cc

  out=$(bazel cquery "somepath(//${package}:test,@remote_coverage_tools//:all)")
  if [[ -n "$out" ]]; then
    fail "Expected no dependency on remote coverage tools, but got: $out"
  fi
}

# sanitizer features are opt-in so we check if the sanitizer library is
# installed and skip the test if it isn't (e.g. centos-7-openjdk-11-gcc-10)
function __is_installed() {
  local lib="$1"

  if [[ "$(uname -s | tr 'A-Z' 'a-z')" == "linux" ]]; then
    return $(ldconfig -p | grep -q "$lib")
  fi

  # assume installed for darwin
}

function test_cc_toolchain_asan_feature() {
  local feature=asan
  __is_installed "lib$feature" || return 0

  mkdir pkg
  cat > pkg/BUILD <<EOF
cc_binary(
  name = 'example',
  srcs = ['example.cc'],
  features = ['$feature'],
)
EOF

  # some versions of clang will optimize away the pointer assignment and
  # dereference without volatile
  # https://godbolt.org/z/of8cr3P8q
  cat > pkg/example.cc <<EOF
int main() {
  volatile int* p;

  {
    volatile int x = 0;
    p = &x;
  }

  return *p;
}
EOF

  bazel run //pkg:example &> "$TEST_log" && fail "Should have failed due to $feature" || true
  expect_log "ERROR: AddressSanitizer: stack-use-after-scope"
}

function test_cc_toolchain_tsan_feature() {
  local feature=tsan
  __is_installed "lib$feature" || return 0

  mkdir pkg
  cat > pkg/BUILD <<EOF
cc_binary(
  name = 'example',
  srcs = ['example.cc'],
  features = ['$feature'],
)
EOF

  cat > pkg/example.cc <<EOF
#include <thread>

int value = 0;

void increment() {
  ++value;
}

int main() {
  std::thread t1(increment);
  std::thread t2(increment);
  t1.join();
  t2.join();

  return value;
}
EOF

  bazel run //pkg:example &> "$TEST_log" && fail "Should have failed due to $feature" || true
  expect_log "WARNING: ThreadSanitizer: data race"
}

function test_cc_toolchain_ubsan_feature() {
  local feature=ubsan
  __is_installed "lib$feature" || return 0

  mkdir pkg
  cat > pkg/BUILD <<EOF
cc_binary(
  name = 'example',
  srcs = ['example.cc'],
  features = ['$feature'],
)
EOF

  cat > pkg/example.cc <<EOF
int main() {
  int array[10];
  return array[10];
}
EOF

  bazel run //pkg:example &> "$TEST_log" && fail "Should have failed due to $feature" || true
  expect_log "runtime error: index 10 out of bounds"
}

function setup_find_optional_cpp_toolchain() {

  add_platforms "MODULE.bazel"

  mkdir -p pkg

  cat > pkg/BUILD <<'EOF'
load(":rules.bzl", "my_rule")

my_rule(
    name = "my_rule",
)

platform(
    name = "exotic_platform",
    constraint_values = [
        "@platforms//cpu:wasm64",
        "@platforms//os:windows",
    ],
)
EOF

  cat > pkg/rules.bzl <<'EOF'
load("@bazel_tools//tools/cpp:toolchain_utils.bzl", "find_cpp_toolchain", "use_cpp_toolchain")

def _my_rule_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.name)
    toolchain = find_cpp_toolchain(ctx, mandatory = False)
    if toolchain:
        ctx.actions.write(out, "Toolchain found")
    else:
        ctx.actions.write(out, "Toolchain not found")
    return [DefaultInfo(files = depset([out]))]

my_rule = rule(
    implementation = _my_rule_impl,
    attrs = {
        "_cc_toolchain": attr.label(
            default = "@bazel_tools//tools/cpp:optional_current_cc_toolchain",
        ),
    },
    toolchains = use_cpp_toolchain(mandatory = False),
)
EOF
}

function test_find_optional_cpp_toolchain_present_without_toolchain_resolution() {
  setup_find_optional_cpp_toolchain

  bazel build //pkg:my_rule --noincompatible_enable_cc_toolchain_resolution \
    &> "$TEST_log" || fail "Build failed"
  assert_contains "Toolchain found" bazel-bin/pkg/my_rule
}

function test_find_optional_cpp_toolchain_present_with_toolchain_resolution() {
  setup_find_optional_cpp_toolchain

  bazel build //pkg:my_rule --incompatible_enable_cc_toolchain_resolution \
    &> "$TEST_log" || fail "Build failed"
  assert_contains "Toolchain found" bazel-bin/pkg/my_rule
}

function test_find_optional_cpp_toolchain_not_present_with_toolchain_resolution() {
  setup_find_optional_cpp_toolchain

  bazel build //pkg:my_rule --incompatible_enable_cc_toolchain_resolution \
    --platforms=//pkg:exotic_platform &> "$TEST_log" || fail "Build failed"
  assert_contains "Toolchain not found" bazel-bin/pkg/my_rule
}

function test_no_cpp_stdlib_linked_to_c_library() {
  mkdir pkg
  cat > pkg/BUILD <<'EOF'
cc_binary(
  name = 'example',
  srcs = ['example.c'],
)
EOF
  cat > pkg/example.c <<'EOF'
int main() {}
EOF

  bazel build //pkg:example &> "$TEST_log" || fail "Build failed"
  if is_darwin; then
    otool -L bazel-bin/pkg/example &> "$TEST_log" || fail "otool failed"
    expect_log 'libc'
    expect_not_log 'libc\+\+'
  else
    ldd bazel-bin/pkg/example &> "$TEST_log" || fail "ldd failed"
    expect_log 'libc'
    expect_not_log 'libstdc\+\+'
  fi
}

function test_parse_headers_unclean() {
  mkdir pkg
  cat > pkg/BUILD <<'EOF'
cc_library(name = "lib", hdrs = ["lib.h"])
EOF
  cat > pkg/lib.h <<'EOF'
// Missing include of cstdint, which defines uint8_t.
uint8_t foo();
EOF

  bazel build -s --process_headers_in_dependencies --features parse_headers \
    //pkg:lib &> "$TEST_log" && fail "Build should have failed due to unclean headers"
  expect_log "Compiling pkg/lib.h"
  expect_log "error:.*uint8_t"

  bazel build -s --process_headers_in_dependencies \
    //pkg:lib &> "$TEST_log" || fail "Build should have passed"
}

function test_parse_headers_clean() {
  mkdir pkg
  cat > pkg/BUILD <<'EOF'
package(features = ["parse_headers"])
cc_library(name = "lib", hdrs = ["lib.h"])
EOF
  cat > pkg/lib.h <<'EOF'
#include <cstdint>
uint8_t foo();
EOF

  bazel build -s --process_headers_in_dependencies \
    //pkg:lib &> "$TEST_log" || fail "Build should have passed"
  expect_log "Compiling pkg/lib.h"
}

# Test for a very obscure case that is sadly used by protobuf: when the
# WORKSPACE file contains a local_repository with the same name as the main
# one. See HeaderDiscovery.runDiscovery() for more details.
function test_inclusion_validation_with_overlapping_external_repo() {
  cat >> WORKSPACE<<EOF
local_repository(name="$WORKSPACE_NAME", path=".")
EOF

  mkdir -p a
  cat > a/BUILD <<'EOF'
cc_library(name="a", srcs=["a.cc"])
EOF

  cat > a/a.cc <<'EOF'
int a() {
  return 3;
}
EOF

  bazel build \
    --noenable_bzlmod \
    --enable_workspace \
    --experimental_sibling_repository_layout \
    "@@$WORKSPACE_NAME//a:a" || fail "build failed"
}

function test_tree_artifact_sources_in_no_deps_library() {
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF'
load("generate.bzl", "generate_source")
sh_binary(
    name = "generate_tool",
    srcs = ["generate.sh"],
)

generate_source(
    name = "generated_source",
    tool = ":generate_tool",
    output_dir = "generated",
)

cc_library(
    name = "hello_world",
    srcs = [":generated_source"],
    hdrs = [":generated_source"],
)

cc_test(
    name = "testCodegen",
    srcs = ["testCodegen.cpp"],
    deps = [":hello_world"],
)
EOF
  cat > pkg/generate.bzl <<'EOF'
def _generate_source_impl(ctx):
    output_dir = ctx.attr.output_dir
    files = ctx.actions.declare_directory(output_dir)

    ctx.actions.run(
        inputs = [],
        outputs = [files],
        arguments = [files.path],
        executable = ctx.executable.tool
    )

    return [
        DefaultInfo(files = depset([files]))
    ]


generate_source = rule(
    implementation = _generate_source_impl,
    attrs = {
        "output_dir": attr.string(),
        "tool": attr.label(executable = True, cfg = "exec")
    }
)
EOF
  cat > pkg/generate.sh <<'EOF2'
#!/bin/bash

OUTPUT_DIR=$1

cat << EOF > $OUTPUT_DIR/test.hpp
#pragma once
void hello_world();
EOF


cat << EOF > $OUTPUT_DIR/test.cpp
#include "test.hpp"
#include <cstdio>
void hello_world()
{
    puts("Hello World!");
}
EOF
EOF2
  chmod +x pkg/generate.sh
  cat > pkg/testCodegen.cpp <<'EOF'
#include "pkg/generated/test.hpp"

int main() {
    hello_world();
    return 0;
}
EOF

  bazel build //pkg:testCodegen &> "$TEST_log" || fail "Build failed"
}

run_suite "cc_integration_test"
