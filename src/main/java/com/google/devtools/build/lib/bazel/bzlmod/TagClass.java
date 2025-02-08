// Copyright 2021 The Bazel Authors. All rights reserved.
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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryModuleApi.TagClassApi;
import java.util.Optional;

/**
 * Represents a tag class, which is a "class" of {@link Tag}s that share the same attribute schema.
 *
 * @param attributes The list of attributes of this tag class.
 * @param doc Documentation about this tag class.
 * @param attributeIndices A mapping from the <em> public </em> name of an attribute to the position
 *     of said attribute in {@link #getAttributes}.
 */
public record TagClass(
    ImmutableList<Attribute> attributes,
    Optional<String> doc,
    ImmutableMap<String, Integer> attributeIndices)
    implements TagClassApi {
  public TagClass {
    requireNonNull(attributes, "attributes");
    requireNonNull(doc, "doc");
    requireNonNull(attributeIndices, "attributeIndices");
  }

  public static TagClass create(ImmutableList<Attribute> attributes, Optional<String> doc) {
    ImmutableMap.Builder<String, Integer> attributeIndicesBuilder =
        ImmutableMap.builderWithExpectedSize(attributes.size());
    for (int i = 0; i < attributes.size(); i++) {
      attributeIndicesBuilder.put(attributes.get(i).getPublicName(), i);
    }
    return new TagClass(attributes, doc, attributeIndicesBuilder.buildOrThrow());
  }
}
