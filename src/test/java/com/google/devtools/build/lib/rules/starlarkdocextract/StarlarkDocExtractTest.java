// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.starlarkdocextract;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static com.google.devtools.build.lib.starlarkdocextract.RuleInfoExtractor.IMPLICIT_RULE_ATTRIBUTES;
import static com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_ORDINARY;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.actions.BinaryFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.starlarkdocextract.ModuleInfoExtractor;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AspectInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeType;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ModuleExtensionInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ModuleExtensionTagClassInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ModuleInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.OriginKey;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ProviderInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ProviderNameGroup;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.RepositoryRuleInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.RuleInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.StarlarkFunctionInfo;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.TextFormat;
import java.util.NoSuchElementException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StarlarkDocExtractTest extends BuildViewTestCase {

  private static ModuleInfo protoFromBinaryFileWriteAction(Action action) throws Exception {
    assertThat(action).isInstanceOf(BinaryFileWriteAction.class);
    return ModuleInfo.parseFrom(
        ((BinaryFileWriteAction) action).getSource().openStream(),
        ExtensionRegistry.getEmptyRegistry());
  }

  private static ModuleInfo protoFromTextFileWriteAction(Action action) throws Exception {
    assertThat(action).isInstanceOf(FileWriteAction.class);
    return TextFormat.parse(
        ((FileWriteAction) action).getFileContents(),
        ExtensionRegistry.getEmptyRegistry(),
        ModuleInfo.class);
  }

  private ModuleInfo protoFromConfiguredTarget(String targetName) throws Exception {
    ConfiguredTarget target = getConfiguredTarget(targetName);
    Label targetLabel = Label.parseCanonicalUnchecked(targetName);
    String outputName = targetLabel.toPathFragment().getPathString() + ".binaryproto";
    if (!targetLabel.getRepository().isMain()) {
      outputName =
          String.format("external/%s/%s", targetLabel.getRepository().getName(), outputName);
    }
    return protoFromBinaryFileWriteAction(getGeneratingAction(target, outputName));
  }

  @Before
  public void setUpBzlLibrary() throws Exception {
    // TODO(https://github.com/bazelbuild/bazel/issues/18599): get rid of this when we bundle
    // bzl_library with Bazel.
    scratch.file(
        "bzl_library.bzl",
        """
        def _bzl_library_impl(ctx):
            deps_files = [x.files for x in ctx.attr.deps]
            all_files = depset(ctx.files.srcs, order = "postorder", transitive = deps_files)
            return DefaultInfo(files = all_files)

        bzl_library = rule(
            implementation = _bzl_library_impl,
            attrs = {
                "srcs": attr.label_list(allow_files = [".bzl", ".scl"]),
                "deps": attr.label_list(),
            },
        )
        """);
  }

  @Test
  public void basicFunctionality() throws Exception {
    scratch.file(
        "foo.bzl",
        """
        '''Module doc string'''
        True
        """);
    scratch.file(
        "BUILD",
        """
        starlark_doc_extract(
            name = "extract",
            src = "foo.bzl",
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//:extract");
    ModuleInfo moduleInfo =
        protoFromBinaryFileWriteAction(getGeneratingAction(target, "extract.binaryproto"));
    assertThat(moduleInfo.getModuleDocstring()).isEqualTo("Module doc string");
    assertThat(moduleInfo.getFile()).isEqualTo("//:foo.bzl");
  }

  @Test
  public void textprotoOut() throws Exception {
    scratch.file(
        "foo.bzl",
        """
        '''Module doc string'''
        True
        """);
    scratch.file(
        "BUILD",
        """
        starlark_doc_extract(
            name = "extract",
            src = "foo.bzl",
        )
        """);
    ConfiguredTarget ruleTarget = getConfiguredTarget("//:extract");
    // Verify that we do not generate textproto output unless explicitly requested.
    assertThrows(
        NoSuchElementException.class, () -> getGeneratingAction(ruleTarget, "extract.textproto"));

    ConfiguredTarget textprotoOutputTarget = getConfiguredTarget("//:extract.textproto");
    ModuleInfo moduleInfo =
        protoFromTextFileWriteAction(
            getGeneratingAction(textprotoOutputTarget, "extract.textproto"));
    assertThat(moduleInfo.getModuleDocstring()).isEqualTo("Module doc string");
  }

  @Test
  public void sclDialect() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect");
    scratch.file(
        "foo.scl",
        """
        def f():
            '''This is my function'''
            pass
        """);
    scratch.file(
        "bar.scl",
        """
        '''My scl module string'''
        load("//:foo.scl", "f")

        bar_f = f
        """);
    scratch.file(
        "BUILD",
        """
        load("bzl_library.bzl", "bzl_library")

        bzl_library(
            name = "foo_scl",
            srcs = ["foo.scl"],
        )

        starlark_doc_extract(
            name = "extract",
            src = "bar.scl",
            deps = ["foo_scl"],
        )
        """);
    ModuleInfo moduleInfo = protoFromConfiguredTarget("//:extract");
    assertThat(moduleInfo.getModuleDocstring()).isEqualTo("My scl module string");
    assertThat(moduleInfo.getFile()).isEqualTo("//:bar.scl");
    assertThat(moduleInfo.getFuncInfo(0).getDocString()).isEqualTo("This is my function");
  }

  @Test
  public void sourceWithSyntaxError_fails() throws Exception {
    scratch.file(
        "error.bzl", //
        "!!!");
    scratch.file(
        "error_loader.bzl",
        """
        '''This is my module'''
        load("error.bzl", "x")
        """);
    scratch.file(
        "BUILD",
        """
        load("bzl_library.bzl", "bzl_library")

        bzl_library(
            name = "error_bzl",
            srcs = ["error.bzl"],
        )

        starlark_doc_extract(
            name = "error_doc",
            src = "error.bzl",
        )

        starlark_doc_extract(
            name = "error_loader_doc",
            src = "error_loader.bzl",
            deps = ["error_bzl"],
        )
        """);

    AssertionError errorDocFailure =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//:error_doc"));
    assertThat(errorDocFailure).hasMessageThat().contains("invalid character: '!'");

    AssertionError errorLoaderDocFailure =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//:error_loader_doc"));
    assertThat(errorLoaderDocFailure).hasMessageThat().contains("invalid character: '!'");
  }

  @Test
  public void symbolNames() throws Exception {
    scratch.file(
        "foo.bzl",
        """
        def func1():
            pass

        def func2():
            pass

        def _hidden():
            pass
        """);
    scratch.file(
        "BUILD",
        """
        starlark_doc_extract(
            name = "extract_some",
            src = "foo.bzl",
            symbol_names = ["func1"],
        )

        starlark_doc_extract(
            name = "extract_all",
            src = "foo.bzl",
        )
        """);

    ModuleInfo dumpSome = protoFromConfiguredTarget("//:extract_some");
    assertThat(dumpSome.getFuncInfoList().stream().map(StarlarkFunctionInfo::getFunctionName))
        .containsExactly("func1");

    ModuleInfo dumpAll = protoFromConfiguredTarget("//:extract_all");
    assertThat(dumpAll.getFuncInfoList().stream().map(StarlarkFunctionInfo::getFunctionName))
        .containsExactly("func1", "func2");
  }

  @Test
  public void originKey() throws Exception {
    scratch.file(
        "origin.bzl",
        """
        def my_macro():
            pass

        MyInfo = provider()
        MyOtherInfo = provider()
        my_rule = rule(
            implementation = lambda ctx: None,
            attrs = {"a": attr.label(providers = [MyInfo, MyOtherInfo])},
            provides = [MyInfo, MyOtherInfo],
        )
        my_aspect = aspect(implementation = lambda target, ctx: None)
        """);
    scratch.file(
        "renamer.bzl",
        """
        load(":origin.bzl", "MyInfo", "MyOtherInfo", "my_aspect", "my_macro", "my_rule")

        namespace = struct(
            renamed_macro = my_macro,
            RenamedInfo = MyInfo,
            renamed_rule = my_rule,
            renamed_aspect = my_aspect,
        )
        other_namespace = struct(
            RenamedOtherInfo = MyOtherInfo,
        )
        """);
    scratch.file(
        "BUILD",
        """
        load("bzl_library.bzl", "bzl_library")

        bzl_library(
            name = "origin_bzl",
            srcs = ["origin.bzl"],
        )

        starlark_doc_extract(
            name = "extract_renamed",
            src = "renamer.bzl",
            deps = ["origin_bzl"],
            symbol_names = ["namespace"],
        )
        """);

    ModuleInfo moduleInfo = protoFromConfiguredTarget("//:extract_renamed");

    assertThat(moduleInfo.getFuncInfoList().stream().map(StarlarkFunctionInfo::getFunctionName))
        .containsExactly("namespace.renamed_macro");
    assertThat(moduleInfo.getFuncInfoList().stream().map(StarlarkFunctionInfo::getOriginKey))
        .containsExactly(
            OriginKey.newBuilder().setName("my_macro").setFile("//:origin.bzl").build());

    assertThat(moduleInfo.getProviderInfoList().stream().map(ProviderInfo::getProviderName))
        .containsExactly("namespace.RenamedInfo");
    assertThat(moduleInfo.getProviderInfoList().stream().map(ProviderInfo::getOriginKey))
        .containsExactly(OriginKey.newBuilder().setName("MyInfo").setFile("//:origin.bzl").build());

    assertThat(moduleInfo.getRuleInfoList().stream().map(RuleInfo::getRuleName))
        .containsExactly("namespace.renamed_rule");
    assertThat(moduleInfo.getRuleInfoList().stream().map(RuleInfo::getOriginKey))
        .containsExactly(
            OriginKey.newBuilder().setName("my_rule").setFile("//:origin.bzl").build());

    assertThat(moduleInfo.getRuleInfo(0).getAttributeList())
        .isEqualTo(
            ImmutableList.builder()
                .addAll(IMPLICIT_RULE_ATTRIBUTES.values())
                .add(
                    AttributeInfo.newBuilder()
                        .setName("a")
                        .setType(AttributeType.LABEL)
                        .setDefaultValue("None")
                        .addProviderNameGroup(
                            ProviderNameGroup.newBuilder()
                                .addProviderName("namespace.RenamedInfo")
                                .addProviderName("other_namespace.RenamedOtherInfo")
                                .addOriginKey(
                                    OriginKey.newBuilder()
                                        .setName("MyInfo")
                                        .setFile("//:origin.bzl"))
                                .addOriginKey(
                                    OriginKey.newBuilder()
                                        .setName("MyOtherInfo")
                                        .setFile("//:origin.bzl")))
                        .build())
                .build());
    assertThat(moduleInfo.getRuleInfo(0).getAdvertisedProviders())
        .isEqualTo(
            ProviderNameGroup.newBuilder()
                .addProviderName("namespace.RenamedInfo")
                .addProviderName("other_namespace.RenamedOtherInfo")
                .addOriginKey(OriginKey.newBuilder().setName("MyInfo").setFile("//:origin.bzl"))
                .addOriginKey(
                    OriginKey.newBuilder().setName("MyOtherInfo").setFile("//:origin.bzl"))
                .build());

    assertThat(moduleInfo.getAspectInfoList().stream().map(AspectInfo::getAspectName))
        .containsExactly("namespace.renamed_aspect");
    assertThat(moduleInfo.getAspectInfoList().stream().map(AspectInfo::getOriginKey))
        .containsExactly(
            OriginKey.newBuilder().setName("my_aspect").setFile("//:origin.bzl").build());
  }

  private static AttributeInfo getFirstRuleFirstAttr(ModuleInfo moduleInfo) {
    // Attribute 0 is the implicit `name` attribute
    return moduleInfo.getRuleInfo(0).getAttribute(1);
  }

  @Test
  public void originKeyFileAndModuleInfoFileLabels_forBzlFileInBzlmodModule_areDisplayForm()
      throws Exception {
    scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='origin_repo', version='0.1')");
    registry.addModule(
        BzlmodTestUtil.createModuleKey("origin_repo", "0.1"),
        "module(name='origin_repo', version='0.1')");
    Path originRepoPath = moduleRoot.getRelative("origin_repo+0.1");
    scratch.file(originRepoPath.getRelative("REPO.bazel").getPathString());
    scratch.file(
        originRepoPath.getRelative("BUILD").getPathString(), //
        "exports_files(['origin.bzl'])");
    scratch.file(
        originRepoPath.getRelative("origin.bzl").getPathString(),
        """
        def my_macro():
            pass

        MyInfo = provider()
        my_rule = rule(
            implementation = lambda ctx: None,
            attrs = {"a": attr.label(providers = [MyInfo])},
            provides = [MyInfo],
        )
        my_aspect = aspect(implementation = lambda target, ctx: None)
        """);
    scratch.file(
        "renamer.bzl",
        """
        load("@origin_repo//:origin.bzl", "MyInfo", "my_aspect", "my_macro", "my_rule")

        namespace = struct(
            renamed_macro = my_macro,
            RenamedInfo = MyInfo,
            renamed_rule = my_rule,
            renamed_aspect = my_aspect,
        )
        """);
    scratch.file(
        "BUILD",
        """
        load("bzl_library.bzl", "bzl_library")

        bzl_library(
            name = "origin_bzl",
            srcs = ["@origin_repo//:origin.bzl"],
        )

        starlark_doc_extract(
            name = "extract_origin",
            src = "@origin_repo//:origin.bzl",
        )

        starlark_doc_extract(
            name = "extract_renamed",
            src = "renamer.bzl",
            deps = ["origin_bzl"],
        )
        """);
    invalidatePackages();

    // verify that ModuleInfo.name for a .bzl file in another bzlmod module is in display form, i.e.
    // "@origin_repo//:origin.bzl" as opposed to "@@origin_repo+0.1//:origin.bzl"
    ModuleInfo originModuleInfo = protoFromConfiguredTarget("//:extract_origin");
    assertThat(originModuleInfo.getFile()).isEqualTo("@origin_repo//:origin.bzl");

    // verify that OriginKey.name for entities defined in a .bzl file in another bzlmod module is in
    // display form, i.e. "@origin_repo//:origin.bzl" as opposed to "@@origin_repo+0.1//:origin.bzl"
    ModuleInfo renamedModuleInfo = protoFromConfiguredTarget("//:extract_renamed");
    assertThat(renamedModuleInfo.getFile()).isEqualTo("//:renamer.bzl");
    assertThat(renamedModuleInfo.getFuncInfo(0).getOriginKey().getFile())
        .isEqualTo("@origin_repo//:origin.bzl");
    assertThat(renamedModuleInfo.getProviderInfo(0).getOriginKey().getFile())
        .isEqualTo("@origin_repo//:origin.bzl");
    assertThat(renamedModuleInfo.getAspectInfo(0).getOriginKey().getFile())
        .isEqualTo("@origin_repo//:origin.bzl");
    assertThat(renamedModuleInfo.getRuleInfo(0).getOriginKey().getFile())
        .isEqualTo("@origin_repo//:origin.bzl");
    assertThat(
            getFirstRuleFirstAttr(renamedModuleInfo)
                .getProviderNameGroup(0)
                .getOriginKey(0)
                .getFile())
        .isEqualTo("@origin_repo//:origin.bzl");
    assertThat(renamedModuleInfo.getRuleInfo(0).getAdvertisedProviders().getOriginKey(0).getFile())
        .isEqualTo("@origin_repo//:origin.bzl");
  }

  @Test
  public void exportNestedFunctionsAndLambdas() throws Exception {
    scratch.file(
        "origin.bzl",
        """
        def return_nested():
            def nested(x):
                '''My nested function'''
                pass

            return nested

        def return_lambda():
            return lambda y: y
        """);
    scratch.file(
        "exporter.bzl",
        """
        load(":origin.bzl", "return_lambda", "return_nested")

        exported_nested = return_nested()
        exported_lambda = return_lambda()
        """);
    scratch.file(
        "BUILD",
        """
        load("bzl_library.bzl", "bzl_library")

        bzl_library(
            name = "origin_bzl",
            srcs = ["origin.bzl"],
        )

        starlark_doc_extract(
            name = "extract_exporter",
            src = "exporter.bzl",
            deps = ["origin_bzl"],
        )
        """);

    ModuleInfo moduleInfo = protoFromConfiguredTarget("//:extract_exporter");

    assertThat(moduleInfo.getFuncInfoList())
        .containsExactly(
            StarlarkFunctionInfo.newBuilder()
                .setFunctionName("exported_nested")
                .setDocString("My nested function")
                .addParameter(
                    FunctionParamInfo.newBuilder()
                        .setName("x")
                        .setRole(PARAM_ROLE_ORDINARY)
                        .setMandatory(true))
                .setOriginKey(
                    // OriginKey.name for nested functions is explicitly unset
                    OriginKey.newBuilder().setFile("//:origin.bzl"))
                .build(),
            StarlarkFunctionInfo.newBuilder()
                .setFunctionName("exported_lambda")
                .addParameter(
                    FunctionParamInfo.newBuilder()
                        .setName("y")
                        .setRole(PARAM_ROLE_ORDINARY)
                        .setMandatory(true))
                .setOriginKey(
                    // OriginKey.name for lambdas is explicitly unset
                    OriginKey.newBuilder().setFile("//:origin.bzl"))
                .build());
  }

  @Test
  public void missingBzlLibraryDeps_fails() throws Exception {
    scratch.file(
        "dep.bzl",
        """
        load("//:forgotten_dep_of_dep.bzl", "g")

        def f():
            pass
        """);
    scratch.file(
        "forgotten_dep_of_dep.bzl", //
        "def g(): pass");
    scratch.file(
        "forgotten_dep.bzl",
        """
        load("//:forgotten_dep_of_forgotten_dep.bzl", "j")

        def h():
            pass
        """);
    scratch.file(
        "forgotten_dep2.bzl", //
        "def i(): pass");
    scratch.file(
        "forgotten_dep_of_forgotten_dep.bzl", //
        "def j(): pass");
    scratch.file(
        "foo.bzl",
        """
        load("//:dep.bzl", "f")
        load("//:forgotten_dep.bzl", "h")
        load("//:forgotten_dep2.bzl", "i")
        """);
    scratch.file(
        "BUILD",
        """
        load("bzl_library.bzl", "bzl_library")

        bzl_library(
            name = "dep_bzl",
            srcs = ["dep.bzl"],
        )

        starlark_doc_extract(
            name = "extract",
            src = "foo.bzl",  # Note that src does not need to be part of deps
            deps = ["dep_bzl"],
        )
        """);

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//:extract"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "missing bzl_library targets for Starlark module(s) //:forgotten_dep_of_dep.bzl,"
                + " //:forgotten_dep.bzl, //:forgotten_dep2.bzl");
    // We do not want to log transitive deps of already missing deps in the error message - it would
    // be hard to read and unnecessary, since a valid bzl_library target should bring in its
    // transitive deps.
    assertThat(e).hasMessageThat().doesNotContain("forgotten_dep_of_forgotten_dep.bzl");
  }

  @Test
  public void depsWithDerivedFiles_onUnknownLoads_failsAndPrintsDerivedFiles() throws Exception {
    scratch.file("BUILD");
    scratch.file(
        "pkg/source_file_masked_by_rule_name.bzl", //
        "def f(): pass");
    scratch.file(
        "pkg/source_file_masked_by_rule_output_name.bzl", //
        "def g(): pass");
    scratch.file(
        "pkg/foo.bzl",
        """
        load("//pkg:source_file_masked_by_rule_name.bzl", "f")
        load("//pkg:source_file_masked_by_rule_output_name.bzl", "g")
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//:bzl_library.bzl", "bzl_library")

        genrule(
            name = "source_file_masked_by_rule_name.bzl",
            outs = ["some_output.bzl"],
            cmd = "touch $@",
        )

        genrule(
            name = "source_file_masked_by_rule_output_name_bzl_generator",
            outs = ["source_file_masked_by_rule_output_name.bzl"],
            cmd = "touch $@",
        )

        genrule(
            name = "some_rule",
            outs = ["ordinary_generated_file.bzl"],
            cmd = "touch $@",
        )

        bzl_library(
            name = "deps_bzl",
            srcs = [
                "source_file_masked_by_rule_name.bzl",
                "source_file_masked_by_rule_output_name.bzl",
                "ordinary_generated_file.bzl",
            ],
        )

        starlark_doc_extract(
            name = "extract",
            src = "foo.bzl",
            deps = ["deps_bzl"],
        )
        """);

    AssertionError error =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//pkg:extract"));
    assertThat(error)
        .hasMessageThat()
        .contains(
            "missing bzl_library targets for Starlark module(s)"
                + " //pkg:source_file_masked_by_rule_name.bzl,"
                + " //pkg:source_file_masked_by_rule_output_name.bzl\n"
                + "Note the following are generated file(s) and cannot be loaded in Starlark:"
                + " pkg/some_output.bzl (generated by rule"
                + " //pkg:source_file_masked_by_rule_name.bzl),"
                + " pkg/source_file_masked_by_rule_output_name.bzl (generated by rule"
                + " //pkg:source_file_masked_by_rule_output_name_bzl_generator),"
                + " pkg/ordinary_generated_file.bzl (generated by rule //pkg:some_rule)");
  }

  @Test
  public void depsWithDerivedFiles_onNoUnknownLoads_succeeds() throws Exception {
    scratch.file("BUILD");
    scratch.file(
        "util.bzl",
        """
        def _impl(ctx):
            out = ctx.actions.declare_file(ctx.attr.out)
            ctx.actions.run_shell(command = "touch $1", arguments = [out.path], outputs = [out])
            return DefaultInfo(files = depset([out]), runfiles = ctx.runfiles([out]))

        generate_out_without_declaring_it_as_a_target = rule(
            attrs = {"out": attr.string()},
            implementation = _impl,
        )
        """);
    scratch.file(
        "pkg/source_dep.bzl", //
        "def f(): pass");
    scratch.file(
        "pkg/foo.bzl", //
        "load('//pkg:source_dep.bzl', 'f')");
    scratch.file(
        "pkg/BUILD",
        """
        load("//:bzl_library.bzl", "bzl_library")
        load("//:util.bzl", "generate_out_without_declaring_it_as_a_target")

        genrule(
            name = "some_rule",
            outs = ["declared_derived_dep.bzl"],
            cmd = "touch $@",
        )

        # //pkg:generate_source_dep_without_declaring_it_as_a_target masks the source_dep.bzl
        # source artifact with a non-target, derived artifact having the same root-relative path.
        generate_out_without_declaring_it_as_a_target(
            name = "generate_source_dep_without_declaring_it_as_a_target",
            out = "source_dep.bzl",
        )

        bzl_library(
            name = "deps_bzl",
            srcs = [
                "declared_derived_dep.bzl",
                "source_dep.bzl",
                "generate_source_dep_without_declaring_it_as_a_target",
            ],
        )

        starlark_doc_extract(
            name = "extract",
            src = "foo.bzl",
            deps = ["deps_bzl"],
        )
        """);

    getConfiguredTarget("//pkg:extract");
    assertNoEvents();
  }

  @Test
  public void srcDerivedFile_fails() throws Exception {
    scratch.file("BUILD");
    scratch.file("pkg/source_file_masked_by_rule_name.bzl");
    scratch.file("pkg/source_file_masked_by_rule_output_name.bzl");
    scratch.file(
        "pkg/BUILD",
        """
        genrule(
            name = "source_file_masked_by_rule_name.bzl",
            outs = ["some_output.bzl"],
            cmd = "touch $@",
        )

        genrule(
            name = "source_file_masked_by_rule_output_name_bzl_generator",
            outs = ["source_file_masked_by_rule_output_name.bzl"],
            cmd = "touch $@",
        )

        genrule(
            name = "some_rule",
            outs = ["ordinary_generated_file.bzl"],
            cmd = "touch $@",
        )

        starlark_doc_extract(
            name = "source_file_masked_by_rule_name_doc",
            src = "source_file_masked_by_rule_name.bzl",
        )

        starlark_doc_extract(
            name = "source_file_masked_by_rule_output_name_doc",
            src = "source_file_masked_by_rule_output_name.bzl",
        )

        starlark_doc_extract(
            name = "ordinary_generated_file_doc",
            src = "ordinary_generated_file.bzl",
        )
        """);

    AssertionError maskedByRuleError =
        assertThrows(
            AssertionError.class,
            () -> getConfiguredTarget("//pkg:source_file_masked_by_rule_name_doc"));
    assertThat(maskedByRuleError)
        .hasMessageThat()
        .contains(
            "pkg/some_output.bzl (generated by rule //pkg:source_file_masked_by_rule_name.bzl)"
                + " is not a source file and cannot be loaded in Starlark");

    AssertionError maskedByRuleOutputError =
        assertThrows(
            AssertionError.class,
            () -> getConfiguredTarget("//pkg:source_file_masked_by_rule_output_name_doc"));
    assertThat(maskedByRuleOutputError)
        .hasMessageThat()
        .contains(
            "pkg/source_file_masked_by_rule_output_name.bzl (generated by rule"
                + " //pkg:source_file_masked_by_rule_output_name_bzl_generator) is not a source"
                + " file and cannot be loaded in Starlark");

    AssertionError ordinaryGeneratedFileError =
        assertThrows(
            AssertionError.class, () -> getConfiguredTarget("//pkg:ordinary_generated_file_doc"));
    assertThat(ordinaryGeneratedFileError)
        .hasMessageThat()
        .contains(
            "pkg/ordinary_generated_file.bzl (generated by rule //pkg:some_rule) is not a source"
                + " file and cannot be loaded in Starlark");
  }

  @Test
  public void srcAlias_resolvesToActual() throws Exception {
    scratch.file("alias_name.bzl");
    scratch.file("alias_actual.bzl");
    scratch.file(
        "BUILD",
        """
        alias(
            name = "alias_name.bzl",
            actual = "alias_actual.bzl",
        )

        starlark_doc_extract(
            name = "extract",
            src = "alias_name.bzl",
        )
        """);

    ModuleInfo moduleInfo = protoFromConfiguredTarget("//:extract");
    assertThat(moduleInfo.getFile()).isEqualTo("//:alias_actual.bzl");
  }

  @Test
  public void srcFilegroup_resolvesToFilegroupSrc() throws Exception {
    scratch.file("masked_by_filegroup_name.bzl");
    scratch.file("filegroup_src_actual.bzl");
    scratch.file(
        "BUILD",
        """
        filegroup(
            name = "masked_by_filegroup_name.bzl",
            srcs = ["filegroup_src_actual.bzl"],
        )

        starlark_doc_extract(
            name = "extract",
            src = "masked_by_filegroup_name.bzl",
        )
        """);

    ModuleInfo moduleInfo = protoFromConfiguredTarget("//:extract");
    assertThat(moduleInfo.getFile()).isEqualTo("//:filegroup_src_actual.bzl");
  }

  @Test
  public void srcFilegroup_mustHaveSingleSrc() throws Exception {
    scratch.file("foo.bzl");
    scratch.file("bar.bzl");
    scratch.file(
        "BUILD",
        """
        filegroup(
            name = "no_files",
            srcs = [],
        )

        filegroup(
            name = "two_files",
            srcs = ["foo.bzl", "bar.bzl"],
        )

        starlark_doc_extract(
            name = "no_files_doc",
            src = "no_files",
        )

        starlark_doc_extract(
            name = "two_files_doc",
            src = "two_files",
        )
        """);

    AssertionError extractNoFilesError =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//:no_files_doc"));
    assertThat(extractNoFilesError)
        .hasMessageThat()
        .contains("'//:no_files' must produce a single file");

    AssertionError extractTwoFilesError =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//:two_files_doc"));
    assertThat(extractTwoFilesError)
        .hasMessageThat()
        .contains("'//:two_files' must produce a single file");
  }

  @Test
  public void repositoryRule() throws Exception {
    scratch.file(
        "dep.bzl",
        """
        def _impl(repository_ctx):
            pass

        my_repo_rule = repository_rule(
            implementation = _impl,
            doc = '''My repository rule

            With details
            ''',
            attrs = {
                "a": attr.string(doc = "My doc", default = "foo"),
                "b": attr.string(mandatory = True),
                "_c": attr.string(doc = "Hidden attribute"),
            },
            environ = ["FOO_PATH", "BAR_COMPILER"],
        )

        """);
    scratch.file(
        "foo.bzl",
        """
        load("//:dep.bzl", "my_repo_rule")

        foo = struct(repo_rule = my_repo_rule)
        """);
    scratch.file(
        "BUILD",
        """
        load("bzl_library.bzl", "bzl_library")

        bzl_library(
            name = "dep_bzl",
            srcs = ["dep.bzl"],
        )

        starlark_doc_extract(
            name = "extract",
            src = "foo.bzl",
            deps = ["dep_bzl"],
        )
        """);
    ModuleInfo moduleInfo = protoFromConfiguredTarget("//:extract");
    assertThat(moduleInfo.getRepositoryRuleInfoList())
        .containsExactly(
            RepositoryRuleInfo.newBuilder()
                .setRuleName("foo.repo_rule")
                .setOriginKey(
                    OriginKey.newBuilder().setName("my_repo_rule").setFile("//:dep.bzl").build())
                .setDocString("My repository rule\n\nWith details")
                .addAllAttribute(ModuleInfoExtractor.IMPLICIT_REPOSITORY_RULE_ATTRIBUTES.values())
                .addAttribute(
                    AttributeInfo.newBuilder()
                        .setName("a")
                        .setType(AttributeType.STRING)
                        .setDocString("My doc")
                        .setDefaultValue("\"foo\""))
                .addAttribute(
                    AttributeInfo.newBuilder()
                        .setName("b")
                        .setType(AttributeType.STRING)
                        .setMandatory(true))
                .addEnviron("FOO_PATH")
                .addEnviron("BAR_COMPILER")
                .build());
  }

  @Test
  public void unexportedRepositoryRule_notDocumented() throws Exception {
    scratch.file(
        "foo.bzl",
        """
        def _my_impl(repository_ctx):
            pass

        s = struct(
            unexported_repo_rule = repository_rule(
                doc = "Unexported repo rule",
                implementation = _my_impl,
            ),
        )
        """);
    scratch.file(
        "BUILD",
        """
        starlark_doc_extract(
            name = "extract",
            src = "foo.bzl",
        )
        """);
    ModuleInfo moduleInfo = protoFromConfiguredTarget("//:extract");
    assertThat(moduleInfo.getRepositoryRuleInfoList()).isEmpty();
  }

  @Test
  public void moduleExtension() throws Exception {
    scratch.file(
        "dep.bzl",
        """
        _install = tag_class(
            doc = '''Install

            With details''',
            attrs = {
                "artifacts": attr.string_list(doc = "Artifacts"),
                "_hidden": attr.bool(),
            },
        )

        _artifact = tag_class(
            attrs = {
                "group": attr.string(),
                "artifact": attr.string(default = "foo"),
            },
        )

        def _impl(ctx):
            pass

        my_ext = module_extension(
            doc = '''My extension

            With details''',
            tag_classes = {
                "install": _install,
                "artifact": _artifact,
            },
            implementation = _impl,
        )
        """);
    scratch.file(
        "foo.bzl",
        """
        load("//:dep.bzl", "my_ext")

        foo = struct(ext = my_ext)
        """);
    scratch.file(
        "BUILD",
        """
        load("bzl_library.bzl", "bzl_library")

        bzl_library(
            name = "dep_bzl",
            srcs = ["dep.bzl"],
        )

        starlark_doc_extract(
            name = "extract",
            src = "foo.bzl",
            deps = ["dep_bzl"],
        )
        """);
    ModuleInfo moduleInfo = protoFromConfiguredTarget("//:extract");
    assertThat(moduleInfo.getFile()).isEqualTo("//:foo.bzl");
    assertThat(moduleInfo.getModuleExtensionInfoList())
        .containsExactly(
            ModuleExtensionInfo.newBuilder()
                .setExtensionName("foo.ext")
                .setDocString("My extension\n\nWith details")
                .setOriginKey(OriginKey.newBuilder().setFile("//:dep.bzl").build())
                .addTagClass(
                    ModuleExtensionTagClassInfo.newBuilder()
                        .setTagName("install")
                        .setDocString("Install\n\nWith details")
                        .addAttribute(
                            AttributeInfo.newBuilder()
                                .setName("artifacts")
                                .setType(AttributeType.STRING_LIST)
                                .setDocString("Artifacts")
                                .setDefaultValue("[]"))
                        .build())
                .addTagClass(
                    ModuleExtensionTagClassInfo.newBuilder()
                        .setTagName("artifact")
                        .addAttribute(
                            AttributeInfo.newBuilder()
                                .setName("group")
                                .setType(AttributeType.STRING)
                                .setDefaultValue("\"\""))
                        .addAttribute(
                            AttributeInfo.newBuilder()
                                .setName("artifact")
                                .setType(AttributeType.STRING)
                                .setDefaultValue("\"foo\""))
                        .build())
                .build());
  }

  @Test
  public void repoName_inMainBzlmodModule() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel", //
        "module(name = 'my_module', repo_name = 'legacy_internal_repo_name')");
    scratch.file(
        "foo.bzl",
        """
        def my_macro(arg = Label("//target:target")):
            pass

        my_rule = rule(
            implementation = lambda ctx: None,
            attrs = {"a": attr.label(default = "//target:target")},
        )
        """);
    scratch.file(
        "BUILD",
        """
        starlark_doc_extract(
            name = "with_main_repo_name",
            src = "foo.bzl",
            render_main_repo_name = True,
        )

        # render_main_repo_name is false by default
        starlark_doc_extract(
            name = "without_main_repo_name",
            src = "foo.bzl",
        )
        """);
    invalidatePackages();

    ModuleInfo withMainRepoName = protoFromConfiguredTarget("//:with_main_repo_name");
    assertThat(withMainRepoName.getFile()).isEqualTo("@my_module//:foo.bzl");
    assertThat(withMainRepoName.getRuleInfo(0).getOriginKey().getFile())
        .isEqualTo("@my_module//:foo.bzl");
    assertThat(withMainRepoName.getFuncInfo(0).getParameter(0).getDefaultValue())
        .isEqualTo("Label(\"@my_module//target:target\")");
    assertThat(getFirstRuleFirstAttr(withMainRepoName).getDefaultValue())
        .isEqualTo("\"@my_module//target\"");

    ModuleInfo withoutMainRepoName = protoFromConfiguredTarget("//:without_main_repo_name");
    assertThat(withoutMainRepoName.getFile()).isEqualTo("//:foo.bzl");
    assertThat(withoutMainRepoName.getFuncInfo(0).getParameter(0).getDefaultValue())
        .isEqualTo("Label(\"//target:target\")");
    assertThat(withoutMainRepoName.getRuleInfo(0).getOriginKey().getFile()).isEqualTo("//:foo.bzl");
    assertThat(getFirstRuleFirstAttr(withoutMainRepoName).getDefaultValue())
        .isEqualTo("\"//target\"");
  }

  @Test
  public void repoName_inBzlmodDep() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel", "module(name = 'my_module')", "bazel_dep(name='dep_mod', version='0.1')");
    registry.addModule(
        BzlmodTestUtil.createModuleKey("dep_mod", "0.1"), "module(name='dep_mod', version='0.1')");
    Path depModRepoPath = moduleRoot.getRelative("dep_mod+0.1");
    scratch.file(depModRepoPath.getRelative("REPO.bazel").getPathString());
    scratch.file(
        depModRepoPath.getRelative("foo.bzl").getPathString(),
        """
        def my_macro(arg = Label("//target:target")):
            pass

        my_rule = rule(
            implementation = lambda ctx: None,
            attrs = {"a": attr.label(default = "//target:target")},
        )
        """);
    scratch.file(
        depModRepoPath.getRelative("BUILD").getPathString(),
        """
        starlark_doc_extract(
            name = "extract",
            src = "foo.bzl",
        )
        """);
    invalidatePackages();

    ModuleInfo moduleInfo = protoFromConfiguredTarget("@dep_mod+//:extract");
    assertThat(moduleInfo.getFile()).isEqualTo("@dep_mod//:foo.bzl");
    assertThat(moduleInfo.getFuncInfo(0).getParameter(0).getDefaultValue())
        .isEqualTo("Label(\"@dep_mod//target:target\")");
    assertThat(moduleInfo.getRuleInfo(0).getOriginKey().getFile()).isEqualTo("@dep_mod//:foo.bzl");
    assertThat(getFirstRuleFirstAttr(moduleInfo).getDefaultValue())
        .isEqualTo("\"@dep_mod//target\"");
  }
}
