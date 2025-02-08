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
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A simple SkyFunction that fetches the yanked versions for a given module from its {@link
 * Registry}.
 */
public class YankedVersionsFunction implements SkyFunction {
  @Nullable private DownloadManager downloadManager;

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
    var key = (YankedVersionsValue.Key) skyKey.argument();

    Registry registry = (Registry) env.getValue(RegistryKey.create(key.registryUrl()));
    if (registry == null) {
      return null;
    }

    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.BZLMOD, () -> "getting yanked versions: " + key.moduleName())) {
      return YankedVersionsValue.create(
          registry.getYankedVersions(key.moduleName(), env.getListener(), downloadManager));
    } catch (IOException e) {
      env.getListener()
          .handle(
              Event.warn(
                  String.format(
                      "Could not read metadata file for module %s from registry %s: %s",
                      key.moduleName(), key.registryUrl(), e.getMessage())));
      // This is failing open: If we can't read the metadata file, we allow yanked modules to be
      // fetched.
      return YankedVersionsValue.create(Optional.empty());
    }
  }

  public void setDownloadManager(DownloadManager downloadManager) {
    this.downloadManager = downloadManager;
  }
}
