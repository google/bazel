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

package com.google.devtools.build.lib.bazel.coverage;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.BaseSpawn;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ImportantOutputHandler;
import com.google.devtools.build.lib.actions.ImportantOutputHandler.ImportantOutputException;
import com.google.devtools.build.lib.actions.NotifyOnActionCacheHit;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.IncompatiblePlatformProvider;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.actions.LazyWritePathsFileAction;
import com.google.devtools.build.lib.analysis.test.CoverageReportActionFactory.CoverageReportActionsWrapper;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.analysis.test.TestProvider.TestParams;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * A class to create the coverage report generator action.
 *
 * <p>The coverage report action is created after every test shard action is created, at the very
 * end of the analysis phase. There is only one coverage report action per coverage command
 * invocation. It can also be viewed as a single sink node of the action graph.
 *
 * <p>Its inputs are the individual coverage.dat files from the test outputs (each shard produces
 * one) and the baseline coverage artifacts. Note that each ConfiguredTarget among the transitive
 * dependencies of the top level test targets may provide baseline coverage artifacts.
 *
 * <p>The coverage report generation can have two phases, though they both run in the same action.
 * The source code of the coverage report tool {@code lcov_merger} is in the {@code
 * testing/coverage/lcov_merger} directory. The deployed binaries used by Blaze are under {@code
 * tools/coverage}.
 *
 * <p>The first phase is merging the individual coverage files into a single report file. The
 * location of this file is reported by Blaze. This phase always happens if the {@code
 * --combined_report=lcov} or {@code --combined_report=html}.
 *
 * <p>The second phase is generating an html report. It only happens if {@code
 * --combined_report=html}. The action generates an html output file potentially for every tested
 * source file into the report. Since this set of files is unknown in the analysis phase (the tool
 * figures it out from the contents of the merged coverage report file) the action always runs
 * locally when {@code --combined_report=html}.
 */
public final class CoverageReportActionBuilder {

  private static final ResourceSet LOCAL_RESOURCES =
      ResourceSet.createWithRamCpu(/* memoryMb= */ 750, /* cpu= */ 1);

  private static final ActionOwner ACTION_OWNER = ActionOwner.SYSTEM_ACTION_OWNER;

  // SpawnActions can't be used because they need the AnalysisEnvironment and this action is
  // created specially at the very end of the analysis phase when we don't have it anymore.
  @Immutable
  private static final class CoverageReportAction extends AbstractAction
      implements NotifyOnActionCacheHit {
    private final ImmutableList<String> command;
    private final boolean remotable;
    private final String locationMessage;

    CoverageReportAction(
        ActionOwner owner,
        NestedSet<Artifact> inputs,
        ImmutableSet<Artifact> outputs,
        ImmutableList<String> command,
        String locationMessage,
        boolean remotable) {
      super(owner, inputs, outputs);
      this.command = command;
      this.remotable = remotable;
      this.locationMessage = locationMessage;
    }

    @Override
    public ActionResult execute(ActionExecutionContext ctx)
        throws ActionExecutionException, InterruptedException {
      ImmutableMap<String, String> executionInfo =
          remotable ? ImmutableMap.of() : ImmutableMap.of(ExecutionRequirements.NO_REMOTE, "");
      Spawn spawn = new BaseSpawn(command, ImmutableMap.of(), executionInfo, this, LOCAL_RESOURCES);
      try {
        ImmutableList<SpawnResult> spawnResults =
            ctx.getContext(SpawnStrategyResolver.class).exec(spawn, ctx);
        informImportantOutputHandler(ctx);
        ctx.getEventHandler().handle(Event.info(locationMessage));
        return ActionResult.create(spawnResults);
      } catch (ExecException e) {
        throw ActionExecutionException.fromExecException(e, this);
      }
    }

    private void informImportantOutputHandler(ActionExecutionContext ctx)
        throws EnvironmentalExecException, InterruptedException {
      var importantOutputHandler = ctx.getContext(ImportantOutputHandler.class);
      if (importantOutputHandler == null) {
        return;
      }

      Path coverageReportOutput = ctx.getPathResolver().toPath(getPrimaryOutput());
      try (var ignored =
          GoogleAutoProfilerUtils.logged(
              "Informing important output handler of coverage report",
              ImportantOutputHandler.LOG_THRESHOLD)) {
        importantOutputHandler.processTestOutputs(ImmutableList.of(coverageReportOutput));
      } catch (ImportantOutputException e) {
        throw new EnvironmentalExecException(e, e.getFailureDetail());
      }
    }

    @Override
    public String getMnemonic() {
      return "CoverageReport";
    }

    @Override
    protected String getRawProgressMessage() {
      return "Coverage report generation";
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      fp.addStrings(command);
    }

    @Override
    public boolean actionCacheHit(ActionCachedContext context) {
      context.getEventHandler().handle(Event.info(locationMessage));
      return true;
    }
  }

  public CoverageReportActionBuilder() {
  }

  /** Returns the coverage report action. May return null in case of an error. */
  @Nullable
  public CoverageReportActionsWrapper createCoverageActionsWrapper(
      EventHandler reporter,
      BlazeDirectories directories,
      Collection<ConfiguredTarget> targetsToTest,
      NestedSet<Artifact> baselineCoverageArtifacts,
      ArtifactFactory factory,
      ActionKeyContext actionKeyContext,
      ArtifactOwner artifactOwner,
      String workspaceName,
      ArgsFunc argsFunction,
      LocationFunc locationFunc,
      boolean htmlReport)
      throws InterruptedException {
    if (targetsToTest == null || targetsToTest.isEmpty()) {
      return null;
    }
    NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
    FilesToRunProvider reportGenerator = null;
    for (ConfiguredTarget target : targetsToTest) {
      // Skip incompatible tests.
      if (target.get(IncompatiblePlatformProvider.PROVIDER) != null) {
        continue;
      }
      TestParams testParams = target.getProvider(TestProvider.class).getTestParams();
      builder.addAll(testParams.getCoverageArtifacts());
      if (reportGenerator == null) {
        reportGenerator = testParams.getCoverageReportGenerator();
      }
    }
    // If all tests are incompatible, there's nothing to do.
    if (reportGenerator == null) {
      return null;
    }
    NestedSet<Artifact> coverageArtifacts =
        builder.addTransitive(baselineCoverageArtifacts).build();
    if (!coverageArtifacts.isEmpty()) {
      PathFragment coverageDir = TestRunnerAction.COVERAGE_TMP_ROOT;
      Artifact lcovArtifact = factory.getDerivedArtifact(
          coverageDir.getRelative("lcov_files.tmp"),
          directories.getBuildDataDirectory(workspaceName),
          artifactOwner);
      Action lcovFileAction = generateLcovFileWriteAction(lcovArtifact, coverageArtifacts);
      Action coverageReportAction = generateCoverageReportAction(
          CoverageArgs.create(directories, coverageArtifacts, lcovArtifact, factory, artifactOwner,
              reportGenerator, workspaceName, htmlReport),
          argsFunction, locationFunc);
      return new CoverageReportActionsWrapper(
          lcovFileAction, coverageReportAction, actionKeyContext);
    } else {
      reporter.handle(
          Event.error("Cannot generate coverage report - no coverage information was collected"));
      return null;
    }
  }

  private static LazyWritePathsFileAction generateLcovFileWriteAction(
      Artifact lcovArtifact, NestedSet<Artifact> coverageArtifacts) {
    return new LazyWritePathsFileAction(
        ACTION_OWNER,
        lcovArtifact,
        coverageArtifacts,
        /* filesToIgnore= */ ImmutableSet.of(),
        /* includeDerivedArtifacts= */ true);
  }

  /** Computes the arguments passed to the coverage report generator. */
  @FunctionalInterface
  public interface ArgsFunc {
    ImmutableList<String> apply(CoverageArgs args);
  }

  /** Computes the location message for the {@link CoverageReportAction}. */
  @FunctionalInterface
  public interface LocationFunc {
    String apply(CoverageArgs args);
  }

  private static CoverageReportAction generateCoverageReportAction(
      CoverageArgs args, ArgsFunc argsFunc, LocationFunc locationFunc) {
    ArtifactRoot root = args.directories().getBuildDataDirectory(args.workspaceName());
    PathFragment coverageDir = TestRunnerAction.COVERAGE_TMP_ROOT;
    Artifact lcovOutput =
        args.factory()
            .getDerivedArtifact(
                coverageDir.getRelative("_coverage_report.dat"), root, args.artifactOwner());
    Artifact reportGeneratorExec = args.reportGenerator().getExecutable();
    RunfilesSupport runfilesSupport = args.reportGenerator().getRunfilesSupport();
    Artifact runfilesTree =
        runfilesSupport != null ? runfilesSupport.getRunfilesTreeArtifact() : null;
    args = CoverageArgs.createCopyWithCoverageDirAndLcovOutput(args, coverageDir, lcovOutput);
    ImmutableList<String> actionArgs = argsFunc.apply(args);

    NestedSetBuilder<Artifact> inputsBuilder =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(args.coverageArtifacts())
            .add(reportGeneratorExec)
            .add(args.lcovArtifact());
    if (runfilesTree != null) {
      inputsBuilder.add(runfilesTree);
    }
    return new CoverageReportAction(
        ACTION_OWNER,
        inputsBuilder.build(),
        ImmutableSet.of(lcovOutput),
        actionArgs,
        locationFunc.apply(args),
        !args.htmlReport());
  }
}
