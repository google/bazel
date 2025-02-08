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

import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * A simple SkyFunction that computes a {@link RepoSpec} for the given {@link InterimModule} by
 * fetching required information from its {@link Registry}.
 */
public class RepoSpecFunction implements SkyFunction {
  @Nullable private DownloadManager downloadManager;

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, RepoSpecException {
    RepoSpecKey key = (RepoSpecKey) skyKey.argument();

    Registry registry = (Registry) env.getValue(RegistryKey.create(key.registryUrl()));
    if (registry == null) {
      return null;
    }

    StoredEventHandler downloadEvents = new StoredEventHandler();
    RepoSpec repoSpec;
    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.BZLMOD, () -> "compute repo spec: " + key.moduleKey())) {
      repoSpec = registry.getRepoSpec(key.moduleKey(), downloadEvents, this.downloadManager);
    } catch (IOException e) {
      throw new RepoSpecException(
          ExternalDepsException.withCauseAndMessage(
              FailureDetails.ExternalDeps.Code.ERROR_ACCESSING_REGISTRY,
              e,
              "Unable to get module repo spec for %s from registry",
              key.moduleKey()));
    }
    downloadEvents.replayOn(env.getListener());
    return RepoSpecValue.create(
        repoSpec, RegistryFileDownloadEvent.collectToMap(downloadEvents.getPosts()));
  }

  public void setDownloadManager(DownloadManager downloadManager) {
    this.downloadManager = downloadManager;
  }

  static final class RepoSpecException extends SkyFunctionException {

    RepoSpecException(ExternalDepsException cause) {
      super(cause, Transience.TRANSIENT);
    }
  }
}
