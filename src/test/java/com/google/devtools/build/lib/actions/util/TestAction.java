// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions.util;

import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;

/**
 * A dummy action for testing.  Its execution runs the specified
 * Runnable or Callable, which is defined by the test case,
 * and touches all the output files.
 */
public class TestAction extends AbstractAction {

  @SerializationConstant public static final Runnable NO_EFFECT = () -> {};

  private static boolean isOptional(Artifact artifact) {
    return artifact.getExecPath().getBaseName().endsWith(".optional");
  }

  private static NestedSet<Artifact> mandatoryArtifacts(NestedSet<Artifact> inputs) {
    return NestedSetBuilder.wrap(
        Order.STABLE_ORDER, Iterables.filter(inputs.toList(), a -> !isOptional(a)));
  }

  private static ImmutableList<Artifact> optionalArtifacts(NestedSet<Artifact> inputs) {
    return ImmutableList.copyOf(Iterables.filter(inputs.toList(), a -> isOptional(a)));
  }

  protected final Callable<Void> effect;
  private final NestedSet<Artifact> mandatoryInputs;
  private final ImmutableList<Artifact> optionalInputs;
  private boolean inputsDiscovered = false;

  /** Use this constructor if the effect can't throw exceptions. */
  public TestAction(Runnable effect, NestedSet<Artifact> inputs, ImmutableSet<Artifact> outputs) {
    this(Executors.callable(effect, null), inputs, outputs);
  }

  /**
   * Use this constructor if the effect can throw exceptions. Any checked exception thrown will be
   * repackaged as an ActionExecutionException.
   */
  public TestAction(
      Callable<Void> effect, NestedSet<Artifact> inputs, ImmutableSet<Artifact> outputs) {
    super(NULL_ACTION_OWNER, mandatoryArtifacts(inputs), outputs);
    this.mandatoryInputs = getInputs();
    this.optionalInputs = optionalArtifacts(inputs);
    this.effect = effect;
  }

  @Override
  public NestedSet<Artifact> getMandatoryInputs() {
    return mandatoryInputs;
  }

  @Override
  public boolean discoversInputs() {
    return !optionalInputs.isEmpty();
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
  public NestedSet<Artifact> getOriginalInputs() {
    return mandatoryInputs;
  }

  @Override
  public NestedSet<Artifact> getAllowedDerivedInputs() {
    return NestedSetBuilder.<Artifact>wrap(Order.STABLE_ORDER, optionalInputs);
  }

  @Override
  public NestedSet<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext) {
    Preconditions.checkState(discoversInputs(), this);
    NestedSet<Artifact> discoveredInputs =
        NestedSetBuilder.wrap(
            Order.STABLE_ORDER, Iterables.filter(optionalInputs, i -> i.getPath().exists()));
    updateInputs(
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(mandatoryInputs)
            .addTransitive(discoveredInputs)
            .build());
    return discoveredInputs;
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    for (Artifact artifact : getInputs().toList()) {
      // Do not check *.optional artifacts - artifacts with such extension are
      // used by tests to specify artifacts that may or may not be missing.
      // This is used, e.g., to test Blaze behavior when action has missing
      // input artifacts but still is successfully executed.
      if (!artifact.getPath().exists()) {
        throw new IllegalStateException("action's input file does not exist: "
            + artifact.getPath());
      }
    }

    try {
      effect.call();
    } catch (RuntimeException | Error | ActionExecutionException | InterruptedException e) {
      throw e;
    } catch (Exception e) {
      DetailedExitCode code = CrashFailureDetails.detailedExitCodeForThrowable(e);
      throw new ActionExecutionException(
          "TestAction failed due to exception: " + e.getMessage(), e, this, false, code);
    }

    try {
      for (Artifact artifact : getOutputs()) {
        FileSystemUtils.touchFile(artifact.getPath());
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    }

    return ActionResult.EMPTY;
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp) {
    fp.addPaths(Artifact.asSortedPathFragments(getOutputs()));
    fp.addPaths(Artifact.asSortedPathFragments(getMandatoryInputs().toList()));
  }

  @Override
  public String getMnemonic() {
    return "Test";
  }

  /** No-op action that has exactly one output. */
  @AutoCodec
  public static class DummyAction extends TestAction {
    @AutoCodec.Instantiator
    public DummyAction(NestedSet<Artifact> inputs, Artifact primaryOutput) {
      super(NO_EFFECT, inputs, ImmutableSet.of(primaryOutput));
    }

    public DummyAction(Artifact input, Artifact output) {
      this(NestedSetBuilder.create(Order.STABLE_ORDER, input), output);
    }

  }
}
