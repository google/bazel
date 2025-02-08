// Copyright 2024 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule.DepSpec;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.StarlarkThreadContext;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.spelling.SpellChecker;
import net.starlark.java.syntax.Location;

/** Context object for a Starlark thread evaluating the MODULE.bazel file and files it includes. */
public class ModuleThreadContext extends StarlarkThreadContext {
  private boolean moduleCalled = false;
  private boolean hadNonModuleCall = false;
  private PathFragment currentModuleFilePath = LabelConstants.MODULE_DOT_BAZEL_FILE_NAME;

  private final boolean ignoreDevDeps;
  private final InterimModule.Builder module;
  private final ImmutableMap<String, NonRegistryOverride> builtinModules;
  @Nullable private final ImmutableMap<String, CompiledModuleFile> includeLabelToCompiledModuleFile;
  private final Map<String, DepSpec> deps = new LinkedHashMap<>();
  private final List<ModuleExtensionUsageBuilder> extensionUsageBuilders = new ArrayList<>();
  private final Map<String, ModuleOverride> overrides = new LinkedHashMap<>();
  private final Map<String, RepoNameUsage> repoNameUsages = new HashMap<>();

  private final Map<String, RepoOverride> overriddenRepos = new HashMap<>();
  private final Map<String, RepoOverride> overridingRepos = new HashMap<>();

  public static ModuleThreadContext fromOrFail(StarlarkThread thread, String what)
      throws EvalException {
    StarlarkThreadContext context = thread.getThreadLocal(StarlarkThreadContext.class);
    if (context instanceof ModuleThreadContext c) {
      return c;
    }
    throw Starlark.errorf("%s can only be called from MODULE.bazel and files it includes", what);
  }

  public ModuleThreadContext(
      ImmutableMap<String, NonRegistryOverride> builtinModules,
      ModuleKey key,
      boolean ignoreDevDeps,
      @Nullable ImmutableMap<String, CompiledModuleFile> includeLabelToCompiledModuleFile) {
    super(/* mainRepoMappingSupplier= */ null);
    module = InterimModule.builder().setKey(key);
    this.ignoreDevDeps = ignoreDevDeps;
    this.builtinModules = builtinModules;
    this.includeLabelToCompiledModuleFile = includeLabelToCompiledModuleFile;
  }

  record RepoOverride(
      String overriddenRepoName,
      String overridingRepoName,
      boolean mustExist,
      String extensionName,
      ImmutableList<StarlarkThread.CallStackEntry> stack) {
    Location location() {
      if (stack.size() < 2) {
        return Location.BUILTIN;
      }
      // Skip over the override_repo builtin frame.
      return stack.reverse().get(1).location;
    }
  }

  record RepoNameUsage(String how, ImmutableList<StarlarkThread.CallStackEntry> stack) {
    Location location() {
      if (stack.size() < 2) {
        return Location.BUILTIN;
      }
      // Skip over the override_repo builtin frame.
      return stack.reverse().get(1).location;
    }
  }

  public void addRepoNameUsage(
      String repoName, String how, ImmutableList<StarlarkThread.CallStackEntry> stack)
      throws EvalException {
    RepoNameUsage collision = repoNameUsages.put(repoName, new RepoNameUsage(how, stack));
    if (collision != null) {
      throw Starlark.errorf(
          "The repo name '%s' is already being used %s at %s",
          repoName, collision.how(), collision.location());
    }
  }

  /** Whether the {@code module()} directive has been called. */
  public boolean isModuleCalled() {
    return moduleCalled;
  }

  public void setModuleCalled() {
    moduleCalled = true;
  }

  /** Whether any directives other than {@code module()} have been called. */
  public boolean hadNonModuleCall() {
    return hadNonModuleCall;
  }

  public void setNonModuleCalled() {
    hadNonModuleCall = true;
  }

  public InterimModule.Builder getModuleBuilder() {
    return module;
  }

  public boolean shouldIgnoreDevDeps() {
    return ignoreDevDeps;
  }

  public void addDep(String repoName, DepSpec depSpec) {
    deps.put(repoName, depSpec);
  }

  List<ModuleExtensionUsageBuilder> getExtensionUsageBuilders() {
    return extensionUsageBuilders;
  }

  static class ModuleExtensionUsageBuilder {

    private final ModuleThreadContext context;
    private final String extensionBzlFile;
    private final String extensionName;
    private final boolean isolate;
    private final ArrayList<ModuleExtensionUsage.Proxy.Builder> proxyBuilders;
    private final HashBiMap<String, String> imports;
    private final Map<String, RepoOverride> repoOverrides;
    private final ImmutableList.Builder<Tag> tags;

    ModuleExtensionUsageBuilder(
        ModuleThreadContext context,
        String extensionBzlFile,
        String extensionName,
        boolean isolate) {
      this.context = context;
      this.extensionBzlFile = extensionBzlFile;
      this.extensionName = extensionName;
      this.isolate = isolate;
      this.proxyBuilders = new ArrayList<>();
      this.imports = HashBiMap.create();
      this.repoOverrides = new HashMap<>();
      this.tags = ImmutableList.builder();
    }

    ModuleThreadContext getContext() {
      return context;
    }

    void addProxyBuilder(ModuleExtensionUsage.Proxy.Builder builder) {
      proxyBuilders.add(builder);
    }

    boolean isForExtension(String extensionBzlFile, String extensionName) {
      return this.extensionBzlFile.equals(extensionBzlFile)
          && this.extensionName.equals(extensionName)
          && !this.isolate;
    }

    void addImport(
        String localRepoName,
        String exportedName,
        String byWhat,
        ImmutableList<StarlarkThread.CallStackEntry> stack)
        throws EvalException {
      RepositoryName.validateUserProvidedRepoName(localRepoName);
      RepositoryName.validateUserProvidedRepoName(exportedName);
      context.addRepoNameUsage(localRepoName, byWhat, stack);
      if (imports.containsValue(exportedName)) {
        String collisionRepoName = imports.inverse().get(exportedName);
        throw Starlark.errorf(
            "The repo exported as '%s' by module extension '%s' is already imported at %s",
            exportedName, extensionName, context.repoNameUsages.get(collisionRepoName).location());
      }
      imports.put(localRepoName, exportedName);
    }

    public void addRepoOverride(
        String overriddenRepoName,
        String overridingRepoName,
        boolean mustExist,
        ImmutableList<StarlarkThread.CallStackEntry> stack)
        throws EvalException {
      RepositoryName.validateUserProvidedRepoName(overriddenRepoName);
      RepositoryName.validateUserProvidedRepoName(overridingRepoName);
      RepoOverride collision =
          repoOverrides.put(
              overriddenRepoName,
              new RepoOverride(
                  overriddenRepoName, overridingRepoName, mustExist, extensionName, stack));
      if (collision != null) {
        throw Starlark.errorf(
            "The repo exported as '%s' by module extension '%s' is already overridden with '%s' at"
                + " %s",
            overriddenRepoName, extensionName, collision.overridingRepoName, collision.location());
      }
    }

    void addTag(Tag tag) {
      tags.add(tag);
    }

    ModuleExtensionUsage buildUsage() throws EvalException {
      var proxies = proxyBuilders.stream().map(p -> p.build()).collect(toImmutableList());
      var builder =
          ModuleExtensionUsage.builder()
              .setExtensionBzlFile(extensionBzlFile)
              .setExtensionName(extensionName)
              .setProxies(proxies)
              .setTags(tags.build());
      if (isolate) {
        ModuleExtensionUsage.Proxy onlyProxy = Iterables.getOnlyElement(proxies);
        if (onlyProxy.getProxyName().isEmpty()) {
          throw Starlark.errorf(
              "Isolated extension usage at %s must be assigned to a top-level variable",
              onlyProxy.getLocation());
        }
        builder.setIsolationKey(
            Optional.of(
                ModuleExtensionId.IsolationKey.create(
                    context.getModuleBuilder().getKey(), onlyProxy.getProxyName())));
      } else {
        builder.setIsolationKey(Optional.empty());
      }

      for (var override : repoOverrides.entrySet()) {
        String overriddenRepoName = override.getKey();
        String overridingRepoName = override.getValue().overridingRepoName;
        if (!context.repoNameUsages.containsKey(overridingRepoName)) {
          throw Starlark.errorf(
                  "The repo exported as '%s' by module extension '%s' is overridden with '%s', but"
                      + " no repo is visible under this name%s",
                  overriddenRepoName,
                  extensionName,
                  overridingRepoName,
                  SpellChecker.didYouMean(overridingRepoName, context.repoNameUsages.keySet()))
              .withCallStack(override.getValue().stack);
        }
        String importedAs = imports.inverse().get(overriddenRepoName);
        if (importedAs != null) {
          if (!override.getValue().mustExist) {
            throw Starlark.errorf(
                    "Cannot import repo '%s' that has been injected into module extension '%s' at"
                        + " %s. Please refer to @%s directly.",
                    overriddenRepoName,
                    extensionName,
                    override.getValue().location(),
                    overridingRepoName)
                .withCallStack(context.repoNameUsages.get(importedAs).stack);
          }
          context.overriddenRepos.put(importedAs, override.getValue());
        }
        context.overridingRepos.put(overridingRepoName, override.getValue());
      }
      builder.setRepoOverrides(
          ImmutableMap.copyOf(
              Maps.transformValues(
                  repoOverrides,
                  v ->
                      new ModuleExtensionUsage.RepoOverride(
                          v.overridingRepoName, v.mustExist, v.location()))));

      return builder.build();
    }
  }

  public void include(String includeLabel, StarlarkThread thread)
      throws InterruptedException, EvalException {
    if (includeLabelToCompiledModuleFile == null) {
      // This should never happen because compiling the non-root module file should have failed, way
      // before evaluation started.
      throw Starlark.errorf("trying to call `include()` from a non-root module");
    }
    var compiledModuleFile = includeLabelToCompiledModuleFile.get(includeLabel);
    if (compiledModuleFile == null) {
      // This should never happen because the file we're trying to include should have already been
      // compiled before evaluation started.
      throw Starlark.errorf("internal error; included file %s not compiled", includeLabel);
    }
    PathFragment includer = currentModuleFilePath;
    currentModuleFilePath = Label.parseCanonicalUnchecked(includeLabel).toPathFragment();
    compiledModuleFile.runOnThread(thread);
    currentModuleFilePath = includer;
  }

  public PathFragment getCurrentModuleFilePath() {
    return currentModuleFilePath;
  }

  public void addOverride(String moduleName, ModuleOverride override) throws EvalException {
    if (shouldIgnoreDevDeps()) {
      return;
    }
    ModuleOverride existingOverride = overrides.putIfAbsent(moduleName, override);
    if (existingOverride != null) {
      throw Starlark.errorf("multiple overrides for dep %s found", moduleName);
    }
  }

  public InterimModule buildModule(@Nullable Registry registry) throws EvalException {
    // Add builtin modules as default deps of the current module.
    for (String builtinModule : builtinModules.keySet()) {
      if (module.getKey().name().equals(builtinModule)) {
        // The built-in module does not depend on itself.
        continue;
      }
      deps.put(builtinModule, DepSpec.create(builtinModule, Version.EMPTY, -1));
      try {
        addRepoNameUsage(builtinModule, "as a built-in dependency", ImmutableList.of());
      } catch (EvalException e) {
        throw new EvalException(
            e.getMessage()
                + String.format(
                    ", '%s' is a built-in dependency and cannot be used by any 'bazel_dep' or"
                        + " 'use_repo' directive",
                    builtinModule),
            e);
      }
    }
    // Build module extension usages and the rest of the module.
    var extensionUsages = ImmutableList.<ModuleExtensionUsage>builder();
    for (var extensionUsageBuilder : extensionUsageBuilders) {
      if (extensionUsageBuilder.proxyBuilders.isEmpty()) {
        // This can happen for the special extension used for "use_repo_rule" calls.
        continue;
      }
      extensionUsages.add(extensionUsageBuilder.buildUsage());
    }
    // A repo cannot be both overriding and overridden. This ensures that repo overrides can be
    // applied to repo mappings in a single step (and also prevents cycles).
    Optional<String> overridingAndOverridden =
        overridingRepos.keySet().stream().filter(overriddenRepos::containsKey).findFirst();
    if (overridingAndOverridden.isPresent()) {
      var override = overridingRepos.get(overridingAndOverridden.get());
      var overrideOnOverride = overriddenRepos.get(overridingAndOverridden.get());
      throw Starlark.errorf(
              "The repo '%s' used as an override for '%s' in module extension '%s' is itself"
                  + " overridden with '%s' at %s, which is not supported.",
              override.overridingRepoName,
              override.overriddenRepoName,
              override.extensionName,
              overrideOnOverride.overridingRepoName,
              overrideOnOverride.location())
          .withCallStack(override.stack);
    }

    return module
        .setRegistry(registry)
        .setDeps(ImmutableMap.copyOf(deps))
        .setOriginalDeps(ImmutableMap.copyOf(deps))
        .setExtensionUsages(extensionUsages.build())
        .build();
  }

  public ImmutableMap<String, ModuleOverride> buildOverrides() {
    // Add overrides for builtin modules if there is no existing override for them.
    if (ModuleKey.ROOT.equals(module.getKey())) {
      for (String moduleName : builtinModules.keySet()) {
        overrides.putIfAbsent(moduleName, builtinModules.get(moduleName));
      }
    }
    return ImmutableMap.copyOf(overrides);
  }
}
