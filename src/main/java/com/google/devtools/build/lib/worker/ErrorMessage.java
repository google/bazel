// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.worker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;

/** A well-formatted error message that is easy to read and easy to create. */
record ErrorMessage(String message) {
  ErrorMessage {
    requireNonNull(message, "message");
  }

  @Override
  public String toString() {
    return message();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String message = "Unknown error";
    private Path logFile;
    private String logText = "";
    private int logSizeLimit = Integer.MAX_VALUE;
    private Exception exception;

    private Builder() {}

    /** Sets the main text of this error message. */
    @CanIgnoreReturnValue
    public Builder message(String message) {
      Preconditions.checkNotNull(message);
      this.message = message.isEmpty() ? "Unknown error" : message.trim();
      return this;
    }

    /** Sets the log file that should be printed as part of the error message. */
    public Builder logFile(Path logFile) {
      Preconditions.checkNotNull(logFile);
      try {
        this.logFile = logFile;
        return logText(FileSystemUtils.readContent(logFile, UTF_8));
      } catch (IOException e) {
        logSizeLimit(Integer.MAX_VALUE);
        return logText(
            "ERROR: IOException while trying to read log file:\n"
                + Throwables.getStackTraceAsString(e));
      }
    }

    /**
     * Sets additional text, which is to be presented as a log file in the error message.
     *
     * <p>If the log originally comes from a file, it is recommended to use {@link #logFile}
     * instead, because then the path to the log file can be printed together with the message.
     */
    @CanIgnoreReturnValue
    public Builder logText(String logText) {
      Preconditions.checkNotNull(logText);
      // Set the log text to "(empty)" when the passed in string is empty, otherwise error messages
      // like "Something failed. Check below log for details:" would be pretty confusing for users.
      this.logText = logText.isEmpty() ? "(empty)" : logText.trim();
      return this;
    }

    /**
     * If the log file or text of this error message is longer than the character limit set via this
     * method, it will be truncated so that only the last X characters of the log are printed.
     */
    @CanIgnoreReturnValue
    public Builder logSizeLimit(int logSizeLimit) {
      Preconditions.checkArgument(logSizeLimit > 0, "logSizeLimit must be positive");
      this.logSizeLimit = logSizeLimit;
      return this;
    }

    /** Lets the error message contain the details of an exception. */
    @CanIgnoreReturnValue
    public Builder exception(Exception e) {
      this.exception = e;
      return this;
    }

    /** Builds and returns the formatted error message. */
    public ErrorMessage build() {
      StringBuilder sb = new StringBuilder(message);

      if (exception != null) {
        sb.append("\n\n---8<---8<--- Exception details ---8<---8<---\n");
        sb.append(Throwables.getStackTraceAsString(exception).trim());
        sb.append("\n---8<---8<--- End of exception details ---8<---8<---");
      }

      if (!logText.isEmpty()) {
        sb.append("\n\n---8<---8<--- Start of log");
        if (logText.length() > logSizeLimit) {
          sb.append(" snippet");
        }
        if (logFile != null) {
          sb.append(", file at ");
          sb.append(logFile.getPathString());
        }
        sb.append(" ---8<---8<---\n");

        // If the length of the log is longer than the limit, print only the last part.
        if (logText.length() > logSizeLimit) {
          sb.append("[... truncated ...]\n");
          sb.append(logText, logText.length() - logSizeLimit, logText.length());
          sb.append("\n---8<---8<--- End of log snippet, ");
          sb.append(logText.length() - logSizeLimit);
          sb.append(" chars omitted ---8<---8<---");
        } else {
          sb.append(logText);
          sb.append("\n---8<---8<--- End of log ---8<---8<---");
        }
      }

      return new ErrorMessage(sb.toString());
    }
  }
}
