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
package com.google.devtools.build.lib.skyframe.config;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import javax.annotation.Nullable;

/**
 * Container for storing a set of native and Starlark flag settings in separate buckets.
 *
 * <p>This is necessary because native and Starlark flags are parsed with different logic.
 */
@AutoValue
public abstract class NativeAndStarlarkFlags {

  public static final NativeAndStarlarkFlags EMPTY = builder().build();

  /** Builder for new {@link NativeAndStarlarkFlags} instances. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder nativeFlags(ImmutableList<String> nativeFlags);

    public abstract Builder starlarkFlags(ImmutableMap<String, Object> starlarkFlags);

    public abstract Builder scopesAttributes(ImmutableMap<String, String> scopesAttributes);

    public abstract Builder starlarkFlagDefaults(ImmutableMap<String, Object> starlarkFlagDefaults);

    public abstract Builder optionsClasses(
        ImmutableSet<Class<? extends FragmentOptions>> optionsClasses);

    public abstract Builder repoMapping(RepositoryMapping repoMapping);

    public abstract NativeAndStarlarkFlags build();
  }

  /** Returns a new {@link Builder}. */
  public static Builder builder() {
    return new AutoValue_NativeAndStarlarkFlags.Builder()
        .nativeFlags(ImmutableList.of())
        .starlarkFlags(ImmutableMap.of())
        .starlarkFlagDefaults(ImmutableMap.of())
        .scopesAttributes(ImmutableMap.of())
        .optionsClasses(ImmutableSet.of());
  }

  /**
   * The native flags from a given set of flags, in the format <code>[--flag=value]</code> or <code>
   * ["--flag", "value"]</code>.
   */
  public abstract ImmutableList<String> nativeFlags();

  /**
   * The Starlark flags from a given set of flags, mapped to the correct converted data type. If a
   * Starlark flag is explicitly set to the default value it should still appear in this map so that
   * consumers can properly handle the flag.
   */
  public abstract ImmutableMap<String, Object> starlarkFlags();

  public abstract ImmutableMap<String, String> scopesAttributes();

  // TODO: https://github.com/bazelbuild/bazel/issues/22365 - Improve looking up Starlark flag
  // option definitions and do not store this.
  public abstract ImmutableMap<String, Object> starlarkFlagDefaults();

  abstract ImmutableSet<Class<? extends FragmentOptions>> optionsClasses();

  @Nullable
  abstract RepositoryMapping repoMapping();

  public final OptionsParsingResult parse() throws OptionsParsingException {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(this.optionsClasses())
            // We need the ability to re-map internal options in the mappings file.
            .ignoreInternalOptions(false)
            .withConversionContext(this.repoMapping())
            .build();
    parser.parse(this.nativeFlags());
    parser.setStarlarkOptions(this.starlarkFlags());
    parser.setScopesAttributes(this.scopesAttributes());
    return parser;
  }
}
