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

package com.google.devtools.build.lib.analysis.configuredtargets;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.LicensesProvider;
import com.google.devtools.build.lib.analysis.LicensesProviderImpl;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider;
import com.google.devtools.build.lib.analysis.TargetContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Printer;

/** A ConfiguredTarget for an OutputFile. */
@Immutable
@AutoCodec
public final class OutputFileConfiguredTarget extends FileConfiguredTarget {

  private final RuleConfiguredTarget generatingRule;

  public OutputFileConfiguredTarget(
      TargetContext targetContext, Artifact outputArtifact, RuleConfiguredTarget generatingRule) {
    this(
        targetContext.getAnalysisEnvironment().getOwner(),
        targetContext.getVisibility(),
        outputArtifact,
        checkNotNull(generatingRule));
    checkArgument(targetContext.getTarget() instanceof OutputFile, targetContext.getTarget());
  }

  @AutoCodec.Instantiator
  @VisibleForSerialization
  OutputFileConfiguredTarget(
      ActionLookupKey lookupKey,
      NestedSet<PackageGroupContents> visibility,
      Artifact artifact,
      RuleConfiguredTarget generatingRule) {
    super(lookupKey, visibility, artifact);
    this.generatingRule = checkNotNull(generatingRule);
  }

  public RuleConfiguredTarget getGeneratingRule() {
    return generatingRule;
  }

  @Override
  public boolean isCreatedInSymbolicMacro() {
    return generatingRule.isCreatedInSymbolicMacro();
  }

  @Override
  public BuiltinProvider<LicensesProvider> getProvider() {
    return LicensesProvider.PROVIDER;
  }

  @Override
  @Nullable
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> providerClass) {
    P provider = super.getProvider(providerClass);
    if (provider != null) {
      return provider;
    }
    if (providerClass == RequiredConfigFragmentsProvider.class) {
      return generatingRule.getProvider(providerClass);
    }
    return null;
  }

  @Nullable
  @Override
  protected Info rawGetStarlarkProvider(Provider.Key providerKey) {
    // The following Starlark providers do not implement TransitiveInfoProvider and thus may only be
    // requested via this method using a Provider.Key, not via getProvider(Class) above.

    if (providerKey.equals(LicensesProvider.PROVIDER.getKey())) {
      return generatingRule.get(LicensesProvider.PROVIDER);
    }

    if (providerKey.equals(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR.getKey())) {
      return firstNonNull(
          generatingRule.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR),
          InstrumentedFilesInfo.EMPTY);
    }

    if (providerKey.equals(OutputGroupInfo.STARLARK_CONSTRUCTOR.getKey())) {
      // We have an OutputFileConfiguredTarget, so the generating rule must have OutputGroupInfo.
      NestedSet<Artifact> validationOutputs =
          generatingRule
              .get(OutputGroupInfo.STARLARK_CONSTRUCTOR)
              .getOutputGroup(OutputGroupInfo.VALIDATION);
      if (!validationOutputs.isEmpty()) {
        return OutputGroupInfo.singleGroup(OutputGroupInfo.VALIDATION, validationOutputs);
      }
    }

    return null;
  }

  @Override
  public Dict<String, Object> getProvidersDictForQuery() {
    Dict.Builder<String, Object> dict = Dict.builder();
    dict.putAll(super.getProvidersDictForQuery());
    addStarlarkProviderIfPresent(dict, InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    addStarlarkProviderIfPresent(dict, OutputGroupInfo.STARLARK_CONSTRUCTOR);
    addNativeProviderFromRuleIfPresent(dict, RequiredConfigFragmentsProvider.class);
    return dict.buildImmutable();
  }

  private void addStarlarkProviderIfPresent(Dict.Builder<String, Object> dict, Provider provider) {
    Info info = rawGetStarlarkProvider(provider.getKey());
    if (info != null) {
      tryAddProviderForQuery(dict, provider.getKey(), info);
    }
  }

  private void addNativeProviderFromRuleIfPresent(
      Dict.Builder<String, Object> dict, Class<? extends TransitiveInfoProvider> providerClass) {
    TransitiveInfoProvider provider = generatingRule.getProvider(providerClass);
    if (provider != null) {
      tryAddProviderForQuery(dict, providerClass, provider);
    }
  }

  @Override
  public NestedSet<TargetLicense> getTransitiveLicenses() {
    return getLicencesProviderFromGeneratingRule().getTransitiveLicenses();
  }

  @Override
  public TargetLicense getOutputLicenses() {
    return getLicencesProviderFromGeneratingRule().getOutputLicenses();
  }

  @Override
  public boolean hasOutputLicenses() {
    return getLicencesProviderFromGeneratingRule().hasOutputLicenses();
  }

  private LicensesProvider getLicencesProviderFromGeneratingRule() {
    return firstNonNull(generatingRule.get(LicensesProvider.PROVIDER), LicensesProviderImpl.EMPTY);
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<output file target " + getLabel() + ">");
  }
}
