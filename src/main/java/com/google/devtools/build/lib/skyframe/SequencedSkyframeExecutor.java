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
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.AnalysisProtos.ActionGraphContainer;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction.Factory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.BuildFileName;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.StarlarkSemanticsOptions;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.packages.WorkspaceFileValue.WorkspaceFileKey;
import com.google.devtools.build.lib.pkgcache.PackageCacheOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.query2.aquery.AqueryActionFilter;
import com.google.devtools.build.lib.rules.repository.ManagedDirectoriesKnowledge;
import com.google.devtools.build.lib.skyframe.AspectValue.AspectKey;
import com.google.devtools.build.lib.skyframe.DiffAwarenessManager.ProcessableModifiedFileSet;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.BasicFilesystemDirtinessChecker;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.ExternalDirtinessChecker;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.MissingDiffDirtinessChecker;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.UnionDirtinessChecker;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFilesKnowledge;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType;
import com.google.devtools.build.lib.skyframe.PackageFunction.ActionOnIOExceptionReadingBuildFile;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.actiongraph.ActionGraphDump;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.ResourceUsage;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.BatchStat;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.BuildDriver;
import com.google.devtools.build.skyframe.Differencer;
import com.google.devtools.build.skyframe.Differencer.Diff;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.GraphInconsistencyReceiver;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.Injectable;
import com.google.devtools.build.skyframe.MemoizingEvaluator.EvaluatorSupplier;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsProvider;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A SkyframeExecutor that implicitly assumes that builds can be done incrementally from the most
 * recent build. In other words, builds are "sequenced".
 */
public final class SequencedSkyframeExecutor extends SkyframeExecutor<BuildDriver> {

  private static final Logger logger = Logger.getLogger(SequencedSkyframeExecutor.class.getName());

  /**
   * If false, the graph will not store state useful for incremental builds, saving memory but
   * leaving the graph un-reusable. Subsequent builds will therefore not be incremental.
   *
   * <p>Avoids storing edges entirely and dereferences each action after execution.
   */
  private boolean trackIncrementalState = true;

  private boolean evaluatorNeedsReset = false;

  // This is intentionally not kept in sync with the evaluator: we may reset the evaluator without
  // ever losing injected/invalidated data here. This is safe because the worst that will happen is
  // that on the next build we try to inject/invalidate some nodes that aren't needed for the build.
  private final RecordingDifferencer recordingDiffer = new SequencedRecordingDifferencer();
  private final DiffAwarenessManager diffAwarenessManager;
  private final Iterable<SkyValueDirtinessChecker> customDirtinessCheckers;
  private Set<String> previousClientEnvironment = ImmutableSet.of();

  private int modifiedFiles;
  private int outputDirtyFiles;
  private int modifiedFilesDuringPreviousBuild;
  private Duration sourceDiffCheckingDuration = Duration.ofSeconds(-1L);
  private Duration outputTreeDiffCheckingDuration = Duration.ofSeconds(-1L);

  // If this is null then workspace header pre-calculation won't happen.
  @Nullable private final ManagedDirectoriesKnowledge managedDirectoriesKnowledge;

  private SequencedSkyframeExecutor(
      Consumer<SkyframeExecutor> skyframeExecutorConsumerOnInit,
      EvaluatorSupplier evaluatorSupplier,
      PackageFactory pkgFactory,
      FileSystem fileSystem,
      BlazeDirectories directories,
      ActionKeyContext actionKeyContext,
      Factory workspaceStatusActionFactory,
      ImmutableList<BuildInfoFactory> buildInfoFactories,
      Iterable<? extends DiffAwareness.Factory> diffAwarenessFactories,
      ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions,
      Iterable<SkyValueDirtinessChecker> customDirtinessCheckers,
      ImmutableSet<PathFragment> hardcodedBlacklistedPackagePrefixes,
      PathFragment additionalBlacklistedPackagePrefixesFile,
      CrossRepositoryLabelViolationStrategy crossRepositoryLabelViolationStrategy,
      List<BuildFileName> buildFilesByPriority,
      ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile,
      BuildOptions defaultBuildOptions,
      MutableArtifactFactorySupplier mutableArtifactFactorySupplier,
      @Nullable ManagedDirectoriesKnowledge managedDirectoriesKnowledge) {
    super(
        skyframeExecutorConsumerOnInit,
        evaluatorSupplier,
        pkgFactory,
        fileSystem,
        directories,
        actionKeyContext,
        workspaceStatusActionFactory,
        buildInfoFactories,
        extraSkyFunctions,
        ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
        hardcodedBlacklistedPackagePrefixes,
        additionalBlacklistedPackagePrefixesFile,
        crossRepositoryLabelViolationStrategy,
        buildFilesByPriority,
        actionOnIOExceptionReadingBuildFile,
        /*shouldUnblockCpuWorkWhenFetchingDeps=*/ false,
        GraphInconsistencyReceiver.THROWING,
        defaultBuildOptions,
        new PackageProgressReceiver(),
        mutableArtifactFactorySupplier,
        new ConfiguredTargetProgressReceiver(),
        /*nonexistentFileReceiver=*/ null,
        managedDirectoriesKnowledge);
    this.diffAwarenessManager = new DiffAwarenessManager(diffAwarenessFactories);
    this.customDirtinessCheckers = customDirtinessCheckers;
    this.managedDirectoriesKnowledge = managedDirectoriesKnowledge;
  }

  @Override
  protected BuildDriver getBuildDriver() {
    return new SequentialBuildDriver(memoizingEvaluator);
  }

  @Override
  public void resetEvaluator() {
    super.resetEvaluator();
    diffAwarenessManager.reset();
  }

  @Override
  protected Differencer evaluatorDiffer() {
    return recordingDiffer;
  }

  @Override
  protected Injectable injectable() {
    return recordingDiffer;
  }

  @VisibleForTesting
  public RecordingDifferencer getDifferencerForTesting() {
    return recordingDiffer;
  }

  @Override
  public void sync(
      ExtendedEventHandler eventHandler,
      PackageCacheOptions packageCacheOptions,
      PathPackageLocator packageLocator,
      StarlarkSemanticsOptions starlarkSemanticsOptions,
      UUID commandId,
      Map<String, String> clientEnv,
      TimestampGranularityMonitor tsgm,
      OptionsProvider options)
      throws InterruptedException, AbruptExitException {
    if (evaluatorNeedsReset) {
      // Recreate MemoizingEvaluator so that graph is recreated with correct edge-clearing status,
      // or if the graph doesn't have edges, so that a fresh graph can be used.
      resetEvaluator();
      evaluatorNeedsReset = false;
    }
    super.sync(
        eventHandler,
        packageCacheOptions,
        packageLocator,
        starlarkSemanticsOptions,
        commandId,
        clientEnv,
        tsgm,
        options);
    long startTime = System.nanoTime();
    handleDiffs(eventHandler, packageCacheOptions.checkOutputFiles, options);
    long stopTime = System.nanoTime();
    Profiler.instance().logSimpleTask(startTime, stopTime, ProfilerTask.INFO, "handleDiffs");
    long duration = stopTime - startTime;
    sourceDiffCheckingDuration = duration > 0 ? Duration.ofNanos(duration) : Duration.ZERO;
  }

  /**
   * The value types whose builders have direct access to the package locator, rather than accessing
   * it via an explicit Skyframe dependency. They need to be invalidated if the package locator
   * changes.
   */
  private static final ImmutableSet<SkyFunctionName> PACKAGE_LOCATOR_DEPENDENT_VALUES =
      ImmutableSet.of(
          FileStateValue.FILE_STATE,
          FileValue.FILE,
          SkyFunctions.DIRECTORY_LISTING_STATE,
          SkyFunctions.TARGET_PATTERN,
          SkyFunctions.PREPARE_DEPS_OF_PATTERN,
          SkyFunctions.TARGET_PATTERN,
          SkyFunctions.TARGET_PATTERN_PHASE);

  @Override
  protected ImmutableMap<Root, ArtifactRoot> createSourceArtifactRootMapOnNewPkgLocator(
      PathPackageLocator oldLocator, PathPackageLocator pkgLocator) {
    invalidate(SkyFunctionName.functionIsIn(PACKAGE_LOCATOR_DEPENDENT_VALUES));
    return super.createSourceArtifactRootMapOnNewPkgLocator(oldLocator, pkgLocator);
  }

  @Override
  protected void invalidate(Predicate<SkyKey> pred) {
    recordingDiffer.invalidate(Iterables.filter(memoizingEvaluator.getValues().keySet(), pred));
  }

  private void invalidateDeletedPackages(Iterable<PackageIdentifier> deletedPackages) {
    ArrayList<SkyKey> packagesToInvalidate = Lists.newArrayList();
    for (PackageIdentifier deletedPackage : deletedPackages) {
      packagesToInvalidate.add(PackageLookupValue.key(deletedPackage));
    }
    // Exit early if there are no packages to be deleted to avoid iterating over a large map.
    if (packagesToInvalidate.isEmpty()) {
      return;
    }
    memoizingEvaluator.delete(Predicates.in(packagesToInvalidate));
  }

  /**
   * Sets the packages that should be treated as deleted and ignored.
   */
  @Override
  @VisibleForTesting  // productionVisibility = Visibility.PRIVATE
  public void setDeletedPackages(Iterable<PackageIdentifier> pkgs) {
    // Invalidate the old deletedPackages as they may exist now.
    invalidateDeletedPackages(deletedPackages.get());
    deletedPackages.set(ImmutableSet.copyOf(pkgs));
    // Invalidate the new deletedPackages as we need to pretend that they don't exist now.
    invalidateDeletedPackages(deletedPackages.get());
  }

  /** Uses diff awareness on all the package paths to invalidate changed files. */
  @VisibleForTesting
  public void handleDiffsForTesting(ExtendedEventHandler eventHandler)
      throws InterruptedException, AbruptExitException {
    if (super.lastAnalysisDiscarded) {
      // Values were cleared last build, but they couldn't be deleted because they were needed for
      // the execution phase. We can delete them now.
      dropConfiguredTargetsNow(eventHandler);
      super.lastAnalysisDiscarded = false;
    }
    handleDiffs(eventHandler, /*checkOutputFiles=*/false, OptionsProvider.EMPTY);
  }

  private void handleDiffs(
      ExtendedEventHandler eventHandler, boolean checkOutputFiles, OptionsProvider options)
      throws InterruptedException, AbruptExitException {
    TimestampGranularityMonitor tsgm = this.tsgm.get();
    modifiedFiles = 0;

    boolean managedDirectoriesChanged =
        managedDirectoriesKnowledge != null && refreshWorkspaceHeader(eventHandler);
    if (managedDirectoriesChanged) {
      invalidateCachedWorkspacePathsStates();
    }

    Map<Root, DiffAwarenessManager.ProcessableModifiedFileSet> modifiedFilesByPathEntry =
        Maps.newHashMap();
    Set<Pair<Root, DiffAwarenessManager.ProcessableModifiedFileSet>>
        pathEntriesWithoutDiffInformation = Sets.newHashSet();
    for (Root pathEntry : pkgLocator.get().getPathEntries()) {
      DiffAwarenessManager.ProcessableModifiedFileSet modifiedFileSet =
          diffAwarenessManager.getDiff(eventHandler, pathEntry, options);
      if (modifiedFileSet.getModifiedFileSet().treatEverythingAsModified()) {
        pathEntriesWithoutDiffInformation.add(Pair.of(pathEntry, modifiedFileSet));
      } else {
        modifiedFilesByPathEntry.put(pathEntry, modifiedFileSet);
      }
    }
    handleDiffsWithCompleteDiffInformation(
        tsgm, modifiedFilesByPathEntry, managedDirectoriesChanged);
    handleDiffsWithMissingDiffInformation(
        eventHandler,
        tsgm,
        pathEntriesWithoutDiffInformation,
        checkOutputFiles,
        managedDirectoriesChanged);
    handleClientEnvironmentChanges();
  }

  /**
   * Skyframe caches the states of files (FileStateValue) and directory listings
   * (DirectoryListingStateValue). In the case when the files/directories are under a managed
   * directory or inside an external repository, evaluation of file/directory listing states
   * requires that RepositoryDirectoryValue of the owning external repository is evaluated
   * beforehand. (So that the repository rule generates the files.) So there is a dependency on
   * RepositoryDirectoryValue for files under managed directories and external repositories. This
   * dependency is recorded by Skyframe.
   *
   * <p>From the other side, by default Skyframe injects the new values of changed files already at
   * the stage of checking what files have changed. Only values without any dependencies can be
   * injected into Skyframe.
   *
   * <p>When the values of managed directories change, whether a file is under a managed directory
   * can change. This implies that corresponding file/directory listing states gain the dependency
   * (RepositoryDirectoryValue) or they lose this dependency. In both cases, we should prevent
   * Skyframe from injecting those new values of file/directory listing states at the stage of
   * checking changed files, because the files have not been generated yet.
   *
   * <p>The selected approach is to invalidate PACKAGE_LOCATOR_DEPENDENT_VALUES, which includes
   * invalidating all cached file/directory listing state values. Additionally, no new
   * FileStateValues and DirectoryStateValues should be injected.
   */
  private void invalidateCachedWorkspacePathsStates() {
    logger.info(
        "Invalidating cached packages and paths states"
            + " because managed directories configuration has changed.");
    invalidate(SkyFunctionName.functionIsIn(PACKAGE_LOCATOR_DEPENDENT_VALUES));
  }

  /** Invalidates entries in the client environment. */
  private void handleClientEnvironmentChanges() {
    // Remove deleted client environmental variables.
    Iterable<SkyKey> deletedKeys =
        Sets.difference(previousClientEnvironment, clientEnv.get().keySet())
            .stream()
            .map(ClientEnvironmentFunction::key)
            .collect(ImmutableList.toImmutableList());
    recordingDiffer.invalidate(deletedKeys);
    previousClientEnvironment = clientEnv.get().keySet();
    // Inject current client environmental values. We can inject unconditionally without fearing
    // over-invalidation; skyframe will not invalidate an injected key if the key's new value is the
    // same as the old value.
    ImmutableMap.Builder<SkyKey, SkyValue> newValuesBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : clientEnv.get().entrySet()) {
      newValuesBuilder.put(
          ClientEnvironmentFunction.key(entry.getKey()),
          new ClientEnvironmentValue(entry.getValue()));
    }
    recordingDiffer.inject(newValuesBuilder.build());
  }

  /**
   * Invalidates files under path entries whose corresponding {@link DiffAwareness} gave an exact
   * diff. Removes entries from the given map as they are processed. All of the files need to be
   * invalidated, so the map should be empty upon completion of this function.
   */
  private void handleDiffsWithCompleteDiffInformation(
      TimestampGranularityMonitor tsgm,
      Map<Root, ProcessableModifiedFileSet> modifiedFilesByPathEntry,
      boolean managedDirectoriesChanged)
      throws InterruptedException {
    for (Root pathEntry : ImmutableSet.copyOf(modifiedFilesByPathEntry.keySet())) {
      DiffAwarenessManager.ProcessableModifiedFileSet processableModifiedFileSet =
          modifiedFilesByPathEntry.get(pathEntry);
      ModifiedFileSet modifiedFileSet = processableModifiedFileSet.getModifiedFileSet();
      Preconditions.checkState(!modifiedFileSet.treatEverythingAsModified(), pathEntry);
      handleChangedFiles(
          ImmutableList.of(pathEntry),
          getDiff(tsgm, modifiedFileSet.modifiedSourceFiles(), pathEntry),
          managedDirectoriesChanged);
      processableModifiedFileSet.markProcessed();
    }
  }

  /**
   * Finds and invalidates changed files under path entries whose corresponding {@link
   * DiffAwareness} said all files may have been modified.
   */
  private void handleDiffsWithMissingDiffInformation(
      ExtendedEventHandler eventHandler,
      TimestampGranularityMonitor tsgm,
      Set<Pair<Root, ProcessableModifiedFileSet>> pathEntriesWithoutDiffInformation,
      boolean checkOutputFiles,
      boolean managedDirectoriesChanged)
      throws InterruptedException {
    ExternalFilesKnowledge externalFilesKnowledge =
        externalFilesHelper.getExternalFilesKnowledge();
    if (pathEntriesWithoutDiffInformation.isEmpty()
        && Iterables.isEmpty(customDirtinessCheckers)
        && ((!externalFilesKnowledge.anyOutputFilesSeen || !checkOutputFiles)
            && !externalFilesKnowledge.anyNonOutputExternalFilesSeen)) {
      // Avoid a full graph scan if we have good diff information for all path entries, there are
      // no custom checkers that need to look at the whole graph, and no external (not under any
      // path) files need to be checked.
      return;
    }
    // Before running the FilesystemValueChecker, ensure that all values marked for invalidation
    // have actually been invalidated (recall that invalidation happens at the beginning of the
    // next evaluate() call), because checking those is a waste of time.
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(DEFAULT_THREAD_COUNT)
            .setEventHander(eventHandler)
            .build();
    buildDriver.evaluate(ImmutableList.<SkyKey>of(), evaluationContext);

    FilesystemValueChecker fsvc = new FilesystemValueChecker(tsgm, null);
    // We need to manually check for changes to known files. This entails finding all dirty file
    // system values under package roots for which we don't have diff information. If at least
    // one path entry doesn't have diff information, then we're going to have to iterate over
    // the skyframe values at least once no matter what.
    Set<Root> diffPackageRootsUnderWhichToCheck = new HashSet<>();
    for (Pair<Root, DiffAwarenessManager.ProcessableModifiedFileSet> pair :
        pathEntriesWithoutDiffInformation) {
      diffPackageRootsUnderWhichToCheck.add(pair.getFirst());
    }

    // We freshly compute knowledge of the presence of external files in the skyframe graph. We use
    // a fresh ExternalFilesHelper instance and only set the real instance's knowledge *after* we
    // are done with the graph scan, lest an interrupt during the graph scan causes us to
    // incorrectly think there are no longer any external files.
    ExternalFilesHelper tmpExternalFilesHelper =
        externalFilesHelper.cloneWithFreshExternalFilesKnowledge();
    // See the comment for FileType.OUTPUT for why we need to consider output files here.
    EnumSet<FileType> fileTypesToCheck =
        checkOutputFiles
            ? EnumSet.of(
                FileType.EXTERNAL,
                FileType.EXTERNAL_REPO,
                FileType.EXTERNAL_IN_MANAGED_DIRECTORY,
                FileType.OUTPUT)
            : EnumSet.of(
                FileType.EXTERNAL, FileType.EXTERNAL_REPO, FileType.EXTERNAL_IN_MANAGED_DIRECTORY);
    logger.info(
        "About to scan skyframe graph checking for filesystem nodes of types "
            + Iterables.toString(fileTypesToCheck));
    Differencer.Diff diff;
    try (SilentCloseable c = Profiler.instance().profile("fsvc.getDirtyKeys")) {
      diff =
          fsvc.getDirtyKeys(
              memoizingEvaluator.getValues(),
              new UnionDirtinessChecker(
                  Iterables.concat(
                      customDirtinessCheckers,
                      ImmutableList.<SkyValueDirtinessChecker>of(
                          new ExternalDirtinessChecker(tmpExternalFilesHelper, fileTypesToCheck),
                          new MissingDiffDirtinessChecker(diffPackageRootsUnderWhichToCheck)))));
    }
    handleChangedFiles(diffPackageRootsUnderWhichToCheck, diff, managedDirectoriesChanged);

    for (Pair<Root, DiffAwarenessManager.ProcessableModifiedFileSet> pair :
        pathEntriesWithoutDiffInformation) {
      pair.getSecond().markProcessed();
    }
    // We use the knowledge gained during the graph scan that just completed. Otherwise, naively,
    // once an external file gets into the Skyframe graph, we'll overly-conservatively always think
    // the graph needs to be scanned.
    externalFilesHelper.setExternalFilesKnowledge(
        tmpExternalFilesHelper.getExternalFilesKnowledge());
  }

  private void handleChangedFiles(
      Collection<Root> diffPackageRootsUnderWhichToCheck,
      Diff diff,
      boolean managedDirectoriesChanged) {
    int numWithoutNewValues = diff.changedKeysWithoutNewValues().size();
    Iterable<SkyKey> keysToBeChangedLaterInThisBuild = diff.changedKeysWithoutNewValues();
    Map<SkyKey, SkyValue> changedKeysWithNewValues = diff.changedKeysWithNewValues();

    // If managed directories settings changed, do not inject any new values, just invalidate
    // keys of the changed values. {@link #invalidateCachedWorkspacePathsStates()}
    if (managedDirectoriesChanged) {
      numWithoutNewValues += changedKeysWithNewValues.size();
      keysToBeChangedLaterInThisBuild =
          Iterables.concat(keysToBeChangedLaterInThisBuild, changedKeysWithNewValues.keySet());
      changedKeysWithNewValues = ImmutableMap.of();
    }

    logDiffInfo(
        diffPackageRootsUnderWhichToCheck,
        keysToBeChangedLaterInThisBuild,
        numWithoutNewValues,
        changedKeysWithNewValues);

    recordingDiffer.invalidate(keysToBeChangedLaterInThisBuild);
    recordingDiffer.inject(changedKeysWithNewValues);
    modifiedFiles += getNumberOfModifiedFiles(keysToBeChangedLaterInThisBuild);
    modifiedFiles += getNumberOfModifiedFiles(changedKeysWithNewValues.keySet());
    incrementalBuildMonitor.accrue(keysToBeChangedLaterInThisBuild);
    incrementalBuildMonitor.accrue(changedKeysWithNewValues.keySet());
  }

  private static final int MAX_NUMBER_OF_CHANGED_KEYS_TO_LOG = 10;

  private static void logDiffInfo(
      Iterable<Root> pathEntries,
      Iterable<SkyKey> changedWithoutNewValue,
      int numWithoutNewValues,
      Map<SkyKey, ? extends SkyValue> changedWithNewValue) {
    int numModified = changedWithNewValue.size() + numWithoutNewValues;
    StringBuilder result = new StringBuilder("DiffAwareness found ")
        .append(numModified)
        .append(" modified source files and directory listings");
    if (!Iterables.isEmpty(pathEntries)) {
      result.append(" for ");
      result.append(Joiner.on(", ").join(pathEntries));
    }

    if (numModified > 0) {
      Iterable<SkyKey> allModifiedKeys = Iterables.concat(changedWithoutNewValue,
          changedWithNewValue.keySet());
      Iterable<SkyKey> trimmed =
          Iterables.limit(allModifiedKeys, MAX_NUMBER_OF_CHANGED_KEYS_TO_LOG);

      result.append(": ")
          .append(Joiner.on(", ").join(trimmed));

      if (numModified > MAX_NUMBER_OF_CHANGED_KEYS_TO_LOG) {
        result.append(", ...");
      }
    }

    logger.info(result.toString());
  }

  private static int getNumberOfModifiedFiles(Iterable<SkyKey> modifiedValues) {
    // We are searching only for changed files, DirectoryListingValues don't depend on
    // child values, that's why they are invalidated separately
    return Iterables.size(
        Iterables.filter(modifiedValues, SkyFunctionName.functionIs(FileStateValue.FILE_STATE)));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Necessary conditions to not store graph edges are either
   *
   * <ol>
   *   <li>batch (since incremental builds are not possible) and discard_analysis_cache (since
   *       otherwise user isn't concerned about saving memory this way).
   *   <li>track_incremental_state set to false.
   * </ol>
   */
  @Override
  public void decideKeepIncrementalState(
      boolean batch,
      boolean keepStateAfterBuild,
      boolean shouldTrackIncrementalState,
      boolean discardAnalysisCache,
      EventHandler eventHandler) {
    Preconditions.checkState(!active);
    boolean oldValueOfTrackIncrementalState = trackIncrementalState;

    // First check if the incrementality state should be kept around during the build.
    boolean explicitlyRequestedNoIncrementalData = !shouldTrackIncrementalState;
    boolean implicitlyRequestedNoIncrementalData = (batch && discardAnalysisCache);
    trackIncrementalState =
        !explicitlyRequestedNoIncrementalData && !implicitlyRequestedNoIncrementalData;
    if (explicitlyRequestedNoIncrementalData != implicitlyRequestedNoIncrementalData) {
      if (!explicitlyRequestedNoIncrementalData) {
        eventHandler.handle(
            Event.warn(
                "--batch and --discard_analysis_cache specified, but --notrack_incremental_state "
                    + "not specified: incrementality data is implicitly discarded, but you may need"
                    + " to specify --notrack_incremental_state in the future if you want to "
                    + "maximize memory savings."));
      }
      if (!batch && keepStateAfterBuild) {
        eventHandler.handle(
            Event.warn(
                "--notrack_incremental_state was specified, but without "
                    + "--nokeep_state_after_build. Inmemory state from this build will not be "
                    + "reusable, but it will not get fully wiped until the beginning of the next "
                    + "build. Use --nokeep_state_after_build to clean up eagerly."));
      }
    }

    // Now check if it is necessary to wipe the previous state. We do this if either the previous
    // or current incrementalStateRetentionStrategy requires the build to have been isolated.
    if (oldValueOfTrackIncrementalState != trackIncrementalState) {
      logger.info("Set incremental state to " + trackIncrementalState);
      evaluatorNeedsReset = true;
    } else if (!trackIncrementalState) {
      evaluatorNeedsReset = true;
    }
  }

  @Override
  public boolean tracksStateForIncrementality() {
    return trackIncrementalState;
  }

  @Override
  public void clearAnalysisCache(
      Collection<ConfiguredTarget> topLevelTargets, Collection<AspectValue> topLevelAspects) {
    discardPreExecutionCache(
        topLevelTargets,
        topLevelAspects,
        trackIncrementalState ? DiscardType.ANALYSIS_REFS_ONLY : DiscardType.ALL);
  }

  @Override
  protected void invalidateFilesUnderPathForTestingImpl(
      ExtendedEventHandler eventHandler, ModifiedFileSet modifiedFileSet, Root pathEntry)
      throws InterruptedException {
    TimestampGranularityMonitor tsgm = this.tsgm.get();
    Differencer.Diff diff;
    if (modifiedFileSet.treatEverythingAsModified()) {
      diff = new FilesystemValueChecker(tsgm, null).getDirtyKeys(memoizingEvaluator.getValues(),
          new BasicFilesystemDirtinessChecker());
    } else {
      diff = getDiff(tsgm, modifiedFileSet.modifiedSourceFiles(), pathEntry);
    }
    syscalls.set(getPerBuildSyscallCache(/*concurrencyLevel=*/ 42));
    recordingDiffer.invalidate(diff.changedKeysWithoutNewValues());
    recordingDiffer.inject(diff.changedKeysWithNewValues());
    // Blaze invalidates transient errors on every build.
    invalidateTransientErrors();
  }

  @Override
  public void invalidateTransientErrors() {
    checkActive();
    recordingDiffer.invalidateTransientErrors();
  }

  @Override
  public void detectModifiedOutputFiles(
      ModifiedFileSet modifiedOutputFiles, @Nullable Range<Long> lastExecutionTimeRange)
      throws InterruptedException {
    long startTime = System.nanoTime();
    FilesystemValueChecker fsvc =
        new FilesystemValueChecker(Preconditions.checkNotNull(tsgm.get()), lastExecutionTimeRange);
    BatchStat batchStatter = outputService == null ? null : outputService.getBatchStatter();
    recordingDiffer.invalidate(
        fsvc.getDirtyActionValues(
            memoizingEvaluator.getValues(), batchStatter, modifiedOutputFiles));
    modifiedFiles += fsvc.getNumberOfModifiedOutputFiles();
    outputDirtyFiles += fsvc.getNumberOfModifiedOutputFiles();
    modifiedFilesDuringPreviousBuild += fsvc.getNumberOfModifiedOutputFilesDuringPreviousBuild();
    logger.info(String.format("Found %d modified files from last build", modifiedFiles));
    long stopTime = System.nanoTime();
    Profiler.instance()
        .logSimpleTask(startTime, stopTime, ProfilerTask.INFO, "detectModifiedOutputFiles");
    long duration = stopTime - startTime;
    outputTreeDiffCheckingDuration = duration > 0 ? Duration.ofNanos(duration) : Duration.ZERO;
  }

  @Override
  public List<RuleStat> getRuleStats(ExtendedEventHandler eventHandler) {
    Map<String, RuleStat> ruleStats = new HashMap<>();
    for (Map.Entry<SkyKey, SkyValue> skyKeyAndValue :
        memoizingEvaluator.getDoneValues().entrySet()) {
      SkyValue value = skyKeyAndValue.getValue();
      SkyKey key = skyKeyAndValue.getKey();
      SkyFunctionName functionName = key.functionName();
      if (functionName.equals(SkyFunctions.CONFIGURED_TARGET)) {
        ConfiguredTargetValue ctValue = (ConfiguredTargetValue) value;
          ConfiguredTarget configuredTarget = ctValue.getConfiguredTarget();
          if (configuredTarget instanceof RuleConfiguredTarget) {

            Rule rule;
            try {
              rule =
                  (Rule) getPackageManager().getTarget(eventHandler, configuredTarget.getLabel());
            } catch (NoSuchPackageException | NoSuchTargetException | InterruptedException e) {
              throw new IllegalStateException(
                  "Failed to get Rule target from package when calculating stats.", e);
            }
            RuleClass ruleClass = rule.getRuleClassObject();
            RuleStat ruleStat =
                ruleStats.computeIfAbsent(
                    ruleClass.getKey(), k -> new RuleStat(k, ruleClass.getName(), true));
            ruleStat.addRule(ctValue.getNumActions());
          }
      } else if (functionName.equals(SkyFunctions.ASPECT)) {
        AspectValue aspectValue = (AspectValue) value;
          AspectClass aspectClass = aspectValue.getAspect().getAspectClass();
          RuleStat ruleStat =
              ruleStats.computeIfAbsent(
                  aspectClass.getKey(), k -> new RuleStat(k, aspectClass.getName(), false));
          ruleStat.addRule(aspectValue.getNumActions());
      }
    }
    return new ArrayList<>(ruleStats.values());
  }

  @Override
  public ActionGraphContainer getActionGraphContainer(
      List<String> actionGraphTargets, boolean includeActionCmdLine, boolean includeArtifacts)
      throws CommandLineExpansionException {
    ActionGraphDump actionGraphDump =
        new ActionGraphDump(actionGraphTargets, includeActionCmdLine, includeArtifacts);
    return buildActionGraphContainerFromDump(actionGraphDump);
  }

  /** Get ActionGraphContainer from the Skyframe evaluator. Used for aquery. */
  public ActionGraphContainer getActionGraphContainer(
      boolean includeActionCmdLine,
      AqueryActionFilter aqueryActionFilter,
      boolean includeParamFiles,
      boolean includeArtifacts)
      throws CommandLineExpansionException {
    ActionGraphDump actionGraphDump =
        new ActionGraphDump(
            includeActionCmdLine, includeArtifacts, aqueryActionFilter, includeParamFiles);
    return buildActionGraphContainerFromDump(actionGraphDump);
  }

  private ActionGraphContainer buildActionGraphContainerFromDump(ActionGraphDump actionGraphDump)
      throws CommandLineExpansionException {
    for (Map.Entry<SkyKey, SkyValue> skyKeyAndValue :
        memoizingEvaluator.getDoneValues().entrySet()) {
      SkyKey key = skyKeyAndValue.getKey();
      SkyValue skyValue = skyKeyAndValue.getValue();
      SkyFunctionName functionName = key.functionName();
      try {
        // The skyValue may be null in case analysis of the previous build failed.
        if (skyValue != null) {
          if (functionName.equals(SkyFunctions.CONFIGURED_TARGET)) {
            actionGraphDump.dumpConfiguredTarget((ConfiguredTargetValue) skyValue);
          } else if (functionName.equals(SkyFunctions.ASPECT)) {
            AspectValue aspectValue = (AspectValue) skyValue;
            AspectKey aspectKey = aspectValue.getKey();
            ConfiguredTargetValue configuredTargetValue =
                (ConfiguredTargetValue)
                    memoizingEvaluator.getExistingValue(aspectKey.getBaseConfiguredTargetKey());
            actionGraphDump.dumpAspect(aspectValue, configuredTargetValue);
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("No interruption in sequenced evaluation", e);
      }
    }
    return actionGraphDump.build();
  }

  /**
   * In addition to calling the superclass method, deletes all analysis-related values from the
   * Skyframe cache. This is done to save memory (e.g. on a configuration change); since the
   * configuration is part of the key, these key/value pairs will be sitting around doing nothing
   * until the configuration changes back to the previous value.
   *
   * <p>The next evaluation will delete all invalid values.
   */
  @Override
  public void handleAnalysisInvalidatingChange() {
    super.handleAnalysisInvalidatingChange();
    memoizingEvaluator.delete(ANALYSIS_KEY_PREDICATE);
  }

  /**
   * Deletes all ConfiguredTarget values from the Skyframe cache.
   *
   * <p>After the execution of this method all invalidated and marked for deletion values (and the
   * values depending on them) will be deleted from the cache.
   *
   * <p>WARNING: Note that a call to this method leaves legacy data inconsistent with Skyframe. The
   * next build should clear the legacy caches.
   */
  @Override
  protected void dropConfiguredTargetsNow(final ExtendedEventHandler eventHandler) {
    handleAnalysisInvalidatingChange();
    // Run the invalidator to actually delete the values.
    try {
      progressReceiver.ignoreInvalidations = true;
      Uninterruptibles.callUninterruptibly(
          new Callable<Void>() {
            @Override
            public Void call() throws InterruptedException {
              EvaluationContext evaluationContext =
                  EvaluationContext.newBuilder()
                      .setKeepGoing(false)
                      .setNumThreads(ResourceUsage.getAvailableProcessors())
                      .setEventHander(eventHandler)
                      .build();
              buildDriver.evaluate(ImmutableList.<SkyKey>of(), evaluationContext);
              return null;
            }
          });
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      progressReceiver.ignoreInvalidations = false;
    }
  }

  @Override
  public ExecutionFinishedEvent createExecutionFinishedEvent() {
    ExecutionFinishedEvent result =
        new ExecutionFinishedEvent(
            outputDirtyFiles,
            modifiedFilesDuringPreviousBuild,
            sourceDiffCheckingDuration,
            outputTreeDiffCheckingDuration);
    outputDirtyFiles = 0;
    modifiedFilesDuringPreviousBuild = 0;
    sourceDiffCheckingDuration = Duration.ZERO;
    outputTreeDiffCheckingDuration = Duration.ZERO;
    return result;
  }

  @Override
  public void deleteOldNodes(long versionWindowForDirtyGc) {
    // TODO(bazel-team): perhaps we should come up with a separate GC class dedicated to maintaining
    // value garbage. If we ever do so, this logic should be moved there.
    memoizingEvaluator.deleteDirty(versionWindowForDirtyGc);
  }

  @Override
  public void dumpPackages(PrintStream out) {
    Iterable<SkyKey> packageSkyKeys = Iterables.filter(memoizingEvaluator.getValues().keySet(),
        SkyFunctions.isSkyFunction(SkyFunctions.PACKAGE));
    out.println(Iterables.size(packageSkyKeys) + " packages");
    for (SkyKey packageSkyKey : packageSkyKeys) {
      Package pkg = ((PackageValue) memoizingEvaluator.getValues().get(packageSkyKey)).getPackage();
      pkg.dump(out);
    }
  }

  /**
   * Calculate the new value of the WORKSPACE file header (WorkspaceFileValue with the index = 0),
   * and call the listener, if the value has changed. Needed for incremental update of user-owned
   * directories by repository rules.
   */
  private boolean refreshWorkspaceHeader(ExtendedEventHandler eventHandler)
      throws InterruptedException, AbruptExitException {
    Root workspaceRoot = Root.fromPath(directories.getWorkspace());
    RootedPath workspacePath =
        RootedPath.toRootedPath(workspaceRoot, LabelConstants.WORKSPACE_FILE_NAME);
    WorkspaceFileKey workspaceFileKey = WorkspaceFileValue.key(workspacePath);

    WorkspaceFileValue oldValue =
        (WorkspaceFileValue) memoizingEvaluator.getExistingValue(workspaceFileKey);
    FileStateValue newFileStateValue = maybeInvalidateWorkspaceFileStateValue(workspacePath);
    WorkspaceFileValue newValue =
        (WorkspaceFileValue) evaluateSingleValue(workspaceFileKey, eventHandler);
    if (!newValue.getManagedDirectories().isEmpty()
        && FileStateType.SYMLINK.equals(newFileStateValue.getType())) {
      throw new AbruptExitException(
          "WORKSPACE file can not be a symlink if incrementally updated directories are used.",
          ExitCode.PARSING_FAILURE);
    }
    return managedDirectoriesKnowledge.workspaceHeaderReloaded(oldValue, newValue);
  }

  // We only check the FileStateValue of the WORKSPACE file; we do not support the case
  // when the WORKSPACE file is a symlink.
  private FileStateValue maybeInvalidateWorkspaceFileStateValue(RootedPath workspacePath)
      throws InterruptedException, AbruptExitException {
    SkyKey workspaceFileStateKey = FileStateValue.key(workspacePath);
    SkyValue oldWorkspaceFileState = memoizingEvaluator.getExistingValue(workspaceFileStateKey);
    FileStateValue newWorkspaceFileState;
    try {
      newWorkspaceFileState = FileStateValue.create(workspacePath, tsgm.get());
    } catch (IOException e) {
      throw new AbruptExitException("Can not read WORKSPACE file.", ExitCode.PARSING_FAILURE, e);
    }
    if (oldWorkspaceFileState != null && !oldWorkspaceFileState.equals(newWorkspaceFileState)) {
      recordingDiffer.invalidate(ImmutableSet.of(workspaceFileStateKey));
    }
    return newWorkspaceFileState;
  }

  private SkyValue evaluateSingleValue(SkyKey key, ExtendedEventHandler eventHandler)
      throws InterruptedException {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(DEFAULT_THREAD_COUNT)
            .setEventHander(eventHandler)
            .build();
    return buildDriver.evaluate(ImmutableSet.of(key), evaluationContext).get(key);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder class for {@link SequencedSkyframeExecutor}.
   *
   * <p>Allows addition of the new arguments to {@link SequencedSkyframeExecutor} constructor
   * without the need to modify all the places, where {@link SequencedSkyframeExecutor} is
   * constructed (if the default value can be provided for the new argument in Builder).
   */
  public static final class Builder {
    protected PackageFactory pkgFactory;
    protected FileSystem fileSystem;
    protected BlazeDirectories directories;
    protected ActionKeyContext actionKeyContext;
    protected ImmutableList<BuildInfoFactory> buildInfoFactories;
    protected BuildOptions defaultBuildOptions;
    private ImmutableSet<PathFragment> hardcodedBlacklistedPackagePrefixes;
    private PathFragment additionalBlacklistedPackagePrefixesFile;
    private CrossRepositoryLabelViolationStrategy crossRepositoryLabelViolationStrategy;
    private List<BuildFileName> buildFilesByPriority;
    private ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile;
    @Nullable private ManagedDirectoriesKnowledge managedDirectoriesKnowledge;

    // Fields with default values.
    private ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions = ImmutableMap.of();
    private Factory workspaceStatusActionFactory;
    private Iterable<? extends DiffAwareness.Factory> diffAwarenessFactories = ImmutableList.of();
    private Iterable<SkyValueDirtinessChecker> customDirtinessCheckers = ImmutableList.of();
    private MutableArtifactFactorySupplier mutableArtifactFactorySupplier =
        new MutableArtifactFactorySupplier();
    private Consumer<SkyframeExecutor> skyframeExecutorConsumerOnInit = skyframeExecutor -> {};

    private Builder() {}

    public SequencedSkyframeExecutor build() {
      // Check that the values were explicitly set.
      Preconditions.checkNotNull(pkgFactory);
      Preconditions.checkNotNull(fileSystem);
      Preconditions.checkNotNull(directories);
      Preconditions.checkNotNull(actionKeyContext);
      Preconditions.checkNotNull(buildInfoFactories);
      Preconditions.checkNotNull(defaultBuildOptions);
      Preconditions.checkNotNull(hardcodedBlacklistedPackagePrefixes);
      Preconditions.checkNotNull(additionalBlacklistedPackagePrefixesFile);
      Preconditions.checkNotNull(crossRepositoryLabelViolationStrategy);
      Preconditions.checkNotNull(buildFilesByPriority);
      Preconditions.checkNotNull(actionOnIOExceptionReadingBuildFile);

      SequencedSkyframeExecutor skyframeExecutor =
          new SequencedSkyframeExecutor(
              skyframeExecutorConsumerOnInit,
              InMemoryMemoizingEvaluator.SUPPLIER,
              pkgFactory,
              fileSystem,
              directories,
              actionKeyContext,
              workspaceStatusActionFactory,
              buildInfoFactories,
              diffAwarenessFactories,
              extraSkyFunctions,
              customDirtinessCheckers,
              hardcodedBlacklistedPackagePrefixes,
              additionalBlacklistedPackagePrefixesFile,
              crossRepositoryLabelViolationStrategy,
              buildFilesByPriority,
              actionOnIOExceptionReadingBuildFile,
              defaultBuildOptions,
              mutableArtifactFactorySupplier,
              managedDirectoriesKnowledge);
      skyframeExecutor.init();
      return skyframeExecutor;
    }

    public Builder setPkgFactory(PackageFactory pkgFactory) {
      this.pkgFactory = pkgFactory;
      return this;
    }

    public Builder setFileSystem(FileSystem fileSystem) {
      this.fileSystem = fileSystem;
      return this;
    }

    public Builder setDirectories(BlazeDirectories directories) {
      this.directories = directories;
      return this;
    }

    public Builder setActionKeyContext(ActionKeyContext actionKeyContext) {
      this.actionKeyContext = actionKeyContext;
      return this;
    }

    public Builder setBuildInfoFactories(ImmutableList<BuildInfoFactory> buildInfoFactories) {
      this.buildInfoFactories = buildInfoFactories;
      return this;
    }

    public Builder setDefaultBuildOptions(BuildOptions defaultBuildOptions) {
      this.defaultBuildOptions = defaultBuildOptions;
      return this;
    }

    public Builder setExtraSkyFunctions(
        ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions) {
      this.extraSkyFunctions = extraSkyFunctions;
      return this;
    }

    public Builder setWorkspaceStatusActionFactory(@Nullable Factory workspaceStatusActionFactory) {
      this.workspaceStatusActionFactory = workspaceStatusActionFactory;
      return this;
    }

    public Builder setDiffAwarenessFactories(
        Iterable<? extends DiffAwareness.Factory> diffAwarenessFactories) {
      this.diffAwarenessFactories = diffAwarenessFactories;
      return this;
    }

    public Builder setCustomDirtinessCheckers(
        Iterable<SkyValueDirtinessChecker> customDirtinessCheckers) {
      this.customDirtinessCheckers = customDirtinessCheckers;
      return this;
    }

    public Builder setHardcodedBlacklistedPackagePrefixes(
        ImmutableSet<PathFragment> hardcodedBlacklistedPackagePrefixes) {
      this.hardcodedBlacklistedPackagePrefixes = hardcodedBlacklistedPackagePrefixes;
      return this;
    }

    public Builder setAdditionalBlacklistedPackagePrefixesFile(
        PathFragment additionalBlacklistedPackagePrefixesFile) {
      this.additionalBlacklistedPackagePrefixesFile = additionalBlacklistedPackagePrefixesFile;
      return this;
    }

    public Builder setCrossRepositoryLabelViolationStrategy(
        CrossRepositoryLabelViolationStrategy crossRepositoryLabelViolationStrategy) {
      this.crossRepositoryLabelViolationStrategy = crossRepositoryLabelViolationStrategy;
      return this;
    }

    public Builder setBuildFilesByPriority(List<BuildFileName> buildFilesByPriority) {
      this.buildFilesByPriority = buildFilesByPriority;
      return this;
    }

    public Builder setActionOnIOExceptionReadingBuildFile(
        ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile) {
      this.actionOnIOExceptionReadingBuildFile = actionOnIOExceptionReadingBuildFile;
      return this;
    }

    public Builder setMutableArtifactFactorySupplier(
        MutableArtifactFactorySupplier mutableArtifactFactorySupplier) {
      this.mutableArtifactFactorySupplier = mutableArtifactFactorySupplier;
      return this;
    }
    public Builder setSkyframeExecutorConsumerOnInit(
        Consumer<SkyframeExecutor> skyframeExecutorConsumerOnInit) {
      this.skyframeExecutorConsumerOnInit = skyframeExecutorConsumerOnInit;
      return this;
    }

    public Builder setManagedDirectoriesKnowledge(
        @Nullable ManagedDirectoriesKnowledge managedDirectoriesKnowledge) {
      this.managedDirectoriesKnowledge = managedDirectoriesKnowledge;
      return this;
    }
  }

  /**
   * Listener class to subscribe for WORKSPACE file header refreshes.
   *
   * <p>Changes to WORKSPACE file header are computed before the files difference is computed in
   * {@link #handleDiffs(ExtendedEventHandler, boolean, OptionsProvider)}
   */
  public interface WorkspaceFileHeaderListener {
    boolean workspaceHeaderReloaded(
        @Nullable WorkspaceFileValue oldValue, @Nullable WorkspaceFileValue newValue)
        throws AbruptExitException;
  }
}
