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

package com.google.devtools.build.lib.analysis.config;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.devtools.build.lib.skyframe.serialization.ImmutableMapCodecs.IMMUTABLE_MAP_CODEC;
import static com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec.stringCodec;
import static java.util.Comparator.naturalOrder;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.devtools.common.options.OptionsProvider;
import com.google.devtools.common.options.ParsedOptionDescription;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Stores the command-line options from a set of configuration fragments. */
// TODO(janakr): If overhead of FragmentOptions class names is too high, add constructor that just
// takes fragments and gets names from them.
public final class BuildOptions implements Cloneable {

  @SerializationConstant
  static final Comparator<Class<? extends FragmentOptions>> LEXICAL_FRAGMENT_OPTIONS_COMPARATOR =
      Comparator.comparing(Class::getName);

  public static Map<Label, Object> labelizeStarlarkOptions(Map<String, Object> starlarkOptions) {
    return starlarkOptions.entrySet().stream()
        .collect(
            Collectors.toMap(e -> Label.parseCanonicalUnchecked(e.getKey()), Map.Entry::getValue));
  }

  /**
   * Converts the map containing String representation of scopes attributes to a map of {@link
   * Label} of Starlark options to their corresponding {@link Scope.ScopeType}.
   */
  private static ImmutableMap<Label, Scope.ScopeType> convertScopesAttributes(
      Map<String, String> scopesAttributes, Map<String, Object> starlarkOptions) {
    return scopesAttributes.entrySet().stream()
        .filter(e -> starlarkOptions.containsKey(e.getKey()))
        .collect(
            toImmutableMap(
                e -> Label.parseCanonicalUnchecked(e.getKey()),
                e -> Scope.ScopeType.valueOfIgnoreCase(e.getValue())));
  }

  public static BuildOptions getDefaultBuildOptionsForFragments(
      Iterable<Class<? extends FragmentOptions>> fragmentClasses) {
    try {
      return BuildOptions.of(fragmentClasses);
    } catch (OptionsParsingException e) {
      throw new IllegalArgumentException("Failed to parse empty options", e);
    }
  }

  /**
   * Creates a BuildOptions class by taking the option values from an options provider (eg. an
   * OptionsParser).
   */
  public static BuildOptions of(
      Iterable<Class<? extends FragmentOptions>> optionsList, OptionsProvider provider) {
    Builder builder = builder();
    for (Class<? extends FragmentOptions> optionsClass : optionsList) {
      builder.addFragmentOptions(provider.getOptions(optionsClass));
    }
    return builder
        .addStarlarkOptions(labelizeStarlarkOptions(provider.getStarlarkOptions()))
        .addScopeTypeMap(
            convertScopesAttributes(provider.getScopesAttributes(), provider.getStarlarkOptions()))
        .build();
  }

  /**
   * Creates a BuildOptions class by taking the option values from command-line arguments. Returns a
   * BuildOptions class that only has native options.
   */
  @VisibleForTesting
  public static BuildOptions of(
      Iterable<Class<? extends FragmentOptions>> optionsList, String... args)
      throws OptionsParsingException {
    Builder builder = builder();
    OptionsParser parser = OptionsParser.builder().optionsClasses(optionsList).build();
    parser.parse(args);
    for (Class<? extends FragmentOptions> optionsClass : optionsList) {
      builder.addFragmentOptions(parser.getOptions(optionsClass));
    }
    return builder.build();
  }

  /*
   * Returns a BuildOptions class that only has Starlark options.
   */
  @VisibleForTesting
  public static BuildOptions of(Map<Label, Object> starlarkOptions) {
    return builder().addStarlarkOptions(starlarkOptions).build();
  }

  /**
   * Returns the actual instance of a {@link FragmentOptions} class, or {@code null} if the options
   * class is not present.
   */
  @Nullable
  public <T extends FragmentOptions> T get(Class<T> optionsClass) {
    FragmentOptions options = fragmentOptionsMap.get(optionsClass);
    return optionsClass.cast(options);
  }

  /** Returns true if these options contain the given {@link FragmentOptions}. */
  public boolean contains(Class<? extends FragmentOptions> optionsClass) {
    return fragmentOptionsMap.containsKey(optionsClass);
  }

  /**
   * Are these options "empty", meaning they contain no meaningful configuration information?
   *
   * <p>See {@link com.google.devtools.build.lib.analysis.config.transitions.NoConfigTransition}.
   */
  public boolean hasNoConfig() {
    // Ideally the implementation is fragmentOptionsMap.isEmpty() && starlarkOptionsMap.isEmpty().
    // See NoConfigTransition for why CoreOptions stays included.
    return fragmentOptionsMap.size() == 1
        && Iterables.getOnlyElement(fragmentOptionsMap.values())
            .getClass()
            .getSimpleName()
            .equals("CoreOptions")
        && starlarkOptionsMap.isEmpty();
  }

  /** Returns a hex digest string uniquely identifying the build options. */
  public String checksum() {
    if (checksum == null) {
      synchronized (this) {
        if (checksum == null) {
          if (fragmentOptionsMap.isEmpty() && starlarkOptionsMap.isEmpty()) {
            checksum = "0".repeat(64); // Make empty build options easy to distinguish.
          } else {
            Fingerprint fingerprint = new Fingerprint();
            for (FragmentOptions options : fragmentOptionsMap.values()) {
              fingerprint.addString(options.cacheKey());
            }
            fingerprint.addString(OptionsBase.mapToCacheKey(starlarkOptionsMap));
            fingerprint.addString(OptionsBase.mapToCacheKey(scopes));
            checksum = fingerprint.hexDigestAndReset();
          }
        }
      }
    }
    return checksum;
  }

  /**
   * Returns a user-friendly configuration identifier as a prefix of <code>fullId</code>.
   *
   * <p>This eliminates having to manipulate long full hashes, just like Git short commit hashes.
   */
  public String shortId() {
    // Inherit Git's default commit hash prefix length. It's a principled choice with similar usage
    // patterns. cquery, which uses this, has access to every configuration in the build. If it
    // turns out this setting produces ambiguous prefixes, we could always compare configurations
    // to find the actual minimal unambiguous length.
    return checksum() == null ? "null" : checksum().substring(0, 7);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("checksum", checksum())
        .add("fragmentOptions", fragmentOptionsMap.values())
        .add("starlarkOptions", starlarkOptionsMap)
        .add("scopes", scopes)
        .toString();
  }

  /** Returns the options contained in this collection, sorted by {@link FragmentOptions} name. */
  public ImmutableCollection<FragmentOptions> getNativeOptions() {
    return fragmentOptionsMap.values();
  }

  /**
   * Returns the set of fragment classes contained in these options, sorted by {@link
   * FragmentOptions} name.
   */
  public ImmutableSet<Class<? extends FragmentOptions>> getFragmentClasses() {
    return fragmentOptionsMap.keySet();
  }

  /** Starlark options, sorted lexicographically by name. */
  public ImmutableMap<Label, Object> getStarlarkOptions() {
    return starlarkOptionsMap;
  }

  /**
   * Map of {@link ScopeType} for starlark options. Before the final {@link BuildOptions} is
   * produced to create the final {@link BuildConfigurationKey}, the {@link ScopeType} for each
   * starlark flag is expected to be resolved. If there is a transition involved introducing a
   * starlark flag that is not already part of the baseline configuration, the {@link ScopeType} for
   * that flag will be null until the final {@link BuildOptions} is produced.
   */
  public ImmutableMap<Label, Scope.ScopeType> getScopeTypeMap() {
    return scopes;
  }

  /**
   * Creates a copy of the BuildOptions object that contains copies of the FragmentOptions and
   * Starlark options.
   */
  @Override
  public BuildOptions clone() {
    ImmutableMap<Class<? extends FragmentOptions>, FragmentOptions> nativeOptions =
        fragmentOptionsMap.entrySet().stream()
            .collect(
                toImmutableMap(
                    Map.Entry::getKey,
                    // Explicitly clone native options because FragmentOptions is mutable.
                    e -> e.getValue().clone()));
    // Note that this assumes that starlark option values are immutable.
    ImmutableMap<Label, Object> starlarkOptions = ImmutableMap.copyOf(starlarkOptionsMap);
    ImmutableMap<Label, Scope.ScopeType> scopes = this.scopes;
    return new BuildOptions(nativeOptions, starlarkOptions, scopes);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof BuildOptions)) {
      return false;
    }
    return checksum().equals(((BuildOptions) other).checksum());
  }

  @Override
  public int hashCode() {
    return 31 + checksum().hashCode();
  }

  /** Maps options class definitions to FragmentOptions objects. */
  private final ImmutableMap<Class<? extends FragmentOptions>, FragmentOptions> fragmentOptionsMap;

  /**
   * Maps Starlark options names to Starlark options values. This should never contain an entry for
   * a Starlark option and the default value: if a Starlark option is explicitly or implicitly set
   * to the default it should be removed from this map so that configurations are not duplicated
   * needlessly.
   */
  private final ImmutableMap<Label, Object> starlarkOptionsMap;

  // TODO: b/377559852 - Merge scopes into starlarkOptionsMap
  /** Maps Starlark options names to {@link Scope} information */
  private final ImmutableMap<Label, Scope.ScopeType> scopes;

  // Lazily initialized both for performance and correctness - BuildOptions instances may be mutated
  // after construction but before consumption. Access via checksum() to ensure initialization. This
  // field is volatile as per https://errorprone.info/bugpattern/DoubleCheckedLocking, which
  // encourages using volatile even for immutable objects.
  @Nullable private volatile String checksum = null;

  private BuildOptions(
      ImmutableMap<Class<? extends FragmentOptions>, FragmentOptions> fragmentOptionsMap,
      ImmutableMap<Label, Object> starlarkOptionsMap,
      ImmutableMap<Label, Scope.ScopeType> scopes) {
    this.fragmentOptionsMap = fragmentOptionsMap;
    this.starlarkOptionsMap = starlarkOptionsMap;
    this.scopes = scopes;
  }

  /**
   * Returns true if the passed parsing result's options have the same value as these options.
   *
   * <p>If a native parsed option is passed whose fragment has been trimmed in these options it is
   * considered to match.
   *
   * <p>If no options are present in the parsing result or all options in the parsing result have
   * been trimmed the result is considered not to match. This is because otherwise the parsing
   * result would match any options in a similar trimmed state, regardless of contents.
   *
   * @param parsingResult parsing result to be compared to these options
   * @return true if all non-trimmed values match
   * @throws OptionsParsingException if options cannot be parsed
   */
  public boolean matches(OptionsParsingResult parsingResult) throws OptionsParsingException {
    Set<OptionDefinition> ignoredDefinitions = new HashSet<>();
    for (ParsedOptionDescription parsedOption : parsingResult.asListOfExplicitOptions()) {
      OptionDefinition optionDefinition = parsedOption.getOptionDefinition();

      // All options obtained from an options parser are guaranteed to have been defined in an
      // FragmentOptions class.
      Class<? extends FragmentOptions> fragmentClass =
          optionDefinition.getDeclaringClass(FragmentOptions.class);

      FragmentOptions originalFragment = fragmentOptionsMap.get(fragmentClass);
      if (originalFragment == null) {
        // Ignore flags set in trimmed fragments.
        ignoredDefinitions.add(optionDefinition);
        continue;
      }
      Object originalValue = originalFragment.asMap().get(optionDefinition.getOptionName());
      if (!Objects.equals(originalValue, parsedOption.getConvertedValue())) {
        return false;
      }
    }

    Map<Label, Object> starlarkOptions =
        labelizeStarlarkOptions(parsingResult.getStarlarkOptions());
    MapDifference<Label, Object> starlarkDifference =
        Maps.difference(starlarkOptionsMap, starlarkOptions);
    if (starlarkDifference.entriesInCommon().size() < starlarkOptions.size()) {
      return false;
    }

    if (ignoredDefinitions.size() == parsingResult.asListOfExplicitOptions().size()
        && starlarkOptions.isEmpty()) {
      // Zero options were compared, either because none were passed or because all of them were
      // trimmed.
      return false;
    }

    return true;
  }

  /** Creates a builder object for BuildOptions */
  public static Builder builder() {
    return new Builder();
  }

  /** Creates a builder operating on a clone of this BuildOptions. */
  public Builder toBuilder() {
    return builder().merge(clone());
  }

  /** Builder class for BuildOptions. */
  public static class Builder {
    /**
     * Merges the given BuildOptions into this builder, overriding any previous instances of
     * Starlark options or FragmentOptions subclasses found in the new BuildOptions.
     */
    @CanIgnoreReturnValue
    public Builder merge(BuildOptions options) {
      for (FragmentOptions fragment : options.getNativeOptions()) {
        this.addFragmentOptions(fragment);
      }
      this.addStarlarkOptions(options.getStarlarkOptions());
      this.addScopeTypeMap(options.getScopeTypeMap());
      return this;
    }

    /**
     * Adds a new {@link FragmentOptions} instance to the builder.
     *
     * <p>Overrides previous instances of the exact same subclass of {@code FragmentOptions}.
     *
     * <p>The options get preprocessed with {@link FragmentOptions#getNormalized}.
     */
    @CanIgnoreReturnValue
    public <T extends FragmentOptions> Builder addFragmentOptions(T options) {
      fragmentOptions.put(options.getClass(), options.getNormalized());
      return this;
    }

    /**
     * Returns the {@link FragmentOptions} for the given class, or {@code null} if that fragment is
     * not present.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T extends FragmentOptions> T getFragmentOptions(Class<T> key) {
      return (T) fragmentOptions.get(key);
    }

    /** Removes the value for the {@link FragmentOptions} with the given FragmentOptions class. */
    @CanIgnoreReturnValue
    public Builder removeFragmentOptions(Class<? extends FragmentOptions> key) {
      fragmentOptions.remove(key);
      return this;
    }

    /**
     * Adds multiple Starlark options to the builder. Overrides previous instances of the same key.
     */
    @CanIgnoreReturnValue
    public Builder addStarlarkOptions(Map<Label, Object> options) {
      starlarkOptions.putAll(options);
      return this;
    }

    /** Adds a Starlark option to the builder. Overrides previous instances of the same key. */
    @CanIgnoreReturnValue
    public Builder addStarlarkOption(Label key, Object value) {
      starlarkOptions.put(key, value);
      return this;
    }

    /**
     * Adds ScopeType for a Starlark option to the builder. Overrides previous instances of the same
     * key.
     */
    @CanIgnoreReturnValue
    public Builder addScopeType(Label key, Scope.ScopeType value) {
      scopes.put(key, value);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addScopeTypeMap(Map<Label, Scope.ScopeType> scopes) {
      for (Map.Entry<Label, Scope.ScopeType> entry : scopes.entrySet()) {
        this.scopes.put(entry.getKey(), entry.getValue());
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder removeScope(Label key) {
      scopes.remove(key);
      return this;
    }

    /** Removes the value and associated ScopeType for the Starlark option with the given key. */
    @CanIgnoreReturnValue
    public Builder removeStarlarkOption(Label key) {
      starlarkOptions.remove(key);
      removeScope(key);
      return this;
    }

    public BuildOptions build() {
      return new BuildOptions(
          sortedImmutableHashMap(fragmentOptions, LEXICAL_FRAGMENT_OPTIONS_COMPARATOR),
          sortedImmutableHashMap(starlarkOptions, naturalOrder()),
          sortedImmutableHashMap(scopes, naturalOrder()));
    }

    /**
     * Constructs a hash-based {@link ImmutableMap} copy of the given map, with an iteration order
     * defined by the given key comparator.
     *
     * <p>The returned map has a deterministic iteration order but is <em>not</em> an {@link
     * ImmutableSortedMap}, which uses binary search lookups. Hash-based lookups are expected to be
     * much faster for build options.
     */
    private static <K, V> ImmutableMap<K, V> sortedImmutableHashMap(
        Map<K, V> map, Comparator<K> keyComparator) {
      List<Map.Entry<K, V>> entries = new ArrayList<>(map.entrySet());
      entries.sort(Map.Entry.comparingByKey(keyComparator));
      return ImmutableMap.copyOf(entries);
    }

    private final Map<Class<? extends FragmentOptions>, FragmentOptions> fragmentOptions =
        new HashMap<>();
    private final LinkedHashMap<Label, Object> starlarkOptions = new LinkedHashMap<>();

    // TODO: b/377559852 - Merge scopes into starlarkOptionsMap
    private final LinkedHashMap<Label, Scope.ScopeType> scopes = new LinkedHashMap<>();

    private Builder() {}
  }

  public static ValueSharingCodec valueSharingCodec() {
    return ValueSharingCodec.INSTANCE;
  }

  /**
   * A value sharing codec for BuildOptions that does not rely on an OptionsChecksumCache.
   *
   * <p>This allows the BuildOptions object to be serialized remotely, and fetched with a new
   * instance without relying on an existing local primed cache.
   */
  private static final class ValueSharingCodec extends DeferredObjectCodec<BuildOptions> {
    private static final ValueSharingCodec INSTANCE = new ValueSharingCodec();

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public Class<BuildOptions> getEncodedClass() {
      return BuildOptions.class;
    }

    @Override
    public void serialize(
        SerializationContext context, BuildOptions options, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.putSharedValue(options.fragmentOptionsMap, null, IMMUTABLE_MAP_CODEC, codedOut);
      context.putSharedValue(options.starlarkOptionsMap, null, IMMUTABLE_MAP_CODEC, codedOut);
      context.putSharedValue(options.scopes, null, IMMUTABLE_MAP_CODEC, codedOut);
    }

    @Override
    public DeferredValue<? extends BuildOptions> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      var builder = new DeserializationBuilder();
      context.getSharedValue(
          codedIn,
          null,
          IMMUTABLE_MAP_CODEC,
          builder,
          DeserializationBuilder::setFragmentOptionsMap);
      context.getSharedValue(
          codedIn,
          null,
          IMMUTABLE_MAP_CODEC,
          builder,
          DeserializationBuilder::setStarlarkOptionsMap);
      context.getSharedValue(
          codedIn, null, IMMUTABLE_MAP_CODEC, builder, DeserializationBuilder::setScopes);
      return builder;
    }

    private static final class DeserializationBuilder
        implements DeferredObjectCodec.DeferredValue<BuildOptions> {

      ImmutableMap<Class<? extends FragmentOptions>, FragmentOptions> fragmentOptionsMap;
      ImmutableMap<Label, Object> starlarkOptionsMap;
      // TODO: b/377559852 - Merge scopes into starlarkOptionsMap
      ImmutableMap<Label, Scope.ScopeType> scopes;

      @Override
      public BuildOptions call() {
        return new BuildOptions(fragmentOptionsMap, starlarkOptionsMap, scopes);
      }

      @SuppressWarnings("unchecked")
      private static void setFragmentOptionsMap(DeserializationBuilder builder, Object value) {
        builder.fragmentOptionsMap =
            (ImmutableMap<Class<? extends FragmentOptions>, FragmentOptions>) value;
      }

      @SuppressWarnings("unchecked")
      private static void setStarlarkOptionsMap(DeserializationBuilder builder, Object value) {
        builder.starlarkOptionsMap = (ImmutableMap<Label, Object>) value;
      }

      @SuppressWarnings("unchecked")
      private static void setScopes(DeserializationBuilder builder, Object value) {
        builder.scopes = (ImmutableMap<Label, Scope.ScopeType>) value;
      }
    }
  }

  /**
   * Codec for {@link BuildOptions}.
   *
   * <p>This codec works by serializing the {@link BuildOptions#checksum} only. This works due to
   * the assumption that anytime a value containing a particular configuration is deserialized, it
   * was previously requested using the same configuration key, thus priming the cache.
   */
  @VisibleForSerialization
  public static final class Codec extends LeafObjectCodec<BuildOptions> {
    private static final Codec INSTANCE = new Codec();

    public static Codec buildOptionsCodec() {
      return INSTANCE;
    }

    @Override
    public Class<BuildOptions> getEncodedClass() {
      return BuildOptions.class;
    }

    @Override
    public void serialize(
        LeafSerializationContext context, BuildOptions options, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      if (!context.getDependency(OptionsChecksumCache.class).prime(options)) {
        throw new SerializationException("Failed to prime cache for " + options.checksum());
      }
      context.serializeLeaf(options.checksum(), stringCodec(), codedOut);
    }

    @Override
    public BuildOptions deserialize(LeafDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      String checksum = context.deserializeLeaf(codedIn, stringCodec());
      BuildOptions result = context.getDependency(OptionsChecksumCache.class).getOptions(checksum);
      if (result == null) {
        throw new SerializationException("No options instance for " + checksum);
      }
      return result;
    }
  }

  /**
   * Provides {@link BuildOptions} instances when requested via their {@linkplain
   * BuildOptions#checksum() checksum}.
   */
  public interface OptionsChecksumCache {

    /**
     * Called during deserialization to transform a checksum into a {@link BuildOptions} instance.
     *
     * <p>Returns {@code null} when the given checksum is unknown, in which case the codec throws
     * {@link SerializationException}.
     */
    @Nullable
    BuildOptions getOptions(String checksum);

    /**
     * Notifies the cache that it may be necessary to deserialize the given options diff's checksum.
     *
     * <p>Called each time an {@link BuildOptions} instance is serialized.
     *
     * @return whether this cache was successfully primed, if {@code false} the codec will throw
     *     {@link SerializationException}
     */
    boolean prime(BuildOptions options);
  }

  /**
   * Simple {@link OptionsChecksumCache} backed by a {@link ConcurrentMap}.
   *
   * <p>Checksum mappings are retained indefinitely.
   */
  public static final class MapBackedChecksumCache implements OptionsChecksumCache {
    private final ConcurrentMap<String, BuildOptions> map = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public BuildOptions getOptions(String checksum) {
      return map.get(checksum);
    }

    @Override
    public boolean prime(BuildOptions options) {
      map.putIfAbsent(options.checksum(), options);
      return true;
    }
  }
}
