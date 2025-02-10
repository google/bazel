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

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.ImplicitOutputsFunction.fromTemplates;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ALWAYS_LINK_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ALWAYS_LINK_PIC_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ARCHIVE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ASSEMBLER;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.CPP_HEADER;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.CPP_SOURCE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.C_SOURCE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.INTERFACE_SHARED_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.OBJECT_FILE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.PIC_ARCHIVE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.PIC_OBJECT_FILE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.SHARED_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.VERSIONED_SHARED_LIBRARY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.packages.Attribute.LabelLateBoundDefault;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.OsUtils;

/** Rule class definitions for C++ rules. */
public class CppRuleClasses {
  public static final LabelLateBoundDefault<?> DEFAULT_MALLOC =
      LabelLateBoundDefault.fromTargetConfiguration(
          CppConfiguration.class, null, (rule, attributes, cppConfig) -> cppConfig.customMalloc());

  public static ToolchainTypeRequirement ccToolchainTypeRequirement(RuleDefinitionEnvironment env) {
    return ToolchainTypeRequirement.builder(env.getToolsLabel(CppHelper.TOOLCHAIN_TYPE_LABEL))
        .mandatory(false)
        .build();
  }

  // Artifacts of these types are discarded from the 'hdrs' attribute in cc rules
  static final FileTypeSet DISALLOWED_HDRS_FILES =
      FileTypeSet.of(
          ARCHIVE,
          PIC_ARCHIVE,
          ALWAYS_LINK_LIBRARY,
          ALWAYS_LINK_PIC_LIBRARY,
          SHARED_LIBRARY,
          INTERFACE_SHARED_LIBRARY,
          VERSIONED_SHARED_LIBRARY,
          OBJECT_FILE,
          PIC_OBJECT_FILE);

  /**
   * The set of instrumented source file types; keep this in sync with the list above. Note that
   * extension-less header files cannot currently be declared, so we cannot collect coverage for
   * those.
   */
  static final InstrumentationSpec INSTRUMENTATION_SPEC =
      new InstrumentationSpec(
              FileTypeSet.of(
                  CPP_SOURCE, C_SOURCE, CPP_HEADER, ASSEMBLER_WITH_C_PREPROCESSOR, ASSEMBLER))
          .withSourceAttributes("srcs", "hdrs")
          .withDependencyAttributes("implementation_deps", "deps", "data");

  /** Implicit outputs for cc_binary rules. */
  public static final SafeImplicitOutputsFunction CC_BINARY_STRIPPED =
      fromTemplates("%{name}.stripped" + OsUtils.executableExtension());

  // Used for requesting dwp "debug packages".
  public static final SafeImplicitOutputsFunction CC_BINARY_DEBUG_PACKAGE =
      fromTemplates("%{name}.dwp");

  /** A string constant for the Objective-C language feature. */
  public static final String LANG_OBJC = "lang_objc";

  /** Name of the feature that will be exempt from flag filtering when nocopts are used */
  public static final String UNFILTERED_COMPILE_FLAGS_FEATURE_NAME = "unfiltered_compile_flags";

  /** A string constant for the parse_headers feature. */
  public static final String PARSE_HEADERS = "parse_headers";

  /**
   * A string constant for the module_maps feature; this is a precondition to the layering_check and
   * header_modules features.
   */
  public static final String MODULE_MAPS = "module_maps";

  /** A string constant for the cpp_modules feature. */
  public static final String CPP_MODULES = "cpp_modules";

  /**
   * A string constant for the random_seed feature. This is used by gcc and Clangfor the
   * randomization of symbol names that are in the anonymous namespace but have external linkage.
   */
  public static final String RANDOM_SEED = "random_seed";

  /** A string constant for the dependency_file feature. This feature generates the .d file. */
  public static final String DEPENDENCY_FILE = "dependency_file";

  /**
   * A string constant for the serialized_diagnostics_file feature. This feature generates the .dia
   * file.
   */
  public static final String SERIALIZED_DIAGNOSTICS_FILE = "serialized_diagnostics_file";

  /** A string constant for the module_map_home_cwd feature. */
  public static final String MODULE_MAP_HOME_CWD = "module_map_home_cwd";

  /**
   * A string constant for the module_map_without_extern_module feature.
   *
   * <p>This features is a transitional feature; enabling it means that generated module maps will
   * not have "extern module" declarations inside them; instead, the module maps need to be passed
   * via the dependent_module_map_files build variable.
   *
   * <p>This variable is phrased negatively to aid the roll-out: currently, the default is that
   * "extern module" declarations are generated.
   */
  public static final String MODULE_MAP_WITHOUT_EXTERN_MODULE = "module_map_without_extern_module";

  /** A string constant for the layering_check feature. */
  public static final String LAYERING_CHECK = "layering_check";

  /**
   * A string constant that signifies whether the crosstool validates layering_check in
   * textual_hdrs. We use the compiler's definition of textual_hdrs also for all regular but
   * non-modular headers. This in turn means that the compiler can check layering in headers of the
   * same target, irrespective the PARSE_HEADERS feature and we can elide separate header actions.
   */
  public static final String VALIDATES_LAYERING_CHECK_IN_TEXTUAL_HDRS =
      "validates_layering_check_in_textual_hdrs";

  /** A string constant for the header_modules feature. */
  public static final String HEADER_MODULES = "header_modules";

  /** A string constant for the header_modules_compile feature. */
  public static final String HEADER_MODULE_COMPILE = "header_module_compile";

  /** A string constant for the header_module_codegen feature. */
  public static final String HEADER_MODULE_CODEGEN = "header_module_codegen";

  /** A string constant for the compile_all_modules feature. */
  public static final String COMPILE_ALL_MODULES = "compile_all_modules";

  /** A string constant for the exclude_private_headers_in_module_maps feature. */
  public static final String EXCLUDE_PRIVATE_HEADERS_IN_MODULE_MAPS =
      "exclude_private_headers_in_module_maps";

  /**
   * A string constant for the use_header_modules feature.
   *
   * <p>This feature is only used during rollout; we expect to default enable this once we have
   * verified that module-enabled compilation is stable enough.
   */
  public static final String USE_HEADER_MODULES = "use_header_modules";

  /**
   * A string constant for the generate_submodules feature.
   *
   * <p>This feature is only used temporarily to make the switch to using submodules easier. With
   * submodules, each header of a cc_library is placed into a submodule of the module generated for
   * the appropriate target. As this influences the layering_check semantics and needs to be synced
   * with a clang release, we want to be able to switch back and forth easily.
   */
  public static final String GENERATE_SUBMODULES = "generate_submodules";

  /**
   * A string constant for the only_doth_headers_in_module_maps.
   *
   * <p>This feature filters any headers without a ".h" suffix from generated module maps.
   */
  public static final String ONLY_DOTH_HEADERS_IN_MODULE_MAPS = "only_doth_headers_in_module_maps";

  /**
   * A string constant for the no_legacy_features feature.
   *
   * <p>If this feature is enabled, Bazel will not extend the crosstool configuration with the
   * default legacy feature set.
   */
  public static final String NO_LEGACY_FEATURES = "no_legacy_features";

  /**
   * A string constant for the legacy_compile_flags feature. If this feature is present in the
   * toolchain, and the toolchain doesn't specify no_legacy_features, bazel will move
   * legacy_compile_flags before other features from {@link CppActionConfigs}.
   */
  public static final String LEGACY_COMPILE_FLAGS = "legacy_compile_flags";

  /**
   * A string constant for the default_compile_flags feature. If this feature is present in the
   * toolchain, and the toolchain doesn't specify no_legacy_features, bazel will move
   * default_compile_flags before other features from {@link CppActionConfigs}.
   */
  public static final String DEFAULT_COMPILE_FLAGS = "default_compile_flags";

  /** A string constant for the feature that makes us build per-object debug info files. */
  public static final String PER_OBJECT_DEBUG_INFO = "per_object_debug_info";

  /**
   * A string constant for the PIC feature.
   *
   * <p>If this feature is active (currently it cannot be switched off) and PIC compilation is
   * requested, the "pic" build variable will be defined with an empty string as its value.
   */
  public static final String PIC = "pic";

  /** A string constant for a feature that indicates that the toolchain can produce PIC objects. */
  public static final String SUPPORTS_PIC = "supports_pic";

  /**
   * A string constant for a feature that indicates that PIC compiles are preferred for binaries
   * even in optimized builds. For configurations that use dynamic linking for tests, this provides
   * increases sharing of artifacts between tests and binaries at the cost of performance overhead.
   */
  public static final String PREFER_PIC_FOR_OPT_BINARIES = "prefer_pic_for_opt_binaries";

  /** A string constant for the feature the represents preprocessor defines. */
  public static final String PREPROCESSOR_DEFINES = "preprocessor_defines";

  /** A string constant for the includes feature. */
  public static final String INCLUDES = "includes";

  /** A string constant for the include_paths feature. */
  public static final String INCLUDE_PATHS = "include_paths";

  /** A string constant for the external_include_paths feature. */
  public static final String EXTERNAL_INCLUDE_PATHS = "external_include_paths";

  /** A string constant for the feature signalling static linking mode. */
  public static final String STATIC_LINKING_MODE = "static_linking_mode";

  /** A string constant for the feature signalling dynamic linking mode. */
  public static final String DYNAMIC_LINKING_MODE = "dynamic_linking_mode";

  /** A string constant for the ThinLTO feature. */
  public static final String THIN_LTO = "thin_lto";

  /** A string constant for the LTO indexing bitcode feature. */
  public static final String NO_USE_LTO_INDEXING_BITCODE_FILE = "no_use_lto_indexing_bitcode_file";

  /** A string constant for the LTO separate native object directory feature. */
  public static final String USE_LTO_NATIVE_OBJECT_DIRECTORY = "use_lto_native_object_directory";

  /*
   * A string constant for allowing implicit ThinLTO enablement for AFDO.
   */
  public static final String AUTOFDO_IMPLICIT_THINLTO = "autofdo_implicit_thinlto";

  /*
   * A string constant for enabling ThinLTO for AFDO implicitly.
   */
  public static final String ENABLE_AFDO_THINLTO = "enable_afdo_thinlto";

  /*
   * A string constant for enabling ThinLTO for FDO implicitly.
   */
  public static final String ENABLE_FDO_THINLTO = "enable_fdo_thinlto";

  /*
   * A string constant for enabling ThinLTO for XFDO implicitly.
   */
  public static final String ENABLE_XFDO_THINLTO = "enable_xbinaryfdo_thinlto";

  /** A string constant for the split functions feature. */
  public static final String SPLIT_FUNCTIONS = "split_functions";

  /** A string constant for enabling split functions for FDO implicitly. */
  public static final String ENABLE_FDO_SPLIT_FUNCTIONS = "enable_fdo_split_functions";

  /** A string constant for the fsafdo feature. */
  public static final String FSAFDO = "fsafdo";

  /** A string constant for enabling fsafdo for AutoFDO implicitly. */
  public static final String ENABLE_FSAFDO = "enable_fsafdo";

  /** A string constant for enabling memprof_optimize for AutoFDO implicitly. */
  public static final String ENABLE_AUTOFDO_MEMPROF_OPTIMIZE = "enable_autofdo_memprof_optimize";

  /** A string constant for allowing memprof_optimize for AutoFDO implicitly. */
  public static final String AUTOFDO_IMPLICIT_MEMPROF_OPTIMIZE =
      "autofdo_implicit_memprof_optimize";

  /**
   * A string constant for allowing use of shared LTO backend actions for linkstatic tests building
   * with ThinLTO.
   */
  public static final String THIN_LTO_LINKSTATIC_TESTS_USE_SHARED_NONLTO_BACKENDS =
      "thin_lto_linkstatic_tests_use_shared_nonlto_backends";

  /**
   * A string constant for allowing use of shared LTO backend actions for all linkstatic links
   * building with ThinLTO.
   */
  public static final String THIN_LTO_ALL_LINKSTATIC_USE_SHARED_NONLTO_BACKENDS =
      "thin_lto_all_linkstatic_use_shared_nonlto_backends";

  /** A string constant for native deps links. */
  public static final String NATIVE_DEPS_LINK = "native_deps_link";

  /** A string constant for java launcher links. */
  public static final String JAVA_LAUNCHER_LINK = "java_launcher_link";

  /** A string constant for python launcher links. */
  public static final String PY_LAUNCHER_LINK = "py_launcher_link";

  /**
   * A string constant for the PDB file generation feature, should only be used for toolchains
   * targeting Windows that include a linker producing PDB files
   */
  public static final String GENERATE_PDB_FILE = "generate_pdb_file";

  /**
   * A string constant for a feature that automatically exporting symbols on Windows. Bazel
   * generates a DEF file for object files of a cc_library, then use it at linking time. This
   * feature should only be used for toolchains targeting Windows, and the toolchain should support
   * using DEF files for exporting symbols.
   */
  public static final String WINDOWS_EXPORT_ALL_SYMBOLS = "windows_export_all_symbols";

  /** A string constant for a feature to disable WINDOWS_EXPORT_ALL_SYMBOLS. */
  public static final String NO_WINDOWS_EXPORT_ALL_SYMBOLS = "no_windows_export_all_symbols";

  /** A string constant for a feature to copy dynamic libraries to the binary's directory. */
  public static final String COPY_DYNAMIC_LIBRARIES_TO_BINARY = "copy_dynamic_libraries_to_binary";

  /** A string constant for a feature to statically link MSVCRT info on Windows. */
  public static final String STATIC_LINK_MSVCRT = "static_link_msvcrt";

  /** A string constant for a feature to statically link MSVCRT without debug info on Windows. */
  public static final String STATIC_LINK_MSVCRT_NO_DEBUG = "static_link_msvcrt_no_debug";

  /** A string constant for a feature to dynamically link MSVCRT without debug info on Windows. */
  public static final String DYNAMIC_LINK_MSVCRT_NO_DEBUG = "dynamic_link_msvcrt_no_debug";

  /** A string constant for a feature to statically link MSVCRT with debug info on Windows. */
  public static final String STATIC_LINK_MSVCRT_DEBUG = "static_link_msvcrt_debug";

  /** A string constant for a feature to dynamically link MSVCRT with debug info on Windows. */
  public static final String DYNAMIC_LINK_MSVCRT_DEBUG = "dynamic_link_msvcrt_debug";

  /** A string constant for a feature to statically link the C++ runtimes. */
  public static final String STATIC_LINK_CPP_RUNTIMES = "static_link_cpp_runtimes";

  /**
   * A string constant for a feature that indicates we are using a toolchain building for Windows.
   */
  public static final String TARGETS_WINDOWS = "targets_windows";

  /**
   * A string constant for a feature that indicates we are using a toolchain building for Windows.
   */
  public static final String SUPPORTS_INTERFACE_SHARED_LIBRARIES =
      "supports_interface_shared_libraries";

  /**
   * A string constant for no_stripping feature, if it's specified, then no strip action config is
   * needed, instead the stripped binary will simply be a symlink (or a copy on Windows) of the
   * original binary.
   */
  public static final String NO_STRIPPING = "no_stripping";

  /** A string constant for /showIncludes parsing feature, should only be used for MSVC toolchain */
  public static final String PARSE_SHOWINCLUDES = "parse_showincludes";

  /** A string constant for a feature that, if enabled, disables .d file handling. */
  public static final String NO_DOTD_FILE = "no_dotd_file";

  /*
   * A string constant for the fdo_instrument feature.
   */
  public static final String FDO_INSTRUMENT = "fdo_instrument";

  /** A string constant for the cs_fdo_instrument feature. */
  public static final String CS_FDO_INSTRUMENT = "cs_fdo_instrument";

  /** A string constant for the fdo_optimize feature. */
  public static final String FDO_OPTIMIZE = "fdo_optimize";

  /** A string constant for the cs_fdo_optimize feature. */
  public static final String CS_FDO_OPTIMIZE = "cs_fdo_optimize";

  /** A string constant for the cache prefetch hints feature. */
  public static final String FDO_PREFETCH_HINTS = "fdo_prefetch_hints";

  /** A string constant for the propeller optimize feature. */
  public static final String PROPELLER_OPTIMIZE = "propeller_optimize";

  /** A string constant for the memprof profile optimization feature. */
  public static final String MEMPROF_OPTIMIZE = "memprof_optimize";

  /**
   * A string constant for the propeller_optimize_thinlto_compile_actions feature.
   *
   * <p>TODO(b/182804945): Remove after making sure that the rollout of the new Propeller profile
   * passing logic didn't break anything.
   */
  public static final String PROPELLER_OPTIMIZE_THINLTO_COMPILE_ACTIONS =
      "propeller_optimize_thinlto_compile_actions";

  /** A string constant for the autofdo feature. */
  public static final String AUTOFDO = "autofdo";

  /** A string constant for the build_interface_libraries feature. */
  public static final String BUILD_INTERFACE_LIBRARIES = "build_interface_libraries";

  /** A string constant for the xbinaryfdo feature. */
  public static final String XBINARYFDO = "xbinaryfdo";

  /** A string constant for the coverage feature. */
  public static final String COVERAGE = "coverage";

  /** Produce artifacts for coverage in llvm coverage mapping format. */
  public static final String LLVM_COVERAGE_MAP_FORMAT = "llvm_coverage_map_format";

  /** Produce artifacts for coverage in gcc coverage mapping format. */
  public static final String GCC_COVERAGE_MAP_FORMAT = "gcc_coverage_map_format";

  /** A string constant for the match-clif action. */
  public static final String MATCH_CLIF = "match_clif";

  /** A feature marking that the toolchain can use --start-lib/--end-lib flags */
  public static final String SUPPORTS_START_END_LIB = "supports_start_end_lib";

  /**
   * A feature marking that the toolchain can produce binaries that load shared libraries at
   * runtime.
   */
  public static final String SUPPORTS_DYNAMIC_LINKER = "supports_dynamic_linker";

  /** A feature marking that the target needs to link its deps in --whole-archive block. */
  public static final String LEGACY_WHOLE_ARCHIVE = "legacy_whole_archive";

  /** A feature for force disabling whole-archive on a per target and per rule type basis. */
  public static final String FORCE_NO_WHOLE_ARCHIVE = "force_no_whole_archive";

  /**
   * A feature marking that the target generates libraries that should not be put in a
   * --whole-archive block.
   */
  public static final String DISABLE_WHOLE_ARCHIVE_FOR_STATIC_LIB =
      "disable_whole_archive_for_static_lib";

  public static final String COMPILER_PARAM_FILE = "compiler_param_file";

  /**
   * A feature to control whether to use param files for archiving commands. This can be applied to
   * individual targets.
   */
  public static final String ARCHIVE_PARAM_FILE = "archive_param_file";

  /** A feature to use gcc quoting for linking param files. */
  public static final String GCC_QUOTING_FOR_PARAM_FILES = "gcc_quoting_for_param_files";

  /** A feature to use windows quoting for linking param files. */
  public static final String WINDOWS_QUOTING_FOR_PARAM_FILES = "windows_quoting_for_param_files";

  /**
   * A feature to indicate that this target generates debug symbols for a dSYM file. For Apple
   * platform only.
   */
  public static final String GENERATE_DSYM_FILE_FEATURE_NAME = "generate_dsym_file";

  /**
   * A feature to indicate that this target does not generate debug symbols. For Apple platform
   * only.
   *
   * <p>Note that the crosstool does not support feature negation in FlagSet.with_feature, which is
   * the mechanism used to condition linker arguments here. Therefore, we expose
   * "no_generate_debug_symbols" in addition to "generate_dsym_file"
   */
  public static final String NO_GENERATE_DEBUG_SYMBOLS_FEATURE_NAME = "no_generate_debug_symbols";

  /** A feature to indicate whether to generate linkmap. */
  public static final String GENERATE_LINKMAP_FEATURE_NAME = "generate_linkmap";

  /** A feature to indicate whether to do linker deadstrip. For Apple platform only. */
  public static final String DEAD_STRIP_FEATURE_NAME = "dead_strip";

  /** Ancestor for all rules that do include scanning. */
  public static final class CcIncludeScanningRule implements RuleDefinition {
    private final boolean addGrepIncludes;

    public CcIncludeScanningRule(boolean addGrepIncludes) {
      this.addGrepIncludes = addGrepIncludes;
    }

    public CcIncludeScanningRule() {
      this(true);
    }

    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      if (addGrepIncludes) {
        builder.add(
            attr("$grep_includes", LABEL)
                .cfg(ExecutionTransitionFactory.createFactory())
                .value(env.getToolsLabel("//tools/cpp:grep-includes")));
      }
      return builder.build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$cc_include_scanning_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /** Name of the exec group that Cpp link actions run under */
  @VisibleForTesting public static final String CPP_LINK_EXEC_GROUP = "cpp_link";

  /** Common logic for all rules that create C++ linking actions. */
  public static final class CcLinkingRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .addExecGroups(
              ImmutableMap.of(
                  CPP_LINK_EXEC_GROUP,
                  ExecGroup.builder().addToolchainType(ccToolchainTypeRequirement(env)).build()))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$cc_linking_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }
}
