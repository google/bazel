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

package com.google.devtools.build.lib.analysis;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.MakeVariableSupplier.MapBackedMakeVariableSupplier;
import com.google.devtools.build.lib.analysis.MakeVariableSupplier.TemplateVariableInfoBackedMakeVariableSupplier;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.stringtemplate.ExpansionException;
import com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext;
import com.google.devtools.build.lib.packages.Package;
import net.starlark.java.eval.Dict;

/**
 * Implements make variable expansion for make variables that depend on the configuration and the
 * target (not on behavior of the {@link ConfiguredTarget} implementation). Retrieved Make variable
 * value can be modified using {@link MakeVariableSupplier}
 */
public class ConfigurationMakeVariableContext implements TemplateContext {

  private final ImmutableList<? extends MakeVariableSupplier> allMakeVariableSuppliers;

  public ConfigurationMakeVariableContext(
      Package pkg,
      BuildConfigurationValue configuration,
      ImmutableList<TemplateVariableInfo> ruleTemplateVariableProviders) {
    this(pkg, configuration, ruleTemplateVariableProviders, ImmutableList.of());
  }

  public ConfigurationMakeVariableContext(
      Package pkg,
      BuildConfigurationValue configuration,
      ImmutableList<TemplateVariableInfo> ruleTemplateVariableProviders,
      Iterable<? extends MakeVariableSupplier> extraMakeVariableSuppliers) {
    this.allMakeVariableSuppliers =
        ImmutableList.<MakeVariableSupplier>builder()
            // These should be in priority order:
            // 1) extra suppliers passed in (assume the caller knows what they are doing)
            // 2) variables from the command-line
            // 3) package-level overrides (ie, vardef)
            // 4) variables from the rule (including from resolved toolchains)
            // 5) variables from the global configuration
            .addAll(Preconditions.checkNotNull(extraMakeVariableSuppliers))
            .add(new MapBackedMakeVariableSupplier(configuration.getCommandLineBuildVariables()))
            .add(new MapBackedMakeVariableSupplier(pkg.getMakeEnvironment()))
            .add(new TemplateVariableInfoBackedMakeVariableSupplier(ruleTemplateVariableProviders))
            .add(new MapBackedMakeVariableSupplier(configuration.getGlobalMakeEnvironment()))
            .build();
  }

  @Override
  public String lookupVariable(String name) throws ExpansionException {
    for (MakeVariableSupplier supplier : allMakeVariableSuppliers) {
      String variableValue = supplier.getMakeVariable(name);
      if (variableValue != null) {
        return variableValue;
      }
    }
    throw new ExpansionException(String.format("$(%s) not defined", name));
  }

  public Dict.Builder<String, String> collectMakeVariables() throws ExpansionException {
    Dict.Builder<String, String> map = Dict.builder();
    // Collect variables in the reverse order as in lookupMakeVariable
    // because each update is overwriting.
    for (MakeVariableSupplier supplier : allMakeVariableSuppliers.reverse()) {
      map.putAll(supplier.getAllMakeVariables());
    }
    return map;
  }

  @Override
  public String lookupFunction(String name, String param) throws ExpansionException {
    throw new ExpansionException(String.format("$(%s) not defined", name));
  }
}
