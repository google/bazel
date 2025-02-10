// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.android;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate.OutputPathMapper;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.starlarkbuildapi.android.AndroidStarlarkCommonApi;
import java.io.Serializable;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;

/** Common utilities for Starlark rules related to Android. */
public class AndroidStarlarkCommon
    implements AndroidStarlarkCommonApi<
        Artifact, JavaInfo, FilesToRunProvider, ConstraintValueInfo, StarlarkRuleContext> {

  @Override
  public String getSourceDirectoryRelativePathFromResource(Artifact resource) {
    return AndroidCommon.getSourceDirectoryRelativePathFromResource(resource).toString();
  }

  @Override
  public void createDexMergerActions(
      StarlarkRuleContext starlarkRuleContext,
      Artifact output,
      Artifact input,
      Sequence<?> dexopts, // <String> expected.
      FilesToRunProvider dexmerger,
      StarlarkInt minSdkVersion,
      Object desugarGlobals)
      throws EvalException, RuleErrorException {
    createTemplatedMergerActions(
        starlarkRuleContext.getRuleContext(),
        (SpecialArtifact) output,
        (SpecialArtifact) input,
        Sequence.cast(dexopts, String.class, "dexopts"),
        dexmerger,
        minSdkVersion.toInt("min_sdk_version"),
        desugarGlobals);
  }

  /**
   * Sets up a monodex {@code $dexmerger} actions for each dex archive in the given tree artifact
   * and puts the outputs in a tree artifact.
   */
  private static void createTemplatedMergerActions(
      RuleContext ruleContext,
      SpecialArtifact outputTree,
      SpecialArtifact inputTree,
      List<String> dexopts,
      FilesToRunProvider executable,
      int minSdkVersion,
      Object desugarGlobals) {
    SpawnActionTemplate.Builder dexmerger =
        new SpawnActionTemplate.Builder(inputTree, outputTree)
            .setExecutable(executable)
            .setMnemonics("DexShardsToMerge", "DexMerger")
            .setOutputPathMapper(
                (OutputPathMapper & Serializable) TreeFileArtifact::getParentRelativePath);
    CustomCommandLine.Builder commandLine =
        CustomCommandLine.builder()
            .addPlaceholderTreeArtifactExecPath("--input", inputTree)
            .addPlaceholderTreeArtifactExecPath("--output", outputTree)
            .add("--multidex=given_shard")
            .addAll(
                AndroidCommon.mergerDexopts(
                    ruleContext,
                    Iterables.filter(
                        dexopts, Predicates.not(Predicates.equalTo("--minimal-main-dex")))));
    if (minSdkVersion > 0) {
      commandLine.add("--min_sdk_version", Integer.toString(minSdkVersion));
    }
    Artifact desugarGlobalsArtifact = fromNoneable(desugarGlobals, Artifact.class);
    if (desugarGlobalsArtifact != null) {
      dexmerger.addCommonInputs(ImmutableList.of(desugarGlobalsArtifact));
      commandLine.addPath("--global_synthetics_path", desugarGlobalsArtifact.getExecPath());
    }
    dexmerger.setCommandLineTemplate(commandLine.build());
    ruleContext.registerAction(dexmerger.build(ruleContext.getActionOwner()));
  }

  /**
   * Checks if a "Noneable" object passed by Starlark is "None", which Java should treat as null.
   */
  private static boolean isNone(Object object) {
    return object == Starlark.NONE;
  }

  /**
   * Converts a "Noneable" Object passed by Starlark to an nullable object of the appropriate type.
   *
   * <p>Starlark "Noneable" types are passed in as an Object that may be either the correct type or
   * a Starlark.NONE object. Starlark will handle type checking, based on the appropriate @param
   * annotation, but we still need to do the actual cast (or conversion to null) ourselves.
   *
   * @param object the Noneable object
   * @param clazz the correct class, as defined in the @Param annotation
   * @param <T> the type to cast to
   * @return {@code null}, if the noneable argument was None, or the cast object, otherwise.
   */
  @Nullable
  private static <T> T fromNoneable(Object object, Class<T> clazz) {
    if (isNone(object)) {
      return null;
    }

    return clazz.cast(object);
  }
}
