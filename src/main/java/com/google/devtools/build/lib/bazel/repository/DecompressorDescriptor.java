// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.vfs.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Description of an archive to be decompressed.
 *
 * @param context The context in which this decompression is happening. Should only be used for
 *     reporting.
 */
public record DecompressorDescriptor(
    String context,
    Path archivePath,
    Path destinationPath,
    Optional<String> prefix,
    ImmutableMap<String, String> renameFiles) {
  public DecompressorDescriptor {
    requireNonNull(context, "context");
    requireNonNull(archivePath, "archivePath");
    requireNonNull(destinationPath, "destinationPath");
    requireNonNull(prefix, "prefix");
    requireNonNull(renameFiles, "renameFiles");
  }

  public static Builder builder() {
    return new AutoBuilder_DecompressorDescriptor_Builder()
        .setContext("")
        .setRenameFiles(ImmutableMap.of());
  }

  /** Builder for describing the file to be decompressed. */
  @AutoBuilder
  public abstract static class Builder {

    public abstract Builder setContext(String context);

    public abstract Builder setArchivePath(Path archivePath);

    public abstract Builder setDestinationPath(Path destinationPath);

    public abstract Builder setPrefix(String prefix);

    public abstract Builder setRenameFiles(Map<String, String> renameFiles);

    public abstract DecompressorDescriptor build();
  }
}
