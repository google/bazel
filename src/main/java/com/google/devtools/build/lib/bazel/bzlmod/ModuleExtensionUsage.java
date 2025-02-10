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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;
import java.util.Optional;
import net.starlark.java.syntax.Location;

/**
 * Represents the usage of a module extension in one module. This class records all the information
 * pertinent to all the proxy objects returned from any {@code use_extension} calls in this module
 * that refer to the same extension (or isolate, when applicable).
 *
 * <p>When adding new fields, make sure to update {@link #trimForEvaluation()} as well.
 */
@AutoValue
@GenerateTypeAdapter
public abstract class ModuleExtensionUsage {
  /** An unresolved label pointing to the Starlark file where the module extension is defined. */
  public abstract String getExtensionBzlFile();

  /** The name of the extension. */
  public abstract String getExtensionName();

  /**
   * The isolation key of this module extension usage. This is present if and only if the usage is
   * created with {@code isolate = True}.
   */
  public abstract Optional<ModuleExtensionId.IsolationKey> getIsolationKey();

  /** Represents one "proxy object" returned from one {@code use_extension} call. */
  @AutoValue
  @GenerateTypeAdapter
  public abstract static class Proxy {
    /** The location of the {@code use_extension} call. */
    public abstract Location getLocation();

    /**
     * The name of the proxy object; as in, the name that the return value of {@code use_extension}
     * is bound to. Is the empty string if the return value is not bound to any name (e.g. {@code
     * use_repo(use_extension(...))}).
     */
    public abstract String getProxyName();

    /**
     * The path to the MODULE.bazel file (or one of its includes) that contains this proxy object.
     * This path should be relative to the workspace root.
     */
    public abstract PathFragment getContainingModuleFilePath();

    /** Whether {@code dev_dependency} is set to true. */
    public abstract boolean isDevDependency();

    /**
     * All the repos imported, through this proxy, from this module extension into the scope of the
     * current module. The key is the local repo name (in the scope of the current module), and the
     * value is the name exported by the module extension.
     */
    public abstract ImmutableBiMap<String, String> getImports();

    public static Builder builder() {
      return new AutoValue_ModuleExtensionUsage_Proxy.Builder().setProxyName("");
    }

    /** Builder for {@link ModuleExtensionUsage.Proxy}. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setLocation(Location value);

      public abstract String getProxyName();

      public abstract Builder setProxyName(String value);

      public abstract Builder setContainingModuleFilePath(PathFragment value);

      public abstract boolean isDevDependency();

      public abstract Builder setDevDependency(boolean value);

      abstract ImmutableBiMap.Builder<String, String> importsBuilder();

      @CanIgnoreReturnValue
      public final Builder addImport(String key, String value) {
        importsBuilder().put(key, value);
        return this;
      }

      public abstract Builder setImports(ImmutableBiMap<String, String> value);

      public abstract Proxy build();
    }
  }

  /** The list of proxy objects that constitute */
  public abstract ImmutableList<Proxy> getProxies();

  /** All the tags specified by this module for this extension. */
  public abstract ImmutableList<Tag> getTags();

  /**
   * Whether any {@code use_extension} calls for this usage had {@code dev_dependency = True} set.
   */
  public final boolean getHasDevUseExtension() {
    return getProxies().stream().anyMatch(p -> p.isDevDependency());
  }

  /**
   * Whether any {@code use_extension} calls for this usage had {@code dev_dependency = False} set.
   */
  public final boolean getHasNonDevUseExtension() {
    return getProxies().stream().anyMatch(p -> !p.isDevDependency());
  }

  /**
   * Represents a repo that overrides another repo within the scope of the extension.
   *
   * @param overridingRepoName The apparent name of the overriding repo in the root module.
   * @param mustExist Whether this override should apply to an existing repo.
   * @param location The location of the {@code override_repo} or {@code inject_repo} call.
   */
  @AutoCodec
  @GenerateTypeAdapter
  public record RepoOverride(String overridingRepoName, boolean mustExist, Location location) {}

  /**
   * Contains information about overrides that apply to repos generated by this extension. Keyed by
   * the extension-local repo name.
   *
   * <p>This is only non-empty for root module usages.
   */
  public abstract ImmutableMap<String, RepoOverride> getRepoOverrides();

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_ModuleExtensionUsage.Builder();
  }

  /**
   * Returns a new usage with all information removed that does not influence the evaluation of the
   * extension.
   */
  ModuleExtensionUsage trimForEvaluation() {
    // We start with the full usage and selectively remove information that does not influence the
    // evaluation of the extension. Compared to explicitly copying over the parts that do, this
    // preserves correctness in case new fields are added without updating this code.
    return toBuilder()
        .setTags(getTags().stream().map(Tag::trimForEvaluation).collect(toImmutableList()))
        // Clear out all proxies as information contained therein isn't useful for evaluation.
        // Locations are only used for error reporting and thus don't influence whether the
        // evaluation of the extension is successful and what its result is in case of success.
        // Extension implementation functions do not see the imports, they are only validated
        // against the set of generated repos in a validation step that comes afterward.
        .setProxies(ImmutableList.of())
        // Tracked in SingleExtensionUsagesValue instead, using canonical instead of apparent names.
        // Whether this override must apply to an existing repo as well as its source location also
        // don't influence the evaluation of the extension as they are checked in
        // SingleExtensionFunction.
        .setRepoOverrides(ImmutableMap.of())
        .build();
  }

  /** Builder for {@link ModuleExtensionUsage}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setExtensionBzlFile(String value);

    public abstract Builder setExtensionName(String value);

    public abstract Builder setIsolationKey(Optional<ModuleExtensionId.IsolationKey> value);

    public abstract Builder setProxies(ImmutableList<Proxy> value);

    abstract ImmutableList.Builder<Proxy> proxiesBuilder();

    @CanIgnoreReturnValue
    public Builder addProxy(Proxy value) {
      proxiesBuilder().add(value);
      return this;
    }

    public abstract Builder setTags(ImmutableList<Tag> value);

    abstract ImmutableList.Builder<Tag> tagsBuilder();

    @CanIgnoreReturnValue
    public Builder addTag(Tag value) {
      tagsBuilder().add(value);
      return this;
    }

    @CanIgnoreReturnValue
    public abstract Builder setRepoOverrides(ImmutableMap<String, RepoOverride> repoOverrides);

    public abstract ModuleExtensionUsage build();
  }
}
