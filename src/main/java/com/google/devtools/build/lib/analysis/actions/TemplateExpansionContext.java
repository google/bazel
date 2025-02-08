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

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.SpawnResult;
import net.starlark.java.eval.EvalException;

/** The action context for {@link TemplateExpansionAction} instances */
public interface TemplateExpansionContext extends ActionContext {
  /** Placeholder for metadata associated with a template. */
  public record TemplateMetadata(
      Template template,
      Artifact primaryOutput,
      ImmutableList<Substitution> substitutions,
      boolean makeExecutable) {
    public TemplateMetadata {
      requireNonNull(template, "template");
      requireNonNull(primaryOutput, "primaryOutput");
      requireNonNull(substitutions, "substitutions");
    }

    public static Builder builder() {
      return new AutoBuilder_TemplateExpansionContext_TemplateMetadata_Builder();
    }

    /** Builder of {@link TemplateMetadata} instances. */
    @AutoBuilder
    public abstract static class Builder {
      public abstract Builder setTemplate(Template value);

      public abstract Builder setPrimaryOutput(Artifact value);

      public abstract Builder setSubstitutions(ImmutableList<Substitution> value);

      public abstract Builder setMakeExecutable(boolean value);

      public abstract TemplateMetadata build();
    }
  }

  ImmutableList<SpawnResult> expandTemplate(
      AbstractAction action, ActionExecutionContext ctx, TemplateMetadata templateMetadata)
      throws InterruptedException, EvalException, ExecException;
}
