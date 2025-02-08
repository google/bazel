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

package com.google.devtools.build.execlog;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Options for execution log converter. */
public class ConverterOptions extends OptionsBase {
  private static final Splitter COLON_SPLITTER = Splitter.on(':').limit(2);

  @Option(
      name = "input",
      defaultValue = "null",
      converter = FormatAndPathConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Input log format and path.")
  public FormatAndPath input;

  @Option(
      name = "output",
      defaultValue = "null",
      converter = FormatAndPathConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Output log format and path.")
  public FormatAndPath output;

  @Option(
      name = "sort",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Whether to sort the output in a deterministic order.")
  public boolean sort;

  enum Format {
    BINARY,
    JSON,
    COMPACT
  }

  private static final ImmutableMap<String, Format> FORMAT_BY_NAME =
      Arrays.stream(Format.values())
          .collect(toImmutableMap(f -> f.name().toLowerCase(Locale.US), f -> f));

  record FormatAndPath(Format format, Path path) {
    FormatAndPath {
      requireNonNull(format, "format");
      requireNonNull(path, "path");
    }

    public static FormatAndPath of(Format format, Path path) {
      return new FormatAndPath(format, path);
    }
  }

  private static class FormatAndPathConverter extends Converter.Contextless<FormatAndPath> {

    @Override
    public FormatAndPath convert(String input) throws OptionsParsingException {
      List<String> parts = COLON_SPLITTER.splitToList(input);
      String formats =
          Arrays.stream(Format.values())
              .map(Enum::name)
              .map(String::toLowerCase)
              .collect(Collectors.joining(","));
      if (parts.size() != 2) {
        throw new OptionsParsingException(
            "Expected input to be in the form of <type>:<path>,"
                + " where <type> is one of "
                + formats
                + ".");
      }

      String format = parts.get(0);
      if (!FORMAT_BY_NAME.containsKey(format)) {
        throw new OptionsParsingException(
            "Invalid type '" + format + "' specified; valid types: " + formats);
      }

      String path = parts.get(1);
      if (path.isEmpty()) {
        throw new OptionsParsingException(
            "Empty path specified, expected input to be in the form of <type>:<path>.");
      }

      return FormatAndPath.of(FORMAT_BY_NAME.get(format), Path.of(path));
    }

    @Override
    public String getTypeDescription() {
      return "type:path, where type is one of " + String.join(" ", FORMAT_BY_NAME.keySet());
    }
  }
}
