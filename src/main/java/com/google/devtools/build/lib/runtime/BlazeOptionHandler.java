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
package com.google.devtools.build.lib.runtime;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.runtime.ConfigFlagDefinitions.ConfigDefinition;
import com.google.devtools.build.lib.runtime.StarlarkOptionsParser.BuildSettingLoader;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.Command.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.InvocationPolicyEnforcer;
import com.google.devtools.common.options.OptionAndRawValue;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Handles parsing the blaze command arguments.
 *
 * <p>This class manages rc options, configs, default options, and invocation policy.
 */
public final class BlazeOptionHandler {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // Keep in sync with options added in OptionProcessor::AddRcfileArgsAndOptions()
  private static final ImmutableSet<String> INTERNAL_COMMAND_OPTIONS =
      ImmutableSet.of(
          "rc_source",
          "default_override",
          "isatty",
          "terminal_columns",
          "ignore_client_env",
          "client_env",
          "client_cwd");

  // All options set on this pseudo command are inherited by all commands, with unrecognized options
  // resulting in an error.
  private static final String ALWAYS_PSEUDO_COMMAND = "always";

  // All options set on this pseudo command are inherited by all commands, with unrecognized options
  // being ignored as long as they are recognized by at least one (other) command.
  static final String COMMON_PSEUDO_COMMAND = "common";

  private static final ImmutableSet<String> BUILD_COMMAND_ANCESTORS =
      ImmutableSet.of("build", COMMON_PSEUDO_COMMAND, ALWAYS_PSEUDO_COMMAND);

  // Marks an event to indicate a parsing error.
  static final String BAD_OPTION_TAG = "invalidOption";
  // Separates the invalid tag from the full error message for easier parsing.
  static final String ERROR_SEPARATOR = " :: ";

  private final BlazeRuntime runtime;
  private final OptionsParser optionsParser;
  private final BlazeWorkspace workspace;
  private final BlazeCommand command;
  private final Command commandAnnotation;
  private final InvocationPolicy invocationPolicy;
  private final List<String> rcfileNotes = new ArrayList<>();
  private final ImmutableList<Class<? extends OptionsBase>> allOptionsClasses;

  BlazeOptionHandler(
      BlazeRuntime runtime,
      BlazeWorkspace workspace,
      BlazeCommand command,
      Command commandAnnotation,
      OptionsParser optionsParser,
      InvocationPolicy invocationPolicy) {
    this.runtime = runtime;
    this.workspace = workspace;
    this.command = command;
    this.commandAnnotation = commandAnnotation;
    this.optionsParser = optionsParser;
    this.invocationPolicy = invocationPolicy;
    this.allOptionsClasses =
        runtime.getCommandMap().values().stream()
            .map(BlazeCommand::getClass)
            .flatMap(
                cmd ->
                    BlazeCommandUtils.getOptions(
                        cmd, runtime.getBlazeModules(), runtime.getRuleClassProvider())
                        .stream())
            .distinct()
            .collect(toImmutableList());
  }

  /**
   * Return options as {@link OptionsParsingResult} so the options can't be easily modified after
   * we've applied the invocation policy.
   */
  OptionsParsingResult getOptionsResult() {
    return optionsParser;
  }

  public List<String> getRcfileNotes() {
    return rcfileNotes;
  }

  /**
   * Only some commands work if cwd != workspaceSuffix in Blaze. In that case, also check if Blaze
   * was called from the output directory and fail if it was.
   */
  private DetailedExitCode checkCwdInWorkspace(EventHandler eventHandler) {
    if (!commandAnnotation.mustRunInWorkspace()) {
      return DetailedExitCode.success();
    }

    if (!workspace.getDirectories().inWorkspace()) {
      String message =
          "The '"
              + commandAnnotation.name()
              + "' command is only supported from within a workspace"
              + " (below a directory having a MODULE.bazel file).\n"
              + "See documentation at"
              + " https://bazel.build/concepts/build-ref#workspace";
      eventHandler.handle(Event.error(message));
      return createDetailedExitCode(message, Code.NOT_IN_WORKSPACE);
    }

    Path workspacePath = workspace.getWorkspace();
    if (workspacePath.getParentDirectory() != null) {
      Path doNotBuild =
          workspacePath.getParentDirectory().getRelative(BlazeWorkspace.DO_NOT_BUILD_FILE_NAME);

      if (doNotBuild.exists()) {
        String message = getNotInRealWorkspaceError(doNotBuild);
        eventHandler.handle(Event.error(message));
        return createDetailedExitCode(message, Code.IN_OUTPUT_DIRECTORY);
      }
    }
    return DetailedExitCode.success();
  }

  /**
   * Parses the unconditional options from .rc files for the current command.
   *
   * <p>This is not as trivial as simply taking the list of options for the specified command
   * because commands can inherit arguments from each other, and we have to respect that (e.g. if an
   * option is specified for 'build', it needs to take effect for the 'test' command, too). More
   * specific commands should have priority over the broader commands (say a "build" option that
   * conflicts with a "common" option should override the common one regardless of order.)
   *
   * <p>For each command, the options are parsed in rc order. This uses the primary rc file first,
   * and follows import statements. This is the order in which they were passed by the client.
   */
  @VisibleForTesting
  void parseRcOptions(
      EventHandler eventHandler, ListMultimap<String, RcChunkOfArgs> commandToRcArgs)
      throws OptionsParsingException {
    for (String commandToParse : getCommandNamesToParse(commandAnnotation)) {
      // Get all args defined for this command (or "common"), grouped by rc chunk.
      for (RcChunkOfArgs rcArgs : commandToRcArgs.get(commandToParse)) {
        if (!rcArgs.getArgs().isEmpty()) {
          String inherited = commandToParse.equals(commandAnnotation.name()) ? "" : "Inherited ";
          String source =
              rcArgs.getRcFile().equals("client")
                  ? "Options provided by the client"
                  : String.format(
                      "Reading rc options for '%s' from %s",
                      commandAnnotation.name(), rcArgs.getRcFile());
          rcfileNotes.add(
              String.format(
                  "%s:\n  %s'%s' options: %s",
                  source, inherited, commandToParse, Joiner.on(' ').join(rcArgs.getArgs())));
        }
        if (commandToParse.equals(COMMON_PSEUDO_COMMAND)) {
          // Pass in options data for all commands supported by the runtime so that options that
          // apply to some but not the current command can be ignored.
          //
          // Important note: The consistency checks performed by
          // OptionsParser#getFallbackOptionsData ensure that there aren't any two options across
          // all commands that have the same name but parse differently (e.g. because one accepts
          // a value and the other doesn't). This means that the options available on a command
          // limit the options available on other commands even without command inheritance. This
          // restriction is necessary to ensure that the options specified on the "common"
          // pseudo command can be parsed unambiguously.
          ImmutableList<String> ignoredArgs =
              optionsParser.parseWithSourceFunction(
                  PriorityCategory.RC_FILE,
                  o -> rcArgs.getRcFile(),
                  rcArgs.getArgs(),
                  OptionsParser.getFallbackOptionsData(allOptionsClasses));
          if (!ignoredArgs.isEmpty()) {
            // Append richer information to the note.
            int index = rcfileNotes.size() - 1;
            String note = rcfileNotes.get(index);
            note +=
                String.format(
                    "\n  Ignored as unsupported by '%s': %s",
                    commandAnnotation.name(), Joiner.on(' ').join(ignoredArgs));
            rcfileNotes.set(index, note);
          }
        } else {
          optionsParser.parse(PriorityCategory.RC_FILE, rcArgs.getRcFile(), rcArgs.getArgs());
        }
      }
    }
  }

  /**
   * Returns a map from rc file definitions to the options they define and which rc files defined.
   * them. For example, "build:asan" keys a {@code --config=asan} definition and "build" keys
   * options that apply to all build commands.
   */
  private ListMultimap<String, RcChunkOfArgs> parseArgsAndConfigs(
      List<String> args, ExtendedEventHandler eventHandler)
      throws OptionsParsingException, InterruptedException, AbruptExitException {
    Path workspaceDirectory = workspace.getWorkspace();
    // TODO(ulfjack): The working directory is passed by the client as part of CommonCommandOptions,
    // and we can't know it until after we've parsed the options, so use the workspace for now.
    Path workingDirectory = workspace.getWorkspace();

    Function<OptionDefinition, String> commandOptionSourceFunction =
        option -> {
          if (INTERNAL_COMMAND_OPTIONS.contains(option.getOptionName())) {
            return "options generated by " + runtime.getProductName() + " launcher";
          } else {
            return "command line options";
          }
        };

    // Explicit command-line options:
    List<String> cmdLineAfterCommand = args.subList(1, args.size());

    // Before parsing any rcfiles we need to first parse --rc_source so the parser can reference the
    // proper rcfiles. The --default_override options should be parsed with the --rc_source since
    // {@link #parseRcOptions} depends on the list populated by the {@link
    // ClientOptions#OptionOverrideConverter}.
    ImmutableList.Builder<String> defaultOverridesAndRcSources = new ImmutableList.Builder<>();
    ImmutableList.Builder<String> remainingCmdLine = new ImmutableList.Builder<>();
    partitionCommandLineArgs(cmdLineAfterCommand, defaultOverridesAndRcSources, remainingCmdLine);

    // Parses options needed to parse rcfiles properly.
    optionsParser.parseWithSourceFunction(
        PriorityCategory.COMMAND_LINE,
        commandOptionSourceFunction,
        defaultOverridesAndRcSources.build(),
        /* fallbackData= */ null);

    // Command-specific options from .blazerc passed in via --default_override and --rc_source.
    ClientOptions rcFileOptions = optionsParser.getOptions(ClientOptions.class);
    ListMultimap<String, RcChunkOfArgs> commandToRcArgs =
        structureRcOptionsAndConfigs(
            eventHandler,
            rcFileOptions.rcSource,
            rcFileOptions.optionsOverrides,
            runtime.getCommandMap().keySet());
    parseRcOptions(eventHandler, commandToRcArgs);

    // Parses the remaining command-line options.
    optionsParser.parseWithSourceFunction(
        PriorityCategory.COMMAND_LINE,
        commandOptionSourceFunction,
        remainingCmdLine.build(),
        /* fallbackData= */ null);

    if (commandAnnotation.buildPhase().analyzes()) {
      // split project files from targets in the traditional sense.
      ProjectFileSupport.handleProjectFiles(
          eventHandler,
          runtime.getProjectFileProvider(),
          workspaceDirectory.asFragment(),
          workingDirectory,
          optionsParser,
          commandAnnotation.name());
    }

    expandConfigOptions(eventHandler, commandToRcArgs);
    return commandToRcArgs;
  }

  /**
   * {@link ExtendedEventHandler} override that passes through "normal" events but not events that
   * would go to the build event proto.
   *
   * <p>Starlark flags are conceptually options but still need target pattern evaluation. If we pass
   * {@link #post}able events from that evaluation, that would produce "target loaded" and "target
   * configured" events in the build event proto output that consumers can confuse with actual
   * targets requested by the build.
   *
   * <p>This is important because downstream services (like a continuous integration tool or build
   * results dashboard) read these messages to reconcile which requested targets were built. If they
   * determine Blaze tried to build {@code //foo //bar} then see a "target configured" message for
   * some other target {@code //my_starlark_flag}, they might show misleading messages like "Built 3
   * of 2 requested targets.".
   *
   * <p>Hence this class. By dropping those events, we restrict all info and error reporting logic
   * to the options parsing pipeline.
   */
  private static class NonPostingEventHandler implements ExtendedEventHandler {
    private final ExtendedEventHandler delegate;

    NonPostingEventHandler(ExtendedEventHandler delegate) {
      this.delegate = delegate;
    }

    @Override
    public void handle(Event e) {
      delegate.handle(e);
    }

    @Override
    public void post(ExtendedEventHandler.Postable e) {}
  }

  /**
   * Lets {@link StarlarkOptionsParser} convert flag names to {@link Target}s through {@link
   * TargetPatternPhaseValue}.
   *
   * <p>This is used for top-level flag parsing, outside any {@link SkyFunction}.
   */
  public static class SkyframeExecutorTargetLoader
      implements StarlarkOptionsParser.BuildSettingLoader {
    private final SkyframeExecutor skyframeExecutor;
    private final PathFragment relativeWorkingDirectory;
    private final ExtendedEventHandler reporter;

    public SkyframeExecutorTargetLoader(CommandEnvironment env) {
      this.skyframeExecutor = env.getSkyframeExecutor();
      this.relativeWorkingDirectory = env.getRelativeWorkingDirectory();
      this.reporter = new NonPostingEventHandler(env.getReporter());
    }

    @VisibleForTesting
    public SkyframeExecutorTargetLoader(
        SkyframeExecutor skyframeExecutor,
        PathFragment relativeWorkingDirectory,
        ExtendedEventHandler reporter) {
      this.skyframeExecutor = skyframeExecutor;
      this.relativeWorkingDirectory = relativeWorkingDirectory;
      this.reporter = new NonPostingEventHandler(reporter);
    }

    @Override
    public Target loadBuildSetting(String targetLabel)
        throws InterruptedException, TargetParsingException {
      TargetPatternPhaseValue tpv =
          skyframeExecutor.loadTargetPatternsWithoutFilters(
              reporter,
              Collections.singletonList(targetLabel),
              relativeWorkingDirectory,
              SkyframeExecutor.DEFAULT_THREAD_COUNT,
              /* keepGoing= */ false);
      ImmutableSet<Target> result = tpv.getTargets(reporter, skyframeExecutor.getPackageManager());
      if (result.size() != 1) {
        throw new TargetParsingException(
            "user-defined flags must reference exactly one target",
            TargetPatterns.Code.TARGET_FORMAT_INVALID);
      }
      return Iterables.getOnlyElement(result);
    }
  }

  /**
   * TODO(bazel-team): When we move CoreOptions options to be defined in starlark, make sure they're
   * not passed in here during {@link #getOptionsResult}.
   */
  DetailedExitCode parseStarlarkOptions(CommandEnvironment env) {
    // For now, restrict starlark options to commands that already build to ensure that loading
    // will work. We may want to open this up to other commands in the future.
    if (!commandAnnotation.buildPhase().analyzes()) {
      return DetailedExitCode.success();
    }
    try {
      BuildSettingLoader buildSettingLoader = new SkyframeExecutorTargetLoader(env);
      StarlarkOptionsParser starlarkOptionsParser =
          StarlarkOptionsParser.builder()
              .buildSettingLoader(buildSettingLoader)
              .nativeOptionsParser(optionsParser)
              .build();
      Preconditions.checkState(starlarkOptionsParser.parse());
    } catch (OptionsParsingException e) {
      String logMessage = "Error parsing Starlark options";
      logger.atInfo().withCause(e).log("%s", logMessage);
      return processOptionsParsingException(
          env.getReporter(), e, logMessage, Code.STARLARK_OPTIONS_PARSE_FAILURE);
    } catch (InterruptedException e) {
      String message = "Interrupted while parsing Starlark options";
      env.getReporter().handle(Event.error(message));
      return InterruptedFailureDetails.detailedExitCode(message);
    }
    return DetailedExitCode.success();
  }

  /** Detailed parsing results: exit code and {@code --config=foo} definitions. */
  static record DetailedParseResults(
      DetailedExitCode detailedExitCode, ConfigFlagDefinitions configFlagDefinitions) {}

  /**
   * Parses the options, taking care not to generate any output to outErr, return, or throw an
   * exception.
   *
   * @return {@code DetailedExitCode.success()} if everything went well, or some other value if not
   */
  DetailedExitCode parseOptions(
      List<String> args,
      ExtendedEventHandler eventHandler,
      ImmutableList.Builder<OptionAndRawValue> invocationPolicyFlagListBuilder) {
    DetailedParseResults result =
        parseOptionsInternal(
            args, eventHandler, invocationPolicyFlagListBuilder, /* getConfigDefinitions= */ false);
    if (!result.detailedExitCode.isSuccess()) {
      optionsParser.setError();
    }
    return result.detailedExitCode;
  }

  /**
   * {@link #parseOptions} variation that also returns {@code --config=foo} definitions. Callers can
   * use this to determine which flags {@code --config=foo} sets.
   */
  DetailedParseResults parseOptionsAndGetConfigDefinitions(
      List<String> args,
      ExtendedEventHandler eventHandler,
      ImmutableList.Builder<OptionAndRawValue> invocationPolicyFlagListBuilder) {
    DetailedParseResults result =
        parseOptionsInternal(
            args, eventHandler, invocationPolicyFlagListBuilder, /* getConfigDefinitions= */ true);
    if (!result.detailedExitCode.isSuccess()) {
      optionsParser.setError();
    }
    return result;
  }

  private DetailedParseResults parseOptionsInternal(
      List<String> args,
      ExtendedEventHandler eventHandler,
      ImmutableList.Builder<OptionAndRawValue> invocationPolicyFlagListBuilder,
      boolean getConfigDefinitions) {
    // The initialization code here was carefully written to parse the options early before we call
    // into the BlazeModule APIs, which means we must not generate any output to outErr, return, or
    // throw an exception. All the events happening here are instead stored in a temporary event
    // handler, and later replayed.
    DetailedExitCode earlyExitCode = checkCwdInWorkspace(eventHandler);
    if (!earlyExitCode.isSuccess()) {
      return new DetailedParseResults(
          earlyExitCode, new ConfigFlagDefinitions(ImmutableListMultimap.of()));
    }

    ListMultimap<String, RcChunkOfArgs> rcDefinitions = ImmutableListMultimap.of();
    DetailedExitCode exitCode;
    try {
      rcDefinitions = parseArgsAndConfigs(args, eventHandler);
      // Allow the command to edit the options.
      command.editOptions(optionsParser);
      // Merge the invocation policy that is user-supplied, from the command line, and any
      // invocation policy that was added by a module. The module one goes 'first,' so the user
      // one has priority.
      InvocationPolicy combinedPolicy =
          InvocationPolicy.newBuilder()
              .mergeFrom(runtime.getModuleInvocationPolicy())
              .mergeFrom(invocationPolicy)
              .build();
      InvocationPolicyEnforcer optionsPolicyEnforcer =
          new InvocationPolicyEnforcer(
              combinedPolicy, Level.INFO, optionsParser.getConversionContext());
      // Enforce the invocation policy. It is intentional that this is the last step in preparing
      // the options. The invocation policy is used in security-critical contexts, and may be used
      // as a last resort to override flags. That means that the policy can override flags set in
      // BlazeCommand.editOptions, so the code needs to be safe regardless of the actual flag
      // values. At the time of this writing, editOptions was only used as a convenience feature or
      // to improve the user experience, but not required for safety or correctness.
      optionsPolicyEnforcer.enforce(
          optionsParser, commandAnnotation.name(), invocationPolicyFlagListBuilder);
      // Print warnings for odd options usage
      for (String warning : optionsParser.getWarnings()) {
        eventHandler.handle(Event.warn(warning));
      }
      CommonCommandOptions commonOptions = optionsParser.getOptions(CommonCommandOptions.class);
      for (String warning : commonOptions.deprecationWarnings) {
        eventHandler.handle(Event.warn(warning));
      }
      exitCode = DetailedExitCode.success();
    } catch (OptionsParsingException e) {
      String logMessage = "Error parsing options";
      logger.atInfo().withCause(e).log("%s", logMessage);
      exitCode =
          processOptionsParsingException(eventHandler, e, logMessage, Code.OPTIONS_PARSE_FAILURE);
    } catch (InterruptedException e) {
      exitCode =
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setInterrupted(
                      FailureDetails.Interrupted.newBuilder()
                          .setCode(FailureDetails.Interrupted.Code.INTERRUPTED))
                  .build());
    } catch (AbruptExitException e) {
      exitCode = e.getDetailedExitCode();
    }

    if (!getConfigDefinitions) {
      return new DetailedParseResults(
          exitCode, new ConfigFlagDefinitions(ImmutableListMultimap.of()));
    }
    // Transforms all rc definitions into valid --config definitions for this command. For example,
    // "build:asan" is valid for a build command but not "test:asan". Rc definitions like "build"
    // aren't included because those aren't --config definitions.
    var validConfigDefs = new ImmutableListMultimap.Builder<String, ConfigDefinition>();
    List<String> matchingRcCommands = getCommandNamesToParse(commandAnnotation);
    for (var entry : rcDefinitions.entries()) {
      String rcKey = entry.getKey();
      int firstColon = rcKey.indexOf(":");
      if (firstColon == -1) {
        continue;
      }
      String cmd = rcKey.substring(0, firstColon);
      String configName = rcKey.substring(firstColon + 1);
      if (matchingRcCommands.contains(cmd)) {
        validConfigDefs.put(
            configName,
            new ConfigFlagDefinitions.ConfigDefinition(
                ImmutableList.copyOf(entry.getValue().getArgs()), entry.getValue().getRcFile()));
      }
    }
    return new DetailedParseResults(exitCode, new ConfigFlagDefinitions(validConfigDefs.build()));
  }

  /**
   * Expand the values of --config according to the definitions provided in the rc files and the
   * applicable command.
   */
  @VisibleForTesting
  void expandConfigOptions(
      EventHandler eventHandler, ListMultimap<String, RcChunkOfArgs> commandToRcArgs)
      throws OptionsParsingException {
    ConfigExpander.expandConfigOptions(
        eventHandler,
        commandToRcArgs,
        commandAnnotation.name(),
        getCommandNamesToParse(commandAnnotation),
        rcfileNotes::add,
        optionsParser,
        OptionsParser.getFallbackOptionsData(allOptionsClasses));
  }

  private static List<String> getCommandNamesToParse(Command commandAnnotation) {
    List<String> result = new ArrayList<>();
    result.add(ALWAYS_PSEUDO_COMMAND);
    result.add(COMMON_PSEUDO_COMMAND);
    getCommandNamesToParseHelper(commandAnnotation, result);
    return result;
  }

  private static void getCommandNamesToParseHelper(
      Command commandAnnotation, List<String> accumulator) {
    for (Class<? extends BlazeCommand> base : commandAnnotation.inheritsOptionsFrom()) {
      getCommandNamesToParseHelper(base.getAnnotation(Command.class), accumulator);
    }
    accumulator.add(commandAnnotation.name());
  }

  private static DetailedExitCode processOptionsParsingException(
      ExtendedEventHandler eventHandler,
      OptionsParsingException e,
      String logMessage,
      Code failureCode) {
    Event error;
    // Differentiates errors stemming from an invalid argument and errors from different parts of
    // the codebase.
    if (e.getInvalidArgument() != null) {
      error =
          Event.error(e.getInvalidArgument() + ERROR_SEPARATOR + e.getMessage())
              .withTag(BAD_OPTION_TAG);
    } else {
      error = Event.error(e.getMessage());
    }
    eventHandler.handle(error);
    return createDetailedExitCode(logMessage + ": " + e.getMessage(), failureCode);
  }

  private String getNotInRealWorkspaceError(Path doNotBuildFile) {
    String message =
        String.format(
            "%1$s should not be called from a %1$s output directory. ", runtime.getProductName());
    try {
      String realWorkspace = new String(FileSystemUtils.readContentAsLatin1(doNotBuildFile));
      message += String.format("The pertinent workspace directory is: '%s'", realWorkspace);
    } catch (IOException e) {
      // We are exiting anyway.
    }

    return message;
  }

  /**
   * The rc options are passed via {@link ClientOptions#optionsOverrides} and {@link
   * ClientOptions#rcSource}, which is basically a line-by-line transfer of the rc files read by the
   * client. This is not a particularly useful format for expanding the options, so this method
   * structures the list so that it is easier to find the arguments that apply to a command, or to
   * find the definitions of a config value.
   */
  @VisibleForTesting
  static ListMultimap<String, RcChunkOfArgs> structureRcOptionsAndConfigs(
      EventHandler eventHandler,
      List<String> rcFiles,
      List<ClientOptions.OptionOverride> rawOverrides,
      Set<String> validCommands)
      throws OptionsParsingException {
    ListMultimap<String, RcChunkOfArgs> commandToRcArgs = ArrayListMultimap.create();

    String lastRcFile = null;
    ListMultimap<String, String> commandToArgMapForLastRc = null;
    for (ClientOptions.OptionOverride override : rawOverrides) {
      if (override.blazeRc < 0 || override.blazeRc >= rcFiles.size()) {
        eventHandler.handle(
            Event.warn("inconsistency in generated command line args. Ignoring bogus argument\n"));
        continue;
      }
      String rcFile = rcFiles.get(override.blazeRc);
      // The canonicalize-flags command only inherits bazelrc "build" commands. Not "test", not
      // "build:foo". Restrict --flag_alias accordingly to prevent building with flags that
      // canonicalize-flags can't recognize.
      if ((override.option.startsWith("--" + Converters.BLAZE_ALIASING_FLAG + "=")
              || override.option.equals("--" + Converters.BLAZE_ALIASING_FLAG))
          && !BUILD_COMMAND_ANCESTORS.contains(override.command)) {
        throw new OptionsParsingException(
            String.format(
                "%s: \"%s %s\" disallowed. --%s only supports these commands: %s",
                rcFile,
                override.command,
                override.option,
                Converters.BLAZE_ALIASING_FLAG,
                String.join(", ", BUILD_COMMAND_ANCESTORS)));
      }
      String command = override.command;
      int index = command.indexOf(':');
      if (index > 0) {
        command = command.substring(0, index);
      }
      if (!validCommands.contains(command)
          && !command.equals(ALWAYS_PSEUDO_COMMAND)
          && !command.equals(COMMON_PSEUDO_COMMAND)) {
        eventHandler.handle(
            Event.warn(
                "while reading option defaults file '"
                    + rcFile
                    + "':\n"
                    + "  invalid command name '"
                    + override.command
                    + "'."));
        continue;
      }

      // We've moved on to another rc file "chunk," store the accumulated args from the last one.
      if (!rcFile.equals(lastRcFile)) {
        if (lastRcFile != null) {
          // Go through the various commands identified in this rc file (or chunk of file) and
          // store them grouped first by command, then by rc chunk.
          for (String commandKey : commandToArgMapForLastRc.keySet()) {
            commandToRcArgs.put(
                commandKey,
                new RcChunkOfArgs(lastRcFile, commandToArgMapForLastRc.get(commandKey)));
          }
        }
        lastRcFile = rcFile;
        commandToArgMapForLastRc = ArrayListMultimap.create();
      }

      commandToArgMapForLastRc.put(override.command, override.option);
    }
    if (lastRcFile != null) {
      // Once again, for this last rc file chunk, store them grouped by command.
      for (String commandKey : commandToArgMapForLastRc.keySet()) {
        commandToRcArgs.put(
            commandKey, new RcChunkOfArgs(lastRcFile, commandToArgMapForLastRc.get(commandKey)));
      }
    }

    return commandToRcArgs;
  }

  private static DetailedExitCode createDetailedExitCode(String message, Code detailedCode) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setCommand(FailureDetails.Command.newBuilder().setCode(detailedCode))
            .build());
  }

  private static void partitionCommandLineArgs(
      List<String> cmdLine,
      ImmutableList.Builder<String> defaultOverridesAndRcSources,
      ImmutableList.Builder<String> remainingCmdLine) {

    Iterator<String> cmdLineIterator = cmdLine.iterator();

    while (cmdLineIterator.hasNext()) {
      String option = cmdLineIterator.next();
      if (option.startsWith("--rc_source=") || option.startsWith("--default_override=")) {
        defaultOverridesAndRcSources.add(option);
      } else if (option.equals("--rc_source") || option.equals("--default_override")) {
        Optional<String> possibleArgument =
            cmdLineIterator.hasNext() ? Optional.of(cmdLineIterator.next()) : Optional.empty();
        defaultOverridesAndRcSources.add(option);
        if (possibleArgument.isPresent()) {
          defaultOverridesAndRcSources.add(possibleArgument.get());
        }
      } else {
        remainingCmdLine.add(option);
      }
    }
  }
}
