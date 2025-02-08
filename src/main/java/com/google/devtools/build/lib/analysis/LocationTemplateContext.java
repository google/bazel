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

package com.google.devtools.build.lib.analysis;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction;
import com.google.devtools.build.lib.analysis.stringtemplate.ExpansionException;
import com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Expands $(location) and $(locations) tags inside target attributes. You can specify something
 * like this in the BUILD file:
 *
 * <pre>
 * somerule(name='some name',
 *          someopt = [ '$(location //mypackage:myhelper)' ],
 *          ...)
 * </pre>
 *
 * and location will be substituted with //mypackage:myhelper executable output.
 *
 * <p>Note that this expander will always expand labels in srcs, deps, and tools attributes, with
 * data being optional.
 *
 * <p>DO NOT USE DIRECTLY! Use RuleContext.getExpander() instead.
 */
final class LocationTemplateContext implements TemplateContext {
  private final TemplateContext delegate;
  private final ImmutableMap<String, LocationFunction> functions;
  private final RepositoryMapping repositoryMapping;
  private final boolean windowsPath;
  private final String workspaceRunfilesDirectory;

  private LocationTemplateContext(
      TemplateContext delegate,
      Label root,
      Supplier<Map<Label, Collection<Artifact>>> locationMap,
      boolean execPaths,
      boolean legacyExternalRunfiles,
      RepositoryMapping repositoryMapping,
      boolean windowsPath,
      String workspaceRunfilesDirectory) {
    this.delegate = delegate;
    this.functions =
        LocationExpander.allLocationFunctions(root, locationMap, execPaths, legacyExternalRunfiles);
    this.repositoryMapping = repositoryMapping;
    this.windowsPath = windowsPath;
    this.workspaceRunfilesDirectory = workspaceRunfilesDirectory;
  }

  public LocationTemplateContext(
      TemplateContext delegate,
      RuleContext ruleContext,
      @Nullable ImmutableMap<Label, ImmutableCollection<Artifact>> labelMap,
      boolean execPaths,
      boolean allowData,
      boolean collectSrcs,
      boolean windowsPath) {
    this(
        delegate,
        ruleContext.getLabel(),
        // Use a memoizing supplier to avoid eagerly building the location map.
        Suppliers.memoize(
            () -> LocationExpander.buildLocationMap(ruleContext, labelMap, allowData, collectSrcs)),
        execPaths,
        ruleContext.getConfiguration().legacyExternalRunfiles(),
        ruleContext.getRule().getPackage().getRepositoryMapping(),
        windowsPath,
        ruleContext.getWorkspaceName());
  }

  @Override
  public String lookupVariable(String name) throws ExpansionException {
    String val = delegate.lookupVariable(name);
    if (windowsPath) {
      val = val.replace('/', '\\');
    }
    return val;
  }

  @Override
  public String lookupFunction(String name, String param) throws ExpansionException {
    String val = lookupFunctionImpl(name, param);
    if (windowsPath) {
      val = val.replace('/', '\\');
    }
    return val;
  }

  private String lookupFunctionImpl(String name, String param) throws ExpansionException {
    try {
      LocationFunction f = functions.get(name);
      if (f != null) {
        return f.apply(param, repositoryMapping, workspaceRunfilesDirectory);
      }
    } catch (IllegalStateException e) {
      throw new ExpansionException(e.getMessage(), e);
    }
    return delegate.lookupFunction(name, param);
  }
}
