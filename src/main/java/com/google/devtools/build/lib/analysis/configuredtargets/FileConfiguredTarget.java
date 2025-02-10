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

import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.VisibilityProvider;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.util.FileType;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;

/** A configured target representing a source or derived / generated file. */
@Immutable
public abstract sealed class FileConfiguredTarget extends AbstractConfiguredTarget
    implements FileType.HasFileType, LicensesProvider
    permits InputFileConfiguredTarget, OutputFileConfiguredTarget {

  private final NestedSet<Artifact> singleFile;

  FileConfiguredTarget(
      ActionLookupKey lookupKey, NestedSet<PackageGroupContents> visibility, Artifact artifact) {
    super(lookupKey, visibility);
    this.singleFile = NestedSetBuilder.create(Order.STABLE_ORDER, artifact);
  }

  public Artifact getArtifact() {
    return singleFile.getSingleton();
  }

  /** Returns the file name of this file target. */
  public final String getFilename() {
    return getLabel().getName();
  }

  @Override
  public final String filePathForFileTypeMatcher() {
    return getFilename();
  }

  @Override
  @Nullable
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> providerClass) {
    AnalysisUtils.checkProvider(providerClass);
    return providerClass.cast(getProviderInternal(providerClass));
  }

  @Nullable
  private TransitiveInfoProvider getProviderInternal(
      Class<? extends TransitiveInfoProvider> providerClass) {
    // The set of possible providers is small and predictable, so to save memory, this method does
    // simple identity checks so that we don't need to store a TransitiveInfoProviderMap.
    // Additionally, file providers are created on-demand when requested. These optimizations
    // combine to save over 1% of analysis heap.
    if (providerClass == VisibilityProvider.class) {
      return this;
    }
    if (providerClass == FileProvider.class) {
      return createFileProvider();
    }
    if (providerClass == FilesToRunProvider.class) {
      return createFilesToRunProvider();
    }
    return null;
  }

  private FileProvider createFileProvider() {
    return FileProvider.of(singleFile);
  }

  private FilesToRunProvider createFilesToRunProvider() {
    return FilesToRunProvider.create(
        singleFile, /* runfilesSupport= */ null, /* executable= */ getArtifact());
  }

  @Override
  @Nullable
  protected final Object rawGetStarlarkProvider(String providerKey) {
    return null;
  }

  @Override
  public Dict<String, Object> getProvidersDictForQuery() {
    Dict.Builder<String, Object> dict = Dict.builder();
    tryAddProviderForQuery(dict, VisibilityProvider.class, this);
    tryAddProviderForQuery(dict, LicensesProvider.class, this);
    tryAddProviderForQuery(dict, FileProvider.class, createFileProvider());
    tryAddProviderForQuery(dict, FilesToRunProvider.class, createFilesToRunProvider());
    return dict.buildImmutable();
  }
}
