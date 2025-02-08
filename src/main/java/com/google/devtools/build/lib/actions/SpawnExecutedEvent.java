// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.util.io.FileOutErr;
import java.time.Instant;
import javax.annotation.Nullable;

/** This event is fired during the build, when a subprocess is executed. */
public final class SpawnExecutedEvent implements ExtendedEventHandler.Postable {
  private final Spawn spawn;
  private final InputMetadataProvider inputMetadataProvider;
  private final FileOutErr fileOutErr;
  private final SpawnResult result;
  private final Instant startTimeInstant;
  @Nullable private final String spawnIdentifier;

  public SpawnExecutedEvent(
      Spawn spawn,
      InputMetadataProvider inputMetadataProvider,
      FileOutErr fileOutErr,
      SpawnResult result,
      Instant startTimeInstant,
      @Nullable String spawnIdentifier) {
    this.spawn = Preconditions.checkNotNull(spawn);
    this.inputMetadataProvider = inputMetadataProvider;
    this.fileOutErr = fileOutErr;
    this.result = Preconditions.checkNotNull(result);
    this.startTimeInstant = startTimeInstant;
    this.spawnIdentifier = spawnIdentifier;
  }

  /** Returns the Spawn. */
  public Spawn getSpawn() {
    return spawn;
  }

  /** Returns the input metadata provider containing information about the inputs of the Spawn. */
  public InputMetadataProvider getInputMetadataProvider() {
    return inputMetadataProvider;
  }

  /** Returns the action. */
  public ActionAnalysisMetadata getActionMetadata() {
    return spawn.getResourceOwner();
  }

  /** Returns the action exit code. */
  public int getExitCode() {
    return result.exitCode();
  }

  /** Returns the distributor reply. */
  public SpawnResult getSpawnResult() {
    return result;
  }

  /** Returns the instant in time when the spawn starts. */
  public Instant getStartTimeInstant() {
    return startTimeInstant;
  }

  /** Returns the id used by the spawn runner to uniquely identify the spawn. */
  @Nullable
  public String getSpawnIdentifier() {
    return spawnIdentifier;
  }

  /** Returns the FileOutErr used by the Spawn. */
  public FileOutErr getFileOutErr() {
    return fileOutErr;
  }

  /**
   * This event is fired to differentiate actions with multiple spawns that are run sequentially
   * versus parallel. An example of a use case of why this would be important is if we have flaky
   * tests. We want to tell the {@link
   * com.google.devtools.build.lib.metrics.criticalpath.CriticalPathComponent} that all the failed
   * test spawns should have their Duration metrics aggregated so the test runtime matches the
   * runtime of the entire CriticalPathComponent.
   */
  public static final class ChangePhase implements ExtendedEventHandler.Postable {
    private final ActionAnalysisMetadata action;

    public ChangePhase(ActionAnalysisMetadata action) {
      this.action = action;
    }

    public ActionAnalysisMetadata getAction() {
      return this.action;
    }
  }
}
