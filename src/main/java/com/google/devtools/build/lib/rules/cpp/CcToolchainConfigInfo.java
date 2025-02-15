// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ActionConfig;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ArtifactNamePatternMapper;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.EnvEntry;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.EnvSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Flag.SingleChunkFlag;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FlagGroup;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FlagSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.VariableWithValue;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.WithFeatureSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Expandable;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcToolchainConfigInfoApi;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CToolchain;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.ToolPath;
import java.util.List;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

/** Information describing C++ toolchain derived from CROSSTOOL file. */
@Immutable
public class CcToolchainConfigInfo extends NativeInfo implements CcToolchainConfigInfoApi {

  /** Singleton provider instance for {@link CcToolchainConfigInfo}. */
  public static final Provider PROVIDER = new Provider();

  private final ImmutableList<ActionConfig> actionConfigs;
  private final ImmutableList<Feature> features;
  private final ArtifactNamePatternMapper artifactNamePatterns;
  private final ImmutableList<String> cxxBuiltinIncludeDirectories;

  private final String toolchainIdentifier;
  private final String hostSystemName;
  private final String targetSystemName;
  private final String targetCpu;
  private final String targetLibc;
  private final String compiler;
  private final String abiVersion;
  private final String abiLibcVersion;
  private final ImmutableList<Pair<String, String>> toolPaths;
  private final ImmutableList<Pair<String, String>> makeVariables;
  private final String builtinSysroot;
  private final ImmutableList<String> additionalLinkOutputs;

  CcToolchainConfigInfo(
      ImmutableList<ActionConfig> actionConfigs,
      ImmutableList<Feature> features,
      ArtifactNamePatternMapper artifactNamePatterns,
      ImmutableList<String> cxxBuiltinIncludeDirectories,
      String toolchainIdentifier,
      String hostSystemName,
      String targetSystemName,
      String targetCpu,
      String targetLibc,
      String compiler,
      String abiVersion,
      String abiLibcVersion,
      ImmutableList<Pair<String, String>> toolPaths,
      ImmutableList<Pair<String, String>> makeVariables,
      String builtinSysroot,
      ImmutableList<String> additionalLinkOutputs) {
    this.actionConfigs = actionConfigs;
    this.features = features;
    this.artifactNamePatterns = artifactNamePatterns;
    this.cxxBuiltinIncludeDirectories = cxxBuiltinIncludeDirectories;
    this.toolchainIdentifier = toolchainIdentifier;
    this.hostSystemName = hostSystemName;
    this.targetSystemName = targetSystemName;
    this.targetCpu = targetCpu;
    this.targetLibc = targetLibc;
    this.compiler = compiler;
    this.abiVersion = abiVersion;
    this.abiLibcVersion = abiLibcVersion;
    this.toolPaths = toolPaths;
    this.makeVariables = makeVariables;
    this.builtinSysroot = builtinSysroot;
    this.additionalLinkOutputs = additionalLinkOutputs;
  }

  @Override
  public Provider getProvider() {
    return PROVIDER;
  }

  @VisibleForTesting // Only called by tests.
  public static CcToolchainConfigInfo fromToolchainForTestingOnly(CToolchain toolchain)
      throws EvalException {
    ImmutableList.Builder<ActionConfig> actionConfigBuilder = ImmutableList.builder();
    for (CToolchain.ActionConfig actionConfig : toolchain.getActionConfigList()) {
      actionConfigBuilder.add(new ActionConfig(actionConfig));
    }

    ImmutableList.Builder<Feature> featureBuilder = ImmutableList.builder();
    for (CToolchain.Feature feature : toolchain.getFeatureList()) {
      featureBuilder.add(new Feature(feature));
    }

    ArtifactNamePatternMapper.Builder artifactNamePatternBuilder =
        new ArtifactNamePatternMapper.Builder();
    for (CToolchain.ArtifactNamePattern artifactNamePattern :
        toolchain.getArtifactNamePatternList()) {
      ArtifactCategory foundCategory = null;
      for (ArtifactCategory artifactCategory : ArtifactCategory.values()) {
        if (artifactNamePattern.getCategoryName().equals(artifactCategory.getCategoryName())) {
          foundCategory = artifactCategory;
          break;
        }
      }
      Preconditions.checkNotNull(foundCategory, artifactNamePattern);
      String extension = artifactNamePattern.getExtension();
      Preconditions.checkState(
          foundCategory.getAllowedExtensions().contains(extension),
          "%s had extension not in %s",
          artifactNamePattern,
          foundCategory);
      artifactNamePatternBuilder.addOverride(
          foundCategory, artifactNamePattern.getPrefix(), extension);
    }

    return new CcToolchainConfigInfo(
        actionConfigBuilder.build(),
        featureBuilder.build(),
        artifactNamePatternBuilder.build(),
        ImmutableList.copyOf(toolchain.getCxxBuiltinIncludeDirectoryList()),
        toolchain.getToolchainIdentifier(),
        toolchain.getHostSystemName(),
        toolchain.getTargetSystemName(),
        toolchain.getTargetCpu(),
        toolchain.getTargetLibc(),
        toolchain.getCompiler(),
        toolchain.getAbiVersion(),
        toolchain.getAbiLibcVersion(),
        toolchain.getToolPathList().stream()
            .map(a -> Pair.of(a.getName(), a.getPath()))
            .collect(ImmutableList.toImmutableList()),
        toolchain.getMakeVariableList().stream()
            .map(makeVariable -> Pair.of(makeVariable.getName(), makeVariable.getValue()))
            .collect(ImmutableList.toImmutableList()),
        toolchain.getBuiltinSysroot(),
        ImmutableList.copyOf(toolchain.getAdditionalLinkOutputsList()));
  }

  public ImmutableList<ActionConfig> getActionConfigs() {
    return actionConfigs;
  }

  public ImmutableList<Feature> getFeatures() {
    return features;
  }

  public ArtifactNamePatternMapper getArtifactNamePatterns() {
    return artifactNamePatterns;
  }

  @StarlarkMethod(
      name = "cxx_builtin_include_directories",
      documented = false,
      useStarlarkThread = true)
  public List<String> getCxxBuiltinIncludeDirectoriesForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getCxxBuiltinIncludeDirectories();
  }

  public ImmutableList<String> getCxxBuiltinIncludeDirectories() {
    return cxxBuiltinIncludeDirectories;
  }

  @StarlarkMethod(name = "toolchain_id", documented = false, useStarlarkThread = true)
  public String getToolchainIdentifierForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getToolchainIdentifier();
  }

  public String getToolchainIdentifier() {
    return toolchainIdentifier;
  }

  @StarlarkMethod(name = "target_system_name", documented = false, useStarlarkThread = true)
  public String getTargetSystemNameForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getTargetSystemName();
  }

  public String getTargetSystemName() {
    return targetSystemName;
  }

  @StarlarkMethod(name = "target_cpu", documented = false, useStarlarkThread = true)
  public String getTargetCpuForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getTargetCpu();
  }

  public String getTargetCpu() {
    return targetCpu;
  }

  @StarlarkMethod(name = "target_libc", documented = false, useStarlarkThread = true)
  public String getTargetLibcForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getTargetLibc();
  }

  public String getTargetLibc() {
    return targetLibc;
  }

  @StarlarkMethod(name = "compiler", documented = false, useStarlarkThread = true)
  public String getCompilerForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getCompiler();
  }

  public String getCompiler() {
    return compiler;
  }

  @StarlarkMethod(name = "abi_version", documented = false, useStarlarkThread = true)
  public String getAbiVersionForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getAbiVersion();
  }

  public String getAbiVersion() {
    return abiVersion;
  }

  @StarlarkMethod(name = "abi_libc_version", documented = false, useStarlarkThread = true)
  public String getAbiLibcVersionForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getAbiLibcVersion();
  }

  public String getAbiLibcVersion() {
    return abiLibcVersion;
  }

  @StarlarkMethod(name = "tool_paths", documented = false, useStarlarkThread = true)
  public ImmutableList<Tuple> getToolPathsForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getToolPaths().stream()
        .map(p -> Tuple.of(p.getFirst(), p.getSecond()))
        .collect(toImmutableList());
  }

  /** Returns a list of paths of the tools in the form Pair<toolName, path>. */
  public ImmutableList<Pair<String, String>> getToolPaths() {
    return toolPaths;
  }

  @StarlarkMethod(name = "make_variables", documented = false, useStarlarkThread = true)
  public ImmutableList<Tuple> getMakevariablesForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getMakeVariables().stream()
        .map(p -> Tuple.of(p.getFirst(), p.getSecond()))
        .collect(toImmutableList());
  }

  /** Returns a list of make variables that have the form Pair<name, value>. */
  public ImmutableList<Pair<String, String>> getMakeVariables() {
    return makeVariables;
  }

  @StarlarkMethod(name = "builtin_sysroot", documented = false, useStarlarkThread = true)
  public String getBuiltinSysrootForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getBuiltinSysroot();
  }

  public String getBuiltinSysroot() {
    return builtinSysroot;
  }

  @StarlarkMethod(name = "additional_link_outputs", documented = false, useStarlarkThread = true)
  public List<String> getAdditionalLinkOutputsForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getAdditionalLinkOutputs();
  }

  public ImmutableList<String> getAdditionalLinkOutputs() {
    return additionalLinkOutputs;
  }

  @Override
  public String getProto() {
    CToolchain.Builder cToolchain = CToolchain.newBuilder();
    cToolchain.addAllFeature(
        features.stream()
            .map(feature -> featureToProto(feature))
            .collect(ImmutableList.toImmutableList()));
    cToolchain.addAllActionConfig(
        actionConfigs.stream()
            .map(actionConfig -> actionConfigToProto(actionConfig))
            .collect(ImmutableList.toImmutableList()));
    cToolchain.addAllArtifactNamePattern(
        artifactNamePatterns.asImmutableMap().entrySet().stream()
            .map(
                entry ->
                    CToolchain.ArtifactNamePattern.newBuilder()
                        .setCategoryName(entry.getKey().getCategoryName())
                        .setPrefix(entry.getValue().getPrefix())
                        .setExtension(entry.getValue().getExtension())
                        .build())
            .collect(ImmutableList.toImmutableList()));
    cToolchain.addAllToolPath(
        toolPaths.stream()
            .map(
                toolPath ->
                    ToolPath.newBuilder()
                        .setName(toolPath.getFirst())
                        .setPath(toolPath.getSecond())
                        .build())
            .collect(ImmutableList.toImmutableList()));
    cToolchain.addAllMakeVariable(
        makeVariables.stream()
            .map(
                makeVariable ->
                    CrosstoolConfig.MakeVariable.newBuilder()
                        .setName(makeVariable.getFirst())
                        .setValue(makeVariable.getSecond())
                        .build())
            .collect(ImmutableList.toImmutableList()));
    cToolchain
        .addAllCxxBuiltinIncludeDirectory(cxxBuiltinIncludeDirectories)
        .setToolchainIdentifier(toolchainIdentifier)
        .setHostSystemName(hostSystemName)
        .setTargetSystemName(targetSystemName)
        .setTargetCpu(targetCpu)
        .setTargetLibc(targetLibc)
        .setCompiler(compiler)
        .setAbiVersion(abiVersion)
        .setAbiLibcVersion(abiLibcVersion);
    if (!builtinSysroot.isEmpty()) {
      cToolchain.setBuiltinSysroot(builtinSysroot);
    }
    return cToolchain.build().toString();
  }

  private static CToolchain.WithFeatureSet withFeatureSetToProto(WithFeatureSet withFeatureSet) {
    return CToolchain.WithFeatureSet.newBuilder()
        .addAllFeature(withFeatureSet.getFeatures())
        .addAllNotFeature(withFeatureSet.getNotFeatures())
        .build();
  }

  private static CToolchain.EnvEntry envEntryToProto(EnvEntry envEntry) {
    return CToolchain.EnvEntry.newBuilder()
        .setKey(envEntry.getKey())
        .setValue(envEntry.getValue())
        .build();
  }

  private static CToolchain.EnvSet envSetToProto(EnvSet envSet) {
    return CToolchain.EnvSet.newBuilder()
        .addAllAction(envSet.getActions())
        .addAllEnvEntry(
            envSet.getEnvEntries().stream()
                .map(envEntry -> envEntryToProto(envEntry))
                .collect(ImmutableList.toImmutableList()))
        .addAllWithFeature(
            envSet.getWithFeatureSets().stream()
                .map(withFeatureSet -> withFeatureSetToProto(withFeatureSet))
                .collect(ImmutableList.toImmutableList()))
        .build();
  }

  private static CToolchain.FlagGroup flagGroupToProto(FlagGroup flagGroup) {
    ImmutableList.Builder<CToolchain.FlagGroup> flagGroups = ImmutableList.builder();
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    for (Expandable expandable : flagGroup.getExpandables()) {
      if (expandable instanceof FlagGroup expandableFlagGroup) {
        flagGroups.add(flagGroupToProto(expandableFlagGroup));
      } else if (expandable instanceof SingleChunkFlag singleChunkFlag) {
        flags.add(singleChunkFlag.getString());
      } else if (expandable instanceof CcToolchainFeatures.Flag) {
        flags.add(((CcToolchainFeatures.Flag) expandable).getString());
      } else {
        throw new IllegalStateException("Unexpected subclass of Expandable.");
      }
    }

    CToolchain.FlagGroup.Builder flagGroupBuilder = CToolchain.FlagGroup.newBuilder();
    if (flagGroup.getIterateOverVariable() != null) {
      flagGroupBuilder.setIterateOver(flagGroup.getIterateOverVariable());
    }
    if (flagGroup.getExpandIfTrue() != null) {
      flagGroupBuilder.setExpandIfTrue(flagGroup.getExpandIfTrue());
    }
    if (flagGroup.getExpandIfFalse() != null) {
      flagGroupBuilder.setExpandIfFalse(flagGroup.getExpandIfFalse());
    }
    if (flagGroup.getExpandIfEqual() != null) {
      flagGroupBuilder.setExpandIfEqual(variableWithValueFromProto(flagGroup.getExpandIfEqual()));
    }
    return flagGroupBuilder
        .addAllExpandIfAllAvailable(flagGroup.getExpandIfAllAvailable())
        .addAllExpandIfNoneAvailable(flagGroup.getExpandIfNoneAvailable())
        .addAllFlagGroup(flagGroups.build())
        .addAllFlag(flags.build())
        .build();
  }

  private static CToolchain.VariableWithValue variableWithValueFromProto(
      VariableWithValue variable) {
    return CToolchain.VariableWithValue.newBuilder()
        .setValue(variable.getValue())
        .setVariable(variable.getVariable())
        .build();
  }

  private static CToolchain.Feature featureToProto(Feature feature) {
    return CToolchain.Feature.newBuilder()
        .setName(feature.getName())
        .setEnabled(feature.isEnabled())
        .addAllFlagSet(
            feature.getFlagSets().stream()
                .map(flagSet -> flagSetToProto(flagSet, /* forActionConfig= */ false))
                .collect(ImmutableList.toImmutableList()))
        .addAllEnvSet(
            feature.getEnvSets().stream()
                .map(envSet -> envSetToProto(envSet))
                .collect(ImmutableList.toImmutableList()))
        .addAllRequires(
            feature.getRequires().stream()
                .map(featureSet -> featureSetToProto(featureSet))
                .collect(ImmutableList.toImmutableList()))
        .addAllImplies(feature.getImplies())
        .addAllProvides(feature.getProvides())
        .build();
  }

  private static CToolchain.FlagSet flagSetToProto(FlagSet flagSet, boolean forActionConfig) {
    CToolchain.FlagSet.Builder flagSetBuilder =
        CToolchain.FlagSet.newBuilder()
            .addAllFlagGroup(
                flagSet.getFlagGroups().stream()
                    .map(flagGroup -> flagGroupToProto(flagGroup))
                    .collect(ImmutableList.toImmutableList()))
            .addAllWithFeature(
                flagSet.getWithFeatureSets().stream()
                    .map(withFeatureSet -> withFeatureSetToProto(withFeatureSet))
                    .collect(ImmutableList.toImmutableList()))
            .addAllExpandIfAllAvailable(flagSet.getExpandIfAllAvailable());
    if (!forActionConfig) {
      flagSetBuilder.addAllAction(flagSet.getActions());
    }
    return flagSetBuilder.build();
  }

  private static CToolchain.FeatureSet featureSetToProto(ImmutableSet<String> features) {
    return CToolchain.FeatureSet.newBuilder().addAllFeature(features).build();
  }

  private static CToolchain.Tool toolToProto(Tool tool) {
    return CToolchain.Tool.newBuilder()
        .setToolPath(tool.getToolPathFragment().toString())
        .setToolPathOrigin(tool.getToolPathOrigin())
        .addAllWithFeature(
            tool.getWithFeatureSetSets().stream()
                .map(withFeatureSet -> withFeatureSetToProto(withFeatureSet))
                .collect(ImmutableList.toImmutableList()))
        .addAllExecutionRequirement(tool.getExecutionRequirements())
        .build();
  }

  private static CToolchain.ActionConfig actionConfigToProto(ActionConfig actionConfig) {
    return CToolchain.ActionConfig.newBuilder()
        .setConfigName(actionConfig.getName())
        .setActionName(actionConfig.getActionName())
        .setEnabled(actionConfig.isEnabled())
        .addAllTool(
            actionConfig.getTools().stream()
                .map(tool -> toolToProto(tool))
                .collect(ImmutableList.toImmutableList()))
        .addAllFlagSet(
            actionConfig.getFlagSets().stream()
                .map(flagSet -> flagSetToProto(flagSet, /* forActionConfig= */ true))
                .collect(ImmutableList.toImmutableList()))
        .addAllImplies(actionConfig.getImplies())
        .build();
  }

  /** Provider class for {@link CcToolchainConfigInfo} objects. */
  public static class Provider extends BuiltinProvider<CcToolchainConfigInfo>
      implements CcToolchainConfigInfoApi.Provider {

    private Provider() {
      super("CcToolchainConfigInfo", CcToolchainConfigInfo.class);
    }
  }
}
