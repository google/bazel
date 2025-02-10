// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.LibraryToLinkValue;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.SequenceBuilder;
import com.google.devtools.build.lib.rules.cpp.CppActionConfigs.CppPlatform;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CToolchain;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link LinkCommandLine}. In particular, tests command line emitted subject to the
 * presence of certain build variables.
 */
@RunWith(JUnit4.class)
public final class LinkCommandLineTest extends BuildViewTestCase {

  private static CcToolchainVariables.Builder getMockBuildVariables() {
    return getMockBuildVariables(ImmutableList.of());
  }

  private static CcToolchainVariables.Builder getMockBuildVariables(
      ImmutableList<String> linkstampOutputs) {
    CcToolchainVariables.Builder result = CcToolchainVariables.builder();

    result.addStringVariable(LinkBuildVariables.GENERATE_INTERFACE_LIBRARY.getVariableName(), "no");
    result.addStringVariable(
        LinkBuildVariables.INTERFACE_LIBRARY_INPUT.getVariableName(), "ignored");
    result.addStringVariable(
        LinkBuildVariables.INTERFACE_LIBRARY_OUTPUT.getVariableName(), "ignored");
    result.addStringVariable(
        LinkBuildVariables.INTERFACE_LIBRARY_BUILDER.getVariableName(), "ignored");
    result.addStringSequenceVariable(
        LinkBuildVariables.LINKSTAMP_PATHS.getVariableName(), linkstampOutputs);

    return result;
  }

  private static FeatureConfiguration getMockFeatureConfiguration() throws Exception {
    ImmutableList<CToolchain.Feature> features =
        new ImmutableList.Builder<CToolchain.Feature>()
            .addAll(
                CppActionConfigs.getLegacyFeatures(
                    CppPlatform.LINUX,
                    ImmutableSet.of(),
                    "MOCK_LINKER_TOOL",
                    /* supportsEmbeddedRuntimes= */ true,
                    /* supportsInterfaceSharedLibraries= */ false))
            .addAll(CppActionConfigs.getFeaturesToAppearLastInFeaturesList(ImmutableSet.of()))
            .build();

    ImmutableList<CToolchain.ActionConfig> actionConfigs =
        CppActionConfigs.getLegacyActionConfigs(
            CppPlatform.LINUX,
            "MOCK_GCC_TOOL",
            "MOCK_AR_TOOL",
            "MOCK_STRIP_TOOL",
            /* supportsInterfaceSharedLibraries= */ false,
            /* existingActionConfigNames= */ ImmutableSet.of());

    return CcToolchainTestHelper.buildFeatures(features, actionConfigs)
        .getFeatureConfiguration(
            ImmutableSet.of(
                Link.LinkTargetType.EXECUTABLE.getActionName(),
                Link.LinkTargetType.NODEPS_DYNAMIC_LIBRARY.getActionName(),
                Link.LinkTargetType.STATIC_LIBRARY.getActionName(),
                CppActionNames.CPP_COMPILE,
                CppActionNames.LINKSTAMP_COMPILE,
                CppRuleClasses.INCLUDES,
                CppRuleClasses.PREPROCESSOR_DEFINES,
                CppRuleClasses.INCLUDE_PATHS,
                CppRuleClasses.PIC));
  }

  private static LinkCommandLine.Builder minimalConfiguration(
      CcToolchainVariables.Builder variables) throws Exception {
    return new LinkCommandLine.Builder()
        .setBuildVariables(variables.build())
        .setFeatureConfiguration(getMockFeatureConfiguration());
  }

  private static LinkCommandLine.Builder minimalConfiguration() throws Exception {
    return minimalConfiguration(getMockBuildVariables());
  }

  /**
   * Tests that when linking without linkstamps, the exec command is the same as the link command.
   */
  @Test
  public void testLinkCommandIsExecCommandWhenNoLinkstamps() throws Exception {
    LinkCommandLine linkConfig =
        minimalConfiguration()
            .setActionName(LinkTargetType.EXECUTABLE.getActionName())
            .build();
    List<String> rawLinkArgv = linkConfig.arguments();
    assertThat(linkConfig.arguments()).isEqualTo(rawLinkArgv);
  }

  /** Tests that symbol count output does not appear in argv when it should not. */
  @Test
  public void testSymbolCountsDisabled() throws Exception {
    LinkCommandLine linkConfig =
        minimalConfiguration()
            .forceToolPath("foo/bar/gcc")
            .build();
    List<String> argv = linkConfig.arguments();
    for (String arg : argv) {
      assertThat(arg).doesNotContain("print-symbol-counts");
    }
  }

  @Test
  public void testLibrariesToLink() throws Exception {
    CcToolchainVariables.Builder variables =
        getMockBuildVariables()
            .addCustomBuiltVariable(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                new SequenceBuilder()
                    .addValue(LibraryToLinkValue.forStaticLibrary("foo", false))
                    .addValue(LibraryToLinkValue.forStaticLibrary("bar", true)));

    LinkCommandLine linkConfig =
        minimalConfiguration(variables)
            .forceToolPath("foo/bar/gcc")
            .setActionName(LinkTargetType.NODEPS_DYNAMIC_LIBRARY.getActionName())
            .build();
    String commandLine = Joiner.on(" ").join(linkConfig.arguments());
    assertThat(commandLine).matches(".*foo -Wl,-whole-archive bar -Wl,-no-whole-archive.*");
  }

  @Test
  public void testLibrarySearchDirectories() throws Exception {
    CcToolchainVariables.Builder variables =
        getMockBuildVariables()
            .addStringSequenceVariable(
                LinkBuildVariables.LIBRARY_SEARCH_DIRECTORIES.getVariableName(),
                ImmutableList.of("foo", "bar"));

    LinkCommandLine linkConfig =
        minimalConfiguration(variables)
            .setActionName(LinkTargetType.NODEPS_DYNAMIC_LIBRARY.getActionName())
            .build();
    assertThat(linkConfig.arguments()).containsAtLeast("-Lfoo", "-Lbar").inOrder();
  }

  @Test
  public void testLinkerParamFileForStaticLibrary() throws Exception {
    CcToolchainVariables.Builder variables =
        getMockBuildVariables()
            .addStringVariable(
                LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(),
                "LINKER_PARAM_FILE_PLACEHOLDER");

    LinkCommandLine linkConfig =
        minimalConfiguration(variables)
            .setActionName(LinkTargetType.STATIC_LIBRARY.getActionName())
            .setSplitCommandLine(true)
            .build();
    assertThat(linkConfig.getCommandLines().unpack().get(1).paramFileInfo.always()).isTrue();
  }

  @Test
  public void testLinkerParamFileForDynamicLibrary() throws Exception {
    CcToolchainVariables.Builder variables =
        getMockBuildVariables()
            .addStringVariable(
                LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(),
                "LINKER_PARAM_FILE_PLACEHOLDER");

    LinkCommandLine linkConfig =
        minimalConfiguration(variables)
            .setActionName(LinkTargetType.NODEPS_DYNAMIC_LIBRARY.getActionName())
            .setSplitCommandLine(true)
            .build();
    assertThat(linkConfig.getCommandLines().unpack().get(1).paramFileInfo.always()).isTrue();
  }

  private static List<String> basicArgv(LinkTargetType targetType) throws Exception {
    return basicArgv(targetType, getMockBuildVariables());
  }

  private static List<String> basicArgv(
      LinkTargetType targetType, CcToolchainVariables.Builder variables) throws Exception {
    LinkCommandLine linkConfig =
        minimalConfiguration(variables)
            .setActionName(targetType.getActionName())
            .build();
    return linkConfig.arguments();
  }

  /** Tests that a "--force_pic" configuration applies "-pie" to executable links. */
  @Test
  public void testPicMode() throws Exception {
    String pieArg = "-pie";

    // Disabled:
    assertThat(basicArgv(LinkTargetType.EXECUTABLE)).doesNotContain(pieArg);
    assertThat(basicArgv(LinkTargetType.NODEPS_DYNAMIC_LIBRARY)).doesNotContain(pieArg);
    assertThat(basicArgv(LinkTargetType.STATIC_LIBRARY)).doesNotContain(pieArg);
    assertThat(basicArgv(LinkTargetType.PIC_STATIC_LIBRARY)).doesNotContain(pieArg);
    assertThat(basicArgv(LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY)).doesNotContain(pieArg);
    assertThat(basicArgv(LinkTargetType.ALWAYS_LINK_PIC_STATIC_LIBRARY)).doesNotContain(pieArg);

    CcToolchainVariables.Builder picVariables =
        getMockBuildVariables()
            .addStringVariable(LinkBuildVariables.FORCE_PIC.getVariableName(), "");
    // Enabled:
    useConfiguration("--force_pic");
    assertThat(basicArgv(LinkTargetType.EXECUTABLE, picVariables)).contains(pieArg);
    assertThat(basicArgv(LinkTargetType.NODEPS_DYNAMIC_LIBRARY, picVariables))
        .doesNotContain(pieArg);
    assertThat(basicArgv(LinkTargetType.STATIC_LIBRARY, picVariables)).doesNotContain(pieArg);
    assertThat(basicArgv(LinkTargetType.PIC_STATIC_LIBRARY, picVariables)).doesNotContain(pieArg);
    assertThat(basicArgv(LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY, picVariables))
        .doesNotContain(pieArg);
    assertThat(basicArgv(LinkTargetType.ALWAYS_LINK_PIC_STATIC_LIBRARY, picVariables))
        .doesNotContain(pieArg);
  }

  @Test
  public void testSplitStaticLinkCommand() throws Exception {
    useConfiguration("--nostart_end_lib");
    LinkCommandLine linkConfig =
        minimalConfiguration(
                getMockBuildVariables()
                    .addStringVariable(
                        LinkBuildVariables.OUTPUT_EXECPATH.getVariableName(), "a/FakeOutput")
                    .addStringVariable(
                        LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(),
                        "LINKER_PARAM_FILE_PLACEHOLDER"))
            .setActionName(LinkTargetType.STATIC_LIBRARY.getActionName())
            .forceToolPath("foo/bar/ar")
            .setSplitCommandLine(true)
            .setParameterFileType(ParameterFileType.UNQUOTED)
            .build();
    assertThat(linkConfig.getCommandLines().unpack().get(0).commandLine.arguments())
        .containsExactly("foo/bar/ar");
    assertThat(linkConfig.getCommandLines().unpack().get(1).paramFileInfo.always()).isTrue();
    assertThat(linkConfig.getParamCommandLine(null, PathMapper.NOOP))
        .containsExactly("rcsD", "a/FakeOutput")
        .inOrder();
  }

  @Test
  public void testSplitDynamicLinkCommand() throws Exception {
    useConfiguration("--nostart_end_lib");
    LinkCommandLine linkConfig =
        minimalConfiguration(
                getMockBuildVariables()
                    .addStringVariable(
                        LinkBuildVariables.OUTPUT_EXECPATH.getVariableName(), "a/FakeOutput")
                    .addStringVariable(
                        LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(), "some/file.params")
                    .addStringSequenceVariable(
                        LinkBuildVariables.USER_LINK_FLAGS.getVariableName(), ImmutableList.of("")))
            .setActionName(LinkTargetType.DYNAMIC_LIBRARY.getActionName())
            .forceToolPath("foo/bar/linker")
            .setSplitCommandLine(true)
            .build();
    assertThat(linkConfig.getCommandLines().unpack().get(0).commandLine.arguments())
        .containsExactly("foo/bar/linker");
    assertThat(linkConfig.getParamCommandLine(null, PathMapper.NOOP))
        .containsExactly("-shared", "-o", "a/FakeOutput", "")
        .inOrder();
  }

  @Test
  public void testStaticLinkCommand() throws Exception {
    useConfiguration("--nostart_end_lib");
    LinkCommandLine linkConfig =
        minimalConfiguration(
                getMockBuildVariables()
                    .addStringVariable(
                        LinkBuildVariables.OUTPUT_EXECPATH.getVariableName(), "a/FakeOutput"))
            .forceToolPath("foo/bar/ar")
            .setActionName(LinkTargetType.STATIC_LIBRARY.getActionName())
            .build();
    List<String> result = linkConfig.arguments();
    assertThat(result).containsExactly("rcsD", "a/FakeOutput").inOrder();
    assertThat(linkConfig.getLinkerPathString()).isEqualTo("foo/bar/ar");
  }

  @Test
  public void testSplitAlwaysLinkLinkCommand() throws Exception {
    CcToolchainVariables.Builder variables =
        CcToolchainVariables.builder()
            .addStringVariable(CcCommon.SYSROOT_VARIABLE_NAME, "/usr/grte/v1")
            .addStringVariable(LinkBuildVariables.OUTPUT_EXECPATH.getVariableName(), "a/FakeOutput")
            .addStringVariable(
                LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(), "some/file.params")
            .addCustomBuiltVariable(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                new CcToolchainVariables.SequenceBuilder()
                    .addValue(LibraryToLinkValue.forObjectFile("foo.o", false))
                    .addValue(LibraryToLinkValue.forObjectFile("bar.o", false)));

    LinkCommandLine linkConfig =
        minimalConfiguration(variables)
            .setActionName(LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY.getActionName())
            .forceToolPath("foo/bar/ar")
            .setSplitCommandLine(true)
            .build();

    assertThat(linkConfig.getCommandLines().unpack().get(0).commandLine.arguments())
        .containsExactly("foo/bar/ar");
    assertThat(linkConfig.getParamCommandLine(null, PathMapper.NOOP))
        .containsExactly("rcsD", "a/FakeOutput", "foo.o", "bar.o")
        .inOrder();
  }

  private SpecialArtifact createTreeArtifact(String name) {
    FileSystem fs = scratch.getFileSystem();
    Path execRoot = fs.getPath(TestUtils.tmpDir());
    PathFragment execPath = PathFragment.create("out").getRelative(name);
    return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
        ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out"), execPath);
  }

  private static void verifyArguments(
      Iterable<String> arguments,
      Iterable<String> allowedArguments,
      Iterable<String> disallowedArguments) {
    assertThat(arguments).containsAtLeastElementsIn(allowedArguments);
    assertThat(arguments).containsNoneIn(disallowedArguments);
  }

  @Test
  public void testTreeArtifactLink() throws Exception {
    SpecialArtifact testTreeArtifact = createTreeArtifact("library_directory");

    TreeFileArtifact library0 = TreeFileArtifact.createTreeOutput(testTreeArtifact, "library0.o");
    TreeFileArtifact library1 = TreeFileArtifact.createTreeOutput(testTreeArtifact, "library1.o");

    ArtifactExpander expander =
        treeArtifact ->
            treeArtifact.equals(testTreeArtifact)
                ? ImmutableSortedSet.of(library0, library1)
                : ImmutableSortedSet.of();

    Iterable<String> treeArtifactsPaths = ImmutableList.of(testTreeArtifact.getExecPathString());
    Iterable<String> treeFileArtifactsPaths =
        ImmutableList.of(library0.getExecPathString(), library1.getExecPathString());

    LinkCommandLine linkConfig =
        minimalConfiguration(
                getMockBuildVariables()
                    .addStringVariable(
                        LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(), "some/file.params")
                    .addCustomBuiltVariable(
                        LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                        new CcToolchainVariables.SequenceBuilder()
                            .addValue(
                                LibraryToLinkValue.forObjectFileGroup(
                                    ImmutableList.of(testTreeArtifact), false))))
            .forceToolPath("foo/bar/gcc")
            .setActionName(LinkTargetType.STATIC_LIBRARY.getActionName())
            .setSplitCommandLine(true)
            .build();

    // Should only reference the tree artifact.
    verifyArguments(
        linkConfig.arguments(null, PathMapper.NOOP), treeArtifactsPaths, treeFileArtifactsPaths);
    verifyArguments(linkConfig.arguments(), treeArtifactsPaths, treeFileArtifactsPaths);
    verifyArguments(
        linkConfig.getParamCommandLine(null, PathMapper.NOOP),
        treeArtifactsPaths,
        treeFileArtifactsPaths);

    // Should only reference tree file artifacts.
    verifyArguments(
        linkConfig.arguments(expander, PathMapper.NOOP),
        treeFileArtifactsPaths,
        treeArtifactsPaths);
    verifyArguments(
        linkConfig.arguments(expander, PathMapper.NOOP),
        treeFileArtifactsPaths,
        treeArtifactsPaths);
    verifyArguments(
        linkConfig.getParamCommandLine(expander, PathMapper.NOOP),
        treeFileArtifactsPaths,
        treeArtifactsPaths);
  }
}
