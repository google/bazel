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

package com.google.devtools.build.lib.rules.genquery;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import com.google.common.hash.HashFunction;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.DeterministicWriter;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.cmdline.SignedTargetPattern;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.pkgcache.FilteringPolicies;
import com.google.devtools.build.lib.pkgcache.TargetPatternPreloader;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.query2.QueryEnvironmentFactory;
import com.google.devtools.build.lib.query2.common.AbstractBlazeQueryEnvironment;
import com.google.devtools.build.lib.query2.common.UniverseScope;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting;
import com.google.devtools.build.lib.query2.engine.QueryEvalResult;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.query2.engine.QueryUtil;
import com.google.devtools.build.lib.query2.engine.QueryUtil.AggregateAllOutputFormatterCallback;
import com.google.devtools.build.lib.query2.engine.SkyframeRestartQueryException;
import com.google.devtools.build.lib.query2.query.output.OutputFormatter;
import com.google.devtools.build.lib.query2.query.output.OutputFormatters;
import com.google.devtools.build.lib.query2.query.output.QueryOptions;
import com.google.devtools.build.lib.query2.query.output.QueryOptions.OrderOutput;
import com.google.devtools.build.lib.query2.query.output.QueryOutputUtils;
import com.google.devtools.build.lib.query2.query.output.StreamedFormatter;
import com.google.devtools.build.lib.rules.genquery.GenQueryOutputStream.GenQueryResult;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.TargetPatternValue;
import com.google.devtools.build.lib.skyframe.TargetPatternValue.TargetPatternKey;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.TriState;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.ClosedByInterruptException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** An implementation of the 'genquery' rule. */
public class GenQuery implements RuleConfiguredTargetFactory {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final QueryEnvironmentFactory QUERY_ENVIRONMENT_FACTORY =
      new QueryEnvironmentFactory();

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    Artifact outputArtifact = ruleContext.createOutputArtifact();

    // The query string
    final String query = ruleContext.attributes().get("expression", Type.STRING);

    OptionsParser optionsParser =
        OptionsParser.builder()
            .optionsClasses(QueryOptions.class, KeepGoingOption.class)
            .allowResidue(false)
            .withConversionContext(
                Label.RepoContext.of(
                    ruleContext.getRepository(),
                    ruleContext.getRule().getPackage().getRepositoryMapping()))
            .build();
    try {
      optionsParser.parse(ruleContext.attributes().get("opts", Types.STRING_LIST));
    } catch (OptionsParsingException e) {
      ruleContext.attributeError("opts", "error while parsing query options: " + e.getMessage());
      return null;
    }

    // Parsed query options
    QueryOptions queryOptions = optionsParser.getOptions(QueryOptions.class);
    // If you change the list of options here, also change the documentation of genquery.opts in
    // GenQueryRule.java .
    if (optionsParser.getOptions(KeepGoingOption.class).keepGoing) {
      ruleContext.attributeError("opts", "option --keep_going is not allowed");
      return null;
    }
    if (!queryOptions.universeScope.isEmpty()) {
      ruleContext.attributeError("opts", "option --universe_scope is not allowed");
      return null;
    }
    if (optionsParser.containsExplicitOption("order_results")) {
      ruleContext.attributeError("opts", "option --order_results is not allowed");
      return null;
    }
    if (optionsParser.containsExplicitOption("noorder_results")) {
      ruleContext.attributeError("opts", "option --noorder_results is not allowed");
      return null;
    }
    if (optionsParser.containsExplicitOption("order_output")) {
      ruleContext.attributeError("opts", "option --order_output is not allowed");
      return null;
    }
    if (optionsParser.containsExplicitOption("experimental_graphless_query")) {
      ruleContext.attributeError("opts", "option --experimental_graphless_query is not allowed");
      return null;
    }
    // Genquery should always use AUTO, while build isn't affected by query options, .
    queryOptions.useGraphlessQuery = TriState.AUTO;

    // force relative_locations to true so it has a deterministic output across machines.
    queryOptions.relativeLocations = true;

    if (!optionsParser.containsExplicitOption("nodep_deps")) {
      // Have GenQuery *not* include "nodep" deps by default. This is an unfortunate divergence from
      // `query` which is necessary to maintain legacy behavior.
      // TODO(b/123122592): Complete the migration and remove this divergence.
      queryOptions.includeNoDepDeps = false;
    }

    GenQueryResult result;
    try (SilentCloseable c =
        Profiler.instance().profile("GenQuery.executeQuery " + ruleContext.getLabel())) {
      List<Label> scope = ruleContext.attributes().get("scope", BuildType.GENQUERY_SCOPE_TYPE_LIST);
      result =
          executeQuery(
              ruleContext,
              queryOptions,
              scope != null ? ImmutableList.copyOf(scope) : ImmutableList.of(),
              query,
              outputArtifact.getPath().getFileSystem().getDigestFunction().getHashFunction());
    }
    if (result == null || ruleContext.hasErrors()) {
      return null;
    }

    if (result.size() > 50_000_000) {
      logger.atInfo().atMostEvery(1, TimeUnit.SECONDS).log(
          "Genquery %s had large output %s", ruleContext.getLabel(), result.size());
    }
    ruleContext.registerAction(
        new QueryResultAction(ruleContext.getActionOwner(), outputArtifact, result));

    NestedSet<Artifact> filesToBuild = NestedSetBuilder.create(Order.STABLE_ORDER, outputArtifact);
    return new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(filesToBuild)
        .addProvider(
            RunfilesProvider.class,
            RunfilesProvider.simple(
                new Runfiles.Builder(
                        ruleContext.getWorkspaceName(),
                        ruleContext.getConfiguration().legacyExternalRunfiles())
                    .addTransitiveArtifacts(filesToBuild)
                    .build()))
        .addOutputGroup(
            OutputGroupInfo.VALIDATION_TRANSITIVE, NestedSetBuilder.emptySet(Order.STABLE_ORDER))
        .build();
  }

  /**
   * DO NOT USE! We should get rid of this method: errors reported directly to this object don't set
   * the error flag in {@link ConfiguredTarget}.
   */
  private static ExtendedEventHandler getEventHandler(RuleContext ruleContext) {
    return ruleContext.getAnalysisEnvironment().getEventHandler();
  }

  @Nullable
  private static GenQueryResult executeQuery(
      RuleContext ruleContext,
      QueryOptions queryOptions,
      ImmutableList<Label> scope,
      String query,
      HashFunction hashFunction)
      throws InterruptedException {
    SkyFunction.Environment env = ruleContext.getAnalysisEnvironment().getSkyframeEnv();

    GenQueryPackageProvider packageProvider;
    try {
      GenQueryPackageProviderFactory packageProviderFactory =
          ruleContext.getConfiguration().getFragment(GenQueryConfiguration.class).skipTtvs()
              ? new GenQueryDirectPackageProviderFactory()
              : new GenQueryTtvPackageProviderFactory();
      packageProvider = packageProviderFactory.constructPackageMap(env, scope);
      if (packageProvider == null) {
        return null;
      }
    } catch (GenQueryPackageProviderFactory.BrokenQueryScopeException e) {
      ruleContext.ruleError(e.getMessage());
      return null;
    }

    return doQuery(
        queryOptions,
        packageProvider,
        new SkyframeEnvTargetPatternEvaluator(env),
        query,
        ruleContext,
        hashFunction);
  }

  @Nullable
  private static GenQueryResult doQuery(
      QueryOptions queryOptions,
      GenQueryPackageProvider packageProvider,
      TargetPatternPreloader preloader,
      String query,
      RuleContext ruleContext,
      HashFunction hashFunction)
      throws InterruptedException {

    QueryEvalResult queryResult;
    OutputFormatter formatter;
    AggregateAllOutputFormatterCallback<Target, ?> targets;
    boolean graphlessQuery;
    AbstractBlazeQueryEnvironment<Target> queryEnvironment;
    try {
      Set<Setting> settings = queryOptions.toSettings();

      formatter =
          OutputFormatters.getFormatter(
              OutputFormatters.getDefaultFormatters(), queryOptions.outputFormat);
      if (formatter == null) {
        ruleContext.ruleError(
            String.format(
                "Invalid output format '%s'. Valid values are: %s",
                queryOptions.outputFormat,
                OutputFormatters.formatterNames(OutputFormatters.getDefaultFormatters())));
        return null;
      }
      graphlessQuery = formatter instanceof StreamedFormatter;
      if (graphlessQuery) {
        queryOptions.orderOutput = OrderOutput.NO;
      } else {
        // Force results to be deterministic.
        queryOptions.orderOutput = OrderOutput.FULL;
      }

      queryEnvironment =
          QUERY_ENVIRONMENT_FACTORY.create(
              /* queryTransitivePackagePreloader= */ null,
              /* graphFactory= */ null,
              packageProvider,
              packageProvider,
              preloader,
              new TargetPattern.Parser(
                  PathFragment.EMPTY_FRAGMENT,
                  ruleContext.getRepository(),
                  ruleContext.getRule().getPackage().getRepositoryMapping()),
              PathFragment.EMPTY_FRAGMENT,
              /* keepGoing= */ false,
              ruleContext.attributes().get("strict", Type.BOOLEAN),
              /* orderedResults= */ !graphlessQuery,
              UniverseScope.EMPTY,
              // Use a single thread to prevent race conditions causing nondeterministic output
              // (b/127644784). All the packages are already loaded at this point, so there is
              // no need to start up multiple threads anyway.
              /* loadingPhaseThreads= */ 1,
              packageProvider.getValidTargetPredicate(),
              getEventHandler(ruleContext),
              settings,
              /* extraFunctions= */ ImmutableList.of(),
              /* packagePath= */ null,
              /* useGraphlessQuery= */ graphlessQuery,
              queryOptions.getLabelPrinterLegacy(
                  ruleContext.getAnalysisEnvironment().getStarlarkSemantics()));
      QueryExpression expr = QueryExpression.parse(query, queryEnvironment);
      formatter.verifyCompatible(queryEnvironment, expr);
      targets =
          graphlessQuery && !expr.isTopLevelSomePathFunction()
              ? QueryUtil.newLexicographicallySortedTargetAggregator()
              : QueryUtil.newOrderedAggregateAllOutputFormatterCallback(queryEnvironment);
      queryResult = queryEnvironment.evaluateQuery(expr, targets);
    } catch (SkyframeRestartQueryException e) {
      // Do not emit errors for skyframe restarts. They make output of the ConfiguredTargetFunction
      // inconsistent from run to run, and make detecting legitimate errors more difficult.
      return null;
    } catch (QuerySyntaxException e) {
      ruleContext.ruleError("query syntax error: " + e.getMessage());
      return null;
    } catch (QueryException e) {
      ruleContext.ruleError("query failed: " + e.getMessage());
      return null;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    try {
      boolean compressedOutputRequested =
          ruleContext.attributes().get("compressed_output", Type.BOOLEAN);
      GenQueryOutputStream outputStream = new GenQueryOutputStream(compressedOutputRequested);
      Set<Target> result = targets.getResult();
      QueryOutputUtils.output(
          queryOptions,
          queryResult,
          result,
          formatter,
          outputStream,
          queryOptions.aspectDeps.createResolver(packageProvider, getEventHandler(ruleContext)),
          getEventHandler(ruleContext),
          hashFunction,
          queryEnvironment.getLabelPrinter());
      outputStream.close();
      return outputStream.getResult();
    } catch (ClosedByInterruptException e) {
      throw new InterruptedException(e.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Immutable // assuming no other reference to result
  private static final class QueryResultAction extends AbstractFileWriteAction {
    private final GenQueryResult result;

    private QueryResultAction(ActionOwner owner, Artifact output, GenQueryResult result) {
      super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), output);
      this.result = result;
    }

    @Override
    public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
      return new GenQueryResultWriter(result);
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      result.fingerprint(fp);
    }
  }

  /**
   * Provide target pattern evaluation to the query operations using Skyframe dep lookup. For thread
   * safety, we must synchronize access to the SkyFunction.Environment.
   */
  private static final class SkyframeEnvTargetPatternEvaluator implements TargetPatternPreloader {
    private final SkyFunction.Environment env;

    public SkyframeEnvTargetPatternEvaluator(SkyFunction.Environment env) {
      this.env = env;
    }

    private static Target getExistingTarget(Label label, Map<PackageIdentifier, Package> packages) {
      try {
        return packages.get(label.getPackageIdentifier()).getTarget(label.getName());
      } catch (NoSuchTargetException e) {
        // Unexpected since the label was part of the TargetPatternValue.
        throw new IllegalStateException(e);
      }
    }

    @Override
    public Map<String, Collection<Target>> preloadTargetPatterns(
        ExtendedEventHandler eventHandler,
        TargetPattern.Parser targetParser,
        Collection<String> patterns,
        boolean keepGoing)
        throws TargetParsingException, InterruptedException {
      Preconditions.checkArgument(!keepGoing);
      Preconditions.checkArgument(targetParser.getRelativeDirectory().isEmpty());
      boolean ok = true;
      Map<String, Collection<Target>> preloadedPatterns =
          Maps.newHashMapWithExpectedSize(patterns.size());
      ImmutableMap.Builder<TargetPatternKey, String> targetBuilder =
          ImmutableMap.builderWithExpectedSize(patterns.size());
      for (String pattern : patterns) {
        checkValidPatternType(pattern, targetParser);
        targetBuilder.put(
            TargetPatternValue.key(
                SignedTargetPattern.parse(pattern, targetParser), FilteringPolicies.NO_FILTER),
            pattern);
      }
      ImmutableMap<TargetPatternKey, String> patternKeys = targetBuilder.buildOrThrow();
      Set<SkyKey> packageKeys = new HashSet<>();
      Map<String, ResolvedTargets<Label>> resolvedLabelsMap =
          Maps.newHashMapWithExpectedSize(patterns.size());
      synchronized (this) {
        SkyframeLookupResult patternKeysResult = env.getValuesAndExceptions(patternKeys.keySet());
        for (Map.Entry<TargetPatternKey, String> entry : patternKeys.entrySet()) {
          TargetPatternValue patternValue =
              (TargetPatternValue)
                  patternKeysResult.getOrThrow(entry.getKey(), TargetParsingException.class);
          if (patternValue == null) {
            ok = false;
          } else {
            ResolvedTargets<Label> resolvedLabels = patternValue.getTargets();
            resolvedLabelsMap.put(entry.getValue(), resolvedLabels);
            for (Label label :
                Iterables.concat(
                    resolvedLabels.getTargets(), resolvedLabels.getFilteredTargets())) {
              packageKeys.add(label.getPackageIdentifier());
            }
          }
        }
      }
      if (!ok) {
        throw new SkyframeRestartQueryException();
      }
      Map<PackageIdentifier, Package> packages =
          Maps.newHashMapWithExpectedSize(packageKeys.size());
      synchronized (this) {
        SkyframeLookupResult packageKeysResult = env.getValuesAndExceptions(packageKeys);
        // packageKeys is not mutated, the iteration order is the same.
        for (SkyKey depKey : packageKeys) {
          PackageIdentifier pkgName = (PackageIdentifier) depKey.argument();
          Package pkg;
          try {
            PackageValue packageValue =
                (PackageValue) packageKeysResult.getOrThrow(depKey, NoSuchPackageException.class);
            if (packageValue == null) {
              ok = false;
              continue;
            }
            pkg = packageValue.getPackage();
          } catch (NoSuchPackageException nspe) {
            continue;
          }
          Preconditions.checkNotNull(pkg, pkgName);
          packages.put(pkgName, pkg);
        }
      }
      if (!ok) {
        throw new SkyframeRestartQueryException();
      }
      for (Map.Entry<String, ResolvedTargets<Label>> entry : resolvedLabelsMap.entrySet()) {
        String pattern = entry.getKey();
        ResolvedTargets<Label> resolvedLabels = resolvedLabelsMap.get(pattern);
        Set<Target> builder = CompactHashSet.create();
        for (Label label : resolvedLabels.getTargets()) {
          builder.add(getExistingTarget(label, packages));
        }
        preloadedPatterns.put(pattern, builder);
      }
      return preloadedPatterns;
    }

    private static void checkValidPatternType(String pattern, TargetPattern.Parser parser)
        throws TargetParsingException {
      TargetPattern.Type type = parser.parse(pattern).getType();
      if (type == TargetPattern.Type.PATH_AS_TARGET) {
        throw new TargetParsingException(
            String.format("couldn't determine target from filename '%s'", pattern),
            TargetPatterns.Code.CANNOT_DETERMINE_TARGET_FROM_FILENAME);
      } else if (type == TargetPattern.Type.TARGETS_BELOW_DIRECTORY) {
        throw new TargetParsingException(
            String.format("recursive target patterns are not permitted: '%s'", pattern),
            TargetPatterns.Code.RECURSIVE_TARGET_PATTERNS_NOT_ALLOWED);
      }
    }
  }

  private static class GenQueryResultWriter implements DeterministicWriter {
    private final GenQueryResult genQueryResult;

    GenQueryResultWriter(GenQueryResult genQueryResult) {
      this.genQueryResult = genQueryResult;
    }

    @Override
    public void writeOutputFile(OutputStream out) throws IOException {
      genQueryResult.writeTo(out);
    }

    @Override
    public ByteString getBytes() throws IOException {
      return genQueryResult.getBytes();
    }
  }
}
