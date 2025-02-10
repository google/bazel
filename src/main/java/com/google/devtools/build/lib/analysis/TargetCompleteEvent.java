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

package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationId;
import static com.google.devtools.build.lib.buildeventstream.TestFileNameConstants.BASELINE_COVERAGE;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CompletionContext;
import com.google.devtools.build.lib.actions.CompletionContext.ArtifactReceiver;
import com.google.devtools.build.lib.actions.EventReportingArtifacts;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.ArtifactsInOutputGroup;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext.OutputGroupFileMode;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions.OutputGroupFileModes;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.OutputGroup;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TargetComplete;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithConfiguration;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithOrderConstraint;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.TestTimeout;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.protobuf.util.Durations;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** This event is fired as soon as a target is either built or fails. */
public final class TargetCompleteEvent
    implements SkyValue,
        BuildEventWithOrderConstraint,
        EventReportingArtifacts,
        BuildEventWithConfiguration {

  /** Lightweight data needed about the configured target in this event. */
  public static class ExecutableTargetData {
    @Nullable private final RunfilesSupport runfilesSupport;
    @Nullable private final Artifact executable;

    private ExecutableTargetData(ConfiguredTargetAndData targetAndData) {
      FilesToRunProvider provider =
          targetAndData.getConfiguredTarget().getProvider(FilesToRunProvider.class);
      if (provider != null) {
        this.executable = provider.getExecutable();
        this.runfilesSupport = provider.getRunfilesSupport();
      } else {
        this.executable = null;
        this.runfilesSupport = null;
      }
    }

    @Nullable
    public Path getRunfilesDirectory() {
      if (runfilesSupport != null) {
        return runfilesSupport.getRunfilesDirectory();
      }
      return null;
    }

    @Nullable
    public Artifact getExecutable() {
      return executable;
    }
  }

  private static final BaseEncoding LOWERCASE_HEX_ENCODING = BaseEncoding.base16().lowerCase();

  private final Label label;
  private final ConfiguredTargetKey configuredTargetKey;
  private final NestedSet<Cause> rootCauses;
  private final ImmutableList<BuildEventId> postedAfter;
  private final CompletionContext completionContext;
  private final ImmutableMap<String, ArtifactsInOutputGroup> outputs;
  @Nullable private final Artifact baselineCoverageArtifact;
  @Nullable private final LocalFile baselineCoverage;
  // The label as appeared in the BUILD file.
  private final Label originalLabel;
  private final boolean isTest;
  private final boolean announceTargetSummary;
  @Nullable private final Long testTimeoutSeconds;
  @Nullable private final TestProvider.TestParams testParams;
  private final BuildEvent configurationEvent;
  private final BuildEventId configEventId;
  private final Iterable<String> tags;
  private final ExecutableTargetData executableTargetData;
  @Nullable private final DetailedExitCode detailedExitCode;

  private TargetCompleteEvent(
      ConfiguredTargetAndData targetAndData,
      NestedSet<Cause> rootCauses,
      CompletionContext completionContext,
      ImmutableMap<String, ArtifactsInOutputGroup> outputs,
      boolean isTest,
      boolean announceTargetSummary) {
    this.rootCauses =
        (rootCauses == null) ? NestedSetBuilder.emptySet(Order.STABLE_ORDER) : rootCauses;
    this.executableTargetData = new ExecutableTargetData(targetAndData);
    ImmutableList.Builder<BuildEventId> postedAfterBuilder = ImmutableList.builder();
    this.label = targetAndData.getConfiguredTarget().getLabel();
    this.originalLabel = targetAndData.getConfiguredTarget().getOriginalLabel();
    this.configuredTargetKey =
        ConfiguredTargetKey.fromConfiguredTarget(targetAndData.getConfiguredTarget());
    postedAfterBuilder.add(BuildEventIdUtil.targetConfigured(originalLabel));
    DetailedExitCode mostImportantDetailedExitCode = null;
    for (Cause cause : this.rootCauses.toList()) {
      mostImportantDetailedExitCode =
          DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
              mostImportantDetailedExitCode, cause.getDetailedExitCode());
      postedAfterBuilder.add(cause.getIdProto());
    }
    detailedExitCode = mostImportantDetailedExitCode;
    this.completionContext = completionContext;
    this.outputs = outputs;
    this.isTest = isTest;
    this.announceTargetSummary = announceTargetSummary;
    this.testTimeoutSeconds = isTest ? getTestTimeoutSeconds(targetAndData) : null;
    BuildConfigurationValue configuration = targetAndData.getConfiguration();
    this.configEventId = configurationId(configuration);
    this.configurationEvent = configuration != null ? configuration.toBuildEvent() : null;
    this.testParams =
        isTest
            ? targetAndData.getConfiguredTarget().getProvider(TestProvider.class).getTestParams()
            : null;
    this.baselineCoverageArtifact = baselineCoverageArtifact(targetAndData);
    this.baselineCoverage =
        baselineCoverageArtifact == null
            ? null
            : new LocalFile(
                completionContext.pathResolver().toPath(baselineCoverageArtifact),
                LocalFileType.COVERAGE_OUTPUT,
                completionContext.getBaselineCoverageValue());
    this.postedAfter = postedAfterBuilder.build();
    this.tags = targetAndData.getRuleTags();
  }

  @Nullable
  private static Artifact baselineCoverageArtifact(ConfiguredTargetAndData targetAndData) {
    InstrumentedFilesInfo instrumentedFilesProvider =
        targetAndData.getConfiguredTarget().get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    if (instrumentedFilesProvider == null) {
      return null;
    }
    return instrumentedFilesProvider.getBaselineCoverageArtifact();
  }

  /** Construct a successful target completion event. */
  public static TargetCompleteEvent successfulBuild(
      ConfiguredTargetAndData ct,
      CompletionContext completionContext,
      ImmutableMap<String, ArtifactsInOutputGroup> outputs,
      boolean announceTargetSummary) {
    return new TargetCompleteEvent(
        ct, null, completionContext, outputs, false, announceTargetSummary);
  }

  /** Construct a successful target completion event for a target that will be tested. */
  public static TargetCompleteEvent successfulBuildSchedulingTest(
      ConfiguredTargetAndData ct,
      CompletionContext completionContext,
      ImmutableMap<String, ArtifactsInOutputGroup> outputs,
      boolean announceTargetSummary) {
    return new TargetCompleteEvent(
        ct, null, completionContext, outputs, true, announceTargetSummary);
  }

  /**
   * Construct a target completion event for a failed target, with the given non-empty root causes.
   */
  public static TargetCompleteEvent createFailed(
      ConfiguredTargetAndData ct,
      CompletionContext completionContext,
      NestedSet<Cause> rootCauses,
      ImmutableMap<String, ArtifactsInOutputGroup> outputs,
      boolean announceTargetSummary) {
    Preconditions.checkArgument(!rootCauses.isEmpty());
    return new TargetCompleteEvent(
        ct, rootCauses, completionContext, outputs, false, announceTargetSummary);
  }

  /** Returns the label of the target associated with the event. */
  public Label getLabel() {
    return label;
  }

  /**
   * Returns the original label of the target.
   *
   * <p>See {@link ConfiguredTarget#getOriginalLabel()}.
   */
  public Label getOriginalLabel() {
    return originalLabel;
  }

  public ConfiguredTargetKey getConfiguredTargetKey() {
    return configuredTargetKey;
  }

  public ExecutableTargetData getExecutableTargetData() {
    return executableTargetData;
  }

  /** Determines whether the target has failed or succeeded. */
  public boolean failed() {
    return !rootCauses.isEmpty();
  }

  /** Get the root causes of the target. May be empty. */
  public NestedSet<Cause> getRootCauses() {
    return rootCauses;
  }

  public Iterable<Artifact> getLegacyFilteredImportantArtifacts() {
    // TODO(ulfjack): This duplicates code in ArtifactsToBuild.
    NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
    for (ArtifactsInOutputGroup artifactsInOutputGroup : outputs.values()) {
      if (artifactsInOutputGroup.areImportant()) {
        builder.addTransitive(artifactsInOutputGroup.getArtifacts());
      }
    }
    return Iterables.filter(
        builder.build().toList(),
        (artifact) -> !artifact.isSourceArtifact() && !artifact.isRunfilesTree());
  }

  @Override
  public BuildEventId getEventId() {
    return BuildEventIdUtil.targetCompleted(originalLabel, configEventId);
  }

  @Override
  public ImmutableList<BuildEventId> getChildrenEvents() {
    ImmutableList.Builder<BuildEventId> childrenBuilder = ImmutableList.builder();
    for (Cause cause : rootCauses.toList()) {
      childrenBuilder.add(cause.getIdProto());
    }
    if (isTest) {
      // For tests, announce all the test actions that will minimally happen (except for
      // interruption). If after the result of a test action another attempt is necessary,
      // it will be announced with the action that made the new attempt necessary.
      for (int run = 0; run < Math.max(testParams.getRuns(), 1); run++) {
        for (int shard = 0; shard < Math.max(testParams.getShards(), 1); shard++) {
          childrenBuilder.add(BuildEventIdUtil.testResult(label, run, shard, configEventId));
        }
      }
      childrenBuilder.add(BuildEventIdUtil.testSummary(label, configEventId));
    }
    if (announceTargetSummary) {
      childrenBuilder.add(BuildEventIdUtil.targetSummary(originalLabel, configEventId));
    }
    return childrenBuilder.build();
  }

  public CompletionContext getCompletionContext() {
    return completionContext;
  }

  @Nullable
  public ArtifactsInOutputGroup getOutputGroup(String outputGroup) {
    return outputs.get(outputGroup);
  }

  // TODO(aehlig): remove as soon as we managed to get rid of the deprecated "important_output"
  // field.

  private static void addFilesDirectlyToProtoField(
      CompletionContext completionContext,
      TargetComplete.Builder builder,
      BuildEventContext converters,
      Iterable<Artifact> artifacts) {
    addFilesDirectlyToProtoField(
        completionContext,
        builder::addImportantOutput,
        Artifact::getRootRelativePathString,
        converters,
        artifacts);
  }

  private static void addFilesDirectlyToProtoField(
      CompletionContext completionContext,
      Consumer<BuildEventStreamProtos.File> addFile,
      Function<Artifact, String> artifactNameFunction,
      BuildEventContext converters,
      Iterable<Artifact> artifacts) {
    completionContext.visitArtifacts(
        filterFilesets(artifacts),
        new ArtifactReceiver() {
          @Override
          public void accept(Artifact artifact) {
            String name = artifactNameFunction.apply(artifact);
            String uri =
                converters.pathConverter().apply(completionContext.pathResolver().toPath(artifact));
            BuildEventStreamProtos.File file =
                newFileFromArtifact(name, artifact, completionContext, uri);
            // Omit files with unknown contents (e.g. if uploading failed).
            if (file.getFileCase() != BuildEventStreamProtos.File.FileCase.FILE_NOT_SET) {
              addFile.accept(file);
            }
          }

          @Override
          public void acceptFilesetMapping(
              Artifact fileset, PathFragment relativePath, Path targetFile) {
            throw new IllegalStateException(fileset + " should have been filtered out");
          }
        });
  }

  private static Iterable<Artifact> filterFilesets(Iterable<Artifact> artifacts) {
    return Iterables.filter(artifacts, artifact -> !artifact.isFileset());
  }

  public static BuildEventStreamProtos.File newFileFromArtifact(
      @Nullable String name,
      Artifact artifact,
      PathFragment relPath,
      CompletionContext completionContext,
      @Nullable String uri) {
    if (name == null) {
      name =
          StringEncoding.internalToUnicode(
              artifact.getRootRelativePath().getRelative(relPath).getPathString());
    }
    File.Builder file =
        File.newBuilder()
            .setName(name)
            .addAllPathPrefix(
                Iterables.transform(
                    artifact.getRoot().getExecPath().segments(),
                    StringEncoding::internalToUnicode));
    FileArtifactValue fileArtifactValue = completionContext.getFileArtifactValue(artifact);
    if (fileArtifactValue != null && fileArtifactValue.getType().isSymlink()) {
      file.setSymlinkTargetPath(
          StringEncoding.internalToUnicode(fileArtifactValue.getUnresolvedSymlinkTarget()));
    } else if (fileArtifactValue != null && fileArtifactValue.getType().exists()) {
      byte[] digest = fileArtifactValue.getDigest();
      if (digest != null) {
        file.setDigest(LOWERCASE_HEX_ENCODING.encode(digest));
      }
      file.setLength(fileArtifactValue.getSize());
    }
    if (uri != null) {
      file.setUri(StringEncoding.internalToUnicode(uri));
    }
    return file.build();
  }

  public static BuildEventStreamProtos.File newFileFromArtifact(
      String name, Artifact artifact, CompletionContext completionContext, @Nullable String uri) {
    return newFileFromArtifact(name, artifact, PathFragment.EMPTY_FRAGMENT, completionContext, uri);
  }

  public static BuildEventStreamProtos.File newFileFromArtifact(
      Artifact artifact, CompletionContext completionContext, @Nullable String uri) {
    return newFileFromArtifact(
        /* name= */ null, artifact, PathFragment.EMPTY_FRAGMENT, completionContext, uri);
  }

  @Override
  public ImmutableList<LocalFile> referencedLocalFiles() {
    ImmutableList.Builder<LocalFile> builder = ImmutableList.builder();
    for (ArtifactsInOutputGroup group : outputs.values()) {
      if (group.areImportant()) {
        completionContext.visitArtifacts(
            filterFilesets(group.getArtifacts().toList()),
            new ArtifactReceiver() {
              @Override
              public void accept(Artifact artifact) {
                FileArtifactValue metadata = completionContext.getFileArtifactValue(artifact);
                builder.add(
                    new LocalFile(
                        completionContext.pathResolver().toPath(artifact),
                        LocalFileType.forArtifact(artifact, metadata),
                        metadata));
              }

              @Override
              public void acceptFilesetMapping(
                  Artifact fileset, PathFragment name, Path targetFile) {
                throw new IllegalStateException(fileset + " should have been filtered out");
              }
            });
      }
    }
    if (baselineCoverage != null) {
      builder.add(baselineCoverage);
    }
    return builder.build();
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
    BuildEventStreamProtos.TargetComplete.Builder builder =
        BuildEventStreamProtos.TargetComplete.newBuilder();

    boolean failed = failed();
    builder.setSuccess(!failed);
    if (detailedExitCode != null) {
      if (!failed) {
        BugReport.sendBugReport(
            new IllegalStateException("Detailed exit code with success? " + detailedExitCode));
      }
      FailureDetails.FailureDetail failureDetail = detailedExitCode.getFailureDetail();
      if (failureDetail != null) {
        builder.setFailureDetail(failureDetail);
      }
    }
    builder.addAllTag(tags).addAllOutputGroup(getOutputFilesByGroup(converters));

    if (isTest) {
      builder.setTestTimeout(Durations.fromSeconds(testTimeoutSeconds));
      builder.setTestTimeoutSeconds(testTimeoutSeconds);
    }

    Iterable<Artifact> filteredImportantArtifacts = getLegacyFilteredImportantArtifacts();
    for (Artifact artifact : filteredImportantArtifacts) {
      if (artifact.isDirectory()) {
        builder.addDirectoryOutput(
            newFileFromArtifact(artifact, completionContext, /* uri= */ null));
      }
    }
    // TODO(aehlig): remove direct reporting of artifacts as soon as clients no longer need it.
    if (converters.getOptions().legacyImportantOutputs) {
      addFilesDirectlyToProtoField(
          completionContext, builder, converters, filteredImportantArtifacts);
      if (baselineCoverage != null) {
        addFilesDirectlyToProtoField(
            completionContext,
            builder::addImportantOutput,
            artifact -> BASELINE_COVERAGE,
            converters,
            ImmutableList.of(baselineCoverageArtifact));
      }
    }

    BuildEventStreamProtos.TargetComplete complete = builder.build();
    return GenericBuildEvent.protoChaining(this).setCompleted(complete).build();
  }

  @Override
  public ImmutableList<BuildEventId> postedAfter() {
    return postedAfter;
  }

  @Override
  public ReportedArtifacts reportedArtifacts(OutputGroupFileModes outputGroupFileModes) {
    return toReportedArtifacts(
        outputs, completionContext, baselineCoverageArtifact, outputGroupFileModes);
  }

  @Override
  public boolean storeForReplay() {
    return true;
  }

  static ReportedArtifacts toReportedArtifacts(
      ImmutableMap<String, ArtifactsInOutputGroup> outputs,
      CompletionContext completionContext,
      @Nullable Artifact baselineCoverageArtifact,
      OutputGroupFileModes outputGroupFileModes) {
    ImmutableSet.Builder<NestedSet<Artifact>> builder = ImmutableSet.builder();
    for (var entry : outputs.entrySet()) {
      String groupName = entry.getKey();
      OutputGroupFileMode mode = outputGroupFileModes.getMode(groupName);
      var artifactsInGroup = entry.getValue();
      if (artifactsInGroup.areImportant()) {
        if (mode == OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY
            || mode == OutputGroupFileMode.BOTH) {
          builder.add(artifactsInGroup.getArtifacts());
        }
      }
    }
    if (baselineCoverageArtifact != null) {
      OutputGroupFileMode mode = outputGroupFileModes.getMode(BASELINE_COVERAGE);
      if (mode == OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY || mode == OutputGroupFileMode.BOTH) {
        builder.add(NestedSetBuilder.create(Order.STABLE_ORDER, baselineCoverageArtifact));
      }
    }
    return new ReportedArtifacts(builder.build(), completionContext);
  }

  @Override
  public Collection<BuildEvent> getConfigurations() {
    return configurationEvent != null ? ImmutableList.of(configurationEvent) : ImmutableList.of();
  }

  private ImmutableList<OutputGroup> getOutputFilesByGroup(BuildEventContext converters) {
    return toOutputGroupProtos(outputs, baselineCoverageArtifact, completionContext, converters);
  }

  /** Returns {@link OutputGroup} protos for given output groups and optional coverage artifacts. */
  static ImmutableList<OutputGroup> toOutputGroupProtos(
      ImmutableMap<String, ArtifactsInOutputGroup> outputs,
      @Nullable Artifact baselineCoverageArtifact,
      CompletionContext completionContext,
      BuildEventContext converters) {
    ImmutableList.Builder<OutputGroup> groups = ImmutableList.builder();
    outputs.forEach(
        (outputGroup, artifactsInOutputGroup) -> {
          if (!artifactsInOutputGroup.areImportant()) {
            return;
          }
          NestedSet<Artifact> artifacts = artifactsInOutputGroup.getArtifacts();
          groups.add(
              makeOutputGroupProto(
                  completionContext,
                  converters,
                  outputGroup,
                  artifactsInOutputGroup.isIncomplete(),
                  () -> artifacts,
                  artifacts::toList));
        });
    if (baselineCoverageArtifact != null) {
      groups.add(
          makeOutputGroupProto(
              completionContext,
              converters,
              BASELINE_COVERAGE,
              /* outputGroupIncomplete= */ false,
              () -> NestedSetBuilder.create(Order.STABLE_ORDER, baselineCoverageArtifact),
              () -> ImmutableList.of(baselineCoverageArtifact)));
    }
    return groups.build();
  }

  /**
   * Constructs an {@link OutputGroup} message based on how the group has been configured to report
   * its artifacts on the command-line.
   */
  private static OutputGroup makeOutputGroupProto(
      CompletionContext completionContext,
      BuildEventContext converters,
      String outputGroup,
      boolean outputGroupIncomplete,
      Supplier<NestedSet<Artifact>> artifactsToReport,
      Supplier<List<Artifact>> artifactListSupplier) {
    OutputGroup.Builder builder =
        OutputGroup.newBuilder().setName(outputGroup).setIncomplete(outputGroupIncomplete);
    OutputGroupFileMode fileMode = converters.getFileModeForOutputGroup(outputGroup);
    if (fileMode == OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY
        || fileMode == OutputGroupFileMode.BOTH) {
      ArtifactGroupNamer namer = converters.artifactGroupNamer();
      builder.addFileSets(namer.apply(artifactsToReport.get().toNode()));
    }
    if (fileMode == OutputGroupFileMode.INLINE_ONLY || fileMode == OutputGroupFileMode.BOTH) {
      addFilesDirectlyToProtoField(
          completionContext,
          builder::addInlineFiles,
          Artifact::getRootRelativePathString,
          converters,
          artifactListSupplier.get());
    }
    return builder.build();
  }

  /**
   * Returns timeout value in seconds that should be used for all test actions under this configured
   * target. We always use the "categorical timeouts" which are based on the --test_timeout flag. A
   * rule picks its timeout but ends up with the same effective value as all other rules in that
   * category and configuration.
   */
  private static Long getTestTimeoutSeconds(ConfiguredTargetAndData targetAndData) {
    BuildConfigurationValue configuration = targetAndData.getConfiguration();
    TestTimeout categoricalTimeout = targetAndData.getTestTimeout();
    return configuration
        .getFragment(TestConfiguration.class)
        .getTestTimeout()
        .get(categoricalTimeout)
        .toSeconds();
  }
}
