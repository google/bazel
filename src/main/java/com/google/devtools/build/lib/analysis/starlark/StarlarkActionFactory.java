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
package com.google.devtools.build.lib.analysis.starlark;

import static com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.actions.extra.SpawnInfo;
import com.google.devtools.build.lib.analysis.BashCommandConstructor;
import com.google.devtools.build.lib.analysis.CommandHelper;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.PseudoAction;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.ShToolchain;
import com.google.devtools.build.lib.analysis.actions.BuildInfoFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.ParameterFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.StarlarkAction;
import com.google.devtools.build.lib.analysis.actions.Substitution;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Interrupted;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkActionFactoryApi;
import com.google.devtools.build.lib.starlarkbuildapi.TemplateDictApi;
import com.google.devtools.build.lib.supplier.InterruptibleSupplier;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.GeneratedMessage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.SymbolGenerator;

/** Provides a Starlark interface for all action creation needs. */
public class StarlarkActionFactory implements StarlarkActionFactoryApi {
  private final StarlarkActionContext context;

  /** Counter for actions.run_shell helper scripts. Every script must have a unique name. */
  private int runShellOutputCounter = 0;

  private static final ResourceSet DEFAULT_RESOURCE_SET = ResourceSet.createWithRamCpu(250, 1);
  private static final Set<String> validResources =
      new HashSet<>(Arrays.asList(ResourceSet.CPU, ResourceSet.MEMORY, "local_test"));

  public StarlarkActionFactory(StarlarkActionContext context) {
    this.context = context;
  }

  protected StarlarkActionFactory(StarlarkActionFactory parent) {
    this.context = parent.context;
  }

  private ArtifactRoot newFileRoot() {
    return context.newFileRoot();
  }

  private static void checkToolchainParameterIsSet(
      RuleContext ruleContext, Object toolchainUnchecked) throws EvalException {
    if ((ruleContext.getToolchainContexts() == null
            || ruleContext.getToolchainContexts().contextMap().size() > 1)
        && toolchainUnchecked == Starlark.UNBOUND) {
      throw Starlark.errorf(
          "Couldn't identify if tools are from implicit dependencies or a toolchain. Please"
              + " set the toolchain parameter. If you're not using a toolchain, set it to 'None'.");
    }
  }

  @Override
  public Artifact declareFile(String filename, Object sibling) throws EvalException {
    context.checkMutable("actions.declare_file");
    RuleContext ruleContext = getRuleContext();

    PathFragment fragment;
    if (Starlark.NONE.equals(sibling)) {
      fragment = ruleContext.getPackageDirectory().getRelative(PathFragment.create(filename));
    } else {
      PathFragment original =
          ((Artifact) sibling)
              .getOutputDirRelativePath(
                  getSemantics().getBool(EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT));
      fragment = original.replaceName(filename);
    }

    if (!fragment.startsWith(ruleContext.getPackageDirectory())) {
      throw Starlark.errorf(
          "the output artifact '%s' is not under package directory '%s' for target '%s'",
          fragment, ruleContext.getPackageDirectory(), ruleContext.getLabel());
    }
    return ruleContext.getDerivedArtifact(fragment, newFileRoot());
  }

  @Override
  public Artifact declareDirectory(String filename, Object sibling) throws EvalException {
    context.checkMutable("actions.declare_directory");
    RuleContext ruleContext = getRuleContext();
    PathFragment fragment;

    if (Starlark.NONE.equals(sibling)) {
      fragment = ruleContext.getPackageDirectory().getRelative(PathFragment.create(filename));
    } else {
      PathFragment original =
          ((Artifact) sibling)
              .getOutputDirRelativePath(
                  getSemantics().getBool(EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT));
      fragment = original.replaceName(filename);
    }

    if (!fragment.startsWith(ruleContext.getPackageDirectory())) {
      throw Starlark.errorf(
          "the output directory '%s' is not under package directory '%s' for target '%s'",
          fragment, ruleContext.getPackageDirectory(), ruleContext.getLabel());
    }

    Artifact result = ruleContext.getTreeArtifact(fragment, newFileRoot());
    if (!result.isTreeArtifact()) {
      throw Starlark.errorf(
          "'%s' has already been declared as a regular file, not directory.", filename);
    }
    return result;
  }

  @Override
  public Artifact declareSymlink(String filename, Object sibling) throws EvalException {
    context.checkMutable("actions.declare_symlink");
    RuleContext ruleContext = getRuleContext();

    if (!ruleContext.getConfiguration().allowUnresolvedSymlinks()) {
      throw Starlark.errorf(
          "actions.declare_symlink() is not allowed; "
              + "use the --allow_unresolved_symlinks command line option");
    }

    Artifact result;
    PathFragment rootRelativePath;
    if (Starlark.NONE.equals(sibling)) {
      rootRelativePath = ruleContext.getPackageDirectory().getRelative(filename);
    } else {
      PathFragment original =
          ((Artifact) sibling)
              .getOutputDirRelativePath(
                  getSemantics().getBool(EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT));
      rootRelativePath = original.replaceName(filename);
    }

    result =
        ruleContext.getAnalysisEnvironment().getSymlinkArtifact(rootRelativePath, newFileRoot());

    if (!result.isSymlink()) {
      throw Starlark.errorf(
          "'%s' has already been declared as something other than a symlink.", filename);
    }

    return result;
  }

  @Override
  public void doNothing(String mnemonic, Object inputs) throws EvalException {
    context.checkMutable("actions.do_nothing");
    RuleContext ruleContext = getRuleContext();

    NestedSet<Artifact> inputSet =
        inputs instanceof Depset
            ? Depset.cast(inputs, Artifact.class, "inputs")
            : NestedSetBuilder.<Artifact>compileOrder()
                .addAll(Sequence.cast(inputs, Artifact.class, "inputs"))
                .build();
    Action action =
        new PseudoAction<>(
            UUID.nameUUIDFromBytes(
                String.format("empty action %s", ruleContext.getLabel())
                    .getBytes(StandardCharsets.UTF_8)),
            ruleContext.getActionOwner(),
            inputSet,
            ImmutableList.of(PseudoAction.getDummyOutput(ruleContext)),
            mnemonic,
            SPAWN_INFO,
            SpawnInfo.newBuilder().build());
    registerAction(action);
  }

  @SerializationConstant @VisibleForSerialization
  static final GeneratedMessage.GeneratedExtension<ExtraActionInfo, SpawnInfo> SPAWN_INFO =
      SpawnInfo.spawnInfo;

  @Override
  public void symlink(
      FileApi output,
      Object /* Artifact or None */ targetFile,
      Object /* String or None */ targetPath,
      Boolean isExecutable,
      Object /* String or None */ progressMessageUnchecked,
      Object useExecRootForSourceObject,
      StarlarkThread thread)
      throws EvalException {
    context.checkMutable("actions.symlink");
    if (useExecRootForSourceObject != Starlark.UNBOUND) {
      BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    }
    boolean useExecRootForSource =
        !Starlark.UNBOUND.equals(useExecRootForSourceObject)
            && (Boolean) useExecRootForSourceObject;

    RuleContext ruleContext = getRuleContext();

    if ((targetFile == Starlark.NONE) == (targetPath == Starlark.NONE)) {
      throw Starlark.errorf("Exactly one of \"target_file\" or \"target_path\" is required");
    }

    Artifact outputArtifact = (Artifact) output;
    String progressMessage =
        (progressMessageUnchecked != Starlark.NONE)
            ? (String) progressMessageUnchecked
            : "Creating symlink %{output}";

    Action action;
    if (targetFile != Starlark.NONE) {
      Artifact inputArtifact = (Artifact) targetFile;
      if (outputArtifact.isSymlink()) {
        throw Starlark.errorf(
            "symlink() with \"target_file\" param requires that \"output\" be declared as a "
                + "file or directory, not a symlink (did you mean to use declare_file() or "
                + "declare_directory() instead of declare_symlink()?)");
      }

      if (inputArtifact.isDirectory() != outputArtifact.isDirectory()) {
        String inputType = inputArtifact.isDirectory() ? "directory" : "file";
        String outputType = outputArtifact.isDirectory() ? "directory" : "file";
        throw Starlark.errorf(
            "symlink() with \"target_file\" %s param requires that \"output\" be declared as a %s "
                + "(did you mean to use declare_%s() instead of declare_%s()?)",
            inputType, inputType, inputType, outputType);
      }

      if (isExecutable) {
        if (outputArtifact.isTreeArtifact()) {
          throw Starlark.errorf("symlink() with \"output\" directory param cannot be executable");
        }
        action =
            SymlinkAction.toExecutable(
                ruleContext.getActionOwner(), inputArtifact, outputArtifact, progressMessage);
      } else {
        action =
            SymlinkAction.toArtifact(
                ruleContext.getActionOwner(),
                inputArtifact,
                outputArtifact,
                progressMessage,
                useExecRootForSource);
      }
    } else {
      if (!outputArtifact.isSymlink()) {
        throw Starlark.errorf(
            "symlink() with \"target_path\" param requires that \"output\" be declared as a "
                + "symlink, not a file or directory (did you mean to use declare_symlink() instead "
                + "of declare_file() or declare_directory()?)");
      }

      if (isExecutable) {
        throw Starlark.errorf("\"is_executable\" cannot be True when using \"target_path\"");
      }

      action =
          UnresolvedSymlinkAction.create(
              ruleContext.getActionOwner(), outputArtifact, (String) targetPath, progressMessage);
    }
    registerAction(action);
  }

  @Override
  public void write(FileApi output, Object content, Boolean isExecutable)
      throws EvalException, InterruptedException {
    context.checkMutable("actions.write");
    RuleContext ruleContext = getRuleContext();

    final Action action;
    if (content instanceof String) {
      action =
          FileWriteAction.create(ruleContext, (Artifact) output, (String) content, isExecutable);
    } else if (content instanceof Args args) {
      action =
          new ParameterFileWriteAction(
              ruleContext.getActionOwner(),
              NestedSetBuilder.wrap(Order.STABLE_ORDER, args.getDirectoryArtifacts()),
              (Artifact) output,
              args.build(getMainRepoMappingSupplier()),
              args.getParameterFileType());
    } else {
      throw new AssertionError("Unexpected type: " + content.getClass().getSimpleName());
    }
    registerAction(action);
  }

  @Override
  public void run(
      Sequence<?> outputs,
      Object inputs,
      Object unusedInputsList,
      Object executableUnchecked,
      Object toolsUnchecked,
      Sequence<?> arguments,
      Object mnemonicUnchecked,
      Object progressMessage,
      Boolean useDefaultShellEnv,
      Object envUnchecked,
      Object executionRequirementsUnchecked,
      Object inputManifestsUnchecked,
      Object execGroupUnchecked,
      Object shadowedActionUnchecked,
      Object resourceSetUnchecked,
      Object toolchainUnchecked)
      throws EvalException, InterruptedException {
    context.checkMutable("actions.run");
    execGroupUnchecked = context.maybeOverrideExecGroup(execGroupUnchecked);
    toolchainUnchecked = context.maybeOverrideToolchain(toolchainUnchecked);

    RuleContext ruleContext = getRuleContext();
    boolean useAutoExecGroups = ruleContext.useAutoExecGroups();

    StarlarkAction.Builder builder = new StarlarkAction.Builder();
    buildCommandLine(builder, arguments, getMainRepoMappingSupplier());
    if (executableUnchecked instanceof Artifact executable) {
      FilesToRunProvider provider = context.getExecutableRunfiles(executable, "executable");
      if (provider == null) {
        if (useAutoExecGroups && execGroupUnchecked == Starlark.NONE) {
          checkToolchainParameterIsSet(ruleContext, toolchainUnchecked);
        }
        builder.setExecutable(executable);
      } else {
        builder.setExecutable(provider);
      }
    } else if (executableUnchecked instanceof String) {
      // Normalise if needed and then pass as a String; this keeps the reference when PathFragment
      // is passed from native to Starlark
      builder.setExecutableAsString(
          PathFragment.create((String) executableUnchecked).getPathString());
    } else if (executableUnchecked instanceof FilesToRunProvider) {
      if (useAutoExecGroups
          && !context.areRunfilesFromDeps((FilesToRunProvider) executableUnchecked)
          && execGroupUnchecked == Starlark.NONE) {
        checkToolchainParameterIsSet(ruleContext, toolchainUnchecked);
      }
      builder.setExecutable((FilesToRunProvider) executableUnchecked);
    } else {
      // Should have been verified by Starlark before this function is called
      throw new IllegalStateException();
    }
    registerStarlarkAction(
        outputs,
        inputs,
        unusedInputsList,
        toolsUnchecked,
        mnemonicUnchecked,
        progressMessage,
        useDefaultShellEnv,
        envUnchecked,
        executionRequirementsUnchecked,
        execGroupUnchecked,
        shadowedActionUnchecked,
        resourceSetUnchecked,
        toolchainUnchecked,
        builder);
  }

  @Override
  public Artifact transformVersionFile(
      Object transformFuncObject,
      Object templateObject,
      String outputFileName,
      StarlarkThread thread)
      throws InterruptedException, EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return transformBuildInfoFile(
        transformFuncObject, templateObject, outputFileName, true, thread);
  }

  @Override
  public Artifact transformInfoFile(
      Object transformFuncObject,
      Object templateObject,
      String outputFileName,
      StarlarkThread thread)
      throws InterruptedException, EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return transformBuildInfoFile(
        transformFuncObject, templateObject, outputFileName, false, thread);
  }

  private Artifact transformBuildInfoFile(
      Object transformFuncObject,
      Object templateObject,
      String outputFileName,
      boolean isVolatile,
      StarlarkThread thread)
      throws InterruptedException, EvalException {
    RuleContext ruleContext = getRuleContext();
    Artifact templateFile = (Artifact) templateObject;
    PathFragment fragment =
        ruleContext.getPackageDirectory().getRelative(PathFragment.create(outputFileName));
    Artifact buildInfoFile =
        isVolatile
            ? ruleContext
                .getAnalysisEnvironment()
                .getConstantMetadataArtifact(fragment, newFileRoot())
            : ruleContext.getDerivedArtifact(fragment, newFileRoot());
    StarlarkFunction translationFunc = (StarlarkFunction) transformFuncObject;
    BuildInfoFileWriteAction action =
        new BuildInfoFileWriteAction(
            ruleContext.getActionOwner(),
            isVolatile
                ? ruleContext.getAnalysisEnvironment().getVolatileWorkspaceStatusArtifact()
                : ruleContext.getAnalysisEnvironment().getStableWorkspaceStatusArtifact(),
            buildInfoFile,
            translationFunc,
            templateFile,
            isVolatile,
            thread.getSemantics());
    registerAction(action);
    return buildInfoFile;
  }

  private void validateActionCreation() throws EvalException {
    // We check if the rule is a dependency resolution rule but allow aspects attached to them.
    // The idea is that dependency resolution rules should not depend on anything other than
    // dependency resolution rules but since there is no such thing as "dependency resolution
    // aspect", there is no risk of that with aspects.
    if (getRuleContext().getAspectDescriptors().isEmpty()
        && getRuleContext().getRule().getRuleClassObject().isDependencyResolutionRule()) {
      throw Starlark.errorf("rules that can be required for materializers shouldn't have actions");
    }

    if (getRuleContext().getRule().isAnalysisTest()) {
      throw Starlark.errorf(
          "implementation function of a rule with "
              + "analysis_test=true may not register actions. Analysis test rules may only return "
              + "success/failure information via AnalysisTestResultInfo.");
    }
  }

  /**
   * Registers action in the context of this {@link StarlarkActionFactory}.
   *
   * <p>Use {@link #getRuleContext()} to obtain the context required to create this action.
   */
  public void registerAction(ActionAnalysisMetadata action) throws EvalException {
    validateActionCreation();
    getRuleContext().registerAction(action);
  }

  public RuleContext getRuleContext() {
    return context.getRuleContext();
  }

  private StarlarkSemantics getSemantics() {
    return context.getStarlarkSemantics();
  }

  private static void verifyExecGroupExists(String execGroup, RuleContext ctx)
      throws EvalException {
    if (!ctx.hasToolchainContext(execGroup)) {
      throw Starlark.errorf("Action declared for non-existent exec group '%s'.", execGroup);
    }
  }

  private static void verifyAutomaticExecGroupExists(String execGroup, RuleContext ruleContext)
      throws EvalException {
    if (!ruleContext.hasToolchainContext(execGroup)) {
      throw Starlark.errorf("Action declared for non-existent toolchain '%s'.", execGroup);
    }
  }

  private static void checkValidGroupName(String execGroup) throws EvalException {
    if (!StarlarkExecGroupCollection.isValidGroupName(execGroup)) {
      throw Starlark.errorf("Invalid name for exec group '%s'.", execGroup);
    }
  }

  private static PlatformInfo getExecutionPlatform(Object execGroupUnchecked, RuleContext ctx)
      throws EvalException {
    if (execGroupUnchecked == Starlark.NONE) {
      return ctx.getExecutionPlatform(ExecGroup.DEFAULT_EXEC_GROUP_NAME);
    } else {
      String execGroup = (String) execGroupUnchecked;
      verifyExecGroupExists(execGroup, ctx);
      checkValidGroupName(execGroup);
      return ctx.getExecutionPlatform((String) execGroupUnchecked);
    }
  }

  @Override
  public void runShell(
      Sequence<?> outputs,
      Object inputs,
      Object toolsUnchecked,
      Sequence<?> arguments,
      Object mnemonicUnchecked,
      Object commandUnchecked,
      Object progressMessage,
      Boolean useDefaultShellEnv,
      Object envUnchecked,
      Object executionRequirementsUnchecked,
      Object inputManifestsUnchecked,
      Object execGroupUnchecked,
      Object shadowedActionUnchecked,
      Object resourceSetUnchecked,
      Object toolchainUnchecked)
      throws EvalException, InterruptedException {
    context.checkMutable("actions.run_shell");
    execGroupUnchecked = context.maybeOverrideExecGroup(execGroupUnchecked);
    toolchainUnchecked = context.maybeOverrideToolchain(toolchainUnchecked);

    RuleContext ruleContext = getRuleContext();

    StarlarkAction.Builder builder = new StarlarkAction.Builder();
    buildCommandLine(builder, arguments, getMainRepoMappingSupplier());

    // When we use a shell command, add an empty argument before other arguments.
    //   e.g.  bash -c "cmd" '' 'arg1' 'arg2'
    // bash will use the empty argument as the value of $0 (which we don't care about).
    // arg1 and arg2 will be $1 and $2, as a user expects.
    boolean pad = !arguments.isEmpty();

    if (commandUnchecked instanceof String command) {
      ImmutableMap<String, String> executionInfo =
          ImmutableMap.copyOf(TargetUtils.getExecutionInfo(ruleContext.getRule()));
      String helperScriptSuffix = String.format(".run_shell_%d.sh", runShellOutputCounter++);
      PathFragment shExecutable =
          ShToolchain.getPathForPlatform(
              ruleContext.getConfiguration(),
              getExecutionPlatform(execGroupUnchecked, ruleContext));
      BashCommandConstructor constructor =
          CommandHelper.buildBashCommandConstructor(
              executionInfo, shExecutable, helperScriptSuffix);
      Artifact helperScript =
          CommandHelper.commandHelperScriptMaybe(ruleContext, command, constructor);
      if (helperScript == null) {
        builder.setShellCommand(shExecutable, command, pad);
      } else {
        builder.setShellCommand(shExecutable, helperScript.getExecPathString(), pad);
        builder.addInput(helperScript);
      }
    } else if (commandUnchecked instanceof Sequence<?> commandList) {
      if (getSemantics().getBool(BuildLanguageOptions.INCOMPATIBLE_RUN_SHELL_COMMAND_STRING)) {
        throw Starlark.errorf(
            "'command' must be of type string. passing a sequence of strings as 'command'"
                + " is deprecated. To temporarily disable this check,"
                + " set --incompatible_run_shell_command_string=false.");
      }
      if (!arguments.isEmpty()) {
        throw Starlark.errorf("'arguments' must be empty if 'command' is a sequence of strings");
      }
      List<String> command = Sequence.cast(commandList, String.class, "command");
      builder.setShellCommand(command, pad);
    } else {
      throw Starlark.errorf(
          "expected string or list of strings for command instead of %s",
          Starlark.type(commandUnchecked));
    }
    registerStarlarkAction(
        outputs,
        inputs,
        /* unusedInputsList= */ Starlark.NONE,
        toolsUnchecked,
        mnemonicUnchecked,
        progressMessage,
        useDefaultShellEnv,
        envUnchecked,
        executionRequirementsUnchecked,
        execGroupUnchecked,
        shadowedActionUnchecked,
        resourceSetUnchecked,
        toolchainUnchecked,
        builder);
  }

  private static void buildCommandLine(
      SpawnAction.Builder builder,
      Sequence<?> argumentsList,
      InterruptibleSupplier<RepositoryMapping> repoMappingSupplier)
      throws EvalException, InterruptedException {
    ImmutableList.Builder<String> stringArgs = null;
    for (Object value : argumentsList) {
      if (value instanceof String) {
        if (stringArgs == null) {
          stringArgs = ImmutableList.builder();
        }
        stringArgs.add((String) value);
      } else if (value instanceof Args args) {
        if (stringArgs != null) {
          builder.addCommandLine(CommandLine.of(stringArgs.build()));
          stringArgs = null;
        }
        ParamFileInfo paramFileInfo = args.getParamFileInfo();
        builder.addCommandLine(args.build(repoMappingSupplier), paramFileInfo);
      } else {
        throw Starlark.errorf(
            "expected list of strings or ctx.actions.args() for arguments instead of %s",
            Starlark.type(value));
      }
    }
    if (stringArgs != null) {
      builder.addCommandLine(CommandLine.of(stringArgs.build()));
    }
  }

  /**
   * Setup for spawn actions common between {@link #run} and {@link #runShell}.
   *
   * <p>{@code builder} should have either executable or a command set.
   */
  private void registerStarlarkAction(
      Sequence<?> outputs,
      Object inputs,
      Object unusedInputsList,
      Object toolsUnchecked,
      Object mnemonicUnchecked,
      Object progressMessage,
      Boolean useDefaultShellEnv,
      Object envUnchecked,
      Object executionRequirementsUnchecked,
      Object execGroupUnchecked,
      Object shadowedActionUnchecked,
      Object resourceSetUnchecked,
      Object toolchainUnchecked,
      StarlarkAction.Builder builder)
      throws EvalException {
    if (inputs instanceof Sequence) {
      builder.addInputs(Sequence.cast(inputs, Artifact.class, "inputs"));
    } else {
      builder.addTransitiveInputs(Depset.cast(inputs, Artifact.class, "inputs"));
    }

    List<Artifact> outputArtifacts = Sequence.cast(outputs, Artifact.class, "outputs");
    if (outputArtifacts.isEmpty()) {
      throw Starlark.errorf("param 'outputs' may not be empty");
    }
    builder.addOutputs(outputArtifacts);

    if (unusedInputsList != Starlark.NONE) {
      if (unusedInputsList instanceof Artifact artifact) {
        builder.setUnusedInputsList(Optional.of(artifact));
      } else {
        throw Starlark.errorf(
            "expected value of type 'File' for a member of parameter 'unused_inputs_list' but got"
                + " %s instead",
            Starlark.type(unusedInputsList));
      }
    }

    RuleContext ruleContext = getRuleContext();
    boolean useAutoExecGroups = ruleContext.useAutoExecGroups();

    if (toolsUnchecked != Starlark.UNBOUND) {
      List<?> tools =
          toolsUnchecked instanceof Sequence
              ? Sequence.cast(toolsUnchecked, Object.class, "tools")
              : Depset.cast(toolsUnchecked, Object.class, "tools").toList();

      for (Object toolUnchecked : tools) {
        if (toolUnchecked instanceof Artifact artifact) {
          builder.addTool(artifact);
          FilesToRunProvider provider = context.getExecutableRunfiles(artifact, "executable");
          if (provider != null) {
            builder.addTool(provider);
          } else {
            if (useAutoExecGroups && execGroupUnchecked == Starlark.NONE) {
              checkToolchainParameterIsSet(ruleContext, toolchainUnchecked);
            }
          }
        } else if (toolUnchecked instanceof FilesToRunProvider) {
          if (useAutoExecGroups
              && !context.areRunfilesFromDeps((FilesToRunProvider) toolUnchecked)
              && execGroupUnchecked == Starlark.NONE) {
            checkToolchainParameterIsSet(ruleContext, toolchainUnchecked);
          }
          builder.addTool((FilesToRunProvider) toolUnchecked);
        } else if (toolUnchecked instanceof Depset) {
          try {
            if (useAutoExecGroups && execGroupUnchecked == Starlark.NONE) {
              checkToolchainParameterIsSet(ruleContext, toolchainUnchecked);
            }
            builder.addTransitiveTools(((Depset) toolUnchecked).getSet(Artifact.class));
          } catch (TypeException e) {
            throw Starlark.errorf(
                "expected value of type 'File, FilesToRunProvider or Depset of Files' for a member "
                    + "of parameter 'tools' but %s",
                e.getMessage());
          }
        } else {
          throw Starlark.errorf(
              "expected value of type 'File, FilesToRunProvider or Depset of Files' for a member of"
                  + " parameter 'tools' but got %s instead",
              Starlark.type(toolUnchecked));
        }
      }
    }

    String mnemonic = getMnemonic(mnemonicUnchecked);
    try {
      builder.setMnemonic(mnemonic);
    } catch (IllegalArgumentException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
    if (progressMessage != Starlark.NONE) {
      builder.setProgressMessageFromStarlark((String) progressMessage);
    }

    ImmutableMap<String, String> env = null;
    if (envUnchecked != Starlark.NONE) {
      env = ImmutableMap.copyOf(Dict.cast(envUnchecked, String.class, String.class, "env"));
    }
    if (Starlark.truth(useDefaultShellEnv)) {
      if (env != null
          && getSemantics()
              .getBool(BuildLanguageOptions.INCOMPATIBLE_MERGE_FIXED_AND_DEFAULT_SHELL_ENV)) {
        builder.useDefaultShellEnvironmentWithOverrides(env);
      } else {
        builder.useDefaultShellEnvironment();
      }
    } else if (env != null) {
      builder.setEnvironment(env);
    }

    ImmutableMap<String, String> executionInfo =
        TargetUtils.getFilteredExecutionInfo(
            executionRequirementsUnchecked,
            ruleContext.getRule(),
            getSemantics().getBool(BuildLanguageOptions.INCOMPATIBLE_ALLOW_TAGS_PROPAGATION));
    builder.setExecutionInfo(executionInfo);

    Label toolchainLabel = null;
    if (toolchainUnchecked instanceof Label label) {
      toolchainLabel = label;
    } else if (toolchainUnchecked instanceof String) {
      try {
        toolchainLabel =
            Label.parseWithPackageContext(
                (String) toolchainUnchecked, ruleContext.getPackageContext());
      } catch (LabelSyntaxException e) {
        throw Starlark.errorf("%s", e.getMessage());
      }
    }

    if (execGroupUnchecked != Starlark.NONE) {
      String execGroup = (String) execGroupUnchecked;
      verifyExecGroupExists(execGroup, ruleContext);
      checkValidGroupName(execGroup);

      // If toolchain and exec_groups are both defined, verify they are compatible.
      if (useAutoExecGroups && toolchainLabel != null) {
        if (ruleContext.getExecGroups().getExecGroup(execGroup).toolchainTypes().stream()
            .map(ToolchainTypeRequirement::toolchainType)
            .noneMatch(toolchainLabel::equals)) {
          throw Starlark.errorf(
              "`toolchain` and `exec_group` parameters inside actions.{run, run_shell} are not"
                  + " compatible; use one of them or define `toolchain` which is compatible with"
                  + " the exec_group (already exists inside the `exec_group`)");
        }
      }

      builder.setExecGroup(execGroup);
    } else if (useAutoExecGroups && toolchainLabel != null) {
      verifyAutomaticExecGroupExists(toolchainLabel.toString(), ruleContext);
      builder.setExecGroup(toolchainLabel.toString());
    } else {
      builder.setExecGroup(ExecGroup.DEFAULT_EXEC_GROUP_NAME);
    }

    if (shadowedActionUnchecked != Starlark.NONE) {
      builder.setShadowedAction(Optional.of((Action) shadowedActionUnchecked));
    }

    if (resourceSetUnchecked != Starlark.NONE) {
      validateResourceSetBuilder(resourceSetUnchecked);
      builder.setResources(
          new StarlarkActionResourceSetBuilder(
              (StarlarkCallable) resourceSetUnchecked, mnemonic, getSemantics()));
    }

    // Always register the action
    registerAction(builder.build(ruleContext));
  }

  private static class StarlarkActionResourceSetBuilder implements ResourceSetOrBuilder {
    private final StarlarkCallable fn;
    private final String mnemonic;
    private final StarlarkSemantics semantics;

    StarlarkActionResourceSetBuilder(
        StarlarkCallable fn, String mnemonic, StarlarkSemantics semantics) {
      this.fn = fn;
      this.mnemonic = mnemonic;
      this.semantics = semantics;
    }

    @Override
    public ResourceSet buildResourceSet(OS os, int inputsSize) throws ExecException {
      try (Mutability mu = Mutability.create("resource_set_builder_function")) {
        // Only numerical values are retained from the result, so a transient SymbolGenerator
        // is fine.
        StarlarkThread thread =
            StarlarkThread.create(
                mu, semantics, "resource_set callback", SymbolGenerator.createTransient());
        StarlarkInt inputInt = StarlarkInt.of(inputsSize);
        Object response =
            Starlark.positionalOnlyCall(thread, this.fn, os.getCanonicalName(), inputInt);
        Map<String, Object> resourceSetMapRaw =
            Dict.cast(response, String.class, Object.class, "resource_set");

        if (!validResources.containsAll(resourceSetMapRaw.keySet())) {
          String message =
              String.format(
                  "Illegal resource keys: (%s)",
                  Joiner.on(",").join(Sets.difference(resourceSetMapRaw.keySet(), validResources)));
          throw new EvalException(message);
        }

        return ResourceSet.create(
            getNumericOrDefault(
                resourceSetMapRaw, ResourceSet.MEMORY, DEFAULT_RESOURCE_SET.getMemoryMb()),
            getNumericOrDefault(
                resourceSetMapRaw, ResourceSet.CPU, DEFAULT_RESOURCE_SET.getCpuUsage()),
            (int)
                getNumericOrDefault(
                    resourceSetMapRaw, "local_test", DEFAULT_RESOURCE_SET.getLocalTestCount()));
      } catch (EvalException e) {
        throw new UserExecException(
            FailureDetail.newBuilder()
                .setMessage(
                    String.format("Could not build resources for %s. %s", mnemonic, e.getMessage()))
                .setStarlarkAction(
                    FailureDetails.StarlarkAction.newBuilder()
                        .setCode(FailureDetails.StarlarkAction.Code.STARLARK_ACTION_UNKNOWN)
                        .build())
                .build());
      } catch (InterruptedException e) {
        throw new UserExecException(
            FailureDetail.newBuilder()
                .setMessage(e.getMessage())
                .setInterrupted(
                    Interrupted.newBuilder().setCode(Interrupted.Code.INTERRUPTED).build())
                .build());
      }
    }

    private static double getNumericOrDefault(
        Map<String, Object> resourceSetMap, String key, double defaultValue) throws EvalException {
      if (!resourceSetMap.containsKey(key)) {
        return defaultValue;
      }

      Object value = resourceSetMap.get(key);
      if (value instanceof StarlarkInt starlarkInt) {
        return starlarkInt.toDouble();
      }

      if (value instanceof StarlarkFloat starlarkFloat) {
        return starlarkFloat.toDouble();
      }
      throw new EvalException(
          String.format(
              "Illegal resource value type for key %s: got %s, want int or float",
              key, Starlark.type(value)));
    }
  }

  private static void validateResourceSetBuilder(Object fn) throws EvalException {
    if (!(fn instanceof StarlarkCallable)) {
      throw Starlark.errorf(
          "resource_set should be a Starlark-callable function, but got %s instead",
          Starlark.type(fn));
    }

    if (fn instanceof StarlarkFunction sfn) {

      // Reject non-global functions, because arbitrary closures may cause large
      // analysis-phase data structures to remain live into the execution phase.
      // We require that the function is "global" as opposed to "not a closure"
      // because a global function may be closure if it refers to load bindings.
      // This unfortunately disallows such trivially safe non-global
      // functions as "lambda x: x".
      // See https://github.com/bazelbuild/bazel/issues/12701.
      if (sfn.getModule().getGlobal(sfn.getName()) != sfn) {
        throw Starlark.errorf(
            "to avoid unintended retention of analysis data structures, "
                + "the resource_set function (declared at %s) must be declared "
                + "by a top-level def statement",
            sfn.getLocation());
      }
    }
  }

  private String getMnemonic(Object mnemonicUnchecked) {
    String mnemonic = mnemonicUnchecked == Starlark.NONE ? "Action" : (String) mnemonicUnchecked;
    if (getRuleContext().getConfiguration().getReservedActionMnemonics().contains(mnemonic)) {
      mnemonic = mangleMnemonic(mnemonic);
    }
    return mnemonic;
  }

  private static String mangleMnemonic(String mnemonic) {
    return mnemonic + "FromStarlark";
  }

  @Override
  public void expandTemplate(
      FileApi template,
      FileApi output,
      Dict<?, ?> substitutionsUnchecked,
      Boolean executable,
      /* TemplateDict */ Object computedSubstitutions)
      throws EvalException {
    context.checkMutable("actions.expand_template");
    // We use a map to check for duplicate keys
    ImmutableMap.Builder<String, Substitution> substitutionsBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> substitution :
        Dict.cast(substitutionsUnchecked, String.class, String.class, "substitutions").entrySet()) {
      substitutionsBuilder.put(
          substitution.getKey(), Substitution.of(substitution.getKey(), substitution.getValue()));
    }
    if (!Starlark.UNBOUND.equals(computedSubstitutions)) {
      for (Substitution substitution : ((TemplateDict) computedSubstitutions).getAll()) {
        substitutionsBuilder.put(substitution.getKey(), substitution);
      }
    }
    ImmutableMap<String, Substitution> substitutionMap;
    try {
      substitutionMap = substitutionsBuilder.buildOrThrow();
    } catch (IllegalArgumentException e) {
      // user added duplicate keys, report the error, but the stack trace is not of use
      throw Starlark.errorf("%s", e.getMessage());
    }
    TemplateExpansionAction action =
        new TemplateExpansionAction(
            getRuleContext().getActionOwner(),
            (Artifact) template,
            (Artifact) output,
            substitutionMap.values().asList(),
            executable);
    registerAction(action);
  }

  @Override
  public Args args(StarlarkThread thread) {
    return Args.newArgs(thread.mutability(), getSemantics());
  }

  @Override
  public TemplateDictApi templateDict() {
    return TemplateDict.newDict();
  }

  @Override
  public FileApi createShareableArtifact(String path, Object artifactRoot, StarlarkThread thread)
      throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    ArtifactRoot root =
        artifactRoot == Starlark.UNBOUND
            ? getRuleContext().getBinDirectory()
            : (ArtifactRoot) artifactRoot;
    return getRuleContext().getShareableArtifact(PathFragment.create(path), root);
  }

  @Override
  public boolean isImmutable() {
    return context.isImmutable();
  }

  @Override
  public void repr(Printer printer) {
    printer.append("actions for");
    context.repr(printer);
  }

  private InterruptibleSupplier<RepositoryMapping> getMainRepoMappingSupplier() {
    return context.getRuleContext().getAnalysisEnvironment()::getMainRepoMapping;
  }

  /** The analysis context for {@code Starlark} actions */
  // For now, this contains methods necessary for SubruleContext to begin using
  // StarlarkActionFactory without any invasive changes to the latter. It will be improved once the
  // subrule implementation approaches maturity.
  // TODO(hvd): clean up this interface to only contain general-purpose methods
  public interface StarlarkActionContext extends StarlarkValue {
    ArtifactRoot newFileRoot();

    void checkMutable(String attrName) throws EvalException;

    FilesToRunProvider getExecutableRunfiles(Artifact executable, String what) throws EvalException;

    boolean areRunfilesFromDeps(FilesToRunProvider executable);

    RuleContext getRuleContext();

    StarlarkSemantics getStarlarkSemantics();

    default Object maybeOverrideExecGroup(Object execGroupUnchecked) throws EvalException {
      return execGroupUnchecked;
    }

    default Object maybeOverrideToolchain(Object toolchainUnchecked) throws EvalException {
      return toolchainUnchecked;
    }
  }
}
