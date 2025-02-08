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
package com.google.devtools.build.lib.actions.cache;

import com.google.common.hash.HashingOutputStream;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.util.StreamWriter;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An ActionInput that does not actually exist on the filesystem, but can still be written to an
 * OutputStream.
 */
public abstract class VirtualActionInput implements ActionInput, StreamWriter {
  /**
   * An empty virtual artifact <b>without</b> an execpath. This is used to denote empty files in
   * runfiles and filesets.
   */
  public static final VirtualActionInput EMPTY_MARKER = new EmptyActionInput();

  /** The next unique filename suffix to use when writing to a temporary path. */
  private static final AtomicInteger TMP_SUFFIX = new AtomicInteger(0);

  /**
   * Writes a {@link VirtualActionInput} so that no reader can observe an incomplete file, even in
   * the presence of concurrent writers.
   *
   * <p>Concurrent attempts to write the same file are possible when two actions share the same
   * input, or when a single action is dynamically executed and the input is simultaneously created
   * by the local and remote branches.
   *
   * <p>This implementation works by first creating a temporary file with a unique name and then
   * renaming it into place, relying on the atomicity of {@link FileSystem#renameTo} (which is
   * guaranteed for Unix filesystems, but possibly not for Windows). Subclasses may provide a more
   * efficient implementation.
   *
   * @param execRoot the path that this input should be written inside, typically the execroot
   * @return digest of written virtual input
   * @throws IOException if we fail to write the virtual input file
   */
  @CanIgnoreReturnValue
  public byte[] atomicallyWriteRelativeTo(Path execRoot) throws IOException {
    Path outputPath = execRoot.getRelative(getExecPath());
    return atomicallyWriteTo(outputPath);
  }

  /**
   * Like {@link #atomicallyWriteRelativeTo(Path)}, but takes the full path that the input should be
   * written to.
   */
  @CanIgnoreReturnValue
  protected byte[] atomicallyWriteTo(Path outputPath) throws IOException {
    Path tmpPath =
        outputPath
            .getFileSystem()
            .getPath(
                outputPath.getPathString()
                    + ".tmp."
                    + Integer.toUnsignedString(TMP_SUFFIX.getAndIncrement()));
    tmpPath.getParentDirectory().createDirectoryAndParents();
    tmpPath.delete();
    try {
      byte[] digest = writeTo(tmpPath);
      tmpPath.renameTo(outputPath);
      tmpPath = null; // Avoid unnecessary deletion attempt.
      return digest;
    } finally {
      try {
        if (tmpPath != null) {
          // Make sure we don't leave temp files behind if we are interrupted.
          tmpPath.delete();
        }
      } catch (IOException e) {
        // Ignore.
      }
    }
  }

  @CanIgnoreReturnValue
  protected byte[] writeTo(Path target) throws IOException {
    byte[] digest;

    FileSystem fs = target.getFileSystem();
    try (OutputStream out = target.getOutputStream();
        HashingOutputStream hashingOut =
            new HashingOutputStream(fs.getDigestFunction().getHashFunction(), out)) {
      writeTo(hashingOut);
      digest = hashingOut.hash().asBytes();
    }
    // Some of the virtual inputs can be executed, e.g. embedded tools. Setting executable flag for
    // other is fine since that is only more permissive. Please note that for action outputs (e.g.
    // file write, where the user can specify executable flag), we will have artifacts which do not
    // go through this code path.
    target.setExecutable(true);
    return digest;
  }

  /**
   * Gets a {@link ByteString} representation of the fake file. Used to avoid copying if the fake
   * file is internally represented as a {@link ByteString}.
   *
   * <p>Prefer {@link #writeTo} to this method to avoid materializing the entire file in memory. The
   * return value should not be retained.
   */
  public ByteString getBytes() throws IOException {
    ByteString.Output out = ByteString.newOutput();
    writeTo(out);
    return out.toByteString();
  }

  /**
   * Returns the metadata for this input if available. Null otherwise.
   *
   * @throws IOException
   */
  public FileArtifactValue getMetadata() throws IOException {
    return null;
  }

  @Override
  public boolean isDirectory() {
    return false;
  }

  @Override
  public boolean isSymlink() {
    return false;
  }

  /**
   * In some cases, we want empty files in the runfiles tree that have no corresponding artifact. We
   * use instances of this class to represent those files.
   */
  public static final class EmptyActionInput extends VirtualActionInput {
    private static final byte[] emptyDigest = new byte[0];

    private EmptyActionInput() {}

    @Override
    public String getExecPathString() {
      throw new UnsupportedOperationException("empty virtual artifact doesn't have an execpath");
    }

    @Override
    public PathFragment getExecPath() {
      throw new UnsupportedOperationException("empty virtual artifact doesn't have an execpath");
    }

    @Override
    public byte[] atomicallyWriteRelativeTo(Path execRoot) {
      return emptyDigest;
    }

    @Override
    protected byte[] atomicallyWriteTo(Path outputPath) {
      return emptyDigest;
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
      // Write no content - it's an empty file.
    }

    @Override
    public ByteString getBytes() {
      return ByteString.EMPTY;
    }

    @Override
    public String toString() {
      return "EmptyActionInput";
    }
  }
}
