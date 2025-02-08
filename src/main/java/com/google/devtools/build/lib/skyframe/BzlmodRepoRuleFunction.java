// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleCreator;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.NonRegistryOverride;
import com.google.devtools.build.lib.bazel.bzlmod.RepoRuleId;
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec;
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.packages.RuleFunction;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/**
 * Looks up the {@link RepoSpec} of a given repository name and create its repository rule instance.
 */
public final class BzlmodRepoRuleFunction implements SkyFunction {

  private final RuleClassProvider ruleClassProvider;
  private final BlazeDirectories directories;

  public BzlmodRepoRuleFunction(RuleClassProvider ruleClassProvider, BlazeDirectories directories) {
    this.ruleClassProvider = ruleClassProvider;
    this.directories = directories;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }

    RootModuleFileValue root =
        (RootModuleFileValue) env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    if (env.valuesMissing()) {
      return null;
    }

    // Sometimes, the attributes in the repo specs contain label strings instead of label objects.
    // This really only happens for attributes like `patches` that come from an `archive_override`
    // or `git_override` in MODULE.bazel. (This is because those overrides just store all attributes
    // unparsed and unvalidated in the repo spec.) In other words, this can only happen for Step 1
    // below -- but for consistency's sake, we pass this mapping for all 3 cases.
    //
    // In such cases, we need to provide a "basic repo mapping", so that we can properly turn those
    // label strings into label objects. Since we only accept patches from the main repo anyway, we
    // only need the two simple entries pointing into the main repo itself.
    RepositoryMapping basicMainRepoMapping =
        RepositoryMapping.create(
            ImmutableMap.<String, RepositoryName>builder()
                .put("", RepositoryName.MAIN)
                .put(root.getModule().getRepoName(), RepositoryName.MAIN)
                .buildKeepingLast(),
            RepositoryName.MAIN);

    RepositoryName repositoryName = ((BzlmodRepoRuleValue.Key) skyKey).argument();

    // Step 1: Look for repositories defined by non-registry overrides.
    Optional<RepoSpec> repoSpec = checkRepoFromNonRegistryOverrides(root, repositoryName);
    if (repoSpec.isPresent()) {
      return createRuleFromSpec(
          repoSpec.get(),
          repositoryName,
          /* originalName= */ null,
          basicMainRepoMapping,
          starlarkSemantics,
          env);
    }

    // BazelDepGraphValue is affected by repos found in Step 1, therefore it should NOT
    // be requested in Step 1 to avoid cycle dependency.
    BazelDepGraphValue bazelDepGraphValue =
        (BazelDepGraphValue) env.getValue(BazelDepGraphValue.KEY);
    if (env.valuesMissing()) {
      return null;
    }

    // Step 2: Look for repositories derived from Bazel Modules.
    repoSpec = checkRepoFromBazelModules(bazelDepGraphValue, repositoryName);
    if (repoSpec.isPresent()) {
      return createRuleFromSpec(
          repoSpec.get(),
          repositoryName,
          /* originalName= */ null,
          basicMainRepoMapping,
          starlarkSemantics,
          env);
    }

    // Step 3: look for the repo from module extension evaluation results.
    Optional<ModuleExtensionId> extensionId =
        bazelDepGraphValue.getExtensionUniqueNames().entrySet().stream()
            .filter(e -> repositoryName.getName().startsWith(e.getValue() + "+"))
            .map(Entry::getKey)
            .findFirst();

    if (extensionId.isEmpty()) {
      return BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE;
    }

    SingleExtensionValue extensionValue =
        (SingleExtensionValue) env.getValue(SingleExtensionValue.key(extensionId.get()));
    if (extensionValue == null) {
      return null;
    }

    String internalRepo = extensionValue.canonicalRepoNameToInternalNames().get(repositoryName);
    if (internalRepo == null) {
      return BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE;
    }
    RepoSpec extRepoSpec = extensionValue.generatedRepoSpecs().get(internalRepo);
    return createRuleFromSpec(
        extRepoSpec, repositoryName, internalRepo, basicMainRepoMapping, starlarkSemantics, env);
  }

  private static Optional<RepoSpec> checkRepoFromNonRegistryOverrides(
      RootModuleFileValue root, RepositoryName repositoryName) {
    String moduleName = root.getNonRegistryOverrideCanonicalRepoNameLookup().get(repositoryName);
    if (moduleName == null) {
      return Optional.empty();
    }
    NonRegistryOverride override = (NonRegistryOverride) root.getOverrides().get(moduleName);
    return Optional.of(override.repoSpec());
  }

  private Optional<RepoSpec> checkRepoFromBazelModules(
      BazelDepGraphValue bazelDepGraphValue, RepositoryName repositoryName) {
    ModuleKey moduleKey = bazelDepGraphValue.getCanonicalRepoNameLookup().get(repositoryName);
    if (moduleKey == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(bazelDepGraphValue.getDepGraph().get(moduleKey).getRepoSpec());
  }

  @Nullable
  private BzlmodRepoRuleValue createRuleFromSpec(
      RepoSpec repoSpec,
      RepositoryName repositoryName,
      @Nullable String originalName,
      RepositoryMapping basicMainRepoMapping,
      StarlarkSemantics starlarkSemantics,
      Environment env)
      throws BzlmodRepoRuleFunctionException, InterruptedException {
    RuleClass ruleClass = loadRepoRule(repoSpec.repoRuleId(), env);
    if (ruleClass == null) {
      return null;
    }

    var attributesBuilder =
        ImmutableMap.<String, Object>builder()
            .putAll(repoSpec.attributes().attributes())
            .put("name", repositoryName.getName());
    if (originalName != null) {
      attributesBuilder.put("$original_name", originalName);
    }
    try {
      Rule rule =
          BzlmodRepoRuleCreator.createRule(
              PackageIdentifier.EMPTY_PACKAGE_ID,
              basicMainRepoMapping,
              directories,
              starlarkSemantics,
              env.getListener(),
              ImmutableList.of(
                  StarlarkThread.callStackEntry(
                      "BzlmodRepoRuleFunction.createRuleFromSpec", Location.BUILTIN)),
              ruleClass,
              attributesBuilder.buildOrThrow());
      return new BzlmodRepoRuleValue(rule.getPackage(), rule.getName());
    } catch (InvalidRuleException e) {
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    } catch (NoSuchPackageException e) {
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    } catch (EvalException e) {
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    }
  }

  @Nullable
  private RuleClass loadRepoRule(RepoRuleId repoRuleId, Environment env)
      throws InterruptedException, BzlmodRepoRuleFunctionException {
    if (repoRuleId.isNative()) {
      RuleClass ruleClass = ruleClassProvider.getRuleClassMap().get(repoRuleId.ruleName());
      if (ruleClass == null) {
        throw new BzlmodRepoRuleFunctionException(
            new InvalidRuleException(
                "Unrecognized native repository rule: " + repoRuleId.ruleName()),
            Transience.PERSISTENT);
      }
      return ruleClass;
    }

    SkyKey key;
    if (NonRegistryOverride.BOOTSTRAP_REPO_RULES.contains(repoRuleId)) {
      key = BzlLoadValue.keyForBzlmodBootstrap(repoRuleId.bzlFileLabel());
    } else {
      key = BzlLoadValue.keyForBzlmod(repoRuleId.bzlFileLabel());
    }

    // Load the .bzl file pointed to by the label.
    BzlLoadValue bzlLoadValue;
    try {
      bzlLoadValue = (BzlLoadValue) env.getValueOrThrow(key, BzlLoadFailedException.class);
    } catch (BzlLoadFailedException e) {
      // No need for a super detailed error message, since errors here can basically only happen
      // when something is horribly wrong. (The labels to load are either hardcoded or already
      // sanity-checked somewhere else.)
      throw new BzlmodRepoRuleFunctionException(e, Transience.PERSISTENT);
    }
    if (bzlLoadValue == null) {
      return null;
    }

    Object object = bzlLoadValue.getModule().getGlobal(repoRuleId.ruleName());
    if (object instanceof RuleFunction ruleFunction) {
      return ruleFunction.getRuleClass();
    } else if (object == null) {
      throw new BzlmodRepoRuleFunctionException(
          new InvalidRuleException(
              "repository rule %s does not exist (no such symbol in that file)"
                  .formatted(repoRuleId)),
          Transience.PERSISTENT);
    } else {
      throw new BzlmodRepoRuleFunctionException(
          new InvalidRuleException(
              "invalid repository rule: %s, expected type repository_rule, got type %s"
                  .formatted(repoRuleId, Starlark.type(object))),
          Transience.PERSISTENT);
    }
  }

  private static final class BzlmodRepoRuleFunctionException extends SkyFunctionException {

    BzlmodRepoRuleFunctionException(InvalidRuleException e, Transience transience) {
      super(e, transience);
    }

    BzlmodRepoRuleFunctionException(BzlLoadFailedException e, Transience transience) {
      super(e, transience);
    }

    BzlmodRepoRuleFunctionException(NoSuchPackageException e, Transience transience) {
      super(e, transience);
    }

    BzlmodRepoRuleFunctionException(EvalException e, Transience transience) {
      super(e, transience);
    }
  }
}
