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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fake implementation of {@link Registry}, where modules can be freely added and stored in memory.
 * The contents of the modules are expected to be located under a given file path as subdirectories.
 */
public class FakeRegistry implements Registry {
  private static final Joiner JOINER = Joiner.on('\n');
  private final String url;
  private final String rootPath;
  private final Map<ModuleKey, String> modules = new HashMap<>();
  private final Map<String, ImmutableMap<Version, String>> yankedVersionMap = new HashMap<>();

  public FakeRegistry(String url, String rootPath) {
    this.url = url;
    this.rootPath = rootPath;
  }

  @CanIgnoreReturnValue
  public FakeRegistry addModule(ModuleKey key, String... moduleFileLines) {
    modules.put(key, JOINER.join(moduleFileLines));
    return this;
  }

  @CanIgnoreReturnValue
  public FakeRegistry addYankedVersion(
      String moduleName, ImmutableMap<Version, String> yankedVersions) {
    yankedVersionMap.put(moduleName, yankedVersions);
    return this;
  }

  @Override
  public String getUrl() {
    return url;
  }

  @Override
  public ModuleFile getModuleFile(
      ModuleKey key, ExtendedEventHandler eventHandler, DownloadManager downloadManager)
      throws NotFoundException {
    String uri =
        String.format("%s/modules/%s/%s/MODULE.bazel", url, key.getName(), key.getVersion());
    var maybeContent = Optional.ofNullable(modules.get(key)).map(value -> value.getBytes(UTF_8));
    eventHandler.post(RegistryFileDownloadEvent.create(uri, maybeContent));
    if (maybeContent.isEmpty()) {
      throw new NotFoundException("module not found: " + key);
    }
    return ModuleFile.create(maybeContent.get(), uri);
  }

  @Override
  public RepoSpec getRepoSpec(
      ModuleKey key, ExtendedEventHandler eventHandler, DownloadManager downloadManager) {
    RepoSpec repoSpec =
        RepoSpec.builder()
            .setRuleClassName("local_repository")
            .setAttributes(
                AttributeValues.create(
                    ImmutableMap.of(
                        "path",
                        rootPath
                            + "/"
                            + key.getCanonicalRepoNameWithVersionForTesting().getName())))
            .build();
    eventHandler.post(
        RegistryFileDownloadEvent.create(
            "%s/modules/%s/%s/source.json"
                .formatted(url, key.getName(), key.getVersion().toString()),
            Optional.of(
                GsonTypeAdapterUtil.SINGLE_EXTENSION_USAGES_VALUE_GSON
                    .toJson(repoSpec)
                    .getBytes(UTF_8))));
    return repoSpec;
  }

  @Override
  public Optional<ImmutableMap<Version, String>> getYankedVersions(
      String moduleName, ExtendedEventHandler eventHandler, DownloadManager downloadManager) {
    return Optional.ofNullable(yankedVersionMap.get(moduleName));
  }

  @Override
  public Optional<YankedVersionsValue> tryGetYankedVersionsFromLockfile(
      ModuleKey selectedModuleKey) {
    return Optional.empty();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof FakeRegistry
        && this.url.equals(((FakeRegistry) other).url)
        && this.modules.equals(((FakeRegistry) other).modules);
  }

  @Override
  public int hashCode() {
    return Objects.hash(url, modules);
  }

  public static final Factory DEFAULT_FACTORY = new Factory();

  /** Fake {@link RegistryFactory} that only supports {@link FakeRegistry}. */
  public static class Factory implements RegistryFactory {

    private int numFakes = 0;
    private final Map<String, FakeRegistry> registries = new HashMap<>();

    public FakeRegistry newFakeRegistry(String rootPath) {
      FakeRegistry registry = new FakeRegistry("fake:" + numFakes++, rootPath);
      registries.put(registry.getUrl(), registry);
      return registry;
    }

    @Override
    public Registry createRegistry(
        String url,
        LockfileMode lockfileMode,
        ImmutableMap<String, Optional<Checksum>> fileHashes,
        ImmutableMap<ModuleKey, String> previouslySelectedYankedVersions,
        Optional<Path> vendorDir) {
      return Preconditions.checkNotNull(registries.get(url), "unknown registry url: %s", url);
    }
  }
}
