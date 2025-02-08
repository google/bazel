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
package com.google.devtools.build.lib.actions;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.GccParamFileEscaper;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.util.WindowsParamFileEscaper;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Support for parameter file generation (as used by gcc and other tools, e.g. {@code
 * gcc @param_file}. Note that the parameter file needs to be explicitly deleted after use.
 * Different tools require different parameter file formats, which can be selected via the {@link
 * ParameterFileType} enum.
 *
 * <p>The default charset is ISO-8859-1 (latin1). This also has to match the expectation of the
 * tool.
 *
 * <p>Don't use this class for new code. Use the ParameterFileWriteAction instead!
 */
public class ParameterFile {

  public static final StringUnsafe STRING_UNSAFE = StringUnsafe.getInstance();

  /** Different styles of parameter files. */
  public enum ParameterFileType {
    /**
     * A parameter file with every parameter on a separate line. This format cannot handle newlines
     * in parameters. It is currently used for most tools, but may not be interpreted correctly if
     * parameters contain white space or other special characters. It should be avoided for new
     * development.
     */
    UNQUOTED,

    /**
     * A parameter file where each parameter is correctly quoted for shell use, and separated by
     * white space (space, tab, newline). This format is safe for all characters, but must be
     * specially supported by the tool. In particular, it must not be used with gcc and related
     * tools, which do not support this format as it is.
     */
    SHELL_QUOTED,

    /**
     * A parameter file where each parameter is correctly quoted for gcc or clang use, and separated
     * by white space (space, tab, newline).
     */
    GCC_QUOTED,

    /**
     * A parameter file where each parameter is correctly quoted for windows use. Double-quotes are
     * escaped, and each parameter that contains whitespace is surrounded in double-quotes.
     */
    WINDOWS,
  }

  public static final FileType PARAMETER_FILE = FileType.of(".params");

  /** Creates a parameter file with the given parameters. */
  private ParameterFile() {}

  /** Derives a path from a given path by appending <code>".params"</code>. */
  public static PathFragment derivePath(PathFragment original) {
    return derivePath(original, "2");
  }

  /** Derives a path from a given path by appending <code>".params"</code>. */
  public static PathFragment derivePath(PathFragment original, String flavor) {
    return original.replaceName(original.getBaseName() + "-" + flavor + ".params");
  }

  /** Writes an argument list to a parameter file. */
  public static void writeParameterFile(
      OutputStream out, Iterable<String> arguments, ParameterFileType type) throws IOException {
    OutputStream bufferedOut = new BufferedOutputStream(out);
    switch (type) {
      case SHELL_QUOTED -> writeContent(bufferedOut, ShellEscaper.escapeAll(arguments));
      case GCC_QUOTED -> writeContent(bufferedOut, GccParamFileEscaper.escapeAll(arguments));
      case UNQUOTED -> writeContent(bufferedOut, arguments);
      case WINDOWS -> writeContent(bufferedOut, WindowsParamFileEscaper.escapeAll(arguments));
    }
  }

  private static void writeContent(OutputStream out, Iterable<String> arguments)
      throws IOException {
    for (String line : arguments) {
      out.write(STRING_UNSAFE.getInternalStringBytes(line));
      out.write('\n');
    }
    out.flush();
  }

  /** Criterion shared by {@link #flagsOnly} and {@link #nonFlags}. */
  private static boolean isFlag(String arg) {
    return arg.startsWith("--");
  }

  /**
   * Filters the given args to only flags (i.e. start with "--").
   *
   * <p>Note, this makes sense only if flags with values have previously been joined,
   * e.g."--foo=bar" rather than "--foo", "bar".
   */
  public static Iterable<String> flagsOnly(Iterable<String> args) {
    return Iterables.filter(args, ParameterFile::isFlag);
  }

  /**
   * * Filters the given args to only non-flags (i.e. do not start with "--").
   *
   * <p>Note, this makes sense only if flags with values have previously been joined,
   * e.g."--foo=bar" rather than "--foo", "bar".
   */
  public static Iterable<String> nonFlags(Iterable<String> args) {
    return Iterables.filter(args, arg -> !isFlag(arg));
  }
}
