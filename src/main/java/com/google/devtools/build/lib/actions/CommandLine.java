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

package com.google.devtools.build.lib.actions;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.util.Fingerprint;
import javax.annotation.Nullable;

/** A representation of a list of arguments. */
public abstract class CommandLine {

  public static CommandLine empty() {
    return EmptyCommandLine.INSTANCE;
  }

  /** Returns a {@link CommandLine} backed by the given list of arguments. */
  public static CommandLine of(ImmutableList<String> arguments) {
    return arguments.isEmpty() ? empty() : new SimpleCommandLine(arguments);
  }

  /**
   * Returns a {@link CommandLine} that is constructed by appending the {@code args} to {@code
   * commandLine}.
   */
  public static CommandLine concat(CommandLine commandLine, ImmutableList<String> args) {
    if (args.isEmpty()) {
      return commandLine;
    }
    if (commandLine == EmptyCommandLine.INSTANCE) {
      return CommandLine.of(args);
    }
    return new SuffixedCommandLine(args, commandLine);
  }

  /** Implementation of {@link ArgChunk} that delegates to an {@link Iterable}. */
  public static final class SimpleArgChunk implements ArgChunk {
    private final Iterable<String> args;

    public SimpleArgChunk(Iterable<String> args) {
      this.args = args;
    }

    @Override
    public Iterable<String> arguments() {
      return args;
    }

    @Override
    public int totalArgLength() {
      int total = 0;
      for (String arg : args) {
        total += arg.length() + 1;
      }
      return total;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("args", args).toString();
    }
  }

  /** Returns the expanded command line. */
  public abstract ArgChunk expand() throws CommandLineExpansionException, InterruptedException;

  /**
   * Returns the expanded command line with enclosed artifacts expanded by {@code artifactExpander}
   * at execution time.
   */
  public abstract ArgChunk expand(ArtifactExpander artifactExpander, PathMapper pathMapper)
      throws CommandLineExpansionException, InterruptedException;

  /** Identical to calling {@code expand().arguments()}. */
  public abstract Iterable<String> arguments()
      throws CommandLineExpansionException, InterruptedException;

  /** Identical to calling {@code expand(artifactExpander, pathMapper).arguments()}. */
  public abstract Iterable<String> arguments(
      ArtifactExpander artifactExpander, PathMapper pathMapper)
      throws CommandLineExpansionException, InterruptedException;

  /** Adds this command line to the provided {@link Fingerprint}. */
  public abstract void addToFingerprint(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      CoreOptions.OutputPathsMode effectiveOutputPathsMode,
      Fingerprint fingerprint)
      throws CommandLineExpansionException, InterruptedException;

  private static final class EmptyCommandLine extends AbstractCommandLine {
    private static final EmptyCommandLine INSTANCE = new EmptyCommandLine();

    @Override
    public ImmutableList<String> arguments() {
      return ImmutableList.of();
    }
  }

  private static final class SimpleCommandLine extends AbstractCommandLine {
    private final ImmutableList<String> args;

    SimpleCommandLine(ImmutableList<String> args) {
      this.args = args;
    }

    @Override
    public ImmutableList<String> arguments() {
      return args;
    }
  }

  private static final class SuffixedCommandLine extends AbstractCommandLine {
    private final ImmutableList<String> executableArgs;
    private final CommandLine commandLine;

    SuffixedCommandLine(ImmutableList<String> executableArgs, CommandLine commandLine) {
      this.executableArgs = executableArgs;
      this.commandLine = commandLine;
    }

    @Override
    public Iterable<String> arguments() throws CommandLineExpansionException, InterruptedException {
      return Iterables.concat(commandLine.arguments(), executableArgs);
    }

    @Override
    public Iterable<String> arguments(ArtifactExpander artifactExpander, PathMapper pathMapper)
        throws CommandLineExpansionException, InterruptedException {
      return Iterables.concat(commandLine.arguments(artifactExpander, pathMapper), executableArgs);
    }
  }

  /**
   * This helps when debugging Blaze code that uses {@link CommandLine}s, as you can see their
   * content directly in the variable inspector.
   */
  @Override
  public final String toString() {
    try {
      return Joiner.on(' ').join(arguments());
    } catch (CommandLineExpansionException e) {
      return "Error in expanding command line";
    } catch (InterruptedException unused) {
      Thread.currentThread().interrupt();
      return "Interrupted while expanding command line";
    }
  }
}
