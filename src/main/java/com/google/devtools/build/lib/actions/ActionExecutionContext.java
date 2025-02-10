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

package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.cache.OutputMetadataStore;
import com.google.devtools.build.lib.analysis.SymlinkEntry;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.RunfileSymlinksMode;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.util.CommandDescriptionForm;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.common.options.OptionsProvider;
import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nullable;

/** A class that groups services in the scope of the action. Like the FileOutErr object. */
public class ActionExecutionContext implements Closeable, ActionContext.ActionContextRegistry {

  /**
   * A {@link RunfilesTree} implementation that wraps another one while overriding the path it
   * should be materialized at.
   */
  private static class OverriddenPathRunfilesTree implements RunfilesTree {
    private final PathFragment execPath;
    private final RunfilesTree wrapped;

    private OverriddenPathRunfilesTree(RunfilesTree wrapped, PathFragment execPath) {
      this.wrapped = wrapped;
      this.execPath = execPath;
    }

    @Override
    public PathFragment getExecPath() {
      return execPath;
    }

    @Override
    public Map<PathFragment, Artifact> getMapping() {
      return wrapped.getMapping();
    }

    @Override
    public NestedSet<Artifact> getArtifacts() {
      return wrapped.getArtifacts();
    }

    @Override
    public RunfileSymlinksMode getSymlinksMode() {
      return wrapped.getSymlinksMode();
    }

    @Override
    public boolean isBuildRunfileLinks() {
      return wrapped.isBuildRunfileLinks();
    }

    @Override
    public String getWorkspaceName() {
      return wrapped.getWorkspaceName();
    }

    @Override
    public NestedSet<Artifact> getArtifactsAtCanonicalLocationsForLogging() {
      return wrapped.getArtifactsAtCanonicalLocationsForLogging();
    }

    @Override
    public Iterable<PathFragment> getEmptyFilenamesForLogging() {
      return wrapped.getEmptyFilenamesForLogging();
    }

    @Override
    public NestedSet<SymlinkEntry> getSymlinksForLogging() {
      return wrapped.getSymlinksForLogging();
    }

    @Override
    public NestedSet<SymlinkEntry> getRootSymlinksForLogging() {
      return wrapped.getRootSymlinksForLogging();
    }

    @Nullable
    @Override
    public Artifact getRepoMappingManifestForLogging() {
      return wrapped.getRepoMappingManifestForLogging();
    }

    @Override
    public boolean isLegacyExternalRunfiles() {
      return wrapped.isLegacyExternalRunfiles();
    }

    @Override
    public boolean isMappingCached() {
      return wrapped.isMappingCached();
    }

    @Override
    public void fingerprint(
        ActionKeyContext actionKeyContext, Fingerprint fp, boolean digestAbsolutePaths) {
      wrapped.fingerprint(actionKeyContext, fp, digestAbsolutePaths);
    }
  }

  /**
   * An {@link InputMetadataProvider} wrapping another while overriding the materialization path of
   * a chosen runfiles tree.
   *
   * <p>The choice is made by passing in the runfiles tree artifact which represents the tree whose
   * path is is to be overridden.
   */
  private static class OverriddenRunfilesPathInputMetadataProvider
      implements InputMetadataProvider {
    private final InputMetadataProvider wrapped;
    private final ActionInput wrappedRunfilesArtifact;
    private final OverriddenPathRunfilesTree overriddenTree;

    private OverriddenRunfilesPathInputMetadataProvider(
        InputMetadataProvider wrapped, ActionInput wrappedRunfilesArtifact, PathFragment execPath) {
      this.wrapped = wrapped;
      this.wrappedRunfilesArtifact = wrappedRunfilesArtifact;
      this.overriddenTree =
          new OverriddenPathRunfilesTree(
              wrapped.getRunfilesMetadata(wrappedRunfilesArtifact).getRunfilesTree(), execPath);
    }

    @Nullable
    @Override
    public FileArtifactValue getInputMetadataChecked(ActionInput input)
        throws IOException, MissingDepExecException {
      return wrapped.getInputMetadataChecked(input);
    }

    @Nullable
    @Override
    public ActionInput getInput(String execPath) {
      return wrapped.getInput(execPath);
    }

    @Nullable
    @Override
    public RunfilesArtifactValue getRunfilesMetadata(ActionInput input) {
      RunfilesArtifactValue original = wrapped.getRunfilesMetadata(input);
      if (wrappedRunfilesArtifact.equals(input)) {
        return original.withOverriddenRunfilesTree(overriddenTree);
      } else {
        return original;
      }
    }

    @Override
    public ImmutableList<RunfilesTree> getRunfilesTrees() {
      return ImmutableList.of(overriddenTree);
    }

    @Override
    public FileSystem getFileSystemForInputResolution() {
      return wrapped.getFileSystemForInputResolution();
    }

  }

  /** Enum for --subcommands flag */
  public enum ShowSubcommands {
    TRUE(true, false), PRETTY_PRINT(true, true), FALSE(false, false);

    private final boolean shouldShowSubcommands;
    private final boolean prettyPrintArgs;

    ShowSubcommands(boolean shouldShowSubcommands, boolean prettyPrintArgs) {
      this.shouldShowSubcommands = shouldShowSubcommands;
      this.prettyPrintArgs = prettyPrintArgs;
    }
  }

  private final Executor executor;
  private final InputMetadataProvider inputMetadataProvider;
  private final ActionInputPrefetcher actionInputPrefetcher;
  private final ActionKeyContext actionKeyContext;
  private final OutputMetadataStore outputMetadataStore;
  private final boolean rewindingEnabled;
  private final LostInputsCheck lostInputsCheck;
  private final FileOutErr fileOutErr;
  private final ExtendedEventHandler eventHandler;
  private final ImmutableMap<String, String> clientEnv;
  private final ImmutableMap<Artifact, FilesetOutputTree> topLevelFilesets;
  @Nullable private final ArtifactExpander artifactExpander;
  @Nullable private final Environment env;

  @Nullable private final FileSystem actionFileSystem;
  @Nullable private final Object skyframeDepsResult;

  private FilesetOutputTree filesetOutput = FilesetOutputTree.EMPTY;

  private final ArtifactPathResolver pathResolver;
  private final DiscoveredModulesPruner discoveredModulesPruner;
  private final SyscallCache syscallCache;
  private final ThreadStateReceiver threadStateReceiverForMetrics;

  private ActionExecutionContext(
      Executor executor,
      InputMetadataProvider inputMetadataProvider,
      ActionInputPrefetcher actionInputPrefetcher,
      ActionKeyContext actionKeyContext,
      OutputMetadataStore outputMetadataStore,
      boolean rewindingEnabled,
      LostInputsCheck lostInputsCheck,
      FileOutErr fileOutErr,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv,
      ImmutableMap<Artifact, FilesetOutputTree> topLevelFilesets,
      @Nullable ArtifactExpander artifactExpander,
      @Nullable Environment env,
      @Nullable FileSystem actionFileSystem,
      @Nullable Object skyframeDepsResult,
      DiscoveredModulesPruner discoveredModulesPruner,
      SyscallCache syscallCache,
      ThreadStateReceiver threadStateReceiverForMetrics) {
    this.inputMetadataProvider = inputMetadataProvider;
    this.actionInputPrefetcher = actionInputPrefetcher;
    this.actionKeyContext = actionKeyContext;
    this.outputMetadataStore = outputMetadataStore;
    this.rewindingEnabled = rewindingEnabled;
    this.lostInputsCheck = lostInputsCheck;
    this.fileOutErr = fileOutErr;
    this.eventHandler = eventHandler;
    this.clientEnv = ImmutableMap.copyOf(clientEnv);
    this.topLevelFilesets = topLevelFilesets;
    this.executor = executor;
    this.artifactExpander = artifactExpander;
    this.env = env;
    this.actionFileSystem = actionFileSystem;
    this.skyframeDepsResult = skyframeDepsResult;
    this.threadStateReceiverForMetrics = threadStateReceiverForMetrics;
    this.pathResolver = ArtifactPathResolver.createPathResolver(actionFileSystem,
        // executor is only ever null in testing.
        executor == null ? null : executor.getExecRoot());
    this.discoveredModulesPruner = discoveredModulesPruner;
    this.syscallCache = syscallCache;
  }

  public ActionExecutionContext(
      Executor executor,
      InputMetadataProvider inputMetadataProvider,
      ActionInputPrefetcher actionInputPrefetcher,
      ActionKeyContext actionKeyContext,
      OutputMetadataStore outputMetadataStore,
      boolean rewindingEnabled,
      LostInputsCheck lostInputsCheck,
      FileOutErr fileOutErr,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv,
      ImmutableMap<Artifact, FilesetOutputTree> topLevelFilesets,
      ArtifactExpander artifactExpander,
      @Nullable FileSystem actionFileSystem,
      @Nullable Object skyframeDepsResult,
      DiscoveredModulesPruner discoveredModulesPruner,
      SyscallCache syscallCache,
      ThreadStateReceiver threadStateReceiverForMetrics) {
    this(
        executor,
        inputMetadataProvider,
        actionInputPrefetcher,
        actionKeyContext,
        outputMetadataStore,
        rewindingEnabled,
        lostInputsCheck,
        fileOutErr,
        eventHandler,
        clientEnv,
        topLevelFilesets,
        artifactExpander,
        /* env= */ null,
        actionFileSystem,
        skyframeDepsResult,
        discoveredModulesPruner,
        syscallCache,
        threadStateReceiverForMetrics);
  }

  public static ActionExecutionContext forInputDiscovery(
      Executor executor,
      InputMetadataProvider actionInputFileCache,
      ActionInputPrefetcher actionInputPrefetcher,
      ActionKeyContext actionKeyContext,
      OutputMetadataStore outputMetadataStore,
      boolean rewindingEnabled,
      LostInputsCheck lostInputsCheck,
      FileOutErr fileOutErr,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv,
      Environment env,
      @Nullable FileSystem actionFileSystem,
      DiscoveredModulesPruner discoveredModulesPruner,
      SyscallCache syscalls,
      ThreadStateReceiver threadStateReceiverForMetrics) {
    return new ActionExecutionContext(
        executor,
        actionInputFileCache,
        actionInputPrefetcher,
        actionKeyContext,
        outputMetadataStore,
        rewindingEnabled,
        lostInputsCheck,
        fileOutErr,
        eventHandler,
        clientEnv,
        ImmutableMap.of(),
        /* artifactExpander= */ null,
        env,
        actionFileSystem,
        /* skyframeDepsResult= */ null,
        discoveredModulesPruner,
        syscalls,
        threadStateReceiverForMetrics);
  }

  public ActionInputPrefetcher getActionInputPrefetcher() {
    return actionInputPrefetcher;
  }

  public InputMetadataProvider getInputMetadataProvider() {
    return inputMetadataProvider;
  }

  public OutputMetadataStore getOutputMetadataStore() {
    return outputMetadataStore;
  }

  public FileSystem getFileSystem() {
    if (actionFileSystem != null) {
      return actionFileSystem;
    }
    return executor.getFileSystem();
  }

  public Path getExecRoot() {
    return actionFileSystem != null
        ? actionFileSystem.getPath(executor.getExecRoot().asFragment())
        : executor.getExecRoot();
  }

  @Nullable
  public FileSystem getActionFileSystem() {
    return actionFileSystem;
  }

  public boolean isRewindingEnabled() {
    return rewindingEnabled;
  }

  public void checkForLostInputs() throws LostInputsActionExecutionException {
    lostInputsCheck.checkForLostInputs();
  }

  /**
   * Returns the path for an ActionInput.
   *
   * <p>Notably, in the future, we want any action-scoped artifacts to resolve paths using this
   * method instead of {@link Artifact#getPath} because that does not allow filesystem injection.
   *
   * <p>TODO(shahan): cleanup {@link Action}-scoped references to {@link Artifact#getPath} and
   * {@link Artifact#getRoot}.
   */
  public Path getInputPath(ActionInput input) {
    return pathResolver.toPath(input);
  }

  public Root getRoot(Artifact artifact) {
    return pathResolver.transformRoot(artifact.getRoot().getRoot());
  }

  public ArtifactPathResolver getPathResolver() {
    return pathResolver;
  }

  /**
   * Returns the command line options of the Blaze command being executed.
   */
  public OptionsProvider getOptions() {
    return executor.getOptions();
  }

  public Clock getClock() {
    return executor.getClock();
  }

  /**
   * Returns {@link BugReporter} to use when reporting bugs, instead of {@link
   * com.google.devtools.build.lib.bugreport.BugReport#sendBugReport}.
   */
  public BugReporter getBugReporter() {
    return executor.getBugReporter();
  }

  public ExtendedEventHandler getEventHandler() {
    return eventHandler;
  }

  public ImmutableMap<Artifact, FilesetOutputTree> getTopLevelFilesets() {
    return topLevelFilesets;
  }

  public FilesetOutputTree getFilesetOutput() {
    return filesetOutput;
  }

  public void setFilesetOutput(FilesetOutputTree filesetOutput) {
    checkState(
        this.filesetOutput.isEmpty(),
        "Unexpected reassignment of Fileset output from\n:%s to:\n%s",
        this.filesetOutput,
        filesetOutput);
    this.filesetOutput = checkNotNull(filesetOutput);
  }

  @Override
  @Nullable
  public <T extends ActionContext> T getContext(Class<T> type) {
    return executor.getContext(type);
  }

  /**
   * Report a subcommand event to this Executor's Reporter and, if action logging is enabled, post
   * it on its EventBus.
   */
  public void maybeReportSubcommand(Spawn spawn, @Nullable String spawnRunner) {
    ShowSubcommands showSubcommands = executor.reportsSubcommands();
    if (!showSubcommands.shouldShowSubcommands) {
      return;
    }

    StringBuilder reason = new StringBuilder();
    ActionOwner owner = spawn.getResourceOwner().getOwner();
    if (owner == null) {
      reason.append(spawn.getResourceOwner().prettyPrint());
    } else {
      reason.append(owner.getDescription());
      reason.append(" [");
      reason.append(spawn.getResourceOwner().prettyPrint());
      reason.append(", configuration: ");
      reason.append(owner.getConfigurationChecksum());
      if (owner.getExecutionPlatform() != null) {
        reason.append(", execution platform: ");
        reason.append(owner.getExecutionPlatform().label());
      }
      reason.append(", mnemonic: ");
      reason.append(spawn.getMnemonic());
      reason.append("]");
    }

    // We print this command out in such a way that it can safely be
    // copied+pasted as a Bourne shell command.  This is extremely valuable for
    // debugging.
    String message =
        CommandFailureUtils.describeCommand(
            CommandDescriptionForm.COMPLETE,
            showSubcommands.prettyPrintArgs,
            spawn.getArguments(),
            spawn.getEnvironment(),
            /* environmentVariablesToClear= */ null,
            getExecRoot().getPathString(),
            spawn.getConfigurationChecksum(),
            spawn.getExecutionPlatformLabel(),
            spawnRunner);
    getEventHandler().handle(Event.of(EventKind.SUBCOMMAND, null, "# " + reason + "\n" + message));
  }

  public ImmutableMap<String, String> getClientEnv() {
    return clientEnv;
  }

  public ArtifactExpander getArtifactExpander() {
    return artifactExpander;
  }

  @Nullable
  public Object getSkyframeDepsResult() {
    return skyframeDepsResult;
  }

  /**
   * Provide that {@code FileOutErr} that the action should use for redirecting the output and error
   * stream.
   */
  public FileOutErr getFileOutErr() {
    return fileOutErr;
  }

  /**
   * Provides a mechanism for the action to request values from Skyframe while it discovers inputs.
   */
  public Environment getEnvironmentForDiscoveringInputs() {
    return checkNotNull(env);
  }

  public ActionKeyContext getActionKeyContext() {
    return actionKeyContext;
  }

  public DiscoveredModulesPruner getDiscoveredModulesPruner() {
    return discoveredModulesPruner;
  }

  /** This only exists for loose header checking and as a helper for digest computations. */
  public SyscallCache getSyscallCache() {
    return syscallCache;
  }

  public ThreadStateReceiver getThreadStateReceiverForMetrics() {
    return threadStateReceiverForMetrics;
  }

  @Override
  public void close() throws IOException {
    // Ensure that we close both fileOutErr and actionFileSystem even if one throws.
    try {
      fileOutErr.close();
    } finally {
      if (actionFileSystem instanceof Closeable closeable) {
        closeable.close();
      }
    }
  }

  private ActionExecutionContext withInputMetadataProvider(
      InputMetadataProvider newInputMetadataProvider) {
    return new ActionExecutionContext(
        executor,
        newInputMetadataProvider,
        actionInputPrefetcher,
        actionKeyContext,
        outputMetadataStore,
        rewindingEnabled,
        lostInputsCheck,
        fileOutErr,
        eventHandler,
        clientEnv,
        topLevelFilesets,
        artifactExpander,
        env,
        actionFileSystem,
        skyframeDepsResult,
        discoveredModulesPruner,
        syscallCache,
        threadStateReceiverForMetrics);
  }

  /**
   * Creates a new {@link ActionExecutionContext} whose {@link InputMetadataProvider} has the given
   * {@link ActionInput}s as inputs.
   *
   * <p>Each {@link ActionInput} must be an output of the current {@link ActionExecutionContext} and
   * it must already have been built.
   */
  public ActionExecutionContext withOutputsAsInputs(
      Iterable<? extends ActionInput> additionalInputs) throws IOException, InterruptedException {
    ImmutableMap.Builder<ActionInput, FileArtifactValue> additionalInputMap =
        ImmutableMap.builder();

    for (ActionInput input : additionalInputs) {
      additionalInputMap.put(input, outputMetadataStore.getOutputMetadata(input));
    }

    StaticInputMetadataProvider additionalInputMetadata =
        new StaticInputMetadataProvider(additionalInputMap.buildOrThrow());

    return withInputMetadataProvider(
        new DelegatingPairInputMetadataProvider(additionalInputMetadata, inputMetadataProvider));
  }

  public ActionExecutionContext withOverriddenRunfilesPath(
      ActionInput overriddenRunfilesArtifact, PathFragment overrideRunfilesPath) {
    return withInputMetadataProvider(
        new OverriddenRunfilesPathInputMetadataProvider(
            inputMetadataProvider, overriddenRunfilesArtifact, overrideRunfilesPath));
  }

  /**
   * Allows us to create a new context that overrides the FileOutErr with another one. This is
   * useful for muting the output for example.
   */
  public ActionExecutionContext withFileOutErr(FileOutErr fileOutErr) {
    return new ActionExecutionContext(
        executor,
        inputMetadataProvider,
        actionInputPrefetcher,
        actionKeyContext,
        outputMetadataStore,
        rewindingEnabled,
        lostInputsCheck,
        fileOutErr,
        eventHandler,
        clientEnv,
        topLevelFilesets,
        artifactExpander,
        env,
        actionFileSystem,
        skyframeDepsResult,
        discoveredModulesPruner,
        syscallCache,
        threadStateReceiverForMetrics);
  }

  /**
   * A way of checking whether any lost inputs have been detected during the execution of this
   * action.
   */
  public interface LostInputsCheck {

    LostInputsCheck NONE = () -> {};

    /** Throws if inputs have been lost. */
    void checkForLostInputs() throws LostInputsActionExecutionException;
  }
}
