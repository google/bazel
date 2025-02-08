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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.DiffAwareness.View;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.common.options.OptionsProvider;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Helper class to make it easier to correctly use the {@link DiffAwareness} interface in a
 * sequential manner.
 */
public final class DiffAwarenessManager {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // The manager attempts to instantiate these in the order in which they are passed to the
  // constructor; this is critical in the case where a factory always succeeds.
  private final ImmutableList<? extends DiffAwareness.Factory> diffAwarenessFactories;

  /** The unique key to retrieve a DiffAwarenessState. */
  @AutoValue
  public abstract static class StateKey {
    private static StateKey create(Root root, IgnoredSubdirectories ignoredPaths) {
      return new AutoValue_DiffAwarenessManager_StateKey(root, ignoredPaths);
    }

    abstract Root root();

    abstract IgnoredSubdirectories ignoredPaths();
  }

  private final Map<StateKey, DiffAwarenessState> currentDiffAwarenessStates = Maps.newHashMap();

  public DiffAwarenessManager(Iterable<? extends DiffAwareness.Factory> diffAwarenessFactories) {
    this.diffAwarenessFactories = ImmutableList.copyOf(diffAwarenessFactories);
  }

  private static class DiffAwarenessState {
    private final DiffAwareness diffAwareness;
    /**
     * The {@link View} that should be the baseline for the next {@link #getDiff} call, or
     * {@code null} if the next {@link #getDiff} will be the first incremental one.
     */
    @Nullable
    private View baselineView;

    private DiffAwarenessState(DiffAwareness diffAwareness, @Nullable View baselineView) {
      this.diffAwareness = diffAwareness;
      this.baselineView = baselineView;
    }
  }

  public ModifiedFileSet getDiffFromEvaluatingVersion(
      FileSystem fileSystem,
      Root pathEntry,
      IgnoredSubdirectories ignoredPaths,
      OptionsProvider options) {
    DiffAwarenessState diffAwarenessState =
        maybeGetDiffAwarenessState(pathEntry, ignoredPaths, options);
    if (diffAwarenessState == null) {
      return ModifiedFileSet.EVERYTHING_MODIFIED;
    }

    try {
      return diffAwarenessState.diffAwareness.getDiffFromEvaluatingVersion(options, fileSystem);
    } catch (BrokenDiffAwarenessException e) {
      return ModifiedFileSet.EVERYTHING_MODIFIED;
    }
  }

  /** Reset internal {@link DiffAwareness} state. */
  public void reset() {
    for (DiffAwarenessState diffAwarenessState : currentDiffAwarenessStates.values()) {
      diffAwarenessState.diffAwareness.close();
    }
    currentDiffAwarenessStates.clear();
  }

  /** A set of modified files that should be marked as processed. */
  public interface ProcessableModifiedFileSet {
    ModifiedFileSet getModifiedFileSet();

    @Nullable
    WorkspaceInfoFromDiff getWorkspaceInfo();

    /**
     * This should be called when the changes have been noted. Otherwise, the result from the next
     * call to {@link #getDiff} will be from the baseline of the old, unprocessed, diff.
     */
    void markProcessed();
  }

  /**
   * Gets the set of changed files since the last call with this path entry, or {@code
   * ModifiedFileSet.EVERYTHING_MODIFIED} if this is the first such call.
   */
  public ProcessableModifiedFileSet getDiff(
      EventHandler eventHandler,
      Root pathEntry,
      IgnoredSubdirectories ignoredPaths,
      OptionsProvider options)
      throws InterruptedException {
    DiffAwarenessState diffAwarenessState =
        maybeGetDiffAwarenessState(pathEntry, ignoredPaths, options);
    if (diffAwarenessState == null) {
      return BrokenProcessableModifiedFileSet.INSTANCE;
    }
    DiffAwareness diffAwareness = diffAwarenessState.diffAwareness;
    View newView;
    try (SilentCloseable c = Profiler.instance().profile("diffAwareness.getCurrentView")) {
      newView = diffAwareness.getCurrentView(options);
    } catch (BrokenDiffAwarenessException e) {
      handleBrokenDiffAwareness(eventHandler, pathEntry, ignoredPaths, e);
      return BrokenProcessableModifiedFileSet.INSTANCE;
    }

    View baselineView = diffAwarenessState.baselineView;
    ModifiedFileSet diff;
    logger.atInfo().log(
        "About to compute diff between %s and %s for %s", baselineView, newView, pathEntry);
    try (SilentCloseable c = Profiler.instance().profile("diffAwareness.getDiff")) {
      diff = diffAwareness.getDiff(baselineView, newView);
    } catch (BrokenDiffAwarenessException e) {
      handleBrokenDiffAwareness(eventHandler, pathEntry, ignoredPaths, e);
      return BrokenProcessableModifiedFileSet.INSTANCE;
    } catch (IncompatibleViewException e) {
      throw new IllegalStateException(pathEntry + " " + baselineView + " " + newView, e);
    }

    return new ProcessableModifiedFileSetImpl(diff, pathEntry, ignoredPaths, newView);
  }

  private void handleBrokenDiffAwareness(
      EventHandler eventHandler,
      Root pathEntry,
      IgnoredSubdirectories ignoredPaths,
      BrokenDiffAwarenessException e) {
    StateKey stateKey = StateKey.create(pathEntry, ignoredPaths);
    currentDiffAwarenessStates.remove(stateKey);
    logger.atInfo().withCause(e).log("Broken diff awareness for %s", pathEntry);
    eventHandler.handle(Event.warn(e.getMessage() + "... temporarily falling back to manually "
        + "checking files for changes"));
  }

  /**
   * Returns the current diff awareness for the given path entry, or a fresh one if there is no
   * current one, or otherwise {@code null} if no factory could make a fresh one.
   */
  @Nullable
  private DiffAwarenessState maybeGetDiffAwarenessState(
      Root pathEntry, IgnoredSubdirectories ignoredPaths, OptionsProvider options) {
    StateKey stateKey = StateKey.create(pathEntry, ignoredPaths);
    DiffAwarenessState diffAwarenessState = currentDiffAwarenessStates.get(stateKey);
    if (diffAwarenessState != null) {
      return diffAwarenessState;
    }

    for (DiffAwareness.Factory factory : diffAwarenessFactories) {
      DiffAwareness newDiffAwareness = factory.maybeCreate(pathEntry, ignoredPaths, options);
      if (newDiffAwareness != null) {
        logger.atInfo().log(
            "Using %s DiffAwareness strategy for %s", newDiffAwareness.name(), pathEntry);
        diffAwarenessState = new DiffAwarenessState(newDiffAwareness, /*baselineView=*/null);
        currentDiffAwarenessStates.put(stateKey, diffAwarenessState);
        return diffAwarenessState;
      }
    }
    return null;
  }

  private class ProcessableModifiedFileSetImpl implements ProcessableModifiedFileSet {

    private final ModifiedFileSet modifiedFileSet;
    private final Root pathEntry;
    /**
     * The {@link View} that should be the baseline on the next {@link #getDiff} call after
     * {@link #markProcessed} is called.
     */
    private final View nextView;

    private final IgnoredSubdirectories ignoredPaths;

    private ProcessableModifiedFileSetImpl(
        ModifiedFileSet modifiedFileSet,
        Root pathEntry,
        IgnoredSubdirectories ignoredPaths,
        View nextView) {
      this.modifiedFileSet = modifiedFileSet;
      this.pathEntry = pathEntry;
      this.ignoredPaths = ignoredPaths;
      this.nextView = nextView;
    }

    @Override
    public ModifiedFileSet getModifiedFileSet() {
      return modifiedFileSet;
    }

    @Nullable
    @Override
    public WorkspaceInfoFromDiff getWorkspaceInfo() {
      return nextView.getWorkspaceInfo();
    }

    @Override
    public void markProcessed() {
      StateKey stateKey = StateKey.create(pathEntry, ignoredPaths);
      DiffAwarenessState diffAwarenessState = currentDiffAwarenessStates.get(stateKey);
      if (diffAwarenessState != null) {
        diffAwarenessState.baselineView = nextView;
      }
    }
  }

  private static class BrokenProcessableModifiedFileSet implements ProcessableModifiedFileSet {

    private static final BrokenProcessableModifiedFileSet INSTANCE =
        new BrokenProcessableModifiedFileSet();

    @Override
    public ModifiedFileSet getModifiedFileSet() {
      return ModifiedFileSet.EVERYTHING_MODIFIED;
    }

    @Nullable
    @Override
    public WorkspaceInfoFromDiff getWorkspaceInfo() {
      return null;
    }

    @Override
    public void markProcessed() {}
  }
}
