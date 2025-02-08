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

package com.google.devtools.build.lib.runtime.commands;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.runtime.Command.BuildPhase.EXECUTES;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RunEnvironmentInfo;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.ShToolchain;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.RunUnder;
import com.google.devtools.build.lib.analysis.config.RunUnder.LabelRunUnder;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.analysis.test.TestStrategy;
import com.google.devtools.build.lib.analysis.test.TestTargetExecutionSettings;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.buildtool.PathPrettyPrinter;
import com.google.devtools.build.lib.buildtool.buildevent.ExecRequestEvent;
import com.google.devtools.build.lib.buildtool.buildevent.RunBuildCompleteEvent;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.RunfilesTreeUpdater;
import com.google.devtools.build.lib.exec.TestPolicy;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.pkgcache.LoadingFailedException;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeServerStartupOptions;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.CommandProtos;
import com.google.devtools.build.lib.server.CommandProtos.EnvironmentVariable;
import com.google.devtools.build.lib.server.CommandProtos.ExecRequest;
import com.google.devtools.build.lib.server.CommandProtos.PathToReplace;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Interrupted;
import com.google.devtools.build.lib.server.FailureDetails.RunCommand.Code;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

/** Builds and run a target with the given command line arguments. */
@Command(
    name = "run",
    buildPhase = EXECUTES,
    options = {RunCommand.RunOptions.class},
    inheritsOptionsFrom = {BuildCommand.class},
    shortDescription = "Runs the specified target.",
    help = "resource:run.txt",
    allowResidue = true,
    hasSensitiveResidue = true,
    completion = "label-bin")
public class RunCommand implements BlazeCommand {
  /** Options for the "run" command. */
  public static class RunOptions extends OptionsBase {
    @Option(
        name = "script_path",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.EXECUTION},
        converter = OptionsUtils.PathFragmentConverter.class,
        help =
            "If set, write a shell script to the given file which invokes the target. If this"
                + " option is set, the target is not run from %{product}. Use '%{product} run"
                + " --script_path=foo //foo && ./foo' to invoke target '//foo' This differs from"
                + " '%{product} run //foo' in that the %{product} lock is released and the"
                + " executable is connected to the terminal's stdin.")
    public PathFragment scriptPath;

    @Option(
        name = "emit_script_path_in_exec_request",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
        help =
            "If true, emits the ExecRequest with --script_path file value and script contents"
                + " instead of writing the script.")
    public boolean emitScriptPathInExecRequest;

    @Option(
        name = "run",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
        help =
            "If false, skip running the command line constructed for the built target. Note that"
                + " this flag is ignored for all --script_path builds.")
    public boolean runBuiltTarget;

    @Option(
        name = "portable_paths",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
        help =
            "If true, includes paths to replace in ExecRequest to make the resulting paths"
                + " portable.")
    public boolean portablePaths;

    @Option(
        name = "run_env",
        converter = Converters.OptionalAssignmentConverter.class,
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
        help =
            "Specifies the set of environment variables available to actions with target"
                + " configuration. Variables can be either specified by name, in which case the"
                + " value will be taken from the invocation environment, or by the name=value pair"
                + " which sets the value independent of the invocation environment. This option can"
                + " be used multiple times; for options given for the same variable, the latest"
                + " wins, options for different variables accumulate.")
    public List<Map.Entry<String, String>> runEnvironment;
  }

  private static final String NO_TARGET_MESSAGE = "No targets found to run";

  private static final String MULTIPLE_TESTS_MESSAGE =
      "'run' only works with tests with one shard ('--test_sharding_strategy=disabled' is okay) "
          + "and without --runs_per_test";

  private static final ImmutableSortedSet<String> ENV_VARIABLES_TO_CLEAR =
      ImmutableSortedSet.of(
          // These variables are all used by runfiles libraries to locate the runfiles directory or
          // manifest and can cause incorrect behavior when set for the top-level binary run with
          // bazel run.
          "JAVA_RUNFILES",
          "RUNFILES_DIR",
          "RUNFILES_MANIFEST_FILE",
          "RUNFILES_MANIFEST_ONLY",
          "TEST_SRCDIR");

  /** The test policy to determine the environment variables from when running tests */
  private final TestPolicy testPolicy;

  public RunCommand(TestPolicy testPolicy) {
    this.testPolicy = testPolicy;
  }

  @Override
  public void editOptions(OptionsParser optionsParser) {}

  /** Returns the arguments in a {@link ConfiguredTarget}'s {@code args} attribute. */
  private static ImmutableList<String> getBinaryArgs(ConfiguredTarget targetToRun)
      throws InterruptedException, CommandLineExpansionException {
    List<String> args = Lists.newArrayList();

    FilesToRunProvider provider = targetToRun.getProvider(FilesToRunProvider.class);
    RunfilesSupport runfilesSupport = provider == null ? null : provider.getRunfilesSupport();
    if (runfilesSupport != null && runfilesSupport.getArgs() != null) {
      CommandLine targetArgs = runfilesSupport.getArgs();
      Iterables.addAll(args, targetArgs.arguments());
    }
    return ImmutableList.copyOf(args);
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    RunOptions runOptions = options.getOptions(RunOptions.class);
    // This list should look like: ["//executable:target", "arg1", "arg2"]
    List<String> targetAndArgs = options.getResidue();

    // The user must at the least specify an executable target.
    if (targetAndArgs.isEmpty()) {
      env.getReporter()
          .post(
              new RunBuildCompleteEvent(
                  ExitCode.COMMAND_LINE_ERROR, env.getRuntime().getClock().currentTimeMillis()));
      return reportAndCreateFailureResult(
          env, "Must specify a target to run", Code.NO_TARGET_SPECIFIED);
    }
    String targetString = targetAndArgs.get(0);
    RunUnder runUnder = options.getOptions(CoreOptions.class).runUnder;

    BuiltTargets builtTargets;
    try {
      builtTargets = runBuild(env, options, targetString, runUnder);
    } catch (RunCommandException e) {
      env.getReporter()
          .post(
              new RunBuildCompleteEvent(
                  e.result.getDetailedExitCode().getExitCode(), e.finishTimeMillis));
      return e.result;
    }
    ImmutableList.Builder<BuildEventId> runCompleteChildrenEvents =
        ImmutableList.<BuildEventId>builder()
            .add(BuildEventIdUtil.buildToolLogs())
            .add(BuildEventIdUtil.buildMetrics());
    if (runOptions.scriptPath == null) {
      runCompleteChildrenEvents.add(BuildEventIdUtil.execRequestId());
    }
    env.getReporter()
        .post(
            new RunBuildCompleteEvent(
                // If the build returned non-zero exit code, an error would have already been
                // thrown.
                ExitCode.SUCCESS, builtTargets.stopTime, runCompleteChildrenEvents.build()));
    ImmutableList<String> argsFromResidue =
        ImmutableList.copyOf(targetAndArgs.subList(1, targetAndArgs.size()));
    RunCommandLine runCommandLine;
    try {
      runCommandLine =
          getCommandLineInfo(
              env, builtTargets, options, argsFromResidue, runOptions.runEnvironment, testPolicy);
    } catch (RunCommandException e) {
      return e.result;
    }
    boolean batchMode =
        env.getRuntime()
            .getStartupOptionsProvider()
            .getOptions(BlazeServerStartupOptions.class)
            .batch;
    TreeMap<String, String> finalRunEnv = new TreeMap<>(runCommandLine.getEnvironment());
    if (batchMode) {
      // In --batch, prioritize original client env-var values over those added by the c++ launcher.
      // Only necessary in --batch since the command runs as a subprocess of the java server.
      finalRunEnv.putAll(env.getClientEnv());
    }

    ExecRequest.Builder execRequest;
    try {
      boolean shouldRunTarget = runOptions.scriptPath == null && runOptions.runBuiltTarget;
      ImmutableList<PathToReplace> pathsToReplace =
          runOptions.portablePaths
              ? getPathsToReplace(
                  env,
                  /* testLogDir= */ builtTargets
                      .configuration
                      .getTestLogsDirectory(RepositoryName.MAIN)
                      .getExecPathString(),
                  runCommandLine.isTestTarget())
              : ImmutableList.of();

      execRequest =
          execRequestBuilder(
              env,
              runCommandLine,
              ImmutableSortedMap.copyOf(finalRunEnv),
              builtTargets.configuration,
              builtTargets.stopTime,
              shouldRunTarget,
              pathsToReplace);
    } catch (RunCommandException e) {
      return e.result;
    }

    if (runOptions.scriptPath != null) {
      return handleScriptPath(runOptions, execRequest, runCommandLine, env, builtTargets);
    }
    if (runOptions.runBuiltTarget) {
      env.getReporter()
          .handle(Event.info(null, "Running command line: " + runCommandLine.getPrettyArgs()));
    } else {
      env.getReporter()
          .handle(Event.info(null, "Runnable command line: " + runCommandLine.getPrettyArgs()));
    }

    try {
      env.getReporter()
          .post(
              new ExecRequestEvent(
                  execRequest.build(),
                  /* redactedArgv= */ options.getOptions(BuildEventProtocolOptions.class)
                          .includeResidueInRunBepEvent
                      ? ImmutableList.copyOf(execRequest.getArgvList())
                      : getArgvWithoutResidue(
                          env, runCommandLine, builtTargets.configuration, builtTargets.stopTime)));
      return BlazeCommandResult.execute(execRequest.build());
    } catch (RunCommandException e) {
      return e.result;
    }
  }

  private static BuiltTargets runBuild(
      CommandEnvironment env,
      OptionsParsingResult options,
      String targetString,
      @Nullable RunUnder runUnder)
      throws RunCommandException {
    ImmutableList<String> targetsToBuild =
        runUnder instanceof LabelRunUnder runUnderLabel
            ? ImmutableList.of(targetString, runUnderLabel.label().toString())
            : ImmutableList.of(targetString);
    BuildRequest request =
        BuildRequest.builder()
            .setCommandName(RunCommand.class.getAnnotation(Command.class).name())
            .setId(env.getCommandId())
            .setOptions(options)
            .setStartupOptions(env.getRuntime().getStartupOptionsProvider())
            .setOutErr(env.getReporter().getOutErr())
            .setTargets(targetsToBuild)
            .setStartTimeMillis(env.getCommandStartTime())
            .build();

    BuildResult buildResult =
        new BuildTool(env)
            .processRequest(
                request,
                (Collection<Target> tgts, boolean keepGoing) ->
                    validateTargets(
                        env.getReporter(), request.getTargets(), tgts, runUnder, keepGoing),
                options);
    if (!buildResult.getSuccess()) {
      env.getReporter().handle(Event.error("Build failed. Not running target"));
      throw new RunCommandException(
          BlazeCommandResult.detailedExitCode(buildResult.getDetailedExitCode()),
          buildResult.getStopTime());
    }
    // Build succeeded - make sure outputs are available before attempting to use them.
    flushOutputs(env);

    return getBuiltTargets(buildResult, env, targetString, runUnder);
  }

  private static BuiltTargets getBuiltTargets(
      BuildResult result, CommandEnvironment env, String targetString, RunUnder runUnder)
      throws RunCommandException {
    Collection<ConfiguredTarget> topLevelTargets = result.getSuccessfulTargets();
    ConfiguredTarget targetToRun = null;
    ConfiguredTarget runUnderTarget = null;

    if (topLevelTargets != null) {
      // Make sure that we have exactly 1 built target (excluding --run_under) and that it is
      // executable. These checks should only fail if keepGoing is true, because we already did
      // validation before the build began in validateTargets().
      int maxTargets = runUnder instanceof LabelRunUnder ? 2 : 1;
      if (topLevelTargets.size() > maxTargets) {
        throw new RunCommandException(
            reportAndCreateFailureResult(
                env,
                makeErrorMessageForNotHavingASingleTarget(
                    targetString,
                    Iterables.transform(topLevelTargets, ct -> ct.getLabel().toString())),
                Code.TOO_MANY_TARGETS_SPECIFIED),
            result.getStopTime());
      }

      for (ConfiguredTarget target : topLevelTargets) {
        BlazeCommandResult targetValidationResult = fullyValidateTarget(env, target);
        if (!targetValidationResult.isSuccess()) {
          throw new RunCommandException(targetValidationResult, result.getStopTime());
        }
        if (runUnder instanceof LabelRunUnder labelRunUnder
            && target.getOriginalLabel().equals(labelRunUnder.label())) {
          if (runUnderTarget != null) {
            throw new RunCommandException(
                reportAndCreateFailureResult(
                    env,
                    "Can't identify the run_under target from multiple options?",
                    Code.RUN_UNDER_TARGET_NOT_BUILT),
                result.getStopTime());
          }
          runUnderTarget = target;
        } else if (targetToRun == null) {
          targetToRun = target;
        } else {
          throw new RunCommandException(
              reportAndCreateFailureResult(
                  env,
                  makeErrorMessageForNotHavingASingleTarget(
                      targetString,
                      Iterables.transform(topLevelTargets, ct -> ct.getLabel().toString())),
                  Code.TOO_MANY_TARGETS_SPECIFIED),
              result.getStopTime());
        }
      }
    }

    // Handle target & run_under referring to the same target.
    if (targetToRun == null && runUnderTarget != null) {
      targetToRun = runUnderTarget;
    }

    if (targetToRun == null) {
      throw new RunCommandException(
          reportAndCreateFailureResult(env, NO_TARGET_MESSAGE, Code.NO_TARGET_SPECIFIED),
          result.getStopTime());
    }

    BuildConfigurationValue configuration =
        env.getSkyframeExecutor()
            .getConfiguration(env.getReporter(), targetToRun.getConfigurationKey());
    if (configuration == null) {
      // The target may be an input file, which doesn't have a configuration. In that case, we
      // choose any target configuration.
      configuration = result.getBuildConfiguration();
    }

    if (!configuration.buildRunfileManifests()) {
      throw new RunCommandException(
          reportAndCreateFailureResult(
              env,
              "--nobuild_runfile_manifests is incompatible with the \"run\" command",
              Code.RUN_PREREQ_UNMET),
          result.getStopTime());
    }

    // Ensure runfiles directories are constructed, both for the target to run
    // and the --run_under target. The path of the runfiles directory of the
    // target to run needs to be preserved, as it acts as the working directory.
    Path targetToRunRunfilesDir = null;
    RunfilesSupport targetToRunRunfilesSupport = null;
    RunfilesTreeUpdater runfilesTreeUpdater = RunfilesTreeUpdater.forCommandEnvironment(env);
    for (ConfiguredTarget target : topLevelTargets) {
      FilesToRunProvider provider = target.getProvider(FilesToRunProvider.class);
      RunfilesSupport runfilesSupport = provider == null ? null : provider.getRunfilesSupport();

      if (runfilesSupport == null) {
        continue;
      }
      try {
        Path runfilesDir =
            ensureRunfilesBuilt(
                env,
                runfilesSupport,
                env.getSkyframeExecutor()
                    .getConfiguration(env.getReporter(), target.getConfigurationKey()),
                runfilesTreeUpdater);
        if (target == targetToRun) {
          targetToRunRunfilesDir = runfilesDir;
          targetToRunRunfilesSupport = runfilesSupport;
        }
      } catch (RunfilesException e) {
        env.getReporter().handle(Event.error(e.getMessage()));
        throw new RunCommandException(
            BlazeCommandResult.failureDetail(e.createFailureDetail()), result.getStopTime());
      } catch (InterruptedException e) {
        env.getReporter().handle(Event.error("Interrupted"));
        throw new RunCommandException(
            BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setInterrupted(Interrupted.newBuilder().setCode(Interrupted.Code.INTERRUPTED))
                    .build()),
            result.getStopTime());
      }
    }
    return new BuiltTargets(
        targetToRun,
        targetToRunRunfilesDir,
        targetToRunRunfilesSupport,
        runUnderTarget,
        configuration,
        result.getConvenienceSymlinks(),
        result.getStopTime());
  }

  private static ExecRequest.Builder execRequestBuilder(
      CommandEnvironment env,
      RunCommandLine runCommandLine,
      ImmutableSortedMap<String, String> runEnv,
      BuildConfigurationValue configuration,
      long stopTime,
      boolean shouldRunTarget,
      ImmutableList<PathToReplace> pathsToReplace)
      throws RunCommandException {
    ExecRequest.Builder execDescription =
        ExecRequest.newBuilder()
            .setWorkingDirectory(
                ByteString.copyFrom(runCommandLine.getWorkingDir().getPathString(), ISO_8859_1))
            .addAllArgv(getArgvForExecRequest(env, runCommandLine, configuration, stopTime));

    for (Map.Entry<String, String> variable : runEnv.entrySet()) {
      execDescription.addEnvironmentVariable(
          EnvironmentVariable.newBuilder()
              .setName(ByteString.copyFrom(variable.getKey(), ISO_8859_1))
              .setValue(ByteString.copyFrom(variable.getValue(), ISO_8859_1))
              .build());
    }
    return execDescription
        .addAllEnvironmentVariableToClear(
            ENV_VARIABLES_TO_CLEAR.stream()
                .map(s -> ByteString.copyFrom(s, ISO_8859_1))
                .collect(toImmutableList()))
        .setShouldExec(shouldRunTarget)
        .addAllPathToReplace(pathsToReplace);
  }

  private static ImmutableList<PathToReplace> getPathsToReplace(
      CommandEnvironment env, String testLogDir, boolean isTestTarget) {
    ImmutableList<PathToReplace> pathsToReplace = PathToReplaceUtils.getPathsToReplace(env);
    if (isTestTarget) {
      return ImmutableList.<PathToReplace>builder()
          .addAll(pathsToReplace)
          .add(
              PathToReplace.newBuilder()
                  .setType(PathToReplace.Type.TEST_LOG_SUBDIR)
                  .setValue(ByteString.copyFrom(testLogDir, ISO_8859_1))
                  .build())
          .build();
    }
    return pathsToReplace;
  }

  private static ImmutableList<ByteString> getArgvForExecRequest(
      CommandEnvironment env,
      RunCommandLine runCommandLine,
      BuildConfigurationValue configuration,
      long stopTime)
      throws RunCommandException {
    return getArgv(env, runCommandLine, /* includeResidue= */ true, configuration, stopTime);
  }

  private static ImmutableList<ByteString> getArgvWithoutResidue(
      CommandEnvironment env,
      RunCommandLine runCommandLine,
      BuildConfigurationValue configuration,
      long stopTime)
      throws RunCommandException {
    return getArgv(env, runCommandLine, /* includeResidue= */ false, configuration, stopTime);
  }

  private static ImmutableList<ByteString> getArgv(
      CommandEnvironment env,
      RunCommandLine runCommandLine,
      boolean includeResidue,
      BuildConfigurationValue configuration,
      long stopTime)
      throws RunCommandException {
    String shExecutable = null;
    if (runCommandLine.requiresShExecutable()) {
      shExecutable = getShellExecutableOrThrow(env, configuration, /* reason= */ "", stopTime);
    }
    ImmutableList<String> args =
        includeResidue
            ? runCommandLine.getArgs(shExecutable)
            : runCommandLine.getArgsWithoutResidue(shExecutable);
    return args.stream().map(s -> ByteString.copyFrom(s, ISO_8859_1)).collect(toImmutableList());
  }

  private static BlazeCommandResult handleScriptPath(
      RunOptions runOptions,
      ExecRequest.Builder execRequest,
      RunCommandLine runCommandLine,
      CommandEnvironment env,
      BuiltTargets builtTargets) {
    String shExecutable;
    try {
      shExecutable =
          getShellExecutableOrThrow(
              env, builtTargets.configuration, "with \"--script_path\"", builtTargets.stopTime);
    } catch (RunCommandException e) {
      return e.result;
    }

    String scriptContents = runCommandLine.getScriptForm(shExecutable, ENV_VARIABLES_TO_CLEAR);

    if (runOptions.emitScriptPathInExecRequest) {
      execRequest.setScriptPath(
          CommandProtos.ScriptPath.newBuilder()
              .setScriptPath(ByteString.copyFrom(runOptions.scriptPath.toString(), ISO_8859_1))
              .setScriptContents(ByteString.copyFrom(scriptContents, ISO_8859_1))
              .build());
      return BlazeCommandResult.execute(execRequest.build());
    } else {
      try {
        writeScript(env, runOptions.scriptPath, scriptContents);
      } catch (IOException e) {
        String message = "Error writing run script: " + e.getMessage();
        return reportAndCreateFailureResult(env, message, Code.SCRIPT_WRITE_FAILURE);
      }
      return BlazeCommandResult.success();
    }
  }

  private static RunCommandLine getCommandLineInfo(
      CommandEnvironment env,
      BuiltTargets builtTargets,
      OptionsParsingResult options,
      ImmutableList<String> argsFromResidue,
      List<Map.Entry<String, String>> extraRunEnvironment,
      TestPolicy testPolicy)
      throws RunCommandException {
    if (builtTargets.targetToRun.getProvider(TestProvider.class) != null) {
      return getTestCommandLine(env, builtTargets, options, argsFromResidue, testPolicy);
    }

    ActionEnvironment actionEnvironment = ActionEnvironment.EMPTY;
    if (builtTargets.targetToRunRunfilesSupport != null) {
      actionEnvironment = builtTargets.targetToRunRunfilesSupport.getActionEnvironment();
    }
    RunEnvironmentInfo environmentProvider =
        builtTargets.targetToRun.get(RunEnvironmentInfo.PROVIDER);
    if (environmentProvider != null) {
      actionEnvironment =
          actionEnvironment.withAdditionalVariables(
              environmentProvider.getEnvironment(),
              ImmutableSet.copyOf(environmentProvider.getInheritedEnvironment()));
    }
    TreeMap<String, String> runEnvironment = makeMutableRunEnvironment(env);
    actionEnvironment.resolve(runEnvironment, env.getClientEnv());
    for (var entry : extraRunEnvironment) {
      runEnvironment.put(entry.getKey(), entry.getValue());
    }

    ImmutableList<String> argsFromBinary;
    try {
      argsFromBinary = getBinaryArgs(builtTargets.targetToRun);
    } catch (InterruptedException e) {
      String message = "run: command line expansion interrupted";
      env.getReporter().handle(Event.error(message));
      throw new RunCommandException(
          BlazeCommandResult.detailedExitCode(InterruptedFailureDetails.detailedExitCode(message)),
          builtTargets.stopTime);
    } catch (CommandLineExpansionException e) {
      throw new RunCommandException(
          reportAndCreateFailureResult(
              env, Strings.nullToEmpty(e.getMessage()), Code.COMMAND_LINE_EXPANSION_FAILURE),
          builtTargets.stopTime);
    }

    return constructCommandLine(
        env,
        builtTargets,
        ImmutableSortedMap.copyOf(runEnvironment),
        argsFromBinary,
        argsFromResidue);
  }

  /**
   * Returns the command line for the test, making a best effort to mimic the environment had we run
   * `test //target`.
   */
  private static RunCommandLine getTestCommandLine(
      CommandEnvironment env,
      BuiltTargets builtTargets,
      OptionsParsingResult options,
      ImmutableList<String> argsFromResidue,
      TestPolicy testPolicy)
      throws RunCommandException {
    ImmutableList<Artifact.DerivedArtifact> statusArtifacts =
        TestProvider.getTestStatusArtifacts(builtTargets.targetToRun);
    if (statusArtifacts.size() != 1) {
      throw new RunCommandException(
          reportAndCreateFailureResult(
              env, MULTIPLE_TESTS_MESSAGE, Code.TOO_MANY_TEST_SHARDS_OR_RUNS),
          builtTargets.stopTime);
    }

    TestRunnerAction testAction =
        (TestRunnerAction)
            env.getSkyframeExecutor()
                .getActionGraph(env.getReporter())
                .getGeneratingAction(Iterables.getOnlyElement(statusArtifacts));
    TestTargetExecutionSettings settings = testAction.getExecutionSettings();
    // ensureRunfilesBuilt does build the runfiles, but an extra consistency check won't hurt.
    Preconditions.checkState(
        settings.getRunfilesSymlinksCreated()
            == options.getOptions(CoreOptions.class).buildRunfileLinks);

    ExecutionOptions executionOptions = options.getOptions(ExecutionOptions.class);
    Path tmpDirRoot =
        TestStrategy.getTmpRoot(env.getWorkspace(), env.getExecRoot(), executionOptions);
    PathFragment maybeRelativeTmpDir =
        tmpDirRoot.startsWith(env.getExecRoot())
            ? tmpDirRoot.relativeTo(env.getExecRoot())
            : tmpDirRoot.asFragment();
    TreeMap<String, String> runEnvironment = makeMutableRunEnvironment(env);
    runEnvironment.putAll(
        testPolicy.computeTestEnvironment(
            testAction,
            env.getClientEnv(),
            settings.getRunfilesDir().relativeTo(env.getExecRoot()),
            maybeRelativeTmpDir.getRelative(TestStrategy.getTmpDirName(testAction))));

    try {
      testAction.prepare(
          env.getExecRoot(),
          ArtifactPathResolver.IDENTITY,
          /* bulkDeleter= */ null,
          /* cleanupArchivedArtifacts= */ false);
    } catch (IOException e) {
      throw new RunCommandException(
          reportAndCreateFailureResult(
              env,
              "Error while setting up test: " + e.getMessage(),
              Code.TEST_ENVIRONMENT_SETUP_FAILURE),
          builtTargets.stopTime);
    } catch (InterruptedException e) {
      throw new RunCommandException(
          reportAndCreateFailureResult(
              env,
              "Error while setting up test: " + e.getMessage(),
              Code.TEST_ENVIRONMENT_SETUP_INTERRUPTED),
          builtTargets.stopTime);
    }

    ImmutableList<String> testArgs;
    try {
      testArgs = TestStrategy.getArgs(testAction);
    } catch (ExecException e) {
      throw new RunCommandException(
          reportAndCreateFailureResult(
              env, Strings.nullToEmpty(e.getMessage()), Code.COMMAND_LINE_EXPANSION_FAILURE),
          builtTargets.stopTime);
    } catch (InterruptedException e) {
      String message = "run: command line expansion interrupted";
      env.getReporter().handle(Event.error(message));
      throw new RunCommandException(
          BlazeCommandResult.detailedExitCode(InterruptedFailureDetails.detailedExitCode(message)),
          builtTargets.stopTime);
    }

    return new RunCommandLine.Builder(
            ImmutableSortedMap.copyOf(runEnvironment), env.getExecRoot(), /* isTestTarget= */ true)
        .addArgs(testArgs)
        .addArgsFromResidue(argsFromResidue)
        .build();
  }

  /**
   * Returns a new {@link TreeMap} with environment variables common to all run invocations. The
   * return value is a new, mutable instance - this is necessary since we want to maintain order and
   * overwrite existing keys, something which isn't supported by current immutable implementations.
   */
  @SuppressWarnings("NonApiType") // Sorted, mutable map is desired - see javadoc.
  private static TreeMap<String, String> makeMutableRunEnvironment(CommandEnvironment env) {
    TreeMap<String, String> result = new TreeMap<>();
    result.put("BUILD_WORKSPACE_DIRECTORY", env.getWorkspace().getPathString());
    result.put("BUILD_WORKING_DIRECTORY", env.getWorkingDirectory().getPathString());
    result.put("BUILD_EXECROOT", env.getExecRoot().getPathString());
    result.put("BUILD_ID", env.getCommandId().toString());
    return result;
  }

  private static RunCommandLine constructCommandLine(
      CommandEnvironment env,
      BuiltTargets builtTargets,
      ImmutableSortedMap<String, String> runEnvironment,
      ImmutableList<String> argsFromBinary,
      ImmutableList<String> argsFromResidue) {
    BuildRequestOptions requestOptions = env.getOptions().getOptions(BuildRequestOptions.class);
    PathPrettyPrinter prettyPrinter =
        new PathPrettyPrinter(
            requestOptions.getSymlinkPrefix(env.getRuntime().getProductName()),
            builtTargets.convenienceSymlinks);

    RunCommandLine.Builder runCommandLine =
        new RunCommandLine.Builder(
            runEnvironment,
            /* workingDir= */ builtTargets.targetToRunRunfilesDir != null
                ? builtTargets.targetToRunRunfilesDir
                : env.getWorkingDirectory(),
            /* isTestTarget= */ false);

    RunUnder runUnder = env.getOptions().getOptions(CoreOptions.class).runUnder;
    // Insert the command prefix specified by the "--run_under=<command-prefix>" option
    // at the start of the command line.
    if (runUnder != null) {
      if (builtTargets.runUnderTarget != null) {
        // --run_under specifies a target. Get the corresponding executable, this will be an
        // absolute path because the run_under target is only in the runfiles of test targets
        Path runUnderPath =
            builtTargets
                .runUnderTarget
                .getProvider(FilesToRunProvider.class)
                .getExecutable()
                .getPath();
        runCommandLine.setRunUnderTarget(runUnderPath, runUnder.options(), prettyPrinter);
      } else {
        runCommandLine.setRunUnderPrefix(runUnder.value());
      }
    }

    Artifact executable =
        builtTargets.targetToRun.getProvider(FilesToRunProvider.class).getExecutable();
    return runCommandLine
        .addArg(executable.getPath(), prettyPrinter)
        .addArgs(argsFromBinary)
        .addArgsFromResidue(argsFromResidue)
        .build();
  }

  private static String getShellExecutableOrThrow(
      CommandEnvironment env, BuildConfigurationValue configuration, String reason, long stopTime)
      throws RunCommandException {
    PathFragment shExecutable = ShToolchain.getPathForHost(configuration);
    if (shExecutable.isEmpty()) {
      throw new RunCommandException(
          reportAndCreateFailureResult(
              env,
              "the \"run\" command needs a shell"
                  + reason
                  + "; use the --shell_executable=<path> "
                  + "flag to specify the shell's path, e.g. --shell_executable=/bin/bash",
              Code.NO_SHELL_SPECIFIED),
          stopTime);
    }
    return shExecutable.getPathString();
  }

  private static class RunCommandException extends Exception {
    private final BlazeCommandResult result;
    private final long finishTimeMillis;

    private RunCommandException(BlazeCommandResult result, long finishTimeMillis) {
      Preconditions.checkArgument(!result.isSuccess(), "Success is not exceptional: %s", result);
      this.result = result;
      this.finishTimeMillis = finishTimeMillis;
    }
  }

  /** Contains the targets built as part of a run-command invocation. */
  private static class BuiltTargets {
    private final ConfiguredTarget targetToRun;
    private final Path targetToRunRunfilesDir;
    private final RunfilesSupport targetToRunRunfilesSupport;
    @Nullable private final ConfiguredTarget runUnderTarget;
    private final BuildConfigurationValue configuration;
    private final ImmutableMap<PathFragment, PathFragment> convenienceSymlinks;
    private final long stopTime;

    private BuiltTargets(
        ConfiguredTarget targetToRun,
        Path targetToRunRunfilesDir,
        RunfilesSupport targetToRunRunfilesSupport,
        @Nullable ConfiguredTarget runUnderTarget,
        BuildConfigurationValue configuration,
        ImmutableMap<PathFragment, PathFragment> convenienceSymlinks,
        long stopTime) {
      this.targetToRun = targetToRun;
      this.runUnderTarget = runUnderTarget;
      this.targetToRunRunfilesDir = targetToRunRunfilesDir;
      this.targetToRunRunfilesSupport = targetToRunRunfilesSupport;
      this.configuration = configuration;
      this.convenienceSymlinks = convenienceSymlinks;
      this.stopTime = stopTime;
    }
  }

  /**
   * When using an output service (e.g. Build without the Bytes), flushes the output tree, waiting
   * for downloads to complete. This is necessary since outputs might still be downloading in the
   * background.
   */
  private static void flushOutputs(CommandEnvironment env) {
    if (env.getOutputService() != null) {
      try {
        env.getOutputService().flushOutputTree();
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static BlazeCommandResult reportAndCreateFailureResult(
      CommandEnvironment env, String message, Code detailedCode) {
    env.getReporter().handle(Event.error(message));
    return BlazeCommandResult.failureDetail(createFailureDetail(message, detailedCode));
  }

  /**
   * Ensures that runfiles are built for the specified target. If they already are, does nothing,
   * otherwise builds them.
   */
  private static Path ensureRunfilesBuilt(
      CommandEnvironment env,
      RunfilesSupport runfilesSupport,
      BuildConfigurationValue configuration,
      RunfilesTreeUpdater runfilesTreeUpdater)
      throws RunfilesException, InterruptedException {
    PathFragment runfilesDir = runfilesSupport.getRunfilesTree().getExecPath();
    Path workingDir = env.getExecRoot().getRelative(runfilesDir);
    // On Windows, runfiles tree is disabled.
    // Workspace name directory doesn't exist, so don't add it.
    if (configuration.runfilesEnabled()) {
      workingDir = workingDir.getRelative(runfilesSupport.getRunfiles().getPrefix());
    }

    // Always create runfiles directory and the workspace-named directory underneath, even if we
    // run with --enable_runfiles=no (which is the default on Windows as of 2020-01-24).
    // If the binary we run is in fact a test, it will expect to be able to chdir into the runfiles
    // directory. See https://github.com/bazelbuild/bazel/issues/10621
    try {
      runfilesSupport
          .getRunfilesDirectory()
          .getRelative(runfilesSupport.getRunfilesTree().getWorkspaceName())
          .createDirectoryAndParents();
    } catch (IOException e) {
      throw new RunfilesException(
          "Failed to create runfiles directories: " + e.getMessage(),
          Code.RUNFILES_DIRECTORIES_CREATION_FAILURE,
          e);
    }

    try {
      runfilesTreeUpdater.updateRunfiles(
          ImmutableList.of(runfilesSupport.getRunfilesTree()),
          /* env= */ ImmutableMap.of(),
          /* outErr= */ null);
    } catch (ExecException | IOException e) {
      throw new RunfilesException(
          "Failed to create runfiles symlinks: " + e.getMessage(),
          Code.RUNFILES_SYMLINKS_CREATION_FAILURE,
          e);
    }
    return workingDir;
  }

  private static void writeScript(
      CommandEnvironment env, PathFragment scriptPathFrag, String scriptContent)
      throws IOException {
    Path scriptPath = env.getWorkingDirectory().getRelative(scriptPathFrag);
    FileSystemUtils.writeContent(scriptPath, ISO_8859_1, scriptContent);
    scriptPath.setExecutable(true);
  }

  // Make sure we are building exactly 1 binary target.
  // If keepGoing, we'll build all the targets even if they are non-binary.
  private static void validateTargets(
      Reporter reporter,
      List<String> targetPatternStrings,
      Collection<Target> targets,
      RunUnder runUnder,
      boolean keepGoing)
      throws LoadingFailedException {
    Target targetToRun = null;
    Target runUnderTarget = null;

    boolean singleTargetWarningWasOutput = false;
    int maxTargets = runUnder instanceof LabelRunUnder ? 2 : 1;
    if (targets.size() > maxTargets) {
      warningOrException(
          reporter,
          makeErrorMessageForNotHavingASingleTarget(
              targetPatternStrings.get(0),
              Iterables.transform(targets, t -> t.getLabel().toString())),
          keepGoing,
          Code.TOO_MANY_TARGETS_SPECIFIED);
      singleTargetWarningWasOutput = true;
    }
    for (Target target : targets) {
      if (!isExecutable(target)) {
        warningOrException(
            reporter, notExecutableError(target), keepGoing, Code.TARGET_NOT_EXECUTABLE);
      }

      if (runUnder instanceof LabelRunUnder labelRunUnder
          && target.getLabel().equals(labelRunUnder.label())) {
        // It's impossible to have two targets with the same label.
        Preconditions.checkState(runUnderTarget == null);
        runUnderTarget = target;
      } else if (targetToRun == null) {
        targetToRun = target;
      } else {
        if (!singleTargetWarningWasOutput) {
          warningOrException(
              reporter,
              makeErrorMessageForNotHavingASingleTarget(
                  targetPatternStrings.get(0),
                  Iterables.transform(targets, t -> t.getLabel().toString())),
              keepGoing,
              Code.TOO_MANY_TARGETS_SPECIFIED);
        }
        return;
      }
    }
    // Handle target & run_under referring to the same target.
    if ((targetToRun == null) && (runUnderTarget != null)) {
      targetToRun = runUnderTarget;
    }
    if (targetToRun == null) {
      warningOrException(reporter, NO_TARGET_MESSAGE, keepGoing, Code.NO_TARGET_SPECIFIED);
    }
  }

  /**
   * If keepGoing, print a warning and return the given collection. Otherwise, throw
   * InvalidTargetException.
   */
  private static void warningOrException(
      Reporter reporter, String message, boolean keepGoing, Code detailedCode)
      throws LoadingFailedException {
    if (keepGoing) {
      reporter.handle(Event.warn(message + ". Will continue anyway"));
    } else {
      throw new LoadingFailedException(
          message, DetailedExitCode.of(createFailureDetail(message, detailedCode)));
    }
  }

  private static String notExecutableError(Target target) {
    return "Cannot run target " + target.getLabel() + ": Not executable";
  }

  /**
   * Performs all available validation checks on an individual target.
   *
   * @param configuredTarget ConfiguredTarget to validate
   * @return BlazeCommandResult.exitCode(ExitCode.SUCCESS) if all checks succeeded, otherwise a
   *     result describing the failure.
   * @throws IllegalStateException if unable to find a target from the package manager.
   */
  private static BlazeCommandResult fullyValidateTarget(
      CommandEnvironment env, ConfiguredTarget configuredTarget) {

    Target target;
    try {
      target = env.getPackageManager().getTarget(env.getReporter(), configuredTarget.getLabel());
    } catch (InterruptedException e) {
      String message = "run command interrupted";
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(message));
    } catch (NoSuchTargetException | NoSuchPackageException e) {
      env.getReporter().handle(Event.error("Failed to find a target to validate. " + e));
      throw new IllegalStateException("Failed to find a target to validate", e);
    }

    if (!isExecutable(target)) {
      return reportAndCreateFailureResult(
          env, notExecutableError(target), Code.TARGET_NOT_EXECUTABLE);
    }

    Artifact executable =
        Preconditions.checkNotNull(
                configuredTarget.getProvider(FilesToRunProvider.class), configuredTarget)
            .getExecutable();
    if (executable == null) {
      return reportAndCreateFailureResult(
          env, notExecutableError(target), Code.TARGET_NOT_EXECUTABLE);
    }

    Path executablePath = executable.getPath();
    try {
      if (!executablePath.exists() || !executablePath.isExecutable()) {
        return reportAndCreateFailureResult(
            env,
            "Non-existent or non-executable " + executablePath,
            Code.TARGET_BUILT_BUT_PATH_NOT_EXECUTABLE);
      }
    } catch (IOException e) {
      return reportAndCreateFailureResult(
          env,
          "Error checking " + executablePath.getPathString() + ": " + e.getMessage(),
          Code.TARGET_BUILT_BUT_PATH_VALIDATION_FAILED);
    }

    return BlazeCommandResult.success();
  }

  /**
   * Return true iff it is possible that {@code target} is a rule that has an executable file. This
   * *_test rules, *_binary rules, aliases, generated outputs, and inputs.
   *
   * <p>Determining definitively whether a rule produces an executable can only be done after
   * analysis. This is only an early check to quickly catch most mistakes.
   */
  private static boolean isExecutable(Target target) {
    return isPlainFile(target)
        || isExecutableNonTestRule(target)
        || TargetUtils.isTestRule(target)
        || AliasProvider.mayBeAlias(target);
  }

  /**
   * Return true iff {@code target} is a rule that generates an executable file and is user-executed
   * code.
   */
  private static boolean isExecutableNonTestRule(Target target) {
    if (!(target instanceof Rule rule)) {
      return false;
    }
    return rule.isExecutable();
  }

  private static boolean isPlainFile(Target target) {
    return (target instanceof OutputFile) || (target instanceof InputFile);
  }

  private static String makeErrorMessageForNotHavingASingleTarget(
      String targetPatternString, Iterable<String> expandedTargetNames) {
    final int maxNumExpandedTargetsToIncludeInErrorMessage = 5;
    boolean truncateTargetNameList = Iterables.size(expandedTargetNames) > 5;
    Iterable<String> targetNamesToIncludeInErrorMessage =
        truncateTargetNameList
            ? Iterables.limit(expandedTargetNames, maxNumExpandedTargetsToIncludeInErrorMessage)
            : expandedTargetNames;
    return String.format(
        "Only a single target can be run. Your target pattern %s expanded to the targets %s%s",
        targetPatternString,
        Joiner.on(", ").join(ImmutableSortedSet.copyOf(targetNamesToIncludeInErrorMessage)),
        truncateTargetNameList ? "[TRUNCATED]" : "");
  }

  private static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setRunCommand(FailureDetails.RunCommand.newBuilder().setCode(detailedCode))
        .build();
  }

  private static class RunfilesException extends Exception {
    private final FailureDetails.RunCommand.Code detailedCode;

    private RunfilesException(String message, Code detailedCode, Exception cause) {
      super("Error creating runfiles: " + message, cause);
      this.detailedCode = detailedCode;
    }

    private FailureDetail createFailureDetail() {
      return RunCommand.createFailureDetail(getMessage(), detailedCode);
    }
  }
}
