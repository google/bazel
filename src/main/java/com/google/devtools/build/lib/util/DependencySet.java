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

package com.google.devtools.build.lib.util;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Representation of a set of file dependencies for a given output file. There are generally one
 * input dependency and a bunch of include dependencies. The files are stored as {@code Path}s and
 * may be relative or absolute.
 *
 * <p>The serialized format read and written is equivalent and compatible with the ".d" file
 * produced by the -MM for a given out (.o) file.
 *
 * <p>The file format looks like:
 *
 * <pre>
 * {outfile}:  \
 *  {infile} \
 *   {include} \
 *   ... \
 *   {include}
 * </pre>
 *
 * @see "http://gcc.gnu.org/onlinedocs/gcc-4.2.1/gcc/Preprocessor-Options.html#Preprocessor-Options"
 */
public final class DependencySet {

  /**
   * The set of dependent files that this DependencySet embodies. They are all Path with the same
   * FileSystem A tree set is used to ensure that we write them out in a consistent order.
   */
  private final Collection<Path> dependencies = new ArrayList<>();

  private final Path root;
  private String outputFileName;

  /** Get output file name for which dependencies are included in this DependencySet. */
  public String getOutputFileName() {
    return outputFileName;
  }

  public void setOutputFileName(String outputFileName) {
    this.outputFileName = outputFileName;
  }

  /** Constructs a new empty DependencySet instance. */
  public DependencySet(Path root) {
    this.root = root;
  }

  /**
   * Gets an unmodifiable view of the set of dependencies in {@link Path} form from this
   * DependencySet instance.
   */
  public Collection<Path> getDependencies() {
    return Collections.unmodifiableCollection(dependencies);
  }

  /**
   * Adds a given collection of dependencies in Path form to this DependencySet instance. Paths are
   * converted to root-relative
   */
  @VisibleForTesting // only called from DependencySetTest
  public void addDependencies(Collection<Path> deps) {
    for (Path d : deps) {
      Preconditions.checkArgument(d.startsWith(root));
      dependencies.add(d);
    }
  }

  /** Adds a given dependency to this DependencySet instance. */
  private void addDependency(String dep) {
    dep = translatePath(dep);
    Path depPath = root.getRelative(dep);
    dependencies.add(depPath);
  }

  private String translatePath(String path) {
    if (OS.getCurrent() != OS.WINDOWS) {
      return path;
    }
    return WindowsPath.removeWorkspace(WindowsPath.translateWindowsPath(path));
  }

  /** Reads a dotd file into this DependencySet instance. */
  public DependencySet read(Path dotdFile) throws IOException {
    byte[] content = FileSystemUtils.readContent(dotdFile);
    try {
      return process(content);
    } catch (IOException e) {
      throw new IOException("Error processing " + dotdFile + ": " + e.getMessage());
    }
  }

  /**
   * Parses a .d file.
   *
   * <p>Performance-critical! In large C++ builds there are lots of .d files to read, and some of
   * them reach into hundreds of kilobytes.
   */
  @CanIgnoreReturnValue
  public DependencySet process(byte[] content) throws IOException {
    final int n = content.length;
    if (n > 0 && content[n - 1] != '\n') {
      throw new IOException("File does not end in a newline");
      // From now on, we can safely peek ahead one character when not at a newline.
    }
    // Our write position in content[]; we use the prefix as working space to build strings.
    int w = 0;
    // Have we seen a leading "mumble.o:" on this line yet?  If not, we ignore
    // any dependencies we parse.  This is bug-for-bug compatibility with our
    // MSVC wrapper, which generates invalid .d files :(
    boolean sawTarget = false;
    for (int r = 0; r < n; ) {
      final byte c = content[r++];
      switch (c) {
        case ' ':
          // If we haven't yet seen the colon delimiting the target name,
          // keep scanning.  We do this to cope with "foo.o : \" which is
          // valid Makefile syntax produced by the cuda compiler.
          if (sawTarget && w > 0) {
            addDependency(new String(content, 0, w, ISO_8859_1));
            w = 0;
          }
          continue;

        case '\r':
          // Ignore, should be followed by a \n.
          continue;

        case '\n':
          // This closes a filename.
          // (Arguably if !sawTarget && w > 0 we should report an error,
          // as that suggests the .d file is malformed.)
          if (sawTarget && w > 0) {
            addDependency(new String(content, 0, w, ISO_8859_1));
          }
          w = 0;
          sawTarget = false; // reset for new line
          continue;

        case ':':
          // Normally this indicates the target name, but it might be part of a
          // filename on Windows.  Peek ahead at the next character.
          switch (content[r]) {
            case ' ':
            case '\n':
            case '\r':
              if (w > 0) {
                outputFileName = new String(content, 0, w, ISO_8859_1);
                w = 0;
                sawTarget = true;
              }
              continue;
            default:
              content[w++] = c; // copy a colon to filename
              continue;
          }

        case '\\':
          // Peek ahead at the next character.
          switch (content[r]) {
              // Backslashes are taken literally except when followed by whitespace.
              // See the Windows tests for some of the nonsense we have to tolerate.
            case ' ':
              content[w++] = ' '; // copy a space to the filename
              ++r; // skip over the space
              continue;
            case '\n':
              ++r; // skip over the newline
              continue;
            case '\r':
              // One backslash can escape \r\n, so peek one more character.
              if (content[++r] == '\n') {
                ++r;
              }
              continue;
            default:
              content[w++] = c; // copy a backlash to the filename
              continue;
          }

        case '$':
          if (content[r] == '$') {
            content[w++] = '$';
            ++r;
            continue;
          }
          // I don't think this can ever happen, but fall through nevertheless...

        default:
          content[w++] = c;
      }
    }
    return this;
  }

  /**
   * Writes this DependencySet object for a specified output file under the root dir, and with a
   * given suffix.
   */
  public void write(Path outFile, String suffix) throws IOException {
    Path dotdFile =
        outFile.getRelative(FileSystemUtils.replaceExtension(outFile.asFragment(), suffix));

    try (PrintStream out = new PrintStream(dotdFile.getOutputStream())) {
      out.print(outFile.relativeTo(root) + ": ");
      for (Path d : dependencies) {
        out.print(" \\\n  " + d.getPathString()); // should already be root relative
      }
      out.println();
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof DependencySet dependencySet
        && dependencySet.dependencies.equals(dependencies);
  }

  @Override
  public int hashCode() {
    return dependencies.hashCode();
  }

  private static final class WindowsPath {
    private static final AtomicReference<String> UNIX_ROOT = new AtomicReference<>(null);

    private static final Pattern EXECROOT_BASE_HEADER_PATTERN =
        Pattern.compile(".*execroot[\\\\/](?<headerPath>.*)");

    private static String removeWorkspace(String path) {
      Matcher m = EXECROOT_BASE_HEADER_PATTERN.matcher(path);
      if (m.matches()) {
        path = "../" + m.group("headerPath");
      }
      return path;
    }

    private static String translateWindowsPath(String path) {
      int n = path.length();
      if (n == 0 || path.charAt(0) != '/') {
        return path;
      }
      if (n >= 2 && isAsciiLetter(path.charAt(1)) && (n == 2 || path.charAt(2) == '/')) {
        return Ascii.toUpperCase(path.charAt(1)) + ":/" + path.substring(2);
      } else {
        String unixRoot = getUnixRoot();
        return unixRoot + path;
      }
    }

    private static boolean isAsciiLetter(char c) {
      return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static String getUnixRoot() {
      String value = UNIX_ROOT.get();
      if (value == null) {
        String jvmFlag = "bazel.windows_unix_root";
        value = determineUnixRoot(jvmFlag);
        if (value == null) {
          throw new IllegalStateException(
              String.format(
                  "\"%1$s\" JVM flag is not set. Use the --host_jvm_args flag. "
                      + "For example: "
                      + "\"--host_jvm_args=-D%1$s=c:/msys64\".",
                  jvmFlag));
        }
        value = value.replace('\\', '/');
        if (value.length() > 3 && value.endsWith("/")) {
          value = value.substring(0, value.length() - 1);
        }
        UNIX_ROOT.set(value);
      }
      return value;
    }

    @Nullable
    private static String determineUnixRoot(String jvmArgName) {
      // Get the path from a JVM flag, if specified.
      String path = StringEncoding.platformToInternal(System.getProperty(jvmArgName));
      if (path == null) {
        return null;
      }
      path = path.trim();
      if (path.isEmpty()) {
        return null;
      }
      return path;
    }
  }
}
