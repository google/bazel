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
package com.google.devtools.build.lib.runtime;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Holds {@code --config=foo} definitions for the current invocation.
 *
 * <p>Callers can use this to determine which flags {@code --config=foo} sets:
 *
 * {@snippet :
 *   ConfigFlagDefinitions definitions = <get a reference from your favorite provider>;
 *   try {
 *     ConfigValue definition = ConfigFlagDefinitions.get("foo", configDefinitions);
 *     List<String> expandedFlags = definition.flags();
 *     List<String> rcfileSources = definition.rcSources();
 *   } catch (OptionsParsingException e) {
 *      // --config=foo doesn't exist or doesn't resolve.
 *   }
 * }
 *
 * <p>This is a pure data class, which makes it Skyframe- and cache-friendly. Resolution logic is a
 * pure static function over that data.
 */
public final class ConfigFlagDefinitions {
  private final ImmutableListMultimap<String, ConfigDefinition> definitions;

  @VisibleForTesting
  public static final ConfigFlagDefinitions NONE =
      new ConfigFlagDefinitions(ImmutableListMultimap.of());

  /**
   * There's no need for callers outside {@code lib.runtime} to construct this or see its underlying
   * data. The underlying data is a complicated implementation detail of the options parsing logic.
   */
  ConfigFlagDefinitions(ImmutableListMultimap<String, ConfigDefinition> definitions) {
    this.definitions = definitions;
  }

  /**
   * {@code --config=foo} expansion.
   *
   * @param flags the flags this --config expands to
   * @param rcSources full paths of the rc files that define this --config. Can be more than one
   *     because it may call other --configs from other files.
   */
  public record ConfigValue(List<String> flags, Set<String> rcSources) {}

  /**
   * Returns the definition of {@code --config=<configName>}
   *
   * @throws OptionsParsingException if the config doesn't exist or doesn't resolve correctly
   */
  public static ConfigValue get(String configName, ConfigFlagDefinitions definitions)
      throws OptionsParsingException {
    if (!definitions.definitions.containsKey(configName)) {
      throw new OptionsParsingException(String.format("--config=%s doesn't exist", configName));
    }
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    ImmutableSet.Builder<String> rcSources = ImmutableSet.builder();
    Set<String> seenConfigs = new HashSet<>();
    applyDefinition(configName, configName, definitions, flags, rcSources, seenConfigs);
    return new ConfigValue(flags.build(), rcSources.build());
  }

  /**
   * Expands {@code --config=<configName>}, making recursive calls any time it encounters {@code
   * --config=something_else}. Throws an {@code OptionsParsingException} on bad references or
   * cycles.
   */
  private static void applyDefinition(
      String origConfigName,
      String configName,
      ConfigFlagDefinitions definitions,
      ImmutableList.Builder<String> flags,
      ImmutableSet.Builder<String> rcSources,
      Set<String> seenConfigs)
      throws OptionsParsingException {
    ImmutableList<ConfigDefinition> directFlags = definitions.definitions.get(configName);
    if (!seenConfigs.add(configName)) {
      throw new OptionsParsingException(
          String.format(
              "--config=%s can't be evaluated because its definition has a cycle.",
              origConfigName));
    }
    if (directFlags.isEmpty()) {
      throw new OptionsParsingException(
          String.format(
              "--config=%s doesn't resolve because it expands to non-existent --config=%s",
              origConfigName, configName));
    }
    // The underlying data stores a multimap: { name: Collection<Definition> }. This is more
    // important for non-config definitions like "build --a=b" where each rc file defines its own
    // "build" args. For --config, just choose the first.
    ConfigDefinition def = directFlags.get(0);
    rcSources.add(def.rcSource());
    for (String flag : def.flags()) {
      if (flag.startsWith("--config=")) {
        applyDefinition(
            origConfigName,
            flag.substring(flag.indexOf("=") + 1),
            definitions,
            flags,
            rcSources,
            seenConfigs);
      } else {
        flags.add(flag);
      }
    }
  }

  /** Serialization- and BUILD library-friendly version of RcChunkOfArgs. */
  @AutoCodec
  record ConfigDefinition(ImmutableList<String> flags, String rcSource) {}

  @Override
  public boolean equals(Object o) {
    if (o instanceof ConfigFlagDefinitions other) {
      return other.definitions.equals(definitions);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return definitions.hashCode();
  }
}
