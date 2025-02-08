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

package com.google.devtools.build.lib.starlarkdocextract;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;

/**
 * Shared settings used by documentation extractors for transforming Starlark data structures into
 * StardocOutputProtos.* protos.
 */
public record ExtractorContext(
    LabelRenderer labelRenderer,
    ImmutableMap<StarlarkProvider.Key, String> providerQualifiedNames,
    boolean extractNativelyDefinedAttrs) {

  public ExtractorContext {
    checkNotNull(labelRenderer, "labelRenderer cannot be null.");
    checkNotNull(providerQualifiedNames, "providerQualifiedNames cannot be null.");
  }

  /** Returns a new {@link Builder} instance. */
  public static Builder builder() {
    return new AutoBuilder_ExtractorContext_Builder()
        .providerQualifiedNames(ImmutableMap.of())
        .extractNativelyDefinedAttrs(false);
  }

  public Builder toBuilder() {
    return builder()
        .labelRenderer(labelRenderer)
        .providerQualifiedNames(providerQualifiedNames)
        .extractNativelyDefinedAttrs(extractNativelyDefinedAttrs);
  }

  /** Builder for {@link ExtractorContext}. */
  @AutoBuilder
  public interface Builder {
    Builder labelRenderer(LabelRenderer labelRenderer);

    Builder providerQualifiedNames(
        ImmutableMap<StarlarkProvider.Key, String> providerQualifiedNames);

    Builder extractNativelyDefinedAttrs(boolean extractNativelyDefinedAttrs);

    ExtractorContext build();
  }

  /**
   * Returns true if the name should be, by default, considered for documentation extraction or for
   * recursing into.
   */
  static boolean isPublicName(String name) {
    return name.length() > 0 && Character.isAlphabetic(name.charAt(0));
  }

  /**
   * Returns the human-readable provider name suitable for use in a given module's documentation.
   * For a provider loadable from that module, this is intended to be the qualified name (or more
   * precisely, the first qualified name) under which a user of this module may access it. For local
   * providers and for providers loaded but not re-exported via a global, it's the provider key name
   * (a.k.a. {@code provider.toString()}). For legacy struct providers, it's the legacy ID (which
   * also happens to be {@code provider.toString()}).
   */
  String getDocumentedProviderName(StarlarkProviderIdentifier provider) {
    if (!provider.isLegacy()) {
      String qualifiedName = providerQualifiedNames.get(provider.getKey());
      if (qualifiedName != null) {
        return qualifiedName;
      }
    }
    return provider.toString();
  }
}
