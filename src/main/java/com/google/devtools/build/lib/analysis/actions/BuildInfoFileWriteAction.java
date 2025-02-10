// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.WorkspaceStatusValue.BuildInfoKey;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator;

/**
 * Translates workspace status text files(<a
 * href="https://bazel.build/rules/lib/ctx#info_file">ctx.info_file</a> and <a
 * href="https://bazel.build/rules/lib/ctx#version_file">ctx.version_file</a>) to a language
 * consumable file and writes its contents to output. Keys and values are translated by the callback
 * translation_func Starlark method, and the output file format is generated according to the
 * template.
 *
 * <p>Action takes text file as an input and transforms it with a user provided Starlark callback
 * function to a dictionary of strings to strings. This dictionary is then used as substitutions to
 * expand the user provided template file.
 */
public final class BuildInfoFileWriteAction extends AbstractAction {

  private static final String GUID = "7e4657a6-dd09-435e-9423-51d4846aad4a";

  private final StarlarkFunction translationCallback;
  private final Artifact template;
  private final boolean isVolatile;
  private final StarlarkSemantics semantics;

  public BuildInfoFileWriteAction(
      ActionOwner owner,
      Artifact input,
      Artifact output,
      StarlarkFunction translationCallback,
      Artifact template,
      boolean isVolatile,
      StarlarkSemantics semantics) {
    super(
        owner,
        NestedSetBuilder.create(Order.STABLE_ORDER, input, template),
        ImmutableList.of(output));
    Preconditions.checkNotNull(translationCallback);
    Preconditions.checkNotNull(template);
    Preconditions.checkArgument(
        input.getArtifactOwner() instanceof BuildInfoKey,
        "input artifact of BuildInfoFileWriteAction must be one of workspace status artifacts:"
            + " ctx.info_file or ctx.version_file");
    this.translationCallback = translationCallback;
    this.template = template;
    this.isVolatile = isVolatile;
    this.semantics = semantics;
  }

  @Override
  public ActionResult execute(ActionExecutionContext ctx)
      throws ActionExecutionException, InterruptedException {
    Map<String, String> values = new HashMap<>();
    // Parse values from text file.
    try {
      Artifact valueFile = getPrimaryInput();
      values.putAll(WorkspaceStatusAction.parseValues(ctx.getInputPath(valueFile)));
    } catch (IOException e) {
      String message = "Failed to parse workspace status: " + e.getMessage();
      throw new ActionExecutionException(
          message,
          /* cause= */ e,
          /* action= */ this,
          /* catastrophe= */ false,
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(message)
                  .setExecution(
                      Execution.newBuilder().setCode(Execution.Code.SOURCE_INPUT_IO_EXCEPTION))
                  .build()));
    }
    // Call Starlark callback function which takes workspace status file's
    // content as an input and produces a dict which is written to the output.
    Object substitutionDictObject = null;
    try (Mutability mutability = Mutability.create("translate_build_info_file")) {
      try {
        StarlarkThread thread =
            StarlarkThread.create(
                mutability,
                semantics,
                isVolatile() ? "transform_version_file callback" : "transform_info_file callback",
                // Since the result of this thread is a String to String Dict, it should not result
                // in any reference-equals objects.
                SymbolGenerator.createTransient());
        substitutionDictObject =
            Starlark.positionalOnlyCall(thread, translationCallback, Dict.immutableCopyOf(values));
      } catch (EvalException e) {
        String message =
            String.format(
                "Error during translating %s status file : %s",
                isVolatile ? "volatile" : "stable", e);
        throw new ActionExecutionException(
            message,
            /* cause= */ e,
            /* action= */ this,
            /* catastrophe= */ false,
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setExecution(
                        Execution.newBuilder().setCode(Execution.Code.NON_ACTION_EXECUTION_FAILURE))
                    .build()));
      }
      Dict<String, String> substitutionDict = null;
      try {
        substitutionDict =
            Dict.cast(substitutionDictObject, String.class, String.class, "substitution_dict");
      } catch (EvalException e) {
        String message =
            "BuildInfo translation callback function is expected to return dict of strings to"
                + " strings, could not convert return value to Java type: "
                + e;
        throw new ActionExecutionException(
            message,
            /* cause= */ e,
            /* action= */ this,
            /* catastrophe= */ false,
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setExecution(
                        Execution.newBuilder().setCode(Execution.Code.NON_ACTION_EXECUTION_FAILURE))
                    .build()));
      }
      ImmutableList<Substitution> substitutionList =
          substitutionDict.entrySet().stream()
              .map(s -> Substitution.of(s.getKey(), s.getValue()))
              .collect(toImmutableList());

      return TemplateExpansionAction.execute(
          /* actionExecutionContext= */ ctx,
          /* action= */ this,
          TemplateExpansionContext.TemplateMetadata.builder()
              .setTemplate(Template.forArtifact(template))
              .setPrimaryOutput(getPrimaryOutput())
              .setSubstitutions(substitutionList)
              .setMakeExecutable(false)
              .build());
    }
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp) {
    fp.addString(GUID);
    fp.addBoolean(isVolatile);
    // Add Starlark function to the fingerprint.
    fp.addBytes(BazelModuleContext.of(translationCallback.getModule()).bzlTransitiveDigest());
  }

  @Override
  public String getMnemonic() {
    return "TranslateBuildInfo";
  }

  @Override
  protected String getRawProgressMessage() {
    if (isVolatile) {
      return "Translating volatile BuildInfo file";
    } else {
      return "Translating stable BuildInfo file";
    }
  }

  @Override
  public boolean executeUnconditionally() {
    return isVolatile;
  }

  @Override
  public boolean isVolatile() {
    return isVolatile;
  }
}
