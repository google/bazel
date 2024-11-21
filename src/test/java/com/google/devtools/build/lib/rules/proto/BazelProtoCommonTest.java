// Copyright 2022 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.proto;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.truth.Correspondence;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.util.MockProtoSupport;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.OS;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for proto_common module. */
@RunWith(TestParameterInjector.class)
public class BazelProtoCommonTest extends BuildViewTestCase {
  private static final Correspondence<String, String> MATCHES_REGEX =
      Correspondence.from((a, b) -> Pattern.matches(b, a), "matches");

  private static final StarlarkProviderIdentifier boolProviderId =
      StarlarkProviderIdentifier.forKey(
          new StarlarkProvider.Key(
              keyForBuild(Label.parseCanonicalUnchecked("//foo:should_generate.bzl")),
              "BoolProvider"));

  @Before
  public final void setup() throws Exception {
    MockProtoSupport.setup(mockToolsConfig);
    invalidatePackages();

    scratch.file(
        "third_party/x/BUILD",
        """
        load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
        licenses(['unencumbered'])
        cc_binary(name = 'plugin', srcs = ['plugin.cc'])
        cc_library(name = 'runtime', srcs = ['runtime.cc'])
        filegroup(name = 'descriptors', srcs = ['metadata.proto', 'descriptor.proto'])
        filegroup(name = 'any', srcs = ['any.proto'])
        filegroup(name = 'something', srcs = ['something.proto'])
        proto_library(name = 'mixed', srcs = [':descriptors', ':something'])
        proto_library(name = 'denied', srcs = [':descriptors', ':any'])
        """);
    scratch.file(
        "foo/BUILD",
        TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
        "proto_lang_toolchain(",
        "    name = 'toolchain',",
        "    command_line = '--java_out=param1,param2:$(OUT)',",
        "    plugin_format_flag = '--plugin=%s',",
        "    plugin = '//third_party/x:plugin',",
        "    runtime = '//third_party/x:runtime',",
        "    blacklisted_protos = ['//third_party/x:denied'],",
        "    progress_message = 'Progress Message %{label}',",
        "    mnemonic = 'MyMnemonic',",
        "    allowlist_different_package ="
            + " '//tools/allowlists/proto_library_allowlists:lang_proto_library_allowed_in_different_package'",
        ")",
        "proto_lang_toolchain(",
        "    name = 'toolchain_noplugin',",
        "    command_line = '--java_out=param1,param2:$(OUT)',",
        "    runtime = '//third_party/x:runtime',",
        "    blacklisted_protos = ['//third_party/x:denied'],",
        "    progress_message = 'Progress Message %{label}',",
        "    mnemonic = 'MyMnemonic',",
        ")");

    mockToolsConfig.overwrite(
        "tools/allowlists/proto_library_allowlists/BUILD",
        """
        package_group(
            name='lang_proto_library_allowed_in_different_package',
            packages=['//...', '-//test/...'],
        )
        """);

    scratch.file(
        "foo/generate.bzl",
        """
load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")
load("@com_google_protobuf//bazel/common:proto_common.bzl", "proto_common")
load("@com_google_protobuf//bazel/common:proto_lang_toolchain_info.bzl", "ProtoLangToolchainInfo")
def _resource_set_callback(os, inputs_size):
   return {'memory': 25 + 0.15 * inputs_size, 'cpu': 1}
def _impl(ctx):
  outfile = ctx.actions.declare_file('out')
  kwargs = {}
  if ctx.attr.plugin_output == 'single':
    kwargs['plugin_output'] = outfile.path
  elif ctx.attr.plugin_output == 'multiple':
    kwargs['plugin_output'] = ctx.genfiles_dir.path
  elif ctx.attr.plugin_output == 'wrong':
    kwargs['plugin_output'] = ctx.genfiles_dir.path + '///'
  if ctx.attr.additional_args:
    additional_args = ctx.actions.args()
    additional_args.add_all(ctx.attr.additional_args)
    kwargs['additional_args'] = additional_args
  if ctx.files.additional_tools:
    kwargs['additional_tools'] = ctx.files.additional_tools
  if ctx.files.additional_inputs:
    kwargs['additional_inputs'] = depset(ctx.files.additional_inputs)
  if ctx.attr.use_resource_set:
    kwargs['resource_set'] = _resource_set_callback
  if ctx.attr.progress_message:
    kwargs['experimental_progress_message'] = ctx.attr.progress_message
  proto_common.compile(
    ctx.actions,
    ctx.attr.proto_dep[ProtoInfo],
    ctx.attr.toolchain[ProtoLangToolchainInfo],
    [outfile],
    **kwargs)
  return [DefaultInfo(files = depset([outfile]))]
compile_rule = rule(_impl,
  attrs = {
     'proto_dep': attr.label(),
     'plugin_output': attr.string(),
     'toolchain': attr.label(default = '//foo:toolchain'),
     'additional_args': attr.string_list(),
     'additional_tools': attr.label_list(cfg = 'exec'),
     'additional_inputs': attr.label_list(allow_files = True),
     'use_resource_set': attr.bool(),
     'progress_message': attr.string(),
  })
""");

    scratch.file(
        "foo/should_generate.bzl",
        """
load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")
load("@com_google_protobuf//bazel/common:proto_common.bzl", "proto_common")
load("@com_google_protobuf//bazel/common:proto_lang_toolchain_info.bzl", "ProtoLangToolchainInfo")
BoolProvider = provider()
def _impl(ctx):
  result = proto_common.experimental_should_generate_code(
    ctx.attr.proto_dep[ProtoInfo],
    ctx.attr.toolchain[ProtoLangToolchainInfo],
    'MyRule',
    ctx.attr.proto_dep.label)
  return [BoolProvider(value = result)]
should_compile_rule = rule(_impl,
  attrs = {
     'proto_dep': attr.label(),
     'toolchain': attr.label(default = '//foo:toolchain'),
  })
""");

    scratch.file(
        "foo/declare_generated_files.bzl",
        """
        load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")
        load("@com_google_protobuf//bazel/common:proto_common.bzl", "proto_common")
        def _impl(ctx):
          files = proto_common.declare_generated_files(
            ctx.actions,
            ctx.attr.proto_dep[ProtoInfo],
            ctx.attr.extension,
            (lambda s: s.replace('-','_').replace('.','/')) if ctx.attr.python_names else None)
          for f in files:
            ctx.actions.write(f, '')
          return [DefaultInfo(files = depset(files))]
        declare_generated_files = rule(_impl,
          attrs = {
             'proto_dep': attr.label(),
             'extension': attr.string(),
             'python_names': attr.bool(default = False),
          })
        """);

    scratch.file(
        "foo/check_collocated.bzl",
        """
load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")
load("@com_google_protobuf//bazel/common:proto_common.bzl", "proto_common")
load("@com_google_protobuf//bazel/common:proto_lang_toolchain_info.bzl", "ProtoLangToolchainInfo")
def _impl(ctx):
  proto_common.check_collocated(
    ctx.label,
    ctx.attr.proto_dep[ProtoInfo],
    ctx.attr.toolchain[ProtoLangToolchainInfo])
  return None
check_collocated = rule(_impl,
  attrs = {
     'proto_dep': attr.label(),
     'toolchain': attr.label(default = '//foo:toolchain'),
  })
""");
  }

  // LINT.IfChange

  /** Verifies basic usage of <code>proto_common.compile</code>. */
  @Test
  public void protoCommonCompile_basic() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto')");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    List<String> cmdLine = spawnAction.getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly(
            "--plugin=bl?azel?-out/[^/]*-exec-[^/]*/bin/third_party/x/plugin", "-I.", "bar/A.proto")
        .inOrder();
    assertThat(spawnAction.getMnemonic()).isEqualTo("MyMnemonic");
    assertThat(spawnAction.getProgressMessage()).isEqualTo("Progress Message //bar:simple");
  }

  /** Verifies usage of proto_common.compile with no plugin specified by toolchain. */
  @Test
  public void protoCommonCompile_noPlugin() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto',",
        "  toolchain = '//foo:toolchain_noplugin')");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    List<String> cmdLine =
        getGeneratingSpawnAction(getBinArtifact("out", target)).getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly("-I.", "bar/A.proto")
        .inOrder();
  }

  /**
   * Verifies usage of <code>proto_common.compile</code> with <code>plugin_output</code> parameter
   * set to file.
   */
  @Test
  public void protoCommonCompile_withPluginOutput() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto', plugin_output = 'single')");
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));
    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    List<String> cmdLine =
        getGeneratingSpawnAction(getBinArtifact("out", target)).getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly(
            "--java_out=param1,param2:bl?azel?-out/k8-fastbuild/bin/bar/out",
            "--plugin=bl?azel?-out/[^/]*-exec/bin/third_party/x/plugin",
            "-I.",
            "bar/A.proto")
        .inOrder();
  }

  /**
   * Verifies usage of <code>proto_common.compile</code> with <code>plugin_output</code> parameter
   * set to directory.
   */
  @Test
  public void protoCommonCompile_withDirectoryPluginOutput() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto', plugin_output = 'multiple')");
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    List<String> cmdLine =
        getGeneratingSpawnAction(getBinArtifact("out", target)).getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly(
            "--java_out=param1,param2:bl?azel?-out/k8-fastbuild/bin",
            "--plugin=bl?azel?-out/[^/]*-exec/bin/third_party/x/plugin",
            "-I.",
            "bar/A.proto")
        .inOrder();
  }

  /**
   * Verifies usage of <code>proto_common.compile</code> with <code>additional_args</code>
   * parameter.
   */
  @Test
  public void protoCommonCompile_additionalArgs() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto', additional_args = ['--a', '--b'])");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    List<String> cmdLine =
        getGeneratingSpawnAction(getBinArtifact("out", target)).getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly(
            "--plugin=bl?azel?-out/[^/]*-exec-[^/]*/bin/third_party/x/plugin",
            "-I.",
            "bar/A.proto",
            "--a",
            "--b")
        .inOrder();
  }

  /**
   * Verifies usage of <code>proto_common.compile</code> with <code>additional_tools</code>
   * parameter.
   */
  @Test
  public void protoCommonCompile_additionalTools() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "cc_binary(name = 'tool1', srcs = ['tool1.cc'])",
        "cc_binary(name = 'tool2', srcs = ['tool2.cc'])",
        "compile_rule(name = 'simple', proto_dep = ':proto',",
        "  additional_tools = [':tool1', ':tool2'])");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    assertThat(prettyArtifactNames(spawnAction.getTools()))
        .containsAtLeast("bar/tool1", "bar/tool2", "third_party/x/plugin");
  }

  /**
   * Verifies usage of <code>proto_common.compile</code> with <code>additional_tools</code>
   * parameter and no plugin on the toolchain.
   */
  @Test
  public void protoCommonCompile_additionalToolsNoPlugin() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "cc_binary(name = 'tool1', srcs = ['tool1.cc'])",
        "cc_binary(name = 'tool2', srcs = ['tool2.cc'])",
        "compile_rule(name = 'simple',",
        "  proto_dep = ':proto',",
        "  additional_tools = [':tool1', ':tool2'],",
        "  toolchain = '//foo:toolchain_noplugin',",
        ")");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    assertThat(prettyArtifactNames(spawnAction.getTools()))
        .containsAtLeast("bar/tool1", "bar/tool2");
  }

  /**
   * Verifies usage of <code>proto_common.compile</code> with <code>additional_inputs</code>
   * parameter.
   */
  @Test
  public void protoCommonCompile_additionalInputs() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto',",
        "  additional_inputs = [':input1.txt', ':input2.txt'])");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    assertThat(prettyArtifactNames(spawnAction.getInputs()))
        .containsAtLeast("bar/input1.txt", "bar/input2.txt");
  }

  /**
   * Verifies usage of <code>proto_common.compile</code> with <code>resource_set</code> parameter.
   */
  @Test
  public void protoCommonCompile_resourceSet() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto', use_resource_set = True)");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    assertThat(spawnAction.getResourceSetOrBuilder().buildResourceSet(OS.DARWIN, 0))
        .isEqualTo(ResourceSet.createWithRamCpu(25, 1));
    assertThat(spawnAction.getResourceSetOrBuilder().buildResourceSet(OS.LINUX, 2))
        .isEqualTo(ResourceSet.createWithRamCpu(25.3, 1));
  }

  /** Verifies <code>--protocopts</code> are passed to command line. */
  @Test
  public void protoCommonCompile_protocOpts() throws Exception {
    useConfiguration("--protocopt=--foo", "--protocopt=--bar");
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto')");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    List<String> cmdLine = spawnAction.getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly(
            "--plugin=bl?azel?-out/[^/]*-exec-[^/]*/bin/third_party/x/plugin",
            "-I.",
            "--foo",
            "--bar",
            "bar/A.proto")
        .inOrder();
  }

  /**
   * Verifies <code>proto_common.compile</code> correctly handles direct generated <code>
   * .proto</code> files.
   */
  @Test
  public void protoCommonCompile_directGeneratedProtos() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "genrule(name = 'generate', srcs = ['A.txt'], cmd = '', outs = ['G.proto'])",
        "proto_library(name = 'proto', srcs = ['A.proto', 'G.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto')");
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    List<String> cmdLine = spawnAction.getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly(
            "--plugin=bl?azel?-out/[^/]*-exec/bin/third_party/x/plugin",
            "-Ibl?azel?-out/k8-fastbuild/bin",
            "-I.",
            "bar/A.proto",
            "bl?azel?-out/k8-fastbuild/bin/bar/G.proto")
        .inOrder();
  }

  /**
   * Verifies <code>proto_common.compile</code> correctly handles in-direct generated <code>
   * .proto</code> files.
   */
  @Test
  public void protoCommonCompile_inDirectGeneratedProtos() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "genrule(name = 'generate', srcs = ['A.txt'], cmd = '', outs = ['G.proto'])",
        "proto_library(name = 'generated', srcs = ['G.proto'])",
        "proto_library(name = 'proto', srcs = ['A.proto'], deps = [':generated'])",
        "compile_rule(name = 'simple', proto_dep = ':proto')");

    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    List<String> cmdLine = spawnAction.getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly(
            "--plugin=bl?azel?-out/[^/]*-exec/bin/third_party/x/plugin",
            "-Ibl?azel?-out/k8-fastbuild/bin",
            "-I.",
            "bar/A.proto")
        .inOrder();
  }

  // LINT.ThenChange(@com_google_protobuf/bazel/tests/proto_common_compile_tests.bzl)

  /**
   * Verifies <code>proto_common.compile</code> correctly handles external <code>proto_library
   * </code>-es.
   */
  @Test
  @TestParameters({
    "{sibling: false, generated: false, expectedFlags:" + " ['-Iexternal/foo\\+']}",
    "{sibling: false, generated: true, expectedFlags:"
        + " ['-Ibl?azel?-out/k8-fastbuild/bin/external/foo\\+']}",
    "{sibling: true, generated: false,expectedFlags:" + " ['-I../foo\\+']}",
    "{sibling: true, generated: true, expectedFlags:"
        + " ['-Ibl?azel?-out/foo\\+/k8-fastbuild/bin']}",
  })
  public void protoCommonCompile_externalProtoLibrary(
      boolean sibling, boolean generated, List<String> expectedFlags) throws Exception {
    if (!analysisMock.isThisBazel()) {
      return;
    }
    if (sibling) {
      setBuildLanguageOptions("--experimental_sibling_repository_layout");
    }
    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'foo')",
        "local_path_override(module_name = 'foo', path = '/foo')");
    scratch.file("/foo/MODULE.bazel", "module(name = 'foo')");
    scratch.file(
        "/foo/e/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='e', srcs=['E.proto'])",
        generated
            ? "genrule(name = 'generate', srcs = ['A.txt'], cmd = '', outs = ['E.proto'])"
            : "");
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'], deps = ['@foo//e:e'])",
        "compile_rule(name = 'simple', proto_dep = ':proto')");
    invalidatePackages();
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    List<String> cmdLine = spawnAction.getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly(
            "--plugin=bl?azel?-out/[^/]*-exec/bin/third_party/x/plugin",
            expectedFlags.get(0),
            "-I.",
            "bar/A.proto")
        .inOrder();
  }

  /** Verifies <code>experimental_progress_message</code> parameters. */
  @Test
  public void protoCommonCompile_overrideProgressMessage() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:generate.bzl', 'compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "compile_rule(name = 'simple', proto_dep = ':proto', progress_message = 'My %{label}')");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    SpawnAction spawnAction = getGeneratingSpawnAction(getBinArtifact("out", target));
    List<String> cmdLine = spawnAction.getRemainingArguments();
    assertThat(cmdLine)
        .comparingElementsUsing(MATCHES_REGEX)
        .containsExactly(
            "--plugin=bl?azel?-out/[^/]*-exec-[^/]*/bin/third_party/x/plugin", "-I.", "bar/A.proto")
        .inOrder();
    assertThat(spawnAction.getMnemonic()).isEqualTo("MyMnemonic");
    assertThat(spawnAction.getProgressMessage()).isEqualTo("My //bar:simple");
  }

  /** Verifies <code>proto_common.should_generate_code</code> call. */
  @Test
  public void shouldprotoCommonCompile_basic() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:should_generate.bzl', 'should_compile_rule')",
        "proto_library(name = 'proto', srcs = ['A.proto'])",
        "should_compile_rule(name = 'simple', proto_dep = ':proto')");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    StarlarkInfo boolProvider = (StarlarkInfo) target.get(boolProviderId);
    assertThat(boolProvider.getValue("value", Boolean.class)).isTrue();
  }

  /** Verifies <code>proto_common.should_generate_code</code> call. */
  @Test
  public void shouldprotoCommonCompile_dontGenerate() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:should_generate.bzl', 'should_compile_rule')",
        "should_compile_rule(name = 'simple', proto_dep = '//third_party/x:denied')");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    StarlarkInfo boolProvider = (StarlarkInfo) target.get(boolProviderId);
    assertThat(boolProvider.getValue("value", Boolean.class)).isFalse();
  }

  /** Verifies <code>proto_common.should_generate_code</code> call. */
  @Test
  public void shouldprotoCommonCompile_mixed() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:should_generate.bzl', 'should_compile_rule')",
        "should_compile_rule(name = 'simple', proto_dep = '//third_party/x:mixed')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//bar:simple");

    assertContainsEvent(
        "The 'srcs' attribute of '@@//third_party/x:mixed' contains protos for which 'MyRule'"
            + " shouldn't generate code (third_party/x/metadata.proto,"
            + " third_party/x/descriptor.proto), in addition to protos for which it should"
            + " (third_party/x/something.proto).\n"
            + "Separate '@@//third_party/x:mixed' into 2 proto_library rules.");
  }

  /** Verifies <code>proto_common.declare_generated_files</code> call. */
  @Test
  public void declareGenerateFiles_basic() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:declare_generated_files.bzl', 'declare_generated_files')",
        "proto_library(name = 'proto', srcs = ['A.proto', 'b/B.proto'])",
        "declare_generated_files(name = 'simple', proto_dep = ':proto', extension = '.cc')");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    assertThat(prettyArtifactNames(target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("bar/A.cc", "bar/b/B.cc");
  }

  /** Verifies <code>proto_common.declare_generated_files</code> call for Python. */
  @Test
  public void declareGenerateFiles_pythonc() throws Exception {
    scratch.file(
        "bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "load('//foo:declare_generated_files.bzl', 'declare_generated_files')",
        "proto_library(name = 'proto', srcs = ['my-proto.gen.proto'])",
        "declare_generated_files(name = 'simple', proto_dep = ':proto', extension = '_pb2.py',",
        "  python_names = True)");

    ConfiguredTarget target = getConfiguredTarget("//bar:simple");

    assertThat(prettyArtifactNames(target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("bar/my_proto/gen_pb2.py");
  }

  @Test
  public void langProtoLibrary_inDifferentPackage_allowed() throws Exception {
    scratch.file(
        "proto/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name = 'proto', srcs = ['A.proto'])");
    scratch.file(
        "bar/BUILD",
        """
        load('//foo:check_collocated.bzl', 'check_collocated')
        check_collocated(name = 'simple', proto_dep = '//proto:proto')
        """);

    getConfiguredTarget("//bar:simple");

    assertNoEvents();
  }

  @Test
  public void langProtoLibrary_inDifferentPackage_fails() throws Exception {
    scratch.file(
        "proto/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name = 'proto', srcs = ['A.proto'])");
    scratch.file(
        "test/BUILD",
        """
        load('//foo:check_collocated.bzl', 'check_collocated')
        check_collocated(name = 'simple', proto_dep = '//proto:proto')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:simple");

    assertContainsEvent(
        "lang_proto_library '@@//test:simple' may only be created in the same package as"
            + " proto_library '@@//proto:proto'");
  }

  @Test
  public void langProtoLibrary_exportNotAllowed() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
        proto_library(name='foo', srcs=['foo.proto'], allow_exports = ':test')
        package_group(
            name='test',
            packages=['//allowed'],
        )
        """);
    scratch.file(
        "notallowed/BUILD",
        """
        load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
        load('//foo:check_collocated.bzl', 'check_collocated')
        check_collocated(name = 'simple', proto_dep = '//x:foo')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//notallowed:simple");

    assertContainsEvent(
        "lang_proto_library '@@//notallowed:simple' may only be created in the same package as"
            + " proto_library '@@//x:foo'");
  }

  @Test
  public void langProtoLibrary_exportAllowed() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
        proto_library(name='foo', srcs=['foo.proto'], allow_exports = ':test')
        package_group(
            name='test',
            packages=['//allowed'],
        )
        """);
    scratch.file(
        "allowed/BUILD",
        """
        load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
        load('//foo:check_collocated.bzl', 'check_collocated')
        check_collocated(name = 'simple', proto_dep = '//x:foo')
        """);

    getConfiguredTarget("//allowed:simple");

    assertNoEvents();
  }
}
