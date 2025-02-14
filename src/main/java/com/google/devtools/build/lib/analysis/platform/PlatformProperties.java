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
package com.google.devtools.build.lib.analysis.platform;

import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import javax.annotation.Nullable;

/** Proepeties set on a specific {@link PlatformInfo}. */
@AutoValue
public abstract class PlatformProperties {
  abstract ImmutableMap<String, String> properties();

  public boolean isEmpty() {
    return properties().isEmpty();
  }

  /** Returns a new {@link Builder} for creating a fresh {@link PlatformProperties} instance. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder class to facilitate creating valid {@link PlatformProperties} instances. */
  public static final class Builder {
    @Nullable private PlatformProperties parent = null;
    private ImmutableMap<String, String> properties = ImmutableMap.of();

    @CanIgnoreReturnValue
    public Builder setParent(@Nullable PlatformProperties parent) {
      this.parent = parent;
      return this;
    }

    /** Returns the current properties (but not any from the parent), for validation. */
    ImmutableMap<String, String> getProperties() {
      return this.properties;
    }

    @CanIgnoreReturnValue
    public Builder setProperties(Map<String, String> properties) {
      this.properties = ImmutableMap.copyOf(properties);
      return this;
    }

    public PlatformProperties build() {
      ImmutableMap<String, String> properties = mergeParent(parent, this.properties);

      return new AutoValue_PlatformProperties(properties);
    }

    @Nullable
    private static ImmutableMap<String, String> mergeParent(
        PlatformProperties parent, ImmutableMap<String, String> properties) {
      if (parent == null || parent.isEmpty()) {
        return properties;
      }

      SequencedMap<String, String> result = new LinkedHashMap<>();
      if (!parent.properties().isEmpty()) {
        result.putAll(parent.properties());
      }

      if (!properties.isEmpty()) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
          if (Strings.isNullOrEmpty(entry.getValue())) {
            result.remove(entry.getKey());
          } else {
            result.put(entry.getKey(), entry.getValue());
          }
        }
      }

      return ImmutableMap.copyOf(result);
    }
  }
}
