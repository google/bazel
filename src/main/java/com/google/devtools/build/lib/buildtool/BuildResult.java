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

package com.google.devtools.build.lib.buildtool;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileCompression;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.BuildToolLogs;
import com.google.devtools.build.lib.buildeventstream.BuildToolLogs.LogFileEntry;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Contains information about the result of a build. While BuildRequest is immutable, this class is
 * mutable.
 */
public final class BuildResult {
  private long startTimeMillis = 0; // milliseconds since UNIX epoch.
  private long stopTimeMillis = 0;

  private Throwable crash = null;
  private boolean catastrophe = false;
  private boolean stopOnFirstFailure;
  @Nullable private DetailedExitCode detailedExitCode;

  private BuildConfigurationValue configuration;
  private ImmutableMap<PathFragment, PathFragment> convenienceSymlinks = ImmutableMap.of();
  private Collection<ConfiguredTarget> actualTargets;
  private Collection<ConfiguredTarget> testTargets;
  private Collection<ConfiguredTarget> successfulTargets;
  private Collection<ConfiguredTarget> skippedTargets;
  private ImmutableSet<AspectKey> successfulAspects;

  private final BuildToolLogCollection buildToolLogCollection = new BuildToolLogCollection();

  @Nullable private FailureDetail postBuildCallbackFailureDetail;

  public BuildResult(long startTimeMillis) {
    this.startTimeMillis = startTimeMillis;
  }

  /**
   * Return the time (according to System.currentTimeMillis()) at which the service of this request
   * was initiated.
   */
  public long getStartTime() {
    return startTimeMillis;
  }

  /**
   * Record the time (according to System.currentTimeMillis()) at which the service of this request
   * was completed.
   */
  public void setStopTime(long stopTimeMillis) {
    this.stopTimeMillis = stopTimeMillis;
  }

  /**
   * Return the time (according to System.currentTimeMillis()) at which the service of this request
   * was completed.
   */
  public long getStopTime() {
    return stopTimeMillis;
  }

  /**
   * Returns the elapsed time in seconds for the service of this request. Not defined for requests
   * that have not been serviced.
   */
  public double getElapsedSeconds() {
    if (startTimeMillis == 0 || stopTimeMillis == 0) {
      throw new IllegalStateException("BuildRequest has not been serviced");
    }
    return (stopTimeMillis - startTimeMillis) / 1000.0;
  }

  public void setDetailedExitCode(DetailedExitCode detailedExitCode) {
    this.detailedExitCode = detailedExitCode;
  }

  /** True iff the build request has been successfully completed. */
  public boolean getSuccess() {
    return detailedExitCode != null && detailedExitCode.isSuccess();
  }

  /**
   * Gets the {@link DetailedExitCode} containing the {@link ExitCode} and optional failure detail
   * to complete the command with.
   */
  public DetailedExitCode getDetailedExitCode() {
    if (detailedExitCode != null) {
      return detailedExitCode;
    }
    return CrashFailureDetails.detailedExitCodeForThrowable(
        new IllegalStateException("Unspecified DetailedExitCode"));
  }

  /** Sets the RuntimeException / Error that induced a Blaze crash. */
  public void setUnhandledThrowable(Throwable crash) {
    Preconditions.checkState(
        crash == null || ((crash instanceof RuntimeException) || (crash instanceof Error)));
    this.crash = crash;
  }

  /** Sets a "catastrophe": A build failure severe enough to halt a keep_going build. */
  public void setCatastrophe() {
    this.catastrophe = true;
  }

  /** Was the build a "catastrophe": A build failure severe enough to halt a keep_going build. */
  public boolean wasCatastrophe() {
    return catastrophe;
  }

  /** Whether some targets were skipped because of {@code setStopOnFirstFailure}. */
  public boolean getStopOnFirstFailure() {
    return stopOnFirstFailure;
  }

  /**
   * Indicates that remaining targets should be skipped once a target breaks/fails. This will be set
   * when --nokeep_going or --notest_keep_going is set.
   */
  public void setStopOnFirstFailure(boolean stopOnFirstFailure) {
    this.stopOnFirstFailure = stopOnFirstFailure;
  }

  /** Gets the Blaze crash Throwable. Null if Blaze did not crash. */
  public Throwable getUnhandledThrowable() {
    return crash;
  }

  public void setBuildConfiguration(BuildConfigurationValue configuration) {
    this.configuration = configuration;
  }

  /** Returns the build configuration collection used for the build. */
  public BuildConfigurationValue getBuildConfiguration() {
    return configuration;
  }

  void setConvenienceSymlinks(ImmutableMap<PathFragment, PathFragment> convenienceSymlinks) {
    this.convenienceSymlinks = convenienceSymlinks;
  }

  /**
   * Returns the convenience symlinks for this build in name -> target format (eg blaze-out ->
   * /symlink/target).
   */
  public ImmutableMap<PathFragment, PathFragment> getConvenienceSymlinks() {
    return convenienceSymlinks;
  }

  /** @see #getActualTargets */
  public void setActualTargets(Collection<ConfiguredTarget> actualTargets) {
    this.actualTargets = actualTargets;
  }

  /**
   * Returns the actual set of targets which we attempted to build. This value is set during the
   * build, after the target patterns have been parsed and resolved. If --keep_going is specified,
   * this set may exclude targets that could not be found or successfully analyzed. It may be
   * examined after the build. May be null even after the build, if there were errors in the loading
   * or analysis phases.
   */
  public Collection<ConfiguredTarget> getActualTargets() {
    return actualTargets;
  }

  /** @see #getTestTargets */
  public void setTestTargets(@Nullable Collection<ConfiguredTarget> testTargets) {
    this.testTargets = testTargets == null ? null : Collections.unmodifiableCollection(testTargets);
  }

  /**
   * Returns the actual unmodifiable collection of targets which we attempted to test. This value is
   * set at the end of the build analysis phase, after the test target patterns have been parsed and
   * resolved. If --keep_going is specified, this collection may exclude targets that could not be
   * found or successfully analyzed. It may be examined after the build. May be null even after the
   * build, if there were errors in the loading or analysis phases or if testing was not requested.
   */
  public Collection<ConfiguredTarget> getTestTargets() {
    return testTargets;
  }

  /** @see #getSuccessfulTargets */
  void setSuccessfulTargets(Collection<ConfiguredTarget> successfulTargets) {
    this.successfulTargets = successfulTargets;
  }

  /** See #getSuccessfulAspects */
  void setSuccessfulAspects(ImmutableSet<AspectKey> successfulAspects) {
    this.successfulAspects = successfulAspects;
  }

  void setPostBuildCallbackFailureDetail(FailureDetail failureDetail) {
    this.postBuildCallbackFailureDetail = failureDetail;
  }

  @Nullable
  /** @return only set if build was successful; if callback is successful as well, returns null. */
  public FailureDetail getPostBuildCallBackFailureDetail() {
    return postBuildCallbackFailureDetail;
  }

  /**
   * Returns the set of targets that were successfully built. This value is set at the end of the
   * build, after the target patterns have been parsed and resolved and after attempting to build
   * the targets. If --keep_going is specified, this set may exclude targets that could not be found
   * or successfully analyzed, or could not be built. It may be examined after the build. May be
   * null if the execution phase was not attempted, as may happen if there are errors in the loading
   * phase, for example.
   */
  public Collection<ConfiguredTarget> getSuccessfulTargets() {
    return successfulTargets;
  }

  /**
   * Returns the set of aspects that were successfully built. This value is set at the end of the
   * build, after the target patterns have been parsed and resolved and after attempting to build
   * the targets. If --keep_going is specified, this set may exclude targets that could not be found
   * or successfully analyzed, or could not be built. It may be examined after the build. May be
   * null if the execution phase was not attempted, as may happen if there are errors in the loading
   * phase, for example.
   */
  public ImmutableSet<AspectKey> getSuccessfulAspects() {
    return successfulAspects;
  }

  /** See {@link #getSkippedTargets()}. */
  void setSkippedTargets(Collection<ConfiguredTarget> skippedTargets) {
    this.skippedTargets = skippedTargets;
  }

  /**
   * Returns the set of targets which were skipped (Blaze didn't attempt to execute them) because
   * they're not compatible with the build's target platform.
   */
  public Collection<ConfiguredTarget> getSkippedTargets() {
    return skippedTargets;
  }

  /**
   * Collection of data for the build tool logs event. This may only be modified until the
   * BuildCompleteEvent is posted; any changes after that event is handled will not be included in
   * the build tool logs event.
   */
  public BuildToolLogCollection getBuildToolLogCollection() {
    return buildToolLogCollection;
  }

  /** For debugging. */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("startTimeMillis", startTimeMillis)
        .add("stopTimeMillis", stopTimeMillis)
        .add("crash", crash)
        .add("catastrophe", catastrophe)
        .add("detailedExitCode", detailedExitCode)
        .add("actualTargets", actualTargets)
        .add("testTargets", testTargets)
        .add("successfulTargets", successfulTargets)
        .add("buildToolLogCollection", buildToolLogCollection)
        .toString();
  }

  /** Collection of data for the build tool logs event. See {@link BuildToolLogs} for details. */
  public static final class BuildToolLogCollection {
    private final List<Pair<String, ByteString>> directValues = new ArrayList<>();
    private final List<Pair<String, ListenableFuture<String>>> futureUris = new ArrayList<>();
    private final List<LogFileEntry> localFiles = new ArrayList<>();
    private boolean frozen;

    @CanIgnoreReturnValue
    public BuildToolLogCollection freeze() {
      frozen = true;
      return this;
    }

    @VisibleForTesting
    public List<LogFileEntry> getLocalFiles() {
      return localFiles;
    }

    @CanIgnoreReturnValue
    public BuildToolLogCollection addDirectValue(String name, byte[] data) {
      Preconditions.checkState(!frozen);
      this.directValues.add(Pair.of(name, ByteString.copyFrom(data)));
      return this;
    }

    @CanIgnoreReturnValue
    public BuildToolLogCollection addUri(String name, String uri) {
      Preconditions.checkState(!frozen);
      this.futureUris.add(Pair.of(name, Futures.immediateFuture(uri)));
      return this;
    }

    @CanIgnoreReturnValue
    public BuildToolLogCollection addUriFuture(String name, ListenableFuture<String> uriFuture) {
      Preconditions.checkState(!frozen);
      this.futureUris.add(Pair.of(name, uriFuture));
      return this;
    }

    @CanIgnoreReturnValue
    public BuildToolLogCollection addLocalFile(String name, Path path) {
      return addLocalFile(name, path, LocalFileType.LOG, LocalFileCompression.NONE);
    }

    @CanIgnoreReturnValue
    public BuildToolLogCollection addLocalFile(
        String name, Path path, LocalFileType localFileType, LocalFileCompression compression) {
      return addLocalFile(
          name, new LocalFile(path, localFileType, compression, /* artifactMetadata= */ null));
    }

    @CanIgnoreReturnValue
    public BuildToolLogCollection addLocalFile(String name, LocalFile localFile) {
      Preconditions.checkState(!frozen);
      if (localFile.compression == LocalFileCompression.GZIP) {
        name += ".gz";
      }
      this.localFiles.add(new LogFileEntry(name, localFile));
      return this;
    }

    public BuildToolLogs toEvent() {
      Preconditions.checkState(frozen);
      return new BuildToolLogs(directValues, futureUris, localFiles);
    }

    /** For debugging. */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("directValues", directValues)
          .add("futureUris", futureUris)
          .add("localFiles", localFiles)
          .toString();
    }
  }
}
