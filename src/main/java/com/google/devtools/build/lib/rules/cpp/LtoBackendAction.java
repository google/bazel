// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static java.util.stream.Collectors.joining;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.LtoAction;
import com.google.devtools.build.lib.server.FailureDetails.LtoAction.Code;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Action used by LtoBackendArtifacts to create an LtoBackendAction. Similar to {@link SpawnAction},
 * except that inputs are discovered from the imports file created by the ThinLTO indexing step for
 * each backend artifact.
 *
 * <p>See {@link LtoBackendArtifacts} for a high level description of the ThinLTO build process. The
 * LTO indexing step takes all bitcode .o files and decides which other .o file symbols can be
 * imported/inlined. The additional input files for each backend action are then written to an
 * imports file. Therefore, these new inputs must be discovered here by subsetting the imports paths
 * from the set of all bitcode artifacts, before executing the backend action.
 *
 * <p>For more information on ThinLTO see
 * http://blog.llvm.org/2016/06/thinlto-scalable-and-incremental-lto.html.
 */
public final class LtoBackendAction extends SpawnAction {
  private static final String GUID = "72ce1eca-4625-4e24-a0d8-bb91bb8b0e0e";

  private final NestedSet<Artifact> mandatoryInputs;
  private final BitcodeFiles bitcodeFiles;
  private final Artifact imports;
  private boolean inputsDiscovered = false;

  public LtoBackendAction(
      NestedSet<Artifact> inputs,
      @Nullable BitcodeFiles allBitcodeFiles,
      @Nullable Artifact importsFile,
      ImmutableSet<Artifact> outputs,
      ActionOwner owner,
      CommandLines argv,
      ActionEnvironment env,
      Map<String, String> executionInfo,
      CharSequence progressMessage,
      String mnemonic) {
    super(
        owner,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        inputs,
        outputs,
        AbstractAction.DEFAULT_RESOURCE_SET,
        argv,
        env,
        ImmutableMap.copyOf(executionInfo),
        progressMessage,
        mnemonic,
        OutputPathsMode.OFF);
    mandatoryInputs = inputs;
    Preconditions.checkState(
        (allBitcodeFiles == null) == (importsFile == null),
        "Either both or neither bitcodeFiles and imports files should be null");
    bitcodeFiles = allBitcodeFiles;
    imports = importsFile;
  }

  @Override
  public boolean discoversInputs() {
    return imports != null;
  }

  @Override
  protected boolean inputsDiscovered() {
    return inputsDiscovered;
  }

  @Override
  protected void setInputsDiscovered(boolean inputsDiscovered) {
    this.inputsDiscovered = inputsDiscovered;
  }

  /**
   * Given a map of path to artifact, and a path, returns the artifact whose key is in the map, or
   * if none, an artifact whose key matches a prefix of the path. Assumes that artifacts whose paths
   * are directories are tree artifacts. Assumes that no artifact key is a sub directory of another
   * artifact key. For example, "path/file1" may return the artifact whose path is "path/file1" or
   * whose path is "path/". Returns empty if there are no matches.
   */
  private Optional<Artifact> getArtifactOrTreeArtifact(
      PathFragment path, Map<PathFragment, Artifact> pathToArtifact) {
    PathFragment currentPath = path;
    while (!currentPath.isEmpty()) {
      if (pathToArtifact.containsKey(currentPath)) {
        return Optional.of(pathToArtifact.get(currentPath));
      } else {
        currentPath = currentPath.getParentDirectory();
      }
    }
    return Optional.empty();
  }

  /**
   * Throws an error if any of the input paths is not in the bitcodeFiles or in a subdirecorty of a
   * file in bitcodeFiles
   */
  private NestedSet<Artifact> computeBitcodeInputs(
      HashSet<PathFragment> inputPaths, ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException {
    NestedSetBuilder<Artifact> bitcodeInputs = NestedSetBuilder.stableOrder();
    Map<PathFragment, Artifact> execPathToArtifact = bitcodeFiles.getFilesArtifactPathMap();
    Set<PathFragment> missingInputs = new HashSet<>();
    for (PathFragment inputPath : inputPaths) {
      Optional<Artifact> maybeArtifact = getArtifactOrTreeArtifact(inputPath, execPathToArtifact);
      if (maybeArtifact.isPresent()) {
        bitcodeInputs.add(maybeArtifact.get());
      } else {
        // One of the inputs is not present. We add it to missingInputs and will fail.
        missingInputs.add(inputPath);
      }
    }
    if (!missingInputs.isEmpty()) {
      String message =
          String.format(
              "error computing inputs from imports file: %s, missing bitcode files (first 10): %s",
              actionExecutionContext.getInputPath(imports),
              // Limit the reported count to protect against a large error message.
              missingInputs.stream()
                  .map(Object::toString)
                  .sorted()
                  .limit(10)
                  .collect(joining(", ")));
      DetailedExitCode code = createDetailedExitCode(message, Code.MISSING_BITCODE_FILES);
      throw new ActionExecutionException(message, this, false, code);
    }
    return bitcodeInputs.build();
  }

  @Nullable
  @Override
  public NestedSet<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException {
    Path importsFilePath = actionExecutionContext.getInputPath(imports);
    ImmutableList<String> lines;
    try {
      lines = FileSystemUtils.readLinesAsLatin1(importsFilePath);
    } catch (IOException e) {
      String message =
          String.format(
              "error reading imports file %s: %s",
              actionExecutionContext.getInputPath(imports), e.getMessage());
      DetailedExitCode code = createDetailedExitCode(message, Code.IMPORTS_READ_IO_EXCEPTION);
      throw new ActionExecutionException(message, e, this, false, code);
    }

    // Build set of files this LTO backend artifact will import from.
    HashSet<PathFragment> importSet = new HashSet<>();
    for (String line : lines) {
      if (line.isEmpty()) {
        continue;
      }
      PathFragment execPath = PathFragment.create(line);
      if (execPath.isAbsolute()) {
        String message =
            String.format(
                "Absolute paths not allowed in imports file %s: %s",
                actionExecutionContext.getInputPath(imports), execPath);
        DetailedExitCode code =
            createDetailedExitCode(message, Code.INVALID_ABSOLUTE_PATH_IN_IMPORTS);
        throw new ActionExecutionException(message, this, false, code);
      }
      importSet.add(execPath);
    }

    // Convert the import set of paths to the set of bitcode file artifacts.
    // Throws an error if there is any path in the importset that is not pat of any artifact
    NestedSet<Artifact> bitcodeInputSet = computeBitcodeInputs(importSet, actionExecutionContext);
    updateInputs(
        NestedSetBuilder.fromNestedSet(bitcodeInputSet).addTransitive(mandatoryInputs).build());
    return bitcodeInputSet;
  }

  @Override
  public NestedSet<Artifact> getOriginalInputs() {
    return mandatoryInputs;
  }

  private static DetailedExitCode createDetailedExitCode(String message, Code detailedCode) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setLtoAction(LtoAction.newBuilder().setCode(detailedCode))
            .build());
  }

  @Override
  public NestedSet<Artifact> getMandatoryInputs() {
    return mandatoryInputs;
  }

  @Override
  public NestedSet<Artifact> getAllowedDerivedInputs() {
    return bitcodeFiles.getFiles();
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws InterruptedException {
    fp.addString(GUID);
    try {
      fp.addStrings(getArguments());
    } catch (CommandLineExpansionException e) {
      throw new AssertionError("LtoBackendAction command line expansion cannot fail", e);
    }
    fp.addString(getMnemonic());
    for (Artifact input : mandatoryInputs.toList()) {
      fp.addPath(input.getExecPath());
    }
    if (imports != null) {
      bitcodeFiles.addToFingerprint(fp);
      fp.addPath(imports.getExecPath());
    }
    getEnvironment().addTo(fp);
    fp.addStringMap(getExecutionInfo());
  }

  /** Builder class to construct {@link LtoBackendAction} instances. */
  public static class Builder extends SpawnAction.Builder {
    private BitcodeFiles bitcodeFiles;
    private Artifact imports;

    public Builder() {
      super();
    }

    public Builder(Builder other) {
      super(other);
      bitcodeFiles = other.bitcodeFiles;
      imports = other.imports;
    }

    @CanIgnoreReturnValue
    public Builder addImportsInfo(BitcodeFiles allBitcodeFiles, Artifact importsFile) {
      this.bitcodeFiles = allBitcodeFiles;
      this.imports = importsFile;
      return this;
    }

    @Override
    protected SpawnAction createSpawnAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputsAndTools,
        ImmutableSet<Artifact> outputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment env,
        @Nullable BuildConfigurationValue configuration,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        String mnemonic) {
      return new LtoBackendAction(
          inputsAndTools,
          bitcodeFiles,
          imports,
          outputs,
          owner,
          commandLines,
          env,
          executionInfo,
          progressMessage,
          mnemonic);
    }
  }
}
