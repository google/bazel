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

import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.util.Fingerprint;
import javax.annotation.Nullable;

/**
 * Partial implementation of a {@link CommandLine} suitable for when expansion eagerly materializes
 * strings.
 */
public abstract class AbstractCommandLine extends CommandLine {

  @Override
  public final ArgChunk expand() throws CommandLineExpansionException, InterruptedException {
    return new SimpleArgChunk(arguments());
  }

  @Override
  public final ArgChunk expand(ArtifactExpander artifactExpander, PathMapper pathMapper)
      throws CommandLineExpansionException, InterruptedException {
    return new SimpleArgChunk(arguments(artifactExpander, pathMapper));
  }

  /**
   * Returns the expanded command line with enclosed artifacts expanded by {@code artifactExpander}
   * at execution time.
   *
   * <p>By default, this method just delegates to {@link #arguments()}, without performing any
   * artifact expansion. Subclasses should override this method if they contain tree artifacts and
   * need to expand them for proper argument evaluation.
   */
  @Override
  public Iterable<String> arguments(ArtifactExpander artifactExpander, PathMapper pathMapper)
      throws CommandLineExpansionException, InterruptedException {
    return arguments();
  }

  @Override
  public void addToFingerprint(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      CoreOptions.OutputPathsMode effectiveOutputPathsMode,
      Fingerprint fingerprint)
      throws CommandLineExpansionException, InterruptedException {
    for (String s :
        arguments(
            /* artifactExpander= */ null, PathMapper.forActionKey(effectiveOutputPathsMode))) {
      fingerprint.addString(s);
    }
  }
}
