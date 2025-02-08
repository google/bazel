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

package com.google.devtools.build.lib.rules.cpp.proto;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.getFirstArtifactEndingWith;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.packages.util.MockProtoSupport;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction;
import com.google.devtools.build.lib.rules.cpp.CppRuleClasses;
import com.google.devtools.build.lib.testutil.TestConstants;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CcProtoLibraryTest extends BuildViewTestCase {
  @Before
  public void setUp() throws Exception {
    MockProtoSupport.setup(mockToolsConfig);
    scratch.appendFile(
        "third_party/protobuf/BUILD.bazel",
        TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "filegroup(name='license')",
        "genrule(name='protoc_gen', cmd='', executable = True, outs = ['protoc'])",
        "proto_library(",
        "    name = 'any_proto',",
        "    srcs = ['any.proto'],",
        ")",
        "proto_lang_toolchain(",
        "    name = 'cc_toolchain',",
        "    command_line = '--cpp_out=$(OUT)',",
        "    blacklisted_protos = [':any_proto'],",
        "    progress_message = 'Generating C++ proto_library %{label}',",
        "    toolchain_type = '@com_google_protobuf//bazel/private:cc_toolchain_type',",
        ")");
    scratch.appendFile(
        "third_party/protobuf/bazel/private/toolchains/BUILD.bazel",
        """
        toolchain(
            name = "cc_source_toolchain",
            exec_compatible_with = [],
            target_compatible_with = [],
            toolchain = "//:cc_toolchain",
            toolchain_type = "//bazel/private:cc_toolchain_type",
        )
        """);
    scratch.appendFile(
        "third_party/protobuf/MODULE.bazel",
        "register_toolchains('//bazel/private/toolchains:all')");
    invalidatePackages(); // A dash of magic to re-evaluate the WORKSPACE file.
  }

  @Test
  public void protoToolchainResolution_enabled() throws Exception {
    setBuildLanguageOptions("--incompatible_enable_proto_toolchain_resolution");
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES));
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = ['foo_proto'])",
        "proto_library(name = 'foo_proto', srcs = ['foo.proto'])");
    assertThat(prettyArtifactNames(getFilesToBuild(getConfiguredTarget("//x:foo_cc_proto"))))
        .containsExactly(
            "x/foo.pb.h",
            "x/foo.pb.cc",
            "x/libfoo_proto.a",
            "x/libfoo_proto.ifso",
            "x/libfoo_proto.so");
  }

  @Test
  public void basic() throws Exception {
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES));
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = ['foo_proto'])",
        "proto_library(name = 'foo_proto', srcs = ['foo.proto'])");
    assertThat(prettyArtifactNames(getFilesToBuild(getConfiguredTarget("//x:foo_cc_proto"))))
        .containsExactly("x/foo.pb.h", "x/foo.pb.cc", "x/libfoo_proto.a",
            "x/libfoo_proto.ifso", "x/libfoo_proto.so");
  }

  @Test
  public void canBeUsedFromCcRules() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_library(name = 'foo', srcs = ['foo.cc'], deps = ['foo_cc_proto'])",
        "cc_binary(name = 'bin', srcs = ['bin.cc'], deps = ['foo_cc_proto'])",
        "cc_proto_library(name = 'foo_cc_proto', deps = ['foo_proto'])",
        "proto_library(name = 'foo_proto', srcs = ['foo.proto'])");

    update(
        ImmutableList.of("//x:foo", "//x:bin"),
        false /* keepGoing */,
        1 /* loadingPhaseThreads */,
        true /* doAnalysis */,
        new EventBus());
  }

  @Test
  public void disallowMultipleDeps() throws Exception {
    checkError(
        "x",
        "foo_cc_proto",
        "'deps' attribute must contain exactly one label",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = ['foo_proto', 'bar_proto'])",
        "proto_library(name = 'foo_proto', srcs = ['foo.proto'])",
        "proto_library(name = 'bar_proto', srcs = ['bar.proto'])");

    checkError(
        "y",
        "foo_cc_proto",
        "'deps' attribute must contain exactly one label",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = [])");
  }

  @Test
  public void aliasProtos() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = ['alias_proto'])",
        "proto_library(name = 'alias_proto', deps = [':foo_proto'])",
        "proto_library(name = 'foo_proto', srcs = ['foo.proto'])");

    CcCompilationContext ccCompilationContext =
        getConfiguredTarget("//x:foo_cc_proto").get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(prettyArtifactNames(ccCompilationContext.getDeclaredIncludeSrcs()))
        .containsExactly("x/foo.pb.h");
  }

  @Test
  public void blacklistedProtos() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'any_cc_proto', deps = ['@com_google_protobuf//:any_proto'])");

    CcCompilationContext ccCompilationContext =
        getConfiguredTarget("//x:any_cc_proto").get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(prettyArtifactNames(ccCompilationContext.getDeclaredIncludeSrcs())).isEmpty();
  }

  @Test
  public void blacklistedProtosInTransitiveDeps() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = ['foo_proto'])",
        "proto_library(",
        "    name = 'foo_proto',",
        "    srcs = ['foo.proto'],",
        "    deps = ['@com_google_protobuf//:any_proto'],",
        ")");

    CcCompilationContext ccCompilationContext =
        getConfiguredTarget("//x:foo_cc_proto").get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(prettyArtifactNames(ccCompilationContext.getDeclaredIncludeSrcs()))
        .containsExactly("x/foo.pb.h");
  }

  @Test
  public void ccCompilationContext() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = ['foo_proto'])",
        "proto_library(name = 'foo_proto', srcs = ['foo.proto'], deps = [':bar_proto'])",
        "proto_library(name = 'bar_proto', srcs = ['bar.proto'])");

    CcCompilationContext ccCompilationContext =
        getConfiguredTarget("//x:foo_cc_proto").get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(prettyArtifactNames(ccCompilationContext.getDeclaredIncludeSrcs()))
        .containsExactly("x/foo.pb.h", "x/bar.pb.h");
  }

  @Test
  public void outputDirectoryForProtoCompileAction() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = [':bar_proto'])",
        "proto_library(name = 'bar_proto', srcs = ['bar.proto'])");

    Artifact hFile =
        getFirstArtifactEndingWith(
            getFilesToBuild(getConfiguredTarget("//x:foo_cc_proto")), "bar.pb.h");
    SpawnAction protoCompileAction = getGeneratingSpawnAction(hFile);

    assertThat(protoCompileAction.getArguments())
        .contains(
            String.format(
                "--cpp_out=%s", getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN)));
  }

  @Test
  public void commandLineControlsOutputFileSuffixes() throws Exception {
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES));
    useConfiguration(
        "--cc_proto_library_header_suffixes=.pb.h,.proto.h",
        "--cc_proto_library_source_suffixes=.pb.cc,.pb.cc.meta");
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = ['foo_proto'])",
        "proto_library(name = 'foo_proto', srcs = ['foo.proto'])");

    assertThat(prettyArtifactNames(getFilesToBuild(getConfiguredTarget("//x:foo_cc_proto"))))
        .containsExactly("x/foo.pb.cc", "x/foo.pb.h", "x/foo.pb.cc.meta", "x/foo.proto.h",
            "x/libfoo_proto.a", "x/libfoo_proto.ifso", "x/libfoo_proto.so");
  }

  // TODO(carmi): test blacklisted protos. I don't currently understand what's the wanted behavior.

  @Test
  public void generatedSourcesNotCoverageInstrumented() throws Exception {
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=.");
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(name = 'foo_cc_proto', deps = ['foo_proto'])",
        "proto_library(name = 'foo_proto', srcs = ['foo.proto'])");
    ConfiguredTarget target = getConfiguredTarget("//x:foo_cc_proto");
    List<CppCompileAction> compilationSteps =
        actionsTestUtil()
            .findTransitivePrerequisitesOf(
                getFirstArtifactEndingWith(getFilesToBuild(target), ".a"), CppCompileAction.class);
    List<String> options = compilationSteps.get(0).getCompilerOptions();
    assertThat(options).doesNotContain("-fprofile-arcs");
    assertThat(options).doesNotContain("-ftest-coverage");
  }

  @Test
  public void testCcProtoLibraryLoadedThroughMacro() throws Exception {
    if (!analysisMock.isThisBazel()) {
      return;
    }
    setupTestCcProtoLibraryLoadedThroughMacro(/* loadMacro= */ true);
    assertThat(getConfiguredTarget("//a:a")).isNotNull();
    assertNoEvents();
  }

  private void setupTestCcProtoLibraryLoadedThroughMacro(boolean loadMacro) throws Exception {
    scratch.file(
        "a/BUILD",
        getAnalysisMock().ccSupport().getMacroLoadStatement(loadMacro, "cc_proto_library"),
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('@com_google_protobuf//bazel:cc_proto_library.bzl', 'cc_proto_library')",
        "cc_proto_library(",
        "    name='a',",
        "    deps=[':a_p'],",
        ")",
        "proto_library(",
        "    name='a_p',",
        "    srcs = ['a.proto'],",
        ")");
  }
}
