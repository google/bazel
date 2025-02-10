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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * An Analysis phase interface for an {@link Action} or Action-like object, containing only
 * side-effect-free query methods for information needed during action analysis.
 */
public interface ActionAnalysisMetadata {

  /**
   * Return this key from {@link #getKey} to signify a failed key computation.
   *
   * <p>Actions that return this value should fail to execute.
   *
   * <p>Consumers must either gracefully handle multiple failed actions having the same key,
   * (recommended), or check against this value explicitly.
   */
  String KEY_ERROR = "1ea50e01-0349-4552-80cf-76cf520e8592";

  /**
   * Returns the owner of this executable if this executable can supply verbose information. This is
   * typically the rule that constructed it; see ActionOwner class comment for details.
   */
  ActionOwner getOwner();

  /**
   * Returns true if the action can be shared, i.e. multiple configured targets can create the same
   * action.
   *
   * <p>In theory, these should not exist, but in practice, they do.
   */
  boolean isShareable();

  /**
   * Returns a mnemonic (string constant) for this kind of action; written into the master log so
   * that the appropriate parser can be invoked for the output of the action. Effectively a public
   * method as the value is used by the extra_action feature to match actions.
   */
  String getMnemonic();

  /**
   * Returns a string encoding all of the significant behaviour of this Action that might affect the
   * output. The general contract of <code>getKey</code> is this: if the work to be performed by the
   * execution of this action changes, the key must change.
   *
   * <p>As a corollary, the build system is free to omit the execution of an Action <code>a1</code>
   * if (a) at some time in the past, it has already executed an Action <code>a0</code> with the
   * same key as <code>a1</code>, (b) the names and contents of the input files listed by <code>
   * a1.getInputs()</code> are identical to the names and contents of the files listed by <code>
   * a0.getInputs()</code>, and (c) the names and values in the client environment of the variables
   * listed by <code>a1.getClientEnvironmentVariables()</code> are identical to those listed by
   * <code>a0.getClientEnvironmentVariables()</code>.
   *
   * <p>Examples of changes that should affect the key are:
   *
   * <ul>
   *   <li>Changes to the BUILD file that materially affect the rule which gave rise to this Action.
   *   <li>Changes to the command-line options, environment, or other global configuration resources
   *       which affect the behaviour of this kind of Action (other than changes to the names of the
   *       input/output files, which are handled externally).
   *   <li>An upgrade to the build tools which changes the program logic of this kind of Action
   *       (typically this is achieved by incorporating a UUID into the key, which is changed each
   *       time the program logic of this action changes).
   * </ul>
   *
   * <p>Note the following exception: for actions that discover inputs, the key must change if any
   * input names change or else action validation may falsely validate.
   *
   * <p>In case the {@link ArtifactExpander} is not provided, the key is not guaranteed to be
   * correct. In fact, getting the key of an action is generally impossible until we have all the
   * information necessary to execute the action. An example of this is when arguments to an action
   * are defined as a lazy evaluation of Starlark over outputs of another action, after expanding
   * directories. In such case, if the dependent action outputs a tree artifact, creating a truly
   * unique key will depend on knowing the tree artifact contents. At analysis time, we only know
   * about the tree artifact directory and we find what is in it only after we execute that action.
   */
  String getKey(ActionKeyContext actionKeyContext, @Nullable ArtifactExpander artifactExpander)
      throws InterruptedException;

  /**
   * Returns a pretty string representation of this action, suitable for use in progress messages or
   * error messages.
   */
  String prettyPrint();

  /** Returns a description of this action. */
  String describe();

  /**
   * Returns the (possibly empty) set of tool artifacts that this action depends upon.
   *
   * <p>Tools are a subset of {@link #getInputs} and used by the workers to determine whether a
   * compiler has changed since the last time it was used. This should include all artifacts that
   * the tool does not dynamically reload / check on each unit of work - e.g. its own binary, the
   * JDK for Java binaries, shared libraries, ... but not a configuration file, if it reloads that
   * when it has changed.
   *
   * <p>If this method does not return exactly the right set of artifacts, the following can happen:
   * If an artifact that should be included is missing, the tool might not be restarted when it
   * should, and builds can become incorrect (example: The compiler binary is not part of this set,
   * then the compiler gets upgraded, but the worker strategy still reuses the old version). If an
   * artifact that should <em>not</em> be included is accidentally part of this set, the worker
   * process will be restarted more often that is necessary - e.g. if a file that is unique to each
   * unit of work, e.g. the source code that a compiler should compile for a compile action, is part
   * of this set, then the worker will never be reused and will be restarted for each unit of work.
   */
  NestedSet<Artifact> getTools();

  /**
   * Returns the input Artifacts that this Action depends upon. May be empty.
   *
   * <p>For actions that do input discovery, a different result may be returned before and after
   * action execution, because input discovery may add or remove inputs. The original input set may
   * be retrieved from {@link ActionExecutionMetadata#getOriginalInputs}.
   */
  NestedSet<Artifact> getInputs();

  /**
   * Returns this action's original inputs prior to input discovery.
   *
   * <p>Unlike {@link #getInputs}, the same result is returned before and after action execution.
   */
  NestedSet<Artifact> getOriginalInputs();

  /**
   * Returns the input Artifacts that must be built before the action can be executed, but are not
   * dependencies of the action in the action cache.
   *
   * <p>Useful for actions that do input discovery: then these Artifacts will be readable during
   * input discovery and then it can be decided which ones are actually necessary.
   */
  NestedSet<Artifact> getSchedulingDependencies();

  /**
   * Returns the environment variables from the client environment that this action depends on. May
   * be empty.
   *
   * <p>Warning: For optimization reasons, the available environment variables are restricted to
   * those white-listed on the command line. If actions want to specify additional client
   * environment variables to depend on, that restriction must be lifted in {@link
   * com.google.devtools.build.lib.runtime.CommandEnvironment}.
   */
  Collection<String> getClientEnvironmentVariables();

  /**
   * Returns the output artifacts that this action generates.
   *
   * <p>The returned {@link Collection} is immutable, non-empty, and duplicate-free.
   */
  Collection<Artifact> getOutputs();

  /**
   * Returns input files that need to be present to allow extra_action rules to shadow this action
   * correctly when run remotely. This is at least the normal inputs of the action, but may include
   * other files as well. For example C(++) compilation may perform include file header scanning.
   * This needs to be mirrored by the extra_action rule. Called by {@link
   * com.google.devtools.build.lib.analysis.extra.ExtraAction} at execution time for actions that
   * return true for {link ActionExecutionMetadata#discoversInputs}.
   *
   * @param actionExecutionContext Services in the scope of the action, like the Out/Err streams.
   * @throws ActionExecutionException only when code called from this method throws that exception.
   * @throws InterruptedException if interrupted
   */
  NestedSet<Artifact> getInputFilesForExtraAction(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException;

  /**
   * Returns the set of output Artifacts that are required to be saved. This is used to identify
   * items that would otherwise be potentially identified as orphaned (not consumed by any
   * downstream {@link Action}s and potentially discarded during the build process.
   *
   * <p>Do not call unless you are in the business of identifying orphaned artifacts: otherwise just
   * use {@link #getOutputs}.
   */
  ImmutableSet<Artifact> getMandatoryOutputs();

  /**
   * Returns the "primary" input of this action, or {@code null} if this action has no inputs.
   *
   * <p>For example, a C++ compile action would return the .cc file which is being compiled,
   * irrespective of the other inputs.
   */
  @Nullable
  Artifact getPrimaryInput();

  /**
   * Returns the "primary" output of this action, which is the same as the first artifact in {@link
   * #getOutputs}.
   *
   * <p>For example, the linked library would be the primary output of a LinkAction.
   *
   * <p>Never returns null.
   */
  Artifact getPrimaryOutput();

  /**
   * Returns an iterable of input Artifacts that MUST exist prior to executing an action. In other
   * words, in case when action is scheduled for execution, builder will ensure that all artifacts
   * returned by this method are present in the filesystem (artifact.getPath().exists() is true) or
   * action execution will be aborted with an error that input file does not exist. While in
   * majority of cases this method will return all action inputs, for some actions (e.g.
   * CppCompileAction) it can return a subset of inputs because that not all action inputs might be
   * mandatory for action execution to succeed (e.g. header files retrieved from *.d file from the
   * previous build).
   */
  NestedSet<Artifact> getMandatoryInputs();

  /**
   * Returns a String to String map containing the execution properties of this action.
   *
   * <p>These properties are typically inherited from {@link #getOwner()} and contain the
   * exec_properties provided on the target or execution platform level. Subclasses can override
   * this to return an empty map if that is more appropriate.
   */
  ImmutableMap<String, String> getExecProperties();

  /**
   * Returns the {@link PlatformInfo} platform this action should be executed on. If the execution
   * platform is {@code null}, then the host platform is assumed.
   */
  @Nullable
  PlatformInfo getExecutionPlatform();

  /**
   * Returns the execution requirements for this action, or an empty map if the action type does not
   * have access to execution requirements.
   */
  default ImmutableMap<String, String> getExecutionInfo() {
    return getExecProperties();
  }

  static ImmutableMap<String, String> mergeMaps(
      ImmutableMap<String, String> first, ImmutableMap<String, String> second) {
    if (first.isEmpty()) {
      return second;
    }
    if (second.isEmpty()) {
      return first;
    }
    return ImmutableMap.<String, String>builderWithExpectedSize(first.size() + second.size())
        .putAll(first)
        .putAll(second)
        .buildKeepingLast();
  }
}
