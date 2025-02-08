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

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.LibraryToLinkValue;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.VariableValue;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests that C++ linking action is populated with the correct build variables. */
@RunWith(JUnit4.class)
public class LinkBuildVariablesTest extends LinkBuildVariablesTestCase {

  @Before
  public void createFooFooCcLibraryForRuleContext() throws IOException {
    scratch.file("foo/BUILD", "cc_library(name = 'foo')");
  }

  private RuleContext getRuleContext() throws Exception {
    return getRuleContext(getConfiguredTarget("//foo:foo"));
  }

  @Test
  public void testIsUsingFissionIsIdenticalForCompileAndLink() {
    assertThat(LinkBuildVariables.IS_USING_FISSION.getVariableName())
        .isEqualTo(CompileBuildVariables.IS_USING_FISSION.getVariableName());
  }

  @Test
  public void testForcePicBuildVariable() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC));
    useConfiguration("--force_pic");
    scratch.file("x/BUILD", "cc_binary(name = 'bin', srcs = ['a.cc'])");
    scratch.file("x/a.cc");

    ConfiguredTarget target = getConfiguredTarget("//x:bin");
    CcToolchainVariables variables = getLinkBuildVariables(target, Link.LinkTargetType.EXECUTABLE);
    String variableValue =
        getVariableValue(
            getRuleContext(), variables, LinkBuildVariables.FORCE_PIC.getVariableName());
    assertThat(variableValue).contains("");
  }

  @Test
  public void testLibrariesToLinkAreExported() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    useConfiguration();

    scratch.file("x/BUILD", "cc_library(name = 'foo', srcs = ['a.cc'])");
    scratch.file("x/a.cc");

    ConfiguredTarget target = getConfiguredTarget("//x:foo");
    CcToolchainVariables variables =
        getLinkBuildVariables(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY);
    VariableValue librariesToLinkSequence =
        variables.getVariable(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    assertThat(librariesToLinkSequence).isNotNull();
    Iterable<? extends VariableValue> librariesToLink =
        librariesToLinkSequence.getSequenceValue(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    assertThat(librariesToLink).hasSize(1);
    VariableValue nameValue =
        librariesToLink
            .iterator()
            .next()
            .getFieldValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                LibraryToLinkValue.NAME_FIELD_NAME);
    assertThat(nameValue).isNotNull();
    String name = nameValue.getStringValue(LibraryToLinkValue.NAME_FIELD_NAME, PathMapper.NOOP);
    assertThat(name).matches(".*a\\..*o");
  }

  @Test
  public void testLibrarySearchDirectoriesAreExported() throws Exception {
    useConfiguration();

    scratch.file("x/BUILD", "cc_binary(name = 'bin', srcs = ['some-dir/bar.so'])");
    scratch.file("x/some-dir/bar.so");

    ConfiguredTarget target = getConfiguredTarget("//x:bin");
    CcToolchainVariables variables = getLinkBuildVariables(target, Link.LinkTargetType.EXECUTABLE);
    List<String> variableValue =
        getSequenceVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.LIBRARY_SEARCH_DIRECTORIES.getVariableName());
    assertThat(Iterables.getOnlyElement(variableValue)).contains("some-dir");
  }

  @Test
  public void testLinkSimpleLibName() throws Exception {
    useConfiguration();

    scratch.file("x/BUILD", "cc_binary(name = 'bin', srcs = ['some-dir/libbar.so'])");
    scratch.file("x/some-dir/bar.so");

    ConfiguredTarget target = getConfiguredTarget("//x:bin");

    CcToolchainVariables variables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE);
    VariableValue librariesToLinkSequence =
        variables.getVariable(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    Iterable<? extends VariableValue> librariestoLink =
        librariesToLinkSequence.getSequenceValue(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    VariableValue nameValue =
        librariestoLink
            .iterator()
            .next()
            .getFieldValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                LibraryToLinkValue.NAME_FIELD_NAME);
    assertThat(nameValue).isNotNull();
    String name = nameValue.getStringValue(LibraryToLinkValue.NAME_FIELD_NAME, PathMapper.NOOP);
    assertThat(name).isEqualTo("bar");
  }

  @Test
  public void testLinkVersionedLibName() throws Exception {
    useConfiguration();

    scratch.file("x/BUILD", "cc_binary(name = 'bin', srcs = ['some-dir/libbar.so.1a.2'])");
    scratch.file("x/some-dir/bar.so");

    ConfiguredTarget target = getConfiguredTarget("//x:bin");

    CcToolchainVariables variables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE);
    VariableValue librariesToLinkSequence =
        variables.getVariable(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    Iterable<? extends VariableValue> librariestoLink =
        librariesToLinkSequence.getSequenceValue(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    VariableValue nameValue =
        librariestoLink
            .iterator()
            .next()
            .getFieldValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                LibraryToLinkValue.NAME_FIELD_NAME);
    assertThat(nameValue).isNotNull();
    String name = nameValue.getStringValue(LibraryToLinkValue.NAME_FIELD_NAME, PathMapper.NOOP);
    assertThat(name).isEqualTo("libbar.so.1a.2");
  }

  @Test
  public void testLinkUnusualLibName() throws Exception {
    useConfiguration();

    scratch.file("x/BUILD", "cc_binary(name = 'bin', srcs = ['some-dir/_libbar.so'])");
    scratch.file("x/some-dir/_libbar.so");

    ConfiguredTarget target = getConfiguredTarget("//x:bin");

    CcToolchainVariables variables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE);
    VariableValue librariesToLinkSequence =
        variables.getVariable(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    Iterable<? extends VariableValue> librariestoLink =
        librariesToLinkSequence.getSequenceValue(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    VariableValue nameValue =
        librariestoLink
            .iterator()
            .next()
            .getFieldValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                LibraryToLinkValue.NAME_FIELD_NAME);
    assertThat(nameValue).isNotNull();
    String name = nameValue.getStringValue(LibraryToLinkValue.NAME_FIELD_NAME, PathMapper.NOOP);
    assertThat(name).isEqualTo("_libbar.so");
  }

  @Test
  public void testInterfaceLibraryBuildingVariablesWhenLegacyGenerationPossible() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    useConfiguration();

    verifyIfsoVariables();
  }

  @Test
  public void testInterfaceLibraryBuildingVariablesWhenGenerationPossible() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES));
    useConfiguration();

    verifyIfsoVariables();
  }

  private void verifyIfsoVariables() throws Exception {
    scratch.file("x/BUILD", "cc_library(name = 'foo', srcs = ['a.cc'])");
    scratch.file("x/a.cc");

    ConfiguredTarget target = getConfiguredTarget("//x:foo");
    CcToolchainVariables variables =
        getLinkBuildVariables(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY);

    String interfaceLibraryBuilder =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.INTERFACE_LIBRARY_BUILDER.getVariableName());
    String interfaceLibraryInput =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.INTERFACE_LIBRARY_INPUT.getVariableName());
    String interfaceLibraryOutput =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.INTERFACE_LIBRARY_OUTPUT.getVariableName());
    String generateInterfaceLibrary =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.GENERATE_INTERFACE_LIBRARY.getVariableName());

    assertThat(generateInterfaceLibrary).isEqualTo("yes");
    assertThat(interfaceLibraryInput).endsWith("libfoo.so");
    assertThat(interfaceLibraryOutput).endsWith("libfoo.ifso");
    assertThat(interfaceLibraryBuilder).endsWith("build_interface_so");
  }

  @Test
  public void testNoIfsoBuildingWhenWhenThinLtoIndexing() throws Exception {
    // Make sure the interface shared object generation is enabled in the configuration
    // (which it is not by default for some windows toolchains)
    invalidatePackages(true);
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.THIN_LTO,
                    CppRuleClasses.SUPPORTS_PIC,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_START_END_LIB));
    useConfiguration("--features=thin_lto");

    scratch.file("x/BUILD", "cc_library(name = 'foo', srcs = ['a.cc'])");
    scratch.file("x/a.cc");

    ConfiguredTarget target = getConfiguredTarget("//x:foo");
    SpawnAction linkAction = getCppLinkAction(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY);
    String rootExecPath = linkAction.getPrimaryOutput().getRoot().getExecPathString();

    LtoBackendAction backendAction =
        (LtoBackendAction)
            getPredecessorByInputName(
                linkAction, "x/libfoo.so.lto/" + rootExecPath + "/x/_objs/foo/a.pic.o");
    assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile");

    SpawnAction indexAction =
        (SpawnAction)
            getPredecessorByInputName(
                backendAction,
                "x/libfoo.so.lto/" + rootExecPath + "/x/_objs/foo/a.pic.o.thinlto.bc");
    CcToolchainVariables variables = getLinkCommandLine(indexAction).getBuildVariables();

    String interfaceLibraryBuilder =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.INTERFACE_LIBRARY_BUILDER.getVariableName());
    String interfaceLibraryInput =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.INTERFACE_LIBRARY_INPUT.getVariableName());
    String interfaceLibraryOutput =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.INTERFACE_LIBRARY_OUTPUT.getVariableName());
    String generateInterfaceLibrary =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.GENERATE_INTERFACE_LIBRARY.getVariableName());

    assertThat(generateInterfaceLibrary).isEqualTo("no");
    assertThat(interfaceLibraryInput).endsWith("ignored");
    assertThat(interfaceLibraryOutput).endsWith("ignored");
    assertThat(interfaceLibraryBuilder).endsWith("ignored");
  }

  @Test
  public void testInterfaceLibraryBuildingVariablesWhenGenerationNotAllowed() throws Exception {
    // Make sure the interface shared object generation is enabled in the configuration
    // (which it is not by default for some windows toolchains)
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES));
    useConfiguration();

    scratch.file("x/BUILD", "cc_library(name = 'foo', srcs = ['a.cc'])");
    scratch.file("x/a.cc");

    ConfiguredTarget target = getConfiguredTarget("//x:foo");
    CcToolchainVariables variables = getLinkBuildVariables(target, LinkTargetType.STATIC_LIBRARY);

    String interfaceLibraryBuilder =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.INTERFACE_LIBRARY_BUILDER.getVariableName());
    String interfaceLibraryInput =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.INTERFACE_LIBRARY_INPUT.getVariableName());
    String interfaceLibraryOutput =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.INTERFACE_LIBRARY_OUTPUT.getVariableName());
    String generateInterfaceLibrary =
        getVariableValue(
            getRuleContext(),
            variables,
            LinkBuildVariables.GENERATE_INTERFACE_LIBRARY.getVariableName());

    assertThat(generateInterfaceLibrary).isEqualTo("no");
    assertThat(interfaceLibraryInput).endsWith("ignored");
    assertThat(interfaceLibraryOutput).endsWith("ignored");
    assertThat(interfaceLibraryBuilder).endsWith("ignored");
  }

  @Test
  public void testOutputExecpath() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    // Make sure the interface shared object generation is enabled in the configuration
    // (which it is not by default for some windows toolchains)
    scratch.file("x/BUILD", "cc_library(name = 'foo', srcs = ['a.cc'])");
    scratch.file("x/a.cc");

    ConfiguredTarget target = getConfiguredTarget("//x:foo");
    CcToolchainVariables variables =
        getLinkBuildVariables(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY);

    assertThat(
            getVariableValue(
                getRuleContext(), variables, LinkBuildVariables.OUTPUT_EXECPATH.getVariableName()))
        .endsWith("x/libfoo.so");
  }

  @Test
  public void testOutputExecpathIsNotExposedWhenThinLtoIndexing() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.THIN_LTO,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_PIC,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                    CppRuleClasses.SUPPORTS_START_END_LIB));
    useConfiguration("--features=thin_lto");

    scratch.file("x/BUILD", "cc_library(name = 'foo', srcs = ['a.cc'])");
    scratch.file("x/a.cc");

    ConfiguredTarget target = getConfiguredTarget("//x:foo");
    SpawnAction linkAction = getCppLinkAction(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY);
    String rootExecPath = linkAction.getPrimaryOutput().getRoot().getExecPathString();

    LtoBackendAction backendAction =
        (LtoBackendAction)
            getPredecessorByInputName(
                linkAction, "x/libfoo.so.lto/" + rootExecPath + "/x/_objs/foo/a.pic.o");
    assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile");

    SpawnAction indexAction =
        (SpawnAction)
            getPredecessorByInputName(
                backendAction,
                "x/libfoo.so.lto/" + rootExecPath + "/x/_objs/foo/a.pic.o.thinlto.bc");
    CcToolchainVariables variables = getLinkCommandLine(indexAction).getBuildVariables();

    assertThat(variables.isAvailable(LinkBuildVariables.OUTPUT_EXECPATH.getVariableName()))
        .isFalse();
  }

  @Test
  public void testIsCcTestLinkActionBuildVariable() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        cc_test(
            name = "foo_test",
            srcs = ["a.cc"],
        )

        cc_binary(
            name = "foo",
            srcs = ["a.cc"],
        )
        """);
    scratch.file("x/a.cc");

    ConfiguredTarget testTarget = getConfiguredTarget("//x:foo_test");
    CcToolchainVariables testVariables =
        getLinkBuildVariables(testTarget, LinkTargetType.EXECUTABLE);

    assertThat(
            testVariables
                .getVariable(LinkBuildVariables.IS_CC_TEST.getVariableName(), PathMapper.NOOP)
                .isTruthy())
        .isTrue();

    ConfiguredTarget binaryTarget = getConfiguredTarget("//x:foo");
    CcToolchainVariables binaryVariables =
        getLinkBuildVariables(binaryTarget, LinkTargetType.EXECUTABLE);

    assertThat(
            binaryVariables
                .getVariable(LinkBuildVariables.IS_CC_TEST.getVariableName(), PathMapper.NOOP)
                .isTruthy())
        .isFalse();
  }

  @Test
  public void testStripBinariesIsEnabledWhenStripModeIsAlwaysNoMatterWhat() throws Exception {
    scratch.file("x/BUILD", "cc_binary(name = 'foo', srcs = ['a.cc'])");
    scratch.file("x/a.cc");

    assertStripBinaryVariableIsPresent("always", "opt", true);
    assertStripBinaryVariableIsPresent("always", "fastbuild", true);
    assertStripBinaryVariableIsPresent("always", "dbg", true);
    assertStripBinaryVariableIsPresent("sometimes", "opt", false);
    assertStripBinaryVariableIsPresent("sometimes", "fastbuild", true);
    assertStripBinaryVariableIsPresent("sometimes", "dbg", false);
    assertStripBinaryVariableIsPresent("never", "opt", false);
    assertStripBinaryVariableIsPresent("never", "fastbuild", false);
    assertStripBinaryVariableIsPresent("never", "dbg", false);
  }

  private void assertStripBinaryVariableIsPresent(
      String stripMode, String compilationMode, boolean isEnabled) throws Exception {
    useConfiguration("--strip=" + stripMode, "--compilation_mode=" + compilationMode);
    ConfiguredTarget target = getConfiguredTarget("//x:foo");
    CcToolchainVariables variables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE);
    assertThat(variables.isAvailable(LinkBuildVariables.STRIP_DEBUG_SYMBOLS.getVariableName()))
        .isEqualTo(isEnabled);
  }

  @Test
  public void testIsUsingFissionVariableUsingLegacyFields() throws Exception {
    scratch.file("x/BUILD",
        "cc_binary(name = 'foo', srcs = ['foo.cc'])");
    scratch.file("x/foo.cc");

    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO));

    useConfiguration("--fission=no");
    ConfiguredTarget target = getConfiguredTarget("//x:foo");
    CcToolchainVariables variables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE);
    assertThat(variables.isAvailable(LinkBuildVariables.IS_USING_FISSION.getVariableName()))
        .isFalse();

    useConfiguration("--fission=yes");
    ConfiguredTarget fissionTarget = getConfiguredTarget("//x:foo");
    CcToolchainVariables fissionVariables =
        getLinkBuildVariables(fissionTarget, LinkTargetType.EXECUTABLE);
    assertThat(fissionVariables.isAvailable(LinkBuildVariables.IS_USING_FISSION.getVariableName()))
        .isTrue();
  }

  @Test
  public void testIsUsingFissionVariable() throws Exception {
    scratch.file("x/BUILD", "cc_binary(name = 'foo', srcs = ['foo.cc'])");
    scratch.file("x/foo.cc");

    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO));

    useConfiguration("--fission=no");
    ConfiguredTarget target = getConfiguredTarget("//x:foo");
    CcToolchainVariables variables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE);
    assertThat(variables.isAvailable(LinkBuildVariables.IS_USING_FISSION.getVariableName()))
        .isFalse();

    useConfiguration("--fission=yes");
    ConfiguredTarget fissionTarget = getConfiguredTarget("//x:foo");
    CcToolchainVariables fissionVariables =
        getLinkBuildVariables(fissionTarget, LinkTargetType.EXECUTABLE);
    assertThat(fissionVariables.isAvailable(LinkBuildVariables.IS_USING_FISSION.getVariableName()))
        .isTrue();
  }

  @Test
  public void testSysrootVariable() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withSysroot("/usr/local/custom-sysroot"));
    useConfiguration();

    scratch.file("x/BUILD", "cc_binary(name = 'foo', srcs = ['a.cc'])");
    scratch.file("x/a.cc");

    ConfiguredTarget testTarget = getConfiguredTarget("//x:foo");
    CcToolchainVariables testVariables =
        getLinkBuildVariables(testTarget, LinkTargetType.EXECUTABLE);

    assertThat(testVariables.isAvailable(CcCommon.SYSROOT_VARIABLE_NAME)).isTrue();
  }

  private Action getPredecessorByInputName(Action action, String str) {
    for (Artifact a : action.getInputs().toList()) {
      if (a.getExecPathString().contains(str)) {
        return getGeneratingAction(a);
      }
    }
    return null;
  }

  @Test
  public void testUserLinkFlagsWithLinkoptOption() throws Exception {
    useConfiguration("--linkopt=-bar");

    scratch.file("x/BUILD", "cc_binary(name = 'foo', srcs = ['a.cc'], linkopts = ['-foo'])");
    scratch.file("x/a.cc");

    ConfiguredTarget testTarget = getConfiguredTarget("//x:foo");
    CcToolchainVariables testVariables =
        getLinkBuildVariables(testTarget, LinkTargetType.EXECUTABLE);

    ImmutableList<String> userLinkFlags =
        CcToolchainVariables.toStringList(
            testVariables, LinkBuildVariables.USER_LINK_FLAGS.getVariableName(), PathMapper.NOOP);
    assertThat(userLinkFlags).containsAtLeast("-foo", "-bar").inOrder();
  }

  @Test
  public void testLinkerInputsOverrideWholeArchive() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures("disable_whole_archive_for_static_lib_configuration"));

    scratch.file(
        "x/BUILD",
        "cc_library(name='a', hdrs=['a.h'], srcs = ['a.cc'], "
            + " features=['disable_whole_archive_for_static_lib'])",
        "cc_library(name='b', hdrs=['b.h'], srcs = ['b.cc'], alwayslink=1)",
        "cc_binary(name = 'c.so', linkstatic=1, linkshared=1, deps=[':a', ':b'])");

    ConfiguredTarget testTarget = getConfiguredTarget("//x:c.so");
    CcToolchainVariables testVariables =
        getLinkBuildVariables(testTarget, LinkTargetType.DYNAMIC_LIBRARY);

    VariableValue librariesToLinkSequence =
        testVariables.getVariable(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    assertThat(librariesToLinkSequence).isNotNull();
    Iterable<? extends VariableValue> librariesToLink =
        librariesToLinkSequence.getSequenceValue(
            LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP);
    assertThat(Iterables.size(librariesToLink)).isAnyOf(2, 3);

    Iterator<? extends VariableValue> librariesToLinkIterator = librariesToLink.iterator();
    // :a should not be whole archive
    VariableValue aWholeArchiveValue =
        librariesToLinkIterator
            .next()
            .getFieldValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                LibraryToLinkValue.IS_WHOLE_ARCHIVE_FIELD_NAME);
    assertThat(aWholeArchiveValue).isNotNull();
    assertThat(aWholeArchiveValue.isTruthy()).isFalse();

    // :b should be whole archive
    VariableValue bWholeArchiveValue =
        librariesToLinkIterator
            .next()
            .getFieldValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                LibraryToLinkValue.IS_WHOLE_ARCHIVE_FIELD_NAME);
    assertThat(bWholeArchiveValue).isNotNull();
    assertThat(bWholeArchiveValue.isTruthy()).isTrue();
  }
}
