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
package com.google.devtools.build.lib.vfs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ConditionallyThreadSafe;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.util.StringEncoding;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** Helper functions that implement often-used complex operations on file systems. */
@ConditionallyThreadSafe
public class FileSystemUtils {

  private FileSystemUtils() {}

  /**
   * Throws exceptions if {@code baseName} is not a valid base name. A valid
   * base name:
   * <ul>
   * <li>Is not null
   * <li>Is not an empty string
   * <li>Is not "." or ".."
   * <li>Does not contain a slash
   * </ul>
   */
  @ThreadSafe
  public static void checkBaseName(String baseName) {
    if (baseName.length() == 0) {
      throw new IllegalArgumentException("Child must not be empty string ('')");
    }
    if (baseName.equals(".") || baseName.equals("..")) {
      throw new IllegalArgumentException("baseName must not be '" + baseName + "'");
    }
    if (baseName.indexOf('/') != -1) {
      throw new IllegalArgumentException("baseName must not contain a slash: '" + baseName + "'");
    }
  }

  /**
   * Returns the common ancestor between two paths, or null if none (including
   * if they are on different filesystems).
   */
  public static Path commonAncestor(Path a, Path b) {
    while (a != null && !b.startsWith(a)) {
      a = a.getParentDirectory();  // returns null at root
    }
    return a;
  }

  /**
   * Returns the longest common ancestor of the two path fragments, or either "/" or "" (depending
   * on whether {@code a} is absolute or relative) if there is none.
   */
  public static PathFragment commonAncestor(PathFragment a, PathFragment b) {
    while (a != null && !b.startsWith(a)) {
      a = a.getParentDirectory();
    }

    return a;
  }

  /**
   * Returns a path fragment from a given from-dir to a given to-path.
   */
  public static PathFragment relativePath(PathFragment fromDir, PathFragment to) {
    if (to.equals(fromDir)) {
      return PathFragment.EMPTY_FRAGMENT;
    }
    if (to.startsWith(fromDir)) {
      return to.relativeTo(fromDir);  // easy case--it's a descendant
    }
    PathFragment ancestor = commonAncestor(fromDir, to);
    if (ancestor == null) {
      return to;  // no common ancestor, use 'to'
    }
    int levels = fromDir.relativeTo(ancestor).segmentCount();
    StringBuilder dotdots = new StringBuilder();
    for (int i = 0; i < levels; i++) {
      dotdots.append("../");
    }
    return PathFragment.create(dotdots.toString()).getRelative(to.relativeTo(ancestor));
  }

  /**
   * Removes the shortest suffix beginning with '.' from the basename of the
   * filename string. If the basename contains no '.', the filename is returned
   * unchanged.
   *
   * <p>e.g. "foo/bar.x" -> "foo/bar"
   *
   * <p>Note that if the filename is composed entirely of ".", this method will return the string
   * with one fewer ".", which may have surprising effects.
   */
  @ThreadSafe
  public static String removeExtension(String filename) {
    int lastDotIndex = filename.lastIndexOf('.');
    if (lastDotIndex == -1) { return filename; }
    int lastSlashIndex = filename.lastIndexOf('/');
    if (lastSlashIndex > lastDotIndex) {
      return filename;
    }
    return filename.substring(0, lastDotIndex);
  }

  /**
   * Removes the shortest suffix beginning with '.' from the basename of the
   * PathFragment. If the basename contains no '.', the filename is returned
   * unchanged.
   *
   * <p>e.g. "foo/bar.x" -> "foo/bar"
   *
   * <p>Note that if the base filename is composed entirely of ".", this method will return the
   * filename with one fewer "." in the base filename, which may have surprising effects.
   */
  @ThreadSafe
  public static PathFragment removeExtension(PathFragment path) {
    return path.replaceName(removeExtension(path.getBaseName()));
  }

  /**
   * Removes the shortest suffix beginning with '.' from the basename of the
   * Path. If the basename contains no '.', the filename is returned
   * unchanged.
   *
   * <p>e.g. "foo/bar.x" -> "foo/bar"
   *
   * <p>Note that if the base filename is composed entirely of ".", this method will return the
   * filename with one fewer "." in the base filename, which may have surprising effects.
   */
  @ThreadSafe
  public static Path removeExtension(Path path) {
    return path.getFileSystem().getPath(removeExtension(path.asFragment()));
  }

  /**
   * Returns a new {@code PathFragment} formed by replacing the extension of the
   * last path segment of {@code path} with {@code newExtension}. Null is
   * returned iff {@code path} has zero segments.
   */
  public static PathFragment replaceExtension(PathFragment path, String newExtension) {
    return path.replaceName(removeExtension(path.getBaseName()) + newExtension);
  }

  /**
   * Returns a new {@code PathFragment} formed by replacing the extension of the last path segment
   * of {@code path} with {@code newExtension}. Null is returned iff {@code path} has zero segments
   * or it doesn't end with {@code oldExtension}.
   */
  @Nullable
  public static PathFragment replaceExtension(
      PathFragment path, String newExtension, String oldExtension) {
    String base = path.getBaseName();
    if (!base.endsWith(oldExtension)) {
      return null;
    }
    String newBase = base.substring(0, base.length() - oldExtension.length()) + newExtension;
    return path.replaceName(newBase);
  }

  /**
   * Returns a new {@code Path} formed by replacing the extension of the last path segment of {@code
   * path} with {@code newExtension}. Null is returned iff {@code path} has zero segments.
   */
  @Nullable
  public static Path replaceExtension(Path path, String newExtension) {
    PathFragment fragment = replaceExtension(path.asFragment(), newExtension);
    return fragment == null ? null : path.getFileSystem().getPath(fragment);
  }

  /**
   * Returns a new {@code PathFragment} formed by adding the extension to the last path segment of
   * {@code path}. Null is returned if {@code path} has zero segments.
   */
  public static PathFragment appendExtension(PathFragment path, String newExtension) {
    return path.replaceName(path.getBaseName() + newExtension);
  }

  /**
   * Returns a new {@code PathFragment} formed by appending the given string to the last path
   * segment of {@code path} without removing the extension.  Returns null if {@code path}
   * has zero segments.
   */
  public static PathFragment appendWithoutExtension(PathFragment path, String toAppend) {
    return path.replaceName(appendWithoutExtension(path.getBaseName(), toAppend));
  }

  /**
   * Given a string that represents a file with an extension separated by a '.' and a string
   * to append, return a string in which {@code toAppend} has been appended to {@code name}
   * before the last '.' character.  If {@code name} does not include a '.', appends {@code
   * toAppend} at the end.
   *
   * <p>For example,
   * ("libfoo.jar", "-src") ==> "libfoo-src.jar"
   * ("libfoo", "-src") ==> "libfoo-src"
   */
  private static String appendWithoutExtension(String name, String toAppend) {
    int dotIndex = name.lastIndexOf('.');
    if (dotIndex > 0) {
      String baseName = name.substring(0, dotIndex);
      String extension = name.substring(dotIndex);
      return baseName + toAppend + extension;
    } else {
      return name + toAppend;
    }
  }

  /**
   * Return the current working directory as expressed by the System property
   * 'user.dir'.
   */
  public static Path getWorkingDirectory(FileSystem fs) {
    return fs.getPath(getWorkingDirectory());
  }

  /**
   * Returns the current working directory as expressed by the System property
   * 'user.dir'. This version does not require a {@link FileSystem}.
   */
  public static PathFragment getWorkingDirectory() {
    // System properties obtained from host are encoded using sun.jnu.encoding, so reencode them to
    // the internal representation.
    // https://github.com/openjdk/jdk/blob/285385247aaa262866697ed848040f05f4d94988/src/java.base/share/native/libjava/System.c#L121
    return PathFragment.create(
        StringEncoding.platformToInternal(System.getProperty("user.dir", "/")));
  }

  /**
   * "Touches" the file or directory specified by the path, following symbolic
   * links. If it does not exist, it is created as an empty file; otherwise, the
   * time of last access is updated to the current time.
   *
   * @throws IOException if there was an error while touching the file
   */
  @ThreadSafe
  public static void touchFile(Path path) throws IOException {
    if (path.exists()) {
      path.setLastModifiedTime(Path.NOW_SENTINEL_TIME);
    } else {
      createEmptyFile(path);
    }
  }

  /**
   * Creates an empty regular file with the name of the current path, following
   * symbolic links.
   *
   * @throws IOException if the file could not be created for any reason
   *         (including that there was already a file at that location)
   */
  public static void createEmptyFile(Path path) throws IOException {
    path.getOutputStream().close();
  }

  /**
   * Creates or updates a symbolic link from 'link' to 'target'. Replaces
   * existing symbolic links with target, and skips the link creation if it is
   * already present. Will also create any missing ancestor directories of the
   * link. This method is non-atomic
   *
   * <p>Note: this method will throw an IOException if there is an unequal
   * non-symlink at link.
   *
   * @throws IOException if the creation of the symbolic link was unsuccessful
   *         for any reason.
   */
  @ThreadSafe  // but not atomic
  public static void ensureSymbolicLink(Path link, Path target) throws IOException {
    ensureSymbolicLink(link, target.asFragment());
  }

  /**
   * Creates or updates a symbolic link from 'link' to 'target'. Replaces
   * existing symbolic links with target, and skips the link creation if it is
   * already present. Will also create any missing ancestor directories of the
   * link. This method is non-atomic
   *
   * <p>Note: this method will throw an IOException if there is an unequal
   * non-symlink at link.
   *
   * @throws IOException if the creation of the symbolic link was unsuccessful
   *         for any reason.
   */
  @ThreadSafe  // but not atomic
  public static void ensureSymbolicLink(Path link, String target) throws IOException {
    ensureSymbolicLink(link, PathFragment.create(target));
  }

  /**
   * Creates or updates a symbolic link from 'link' to 'target'. Replaces
   * existing symbolic links with target, and skips the link creation if it is
   * already present. Will also create any missing ancestor directories of the
   * link. This method is non-atomic
   *
   * <p>Note: this method will throw an IOException if there is an unequal
   * non-symlink at link.
   *
   * @throws IOException if the creation of the symbolic link was unsuccessful
   *         for any reason.
   */
  @ThreadSafe  // but not atomic
  public static void ensureSymbolicLink(Path link, PathFragment target) throws IOException {
    // TODO(bazel-team): (2009) consider adding the logic for recovering from the case when
    // we have already created a parent directory symlink earlier.
    try {
      if (link.readSymbolicLink().equals(target)) {
        return;  // Do nothing if the link is already there.
      }
    } catch (IOException e) { // link missing or broken
      /* fallthru and do the work below */
    }
    if (link.isSymbolicLink()) {
      link.delete(); // Remove the symlink since it is pointing somewhere else.
    } else {
      link.getParentDirectory().createDirectoryAndParents();
    }
    try {
      link.createSymbolicLink(target);
    } catch (IOException e) {
      // Only pass on exceptions caused by a true link creation failure.
      if (!link.isSymbolicLink() ||
          !link.resolveSymbolicLinks().equals(link.getRelative(target))) {
        throw e;
      }
    }
  }

  public static ByteSource asByteSource(final Path path) {
    return new ByteSource() {
      @Override public InputStream openStream() throws IOException {
        return path.getInputStream();
      }
    };
  }

  public static ByteSink asByteSink(final Path path, final boolean append) {
    return new ByteSink() {
      @Override public OutputStream openStream() throws IOException {
        return path.getOutputStream(append);
      }
    };
  }

  public static ByteSink asByteSink(final Path path) {
    return asByteSink(path, false);
  }

  /**
   * Copies the file from location "from" to location "to", while overwriting a
   * potentially existing "to". File's last modified time, executable and
   * writable bits are also preserved.
   *
   * <p>If no error occurs, the method returns normally. If a parent directory does
   * not exist, a FileNotFoundException is thrown. An IOException is thrown when
   * other erroneous situations occur. (e.g. read errors)
   */
  @ThreadSafe  // but not atomic
  public static void copyFile(Path from, Path to) throws IOException {
    try {
      to.delete();
    } catch (IOException e) {
      throw new IOException("error copying file: "
          + "couldn't delete destination: " + e.getMessage());
    }
    try (InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream()) {
      ByteStreams.copy(in, out);
    }
    to.setLastModifiedTime(from.getLastModifiedTime()); // Preserve mtime.
    if (!from.isWritable()) {
      to.setWritable(false); // Make file read-only if original was read-only.
    }
    to.setExecutable(from.isExecutable()); // Copy executable bit.
  }

  /** Describes the behavior of a {@link #moveFile(Path, Path)} operation. */
  public enum MoveResult {
    /** The file was moved at the file system level. */
    FILE_MOVED,

    /** The file had to be copied and then deleted because the move failed. */
    FILE_COPIED,
  }

  /**
   * copyLargeBuffer is a replacement for ByteStreams.copy which uses a larger buffer. Increasing
   * the buffer size is a performance improvement when copying from/to FUSE file systems, where
   * individual requests are more costly, but can also be larger.
   */
  private static long copyLargeBuffer(InputStream from, OutputStream to) throws IOException {
    byte[] buf = new byte[1 * 1024 * 1024]; // Match libfuse3 maximum FUSE request size of 1 MB.
    long total = 0;
    while (true) {
      int r = from.read(buf);
      if (r == -1) {
        break;
      }
      to.write(buf, 0, r);
      total += r;
    }
    return total;
  }

  /**
   * Moves the file from location "from" to location "to", while overwriting a potentially existing
   * "to". If "from" is a regular file, its last modified time, executable and writable bits are
   * also preserved. Symlinks are also supported but not directories or special files.
   *
   * <p>This method is not guaranteed to be atomic. Use {@link Path#renameTo(Path)} instead.
   *
   * <p>If the move fails (usually because the "from" and "to" live in different file systems), this
   * falls back to copying the file. Note that these two operations have very different performance
   * characteristics and is why this operation reports back to the caller what actually happened.
   *
   * <p>If no error occurs, the method returns normally. If a parent directory does not exist, a
   * FileNotFoundException is thrown. {@link IOException} is thrown when other erroneous situations
   * occur. (e.g. read errors)
   *
   * @param from location of the file to move
   * @param to destination to where to move the file
   * @return a description of how the move was performed
   * @throws IOException if the move fails
   */
  @ThreadSafe // but not atomic
  public static MoveResult moveFile(Path from, Path to) throws IOException {
    // We don't try-catch here for better performance.
    to.delete();
    try {
      from.renameTo(to);
      return MoveResult.FILE_MOVED;
    } catch (IOException unused) {
      // Fallback to a copy.
      FileStatus stat = from.stat(Symlinks.NOFOLLOW);
      if (stat.isFile()) {
        try (InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream()) {
          copyLargeBuffer(in, out);
        } catch (FileAccessException e) {
          // Rules can accidentally make output non-readable, let's fix that (b/150963503)
          if (!from.isReadable()) {
            from.setReadable(true);
            try (InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream()) {
              copyLargeBuffer(in, out);
            }
          } else {
            throw e;
          }
        }
        to.setLastModifiedTime(stat.getLastModifiedTime()); // Preserve mtime.
        if (!from.isWritable()) {
          to.setWritable(false); // Make file read-only if original was read-only.
        }
        to.setExecutable(from.isExecutable()); // Copy executable bit.
      } else if (stat.isSymbolicLink()) {
        to.createSymbolicLink(from.readSymbolicLink());
      } else {
        throw new IOException("Don't know how to copy " + from);
      }
      if (!from.delete()) {
        if (!to.delete()) {
          throw new IOException("Unable to delete " + to);
        }
        throw new IOException("Unable to delete " + from);
      }
      return MoveResult.FILE_COPIED;
    }
  }

  /**
   * Copies a tool binary from one path to another, returning the target path.
   * The directory of the target path must already exist. The target copy's time
   * is set to match, as well as its read-only and executable flags. The
   * operation is skipped if the target file has the same time and size as the
   * source.
   */
  public static Path copyTool(Path source, Path target) throws IOException {
    FileStatus sourceStat = null;
    FileStatus targetStat = target.statNullable();
    if (targetStat != null) {
      // stat the source file only if we'll need the stat.
      sourceStat = source.stat(Symlinks.FOLLOW);
    }
    if (targetStat == null ||
        targetStat.getLastModifiedTime() != sourceStat.getLastModifiedTime() ||
        targetStat.getSize() != sourceStat.getSize()) {
      copyFile(source, target);
      target.setWritable(source.isWritable());
      target.setExecutable(source.isExecutable());
      target.setLastModifiedTime(source.getLastModifiedTime());
    }
    return target;
  }

  /* Directory tree operations. */

  /**
   * Returns a new collection containing all of the paths below a given root path, for which the
   * given predicate is true. Symbolic links are not followed, and may appear in the result.
   *
   * @throws IOException If the root does not denote a directory
   */
  @ThreadSafe
  public static Collection<Path> traverseTree(Path root, Predicate<Path> predicate)
      throws IOException {
    List<Path> paths = new ArrayList<>();
    traverseTree(paths, root, predicate);
    return paths;
  }

  /**
   * Populates an existing Path List, adding all of the paths below a given root path for which the
   * given predicate is true. Symbolic links are not followed, and may appear in the result.
   *
   * @throws IOException If the root does not denote a directory
   */
  @ThreadSafe
  public static void traverseTree(Collection<Path> paths, Path root, Predicate<Path> predicate)
      throws IOException {
    for (Path p : root.getDirectoryEntries()) {
      if (predicate.test(p)) {
        paths.add(p);
      }
      if (p.isDirectory(Symlinks.NOFOLLOW)) {
        traverseTree(paths, p, predicate);
      }
    }
  }

  /**
   * Copies all dir trees under a given 'from' dir to location 'to', while overwriting all files in
   * the potentially existing 'to'. Resolves symbolic links if {@code followSymlinks ==
   * Symlinks#FOLLOW}. Otherwise copies symlinks as-is.
   *
   * <p>The source and the destination must be non-overlapping, otherwise an
   * IllegalArgumentException will be thrown. This method cannot be used to copy a dir tree to a sub
   * tree of itself.
   *
   * <p>If no error occurs, the method returns normally. If the given 'from' does not exist, a
   * FileNotFoundException is thrown. An IOException is thrown when other erroneous situations
   * occur. (e.g. read errors)
   */
  @ThreadSafe
  public static void copyTreesBelow(Path from, Path to, Symlinks followSymlinks)
      throws IOException {
    if (to.startsWith(from)) {
      throw new IllegalArgumentException(to + " is a subdirectory of " + from);
    }

    Collection<Path> entries = from.getDirectoryEntries();
    for (Path entry : entries) {
      Path toPath = to.getChild(entry.getBaseName());
      if (!followSymlinks.toBoolean() && entry.isSymbolicLink()) {
        FileSystemUtils.ensureSymbolicLink(toPath, entry.readSymbolicLink());
      } else if (entry.isFile()) {
        copyFile(entry, toPath);
      } else {
        toPath.createDirectory();
        copyTreesBelow(entry, toPath, followSymlinks);
      }
    }
  }

  /**
   * Moves all dir trees under a given 'from' dir to location 'to', while overwriting
   * all files in the potentially existing 'to'. Doesn't resolve symbolic links.
   *
   * <p>The source and the destination must be non-overlapping, otherwise an
   * IllegalArgumentException will be thrown. This method cannot be used to copy
   * a dir tree to a sub tree of itself.
   *
   * <p>If no error occurs, the method returns normally. If the given 'from' does
   * not exist, a FileNotFoundException is thrown. An IOException is thrown when
   * other erroneous situations occur. (e.g. read errors)
   */
  @ThreadSafe
  public static void moveTreesBelow(Path from , Path to) throws IOException {
    if (to.startsWith(from)) {
      throw new IllegalArgumentException(to + " is a subdirectory of " + from);
    }

    // Actions can make output directories inaccessible, which would cause the move to fail.
    from.chmod(0755);

    // TODO(tjgq): Don't leave an empty directory behind.
    Collection<Path> entries = from.getDirectoryEntries();
    for (Path entry : entries) {
      if (entry.isDirectory(Symlinks.NOFOLLOW)) {
        Path subDir = to.getChild(entry.getBaseName());
        subDir.createDirectory();
        moveTreesBelow(entry, subDir);
      } else {
        Path newEntry = to.getChild(entry.getBaseName());
        moveFile(entry, newEntry);
      }
    }
  }

  /**
   * Attempts to remove a relative chain of directories under a given base. Returns {@code true} if
   * the removal was successful, and returns {@code false} if the removal fails because a directory
   * was not empty. An {@link IOException} is thrown for any other errors.
   */
  @ThreadSafe
  public static boolean removeDirectoryAndParents(Path base, PathFragment toRemove) {
    if (toRemove.isAbsolute()) {
      return false;
    }
    try {
      while (!toRemove.isEmpty()) {
        Path p = base.getRelative(toRemove);
        if (p.exists()) {
          p.delete();
        }
        toRemove = toRemove.getParentDirectory();
      }
    } catch (IOException e) {
      return false;
    }
    return true;
  }

  /**
   * Decodes the given byte array assumed to be encoded with ISO-8859-1 encoding (isolatin1).
   */
  public static char[] convertFromLatin1(byte[] content) {
    char[] latin1 = new char[content.length];
    for (int i = 0; i < latin1.length; i++) { // yeah, latin1 is this easy! :-)
      latin1[i] = (char) (0xff & content[i]);
    }
    return latin1;
  }

  /**
   * Writes lines to file using ISO-8859-1 encoding (isolatin1).
   */
  @ThreadSafe // but not atomic
  public static void writeIsoLatin1(Path file, String... lines) throws IOException {
    writeLinesAs(file, ISO_8859_1, lines);
  }

  /**
   * Append lines to file using ISO-8859-1 encoding (isolatin1).
   */
  @ThreadSafe // but not atomic
  public static void appendIsoLatin1(Path file, String... lines) throws IOException {
    appendLinesAs(file, ISO_8859_1, lines);
  }

  /**
   * Writes the specified String as ISO-8859-1 (latin1) encoded bytes to the
   * file. Follows symbolic links.
   *
   * @throws IOException if there was an error
   */
  public static void writeContentAsLatin1(Path outputFile, String content) throws IOException {
    writeContent(outputFile, ISO_8859_1, content);
  }

  /**
   * Writes the specified String using the specified encoding to the file. Follows symbolic links.
   *
   * @throws IOException if there was an error
   */
  public static void writeContent(Path outputFile, Charset charset, String content)
      throws IOException {
    asByteSink(outputFile).asCharSink(charset).write(content);
  }

  /**
   * Writes the specified byte array to the output file. Follows symbolic links.
   *
   * @throws IOException if there was an error
   */
  public static void writeContent(Path outputFile, byte[] content) throws IOException {
    asByteSink(outputFile).write(content);
  }

  /**
   * Writes lines to file using the given encoding, ending every line with a system specific line
   * break character.
   */
  @ThreadSafe // but not atomic
  public static void writeLinesAs(Path file, Charset charset, String... lines) throws IOException {
    writeLinesAs(file, charset, Arrays.asList(lines));
  }

  /**
   * Writes lines to file using the given encoding, ending every line with a system specific line
   * break character.
   */
  @ThreadSafe // but not atomic
  public static void writeLinesAs(Path file, Charset charset, Iterable<String> lines)
      throws IOException {
    file.getParentDirectory().createDirectoryAndParents();
    asByteSink(file).asCharSink(charset).writeLines(lines);
  }

  /**
   * Writes lines to file using the given encoding, ending every line with a given line break
   * character.
   */
  @ThreadSafe // but not atomic
  public static void writeLinesAs(
      Path file, Charset charset, Iterable<String> lines, String lineBreak) throws IOException {
    file.getParentDirectory().createDirectoryAndParents();
    asByteSink(file).asCharSink(charset).writeLines(lines, lineBreak);
  }

  /**
   * Appends lines to file using the given encoding, ending every line with a system specific line
   * break character.
   */
  @ThreadSafe // but not atomic
  public static void appendLinesAs(Path file, Charset charset, String... lines) throws IOException {
    appendLinesAs(file, charset, Arrays.asList(lines));
  }

  /**
   * Appends lines to file using the given encoding, ending every line with a system specific line
   * break character.
   */
  @ThreadSafe // but not atomic
  public static void appendLinesAs(Path file, Charset charset, Iterable<String> lines)
      throws IOException {
    file.getParentDirectory().createDirectoryAndParents();
    asByteSink(file, true).asCharSink(charset).writeLines(lines);
  }

  /**
   * Updates the contents of the output file if they do not match the given array, thus maintaining
   * the mtime and ctime in case of no updates. Follows symbolic links.
   *
   * <p>If the output file already exists but is unreadable, this tries to overwrite it with the new
   * contents. In other words: unreadable or missing files are considered to be non-matching.
   *
   * @throws IOException if there was an error
   */
  public static void maybeUpdateContent(Path outputFile, byte[] newContent) throws IOException {
    byte[] currentContent;
    try {
      currentContent = readContent(outputFile);
    } catch (IOException e) {
      // Ignore error per the rationale given in the docstring. Keep in mind that what we are doing
      // here is for performance reasons only so we should only break if the real action (that is,
      // the write) fails -- not any of the optimization steps.
      currentContent = null;
    }

    if (currentContent == null) {
      writeContent(outputFile, newContent);
    } else {
      if (!Arrays.equals(newContent, currentContent)) {
        if (!outputFile.isWritable()) {
          outputFile.delete();
        }
        writeContent(outputFile, newContent);
      }
    }
  }

  /**
   * Returns the entirety of the specified input stream and returns it as a char
   * array, decoding characters using ISO-8859-1 (Latin1).
   *
   * @throws IOException if there was an error
   */
  public static char[] readContentAsLatin1(InputStream in) throws IOException {
    return convertFromLatin1(ByteStreams.toByteArray(in));
  }

  /**
   * Returns the entirety of the specified file and returns it as a char array,
   * decoding characters using ISO-8859-1 (Latin1).
   *
   * @throws IOException if there was an error
   */
  public static char[] readContentAsLatin1(Path inputFile) throws IOException {
    return convertFromLatin1(readContent(inputFile));
  }

  /**
   * Returns a list of the lines in an ISO-8859-1 (Latin1) text file. If the file ends in a line
   * break, the list will contain an empty string as the last element.
   *
   * @throws IOException if there was an error
   */
  public static ImmutableList<String> readLinesAsLatin1(Path inputFile) throws IOException {
    return readLines(inputFile, ISO_8859_1);
  }

  /**
   * Returns a list of the lines in a text file in the given {@link Charset}. If the file ends in a
   * line break, the list will contain an empty string as the last element.
   *
   * @throws IOException if there was an error
   */
  public static ImmutableList<String> readLines(Path inputFile, Charset charset)
      throws IOException {
    return asByteSource(inputFile).asCharSource(charset).readLines();
  }

  /**
   * Returns the entirety of the specified file and returns it as a byte array.
   *
   * @throws IOException if there was an error
   */
  public static byte[] readContent(Path inputFile) throws IOException {
    return asByteSource(inputFile).read();
  }

  /**
   * Reads the entire file using the given charset and returns the contents as a string
   */
  public static String readContent(Path inputFile, Charset charset) throws IOException {
    return asByteSource(inputFile).asCharSource(charset).read();
  }

  /**
   * The type of {@link IOException} thrown by {@link #readWithKnownFileSize} when fewer bytes than
   * expected are read.
   */
  public static class ShortReadIOException extends IOException {
    public final Path path;
    public final int fileSize;
    public final int numBytesRead;

    private ShortReadIOException(Path path, int fileSize, int numBytesRead) {
      super("Unexpected short read from file '" + path + "' (expected " + fileSize + ", got "
          + numBytesRead + " bytes)");
      this.path = path;
      this.fileSize = fileSize;
      this.numBytesRead = numBytesRead;
    }
  }

  /**
   * The type of {@link IOException} thrown by {@link #readWithKnownFileSize} when more bytes than
   * expected could be read.
   */
  public static class LongReadIOException extends IOException {
    public final Path path;
    public final int fileSize;

    private LongReadIOException(Path path, int fileSize) {
      super("File '" + path + "' is unexpectedly longer than " + fileSize + " bytes)");
      this.path = path;
      this.fileSize = fileSize;
    }
  }

  /**
   * Reads the given file {@code path}, assumed to have size {@code fileSize}, and does a check on
   * the number of bytes read.
   *
   * <p>Use this method when you already know the size of the file. The check is intended to catch
   * issues where the filesystem incorrectly returns truncated file contents, or where an external
   * modification has concurrently truncated or appended to the file.
   *
   * @throws IOException if there was an error, or if fewer than {@code fileSize} bytes were read.
   */
  public static byte[] readWithKnownFileSize(Path path, long fileSize) throws IOException {
    Preconditions.checkArgument(fileSize >= 0, "fileSize needs to be >=0, but it is %s", fileSize);
    if (fileSize > Integer.MAX_VALUE) {
      throw new IOException("Cannot read file with size larger than 2GB");
    }
    int size = (int) fileSize;
    byte[] bytes = new byte[size];
    try (InputStream in = asByteSource(path).openBufferedStream()) {
      int read = ByteStreams.read(in, bytes, 0, size);
      if (read != size) {
        throw new ShortReadIOException(path, size, read);
      }
      int eof = in.read();
      if (eof != -1) {
        throw new LongReadIOException(path, size);
      }
    }
    return bytes;
  }

  /**
   * Returns the type of the file system path belongs to.
   */
  public static String getFileSystem(Path path) {
    return path.getFileSystem().getFileSystemType(path.asFragment());
  }

  /**
   * Returns whether the given path starts with any of the paths in the given
   * list of prefixes.
   */
  public static boolean startsWithAny(Path path, Iterable<Path> prefixes) {
    for (Path prefix : prefixes) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns whether the given path starts with any of the paths in the given
   * list of prefixes.
   */
  public static boolean startsWithAny(PathFragment path, Iterable<PathFragment> prefixes) {
    for (PathFragment prefix : prefixes) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }


  /**
   * Create a new hard link file at "linkPath" for file at "originalPath". If "originalPath" is a
   * directory, then for each entry, create link under "linkPath" recursively.
   *
   * @param linkPath The path of the new link file to be created
   * @param originalPath The path of the original file
   * @throws IOException if there was an error executing {@link Path#createHardLink}
   */
  public static void createHardLink(Path linkPath, Path originalPath) throws IOException {

    // Directory
    if (originalPath.isDirectory()) {
      for (Path originalSubpath : originalPath.getDirectoryEntries()) {
        Path linkSubpath = linkPath.getRelative(originalSubpath.relativeTo(originalPath));
        createHardLink(linkSubpath, originalSubpath);
      }
      // Other types of file
    } else {
      Path parentDir = linkPath.getParentDirectory();
      if (!parentDir.exists()) {
        parentDir.createDirectoryAndParents();
      }
      originalPath.createHardLink(linkPath);
    }
  }
}
