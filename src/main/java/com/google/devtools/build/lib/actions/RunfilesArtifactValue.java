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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.FileArtifactValue.ConstantMetadataValue;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.skyframe.SkyValue;

/** The artifacts behind a runfiles tree. */
public final class RunfilesArtifactValue implements SkyValue {

  /** A callback for consuming artifacts in a runfiles tree. */
  @FunctionalInterface
  public interface RunfilesConsumer<T> {
    void accept(Artifact artifact, T metadata) throws InterruptedException;
  }

  private final FileArtifactValue metadata;
  private final RunfilesTree runfilesTree;

  // Parallel lists.
  private final ImmutableList<Artifact> files;
  private final ImmutableList<FileArtifactValue> fileValues;

  // Parallel lists.
  private final ImmutableList<Artifact> trees;
  private final ImmutableList<TreeArtifactValue> treeValues;

  public RunfilesArtifactValue(
      RunfilesTree runfilesTree,
      ImmutableList<Artifact> files,
      ImmutableList<FileArtifactValue> fileValues,
      ImmutableList<Artifact> trees,
      ImmutableList<TreeArtifactValue> treeValues) {
    this.runfilesTree = checkNotNull(runfilesTree);
    this.files = checkNotNull(files);
    this.fileValues = checkNotNull(fileValues);
    this.trees = checkNotNull(trees);
    this.treeValues = checkNotNull(treeValues);
    checkArgument(
        files.size() == fileValues.size() && trees.size() == treeValues.size(),
        "Size mismatch: %s",
        this);

    // Compute the digest of this runfiles tree by combining its layout and the digests of every
    // artifact it references.
    this.metadata = FileArtifactValue.createProxy(computeDigest());
  }

  private byte[] computeDigest() {
    Fingerprint result = new Fingerprint();

    result.addInt(runfilesTree.getMapping().size());
    for (var entry : runfilesTree.getMapping().entrySet()) {
      result.addPath(entry.getKey());
      result.addBoolean(entry.getValue() != null);
      if (entry.getValue() != null) {
        result.addPath(entry.getValue().getExecPath());
      }
    }

    result.addInt(files.size());
    for (int i = 0; i < files.size(); i++) {
      FileArtifactValue value =
          files.get(i).isConstantMetadata() ? ConstantMetadataValue.INSTANCE : fileValues.get(i);
      value.addTo(result);
    }

    result.addInt(trees.size());
    for (int i = 0; i < trees.size(); i++) {
      result.addBytes(treeValues.get(i).getDigest());
    }

    return result.digestAndReset();
  }

  public RunfilesArtifactValue withOverriddenRunfilesTree(RunfilesTree overrideTree) {
    return new RunfilesArtifactValue(overrideTree, files, fileValues, trees, treeValues);
  }

  /** Returns the data of the artifact for this value, as computed by the action cache checker. */
  public FileArtifactValue getMetadata() {
    return metadata;
  }

  /** Returns the runfiles tree this value represents. */
  public RunfilesTree getRunfilesTree() {
    return runfilesTree;
  }

  /** Visits the file artifacts that this runfiles artifact expands to, together with their data. */
  public void forEachFile(RunfilesConsumer<FileArtifactValue> consumer)
      throws InterruptedException {
    for (int i = 0; i < files.size(); i++) {
      consumer.accept(files.get(i), fileValues.get(i));
    }
  }

  /** Visits the tree artifacts that this runfiles artifact expands to, together with their data. */
  public void forEachTree(RunfilesConsumer<TreeArtifactValue> consumer)
      throws InterruptedException {
    for (int i = 0; i < trees.size(); i++) {
      consumer.accept(trees.get(i), treeValues.get(i));
    }
  }

  @Override
  public boolean equals(Object o) {
    // This method, seemingly erroneously, does not check whether the runfilesTree of the two
    // objects are equivalent. This is because it's unnecessary because the layout of the runfiles
    // tree is already factored into the equality decision in two ways:
    // - Through "metadata", which takes the layout into account (see computeDigest())
    // - Through the runfiles input manifest file, which is part of the runfiles tree, which
    //   contains the exact mapping and whose digest is in "fileValues"
    if (this == o) {
      return true;
    }
    if (!(o instanceof RunfilesArtifactValue that)) {
      return false;
    }
    return metadata.equals(that.metadata)
        && files.equals(that.files)
        && fileValues.equals(that.fileValues)
        && trees.equals(that.trees)
        && treeValues.equals(that.treeValues);
  }

  @Override
  public int hashCode() {
    return HashCodes.hashObjects(metadata, files, fileValues, trees, treeValues);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("metadata", metadata)
        .add("files", files)
        .add("fileValues", fileValues)
        .add("trees", trees)
        .add("treeValues", treeValues)
        .toString();
  }
}
