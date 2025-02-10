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
package com.google.devtools.build.lib.actions;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.shell.ExecutionStatistics;
import com.google.devtools.build.lib.shell.TerminationStatus;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import javax.annotation.Nullable;

/** The result of a {@link Spawn}'s execution. */
@SuppressWarnings("GoodTime") // Use ints instead of Durations to improve build time (cl/505728570)
public interface SpawnResult {

  int POSIX_TIMEOUT_EXIT_CODE = /* SIGNAL_BASE= */ 128 + /* SIGALRM= */ 14;

  /** The status of the attempted Spawn execution. */
  enum Status {
    /** Subprocess executed successfully, and returned a zero exit code. */
    SUCCESS,

    /** Subprocess executed successfully, but returned a non-zero exit code. */
    NON_ZERO_EXIT(true),

    /** Subprocess execution timed out. */
    TIMEOUT(true),

    /**
     * The subprocess ran out of memory. On Linux systems, the kernel may kill processes in
     * low-memory situations, and this status is intended to report such a case back to Bazel.
     */
    OUT_OF_MEMORY(true),

    /**
     * Subprocess did not execute, it's not the user's fault, and the error is not catastrophic. If
     * keep_going is enabled then Bazel will try to continue the build, possibly will attempt to
     * rerun the same spawn, and possibly will attempt to run other actions.
     */
    EXECUTION_FAILED,

    /**
     * Subprocess did not execute, it's not the user's fault, and the error is catastrophic. Bazel
     * will not rerun this spawn. Bazel will attempt to not run other actions (regardless of whether
     * keep_going is enabled).
     */
    EXECUTION_FAILED_CATASTROPHICALLY,

    /**
     * Subprocess did not execute, it may be the user's fault, and the error is not catastrophic.
     * The user may be able to fix it. For example, a remote system may have denied the execution
     * due to too many inputs or too large inputs.
     */
    EXECUTION_DENIED(true),

    /**
     * Subprocess did not execute, it may be the user's fault, and the error is catastrophic. The
     * user may be able to prevent it from reoccurring. For example, an input file's contents may
     * have been modified by the user intra-build.
     */
    EXECUTION_DENIED_CATASTROPHICALLY(true),

    /**
     * The result of the remotely executed Spawn could not be retrieved due to errors in the remote
     * caching layer.
     */
    REMOTE_CACHE_FAILED;

    private final boolean isUserError;

    Status(boolean isUserError) {
      this.isUserError = isUserError;
    }

    Status() {
      this(false);
    }

    public boolean isConsideredUserError() {
      return isUserError;
    }
  }

  /**
   * Returns whether the spawn was actually run, regardless of the exit code. I.e., returns {@code
   * true} if {@link #status} is any of {@link Status#SUCCESS}, {@link Status#NON_ZERO_EXIT}, {@link
   * Status#TIMEOUT} or {@link Status#OUT_OF_MEMORY}.
   *
   * <p>Returns false if there were errors that prevented the spawn from being run, such as network
   * errors, missing local files, errors setting up sandboxing, etc.
   */
  default boolean setupSuccess() {
    Status status = status();
    return status == Status.SUCCESS
        || status == Status.NON_ZERO_EXIT
        || status == Status.TIMEOUT
        || status == Status.OUT_OF_MEMORY;
  }

  /**
   * Returns true if the status was {@link Status#EXECUTION_FAILED_CATASTROPHICALLY} or {@link
   * Status#EXECUTION_DENIED_CATASTROPHICALLY}.
   */
  default boolean isCatastrophe() {
    return status() == Status.EXECUTION_FAILED_CATASTROPHICALLY
        || status() == Status.EXECUTION_DENIED_CATASTROPHICALLY;
  }

  /** Returns the status of the attempted Spawn execution. */
  Status status();

  /**
   * Returns the exit code of the subprocess if the subprocess was executed.
   *
   * <p>Returns zero if {@link #status} returns {@link Status#SUCCESS}.
   *
   * <p>Returns non-zero if {@link #status} returns {@link Status#NON_ZERO_EXIT} or {@link
   * Status#OUT_OF_MEMORY}.
   *
   * <p>Returns 128 + 14 (corresponding to the Unix signal SIGALRM) if {@link #status} returns
   * {@link Status#TIMEOUT}.
   *
   * <p>Otherwise, the returned value is not meaningful.
   */
  // TODO(mschaller): clean up all uses of this method when {@code !this.setupSuccess()}
  int exitCode();

  /**
   * A detailed representation of what failed if {@link #status} is not {@link Status#SUCCESS}, and
   * {@code null} otherwise.
   */
  @Nullable
  FailureDetail failureDetail();

  /**
   * Returns the host name of the executor or {@code null}. This information is intended for
   * debugging purposes, especially for remote execution systems. Remote caches usually do not store
   * the original host name, so this is generally {@code null} for cache hits.
   */
  @Nullable
  String getExecutorHostName();

  /**
   * Returns the name of the SpawnRunner that executed the spawn. It should always be defined,
   * unless isCacheHit is true, in which case the spawn was not actually run.
   */
  String getRunnerName();

  /** Returns optional details about the runner. */
  String getRunnerSubtype();

  /**
   * Returns the start time for the {@link Spawn}'s execution.
   *
   * @return the measurement, or null in case of execution errors or when the measurement is not
   *     implemented for the current platform
   */
  @Nullable
  Instant getStartTime();

  /**
   * Returns the wall time taken by the {@link Spawn}'s execution.
   *
   * @return the measurement, or 0 in case of execution errors or when the measurement is not
   *     implemented for the current platform
   */
  int getWallTimeInMs();

  /**
   * Returns the user time taken by the {@link Spawn}'s execution.
   *
   * @return the measurement, or 0 in case of execution errors or when the measurement is not
   *     implemented for the current platform
   */
  int getUserTimeInMs();

  /**
   * Returns the system time taken by the {@link Spawn}'s execution.
   *
   * @return the measurement, or 0 in case of execution errors or when the measurement is not
   *     implemented for the current platform
   */
  int getSystemTimeInMs();

  /**
   * Returns the number of block output operations during the {@link Spawn}'s execution.
   *
   * @return the measurement, or null in case of execution errors or when the measurement is not
   *     implemented for the current platform
   */
  @Nullable
  Long getNumBlockOutputOperations();

  /**
   * Returns the number of block input operations during the {@link Spawn}'s execution.
   *
   * @return the measurement, or null in case of execution errors or when the measurement is not
   *     implemented for the current platform
   */
  @Nullable
  Long getNumBlockInputOperations();

  /**
   * Returns the number of involuntary context switches during the {@link Spawn}'s execution.
   *
   * @return the measurement, or null in case of execution errors or when the measurement is not
   *     implemented for the current platform
   */
  @Nullable
  Long getNumInvoluntaryContextSwitches();

  /**
   * Returns the memory in Kilobytes used during the {@link Spawn}'s execution. The spawn memory
   * based on the maximum resident set size during command execution.
   *
   * @return the measurement, or null in case of execution errors or when the measurement is not
   *     implemented for the current platform
   */
  // TODO(b/181317827) implement for windows systems.
  @Nullable
  Long getMemoryInKb();

  SpawnMetrics getMetrics();

  /** Returns whether the spawn result was a cache hit. */
  boolean isCacheHit();

  /** Returns an optional custom failure message for the result. */
  default String getFailureMessage() {
    return "";
  }

  /**
   * Returns a {@link Spawn}'s output in-memory, if supported and available.
   *
   * <p>This behavior may be triggered with {@link
   * ExecutionRequirements#REMOTE_EXECUTION_INLINE_OUTPUTS}.
   */
  @Nullable
  default ByteString getInMemoryOutput(ActionInput output) {
    return null;
  }

  String getDetailMessage(String message, boolean catastrophe, boolean forciblyRunRemotely);

  /** Whether the spawn result was obtained through remote strategy. */
  boolean wasRemote();

  /**
   * Returns the remote or disk cache digest.
   *
   * <p>Only available when remote execution, remote cache or disk cache was enabled for the spawn.
   */
  @Nullable
  default Digest getDigest() {
    return null;
  }

  /** Basic implementation of {@link SpawnResult}. */
  @Immutable
  @ThreadSafe
  final class SimpleSpawnResult implements SpawnResult {
    private final int exitCode;
    private final Status status;
    @Nullable private final FailureDetail failureDetail;
    private final String executorHostName;
    private final String runnerName;
    private final String runnerSubtype;
    private final SpawnMetrics spawnMetrics;
    @Nullable private final Instant startTime;
    private final int wallTimeInMs;
    private final int userTimeInMs;
    private final int systemTimeInMs;
    @Nullable private final Long numBlockOutputOperations;
    @Nullable private final Long numBlockInputOperations;
    @Nullable private final Long numInvoluntaryContextSwitches;
    @Nullable private final Long memoryKb;
    private final boolean cacheHit;
    private final String failureMessage;

    // Invariant: Either both have a value or both are null.
    @Nullable private final ActionInput inMemoryOutputFile;
    @Nullable private final ByteString inMemoryContents;

    private final boolean remote;
    @Nullable private final Digest digest;

    SimpleSpawnResult(Builder builder) {
      this.exitCode = builder.exitCode;
      this.status = Preconditions.checkNotNull(builder.status);
      this.failureDetail = builder.failureDetail;
      this.executorHostName = builder.executorHostName;
      this.runnerName = builder.runnerName;
      this.runnerSubtype = builder.runnerSubtype;
      this.spawnMetrics =
          builder.spawnMetrics != null
              ? builder.spawnMetrics
              : SpawnMetrics.forLocalExecution(builder.wallTimeInMs);
      this.startTime = builder.startTime;
      this.wallTimeInMs = builder.wallTimeInMs;
      this.userTimeInMs = builder.userTimeInMs;
      this.systemTimeInMs = builder.systemTimeInMs;
      this.numBlockOutputOperations = builder.numBlockOutputOperations;
      this.numBlockInputOperations = builder.numBlockInputOperations;
      this.numInvoluntaryContextSwitches = builder.numInvoluntaryContextSwitches;
      this.memoryKb = builder.memoryInKb;
      this.cacheHit = builder.cacheHit;
      this.failureMessage = builder.failureMessage;
      this.inMemoryOutputFile = builder.inMemoryOutputFile;
      this.inMemoryContents = builder.inMemoryContents;
      this.remote = builder.remote;
      this.digest = builder.digest;
    }

    @Override
    public int exitCode() {
      return exitCode;
    }

    @Override
    public Status status() {
      return status;
    }

    @Override
    @Nullable
    public FailureDetail failureDetail() {
      return failureDetail;
    }

    @Override
    public String getExecutorHostName() {
      return executorHostName;
    }

    @Override
    public String getRunnerName() {
      return runnerName;
    }

    @Override
    public String getRunnerSubtype() {
      return runnerSubtype;
    }

    @Override
    public SpawnMetrics getMetrics() {
      return spawnMetrics;
    }

    @Override
    public Instant getStartTime() {
      return startTime;
    }

    @Override
    public int getWallTimeInMs() {
      return wallTimeInMs;
    }

    @Override
    public int getUserTimeInMs() {
      return userTimeInMs;
    }

    @Override
    public int getSystemTimeInMs() {
      return systemTimeInMs;
    }

    @Override
    public Long getNumBlockOutputOperations() {
      return numBlockOutputOperations;
    }

    @Override
    public Long getNumBlockInputOperations() {
      return numBlockInputOperations;
    }

    @Override
    public Long getNumInvoluntaryContextSwitches() {
      return numInvoluntaryContextSwitches;
    }

    @Override
    public Long getMemoryInKb() {
      return memoryKb;
    }

    @Override
    public boolean isCacheHit() {
      return cacheHit;
    }

    @Override
    public String getFailureMessage() {
      return failureMessage;
    }

    @Override
    public String getDetailMessage(
        String message, boolean catastrophe, boolean forciblyRunRemotely) {
      TerminationStatus status = new TerminationStatus(exitCode(), status() == Status.TIMEOUT);
      String reason = "(" + status.toShortString() + ")"; // e.g. "(Exit 1)"
      String explanation = Strings.isNullOrEmpty(message) ? "" : ": " + message;

      if (status() == Status.TIMEOUT) {
        // 0 wall time means no measurement
        if (getWallTimeInMs() != 0) {
          explanation +=
              String.format(
                  Locale.US,
                  " (failed due to timeout after %.2f seconds.)",
                  getWallTimeInMs() / 1000.0);
        } else {
          explanation += " (failed due to timeout.)";
        }
      } else if (status() == Status.OUT_OF_MEMORY) {
        explanation += " (Remote action was terminated due to Out of Memory.)";
      }
      if (status() != Status.TIMEOUT && forciblyRunRemotely) {
        explanation +=
            " Action tagged as local was forcibly run remotely and failed - it's "
                + "possible that the action simply doesn't work remotely";
      }
      return reason + explanation;
    }

    @Nullable
    @Override
    public ByteString getInMemoryOutput(ActionInput output) {
      if (inMemoryOutputFile != null && inMemoryOutputFile.equals(output)) {
        return inMemoryContents;
      }
      return null;
    }

    @Override
    public boolean wasRemote() {
      return remote;
    }

    @Override
    public Digest getDigest() {
      return digest;
    }
  }

  /**
   * A helper class for wrapping an existing {@link SpawnResult} and modifying a subset of its
   * methods.
   */
  class DelegateSpawnResult implements SpawnResult {
    private final SpawnResult delegate;

    public DelegateSpawnResult(SpawnResult delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean setupSuccess() {
      return delegate.setupSuccess();
    }

    @Override
    public boolean isCatastrophe() {
      return delegate.isCatastrophe();
    }

    @Override
    public Status status() {
      return delegate.status();
    }

    @Override
    public int exitCode() {
      return delegate.exitCode();
    }

    @Override
    @Nullable
    public FailureDetail failureDetail() {
      return delegate.failureDetail();
    }

    @Override
    @Nullable
    public String getExecutorHostName() {
      return delegate.getExecutorHostName();
    }

    @Override
    public String getRunnerName() {
      return delegate.getRunnerName();
    }

    @Override
    public String getRunnerSubtype() {
      return delegate.getRunnerSubtype();
    }

    @Override
    @Nullable
    public Instant getStartTime() {
      return delegate.getStartTime();
    }

    @Override
    public int getWallTimeInMs() {
      return delegate.getWallTimeInMs();
    }

    @Override
    public int getUserTimeInMs() {
      return delegate.getUserTimeInMs();
    }

    @Override
    public int getSystemTimeInMs() {
      return delegate.getSystemTimeInMs();
    }

    @Override
    @Nullable
    public Long getNumBlockOutputOperations() {
      return delegate.getNumBlockOutputOperations();
    }

    @Override
    @Nullable
    public Long getNumBlockInputOperations() {
      return delegate.getNumBlockInputOperations();
    }

    @Override
    @Nullable
    public Long getNumInvoluntaryContextSwitches() {
      return delegate.getNumInvoluntaryContextSwitches();
    }

    @Override
    @Nullable
    public Long getMemoryInKb() {
      return delegate.getMemoryInKb();
    }

    @Override
    public SpawnMetrics getMetrics() {
      return delegate.getMetrics();
    }

    @Override
    public boolean isCacheHit() {
      return delegate.isCacheHit();
    }

    @Override
    public String getFailureMessage() {
      return delegate.getFailureMessage();
    }

    @Override
    @Nullable
    public ByteString getInMemoryOutput(ActionInput output) {
      return delegate.getInMemoryOutput(output);
    }

    @Override
    public String getDetailMessage(
        String message, boolean catastrophe, boolean forciblyRunRemotely) {
      return delegate.getDetailMessage(message, catastrophe, forciblyRunRemotely);
    }

    @Override
    public boolean wasRemote() {
      return delegate.wasRemote();
    }

    @Override
    @Nullable
    public Digest getDigest() {
      return delegate.getDigest();
    }
  }

  /** Builder class for {@link SpawnResult}. */
  final class Builder {
    private int exitCode;
    private Status status;
    private FailureDetail failureDetail;
    private String executorHostName;
    private String runnerName = "";
    private String runnerSubtype = "";
    private SpawnMetrics spawnMetrics;
    private Instant startTime;
    private int wallTimeInMs;
    private int userTimeInMs;
    private int systemTimeInMs;
    private Long numBlockOutputOperations;
    private Long numBlockInputOperations;
    private Long numInvoluntaryContextSwitches;
    private Long memoryInKb;
    private boolean cacheHit;
    private String failureMessage = "";

    // Invariant: Either both have a value or both are null.
    @Nullable private ActionInput inMemoryOutputFile;
    @Nullable private ByteString inMemoryContents;

    private boolean remote;
    private Digest digest;

    public SpawnResult build() {
      Preconditions.checkArgument(!runnerName.isEmpty());
      switch (status) {
        case SUCCESS:
          Preconditions.checkArgument(exitCode == 0, exitCode);
          Preconditions.checkArgument(failureDetail == null, failureDetail);
          break;
        case TIMEOUT:
          Preconditions.checkArgument(exitCode == POSIX_TIMEOUT_EXIT_CODE, exitCode);
          // Fall through.
        default:
          Preconditions.checkArgument(
              exitCode != 0,
              "Failed spawn with status %s had exit code 0 (%s %s)",
              status,
              failureMessage,
              failureDetail);
          Preconditions.checkArgument(
              failureDetail != null,
              "Failed spawn with status %s and exit code %s had no failure detail (%s)",
              status,
              exitCode,
              failureMessage);
          if (!status.isConsideredUserError()
              && ExitCode.BUILD_FAILURE.equals(DetailedExitCode.getExitCode(failureDetail))) {
            BugReport.sendBugReport(
                new IllegalStateException(
                    String.format(
                        "System error %s should not have failure detail %s with 'build failure'"
                            + " exit code (%s)",
                        status, failureDetail, failureMessage)));
          }
      }

      return new SimpleSpawnResult(this);
    }

    @CanIgnoreReturnValue
    public Builder setExitCode(int exitCode) {
      this.exitCode = exitCode;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setStatus(Status status) {
      this.status = status;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setFailureDetail(FailureDetail failureDetail) {
      this.failureDetail = failureDetail;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setExecutorHostname(String executorHostName) {
      this.executorHostName = executorHostName;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setRunnerName(String runnerName) {
      this.runnerName = runnerName;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSpawnMetrics(SpawnMetrics spawnMetrics) {
      this.spawnMetrics = spawnMetrics;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setStartTime(Instant startTime) {
      this.startTime = startTime;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setWallTimeInMs(int wallTimeInMs) {
      this.wallTimeInMs = wallTimeInMs;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setUserTimeInMs(int userTimeInMs) {
      this.userTimeInMs = userTimeInMs;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSystemTimeInMs(int systemTimeInMs) {
      this.systemTimeInMs = systemTimeInMs;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setNumBlockOutputOperations(long numBlockOutputOperations) {
      this.numBlockOutputOperations = numBlockOutputOperations;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setNumBlockInputOperations(long numBlockInputOperations) {
      this.numBlockInputOperations = numBlockInputOperations;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setNumInvoluntaryContextSwitches(long numInvoluntaryContextSwitches) {
      this.numInvoluntaryContextSwitches = numInvoluntaryContextSwitches;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setMemoryInKb(long memoryInKb) {
      this.memoryInKb = memoryInKb;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCacheHit(boolean cacheHit) {
      this.cacheHit = cacheHit;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setFailureMessage(String failureMessage) {
      this.failureMessage = failureMessage;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setInMemoryOutput(ActionInput outputFile, ByteString contents) {
      this.inMemoryOutputFile = Preconditions.checkNotNull(outputFile);
      this.inMemoryContents = Preconditions.checkNotNull(contents);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setRemote(boolean remote) {
      this.remote = remote;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setDigest(Digest digest) {
      this.digest = digest;
      return this;
    }

    /** Adds execution statistics based on a {@code execution_statistics.proto} file. */
    @CanIgnoreReturnValue
    public Builder setResourceUsageFromProto(Path statisticsPath) throws IOException {
      ExecutionStatistics.getResourceUsage(statisticsPath)
          .ifPresent(
              resourceUsage -> {
                setUserTimeInMs((int) resourceUsage.getUserExecutionTime().toMillis());
                setSystemTimeInMs((int) resourceUsage.getSystemExecutionTime().toMillis());
                setNumBlockOutputOperations(resourceUsage.getBlockOutputOperations());
                setNumBlockInputOperations(resourceUsage.getBlockInputOperations());
                setNumInvoluntaryContextSwitches(resourceUsage.getInvoluntaryContextSwitches());
                // The memory usage of the largest child process. For Darwin maxrss returns size in
                // bytes.
                if (OS.getCurrent() == OS.DARWIN) {
                  setMemoryInKb(resourceUsage.getMaximumResidentSetSize() / 1000);
                } else {
                  setMemoryInKb(resourceUsage.getMaximumResidentSetSize());
                }
              });
      return this;
    }
  }
}
