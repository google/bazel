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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.DebugPackageInfoApi;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/**
 * Provides the binary artifact and its associated .dwp files, if fission is enabled. If Fission
 * ({@url https://gcc.gnu.org/wiki/DebugFission}) is not enabled, the dwp file will be null.
 */
@Immutable
public final class DebugPackageProvider extends NativeInfo
    implements DebugPackageInfoApi<Artifact> {
  public static final Provider PROVIDER = new Provider();

  private final Label targetLabel;
  private final Artifact strippedArtifact;
  private final Artifact unstrippedArtifact;
  @Nullable private final Artifact dwpArtifact;
  @Nullable private final Depset dwoFiles;

  public DebugPackageProvider(
      Label targetLabel,
      @Nullable Artifact strippedArtifact,
      Artifact unstrippedArtifact,
      @Nullable Artifact dwpArtifact, 
      @Nullable Depset dwoFiles) {
    Preconditions.checkNotNull(unstrippedArtifact);
    this.targetLabel = targetLabel;
    this.strippedArtifact = strippedArtifact;
    this.unstrippedArtifact = unstrippedArtifact;
    this.dwpArtifact = dwpArtifact;
    this.dwoFiles = dwoFiles;
  }

  @Override
  public Provider getProvider() {
    return PROVIDER;
  }

  /** Returns the label for the *_binary target. */
  @Override
  public final Label getTargetLabel() {
    return targetLabel;
  }

  /** Returns the stripped file (the explicit ".stripped" target). */
  @Override
  public final Artifact getStrippedArtifact() {
    return strippedArtifact;
  }

  /** Returns the unstripped file (the default executable target). */
  @Override
  public final Artifact getUnstrippedArtifact() {
    return unstrippedArtifact;
  }

  /** Returns the .dwp file (for fission builds) or null if --fission=no. */
  @Nullable
  @Override
  public final Artifact getDwpArtifact() {
    return dwpArtifact;
  }

  /** Returns the depset of dwo files (for fission builds), the depset is empty if --fission=no. */
  @Nullable
  @Override
  public final Depset getDwoFiles() {
    return dwoFiles;
  }

  /** Provider class for {@link DebugPackageProvider} objects. */
  public static class Provider extends BuiltinProvider<DebugPackageProvider>
      implements DebugPackageInfoApi.Provider<Artifact> {
    private Provider() {
      super(DebugPackageInfoApi.NAME, DebugPackageProvider.class);
    }

    @Override
    public DebugPackageProvider createDebugPackageInfo(
        Label starlarkTargetLabel,
        Object starlarkStrippedArtifact,
        Artifact starlarkUnstrippedArtifact,
        Object starlarkDwpArtifact,
        Object starlarkDwoFiles)
        throws EvalException {
      return new DebugPackageProvider(
          starlarkTargetLabel,
          nullIfNone(starlarkStrippedArtifact, Artifact.class),
          starlarkUnstrippedArtifact,
          nullIfNone(starlarkDwpArtifact, Artifact.class),nullIfNone(starlarkDwoFiles,Depset.class));
    }

    @Nullable
    private static <T> T nullIfNone(Object object, Class<T> type) {
      return object != Starlark.NONE ? type.cast(object) : null;
    }
  }
}
