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

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.baseArtifactNames;
import static com.google.devtools.build.lib.rules.objc.CompilationSupport.ABSOLUTE_INCLUDES_PATH_FORMAT;
import static com.google.devtools.build.lib.rules.objc.CompilationSupport.BOTH_MODULE_NAME_AND_MODULE_MAP_SPECIFIED;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.ScratchAttributeWriter;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.util.MockObjcSupport;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.LinkerInput;
import com.google.devtools.build.lib.rules.cpp.CcToolchainProvider;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction;
import com.google.devtools.build.lib.rules.cpp.CppRuleClasses;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test case for objc_library.
 *
 * <p>TODO(b/322845822): remove uses of `--cpu=k8` in tests.
 */
@RunWith(JUnit4.class)
public class ObjcLibraryTest extends ObjcRuleTestCase {

  private static final RuleType RULE_TYPE = new OnlyNeedsSourcesRuleType("objc_library");
  private static final String WRAPPED_CLANG = "wrapped_clang";

  @Test
  public void testConfigTransitionWithTopLevelAppleConfiguration() throws Exception {
    scratch.file(
        "bin/BUILD",
        """
        objc_library(
            name = "objc",
            srcs = ["objc.m"],
        )

        cc_binary(
            name = "cc",
            srcs = ["cc.cc"],
            deps = [":objc"],
        )
        """);

    setBuildLanguageOptions("--noincompatible_disable_objc_library_transition");
    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=x86_64",
        "--platforms=" + MockObjcSupport.IOS_X86_64,
        "--experimental_platform_in_output_dir");

    ConfiguredTarget cc = getConfiguredTarget("//bin:cc");
    Artifact objcObject =
        ActionsTestUtil.getFirstArtifactEndingWith(
            actionsTestUtil().artifactClosureOf(getFilesToBuild(cc)), "objc.o");
    assertThat(objcObject.getExecPathString()).contains("ios_x86_64");
  }

  @Test
  public void testNoTransition() throws Exception {
    scratch.file(
        "bin/BUILD",
        """
        objc_library(
            name = "objc",
            srcs = ["objc.m"],
        )

        cc_binary(
            name = "cc",
            srcs = ["cc.cc"],
            deps = [":objc"],
        )
        """);

    setBuildLanguageOptions("--incompatible_disable_objc_library_transition");
    useConfiguration("--macos_cpus=arm64,x86_64", "--cpu=k8");
    ConfiguredTarget cc = getConfiguredTarget("//bin:cc");
    Artifact objcObject =
        ActionsTestUtil.getFirstArtifactEndingWith(
            actionsTestUtil().artifactClosureOf(getFilesToBuild(cc)), "objc.o");
    assertThat(objcObject.getExecPathString()).contains("k8-fastbuild");
  }

  @Test
  public void testFilesToBuild() throws Exception {
    ConfiguredTarget target =
        createLibraryTargetWriter("//objc:One")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .write();

    NestedSet<Artifact> files = getFilesToBuild(target);
    assertThat(Artifact.toRootRelativePaths(files)).containsExactly("objc/libOne.a");
  }

  @Test
  public void testCompilesSources() throws Exception {
    createLibraryTargetWriter("//objc/lib1")
        .setAndCreateFiles("srcs", "a.m")
        .setAndCreateFiles("hdrs", "hdr.h")
        .write();

    createLibraryTargetWriter("//objc/lib2")
        .setAndCreateFiles("srcs", "b.m")
        .setAndCreateFiles("hdrs", "private.h")
        .write();

    createLibraryTargetWriter("//objc/lib3")
        .setAndCreateFiles("srcs", "a.m")
        .setAndCreateFiles("hdrs", "hdr.h")
        .setList("deps", "//objc/lib1")
        .setList("implementation_deps", "//objc/lib2")
        .write();

    createLibraryTargetWriter("//objc:x")
        .setAndCreateFiles("srcs", "a.m", "private.h")
        .setAndCreateFiles("hdrs", "hdr.h")
        .setList("deps", "//objc/lib3:lib3")
        .write();

    CppCompileAction compileA = (CppCompileAction) compileAction("//objc:x", "a.o");

    assertThat(Artifact.toRootRelativePaths(compileA.getPossibleInputsForTesting()))
        .containsAtLeast("objc/a.m", "objc/hdr.h", "objc/private.h");
    assertThat(Artifact.toRootRelativePaths(compileA.getOutputs()))
        .containsExactly("objc/_objs/x/arc/a.o", "objc/_objs/x/arc/a.d");
  }

  @Test
  public void testSerializedDiagnosticsFileFeature() throws Exception {
    useConfiguration("--features=serialized_diagnostics_file");

    createLibraryTargetWriter("//objc/lib1")
        .setAndCreateFiles("srcs", "a.m")
        .setAndCreateFiles("hdrs", "hdr.h")
        .write();

    createLibraryTargetWriter("//objc/lib2")
        .setAndCreateFiles("srcs", "a.m")
        .setAndCreateFiles("hdrs", "hdr.h")
        .setList("deps", "//objc/lib1")
        .write();

    createLibraryTargetWriter("//objc:x")
        .setAndCreateFiles("srcs", "a.m", "private.h")
        .setAndCreateFiles("hdrs", "hdr.h")
        .setList("deps", "//objc/lib2:lib2")
        .write();

    CppCompileAction compileA = (CppCompileAction) compileAction("//objc:x", "a.o");

    assertThat(Artifact.toRootRelativePaths(compileA.getOutputs()))
        .containsExactly("objc/_objs/x/arc/a.o", "objc/_objs/x/arc/a.d", "objc/_objs/x/arc/a.dia");
  }

  @Test
  public void testCompilesSourcesWithSameBaseName() throws Exception {
    createLibraryTargetWriter("//foo:lib")
        .setAndCreateFiles("srcs", "a.m", "pkg1/a.m", "b.m")
        .setAndCreateFiles("non_arc_srcs", "pkg2/a.m")
        .write();

    getConfiguredTarget("//foo:lib");

    Artifact a0 = getBinArtifact("_objs/lib/arc/0/a.o", getConfiguredTarget("//foo:lib"));
    Artifact a1 = getBinArtifact("_objs/lib/arc/1/a.o", getConfiguredTarget("//foo:lib"));
    Artifact a2 = getBinArtifact("_objs/lib/non_arc/a.o", getConfiguredTarget("//foo:lib"));
    Artifact b = getBinArtifact("_objs/lib/arc/b.o", getConfiguredTarget("//foo:lib"));

    assertThat(getGeneratingAction(a0)).isNotNull();
    assertThat(getGeneratingAction(a1)).isNotNull();
    assertThat(getGeneratingAction(a2)).isNotNull();
    assertThat(getGeneratingAction(b)).isNotNull();

    assertThat(getGeneratingAction(a0).getInputs().toList()).contains(getSourceArtifact("foo/a.m"));
    assertThat(getGeneratingAction(a1).getInputs().toList())
        .contains(getSourceArtifact("foo/pkg1/a.m"));
    assertThat(getGeneratingAction(a2).getInputs().toList())
        .contains(getSourceArtifact("foo/pkg2/a.m"));
    assertThat(getGeneratingAction(b).getInputs().toList()).contains(getSourceArtifact("foo/b.m"));
  }

  @Test
  public void testObjcPlusPlusCompile() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=arm64",
        "--platforms=" + MockObjcSupport.IOS_ARM64);
    createLibraryTargetWriter("//objc:lib").setList("srcs", "a.mm").write();
    CommandAction compileAction = compileAction("//objc:lib", "a.o");
    assertThat(compileAction.getArguments()).containsAtLeast("-stdlib=libc++", "-std=gnu++11");
  }

  @Test
  public void testObjcPlusPlusCompileDarwin() throws Exception {
    useConfiguration(
        "--cpu=darwin_x86_64",
        // TODO(b/36126423): Darwin should imply macos, so the
        // following line should not be necessary.
        "--apple_platform_type=macos",
        "--platforms=" + MockObjcSupport.DARWIN_X86_64);
    createLibraryTargetWriter("//objc:lib").setList("srcs", "a.mm").write();
    CommandAction compileAction = compileAction("//objc:lib", "a.o");
    assertThat(compileAction.getArguments()).containsAtLeast("-stdlib=libc++", "-std=gnu++11");
  }

  @Test
  public void testObjcSourceContainsObjccopt() throws Exception {
    useConfiguration("--objccopt=--xyzzy");
    scratch.file("objc/a.m");
    scratch.file("objc/BUILD", RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.m']"));

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");
    assertThat(compileActionA.getArguments()).contains("--xyzzy");
  }

  @Test
  public void testObjcppSourceContainsObjccopt() throws Exception {
    useConfiguration("--objccopt=--xyzzy");
    scratch.file("objc/a.mm");
    scratch.file("objc/BUILD", RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.mm']"));

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");
    assertThat(compileActionA.getArguments()).contains("--xyzzy");
  }

  @Test
  public void testCSourceDoesNotContainObjccopt() throws Exception {
    useConfiguration("--objccopt=--xyzzy");
    scratch.file("objc/a.c");
    scratch.file("objc/BUILD", RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.c']"));

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");
    assertThat(compileActionA.getArguments()).doesNotContain("--xyzzy");
  }

  @Test
  public void testCppSourceDoesNotContainObjccopt() throws Exception {
    useConfiguration("--objccopt=--xyzzy");
    scratch.file("objc/a.cc");
    scratch.file("objc/BUILD", RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.cc']"));

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");
    assertThat(compileActionA.getArguments()).doesNotContain("--xyzzy");
  }

  @Test
  public void testCppHeaderDoesNotContainsObjccopt() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration(
        "--features=parse_headers", "--process_headers_in_dependencies", "--objccopt=--xyzzy");

    ConfiguredTarget x =
        scratchConfiguredTarget("foo", "x", "cc_library(name = 'x', hdrs = ['x.h'])");

    assertThat(getGeneratingCompileAction("_objs/x/x.h.processed", x).getArguments())
        .doesNotContain("--xyzzy");
  }

  @Test
  public void testObjcHeaderContainsObjccopt() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration(
        "--features=parse_headers", "--process_headers_in_dependencies", "--objccopt=--xyzzy");

    ConfiguredTarget x =
        scratchConfiguredTarget("foo", "x", "objc_library(name = 'x', hdrs = ['x.h'])");

    assertThat(getGeneratingCompileAction("_objs/x/arc/x.h.processed", x).getArguments())
        .contains("--xyzzy");
  }

  @Test
  public void testCompilationModeDbg() throws Exception {
    useConfiguration("--ios_multi_cpus=arm64", "--compilation_mode=dbg");
    scratch.file("objc/a.m");
    scratch.file("objc/BUILD", RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.m']"));

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");

    assertThat(compileActionA.getArguments()).contains("--DBG_ONLY_FLAG");
    assertThat(compileActionA.getArguments()).doesNotContain("--FASTBUILD_ONLY_FLAG");
    assertThat(compileActionA.getArguments()).doesNotContain("--OPT_ONLY_FLAG");
  }

  @Test
  public void testCompilationModeFastbuild() throws Exception {
    useConfiguration("--compilation_mode=fastbuild");
    scratch.file("objc/a.m");
    scratch.file("objc/BUILD", RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.m']"));

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");

    assertThat(compileActionA.getArguments()).doesNotContain("--DBG_ONLY_FLAG");
    assertThat(compileActionA.getArguments()).contains("--FASTBUILD_ONLY_FLAG");
    assertThat(compileActionA.getArguments()).doesNotContain("--OPT_ONLY_FLAG");
  }

  @Test
  public void testCompilationModeOpt() throws Exception {
    useConfiguration("--compilation_mode=opt");
    scratch.file("objc/a.m");
    scratch.file("objc/BUILD", RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.m']"));

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");

    assertThat(compileActionA.getArguments()).doesNotContain("--DBG_ONLY_FLAG");
    assertThat(compileActionA.getArguments()).doesNotContain("--FASTBUILD_ONLY_FLAG");
    assertThat(compileActionA.getArguments()).contains("--OPT_ONLY_FLAG");
  }

  @Test
  public void testCreate_runfilesWithSourcesOnly() throws Exception {
    ConfiguredTarget target =
        createLibraryTargetWriter("//objc:One")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .write();
    RunfilesProvider provider = target.getProvider(RunfilesProvider.class);
    assertThat(baseArtifactNames(provider.getDefaultRunfiles().getArtifacts())).isEmpty();
    assertThat(Artifact.toRootRelativePaths(provider.getDataRunfiles().getArtifacts()))
        .containsExactly("objc/libOne.a");
  }

  @Test
  public void testCreate_noErrorForEmptySourcesButHasDependency() throws Exception {
    createLibraryTargetWriter("//baselib:baselib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .write();
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("hdrs", "a.h")
        .setList("deps", "//baselib:baselib")
        .write();

    CcLinkingContext ccLinkingContext =
        getConfiguredTarget("//lib:lib").get(CcInfo.PROVIDER).getCcLinkingContext();
    assertThat(ccLinkingContext.getStaticModeParamsForDynamicLibraryLibraries())
        .containsExactlyElementsIn(archiveAction("//baselib:baselib").getOutputs());
  }

  @Test
  public void testCreate_errorForEmptyFilegroupSources() throws Exception {
    checkError(
        "x",
        "x",
        "does not produce any objc_library srcs files",
        "filegroup(name = 'fg', srcs = [])",
        "objc_library(name = 'x', srcs = ['fg'])");
  }

  @Test
  public void testCreate_srcsContainingHeaders() throws Exception {
    scratch.file("x/a.m", "dummy source file");
    scratch.file("x/a.h", "dummy header file");
    scratch.file("x/BUILD", "objc_library(name = 'Target', srcs = ['a.m', 'a.h'])");
    assertThat(view.hasErrors(getConfiguredTarget("//x:Target"))).isFalse();
  }

  @Test
  public void testCreate_headerAndCompiledSourceWithSameName() throws Exception {
    scratch.file("objc/BUILD", "objc_library(name = 'Target', srcs = ['a.m'], hdrs = ['a.h'])");
    assertThat(view.hasErrors(getConfiguredTarget("//objc:Target"))).isFalse();
  }

  @Test
  public void testCreate_errorForCcInNonArcSources() throws Exception {
    scratch.file("x/cc.cc");
    checkError(
        "x",
        "x",
        "non_arc_srcs attribute of objc_library rule @@//x:x: source file '@@//x:cc.cc' is"
            + " misplaced here",
        "objc_library(name = 'x', non_arc_srcs = ['cc.cc'])");
  }

  @Test
  public void testFileInSrcsAndNonArcSources() throws Exception {
    checkError(
        "x",
        "x",
        String.format(CompilationSupport.FILE_IN_SRCS_AND_NON_ARC_SRCS_ERROR_FORMAT, "x/foo.m"),
        "objc_library(name = 'x', srcs = ['foo.m'], non_arc_srcs = ['foo.m'])");
  }

  @Test
  public void testCreate_headerContainingDotMAndDotCFiles() throws Exception {
    scratch.file("x/a.m", "dummy source file");
    scratch.file("x/a.h", "dummy header file");
    scratch.file("x/b.m", "dummy source file");
    scratch.file("x/a.c", "dummy source file");
    scratch.file(
        "x/BUILD", "objc_library(name = 'Target', srcs = ['a.m'], hdrs = ['a.h', 'b.m', 'a.c'])");
    assertThat(view.hasErrors(getConfiguredTarget("//x:Target"))).isFalse();
  }

  @Test
  public void testProvidesObjcHeadersWithDotMFiles() throws Exception {
    ConfiguredTarget target =
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "a.h", "b.h", "f.m")
            .write();
    ConfiguredTarget depender =
        createLibraryTargetWriter("//objc2:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "d.h", "e.m")
            .setList("deps", "//objc:lib")
            .write();
    assertThat(getArifactPathsOfHeaders(target))
        .containsExactly("objc/a.h", "objc/b.h", "objc/f.m", "objc/private.h");
    assertThat(getArifactPathsOfHeaders(depender))
        .containsExactly(
            "objc/a.h",
            "objc/b.h",
            "objc/f.m",
            "objc/private.h",
            "objc2/d.h",
            "objc2/e.m",
            "objc2/private.h");
  }

  @Test
  public void testMultiPlatformLibrary() throws Exception {
    useConfiguration("--ios_multi_cpus=arm64,x86_64,arm64e,sim_arm64");

    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setAndCreateFiles("hdrs", "a.h")
        .write();

    assertThat(view.hasErrors(getConfiguredTarget("//objc:lib"))).isFalse();
  }

  @Test
  public void testCompilationActions_simulator() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=x86_64",
        "--platforms=" + MockObjcSupport.IOS_X86_64);

    scratch.file("objc/a.m");
    scratch.file("objc/non_arc.m");
    scratch.file("objc/private.h");
    scratch.file("objc/c.h");
    scratch.file(
        "objc/BUILD",
        RULE_TYPE.target(
            scratch,
            "objc",
            "lib",
            "srcs",
            "['a.m', 'private.h']",
            "hdrs",
            "['c.h']",
            "non_arc_srcs",
            "['non_arc.m']"));

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");
    CommandAction compileActionNonArc = compileAction("//objc:lib", "non_arc.o");

    assertRequiresDarwin(compileActionA);
    assertThat(compileActionA.getArguments())
        .contains("tools/osx/crosstool/iossim/" + WRAPPED_CLANG);
    assertThat(compileActionA.getArguments())
        .containsAtLeast("-isysroot", "__BAZEL_XCODE_SDKROOT__")
        .inOrder();
    assertThat(compileActionA.getArguments())
        .containsAtLeastElementsIn(CompilationSupport.DEFAULT_COMPILER_FLAGS);
    assertThat(compileActionA.getArguments())
        .containsAtLeastElementsIn(CompilationSupport.SIMULATOR_COMPILE_FLAGS);
    assertThat(compileActionA.getArguments()).contains("-fobjc-arc");
    assertThat(compileActionA.getArguments()).containsAtLeast("-c", "objc/a.m");
    assertThat(compileActionNonArc.getArguments()).contains("-fno-objc-arc");
    assertThat(compileActionA.getArguments()).containsAtLeastElementsIn(FASTBUILD_COPTS);
    assertThat(compileActionA.getArguments()).contains("-arch x86_64");
  }

  @Test
  public void testCompilationActions_device() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=arm64",
        "--platforms=" + MockObjcSupport.IOS_ARM64);

    scratch.file("objc/a.m");
    scratch.file("objc/non_arc.m");
    scratch.file("objc/private.h");
    scratch.file("objc/c.h");
    scratch.file(
        "objc/BUILD",
        RULE_TYPE.target(
            scratch,
            "objc",
            "lib",
            "srcs",
            "['a.m', 'private.h']",
            "hdrs",
            "['c.h']",
            "non_arc_srcs",
            "['non_arc.m']"));

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");
    CommandAction compileActionNonArc = compileAction("//objc:lib", "non_arc.o");

    assertRequiresDarwin(compileActionA);
    assertThat(compileActionA.getArguments()).contains("tools/osx/crosstool/ios/" + WRAPPED_CLANG);
    assertThat(compileActionA.getArguments())
        .containsAtLeast("-isysroot", "__BAZEL_XCODE_SDKROOT__")
        .inOrder();
    assertThat(compileActionA.getArguments())
        .containsAtLeastElementsIn(CompilationSupport.DEFAULT_COMPILER_FLAGS);
    assertThat(compileActionA.getArguments())
        .containsNoneIn(CompilationSupport.SIMULATOR_COMPILE_FLAGS);

    assertThat(compileActionA.getArguments()).contains("-fobjc-arc");
    assertThat(compileActionA.getArguments()).containsAtLeast("-c", "objc/a.m");

    assertThat(compileActionNonArc.getArguments()).contains("-fno-objc-arc");
    assertThat(compileActionA.getArguments()).containsAtLeastElementsIn(FASTBUILD_COPTS);
    assertThat(compileActionA.getArguments()).contains("-arch arm64");
  }

  @Test
  public void testArchivesPrecompiledObjectFiles() throws Exception {
    scratch.file("objc/a.m");
    scratch.file("objc/b.o");
    scratch.file("objc/BUILD", RULE_TYPE.target(scratch, "objc", "x", "srcs", "['a.m', 'b.o']"));
    assertThat(Artifact.toRootRelativePaths(archiveAction("//objc:x").getInputs()))
        .contains("objc/b.o");
  }

  @Test
  public void testCompileWithFrameworkImportsIncludesFlags() throws Exception {
    addAppleBinaryStarlarkRule(scratch);
    addBinWithTransitiveDepOnFrameworkImport();
    CommandAction compileAction = compileAction("//lib:lib", "a.o");

    assertThat(compileAction.getArguments()).doesNotContain("-framework");
    assertThat(Joiner.on("").join(compileAction.getArguments())).contains("-Ffx");
  }

  @Test
  public void testPrecompiledHeaders() throws Exception {
    scratch.file("objc/a.m");
    scratch.file("objc/c.pch");
    scratch.file(
        "objc/BUILD",
        RULE_TYPE.target(
            scratch, "objc", "x", "srcs", "['a.m']", "non_arc_srcs", "['b.m']", "pch", "'c.pch'"));
    CppCompileAction compileAction = (CppCompileAction) compileAction("//objc:x", "a.o");
    assertThat(Joiner.on(" ").join(compileAction.getArguments())).contains("-include objc/c.pch");
    assertThat(Artifact.toRootRelativePaths(compileAction.getPossibleInputsForTesting()))
        .contains("objc/c.pch");
  }

  @Test
  public void testCompilationActionsWithCopts() throws Exception {
    useConfiguration("--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_ARM64);

    scratch.file(
        "objc/defs.bzl",
        """
        def _var_providing_rule_impl(ctx):
            return [
                platform_common.TemplateVariableInfo({
                    "FOO": "$(BAR)",
                    "BAR": ctx.attr.var_value,
                    "BAZ": "$(FOO)",
                }),
            ]

        var_providing_rule = rule(
            implementation = _var_providing_rule_impl,
            attrs = {"var_value": attr.string()},
        )
        """);

    scratch.file(
        "objc/BUILD",
        """
        load("//objc:defs.bzl", "var_providing_rule")

        var_providing_rule(
            name = "set_foo_to_bar",
            var_value = "bar",
        )

        objc_library(
            name = "lib",
            srcs = [
                "a.m",
                "b.m",
                "private.h",
            ],
            hdrs = ["c.h"],
            copts = [
                "-Ifoo",
                "--monkeys=enabled",
                "--gorillas=$(FOO),$(BAR),$(BAZ)",
            ],
            toolchains = [":set_foo_to_bar"],
        )
        """);

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");
    assertThat(compileActionA.getArguments())
        .containsAtLeast("-Ifoo", "--monkeys=enabled", "--gorillas=bar,bar,bar");
  }

  @Test
  public void testObjcCopts() throws Exception {
    useConfiguration("--objccopt=-foo");
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .write();
    List<String> args = compileAction("//lib:lib", "a.o").getArguments();
    assertThat(args).contains("-foo");
  }

  @Test
  public void testObjcCopts_argumentOrdering() throws Exception {
    useConfiguration("--objccopt=-foo");
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("copts", "-bar")
        .write();
    List<String> args = compileAction("//lib:lib", "a.o").getArguments();
    assertThat(args).containsAtLeast("-fobjc-arc", "-foo", "-bar").inOrder();
  }

  @Test
  public void testObjcCxxopts_argumentOrdering() throws Exception {
    useConfiguration("--objccopt=-foo", "--cxxopt=-cxxfoo");
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.mm", "b.m", "private.h")
        .setList("copts", "-bar")
        .write();
    List<String> aArgs = compileAction("//lib:lib", "a.o").getArguments();
    assertThat(aArgs).containsAtLeast("-fobjc-arc", "-cxxfoo", "-foo", "-bar").inOrder();
    List<String> bArgs = compileAction("//lib:lib", "b.o").getArguments();
    assertThat(bArgs).containsAtLeast("-fobjc-arc", "-foo", "-bar").inOrder();
    assertThat(bArgs).doesNotContain("-cxxfoo");
  }

  @Test
  public void testMultipleLanguagesCopts() throws Exception {
    useConfiguration("--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_ARM64);

    scratch.file(
        "objc/defs.bzl",
        """
        def _var_providing_rule_impl(ctx):
            return [
                platform_common.TemplateVariableInfo({
                    "FOO": "$(BAR)",
                    "BAR": ctx.attr.var_value,
                    "BAZ": "$(FOO)",
                }),
            ]

        var_providing_rule = rule(
            implementation = _var_providing_rule_impl,
            attrs = {"var_value": attr.string()},
        )
        """);

    scratch.file(
        "objc/BUILD",
        """
        load("//objc:defs.bzl", "var_providing_rule")

        var_providing_rule(
            name = "set_foo_to_bar",
            var_value = "bar",
        )

        objc_library(
            name = "lib",
            srcs = [
                "c.c",
                "cpp.cpp",
                "objc.m",
                "objcpp.mm",
            ],
            copts = ["-DFROM_SHARED=$(FOO),$(BAR),$(BAZ)"],
            conlyopts = ["-DFROM_CONLYOPTS=$(FOO),$(BAR),$(BAZ)"],
            cxxopts = ["-DFROM_CXXOPTS=$(FOO),$(BAR),$(BAZ)"],
            toolchains = [":set_foo_to_bar"],
        )
        """);

    CommandAction cCompileAction = compileAction("//objc:lib", "c.o");
    assertThat(cCompileAction.getArguments())
        .containsAtLeast("-DFROM_SHARED=bar,bar,bar", "-DFROM_CONLYOPTS=bar,bar,bar")
        .inOrder();
    assertThat(cCompileAction.getArguments()).doesNotContain("-DFROM_CXXOPTS=bar,bar,bar");

    CommandAction objcCompileAction = compileAction("//objc:lib", "objc.o");
    assertThat(objcCompileAction.getArguments()).contains("-DFROM_SHARED=bar,bar,bar");
    assertThat(objcCompileAction.getArguments()).doesNotContain("-DFROM_CONLYOPTS=bar,bar,bar");
    assertThat(objcCompileAction.getArguments()).doesNotContain("-DFROM_CXXOPTS=bar,bar,bar");

    CommandAction objcppCompileAction = compileAction("//objc:lib", "objcpp.o");
    assertThat(objcppCompileAction.getArguments())
        .containsAtLeast("-DFROM_SHARED=bar,bar,bar", "-DFROM_CXXOPTS=bar,bar,bar")
        .inOrder();
    assertThat(objcppCompileAction.getArguments()).doesNotContain("-DFROM_CONLYOPTS=bar,bar,bar");

    CommandAction cppCompileAction = compileAction("//objc:lib", "cpp.o");
    assertThat(cppCompileAction.getArguments())
        .containsAtLeast("-DFROM_SHARED=bar,bar,bar", "-DFROM_CXXOPTS=bar,bar,bar")
        .inOrder();
    assertThat(cppCompileAction.getArguments()).doesNotContain("-DFROM_CONLYOPTS=bar,bar,bar");
  }

  @Test
  public void testBothModuleNameAndModuleMapGivesError() throws Exception {
    checkError(
        "x",
        "x",
        BOTH_MODULE_NAME_AND_MODULE_MAP_SPECIFIED,
        "objc_library( name = 'x', module_name = 'x', module_map = 'x.modulemap' )");
  }

  @Test
  public void testCompilationActionsWithCoptFmodules() throws Exception {
    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setAndCreateFiles("hdrs", "c.h")
        .setList("copts", "-fmodules")
        .write();
    CommandAction compileActionA = compileAction("//objc:lib", "a.o");
    assertThat(removeConfigFragment(compileActionA.getArguments()))
        .containsAtLeast(
            "-fmodules",
            "-fmodules-cache-path="
                + OUTPUTDIR
                + "/"
                + CompilationSupport.OBJC_MODULE_CACHE_DIR_NAME);
  }

  @Test
  public void testArchiveAction_simulator() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=x86_64",
        "--platforms=" + MockObjcSupport.IOS_X86_64);
    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setAndCreateFiles("hdrs", "c.h")
        .write();

    CommandAction archiveAction = archiveAction("//objc:lib");
    assertThat(archiveAction.getArguments())
        .isEqualTo(
            ImmutableList.of(
                "tools/osx/crosstool/iossim/ar_wrapper",
                "rcs",
                Iterables.getOnlyElement(archiveAction.getOutputs()).getExecPathString(),
                getBinArtifact("_objs/lib/arc/a.o", getConfiguredTarget("//objc:lib"))
                    .getExecPathString(),
                getBinArtifact("_objs/lib/arc/b.o", getConfiguredTarget("//objc:lib"))
                    .getExecPathString()));
    assertThat(baseArtifactNames(archiveAction.getInputs()))
        .containsAtLeast("a.o", "b.o", "ar", "libempty.a", "libtool");
    assertThat(baseArtifactNames(archiveAction.getOutputs())).containsExactly("liblib.a");
    assertRequiresDarwin(archiveAction);
  }

  @Test
  public void testArchiveAction_device() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=arm64",
        "--platforms=" + MockObjcSupport.IOS_ARM64);
    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setAndCreateFiles("hdrs", "c.h")
        .write();
    CommandAction archiveAction = archiveAction("//objc:lib");

    assertThat(archiveAction.getArguments())
        .isEqualTo(
            ImmutableList.of(
                "tools/osx/crosstool/ios/ar_wrapper",
                "rcs",
                Iterables.getOnlyElement(archiveAction.getOutputs()).getExecPathString(),
                getBinArtifact("_objs/lib/arc/a.o", getConfiguredTarget("//objc:lib"))
                    .getExecPathString(),
                getBinArtifact("_objs/lib/arc/b.o", getConfiguredTarget("//objc:lib"))
                    .getExecPathString()));
    assertThat(baseArtifactNames(archiveAction.getInputs())).containsAtLeast("a.o", "b.o");
    assertThat(baseArtifactNames(archiveAction.getOutputs())).containsExactly("liblib.a");
    assertRequiresDarwin(archiveAction);
  }

  @Test
  public void testIncludesDirsGetPassedToCompileAction() throws Exception {
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("includes", "../third_party/foo", "opensource/bar")
        .write();
    CommandAction compileAction = compileAction("//lib:lib", "a.o");

    for (String path : rootedIncludePaths("third_party/foo", "lib/opensource/bar")) {
      assertThat(Joiner.on("").join(removeConfigFragment(compileAction.getArguments())))
          .contains("-I" + path);
    }
  }

  @Test
  public void testIncludesDirs_inExternalRepo_resolvesSiblingLayout() throws Exception {
    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name='lib_external')",
        "local_path_override(module_name = 'lib_external', path = 'lib_external')");
    scratch.file("lib_external/MODULE.bazel", "module(name='lib_external')");
    scratch.file(
        "lib_external/BUILD",
        """
        objc_library(
            name = "lib",
            srcs = [
                "a.m",
                "bar/b.h",
            ],
            includes = ["bar"],
        )
        """);
    scratch.file("lib_external/a.m");
    scratch.file("lib_external/bar/b.h");
    invalidatePackages();

    setBuildLanguageOptions("--experimental_sibling_repository_layout");

    CommandAction compileAction = compileAction("@@lib_external+//:lib", "a.o");
    String actionArgs = Joiner.on("").join(removeConfigFragment(compileAction.getArguments()));

    assertThat(actionArgs).contains("-I../lib_external+/bar");
  }

  @Test
  public void testPropagatesDefinesToDependersTransitively() throws Exception {
    useConfiguration("--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_X86_64);
    createLibraryTargetWriter("//lib1:lib1")
        .setAndCreateFiles("srcs", "a.m")
        .setAndCreateFiles("non_arc_srcs", "b.m")
        .setList("defines", "A=foo", "B", "MONKEYS=enabled")
        .write();
    createLibraryTargetWriter("//lib2:lib2")
        .setAndCreateFiles("srcs", "a.m")
        .setAndCreateFiles("non_arc_srcs", "b.m")
        .setList("deps", "//lib1:lib1")
        .setList("defines", "C=bar", "D")
        .write();
    addAppleBinaryStarlarkRule(scratch);
    scratch.file(
        "bin/BUILD",
        """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//lib2"],
        )
        """);

    assertThat(compileAction("//lib1:lib1", "a.o").getArguments())
        .containsAtLeast("-DA=foo", "-DB", "-DMONKEYS=enabled")
        .inOrder();
    assertThat(compileAction("//lib1:lib1", "b.o").getArguments())
        .containsAtLeast("-DA=foo", "-DB", "-DMONKEYS=enabled")
        .inOrder();
    assertThat(compileAction("//lib2:lib2", "a.o").getArguments())
        .containsAtLeast("-DA=foo", "-DB", "-DMONKEYS=enabled", "-DC=bar", "-DD")
        .inOrder();
    assertThat(compileAction("//lib2:lib2", "b.o").getArguments())
        .containsAtLeast("-DA=foo", "-DB", "-DMONKEYS=enabled", "-DC=bar", "-DD")
        .inOrder();
    // TODO: Add tests for //bin:bin once experimental_objc_binary is implemented
  }

  @Test
  public void testDuplicateDefines() throws Exception {
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m")
        .setList("defines", "foo=bar", "foo=bar")
        .write();
    int timesDefinesAppear = 0;
    for (String arg : compileAction("//lib:lib", "a.o").getArguments()) {
      if (arg.equals("-Dfoo=bar")) {
        timesDefinesAppear++;
      }
    }
    assertWithMessage("Duplicate define \"foo=bar\" should occur only once in command line")
        .that(timesDefinesAppear)
        .isEqualTo(1);
  }

  @Test
  public void checkDefinesFromCcLibraryDep() throws Exception {
    checkDefinesFromCcLibraryDep(RULE_TYPE);
  }

  @Test
  public void testCppSourceCompilesWithCppFlags() throws Exception {
    createLibraryTargetWriter("//objc:x")
        .setAndCreateFiles("srcs", "a.mm", "b.cc", "c.mm", "d.cxx", "e.c", "f.m", "g.C")
        .write();
    assertThat(compileAction("//objc:x", "a.o").getArguments()).contains("-std=gnu++11");
    assertThat(compileAction("//objc:x", "b.o").getArguments()).contains("-std=gnu++11");
    assertThat(compileAction("//objc:x", "c.o").getArguments()).contains("-std=gnu++11");
    assertThat(compileAction("//objc:x", "d.o").getArguments()).contains("-std=gnu++11");
    assertThat(compileAction("//objc:x", "e.o").getArguments()).doesNotContain("-std=gnu++11");
    assertThat(compileAction("//objc:x", "f.o").getArguments()).doesNotContain("-std=gnu++11");
    assertThat(compileAction("//objc:x", "g.o").getArguments()).contains("-std=gnu++11");
  }

  @Test
  public void testDoesNotUseCxxUnfilteredFlags() throws Exception {
    createLibraryTargetWriter("//lib:lib").setList("srcs", "a.m").write();
    // -pthread is an unfiltered_cxx_flag in the osx crosstool.
    assertThat(compileAction("//lib:lib", "a.o").getArguments()).doesNotContain("-pthread");
  }

  @Test
  public void testDoesNotUseDotdPruning() throws Exception {
    useConfiguration("--objc_use_dotd_pruning=false");
    createLibraryTargetWriter("//lib:lib").setList("srcs", "a.m").write();
    CppCompileAction compileAction = (CppCompileAction) compileAction("//lib:lib", "a.o");
    assertThat(compileAction.getDotdFile()).isNull();
  }

  @Test
  public void testProvidesObjcLibraryAndHeaders() throws Exception {
    ConfiguredTarget target =
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "a.h", "b.h")
            .write();
    ConfiguredTarget impltarget =
        createLibraryTargetWriter("//objc_impl:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "a.h", "b.h")
            .write();
    ConfiguredTarget depender =
        createLibraryTargetWriter("//objc_depender:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "c.h", "d.h")
            .setList("deps", "//objc:lib")
            .setList("implementation_deps", "//objc_impl:lib")
            .write();

    assertThat(getArifactPathsOfLibraries(target)).containsExactly("objc/liblib.a");
    assertThat(getArifactPathsOfLibraries(depender))
        .containsExactly("objc/liblib.a", "objc_impl/liblib.a", "objc_depender/liblib.a");
    assertThat(getArifactPathsOfHeaders(target))
        .containsExactly("objc/a.h", "objc/b.h", "objc/private.h");
    assertThat(getArifactPathsOfHeaders(impltarget))
        .containsExactly("objc_impl/a.h", "objc_impl/b.h", "objc_impl/private.h");
    assertThat(getArifactPathsOfHeaders(depender))
        .containsExactly(
            "objc/a.h",
            "objc/b.h",
            "objc/private.h",
            "objc_depender/c.h",
            "objc_depender/d.h",
            "objc_depender/private.h");
  }

  @Test
  public void testCollectsWeakSdkFrameworksTransitively() throws Exception {
    createLibraryTargetWriter("//base_lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("weak_sdk_frameworks", "foo")
        .write();
    createLibraryTargetWriter("//depender_lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("weak_sdk_frameworks", "bar")
        .setList("deps", "//base_lib:lib")
        .write();

    ImmutableList<String> baseLinkFlags = getCcInfoUserLinkFlagsFromTarget("//base_lib:lib");
    assertThat(baseLinkFlags).containsExactly("-weak_framework", "foo").inOrder();
    ImmutableList<String> dependerLinkFlags =
        getCcInfoUserLinkFlagsFromTarget("//depender_lib:lib");
    assertThat(dependerLinkFlags)
        .containsExactly("-weak_framework", "bar", "-weak_framework", "foo")
        .inOrder();
  }

  @Test
  public void testErrorIfDepDoesNotExist() throws Exception {
    checkErrorIfNotExist("deps", "[':nonexistent']");
  }

  @Test
  public void testArIsNotImplicitOutput() throws Exception {
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .write();
    reporter.removeHandler(failFastHandler);
    assertThrows(NoSuchTargetException.class, () -> getTarget("//lib:liblib.a"));
  }

  @Test
  public void testErrorForAbsoluteIncludesPath() throws Exception {
    scratch.file("x/a.m");
    checkError(
        "x",
        "x",
        String.format(ABSOLUTE_INCLUDES_PATH_FORMAT, "/absolute/path"),
        "objc_library(",
        "    name = 'x',",
        "    srcs = ['a.m'],",
        "    includes = ['/absolute/path'],",
        ")");
  }

  @Test
  public void testDylibsProvided() throws Exception {
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("sdk_dylibs", "libdy1", "libdy2")
        .write();

    CcLinkingContext ccLinkingContext = ccInfoForTarget("//lib:lib").getCcLinkingContext();
    assertThat(ccLinkingContext.getFlattenedUserLinkFlags()).containsExactly("-ldy1", "-ldy2");
  }

  @Test
  public void testPopulatesCompilationArtifacts() throws Exception {
    checkPopulatesCompilationArtifacts(RULE_TYPE);
  }

  @Test
  public void testCompilationActionsForDebug() throws Exception {
    checkClangCoptsForCompilationMode(RULE_TYPE, CompilationMode.DBG);
  }

  @Test
  public void testClangCoptsForDebugModeWithoutGlib() throws Exception {
    checkClangCoptsForDebugModeWithoutGlib(RULE_TYPE);
  }

  @Test
  public void testClangCoptsForDebugModeWithoutHardcoding() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios",
        "--platforms=" + MockObjcSupport.IOS_X86_64,
        "--compilation_mode=dbg",
        "--incompatible_avoid_hardcoded_objc_compilation_flags",
        "--cpu=k8");
    scratch.file("x/a.m");
    RULE_TYPE.scratchTarget(scratch, "srcs", "['a.m']");

    assertThat(compileAction("//x:x", "a.o").getArguments()).doesNotContain("-DDEBUG=1");
  }

  @Test
  public void testClangCoptsForDebugModeWithoutGlibOrHardcoding() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios",
        "--platforms=" + MockObjcSupport.IOS_X86_64,
        "--compilation_mode=dbg",
        "--objc_debug_with_GLIBCXX=false",
        "--incompatible_avoid_hardcoded_objc_compilation_flags",
        "--cpu=k8");
    scratch.file("x/a.m");
    RULE_TYPE.scratchTarget(scratch, "srcs", "['a.m']");

    assertThat(compileAction("//x:x", "a.o").getArguments())
        .containsNoneOf("-D_GLIBCXX_DEBUG", "-DDEBUG=1");
  }

  @Test
  public void testCompilationActionsForOptimized() throws Exception {
    checkClangCoptsForCompilationMode(RULE_TYPE, CompilationMode.OPT);
  }

  @Test
  public void testClangCoptsForOptimizedWithoutHardcoding() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios",
        "--platforms=" + MockObjcSupport.IOS_X86_64,
        "--compilation_mode=opt",
        "--incompatible_avoid_hardcoded_objc_compilation_flags",
        "--cpu=k8");
    scratch.file("x/a.m");
    RULE_TYPE.scratchTarget(scratch, "srcs", "['a.m']");

    assertThat(compileAction("//x:x", "a.o").getArguments()).doesNotContain("-DNDEBUG=1");
  }

  @Test
  public void testUsesDefinesFromTransitiveCcDeps() throws Exception {
    scratch.file(
        "package/BUILD",
        """
        cc_library(
            name = "cc_lib",
            srcs = ["a.cc"],
            defines = ["FOO"],
        )

        objc_library(
            name = "objc_lib",
            srcs = ["b.m"],
            deps = [":cc_lib"],
        )
        """);

    CommandAction compileAction = compileAction("//package:objc_lib", "b.o");
    assertThat(compileAction.getArguments()).contains("-DFOO");
  }

  @Test
  public void testAllowVariousNonBlacklistedTypesInHeaders() throws Exception {
    checkAllowVariousNonBlacklistedTypesInHeaders(RULE_TYPE);
  }

  @Test
  public void testXcodeVersionEnv() throws Exception {
    useConfiguration("--xcode_version=5.8");

    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setAndCreateFiles("hdrs", "c.h")
        .write();
    CommandAction action = compileAction("//objc:lib", "a.o");

    assertXcodeVersionEnv(action, "5.8");
  }

  @Test
  public void testIosSdkVersionCannotBeDefinedButEmpty() {
    var e =
        assertThrows(
            InvalidConfigurationException.class, () -> useConfiguration("--ios_sdk_version="));
    assertThat(e).hasMessageThat().contains("--ios_sdk_version");
  }

  private void checkErrorIfNotExist(String attribute, String value) throws Exception {
    scratch.file("x/a.m");
    checkError(
        "x",
        "x",
        "in "
            + attribute
            + " attribute of objc_library rule //x:x: rule '//x:nonexistent' does not exist",
        "objc_library(",
        "    name = 'x',",
        "    srcs = ['a.m'],",
        attribute + " = " + value,
        ")");
  }

  @Test
  public void testCompilesWithHdrs() throws Exception {
    checkCompilesWithHdrs(ObjcLibraryTest.RULE_TYPE);
  }

  @Test
  public void testCompilesAssemblyWithPreprocessing() throws Exception {
    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.S")
        .setAndCreateFiles("hdrs", "c.h")
        .write();

    CommandAction compileAction = compileAction("//objc:lib", "b.o");

    // Clang automatically preprocesses .S files, so the assembler-with-cpp flag is unnecessary.
    // Regression test for b/22636858.
    assertThat(compileAction.getArguments()).doesNotContain("-x");
    assertThat(compileAction.getArguments()).doesNotContain("assembler-with-cpp");
    assertThat(baseArtifactNames(compileAction.getOutputs())).containsExactly("b.o", "b.d");
    assertThat(baseArtifactNames(compileAction.getPossibleInputsForTesting()))
        .containsAtLeast("c.h", "b.S");
  }

  @Test
  public void testReceivesTransitivelyPropagatedDefines() throws Exception {
    checkReceivesTransitivelyPropagatedDefines(RULE_TYPE);
  }

  @Test
  public void testSdkIncludesUsedInCompileAction() throws Exception {
    checkSdkIncludesUsedInCompileAction(RULE_TYPE);
  }

  @Test
  public void testCompilationActionsWithPch() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_X86_64, "--cpu=k8");
    scratch.file("objc/foo.pch");
    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setAndCreateFiles("hdrs", "c.h")
        .set("pch", "'some.pch'")
        .write();

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");

    assertThat(removeConfigFragment(compileActionA.getArguments()))
        .containsAtLeastElementsIn(
            new ImmutableList.Builder<String>()
                .add("-fexceptions")
                .add("-fasm-blocks")
                .add("-fobjc-abi-version=2")
                .add("-fobjc-legacy-dispatch")
                .addAll(CompilationSupport.DEFAULT_COMPILER_FLAGS)
                .add("-arch x86_64")
                .add("-isysroot", "__BAZEL_XCODE_SDKROOT__")
                .addAll(FASTBUILD_COPTS)
                .add("-iquote", ".")
                .add("-iquote", OUTPUTDIR)
                .add("-include", "objc/some.pch")
                .add("-fobjc-arc")
                .add("-c", "objc/a.m")
                .addAll(outputArgs(compileActionA.getOutputs()))
                .build());

    assertThat(compileActionA.getPossibleInputsForTesting().toList())
        .contains(getFileConfiguredTarget("//objc:some.pch").getArtifact());
  }

  // Converts output artifacts into expected command-line arguments.
  private ImmutableList<String> outputArgs(Collection<Artifact> outputs) {
    ImmutableList.Builder<String> result = new ImmutableList.Builder<>();
    for (String outputConfig : Artifact.toExecPaths(outputs)) {
      String output = removeConfigFragment(outputConfig);
      if (output.endsWith(".o")) {
        result.add("-o", output);
      } else if (output.endsWith(".d")) {
        result.add("-MD", "-MF", output);
      } else {
        throw new IllegalArgumentException(
            "output " + output + " has unknown ending (not in (.d, .o)");
      }
    }
    return result.build();
  }

  @Test
  public void testCollectsSdkFrameworksTransitively() throws Exception {
    createLibraryTargetWriter("//base_lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("sdk_frameworks", "foo")
        .write();
    createLibraryTargetWriter("//depender_lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("sdk_frameworks", "bar")
        .setList("deps", "//base_lib:lib")
        .write();

    ImmutableList<String> baseLinkFlags = getCcInfoUserLinkFlagsFromTarget("//base_lib:lib");
    assertThat(baseLinkFlags).containsExactly("-framework", "foo").inOrder();
    ImmutableList<String> dependerLinkFlags =
        getCcInfoUserLinkFlagsFromTarget("//depender_lib:lib");
    assertThat(dependerLinkFlags)
        .containsExactly("-framework", "bar", "-framework", "foo")
        .inOrder();

    // Make sure that the archive action does not actually include the frameworks. This is needed
    // for creating binaries but is ignored for libraries.
    CommandAction archiveAction = archiveAction("//depender_lib:lib");
    assertThat(archiveAction.getArguments())
        .isEqualTo(
            ImmutableList.of(
                "tools/osx/crosstool/mac/ar_wrapper",
                "rcs",
                Iterables.getOnlyElement(archiveAction.getOutputs()).getExecPathString(),
                getBinArtifact("_objs/lib/arc/a.o", getConfiguredTarget("//depender_lib:lib"))
                    .getExecPathString(),
                getBinArtifact("_objs/lib/arc/b.o", getConfiguredTarget("//depender_lib:lib"))
                    .getExecPathString()));
  }

  @Test
  public void testMultipleRulesCompilingOneSourceGenerateUniqueObjFiles() throws Exception {
    scratch.file("lib/a.m");
    scratch.file(
        "lib/BUILD",
        """
        objc_library(
            name = "lib1",
            srcs = ["a.m"],
            copts = ["-Ilib1flag"],
        )

        objc_library(
            name = "lib2",
            srcs = ["a.m"],
            copts = ["-Ilib2flag"],
        )
        """);
    Artifact obj1 = Iterables.getOnlyElement(inputsEndingWith(archiveAction("//lib:lib1"), ".o"));
    Artifact obj2 = Iterables.getOnlyElement(inputsEndingWith(archiveAction("//lib:lib2"), ".o"));

    // The exec paths of each obj file should be based on the objc_library target.
    assertThat(obj1.getExecPathString()).contains("lib1");
    assertThat(obj1.getExecPathString()).doesNotContain("lib2");
    assertThat(obj2.getExecPathString()).doesNotContain("lib1");
    assertThat(obj2.getExecPathString()).contains("lib2");

    CommandAction compile1 = (CommandAction) getGeneratingAction(obj1);
    CommandAction compile2 = (CommandAction) getGeneratingAction(obj2);
    assertThat(compile1.getArguments()).contains("-Ilib1flag");
    assertThat(compile2.getArguments()).contains("-Ilib2flag");
  }

  @Test
  public void testIncludesDirsOfTransitiveDepsGetPassedToCompileAction() throws Exception {
    createLibraryTargetWriter("//lib1:lib1")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("includes", "third_party/foo", "opensource/bar")
        .write();

    createLibraryTargetWriter("//lib2:lib2")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("includes", "more_includes")
        .setList("deps", "//lib1:lib1")
        .write();
    CommandAction compileAction = compileAction("//lib2:lib2", "a.o");
    // We remove spaces, since the crosstool rules do not use spaces in include paths
    String compileActionArgs =
        Joiner.on("").join(removeConfigFragment(compileAction.getArguments())).replace(" ", "");
    List<String> expectedIncludePaths =
        rootedIncludePaths("lib2/more_includes", "lib1/third_party/foo", "lib1/opensource/bar");
    for (String expectedIncludePath : expectedIncludePaths) {
      assertThat(compileActionArgs).contains("-I" + expectedIncludePath);
    }
  }

  @Test
  public void testIncludesDirsOfTransitiveCcDepsGetPassedToCompileAction() throws Exception {
    scratch.file(
        "package/BUILD",
        """
        cc_library(
            name = "cc_lib",
            srcs = ["a.cc"],
            includes = ["foo/bar"],
        )

        objc_library(
            name = "objc_lib",
            srcs = ["b.m"],
            deps = [":cc_lib"],
        )
        """);

    CommandAction compileAction = compileAction("//package:objc_lib", "b.o");
    assertContainsSublist(
        removeConfigFragment(removeConfigFragment(compileAction.getArguments())),
        ImmutableList.copyOf(
            Iterables.concat(
                Iterables.transform(
                    rootedIncludePaths("package/foo/bar"),
                    element -> ImmutableList.of("-isystem", element)))));
  }

  @Test
  public void testIncludesDirsOfTransitiveCcIncDepsGetPassedToCompileAction() throws Exception {
    scratch.file(
        "third_party/cc_lib/BUILD",
        """
        licenses(["unencumbered"])

        cc_library(
            name = "cc_lib_impl",
            srcs = [
                "v1/a.c",
                "v1/a.h",
            ],
        )

        cc_library(
            name = "cc_lib",
            hdrs = ["v1/a.h"],
            strip_include_prefix = "v1",
            deps = [":cc_lib_impl"],
        )
        """);

    scratch.file(
        "package/BUILD",
        """
        objc_library(
            name = "objc_lib",
            srcs = ["b.m"],
            deps = ["//third_party/cc_lib"],
        )
        """);

    CommandAction compileAction = compileAction("//package:objc_lib", "b.o");
    // We remove spaces, since the crosstool rules do not use spaces for include paths.
    String compileActionArgs = Joiner.on("").join(compileAction.getArguments()).replace(" ", "");
    assertThat(compileActionArgs)
        .matches(".*-iquote.*/third_party/cc_lib/_virtual_includes/cc_lib.*");
  }

  @Test
  public void testIncludesIquoteFlagForGenFilesRoot() throws Exception {
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .write();
    CommandAction compileAction = compileAction("//lib:lib", "a.o");
    assertContainsSublist(
        removeConfigFragment(compileAction.getArguments()), ImmutableList.of("-iquote", OUTPUTDIR));
  }

  @Test
  public void testCompilesAssemblyAsm() throws Exception {
    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.asm")
        .setAndCreateFiles("hdrs", "c.h")
        .write();

    CommandAction compileAction = compileAction("//objc:lib", "b.o");

    assertThat(compileAction.getArguments()).doesNotContain("-x");
    assertThat(compileAction.getArguments()).doesNotContain("assembler-with-cpp");
    assertThat(baseArtifactNames(compileAction.getOutputs())).contains("b.o");
    assertThat(baseArtifactNames(compileAction.getPossibleInputsForTesting()))
        .containsAtLeast("c.h", "b.asm");
  }

  @Test
  public void testCompilesAssemblyS() throws Exception {
    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.s")
        .setAndCreateFiles("hdrs", "c.h")
        .write();

    CommandAction compileAction = compileAction("//objc:lib", "b.o");

    assertThat(compileAction.getArguments()).doesNotContain("-x");
    assertThat(compileAction.getArguments()).doesNotContain("assembler-with-cpp");
    assertThat(baseArtifactNames(compileAction.getOutputs())).contains("b.o");
    assertThat(baseArtifactNames(compileAction.getPossibleInputsForTesting()))
        .containsAtLeast("c.h", "b.s");
  }

  @Test
  public void testProvidesHdrsAndIncludes() throws Exception {
    checkProvidesHdrsAndIncludes(RULE_TYPE, Optional.of("x/private.h"));
  }

  @Test
  public void testPruningActionsSetLocalityBasedOnXcode() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_version(
            name = "version10_1_0",
            aliases = [
                "10.1",
                "10.1.0",
            ],
            default_ios_sdk_version = "12.1",
            default_macos_sdk_version = "10.14",
            default_tvos_sdk_version = "12.1",
            default_watchos_sdk_version = "5.1",
            version = "10.1.0",
        )

        xcode_version(
            name = "version10_2_1",
            aliases = [
                "10.2.1",
                "10.2",
            ],
            default_ios_sdk_version = "12.2",
            default_macos_sdk_version = "10.14",
            default_tvos_sdk_version = "12.2",
            default_watchos_sdk_version = "5.2",
            version = "10.2.1",
        )

        available_xcodes(
            name = "local",
            default = ":version10_1_0",
            versions = [":version10_1_0"],
        )

        available_xcodes(
            name = "remote",
            default = ":version10_2_1",
            versions = [":version10_2_1"],
        )

        xcode_config(
            name = "my_config",
            local_versions = ":local",
            remote_versions = ":remote",
        )
        """);

    useConfigurationWithCustomXcode(
        "--xcode_version=10.2.1",
        "--xcode_version_config=//xcode:my_config",
        "--objc_use_dotd_pruning");
    createLibraryTargetWriter("//lib:lib").setList("srcs", "a.m").write();
    CppCompileAction action = (CppCompileAction) compileAction("//lib:lib", "a.o");
    assertHasRequirement(action, ExecutionRequirements.REQUIREMENTS_SET);
    assertHasRequirement(action, ExecutionRequirements.NO_LOCAL);
    assertNotHasRequirement(action, ExecutionRequirements.NO_REMOTE);
  }

  @Test
  public void testUsesDotdPruning() throws Exception {
    useConfiguration("--objc_use_dotd_pruning");
    createLibraryTargetWriter("//lib:lib").setList("srcs", "a.m").write();
    CppCompileAction compileAction = (CppCompileAction) compileAction("//lib:lib", "a.o");
    ActionExecutionException expected =
        assertThrows(
            ActionExecutionException.class,
            () ->
                compileAction.discoverInputsFromDotdFiles(
                    new ActionExecutionContextBuilder().build(),
                    null,
                    null,
                    null,
                    false,
                    PathMapper.NOOP));
    assertThat(expected).hasMessageThat().contains("error while parsing .d file");
  }

  @Test
  public void testAppleSdkDefaultPlatformEnv() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_X86_64, "--cpu=k8");
    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setAndCreateFiles("hdrs", "c.h")
        .write();
    CommandAction action = compileAction("//objc:lib", "a.o");

    assertAppleSdkPlatformEnv(action, "iPhoneSimulator");
  }

  @Test
  public void testAppleSdkDevicePlatformEnv() throws Exception {
    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=arm64",
        "--platforms=" + MockObjcSupport.IOS_ARM64);

    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setAndCreateFiles("hdrs", "c.h")
        .write();
    CommandAction action = compileAction("//objc:lib", "a.o");

    assertAppleSdkPlatformEnv(action, "iPhoneOS");
  }

  @Test
  public void testObjcImportDoesNotCrash() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        objc_library(
            name = "objc",
            srcs = ["source.m"],
            deps = [":import"],
        )

        objc_import(
            name = "import",
            archives = ["archive.a"],
        )
        """);
    assertThat(getConfiguredTarget("//x:objc")).isNotNull();
  }

  @Test
  public void testCompilationActionsWithIQuotesInCopts() throws Exception {
    useConfiguration("--ios_multi_cpus=arm64");
    createLibraryTargetWriter("//objc:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setAndCreateFiles("hdrs", "c.h")
        .setList("copts", "-iquote foo/bar", "-iquote bam/baz")
        .write();

    CommandAction compileActionA = compileAction("//objc:lib", "a.o");
    String action = String.join(" ", compileActionA.getArguments());
    assertThat(action).contains("-iquote foo/bar");
    assertThat(action).contains("-iquote bam/baz");
  }

  @Test
  public void testCollectCodeCoverageWithGCOVFlags() throws Exception {
    useConfiguration("--collect_code_coverage");
    createLibraryTargetWriter("//objc:x")
        .setAndCreateFiles("srcs", "a.mm", "b.cc", "c.mm", "d.cxx", "e.c", "f.m", "g.C")
        .write();
    ImmutableList<String> copts = ImmutableList.of("-fprofile-arcs", "-ftest-coverage");
    assertThat(compileAction("//objc:x", "a.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "b.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "c.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "d.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "e.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "f.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "g.o").getArguments()).containsAtLeastElementsIn(copts);
  }

  @Test
  public void testCollectCodeCoverageWithLLVMCOVFlags() throws Exception {
    useConfiguration("--collect_code_coverage", "--experimental_use_llvm_covmap");
    createLibraryTargetWriter("//objc:x")
        .setAndCreateFiles("srcs", "a.mm", "b.cc", "c.mm", "d.cxx", "e.c", "f.m", "g.C")
        .write();
    ImmutableList<String> copts =
        ImmutableList.of("-fprofile-instr-generate", "-fcoverage-mapping");
    assertThat(compileAction("//objc:x", "a.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "b.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "c.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "d.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "e.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "f.o").getArguments()).containsAtLeastElementsIn(copts);
    assertThat(compileAction("//objc:x", "g.o").getArguments()).containsAtLeastElementsIn(copts);
  }

  @Test
  public void testNoG0IfGeneratesDsym() throws Exception {
    useConfiguration("--apple_generate_dsym", "-c", "opt");
    createLibraryTargetWriter("//x:x").setList("srcs", "a.m").write();
    CommandAction compileAction = compileAction("//x:x", "a.o");
    assertThat(compileAction.getArguments()).doesNotContain("-g0");
  }

  @Test
  public void testFilesToCompileOutputGroup() throws Exception {
    checkFilesToCompileOutputGroup(RULE_TYPE);
  }

  @Test
  public void testDefaultEnabledFeatureIsUsed() throws Exception {
    // Although using --ios_multi_cpus=x86_64, it transitions to darwin_x86_64, so the actual
    // cc_toolchain in use will be the darwin_x86_64 one.
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures("default_feature"));
    useConfiguration("--ios_multi_cpus=x86_64");
    scratch.file(
        "x/BUILD",
        """
        objc_library(
            name = "objc",
            srcs = ["source.m"],
        )
        """);
    CommandAction compileAction = compileAction("//x:objc", "source.o");
    assertThat(compileAction.getArguments()).contains("-dummy");
  }

  @Test
  public void testHeaderPassedToCcLib() throws Exception {
    createLibraryTargetWriter("//objc:lib").setList("hdrs", "objc_hdr.h").write();
    ScratchAttributeWriter.fromLabelString(this, "cc_library", "//cc:lib")
        .setList("srcs", "a.cc")
        .setList("deps", "//objc:lib")
        .write();
    CommandAction compileAction = compileAction("//cc:lib", "a.o");
    assertThat(Artifact.toRootRelativePaths(compileAction.getPossibleInputsForTesting()))
        .contains("objc/objc_hdr.h");
  }

  @Test
  public void testTextualHeaderPassedToCcLib() throws Exception {
    ScratchAttributeWriter.fromLabelString(this, "cc_library", "//cc/txt_dep")
        .setList("textual_hdrs", "hdr.h")
        .write();
    createLibraryTargetWriter("//objc:lib").setList("deps", "//cc/txt_dep").write();
    ScratchAttributeWriter.fromLabelString(this, "cc_library", "//cc/lib")
        .setList("srcs", "a.cc")
        .setList("deps", "//objc:lib")
        .write();
    CommandAction compileAction = compileAction("//cc/lib", "a.o");
    assertThat(Artifact.toRootRelativePaths(compileAction.getPossibleInputsForTesting()))
        .contains("cc/txt_dep/hdr.h");
  }

  /** Regression test for https://github.com/bazelbuild/bazel/issues/7721. */
  @Test
  public void testToolchainRuntimeLibrariesSolibDir() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig,
        MockObjcSupport.darwinX86_64()
            .withFeatures(
                CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    scratch.file(
        "foo/BUILD",
        """
        cc_test(
            name = "d",
            deps = [":b"],
        )

        objc_library(
            name = "b",
            deps = [":a"],
        )

        cc_library(
            name = "a",
            srcs = ["a.c"],
        )
        """);
    ConfiguredTarget configuredTarget = getConfiguredTarget("//foo:d");
    assertThat(configuredTarget).isNotNull();
  }

  @Test
  public void testDirectFields() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        objc_library(
            name = "foo",
            srcs = [
                "foo.m",
                "foo_impl.h",
            ],
            hdrs = ["foo.h"],
            textual_hdrs = ["foo.inc"],
        )

        objc_library(
            name = "bar",
            srcs = [
                "bar.m",
                "bar_impl.h",
            ],
            hdrs = ["bar.h"],
            textual_hdrs = ["bar.inc"],
            deps = [":foo"],
        )
        """);

    StarlarkInfo dependerProvider = getObjcInfo(getConfiguredTarget("//x:bar"));
    assertThat(baseArtifactNames(getDirectSources(dependerProvider)))
        .containsExactly("bar.m", "bar_impl.h");

    ConfiguredTarget target = getConfiguredTarget("//x:bar");
    CcCompilationContext ccCompilationContext =
        target.get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(baseArtifactNames(ccCompilationContext.getDirectPublicHdrs()))
        .containsExactly("bar.h");
    assertThat(baseArtifactNames(ccCompilationContext.getDirectPrivateHdrs()))
        .containsExactly("bar_impl.h");
    assertThat(baseArtifactNames(ccCompilationContext.getTextualHdrs())).containsExactly("bar.inc");

    // Verify that the CppModuleMap objects are not added twice when merging the ARC and non-ARC
    // contexts.
    assertThat(ccCompilationContext.getExportingModuleMaps()).hasSize(1);
  }

  @Test
  public void testNameHasSlash() throws Exception {
    scratch.file("x/foo.m");
    checkError(
        "x",
        "foo/bar",
        "this attribute has unsupported character '/'",
        "objc_library(name = 'foo/bar', srcs = ['foo.m'])");
  }

  @Test
  public void testObjcLibraryLoadedThroughMacro() throws Exception {
    setupTestObjcLibraryLoadedThroughMacro(/* loadMacro= */ true);
    assertThat(getConfiguredTarget("//a:a")).isNotNull();
    assertNoEvents();
  }

  private void setupTestObjcLibraryLoadedThroughMacro(boolean loadMacro) throws Exception {
    scratch.file(
        "a/BUILD",
        getAnalysisMock().ccSupport().getMacroLoadStatement(loadMacro, "objc_library"),
        "objc_library(name='a', srcs=['a.cc'])");
  }

  @Test
  public void testGenerateDsymFlagPropagatesToObjcLibraryFeature() throws Exception {
    useConfiguration("--apple_generate_dsym");
    createLibraryTargetWriter("//objc/lib").setList("srcs", "a.m").write();
    CommandAction compileAction = compileAction("//objc/lib", "a.o");
    assertThat(compileAction.getArguments()).contains("-DDUMMY_GENERATE_DSYM_FILE");
  }

  @Test
  public void testArtifactsToAlwaysBuild() throws Exception {
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo",
            "x",
            "objc_library(name = 'x', srcs = ['x.m'], non_arc_srcs = ['x2.m'], deps = [':y'])",
            "objc_library(name = 'y', srcs = ['y.m'], non_arc_srcs = ['y2.m'], )");
    assertThat(
            ActionsTestUtil.sortedBaseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
        .isEqualTo("x.o x2.o y.o y2.o");
  }

  @Test
  public void testLangObjcFeature() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");

    ConfiguredTarget x =
        scratchConfiguredTarget("foo", "x", "objc_library(name = 'x', hdrs = ['x.h'])");

    assertThat(getGeneratingCompileAction("_objs/x/arc/x.h.processed", x).getArguments())
        .contains("-DDUMMY_LANG_OBJC");
  }

  private CppCompileAction getGeneratingCompileAction(
      String packageRelativePath, ConfiguredTarget owner) {
    return (CppCompileAction) getGeneratingAction(getBinArtifact(packageRelativePath, owner));
  }

  @Test
  public void testProcessHeadersInArcOnly() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");
    ConfiguredTarget x =
        scratchConfiguredTarget("foo", "x", "objc_library(name = 'x', hdrs = ['x.h'])");
    CcCompilationContext ccCompilationContext = x.get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(Artifact.toRootRelativePaths(ccCompilationContext.getHeaderTokens()))
        .containsExactly("foo/_objs/x/arc/x.h.processed");
  }

  @Test
  public void testProcessHeadersInDependencies() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo",
            "x",
            "objc_library(name = 'x', deps = [':y'])",
            "objc_library(name = 'y', hdrs = ['y.h'])");
    CcCompilationContext ccCompilationContext = x.get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(ActionsTestUtil.baseNamesOf(ccCompilationContext.getHeaderTokens()))
        .isEqualTo("y.h.processed");
    assertThat(ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
        .isEqualTo("y.h.processed");
  }

  @Test
  public void testProcessHeadersInDependenciesOfCcBinary() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");
    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo",
            "x",
            "cc_binary(name = 'x', deps = [':y', ':z'])",
            "cc_library(name = 'y', hdrs = ['y.h'])",
            "objc_library(name = 'z', srcs = ['z.h'])");
    String validation = ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.VALIDATION));
    assertThat(validation).contains("y.h.processed");
    assertThat(validation).contains("z.h.processed");
  }

  @Test
  public void testSrcCompileActionMnemonic() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");

    ConfiguredTarget x =
        scratchConfiguredTarget("foo", "x", "objc_library(name = 'x', srcs = ['a.m'])");

    assertThat(getGeneratingCompileAction("_objs/x/arc/a.o", x).getMnemonic())
        .isEqualTo("ObjcCompile");
  }

  @Test
  public void testHeaderCompileActionMnemonic() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration("--features=parse_headers", "--process_headers_in_dependencies");

    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo", "x", "objc_library(name = 'x', srcs = ['y.h'], hdrs = ['z.h'])");

    assertThat(getGeneratingCompileAction("_objs/x/arc/y.h.processed", x).getMnemonic())
        .isEqualTo("ObjcCompile");
    assertThat(getGeneratingCompileAction("_objs/x/arc/z.h.processed", x).getMnemonic())
        .isEqualTo("ObjcCompile");
  }

  @Test
  public void testIncompatibleUseCppCompileHeaderMnemonic() throws Exception {
    MockObjcSupport.setupCcToolchainConfig(
        mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS));
    useConfiguration(
        "--incompatible_use_cpp_compile_header_mnemonic",
        "--features=parse_headers",
        "--process_headers_in_dependencies");

    ConfiguredTarget x =
        scratchConfiguredTarget(
            "foo", "x", "objc_library(name = 'x', srcs = ['a.m', 'y.h'], hdrs = ['z.h'])");

    assertThat(getGeneratingCompileAction("_objs/x/arc/a.o", x).getMnemonic())
        .isEqualTo("ObjcCompile");
    assertThat(getGeneratingCompileAction("_objs/x/arc/y.h.processed", x).getMnemonic())
        .isEqualTo("ObjcCompileHeader");
    assertThat(getGeneratingCompileAction("_objs/x/arc/z.h.processed", x).getMnemonic())
        .isEqualTo("ObjcCompileHeader");
  }

  @Test
  public void testAlwaysLinkDefaultFalse() throws Exception {
    useConfiguration("--incompatible_objc_alwayslink_by_default=false");
    addAppleBinaryStarlarkRule(scratch);

    scratch.file(
        "test/BUILD",
        """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "objc_bin",
            platform_type = "ios",
            deps = [":main_lib"],
        )

        objc_library(
            name = "main_lib",
            srcs = ["b.m"],
        )
        """);

    CommandAction testLinkAction = linkAction("//test:objc_bin");
    assertThat(Joiner.on(" ").join(testLinkAction.getArguments())).doesNotContain("-force_load");
  }

  @Test
  public void testAlwaysLinkDefaultTrue() throws Exception {
    useConfiguration("--incompatible_objc_alwayslink_by_default");
    addAppleBinaryStarlarkRule(scratch);

    scratch.file(
        "test/BUILD",
        """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "objc_bin",
            platform_type = "ios",
            deps = [":main_lib"],
        )

        objc_library(
            name = "main_lib",
            srcs = ["b.m"],
        )
        """);
    scratch.file("test/b.m", "// dummy file");

    CommandAction testLinkAction = linkAction("//test:objc_bin");
    assertThat(Joiner.on(" ").join(testLinkAction.getArguments()))
        .containsMatch("-force_load [^ ]+-out/[^ ]+/test/libmain_lib.lo");
  }

  @Test
  public void testAlwaysLinkTrueDefaultFalse() throws Exception {
    useConfiguration("--incompatible_objc_alwayslink_by_default=false");
    addAppleBinaryStarlarkRule(scratch);

    scratch.file(
        "test/BUILD",
        """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "objc_bin",
            platform_type = "ios",
            deps = [":main_lib"],
        )

        objc_library(
            name = "main_lib",
            srcs = ["b.m"],
            alwayslink = True,
        )
        """);

    CommandAction testLinkAction = linkAction("//test:objc_bin");
    assertThat(Joiner.on(" ").join(testLinkAction.getArguments()))
        .containsMatch("-force_load [^ ]+-out/[^ ]+/test/libmain_lib.lo");
  }

  @Test
  public void testAlwaysLinkFalseDefaultTrue() throws Exception {
    useConfiguration("--incompatible_objc_alwayslink_by_default");
    addAppleBinaryStarlarkRule(scratch);

    scratch.file(
        "test/BUILD",
        """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "objc_bin",
            platform_type = "ios",
            deps = [":main_lib"],
        )

        objc_library(
            name = "main_lib",
            srcs = ["b.m"],
            alwayslink = False,
        )
        """);

    CommandAction testLinkAction = linkAction("//test:objc_bin");
    assertThat(Joiner.on(" ").join(testLinkAction.getArguments())).doesNotContain("-force_load");
  }

  @Test
  public void testLinkActionMnemonic() throws Exception {
    scratchConfiguredTarget("foo", "x", "objc_library(name = 'x', srcs = ['a.m'])");

    SpawnAction archiveAction = (SpawnAction) archiveAction("//foo:x");
    assertThat(archiveAction.getMnemonic()).isEqualTo("CppArchive");
  }

  private static List<String> linkstampExecPaths(NestedSet<CcLinkingContext.Linkstamp> linkstamps) {
    return ActionsTestUtil.execPaths(
        ActionsTestUtil.transform(linkstamps.toList(), CcLinkingContext.Linkstamp::getArtifact));
  }

  @Test
  public void testPassesThroughLinkstamps() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        objc_library(
            name = "foo",
            deps = [":bar"],
        )

        cc_library(
            name = "bar",
            linkstamp = "bar.cc",
        )
        """);

    assertThat(
            linkstampExecPaths(
                getConfiguredTarget("//x:foo")
                    .get(CcInfo.PROVIDER)
                    .getCcLinkingContext()
                    .getLinkstamps()))
        .containsExactly("x/bar.cc");
  }

  @Test
  public void testCompileLanguageApi() throws Exception {
    String fragments = "    fragments = ['google_cpp', 'cpp'],";
    if (AnalysisMock.get().isThisBazel()) {
      fragments = "    fragments = ['cpp'],";
    }
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");
    scratch.file("myinfo/BUILD");
    scratch.overwriteFile("tools/build_defs/foo/BUILD");
    scratch.file(
        "tools/build_defs/foo/extension.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _objc_starlark_library_impl(ctx):",
        "    toolchain = ctx.attr._my_cc_toolchain[cc_common.CcToolchainInfo]",
        "    features = ['objc-compile']",
        "    features.extend(ctx.features)",
        "    feature_configuration = cc_common.configure_features(",
        "        ctx = ctx,",
        "        cc_toolchain=toolchain,",
        "        requested_features = features,",
        "        unsupported_features = ctx.disabled_features)",
        "    foo_dict = {'string_variable': 'foo',",
        "            'string_sequence_variable' : ['foo'],",
        "            'string_depset_variable': depset(['foo'])}",
        "    (compilation_context, compilation_outputs) = cc_common.compile(",
        "        actions=ctx.actions,",
        "        feature_configuration=feature_configuration,",
        "        cc_toolchain=toolchain,",
        "        srcs=ctx.files.srcs,",
        "        name=ctx.label.name + '_suffix',",
        "        language='objc'",
        "    )",
        "    (linking_context,",
        "     linking_outputs) = cc_common.create_linking_context_from_compilation_outputs(",
        "        actions=ctx.actions,",
        "        feature_configuration=feature_configuration,",
        "        compilation_outputs=compilation_outputs,",
        "        name = ctx.label.name,",
        "        cc_toolchain=toolchain,",
        "        language='c++'",
        "    )",
        "    files_to_build = []",
        "    files_to_build.extend(compilation_outputs.pic_objects)",
        "    files_to_build.extend(compilation_outputs.objects)",
        "    library_to_link = None",
        "    if len(ctx.files.srcs) > 0:",
        "        library_to_link = linking_outputs.library_to_link",
        "        if library_to_link.pic_static_library != None:",
        "            files_to_build.append(library_to_link.pic_static_library)",
        "        files_to_build.append(library_to_link.dynamic_library)",
        "    return [MyInfo(libraries=[library_to_link]),",
        "            DefaultInfo(files=depset(files_to_build)),",
        "            CcInfo(compilation_context=compilation_context,",
        "                   linking_context=linking_context)]",
        "objc_starlark_library = rule(",
        "    implementation = _objc_starlark_library_impl,",
        "    attrs = {",
        "      'srcs': attr.label_list(allow_files=True),",
        "      '_my_cc_toolchain': attr.label(default =",
        "          '//a:alias')",
        "    },",
        fragments,
        ")");
    scratch.file(
        "foo/BUILD",
        """
        load("//tools/build_defs/foo:extension.bzl", "objc_starlark_library")

        objc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.m"],
        )
        """);
    scratch.file("a/BUILD", "cc_toolchain_alias(name='alias')");
    getConfiguredTarget("//foo:starlark_lib");
    assertNoEvents();
  }

  @Test
  public void testCcTestUsesStaticLibraries() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        cc_test(
            name = "test",
            deps = [":foo"],
        )

        objc_library(
            name = "foo",
            deps = [":bar"],
        )

        cc_library(
            name = "bar",
            srcs = [
                "bar.a",
                "bar.so",
            ],
        )
        """);

    assertThat(
            artifactsToStrings(
                getGeneratingAction(
                        getConfiguredTarget("//x:test")
                            .getProvider(FilesToRunProvider.class)
                            .getExecutable())
                    .getInputs()))
        .contains("src x/bar.a");
  }

  @Test
  public void testPassesDependenciesStaticLibrariesInCcInfo() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        objc_library(
            name = "baz",
            srcs = ["baz.mm"],
        )

        objc_library(
            name = "foo",
            srcs = ["foo.mm"],
            deps = [":baz"],
        )

        cc_library(
            name = "bar",
            srcs = ["bar.cc"],
            deps = [":foo"],
        )
        """);
    useConfiguration(
        "--proto_toolchain_for_java=//tools/proto/toolchains:java",
        "--platforms=" + MockObjcSupport.DARWIN_X86_64,
        "--apple_platform_type=macos",
        "--cpu=darwin_x86_64");

    CcInfo ccInfo = getConfiguredTarget("//x:bar").get(CcInfo.PROVIDER);

    assertThat(
            artifactsToStrings(
                ccInfo.getCcLinkingContext().getLinkerInputs().toList().stream()
                    .map(LinkerInput::getLibraries)
                    .flatMap(List::stream)
                    .map(LibraryToLink::getStaticLibrary)
                    .collect(toImmutableList())))
        .contains("bin x/libbaz.a");
  }

  @Test
  public void testGrepIncludesPassed() throws Exception {
    if (analysisMock.isThisBazel()) {
      return;
    }
    scratch.file(
        "x/BUILD",
        """
        objc_library(
            name = "foo",
            srcs = ["foo.mm"],
        )
        """);

    CppCompileAction compileA = (CppCompileAction) compileAction("//x:foo", "foo.o");
    assertThat(compileA.getGrepIncludes()).isNotNull();
  }

  @Test
  public void testModuleMapFileAccessed() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        objc_library(
            name = "foo",
            srcs = ["foo.mm"],
            enable_modules = True,
            module_map = "foo.modulemap",
        )
        """);

    getConfiguredTarget("//x:foo");
  }

  @Test
  public void correctToolFilesUsed() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        cc_toolchain_alias(name = "a")

        objc_library(
            name = "l",
            srcs = ["l.m"],
        )

        objc_library(
            name = "asm",
            srcs = ["a.s"],
        )

        objc_library(
            name = "preprocessed-asm",
            srcs = ["a.S"],
        )
        """);
    useConfiguration("--incompatible_use_specific_tool_files");

    ConfiguredTarget target = getConfiguredTarget("//a:a");
    CcToolchainProvider toolchainProvider = target.get(CcToolchainProvider.PROVIDER);

    RuleConfiguredTarget libTarget = (RuleConfiguredTarget) getConfiguredTarget("//a:l");
    ActionAnalysisMetadata archiveAction =
        libTarget.getActions().stream()
            .filter((a) -> a.getMnemonic().equals("CppArchive"))
            .collect(onlyElement());
    assertThat(archiveAction.getInputs().toList())
        .containsAtLeastElementsIn(toolchainProvider.getArFiles().toList());

    ActionAnalysisMetadata objcCompileAction =
        libTarget.getActions().stream()
            .filter((a) -> a.getMnemonic().equals("ObjcCompile"))
            .collect(onlyElement());
    assertThat(objcCompileAction.getInputs().toList())
        .containsAtLeastElementsIn(toolchainProvider.getCompilerFiles().toList());

    ActionAnalysisMetadata asmAction =
        ((RuleConfiguredTarget) getConfiguredTarget("//a:asm"))
            .getActions().stream()
                .filter((a) -> a.getMnemonic().equals("CppCompile"))
                .collect(onlyElement());
    assertThat(asmAction.getInputs().toList())
        .containsAtLeastElementsIn(toolchainProvider.getAsFiles().toList());

    ActionAnalysisMetadata preprocessedAsmAction =
        ((RuleConfiguredTarget) getConfiguredTarget("//a:preprocessed-asm"))
            .getActions().stream()
                .filter((a) -> a.getMnemonic().equals("CppCompile"))
                .collect(onlyElement());
    assertThat(preprocessedAsmAction.getInputs().toList())
        .containsAtLeastElementsIn(toolchainProvider.getCompilerFiles().toList());
  }

  /** b/197608223 */
  @Test
  public void testCompilationPrerequisitesHasHeaders() throws Exception {
    scratch.file(
        "bin/BUILD",
        """
        objc_library(
            name = "objc",
            srcs = ["objc.m"],
            deps = [":cc"],
        )

        cc_library(
            name = "cc",
            srcs = ["cc.cc"],
            hdrs = ["cc.h"],
        )
        """);

    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=x86_64",
        "--platforms=" + MockObjcSupport.IOS_X86_64);

    ConfiguredTarget cc = getConfiguredTarget("//bin:objc");

    assertThat(
            artifactsToStrings(
                cc.get(OutputGroupInfo.STARLARK_CONSTRUCTOR)
                    .getOutputGroup(OutputGroupInfo.COMPILATION_PREREQUISITES)))
        .contains("src bin/cc.h");
  }

  @Test
  public void testCoptsLocationIsExpanded() throws Exception {
    scratch.file(
        "bin/BUILD",
        "objc_library(",
        "    name = 'lib',",
        "    copts = ['$(rootpath lib1.m) $(location lib2.m) $(location data.data) $(execpath"
            + " header.h)'],",
        "    srcs = ['lib1.m'],",
        "    non_arc_srcs = ['lib2.m'],",
        "    data = ['data.data', 'lib2.m'],",
        "    hdrs = ['header.h'],",
        ")");

    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=x86_64",
        "--platforms=" + MockObjcSupport.IOS_X86_64);

    CppCompileAction compileA = (CppCompileAction) compileAction("//bin:lib", "lib1.o");
    assertThat(compileA.compileCommandLine.getCopts(PathMapper.NOOP))
        .containsAtLeast("bin/lib1.m", "bin/lib2.m", "bin/data.data", "bin/header.h");
  }

  @Test
  public void testCoptsLocationWhenNotExpanded_throwsAssertionError() throws Exception {
    scratch.file(
        "bin/BUILD",
        """
        objc_library(
            name = "lib",
            srcs = ["lib1.m"],
            hdrs = ["header.h"],
            copts = ["$(execpath lib2.m)"],
        )
        """);

    useConfiguration(
        "--apple_platform_type=ios",
        "--ios_multi_cpus=x86_64",
        "--platforms=" + MockObjcSupport.IOS_X86_64);

    assertThrows(AssertionError.class, () -> compileAction("//bin:lib", "lib1.o"));
  }

  @Test
  public void testEnableCoveragePropagatesSupportFiles() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        cc_toolchain_alias(name = "toolchain")

        objc_library(
            name = "lib",
        )
        """);
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=//a[:/]");

    CcToolchainProvider ccToolchainProvider =
        getConfiguredTarget("//a:toolchain").get(CcToolchainProvider.PROVIDER);
    InstrumentedFilesInfo instrumentedFilesInfo =
        getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);

    assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList()).isNotEmpty();
    assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList())
        .containsExactlyElementsIn(ccToolchainProvider.getCoverageFiles().toList());
  }

  @Test
  public void testDisableCoverageDoesNotPropagateSupportFiles() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        cc_toolchain_alias(name = "toolchain")

        objc_library(
            name = "lib",
        )
        """);

    InstrumentedFilesInfo instrumentedFilesInfo =
        getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);

    assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList()).isEmpty();
  }

  @Test
  public void testCoverageMetadataFiles() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        cc_toolchain_alias(name = "toolchain")

        objc_library(
            name = "foo",
            srcs = ["foo.m"],
        )

        objc_library(
            name = "bar",
            srcs = ["bar.m"],
            deps = [":foo"],
        )
        """);
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=//a[:/]");

    InstrumentedFilesInfo instrumentedFilesInfo =
        getConfiguredTarget("//a:bar").get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);

    assertThat(
            Artifact.toRootRelativePaths(instrumentedFilesInfo.getInstrumentationMetadataFiles()))
        .containsExactly("a/_objs/foo/arc/foo.gcno", "a/_objs/bar/arc/bar.gcno");
  }

  private ImmutableList<String> getCcInfoUserLinkFlagsFromTarget(String target)
      throws LabelSyntaxException {
    return getConfiguredTarget(target)
        .get(CcInfo.PROVIDER)
        .getCcLinkingContext()
        .getUserLinkFlags()
        .toList()
        .stream()
        .map(CcLinkingContext.LinkOptions::get)
        .flatMap(List::stream)
        .collect(toImmutableList());
  }

  @Test
  public void testSdkUserLinkFlagsFromSdkFieldsAndLinkoptsArePropagatedOnCcInfo() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        objc_library(
            name = "foo",
            linkopts = [
                "-lxml2",
                "-framework AVFoundation",
                "-Wl,-framework,Framework",
            ],
            sdk_dylibs = ["libz"],
            sdk_frameworks = ["CoreData"],
            deps = [
                ":bar",
                ":car",
            ],
        )

        objc_library(
            name = "bar",
            linkopts = [
                "-lsqlite3",
                "-Wl,-weak_framework,WeakFrameworkFromLinkOpt",
            ],
            sdk_frameworks = ["Foundation"],
        )

        objc_library(
            name = "car",
            linkopts = [
                "-framework UIKit",
            ],
            sdk_dylibs = ["libc++"],
            weak_sdk_frameworks = ["WeakFramework"],
        )
        """);

    ImmutableList<String> userLinkFlags = getCcInfoUserLinkFlagsFromTarget("//x:foo");
    assertThat(userLinkFlags).isNotEmpty();
    assertThat(userLinkFlags).containsAtLeast("-framework", "AVFoundation").inOrder();
    assertThat(userLinkFlags).containsAtLeast("-framework", "CoreData").inOrder();
    assertThat(userLinkFlags).containsAtLeast("-framework", "Foundation").inOrder();
    assertThat(userLinkFlags).containsAtLeast("-framework", "UIKit").inOrder();
    assertThat(userLinkFlags).containsAtLeast("-lz", "-lc++", "-lxml2", "-lsqlite3");
    assertThat(userLinkFlags).containsAtLeast("-framework", "Framework").inOrder();
    assertThat(userLinkFlags).containsAtLeast("-weak_framework", "WeakFramework").inOrder();
    assertThat(userLinkFlags)
        .containsAtLeast("-weak_framework", "WeakFrameworkFromLinkOpt")
        .inOrder();
  }

  @Test
  public void testTreeArtifactSrcs() throws Exception {
    doTestTreeAtrifactInAttributes("srcs");
  }

  @Test
  public void testTreeArtifactNonArcSrcs() throws Exception {
    doTestTreeAtrifactInAttributes("non_arc_srcs");
  }

  @Test
  public void testTreeArtifactHdrs() throws Exception {
    doTestTreeAtrifactInAttributes("hdrs");
  }

  private void doTestTreeAtrifactInAttributes(String attrName) throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "bar/create_tree_artifact.bzl",
        """
        def _impl(ctx):
            tree = ctx.actions.declare_directory("dir")
            ctx.actions.run_shell(
                outputs = [tree],
                inputs = [],
                arguments = [tree.path],
                command = "mkdir $1",
            )
            return [DefaultInfo(files = depset([tree]))]

        create_tree_artifact = rule(implementation = _impl)
        """);
    scratch.file(
        "bar/BUILD",
        "load(':create_tree_artifact.bzl', 'create_tree_artifact')",
        "create_tree_artifact(name = 'tree_artifact')",
        "objc_library(",
        "    name = 'lib',",
        "    " + attrName + " = [':tree_artifact'],",
        ")");

    getConfiguredTarget("//bar:lib");

    assertNoEvents();
  }
}
