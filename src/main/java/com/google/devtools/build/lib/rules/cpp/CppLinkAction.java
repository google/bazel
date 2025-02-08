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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OS;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

// LINT.IfChange
/** Action that represents a linking step. */
@ThreadCompatible
public final class CppLinkAction extends SpawnAction {

  private static final String LINK_GUID = "58ec78bd-1176-4e36-8143-439f656b181d";

  private static final LinkResourceSetBuilder resourceSetBuilder = new LinkResourceSetBuilder();
  private final ImmutableMap<String, String> toolchainEnv;

  /**
   * Use {@link CppLinkActionBuilder} to create instances of this class. Also see there for the
   * documentation of all parameters.
   *
   * <p>This constructor is intentionally private and is only to be called from {@link
   * CppLinkActionBuilder#build()}.
   */
  CppLinkAction(
      ActionOwner owner,
      String mnemonic,
      String progressMessage,
      NestedSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      LinkCommandLine linkCommandLine,
      ActionEnvironment env,
      ImmutableMap<String, String> toolchainEnv,
      ImmutableMap<String, String> executionRequirements,
      OutputPathsMode outputPathsMode)
      throws EvalException {
    super(
        owner,
        /* tools= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        inputs,
        outputs,
        /* resourceSetOrBuilder= */ resourceSetBuilder,
        /* commandLines= */ linkCommandLine.getCommandLines(),
        /* env= */ env,
        /* executionInfo= */ executionRequirements,
        /* progressMessage= */ progressMessage,
        /* mnemonic= */ mnemonic,
        /* outputPathsMode= */ outputPathsMode);

    this.toolchainEnv = toolchainEnv;
  }

  @Override
  public ImmutableMap<String, String> getEffectiveEnvironment(Map<String, String> clientEnv) {
    LinkedHashMap<String, String> result =
        Maps.newLinkedHashMapWithExpectedSize(getEnvironment().estimatedSize());
    getEnvironment().resolve(result, clientEnv);

    // TODO(fmeum): The environment is not path mapped.
    result.putAll(toolchainEnv);

    if (!getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_DARWIN)) {
      // This prevents gcc from writing the unpredictable (and often irrelevant)
      // value of getcwd() into the debug info.
      result.put("PWD", "/proc/self/cwd");
    }
    return ImmutableMap.copyOf(result);
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {
    fp.addString(LINK_GUID);
    super.computeKey(actionKeyContext, artifactExpander, fp);
    fp.addStringMap(toolchainEnv);
  }

  /** Estimates resource consumption when this action is executed locally. */
  @VisibleForTesting
  static class LinkResourceSetBuilder implements ResourceSetOrBuilder {
    @Override
    public ResourceSet buildResourceSet(OS os, int inputsCount) {
      final ResourceSet resourceSet;
      switch (os) {
        case DARWIN:
          resourceSet =
              ResourceSet.createWithRamCpu(/* memoryMb= */ 15 + 0.05 * inputsCount, /* cpu= */ 1);
          break;
        case LINUX:
          resourceSet =
              ResourceSet.createWithRamCpu(
                  /* memoryMb= */ Math.max(50, -100 + 0.1 * inputsCount), /* cpu= */ 1);
          break;
        default:
          resourceSet =
              ResourceSet.createWithRamCpu(/* memoryMb= */ 1500 + inputsCount, /* cpu= */ 1);
          break;
      }
      return resourceSet;
    }
  }
}
// LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/link/finalize_link_action.bzl)
