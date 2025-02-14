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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.ResolutionReason;
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule.DepSpec;
import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import net.starlark.java.eval.Dict;
import net.starlark.java.syntax.Location;

/** Utilities for bzlmod tests. */
public final class BzlmodTestUtil {
  private BzlmodTestUtil() {}

  /** Simple wrapper around the {@link ModuleKey} constructor that takes a string version. */
  public static ModuleKey createModuleKey(String name, String version) {
    try {
      return new ModuleKey(name, Version.parse(version));
    } catch (Version.ParseException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static DepSpec createDepSpec(String name, String version, int maxCompatibilityLevel) {
    try {
      return DepSpec.create(name, Version.parse(version), maxCompatibilityLevel);
    } catch (Version.ParseException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static Module.Builder buildModule(String name, String version) throws Exception {
    return Module.builder()
        .setName(name)
        .setVersion(Version.parse(version))
        .setRepoName(name)
        .setKey(createModuleKey(name, version))
        .setExtensionUsages(ImmutableList.of())
        .setExecutionPlatformsToRegister(ImmutableList.of())
        .setToolchainsToRegister(ImmutableList.of());
  }

  /** Builder class to create a {@code Entry<ModuleKey, Module>} entry faster inside UnitTests */
  static final class InterimModuleBuilder {
    InterimModule.Builder builder;
    ModuleKey key;
    ImmutableMap.Builder<String, DepSpec> deps = new ImmutableMap.Builder<>();
    ImmutableMap.Builder<String, DepSpec> originalDeps = new ImmutableMap.Builder<>();

    private InterimModuleBuilder() {}

    public static InterimModuleBuilder create(
        String name, Version version, int compatibilityLevel) {
      InterimModuleBuilder moduleBuilder = new InterimModuleBuilder();
      ModuleKey key = new ModuleKey(name, version);
      moduleBuilder.key = key;
      moduleBuilder.builder =
          InterimModule.builder()
              .setName(name)
              .setVersion(version)
              .setKey(key)
              .setCompatibilityLevel(compatibilityLevel);
      return moduleBuilder;
    }

    public static InterimModuleBuilder create(String name, String version, int compatibilityLevel)
        throws ParseException {
      return create(name, Version.parse(version), compatibilityLevel);
    }

    public static InterimModuleBuilder create(String name, String version) throws ParseException {
      return create(name, Version.parse(version), 0);
    }

    public static InterimModuleBuilder create(String name, Version version) throws ParseException {
      return create(name, version, 0);
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder addDep(String depRepoName, ModuleKey key) {
      deps.put(depRepoName, DepSpec.fromModuleKey(key));
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder addDep(String depRepoName, DepSpec depSpec) {
      deps.put(depRepoName, depSpec);
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder addOriginalDep(String depRepoName, ModuleKey key) {
      originalDeps.put(depRepoName, DepSpec.fromModuleKey(key));
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder addOriginalDep(String depRepoName, DepSpec depSpec) {
      originalDeps.put(depRepoName, depSpec);
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder addNodepDep(ModuleKey key) {
      builder.addNodepDep(DepSpec.fromModuleKey(key));
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder setKey(ModuleKey value) {
      this.key = value;
      this.builder.setKey(value);
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder setRepoName(String value) {
      this.builder.setRepoName(value);
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder setRegistry(FakeRegistry value) {
      this.builder.setRegistry(value);
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder addExecutionPlatformsToRegister(ImmutableList<String> value) {
      this.builder.addExecutionPlatformsToRegister(value);
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder addToolchainsToRegister(ImmutableList<String> value) {
      this.builder.addToolchainsToRegister(value);
      return this;
    }

    @CanIgnoreReturnValue
    public InterimModuleBuilder addExtensionUsage(ModuleExtensionUsage value) {
      this.builder.addExtensionUsage(value);
      return this;
    }

    public Map.Entry<ModuleKey, InterimModule> buildEntry() {
      InterimModule module = this.build();
      return new SimpleEntry<>(this.key, module);
    }

    public InterimModule build() {
      ImmutableMap<String, DepSpec> builtDeps = this.deps.buildOrThrow();

      /* Copy dep entries that have not been changed to original deps */
      ImmutableMap<String, DepSpec> initOriginalDeps = this.originalDeps.buildOrThrow();
      for (Entry<String, DepSpec> e : builtDeps.entrySet()) {
        if (!initOriginalDeps.containsKey(e.getKey())) {
          originalDeps.put(e);
        }
      }
      ImmutableMap<String, DepSpec> builtOriginalDeps = this.originalDeps.buildOrThrow();

      return this.builder.setDeps(builtDeps).setOriginalDeps(builtOriginalDeps).build();
    }
  }

  /**
   * Builder helper for {@link
   * com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule}
   */
  public static final class AugmentedModuleBuilder {

    public static AugmentedModuleBuilder buildAugmentedModule(
        ModuleKey key, String name, Version version, boolean loaded) {
      AugmentedModuleBuilder myBuilder = new AugmentedModuleBuilder();
      myBuilder.key = key;
      myBuilder.builder =
          AugmentedModule.builder(key)
              .setName(name)
              .setVersion(version)
              .setRepoName(name)
              .setLoaded(loaded);
      return myBuilder;
    }

    public static AugmentedModuleBuilder buildAugmentedModule(
        String name, String version, boolean loaded) throws ParseException {
      ModuleKey key = createModuleKey(name, version);
      return buildAugmentedModule(key, name, Version.parse(version), loaded);
    }

    public static AugmentedModuleBuilder buildAugmentedModule(String name, String version)
        throws ParseException {
      ModuleKey key = createModuleKey(name, version);
      return buildAugmentedModule(key, name, Version.parse(version), true);
    }

    public static AugmentedModuleBuilder buildAugmentedModule(ModuleKey key, String name) {
      return buildAugmentedModule(key, name, key.version(), true);
    }

    private AugmentedModule.Builder builder;
    private ModuleKey key;

    private AugmentedModuleBuilder() {}

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addChangedDep(
        String name, String version, String oldVersion, ResolutionReason reason) {
      this.builder
          .addDep(name, createModuleKey(name, version))
          .addUnusedDep(name, createModuleKey(name, oldVersion))
          .addDepReason(name, reason);
      return this;
    }

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addChangedDep(
        String repoName,
        String moduleName,
        String version,
        String oldVersion,
        ResolutionReason reason) {
      this.builder
          .addDep(repoName, createModuleKey(moduleName, version))
          .addUnusedDep(repoName, createModuleKey(moduleName, oldVersion))
          .addDepReason(repoName, reason);
      return this;
    }

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addDep(String name, String version) {
      this.builder
          .addDep(name, createModuleKey(name, version))
          .addDepReason(name, ResolutionReason.ORIGINAL);
      return this;
    }

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addDep(String repoName, String moduleName, String version) {
      this.builder
          .addDep(repoName, createModuleKey(moduleName, version))
          .addDepReason(repoName, ResolutionReason.ORIGINAL);
      return this;
    }

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addDependant(String name, String version) {
      this.builder.addDependant(createModuleKey(name, version));
      return this;
    }

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addDependant(ModuleKey key) {
      this.builder.addDependant(key);
      return this;
    }

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addOriginalDependant(String name, String version) {
      this.builder.addOriginalDependant(createModuleKey(name, version));
      return this;
    }

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addOriginalDependant(ModuleKey key) {
      this.builder.addOriginalDependant(key);
      return this;
    }

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addStillDependant(String name, String version) {
      this.builder.addOriginalDependant(createModuleKey(name, version));
      this.builder.addDependant(createModuleKey(name, version));
      return this;
    }

    @CanIgnoreReturnValue
    public AugmentedModuleBuilder addStillDependant(ModuleKey key) {
      this.builder.addOriginalDependant(key);
      this.builder.addDependant(key);
      return this;
    }

    public Entry<ModuleKey, AugmentedModule> buildEntry() {
      return new SimpleEntry<>(this.key, this.builder.build());
    }
  }

  public static RepositoryMapping createRepositoryMapping(ModuleKey key, String... names) {
    ImmutableMap.Builder<String, RepositoryName> mappingBuilder = ImmutableMap.builder();
    for (int i = 0; i < names.length; i += 2) {
      mappingBuilder.put(names[i], RepositoryName.createUnvalidated(names[i + 1]));
    }
    return RepositoryMapping.create(
        mappingBuilder.buildOrThrow(), key.getCanonicalRepoNameWithoutVersion());
  }

  public static TagClass createTagClass(Attribute... attrs) {
    return TagClass.create(ImmutableList.copyOf(attrs), Optional.of("doc"));
  }

  /** A builder for {@link Tag} for testing purposes. */
  public static class TestTagBuilder {
    private final Dict.Builder<String, Object> attrValuesBuilder = Dict.builder();
    private Location location = Location.BUILTIN;
    private final String tagName;
    private boolean devDependency = false;

    private TestTagBuilder(String tagName) {
      this.tagName = tagName;
    }

    @CanIgnoreReturnValue
    public TestTagBuilder addAttr(String attrName, Object attrValue) {
      attrValuesBuilder.put(attrName, attrValue);
      return this;
    }

    @CanIgnoreReturnValue
    public TestTagBuilder setDevDependency() {
      devDependency = true;
      return this;
    }

    public Tag build() {
      return Tag.builder()
          .setTagName(tagName)
          .setLocation(location)
          .setAttributeValues(AttributeValues.create(attrValuesBuilder.buildImmutable()))
          .setDevDependency(devDependency)
          .build();
    }
  }

  public static TestTagBuilder buildTag(String tagName) throws Exception {
    return new TestTagBuilder(tagName);
  }
}
