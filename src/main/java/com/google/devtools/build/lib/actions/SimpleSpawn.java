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

package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import java.util.Collection;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/** Immutable implementation of a Spawn that does not perform any processing on the parameters. */
@Immutable
public final class SimpleSpawn implements Spawn {
  private final ActionExecutionMetadata owner;
  private final ImmutableList<String> arguments;
  private final ImmutableMap<String, String> environment;
  private final ImmutableMap<String, String> executionInfo;
  private final NestedSet<? extends ActionInput> inputs;
  private final NestedSet<? extends ActionInput> tools;
  private final ImmutableMap<Artifact, FilesetOutputTree> filesetMappings;
  private final ImmutableList<ActionInput> outputs;
  // If null, all outputs are mandatory.
  @Nullable private final Set<? extends ActionInput> mandatoryOutputs;
  private final PathMapper pathMapper;
  private final LocalResourcesSupplier localResourcesSupplier;
  @Nullable private ResourceSet localResourcesCached;

  private SimpleSpawn(
      ActionExecutionMetadata owner,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, String> executionInfo,
      ImmutableMap<Artifact, FilesetOutputTree> filesetMappings,
      NestedSet<? extends ActionInput> inputs,
      NestedSet<? extends ActionInput> tools,
      Collection<? extends ActionInput> outputs,
      @Nullable final Set<? extends ActionInput> mandatoryOutputs,
      @Nullable ResourceSet localResources,
      @Nullable LocalResourcesSupplier localResourcesSupplier,
      PathMapper pathMapper) {
    this.owner = Preconditions.checkNotNull(owner);
    this.arguments = Preconditions.checkNotNull(arguments);
    this.environment = Preconditions.checkNotNull(environment);
    this.executionInfo = Preconditions.checkNotNull(executionInfo);
    this.inputs = Preconditions.checkNotNull(inputs);
    this.tools = Preconditions.checkNotNull(tools);
    this.filesetMappings = filesetMappings;
    this.outputs = ImmutableList.copyOf(outputs);
    this.mandatoryOutputs = mandatoryOutputs;
    checkState(
        (localResourcesSupplier == null) != (localResources == null),
        "Exactly one must be null: %s %s",
        localResources,
        localResourcesSupplier);
    if (localResources != null) {
      this.localResourcesCached = localResources;
      this.localResourcesSupplier = null;
    } else {
      this.localResourcesSupplier = localResourcesSupplier;
      this.localResourcesCached = null;
    }
    this.pathMapper = pathMapper;
  }

  public SimpleSpawn(
      ActionExecutionMetadata owner,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, String> executionInfo,
      ImmutableMap<Artifact, FilesetOutputTree> filesetMappings,
      NestedSet<? extends ActionInput> inputs,
      NestedSet<? extends ActionInput> tools,
      Collection<? extends ActionInput> outputs,
      @Nullable Set<? extends ActionInput> mandatoryOutputs,
      ResourceSet localResources) {
    this(
        owner,
        arguments,
        environment,
        executionInfo,
        filesetMappings,
        inputs,
        tools,
        outputs,
        mandatoryOutputs,
        localResources,
        /* localResourcesSupplier= */ null,
        PathMapper.NOOP);
  }

  @SuppressWarnings("TooManyParameters")
  public SimpleSpawn(
      ActionExecutionMetadata owner,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, String> executionInfo,
      ImmutableMap<Artifact, FilesetOutputTree> filesetMappings,
      NestedSet<? extends ActionInput> inputs,
      NestedSet<? extends ActionInput> tools,
      Collection<? extends ActionInput> outputs,
      @Nullable Set<? extends ActionInput> mandatoryOutputs,
      LocalResourcesSupplier localResourcesSupplier) {
    this(
        owner,
        arguments,
        environment,
        executionInfo,
        filesetMappings,
        inputs,
        tools,
        outputs,
        mandatoryOutputs,
        /* localResources= */ null,
        localResourcesSupplier,
        PathMapper.NOOP);
  }

  public SimpleSpawn(
      ActionExecutionMetadata owner,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, String> executionInfo,
      ImmutableMap<Artifact, FilesetOutputTree> filesetMappings,
      NestedSet<? extends ActionInput> inputs,
      NestedSet<? extends ActionInput> tools,
      Collection<? extends ActionInput> outputs,
      @Nullable Set<? extends ActionInput> mandatoryOutputs,
      LocalResourcesSupplier localResourcesSupplier,
      PathMapper pathMapper) {
    this(
        owner,
        arguments,
        environment,
        executionInfo,
        filesetMappings,
        inputs,
        tools,
        outputs,
        mandatoryOutputs,
        null,
        localResourcesSupplier,
        pathMapper);
  }

  public SimpleSpawn(
      ActionExecutionMetadata owner,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, String> executionInfo,
      ImmutableMap<Artifact, FilesetOutputTree> filesetMappings,
      NestedSet<? extends ActionInput> inputs,
      NestedSet<? extends ActionInput> tools,
      Collection<? extends ActionInput> outputs,
      @Nullable Set<? extends ActionInput> mandatoryOutputs,
      ResourceSet localResources,
      PathMapper pathMapper) {
    this(
        owner,
        arguments,
        environment,
        executionInfo,
        filesetMappings,
        inputs,
        tools,
        outputs,
        mandatoryOutputs,
        localResources,
        null,
        pathMapper);
  }

  public SimpleSpawn(
      ActionExecutionMetadata owner,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, String> executionInfo,
      NestedSet<? extends ActionInput> inputs,
      Collection<Artifact> outputs,
      LocalResourcesSupplier localResourcesSupplier) {
    this(
        owner,
        arguments,
        environment,
        executionInfo,
        /* filesetMappings= */ ImmutableMap.of(),
        inputs,
        /* tools= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        outputs,
        /* mandatoryOutputs= */ null,
        localResourcesSupplier);
  }

  public SimpleSpawn(
      ActionExecutionMetadata owner,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, String> executionInfo,
      NestedSet<? extends ActionInput> inputs,
      Collection<? extends ActionInput> outputs,
      ResourceSet resourceSet) {
    this(
        owner,
        arguments,
        environment,
        executionInfo,
        ImmutableMap.of(),
        inputs,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        outputs,
        /* mandatoryOutputs= */ null,
        resourceSet);
  }

  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    return executionInfo;
  }

  @Override
  public ImmutableList<String> getArguments() {
    return arguments;
  }

  @Override
  public ImmutableMap<String, String> getEnvironment() {
    return environment;
  }

  @Override
  public ImmutableMap<Artifact, FilesetOutputTree> getFilesetMappings() {
    return filesetMappings;
  }

  @Override
  public NestedSet<? extends ActionInput> getInputFiles() {
    return inputs;
  }

  @Override
  public NestedSet<? extends ActionInput> getToolFiles() {
    return tools;
  }

  @Override
  public ImmutableList<ActionInput> getOutputFiles() {
    return outputs;
  }

  @Override
  public boolean isMandatoryOutput(ActionInput output) {
    return mandatoryOutputs == null || mandatoryOutputs.contains(output);
  }

  @Override
  public ActionExecutionMetadata getResourceOwner() {
    return owner;
  }

  @Override
  public ResourceSet getLocalResources() throws ExecException {
    ResourceSet result = localResourcesCached;
    if (result == null) {
      // Not expected to be called concurrently, and an idempotent computation if it is.
      result = localResourcesSupplier.get();
      localResourcesCached = result;
    }
    return result;
  }

  @Override
  public PathMapper getPathMapper() {
    return pathMapper;
  }

  @Override
  public String getMnemonic() {
    return owner.getMnemonic();
  }

  @Override
  @Nullable
  public PlatformInfo getExecutionPlatform() {
    return owner.getExecutionPlatform();
  }

  @Override
  public String toString() {
    return Spawns.prettyPrint(this);
  }

  /** Supplies resources needed for local execution. Result will be cached. */
  public interface LocalResourcesSupplier {
    ResourceSet get() throws ExecException;
  }
}
