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

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Optional;
import java.util.Set;
import net.starlark.java.eval.Starlark;

/**
 * The contents of decompressed archive.
 */
public class DecompressorValue implements SkyValue {

  /** Implementation of a decompression algorithm. */
  public interface Decompressor {

    /** Exception reporting about absence of an expected prefix in an archive. */
    class CouldNotFindPrefixException extends IOException {

      CouldNotFindPrefixException(String prefix, Set<String> availablePrefixes) {

        super(CouldNotFindPrefixException.prepareErrorMessage(prefix, availablePrefixes));
      }

      private static String prepareErrorMessage(String prefix, Set<String> availablePrefixes) {
        String error = "Prefix \"" + prefix + "\" was given, but not found in the archive. ";
        String suggestion = "Here are possible prefixes for this archive: ";
        String suggestionBody = "";

        if (availablePrefixes.isEmpty()) {
          suggestion =
              "We could not find any directory in this archive"
                  + " (maybe there is no need for `strip_prefix`?)";
        } else {
          // Add a list of possible suggestion wrapped with `"` and separated by `, `.
          suggestionBody = "\"" + String.join("\", \"", availablePrefixes) + "\".";
        }

        return error + suggestion + suggestionBody;
      }

      public static Optional<String> maybeMakePrefixSuggestion(PathFragment pathFragment) {
        if (!pathFragment.isMultiSegment()) {
          return Optional.empty();
        }
        return Optional.of(pathFragment.getSegment(0));
      }
    }

    Path decompress(DecompressorDescriptor descriptor)
        throws IOException, RepositoryFunctionException, InterruptedException;
  }

  private final Path directory;

  public DecompressorValue(Path repositoryPath) {
    directory = repositoryPath;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || (other instanceof DecompressorValue decompressorValue
            && directory.equals(decompressorValue.directory));
  }

  @Override
  public int hashCode() {
    return directory.hashCode();
  }

  @VisibleForTesting
  static Decompressor getDecompressor(Path archivePath) throws RepositoryFunctionException {
    String baseName = archivePath.getBaseName();
    if (baseName.endsWith(".zip")
        || baseName.endsWith(".jar")
        || baseName.endsWith(".war")
        || baseName.endsWith(".aar")
        || baseName.endsWith(".nupkg")) {
      return ZipDecompressor.INSTANCE;
    } else if (baseName.endsWith(".tar")) {
      return TarFunction.INSTANCE;
    } else if (baseName.endsWith(".tar.gz") || baseName.endsWith(".tgz")) {
      return TarGzFunction.INSTANCE;
    } else if (baseName.endsWith(".tar.xz") || baseName.endsWith(".txz")) {
      return TarXzFunction.INSTANCE;
    } else if (baseName.endsWith(".tar.zst") || baseName.endsWith(".tzst")) {
      return TarZstFunction.INSTANCE;
    } else if (baseName.endsWith(".tar.bz2") || baseName.endsWith(".tbz")) {
      return TarBz2Function.INSTANCE;
    } else if (baseName.endsWith(".ar") || baseName.endsWith(".deb")) {
      return ArFunction.INSTANCE;
    } else {
      throw new RepositoryFunctionException(
          Starlark.errorf(
              "Expected a file with a .zip, .jar, .war, .aar, .nupkg, .tar, .tar.gz, .tgz, .tar.xz,"
                  + " , .tar.zst, .tzst, .tar.bz2, .tbz, .ar or .deb suffix (got %s)",
              archivePath),
          Transience.PERSISTENT);
    }
  }

  public static Path decompress(DecompressorDescriptor descriptor)
      throws RepositoryFunctionException, InterruptedException {
    try {
      return getDecompressor(descriptor.archivePath()).decompress(descriptor);
    } catch (ClosedByInterruptException e) {
      throw new InterruptedException();
    } catch (IOException e) {
      Path destinationDirectory = descriptor.archivePath().getParentDirectory();
      throw new RepositoryFunctionException(
          new IOException(
              String.format(
                  "Error extracting %s to %s: %s",
                  descriptor.archivePath(), destinationDirectory, e.getMessage())),
          Transience.TRANSIENT);
    }
  }
}
