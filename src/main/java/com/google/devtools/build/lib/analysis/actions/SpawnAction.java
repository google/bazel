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

package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.actions.ActionAnalysisMetadata.mergeMaps;
import static com.google.devtools.build.lib.packages.ExecGroup.DEFAULT_EXEC_GROUP_NAME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.BaseSpawn;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLineItem;
import com.google.devtools.build.lib.actions.CommandLineLimits;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.actions.CommandLines.ExpandedCommandLines;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.extra.EnvironmentVariable;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.actions.extra.SpawnInfo;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.analysis.starlark.Args;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.exec.SpawnExecException;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.devtools.build.lib.starlarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OnDemandString;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.CompileTimeConstant;
import com.google.errorprone.annotations.ForOverride;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkList;

/** An Action representing an arbitrary subprocess to be forked and exec'd. */
public class SpawnAction extends AbstractAction implements CommandAction {

  private static final String GUID = "ebd6fce3-093e-45ee-adb6-bf513b602f0d";

  private static final Interner<ImmutableSortedMap<String, String>> executionInfoInterner =
      BlazeInterners.newWeakInterner();

  private final NestedSet<Artifact> tools;
  private final CommandLines commandLines;
  private final ActionEnvironment env;

  private final CharSequence progressMessage;
  private final String mnemonic;

  private final ResourceSetOrBuilder resourceSetOrBuilder;
  private final ImmutableMap<String, String> executionInfo;
  private final OutputPathsMode outputPathsMode;

  /**
   * Constructs a SpawnAction using direct initialization arguments.
   *
   * <p>All collections provided must not be subsequently modified.
   *
   * @param owner the owner of the Action
   * @param tools the set of files comprising the tool that does the work (e.g. compiler). This is a
   *     subset of "inputs" and is only used by the WorkerSpawnStrategy
   * @param inputs the set of all files potentially read by this action; must not be subsequently
   *     modified
   * @param outputs the set of all files written by this action; must not be subsequently modified.
   * @param resourceSetOrBuilder the resources consumed by executing this Action.
   * @param env the action's environment
   * @param executionInfo out-of-band information for scheduling the spawn
   * @param commandLines the command lines to execute. This includes the main argv vector and any
   *     param file-backed command lines.
   * @param progressMessage the message printed during the progression of the build
   * @param mnemonic the mnemonic that is reported in the master log
   */
  public SpawnAction(
      ActionOwner owner,
      NestedSet<Artifact> tools,
      NestedSet<Artifact> inputs,
      Iterable<? extends Artifact> outputs,
      ResourceSetOrBuilder resourceSetOrBuilder,
      CommandLines commandLines,
      ActionEnvironment env,
      ImmutableMap<String, String> executionInfo,
      CharSequence progressMessage,
      String mnemonic,
      OutputPathsMode outputPathsMode) {
    super(owner, inputs, outputs);
    this.tools = tools;
    this.resourceSetOrBuilder = resourceSetOrBuilder;
    this.executionInfo =
        executionInfo.isEmpty()
            ? ImmutableSortedMap.of()
            : executionInfoInterner.intern(ImmutableSortedMap.copyOf(executionInfo));
    this.commandLines = commandLines;
    this.env = env;
    this.progressMessage = progressMessage;
    this.mnemonic = mnemonic;
    this.outputPathsMode = outputPathsMode;
  }

  @Override
  public final NestedSet<Artifact> getTools() {
    return tools;
  }

  @Override
  public final ActionEnvironment getEnvironment() {
    return env;
  }

  @VisibleForTesting
  public CommandLines getCommandLines() {
    return commandLines;
  }

  @Override
  public List<String> getArguments() throws CommandLineExpansionException, InterruptedException {
    return commandLines.allArguments(
        PathMappers.create(this, outputPathsMode, this instanceof StarlarkAction));
  }

  @Override
  public Sequence<CommandLineArgsApi> getStarlarkArgs() {
    ImmutableList.Builder<CommandLineArgsApi> result = ImmutableList.builder();
    ImmutableSet<Artifact> directoryInputs =
        getInputs().toList().stream().filter(Artifact::isDirectory).collect(toImmutableSet());

    for (CommandLineAndParamFileInfo commandLine : commandLines.unpack()) {
      result.add(Args.forRegisteredAction(commandLine, directoryInputs));
    }
    return StarlarkList.immutableCopyOf(result.build());
  }

  @Override
  public Sequence<String> getStarlarkArgv() throws EvalException, InterruptedException {
    try {
      return StarlarkList.immutableCopyOf(getArguments());
    } catch (CommandLineExpansionException ex) {
      throw new EvalException(ex);
    }
  }

  @Override
  @VisibleForTesting
  public NestedSet<Artifact> getPossibleInputsForTesting() {
    return getInputs();
  }

  /** Returns command argument, argv[0]. */
  @VisibleForTesting
  public String getCommandFilename() throws CommandLineExpansionException, InterruptedException {
    return Iterables.getFirst(getArguments(), null);
  }

  /** Returns the (immutable) list of arguments, excluding the command name, argv[0]. */
  @VisibleForTesting
  public List<String> getRemainingArguments()
      throws CommandLineExpansionException, InterruptedException {
    return ImmutableList.copyOf(Iterables.skip(getArguments(), 1));
  }

  @Override
  public final boolean isVolatile() {
    return executeUnconditionally();
  }

  /** Hook for subclasses to perform work before the spawn is executed. */
  protected void beforeExecute(ActionExecutionContext actionExecutionContext)
      throws ExecException {}

  /**
   * Hook for subclasses to perform work after the spawn is executed. This method is only executed
   * if the subprocess execution returns normally, not in case of errors (non-zero exit,
   * setup/network failures, etc.).
   */
  @ForOverride
  protected void afterExecute(
      ActionExecutionContext actionExecutionContext,
      List<SpawnResult> spawnResults,
      PathMapper pathMapper)
      throws ExecException, InterruptedException {}

  @Override
  public final ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    try {
      beforeExecute(actionExecutionContext);
      Spawn spawn = getSpawn(actionExecutionContext);
      ImmutableList<SpawnResult> result =
          actionExecutionContext
              .getContext(SpawnStrategyResolver.class)
              .exec(spawn, actionExecutionContext);
      afterExecute(actionExecutionContext, result, spawn.getPathMapper());
      return ActionResult.create(result);
    } catch (CommandLineExpansionException e) {
      throw createCommandLineException(e);
    } catch (ExecException e) {
      if (e instanceof SpawnExecException spawnExecException) {
        throw spawnExecException.toActionExecutionException(this);
      }
      throw ActionExecutionException.fromExecException(e, this);
    }
  }

  private ActionExecutionException createCommandLineException(CommandLineExpansionException e) {
    DetailedExitCode detailedExitCode =
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(Strings.nullToEmpty(e.getMessage()))
                .setSpawn(
                    FailureDetails.Spawn.newBuilder().setCode(Code.COMMAND_LINE_EXPANSION_FAILURE))
                .build());
    return new ActionExecutionException(e, this, /*catastrophe=*/ false, detailedExitCode);
  }

  @VisibleForTesting
  public ResourceSetOrBuilder getResourceSetOrBuilder() {
    return resourceSetOrBuilder;
  }

  /**
   * Returns a Spawn that is representative of the command that this Action will execute. This
   * function must not modify any state.
   *
   * <p>This method is final, as it is merely a shorthand use of the generic way to obtain a spawn,
   * which also depends on the client environment. Subclasses that wish to override the way to get a
   * spawn should override getSpawn() instead.
   */
  @VisibleForTesting
  public final Spawn getSpawnForTesting()
      throws CommandLineExpansionException, InterruptedException {
    return getSpawnForExtraActionSpawnInfo(getInputs());
  }

  Spawn getSpawnForExtraActionSpawnInfo()
      throws CommandLineExpansionException, InterruptedException {
    return getSpawnForExtraActionSpawnInfo(getInputs());
  }

  final Spawn getSpawnForExtraActionSpawnInfo(NestedSet<Artifact> inputs)
      throws CommandLineExpansionException, InterruptedException {
    return new ActionSpawn(
        commandLines.allArguments(),
        this,
        /* env= */ ImmutableMap.of(),
        /* envResolved= */ false,
        inputs,
        // SpawnInfo doesn't report the runfiles trees of the Spawn, so it's fine to just pass in
        // an empty list here.
        /* additionalInputs= */ ImmutableList.of(),
        /* filesetMappings= */ ImmutableMap.of(),
        /* reportOutputs= */ true,
        PathMapper.NOOP);
  }

  /**
   * Returns a spawn that is representative of the command that this Action will execute in the
   * given client environment.
   */
  public Spawn getSpawn(ActionExecutionContext actionExecutionContext)
      throws CommandLineExpansionException, InterruptedException {
    return getSpawn(
        actionExecutionContext,
        actionExecutionContext.getClientEnv(),
        /* envResolved= */ false,
        /* reportOutputs= */ true);
  }

  /**
   * Return a spawn that is representative of the command that this Action will execute in the given
   * environment.
   *
   * @param envResolved If set to true, the passed environment variables will be used as the Spawn
   *     effective environment. Otherwise they will be used as client environment to resolve the
   *     action env.
   */
  protected Spawn getSpawn(
      ActionExecutionContext actionExecutionContext,
      Map<String, String> env,
      boolean envResolved,
      boolean reportOutputs)
      throws CommandLineExpansionException, InterruptedException {
    PathMapper pathMapper =
        PathMappers.create(this, outputPathsMode, this instanceof StarlarkAction);
    ExpandedCommandLines expandedCommandLines =
        commandLines.expand(
            actionExecutionContext.getArtifactExpander(),
            getPrimaryOutput().getExecPath(),
            pathMapper,
            getCommandLineLimits());
    return new ActionSpawn(
        expandedCommandLines.arguments(),
        this,
        env,
        envResolved,
        getInputs(),
        expandedCommandLines.getParamFiles(),
        actionExecutionContext.getTopLevelFilesets(),
        reportOutputs,
        pathMapper);
  }

  @ForOverride
  protected CommandLineLimits getCommandLineLimits() {
    return getOwner().getBuildConfigurationInfo().getCommandLineLimits();
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {
    fp.addString(GUID);
    commandLines.addToFingerprint(
        actionKeyContext,
        artifactExpander,
        PathMappers.getEffectiveOutputPathsMode(outputPathsMode, getMnemonic(), getExecutionInfo()),
        fp);
    fp.addString(mnemonic);
    env.addTo(fp);
    fp.addStringMap(getExecutionInfo());
    PathMappers.addToFingerprint(
        getMnemonic(),
        getExecutionInfo(),
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        actionKeyContext,
        outputPathsMode,
        fp);
  }

  @Override
  public String describeKey() {
    StringBuilder message = new StringBuilder();
    message.append(getProgressMessage());
    message.append('\n');
    for (Map.Entry<String, String> entry : env.getFixedEnv().entrySet()) {
      message.append("  Environment variable: ");
      message.append(ShellEscaper.escapeString(entry.getKey()));
      message.append('=');
      message.append(ShellEscaper.escapeString(entry.getValue()));
      message.append('\n');
    }
    for (String var : getClientEnvironmentVariables()) {
      message.append("  Environment variables taken from the client environment: ");
      message.append(ShellEscaper.escapeString(var));
      message.append('\n');
    }
    for (Map.Entry<String, String> entry : getExecutionInfo().entrySet()) {
      message.append("  Execution info: ");
      message.append(entry.getKey()).append('=').append(entry.getValue());
      message.append('\n');
    }
    try {
      for (String argument : ShellEscaper.escapeAll(getArguments())) {
        message.append("  Argument: ");
        message.append(argument);
        message.append('\n');
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      message.append("Interrupted while expanding command line\n");
    } catch (CommandLineExpansionException e) {
      message.append("Could not expand command line: ");
      message.append(e);
      message.append('\n');
    }
    message.append("  Output paths mode: ");
    message.append(outputPathsMode);
    message.append('\n');
    return message.toString();
  }

  @Override
  public final String getMnemonic() {
    return mnemonic;
  }

  @Override
  protected String getRawProgressMessage() {
    if (progressMessage != null) {
      return progressMessage.toString();
    }
    return super.getRawProgressMessage();
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo(ActionKeyContext actionKeyContext)
      throws CommandLineExpansionException, InterruptedException {
    return super.getExtraActionInfo(actionKeyContext)
        .setExtension(SpawnInfo.spawnInfo, getExtraActionSpawnInfo());
  }

  /**
   * Returns information about this spawn action for use by the extra action mechanism.
   *
   * <p>Subclasses of SpawnAction may override this in order to provide action-specific behaviour.
   * This can be necessary, for example, when the action discovers inputs.
   */
  private SpawnInfo getExtraActionSpawnInfo()
      throws CommandLineExpansionException, InterruptedException {
    SpawnInfo.Builder info = SpawnInfo.newBuilder();
    Spawn spawn = getSpawnForExtraActionSpawnInfo();
    info.addAllArgument(spawn.getArguments());
    for (Map.Entry<String, String> variable : spawn.getEnvironment().entrySet()) {
      info.addVariable(
          EnvironmentVariable.newBuilder()
              .setName(variable.getKey())
              .setValue(variable.getValue())
              .build());
    }
    for (ActionInput input : spawn.getInputFiles().toList()) {
      // Explicitly ignore runfiles tree artifacts here.
      if (!(input instanceof Artifact) || !((Artifact) input).isRunfilesTree()) {
        info.addInputFile(input.getExecPathString());
      }
    }
    info.addAllOutputFile(ActionInputHelper.toExecPaths(spawn.getOutputFiles()));
    return info.build();
  }

  @Override
  @VisibleForTesting
  public final ImmutableMap<String, String> getIncompleteEnvironmentForTesting() {
    // TODO(ulfjack): AbstractAction should declare getEnvironment with a return value of type
    // ActionEnvironment to avoid developers misunderstanding the purpose of this method. That
    // requires first updating all subclasses and callers to actually handle environments correctly,
    // so it's not a small change.
    return getEnvironment().getFixedEnv();
  }

  /** Returns the out-of-band execution data for this action. */
  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    return mergeMaps(super.getExecutionInfo(), executionInfo);
  }

  /** A spawn instance that is tied to a specific SpawnAction. */
  private static final class ActionSpawn extends BaseSpawn {
    private final NestedSet<ActionInput> inputs;
    private final Map<Artifact, FilesetOutputTree> filesetMappings;
    private final ImmutableMap<String, String> effectiveEnvironment;
    private final boolean reportOutputs;
    private final PathMapper pathMapper;

    /**
     * Creates an ActionSpawn with the given environment variables.
     *
     * <p>Subclasses of ActionSpawn may subclass in order to provide action-specific values for
     * environment variables or action inputs.
     */
    private ActionSpawn(
        ImmutableList<String> arguments,
        SpawnAction parent,
        Map<String, String> env,
        boolean envResolved,
        NestedSet<Artifact> inputs,
        Iterable<? extends ActionInput> additionalInputs,
        Map<Artifact, FilesetOutputTree> filesetMappings,
        boolean reportOutputs,
        PathMapper pathMapper)
        throws CommandLineExpansionException {
      super(
          arguments,
          ImmutableMap.of(),
          parent.getExecutionInfo(),
          parent,
          parent.resourceSetOrBuilder);
      NestedSetBuilder<ActionInput> inputsBuilder = NestedSetBuilder.stableOrder();
      addNonFilesetInputs(inputsBuilder, inputs, filesetMappings);
      inputsBuilder.addAll(additionalInputs);

      this.inputs = inputsBuilder.build();
      this.filesetMappings = filesetMappings;
      this.pathMapper = pathMapper;

      // If the action environment is already resolved using the client environment, the given
      // environment variables are used as they are. Otherwise, they are used as clientEnv to
      // resolve the action environment variables.
      if (envResolved) {
        effectiveEnvironment = ImmutableMap.copyOf(env);
      } else {
        effectiveEnvironment = parent.getEffectiveEnvironment(env);
      }
      this.reportOutputs = reportOutputs;
    }

    private static void addNonFilesetInputs(
        NestedSetBuilder<ActionInput> builder,
        NestedSet<Artifact> inputs,
        Map<Artifact, FilesetOutputTree> filesetMappings) {
      if (filesetMappings.isEmpty()) {
        // Keep the original nested set intact. This aids callers that exploit the nested set
        // structure to perform optimizations (see SpawnInputExpander#walkInputs and its callers).
        builder.addTransitive(inputs);
        return;
      }
      for (Artifact input : inputs.toList()) {
        if (!input.isFileset()) {
          builder.add(input);
        }
      }
    }

    @Override
    public PathMapper getPathMapper() {
      return pathMapper;
    }

    @Override
    public ImmutableMap<String, String> getEnvironment() {
      return effectiveEnvironment;
    }

    @Override
    public ImmutableMap<Artifact, FilesetOutputTree> getFilesetMappings() {
      return ImmutableMap.copyOf(filesetMappings);
    }

    @Override
    public NestedSet<? extends ActionInput> getInputFiles() {
      return inputs;
    }

    @Override
    public Collection<Artifact> getOutputFiles() {
      return reportOutputs ? super.getOutputFiles() : ImmutableSet.of();
    }
  }

  /**
   * Builder class to construct {@link SpawnAction} instances.
   */
  public static class Builder {

    private final NestedSetBuilder<Artifact> toolsBuilder = NestedSetBuilder.stableOrder();
    private final NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
    private final List<Artifact> outputs = new ArrayList<>();
    private ResourceSetOrBuilder resourceSetOrBuilder = AbstractAction.DEFAULT_RESOURCE_SET;
    private ImmutableMap<String, String> environment = ImmutableMap.of();
    private ImmutableMap<String, String> executionInfo = ImmutableMap.of();
    private boolean useDefaultShellEnvironment = false;
    protected boolean executeUnconditionally;
    private Object executableArg;
    @Nullable private CustomCommandLine.Builder executableArgs;
    private List<CommandLineAndParamFileInfo> commandLines = new ArrayList<>();

    private CharSequence progressMessage;
    private String mnemonic = "Unknown";
    private String execGroup = DEFAULT_EXEC_GROUP_NAME;

    /** Creates a SpawnAction builder. */
    public Builder() {}

    /** Creates a builder that is a copy of another builder. */
    public Builder(Builder other) {
      this.toolsBuilder.addTransitive(other.toolsBuilder.build());
      this.inputsBuilder.addTransitive(other.inputsBuilder.build());
      this.outputs.addAll(other.outputs);
      this.resourceSetOrBuilder = other.resourceSetOrBuilder;
      this.environment = other.environment;
      this.executionInfo = other.executionInfo;
      this.useDefaultShellEnvironment = other.useDefaultShellEnvironment;
      this.executableArg = other.executableArg;
      this.executableArgs = other.executableArgs;
      this.commandLines = new ArrayList<>(other.commandLines);
      this.progressMessage = other.progressMessage;
      this.mnemonic = other.mnemonic;
    }

    /**
     * Builds the SpawnAction using the passed-in action configuration.
     *
     * <p>This method makes a copy of all the collections, so it is safe to reuse the builder after
     * this method returns.
     *
     * <p>This is annotated with @CheckReturnValue, which causes a compiler error when you call this
     * method and ignore its return value. This is because some time ago, calling .build() had the
     * side-effect of registering it with the RuleContext that was passed in to the constructor.
     * This logic was removed, but if people don't notice and still rely on the side-effect, things
     * may break.
     *
     * @return the SpawnAction.
     */
    @CheckReturnValue
    public SpawnAction build(ActionConstructionContext context) {
      return build(context.getActionOwner(execGroup), context.getConfiguration());
    }

    @VisibleForTesting
    @CheckReturnValue
    public SpawnAction build(ActionOwner owner, BuildConfigurationValue configuration) {
      CommandLines.Builder result = CommandLines.builder();
      if (executableArg != null) {
        result.addSingleArgument(executableArg);
      } else if (executableArgs != null) {
        result.addCommandLine(executableArgs.build());
      }
      for (CommandLineAndParamFileInfo pair : this.commandLines) {
        result.addCommandLine(pair);
      }
      CommandLines commandLines = result.build();
      ActionEnvironment env;
      if (useDefaultShellEnvironment && environment != null) {
        // Inherited variables override fixed variables in ActionEnvironment. Since we want the
        // fixed part of the action-provided environment to override the inherited part of the
        // user-provided environment, we have to explicitly filter the inherited part.
        var userFilteredInheritedEnv =
            ImmutableSet.copyOf(
                Sets.difference(
                    configuration.getActionEnvironment().getInheritedEnv(), environment.keySet()));
        // Do not create a new ActionEnvironment in the common case where no vars have been filtered
        // out.
        if (userFilteredInheritedEnv.equals(
            configuration.getActionEnvironment().getInheritedEnv())) {
          env = configuration.getActionEnvironment();
        } else {
          env =
              ActionEnvironment.create(
                  configuration.getActionEnvironment().getFixedEnv(), userFilteredInheritedEnv);
        }
        env = env.withAdditionalFixedVariables(environment);
      } else if (useDefaultShellEnvironment) {
        env = configuration.getActionEnvironment();
      } else {
        env = ActionEnvironment.create(environment);
      }
      return buildSpawnAction(owner, commandLines, configuration, env);
    }

    @CheckReturnValue
    public SpawnAction buildForActionTemplate(ActionOwner owner) {
      CommandLines.Builder result = CommandLines.builder();
      if (executableArg != null) {
        result.addSingleArgument(executableArg);
      } else {
        result.addCommandLine(executableArgs.build());
      }
      for (CommandLineAndParamFileInfo pair : commandLines) {
        result.addCommandLine(pair.commandLine);
      }
      return buildSpawnAction(owner, result.build(), null, ActionEnvironment.create(environment));
    }

    /**
     * Builds the SpawnAction using the passed-in action configuration.
     *
     * <p>This method makes a copy of all the collections, so it is safe to reuse the builder after
     * this method returns.
     */
    private SpawnAction buildSpawnAction(
        ActionOwner owner,
        CommandLines commandLines,
        @Nullable BuildConfigurationValue configuration,
        ActionEnvironment env) {
      NestedSet<Artifact> tools = toolsBuilder.build();

      // Don't call getInputsAndTools - it wouldn't reuse the built set of tools.
      NestedSet<Artifact> inputsAndTools =
          NestedSetBuilder.<Artifact>stableOrder()
              .addTransitive(inputsBuilder.build())
              .addTransitive(tools)
              .build();

      return createSpawnAction(
          owner,
          tools,
          inputsAndTools,
          ImmutableSet.copyOf(outputs),
          resourceSetOrBuilder,
          commandLines,
          env,
          configuration,
          configuration == null
              ? executionInfo
              : configuration.modifiedExecutionInfo(executionInfo, mnemonic),
          progressMessage,
          mnemonic);
    }

    /** Creates a SpawnAction. */
    protected SpawnAction createSpawnAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputsAndTools,
        ImmutableSet<Artifact> outputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment env,
        @Nullable BuildConfigurationValue configuration,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        String mnemonic) {
      return new SpawnAction(
          owner,
          tools,
          inputsAndTools,
          outputs,
          resourceSetOrBuilder,
          commandLines,
          env,
          executionInfo,
          progressMessage,
          mnemonic,
          PathMappers.getOutputPathsMode(configuration));
    }

    /**
     * Adds an artifact that is necessary for executing the spawn itself (e.g. a compiler), in
     * contrast to an artifact that is necessary for the spawn to do its work (e.g. source code).
     *
     * <p>The artifact is implicitly added to the inputs of the action as well.
     */
    @CanIgnoreReturnValue
    public Builder addTool(Artifact tool) {
      toolsBuilder.add(tool);
      return this;
    }

    /**
     * Adds an executable and its runfiles, which is necessary for executing the spawn itself (e.g.
     * a compiler), in contrast to artifacts that are necessary for the spawn to do its work (e.g.
     * source code).
     */
    @CanIgnoreReturnValue
    public Builder addTool(FilesToRunProvider tool) {
      addTransitiveTools(tool.getFilesToRun());
      return this;
    }

    /** Adds an input to this action. */
    @CanIgnoreReturnValue
    public Builder addInput(Artifact artifact) {
      inputsBuilder.add(artifact);
      return this;
    }

    /** Adds tools to this action. */
    @CanIgnoreReturnValue
    Builder addTools(Iterable<Artifact> artifacts) {
      toolsBuilder.addAll(artifacts);
      return this;
    }

    /** Adds tools to this action. */
    @CanIgnoreReturnValue
    public Builder addTransitiveTools(NestedSet<Artifact> artifacts) {
      toolsBuilder.addTransitive(artifacts);
      return this;
    }

    /** Adds inputs to this action. */
    @CanIgnoreReturnValue
    public Builder addInputs(Iterable<Artifact> artifacts) {
      inputsBuilder.addAll(artifacts);
      return this;
    }

    /**
     * Returns the inputs that the spawn action will depend on. Tools are by definition a subset of
     * the inputs, so they are also present.
     *
     * <p>Warning: this calls {@link NestedSetBuilder#build} on both inputs and tools.
     */
    // TODO(antunesi): Refactor so this method isn't needed. Building new NestedSets on every call
    // is a memory vulnerability, see b/291063247.
    public NestedSet<Artifact> getInputsAndTools() {
      return NestedSetBuilder.<Artifact>stableOrder()
          .addTransitive(inputsBuilder.build())
          .addTransitive(toolsBuilder.build())
          .build();
    }

    /** Adds transitive inputs to this action. */
    @CanIgnoreReturnValue
    public Builder addTransitiveInputs(NestedSet<Artifact> artifacts) {
      inputsBuilder.addTransitive(artifacts);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addOutput(Artifact artifact) {
      outputs.add(artifact);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addOutputs(Iterable<Artifact> artifacts) {
      Iterables.addAll(outputs, artifacts);
      return this;
    }

    /**
     * Sets RecourceSet for builder. If ResourceSetBuilder set, then ResourceSetBuilder will
     * override setResources.
     */
    @CanIgnoreReturnValue
    public Builder setResources(ResourceSetOrBuilder resourceSetOrBuilder) {
      this.resourceSetOrBuilder = resourceSetOrBuilder;
      return this;
    }

    private static final Interner<ImmutableMap<String, String>> envInterner =
        BlazeInterners.newWeakInterner();

    /**
     * Sets the map of environment variables. Do not use! This makes the builder ignore the 'default
     * shell environment', which is computed from the --action_env command line option.
     */
    @CanIgnoreReturnValue
    public Builder setEnvironment(Map<String, String> environment) {
      this.environment = envInterner.intern(ImmutableMap.copyOf(environment));
      this.useDefaultShellEnvironment = false;
      return this;
    }

    /** Sets the map of execution info. */
    @CanIgnoreReturnValue
    public Builder setExecutionInfo(Map<String, String> info) {
      this.executionInfo = ImmutableMap.copyOf(info);
      return this;
    }

    /**
     * Sets the environment to the configuration's default shell environment.
     *
     * <p><b>All actions should set this if possible and avoid using {@link #setEnvironment}.</b>
     *
     * <p>When this property is set, the action will use a minimal, standardized environment map.
     *
     * <p>The list of envvars available to the action (the keys in this map) comes from two places:
     * from the rule class provider and from the command line or rc-files via {@code --action_env}
     * flags.
     *
     * <p>The values for these variables may come from one of three places: from the configuration
     * fragment, or from the {@code --action_env} flag (when the flag specifies a name-value pair,
     * e.g. {@code --action_env=FOO=bar}), or from the client environment (when the flag only
     * specifies a name, e.g. {@code --action_env=HOME}).
     *
     * <p>The client environment is specified by the {@code --client_env} flags. The Bazel client
     * passes these flags to the Bazel server upon each build (e.g. {@code
     * --client_env=HOME=/home/johndoe}), so the server can keep track of environmental changes
     * between builds, and always use the up-to-date environment (as opposed to calling {@code
     * System.getenv}, which it should never do, though as of 2017-08-02 it still does in a few
     * places).
     *
     * <p>The {@code --action_env} has priority over configuration-fragment-dictated envvar values,
     * i.e. if the configuration fragment tries to add FOO=bar to the environment, and there's also
     * {@code --action_env=FOO=baz} or {@code --action_env=FOO}, then FOO will be available to the
     * action and its value will be "baz", or whatever the corresponding {@code --client_env} flag
     * specified, respectively.
     *
     * @see BuildConfigurationValue#getLocalShellEnvironment
     */
    @CanIgnoreReturnValue
    public Builder useDefaultShellEnvironment() {
      this.environment = null;
      this.useDefaultShellEnvironment = true;
      return this;
    }

    /**
     * Same as {@link #useDefaultShellEnvironment()}, but additionally sets the provided fixed
     * environment variables, which take precedence over the variables contained in the default
     * shell environment.
     */
    @CanIgnoreReturnValue
    public Builder useDefaultShellEnvironmentWithOverrides(Map<String, String> environment) {
      this.environment = ImmutableMap.copyOf(environment);
      this.useDefaultShellEnvironment = true;
      return this;
    }

    /**
     * Sets the executable path; the path is interpreted relative to the execution root, unless it's
     * a bare file name.
     *
     * <p><b>Caution</b>: if the executable is a bare file name ("foo"), it will be interpreted
     * relative to PATH. See https://github.com/bazelbuild/bazel/issues/13189 for details. To avoid
     * that, use {@link #setExecutable(Artifact)} instead.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable}
     * or {@link #setShellCommand}.
     */
    @CanIgnoreReturnValue
    public Builder setExecutable(PathFragment executable) {
      this.executableArg = executable;
      this.executableArgs = null;
      return this;
    }

    /**
     * Sets the executable as an artifact.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable}
     * or {@link #setShellCommand}.
     */
    @CanIgnoreReturnValue
    public Builder setExecutable(Artifact executable) {
      addTool(executable);
      this.executableArg = ensureCallable(executable);
      this.executableArgs = null;
      return this;
    }

    /**
     * Sets the executable as a configured target. Automatically adds the files to run to the tools
     * and inputs and uses the executable of the target as the executable.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable}
     * or {@link #setShellCommand}.
     */
    @CanIgnoreReturnValue
    public Builder setExecutable(TransitiveInfoCollection executable) {
      FilesToRunProvider provider = checkNotNull(executable.getProvider(FilesToRunProvider.class));
      return setExecutable(provider);
    }

    /**
     * Sets the executable as a configured target. Automatically adds the files to run to the tools
     * and inputs and uses the executable of the target as the executable.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable}
     * or {@link #setShellCommand}.
     */
    @CanIgnoreReturnValue
    public Builder setExecutable(FilesToRunProvider executableProvider) {
      Artifact executable =
          checkNotNull(
              executableProvider.getExecutable(), "The target does not have an executable");
      this.executableArg = ensureCallable(executable);
      this.executableArgs = null;
      return addTool(executableProvider);
    }

    /**
     * Sets the executable as a String.
     *
     * <p><b>Caution</b>: this is an optimisation intended to be used only by {@link
     * com.google.devtools.build.lib.analysis.starlark.StarlarkActionFactory}. It prevents reference
     * duplication when passing {@link PathFragment} to Starlark as a String and then executing with
     * it.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable}
     * or {@link #setShellCommand}.
     */
    @CanIgnoreReturnValue
    public Builder setExecutableAsString(String executable) {
      this.executableArg = executable;
      this.executableArgs = null;
      return this;
    }

    /**
     * Sets the executable to be a jar executed from the given deploy jar. The deploy jar is
     * automatically added to the action inputs.
     *
     * <p>Assumes that the Jar artifact declares a main class.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable}
     * or {@link #setShellCommand}.
     */
    @CanIgnoreReturnValue
    public Builder setJarExecutable(
        PathFragment javaExecutable, Artifact deployJar, NestedSet<String> jvmArgs) {
      this.executableArgs =
          CustomCommandLine.builder()
              .addPath(javaExecutable)
              .addAll(jvmArgs)
              .add("-jar")
              .addExecPath(deployJar);
      this.executableArg = null;
      toolsBuilder.add(deployJar);
      return this;
    }

    /**
     * Sets the executable to be the shell and adds the given command as the command to be executed.
     *
     * <p>Note that this will not clear the arguments, so any arguments will be passed in addition
     * to the command given here.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable}
     * or {@link #setShellCommand}.
     */
    @CanIgnoreReturnValue
    public Builder setShellCommand(PathFragment shExecutable, String command, boolean pad) {
      this.executableArg = new ShellCommand(shExecutable, command, pad);
      this.executableArgs = null;
      return this;
    }

    /**
     * Sets the executable to be the shell and adds the given interned commands as the commands to
     * be executed.
     */
    @CanIgnoreReturnValue
    public Builder setShellCommand(Iterable<String> command, boolean pad) {
      this.executableArgs = CustomCommandLine.builder().addAll(ImmutableList.copyOf(command));
      if (pad) {
        executableArgs.add("");
      }
      this.executableArg = null;
      return this;
    }

    /** Appends the arguments to the list of executable arguments. */
    @CanIgnoreReturnValue
    public Builder addExecutableArguments(String... arguments) {
      if (executableArg != null) {
        executableArgs = CustomCommandLine.builder().addObject(executableArg);
        executableArg = null;
      }
      executableArgs.addAll(ImmutableList.copyOf(arguments));
      return this;
    }

    /**
     * Adds a delegate to compute the command line at a later time.
     *
     * <p>The arguments are added after the executable arguments. If you add multiple command lines,
     * they are expanded in the corresponding order.
     *
     * <p>The main intention of this method is to save memory by allowing client-controlled sharing
     * between actions and configured targets. Objects passed to this method MUST be immutable.
     *
     * <p>See also {@link CustomCommandLine}.
     */
    @CanIgnoreReturnValue
    public Builder addCommandLine(CommandLine commandLine) {
      this.commandLines.add(new CommandLineAndParamFileInfo(commandLine, null));
      return this;
    }

    /**
     * Adds a delegate to compute the command line at a later time, optionally spilled to a params
     * file.
     *
     * <p>The arguments are added after the executable arguments. If you add multiple command lines,
     * they are expanded in the corresponding order. If the command line is spilled to a params
     * file, it is replaced with an argument pointing to the param file.
     *
     * <p>The main intention of this method is to save memory by allowing client-controlled sharing
     * between actions and configured targets. Objects passed to this method MUST be immutable.
     *
     * <p>See also {@link CustomCommandLine}.
     */
    @CanIgnoreReturnValue
    public Builder addCommandLine(CommandLine commandLine, @Nullable ParamFileInfo paramFileInfo) {
      this.commandLines.add(new CommandLineAndParamFileInfo(commandLine, paramFileInfo));
      return this;
    }

    /**
     * Sets the progress message.
     *
     * <p>The message may contain <code>%{label}</code>, <code>%{input}</code> or <code>%{output}
     * </code> patterns, which are substituted with label string, first input or output's path,
     * respectively.
     */
    @CanIgnoreReturnValue
    public Builder setProgressMessage(@CompileTimeConstant String progressMessage) {
      this.progressMessage = progressMessage;
      return this;
    }

    /**
     * Sets the progress message. The string is lazily evaluated.
     *
     * @param progressMessage The message to display
     * @param subject Passed to {@link String#format}
     * @deprecated Use {@link #setProgressMessage(String)} with provided patterns.
     */
    @FormatMethod
    @Deprecated
    @CanIgnoreReturnValue
    public Builder setProgressMessage(@FormatString String progressMessage, Object subject) {
      return setProgressMessage(
          new OnDemandString() {
            @Override
            public String toString() {
              return String.format(progressMessage, subject);
            }
          });
    }

    /**
     * Sets the progress message. The string is lazily evaluated.
     *
     * @param progressMessage The message to display
     * @param subject0 Passed to {@link String#format}
     * @param subject1 Passed to {@link String#format}
     * @deprecated Use {@link #setProgressMessage(String)} with provided patterns.
     */
    @FormatMethod
    @Deprecated
    @CanIgnoreReturnValue
    public Builder setProgressMessage(
        @FormatString String progressMessage, Object subject0, Object subject1) {
      return setProgressMessage(
          new OnDemandString() {
            @Override
            public String toString() {
              return String.format(progressMessage, subject0, subject1);
            }
          });
    }

    /**
     * Sets the progress message. The string is lazily evaluated.
     *
     * @param progressMessage The message to display
     * @param subject0 Passed to {@link String#format}
     * @param subject1 Passed to {@link String#format}
     * @param subject2 Passed to {@link String#format}
     * @deprecated Use {@link #setProgressMessage(String)} with provided patterns.
     */
    @FormatMethod
    @Deprecated
    @CanIgnoreReturnValue
    public Builder setProgressMessage(
        @FormatString String progressMessage, Object subject0, Object subject1, Object subject2) {
      return setProgressMessage(
          new OnDemandString() {
            @Override
            public String toString() {
              return String.format(progressMessage, subject0, subject1, subject2);
            }
          });
    }

    /**
     * Sets a lazily computed progress message.
     *
     * <p>When possible, prefer use of one of the overloads that use {@link String#format}. If you
     * do use this overload, take care not to capture anything expensive.
     */
    @CanIgnoreReturnValue
    private Builder setProgressMessage(OnDemandString progressMessage) {
      this.progressMessage = progressMessage;
      return this;
    }

    /**
     * Sets the progress message.
     *
     * <p>Same as {@link #setProgressMessage(String)}, except that it may be used with non compile
     * time constants (needed for Starlark literals).
     */
    @CanIgnoreReturnValue
    public Builder setProgressMessageFromStarlark(String progressMessage) {
      this.progressMessage = progressMessage;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setMnemonic(String mnemonic) {
      checkArgument(
          !mnemonic.isEmpty() && CharMatcher.javaLetterOrDigit().matchesAllOf(mnemonic),
          "mnemonic must only contain letters and/or digits, and have non-zero length, was: \"%s\"",
          mnemonic);
      this.mnemonic = mnemonic;
      return this;
    }

    /**
     * Sets the exec group for this action by name. This does not check that {@code execGroup} is
     * being set to a valid exec group (i.e. one that actually exists). This method expects callers
     * to do that work.
     */
    @CanIgnoreReturnValue
    public Builder setExecGroup(String execGroup) {
      this.execGroup = execGroup;
      return this;
    }
  }

  /**
   * Returns a {@link CommandLineItem} for the given executable.
   *
   * <p>In the common case that the executable's exec path is already {@linkplain
   * PathFragment#getCallablePathString callable} (contains {@link PathFragment#SEPARATOR_CHAR}),
   * returns the executable as-is to avoid creating a new object.
   *
   * <p>The only time this method can't return {@code executable} as-is is for source artifacts in
   * the root package, since their exec path contains no path separator. Note that derived artifacts
   * are necessarily callable since they are always under an output directory.
   */
  private static CommandLineItem ensureCallable(Artifact executable) {
    PathFragment execPath = executable.getExecPath();
    return execPath.getCallablePathString().equals(executable.expandToCommandLine())
        ? executable
        : execPath::getCallablePathString;
  }
}
