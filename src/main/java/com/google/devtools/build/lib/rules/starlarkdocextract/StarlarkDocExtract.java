// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.starlarkdocextract;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.packages.ImplicitOutputsFunction.fromTemplates;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;
import static java.util.stream.Collectors.partitioningBy;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.actions.BinaryFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.starlarkdocextract.ExtractionException;
import com.google.devtools.build.lib.starlarkdocextract.LabelRenderer;
import com.google.devtools.build.lib.starlarkdocextract.ModuleInfoExtractor;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ModuleInfo;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.TextFormat;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.starlark.java.eval.Module;

/** Implementation of the {@code starlark_doc_extract} rule. */
public class StarlarkDocExtract implements RuleConfiguredTargetFactory {
  static final String SRC_ATTR = "src";
  static final String DEPS_ATTR = "deps";
  static final String SYMBOL_NAMES_ATTR = "symbol_names";
  static final String RENDER_MAIN_REPO_NAME = "render_main_repo_name";
  static final SafeImplicitOutputsFunction BINARYPROTO_OUT = fromTemplates("%{name}.binaryproto");
  static final SafeImplicitOutputsFunction TEXTPROTO_OUT = fromTemplates("%{name}.textproto");

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws ActionConflictException, InterruptedException, RuleErrorException {
    RepositoryMappingValue mainRepositoryMappingValue = getMainRepositoryMappingValue(ruleContext);
    RepositoryMapping repositoryMapping = mainRepositoryMappingValue.repositoryMapping();
    Module module = loadModule(ruleContext, repositoryMapping);
    if (module == null) {
      // Skyframe restart
      verify(
          ruleContext.getAnalysisEnvironment().getSkyframeEnv().valuesMissing()
              && !ruleContext.hasErrors());
      return null;
    }
    verifyModuleDeps(ruleContext, module, repositoryMapping);
    Optional<String> mainRepoName = Optional.empty();
    if (ruleContext.attributes().get(RENDER_MAIN_REPO_NAME, BOOLEAN)) {
      mainRepoName = mainRepositoryMappingValue.associatedModuleName();
      if (mainRepoName.isEmpty()) {
        mainRepoName = Optional.of(ruleContext.getWorkspaceName());
      }
    }
    ModuleInfo moduleInfo =
        getModuleInfo(ruleContext, module, new LabelRenderer(repositoryMapping, mainRepoName));

    NestedSet<Artifact> filesToBuild =
        new NestedSetBuilder<Artifact>(Order.STABLE_ORDER)
            .add(createBinaryProtoOutput(ruleContext, moduleInfo))
            .build();
    // Textproto output isn't in filesToBuild: we want to create it only if explicitly requested.
    createTextProtoOutput(ruleContext, moduleInfo);

    return new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(filesToBuild)
        .addProvider(
            RunfilesProvider.class,
            RunfilesProvider.simple(
                new Runfiles.Builder(ruleContext.getWorkspaceName())
                    .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES)
                    .addTransitiveArtifacts(filesToBuild)
                    .build()))
        .build();
  }

  /**
   * Loads the Starlark module from the source file given by the rule's {@code src} attribute.
   *
   * @throws RuleErrorException and reports an error in the rule if the {@code src} attribute refers
   *     to multiple or zero files, a generated file, or a source file which cannot be loaded or
   *     parsed
   * @return the module object, or null on Skyframe restart
   */
  @Nullable
  private static Module loadModule(RuleContext ruleContext, RepositoryMapping repositoryMapping)
      throws RuleErrorException, InterruptedException {
    try (SilentCloseable c = Profiler.instance().profile("BzlDocDump.loadModule")) {
      // Note attr schema validates that src is a .bzl or .scl file.
      Label label = getSourceFileLabel(ruleContext, SRC_ATTR, repositoryMapping);

      // Note getSkyframeEnv() cannot be null while creating a configured target.
      SkyFunction.Environment env = ruleContext.getAnalysisEnvironment().getSkyframeEnv();

      BzlLoadValue bzlLoadValue;
      try {
        // TODO(b/276733504): support loading modules in @_builtins
        bzlLoadValue =
            (BzlLoadValue)
                env.getValueOrThrow(BzlLoadValue.keyForBuild(label), BzlLoadFailedException.class);
      } catch (BzlLoadFailedException e) {
        ruleContext.attributeError(SRC_ATTR, e.getMessage());
        throw new RuleErrorException(e);
      }
      if (bzlLoadValue == null) {
        // Skyframe restart
        return null;
      }
      return bzlLoadValue.getModule();
    }
  }

  /**
   * Retrieves the label of the singular source artifact from a given attribute. Note that we can't
   * simply use {@code ruleContext.attributes().get(attrName, LABEL)} because that does not resolve
   * aliases and filegroups.
   *
   * @throws RuleErrorException if the source is not a singular source artifact, meaning its label
   *     cannot be used as a label for a Starlark load()
   */
  private static Label getSourceFileLabel(
      RuleContext ruleContext, String attrName, RepositoryMapping repositoryMapping)
      throws RuleErrorException {
    Artifact artifact = ruleContext.getPrerequisiteArtifact(attrName);
    ruleContext.assertNoErrors();
    // If ruleContext.getPrerequisiteArtifact() set no errors, we know artifact != null
    if (!artifact.isSourceArtifact()) {
      RuleErrorException error =
          new RuleErrorException(
              String.format(
                  "%s is not a source file and cannot be loaded in Starlark",
                  formatDerivedArtifact(artifact, repositoryMapping)));
      ruleContext.attributeError(attrName, error.getMessage());
      throw error;
    }
    return verifyNotNull(artifact.getOwner());
  }

  private static String formatDerivedArtifact(
      Artifact artifact, RepositoryMapping repositoryMapping) {
    checkArgument(!artifact.isSourceArtifact());
    return String.format(
        "%s (generated by rule %s)",
        artifact.getRepositoryRelativePath(),
        artifact.getOwner().getDisplayForm(repositoryMapping));
  }

  /**
   * Verifies that the module's transitive loads are a subset of the source artifacts in
   * files-to-build of the rule's deps.
   *
   * @throws RuleErrorException if that is not the case.
   */
  // TODO(https://github.com/bazelbuild/bazel/issues/18599): to avoid flattening deps, we could use
  // either (a) a new, native bzl_library-like rule that verifies strict deps, or (b) a new native
  // aspect that verifies strict deps for the existing bzl_library rule. Ideally, however, we ought
  // to get rid of the deps attribute (and the need to verify it) altogether; that requires new
  // dependency machinery for `bazel query` to use the Starlark load graph for collecting the
  // dependencies of starlark_doc_extract's src.
  private static void verifyModuleDeps(
      RuleContext ruleContext, Module module, RepositoryMapping repositoryMapping)
      throws RuleErrorException {
    // Note attr schema validates that deps are .bzl or .scl files.
    Map<Boolean, ImmutableSet<Artifact>> flattenedDepsPartitionedByIsSource =
        ruleContext.getPrerequisites(DEPS_ATTR).stream()
            // TODO(https://github.com/bazelbuild/bazel/issues/18599): we are using FileProvider
            // instead of StarlarkLibraryInfo only because StarlarkLibraryInfo is defined in
            // bazel_skylib, not natively in Bazel.
            .flatMap(dep -> dep.getProvider(FileProvider.class).getFilesToBuild().toList().stream())
            .collect(partitioningBy(Artifact::isSourceArtifact, toImmutableSet()));
    // bzl_library targets may contain both source artifacts and derived artifacts (e.g. generated
    // .bzl files for tests); only the source artifacts can be load()-ed by Bazel.
    ImmutableSet<Artifact> flattenedDepsSourceArtifacts =
        flattenedDepsPartitionedByIsSource.getOrDefault(true, ImmutableSet.of());
    ImmutableSet<Artifact> flattenedDepsDerivedArtifacts =
        flattenedDepsPartitionedByIsSource.getOrDefault(false, ImmutableSet.of());

    ImmutableList<String> topmostUnknownLoads =
        getTopmostUnknownLoads(
            module,
            flattenedDepsSourceArtifacts.stream()
                .map(artifact -> verifyNotNull(artifact.getOwner()))
                .collect(toImmutableSet()),
            repositoryMapping);

    if (!topmostUnknownLoads.isEmpty()) {
      StringBuilder errorMessageBuilder =
          new StringBuilder("missing bzl_library targets for Starlark module(s) ")
              .append(Joiner.on(", ").join(topmostUnknownLoads));
      if (!flattenedDepsDerivedArtifacts.isEmpty()) {
        // TODO(arostovtsev): we ought to print only the derived artifacts having the same
        // root-relative path as topmostUnknownLoads.
        errorMessageBuilder
            .append("\nNote the following are generated file(s) and cannot be loaded in Starlark: ")
            .append(
                Joiner.on(", ")
                    .join(
                        flattenedDepsDerivedArtifacts.stream()
                            .map(artifact -> formatDerivedArtifact(artifact, repositoryMapping))
                            .iterator()));
      }
      RuleErrorException error = new RuleErrorException(errorMessageBuilder.toString());
      ruleContext.attributeError(DEPS_ATTR, error.getMessage());
      throw error;
    }
  }

  /**
   * Finds the topmost modules that are transitively loaded by the given module but not mentioned in
   * the given set of known modules, and returns these modules' display forms.
   *
   * <p>Unknown modules that are only referenced by other unknown modules are not included.
   */
  private static ImmutableList<String> getTopmostUnknownLoads(
      Module module, ImmutableSet<Label> knownModules, RepositoryMapping repositoryMapping) {
    ImmutableList.Builder<String> unknown = ImmutableList.builder();
    Set<Label> visited = new LinkedHashSet<>();
    BazelModuleContext.visitLoadGraphRecursively(
        BazelModuleContext.of(module).loads(),
        label -> {
          if (!visited.add(label)) {
            return false;
          }
          if (!knownModules.contains(label)) {
            unknown.add(label.getDisplayForm(repositoryMapping));
            return false;
          }
          return true;
        });
    return unknown.build();
  }

  /** Returns the main repository's repo mapping value. */
  private static RepositoryMappingValue getMainRepositoryMappingValue(RuleContext ruleContext)
      throws RuleErrorException, InterruptedException {
    RepositoryMappingValue repositoryMappingValue;
    try {
      repositoryMappingValue =
          (RepositoryMappingValue)
              ruleContext
                  .getAnalysisEnvironment()
                  .getSkyframeEnv()
                  .getValueOrThrow(
                      RepositoryMappingValue.key(RepositoryName.MAIN),
                      RepositoryMappingResolutionException.class);
    } catch (RepositoryMappingResolutionException e) {
      ruleContext.ruleError(e.getMessage());
      throw new RuleErrorException(e);
    }
    verifyNotNull(repositoryMappingValue);
    return repositoryMappingValue;
  }

  private static ModuleInfo getModuleInfo(
      RuleContext ruleContext, Module module, LabelRenderer labelRenderer)
      throws RuleErrorException {
    ModuleInfo moduleInfo;
    try {
      moduleInfo =
          new ModuleInfoExtractor(getWantedSymbolPredicate(ruleContext), labelRenderer)
              .extractFrom(module);
    } catch (ExtractionException e) {
      ruleContext.ruleError(e.getMessage());
      throw new RuleErrorException(e);
    }
    return moduleInfo;
  }

  private static Predicate<String> getWantedSymbolPredicate(RuleContext ruleContext) {
    ImmutableList<String> symbolNames =
        ImmutableList.copyOf(ruleContext.attributes().get(SYMBOL_NAMES_ATTR, STRING_LIST));
    if (symbolNames.isEmpty()) {
      return name -> true;
    } else {
      return symbolNames::contains;
    }
  }

  private static Artifact createBinaryProtoOutput(RuleContext ruleContext, ModuleInfo moduleInfo)
      throws InterruptedException {
    Artifact binaryProtoOutput = ruleContext.getImplicitOutputArtifact(BINARYPROTO_OUT);
    ruleContext.registerAction(
        new BinaryFileWriteAction(
            ruleContext.getActionOwner(),
            binaryProtoOutput,
            ByteSource.wrap(moduleInfo.toByteArray()),
            /* makeExecutable= */ false));
    return binaryProtoOutput;
  }

  @Nullable
  @CanIgnoreReturnValue
  private static Artifact createTextProtoOutput(RuleContext ruleContext, ModuleInfo moduleInfo)
      throws InterruptedException, RuleErrorException {
    Artifact textProtoOutput = ruleContext.getImplicitOutputArtifact(TEXTPROTO_OUT);

    StringBuilder textprotoBuilder = new StringBuilder();
    try {
      TextFormat.printer().print(moduleInfo, textprotoBuilder);
    } catch (IOException e) {
      ruleContext.ruleError(e.getMessage());
      throw new RuleErrorException(e);
    }
    ruleContext.registerAction(
        FileWriteAction.create(
            ruleContext,
            textProtoOutput,
            textprotoBuilder.toString(),
            /* makeExecutable= */ false));
    return textProtoOutput;
  }
}
