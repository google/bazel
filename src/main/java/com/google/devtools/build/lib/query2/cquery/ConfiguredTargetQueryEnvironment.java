// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.cquery;

import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
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
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.KeyExtractor;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryUtil.ThreadSafeMutableKeyExtractorBackedSetImpl;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver;
import com.google.devtools.build.lib.rules.AliasConfiguredTarget;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
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
 * {@link QueryEnvironment} that runs queries over the configured target (analysis) graph.
 *
 * <p>Aspects are partially supported. Their dependencies appear as implicit dependencies on the
 * targets they're connected to. When using the --experimental_explicit_aspects flag, the aspects
 * themselves are visible as query nodes. See https://github.com/bazelbuild/bazel/issues/16310 for
 * details.
 */
public class ConfiguredTargetQueryEnvironment extends PostAnalysisQueryEnvironment<CqueryNode> {
  /** Common query functions and cquery specific functions. */
  public static final ImmutableList<QueryFunction> FUNCTIONS = populateFunctions();
  /** Cquery specific functions. */
  public static final ImmutableList<QueryFunction> CQUERY_FUNCTIONS = getCqueryFunctions();

  private CqueryOptions cqueryOptions;

  private final TopLevelArtifactContext topLevelArtifactContext;

  private final KeyExtractor<CqueryNode, ActionLookupKey> configuredTargetKeyExtractor;

  private final ConfiguredTargetAccessor accessor;

  @Override
  protected KeyExtractor<CqueryNode, ActionLookupKey> getConfiguredTargetKeyExtractor() {
    return configuredTargetKeyExtractor;
  }

  public ConfiguredTargetQueryEnvironment(
      boolean keepGoing,
      ExtendedEventHandler eventHandler,
      Iterable<QueryFunction> extraFunctions,
      TopLevelConfigurations topLevelConfigurations,
      ImmutableMap<String, BuildConfigurationValue> transitiveConfigurations,
      TargetPattern.Parser mainRepoTargetParser,
      PathPackageLocator pkgPath,
      Supplier<WalkableGraph> walkableGraphSupplier,
      Set<Setting> settings,
      TopLevelArtifactContext topLevelArtifactContext,
      LabelPrinter labelPrinter)
      throws InterruptedException {
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
    this.accessor = new ConfiguredTargetAccessor(walkableGraphSupplier.get(), this);
    this.configuredTargetKeyExtractor = CqueryNode::getLookupKey;
    this.topLevelArtifactContext = topLevelArtifactContext;
  }

  public ConfiguredTargetQueryEnvironment(
      boolean keepGoing,
      ExtendedEventHandler eventHandler,
      Iterable<QueryFunction> extraFunctions,
      TopLevelConfigurations topLevelConfigurations,
      ImmutableMap<String, BuildConfigurationValue> transitiveConfigurations,
      TargetPattern.Parser mainRepoTargetParser,
      PathPackageLocator pkgPath,
      Supplier<WalkableGraph> walkableGraphSupplier,
      CqueryOptions cqueryOptions,
      TopLevelArtifactContext topLevelArtifactContext,
      LabelPrinter labelPrinter)
      throws InterruptedException {
    this(
        keepGoing,
        eventHandler,
        extraFunctions,
        topLevelConfigurations,
        transitiveConfigurations,
        mainRepoTargetParser,
        pkgPath,
        walkableGraphSupplier,
        cqueryOptions.toSettings(),
        topLevelArtifactContext,
        labelPrinter);
    this.cqueryOptions = cqueryOptions;
  }

  private static ImmutableList<QueryFunction> populateFunctions() {
    return new ImmutableList.Builder<QueryFunction>()
        .addAll(QueryEnvironment.DEFAULT_QUERY_FUNCTIONS)
        .addAll(getCqueryFunctions())
        .build();
  }

  private static ImmutableList<QueryFunction> getCqueryFunctions() {
    return ImmutableList.of(new ConfigFunction());
  }

  @Override
  public ImmutableList<NamedThreadSafeOutputFormatterCallback<CqueryNode>>
      getDefaultOutputFormatters(
          TargetAccessor<CqueryNode> accessor,
          ExtendedEventHandler eventHandler,
          OutputStream out,
          SkyframeExecutor skyframeExecutor,
          RuleClassProvider ruleClassProvider,
          PackageManager packageManager,
          StarlarkSemantics starlarkSemantics)
          throws QueryException, InterruptedException {
    AspectResolver aspectResolver =
        cqueryOptions.aspectDeps.createResolver(packageManager, eventHandler);
    return ImmutableList.of(
        new LabelAndConfigurationOutputFormatterCallback(
            eventHandler, cqueryOptions, out, skyframeExecutor, accessor, true, getLabelPrinter()),
        new LabelAndConfigurationOutputFormatterCallback(
            eventHandler, cqueryOptions, out, skyframeExecutor, accessor, false, getLabelPrinter()),
        new TransitionsOutputFormatterCallback(
            eventHandler,
            cqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            ruleClassProvider,
            getLabelPrinter()),
        new ProtoOutputFormatterCallback(
            eventHandler,
            cqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            aspectResolver,
            OutputType.BINARY,
            getLabelPrinter()),
        new ProtoOutputFormatterCallback(
            eventHandler,
            cqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            aspectResolver,
            OutputType.DELIMITED_BINARY,
            labelPrinter),
        new ProtoOutputFormatterCallback(
            eventHandler,
            cqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            aspectResolver,
            OutputType.TEXT,
            getLabelPrinter()),
        new ProtoOutputFormatterCallback(
            eventHandler,
            cqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            aspectResolver,
            OutputType.JSON,
            getLabelPrinter()),
        new BuildOutputFormatterCallback(
            eventHandler, cqueryOptions, out, skyframeExecutor, accessor, getLabelPrinter()),
        new GraphOutputFormatterCallback(
            eventHandler,
            cqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            kct -> getFwdDeps(ImmutableList.of(kct)),
            getLabelPrinter()),
        new StarlarkOutputFormatterCallback(
            eventHandler, cqueryOptions, out, skyframeExecutor, accessor, starlarkSemantics),
        new FilesOutputFormatterCallback(
            eventHandler, cqueryOptions, out, skyframeExecutor, accessor, topLevelArtifactContext));
  }

  @Override
  public String getOutputFormat() {
    return cqueryOptions.outputFormat;
  }

  @Override
  public ConfiguredTargetAccessor getAccessor() {
    return accessor;
  }

  @Override
  public QueryTaskFuture<Void> getTargetsMatchingPattern(
      QueryExpression owner, String pattern, Callback<CqueryNode> callback) {
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
                      List<CqueryNode> transformedResult = new ArrayList<>();
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
   * Returns the {@link CqueryNode} for the given label and configuration if it exists, else null.
   */
  @Nullable
  private CqueryNode getConfiguredTarget(
      Label label, @Nullable BuildConfigurationValue configuration) throws InterruptedException {
    BuildConfigurationKey configurationKey = configuration == null ? null : configuration.getKey();
    CqueryNode target =
        getValueFromKey(
            ConfiguredTargetKey.builder()
                .setLabel(label)
                .setConfigurationKey(configurationKey)
                .build());
    // The configurations might not match if the target's configuration changed due to a transition
    // or trimming. Filters such targets.
    if (target == null || !Objects.equals(configurationKey, target.getConfigurationKey())) {
      return null;
    }
    return target;
  }

  /**
   * Returns the {@link CqueryNode} for the given key if its value is a supported instance of
   * CqueryNode. This function can only receive keys of node types that the calling logic can
   * support. For example, if the caller does not support handling of AspectKey types of
   * CqueryNodes, then this function should not be called with an AspectKey key.
   */
  @Override
  @Nullable
  protected CqueryNode getValueFromKey(SkyKey key) throws InterruptedException {
    SkyValue value = getConfiguredTargetValue(key);
    if (value == null) {
      return null;
    } else if (value instanceof ConfiguredTargetValue configuredTargetValue) {
      return configuredTargetValue.getConfiguredTarget();
    } else if (value instanceof AspectValue && key instanceof AspectKey aspectValue) {
      return aspectValue;
    } else {
      throw new IllegalStateException("unknown value type for CqueryNode");
    }
  }

  /**
   * Returns all configured targets in Skyframe with the given label.
   *
   * <p>If there are no matches, returns an empty list.
   */
  private ImmutableList<CqueryNode> getConfiguredTargetsForLabel(Label label)
      throws InterruptedException {
    ImmutableList.Builder<CqueryNode> ans = ImmutableList.builder();
    for (BuildConfigurationValue config : transitiveConfigurations.values()) {
      CqueryNode kct = getConfiguredTarget(label, config);
      if (kct != null) {
        ans.add(kct);
      }
    }
    CqueryNode nullConfiguredTarget = getNullConfiguredTarget(label);
    if (nullConfiguredTarget != null) {
      ans.add(nullConfiguredTarget);
    }
    return ans.build();
  }

  /**
   * Processes the targets in {@code targets} with the requested {@code configuration}
   *
   * @param pattern the original pattern that {@code targets} were parsed from. Used for error
   *     message.
   * @param targetsFuture the set of {@link ConfiguredTarget}s whose labels represent the targets
   *     being requested.
   * @param configPrefix the configuration to request {@code targets} in. This can be the
   *     configuration's checksum, any prefix of its checksum, or the special identifiers "target"
   *     or "null".
   * @param callback the callback to receive the results of this method.
   * @return {@link QueryTaskCallable} that returns the correctly configured targets.
   */
  @SuppressWarnings("unchecked")
  <T> QueryTaskCallable<Void> getConfiguredTargetsForConfigFunction(
      String pattern,
      QueryTaskFuture<ThreadSafeMutableSet<T>> targetsFuture,
      String configPrefix,
      Callback<CqueryNode> callback) {
    // There's no technical reason other callers beside ConfigFunction can't call this. But they'd
    // need to adjust the error messaging below to not make it config()-specific. Please don't just
    // remove that line: the counter-priority is making error messages as clear, precise, and
    // actionable as possible.
    return () -> {
      ThreadSafeMutableSet<CqueryNode> targets =
          (ThreadSafeMutableSet<CqueryNode>) targetsFuture.getIfSuccessful();
      List<CqueryNode> transformedResult = new ArrayList<>();
      boolean userFriendlyConfigName = true;
      for (CqueryNode target : targets) {
        Label label = getCorrectLabel(target);
        CqueryNode keyedConfiguredTarget;
        switch (configPrefix) {
          case "host" ->
              throw new QueryException(
                  "'host' configuration no longer exists. Use a specific configuration hash"
                      + " instead",
                  ConfigurableQuery.Code.INCORRECT_CONFIG_ARGUMENT_ERROR);
          case "target" -> keyedConfiguredTarget = getTargetConfiguredTarget(label);
          case "null" -> keyedConfiguredTarget = getNullConfiguredTarget(label);
          default -> {
            ImmutableList<String> matchingConfigs =
                transitiveConfigurations.keySet().stream()
                    .filter(fullConfig -> fullConfig.startsWith(configPrefix))
                    .collect(ImmutableList.toImmutableList());
            if (matchingConfigs.size() == 1) {
              keyedConfiguredTarget =
                  getConfiguredTarget(
                      label,
                      Verify.verifyNotNull(transitiveConfigurations.get(matchingConfigs.get(0))));
              userFriendlyConfigName = false;
            } else if (matchingConfigs.size() >= 2) {
              throw new QueryException(
                  String.format(
                      "Configuration ID '%s' is ambiguous.\n"
                          + "'%s' is a prefix of multiple configurations:\n "
                          + Joiner.on("\n ").join(matchingConfigs)
                          + "\n\n"
                          + "Use a longer prefix to uniquely identify one configuration.",
                      configPrefix,
                      configPrefix),
                  ConfigurableQuery.Code.INCORRECT_CONFIG_ARGUMENT_ERROR);
            } else {
              throw new QueryException(
                  String.format("Unknown configuration ID '%s'.\n", configPrefix)
                      + "config()'s second argument must identify a unique configuration.\n"
                      + "\n"
                      + "Valid values:\n"
                      + " 'target' for the default configuration\n"
                      + " 'null' for source files (which have no configuration)\n"
                      + " an arbitrary configuration's full or short ID\n"
                      + "\n"
                      + "A short ID is any prefix of a full ID. cquery shows short IDs. 'bazel "
                      + "config' shows full IDs.\n"
                      + "\n"
                      + "For more help, see https://bazel.build/docs/cquery.",
                  ConfigurableQuery.Code.INCORRECT_CONFIG_ARGUMENT_ERROR);
            }
          }
        }
        if (keyedConfiguredTarget != null) {
          transformedResult.add(keyedConfiguredTarget);
        }
      }
      if (transformedResult.isEmpty()) {
        throw new QueryException(
            String.format(
                "No target (in) %s could be found in the %s",
                pattern,
                userFriendlyConfigName
                    ? "'" + configPrefix + "' configuration"
                    : "configuration with checksum '" + configPrefix + "'"),
            ConfigurableQuery.Code.TARGET_MISSING);
      }
      callback.process(transformedResult);
      return null;
    };
  }

  /**
   * This method has to exist because {@link AliasConfiguredTarget#getLabel()} returns the label of
   * the "actual" target instead of the alias target. Grr.
   */
  @Override
  public Label getCorrectLabel(CqueryNode target) {
    // Dereference any aliases that might be present.
    return target.getOriginalLabel();
  }

  @Nullable
  @Override
  protected CqueryNode getTargetConfiguredTarget(Label label) throws InterruptedException {
    if (topLevelConfigurations.isTopLevelTarget(label)) {
      return getConfiguredTarget(
          label, topLevelConfigurations.getConfigurationForTopLevelTarget(label));
    } else {
      CqueryNode toReturn;
      for (BuildConfigurationValue configuration : topLevelConfigurations.getConfigurations()) {
        toReturn = getConfiguredTarget(label, configuration);
        if (toReturn != null) {
          return toReturn;
        }
      }
      return null;
    }
  }

  @Nullable
  @Override
  protected CqueryNode getNullConfiguredTarget(Label label) throws InterruptedException {
    return getConfiguredTarget(label, null);
  }

  @Nullable
  @Override
  protected RuleConfiguredTarget getRuleConfiguredTarget(CqueryNode configuredTarget) {
    if (configuredTarget instanceof RuleConfiguredTarget ruleConfiguredTarget) {
      return ruleConfiguredTarget;
    }
    return null;
  }

  @Nullable
  @Override
  protected RuleConfiguredTarget getOwningRuleforOutputConfiguredTarget(
      CqueryNode configuredTarget) {
    if (configuredTarget instanceof OutputFileConfiguredTarget outputFileTarget) {
      return outputFileTarget.getGeneratingRule();
    }
    return null;
  }

  @Override
  protected boolean isAliasConfiguredTarget(CqueryNode configuredTarget) {
    return configuredTarget instanceof AliasConfiguredTarget;
  }

  @Nullable
  @Override
  protected BuildConfigurationValue getConfiguration(CqueryNode target) {
    try {
      return target.getConfigurationKey() == null
          ? null
          : (BuildConfigurationValue) graph.getValue(target.getConfigurationKey());
    } catch (InterruptedException e) {
      throw new IllegalStateException("Unexpected interruption during configured target query", e);
    }
  }

  @Override
  protected ActionLookupKey getConfiguredTargetKey(CqueryNode target) {
    return target.getLookupKey();
  }

  @Override
  public ThreadSafeMutableSet<CqueryNode> createThreadSafeMutableSet() {
    return new ThreadSafeMutableKeyExtractorBackedSetImpl<>(
        configuredTargetKeyExtractor, CqueryNode.class, SkyQueryEnvironment.DEFAULT_THREAD_COUNT);
  }
}
