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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.analysis.LicensesProvider;
import com.google.devtools.build.lib.analysis.TargetContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.License;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.eval.Printer;

/**
 * A ConfiguredTarget for an InputFile.
 *
 * <p>All InputFiles for the same target are equivalent, so configuration does not play any role
 * here and is always set to <b>null</b>.
 */
@Immutable
@AutoCodec
public final class InputFileConfiguredTarget extends FileConfiguredTarget {

  private final NestedSet<TargetLicense> licenses;
  private final boolean isCreatedInSymbolicMacro;

  public InputFileConfiguredTarget(TargetContext targetContext, SourceArtifact artifact) {
    this(
        targetContext.getAnalysisEnvironment().getOwner(),
        targetContext.getVisibility(),
        artifact,
        makeLicenses(targetContext.getTarget()),
        targetContext.getTarget().isCreatedInSymbolicMacro());
    checkArgument(targetContext.getTarget() instanceof InputFile, targetContext.getTarget());
    checkArgument(getConfigurationKey() == null, getLabel());
  }

  @AutoCodec.Instantiator
  @VisibleForSerialization
  InputFileConfiguredTarget(
      ActionLookupKey lookupKey,
      NestedSet<PackageGroupContents> visibility,
      SourceArtifact artifact,
      NestedSet<TargetLicense> licenses,
      boolean isCreatedInSymbolicMacro) {
    super(lookupKey, visibility, artifact);
    this.licenses = licenses;
    this.isCreatedInSymbolicMacro = isCreatedInSymbolicMacro;
  }

  private static NestedSet<TargetLicense> makeLicenses(Target inputFile) {
    License license = inputFile.getLicense();
    return Objects.equals(license, License.NO_LICENSE)
        ? NestedSetBuilder.emptySet(Order.LINK_ORDER)
        : NestedSetBuilder.create(
            Order.LINK_ORDER, new TargetLicense(inputFile.getLabel(), license));
  }

  @Override
  public boolean isCreatedInSymbolicMacro() {
    return isCreatedInSymbolicMacro;
  }

  @Override
  public BuiltinProvider<LicensesProvider> getProvider() {
    return LicensesProvider.PROVIDER;
  }

  @Override
  public SourceArtifact getArtifact() {
    return (SourceArtifact) super.getArtifact();
  }

  @Override
  @Nullable
  protected Info rawGetStarlarkProvider(Provider.Key providerKey) {
    if (providerKey.equals(LicensesProvider.PROVIDER.getKey())) {
      return this;
    }
    return null;
  }

  @Override
  public NestedSet<TargetLicense> getTransitiveLicenses() {
    return licenses;
  }

  @Override
  @Nullable
  public TargetLicense getOutputLicenses() {
    return null;
  }

  @Override
  public boolean hasOutputLicenses() {
    return false;
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<input file target " + getLabel() + ">");
  }

  @Override
  public String toString() {
    return "InputFileConfiguredTarget(" + getLabel() + ")";
  }
}
