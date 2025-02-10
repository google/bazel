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
package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.server.FailureDetails.TargetPatterns.Code.DEPENDENCY_NOT_FOUND;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.analysis.ProjectResolutionException;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.Scope;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.Label.PackageContext;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.runtime.StarlarkOptionsParser;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * SkyFunction that creates the {@link BuildOptionsScopeValue} for a given {@link BuildOptions}.
 * This SkyFunction is responsible for the following:
 *
 * <ul>
 *   <li>Resolving the {@link Scope.ScopeType} for each scoped flag if not already resolved.
 *   <li>Getting the PROJECT.scl files for each flag scoped with {@link Scope.ScopeType.PROJECT}.
 *   <li>Looking up {@link ProjectValue} for scoped flags that have PROJECT.scl files to get the
 *       list of active directories that define the scope of the flag.
 * </ul>
 */
public final class BuildOptionsScopeFunction implements SkyFunction {

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws BuildOptionsScopeFunctionException, InterruptedException {
    BuildOptionsScopeValue.Key key = (BuildOptionsScopeValue.Key) skyKey.argument();
    BuildOptions.Builder fullyResolvedBuildOptionsBuilder = key.getBuildOptions().toBuilder();
    LinkedHashMap<Label, Scope> scopes = new LinkedHashMap<>();
    for (Label scopedFlag : key.getFlagsWithIncompleteScopeInfo()) {
      Scope.ScopeType scopeType = key.getBuildOptions().getScopeTypeMap().get(scopedFlag);
      if (scopeType == null) {
        scopeType = getScopeType(env, scopedFlag, scopedFlag.getPackageIdentifier());
        if (scopeType == null) {
          return null;
        }
      }
      scopes.put(scopedFlag, new Scope(scopeType, null));

      // this is needed because the final BuildOptions used to create the BuildConfigurationKey
      // needs to have the scopeType set for all starlark flags.
      fullyResolvedBuildOptionsBuilder =
          fullyResolvedBuildOptionsBuilder.addScopeType(scopedFlag, scopeType);
    }

    // get PROJECT.scl files for each scoped flag that is not universal
    ImmutableMultimap<Label, Label> projectFiles;
    try {
      projectFiles = findProjectFiles(scopes, env);
    } catch (ProjectResolutionException e) {
      throw new BuildOptionsScopeFunctionException(e);
    }

    if (projectFiles == null) {
      return null;
    }

    // look up ProjectValue for scoped flags that have PROJECT.scl files to get the list of
    // active directories that define the scope of the flag.
    Map<Label, SkyKey> projectValueSkyKeysMap = new HashMap<>();
    for (Label projectScopedFlag : projectFiles.keySet()) {
      if (!projectFiles.get(projectScopedFlag).isEmpty()) {
        ProjectValue.Key projectKey =
            new ProjectValue.Key(projectFiles.get(projectScopedFlag).asList().get(0));
        projectValueSkyKeysMap.put(projectScopedFlag, projectKey);
      }
    }

    SkyframeLookupResult projectValuesLookUpResult =
        env.getValuesAndExceptions(projectValueSkyKeysMap.values());

    if (env.valuesMissing()) {
      return null;
    }

    for (Map.Entry<Label, SkyKey> entry : projectValueSkyKeysMap.entrySet()) {
      Label projectScopedFlag = entry.getKey();
      ProjectValue projectValue = (ProjectValue) projectValuesLookUpResult.get(entry.getValue());
      scopes.put(
          projectScopedFlag,
          new Scope(
              scopes.get(projectScopedFlag).getScopeType(),
              projectValue.getDefaultActiveDirectory().isEmpty()
                  ? null
                  : new Scope.ScopeDefinition(projectValue.getDefaultActiveDirectory())));
    }

    return BuildOptionsScopeValue.create(
        fullyResolvedBuildOptionsBuilder.build(),
        Lists.newArrayList(projectValueSkyKeysMap.keySet()),
        scopes);
  }

  /** TODO: b/384057043 - deduplicate this method in several places in a follow up CL. */
  @Nullable
  private ImmutableMultimap<Label, Label> findProjectFiles(
      Map<Label, Scope> scopes, Environment env)
      throws InterruptedException, ProjectResolutionException {

    Map<Label, ProjectFilesLookupValue.Key> targetsToSkyKeys = new HashMap<>();
    for (Label starlarkOption : scopes.keySet()) {
      if (scopes.get(starlarkOption).getScopeType() == Scope.ScopeType.PROJECT) {
        targetsToSkyKeys.put(
            starlarkOption, ProjectFilesLookupValue.key(starlarkOption.getPackageIdentifier()));
      }
    }

    Map<Label, ProjectFilesLookupValue> projectFilesLookupValues = new HashMap<>();
    for (Map.Entry<Label, ProjectFilesLookupValue.Key> skyKeyEntry : targetsToSkyKeys.entrySet()) {
      ProjectFilesLookupValue projectFilesLookupValue =
          (ProjectFilesLookupValue)
              env.getValueOrThrow(skyKeyEntry.getValue(), ProjectResolutionException.class);

      if (projectFilesLookupValue == null) {
        return null;
      }
      projectFilesLookupValues.put(skyKeyEntry.getKey(), projectFilesLookupValue);
    }

    ImmutableMultimap.Builder<Label, Label> projectFiles = ImmutableMultimap.builder();
    for (Map.Entry<Label, ProjectFilesLookupValue> entry : projectFilesLookupValues.entrySet()) {
      projectFiles.putAll(entry.getKey(), entry.getValue().getProjectFiles());
    }

    return projectFiles.build();
  }

  @Nullable
  private Scope.ScopeType getScopeType(
      Environment env, Label label, PackageIdentifier packageIdentifier)
      throws BuildOptionsScopeFunctionException, InterruptedException {
    PackageContext packageContext =
        PackageContext.of(packageIdentifier, RepositoryMapping.ALWAYS_FALLBACK);
    SkyframeTargetLoader targetLoader = new SkyframeTargetLoader(env, packageContext);

    Target target;
    try {
      target = targetLoader.loadBuildSetting(label.toString());
    } catch (TargetParsingException e) {
      throw new BuildOptionsScopeFunctionException(e);
    }

    if (target == null) {
      return null;
    }

    Rule rule = target.getAssociatedRule();

    return rule.getAttr("scope") == null
        ? Scope.ScopeType.UNIVERSAL
        : Scope.ScopeType.valueOfIgnoreCase(rule.getAttr("scope", Type.STRING).toString());
  }

  /**
   * Same as {@link ParsedFlagsFunction.SkyframeTargetLoader} but forking it here to avoid circular
   * dependencies.
   */
  private static class SkyframeTargetLoader implements StarlarkOptionsParser.BuildSettingLoader {
    private final Environment env;
    private final PackageContext packageContext;

    public SkyframeTargetLoader(Environment env, PackageContext packageContext) {
      this.env = env;
      this.packageContext = packageContext;
    }

    @Override
    @Nullable
    public Target loadBuildSetting(String name)
        throws InterruptedException, TargetParsingException {
      Label asLabel;
      try {
        asLabel = Label.parseWithPackageContext(name, packageContext);
      } catch (LabelSyntaxException e) {
        throw new IllegalArgumentException(e);
      }
      try {
        SkyKey pkgKey = asLabel.getPackageIdentifier();
        PackageValue pkg = (PackageValue) env.getValueOrThrow(pkgKey, NoSuchPackageException.class);
        if (pkg == null) {
          return null;
        }
        return pkg.getPackage().getTarget(asLabel.getName());
      } catch (NoSuchPackageException | NoSuchTargetException e) {
        throw new TargetParsingException(
            String.format("Failed to load %s", name), e, DEPENDENCY_NOT_FOUND);
      }
    }
  }

  /** Exception thrown by BuildOptionsScopesFunction. */
  public static final class BuildOptionsScopeFunctionException extends SkyFunctionException {
    BuildOptionsScopeFunctionException(ProjectResolutionException cause) {
      super(cause, Transience.PERSISTENT);
    }

    BuildOptionsScopeFunctionException(TargetParsingException cause) {
      super(cause, Transience.PERSISTENT);
    }
  }
}
