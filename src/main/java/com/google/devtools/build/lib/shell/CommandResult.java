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

package com.google.devtools.build.lib.shell;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.flogger.GoogleLogger;
import com.google.common.flogger.LazyArgs;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * Encapsulates the results of a command execution, including exit status and output to stdout and
 * stderr.
 *
 * @param stdoutStream Returns the stdout {@link ByteArrayOutputStream}.
 * @param stderrStream Returns the stderr {@link ByteArrayOutputStream}.
 * @param terminationStatus Returns the termination status of the subprocess.
 * @param wallExecutionTime Returns the wall execution time. the measurement, or empty in case of
 *     execution errors or when the measurement is not implemented for the current platform
 * @param userExecutionTime Returns the user execution time. the measurement, or empty in case of
 *     execution errors or when the measurement is not implemented for the current platform
 * @param systemExecutionTime Returns the system execution time. the measurement, or empty in case
 *     of execution errors or when the measurement is not implemented for the current platform
 */
public record CommandResult(
    ByteArrayOutputStream stdoutStream,
    ByteArrayOutputStream stderrStream,
    TerminationStatus terminationStatus,
    Optional<Duration> wallExecutionTime,
    Optional<Duration> userExecutionTime,
    Optional<Duration> systemExecutionTime) {
  public CommandResult {
    requireNonNull(stdoutStream, "stdoutStream");
    requireNonNull(stderrStream, "stderrStream");
    requireNonNull(terminationStatus, "terminationStatus");
    requireNonNull(wallExecutionTime, "wallExecutionTime");
    requireNonNull(userExecutionTime, "userExecutionTime");
    requireNonNull(systemExecutionTime, "systemExecutionTime");
  }

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final byte[] NO_BYTES = new byte[0];

  static final ByteArrayOutputStream EMPTY_OUTPUT =
    new ByteArrayOutputStream() {

      @Override
      public synchronized byte[] toByteArray() {
        return NO_BYTES;
      }
  };

  static final ByteArrayOutputStream NO_OUTPUT_COLLECTED =
    new ByteArrayOutputStream(){

      @Override
      public synchronized byte[] toByteArray() {
        throw new IllegalStateException("Output was not collected");
      }
  };

  /**
   * Returns the stdout as a byte array.
   *
   * @return raw bytes that were written to stdout by the command, or null if caller did chose to
   *     ignore output
   * @throws IllegalStateException if output was not collected
   */
  public byte[] getStdout() {
    return stdoutStream().toByteArray();
  }

  /**
   * Returns the stderr as a byte array.
   *
   * @return raw bytes that were written to stderr by the command, or null if caller did chose to
   *     ignore output
   * @throws IllegalStateException if output was not collected
   */
  public byte[] getStderr() {
    return stderrStream().toByteArray();
  }

  void logThis() {
    logger.atFiner().log("%s", LazyArgs.lazy(() -> terminationStatus()));

    if (stdoutStream() == NO_OUTPUT_COLLECTED) {
      return;
    }
    logger.atFiner().log("Stdout: %s", LazyArgs.lazy(() -> LogUtil.toTruncatedString(getStdout())));
    logger.atFiner().log("Stderr: %s", LazyArgs.lazy(() -> LogUtil.toTruncatedString(getStderr())));
  }

  /** Returns a new {@link CommandResult.Builder}. */
  public static Builder builder() {
    return new AutoBuilder_CommandResult_Builder();
  }

  /** A builder for {@link CommandResult}s. */
  @AutoBuilder
  public abstract static class Builder {
    /** Sets the stdout output for the command. */
    public abstract Builder setStdoutStream(ByteArrayOutputStream stdout);

    /** Sets the stderr output for the command. */
    public abstract Builder setStderrStream(ByteArrayOutputStream stderr);

    /** Sets the termination status for the command. */
    public abstract Builder setTerminationStatus(TerminationStatus terminationStatus);

    /** Sets the wall execution time. */
    @CanIgnoreReturnValue
    public Builder setWallExecutionTime(Duration wallExecutionTime) {
      setWallExecutionTime(Optional.of(wallExecutionTime));
      return this;
    }

    /** Sets or clears the wall execution time. */
    public abstract Builder setWallExecutionTime(Optional<Duration> wallExecutionTime);

    /** Sets the user execution time. */
    @CanIgnoreReturnValue
    public Builder setUserExecutionTime(Duration userExecutionTime) {
      setUserExecutionTime(Optional.of(userExecutionTime));
      return this;
    }

    /** Sets or clears the user execution time. */
    public abstract Builder setUserExecutionTime(Optional<Duration> userExecutionTime);

    /** Sets the system execution time. */
    @CanIgnoreReturnValue
    public Builder setSystemExecutionTime(Duration systemExecutionTime) {
      setSystemExecutionTime(Optional.of(systemExecutionTime));
      return this;
    }

    /** Sets or clears the system execution time. */
    public abstract Builder setSystemExecutionTime(Optional<Duration> systemExecutionTime);

    /** Builds a {@link CommandResult} object. */
    public abstract CommandResult build();
  }
}
