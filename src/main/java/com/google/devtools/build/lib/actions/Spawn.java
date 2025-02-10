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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.util.DescribableExecutionUnit;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * An object representing a subprocess to be invoked, including its command and arguments, its
 * working directory, its environment, a boolean indicating whether remote execution is appropriate
 * for this command, and if so, the set of files it is expected to read and write.
 */
public interface Spawn extends DescribableExecutionUnit {
  /**
   * Out-of-band data for this spawn. This can be used to signal hints (hardware requirements, local
   * vs. remote) to the execution subsystem. This data can come from multiple places e.g. tags, hard
   * coded into rule logic, etc.
   *
   * <p>The data in this field can be in one of two forms and it is up to the caller of this method
   * to extract the data it cares about. Forms:
   *
   * <ul>
   *   <li>true key-value pairs
   *   <li>string tags from {@link *
   *       com.google.devtools.build.lib.analysis.test.TestTargetProperties#getExecutionInfo()}
   *       which can be added to the map as keys with arbitrary values (canonically the empty
   *       string)
   * </ul>
   *
   * <p>Callers of this method may also be interested in the {@link #getCombinedExecProperties()}.
   * See its javadoc for a comparison.
   */
  ImmutableMap<String, String> getExecutionInfo();

  /** Returns the command (the first element) and its arguments. */
  @Override
  ImmutableList<String> getArguments();

  /**
   * Returns the initial environment of the process. If null, the environment is inherited from the
   * parent process.
   */
  @Override
  ImmutableMap<String, String> getEnvironment();

  /**
   * Map of the execpath at which we expect the Fileset symlink trees, to a list of
   * FilesetOutputSymlinks which contains the details of the Symlink trees.
   */
  ImmutableMap<Artifact, FilesetOutputTree> getFilesetMappings();

  /**
   * Returns the list of files that are required to execute this spawn (e.g. the compiler binary),
   * in contrast to files necessary for the tool to do its work (e.g. source code to be compiled).
   *
   * <p>The returned set of files is a subset of what getInputFiles() returns.
   *
   * <p>This method explicitly does not expand runfiles trees. Pass the result to an appropriate
   * utility method on {@link com.google.devtools.build.lib.actions.Artifact} to expand them.
   *
   * <p>This is for use with persistent workers, so we can restart workers when their binaries have
   * changed.
   */
  NestedSet<? extends ActionInput> getToolFiles();

  /**
   * Returns the list of files that this command may read.
   *
   * <p>This method explicitly does not expand runfiles trees. Pass the result to an appropriate
   * utility method on {@link com.google.devtools.build.lib.actions.Artifact} to expand them.
   *
   * <p>This is for use with remote execution, so we can ship inputs before starting the command.
   * Order stability across multiple calls should be upheld for performance reasons.
   */
  NestedSet<? extends ActionInput> getInputFiles();

  /**
   * Returns the collection of files that this command will write. Callers should not mutate the
   * result.
   *
   * <p>This is for use with remote execution, so remote execution does not have to guess what
   * outputs the process writes. While the order does not affect the semantics, it should be stable
   * so it can be cached.
   */
  Collection<? extends ActionInput> getOutputFiles();

  /**
   * Returns the output files that should be considered to be "generated" by this spawn for purposes
   * of reconstructing the execution graph in {@link
   * com.google.devtools.build.lib.runtime.ExecutionGraphModule}.
   *
   * <p>This method is only used for constructing a model of the execution graph and does not affect
   * running this spawn in any way. It can be overridden to provide a clearer representation of the
   * execution graph in the face of oddities such as a difference between {@link #getOutputFiles}
   * and {@link Action#getOutputs}.
   */
  default Iterable<? extends ActionInput> getOutputEdgesForExecutionGraph() {
    return getOutputFiles();
  }

  /**
   * Returns true if {@code output} must be created for the action to succeed. Can be used by remote
   * execution implementations to mark a command as failed if it did not create an output, even if
   * the command itself exited with a successful exit code.
   *
   * <p>Some actions, like tests, may have optional files (like .xml files) that may be created, but
   * are not required, so their spawns should return false for those optional files. Note that in
   * general, every output in {@link ActionAnalysisMetadata#getOutputs} is checked for existence in
   * {@link com.google.devtools.build.lib.skyframe.SkyframeActionExecutor#checkOutputs}, so
   * eventually all those outputs must be produced by at least one {@code Spawn} for that action, or
   * locally by the action in some cases.
   *
   * <p>This method should not be overridden by any new Spawns if possible: outputs should be
   * mandatory.
   */
  default boolean isMandatoryOutput(ActionInput output) {
    return true;
  }

  /** Returns the resource owner for local fallback. */
  ActionExecutionMetadata getResourceOwner();

  /**
   * Returns the amount of resources needed for local execution. Calling this may trigger an
   * expensive computation: do not call unless actually needed!
   */
  ResourceSet getLocalResources() throws ExecException;

  /** Returns a mnemonic (string constant) for this kind of spawn. */
  @Override
  String getMnemonic();

  /**
   * Returns execution properties related to this spawn.
   *
   * <p>Note that this includes data from the execution platform's exec_properties as well as
   * target-level exec_properties.
   *
   * <p>Callers might also be interested in {@link #getExecutionInfo()} above. {@link
   * #getExecutionInfo()} can be set by multiple sources while this data is set via the {@code
   * exec_properties} attribute on targets and platforms.
   */
  default ImmutableMap<String, String> getCombinedExecProperties() {
    return getResourceOwner().getOwner().getExecProperties();
  }

  @Nullable
  PlatformInfo getExecutionPlatform();

  @Override
  @Nullable
  default Label getExecutionPlatformLabel() {
    PlatformInfo executionPlatform = getExecutionPlatform();
    return executionPlatform != null ? executionPlatform.label() : null;
  }

  @Override
  @Nullable
  default String getConfigurationChecksum() {
    return getResourceOwner().getOwner().getConfigurationChecksum();
  }

  @Override
  @Nullable
  default String getTargetDescription() {
    return getResourceOwner().getOwner().getDescription();
  }

  @Nullable
  default Label getTargetLabel() {
    return getResourceOwner().getOwner().getLabel();
  }

  /**
   * Returns the {@link PathMapper} that was used to create this spawn and that should be used to
   * map the paths of the spawn's inputs and outputs.
   */
  default PathMapper getPathMapper() {
    return PathMapper.NOOP;
  }
}
