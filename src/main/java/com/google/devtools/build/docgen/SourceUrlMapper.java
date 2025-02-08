// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.docgen;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/**
 * Given a Build Encyclopedia generator tool's input filename or label, the mapper produces the URL
 * for the corresponding source file in the source code repository.
 */
class SourceUrlMapper {
  private final String sourceUrlRoot;
  private final Path inputRoot;
  private final ImmutableMap<String, String> repoPathsRewrites;

  /**
   * @param sourceUrlRoot root URL for the source code repository
   * @param inputRoot input directory corresponding to the source tree root
   * @param repoPathsRewrites an ordered map of repo path prefixes; A repo path is a string formed
   *     by concatenation of @repo// and a path. This handles labels from @builtins as well as from
   *     external * repositories. A map entry of the form "foo" -> "bar" indicates that if a repo
   *     path starts with "foo", that prefix should be replaced with "bar" to form a full url.
   */
  SourceUrlMapper(
      String sourceUrlRoot, String inputRoot, ImmutableMap<String, String> repoPathsRewrites) {
    this.sourceUrlRoot = sourceUrlRoot;
    this.inputRoot = new File(inputRoot).toPath();
    this.repoPathsRewrites = repoPathsRewrites;
  }

  SourceUrlMapper(DocLinkMap linkMap, String inputRoot) {
    this(linkMap.sourceUrlRoot, inputRoot, ImmutableMap.copyOf(linkMap.repoPathRewrites));
  }

  /**
   * Returns the source code repository URL of a Java source file which was passed as an input to
   * the Build Encyclopedia generator tool.
   *
   * @throws InvalidArgumentException if the URL could not be produced.
   */
  String urlOfFile(File file) {
    Path path = file.toPath();
    Preconditions.checkArgument(
        path.startsWith(inputRoot), "File '%s' is expected to be under '%s'", path, inputRoot);
    return sourceUrlRoot + inputRoot.toUri().relativize(path.toUri());
  }

  /**
   * Returns the source code repository URL of a .bzl file label which was passed as an input to the
   * Build Encyclopedia generator tool.
   *
   * <p>A label is first rewritten via {@link repoPathsRewrites}: an entry of the form "foo" ->
   * "bar" means that if {@code labelString} starts with "foo", the "foo" prefix is replaced with
   * "bar". Rewrite rules in {@link repoPathsRewrites} are examined in order, and only the first
   * matching rewrite is applied.
   *
   * <p>If the result is a label in the main repo, the (possibly rewritten) label is transformed
   * into a URL.
   *
   * @throws InvalidArgumentException if the URL could not be produced.
   */
  String urlOfLabel(String labelString) {
    Label label;
    try {
      label = Label.parseCanonical(labelString);
    } catch (LabelSyntaxException e) {
      String message = String.format("Failed to parse label '%s'", labelString);
      throw new IllegalArgumentException(message, e);
    }
    return urlOfLabel(label);
  }

  private String urlOfLabel(Label label) {
    String path =
        "@"
            + label.getPackageIdentifier().getRepository().getName()
            + "//"
            + label.toPathFragment().getPathString();
    for (Map.Entry<String, String> entry : repoPathsRewrites.entrySet()) {
      if (path.startsWith(entry.getKey())) {
        return entry.getValue() + path.substring(entry.getKey().length());
      }
    }
    throw new IllegalStateException("Label URL left untransformed: " + path);
  }
}
