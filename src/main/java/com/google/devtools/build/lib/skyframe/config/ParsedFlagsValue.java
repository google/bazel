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

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.Label.PackageContext;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionValueDescription;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.Map;

/** Stores the {@link OptionsParsingResult} from {@link ParsedFlagsFunction}. */
@AutoCodec
public final class ParsedFlagsValue implements SkyValue {

  /** Key for {@link ParsedFlagsValue} based on the raw flags. */
  @ThreadSafety.Immutable
  @AutoCodec
  public static final class Key implements SkyKey {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    /**
     * Returns a new {@link Key} for the given command-line flags, such as {@code
     * --compilation_mode=bdg} or {@code --//custom/starlark:flag=23}.
     */
    public static Key create(ImmutableList<String> rawFlags, PackageContext packageContext) {
      return create(rawFlags, packageContext, /* includeDefaultValues= */ false);
    }

    /**
     * Returns a new {@link Key} for the given command-line flags, such as {@code
     * --compilation_mode=bdg} or {@code --//custom/starlark:flag=23}.
     */
    @AutoCodec.Instantiator
    public static Key create(
        ImmutableList<String> rawFlags,
        PackageContext packageContext,
        boolean includeDefaultValues) {
      return interner.intern(new Key(rawFlags, packageContext, includeDefaultValues));
    }

    private final ImmutableList<String> rawFlags;
    private final PackageContext packageContext;
    private final boolean includeDefaultValues;

    private Key(
        ImmutableList<String> rawFlags,
        PackageContext packageContext,
        boolean includeDefaultValues) {
      this.rawFlags = checkNotNull(rawFlags);
      this.packageContext = checkNotNull(packageContext);
      this.includeDefaultValues = includeDefaultValues;
    }

    ImmutableList<String> rawFlags() {
      return rawFlags;
    }

    PackageContext packageContext() {
      return packageContext;
    }

    boolean includeDefaultValues() {
      return includeDefaultValues;
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.PARSED_FLAGS;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key that)) {
        return false;
      }
      return rawFlags.equals(that.rawFlags)
          && packageContext.equals(that.packageContext)
          && includeDefaultValues == that.includeDefaultValues;
    }

    @Override
    public int hashCode() {
      return HashCodes.hashObjects(rawFlags, packageContext) * 31
          + Boolean.hashCode(includeDefaultValues);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper("ParsedFlagsValue.Key")
          .add("rawFlags", rawFlags)
          .add("packageContext", packageContext)
          .add("includeDefaultValues", includeDefaultValues)
          .toString();
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  static ParsedFlagsValue parseAndCreate(NativeAndStarlarkFlags flags)
      throws OptionsParsingException {
    return new ParsedFlagsValue(flags, flags.parse());
  }

  @AutoCodec.Instantiator
  @VisibleForSerialization
  static ParsedFlagsValue createForDeserialization(NativeAndStarlarkFlags flags) {
    try {
      return parseAndCreate(flags);
    } catch (OptionsParsingException e) {
      // Should be impossible since it parsed successfully before it was serialized.
      throw new IllegalStateException(e);
    }
  }

  private final NativeAndStarlarkFlags flags;
  private final OptionsParsingResult parsingResult;
  private final LoadingCache<BuildOptions, BuildConfigurationKey> mergeCache =
      Caffeine.newBuilder().weakKeys().build(this::mergeWithImpl);

  private ParsedFlagsValue(NativeAndStarlarkFlags flags, OptionsParsingResult parsingResult) {
    this.parsingResult = checkNotNull(parsingResult);
    this.flags = checkNotNull(flags);
  }

  public OptionsParsingResult parsingResult() {
    return parsingResult;
  }

  /**
   * Returns a new {@link BuildConfigurationKey} with options containing all flags from the given
   * {@link BuildOptions} with {@link #parsingResult()} merged in.
   *
   * <p>Returns a {@link BuildConfigurationKey} instead of just {@link BuildOptions} so that caching
   * of mappings also saves the CPU cost of interning {@link BuildConfigurationKey} (which is what
   * callers typically need).
   *
   * <p>The merging logic is as follows:
   *
   * <ul>
   *   <li>For native flags, only the fragments in the original {@link BuildOptions} are kept.
   *   <li>Any native flags in this instance, for fragments that are kept, are set to the value from
   *       this instance.
   *   <li>All Starlark flags from the original {@link BuildOptions} are kept, then all Starlark
   *       options from this instance are added.
   *   <li>Any Starlark flags which are present in both, the value from this instance is kept.
   * </ul>
   *
   * <p>To preserve fragment trimming, this method will not expand the set of included native
   * fragments from the original {@link BuildOptions}. If the parsing result contains native options
   * whose owning fragment is not part of the original {@link BuildOptions} they will be ignored
   * (i.e. not set on the resulting options). Starlark options are not affected by this restriction.
   *
   * @param source the base options to modify
   * @return a {@link BuildConfigurationKey} to request the new configuration after applying this
   *     parsed flags value to the original options
   */
  public BuildConfigurationKey mergeWith(BuildOptions source) {
    return mergeCache.get(source);
  }

  private BuildConfigurationKey mergeWithImpl(BuildOptions source) {
    BuildOptions.Builder builder = source.toBuilder();

    // Handle native options.
    for (OptionValueDescription optionValue : parsingResult.allOptionValues()) {
      OptionDefinition optionDefinition = optionValue.getOptionDefinition();
      // All options obtained from an options parser are guaranteed to have been defined in an
      // FragmentOptions class.
      Class<? extends FragmentOptions> fragmentOptionClass =
          optionDefinition.getDeclaringClass(FragmentOptions.class);

      FragmentOptions fragment = builder.getFragmentOptions(fragmentOptionClass);
      if (fragment == null) {
        // Preserve trimming by ignoring fragments not present in the original options.
        continue;
      }
      updateOptionValue(fragment, optionDefinition, optionValue);
    }

    // Also copy Starlark options.
    for (Map.Entry<String, Object> starlarkOption : parsingResult.getStarlarkOptions().entrySet()) {
      updateStarlarkFlag(builder, starlarkOption.getKey(), starlarkOption.getValue());
    }

    return BuildConfigurationKey.create(builder.addScopeTypeMap(source.getScopeTypeMap()).build());
  }

  private static void updateOptionValue(
      FragmentOptions fragment,
      OptionDefinition optionDefinition,
      OptionValueDescription optionValue) {
    // TODO: https://github.com/bazelbuild/bazel/issues/22453 - This will completely overwrite
    //  accumulating flags, which is almost certainly not what users want. Instead this should
    //  intelligently merge options.
    Object value = optionValue.getValue();
    optionDefinition.setValue(fragment, value);
  }

  private void updateStarlarkFlag(
      BuildOptions.Builder builder, String rawFlagName, Object rawFlagValue) {
    Label flagName = Label.parseCanonicalUnchecked(rawFlagName);
    // If the known default value is the same as the new value, unset it.
    if (isStarlarkFlagSetToDefault(rawFlagName, rawFlagValue)) {
      builder.removeStarlarkOption(flagName);
    } else {
      builder.addStarlarkOption(flagName, rawFlagValue);
    }
  }

  private boolean isStarlarkFlagSetToDefault(String rawFlagName, Object rawFlagValue) {
    var defaultVal = flags.starlarkFlagDefaults().get(rawFlagName);
    return defaultVal != null && defaultVal.equals(rawFlagValue);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ParsedFlagsValue that)) {
      return false;
    }
    return flags.equals(that.flags);
  }

  @Override
  public int hashCode() {
    return flags.hashCode();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("flags", flags)
        .add("parsingResult", parsingResult)
        .toString();
  }
}
