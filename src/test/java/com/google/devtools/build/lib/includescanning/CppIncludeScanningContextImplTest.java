// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.collect.Streams.stream;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.DummyExecutor;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.packages.util.MockCcSupport;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction;
import com.google.devtools.build.lib.rules.cpp.CppRuleClasses;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.AbstractSkyFunctionEnvironmentForTesting;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrUntypedException;
import java.io.IOException;
import java.util.Collection;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class CppIncludeScanningContextImplTest extends BuildViewTestCase {

  private static final IncludeScanningHeaderData EMPTY_HEADER_DATA =
      new IncludeScanningHeaderData(
          /*pathToDeclaredHeader=*/ ImmutableMap.of(),
          /*modularHeaders=*/ ImmutableSet.of(),
          /*systemIncludeDirs=*/ ImmutableList.of(),
          /*cmdlineIncludes=*/ ImmutableList.of(),
          /*isValidUndeclaredHeader=*/ ignored -> true);

  @Before
  public void setupCppSupport() throws IOException {
    analysisMock
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC));
  }

  @Test
  public void treeArtifactHeader_scansExpandedArtifact() throws Exception {
    writeTreeRuleBzl(scratch.file("foo/def.bzl"));
    scratch.file(
        "foo/BUILD",
        """
        load(":def.bzl", "tree")

        package(features = [
            "cc_include_scanning",
            "header_modules",
            "use_header_modules",
        ])

        tree(name = "headers")

        cc_library(
            name = "foo",
            hdrs = [":headers"],
        )
        """);
    IncludeScanner includeScanner = mock(IncludeScanner.class);
    CppIncludeScanningContextImpl includeScanningContext =
        createIncludeScanningContext(includeScanner);
    CppCompileAction action = getCppCompileAction("//foo");
    var headerTree = (SpecialArtifact) getArtifact("//foo:headers");
    var headerTreeFile = TreeFileArtifact.createTreeOutput(headerTree, "file1.h");
    var environment = environmentWithTreeValue(headerTree, headerTreeFile);
    var actionExecutionContext = createActionExecutionContext(environment);

    var result =
        includeScanningContext.findAdditionalInputs(
            action, actionExecutionContext, EMPTY_HEADER_DATA);

    assertThat(result).isNotNull();
    ArgumentCaptor<Collection<Artifact>> collector = createCaptor(Collection.class);
    verify(includeScanner)
        .processAsync(any(), collector.capture(), any(), any(), any(), any(), any(), any());
    assertThat(collector.getValue()).containsExactly(headerTreeFile);
  }

  @Test
  public void treeArtifactAndRegularHeader_scansRegularAndExpandedArtifact() throws Exception {
    writeTreeRuleBzl(scratch.file("foo/def.bzl"));
    scratch.file(
        "foo/BUILD",
        """
        load(":def.bzl", "tree")

        package(features = [
            "cc_include_scanning",
            "header_modules",
            "use_header_modules",
        ])

        tree(name = "headers")

        cc_library(
            name = "foo",
            hdrs = [
                "header.h",
                ":headers",
            ],
        )
        """);
    scratch.file("foo/header.h");
    IncludeScanner includeScanner = mock(IncludeScanner.class);
    CppIncludeScanningContextImpl includeScanningContext =
        createIncludeScanningContext(includeScanner);
    CppCompileAction action = getCppCompileAction("//foo");
    var headerTree = (SpecialArtifact) getArtifact("//foo:headers");
    var headerTreeFile = TreeFileArtifact.createTreeOutput(headerTree, "file1.h");
    var environment = environmentWithTreeValue(headerTree, headerTreeFile);
    var actionExecutionContext = createActionExecutionContext(environment);

    var result =
        includeScanningContext.findAdditionalInputs(
            action, actionExecutionContext, EMPTY_HEADER_DATA);

    assertThat(result).isNotNull();
    ArgumentCaptor<Collection<Artifact>> collector = createCaptor(Collection.class);
    verify(includeScanner)
        .processAsync(any(), collector.capture(), any(), any(), any(), any(), any(), any());
    assertThat(collector.getValue()).containsExactly(headerTreeFile, getArtifact("//foo:header.h"));
  }

  @Test
  public void treeArtifactHeader_missingValue_returnsNull() throws Exception {
    writeTreeRuleBzl(scratch.file("foo/def.bzl"));
    scratch.file(
        "foo/BUILD",
        """
        load(":def.bzl", "tree")

        package(features = [
            "cc_include_scanning",
            "header_modules",
            "use_header_modules",
        ])

        tree(name = "headers")

        cc_library(
            name = "foo",
            hdrs = [":headers"],
        )
        """);
    CppIncludeScanningContextImpl includeScanningContext = createIncludeScanningContext(null);
    CppCompileAction action = getCppCompileAction("//foo");
    var actionExecutionContext = createActionExecutionContext(emptyEnvironment());

    var result =
        includeScanningContext.findAdditionalInputs(
            action, actionExecutionContext, EMPTY_HEADER_DATA);

    assertThat(result).isNull();
  }

  private static void writeTreeRuleBzl(Path file) throws IOException {
    TestUtils.writeLines(
        file,
        "def _tree(ctx):",
        "  dir = ctx.actions.declare_directory(ctx.label.name)",
        "  ctx.actions.run_shell(command = ':', outputs = [dir])",
        "  return DefaultInfo(files = depset([dir]))",
        "tree = rule(implementation = _tree)");
  }

  @SuppressWarnings("unchecked")
  private static <T, S> ArgumentCaptor<T> createCaptor(Class<S> clazz) {
    return (ArgumentCaptor<T>) ArgumentCaptor.forClass(clazz);
  }

  private static CppIncludeScanningContextImpl createIncludeScanningContext(
      IncludeScanner includeScanner) {
    IncludeScannerSupplier includeScannerSupplier = mock(IncludeScannerSupplier.class);
    when(includeScannerSupplier.scannerFor(any(), any(), any())).thenReturn(includeScanner);
    return new CppIncludeScanningContextImpl(() -> includeScannerSupplier);
  }

  private ActionExecutionContext createActionExecutionContext(Environment environment) {
    return ActionsTestUtil.createContextForInputDiscovery(
        new DummyExecutor(),
        NullEventHandler.INSTANCE,
        new ActionKeyContext(),
        new FileOutErr(),
        scratch.resolve("/execroot"),
        /* outputMetadataStore= */ null,
        environment,
        DiscoveredModulesPruner.DEFAULT);
  }

  private CppCompileAction getCppCompileAction(String label) throws LabelSyntaxException {
    return ((RuleConfiguredTarget) getConfiguredTarget(label))
        .getActions().stream()
            .filter(CppCompileAction.class::isInstance)
            .map(CppCompileAction.class::cast)
            .collect(onlyElement());
  }

  private static Environment emptyEnvironment() {
    return environmentWithValues(ImmutableMap.of());
  }

  private static Environment environmentWithTreeValue(
      SpecialArtifact tree, TreeFileArtifact... treeFiles) {
    var treeValue = TreeArtifactValue.newBuilder(tree);
    for (var treeFile : treeFiles) {
      treeValue.putChild(treeFile, mock(FileArtifactValue.class));
    }
    return environmentWithValues(ImmutableMap.of(tree, treeValue.build()));
  }

  private static Environment environmentWithValues(ImmutableMap<SkyKey, SkyValue> values) {
    return new AbstractSkyFunctionEnvironmentForTesting() {
      @Override
      protected ImmutableMap<SkyKey, ValueOrUntypedException> getValueOrUntypedExceptions(
          Iterable<? extends SkyKey> depKeys) {
        return stream(depKeys)
            .collect(
                toImmutableMap(
                    Function.identity(),
                    key -> {
                      @Nullable SkyValue value = values.get(key);
                      return value != null
                          ? ValueOrUntypedException.ofValueUntyped(value)
                          : ValueOrUntypedException.ofNull();
                    }));
      }

      @Override
      public ExtendedEventHandler getListener() {
        throw new UnsupportedOperationException();
      }
    };
  }
}
