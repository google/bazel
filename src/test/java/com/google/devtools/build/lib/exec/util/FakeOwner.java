// Copyright 2017 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.BuildConfigurationEvent;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import java.util.Collection;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/** Fake implementation of {@link ActionExecutionMetadata} for testing. */
public class FakeOwner implements ActionExecutionMetadata {
  private final String mnemonic;
  private final String progressMessage;
  private final String ownerLabel;
  private final String ownerRuleKind;
  @Nullable private final Artifact primaryOutput;
  @Nullable private final PlatformInfo platform;
  private final ImmutableMap<String, String> combinedExecProperties;
  private final boolean isBuiltForToolConfiguration;

  FakeOwner(
      String mnemonic,
      String progressMessage,
      String ownerLabel,
      String ownerRuleKind,
      @Nullable Artifact primaryOutput,
      @Nullable PlatformInfo platform,
      ImmutableMap<String, String> combinedExecProperties,
      boolean isBuiltForToolConfiguration) {
    this.mnemonic = mnemonic;
    this.progressMessage = progressMessage;
    this.ownerLabel = checkNotNull(ownerLabel);
    this.ownerRuleKind = checkNotNull(ownerRuleKind);
    this.primaryOutput = primaryOutput;
    this.platform = platform;
    this.combinedExecProperties = combinedExecProperties;
    this.isBuiltForToolConfiguration = isBuiltForToolConfiguration;
  }

  private FakeOwner(
      String mnemonic, String progressMessage, String ownerLabel, @Nullable PlatformInfo platform) {
    this(
        mnemonic,
        progressMessage,
        ownerLabel,
        /* ownerRuleKind= */ "dummy-target-kind",
        /* primaryOutput= */ null,
        platform,
        ImmutableMap.of(),
        /* isBuiltForToolConfiguration= */ false);
  }

  public FakeOwner(String mnemonic, String progressMessage, String ownerLabel) {
    this(mnemonic, progressMessage, checkNotNull(ownerLabel), null);
  }

  @Override
  public ActionOwner getOwner() {
    return ActionOwner.createDummy(
        Label.parseCanonicalUnchecked(ownerLabel),
        new Location("dummy-file", 0, 0),
        ownerRuleKind,
        mnemonic,
        /* configurationChecksum= */ "configurationChecksum",
        new BuildConfigurationEvent(
            BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
            BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
        /* isToolConfiguration= */ isBuiltForToolConfiguration,
        /* executionPlatform= */ PlatformInfo.EMPTY_PLATFORM_INFO,
        /* aspectDescriptors= */ ImmutableList.of(),
        /* execProperties= */ combinedExecProperties);
  }

  @Override
  public boolean isShareable() {
    return false;
  }

  @Override
  public String getMnemonic() {
    return mnemonic;
  }

  @Override
  public String getProgressMessage() {
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
    throw new UnsupportedOperationException();
  }

  @Override
  public NestedSet<Artifact> getOriginalInputs() {
    throw new UnsupportedOperationException();
  }

  @Override
  public NestedSet<Artifact> getSchedulingDependencies() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSet<Artifact> getOutputs() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<String> getClientEnvironmentVariables() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Artifact getPrimaryInput() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Artifact getPrimaryOutput() {
    checkState(primaryOutput != null, "primaryOutput not set");
    return primaryOutput;
  }

  @Override
  public NestedSet<Artifact> getMandatoryInputs() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getKey(
      ActionKeyContext actionKeyContext, @Nullable ArtifactExpander artifactExpander) {
    return "MockOwner.getKey";
  }

  @Override
  public String describeKey() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String prettyPrint() {
    return "action '" + describe() + "'";
  }

  @Override
  public String describe() {
    return getProgressMessage();
  }

  @Override
  public NestedSet<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext) {
    return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableMap<String, String> getExecProperties() {
    return ImmutableMap.of();
  }

  @Nullable
  @Override
  public PlatformInfo getExecutionPlatform() {
    return platform;
  }
}
