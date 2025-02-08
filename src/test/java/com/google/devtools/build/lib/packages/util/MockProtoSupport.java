// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages.util;

import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;
import static java.lang.Integer.MAX_VALUE;

import com.google.devtools.build.lib.rules.proto.ProtoConstants;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.IOException;

/**
 * Creates mock BUILD files required for the proto_library rule.
 */
public final class MockProtoSupport {
  private MockProtoSupport() {
    throw new UnsupportedOperationException();
  }

  /**
   * Setup the support for building proto_library. You additionally need to setup support for each
   * of the languages used in the specific test.
   *
   * <p>Cannot be used for integration tests that actually need to run protoc.
   */
  public static void setup(MockToolsConfig config) throws IOException {
    createNetProto2(config);
    setupWorkspace(config);
    registerProtoToolchain(config);
  }

  private static void registerProtoToolchain(MockToolsConfig config) throws IOException {
    config.append("MODULE.bazel", "register_toolchains('//tools/proto/toolchains:all')");
    config.create(
        "tools/proto/toolchains/BUILD",
        "load('@com_google_protobuf//bazel/toolchains:proto_toolchain.bzl', 'proto_toolchain')",
        TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
        "proto_toolchain(name = 'protoc_sources',"
            + "proto_compiler = '"
            + ProtoConstants.DEFAULT_PROTOC_LABEL
            + "')");
  }

  /**
   * Create a dummy net/proto2 compiler, a dummy protoc_minimal and proto APIs for all languages and
   * versions.
   */
  private static void createNetProto2(MockToolsConfig config) throws IOException {
    config.create(
        "net/proto2/compiler/public/BUILD",
        """
        load("//test_defs:foo_binary.bzl", "foo_binary")
        package(default_visibility = ["//visibility:public"])

        foo_binary(
            name = "protocol_compiler",
            srcs = ["protocol_compiler.sh"],
        )
        """);

    // TODO: b/305068148 - Remove this after blaze is released with protoc_minimal.
    config.create(
        "third_party/protobuf/compiler/release/BUILD",
        """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        package(default_visibility = ["//visibility:public"])

        foo_binary(
            name = "protoc_minimal",
            srcs = ["protoc_minimal.sh"],
        )
        """);

    if (config.isRealFileSystem()) {
      // when using a "real" file system, import the jars and link to ensure compilation
      config.create(
          "java/com/google/io/protocol/BUILD",
          """
          load("@rules_java//java:defs.bzl", "java_import")
          package(default_visibility = ["//visibility:public"])

          java_import(
              name = "protocol",
              jars = ["protocol.jar"],
          )
          """);
      config.create(
          "java/com/google/io/protocol2/BUILD",
          """
          load("@rules_java//java:defs.bzl", "java_import")
          package(default_visibility = ["//visibility:public"])

          java_import(
              name = "protocol2",
              jars = ["protocol2.jar"],
          )
          """);

      config.linkTool("javatests/com/google/devtools/build/lib/prepackaged_protocol_deploy.jar",
          "java/com/google/io/protocol/protocol.jar");
      config.linkTool("javatests/com/google/devtools/build/lib/prepackaged_protocol2_deploy.jar",
          "java/com/google/io/protocol2/protocol2.jar");
    } else {
      // for "fake" file systems, provide stub rules. This is different from the "real" filesystem,
      // as it produces the interface jars that the production environment has.
      config.create(
          "java/com/google/io/protocol/BUILD",
          """
          load("@rules_java//java:defs.bzl", "java_library")
          package(default_visibility = ["//visibility:public"])

          java_library(
              name = "protocol",
              srcs = ["Protocol.java"],
          )
          """);
      config.create("java/com/google/io/protocol/Protocol.java");
      config.create(
          "java/com/google/io/protocol2/BUILD",
          """
          load("@rules_java//java:defs.bzl", "java_library")
          package(default_visibility = ["//visibility:public"])

          java_library(
              name = "protocol2",
              srcs = ["Protocol2.java"],
          )
          """);
      config.create("java/com/google/io/protocol/Protocol2.java");
    }

    config.create(
        "java/com/google/protobuf/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        proto_library(
            name = "protobuf_proto_sources",
            srcs = [],
        )
        """);

    // RPC generator plugins.
    config.create(
        "net/rpc/compiler/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        cc_binary(
            name = "proto2_py_plugin",
            srcs = ["proto2_py_plugin.cc"],
        )

        cc_binary(
            name = "proto2_java_plugin",
            srcs = ["proto2_java_plugin.cc"],
        )
        """);

    config.create(
        "net/grpc/compiler/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        cc_binary(
            name = "composite_cc_plugin",
            srcs = ["composite_cc_plugin.cc"],
        )
        """);

    // Fake targets for proto API libs of all languages and versions.
    config.create(
        "net/proto2/public/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        cc_library(
            name = "cc_proto_library_blaze_internal_deps",
            srcs = ["cc_proto_library_blaze_internal_deps.cc"],
        )
        """);
    config.create(
        "net/proto2/python/public/BUILD",
        getPyLoad("py_library"),
        "package(default_visibility=['//visibility:public'])",
        "py_library(name = 'public',",
        "           srcs = [ 'pyproto2.py' ])");
    config.create(
        "net/proto2/bridge/public/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        cc_library(
            name = "compatibility_mode_support",
            srcs = ["compatibility.cc"],
        )
        """);
    config.create(
        "net/proto/BUILD",
        getPyLoad("py_library"),
        "package(default_visibility=['//visibility:public'])",
        "cc_library(name = 'proto',",
        "           srcs = [ 'proto.cc' ])",
        "py_library(name = 'pyproto',",
        "           srcs = [ 'pyproto.py' ])");
    config.create(
        "net/proto/python/BUILD",
        getPyLoad("py_library"),
        "package(default_visibility=['//visibility:public'])",
        "py_library(name = 'proto1',",
        "           srcs = [ 'pyproto.py' ])");
    config.create(
        "net/rpc/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        cc_library(name = "stubby12_proto_rpc_libs")
        """);
    config.create(
        "net/rpc4/public/core/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        cc_library(name = "stubby4_rpc_libs")
        """);
    config.create(
        "net/grpc/BUILD",
        """
        package(default_visibility = ["//visibility:public"])

        cc_library(
            name = "grpc++_codegen_lib",
            srcs = ["grpc++_codegen_lib.cc"],
        )
        """);
    config.create(
        "net/rpc/python/BUILD",
        getPyLoad("py_library"),
        "py_library(name = 'proto_python_api_2_stub',",
        "           srcs = [ 'test_only_prefix_proto_python_api_2_stub.py' ],",
        "           visibility = ['//visibility:public'],",
        ")");
    config.create(
        "java/com/google/net/rpc/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        package(default_visibility = ["//visibility:public"])

        java_library(
            name = "rpc",
            srcs = ["Rpc.java"],
        )

        java_library(
            name = "rpc_noloas_internal",
            srcs = ["RpcNoloas.java"],
        )
        """);
    config.create(
        "java/com/google/net/rpc3/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        package(default_visibility = ["//visibility:public"])

        java_library(
            name = "rpc3_proto_runtime",
            srcs = ["Rpc3.java"],
            deps = [":rpc3_noloas_internal"],
        )

        java_library(
            name = "rpc3_noloas_internal",
            srcs = ["Rpc3Noloas.java"],
            deps = ["//java/com/google/net/rpc:rpc_noloas_internal"],
        )
        """);
    config.create(
        "net/proto2/proto/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "package(default_visibility=['//visibility:public'])",
        "genrule(name = 'go_internal_bootstrap_hack',",
        "        srcs = [ 'descriptor.pb.go-prebuilt' ],",
        "        outs = [ 'descriptor.pb.go' ],",
        "        cmd = '')",
        "proto_library(name='descriptor',",
        "              srcs=['descriptor.proto'])");
    config.create(
        "net/proto2/go/BUILD",
        """
        load("//tools/build_defs/go:go_library.bzl", "go_library")
        package(default_visibility = ["//visibility:public"])

        go_library(
            name = "protodeps",
            srcs = ["protodeps.go"],
        )
        """);
    config.create(
        "net/proto2/compiler/go/BUILD",
        """
        load("//tools/build_defs/go:go_binary.bzl", "go_binary")
        package(default_visibility = ["//visibility:public"])

        go_binary(
            name = "protoc-gen-go",
            srcs = ["main.go"],
        )
        """);
    config.create(
        "net/rpc/go/BUILD",
        """
        load("//tools/build_defs/go:go_library.bzl", "go_library")
        package(default_visibility = ["//visibility:public"])

        go_library(
            name = "rpc",
            srcs = ["rpc.go"],
        )
        """);
    config.create(
        "go/context/BUILD",
        """
        load("//tools/build_defs/go:go_library.bzl", "go_library")
        package(default_visibility = ["//visibility:public"])

        go_library(
            name = "context",
            srcs = ["context.go"],
        )
        """);
    // TODO(b/77901188): remove once j_p_l migration is complete
    config.create(
        "third_party/java/jsr250_annotations/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        package(default_visibility = ["//visibility:public"])

        licenses(["notice"])

        java_library(
            name = "jsr250_source_annotations",
            srcs = ["Generated.java"],
        )
        """);
    config.create(
        "third_party/golang/grpc/metadata/BUILD",
        """
        load("//tools/build_defs/go:go_library.bzl", "go_library")
        package(default_visibility = ["//visibility:public"])

        licenses(["notice"])

        go_library(
            name = "metadata",
            srcs = ["metadata.go"],
        )
        """);
  }

  public static void setupWorkspace(MockToolsConfig config) throws IOException {
    // TODO - ilist@: Remove after Google proto_library doesn't depend on rules_proto
    config.create(
        "third_party/bazel_rules/rules_proto/proto/BUILD",
        """
        licenses(["notice"])

        toolchain_type(
            name = "toolchain_type",
            visibility = ["//visibility:public"],
        )
        """);
    if (TestConstants.PRODUCT_NAME.equals("bazel")) {
      Runfiles runfiles = Runfiles.preload().withSourceRepository("");
      PathFragment path =
          PathFragment.create(runfiles.rlocation("com_google_protobuf/bazel/BUILD.bazel"));
      config.copyDirectory(
          path.getParentDirectory(), "third_party/protobuf/bazel", MAX_VALUE, false);
      config.overwrite(
          "third_party/protobuf/BUILD",
          "filegroup(name = 'license')",
          "genrule(name='protoc_gen', cmd='', executable = True, outs = ['protoc'])");
      config.overwrite(
          "proto_bazel_features_workspace/MODULE.bazel", "module(name = 'proto_bazel_features')");
      // Overwritten to remove bazel7 toolchains from protobuf
      config.overwrite(
          "third_party/protobuf/bazel/private/toolchains/BUILD.bazel",
          "load('//bazel/toolchains:proto_toolchain.bzl', 'proto_toolchain')",
          TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
          "proto_toolchain(name = 'protoc_sources',"
              + "proto_compiler = '"
              + ProtoConstants.DEFAULT_PROTOC_LABEL
              + "')");
      config.overwrite("proto_bazel_features_workspace/BUILD");
      config.overwrite(
          "proto_bazel_features_workspace/features.bzl",
          "bazel_features = struct(",
          "  globals = struct(PackageSpecificationInfo = PackageSpecificationInfo),",
          "  proto = struct(starlark_proto_info = True),",
          "  cc = struct(protobuf_on_allowlist = True),",
          ")");
    }
  }
}
