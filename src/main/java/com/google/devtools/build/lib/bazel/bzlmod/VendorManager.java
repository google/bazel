// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

/** Class to manage the vendor directory. */
public class VendorManager {

  private static final String REGISTRIES_DIR = "_registries";

  public static final String EXTERNAL_ROOT_SYMLINK_NAME = "bazel-external";

  private final Path vendorDirectory;

  public VendorManager(Path vendorDirectory) {
    this.vendorDirectory = vendorDirectory;
  }

  /**
   * Vendors the specified repositories under the vendor directory.
   *
   * <p>TODO(pcloudy): Parallelize vendoring repos
   *
   * @param externalRepoRoot The root directory of the external repositories.
   * @param reposToVendor The list of repositories to vendor.
   * @throws IOException if an I/O error occurs.
   */
  public void vendorRepos(Path externalRepoRoot, ImmutableList<RepositoryName> reposToVendor)
      throws IOException {
    if (!vendorDirectory.exists()) {
      vendorDirectory.createDirectoryAndParents();
    }

    for (RepositoryName repo : reposToVendor) {
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.REPOSITORY_VENDOR, repo.toString())) {
        Path repoUnderExternal = externalRepoRoot.getChild(repo.getName());
        Path repoUnderVendor = vendorDirectory.getChild(repo.getName());
        // This could happen when running the vendor command twice without changing anything.
        if (repoUnderExternal.isSymbolicLink()
            && repoUnderExternal.resolveSymbolicLinks().equals(repoUnderVendor)) {
          continue;
        }

        // At this point, the repo should exist under external dir, but check if the vendor src is
        // already up-to-date.
        Path markerUnderExternal = externalRepoRoot.getChild(repo.getMarkerFileName());
        Path markerUnderVendor = vendorDirectory.getChild(repo.getMarkerFileName());
        if (isRepoUpToDate(markerUnderVendor, markerUnderExternal)) {
          continue;
        }

        // Actually vendor the repo:
        // 1. Clean up existing marker file and vendor dir.
        markerUnderVendor.delete();
        repoUnderVendor.deleteTree();
        repoUnderVendor.createDirectory();
        // 2. Move the marker file to a temporary one under vendor dir.
        Path tMarker = vendorDirectory.getChild(repo.getMarkerFileName() + ".tmp");
        FileSystemUtils.moveFile(markerUnderExternal, tMarker);
        // 3. Move the external repo to vendor dir. It's fine if this step fails or is interrupted,
        // because the marker file under external is gone anyway.
        FileSystemUtils.moveTreesBelow(repoUnderExternal, repoUnderVendor);
        // 4. Re-plant symlinks pointing a path under the external root to a relative path
        // to make sure the vendor src keep working after being moved or output base changed
        replantSymlinks(repoUnderVendor, externalRepoRoot);
        // 5. Rename the temporary marker file after the move is done.
        tMarker.renameTo(markerUnderVendor);
        // 6. Leave a symlink in external dir to keep things working.
        repoUnderExternal.deleteTree();
        FileSystemUtils.ensureSymbolicLink(repoUnderExternal, repoUnderVendor);
      }
    }
  }

  /**
   * Replants the symlinks under the specified repository directory.
   *
   * <p>Re-write symlinks that originally pointing to a path under the external root to a relative
   * path pointing to an external root symlink under the vendor directory.
   *
   * @param repoUnderVendor The path to the repository directory under the vendor directory.
   * @param externalRepoRoot The path to the root of external repositories.
   * @throws IOException If an I/O error occurs while replanting the symlinks.
   */
  private void replantSymlinks(Path repoUnderVendor, Path externalRepoRoot) throws IOException {
    try {
      Collection<Path> symlinks =
          FileSystemUtils.traverseTree(repoUnderVendor, Path::isSymbolicLink);
      Path externalSymlinkUnderVendor = vendorDirectory.getChild(EXTERNAL_ROOT_SYMLINK_NAME);
      FileSystemUtils.ensureSymbolicLink(externalSymlinkUnderVendor, externalRepoRoot);
      for (Path symlink : symlinks) {
        PathFragment target = symlink.readSymbolicLink();
        if (!target.startsWith(externalRepoRoot.asFragment())) {
          // TODO: print a warning for absolute symlinks?
          continue;
        }
        PathFragment newTarget =
            PathFragment.create(
                    "../".repeat(symlink.relativeTo(vendorDirectory).segmentCount() - 1))
                .getRelative(EXTERNAL_ROOT_SYMLINK_NAME)
                .getRelative(target.relativeTo(externalRepoRoot.asFragment()));
        if (OS.getCurrent() == OS.WINDOWS) {
          // On Windows, FileSystemUtils.ensureSymbolicLink always resolves paths to absolute path.
          // Use Files.createSymbolicLink here instead to preserve relative target path.
          symlink.delete();
          Files.createSymbolicLink(
              java.nio.file.Path.of(symlink.getPathString()),
              java.nio.file.Path.of(newTarget.getPathString()));
        } else {
          FileSystemUtils.ensureSymbolicLink(symlink, newTarget);
        }
      }
    } catch (IOException e) {
      throw new IOException(
          String.format("Failed to rewrite symlinks under %s: ", repoUnderVendor), e);
    }
  }

  /**
   * Checks if the given URL is vendored.
   *
   * @param url The URL to check.
   * @return true if the URL is vendored, false otherwise.
   * @throws UnsupportedEncodingException if the URL decoding fails.
   */
  public boolean isUrlVendored(URL url) throws UnsupportedEncodingException {
    return getVendorPathForUrl(url).isFile();
  }

  /**
   * Vendors the registry URL with the specified content.
   *
   * @param url The registry URL to vendor.
   * @param content The content to write.
   * @throws IOException if an I/O error occurs.
   */
  public void vendorRegistryUrl(URL url, byte[] content) throws IOException {
    Path outputPath = getVendorPathForUrl(url);
    Objects.requireNonNull(outputPath.getParentDirectory()).createDirectoryAndParents();
    FileSystemUtils.writeContent(outputPath, content);
  }

  /**
   * Reads the content of the registry URL and verifies its checksum.
   *
   * @param url The registry URL to read.
   * @param checksum The checksum to verify.
   * @return The content of the registry URL.
   * @throws IOException if an I/O error occurs or the checksum verification fails.
   */
  public byte[] readRegistryUrl(URL url, Checksum checksum) throws IOException {
    byte[] content = FileSystemUtils.readContent(getVendorPathForUrl(url));
    Hasher hasher = checksum.getKeyType().newHasher();
    hasher.putBytes(content);
    HashCode actual = hasher.hash();
    if (!checksum.getHashCode().equals(actual)) {
      throw new IOException(
          String.format(
              "Checksum was %s but wanted %s",
              checksum.emitOtherHashInSameFormat(actual),
              checksum.emitOtherHashInSameFormat(checksum.getHashCode())));
    }
    return content;
  }

  /**
   * Checks if the repository under vendor dir is up-to-date by comparing its marker file with the
   * one under <output_base>/external. This function assumes the marker file under
   * <output_base>/external exists and is up-to-date.
   *
   * @param markerUnderVendor The marker file path under vendor dir
   * @param markerUnderExternal The marker file path under external dir
   * @return true if the repository is up-to-date, false otherwise.
   * @throws IOException if an I/O error occurs.
   */
  private boolean isRepoUpToDate(Path markerUnderVendor, Path markerUnderExternal)
      throws IOException {
    if (!markerUnderVendor.exists()) {
      return false;
    }
    String vendorMarkerContent = FileSystemUtils.readContentToString(markerUnderVendor);
    String externalMarkerContent = FileSystemUtils.readContentToString(markerUnderExternal);
    return Objects.equals(vendorMarkerContent, externalMarkerContent);
  }

  /**
   * Returns the vendor path for the given URL.
   *
   * <p>The vendor path is constructed as follows: <vendor_directory>/registry_cache/<host>/<path>
   *
   * <p>The host name is case-insensitive, so it is converted to lowercase. The path is
   * case-sensitive, so it is left as is. The port number is not included in the vendor path.
   *
   * <p>Note that the vendor path may conflict if two URLs only differ by the case or port number.
   * But this is unlikely to happen in practice, and conflicts are checked in VendorCommand.java.
   *
   * @param url The URL to get the vendor path for.
   * @return The vendor path.
   * @throws UnsupportedEncodingException if the URL decoding fails.
   */
  public Path getVendorPathForUrl(URL url) throws UnsupportedEncodingException {
    String host = url.getHost().toLowerCase(Locale.ROOT); // Host names are case-insensitive
    String path = url.getPath();
    path = URLDecoder.decode(path, "UTF-8");
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    return vendorDirectory.getRelative(REGISTRIES_DIR).getRelative(host).getRelative(path);
  }
}
