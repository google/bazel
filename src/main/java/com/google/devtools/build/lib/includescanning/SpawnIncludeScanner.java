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
package com.google.devtools.build.lib.includescanning;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.includescanning.IncludeParser.GrepIncludesFileType;
import com.google.devtools.build.lib.includescanning.IncludeParser.Inclusion;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * C include scanner. Scans C/C++ source files using spawns to determine the bounding set of
 * transitively referenced include files. Has lifetime of a single build.
 */
public class SpawnIncludeScanner {
  /** The grep-includes tool is very lightweight, so don't use the default from AbstractAction. */
  private static final ResourceSet LOCAL_RESOURCES =
      ResourceSet.createWithRamCpu(/* memoryMb= */ 10, /* cpu= */ 1);

  private final Path execRoot;
  private OutputService outputService;
  private boolean inMemoryOutput;
  private final int remoteExtractionThreshold;
  private final SyscallCache syscallCache;

  /** Constructs a new SpawnIncludeScanner. */
  public SpawnIncludeScanner(
      Path execRoot, int remoteExtractionThreshold, SyscallCache syscallCache) {
    this.execRoot = execRoot;
    this.remoteExtractionThreshold = remoteExtractionThreshold;
    this.syscallCache = syscallCache;
  }

  public void setOutputService(OutputService outputService) {
    Preconditions.checkState(this.outputService == null);
    this.outputService = outputService;
  }

  public void setInMemoryOutput(boolean inMemoryOutput) {
    this.inMemoryOutput = inMemoryOutput;
  }

  @VisibleForTesting
  Path getIncludesOutput(
      Artifact src, ArtifactPathResolver resolver, GrepIncludesFileType fileType,
      boolean placeNextToFile) {
    if (placeNextToFile) {
      // If this is an output file, just place the grepped-file next to it. The directory is bound
      // to exist.
      return resolver.toPath(src)
          .getParentDirectory()
          .getRelative(src.getFilename() + ".blaze-grepped_includes_" + fileType);
    }
    return resolver.convertPath(execRoot)
        .getChild("blaze-grepped_includes_" + fileType.getFileType())
        .getRelative(src.getExecPath());
  }

  private PathFragment execPath(Path path) {
    return path.asFragment().relativeTo(execRoot.asFragment());
  }

  /** Returns whether {@code file} should be parsed using this include scanner. */
  boolean shouldParseRemotely(Artifact file) throws IOException {
    // We currently cannot remotely extract inclusions from files that aren't underneath a known
    // Blaze root (e.g. that are in /usr/include). Likely, it's not a good idea to look at those in
    // the first place as it means we have a non-hermetic build.
    // TODO(b/115503807): Fix underlying issue and consider turning this into a precondition check.
    if (file.getRoot().getRoot().isAbsolute()) {
      return false;
    }
    // Enable include scanning remotely when explicitly directed to via a flag.
    if (remoteExtractionThreshold == 0) {
      return true;
    }
    // Files written remotely that are not locally available should be scanned remotely to avoid the
    // bandwidth and disk space penalty of bringing them across.
    if (!outputService.isLocalOnly() && !file.isSourceArtifact()) {
      return true;
    }
    Path path = file.getPath();
    // Don't use syscallCache for a derived artifact: it might have been statted before it was
    // regenerated.
    FileStatus status =
        file.isSourceArtifact()
            ? syscallCache.statIfFound(path, Symlinks.FOLLOW)
            : path.statIfFound(Symlinks.FOLLOW);
    return status == null || status.getSize() > remoteExtractionThreshold;
  }

  /**
   * Action for grepping. Is used basically just for ActionStatusMessages (displaying the action
   * status to the user as it executes).
   */
  private static class GrepIncludesAction implements ActionExecutionMetadata {
    private static final String MNEMONIC = "GrepIncludes";

    /**
     * We don't use this object as the 'resource owner' of the spawn because we want to override the
     * mnemonic (among other things, see additional comments below). However, we do delegate
     * getOwner, and we may delegate other methods (e.g., getProgressMessage, describe) in the
     * future.
     */
    private final ActionExecutionMetadata actionExecutionMetadata;
    private final String progressMessage;

    GrepIncludesAction(ActionExecutionMetadata actionExecutionMetadata, PathFragment input) {
      this.actionExecutionMetadata = Preconditions.checkNotNull(actionExecutionMetadata);
      this.progressMessage = "Extracting include lines from " + input.getPathString();
    }

    @Override
    public ActionOwner getOwner() {
      return actionExecutionMetadata.getOwner();
    }

    @Override
    public String getMnemonic() {
      return MNEMONIC;
    }

    @Override
    public boolean isShareable() {
      return false;
    }

    @Override
    public String getProgressMessage() {
      return progressMessage;
    }

    @Override
    public String describe() {
      return progressMessage;
    }

    @Override
    public boolean inputsKnown() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean discoversInputs() {
      throw new UnsupportedOperationException();
    }

    @Override
    public NestedSet<Artifact> getTools() {
      throw new UnsupportedOperationException();
    }

    @Override
    public NestedSet<Artifact> getInputs() {
      return actionExecutionMetadata.getInputs();
    }

    @Override
    public NestedSet<Artifact> getOriginalInputs() {
      return actionExecutionMetadata.getInputs();
    }

    @Override
    public NestedSet<Artifact> getSchedulingDependencies() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableMap<String, String> getExecProperties() {
      return actionExecutionMetadata.getExecProperties();
    }

    @Override
    @Nullable
    public PlatformInfo getExecutionPlatform() {
      return actionExecutionMetadata.getExecutionPlatform();
    }

    @Override
    public ImmutableSet<Artifact> getOutputs() {
      // We currently compute orphaned outputs from the Action's list of outputs rather than from
      // the Spawn's list of outputs. If we return something here, we need to update that place as
      // well.
      return ImmutableSet.of();
    }

    @Override
    public Collection<String> getClientEnvironmentVariables() {
      return ImmutableSet.of();
    }

    @Override
    public Artifact getPrimaryInput() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Artifact getPrimaryOutput() {
      // This violates the contract of ActionExecutionMetadata. Classes that call here are working
      // around this returning null. At least some subclasses of CriticalPathComputer are affected.
      // TODO(ulfjack): Either fix this or change the contract. See b/111583707 for
      // CriticalPathComputer.
      return null;
    }

    @Override
    public NestedSet<Artifact> getMandatoryInputs() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getKey(
        ActionKeyContext actionKeyContext, @Nullable ArtifactExpander artifactExpander) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String describeKey() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String prettyPrint() {
      // This is called when running with -s (printing all subcommands).
      return "(include scanning)";
    }

    @Override
    public NestedSet<Artifact> getInputFilesForExtraAction(
        ActionExecutionContext actionExecutionContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSet<Artifact> getMandatoryOutputs() {
      // This is called to compute orphaned outputs. See getOutputs.
      return ImmutableSet.of();
    }

  }

  /** Extracts and returns inclusions from "file" using a spawn. */
  public Collection<Inclusion> extractInclusions(
      Artifact file,
      ActionExecutionMetadata actionExecutionMetadata,
      ActionExecutionContext actionExecutionContext,
      Artifact grepIncludes,
      GrepIncludesFileType fileType,
      boolean isOutputFile)
      throws IOException, ExecException, InterruptedException {
    boolean placeNextToFile = isOutputFile && !file.hasParent();
    Path output = getIncludesOutput(file, actionExecutionContext.getPathResolver(), fileType,
        placeNextToFile);
    if (!inMemoryOutput) {
      AbstractAction.deleteOutput(
          output,
          placeNextToFile
              ? actionExecutionContext.getPathResolver().transformRoot(file.getRoot().getRoot())
              : null);
      if (!placeNextToFile) {
        output.getParentDirectory().createDirectoryAndParents();
      }
    }

    InputStream dotIncludeStream =
        spawnGrep(
            file,
            execPath(output),
            inMemoryOutput,
            // We use {@link GrepIncludesAction} primarily to overwrite {@link Action#getMnemonic}.
            // You might be tempted to use a custom mnemonic on the Spawn instead, but rest assured
            // that _this does not work_. We call Spawn.getResourceOwner().getMnemonic() in a lot of
            // places, some of which are downstream from here, and doing so would cause the Spawn
            // and its owning ActionExecutionMetadata to be inconsistent with each other.
            new GrepIncludesAction(actionExecutionMetadata, file.getExecPath()),
            actionExecutionContext,
            grepIncludes,
            fileType);
    return dotIncludeStream == null
        ? IncludeParser.processIncludes(output)
        : IncludeParser.processIncludes(output, dotIncludeStream);
  }

  /**
   * Executes grep-includes.
   *
   * @param input the file to parse
   * @param outputExecPath the output file (exec path)
   * @param inMemoryOutput if true, return the contents of the output in the return value instead of
   *     to the given Path
   * @param resourceOwner the resource owner
   * @param actionExecutionContext services in the scope of the action. Like the Err/Out stream
   *     outputs.
   * @param fileType Either "c++" or "swig", passed verbatim to grep-includes.
   * @return The InputStream of the .includes file if inMemoryOutput feature retrieved it directly.
   *     Otherwise "null"
   * @throws ExecException if scanning fails
   */
  @Nullable
  private static InputStream spawnGrep(
      Artifact input,
      PathFragment outputExecPath,
      boolean inMemoryOutput,
      ActionExecutionMetadata resourceOwner,
      ActionExecutionContext actionExecutionContext,
      Artifact grepIncludes,
      GrepIncludesFileType fileType)
      throws ExecException, InterruptedException {
    ActionInput output = ActionInputHelper.fromPath(outputExecPath);
    NestedSet<? extends ActionInput> inputs =
        NestedSetBuilder.create(Order.STABLE_ORDER, grepIncludes, input);
    ImmutableSet<ActionInput> outputs = ImmutableSet.of(output);
    ImmutableList<String> command =
        ImmutableList.of(
            grepIncludes.getExecPathString(),
            input.getExecPath().getPathString(),
            outputExecPath.getPathString(),
            fileType.getFileType());

    ImmutableMap.Builder<String, String> execInfoBuilder = ImmutableMap.builder();
    execInfoBuilder.putAll(resourceOwner.getExecutionInfo());
    if (inMemoryOutput) {
      execInfoBuilder.put(
          ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS,
          outputExecPath.getPathString());
      // grep-includes writes output file to disk. If in-memory output is requested, no-local should
      // also be added, otherwise, grep-includes could be executed locally resulting output be
      // written to local disk.
      execInfoBuilder.put(ExecutionRequirements.NO_LOCAL, "");
    }
    execInfoBuilder.put(ExecutionRequirements.DO_NOT_REPORT, "");

    Spawn spawn =
        new SimpleSpawn(
            resourceOwner,
            command,
            ImmutableMap.of(),
            execInfoBuilder.buildOrThrow(),
            inputs,
            outputs,
            LOCAL_RESOURCES);

    actionExecutionContext.maybeReportSubcommand(spawn, /* spawnRunner= */ null);

    // Don't share the originalOutErr across spawnGrep calls. Doing so would not be thread-safe.
    FileOutErr originalOutErr = actionExecutionContext.getFileOutErr();
    FileOutErr grepOutErr = originalOutErr.childOutErr();
    SpawnStrategyResolver spawnStrategyResolver =
        actionExecutionContext.getContext(SpawnStrategyResolver.class);
    ActionExecutionContext spawnContext = actionExecutionContext.withFileOutErr(grepOutErr);
    List<SpawnResult> results;
    try {
      results = spawnStrategyResolver.exec(spawn, spawnContext);
      dump(spawnContext, actionExecutionContext);
    } catch (ExecException e) {
      dump(spawnContext, actionExecutionContext);
      throw e;
    }

    SpawnResult result = results.getFirst();
    ByteString includesContent = result.getInMemoryOutput(output);
    if (includesContent != null) {
      return includesContent.newInput();
    }
    return null;
  }

  private static void dump(ActionExecutionContext fromContext, ActionExecutionContext toContext) {
    if (fromContext.getFileOutErr().hasRecordedOutput()) {
      synchronized (toContext) {
        FileOutErr.dump(fromContext.getFileOutErr(), toContext.getFileOutErr());
      }
    }
  }
}
