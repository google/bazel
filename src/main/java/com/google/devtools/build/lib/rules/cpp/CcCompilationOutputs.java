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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcCompilationOutputsApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.LinkedHashSet;
import java.util.Set;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;

/** A structured representation of the compilation outputs of a C++ rule. */
public class CcCompilationOutputs implements CcCompilationOutputsApi<Artifact> {
  public static final CcCompilationOutputs EMPTY = builder().build();

  /**
   * All .o files built by the target.
   */
  private final ImmutableList<Artifact> objectFiles;

  /**
   * All .pic.o files built by the target.
   */
  private final ImmutableList<Artifact> picObjectFiles;

  /**
   * All C++ Modules files (e.g. .pcm files for clang) built by the target.
   */
  private final NestedSet<Artifact.DerivedArtifact> cpp20ModuleFiles;

  /**
   * All C++ Modules files (e.g. .pic.pcm files for clang) built by the target.
   */
  private final NestedSet<Artifact.DerivedArtifact> picCpp20ModuleFiles;

  /**
   * All .CXXModules.json files used by the target.
   */
  private final ImmutableList<Artifact> modulesInfoFiles;

  /**
   * All .pic.CXXModules.json files used by the target.
   */
  private final ImmutableList<Artifact> picModulesInfoFiles;
  /**
   * Maps all .o bitcode files coming from a ThinLTO C(++) compilation under our control to
   * information needed by the LTO indexing and backend steps.
   */
  private final LtoCompilationContext ltoCompilationContext;

  /**
   * All .dwo files built by the target, corresponding to .o outputs.
   */
  private final ImmutableList<Artifact> dwoFiles;

  /**
   * All .pic.dwo files built by the target, corresponding to .pic.o outputs.
   */
  private final ImmutableList<Artifact> picDwoFiles;

  /** All .gcno files built by the target, corresponding to .o outputs. */
  private final ImmutableList<Artifact> gcnoFiles;

  /** All .pic.gcno files built by the target, corresponding to .pic.gcno outputs. */
  private final ImmutableList<Artifact> picGcnoFiles;

  /**
   * All artifacts that are created if "--save_temps" is true.
   */
  private final NestedSet<Artifact> temps;

  /**
   * All token .h.processed files created when preprocessing or parsing headers.
   */
  private final ImmutableList<Artifact> headerTokenFiles;

  /** All .pcm files built by the target. */
  private final ImmutableList<Artifact> moduleFiles;

  private CcCompilationOutputs(
      ImmutableList<Artifact> objectFiles,
      ImmutableList<Artifact> picObjectFiles,
      NestedSet<Artifact.DerivedArtifact> cpp20ModuleFiles,
      NestedSet<Artifact.DerivedArtifact> picCpp20ModuleFiles,
      ImmutableList<Artifact> modulesInfoFiles,
      ImmutableList<Artifact> picModulesInfoFiles,
      LtoCompilationContext ltoCompilationContext,
      ImmutableList<Artifact> dwoFiles,
      ImmutableList<Artifact> picDwoFiles,
      ImmutableList<Artifact> gcnoFiles,
      ImmutableList<Artifact> picGcnoFiles,
      NestedSet<Artifact> temps,
      ImmutableList<Artifact> headerTokenFiles,
      ImmutableList<Artifact> moduleFiles) {
    this.objectFiles = objectFiles;
    this.picObjectFiles = picObjectFiles;
    this.cpp20ModuleFiles = cpp20ModuleFiles;
    this.picCpp20ModuleFiles = picCpp20ModuleFiles;
    this.modulesInfoFiles = modulesInfoFiles;
    this.picModulesInfoFiles = picModulesInfoFiles;
    this.ltoCompilationContext = ltoCompilationContext;
    this.dwoFiles = dwoFiles;
    this.picDwoFiles = picDwoFiles;
    this.gcnoFiles = gcnoFiles;
    this.picGcnoFiles = picGcnoFiles;
    this.temps = temps;
    this.headerTokenFiles = headerTokenFiles;
    this.moduleFiles = moduleFiles;
  }

  /**
   * Returns an unmodifiable view of the .o or .pic.o files set.
   *
   * @param usePic whether to return .pic.o files
   */
  public ImmutableList<Artifact> getObjectFiles(boolean usePic) {
    return usePic ? picObjectFiles : objectFiles;
  }

  /**
   * Returns an unmodifiable view of the .pcm or .pic.pcm files set.
   *
   * @param usePic whether to return .pic.pcm files
   */
  public NestedSet<Artifact.DerivedArtifact> getPcmFiles(boolean usePic) {
    return usePic ? picCpp20ModuleFiles : cpp20ModuleFiles;
  }

  public ImmutableList<Artifact> getModulesInfoFiles(boolean usePic) {
    return usePic ? picModulesInfoFiles : modulesInfoFiles;
  }

  @Override
  public Sequence<Artifact> getStarlarkObjects() throws EvalException {
    return StarlarkList.immutableCopyOf(getObjectFiles(/* usePic= */ false));
  }

  @Override
  public Sequence<Artifact> getStarlarkPicObjects() throws EvalException {
    return StarlarkList.immutableCopyOf(getObjectFiles(/* usePic= */ true));
  }

  @Override
  public Depset getStarlarkTemps(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return Depset.of(Artifact.class, getTemps());
  }

  @Override
  public Depset getStarlarkFilesToCompile(
      boolean parseHeaders, boolean usePic, StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return Depset.of(Artifact.class, getFilesToCompile(parseHeaders, usePic));
  }

  @Override
  public Sequence<Artifact> getStarlarkHeaderTokens(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return StarlarkList.immutableCopyOf(getHeaderTokenFiles());
  }

  @Override
  public Sequence<Artifact> getStarlarkModuleFiles(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return StarlarkList.immutableCopyOf(getModuleFiles());
  }

  /** Returns information about bitcode object files resulting from compilation. */
  public LtoCompilationContext getLtoCompilationContext() {
    return ltoCompilationContext;
  }

  @Override
  public LtoCompilationContext getLtoCompilationContextForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return ltoCompilationContext;
  }

  @Override
  public Sequence<Artifact> getStarlarkDwoFiles(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return StarlarkList.immutableCopyOf(getDwoFiles());
  }

  @Override
  public Sequence<Artifact> getStarlarkPicDwoFiles(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return StarlarkList.immutableCopyOf(getPicDwoFiles());
  }

  /**
   * Returns an unmodifiable view of the .dwo files set.
   */
  public ImmutableList<Artifact> getDwoFiles() {
    return dwoFiles;
  }

  /**
   * Returns an unmodifiable view of the .pic.dwo files set.
   */
  public ImmutableList<Artifact> getPicDwoFiles() {
    return picDwoFiles;
  }

  @Override
  public Sequence<Artifact> getStarlarkGcnoFiles(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return StarlarkList.immutableCopyOf(getGcnoFiles());
  }

  @Override
  public Sequence<Artifact> getStarlarkPicGcnoFiles(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return StarlarkList.immutableCopyOf(getPicGcnoFiles());
  }

  /** Returns an unmodifiable view of the .gcno files set. */
  public ImmutableList<Artifact> getGcnoFiles() {
    return gcnoFiles;
  }

  /** Returns an unmodifiable view of the .pic.gcno files set. */
  public ImmutableList<Artifact> getPicGcnoFiles() {
    return picGcnoFiles;
  }

  /**
   * Returns an unmodifiable view of the temp files set.
   */
  public NestedSet<Artifact> getTemps() {
    return temps;
  }

  /**
   * Returns an unmodifiable view of the .h.processed files.
   */
  public Iterable<Artifact> getHeaderTokenFiles() {
    return headerTokenFiles;
  }

  /** Returns an unmodifiable view of the .pcm files. */
  public Iterable<Artifact> getModuleFiles() {
    return moduleFiles;
  }

  /** Returns the output files that are considered "compiled" by this C++ compile action. */
  NestedSet<Artifact> getFilesToCompile(boolean parseHeaders, boolean usePic) {
    NestedSetBuilder<Artifact> files = NestedSetBuilder.stableOrder();
    files.addAll(getObjectFiles(usePic));
    if (parseHeaders) {
      files.addAll(getHeaderTokenFiles());
    }
    return files.build();
  }

  /** Creates a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for CcCompilationOutputs. */
  public static final class Builder {
    private final Set<Artifact> objectFiles = new LinkedHashSet<>();
    private final Set<Artifact> picObjectFiles = new LinkedHashSet<>();
    private final NestedSetBuilder<Artifact.DerivedArtifact> cpp20ModuleFiles = NestedSetBuilder.stableOrder();
    private final NestedSetBuilder<Artifact.DerivedArtifact> picCpp20ModuleFiles = NestedSetBuilder.stableOrder();
    private final Set<Artifact> modulesInfoFiles = new LinkedHashSet<>();
    private final Set<Artifact> picModulesInfoFiles = new LinkedHashSet<>();
    private final LtoCompilationContext.Builder ltoCompilationContext =
        new LtoCompilationContext.Builder();
    private final Set<Artifact> dwoFiles = new LinkedHashSet<>();
    private final Set<Artifact> picDwoFiles = new LinkedHashSet<>();
    private final Set<Artifact> gcnoFiles = new LinkedHashSet<>();
    private final Set<Artifact> picGcnoFiles = new LinkedHashSet<>();
    private final NestedSetBuilder<Artifact> temps = NestedSetBuilder.stableOrder();
    private final Set<Artifact> headerTokenFiles = new LinkedHashSet<>();
    private final Set<Artifact> moduleFiles = new LinkedHashSet<>();

    private Builder() {
      // private to avoid class initialization deadlock between this class and its outer class
    }

    public CcCompilationOutputs build() {
      return new CcCompilationOutputs(
          ImmutableList.copyOf(objectFiles),
          ImmutableList.copyOf(picObjectFiles),
          cpp20ModuleFiles.build(),
          picCpp20ModuleFiles.build(),
          ImmutableList.copyOf(modulesInfoFiles),
          ImmutableList.copyOf(picModulesInfoFiles),
          ltoCompilationContext.build(),
          ImmutableList.copyOf(dwoFiles),
          ImmutableList.copyOf(picDwoFiles),
          ImmutableList.copyOf(gcnoFiles),
          ImmutableList.copyOf(picGcnoFiles),
          temps.build(),
          ImmutableList.copyOf(headerTokenFiles),
          ImmutableList.copyOf(moduleFiles));
    }

    @CanIgnoreReturnValue
    public Builder merge(CcCompilationOutputs outputs) {
      this.objectFiles.addAll(outputs.objectFiles);
      this.picObjectFiles.addAll(outputs.picObjectFiles);
      this.cpp20ModuleFiles.addTransitive(outputs.cpp20ModuleFiles);
      this.picCpp20ModuleFiles.addTransitive(outputs.picCpp20ModuleFiles);
      this.modulesInfoFiles.addAll(outputs.modulesInfoFiles);
      this.picModulesInfoFiles.addAll(outputs.picModulesInfoFiles);
      this.dwoFiles.addAll(outputs.dwoFiles);
      this.picDwoFiles.addAll(outputs.picDwoFiles);
      this.gcnoFiles.addAll(outputs.gcnoFiles);
      this.picGcnoFiles.addAll(outputs.picGcnoFiles);
      this.temps.addTransitive(outputs.temps);
      this.headerTokenFiles.addAll(outputs.headerTokenFiles);
      this.moduleFiles.addAll(outputs.moduleFiles);
      this.ltoCompilationContext.addAll(outputs.ltoCompilationContext);
      return this;
    }

    /** Adds an object file. */
    @CanIgnoreReturnValue
    public Builder addObjectFile(Artifact artifact) {
      // We skip file extension checks for TreeArtifacts because they represent directory artifacts
      // without a file extension.
      Preconditions.checkArgument(
          artifact.isTreeArtifact() || Link.OBJECT_FILETYPES.matches(artifact.getFilename()));
      objectFiles.add(artifact);
      return this;
    }

    /** Adds a C++ Modules file. */
    @CanIgnoreReturnValue
    public Builder addCpp20ModuleFile(Artifact.DerivedArtifact artifact) {
      cpp20ModuleFiles.add(artifact);
      return this;
    }

    /** Adds a modules info file. */
    @CanIgnoreReturnValue
    public Builder addModulesInfoFile(Artifact artifact) {
      modulesInfoFiles.add(artifact);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addObjectFiles(Iterable<Artifact> artifacts) {
      for (Artifact artifact : artifacts) {
        Preconditions.checkArgument(
            artifact.isTreeArtifact() || Link.OBJECT_FILETYPES.matches(artifact.getFilename()));
      }
      Iterables.addAll(objectFiles, artifacts);
      return this;
    }

    /** Adds a pic object file. */
    @CanIgnoreReturnValue
    public Builder addPicObjectFile(Artifact artifact) {
      picObjectFiles.add(artifact);
      return this;
    }

    /** Adds a pic C++ Modules file. */
    @CanIgnoreReturnValue
    public Builder addPicCpp20ModuleFile(Artifact.DerivedArtifact artifact) {
      picCpp20ModuleFiles.add(artifact);
      return this;
    }
    /** Adds a pic modules info file. */
    @CanIgnoreReturnValue
    public Builder addPicModulesInfoFile(Artifact artifact) {
      picModulesInfoFiles.add(artifact);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addLtoBitcodeFile(
        Artifact fullBitcode, Artifact ltoIndexingBitcode, ImmutableList<String> copts) {
      ltoCompilationContext.addBitcodeFile(fullBitcode, ltoIndexingBitcode, copts);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addLtoCompilationContext(LtoCompilationContext ltoCompilationContext) {
      this.ltoCompilationContext.addAll(ltoCompilationContext);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addPicObjectFiles(Iterable<Artifact> artifacts) {
      for (Artifact artifact : artifacts) {
        Preconditions.checkArgument(
            artifact.isTreeArtifact() || Link.OBJECT_FILETYPES.matches(artifact.getFilename()));
      }

      Iterables.addAll(picObjectFiles, artifacts);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addDwoFile(Artifact artifact) {
      dwoFiles.add(artifact);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addPicDwoFile(Artifact artifact) {
      picDwoFiles.add(artifact);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addGcnoFile(Artifact artifact) {
      gcnoFiles.add(artifact);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addPicGcnoFile(Artifact artifact) {
      picGcnoFiles.add(artifact);
      return this;
    }

    /** Adds temp files. */
    @CanIgnoreReturnValue
    public Builder addTemps(Iterable<Artifact> artifacts) {
      temps.addAll(artifacts);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addHeaderTokenFile(Artifact artifact) {
      headerTokenFiles.add(artifact);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addModuleFile(Artifact artifact) {
      moduleFiles.add(artifact);
      return this;
    }
  }
}
