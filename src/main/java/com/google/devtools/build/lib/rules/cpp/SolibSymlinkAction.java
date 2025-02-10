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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.exec.SpawnLogContext;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.SymlinkAction.Code;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Creates mangled symlinks in the solib directory for all shared libraries. For shared libraries
 * that have potential to contain a SONAME field, create a link to the shared library parent
 * directory instead - so that the name of the library file is preserved.
 *
 * <p>Such symlinks are used by the linker to ensure that all rpath entries can be specified
 * relative to the $ORIGIN.
 */
@Immutable
public final class SolibSymlinkAction extends AbstractAction {
  private final Artifact symlink;

  private SolibSymlinkAction(ActionOwner owner, Artifact primaryInput, Artifact primaryOutput) {
    super(
        owner,
        NestedSetBuilder.create(Order.STABLE_ORDER, primaryInput),
        ImmutableSet.of(primaryOutput));

    Preconditions.checkArgument(Link.SHARED_LIBRARY_FILETYPES.matches(primaryInput.getFilename()));
    this.symlink = Preconditions.checkNotNull(primaryOutput);
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    Path mangledPath = actionExecutionContext.getInputPath(symlink);
    try {
      mangledPath.createSymbolicLink(actionExecutionContext.getInputPath(getPrimaryInput()));
    } catch (IOException e) {
      String message =
          String.format(
              "failed to create _solib symbolic link '%s' to target '%s': %s",
              symlink.prettyPrint(), getPrimaryInput(), e.getMessage());
      DetailedExitCode code =
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(message)
                  .setSymlinkAction(
                      FailureDetails.SymlinkAction.newBuilder()
                          .setCode(Code.LINK_CREATION_IO_EXCEPTION))
                  .build());
      throw new ActionExecutionException(message, e, this, false, code);
    }

    SpawnLogContext logContext = actionExecutionContext.getContext(SpawnLogContext.class);
    if (logContext != null) {
      try {
        logContext.logSymlinkAction(this);
      } catch (IOException e) {
        String message =
            String.format(
                "failed to log creation of _solib symbolic link '%s' to target '%s': %s",
                symlink.prettyPrint(), getPrimaryInput(), e.getMessage());
        DetailedExitCode code =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setSymlinkAction(
                        FailureDetails.SymlinkAction.newBuilder()
                            .setCode(Code.LINK_LOG_IO_EXCEPTION))
                    .build());
        throw new ActionExecutionException(message, e, this, false, code);
      }
    }
    SymlinkAction.maybeInjectMetadata(this, actionExecutionContext);
    return ActionResult.EMPTY;
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp) {
    fp.addPath(symlink.getExecPath());
    fp.addPath(getPrimaryInput().getExecPath());
  }

  @Override
  public String getMnemonic() {
    return "SolibSymlink";
  }

  @Nullable
  @Override
  protected String getRawProgressMessage() {
    return null;
  }

  /**
   * Replaces shared library artifact with mangled symlink and creates related symlink action. For
   * artifacts that should retain filename (e.g. libraries with SONAME tag), link is created to the
   * parent directory instead.
   *
   * <p>This action is performed to minimize number of -rpath entries used during linking process
   * (by essentially "collecting" as many shared libraries as possible in the single directory),
   * since we will be paying quadratic price for each additional entry on the -rpath.
   *
   * @param actionConstructionContext action construction context of rule requesting symlink
   * @param solibDir String giving the solib directory
   * @param library Shared library artifact that needs to be mangled.
   * @param preserveName whether to preserve the name of the library
   * @param prefixConsumer whether to prefix the output artifact name with the label of the consumer
   * @return mangled symlink artifact.
   */
  public static Artifact getDynamicLibrarySymlink(
      ActionConstructionContext actionConstructionContext,
      String solibDir,
      final Artifact library,
      boolean preserveName,
      boolean prefixConsumer) {
    PathFragment mangledName =
        getMangledName(
            actionConstructionContext.getOwner().getLabel(),
            solibDir,
            actionConstructionContext.getConfiguration().getMnemonic(),
            library.getRootRelativePath(),
            preserveName,
            prefixConsumer);
    return getDynamicLibrarySymlinkInternal(actionConstructionContext, library, mangledName);
  }

  /**
   * Replaces shared library artifact with user specified symlink and creates related symlink
   * action.
   *
   * <p>This action is performed to minimize number of -rpath entries used during linking process
   * (by essentially "collecting" as many shared libraries as possible in the single directory),
   * since we will be paying quadratic price for each additional entry on the -rpath.
   *
   * @param actionConstructionContext action construction context of rule requesting symlink
   * @param solibDir String giving the solib directory
   * @param library Shared library artifact that needs to be linked.
   * @param path Symlink path underneath the solib directory.
   * @return linked symlink artifact.
   */
  public static Artifact getDynamicLibrarySymlink(
      ActionConstructionContext actionConstructionContext,
      String solibDir,
      final Artifact library,
      PathFragment path) {
    Preconditions.checkArgument(Link.SHARED_LIBRARY_FILETYPES.matches(library.getFilename()));
    Preconditions.checkArgument(Link.SHARED_LIBRARY_FILETYPES.matches(path.getBaseName()));
    Preconditions.checkArgument(
        !library.getRootRelativePath().getPathString().startsWith("_solib_"));

    PathFragment solibDirPath = PathFragment.create(solibDir);
    PathFragment linkName = solibDirPath.getRelative(path);
    return getDynamicLibrarySymlinkInternal(actionConstructionContext, library, linkName);
  }

  /**
   * Version of {@link #getDynamicLibrarySymlink} for the special case of C++ runtime libraries.
   * These are handled differently than other libraries: neither their names nor directories are
   * mangled, i.e. libstdc++.so.6 is symlinked from _solib_[arch]/libstdc++.so.6
   */
  public static Artifact getCppRuntimeSymlink(
      RuleContext ruleContext,
      Artifact library,
      String toolchainProvidedSolibDir,
      String solibDirOverride) {
    PathFragment solibDir =
        PathFragment.create(
            solibDirOverride != null ? solibDirOverride : toolchainProvidedSolibDir);
    PathFragment symlinkName = solibDir.getRelative(library.getRootRelativePath().getBaseName());
    return getDynamicLibrarySymlinkInternal(
        /* actionConstructionContext= */ ruleContext,
        library,
        symlinkName);
  }

  /**
   * Internal implementation that takes a pre-determined symlink name; supports both the generic
   * {@link #getDynamicLibrarySymlink} and the specialized {@link #getCppRuntimeSymlink}.
   */
  private static Artifact getDynamicLibrarySymlinkInternal(
      ActionConstructionContext actionConstructionContext,
      Artifact library,
      PathFragment symlinkName) {
    Preconditions.checkArgument(Link.SHARED_LIBRARY_FILETYPES.matches(library.getFilename()));
    Preconditions.checkArgument(
        !library.getRootRelativePath().getPathString().startsWith("_solib_"));

    // Ignore libraries that are already represented by the symlinks.
    ArtifactRoot root = actionConstructionContext.getBinDirectory();
    Artifact symlink = actionConstructionContext.getShareableArtifact(symlinkName, root);
    actionConstructionContext.registerAction(
        new SolibSymlinkAction(actionConstructionContext.getActionOwner(), library, symlink));
    return symlink;
  }

  @VisibleForTesting public static final int MAX_FILENAME_LENGTH = 255;

  private static String maybeHashPreserveExtension(String filename) {
    if (filename.length() <= MAX_FILENAME_LENGTH) {
      return filename;
    } else {
      String hashedName = Hashing.sha256().hashString(filename, UTF_8).toString();
      String extension = Files.getFileExtension(filename);
      if (extension.isEmpty()) {
        return hashedName;
      } else {
        return hashedName + "." + extension;
      }
    }
  }

  /**
   * Returns the name of the symlink that will be created for a library, given its name.
   *
   * @param label label of the rule calling this
   * @param solibDir a String giving the solib directory
   * @param libraryPath the root-relative path of the library
   * @param preserveName true if filename should be preserved
   * @param prefixConsumer true if the result should be prefixed with the label of the consumer
   * @returns root relative path name
   */
  private static PathFragment getMangledName(
      Label label,
      String solibDir,
      String mnemonic,
      PathFragment libraryPath,
      boolean preserveName,
      boolean prefixConsumer) {
    String escapedRulePath = Actions.escapedPath("_" + label);
    String soname = getDynamicLibrarySoname(libraryPath, preserveName, mnemonic);
    PathFragment solibDirPath = PathFragment.create(solibDir);
    if (preserveName) {
      String escapedLibraryPath =
          Actions.escapedPath("_" + libraryPath.getParentDirectory().getPathString());
      String escapedFullPath =
          prefixConsumer ? escapedRulePath + "__" + escapedLibraryPath : escapedLibraryPath;
      PathFragment mangledDir =
          solibDirPath.getRelative(maybeHashPreserveExtension(escapedFullPath));
      return mangledDir.getRelative(soname);
    } else {
      String filename = prefixConsumer ? escapedRulePath + "__" + soname : soname;
      return solibDirPath.getRelative(maybeHashPreserveExtension(filename));
    }
  }

  /**
   * Compute the SONAME to use for a dynamic library. This name is basically the name of the shared
   * library in its final symlinked location.
   *
   * @param libraryPath name of the shared library that needs to be mangled
   * @param preserveName true if filename should be preserved, false - mangled
   * @param mnemonic the output directory mnemonic, to be mangled in for nondefault configurations
   * @return soname to embed in the dynamic library
   */
  public static String getDynamicLibrarySoname(
      PathFragment libraryPath, boolean preserveName, String mnemonic) {
    String mangledName;
    if (preserveName) {
      mangledName = libraryPath.getBaseName();
    } else {
      String mnemonicMangling = "";
      if (mnemonic.contains("ST-")) {
        mnemonicMangling = mnemonic.substring(mnemonic.indexOf("ST-")) + "_";
      }
      mangledName = "lib" + mnemonicMangling + Actions.escapedPath(libraryPath.getPathString());
    }
    return mangledName;
  }

  @Override
  public boolean mayInsensitivelyPropagateInputs() {
    return true;
  }

  @Override
  public PlatformInfo getExecutionPlatform() {
    return PlatformInfo.EMPTY_PLATFORM_INFO;
  }

  @Override
  public ImmutableMap<String, String> getExecProperties() {
    // SolibSymlinkAction is platform agnostic.
    return ImmutableMap.of();
  }
}
