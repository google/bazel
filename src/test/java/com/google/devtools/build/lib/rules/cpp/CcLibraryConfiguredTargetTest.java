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
// Copyright 2006 Google Inc. All rights reserved.

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.rules.cpp.SolibSymlinkAction.MAX_FILENAME_LENGTH;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.DefaultInfo;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.packages.util.MockCcSupport;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** "White-box" unit test of cc_library rule. */
@RunWith(JUnit4.class)
public class CcLibraryConfiguredTargetTest extends BuildViewTestCase {
  private static final PathFragment STL_CPPMAP = PathFragment.create("stl_cc_library.cppmap");
  private static final PathFragment CROSSTOOL_CPPMAP = PathFragment.create("crosstool.cppmap");

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DummyTestFragment.class);
    return builder.addRuleDefinition(new TestRuleClassProvider.MakeVariableTesterRule()).build();
  }

  @Before
  public final void createFiles() throws Exception {
    scratch.file(
        "hello/BUILD",
        """
        cc_library(
          name = 'hello',
          srcs = ['hello.cc'],
        )
        cc_library(
          name = 'hello_static',
          srcs = ['hello.cc'],
          linkstatic = 1,
        )
        cc_library(
          name = 'hello_alwayslink',
          srcs = ['hello.cc'],
          alwayslink = 1,
        )
        cc_binary(
          name = 'hello_bin',
          srcs = ['hello_main.cc'],
        )
        """);
    scratch.file(
        "hello/hello.cc",
        "#include <stdio.h>",
        "int hello_world() { printf(\"Hello, world!\\n\"); }");
    scratch.file(
        "hello/hello_main.cc",
        "#include <stdio.h>",
        "int main() { printf(\"Hello, world!\\n\"); }");
  }

  private CppCompileAction getCppCompileAction(String label) throws Exception {
    return getCppCompileAction(getConfiguredTarget(label));
  }

  private CppCompileAction getCppCompileAction(ConfiguredTarget target) throws Exception {
    List<CppCompileAction> compilationSteps =
        actionsTestUtil()
            .findTransitivePrerequisitesOf(
                ActionsTestUtil.getFirstArtifactEndingWith(getFilesToBuild(target), ".a"),
                CppCompileAction.class);
    return compilationSteps.get(0);
  }

  private CppModuleMapAction getCppModuleMapAction(String label) throws Exception {
    ConfiguredTarget target = getConfiguredTarget(label);
    CppModuleMap cppModuleMap =
        target.get(CcInfo.PROVIDER).getCcCompilationContext().getCppModuleMap();
    return (CppModuleMapAction) getGeneratingAction(cppModuleMap.getArtifact());
  }

  private void assertNoCppModuleMapAction(String label) throws Exception {
    ConfiguredTarget target = getConfiguredTarget(label);
    assertThat(target.get(CcInfo.PROVIDER).getCcCompilationContext().getCppModuleMap()).isNull();
  }

  public void checkWrongExtensionInArtifactNamePattern(
      String categoryName, ImmutableList<String> correctExtensions) throws Exception {
    reporter.removeHandler(failFastHandler);
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY, CppRuleClasses.TARGETS_WINDOWS)
                .withArtifactNamePatterns(ImmutableList.of(categoryName, "", ".wrong_ext")));
    useConfiguration();
    getConfiguredTarget(
        ruleClassProvider.getToolsRepository() + "//tools/cpp:current_cc_toolchain");
    assertContainsEvent(
        String.format(
            "Unrecognized file extension '.wrong_ext', allowed "
                + "extensions are %s, please check artifact_name_pattern configuration for "
                + "%s in your rule.",
            StringUtil.joinEnglishListSingleQuoted(correctExtensions), categoryName));
  }

  @Test
  public void testDefinesAndMakeVariables() throws Exception {
    ConfiguredTarget l =
        scratchConfiguredTarget(
            "a",
            "l",
            "cc_library(name='l', srcs=['l.cc'], defines=['V=$(FOO)'], toolchains=[':v'])",
            "make_variable_tester(name='v', variables={'FOO': 'BAR'})");
    assertThat(l.get(CcInfo.PROVIDER).getCcCompilationContext().getDefines()).contains("V=BAR");
  }

  @Test
  public void testLocalDefinesAndMakeVariables() throws Exception {
    ConfiguredTarget l =
        scratchConfiguredTarget(
            "a",
            "l",
            "cc_library(name='l', srcs=['l.cc'], local_defines=['V=$(FOO)'], toolchains=[':v'])",
            "make_variable_tester(name='v', variables={'FOO': 'BAR'})");
    assertThat(l.get(CcInfo.PROVIDER).getCcCompilationContext().getNonTransitiveDefines())
        .contains("V=BAR");
  }

  @Test
  public void testMisconfiguredCrosstoolRaisesErrorWhenLinking() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES, CppRuleClasses.PIC)
                .withActionConfigs(CppActionNames.CPP_COMPILE));
    useConfiguration();

    checkError(
        "test",
        "test",
        "Expected action_config for 'c++-link-static-library' to be configured",
        "cc_library(name = 'test', srcs = ['test.cc'])");
  }

  @Test
  public void testMisconfiguredCrosstoolRaisesErrorWhenCompiling() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES, CppRuleClasses.PIC)
                .withActionConfigs(CppActionNames.CPP_LINK_STATIC_LIBRARY));
    useConfiguration();

    checkError(
        "test",
        "test",
        "Expected action_config for 'c++-compile' to be configured",
        "cc_library(name = 'test', srcs = ['test.cc'])");
  }

  @Test
  public void testFilesToBuild() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES));
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    String cpu = "k8"; // CPU of the platform specified with --platforms
    Artifact archive = getBinArtifact("libhello.a", hello);
    Artifact implSharedObject = getBinArtifact("libhello.so", hello);
    Artifact implInterfaceSharedObject = getBinArtifact("libhello.ifso", hello);
    Artifact implSharedObjectLink =
        getSharedArtifact("_solib_" + cpu + "/libhello_Slibhello.so", hello);
    Artifact implInterfaceSharedObjectLink =
        getSharedArtifact("_solib_" + cpu + "/libhello_Slibhello.ifso", hello);
    assertThat(getFilesToBuild(hello).toList())
        .containsExactly(archive, implSharedObject, implInterfaceSharedObject);
    assertThat(
            LibraryToLink.getDynamicLibrariesForLinking(
                hello
                    .get(CcInfo.PROVIDER)
                    .getCcNativeLibraryInfo()
                    .getTransitiveCcNativeLibraries()))
        .containsExactly(implInterfaceSharedObjectLink);
    assertThat(
            hello
                .get(CcInfo.PROVIDER)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime(/* linkingStatically= */ false))
        .containsExactly(implSharedObjectLink);
  }

  @Test
  public void testFilesToBuildWithoutDSO() throws Exception {
    // This is like the preceding test, but with a toolchain that can't build '.so' files
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--host_platform=" + TestConstants.PLATFORM_LABEL);
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    Artifact archive = getBinArtifact("libhello.a", hello);
    assertThat(getFilesToBuild(hello).toList()).containsExactly(archive);
  }

  @Test
  public void testFilesToBuildWithInterfaceSharedObjects() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES));
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    String cpu = "k8"; // CPU of the platform specified with --platforms
    Artifact archive = getBinArtifact("libhello.a", hello);
    Artifact sharedObject = getBinArtifact("libhello.ifso", hello);
    Artifact implSharedObject = getBinArtifact("libhello.so", hello);
    Artifact sharedObjectLink =
        getSharedArtifact("_solib_" + cpu + "/libhello_Slibhello.ifso", hello);
    Artifact implSharedObjectLink =
        getSharedArtifact("_solib_" + cpu + "/libhello_Slibhello.so", hello);
    assertThat(getFilesToBuild(hello).toList())
        .containsExactly(archive, sharedObject, implSharedObject);
    assertThat(
            LibraryToLink.getDynamicLibrariesForLinking(
                hello
                    .get(CcInfo.PROVIDER)
                    .getCcNativeLibraryInfo()
                    .getTransitiveCcNativeLibraries()))
        .containsExactly(sharedObjectLink);
    assertThat(
            hello
                .get(CcInfo.PROVIDER)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime(/* linkingStatically= */ false))
        .containsExactly(implSharedObjectLink);
  }

  @Test
  public void testFilesToBuildWithSaveFeatureState() throws Exception {
    useConfiguration("--experimental_save_feature_state");
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    Artifact archive = getBinArtifact("libhello.a", hello);
    assertThat(getFilesToBuild(hello).toList()).containsExactly(archive);
    assertThat(ActionsTestUtil.baseArtifactNames(getOutputGroup(hello, OutputGroupInfo.DEFAULT)))
        .contains("hello_feature_state.txt");
  }

  @Test
  public void testEmptyLinkopts() throws Exception {
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    assertThat(hello.get(CcInfo.PROVIDER).getCcLinkingContext().getUserLinkFlags().isEmpty())
        .isTrue();
  }

  @Test
  public void testSoName() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES));
    // Without interface shared libraries.
    useConfiguration("--nointerface_shared_objects");
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    Artifact sharedObject =
        getOnlyElement(
            FileType.filter(getFilesToBuild(hello).toList(), CppFileTypes.SHARED_LIBRARY));
    SpawnAction action = (SpawnAction) getGeneratingAction(sharedObject);
    for (String option : action.getArguments()) {
      assertThat(option).doesNotContain("-Wl,-soname");
    }

    // With interface shared libraries.
    useConfiguration("--interface_shared_objects");
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    hello = getConfiguredTarget("//hello:hello");
    sharedObject =
        FileType.filter(getFilesToBuild(hello).toList(), CppFileTypes.SHARED_LIBRARY)
            .iterator()
            .next();
    action = (SpawnAction) getGeneratingAction(sharedObject);
    assertThat(action.getArguments()).contains("-Wl,-soname=libhello_Slibhello.so");
  }

  @Test
  public void testLinkActionCanConsumeArtifactExtensions() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withArtifactNamePatterns(MockCcSupport.STATIC_LINK_TWEAKED_ARTIFACT_NAME_PATTERN));
    useConfiguration("--features=" + Link.LinkTargetType.STATIC_LIBRARY.getActionName());
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    Artifact archive =
        FileType.filter(getFilesToBuild(hello).toList(), FileType.of(".lib")).iterator().next();

    SpawnAction action = (SpawnAction) getGeneratingAction(archive);

    assertThat(action.getArguments()).contains(archive.getExecPathString());
  }

  @Test
  public void testObjectFileNamesCanBeSpecifiedInToolchain() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withArtifactNamePatterns(ImmutableList.of("object_file", "", ".obj")));

    useConfiguration();
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    assertThat(artifactByPath(getFilesToBuild(hello), ".a", ".obj")).isNotNull();
  }

  @Test
  public void testWindowsFileNamePatternsCanBeSpecifiedInToolchain() throws Exception {
    if (!AnalysisMock.get().isThisBazel()) {
      return;
    }
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                    CppRuleClasses.TARGETS_WINDOWS)
                .withArtifactNamePatterns(
                    ImmutableList.of("object_file", "", ".obj"),
                    ImmutableList.of("static_library", "", ".lib"),
                    ImmutableList.of("alwayslink_static_library", "", ".lo.lib"),
                    ImmutableList.of("executable", "", ".exe"),
                    ImmutableList.of("dynamic_library", "", ".dll"),
                    ImmutableList.of("interface_library", "", ".if.lib")));
    useConfiguration();

    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    Artifact helloObj =
        getBinArtifact("_objs/hello/hello.obj", getConfiguredTarget("//hello:hello"));
    CppCompileAction helloObjAction = (CppCompileAction) getGeneratingAction(helloObj);
    assertThat(helloObjAction).isNotNull();

    Artifact helloLib =
        FileType.filter(getFilesToBuild(hello).toList(), CppFileTypes.ARCHIVE).iterator().next();
    assertThat(helloLib.getExecPathString()).endsWith("hello.lib");

    ConfiguredTarget helloAlwaysLink = getConfiguredTarget("//hello:hello_alwayslink");
    Artifact helloLibAlwaysLink =
        FileType.filter(getFilesToBuild(helloAlwaysLink).toList(), CppFileTypes.ALWAYS_LINK_LIBRARY)
            .iterator()
            .next();
    assertThat(helloLibAlwaysLink.getExecPathString()).endsWith("hello_alwayslink.lo.lib");

    ConfiguredTarget helloBin = getConfiguredTarget("//hello:hello_bin");
    Artifact helloBinExe = getFilesToBuild(helloBin).toList().get(0);
    assertThat(helloBinExe.getExecPathString()).endsWith("hello_bin.exe");

    assertThat(artifactsToStrings(getOutputGroup(hello, "dynamic_library")))
        .containsExactly("bin hello/hello_5e918d2.dll", "bin hello/hello.if.lib");
  }

  @Test
  public void testWrongObjectFileArtifactNamePattern() throws Exception {
    checkWrongExtensionInArtifactNamePattern(
        "object_file", ArtifactCategory.OBJECT_FILE.getAllowedExtensions());
  }

  @Test
  public void testWrongStaticLibraryArtifactNamePattern() throws Exception {
    checkWrongExtensionInArtifactNamePattern(
        "static_library", ArtifactCategory.STATIC_LIBRARY.getAllowedExtensions());
  }

  @Test
  public void testWrongAlwayslinkStaticLibraryArtifactNamePattern() throws Exception {
    checkWrongExtensionInArtifactNamePattern(
        "alwayslink_static_library",
        ArtifactCategory.ALWAYSLINK_STATIC_LIBRARY.getAllowedExtensions());
  }

  @Test
  public void testWrongExecutableArtifactNamePattern() throws Exception {
    checkWrongExtensionInArtifactNamePattern(
        "executable", ArtifactCategory.EXECUTABLE.getAllowedExtensions());
  }

  @Test
  public void testWrongDynamicLibraryArtifactNamePattern() throws Exception {
    checkWrongExtensionInArtifactNamePattern(
        "dynamic_library", ArtifactCategory.DYNAMIC_LIBRARY.getAllowedExtensions());
  }

  @Test
  public void testWrongInterfaceLibraryArtifactNamePattern() throws Exception {
    checkWrongExtensionInArtifactNamePattern(
        "interface_library", ArtifactCategory.INTERFACE_LIBRARY.getAllowedExtensions());
  }

  @Test
  public void testArtifactSelectionBaseNameTemplating() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withArtifactNamePatterns(
                    MockCcSupport.STATIC_LINK_AS_DOT_A_ARTIFACT_NAME_PATTERN));
    useConfiguration("--features=" + Link.LinkTargetType.STATIC_LIBRARY.getActionName());
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    Artifact archive =
        FileType.filter(getFilesToBuild(hello).toList(), CppFileTypes.ARCHIVE).iterator().next();
    assertThat(archive.getExecPathString()).endsWith("libhello.a");
  }

  @Test
  public void testArtifactsToAlwaysBuild() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    // ArtifactsToAlwaysBuild should apply both for static libraries.
    ConfiguredTarget helloStatic = getConfiguredTarget("//hello:hello_static");
    assertThat(artifactsToStrings(getOutputGroup(helloStatic, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
        .containsExactly("bin hello/_objs/hello_static/hello.pic.o");
    Artifact implSharedObject = getBinArtifact("libhello_static.so", helloStatic);
    assertThat(getFilesToBuild(helloStatic).toList()).doesNotContain(implSharedObject);

    // And for shared libraries.
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    assertThat(artifactsToStrings(getOutputGroup(helloStatic, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
        .containsExactly("bin hello/_objs/hello_static/hello.pic.o");
    implSharedObject = getBinArtifact("libhello.so", hello);
    assertThat(getFilesToBuild(hello).toList()).contains(implSharedObject);
  }

  @Test
  public void testTransitiveArtifactsToAlwaysBuildStatic() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC));

    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo",
            "x",
            "cc_library(name = 'x', srcs = ['x.cc'], deps = [':y'], linkstatic = 1)",
            "cc_library(name = 'y', srcs = ['y.cc'], deps = [':z'])",
            "cc_library(name = 'z', srcs = ['z.cc'])");
    assertThat(artifactsToStrings(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
        .containsExactly(
            "bin foo/_objs/x/x.pic.o", "bin foo/_objs/y/y.pic.o", "bin foo/_objs/z/z.pic.o");
  }

  @Test
  public void testBuildHeaderModulesAsPrerequisites() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC));
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo",
            "x",
            "package(features = ['header_modules'])",
            "cc_library(name = 'x', srcs = ['x.cc'], deps = [':y'])",
            "cc_library(name = 'y', hdrs = ['y.h'])");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                getOutputGroup(x, OutputGroupInfo.COMPILATION_PREREQUISITES)))
        .containsAtLeast("y.h", "y.cppmap", "crosstool.cppmap", "x.cppmap", "y.pic.pcm", "x.cc");
  }

  @Test
  public void testCodeCoverage() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC));
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL, "--collect_code_coverage");
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo",
            "x",
            "package(features = ['header_modules'])",
            "cc_library(name = 'x', srcs = ['x.cc'])");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                x.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR)
                    .getInstrumentationMetadataFiles()))
        .containsExactly("x.pic.gcno");
  }

  @Test
  public void testDisablingHeaderModulesWhenDependingOnModuleBuildTransitively() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(MockCcSupport.HEADER_MODULES_FEATURES));
    useConfiguration();
    scratch.file(
        "module/BUILD",
        """
        package(features = ['header_modules'])
        cc_library(
            name = 'module',
            srcs = ['a.cc', 'a.h'],
        )
        """);
    scratch.file(
        "nomodule/BUILD",
        """
        package(features = ['-header_modules'])
        cc_library(
            name = 'nomodule',
            srcs = ['a.cc', 'a.h'],
            deps = ['//module']
        )
        """);
    CppCompileAction moduleAction = getCppCompileAction("//module:module");
    assertThat(moduleAction.getCompilerOptions()).contains("module_name://module:module");
    CppCompileAction noModuleAction = getCppCompileAction("//nomodule:nomodule");
    assertThat(noModuleAction.getCompilerOptions()).doesNotContain("module_name://module:module");
  }

  /** Returns the non-system module maps in {@code input}. */
  private static Iterable<Artifact> getNonSystemModuleMaps(NestedSet<Artifact> input) {
    return Iterables.filter(
        input.toList(),
        (a) -> {
          PathFragment path = a.getExecPath();
          return CppFileTypes.CPP_MODULE_MAP.matches(path)
              && !path.endsWith(STL_CPPMAP)
              && !path.endsWith(CROSSTOOL_CPPMAP);
        });
  }

  /** Returns the header module artifacts in {@code input}. */
  private static Iterable<Artifact> getHeaderModules(NestedSet<Artifact> input) {
    return Iterables.filter(
        input.toList(), (artifact) -> CppFileTypes.CPP_MODULE.matches(artifact.getExecPath()));
  }

  /** Returns the flags in {@code input} that reference a header module. */
  private Iterable<String> getHeaderModuleFlags(Iterable<String> input) {
    List<String> names = new ArrayList<>();
    for (String flag : input) {
      if (CppFileTypes.CPP_MODULE.matches(flag)) {
        names.add(PathFragment.create(flag).getBaseName());
      }
    }
    return names;
  }

  @Test
  public void testCompileHeaderModules() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures("compile_header_modules"));
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    scratch.file(
        "module/BUILD",
        """
        package(features = ['header_modules'])
        cc_library(
            name = 'a',
            srcs = ['a.h', 'a.cc'],
            deps = ['b']
        )
        cc_library(
            name = 'b',
            srcs = ['b.h'],
            textual_hdrs = ['t.h'],
        )
        """);
    ConfiguredTarget moduleB = getConfiguredTarget("//module:b");
    Artifact bModuleArtifact = getBinArtifact("_objs/b/b.pic.pcm", moduleB);
    CppCompileAction bModuleAction = (CppCompileAction) getGeneratingAction(bModuleArtifact);
    assertThat(bModuleAction.getIncludeScannerSources())
        .containsExactly(getSourceArtifact("module/b.h"), getSourceArtifact("module/t.h"));
    assertThat(bModuleAction.getInputs().toList())
        .contains(getGenfilesArtifact("b.cppmap", moduleB));

    ConfiguredTarget moduleA = getConfiguredTarget("//module:a");
    Artifact aObjectArtifact = getBinArtifact("_objs/a/a.pic.o", moduleA);
    CppCompileAction aObjectAction = (CppCompileAction) getGeneratingAction(aObjectArtifact);
    assertThat(aObjectAction.getIncludeScannerSources())
        .containsExactly(getSourceArtifact("module/a.cc"));
    assertThat(aObjectAction.getCcCompilationContext().getTransitiveModules(true).toList())
        .contains(getBinArtifact("_objs/b/b.pic.pcm", moduleB));
    assertThat(aObjectAction.getInputs().toList())
        .contains(getGenfilesArtifact("b.cppmap", moduleB));
    assertNoEvents();
  }

  private void setupPackagesForSourcesWithSameBaseNameTests() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'lib',
            srcs = ['a.cc', 'subpkg1/b.cc', 'subpkg1/a.c', '//bar:srcs', 'subpkg2/A.c'],
        )
        """);
    scratch.file("bar/BUILD", "filegroup(name = 'srcs', srcs = ['a.cpp'])");
  }

  @Test
  public void testContainingSourcesWithSameBaseName() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC));
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    setupPackagesForSourcesWithSameBaseNameTests();
    getConfiguredTarget("//foo:lib");

    Artifact a0 = getBinArtifact("_objs/lib/0/a.pic.o", getConfiguredTarget("//foo:lib"));
    Artifact a1 = getBinArtifact("_objs/lib/1/a.pic.o", getConfiguredTarget("//foo:lib"));
    Artifact a2 = getBinArtifact("_objs/lib/2/a.pic.o", getConfiguredTarget("//foo:lib"));
    Artifact a3 = getBinArtifact("_objs/lib/3/A.pic.o", getConfiguredTarget("//foo:lib"));
    Artifact b = getBinArtifact("_objs/lib/b.pic.o", getConfiguredTarget("//foo:lib"));

    assertThat(getGeneratingAction(a0)).isNotNull();
    assertThat(getGeneratingAction(a1)).isNotNull();
    assertThat(getGeneratingAction(a2)).isNotNull();
    assertThat(getGeneratingAction(a3)).isNotNull();
    assertThat(getGeneratingAction(b)).isNotNull();

    assertThat(getGeneratingAction(a0).getInputs().toList())
        .contains(getSourceArtifact("foo/a.cc"));
    assertThat(getGeneratingAction(a1).getInputs().toList())
        .contains(getSourceArtifact("foo/subpkg1/a.c"));
    assertThat(getGeneratingAction(a2).getInputs().toList())
        .contains(getSourceArtifact("bar/a.cpp"));
    assertThat(getGeneratingAction(a3).getInputs().toList())
        .contains(getSourceArtifact("foo/subpkg2/A.c"));
    assertThat(getGeneratingAction(b).getInputs().toList())
        .contains(getSourceArtifact("foo/subpkg1/b.cc"));
  }

  private void setupPackagesForModuleTests(boolean useHeaderModules) throws Exception {
    scratch.file(
        "module/BUILD",
        """
        package(features = ['header_modules'])
        cc_library(
            name = 'b',
            srcs = ['b.h'],
            deps = ['//nomodule:a'],
        )
        cc_library(
            name = 'g',
            srcs = ['g.h', 'g.cc'],
            deps = ['//nomodule:c'],
        )
        cc_library(
            name = 'j',
            srcs = ['j.h', 'j.cc'],
            deps = ['//nomodule:c', '//nomodule:i'],
        )
        """);
    scratch.file(
        "nomodule/BUILD",
        "package(features = ['-header_modules'"
            + (useHeaderModules ? ", 'use_header_modules'" : "")
            + "])",
        "cc_library(",
        "    name = 'y',",
        "    srcs = ['y.h'],",
        ")",
        "cc_library(",
        "    name = 'z',",
        "    srcs = ['z.h'],",
        "    deps = [':y'],",
        ")",
        "cc_library(",
        "    name = 'a',",
        "    srcs = ['a.h'],",
        "    deps = [':z'],",
        ")",
        "cc_library(",
        "    name = 'c',",
        "    srcs = ['c.h', 'c.cc'],",
        "    deps = ['//module:b'],",
        ")",
        "cc_library(",
        "    name = 'd',",
        "    srcs = ['d.h', 'd.cc'],",
        "    deps = [':c'],",
        ")",
        "cc_library(",
        "    name = 'e',",
        "    srcs = ['e.h'],",
        "    deps = [':a'],",
        ")",
        "cc_library(",
        "    name = 'f',",
        "    srcs = ['f.h', 'f.cc'],",
        "    deps = [':e'],",
        ")",
        "cc_library(",
        "    name = 'h',",
        "    srcs = ['h.h', 'h.cc'],",
        "    deps = ['//module:g'],",
        ")",
        "cc_library(",
        "    name = 'i',",
        "    srcs = ['i.h', 'i.cc'],",
        "    deps = [':h'],",
        ")");
  }

  @Test
  public void testCompileHeaderModulesTransitively() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC));
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    setupPackagesForModuleTests(/* useHeaderModules= */ false);

    // The //nomodule:f target only depends on non-module targets, thus it should be module-free.
    ConfiguredTarget nomoduleF = getConfiguredTarget("//nomodule:f");
    ConfiguredTarget nomoduleE = getConfiguredTarget("//nomodule:e");
    assertThat(getGeneratingAction(getBinArtifact("_objs/f/f.pic.pcm", nomoduleF))).isNull();
    Artifact fObjectArtifact = getBinArtifact("_objs/f/f.pic.o", nomoduleF);
    CppCompileAction fObjectAction = (CppCompileAction) getGeneratingAction(fObjectArtifact);
    // Only the module map of f itself itself and the direct dependencies are needed.
    assertThat(getNonSystemModuleMaps(fObjectAction.getInputs()))
        .containsExactly(
            getGenfilesArtifact("f.cppmap", nomoduleF), getGenfilesArtifact("e.cppmap", nomoduleE));
    assertThat(getHeaderModules(fObjectAction.getInputs())).isEmpty();
    assertThat(fObjectAction.getIncludeScannerSources())
        .containsExactly(getSourceArtifact("nomodule/f.cc"));
    assertThat(getHeaderModuleFlags(fObjectAction.getCompilerOptions())).isEmpty();

    // The //nomodule:c target will get the header module for //module:b, which is a direct
    // dependency.
    ConfiguredTarget nomoduleC = getConfiguredTarget("//nomodule:c");
    assertThat(getGeneratingAction(getBinArtifact("_objs/c/c.pic.pcm", nomoduleC))).isNull();
    Artifact cObjectArtifact = getBinArtifact("_objs/c/c.pic.o", nomoduleC);
    CppCompileAction cObjectAction = (CppCompileAction) getGeneratingAction(cObjectArtifact);
    assertThat(getNonSystemModuleMaps(cObjectAction.getInputs()))
        .containsExactly(
            getGenfilesArtifact("b.cppmap", "//module:b"),
            getGenfilesArtifact("c.cppmap", nomoduleC));
    assertThat(getHeaderModules(cObjectAction.getInputs())).isEmpty();
    // All headers of transitive dependencies that are built as modules are needed as entry points
    // for include scanning.
    assertThat(cObjectAction.getIncludeScannerSources())
        .containsExactly(getSourceArtifact("nomodule/c.cc"));
    assertThat(cObjectAction.getMainIncludeScannerSource())
        .isEqualTo(getSourceArtifact("nomodule/c.cc"));
    assertThat(getHeaderModuleFlags(cObjectAction.getCompilerOptions())).isEmpty();

    // The //nomodule:d target depends on //module:b via one indirection (//nomodule:c).
    getConfiguredTarget("//nomodule:d");
    assertThat(
            getGeneratingAction(
                getBinArtifact("_objs/d/d.pic.pcm", getConfiguredTarget("//nomodule:d"))))
        .isNull();
    Artifact dObjectArtifact =
        getBinArtifact("_objs/d/d.pic.o", getConfiguredTarget("//nomodule:d"));
    CppCompileAction dObjectAction = (CppCompileAction) getGeneratingAction(dObjectArtifact);
    // Module map 'c.cppmap' is needed because it is a direct dependency.
    assertThat(getNonSystemModuleMaps(dObjectAction.getInputs()))
        .containsExactly(
            getGenfilesArtifact("c.cppmap", "//nomodule:c"),
            getGenfilesArtifact("d.cppmap", "//nomodule:d"));
    assertThat(getHeaderModules(dObjectAction.getInputs())).isEmpty();
    assertThat(dObjectAction.getIncludeScannerSources())
        .containsExactly(getSourceArtifact("nomodule/d.cc"));
    assertThat(getHeaderModuleFlags(dObjectAction.getCompilerOptions())).isEmpty();

    // The //module:j target depends on //module:g via //nomodule:h and on //module:b via
    // both //module:g and //nomodule:c.
    ConfiguredTarget moduleJ = getConfiguredTarget("//module:j");
    Artifact jObjectArtifact = getBinArtifact("_objs/j/j.pic.o", moduleJ);
    CppCompileAction jObjectAction = (CppCompileAction) getGeneratingAction(jObjectArtifact);
    assertThat(getHeaderModules(jObjectAction.getCcCompilationContext().getTransitiveModules(true)))
        .containsExactly(
            getBinArtifact("_objs/b/b.pic.pcm", getConfiguredTarget("//module:b")),
            getBinArtifact("_objs/g/g.pic.pcm", getConfiguredTarget("//module:g")));
    assertThat(jObjectAction.getIncludeScannerSources())
        .containsExactly(getSourceArtifact("module/j.cc"));
    assertThat(jObjectAction.getMainIncludeScannerSource())
        .isEqualTo(getSourceArtifact("module/j.cc"));
    assertThat(getHeaderModules(jObjectAction.getCcCompilationContext().getTransitiveModules(true)))
        .containsExactly(
            getBinArtifact("_objs/b/b.pic.pcm", getConfiguredTarget("//module:b")),
            getBinArtifact("_objs/g/g.pic.pcm", getConfiguredTarget("//module:g")));
  }

  @Test
  public void testCompileUsingHeaderModulesTransitively() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC));
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    setupPackagesForModuleTests(/* useHeaderModules= */ true);
    invalidatePackages();

    ConfiguredTarget nomoduleF = getConfiguredTarget("//nomodule:f");
    Artifact fObjectArtifact =
        getBinArtifact("_objs/f/f.pic.o", getConfiguredTarget("//nomodule:f"));
    CppCompileAction fObjectAction = (CppCompileAction) getGeneratingAction(fObjectArtifact);
    // Only the module map of f itself itself and the direct dependencies are needed.
    assertThat(getNonSystemModuleMaps(fObjectAction.getInputs()))
        .containsExactly(
            getGenfilesArtifact("f.cppmap", nomoduleF),
            getGenfilesArtifact("e.cppmap", "//nomodule:e"));

    getConfiguredTarget("//nomodule:c");
    Artifact cObjectArtifact =
        getBinArtifact("_objs/c/c.pic.o", getConfiguredTarget("//nomodule:c"));
    CppCompileAction cObjectAction = (CppCompileAction) getGeneratingAction(cObjectArtifact);
    assertThat(getNonSystemModuleMaps(cObjectAction.getInputs()))
        .containsExactly(
            getGenfilesArtifact("b.cppmap", "//module:b"),
            getGenfilesArtifact("c.cppmap", "//nomodule:c"));
    assertThat(getHeaderModules(cObjectAction.getCcCompilationContext().getTransitiveModules(true)))
        .containsExactly(getBinArtifact("_objs/b/b.pic.pcm", getConfiguredTarget("//module:b")));

    getConfiguredTarget("//nomodule:d");
    Artifact dObjectArtifact =
        getBinArtifact("_objs/d/d.pic.o", getConfiguredTarget("//nomodule:d"));
    CppCompileAction dObjectAction = (CppCompileAction) getGeneratingAction(dObjectArtifact);
    assertThat(getNonSystemModuleMaps(dObjectAction.getInputs()))
        .containsExactly(
            getGenfilesArtifact("c.cppmap", "//nomodule:c"),
            getGenfilesArtifact("d.cppmap", "//nomodule:d"));
    assertThat(getHeaderModules(dObjectAction.getCcCompilationContext().getTransitiveModules(true)))
        .containsExactly(getBinArtifact("_objs/b/b.pic.pcm", getConfiguredTarget("//module:b")));
  }

  private void writeSimpleCcLibrary() throws Exception {
    scratch.file(
        "module/BUILD",
        """
        cc_library(
            name = 'map',
            srcs = ['a.cc', 'a.h'],
        )
        """);
  }

  @Test
  public void testToolchainWithoutPicForNoPicCompilation() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES)
                .withActionConfigs(
                    CppActionNames.CPP_COMPILE,
                    CppActionNames.CPP_LINK_EXECUTABLE,
                    CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY,
                    CppActionNames.CPP_LINK_DYNAMIC_LIBRARY,
                    CppActionNames.CPP_LINK_STATIC_LIBRARY,
                    CppActionNames.STRIP));
    useConfiguration("--features=-supports_pic");
    scratchConfiguredTarget(
        "a",
        "a",
        "cc_binary(name='a', srcs=['a.cc'], deps=[':b'])",
        "cc_library(name='b', srcs=['b.cc'])");
  }

  @Test
  public void testNoCppModuleMap() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES, CppRuleClasses.PIC)
                .withActionConfigs(
                    CppActionNames.CPP_COMPILE,
                    CppActionNames.CPP_LINK_EXECUTABLE,
                    CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY,
                    CppActionNames.CPP_LINK_DYNAMIC_LIBRARY,
                    CppActionNames.CPP_LINK_STATIC_LIBRARY));
    useConfiguration();
    writeSimpleCcLibrary();
    assertNoCppModuleMapAction("//module:map");
  }

  @Test
  public void testCppModuleMap() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.MODULE_MAPS));
    useConfiguration();
    writeSimpleCcLibrary();
    CppModuleMapAction action = getCppModuleMapAction("//module:map");
    assertThat(ActionsTestUtil.baseArtifactNames(action.getDependencyArtifacts()))
        .contains("crosstool.cppmap");
    assertThat(artifactsToStrings(action.getPrivateHeaders())).containsExactly("src module/a.h");
    assertThat(action.getPublicHeaders()).isEmpty();
  }

  /**
   * Historically, blaze hasn't added the pre-compiled libraries from srcs to the files to build.
   * This test ensures that we do not accidentally break that - we may do so intentionally.
   */
  @Test
  public void testFilesToBuildWithPrecompiledStaticLibrary() throws Exception {
    ConfiguredTarget hello =
        scratchConfiguredTarget(
            "precompiled",
            "library",
            "cc_library(name = 'library', ",
            "           srcs = ['missing.a'])");
    assertThat(artifactsToStrings(getFilesToBuild(hello)))
        .doesNotContain("src precompiled/missing.a");
  }

  @Test
  public void testAllowDuplicateNonCompiledSources() throws Exception {
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "x",
            "x",
            "filegroup(name = 'xso', srcs = ['x.so'])",
            "cc_library(name = 'x', srcs = ['x.so', ':xso'])");
    assertThat(x).isNotNull();
  }

  @Test
  public void testDoNotCompileSourceFilesInHeaders() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers");
    ConfiguredTarget x =
        scratchConfiguredTarget("x", "x", "cc_library(name = 'x', hdrs = ['x.cc'])");
    assertThat(getGeneratingAction(getBinArtifact("_objs/x/x.o", x))).isNull();
  }

  @Test
  public void testProcessHeadersInDependencies() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo",
            "x",
            "cc_library(name = 'x', deps = [':y'])",
            "cc_library(name = 'y', hdrs = ['y.h'])");
    assertThat(ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
        .isEqualTo("y.h.processed");
  }

  @Test
  public void testProcessHeadersInDependenciesOfBinaries() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo",
            "x",
            "cc_binary(name = 'x', deps = [':y', ':z'])",
            "cc_library(name = 'y', hdrs = ['y.h'])",
            "cc_library(name = 'z', srcs = ['z.cc'])");
    String hiddenTopLevel =
        ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL));
    assertThat(hiddenTopLevel).doesNotContain("y.h.processed");
    assertThat(hiddenTopLevel).doesNotContain("z.pic.o");
    String validation = ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.VALIDATION));
    assertThat(validation).contains("y.h.processed");
  }

  @Test
  public void testDoNotProcessHeadersInDependencies() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers");
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo",
            "x",
            "cc_library(name = 'x', deps = [':y'])",
            "cc_library(name = 'y', hdrs = ['y.h'])");
    assertThat(ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
        .isEmpty();
  }

  @Test
  public void testProcessHeadersInCompileOnlyMode() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");
    ConfiguredTarget y =
        scratchConfiguredTarget(
            "foo",
            "y",
            "cc_library(name = 'x', deps = [':y'])",
            "cc_library(name = 'y', hdrs = ['y.h'])");
    assertThat(ActionsTestUtil.baseNamesOf(getOutputGroup(y, OutputGroupInfo.FILES_TO_COMPILE)))
        .isEqualTo("y.h.processed");
  }

  @Test
  public void testSrcCompileActionMnemonic() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");

    ConfiguredTarget x =
        scratchConfiguredTarget("foo", "x", "cc_library(name = 'x', srcs = ['a.cc'])");

    assertThat(getGeneratingCompileAction("_objs/x/a.o", x).getMnemonic()).isEqualTo("CppCompile");
  }

  @Test
  public void testHeaderCompileActionMnemonic() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");

    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo", "x", "cc_library(name = 'x', srcs = ['y.h'], hdrs = ['z.h'])");

    assertThat(getGeneratingCompileAction("_objs/x/y.h.processed", x).getMnemonic())
        .isEqualTo("CppCompile");
    assertThat(getGeneratingCompileAction("_objs/x/z.h.processed", x).getMnemonic())
        .isEqualTo("CppCompile");
  }

  @Test
  public void testIncompatibleUseCppCompileHeaderMnemonic() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration(
        "--incompatible_use_cpp_compile_header_mnemonic",
        "--features=parse_headers",
        "--process_headers_in_dependencies");

    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo", "x", "cc_library(name = 'x', srcs = ['a.cc', 'y.h'], hdrs = ['z.h'])");

    assertThat(getGeneratingCompileAction("_objs/x/a.o", x).getMnemonic()).isEqualTo("CppCompile");
    assertThat(getGeneratingCompileAction("_objs/x/y.h.processed", x).getMnemonic())
        .isEqualTo("CppCompileHeader");
    assertThat(getGeneratingCompileAction("_objs/x/z.h.processed", x).getMnemonic())
        .isEqualTo("CppCompileHeader");
  }

  private CppCompileAction getGeneratingCompileAction(
      String packageRelativePath, ConfiguredTarget owner) {
    return (CppCompileAction) getGeneratingAction(getBinArtifact(packageRelativePath, owner));
  }

  @Test
  public void testIncludePathOrder() throws Exception {
    useConfiguration("--incompatible_merge_genfiles_directory=false");
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'bar',
            includes = ['bar'],
        )
        cc_library(
            name = 'foo',
            srcs = ['foo.cc'],
            includes = ['foo'],
            deps = [':bar'],
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//foo");
    CppCompileAction action = getCppCompileAction(target);
    String genfilesDir =
        getConfiguration(target).getGenfilesFragment(RepositoryName.MAIN).toString();
    String binDir = getConfiguration(target).getBinFragment(RepositoryName.MAIN).toString();
    // Local include paths come first.
    assertContainsSublist(
        action.getCompilerOptions(),
        ImmutableList.of(
            "-isystem",
            "foo/foo",
            "-isystem",
            genfilesDir + "/foo/foo",
            "-isystem",
            binDir + "/foo/foo",
            "-isystem",
            "foo/bar",
            "-isystem",
            genfilesDir + "/foo/bar",
            "-isystem",
            binDir + "/foo/bar"));
  }

  @Test
  public void testDefinesOrder() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'bar',
            defines = ['BAR'],
        )
        cc_library(
            name = 'foo',
            srcs = ['foo.cc'],
            defines = ['FOO'],
            deps = [':bar'],
        )
        """);
    CppCompileAction action = getCppCompileAction("//foo");
    // Inherited defines come first.
    assertContainsSublist(action.getCompilerOptions(), ImmutableList.of("-DBAR", "-DFOO"));
  }

  @Test
  public void testLocalDefinesNotPassedTransitively() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'bar',
            defines = ['TRANSITIVE_BAR'],
            local_defines = ['LOCAL_BAR'],
        )
        cc_library(
            name = 'foo',
            srcs = ['foo.cc'],
            defines = ['TRANSITIVE_FOO'],
            local_defines = ['LOCAL_FOO'],
            deps = [':bar'],
        )
        """);
    CppCompileAction action = getCppCompileAction("//foo");
    // Inherited defines come first.
    assertContainsSublist(
        action.getCompilerOptions(),
        ImmutableList.of("-DTRANSITIVE_BAR", "-DTRANSITIVE_FOO", "-DLOCAL_FOO"));
    assertThat(action.getCompilerOptions()).doesNotContain("-DLOCAL_BAR");
  }

  // Regression test - setting "-shared" caused an exception when computing the link command.
  @Test
  public void testLinkOptsNotPassedToStaticLink() throws Exception {
    scratchConfiguredTarget(
        "foo",
        "foo",
        "cc_library(",
        "    name = 'foo',",
        "    srcs = ['foo.cc'],",
        "    linkopts = ['-shared'],",
        ")");
  }

  // cc_toolchain_config.bzl provides "dbg", "fastbuild" and "opt" feature when
  // compilation_mode_features are requested.
  private static final String COMPILATION_MODE_FEATURES = "compilation_mode_features";

  private List<String> getCompilationModeFlags(String... flags) throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(COMPILATION_MODE_FEATURES, CppRuleClasses.SUPPORTS_PIC));
    useConfiguration(flags);
    scratch.overwriteFile("mode/BUILD", "cc_library(name = 'a', srcs = ['a.cc'])");
    getConfiguredTarget("//mode:a");
    Artifact objectArtifact = getBinArtifact("_objs/a/a.pic.o", getConfiguredTarget("//mode:a"));
    CppCompileAction action = (CppCompileAction) getGeneratingAction(objectArtifact);
    return action.getCompilerOptions();
  }

  @Test
  public void testCompilationModeFeatures() throws Exception {
    List<String> flags;
    flags = getCompilationModeFlags("--platforms=" + TestConstants.PLATFORM_LABEL);
    assertThat(flags).contains("-fastbuild");
    assertThat(flags).containsNoneOf("-opt", "-dbg");

    flags =
        getCompilationModeFlags(
            "--platforms=" + TestConstants.PLATFORM_LABEL, "--compilation_mode=fastbuild");
    assertThat(flags).contains("-fastbuild");
    assertThat(flags).containsNoneOf("-opt", "-dbg");

    flags =
        getCompilationModeFlags(
            "--platforms=" + TestConstants.PLATFORM_LABEL, "--compilation_mode=opt");
    assertThat(flags).contains("-opt");
    assertThat(flags).containsNoneOf("-fastbuild", "-dbg");

    flags =
        getCompilationModeFlags(
            "--platforms=" + TestConstants.PLATFORM_LABEL, "--compilation_mode=dbg");
    assertThat(flags).contains("-dbg");
    assertThat(flags).containsNoneOf("-fastbuild", "-opt");
  }

  @Test
  public void testIncludePathsOutsideExecutionRoot() throws Exception {
    scratchRule("root", "a", "cc_library(name='a', srcs=['a.cc'], copts=['-Id/../../somewhere'])");
    CppCompileAction compileAction = getCppCompileAction("//root:a");
    try {
      compileAction.verifyActionIncludePaths(compileAction.getSystemIncludeDirs(), false);
    } catch (ActionExecutionException exception) {
      assertThat(exception)
          .hasMessageThat()
          .isEqualTo(
              "The include path '../somewhere' references a path outside of the execution root.");
    }
  }

  @Test
  public void testAbsoluteIncludePathsOutsideExecutionRoot() throws Exception {
    scratchRule("root", "a", "cc_library(name='a', srcs=['a.cc'], copts=['-I/somewhere'])");
    CppCompileAction compileAction = getCppCompileAction("//root:a");
    try {
      compileAction.verifyActionIncludePaths(compileAction.getSystemIncludeDirs(), false);
    } catch (ActionExecutionException exception) {
      assertThat(exception)
          .hasMessageThat()
          .isEqualTo(
              "The include path '/somewhere' references a path outside of the execution root.");
    }
  }

  @Test
  public void testSystemIncludePathsOutsideExecutionRoot() throws Exception {
    scratchRule("root", "a", "cc_library(name='a', srcs=['a.cc'], copts=['-isystem../system'])");
    CppCompileAction compileAction = getCppCompileAction("//root:a");
    try {
      compileAction.verifyActionIncludePaths(compileAction.getSystemIncludeDirs(), false);
    } catch (ActionExecutionException exception) {
      assertThat(exception)
          .hasMessageThat()
          .isEqualTo(
              "The include path '../system' references a path outside of the execution root.");
    }
  }

  @Test
  public void testAbsoluteSystemIncludePathsOutsideExecutionRoot() throws Exception {
    scratchRule("root", "a", "cc_library(name='a', srcs=['a.cc'], copts=['-isystem/system'])");
    CppCompileAction compileAction = getCppCompileAction("//root:a");
    try {
      compileAction.verifyActionIncludePaths(compileAction.getSystemIncludeDirs(), false);
    } catch (ActionExecutionException exception) {
      assertThat(exception)
          .hasMessageThat()
          .isEqualTo("The include path '/system' references a path outside of the execution root.");
    }
  }

  @Test
  public void alwaysAddStaticAndDynamicLibraryToFilesToBuildWhenBuilding() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES));
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    ConfiguredTarget target =
        scratchConfiguredTarget("a", "b", "cc_library(name = 'b', srcs = ['source.cc'])");

    assertThat(artifactsToStrings(getFilesToBuild(target)))
        .containsExactly("bin a/libb.a", "bin a/libb.ifso", "bin a/libb.so");
  }

  @Test
  public void addOnlyStaticLibraryToFilesToBuildWhenWrappingIffImplicitOutput() throws Exception {
    // This shared library has the same name as the archive generated by this rule, so it should
    // override said archive. However, said archive should still be put in files to build.
    ConfiguredTargetAndData target =
        scratchConfiguredTargetAndData("a", "b", "cc_library(name = 'b', srcs = ['libb.so'])");

    if (!analysisMock.isThisBazel()) {
      assertThat(artifactsToStrings(getFilesToBuild(target.getConfiguredTarget())))
          .containsExactly("bin a/libb.a");
    } else {
      assertThat(artifactsToStrings(getFilesToBuild(target.getConfiguredTarget()))).isEmpty();
    }
  }

  @Test
  public void addStaticLibraryToStaticSharedLinkParamsWhenBuilding() throws Exception {
    ConfiguredTarget target =
        scratchConfiguredTarget("a", "foo", "cc_library(name = 'foo', srcs = ['foo.cc'])");

    LibraryToLink library =
        target.get(CcInfo.PROVIDER).getCcLinkingContext().getLibraries().getSingleton();
    Artifact libraryToUse = library.getPicStaticLibrary();
    if (libraryToUse == null) {
      // We may get either a static library or pic static library depending on platform.
      libraryToUse = library.getStaticLibrary();
    }
    assertThat(libraryToUse).isNotNull();
    assertThat(artifactsToStrings(ImmutableList.of(libraryToUse))).contains("bin a/libfoo.a");
  }

  @Test
  public void dontAddStaticLibraryToStaticSharedLinkParamsWhenWrappingSameLibraryIdentifier()
      throws Exception {
    ConfiguredTarget target =
        scratchConfiguredTarget("a", "foo", "cc_library(name = 'foo', srcs = ['libfoo.so'])");

    LibraryToLink library =
        target.get(CcInfo.PROVIDER).getCcLinkingContext().getLibraries().getSingleton();
    assertThat(library.getStaticLibrary()).isNull();
    assertThat(artifactsToStrings(ImmutableList.of(library.getResolvedSymlinkDynamicLibrary())))
        .contains("src a/libfoo.so");
  }

  @Test
  public void onlyAddOneWrappedLibraryWithSameLibraryIdentifierToLibraries() throws Exception {
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "a", "foo", "cc_library(name = 'foo', srcs = ['libfoo.lo', 'libfoo.so'])");

    assertThat(target.get(CcInfo.PROVIDER).getCcLinkingContext().getLibraries().toList())
        .hasSize(1);
  }

  @Test
  public void testCcLinkParamsHasDynamicLibrariesForRuntime() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--features=copy_dynamic_libraries_to_binary");
    ConfiguredTarget target =
        scratchConfiguredTarget("a", "foo", "cc_library(name = 'foo', srcs = ['foo.cc'])");
    Iterable<Artifact> libraries =
        target
            .get(CcInfo.PROVIDER)
            .getCcLinkingContext()
            .getDynamicLibrariesForRuntime(/* linkingStatically= */ false);
    assertThat(artifactsToStrings(libraries)).doesNotContain("bin a/libfoo.ifso");
    assertThat(artifactsToStrings(libraries)).contains("bin a/libfoo.so");
  }

  @Test
  public void testCcLinkParamsHasDynamicLibrariesForRuntimeWithoutCopyFeature() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    invalidatePackages();
    ConfiguredTarget target =
        scratchConfiguredTarget("a", "foo", "cc_library(name = 'foo', srcs = ['foo.cc'])");
    Iterable<Artifact> libraries =
        target
            .get(CcInfo.PROVIDER)
            .getCcLinkingContext()
            .getDynamicLibrariesForRuntime(/* linkingStatically= */ false);
    assertThat(artifactsToStrings(libraries)).doesNotContain("bin _solib_k8/liba_Slibfoo.ifso");
    assertThat(artifactsToStrings(libraries)).contains("bin _solib_k8/liba_Slibfoo.so");
  }

  @Test
  public void testCcLinkParamsDoNotHaveDynamicLibrariesForRuntime() throws Exception {
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "a", "foo", "cc_library(name = 'foo', srcs = ['foo.cc'], linkstatic=1)");
    Iterable<Artifact> libraries =
        target
            .get(CcInfo.PROVIDER)
            .getCcLinkingContext()
            .getDynamicLibrariesForRuntime(/* linkingStatically= */ false);
    assertThat(artifactsToStrings(libraries)).isEmpty();
  }

  @Test
  public void forbidBuildingAndWrappingSameLibraryIdentifier() throws Exception {
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    checkError(
        "a",
        "foo",
        "Can't put library with "
            + "identifier 'a/libfoo' into the srcs of a cc_library with the same name (foo) which "
            + "also contains other code or objects to link",
        "cc_library(name = 'foo', srcs = ['foo.cc', 'libfoo.lo'])");
  }

  @Test
  public void testProcessedHeadersWithPicSharedLibsAndNoPicBinaries() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "-c", "opt");
    // Should not crash
    scratchConfiguredTarget("a", "a", "cc_library(name='a', hdrs=['a.h'])");
  }

  @Test
  public void testAlwaysLinkAndDisableWholeArchiveError() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures("disable_whole_archive_for_static_lib_configuration"));

    useConfiguration("--features=disable_whole_archive_for_static_lib");
    // Should be fine.
    assertThat(
            scratchConfiguredTarget("a", "a", "cc_library(name='a', hdrs=['a.h'], srcs=['a.cc'])"))
        .isNotNull();
    // Should error out.
    reporter.removeHandler(failFastHandler);
    scratchConfiguredTarget(
        "b", "b", "cc_library(name='b', hdrs=['b.h'], srcs=['b.cc'], alwayslink=1)");
    assertContainsEvent(
        "alwayslink should not be True for a target with the disable_whole_archive_for_static_lib"
            + " feature enabled");
  }

  @Test
  public void checkWarningEmptyLibrary() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        package(features = ['header_modules'])
        cc_library(
            name = 'foo',
            srcs = ['foo.o'],
        )
        """);
    getConfiguredTarget("//a:foo");
    assertNoEvents();
  }

  @Test
  public void testLinkerInputsHasRightLabels() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'baz',
            srcs = ['baz.cc'],
        )
        cc_library(
            name = 'bar',
            srcs = ['bar.cc'],
            deps = [':baz'],
        )
        cc_library(
            name = 'foo',
            srcs = ['foo.cc'],
            deps = [':bar'],
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//foo");
    assertThat(
            target.get(CcInfo.PROVIDER).getCcLinkingContext().getLinkerInputs().toList().stream()
                .map(x -> x.getOwner().toString())
                .collect(ImmutableList.toImmutableList()))
        .containsExactly("//foo:foo", "//foo:bar", "//foo:baz")
        .inOrder();
  }

  @Test
  public void testPrecompiledFilesFromDifferentConfigs() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(':example_transition.bzl', 'transitioned_file')
        genrule(
           name = 'generated',
           outs = ['libbar.so'],
           cmd = 'echo foo > @',
        )
        transitioned_file(
           name = 'transitioned_libbar',
           src = 'generated',
        )
        cc_library(
           name = 'foo',
           srcs = [
               'generated',
               'transitioned_libbar',
           ],
        )
        """);
    scratch.file(
        "foo/example_transition.bzl",
        """
        def _impl(settings, attr):
            _ignore = (settings, attr)
            return [
                {'//command_line_option:foo': 'foo'},
            ]
        cpu_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ['//command_line_option:foo'],
        )
        def _transitioned_file_impl(ctx):
            return DefaultInfo(files = depset([ctx.file.src]))

        transitioned_file = rule(
            implementation = _transitioned_file_impl,
            attrs = {
                'src': attr.label(
                    allow_single_file = True,
                    cfg = cpu_transition,
                ),
            },
        )
        """);
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = 'function_transition_allowlist',
            packages = ['//...'],
        )
        filegroup(
            name = 'srcs',
            srcs = glob(['**']),
            visibility = ['//tools/allowlists:__pkg__'],
        )
        """);
    checkError("//foo", "Trying to link twice");
  }

  @Test
  public void testImplicitOutputsWhitelistOnWhitelist() throws Exception {
    if (analysisMock.isThisBazel()) {
      return;
    }
    scratch.overwriteFile(
        "tools/build_defs/cc/whitelists/cc_lib_implicit_outputs/BUILD",
        """
        package_group(
            name = 'allowed_cc_lib_implicit_outputs',
            packages = ['//bar'])
        """);

    scratch.file(
        "bar/BUILD",
        """
        filegroup(
            name = 'allowed',
            srcs = [':liballowed_cc_lib.a'],
        )
        cc_library(
            name = 'allowed_cc_lib',
            srcs = ['allowed_cc_lib.cc'],
        )
        """);
    getConfiguredTarget("//bar:allowed");
    assertNoEvents();
  }

  private void prepareCustomTransition() throws Exception {
    scratch.file(
        "transition/custom_transition.bzl",
        """
        def _custom_transition_impl(settings, attr):
            _ignore = settings, attr

            return {'//command_line_option:copt': ['-DFLAG']}

        custom_transition = transition(
            implementation = _custom_transition_impl,
            inputs = [],
            outputs = ['//command_line_option:copt'],
        )

        def _apply_custom_transition_impl(ctx):
            cc_infos = []
            for dep in ctx.attr.deps:
                cc_infos.append(dep[CcInfo])
            merged_cc_info = cc_common.merge_cc_infos(cc_infos = cc_infos)
            return merged_cc_info

        apply_custom_transition = rule(
            implementation = _apply_custom_transition_impl,
            attrs = {
                'deps': attr.label_list(cfg = custom_transition),
            },
        )
        """);
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = 'function_transition_allowlist',
            packages = ['//...'],
        )
        filegroup(
            name = 'srcs',
            srcs = glob(['**']),
            visibility = ['//tools/allowlists:__pkg__'],
        )
        """);
  }

  @Test
  public void testDynamicLinkTwiceAfterTransition() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));

    prepareCustomTransition();

    scratch.file(
        "transition/BUILD",
        """
        load(':custom_transition.bzl', 'apply_custom_transition')
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            linkstatic = 0,
            deps = [
                'dep1',
                'dep2',
            ],
        )

        apply_custom_transition(
            name = 'dep1',
            deps = [
                ':dep2',
            ],
        )

        cc_library(
            name = 'dep2',
            srcs = ['test.cc'],
            hdrs = ['test.h'],
        )
        """);

    checkError("//transition:main", "built in a different configuration");
  }

  @Test
  public void testDynamicLinkUniqueAfterTransition() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));

    prepareCustomTransition();

    scratch.file(
        "transition/BUILD",
        """
        load(':custom_transition.bzl', 'apply_custom_transition')
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            linkstatic = 0,
            deps = [
                'dep1',
                'dep3',
            ],
        )
        apply_custom_transition(
            name = 'dep1',
            deps = [
                ':dep2',
            ],
        )
        cc_library(
            name = 'dep2',
            srcs = ['test.cc'],
            hdrs = ['test.h'],
        )
        cc_library(
            name = 'dep3',
            srcs = ['other_test.cc'],
            hdrs = ['other_test.h'],
        )
        """);

    getConfiguredTarget("//transition:main");
    assertNoEvents();
  }

  // b/162180592
  @Test
  public void testSameSymlinkedLibraryDoesNotGiveDuplicateError() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));

    scratch.file(
        "transition/BUILD",
        """
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            deps = [
                'dep1',
                'dep2',
            ],
        )
        cc_binary(
            name = 'libshared.so',
            srcs = ['shared.cc'],
            linkshared = 1,
        )
        cc_library(
            name = 'dep1',
            srcs = ['test.cc', 'libshared.so'],
            hdrs = ['test.h'],
        )
        cc_library(
            name = 'dep2',
            srcs = ['other_test.cc', 'libshared.so'],
            hdrs = ['other_test.h'],
        )
        """);

    getConfiguredTarget("//transition:main");
    assertNoEvents();
  }

  @Test
  public void testImplementationDepsCompilationContextIsNotPropagated() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        cc_binary(
            name = 'bin',
            srcs = ['bin.cc'],
            deps = ['lib'],
        )
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            deps = ['public_dep'],
        )
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            includes = ['public_dep'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
            deps = ['interface_dep'],
        )
        cc_library(
            name = 'interface_dep',
            srcs = ['interface_dep.cc'],
            includes = ['interface_dep'],
            hdrs = ['interface_dep.h'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            includes = ['implementation_dep'],
            hdrs = ['implementation_dep.h'],
        )
        """);

    CcCompilationContext libCompilationContext =
        getCppCompileAction("//foo:lib").getCcCompilationContext();
    assertThat(artifactsToStrings(libCompilationContext.getDeclaredIncludeSrcs()))
        .contains("src foo/public_dep.h");
    assertThat(artifactsToStrings(libCompilationContext.getDeclaredIncludeSrcs()))
        .contains("src foo/interface_dep.h");
    assertThat(artifactsToStrings(libCompilationContext.getDeclaredIncludeSrcs()))
        .doesNotContain("src foo/implementation_dep.h");

    assertThat(pathfragmentsToStrings(libCompilationContext.getSystemIncludeDirs()))
        .contains("foo/public_dep");
    assertThat(pathfragmentsToStrings(libCompilationContext.getSystemIncludeDirs()))
        .contains("foo/interface_dep");
    assertThat(pathfragmentsToStrings(libCompilationContext.getSystemIncludeDirs()))
        .doesNotContain("foo/implementation_dep");

    CcCompilationContext publicDepCompilationContext =
        getCppCompileAction("//foo:public_dep").getCcCompilationContext();
    assertThat(artifactsToStrings(publicDepCompilationContext.getDeclaredIncludeSrcs()))
        .contains("src foo/interface_dep.h");
    assertThat(pathfragmentsToStrings(publicDepCompilationContext.getSystemIncludeDirs()))
        .contains("foo/interface_dep");
    assertThat(artifactsToStrings(publicDepCompilationContext.getDeclaredIncludeSrcs()))
        .contains("src foo/implementation_dep.h");
    assertThat(pathfragmentsToStrings(publicDepCompilationContext.getSystemIncludeDirs()))
        .contains("foo/implementation_dep");
  }

  @Test
  public void testImplementationDepsLinkingContextIsPropagated() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        cc_binary(
            name = 'bin',
            srcs = ['bin.cc'],
            deps = ['lib'],
        )
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            deps = ['public_dep'],
        )
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
            deps = ['interface_dep'],
        )
        cc_library(
            name = 'interface_dep',
            srcs = ['interface_dep.cc'],
            hdrs = ['interface_dep.h'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        """);

    ConfiguredTarget lib = getConfiguredTarget("//foo:lib");
    assertThat(
            artifactsToStrings(
                lib.get(CcInfo.PROVIDER)
                    .getCcLinkingContext()
                    .getStaticModeParamsForExecutableLibraries()))
        .contains("bin foo/libpublic_dep.a");
    assertThat(
            artifactsToStrings(
                lib.get(CcInfo.PROVIDER)
                    .getCcLinkingContext()
                    .getStaticModeParamsForExecutableLibraries()))
        .contains("bin foo/libimplementation_dep.a");
  }

  @Test
  public void testImplementationDepsDebugContextIsPropagated() throws Exception {
    useConfiguration(
        "--fission=yes",
        "--features=per_object_debug_info");
    scratch.file(
        "foo/BUILD",
        """
        cc_binary(
            name = 'bin',
            srcs = ['bin.cc'],
            deps = ['lib'],
        )
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            deps = ['public_dep'],
        )
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
            deps = ['interface_dep'],
        )
        cc_library(
            name = 'interface_dep',
            srcs = ['interface_dep.cc'],
            hdrs = ['interface_dep.h'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        """);

    ConfiguredTarget lib = getConfiguredTarget("//foo:lib");
    assertThat(
            lib
                .get(CcInfo.PROVIDER)
                .getCcDebugInfoContext()
                .getTransitiveDwoFiles()
                .toList()
                .stream()
                .map(Artifact::getFilename))
        .contains("public_dep.dwo");
    assertThat(
            lib
                .get(CcInfo.PROVIDER)
                .getCcDebugInfoContext()
                .getTransitiveDwoFiles()
                .toList()
                .stream()
                .map(Artifact::getFilename))
        .contains("implementation_dep.dwo");
  }

  @Test
  public void testImplementationDepsRunfilesArePropagated() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        cc_binary(
            name = 'bin',
            srcs = ['bin.cc'],
            deps = ['lib'],
        )
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            deps = ['public_dep'],
        )
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
            deps = ['interface_dep'],
        )
        cc_library(
            name = 'interface_dep',
            data = ['data/interface.txt'],
        )
        cc_library(
            name = 'implementation_dep',
            data = ['data/implementation.txt'],
        )
        """);

    ConfiguredTarget lib = getConfiguredTarget("//foo:bin");
    assertThat(
            artifactsToStrings(
                lib.get(DefaultInfo.PROVIDER).getDefaultRunfiles().getAllArtifacts()))
        .containsAtLeast("src foo/data/interface.txt", "src foo/data/implementation.txt");
  }

  @Test
  public void testImplementationDepsConfigurationHostSucceeds() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        """);

    assertThat(getExecConfiguredTarget("//foo:public_dep")).isNotNull();
    ;
  }

  @Test
  public void testImplementationDepsSucceedsWithoutFlag() throws Exception {
    if (!analysisMock.isThisBazel()) {
      return;
    }
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            implementation_deps = ['implementation_dep'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        """);
    assertThat(getConfiguredTarget("//foo:lib")).isNotNull();
    ;
  }

  @Test
  public void testImplementationDepsNotInAllowlistThrowsError() throws Exception {
    if (analysisMock.isThisBazel()) {
      // In OSS usage is controlled only by a flag and not an allowlist.
      return;
    }
    scratch.overwriteFile(
        "tools/build_defs/cc/whitelists/implementation_deps/BUILD",
        """
        package_group(
            name = 'cc_library_implementation_deps_attr_allowed',
            packages = []
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            implementation_deps = ['implementation_dep'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:lib");
    assertContainsEvent("Only targets in the following allowlist");
  }

  @Test
  public void testCcLibraryProducesEmptyArchive() throws Exception {
    if (analysisMock.isThisBazel()) {
      return;
    }
    scratch.file("foo/BUILD", "cc_library(name = 'foo')");
    assertThat(
            getConfiguredTarget("//foo:foo")
                .getProvider(FileProvider.class)
                .getFilesToBuild()
                .toList())
        .isNotEmpty();
  }

  @Test
  public void testRpathIsNotAddedWhenThereAreNoSoDeps() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));

    prepareCustomTransition();

    scratch.file(
        "BUILD",
        "cc_library(",
        "    name = 'malloc',",
        "    srcs = ['malloc.cc'],",
        "    linkstatic = 1,",
        ")",
        "cc_library(name = 'empty_lib')",
        "cc_binary(",
        "    name = 'main',",
        "    srcs = ['main.cc'],",
        "    malloc = ':malloc',",
        "    link_extra_lib = ':empty_lib',",
        "    linkstatic = 0,",
        ")");

    ConfiguredTarget main = getConfiguredTarget("//:main");
    Artifact mainBin = getBinArtifact("main", main);
    SpawnAction action = (SpawnAction) getGeneratingAction(mainBin);
    assertThat(Joiner.on(" ").join(action.getArguments())).doesNotContain("-Xlinker -rpath");
  }

  @Test
  public void testRpathAndLinkPathsWithoutTransitions() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));

    prepareCustomTransition();
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--compilation_mode=fastbuild",
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));

    scratch.file(
        "no-transition/BUILD",
        """
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            linkstatic = 0,
            deps = ['dep1'],
        )

        cc_library(
            name = 'dep1',
            srcs = ['test.cc'],
            hdrs = ['test.h'],
        )
        """);

    ConfiguredTarget main = getConfiguredTarget("//no-transition:main");
    Artifact mainBin = getBinArtifact("main", main);
    SpawnAction action = (SpawnAction) getGeneratingAction(mainBin);
    List<String> linkArgv = action.getArguments();
    assertThat(linkArgv)
        .containsAtLeast("-Xlinker", "-rpath", "-Xlinker", "$ORIGIN/../_solib_k8/")
        .inOrder();
    assertThat(linkArgv)
        .containsAtLeast(
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            "$ORIGIN/main.runfiles/" + ruleClassProvider.getRunfilesPrefix() + "/_solib_k8/")
        .inOrder();
    assertThat(linkArgv)
        .contains("-L" + TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/_solib_k8");
    assertThat(linkArgv).contains("-lno-transition_Slibdep1");
    assertThat(Joiner.on(" ").join(linkArgv))
        .doesNotContain("-Xlinker -rpath -Xlinker $ORIGIN/../_solib_k8/../../../k8-fastbuild-ST-");
    assertThat(Joiner.on(" ").join(linkArgv))
        .doesNotContain("-L" + TestConstants.PRODUCT_NAME + "-out/k8-fastbuild-ST-");
    assertThat(Joiner.on(" ").join(linkArgv)).doesNotContain("-lST-");
  }

  @Test
  public void testRpathRootIsAddedEvenWithTransitionedDepsOnly() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));

    prepareCustomTransition();
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--compilation_mode=fastbuild",
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));

    scratch.file(
        "transition/BUILD",
        """
        load(':custom_transition.bzl', 'apply_custom_transition')
        cc_library(
            name = 'malloc',
            srcs = ['malloc.cc'],
            linkstatic = 1,
        )
        cc_library(name = 'empty_lib')
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            linkstatic = 0,
            malloc = ':malloc',
            link_extra_lib = ':empty_lib',
            deps = ['dep1'],
        )

        apply_custom_transition(
            name = 'dep1',
            deps = [
                ':dep2',':dep3',
            ],
        )

        cc_library(
            name = 'dep2',
            srcs = ['test.cc'],
            hdrs = ['test.h'],
        )
        cc_library(
            name = 'dep3',
            srcs = ['test3.cc'],
            hdrs = ['test3.h'],
        )
        """);

    ConfiguredTarget main = getConfiguredTarget("//transition:main");
    Artifact mainBin = getBinArtifact("main", main);
    SpawnAction action = (SpawnAction) getGeneratingAction(mainBin);
    List<String> linkArgv = action.getArguments();
    assertThat(linkArgv)
        .containsAtLeast("-Xlinker", "-rpath", "-Xlinker", "$ORIGIN/../_solib_k8/")
        .inOrder();
    assertThat(linkArgv)
        .containsAtLeast(
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            "$ORIGIN/main.runfiles/" + ruleClassProvider.getRunfilesPrefix() + "/_solib_k8/")
        .inOrder();
    assertThat(Joiner.on(" ").join(linkArgv))
        .contains("-Xlinker -rpath -Xlinker $ORIGIN/../../../k8-fastbuild-ST-");
    assertThat(Joiner.on(" ").join(linkArgv))
        .contains("-L" + TestConstants.PRODUCT_NAME + "-out/k8-fastbuild-ST-");
    assertThat(Joiner.on(" ").join(linkArgv)).containsMatch("-lST-[0-9a-f]+_transition_Slibdep2");
    assertThat(Joiner.on(" ").join(linkArgv))
        .doesNotContain("-L" + TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/_solib_k8");
    assertThat(Joiner.on(" ").join(linkArgv)).doesNotContain("-ltransition_Slibdep2");
  }

  /**
   * Due to Windows forcing every dynamic library to link its dependencies, the
   * NODEPS_DYNAMIC_LIBRARY link target type actually does link in its transitive dependencies
   * statically on Windows. There is no reason why these cc_libraries should be link stamped.
   */
  @Test
  public void testWindowsCcLibrariesNoDepsDynamicLibrariesDoNotLinkstamp() throws Exception {
    scratch.overwriteFile(
        "hello/BUILD",
        """
        cc_library(
          name = 'hello',
          srcs = ['hello.cc'],
          deps = ['linkstamp']
        )
        cc_library(
          name = 'linkstamp',
          linkstamp = 'linkstamp.cc',
        )
        """);
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.TARGETS_WINDOWS,
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY));
    ConfiguredTarget hello = getConfiguredTarget("//hello:hello");
    Artifact sharedObject =
        hello
            .get(CcInfo.PROVIDER)
            .getCcLinkingContext()
            .getLinkerInputs()
            .toList()
            .get(0)
            .getLibraries()
            .get(0)
            .getDynamicLibrary();
    SpawnAction action = (SpawnAction) getGeneratingAction(sharedObject);
    assertThat(artifactsToStrings(action.getInputs()))
        .doesNotContain("bin hello/_objs/bin/hello/linkstamp.o");
  }

  @Test
  public void testReallyLongSolibLink() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));

    String longpath =
        "this/is/a/really/really/really/really/really/really/really/really/really/really/"
            + "really/really/really/really/really/really/really/really/really/really/really/"
            + "really/really/long/path/that/generates/really/long/solib/link/file";
    scratch.file(
        longpath + "/BUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = ['lib.cc'],",
        "    linkstatic = 0,",
        ")");

    ConfiguredTarget lib = getConfiguredTarget("//" + longpath + ":lib");
    List<Artifact> libraries =
        lib.get(CcInfo.PROVIDER)
            .getCcLinkingContext()
            .getDynamicLibrariesForRuntime(/* linkingStatically= */ false);
    List<String> libraryBaseNames = ActionsTestUtil.baseArtifactNames(libraries);
    for (String baseName : libraryBaseNames) {
      assertThat(baseName.length()).isLessThan(MAX_FILENAME_LENGTH + 1);
    }
  }

  @Test
  public void testLinkerInputAlwaysAddedEvenIfEmpty() throws Exception {
    AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig);
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'lib',
        )
        """);
    assertThat(
            getConfiguredTarget("//foo:lib")
                .get(CcInfo.PROVIDER)
                .getCcLinkingContext()
                .getLinkerInputs()
                .toList()
                .stream()
                .map(x -> x.getOwner().toString()))
        .containsExactly("//foo:lib")
        .inOrder();
  }

  @Test
  public void testDataDepRunfilesArePropagated() throws Exception {
    AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig);
    scratch.file(
        "foo/data_dep.bzl",
        """
        def _my_data_dep_impl(ctx):
            return [
               DefaultInfo(
                runfiles = ctx.runfiles(
                     root_symlinks = { ctx.attr.dst: ctx.files.src[0] },
               ),
             )
           ]
        my_data_dep = rule(
           implementation = _my_data_dep_impl,
           attrs = {
             'src': attr.label(mandatory = True, allow_single_file = True),
             'dst': attr.string(mandatory = True),
           },
         )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(':data_dep.bzl', 'my_data_dep')
        my_data_dep(
            name = 'data_dep',
            src = ':file.txt',
            dst = 'data/file.txt',
        )
        cc_library(
            name = 'lib',
            data = [':data_dep'],
        )
        """);

    ConfiguredTarget lib = getConfiguredTarget("//foo:lib");
    assertThat(
            artifactsToStrings(
                lib.get(DefaultInfo.PROVIDER).getDefaultRunfiles().getAllArtifacts()))
        .containsExactly("src foo/file.txt");
  }

  @Test
  public void testAdditionalCompilerInputsArePassedToCompile() throws Exception {
    AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig);
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'foo',
            srcs = ['hello.cc'],
            copts = ['$(location compiler_input.txt)'],
            additional_compiler_inputs = ['compiler_input.txt'],
        )
        """);
    scratch.file("foo/compiler_input.txt", "hello world!");

    ConfiguredTarget lib = getConfiguredTarget("//foo:foo");
    Artifact artifact = getBinArtifact("_objs/foo/hello.o", lib);
    CppCompileAction action = (CppCompileAction) getGeneratingAction(artifact);
    assertThat(action.getInputs().toList()).contains(getSourceArtifact("foo/compiler_input.txt"));
    assertThat(action.getArguments()).contains("foo/compiler_input.txt");
  }

  @Test
  public void testAdditionalCompilerInputsArePassedToCompileFromLocalDefines() throws Exception {
    AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig);
    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = 'foo',
            srcs = ['hello.cc'],
            local_defines = ['FOO=$(location compiler_input.txt)'],
            additional_compiler_inputs = ['compiler_input.txt'],
        )
        """);
    scratch.file("foo/compiler_input.txt", "hello world!");

    ConfiguredTarget lib = getConfiguredTarget("//foo:foo");
    Artifact artifact = getBinArtifact("_objs/foo/hello.o", lib);
    CppCompileAction action = (CppCompileAction) getGeneratingAction(artifact);
    assertThat(action.getInputs().toList()).contains(getSourceArtifact("foo/compiler_input.txt"));
    assertThat(action.getArguments()).contains("-DFOO=foo/compiler_input.txt");
  }
}
