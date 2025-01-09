// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.skyframe.NotComparableSkyValue;
import java.io.IOException;
import java.util.Optional;

/** A database where module metadata is stored. */
public interface Registry extends NotComparableSkyValue {

  /** The URL that uniquely identifies the registry. */
  String getUrl();

  /** Thrown when a file is not found in the registry. */
  final class NotFoundException extends Exception {
    public NotFoundException(String message) {
      super(message);
    }
  }

  /**
   * Retrieves the contents of the module file of the module identified by {@code key} from the
   * registry.
   *
   * @throws NotFoundException if the module file is not found in the registry
   */
  ModuleFile getModuleFile(
      ModuleKey key, ExtendedEventHandler eventHandler, DownloadManager downloadManager)
      throws IOException, InterruptedException, NotFoundException;

  /**
   * Retrieves the {@link RepoSpec} object that indicates how the contents of the module identified
   * by {@code key} should be materialized as a repo.
   */
  RepoSpec getRepoSpec(
      ModuleKey key, ExtendedEventHandler eventHandler, DownloadManager downloadManager)
      throws IOException, InterruptedException;

  /**
   * Retrieves yanked versions of the module identified by {@code key.getName()} from the registry.
   * Returns {@code Optional.empty()} when the information is not found in the registry.
   */
  Optional<ImmutableMap<Version, String>> getYankedVersions(
      String moduleName, ExtendedEventHandler eventHandler, DownloadManager downloadManager)
      throws IOException, InterruptedException;

  /**
   * Returns the yanked versions information, limited to the given selected module version, purely
   * based on the lockfile (if possible).
   */
  Optional<YankedVersionsValue> tryGetYankedVersionsFromLockfile(ModuleKey selectedModuleKey);
}
