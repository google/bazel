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

package com.google.devtools.build.lib.rules.proto;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.getFirstArtifactEndingWith;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.prettyArtifactNames;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.util.MockProtoSupport;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@code proto_library}. */
@RunWith(JUnit4.class)
public class BazelProtoLibraryTest extends BuildViewTestCase {
  private boolean isThisBazel() {
    return getAnalysisMock().isThisBazel();
  }

  @Before
  public void setUp() throws Exception {
    useConfiguration("--proto_compiler=//proto:compiler");
    MockProtoSupport.setup(mockToolsConfig);
    scratch.file(
        "proto/BUILD",
        """
        licenses(["notice"])

        exports_files(["compiler"])
        """);

    invalidatePackages();
  }

  @Test
  public void protoToolchainResolution_enabled() throws Exception {
    setBuildLanguageOptions("--incompatible_enable_proto_toolchain_resolution");
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='foo', srcs=['foo.proto'])");

    getDescriptorOutput("//x:foo");

    assertNoEvents();
  }

  @Test
  public void createsDescriptorSets() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='alias', deps = ['foo'])",
        "proto_library(name='foo', srcs=['foo.proto'])",
        "proto_library(name='alias_to_no_srcs', deps = ['no_srcs'])",
        "proto_library(name='no_srcs')");

    assertThat(getDescriptorOutput("//x:alias").getRootRelativePathString())
        .isEqualTo("x/alias-descriptor-set.proto.bin");
    assertThat(getDescriptorOutput("//x:foo").getRootRelativePathString())
        .isEqualTo("x/foo-descriptor-set.proto.bin");
    assertThat(getDescriptorOutput("//x:alias_to_no_srcs").getRootRelativePathString())
        .isEqualTo("x/alias_to_no_srcs-descriptor-set.proto.bin");
    assertThat(getDescriptorOutput("//x:no_srcs").getRootRelativePathString())
        .isEqualTo("x/no_srcs-descriptor-set.proto.bin");
  }

  @Test
  public void descriptorSets_ruleWithSrcsCallsProtoc() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='foo', srcs=['foo.proto'])");
    Artifact file = getDescriptorOutput("//x:foo");

    assertThat(getGeneratingSpawnAction(file).getRemainingArguments())
        .containsAtLeast("-I.", "--descriptor_set_out=" + file.getExecPathString(), "x/foo.proto");
  }

  /** Asserts that we register a FileWriteAction with empty contents if there are no srcs. */
  @Test
  public void descriptorSets_ruleWithoutSrcsWritesEmptyFile() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='no_srcs')");
    Action action = getDescriptorWriteAction("//x:no_srcs");
    assertThat(action).isInstanceOf(FileWriteAction.class);
    assertThat(((FileWriteAction) action).getFileContents()).isEmpty();
  }

  /**
   * Asserts that the actions creating descriptor sets for rule R, take as input (=depend on) all of
   * the descriptor sets of the transitive dependencies of R.
   *
   * <p>This is needed so that building R, that has a dependency R' which violates strict proto
   * deps, would break.
   */
  @Test
  @Ignore("b/204266604 Remove if the testing shows it's not needed.")
  public void descriptorSetsDependOnChildren() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='alias', deps = ['foo'])",
        "proto_library(name='foo', srcs=['foo.proto'], deps = ['bar'])",
        "proto_library(name='bar', srcs=['bar.proto'])",
        "proto_library(name='alias_to_no_srcs', deps = ['no_srcs'])",
        "proto_library(name='no_srcs')");

    assertThat(getDepsDescriptorSets(getDescriptorOutput("//x:alias")))
        .containsExactly("x/foo-descriptor-set.proto.bin", "x/bar-descriptor-set.proto.bin");
    assertThat(getDepsDescriptorSets(getDescriptorOutput("//x:foo")))
        .containsExactly("x/bar-descriptor-set.proto.bin");
    assertThat(getDepsDescriptorSets(getDescriptorOutput("//x:bar"))).isEmpty();
    assertThat(getDepsDescriptorSets(getDescriptorOutput("//x:alias_to_no_srcs")))
        .containsExactly("x/no_srcs-descriptor-set.proto.bin");
    assertThat(getDepsDescriptorSets(getDescriptorOutput("//x:no_srcs"))).isEmpty();
  }

  /**
   * Returns all of the inputs of the action that generated 'getDirectDescriptorSet', and which are
   * themselves descriptor sets.
   */
  private ImmutableList<String> getDepsDescriptorSets(Artifact descriptorSet) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    for (String input : prettyArtifactNames(getGeneratingAction(descriptorSet).getInputs())) {
      if (input.endsWith("-descriptor-set.proto.bin")) {
        result.add(input);
      }
    }
    return result.build();
  }

  @Test
  public void descriptorSetsAreExposedInProvider() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='alias', deps = ['foo'])",
        "proto_library(name='foo', srcs=['foo.proto'], deps = ['bar'])",
        "proto_library(name='bar', srcs=['bar.proto'])",
        "proto_library(name='alias_to_no_srcs', deps = ['no_srcs'])",
        "proto_library(name='no_srcs')");

    {
      ProtoInfo provider = getConfiguredTarget("//x:alias").get(ProtoInfo.PROVIDER);
      assertThat(provider.getDirectDescriptorSet().getRootRelativePathString())
          .isEqualTo("x/alias-descriptor-set.proto.bin");
      assertThat(prettyArtifactNames(provider.getTransitiveDescriptorSets()))
          .containsExactly(
              "x/alias-descriptor-set.proto.bin",
              "x/foo-descriptor-set.proto.bin",
              "x/bar-descriptor-set.proto.bin");
    }

    {
      ProtoInfo provider = getConfiguredTarget("//x:foo").get(ProtoInfo.PROVIDER);
      assertThat(provider.getDirectDescriptorSet().getRootRelativePathString())
          .isEqualTo("x/foo-descriptor-set.proto.bin");
      assertThat(prettyArtifactNames(provider.getTransitiveDescriptorSets()))
          .containsExactly("x/foo-descriptor-set.proto.bin", "x/bar-descriptor-set.proto.bin");
    }

    {
      ProtoInfo provider = getConfiguredTarget("//x:bar").get(ProtoInfo.PROVIDER);
      assertThat(provider.getDirectDescriptorSet().getRootRelativePathString())
          .isEqualTo("x/bar-descriptor-set.proto.bin");
      assertThat(prettyArtifactNames(provider.getTransitiveDescriptorSets()))
          .containsExactly("x/bar-descriptor-set.proto.bin");
    }

    {
      ProtoInfo provider = getConfiguredTarget("//x:alias_to_no_srcs").get(ProtoInfo.PROVIDER);
      assertThat(provider.getDirectDescriptorSet().getRootRelativePathString())
          .isEqualTo("x/alias_to_no_srcs-descriptor-set.proto.bin");
      assertThat(prettyArtifactNames(provider.getTransitiveDescriptorSets()))
          .containsExactly(
              "x/alias_to_no_srcs-descriptor-set.proto.bin", "x/no_srcs-descriptor-set.proto.bin");
    }

    {
      ProtoInfo provider = getConfiguredTarget("//x:no_srcs").get(ProtoInfo.PROVIDER);
      assertThat(provider.getDirectDescriptorSet().getRootRelativePathString())
          .isEqualTo("x/no_srcs-descriptor-set.proto.bin");
      assertThat(prettyArtifactNames(provider.getTransitiveDescriptorSets()))
          .containsExactly("x/no_srcs-descriptor-set.proto.bin");
    }
  }

  @Test
  public void testDescriptorSetOutput_strictDeps() throws Exception {
    useConfiguration("--proto_compiler=//proto:compiler", "--strict_proto_deps=error");
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='nodeps', srcs=['nodeps.proto'])",
        "proto_library(name='withdeps', srcs=['withdeps.proto'], deps=[':dep1', ':dep2'])",
        "proto_library(name='depends_on_alias', srcs=['depends_on_alias.proto'], deps=[':alias'])",
        "proto_library(name='alias', deps=[':dep1', ':dep2'])",
        "proto_library(name='dep1', srcs=['dep1.proto'])",
        "proto_library(name='dep2', srcs=['dep2.proto'])");

    assertThat(getGeneratingSpawnAction(getDescriptorOutput("//x:nodeps")).getRemainingArguments())
        .containsAtLeast("--direct_dependencies", "x/nodeps.proto")
        .inOrder();
    assertThat(getGeneratingSpawnAction(getDescriptorOutput("//x:nodeps")).getRemainingArguments())
        .contains(String.format(ProtoConstants.STRICT_PROTO_DEPS_VIOLATION_MESSAGE, "//x:nodeps"));

    assertThat(
            getGeneratingSpawnAction(getDescriptorOutput("//x:withdeps")).getRemainingArguments())
        .containsAtLeast("--direct_dependencies", "x/dep1.proto:x/dep2.proto:x/withdeps.proto")
        .inOrder();

    assertThat(
            getGeneratingSpawnAction(getDescriptorOutput("//x:depends_on_alias"))
                .getRemainingArguments())
        .containsAtLeast(
            "--direct_dependencies", "x/dep1.proto:x/dep2.proto:x/depends_on_alias.proto")
        .inOrder();
  }

  /**
   * When building a proto_library with multiple srcs (say foo.proto and bar.proto), we should allow
   * foo.proto to import bar.proto without tripping strict-deps checking. This means that
   * --direct_dependencies should list the srcs.
   */
  @Test
  public void testDescriptorSetOutput_strict_deps_multipleSrcs() throws Exception {
    useConfiguration("--proto_compiler=//proto:compiler", "--strict_proto_deps=error");
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "x",
            "foo",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='foo', srcs=['foo.proto', 'bar.proto'])");
    Artifact file = getFirstArtifactEndingWith(getFilesToBuild(target), ".proto.bin");
    assertThat(file.getRootRelativePathString()).isEqualTo("x/foo-descriptor-set.proto.bin");

    assertThat(getGeneratingSpawnAction(file).getRemainingArguments())
        .containsAtLeast("--direct_dependencies", "x/foo.proto:x/bar.proto")
        .inOrder();
  }

  @Test
  public void testDescriptorSetOutput_strict_deps_strict() throws Exception {
    useConfiguration("--proto_compiler=//proto:compiler", "--strict_proto_deps=strict");
    ConfiguredTarget target =
        scratchConfiguredTarget(
            "x",
            "foo",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='foo', srcs=['foo.proto', 'bar.proto'])");
    Artifact file = getFirstArtifactEndingWith(getFilesToBuild(target), ".proto.bin");
    assertThat(file.getRootRelativePathString()).isEqualTo("x/foo-descriptor-set.proto.bin");

    assertThat(getGeneratingSpawnAction(file).getRemainingArguments())
        .containsAtLeast("--direct_dependencies", "x/foo.proto:x/bar.proto")
        .inOrder();
  }

  @Test
  public void testDescriptorSetOutput_strictDeps_disabled() throws Exception {
    useConfiguration("--proto_compiler=//proto:compiler", "--strict_proto_deps=off");
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='foo', srcs=['foo.proto'])");

    for (String arg :
        getGeneratingSpawnAction(getDescriptorOutput("//x:foo")).getRemainingArguments()) {
      assertThat(arg).doesNotContain("--direct_dependencies=");
      assertThat(arg)
          .doesNotContain(
              String.format(ProtoConstants.STRICT_PROTO_DEPS_VIOLATION_MESSAGE, "//x:foo_proto"));
    }
  }

  @Test
  public void testDescriptorSetOutput_strictDeps_default() throws Exception {
    useConfiguration("--proto_compiler=//proto:compiler", "--strict_proto_deps=default");
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='foo', srcs=['foo.proto'])");

    for (String arg :
        getGeneratingSpawnAction(getDescriptorOutput("//x:foo")).getRemainingArguments()) {
      assertThat(arg).doesNotContain("--direct_dependencies=");
      assertThat(arg)
          .doesNotContain(
              String.format(ProtoConstants.STRICT_PROTO_DEPS_VIOLATION_MESSAGE, "//x:foo_proto"));
    }
  }

  @Test
  public void testStripImportPrefixWithoutDeps() throws Exception {
    scratch.file(
        "third_party/x/foo/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "licenses(['unencumbered'])",
        "proto_library(",
        "    name = 'nodeps',",
        "    srcs = ['foo/nodeps.proto'],",
        "    strip_import_prefix = '/third_party/x/foo',",
        ")");
    ConfiguredTarget protoTarget = getConfiguredTarget("//third_party/x/foo:nodeps");
    ProtoInfo sourcesProvider = protoTarget.get(ProtoInfo.PROVIDER);
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();

    assertThat(sourcesProvider.getTransitiveProtoSourceRoots().toList())
        .containsExactly(genfiles + "/third_party/x/foo/_virtual_imports/nodeps");
    assertThat(
            getGeneratingSpawnAction(getDescriptorOutput("//third_party/x/foo:nodeps"))
                .getRemainingArguments())
        .contains("-I" + genfiles + "/third_party/x/foo/_virtual_imports/nodeps");
  }

  @Test
  public void strictPublicImports_enabled() throws Exception {
    useConfiguration("--strict_public_imports=ERROR", "--proto_compiler=//proto:compiler");
    scratch.file(
        "test/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "  name = 'myProto',",
        "  srcs = ['myProto.proto'],",
        ")");

    ConfiguredTarget configuredTargetTrue = getConfiguredTarget("//test:myProto");
    SpawnAction spawnActionTrue =
        (SpawnAction) ((RuleConfiguredTarget) configuredTargetTrue).getActions().get(0);
    assertThat(spawnActionTrue.getCommandLines().allArguments())
        .contains("--allowed_public_imports=");
  }

  @Test
  public void strictPublicImports_disabled() throws Exception {
    useConfiguration("--strict_public_imports=OFF", "--proto_compiler=//proto:compiler");
    scratch.file(
        "test/BUILD",
        """
        load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
        proto_library(
            name = "myProto",
            srcs = ["myProto.proto"],
        )
        """);

    ConfiguredTarget configuredTargetFalse = getConfiguredTarget("//test:myProto");
    SpawnAction spawnActionFalse =
        (SpawnAction) ((RuleConfiguredTarget) configuredTargetFalse).getActions().get(0);
    assertThat(spawnActionFalse.getCommandLines().allArguments())
        .doesNotContain("--allowed_public_imports=");
  }

  @Test
  public void strictPublicImports_transitiveExports() throws Exception {
    useConfiguration("--strict_public_imports=ERROR", "--proto_compiler=//proto:compiler");
    scratch.file(
        "x/BUILD",
        """
        load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
        proto_library(
            name = "prototop",
            srcs = ["top.proto"],
            exports = [
                ":exported1",
                ":exported2",
            ],
            deps = [
                ":exported1",
                ":exported2",
                ":notexported",
            ],
        )

        proto_library(
            name = "exported1",
            srcs = ["exported1.proto"],
        )

        proto_library(
            name = "exported2",
            srcs = ["exported2.proto"],
        )

        proto_library(
            name = "notexported",
            srcs = ["notexported.proto"],
        )
        """);

    // Check that the allowed public imports are passed correctly to the proto compiler.
    ConfiguredTarget configuredTarget = getConfiguredTarget("//x:prototop");
    List<String> arguments =
        ((SpawnAction) ((RuleConfiguredTarget) configuredTarget).getActions().get(0))
            .getRemainingArguments();
    String allowedImports =
        Iterables.getFirst(flagValue("--allowed_public_imports", arguments), null);
    assertThat(allowedImports).isEqualTo("x/exported1.proto:x/exported2.proto");
  }

  @Test
  public void testStripImportPrefixWithDepsDuplicate() throws Exception {
    scratch.file(
        "third_party/x/foo/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "licenses(['unencumbered'])",
        "proto_library(",
        "    name = 'withdeps',",
        "    srcs = ['foo/withdeps.proto'],",
        "    strip_import_prefix = '/third_party/x/foo',",
        "    deps = [':dep'],",
        ")",
        "proto_library(",
        "    name = 'dep',",
        "    srcs = ['foo/dep.proto'],",
        "    strip_import_prefix = '/third_party/x/foo',",
        ")");
    ConfiguredTarget protoTarget = getConfiguredTarget("//third_party/x/foo:withdeps");
    ProtoInfo sourcesProvider = protoTarget.get(ProtoInfo.PROVIDER);
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    assertThat(sourcesProvider.getTransitiveProtoSourceRoots().toList())
        .containsExactly(
            genfiles + "/third_party/x/foo/_virtual_imports/dep",
            genfiles + "/third_party/x/foo/_virtual_imports/withdeps");

    assertThat(
            getGeneratingSpawnAction(getDescriptorOutput("//third_party/x/foo:withdeps"))
                .getRemainingArguments())
        .containsAtLeast(
            "-I" + genfiles + "/third_party/x/foo/_virtual_imports/withdeps",
            "-I" + genfiles + "/third_party/x/foo/_virtual_imports/dep");
  }

  @Test
  public void testStripImportPrefixWithDeps() throws Exception {
    scratch.file(
        "third_party/x/foo/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "licenses(['unencumbered'])",
        "proto_library(",
        "    name = 'withdeps',",
        "    srcs = ['foo/withdeps.proto'],",
        "    strip_import_prefix = '/third_party/x/foo',",
        "    deps = ['//third_party/x/bar:dep', ':dep'],",
        ")",
        "proto_library(",
        "    name = 'dep',",
        "    srcs = ['foo/dep.proto'],",
        ")");
    scratch.file(
        "third_party/x/bar/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "licenses(['unencumbered'])",
        "proto_library(",
        "    name = 'dep',",
        "    srcs = ['foo/dep.proto'],",
        "    strip_import_prefix = '/third_party/x/bar',",
        ")");
    ConfiguredTarget protoTarget = getConfiguredTarget("//third_party/x/foo:withdeps");
    ProtoInfo sourcesProvider = protoTarget.get(ProtoInfo.PROVIDER);
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    assertThat(sourcesProvider.getTransitiveProtoSourceRoots().toList())
        .containsExactly(
            genfiles + "/third_party/x/foo/_virtual_imports/withdeps",
            genfiles + "/third_party/x/bar/_virtual_imports/dep",
            ".");
  }

  private void testExternalRepoWithGeneratedProto(boolean siblingRepoLayout) throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'foo')",
        "local_path_override(module_name = 'foo', path = '/foo')");
    if (siblingRepoLayout) {
      setBuildLanguageOptions("--experimental_sibling_repository_layout");
    }

    scratch.file("/foo/MODULE.bazel", "module(name = 'foo')");
    scratch.file(
        "/foo/x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='x', srcs=['generated.proto'])",
        "genrule(name='g', srcs=[], outs=['generated.proto'], cmd='')");
    scratch.file(
        "a/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='a', srcs=['a.proto'], deps=['@foo//x:x'])");
    invalidatePackages();

    String genfiles =
        getTargetConfiguration()
            .getGenfilesFragment(
                siblingRepoLayout ? RepositoryName.create("foo+") : RepositoryName.MAIN)
            .toString();
    String fooProtoRoot;
    fooProtoRoot = (siblingRepoLayout ? genfiles : genfiles + "/external/foo+");
    ConfiguredTarget a = getConfiguredTarget("//a:a");
    ProtoInfo aInfo = a.get(ProtoInfo.PROVIDER);
    assertThat(aInfo.getTransitiveProtoSourceRoots().toList()).containsExactly(".", fooProtoRoot);

    ConfiguredTarget x = getConfiguredTarget("@@foo+//x:x");
    ProtoInfo xInfo = x.get(ProtoInfo.PROVIDER);
    assertThat(xInfo.getTransitiveProtoSourceRoots().toList()).containsExactly(fooProtoRoot);
  }

  @Test
  public void testExternalRepoWithGeneratedProto_withSubdirRepoLayout() throws Exception {
    testExternalRepoWithGeneratedProto(/* siblingRepoLayout= */ false);
  }

  @Test
  public void test_siblingRepoLayout_externalRepoWithGeneratedProto() throws Exception {
    testExternalRepoWithGeneratedProto(/* siblingRepoLayout= */ true);
  }

  @Test
  public void testExportedStrippedImportPrefixes() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "ad/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='ad', strip_import_prefix='/ad', srcs=['ad.proto'])");
    scratch.file(
        "ae/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='ae', strip_import_prefix='/ae', srcs=['ae.proto'])");
    scratch.file(
        "bd/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='bd', strip_import_prefix='/bd', srcs=['bd.proto'])");
    scratch.file(
        "be/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='be', strip_import_prefix='/be', srcs=['be.proto'])");
    scratch.file(
        "a/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name='a',",
        "    strip_import_prefix='/a',",
        "    srcs=['a.proto'],",
        "    exports=['//ae:ae'],",
        "    deps=['//ad:ad'])");
    scratch.file(
        "b/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name='b',",
        "    strip_import_prefix='/b',",
        "    srcs=['b.proto'],",
        "    exports=['//be:be'],",
        "    deps=['//bd:bd'])");
    scratch.file(
        "c/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name='c',",
        "    strip_import_prefix='/c',",
        "    srcs=['c.proto'],",
        "    exports=['//a:a'],",
        "    deps=['//b:b'])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    // exported proto source roots should be the source root of the rule and the direct source roots
    // of its exports and nothing else (not the exports of its exports or the deps of its exports
    // or the exports of its deps)
    assertThat(
            Iterables.transform(
                a.get(ProtoInfo.PROVIDER).getExportedSources().toList(),
                s -> s.getRootRelativePathString()))
        .containsExactly("a/_virtual_imports/a/a.proto");
  }

  private void testImportPrefixInExternalRepo(boolean siblingRepoLayout) throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'yolo_repo')",
        "local_path_override(module_name = 'yolo_repo', path = '/yolo_repo')");

    if (siblingRepoLayout) {
      setBuildLanguageOptions("--experimental_sibling_repository_layout");
    }

    scratch.file("/yolo_repo/MODULE.bazel", "module(name = 'yolo_repo')");
    scratch.file("/yolo_repo/yolo_pkg/yolo.proto");
    scratch.file(
        "/yolo_repo/yolo_pkg/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "  name = 'yolo_proto',",
        "  srcs = ['yolo.proto'],",
        "  import_prefix = 'bazel.build/yolo',",
        "  visibility = ['//visibility:public'],",
        ")");
    invalidatePackages();

    ConfiguredTarget target = getConfiguredTarget("@@yolo_repo+//yolo_pkg:yolo_proto");
    assertThat(
            Iterables.getOnlyElement(target.get(ProtoInfo.PROVIDER).getExportedSources().toList())
                .getExecPathString())
        .endsWith("/_virtual_imports/yolo_proto/bazel.build/yolo/yolo_pkg/yolo.proto");
  }

  @Test
  public void testImportPrefixInExternalRepo_withSubdirRepoLayout() throws Exception {
    testImportPrefixInExternalRepo(/*siblingRepoLayout=*/ false);
  }

  @Test
  public void testImportPrefixInExternalRepo_withSiblingRepoLayout() throws Exception {
    testImportPrefixInExternalRepo(/*siblingRepoLayout=*/ true);
  }

  private void testImportPrefixAndStripInExternalRepo(boolean siblingRepoLayout) throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'yolo_repo')",
        "local_path_override(module_name = 'yolo_repo', path = '/yolo_repo')");

    if (siblingRepoLayout) {
      setBuildLanguageOptions("--experimental_sibling_repository_layout");
    }

    scratch.file("/yolo_repo/MODULE.bazel", "module(name = 'yolo_repo')");
    scratch.file("/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/yolo.proto");
    scratch.file(
        "/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "  name = 'yolo_proto',",
        "  srcs = ['yolo.proto'],",
        "  import_prefix = 'bazel.build/yolo',",
        "  strip_import_prefix = '/yolo_pkg_to_be_stripped',",
        "  visibility = ['//visibility:public'],",
        ")");
    invalidatePackages();

    ConfiguredTarget target =
        getConfiguredTarget("@@yolo_repo+//yolo_pkg_to_be_stripped/yolo_pkg:yolo_proto");
    assertThat(
            Iterables.getOnlyElement(target.get(ProtoInfo.PROVIDER).getExportedSources().toList())
                .getExecPathString())
        .endsWith("/_virtual_imports/yolo_proto/bazel.build/yolo/yolo_pkg/yolo.proto");
  }

  @Test
  public void testImportPrefixAndStripInExternalRepo_withSubdirRepoLayout() throws Exception {
    testImportPrefixAndStripInExternalRepo(/*siblingRepoLayout=*/ false);
  }

  @Test
  public void testImportPrefixAndStripInExternalRepo_withSiblingRepoLayout() throws Exception {
    testImportPrefixAndStripInExternalRepo(/*siblingRepoLayout=*/ true);
  }

  private void testStripImportPrefixInExternalRepo(boolean siblingRepoLayout) throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'yolo_repo')",
        "local_path_override(module_name = 'yolo_repo', path = '/yolo_repo')");

    if (siblingRepoLayout) {
      setBuildLanguageOptions("--experimental_sibling_repository_layout");
    }

    scratch.file("/yolo_repo/MODULE.bazel", "module(name = 'yolo_repo')");
    scratch.file("/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/yolo.proto");
    scratch.file(
        "/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "  name = 'yolo_proto',",
        "  srcs = ['yolo.proto'],",
        "  strip_import_prefix = '/yolo_pkg_to_be_stripped',",
        "  visibility = ['//visibility:public'],",
        ")");
    invalidatePackages();

    ConfiguredTarget target =
        getConfiguredTarget("@@yolo_repo+//yolo_pkg_to_be_stripped/yolo_pkg:yolo_proto");
    assertThat(
            Iterables.getOnlyElement(target.get(ProtoInfo.PROVIDER).getExportedSources().toList())
                .getExecPathString())
        .endsWith("/_virtual_imports/yolo_proto/yolo_pkg/yolo.proto");
  }

  @Test
  public void testStripImportPrefixInExternalRepo_withSubdirRepoLayout() throws Exception {
    testStripImportPrefixInExternalRepo(/*siblingRepoLayout=*/ false);
  }

  @Test
  public void testStripImportPrefixInExternalRepo_withSiblingRepoLayout() throws Exception {
    testStripImportPrefixInExternalRepo(/*siblingRepoLayout=*/ true);
  }

  private void testRelativeStripImportPrefixInExternalRepo(boolean siblingRepoLayout)
      throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'yolo_repo')",
        "local_path_override(module_name = 'yolo_repo', path = '/yolo_repo')");

    if (siblingRepoLayout) {
      setBuildLanguageOptions("--experimental_sibling_repository_layout");
    }

    scratch.file("/yolo_repo/MODULE.bazel", "module(name = 'yolo_repo')");
    scratch.file("/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/yolo.proto");
    scratch.file(
        "/yolo_repo/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "  name = 'yolo_proto',",
        "  srcs = ['yolo_pkg_to_be_stripped/yolo_pkg/yolo.proto'],",
        "  strip_import_prefix = 'yolo_pkg_to_be_stripped',",
        "  visibility = ['//visibility:public'],",
        ")");
    invalidatePackages();

    ConfiguredTarget target = getConfiguredTarget("@@yolo_repo+//:yolo_proto");
    assertThat(
            Iterables.getOnlyElement(target.get(ProtoInfo.PROVIDER).getExportedSources().toList())
                .getExecPathString())
        .endsWith("/_virtual_imports/yolo_proto/yolo_pkg/yolo.proto");
  }

  @Test
  public void testRelativeStripImportPrefixInExternalRepo_withSubdirRepoLayout() throws Exception {
    testRelativeStripImportPrefixInExternalRepo(/*siblingRepoLayout=*/ false);
  }

  @Test
  public void testRelativeStripImportPrefixInExternalRepo_withSiblingRepoLayout() throws Exception {
    testRelativeStripImportPrefixInExternalRepo(/*siblingRepoLayout=*/ true);
  }

  @Test
  public void testIllegalStripImportPrefix() throws Exception {
    scratch.file(
        "third_party/a/BUILD",
        "licenses(['unencumbered'])",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'a',",
        "    srcs = ['a.proto'],",
        "    strip_import_prefix = 'foo')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//third_party/a");
    assertContainsEvent(
        ".proto file 'third_party/a/a.proto' is not under the specified strip prefix");
  }

  @Test
  public void testIllegalImportPrefix() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "a/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'a',",
        "    srcs = ['a.proto'],",
        "    import_prefix = '/foo')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a");
    assertContainsEvent("should be a relative path");
  }

  @Test
  public void testRelativeStripImportPrefix() throws Exception {
    scratch.file(
        "third_party/a/b/BUILD",
        "licenses(['unencumbered'])",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    strip_import_prefix = 'c')");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//third_party/a/b:d"));
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    assertThat(commandLine).contains("-I" + genfiles + "/third_party/a/b/_virtual_imports/d");
  }

  @Test
  public void testAbsoluteStripImportPrefix() throws Exception {
    scratch.file(
        "third_party/a/b/BUILD",
        "licenses(['unencumbered'])",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    strip_import_prefix = '/third_party/a')");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//third_party/a/b:d"));
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    assertThat(commandLine).contains("-I" + genfiles + "/third_party/a/b/_virtual_imports/d");
  }

  @Test
  public void testAbsoluteStripImportPrefixWithSlash() throws Exception {
    scratch.file(
        "third_party/a/b/BUILD",
        "licenses(['unencumbered'])",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    strip_import_prefix = '/third_party/a/')");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//third_party/a/b:d"));
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    assertThat(commandLine).contains("-I" + genfiles + "/third_party/a/b/_virtual_imports/d");
  }

  @Test
  public void testStripImportPrefixAndImportPrefix() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "a/b/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    import_prefix = 'foo',",
        "    strip_import_prefix = 'c')");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//a/b:d"));
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    assertThat(commandLine).contains("-I" + genfiles + "/a/b/_virtual_imports/d");
  }

  @Test
  public void testImportPrefixWithoutStripImportPrefix() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "a/b/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    import_prefix = 'foo')");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//a/b:d"));
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    assertThat(commandLine).contains("-I" + genfiles + "/a/b/_virtual_imports/d");
  }

  @Test
  public void testDotInStripImportPrefix() throws Exception {
    scratch.file(
        "third_party/a/b/BUILD",
        "licenses(['unencumbered'])",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    strip_import_prefix = './c')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//third_party/a/b:d");
    assertContainsEvent("should be normalized");
  }

  @Test
  public void testDotDotInStripImportPrefix() throws Exception {
    scratch.file(
        "third_party/a/b/BUILD",
        "licenses(['unencumbered'])",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    strip_import_prefix = '../b/c')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//third_party/a/b:d");
    assertContainsEvent("should be normalized");
  }

  @Test
  public void testDotInImportPrefix() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "a/b/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    import_prefix = './e')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a/b:d");
    assertContainsEvent("should be normalized");
  }

  @Test
  public void testDotDotInImportPrefix() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "a/b/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    import_prefix = '../e')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a/b:d");
    assertContainsEvent("should be normalized");
  }

  @Test
  public void testStripImportPrefixWithStrictProtoDeps() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    useConfiguration("--strict_proto_deps=STRICT");
    scratch.file(
        "a/b/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto','c/e.proto'],",
        "    strip_import_prefix = 'c')");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//a/b:d"));
    assertThat(commandLine).containsAtLeast("--direct_dependencies", "d.proto:e.proto").inOrder();
  }

  @Test
  public void testDepOnStripImportPrefixWithStrictProtoDeps() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    useConfiguration("--strict_proto_deps=STRICT");
    scratch.file(
        "a/b/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    strip_import_prefix = 'c')");
    scratch.file(
        "a/b/e/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'e',",
        "    srcs = ['e.proto'],",
        "    deps = ['//a/b:d'])");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//a/b/e:e"));
    assertThat(commandLine)
        .containsAtLeast("--direct_dependencies", "d.proto:a/b/e/e.proto")
        .inOrder();
  }

  @Test
  public void testStripImportPrefixAndImportPrefixWithStrictProtoDeps() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    useConfiguration("--strict_proto_deps=STRICT");
    scratch.file(
        "a/b/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'd',",
        "    srcs = ['c/d.proto'],",
        "    import_prefix = 'foo',",
        "    strip_import_prefix = 'c')");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//a/b:d"));
    assertThat(commandLine).containsAtLeast("--direct_dependencies", "foo/d.proto").inOrder();
  }

  @Test
  public void testStripImportPrefixForExternalRepositories() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'foo')",
        "local_path_override(module_name = 'foo', path = '/foo')");

    scratch.file("/foo/MODULE.bazel", "module(name = 'foo')");
    scratch.file(
        "/foo/x/y/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'q',",
        "    srcs = ['z/q.proto'],",
        "    strip_import_prefix = '/x')");

    scratch.file(
        "a/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='a', srcs=['a.proto'], deps=['@foo//x/y:q'])");
    invalidatePackages();

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//a:a"));
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    assertThat(commandLine).contains("-I" + genfiles + "/external/foo+/x/y/_virtual_imports/q");
  }

  @CanIgnoreReturnValue
  private Artifact getDescriptorOutput(String label) throws Exception {
    return getFirstArtifactEndingWith(getFilesToBuild(getConfiguredTarget(label)), ".proto.bin");
  }

  private Action getDescriptorWriteAction(String label) throws Exception {
    return getGeneratingAction(getDescriptorOutput(label));
  }

  @Test
  public void testNoExperimentalProtoDescriptorSetsIncludeSourceInfo() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'a_proto',",
        "    srcs = ['a.proto'],",
        ")");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//x:a_proto"));
    assertThat(commandLine).doesNotContain("--include_source_info");
  }

  @Test
  public void testExperimentalProtoDescriptorSetsIncludeSourceInfo() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    useConfiguration("--experimental_proto_descriptor_sets_include_source_info");
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(",
        "    name = 'a_proto',",
        "    srcs = ['a.proto'],",
        ")");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//x:a_proto"));
    assertThat(commandLine).contains("--include_source_info");
  }

  @Test
  public void testSourceAndGeneratedProtoFiles() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "genrule(name='g', srcs=[], outs=['g.proto'], cmd = '')",
        "proto_library(name='p', srcs=['s.proto', 'g.proto'])");

    ImmutableList<String> commandLine =
        allArgsForAction((SpawnAction) getDescriptorWriteAction("//a:p"));
    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    assertThat(commandLine).containsAtLeast("-I.", "-I" + genfiles);
  }

  @Test
  public void testDependencyOnProtoSourceInExternalRepo() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file("third_party/foo/MODULE.bazel", "module(name = 'foo')");
    scratch.file(
        "third_party/foo/BUILD.bazel",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='a', srcs=['a.proto'])",
        "proto_library(name='c', srcs=['a/b/c.proto'])");
    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'foo')",
        "local_path_override(module_name = 'foo', path = 'third_party/foo')");
    invalidatePackages();

    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='a', srcs=['a.proto'], deps=['@foo//:a'])",
        "proto_library(name='c', srcs=['c.proto'], deps=['@foo//:c'])");

    {
      ImmutableList<String> commandLine =
          allArgsForAction((SpawnAction) getDescriptorWriteAction("//x:a"));
      assertThat(commandLine).containsAtLeast("-Iexternal/foo+", "-I.");
    }

    {
      ImmutableList<String> commandLine =
          allArgsForAction((SpawnAction) getDescriptorWriteAction("//x:c"));
      assertThat(commandLine).containsAtLeast("-Iexternal/foo+", "-I.");
    }
  }

  @Test
  public void testProtoLibrary() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='foo', srcs=['a.proto', 'b.proto', 'c.proto'])");

    ProtoInfo provider = getConfiguredTarget("//x:foo").get(ProtoInfo.PROVIDER);
    assertThat(Iterables.transform(provider.getDirectSources(), s -> s.getExecPathString()))
        .containsExactly("x/a.proto", "x/b.proto", "x/c.proto");
  }

  @Test
  public void testProtoLibraryWithoutSources() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='foo')");

    ProtoInfo provider = getConfiguredTarget("//x:foo").get(ProtoInfo.PROVIDER);
    assertThat(provider.getDirectSources()).isEmpty();
  }

  @Test
  public void testProtoLibraryWithVirtualProtoSourceRoot() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='foo', srcs=['a.proto'], import_prefix='foo')");

    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    ProtoInfo provider = getConfiguredTarget("//x:foo").get(ProtoInfo.PROVIDER);
    assertThat(Iterables.transform(provider.getDirectSources(), s -> s.getExecPathString()))
        .containsExactly(genfiles + "/x/_virtual_imports/foo/foo/x/a.proto");
  }

  @Test
  public void testProtoLibraryWithGeneratedSources_Blaze() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        """
        genrule(
            name = "g",
            srcs = [],
            outs = ["generated.proto"],
            cmd = "",
        )

        proto_library(
            name = "foo",
            srcs = ["generated.proto"],
        )
        """);

    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    ProtoInfo provider = getConfiguredTarget("//x:foo").get(ProtoInfo.PROVIDER);
    assertThat(Iterables.transform(provider.getDirectSources(), s -> s.getExecPathString()))
        .containsExactly(genfiles + "/x/generated.proto");
  }

  @Test
  public void testProtoLibraryWithMixedSources_Blaze() throws Exception {
    if (!isThisBazel()) {
      return;
    }

    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        """
        genrule(
            name = "g",
            srcs = [],
            outs = ["generated.proto"],
            cmd = "",
        )

        proto_library(
            name = "foo",
            srcs = [
                "a.proto",
                "generated.proto",
            ],
        )
        """);

    String genfiles = getTargetConfiguration().getGenfilesFragment(RepositoryName.MAIN).toString();
    ProtoInfo provider = getConfiguredTarget("//x:foo").get(ProtoInfo.PROVIDER);
    assertThat(Iterables.transform(provider.getDirectSources(), s -> s.getExecPathString()))
        .containsExactly(genfiles + "/x/generated.proto", "x/a.proto");
  }

  @Test
  public void protoLibrary_reexport_allowed() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        """
        proto_library(
            name = "foo",
            srcs = ["foo.proto"],
            allow_exports = ":test",
        )

        package_group(
            name = "test",
            packages = ["//allowed"],
        )
        """);
    scratch.file(
        "allowed/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        """
        proto_library(
            name = "test1",
            deps = ["//x:foo"],
        )

        proto_library(
            name = "test2",
            srcs = ["A.proto"],
            exports = ["//x:foo"],
        )
        """);

    getConfiguredTarget("//allowed:test1");
    getConfiguredTarget("//allowed:test2");

    assertNoEvents();
  }

  @Test
  public void protoLibrary_implcitReexport_fails() throws Exception {
    scratch.file(
        "x/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        """
        proto_library(
            name = "foo",
            srcs = ["foo.proto"],
            allow_exports = ":test",
        )

        package_group(
            name = "test",
            packages = ["//allowed"],
        )
        """);
    scratch.file(
        "notallowed/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='test', deps = ['//x:foo'])");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//notallowed:test");

    assertContainsEvent("proto_library '@@//x:foo' can't be reexported in package '//notallowed'");
  }

  @Test
  public void protoLibrary_explicitExport_fails() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        proto_library(
            name = "foo",
            srcs = ["foo.proto"],
            allow_exports = ":test",
        )

        package_group(
            name = "test",
            packages = ["//allowed"],
        )
        """);
    scratch.file(
        "notallowed/BUILD",
        "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
        "proto_library(name='test', srcs = ['A.proto'], exports = ['//x:foo'])");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//notallowed:test");

    assertContainsEvent("proto_library '@@//x:foo' can't be reexported in package '//notallowed'");
  }
}
