// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Calculates the hash based on the files, which should be unchanged on disk for a worker to get
 * reused.
 */
public class WorkerFilesHash {

  private WorkerFilesHash() {}

  public static HashCode getCombinedHash(SortedMap<PathFragment, byte[]> workerFilesMap) {
    Hasher hasher = Hashing.sha256().newHasher();
    workerFilesMap.forEach(
        (execPath, digest) -> {
          String execPathString = execPath.getPathString();
          hasher.putByte(StringUnsafe.getInstance().getCoder(execPathString));
          hasher.putInt(execPathString.length());
          hasher.putBytes(StringUnsafe.getInstance().getByteArray(execPathString));

          hasher.putInt(digest.length);
          hasher.putBytes(digest);
        });
    return hasher.hash();
  }

  /**
   * Return a map that contains the execroot relative path and hash of each tool and runfiles
   * artifact of the given spawn.
   *
   * @throws MissingInputException if metadata is missing for any of the worker files.
   */
  public static SortedMap<PathFragment, byte[]> getWorkerFilesWithDigests(
      Spawn spawn, ArtifactExpander artifactExpander, InputMetadataProvider actionInputFileCache)
      throws IOException {
    TreeMap<PathFragment, byte[]> workerFilesMap = new TreeMap<>();

    List<ActionInput> tools =
        ActionInputHelper.expandArtifacts(
            spawn.getToolFiles(),
            artifactExpander,
            /* keepEmptyTreeArtifacts= */ false,
            /* keepRunfilesTreeArtifacts= */ true);
    for (ActionInput tool : tools) {
      if (tool instanceof Artifact artifact && artifact.isRunfilesTree()) {
        RunfilesTree runfilesTree =
            actionInputFileCache.getRunfilesMetadata(tool).getRunfilesTree();
        PathFragment root = runfilesTree.getExecPath();
        Preconditions.checkState(!root.isAbsolute(), root);
        for (Map.Entry<PathFragment, Artifact> mapping : runfilesTree.getMapping().entrySet()) {
          Artifact localArtifact = mapping.getValue();
          if (localArtifact != null) {
            @Nullable
            FileArtifactValue metadata = actionInputFileCache.getInputMetadata(localArtifact);
            if (metadata == null) {
              throw new MissingInputException(localArtifact);
            }
            if (metadata.getType().isFile()) {
              workerFilesMap.put(
                  spawn.getPathMapper().map(root.getRelative(mapping.getKey())),
                  metadata.getDigest());
            }
          }
        }

        continue;
      }

      @Nullable FileArtifactValue metadata = actionInputFileCache.getInputMetadata(tool);
      if (metadata == null) {
        throw new MissingInputException(tool);
      }
      workerFilesMap.put(
          spawn.getPathMapper().map(tool.getExecPath()),
          actionInputFileCache.getInputMetadata(tool).getDigest());
    }

    return workerFilesMap;
  }

  /** Exception thrown when the metadata for a tool/runfile is missing. */
  public static final class MissingInputException extends RuntimeException {
    private MissingInputException(ActionInput input) {
      super(String.format("Missing input metadata for: '%s'", input.getExecPathString()));
    }
  }
}
