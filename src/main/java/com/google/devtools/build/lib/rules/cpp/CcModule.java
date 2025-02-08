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

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.base.StandardSystemProperty.LINE_SEPARATOR;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.starlark.StarlarkActionFactory;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TriState;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CoptsFilter;
import com.google.devtools.build.lib.rules.cpp.CcCommon.Language;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper.CompilationInfo;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper.SourceCategory;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.LinkOptions;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.Linkstamp;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ActionConfig;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.EnvEntry;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.EnvSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Flag;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FlagGroup;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FlagSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.VariableWithValue;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.WithFeatureSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Expandable;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.StringValueParser;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.VariablesExtension;
import com.google.devtools.build.lib.rules.cpp.CppActionConfigs.CppPlatform;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.cpp.Link.LinkingMode;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcModuleApi;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.ExtraLinkTimeLibraryApi;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CToolchain;
import com.google.errorprone.annotations.FormatMethod;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Structure;
import net.starlark.java.eval.Tuple;

/**
 * A module that contains Starlark utilities for C++ support.
 *
 * <p>The Bazel team is planning to rewrite all native rules in Starlark. Many of these rules use
 * C++ functionality that is not presently exposed to the public Starlark C++ API. To speed up the
 * transition to Starlark, we are exposing functionality "as is" but preventing its use externally
 * until we are comfortable with the API which would need to be supported long term.
 *
 * <p>We are not opposed to gradually adding to and improving the public C++ API but nothing should
 * merged without following proper design processes and discussions.
 */
public abstract class CcModule
    implements CcModuleApi<
        StarlarkActionFactory,
        Artifact,
        FeatureConfigurationForStarlark,
        CcCompilationContext,
        LtoBackendArtifacts,
        CcLinkingContext.LinkerInput,
        CcLinkingContext,
        LibraryToLink,
        CcToolchainVariables,
        ConstraintValueInfo,
        StarlarkRuleContext,
        CcToolchainConfigInfo,
        CcCompilationOutputs,
        CcDebugInfoContext,
        CppModuleMap,
        CcLinkingOutputs> {

  private static final ImmutableList<String> SUPPORTED_OUTPUT_TYPES =
      ImmutableList.of("executable", "dynamic_library", "archive");

  // TODO(bazel-team): This only makes sense for the parameter in cc_common.compile()
  //  additional_include_scanning_roots which is technical debt and should go away.
  private static final BuiltinRestriction.AllowlistEntry MATCH_CLIF_ALLOWLISTED_LOCATION =
      BuiltinRestriction.allowlistEntry("", "tools/build_defs/clif");

  public abstract CppSemantics getSemantics();

  public abstract CppSemantics getSemantics(Language language);

  @Override
  public Provider getCcToolchainProvider() {
    return CcToolchainProvider.PROVIDER;
  }

  @Override
  public FeatureConfigurationForStarlark configureFeatures(
      Object ruleContextOrNone,
      Info toolchainInfo,
      Object languageObject,
      Sequence<?> requestedFeatures, // <String> expected
      Sequence<?> unsupportedFeatures, // <String> expected
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    StarlarkRuleContext ruleContext = nullIfNone(ruleContextOrNone, StarlarkRuleContext.class);

    String languageString = convertFromNoneable(languageObject, Language.CPP.getRepresentation());
    Language language = parseLanguage(languageString);
    // TODO(236152224): Remove the following when all Starlark objc configure_features have the
    // chance to migrate to using the language parameter.
    if (requestedFeatures.contains(CppRuleClasses.LANG_OBJC)) {
      language = Language.OBJC;
    }

    ImmutableSet<String> requestedFeaturesSet =
        ImmutableSet.copyOf(Sequence.cast(requestedFeatures, String.class, "requested_features"));
    ImmutableSet<String> unsupportedFeaturesSet =
        ImmutableSet.copyOf(
            Sequence.cast(unsupportedFeatures, String.class, "unsupported_features"));
    final CppConfiguration cppConfiguration;
    CcToolchainProvider toolchain =
        CcToolchainProvider.PROVIDER.wrapOrThrowEvalException(toolchainInfo);
    if (ruleContext == null) {
      throw Starlark.errorf(
          "Mandatory parameter 'ctx' of cc_common.configure_features is missing. "
              + "Please add 'ctx' as a named parameter. See "
              + "https://github.com/bazelbuild/bazel/issues/7793 for details.");
    } else {
      if (!ruleContext.getRuleContext().isLegalFragment(CppConfiguration.class)) {
        throw Starlark.errorf(
            "%s must declare '%s' as a required configuration fragment to access it.",
            ruleContext.getRuleContext().getRuleClassNameForLogging(),
            CppConfiguration.class.getSimpleName());
      }
      cppConfiguration = toolchain.getCppConfiguration();
      // buildOptions are only used when --incompatible_enable_cc_toolchain_resolution is flipped,
      // and that will only be flipped when --incompatible_require_ctx_in_configure_features is
      // flipped.
      getSemantics(language)
          .validateLayeringCheckFeatures(
              ruleContext.getRuleContext(),
              ruleContext.getAspectDescriptor(),
              toolchain,
              unsupportedFeaturesSet);
    }
    return FeatureConfigurationForStarlark.from(
        CcCommon.configureFeaturesOrThrowEvalException(
            requestedFeaturesSet, unsupportedFeaturesSet, language, toolchain, cppConfiguration));
  }

  @Override
  public String getToolForAction(
      FeatureConfigurationForStarlark featureConfiguration,
      String actionName,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    try {
      return featureConfiguration.getFeatureConfiguration().getToolPathForAction(actionName);
    } catch (IllegalArgumentException illegalArgumentException) {
      throw new EvalException(illegalArgumentException);
    }
  }

  // TODO(blaze-team): duplicate with the getExecutionRequirements below
  @Override
  public Sequence<String> getToolRequirementForAction(
      FeatureConfigurationForStarlark featureConfiguration,
      String actionName,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return StarlarkList.immutableCopyOf(
        featureConfiguration.getFeatureConfiguration().getToolRequirementsForAction(actionName));
  }

  @Override
  public Sequence<String> getExecutionRequirements(
      FeatureConfigurationForStarlark featureConfiguration,
      String actionName,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return StarlarkList.immutableCopyOf(
        featureConfiguration.getFeatureConfiguration().getToolRequirementsForAction(actionName));
  }

  @Override
  public boolean actionIsEnabled(
      FeatureConfigurationForStarlark featureConfiguration,
      String actionName,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return featureConfiguration.getFeatureConfiguration().actionIsConfigured(actionName);
  }

  @Override
  public Sequence<String> getCommandLine(
      FeatureConfigurationForStarlark featureConfiguration,
      String actionName,
      CcToolchainVariables variables,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return StarlarkList.immutableCopyOf(
        featureConfiguration.getFeatureConfiguration().getCommandLine(actionName, variables));
  }

  @Override
  public Dict<String, String> getEnvironmentVariable(
      FeatureConfigurationForStarlark featureConfiguration,
      String actionName,
      CcToolchainVariables variables,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return Dict.immutableCopyOf(
        featureConfiguration
            .getFeatureConfiguration()
            .getEnvironmentVariables(actionName, variables, PathMapper.NOOP));
  }

  @Override
  public CcToolchainVariables getCompileBuildVariables(
      Info ccToolchainInfo,
      FeatureConfigurationForStarlark featureConfiguration,
      Object sourceFile,
      Object outputFile,
      Object userCompileFlags,
      Object includeDirs,
      Object quoteIncludeDirs,
      Object systemIncludeDirs,
      Object frameworkIncludeDirs,
      Object defines,
      Object thinLtoIndex,
      Object thinLtoInputBitcodeFile,
      Object thinLtoOutputObjectFile,
      boolean usePic,
      boolean addLegacyCxxOptions,
      Object variablesExtension,
      Object stripOpts,
      Object inputFile,
      StarlarkThread thread)
      throws EvalException, InterruptedException {
    isCalledFromStarlarkCcCommon(thread);
    ImmutableList<VariablesExtension> variablesExtensions =
        asDict(variablesExtension).isEmpty()
            ? ImmutableList.of()
            : ImmutableList.of(new UserVariablesExtension(asDict(variablesExtension)));
    CcToolchainProvider ccToolchainProvider =
        CcToolchainProvider.PROVIDER.wrapOrThrowEvalException(ccToolchainInfo);
    CcToolchainVariables.Builder variables =
        CcToolchainVariables.builder(
                CompileBuildVariables.setupVariablesOrThrowEvalException(
                    featureConfiguration.getFeatureConfiguration(),
                    ccToolchainProvider,
                    convertFromNoneable(sourceFile, /* defaultValue= */ null),
                    convertFromNoneable(outputFile, /* defaultValue= */ null),
                    /* isCodeCoverageEnabled= */ false,
                    /* gcnoFile= */ null,
                    /* isUsingFission= */ false,
                    /* dwoFile= */ null,
                    /* ltoIndexingFile= */ null,
                    convertFromNoneable(thinLtoIndex, /* defaultValue= */ null),
                    convertFromNoneable(thinLtoInputBitcodeFile, /* defaultValue= */ null),
                    convertFromNoneable(thinLtoOutputObjectFile, /* defaultValue= */ null),
                    /* includes= */ ImmutableList.of(),
                    userFlagsToIterable(userCompileFlags),
                    /* cppModuleMap= */ null,
                    usePic,
                    /* fdoStamp= */ null,
                    /* dotdFile= */ null,
                    /* diagnosticsFile= */ null,
                    variablesExtensions,
                    /* additionalBuildVariables= */ ImmutableMap.of(),
                    /* directModuleMaps= */ ImmutableList.of(),
                    Depset.noneableCast(includeDirs, String.class, "framework_include_directories"),
                    Depset.noneableCast(
                        quoteIncludeDirs, String.class, "quote_include_directories"),
                    Depset.noneableCast(
                        systemIncludeDirs, String.class, "system_include_directories"),
                    Depset.noneableCast(
                        frameworkIncludeDirs, String.class, "framework_include_directories"),
                    Depset.noneableCast(defines, String.class, "preprocessor_defines").toList(),
                    ImmutableList.of()))
            .addStringSequenceVariable("stripopts", asClassImmutableList(stripOpts));
    String inputFileString = convertFromNoneable(inputFile, null);
    if (inputFileString != null) {
      variables.addStringVariable("input_file", inputFileString);
    }
    return variables.build();
  }

  @Override
  public CcToolchainVariables getVariables(StarlarkThread thread) throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return CcToolchainVariables.EMPTY;
  }

  /**
   * Converts an object that can be the NoneType to the actual object if it is not or returns the
   * default value if none.
   *
   * <p>This operation is wildly unsound. It performs no dymamic checks (casts), it simply lies
   * about the type.
   */
  @SuppressWarnings("unchecked")
  protected static <T> T convertFromNoneable(Object obj, @Nullable T defaultValue) {
    if (Starlark.UNBOUND == obj || Starlark.isNullOrNone(obj)) {
      return defaultValue;
    }
    return (T) obj; // totally unsafe
  }

  /** Converts an object that can be ether Depset or None into NestedSet. */
  protected NestedSet<String> asStringNestedSet(Object o) throws Depset.TypeException {
    Depset starlarkNestedSet = convertFromNoneable(o, /* defaultValue= */ (Depset) null);
    if (starlarkNestedSet != null) {
      return starlarkNestedSet.getSet(String.class);
    } else {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
  }

  /** Converts an object that can be either Sequence, or None into ImmutableList. */
  protected ImmutableList<String> asStringImmutableList(Object o) {
    Sequence<String> starlarkList =
        convertFromNoneable(o, /* defaultValue= */ (Sequence<String>) null);
    if (starlarkList != null) {
      return starlarkList.getImmutableList();
    } else {
      return ImmutableList.of();
    }
  }

  /** Converts an object that represents user flags as either Sequence or None into Iterable. */
  protected Iterable<String> userFlagsToIterable(Object o) throws EvalException {
    if (o instanceof Sequence) {
      return asStringImmutableList(o);
    } else if (o instanceof NoneType) {
      return ImmutableList.of();
    } else {
      throw Starlark.errorf("Only list is allowed.");
    }
  }

  @SuppressWarnings("unchecked")
  @Nullable
  protected ImmutableList<Artifact> asArtifactImmutableList(Object o) {
    if (o == Starlark.UNBOUND) {
      return null;
    } else {
      ImmutableList<Artifact> list = ((Sequence<Artifact>) o).getImmutableList();
      if (list.isEmpty()) {
        return null;
      }
      return list;
    }
  }

  @SuppressWarnings("unchecked")
  @Nullable
  protected <T> ImmutableList<T> asClassImmutableList(Object o) {
    if (o == Starlark.UNBOUND) {
      return ImmutableList.of();
    } else {
      ImmutableList<T> list = ((Sequence<T>) o).getImmutableList();
      if (list.isEmpty()) {
        return ImmutableList.of();
      }
      return list;
    }
  }

  protected Dict<?, ?> asDict(Object o) {
    return o == Starlark.UNBOUND ? Dict.empty() : (Dict<?, ?>) o;
  }

  @Nullable
  protected <T> Object asClassImmutableListOrNestedSet(
      Object o, Class<T> tClass, String description) throws EvalException {
    if (o == Starlark.UNBOUND) {
      return ImmutableList.of();
    } else {
      return o instanceof Depset
          ? Depset.cast(o, tClass, description)
          : Sequence.cast(o, tClass, description).getImmutableList();
    }
  }

  /**
   * This method returns a {@link LibraryToLink} object that will be used to contain linking
   * artifacts and information for a single library that will later be used by a linking action.
   *
   * @param actionsObject StarlarkActionFactory
   * @param featureConfigurationObject FeatureConfiguration
   * @param staticLibraryObject Artifact
   * @param picStaticLibraryObject Artifact
   * @param dynamicLibraryObject Artifact
   * @param interfaceLibraryObject Artifact
   * @param alwayslink boolean
   * @param dynamicLibraryPath String
   * @param interfaceLibraryPath String
   * @param picObjectFiles {@code Sequence<Artifact>}
   * @param objectFiles {@code Sequence<Artifact>}
   * @return
   * @throws EvalException
   */
  @Override
  public LibraryToLink createLibraryLinkerInput(
      Object actionsObject,
      Object featureConfigurationObject,
      Object ccToolchainProviderObject,
      Object staticLibraryObject,
      Object picStaticLibraryObject,
      Object dynamicLibraryObject,
      Object interfaceLibraryObject,
      Object picObjectFiles, // Sequence<Artifact> expected
      Object objectFiles, // Sequence<Artifact> expected
      Object ltoCopmilationContextObject,
      boolean alwayslink,
      String dynamicLibraryPath,
      String interfaceLibraryPath,
      Object mustKeepDebugForStarlark,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    StarlarkActionFactory starlarkActionFactory =
        nullIfNone(actionsObject, StarlarkActionFactory.class);
    FeatureConfigurationForStarlark featureConfiguration =
        nullIfNone(featureConfigurationObject, FeatureConfigurationForStarlark.class);
    Info ccToolchainProviderInfo = nullIfNone(ccToolchainProviderObject, Info.class);
    CcToolchainProvider ccToolchainProvider = null;
    if (ccToolchainProviderInfo != null) {
      ccToolchainProvider =
          CcToolchainProvider.PROVIDER.wrapOrThrowEvalException(ccToolchainProviderInfo);
    }
    Artifact staticLibrary = nullIfNone(staticLibraryObject, Artifact.class);
    Artifact picStaticLibrary = nullIfNone(picStaticLibraryObject, Artifact.class);
    Artifact dynamicLibrary = nullIfNone(dynamicLibraryObject, Artifact.class);
    Artifact interfaceLibrary = nullIfNone(interfaceLibraryObject, Artifact.class);
    boolean mustKeepDebug =
        convertFromNoneable(mustKeepDebugForStarlark, /* defaultValue= */ false);

    ImmutableList<Artifact> picObjects = asArtifactImmutableList(picObjectFiles);
    ImmutableList<Artifact> nopicObjects = asArtifactImmutableList(objectFiles);

    LtoCompilationContext ltoCompilationContext =
        convertFromNoneable(ltoCopmilationContextObject, LtoCompilationContext.EMPTY);
    if (!ltoCompilationContext.equals(LtoCompilationContext.EMPTY)) {
      checkPrivateStarlarkificationAllowlist(thread);
    }

    StringBuilder extensionErrorsBuilder = new StringBuilder();
    String extensionErrorMessage = "does not have any of the allowed extensions";

    PathFragment dynamicLibraryPathFragment = null;
    if (!Strings.isNullOrEmpty(dynamicLibraryPath)) {
      dynamicLibraryPathFragment = PathFragment.create(dynamicLibraryPath);
      validateSymlinkPath(
          "dynamic_library_symlink_path",
          dynamicLibraryPathFragment,
          Link.ONLY_SHARED_LIBRARY_FILETYPES,
          extensionErrorsBuilder);
    }

    PathFragment interfaceLibraryPathFragment = null;
    if (!Strings.isNullOrEmpty(interfaceLibraryPath)) {
      interfaceLibraryPathFragment = PathFragment.create(interfaceLibraryPath);
      validateSymlinkPath(
          "interface_library_symlink_path",
          interfaceLibraryPathFragment,
          Link.ONLY_INTERFACE_LIBRARY_FILETYPES,
          extensionErrorsBuilder);
    }

    Artifact notNullArtifactForIdentifier = null;
    if (staticLibrary != null) {
      String filename = staticLibrary.getFilename();
      if (!Link.ARCHIVE_FILETYPES.matches(filename)
          && (!alwayslink || !Link.LINK_LIBRARY_FILETYPES.matches(filename))) {
        String extensions = Link.ARCHIVE_FILETYPES.toString();
        if (alwayslink) {
          extensions += ", " + Link.LINK_LIBRARY_FILETYPES;
        }
        extensionErrorsBuilder.append(
            String.format("'%s' %s %s", filename, extensionErrorMessage, extensions));
        extensionErrorsBuilder.append(LINE_SEPARATOR.value());
      }
      notNullArtifactForIdentifier = staticLibrary;
    }
    if (picStaticLibrary != null) {
      String filename = picStaticLibrary.getFilename();
      if (!Link.ARCHIVE_FILETYPES.matches(filename)
          && (!alwayslink || !Link.LINK_LIBRARY_FILETYPES.matches(filename))) {
        String extensions = Link.ARCHIVE_FILETYPES.toString();
        if (alwayslink) {
          extensions += ", " + Link.LINK_LIBRARY_FILETYPES;
        }
        extensionErrorsBuilder.append(
            String.format("'%s' %s %s", filename, extensionErrorMessage, extensions));
        extensionErrorsBuilder.append(LINE_SEPARATOR.value());
      }
      notNullArtifactForIdentifier = picStaticLibrary;
    }
    if (dynamicLibrary != null) {
      String filename = dynamicLibrary.getFilename();
      if (!Link.ONLY_SHARED_LIBRARY_FILETYPES.matches(filename)) {
        extensionErrorsBuilder.append(
            String.format(
                "'%s' %s %s", filename, extensionErrorMessage, Link.ONLY_SHARED_LIBRARY_FILETYPES));
        extensionErrorsBuilder.append(LINE_SEPARATOR.value());
      }
      notNullArtifactForIdentifier = dynamicLibrary;
    }
    if (interfaceLibrary != null) {
      String filename = interfaceLibrary.getFilename();
      if (!FileTypeSet.of(CppFileTypes.INTERFACE_SHARED_LIBRARY, CppFileTypes.UNIX_SHARED_LIBRARY)
          .matches(filename)) {
        extensionErrorsBuilder.append(
            String.format(
                "'%s' %s %s",
                filename, extensionErrorMessage, Link.ONLY_INTERFACE_LIBRARY_FILETYPES));
        extensionErrorsBuilder.append(LINE_SEPARATOR.value());
      }
      notNullArtifactForIdentifier = interfaceLibrary;
    }
    if (dynamicLibrary != null || interfaceLibrary != null) {
      String library = (dynamicLibrary != null) ? "dynamic" : "interface";
      if (ccToolchainProvider == null) {
        throw Starlark.errorf(
            "If you pass '%s_library', you must also pass a 'cc_toolchain'", library);
      }
      if (featureConfiguration == null) {
        throw Starlark.errorf(
            "If you pass '%s_library', you must also pass a 'feature_configuration'", library);
      }
    }
    if (notNullArtifactForIdentifier == null) {
      throw Starlark.errorf("Must pass at least one artifact");
    }
    String extensionErrors = extensionErrorsBuilder.toString();
    if (!extensionErrors.isEmpty()) {
      throw Starlark.errorf("%s", extensionErrors);
    }

    Artifact resolvedSymlinkDynamicLibrary = null;
    Artifact resolvedSymlinkInterfaceLibrary = null;
    if (dynamicLibrary != null
        && !featureConfiguration
            .getFeatureConfiguration()
            .isEnabled(CppRuleClasses.TARGETS_WINDOWS)) {
      resolvedSymlinkDynamicLibrary = dynamicLibrary;
      if (dynamicLibraryPathFragment != null) {
        if (dynamicLibrary.getRootRelativePath().getPathString().startsWith("_solib_")) {
          throw Starlark.errorf(
              "dynamic_library must not be a symbolic link in the solib directory. Got '%s'",
              dynamicLibrary.getRootRelativePath());
        }
        dynamicLibrary =
            SolibSymlinkAction.getDynamicLibrarySymlink(
                starlarkActionFactory.getRuleContext(),
                ccToolchainProvider.getSolibDirectory(),
                dynamicLibrary,
                dynamicLibraryPathFragment);
      } else {
        dynamicLibrary =
            SolibSymlinkAction.getDynamicLibrarySymlink(
                starlarkActionFactory.getRuleContext(),
                ccToolchainProvider.getSolibDirectory(),
                dynamicLibrary,
                /* preserveName= */ true,
                /* prefixConsumer= */ true);
      }
    }
    if (interfaceLibrary != null
        && !featureConfiguration
            .getFeatureConfiguration()
            .isEnabled(CppRuleClasses.TARGETS_WINDOWS)) {
      resolvedSymlinkInterfaceLibrary = interfaceLibrary;
      if (interfaceLibraryPathFragment != null) {
        if (interfaceLibrary.getRootRelativePath().getPathString().startsWith("_solib_")) {
          throw Starlark.errorf(
              "interface_library must not be a symbolic link in the solib directory. Got '%s'",
              interfaceLibrary.getRootRelativePath());
        }
        interfaceLibrary =
            SolibSymlinkAction.getDynamicLibrarySymlink(
                /* actionConstructionContext= */ starlarkActionFactory.getRuleContext(),
                ccToolchainProvider.getSolibDirectory(),
                interfaceLibrary,
                interfaceLibraryPathFragment);
      } else {
        interfaceLibrary =
            SolibSymlinkAction.getDynamicLibrarySymlink(
                /* actionConstructionContext= */ starlarkActionFactory.getRuleContext(),
                ccToolchainProvider.getSolibDirectory(),
                interfaceLibrary,
                /* preserveName= */ true,
                /* prefixConsumer= */ true);
      }
    }
    if (staticLibrary == null
        && picStaticLibrary == null
        && dynamicLibrary == null
        && interfaceLibrary == null) {
      throw Starlark.errorf(
          "Must pass at least one of the following parameters: static_library, pic_static_library, "
              + "dynamic_library and interface_library.");
    }
    return LibraryToLink.builder()
        .setLibraryIdentifier(CcLinkingOutputs.libraryIdentifierOf(notNullArtifactForIdentifier))
        .setStaticLibrary(staticLibrary)
        .setPicStaticLibrary(picStaticLibrary)
        .setDynamicLibrary(dynamicLibrary)
        .setResolvedSymlinkDynamicLibrary(resolvedSymlinkDynamicLibrary)
        .setInterfaceLibrary(interfaceLibrary)
        .setResolvedSymlinkInterfaceLibrary(resolvedSymlinkInterfaceLibrary)
        .setObjectFiles(nopicObjects)
        .setPicObjectFiles(picObjects)
        .setAlwayslink(alwayslink)
        .setMustKeepDebug(mustKeepDebug)
        .setLtoCompilationContext(ltoCompilationContext)
        .build();
  }

  private static void validateSymlinkPath(
      String attrName, PathFragment symlinkPath, FileTypeSet filetypes, StringBuilder errorsBuilder)
      throws EvalException {
    if (symlinkPath.isEmpty()
        || symlinkPath.isAbsolute()
        || symlinkPath.containsUplevelReferences()) {
      throw Starlark.errorf("%s must be a relative file path. Got '%s'", attrName, symlinkPath);
    }
    if (!filetypes.matches(symlinkPath.getBaseName())) {
      errorsBuilder.append(
          String.format(
              "'%s' %s %s", symlinkPath, "does not have any of the allowed extensions", filetypes));
      errorsBuilder.append(LINE_SEPARATOR.value());
    }
  }

  @Override
  public CcCompilationContext createCcCompilationContext(
      Object headers,
      Object systemIncludes,
      Object includes,
      Object quoteIncludes,
      Object frameworkIncludes,
      Object defines,
      Object localDefines,
      Sequence<?> directTextualHdrs,
      Sequence<?> directPublicHdrs,
      Sequence<?> directPrivateHdrs,
      Object unused3,
      Object moduleMap,
      Object unused1,
      Object unused2,
      Object externalIncludes,
      Object virtualToOriginalHeaders,
      Sequence<?> dependentCcCompilationContexts,
      Sequence<?> exportedDependentCcCompilationContexts,
      Sequence<?> nonCodeInputs,
      Sequence<?> looseHdrsDirsObject,
      String headersCheckingMode,
      Boolean propagateModuleMapToCompileAction,
      Object picHeaderModule,
      Object headerModule,
      Sequence<?> separateModuleHeaders,
      Object separateModule,
      Object separatePicModule,
      Object addPublicHeadersToModularHeaders,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);

    CcCompilationContext.Builder ccCompilationContext = CcCompilationContext.builder();

    // Public parameters.
    ImmutableList<Artifact> headerList = toNestedSetOfArtifacts(headers, "headers").toList();
    ccCompilationContext.addDeclaredIncludeSrcs(headerList);
    ImmutableList<Artifact> textualHdrsList =
        Sequence.cast(directTextualHdrs, Artifact.class, "direct_textual_headers")
            .getImmutableList();
    ImmutableList<Artifact> modularPublicHdrsList =
        Sequence.cast(directPublicHdrs, Artifact.class, "direct_public_headers").getImmutableList();
    ImmutableList<Artifact> modularPrivateHdrsList =
        Sequence.cast(directPrivateHdrs, Artifact.class, "direct_private_headers")
            .getImmutableList();

    ccCompilationContext.addSystemIncludeDirs(
        toNestedSetOfStrings(systemIncludes, "system_includes").toList().stream()
            .map(x -> PathFragment.create(x))
            .collect(toImmutableList()));
    ccCompilationContext.addIncludeDirs(
        toNestedSetOfStrings(includes, "includes").toList().stream()
            .map(x -> PathFragment.create(x))
            .collect(toImmutableList()));
    ccCompilationContext.addQuoteIncludeDirs(
        toNestedSetOfStrings(quoteIncludes, "quote_includes").toList().stream()
            .map(x -> PathFragment.create(x))
            .collect(toImmutableList()));
    ccCompilationContext.addFrameworkIncludeDirs(
        toNestedSetOfStrings(frameworkIncludes, "framework_includes").toList().stream()
            .map(x -> PathFragment.create(x))
            .collect(toImmutableList()));
    ccCompilationContext.addDefines(toNestedSetOfStrings(defines, "defines").toList());
    ccCompilationContext.addNonTransitiveDefines(
        toNestedSetOfStrings(localDefines, "local_defines").toList());
    ccCompilationContext.addTextualHdrs(textualHdrsList);
    ccCompilationContext.addModularPublicHdrs(modularPublicHdrsList);
    ccCompilationContext.addModularPrivateHdrs(modularPrivateHdrsList);

    if (moduleMap != null && moduleMap != Starlark.UNBOUND && moduleMap != Starlark.NONE) {
      ccCompilationContext.setCppModuleMap((CppModuleMap) moduleMap);
    }

    ccCompilationContext.addExternalIncludeDirs(
        toNestedSetOfStrings(externalIncludes, "external_includes").toList().stream()
            .map(PathFragment::create)
            .collect(toImmutableList()));

    ccCompilationContext.addVirtualToOriginalHeaders(
        Depset.cast(virtualToOriginalHeaders, Tuple.class, "virtual_to_original_headers"));

    ccCompilationContext.addDependentCcCompilationContexts(
        Sequence.cast(
                exportedDependentCcCompilationContexts,
                CcCompilationContext.class,
                "exported_dependent_cc_compilation_contexts")
            .getImmutableList(),
        Sequence.cast(
                dependentCcCompilationContexts,
                CcCompilationContext.class,
                "dependent_cc_compilation_contexts")
            .getImmutableList());

    ccCompilationContext.addNonCodeInputs(
        Sequence.cast(nonCodeInputs, Artifact.class, "non_code_inputs").getImmutableList());

    ccCompilationContext.setPropagateCppModuleMapAsActionInput(propagateModuleMapToCompileAction);
    ccCompilationContext.setPicHeaderModule(
        picHeaderModule == Starlark.NONE ? null : (Artifact.DerivedArtifact) picHeaderModule);
    ccCompilationContext.setHeaderModule(
        headerModule == Starlark.NONE ? null : (Artifact.DerivedArtifact) headerModule);
    ccCompilationContext.setSeparateModuleHdrs(
        Sequence.cast(separateModuleHeaders, Artifact.class, "separate_module_headers"),
        convertFromNoneable(separateModule, null),
        convertFromNoneable(separatePicModule, null));

    if ((Boolean) addPublicHeadersToModularHeaders) {
      ccCompilationContext.addModularPublicHdrs(headerList);
    }

    return ccCompilationContext.build();
  }

  @Override
  public CcCompilationOutputs mergeCcCompilationOutputsFromStarlark(
      Sequence<?> compilationOutputs, // <CcCompilationOutputs>
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    CcCompilationOutputs.Builder ccCompilationOutputsBuilder = CcCompilationOutputs.builder();
    for (CcCompilationOutputs ccCompilationOutputs :
        Sequence.cast(compilationOutputs, CcCompilationOutputs.class, "compilation_outputs")) {
      ccCompilationOutputsBuilder.merge(ccCompilationOutputs);
    }
    return ccCompilationOutputsBuilder.build();
  }

  @Override
  public CcCompilationContext mergeCompilationContexts(
      Sequence<?> compilationContexts,
      Sequence<?> nonExportedCompilationContexts,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    if (compilationContexts.isEmpty() && nonExportedCompilationContexts.isEmpty()) {
      return CcCompilationContext.EMPTY;
    }
    return CcCompilationContext.builder()
        .addDependentCcCompilationContexts(
            Sequence.cast(compilationContexts, CcCompilationContext.class, "compilation_contexts"),
            Sequence.cast(
                nonExportedCompilationContexts,
                CcCompilationContext.class,
                "non_exported_compilation_contexts"))
        .build();
  }

  @StarlarkMethod(
      name = "merge_linking_contexts",
      documented = false,
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "linking_contexts",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "[]"),
      })
  public CcLinkingContext mergeLinkingContexts(
      Sequence<?> linkingContexts, // <CcLinkingContext> expected
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return CcLinkingContext.merge(
        Sequence.cast(linkingContexts, CcLinkingContext.class, "linking_contexts"));
  }

  private static NestedSet<Artifact> toNestedSetOfArtifacts(Object obj, String fieldName)
      throws EvalException {
    if (obj == Starlark.UNBOUND) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    } else {
      return Depset.noneableCast(obj, Artifact.class, fieldName);
    }
  }

  private static NestedSet<String> toNestedSetOfStrings(Object obj, String fieldName)
      throws EvalException {
    if (obj == Starlark.UNBOUND) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    } else {
      return Depset.noneableCast(obj, String.class, fieldName);
    }
  }

  @Override
  public CppModuleMap createCppModuleMap(
      Artifact file, Object umbrellaHeaderNoneable, String name, StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    Artifact umbrellaHeader = convertFromNoneable(umbrellaHeaderNoneable, /* defaultValue= */ null);
    if (umbrellaHeader == null) {
      return new CppModuleMap(file, name);
    } else {
      return new CppModuleMap(file, umbrellaHeader, name);
    }
  }

  /**
   * Create an LTO backend that does not perform any cross-module optimization because Starlark does
   * not have support for LTO indexing actions yet.
   *
   * <p>TODO(b/128341904): Do cross module optimization once there is Starlark support.
   */
  @Override
  public LtoBackendArtifacts createLtoBackendArtifacts(
      StarlarkRuleContext starlarkRuleContext,
      String ltoOutputRootPrefixString,
      String ltoObjRootPrefixString,
      Artifact bitcodeFile,
      FeatureConfigurationForStarlark featureConfigurationForStarlark,
      Info ccToolchainInfo,
      StructImpl fdoContextStruct,
      boolean usePic,
      boolean shouldCreatePerObjectDebugInfo,
      Sequence<?> argv,
      StarlarkThread thread)
      throws EvalException, InterruptedException, RuleErrorException {
    isCalledFromStarlarkCcCommon(thread);
    RuleContext ruleContext = starlarkRuleContext.getRuleContext();
    PathFragment ltoOutputRootPrefix = PathFragment.create(ltoOutputRootPrefixString);
    PathFragment ltoObjRootPrefix = PathFragment.create(ltoObjRootPrefixString);
    CcToolchainProvider ccToolchain =
        CcToolchainProvider.PROVIDER.wrapOrThrowEvalException(ccToolchainInfo);
    LtoBackendArtifacts ltoBackendArtifacts;
    ltoBackendArtifacts =
        new LtoBackendArtifacts(
            ltoOutputRootPrefix,
            ltoObjRootPrefix,
            bitcodeFile,
            /* allBitcodeFiles= */ null,
            CppLinkActionBuilder.newActionConstruction(ruleContext),
            featureConfigurationForStarlark.getFeatureConfiguration(),
            ccToolchain,
            new FdoContext(fdoContextStruct),
            usePic,
            shouldCreatePerObjectDebugInfo,
            Sequence.cast(argv, String.class, "argv"));
    return ltoBackendArtifacts;
  }

  @Override
  public CcLinkingContext.LinkerInput createLinkerInput(
      Label owner,
      Object librariesToLinkObject,
      Object userLinkFlagsObject,
      Object nonCodeInputs, // <FileT> expected
      Object linkstampsObject,
      StarlarkThread thread)
      throws EvalException, InterruptedException {
    isCalledFromStarlarkCcCommon(thread);
    ImmutableList.Builder<LinkOptions> optionsBuilder = ImmutableList.builder();
    if (userLinkFlagsObject instanceof Depset || userLinkFlagsObject instanceof NoneType) {
      // Depsets are allowed in user_link_flags for compatibility purposes but they do not really
      // make sense here since LinkerInput takes a list of flags. For storing user_link_flags
      // without flattening they would have to be wrapped around a LinkerInput for which we keep
      // a depset that isn't flattened till the end.
      ImmutableList<String> userLinkFlagsFlattened =
          Depset.noneableCast(userLinkFlagsObject, String.class, "user_link_flags").toList();
      if (!userLinkFlagsFlattened.isEmpty()) {
        LinkOptions options = LinkOptions.of(userLinkFlagsFlattened, thread.getNextIdentityToken());
        optionsBuilder.add(options);
      }
    } else if (userLinkFlagsObject instanceof Sequence) {
      ImmutableList<Object> options =
          Sequence.cast(userLinkFlagsObject, Object.class, "user_link_flags[]").getImmutableList();
      if (!options.isEmpty()) {
        if (options.get(0) instanceof String) {
          optionsBuilder.add(
              LinkOptions.of(
                  Sequence.cast(userLinkFlagsObject, String.class, "user_link_flags[]")
                      .getImmutableList(),
                  thread.getNextIdentityToken()));
        } else if (options.get(0) instanceof Sequence) {
          for (Object optionObject : options) {
            ImmutableList<String> option =
                Sequence.cast(optionObject, String.class, "user_link_flags[][]").getImmutableList();
            optionsBuilder.add(LinkOptions.of(option, thread.getNextIdentityToken()));
          }
        } else {
          throw Starlark.errorf(
              "Elements of list in user_link_flags must be either Strings or lists.");
        }
      }
    }

    return CcLinkingContext.LinkerInput.builder()
        .setOwner(owner)
        .addLibraries(
            Depset.noneableCast(librariesToLinkObject, LibraryToLink.class, "libraries").toList())
        .addUserLinkFlags(optionsBuilder.build())
        .addLinkstamps(convertToNestedSet(linkstampsObject, Linkstamp.class, "linkstamps").toList())
        .addNonCodeInputs(
            Depset.noneableCast(nonCodeInputs, Artifact.class, "additional_inputs").toList())
        .build();
  }

  @Override
  public boolean checkExperimentalCcSharedLibrary(StarlarkThread thread) throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_CC_SHARED_LIBRARY);
  }

  @Override
  public boolean checkExperimentalCcStaticLibrary(StarlarkThread thread) throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_CC_STATIC_LIBRARY);
  }

  @Override
  public boolean getIncompatibleDisableObjcLibraryTransition(StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return thread
        .getSemantics()
        .getBool(BuildLanguageOptions.INCOMPATIBLE_DISABLE_OBJC_LIBRARY_TRANSITION);
  }

  @Override
  public CcLinkingContext createCcLinkingInfo(
      Object linkerInputs,
      Object librariesToLinkObject,
      Object userLinkFlagsObject,
      Object nonCodeInputsObject,
      Object extraLinkTimeLibraryObject,
      Object ownerObject,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    if (Starlark.isNullOrNone(linkerInputs)) {
      if (thread
          .getSemantics()
          .getBool(BuildLanguageOptions.INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API)) {
        throw Starlark.errorf("linker_inputs cannot be None");
      }
      @SuppressWarnings("unchecked")
      Sequence<LibraryToLink> librariesToLink = nullIfNone(librariesToLinkObject, Sequence.class);
      @SuppressWarnings("unchecked")
      Sequence<String> userLinkFlags = nullIfNone(userLinkFlagsObject, Sequence.class);

      if (librariesToLink != null || userLinkFlags != null) {
        CcLinkingContext.Builder ccLinkingContextBuilder = CcLinkingContext.builder();
        // TODO(b/135146460): Old API, no support for shared library, linker input won't have
        //  labels.
        if (librariesToLink != null) {
          ccLinkingContextBuilder.addLibraries(librariesToLink.getImmutableList());
        }
        if (userLinkFlags != null) {
          ccLinkingContextBuilder.addUserLinkFlags(
              ImmutableList.of(
                  CcLinkingContext.LinkOptions.of(
                      userLinkFlags.getImmutableList(), thread.getNextIdentityToken())));
        }
        @SuppressWarnings("unchecked")
        Sequence<String> nonCodeInputs = nullIfNone(nonCodeInputsObject, Sequence.class);
        if (nonCodeInputs != null) {
          ccLinkingContextBuilder.addNonCodeInputs(
              Sequence.cast(nonCodeInputs, Artifact.class, "additional_inputs"));
        }
        return ccLinkingContextBuilder.build();
      }

      throw Starlark.errorf("Must pass libraries_to_link, user_link_flags or both.");
    } else {
      CcLinkingContext.Builder ccLinkingContextBuilder = CcLinkingContext.builder();
      ccLinkingContextBuilder.addTransitiveLinkerInputs(
          Depset.noneableCast(linkerInputs, CcLinkingContext.LinkerInput.class, "linker_inputs"));
      ExtraLinkTimeLibrary extraLinkTimeLibrary =
          convertFromNoneable(extraLinkTimeLibraryObject, /* defaultValue= */ null);
      if (extraLinkTimeLibrary != null) {
        ccLinkingContextBuilder.setExtraLinkTimeLibraries(
            ExtraLinkTimeLibraries.builder().add(extraLinkTimeLibrary).build());
      }
      Label owner = convertFromNoneable(ownerObject, /* defaultValue= */ null);
      if (owner != null) {
        ccLinkingContextBuilder.setOwner(owner);
      }

      @SuppressWarnings("unchecked")
      Sequence<LibraryToLink> librariesToLink = nullIfNone(librariesToLinkObject, Sequence.class);
      @SuppressWarnings("unchecked")
      Sequence<String> userLinkFlags = nullIfNone(userLinkFlagsObject, Sequence.class);
      @SuppressWarnings("unchecked")
      Sequence<String> nonCodeInputs = nullIfNone(nonCodeInputsObject, Sequence.class);

      if (librariesToLink != null || userLinkFlags != null || nonCodeInputs != null) {
        throw Starlark.errorf(
            "If you pass linker_inputs you are using the new API. "
                + "Just pass linker_inputs. Do not mix old and new API parameters.");
      }

      return ccLinkingContextBuilder.build();
    }
  }

  // TODO(b/65151735): Remove when cc_flags is entirely from features.
  @Override
  public String legacyCcFlagsMakeVariable(Info ccToolchainInfo, StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    CcToolchainProvider ccToolchain =
        CcToolchainProvider.PROVIDER.wrapOrThrowEvalException(ccToolchainInfo);
    return ccToolchain.getLegacyCcFlagsMakeVariable();
  }

  /** Converts None, or a Sequence, or a Depset to a NestedSet. */
  private static <T> NestedSet<T> convertToNestedSet(Object o, Class<T> type, String fieldName)
      throws EvalException {
    if (o == Starlark.UNBOUND || o == Starlark.NONE) {
      return NestedSetBuilder.emptySet(Order.COMPILE_ORDER);
    }
    return o instanceof Depset
        ? Depset.cast(o, type, fieldName)
        : NestedSetBuilder.wrap(Order.COMPILE_ORDER, Sequence.cast(o, type, fieldName));
  }

  @Override
  public CcToolchainConfigInfo ccToolchainConfigInfoFromStarlark(
      StarlarkRuleContext starlarkRuleContext,
      Sequence<?> features, // <StarlarkInfo> expected
      Sequence<?> actionConfigs, // <StarlarkInfo> expected
      Sequence<?> artifactNamePatterns, // <StarlarkInfo> expected
      Sequence<?> cxxBuiltInIncludeDirectoriesUnchecked, // <String> expected
      String toolchainIdentifier,
      Object hostSystemName,
      Object targetSystemName,
      Object targetCpu,
      Object targetLibc,
      String compiler,
      Object abiVersion,
      Object abiLibcVersion,
      Sequence<?> toolPaths, // <StarlarkInfo> expected
      Sequence<?> makeVariables, // <StarlarkInfo> expected
      Object builtinSysroot,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    List<String> cxxBuiltInIncludeDirectories =
        Sequence.cast(
            cxxBuiltInIncludeDirectoriesUnchecked, String.class, "cxx_builtin_include_directories");

    ImmutableList.Builder<Feature> featureBuilder = ImmutableList.builder();
    for (Object feature : features) {
      checkRightStarlarkInfoProvider(feature, "features", "FeatureInfo");
      featureBuilder.add(featureFromStarlark((StarlarkInfo) feature));
    }
    ImmutableList<Feature> featureList = featureBuilder.build();

    ImmutableSet<String> featureNames =
        featureList.stream().map(Feature::getName).collect(toImmutableSet());

    ImmutableList.Builder<ActionConfig> actionConfigBuilder = ImmutableList.builder();
    for (Object actionConfig : actionConfigs) {
      checkRightStarlarkInfoProvider(actionConfig, "action_configs", "ActionConfigInfo");
      actionConfigBuilder.add(actionConfigFromStarlark((StarlarkInfo) actionConfig));
    }
    ImmutableList<ActionConfig> actionConfigList = actionConfigBuilder.build();

    ImmutableSet<String> actionConfigNames =
        actionConfigList.stream()
            .map(actionConfig -> actionConfig.getActionName())
            .collect(toImmutableSet());

    CcToolchainFeatures.ArtifactNamePatternMapper.Builder artifactNamePatternBuilder =
        new CcToolchainFeatures.ArtifactNamePatternMapper.Builder();
    for (Object artifactNamePattern : artifactNamePatterns) {
      checkRightStarlarkInfoProvider(
          artifactNamePattern, "artifact_name_patterns", "ArtifactNamePatternInfo");
      artifactNamePatternFromStarlark(
          (StarlarkInfo) artifactNamePattern, artifactNamePatternBuilder::addOverride);
    }

    // Pairs (toolName, toolPath)
    ImmutableList.Builder<Pair<String, String>> toolPathPairs = ImmutableList.builder();
    for (Object toolPath : toolPaths) {
      checkRightStarlarkInfoProvider(toolPath, "tool_paths", "ToolPathInfo");
      Pair<String, String> toolPathPair = toolPathFromStarlark((StarlarkInfo) toolPath);
      toolPathPairs.add(toolPathPair);
    }
    ImmutableList<Pair<String, String>> toolPathList = toolPathPairs.build();

    if (!featureNames.contains(CppRuleClasses.NO_LEGACY_FEATURES)) {
      String gccToolPath = "DUMMY_GCC_TOOL";
      String linkerToolPath = "DUMMY_LINKER_TOOL";
      String arToolPath = "DUMMY_AR_TOOL";
      String stripToolPath = "DUMMY_STRIP_TOOL";
      for (Pair<String, String> tool : toolPathList) {
        if (tool.first.equals(CppConfiguration.Tool.GCC.getNamePart())) {
          gccToolPath = tool.second;
          linkerToolPath =
              starlarkRuleContext
                  .getRuleContext()
                  .getLabel()
                  .getPackageIdentifier()
                  .getExecPath(starlarkRuleContext.getConfiguration().isSiblingRepositoryLayout())
                  .getRelative(PathFragment.create(tool.second))
                  .getPathString();
        }
        if (tool.first.equals(CppConfiguration.Tool.AR.getNamePart())) {
          arToolPath = tool.second;
        }
        if (tool.first.equals(CppConfiguration.Tool.STRIP.getNamePart())) {
          stripToolPath = tool.second;
        }
      }

      ImmutableList.Builder<Feature> legacyFeaturesBuilder = ImmutableList.builder();
      // TODO(b/30109612): Remove fragile legacyCompileFlags shuffle once there are no legacy
      // crosstools.
      // Existing projects depend on flags from legacy toolchain fields appearing first on the
      // compile command line. 'legacy_compile_flags' feature contains all these flags, and so it
      // needs to appear before other features from {@link CppActionConfigs}.
      if (featureNames.contains(CppRuleClasses.LEGACY_COMPILE_FLAGS)) {
        Feature legacyCompileFlags =
            featureList.stream()
                .filter(feature -> feature.getName().equals(CppRuleClasses.LEGACY_COMPILE_FLAGS))
                .findFirst()
                .get();
        if (legacyCompileFlags != null) {
          legacyFeaturesBuilder.add(legacyCompileFlags);
        }
      }
      if (featureNames.contains(CppRuleClasses.DEFAULT_COMPILE_FLAGS)) {
        Feature defaultCompileFlags =
            featureList.stream()
                .filter(feature -> feature.getName().equals(CppRuleClasses.DEFAULT_COMPILE_FLAGS))
                .findFirst()
                .get();
        if (defaultCompileFlags != null) {
          legacyFeaturesBuilder.add(defaultCompileFlags);
        }
      }

      CppPlatform platform =
          targetLibc.equals(CppActionConfigs.MACOS_TARGET_LIBC)
              ? CppPlatform.MAC
              : CppPlatform.LINUX;
      for (CToolchain.Feature feature :
          CppActionConfigs.getLegacyFeatures(
              platform,
              featureNames,
              linkerToolPath,
              /* supportsEmbeddedRuntimes= */ false,
              /* supportsInterfaceSharedLibraries= */ false)) {
        legacyFeaturesBuilder.add(new Feature(feature));
      }
      legacyFeaturesBuilder.addAll(
          featureList.stream()
              .filter(feature -> !feature.getName().equals(CppRuleClasses.LEGACY_COMPILE_FLAGS))
              .filter(feature -> !feature.getName().equals(CppRuleClasses.DEFAULT_COMPILE_FLAGS))
              .collect(toImmutableList()));
      for (CToolchain.Feature feature :
          CppActionConfigs.getFeaturesToAppearLastInFeaturesList(featureNames)) {
        legacyFeaturesBuilder.add(new Feature(feature));
      }

      featureList = legacyFeaturesBuilder.build();

      ImmutableList.Builder<ActionConfig> legacyActionConfigBuilder = ImmutableList.builder();
      for (CToolchain.ActionConfig actionConfig :
          CppActionConfigs.getLegacyActionConfigs(
              platform,
              gccToolPath,
              arToolPath,
              stripToolPath,
              /* supportsInterfaceSharedLibraries= */ false,
              actionConfigNames)) {
        legacyActionConfigBuilder.add(new ActionConfig(actionConfig));
      }
      legacyActionConfigBuilder.addAll(actionConfigList);
      actionConfigList = legacyActionConfigBuilder.build();
    }

    ImmutableList.Builder<Pair<String, String>> makeVariablePairs = ImmutableList.builder();
    for (Object makeVariable : makeVariables) {
      checkRightStarlarkInfoProvider(makeVariable, "make_variables", "MakeVariableInfo");
      Pair<String, String> makeVariablePair = makeVariableFromStarlark((StarlarkInfo) makeVariable);
      makeVariablePairs.add(makeVariablePair);
    }

    return new CcToolchainConfigInfo(
        actionConfigList,
        featureList,
        artifactNamePatternBuilder.build(),
        ImmutableList.copyOf(cxxBuiltInIncludeDirectories),
        toolchainIdentifier,
        convertFromNoneable(hostSystemName, /* defaultValue= */ ""),
        convertFromNoneable(targetSystemName, /* defaultValue= */ ""),
        convertFromNoneable(targetCpu, /* defaultValue= */ ""),
        convertFromNoneable(targetLibc, /* defaultValue= */ ""),
        compiler,
        convertFromNoneable(abiVersion, /* defaultValue= */ ""),
        convertFromNoneable(abiLibcVersion, /* defaultValue= */ ""),
        toolPathList,
        makeVariablePairs.build(),
        convertFromNoneable(builtinSysroot, /* defaultValue= */ ""));
  }

  private static void checkRightStarlarkInfoProvider(
      Object o, String parameterName, String expectedProvider) throws EvalException {
    if (!(o instanceof StarlarkInfo)) {
      throw Starlark.errorf(
          "'%s' parameter of cc_common.create_cc_toolchain_config_info() contains an element"
              + " of type '%s' instead of a '%s' provider. Use the methods provided in"
              + " https://source.bazel.build/bazel/+/master:tools/cpp/cc_toolchain_config_lib.bzl"
              + " for obtaining the right providers.",
          parameterName, Starlark.type(o), expectedProvider);
    }
  }

  @FormatMethod
  private static EvalException infoError(Info info, String format, Object... args) {
    return Starlark.errorf(
        "in %s instantiated at %s: %s",
        info.getProvider().getPrintableName(),
        info.getCreationLocation(),
        String.format(format, args));
  }

  /** Checks whether the {@link StarlarkInfo} is of the required type. */
  private static void checkRightProviderType(StarlarkInfo provider, String type)
      throws EvalException {
    String providerType = (String) getValueOrNull(provider, "type_name");
    if (providerType == null) {
      providerType = provider.getProvider().getPrintableName();
    }
    if (!type.equals(provider.getValue("type_name"))) {
      throw infoError(provider, "Expected object of type '%s', received '%s'.", type, providerType);
    }
  }

  @Nullable
  private static Object getValueOrNull(Structure x, String name) {
    try {
      return x.getValue(name);
    } catch (EvalException e) {
      return null;
    }
  }

  /** Creates a {@link Feature} from a {@link StarlarkInfo}. */
  @VisibleForTesting
  static Feature featureFromStarlark(StarlarkInfo featureStruct) throws EvalException {
    checkRightProviderType(featureStruct, "feature");
    String name = getMandatoryFieldFromStarlarkProvider(featureStruct, "name", String.class);
    Boolean enabled =
        getMandatoryFieldFromStarlarkProvider(featureStruct, "enabled", Boolean.class);
    if (name == null || (name.isEmpty() && !enabled)) {
      throw infoError(
          featureStruct, "A feature must either have a nonempty 'name' field or be enabled.");
    }

    if (!name.matches("^[_a-z0-9+\\-\\.]*$")) {
      throw infoError(
          featureStruct,
          "A feature's name must consist solely of lowercase ASCII letters, digits, '.', "
              + "'_', '+', and '-', got '%s'",
          name);
    }

    ImmutableList.Builder<FlagSet> flagSetBuilder = ImmutableList.builder();
    ImmutableList<StarlarkInfo> flagSets =
        getStarlarkProviderListFromStarlarkField(featureStruct, "flag_sets");
    for (StarlarkInfo flagSetObject : flagSets) {
      FlagSet flagSet = flagSetFromStarlark(flagSetObject, /* actionName= */ null);
      if (flagSet.getActions().isEmpty()) {
        throw infoError(
            flagSetObject,
            "A flag_set that belongs to a feature must have nonempty 'actions' parameter.");
      }
      flagSetBuilder.add(flagSet);
    }

    ImmutableList.Builder<EnvSet> envSetBuilder = ImmutableList.builder();
    ImmutableList<StarlarkInfo> envSets =
        getStarlarkProviderListFromStarlarkField(featureStruct, "env_sets");
    for (StarlarkInfo envSet : envSets) {
      envSetBuilder.add(envSetFromStarlark(envSet));
    }

    ImmutableList.Builder<ImmutableSet<String>> requiresBuilder = ImmutableList.builder();

    ImmutableList<StarlarkInfo> requires =
        getStarlarkProviderListFromStarlarkField(featureStruct, "requires");
    for (StarlarkInfo featureSetStruct : requires) {
      if (!"feature_set".equals(featureSetStruct.getValue("type_name"))) { // getValue() may be null
        throw infoError(featureStruct, "expected object of type 'feature_set'.");
      }
      ImmutableSet<String> featureSet =
          getStringSetFromStarlarkProviderField(featureSetStruct, "features");
      requiresBuilder.add(featureSet);
    }

    ImmutableList<String> implies =
        getStringListFromStarlarkProviderField(featureStruct, "implies");

    ImmutableList<String> provides =
        getStringListFromStarlarkProviderField(featureStruct, "provides");

    return new Feature(
        name,
        flagSetBuilder.build(),
        envSetBuilder.build(),
        enabled,
        requiresBuilder.build(),
        implies,
        provides);
  }

  /**
   * Creates a Pair(name, value) that represents a {@link
   * com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.MakeVariable} from a {@link
   * StarlarkInfo}.
   */
  @VisibleForTesting
  static Pair<String, String> makeVariableFromStarlark(StarlarkInfo makeVariableStruct)
      throws EvalException {
    checkRightProviderType(makeVariableStruct, "make_variable");
    String name = getMandatoryFieldFromStarlarkProvider(makeVariableStruct, "name", String.class);
    String value = getMandatoryFieldFromStarlarkProvider(makeVariableStruct, "value", String.class);
    if (name == null || name.isEmpty()) {
      throw infoError(
          makeVariableStruct, "'name' parameter of make_variable must be a nonempty string.");
    }
    if (value == null || value.isEmpty()) {
      throw infoError(
          makeVariableStruct, "'value' parameter of make_variable must be a nonempty string.");
    }
    return Pair.of(name, value);
  }

  /**
   * Creates a Pair(name, path) that represents a {@link
   * com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.ToolPath} from a {@link
   * StarlarkInfo}.
   */
  @VisibleForTesting
  static Pair<String, String> toolPathFromStarlark(StarlarkInfo toolPathStruct)
      throws EvalException {
    checkRightProviderType(toolPathStruct, "tool_path");
    String name = getMandatoryFieldFromStarlarkProvider(toolPathStruct, "name", String.class);
    String path = getMandatoryFieldFromStarlarkProvider(toolPathStruct, "path", String.class);
    if (name == null || name.isEmpty()) {
      throw infoError(toolPathStruct, "'name' parameter of tool_path must be a nonempty string.");
    }
    if (path == null || path.isEmpty()) {
      throw infoError(toolPathStruct, "'path' parameter of tool_path must be a nonempty string.");
    }
    return Pair.of(name, path);
  }

  /** Creates a {@link VariableWithValue} from a {@link StarlarkInfo}. */
  @VisibleForTesting
  static VariableWithValue variableWithValueFromStarlark(StarlarkInfo variableWithValueStruct)
      throws EvalException {
    checkRightProviderType(variableWithValueStruct, "variable_with_value");
    String name =
        getMandatoryFieldFromStarlarkProvider(variableWithValueStruct, "name", String.class);
    String value =
        getMandatoryFieldFromStarlarkProvider(variableWithValueStruct, "value", String.class);
    if (name == null || name.isEmpty()) {
      throw infoError(
          variableWithValueStruct,
          "'name' parameter of variable_with_value must be a nonempty string.");
    }
    if (value == null || value.isEmpty()) {
      throw infoError(
          variableWithValueStruct,
          "'value' parameter of variable_with_value must be a nonempty string.");
    }
    return new VariableWithValue(name, value);
  }

  /** Creates an {@link EnvEntry} from a {@link StarlarkInfo}. */
  @VisibleForTesting
  static EnvEntry envEntryFromStarlark(StarlarkInfo envEntryStruct) throws EvalException {
    checkRightProviderType(envEntryStruct, "env_entry");
    String key = getMandatoryFieldFromStarlarkProvider(envEntryStruct, "key", String.class);
    String value = getMandatoryFieldFromStarlarkProvider(envEntryStruct, "value", String.class);
    if (key == null || key.isEmpty()) {
      throw infoError(envEntryStruct, "'key' parameter of env_entry must be a nonempty string.");
    }
    if (value == null || value.isEmpty()) {
      throw infoError(envEntryStruct, "'value' parameter of env_entry must be a nonempty string.");
    }
    String expandIfAvailable =
        getOptionalFieldFromStarlarkProvider(envEntryStruct, "expand_if_available", String.class);
    StringValueParser parser = new StringValueParser(value);
    return new EnvEntry(
        key,
        parser.getChunks(),
        expandIfAvailable == null ? ImmutableSet.of() : ImmutableSet.of(expandIfAvailable));
  }

  /** Creates a {@link WithFeatureSet} from a {@link StarlarkInfo}. */
  @VisibleForTesting
  static WithFeatureSet withFeatureSetFromStarlark(StarlarkInfo withFeatureSetStruct)
      throws EvalException {
    checkRightProviderType(withFeatureSetStruct, "with_feature_set");
    ImmutableSet<String> features =
        getStringSetFromStarlarkProviderField(withFeatureSetStruct, "features");
    ImmutableSet<String> notFeatures =
        getStringSetFromStarlarkProviderField(withFeatureSetStruct, "not_features");
    return new WithFeatureSet(features, notFeatures);
  }

  /** Creates an {@link EnvSet} from a {@link StarlarkInfo}. */
  @VisibleForTesting
  static EnvSet envSetFromStarlark(StarlarkInfo envSetStruct) throws EvalException {
    checkRightProviderType(envSetStruct, "env_set");
    ImmutableSet<String> actions = getStringSetFromStarlarkProviderField(envSetStruct, "actions");
    if (actions.isEmpty()) {
      throw infoError(envSetStruct, "actions parameter of env_set must be a nonempty list.");
    }
    ImmutableList.Builder<EnvEntry> envEntryBuilder = ImmutableList.builder();
    ImmutableList<StarlarkInfo> envEntryStructs =
        getStarlarkProviderListFromStarlarkField(envSetStruct, "env_entries");
    for (StarlarkInfo envEntryStruct : envEntryStructs) {
      envEntryBuilder.add(envEntryFromStarlark(envEntryStruct));
    }

    ImmutableSet.Builder<WithFeatureSet> withFeatureSetBuilder = ImmutableSet.builder();
    ImmutableList<StarlarkInfo> withFeatureSetStructs =
        getStarlarkProviderListFromStarlarkField(envSetStruct, "with_features");
    for (StarlarkInfo withFeatureSetStruct : withFeatureSetStructs) {
      withFeatureSetBuilder.add(withFeatureSetFromStarlark(withFeatureSetStruct));
    }
    return new EnvSet(actions, envEntryBuilder.build(), withFeatureSetBuilder.build());
  }

  /** Creates a {@link FlagGroup} from a {@link StarlarkInfo}. */
  @VisibleForTesting
  static FlagGroup flagGroupFromStarlark(StarlarkInfo flagGroupStruct) throws EvalException {
    checkRightProviderType(flagGroupStruct, "flag_group");

    ImmutableList.Builder<Expandable> expandableBuilder = ImmutableList.builder();
    ImmutableList<String> flags = getStringListFromStarlarkProviderField(flagGroupStruct, "flags");
    for (String flag : flags) {
      StringValueParser parser = new StringValueParser(flag);
      expandableBuilder.add(Flag.create(parser.getChunks()));
    }

    ImmutableList<StarlarkInfo> flagGroups =
        getStarlarkProviderListFromStarlarkField(flagGroupStruct, "flag_groups");
    for (StarlarkInfo flagGroup : flagGroups) {
      expandableBuilder.add(flagGroupFromStarlark(flagGroup));
    }

    if (flagGroups.size() > 0 && flags.size() > 0) {
      throw infoError(
          flagGroupStruct,
          "flag_group must contain either a list of flags or a list of flag_groups.");
    }

    if (flagGroups.size() == 0 && flags.size() == 0) {
      throw infoError(flagGroupStruct, "Both 'flags' and 'flag_groups' are empty.");
    }

    String iterateOver =
        getMandatoryFieldFromStarlarkProvider(flagGroupStruct, "iterate_over", String.class);
    String expandIfAvailable =
        getMandatoryFieldFromStarlarkProvider(flagGroupStruct, "expand_if_available", String.class);
    String expandIfNotAvailable =
        getMandatoryFieldFromStarlarkProvider(
            flagGroupStruct, "expand_if_not_available", String.class);
    String expandIfTrue =
        getMandatoryFieldFromStarlarkProvider(flagGroupStruct, "expand_if_true", String.class);
    String expandIfFalse =
        getMandatoryFieldFromStarlarkProvider(flagGroupStruct, "expand_if_false", String.class);
    StarlarkInfo expandIfEqualStruct =
        getMandatoryFieldFromStarlarkProvider(
            flagGroupStruct, "expand_if_equal", StarlarkInfo.class);
    VariableWithValue expandIfEqual =
        expandIfEqualStruct == null ? null : variableWithValueFromStarlark(expandIfEqualStruct);

    return new FlagGroup(
        expandableBuilder.build(),
        iterateOver,
        expandIfAvailable == null ? ImmutableSet.of() : ImmutableSet.of(expandIfAvailable),
        expandIfNotAvailable == null ? ImmutableSet.of() : ImmutableSet.of(expandIfNotAvailable),
        expandIfTrue,
        expandIfFalse,
        expandIfEqual);
  }

  /** Creates a {@link FlagSet} from a {@link StarlarkInfo}. */
  @VisibleForTesting
  static FlagSet flagSetFromStarlark(StarlarkInfo flagSetStruct, String actionName)
      throws EvalException {
    checkRightProviderType(flagSetStruct, "flag_set");
    ImmutableSet<String> actions = getStringSetFromStarlarkProviderField(flagSetStruct, "actions");
    // if we are creating a flag set for an action_config, we need to propagate the name of the
    // action to its flag_set.action_names
    if (actionName != null) {
      if (!actions.isEmpty()) {
        throw Starlark.errorf(ActionConfig.FLAG_SET_WITH_ACTION_ERROR, actionName);
      }
      actions = ImmutableSet.of(actionName);
    }
    ImmutableList.Builder<FlagGroup> flagGroupsBuilder = ImmutableList.builder();
    ImmutableList<StarlarkInfo> flagGroups =
        getStarlarkProviderListFromStarlarkField(flagSetStruct, "flag_groups");
    for (StarlarkInfo flagGroup : flagGroups) {
      flagGroupsBuilder.add(flagGroupFromStarlark(flagGroup));
    }

    ImmutableSet.Builder<WithFeatureSet> withFeatureSetBuilder = ImmutableSet.builder();
    ImmutableList<StarlarkInfo> withFeatureSetStructs =
        getStarlarkProviderListFromStarlarkField(flagSetStruct, "with_features");
    for (StarlarkInfo withFeatureSetStruct : withFeatureSetStructs) {
      withFeatureSetBuilder.add(withFeatureSetFromStarlark(withFeatureSetStruct));
    }

    return new FlagSet(
        actions, ImmutableSet.of(), withFeatureSetBuilder.build(), flagGroupsBuilder.build());
  }

  /**
   * Creates a {@link com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool} from a
   * {@link StarlarkInfo}.
   */
  @VisibleForTesting
  static CcToolchainFeatures.Tool toolFromStarlark(StarlarkInfo toolStruct) throws EvalException {
    checkRightProviderType(toolStruct, "tool");

    String toolPathString = getOptionalFieldFromStarlarkProvider(toolStruct, "path", String.class);
    Artifact toolArtifact =
        getOptionalFieldFromStarlarkProvider(toolStruct, "tool", Artifact.class);

    PathFragment toolPath;
    CToolchain.Tool.PathOrigin toolPathOrigin;
    if (toolPathString != null) {
      if (toolArtifact != null) {
        throw infoError(toolStruct, "\"tool\" and \"path\" cannot be set at the same time.");
      }

      toolPath = PathFragment.create(toolPathString);
      if (toolPath.isEmpty()) {
        throw infoError(toolStruct, "The 'path' field of tool must be a nonempty string.");
      }

      if (toolPath.isAbsolute()) {
        toolPathOrigin = CToolchain.Tool.PathOrigin.FILESYSTEM_ROOT;
      } else {
        toolPathOrigin = CToolchain.Tool.PathOrigin.CROSSTOOL_PACKAGE;
      }
    } else if (toolArtifact != null) {
      toolPath = toolArtifact.getExecPath();
      toolPathOrigin = CToolchain.Tool.PathOrigin.WORKSPACE_ROOT;
    } else {
      throw Starlark.errorf("Exactly one of \"tool\" and \"path\" must be set.");
    }
    Preconditions.checkState(toolPath != null && toolPathOrigin != null);

    ImmutableSet.Builder<WithFeatureSet> withFeatureSetBuilder = ImmutableSet.builder();
    ImmutableList<StarlarkInfo> withFeatureSetStructs =
        getStarlarkProviderListFromStarlarkField(toolStruct, "with_features");
    for (StarlarkInfo withFeatureSetStruct : withFeatureSetStructs) {
      withFeatureSetBuilder.add(withFeatureSetFromStarlark(withFeatureSetStruct));
    }

    ImmutableSet<String> executionRequirements =
        getStringSetFromStarlarkProviderField(toolStruct, "execution_requirements");
    return new CcToolchainFeatures.Tool(
        toolPath, toolPathOrigin, executionRequirements, withFeatureSetBuilder.build());
  }

  /** Creates an {@link ActionConfig} from a {@link StarlarkInfo}. */
  @VisibleForTesting
  static ActionConfig actionConfigFromStarlark(StarlarkInfo actionConfigStruct)
      throws EvalException {
    checkRightProviderType(actionConfigStruct, "action_config");
    String actionName =
        getMandatoryFieldFromStarlarkProvider(actionConfigStruct, "action_name", String.class);
    if (actionName == null || actionName.isEmpty()) {
      throw infoError(
          actionConfigStruct,
          "The 'action_name' field of action_config must be a nonempty string.");
    }
    if (!actionName.matches("^[_a-z0-9+\\-\\.]*$")) {
      throw infoError(
          actionConfigStruct,
          "An action_config's name must consist solely of lowercase ASCII letters, digits, "
              + "'.', '_', '+', and '-', got '%s'",
          actionName);
    }

    Boolean enabled =
        getMandatoryFieldFromStarlarkProvider(actionConfigStruct, "enabled", Boolean.class);

    ImmutableList.Builder<CcToolchainFeatures.Tool> toolBuilder = ImmutableList.builder();
    ImmutableList<StarlarkInfo> toolStructs =
        getStarlarkProviderListFromStarlarkField(actionConfigStruct, "tools");
    for (StarlarkInfo toolStruct : toolStructs) {
      toolBuilder.add(toolFromStarlark(toolStruct));
    }

    ImmutableList.Builder<FlagSet> flagSetBuilder = ImmutableList.builder();
    ImmutableList<StarlarkInfo> flagSets =
        getStarlarkProviderListFromStarlarkField(actionConfigStruct, "flag_sets");
    for (StarlarkInfo flagSet : flagSets) {
      flagSetBuilder.add(flagSetFromStarlark(flagSet, actionName));
    }

    ImmutableList<String> implies =
        getStringListFromStarlarkProviderField(actionConfigStruct, "implies");

    return new ActionConfig(
        actionName, actionName, toolBuilder.build(), flagSetBuilder.build(), enabled, implies);
  }

  @VisibleForTesting
  interface ArtifactNamePatternAdder {
    void add(ArtifactCategory category, String prefix, String extension);
  }

  @VisibleForTesting
  static void artifactNamePatternFromStarlark(
      StarlarkInfo artifactNamePatternStruct, ArtifactNamePatternAdder adder) throws EvalException {
    checkRightProviderType(artifactNamePatternStruct, "artifact_name_pattern");
    String categoryName =
        getMandatoryFieldFromStarlarkProvider(
            artifactNamePatternStruct, "category_name", String.class);
    if (categoryName == null || categoryName.isEmpty()) {
      throw infoError(
          artifactNamePatternStruct,
          "The 'category_name' field of artifact_name_pattern must be a nonempty string.");
    }
    ArtifactCategory foundCategory = null;
    for (ArtifactCategory artifactCategory : ArtifactCategory.values()) {
      if (categoryName.equals(artifactCategory.getCategoryName())) {
        foundCategory = artifactCategory;
      }
    }

    if (foundCategory == null) {
      throw infoError(
          artifactNamePatternStruct, "Artifact category %s not recognized.", categoryName);
    }

    String extension =
        Strings.nullToEmpty(
            getMandatoryFieldFromStarlarkProvider(
                artifactNamePatternStruct, "extension", String.class));
    if (!foundCategory.getAllowedExtensions().contains(extension)) {
      throw infoError(
          artifactNamePatternStruct,
          "Unrecognized file extension '%s', allowed extensions are %s,"
              + " please check artifact_name_pattern configuration for %s in your rule.",
          extension,
          StringUtil.joinEnglishListSingleQuoted(foundCategory.getAllowedExtensions()),
          foundCategory.getCategoryName());
    }

    String prefix =
        Strings.nullToEmpty(
            getMandatoryFieldFromStarlarkProvider(
                artifactNamePatternStruct, "prefix", String.class));
    adder.add(foundCategory, prefix, extension);
  }

  private static <T> T getOptionalFieldFromStarlarkProvider(
      StarlarkInfo provider, String fieldName, Class<T> clazz) throws EvalException {
    return getFieldFromStarlarkProvider(provider, fieldName, clazz, false);
  }

  private static <T> T getMandatoryFieldFromStarlarkProvider(
      StarlarkInfo provider, String fieldName, Class<T> clazz) throws EvalException {
    return getFieldFromStarlarkProvider(provider, fieldName, clazz, true);
  }

  private static <T> T getFieldFromStarlarkProvider(
      StarlarkInfo provider, String fieldName, Class<T> clazz, boolean mandatory)
      throws EvalException {
    Object obj = provider.getValue(fieldName);
    if (obj == null) {
      if (mandatory) {
        throw infoError(provider, "Missing mandatory field '%s'", fieldName);
      }
      return null;
    }
    if (clazz.isInstance(obj)) {
      return clazz.cast(obj);
    }
    if (NoneType.class.isInstance(obj)) {
      return null;
    }
    throw infoError(provider, "Field '%s' is not of '%s' type.", fieldName, clazz.getName());
  }

  /** Returns a list of strings from a field of a {@link StarlarkInfo}. */
  private static ImmutableList<String> getStringListFromStarlarkProviderField(
      StarlarkInfo provider, String fieldName) throws EvalException {
    Object v = getValueOrNull(provider, fieldName);
    return v == null
        ? ImmutableList.of()
        : ImmutableList.copyOf(Sequence.noneableCast(v, String.class, fieldName));
  }

  /** Returns a set of strings from a field of a {@link StarlarkInfo}. */
  private static ImmutableSet<String> getStringSetFromStarlarkProviderField(
      StarlarkInfo provider, String fieldName) throws EvalException {
    Object v = getValueOrNull(provider, fieldName);
    return v == null
        ? ImmutableSet.of()
        : ImmutableSet.copyOf(Sequence.noneableCast(v, String.class, fieldName));
  }

  /** Returns a list of StarlarkInfo providers from a field of a {@link StarlarkInfo}. */
  private static ImmutableList<StarlarkInfo> getStarlarkProviderListFromStarlarkField(
      StarlarkInfo provider, String fieldName) throws EvalException {
    Object v = getValueOrNull(provider, fieldName);
    return v == null
        ? ImmutableList.of()
        : ImmutableList.copyOf(Sequence.noneableCast(v, StarlarkInfo.class, fieldName));
  }

  @Nullable
  private static <T> T nullIfNone(Object object, Class<T> type) {
    return object != Starlark.NONE ? type.cast(object) : null;
  }

  // LINT.IfChange
  @Override
  public Tuple createLinkingContextFromCompilationOutputs(
      StarlarkActionFactory starlarkActionFactoryApi,
      String name,
      FeatureConfigurationForStarlark starlarkFeatureConfiguration,
      Info starlarkCcToolchainProvider,
      String language,
      boolean disallowStaticLibraries,
      boolean disallowDynamicLibraries,
      CcCompilationOutputs compilationOutputs,
      Sequence<?> linkingContextsObjects,
      Sequence<?> userLinkFlags, // <String> expected
      // <CcLinkingContext> expected
      boolean alwayslink,
      Sequence<?> additionalInputs, // <Artifact> expected
      Object variablesExtension,
      Object stamp,
      Object linkedDllNameSuffix,
      Object testOnlyTargetObject,
      StarlarkThread thread)
      throws InterruptedException, EvalException {
    isCalledFromStarlarkCcCommon(thread);
    StarlarkActionFactory actions = starlarkActionFactoryApi;
    int stampInt = 0;
    if (stamp != Starlark.UNBOUND) {
      stampInt = Starlark.toInt(stamp, "stamp");
    }
    boolean isStampingEnabled =
        isStampingEnabled(stampInt, actions.getRuleContext().getConfiguration());
    CcToolchainProvider ccToolchainProvider =
        CcToolchainProvider.PROVIDER.wrapOrThrowEvalException(starlarkCcToolchainProvider);
    FeatureConfigurationForStarlark featureConfiguration =
        convertFromNoneable(starlarkFeatureConfiguration, null);
    FdoContext fdoContext = ccToolchainProvider.getFdoContext();
    LinkTargetType staticLinkTargetType = null;
    if (alwayslink && !actions.getRuleContext().getRule().getRuleClass().equals("swift_library")) {
      // TODO(b/202252560): Fix for swift_library's implicit output.
      staticLinkTargetType = LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY;
    } else {
      staticLinkTargetType = LinkTargetType.STATIC_LIBRARY;
    }
    List<CcLinkingContext> ccLinkingContexts =
        Sequence.cast(linkingContextsObjects, CcLinkingContext.class, "linking_contexts");
    CcLinkingHelper helper =
        new CcLinkingHelper(
                name,
                CppLinkActionBuilder.newActionConstruction(actions.getRuleContext()),
                getSemantics(Language.CPP),
                featureConfiguration.getFeatureConfiguration(),
                ccToolchainProvider,
                fdoContext,
                thread.getSymbolGenerator(),
                TargetUtils.getExecutionInfo(
                    actions.getRuleContext().getRule(),
                    actions.getRuleContext().isAllowTagsPropagation()))
            .addNonCodeLinkerInputs(
                Sequence.cast(additionalInputs, Artifact.class, "additional_inputs"))
            .setShouldCreateStaticLibraries(!disallowStaticLibraries)
            .addCcLinkingContexts(ccLinkingContexts)
            .setShouldCreateDynamicLibrary(!disallowDynamicLibraries)
            .setStaticLinkType(staticLinkTargetType)
            .setDynamicLinkType(LinkTargetType.NODEPS_DYNAMIC_LIBRARY)
            .emitInterfaceSharedLibraries(true)
            .setLinkedDLLNameSuffix(
                convertFromNoneable(linkedDllNameSuffix, /* defaultValue= */ ""))
            .setIsStampingEnabled(isStampingEnabled)
            .setTestOrTestOnlyTarget(convertFromNoneable(testOnlyTargetObject, false))
            .addLinkopts(Sequence.cast(userLinkFlags, String.class, "user_link_flags"));
    if (!asDict(variablesExtension).isEmpty()) {
      helper.addVariableExtension(new UserVariablesExtension(asDict(variablesExtension)));
    }
    try {
      ImmutableList<LibraryToLink> libraryToLink = ImmutableList.of();
      CcLinkingOutputs ccLinkingOutputs = helper.link(compilationOutputs);
      if (!ccLinkingOutputs.isEmpty()) {
        LibraryToLink rewrappedForAlwaysLink =
            ccLinkingOutputs.getLibraryToLink().toBuilder().setAlwayslink(alwayslink).build();
        ccLinkingOutputs =
            CcLinkingOutputs.builder()
                .setExecutable(ccLinkingOutputs.getExecutable())
                .setLibraryToLink(rewrappedForAlwaysLink)
                .addAllLtoArtifacts(ccLinkingOutputs.getAllLtoArtifacts())
                .build();
        libraryToLink = ImmutableList.of(rewrappedForAlwaysLink);
      }
      CcLinkingContext linkingContext =
          helper.buildCcLinkingContextFromLibrariesToLink(
              libraryToLink, CcCompilationContext.EMPTY);
      return Tuple.of(linkingContext, ccLinkingOutputs);
    } catch (RuleErrorException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
  }

  // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/link/create_linking_context_from_compilation_outputs.bzl)

  @Override
  public CcDebugInfoContext createCcDebugInfoFromStarlark(
      CcCompilationOutputs ccCompilationOutputs, StarlarkThread thread) throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return CcDebugInfoContext.from(ccCompilationOutputs);
  }

  @Override
  public CcDebugInfoContext mergeCcDebugInfoFromStarlark(
      Sequence<?> debugInfos, StarlarkThread thread) throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    return CcDebugInfoContext.merge(
        Sequence.cast(debugInfos, CcDebugInfoContext.class, "debug_infos"));
  }

  public static void checkPrivateStarlarkificationAllowlist(StarlarkThread thread)
      throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
  }

  public static boolean isStarlarkCcCommonCalledFromBuiltins(StarlarkThread thread) {
    Label label =
        ((BazelModuleContext)
                Module.ofInnermostEnclosingStarlarkFunction(thread, 1).getClientData())
            .label();
    return label.getPackageIdentifier().getRepository().getName().equals("_builtins");
  }

  protected static void isCalledFromStarlarkCcCommon(StarlarkThread thread) throws EvalException {
    Label label = BazelModuleContext.ofInnermostBzlOrThrow(thread).label();
    // Allow direct access to cc_common.bzl and to C++ linking code that can't use cc_common.bzl
    // directly without creating a cycle.
    if (!label.getCanonicalForm().endsWith("_builtins//:common/cc/cc_common.bzl")
        && !label.getCanonicalForm().contains("_builtins//:common/cc/link")) {
      throw Starlark.errorf(
          "cc_common_internal can only be used by cc_common.bzl in builtins, "
              + "please use cc_common instead.");
    }
  }

  @StarlarkMethod(
      name = "check_private_api",
      documented = false,
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "allowlist",
            documented = false,
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = Tuple.class),
            }),
      })
  public void checkPrivateApi(Object allowlistObject, StarlarkThread thread) throws EvalException {
    // This method may be called anywhere from builtins, but not outside (because it's not exposed
    // in cc_common.bzl
    BazelModuleContext bazelModuleContext =
        (BazelModuleContext) Module.ofInnermostEnclosingStarlarkFunction(thread, 1).getClientData();
    ImmutableList<BuiltinRestriction.AllowlistEntry> allowlist =
        Sequence.cast(allowlistObject, Tuple.class, "allowlist").stream()
            // TODO(bazel-team): Avoid unchecked indexing and casts on values obtained from
            // Starlark, even though it is allowlisted.
            .map(p -> BuiltinRestriction.allowlistEntry((String) p.get(0), (String) p.get(1)))
            .collect(toImmutableList());
    BuiltinRestriction.failIfModuleOutsideAllowlist(bazelModuleContext, allowlist);
  }

  protected Language parseLanguage(String string) throws EvalException {
    try {
      return Language.valueOf(Ascii.toUpperCase(string.replace('+', 'p')));
    } catch (IllegalArgumentException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
  }

  protected void validateOutputType(String outputType) throws EvalException {
    if (!SUPPORTED_OUTPUT_TYPES.contains(outputType)) {
      throw Starlark.errorf("Output type '%s' is not supported", outputType);
    }
  }

  private static boolean isStampingEnabled(int stamp, BuildConfigurationValue config)
      throws EvalException {
    if (stamp == 0 || stamp == 1 || stamp == -1) {
      return AnalysisUtils.isStampingEnabled(TriState.fromInt(stamp), config);
    }
    throw Starlark.errorf(
        "stamp value %d is not supported, must be 0 (disabled), 1 (enabled), or -1 (default)",
        stamp);
  }

  protected Label getCallerLabel(StarlarkActionFactory actions, String name) throws EvalException {
    try {
      return Label.create(
          actions.getRuleContext().getActionOwner().getLabel().getPackageIdentifier(), name);
    } catch (LabelSyntaxException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public Tuple compile(
      StarlarkActionFactory starlarkActionFactoryApi,
      FeatureConfigurationForStarlark starlarkFeatureConfiguration,
      Info starlarkCcToolchainProvider,
      Sequence<?> sourcesUnchecked, // <Artifact> expected
      Sequence<?> publicHeadersUnchecked, // <Artifact> expected
      Sequence<?> privateHeadersUnchecked, // <Artifact> expected
      Object textualHeadersStarlarkObject,
      Object additionalExportedHeadersObject,
      Object starlarkIncludes,
      Object starlarkLooseIncludes,
      Sequence<?> quoteIncludes, // <String> expected
      Sequence<?> systemIncludes, // <String> expected
      Sequence<?> frameworkIncludes, // <String> expected
      Sequence<?> defines, // <String> expected
      Sequence<?> localDefines, // <String> expected
      String includePrefix,
      String stripIncludePrefix,
      Sequence<?> userCompileFlags, // <String> expected
      Sequence<?> conlyFlags, // <String> expected
      Sequence<?> cxxFlags, // <String> expected
      Sequence<?> ccCompilationContexts, // <CcCompilationContext> expected
      Object implementationCcCompilationContextsObject,
      String name,
      boolean disallowPicOutputs,
      boolean disallowNopicOutputs,
      Sequence<?> additionalIncludeScanningRoots, // <Artifact> expected
      Sequence<?> additionalInputs, // <Artifact> expected
      Object moduleMapNoneable,
      Object additionalModuleMapsNoneable,
      Object propagateModuleMapToCompileActionObject,
      Object doNotGenerateModuleMapObject,
      Object codeCoverageEnabledObject,
      Object hdrsCheckingModeObject,
      Object variablesExtension,
      Object languageObject,
      Object purposeObject,
      Object coptsFilterObject,
      Object separateModuleHeadersObject,
      Sequence<?> moduleInterfacesUnchecked, // <Artifact> expected
      Object nonCompilationAdditionalInputsObject,
      StarlarkThread thread)
      throws EvalException, InterruptedException {
    isCalledFromStarlarkCcCommon(thread);
    getSemantics()
        .validateStarlarkCompileApiCall(
            starlarkActionFactoryApi,
            thread,
            includePrefix,
            stripIncludePrefix,
            additionalIncludeScanningRoots);

    List<Artifact> includeScanningRoots =
        getAdditionalIncludeScanningRoots(additionalIncludeScanningRoots, thread);

    StarlarkActionFactory actions = starlarkActionFactoryApi;
    CcToolchainProvider ccToolchainProvider =
        CcToolchainProvider.PROVIDER.wrapOrThrowEvalException(starlarkCcToolchainProvider);

    CppModuleMap moduleMap = convertFromNoneable(moduleMapNoneable, /* defaultValue= */ null);
    ImmutableList<CppModuleMap> additionalModuleMaps =
        asClassImmutableList(additionalModuleMapsNoneable);

    String coptsFilterRegex = convertFromNoneable(coptsFilterObject, /* defaultValue= */ null);
    CoptsFilter coptsFilter = null;
    if (Strings.isNullOrEmpty(coptsFilterRegex)) {
      coptsFilter = CoptsFilter.alwaysPasses();
    } else {
      try {
        coptsFilter = CoptsFilter.fromRegex(Pattern.compile(coptsFilterRegex));
      } catch (PatternSyntaxException e) {
        throw Starlark.errorf(
            "invalid regular expression '%s': %s", coptsFilterRegex, e.getMessage());
      }
    }

    Object textualHeadersObject =
        asClassImmutableListOrNestedSet(
            textualHeadersStarlarkObject, Artifact.class, "textual_headers");

    String languageString = convertFromNoneable(languageObject, Language.CPP.getRepresentation());
    Language language = parseLanguage(languageString);

    ImmutableList<String> additionalExportedHeaders =
        asClassImmutableList(additionalExportedHeadersObject);
    ImmutableList<Artifact> nonCompilationAdditionalInputs =
        asClassImmutableList(nonCompilationAdditionalInputsObject);
    boolean propagateModuleMapToCompileAction =
        convertFromNoneable(propagateModuleMapToCompileActionObject, /* defaultValue= */ true);
    boolean doNotGenerateModuleMap =
        convertFromNoneable(doNotGenerateModuleMapObject, /* defaultValue= */ false);
    boolean codeCoverageEnabled =
        convertFromNoneable(codeCoverageEnabledObject, /* defaultValue= */ false);
    String purpose = convertFromNoneable(purposeObject, null);
    ImmutableList<CcCompilationContext> implementationContexts =
        asClassImmutableList(implementationCcCompilationContextsObject);

    FeatureConfigurationForStarlark featureConfiguration =
        convertFromNoneable(starlarkFeatureConfiguration, null);
    Label label = getCallerLabel(actions, name);
    FdoContext fdoContext = ccToolchainProvider.getFdoContext();

    if (disallowNopicOutputs && disallowPicOutputs) {
      throw Starlark.errorf("Either PIC or no PIC actions have to be created.");
    }

    SourceCategory sourceCategory =
        (language == Language.CPP) ? SourceCategory.CC : SourceCategory.CC_AND_OBJC;
    CcCommon common = new CcCommon(actions.getRuleContext());
    BuildConfigurationValue configuration = actions.getRuleContext().getConfiguration();
    CcCompilationHelper helper =
        new CcCompilationHelper(
            actions.getRuleContext(),
            label,
            getSemantics(language),
            featureConfiguration.getFeatureConfiguration(),
            sourceCategory,
            ccToolchainProvider,
            fdoContext,
            actions.getRuleContext().getConfiguration(),
            TargetUtils.getExecutionInfo(
                actions.getRuleContext().getRule(),
                actions.getRuleContext().isAllowTagsPropagation()),
            /* shouldProcessHeaders= */ CcToolchainProvider.shouldProcessHeaders(
                featureConfiguration.getFeatureConfiguration(),
                configuration.getFragment(CppConfiguration.class)));
    boolean tuple =
        (!sourcesUnchecked.isEmpty() && sourcesUnchecked.get(0) instanceof Tuple)
            || (!moduleInterfacesUnchecked.isEmpty()
                && moduleInterfacesUnchecked.get(0) instanceof Tuple)
            || (!publicHeadersUnchecked.isEmpty() && publicHeadersUnchecked.get(0) instanceof Tuple)
            || (!privateHeadersUnchecked.isEmpty()
                && privateHeadersUnchecked.get(0) instanceof Tuple);
    if (tuple) {
      ImmutableList<Pair<Artifact, Label>> sources = convertSequenceTupleToPair(sourcesUnchecked);
      ImmutableList<Pair<Artifact, Label>> moduleInterfaces =
          convertSequenceTupleToPair(moduleInterfacesUnchecked);
      ImmutableList<Pair<Artifact, Label>> publicHeaders =
          convertSequenceTupleToPair(publicHeadersUnchecked);
      ImmutableList<Pair<Artifact, Label>> privateHeaders =
          convertSequenceTupleToPair(privateHeadersUnchecked);
      helper
          .addPublicHeaders(publicHeaders)
          .addPrivateHeaders(privateHeaders)
          .addSources(sources)
          .addModuleInterfaceSources(moduleInterfaces);
    } else {
      List<Artifact> sources = Sequence.cast(sourcesUnchecked, Artifact.class, "srcs");
      List<Artifact> moduleInterfaces =
          Sequence.cast(moduleInterfacesUnchecked, Artifact.class, "module_interfaces");
      List<Artifact> publicHeaders =
          Sequence.cast(publicHeadersUnchecked, Artifact.class, "public_hdrs");
      List<Artifact> privateHeaders =
          Sequence.cast(privateHeadersUnchecked, Artifact.class, "private_hdrs");
      helper
          .addPublicHeaders(publicHeaders)
          .addPrivateHeaders(privateHeaders)
          .addSources(sources)
          .addModuleInterfaceSources(moduleInterfaces);
    }

    List<String> includes =
        starlarkIncludes instanceof Depset
            ? Depset.cast(starlarkIncludes, String.class, "includes").toList()
            : Sequence.cast(starlarkIncludes, String.class, "includes");
    helper
        .addCcCompilationContexts(
            Sequence.cast(
                ccCompilationContexts, CcCompilationContext.class, "compilation_contexts"))
        .addImplementationDepsCcCompilationContexts(implementationContexts)
        .addIncludeDirs(includes.stream().map(PathFragment::create).collect(toImmutableList()))
        .addQuoteIncludeDirs(
            Sequence.cast(quoteIncludes, String.class, "quote_includes").stream()
                .map(PathFragment::create)
                .collect(toImmutableList()))
        .addSystemIncludeDirs(
            Sequence.cast(systemIncludes, String.class, "system_includes").stream()
                .map(PathFragment::create)
                .collect(toImmutableList()))
        .addFrameworkIncludeDirs(
            Sequence.cast(frameworkIncludes, String.class, "framework_includes").stream()
                .map(PathFragment::create)
                .collect(toImmutableList()))
        .addDefines(Sequence.cast(defines, String.class, "defines"))
        .addNonTransitiveDefines(Sequence.cast(localDefines, String.class, "local_defines"))
        .setCopts(
            ImmutableList.copyOf(
                Sequence.cast(userCompileFlags, String.class, "user_compile_flags")))
        .setConlyopts(ImmutableList.copyOf(Sequence.cast(conlyFlags, String.class, "conly_flags")))
        .setCxxopts(ImmutableList.copyOf(Sequence.cast(cxxFlags, String.class, "cxx_flags")))
        .addAdditionalCompilationInputs(
            Sequence.cast(additionalInputs, Artifact.class, "additional_inputs"))
        .addAdditionalInputs(nonCompilationAdditionalInputs)
        .addAdditionalIncludeScanningRoots(includeScanningRoots)
        .setPurpose(common.getPurpose(getSemantics(language)))
        .addAdditionalExportedHeaders(
            additionalExportedHeaders.stream().map(PathFragment::create).collect(toImmutableList()))
        .setPropagateModuleMapToCompileAction(propagateModuleMapToCompileAction)
        .setCodeCoverageEnabled(codeCoverageEnabled);

    if (textualHeadersObject instanceof NestedSet) {
      helper.addPublicTextualHeaders((NestedSet<Artifact>) textualHeadersObject);
    } else {
      helper.addPublicTextualHeaders((List<Artifact>) textualHeadersObject);
    }
    if (doNotGenerateModuleMap) {
      helper.doNotGenerateModuleMap();
    }
    if (moduleMap != null) {
      helper.setCppModuleMap(moduleMap);
    }
    if (coptsFilter != null) {
      helper.setCoptsFilter(coptsFilter);
    }
    for (CppModuleMap additionalModuleMap : additionalModuleMaps) {
      helper.registerAdditionalModuleMap(additionalModuleMap);
    }
    if (disallowNopicOutputs) {
      helper.setGenerateNoPicAction(false);
    }
    if (disallowPicOutputs) {
      helper.setGeneratePicAction(false);
      helper.setGenerateNoPicAction(true);
    }
    if (!Strings.isNullOrEmpty(includePrefix)) {
      helper.setIncludePrefix(includePrefix);
    }
    if (!Strings.isNullOrEmpty(stripIncludePrefix)) {
      helper.setStripIncludePrefix(stripIncludePrefix);
    }
    if (!asDict(variablesExtension).isEmpty()) {
      helper.addVariableExtension(new UserVariablesExtension(asDict(variablesExtension)));
    }
    if (purpose != null) {
      helper.setPurpose(purpose);
    }
    ImmutableList<Artifact> separateModuleHeaders =
        asClassImmutableList(separateModuleHeadersObject);
    helper.addSeparateModuleHeaders(separateModuleHeaders);

    try {
      RuleContext ruleContext = actions.getRuleContext();
      CompilationInfo compilationInfo = helper.compile(ruleContext);
      return Tuple.of(
          compilationInfo.getCcCompilationContext(), compilationInfo.getCcCompilationOutputs());
    } catch (RuleErrorException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
  }

  private List<Artifact> getAdditionalIncludeScanningRoots(
      Sequence<?> additionalIncludeScanningRoots, StarlarkThread thread) throws EvalException {
    if (!additionalIncludeScanningRoots.isEmpty()) {
      BazelModuleContext bazelModuleContext =
          (BazelModuleContext)
              Module.ofInnermostEnclosingStarlarkFunction(thread, 1).getClientData();
      BuiltinRestriction.failIfModuleOutsideAllowlist(
          bazelModuleContext, ImmutableList.of(MATCH_CLIF_ALLOWLISTED_LOCATION));
    }
    return Sequence.cast(
        additionalIncludeScanningRoots, Artifact.class, "additional_include_scanning_roots");
  }

  // LINT.IfChange
  @Override
  public CcLinkingOutputs link(
      StarlarkActionFactory actions,
      String name,
      FeatureConfigurationForStarlark starlarkFeatureConfiguration,
      Info starlarkCcToolchainProvider,
      String languageString,
      String outputType,
      boolean linkDepsStatically,
      Object compilationOutputsObject,
      Sequence<?> linkingContexts,
      Sequence<?> userLinkFlags,
      StarlarkInt stamp,
      Object additionalInputs,
      Object linkerOutputsObject,
      Object variablesExtension,
      Object useTestOnlyFlags,
      Object neverLinkObject,
      Object testOnlyTargetObject,
      Object nativeDepsObject,
      Object wholeArchiveObject,
      Object additionalLinkstampDefines,
      Object alwaysLinkObject,
      Object onlyForDynamicLibsObject,
      Object linkedArtifactNameSuffixObject,
      Object mainOutputObject,
      Object useShareableArtifactFactory,
      Object buildConfig,
      Object emitInterfaceSharedLibrary,
      StarlarkThread thread)
      throws InterruptedException, EvalException {
    // TODO(bazel-team): Rename always_link to alwayslink before delisting. Also it looks like the
    //  suffix parameter can be removed since we can use `name` for the same thing.
    isCalledFromStarlarkCcCommon(thread);
    Language language = parseLanguage(languageString);
    validateOutputType(outputType);
    boolean isStampingEnabled =
        isStampingEnabled(stamp.toInt("stamp"), actions.getRuleContext().getConfiguration());
    CcToolchainProvider ccToolchainProvider =
        CcToolchainProvider.PROVIDER.wrapOrThrowEvalException(starlarkCcToolchainProvider);
    FeatureConfigurationForStarlark featureConfiguration =
        convertFromNoneable(starlarkFeatureConfiguration, null);
    Artifact mainOutput = convertFromNoneable(mainOutputObject, null);
    FdoContext fdoContext = ccToolchainProvider.getFdoContext();
    LinkTargetType dynamicLinkTargetType = null;
    LinkTargetType staticLinkTargetType = null;
    if (language == Language.CPP) {
      switch (outputType) {
        case "executable":
          dynamicLinkTargetType = LinkTargetType.EXECUTABLE;
          break;
        case "dynamic_library":
          dynamicLinkTargetType = LinkTargetType.DYNAMIC_LIBRARY;
          break;
        case "archive":
          throw Starlark.errorf("Language 'c++' does not support 'archive'");
        default:
          // fall through
      }
    } else if (language == Language.OBJC && outputType.equals("executable")) {
      dynamicLinkTargetType = LinkTargetType.OBJC_EXECUTABLE;
    } else if (language == Language.OBJCPP && outputType.equals("executable")) {
      dynamicLinkTargetType = LinkTargetType.OBJC_EXECUTABLE;
    } else if (language == Language.OBJC && outputType.equals("archive")) {
      staticLinkTargetType = LinkTargetType.OBJC_FULLY_LINKED_ARCHIVE;
    } else {
      throw Starlark.errorf("Language '%s' does not support %s", language, outputType);
    }
    NestedSet<Artifact> additionalInputsSet =
        additionalInputs instanceof Depset
            ? Depset.cast(additionalInputs, Artifact.class, "additional_inputs")
            : NestedSetBuilder.<Artifact>compileOrder()
                .addAll(Sequence.cast(additionalInputs, Artifact.class, "additional_inputs"))
                .build();
    FeatureConfiguration actualFeatureConfiguration =
        featureConfiguration.getFeatureConfiguration();
    BuildConfigurationValue buildConfiguration =
        convertFromNoneable(buildConfig, actions.getRuleContext().getConfiguration());
    ImmutableList<Artifact> linkerOutputs = asClassImmutableList(linkerOutputsObject);
    boolean shareableArtifacts = convertFromNoneable(useShareableArtifactFactory, false);

    CcLinkingHelper helper =
        new CcLinkingHelper(
                name,
                CppLinkActionBuilder.newActionConstruction(
                    actions.getRuleContext(), buildConfiguration, shareableArtifacts),
                getSemantics(language),
                actualFeatureConfiguration,
                ccToolchainProvider,
                fdoContext,
                thread.getSymbolGenerator(),
                TargetUtils.getExecutionInfo(
                    actions.getRuleContext().getRule(),
                    actions.getRuleContext().isAllowTagsPropagation()))
            .setLinkingMode(linkDepsStatically ? LinkingMode.STATIC : LinkingMode.DYNAMIC)
            .setIsStampingEnabled(isStampingEnabled)
            .addTransitiveAdditionalLinkerInputs(additionalInputsSet)
            .addCcLinkingContexts(
                Sequence.cast(linkingContexts, CcLinkingContext.class, "linking_contexts"))
            .addLinkopts(Sequence.cast(userLinkFlags, String.class, "user_link_flags"))
            .setLinkedArtifactNameSuffix(convertFromNoneable(linkedArtifactNameSuffixObject, ""))
            .setNeverLink(convertFromNoneable(neverLinkObject, false))
            // setAlwayslink may be deprecated but we're trying to replicate CcBinary as closely as
            // possible for the moment.
            .setAlwayslink(convertFromNoneable(alwaysLinkObject, false))
            .setTestOrTestOnlyTarget(convertFromNoneable(testOnlyTargetObject, false))
            .setNativeDeps(convertFromNoneable(nativeDepsObject, false))
            .setWholeArchive(convertFromNoneable(wholeArchiveObject, false))
            .addAdditionalLinkstampDefines(asStringImmutableList(additionalLinkstampDefines))
            .setWillOnlyBeLinkedIntoDynamicLibraries(
                convertFromNoneable(onlyForDynamicLibsObject, false))
            .emitInterfaceSharedLibraries(
                dynamicLinkTargetType == LinkTargetType.DYNAMIC_LIBRARY
                    && (convertFromNoneable(emitInterfaceSharedLibrary, false)
                        || actualFeatureConfiguration.isEnabled(CppRuleClasses.TARGETS_WINDOWS))
                    && CppHelper.useInterfaceSharedLibraries(
                        ccToolchainProvider.getCppConfiguration(), actualFeatureConfiguration))
            .setLinkerOutputArtifact(convertFromNoneable(mainOutput, null))
            .setUseTestOnlyFlags(convertFromNoneable(useTestOnlyFlags, false))
            .addLinkerOutputs(linkerOutputs);
    if (staticLinkTargetType != null) {
      helper.setShouldCreateDynamicLibrary(false).setStaticLinkType(staticLinkTargetType);
    } else {
      helper.setShouldCreateStaticLibraries(false).setDynamicLinkType(dynamicLinkTargetType);
    }
    if (!asDict(variablesExtension).isEmpty()) {
      helper.addVariableExtension(new UserVariablesExtension(asDict(variablesExtension)));
    }

    CcCompilationOutputs compilationOutputs =
        convertFromNoneable(compilationOutputsObject, /* defaultValue= */ null);
    try {
      return helper.link(
          compilationOutputs != null ? compilationOutputs : CcCompilationOutputs.EMPTY);
    } catch (RuleErrorException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
  }

  // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/link/link.bzl)

  @Override
  @SuppressWarnings("unchecked")
  public LtoCompilationContext createLtoCompilationContextFromStarlark(
      Object objectsObject, StarlarkThread thread) throws EvalException {
    checkPrivateStarlarkificationAllowlist(thread);
    Dict<Artifact, Tuple> objects =
        Dict.cast(objectsObject, Artifact.class, Tuple.class, "objects");
    LtoCompilationContext.Builder builder = new LtoCompilationContext.Builder();
    for (Artifact k : objects) {
      Tuple t = objects.get(k);
      if (t.size() != 2) {
        throw new EvalException(
            "wrong length tuple for an (index_file, copts), want 2, got " + t.size());
      }
      Object minimizedBitcode = t.get(0);
      if (!(minimizedBitcode instanceof Artifact)) {
        throw new EvalException("expected Artifact for minimized bitcode, got something else");
      }
      Object copts = t.get(1);
      if (!(copts instanceof StarlarkList)) {
        throw new EvalException("expected list for copts, got something else");
      }
      builder.addBitcodeFile(
          k, (Artifact) minimizedBitcode, ImmutableList.copyOf((StarlarkList<String>) copts));
    }
    return builder.build();
  }

  @Override
  public CcCompilationOutputs createCompilationOutputsFromStarlark(
      Object objectsObject,
      Object picObjectsObject,
      Object ltoCompilationContextObject,
      Object dwoObjectsObject,
      Object picDwoObjectsObject,
      StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    CcCompilationOutputs.Builder ccCompilationOutputsBuilder = CcCompilationOutputs.builder();
    NestedSet<Artifact> objects = convertToNestedSet(objectsObject, Artifact.class, "objects");
    validateExtensions(
        "objects",
        objects.toList(),
        Link.OBJECT_FILETYPES,
        Link.OBJECT_FILETYPES,
        /* allowAnyTreeArtifacts= */ true);
    LtoCompilationContext ltoCompilationContext =
        convertFromNoneable(ltoCompilationContextObject, null);
    NestedSet<Artifact> picObjects =
        convertToNestedSet(picObjectsObject, Artifact.class, "pic_objects");
    validateExtensions(
        "pic_objects",
        picObjects.toList(),
        Link.OBJECT_FILETYPES,
        Link.OBJECT_FILETYPES,
        /* allowAnyTreeArtifacts= */ true);
    ccCompilationOutputsBuilder.addObjectFiles(objects.toList());
    ccCompilationOutputsBuilder.addPicObjectFiles(picObjects.toList());
    if (ltoCompilationContext != null) {
      ccCompilationOutputsBuilder.addLtoCompilationContext(ltoCompilationContext);
    }
    NestedSet<Artifact> dwoObjects =
        convertToNestedSet(dwoObjectsObject, Artifact.class, "dwo_objects");
    for (Artifact dwoFile : dwoObjects.toList()) {
      ccCompilationOutputsBuilder.addDwoFile(dwoFile);
    }
    NestedSet<Artifact> picDwoObjects =
        convertToNestedSet(picDwoObjectsObject, Artifact.class, "pic_dwo_objects");
    for (Artifact picDwoFile : picDwoObjects.toList()) {
      ccCompilationOutputsBuilder.addPicDwoFile(picDwoFile);
    }
    return ccCompilationOutputsBuilder.build();
  }

  private static void validateExtensions(
      String paramName,
      List<Artifact> files,
      FileTypeSet validFileTypeSet,
      FileTypeSet fileTypeForErrorMessage,
      boolean allowAnyTreeArtifacts)
      throws EvalException {
    for (Artifact file : files) {
      if (allowAnyTreeArtifacts && file.isTreeArtifact()) {
        continue;
      }
      if (!validFileTypeSet.matches(file.getFilename())) {
        throw Starlark.errorf(
            "'%s' has wrong extension. The list of possible extensions for '%s' is: %s",
            file.getExecPathString(),
            paramName,
            Joiner.on(",").join(fileTypeForErrorMessage.getExtensions()));
      }
    }
  }

  @StarlarkMethod(
      name = "register_linkstamp_compile_action",
      documented = false,
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "actions",
            positional = false,
            named = true,
            doc = "<code>actions</code> object."),
        @Param(
            name = "cc_toolchain",
            doc = "<code>CcToolchainInfo</code> provider to be used.",
            positional = false,
            named = true),
        @Param(
            name = "feature_configuration",
            doc = "<code>feature_configuration</code> to be queried.",
            positional = false,
            named = true),
        @Param(name = "source_file", documented = false, positional = false, named = true),
        @Param(name = "output_file", documented = false, positional = false, named = true),
        @Param(name = "compilation_inputs", documented = false, positional = false, named = true),
        @Param(
            name = "inputs_for_validation",
            documented = false,
            positional = false,
            named = true),
        @Param(name = "label_replacement", documented = false, positional = false, named = true),
        @Param(name = "output_replacement", documented = false, positional = false, named = true),
        @Param(
            name = "needs_pic",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "False"),
        @Param(
            name = "stamping",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "None"),
        @Param(
            name = "additional_linkstamp_defines",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "unbound"),
      })
  public void registerLinkstampCompileAction(
      StarlarkActionFactory starlarkActionFactoryApi,
      Info ccToolchainInfo,
      FeatureConfigurationForStarlark featureConfigurationForStarlark,
      Artifact sourceFile,
      Artifact outputFile,
      Depset compilationInputs,
      Depset inputsForValidation,
      String labelReplacement,
      String outputReplacement,
      boolean needsPic,
      Object stampingObject,
      Object additionalLinkstampDefines,
      StarlarkThread thread)
      throws EvalException, InterruptedException, TypeException, RuleErrorException {
    isCalledFromStarlarkCcCommon(thread);
    RuleContext ruleContext = starlarkActionFactoryApi.getRuleContext();
    boolean stamping =
        stampingObject instanceof Boolean
            ? (Boolean) stampingObject
            : AnalysisUtils.isStampingEnabled(ruleContext, ruleContext.getConfiguration());
    CcToolchainProvider ccToolchain =
        CcToolchainProvider.PROVIDER.wrapOrThrowEvalException(ccToolchainInfo);
    CppConfiguration cppConfiguration = ccToolchain.getCppConfiguration();
    if (AnalysisUtils.isStampingEnabled(ruleContext, ruleContext.getConfiguration())) {
      // Makes the target depend on BUILD_INFO_KEY, which helps to discover stamped targets
      // See b/326620485 for more details.
      var unused =
          starlarkActionFactoryApi
              .getRuleContext()
              .getAnalysisEnvironment()
              .getVolatileWorkspaceStatusArtifact();
    }
    starlarkActionFactoryApi.registerAction(
        CppLinkstampCompileHelper.createLinkstampCompileAction(
            CppLinkActionBuilder.newActionConstruction(ruleContext),
            sourceFile,
            outputFile,
            compilationInputs.getSet(Artifact.class),
            /* nonCodeInputs= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            inputsForValidation.getSet(Artifact.class),
            stamping
                ? ccToolchain
                    .getCcBuildInfoTranslator()
                    .getOutputGroup("non_redacted_build_info_files")
                    .toList()
                : ccToolchain
                    .getCcBuildInfoTranslator()
                    .getOutputGroup("redacted_build_info_files")
                    .toList(),
            asStringImmutableList(additionalLinkstampDefines),
            ccToolchain,
            ruleContext.getConfiguration().isCodeCoverageEnabled(),
            CppHelper.getFdoBuildStamp(
                cppConfiguration,
                ccToolchain.getFdoContext(),
                featureConfigurationForStarlark.getFeatureConfiguration()),
            featureConfigurationForStarlark.getFeatureConfiguration(),
            needsPic,
            labelReplacement,
            outputReplacement,
            getSemantics()));
  }

  @StarlarkMethod(
      name = "create_extra_link_time_library",
      documented = false,
      doc =
          "Creates a custom ExtraLinkTimeLibrary object. Extra keyword arguments are passed to the"
              + " provided build function when build_libraries is called. Arguments that are"
              + " depsets will be added transitively when these are combined via"
              + " cc_common.merge_cc_infos. For arguments that are not depsets, only one copy will"
              + " be maintained.",
      parameters = {
        @Param(name = "build_library_func", positional = false, named = true),
      },
      extraKeywords = @Param(name = "data"),
      useStarlarkThread = true)
  public ExtraLinkTimeLibraryApi createExtraLinkTimeLibrary(
      StarlarkCallable buildLibraryFunc, Dict<String, Object> dataSetsMap, StarlarkThread thread)
      throws EvalException {
    isCalledFromStarlarkCcCommon(thread);
    checkPrivateStarlarkificationAllowlist(thread);
    boolean nonGlobalFunc = false;
    if (buildLibraryFunc instanceof StarlarkFunction fn) {
      if (fn.getModule().getGlobal(fn.getName()) != fn) {
        nonGlobalFunc = true;
      }
    }
    if (nonGlobalFunc) {
      throw Starlark.errorf("Passed function must be top-level functions.");
    }
    return new StarlarkDefinedLinkTimeLibrary(buildLibraryFunc, ImmutableMap.copyOf(dataSetsMap));
  }

  private ImmutableList<Pair<Artifact, Label>> convertSequenceTupleToPair(Sequence<?> sequenceTuple)
      throws EvalException {
    return Sequence.cast(sequenceTuple, Tuple.class, "files").stream()
        .map(p -> Pair.of((Artifact) p.get(0), (Label) p.get(1)))
        .collect(toImmutableList());
  }
}
