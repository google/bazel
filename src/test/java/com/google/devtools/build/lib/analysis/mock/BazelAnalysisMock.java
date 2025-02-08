// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.mock;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.lang.Short.MAX_VALUE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.MoreFiles;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ShellConfiguration;
import com.google.devtools.build.lib.analysis.util.AbstractMockJavaSupport;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.BazelRepositoryModule;
import com.google.devtools.build.lib.bazel.bzlmod.LocalPathRepoSpecs;
import com.google.devtools.build.lib.bazel.bzlmod.NonRegistryOverride;
import com.google.devtools.build.lib.bazel.rules.BazelRuleClassProvider;
import com.google.devtools.build.lib.packages.util.BazelMockCcSupport;
import com.google.devtools.build.lib.packages.util.BazelMockPythonSupport;
import com.google.devtools.build.lib.packages.util.MockCcSupport;
import com.google.devtools.build.lib.packages.util.MockGenruleSupport;
import com.google.devtools.build.lib.packages.util.MockPlatformSupport;
import com.google.devtools.build.lib.packages.util.MockProtoSupport;
import com.google.devtools.build.lib.packages.util.MockPythonSupport;
import com.google.devtools.build.lib.packages.util.MockToolsConfig;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

/** Subclass of {@link AnalysisMock} using Bazel-specific semantics. */
public final class BazelAnalysisMock extends AnalysisMock {
  public static final AnalysisMock INSTANCE = new BazelAnalysisMock();

  private BazelAnalysisMock() {}

  @Override
  public void setupMockClientInternal(MockToolsConfig config) throws IOException {
    config.create("local_config_xcode_workspace/BUILD", "xcode_config(name = 'host_xcodes')");
    config.create(
        "local_config_xcode_workspace/MODULE.bazel", "module(name = 'local_config_xcode')");
    config.create("third_party/protobuf/BUILD");
    config.create("third_party/protobuf/MODULE.bazel", "module(name='com_google_protobuf')");
    config.overwrite(
        "MODULE.bazel",
        "register_toolchains('@rules_java//java/toolchains/runtime:all')",
        "register_toolchains('@rules_java//java/toolchains/javac:all')",
        "register_toolchains('@bazel_tools//tools/cpp:all')",
        "register_toolchains('@bazel_tools//tools/jdk:all')",
        "register_toolchains('@bazel_tools//tools/python:autodetecting_toolchain')");
    /* The rest of platforms is initialized in {@link MockPlatformSupport}. */
    config.create("platforms_workspace/MODULE.bazel", "module(name = 'platforms')");
    config.create(
        "local_config_platform_workspace/MODULE.bazel", "module(name = 'local_config_platform')");
    config.create(
        "build_bazel_apple_support/MODULE.bazel", "module(name = 'build_bazel_apple_support')");
    config.create(
        "third_party/bazel_rules/rules_shell/MODULE.bazel", "module(name = 'rules_shell')");

    // TODO: remove after figuring out https://github.com/bazelbuild/bazel/issues/22208
    config.create(
        ".bazelignore",
        "embedded_tools",
        "platforms_workspace",
        "local_config_platform_workspace",
        "rules_java_workspace",
        "rules_python_workspace",
        "third_party/protobuf",
        "proto_bazel_features_workspace",
        "build_bazel_apple_support",
        "local_config_xcode_workspace",
        "third_party/bazel_rules/rules_cc",
        "third_party/bazel_rules/rules_shell");

    Runfiles runfiles = Runfiles.preload().withSourceRepository("");
    for (String filename : Arrays.asList("tools/jdk/java_toolchain_alias.bzl")) {
      java.nio.file.Path path = Paths.get(runfiles.rlocation("io_bazel/" + filename));
      if (!Files.exists(path)) {
        continue; // the io_bazel workspace root only exists for Bazel
      }
      config.create("embedded_tools/" + filename, MoreFiles.asCharSource(path, UTF_8).read());
    }
    config.create(
        "embedded_tools/tools/jdk/launcher_flag_alias.bzl",
        """
        _providers = [CcInfo, cc_common.launcher_provider]

        def _impl(ctx):
            if not ctx.attr._launcher:
                return None
            launcher = ctx.attr._launcher
            providers = [ctx.attr._launcher[p] for p in _providers]
            providers.append(DefaultInfo(
                files = launcher[DefaultInfo].files,
                runfiles = launcher[DefaultInfo].default_runfiles,
            ))
            return providers

        launcher_flag_alias = rule(
            implementation = _impl,
            attrs = {
                "_launcher": attr.label(
                    default = configuration_field(
                        fragment = "java",
                        name = "launcher",
                    ),
                    providers = _providers,
                ),
            },
        )
        """);
    config.create(
        "embedded_tools/tools/jdk/BUILD",
        """
load("@rules_java//java:defs.bzl",
  "java_binary", "java_import", "java_toolchain", "java_runtime")
load(
    ":java_toolchain_alias.bzl",
    "java_host_runtime_alias",
    "java_runtime_alias",
    "java_toolchain_alias",
)
load(":launcher_flag_alias.bzl", "launcher_flag_alias")

package(default_visibility = ["//visibility:public"])

java_toolchain(
    name = "toolchain",
    bootclasspath = [":bootclasspath"],
    genclass = ["GenClass_deploy.jar"],
    header_compiler = ["turbine_deploy.jar"],
    header_compiler_direct = ["TurbineDirect_deploy.jar"],
    ijar = ["ijar"],
    jacocorunner = ":JacocoCoverage",
    java_runtime = "host_jdk",
    javabuilder = ["JavaBuilder_deploy.jar"],
    singlejar = ["singlejar"],
    source_version = "8",
    target_version = "8",
)

java_toolchain(
    name = "remote_toolchain",
    bootclasspath = [":bootclasspath"],
    genclass = ["GenClass_deploy.jar"],
    header_compiler = ["turbine_deploy.jar"],
    header_compiler_direct = ["TurbineDirect_deploy.jar"],
    ijar = ["ijar"],
    jacocorunner = ":JacocoCoverage",
    java_runtime = "host_jdk",
    javabuilder = ["JavaBuilder_deploy.jar"],
    singlejar = ["singlejar"],
    source_version = "8",
    target_version = "8",
)

java_import(
    name = "JacocoCoverageRunner",
    jars = ["JacocoCoverage_jarjar_deploy.jar"],
)

java_import(
    name = "proguard_import",
    jars = ["proguard_rt.jar"],
)

java_binary(
    name = "proguard",
    main_class = "proguard.Proguard",
    runtime_deps = [":proguard_import"],
)

java_import(
    name = "TestRunner",
    jars = ["TestRunner.jar"],
)

java_runtime(
    name = "jdk",
    srcs = [],
)

java_runtime(
    name = "host_jdk",
    srcs = [],
)

java_runtime(
    name = "remote_jdk11",
    srcs = [],
)

java_toolchain_alias(name = "current_java_toolchain")

java_runtime_alias(name = "current_java_runtime")

java_host_runtime_alias(name = "current_host_java_runtime")

filegroup(
    name = "bootclasspath",
    srcs = ["jdk/jre/lib/rt.jar"],
)

filegroup(
    name = "extdir",
    srcs = glob(
        ["jdk/jre/lib/ext/*"],
        allow_empty = True,
    ),
)

filegroup(
    name = "java",
    srcs = ["jdk/jre/bin/java"],
)

filegroup(
    name = "JacocoCoverage",
    srcs = ["JacocoCoverage_deploy.jar"],
)

exports_files([
    "JavaBuilder_deploy.jar",
    "singlejar",
    "TestRunner_deploy.jar",
    "ijar",
    "GenClass_deploy.jar",
    "turbine_deploy.jar",
    "TurbineDirect_deploy.jar",
    "proguard_allowlister.par",
])

toolchain_type(name = "toolchain_type")

toolchain_type(name = "runtime_toolchain_type")

toolchain(
    name = "dummy_java_toolchain",
    toolchain = ":toolchain",
    toolchain_type = ":toolchain_type",
)

toolchain(
    name = "dummy_java_runtime_toolchain",
    toolchain = ":jdk",
    toolchain_type = ":runtime_toolchain_type",
)

java_plugins_flag_alias(name = "java_plugins_flag_alias")

launcher_flag_alias(
    name = "launcher_flag_alias",
    visibility = ["//visibility:public"],
)
""");

    config.create(
        TestConstants.CONSTRAINTS_PATH + "/android/BUILD",
        "package(default_visibility=['//visibility:public'])",
        "platform(",
        "  name = 'armeabi-v7a',",
        "  parents = ['" + TestConstants.PLATFORM_LABEL + "'],",
        "  constraint_values = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:android',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:armv7',",
        "  ],",
        ")");

    // Create the actual SDKs.
    config.create(
        "embedded_tools/src/tools/android/java/com/google/devtools/build/android/r8/BUILD",
        """
        filegroup(name='r8', srcs = [])
        """);
    config.create(
        "android_gmaven_r8/jar/BUILD",
        """
        filegroup(name = "jar", srcs = ["r8.jar"])
        filegroup(name = "file", srcs = [])
        """);
    config.create("android_gmaven_r8/REPO.bazel");

    MockGenruleSupport.setup(config);

    config.create(
        "embedded_tools/tools/BUILD",
        "alias(name='host_platform',actual='" + TestConstants.PLATFORM_LABEL + "')");
    config.create(
        "embedded_tools/tools/test/BUILD",
        """
        filegroup(
            name = "runtime",
            srcs = [
                "test-setup.sh",
                "test-xml-generator.sh",
            ],
        )

        filegroup(
            name = "test_wrapper",
            srcs = ["test_wrapper_bin"],
        )

        filegroup(
            name = "xml_writer",
            srcs = ["xml_writer_bin"],
        )

        filegroup(
            name = "test_setup",
            srcs = ["test-setup.sh"],
        )

        filegroup(
            name = "test_xml_generator",
            srcs = ["test-xml-generator.sh"],
        )

        filegroup(
            name = "collect_coverage",
            srcs = ["collect_coverage.sh"],
        )

        filegroup(
            name = "collect_cc_coverage",
            srcs = ["collect_cc_coverage.sh"],
        )

        filegroup(
            name = "coverage_support",
            srcs = ["collect_coverage.sh"],
        )

        filegroup(
            name = "coverage_report_generator",
            srcs = ["coverage_report_generator.sh"],
        )

        filegroup(
            name = "lcov_merger",
            srcs = ["lcov_merger.sh"],
        )
        """);

    // Create fake, minimal implementations of test-setup.sh and test-xml-generator.sh for test
    // cases that actually execute tests. Does not support coverage, interruption, signals, etc.
    // For proper test execution support, the actual test-setup.sh will need to be included in the
    // Java test's runfiles and copied/symlinked into the MockToolsConfig's workspace.
    config
        .create(
            "embedded_tools/tools/test/test-setup.sh",
            """
            #!/bin/bash
            set -e
            function is_absolute {
              [[ "$1" = /* ]] || [[ "$1" =~ ^[a-zA-Z]:[/\\].* ]]
            }
            is_absolute "$TEST_SRCDIR" || TEST_SRCDIR="$PWD/$TEST_SRCDIR"
            RUNFILES_MANIFEST_FILE="${TEST_SRCDIR}/MANIFEST"
            cd ${TEST_SRCDIR}
            function rlocation() {
              if is_absolute "$1" ; then
                # If the file path is already fully specified, simply return it.
                echo "$1"
              elif [[ -e "$TEST_SRCDIR/$1" ]]; then
                # If the file exists in the $TEST_SRCDIR then just use it.
                echo "$TEST_SRCDIR/$1"
              elif [[ -e "$RUNFILES_MANIFEST_FILE" ]]; then
                # If a runfiles manifest file exists then use it.
                echo "$(grep "^$1 " "$RUNFILES_MANIFEST_FILE" | sed 's/[^ ]* //')"
              fi
            }

            EXE="${1#./}"
            shift

            if is_absolute "$EXE"; then
              TEST_PATH="$EXE"
            else
              TEST_PATH="$(rlocation $TEST_WORKSPACE/$EXE)"
            fi
            exec $TEST_PATH
            """)
        .chmod(0755);
    config
        .create("embedded_tools/tools/test/test-xml-generator.sh", "#!/bin/sh", "cp \"$1\" \"$2\"")
        .chmod(0755);

    // Use an alias package group to allow for modification at the simpler path
    config.create(
        "embedded_tools/tools/allowlists/config_feature_flag/BUILD",
        """
        package_group(
            name = "config_feature_flag",
            includes = ["@@//tools/allowlists/config_feature_flag"],
        )

        package_group(
            name = "config_feature_flag_setter",
            includes = ["@@//tools/allowlists/config_feature_flag:config_feature_flag_setter"],
        )
        """);

    config.create(
        "tools/allowlists/config_feature_flag/BUILD",
        """
        package_group(
            name = "config_feature_flag",
            packages = ["public"],
        )

        package_group(
            name = "config_feature_flag_Setter",
            packages = ["public"],
        )
        """);
    config.create(
        "embedded_tools/tools/allowlists/initializer_allowlist/BUILD",
        """
        package_group(
            name = "initializer_allowlist",
            packages = [],
        )
        """);
    config.create(
        "embedded_tools/tools/allowlists/extend_rule_allowlist/BUILD",
        """
        package_group(
            name = "extend_rule_allowlist",
            packages = ["public"],
        )
        package_group(
            name = "extend_rule_api_allowlist",
            packages = [],
        )
        """);
    config.create(
        "embedded_tools/tools/allowlists/subrules_allowlist/BUILD",
        """
        package_group(
            name = "subrules_allowlist",
            packages = [],
        )
        """);

    config.create(
        "embedded_tools/tools/allowlists/android_binary_allowlist/BUILD",
        """
        package_group(
            name = "enable_starlark_dex_desugar_proguard",
            includes = [
            "@@//tools/allowlists/android_binary_allowlist:enable_starlark_dex_desugar_proguard",
            ],
        )
        """);
    config.create(
        "tools/allowlists/android_binary_allowlist/BUILD",
        "package_group(name='enable_starlark_dex_desugar_proguard', packages=[])");

    config.create(
        "embedded_tools/tools/proto/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        alias(
            name = "protoc",
            actual = "@com_google_protobuf//:protoc",
        )

        alias(
            name = "javalite_toolchain",
            actual = "@com_google_protobuf//:javalite_toolchain",
        )

        alias(
            name = "java_toolchain",
            actual = "@com_google_protobuf//:java_toolchain",
        )

        alias(
            name = "cc_toolchain",
            actual = "@com_google_protobuf//:cc_toolchain",
        )
        """);

    config.create(
        "embedded_tools/tools/zip/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        exports_files(["precompile.py"])

        cc_binary(
            name = "zipper",
            srcs = ["zip_main.cc"],
        )

        alias(
            name = "unzip_fdo",
            actual = ":zipper",
        )
        """);

    config.create(
        "embedded_tools/tools/launcher/BUILD",
        """
        load("@bazel_tools//third_party/cc_rules/macros:defs.bzl", "cc_binary")

        package(default_visibility = ["//visibility:public"])

        cc_binary(
            name = "launcher",
            srcs = ["launcher_main.cc"],
        )

        cc_binary(
            name = "launcher_maker",
            srcs = ["launcher_maker.cc"],
        )
        """);

    config.create(
        "embedded_tools/tools/def_parser/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        filegroup(
            name = "def_parser",
            srcs = ["def_parser.exe"],
        )
        """);

    config.create(
        "embedded_tools/objcproto/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        objc_library(
            name = "protobuf_lib",
            srcs = ["empty.m"],
            hdrs = ["include/header.h"],
            includes = ["include"],
        )

        exports_files(["well_known_type.proto"])

        proto_library(
            name = "well_known_type_proto",
                srcs = ["well_known_type.proto"],
            )
        """);
    config.create("embedded_tools/objcproto/empty.m");
    config.create("embedded_tools/objcproto/empty.cc");
    config.create("embedded_tools/objcproto/well_known_type.proto");

    // Copies bazel_skylib from real @bazel_skylib (needed by rules_python)
    PathFragment path = PathFragment.create(runfiles.rlocation("bazel_skylib/BUILD"));
    config.copyDirectory(path.getParentDirectory(), "bazel_skylib_workspace", MAX_VALUE, true);
    config.overwrite("bazel_skylib_workspace/MODULE.bazel", "module(name = 'bazel_skylib')");

    config.create(
        "embedded_tools/tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = "function_transition_allowlist",
            packages = ["public"],
        )
        """);

    config.create(
        "embedded_tools/tools/allowlists/dormant_dependency_allowlist/BUILD",
        """
        package_group(
            name = "dormant_dependency_allowlist",
            packages = ["public"],
        )
        """);
    MockProtoSupport.setupWorkspace(config);
    MockPlatformSupport.setup(config);
    ccSupport().setup(config);
    javaSupport().setupRulesJava(config, runfiles::rlocation);
    pySupport().setup(config);
    ShellConfiguration.injectShellExecutableFinder(
        BazelRuleClassProvider::getDefaultPathFromOptions, BazelRuleClassProvider.SHELL_EXECUTABLE);
  }

  @Override
  public void setupMockToolsRepository(MockToolsConfig config) throws IOException {
    config.create("embedded_tools/MODULE.bazel", "module(name='bazel_tools')");
    config.create("embedded_tools/tools/build_defs/repo/BUILD");
    config.create(
        "embedded_tools/tools/build_defs/build_info/bazel_cc_build_info.bzl",
        """
        def _impl(ctx):
            volatile_file = ctx.actions.declare_file("volatile_file.h")
            non_volatile_file = ctx.actions.declare_file("non_volatile_file.h")
            redacted_file = ctx.actions.declare_file("redacted_file.h")
            ctx.actions.write(output = volatile_file, content = "")
            ctx.actions.write(output = non_volatile_file, content = "")
            ctx.actions.write(output = redacted_file, content = "")
            output_groups = {
                "non_redacted_build_info_files": depset([volatile_file, non_volatile_file]),
                "redacted_build_info_files": depset([redacted_file]),
            }
            return OutputGroupInfo(**output_groups)

        bazel_cc_build_info = rule(implementation = _impl)
        """);
    config.create(
        "embedded_tools/tools/build_defs/build_info/bazel_java_build_info.bzl",
        """
        def _impl(ctx):
            volatile_file = ctx.actions.declare_file("volatile_file.properties")
            non_volatile_file = ctx.actions.declare_file("non_volatile_file.properties")
            redacted_file = ctx.actions.declare_file("redacted_file.properties")
            ctx.actions.write(output = volatile_file, content = "")
            ctx.actions.write(output = non_volatile_file, content = "")
            ctx.actions.write(output = redacted_file, content = "")
            output_groups = {
                "non_redacted_build_info_files": depset([volatile_file, non_volatile_file]),
                "redacted_build_info_files": depset([redacted_file]),
            }
            return OutputGroupInfo(**output_groups)

        bazel_java_build_info = rule(implementation = _impl)
        """);
    config.create(
        "embedded_tools/tools/build_defs/build_info/BUILD",
        """
        load("//tools/build_defs/build_info:bazel_cc_build_info.bzl", "bazel_cc_build_info")
        load("//tools/build_defs/build_info:bazel_java_build_info.bzl", "bazel_java_build_info")

        bazel_cc_build_info(
            name = "cc_build_info",
            visibility = ["//visibility:public"],
        )

        bazel_java_build_info(
            name = "java_build_info",
            visibility = ["//visibility:public"],
        )
        """);
    config.create(
        "embedded_tools/tools/build_defs/repo/utils.bzl",
        """
        def maybe(repo_rule, name, **kwargs):
            if name not in native.existing_rules():
                repo_rule(name = name, **kwargs)
        """);
    config.create(
        "embedded_tools/tools/build_defs/repo/http.bzl",
        """
        def http_archive(**kwargs):
            pass

        def http_file(**kwargs):
            pass

        def http_jar(**kwargs):
            pass
        """);
    config.create(
        "embedded_tools/tools/build_defs/repo/local.bzl",
        """
        def _local_repository_impl(rctx):
          path = rctx.workspace_root.get_child(rctx.attr.path)
          rctx.symlink(path, ".")
        local_repository = repository_rule(
          implementation = _local_repository_impl,
          attrs = {"path": attr.string()},
        )

        def new_local_repository(**kwargs):
            pass
        """);
    config.create("embedded_tools/tools/jdk/jdk_build_file.bzl", "JDK_BUILD_TEMPLATE = ''");
    config.create(
        "embedded_tools/tools/jdk/local_java_repository.bzl",
        """
        def local_java_repository(**kwargs):
            pass
        """);
    config.create(
        "embedded_tools/tools/jdk/remote_java_repository.bzl",
        """
        def remote_java_repository(**kwargs):
            pass
        """);
    config.create(
        "embedded_tools/tools/cpp/cc_configure.bzl",
        """
        def cc_configure(**kwargs):
            pass
        """);

    config.create("embedded_tools/tools/sh/BUILD");
    config.create("embedded_tools/tools/osx/BUILD");
    config.create(
        "embedded_tools/tools/osx/xcode_configure.bzl",
        """
        # no positional arguments for XCode
        def xcode_configure(*args, **kwargs):
            pass
        """);
    config.create("embedded_tools/bin/sh", "def sh(**kwargs):", "  pass");
  }

  @Override
  public ImmutableMap<String, NonRegistryOverride> getBuiltinModules(BlazeDirectories directories) {
    ImmutableMap<String, String> moduleNameToPath =
        ImmutableMap.<String, String>builder()
            .put("bazel_tools", "embedded_tools")
            .put("platforms", "platforms_workspace")
            .put("local_config_platform", "local_config_platform_workspace")
            .put("rules_java", "rules_java_workspace")
            .put("rules_python", "rules_python_workspace")
            .put("rules_python_internal", "rules_python_internal_workspace")
            .put("bazel_skylib", "bazel_skylib_workspace")
            .put(
                "com_google_protobuf",
                "third_party/protobuf") // for WORKSPACE compatibility use com_google_protobuf
            .put("proto_bazel_features", "proto_bazel_features_workspace")
            .put("build_bazel_apple_support", "build_bazel_apple_support")
            .put("local_config_xcode", "local_config_xcode_workspace")
            .put("rules_cc", "third_party/bazel_rules/rules_cc")
            .put("rules_shell", "third_party/bazel_rules/rules_shell")
            .buildOrThrow();
    return moduleNameToPath.entrySet().stream()
        .collect(
            toImmutableMap(
                Map.Entry::getKey,
                e ->
                    new NonRegistryOverride(
                        LocalPathRepoSpecs.create(
                            directories
                                .getWorkingDirectory()
                                .getRelative(e.getValue())
                                .getPathString()))));
  }

  @Override
  public void setupPrelude(MockToolsConfig mockToolsConfig) {}

  @Override
  public boolean isThisBazel() {
    return true;
  }

  @Override
  public MockCcSupport ccSupport() {
    return BazelMockCcSupport.INSTANCE;
  }

  @Override
  public AbstractMockJavaSupport javaSupport() {
    return AbstractMockJavaSupport.BAZEL;
  }

  @Override
  public MockPythonSupport pySupport() {
    return BazelMockPythonSupport.INSTANCE;
  }

  @Override
  public BlazeModule getBazelRepositoryModule(BlazeDirectories directories) {
    return new BazelRepositoryModule(getBuiltinModules(directories));
  }
}
