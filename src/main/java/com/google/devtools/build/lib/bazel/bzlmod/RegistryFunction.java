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

import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Precomputed;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.annotation.Nullable;

/** A simple SkyFunction that creates a {@link Registry} with a given URL. */
public class RegistryFunction implements SkyFunction {
  /**
   * Set to the current time in {@link com.google.devtools.build.lib.bazel.BazelRepositoryModule}
   * after {@link #INVALIDATION_INTERVAL} has passed. This is used to refresh the mutable registry
   * contents cached in memory from time to time.
   */
  public static final Precomputed<Instant> LAST_INVALIDATION =
      new Precomputed<>("last_registry_invalidation");

  /**
   * The interval after which the mutable registry contents cached in memory should be refreshed.
   */
  public static final Duration INVALIDATION_INTERVAL = Duration.ofHours(1);

  private final RegistryFactory registryFactory;
  private final Path workspaceRoot;

  public RegistryFunction(RegistryFactory registryFactory, Path workspaceRoot) {
    this.registryFactory = registryFactory;
    this.workspaceRoot = workspaceRoot;
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, RegistryException {
    LockfileMode lockfileMode = BazelLockFileFunction.LOCKFILE_MODE.get(env);
    Optional<Path> vendorDir = RepositoryDelegatorFunction.VENDOR_DIRECTORY.get(env);

    if (lockfileMode == LockfileMode.REFRESH) {
      RegistryFunction.LAST_INVALIDATION.get(env);
    }

    BazelLockFileValue lockfile = (BazelLockFileValue) env.getValue(BazelLockFileValue.KEY);
    if (lockfile == null) {
      return null;
    }

    RegistryKey key = (RegistryKey) skyKey.argument();
    try {
      return registryFactory.createRegistry(
          key.url().replace("%workspace%", workspaceRoot.getPathString()),
          lockfileMode,
          lockfile.getRegistryFileHashes(),
          lockfile.getSelectedYankedVersions(),
          vendorDir);
    } catch (URISyntaxException e) {
      throw new RegistryException(
          ExternalDepsException.withCauseAndMessage(
              FailureDetails.ExternalDeps.Code.INVALID_REGISTRY_URL,
              e,
              "Invalid registry URL: %s",
              key.url()));
    }
  }

  static final class RegistryException extends SkyFunctionException {

    RegistryException(ExternalDepsException cause) {
      super(cause, Transience.TRANSIENT);
    }
  }
}
