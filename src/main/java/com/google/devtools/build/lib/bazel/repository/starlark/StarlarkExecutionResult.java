// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.starlark;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.shell.AbnormalTerminationException;
import com.google.devtools.build.lib.shell.BadExitStatusException;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.shell.CommandResult;
import com.google.devtools.build.lib.shell.TerminationStatus;
import com.google.devtools.build.lib.util.io.DelegatingOutErr;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.util.io.RecordingOutErr;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.StarlarkValue;

/**
 * A structure callable from Starlark that stores the result of repository_ctx.execute() method. It
 * contains the standard output stream content, the standard error stream content and the execution
 * return code.
 */
@Immutable
@StarlarkBuiltin(
    name = "exec_result",
    category = DocCategory.BUILTIN,
    doc =
        """
        A structure storing result of repository_ctx.execute() method. It contains the standard \
        output stream content, the standard error stream content and the execution return \
        code.
        """)
final class StarlarkExecutionResult implements StarlarkValue {
  private final int returnCode;
  private final String stdout;
  private final String stderr;

  StarlarkExecutionResult(int returnCode, String stdout, String stderr) {
    this.returnCode = returnCode;
    this.stdout = stdout;
    this.stderr = stderr;
  }

  @Override
  public boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  @StarlarkMethod(
      name = "return_code",
      structField = true,
      doc =
          """
          The return code returned after the execution of the program. 256 if the process was \
          terminated by a time out; values larger than 128 indicate termination by a \
          signal.
          """)
  public int getReturnCode() {
    return returnCode;
  }

  @StarlarkMethod(
      name = "stdout",
      structField = true,
      doc = "The content of the standard output returned by the execution.")
  public String getStdout() {
    return stdout;
  }

  @StarlarkMethod(
      name = "stderr",
      structField = true,
      doc = "The content of the standard error output returned by the execution.")
  public String getStderr() {
    return stderr;
  }

  /**
   * Returns a Builder that can be used to execute a command and build an execution result.
   *
   * @param environment pass through the list of environment variables from the client to be passed
   * to the execution environment.
   */
  public static Builder builder(Map<String, String> environment) {
    return new Builder(environment);
  }

  /**
   * A Builder class to build a {@link StarlarkExecutionResult} object by executing a command.
   */
  static final class Builder {

    private final List<String> args = new ArrayList<>();
    private File directory = null;
    private final Map<String, String> envBuilder = Maps.newLinkedHashMap();
    private long timeout = -1;
    private boolean executed = false;
    private boolean quiet;

    private Builder(Map<String, String> environment) {
      envBuilder.putAll(environment);
    }

    /**
     * Adds arguments to the list of arguments to pass to the command. The first argument is
     * expected to be the binary to execute. The subsequent arguments are the arguments passed to
     * the binary.
     */
    @CanIgnoreReturnValue
    Builder addArguments(List<String> args) {
      this.args.addAll(args);
      return this;
    }

    /**
     * Set the path to the directory to execute the result process. This method must be called
     * before calling {@link #execute()}.
     */
    @CanIgnoreReturnValue
    Builder setDirectory(File path) {
      this.directory = path;
      return this;
    }

    /**
     * Add an environment variables to be added to the list of environment variables. For all key
     * <code>k</code> of <code>variables</code>, the resulting process will have the variable <code>
     * k=variables.get(k)</code> defined.
     */
    @CanIgnoreReturnValue
    Builder addEnvironmentVariables(Map<String, String> variables) {
      this.envBuilder.putAll(variables);
      return this;
    }

    /** Ensure that an environment variable is not passed to the process. */
    @CanIgnoreReturnValue
    Builder removeEnvironmentVariables(Set<String> removeEnvVariables) {
      removeEnvVariables.forEach(envBuilder::remove);
      return this;
    }

    /** Sets the timeout, in milliseconds, after which the executed command will be terminated. */
    @CanIgnoreReturnValue
    Builder setTimeout(long timeout) {
      Preconditions.checkArgument(timeout > 0, "Timeout must be a positive number.");
      this.timeout = timeout;
      return this;
    }

    @CanIgnoreReturnValue
    Builder setQuiet(boolean quiet) {
      this.quiet = quiet;
      return this;
    }

    private static String toString(ByteArrayOutputStream stream) {
      try {
        return stream.toString(ISO_8859_1);
      } catch (IllegalStateException e) {
        return "";
      }
    }

    /** Execute the command specified by {@link #addArguments}. */
    StarlarkExecutionResult execute() throws InterruptedException {
      Preconditions.checkArgument(timeout > 0, "Timeout must be set prior to calling execute().");
      Preconditions.checkArgument(!args.isEmpty(), "No command specified.");
      Preconditions.checkState(!executed, "Command was already executed, cannot re-use builder.");
      Preconditions.checkNotNull(directory, "Directory must be set before calling execute().");
      executed = true;

      DelegatingOutErr delegator = new DelegatingOutErr();
      RecordingOutErr recorder = new RecordingOutErr();
      // TODO(dmarting): if a lot of data is sent to stdout, this will use all the memory and
      // Bazel will crash. Maybe we should use custom output streams that throw an appropriate
      // exception when reaching a specific size.
      delegator.addSink(recorder);
      if (!quiet) {
        delegator.addSink(OutErr.create(System.err, System.err));
      }
      try {
        String[] argsArray = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
          argsArray[i] = args.get(i);
        }
        Command command = new Command(argsArray, envBuilder, directory, Duration.ofMillis(timeout));
        CommandResult result =
            command.execute(delegator.getOutputStream(), delegator.getErrorStream());
        return new StarlarkExecutionResult(
            result.terminationStatus().getExitCode(),
            recorder.outAsLatin1(),
            recorder.errAsLatin1());
      } catch (BadExitStatusException e) {
        return new StarlarkExecutionResult(
            e.getResult().terminationStatus().getExitCode(),
            recorder.outAsLatin1(),
            recorder.errAsLatin1());
      } catch (AbnormalTerminationException e) {
        TerminationStatus status = e.getResult().terminationStatus();
        if (status.timedOut()) {
          // Signal a timeout by an exit code outside the normal range
          return new StarlarkExecutionResult(256, "", e.getMessage());
        } else if (status.exited()) {
          return new StarlarkExecutionResult(
              status.getExitCode(),
              toString(e.getResult().stdoutStream()),
              toString(e.getResult().stderrStream()));
        } else if (status.getTerminatingSignal() == 15) {
          // We have a bit of a problem here: we cannot distingusih between the case where
          // the SIGTERM was sent by something that the calling rule wants to legitimately handle,
          // and the case where it was sent by bazel to abort the build, e.g., because something
          // else failed.
          //
          // We just assume the latter to correctly handle aborts, accepting that rule authors have
          // to write their rules without relying on the ability to handle termination by signal 15.
          throw new InterruptedException();
        } else {
          return new StarlarkExecutionResult(
              status.getRawExitCode(),
              toString(e.getResult().stdoutStream()),
              toString(e.getResult().stderrStream()));
        }
      } catch (CommandException e) {
        // 256 is outside of the standard range for exit code on Unixes. We are not guaranteed that
        // on all system it would be outside of the standard range.
        return new StarlarkExecutionResult(256, "", e.getMessage());
      }
    }
  }
}
