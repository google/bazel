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
package com.google.devtools.build.docgen;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A class to contain the base definition of common BUILD rule attributes.
 */
public class PredefinedAttributes {

  /**
   * List of documentation for common attributes of *_test rules, relative to {@link
   * com.google.devtools.build.docgen}.
   */
  public static final ImmutableList<String> TEST_ATTRIBUTES_DOCFILES =
      ImmutableList.of(
          "templates/attributes/test/args.html",
          "templates/attributes/test/env.html",
          "templates/attributes/test/env_inherit.html",
          "templates/attributes/test/size.html",
          "templates/attributes/test/timeout.html",
          "templates/attributes/test/flaky.html",
          "templates/attributes/test/shard_count.html",
          "templates/attributes/test/local.html");

  /**
   * List of typical (defined by most rules, but not implicitly added to all rules) attributes
   * documentation, relative to {@link com.google.devtools.build.docgen}.
   */
  public static final ImmutableList<String> TYPICAL_ATTRIBUTES_DOCFILES =
      ImmutableList.of(
          "templates/attributes/typical/data.html",
          "templates/attributes/typical/deps.html",
          "templates/attributes/typical/licenses.html",
          "templates/attributes/typical/srcs.html");

  /**
   * List of common (implicitly added to all rules) attributes documentation, relative to {@link
   * com.google.devtools.build.docgen}.
   */
  public static final ImmutableList<String> COMMON_ATTRIBUTES_DOCFILES =
      ImmutableList.of(
          "templates/attributes/common/compatible_with.html",
          "templates/attributes/common/deprecation.html",
          "templates/attributes/common/distribs.html",
          "templates/attributes/common/exec_compatible_with.html",
          "templates/attributes/common/exec_group_compatible_with.html",
          "templates/attributes/common/exec_properties.html",
          "templates/attributes/common/features.html",
          "templates/attributes/common/package_metadata.html",
          "templates/attributes/common/restricted_to.html",
          "templates/attributes/common/tags.html",
          "templates/attributes/common/target_compatible_with.html",
          "templates/attributes/common/testonly.html",
          "templates/attributes/common/toolchains.html",
          "templates/attributes/common/visibility.html");

  /**
   * List of documentation for common attributes of *_binary rules, relative to {@link
   * com.google.devtools.build.docgen}.
   */
  public static final ImmutableList<String> BINARY_ATTRIBUTES_DOCFILES =
      ImmutableList.of(
          "templates/attributes/binary/args.html",
          "templates/attributes/binary/env.html",
          "templates/attributes/binary/output_licenses.html");

  private static ImmutableMap<String, RuleDocumentationAttribute> generateAttributeMap(
      String commonType, ImmutableList<String> filenames) {
    ImmutableMap.Builder<String, RuleDocumentationAttribute> builder = ImmutableMap.builder();
    for (String filename : filenames) {
      String name = Files.getNameWithoutExtension(filename);
      try {
        InputStream stream = PredefinedAttributes.class.getResourceAsStream(filename);
        if (stream == null) {
          throw new IllegalStateException("Resource " + filename + " not found");
        }
        String content = new String(ByteStreams.toByteArray(stream), StandardCharsets.UTF_8);
        builder.put(name, RuleDocumentationAttribute.createCommon(name, commonType, content));
      } catch (IOException e) {
        throw new IllegalStateException("Exception while reading " + filename, e);
      }
    }
    return builder.buildOrThrow();
  }

  public static final ImmutableMap<String, RuleDocumentationAttribute> TYPICAL_ATTRIBUTES =
      generateAttributeMap(DocgenConsts.TYPICAL_ATTRIBUTES, TYPICAL_ATTRIBUTES_DOCFILES);

  public static final ImmutableMap<String, RuleDocumentationAttribute> COMMON_ATTRIBUTES =
      generateAttributeMap(DocgenConsts.COMMON_ATTRIBUTES, COMMON_ATTRIBUTES_DOCFILES);

  public static final ImmutableMap<String, RuleDocumentationAttribute> BINARY_ATTRIBUTES =
      generateAttributeMap(DocgenConsts.BINARY_ATTRIBUTES, BINARY_ATTRIBUTES_DOCFILES);

  public static final ImmutableMap<String, RuleDocumentationAttribute> TEST_ATTRIBUTES =
      generateAttributeMap(DocgenConsts.TEST_ATTRIBUTES, TEST_ATTRIBUTES_DOCFILES);
}
