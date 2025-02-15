// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.Tuple;

/**
 * A helper class for collecting instrumented files and metadata for a target.
 */
public final class InstrumentedFilesCollector {

  private InstrumentedFilesCollector() {}

  /**
   * Forwards any instrumented files from the given target's dependencies (as defined in {@code
   * dependencyAttributes}) for further export. No files from this target are considered
   * instrumented.
   *
   * @return instrumented file provider of all dependencies in {@code dependencyAttributes}
   */
  public static InstrumentedFilesInfo forward(
      RuleContext ruleContext, String... dependencyAttributes) {
    return collect(
        ruleContext,
        new InstrumentationSpec(FileTypeSet.NO_FILE).withDependencyAttributes(dependencyAttributes),
        /* localMetadataCollector= */ null,
        /* rootFiles= */ null,
        /* reportedToActualSources= */ NestedSetBuilder.<Tuple>emptySet(Order.STABLE_ORDER));
  }

  public static InstrumentedFilesInfo forwardAll(RuleContext ruleContext) {
    if (!ruleContext.getConfiguration().isCodeCoverageEnabled()) {
      return InstrumentedFilesInfo.EMPTY;
    }
    InstrumentedFilesInfoBuilder instrumentedFilesInfoBuilder =
        new InstrumentedFilesInfoBuilder(ruleContext);
    for (TransitiveInfoCollection dep : getAllNonToolPrerequisites(ruleContext)) {
      instrumentedFilesInfoBuilder.addFromDependency(dep);
    }
    return instrumentedFilesInfoBuilder.build();
  }

  public static InstrumentedFilesInfo collect(RuleContext ruleContext, InstrumentationSpec spec) {
    return collect(
        ruleContext,
        spec,
        NO_METADATA_COLLECTOR,
        ImmutableList.of(),
        /* reportedToActualSources= */ NestedSetBuilder.<Tuple>emptySet(Order.STABLE_ORDER));
  }

  public static InstrumentedFilesInfo collect(
      RuleContext ruleContext, InstrumentationSpec spec, NestedSet<Tuple> reportedToActualSources) {
    return collect(
        ruleContext,
        spec,
        NO_METADATA_COLLECTOR,
        ImmutableList.of(),
        NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER),
        ImmutableMap.of(),
        false,
        reportedToActualSources);
  }

  public static InstrumentedFilesInfo collect(
      RuleContext ruleContext,
      InstrumentationSpec spec,
      LocalMetadataCollector localMetadataCollector,
      Iterable<Artifact> rootFiles) {
    return collect(
        ruleContext,
        spec,
        localMetadataCollector,
        rootFiles,
        /* reportedToActualSources= */ NestedSetBuilder.<Tuple>emptySet(Order.STABLE_ORDER));
  }

  public static InstrumentedFilesInfo collect(
      RuleContext ruleContext,
      InstrumentationSpec spec,
      LocalMetadataCollector localMetadataCollector,
      Iterable<Artifact> rootFiles,
      NestedSet<Tuple> reportedToActualSources) {
    return collect(
        ruleContext,
        spec,
        localMetadataCollector,
        rootFiles,
        NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER),
        ImmutableMap.of(),
        false,
        reportedToActualSources);
  }

  /**
   * Collects transitive instrumentation data from dependencies, collects local source files from
   * dependencies, collects local metadata files by traversing the action graph of the current
   * configured target, collect rule-specific instrumentation support files and creates baseline
   * coverage actions for the transitive closure of source files (if <code>withBaselineCoverage
   * </code> is true).
   */
  public static InstrumentedFilesInfo collect(
      RuleContext ruleContext,
      InstrumentationSpec spec,
      LocalMetadataCollector localMetadataCollector,
      Iterable<Artifact> rootFiles,
      NestedSet<Artifact> coverageSupportFiles,
      ImmutableMap<String, String> coverageEnvironment,
      boolean withBaselineCoverage) {
    return collect(
        ruleContext,
        spec,
        localMetadataCollector,
        rootFiles,
        coverageSupportFiles,
        coverageEnvironment,
        withBaselineCoverage,
        /* reportedToActualSources= */ NestedSetBuilder.<Tuple>emptySet(Order.STABLE_ORDER));
  }

  public static InstrumentedFilesInfo collect(
      RuleContext ruleContext,
      InstrumentationSpec spec,
      @Nullable LocalMetadataCollector localMetadataCollector,
      @Nullable Iterable<Artifact> rootFiles,
      NestedSet<Artifact> coverageSupportFiles,
      ImmutableMap<String, String> coverageEnvironment,
      boolean withBaselineCoverage,
      NestedSet<Tuple> reportedToActualSources) {
    return collect(
        ruleContext,
        spec,
        localMetadataCollector,
        rootFiles,
        coverageSupportFiles,
        coverageEnvironment,
        withBaselineCoverage,
        reportedToActualSources,
        /* additionalMetadata= */ null);
  }

  public static InstrumentedFilesInfo collect(
      RuleContext ruleContext,
      InstrumentationSpec spec,
      @Nullable LocalMetadataCollector localMetadataCollector,
      @Nullable Iterable<Artifact> rootFiles,
      NestedSet<Artifact> coverageSupportFiles,
      ImmutableMap<String, String> coverageEnvironment,
      boolean withBaselineCoverage,
      NestedSet<Tuple> reportedToActualSources,
      @Nullable Iterable<Artifact> additionalMetadata) {
    Preconditions.checkNotNull(ruleContext);
    Preconditions.checkNotNull(spec);

    if (!ruleContext.getConfiguration().isCodeCoverageEnabled()) {
      return InstrumentedFilesInfo.EMPTY;
    }

    InstrumentedFilesInfoBuilder instrumentedFilesInfoBuilder =
        new InstrumentedFilesInfoBuilder(
            ruleContext, coverageSupportFiles, reportedToActualSources);

    // Transitive instrumentation data.
    for (TransitiveInfoCollection dep :
        getPrerequisitesForAttributes(ruleContext, spec.dependencyAttributes)) {
      instrumentedFilesInfoBuilder.addFromDependency(dep);
    }

    // add top-level coverage env last so that it overrides conflicting keys from deps
    instrumentedFilesInfoBuilder.coverageEnvironmentBuilder.putAll(coverageEnvironment);

    // Local sources.
    ImmutableSet<Artifact> localSources = ImmutableSet.of();
    if (shouldIncludeLocalSources(
        ruleContext.getConfiguration(), ruleContext.getLabel(), ruleContext.isTestTarget())) {
      ImmutableSet.Builder<Artifact> localSourcesBuilder = ImmutableSet.builder();
      for (TransitiveInfoCollection dep :
          getPrerequisitesForAttributes(ruleContext, spec.sourceAttributes)) {
        for (Artifact artifact : dep.getProvider(FileProvider.class).getFilesToBuild().toList()) {
          if (shouldIncludeArtifact(ruleContext.getConfiguration(), artifact)
              && spec.instrumentedFileTypes.matches(artifact.getFilename())) {
            localSourcesBuilder.add(artifact);
          }
        }
      }
      localSources = localSourcesBuilder.build();
    }
    instrumentedFilesInfoBuilder.setLocalSources(localSources);
    if (withBaselineCoverage) {
      instrumentedFilesInfoBuilder.generateBaselineCoverage();
    }

    // Local metadata files.
    if (localMetadataCollector != null) {
      instrumentedFilesInfoBuilder.collectLocalMetadata(localMetadataCollector, rootFiles);
    }

    if (additionalMetadata != null) {
      instrumentedFilesInfoBuilder.addMetadataFiles(additionalMetadata);
    }

    return instrumentedFilesInfoBuilder.build();
  }

  /**
   * Return whether the sources included by {@code target} (a {@link TransitiveInfoCollection}
   * representing a rule) should be instrumented according the --instrumentation_filter and
   * --instrument_test_targets settings in {@code config}.
   */
  public static boolean shouldIncludeLocalSources(
      BuildConfigurationValue config, TransitiveInfoCollection target) {
    return shouldIncludeLocalSources(config, target.getLabel(),
        target.getProvider(TestProvider.class) != null);
  }

  /**
   * Return whether the sources of the rule in {@code ruleContext} should be instrumented based on
   * the --instrumentation_filter and --instrument_test_targets config settings.
   */
  public static boolean shouldIncludeLocalSources(
      BuildConfigurationValue config, Label label, boolean isTest) {
    return ((config.shouldInstrumentTestTargets() || !isTest)
        && config.getInstrumentationFilter().isIncluded(label.toString()));
  }

  /**
   * Return whether the artifact should be collected based on the origin of the artifact and the
   * --experimental_collect_code_coverage_for_generated_files config setting.
   */
  public static boolean shouldIncludeArtifact(BuildConfigurationValue config, Artifact artifact) {
    return artifact.isSourceArtifact() || config.shouldCollectCodeCoverageForGeneratedFiles();
  }

  /**
   * The set of file types and attributes to visit to collect instrumented files for a certain rule
   * type. The class is intentionally immutable, so that a single instance is sufficient for all
   * rules of the same type (and in some cases all rules of related types, such as all {@code foo_*}
   * rules).
   */
  @Immutable
  public static final class InstrumentationSpec {
    private final FileTypeSet instrumentedFileTypes;

    /** The list of attributes which should be checked for sources. */
    private final ImmutableList<String> sourceAttributes;

    /** The list of attributes from which to collect transitive coverage information. */
    private final ImmutableList<String> dependencyAttributes;

    private InstrumentationSpec(
        FileTypeSet instrumentedFileTypes,
        ImmutableList<String> instrumentedSourceAttributes,
        ImmutableList<String> instrumentedDependencyAttributes) {
      this.instrumentedFileTypes = instrumentedFileTypes;
      this.sourceAttributes = instrumentedSourceAttributes;
      this.dependencyAttributes = instrumentedDependencyAttributes;
    }

    public InstrumentationSpec(FileTypeSet instrumentedFileTypes) {
      this(instrumentedFileTypes, ImmutableList.of(), ImmutableList.of());
    }

    /**
     * Returns a new instrumentation spec with the given attribute names replacing the ones stored
     * in this object.
     */
    public InstrumentationSpec withSourceAttributes(Collection<String> attributes) {
      return new InstrumentationSpec(
          instrumentedFileTypes, ImmutableList.copyOf(attributes), dependencyAttributes);
    }

    /**
     * Returns a new instrumentation spec with the given attribute names replacing the ones stored
     * in this object.
     */
    public InstrumentationSpec withSourceAttributes(String... attributes) {
      return withSourceAttributes(ImmutableList.copyOf(attributes));
    }

    /**
     * Returns a new instrumentation spec with the given attribute names replacing the ones stored
     * in this object.
     */
    public InstrumentationSpec withDependencyAttributes(Collection<String> attributes) {
      return new InstrumentationSpec(
          instrumentedFileTypes, sourceAttributes, ImmutableList.copyOf(attributes));
    }

    /**
     * Returns a new instrumentation spec with the given attribute names replacing the ones stored
     * in this object.
     */
    public InstrumentationSpec withDependencyAttributes(String... attributes) {
      return withDependencyAttributes(ImmutableList.copyOf(attributes));
    }
  }

  /**
   * The implementation for the local metadata collection. The intention is that implementations
   * recurse over the locally (i.e., for that configured target) created actions and collect
   * metadata files.
   */
  public abstract static class LocalMetadataCollector {
    /**
     * Recursively runs over the local actions and add metadata files to the metadataFilesBuilder.
     */
    public abstract void collectMetadataArtifacts(
        Iterable<Artifact> artifacts, AnalysisEnvironment analysisEnvironment,
        NestedSetBuilder<Artifact> metadataFilesBuilder);

    /**
     * Adds action output of a particular type to metadata files.
     *
     * <p>Only adds the first output that matches the given file type.
     *
     * @param metadataFilesBuilder builder to collect metadata files
     * @param action the action whose outputs to scan
     * @param fileType the filetype of outputs which should be collected
     */
    protected void addOutputs(NestedSetBuilder<Artifact> metadataFilesBuilder,
                              ActionAnalysisMetadata action, FileType fileType) {
      for (Artifact output : action.getOutputs()) {
        if (fileType.matches(output.getFilename())) {
          metadataFilesBuilder.add(output);
          break;
        }
      }
    }
  }

  private static class InstrumentedFilesInfoBuilder {

    final RuleContext ruleContext;
    final NestedSetBuilder<Artifact> instrumentedFilesBuilder;
    final NestedSetBuilder<Artifact> metadataFilesBuilder;
    final NestedSetBuilder<Artifact> baselineCoverageArtifactsBuilder;
    final NestedSetBuilder<Artifact> coverageSupportFilesBuilder;
    final ImmutableMap.Builder<String, String> coverageEnvironmentBuilder;
    final NestedSet<Tuple> reportedToActualSources;
    NestedSet<Artifact> localSources;
    boolean generateBaselineCoverage;

    InstrumentedFilesInfoBuilder(
        RuleContext ruleContext,
        NestedSet<Artifact> coverageSupportFiles,
        NestedSet<Tuple> reportedToActualSources) {
      this.ruleContext = ruleContext;
      instrumentedFilesBuilder = NestedSetBuilder.stableOrder();
      metadataFilesBuilder = NestedSetBuilder.stableOrder();
      baselineCoverageArtifactsBuilder = NestedSetBuilder.stableOrder();
      coverageSupportFilesBuilder =
          NestedSetBuilder.<Artifact>stableOrder().addTransitive(coverageSupportFiles);
      coverageEnvironmentBuilder = ImmutableMap.builder();
      this.reportedToActualSources = reportedToActualSources;
    }

    InstrumentedFilesInfoBuilder(RuleContext ruleContext) {
      this(
          ruleContext,
          NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.<Tuple>emptySet(Order.STABLE_ORDER));
    }

    void addFromDependency(TransitiveInfoCollection dep) {
      InstrumentedFilesInfo provider = dep.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
      if (provider != null) {
        instrumentedFilesBuilder.addTransitive(provider.getInstrumentedFiles());
        metadataFilesBuilder.addTransitive(provider.getInstrumentationMetadataFiles());
        baselineCoverageArtifactsBuilder.addTransitive(provider.getBaselineCoverageArtifacts());
        coverageSupportFilesBuilder.addTransitive(provider.getCoverageSupportFiles());
        coverageEnvironmentBuilder.putAll(provider.getCoverageEnvironment());
      }
    }

    void setLocalSources(ImmutableSet<Artifact> localSources) {
      // Wrap in a nested set to share the set between the transitive set of instrumented files and
      // the inputs to the local baseline coverage action.
      NestedSet<Artifact> localSourcesNestedSet =
          NestedSetBuilder.wrap(Order.STABLE_ORDER, localSources);
      instrumentedFilesBuilder.addTransitive(localSourcesNestedSet);
      this.localSources = localSourcesNestedSet;
    }

    public void generateBaselineCoverage() {
      generateBaselineCoverage = true;
    }

    void collectLocalMetadata(
        LocalMetadataCollector localMetadataCollector, Iterable<Artifact> rootFiles) {
      localMetadataCollector.collectMetadataArtifacts(
          rootFiles, ruleContext.getAnalysisEnvironment(), metadataFilesBuilder);
    }

    void addMetadataFiles(Iterable<Artifact> files) {
      metadataFilesBuilder.addAll(files);
    }

    InstrumentedFilesInfo build() {
      if (generateBaselineCoverage) {
        var baselineCoverageAction = BaselineCoverageAction.create(ruleContext, localSources);
        ruleContext.registerAction(baselineCoverageAction);
        baselineCoverageArtifactsBuilder.add(baselineCoverageAction.getPrimaryOutput());
      }

      return new InstrumentedFilesInfo(
          instrumentedFilesBuilder.build(),
          metadataFilesBuilder.build(),
          baselineCoverageArtifactsBuilder.build(),
          coverageSupportFilesBuilder.build(),
          coverageEnvironmentBuilder.buildKeepingLast(),
          reportedToActualSources);
    }
  }

  /**
   * An explicit constant for a {@link LocalMetadataCollector} that doesn't collect anything.
   */
  public static final LocalMetadataCollector NO_METADATA_COLLECTOR = null;

  private static Iterable<TransitiveInfoCollection> getPrerequisitesForAttributes(
      RuleContext ruleContext, Collection<String> attributeNames) {
    List<TransitiveInfoCollection> prerequisites = new ArrayList<>();
    for (String attributeName : attributeNames) {
      Attribute attribute =
          ruleContext.getRule().getRuleClassObject().getAttributeByNameMaybe(attributeName);
      if (attribute != null) {
        prerequisites.addAll(attributeDependencyPrerequisites(attribute, ruleContext));
      }
    }
    return prerequisites;
  }

  private static Iterable<TransitiveInfoCollection> getAllNonToolPrerequisites(
      RuleContext ruleContext) {
    List<TransitiveInfoCollection> prerequisites = new ArrayList<>();
    for (Attribute attribute : ruleContext.getRule().getAttributes()) {
      prerequisites.addAll(attributeDependencyPrerequisites(attribute, ruleContext));
    }
    return prerequisites;
  }

  private static List<? extends TransitiveInfoCollection> attributeDependencyPrerequisites(
      Attribute attribute, RuleContext ruleContext) {
    if (attribute.getType().getLabelClass() == LabelClass.DEPENDENCY) {
      return ruleContext.getPrerequisites(attribute.getName());
    }
    return ImmutableList.of();
  }
}
