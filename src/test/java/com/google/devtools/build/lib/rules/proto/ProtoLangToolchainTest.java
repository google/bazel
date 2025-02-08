// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.proto;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.util.MockProtoSupport;
import com.google.devtools.build.lib.testutil.TestConstants;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@code proto_lang_toolchain}. */
@RunWith(JUnit4.class)
public class ProtoLangToolchainTest extends BuildViewTestCase {
  @Before
  public void setUp() throws Exception {
    MockProtoSupport.setup(mockToolsConfig);
    useConfiguration("--protocopt=--myflag");
    invalidatePackages();
  }

  private void validateProtoLangToolchain(ProtoLangToolchainProvider toolchain) throws Exception {
    assertThat(toolchain.outReplacementFormatFlag()).isEqualTo("cmd-line:%s");
    assertThat(toolchain.pluginFormatFlag()).isEqualTo("--plugin=%s");
    assertThat(toolchain.pluginExecutable().getExecutable().getRootRelativePathString())
        .isEqualTo("third_party/x/plugin");

    TransitiveInfoCollection runtimes = toolchain.runtime();
    assertThat(runtimes.getLabel()).isEqualTo(Label.parseCanonical("//third_party/x:runtime"));

    assertThat(toolchain.protocOpts()).containsExactly("--myflag");

    assertThat(toolchain.progressMessage()).isEqualTo("Progress Message %{label}");
    assertThat(toolchain.mnemonic()).isEqualTo("MyMnemonic");
  }

  private void validateProtoCompiler(ProtoLangToolchainProvider toolchain, String protocLabel)
      throws Exception {
    Label actualProtocLabel = getConfiguredTarget(protocLabel).getActual().getLabel();
    assertThat(toolchain.protoc().getExecutable().prettyPrint())
        .isEqualTo(
            actualProtocLabel
                .getRepository()
                .getExecPath(false)
                .getRelative(actualProtocLabel.toPathFragment())
                .getPathString());
  }

  @Test
  public void protoToolchain() throws Exception {
    scratch.file(
        "third_party/x/BUILD",
        """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        licenses(["unencumbered"])

        cc_binary(
            name = "plugin",
            srcs = ["plugin.cc"],
        )

        cc_library(
            name = "runtime",
            srcs = ["runtime.cc"],
        )

        filegroup(
            name = "descriptors",
            srcs = [
                "descriptor.proto",
                "metadata.proto",
            ],
        )

        filegroup(
            name = "any",
            srcs = ["any.proto"],
        )

        proto_library(
            name = "denied",
            srcs = [
                ":any",
                ":descriptors",
            ],
        )
        """);

    scratch.file(
        "foo/BUILD",
        TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
        "licenses(['unencumbered'])",
        "proto_lang_toolchain(",
        "    name = 'toolchain',",
        "    command_line = 'cmd-line:$(OUT)',",
        "    plugin_format_flag = '--plugin=%s',",
        "    plugin = '//third_party/x:plugin',",
        "    runtime = '//third_party/x:runtime',",
        "    progress_message = 'Progress Message %{label}',",
        "    mnemonic = 'MyMnemonic',",
        ")");

    update(ImmutableList.of("//foo:toolchain"), false, 1, true, new EventBus());
    ProtoLangToolchainProvider toolchain =
        ProtoLangToolchainProvider.get(getConfiguredTarget("//foo:toolchain"));

    validateProtoLangToolchain(toolchain);
    validateProtoCompiler(toolchain, ProtoConstants.DEFAULT_PROTOC_LABEL);
  }

  @Test
  public void protoToolchainResolution_enabled() throws Exception {
    setBuildLanguageOptions("--incompatible_enable_proto_toolchain_resolution");
    scratch.file(
        "third_party/x/BUILD",
        """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        licenses(["unencumbered"])

        cc_binary(
            name = "plugin",
            srcs = ["plugin.cc"],
        )

        cc_library(
            name = "runtime",
            srcs = ["runtime.cc"],
        )

        filegroup(
            name = "descriptors",
            srcs = [
                "descriptor.proto",
                "metadata.proto",
            ],
        )

        filegroup(
            name = "any",
            srcs = ["any.proto"],
        )

        proto_library(
            name = "denied",
            srcs = [
                ":any",
                ":descriptors",
            ],
        )
        """);
    scratch.file(
        "foo/BUILD",
        TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
        "licenses(['unencumbered'])",
        "proto_lang_toolchain(",
        "    name = 'toolchain',",
        "    command_line = 'cmd-line:$(OUT)',",
        "    plugin_format_flag = '--plugin=%s',",
        "    plugin = '//third_party/x:plugin',",
        "    runtime = '//third_party/x:runtime',",
        "    progress_message = 'Progress Message %{label}',",
        "    mnemonic = 'MyMnemonic',",
        ")");

    update(ImmutableList.of("//foo:toolchain"), false, 1, true, new EventBus());
    ProtoLangToolchainProvider toolchain =
        ProtoLangToolchainProvider.get(getConfiguredTarget("//foo:toolchain"));

    validateProtoLangToolchain(toolchain);
    validateProtoCompiler(toolchain, ProtoConstants.DEFAULT_PROTOC_LABEL);
  }

  @Test
  public void protoToolchainBlacklistProtoLibraries() throws Exception {
    scratch.file(
        "third_party/x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "licenses(['unencumbered'])",
        "cc_binary(name = 'plugin', srcs = ['plugin.cc'])",
        "cc_library(name = 'runtime', srcs = ['runtime.cc'])",
        "proto_library(name = 'descriptors', srcs = ['metadata.proto', 'descriptor.proto'])",
        "proto_library(name = 'any', srcs = ['any.proto'], strip_import_prefix = '/third_party')");

    scratch.file(
        "foo/BUILD",
        TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
        "proto_lang_toolchain(",
        "    name = 'toolchain',",
        "    command_line = 'cmd-line:$(OUT)',",
        "    plugin_format_flag = '--plugin=%s',",
        "    plugin = '//third_party/x:plugin',",
        "    runtime = '//third_party/x:runtime',",
        "    progress_message = 'Progress Message %{label}',",
        "    mnemonic = 'MyMnemonic',",
        ")");

    update(ImmutableList.of("//foo:toolchain"), false, 1, true, new EventBus());
    ProtoLangToolchainProvider toolchain =
        ProtoLangToolchainProvider.get(getConfiguredTarget("//foo:toolchain"));

    validateProtoLangToolchain(toolchain);
    validateProtoCompiler(toolchain, ProtoConstants.DEFAULT_PROTOC_LABEL);
  }

  @Test
  public void protoToolchainBlacklistTransitiveProtos() throws Exception {
    scratch.file(
        "third_party/x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "licenses(['unencumbered'])",
        "cc_binary(name = 'plugin', srcs = ['plugin.cc'])",
        "cc_library(name = 'runtime', srcs = ['runtime.cc'])",
        "proto_library(name = 'descriptors', srcs = ['metadata.proto', 'descriptor.proto'])",
        "proto_library(name = 'any', srcs = ['any.proto'], deps = [':descriptors'])");

    scratch.file(
        "foo/BUILD",
        TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
        "proto_lang_toolchain(",
        "    name = 'toolchain',",
        "    command_line = 'cmd-line:$(OUT)',",
        "    plugin_format_flag = '--plugin=%s',",
        "    plugin = '//third_party/x:plugin',",
        "    runtime = '//third_party/x:runtime',",
        "    progress_message = 'Progress Message %{label}',",
        "    mnemonic = 'MyMnemonic',",
        ")");

    update(ImmutableList.of("//foo:toolchain"), false, 1, true, new EventBus());
    ProtoLangToolchainProvider toolchain =
        ProtoLangToolchainProvider.get(getConfiguredTarget("//foo:toolchain"));

    validateProtoLangToolchain(toolchain);
    validateProtoCompiler(toolchain, ProtoConstants.DEFAULT_PROTOC_LABEL);
  }

  @Test
  public void optionalFieldsAreEmpty() throws Exception {
    scratch.file(
        "foo/BUILD",
        TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
        "proto_lang_toolchain(",
        "    name = 'toolchain',",
        "    command_line = 'cmd-line:$(OUT)',",
        ")");

    update(ImmutableList.of("//foo:toolchain"), false, 1, true, new EventBus());
    ProtoLangToolchainProvider toolchain =
        ProtoLangToolchainProvider.get(getConfiguredTarget("//foo:toolchain"));

    assertThat(toolchain.pluginExecutable()).isNull();
    assertThat(toolchain.runtime()).isNull();
    assertThat(toolchain.mnemonic()).isEqualTo("GenProto");
  }
}
