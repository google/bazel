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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum.MissingChecksumException;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Represents a Bazel module registry that serves a list of module metadata from a static HTTP
 * server or a local file path.
 *
 * <p>For details, see <a href="https://bazel.build/external/registry">the docs</a>
 */
public class IndexRegistry implements Registry {

  /**
   * How to handle the list of file hashes known from the lockfile when downloading files from the
   * registry.
   */
  public enum KnownFileHashesMode {
    /**
     * Neither use nor update any file hashes. All registry downloads will go out to the network.
     */
    IGNORE,
    /**
     * Use file hashes from the lockfile if available and add hashes for new files to the lockfile.
     * Avoid revalidation of mutable registry information (yanked versions in metadata.json and
     * modules that previously 404'd) by using these hashes and recording absent files in the
     * lockfile.
     */
    USE_AND_UPDATE,
    /**
     * Use file hashes from the lockfile if available and add hashes for new files to the lockfile.
     * Always revalidate mutable registry information.
     */
    USE_IMMUTABLE_AND_UPDATE,
    /**
     * Require file hashes for all registry downloads. In particular, mutable registry files such as
     * metadata.json can't be downloaded in this mode.
     */
    ENFORCE
  }

  private final URI uri;
  private final Map<String, String> clientEnv;
  private final Gson gson;
  private final ImmutableMap<String, Optional<Checksum>> knownFileHashes;
  private final ImmutableMap<ModuleKey, String> previouslySelectedYankedVersions;
  @Nullable private final VendorManager vendorManager;
  private final KnownFileHashesMode knownFileHashesMode;
  private volatile Optional<BazelRegistryJson> bazelRegistryJson;
  private volatile StoredEventHandler bazelRegistryJsonEvents;

  private static final String SOURCE_JSON_FILENAME = "source.json";

  public IndexRegistry(
      URI uri,
      Map<String, String> clientEnv,
      ImmutableMap<String, Optional<Checksum>> knownFileHashes,
      KnownFileHashesMode knownFileHashesMode,
      ImmutableMap<ModuleKey, String> previouslySelectedYankedVersions,
      Optional<Path> vendorDir) {
    this.uri = uri;
    this.clientEnv = clientEnv;
    this.gson =
        new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();
    this.knownFileHashes = knownFileHashes;
    this.knownFileHashesMode = knownFileHashesMode;
    this.previouslySelectedYankedVersions = previouslySelectedYankedVersions;
    this.vendorManager = vendorDir.map(VendorManager::new).orElse(null);
  }

  @Override
  public String getUrl() {
    return uri.toString();
  }

  private String constructUrl(String base, String... segments) {
    StringBuilder url = new StringBuilder(base);
    for (String segment : segments) {
      if (url.charAt(url.length() - 1) != '/' && !segment.startsWith("/")) {
        url.append('/');
      }
      url.append(segment);
    }
    return url.toString();
  }

  /** Grabs a file from the given URL. Throws {@link NotFoundException} if it doesn't exist. */
  private byte[] grabFile(
      String url,
      ExtendedEventHandler eventHandler,
      DownloadManager downloadManager,
      boolean useChecksum)
      throws IOException, InterruptedException, NotFoundException {
    Optional<byte[]> maybeContent = Optional.empty();
    try {
      maybeContent = Optional.of(doGrabFile(downloadManager, url, eventHandler, useChecksum));
      return maybeContent.get();
    } finally {
      if ((knownFileHashesMode == KnownFileHashesMode.USE_AND_UPDATE
              || knownFileHashesMode == KnownFileHashesMode.USE_IMMUTABLE_AND_UPDATE)
          && useChecksum) {
        eventHandler.post(RegistryFileDownloadEvent.create(url, maybeContent));
      }
    }
  }

  private byte[] doGrabFile(
      DownloadManager downloadManager,
      String rawUrl,
      ExtendedEventHandler eventHandler,
      boolean useChecksum)
      throws IOException, InterruptedException, NotFoundException {
    Optional<Checksum> checksum;
    if (knownFileHashesMode != KnownFileHashesMode.IGNORE && useChecksum) {
      Optional<Checksum> knownChecksum = knownFileHashes.get(rawUrl);
      if (knownChecksum == null) {
        if (knownFileHashesMode == KnownFileHashesMode.ENFORCE) {
          throw new MissingChecksumException(
              String.format(
                  "Missing checksum for registry file %s not permitted with --lockfile_mode=error."
                      + " Please run `bazel mod deps --lockfile_mode=update` to update your"
                      + " lockfile.",
                  rawUrl));
        }
        // This is a new file, download without providing a checksum.
        checksum = Optional.empty();
      } else if (knownChecksum.isEmpty()) {
        // The file didn't exist when the lockfile was created, but it may exist now.
        if (knownFileHashesMode == KnownFileHashesMode.USE_IMMUTABLE_AND_UPDATE) {
          // Attempt to download the file again.
          checksum = Optional.empty();
        } else {
          // Guarantee reproducibility by assuming that the file still doesn't exist.
          throw new NotFoundException(
              String.format(
                  "%s: previously not found (as recorded in %s, refresh with"
                      + " --lockfile_mode=refresh)",
                  rawUrl, LabelConstants.MODULE_LOCKFILE_NAME));
        }
      } else {
        // The file is known, download with a checksum to potentially obtain a repository cache hit
        // and ensure that the remote file hasn't changed.
        checksum = knownChecksum;
      }
    } else {
      checksum = Optional.empty();
    }
    if (knownFileHashesMode == KnownFileHashesMode.ENFORCE) {
      Preconditions.checkState(
          checksum.isPresent(),
          "Cannot fetch a file without a checksum in ENFORCE mode. This is a bug in Bazel, please "
              + "report at https://github.com/bazelbuild/bazel/issues/new/choose.");
    }

    URL url = URI.create(rawUrl).toURL();
    // Don't read the registry URL from the vendor directory in the following cases:
    // 1. vendorUtil is null, which means vendor mode is disabled.
    // 2. The checksum is not present, which means the URL is not vendored or the vendored content
    // is out-dated.
    // 3. The URL starts with "file:", which means it's a local file and isn't vendored.
    // 4. The vendor path doesn't exist, which means the URL is not vendored.
    if (vendorManager != null
        && checksum.isPresent()
        && !url.getProtocol().equals("file")
        && vendorManager.isUrlVendored(url)) {
      try {
        return vendorManager.readRegistryUrl(url, checksum.get());
      } catch (IOException e) {
        throw new IOException(
            String.format(
                "Failed to read vendored registry file %s at %s: %s. Please rerun the bazel"
                    + " vendor command.",
                rawUrl, vendorManager.getVendorPathForUrl(url), e.getMessage()),
            e);
      }
    }

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, () -> "download file: " + rawUrl)) {
      return downloadManager.downloadAndReadOneUrlForBzlmod(url, eventHandler, clientEnv, checksum);
    } catch (FileNotFoundException e) {
      throw new NotFoundException(String.format("%s: not found", rawUrl));
    } catch (IOException e) {
      // Include the URL in the exception message for easier debugging.
      throw new IOException(
          "Failed to fetch registry file %s: %s".formatted(rawUrl, e.getMessage()), e);
    }
  }

  @Override
  public ModuleFile getModuleFile(
      ModuleKey key, ExtendedEventHandler eventHandler, DownloadManager downloadManager)
      throws IOException, InterruptedException, NotFoundException {
    String url =
        constructUrl(
            getUrl(), "modules", key.getName(), key.getVersion().toString(), "MODULE.bazel");
    byte[] content = grabFile(url, eventHandler, downloadManager, /* useChecksum= */ true);
    return ModuleFile.create(content, url);
  }

  /** Represents fields available in {@code bazel_registry.json} for the registry. */
  private static class BazelRegistryJson {
    String[] mirrors;
    String moduleBasePath;
  }

  /** Represents the type field in {@code source.json} for each version of a module. */
  private static class SourceJson {
    String type = "archive";
  }

  /** Represents fields in {@code source.json} for each archive-type version of a module. */
  private static class ArchiveSourceJson {
    URL url;
    String integrity;
    String stripPrefix;
    Map<String, String> patches;
    Map<String, String> overlay;
    int patchStrip;
    String archiveType;
  }

  /** Represents fields in {@code source.json} for each local_path-type version of a module. */
  private static class LocalPathSourceJson {
    String path;
  }

  /** Represents fields in {@code source.json} for each git_repository-type version of a module. */
  private static class GitRepoSourceJson {
    String remote;
    String commit;
    String shallowSince;
    String tag;
    boolean initSubmodules;
    boolean verbose;
    String stripPrefix;
  }

  /**
   * Grabs a JSON file from the given URL, and returns its content. Returns {@link Optional#empty}
   * if the file doesn't exist.
   */
  private Optional<String> grabJsonFile(
      String url,
      ExtendedEventHandler eventHandler,
      DownloadManager downloadManager,
      boolean useChecksum)
      throws IOException, InterruptedException {
    try {
      return Optional.of(
          new String(grabFile(url, eventHandler, downloadManager, useChecksum), UTF_8));
    } catch (NotFoundException e) {
      return Optional.empty();
    }
  }

  /**
   * Grabs a JSON file from the given URL, and returns it as a parsed object with fields in {@code
   * T}. Returns {@link Optional#empty} if the file doesn't exist.
   */
  private <T> Optional<T> grabJson(
      String url,
      Class<T> klass,
      ExtendedEventHandler eventHandler,
      DownloadManager downloadManager,
      boolean useChecksum)
      throws IOException, InterruptedException {
    Optional<String> jsonString = grabJsonFile(url, eventHandler, downloadManager, useChecksum);
    if (jsonString.isEmpty() || jsonString.get().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(parseJson(jsonString.get(), url, klass));
  }

  /** Parses the given JSON string and returns it as an object with fields in {@code T}. */
  private <T> T parseJson(String jsonString, String url, Class<T> klass) throws IOException {
    try {
      return gson.fromJson(jsonString, klass);
    } catch (JsonParseException e) {
      throw new IOException(
          String.format("Unable to parse json at url %s: %s", url, e.getMessage()), e);
    }
  }

  @Override
  public RepoSpec getRepoSpec(
      ModuleKey key, ExtendedEventHandler eventHandler, DownloadManager downloadManager)
      throws IOException, InterruptedException {
    String jsonUrl = getSourceJsonUrl(key);
    Optional<String> jsonString =
        grabJsonFile(jsonUrl, eventHandler, downloadManager, /* useChecksum= */ true);
    if (jsonString.isEmpty()) {
      throw new FileNotFoundException(
          String.format(
              "Module %s's %s not found in registry %s", key, SOURCE_JSON_FILENAME, getUrl()));
    }
    SourceJson sourceJson = parseJson(jsonString.get(), jsonUrl, SourceJson.class);
    switch (sourceJson.type) {
      case "archive" -> {
        ArchiveSourceJson typedSourceJson =
            parseJson(jsonString.get(), jsonUrl, ArchiveSourceJson.class);
        return createArchiveRepoSpec(typedSourceJson, getBazelRegistryJson(eventHandler, downloadManager), key);
      }
      case "local_path" -> {
        LocalPathSourceJson typedSourceJson =
            parseJson(jsonString.get(), jsonUrl, LocalPathSourceJson.class);
        return createLocalPathRepoSpec(typedSourceJson, getBazelRegistryJson(eventHandler, downloadManager), key);
      }
      case "git_repository" -> {
        GitRepoSourceJson typedSourceJson =
            parseJson(jsonString.get(), jsonUrl, GitRepoSourceJson.class);
        return createGitRepoSpec(typedSourceJson);
      }
      default ->
          throw new IOException(
              String.format("Invalid source type \"%s\" for module %s", sourceJson.type, key));
    }
  }

  private String getSourceJsonUrl(ModuleKey key) {
    return constructUrl(
        getUrl(), "modules", key.getName(), key.getVersion().toString(), SOURCE_JSON_FILENAME);
  }

  private Optional<BazelRegistryJson> getBazelRegistryJson(
      ExtendedEventHandler eventHandler, DownloadManager downloadManager)
      throws IOException, InterruptedException {
    if (bazelRegistryJson == null || bazelRegistryJsonEvents == null) {
      synchronized (this) {
        if (bazelRegistryJson == null || bazelRegistryJsonEvents == null) {
          Preconditions.checkState(bazelRegistryJson == null && bazelRegistryJsonEvents == null);
          var storedEventHandler = new StoredEventHandler();
          bazelRegistryJson =
              grabJson(
                  constructUrl(getUrl(), "bazel_registry.json"),
                  BazelRegistryJson.class,
                  storedEventHandler,
                  downloadManager,
                  /* useChecksum= */ true);
          bazelRegistryJsonEvents = storedEventHandler;
        }
      }
    }
    bazelRegistryJsonEvents.replayOn(eventHandler);
    return bazelRegistryJson;
  }

  private RepoSpec createLocalPathRepoSpec(
      LocalPathSourceJson sourceJson, Optional<BazelRegistryJson> bazelRegistryJson, ModuleKey key)
      throws IOException {
    String path = sourceJson.path;
    if (!PathFragment.isAbsolute(path)) {
      String moduleBase = bazelRegistryJson.get().moduleBasePath;
      path = moduleBase + "/" + path;
      if (!PathFragment.isAbsolute(moduleBase)) {
        if (uri.getScheme().equals("file")) {
          if (uri.getPath().isEmpty() || !uri.getPath().startsWith("/")) {
            throw new IOException(
                String.format(
                    "Provided non absolute local registry path for module %s: %s",
                    key, uri.getPath()));
          }
          // Unix:    file:///tmp --> /tmp
          // Windows: file:///C:/tmp --> C:/tmp
          path = uri.getPath().substring(OS.getCurrent() == OS.WINDOWS ? 1 : 0) + "/" + path;
        } else {
          throw new IOException(String.format("Provided non local registry for module %s", key));
        }
      }
    }

    return RepoSpec.builder()
        .setRuleClassName("local_repository")
        .setAttributes(
            AttributeValues.create(ImmutableMap.of("path", PathFragment.create(path).toString())))
        .build();
  }

  private RepoSpec createArchiveRepoSpec(
      ArchiveSourceJson sourceJson, Optional<BazelRegistryJson> bazelRegistryJson, ModuleKey key)
      throws IOException {
    URL sourceUrl = sourceJson.url;
    if (sourceUrl == null) {
      throw new IOException(String.format("Missing source URL for module %s", key));
    }
    if (sourceJson.integrity == null) {
      throw new IOException(String.format("Missing integrity for module %s", key));
    }

    ImmutableList.Builder<String> urls = new ImmutableList.Builder<>();
    // For each mirror specified in bazel_registry.json, add a URL that's essentially the mirror
    // URL concatenated with the source URL.
    if (bazelRegistryJson.isPresent() && bazelRegistryJson.get().mirrors != null) {
      for (String mirror : bazelRegistryJson.get().mirrors) {
        try {
          var unused = new URL(mirror);
        } catch (MalformedURLException e) {
          throw new IOException("Malformed mirror URL", e);
        }

        urls.add(constructUrl(mirror, sourceUrl.getAuthority(), sourceUrl.getFile()));
      }
    }
    // Finally add the original source URL itself.
    urls.add(sourceUrl.toString());

    // Build remote patches as key-value pairs of "url" => "integrity".
    ImmutableMap.Builder<String, String> remotePatches = new ImmutableMap.Builder<>();
    if (sourceJson.patches != null) {
      for (Map.Entry<String, String> entry : sourceJson.patches.entrySet()) {
        remotePatches.put(
            constructUrl(
                getUrl(),
                "modules",
                key.getName(),
                key.getVersion().toString(),
                "patches",
                entry.getKey()),
            entry.getValue());
      }
    }

    ImmutableMap<String, String> sourceJsonOverlay =
        sourceJson.overlay != null ? ImmutableMap.copyOf(sourceJson.overlay) : ImmutableMap.of();
    ImmutableMap<String, ArchiveRepoSpecBuilder.RemoteFile> overlay =
        sourceJsonOverlay.entrySet().stream()
            .collect(
                toImmutableMap(
                    Entry::getKey,
                    entry ->
                        new ArchiveRepoSpecBuilder.RemoteFile(
                            entry.getValue(), // integrity
                            // URLs in the registry itself are not mirrored.
                            ImmutableList.of(
                                constructUrl(
                                    getUrl(),
                                    "modules",
                                    key.getName(),
                                    key.getVersion().toString(),
                                    "overlay",
                                    entry.getKey())))));

    return new ArchiveRepoSpecBuilder()
        .setUrls(urls.build())
        .setIntegrity(sourceJson.integrity)
        .setStripPrefix(Strings.nullToEmpty(sourceJson.stripPrefix))
        .setRemotePatches(remotePatches.buildOrThrow())
        .setOverlay(overlay)
        .setRemotePatchStrip(sourceJson.patchStrip)
        .setArchiveType(sourceJson.archiveType)
        .build();
  }

  private RepoSpec createGitRepoSpec(GitRepoSourceJson sourceJson) {
    return new GitRepoSpecBuilder()
        .setRemote(sourceJson.remote)
        .setCommit(sourceJson.commit)
        .setShallowSince(sourceJson.shallowSince)
        .setTag(sourceJson.tag)
        .setInitSubmodules(sourceJson.initSubmodules)
        .setVerbose(sourceJson.verbose)
        .setStripPrefix(sourceJson.stripPrefix)
        .build();
  }

  @Override
  public Optional<ImmutableMap<Version, String>> getYankedVersions(
      String moduleName, ExtendedEventHandler eventHandler, DownloadManager downloadManager)
      throws IOException, InterruptedException {
    Optional<MetadataJson> metadataJson =
        grabJson(
            constructUrl(getUrl(), "modules", moduleName, "metadata.json"),
            MetadataJson.class,
            eventHandler,
            downloadManager,
            // metadata.json is not immutable
            /* useChecksum= */ false);
    if (metadataJson.isEmpty()) {
      return Optional.empty();
    }

    try {
      ImmutableMap.Builder<Version, String> yankedVersionsBuilder = new ImmutableMap.Builder<>();
      if (metadataJson.get().yankedVersions != null) {
        for (Entry<String, String> e : metadataJson.get().yankedVersions.entrySet()) {
          yankedVersionsBuilder.put(Version.parse(e.getKey()), e.getValue());
        }
      }
      return Optional.of(yankedVersionsBuilder.buildOrThrow());
    } catch (ParseException e) {
      throw new IOException(
          String.format(
              "Could not parse module %s's metadata file: %s", moduleName, e.getMessage()));
    }
  }

  @Override
  public Optional<YankedVersionsValue> tryGetYankedVersionsFromLockfile(
      ModuleKey selectedModuleKey) {
    if (knownFileHashesMode == KnownFileHashesMode.USE_IMMUTABLE_AND_UPDATE) {
      // Yanked version information is inherently mutable, so always refresh it when requested.
      return Optional.empty();
    }
    String yankedInfo = previouslySelectedYankedVersions.get(selectedModuleKey);
    if (yankedInfo != null) {
      // The module version was selected when the lockfile was created, but known to be yanked
      // (hence, it was explicitly allowed by the user). We reuse the yanked info from the lockfile.
      // Rationale: A module that was yanked in the past should remain yanked in the future. The
      // yanked info may have been updated since then, but by not fetching it, we avoid network
      // access if the set of yanked versions has not changed, but the set allowed versions has.
      return Optional.of(
          YankedVersionsValue.create(
              Optional.of(ImmutableMap.of(selectedModuleKey.getVersion(), yankedInfo))));
    }
    if (knownFileHashes.containsKey(getSourceJsonUrl(selectedModuleKey))) {
      // If the source.json hash is recorded in the lockfile, we know that the module was selected
      // when the lockfile was created. Since it does not appear in the list of selected yanked
      // versions recorded in the lockfile, it must not have been yanked at that time. We do not
      // refresh yanked versions information.
      // Rationale: This ensures that builds with --lockfile_mode=update or error are reproducible
      // and do not fail due to changes in the set of yanked versions. Furthermore, it avoids
      // refetching yanked versions for all modules every time the user modifies or adds a
      // dependency. If the selected version for a module changes, yanked version information is
      // always refreshed.
      return Optional.of(YankedVersionsValue.NONE_YANKED);
    }
    // The lockfile does not contain sufficient information to determine the "yanked" status of the
    // module - network access to the registry is required.
    // Note that this point can't (and must not) be reached with --lockfile_mode=error: The lockfile
    // records the source.json hashes of all selected modules and the result of selection is fully
    // determined by the lockfile.
    return Optional.empty();
  }

  /** Represents fields available in {@code metadata.json} for each module. */
  static class MetadataJson {
    // There are other attributes in the metadata.json file, but for now, we only care about
    // the yanked_version attribute.
    Map<String, String> yankedVersions;
  }
}
