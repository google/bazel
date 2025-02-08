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

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.Path;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Class for filtering the output of /showIncludes from MSVC compiler.
 *
 * <p>A discovered header file will be printed with prefix "Note: including file:", the path is
 * collected, and the line is suppressed from the actual output users can see.
 *
 * <p>Also suppress the basename of source file, which is printed unconditionally by MSVC compiler,
 * there is no way to turn it off.
 */
public class ShowIncludesFilter {

  private FilterShowIncludesOutputStream filterShowIncludesOutputStream;
  private final String sourceFileName;

  public ShowIncludesFilter(String sourceFileName) {
    this.sourceFileName = sourceFileName;
  }

  /**
   * Use this class to filter and collect the headers discovered by MSVC compiler, also filter out
   * the source file name printed unconditionally by the compiler.
   */
  public static class FilterShowIncludesOutputStream extends FilterOutputStream {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
    private final Collection<String> dependencies = new ArrayList<>();
    private static final int NEWLINE = '\n';
    // "Note: including file:" in 14 languages,
    // cl.exe will print different prefix according to the locale configured for MSVC.
    private static final ImmutableList<String> SHOW_INCLUDES_PREFIXES =
        ImmutableList.of(
                // English
                "Note: including file:",
                // Traditional Chinese
                "注意: 包含檔案:",
                // Czech
                "Poznámka: Včetně souboru:",
                // German
                "Hinweis: Einlesen der Datei:",
                // French (non-breaking spaces before the colons)
                "Remarque : inclusion du fichier :",
                // Italian (the missing : is intentional, this appears to be a bug in MSVC)
                "Nota: file incluso",
                // Japanese
                "メモ: インクルード ファイル:",
                // Korean
                "참고: 포함 파일:",
                // Polish
                "Uwaga: w tym pliku:",
                // Portuguese
                "Observação: incluindo arquivo:",
                // Russian
                "Примечание: включение файла:",
                // Turkish
                "Not: eklenen dosya:",
                // Simplified Chinese
                "注意: 包含文件:",
                // Spanish
                "Nota: inclusión del archivo:")
            .stream()
            .map(StringEncoding::unicodeToInternal)
            .collect(toImmutableList());
    private final String sourceFileName;
    private boolean sawPotentialUnsupportedShowIncludesLine;
    // Grab everything under the execroot base so that external repository header files are covered
    // in the sibling repository layout.
    private static final Pattern EXECROOT_BASE_HEADER_PATTERN =
        Pattern.compile(".*execroot\\\\(?<headerPath>.*)");
    // Match a line of the form "fooo: bar:   C:\some\path\file.h". As this is relatively generic,
    // we require the line to include an absolute path with drive letter. If remote workers rewrite
    // the path to a relative one, we won't match it, but it is unlikely that such setups use an
    // unsupported encoding. We also exclude any matches that contain numbers: MSVC warnings and
    // errors always contain numbers, but the /showIncludes output doesn't in any encoding since all
    // codepages are ASCII-compatible.
    private static final Pattern POTENTIAL_UNSUPPORTED_SHOW_INCLUDES_LINE =
        Pattern.compile("[^:0-9]+:\\s+[^:0-9]+:\\s+[A-Za-z]:\\\\[^:]*\\\\execroot\\\\[^:]*");

    public FilterShowIncludesOutputStream(OutputStream out, String sourceFileName) {
      super(out);
      this.sourceFileName = sourceFileName;
    }

    @Override
    public void write(int b) throws IOException {
      buffer.write(b);
      if (b == NEWLINE) {
        String line = buffer.toString(ISO_8859_1);
        boolean prefixMatched = false;
        for (String prefix : SHOW_INCLUDES_PREFIXES) {
          if (line.startsWith(prefix)) {
            line = line.substring(prefix.length()).trim();
            Matcher m = EXECROOT_BASE_HEADER_PATTERN.matcher(line);
            if (m.matches()) {
              // Prefix the matched header path with "..\". This way, external repo header paths are
              // resolved to "<execroot>\..\<repo name>\<path>", and main repo file paths are
              // resolved to "<execroot>\..\<main repo>\<path>", which is nicely normalized to
              // "<execroot>\<path>".
              line = "..\\" + m.group("headerPath");
            }
            dependencies.add(line);
            prefixMatched = true;
            break;
          }
        }
        // cl.exe also prints out the file name unconditionally, we need to also filter it out.
        if (!prefixMatched && !line.trim().equals(sourceFileName)) {
          // When the toolchain definition failed to force an English locale, /showIncludes lines
          // can use non-UTF8 encodings, which the checks above fail to detect. As this results in
          // incorrect incremental builds, we emit a warning if the raw byte sequence comprising the
          // line looks like it could be a /showIncludes line.
          if (POTENTIAL_UNSUPPORTED_SHOW_INCLUDES_LINE.matcher(line.trim()).matches()) {
            sawPotentialUnsupportedShowIncludesLine = true;
          }
          buffer.writeTo(out);
        }
        buffer.reset();
      }
    }

    @Override
    public void flush() throws IOException {
      String line = buffer.toString(ISO_8859_1);

      // If this line starts or could start with a prefix.
      boolean startingWithAnyPrefix = false;
      for (String prefix : SHOW_INCLUDES_PREFIXES) {
        if (line.startsWith(prefix) || prefix.startsWith(line)) {
          startingWithAnyPrefix = true;
          break;
        }
      }

      if (!startingWithAnyPrefix
          // If this line starts or could start with the source file name.
          && !line.startsWith(sourceFileName)
          && !sourceFileName.startsWith(line)) {
        buffer.writeTo(out);
        buffer.reset();
      }
      out.flush();
    }

    public Collection<String> getDependencies() {
      return this.dependencies;
    }

    public boolean sawPotentialUnsupportedShowIncludesLine() {
      return sawPotentialUnsupportedShowIncludesLine;
    }
  }

  public FilterOutputStream getFilteredOutputStream(OutputStream outputStream) {
    filterShowIncludesOutputStream =
        new FilterShowIncludesOutputStream(outputStream, sourceFileName);
    return filterShowIncludesOutputStream;
  }

  public Collection<Path> getDependencies(Path root) {
    Collection<Path> dependenciesInPath = new ArrayList<>();
    if (filterShowIncludesOutputStream != null) {
      for (String dep : filterShowIncludesOutputStream.getDependencies()) {
        dependenciesInPath.add(root.getRelative(dep));
      }
    }
    return Collections.unmodifiableCollection(dependenciesInPath);
  }

  public boolean sawPotentialUnsupportedShowIncludesLine() {
    return filterShowIncludesOutputStream != null
        && filterShowIncludesOutputStream.sawPotentialUnsupportedShowIncludesLine();
  }

  @VisibleForTesting
  Collection<String> getDependencies() {
    return filterShowIncludesOutputStream.getDependencies();
  }
}
