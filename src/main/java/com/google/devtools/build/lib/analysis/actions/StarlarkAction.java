// Copyright 2019 The Bazel Authors. All rights reserved.
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

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionCacheAwareAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.StarlarkAction.Code;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/** A Starlark specific SpawnAction. */
public class StarlarkAction extends SpawnAction {

  private StarlarkAction(
      ActionOwner owner,
      NestedSet<Artifact> tools,
      NestedSet<Artifact> inputs,
      Iterable<Artifact> outputs,
      ResourceSetOrBuilder resourceSetOrBuilder,
      CommandLines commandLines,
      ActionEnvironment env,
      ImmutableMap<String, String> executionInfo,
      CharSequence progressMessage,
      String mnemonic,
      OutputPathsMode outputPathsMode) {
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
        outputPathsMode);
  }

  @VisibleForTesting
  public Optional<Artifact> getUnusedInputsList() {
    return Optional.empty();
  }

  @Override
  public NestedSet<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    return getInputs();
  }

  private static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setStarlarkAction(FailureDetails.StarlarkAction.newBuilder().setCode(detailedCode))
        .build();
  }

  @SafeVarargs
  private static NestedSet<Artifact> createInputs(NestedSet<Artifact>... inputsLists) {
    NestedSetBuilder<Artifact> nestedSetBuilder = new NestedSetBuilder<>(Order.STABLE_ORDER);
    for (NestedSet<Artifact> inputs : inputsLists) {
      nestedSetBuilder.addTransitive(inputs);
    }
    return nestedSetBuilder.build();
  }

  /** Builder class to construct {@link StarlarkAction} instances. */
  public static class Builder extends SpawnAction.Builder {

    private Optional<Artifact> unusedInputsList = Optional.empty();
    private Optional<Action> shadowedAction = Optional.empty();

    @CanIgnoreReturnValue
    public Builder setUnusedInputsList(Optional<Artifact> unusedInputsList) {
      this.unusedInputsList = unusedInputsList;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setShadowedAction(Optional<Action> shadowedAction) {
      this.shadowedAction = shadowedAction;
      return this;
    }

    /** Creates a SpawnAction. */
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
      if (unusedInputsList.isPresent()) {
        // Always download unused_inputs_list file from remote cache.
        executionInfo =
            ImmutableMap.<String, String>builderWithExpectedSize(executionInfo.size() + 1)
                .putAll(executionInfo)
                .put(
                    ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS,
                    unusedInputsList.get().getExecPathString())
                .buildOrThrow();
      }
      OutputPathsMode outputPathsMode = PathMappers.getOutputPathsMode(configuration);
      return unusedInputsList.isPresent() || shadowedAction.isPresent()
          ? new EnhancedStarlarkAction(
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
              outputPathsMode,
              unusedInputsList,
              shadowedAction)
          : new StarlarkAction(
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
              outputPathsMode);
    }
  }

  /** A {@link StarlarkAction} with {@code unused_inputs_list} and/or a shadowed action present. */
  private static final class EnhancedStarlarkAction extends StarlarkAction
      implements ActionCacheAwareAction {
    // All the inputs of the Starlark action including those listed in the unused inputs and
    // excluding the shadowed action inputs.
    private final NestedSet<Artifact> allStarlarkActionInputs;

    private final Optional<Artifact> unusedInputsList;
    private final Optional<Action> shadowedAction;
    private boolean inputsDiscovered = false;

    EnhancedStarlarkAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputs,
        Iterable<Artifact> outputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment env,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        String mnemonic,
        OutputPathsMode outputPathsMode,
        Optional<Artifact> unusedInputsList,
        Optional<Action> shadowedAction) {
      super(
          owner,
          tools,
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getInputs(), inputs)
              : inputs,
          outputs,
          resourceSetOrBuilder,
          commandLines,
          env,
          executionInfo,
          progressMessage,
          mnemonic,
          outputPathsMode);
      this.allStarlarkActionInputs = inputs;
      this.unusedInputsList = unusedInputsList;
      this.shadowedAction = shadowedAction;
    }

    @Override
    public NestedSet<Artifact> getSchedulingDependencies() {
      return shadowedAction.isPresent()
          ? shadowedAction.get().getSchedulingDependencies()
          : NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    @Override
    public Optional<Artifact> getUnusedInputsList() {
      return unusedInputsList;
    }

    @Override
    public boolean isShareable() {
      return unusedInputsList.isEmpty();
    }

    @Override
    public boolean discoversInputs() {
      return unusedInputsList.isPresent()
          || (shadowedAction.isPresent() && shadowedAction.get().discoversInputs());
    }

    @Override
    public NestedSet<Artifact> getOriginalInputs() {
      return allStarlarkActionInputs;
    }

    @Override
    protected boolean inputsDiscovered() {
      return inputsDiscovered;
    }

    @Override
    protected void setInputsDiscovered(boolean inputsDiscovered) {
      this.inputsDiscovered = inputsDiscovered;
    }

    @Override
    public NestedSet<Artifact> getAllowedDerivedInputs() {
      if (shadowedAction.isPresent()) {
        return createInputs(shadowedAction.get().getAllowedDerivedInputs(), getInputs());
      }
      return getInputs();
    }

    @Nullable
    @Override
    public NestedSet<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      // If the Starlark action shadows another action and the shadowed action discovers its inputs,
      // we get the shadowed action's discovered inputs and append it to the Starlark action inputs.
      if (shadowedAction.isPresent() && shadowedAction.get().discoversInputs()) {
        Action shadowedActionObj = shadowedAction.get();

        NestedSet<Artifact> oldInputs = getInputs();
        NestedSet<Artifact> inputFilesForExtraAction =
            shadowedActionObj.getInputFilesForExtraAction(actionExecutionContext);
        if (inputFilesForExtraAction == null) {
          return null;
        }
        updateInputs(
            createInputs(
                shadowedActionObj.getInputs(), inputFilesForExtraAction, allStarlarkActionInputs));
        return NestedSetBuilder.wrap(
            Order.STABLE_ORDER, Sets.difference(getInputs().toSet(), oldInputs.toSet()));
      }
      // Otherwise, we need to "re-discover" all the original inputs: the unused ones that were
      // removed might now be needed.
      updateInputs(allStarlarkActionInputs);
      return allStarlarkActionInputs;
    }

    private InputStream getUnusedInputListInputStream(
        ActionExecutionContext actionExecutionContext, List<SpawnResult> spawnResults)
        throws IOException, ExecException {

      // Check if the file is in-memory.
      // Note: SpawnActionContext guarantees that the first list entry exists and corresponds to the
      // executed spawn.
      Artifact unusedInputsListArtifact = unusedInputsList.get();
      ByteString content = spawnResults.get(0).getInMemoryOutput(unusedInputsListArtifact);
      if (content != null) {
        return content.newInput();
      }
      // Fallback to reading from disk.
      try {
        return actionExecutionContext
            .getPathResolver()
            .toPath(unusedInputsListArtifact)
            .getInputStream();
      } catch (FileNotFoundException e) {
        String message =
            "Action did not create expected output file listing unused inputs: "
                + unusedInputsListArtifact.getExecPathString();
        throw new UserExecException(
            e, createFailureDetail(message, Code.UNUSED_INPUT_LIST_FILE_NOT_FOUND));
      }
    }

    @Override
    protected void afterExecute(
        ActionExecutionContext actionExecutionContext,
        List<SpawnResult> spawnResults,
        PathMapper pathMapper)
        throws ExecException {
      if (unusedInputsList.isEmpty()) {
        return;
      }

      // Get all the action's inputs after execution which will include the shadowed action
      // discovered inputs
      NestedSet<Artifact> allInputs = getInputs();
      Map<String, Artifact> usedInputsByMappedPath = new HashMap<>();
      for (Artifact input : allInputs.toList()) {
        usedInputsByMappedPath.put(pathMapper.getMappedExecPathString(input), input);
      }
      // Bazel encodes file system paths as raw bytes stored in a Latin-1 encoded string, so we need
      // to make sure to also decode the unused input list as Latin-1.
      try (BufferedReader br =
          new BufferedReader(
              new InputStreamReader(
                  getUnusedInputListInputStream(actionExecutionContext, spawnResults),
                  ISO_8859_1))) {
        String line;
        while ((line = br.readLine()) != null) {
          line = line.trim();
          if (line.isEmpty()) {
            continue;
          }
          usedInputsByMappedPath.remove(line);
        }
      } catch (IOException e) {
        throw new EnvironmentalExecException(
            e,
            createFailureDetail("Unused inputs read failure", Code.UNUSED_INPUT_LIST_READ_FAILURE));
      }
      updateInputs(NestedSetBuilder.wrap(Order.STABLE_ORDER, usedInputsByMappedPath.values()));
    }

    @Override
    Spawn getSpawnForExtraActionSpawnInfo()
        throws CommandLineExpansionException, InterruptedException {
      if (shadowedAction.isPresent()) {
        return this.getSpawnForExtraActionSpawnInfo(
            createInputs(shadowedAction.get().getInputs(), allStarlarkActionInputs));
      }
      return this.getSpawnForExtraActionSpawnInfo(allStarlarkActionInputs);
    }

    @Nullable
    @Override
    public NestedSet<Artifact> getInputFilesForExtraAction(
        ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      if (shadowedAction.isEmpty()) {
        return allStarlarkActionInputs;
      }
      NestedSet<Artifact> inputFilesForExtraAction =
          shadowedAction.get().getInputFilesForExtraAction(actionExecutionContext);
      if (inputFilesForExtraAction == null) {
        return null;
      }
      return createInputs(inputFilesForExtraAction, allStarlarkActionInputs);
    }

    /**
     * StarlarkAction can contain `unused_input_list`, which rely on the action cache entry's file
     * list to determine the list of inputs for a subsequent run, taking into account
     * unused_input_list. Hence we need to store the inputs' execPaths in the action cache. The
     * StarlarkAction inputs' execPaths should also be stored in the action cache if it shadows
     * another action that discovers its inputs to avoid re-running input discovery after a
     * shutdown.
     */
    @Override
    public boolean storeInputsExecPathsInActionCache() {
      return unusedInputsList.isPresent()
          || (shadowedAction.isPresent() && shadowedAction.get().discoversInputs());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Adds the environment of the shadowed action, if any, to the execution spawn.
     */
    @Override
    public Spawn getSpawn(ActionExecutionContext actionExecutionContext)
        throws CommandLineExpansionException, InterruptedException {
      return getSpawn(
          actionExecutionContext,
          getEffectiveEnvironment(actionExecutionContext.getClientEnv()),
          /* envResolved= */ true,
          /* reportOutputs= */ true);
    }

    @Override
    public ImmutableMap<String, String> getEffectiveEnvironment(Map<String, String> clientEnv)
        throws CommandLineExpansionException {
      ActionEnvironment env = getEnvironment();
      Map<String, String> environment = Maps.newLinkedHashMapWithExpectedSize(env.estimatedSize());

      if (shadowedAction.isPresent()) {
        // Put all the variables of the shadowed action's environment
        environment.putAll(shadowedAction.get().getEffectiveEnvironment(clientEnv));
      }

      // This order guarantees that the Starlark action can overwrite any variable in its shadowed
      // action environment with a new value.
      env.resolve(environment, clientEnv);
      return ImmutableMap.copyOf(environment);
    }
  }
}
