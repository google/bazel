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

import static com.google.devtools.build.lib.runtime.BlazeOptionHandler.BAD_OPTION_TAG;
import static com.google.devtools.build.lib.runtime.BlazeOptionHandler.ERROR_SEPARATOR;
import static com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie;
import static com.google.devtools.common.options.Converters.BLAZE_ALIASING_FLAG;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.Flushables;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.bugreport.Crash;
import com.google.devtools.build.lib.bugreport.CrashContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildtool.buildevent.MainRepoMappingComputationStartingEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ProfilerStartedEvent;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.profiler.MemoryProfiler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.BlazeOptionHandler.DetailedParseResults;
import com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.DestinationRelativeTo;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.AnsiStrippingOutputStream;
import com.google.devtools.build.lib.util.DebugLoggerConfigurator;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.util.LoggingUtil;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.CommandExtensionReporter;
import com.google.devtools.build.lib.util.io.DelegatingOutErr;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.OpaqueOptionsData;
import com.google.devtools.common.options.OptionAndRawValue;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.devtools.common.options.TriState;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.Any;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import javax.annotation.Nullable;
import net.starlark.java.eval.Starlark;

/**
 * Dispatches to the Blaze commands; that is, given a command line, this abstraction looks up the
 * appropriate command object, parses the options required by the object, and calls its exec method.
 * Also, this object provides the runtime state (BlazeRuntime) to the commands.
 */
public class BlazeCommandDispatcher implements CommandDispatcher {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  public static final int UNKNOWN_SERVER_PID = -1;

  private static final ImmutableList<String> HELP_COMMAND = ImmutableList.of("help");

  private static final ImmutableSet<String> ALL_HELP_OPTIONS =
      ImmutableSet.of("--help", "-help", "-h");

  private final BlazeRuntime runtime;
  private final int serverPid;
  private final BugReporter bugReporter;
  private final Object commandLock;
  private String currentClientDescription = null;
  private final AtomicReference<String> shutdownReason = new AtomicReference<>();
  private OutputStream logOutputStream = null;
  private final LoadingCache<BlazeCommand, OpaqueOptionsData> optionsDataCache =
      Caffeine.newBuilder()
          .build(
              new CacheLoader<BlazeCommand, OpaqueOptionsData>() {
                @Override
                public OpaqueOptionsData load(BlazeCommand command) {
                  return OptionsParser.getOptionsData(
                      BlazeCommandUtils.getOptions(
                          command.getClass(),
                          runtime.getBlazeModules(),
                          runtime.getRuleClassProvider()));
                }
              });

  BlazeCommandDispatcher(BlazeRuntime runtime, int serverPid) {
    this(runtime, serverPid, runtime.getBugReporter());
  }

  /** Convenience test-only constructor. */
  @VisibleForTesting
  public BlazeCommandDispatcher(BlazeRuntime runtime) {
    this(runtime, UNKNOWN_SERVER_PID, runtime.getBugReporter());
  }

  /** Convenience test-only constructor. */
  @VisibleForTesting
  BlazeCommandDispatcher(BlazeRuntime runtime, BugReporter bugReporter) {
    this(runtime, UNKNOWN_SERVER_PID, bugReporter);
  }

  private BlazeCommandDispatcher(BlazeRuntime runtime, int serverPid, BugReporter bugReporter) {
    this.runtime = runtime;
    this.serverPid = serverPid;
    this.bugReporter = bugReporter;
    this.commandLock = new Object();
  }

  @Override
  @CanIgnoreReturnValue
  public BlazeCommandResult exec(
      InvocationPolicy invocationPolicy,
      List<String> args,
      OutErr outErr,
      LockingMode lockingMode,
      UiVerbosity uiVerbosity,
      String clientDescription,
      long firstContactTimeMillis,
      Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
      List<Any> commandExtensions,
      CommandExtensionReporter commandExtensionReporter)
      throws InterruptedException {
    Preconditions.checkNotNull(clientDescription);
    if (args.isEmpty()) { // Default to help command if no arguments specified.
      args = HELP_COMMAND;
    }

    String commandName = args.get(0);

    // Be gentle to users who want to find out about Blaze invocation.
    if (ALL_HELP_OPTIONS.contains(commandName)) {
      commandName = "help";
    }

    BlazeCommand command = runtime.getCommandMap().get(commandName);
    if (command == null) {
      outErr.printErrLn(
          String.format(
              "Command '%s' not found. Try '%s help'.", commandName, runtime.getProductName()));
      return createDetailedCommandResult(
          String.format("Command '%s' not found.", commandName),
          FailureDetails.Command.Code.COMMAND_NOT_FOUND);
    }

    // Take the exclusive server lock.  If we fail, we busy-wait until the lock becomes available.
    //
    // We used to rely on commandLock.wait() to lazy-wait for the lock to become available, which is
    // theoretically fine, but doing so prevents us from determining if the PID of the server
    // holding the lock has changed under the hood.  There have been multiple bug reports where
    // users (especially macOS ones) mention that the Blaze invocation hangs on a non-existent PID.
    // This should help troubleshoot those scenarios in case there really is a bug somewhere.
    boolean multipleAttempts = false;
    long clockBefore = BlazeClock.nanoTime();
    String otherClientDescription = "";
    // TODO(ulfjack): Add lock acquisition to the profiler.
    synchronized (commandLock) {
      while (currentClientDescription != null) {
        switch (lockingMode) {
          case WAIT:
            if (!otherClientDescription.equals(currentClientDescription)) {
              String serverDescription =
                  serverPid == UNKNOWN_SERVER_PID ? "" : (" (server_pid=" + serverPid + ")");
              outErr.printErrLn(
                  String.format(
                      "Another command (%s) is running. Waiting for it to complete on the"
                          + " server%s...",
                      currentClientDescription, serverDescription));
              otherClientDescription = currentClientDescription;
            }
            commandLock.wait(500);
            break;

          case ERROR_OUT:
            String message =
                String.format(
                    "Another command (%s) is running. Exiting immediately.",
                    currentClientDescription);
            outErr.printErrLn(message);
            return createDetailedCommandResult(
                message, FailureDetails.Command.Code.ANOTHER_COMMAND_RUNNING);

          default:
            throw new IllegalStateException();
        }

        multipleAttempts = true;
      }
      currentClientDescription = clientDescription;
    }
    // If we took the lock on the first try, force the reported wait time to 0 to avoid unnecessary
    // noise in the logs.  In this metric, we are only interested in knowing how long it took for
    // other commands to complete, not how fast acquiring a lock is.
    long waitTimeInMs =
        !multipleAttempts ? 0 : (BlazeClock.nanoTime() - clockBefore) / (1000L * 1000L);

    try {
      String retrievedShutdownReason = this.shutdownReason.get();
      if (retrievedShutdownReason != null) {
        outErr.printErrLn(retrievedShutdownReason);
        return createDetailedCommandResult(
            retrievedShutdownReason, FailureDetails.Command.Code.PREVIOUSLY_SHUTDOWN);
      }
      BlazeCommandResult result;
      int attemptNumber = 0;
      Set<UUID> attemptedCommandIds = new HashSet<>();
      BlazeCommandResult lastResult = null;
      while (true) {
        attemptNumber += 1;
        try {
          result =
              execExclusively(
                  invocationPolicy,
                  args,
                  outErr,
                  uiVerbosity == UiVerbosity.QUIET,
                  firstContactTimeMillis,
                  commandName,
                  command,
                  waitTimeInMs,
                  startupOptionsTaggedWithBazelRc,
                  commandExtensions,
                  attemptNumber,
                  attemptedCommandIds,
                  lastResult,
                  commandExtensionReporter);
          break;
        } catch (RemoteCacheTransientErrorException e) {
          attemptedCommandIds.add(e.getCommandId());
          lastResult = e.getResult();
        }
      }
      if (result.shutdown()) {
        setShutdownReason(
            "Server shut down "
                + (result.getExitCode().isInfrastructureFailure()
                    ? "due to a crash: " + result.getFailureDetail().getMessage()
                    : "explicitly by client " + clientDescription));
      }
      if (!result.getDetailedExitCode().isSuccess()) {
        logger.atInfo().log("Exit status was %s", result.getDetailedExitCode());
      }
      return result;
    } finally {
      synchronized (commandLock) {
        currentClientDescription = null;
        commandLock.notify();
      }
    }
  }

  /**
   * For testing ONLY. Same as {@link CommandDispatcher#exec(InvocationPolicy, List, OutErr,
   * LockingMode, String, long, Optional, List, CommandExtensionReporter)} but automatically uses
   * the current time.
   */
  @VisibleForTesting
  public BlazeCommandResult exec(List<String> args, String clientDescription, OutErr originalOutErr)
      throws InterruptedException {
    return exec(
        InvocationPolicy.getDefaultInstance(),
        args,
        originalOutErr,
        LockingMode.ERROR_OUT,
        UiVerbosity.NORMAL,
        clientDescription,
        runtime.getClock().currentTimeMillis(),
        /* startupOptionsTaggedWithBazelRc= */ Optional.empty(),
        /* commandExtensions= */ ImmutableList.of(),
        /* commandExtensionReporter= */ (ext) -> {});
  }

  private BlazeCommandResult execExclusively(
      InvocationPolicy invocationPolicy,
      List<String> args,
      OutErr outErr,
      boolean quiet,
      long firstContactTime,
      String commandName,
      BlazeCommand command,
      long waitTimeInMs,
      Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
      List<Any> commandExtensions,
      int attemptNumber,
      Set<UUID> attemptedCommandIds,
      @Nullable BlazeCommandResult lastResult,
      CommandExtensionReporter commandExtensionReporter)
      throws RemoteCacheTransientErrorException {
    // Record the start time for the profiler. Do not put anything before this!
    long execStartTimeNanos = runtime.getClock().nanoTime();

    Command commandAnnotation = command.getClass().getAnnotation(Command.class);
    BlazeWorkspace workspace = runtime.getWorkspace();

    StoredEventHandler storedEventHandler = new StoredEventHandler();
    // Provide the options parser so that we can cache OptionsData here.
    OptionsParser optionsParser = createOptionsParser(command);
    BlazeOptionHandler optionHandler =
        new BlazeOptionHandler(
            runtime, workspace, command, commandAnnotation, optionsParser, invocationPolicy);
    DetailedParseResults parseResults =
        optionHandler.parseOptionsAndGetConfigDefinitions(
            args,
            storedEventHandler,
            /* invocationPolicyFlagListBuilder= */ ImmutableList.builder());
    DetailedExitCode earlyExitCode = parseResults.detailedExitCode();
    OptionsParsingResult options = optionHandler.getOptionsResult();

    // The initCommand call also records the start time for the timestamp granularity monitor.
    List<String> commandEnvWarnings = new ArrayList<>();
    CommandEnvironment env =
        workspace.initCommand(
            commandAnnotation,
            options,
            invocationPolicy,
            commandEnvWarnings,
            waitTimeInMs,
            firstContactTime,
            commandExtensions,
            this::setShutdownReason,
            commandExtensionReporter,
            attemptNumber,
            parseResults.configFlagDefinitions());

    if (!attemptedCommandIds.isEmpty()) {
      if (attemptedCommandIds.contains(env.getCommandId())) {
        outErr.printErrLn(
            String.format(
                "Failed to retry the build: invocation id `%s` has already been used.",
                env.getCommandId()));
        return Preconditions.checkNotNull(lastResult);
      } else {
        outErr.printErrLn("Found transient remote cache error, retrying the build...");
      }
    }

    CommonCommandOptions commonOptions = options.getOptions(CommonCommandOptions.class);
    boolean tracerEnabled = false;
    if (commonOptions.enableTracer == TriState.YES) {
      tracerEnabled = true;
    } else if (commonOptions.enableTracer == TriState.AUTO) {
      boolean commandSupportsProfile =
          commandName.equals("query") || commandAnnotation.buildPhase().analyzes();
      tracerEnabled = commandSupportsProfile || commonOptions.profilePath != null;
    }

    // TODO(ulfjack): Move the profiler initialization as early in the startup sequence as possible.
    // Profiler setup and shutdown must always happen in pairs. Shutdown is currently performed in
    // the afterCommand call in the finally block below.
    ProfilerStartedEvent profilerStartedEvent =
        runtime.initProfiler(
            tracerEnabled,
            storedEventHandler,
            workspace,
            options,
            env,
            execStartTimeNanos,
            waitTimeInMs);
    storedEventHandler.post(profilerStartedEvent);

    // Enable Starlark CPU profiling (--starlark_cpu_profile=/tmp/foo.pprof.gz)
    boolean success = false;
    if (!commonOptions.starlarkCpuProfile.isEmpty()) {
      OutputStream out;
      try {
        InstrumentationOutput starlarkCpuProfile =
            runtime
                .getInstrumentationOutputFactory()
                .createInstrumentationOutput(
                    /* name= */ "starlarkCpuProfile",
                    PathFragment.create(commonOptions.starlarkCpuProfile),
                    DestinationRelativeTo.WORKING_DIRECTORY_OR_HOME,
                    env,
                    storedEventHandler,
                    /* append= */ null,
                    /* internal= */ null);
        out = starlarkCpuProfile.createOutputStream();
      } catch (IOException ex) {
        String message = "Starlark CPU profiler: " + ex.getMessage();
        outErr.printErrLn(message);
        return createDetailedCommandResult(
            message, FailureDetails.Command.Code.STARLARK_CPU_PROFILE_FILE_INITIALIZATION_FAILURE);
      }
      try {
        success = Starlark.startCpuProfile(out, Duration.ofMillis(10));
      } catch (IllegalStateException ex) { // e.g. SIGPROF in use
        String message = Strings.nullToEmpty(ex.getMessage());
        outErr.printErrLn(message);
        return createDetailedCommandResult(
            message, FailureDetails.Command.Code.STARLARK_CPU_PROFILING_INITIALIZATION_FAILURE);
      }
    }

    BlazeCommandResult result =
        createDetailedCommandResult(
            "Unknown command failure", FailureDetails.Command.Code.COMMAND_FAILURE_UNKNOWN);
    boolean needToCallAfterCommand = true;
    Reporter reporter = env.getReporter();
    OutErr.SystemPatcher systemOutErrPatcher = reporter.getOutErr().getSystemPatcher();
    try {
      // Both the call to env.decideKeepIncrementalState() and module.beforeCommand() may emit
      // events, but the reporter isn't setup yet. Use a stored event handler to catch those events.
      reporter.addHandler(storedEventHandler);
      env.decideKeepIncrementalState();
      for (BlazeModule module : runtime.getBlazeModules()) {
        try (SilentCloseable closeable = Profiler.instance().profile(module + ".beforeCommand")) {
          module.beforeCommand(env);
        } catch (AbruptExitException e) {
          logger.atInfo().withCause(e).log("Error in %s", module);
          // Don't let one module's complaints prevent the other modules from doing necessary
          // setup. We promised to call beforeCommand exactly once per-module before each command
          // and will be calling afterCommand soon in the future - a module's afterCommand might
          // rightfully assume its beforeCommand has already been called.
          storedEventHandler.handle(Event.error(e.getMessage()));

          // Use the highest priority exit code, or the first one that is encountered if all exit
          // codes have equivalent priority.
          earlyExitCode = chooseMoreImportantWithFirstIfTie(earlyExitCode, e.getDetailedExitCode());
        }
      }
      reporter.removeHandler(storedEventHandler);

      // Setup stdout / stderr.
      outErr = tee(outErr, env.getOutputListeners());

      // Early exit. We need to guarantee that the ErrOut and Reporter setup below never error out,
      // so any invariants they need must be checked before this point.
      if (!earlyExitCode.isSuccess()) {
        replayEarlyExitEvents(
            outErr,
            optionHandler,
            storedEventHandler,
            env,
            new NoBuildEvent(
                commandName, firstContactTime, false, true, env.getCommandId().toString()));
        result = BlazeCommandResult.detailedExitCode(earlyExitCode);
        return result;
      }

      try (SilentCloseable closeable = Profiler.instance().profile("setup event handler")) {
        UiOptions eventHandlerOptions = options.getOptions(UiOptions.class);
        OutErr colorfulOutErr = outErr;

        if (!eventHandlerOptions.useColor()) {
          if (!commandAnnotation.binaryStdOut()) {
            outErr = ansiStripOut(outErr);
            colorfulOutErr = ansiStripOut(colorfulOutErr);
          }
          if (!commandAnnotation.binaryStdErr()) {
            outErr = ansiStripErr(outErr);
            colorfulOutErr = ansiStripErr(colorfulOutErr);
          }
        }

        if (!commandAnnotation.binaryStdOut()) {
          outErr = bufferOut(outErr);
        }

        if (!commandAnnotation.binaryStdErr()) {
          outErr = bufferErr(outErr);
        }

        DebugLoggerConfigurator.setupLogging(commonOptions.verbosity);

        boolean newStatsSummary =
            options.getOptions(ExecutionOptions.class) != null
                && options.getOptions(ExecutionOptions.class).statsSummary;
        EventHandler handler =
            createEventHandler(outErr, eventHandlerOptions, quiet, env, newStatsSummary);
        reporter.addHandler(handler);
        env.getEventBus().register(handler);

        // We register an ANSI-allowing handler associated with {@code handler} so that ANSI control
        // codes can be re-introduced later even if blaze is invoked with --color=no. This is useful
        // for commands such as 'blaze run' where the output of the final executable shouldn't be
        // modified.
        if (!eventHandlerOptions.useColor()) {
          UiEventHandler ansiAllowingHandler =
              createEventHandler(colorfulOutErr, eventHandlerOptions, quiet, env, newStatsSummary);
          reporter.registerAnsiAllowingHandler(handler, ansiAllowingHandler);
          env.getEventBus().register(new PassiveExperimentalEventHandler(ansiAllowingHandler));
        }
      }

      try (SilentCloseable closeable = Profiler.instance().profile("replay stored events")) {
        // Now we're ready to replay the events.
        storedEventHandler.replayOn(reporter);
        for (String warning : commandEnvWarnings) {
          reporter.handle(Event.warn(warning));
        }
      }

      try (SilentCloseable closeable = Profiler.instance().profile("announce rc options")) {
        if (commonOptions.announceRcOptions) {
          if (startupOptionsTaggedWithBazelRc.isPresent()) {
            String lastBlazerc = "";
            List<String> accumulatedStartupOptions = new ArrayList<>();
            for (Pair<String, String> option : startupOptionsTaggedWithBazelRc.get()) {
              // Do not include the command line options, marked by the empty string.
              if (option.getFirst().isEmpty()) {
                continue;
              }

              // If we've moved to a new blazerc in the list, print out the info from the last one,
              // and clear the accumulated list.
              if (!lastBlazerc.isEmpty() && !option.getFirst().equals(lastBlazerc)) {
                String logMessage =
                    String.format(
                        "Reading 'startup' options from %s: %s",
                        lastBlazerc, String.join(", ", accumulatedStartupOptions));
                reporter.handle(Event.info(logMessage));
                accumulatedStartupOptions = new ArrayList<>();
              }

              lastBlazerc = option.getFirst();
              accumulatedStartupOptions.add(option.getSecond());
            }
            // Print out the final blazerc-grouped list, if any startup options were provided by
            // blazerc.
            if (!lastBlazerc.isEmpty()) {
              String logMessage =
                  String.format(
                      "Reading 'startup' options from %s: %s",
                      lastBlazerc, String.join(", ", accumulatedStartupOptions));
              reporter.handle(Event.info(logMessage));
            }
          }
          for (String note : optionHandler.getRcfileNotes()) {
            reporter.handle(Event.info(note));
          }
        }
      }

      // While a Blaze command is active, direct all errors to the client's event handler (and
      // out/err streams).
      systemOutErrPatcher.start();

      try (SilentCloseable closeable = Profiler.instance().profile("CommandEnv.beforeCommand")) {
        // Notify the BlazeRuntime, so it can do some initial setup.
        env.beforeCommand(invocationPolicy);
      } catch (AbruptExitException e) {
        logger.atInfo().withCause(e).log("Error before command");
        reporter.handle(Event.error(e.getMessage()));
        result = BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
        return result;
      }

      for (BlazeModule module : runtime.getBlazeModules()) {
        try (SilentCloseable closeable =
            Profiler.instance().profile(module + ".injectExtraPrecomputedValues")) {
          env.getSkyframeExecutor().injectExtraPrecomputedValues(module.getPrecomputedValues());
        }
      }

      if (env.getCommand().buildPhase().analyzes()) {
        try {
          env.syncPackageLoading(options);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          String message = "command interrupted while syncing package loading";
          reporter.handle(Event.error(message));
          earlyExitCode = InterruptedFailureDetails.detailedExitCode(message);
        } catch (AbruptExitException e) {
          logger.atInfo().withCause(e).log("Error package loading");
          reporter.handle(Event.error(e.getMessage()));
          earlyExitCode = e.getDetailedExitCode();
        }
        if (!earlyExitCode.isSuccess()) {
          reporter.post(
              new NoBuildEvent(
                  commandName, firstContactTime, false, true, env.getCommandId().toString()));
          result = BlazeCommandResult.detailedExitCode(earlyExitCode);
          return result;
        }

        // Compute the repo mapping of the main repo and re-parse options so that we get correct
        // values for label-typed options.
        env.getEventBus().post(new MainRepoMappingComputationStartingEvent());
        try (SilentCloseable c =
            Profiler.instance().profile(ProfilerTask.BZLMOD, "compute main repo mapping")) {
          RepositoryMapping mainRepoMapping =
              env.getSkyframeExecutor().getMainRepoMapping(reporter);
          optionsParser = optionsParser.toBuilder().withConversionContext(mainRepoMapping).build();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          String message = "command interrupted while computing main repo mapping";
          reporter.handle(Event.error(message));
          earlyExitCode = InterruptedFailureDetails.detailedExitCode(message);
        } catch (RepositoryMappingResolutionException e) {
          logger.atInfo().withCause(e).log("Error computing main repo mapping");
          reporter.handle(Event.error(e.getMessage()));
          earlyExitCode = e.getDetailedExitCode();
        }
        if (!earlyExitCode.isSuccess()) {
          reporter.post(
              new NoBuildEvent(
                  commandName, firstContactTime, false, true, env.getCommandId().toString()));
          result = BlazeCommandResult.detailedExitCode(earlyExitCode);
          return result;
        }
        try (SilentCloseable c =
            Profiler.instance()
                .profile(ProfilerTask.BZLMOD, "reparse options with main repo mapping")) {
          optionHandler =
              new BlazeOptionHandler(
                  runtime, workspace, command, commandAnnotation, optionsParser, invocationPolicy);
          ImmutableList.Builder<OptionAndRawValue> invocationPolicyFlagListBuilder =
              ImmutableList.builder();
          // Do not handle any events since this is the second time we parse the options.
          earlyExitCode =
              optionHandler.parseOptions(
                  args, ExtendedEventHandler.NOOP, invocationPolicyFlagListBuilder);
          env.setInvocationPolicyFlags(invocationPolicyFlagListBuilder.build());
        }
        if (!earlyExitCode.isSuccess()) {
          reporter.post(
              new NoBuildEvent(
                  commandName, firstContactTime, false, true, env.getCommandId().toString()));
          result = BlazeCommandResult.detailedExitCode(earlyExitCode);
          return result;
        }
      }

      // Parse starlark options.
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.BZLMOD, "parse starlark options")) {
        earlyExitCode = optionHandler.parseStarlarkOptions(env);
      }
      if (!earlyExitCode.isSuccess()) {
        reporter.post(
            new NoBuildEvent(
                commandName, firstContactTime, false, true, env.getCommandId().toString()));
        result = BlazeCommandResult.detailedExitCode(earlyExitCode);
        return result;
      }
      options = optionHandler.getOptionsResult();

      // Log the command line now that the modules have all had a change to register their listeners
      // to the event bus, and the flags have been re-parsed.
      CommandLineEvent originalCommandLineEvent =
          new CommandLineEvent.OriginalCommandLineEvent(
              runtime, commandName, options, startupOptionsTaggedWithBazelRc);
      // If flagsets are applied, a CanonicalCommandLineEvent is also emitted by
      // BuildTool.buildTargets(). This is a duplicate event, and consumers are expected to
      // handle it correctly, by accepting the last event.
      CommandLineEvent canonicalCommandLineEvent =
          new CommandLineEvent.CanonicalCommandLineEvent(runtime, commandName, options);
      BuildEventProtocolOptions bepOptions =
          env.getOptions().getOptions(BuildEventProtocolOptions.class);
      OriginalUnstructuredCommandLineEvent unstructuredServerCommandLineEvent;
      if (commandName.equals("run") && !bepOptions.includeResidueInRunBepEvent) {
        unstructuredServerCommandLineEvent =
            OriginalUnstructuredCommandLineEvent.REDACTED_UNSTRUCTURED_COMMAND_LINE_EVENT;
      } else {
        unstructuredServerCommandLineEvent = new OriginalUnstructuredCommandLineEvent(args);
      }
      env.getEventBus().post(unstructuredServerCommandLineEvent);
      env.getEventBus().post(originalCommandLineEvent);
      env.getEventBus().post(canonicalCommandLineEvent);
      env.getEventBus().post(commonOptions.toolCommandLine);

      // Run the command.
      result = command.exec(env, options);

      DetailedExitCode moduleExitCode = env.precompleteCommand(result.getDetailedExitCode());
      // If Blaze did not suffer an infrastructure failure, check for errors in modules.
      if (!result.getExitCode().isInfrastructureFailure() && moduleExitCode != null) {
        result = BlazeCommandResult.detailedExitCode(moduleExitCode);
      }

      // Finalize the Starlark CPU profile.
      if (!commonOptions.starlarkCpuProfile.isEmpty() && success) {
        try {
          Starlark.stopCpuProfile();
        } catch (IOException ex) {
          String message = "Starlark CPU profiler: " + ex.getMessage();
          reporter.handle(Event.error(message));
          if (result.getDetailedExitCode().isSuccess()) { // don't clobber existing error
            result =
                createDetailedCommandResult(
                    message, FailureDetails.Command.Code.STARLARK_CPU_PROFILE_FILE_WRITE_FAILURE);
          }
        }
      }

      needToCallAfterCommand = false;
      var newResult = runtime.afterCommand(/* forceKeepStateForTesting= */ false, env, result);
      if (newResult.getExitCode().equals(ExitCode.REMOTE_CACHE_EVICTED)) {
        var executionOptions =
            Preconditions.checkNotNull(options.getOptions(ExecutionOptions.class));
        if (attemptedCommandIds.size() < executionOptions.remoteRetryOnTransientCacheError) {
          throw new RemoteCacheTransientErrorException(env.getCommandId(), newResult);
        }
      }

      return newResult;
    } catch (RemoteCacheTransientErrorException e) {
      throw e;
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log("Shutting down due to exception");
      Crash crash = Crash.from(e);
      bugReporter.handleCrash(crash, CrashContext.keepAlive().withArgs(args));
      needToCallAfterCommand = false; // We are crashing.
      result = BlazeCommandResult.createShutdown(crash);
      return result;
    } finally {
      try {
        // Profiler might still be running when an exception is thrown before BuildCompleteEvent is
        // emitted or BlazeModule#completeCommand() is called. So we still need to try to stop the
        // profiler here.
        Profiler.instance().stop();
        if (profilerStartedEvent.getProfile() instanceof LocalInstrumentationOutput profile) {
          profile.makeConvenienceLink();
        }
      } catch (IOException e) {
        env.getReporter()
            .handle(Event.error("Error while writing profile file: " + e.getMessage()));
      }
      Profiler.instance().clear();

      if (needToCallAfterCommand) {
        BlazeCommandResult newResult = runtime.afterCommand(false, env, result);
        if (!newResult.equals(result)) {
          logger.atWarning().log("afterCommand yielded different result: %s %s", result, newResult);
        }
      }

      MemoryProfiler.instance().stop();

      // Swallow IOException, as we are already in a finally clause
      Flushables.flushQuietly(outErr.getOutputStream());
      Flushables.flushQuietly(outErr.getErrorStream());

      systemOutErrPatcher.close();

      env.getTimestampGranularityMonitor().waitForTimestampGranularity(outErr);
    }
  }

  private static class RemoteCacheTransientErrorException extends IOException {
    private final UUID commandId;
    private final BlazeCommandResult result;

    private RemoteCacheTransientErrorException(UUID commandId, BlazeCommandResult result) {
      this.commandId = commandId;
      this.result = result;
    }

    public UUID getCommandId() {
      return commandId;
    }

    public BlazeCommandResult getResult() {
      return result;
    }
  }

  private static void replayEarlyExitEvents(
      OutErr outErr,
      BlazeOptionHandler optionHandler,
      StoredEventHandler storedEventHandler,
      CommandEnvironment env,
      NoBuildEvent noBuildEvent) {
    PrintingEventHandler printingEventHandler =
        new PrintingEventHandler(outErr, EventKind.ALL_EVENTS);

    Optional<String> badOption = retrieveBadOption(storedEventHandler.getEvents());

    for (String note : optionHandler.getRcfileNotes()) {
      if (badOption.isPresent()) {
        if (note.contains(badOption.get())) {
          printingEventHandler.handle(Event.info(note));
        }
      }
    }
    for (Event event : storedEventHandler.getEvents()) {
      printingEventHandler.handle(event);
    }
    for (Postable post : storedEventHandler.getPosts()) {
      env.getEventBus().post(post);
    }
    env.getEventBus().post(noBuildEvent);
  }

  private static Optional<String> retrieveBadOption(ImmutableList<Event> events) {
    return events.stream()
        .filter(e -> e.getTag() != null && e.getTag().equals(BAD_OPTION_TAG))
        .map(Event::getMessage)
        .filter(message -> message.contains(ERROR_SEPARATOR))
        .map(message -> message.substring(0, message.indexOf(ERROR_SEPARATOR)))
        .findFirst();
  }

  private OutErr bufferOut(OutErr outErr) {
    OutputStream wrappedOut = new BufferedOutputStream(outErr.getOutputStream());
    return OutErr.create(wrappedOut, outErr.getErrorStream());
  }

  private OutErr bufferErr(OutErr outErr) {
    OutputStream wrappedErr = new BufferedOutputStream(outErr.getErrorStream());
    return OutErr.create(outErr.getOutputStream(), wrappedErr);
  }

  private OutErr ansiStripOut(OutErr outErr) {
    OutputStream wrappedOut = new AnsiStrippingOutputStream(outErr.getOutputStream());
    return OutErr.create(wrappedOut, outErr.getErrorStream());
  }

  private OutErr ansiStripErr(OutErr outErr) {
    OutputStream wrappedErr = new AnsiStrippingOutputStream(outErr.getErrorStream());
    return OutErr.create(outErr.getOutputStream(), wrappedErr);
  }

  private OutErr tee(OutErr outErr, List<OutErr> additionalOutErrs) {
    if (additionalOutErrs.isEmpty()) {
      return outErr;
    }
    DelegatingOutErr result = new DelegatingOutErr();
    result.addSink(outErr);
    for (OutErr additionalOutErr : additionalOutErrs) {
      result.addSink(additionalOutErr);
    }
    return result;
  }

  private void closeSilently(OutputStream logOutputStream) {
    if (logOutputStream != null) {
      try {
        logOutputStream.close();
      } catch (IOException e) {
        LoggingUtil.logToRemote(Level.WARNING, "Unable to close command.log", e);
      }
    }
  }

  /**
   * Creates an option parser using the common options classes and the command-specific options
   * classes.
   *
   * <p>An overriding method should first call this method and can then override default values
   * directly or by calling {@link BlazeOptionHandler#parseOptions} for command-specific options.
   */
  private OptionsParser createOptionsParser(BlazeCommand command)
      throws OptionsParser.ConstructionException {
    OpaqueOptionsData optionsData;
    optionsData = optionsDataCache.get(command);
    Command annotation = command.getClass().getAnnotation(Command.class);
    OptionsParser parser =
        OptionsParser.builder()
            .optionsData(optionsData)
            .skipStarlarkOptionPrefixes()
            .allowResidue(annotation.allowResidue())
            .withAliasFlag(BLAZE_ALIASING_FLAG)
            .build();
    return parser;
  }

  /** Returns the event handler to use for this Blaze command. */
  private UiEventHandler createEventHandler(
      OutErr outErr,
      UiOptions eventOptions,
      boolean quiet,
      CommandEnvironment env,
      boolean newStatsSummary) {
    Path workspacePath = runtime.getWorkspace().getDirectories().getWorkspace();
    PathFragment workspacePathFragment = workspacePath == null ? null : workspacePath.asFragment();
    return new UiEventHandler(
        outErr,
        eventOptions,
        quiet,
        runtime.getClock(),
        env.getEventBus(),
        workspacePathFragment,
        env.withMergedAnalysisAndExecutionSourceOfTruth(),
        newStatsSummary);
  }

  /** Returns the runtime instance shared by the commands that this dispatcher dispatches to. */
  @VisibleForTesting
  public BlazeRuntime getRuntime() {
    return runtime;
  }

  /**
   * Shuts down all the registered commands to give them a chance to cleanup or close resources.
   * Should be called by the owner of this command dispatcher in all termination cases.
   */
  public void shutdown() {
    closeSilently(logOutputStream);
    logOutputStream = null;
  }

  private void setShutdownReason(String shutdownReason) {
    this.shutdownReason.compareAndSet(null, shutdownReason);
  }

  private static BlazeCommandResult createDetailedCommandResult(
      String message, FailureDetails.Command.Code detailedCode) {
    return BlazeCommandResult.detailedExitCode(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setCommand(FailureDetails.Command.newBuilder().setCode(detailedCode))
                .build()));
  }
}
