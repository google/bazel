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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.Linkstamp;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.VariablesExtension;
import com.google.devtools.build.lib.rules.cpp.CppLinkActionBuilder.LinkActionConstruction;
import com.google.devtools.build.lib.rules.cpp.LegacyLinkerInputs.LibraryInput;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.cpp.Link.LinkerOrArchiver;
import com.google.devtools.build.lib.rules.cpp.Link.LinkingMode;
import com.google.devtools.build.lib.rules.cpp.Link.Picness;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.SymbolGenerator;

// LINT.IfChange
/**
 * A class to create C/C++ link actions in a way that is consistent with cc_library. Rules that
 * generate source files and emulate cc_library on top of that should use this class instead of the
 * lower-level APIs in CppHelper and CppLinkActionBuilder.
 *
 * <p>Rules that want to use this class are required to have implicit dependencies on the toolchain,
 * the STL, and so on. Optionally, they can also have copts, and malloc attributes, but note that
 * these require explicit calls to the corresponding setter methods.
 */
public final class CcLinkingHelper {
  private final CppSemantics semantics;

  private final NestedSetBuilder<Artifact> additionalLinkerInputsBuilder =
      NestedSetBuilder.stableOrder();
  private final List<Artifact> linkerOutputs = new ArrayList<>();
  private final List<String> linkopts = new ArrayList<>();
  private final List<CcLinkingContext> ccLinkingContexts = new ArrayList<>();
  private final NestedSetBuilder<Artifact> linkstamps = NestedSetBuilder.stableOrder();
  private final List<Artifact> linkActionInputs = new ArrayList<>();

  @Nullable private Artifact linkerOutputArtifact;
  private LinkTargetType staticLinkType = LinkTargetType.STATIC_LIBRARY;
  private LinkTargetType dynamicLinkType = LinkTargetType.NODEPS_DYNAMIC_LIBRARY;
  private NestedSet<Artifact> additionalLinkerInputs;
  private boolean neverlink;

  private boolean emitInterfaceSharedLibraries;
  private boolean shouldCreateDynamicLibrary = true;
  private boolean shouldCreateStaticLibraries = true;
  private boolean willOnlyBeLinkedIntoDynamicLibraries;
  private final List<VariablesExtension> variablesExtensions = new ArrayList<>();
  private boolean useTestOnlyFlags;
  private LinkingMode linkingMode = LinkingMode.DYNAMIC;
  private boolean nativeDeps;
  private boolean wholeArchive;
  private final ImmutableList.Builder<String> additionalLinkstampDefines = ImmutableList.builder();

  private final FeatureConfiguration featureConfiguration;
  private final CcToolchainProvider ccToolchain;
  private final FdoContext fdoContext;
  private String linkedArtifactNameSuffix = "";
  private String linkedDLLNameSuffix = "";

  private final LinkActionConstruction linkActionConstruction;
  private final String targetName;
  private final SymbolGenerator<?> symbolGenerator;
  private final ImmutableMap<String, String> executionInfo;

  private boolean isStampingEnabled;
  private boolean isTestOrTestOnlyTarget;

  /**
   * Creates a CcLinkingHelper that outputs artifacts in a given configuration.
   *
   * @param targetName the name of the rule being built
   * @param linkActionConstruction the ActionConstructionContext of the rule being built
   * @param semantics CppSemantics for the build
   * @param featureConfiguration activated features and action configs for the build
   * @param ccToolchain the C++ toolchain provider for the build
   * @param fdoContext the C++ FDO optimization support provider for the build
   * @param executionInfo the execution info data associated with a rule
   */
  public CcLinkingHelper(
      String targetName,
      LinkActionConstruction linkActionConstruction,
      CppSemantics semantics,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider ccToolchain,
      FdoContext fdoContext,
      SymbolGenerator<?> symbolGenerator,
      ImmutableMap<String, String> executionInfo) {
    this.semantics = Preconditions.checkNotNull(semantics);
    this.featureConfiguration = Preconditions.checkNotNull(featureConfiguration);
    this.ccToolchain = Preconditions.checkNotNull(ccToolchain);
    this.fdoContext = Preconditions.checkNotNull(fdoContext);
    this.targetName = targetName;
    this.linkActionConstruction = linkActionConstruction;
    this.symbolGenerator = symbolGenerator;
    this.executionInfo = executionInfo;
  }

  @CanIgnoreReturnValue
  public CcLinkingHelper setNativeDeps(boolean nativeDeps) {
    this.nativeDeps = nativeDeps;
    return this;
  }

  @CanIgnoreReturnValue
  public CcLinkingHelper setWholeArchive(boolean wholeArchive) {
    this.wholeArchive = wholeArchive;
    return this;
  }

  @CanIgnoreReturnValue
  public CcLinkingHelper addAdditionalLinkstampDefines(List<String> additionalLinkstampDefines) {
    this.additionalLinkstampDefines.addAll(additionalLinkstampDefines);
    return this;
  }

  /**
   * Adds the corresponding non-code files as linker inputs.
   *
   * <p>TODO(bazel-team): There is no practical difference in non-code inputs and additional linker
   * inputs in CppLinkActionBuilder. So these should be merged. Even before that happens, it's
   * totally fine for nonCodeLinkerInputs to contains precompiled libraries.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper addNonCodeLinkerInputs(List<Artifact> nonCodeLinkerInputs) {
    this.additionalLinkerInputsBuilder.addAll(nonCodeLinkerInputs);
    return this;
  }

  @CanIgnoreReturnValue
  public CcLinkingHelper addTransitiveAdditionalLinkerInputs(
      NestedSet<Artifact> additionalLinkerInputs) {
    this.additionalLinkerInputsBuilder.addTransitive(additionalLinkerInputs);
    return this;
  }

  /** TODO(bazel-team): Add to Starlark API */
  @CanIgnoreReturnValue
  public CcLinkingHelper addLinkerOutputs(List<Artifact> linkerOutputs) {
    this.linkerOutputs.addAll(linkerOutputs);
    return this;
  }

  /** Adds the given options as linker options to the link command. */
  @CanIgnoreReturnValue
  public CcLinkingHelper addLinkopts(Iterable<String> linkopts) {
    Iterables.addAll(this.linkopts, linkopts);
    return this;
  }

  /**
   * Adds additional {@link CcLinkingContext} that will be used everywhere where CcLinkingInfos were
   * obtained from deps.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper addCcLinkingContexts(Iterable<CcLinkingContext> ccLinkingContexts) {
    Iterables.addAll(this.ccLinkingContexts, ccLinkingContexts);
    return this;
  }

  /** Adds a variableExtension to template the crosstool. */
  @CanIgnoreReturnValue
  public CcLinkingHelper addVariableExtension(VariablesExtension variableExtension) {
    Preconditions.checkNotNull(variableExtension);
    this.variablesExtensions.add(variableExtension);
    return this;
  }

  /**
   * Overrides the path for the generated dynamic library - this should only be called if the
   * dynamic library is an implicit or explicit output of the rule, i.e., if it is accessible by
   * name from other rules in the same package. Set to {@code null} to use the default computation.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper setLinkerOutputArtifact(@Nullable Artifact linkerOutputArtifact) {
    this.linkerOutputArtifact = linkerOutputArtifact;
    return this;
  }

  /**
   * Marks the output of this rule as alwayslink, i.e., the corresponding symbols will be retained
   * by the linker even if they are not otherwise used. This is useful for libraries that register
   * themselves somewhere during initialization.
   *
   * <p>This only sets the link type (see {@link #setStaticLinkType}), either to a static library or
   * to an alwayslink static library (blaze uses a different file extension to signal alwayslink to
   * downstream code).
   *
   * @deprecated This is only set here for naming always to link static libraries with the *.lo
   *     extension. This is no longer needed because {@link
   *     com.google.devtools.build.lib.rules.cpp.LinkerInputs.LibraryToLink}s already carry
   *     information about whether a library should always be linked or not. This method will be
   *     removed when we no longer use *.lo for always to link static libraries in native
   *     cc_library.
   */
  @CanIgnoreReturnValue
  @Deprecated
  public CcLinkingHelper setAlwayslink(boolean alwayslink) {
    staticLinkType =
        alwayslink ? LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY : LinkTargetType.STATIC_LIBRARY;
    return this;
  }

  /**
   * Directly set the link type. This can be used instead of {@link #setAlwayslink}. Setting
   * anything other than a static link causes this class to skip the link action creation. This
   * exists only for Objective-C.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper setStaticLinkType(LinkTargetType linkType) {
    Preconditions.checkNotNull(linkType);
    Preconditions.checkState(linkType.linkerOrArchiver() == LinkerOrArchiver.ARCHIVER);
    this.staticLinkType = linkType;
    return this;
  }

  /**
   * Marks the resulting code as neverlink, i.e., the code will not be linked into dependent
   * libraries or binaries - the header files are still available.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper setNeverLink(boolean neverlink) {
    this.neverlink = neverlink;
    return this;
  }

  /*
   * Adds a suffix for paths of linked artifacts. Normally their paths are derived solely from rule
   * labels. In the case of multiple callers (e.g., aspects) acting on a single rule, they may
   * generate the same linked artifact and therefore lead to artifact conflicts. This method
   * provides a way to avoid this artifact conflict by allowing different callers acting on the same
   * rule to provide a suffix that will be used to scope their own linked artifacts.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper setLinkedArtifactNameSuffix(String suffix) {
    this.linkedArtifactNameSuffix = Preconditions.checkNotNull(suffix);
    return this;
  }

  /*
   * Adds a suffix (_{hash}) for path of linked DLL. This will be enabled only when rename_dll is
   * enabled and the linked artifact's LinkTargetType is NODEPS_DYNAMIC_LIBRARY
   * (i.e DLL generated by cc_library). We have to add the suffix to make sure the CppLinkAction
   * link against the renamed DLL. If not, CppLinkAction will link against the DLL whose name is the
   * same as the name of cc_library.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper setLinkedDLLNameSuffix(String suffix) {
    this.linkedDLLNameSuffix = Preconditions.checkNotNull(suffix);
    return this;
  }

  /**
   * Enables the optional generation of interface dynamic libraries - this is only used when the
   * linker generates a dynamic library, and only if the crosstool supports it. The default is not
   * to generate interface dynamic libraries.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper emitInterfaceSharedLibraries(boolean emitInterfaceSharedLibraries) {
    this.emitInterfaceSharedLibraries = emitInterfaceSharedLibraries;
    return this;
  }

  /**
   * This enables or disables the generation of a dynamic library link action. The default is to
   * generate a dynamic library. Note that the selection between dynamic or static linking is
   * performed at the binary rule level.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper setShouldCreateDynamicLibrary(boolean emitDynamicLibrary) {
    this.shouldCreateDynamicLibrary = emitDynamicLibrary;
    return this;
  }

  /**
   * When shouldCreateStaticLibraries is true, there are no actions created for static libraries.
   */
  @CanIgnoreReturnValue
  public CcLinkingHelper setShouldCreateStaticLibraries(boolean emitStaticLibraries) {
    this.shouldCreateStaticLibraries = emitStaticLibraries;
    return this;
  }

  /** Create the C++ link actions, and the corresponding linking related providers. */
  public CcLinkingOutputs link(CcCompilationOutputs ccOutputs)
      throws EvalException, RuleErrorException, InterruptedException {
    Preconditions.checkNotNull(ccOutputs);

    Preconditions.checkState(additionalLinkerInputs == null);
    additionalLinkerInputs = additionalLinkerInputsBuilder.build();

    // Create link actions (only if there are object files or if explicitly requested).
    //
    // On some systems, the linker gives an error message if there are no input files. Even with
    // the check above, this can still happen if there is a .nopic.o or .o files in srcs, but no
    // other files. To fix that, we'd have to check for each link action individually.
    //
    // An additional pre-existing issue is that the header check tokens are dropped if we don't
    // generate any link actions, effectively disabling header checking in some cases.
    return createCcLinkActions(ccOutputs);
  }

  public CcLinkingContext buildCcLinkingContextFromLibrariesToLink(
      List<LibraryToLink> librariesToLink, CcCompilationContext ccCompilationContext)
      throws EvalException, InterruptedException {
    ImmutableList.Builder<Linkstamp> linkstampBuilder = ImmutableList.builder();
    for (Artifact linkstamp : linkstamps.build().toList()) {
      try {
        linkstampBuilder.add(
            new Linkstamp( // throws InterruptedException
                linkstamp,
                ccCompilationContext.getDeclaredIncludeSrcs(),
                linkActionConstruction.getContext().getActionKeyContext()));
      } catch (CommandLineExpansionException ex) {
        throw new AssertionError("unexpected failure of command line expansion", ex);
      }
    }
    CcLinkingContext ccLinkingContext = CcLinkingContext.EMPTY;
    if (!neverlink) {
      // We want an empty set if there are no link options. We have to make sure we don't
      // create a LinkOptions instance that contains an empty list.
      try {
        ccLinkingContext =
            CcLinkingContext.builder()
                .setOwner(
                    Label.create(
                        linkActionConstruction
                            .getContext()
                            .getActionOwner()
                            .getLabel()
                            .getPackageIdentifier(),
                        targetName))
                .addUserLinkFlags(
                    linkopts.isEmpty()
                        ? ImmutableList.of()
                        : ImmutableList.of(
                            CcLinkingContext.LinkOptions.of(
                                ImmutableList.copyOf(linkopts), symbolGenerator.generate())))
                .addLibraries(librariesToLink)
                // additionalLinkerInputsBuilder not expected to be a big list for now.
                .addNonCodeInputs(additionalLinkerInputsBuilder.build().toList())
                .addLinkstamps(linkstampBuilder.build())
                .build();
      } catch (LabelSyntaxException e) {
        throw new EvalException(e);
      }
    }
    ImmutableList.Builder<CcLinkingContext> mergedCcLinkingContexts = ImmutableList.builder();
    mergedCcLinkingContexts.add(ccLinkingContext);
    mergedCcLinkingContexts.addAll(ccLinkingContexts);
    return CcLinkingContext.merge(mergedCcLinkingContexts.build());
  }

  /**
   * Constructs the C++ linker actions. It generally generates two actions, one for a static library
   * and one for a dynamic library. If PIC is required for shared libraries, but not for binaries,
   * it additionally creates a third action to generate a PIC static library. If PIC is required for
   * shared libraries and binaries, then only PIC actions are registered.
   *
   * <p>For dynamic libraries, this method can additionally create an interface shared library that
   * can be used for linking, but doesn't contain any executable code. This increases the number of
   * cache hits for link actions. Call {@link #emitInterfaceSharedLibraries(boolean)} to enable this
   * behavior.
   */
  private CcLinkingOutputs createCcLinkActions(CcCompilationOutputs ccOutputs)
      throws RuleErrorException, EvalException, InterruptedException {
    // For now only handle static links. Note that the dynamic library link below ignores
    // staticLinkType.
    // TODO(bazel-team): Either support non-static links or move this check to setStaticLinkType().
    Preconditions.checkState(
        staticLinkType.linkerOrArchiver() == LinkerOrArchiver.ARCHIVER,
        "can only handle static links");

    LibraryToLink.Builder libraryToLinkBuilder = LibraryToLink.builder();
    boolean usePicForBinaries =
        CppHelper.usePicForBinaries(ccToolchain.getCppConfiguration(), featureConfiguration);
    boolean usePicForDynamicLibs =
        CcToolchainProvider.usePicForDynamicLibraries(
            ccToolchain.getCppConfiguration(), featureConfiguration);

    PathFragment labelName = PathFragment.create(targetName);
    String libraryIdentifier =
        linkActionConstruction
            .getContext()
            .getPackageDirectory()
            .getRelative(labelName.replaceName("lib" + labelName.getBaseName()))
            .getPathString();

    if (shouldCreateStaticLibraries) {
      createNoPicAndPicStaticLibraries(
          libraryToLinkBuilder,
          usePicForBinaries,
          usePicForDynamicLibs,
          libraryIdentifier,
          ccOutputs);
    }

    boolean hasBuiltDynamicLibrary = false;
    CcLinkingOutputs.Builder ccLinkingOutputsBuilder = CcLinkingOutputs.builder();
    if (shouldCreateDynamicLibrary) {
      boolean usePic =
          (!dynamicLinkType.isExecutable() && usePicForDynamicLibs)
              || (dynamicLinkType.isExecutable() && usePicForBinaries);
      try {
        hasBuiltDynamicLibrary =
            createDynamicLinkAction(
                ccLinkingOutputsBuilder,
                libraryToLinkBuilder,
                usePic,
                libraryIdentifier,
                ccOutputs);
      } catch (EvalException e) {
        throw new RuleErrorException(e.getMessage());
      }
    }

    if (shouldCreateStaticLibraries
        && featureConfiguration.isEnabled(CppRuleClasses.DISABLE_WHOLE_ARCHIVE_FOR_STATIC_LIB)
        && (staticLinkType == LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY)) {
      throw Starlark.errorf(
          "Attribute alwayslink: alwayslink should not be True for a target with the"
              + " disable_whole_archive_for_static_lib feature enabled.");
    }

    libraryToLinkBuilder.setDisableWholeArchive(
        featureConfiguration.isEnabled(CppRuleClasses.DISABLE_WHOLE_ARCHIVE_FOR_STATIC_LIB));

    if (hasBuiltDynamicLibrary || shouldCreateStaticLibraries) {
      ccLinkingOutputsBuilder.setLibraryToLink(libraryToLinkBuilder.build());
    }

    return ccLinkingOutputsBuilder.build();
  }

  @CanIgnoreReturnValue
  public CcLinkingHelper setWillOnlyBeLinkedIntoDynamicLibraries(
      boolean willOnlyBeLinkedIntoDynamicLibraries) {
    this.willOnlyBeLinkedIntoDynamicLibraries = willOnlyBeLinkedIntoDynamicLibraries;
    return this;
  }

  @CanIgnoreReturnValue
  public CcLinkingHelper setUseTestOnlyFlags(boolean useTestOnlyFlags) {
    this.useTestOnlyFlags = useTestOnlyFlags;
    return this;
  }

  /** Used to set the runfiles path for test rules' dynamic libraries. */
  @CanIgnoreReturnValue
  public CcLinkingHelper setTestOrTestOnlyTarget(boolean isTestOrTestOnlyTarget) {
    this.isTestOrTestOnlyTarget = isTestOrTestOnlyTarget;
    return this;
  }

  /** Whether linkstamping is enabled. */
  @CanIgnoreReturnValue
  public CcLinkingHelper setIsStampingEnabled(boolean isStampingEnabled) {
    this.isStampingEnabled = isStampingEnabled;
    return this;
  }

  @CanIgnoreReturnValue
  public CcLinkingHelper setLinkingMode(LinkingMode linkingMode) {
    this.linkingMode = linkingMode;
    return this;
  }

  @CanIgnoreReturnValue
  public CcLinkingHelper setDynamicLinkType(LinkTargetType dynamicLinkType) {
    this.dynamicLinkType = dynamicLinkType;
    return this;
  }

  private void createNoPicAndPicStaticLibraries(
      LibraryToLink.Builder libraryToLinkBuilder,
      boolean usePicForBinaries,
      boolean usePicForDynamicLibs,
      String libraryIdentifier,
      CcCompilationOutputs ccOutputs)
      throws RuleErrorException, InterruptedException {
    // Create static library (.a). The staticLinkType only reflects whether the library is
    // alwayslink or not. The PIC-ness is determined by whether we need to use PIC or not. There
    // are four cases:
    // for (usePicForDynamicLibs usePicForBinaries):
    //
    // (1) (false false) -> no pic code is when toolchain and cppOptions don't need pic code for
    //  dynamic libraries or binaries
    // (2) (true false)  -> shared libraries as pic, but not binaries
    // (3) (true true)   -> both shared libraries and binaries as pic
    // (4) (false true) -> only pic files generated when toolchain needs pic for shared libraries
    //  and {@link #willOnlyBeLinkedIntoDynamicLibraries} is set to true.

    // In case (3), we always need PIC, so only create one static library containing the PIC
    // object files. The name therefore does not match the content.
    //
    // Presumably, it is done this way because the .a file is an implicit output of every
    // cc_library rule, so we can't use ".pic.a" that in the always-PIC case.

    // If the crosstool is configured to select an output artifact, we use that selection.
    // Otherwise, we use linux defaults.
    boolean createNoPicAction;
    boolean createPicAction;
    if (willOnlyBeLinkedIntoDynamicLibraries) {
      createNoPicAction = !usePicForDynamicLibs;
      createPicAction = usePicForDynamicLibs;
    } else {
      createNoPicAction = !usePicForBinaries;
      createPicAction = usePicForBinaries || usePicForDynamicLibs;
    }

    if (createNoPicAction) {
      LibraryInput staticLibrary =
          registerActionForStaticLibrary(
              staticLinkType, ccOutputs, /* usePic= */ false, libraryIdentifier);
      libraryToLinkBuilder
          .setLibraryIdentifier(staticLibrary.getLibraryIdentifier())
          .setStaticLibrary(staticLibrary.getArtifact())
          .setObjectFiles(ImmutableList.copyOf(staticLibrary.getObjectFiles()))
          .setLtoCompilationContext(staticLibrary.getLtoCompilationContext())
          .setSharedNonLtoBackends(staticLibrary.getSharedNonLtoBackends())
          .setAlwayslink(
              staticLibrary.getArtifactCategory() == ArtifactCategory.ALWAYSLINK_STATIC_LIBRARY);
    }

    if (createPicAction) {
      LinkTargetType linkTargetTypeUsedForNaming;
      if (!createNoPicAction) {
        // Only PIC library created, name does not match content.
        linkTargetTypeUsedForNaming = staticLinkType;
      } else {
        linkTargetTypeUsedForNaming =
            (staticLinkType == LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY)
                ? LinkTargetType.ALWAYS_LINK_PIC_STATIC_LIBRARY
                : LinkTargetType.PIC_STATIC_LIBRARY;
      }
      LibraryInput picStaticLibrary =
          registerActionForStaticLibrary(
              linkTargetTypeUsedForNaming, ccOutputs, /* usePic= */ true, libraryIdentifier);
      libraryToLinkBuilder
          .setLibraryIdentifier(picStaticLibrary.getLibraryIdentifier())
          .setPicStaticLibrary(picStaticLibrary.getArtifact())
          .setPicObjectFiles(ImmutableList.copyOf(picStaticLibrary.getObjectFiles()))
          .setPicLtoCompilationContext(picStaticLibrary.getLtoCompilationContext())
          .setPicSharedNonLtoBackends(picStaticLibrary.getSharedNonLtoBackends())
          .setAlwayslink(
              picStaticLibrary.getArtifactCategory() == ArtifactCategory.ALWAYSLINK_STATIC_LIBRARY);
    }
  }

  /** Returns whether Propeller profiles should be passed to the linking step. */
  private boolean shouldPassPropellerProfiles() throws EvalException {
    return !ccToolchain.isToolConfiguration()
        && fdoContext.getPropellerOptimizeInputFile() != null
        && fdoContext.getPropellerOptimizeInputFile().getLdArtifact() != null;
  }

  private LibraryInput registerActionForStaticLibrary(
      LinkTargetType linkTargetTypeUsedForNaming,
      CcCompilationOutputs ccOutputs,
      boolean usePic,
      String libraryIdentifier)
      throws RuleErrorException, InterruptedException {
    Artifact linkedArtifact = getLinkedArtifact(linkTargetTypeUsedForNaming);
    CppLinkActionBuilder builder =
        newLinkActionBuilder(linkedArtifact, linkTargetTypeUsedForNaming)
            .setMnemonic("CppArchive")
            .addObjectFiles(ccOutputs.getObjectFiles(usePic))
            .addLtoCompilationContext(ccOutputs.getLtoCompilationContext())
            .setUsePicForLtoBackendActions(usePic)
            .setLinkingMode(LinkingMode.STATIC)
            .addActionInputs(linkActionInputs)
            .setLibraryIdentifier(libraryIdentifier)
            .addVariablesExtensions(variablesExtensions);
    try {
    if (shouldPassPropellerProfiles()) {
      builder.addNonCodeInput(fdoContext.getPropellerOptimizeInputFile().getLdArtifact());
    }
    CppLinkAction action = builder.build();
      linkActionConstruction.getContext().registerAction(action);
      return builder.getOutputLibrary();
    } catch (EvalException e) {
      throw new RuleErrorException(e.getMessage());
    }
  }

  private boolean createDynamicLinkAction(
      CcLinkingOutputs.Builder ccLinkingOutputs,
      LibraryToLink.Builder libraryToLinkBuilder,
      boolean usePic,
      String libraryIdentifier,
      CcCompilationOutputs ccOutputs)
      throws RuleErrorException, InterruptedException, EvalException {
    boolean hasBuiltDynamicLibrary = false;
    // Create dynamic library.
    Artifact linkerOutput;
    String mainLibraryIdentifier;
    if (linkerOutputArtifact == null) {
      // If the crosstool is configured to select an output artifact, we use that selection.
      // Otherwise, we use linux defaults.
      linkerOutput = getLinkedArtifact(dynamicLinkType);
      mainLibraryIdentifier = libraryIdentifier;
    } else {
      // This branch is only used for vestigial Google-internal rules where the name of the output
      // file is explicitly specified in the BUILD file and as such, is platform-dependent. Thus,
      // we just hardcode some reasonable logic to compute the library identifier and hope that this
      // will eventually go away.
      linkerOutput = linkerOutputArtifact;
      mainLibraryIdentifier =
          FileSystemUtils.removeExtension(linkerOutput.getRootRelativePath().getPathString());
    }

    List<String> sonameLinkopts = ImmutableList.of();
    Artifact soInterface = null;
    if (CppHelper.useInterfaceSharedLibraries(
            ccToolchain.getCppConfiguration(), featureConfiguration)
        && emitInterfaceSharedLibraries) {
      soInterface = getLinkedArtifact(LinkTargetType.INTERFACE_DYNAMIC_LIBRARY);
      // TODO(b/28946988): Remove this hard-coded flag.
      if (!featureConfiguration.isEnabled(CppRuleClasses.TARGETS_WINDOWS)) {
        sonameLinkopts =
            ImmutableList.of(
                "-Wl,-soname="
                    + SolibSymlinkAction.getDynamicLibrarySoname(
                        linkerOutput.getRootRelativePath(),
                        /* preserveName= */ dynamicLinkType
                            != LinkTargetType.NODEPS_DYNAMIC_LIBRARY,
                        linkActionConstruction.getContext().getConfiguration().getMnemonic()));
      }
    }

    CppLinkActionBuilder dynamicLinkActionBuilder =
        newLinkActionBuilder(linkerOutput, dynamicLinkType)
            .setWholeArchive(wholeArchive)
            .setNativeDeps(nativeDeps)
            .setAdditionalLinkstampDefines(additionalLinkstampDefines.build())
            .setInterfaceOutput(soInterface)
            .addNonCodeInputs(ccOutputs.getHeaderTokenFiles())
            .addLtoCompilationContext(ccOutputs.getLtoCompilationContext())
            .setLinkingMode(linkingMode)
            .addActionInputs(linkActionInputs)
            .addLinkopts(linkopts)
            .addLinkopts(sonameLinkopts)
            .addVariablesExtensions(variablesExtensions);

    dynamicLinkActionBuilder.addObjectFiles(ccOutputs.getObjectFiles(usePic));

    if (!dynamicLinkType.isExecutable()) {
      dynamicLinkActionBuilder.setLibraryIdentifier(mainLibraryIdentifier);
    }

    if (linkingMode == LinkingMode.DYNAMIC) {
      try {
        dynamicLinkActionBuilder.setRuntimeInputs(
            ArtifactCategory.DYNAMIC_LIBRARY,
            CcToolchainProvider.getDynamicRuntimeLinkInputsOrThrowError(
                ccToolchain.getDynamicRuntimeLinkInputs(), featureConfiguration));
      } catch (TypeException e) {
        throw new RuleErrorException(e.getMessage());
      }
    } else {
      try {
        dynamicLinkActionBuilder.setRuntimeInputs(
            ArtifactCategory.STATIC_LIBRARY,
            CcToolchainProvider.getStaticRuntimeLinkInputsOrThrowError(
                ccToolchain.getStaticRuntimeLinkInputs(), featureConfiguration));
      } catch (TypeException e) {
        throw new RuleErrorException(e.getMessage());
      }
    }

    // On Windows, we cannot build a shared library with symbols unresolved, so here we
    // dynamically link to all its dependencies, even for LinkTargetType.NODEPS_DYNAMIC_LIBRARY.
    boolean shouldLinkTransitively =
        featureConfiguration.isEnabled(CppRuleClasses.TARGETS_WINDOWS)
            || dynamicLinkType != LinkTargetType.NODEPS_DYNAMIC_LIBRARY;

    if (shouldLinkTransitively) {
      // LINT.IfChange
      CcLinkingContext ccLinkingContext = CcLinkingContext.merge(ccLinkingContexts);
      ImmutableList<LibraryInput> libraries =
          convertLibraryToLinkListToLinkerInputList(
              ccLinkingContext.getLibraries(),
              linkingMode != LinkingMode.DYNAMIC,
              dynamicLinkType.isDynamicLibrary(),
              featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
      // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/link/convert_linker_inputs.bzl)
      ImmutableList<CcLinkingContext.Linkstamp> linkstamps =
          ccLinkingContext.getLinkstamps().toList();
      if (dynamicLinkType == LinkTargetType.NODEPS_DYNAMIC_LIBRARY) {
        linkstamps = ImmutableList.of();
      }
      dynamicLinkActionBuilder.addLinkParams(
          libraries,
          ccLinkingContext.getFlattenedUserLinkFlags(),
          linkstamps,
          ccLinkingContext.getNonCodeInputs().toList());
    }

    if (dynamicLinkActionBuilder.hasLtoBitcodeInputs()
        && featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO)) {
      if (featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_START_END_LIB)) {
        dynamicLinkActionBuilder.setUsePicForLtoBackendActions(usePic);
        dynamicLinkActionBuilder.buildAllLtoArtifacts();
        CppLinkAction indexAction = dynamicLinkActionBuilder.buildLtoIndexingAction();
        if (indexAction != null) {
          linkActionConstruction.getContext().registerAction(indexAction);
        }
      } else {
        throw Starlark.errorf(
            "When using LTO. The feature "
                + CppRuleClasses.SUPPORTS_START_END_LIB
                + " must be enabled.");
      }
    }

    if (shouldPassPropellerProfiles()) {
      dynamicLinkActionBuilder.addNonCodeInput(
          fdoContext.getPropellerOptimizeInputFile().getLdArtifact());
    }

    if (dynamicLinkActionBuilder.getAllLtoBackendArtifacts() != null) {
      ccLinkingOutputs.addAllLtoArtifacts(dynamicLinkActionBuilder.getAllLtoBackendArtifacts());
    }
    Artifact implLibraryLinkArtifact = getDynamicLibrarySolibSymlinkOutput(linkerOutput);
    if (implLibraryLinkArtifact != null) {
      dynamicLinkActionBuilder.setDynamicLibrarySolibSymlinkOutput(implLibraryLinkArtifact);
    }
    CppLinkAction dynamicLinkAction = dynamicLinkActionBuilder.build();
    if (dynamicLinkType.isExecutable()) {
      ccLinkingOutputs.setExecutable(linkerOutput);
    }
    linkActionConstruction.getContext().registerAction(dynamicLinkAction);

    LibraryInput dynamicLibrary = dynamicLinkActionBuilder.getOutputLibrary();
    LibraryInput interfaceLibrary = dynamicLinkActionBuilder.getInterfaceOutputLibrary();

    // If shared library has neverlink=1, then leave it untouched. Otherwise,
    // create a mangled symlink for it and from now on reference it through
    // mangled name only.
    //
    // When COPY_DYNAMIC_LIBRARIES_TO_BINARY is enabled, we don't need to create the special
    // solibDir, instead we use the original interface library and dynamic library.
    if (dynamicLibrary != null) {
      hasBuiltDynamicLibrary = true;
      libraryToLinkBuilder.setLibraryIdentifier(dynamicLibrary.getLibraryIdentifier());
      if (neverlink
          || featureConfiguration.isEnabled(CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY)) {
        if (interfaceLibrary != null) {
          libraryToLinkBuilder.setInterfaceLibrary(interfaceLibrary.getArtifact());
        }
        libraryToLinkBuilder.setDynamicLibrary(dynamicLibrary.getArtifact());
      } else {
        if (dynamicLinkType == LinkTargetType.NODEPS_DYNAMIC_LIBRARY) {
          implLibraryLinkArtifact =
              SolibSymlinkAction.getDynamicLibrarySymlink(
                  /* actionConstructionContext= */ linkActionConstruction.getContext(),
                  ccToolchain.getSolibDirectory(),
                  dynamicLibrary.getArtifact(),
                  /* preserveName= */ false,
                  /* prefixConsumer= */ false);
        }
        libraryToLinkBuilder.setDynamicLibrary(implLibraryLinkArtifact);
        libraryToLinkBuilder.setResolvedSymlinkDynamicLibrary(dynamicLibrary.getArtifact());

        if (interfaceLibrary != null) {
          Artifact libraryLinkArtifact =
              SolibSymlinkAction.getDynamicLibrarySymlink(
                  /* actionConstructionContext= */ linkActionConstruction.getContext(),
                  ccToolchain.getSolibDirectory(),
                  interfaceLibrary.getArtifact(),
                  // Need to preserve name for transitive shared libraries that may be distributed.
                  /* preserveName= */ dynamicLinkType != LinkTargetType.NODEPS_DYNAMIC_LIBRARY,
                  /* prefixConsumer= */ false);
          libraryToLinkBuilder.setInterfaceLibrary(libraryLinkArtifact);
          libraryToLinkBuilder.setResolvedSymlinkInterfaceLibrary(interfaceLibrary.getArtifact());
        }
      }
    }
    return hasBuiltDynamicLibrary;
  }

  private CppLinkActionBuilder newLinkActionBuilder(
      Artifact outputArtifact, LinkTargetType linkType) throws RuleErrorException {
    String mnemonic = linkType.equals(LinkTargetType.OBJC_EXECUTABLE) ? "ObjcLink" : null;
    try {
      CppLinkActionBuilder builder =
          new CppLinkActionBuilder(
                  linkActionConstruction,
                  outputArtifact,
                  ccToolchain,
                  fdoContext,
                  featureConfiguration,
                  semantics)
              .setMnemonic(mnemonic)
              .setIsStampingEnabled(isStampingEnabled)
              .setTestOrTestOnlyTarget(isTestOrTestOnlyTarget)
              .setLinkType(linkType)
              .setLinkerFiles(
                  (ccToolchain.getCppConfiguration().useSpecificToolFiles()
                          && linkType.linkerOrArchiver() == LinkerOrArchiver.ARCHIVER)
                      ? ccToolchain.getArFiles()
                      : ccToolchain.getLinkerFiles())
              .setUseTestOnlyFlags(useTestOnlyFlags)
              .addTransitiveActionInputs(additionalLinkerInputs)
              .addExecutionInfo(executionInfo);
      for (Artifact output : linkerOutputs) {
        builder.addActionOutput(output);
    }
      return builder;
    } catch (TypeException | EvalException e) {
      throw new RuleErrorException(e.getMessage());
    }
  }

  /**
   * Returns the linked artifact resulting from a linking of the given type. Consults the feature
   * configuration to obtain an action_config that provides the artifact. If the feature
   * configuration provides no artifact, uses a default.
   *
   * <p>We cannot assume that the feature configuration contains an action_config for the link
   * action, because the linux link action depends on hardcoded values in
   * LinkCommandLine.getRawLinkArgv(), which are applied on the condition that an action_config is
   * not present. TODO(b/30393154): Assert that the given link action has an action_config.
   */
  private Artifact getLinkedArtifact(LinkTargetType linkTargetType) throws RuleErrorException {
    String maybePicName = targetName + linkedArtifactNameSuffix;
    if (linkTargetType.picness() == Picness.PIC) {
      maybePicName =
          CppHelper.getArtifactNameForCategory(
              ccToolchain, ArtifactCategory.PIC_FILE, maybePicName);
    }

    String linkedName = maybePicName;
    if (linkTargetType.equals(LinkTargetType.NODEPS_DYNAMIC_LIBRARY)) {
      linkedName += linkedDLLNameSuffix;
    }
    linkedName =
        CppHelper.getArtifactNameForCategory(
            ccToolchain, linkTargetType.getLinkerOutput(), linkedName);

    PathFragment artifactFragment = PathFragment.create(linkedName);
    if (linkTargetType.equals(LinkTargetType.OBJC_FULLY_LINKED_ARCHIVE)) {
      // TODO(blaze-team): This unfortunate editing of the name is here bedcause Objective-C rules
      // were creating this type of archive without the lib prefix, unlike what the objective-c
      // toolchain says with getArtifactNameForCategory.
      // This can be fixed either when implicit outputs are removed from objc_library by keeping the
      // lib prefix, or by editing the toolchain not to add it.
      Preconditions.checkState(artifactFragment.getBaseName().startsWith("lib"));
      artifactFragment = artifactFragment.replaceName(artifactFragment.getBaseName().substring(3));
    }

    return CppHelper.getLinkedArtifact(
        targetName,
        linkActionConstruction,
        linkTargetType,
        linkedArtifactNameSuffix,
        artifactFragment);
  }

  // LINT.IfChange
  public static ImmutableList<LibraryInput> convertLibraryToLinkListToLinkerInputList(
      NestedSet<LibraryToLink> librariesToLink,
      boolean staticMode,
      boolean forDynamicLibrary,
      boolean supportsDynamicLinker) {
    ImmutableList.Builder<LibraryInput> libraryInputsBuilder = ImmutableList.builder();
    for (LibraryToLink libraryToLink : librariesToLink.toList()) {
      LibraryInput staticLibraryInput =
          libraryToLink.getStaticLibrary() == null ? null : libraryToLink.getStaticLibraryInput();
      LibraryInput picStaticLibraryInput =
          libraryToLink.getPicStaticLibrary() == null
              ? null
              : libraryToLink.getPicStaticLibraryInput();
      LibraryInput libraryInputToUse = null;
      if (staticMode) {
        if (forDynamicLibrary) {
          if (picStaticLibraryInput != null) {
            libraryInputToUse = picStaticLibraryInput;
          } else if (staticLibraryInput != null) {
            libraryInputToUse = staticLibraryInput;
          }
        } else {
          if (staticLibraryInput != null) {
            libraryInputToUse = staticLibraryInput;
          } else if (picStaticLibraryInput != null) {
            libraryInputToUse = picStaticLibraryInput;
          }
        }
        if (libraryInputToUse == null) {
          if (libraryToLink.getInterfaceLibrary() != null) {
            libraryInputToUse = libraryToLink.getInterfaceLibraryInput();
          } else if (libraryToLink.getDynamicLibrary() != null) {
            libraryInputToUse = libraryToLink.getDynamicLibraryInput();
          }
        }
      } else {
        if (libraryToLink.getInterfaceLibrary() != null) {
          libraryInputToUse = libraryToLink.getInterfaceLibraryInput();
        } else if (libraryToLink.getDynamicLibrary() != null) {
          libraryInputToUse = libraryToLink.getDynamicLibraryInput();
        }
        if (libraryInputToUse == null || !supportsDynamicLinker) {
          if (forDynamicLibrary) {
            if (picStaticLibraryInput != null) {
              libraryInputToUse = picStaticLibraryInput;
            } else if (staticLibraryInput != null) {
              libraryInputToUse = staticLibraryInput;
            }
          } else {
            if (staticLibraryInput != null) {
              libraryInputToUse = staticLibraryInput;
            } else if (picStaticLibraryInput != null) {
              libraryInputToUse = picStaticLibraryInput;
            }
          }
        }
      }
      Preconditions.checkNotNull(libraryInputToUse);
      libraryInputsBuilder.add(libraryInputToUse);
    }
    return libraryInputsBuilder.build();
  }

  // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/link/convert_linker_inputs.bzl)

  @Nullable
  private Artifact getDynamicLibrarySolibSymlinkOutput(Artifact linkerOutputArtifact)
      throws EvalException {
    if (dynamicLinkType != LinkTargetType.DYNAMIC_LIBRARY
        || neverlink
        || featureConfiguration.isEnabled(CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY)) {
      return null;
    }
    return SolibSymlinkAction.getDynamicLibrarySymlink(
        /* actionConstructionContext= */ linkActionConstruction.getContext(),
        ccToolchain.getSolibDirectory(),
        linkerOutputArtifact,
        // For transitive shared libraries we want to preserve the name of the original library so
        // that distribution artifacts can be linked against it and not against the mangled name.
        // This makes it possible to find the library on the system if the RPATH has been set
        // properly.
        /* preserveName= */ true,
        /* prefixConsumer= */ false);
  }
}
// LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/link/cc_linking_helper.bzl)
