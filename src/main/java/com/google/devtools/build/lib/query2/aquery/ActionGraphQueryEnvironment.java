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
package com.google.devtools.build.lib.query2.aquery;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.query2.NamedThreadSafeOutputFormatterCallback;
import com.google.devtools.build.lib.query2.PostAnalysisQueryEnvironment;
import com.google.devtools.build.lib.query2.SkyQueryEnvironment;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.InputsFunction;
import com.google.devtools.build.lib.query2.engine.KeyExtractor;
import com.google.devtools.build.lib.query2.engine.MnemonicFunction;
import com.google.devtools.build.lib.query2.engine.OutputsFunction;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryUtil.ThreadSafeMutableKeyExtractorBackedSetImpl;
import com.google.devtools.build.lib.rules.AliasConfiguredTarget;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * {@link QueryEnvironment} that is specialized for running action graph queries over the configured
 * target graph.
 */
public class ActionGraphQueryEnvironment
    extends PostAnalysisQueryEnvironment<ConfiguredTargetValue> {

  public static final ImmutableList<QueryFunction> AQUERY_FUNCTIONS = populateAqueryFunctions();
  public static final ImmutableList<QueryFunction> FUNCTIONS = populateFunctions();
  private AqueryOptions aqueryOptions;

  private AqueryActionFilter actionFilters;
  private final KeyExtractor<ConfiguredTargetValue, ActionLookupKey> configuredTargetKeyExtractor;
  private final ConfiguredTargetValueAccessor accessor;

  public ActionGraphQueryEnvironment(
      boolean keepGoing,
      ExtendedEventHandler eventHandler,
      Iterable<QueryFunction> extraFunctions,
      TopLevelConfigurations topLevelConfigurations,
      ImmutableMap<String, BuildConfigurationValue> transitiveConfigurations,
      TargetPattern.Parser mainRepoTargetParser,
      PathPackageLocator pkgPath,
      Supplier<WalkableGraph> walkableGraphSupplier,
      Set<Setting> settings,
      LabelPrinter labelPrinter) {
    super(
        keepGoing,
        eventHandler,
        extraFunctions,
        topLevelConfigurations,
        transitiveConfigurations,
        mainRepoTargetParser,
        pkgPath,
        walkableGraphSupplier,
        settings,
        labelPrinter);
    this.configuredTargetKeyExtractor = ActionGraphQueryEnvironment::getConfiguredTargetKeyImpl;
    this.accessor =
        new ConfiguredTargetValueAccessor(
            walkableGraphSupplier.get(), this::getTarget, this.configuredTargetKeyExtractor);
  }

  public ActionGraphQueryEnvironment(
      boolean keepGoing,
      ExtendedEventHandler eventHandler,
      Iterable<QueryFunction> extraFunctions,
      TopLevelConfigurations topLevelConfigurations,
      ImmutableMap<String, BuildConfigurationValue> transitiveConfigurations,
      TargetPattern.Parser mainRepoTargetParser,
      PathPackageLocator pkgPath,
      Supplier<WalkableGraph> walkableGraphSupplier,
      AqueryOptions aqueryOptions,
      LabelPrinter labelPrinter) {
    this(
        keepGoing,
        eventHandler,
        extraFunctions,
        topLevelConfigurations,
        transitiveConfigurations,
        mainRepoTargetParser,
        pkgPath,
        walkableGraphSupplier,
        aqueryOptions.toSettings(),
        labelPrinter);
    this.aqueryOptions = aqueryOptions;
  }

  private static ImmutableList<QueryFunction> populateFunctions() {
    return ImmutableList.copyOf(QueryEnvironment.DEFAULT_QUERY_FUNCTIONS);
  }

  private static ImmutableList<QueryFunction> populateAqueryFunctions() {
    return ImmutableList.of(new InputsFunction(), new OutputsFunction(), new MnemonicFunction());
  }

  @Override
  public ConfiguredTargetValueAccessor getAccessor() {
    return accessor;
  }

  @Override
  public ImmutableList<NamedThreadSafeOutputFormatterCallback<ConfiguredTargetValue>>
      getDefaultOutputFormatters(
          TargetAccessor<ConfiguredTargetValue> accessor,
          ExtendedEventHandler eventHandler,
          OutputStream out,
          SkyframeExecutor skyframeExecutor,
          RuleClassProvider ruleClassProvider,
          PackageManager packageManager,
          StarlarkSemantics starlarkSemantics) {
    return ImmutableList.of(
        new ActionGraphProtoOutputFormatterCallback(
            eventHandler,
            aqueryOptions,
            out,
            accessor,
            AqueryOutputHandler.OutputType.BINARY,
            actionFilters),
        new ActionGraphProtoOutputFormatterCallback(
            eventHandler,
            aqueryOptions,
            out,
            accessor,
            AqueryOutputHandler.OutputType.DELIMITED_BINARY,
            actionFilters),
        new ActionGraphProtoOutputFormatterCallback(
            eventHandler,
            aqueryOptions,
            out,
            accessor,
            AqueryOutputHandler.OutputType.TEXT,
            actionFilters),
        new ActionGraphProtoOutputFormatterCallback(
            eventHandler,
            aqueryOptions,
            out,
            accessor,
            AqueryOutputHandler.OutputType.JSON,
            actionFilters),
        new ActionGraphTextOutputFormatterCallback(
            eventHandler, aqueryOptions, out, accessor, actionFilters, getLabelPrinter()),
        new ActionGraphSummaryOutputFormatterCallback(
            eventHandler, aqueryOptions, out, accessor, actionFilters));
  }

  @Override
  public String getOutputFormat() {
    return aqueryOptions.outputFormat;
  }

  @Override
  protected KeyExtractor<ConfiguredTargetValue, ActionLookupKey> getConfiguredTargetKeyExtractor() {
    return configuredTargetKeyExtractor;
  }

  @Override
  public Label getCorrectLabel(ConfiguredTargetValue configuredTargetValue) {
    ConfiguredTarget target = configuredTargetValue.getConfiguredTarget();
    // Dereference any aliases that might be present.
    return target.getOriginalLabel();
  }

  @Nullable
  private ConfiguredTargetValue createConfiguredTargetValueFromKey(ConfiguredTargetKey key)
      throws InterruptedException {
    ConfiguredTargetValue value = (ConfiguredTargetValue) getConfiguredTargetValue(key);
    if (value == null
        || !Objects.equals(
            value.getConfiguredTarget().getConfigurationKey(), key.getConfigurationKey())) {
      // The configurations might not match if the target's configuration changed due to a
      // transition or trimming. Filters such targets.
      return null;
    }
    return value;
  }

  @Nullable
  @Override
  protected ConfiguredTargetValue getTargetConfiguredTarget(Label label)
      throws InterruptedException {
    if (topLevelConfigurations.isTopLevelTarget(label)) {
      return createConfiguredTargetValueFromKey(
          ConfiguredTargetKey.builder()
              .setLabel(label)
              .setConfiguration(topLevelConfigurations.getConfigurationForTopLevelTarget(label))
              .build());
    } else {
      ConfiguredTargetValue toReturn;
      for (BuildConfigurationValue configuration : topLevelConfigurations.getConfigurations()) {
        toReturn =
            createConfiguredTargetValueFromKey(
                ConfiguredTargetKey.builder()
                    .setLabel(label)
                    .setConfiguration(configuration)
                    .build());
        if (toReturn != null) {
          return toReturn;
        }
      }
      return null;
    }
  }

  @Nullable
  @Override
  protected ConfiguredTargetValue getNullConfiguredTarget(Label label) throws InterruptedException {
    return createConfiguredTargetValueFromKey(
        ConfiguredTargetKey.builder().setLabel(label).build());
  }

  @Nullable
  @Override
  protected ConfiguredTargetValue getValueFromKey(SkyKey key) throws InterruptedException {
    Preconditions.checkState(key instanceof ConfiguredTargetKey);
    return (ConfiguredTargetValue) getConfiguredTargetValue(key);
  }

  @Nullable
  @Override
  protected RuleConfiguredTarget getRuleConfiguredTarget(
      ConfiguredTargetValue configuredTargetValue) {
    ConfiguredTarget configuredTarget = configuredTargetValue.getConfiguredTarget();
    if (configuredTarget instanceof RuleConfiguredTarget ruleConfiguredTarget) {
      return ruleConfiguredTarget;
    }
    return null;
  }

  @Nullable
  @Override
  protected RuleConfiguredTarget getOwningRuleforOutputConfiguredTarget(
      ConfiguredTargetValue configuredTargetValue) {
    ConfiguredTarget configuredTarget = configuredTargetValue.getConfiguredTarget();
    if (configuredTarget instanceof OutputFileConfiguredTarget outputFileTarget) {
      return outputFileTarget.getGeneratingRule();
    }
    return null;
  }

  @Override
  protected boolean isAliasConfiguredTarget(ConfiguredTargetValue configuredTargetValue) {
    return configuredTargetValue.getConfiguredTarget() instanceof AliasConfiguredTarget;
  }

  @Nullable
  @Override
  protected BuildConfigurationValue getConfiguration(ConfiguredTargetValue configuredTargetValue) {
    ConfiguredTarget target = configuredTargetValue.getConfiguredTarget();
    try {
      return target.getConfigurationKey() == null
          ? null
          : (BuildConfigurationValue) graph.getValue(target.getConfigurationKey());
    } catch (InterruptedException e) {
      throw new IllegalStateException("Unexpected interruption during aquery", e);
    }
  }

  @Override
  protected ConfiguredTargetKey getConfiguredTargetKey(
      ConfiguredTargetValue configuredTargetValue) {
    return getConfiguredTargetKeyImpl(configuredTargetValue);
  }

  @Override
  public QueryTaskFuture<Void> getTargetsMatchingPattern(
      QueryExpression owner, String pattern, Callback<ConfiguredTargetValue> callback) {
    TargetPattern patternToEval;
    try {
      patternToEval = getPattern(pattern);
    } catch (TargetParsingException tpe) {
      try {
        handleError(owner, tpe.getMessage(), tpe.getDetailedExitCode());
      } catch (QueryException qe) {
        return immediateFailedFuture(qe);
      }
      return immediateSuccessfulFuture(null);
    }

    AsyncFunction<TargetParsingException, Void> reportBuildFileErrorAsyncFunction =
        exn -> {
          handleError(owner, exn.getMessage(), exn.getDetailedExitCode());
          return Futures.immediateFuture(null);
        };
    return QueryTaskFutureImpl.ofDelegate(
        Futures.catchingAsync(
            patternToEval.evalAdaptedForAsync(
                resolver,
                getIgnoredSubdirectories(patternToEval.getRepository()),
                /* excludedSubdirectories= */ ImmutableSet.of(),
                (Callback<Target>)
                    partialResult -> {
                      List<ConfiguredTargetValue> transformedResult = new ArrayList<>();
                      for (Target target : partialResult) {
                        transformedResult.addAll(getConfiguredTargetsForLabel(target.getLabel()));
                      }
                      callback.process(transformedResult);
                    },
                QueryException.class),
            TargetParsingException.class,
            reportBuildFileErrorAsyncFunction,
            MoreExecutors.directExecutor()));
  }

  /**
   * Returns all configured targets in Skyframe with the given label.
   *
   * <p>If there are no matches, returns an empty list.
   */
  private ImmutableList<ConfiguredTargetValue> getConfiguredTargetsForLabel(Label label)
      throws InterruptedException {
    ImmutableList.Builder<ConfiguredTargetValue> ans = ImmutableList.builder();
    for (BuildConfigurationValue config : transitiveConfigurations.values()) {
      ConfiguredTargetValue configuredTargetValue =
          createConfiguredTargetValueFromKey(
              ConfiguredTargetKey.builder().setLabel(label).setConfiguration(config).build());
      if (configuredTargetValue != null) {
        ans.add(configuredTargetValue);
      }
    }
    ConfiguredTargetValue nullConfiguredTarget = getNullConfiguredTarget(label);
    if (nullConfiguredTarget != null) {
      ans.add(nullConfiguredTarget);
    }
    return ans.build();
  }

  @Override
  public ThreadSafeMutableSet<ConfiguredTargetValue> createThreadSafeMutableSet() {
    return new ThreadSafeMutableKeyExtractorBackedSetImpl<>(
        configuredTargetKeyExtractor,
        ConfiguredTargetValue.class,
        SkyQueryEnvironment.DEFAULT_THREAD_COUNT);
  }

  public void setActionFilters(AqueryActionFilter actionFilters) {
    this.actionFilters = actionFilters;
  }

  private static ConfiguredTargetKey getConfiguredTargetKeyImpl(ConfiguredTargetValue targetValue) {
    return ConfiguredTargetKey.fromConfiguredTarget(targetValue.getConfiguredTarget());
  }
}
