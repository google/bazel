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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.starlarkbuildapi.ActionApi;
import com.google.devtools.build.lib.starlarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.vfs.BulkDeleter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.errorprone.annotations.ForOverride;
import java.io.IOException;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;

/**
 * Abstract implementation of Action which implements basic functionality: the inputs, outputs, and
 * toString method. Both input and output sets are immutable. Subclasses must be generally immutable
 * - see the documentation on {@link Action}.
 */
@Immutable
@ThreadSafe
public abstract class AbstractAction extends ActionKeyComputer implements Action, ActionApi {

  /**
   * An arbitrary default resource set. We assume that a typical subprocess is single-threaded
   * (i.e., uses one CPU core) and CPU-bound, and uses a small-ish amount of memory. In the past,
   * we've seen that assuming less than one core can lead to local overload. Unless you have data
   * indicating otherwise (for example, we've observed in the past that C++ linking can use large
   * amounts of memory), we suggest to use this default set.
   */
  // TODO(ulfjack): Collect actual data to confirm that this is an acceptable approximation.
  public static final ResourceSet DEFAULT_RESOURCE_SET = ResourceSet.createWithRamCpu(250, 1);

  private final ActionOwner owner;

  // The variable inputs is non-final only so that actions that discover their inputs can modify it.
  // Access through getInputs() in case it's overridden.
  @GuardedBy("this")
  private NestedSet<Artifact> inputs;

  /**
   * To save memory, this is either an {@link Artifact} for actions with a single output, or a
   * duplicate-free {@code Artifact[]} for actions with multiple outputs.
   */
  private final Object outputs;

  protected AbstractAction(
      ActionOwner owner, NestedSet<Artifact> inputs, Iterable<? extends Artifact> outputs) {
    this.owner = checkNotNull(owner);
    this.inputs = checkNotNull(inputs);
    this.outputs = singletonOrArray(outputs);
  }

  private static Object singletonOrArray(Iterable<? extends Artifact> outputs) {
    ImmutableSet<Artifact> set = ImmutableSet.copyOf(outputs);
    checkArgument(!set.isEmpty(), "Action outputs may not be empty");
    return set.size() == 1 ? Iterables.getOnlyElement(set) : set.toArray(Artifact[]::new);
  }

  @Override
  public final boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  @Override
  public final ActionOwner getOwner() {
    return owner;
  }

  @Override
  public final boolean inputsKnown() {
    if (!discoversInputs()) {
      return true;
    }
    synchronized (this) {
      return inputsDiscovered();
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Should be overridden along with {@link #discoverInputs}, {@link #inputsDiscovered}, {@link
   * #setInputsDiscovered} and {@link #getOriginalInputs} by actions that do input discovery.
   */
  @Override
  public boolean discoversInputs() {
    return false;
  }

  @Override
  @Nullable
  public NestedSet<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    throw new IllegalStateException("Not an input-discovering action: " + this);
  }

  @Override
  public final void resetDiscoveredInputs() {
    checkState(discoversInputs(), "Not an input-discovering action: %s", this);
    if (!inputsKnown()) {
      return;
    }
    synchronized (this) {
      inputs = getOriginalInputs();
      setInputsDiscovered(false);
    }
  }

  /**
   * Returns true if inputs have been discovered.
   *
   * <p>The value returned reflects the most recent call to {@link #setInputsDiscovered}. If {@link
   * #setInputsDiscovered} has never been called, returns false.
   *
   * <p>This method is used instead of a {@code boolean} field in this class in order to save memory
   * for actions which do not discover inputs.
   */
  @ForOverride
  @GuardedBy("this")
  protected boolean inputsDiscovered() {
    throw new IllegalStateException("Must be overridden by input-discovering action: " + this);
  }

  /**
   * Informs input-discovering actions about their discovery state so that they can correctly
   * implement {@link #inputsDiscovered}.
   */
  @ForOverride
  @GuardedBy("this")
  protected void setInputsDiscovered(boolean inputsDiscovered) {
    throw new IllegalStateException("Must be overridden by input-discovering action: " + this);
  }

  @Override
  public NestedSet<Artifact> getOriginalInputs() {
    checkState(!discoversInputs(), "Must be overridden by input-discovering action");
    return getInputs();
  }

  @Override
  public NestedSet<Artifact> getAllowedDerivedInputs() {
    throw new IllegalStateException(
        "Must be overridden for action that may have unknown inputs: " + this);
  }

  @Override
  public NestedSet<Artifact> getSchedulingDependencies() {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  /**
   * Should be called when the inputs of the action become known, that is, either during {@link
   * #discoverInputs(ActionExecutionContext)} or during {@link #execute(ActionExecutionContext)}.
   *
   * <p>When an action discovers inputs, it must have been called by the time {@code #execute()}
   * returns. It can be called both during {@code discoverInputs} and during {@code execute()}.
   *
   * <p>In addition to being called from action implementations, it will also be called by Bazel
   * itself when an action is loaded from the on-disk action cache.
   */
  @Override
  public synchronized void updateInputs(NestedSet<Artifact> inputs) {
    checkState(discoversInputs(), "Not an input-discovering action: %s", this);
    this.inputs = inputs;
    setInputsDiscovered(true);
  }

  @Override
  public NestedSet<Artifact> getTools() {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  @Override
  public synchronized NestedSet<Artifact> getInputs() {
    return inputs;
  }

  public ActionEnvironment getEnvironment() {
    return ActionEnvironment.EMPTY;
  }

  @Override
  public ImmutableMap<String, String> getEffectiveEnvironment(Map<String, String> clientEnv)
      throws CommandLineExpansionException {
    ActionEnvironment env = getEnvironment();
    Map<String, String> effectiveEnvironment =
        Maps.newLinkedHashMapWithExpectedSize(env.estimatedSize());
    env.resolve(effectiveEnvironment, clientEnv);
    return ImmutableMap.copyOf(effectiveEnvironment);
  }

  @Override
  public Collection<String> getClientEnvironmentVariables() {
    return getEnvironment().getInheritedEnv();
  }

  @Override
  public Collection<Artifact> getOutputs() {
    return outputs instanceof Artifact artifact
        ? ImmutableSet.of(artifact)
        : new OutputSet((Artifact[]) outputs);
  }

  /**
   * Simple {@link Set} wrapper around an array for actions with multiple outputs.
   *
   * <p>Implements {@link Set} so that passing an instance to {@link ImmutableSet#copyOf} results in
   * precise pre-sizing (since it is known to be duplicate-free). Note that the return type of
   * {@link ActionAnalysisMetadata#getOutputs} is {@link Collection}, so callers are unlikely to
   * expect a fast {@link #contains} implementation.
   */
  private static final class OutputSet extends AbstractSet<Artifact> {
    private final Artifact[] array;

    OutputSet(Artifact[] array) {
      this.array = array;
    }

    @Override
    public Iterator<Artifact> iterator() {
      return Iterators.forArray(array);
    }

    @Override
    public int size() {
      return array.length;
    }
  }

  @Nullable
  @Override
  public Artifact getPrimaryInput() {
    // The default behavior is to return the first input artifact.
    // Call through the method, not the field, because it may be overridden.
    return getFirstOrNull(getInputs());
  }

  @Nullable
  private Artifact getOriginalPrimaryInput() {
    // The default behavior is to return the first input artifact of the original input list (before
    // input discovery).
    // Call through the method, not the field, because it may be overridden.
    return getFirstOrNull(getOriginalInputs());
  }

  @Nullable
  private static Artifact getFirstOrNull(NestedSet<Artifact> inputs) {
    if (inputs.isEmpty()) {
      return null;
    } else if (inputs.isSingleton()) {
      return inputs.getSingleton();
    } else {
      return inputs.toList().getFirst();
    }
  }

  @Override
  public final Artifact getPrimaryOutput() {
    return outputs instanceof Artifact ? (Artifact) outputs : ((Artifact[]) outputs)[0];
  }

  @Override
  public NestedSet<Artifact> getMandatoryInputs() {
    return getInputs();
  }

  @Override
  public String toString() {
    return prettyPrint()
        + " ("
        + getMnemonic()
        + "["
        + getInputs().toList()
        + (inputsKnown() ? " -> " : ", unknown inputs -> ")
        + getOutputs()
        + "]"
        + ")";
  }

  @Override
  public abstract String getMnemonic();

  @Override
  public String describeKey() {
    return null;
  }

  @Override
  public boolean executeUnconditionally() {
    return false;
  }

  @Override
  public boolean isVolatile() {
    return false;
  }

  @Override
  public boolean isShareable() {
    return true;
  }

  @Override
  public boolean showsOutputUnconditionally() {
    return false;
  }

  @Nullable
  @Override
  public final String getProgressMessage() {
    return getProgressMessageChecked(null);
  }

  @Nullable
  @Override
  public final String getProgressMessage(RepositoryMapping mainRepositoryMapping) {
    checkNotNull(mainRepositoryMapping);
    return getProgressMessageChecked(mainRepositoryMapping);
  }

  @Nullable
  private String getProgressMessageChecked(@Nullable RepositoryMapping mainRepositoryMapping) {
    String message = getRawProgressMessage();
    if (message == null) {
      return null;
    }
    message = replaceProgressMessagePlaceholders(message, mainRepositoryMapping);
    return owner.isBuildConfigurationForTool() ? message + " [for tool]" : message;
  }

  private String replaceProgressMessagePlaceholders(
      String progressMessage, @Nullable RepositoryMapping mainRepositoryMapping) {
    if (progressMessage.contains("%{label}") && owner.getLabel() != null) {
      progressMessage =
          progressMessage.replace(
              "%{label}", owner.getLabel().getDisplayForm(mainRepositoryMapping));
    }
    if (progressMessage.contains("%{output}") && getPrimaryOutput() != null) {
      progressMessage =
          progressMessage.replace("%{output}", getPrimaryOutput().getRootRelativePathString());
    }
    if (progressMessage.contains("%{input}") && getOriginalPrimaryInput() != null) {
      progressMessage =
          progressMessage.replace(
              "%{input}", getOriginalPrimaryInput().getRootRelativePathString());
    }
    return progressMessage;
  }

  /**
   * Returns a progress message string that is specific for this action. This is then annotated with
   * additional information, currently the string '[for tool]' for actions in the tool
   * configurations.
   *
   * <p>A return value of null indicates no message should be reported.
   */
  protected String getRawProgressMessage() {
    // A cheesy default implementation.  Subclasses are invited to do something
    // more meaningful.
    return defaultProgressMessage();
  }

  private String defaultProgressMessage() {
    return getMnemonic() + " " + getPrimaryOutput().prettyPrint();
  }

  @Override
  public String prettyPrint() {
    return "action '" + describe() + "'";
  }

  @Override
  public void repr(Printer printer) {
    printer.append(prettyPrint()); // TODO(bazel-team): implement a readable representation
  }

  /**
   * Deletes all of the action's output files, if they exist. If any of the Artifacts refers to a
   * directory recursively removes the contents of the directory.
   *
   * @param execRoot the exec root in which this action is executed
   * @param bulkDeleter a helper to bulk delete outputs to avoid delegating to the filesystem
   * @param cleanupArchivedArtifacts whether to clean up archived tree artifacts
   */
  protected final void deleteOutputs(
      Path execRoot,
      ArtifactPathResolver pathResolver,
      @Nullable BulkDeleter bulkDeleter,
      boolean cleanupArchivedArtifacts)
      throws IOException, InterruptedException {
    Collection<Artifact> outputs = getOutputs();
    Iterable<Artifact> artifactsToDelete =
        cleanupArchivedArtifacts
            ? Iterables.concat(outputs, archivedTreeArtifactOutputs(outputs))
            : outputs;
    Iterable<PathFragment> additionalPathOutputsToDelete = getAdditionalPathOutputsToDelete();
    Iterable<PathFragment> directoryOutputsToDelete = getDirectoryOutputsToDelete();
    if (bulkDeleter != null) {
      bulkDeleter.bulkDelete(
          Iterables.concat(
              Artifact.asPathFragments(artifactsToDelete),
              additionalPathOutputsToDelete,
              directoryOutputsToDelete));
      return;
    }

    // TODO(b/185277726): Either we don't need a path resolver for actual deletion of output
    //  artifacts (likely) or we need to transform the fragments below (and then the resolver should
    //  be augmented to deal with exec-path PathFragments).
    for (Artifact output : artifactsToDelete) {
      deleteOutput(output, pathResolver);
    }

    for (PathFragment path : additionalPathOutputsToDelete) {
      deleteOutput(execRoot.getRelative(path), /*root=*/ null);
    }

    for (PathFragment path : directoryOutputsToDelete) {
      execRoot.getRelative(path).deleteTree();
    }
  }

  @ForOverride
  protected Iterable<PathFragment> getAdditionalPathOutputsToDelete() {
    return ImmutableList.of();
  }

  @ForOverride
  protected Iterable<PathFragment> getDirectoryOutputsToDelete() {
    return ImmutableList.of();
  }

  private static Iterable<Artifact> archivedTreeArtifactOutputs(Collection<Artifact> outputs) {
    return Iterables.transform(
        Iterables.filter(outputs, Artifact::isTreeArtifact),
        tree -> ArchivedTreeArtifact.createForTree((SpecialArtifact) tree));
  }

  /**
   * Remove an output artifact.
   *
   * <p>If the path refers to a directory, recursively removes the contents of the directory.
   *
   * @param output artifact to remove
   */
  protected static void deleteOutput(Artifact output, ArtifactPathResolver pathResolver)
      throws IOException {
    deleteOutput(
        pathResolver.toPath(output), pathResolver.transformRoot(output.getRoot().getRoot()));
  }

  /**
   * Helper method to remove an output file.
   *
   * <p>If the path refers to a directory, recursively removes the contents of the directory.
   *
   * @param path the output to remove
   * @param root the root containing the output. This is used to check that we don't delete
   *     arbitrary files in the file system.
   */
  public static void deleteOutput(Path path, @Nullable Root root) throws IOException {
    try {
      // Optimize for the common case: output artifacts are files.
      path.delete();
    } catch (IOException e) {
      // Handle a couple of scenarios where the output can still be deleted, but make sure we're not
      // deleting random files on the filesystem.
      if (root == null) {
        throw new IOException("null root", e);
      }
      if (!root.contains(path)) {
        throw new IOException(String.format("%s not under %s", path, root), e);
      }

      Path parentDir = path.getParentDirectory();
      if (root.contains(parentDir)) {
        try {
          parentDir.setWritable(true);
        } catch (IOException ignored) {
          // Intentionally ignored because we will fail below anyway.
        }
      }

      // Retry deleting after making the parent writable.
      if (path.isDirectory(Symlinks.NOFOLLOW)) {
        path.deleteTree();
      } else {
        path.delete();
      }
    }
  }

  @Override
  public void prepare(
      Path execRoot,
      ArtifactPathResolver pathResolver,
      @Nullable BulkDeleter bulkDeleter,
      boolean cleanupArchivedArtifacts)
      throws IOException, InterruptedException {
    deleteOutputs(execRoot, pathResolver, bulkDeleter, cleanupArchivedArtifacts);
  }

  @Override
  public final String describe() {
    String progressMessage = getProgressMessage();
    return progressMessage != null ? progressMessage : defaultProgressMessage();
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo(ActionKeyContext actionKeyContext)
      throws CommandLineExpansionException, InterruptedException {
    ExtraActionInfo.Builder result =
        ExtraActionInfo.newBuilder()
            .setOwner(owner.getLabel().toString())
            .setId(getKey(actionKeyContext, /* artifactExpander= */ null))
            .setMnemonic(getMnemonic());
    ImmutableList<AspectDescriptor> aspectDescriptors = owner.getAspectDescriptors();
    AspectDescriptor lastAspect =
        aspectDescriptors.isEmpty() ? null : Iterables.getLast(aspectDescriptors);
    if (lastAspect != null) {
      result.setAspectName(lastAspect.getAspectClass().getName());

      for (Map.Entry<String, Collection<String>> entry :
          lastAspect.getParameters().getAttributes().asMap().entrySet()) {
        result.putAspectParameters(
            entry.getKey(),
            ExtraActionInfo.StringList.newBuilder().addAllValue(entry.getValue()).build());
      }
    }
    return result;
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    return ImmutableSet.of();
  }

  /**
   * Returns input files that need to be present to allow extra_action rules to shadow this action
   * correctly when run remotely. This is at least the normal inputs of the action, but may include
   * other files as well. For example C(++) compilation may perform include file header scanning.
   * This needs to be mirrored by the extra_action rule. Called by {@link
   * com.google.devtools.build.lib.analysis.extra.ExtraAction} at execution time for actions that
   * return true for {link #discoversInputs}.
   *
   * <p>Returns null when a required value is missing and a Skyframe restart is required.
   *
   * @param actionExecutionContext Services in the scope of the action, like the Out/Err streams.
   * @throws ActionExecutionException only when code called from this method throws that exception.
   * @throws InterruptedException if interrupted
   */
  @Override
  public NestedSet<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  @Override
  public Depset getStarlarkInputs() {
    return Depset.of(Artifact.class, getInputs());
  }

  @Override
  public Depset getStarlarkOutputs() {
    return Depset.of(Artifact.class, NestedSetBuilder.wrap(Order.STABLE_ORDER, getOutputs()));
  }

  @Override
  @Nullable
  public Sequence<String> getStarlarkArgv() throws EvalException, InterruptedException {
    return null;
  }

  @Override
  @Nullable
  public Sequence<CommandLineArgsApi> getStarlarkArgs() {
    // Not all action types support returning Args.
    return null;
  }

  @Override
  @Nullable
  public String getStarlarkContent() throws IOException, EvalException, InterruptedException {
    return null;
  }

  @Override
  @Nullable
  public Dict<String, String> getStarlarkSubstitutions() throws EvalException {
    return null;
  }

  @Override
  public Dict<String, String> getExecutionInfoDict() {
    ImmutableMap<String, String> executionInfo = getExecutionInfo();
    return Dict.immutableCopyOf(executionInfo);
  }

  @Override
  public Dict<String, String> getEnv() {
    return Dict.immutableCopyOf(getEnvironment().getFixedEnv());
  }

  @Override
  public ImmutableMap<String, String> getExecProperties() {
    return owner.getExecProperties();
  }

  @Override
  @Nullable
  public PlatformInfo getExecutionPlatform() {
    return owner.getExecutionPlatform();
  }

  /**
   * Returns artifacts that should be subject to path mapping (see {@link Spawn#getPathMapper()},
   * but aren't inputs of the action.
   */
  public NestedSet<Artifact> getAdditionalArtifactsForPathMapping() {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }
}
