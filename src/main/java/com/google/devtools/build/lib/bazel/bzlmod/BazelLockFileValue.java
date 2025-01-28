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
 * The result of reading the lockfile. Contains the lockfile version, module hash, definitions of
 * module repositories, post-resolution dependency graph and module extensions data (ID, hash,
 * definition, usages)
 */
@AutoValue
@GenerateTypeAdapter
public abstract class BazelLockFileValue implements SkyValue {

  // NOTE: See "HACK" note in BazelLockFileModule. While this hack exists, normal increments of the
  // lockfile version need to be done by 2 at a time (i.e. keep LOCK_FILE_VERSION an odd number).
  public static final int LOCK_FILE_VERSION = 18;

  @SerializationConstant public static final SkyKey KEY = () -> SkyFunctions.BAZEL_LOCK_FILE;

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
