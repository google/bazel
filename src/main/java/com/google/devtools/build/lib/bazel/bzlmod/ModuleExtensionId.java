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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.collect.Comparators.emptiesFirst;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A unique identifier for a {@link ModuleExtension}. */
@AutoCodec
public record ModuleExtensionId(
    Label bzlFileLabel, String extensionName, Optional<IsolationKey> isolationKey) {
  public ModuleExtensionId {
    requireNonNull(bzlFileLabel, "bzlFileLabel");
    requireNonNull(extensionName, "extensionName");
    requireNonNull(isolationKey, "isolationKey");
  }

  public static final Comparator<ModuleExtensionId> LEXICOGRAPHIC_COMPARATOR =
      comparing(ModuleExtensionId::bzlFileLabel)
          .thenComparing(ModuleExtensionId::extensionName)
          .thenComparing(
              ModuleExtensionId::isolationKey, emptiesFirst(IsolationKey.LEXICOGRAPHIC_COMPARATOR));

  /**
   * A unique identifier for a single isolated usage of a fixed module extension.
   *
   * @param module The module which contains this isolated usage of a module extension.
   * @param usageExportedName The exported name of the first extension proxy for this usage.
   */
  @AutoCodec
  record IsolationKey(ModuleKey module, String usageExportedName) {
    IsolationKey {
      requireNonNull(module, "module");
      requireNonNull(usageExportedName, "usageExportedName");
    }

    static final Comparator<IsolationKey> LEXICOGRAPHIC_COMPARATOR =
        comparing(IsolationKey::module, ModuleKey.LEXICOGRAPHIC_COMPARATOR)
            .thenComparing(IsolationKey::usageExportedName);

    public static IsolationKey create(ModuleKey module, String usageExportedName) {
      return new IsolationKey(module, usageExportedName);
    }

    @Override
    public String toString() {
      return module() + "+" + usageExportedName();
    }

    public static IsolationKey fromString(String s) throws Version.ParseException {
      List<String> isolationKeyParts = Splitter.on("+").splitToList(s);
      return ModuleExtensionId.IsolationKey.create(
          ModuleKey.fromString(isolationKeyParts.get(0)), isolationKeyParts.get(1));
    }
  }

  public static ModuleExtensionId create(
      Label bzlFileLabel, String extensionName, Optional<IsolationKey> isolationKey) {
    return new ModuleExtensionId(bzlFileLabel, extensionName, isolationKey);
  }

  public boolean isInnate() {
    return extensionName().contains(" ");
  }

  @Override
  public String toString() {
    String isolationKeyPart = isolationKey().map(key -> "%" + key).orElse("");
    return String.format(
        "%s%%%s%s",
        bzlFileLabel().getUnambiguousCanonicalForm(), extensionName(), isolationKeyPart);
  }
}
