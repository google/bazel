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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;
import java.util.Optional;

/**
 * This object serves as a container for the transitive digest (obtained from transitive .bzl files)
 * and the generated repositories from evaluating a module extension. Its purpose is to store this
 * information within the lockfile.
 */
@AutoValue
@GenerateTypeAdapter
public abstract class LockFileModuleExtension implements Postable {

  public static Builder builder() {
    return new AutoValue_LockFileModuleExtension.Builder()
        .setModuleExtensionMetadata(Optional.empty())
        .setRecordedRepoMappingEntries(ImmutableTable.of());
  }

  @SuppressWarnings("mutable")
  public abstract byte[] getBzlTransitiveDigest();

  @SuppressWarnings("mutable")
  public abstract byte[] getUsagesDigest();

  public abstract ImmutableMap<RepoRecordedInput.File, String> getRecordedFileInputs();

  public abstract ImmutableMap<RepoRecordedInput.Dirents, String> getRecordedDirentsInputs();

  public abstract ImmutableMap<RepoRecordedInput.EnvVar, Optional<String>> getEnvVariables();

  public abstract ImmutableMap<String, RepoSpec> getGeneratedRepoSpecs();

  public abstract Optional<ModuleExtensionMetadata> getModuleExtensionMetadata();

  public abstract ImmutableTable<RepositoryName, String, RepositoryName>
      getRecordedRepoMappingEntries();

  public boolean isReproducible() {
    return getModuleExtensionMetadata().map(ModuleExtensionMetadata::getReproducible).orElse(false);
  }

  /** Builder type for {@link LockFileModuleExtension}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setBzlTransitiveDigest(byte[] digest);

    public abstract Builder setUsagesDigest(byte[] digest);

    public abstract Builder setRecordedFileInputs(
        ImmutableMap<RepoRecordedInput.File, String> value);

    public abstract Builder setRecordedDirentsInputs(
        ImmutableMap<RepoRecordedInput.Dirents, String> value);

    public abstract Builder setEnvVariables(
        ImmutableMap<RepoRecordedInput.EnvVar, Optional<String>> value);

    public abstract Builder setGeneratedRepoSpecs(ImmutableMap<String, RepoSpec> value);

    public abstract Builder setModuleExtensionMetadata(Optional<ModuleExtensionMetadata> value);

    public abstract Builder setRecordedRepoMappingEntries(
        ImmutableTable<RepositoryName, String, RepositoryName> value);

    public abstract LockFileModuleExtension build();
  }

  /**
   * A {@link LockFileModuleExtension} together with its {@link ModuleExtensionEvalFactors},
   * comprising a single lockfile entry for a certain extension.
   */
  @AutoCodec
  public record WithFactors(
      ModuleExtensionEvalFactors extensionFactors, LockFileModuleExtension moduleExtension) {}
}
