// Copyright 2024 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.vfs;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.actions.cache.OutputMetadataStore;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * A minimal local-only {@link OutputService}.
 *
 * <p>This is used by default when no {@link com.google.devtools.build.lib.runtime.BlazeModule}
 * {@linkplain com.google.devtools.build.lib.runtime.BlazeModule#getOutputService provides} an
 * {@link OutputService}.
 */
public final class LocalOutputService implements OutputService {
  private final BlazeDirectories directories;

  public LocalOutputService(BlazeDirectories directories) {
    this.directories = checkNotNull(directories);
  }

  @Override
  public String getFileSystemName(String outputBaseFileSystemName) {
    return outputBaseFileSystemName;
  }

  @Override
  public boolean isLocalOnly() {
    return true;
  }

  @Override
  public ModifiedFileSet startBuild(
      UUID buildId, String workspaceName, EventHandler eventHandler, boolean finalizeActions)
      throws AbruptExitException {
    Path outputPath = directories.getOutputPath(workspaceName);
    Path localOutputPath = directories.getLocalOutputPath();

    if (outputPath.isSymbolicLink()) {
      try {
        // Remove the existing symlink first.
        outputPath.delete();
        if (localOutputPath.exists()) {
          // Pre-existing local output directory. Move to outputPath.
          localOutputPath.renameTo(outputPath);
        }
      } catch (IOException e) {
        throw new AbruptExitException(
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(
                        "Couldn't handle local output directory symlinks: " + e.getMessage())
                    .setExecution(
                        Execution.newBuilder().setCode(Code.LOCAL_OUTPUT_DIRECTORY_SYMLINK_FAILURE))
                    .build()),
            e);
      }
    }
    return ModifiedFileSet.EVERYTHING_MODIFIED;
  }

  @Override
  public void finalizeBuild(boolean buildSuccessful) {}

  @Override
  public void finalizeAction(Action action, OutputMetadataStore outputMetadataStore) {}

  @Nullable
  @Override
  public BatchStat getBatchStatter() {
    return null;
  }

  @Override
  public boolean canCreateSymlinkTree() {
    return false;
  }

  @Override
  public void createSymlinkTree(
      Map<PathFragment, PathFragment> symlinks, PathFragment symlinkTreeRoot) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clean() {}

  @Override
  public OutputChecker getOutputChecker() {
    return OutputChecker.TRUST_LOCAL_ONLY;
  }
}
