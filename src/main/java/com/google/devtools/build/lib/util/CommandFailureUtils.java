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

package com.google.devtools.build.lib.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.cmdline.Label;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Utility methods for describing command failures. See also the CommandUtils class. Unlike that
 * one, this class does not depend on Command; instead, it just manipulates command lines
 * represented as Collection&lt;String&gt;.
 */
public class CommandFailureUtils {
  private static final int APPROXIMATE_MAXIMUM_MESSAGE_LENGTH = 200;

  private CommandFailureUtils() {} // Prevent instantiation.

  /**
   * Construct a string that describes the command. Currently this returns a message of the form
   * "foo bar baz", with shell meta-characters appropriately quoted and/or escaped, prefixed (if
   * verbose is true) with an "env" command to set the environment.
   *
   * @param form Form of the command to generate; see the documentation of the {@link
   *     CommandDescriptionForm} values.
   */
  public static String describeCommand(
      CommandDescriptionForm form,
      boolean prettyPrintArgs,
      Collection<String> commandLineElements,
      @Nullable Map<String, String> environment,
      @Nullable List<String> environmentVariablesToClear,
      @Nullable String cwd,
      @Nullable String configurationChecksum,
      @Nullable Label executionPlatformLabel,
      @Nullable String spawnRunner) {

    Preconditions.checkNotNull(form);
    StringBuilder message = new StringBuilder();
    int size = commandLineElements.size();
    int numberRemaining = size;

    if (form == CommandDescriptionForm.COMPLETE) {
      ScriptUtil.emitBeginIsolate(message);
    }

    if (form != CommandDescriptionForm.ABBREVIATED) {
      if (cwd != null) {
        ScriptUtil.emitChangeDirectory(message, cwd);
      }
      /*
       * On Linux, insert an "exec" keyword to save a fork in "blaze run"
       * generated scripts.  If we use "env" as a wrapper, the "exec" needs to
       * be applied to the entire "env" invocation.
       *
       * On Windows, this is a no-op.
       */
      ScriptUtil.emitExec(message);
      /*
       * Java does not provide any way to invoke a subprocess with the environment variables
       * in a specified order.  The order of environment variables in the 'environ' array
       * (which is set by the 'envp' parameter to the execve() system call)
       * is determined by the order of iteration on a HashMap constructed inside Java's
       * ProcessBuilder class (in the ProcessEnvironment class), which is nondeterministic.
       *
       * Nevertheless, we *print* the environment variables here in sorted order, rather
       * than in the potentially nondeterministic order that will be actually used.
       * This is slightly dubious... in theory a process's behaviour could depend on the order
       * of the environment variables passed to it.  (For example, the order of environment
       * variables in the environ array affects the output of '/usr/bin/env'.)
       * However, in practice very few processes depend on the order of the environment
       * variables, and using a deterministic sorted order here makes Blaze's output more
       * deterministic and easier to read.  So this seems the lesser of two evils... I think.
       * Anyway, it's not like we have much choice... even if we wanted to, there's no way to
       * print out the nondeterministic order that will actually be used, since there's
       * no way to guarantee that the iteration over entrySet() here will return the same
       * sequence as the iteration over entrySet() inside the ProcessBuilder class
       * (in ProcessEnvironment.StringEnvironment.toEnvironmentBlock()).
       */
      if (environment != null) {
        ScriptUtil.emitEnvPrefix(
            message, /* ignoreEnvironment= */ true, environment, environmentVariablesToClear);
      }
    }

    boolean isFirstArgument = true;
    for (String commandElement : commandLineElements) {
      if (form == CommandDescriptionForm.ABBREVIATED
          && message.length() + commandElement.length() > APPROXIMATE_MAXIMUM_MESSAGE_LENGTH) {
        message
            .append(" ... (remaining ")
            .append(numberRemaining)
            .append(numberRemaining == 1 ? " argument" : " arguments")
            .append(" skipped)");
        break;
      } else {
        if (numberRemaining < size) {
          message.append(prettyPrintArgs ? " \\\n    " : " ");
        }
        ScriptUtil.emitCommandElement(message, commandElement, isFirstArgument);
        numberRemaining--;
      }
      isFirstArgument = false;
    }

    if (form == CommandDescriptionForm.COMPLETE) {
      ScriptUtil.emitEndIsolate(message);
    }

    if (form == CommandDescriptionForm.COMPLETE) {

      if (configurationChecksum != null) {
        message.append("\n");
        message.append("# Configuration: ").append(configurationChecksum);
      }

      if (executionPlatformLabel != null) {
        message.append("\n");
        message.append("# Execution platform: ").append(executionPlatformLabel);
      }

      if (spawnRunner != null) {
        message.append("\n");
        message.append("# Runner: ").append(spawnRunner);
      }
    }

    return message.toString();
  }

  /**
   * Construct an error message that describes a failed command invocation. Currently this returns a
   * message of the form "foo failed: error executing FooCompile command /dir/foo bar baz".
   */
  @VisibleForTesting
  static String describeCommandFailure(
      boolean verbose,
      String mnemonic,
      Collection<String> commandLineElements,
      Map<String, String> env,
      @Nullable String cwd,
      @Nullable String configurationChecksum,
      @Nullable String targetDescription,
      @Nullable Label executionPlatformLabel,
      @Nullable String spawnRunner) {

    String commandName = commandLineElements.iterator().next();
    // Extract the part of the command name after the last "/", if any.
    String shortCommandName = new File(commandName).getName();

    CommandDescriptionForm form = verbose
        ? CommandDescriptionForm.COMPLETE
        : CommandDescriptionForm.ABBREVIATED;

    StringBuilder output = new StringBuilder();
    output.append("error executing ");
    output.append(mnemonic);
    output.append(" command ");
    if (targetDescription != null) {
      output.append("(from ").append(targetDescription).append(") ");
    }
    if (verbose) {
      output.append("\n  ");
    }
    output.append(
        describeCommand(
            form,
            /* prettyPrintArgs= */ false,
            commandLineElements,
            env,
            null,
            cwd,
            configurationChecksum,
            executionPlatformLabel,
            spawnRunner));
    return shortCommandName + " failed: " + output;
  }

  public static String describeCommandFailure(
      boolean verboseFailures, @Nullable String cwd, DescribableExecutionUnit command) {
    return describeCommandFailure(
        verboseFailures,
        command.getMnemonic(),
        command.getArguments(),
        command.getEnvironment(),
        cwd,
        command.getConfigurationChecksum(),
        command.getTargetDescription(),
        command.getExecutionPlatformLabel(),
        /* spawnRunner= */ null);
  }
}
