// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.exec;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hasher;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

/**
 * Maintains a mapping between relative path (from the execution root) to {@link ActionInput}, for
 * various auxiliary binaries used during action execution (alarm. etc).
 */
public final class BinTools {
  private final Path embeddedBinariesRoot;
  private final ImmutableList<String> embeddedTools;
  private final ImmutableMap<String, ActionInput> actionInputs;

  private BinTools(BlazeDirectories directories, ImmutableList<String> tools) {
    this(directories.getEmbeddedBinariesRoot(), tools);
  }

  private BinTools(Path embeddedBinariesRoot, ImmutableList<String> tools) {
    this.embeddedBinariesRoot = embeddedBinariesRoot;
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    // Files under embedded_tools shouldn't be copied to under _bin dir
    // They won't be used during action execution time.
    for (String tool : tools) {
      if (!tool.startsWith("embedded_tools/")) {
        builder.add(tool);
      }
    }
    this.embeddedTools = builder.build();

    ImmutableMap.Builder<String, ActionInput> result = ImmutableMap.builder();
    for (String embeddedPath : embeddedTools) {
      Path path = getEmbeddedPath(embeddedPath);
      PathFragment execPath =  PathFragment.create("_bin").getRelative(embeddedPath);
      result.put(embeddedPath, new PathActionInput(path, execPath));
    }
    actionInputs = result.buildOrThrow();
  }


  /**
   * Creates an instance with the list of embedded tools obtained from scanning the directory
   * into which said binaries were extracted by the launcher.
   */
  public static BinTools forProduction(BlazeDirectories directories) throws IOException {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    scanDirectoryRecursively(builder, directories.getEmbeddedBinariesRoot(), "");
    return new BinTools(directories, builder.build());
  }

  /**
   * Creates an empty instance for testing.
   */
  @VisibleForTesting
  public static BinTools empty(BlazeDirectories directories) {
    return new BinTools(directories, ImmutableList.of());
  }

  /**
   * Creates an instance for testing with the given embedded binaries root.
   */
  @VisibleForTesting
  public static BinTools forEmbeddedBin(Path embeddedBinariesRoot, Iterable<String> tools) {
    return new BinTools(embeddedBinariesRoot, ImmutableList.copyOf(tools));
  }

  /**
   * Creates an instance for testing without actually symlinking the tools.
   *
   * <p>Used for tests that need a set of embedded tools to be present, but not the actual files.
   */
  @VisibleForTesting
  public static BinTools forUnitTesting(BlazeDirectories directories, Iterable<String> tools) {
    return new BinTools(directories, ImmutableList.copyOf(tools));
  }

  /**
   * Creates an instance for testing without actually symlinking the tools.
   *
   * <p>Used for tests that need a set of embedded tools to be present, but not the actual files.
   */
  @VisibleForTesting
  public static BinTools forUnitTesting(Path execroot, Iterable<String> tools) {
    return new BinTools(execroot.getRelative("/fake/embedded/tools"), ImmutableList.copyOf(tools));
  }

  /**
   * Returns a BinTools instance. Before calling this method, you have to populate the
   * {@link BlazeDirectories#getEmbeddedBinariesRoot} directory.
   */
  @VisibleForTesting
  public static BinTools forIntegrationTesting(
      BlazeDirectories directories, Iterable<String> tools) {
    return new BinTools(directories, ImmutableList.copyOf(tools));
  }

  private static void scanDirectoryRecursively(
      ImmutableList.Builder<String> result, Path root, String relative) throws IOException {
    for (Dirent dirent : root.readdir(Symlinks.NOFOLLOW)) {
      String childRelative = relative.isEmpty()
          ? dirent.getName()
          : relative + "/" + dirent.getName();
      switch (dirent.getType()) {
        case FILE -> result.add(childRelative);
        case DIRECTORY ->
            scanDirectoryRecursively(result, root.getChild(dirent.getName()), childRelative);
        default -> {
          // Nothing to do here -- we ignore symlinks, since they should not be present in the
          // embedded binaries tree.
        }
      }
    }
  }

  /**
   * Returns an action input for the given embedded tool.
   */
  public ActionInput getActionInput(String embeddedPath) {
    return actionInputs.get(embeddedPath);
  }

  @Nullable
  public Path getEmbeddedPath(String embedPath) {
    if (!embeddedTools.contains(embedPath)) {
      return null;
    }
    return embeddedBinariesRoot.getRelative(embedPath);
  }

  /** An ActionInput pointing at an absolute path. */
  @VisibleForTesting
  public static final class PathActionInput extends VirtualActionInput {
    private final ReentrantLock lock = new ReentrantLock();
    private final Path path;
    private final PathFragment execPath;
    private volatile FileArtifactValue metadata;

    /** Contains the digest of the input once it has been written. */
    private volatile byte[] digest;

    public PathActionInput(Path path, PathFragment execPath) {
      this.path = path;
      this.execPath = execPath;
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
      try (InputStream in = path.getInputStream()) {
        ByteStreams.copy(in, out);
      }
    }

    @Override
    @CanIgnoreReturnValue
    protected byte[] atomicallyWriteTo(Path outputPath) throws IOException {
      // The embedded tools do not change, but we need to be sure they're written out without race
      // conditions. We rely on the fact that no two {@link PathActionInput} instances refer to the
      // same file to use in-memory synchronization and avoid writing to a temporary file first.
      if (digest == null || !outputPath.exists()) {
        lock.lock();
        try {
          if (digest == null || !outputPath.exists()) {
            outputPath.getParentDirectory().createDirectoryAndParents();
            digest = writeTo(outputPath);
            // Some of the embedded tools are executable.
            outputPath.setExecutable(true);
          }
        } finally {
          lock.unlock();
        }
      }
      return digest;
    }

    @Override
    public FileArtifactValue getMetadata() throws IOException {
      // We intentionally delay hashing until it is necessary.
      if (metadata == null) {
        lock.lock();
        try {
          if (metadata == null) {
            metadata = hash(path);
          }
        } finally {
          lock.unlock();
        }
      }
      return metadata;
    }

    private static FileArtifactValue hash(Path path) throws IOException {
      DigestHashFunction hashFn = path.getFileSystem().getDigestFunction();
      Hasher hasher = hashFn.getHashFunction().newHasher();
      int bytesCopied = 0;
      try (InputStream in = path.getInputStream()) {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = in.read(buffer)) > 0) {
          hasher.putBytes(buffer, 0, len);
          bytesCopied += len;
        }
      }
      return FileArtifactValue.createForVirtualActionInput(
          hasher.hash().asBytes(),
          bytesCopied);
    }

    @Override
    public String getExecPathString() {
      return execPath.getPathString();
    }

    @Override
    public PathFragment getExecPath() {
      return execPath;
    }
  }
}
