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

package com.google.devtools.build.lib.skyframe.toolchains;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException;
import com.google.devtools.build.lib.bazel.bzlmod.Module;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.SignedTargetPattern;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.FilteringPolicies;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.skyframe.TargetPatternUtil;
import com.google.devtools.build.lib.skyframe.TargetPatternUtil.InvalidTargetPatternException;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * {@link SkyFunction} that returns all registered toolchains available for toolchain resolution.
 */
public class RegisteredToolchainsFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    RegisteredToolchainsValue.Key key = (RegisteredToolchainsValue.Key) skyKey;
    BuildConfigurationValue configuration =
        (BuildConfigurationValue) env.getValue(key.getConfigurationKey());
    RepositoryMappingValue mainRepoMapping =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN));
    if (env.valuesMissing()) {
      return null;
    }

    TargetPattern.Parser mainRepoParser =
        new TargetPattern.Parser(
            PathFragment.EMPTY_FRAGMENT, RepositoryName.MAIN, mainRepoMapping.repositoryMapping());
    ImmutableList.Builder<SignedTargetPattern> targetPatternBuilder = new ImmutableList.Builder<>();

    // Get the toolchains from the configuration.
    // Reverse the list so the last one defined takes precedences.
    PlatformConfiguration platformConfiguration =
        configuration.getFragment(PlatformConfiguration.class);
    try {
      targetPatternBuilder.addAll(
          TargetPatternUtil.parseAllSigned(
              platformConfiguration.getExtraToolchains().reverse(), mainRepoParser));
    } catch (InvalidTargetPatternException e) {
      throw new RegisteredToolchainsFunctionException(
          new InvalidToolchainLabelException(e), Transience.PERSISTENT);
    }

    // Get registered toolchains from the root Bazel module.
    ImmutableList<TargetPattern> bzlmodRootModuleToolchains =
        getBzlmodToolchains(starlarkSemantics, env, /* forRootModule= */ true);
    if (bzlmodRootModuleToolchains == null) {
      return null;
    }
    targetPatternBuilder.addAll(TargetPatternUtil.toSigned(bzlmodRootModuleToolchains));

    // Get the toolchains from the user-supplied WORKSPACE file.
    ImmutableList<TargetPattern> userRegisteredWorkspaceToolchains =
        getWorkspaceToolchains(starlarkSemantics, env, /* userRegistered= */ true);
    if (userRegisteredWorkspaceToolchains == null) {
      return null;
    }
    targetPatternBuilder.addAll(TargetPatternUtil.toSigned(userRegisteredWorkspaceToolchains));

    // Get registered toolchains from non-root Bazel modules.
    ImmutableList<TargetPattern> bzlmodNonRootModuleToolchains =
        getBzlmodToolchains(starlarkSemantics, env, /* forRootModule= */ false);
    if (bzlmodNonRootModuleToolchains == null) {
      return null;
    }
    targetPatternBuilder.addAll(TargetPatternUtil.toSigned(bzlmodNonRootModuleToolchains));

    // Get the toolchains from the Bazel-supplied WORKSPACE suffix.
    ImmutableList<TargetPattern> workspaceSuffixToolchains =
        getWorkspaceToolchains(starlarkSemantics, env, /* userRegistered= */ false);
    if (workspaceSuffixToolchains == null) {
      return null;
    }
    targetPatternBuilder.addAll(TargetPatternUtil.toSigned(workspaceSuffixToolchains));

    // Expand target patterns.
    ImmutableList<Label> toolchainLabels;
    try {
      toolchainLabels =
          TargetPatternUtil.expandTargetPatterns(
              env, targetPatternBuilder.build(), FilteringPolicies.ruleTypeExplicit("toolchain"));
      if (env.valuesMissing()) {
        return null;
      }
    } catch (TargetPatternUtil.InvalidTargetPatternException e) {
      throw new RegisteredToolchainsFunctionException(
          new InvalidToolchainLabelException(e), Transience.PERSISTENT);
    }

    // Load the configured target for each, and get the declared toolchain providers.
    ImmutableList<DeclaredToolchainInfo> registeredToolchains =
        configureRegisteredToolchains(env, configuration, toolchainLabels);
    if (env.valuesMissing()) {
      return null;
    }

    // Check which toolchains are valid according to their configuration.
    ImmutableList.Builder<DeclaredToolchainInfo> validToolchains = new ImmutableList.Builder<>();
    // Some toolchains end up with repeated reasons, so use a HashBasedTable to handle duplicates.
    Table<Label, Label, String> rejectedToolchains = key.debug() ? HashBasedTable.create() : null;
    for (DeclaredToolchainInfo toolchain : registeredToolchains) {
      try {
        Consumer<String> errorHandler =
            key.debug()
                ? message ->
                    rejectedToolchains.put(
                        toolchain.toolchainType().typeLabel(), toolchain.targetLabel(), message)
                : null;
        if (ConfigMatchingUtil.validate(
            toolchain.targetLabel(), toolchain.targetSettings(), errorHandler)) {
          validToolchains.add(toolchain);
        }
      } catch (InvalidConfigurationException e) {
        throw new RegisteredToolchainsFunctionException(
            new InvalidToolchainLabelException(toolchain.targetLabel(), e), Transience.PERSISTENT);
      }
    }

    return RegisteredToolchainsValue.create(
        validToolchains.build(),
        rejectedToolchains != null ? ImmutableTable.copyOf(rejectedToolchains) : null);
  }

  /**
   * Loads the external package and then returns the registered toolchains.
   *
   * @param env the environment to use for lookups
   */
  @Nullable
  @VisibleForTesting
  public static ImmutableList<TargetPattern> getWorkspaceToolchains(
      StarlarkSemantics semantics, Environment env, boolean userRegistered)
      throws InterruptedException {
    if (!semantics.getBool(BuildLanguageOptions.ENABLE_WORKSPACE)) {
      return ImmutableList.of();
    }
    PackageValue externalPackageValue =
        (PackageValue) env.getValue(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER);
    if (externalPackageValue == null) {
      return null;
    }

    Package externalPackage = externalPackageValue.getPackage();
    if (userRegistered) {
      return externalPackage.getUserRegisteredToolchains();
    } else {
      return externalPackage.getWorkspaceSuffixRegisteredToolchains();
    }
  }

  @Nullable
  private static ImmutableList<TargetPattern> getBzlmodToolchains(
      StarlarkSemantics semantics, Environment env, boolean forRootModule)
      throws InterruptedException, RegisteredToolchainsFunctionException {
    if (!semantics.getBool(BuildLanguageOptions.ENABLE_BZLMOD)) {
      return ImmutableList.of();
    }
    BazelDepGraphValue bazelDepGraphValue =
        (BazelDepGraphValue) env.getValue(BazelDepGraphValue.KEY);
    if (bazelDepGraphValue == null) {
      return null;
    }
    ImmutableList.Builder<TargetPattern> toolchains = ImmutableList.builder();
    for (Module module : bazelDepGraphValue.getDepGraph().values()) {
      if (forRootModule != module.getKey().equals(ModuleKey.ROOT)) {
        continue;
      }
      TargetPattern.Parser parser =
          new TargetPattern.Parser(
              PathFragment.EMPTY_FRAGMENT,
              bazelDepGraphValue.getCanonicalRepoNameLookup().inverse().get(module.getKey()),
              bazelDepGraphValue.getFullRepoMapping(module.getKey()));
      for (String pattern : module.getToolchainsToRegister()) {
        try {
          toolchains.add(parser.parse(pattern));
        } catch (TargetParsingException e) {
          throw new RegisteredToolchainsFunctionException(
              new InvalidToolchainLabelException(pattern, e), Transience.PERSISTENT);
        }
      }
    }
    return toolchains.build();
  }

  @Nullable
  private static ImmutableList<DeclaredToolchainInfo> configureRegisteredToolchains(
      Environment env, BuildConfigurationValue configuration, List<Label> labels)
      throws InterruptedException, RegisteredToolchainsFunctionException {
    ImmutableList<ActionLookupKey> keys =
        labels.stream()
            .map(
                label ->
                    ConfiguredTargetKey.builder()
                        .setLabel(label)
                        .setConfiguration(configuration)
                        .build())
            .collect(toImmutableList());

    SkyframeLookupResult values = env.getValuesAndExceptions(keys);
    ImmutableList.Builder<DeclaredToolchainInfo> toolchains = new ImmutableList.Builder<>();
    boolean valuesMissing = false;
    for (ActionLookupKey key : keys) {
      Label toolchainLabel = key.getLabel();
      try {
        SkyValue value = values.getOrThrow(key, ConfiguredValueCreationException.class);
        if (value == null) {
          valuesMissing = true;
          continue;
        }

        ConfiguredTarget target = ((ConfiguredTargetValue) value).getConfiguredTarget();
        DeclaredToolchainInfo toolchainInfo = PlatformProviderUtils.declaredToolchainInfo(target);
        if (toolchainInfo == null) {
          throw new RegisteredToolchainsFunctionException(
              new InvalidToolchainLabelException(toolchainLabel), Transience.PERSISTENT);
        }
        toolchains.add(toolchainInfo);
      } catch (ConfiguredValueCreationException e) {
        throw new RegisteredToolchainsFunctionException(
            new InvalidToolchainLabelException(toolchainLabel, e), Transience.PERSISTENT);
      }
    }

    if (valuesMissing) {
      return null;
    }
    return toolchains.build();
  }

  /**
   * Used to indicate that the given {@link Label} represents a {@link ConfiguredTarget} which is
   * not a valid {@link DeclaredToolchainInfo} provider.
   */
  public static final class InvalidToolchainLabelException extends ToolchainException {

    public InvalidToolchainLabelException(Label invalidLabel) {
      super(
          formatMessage(
              invalidLabel.getCanonicalForm(),
              "target does not provide the DeclaredToolchainInfo provider"));
    }

    public InvalidToolchainLabelException(TargetPatternUtil.InvalidTargetPatternException e) {
      this(e.getInvalidPattern(), e.getTpe());
    }

    public InvalidToolchainLabelException(String invalidPattern, TargetParsingException e) {
      super(formatMessage(invalidPattern, e.getMessage()), e);
    }

    public InvalidToolchainLabelException(Label invalidLabel, ConfiguredValueCreationException e) {
      super(formatMessage(invalidLabel.getCanonicalForm(), e.getMessage()), e);
    }

    public InvalidToolchainLabelException(Label invalidLabel, InvalidConfigurationException e) {
      super(formatMessage(invalidLabel.getCanonicalForm(), e.getMessage()), e);
    }

    @Override
    protected Code getDetailedCode() {
      return Code.INVALID_TOOLCHAIN;
    }

    private static String formatMessage(String invalidPattern, String reason) {
      return String.format("invalid registered toolchain '%s': %s", invalidPattern, reason);
    }
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by {@link
   * #compute}.
   */
  public static class RegisteredToolchainsFunctionException extends SkyFunctionException {

    public RegisteredToolchainsFunctionException(
        InvalidToolchainLabelException cause, Transience transience) {
      super(cause, transience);
    }

    public RegisteredToolchainsFunctionException(
        ExternalDepsException cause, Transience transience) {
      super(cause, transience);
    }
  }
}
