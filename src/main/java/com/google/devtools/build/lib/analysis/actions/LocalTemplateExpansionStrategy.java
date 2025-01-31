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

package com.google.devtools.build.lib.analysis.actions;


import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.StringUtilities;
import java.io.IOException;
import java.util.List;
import net.starlark.java.eval.EvalException;

/** Strategy to perform template expansion locally. */
public class LocalTemplateExpansionStrategy implements TemplateExpansionContext {
  public static final Class<LocalTemplateExpansionStrategy> TYPE =
      LocalTemplateExpansionStrategy.class;

  public static LocalTemplateExpansionStrategy INSTANCE = new LocalTemplateExpansionStrategy();

  @Override
  public ImmutableList<SpawnResult> expandTemplate(
      AbstractAction action,
      ActionExecutionContext ctx,
      TemplateExpansionContext.TemplateMetadata templateMetadata)
      throws InterruptedException, ExecException {
    try {
      // If writeOutputToFile may retain the writer, make sure that it doesn't capture the
      // expanded template string. Since expansion may throw and the writer must not, expand the
      // template once before calling writeOutputToFile. It is assumed to be deterministic, so if
      // it doesn't throw once, it won't throw again.
      final String expandedTemplate =
          getExpandedTemplateUnsafe(
              templateMetadata.template(), templateMetadata.substitutions(), ctx.getPathResolver());
      FileWriteActionContext fileWriteActionContext = ctx.getContext(FileWriteActionContext.class);
      DeterministicWriter deterministicWriter;
      if (fileWriteActionContext.mayRetainWriter()) {
        ArtifactPathResolver pathResolver = ctx.getPathResolver();
        deterministicWriter =
            out -> {
              try {
                out.write(
                    StringUnsafe.getInstance()
                        .getInternalStringBytes(
                            getExpandedTemplateUnsafe(
                                templateMetadata.template(),
                                templateMetadata.substitutions(),
                                pathResolver)));
              } catch (EvalException e) {
                throw new IllegalStateException(
                    "Template expansion is not deterministic, first succeeded and then failed with: "
                        + e.getMessage(),
                    e);
              }
            };
      } else {
        deterministicWriter =
            out -> out.write(StringUnsafe.getInstance().getInternalStringBytes(expandedTemplate));
      }
      return fileWriteActionContext.writeOutputToFile(
          action,
          ctx,
          deterministicWriter,
          templateMetadata.makeExecutable(),
          /* isRemotable= */ true);
    } catch (IOException | EvalException e) {
      throw new EnvironmentalExecException(
          e,
          FailureDetail.newBuilder()
              .setExecution(
                  Execution.newBuilder().setCode(Execution.Code.LOCAL_TEMPLATE_EXPANSION_FAILURE))
              .build());
    }
  }

  /**
   * Get the result of the template expansion prior to executing the action. TODO(b/110418949): Stop
   * public access to this method as it's unhealthy to evaluate the action result without the action
   * being executed.
   */
  public String getExpandedTemplateUnsafe(
      Template template, List<Substitution> substitutions, ArtifactPathResolver resolver)
      throws EvalException, IOException {
    String templateString;
    templateString = template.getContent(resolver);
    for (Substitution entry : substitutions) {
      templateString =
          StringUtilities.replaceAllLiteral(templateString, entry.getKey(), entry.getValue());
    }
    return templateString;
  }
}
