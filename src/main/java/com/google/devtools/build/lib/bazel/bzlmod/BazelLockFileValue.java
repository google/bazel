// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;
import java.util.Optional;

/**
 * The result of reading a lockfile. Contains the lockfile version as well as registry and module
 * extensions data (ID, usages hash, generated repos, ...).
 *
 * <p>Bazel maintains two separate lockfiles:
 *
 * <ul>
 *   <li>the (regular) lockfile stored as MODULE.bazel.lock under the workspace directory;
 *   <li>the persistent lockfile stored as MODULE.bazel.lock under the output base.
 * </ul>
 *
 * See the javadoc of the two {@link SkyKey}s for more information.
 */
@AutoValue
@GenerateTypeAdapter
public abstract class BazelLockFileValue implements SkyValue {

  // NOTE: See "HACK" note on 7.x:
  // https://cs.opensource.google/bazel/bazel/+/release-7.3.0:src/main/java/com/google/devtools/build/lib/bazel/bzlmod/BazelLockFileModule.java;l=120-127;drc=5f5355b75c7c93fba1e15f6658f308953f4baf51
  // While this hack exists on 7.x, lockfile version increments should be done 2 at a time (i.e.
  // keep this number even).
  public static final int LOCK_FILE_VERSION = 16;

  /**
   * The (regular) lockfile, stored as MODULE.bazel.lock under the workspace directory. This file is
   * visible to the user and meant to be committed to source control. Thus, it should
   *
   * <ul>
   *   <li>should only contain the minimal amount of information necessary to make module resolution
   *       and module extension evaluation deterministic;
   *   <li>should be as deterministic as possible to reduce the risk of merge conflicts.
   * </ul>
   */
  @SerializationConstant public static final SkyKey KEY = () -> SkyFunctions.BAZEL_LOCK_FILE;

  /**
   * The persistent lockfile, stored as MODULE.bazel.lock under the output base. This file is not
   * visible to the user and is only removed on a {@code bazel clean --expunge}, similar to the
   * persistent action cache. Thus, it
   *
   * <ul>
   *   <li>should only contain information that is known to be correct indefinitely and never needs
   *       to be invalidated for a correct build;
   *   <li>is not subject to the same space and mergeability constraints as the regular lockfile and
   *       can thus contain more extensive information;
   *   <li>may differ between users and checkouts of the same project as long as it doesn't affect
   *       the outcome of the build, with one exception: the build may fail with an error due to
   *       additional information in the persistent lockfile, e.g. if a module in a registry is
   *       changed retroactively and thus causes a mismatch with the hash in the persistent
   *       lockfile.
   * </ul>
   */
  @SerializationConstant
  public static final SkyKey PERSISTENT_KEY = () -> SkyFunctions.BAZEL_LOCK_FILE;

  static Builder builder() {
    return new AutoValue_BazelLockFileValue.Builder()
        .setLockFileVersion(LOCK_FILE_VERSION)
        .setRegistryFileHashes(ImmutableMap.of())
        .setSelectedYankedVersions(ImmutableMap.of())
        .setModuleExtensions(ImmutableMap.of());
  }

  /** Current version of the lock file */
  public abstract int getLockFileVersion();

  /** Hashes of files retrieved from registries. */
  public abstract ImmutableMap<String, Optional<Checksum>> getRegistryFileHashes();

  /**
   * Selected module versions that are known to be yanked (and hence must have been explicitly
   * allowed by the user).
   */
  public abstract ImmutableMap<ModuleKey, String> getSelectedYankedVersions();

  /** Mapping the extension id to the module extension data */
  public abstract ImmutableMap<
          ModuleExtensionId, ImmutableMap<ModuleExtensionEvalFactors, LockFileModuleExtension>>
      getModuleExtensions();

  public abstract Builder toBuilder();

  /** Builder type for {@link BazelLockFileValue}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setLockFileVersion(int value);

    public abstract Builder setRegistryFileHashes(ImmutableMap<String, Optional<Checksum>> value);

    public abstract Builder setSelectedYankedVersions(ImmutableMap<ModuleKey, String> value);

    public abstract Builder setModuleExtensions(
        ImmutableMap<
                ModuleExtensionId,
                ImmutableMap<ModuleExtensionEvalFactors, LockFileModuleExtension>>
            value);

    public abstract BazelLockFileValue build();
  }
}
