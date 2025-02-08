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

import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.OriginKey;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ProviderNameGroup;

/**
 * Starlark API documentation extractor for a provider group described by a rule's {@code provides}
 * or an attribute's {@code providers} declaration.
 */
final class ProviderNameGroupExtractor {
  static ProviderNameGroup buildProviderNameGroup(
      ExtractorContext context, ImmutableSet<StarlarkProviderIdentifier> providerGroup) {
    ProviderNameGroup.Builder providerNameGroupBuilder = ProviderNameGroup.newBuilder();
    for (StarlarkProviderIdentifier provider : providerGroup) {
      providerNameGroupBuilder.addProviderName(
          internalToUnicode(context.getDocumentedProviderName(provider)));
      OriginKey.Builder providerKeyBuilder =
          OriginKey.newBuilder().setName(internalToUnicode(provider.toString()));
      if (!provider.isLegacy()) {
        if (provider.getKey() instanceof StarlarkProvider.Key) {
          Label definingModule = ((StarlarkProvider.Key) provider.getKey()).getExtensionLabel();
          providerKeyBuilder.setFile(
              internalToUnicode(context.labelRenderer().render(definingModule)));
        } else if (provider.getKey() instanceof BuiltinProvider.Key) {
          providerKeyBuilder.setFile("<native>");
        }
      }
      providerNameGroupBuilder.addOriginKey(providerKeyBuilder.build());
    }
    return providerNameGroupBuilder.build();
  }

  private ProviderNameGroupExtractor() {}
}
