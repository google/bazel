// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime.commands;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.ProjectFileSupport;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

/** Provides support for reading target patterns from a file or the command-line. */
public final class TargetPatternsHelper {

  private static final Splitter TARGET_PATTERN_SPLITTER = Splitter.on('#');

  private TargetPatternsHelper() {}

  /**
   * Reads a list of target patterns, either from the command-line residue or by reading newline
   * delimited target patterns from the --target_pattern_file flag. If --target_pattern_file is
   * specified and options contain a residue, or if the file cannot be read, throws {@link
   * TargetPatternsHelperException}.
   */
  public static List<String> readFrom(CommandEnvironment env, OptionsParsingResult options)
      throws TargetPatternsHelperException {
    List<String> targets = options.getResidue();
    BuildRequestOptions buildRequestOptions = options.getOptions(BuildRequestOptions.class);
    if (!targets.isEmpty() && !buildRequestOptions.targetPatternFile.isEmpty()) {
      throw new TargetPatternsHelperException(
          "Command-line target pattern and --target_pattern_file cannot both be specified",
          TargetPatterns.Code.TARGET_PATTERN_FILE_WITH_COMMAND_LINE_PATTERN);
    } else if (!buildRequestOptions.targetPatternFile.isEmpty()) {
      // Works for absolute or relative file.
      Path residuePath =
          env.getWorkingDirectory().getRelative(buildRequestOptions.targetPatternFile);
      try {
        targets =
            FileSystemUtils.readLines(residuePath, UTF_8).stream()
                .map(s -> TARGET_PATTERN_SPLITTER.splitToList(s).get(0))
                .map(String::trim)
                .filter(Predicate.not(String::isEmpty))
                .collect(toImmutableList());
      } catch (IOException e) {
        throw new TargetPatternsHelperException(
            "I/O error reading from " + residuePath.getPathString() + ": " + e.getMessage(),
            TargetPatterns.Code.TARGET_PATTERN_FILE_READ_FAILURE);
      }
    } else {
      try (SilentCloseable closeable =
          Profiler.instance().profile("ProjectFileSupport.getTargets")) {
        targets = ProjectFileSupport.getTargets(env.getRuntime().getProjectFileProvider(), options);
      }
    }
    return targets;
  }

  /** Thrown when target patterns couldn't be read. */
  public static class TargetPatternsHelperException extends Exception {
    private final TargetPatterns.Code detailedCode;

    private TargetPatternsHelperException(String message, TargetPatterns.Code detailedCode) {
      super(Preconditions.checkNotNull(message));
      this.detailedCode = detailedCode;
    }

    public FailureDetail getFailureDetail() {
      return FailureDetail.newBuilder()
          .setMessage(getMessage())
          .setTargetPatterns(TargetPatterns.newBuilder().setCode(detailedCode))
          .build();
    }
  }
}
