// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util;


import com.google.devtools.build.lib.packages.util.MockToolsConfig;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.function.Function;

public abstract class AbstractMockJavaSupport {

  public static final AbstractMockJavaSupport BAZEL =
      new AbstractMockJavaSupport() {
        @Override
        public void setupRulesJava(
            MockToolsConfig config, Function<String, String> runfilesResolver) throws IOException {
          config.create("rules_java_workspace/MODULE.bazel", "module(name = 'rules_java')");
          PathFragment rulesJavaRoot =
              PathFragment.create(runfilesResolver.apply("rules_java/java/defs.bzl"))
                  .getParentDirectory()
                  .getParentDirectory();
          config.copyDirectory(
              rulesJavaRoot.getRelative("java"),
              "rules_java_workspace/java",
              Integer.MAX_VALUE,
              true);
          config.copyTool(
              rulesJavaRoot.getRelative("toolchains/java_toolchain_alias.bzl"),
              "rules_java_workspace/toolchains/java_toolchain_alias.bzl");
          // Overwrite redirects to not have to use bazel_features / compatibility layer
          config.overwrite(
              "rules_java_workspace/java/java_binary.bzl",
              """
load("@rules_java//java/bazel/rules:bazel_java_binary_wrapper.bzl", _java_binary = "java_binary")
java_binary = _java_binary
""");
          config.overwrite(
              "rules_java_workspace/java/java_import.bzl",
              """
load("@rules_java//java/bazel/rules:bazel_java_import.bzl", _java_import = "java_import")
java_import = _java_import
""");
          config.overwrite(
              "rules_java_workspace/java/java_library.bzl",
              """
load("@rules_java//java/bazel/rules:bazel_java_library.bzl", _java_library = "java_library")
java_library = _java_library
""");
          config.overwrite(
              "rules_java_workspace/java/java_plugin.bzl",
              """
load("@rules_java//java/bazel/rules:bazel_java_plugin.bzl", _java_plugin = "java_plugin")
java_plugin = _java_plugin
""");
          config.overwrite(
              "rules_java_workspace/java/java_test.bzl",
              """
              load("@rules_java//java/bazel/rules:bazel_java_test.bzl", _java_test = "java_test")
              java_test = _java_test
              """);
          config.overwrite(
              "rules_java_workspace/java/toolchains/java_package_configuration.bzl",
              """
              load("@rules_java//java/common/rules:java_package_configuration.bzl",
                _java_package_configuration = "java_package_configuration")
              java_package_configuration = _java_package_configuration
              """);
          config.overwrite(
              "rules_java_workspace/java/toolchains/java_runtime.bzl",
              """
              load("@rules_java//java/common/rules:java_runtime.bzl",
                _java_runtime = "java_runtime")
              java_runtime = _java_runtime
              """);
          config.overwrite(
              "rules_java_workspace/java/toolchains/java_toolchain.bzl",
              """
              load("@rules_java//java/common/rules:java_toolchain.bzl",
                _java_toolchain = "java_toolchain")
              java_toolchain = _java_toolchain
              """);
          config.overwrite(
              "rules_java_workspace/java/common/java_common.bzl",
              """
              load("@rules_java//java/private:java_common.bzl", _java_common = "java_common")
              java_common = _java_common
              """);
          config.overwrite(
              "rules_java_workspace/java/common/java_info.bzl",
              """
              load("@rules_java//java/private:java_info.bzl", _JavaInfo = "JavaInfo")
              JavaInfo = _JavaInfo
              """);
          config.overwrite(
              "rules_java_workspace/java/common/java_plugin_info.bzl",
              """
              load("@rules_java//java/private:java_info.bzl", _JavaPluginInfo = "JavaPluginInfo")
              JavaPluginInfo = _JavaPluginInfo
              """);
          config.overwrite(
              "rules_java_workspace/java/private/proto_support.bzl",
              """
load("@rules_java//java/private:java_common.bzl", "java_common")
def compile(*, injecting_rule_kind, enable_jspecify, include_compilation_info, **kwargs):
    return java_common.compile(**kwargs)
def merge(providers, *, merge_java_outputs = True, merge_source_jars = True):
    return java_common.merge(providers)
""");
          // mocks
          config.create("rules_java_workspace/toolchains/BUILD");
          config.create(
              "rules_java_workspace/toolchains/local_java_repository.bzl",
              """
              def local_java_repository(**attrs):
                  pass
              """);
          config.create(
              "rules_java_workspace/toolchains/jdk_build_file.bzl", "JDK_BUILD_TEMPLATE = ''");
          config.overwrite(
              "rules_java_workspace/java/repositories.bzl",
              """
              def rules_java_dependencies():
                  pass

              def rules_java_toolchains():
                  native.register_toolchains("//java/toolchains/runtime:all")
                  native.register_toolchains("//java/toolchains/javac:all")
              """);

          config.overwrite(
              "rules_java_workspace/java/toolchains/runtime/BUILD",
              """
              toolchain_type(name = "toolchain_type")

              toolchain(
                  name = "local_jdk",
                  toolchain = "@bazel_tools//tools/jdk:jdk",
                  toolchain_type = "@rules_java//java/toolchains/runtime:toolchain_type",
              )
              """);
          config.overwrite(
              "rules_java_workspace/java/toolchains/javac/BUILD",
              """
              toolchain_type(name = "toolchain_type")

              toolchain(
                  name = "javac_toolchain",
                  toolchain = "@bazel_tools//tools/jdk:toolchain",
                  toolchain_type = "@rules_java//java/toolchains/javac:toolchain_type",
              )
              """);
        }

        @Override
        public String getLoadStatementForRule(String ruleName) {
          return "load('@rules_java//java:" + ruleName + ".bzl', '" + ruleName + "')";
        }
      };

  public abstract void setupRulesJava(
      MockToolsConfig mockToolsConfig, Function<String, String> runfilesResolver)
      throws IOException;

  public abstract String getLoadStatementForRule(String ruleName);
}
