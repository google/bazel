#!/bin/bash
#
# Copyright 2023 The Bazel Authors. All rights reserved.
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
# Tests path mapping support of Bazel's executors.

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
source "$(rlocation "io_bazel/src/test/shell/bazel/remote_helpers.sh")" \
  || { echo "remote_helpers.sh not found!" >&2; exit 1; }
source "$(rlocation "io_bazel/src/test/shell/bazel/remote/remote_utils.sh")" \
  || { echo "remote_utils.sh not found!" >&2; exit 1; }

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  function is_windows() { true; }
  ;;
*)
  function is_windows() { false; }
  ;;
esac

function set_up() {
  start_worker

  mkdir -p src/main/java/com/example
  cat > src/main/java/com/example/BUILD <<'EOF'
java_binary(
    name = "Main",
    srcs = ["Main.java"],
    deps = [":lib"],
)
java_library(
    name = "lib",
    srcs = ["Lib.java"],
)
EOF
  cat > src/main/java/com/example/Main.java <<'EOF'
package com.example;
public class Main {
  public static void main(String[] args) {
    System.out.println(Lib.getGreeting());
  }
}
EOF
  cat > src/main/java/com/example/Lib.java <<'EOF'
package com.example;
public class Lib {
  public static String getGreeting() {
    return "Hello, World!";
  }
}
EOF
}

function tear_down() {
  bazel clean >& $TEST_log
  stop_worker
}

function test_path_stripping_sandboxed() {
  if is_windows; then
    echo "Skipping test_path_stripping_sandboxed on Windows as it requires sandboxing"
    return
  fi

  cache_dir=$(mktemp -d)

  bazel run -c fastbuild \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=sandboxed \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses, 1x header compilation and 2x
  # actual compilation.
  expect_log '5 \(linux\|darwin\|processwrapper\)-sandbox'
  expect_not_log 'disk cache hit'

  bazel run -c opt \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=sandboxed \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  expect_log '5 disk cache hit'
  expect_not_log '[0-9] \(linux\|darwin\|processwrapper\)-sandbox'
}

function test_path_stripping_singleplex_worker() {
  if is_windows; then
    echo "Skipping test_path_stripping_singleplex_worker on Windows as it requires sandboxing"
    return
  fi

  cache_dir=$(mktemp -d)

  bazel run -c fastbuild \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=worker \
    --worker_sandboxing \
    --noexperimental_worker_multiplex \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses and header compilation.
  expect_log '3 \(linux\|darwin\|processwrapper\)-sandbox'
  # Actual compilation actions.
  expect_log '2 worker'
  expect_not_log 'disk cache hit'

  bazel run -c opt \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=worker \
    --worker_sandboxing \
    --noexperimental_worker_multiplex \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  expect_log '5 disk cache hit'
  expect_not_log '[0-9] \(linux\|darwin\|processwrapper\)-sandbox'
  expect_not_log '[0-9] worker'
}

function test_path_stripping_multiplex_worker() {
  if is_windows; then
    echo "Skipping test_path_stripping_multiplex_worker on Windows as it requires sandboxing"
    return
  fi

  mkdir toolchain
  cat > toolchain/BUILD <<'EOF'
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain")
default_java_toolchain(
    name = "java_toolchain",
    source_version = "17",
    target_version = "17",
    javac_supports_worker_multiplex_sandboxing = True,
)
EOF

  cache_dir=$(mktemp -d)

  bazel run -c fastbuild \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=worker \
    --experimental_worker_multiplex_sandboxing \
    --extra_toolchains=//toolchain:java_toolchain_definition \
    --java_language_version=17 \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses and header compilation.
  expect_log '3 \(linux\|darwin\|processwrapper\)-sandbox'
  # Actual compilation actions.
  expect_log '2 worker'
  expect_not_log 'disk cache hit'

  bazel run -c opt \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=worker \
    --experimental_worker_multiplex_sandboxing \
    --extra_toolchains=//toolchain:java_toolchain_definition \
    --java_language_version=17 \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  expect_log '5 disk cache hit'
  expect_not_log '[0-9] \(linux\|darwin\|processwrapper\)-sandbox'
  expect_not_log '[0-9] worker'
}

function test_path_stripping_generated_multiplex_worker() {
  if is_windows; then
    echo "Skipping test_path_stripping_generated_multiplex_worker on Windows as it requires sandboxing"
    return
  fi

  cat >> MODULE.bazel <<'EOF'
bazel_dep(name = "rules_java", version = "8.1.0")
toolchains = use_extension("@rules_java//java:extensions.bzl", "toolchains")
use_repo(toolchains, "remote_java_tools")
EOF

  mkdir toolchain
  cat > toolchain/BUILD <<'EOF'
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain")
genrule(
    name = "gen_javabuilder",
    srcs = ["@remote_java_tools//:JavaBuilder"],
    outs = ["JavaBuilder.jar"],
    cmd = "cp $< $@",
    executable = True,
)
default_java_toolchain(
    name = "java_toolchain",
    source_version = "17",
    target_version = "17",
    javac_supports_worker_multiplex_sandboxing = True,
    javabuilder = ":gen_javabuilder",
)
EOF

  cache_dir=$(mktemp -d)

  bazel run -c fastbuild \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=worker \
    --experimental_worker_multiplex_sandboxing \
    --extra_toolchains=//toolchain:java_toolchain_definition \
    --java_language_version=17 \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  # Genrule, JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses and header compilation.
  expect_log '4 \(linux\|darwin\|processwrapper\)-sandbox'
  # Actual compilation actions.
  expect_log '2 worker'
  expect_not_log 'disk cache hit'

  bazel run -c opt \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=worker \
    --experimental_worker_multiplex_sandboxing \
    --extra_toolchains=//toolchain:java_toolchain_definition \
    --java_language_version=17 \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  expect_log '5 disk cache hit'
  expect_not_log '[0-9] \(linux\|darwin\|processwrapper\)-sandbox'
  expect_not_log '[0-9] worker'
}

function test_path_stripping_remote() {
  bazel run -c fastbuild \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses, 1x header compilation and 2x
  # actual compilation.
  expect_log '5 remote'
  expect_not_log 'remote cache hit'

  bazel run -c opt \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  expect_log '5 remote cache hit'
  # Do not match "5 remote cache hit", which is expected.
  expect_not_log '[0-9] remote[^ ]'
}

function test_path_stripping_remote_multiple_configs() {
  mkdir rules
  cat > rules/defs.bzl <<'EOF'
LocationInfo = provider(fields = ["location"])

def _location_setting_impl(ctx):
    return LocationInfo(location = ctx.build_setting_value)

location_setting = rule(
    implementation = _location_setting_impl,
    build_setting = config.string(),
)

def _location_transition_impl(settings, attr):
    return {"//rules:location": attr.location}

_location_transition = transition(
    implementation = _location_transition_impl,
    inputs = [],
    outputs = ["//rules:location"],
)

def _bazelcon_greeting_impl(ctx):
    content = """
package com.example.{package};

public class Lib {{
  public static String getGreeting() {{
    return String.format("Hello, BazelCon {location}!");
  }}
}}
""".format(
        package = ctx.attr.name,
        location = ctx.attr.location,
    )
    file = ctx.actions.declare_file("Lib.java")
    ctx.actions.write(file, content)
    return [
        DefaultInfo(files = depset([file])),
    ]

bazelcon_greeting = rule(
    _bazelcon_greeting_impl,
    cfg = _location_transition,
    attrs = {
        "location": attr.string(),
    },
)
EOF
  cat > rules/BUILD << 'EOF'
load("//rules:defs.bzl", "location_setting")

location_setting(
    name = "location",
    build_setting_default = "",
)
EOF

  mkdir -p src/main/java/com/example
  cat > src/main/java/com/example/BUILD <<'EOF'
load("//rules:defs.bzl", "bazelcon_greeting")
java_binary(
    name = "Main",
    srcs = ["Main.java"],
    deps = [":lib"],
)
java_library(
    name = "lib",
    srcs = [
        ":munich",
        ":new_york",
    ],
)
bazelcon_greeting(
    name = "munich",
    location = "Munich",
)
bazelcon_greeting(
    name = "new_york",
    location = "New York",
)
EOF
  cat > src/main/java/com/example/Main.java <<'EOF'
package com.example;
public class Main {
  public static void main(String[] args) {
    System.out.println(com.example.new_york.Lib.getGreeting());
    System.out.println(com.example.munich.Lib.getGreeting());
  }
}
EOF

  bazel run -c fastbuild \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, BazelCon New York!'
  expect_log 'Hello, BazelCon Munich!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses, 1x header compilation and 2x
  # actual compilation.
  expect_log '5 remote'
  expect_not_log 'remote cache hit'

  bazel run -c opt \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, BazelCon New York!'
  expect_log 'Hello, BazelCon Munich!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses and compilation of the binary.
  expect_log '3 remote cache hit'
  # Do not match "[0-9] remote cache hit", which is expected separately.
  # Header and actual compilation of the library, which doesn't use path stripping as it would
  # result in ambiguous paths due to the multiple configs.
  expect_log '2 remote[^ ]'
}

function test_path_stripping_disabled_with_tags() {
  mkdir pkg
  cat > pkg/defs.bzl <<'EOF'
def _my_rule_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.name)
    args = ctx.actions.args()
    args.add(out)
    ctx.actions.run_shell(
         outputs = [out],
         command = "echo 'Hello, World!' > $1",
         arguments = [args],
         execution_requirements = {"supports-path-mapping": ""},
    )
    return [
        DefaultInfo(files = depset([out])),
    ]

my_rule = rule(_my_rule_impl)
EOF
  cat > pkg/BUILD << 'EOF'
load(":defs.bzl", "my_rule")

my_rule(
    name = "local_target",
    tags = ["local"],
)

my_rule(
    name = "implicitly_local_target",
    tags = [
        "no-sandbox",
        "no-remote",
    ],
)
EOF

  bazel build --experimental_output_paths=strip //pkg:all &> $TEST_log || fail "build failed unexpectedly"
}

# Verifies that path mapping results in cache hits for CppCompile actions
# subject to transitions that don't affect their inputs.
function test_path_stripping_cc_remote() {
  local -r pkg="${FUNCNAME[0]}"

  cat > MODULE.bazel <<EOF
bazel_dep(name = "apple_support", version = "1.15.1")
EOF

  mkdir -p "$pkg"
  cat > "$pkg/BUILD" <<EOF
load("//$pkg/common/utils:defs.bzl", "gen_cc", "transition_wrapper")

cc_binary(
    name = "main",
    srcs = [
        "main.cc",
        ":gen",
    ],
    deps = [
        "//$pkg/lib1",
        "//$pkg/lib2",
    ],
)

gen_cc(
    name = "gen",
    subject = "TreeArtifact",
)

transition_wrapper(
    name = "transitioned_main",
    greeting = "Hi there",
    target = ":main",
)
EOF
  cat > "$pkg/main.cc" <<EOF
#include <iostream>
#include <string>
#include "$pkg/lib1/gen/top_level.h"
#include "$pkg/lib1/gen/other_dir/not_top_level.h"
#include "$pkg/lib1/lib1.h"
#include "lib2.h"

std::string TreeArtifactGreeting();

int main() {
  std::cout << GetLib1Greeting() << std::endl;
  std::cout << GetLib2Greeting() << std::endl;
  std::cout << TreeArtifactGreeting() << std::endl;
  std::cout << TOP_LEVEL_CONSTANT << " " << NOT_TOP_LEVEL_CONSTANT << std::endl;
  return 0;
}
EOF

  mkdir -p "$pkg"/lib1
  cat > "$pkg/lib1/BUILD" <<EOF
load("//$pkg/common/utils:defs.bzl", "gen_h", "transition_wrapper")

cc_library(
    name = "lib1",
    srcs = ["lib1.cc"],
    hdrs = [
        "lib1.h",
        ":gen",
    ],
    deps = ["//$pkg/common/utils:utils"],
    visibility = ["//visibility:public"],
)

gen_h(
    name = "gen",
)
EOF
  cat > "$pkg/lib1/lib1.h" <<'EOF'
#ifndef LIB1_H_
#define LIB1_H_

#include <string>

std::string GetLib1Greeting();

#endif
EOF
  cat > "$pkg/lib1/lib1.cc" <<'EOF'
#include "lib1.h"
#include "other_dir/utils.h"

std::string GetLib1Greeting() {
  return AsGreeting("lib1");
}
EOF

  mkdir -p "$pkg"/lib2
  cat > "$pkg/lib2/BUILD" <<EOF
genrule(
    name = "gen_header",
    srcs = ["lib2.h.tpl"],
    outs = ["lib2.h"],
    cmd = "cp \$< \$@",
)
genrule(
    name = "gen_source",
    srcs = ["lib2.cc.tpl"],
    outs = ["lib2.cc"],
    cmd = "cp \$< \$@",
)
cc_library(
    name = "lib2",
    srcs = ["lib2.cc"],
    hdrs = ["lib2.h"],
    includes = ["."],
    deps = ["//$pkg/common/utils:utils"],
    visibility = ["//visibility:public"],
)
EOF
  cat > "$pkg/lib2/lib2.h.tpl" <<'EOF'
#ifndef LIB2_H_
#define LIB2_H_

#include <string>

std::string GetLib2Greeting();

#endif
EOF
  cat > "$pkg/lib2/lib2.cc.tpl" <<'EOF'
#include "lib2.h"
#include "other_dir/utils.h"

std::string GetLib2Greeting() {
  return AsGreeting("lib2");
}
EOF

  mkdir -p "$pkg"/common/utils
  cat > "$pkg/common/utils/BUILD" <<'EOF'
load(":defs.bzl", "greeting_setting")

greeting_setting(
    name = "greeting",
    build_setting_default = "Hello",
)
genrule(
    name = "gen_header",
    srcs = ["utils.h.tpl"],
    outs = ["dir/utils.h"],
    cmd = "cp $< $@",
)
genrule(
    name = "gen_source",
    srcs = ["utils.cc.tpl"],
    outs = ["dir/utils.cc"],
    cmd = "sed -e 's/{GREETING}/$(GREETING)/' $< > $@",
    toolchains = [":greeting"],
)
cc_library(
    name = "utils",
    srcs = ["dir/utils.cc"],
    hdrs = ["dir/utils.h"],
    defines = ["MY_FILE=\\\"+$(execpath dir/utils.cc)+\\\""],
    include_prefix = "other_dir",
    strip_include_prefix = "dir",
    visibility = ["//visibility:public"],
)
EOF
  cat > "$pkg/common/utils/utils.h.tpl" <<'EOF'
#ifndef SOME_PKG_UTILS_H_
#define SOME_PKG_UTILS_H_

#include <string>

std::string AsGreeting(const std::string& name);
#endif
EOF
  cat > "$pkg/common/utils/defs.bzl" <<EOF
def _greeting_setting_impl(ctx):
    return platform_common.TemplateVariableInfo({
        "GREETING": ctx.build_setting_value,
    })

greeting_setting = rule(
    implementation = _greeting_setting_impl,
    build_setting = config.string(),
)

def _greeting_transition_impl(settings, attr):
    return {"//$pkg/common/utils:greeting": attr.greeting}

greeting_transition = transition(
    implementation = _greeting_transition_impl,
    inputs = [],
    outputs = ["//$pkg/common/utils:greeting"],
)

def _transition_wrapper_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.symlink(output = out, target_file = ctx.executable.target, is_executable = True)
    return [
        DefaultInfo(executable = out),
    ]

transition_wrapper = rule(
    cfg = greeting_transition,
    implementation = _transition_wrapper_impl,
    attrs = {
        "greeting": attr.string(),
        "target": attr.label(
            cfg = "target",
            executable = True,
        ),
    },
    executable = True,
)

def _gen_cc_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    ctx.actions.run_shell(
        outputs = [out],
        command = """\
cat >{out_path}/gen.cc <<EOF2
#include <string>

std::string TreeArtifactGreeting() {{
  return "Hello, {subject}!";
}}
EOF2
        """.format(
            out_path = out.path,
            subject = ctx.attr.subject,
        ),
    )
    return [
        DefaultInfo(files = depset([out])),
    ]

gen_cc = rule(
    implementation = _gen_cc_impl,
    attrs = {
        "subject": attr.string(),
    },
)

def _gen_h_impl(ctx):
    out = ctx.actions.declare_directory(ctx.label.name)
    ctx.actions.run_shell(
        outputs = [out],
        command = """\
cat >{out_path}/top_level.h <<EOF2
#ifndef TOP_LEVEL_H_
#define TOP_LEVEL_H_

#define TOP_LEVEL_CONSTANT 42

#endif
EOF2
mkdir -p {out_path}/other_dir
cat >{out_path}/other_dir/not_top_level.h <<EOF2
#ifndef NOT_TOP_LEVEL_H_
#define NOT_TOP_LEVEL_H_

#define NOT_TOP_LEVEL_CONSTANT 43

#endif
EOF2
        """.format(
            out_path = out.path,
        ),
    )
    return [
        DefaultInfo(files = depset([out])),
    ]

gen_h = rule(implementation = _gen_h_impl)
EOF
  cat > "$pkg/common/utils/utils.cc.tpl" <<'EOF'
#include "utils.h"

#include <cstdlib>
#include <iostream>

std::string AsGreeting(const std::string& name) {
  if (std::string(MY_FILE).find("-out/cfg/") == std::string::npos) {
    std::cerr << "Expected path to contain '-out/cfg/'" << std::endl;
  }
  return "{GREETING}, " + name + "!";
}
EOF

  bazel run \
    --verbose_failures \
    --experimental_output_paths=strip \
    --modify_execution_info=CppCompile=+supports-path-mapping,CppModuleMap=+supports-path-mapping,CppArchive=+supports-path-mapping \
    --remote_executor=grpc://localhost:${worker_port} \
    --features=layering_check \
    "//$pkg:main" &>"$TEST_log" || fail "Expected success"

  expect_log 'Hello, lib1!'
  expect_log 'Hello, lib2!'
  expect_log 'Hello, TreeArtifact!'
  expect_log '42 43'
  expect_not_log 'remote cache hit'

  bazel run \
    --verbose_failures \
    --experimental_output_paths=strip \
    --modify_execution_info=CppCompile=+supports-path-mapping,CppModuleMap=+supports-path-mapping,CppArchive=+supports-path-mapping \
    --remote_executor=grpc://localhost:${worker_port} \
    --features=layering_check \
    -s \
    "//$pkg:transitioned_main" &>"$TEST_log" || fail "Expected success"

  expect_log 'Hi there, lib1!'
  expect_log 'Hi there, lib2!'
  expect_log 'Hello, TreeArtifact!'
  expect_log '42 43'
  # Compilation actions for lib1, lib2 and main should result in cache hits due
  # to path stripping, utils is legitimately different and should not (4 cached
  # out of 5 total).
  # Likewise, link actions for lib1 and lib2 should result in cache hits, but
  # the one for utils does not and the linking action for main doesn't support
  # path mapping (2 cached out of 4 in total). In CI, the C++ toolchain on Linux
  # uses --start-lib/--end-lib linker support to avoid the CppArchive actions
  # entirely (0 cached out of 1 in total).
  # The two custom actions and the four genrule actions are not path-mapped
  # (0 cached out of 6 in total).
  if is_darwin; then
    expect_log ' 6 remote cache hit'
    expect_log ' 9 remote'
  else
    expect_log ' 4 remote cache hit'
    expect_log ' 8 remote'
  fi
}

function test_path_stripping_action_key_not_stale_for_path_collision() {
  mkdir rules
  cat > rules/defs.bzl <<'EOF'
LocationInfo = provider(fields = ["location"])

def _location_setting_impl(ctx):
    return LocationInfo(location = ctx.build_setting_value)

location_setting = rule(
    implementation = _location_setting_impl,
    build_setting = config.string(),
)

def _location_transition_impl(settings, attr):
    return {"//rules:location": attr.location}

_location_transition = transition(
    implementation = _location_transition_impl,
    inputs = [],
    outputs = ["//rules:location"],
)

def _bazelcon_greeting_impl(ctx):
    file = ctx.actions.declare_file("greeting.txt")
    content = "Hello, BazelCon {}!\n".format(ctx.attr.location)
    ctx.actions.write(file, content)
    return [
        DefaultInfo(files = depset([file])),
        LocationInfo(location = ctx.attr.location),
    ]

bazelcon_greeting = rule(
    _bazelcon_greeting_impl,
    cfg = _location_transition,
    attrs = {
        "location": attr.string(),
    },
)

def _file_path(target):
    return target[DefaultInfo].files.to_list()[0].path

def _all_greetings_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name)

    targets = ctx.attr.greetings
    if ctx.attr.sort:
        targets = sorted(targets, key = lambda target: target[LocationInfo].location)

    args = ctx.actions.args()
    args.add(out)
    args.add_all(targets, map_each = _file_path)

    ctx.actions.run_shell(
        inputs = depset(ctx.files.greetings),
        outputs = [out],
        arguments = [args],
        command = "cat ${@:2} > $1",
        execution_requirements = {"supports-path-mapping": ""},
    )

    return [DefaultInfo(files = depset([out]))]

all_greetings = rule(
    _all_greetings_impl,
    attrs = {
        "greetings": attr.label_list(allow_files = True),
        "sort": attr.bool(),
    },
)
EOF
  cat > rules/BUILD << 'EOF'
load("//rules:defs.bzl", "location_setting")

location_setting(
    name = "location",
    build_setting_default = "",
)
EOF

  mkdir -p pkg/greetings
  cat > pkg/greetings/BUILD <<'EOF'
load("//rules:defs.bzl", "bazelcon_greeting")
bazelcon_greeting(
    name = "munich",
    location = "Munich",
    visibility = ["//visibility:public"],
)
bazelcon_greeting(
    name = "new_york",
    location = "New York",
    visibility = ["//visibility:public"],
)
bazelcon_greeting(
    name = "mountain_view",
    location = "Mountain View",
    visibility = ["//visibility:public"],
)
EOF
  cat > pkg/BUILD <<'EOF'
load("//rules:defs.bzl", "all_greetings")
all_greetings(
    name = "all_greetings",
    greetings = [
        "//pkg/greetings:new_york",
        "//pkg/greetings:munich",
        "//pkg/greetings:mountain_view",
    ],
)
EOF

  bazel build pkg:all_greetings -s \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
     &> $TEST_log || fail "run failed unexpectedly"
  assert_equals "Hello, BazelCon New York!
Hello, BazelCon Munich!
Hello, BazelCon Mountain View!" "$(cat "$(bazel cquery --output=files //pkg:all_greetings)")"

  # Change the action command line in a way that only affects the unstripped
  # map_each output.
  cat > pkg/BUILD <<'EOF'
load("//rules:defs.bzl", "all_greetings")
all_greetings(
    name = "all_greetings",
    greetings = [
        "//pkg/greetings:new_york",
        "//pkg/greetings:munich",
        "//pkg/greetings:mountain_view",
    ],
    sort = True,
)
EOF

  bazel build pkg:all_greetings \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
     &> $TEST_log || fail "run failed unexpectedly"
  assert_equals "Hello, BazelCon Mountain View!
Hello, BazelCon Munich!
Hello, BazelCon New York!" "$(cat "$(bazel cquery --output=files //pkg:all_greetings)")"
}

function test_path_stripping_deduplicated_action() {
  if is_windows; then
    echo "Skipping test_path_stripping_deduplicated_action on Windows as it requires sandboxing"
    return
  fi

  mkdir rules
  touch rules/BUILD
  cat > rules/defs.bzl <<'EOF'
def _slow_rule_impl(ctx):
    out_file = ctx.actions.declare_file(ctx.attr.name + "_file")
    out_dir = ctx.actions.declare_directory(ctx.attr.name + "_dir")
    out_symlink = ctx.actions.declare_symlink(ctx.attr.name + "_symlink")
    outs = [out_file, out_dir, out_symlink]
    args = ctx.actions.args().add_all(outs, expand_directories = False)
    ctx.actions.run_shell(
         outputs = outs,
         command = """
         # Sleep to ensure that two actions are scheduled in parallel.
         sleep 3

         echo "Hello, stdout!"
         >&2 echo "Hello, stderr!"

         echo 'echo "Hello, file!"' > $1
         chmod +x $1
         echo 'Hello, dir!' > $2/file
         ln -s $(basename $2)/file $3
         """,
         arguments = [args],
         execution_requirements = {"supports-path-mapping": ""},
    )
    return [
        DefaultInfo(files = depset(outs)),
    ]

slow_rule = rule(_slow_rule_impl)
EOF

  mkdir -p pkg
  cat > pkg/BUILD <<'EOF'
load("//rules:defs.bzl", "slow_rule")

slow_rule(name = "my_rule")

COMMAND = """
function validate() {
  # Sorted by file name.
  local -r dir=$$1
  local -r file=$$2
  local -r symlink=$$3

  [[ $$($$file) == "Hello, file!" ]] || exit 1

  [[ -d $$dir ]] || exit 1
  [[ $$(cat $$dir/file) == "Hello, dir!" ]] || exit 1

  [[ -L $$symlink ]] || exit 1
  [[ $$(cat $$symlink) == "Hello, dir!" ]] || exit 1
}
validate $(execpaths :my_rule)
touch $@
"""

genrule(
    name = "gen_exec",
    outs = ["out_exec"],
    cmd = COMMAND,
    tools = [":my_rule"],
)

genrule(
    name = "gen_target",
    outs = ["out_target"],
    cmd = COMMAND,
    srcs = [":my_rule"],
)
EOF

  bazel build \
    --experimental_output_paths=strip \
    --remote_cache=grpc://localhost:${worker_port} \
    //pkg:all &> $TEST_log || fail "build failed unexpectedly"
  # The first slow_write action plus two genrules.
  expect_log '3 \(linux\|darwin\|processwrapper\)-sandbox'
  expect_log '1 deduplicated'

  # Even though the spawn is only executed once, its stdout/stderr should be
  # printed as if it wasn't deduplicated.
  expect_log_once 'INFO: From Action pkg/my_rule_file:'
  expect_log_once 'INFO: From Action pkg/my_rule_file \[for tool\]:'
  expect_log_n 'Hello, stderr!' 2
  expect_log_n 'Hello, stdout!' 2

  bazel clean || fail "clean failed unexpectedly"
  bazel build \
    --experimental_output_paths=strip \
    --remote_cache=grpc://localhost:${worker_port} \
    //pkg:all &> $TEST_log || fail "build failed unexpectedly"
  # The cache is checked before deduplication.
  expect_log '4 remote cache hit'
}

function test_path_stripping_deduplicated_action_output_not_created() {
  if is_windows; then
    echo "Skipping test_path_stripping_deduplicated_action_output_not_created on Windows as it requires sandboxing"
    return
  fi

  mkdir rules
  touch rules/BUILD
  cat > rules/defs.bzl <<'EOF'
def _slow_rule_impl(ctx):
    out_file = ctx.actions.declare_file(ctx.attr.name + "_file")
    outs = [out_file]
    args = ctx.actions.args().add_all(outs)
    ctx.actions.run_shell(
         outputs = outs,
         command = """
         # Sleep to ensure that two actions are scheduled in parallel.
         sleep 3

         echo "Hello, stdout!"
         >&2 echo "Hello, stderr!"

         # Do not create the output file
         """,
         arguments = [args],
         execution_requirements = {"supports-path-mapping": ""},
    )
    return [
        DefaultInfo(files = depset(outs)),
    ]

slow_rule = rule(_slow_rule_impl)
EOF

  mkdir -p pkg
  cat > pkg/BUILD <<'EOF'
load("//rules:defs.bzl", "slow_rule")

slow_rule(name = "my_rule")

COMMAND = """
[[ $$($(execpath :my_rule)) == "Hello, file!" ]] || exit 1
touch $@
"""

genrule(
    name = "gen_exec",
    outs = ["out_exec"],
    cmd = COMMAND,
    tools = [":my_rule"],
)

genrule(
    name = "gen_target",
    outs = ["out_target"],
    cmd = COMMAND,
    srcs = [":my_rule"],
)
EOF

  bazel build \
    --experimental_output_paths=strip \
    --remote_cache=grpc://localhost:${worker_port} \
    --keep_going \
    //pkg:all &> $TEST_log && fail "build succeeded unexpectedly"
  # The second action runs normally after discovering that the first one failed
  # to create the output file.
  expect_log '2 \(linux\|darwin\|processwrapper\)-sandbox'
  expect_not_log '[0-9] deduplicated'

  expect_log 'Action pkg/my_rule_file failed:'
  expect_log 'Action pkg/my_rule_file \[for tool\] failed:'
  # Remote cache warning.
  expect_log 'Expected output pkg/my_rule_file was not created locally.'

  expect_log_once 'INFO: From Action pkg/my_rule_file:'
  expect_log_once 'INFO: From Action pkg/my_rule_file \[for tool\]:'
  expect_log_n 'Hello, stderr!' 2
  expect_log_n 'Hello, stdout!' 2
}

function test_path_stripping_deduplicated_action_non_zero_exit_code() {
  if is_windows; then
    echo "Skipping test_path_stripping_deduplicated_action_non_zero_exit_code on Windows as it requires sandboxing"
    return
  fi

  mkdir rules
  touch rules/BUILD
  cat > rules/defs.bzl <<'EOF'
def _slow_rule_impl(ctx):
    out_file = ctx.actions.declare_file(ctx.attr.name + "_file")
    outs = [out_file]
    args = ctx.actions.args().add_all(outs)
    ctx.actions.run_shell(
         outputs = outs,
         command = """
         # Sleep to ensure that two actions are scheduled in parallel.
         sleep 3

         echo "Hello, stdout!"
         >&2 echo "Hello, stderr!"

         # Create the output file, but with a non-zero exit code.
         echo 'echo "Hello, file!"' > $1
         exit 1
         """,
         arguments = [args],
         execution_requirements = {"supports-path-mapping": ""},
    )
    return [
        DefaultInfo(files = depset(outs)),
    ]

slow_rule = rule(_slow_rule_impl)
EOF

  mkdir -p pkg
  cat > pkg/BUILD <<'EOF'
load("//rules:defs.bzl", "slow_rule")

slow_rule(name = "my_rule")

COMMAND = """
[[ $$($(execpath :my_rule)) == "Hello, file!" ]] || exit 1
touch $@
"""

genrule(
    name = "gen_exec",
    outs = ["out_exec"],
    cmd = COMMAND,
    tools = [":my_rule"],
)

genrule(
    name = "gen_target",
    outs = ["out_target"],
    cmd = COMMAND,
    srcs = [":my_rule"],
)
EOF

  bazel build \
    --experimental_output_paths=strip \
    --remote_cache=grpc://localhost:${worker_port} \
    --keep_going \
    //pkg:all &> $TEST_log && fail "build succeeded unexpectedly"
  # Failing actions are not deduplicated.
  expect_not_log '[0-9] deduplicated'

  expect_log 'Action pkg/my_rule_file failed:'
  expect_log 'Action pkg/my_rule_file \[for tool\] failed:'

  # The first execution emits stdout/stderr, the second doesn't.
  # stdout/stderr are emitted as part of the failing action error, not as an
  # info.
  expect_not_log 'INFO: From Action pkg/my_rule_file'
  expect_log_n 'Hello, stderr!' 2
  expect_log_n 'Hello, stdout!' 2
}

run_suite "path mapping tests"
