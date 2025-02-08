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
package com.google.devtools.build.lib.query2;

import static com.google.common.base.MoreObjects.toStringHelper;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet;
import com.google.devtools.build.lib.concurrent.MultisetSemaphore;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.DependencyFilter;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.FilteringPolicies;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.query2.common.AbstractBlazeQueryEnvironment;
import com.google.devtools.build.lib.query2.engine.KeyExtractor;
import com.google.devtools.build.lib.query2.engine.MinDepthUniquifier;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEvalResult;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryExpressionContext;
import com.google.devtools.build.lib.query2.engine.QueryUtil.MinDepthUniquifierImpl;
import com.google.devtools.build.lib.query2.engine.QueryUtil.UniquifierImpl;
import com.google.devtools.build.lib.query2.engine.ThreadSafeOutputFormatterCallback;
import com.google.devtools.build.lib.query2.engine.Uniquifier;
import com.google.devtools.build.lib.rules.AliasConfiguredTarget;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.GraphBackedRecursivePackageProvider;
import com.google.devtools.build.lib.skyframe.GraphBackedRecursivePackageProvider.UniverseTargetPattern;
import com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.RecursivePackageProviderBackedTargetPatternResolver;
import com.google.devtools.build.lib.skyframe.RecursivePkgValueRootPackageExtractor;
import com.google.devtools.build.lib.skyframe.SimplePackageIdentifierBatchingCallback;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.supplier.InterruptibleSupplier;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * {@link QueryEnvironment} that runs queries based on results from the analysis phase.
 *
 * <p>This environment can theoretically be used for multiple queries, but currently is only ever
 * used for one over the course of its lifetime. If this ever changed to be used for multiple, the
 * {@link TargetAccessor} field should be initialized on a per-query basis not a per-environment
 * basis.
 *
 * <p>Aspects are followed if {@link
 * com.google.devtools.build.lib.query2.common.CommonQueryOptions#useAspects} is on.
 */
public abstract class PostAnalysisQueryEnvironment<T> extends AbstractBlazeQueryEnvironment<T> {
  private static final Function<SkyKey, ConfiguredTargetKey> SKYKEY_TO_CTKEY =
      skyKey -> (ConfiguredTargetKey) skyKey.argument();

  protected final TopLevelConfigurations topLevelConfigurations;
  private final TargetPattern.Parser mainRepoTargetParser;
  private final PathPackageLocator pkgPath;
  private final Supplier<WalkableGraph> walkableGraphSupplier;
  protected WalkableGraph graph;

  /**
   * Stores every configuration in the transitive closure of the build graph as a map from its
   * user-friendly hash to the configuration itself.
   *
   * <p>This is used to find configured targets in, e.g. {@code somepath} queries. Given {@code
   * somepath(//foo, //bar)}, cquery finds the configured targets for {@code //foo} and {@code
   * //bar} by creating a {@link ConfiguredTargetKey} from their labels and <i>some</i>
   * configuration, then querying the {@link WalkableGraph} to find the matching configured target.
   *
   * <p>Having this map lets cquery choose from all available configurations in the graph,
   * particularly including configurations that aren't the top-level.
   *
   * <p>This can also be used in cquery's {@code config} function to match against explicitly
   * specified configs. This, in particular, is where having user-friendly hashes is invaluable.
   */
  protected final ImmutableMap<String, BuildConfigurationValue> transitiveConfigurations;

  protected RecursivePackageProviderBackedTargetPatternResolver resolver;

  public PostAnalysisQueryEnvironment(
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
    super(keepGoing, true, Rule.ALL_LABELS, eventHandler, settings, extraFunctions, labelPrinter);
    this.topLevelConfigurations = topLevelConfigurations;
    this.transitiveConfigurations = transitiveConfigurations;
    this.mainRepoTargetParser = mainRepoTargetParser;
    this.pkgPath = pkgPath;
    this.walkableGraphSupplier = walkableGraphSupplier;
  }

  public abstract ImmutableList<NamedThreadSafeOutputFormatterCallback<T>>
      getDefaultOutputFormatters(
          TargetAccessor<T> accessor,
          ExtendedEventHandler eventHandler,
          OutputStream outputStream,
          SkyframeExecutor skyframeExecutor,
          RuleClassProvider ruleClassProvider,
          PackageManager packageManager,
          StarlarkSemantics starlarkSemantics)
          throws QueryException, InterruptedException;

  public abstract String getOutputFormat();

  protected abstract KeyExtractor<T, ActionLookupKey> getConfiguredTargetKeyExtractor();

  @Override
  public QueryEvalResult evaluateQuery(
      QueryExpression expr, ThreadSafeOutputFormatterCallback<T> callback)
      throws QueryException, InterruptedException, IOException {
    beforeEvaluateQuery();
    return evaluateQueryInternal(expr, callback);
  }

  private void beforeEvaluateQuery() throws QueryException {
    graph = walkableGraphSupplier.get();
    GraphBackedRecursivePackageProvider graphBackedRecursivePackageProvider =
        new GraphBackedRecursivePackageProvider(
            graph,
            UniverseTargetPattern.all(),
            pkgPath,
            new RecursivePkgValueRootPackageExtractor());
    resolver =
        new RecursivePackageProviderBackedTargetPatternResolver(
            graphBackedRecursivePackageProvider,
            eventHandler,
            FilteringPolicies.NO_FILTER,
            MultisetSemaphore.unbounded(),
            /* maxConcurrentGetTargetsTasks= */ Optional.empty(),
            SimplePackageIdentifierBatchingCallback::new);
    checkSettings(settings);
  }

  // Check to make sure the settings requested are currently supported by this class
  private void checkSettings(Set<Setting> settings) throws QueryException {
    if (settings.contains(Setting.NO_NODEP_DEPS)
        || settings.contains(Setting.TESTS_EXPRESSION_STRICT)) {
      settings =
          Sets.difference(
              settings, ImmutableSet.of(Setting.ONLY_TARGET_DEPS, Setting.NO_IMPLICIT_DEPS));
      throw new QueryException(
          String.format(
              "The following filter(s) are not currently supported by configured query: %s",
              settings),
          ConfigurableQuery.Code.FILTERS_NOT_SUPPORTED);
    }
  }

  // TODO(bazel-team): It's weird that this untemplated function exists. Fix? Or don't implement?
  @Override
  public Target getTarget(Label label) throws TargetNotFoundException, InterruptedException {
    try {
      return ((PackageValue) walkableGraphSupplier.get().getValue(label.getPackageIdentifier()))
          .getPackage()
          .getTarget(label.getName());
    } catch (NoSuchTargetException e) {
      throw new TargetNotFoundException(e, e.getDetailedExitCode());
    }
  }

  @Override
  public T getOrCreate(T target) {
    return target;
  }

  /**
   * This method has to exist because {@link AliasConfiguredTarget#getLabel()} returns the label of
   * the "actual" target instead of the alias target. Grr.
   */
  public abstract Label getCorrectLabel(T target);

  @Nullable
  protected abstract T getTargetConfiguredTarget(Label label) throws InterruptedException;

  @Nullable
  protected abstract T getNullConfiguredTarget(Label label) throws InterruptedException;

  @Nullable
  public SkyValue getConfiguredTargetValue(SkyKey key) throws InterruptedException {
    return walkableGraphSupplier.get().getValue(key);
  }

  @Nullable
  public AspectValue getAspectValue(SkyKey key) throws InterruptedException {
    return (AspectValue) walkableGraphSupplier.get().getValue(key);
  }

  private boolean isAliasConfiguredTarget(ConfiguredTargetKey key) throws InterruptedException {
    return AliasProvider.isAlias(
        ((ConfiguredTargetValue) getConfiguredTargetValue(key)).getConfiguredTarget());
  }

  protected abstract boolean isAliasConfiguredTarget(T target);

  public InterruptibleSupplier<IgnoredSubdirectories> getIgnoredSubdirectories(
      RepositoryName repositoryName) {
    return () -> {
      IgnoredSubdirectoriesValue ignoredSubdirectoriesValue =
          (IgnoredSubdirectoriesValue)
              walkableGraphSupplier.get().getValue(IgnoredSubdirectoriesValue.key(repositoryName));
      return ignoredSubdirectoriesValue == null
          ? IgnoredSubdirectories.EMPTY
          : ignoredSubdirectoriesValue.asIgnoredSubdirectories();
    };
  }

  @Nullable
  protected abstract T getValueFromKey(SkyKey key) throws InterruptedException;

  protected TargetPattern getPattern(String pattern) throws TargetParsingException {
    return mainRepoTargetParser.parse(pattern);
  }

  @Override
  public LabelPrinter getLabelPrinter() {
    return labelPrinter;
  }

  public ThreadSafeMutableSet<T> getFwdDeps(Iterable<T> targets) throws InterruptedException {
    Map<SkyKey, T> targetsByKey = Maps.newHashMapWithExpectedSize(Iterables.size(targets));
    for (T target : targets) {
      targetsByKey.put(getConfiguredTargetKey(target), target);
    }
    Map<SkyKey, ImmutableList<ClassifiedDependency<T>>> directDeps =
        targetifyValues(targetsByKey, graph.getDirectDeps(targetsByKey.keySet()));
    if (targetsByKey.size() != directDeps.size()) {
      Iterable<ConfiguredTargetKey> missingTargets =
          Sets.difference(targetsByKey.keySet(), directDeps.keySet()).stream()
              .map(SKYKEY_TO_CTKEY)
              .collect(Collectors.toList());
      eventHandler.handle(Event.warn("Targets were missing from graph: " + missingTargets));
    }
    ThreadSafeMutableSet<T> result = createThreadSafeMutableSet();
    for (Map.Entry<SkyKey, ImmutableList<ClassifiedDependency<T>>> entry : directDeps.entrySet()) {
      result.addAll(filterFwdDeps(targetsByKey.get(entry.getKey()), entry.getValue()));
    }
    return result;
  }

  @Override
  public ThreadSafeMutableSet<T> getFwdDeps(Iterable<T> targets, QueryExpressionContext<T> context)
      throws InterruptedException {
    return getFwdDeps(targets);
  }

  private ImmutableList<T> filterFwdDeps(
      T configTarget, ImmutableList<ClassifiedDependency<T>> rawFwdDeps) {
    if (settings.isEmpty()) {
      return getDependencies(rawFwdDeps);
    }
    return getAllowedDeps(configTarget, rawFwdDeps);
  }

  @Override
  public Collection<T> getReverseDeps(Iterable<T> targets, QueryExpressionContext<T> context)
      throws InterruptedException {
    Map<SkyKey, T> targetsByKey = Maps.newHashMapWithExpectedSize(Iterables.size(targets));
    for (T target : targets) {
      targetsByKey.put(getConfiguredTargetKey(target), target);
    }
    Map<SkyKey, ImmutableList<ClassifiedDependency<T>>> reverseDepsByKey =
        targetifyValues(
            targetsByKey,
            skipDelegatingAncestors(graph.getReverseDeps(targetsByKey.keySet())).asMap());
    if (targetsByKey.size() != reverseDepsByKey.size()) {
      Iterable<ConfiguredTargetKey> missingTargets =
          Sets.difference(targetsByKey.keySet(), reverseDepsByKey.keySet()).stream()
              .map(SKYKEY_TO_CTKEY)
              .collect(Collectors.toList());
      eventHandler.handle(Event.warn("Targets were missing from graph: " + missingTargets));
    }
    Map<T, ImmutableList<ClassifiedDependency<T>>> reverseDepsByCT = new HashMap<>();
    for (Map.Entry<SkyKey, ImmutableList<ClassifiedDependency<T>>> entry :
        reverseDepsByKey.entrySet()) {
      reverseDepsByCT.put(targetsByKey.get(entry.getKey()), entry.getValue());
    }
    return reverseDepsByCT.isEmpty() ? Collections.emptyList() : filterReverseDeps(reverseDepsByCT);
  }

  private Collection<T> filterReverseDeps(
      Map<T, ImmutableList<ClassifiedDependency<T>>> rawReverseDeps) {
    Set<T> result = CompactHashSet.create();
    for (Map.Entry<T, ImmutableList<ClassifiedDependency<T>>> targetAndRdeps :
        rawReverseDeps.entrySet()) {
      ImmutableList.Builder<ClassifiedDependency<T>> ruleDeps = ImmutableList.builder();
      for (ClassifiedDependency<T> parent : targetAndRdeps.getValue()) {
        T dependency = parent.dependency;
        if (parent.dependency instanceof RuleConfiguredTarget
            && dependencyFilter != DependencyFilter.ALL_DEPS) {
          ruleDeps.add(parent);
        } else {
          result.add(dependency);
        }
      }
      result.addAll(getAllowedDeps(targetAndRdeps.getKey(), ruleDeps.build()));
    }
    return result;
  }

  /**
   * Expands any delegating ancestors when computing reverse dependencies.
   *
   * <p>The {@link ConfiguredTargetKey} graph contains <i>delegation</i> entries where instead of
   * computing its own value, it delegates to a child with the same labels but a different
   * configuration. This causes problems in reverse dependency traversal because traversal stops at
   * duplicate values. The delegating parent has the same value as the delegate child.
   *
   * <p>This method replaces any delegating ancestor in the set of reverse dependencies with the
   * reverse dependencies of the ancestor.
   */
  private ImmutableListMultimap<SkyKey, SkyKey> skipDelegatingAncestors(
      Map<SkyKey, Iterable<SkyKey>> reverseDeps) throws InterruptedException {
    var result = ImmutableListMultimap.<SkyKey, SkyKey>builder();
    for (Map.Entry<SkyKey, Iterable<SkyKey>> entry : reverseDeps.entrySet()) {
      SkyKey child = entry.getKey();
      Iterable<SkyKey> rdeps = entry.getValue();
      Set<SkyKey> unwoundRdeps = unwindReverseDependencyDelegationLayersIfFound(child, rdeps);
      result.putAll(child, unwoundRdeps == null ? rdeps : unwoundRdeps);
    }
    return result.build();
  }

  @Nullable
  private Set<SkyKey> unwindReverseDependencyDelegationLayersIfFound(
      SkyKey child, Iterable<SkyKey> rdeps) throws InterruptedException {
    // Most rdeps will not be delegating. Performs an optimistic pass that avoids copying.
    boolean foundDelegatingRdep = false;
    for (SkyKey rdepKey : rdeps) {
      if (!rdepKey.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
        continue;
      }
      T rdepValue = getValueFromKey(rdepKey);
      if (rdepValue == null) {
        // Cannot find the actual value, possibly because it failed during analysis.
        // TODO: b/324419258 - Add a test for this case
        continue;
      }
      var actualParentKey = getConfiguredTargetKey(rdepValue);
      if (actualParentKey.equals(child)) {
        // The parent has the same value as the child because it is delegating.
        foundDelegatingRdep = true;
        break;
      }
    }
    if (!foundDelegatingRdep) {
      return null;
    }
    var logicalParents = new HashSet<SkyKey>();
    unwindReverseDependencyDelegationLayers(child, rdeps, logicalParents);
    return logicalParents;
  }

  private void unwindReverseDependencyDelegationLayers(
      SkyKey child, Iterable<SkyKey> rdeps, Set<SkyKey> output) throws InterruptedException {
    // Checks the value of each rdep to see if it is delegating to `child`. If so, fetches its rdeps
    // and processes those, applying the same expansion as needed.
    for (SkyKey rdepKey : rdeps) {
      if (!rdepKey.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
        output.add(rdepKey);
        continue;
      }
      T rdepValue = getValueFromKey(rdepKey);
      if (rdepValue == null) {
        // Cannot find the actual value, possibly because it failed during analysis.
        // TODO: b/324419258 - Add a test for this case
        continue;
      }
      var actualParentKey = getConfiguredTargetKey(rdepValue);
      if (!actualParentKey.equals(child)) {
        output.add(rdepKey);
        continue;
      }
      // Otherwise `rdepKey` is delegating to child and needs to be unwound.
      Iterable<SkyKey> rdepParents = graph.getReverseDeps(ImmutableList.of(rdepKey)).get(rdepKey);
      // Applies this recursively in case there are multiple layers of delegation.
      unwindReverseDependencyDelegationLayers(child, rdepParents, output);
    }
  }

  /**
   * @param target source target
   * @param deps next level of deps to filter
   */
  private ImmutableList<T> getAllowedDeps(T target, Collection<ClassifiedDependency<T>> deps) {
    // It's possible to query on a target that's configured in an exec configuration. In those
    // cases if --notool_deps is turned on, we only allow reachable targets that are ALSO in an
    // exec config. This is somewhat counterintuitive and subject to change in the future but seems
    // like the best option right now.
    if (settings.contains(Setting.ONLY_TARGET_DEPS)) {
      BuildConfigurationValue currentConfig = getConfiguration(target);
      if (currentConfig != null && currentConfig.isToolConfiguration()) {
        deps =
            deps.stream()
                .filter(
                    dep ->
                        getConfiguration(dep.dependency) != null
                            && getConfiguration(dep.dependency).isToolConfiguration())
                .collect(Collectors.toList());
      } else {
        deps =
            deps.stream()
                .filter(
                    dep ->
                        // We include source files, which have null configuration, even though
                        // they can also appear on exec-configured attributes like genrule#tools.
                        // While this may not be strictly correct, it's better to overapproximate
                        // than underapproximate the results.
                        getConfiguration(dep.dependency) == null
                            || !getConfiguration(dep.dependency).isToolConfiguration())
                .collect(Collectors.toList());
      }
    }
    if (settings.contains(Setting.NO_IMPLICIT_DEPS)) {
        deps = deps.stream().filter(dep -> !dep.implicit).collect(Collectors.toList());
    }
    return getDependencies(deps);
  }

  protected abstract RuleConfiguredTarget getRuleConfiguredTarget(T target);

  /**
   * If {@code target} is an {@link OutputFileConfiguredTarget}, return the rule that produces it.
   * Else returns null.
   */
  protected abstract RuleConfiguredTarget getOwningRuleforOutputConfiguredTarget(T target);

  /**
   * Returns targetified dependencies wrapped as {@link ClassifiedDependency} objects which include
   * information on if the target is an implicit or explicit dependency.
   *
   * <p>A target may have toolchain dependencies and aspects attached to its deps that declare their
   * own dependencies through private attributes. All of these are considered implicit dependencies
   * of the target.
   *
   * @param parent Parent target that knows about its attribute-attached implicit deps. If this is
   *     null, that is a signal from the caller that all dependencies should be considered implicit.
   * @param dependencies dependencies to targetify
   * @param knownCtDeps the keys of configured target deps already added to the deps list. Outside
   *     callers should pass an empty set. This is used for recursive calls to prevent aspect and
   *     toolchain deps from duplicating the target's direct deps.
   * @param resolvedAspectClasses aspect classes that have already been examined for dependencies.
   *     Aspects can add dependencies through privately declared label-based attributes. Aspects may
   *     also propagate down the target's deps. So if an aspect of type C is attached to target T
   *     that depends on U and V, the aspect may depend on more type C aspects attached to U and V
   *     that themselves depend on type C aspects attached to U and V's deps and so on. Since C
   *     defines the aspect's deps, all of those aspect instances have the same deps, which makes
   *     examinining each of them down T's transitive deps very wasteful. This parameter lets us
   *     avoid that redundancy.
   */
  private ImmutableList<ClassifiedDependency<T>> targetifyValues(
      @Nullable T parent,
      Iterable<SkyKey> dependencies,
      Set<SkyKey> knownCtDeps,
      Set<AspectClass> resolvedAspectClasses)
      throws InterruptedException {
    Collection<ConfiguredTargetKey> implicitDeps = null;
    if (parent != null) {
      RuleConfiguredTarget ruleConfiguredTarget = getRuleConfiguredTarget(parent);
      if (ruleConfiguredTarget != null) {
        implicitDeps = ruleConfiguredTarget.getImplicitDeps();
      }
    }

    boolean explicitAspects =
        settings.containsAll(ImmutableSet.of(Setting.INCLUDE_ASPECTS, Setting.EXPLICIT_ASPECTS));

    ImmutableList.Builder<ClassifiedDependency<T>> values = ImmutableList.builder();
    // TODO(bazel-team): The end-goal approach is to treat aspects and toolchains as
    // first-class query nodes just like targets. In other words, let query expressions reference
    // them (they also have identifying labels) and make the graph connections between targets,
    // aspects, and toolchains explicit. That would permit more detailed queries and eliminate the
    // per-key-type special casing below.
    // This is being experimentally implemented in phases. Currently support for aspects has been
    // implemented behind the --experimental_explicit_aspects flag.
    // See https://github.com/bazelbuild/bazel/issues/16310 for details.
    for (SkyKey key : dependencies) {
      if (knownCtDeps.contains(key)) {
        continue;
      }
      if (key.functionName().equals(SkyFunctions.CONFIGURED_TARGET)
          || (explicitAspects && key.functionName().equals(SkyFunctions.ASPECT))) {
        T dependency = getValueFromKey(key);
        Preconditions.checkState(
            dependency != null,
            "query-requested node '%s' was unavailable in the query environment graph. If you come"
                + " across this error, please add to"
                + " https://github.com/bazelbuild/bazel/issues/15079 or contact the bazel"
                + " configurability team.",
            key);

        boolean implicitDep;
        if (!(key.argument() instanceof ConfiguredTargetKey)) {
          // All non-configured target deps are implicit.
          implicitDep = true;
        } else if (parent != null && isAliasConfiguredTarget(parent)) {
          // Aliases have only one configured target dep: the ":actual" parameter.
          implicitDep = false;
        } else if (parent != null && getOwningRuleforOutputConfiguredTarget(parent) != null) {
          // An output file's generating target is an explicit dep.
          implicitDep =
              getRuleConfiguredTarget(dependency) != null
                  && !getRuleConfiguredTarget(dependency)
                      .equals(getOwningRuleforOutputConfiguredTarget(parent));
        } else if (implicitDeps == null) {
          // No set of implicit deps available. Assume they're all implicit. This implies the parent
          // isn't a rule configured target.
          Verify.verify(!(parent instanceof RuleConfiguredTarget));
          implicitDep = true;
        } else {
          // Check both the original guess key and the second correct key. In the case of the
          // target platform, Util.findImplicitDeps also uses the original guess key.
          implicitDep =
              implicitDeps.contains(key)
                  || implicitDeps.contains(getConfiguredTargetKey(dependency));
        }

        values.add(new ClassifiedDependency<>(dependency, implicitDep));
        knownCtDeps.add(key);
      } else if (settings.contains(Setting.INCLUDE_ASPECTS)
          && key.functionName().equals(SkyFunctions.ASPECT)) {
        Preconditions.checkState(!settings.contains(Setting.EXPLICIT_ASPECTS));
        if (resolvedAspectClasses.contains(((AspectKey) key).getAspectClass())) {
          continue;
        }
        // When an aspect is attached to an alias configured target, it bypasses standard dependency
        // resolution and just Skyframe-loads the same aspect for the alias' referent. That means
        // the original aspect's attribute deps aren't Skyframe-resolved through AspectFunction's
        // usual call to ConfiguredTargetFunction.computeDependencies, so graph.getDirectDeps()
        // won't include them. So we defer "resolving" the aspect class to the non-alias version,
        // which properly reflects all dependencies. See AspectFunction for details.
        if (!isAliasConfiguredTarget(((AspectKey) key).getBaseConfiguredTargetKey())) {
          // Make sure we don't examine aspects of this type again. This saves us from unnecessarily
          // traversing a target's transitive deps because it propagates an aspect down those deps.
          // The deps added by the aspect are a function of the aspect's class, not the target it's
          // attached to. And they can't be configured because aspects have no UI for overriding
          // attribute defaults. So it's sufficient to examine only a single instance of a given
          // aspect class. This has real memory and performance consequences: see b/163052263.
          // Note the aspect could attach *another* aspect type to its deps. That will still get
          // examined through the recursive call.
          resolvedAspectClasses.add(((AspectKey) key).getAspectClass());
        }
        values.addAll(
            targetifyValues(null, graph.getDirectDeps(key), knownCtDeps, resolvedAspectClasses));
      } else if (key.functionName().equals(SkyFunctions.TOOLCHAIN_RESOLUTION)) {
        values.addAll(
            targetifyValues(null, graph.getDirectDeps(key), knownCtDeps, resolvedAspectClasses));
      }
    }
    return values.build();
  }

  private Map<SkyKey, ImmutableList<ClassifiedDependency<T>>> targetifyValues(
      Map<SkyKey, T> fromTargetsByKey, Map<SkyKey, ? extends Iterable<SkyKey>> input)
      throws InterruptedException {
    Map<SkyKey, ImmutableList<ClassifiedDependency<T>>> result = new HashMap<>();
    for (Map.Entry<SkyKey, ? extends Iterable<SkyKey>> entry : input.entrySet()) {
      SkyKey fromKey = entry.getKey();
      result.put(
          fromKey,
          targetifyValues(
              fromTargetsByKey.get(fromKey),
              entry.getValue(),
              /* knownCtDeps= */ new HashSet<>(),
              /* resolvedAspectClasses= */ new HashSet<>()));
    }
    return result;
  }

  /** A class to store a dependency with some information. */
  private static class ClassifiedDependency<T> {
    // True if this dependency is attached implicitly.
    boolean implicit;
    T dependency;

    private ClassifiedDependency(T dependency, boolean implicit) {
      this.implicit = implicit;
      this.dependency = dependency;
    }

    @Override
    public String toString() {
      return toStringHelper(this)
          .add("implicit", implicit)
          .add("dependency", dependency)
          .toString();
    }
  }

  private static <T> ImmutableList<T> getDependencies(
      Collection<ClassifiedDependency<T>> classifiedDependencies) {
    return classifiedDependencies.stream()
        .map(dep -> dep.dependency)
        .collect(ImmutableList.toImmutableList());
  }

  @Nullable
  protected abstract BuildConfigurationValue getConfiguration(T target);

  protected abstract ActionLookupKey getConfiguredTargetKey(T target);

  @Override
  public ThreadSafeMutableSet<T> getTransitiveClosure(
      ThreadSafeMutableSet<T> targets, QueryExpressionContext<T> context)
      throws InterruptedException {
    return SkyQueryUtils.getTransitiveClosure(
        targets, targets1 -> getFwdDeps(targets1, context), createThreadSafeMutableSet());
  }

  @Override
  public void buildTransitiveClosure(
      QueryExpression caller, ThreadSafeMutableSet<T> targetNodes, OptionalInt maxDepth) {
    // TODO(bazel-team): implement this. Just needed for error-checking.
  }

  @Override
  public ImmutableList<T> getNodesOnPath(T from, T to, QueryExpressionContext<T> context)
      throws InterruptedException {
    return SkyQueryUtils.getNodesOnPath(
        from,
        to,
        targets -> getFwdDeps(targets, context),
        getConfiguredTargetKeyExtractor()::extractKey);
  }

  @Override
  public Uniquifier<T> createUniquifier() {
    return new UniquifierImpl<>(getConfiguredTargetKeyExtractor());
  }

  @Override
  public MinDepthUniquifier<T> createMinDepthUniquifier() {
    return new MinDepthUniquifierImpl<>(
        getConfiguredTargetKeyExtractor(), SkyQueryEnvironment.DEFAULT_THREAD_COUNT);
  }

  /** Target patterns are resolved on the fly so no pre-work to be done here. */
  @Override
  protected void preloadOrThrow(QueryExpression caller, Collection<String> patterns) {}

  @Override
  public TransitiveLoadFilesHelper<T> getTransitiveLoadFilesHelper() throws QueryException {
    throw new QueryException(
        "buildfiles() doesn't make sense for the configured target graph",
        ConfigurableQuery.Code.BUILDFILES_FUNCTION_NOT_SUPPORTED);
  }

  @Override
  public Collection<T> getSiblingTargetsInPackage(T target) throws QueryException {
    throw new QueryException(
        "siblings() not supported for post analysis queries",
        ConfigurableQuery.Code.SIBLINGS_FUNCTION_NOT_SUPPORTED);
  }

  @Override
  public void close() {}

  /** A wrapper class for the set of top-level configurations in a query. */
  public static class TopLevelConfigurations {

    /** A map of non-null configured top-level targets sorted by configuration checksum. */
    private final ImmutableMap<Label, BuildConfigurationValue> nonNulls;

    /**
     * {@code nonNulls} may often have many duplicate values in its value set so we store a sorted
     * set of all the non-null configurations here.
     */
    private final ImmutableSortedSet<BuildConfigurationValue> nonNullConfigs;

    /** A list of null configured top-level targets. */
    private final ImmutableList<Label> nulls;

    public TopLevelConfigurations(
        Collection<TargetAndConfiguration> topLevelTargetsAndConfigurations) {
      ImmutableMap.Builder<Label, BuildConfigurationValue> nonNullsBuilder =
          ImmutableMap.builderWithExpectedSize(topLevelTargetsAndConfigurations.size());
      ImmutableList.Builder<Label> nullsBuilder = new ImmutableList.Builder<>();
      for (TargetAndConfiguration targetAndConfiguration : topLevelTargetsAndConfigurations) {
        if (targetAndConfiguration.getConfiguration() == null) {
          nullsBuilder.add(targetAndConfiguration.getLabel());
        } else {
          nonNullsBuilder.put(
              targetAndConfiguration.getLabel(), targetAndConfiguration.getConfiguration());
        }
      }
      nonNulls = nonNullsBuilder.buildOrThrow();
      nonNullConfigs =
          ImmutableSortedSet.copyOf(
              Comparator.comparing(BuildConfigurationValue::checksum), nonNulls.values());
      nulls = nullsBuilder.build();
    }

    public boolean isTopLevelTarget(Label label) {
      return nonNulls.containsKey(label) || nulls.contains(label);
    }

    // This method returns the configuration of a top-level target if it's not null-configured and
    // otherwise returns null (signifying it is null configured).
    @Nullable
    public BuildConfigurationValue getConfigurationForTopLevelTarget(Label label) {
      Preconditions.checkArgument(
          isTopLevelTarget(label),
          "Attempting to get top-level configuration for non-top-level target %s.",
          label);
      return nonNulls.get(label);
    }

    public Iterable<BuildConfigurationValue> getConfigurations() {
      if (nulls.isEmpty()) {
        return nonNullConfigs;
      } else {
        return Iterables.concat(nonNullConfigs, Collections.singletonList(null));
      }
    }
  }
}
