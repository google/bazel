
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

package com.google.devtools.build.lib.analysis.actions;


import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.Fingerprint;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Lazily writes the path of the given files separated by newline into a specified output file.
 *
 * <p>By default the exec path is written but this behaviour can be customized by providing an
 * alternative converter function.
 */
public final class LazyWritePathsFileAction extends AbstractFileWriteAction {
  private static final String GUID = "6be94d90-96f3-4bec-8104-1fb08abc2546";

  private final NestedSet<Artifact> files;
  private final ImmutableSet<Artifact> filesToIgnore;
  private final boolean includeDerivedArtifacts;
  private final Function<Artifact, String> converter;

  public LazyWritePathsFileAction(
      ActionOwner owner,
      Artifact output,
      NestedSet<Artifact> files,
      ImmutableSet<Artifact> filesToIgnore,
      boolean includeDerivedArtifacts) {
    this(owner, output, files, filesToIgnore, includeDerivedArtifacts, Artifact::getExecPathString);
  }

  public LazyWritePathsFileAction(
      ActionOwner owner,
      Artifact output,
      NestedSet<Artifact> files,
      ImmutableSet<Artifact> filesToIgnore,
      boolean includeDerivedArtifacts,
      Function<Artifact, String> converter) {
    // We don't need to pass the given files as explicit inputs to this action; we don't care about
    // them, we only need their names, which we already know.
    super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), output);
    this.files = files;
    this.includeDerivedArtifacts = includeDerivedArtifacts;
    this.filesToIgnore = filesToIgnore;
    this.converter = converter;
  }

  @Override
  public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
    return out -> out.write(StringUnsafe.getInstance().getInternalStringBytes(getContents()));
  }

  /** Computes the Action key for this action by computing the fingerprint for the file contents. */
  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp) {
    fp.addString(GUID);
    fp.addBoolean(includeDerivedArtifacts);
    fp.addString(getContents());
  }

  private String getContents() {
    StringBuilder stringBuilder = new StringBuilder();
    for (Artifact file : files.toList()) {
      if (filesToIgnore.contains(file)) {
        continue;
      }
      if (file.isSourceArtifact() || includeDerivedArtifacts) {
        stringBuilder.append(converter.apply(file));
        stringBuilder.append("\n");
      }
    }
    return stringBuilder.toString();
  }

  public NestedSet<Artifact> getFiles() {
    return files;
  }
}
