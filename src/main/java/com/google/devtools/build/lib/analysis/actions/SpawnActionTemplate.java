// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyComputer;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLineLimits;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;

/** An {@link ActionTemplate} that expands into {@link SpawnAction}s at execution time. */
public final class SpawnActionTemplate extends ActionKeyComputer
    implements ActionTemplate<SpawnAction> {
  private final SpecialArtifact inputTreeArtifact;
  private final SpecialArtifact outputTreeArtifact;
  private final NestedSet<Artifact> allInputs;
  private final NestedSet<Artifact> commonTools;
  private final ActionOwner actionOwner;
  private final String mnemonic;
  private final OutputPathMapper outputPathMapper;
  private final SpawnAction.Builder spawnActionBuilder;
  private final CustomCommandLine commandLineTemplate;

  /**
   * Interface providing mapping between expanded input files under the input TreeArtifact and
   * parent-relative paths of their associated output file under the output TreeArtifact.
   *
   * <p>Users of SpawnActionTemplate must provide a mapper object implementing this interface.
   * SpawnActionTemplate uses the mapper to query for the path of output artifact associated with
   * each input {@link TreeFileArtifact} resolved at execution time.
   */
  public interface OutputPathMapper {
   /**
    * Given the input {@link TreeFileArtifact}, returns the parent-relative path of the associated
    * output {@link TreeFileArtifact}.
    *
    * @param input the input {@link TreeFileArtifact}
    */
    PathFragment parentRelativeOutputPath(TreeFileArtifact input);
  }

  private SpawnActionTemplate(
      ActionOwner actionOwner,
      SpecialArtifact inputTreeArtifact,
      SpecialArtifact outputTreeArtifact,
      NestedSet<Artifact> commonInputs,
      NestedSet<Artifact> commonTools,
      OutputPathMapper outputPathMapper,
      CustomCommandLine commandLineTemplate,
      String mnemonic,
      SpawnAction.Builder spawnActionBuilder) {
    this.inputTreeArtifact = inputTreeArtifact;
    this.outputTreeArtifact = outputTreeArtifact;
    this.commonTools = commonTools;
    this.allInputs =
        NestedSetBuilder.<Artifact>stableOrder()
            .add(inputTreeArtifact)
            .addTransitive(commonInputs)
            .addTransitive(commonTools)
            .build();
    this.outputPathMapper = outputPathMapper;
    this.actionOwner = actionOwner;
    this.mnemonic = mnemonic;
    this.spawnActionBuilder = spawnActionBuilder;
    this.commandLineTemplate = commandLineTemplate;
  }

  @Override
  public ImmutableList<SpawnAction> generateActionsForInputArtifacts(
      ImmutableSet<TreeFileArtifact> inputTreeFileArtifacts, ActionLookupKey artifactOwner) {
    ImmutableList.Builder<SpawnAction> expandedActions =
        ImmutableList.builderWithExpectedSize(inputTreeFileArtifacts.size());
    for (TreeFileArtifact inputTreeFileArtifact : inputTreeFileArtifacts) {
      PathFragment parentRelativeOutputPath =
          outputPathMapper.parentRelativeOutputPath(inputTreeFileArtifact);

      TreeFileArtifact outputTreeFileArtifact =
          TreeFileArtifact.createTemplateExpansionOutput(
              outputTreeArtifact, parentRelativeOutputPath, artifactOwner);

      expandedActions.add(createAction(inputTreeFileArtifact, outputTreeFileArtifact));
    }

    return expandedActions.build();
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {
    TreeFileArtifact inputTreeFileArtifact =
        TreeFileArtifact.createTreeOutput(inputTreeArtifact, "dummy_for_key");
    TreeFileArtifact outputTreeFileArtifact =
        TreeFileArtifact.createTemplateExpansionOutput(
            outputTreeArtifact,
            outputPathMapper.parentRelativeOutputPath(inputTreeFileArtifact),
            ActionTemplateExpansionValue.key(
                outputTreeArtifact.getArtifactOwner(), /*actionIndex=*/ 0));
    SpawnAction dummyAction = createAction(inputTreeFileArtifact, outputTreeFileArtifact);
    dummyAction.computeKey(actionKeyContext, artifactExpander, fp);
  }

  /**
   * Returns a SpawnAction that takes inputTreeFileArtifact as input and generates
   * outputTreeFileArtifact.
   */
  private SpawnAction createAction(
      TreeFileArtifact inputTreeFileArtifact, TreeFileArtifact outputTreeFileArtifact) {
    SpawnAction.Builder actionBuilder = new ExpandedSpawnAction.Builder(spawnActionBuilder);
    actionBuilder.addInput(inputTreeFileArtifact);
    actionBuilder.addOutput(outputTreeFileArtifact);

    CommandLine commandLine = commandLineTemplate.evaluateTreeFileArtifacts(
        ImmutableList.of(inputTreeFileArtifact, outputTreeFileArtifact));
    actionBuilder.addCommandLine(commandLine);

    // Note that we pass in nulls below because SpawnActionTemplate does not support param file, and
    // it does not use any default value for executable or shell environment. They must be set
    // explicitly via builder method #setExecutable and #setEnvironment.
    return actionBuilder.buildForActionTemplate(actionOwner);
  }

  /**
   * Returns the input TreeArtifact.
   *
   * <p>This method is called by Skyframe to expand the input TreeArtifact into child
   * TreeFileArtifacts. Skyframe then expands this SpawnActionTemplate with the TreeFileArtifacts
   * through {@link #generateActionsForInputArtifacts}.
   */
  @Override
  public SpecialArtifact getInputTreeArtifact() {
    return inputTreeArtifact;
  }

  @Override
  public SpecialArtifact getOutputTreeArtifact() {
    return outputTreeArtifact;
  }

  @Override
  public ActionOwner getOwner() {
    return actionOwner;
  }

  @Override
  public boolean isShareable() {
    return true;
  }

  @Override
  public String getMnemonic() {
    return mnemonic;
  }

  @Override
  public NestedSet<Artifact> getTools() {
    return commonTools;
  }

  @Override
  public NestedSet<Artifact> getInputs() {
    return allInputs;
  }

  @Override
  public NestedSet<Artifact> getOriginalInputs() {
    return getInputs();
  }

  @Override
  public NestedSet<Artifact> getSchedulingDependencies() {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  @Override
  public NestedSet<Artifact> getMandatoryInputs() {
    return allInputs;
  }

  @Override
  public NestedSet<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext) {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    return ImmutableSet.of();
  }

  @Override
  public Collection<String> getClientEnvironmentVariables() {
    return spawnActionBuilder.buildForActionTemplate(actionOwner).getClientEnvironmentVariables();
  }

  @Override
  public String prettyPrint() {
    return "SpawnActionTemplate with output TreeArtifact " + outputTreeArtifact.prettyPrint();
  }

  @Override
  public String describe() {
    return "Executing " + mnemonic + " action on all files in " + inputTreeArtifact.prettyPrint();
  }

  @Override
  public String toString() {
    return prettyPrint();
  }

  /** Builder class to construct {@link SpawnActionTemplate} instances. */
  public static class Builder {
    private String actionTemplateMnemonic = "Unknown";
    private OutputPathMapper outputPathMapper;
    private CustomCommandLine commandLineTemplate;
    private PathFragment executable;

    private final SpecialArtifact inputTreeArtifact;
    private final SpecialArtifact outputTreeArtifact;
    private final NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
    private final NestedSetBuilder<Artifact> toolsBuilder = NestedSetBuilder.stableOrder();
    private final SpawnAction.Builder spawnActionBuilder;

    /**
     * Creates a {@link SpawnActionTemplate} builder.
     *
     * @param inputTreeArtifact the required input TreeArtifact.
     * @param outputTreeArtifact the required output TreeArtifact.
     */
    public Builder(SpecialArtifact inputTreeArtifact, SpecialArtifact outputTreeArtifact) {
      Preconditions.checkState(
          inputTreeArtifact.isTreeArtifact() && outputTreeArtifact.isTreeArtifact(),
          "Either %s or %s is not a TreeArtifact",
          inputTreeArtifact,
          outputTreeArtifact);
      this.inputTreeArtifact = inputTreeArtifact;
      this.outputTreeArtifact = outputTreeArtifact;
      this.spawnActionBuilder = new SpawnAction.Builder();
    }

    /**
     * Sets the mnemonics for both the {@link SpawnActionTemplate} and expanded {@link SpawnAction}.
     */
    @CanIgnoreReturnValue
    public Builder setMnemonics(String actionTemplateMnemonic, String expandedActionMnemonic) {
      this.actionTemplateMnemonic = actionTemplateMnemonic;
      spawnActionBuilder.setMnemonic(expandedActionMnemonic);
      return this;
    }

    /**
     * Adds common tool artifacts. All common tool artifacts will be added as tool artifacts for
     * expanded actions.
     */
    @CanIgnoreReturnValue
    public Builder addCommonTools(Iterable<Artifact> artifacts) {
      toolsBuilder.addAll(artifacts);
      spawnActionBuilder.addTools(artifacts);
      return this;
    }

    /**
     * Adds common tool artifacts. All common tool artifacts will be added as input tool artifacts
     * for expanded actions.
     */
    @CanIgnoreReturnValue
    public Builder addCommonTool(FilesToRunProvider tool) {
      toolsBuilder.addTransitive(tool.getFilesToRun());
      spawnActionBuilder.addTool(tool);
      return this;
    }

    /**
     * Adds common input artifacts. All common input artifacts will be added as input artifacts for
     * expanded actions.
     */
    @CanIgnoreReturnValue
    public Builder addCommonInputs(Iterable<Artifact> artifacts) {
      inputsBuilder.addAll(artifacts);
      spawnActionBuilder.addInputs(artifacts);
      return this;
    }

    /** Sets the map of environment variables for expanded actions. */
    @CanIgnoreReturnValue
    @Deprecated // TODO(ulfjack): Add env variables to the common environment, rather than replacing
    // it wholesale, which ignores --action_env (unless the client code explicitly handles it).
    public Builder setEnvironment(Map<String, String> environment) {
      spawnActionBuilder.setEnvironment(environment);
      return this;
    }

    /** Sets the map of execution info for expanded actions. */
    @CanIgnoreReturnValue
    public Builder setExecutionInfo(Map<String, String> executionInfo) {
      spawnActionBuilder.setExecutionInfo(executionInfo);
      return this;
    }

    /**
     * Sets the executable used by expanded actions as a configured target. Automatically adds the
     * files to run to the tools and uses the executable of the target as the executable.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link
     * #setExecutable(Artifact)} and {@link #setExecutable(PathFragment)}.
     */
    @CanIgnoreReturnValue
    public Builder setExecutable(FilesToRunProvider executableProvider) {
      Preconditions.checkArgument(
          executableProvider.getExecutable() != null, "The target does not have an executable");
      spawnActionBuilder.setExecutable(executableProvider);
      addCommonTool(executableProvider);
      this.executable = executableProvider.getExecutable().getExecPath();
      return this;
    }

    /**
     * Sets the executable path used by expanded actions. The path is interpreted relative to the
     * execution root, unless it's a bare file name.
     *
     * <p><b>Caution</b>: if the executable is a bare file name ("foo"), it will be interpreted
     * relative to PATH. See https://github.com/bazelbuild/bazel/issues/13189 for details. To avoid
     * that, use {@link #setExecutable(Artifact)} instead.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link
     * #setExecutable(Artifact)} and {@link #setExecutable(FilesToRunProvider)}.
     */
    @CanIgnoreReturnValue
    public Builder setExecutable(PathFragment executable) {
      spawnActionBuilder.setExecutable(executable);
      this.executable = executable;
      return this;
    }

    /**
     * Sets the executable artifact used by expanded actions. The path is interpreted relative to
     * the execution root.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link
     * #setExecutable(FilesToRunProvider)} and {@link #setExecutable(PathFragment)}.
     */
    @CanIgnoreReturnValue
    public Builder setExecutable(Artifact artifact) {
      spawnActionBuilder.setExecutable(artifact);
      addCommonTools(ImmutableList.of(artifact));
      this.executable = artifact.getExecPath();
      return this;
    }

    /** Sets the command line template used to expand actions. */
    @CanIgnoreReturnValue
    public Builder setCommandLineTemplate(CustomCommandLine commandLineTemplate) {
      this.commandLineTemplate = commandLineTemplate;
      return this;
    }

    /**
     * Sets the {@link OutputPathMapper} object used to get the parent-relative paths of output
     * {@link TreeFileArtifact}.
     */
    @CanIgnoreReturnValue
    public Builder setOutputPathMapper(OutputPathMapper outputPathMapper) {
      this.outputPathMapper = outputPathMapper;
      return this;
    }

    /**
     * Builds and returns the {@link SpawnActionTemplate} using the accumulated builder information.
     *
     * @param actionOwner the action owner of the SpawnActionTemplate to be built.
     */
    public SpawnActionTemplate build(ActionOwner actionOwner) {
      checkNotNull(executable);

      return new SpawnActionTemplate(
          checkNotNull(actionOwner),
          checkNotNull(inputTreeArtifact),
          checkNotNull(outputTreeArtifact),
          inputsBuilder.build(),
          toolsBuilder.build(),
          checkNotNull(outputPathMapper),
          checkNotNull(commandLineTemplate),
          actionTemplateMnemonic,
          spawnActionBuilder);
    }
  }

  private static final class ExpandedSpawnAction extends SpawnAction {
    ExpandedSpawnAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputs,
        Iterable<? extends Artifact> outputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment env,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        String mnemonic) {
      super(
          owner,
          tools,
          inputs,
          outputs,
          resourceSetOrBuilder,
          commandLines,
          env,
          executionInfo,
          progressMessage,
          mnemonic,
          /* outputPathsMode= */ OutputPathsMode.OFF);
    }

    @Override
    protected CommandLineLimits getCommandLineLimits() {
      return CommandLineLimits.UNLIMITED;
    }

    private static final class Builder extends SpawnAction.Builder {
      Builder(SpawnAction.Builder template) {
        super(template);
      }

      @Override
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
        return new ExpandedSpawnAction(
            owner,
            tools,
            inputsAndTools,
            outputs,
            resourceSetOrBuilder,
            commandLines,
            env,
            executionInfo,
            progressMessage,
            mnemonic);
      }
    }
  }
}
