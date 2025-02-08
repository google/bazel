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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.bzlmod.IndexRegistry.KnownFileHashesMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.vfs.Path;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Prod implementation of {@link RegistryFactory}. */
public class RegistryFactoryImpl implements RegistryFactory {
  private final Supplier<Map<String, String>> clientEnvironmentSupplier;

  public RegistryFactoryImpl(Supplier<Map<String, String>> clientEnvironmentSupplier) {
    this.clientEnvironmentSupplier = clientEnvironmentSupplier;
  }

  @Override
  public Registry createRegistry(
      String url,
      LockfileMode lockfileMode,
      ImmutableMap<String, Optional<Checksum>> knownFileHashes,
      ImmutableMap<ModuleKey, String> previouslySelectedYankedVersions,
      Optional<Path> vendorDir)
      throws URISyntaxException {
    URI uri = new URI(url);
    if (uri.getScheme() == null) {
      throw new URISyntaxException(
          uri.toString(),
          "Registry URL has no scheme -- supported schemes are: "
              + "http://, https:// and file://");
    }
    if (uri.getPath() == null) {
      throw new URISyntaxException(
          uri.toString(),
          "Registry URL path is not valid -- did you mean to use file:///foo/bar "
              + "or file:///c:/foo/bar for Windows?");
    }
    var knownFileHashesMode =
        switch (uri.getScheme()) {
          case "http", "https" ->
              switch (lockfileMode) {
                case ERROR -> KnownFileHashesMode.ENFORCE;
                case REFRESH -> KnownFileHashesMode.USE_IMMUTABLE_AND_UPDATE;
                case OFF, UPDATE -> KnownFileHashesMode.USE_AND_UPDATE;
              };
          case "file" -> KnownFileHashesMode.IGNORE;
          default ->
              throw new URISyntaxException(uri.toString(), "Unrecognized registry URL protocol");
        };
    return new IndexRegistry(
        uri,
        clientEnvironmentSupplier.get(),
        knownFileHashes,
        knownFileHashesMode,
        previouslySelectedYankedVersions,
        vendorDir);
  }
}
