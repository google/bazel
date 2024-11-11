// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec;

import static java.util.stream.Collectors.joining;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.runtime.proto.MnemonicPolicy;
import com.google.devtools.build.lib.runtime.proto.StrategyPolicy;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.ExecutionOptions.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Registry that collects spawn strategies and rules about their applicability and makes them
 * available for querying through various registry interfaces.
 *
 * <p>An instance of this registry can be created using its {@linkplain Builder builder}, which is
 * available to Blaze modules during server startup.
 */
public final class SpawnStrategyRegistry
    implements DynamicStrategyRegistry, ActionContext, RemoteLocalFallbackRegistry {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final SpawnStrategyPolicy ALLOW_ALL_STRATEGIES =
      SpawnStrategyPolicy.create(MnemonicPolicy.getDefaultInstance());

  private final ImmutableListMultimap<String, SpawnStrategy> mnemonicToStrategies;
  private final StrategyRegexFilter strategyRegexFilter;
  private final StrategyPlatformFilter strategyPlatformFilter;
  private final ImmutableList<? extends SpawnStrategy> defaultStrategies;
  private final ImmutableMultimap<String, SandboxedSpawnStrategy> mnemonicToRemoteDynamicStrategies;
  private final ImmutableMultimap<String, SandboxedSpawnStrategy> mnemonicToLocalDynamicStrategies;
  @Nullable private final AbstractSpawnStrategy remoteLocalFallbackStrategy;

  private SpawnStrategyRegistry(
      ImmutableListMultimap<String, SpawnStrategy> mnemonicToStrategies,
      StrategyRegexFilter strategyRegexFilter,
      StrategyPlatformFilter strategyPlatformFilter,
      ImmutableList<? extends SpawnStrategy> defaultStrategies,
      ImmutableMultimap<String, SandboxedSpawnStrategy> mnemonicToRemoteDynamicStrategies,
      ImmutableMultimap<String, SandboxedSpawnStrategy> mnemonicToLocalDynamicStrategies,
      @Nullable AbstractSpawnStrategy remoteLocalFallbackStrategy) {
    this.mnemonicToStrategies = mnemonicToStrategies;
    this.strategyRegexFilter = strategyRegexFilter;
    this.strategyPlatformFilter = strategyPlatformFilter;
    this.defaultStrategies = defaultStrategies;
    this.mnemonicToRemoteDynamicStrategies = mnemonicToRemoteDynamicStrategies;
    this.mnemonicToLocalDynamicStrategies = mnemonicToLocalDynamicStrategies;
    this.remoteLocalFallbackStrategy = remoteLocalFallbackStrategy;
    logger.atInfo().log("Default strategies: %s", defaultStrategies);
    logger.atInfo().log("Filter strategies: %s", strategyRegexFilter);
    logger.atInfo().log("Mnemonic strategies: %s", mnemonicToStrategies);
    logger.atInfo().log("Remote strategies: %s", mnemonicToRemoteDynamicStrategies);
    logger.atInfo().log("Local strategies: %s", mnemonicToLocalDynamicStrategies);
    logger.atInfo().log("Fallback strategies: %s", remoteLocalFallbackStrategy);
  }

  /**
   * Returns the strategies applying to the given spawn, in priority order.
   *
   * <p>Which strategies are returned is based on the precedence as documented on the construction
   * methods of {@linkplain Builder this registry's builder}.
   *
   * <p>If the reason for selecting the context is worth mentioning to the user, logs a message
   * using the given {@link Reporter}.
   */
  public List<? extends SpawnStrategy> getStrategies(Spawn spawn, @Nullable EventHandler reporter) {
    return strategyPlatformFilter.getStrategies(
        spawn, getStrategies(spawn.getResourceOwner(), spawn.getMnemonic(), reporter));
  }

  /**
   * Returns the strategies applying to the given action, in priority order.
   *
   * <p>Which strategies are returned is based on the precedence as documented on the construction
   * methods of {@linkplain Builder this registry's builder}.
   *
   * <p>If the reason for selecting the context is worth mentioning to the user, logs a message
   * using the given {@link Reporter}.
   *
   * NOTE: This method is public for Blaze, getStrategies(Spawn, EventHandler) must be used in Bazel.
   */
  public List<? extends SpawnStrategy> getStrategies(
      ActionExecutionMetadata resourceOwner, String mnemonic, @Nullable EventHandler reporter) {
    // Don't override test strategies by --strategy_regexp for backwards compatibility.
    if (!"TestRunner".equals(mnemonic)) {
      String description = resourceOwner.getProgressMessage();
      if (description != null) {
        ImmutableList<? extends SpawnStrategy> regexStrategies =
            strategyRegexFilter.getStrategies(mnemonic, description, reporter);
        if (!regexStrategies.isEmpty()) {
          return regexStrategies;
        }
      }
    }
    if (mnemonicToStrategies.containsKey(mnemonic)) {
      return mnemonicToStrategies.get(mnemonic);
    }
    return defaultStrategies;
  }

  @Override
  public void notifyUsedDynamic(ActionContext.ActionContextRegistry actionContextRegistry) {
    for (SandboxedSpawnStrategy strategy : mnemonicToLocalDynamicStrategies.values()) {
      strategy.usedContext(actionContextRegistry);
    }
    for (SandboxedSpawnStrategy strategy : mnemonicToRemoteDynamicStrategies.values()) {
      strategy.usedContext(actionContextRegistry);
    }
  }

  @Override
  public ImmutableCollection<SandboxedSpawnStrategy> getDynamicSpawnActionContexts(
      Spawn spawn, DynamicMode dynamicMode) {
    ImmutableMultimap<String, SandboxedSpawnStrategy> mnemonicToDynamicStrategies =
        dynamicMode == DynamicStrategyRegistry.DynamicMode.REMOTE
            ? mnemonicToRemoteDynamicStrategies
            : mnemonicToLocalDynamicStrategies;
    if (mnemonicToDynamicStrategies.containsKey(spawn.getMnemonic())) {
      return strategyPlatformFilter.getStrategies(spawn, mnemonicToDynamicStrategies.get(spawn.getMnemonic()));
    }
    if (mnemonicToDynamicStrategies.containsKey("")) {
      return mnemonicToDynamicStrategies.get("");
    }
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public AbstractSpawnStrategy getRemoteLocalFallbackStrategy(Spawn spawn) {
    return strategyPlatformFilter.getStrategies(spawn, Lists.newArrayList(remoteLocalFallbackStrategy))
        .getFirst();
  }

  /**
   * Notifies all (non-dynamic) strategies stored in this registry that they are {@linkplain
   * SpawnStrategy#usedContext used}.
   */
  public void notifyUsed(ActionContext.ActionContextRegistry actionContextRegistry) {
    for (SpawnStrategy strategy : strategyRegexFilter.getFilterToStrategies().values()) {
      strategy.usedContext(actionContextRegistry);
    }
    for (SpawnStrategy strategy : mnemonicToStrategies.values()) {
      strategy.usedContext(actionContextRegistry);
    }
    for (SpawnStrategy strategy : defaultStrategies) {
      strategy.usedContext(actionContextRegistry);
    }
    if (remoteLocalFallbackStrategy != null) {
      remoteLocalFallbackStrategy.usedContext(actionContextRegistry);
    }
  }

  /**
   * Records the list of all spawn strategies that can be returned by the various query methods of
   * this registry to the given reporter.
   */
  void logSpawnStrategies() {
    for (Map.Entry<String, Collection<SpawnStrategy>> entry :
        mnemonicToStrategies.asMap().entrySet()) {
      logger.atInfo().log(
          "MnemonicToStrategyImplementations: \"%s\" = [%s]",
          entry.getKey(), toImplementationNames(entry.getValue()));
    }

    for (Map.Entry<RegexFilter, Collection<SpawnStrategy>> entry :
        strategyRegexFilter.getFilterToStrategies().asMap().entrySet()) {
      Collection<SpawnStrategy> value = entry.getValue();
      logger.atInfo().log(
          "FilterDescriptionToStrategyImplementations: \"%s\" = [%s]",
          entry.getKey(), toImplementationNames(value));
    }
    for (Map.Entry<Label, ImmutableList<SpawnStrategy>> entry :
        strategyPlatformFilter.getFilterToStrategies().entrySet()) {
      Collection<SpawnStrategy> value = entry.getValue();
      logger.atInfo().log(
          "FilterPlatformToStrategyImplementations: \"%s\" = [%s]",
          entry.getKey().getCanonicalName(), toImplementationNames(value));
    }

    logger.atInfo().log(
        "DefaultStrategyImplementations: [%s]", toImplementationNames(defaultStrategies));

    if (remoteLocalFallbackStrategy != null) {
      logger.atInfo().log(
          "RemoteLocalFallbackImplementation: [%s]",
          remoteLocalFallbackStrategy.getClass().getSimpleName());
    }

    for (Map.Entry<String, Collection<SandboxedSpawnStrategy>> entry :
        mnemonicToRemoteDynamicStrategies.asMap().entrySet()) {
      logger.atInfo().log(
          "MnemonicToRemoteDynamicStrategyImplementations: \"%s\" = [%s]",
          entry.getKey(), toImplementationNames(entry.getValue()));
    }

    for (Map.Entry<String, Collection<SandboxedSpawnStrategy>> entry :
        mnemonicToLocalDynamicStrategies.asMap().entrySet()) {
      logger.atInfo().log(
          "MnemonicToLocalDynamicStrategyImplementations: \"%s\" = [%s]",
          entry.getKey(), toImplementationNames(entry.getValue()));
    }
  }

  private static String toImplementationNames(Collection<?> strategies) {
    return strategies.stream()
        .map(strategy -> strategy.getClass().getSimpleName())
        .collect(joining(", "));
  }

  /** Returns a new {@link Builder} suitable for creating instances of SpawnStrategyRegistry. */
  @VisibleForTesting
  public static Builder builder() {
    return new Builder(
        /* strategyPolicy= */ ALLOW_ALL_STRATEGIES,
        /* dynamicRemotePolicy= */ ALLOW_ALL_STRATEGIES,
        /* dynamicLocalPolicy= */ ALLOW_ALL_STRATEGIES);
  }

  /** Returns a new {@link Builder} suitable for creating instances of SpawnStrategyRegistry. */
  public static Builder builder(StrategyPolicy strategyPolicyProto) {
    return new Builder(
        SpawnStrategyPolicy.create(strategyPolicyProto.getMnemonicPolicy()),
        SpawnStrategyPolicy.create(strategyPolicyProto.getDynamicRemotePolicy()),
        SpawnStrategyPolicy.create(strategyPolicyProto.getDynamicLocalPolicy()));
  }

  /**
   * Builder collecting the strategies and restrictions thereon for a {@link SpawnStrategyRegistry}.
   *
   * <p>To {@linkplain SpawnStrategyRegistry#getStrategies match a strategy to a spawn} it needs to
   * be both {@linkplain #registerStrategy registered} and its registered command-line identifier
   * has to match {@linkplain #addDescriptionFilter a filter on the spawn's progress message},
   * {@linkplain #addMnemonicFilter a filter on the spawn's mnemonic} or be part of the default
   * strategies (see below).
   *
   * <p><strong>Default strategies</strong> are either {@linkplain #setDefaultStrategies set
   * explicitly} or, if {@link #setDefaultStrategies} is not called on this builder, comprised of
   * all registered strategies, in registration order (i.e. the earliest strategy registered will be
   * first in the list of strategies returned by {@link SpawnStrategyRegistry#getStrategies}).
   */
  public static final class Builder {

    private final StrategyMapper strategyMapper = new StrategyMapper();
    private final ArrayList<String> strategiesInRegistrationOrder = new ArrayList<>();

    private ImmutableList<String> explicitDefaultStrategies = ImmutableList.of();

    private final SpawnStrategyPolicy strategyPolicy;
    private final SpawnStrategyPolicy dynamicRemotePolicy;
    private final SpawnStrategyPolicy dynamicLocalPolicy;
    // TODO(schmitt): Using a list and autovalue so as to be able to reverse order while legacy sort
    //  is supported. Can be converted to same as mnemonics once legacy behavior is removed.
    private final List<FilterAndIdentifiers> filterAndIdentifiers = new ArrayList<>();
    // Using List values here rather than multimaps as there is no need for the latter's
    // functionality: The values are always replaced as a whole, no adding/creation required.
    private final HashMap<String, List<String>> mnemonicToIdentifiers = new HashMap<>();
    private final HashMap<String, List<String>> mnemonicToRemoteDynamicIdentifiers =
        new HashMap<>();
    private final HashMap<String, List<String>> mnemonicToLocalDynamicIdentifiers = new HashMap<>();
    private final HashMap<Label, List<String>> execPlatformFilters = new HashMap<>();

    @Nullable private String remoteLocalFallbackStrategyIdentifier;

    private Builder(
        SpawnStrategyPolicy strategyPolicy,
        SpawnStrategyPolicy dynamicRemotePolicy,
        SpawnStrategyPolicy dynamicLocalPolicy) {
      this.strategyPolicy = strategyPolicy;
      this.dynamicRemotePolicy = dynamicRemotePolicy;
      this.dynamicLocalPolicy = dynamicLocalPolicy;
    }

    /**
     * Adds a filter limiting any spawn whose {@linkplain
     * com.google.devtools.build.lib.actions.ActionExecutionMetadata#getProgressMessage() owner's
     * progress message} matches the regular expression to only use strategies with the given
     * command-line identifiers, in order.
     *
     * <p>If multiple filters match the same spawn (including an identical filter) the order of last
     * applicable filter registered by this method will be used.
     */
    @CanIgnoreReturnValue
    public Builder addDescriptionFilter(RegexFilter filter, List<String> identifiers) {
      filterAndIdentifiers.add(
          new AutoValue_SpawnStrategyRegistry_FilterAndIdentifiers(
              filter, ImmutableList.copyOf(identifiers)));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addExecPlatformFilter(Label execPlatform, List<String> identifiers) {
      this.execPlatformFilters.put(execPlatform, identifiers);
      return this;
    }

    /**
     * Adds a filter limiting any spawn whose {@linkplain Spawn#getMnemonic() mnemonic}
     * (case-sensitively) matches the given mnemonic to only use strategies with the given
     * command-line identifiers, in order.
     *
     * <p>If the same mnemonic is registered multiple times the last such call will take precedence.
     * Or in other words, last one wins.
     *
     * <p>Note that if a spawn matches a {@linkplain #addDescriptionFilter registered description
     * filter} that filter will take precedence over any mnemonic-based filters.
     */
    @CanIgnoreReturnValue
    public Builder addMnemonicFilter(String mnemonic, List<String> identifiers) {
      mnemonicToIdentifiers.put(mnemonic, identifiers);
      return this;
    }

    /**
     * Sets the strategy names to use in the remote branch of dynamic execution for a set of action
     * mnemonics.
     *
     * <p>During execution, each strategy is {@linkplain SpawnStrategy#canExec(Spawn,
     * ActionContextRegistry) asked} whether it can execute a given Spawn. The first strategy in the
     * list that says so will get the job.
     */
    @CanIgnoreReturnValue
    public Builder addDynamicRemoteStrategies(Map<String, List<String>> strategies) {
      mnemonicToRemoteDynamicIdentifiers.putAll(strategies);
      return this;
    }

    /**
     * Sets the strategy names to use in the local branch of dynamic execution for a number of
     * action mnemonics.
     *
     * <p>During execution, each strategy is {@linkplain SpawnStrategy#canExec(Spawn,
     * ActionContextRegistry) asked} whether it can execute a given Spawn. The first strategy in the
     * list that says so will get the job.
     */
    @CanIgnoreReturnValue
    public Builder addDynamicLocalStrategies(Map<String, List<String>> strategies) {
      mnemonicToLocalDynamicIdentifiers.putAll(strategies);
      return this;
    }

    /**
     * Registers a strategy implementation with this collector, distinguishing it from other
     * strategies with the given command-line identifiers (of which at least one is required).
     *
     * <p>If multiple strategies are registered with the same command-line identifier the last one
     * so registered will take precedence.
     */
    @CanIgnoreReturnValue
    public Builder registerStrategy(SpawnStrategy strategy, String... commandlineIdentifiers) {
      Preconditions.checkArgument(
          commandlineIdentifiers.length >= 1, "At least one commandLineIdentifier must be given");
      for (String identifier : commandlineIdentifiers) {
        strategyMapper.registerStrategy(identifier, strategy);
        strategiesInRegistrationOrder.add(identifier);
      }
      return this;
    }

    /**
     * Explicitly sets the identifiers of default strategies to use if a spawn matches no filters.
     *
     * <p>Note that if this method is not called on the builder, all registered strategies are
     * considered default strategies, in registration order. See also the {@linkplain Builder class
     * documentation}.
     */
    @CanIgnoreReturnValue
    public Builder setDefaultStrategies(List<String> defaultStrategies) {
      // Ensure there are actual strategies and the contents are not empty.
      Preconditions.checkArgument(!defaultStrategies.isEmpty());
      Preconditions.checkArgument(
          defaultStrategies.stream().anyMatch(strategy -> !"".equals(strategy)));
      this.explicitDefaultStrategies = ImmutableList.copyOf(defaultStrategies);
      return this;
    }

    /**
     * Reset the default strategies (see {@link #setDefaultStrategies}) to the reverse of the order
     * they were registered in.
     */
    @CanIgnoreReturnValue
    public Builder resetDefaultStrategies() {
      this.explicitDefaultStrategies = ImmutableList.of();
      return this;
    }

    /**
     * Sets the commandline identifier of the strategy to be used when falling back from remote to
     * local execution.
     *
     * <p>Note that this is an optional setting, if not provided {@link
     * SpawnStrategyRegistry#getRemoteLocalFallbackStrategy()} will return {@code null}. If the
     * value <b>is</b> provided it must match the commandline identifier of a registered strategy
     * (at {@linkplain #build build} time).
     */
    @CanIgnoreReturnValue
    public Builder setRemoteLocalFallbackStrategyIdentifier(String commandlineIdentifier) {
      this.remoteLocalFallbackStrategyIdentifier = commandlineIdentifier;
      return this;
    }

    public boolean isStrategyRegistered(String strategy) {
      return strategiesInRegistrationOrder.contains(strategy);
    }

    /**
     * Finalizes the construction of the registry.
     *
     * @throws AbruptExitException if a strategy command-line identifier was used in a filter or the
     *     default strategies but no strategy for that identifier was registered
     */
    public SpawnStrategyRegistry build() throws AbruptExitException {
      List<FilterAndIdentifiers> orderedFilterAndIdentifiers = Lists.reverse(filterAndIdentifiers);

      ListMultimap<RegexFilter, String> filterToIdentifiers = LinkedListMultimap.create();
      ListMultimap<RegexFilter, SpawnStrategy> filterToStrategies = LinkedListMultimap.create();
      for (FilterAndIdentifiers filterAndIdentifier : orderedFilterAndIdentifiers) {
        RegexFilter filter = filterAndIdentifier.filter();
        if (!filterToIdentifiers.containsKey(filter)) {
          filterToIdentifiers.putAll(filter, filterAndIdentifier.identifiers());
          filterToStrategies.putAll(
              filter,
              strategyMapper.toStrategies(filterAndIdentifier.identifiers(), "filter " + filter));
        }
      }

      ImmutableMap.Builder<Label, ImmutableList<SpawnStrategy>> platformToStrategies = ImmutableMap.builder();
      for (Map.Entry<Label, List<String>> entry : execPlatformFilters.entrySet()) {
        Label platform = entry.getKey();
        platformToStrategies.put(
          platform,
          strategyMapper.toStrategies(entry.getValue(), "platform " + platform.getCanonicalName()));
      }

      ImmutableListMultimap.Builder<String, SpawnStrategy> mnemonicToStrategies =
          new ImmutableListMultimap.Builder<>();
      for (Map.Entry<String, List<String>> entry : mnemonicToIdentifiers.entrySet()) {
        String mnemonic = entry.getKey();
        ImmutableList<String> sanitizedStrategies =
            strategyPolicy.apply(mnemonic, entry.getValue());
        mnemonicToStrategies.putAll(
            mnemonic, strategyMapper.toStrategies(sanitizedStrategies, "mnemonic " + mnemonic));
      }

      ImmutableListMultimap.Builder<String, SandboxedSpawnStrategy> mnemonicToLocalStrategies =
          new ImmutableListMultimap.Builder<>();
      for (Map.Entry<String, List<String>> entry : mnemonicToLocalDynamicIdentifiers.entrySet()) {
        String mnemonic = entry.getKey();
        ImmutableList<String> sanitizedStrategies =
            dynamicLocalPolicy.apply(mnemonic, entry.getValue());
        mnemonicToLocalStrategies.putAll(
            mnemonic,
            strategyMapper.toSandboxedStrategies(
                sanitizedStrategies, "local mnemonic " + mnemonic));
      }

      ImmutableListMultimap.Builder<String, SandboxedSpawnStrategy> mnemonicToRemoteStrategies =
          new ImmutableListMultimap.Builder<>();
      for (Map.Entry<String, List<String>> entry : mnemonicToRemoteDynamicIdentifiers.entrySet()) {
        String mnemonic = entry.getKey();
        ImmutableList<String> sanitizedStrategies =
            dynamicRemotePolicy.apply(mnemonic, entry.getValue());
        mnemonicToRemoteStrategies.putAll(
            mnemonic,
            strategyMapper.toSandboxedStrategies(
                sanitizedStrategies, "remote mnemonic " + mnemonic));
      }

      AbstractSpawnStrategy remoteLocalFallbackStrategy = null;
      if (remoteLocalFallbackStrategyIdentifier != null) {
        SpawnStrategy strategy =
            strategyMapper.toStrategy(
                remoteLocalFallbackStrategyIdentifier, "remote fallback strategy");
        if (!(strategy instanceof AbstractSpawnStrategy)) {
          // TODO(schmitt): Check if all strategies can use the same base and remove check if so.
          throw createExitException(
              String.format(
                  "'%s' was requested for the remote fallback strategy but is not an"
                      + " abstract spawn strategy (which is required for remote"
                      + " fallback execution).",
                  strategy.getClass().getSimpleName()),
              Code.REMOTE_FALLBACK_STRATEGY_NOT_ABSTRACT_SPAWN);
        }

        remoteLocalFallbackStrategy = (AbstractSpawnStrategy) strategy;
      }

      ImmutableList<? extends SpawnStrategy> defaultStrategies;
      if (explicitDefaultStrategies.isEmpty()) {
        // Use the strategies as registered, in reverse order.
        defaultStrategies =
            strategyMapper.toStrategies(
                strategyPolicy.apply(Lists.reverse(strategiesInRegistrationOrder)),
                "implicit default strategies");
      } else {
        defaultStrategies =
            strategyMapper.toStrategies(
                strategyPolicy.apply(explicitDefaultStrategies), "explicit default strategies");
      }

      return new SpawnStrategyRegistry(
          mnemonicToStrategies.build(),
          new StrategyRegexFilter(
              strategyMapper, strategyPolicy, filterToIdentifiers, filterToStrategies),
          new StrategyPlatformFilter(strategyMapper, platformToStrategies.build()),
          defaultStrategies,
          mnemonicToRemoteStrategies.build(),
          mnemonicToLocalStrategies.build(),
          remoteLocalFallbackStrategy);
    }

    @VisibleForTesting
    public SpawnStrategy toStrategy(String identifier, Object requestName)
        throws AbruptExitException {
      return strategyMapper.toStrategy(identifier, requestName);
    }
  }

  /** Filter that applies strategy_regexp while respecting the command's strategy-policy. */
  private static class StrategyRegexFilter {
    private final SpawnStrategyPolicy strategyPolicy;
    private final ListMultimap<RegexFilter, String> filterToIdentifiers;
    private final ListMultimap<RegexFilter, SpawnStrategy> filterToStrategies;
    private final StrategyMapper strategyMapper;

    public StrategyRegexFilter(
        StrategyMapper strategyMapper,
        SpawnStrategyPolicy strategyPolicy,
        ListMultimap<RegexFilter, String> filterToIdentifiers,
        ListMultimap<RegexFilter, SpawnStrategy> filterToStrategies) {
      this.strategyPolicy = strategyPolicy;
      this.filterToIdentifiers = filterToIdentifiers;
      this.filterToStrategies = filterToStrategies;
      this.strategyMapper = strategyMapper;
    }

    public ImmutableList<? extends SpawnStrategy> getStrategies(
        String mnemonic, String description, @Nullable EventHandler reporter) {
      for (Map.Entry<RegexFilter, List<String>> filterToIdentifiers :
          Multimaps.asMap(filterToIdentifiers).entrySet()) {
        if (filterToIdentifiers.getKey().isIncluded(description)) {
          if (reporter != null) {
            // TODO(schmitt): Why is this done here and not after running canExec?
            reporter.handle(
                Event.progress(description + " with context " + filterToIdentifiers.getValue()));
          }
          // Apply the policy to the identifiers.
          ImmutableList<String> sanitizedStrategies =
              strategyPolicy.apply(mnemonic, filterToIdentifiers.getValue());
          try {
            ImmutableList<? extends SpawnStrategy> strategies =
                strategyMapper.toStrategies(
                    sanitizedStrategies, "filter " + filterToIdentifiers.getKey());
            if (strategies.isEmpty()) {
              // If after sanitizing we get the empty list of strategies, we should return null
              // to indicate that default strategies should be used.
              return ImmutableList.of();
            }
            return strategies;
          } catch (AbruptExitException e) {
            // We should not reach this code because the mapping to strategies already applied
            // while building filterToStrategies
            throw new IllegalStateException(
                String.format(
                    "Failed to apply policy for to strategies that were already applied for"
                        + " mnemonic %s and filter %s",
                    mnemonic, filterToIdentifiers.getKey()),
                e);
          }
        }
      }

      // Return the empty list if no filter matches.
      return ImmutableList.of();
    }

    ListMultimap<RegexFilter, SpawnStrategy> getFilterToStrategies() {
      return filterToStrategies;
    }

    @Override
    public String toString() {
      return filterToStrategies.toString();
    }
  }

  private static class StrategyPlatformFilter {
    private final StrategyMapper strategyMapper;
    private final ImmutableMap<Label, ImmutableList<SpawnStrategy>> platformToStrategies;

    private StrategyPlatformFilter(
      StrategyMapper strategyMapper,
      ImmutableMap<Label, ImmutableList<SpawnStrategy>> platformToStrategies) {
      this.strategyMapper = strategyMapper;
      this.platformToStrategies = platformToStrategies;
    }

    public <T extends SpawnStrategy> List<T> getStrategies(
        Spawn spawn, List<T> candidateStrategies) {
      var platformLabel = spawn.getExecutionPlatformLabel();
      Preconditions.checkNotNull(platformLabel, "Attempting to spawn action without an execution platform.");
      
      var allowedStrategies = platformToStrategies.get(platformLabel);
      if (allowedStrategies != null) {
        List<T> filteredStrategies = new ArrayList<>();
        for (var strategy : candidateStrategies) {
          if (allowedStrategies.contains(strategy)) {
            filteredStrategies.add(strategy);
          }
        }
        return filteredStrategies;
      }

      return candidateStrategies;
    }

    public <T extends SpawnStrategy> ImmutableCollection<T> getStrategies(
        Spawn spawn, ImmutableCollection<T> candidateStrategies) {
      return ImmutableList.copyOf(getStrategies(spawn, Lists.newCopyOnWriteArrayList(candidateStrategies)));
    }

    ImmutableMap<Label, ImmutableList<SpawnStrategy>> getFilterToStrategies() {
      return platformToStrategies;
    }
  }

  /* Maps the strategy identifier (e.g. "local", "worker"..) to the real strategy. */
  private static class StrategyMapper {

    private final Map<String, SpawnStrategy> identifierToStrategy = new HashMap<>();

    StrategyMapper() {}

    void registerStrategy(String identifier, SpawnStrategy strategy) {
      identifierToStrategy.put(identifier, strategy);
    }

    ImmutableList<SpawnStrategy> toStrategies(List<String> identifiers, Object requestName)
        throws AbruptExitException {
      ImmutableList.Builder<SpawnStrategy> strategies = ImmutableList.builder();
      for (String identifier : identifiers) {
        if (identifier.isEmpty()) {
          continue;
        }
        strategies.add(toStrategy(identifier, requestName));
      }
      return strategies.build();
    }

    SpawnStrategy toStrategy(String identifier, Object requestName) throws AbruptExitException {
      SpawnStrategy strategy = identifierToStrategy.get(identifier);
      if (strategy == null) {
        throw createExitException(
            String.format(
                "'%s' was requested for %s but no strategy with that identifier was registered. "
                    + "Valid values are: [%s]",
                identifier, requestName, Joiner.on(", ").join(identifierToStrategy.keySet())),
            Code.STRATEGY_NOT_FOUND);
      }
      return strategy;
    }

    Iterable<? extends SandboxedSpawnStrategy> toSandboxedStrategies(
        List<String> identifiers, Object requestName) throws AbruptExitException {
      Iterable<? extends SpawnStrategy> strategies = toStrategies(identifiers, requestName);
      for (SpawnStrategy strategy : strategies) {
        if (!(strategy instanceof SandboxedSpawnStrategy)) {
          throw createExitException(
              String.format(
                  "'%s' was requested for %s but is not a sandboxed strategy (which is required for"
                      + " dynamic execution).",
                  strategy.getClass().getSimpleName(), requestName),
              Code.DYNAMIC_STRATEGY_NOT_SANDBOXED);
        }
      }

      @SuppressWarnings("unchecked") // Each element of the iterable was checked to fulfil this.
      Iterable<? extends SandboxedSpawnStrategy> sandboxedStrategies =
          (Iterable<? extends SandboxedSpawnStrategy>) strategies;
      return sandboxedStrategies;
    }
  }

  private static AbruptExitException createExitException(String message, Code detailedCode) {
    return new AbruptExitException(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setExecutionOptions(
                    FailureDetails.ExecutionOptions.newBuilder().setCode(detailedCode))
                .build()));
  }

  @AutoValue
  abstract static class FilterAndIdentifiers {

    abstract RegexFilter filter();

    abstract ImmutableList<String> identifiers();
  }
}
