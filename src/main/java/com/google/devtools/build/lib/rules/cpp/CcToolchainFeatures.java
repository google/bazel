// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.cpp;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Expandable;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.SingleVariables;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.StringChunk;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.StringValueParser;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CToolchain;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkValue;

/**
 * Provides access to features supported by a specific toolchain.
 *
 * <p>This class can be generated from the CToolchain protocol buffer.
 *
 * <p>TODO(bazel-team): Implement support for specifying the toolchain configuration directly from
 * the BUILD file.
 *
 * <p>TODO(bazel-team): Find a place to put the public-facing documentation and link to it from
 * here.
 *
 * <p>TODO(bazel-team): Split out Feature as CcToolchainFeature, which will modularize the crosstool
 * configuration into one part that is about handling a set of features (including feature
 * selection) and one part that is about how to apply a single feature (parsing flags and expanding
 * them from build variables).
 */
@Immutable
public class CcToolchainFeatures implements StarlarkValue {

  /**
   * Thrown when a flag value cannot be expanded under a set of build variables.
   *
   * <p>This happens for example when a flag references a variable that is not provided by the
   * action, or when a flag group implicitly references multiple variables of sequence type.
   */
  public static class ExpansionException extends EvalException {
    ExpansionException(String message) {
      super(message);
    }
  }

  /** Thrown when multiple features provide the same string symbol. */
  public static class CollidingProvidesException extends Exception {
    CollidingProvidesException(String message) {
      super(message);
    }
  }

  /** Error message thrown when a toolchain enables two features that provide the same string. */
  public static final String COLLIDING_PROVIDES_ERROR =
      "Symbol %s is provided by all of the following features: %s";

  /** A single flag to be expanded under a set of variables. */
  @Immutable
  public static class Flag implements Expandable {
    private final ImmutableList<StringChunk> chunks;

    public Flag(ImmutableList<StringChunk> chunks) {
      this.chunks = chunks;
    }

    String getString() {
      return Joiner.on("")
          .join(
              chunks.stream()
                  .map(chunk -> chunk.getString())
                  .collect(ImmutableList.toImmutableList()));
    }

    /** Expand this flag into a single new entry in {@code commandLine}. */
    @Override
    public void expand(
        CcToolchainVariables variables,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        List<String> commandLine)
        throws ExpansionException {
      StringBuilder flag = new StringBuilder();
      for (StringChunk chunk : chunks) {
        flag.append(chunk.expand(variables, pathMapper));
      }
      commandLine.add(flag.toString().intern());
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof Flag that) {
        return Iterables.elementsEqual(chunks, that.chunks);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(chunks);
    }

    /** A single environment key/value pair to be expanded under a set of variables. */
    public static Expandable create(ImmutableList<StringChunk> chunks) {
      if (chunks.size() == 1) {
        return new SingleChunkFlag(chunks.get(0));
      }
      return new Flag(chunks);
    }

    /** Optimization for single-chunk case */
    @Immutable
    static class SingleChunkFlag implements Expandable {
      private final StringChunk chunk;

      @VisibleForSerialization
      SingleChunkFlag(StringChunk chunk) {
        this.chunk = chunk;
      }

      @Override
      public void expand(
          CcToolchainVariables variables,
          @Nullable ArtifactExpander artifactExpander,
          PathMapper pathMapper,
          List<String> commandLine)
          throws ExpansionException {
        commandLine.add(chunk.expand(variables, pathMapper));
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        SingleChunkFlag that = (SingleChunkFlag) o;
        return chunk.equals(that.chunk);
      }

      String getString() {
        return chunk.getString();
      }

      @Override
      public int hashCode() {
        return chunk.hashCode();
      }
    }
  }

  /** A single environment key/value pair to be expanded under a set of variables. */
  @Immutable
  public static class EnvEntry {
    private final String key;
    private final ImmutableList<StringChunk> valueChunks;
    private final ImmutableSet<String> expandIfAllAvailable;

    private EnvEntry(CToolchain.EnvEntry envEntry) throws EvalException {
      this.key = envEntry.getKey();
      StringValueParser parser = new StringValueParser(envEntry.getValue());
      this.valueChunks = parser.getChunks();
      this.expandIfAllAvailable = ImmutableSet.copyOf(envEntry.getExpandIfAllAvailableList());
    }

    EnvEntry(
        String key,
        ImmutableList<StringChunk> valueChunks,
        ImmutableSet<String> expandIfAllAvailable) {
      this.key = key;
      this.valueChunks = valueChunks;
      this.expandIfAllAvailable = expandIfAllAvailable;
    }

    String getKey() {
      return key;
    }

    String getValue() {
      return Joiner.on("")
          .join(
              valueChunks.stream()
                  .map(stringChunk -> stringChunk.getString())
                  .collect(ImmutableList.toImmutableList()));
    }

    ImmutableSet<String> getExpandIfAllAvailable() {
      return expandIfAllAvailable;
    }

    private boolean canBeExpanded(CcToolchainVariables variables) {
      for (String variable : expandIfAllAvailable) {
        if (!variables.isAvailable(variable)) {
          return false;
        }
      }
      return true;
    }

    /**
     * Adds the key/value pair this object represents to the given map of environment variables. The
     * value of the entry is expanded with the given {@code variables}.
     */
    public void addEnvEntry(
        CcToolchainVariables variables,
        ImmutableMap.Builder<String, String> envBuilder,
        PathMapper pathMapper)
        throws ExpansionException {
      if (!canBeExpanded(variables)) {
        return;
      }
      StringBuilder value = new StringBuilder();
      for (StringChunk chunk : valueChunks) {
        value.append(chunk.expand(variables, pathMapper));
      }
      envBuilder.put(key, value.toString());
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof EnvEntry that) {
        return Objects.equals(key, that.key)
            && Iterables.elementsEqual(valueChunks, that.valueChunks);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, valueChunks);
    }
  }

  /** Used for equality check between a variable and a specific value. */
  @Immutable
  static class VariableWithValue {
    public final String variable;
    public final String value;

    public VariableWithValue(String variable, String value) {
      this.variable = variable;
      this.value = value;
    }

    String getVariable() {
      return variable;
    }

    String getValue() {
      return value;
    }
  }

  /**
   * A group of flags. When iterateOverVariable is specified, we assume the variable is a sequence
   * and the flag_group will be expanded repeatedly for every value in the sequence.
   */
  @Immutable
  static class FlagGroup implements Expandable {
    private final ImmutableList<Expandable> expandables;
    private String iterateOverVariable;
    private final ImmutableSet<String> expandIfAllAvailable;
    private final ImmutableSet<String> expandIfNoneAvailable;
    private final String expandIfTrue;
    private final String expandIfFalse;
    private final VariableWithValue expandIfEqual;

    private FlagGroup(CToolchain.FlagGroup flagGroup) throws EvalException {
      ImmutableList.Builder<Expandable> expandables = ImmutableList.builder();
      Collection<String> flags = flagGroup.getFlagList();
      Collection<CToolchain.FlagGroup> groups = flagGroup.getFlagGroupList();
      if (!flags.isEmpty() && !groups.isEmpty()) {
        // If both flags and flag_groups are available, the original order is not preservable.
        throw new ExpansionException(
            "Invalid toolchain configuration: a flag_group must not contain both a flag "
                + "and another flag_group.");
      }
      for (String flag : flags) {
        StringValueParser parser = new StringValueParser(flag);
        expandables.add(Flag.create(parser.getChunks()));
      }
      for (CToolchain.FlagGroup group : groups) {
        FlagGroup subgroup = new FlagGroup(group);
        expandables.add(subgroup);
      }
      if (flagGroup.hasIterateOver()) {
        this.iterateOverVariable = flagGroup.getIterateOver();
      }
      this.expandables = expandables.build();
      this.expandIfAllAvailable = ImmutableSet.copyOf(flagGroup.getExpandIfAllAvailableList());
      this.expandIfNoneAvailable = ImmutableSet.copyOf(flagGroup.getExpandIfNoneAvailableList());
      this.expandIfTrue = Strings.emptyToNull(flagGroup.getExpandIfTrue());
      this.expandIfFalse = Strings.emptyToNull(flagGroup.getExpandIfFalse());
      if (flagGroup.hasExpandIfEqual()) {
        this.expandIfEqual = new VariableWithValue(
            flagGroup.getExpandIfEqual().getVariable(),
            flagGroup.getExpandIfEqual().getValue());
      } else {
        this.expandIfEqual = null;
      }
    }

    FlagGroup(
        ImmutableList<Expandable> expandables,
        String iterateOverVariable,
        ImmutableSet<String> expandIfAllAvailable,
        ImmutableSet<String> expandIfNoneAvailable,
        String expandIfTrue,
        String expandIfFalse,
        VariableWithValue expandIfEqual) {
      this.expandables = expandables;
      this.iterateOverVariable = iterateOverVariable;
      this.expandIfAllAvailable = expandIfAllAvailable;
      this.expandIfNoneAvailable = expandIfNoneAvailable;
      this.expandIfTrue = expandIfTrue;
      this.expandIfFalse = expandIfFalse;
      this.expandIfEqual = expandIfEqual;
    }

    @Override
    public void expand(
        CcToolchainVariables variables,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        final List<String> commandLine)
        throws ExpansionException {
      if (!canBeExpanded(variables, expander, pathMapper)) {
        return;
      }
      if (iterateOverVariable != null) {
        for (CcToolchainVariables.VariableValue variableValue :
            variables.getSequenceVariable(iterateOverVariable, expander, pathMapper)) {
          CcToolchainVariables nestedVariables =
              new SingleVariables(variables, iterateOverVariable, variableValue);
          for (Expandable expandable : expandables) {
            expandable.expand(nestedVariables, expander, pathMapper, commandLine);
          }
        }
      } else {
        for (Expandable expandable : expandables) {
          expandable.expand(variables, expander, pathMapper, commandLine);
        }
      }
    }

    private boolean canBeExpanded(
        CcToolchainVariables variables, @Nullable ArtifactExpander expander, PathMapper pathMapper)
        throws ExpansionException {
      for (String variable : expandIfAllAvailable) {
        if (!variables.isAvailable(variable, expander)) {
          return false;
        }
      }
      for (String variable : expandIfNoneAvailable) {
        if (variables.isAvailable(variable, expander)) {
          return false;
        }
      }
      if (expandIfTrue != null
          && (!variables.isAvailable(expandIfTrue, expander)
              || !variables.getVariable(expandIfTrue, pathMapper).isTruthy())) {
        return false;
      }
      if (expandIfFalse != null
          && (!variables.isAvailable(expandIfFalse, expander)
              || variables.getVariable(expandIfFalse, pathMapper).isTruthy())) {
        return false;
      }
      if (expandIfEqual != null
          && (!variables.isAvailable(expandIfEqual.variable, expander)
              || !variables
                  .getVariable(expandIfEqual.variable, pathMapper)
                  .getStringValue(expandIfEqual.variable, pathMapper)
                  .equals(expandIfEqual.value))) {
        return false;
      }
      return true;
    }

    /**
     * Expands all flags in this group and adds them to {@code commandLine}.
     *
     * <p>The flags of the group will be expanded either:
     *
     * <ul>
     *   <li>once, if there is no variable of sequence type in any of the group's flags, or
     *   <li>for each element in the sequence, if there is 'iterate_over' variable specified
     *       (preferred, explicit way), or
     *   <li>for each element in the sequence, if there is only one sequence variable used in the
     *       body of the flag_group (deprecated, implicit way). Having more than a single variable
     *       of sequence type in a single flag group with implicit iteration is not supported. Use
     *       explicit 'iterate_over' instead.
     * </ul>
     */
    private void expandCommandLine(
        CcToolchainVariables variables,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        final List<String> commandLine)
        throws ExpansionException {
      expand(variables, expander, pathMapper, commandLine);
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof FlagGroup that) {
        return Iterables.elementsEqual(expandables, that.expandables)
            && Objects.equals(iterateOverVariable, that.iterateOverVariable)
            && Iterables.elementsEqual(expandIfAllAvailable, that.expandIfAllAvailable)
            && Iterables.elementsEqual(expandIfNoneAvailable, that.expandIfNoneAvailable)
            && Objects.equals(expandIfTrue, that.expandIfTrue)
            && Objects.equals(expandIfFalse, that.expandIfFalse)
            && Objects.equals(expandIfEqual, that.expandIfEqual);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          expandables,
          iterateOverVariable,
          expandIfAllAvailable,
          expandIfNoneAvailable,
          expandIfTrue,
          expandIfFalse,
          expandIfEqual);
    }

    ImmutableList<Expandable> getExpandables() {
      return expandables;
    }

    String getIterateOverVariable() {
      return iterateOverVariable;
    }

    ImmutableSet<String> getExpandIfAllAvailable() {
      return expandIfAllAvailable;
    }

    ImmutableSet<String> getExpandIfNoneAvailable() {
      return expandIfNoneAvailable;
    }

    String getExpandIfTrue() {
      return expandIfTrue;
    }

    String getExpandIfFalse() {
      return expandIfFalse;
    }

    VariableWithValue getExpandIfEqual() {
      return expandIfEqual;
    }
  }

  private static boolean isWithFeaturesSatisfied(
      Collection<WithFeatureSet> withFeatureSets, Set<String> enabledFeatureNames) {
    if (withFeatureSets.isEmpty()) {
      return true;
    }
    for (WithFeatureSet featureSet : withFeatureSets) {
      if (enabledFeatureNames.containsAll(featureSet.getFeatures())
          && featureSet.getNotFeatures().stream().noneMatch(enabledFeatureNames::contains)) {
        return true;
      }
    }
    return false;
  }

  /** Groups a set of flags to apply for certain actions. */
  @Immutable
  public static class FlagSet {
    private final ImmutableSet<String> actions;
    private final ImmutableSet<String> expandIfAllAvailable;
    private final ImmutableSet<WithFeatureSet> withFeatureSets;
    private final ImmutableList<FlagGroup> flagGroups;

    private FlagSet(CToolchain.FlagSet flagSet) throws EvalException {
      this(flagSet, ImmutableSet.copyOf(flagSet.getActionList()));
    }

    /** Constructs a FlagSet for the given set of actions. */
    private FlagSet(CToolchain.FlagSet flagSet, ImmutableSet<String> actions) throws EvalException {
      this.actions = actions;
      this.expandIfAllAvailable = ImmutableSet.copyOf(flagSet.getExpandIfAllAvailableList());
      ImmutableSet.Builder<WithFeatureSet> featureSetBuilder = ImmutableSet.builder();
      for (CToolchain.WithFeatureSet withFeatureSet : flagSet.getWithFeatureList()) {
        featureSetBuilder.add(new WithFeatureSet(withFeatureSet));
      }
      this.withFeatureSets = featureSetBuilder.build();
      ImmutableList.Builder<FlagGroup> builder = ImmutableList.builder();
      for (CToolchain.FlagGroup flagGroup : flagSet.getFlagGroupList()) {
        builder.add(new FlagGroup(flagGroup));
      }
      this.flagGroups = builder.build();
    }

    FlagSet(
        ImmutableSet<String> actions,
        ImmutableSet<String> expandIfAllAvailable,
        ImmutableSet<WithFeatureSet> withFeatureSets,
        ImmutableList<FlagGroup> flagGroups) {
      this.actions = actions;
      this.expandIfAllAvailable = expandIfAllAvailable;
      this.withFeatureSets = withFeatureSets;
      this.flagGroups = flagGroups;
    }

    /** Adds the flags that apply to the given {@code action} to {@code commandLine}. */
    private void expandCommandLine(
        String action,
        CcToolchainVariables variables,
        Set<String> enabledFeatureNames,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        List<String> commandLine)
        throws ExpansionException {
      for (String variable : expandIfAllAvailable) {
        if (!variables.isAvailable(variable, expander)) {
          return;
        }
      }
      if (!isWithFeaturesSatisfied(withFeatureSets, enabledFeatureNames)) {
        return;
      }
      if (!actions.contains(action)) {
        return;
      }
      for (FlagGroup flagGroup : flagGroups) {
        flagGroup.expandCommandLine(variables, expander, pathMapper, commandLine);
      }
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (object instanceof FlagSet that) {
        return Iterables.elementsEqual(actions, that.actions)
            && Iterables.elementsEqual(expandIfAllAvailable, that.expandIfAllAvailable)
            && Iterables.elementsEqual(withFeatureSets, that.withFeatureSets)
            && Iterables.elementsEqual(flagGroups, that.flagGroups);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(actions, expandIfAllAvailable, withFeatureSets, flagGroups);
    }

    ImmutableSet<String> getActions() {
      return actions;
    }

    ImmutableSet<String> getExpandIfAllAvailable() {
      return expandIfAllAvailable;
    }

    ImmutableSet<WithFeatureSet> getWithFeatureSets() {
      return withFeatureSets;
    }

    ImmutableList<FlagGroup> getFlagGroups() {
      return flagGroups;
    }
  }

  /**
   * A set of positive and negative features. This stanza will evaluate to true when every 'feature'
   * is enabled, and every 'not_feature' is not enabled.
   */
  @Immutable
  public static class WithFeatureSet {
    private final ImmutableSet<String> features;
    private final ImmutableSet<String> notFeatures;

    private WithFeatureSet(CToolchain.WithFeatureSet withFeatureSet) {
      this.features = ImmutableSet.copyOf(withFeatureSet.getFeatureList());
      this.notFeatures = ImmutableSet.copyOf(withFeatureSet.getNotFeatureList());
    }

    WithFeatureSet(ImmutableSet<String> features, ImmutableSet<String> notFeatures) {
      this.features = features;
      this.notFeatures = notFeatures;
    }

    public ImmutableSet<String> getFeatures() {
      return features;
    }

    public ImmutableSet<String> getNotFeatures() {
      return notFeatures;
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof WithFeatureSet that) {
        return Iterables.elementsEqual(features, that.features)
            && Iterables.elementsEqual(notFeatures, that.notFeatures);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(features, notFeatures);
    }
  }

  /** Groups a set of environment variables to apply for certain actions. */
  @Immutable
  public static class EnvSet {
    private final ImmutableSet<String> actions;
    private final ImmutableList<EnvEntry> envEntries;
    private final ImmutableSet<WithFeatureSet> withFeatureSets;

    private EnvSet(CToolchain.EnvSet envSet) throws EvalException {
      this.actions = ImmutableSet.copyOf(envSet.getActionList());
      ImmutableList.Builder<EnvEntry> builder = ImmutableList.builder();
      for (CToolchain.EnvEntry envEntry : envSet.getEnvEntryList()) {
        builder.add(new EnvEntry(envEntry));
      }
      ImmutableSet.Builder<WithFeatureSet> withFeatureSetsBuilder = ImmutableSet.builder();
      for (CToolchain.WithFeatureSet withFeatureSet : envSet.getWithFeatureList()) {
        withFeatureSetsBuilder.add(new WithFeatureSet(withFeatureSet));
      }

      this.envEntries = builder.build();
      this.withFeatureSets = withFeatureSetsBuilder.build();
    }

    EnvSet(
        ImmutableSet<String> actions,
        ImmutableList<EnvEntry> envEntries,
        ImmutableSet<WithFeatureSet> withFeatureSets) {
      this.actions = actions;
      this.envEntries = envEntries;
      this.withFeatureSets = withFeatureSets;
    }

    ImmutableSet<String> getActions() {
      return actions;
    }

    ImmutableList<EnvEntry> getEnvEntries() {
      return envEntries;
    }

    ImmutableSet<WithFeatureSet> getWithFeatureSets() {
      return withFeatureSets;
    }

    /**
     * Adds the environment key/value pairs that apply to the given {@code action} to {@code
     * envBuilder}.
     */
    private void expandEnvironment(
        String action,
        CcToolchainVariables variables,
        PathMapper pathMapper,
        Set<String> enabledFeatureNames,
        ImmutableMap.Builder<String, String> envBuilder)
        throws ExpansionException {
      if (!actions.contains(action)) {
        return;
      }
      if (!isWithFeaturesSatisfied(withFeatureSets, enabledFeatureNames)) {
        return;
      }
      for (EnvEntry envEntry : envEntries) {
        envEntry.addEnvEntry(variables, envBuilder, pathMapper);
      }
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof EnvSet that) {
        return Iterables.elementsEqual(actions, that.actions)
            && Iterables.elementsEqual(envEntries, that.envEntries)
            && Iterables.elementsEqual(withFeatureSets, that.withFeatureSets);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(actions, envEntries, withFeatureSets);
    }
  }

  /**
   * An interface for classes representing crosstool messages that can activate each other using
   * 'requires' and 'implies' semantics.
   *
   * <p>Currently there are two types of CrosstoolActivatable: Feature and ActionConfig.
   */
  interface CrosstoolSelectable {

    /**
     * Returns the name of this selectable.
     */
    String getName();
  }

  /** Contains flags for a specific feature. */
  @Immutable
  @AutoCodec
  @VisibleForSerialization
  public static class Feature implements CrosstoolSelectable {
    private static final Interner<Feature> FEATURE_INTERNER = BlazeInterners.newWeakInterner();

    private final String name;
    private final ImmutableList<FlagSet> flagSets;
    private final ImmutableList<EnvSet> envSets;
    private final boolean enabled;
    private final ImmutableList<ImmutableSet<String>> requires;
    private final ImmutableList<String> implies;
    private final ImmutableList<String> provides;

    Feature(CToolchain.Feature feature) throws EvalException {
      this.name = feature.getName();
      ImmutableList.Builder<FlagSet> flagSetBuilder = ImmutableList.builder();
      for (CToolchain.FlagSet flagSet : feature.getFlagSetList()) {
        flagSetBuilder.add(new FlagSet(flagSet));
      }
      this.flagSets = flagSetBuilder.build();

      ImmutableList.Builder<EnvSet> envSetBuilder = ImmutableList.builder();
      for (CToolchain.EnvSet flagSet : feature.getEnvSetList()) {
        envSetBuilder.add(new EnvSet(flagSet));
      }
      this.envSets = envSetBuilder.build();
      this.enabled = feature.getEnabled();

      ImmutableList.Builder<ImmutableSet<String>> requiresBuilder = ImmutableList.builder();
      for (CToolchain.FeatureSet requiresFeatureSet : feature.getRequiresList()) {
        ImmutableSet<String> featureSet = ImmutableSet.copyOf(requiresFeatureSet.getFeatureList());
        requiresBuilder.add(featureSet);
      }
      this.requires = requiresBuilder.build();
      this.implies = ImmutableList.copyOf(feature.getImpliesList());
      this.provides = ImmutableList.copyOf(feature.getProvidesList());
    }

    public Feature(
        String name,
        ImmutableList<FlagSet> flagSets,
        ImmutableList<EnvSet> envSets,
        boolean enabled,
        ImmutableList<ImmutableSet<String>> requires,
        ImmutableList<String> implies,
        ImmutableList<String> provides) {
      this.name = name;
      this.flagSets = flagSets;
      this.envSets = envSets;
      this.enabled = enabled;
      this.requires = requires;
      this.implies = implies;
      this.provides = provides;
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static Feature intern(Feature feature) {
      return FEATURE_INTERNER.intern(feature);
    }

    @Override
    public String getName() {
      return name;
    }

    /** Adds environment variables for the given action to the provided builder. */
    private void expandEnvironment(
        String action,
        CcToolchainVariables variables,
        PathMapper pathMapper,
        Set<String> enabledFeatureNames,
        ImmutableMap.Builder<String, String> envBuilder)
        throws ExpansionException {
      for (EnvSet envSet : envSets) {
        envSet.expandEnvironment(action, variables, pathMapper, enabledFeatureNames, envBuilder);
      }
    }

    /** Adds the flags that apply to the given {@code action} to {@code commandLine}. */
    private void expandCommandLine(
        String action,
        CcToolchainVariables variables,
        Set<String> enabledFeatureNames,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        List<String> commandLine)
        throws ExpansionException {
      for (FlagSet flagSet : flagSets) {
        flagSet.expandCommandLine(
            action, variables, enabledFeatureNames, expander, pathMapper, commandLine);
      }
    }

    ImmutableList<FlagSet> getFlagSets() {
      return flagSets;
    }

    ImmutableList<EnvSet> getEnvSets() {
      return envSets;
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof Feature that) {
        return name.equals(that.name)
            && Iterables.elementsEqual(flagSets, that.flagSets)
            && Iterables.elementsEqual(envSets, that.envSets)
            && Iterables.elementsEqual(requires, that.requires)
            && Iterables.elementsEqual(implies, that.implies)
            && Iterables.elementsEqual(provides, that.provides)
            && enabled == that.enabled;
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, flagSets, envSets, requires, implies, provides, enabled);
    }

    boolean isEnabled() {
      return enabled;
    }

    public ImmutableList<ImmutableSet<String>> getRequires() {
      return requires;
    }

    public ImmutableList<String> getImplies() {
      return implies;
    }

    public ImmutableList<String> getProvides() {
      return provides;
    }
  }

  /**
   * An executable to be invoked by a blaze action. Can carry information on its platform
   * restrictions.
   */
  @Immutable
  public static class Tool {
    private final PathFragment toolPathFragment;
    private final CToolchain.Tool.PathOrigin toolPathOrigin;
    private final ImmutableSet<String> executionRequirements;
    private final ImmutableSet<WithFeatureSet> withFeatureSetSets;

    // Caching tool path string.
    @Nullable private String toolPathString = null;

    private Tool(CToolchain.Tool tool, ImmutableSet<WithFeatureSet> withFeatureSetSets)
        throws EvalException {
      this(
          PathFragment.create(tool.getToolPath()),
          tool.getToolPathOrigin(),
          ImmutableSet.copyOf(tool.getExecutionRequirementList()),
          withFeatureSetSets);
    }

    @VisibleForTesting
    public Tool(
        PathFragment toolPathFragment,
        CToolchain.Tool.PathOrigin toolPathOrigin,
        ImmutableSet<String> executionRequirements,
        ImmutableSet<WithFeatureSet> withFeatureSetSets)
        throws EvalException {
      checkToolPath(toolPathFragment, toolPathOrigin);
      this.toolPathFragment = toolPathFragment;
      this.toolPathOrigin = toolPathOrigin;
      this.executionRequirements = executionRequirements;
      this.withFeatureSetSets = withFeatureSetSets;
    }

    @Deprecated
    @VisibleForTesting
    public Tool(
        PathFragment toolPathFragment,
        ImmutableSet<String> executionRequirements,
        ImmutableSet<WithFeatureSet> withFeatureSetSets)
        throws EvalException {
      this(
          toolPathFragment,
          CToolchain.Tool.PathOrigin.CROSSTOOL_PACKAGE,
          executionRequirements,
          withFeatureSetSets);
    }

    private static void checkToolPath(PathFragment toolPath, CToolchain.Tool.PathOrigin origin)
        throws EvalException {
      switch (origin) {
        case CROSSTOOL_PACKAGE:
          // For legacy reasons, we allow absolute and relative paths here.
          return;

        case FILESYSTEM_ROOT:
          if (!toolPath.isAbsolute()) {
            throw Starlark.errorf(
                "Tool-path with origin FILESYSTEM_ROOT must be absolute, got '%s'.",
                toolPath.getPathString());
          }
          return;

        case WORKSPACE_ROOT:
          if (toolPath.isAbsolute()) {
            throw Starlark.errorf(
                "Tool-path with origin WORKSPACE_ROOT must be relative, got '%s'.",
                toolPath.getPathString());
          }
          return;
      }

      // Unreached.
      throw new IllegalStateException();
    }

    /** Returns the path to this action's tool relative to the provided crosstool path. */
    String getToolPathString(PathFragment ccToolchainPath) {
      switch (toolPathOrigin) {
        case CROSSTOOL_PACKAGE:
          // Legacy behavior.
          if (toolPathString == null) {
            toolPathString = ccToolchainPath.getRelative(toolPathFragment).getSafePathString();
          }
          return toolPathString;

        case FILESYSTEM_ROOT: // fallthrough.
        case WORKSPACE_ROOT:
          return toolPathFragment.getSafePathString();
      }

      // Unreached.
      throw new IllegalStateException();
    }

    /**
     * Returns a list of requirement hints that apply to the execution of this tool.
     */
    ImmutableSet<String> getExecutionRequirements() {
      return executionRequirements;
    }

    /**
     * Returns a set of {@link WithFeatureSet} instances used to decide whether to use this tool
     * given a set of enabled features.
     */
    ImmutableSet<WithFeatureSet> getWithFeatureSetSets() {
      return withFeatureSetSets;
    }

    PathFragment getToolPathFragment() {
      return toolPathFragment;
    }

    CToolchain.Tool.PathOrigin getToolPathOrigin() {
      return toolPathOrigin;
    }
  }

  /**
   * A container for information on a particular blaze action.
   *
   * <p>An ActionConfig can select a tool for its blaze action based on the set of active features.
   * Internally, an ActionConfig maintains an ordered list (the order being that of the list of
   * tools in the crosstool action_config message) of such tools and the feature sets for which they
   * are valid. For a given feature configuration, the ActionConfig will consider the first tool in
   * that list with a feature set that matches the configuration to be the tool for its blaze
   * action.
   *
   * <p>ActionConfigs can be activated by features. That is, a particular feature can cause an
   * ActionConfig to be applied in its "implies" field. Blaze may include certain actions in the
   * action graph only if a corresponding ActionConfig is activated in the toolchain - this provides
   * the crosstool with a mechanism for adding certain actions to the action graph based on feature
   * configuration.
   *
   * <p>It is invalid for a toolchain to contain two action configs for the same blaze action. In
   * that case, blaze will throw an error when it consumes the crosstool.
   */
  @Immutable
  @AutoCodec
  public static class ActionConfig implements CrosstoolSelectable {
    static final String FLAG_SET_WITH_ACTION_ERROR =
        "action_config %s specifies actions.  An action_config's flag sets automatically apply "
            + "to the configured action.  Thus, you must not specify action lists in an "
            + "action_config's flag set.";

    private static final Interner<ActionConfig> ACTION_CONFIG_INTERNER =
        BlazeInterners.newWeakInterner();

    private final String configName;
    private final String actionName;
    private final ImmutableList<Tool> tools;
    private final ImmutableList<FlagSet> flagSets;
    private final boolean enabled;
    private final ImmutableList<String> implies;

    ActionConfig(CToolchain.ActionConfig actionConfig) throws EvalException {
      this.configName = actionConfig.getConfigName();
      this.actionName = actionConfig.getActionName();

      ImmutableList.Builder<Tool> tools = ImmutableList.builder();
      for (CToolchain.Tool tool : actionConfig.getToolList()) {
        ImmutableSet<WithFeatureSet> withFeatureSetSets =
            tool.getWithFeatureList().stream()
                .map(f -> new WithFeatureSet(f))
                .collect(ImmutableSet.toImmutableSet());
        tools.add(new Tool(tool, withFeatureSetSets));
      }
      this.tools = tools.build();

      ImmutableList.Builder<FlagSet> flagSetBuilder = ImmutableList.builder();
      for (CToolchain.FlagSet flagSet : actionConfig.getFlagSetList()) {
        if (!flagSet.getActionList().isEmpty()) {
          throw Starlark.errorf(FLAG_SET_WITH_ACTION_ERROR, configName);
        }

        flagSetBuilder.add(new FlagSet(flagSet, ImmutableSet.of(actionName)));
      }
      this.flagSets = flagSetBuilder.build();

      this.enabled = actionConfig.getEnabled();
      this.implies = ImmutableList.copyOf(actionConfig.getImpliesList());
    }

    public ActionConfig(
        String configName,
        String actionName,
        ImmutableList<Tool> tools,
        ImmutableList<FlagSet> flagSets,
        boolean enabled,
        ImmutableList<String> implies) {
      this.configName = configName;
      this.actionName = actionName;
      this.tools = tools;
      this.flagSets = flagSets;
      this.enabled = enabled;
      this.implies = implies;
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static ActionConfig intern(ActionConfig actionConfig) {
      return ACTION_CONFIG_INTERNER.intern(actionConfig);
    }

    @Override
    public String getName() {
      return configName;
    }

    /**
     * Returns the name of the blaze action this action config applies to.
     */
    String getActionName() {
      return actionName;
    }

    /**
     * Returns the path to this action's tool relative to the provided crosstool path given a set
     * of enabled features.
     */
    private Tool getTool(final Set<String> enabledFeatureNames) {
      Optional<Tool> tool =
          tools
              .stream()
              .filter(t -> isWithFeaturesSatisfied(t.getWithFeatureSetSets(), enabledFeatureNames))
              .findFirst();
      if (tool.isPresent()) {
        return tool.get();
      } else {
        throw new IllegalArgumentException(
            "Matching tool for action "
                + getActionName()
                + " not "
                + "found for given feature configuration");
      }
    }

    /** Adds the flags that apply to this action to {@code commandLine}. */
    private void expandCommandLine(
        CcToolchainVariables variables,
        Set<String> enabledFeatureNames,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        List<String> commandLine)
        throws ExpansionException {
      for (FlagSet flagSet : flagSets) {
        flagSet.expandCommandLine(
            actionName, variables, enabledFeatureNames, expander, pathMapper, commandLine);
      }
    }

    boolean isEnabled() {
      return enabled;
    }

    public ImmutableList<String> getImplies() {
      return implies;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (!(other instanceof ActionConfig that)) {
        return false;
      }

      return Objects.equals(configName, that.configName)
          && Objects.equals(actionName, that.actionName)
          && enabled == that.enabled
          && Iterables.elementsEqual(tools, that.tools)
          && Iterables.elementsEqual(flagSets, that.flagSets)
          && Iterables.elementsEqual(implies, that.implies);
    }

    @Override
    public int hashCode() {
      return Objects.hash(configName, actionName, enabled, tools, flagSets, implies);
    }

    ImmutableList<Tool> getTools() {
      return tools;
    }

    ImmutableList<FlagSet> getFlagSets() {
      return flagSets;
    }
  }

  /** A description of how artifacts of a certain type are named. */
  @Immutable
  static class ArtifactNamePattern {
    private final String prefix;
    private final String extension;

    private ArtifactNamePattern(String prefix, String extension) {
      this.prefix = prefix;
      this.extension = extension;
    }

    String getPrefix() {
      return this.prefix;
    }

    String getExtension() {
      return this.extension;
    }

    /** Returns the artifact name that this pattern selects. */
    private String getArtifactName(String baseName) {
      return prefix + baseName + extension;
    }
  }

  static final class ArtifactNamePatternMapper {
    private static final ImmutableMap<ArtifactCategory, ArtifactNamePattern> DEFAULT_PATTERNS =
        Arrays.stream(ArtifactCategory.values())
            .collect(
                ImmutableMap.toImmutableMap(
                    Function.identity(),
                    c -> new ArtifactNamePattern(c.getDefaultPrefix(), c.getDefaultExtension())));

    private final ImmutableMap<ArtifactCategory, ArtifactNamePattern> prefixExtensionOverrides;

    private ArtifactNamePatternMapper(
        ImmutableMap<ArtifactCategory, ArtifactNamePattern> prefixExtensionOverrides) {
      this.prefixExtensionOverrides = prefixExtensionOverrides;
    }

    public ArtifactNamePattern get(ArtifactCategory category) {
      ArtifactNamePattern result = prefixExtensionOverrides.get(category);
      return result != null ? result : DEFAULT_PATTERNS.get(category);
    }

    public ImmutableMap<ArtifactCategory, ArtifactNamePattern> asImmutableMap() {
      // Don't have ImmutableMap.Builder#buildKeepingLast in open-source yet.
      return ImmutableMap.<ArtifactCategory, ArtifactNamePattern>builderWithExpectedSize(
              DEFAULT_PATTERNS.size())
          .putAll(prefixExtensionOverrides)
          .putAll(Maps.filterKeys(DEFAULT_PATTERNS, k -> !prefixExtensionOverrides.containsKey(k)))
          .buildOrThrow();
    }

    static class Builder {
      private final ImmutableMap.Builder<ArtifactCategory, ArtifactNamePattern> overrides =
          ImmutableMap.builder();

      @CanIgnoreReturnValue
      Builder addOverride(ArtifactCategory category, String prefix, String extension) {
        if (!category.getDefaultPrefix().equals(prefix)
            || !category.getDefaultExtension().equals(extension)) {
          overrides.put(category, new ArtifactNamePattern(prefix, extension));
        }
        return this;
      }

      ArtifactNamePatternMapper build() {
        return new ArtifactNamePatternMapper(overrides.buildOrThrow());
      }
    }
  }

  /** Captures the set of enabled features and action configs for a rule. */
  @Immutable
  @AutoCodec
  @SuppressWarnings("InconsistentHashCode") // enabledFeatureNames, see definition of equals().
  public static class FeatureConfiguration {
    private static final Interner<FeatureConfiguration> FEATURE_CONFIGURATION_INTERNER =
        BlazeInterners.newWeakInterner();

    private final ImmutableSet<String> requestedFeatures;
    private final ImmutableSet<String> enabledFeatureNames;
    private final ImmutableList<Feature> enabledFeatures;
    private final ImmutableSet<String> enabledActionConfigActionNames;

    private final ImmutableMap<String, ActionConfig> actionConfigByActionName;

    private final PathFragment ccToolchainPath;

    /**
     * {@link FeatureConfiguration} instance that doesn't produce any command lines. This is to be
     * used when creation of the real {@link FeatureConfiguration} failed, the rule error was
     * reported, but the analysis continues to collect more rule errors.
     */
    public static final FeatureConfiguration EMPTY =
        FEATURE_CONFIGURATION_INTERNER.intern(new FeatureConfiguration());

    protected FeatureConfiguration() {
      this(
          /* requestedFeatures= */ ImmutableSet.of(),
          /* enabledFeatures= */ ImmutableList.of(),
          /* enabledActionConfigActionNames= */ ImmutableSet.of(),
          /* actionConfigByActionName= */ ImmutableMap.of(),
          /* ccToolchainPath= */ PathFragment.EMPTY_FRAGMENT);
    }

    FeatureConfiguration(
        ImmutableSet<String> requestedFeatures,
        ImmutableList<Feature> enabledFeatures,
        ImmutableSet<String> enabledActionConfigActionNames,
        ImmutableMap<String, ActionConfig> actionConfigByActionName,
        PathFragment ccToolchainPath) {
      this.requestedFeatures = requestedFeatures;
      this.enabledFeatures = enabledFeatures;

      this.actionConfigByActionName = actionConfigByActionName;
      ImmutableSet.Builder<String> featureBuilder = ImmutableSet.builder();
      for (Feature feature : enabledFeatures) {
        featureBuilder.add(feature.getName());
      }
      this.enabledFeatureNames = featureBuilder.build();
      this.enabledActionConfigActionNames = enabledActionConfigActionNames;
      this.ccToolchainPath = ccToolchainPath;
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static FeatureConfiguration intern(FeatureConfiguration featureConfiguration) {
      return FEATURE_CONFIGURATION_INTERNER.intern(featureConfiguration);
    }

    /**
     * @return whether the given {@code feature} is enabled.
     */
    public boolean isEnabled(String feature) {
      return enabledFeatureNames.contains(feature);
    }

    /** The list of requested features, even if they do not exist in CROSSTOOLs. */
    public ImmutableSet<String> getRequestedFeatures() {
      return requestedFeatures;
    }

    /** @return true if tool_path in action_config points to a real tool, not a dummy placeholder */
    public boolean hasConfiguredLinkerPathInActionConfig() {
      return isEnabled("has_configured_linker_path");
    }

    /** @return whether an action config for the blaze action with the given name is enabled. */
    boolean actionIsConfigured(String actionName) {
      return enabledActionConfigActionNames.contains(actionName);
    }

    /** @return the command line for the given {@code action}. */
    public List<String> getCommandLine(String action, CcToolchainVariables variables)
        throws ExpansionException {
      return getCommandLine(action, variables, /* expander= */ null, PathMapper.NOOP);
    }

    public List<String> getCommandLine(
        String action,
        CcToolchainVariables variables,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper)
        throws ExpansionException {
      List<String> commandLine = new ArrayList<>();
      if (actionIsConfigured(action)) {
        actionConfigByActionName
            .get(action)
            .expandCommandLine(variables, enabledFeatureNames, expander, pathMapper, commandLine);
      }

      for (Feature feature : enabledFeatures) {
        feature.expandCommandLine(
            action, variables, enabledFeatureNames, expander, pathMapper, commandLine);
      }

      return commandLine;
    }

    /**
     * @return the flags expanded for the given {@code action} in per-feature buckets.
     */
    public ImmutableList<Pair<String, List<String>>> getPerFeatureExpansions(
        String action, CcToolchainVariables variables, PathMapper pathMapper)
        throws ExpansionException {
      return getPerFeatureExpansions(action, variables, null, pathMapper);
    }

    public ImmutableList<Pair<String, List<String>>> getPerFeatureExpansions(
        String action,
        CcToolchainVariables variables,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper)
        throws ExpansionException {
      ImmutableList.Builder<Pair<String, List<String>>> perFeatureExpansions =
          ImmutableList.builder();
      if (actionIsConfigured(action)) {
        List<String> commandLine = new ArrayList<>();
        ActionConfig actionConfig = actionConfigByActionName.get(action);
        actionConfig.expandCommandLine(
            variables, enabledFeatureNames, expander, pathMapper, commandLine);
        perFeatureExpansions.add(Pair.of(actionConfig.getName(), commandLine));
      }

      for (Feature feature : enabledFeatures) {
        List<String> commandLine = new ArrayList<>();
        feature.expandCommandLine(
            action, variables, enabledFeatureNames, expander, pathMapper, commandLine);
        perFeatureExpansions.add(Pair.of(feature.getName(), commandLine));
      }

      return perFeatureExpansions.build();
    }

    /**
     * @return the environment variables (key/value pairs) for the given {@code action}.
     */
    public ImmutableMap<String, String> getEnvironmentVariables(
        String action, CcToolchainVariables variables, PathMapper pathMapper)
        throws ExpansionException {
      ImmutableMap.Builder<String, String> envBuilder = ImmutableMap.builder();
      for (Feature feature : enabledFeatures) {
        feature.expandEnvironment(action, variables, pathMapper, enabledFeatureNames, envBuilder);
      }
      return envBuilder.buildOrThrow();
    }

    public String getToolPathForAction(String actionName) {
      Preconditions.checkArgument(
          actionConfigByActionName.containsKey(actionName),
          "Action %s does not have an enabled configuration in the toolchain.",
          actionName);
      ActionConfig actionConfig = actionConfigByActionName.get(actionName);
      return actionConfig.getTool(enabledFeatureNames).getToolPathString(ccToolchainPath);
    }

    ImmutableSet<String> getToolRequirementsForAction(String actionName) {
      Preconditions.checkArgument(
          actionConfigByActionName.containsKey(actionName),
          "Action %s does not have an enabled configuration in the toolchain.",
          actionName);
      ActionConfig actionConfig = actionConfigByActionName.get(actionName);
      return actionConfig.getTool(enabledFeatureNames).getExecutionRequirements();
    }

    @Override
    public boolean equals(Object object) {
      if (object == this) {
        return true;
      }
      if (object instanceof FeatureConfiguration that) {
        // Only compare actionConfigByActionName, enabledActionConfigActionnames and enabledFeatures
        // because enabledFeatureNames is based on the list of Features.
        return Objects.equals(actionConfigByActionName, that.actionConfigByActionName)
            && Iterables.elementsEqual(
                enabledActionConfigActionNames, that.enabledActionConfigActionNames)
            && Iterables.elementsEqual(enabledFeatures, that.enabledFeatures);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          actionConfigByActionName,
          enabledActionConfigActionNames,
          enabledFeatureNames,
          enabledFeatures);
    }

    ImmutableSet<String> getEnabledFeatureNames() {
      return enabledFeatureNames;
    }
  }

  private final ArtifactNamePatternMapper artifactNamePatterns;

  /**
   * All features and action configs in the order in which they were specified in the configuration.
   *
   * <p>We guarantee the command line to be in the order in which the flags were specified in the
   * configuration.
   */
  private final ImmutableList<CrosstoolSelectable> selectables;

  /**
   * Maps the selectables's name to the selectable.
   */
  private final ImmutableMap<String, CrosstoolSelectable> selectablesByName;

  /**
   * Maps an action's name to the ActionConfig.
   */
  private final ImmutableMap<String, ActionConfig> actionConfigsByActionName;

  /**
   * Maps from a selectable to a set of all the selectables it has a direct 'implies' edge to.
   */
  private final ImmutableMultimap<CrosstoolSelectable, CrosstoolSelectable> implies;

  /**
   * Maps from a selectable to all features that have an direct 'implies' edge to this
   * selectable.
   */
  private final ImmutableMultimap<CrosstoolSelectable, CrosstoolSelectable> impliedBy;

  /**
   * Maps from a selectable to a set of selecatable sets, where:
   * <ul>
   * <li>a selectable set satisfies the 'requires' condition, if all selectables in the
   *        selectable set are enabled</li>
   * <li>the 'requires' condition is satisfied, if at least one of the selectable sets satisfies
   *        the 'requires' condition.</li>
   * </ul>
   */
  private final ImmutableMultimap<CrosstoolSelectable, ImmutableSet<CrosstoolSelectable>>
      requires;

  /**
   * Maps from a string to the set of selectables that 'provide' it.
   */
  private final ImmutableMultimap<String, CrosstoolSelectable> provides;

  /**
   * Maps from a selectable to all selectables that have a requirement referencing it.
   *
   * <p>This will be used to determine which selectables need to be re-checked after a selectable
   * was disabled.
   */
  private final ImmutableMultimap<CrosstoolSelectable, CrosstoolSelectable> requiredBy;

  private final ImmutableList<String> defaultSelectables;

  /**
   * A cache of feature selection results, so we do not recalculate the feature selection for all
   * actions. This may not be initialized on deserialization.
   */
  private transient LoadingCache<ImmutableSet<String>, FeatureConfiguration> configurationCache =
      buildConfigurationCache();

  private PathFragment ccToolchainPath;

  /**
   * Constructs the feature configuration from a {@link CcToolchainConfigInfo}.
   *
   * @param ccToolchainConfigInfo the toolchain information as specified by the user.
   * @param ccToolchainPath location of the cc_toolchain.
   * @throws EvalException if the configuration has logical errors.
   */
  @VisibleForTesting
  public CcToolchainFeatures(
      CcToolchainConfigInfo ccToolchainConfigInfo, PathFragment ccToolchainPath)
      throws EvalException {
    // Build up the feature/action config graph.  We refer to features/action configs as
    // 'selectables'.
    // First, we build up the map of name -> selectables in one pass, so that earlier selectables
    // can reference later features in their configuration.
    ImmutableList.Builder<CrosstoolSelectable> selectablesBuilder = ImmutableList.builder();
    HashMap<String, CrosstoolSelectable> selectablesByName = new HashMap<>();

    // Also build a map from action -> action_config, for use in tool lookups
    ImmutableMap.Builder<String, ActionConfig> actionConfigsByActionName = ImmutableMap.builder();

    ImmutableList.Builder<String> defaultSelectablesBuilder = ImmutableList.builder();
    for (Feature feature : ccToolchainConfigInfo.getFeatures()) {
      selectablesBuilder.add(feature);
      selectablesByName.put(feature.getName(), feature);
      if (feature.isEnabled()) {
        defaultSelectablesBuilder.add(feature.getName());
      }
    }

    for (ActionConfig actionConfig : ccToolchainConfigInfo.getActionConfigs()) {
      selectablesBuilder.add(actionConfig);
      selectablesByName.put(actionConfig.getName(), actionConfig);
      actionConfigsByActionName.put(actionConfig.getActionName(), actionConfig);
      if (actionConfig.isEnabled()) {
        defaultSelectablesBuilder.add(actionConfig.getName());
      }
    }
    this.defaultSelectables = defaultSelectablesBuilder.build();

    this.selectables = selectablesBuilder.build();
    this.selectablesByName = ImmutableMap.copyOf(selectablesByName);

    checkForActionNameDups(ccToolchainConfigInfo.getActionConfigs());
    checkForActivatableDups(this.selectables);

    this.actionConfigsByActionName = actionConfigsByActionName.buildOrThrow();

    this.artifactNamePatterns = ccToolchainConfigInfo.getArtifactNamePatterns();

    // Next, we build up all forward references for 'implies', 'requires', and 'provides' edges.
    ImmutableMultimap.Builder<CrosstoolSelectable, CrosstoolSelectable> implies =
        ImmutableMultimap.builder();
    ImmutableMultimap.Builder<CrosstoolSelectable, ImmutableSet<CrosstoolSelectable>> requires =
        ImmutableMultimap.builder();
    ImmutableMultimap.Builder<CrosstoolSelectable, String> provides = ImmutableMultimap.builder();
    // We also store the reverse 'implied by' and 'required by' edges during this pass.
    ImmutableMultimap.Builder<CrosstoolSelectable, CrosstoolSelectable> impliedBy =
        ImmutableMultimap.builder();
    ImmutableMultimap.Builder<CrosstoolSelectable, CrosstoolSelectable> requiredBy =
        ImmutableMultimap.builder();

    for (Feature feature : ccToolchainConfigInfo.getFeatures()) {
      String name = feature.getName();
      CrosstoolSelectable selectable = selectablesByName.get(name);
      for (ImmutableSet<String> requiredFeatures : feature.getRequires()) {
        ImmutableSet.Builder<CrosstoolSelectable> allOf = ImmutableSet.builder();
        for (String requiredName : requiredFeatures) {
          CrosstoolSelectable required = getActivatableOrFail(requiredName, name);
          allOf.add(required);
          requiredBy.put(required, selectable);
        }
        requires.put(selectable, allOf.build());
      }
      for (String impliedName : feature.getImplies()) {
        CrosstoolSelectable implied = getActivatableOrFail(impliedName, name);
        impliedBy.put(implied, selectable);
        implies.put(selectable, implied);
      }
      for (String providesName : feature.getProvides()) {
        provides.put(selectable, providesName);
      }
    }

    for (ActionConfig actionConfig : ccToolchainConfigInfo.getActionConfigs()) {
      String name = actionConfig.getName();
      CrosstoolSelectable selectable = selectablesByName.get(name);
      for (String impliedName : actionConfig.getImplies()) {
        CrosstoolSelectable implied = getActivatableOrFail(impliedName, name);
        impliedBy.put(implied, selectable);
        implies.put(selectable, implied);
      }
    }

    this.implies = implies.build();
    this.requires = requires.build();
    this.provides = provides.build().inverse();
    this.impliedBy = impliedBy.build();
    this.requiredBy = requiredBy.build();
    this.ccToolchainPath = ccToolchainPath;
  }

  private static void checkForActivatableDups(Iterable<CrosstoolSelectable> selectables)
      throws EvalException {
    Collection<String> names = new HashSet<>();
    for (CrosstoolSelectable selectable : selectables) {
      if (!names.add(selectable.getName())) {
        throw Starlark.errorf(
            "Invalid toolchain configuration: feature or action config '%s' was specified multiple"
                + " times.",
            selectable.getName());
      }
    }
  }

  private static void checkForActionNameDups(Iterable<ActionConfig> actionConfigs)
      throws EvalException {
    Collection<String> actionNames = new HashSet<>();
    for (ActionConfig actionConfig : actionConfigs) {
      if (!actionNames.add(actionConfig.getActionName())) {
        throw Starlark.errorf(
            "Invalid toolchain configuration: multiple action configs for action '%s'",
            actionConfig.getActionName());
      }
    }
  }

  /** @return an empty {@code FeatureConfiguration} cache. */
  private LoadingCache<ImmutableSet<String>, FeatureConfiguration> buildConfigurationCache() {
    return Caffeine.newBuilder()
        // TODO(klimek): Benchmark and tweak once we support a larger configuration.
        .maximumSize(10000)
        .build(
            new CacheLoader<ImmutableSet<String>, FeatureConfiguration>() {
              @Override
              public FeatureConfiguration load(ImmutableSet<String> requestedFeatures)
                  throws CollidingProvidesException {
                return computeFeatureConfiguration(requestedFeatures);
              }
            });
  }

  /**
   * Given a list of {@code requestedSelectables}, returns all features that are enabled by the
   * toolchain configuration.
   *
   * <p>A requested feature will not be enabled if the toolchain does not support it (which may
   * depend on other requested features).
   *
   * <p>Additional features will be enabled if the toolchain supports them and they are implied by
   * requested features.
   *
   * <p>If multiple threads call this method we may do additional work in initializing the cache.
   * This reinitialization is benign.
   */
  public FeatureConfiguration getFeatureConfiguration(ImmutableSet<String> requestedSelectables)
      throws CollidingProvidesException {
    try {
      if (configurationCache == null) {
        configurationCache = buildConfigurationCache();
      }
      return configurationCache.get(requestedSelectables);
    } catch (CompletionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), CollidingProvidesException.class);
      throw e;
    }
  }

  /**
   * Given {@code featureSpecification}, returns a FeatureConfiguration with all requested features
   * enabled.
   *
   * <p>A requested feature will not be enabled if the toolchain does not support it (which may
   * depend on other requested features).
   *
   * <p>Additional features will be enabled if the toolchain supports them and they are implied by
   * requested features.
   */
  public FeatureConfiguration computeFeatureConfiguration(ImmutableSet<String> requestedSelectables)
      throws CollidingProvidesException {
    // Command line flags will be output in the order in which they are specified in the toolchain
    // configuration.
    return new FeatureSelection(
            requestedSelectables,
            selectablesByName,
            selectables,
            provides,
            implies,
            impliedBy,
            requires,
            requiredBy,
            actionConfigsByActionName,
            ccToolchainPath)
        .run();
  }

  public ImmutableList<String> getDefaultFeaturesAndActionConfigs() {
    return defaultSelectables;
  }

  /**
   * @return the selectable with the given {@code name}.s
   * @throws EvalException if no selectable with the given name was configured.
   */
  private CrosstoolSelectable getActivatableOrFail(String name, String reference)
      throws EvalException {
    if (!selectablesByName.containsKey(name)) {
      throw Starlark.errorf(
          "Invalid toolchain configuration: feature '%s', which is referenced from feature '%s',"
              + " is not defined.",
          name, reference);
    }
    return selectablesByName.get(name);
  }

  @VisibleForTesting
  Collection<String> getActivatableNames() {
    return selectablesByName.keySet();
  }

  /**
   * Returns the artifact selected by the toolchain for the given action type and action category.
   */
  String getArtifactNameForCategory(ArtifactCategory artifactCategory, String outputName) {
    PathFragment output = PathFragment.create(outputName);
    return output
        .getParentDirectory()
        .getChild(artifactNamePatterns.get(artifactCategory).getArtifactName(output.getBaseName()))
        .getPathString();
  }

  /**
   * Returns the artifact name extension selected by the toolchain for the given artifact category.
   */
  String getArtifactNameExtensionForCategory(ArtifactCategory artifactCategory) {
    return artifactNamePatterns.get(artifactCategory).getExtension();
  }
}
