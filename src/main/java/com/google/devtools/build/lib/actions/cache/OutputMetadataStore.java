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
package com.google.devtools.build.lib.actions.cache;

import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import java.io.IOException;

/** Handles the metadata of the outputs of the action during its execution. */
public interface OutputMetadataStore {
  /**
   * Injects the metadata of a file.
   *
   * <p>This can be used to save filesystem operations when the metadata is already known.
   *
   * <p>{@linkplain Artifact#isTreeArtifact Tree artifacts} and their {@linkplain
   * Artifact#isChildOfDeclaredDirectory children} must not be passed here. Instead, they should be
   * passed to {@link #injectTree}.
   *
   * @param output a regular output file
   * @param metadata the file metadata
   */
  void injectFile(Artifact output, FileArtifactValue metadata);

  /**
   * Injects the metadata of a tree artifact.
   *
   * <p>This can be used to save filesystem operations when the metadata is already known.
   *
   * @param output an output directory {@linkplain Artifact#isTreeArtifact tree artifact}
   * @param tree a {@link TreeArtifactValue} with the metadata of the files stored in the directory
   */
  void injectTree(SpecialArtifact output, TreeArtifactValue tree);

  /**
   * Returns a {@link FileArtifactValue} for the given {@link ActionInput}.
   *
   * <p>If the metadata of the given {@link ActionInput} is not known, it's computed. This may
   * result in a significant amount of I/O.
   *
   * <p>The returned {@link FileArtifactValue} instance corresponds to the final target of a symlink
   * and therefore must not have a type of {@link FileStateType#SYMLINK}.
   *
   * <p>Freshly created output files (i.e. from an action that just executed) that require a stat to
   * obtain the metadata will first be set read-only and executable during this call. This ensures
   * that the returned metadata has an appropriate ctime, which is affected by chmod. Note that this
   * does not apply to outputs injected via {@link #injectFile} or {@link #injectTree} since a stat
   * is not required for them.
   *
   * @param output the output to retrieve the digest for
   * @return the artifact's digest or null the artifact is not a known output of the action
   * @throws IOException if the action input cannot be digested
   */
  FileArtifactValue getOutputMetadata(ActionInput output) throws IOException, InterruptedException;

  /** Retrieves the metadata for this tree artifact. Data should already be available. */
  TreeArtifactValue getTreeArtifactValue(SpecialArtifact treeArtifact)
      throws IOException, InterruptedException;

  /**
   * Marks an {@link Artifact} as intentionally omitted.
   *
   * <p>This is used as an optimization to not download <em>orphaned</em> artifacts (artifacts that
   * no action depends on) from a remote system.
   */
  void markOmitted(Artifact output);

  /** Returns {@code true} if {@link #markOmitted} was called on the artifact. */
  boolean artifactOmitted(Artifact artifact);

  /**
   * Discards any cached metadata for the given outputs.
   *
   * <p>May be called if an action can make multiple attempts that are expected to create the same
   * set of output files.
   */
  void resetOutputs(Iterable<? extends Artifact> outputs);
}
