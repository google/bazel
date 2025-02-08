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
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.util.OS;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/** Base implementation of a Spawn. */
@Immutable
public class BaseSpawn implements Spawn {
  private final ImmutableList<String> arguments;
  private final ImmutableMap<String, String> environment;
  private final ImmutableMap<String, String> executionInfo;
  private final ActionExecutionMetadata action;
  private final ResourceSetOrBuilder localResources;
  @Nullable private ResourceSet localResourcesCached;

  public BaseSpawn(
      List<String> arguments,
      Map<String, String> environment,
      Map<String, String> executionInfo,
      ActionExecutionMetadata action,
      ResourceSetOrBuilder localResources) {
    this.arguments = ImmutableList.copyOf(arguments);
    this.environment = ImmutableMap.copyOf(environment);
    this.executionInfo = ImmutableMap.copyOf(executionInfo);
    this.action = action;
    this.localResources = localResources;
  }

  @Override
  public final ImmutableMap<String, String> getExecutionInfo() {
    return executionInfo;
  }

  @Override
  public ImmutableList<String> getArguments() {
    // TODO(bazel-team): this method should be final, as the correct value of the args can be
    // injected in the ctor.
    return arguments;
  }

  @Override
  public ImmutableMap<Artifact, FilesetOutputTree> getFilesetMappings() {
    return ImmutableMap.of();
  }

  @Override
  public ImmutableMap<String, String> getEnvironment() {
    return environment;
  }

  @Override
  public NestedSet<? extends ActionInput> getToolFiles() {
    return action.getTools();
  }

  @Override
  public NestedSet<? extends ActionInput> getInputFiles() {
    return action.getInputs();
  }

  @Override
  public Collection<Artifact> getOutputFiles() {
    return action.getOutputs();
  }

  @Override
  public ActionExecutionMetadata getResourceOwner() {
    return action;
  }

  @Override
  public ResourceSet getLocalResources() throws ExecException {
    ResourceSet result = localResourcesCached;
    if (result == null) {
      // Not expected to be called concurrently, and an idempotent computation if it is.
      result =
          localResources.buildResourceSet(
              OS.getCurrent(), action.getInputs().memoizedFlattenAndGetSize());
      localResourcesCached = result;
    }
    return result;
  }

  @Override
  public String getMnemonic() {
    return action.getMnemonic();
  }

  @Override
  @Nullable
  public PlatformInfo getExecutionPlatform() {
    return action.getExecutionPlatform();
  }

  @Override
  public String toString() {
    return Spawns.prettyPrint(this);
  }
}
