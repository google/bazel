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

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.getFirstArtifactEndingWith;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuiltins;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.ScratchAttributeWriter;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.util.MockObjcSupport;
import com.google.devtools.build.lib.rules.apple.AppleCommandLineOptions;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.List;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import org.junit.Before;

/**
 * Superclass for all Obj-C rule tests.
 *
 * <p>TODO(matvore): split this up into more helper classes, especially the check... methods, which
 * are many and not shared by all objc_ rules.
 *
 * <p>TODO(matvore): find a more concise way to repeat common tests (in particular, those which
 * simply call a check... method) across several rule types.
 */
public abstract class ObjcRuleTestCase extends BuildViewTestCase {
  protected static final ImmutableList<String> FASTBUILD_COPTS = ImmutableList.of("-O0", "-DDEBUG");

  protected static final DottedVersion DEFAULT_IOS_SDK_VERSION =
      DottedVersion.fromStringUnchecked(AppleCommandLineOptions.DEFAULT_IOS_SDK_VERSION);

  protected static final String OUTPUTDIR = TestConstants.PRODUCT_NAME + "-out//bin";

  private static final Provider.Key APPLE_EXECUTABLE_BINARY_PROVIDER_KEY =
      new StarlarkProvider.Key(
          keyForBuild(Label.parseCanonicalUnchecked("//test_starlark:apple_binary_starlark.bzl")),
          "AppleExecutableBinaryInfo");

  @Before
  public void setUp() throws Exception {
    setBuildLanguageOptions("--noincompatible_disable_objc_library_transition");
  }

  protected String execPathEndingWith(Iterable<Artifact> artifacts, String suffix) {
    return getFirstArtifactEndingWith(artifacts, suffix).getExecPathString();
  }

  @Override
  protected void initializeMockClient() throws IOException {
    super.initializeMockClient();
    MockObjcSupport.setup(mockToolsConfig);
  }

  /** Creates an {@code objc_library} target writer for the label indicated by the given String. */
  protected ScratchAttributeWriter createLibraryTargetWriter(String labelString) {
    return ScratchAttributeWriter.fromLabelString(this, "objc_library", labelString);
  }

  private static String compilationModeFlag(CompilationMode mode) {
    switch (mode) {
      case DBG:
        return "dbg";
      case OPT:
        return "opt";
      case FASTBUILD:
        return "fastbuild";
    }
    throw new AssertionError();
  }

  protected static ImmutableList<String> compilationModeCopts(CompilationMode mode) {
    switch (mode) {
      case DBG:
        return ImmutableList.copyOf(ObjcConfiguration.DBG_COPTS);
      case OPT:
        return ObjcConfiguration.OPT_COPTS;
      case FASTBUILD:
        return FASTBUILD_COPTS;
    }
    throw new AssertionError();
  }

  /** Override this to trigger platform-based Apple toolchain resolution. */
  protected boolean platformBasedToolchains() {
    return false;
  }

  @Override
  protected void useConfiguration(String... args) throws Exception {
    ImmutableList<String> newArgs;
    if (platformBasedToolchains()) {
      newArgs = MockObjcSupport.requiredObjcPlatformFlags(args);
    } else {
      newArgs = MockObjcSupport.requiredObjcCrosstoolFlags(args);
    }
    super.useConfiguration(newArgs.toArray(new String[] {}));
  }

  protected void useConfigurationWithCustomXcode(String... args) throws Exception {
    ImmutableList<String> newArgs;
    if (platformBasedToolchains()) {
      newArgs = MockObjcSupport.requiredObjcPlatformFlagsNoXcodeConfig(args);
    } else {
      newArgs = MockObjcSupport.requiredObjcCrosstoolFlagsNoXcodeConfig(args);
    }
    super.useConfiguration(newArgs.toArray(new String[] {}));
  }

  /** Asserts that an action specifies the given requirement. */
  protected void assertHasRequirement(Action action, String executionRequirement) {
    assertThat(action.getExecutionInfo()).containsKey(executionRequirement);
  }

  /** Asserts that an action does not specify the given requirement. */
  protected void assertNotHasRequirement(Action action, String executionRequirement) {
    assertThat(action.getExecutionInfo()).doesNotContainKey(executionRequirement);
  }

  /**
   * Verifies a {@code -filelist} file's contents.
   *
   * @param originalAction the action which uses the filelist artifact
   * @param inputArchives path suffixes of the expected contents of the filelist
   */
  protected void verifyObjlist(Action originalAction, String... inputArchives) throws Exception {
    ImmutableList.Builder<String> execPaths = ImmutableList.builder();
    for (String inputArchive : inputArchives) {
      execPaths.add(execPathEndingWith(originalAction.getInputs().toList(), inputArchive));
    }
    assertThat(paramFileArgsForAction(originalAction)).containsExactlyElementsIn(execPaths.build());
  }

  protected void assertAppleSdkPlatformEnv(CommandAction action, String platformName)
      throws ActionExecutionException {
    assertThat(action.getIncompleteEnvironmentForTesting())
        .containsEntry("APPLE_SDK_PLATFORM", platformName);
  }

  protected void assertXcodeVersionEnv(CommandAction action, String versionNumber)
      throws ActionExecutionException {
    assertThat(action.getIncompleteEnvironmentForTesting())
        .containsEntry("XCODE_VERSION_OVERRIDE", versionNumber);
  }

  protected CcInfo ccInfoForTarget(String label) throws Exception {
    CcInfo ccInfo = getConfiguredTarget(label).get(CcInfo.PROVIDER);
    if (ccInfo != null) {
      return ccInfo;
    }
    StructImpl executableProvider =
        (StructImpl) getConfiguredTarget(label).get(APPLE_EXECUTABLE_BINARY_PROVIDER_KEY);
    if (executableProvider != null) {
      return executableProvider.getValue("cc_info", CcInfo.class);
    }
    return null;
  }

  protected CommandAction archiveAction(String label) throws Exception {
    ConfiguredTarget target = getConfiguredTarget(label);
    return (CommandAction)
        getGeneratingAction(getBinArtifact("lib" + target.getLabel().getName() + ".a", target));
  }

  protected Iterable<Artifact> inputsEndingWith(Action action, final String suffix) {
    return Iterables.filter(
        action.getInputs().toList(), artifact -> artifact.getExecPathString().endsWith(suffix));
  }

  /**
   * Asserts that the given action can specify execution requirements, and requires execution on
   * darwin.
   */
  protected void assertRequiresDarwin(Action action) {
    assertHasRequirement(action, ExecutionRequirements.REQUIRES_DARWIN);
  }

  protected ConfiguredTarget addBinWithTransitiveDepOnFrameworkImport() throws Exception {
    ConfiguredTarget lib = addLibWithDepOnFrameworkImport();
    scratch.file(
        "bin/BUILD",
        "load('//test_starlark:apple_binary_starlark.bzl', 'apple_binary_starlark')",
        "apple_binary_starlark(",
        "    name = 'bin',",
        "    platform_type = 'ios',",
        "    deps = ['" + lib.getLabel().toString() + "'],",
        ")");
    return getConfiguredTarget("//bin:bin");
  }

  private ConfiguredTarget addLibWithDepOnFrameworkImport() throws Exception {
    scratch.file(
        "fx/defs.bzl",
        """
        def _custom_static_framework_import_impl(ctx):
            return [
                CcInfo(
                    compilation_context = cc_common.create_compilation_context(
                        framework_includes = depset(ctx.attr.framework_search_paths),
                    ),
                ),
            ]

        custom_static_framework_import = rule(
            _custom_static_framework_import_impl,
            attrs = {"framework_search_paths": attr.string_list()},
        )
        """);
    scratch.file(
        "fx/BUILD",
        """
        load(":defs.bzl", "custom_static_framework_import")

        custom_static_framework_import(
            name = "fx",
            framework_search_paths = ["fx"],
        )
        """);
    return createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
        .setList("deps", "//fx:fx")
        .write();
  }

  protected static void addAppleBinaryStarlarkRule(Scratch scratch) throws Exception {
    scratch.file("test_starlark/BUILD");
    RepositoryName toolsRepo = TestConstants.TOOLS_REPOSITORY;

    scratch.file(
        "test_starlark/apple_binary_starlark.bzl",
        "_CPU_TO_PLATFORM = {",
        "    'darwin_x86_64': '" + MockObjcSupport.DARWIN_X86_64 + "',",
        "    'ios_x86_64': '" + MockObjcSupport.IOS_X86_64 + "',",
        "    'ios_arm64': '" + MockObjcSupport.IOS_ARM64 + "',",
        "    'ios_arm64e': '" + MockObjcSupport.IOS_ARM64E + "',",
        "    'ios_i386': '" + MockObjcSupport.IOS_I386 + "',", // legacy platform used in tests
        "    'ios_armv7': '" + MockObjcSupport.IOS_ARMV7 + "',", // legacy platform used in tests
        "    'watchos_armv7k': '" + MockObjcSupport.WATCHOS_ARMV7K + "',",
        "    'watchos_arm64_32': '" + MockObjcSupport.WATCHOS_ARM64_32 + "',",
        "}",
        "_apple_platform_transition_inputs = [",
        "    '//command_line_option:apple_crosstool_top',",
        "    '//command_line_option:cpu',",
        "    '//command_line_option:ios_multi_cpus',",
        "    '//command_line_option:macos_cpus',",
        "    '//command_line_option:tvos_cpus',",
        "    '//command_line_option:watchos_cpus',",
        "]",
        "_apple_rule_base_transition_outputs = [",
        "    '//command_line_option:apple configuration distinguisher',",
        "    '//command_line_option:apple_platform_type',",
        "    '//command_line_option:apple_platforms',",
        "    '//command_line_option:apple_split_cpu',",
        "    '//command_line_option:compiler',",
        "    '//command_line_option:cpu',",
        "    '//command_line_option:fission',",
        "    '//command_line_option:grte_top',",
        "    '//command_line_option:platforms',",
        "]",
        "AppleDynamicFrameworkInfo = provider(",
        "    fields = ['framework_dirs', 'framework_files', 'binary', 'cc_info'],)",
        "",
        "AppleExecutableBinaryInfo = provider(",
        "    fields = ['binary', 'cc_info'],)",
        "",
        "AppleDebugOutputsInfo = provider(",
        "    fields = ['outputs_map'],)",
        "",
        "def _command_line_options(*, environment_arch = None, platform_type, settings):",
        "    cpu = ('darwin_' + environment_arch if platform_type == 'macos'",
        "            else platform_type + '_' +  environment_arch)",
        "    output_dictionary = {",
        "        '//command_line_option:apple configuration distinguisher':",
        "            'applebin_' + platform_type,",
        "        '//command_line_option:apple_platform_type': platform_type,",
        "        '//command_line_option:apple_platforms': [],",
        "        '//command_line_option:apple_split_cpu': environment_arch,",
        "        '//command_line_option:compiler': None,",
        "        '//command_line_option:cpu': cpu,",
        "        '//command_line_option:fission': [],",
        "        '//command_line_option:grte_top': None,",
        "        '//command_line_option:platforms': [_CPU_TO_PLATFORM[cpu]],",
        "    }",
        "    return output_dictionary",
        "def _apple_platform_split_transition_impl(settings, attr):",
        "    output_dictionary = {}",
        "    platform_type = attr.platform_type",
        "    if platform_type == 'ios':",
        "       environment_archs = settings['//command_line_option:ios_multi_cpus']",
        "    else:",
        "       environment_archs = settings['//command_line_option:%s_cpus' % platform_type]",
        "    if not environment_archs and platform_type == 'ios':",
        "       cpu_value = settings['//command_line_option:cpu']",
        "       if cpu_value.startswith('ios_'):",
        "           environment_archs = [cpu_value[4:]]",
        "    if not environment_archs:",
        "        environment_archs = ['x86_64']",
        "    for environment_arch in environment_archs:",
        "        found_cpu = 'ios_{}'.format(environment_arch)",
        "        output_dictionary[found_cpu] = _command_line_options(",
        "            environment_arch = environment_arch,",
        "            platform_type = platform_type,",
        "            settings = settings,",
        "        )",
        "    return output_dictionary",
        "apple_platform_split_transition = transition(",
        "    implementation = _apple_platform_split_transition_impl,",
        "    inputs = _apple_platform_transition_inputs,",
        "    outputs = _apple_rule_base_transition_outputs,",
        ")",
        "TargetTripletInfo = provider(fields = ['architecture', 'platform', 'environment'],)",
        "def _build_avoid_library_set(avoid_dep_linking_contexts):",
        "    avoid_library_set = dict()",
        "    for linking_context in avoid_dep_linking_contexts:",
        "        for linker_input in linking_context.linker_inputs.to_list():",
        "            for library_to_link in linker_input.libraries:",
        "                library_artifact ="
            + " apple_common.compilation_support.get_static_library_for_linking(library_to_link)",
        "                if library_artifact:",
        "                    avoid_library_set[library_artifact.short_path] = True",
        "    return avoid_library_set",
        "",
        "def subtract_linking_contexts(owner, linking_contexts, avoid_dep_linking_contexts):",
        "    libraries = []",
        "    user_link_flags = []",
        "    additional_inputs = []",
        "    linkstamps = []",
        "    avoid_library_set = _build_avoid_library_set(avoid_dep_linking_contexts)",
        "    for linking_context in linking_contexts:",
        "        for linker_input in linking_context.linker_inputs.to_list():",
        "            for library_to_link in linker_input.libraries:",
        "                library_artifact ="
            + " apple_common.compilation_support.get_library_for_linking(library_to_link)",
        "                if library_artifact.short_path not in avoid_library_set:",
        "                    libraries.append(library_to_link)",
        "            user_link_flags.extend(linker_input.user_link_flags)",
        "            additional_inputs.extend(linker_input.additional_inputs)",
        "            linkstamps.extend(linker_input.linkstamps)",
        "    linker_input = cc_common.create_linker_input(",
        "        owner = owner,",
        "        libraries = depset(libraries, order = 'topological'),",
        "        user_link_flags = user_link_flags,",
        "        additional_inputs = depset(additional_inputs),",
        "        linkstamps = depset(linkstamps),",
        "    )",
        "    return cc_common.create_linking_context(",
        "        linker_inputs = depset([linker_input]),",
        "        owner = owner,",
        "    )",
        "",
        "def _get_target_triplet(config):",
        "    cpu_platform ="
            + " apple_common.apple_platform.for_target_cpu(apple_common.get_cpu(config))",
        "    apple_config = apple_common.get_apple_config(config)",
        "",
        "    return TargetTripletInfo(",
        "        architecture = apple_config.single_arch_cpu,",
        "        platform = apple_common.apple_platform.get_target_platform(cpu_platform),",
        "        environment = apple_common.apple_platform.get_target_environment(cpu_platform),",
        "    )",
        "",
        "def get_split_target_triplet(ctx):",
        "    result = dict()",
        "    ctads = apple_common.get_split_prerequisites(ctx)",
        "    for split_transition_key, config in ctads.items():",
        "        if split_transition_key == None:",
        "            fail('unexpected empty key in split transition')",
        "        result[split_transition_key] = _get_target_triplet(config)",
        "    return result",
        "",
        "def _link_multi_arch_binary(",
        "        *,",
        "        ctx,",
        "        avoid_deps = [],",
        "        extra_linkopts = [],",
        "        extra_link_inputs = [],",
        "        extra_requested_features = [],",
        "        extra_disabled_features = [],",
        "        stamp = -1,",
        "        variables_extension = {}):",
        "",
        "    split_target_triplets = get_split_target_triplet(ctx)",
        "    split_build_configs = apple_common.get_split_build_configs(ctx)",
        "    split_deps = ctx.split_attr.deps",
        "    child_configs_and_toolchains = ctx.split_attr._child_configuration_dummy",
        "",
        "    if split_deps and split_deps.keys() != child_configs_and_toolchains.keys():",
        "        fail(('Split transition keys are different between deps [%s] and ' +",
        "              '_child_configuration_dummy [%s]') % (",
        "            split_deps.keys(),",
        "            child_configs_and_toolchains.keys(),",
        "        ))",
        "",
        "    avoid_cc_infos = [",
        "        dep[AppleDynamicFrameworkInfo].cc_info",
        "        for dep in avoid_deps",
        "        if AppleDynamicFrameworkInfo in dep",
        "    ]",
        "    avoid_cc_infos.extend([",
        "        dep[AppleExecutableBinaryInfo].cc_info",
        "        for dep in avoid_deps",
        "        if AppleExecutableBinaryInfo in dep",
        "    ])",
        "    avoid_cc_infos.extend([dep[CcInfo] for dep in avoid_deps if CcInfo in dep])",
        "    avoid_cc_linking_contexts = [dep.linking_context for dep in avoid_cc_infos]",
        "",
        "    outputs = []",
        "    cc_infos = []",
        "    legacy_debug_outputs = {}",
        "",
        "    cc_infos.extend(avoid_cc_infos)",
        "",
        "    additional_linker_inputs = getattr(ctx.attr, 'additional_linker_inputs', [])",
        "    attr_linkopts = [",
        "        ctx.expand_location(opt, targets = additional_linker_inputs)",
        "        for opt in getattr(ctx.attr, 'linkopts', [])",
        "    ]",
        "    attr_linkopts = [token for opt in attr_linkopts for token in ctx.tokenize(opt)]",
        "",
        "    for split_transition_key, child_toolchain in child_configs_and_toolchains.items():",
        "        cc_toolchain = child_toolchain[cc_common.CcToolchainInfo]",
        "        deps = split_deps.get(split_transition_key, [])",
        "        target_triplet = split_target_triplets.get(split_transition_key)",
        "",
        "        common_variables = apple_common.compilation_support.build_common_variables(",
        "            ctx = ctx,",
        "            toolchain = cc_toolchain,",
        "            deps = deps,",
        "            extra_disabled_features = extra_disabled_features,",
        "            extra_enabled_features = extra_requested_features,",
        "            attr_linkopts = attr_linkopts,",
        "        )",
        "",
        "        cc_infos.append(CcInfo(",
        "            compilation_context = cc_common.merge_compilation_contexts(",
        "                compilation_contexts =",
        "                    common_variables.objc_compilation_context.cc_compilation_contexts,",
        "            ),",
        "            linking_context = cc_common.merge_linking_contexts(",
        "                linking_contexts ="
            + " common_variables.objc_linking_context.cc_linking_contexts,",
        "            ),",
        "        ))",
        "",
        "        cc_linking_context = subtract_linking_contexts(",
        "            owner = ctx.label,",
        "            linking_contexts = common_variables.objc_linking_context.cc_linking_contexts"
            + " +",
        "                               avoid_cc_linking_contexts,",
        "            avoid_dep_linking_contexts = avoid_cc_linking_contexts,",
        "        )",
        "",
        "        child_config = split_build_configs.get(split_transition_key)",
        "",
        "        additional_outputs = []",
        "        extensions = {}",
        "",
        "        dsym_binary = None",
        "        if ctx.fragments.cpp.apple_generate_dsym:",
        "            if ctx.fragments.cpp.objc_should_strip_binary:",
        "                suffix = '_bin_unstripped.dwarf'",
        "            else:",
        "                suffix = '_bin.dwarf'",
        "            dsym_binary = ctx.actions.declare_shareable_artifact(",
        "                ctx.label.package + '/' + ctx.label.name + suffix,",
        "                child_config.bin_dir,",
        "            )",
        "            extensions['dsym_path'] = dsym_binary.path  # dsym symbol file",
        "            additional_outputs.append(dsym_binary)",
        "            legacy_debug_outputs.setdefault(target_triplet.architecture,"
            + " {})['dsym_binary'] = dsym_binary",
        "",
        "        linkmap = None",
        "        if ctx.fragments.cpp.objc_generate_linkmap:",
        "            linkmap = ctx.actions.declare_shareable_artifact(",
        "                ctx.label.package + '/' + ctx.label.name + '.linkmap',",
        "                child_config.bin_dir,",
        "            )",
        "            extensions['linkmap_exec_path'] = linkmap.path  # linkmap file",
        "            additional_outputs.append(linkmap)",
        "            legacy_debug_outputs.setdefault(target_triplet.architecture, {})['linkmap'] ="
            + " linkmap",
        "",
        "        name = ctx.label.name + '_bin'",
        "        executable ="
            + " apple_common.compilation_support.register_configuration_specific_link_actions(",
        "            name = name,",
        "            common_variables = common_variables,",
        "            cc_linking_context = cc_linking_context,",
        "            build_config = child_config,",
        "            extra_link_args = extra_linkopts,",
        "            stamp = stamp,",
        "            user_variable_extensions = variables_extension | extensions,",
        "            additional_outputs = additional_outputs,",
        "            deps = deps,",
        "            extra_link_inputs = extra_link_inputs,",
        "            attr_linkopts = attr_linkopts,",
        "        )",
        "",
        "        output = {",
        "            'binary': executable,",
        "            'platform': target_triplet.platform,",
        "            'architecture': target_triplet.architecture,",
        "            'environment': target_triplet.environment,",
        "            'dsym_binary': dsym_binary,",
        "            'linkmap': linkmap,",
        "        }",
        "",
        "        outputs.append(struct(**output))",
        "",
        "    header_tokens = []",
        "    for _, deps in split_deps.items():",
        "        for dep in deps:",
        "            if CcInfo in dep:",
        "               "
            + " header_tokens.append(dep[CcInfo].compilation_context.validation_artifacts)",
        "",
        "    output_groups = {'_validation': depset(transitive = header_tokens)}",
        "",
        "    return struct(",
        "        cc_info = cc_common.merge_cc_infos(direct_cc_infos = cc_infos),",
        "        output_groups = output_groups,",
        "        outputs = outputs,",
        "        debug_outputs_provider = AppleDebugOutputsInfo(outputs_map ="
            + " legacy_debug_outputs),",
        "    )",
        "",
        "def apple_binary_starlark_impl(ctx):",
        "    all_avoid_deps = list(ctx.attr.avoid_deps)",
        "    binary_type = ctx.attr.binary_type",
        "    bundle_loader = ctx.attr.bundle_loader",
        "    linkopts = []",
        "    link_inputs = []",
        "    variables_extension = {}",
        "    variables_extension.update(ctx.attr.string_variables_extension)",
        "    variables_extension.update(ctx.attr.string_list_variables_extension)",
        "    if binary_type == 'dylib':",
        "        linkopts.append('-dynamiclib')",
        "    elif binary_type == 'loadable_bundle':",
        "        linkopts.extend(['-bundle', '-Xlinker', '-rpath', '-Xlinker',"
            + " '@loader_path/Frameworks'])",
        "    if ctx.attr.bundle_loader:",
        "        bundle_loader = ctx.attr.bundle_loader",
        "        bundle_loader_file = bundle_loader[AppleExecutableBinaryInfo].binary",
        "        all_avoid_deps.append(bundle_loader)",
        "        linkopts.extend(['-bundle_loader', bundle_loader_file.path])",
        "        link_inputs.append(bundle_loader_file)",
        "    link_result = _link_multi_arch_binary(",
        "        ctx = ctx,",
        "        avoid_deps = all_avoid_deps,",
        "        extra_linkopts = linkopts,",
        "        extra_link_inputs = link_inputs,",
        "        extra_requested_features = ctx.attr.extra_requested_features,",
        "        extra_disabled_features = ctx.attr.extra_disabled_features,",
        "        stamp = ctx.attr.stamp,",
        "        variables_extension = variables_extension,",
        "    )",
        "    processed_binary = ctx.actions.declare_file('{}_lipobin'.format(ctx.label.name))",
        "    lipo_inputs = [output.binary for output in link_result.outputs]",
        "    if len(lipo_inputs) > 1:",
        "        apple_env = {}",
        "        xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]",
        "        apple_env.update(apple_common.apple_host_system_env(xcode_config))",
        "        apple_env.update(",
        "            apple_common.target_apple_env(",
        "                xcode_config,",
        "                ctx.fragments.apple.single_arch_platform,",
        "            ),",
        "        )",
        "        args = ctx.actions.args()",
        "        args.add('-create')",
        "        args.add_all(lipo_inputs)",
        "        args.add('-output', processed_binary)",
        "        ctx.actions.run(",
        "            arguments = [args],",
        "            env = apple_env,",
        "            executable = '/usr/bin/lipo',",
        "            execution_requirements = xcode_config.execution_info(),",
        "            inputs = lipo_inputs,",
        "            outputs = [processed_binary],",
        "        )",
        "    else:",
        "        ctx.actions.symlink(target_file = lipo_inputs[0], output = processed_binary)",
        "    providers = [",
        "        DefaultInfo(files=depset([processed_binary])),",
        "        OutputGroupInfo(**link_result.output_groups),",
        "        link_result.debug_outputs_provider,",
        "    ]",
        "    if binary_type == 'executable':",
        "        providers.append(AppleExecutableBinaryInfo(",
        "            binary = processed_binary,",
        "            cc_info = link_result.cc_info,",
        "        ))",
        "    return providers",
        "apple_binary_starlark = rule(",
        "    apple_binary_starlark_impl,",
        "    attrs = {",
        "        '_child_configuration_dummy': attr.label(",
        "            cfg=apple_platform_split_transition,",
        "            default=Label('" + toolsRepo + "//tools/cpp:current_cc_toolchain'),),",
        "        '_xcode_config': attr.label(",
        "            default=configuration_field(",
        "                fragment='apple', name='xcode_config_label'),),",
        "        'additional_linker_inputs': attr.label_list(",
        "            allow_files = True,",
        "        ),",
        "        'avoid_deps': attr.label_list(default=[]),",
        "        'binary_type': attr.string(",
        "             default = 'executable',",
        "             values = ['dylib', 'executable', 'loadable_bundle']",
        "        ),",
        "        'bundle_loader': attr.label(),",
        "        'deps': attr.label_list(",
        "             cfg=apple_platform_split_transition,",
        "        ),",
        "        'linkopts': attr.string_list(),",
        "        'extra_requested_features': attr.string_list(),",
        "        'extra_disabled_features': attr.string_list(),",
        "        'platform_type': attr.string(),",
        "        'minimum_os_version': attr.string(),",
        "        'stamp': attr.int(values=[-1,0,1],default=-1),",
        "        'string_variables_extension': attr.string_dict(),",
        "        'string_list_variables_extension': attr.string_list_dict(),",
        "    },",
        "    fragments = ['apple', 'objc', 'cpp']",
        ")");
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//...",
            ],
        )
        """);
  }

  protected CommandAction compileAction(String ownerLabel, String objFileName) throws Exception {
    Action archiveAction = archiveAction(ownerLabel);
    return (CommandAction)
        getGeneratingAction(
            getFirstArtifactEndingWith(archiveAction.getInputs(), "/" + objFileName));
  }

  /**
   * Verifies simply that some rule type creates the {@link CompilationArtifacts} object
   * successfully; in particular, makes sure it is not ignoring attributes. If the scope of {@link
   * CompilationArtifacts} expands, make sure this method tests it properly.
   *
   * <p>This test only makes sure the attributes are not being ignored - it does not test any other
   * functionality in depth, which is covered by other unit tests.
   */
  protected void checkPopulatesCompilationArtifacts(RuleType ruleType) throws Exception {
    scratch.file("x/a.m");
    scratch.file("x/b.m");
    scratch.file("x/c.pch");
    ruleType.scratchTarget(scratch, "srcs", "['a.m']", "non_arc_srcs", "['b.m']", "pch", "'c.pch'");
    ImmutableList<String> includeFlags = ImmutableList.of("-include", "x/c.pch");
    assertContainsSublist(compileAction("//x:x", "a.o").getArguments(), includeFlags);
    assertContainsSublist(compileAction("//x:x", "b.o").getArguments(), includeFlags);
  }

  protected void checkProvidesHdrsAndIncludes(RuleType ruleType, Optional<String> privateHdr)
      throws Exception {
    scratch.file("x/a.h");
    ruleType.scratchTarget(scratch, "hdrs", "['a.h']", "includes", "['incdir']");
    CcCompilationContext ccCompilationContext =
        getConfiguredTarget("//x:x").get(CcInfo.PROVIDER).getCcCompilationContext();
    ImmutableList<String> declaredIncludeSrcs =
        ccCompilationContext.getDeclaredIncludeSrcs().toList().stream()
            .map(x -> removeConfigFragment(x.getExecPathString()))
            .collect(toImmutableList());
    if (privateHdr.isPresent()) {
      assertThat(declaredIncludeSrcs)
          .containsExactly(
              getSourceArtifact("x/a.h").getExecPathString(),
              getSourceArtifact(privateHdr.get()).getExecPathString());
    } else {
      assertThat(declaredIncludeSrcs)
          .containsExactly(getSourceArtifact("x/a.h").getExecPathString());
    }
    assertThat(
            ccCompilationContext.getIncludeDirs().stream()
                .map(x -> removeConfigFragment(x.toString())))
        .containsExactly(PathFragment.create("x/incdir").toString(), OUTPUTDIR + "/x/incdir");
  }

  protected void checkCompilesWithHdrs(RuleType ruleType) throws Exception {
    scratch.file("x/a.m");
    scratch.file("x/a.h");
    ruleType.scratchTarget(scratch, "srcs", "['a.m']", "hdrs", "['a.h']");
    assertThat(compileAction("//x:x", "a.o").getPossibleInputsForTesting().toList())
        .contains(getSourceArtifact("x/a.h"));
  }

  protected Action lipoBinAction(String binLabel) throws Exception {
    return actionProducingArtifact(binLabel, "_lipobin");
  }

  protected CommandAction linkAction(String binLabel) throws Exception {
    CommandAction linkAction = (CommandAction) actionProducingArtifact(binLabel, "_bin");
    if (linkAction == null) {
      // For multi-architecture rules, the link action is not in the target configuration, but
      // across a configuration transition.
      Action lipoAction = lipoBinAction(binLabel);
      if (lipoAction != null) {
        Artifact binArtifact = getFirstArtifactEndingWith(lipoAction.getInputs(), "_bin");
        linkAction = (CommandAction) getGeneratingAction(binArtifact);
      }
    }
    return linkAction;
  }

  protected Action actionProducingArtifact(String targetLabel, String artifactSuffix)
      throws Exception {
    ConfiguredTarget libraryTarget = getConfiguredTarget(targetLabel);
    Label parsedLabel = Label.parseCanonical(targetLabel);
    Artifact linkedLibrary = getBinArtifact(parsedLabel.getName() + artifactSuffix, libraryTarget);
    return getGeneratingAction(linkedLibrary);
  }

  protected List<String> rootedIncludePaths(String... unrootedPaths) {
    ImmutableList.Builder<String> rootedPaths = new ImmutableList.Builder<>();
    for (String unrootedPath : unrootedPaths) {
      rootedPaths
          .add(unrootedPath)
          .add(
              removeConfigFragment(
                  PathFragment.create(TestConstants.PRODUCT_NAME + "-out/any-config-fragment/bin")
                      .getRelative(unrootedPath)
                      .getSafePathString()));
    }
    return rootedPaths.build();
  }

  protected void checkClangCoptsForCompilationMode(RuleType ruleType, CompilationMode mode)
      throws Exception {
    ImmutableList.Builder<String> allExpectedCoptsBuilder =
        ImmutableList.<String>builder()
            .addAll(CompilationSupport.DEFAULT_COMPILER_FLAGS)
            .addAll(compilationModeCopts(mode));
    useConfiguration(
        "--platforms=" + MockObjcSupport.IOS_X86_64,
        "--apple_platform_type=ios",
        "--compilation_mode=" + compilationModeFlag(mode));

    scratch.file("x/a.m");
    ruleType.scratchTarget(scratch, "srcs", "['a.m']");

    CommandAction compileActionA = compileAction("//x:x", "a.o");

    assertThat(compileActionA.getArguments())
        .containsAtLeastElementsIn(allExpectedCoptsBuilder.build());
  }

  protected void checkClangCoptsForDebugModeWithoutGlib(RuleType ruleType) throws Exception {
    ImmutableList.Builder<String> allExpectedCoptsBuilder =
        ImmutableList.<String>builder()
            .addAll(CompilationSupport.DEFAULT_COMPILER_FLAGS)
            .addAll(ObjcConfiguration.DBG_COPTS);

    useConfiguration(
        "--platforms=" + MockObjcSupport.IOS_X86_64,
        "--apple_platform_type=ios",
        "--compilation_mode=dbg",
        "--objc_debug_with_GLIBCXX=false",
        "--experimental_platform_in_output_dir");
    scratch.file("x/a.m");
    ruleType.scratchTarget(scratch, "srcs", "['a.m']");

    CommandAction compileActionA = compileAction("//x:x", "a.o");

    assertThat(compileActionA.getArguments())
        .containsAtLeastElementsIn(allExpectedCoptsBuilder.build())
        .inOrder();
    assertThat(compileActionA.getArguments()).doesNotContain("-D_GLIBCXX_DEBUG");
  }

  private void addTransitiveDefinesUsage(RuleType topLevelRuleType) throws Exception {
    createLibraryTargetWriter("//lib1:lib1")
        .setAndCreateFiles("srcs", "a.m")
        .setList("defines", "A=foo", "B")
        .write();
    createLibraryTargetWriter("//lib2:lib2")
        .setAndCreateFiles("srcs", "a.m")
        .setList("deps", "//lib1:lib1")
        .setList("defines", "C=bar", "D")
        .write();

    topLevelRuleType.scratchTarget(
        scratch,
        "srcs",
        "['a.m']",
        "non_arc_srcs",
        "['b.m']",
        "deps",
        "['//lib2:lib2']",
        "defines",
        "['E=baz']",
        "copts",
        "['explicit_copt']");
  }

  protected void checkReceivesTransitivelyPropagatedDefines(RuleType ruleType) throws Exception {
    addTransitiveDefinesUsage(ruleType);
    List<String> expectedArgs =
        ImmutableList.of("-DA=foo", "-DB", "-DC=bar", "-DD", "-DE=baz", "explicit_copt");
    List<String> compileActionAArgs = compileAction("//x:x", "a.o").getArguments();
    List<String> compileActionBArgs = compileAction("//x:x", "b.o").getArguments();
    for (String expectedArg : expectedArgs) {
      assertThat(compileActionAArgs).contains(expectedArg);
      assertThat(compileActionBArgs).contains(expectedArg);
    }
  }

  protected void checkDefinesFromCcLibraryDep(RuleType ruleType) throws Exception {
    useConfiguration();
    ScratchAttributeWriter.fromLabelString(this, "cc_library", "//dep:lib")
        .setList("srcs", "a.cc")
        .setList("defines", "foo", "bar")
        .write();

    ScratchAttributeWriter.fromLabelString(this, ruleType.getRuleTypeName(), "//objc:x")
        .setList("srcs", "a.m")
        .setList("deps", "//dep:lib")
        .write();

    CommandAction compileAction = compileAction("//objc:x", "a.o");
    assertThat(compileAction.getArguments()).containsAtLeast("-Dfoo", "-Dbar");
  }

  protected void checkSdkIncludesUsedInCompileAction(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch, "sdk_includes", "['foo', 'bar/baz']", "srcs", "['a.m', 'b.m']");
    String sdkIncludeDir = "__BAZEL_XCODE_SDKROOT__/usr/include";
    // we remove spaces, since the legacy rules put a space after "-I" in include paths.
    String compileActionACommandLine =
        Joiner.on(" ").join(compileAction("//x:x", "a.o").getArguments()).replace(" ", "");
    assertThat(compileActionACommandLine).contains("-I" + sdkIncludeDir + "/foo");
    assertThat(compileActionACommandLine).contains("-I" + sdkIncludeDir + "/bar/baz");

    String compileActionBCommandLine =
        Joiner.on(" ").join(compileAction("//x:x", "b.o").getArguments()).replace(" ", "");
    assertThat(compileActionBCommandLine).contains("-I" + sdkIncludeDir + "/foo");
    assertThat(compileActionBCommandLine).contains("-I" + sdkIncludeDir + "/bar/baz");
  }

  protected void checkSdkIncludesUsedInCompileActionsOfDependers(RuleType ruleType)
      throws Exception {
    ruleType.scratchTarget(scratch, "sdk_includes", "['foo', 'bar/baz']");
    // Add some dependers (including transitive depender //bin:bin) and make sure they use the flags
    // as well.
    createLibraryTargetWriter("//lib:lib")
        .setAndCreateFiles("srcs", "a.m")
        .setList("deps", "//x:x")
        .setList("sdk_includes", "from_lib")
        .write();
    createLibraryTargetWriter("//bin:main_lib")
        .setAndCreateFiles("srcs", "b.m")
        .setList("deps", "//lib:lib")
        .setList("sdk_includes", "from_bin")
        .write();
    String sdkIncludeDir = "__BAZEL_XCODE_SDKROOT__/usr/include";

    // We remove spaces because the crosstool case does not use spaces for include paths.
    String compileAArgs =
        Joiner.on("").join(compileAction("//lib:lib", "a.o").getArguments()).replace(" ", "");
    assertThat(compileAArgs).contains("-I" + sdkIncludeDir + "/from_lib");
    assertThat(compileAArgs).contains("-I" + sdkIncludeDir + "/foo");
    assertThat(compileAArgs).contains("-I" + sdkIncludeDir + "/bar/baz");

    String compileBArgs =
        Joiner.on("").join(compileAction("//bin:main_lib", "b.o").getArguments()).replace(" ", "");
    assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/from_bin");
    assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/from_lib");
    assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/foo");
    assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/bar/baz");
  }

  public void checkAllowVariousNonBlacklistedTypesInHeaders(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch, "hdrs", "['foo.foo', 'NoExtension', 'bar.inc', 'baz.hpp']");
    assertThat(view.hasErrors(getConfiguredTarget("//x:x"))).isFalse();
  }

  public void checkFilesToCompileOutputGroup(RuleType ruleType) throws Exception {
    ruleType.scratchTarget(scratch);
    ConfiguredTarget target = getConfiguredTarget("//x:x");
    assertThat(
            ActionsTestUtil.baseNamesOf(getOutputGroup(target, OutputGroupInfo.FILES_TO_COMPILE)))
        .isEqualTo("a.o");
  }

  protected String removeConfigFragment(String text) {
    return text.replaceAll("-out/.*/bin", "-out//bin").replaceAll("-out/.*/gen", "-out//gen");
  }

  protected List<String> removeConfigFragment(List<String> text) {
    return text.stream().map(this::removeConfigFragment).collect(toImmutableList());
  }

  protected static Iterable<String> getArifactPathsOfLibraries(ConfiguredTarget target) {
    return Artifact.toRootRelativePaths(
        target
            .get(CcInfo.PROVIDER)
            .getCcLinkingContext()
            .getStaticModeParamsForDynamicLibraryLibraries());
  }

  protected static Iterable<String> getArifactPathsOfHeaders(ConfiguredTarget target) {
    return Artifact.toRootRelativePaths(
        target.get(CcInfo.PROVIDER).getCcCompilationContext().getDeclaredIncludeSrcs());
  }

  protected static StarlarkInfo getObjcInfo(ConfiguredTarget starlarkTarget)
      throws LabelSyntaxException {
    return (StarlarkInfo)
        starlarkTarget.get(
            new StarlarkProvider.Key(
                keyForBuiltins(Label.parseCanonical("@_builtins//:common/objc/objc_info.bzl")),
                "ObjcInfo"));
  }

  protected static ImmutableList<Artifact> getDirectSources(StarlarkInfo provider)
      throws EvalException {
    return Sequence.cast(provider.getValue("direct_sources"), Artifact.class, "direct_sources")
        .getImmutableList();
  }

  protected static NestedSet<Artifact> getModuleMap(StarlarkInfo provider) throws EvalException {
    return Depset.cast(provider.getValue("module_map"), Artifact.class, "module_map");
  }

  protected static ImmutableList<Artifact> getSource(StarlarkInfo provider) throws EvalException {
    return Depset.cast(provider.getValue("source"), Artifact.class, "source").toList();
  }

  protected static ImmutableList<String> getStrictInclude(StarlarkInfo provider)
      throws EvalException {
    return Depset.cast(provider.getValue("strict_include"), String.class, "strict_include")
        .toList();
  }

  protected static ImmutableList<Artifact> getUmbrellaHeader(StarlarkInfo provider)
      throws EvalException {
    return Depset.cast(provider.getValue("umbrella_header"), Artifact.class, "umbrella_header")
        .toList();
  }
}
