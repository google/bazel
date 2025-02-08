// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlark;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.stream;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.analysis.OutputGroupInfo.INTERNAL_SUFFIX;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.analysis.util.TestAspects;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.StarlarkAspectClass;
import com.google.devtools.build.lib.packages.StarlarkDefinedAspect;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.Analysis.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for Starlark aspects */
@RunWith(JUnit4.class)
public class StarlarkDefinedAspectsTest extends AnalysisTestCase {
  protected boolean keepGoing() {
    return false;
  }

  @Test
  public void simpleAspect() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx");
    assertThat(getAspectDescriptions(analysisResult))
        .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)");
  }

  @Test
  public void aspectWithSingleDeclaredProvider() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        foo = provider()
        def _impl(target, ctx):
           return foo()
        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx");
    assertThat(getAspectDescriptions(analysisResult))
        .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)");
    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());

    StarlarkProvider.Key fooKey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "foo");

    assertThat(configuredAspect.get(fooKey).getProvider().getKey()).isEqualTo(fooKey);
  }

  @Test
  public void aspectWithDeclaredProviders() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        foo = provider()
        bar = provider()
        def _impl(target, ctx):
           return [foo(), bar()]
        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx");
    assertThat(getAspectDescriptions(analysisResult))
        .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)");
    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());

    StarlarkProvider.Key fooKey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "foo");
    StarlarkProvider.Key barKey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "bar");

    assertThat(configuredAspect.get(fooKey).getProvider().getKey()).isEqualTo(fooKey);
    assertThat(configuredAspect.get(barKey).getProvider().getKey()).isEqualTo(barKey);
  }

  @Test
  public void aspectWithDeclaredProvidersInAStruct() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        foo = provider()
        bar = provider()
        def _impl(target, ctx):
           return [foo(), bar()]
        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx");
    assertThat(getAspectDescriptions(analysisResult))
        .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)");

    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());

    StarlarkProvider.Key fooKey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "foo");
    StarlarkProvider.Key barKey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "bar");

    assertThat(configuredAspect.get(fooKey).getProvider().getKey()).isEqualTo(fooKey);
    assertThat(configuredAspect.get(barKey).getProvider().getKey()).isEqualTo(barKey);
  }

  private static Iterable<String> getAspectDescriptions(AnalysisResult analysisResult) {
    return Iterables.transform(
        analysisResult.getAspectsMap().keySet(),
        aspectKey ->
            String.format("%s(%s)", aspectKey.getAspectClass().getName(), aspectKey.getLabel()));
  }

  @Test
  public void aspectCommandLineLabel() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    AnalysisResult analysisResult =
        update(ImmutableList.of("//test:aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx");
    assertThat(getAspectDescriptions(analysisResult))
        .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)");
  }

  @Test
  public void aspectCommandLineRepoLabel() throws Exception {
    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name='local')",
        "local_path_override(module_name='local', path='local/repo')");
    scratch.file("local/repo/MODULE.bazel", "module(name='local')");
    scratch.file(
        "local/repo/aspect.bzl",
        """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file("local/repo/BUILD");

    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    AnalysisResult analysisResult =
        update(ImmutableList.of("@local//:aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx");
    assertThat(getAspectDescriptions(analysisResult))
        .containsExactly("@@local+//:aspect.bzl%MyAspect(//test:xxx)");
  }

  private static Iterable<String> getLabelsToBuild(AnalysisResult analysisResult) {
    return Iterables.transform(
        analysisResult.getTargetsToBuild(),
        configuredTarget -> configuredTarget.getLabel().toString());
  }

  @Test
  public void aspectAllowsFragmentsToBeSpecified() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl, fragments=['java'])
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");

    AspectKey key = Iterables.getOnlyElement(analysisResult.getAspectsMap().keySet());
    AspectValue aspectValue = (AspectValue) skyframeExecutor.getEvaluator().getExistingValue(key);
    AspectDefinition aspectDefinition = aspectValue.getAspect().getDefinition();
    assertThat(
            aspectDefinition
                .getConfigurationFragmentPolicy()
                .isLegalConfigurationFragment(JavaConfiguration.class))
        .isTrue();
    assertThat(
            aspectDefinition
                .getConfigurationFragmentPolicy()
                .isLegalConfigurationFragment(CppConfiguration.class))
        .isFalse();
  }

  @Test
  public void aspectPropagating() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
Info = provider()
def _impl(target, ctx):
   s = depset([target.label], transitive = [i[Info].target_labels for i in ctx.rule.attr.deps])
   c = depset([ctx.rule.kind], transitive = [i[Info].rule_kinds for i in ctx.rule.attr.deps])
   return Info(target_labels = s, rule_kinds = c)

MyAspect = aspect(
   implementation=_impl,
   attr_aspects=['deps'],
)
""");
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
             deps = [':yyy'],
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx");
    ConfiguredAspect configuredAspect = analysisResult.getAspectsMap().values().iterator().next();
    assertThat(configuredAspect).isNotNull();
    StarlarkInfo info = getStarlarkProvider(configuredAspect, "//test:aspect.bzl", "Info");
    NestedSet<Label> names =
        Depset.cast(info.getValue("target_labels"), Label.class, "target_labels");
    assertThat(names.toList().stream().map(Label::toString))
        .containsExactly("//test:xxx", "//test:yyy");
    Object ruleKinds =
        getStarlarkProvider(configuredAspect, "//test:aspect.bzl", "Info").getValue("rule_kinds");
    assertThat(ruleKinds).isInstanceOf(Depset.class);
    assertThat(((Depset) ruleKinds).toList()).containsExactly("java_library");
  }

  @Test
  public void aspectsPropagatingForDefaultAndImplicit() throws Exception {
    useConfiguration(
        "--experimental_builtins_injection_override=+cc_library",
        "--incompatible_enable_cc_toolchain_resolution");
    scratch.file(
        "test/aspect.bzl",
        """
        Info = provider()
        def _impl(target, ctx):
           s = []
           c = []
           a = ctx.rule.attr
           if getattr(a, '_defaultattr', None):
               s += [a._defaultattr[Info].target_labels]
               c += [a._defaultattr[Info].rule_kinds]
           if getattr(a, '_cc_toolchain', None):
               s += [a._cc_toolchain[Info].target_labels]
               c += [a._cc_toolchain[Info].rule_kinds]
           return Info(
               target_labels = depset([target.label], transitive = s),
               rule_kinds = depset([ctx.rule.kind], transitive = c))

        def _rule_impl(ctx):
           pass

        my_rule = rule(implementation = _rule_impl,
           attrs = { '_defaultattr' : attr.label(default = Label('//test:xxx')) },
        )
        MyAspect = aspect(
           implementation=_impl,
           attr_aspects=['_defaultattr', '_cc_toolchain'],
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        cc_library(
             name = 'xxx',
        )
        my_rule(
             name = 'yyy',
        )
        """);
    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:yyy");
    ConfiguredAspect configuredAspect = analysisResult.getAspectsMap().values().iterator().next();
    assertThat(configuredAspect).isNotNull();
    StarlarkProvider.Key infoKey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "Info");
    Object nameSet = ((StarlarkInfo) configuredAspect.get(infoKey)).getValue("target_labels");
    ImmutableList<String> names =
        ImmutableList.copyOf(
            Iterables.transform(
                ((Depset) nameSet).toList(),
                o -> {
                  assertThat(o).isInstanceOf(Label.class);
                  return ((Label) o).getName();
                }));

    assertThat(names).containsAtLeast("xxx", "yyy");
  }

  @Test
  public void aspectsDirOnMergedTargets() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        Info = provider()
        def _impl(target, ctx):
           return Info(aspect_provider = 'data')

        p = provider()
        MyAspect = aspect(implementation=_impl)
        def _rule_impl(ctx):
           if ctx.attr.dep:
              return [p(dir = dir(ctx.attr.dep))]
           return [p()]

        my_rule = rule(implementation = _rule_impl,
           attrs = { 'dep' : attr.label(aspects = [MyAspect]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx',)
        my_rule(name = 'yyy', dep = ':xxx')
        """);
    AnalysisResult analysisResult = update("//test:yyy");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());

    StructImpl names = getStarlarkProvider(target, "p");
    assertThat((Iterable<?>) names.getValue("dir"))
        .containsExactly(
            "actions",
            "data_runfiles",
            "default_runfiles",
            "files",
            "files_to_run",
            "label",
            "output_groups");
  }

  @Test
  public void aspectWithOutputGroupsDeclaredProvider() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        "def _impl(target, ctx):",
        "   f = target[OutputGroupInfo]._hidden_top_level" + INTERNAL_SUFFIX,
        "   return [OutputGroupInfo(my_result = f)]",
        "",
        "MyAspect = aspect(",
        "   implementation=_impl,",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx");
    ConfiguredAspect configuredAspect = analysisResult.getAspectsMap().values().iterator().next();
    OutputGroupInfo outputGroupInfo = OutputGroupInfo.get(configuredAspect);

    assertThat(outputGroupInfo).isNotNull();
    NestedSet<Artifact> names = outputGroupInfo.getOutputGroup("my_result");
    assertThat(names.toList()).isNotEmpty();

    // Configuration of the true Artifact may diverge slightly (e.g. be trimmed) causing owners to
    // also diverge so just compare paths instead of the whole Artifact.
    ImmutableList<Path> paths =
        names.toList().stream().map(Artifact::getPath).collect(toImmutableList());
    ImmutableList<Path> expectedPaths =
        OutputGroupInfo.get(getConfiguredTarget("//test:xxx"))
            .getOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL)
            .toList()
            .stream()
            .map(Artifact::getPath)
            .collect(toImmutableList());
    assertThat(paths).containsExactlyElementsIn(expectedPaths);
  }

  @Test
  public void aspectWithOutputGroupsAsListDeclaredProvider() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        "def _impl(target, ctx):",
        "   g = target[OutputGroupInfo]._hidden_top_level" + INTERNAL_SUFFIX,
        "   return [OutputGroupInfo(my_result=g.to_list())]",
        "",
        "MyAspect = aspect(",
        "   implementation=_impl,",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(
            Iterables.transform(
                analysisResult.getTargetsToBuild(),
                configuredTarget -> configuredTarget.getLabel().toString()))
        .containsExactly("//test:xxx");
    ConfiguredAspect configuredAspect = analysisResult.getAspectsMap().values().iterator().next();
    OutputGroupInfo outputGroupInfo = OutputGroupInfo.get(configuredAspect);
    assertThat(outputGroupInfo).isNotNull();
    NestedSet<Artifact> names = outputGroupInfo.getOutputGroup("my_result");
    assertThat(names.toList()).isNotEmpty();

    // Configuration of the true Artifact may diverge slightly (e.g. be trimmed) causing owners to
    // also diverge so just compare paths instead of the whole Artifact.
    ImmutableList<Path> paths =
        names.toList().stream().map(Artifact::getPath).collect(toImmutableList());
    ImmutableList<Path> expectedPaths =
        OutputGroupInfo.get(getConfiguredTarget("//test:xxx"))
            .getOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL)
            .toList()
            .stream()
            .map(Artifact::getPath)
            .collect(toImmutableList());
    assertThat(paths).containsExactlyElementsIn(expectedPaths);
  }

  @Test
  public void aspectsFromStarlarkRules() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
AspectInfo = provider()
def _aspect_impl(target, ctx):
   s = depset([target.label], transitive =
       [i[AspectInfo].target_labels for i in ctx.rule.attr.deps])
   return AspectInfo(target_labels = s)

TargetInfo = provider()
def _rule_impl(ctx):
   s = depset(transitive = [i[AspectInfo].target_labels for i in ctx.attr.attr])
   return TargetInfo(rule_deps = s)

MyAspect = aspect(
   implementation=_aspect_impl,
   attr_aspects=['deps'],
)
my_rule = rule(
   implementation=_rule_impl,
   attrs = { 'attr' :
             attr.label_list(mandatory=True, allow_files=True, aspects = [MyAspect]) },
)
""");

    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        """);

    AnalysisResult analysisResult = update("//test:xxx");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx");
    ConfiguredTarget target = analysisResult.getTargetsToBuild().iterator().next();
    NestedSet<Label> names =
        Depset.cast(
            getStarlarkProvider(target, "TargetInfo").getValue("rule_deps", Depset.class),
            Label.class,
            "rule_deps");
    assertThat(names.toList().stream().map(Label::toString)).containsExactly("//test:yyy");
  }

  @Test
  public void aspectsNonExported() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _aspect_impl(target, ctx):
           return []

        def _rule_impl(ctx):
           pass

        def mk_aspect():
           return aspect(implementation=_aspect_impl)
        my_rule = rule(
           implementation=_rule_impl,
           attrs = { 'attr' : attr.label_list(aspects = [mk_aspect()]) },  # line 11
        )
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        """);

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult analysisResult = update("//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(analysisResult.hasError()).isTrue();
    } catch (ViewCreationFailedException | TargetParsingException e) {
      // expected
    }

    // attr.label_list() fails, stack=[<toplevel>@rules.bzl:11:38, label_list:<builtin>]
    assertContainsEvent("File \"/workspace/test/aspect.bzl\", line 11, column 38, in <toplevel>");
    assertContainsEvent(
        "Error in label_list: Aspects should be top-level values in extension files that define"
            + " them.");
  }

  @Test
  @SuppressWarnings("EmptyCatchBlock")
  public void aspectReturnsNonExportedProvider() throws Exception {
    scratch.file(
        "test/inc.bzl",
        """
        a = aspect(implementation = lambda target, ctx: [provider()()])
        r = rule(
          implementation = lambda ctx: [],
          attrs = {'a': attr.label_list(aspects = [a])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:inc.bzl', 'r')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name = 'j')
        r(name = 'test', a = [':j'])
        """);

    reporter.removeHandler(failFastHandler);
    try {
      update("//test");
      /* reached if --keep_going=true */
    } catch (ViewCreationFailedException unused) {
      /* reached if --keep_going=false */
    }
    assertContainsEvent(
        "aspect function returned an instance of a provider "
            + "(defined at /workspace/test/inc.bzl:1:58) that is not a global");
  }

  @Test
  public void providerNonExported() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def mk_provider():
           return provider()
        def _rule_impl(ctx):
           pass
        my_rule = rule(
           implementation=_rule_impl,
           attrs = { 'attr' : attr.label_list(providers = [mk_provider()]) },  # line 7
        )
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        """);

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult analysisResult = update("//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(analysisResult.hasError()).isTrue();
    } catch (ViewCreationFailedException | TargetParsingException e) {
      // expected
    }

    // attr.label_list() fails, stack=[<toplevel>@rules.bzl:7:38, label_list:<builtin>]
    assertContainsEvent("File \"/workspace/test/rule.bzl\", line 7, column 38, in <toplevel>");
    assertContainsEvent(
        "Error in label_list: Providers should be top-level values in extension files that define"
            + " them.");
  }

  @Test
  public void aspectOnLabelAttr() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
           return AspectInfo(aspect_data='foo')

        RuleInfo = provider()
        def _rule_impl(ctx):
           return RuleInfo(data=ctx.attr.attr[AspectInfo].aspect_data)

        MyAspect = aspect(
           implementation=_aspect_impl,
        )
        my_rule = rule(
           implementation=_rule_impl,
           attrs = { 'attr' :
                     attr.label(aspects = [MyAspect]) },
        )
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = ':yyy',
        )
        """);

    AnalysisResult analysisResult = update("//test:xxx");
    ConfiguredTarget target = analysisResult.getTargetsToBuild().iterator().next();
    String value = getStarlarkProvider(target, "RuleInfo").getValue("data", String.class);
    assertThat(value).isEqualTo("foo");
  }

  @Test
  public void labelKeyedStringDictAllowsAspects() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
           return AspectInfo(aspect_data=target.label.name)

        RuleInfo = provider()
        def _rule_impl(ctx):
           return RuleInfo(
               data=','.join(['{}:{}'.format(dep[AspectInfo].aspect_data, val)
                              for dep, val in ctx.attr.attr.items()]))

        MyAspect = aspect(
           implementation=_aspect_impl,
        )
        my_rule = rule(
           implementation=_rule_impl,
           attrs = { 'attr' :
                     attr.label_keyed_string_dict(aspects = [MyAspect]) },
        )
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = {':yyy': 'zzz'},
        )
        """);

    AnalysisResult analysisResult = update("//test:xxx");
    ConfiguredTarget target = analysisResult.getTargetsToBuild().iterator().next();
    String value = getStarlarkProvider(target, "RuleInfo").getValue("data", String.class);
    assertThat(value).isEqualTo("yyy:zzz");
  }

  @Test
  public void aspectsDoNotAttachToFiles() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []

        MyAspect = aspect(
           implementation=_impl,
           attr_aspects=['deps'],
        )
        """);
    scratch.file("test/zzz.jar");
    scratch.file(
        "test/BUILD",
        """
        exports_files(['zzz.jar'])
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
             deps = ['//test:zzz.jar'],
        )
        """);

    AnalysisResult result = update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void aspectsDoNotAttachToTopLevelFiles() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        p = provider()
        def _impl(target, ctx):
           return [p()]

        MyAspect = aspect(
           implementation=_impl,
           attr_aspects=['deps'],
        )
        """);
    scratch.file("test/zzz.jar");
    scratch.file(
        "test/BUILD",
        """
        exports_files(['zzz.jar'])
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
             deps = ['//test:zzz.jar'],
        )
        """);

    AnalysisResult result = update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:zzz.jar");
    assertThat(result.hasError()).isFalse();
    assertThat(
            Iterables.getOnlyElement(result.getAspectsMap().values())
                .getProviders()
                .getProviderCount())
        .isEqualTo(0);
  }

  @Test
  public void aspectFailingExecution() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return 1 // 0

        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
    }
    // Stack doesn't include source lines because we haven't told EvalException
    // how to read from scratch.
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:2:13: in "
            + "//test:aspect.bzl%MyAspect aspect on java_library rule //test:xxx: \n"
            + "Traceback (most recent call last):\n"
            + "\tFile \"/workspace/test/aspect.bzl\", line 2, column 13, in _impl\n"
            + "\t\treturn 1 // 0\n"
            + "Error: integer division by zero");
  }

  @Test
  public void aspectFailingReturnsNotAStruct() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return 0

        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
    }
    assertContainsEvent(
        "Aspect implementation should return a struct, a list, or a provider "
            + "instance, but got int");
  }

  @Test
  public void aspectFailingOrphanArtifacts() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
          ctx.actions.declare_file('missing_in_action.txt')
          return []

        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
    }
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:2:13: in "
            + "//test:aspect.bzl%MyAspect aspect on java_library rule //test:xxx: \n"
            + "The following files have no generating action:\n"
            + "test/missing_in_action.txt");
  }

  @Test
  public void aspectSkippingOrphanArtifactsWithLocation() throws Exception {
    scratch.file(
        "simple/print.bzl",
        """
        Info = provider()
        def _print_expanded_location_impl(target, ctx):
            return Info(result=ctx.expand_location(ctx.rule.attr.cmd, []))

        print_expanded_location = aspect(
            implementation = _print_expanded_location_impl,
        )
        """);
    scratch.file(
        "simple/BUILD",
        """
        filegroup(
            name = "files",
            srcs = ["afile"],
        )

        genrule(
            name = "concat_all_files",
            srcs = [":files"],
            outs = ["concatenated.txt"],
            cmd = "$(location :files)"
        )
        """);

    reporter.removeHandler(failFastHandler);
    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//simple:print.bzl%print_expanded_location"),
            "//simple:concat_all_files");
    assertThat(analysisResult.hasError()).isFalse();
    ConfiguredAspect configuredAspect = analysisResult.getAspectsMap().values().iterator().next();
    String result =
        getStarlarkProvider(configuredAspect, "//simple:print.bzl", "Info")
            .getValue("result", String.class);

    assertThat(result).isEqualTo("simple/afile");
  }

  @Test
  public void expandLocationFailsForTargetsWithSameLabel() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = 'function_transition_allowlist',
            packages = [
                '//a/...',
            ],
        )
        """);
    scratch.file(
        "a/defs.bzl",
        "def _transition_impl(settings, attr):",
        "    return {",
        "        'opt': {'//command_line_option:compilation_mode': 'opt'},",
        "        'dbg': {'//command_line_option:compilation_mode': 'dbg'},",
        "    }",
        "split_transition = transition(",
        "    implementation = _transition_impl,",
        "    inputs = [],",
        "    outputs = ['//command_line_option:compilation_mode'])",
        "def _split_deps_rule_impl(ctx):",
        "    pass",
        "split_deps_rule = rule(",
        "    implementation = _split_deps_rule_impl,",
        "    attrs = {",
        "        'my_dep': attr.label(cfg = split_transition),",
        "    })",
        "",
        "Info = provider()",
        "def _print_expanded_location_impl(target, ctx):",
        "    return Info(result=ctx.expand_location('$(location //a:lib)',"
            + " [ctx.rule.attr.my_dep[0], ctx.rule.attr.my_dep[1]]))",
        "",
        "print_expanded_location = aspect(",
        "    implementation = _print_expanded_location_impl,",
        ")");
    scratch.file(
        "a/BUILD",
        """
        load('//a:defs.bzl', 'split_deps_rule')
        cc_library(name = 'lib', srcs = ['lib.cc'])
        split_deps_rule(
            name = 'a',
            my_dep = ':lib')
        """);

    reporter.removeHandler(failFastHandler);

    try {
      AnalysisResult analysisResult =
          update(ImmutableList.of("//a:defs.bzl%print_expanded_location"), "//a");
      assertThat(keepGoing()).isTrue();
      assertThat(analysisResult.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
    }
    assertContainsEvent("Label \"//a:lib\" is found more than once in 'targets' list.");
  }

  @Test
  public void topLevelAspectIsNotAnAspect() throws Exception {
    scratch.file("test/aspect.bzl", "MyAspect = 4");
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx')");

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
    }
    assertContainsEvent("MyAspect from //test:aspect.bzl is not an aspect");
  }

  @Test
  public void duplicateOutputGroups() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _aspect_impl(target, ctx):
          a = ctx.actions.declare_file('aspect.txt')
          ctx.actions.write(a, 'f')
          return [OutputGroupInfo(duplicate = depset([a]))]

        MyAspect = aspect(implementation=_aspect_impl)
        def _rule_impl(ctx):
          r = ctx.actions.declare_file('rule.txt')
          ctx.actions.write(r, 'r')
          return [OutputGroupInfo(duplicate = depset([r]))]
        my_rule = rule(_rule_impl)
        def _rbase_impl(ctx):
          return [DefaultInfo(files=ctx.attr.dep[OutputGroupInfo].duplicate)]
        rbase = rule(_rbase_impl, attrs = { 'dep' : attr.label(aspects = [MyAspect]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule', 'rbase')
        my_rule(name = 'xxx')
        rbase(name = 'yyy', dep = ':xxx')
        """);

    AnalysisResult analysisResult = update("//test:yyy");
    NestedSet<Artifact> filesToBuild =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild())
            .getProvider(FileProvider.class)
            .getFilesToBuild();
    assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
        .containsExactly("aspect.txt", "rule.txt");
  }

  @Test
  public void mergesFileProvider() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _asp_impl(target, ctx):
          f = ctx.actions.declare_file('aspectfile')
          ctx.actions.write(f, 'f')
          return [DefaultInfo(files=depset([f]))]

        asp = aspect(implementation=_asp_impl)
        def _rule_impl(ctx):
          return [DefaultInfo(files=depset(ctx.files.dep))]
        my_rule = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [asp]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule')
        my_rule(name='a', dep=':b')
        filegroup(name='b', srcs=['sourcefile'])
        """);

    AnalysisResult analysisResult = update("//test:a");
    NestedSet<Artifact> filesToBuild =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild())
            .getProvider(FileProvider.class)
            .getFilesToBuild();
    assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
        .containsExactly("sourcefile", "aspectfile");
  }

  @Test
  public void aspectDefaultInfoWithNonArtifacts() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _asp_impl(target, ctx):
          return [DefaultInfo(files=depset([1]))]

        asp = aspect(implementation=_asp_impl)
        def _rule_impl(ctx):
          return [DefaultInfo()]
        my_rule = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [asp]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule')
        my_rule(name='a', dep=':b')
        filegroup(name='b', srcs=['sourcefile'])
        """);

    reporter.removeHandler(failFastHandler);
    try {
      update("//test:a");
    } catch (ViewCreationFailedException e) {
      // expected.
    }
    assertContainsEvent("should contain a depset of files");
  }

  @Test
  public void aspectDefaultInfoSomethingElseThanFiles() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _asp_impl(target, ctx):
          return [DefaultInfo(runfiles=ctx.runfiles([]))]

        asp = aspect(implementation=_asp_impl)
        def _rule_impl(ctx):
          return [DefaultInfo()]
        my_rule = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [asp]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule')
        my_rule(name='a', dep=':b')
        filegroup(name='b', srcs=['sourcefile'])
        """);

    reporter.removeHandler(failFastHandler);
    try {
      update("//test:a");
    } catch (ViewCreationFailedException e) {
      // expected.
    }
    assertContainsEvent("must only have the 'files' field set");
  }

  @Test
  public void aspectEmptyDefaultInfo() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _asp_impl(target, ctx):
          return [DefaultInfo()]

        asp = aspect(implementation=_asp_impl)
        def _rule_impl(ctx):
          return [DefaultInfo(files=depset(ctx.files.dep))]
        my_rule = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [asp]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule')
        my_rule(name='a', dep=':b')
        filegroup(name='b', srcs=['sourcefile'])
        """);

    AnalysisResult analysisResult = update("//test:a");
    NestedSet<Artifact> filesToBuild =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild())
            .getProvider(FileProvider.class)
            .getFilesToBuild();
    assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild)).containsExactly("sourcefile");
  }

  @Test
  public void outputGroupsFromOneAspect() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _a1_impl(target, ctx):
          f = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(f, 'f')
          return [OutputGroupInfo(a1_group = depset([f]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])
        def _rule_impl(ctx):
          if not ctx.attr.dep:
             return []
          og = {k:ctx.attr.dep.output_groups[k] for k in ctx.attr.dep[OutputGroupInfo]}
          return [OutputGroupInfo(**og)]
        my_rule1 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a1]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule1')
        my_rule1(name = 'base')
        my_rule1(name = 'xxx', dep = ':base')
        """);

    AnalysisResult analysisResult = update("//test:xxx");
    OutputGroupInfo outputGroupInfo =
        OutputGroupInfo.get(Iterables.getOnlyElement(analysisResult.getTargetsToBuild()));
    assertThat(getOutputGroupContents(outputGroupInfo, "a1_group"))
        .containsExactly("test/base_a1.txt");
  }

  @Test
  public void outputGroupsDeclaredProviderFromOneAspect() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _a1_impl(target, ctx):
          f = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(f, 'f')
          return [OutputGroupInfo(a1_group = depset([f]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])
        def _rule_impl(ctx):
          if not ctx.attr.dep:
             return []
          return [OutputGroupInfo(a1_group = ctx.attr.dep[OutputGroupInfo].a1_group)]
        my_rule1 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a1]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule1')
        my_rule1(name = 'base')
        my_rule1(name = 'xxx', dep = ':base')
        """);

    AnalysisResult analysisResult = update("//test:xxx");
    OutputGroupInfo outputGroupInfo =
        OutputGroupInfo.get(Iterables.getOnlyElement(analysisResult.getTargetsToBuild()));
    assertThat(getOutputGroupContents(outputGroupInfo, "a1_group"))
        .containsExactly("test/base_a1.txt");
  }

  @Test
  public void outputGroupsFromTwoAspects() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _a1_impl(target, ctx):
          f = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(f, 'f')
          return [OutputGroupInfo(a1_group = depset([f]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])
        def _rule_impl(ctx):
          if not ctx.attr.dep:
             return []
          og = {k:ctx.attr.dep.output_groups[k] for k in ctx.attr.dep.output_groups}
          return [OutputGroupInfo(**og)]
        my_rule1 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a1]) })
        def _a2_impl(target, ctx):
          g = ctx.actions.declare_file(target.label.name + '_a2.txt')
          ctx.actions.write(g, 'f')
          return [OutputGroupInfo(a2_group = depset([g]))]

        a2 = aspect(implementation=_a2_impl, attr_aspects = ['dep'])
        my_rule2 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a2]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule1', 'my_rule2')
        my_rule1(name = 'base')
        my_rule1(name = 'xxx', dep = ':base')
        my_rule2(name = 'yyy', dep = ':xxx')
        """);

    AnalysisResult analysisResult = update("//test:yyy");
    OutputGroupInfo outputGroupInfo =
        OutputGroupInfo.get(Iterables.getOnlyElement(analysisResult.getTargetsToBuild()));
    assertThat(getOutputGroupContents(outputGroupInfo, "a1_group"))
        .containsExactly("test/base_a1.txt");
    assertThat(getOutputGroupContents(outputGroupInfo, "a2_group"))
        .containsExactly("test/xxx_a2.txt");
  }

  @Test
  public void outputGroupsDeclaredProvidersFromTwoAspects() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _a1_impl(target, ctx):
          f = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(f, 'f')
          return [OutputGroupInfo(a1_group = depset([f]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])
        def _rule_impl(ctx):
          if not ctx.attr.dep:
             return []
          og = dict()
          dep_og = ctx.attr.dep[OutputGroupInfo]
          if hasattr(dep_og, 'a1_group'):
             og['a1_group'] = dep_og.a1_group
          if hasattr(dep_og, 'a2_group'):
             og['a2_group'] = dep_og.a2_group
          return [OutputGroupInfo(**og)]
        my_rule1 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a1]) })
        def _a2_impl(target, ctx):
          g = ctx.actions.declare_file(target.label.name + '_a2.txt')
          ctx.actions.write(g, 'f')
          return [OutputGroupInfo(a2_group = depset([g]))]

        a2 = aspect(implementation=_a2_impl, attr_aspects = ['dep'])
        my_rule2 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a2]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule1', 'my_rule2')
        my_rule1(name = 'base')
        my_rule1(name = 'xxx', dep = ':base')
        my_rule2(name = 'yyy', dep = ':xxx')
        """);

    AnalysisResult analysisResult = update("//test:yyy");
    OutputGroupInfo outputGroupInfo =
        OutputGroupInfo.get(Iterables.getOnlyElement(analysisResult.getTargetsToBuild()));
    assertThat(getOutputGroupContents(outputGroupInfo, "a1_group"))
        .containsExactly("test/base_a1.txt");
    assertThat(getOutputGroupContents(outputGroupInfo, "a2_group"))
        .containsExactly("test/xxx_a2.txt");
  }

  @Test
  public void duplicateOutputGroupsFromTwoAspects() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _a1_impl(target, ctx):
          a1 = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(a1, 'f')
          return [OutputGroupInfo(aspect_group = depset([a1]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])

        def _a2_impl(target, ctx):
          a2 = ctx.actions.declare_file(target.label.name + '_a2.txt')
          ctx.actions.write(a2, 'f')
          return [OutputGroupInfo(aspect_group = depset([a2]))]

        a2 = aspect(implementation=_a2_impl, attr_aspects = ['dep'])

        def _base_impl(ctx):
          return []

        base = rule(_base_impl, attrs = {})

        def _top_impl(ctx):
          return [DefaultInfo(files=ctx.attr.dep[OutputGroupInfo].aspect_group)]

        top = rule(_top_impl, attrs = { 'dep' : attr.label(aspects = [a1, a2]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'base', 'top')
        base(name = 'base')
        top(name = 'top', dep = ':base')
        """);

    AnalysisResult analysisResult = update("//test:top");
    NestedSet<Artifact> filesToBuild =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild())
            .getProvider(FileProvider.class)
            .getFilesToBuild();
    assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
        .containsExactly("base_a1.txt", "base_a2.txt");
  }

  private static Iterable<String> getOutputGroupContents(
      OutputGroupInfo outputGroupInfo, String groupName) {
    return Iterables.transform(
        outputGroupInfo.getOutputGroup(groupName).toList(), Artifact::getRootRelativePathString);
  }

  @Test
  public void duplicateStarlarkProviders() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        Info = provider()
        def _impl(target, ctx):
          return Info(duplicate = 'x')

        MyAspect = aspect(implementation=_impl)
        def _rule_impl(ctx):
          return Info(duplicate = 'y')
        my_rule = rule(_rule_impl)
        def _noop(ctx):
          pass
        rbase = rule(_noop, attrs = { 'dep' : attr.label(aspects = [MyAspect]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'my_rule', 'rbase')
        my_rule(name = 'xxx')
        rbase(name = 'yyy', dep = ':xxx')
        """);

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update("//test:yyy");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
    }
    assertContainsEvent("ERROR /workspace/test/BUILD:3:6: Provider Info provided twice");
  }

  @Test
  public void topLevelAspectDoesNotExist() throws Exception {
    scratch.file("test/aspect.bzl", "");
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx')");

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
    }
    assertContainsEvent("MyAspect is not exported from //test:aspect.bzl");
  }

  @Test
  public void topLevelAspectDoesNotExist2() throws Exception {
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx')");

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
    }
    assertContainsEvent("cannot load '//test:aspect.bzl': no such file");
  }

  @Test
  public void topLevelAspectDoesNotExistNoBuildFile() throws Exception {
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx')");

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of("foo/aspect.bzl%MyAspect"), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
    }
    assertContainsEvent("Every .bzl file must have a corresponding package");
  }

  /**
   * Tests that a loading-level error (missing bzl file) is properly transformed by the configured
   * target that requested the relevant package, and doesn't bubble up to a higher configured
   * target/aspect that wasn't expecting a loading-level error. The complication is that the
   * configured target that depends directly on the error tries to do configuration resolution after
   * noticing the error, and configuration resolution is interruptible, so it is interrupted. It
   * needs to then throw the error, rather than the interruption.
   *
   * <p>This test covers error propagation up to both the configured target that depends on the one
   * in error, as well as the aspect on that configured target, since the error goes through both.
   */
  @Test
  public void aspectBaseConfiguredTargetTransitivelyDependingOnPackageInError() throws Exception {
    setRulesAndAspectsAvailableInTests(ImmutableList.of(), ImmutableList.of());
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        "package_group(name = 'function_transition_allowlist', packages = ['//aspect/...'])");
    scratch.file(
        "aspect/aspect.bzl",
        """
        def _setting_impl(ctx):
          return []

        string_flag = rule(
          implementation = _setting_impl,
          build_setting = config.string(flag=True),
        )

        def _rule_impl(ctx):
          pass

        def _transition_impl(settings, attr):
          return {'//aspect:formation': 'mesa'}

        formation_transition = transition(
          implementation = _transition_impl,
          inputs = ['//aspect:formation'],
          outputs = ['//aspect:formation'],
        )

        def _aspect_impl(target, ctx):
          pass

        myaspect = aspect(implementation = _aspect_impl)

        cfgrule = rule(
          implementation = _rule_impl,
          attrs = {
            'to': attr.label(),
            'innocent': attr.label(cfg = formation_transition),
          }
        )
        """);
    scratch.file(
        "aspect/BUILD",
        """
        load('aspect.bzl', 'cfgrule', 'string_flag')
        string_flag(name = 'formation', build_setting_default = 'canyon')
        filegroup(name = 'innocent')
        cfgrule(name = 'top', to = '//baz:baz', innocent = ':innocent')
        """);
    scratch.file(
        "bar/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'bar', deps = ['//baz:baz'])");
    scratch.file(
        "baz/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        load('//baz/subdir:missing.bzl', 'sym')
        foo_library(name = 'baz')
        """);
    scratch.file("baz/subdir/missing.bzl");
    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result =
          update(ImmutableList.of("//aspect:aspect.bzl%myaspect"), "//aspect:top");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect to fail.
      assertThat(keepGoing()).isFalse();
    }
    assertContainsEvent("Label '//baz/subdir:missing.bzl' is invalid");
  }

  @Test
  public void aspectParametersUncovered() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of(), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (Exception e) {
      // expect to fail.
    }
    assertContainsEvent( // "ERROR /workspace/test/aspect.bzl:9:11: "
        "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
            + "'my_attr' with type string.");
  }

  @Test
  public void aspectParametersTypeMismatch() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectMismatch = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectMismatch]),
                      'my_attr' : attr.int() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx', my_attr = 4)
        """);

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of(), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (Exception e) {
      // expect to fail.
    }
    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectMismatch requires rule my_rule to specify attribute "
            + "'my_attr' with type string.");
  }

  @Test
  public void aspectParametersDontSupportSelect() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectMismatch = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectMismatch]),
                      'my_attr' : attr.string() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:aspect.bzl", "my_rule")

        # Needed to avoid the select() being eliminated as trivial.
        config_setting(
            name = "config",
            values = {"defines": "something"},
        )

        my_rule(
            name = "xxx",
            my_attr = select({
                ":config": "foo",
                "//conditions:default": "bar",
            }),
        )
        """);

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of(), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (Exception e) {
      // expect to fail.
    }
    assertContainsEvent(
        "//test:xxx: attribute 'my_attr' has a select() and aspect "
            + "//test:aspect.bzl%MyAspectMismatch also declares '//test:xxx'. Aspect attributes "
            + "don't currently support select().");
  }

  @Test
  public void aspectParametersBadDefault() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectBadDefault = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['a'], default='b') },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectBadDefault]) },  # line 11
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of(), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (Exception e) {
      // expect to fail.
    }

    // aspect fails, stack = [<toplevel>@:5:28, aspect@<builtin>]
    assertContainsEvent("File \"/workspace/test/aspect.bzl\", line 5, column 28, in <toplevel>");
    assertContainsEvent(
        "Error in aspect: Aspect parameter attribute 'my_attr' has a bad default value: has to be"
            + " one of 'a' instead of 'b'");
  }

  @Test
  public void aspectParametersBadValue() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectBadValue = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['a']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectBadValue]),
                      'my_attr' : attr.string() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx', my_attr='b')
        """);

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult result = update(ImmutableList.of(), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(result.hasError()).isTrue();
    } catch (Exception e) {
      // expect to fail.
    }
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:2:8: //test:xxx: invalid value in 'my_attr' "
            + "attribute: has to be one of 'a' instead of 'b'");
  }

  @Test
  public void aspectParameters() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspect = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspect]),
                      'my_attr' : attr.string() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx', my_attr = 'aaa')
        """);

    AnalysisResult result = update(ImmutableList.of(), "//test:xxx");
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void aspectParametersConfigurationField() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspect = aspect(
            implementation=_impl,
            attrs = { '_my_attr' : attr.label(default=
                     configuration_field(fragment='cpp', name = 'cc_toolchain')) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspect]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);

    AnalysisResult result = update(ImmutableList.of(), "//test:xxx");
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void aspectParameterComputedDefault() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        def _defattr():
           return Label('//foo/bar:baz')
        MyAspect = aspect(
            implementation=_impl,
            attrs = { '_extra' : attr.label(default = _defattr) }
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspect]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);
    reporter.removeHandler(failFastHandler);

    if (keepGoing()) {
      AnalysisResult result = update("//test:xxx");
      assertThat(result.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update("//test:xxx"));
    }
    assertContainsEvent(
        "Aspect attribute '_extra' (label) with computed default value is unsupported.");
  }

  @Test
  public void aspectParametersOptional() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectOptParam = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa'], default='aaa') },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectOptParam]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);

    AnalysisResult result = update(ImmutableList.of(), "//test:xxx");
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void aspectParametersOptionalOverride() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           if (ctx.attr.my_attr == 'a'):
               fail('Rule is not overriding default, still has value ' + ctx.attr.my_attr)
           return []
        def _rule_impl(ctx):
           return []
        MyAspectOptOverride = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['a', 'b'], default='a') },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectOptOverride]),
                      'my_attr' : attr.string() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx', my_attr = 'b')
        """);

    AnalysisResult result = update(ImmutableList.of(), "//test:xxx");
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testMultipleExecutablesInTarget() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        """
        def _aspect_impl(target, ctx):
           return []
        my_aspect = aspect(_aspect_impl)
        def _main_rule_impl(ctx):
           pass
        my_rule = rule(_main_rule_impl,
           attrs = {
              'exe1' : attr.label(executable = True, allow_files = True, cfg = 'exec'),
              'exe2' : attr.label(executable = True, allow_files = True, cfg = 'exec'),
           },
        )
        """);

    scratch.file("foo/tool.sh", "#!/bin/bash");
    scratch.file(
        "foo/BUILD",
        """
        load(':extension.bzl',  'my_rule')
        my_rule(name = 'main', exe1 = ':tool.sh', exe2 = ':tool.sh')
        """);
    AnalysisResult analysisResultOfRule = update(ImmutableList.of(), "//foo:main");
    assertThat(analysisResultOfRule.hasError()).isFalse();

    AnalysisResult analysisResultOfAspect =
        update(ImmutableList.of("/foo/extension.bzl%my_aspect"), "//foo:main");
    assertThat(analysisResultOfAspect.hasError()).isFalse();
  }

  @Test
  public void aspectFragmentAccessSuccess() throws Exception {
    analyzeConfiguredTargetForAspectFragment("ctx.fragments.java.strict_java_deps", "'java'", "");
    assertNoEvents();
  }

  @Test
  public void aspectFragmentAccessError() {
    reporter.removeHandler(failFastHandler);
    assertThrows(
        ViewCreationFailedException.class,
        () ->
            analyzeConfiguredTargetForAspectFragment(
                "ctx.fragments.java.strict_java_deps", "'cpp'", "'cpp'"));
    assertContainsEvent(
        "//test:aspect.bzl%MyAspect aspect on my_rule has to declare 'java' as a "
            + "required fragment in order to access it. Please update the 'fragments' argument of "
            + "the rule definition (for example: fragments = [\"java\"])");
  }

  private void analyzeConfiguredTargetForAspectFragment(
      String fullFieldName, String fragments, String ruleFragments) throws Exception {
    scratch.file(
        "test/aspect.bzl",
        "AspectInfo = provider()",
        "def _aspect_impl(target, ctx):",
        "   return AspectInfo(result = str(" + fullFieldName + "))",
        "",
        "RuleInfo = provider()",
        "def _rule_impl(ctx):",
        "   return RuleInfo(stuff = '...')",
        "",
        "MyAspect = aspect(",
        "   implementation=_aspect_impl,",
        "   attr_aspects=['deps'],",
        "   fragments=[" + fragments + "],",
        ")",
        "my_rule = rule(",
        "   implementation=_rule_impl,",
        "   attrs = { 'attr' : ",
        "             attr.label_list(mandatory=True, allow_files=True, aspects = [MyAspect]) },",
        "   fragments=[" + ruleFragments + "],",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        exports_files(['zzz'])
        my_rule(
             name = 'yyy',
             attr = ['zzz'],
        )
        my_rule(
             name = 'xxx',
             attr = ['yyy'],
        )
        """);

    AnalysisResult result = update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    if (result.hasError()) {
      assertThat(keepGoing()).isTrue();
      String errorMessage = "Analysis failed";
      throw new ViewCreationFailedException(
          errorMessage,
          FailureDetail.newBuilder()
              .setMessage(errorMessage)
              .setAnalysis(Analysis.newBuilder().setCode(Code.ANALYSIS_UNKNOWN))
              .build());
    }

    assertThat(getConfiguredTarget("//test:xxx")).isNotNull();
  }

  @Test
  public void invalidateAspectOnBzlFileChange() throws Exception {
    scratch.file("test/build_defs.bzl", aspectBzlFile("'deps'"));
    scratch.file(
        "test/BUILD",
        """
        load(':build_defs.bzl', 'repro', 'repro_no_aspect')
        repro_no_aspect(name = 'r0')
        repro_no_aspect(name = 'r1', deps = [':r0'])
        repro(name = 'r2', deps = [':r1'])
        """);
    buildTargetAndCheckRuleInfo("@@//test:r0", "@@//test:r1");

    // Make aspect propagation list empty.
    scratch.overwriteFile("test/build_defs.bzl", aspectBzlFile(""));

    // The aspect should not propagate to //test:r0 anymore.
    buildTargetAndCheckRuleInfo("@@//test:r1");
  }

  private void buildTargetAndCheckRuleInfo(String... expectedLabels) throws Exception {
    AnalysisResult result = update(ImmutableList.of(), "//test:r2");
    ConfiguredTarget configuredTarget = result.getTargetsToBuild().iterator().next();
    Depset ruleInfoValue =
        (Depset) getStarlarkProvider(configuredTarget, "RuleInfo").getValue("rule_info");
    assertThat(ruleInfoValue.getSet(String.class).toList())
        .containsExactlyElementsIn(expectedLabels);
  }

  private static String[] aspectBzlFile(String attrAspects) {
    return new String[] {
      "AspectInfo = provider()",
      "def _repro_aspect_impl(target, ctx):",
      "    s = depset([str(target.label)], transitive =",
      "      [d[AspectInfo].aspect_info for d in ctx.rule.attr.deps if AspectInfo in d])",
      "    return AspectInfo(aspect_info = s)",
      "",
      "_repro_aspect = aspect(",
      "    _repro_aspect_impl,",
      "    attr_aspects = [" + attrAspects + "],",
      ")",
      "",
      "RuleInfo = provider()",
      "def repro_impl(ctx):",
      "    s = depset(transitive = ",
      "      [d[AspectInfo].aspect_info for d in ctx.attr.deps if AspectInfo in d])",
      "    return RuleInfo(rule_info = s)",
      "",
      "def repro_no_aspect_impl(ctx):",
      "    pass",
      "",
      "repro_no_aspect = rule(implementation = repro_no_aspect_impl,",
      "             attrs = {",
      "                       'deps': attr.label_list(",
      "                             allow_files = True,",
      "                       )",
      "                      },",
      ")",
      "",
      "repro = rule(implementation = repro_impl,",
      "             attrs = {",
      "                       'deps': attr.label_list(",
      "                             allow_files = True,",
      "                             aspects = [_repro_aspect],",
      "                       )",
      "                      },",
      ")"
    };
  }

  @Test
  public void aspectOutputsToBinDirectory() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
           file = ctx.actions.declare_file('aspect-output-' + target.label.name)
           ctx.actions.write(file, 'data')
           return AspectInfo(aspect_file = file)
        my_aspect = aspect(_aspect_impl)
        def _rule_impl(ctx):
           pass
        rule_bin_out = rule(_rule_impl, output_to_genfiles=False)
        rule_gen_out = rule(_rule_impl, output_to_genfiles=True)
        RuleInfo = provider()
        def _main_rule_impl(ctx):
           s = depset([d[AspectInfo].aspect_file for d in ctx.attr.deps])
           return RuleInfo(aspect_files = s)
        main_rule = rule(_main_rule_impl,
           attrs = { 'deps' : attr.label_list(aspects = [my_aspect]) },
        )
        """);

    scratch.file(
        "foo/BUILD",
        """
        load(':extension.bzl', 'rule_bin_out', 'rule_gen_out', 'main_rule')
        rule_bin_out(name = 'rbin')
        rule_gen_out(name = 'rgen')
        main_rule(name = 'main', deps = [':rbin', ':rgen'])
        """);
    AnalysisResult analysisResult = update(ImmutableList.of(), "//foo:main");
    ConfiguredTarget target = analysisResult.getTargetsToBuild().iterator().next();
    NestedSet<Artifact> aspectFiles =
        getStarlarkProvider(target, "RuleInfo")
            .getValue("aspect_files", Depset.class)
            .getSet(Artifact.class);
    assertThat(Iterables.transform(aspectFiles.toList(), Artifact::getFilename))
        .containsExactly("aspect-output-rbin", "aspect-output-rgen");
    for (Artifact aspectFile : aspectFiles.toList()) {
      String rootPath = aspectFile.getRoot().getExecPath().toString();
      assertWithMessage("Artifact %s should not be in genfiles", aspectFile)
          .that(rootPath)
          .doesNotContain("genfiles");
      assertWithMessage("Artifact %s should be in bin", aspectFile).that(rootPath).endsWith("bin");
    }
  }

  @Test
  public void toplevelAspectOnFile() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file("test/BUILD", "exports_files(['file.txt'])");
    scratch.file("test/file.txt", "");
    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:file.txt");
    assertThat(analysisResult.hasError()).isFalse();
    assertThat(
            Iterables.getOnlyElement(analysisResult.getAspectsMap().values())
                .getProviders()
                .getProviderCount())
        .isEqualTo(0);
  }

  @Test
  public void sharedAttributeDefinitionWithAspects() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _aspect_impl(target,ctx):
          return []
        my_aspect = aspect(implementation = _aspect_impl)
        _ATTR = { 'deps' : attr.label_list(aspects = [my_aspect]) }
        def _dummy_impl(ctx):
          pass
        r1 = rule(_dummy_impl, attrs =  _ATTR)
        r2 = rule(_dummy_impl, attrs =  _ATTR)
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 't1')
        r2(name = 't2', deps = [':t1'])
        """);
    AnalysisResult analysisResult = update("//test:t2");
    assertThat(analysisResult.hasError()).isFalse();
  }

  @Test
  public void multipleAspects() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _aspect_impl(target,ctx):
          return []
        my_aspect = aspect(implementation = _aspect_impl)
        def _dummy_impl(ctx):
          pass
        r1 = rule(_dummy_impl,
                  attrs = { 'deps' : attr.label_list(aspects = [my_aspect, my_aspect]) })
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r1')
        r1(name = 't1')
        """);
    reporter.removeHandler(failFastHandler);
    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult result = update("//test:r1");
      assertThat(result.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update("//test:r1"));
    }
    assertContainsEvent("aspect //test:aspect.bzl%my_aspect added more than once");
  }

  @Test
  public void topLevelAspectsAndExtraActions() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _aspect_impl(target,ctx):
          f = ctx.actions.declare_file('dummy.txt')
          ctx.actions.run_shell(outputs = [f], command='echo xxx > $(location f)',
                                mnemonic='AspectAction')
          return []
        my_aspect = aspect(implementation = _aspect_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        extra_action(
            name = 'xa',
            cmd = 'echo $(EXTRA_ACTION_FILE) > $(output file.xa)',
            out_templates = ['file.xa'],
        )
        action_listener(
            name = 'al',
            mnemonics = [ 'AspectAction' ],
            extra_actions = [ ':xa' ])
        java_library(name = 'xxx')
        """);
    useConfiguration("--experimental_action_listener=//test:al");
    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%my_aspect"), "//test:xxx");
    assertThat(Iterables.transform(analysisResult.getArtifactsToBuild(), Artifact::getFilename))
        .contains("file.xa");
  }

  /** Regression test for b/137960630. */
  @Test
  public void topLevelAspectsAndExtraActionsWithConflict() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _aspect_impl(target, ctx):
          f = ctx.actions.declare_file('dummy.txt')
          ctx.actions.run_shell(outputs = [f], command='echo xxx > $(location f)',
                                mnemonic='AspectAction')
          return []
        my_aspect = aspect(implementation = _aspect_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        extra_action(
            name = 'xa',
            cmd = 'echo $(EXTRA_ACTION_FILE) > $(output file.xa)',
            out_templates = ['file.xa'],
        )
        action_listener(
            name = 'al',
            mnemonics = ['AspectAction'],
            extra_actions = [':xa'],
        )
        java_library(name = 'xxx')
        java_library(name = 'yyy')
        """);
    useConfiguration("--experimental_action_listener=//test:al");
    reporter.removeHandler(failFastHandler); // We expect an error.

    if (keepGoing()) {
      AnalysisResult result =
          update(ImmutableList.of("test/aspect.bzl%my_aspect"), "//test:xxx", "//test:yyy");
      assertThat(result.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () -> update(ImmutableList.of("test/aspect.bzl%my_aspect"), "//test:xxx", "//test:yyy"));
    }
    assertContainsEvent(
        "file 'extra_actions/test/xa/test/file.xa' is generated by these conflicting actions");
  }

  @Test
  public void aspectsPropagatingToAllAttributes() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           s = depset([target.label], transitive =
             [i.target_labels for i in ctx.rule.attr.runtime_deps]
             if hasattr(ctx.rule.attr, 'runtime_deps') else [])
           return struct(target_labels = s)

        MyAspect = aspect(
            implementation=_impl,
            attrs = { '_tool' : attr.label(default = Label('//test:tool')) },
            attr_aspects=['*'],
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = 'tool',
        )
        java_library(
             name = 'bar',
             runtime_deps = [':tool'],
        )
        java_library(
             name = 'foo',
             runtime_deps = [':bar'],
        )
        """);
    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:foo");
    ConfiguredAspect configuredAspect = analysisResult.getAspectsMap().values().iterator().next();
    assertThat(configuredAspect).isNotNull();
    Object names = configuredAspect.get("target_labels");
    assertThat(names).isInstanceOf(Depset.class);
    assertThat(
            Iterables.transform(
                ((Depset) names).toList(),
                o -> {
                  assertThat(o).isInstanceOf(Label.class);
                  return ((Label) o).getName();
                }))
        .containsExactly("foo", "bar", "tool");
  }

  /** Simple straightforward linear aspects-on-aspects. */
  @Test
  public void aspectOnAspectLinear() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        a1p = provider()
        def _a1_impl(target,ctx):
          return a1p(text = 'random')
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          value = []
          if  ctx.rule.attr.dep and a2p in ctx.rule.attr.dep:
             value += ctx.rule.attr.dep[a2p].value
          if a1p in target:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=yes')
          else:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=no')
          return a2p(value = value)
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        """);
    AnalysisResult analysisResult = update("//test:r2");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    Sequence<?> result = getStarlarkProvider(target, "r2info").getValue("result", Sequence.class);

    // "yes" means that aspect a2 sees a1's providers.
    assertThat(result)
        .containsExactly(
            "@@//test:r0[\"//test:aspect.bzl%a1\", \"//test:aspect.bzl%a2\"]=yes",
            "@@//test:r1[\"//test:aspect.bzl%a2\"]=no");
  }

  /**
   * Diamond case. rule r1 depends or r0 with aspect a1. rule r2 depends or r0 with aspect a2. rule
   * rcollect depends on r1, r2 with aspect a3.
   *
   * <p>Aspect a3 should be applied twice to target r0: once in [a1, a3] sequence and once in [a2,
   * a3] sequence.
   */
  @Test
  public void aspectOnAspectDiamond() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        a1p = provider()
        a2p = provider()
        a3p = provider()
        def _a1_impl(target,ctx):
          return [a1p(value = 'text from a1')]
        a1 = aspect(_a1_impl, attr_aspects = ['deps'], provides = [a1p])

        def _a2_impl(target,ctx):
          return [a2p(value = 'text from a2')]
        a2 = aspect(_a2_impl, attr_aspects = ['deps'], provides = [a2p])

        def _a3_impl(target,ctx):
          value = []
          f = ctx.actions.declare_file('a3.out')
          ctx.actions.write(f, 'text')
          for dep in ctx.rule.attr.deps:
             if a3p in dep:
                 value += dep[a3p].value
          s = str(target.label) + str(ctx.aspect_ids) + '='
          if a1p in target:
             s += 'a1p'
          if a2p in target:
             s += 'a2p'
          value.append(s)
          return [a3p(value = value)]
        a3 = aspect(_a3_impl, attr_aspects = ['deps'],
                    required_aspect_providers = [[a1p], [a2p]])
        def _r1_impl(ctx):
          pass
        RCollectInfo = provider()
        def _rcollect_impl(ctx):
          value = []
          for dep in ctx.attr.deps:
             if a3p in dep:
                 value += dep[a3p].value
          return RCollectInfo(result = value)
        r1 = rule(_r1_impl, attrs = { 'deps' : attr.label_list(aspects = [a1])})
        r2 = rule(_r1_impl, attrs = { 'deps' : attr.label_list(aspects = [a2])})
        rcollect = rule(_rcollect_impl, attrs = { 'deps' : attr.label_list(aspects = [a3])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r1', 'r2', 'rcollect')
        r1(name = 'r0')
        r1(name = 'r1', deps = [':r0'])
        r2(name = 'r2', deps = [':r0'])
        rcollect(name = 'rcollect', deps = [':r1', ':r2'])
        """);
    AnalysisResult analysisResult = update("//test:rcollect");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    Sequence<?> result =
        (Sequence<?>) getStarlarkProvider(target, "RCollectInfo").getValue("result");
    assertThat(result)
        .containsExactly(
            "@@//test:r0[\"//test:aspect.bzl%a1\", \"//test:aspect.bzl%a3\"]=a1p",
            "@@//test:r1[\"//test:aspect.bzl%a3\"]=",
            "@@//test:r0[\"//test:aspect.bzl%a2\", \"//test:aspect.bzl%a3\"]=a2p",
            "@@//test:r2[\"//test:aspect.bzl%a3\"]=");
  }

  /**
   * Linear with duplicates. r2_1 depends on r0 with aspect a2. r1 depends on r2_1 with aspect a1.
   * r2 depends on r1 with aspect a2.
   *
   * <p>a2 is not interested in a1. There should be just one instance of aspect a2 on r0, and is
   * should *not* see a1.
   */
  @Test
  public void aspectOnAspectLinearDuplicates() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        a1p = provider()
        def _a1_impl(target,ctx):
          return a1p()
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          value = []
          if ctx.rule.attr.dep and a2p in ctx.rule.attr.dep:
             value += ctx.rule.attr.dep[a2p].value
          if a1p in target:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=yes')
          else:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=no')
          return a2p(value = value)
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        r2(name = 'r2_1', dep = ':r0')
        r1(name = 'r1', dep = ':r2_1')
        r2(name = 'r2', dep = ':r1')
        """);
    AnalysisResult analysisResult = update("//test:r2");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    Sequence<?> result = getStarlarkProvider(target, "r2info").getValue("result", Sequence.class);
    // "yes" means that aspect a2 sees a1's providers.
    assertThat(result)
        .containsExactly(
            "@@//test:r0[\"//test:aspect.bzl%a2\"]=no",
            "@@//test:r1[\"//test:aspect.bzl%a2\"]=no",
            "@@//test:r2_1[\"//test:aspect.bzl%a2\"]=no");
  }

  /** Linear aspects-on-aspects with alias rule. */
  @Test
  public void aspectOnAspectLinearAlias() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        a1p = provider()
        def _a1_impl(target,ctx):
          return [a1p(text = 'random')]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          value = []
          if ctx.rule.attr.dep and a2p in ctx.rule.attr.dep:
             value += ctx.rule.attr.dep[a2p].value
          if a1p in target:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=yes')
          else:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=no')
          return [a2p(value = value)]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        alias(name = 'a0', actual = ':r0')
        r1(name = 'r1', dep = ':a0')
        r2(name = 'r2', dep = ':r1')
        """);
    AnalysisResult analysisResult = update("//test:r2");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    Sequence<?> result = getStarlarkProvider(target, "r2info").getValue("result", Sequence.class);

    // "yes" means that aspect a2 sees a1's providers.
    assertThat(result)
        .containsExactly(
            "@@//test:r0[\"//test:aspect.bzl%a1\", \"//test:aspect.bzl%a2\"]=yes",
            "@@//test:r1[\"//test:aspect.bzl%a2\"]=no");
  }

  @Test
  public void aspectDescriptions() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
AspectInfo = provider()
def _a_impl(target,ctx):
  s = str(target.label) + str(ctx.aspect_ids) + '='
  value = []
  if ctx.rule.attr.dep:
     d = ctx.rule.attr.dep
     this_id = ctx.aspect_ids[len(ctx.aspect_ids) - 1]
     s += str(d.label) + str(d[AspectInfo].my_ids) + ',' + str(this_id in d[AspectInfo].my_ids)
     value += ctx.rule.attr.dep[AspectInfo].ap
  else:
     s += 'None'
  value.append(s)
  return AspectInfo(ap = value, my_ids = ctx.aspect_ids)
a = aspect(_a_impl, attr_aspects = ['dep'])
RuleInfo = provider()
def _r_impl(ctx):
  if not ctx.attr.dep:
     return RuleInfo(result = [])
  return RuleInfo(result = ctx.attr.dep[AspectInfo].ap)
r = rule(_r_impl, attrs = { 'dep' : attr.label(aspects = [a])})
""");
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r')
        r(name = 'r0')
        r(name = 'r1', dep = ':r0')
        r(name = 'r2', dep = ':r1')
        """);
    AnalysisResult analysisResult = update("//test:r2");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    Sequence<?> result = (Sequence<?>) getStarlarkProvider(target, "RuleInfo").getValue("result");

    assertThat(result)
        .containsExactly(
            "@@//test:r0[\"//test:aspect.bzl%a\"]=None",
            "@@//test:r1[\"//test:aspect.bzl%a\"]=@@//test:r0[\"//test:aspect.bzl%a\"],True");
  }

  @Test
  public void attributesWithAspectsReused() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        my_aspect = aspect(_impl)
        a_dict = { 'foo' : attr.label_list(aspects = [my_aspect]) }
        """);

    scratch.file(
        "test/r1.bzl",
        """
        load(':aspect.bzl', 'my_aspect', 'a_dict')
        def _rule_impl(ctx):
           pass
        r1 = rule(_rule_impl, attrs = a_dict)
        """);

    scratch.file(
        "test/r2.bzl",
        """
        load(':aspect.bzl', 'my_aspect', 'a_dict')
        def _rule_impl(ctx):
           pass
        r2 = rule(_rule_impl, attrs = a_dict)
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':r1.bzl', 'r1')
        load(':r2.bzl', 'r2')
        r1(name = 'x1')
        r2(name = 'x2', foo = [':x1'])
        """);
    AnalysisResult analysisResult = update("//test:x2");
    assertThat(analysisResult.hasError()).isFalse();
  }

  @Test
  public void aspectAdvertisingProviders() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        my_aspect = aspect(_impl, provides = ['foo'])
        a_dict = { 'foo' : attr.label_list(aspects = [my_aspect]) }
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    reporter.removeHandler(failFastHandler);
    try {
      AnalysisResult analysisResult =
          update(ImmutableList.of("//test:aspect.bzl%my_aspect"), "//test:xxx");
      assertThat(keepGoing()).isTrue();
      assertThat(analysisResult.hasError()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expect exception
    }
    assertContainsEvent(
        "Aspect '//test:aspect.bzl%my_aspect', applied to '//test:xxx', "
            + "does not provide advertised provider 'foo'");
  }

  @Test
  public void aspectOnAspectInconsistentVisibility() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        a1p = provider()
        def _a1_impl(target,ctx):
          return [a1p(text = 'random')]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          return [a2p(value = 'random')]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        r1(name = 'r1_1', dep = ':r2')
        r2(name = 'r2_1', dep = ':r1_1')
        """);
    reporter.removeHandler(failFastHandler);

    try {
      AnalysisResult analysisResult = update("//test:r2_1");
      assertThat(analysisResult.hasError()).isTrue();
      assertThat(keepGoing()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expected
    }
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:3:3: Aspect //test:aspect.bzl%a2 is"
            + " applied twice, both before and after aspect //test:aspect.bzl%a1 "
            + "(when propagating to //test:r1)");
  }

  @Test
  public void aspectOnAspectInconsistentVisibilityIndirect() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        a1p = provider()
        def _a1_impl(target,ctx):
          return [a1p(text = 'random')]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          return [a2p(value = 'random')]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        def _r0_impl(ctx):
          pass
        r0 = rule(_r0_impl, attrs = { 'dep' : attr.label()})
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r0', 'r1', 'r2')
        r0(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        r1(name = 'r1_1', dep = ':r2')
        r2(name = 'r2_1', dep = ':r1_1')
        r0(name = 'r0_2', dep = ':r2_1')
        """);
    reporter.removeHandler(failFastHandler);

    try {
      AnalysisResult analysisResult = update("//test:r0_2");
      assertThat(analysisResult.hasError()).isTrue();
      assertThat(keepGoing()).isTrue();
    } catch (ViewCreationFailedException e) {
      // expected
    }
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:3:3: Aspect //test:aspect.bzl%a2 is"
            + " applied twice, both before and after aspect //test:aspect.bzl%a1 "
            + "(when propagating to //test:r1)");
  }

  /**
   * Aspect a3 sees aspect a2, aspect a2 sees aspect a1, but a3 does not see a1. All three aspects
   * should still propagate together.
   */
  @Test
  public void aspectOnAspectOnAspect() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        p1 = provider()
        def _a1_impl(target, ctx):
           return [p1()]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [p1])
        p2 = provider()
        def _a2_impl(target, ctx):
           value = True if p1 in target else False
           return [p2(has_p1 = value)]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'],
           required_aspect_providers = [p1], provides = [p2])
        p3 = provider()
        def _a3_impl(target, ctx):
           list = []
           if ctx.rule.attr.dep:
             list = ctx.rule.attr.dep[p3].value
           my_value = str(target.label) +'=' + str(target[p2].has_p1 if p2 in target else False)
           return [p3(value = list + [my_value])]
        a3 = aspect(_a3_impl, attr_aspects = ['dep'],
           required_aspect_providers = [p2])
        def _r0_impl(ctx):
          pass
        r0 = rule(_r0_impl, attrs = { 'dep' : attr.label()})
        def _r1_impl(ctx):
          pass
        def _r2_impl(ctx):
          pass
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r0', 'r1', 'r2')
        r0(name = 'r0_1')
        r0(name = 'r0_2', dep = ':r0_1')
        r0(name = 'r0_3', dep = ':r0_2')
        r1(name = 'r1_1', dep = ':r0_3')
        r2(name = 'r2_1', dep = ':r1_1')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of("//test:aspect.bzl%a3"), "//test:r2_1");
    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    StarlarkProvider.Key p3 =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "p3");
    StructImpl p3Provider = (StructImpl) configuredAspect.get(p3);
    assertThat((Sequence<?>) p3Provider.getValue("value"))
        .containsExactly(
            "@@//test:r0_1=True",
            "@@//test:r0_2=True",
            "@@//test:r0_3=True",
            "@@//test:r1_1=False",
            "@@//test:r2_1=False");
  }

  /**
   * r0 is a dependency of r1 via two attributes, dep1 and dep2. r1 sends an aspect 'a' along dep1
   * but not along dep2.
   *
   * <p>rcollect depends upon r1 and sends another aspect, 'collector', along its dep dependency.
   * 'collector' wants to see aspect 'a' and propagates along dep1 and dep2. It should be applied
   * both to r0 and to r0+a.
   */
  @Test
  public void multipleDepsDifferentAspects() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        PAspect = provider()
        PCollector = provider()
        def _aspect_impl(target, ctx):
           return [PAspect()]
        a = aspect(_aspect_impl, attr_aspects = ['dep'], provides = [PAspect])
        def _collector_impl(target, ctx):
           suffix = '+PAspect' if PAspect in target else ''
           result = [str(target.label)+suffix]
           for a in ['dep', 'dep1', 'dep2']:
             if hasattr(ctx.rule.attr, a):
                result += getattr(ctx.rule.attr, a)[PCollector].result
           return [PCollector(result=result)]
        collector = aspect(_collector_impl, attr_aspects = ['*'],
                           required_aspect_providers = [PAspect])
        def _rimpl(ctx):
           pass
        r0 = rule(_rimpl)
        r1 = rule(_rimpl,
                  attrs = {
                     'dep1' : attr.label(),
                     'dep2' : attr.label(aspects = [a]),
                  },
        )
        def _rcollect_impl(ctx):
            return [ctx.attr.dep[PCollector]]
        rcollect = rule(_rcollect_impl,
                        attrs = {
                          'dep' : attr.label(aspects = [collector]),
                        })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r0', 'r1', 'rcollect')
        r0(name = 'r0')
        r1(name = 'r1', dep1 = ':r0', dep2 = ':r0')
        rcollect(name = 'rcollect', dep = ':r1')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of(), "//test:rcollect");
    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkProvider.Key pCollector =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector");
    StructImpl pCollectorProvider = (StructImpl) configuredTarget.get(pCollector);
    assertThat((Sequence<?>) pCollectorProvider.getValue("result"))
        .containsExactly("@@//test:r1", "@@//test:r0", "@@//test:r0+PAspect");
  }

  @Test
  public void aspectSeesOtherAspectAttributes() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        PAspect = provider(fields = [])
        PCollector = provider(fields = ['aspect_attr'])
        def _a_impl(target, ctx):
          return [PAspect()]
        a = aspect(_a_impl,
                   provides = [PAspect],
                   attrs = {'_a_attr' : attr.label(default = '//test:foo')})
        def _rcollect(target, ctx):
          if hasattr(ctx.rule.attr, '_a_attr'):
             return [PCollector(aspect_attr = ctx.rule.attr._a_attr.label)]
          if hasattr(ctx.rule.attr, 'dep'):
             return [ctx.rule.attr.dep[PCollector]]
          return [PCollector()]
        acollect = aspect(_rcollect, attr_aspects = ['*'], required_aspect_providers = [PAspect])
        def _rimpl(ctx):
          pass
        r0 = rule(_rimpl)
        r = rule(_rimpl, attrs = { 'dep' : attr.label(aspects = [a]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r0', 'r')
        r0(name = 'foo')
        r0(name = 'bar')
        r(name = 'baz', dep = ':bar')
        """);
    AnalysisResult analysisResult =
        update(ImmutableList.of("//test:aspect.bzl%acollect"), "//test:baz");
    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    StarlarkProvider.Key pCollector =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector");
    StructImpl collector = (StructImpl) configuredAspect.get(pCollector);
    assertThat(collector.getValue("aspect_attr")).isEqualTo(Label.parseCanonical("//test:foo"));
  }

  @Test
  public void ruleAttributesWinOverAspects() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        PAspect = provider(fields = [])
        PCollector = provider(fields = ['attr_value'])
        def _a_impl(target, ctx):
          return [PAspect()]
        a = aspect(_a_impl,
                   provides = [PAspect],
                   attrs = {'_same_attr' : attr.int(default = 239)})
        def _rcollect(target, ctx):
          if hasattr(ctx.rule.attr, '_same_attr'):
             return [PCollector(attr_value = ctx.rule.attr._same_attr)]
          if hasattr(ctx.rule.attr, 'dep'):
             return [ctx.rule.attr.dep[PCollector]]
          return [PCollector()]
        acollect = aspect(_rcollect, attr_aspects = ['*'], required_aspect_providers = [PAspect])
        def _rimpl(ctx):
          pass
        r0 = rule(_rimpl)
        r = rule(_rimpl,
                  attrs = {
                          'dep' : attr.label(aspects = [a]),
                          '_same_attr' : attr.int(default = 30)
                  })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r0', 'r')
        r0(name = 'foo')
        r0(name = 'bar')
        r(name = 'baz', dep = ':bar')
        """);
    AnalysisResult analysisResult =
        update(ImmutableList.of("//test:aspect.bzl%acollect"), "//test:baz");
    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    StarlarkProvider.Key pCollector =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector");
    StructImpl collector = (StructImpl) configuredAspect.get(pCollector);
    assertThat(collector.getValue("attr_value")).isEqualTo(StarlarkInt.of(30));
  }

  @Test
  public void earlyAspectAttributesWin() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        PAspect1 = provider(fields = [])
        PAspect2 = provider(fields = [])
        PCollector = provider(fields = ['attr_value'])
        def _a1_impl(target, ctx):
          return [PAspect1()]
        def _a2_impl(target, ctx):
          return [PAspect2()]
        a1 = aspect(_a1_impl,
                    provides = [PAspect1],
                    attrs = {'_same_attr' : attr.int(default = 30)})
        a2 = aspect(_a2_impl,
                    provides = [PAspect2],
                    attrs = {'_same_attr' : attr.int(default = 239)})
        def _rcollect(target, ctx):
          if hasattr(ctx.rule.attr, 'dep'):
             return [ctx.rule.attr.dep[PCollector]]
          if hasattr(ctx.rule.attr, '_same_attr'):
             return [PCollector(attr_value = ctx.rule.attr._same_attr)]
          fail('???')
          return [PCollector()]
        acollect = aspect(_rcollect, attr_aspects = ['*'],
                          required_aspect_providers = [[PAspect1], [PAspect2]])
        def _rimpl(ctx):
          pass
        r0 = rule(_rimpl)
        r1 = rule(_rimpl,
                  attrs = {
                          'dep' : attr.label(aspects = [a1]),
                  })
        r2 = rule(_rimpl,
                  attrs = {
                          'dep' : attr.label(aspects = [a2]),
                  })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r0', 'r1', 'r2')
        r0(name = 'bar')
        r1(name = 'baz', dep = ':bar')
        r2(name = 'quux', dep = ':baz')
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("//test:aspect.bzl%acollect"), "//test:quux");
    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    StarlarkProvider.Key pCollector =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector");
    StructImpl collector = (StructImpl) configuredAspect.get(pCollector);
    assertThat(collector.getValue("attr_value")).isEqualTo(StarlarkInt.of(30));
  }

  @Test
  public void aspectPropagatesOverOtherAspectAttributes() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        PAspect = provider(fields = [])
        PCollector = provider(fields = ['visited'])
        def _a_impl(target, ctx):
          return [PAspect()]
        a = aspect(_a_impl,
               provides = [PAspect],
               attrs = {'_a_attr' : attr.label(default = '//test:referenced_from_aspect_only')})
        def _rcollect(target, ctx):
          transitive = []
          if hasattr(ctx.rule.attr, 'dep') and ctx.rule.attr.dep:
             transitive += [ctx.rule.attr.dep[PCollector].visited]
          if hasattr(ctx.rule.attr, '_a_attr') and ctx.rule.attr._a_attr:
             transitive += [ctx.rule.attr._a_attr[PCollector].visited]
          visited = depset([target.label], transitive = transitive, )
          return [PCollector(visited = visited)]
        acollect = aspect(_rcollect, attr_aspects = ['*'], required_aspect_providers = [PAspect])
        def _rimpl(ctx):
          pass
        r0 = rule(_rimpl)
        r = rule(_rimpl, attrs = { 'dep' : attr.label(aspects = [a]) })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r0', 'r')
        r0(name = 'referenced_from_aspect_only')
        r0(name = 'bar')
        r(name = 'baz', dep = ':bar')
        """);
    AnalysisResult analysisResult =
        update(ImmutableList.of("//test:aspect.bzl%acollect"), "//test:baz");
    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    StarlarkProvider.Key pCollector =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector");
    StructImpl collector = (StructImpl) configuredAspect.get(pCollector);
    assertThat(((Depset) collector.getValue("visited")).toList())
        .containsExactly(
            Label.parseCanonical("//test:referenced_from_aspect_only"),
            Label.parseCanonical("//test:bar"),
            Label.parseCanonical("//test:baz"));
  }

  @Test
  public void testAspectActionProvider() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        a1p = provider()
        def _a1_impl(target,ctx):
          ctx.actions.run_shell(
            outputs = [ctx.actions.declare_file('a1')],
            command = 'touch $@'
          )
          return [a1p()]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target, ctx):
          value = []
          if hasattr(ctx.rule.attr, 'dep') and a2p in ctx.rule.attr.dep:
             value += ctx.rule.attr.dep[a2p].value
          value += target.actions
          return [a2p(value = value)]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r0_impl(ctx):
          ctx.actions.run_shell(
            outputs = [ctx.actions.declare_file('r0')],
            command = 'touch $@'
          )
        RuleInfo = provider()
        def _r1_impl(ctx):
          pass
        def _r2_impl(ctx):
          return RuleInfo(result = ctx.attr.dep[a2p].value)
        r0 = rule(_r0_impl)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r0', 'r1', 'r2')
        r0(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        """);
    AnalysisResult analysisResult = update("//test:r2");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    Sequence<?> result = (Sequence<?>) getStarlarkProvider(target, "RuleInfo").getValue("result");

    // We should see both the action from the 'r0' rule, and the action from the 'a1' aspect
    assertThat(result).hasSize(2);
    assertThat(
            result.stream()
                .map(a -> ((Action) a).getPrimaryOutput().getExecPath().getBaseName())
                .collect(toList()))
        .containsExactly("r0", "a1");
  }

  @Test
  public void testRuleAndAspectAttrConflict() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        MyInfo = provider()
        def _impl(target, ctx):
           return [MyInfo(hidden_attr_label = str(ctx.attr._hiddenattr.label))]

        def _rule_impl(ctx):
           return []

        my_rule = rule(implementation = _rule_impl,
           attrs = { '_hiddenattr' : attr.label(default = Label('//test:xxx')) },
        )
        MyAspect = aspect(
           implementation=_impl,
           attrs = { '_hiddenattr' : attr.label(default = Label('//test:zzz')) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        cc_library(
             name = 'xxx',
        )
        my_rule(
             name = 'yyy',
        )
        cc_library(
             name = 'zzz',
        )
        """);
    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:yyy");
    ConfiguredAspect configuredAspect = analysisResult.getAspectsMap().values().iterator().next();
    assertThat(configuredAspect).isNotNull();

    StarlarkProvider.Key myInfoKey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "MyInfo");
    StructImpl myInfo = (StructImpl) configuredAspect.get(myInfoKey);
    assertThat(myInfo.getValue("hidden_attr_label")).isEqualTo("@@//test:zzz");
  }

  /** Simple straightforward linear aspects-on-aspects. */
  @Test
  public void aspectOnAspectAttrConflict() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        MyInfo = provider()
        a1p = provider()
        def _a1_impl(target,ctx):
          return a1p(text = 'random')
        a1 = aspect(_a1_impl,
           attrs = { '_hiddenattr' : attr.label(default = Label('//test:xxx')) },
           attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
           return [MyInfo(hidden_attr_label = str(ctx.attr._hiddenattr.label))]
        a2 = aspect(_a2_impl,
          attrs = { '_hiddenattr' : attr.label(default = Label('//test:zzz')) },
          attr_aspects = ['dep'], required_aspect_providers = [a1p])
        RuleInfo = provider()
        def _r1_impl(ctx):
          pass
        def _r2_impl(ctx):
          return RuleInfo(result = ctx.attr.dep[MyInfo].hidden_attr_label)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a1, a2])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        cc_library(
             name = 'xxx',
        )
        cc_library(
             name = 'zzz',
        )
        """);

    AnalysisResult analysisResult = update("//test:r2");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    String result = getStarlarkProvider(target, "RuleInfo").getValue("result", String.class);

    assertThat(result).isEqualTo("@@//test:zzz");
  }

  @Test
  public void testAllCcLibraryAttrsAreValidTypes() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
          for entry in dir(ctx.rule.attr):
            val = getattr(ctx.rule.attr, entry, None)
            # Only legitimate Starlark values can be passed to dir(), so this effectively
            # verifies val is an appropriate Starlark type.
            _test_dir = dir(val)
          return []

        MyAspect = aspect(
          implementation=_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        cc_library(
             name = 'xxx',
        )
        """);
    AnalysisResult analysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");
    assertThat(analysisResult.getAspectsMap().values().iterator().next()).isNotNull();
  }

  @Test
  public void testApplyToGeneratingRules() throws Exception {
    // Create test rules:
    // dep_rule: a rule which may depend on other dep_rule targets and may optionally create
    //     an output file.
    // root_{with,no}_files: a rule which depends on dep_rule targets and attaches an aspect.
    //     The rule returns a RootInfo provider which contains two fields:
    //        'from_aspect' : a list of all labels that the aspect propagated to
    //        'non_aspect' : a list of all labels that information was obtained from without aspect
    //     root_with_files uses an aspect with apply_to_generating_rules=True, and root_no_files
    //     uses an aspect with apply_to_generating_rules=False.
    scratch.file(
        "test/lib.bzl",
        """
        RootInfo = provider()
        NonAspectInfo = provider()
        FromAspectInfo = provider()
        def _aspect_impl(target, ctx):
          dep_labels = []
          for dep in ctx.rule.attr.deps:
            if FromAspectInfo in dep:
              dep_labels += [dep[FromAspectInfo].labels]
          return FromAspectInfo(labels = depset(direct = [ctx.label], transitive = dep_labels))

        def _rule_impl(ctx):
          non_aspect = []
          from_aspect = []
          for dep in ctx.attr.deps:
            if NonAspectInfo in dep:
              non_aspect +=  dep[NonAspectInfo].labels.to_list()
            if FromAspectInfo in dep:
              from_aspect += dep[FromAspectInfo].labels.to_list()
          return RootInfo(from_aspect = from_aspect, non_aspect = non_aspect)

        def _dep_rule_impl(ctx):
          if ctx.outputs.output:
            ctx.actions.run_shell(outputs = [ctx.outputs.output], command = 'dont run me')
          dep_labels = []
          for dep in ctx.attr.deps:
            if NonAspectInfo in dep:
              dep_labels += [dep[NonAspectInfo].labels]
          return NonAspectInfo(labels = depset(direct = [ctx.label], transitive = dep_labels))

        aspect_with_files = aspect(
          implementation = _aspect_impl,
          attr_aspects = ['deps'],
          apply_to_generating_rules = True)

        aspect_no_files = aspect(
          implementation = _aspect_impl,
          attr_aspects = ['deps'],
          apply_to_generating_rules = False)

        root_with_files = rule(implementation = _rule_impl,
          attrs = {'deps' : attr.label_list(aspects = [aspect_with_files])})

        root_no_files = rule(implementation = _rule_impl,
          attrs = {'deps' : attr.label_list(aspects = [aspect_no_files])})

        dep_rule = rule(implementation = _dep_rule_impl,
          attrs = {'deps' : attr.label_list(allow_files = True), 'output' : attr.output()})
        """);

    // Create a target graph such that two graph roots each point to a common subgraph
    // alpha -> beta_output -> charlie, where beta_output is a generated output file of target
    // 'beta'.
    scratch.file(
        "test/BUILD",
        """
        load('//test:lib.bzl', 'root_with_files', 'root_no_files', 'dep_rule')

        root_with_files(name = 'test_with_files', deps = [':alpha'])
        root_no_files(name = 'test_no_files', deps = [':alpha'])
        dep_rule(name = 'alpha', deps = [':beta_output'])
        dep_rule(name = 'beta', deps = [':charlie'], output = 'beta_output')
        dep_rule(name = 'charlie')
        """);

    StarlarkProvider.Key rootInfoKey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootInfo");

    AnalysisResult analysisResultWithFiles = update("//test:test_with_files");
    ConfiguredTarget targetWithFiles =
        Iterables.getOnlyElement(analysisResultWithFiles.getTargetsToBuild());
    StructImpl rootInfoWithFiles = (StructImpl) targetWithFiles.get(rootInfoKey);
    // With apply_to_generating_rules=True, the aspect should have traversed :beta_output and
    // applied to both :beta and :charlie.
    assertThat(rootInfoWithFiles.getValue("from_aspect", Sequence.class))
        .containsExactly(
            Label.parseCanonical("//test:charlie"),
            Label.parseCanonical("//test:beta"),
            Label.parseCanonical("//test:alpha"));
    assertThat(rootInfoWithFiles.getValue("non_aspect", Sequence.class))
        .containsExactly(Label.parseCanonical("//test:alpha"));

    AnalysisResult analysisResultNoFiles = update("//test:test_no_files");
    ConfiguredTarget targetNoFiles =
        Iterables.getOnlyElement(analysisResultNoFiles.getTargetsToBuild());
    StructImpl rootInfoNoFiles = (StructImpl) targetNoFiles.get(rootInfoKey);
    // With apply_to_generating_rules=False, the aspect should have only accessed :alpha, as it
    // must have stopped before :beta_output.
    assertThat(rootInfoNoFiles.getValue("from_aspect", Sequence.class))
        .containsExactly(Label.parseCanonical("//test:alpha"));
    assertThat(rootInfoWithFiles.getValue("non_aspect", Sequence.class))
        .containsExactly(Label.parseCanonical("//test:alpha"));
  }

  private void setupAspectOnAspectTargetGraph(
      boolean applyRootToGeneratingRules, boolean applyDepToGeneratingRules) throws Exception {
    // RootAspectInfo.both_labels returns a list of target labels which
    //     were evaluated as [root_aspect(dep_aspect(target))].
    // RootAspectInfo.root_only_labels returns a list of target labels which
    //     were evaluated as [root_aspect(target)].
    // DepAspectInfo.labels returns a list of target labels which were evaluated by dep_aspect.
    scratch.file(
        "test/lib.bzl",
        "RootAspectInfo = provider()",
        "DepAspectInfo = provider()",
        "def _root_aspect_impl(target, ctx):",
        "  both_labels = []",
        "  root_only_labels = []",
        "  for dep in ctx.rule.attr.deps:",
        "    if RootAspectInfo in dep:",
        "      both_labels += dep[RootAspectInfo].both_labels",
        "      root_only_labels += dep[RootAspectInfo].root_only_labels",
        "      if DepAspectInfo in dep:",
        "        both_labels += [dep.label]",
        "      else:",
        "        root_only_labels += [dep.label]",
        "  return RootAspectInfo(both_labels = both_labels, root_only_labels = root_only_labels)",
        "",
        "def _dep_aspect_impl(target, ctx):",
        "  dep_labels = [ctx.label]",
        "  for dep in ctx.rule.attr.deps:",
        "    if DepAspectInfo in dep:",
        "      dep_labels += dep[DepAspectInfo].labels",
        "  return DepAspectInfo(labels = dep_labels)",
        "",
        "def _root_rule_impl(ctx):",
        "  return [ctx.attr.deps[0][RootAspectInfo], ctx.attr.deps[0][DepAspectInfo]]",
        "",
        "def _dep_with_aspect_rule_impl(ctx):",
        "  return [ctx.attr.deps[0][DepAspectInfo]]",
        "",
        "def _dep_rule_impl(ctx):",
        "  if ctx.outputs.output:",
        "    ctx.actions.run_shell(outputs = [ctx.outputs.output], command = 'dont run me')",
        "  return []",
        "",
        "root_aspect = aspect(",
        "  implementation = _root_aspect_impl,",
        "  attr_aspects = ['deps'],",
        "  required_aspect_providers = [DepAspectInfo],",
        "  apply_to_generating_rules = " + (applyRootToGeneratingRules ? "True" : "False") + ")",
        "",
        "dep_aspect = aspect(",
        "  implementation = _dep_aspect_impl,",
        "  attr_aspects = ['deps'],",
        "  provides = [DepAspectInfo],",
        "  apply_to_generating_rules = " + (applyDepToGeneratingRules ? "True" : "False") + ")",
        "",
        "root_rule = rule(implementation = _root_rule_impl,",
        "  attrs = {'deps' : attr.label_list(aspects = [root_aspect])})",
        "",
        "dep_with_aspect_rule = rule(implementation = _dep_with_aspect_rule_impl,",
        "  attrs = {'deps' : attr.label_list(aspects = [dep_aspect])})",
        "",
        "dep_rule = rule(implementation = _dep_rule_impl,",
        "  attrs = {'deps' : attr.label_list(allow_files = True), 'output' : attr.output()})");

    // Target graph: test -> aspect_propagating_target -> alpha -> beta_output
    // beta_output is an output of target `beta`
    // beta -> charlie
    scratch.file(
        "test/BUILD",
        """
        load('//test:lib.bzl', 'root_rule', 'dep_with_aspect_rule', 'dep_rule')

        root_rule(name = 'test', deps = [':aspect_propagating_target'])
        dep_with_aspect_rule(name = 'aspect_propagating_target', deps = [':alpha'])
        dep_rule(name = 'alpha', deps = [':beta_output'])
        dep_rule(name = 'beta', deps = [':charlie'], output = 'beta_output')
        dep_rule(name = 'charlie')
        """);
  }

  @Test
  public void testAspectOnAspectApplyToGeneratingRules_bothPropagate() throws Exception {
    setupAspectOnAspectTargetGraph(
        /* applyRootToGeneratingRules= */ true, /* applyDepToGeneratingRules= */ true);

    StarlarkProvider.Key rootInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootAspectInfo");
    StarlarkProvider.Key depInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:lib.bzl")), "DepAspectInfo");

    AnalysisResult analysisResult = update("//test:test");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StructImpl rootInfo = (StructImpl) target.get(rootInfoKey);
    StructImpl depInfo = (StructImpl) target.get(depInfoKey);

    assertThat(rootInfo.getValue("both_labels", Sequence.class))
        .containsExactly(
            Label.parseCanonical("//test:alpha"),
            Label.parseCanonical("//test:beta_output"),
            Label.parseCanonical("//test:charlie"));
    assertThat(rootInfo.getValue("root_only_labels", Sequence.class)).isEmpty();
    assertThat(depInfo.getValue("labels", Sequence.class))
        .containsExactly(
            Label.parseCanonical("//test:alpha"),
            Label.parseCanonical("//test:beta"),
            Label.parseCanonical("//test:charlie"));
  }

  @Test
  public void testAspectOnAspectApplyToGeneratingRules_neitherPropagate() throws Exception {
    setupAspectOnAspectTargetGraph(
        /* applyRootToGeneratingRules= */ false, /* applyDepToGeneratingRules= */ false);

    StarlarkProvider.Key rootInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootAspectInfo");
    StarlarkProvider.Key depInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:lib.bzl")), "DepAspectInfo");

    AnalysisResult analysisResult = update("//test:test");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StructImpl rootInfo = (StructImpl) target.get(rootInfoKey);
    StructImpl depInfo = (StructImpl) target.get(depInfoKey);

    assertThat(rootInfo.getValue("both_labels", Sequence.class))
        .containsExactly(Label.parseCanonical("//test:alpha"));
    assertThat(rootInfo.getValue("root_only_labels", Sequence.class)).isEmpty();
    assertThat(depInfo.getValue("labels", Sequence.class))
        .containsExactly(Label.parseCanonical("//test:alpha"));
  }

  @Test
  public void testAspectOnAspectApplyToGeneratingRules_rootOnly() throws Exception {
    setupAspectOnAspectTargetGraph(
        /* applyRootToGeneratingRules= */ true, /* applyDepToGeneratingRules= */ false);

    StarlarkProvider.Key rootInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootAspectInfo");
    StarlarkProvider.Key depInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:lib.bzl")), "DepAspectInfo");

    AnalysisResult analysisResult = update("//test:test");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StructImpl rootInfo = (StructImpl) target.get(rootInfoKey);
    StructImpl depInfo = (StructImpl) target.get(depInfoKey);

    assertThat(rootInfo.getValue("both_labels", Sequence.class))
        .containsExactly(Label.parseCanonical("//test:alpha"));
    assertThat(rootInfo.getValue("root_only_labels", Sequence.class))
        .containsExactly(
            Label.parseCanonical("//test:beta_output"), Label.parseCanonical("//test:charlie"));
    assertThat(depInfo.getValue("labels", Sequence.class))
        .containsExactly(Label.parseCanonical("//test:alpha"));
  }

  @Test
  public void testAspectOnAspectApplyToGeneratingRules_depOnly() throws Exception {
    setupAspectOnAspectTargetGraph(
        /* applyRootToGeneratingRules= */ false, /* applyDepToGeneratingRules= */ true);

    StarlarkProvider.Key rootInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootAspectInfo");
    StarlarkProvider.Key depInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:lib.bzl")), "DepAspectInfo");

    AnalysisResult analysisResult = update("//test:test");
    ConfiguredTarget target = Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StructImpl rootInfo = (StructImpl) target.get(rootInfoKey);
    StructImpl depInfo = (StructImpl) target.get(depInfoKey);

    assertThat(rootInfo.getValue("both_labels", Sequence.class))
        .containsExactly(Label.parseCanonical("//test:alpha"));
    assertThat(rootInfo.getValue("root_only_labels", Sequence.class)).isEmpty();
    assertThat(depInfo.getValue("labels", Sequence.class))
        .containsExactly(
            Label.parseCanonical("//test:alpha"),
            Label.parseCanonical("//test:beta"),
            Label.parseCanonical("//test:charlie"));
  }

  @Test
  public void testAspectActionsDontInheritExecProperties() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'my_target',
          deps = [':my_dep'],
        )
        cc_binary(
          name = 'my_dep',
          srcs = ['dep.cc'],
          exec_properties = {'mem': '16g'},
        )
        """);
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_impl(target, ctx):
          f = ctx.actions.declare_file('f.txt')
          ctx.actions.write(f, 'f')
          return []
        my_aspect = aspect(
          implementation = _aspect_impl,
          attr_aspects = ['deps'],
        )
        def _rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _rule_impl,
          attrs = {
            'deps': attr.label_list(aspects = [my_aspect])
          },
        )
        """);
    scratch.file("test/dep.cc", "int main() {return 0;}");

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%my_aspect"), "//test:my_target");
    assertThat(analysisResult).isNotNull();
    ActionOwner owner =
        Iterables.getOnlyElement(
                Iterables.getOnlyElement(analysisResult.getAspectsMap().values()).getActions())
            .getOwner();
    assertThat(owner.getExecProperties()).isEmpty();
  }

  @Test
  public void testAspectRequiredProviders_defaultNoRequiredProviders() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        prov_b = provider()

        AspectInfo = provider()
        def _my_aspect_impl(target, ctx):
          targets_labels = ["//test:defs.bzl%my_aspect({})".format(target.label)]
          for dep in ctx.rule.attr.deps:
            if AspectInfo in dep:
              targets_labels.extend(dep[AspectInfo].target_labels)
          return [AspectInfo(target_labels = targets_labels)]

        my_aspect = aspect(
          implementation = _my_aspect_impl,
          attr_aspects = ['deps'],
        )

        RuleInfo = provider()
        def _rule_without_providers_impl(ctx):
          s = []
          for dep in ctx.attr.deps:
            if AspectInfo in dep:
              s.extend(dep[AspectInfo].target_labels)
          return RuleInfo(rule_deps = s)

        rule_without_providers = rule(
          implementation = _rule_without_providers_impl,
          attrs = {
            'deps': attr.label_list(aspects = [my_aspect])
          },
        )

        def _rule_with_providers_impl(ctx):
          return [prov_a(), prov_b()]

        rule_with_providers = rule(
          implementation = _rule_with_providers_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a, prov_b]
        )

        rule_with_providers_not_advertised = rule(
          implementation = _rule_with_providers_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = []
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'rule_with_providers', 'rule_without_providers',
                                'rule_with_providers_not_advertised')
        rule_without_providers(
          name = 'main',
          deps = [':target_without_providers', ':target_with_providers',
                  ':target_with_providers_not_advertised'],
        )
        rule_without_providers(
          name = 'target_without_providers',
        )
        rule_with_providers(
          name = 'target_with_providers',
        )
        rule_with_providers(
          name = 'target_with_providers_indeps',
        )
        rule_with_providers_not_advertised(
          name = 'target_with_providers_not_advertised',
          deps = [':target_with_providers_indeps'],
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    // my_aspect does not require any providers so it will be applied to all the dependencies of
    // main target
    List<String> expected = new ArrayList<>();
    expected.add("//test:defs.bzl%my_aspect(@@//test:target_without_providers)");
    expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_providers)");
    expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_providers_not_advertised)");
    expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_providers_indeps)");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:main");
    ConfiguredTarget target = analysisResult.getTargetsToBuild().iterator().next();
    Object ruleDepsUnchecked = getStarlarkProvider(target, "RuleInfo").getValue("rule_deps");
    assertThat(ruleDepsUnchecked).isInstanceOf(StarlarkList.class);
    StarlarkList<?> ruleDeps = (StarlarkList) ruleDepsUnchecked;
    assertThat(Starlark.toIterable(ruleDeps)).containsExactlyElementsIn(expected);
  }

  @Test
  public void testAspectRequiredProviders_flatSetOfRequiredProviders() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        prov_b = provider()

        AspectInfo = provider()
        def _my_aspect_impl(target, ctx):
          targets_labels = ["//test:defs.bzl%my_aspect({})".format(target.label)]
          for dep in ctx.rule.attr.deps:
            if AspectInfo in dep:
              targets_labels.extend(dep[AspectInfo].target_labels)
          return AspectInfo(target_labels = targets_labels)

        my_aspect = aspect(
          implementation = _my_aspect_impl,
          attr_aspects = ['deps'],
          required_providers = [prov_a, prov_b],
        )

        RuleInfo = provider()
        def _rule_without_providers_impl(ctx):
          s = []
          for dep in ctx.attr.deps:
            if AspectInfo in dep:
              s.extend(dep[AspectInfo].target_labels)
          return RuleInfo(rule_deps = s)

        rule_without_providers = rule(
          implementation = _rule_without_providers_impl,
          attrs = {
            'deps': attr.label_list(aspects=[my_aspect])
          },
          provides = []
        )

        def _rule_with_a_impl(ctx):
          return [prov_a()]

        rule_with_a = rule(
          implementation = _rule_with_a_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a]
        )

        def _rule_with_ab_impl(ctx):
          return [prov_a(), prov_b()]

        rule_with_ab = rule(
          implementation = _rule_with_ab_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a, prov_b]
        )

        rule_with_ab_not_advertised = rule(
          implementation = _rule_with_ab_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = []
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'rule_without_providers', 'rule_with_a', 'rule_with_ab',
                                'rule_with_ab_not_advertised')
        rule_without_providers(
          name = 'main',
          deps = [':target_without_providers', ':target_with_a', ':target_with_ab',
                  ':target_with_ab_not_advertised'],
        )
        rule_without_providers(
          name = 'target_without_providers',
        )
        rule_with_a(
          name = 'target_with_a',
          deps = [':target_with_ab_indeps_not_reached']
        )
        rule_with_ab(
          name = 'target_with_ab',
          deps = [':target_with_ab_indeps_reached']
        )
        rule_with_ab(
          name = 'target_with_ab_indeps_not_reached',
        )
        rule_with_ab(
          name = 'target_with_ab_indeps_reached',
        )
        rule_with_ab_not_advertised(
          name = 'target_with_ab_not_advertised',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    // my_aspect will only be applied on target_with_ab and target_with_ab_indeps_reached since
    // their rule (rule_with_ab) is the only rule that advertises the aspect required providers.
    // However, my_aspect cannot be propagated to target_with_ab_indeps_not_reached because it was
    // not applied to its parent (target_with_a)
    List<String> expected = new ArrayList<>();
    expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_ab)");
    expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_ab_indeps_reached)");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:main");
    ConfiguredTarget target = analysisResult.getTargetsToBuild().iterator().next();
    Object ruleDepsUnchecked = getStarlarkProvider(target, "RuleInfo").getValue("rule_deps");
    assertThat(ruleDepsUnchecked).isInstanceOf(StarlarkList.class);
    StarlarkList<?> ruleDeps = (StarlarkList) ruleDepsUnchecked;
    assertThat(Starlark.toIterable(ruleDeps)).containsExactlyElementsIn(expected);
  }

  @Test
  public void testAspectRequiredProviders_listOfRequiredProvidersLists() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        prov_b = provider()
        prov_c = provider()
        AspectInfo = provider()
        def _my_aspect_impl(target, ctx):
          targets_labels = ["//test:defs.bzl%my_aspect({})".format(target.label)]
          for dep in ctx.rule.attr.deps:
            if AspectInfo in dep:
              targets_labels.extend(dep[AspectInfo].target_labels)
          return [AspectInfo(target_labels = targets_labels)]

        my_aspect = aspect(
          implementation = _my_aspect_impl,
          attr_aspects = ['deps'],
          required_providers = [[prov_a, prov_b], [prov_c]],
        )

        RuleInfo = provider()
        def _rule_without_providers_impl(ctx):
          s = []
          for dep in ctx.attr.deps:
            if AspectInfo in dep:
              s.extend(dep[AspectInfo].target_labels)
          return RuleInfo(rule_deps = s)

        rule_without_providers = rule(
          implementation = _rule_without_providers_impl,
          attrs = {
            'deps': attr.label_list(aspects=[my_aspect])
          },
          provides = []
        )

        def _rule_with_a_impl(ctx):
          return [prov_a()]

        rule_with_a = rule(
          implementation = _rule_with_a_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a]
        )

        def _rule_with_c_impl(ctx):
          return [prov_c()]

        rule_with_c = rule(
          implementation = _rule_with_c_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_c]
        )

        def _rule_with_ab_impl(ctx):
          return [prov_a(), prov_b()]

        rule_with_ab = rule(
          implementation = _rule_with_ab_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a, prov_b]
        )

        rule_with_ab_not_advertised = rule(
          implementation = _rule_with_ab_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = []
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'rule_without_providers', 'rule_with_a', 'rule_with_c',
                                'rule_with_ab', 'rule_with_ab_not_advertised')
        rule_without_providers(
          name = 'main',
          deps = [':target_without_providers', ':target_with_a', ':target_with_c',
                  ':target_with_ab', 'target_with_ab_not_advertised'],
        )
        rule_without_providers(
          name = 'target_without_providers',
        )
        rule_with_a(
          name = 'target_with_a',
          deps = [':target_with_c_indeps_not_reached'],
        )
        rule_with_c(
          name = 'target_with_c',
        )
        rule_with_c(
          name = 'target_with_c_indeps_reached',
        )
        rule_with_c(
          name = 'target_with_c_indeps_not_reached',
        )
        rule_with_ab(
          name = 'target_with_ab',
          deps = [':target_with_c_indeps_reached'],
        )
        rule_with_ab_not_advertised(
          name = 'target_with_ab_not_advertised',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    // my_aspect will only be applied on target_with_ab, target_wtih_c and
    // target_with_c_indeps_reached because their rules (rule_with_ab and rule_with_c) are the only
    // rules advertising the aspect required providers
    // However, my_aspect cannot be propagated to target_with_c_indeps_not_reached because it was
    // not applied to its parent (target_with_a)
    List<String> expected = new ArrayList<>();
    expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_ab)");
    expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_c)");
    expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_c_indeps_reached)");
    assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:main");
    ConfiguredTarget target = analysisResult.getTargetsToBuild().iterator().next();
    Object ruleDepsUnchecked = getStarlarkProvider(target, "RuleInfo").getValue("rule_deps");
    assertThat(ruleDepsUnchecked).isInstanceOf(StarlarkList.class);
    StarlarkList<?> ruleDeps = (StarlarkList) ruleDepsUnchecked;
    assertThat(Starlark.toIterable(ruleDeps)).containsExactlyElementsIn(expected);
  }

  @Test
  public void testAspectRequiredByMultipleAspects_inheritsAttrAspects() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        prov_b = provider()
        prov_c = provider()

        def _aspect_c_impl(target, ctx):
          res = ['aspect_c runs on target {}'.format(target.label)]
          return [prov_c(val = res)]
        aspect_c = aspect(
          implementation = _aspect_c_impl,
        )

        def _aspect_b_impl(target, ctx):
          res = []
          res += target[prov_c].val
          res += ['aspect_b runs on target {}'.format(target.label)]
          if ctx.rule.attr.dep_b:
            res += ctx.rule.attr.dep_b[prov_b].val
          return [prov_b(val = res)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep_b'],
          requires = [aspect_c],
        )

        def _aspect_a_impl(target, ctx):
          res = []
          res += target[prov_c].val
          res += ['aspect_a runs on target {}'.format(target.label)]
          if ctx.rule.attr.dep_a:
            res += ctx.rule.attr.dep_a[prov_a].val
          return [prov_a(val = res)]
        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep_a'],
          requires = [aspect_c],
        )

        def _my_rule_impl(ctx):
          pass

        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'dep_a': attr.label(),
            'dep_b': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep_a = ':dep_target_a',
          dep_b = ':dep_target_b',
        )
        my_rule(
          name = 'dep_target_a',
        )
        my_rule(
          name = 'dep_target_b',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%aspect_a", "test/defs.bzl%aspect_b"),
            "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    // aspect_a should run on main_target and dep_target_a and can retrieve aspect_c provider value
    // on both of them
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkProvider.Key aResult =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_a");
    StructImpl aResultProvider = (StructImpl) aspectA.get(aResult);
    assertThat((Sequence<?>) aResultProvider.getValue("val"))
        .containsExactly(
            "aspect_c runs on target @@//test:dep_target_a",
            "aspect_a runs on target @@//test:dep_target_a",
            "aspect_c runs on target @@//test:main_target",
            "aspect_a runs on target @@//test:main_target");

    // aspect_b should run on main_target and dep_target_b and can retrieve aspect_c provider value
    // on both of them
    ConfiguredAspect aspectB = getConfiguredAspect(configuredAspects, "aspect_b");
    assertThat(aspectA).isNotNull();
    StarlarkProvider.Key bResult =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_b");
    StructImpl bResultProvider = (StructImpl) aspectB.get(bResult);
    assertThat((Sequence<?>) bResultProvider.getValue("val"))
        .containsExactly(
            "aspect_c runs on target @@//test:dep_target_b",
            "aspect_b runs on target @@//test:dep_target_b",
            "aspect_c runs on target @@//test:main_target",
            "aspect_b runs on target @@//test:main_target");
  }

  @Test
  public void testAspectRequiredByMultipleAspects_inheritsRequiredProviders() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        aspect_prov_a = provider()
        aspect_prov_b = provider()
        aspect_prov_c = provider()
        rule_prov_a = provider()
        rule_prov_b = provider()
        rule_prov_c = provider()

        def _aspect_c_impl(target, ctx):
          res = ['aspect_c runs on target {}'.format(target.label)]
          return [aspect_prov_c(val = res)]
        aspect_c = aspect(
          implementation = _aspect_c_impl,
          required_providers = [rule_prov_c],
        )

        def _aspect_b_impl(target, ctx):
          res = []
          if aspect_prov_c in target:
            res += target[aspect_prov_c].val
          res += ['aspect_b runs on target {}'.format(target.label)]
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              if aspect_prov_b in dep:
                res += dep[aspect_prov_b].val
          return [aspect_prov_b(val = res)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['deps'],
          required_providers = [[rule_prov_b], [rule_prov_c]],
          requires = [aspect_c],
        )

        def _aspect_a_impl(target, ctx):
          res = []
          if aspect_prov_c in target:
            res += target[aspect_prov_c].val
          res += ['aspect_a runs on target {}'.format(target.label)]
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              if aspect_prov_a in dep:
                res += dep[aspect_prov_a].val
          return [aspect_prov_a(val = res)]
        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['deps'],
          required_providers = [[rule_prov_a], [rule_prov_c]],
          requires = [aspect_c],
        )

        def _my_rule_impl(ctx):
          return [rule_prov_a(), rule_prov_b()]

        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_a, rule_prov_b]
        )

        def _rule_with_prov_a_impl(ctx):
          return [rule_prov_a()]

        rule_with_prov_a = rule(
          implementation = _rule_with_prov_a_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_a]
        )

        def _rule_with_prov_b_impl(ctx):
          return [rule_prov_b()]

        rule_with_prov_b = rule(
          implementation = _rule_with_prov_b_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_b]
        )

        def _rule_with_prov_c_impl(ctx):
          return [rule_prov_c()]
        rule_with_prov_c = rule(
          implementation = _rule_with_prov_c_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_c]
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule', 'rule_with_prov_a',
                                'rule_with_prov_b', 'rule_with_prov_c')
        my_rule(
          name = 'main_target',
          deps = [':dep_target_with_prov_a', ':dep_target_with_prov_b']
        )
        rule_with_prov_a(
          name = 'dep_target_with_prov_a',
          deps = [':dep_target_with_prov_c'],
        )
        rule_with_prov_b(
          name = 'dep_target_with_prov_b',
          deps = [':dep_target_with_prov_c'],
        )
        rule_with_prov_c(
          name = 'dep_target_with_prov_c',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%aspect_a", "test/defs.bzl%aspect_b"),
            "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    // aspect_a runs on main_target, dep_target_with_prov_a and dep_target_with_prov_c and it can
    // only retrieve aspect_c provider value on dep_target_with_prov_c
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkProvider.Key aResult =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "aspect_prov_a");
    StructImpl aResultProvider = (StructImpl) aspectA.get(aResult);
    assertThat((Sequence<?>) aResultProvider.getValue("val"))
        .containsExactly(
            "aspect_c runs on target @@//test:dep_target_with_prov_c",
            "aspect_a runs on target @@//test:dep_target_with_prov_c",
            "aspect_a runs on target @@//test:dep_target_with_prov_a",
            "aspect_a runs on target @@//test:main_target");

    // aspect_b runs on main_target, dep_target_with_prov_b and dep_target_with_prov_c and it can
    // only retrieve aspect_c provider value on dep_target_with_prov_c
    ConfiguredAspect aspectB = getConfiguredAspect(configuredAspects, "aspect_b");
    assertThat(aspectA).isNotNull();
    StarlarkProvider.Key bResult =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "aspect_prov_b");
    StructImpl bResultProvider = (StructImpl) aspectB.get(bResult);
    assertThat((Sequence<?>) bResultProvider.getValue("val"))
        .containsExactly(
            "aspect_c runs on target @@//test:dep_target_with_prov_c",
            "aspect_b runs on target @@//test:dep_target_with_prov_c",
            "aspect_b runs on target @@//test:dep_target_with_prov_b",
            "aspect_b runs on target @@//test:main_target");
  }

  @Test
  public void testAspectRequiredByMultipleAspects_withDifferentParametersValues() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        prov_b = provider()
        prov_c = provider()

        def _aspect_c_impl(target, ctx):
          res = ['aspect_c runs on target {} and param = {}'.format(target.label, ctx.attr.p)]
          return [prov_c(val = res)]
        aspect_c = aspect(
          implementation = _aspect_c_impl,
          attrs = {
            'p': attr.string(values=['rule_1_val', 'rule_2_val']),
          },
        )

        def _aspect_b_impl(target, ctx):
          res = []
          res += target[prov_c].val
          res += ['aspect_b runs on target {}'.format(target.label)]
          if ctx.rule.attr.dep:
            res += ctx.rule.attr.dep[prov_b].val
          return [prov_b(val = res)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          requires = [aspect_c],
        )

        def _aspect_a_impl(target, ctx):
          res = []
          res += target[prov_c].val
          res += ['aspect_a runs on target {}'.format(target.label)]
          if ctx.rule.attr.dep:
            res += ctx.rule.attr.dep[prov_a].val
          return [prov_a(val = res)]
        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          requires = [aspect_c],
        )

        def _rule_1_impl(ctx):
          return ctx.attr.dep[prov_a]

        rule_1 = rule(
          implementation = _rule_1_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_a]),
            'p': attr.string(values = ['rule_1_val', 'rule_2_val'])
          },
        )

        def _rule_2_impl(ctx):
          return ctx.attr.dep[prov_b]

        rule_2 = rule(
          implementation = _rule_2_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_b]),
            'p': attr.string(values = ['rule_1_val', 'rule_2_val'])
          },
        )

        def _rule_3_impl(ctx):
          pass

        rule_3 = rule(
          implementation = _rule_3_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'rule_1', 'rule_2', 'rule_3')
        rule_1(
          name = 'target_1',
          dep = ':dep_1',
          p = 'rule_1_val'
        )
        rule_2(
          name = 'target_2',
          dep = ':dep_2',
          p = 'rule_2_val'
        )
        rule_3(
          name = 'dep_1',
          dep = ':dep_3',
        )
        rule_3(
          name = 'dep_2',
          dep = ':dep_3',
        )
        rule_3(
          name = 'dep_3',
        )
        """);

    AnalysisResult analysisResult = update("//test:target_1", "//test:target_2");

    Iterator<ConfiguredTarget> it = analysisResult.getTargetsToBuild().iterator();
    // aspect_a runs on dep_1 and dep_3 and it can retrieve aspect_c provider value on them
    // aspect_c here should get its parameter value from rule_2
    ConfiguredTarget target1 = it.next();
    StarlarkProvider.Key provAkey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_a");
    StructImpl provA = (StructImpl) target1.get(provAkey);
    assertThat((Sequence<?>) provA.getValue("val"))
        .containsExactly(
            "aspect_c runs on target @@//test:dep_1 and param = rule_1_val",
            "aspect_a runs on target @@//test:dep_1",
            "aspect_c runs on target @@//test:dep_3 and param = rule_1_val",
            "aspect_a runs on target @@//test:dep_3");

    // aspect_b runs on dep_2 and dep_3 and it can retrieve aspect_c provider value on them.
    // aspect_c here should get its parameter value from rule_2
    ConfiguredTarget target2 = it.next();
    StarlarkProvider.Key provBkey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_b");
    StructImpl provB = (StructImpl) target2.get(provBkey);
    assertThat((Sequence<?>) provB.getValue("val"))
        .containsExactly(
            "aspect_c runs on target @@//test:dep_2 and param = rule_2_val",
            "aspect_b runs on target @@//test:dep_2",
            "aspect_c runs on target @@//test:dep_3 and param = rule_2_val",
            "aspect_b runs on target @@//test:dep_3");
  }

  @Test
  public void testAspectRequiresAspect_requireNativeAspect() throws Exception {
    exposeNativeAspectToStarlark();
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        def _impl(target, ctx):
          res = 'aspect_a on target {} '.format(target.label)
          if hasattr(target, 'native_aspect_prov'):
            res += 'can see native aspect provider'
          else:
            res += 'cannot see native aspect provider'
          complete_res = [res]
          if hasattr(ctx.rule.attr, 'dep'):
            complete_res += ctx.rule.attr.dep[prov_a].val
          return [prov_a(val = complete_res)]
        aspect_a = aspect(implementation = _impl,
                          requires = [starlark_native_aspect],
                          attr_aspects = ['dep'],)

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(implementation = _my_rule_impl,
                       attrs = {'dep': attr.label()})
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_1',
        )
        my_rule(
          name = 'dep_1',
          dep = ':dep_2',
        )
        honest(
          name = 'dep_2',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%aspect_a"), "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    // aspect_a runs on main_target, dep_1 and dep_2 but it can only see the required native aspect
    // run on dep_2 because its rule satisfies its required provider.
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkProvider.Key aResult =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_a");
    StructImpl aResultProvider = (StructImpl) aspectA.get(aResult);
    assertThat((Sequence<?>) aResultProvider.getValue("val"))
        .containsExactly(
            "aspect_a on target @@//test:main_target cannot see native aspect provider",
            "aspect_a on target @@//test:dep_1 cannot see native aspect provider",
            "aspect_a on target @@//test:dep_2 can see native aspect provider");
  }

  @Test
  public void testAspectRequiresAspect_aspectsParameters() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        RequiredAspectProv = provider()
        BaseAspectProv = provider()

        def _required_aspect_impl(target, ctx):
          p1_val = 'In required_aspect, p1 = {} on target {}'.format(ctx.attr.p1, target.label)
          p2_val = 'invalid value'
          if not hasattr(ctx.attr, 'p2'):
            p2_val = 'In required_aspect, p2 not found on target {}'.format(target.label)
          return [RequiredAspectProv(p1_val = p1_val, p2_val = p2_val)]
        required_aspect = aspect(
          implementation = _required_aspect_impl,
          attr_aspects = ['dep'],
          attrs = {'p1' : attr.string(values = ['p1_v1', 'p1_v2'])}
        )

        def _base_aspect_impl(target, ctx):
          p2_val = 'In base_aspect, p2 = {} on target {}'.format(ctx.attr.p2, target.label)
          p1_val = 'invalid value'
          if not hasattr(ctx.attr, 'p1'):
            p1_val = 'In base_aspect, p1 not found on target {}'.format(target.label)
          return [BaseAspectProv(p1_val = p1_val, p2_val = p2_val)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          attrs = {'p2' : attr.string(values = ['p2_v1', 'p2_v2'])},
          requires = [required_aspect],
        )

        def _main_rule_impl(ctx):
          return [ctx.attr.dep[RequiredAspectProv], ctx.attr.dep[BaseAspectProv]]
        def _dep_rule_impl(ctx):
          pass

        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects=[base_aspect]),
            'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
            'p2' : attr.string(values = ['p2_v1', 'p2_v2'])
          },
        )

        dep_rule = rule(
          implementation = _dep_rule_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
          p1 = 'p1_v1',
          p2 = 'p2_v1'
        )
        dep_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    // Both base_aspect and required_aspect can get their parameters values from the base rule
    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkProvider.Key requiredAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "RequiredAspectProv");
    StructImpl requiredAspectProvider = (StructImpl) configuredTarget.get(requiredAspectProv);
    assertThat(requiredAspectProvider.getValue("p1_val"))
        .isEqualTo("In required_aspect, p1 = p1_v1 on target @@//test:dep_target");
    assertThat(requiredAspectProvider.getValue("p2_val"))
        .isEqualTo("In required_aspect, p2 not found on target @@//test:dep_target");

    StarlarkProvider.Key baseAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv");
    StructImpl baseAspectProvider = (StructImpl) configuredTarget.get(baseAspectProv);
    assertThat(baseAspectProvider.getValue("p1_val"))
        .isEqualTo("In base_aspect, p1 not found on target @@//test:dep_target");
    assertThat(baseAspectProvider.getValue("p2_val"))
        .isEqualTo("In base_aspect, p2 = p2_v1 on target @@//test:dep_target");
  }

  @Test
  public void testAspectRequiresAspect_ruleAttributes() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        RequiredAspectProv = provider()
        BaseAspectProv = provider()

        def _required_aspect_impl(target, ctx):
          p_val = 'In required_aspect, p = {} on target {}'.format(ctx.rule.attr.p, target.label)
          return [RequiredAspectProv(p_val = p_val)]
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        def _base_aspect_impl(target, ctx):
          p_val = 'In base_aspect, p = {} on target {}'.format(ctx.rule.attr.p, target.label)
          return [BaseAspectProv(p_val = p_val)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect],
        )

        def _main_rule_impl(ctx):
          return [ctx.attr.dep[RequiredAspectProv], ctx.attr.dep[BaseAspectProv]]
        def _dep_rule_impl(ctx):
          pass

        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects=[base_aspect]),
          },
        )

        dep_rule = rule(
          implementation = _dep_rule_impl,
          attrs = {
            'p' : attr.string(values = ['p_v1', 'p_v2']),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
          p = 'p_v2',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    // Both base_aspect and required_aspect can see the attributes of the rule they run on
    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkProvider.Key requiredAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "RequiredAspectProv");
    StructImpl requiredAspectProvider = (StructImpl) configuredTarget.get(requiredAspectProv);
    assertThat(requiredAspectProvider.getValue("p_val"))
        .isEqualTo("In required_aspect, p = p_v2 on target @@//test:dep_target");

    StarlarkProvider.Key baseAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv");
    StructImpl baseAspectProvider = (StructImpl) configuredTarget.get(baseAspectProv);
    assertThat(baseAspectProvider.getValue("p_val"))
        .isEqualTo("In base_aspect, p = p_v2 on target @@//test:dep_target");
  }

  @Test
  public void testAspectRequiresAspect_inheritPropagationAttributes() throws Exception {
    // base_aspect propagates over base_dep attribute and requires first_required_aspect which
    // propagates over first_dep attribute and requires second_required aspect which propagates
    // over second_dep attribute
    scratch.file(
        "test/defs.bzl",
        """
        BaseAspectProv = provider()
        FirstRequiredAspectProv = provider()
        SecondRequiredAspectProv = provider()

        def _second_required_aspect_impl(target, ctx):
          result = []
          if getattr(ctx.rule.attr, 'second_dep'):
            result += getattr(ctx.rule.attr, 'second_dep')[SecondRequiredAspectProv].result
          result += ['second_required_aspect run on target {}'.format(target.label)]
          return [SecondRequiredAspectProv(result = result)]
        second_required_aspect = aspect(
          implementation = _second_required_aspect_impl,
          attr_aspects = ['second_dep'],
        )

        def _first_required_aspect_impl(target, ctx):
          result = []
          result += target[SecondRequiredAspectProv].result
          if getattr(ctx.rule.attr, 'first_dep'):
            result += getattr(ctx.rule.attr, 'first_dep')[FirstRequiredAspectProv].result
          result += ['first_required_aspect run on target {}'.format(target.label)]
          return [FirstRequiredAspectProv(result = result)]
        first_required_aspect = aspect(
          implementation = _first_required_aspect_impl,
          attr_aspects = ['first_dep'],
          requires = [second_required_aspect],
        )

        def _base_aspect_impl(target, ctx):
          result = []
          result += target[FirstRequiredAspectProv].result
          if getattr(ctx.rule.attr, 'base_dep'):
            result += getattr(ctx.rule.attr, 'base_dep')[BaseAspectProv].result
          result += ['base_aspect run on target {}'.format(target.label)]
          return [BaseAspectProv(result = result)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['base_dep'],
          requires = [first_required_aspect],
        )

        def _main_rule_impl(ctx):
          return [ctx.attr.dep[BaseAspectProv]]
        def _dep_rule_impl(ctx):
          pass

        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects=[base_aspect]),
          },
        )

        dep_rule = rule(
          implementation = _dep_rule_impl,
          attrs = {
            'base_dep': attr.label(),
            'first_dep': attr.label(),
            'second_dep': attr.label()
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
          base_dep = ':base_dep_target',
          first_dep = ':first_dep_target',
          second_dep = ':second_dep_target',
        )
        dep_rule(
          name = 'base_dep_target',
        )
        dep_rule(
          name = 'first_dep_target',
        )
        dep_rule(
          name = 'second_dep_target',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    // base_aspect should propagate only along its attr_aspects: 'base_dep'
    // first_required_aspect should propagate along 'base_dep' and 'first_dep'
    // second_required_aspect should propagate along 'base_dep', 'first_dep' and `second_dep`
    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkProvider.Key baseAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv");
    StructImpl baseAspectProvider = (StructImpl) configuredTarget.get(baseAspectProv);
    assertThat((Sequence<?>) baseAspectProvider.getValue("result"))
        .containsExactly(
            "second_required_aspect run on target @@//test:second_dep_target",
            "second_required_aspect run on target @@//test:dep_target",
            "second_required_aspect run on target @@//test:first_dep_target",
            "first_required_aspect run on target @@//test:first_dep_target",
            "first_required_aspect run on target @@//test:dep_target",
            "second_required_aspect run on target @@//test:base_dep_target",
            "first_required_aspect run on target @@//test:base_dep_target",
            "base_aspect run on target @@//test:base_dep_target",
            "base_aspect run on target @@//test:dep_target");
  }

  @Test
  public void testAspectRequiresAspect_inheritRequiredProviders() throws Exception {
    // aspect_a requires provider Prov_A and requires aspect_b which requires
    // provider Prov_B and requires aspect_c which requires provider Prov_C
    scratch.file(
        "test/defs.bzl",
        "Prov_A = provider()",
        "Prov_B = provider()",
        "Prov_C = provider()",
        "",
        "CollectorProv = provider()",
        "",
        "def _aspect_c_impl(target, ctx):",
        "  collector_result = ['aspect_c run on target {} and value of Prov_C ="
            + " {}'.format(target.label, target[Prov_C].val)]",
        "  return [CollectorProv(result = collector_result)]",
        "aspect_c = aspect(",
        "  implementation = _aspect_c_impl,",
        "  required_providers = [Prov_C],",
        "  attr_aspects = ['dep'],",
        ")",
        "",
        "def _aspect_b_impl(target, ctx):",
        "  collector_result = []",
        "  collector_result += ctx.rule.attr.dep[CollectorProv].result",
        "  collector_result += ['aspect_b run on target {} and value of Prov_B ="
            + " {}'.format(target.label, target[Prov_B].val)]",
        "  return [ CollectorProv(result = collector_result)]",
        "aspect_b = aspect(",
        "  implementation = _aspect_b_impl,",
        "  required_providers = [Prov_B],",
        "  requires = [aspect_c],",
        "  attr_aspects = ['dep'],",
        ")",
        "",
        "def _aspect_a_impl(target, ctx):",
        "  collector_result = []",
        "  collector_result += ctx.rule.attr.dep[CollectorProv].result",
        "  collector_result += ['aspect_a run on target {} and value of Prov_A ="
            + " {}'.format(target.label, target[Prov_A].val)]",
        "  return [CollectorProv(result = collector_result)]",
        "aspect_a = aspect(",
        "  implementation = _aspect_a_impl,",
        "  attr_aspects = ['dep'],",
        "  required_providers = [Prov_A],",
        "  requires = [aspect_b],",
        ")",
        "",
        "def _main_rule_impl(ctx):",
        "  return [ctx.attr.dep[CollectorProv]]",
        "main_rule = rule(",
        "  implementation = _main_rule_impl,",
        "  attrs = {",
        "    'dep': attr.label(aspects = [aspect_a]),",
        "  },",
        ")",
        "",
        "def _rule_with_prov_a_impl(ctx):",
        "  return [Prov_A(val='val_a')]",
        "rule_with_prov_a = rule(",
        "  implementation = _rule_with_prov_a_impl,",
        "  attrs = {",
        "    'dep': attr.label(),",
        "  },",
        "  provides = [Prov_A]",
        ")",
        "",
        "def _rule_with_prov_b_impl(ctx):",
        "  return [Prov_B(val = 'val_b')]",
        "rule_with_prov_b = rule(",
        "  implementation = _rule_with_prov_b_impl,",
        "  attrs = {",
        "    'dep': attr.label(),",
        "  },",
        "  provides = [Prov_B]",
        ")",
        "",
        "def _rule_with_prov_c_impl(ctx):",
        "  return [Prov_C(val = 'val_c')]",
        "rule_with_prov_c = rule(",
        "  implementation = _rule_with_prov_c_impl,",
        "  provides = [Prov_C]",
        ")");
    scratch.file(
        "test/BUILD",
        "load('//test:defs.bzl', 'main_rule', 'rule_with_prov_a', 'rule_with_prov_b',"
            + " 'rule_with_prov_c')",
        "main_rule(",
        "  name = 'main',",
        "  dep = ':target_with_prov_a',",
        ")",
        "rule_with_prov_a(",
        "  name = 'target_with_prov_a',",
        "  dep = ':target_with_prov_b'",
        ")",
        "rule_with_prov_b(",
        "  name = 'target_with_prov_b',",
        "  dep = ':target_with_prov_c'",
        ")",
        "rule_with_prov_c(",
        "  name = 'target_with_prov_c'",
        ")");

    AnalysisResult analysisResult = update("//test:main");

    // aspect_a should only run on target_with_prov_a, aspect_b should only run on
    // target_with_prov_b and aspect_c should only run on target_with_prov_c.
    // aspect_c will reach target target_with_prov_c because it inherits the required_providers of
    // aspect_b otherwise it would have stopped propagating after target_with_prov_b.
    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkProvider.Key collectorProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "CollectorProv");
    StructImpl collectorProvider = (StructImpl) configuredTarget.get(collectorProv);
    assertThat((Sequence<?>) collectorProvider.getValue("result"))
        .containsExactly(
            "aspect_c run on target @@//test:target_with_prov_c and value of Prov_C = val_c",
            "aspect_b run on target @@//test:target_with_prov_b and value of Prov_B = val_b",
            "aspect_a run on target @@//test:target_with_prov_a and value of Prov_A = val_a")
        .inOrder();
  }

  @Test
  public void testAspectRequiresAspect_inspectRequiredAspectActions() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _required_aspect_impl(target, ctx):
          f = ctx.actions.declare_file('dummy.txt')
          ctx.actions.run_shell(outputs = [f], command='echo xxx > $(location f)',
                                mnemonic='RequiredAspectAction')
          return []
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        AspectInfo = provider()
        def _base_aspect_impl(target, ctx):
          required_aspect_action = None
          for action in target.actions:
            if action.mnemonic == 'RequiredAspectAction':
              required_aspect_action = action
          if required_aspect_action:
            return AspectInfo(result = 'base_aspect can see required_aspect action')
          else:
            return AspectInfo(result = 'base_aspect cannot see required_aspect action')
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect]
        )

        RuleInfo = provider()
        def _main_rule_impl(ctx):
          return RuleInfo(result = ctx.attr.dep[AspectInfo].result)
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [base_aspect]),
          },
        )

        def _dep_rule_impl(ctx):
          pass
        dep_rule = rule(
          implementation = _dep_rule_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    String result = (String) getStarlarkProvider(configuredTarget, "RuleInfo").getValue("result");
    assertThat(result).isEqualTo("base_aspect can see required_aspect action");
  }

  @Test
  public void testAspectRequiresAspect_inspectRequiredAspectGeneratedFiles() throws Exception {
    scratch.file(
        "test/defs.bzl",
        "def _required_aspect_impl(target, ctx):",
        "  file = ctx.actions.declare_file('required_aspect_file')",
        "  ctx.actions.write(file, 'data')",
        "  return [OutputGroupInfo(out = [file])]",
        "required_aspect = aspect(",
        "  implementation = _required_aspect_impl,",
        ")",
        "",
        "AspectInfo = provider()",
        "def _base_aspect_impl(target, ctx):",
        "  files = ['base_aspect can see file ' + f.path.split('/')[-1] for f in"
            + " target[OutputGroupInfo].out.to_list()]",
        "  return AspectInfo(my_files = files)",
        "base_aspect = aspect(",
        "  implementation = _base_aspect_impl,",
        "  attr_aspects = ['dep'],",
        "  requires = [required_aspect]",
        ")",
        "",
        "RuleInfo = provider()",
        "def _main_rule_impl(ctx):",
        "  return RuleInfo(my_files = ctx.attr.dep[AspectInfo].my_files)",
        "main_rule = rule(",
        "  implementation = _main_rule_impl,",
        "  attrs = {",
        "    'dep': attr.label(aspects = [base_aspect]),",
        "  },",
        ")",
        "",
        "def _dep_rule_impl(ctx):",
        "  pass",
        "dep_rule = rule(",
        "  implementation = _dep_rule_impl,",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkList<?> files =
        (StarlarkList) getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_files");
    assertThat(Starlark.toIterable(files))
        .containsExactly("base_aspect can see file required_aspect_file");
  }

  @Test
  public void testAspectRequiresAspect_withRequiredAspectProvidersSatisfied() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        prov_b = provider()
        prov_b_forwarded = provider()
        AspectAInfo = provider()
        AspectBInfo = provider()
        RuleInfo = provider()
        def _aspect_b_impl(target, ctx):
          result = 'aspect_b on target {} '.format(target.label)
          if prov_b in target:
            result += 'found prov_b = {}'.format(target[prov_b].val)
            return [AspectBInfo(aspect_b_result = result),
                    prov_b_forwarded(val = target[prov_b].val)]
          else:
            result += 'cannot find prov_b'
            return [AspectBInfo(aspect_b_result = result)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_aspect_providers = [prov_b]
        )

        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {} '.format(target.label)
          if prov_a in target:
            result += 'found prov_a = {}'.format(target[prov_a].val)
          else:
            result += 'cannot find prov_a'
          if prov_b_forwarded in target:
            result += ' and found prov_b = {}'.format(target[prov_b_forwarded].val)
          else:
            result += ' but cannot find prov_b'
          return AspectAInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          required_aspect_providers = [prov_a],
          attr_aspects = ['dep'],
          requires = [aspect_b]
        )

        def _aspect_with_prov_a_impl(target, ctx):
          return [prov_a(val = 'a1')]
        aspect_with_prov_a = aspect(
          implementation = _aspect_with_prov_a_impl,
          provides = [prov_a],
          attr_aspects = ['dep'],
        )

        def _aspect_with_prov_b_impl(target, ctx):
          return [prov_b(val = 'b1')]
        aspect_with_prov_b = aspect(
          implementation = _aspect_with_prov_b_impl,
          provides = [prov_b],
          attr_aspects = ['dep'],
        )

        def _main_rule_impl(ctx):
          return RuleInfo(aspect_a_result = ctx.attr.dep[AspectAInfo].aspect_a_result,
                        aspect_b_result = ctx.attr.dep[AspectBInfo].aspect_b_result)
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_with_prov_a, aspect_with_prov_b, aspect_a]),
          },
        )

        def _dep_rule_impl(ctx):
          pass
        dep_rule = rule(
          implementation = _dep_rule_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkInfo targetInfo = getStarlarkProvider(configuredTarget, "RuleInfo");
    String aspectAResult = targetInfo.getValue("aspect_a_result", String.class);
    assertThat(aspectAResult)
        .isEqualTo(
            "aspect_a on target @@//test:dep_target found prov_a = a1 and found prov_b = b1");

    String aspectBResult = targetInfo.getValue("aspect_b_result", String.class);
    assertThat(aspectBResult).isEqualTo("aspect_b on target @@//test:dep_target found prov_b = b1");
  }

  @Test
  public void testAspectRequiresAspect_withRequiredAspectProvidersNotFound() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        prov_b = provider()
        AspectAInfo = provider()
        AspectBInfo = provider()
        def _aspect_b_impl(target, ctx):
          result = 'aspect_b on target {} '.format(target.label)
          if prov_b in target:
            result += 'found prov_b = {}'.format(target[prov_b].val)
          else:
            result += 'cannot find prov_b'
          return AspectBInfo(aspect_b_result = result)
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_aspect_providers = [prov_b]
        )

        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {} '.format(target.label)
          if prov_a in target:
            result += 'found prov_a = {}'.format(target[prov_a].val)
          else:
            result += 'cannot find prov_a'
          return AspectAInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          required_aspect_providers = [prov_a],
          attr_aspects = ['dep'],
          requires = [aspect_b]
        )

        def _aspect_with_prov_a_impl(target, ctx):
          return [prov_a(val = 'a1')]
        aspect_with_prov_a = aspect(
          implementation = _aspect_with_prov_a_impl,
          provides = [prov_a],
          attr_aspects = ['dep'],
        )

        RuleInfo = provider()
        def _main_rule_impl(ctx):
          return RuleInfo(aspect_a_result = ctx.attr.dep[AspectAInfo].aspect_a_result,
                        aspect_b_result = ctx.attr.dep[AspectBInfo].aspect_b_result)
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_with_prov_a, aspect_a]),
          },
        )

        def _dep_rule_impl(ctx):
          pass
        dep_rule = rule(
          implementation = _dep_rule_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkInfo ruleInfo = getStarlarkProvider(configuredTarget, "RuleInfo");
    String aspectAResult = ruleInfo.getValue("aspect_a_result", String.class);
    assertThat(aspectAResult).isEqualTo("aspect_a on target @@//test:dep_target found prov_a = a1");

    String aspectBResult = ruleInfo.getValue("aspect_b_result", String.class);
    assertThat(aspectBResult)
        .isEqualTo("aspect_b on target @@//test:dep_target cannot find prov_b");
  }

  /**
   * --aspects = a3, a2, a1: aspect a1 requires provider a1p, aspect a2 requires provider a2p and
   * provides a1p and aspect a3 provides a2p. The three aspects will propagate together but aspect
   * a1 will only see a1p and aspect a2 will only see a2p.
   */
  @Test
  public void testTopLevelAspectOnAspect_stackOfAspects() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a1p = provider()
        a2p = provider()
        a1_result = provider()
        a2_result = provider()
        a3_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          if a2p in target:
            result += ' and sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' and cannot see a2p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          result = 'aspect a2 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          if a2p in target:
            result += ' and sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' and cannot see a2p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a2_result].value + [result]
          else:
            complete_result = [result]
          return [a2_result(value = complete_result), a1p(value = 'a1p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
          required_aspect_providers = [a2p],
        )

        def _a3_impl(target, ctx):
          result = 'aspect a3 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          if a2p in target:
            result += ' and sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' and cannot see a2p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a3_result].value + [result]
          else:
            complete_result = [result]
          return [a3_result(value = complete_result), a2p(value = 'a2p_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['dep'],
          provides = [a2p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%a3", "test/defs.bzl%a2", "test/defs.bzl%a1"),
            "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a3 = getConfiguredAspect(configuredAspects, "a3");
    assertThat(a3).isNotNull();
    StarlarkProvider.Key a3Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a3_result");
    StructImpl a3ResultProvider = (StructImpl) a3.get(a3Result);
    assertThat((Sequence<?>) a3ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a3 on target @@//test:dep_target cannot see a1p and cannot see a2p",
            "aspect a3 on target @@//test:main cannot see a1p and cannot see a2p");

    ConfiguredAspect a2 = getConfiguredAspect(configuredAspects, "a2");
    assertThat(a2).isNotNull();
    StarlarkProvider.Key a2Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a2_result");
    StructImpl a2ResultProvider = (StructImpl) a2.get(a2Result);
    assertThat((Sequence<?>) a2ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a2 on target @@//test:dep_target cannot see a1p and sees a2p = a2p_val",
            "aspect a2 on target @@//test:main cannot see a1p and sees a2p = a2p_val");

    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:dep_target sees a1p = a1p_val and cannot see a2p",
            "aspect a1 on target @@//test:main sees a1p = a1p_val and cannot see a2p");
  }

  /**
   * --aspects = a3, a2, a1: aspect a1 requires provider a1p, aspect a2 and aspect a3 provides a1p.
   * This should fail because provider a1p is provided twice.
   */
  @Test
  public void testTopLevelAspectOnAspect_requiredProviderProvidedTwiceFailed() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a1p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_a2_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _a3_impl(target, ctx):
          return [a1p(value = 'a1p_a3_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // The call to `update` does not throw an exception when "--keep_going" is passed in the
    // WithKeepGoing test suite. Otherwise, it throws ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult result =
          update(
              ImmutableList.of("test/defs.bzl%a3", "test/defs.bzl%a2", "test/defs.bzl%a1"),
              "//test:main");
      assertThat(result.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () ->
              update(
                  ImmutableList.of("test/defs.bzl%a3", "test/defs.bzl%a2", "test/defs.bzl%a1"),
                  "//test:main"));
    }
    assertContainsEvent("ERROR /workspace/test/BUILD:2:12: Provider a1p provided twice");
  }

  /**
   * --aspects = a3, a1, a2: aspect a1 requires provider a1p, aspect a2 and aspect a3 provide a1p.
   * a1 should see the value provided by a3 because a3 is listed before a1.
   */
  @Test
  public void testTopLevelAspectOnAspect_requiredProviderProvidedTwicePassed() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a1p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_a2_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _a3_impl(target, ctx):
          return [a1p(value = 'a1p_a3_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%a3", "test/defs.bzl%a1", "test/defs.bzl%a2"),
            "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:dep_target sees a1p = a1p_a3_val",
            "aspect a1 on target @@//test:main sees a1p = a1p_a3_val");
  }

  @Test
  public void testTopLevelAspectOnAspect_requiredProviderNotProvided() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a1p = provider()
        a2p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a2p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%a2", "test/defs.bzl%a1"), "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:dep_target cannot see a1p",
            "aspect a1 on target @@//test:main cannot see a1p");
  }

  /**
   * --aspects = a1, a2: aspect a1 requires provider a1p, aspect a2 provides a1p but it was listed
   * after a1 so aspect a1 cannot see a1p value.
   */
  @Test
  public void testTopLevelAspectOnAspect_requiredProviderProvidedAfterTheAspect() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a1p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%a1", "test/defs.bzl%a2"), "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:dep_target cannot see a1p",
            "aspect a1 on target @@//test:main cannot see a1p");
  }

  /**
   * --aspects = a2, a1: aspect a1 requires provider a1p, aspect a2 provides a1p. But aspect a2
   * propagates along different attr_aspects from a1 so a1 cannot get a1p on all dependency targets.
   */
  @Test
  public void testTopLevelAspectOnAspect_differentAttrAspects() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a1p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result += ctx.rule.attr.dep[a1_result].value
          if ctx.rule.attr.extra_dep:
            complete_result += ctx.rule.attr.extra_dep[a1_result].value
          complete_result += [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep', 'extra_dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
            'extra_dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
          extra_dep = ':extra_dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        simple_rule(
          name = 'extra_dep_target',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%a2", "test/defs.bzl%a1"), "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:dep_target sees a1p = a1p_val",
            "aspect a1 on target @@//test:extra_dep_target cannot see a1p",
            "aspect a1 on target @@//test:main sees a1p = a1p_val");
  }

  /**
   * --aspects = a2, a1: aspect a1 requires provider a1p, aspect a2 provides a1p. But aspect a2
   * propagates along different required_providers from a1 so a1 cannot get a1p on all dependency
   * targets.
   */
  @Test
  public void testTopLevelAspectOnAspect_differentRequiredRuleProviders() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a1p = provider()
        a1_result = provider()
        rule_prov_a = provider()
        rule_prov_b = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if hasattr(ctx.rule.attr, 'deps'):
            for dep in ctx.rule.attr.deps:
              complete_result += dep[a1_result].value
          complete_result += [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [a1p],
          required_providers = [[rule_prov_a], [rule_prov_b]],
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a1p],
          required_providers = [rule_prov_a],
        )

        def _main_rule_impl(ctx):
          return [rule_prov_a(), rule_prov_b()]
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_a, rule_prov_b],
        )

        def _rule_with_prov_a_impl(ctx):
          return [rule_prov_a()]
        rule_with_prov_a = rule(
          implementation = _rule_with_prov_a_impl,
          provides = [rule_prov_a]
        )

        def _rule_with_prov_b_impl(ctx):
          return [rule_prov_b()]
        rule_with_prov_b = rule(
          implementation = _rule_with_prov_b_impl,
          provides = [rule_prov_b]
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule', 'rule_with_prov_a', 'rule_with_prov_b')
        main_rule(
          name = 'main',
          deps = [':target_with_prov_a', ':target_with_prov_b'],
        )
        rule_with_prov_a(
          name = 'target_with_prov_a',
        )
        rule_with_prov_b(
          name = 'target_with_prov_b',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%a2", "test/defs.bzl%a1"), "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:target_with_prov_a sees a1p = a1p_val",
            "aspect a1 on target @@//test:target_with_prov_b cannot see a1p",
            "aspect a1 on target @@//test:main sees a1p = a1p_val");
  }

  /**
   * --aspects = a3, a2, a1: both aspects a1 and a2 require provider a3p, aspect a3 provides a3p. a1
   * and a2 should be able to read a3p.
   */
  @Test
  public void testTopLevelAspectOnAspect_providerRequiredByMultipleAspects() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a3p = provider()
        a1_result = provider()
        a2_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a3p in target:
            result += ' sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' cannot see a3p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a3p]
        )

        def _a2_impl(target, ctx):
          result = 'aspect a2 on target {}'.format(target.label)
          if a3p in target:
            result += ' sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' cannot see a3p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a2_result].value + [result]
          else:
            complete_result = [result]
          return [a2_result(value = complete_result)]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a3p]
        )

        def _a3_impl(target, ctx):
          return [a3p(value = 'a3p_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['dep'],
          provides = [a3p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%a3", "test/defs.bzl%a2", "test/defs.bzl%a1"),
            "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a2 = getConfiguredAspect(configuredAspects, "a2");
    assertThat(a2).isNotNull();
    StarlarkProvider.Key a2Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a2_result");
    StructImpl a2ResultProvider = (StructImpl) a2.get(a2Result);
    assertThat((Sequence<?>) a2ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a2 on target @@//test:dep_target sees a3p = a3p_val",
            "aspect a2 on target @@//test:main sees a3p = a3p_val");

    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:dep_target sees a3p = a3p_val",
            "aspect a1 on target @@//test:main sees a3p = a3p_val");
  }

  /**
   * --aspects = a1, a2, a3: aspect a3 requires a1p and a2p, a1 provides a1p and a2 provides a2p.
   *
   * <p>top level target (main) has two dependencies t1 and t2. Aspects a1 and a3 can propagate to
   * t1 and aspects a2 and a3 can propagate to t2. Both t1 and t2 have t0 as dependency, aspect a3
   * will run twice on t0 once with aspects path (a1, a3) and the other with (a2, a3).
   */
  @Test
  public void testTopLevelAspectOnAspect_diamondCase() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a1p = provider()
        a2p = provider()
        a3_result = provider()

        r1p = provider()
        r2p = provider()

        def _a1_impl(target, ctx):
          return [a1p(value = 'a1p_val')]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_providers = [r1p],
          provides = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          required_providers = [r2p],
          provides = [a2p]
        )

        def _a3_impl(target, ctx):
          result = 'aspect a3 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          if a2p in target:
            result += ' and sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' and cannot see a2p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a3_result].value)
          complete_result.append(result)
          return [a3_result(value = complete_result)]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[a1p], [a2p]],
        )

        def _r0_impl(ctx):
          return [r1p(), r2p()]
        r0 = rule(
          implementation = _r0_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [r1p, r2p]
        )
        def _r1_impl(ctx):
          return [r1p()]
        r1 = rule(
          implementation = _r1_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [r1p]
        )
        def _r2_impl(ctx):
          return [r2p()]
        r2 = rule(
          implementation = _r2_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [r2p]
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'r0', 'r1', 'r2')
        r0(
          name = 'main',
          deps = [':t1', ':t2'],
        )
        r1(
          name = 't1',
          deps = [':t0'],
        )
        r2(
          name = 't2',
          deps = [':t0'],
        )
        r0(
          name = 't0',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%a1", "test/defs.bzl%a2", "test/defs.bzl%a3"),
            "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a3 = getConfiguredAspect(configuredAspects, "a3");
    assertThat(a3).isNotNull();
    StarlarkProvider.Key a3Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a3_result");
    StructImpl a3ResultProvider = (StructImpl) a3.get(a3Result);
    assertThat((Sequence<?>) a3ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a3 on target @@//test:t0 sees a1p = a1p_val and cannot see a2p",
            "aspect a3 on target @@//test:t0 cannot see a1p and sees a2p = a2p_val",
            "aspect a3 on target @@//test:t1 sees a1p = a1p_val and cannot see a2p",
            "aspect a3 on target @@//test:t2 cannot see a1p and sees a2p = a2p_val",
            "aspect a3 on target @@//test:main sees a1p = a1p_val and sees a2p = a2p_val");
  }

  @Test
  public void testTopLevelAspectOnAspect_duplicateAspectsNotAllowed() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a2p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [a2p]
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          deps = [':dep_target'],
        )
        simple_rule(
          name = 'dep_target',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // The call to `update` does not throw an exception when "--keep_going" is passed in the
    // WithKeepGoing test suite. Otherwise, it throws ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult result =
          update(
              ImmutableList.of("test/defs.bzl%a1", "test/defs.bzl%a2", "test/defs.bzl%a1"),
              "//test:main");
      assertThat(result.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () ->
              update(
                  ImmutableList.of("test/defs.bzl%a1", "test/defs.bzl%a2", "test/defs.bzl%a1"),
                  "//test:main"));
    }
    assertContainsEvent("aspect //test:defs.bzl%a1 added more than once");
  }

  /**
   * --aspects = a1 requires provider a2p provided by aspect a2. a1 is applied on top level target
   * `main` whose rule propagates aspect a2 to its `deps`. So a1 on `main` cannot see a2p but it can
   * see a2p on `main` deps.
   */
  @Test
  public void testTopLevelAspectOnAspect_requiredAspectProviderOnlyAvailableOnDep()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a2p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [a2p]
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(aspects=[a2]),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          deps = [':dep_target'],
        )
        simple_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult = update(ImmutableList.of("test/defs.bzl%a1"), "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:dep_target sees a2p = a2p_val",
            "aspect a1 on target @@//test:main cannot see a2p");
  }

  @Test
  public void testTopLevelAspectOnAspect_multipleTopLevelTargets() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a2p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [a2p],
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 't1',
        )
        simple_rule(
          name = 't2',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%a2", "test/defs.bzl%a1"), "//test:t2", "//test:t1");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a1Ont1 = getConfiguredAspect(configuredAspects, "a1", "t1");
    assertThat(a1Ont1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1Ont1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly("aspect a1 on target @@//test:t1 sees a2p = a2p_val");

    ConfiguredAspect a1Ont2 = getConfiguredAspect(configuredAspects, "a1", "t2");
    assertThat(a1Ont2).isNotNull();
    a1ResultProvider = (StructImpl) a1Ont2.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly("aspect a1 on target @@//test:t2 sees a2p = a2p_val");
  }

  @Test
  public void testTopLevelAspectOnAspect_multipleRequiredProviders() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a2p = provider()
        a3p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          if a3p in target:
            result += ' and sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' and cannot see a3p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[a2p], [a3p]],
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p]
        )

        def _a3_impl(target, ctx):
          return [a3p(value = 'a3p_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['deps'],
          provides = [a3p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          deps = [':dep_target'],
        )
        simple_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%a3", "test/defs.bzl%a2", "test/defs.bzl%a1"),
            "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:dep_target sees a2p = a2p_val and sees a3p = a3p_val",
            "aspect a1 on target @@//test:main sees a2p = a2p_val and sees a3p = a3p_val");
  }

  @Test
  public void testTopLevelAspectOnAspect_multipleRequiredProviders2() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a2p = provider()
        a3p = provider()
        a1_result = provider()
        a2_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          if a3p in target:
            result += ' and sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' and cannot see a3p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[a2p], [a3p]],
        )

        def _a2_impl(target, ctx):
          result = 'aspect a2 on target {}'.format(target.label)
          if a3p in target:
            result += ' sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' cannot see a3p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a2_result].value)
          complete_result.append(result)
          return [a2_result(value = complete_result), a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p],
          required_aspect_providers = [a3p]
        )

        def _a3_impl(target, ctx):
          return [a3p(value = 'a3p_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['deps'],
          provides = [a3p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          deps = [':dep_target'],
        )
        simple_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%a3", "test/defs.bzl%a2", "test/defs.bzl%a1"),
            "//test:main");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect a1 = getConfiguredAspect(configuredAspects, "a1");
    assertThat(a1).isNotNull();
    StarlarkProvider.Key a1Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result");
    StructImpl a1ResultProvider = (StructImpl) a1.get(a1Result);
    assertThat((Sequence<?>) a1ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a1 on target @@//test:dep_target sees a2p = a2p_val and sees a3p = a3p_val",
            "aspect a1 on target @@//test:main sees a2p = a2p_val and sees a3p = a3p_val");

    ConfiguredAspect a2 = getConfiguredAspect(configuredAspects, "a2");
    assertThat(a2).isNotNull();
    StarlarkProvider.Key a2Result =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a2_result");
    StructImpl a2ResultProvider = (StructImpl) a2.get(a2Result);
    assertThat((Sequence<?>) a2ResultProvider.getValue("value"))
        .containsExactly(
            "aspect a2 on target @@//test:dep_target sees a3p = a3p_val",
            "aspect a2 on target @@//test:main sees a3p = a3p_val");
  }

  /**
   * aspects = a1, a2; aspect a1 provides a1p provider and aspect a2 requires a1p provider. These
   * top-level aspects are applied on top-level target `main` whose rule also provides a1p.
   *
   * <p>By default, the dependency between a1 and a2 will be established, the build will fail since
   * a2 will receive provider a1p twice (from a1 applied on `main` and from `main` target itself).
   */
  @Test
  public void testTopLevelAspects_duplicateRuleProviderError() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a1p = provider()
        a2p = provider()

        def _a1_impl(target, ctx):
          return [a1p(value = 'aspect_a1p_val')]
        a1 = aspect(
          implementation = _a1_impl,
          provides = [a1p],
        )

        def _a2_impl(target, ctx):
          result = 'aspect a2 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          return [a2p(value = result)]
        a2 = aspect(
          implementation = _a2_impl,
          provides = [a2p],
          required_aspect_providers = [a1p]
        )

        def _my_rule_impl(ctx):
          return [a1p(value = 'rule_a1p_val')]
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(name = 'main')
        """);
    reporter.removeHandler(failFastHandler);

    // The call to `update` does not throw an exception when "--keep_going" is passed in the
    // WithKeepGoing test suite. Otherwise, it throws ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult result =
          update(ImmutableList.of("test/defs.bzl%a1", "test/defs.bzl%a2"), "//test:main");
      assertThat(result.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () -> update(ImmutableList.of("test/defs.bzl%a1", "test/defs.bzl%a2"), "//test:main"));
    }
    assertContainsEvent("Provider a1p provided twice");
  }

  @Test
  public void testTopLevelAspectRequiresAspect_stackOfRequiredAspects() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(target, ctx):
           return []
        aspect_c = aspect(implementation = _impl)
        aspect_b = aspect(implementation = _impl, requires = [aspect_c])
        aspect_a = aspect(implementation = _impl, requires = [aspect_b])
        """);
    scratch.file("test/BUILD", "cc_binary(name = 'main_target')");

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%aspect_a"), "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    assertThat(configuredAspects).hasSize(3);
    assertThat(getConfiguredAspect(configuredAspects, "aspect_a")).isNotNull();
    assertThat(getConfiguredAspect(configuredAspects, "aspect_b")).isNotNull();
    assertThat(getConfiguredAspect(configuredAspects, "aspect_c")).isNotNull();
  }

  @Test
  public void testTopLevelAspectRequiresAspect_aspectRequiredByMultipleAspects() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(target, ctx):
           return []
        aspect_c = aspect(implementation = _impl)
        aspect_b = aspect(implementation = _impl, requires = [aspect_c])
        aspect_a = aspect(implementation = _impl, requires = [aspect_c])
        """);
    scratch.file("test/BUILD", "cc_binary(name = 'main_target')");

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%aspect_a", "test/defs.bzl%aspect_b"),
            "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    assertThat(configuredAspects).hasSize(3);
    assertThat(getConfiguredAspect(configuredAspects, "aspect_a")).isNotNull();
    assertThat(getConfiguredAspect(configuredAspects, "aspect_b")).isNotNull();
    assertThat(getConfiguredAspect(configuredAspects, "aspect_c")).isNotNull();
  }

  @Test
  public void testTopLevelAspectRequiresAspect_aspectRequiredByMultipleAspects2() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(target, ctx):
           return []
        aspect_d = aspect(implementation = _impl)
        aspect_c = aspect(implementation = _impl, requires = [aspect_d])
        aspect_b = aspect(implementation = _impl, requires = [aspect_d])
        aspect_a = aspect(implementation = _impl, requires = [aspect_b, aspect_c])
        """);
    scratch.file("test/BUILD", "cc_binary(name = 'main_target')");

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%aspect_a"), "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    assertThat(configuredAspects).hasSize(4);
    assertThat(getConfiguredAspect(configuredAspects, "aspect_a")).isNotNull();
    assertThat(getConfiguredAspect(configuredAspects, "aspect_b")).isNotNull();
    assertThat(getConfiguredAspect(configuredAspects, "aspect_c")).isNotNull();
    assertThat(getConfiguredAspect(configuredAspects, "aspect_d")).isNotNull();
  }

  @Test
  public void testTopLevelAspectRequiresAspect_requireExistingAspect_passed() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(target, ctx):
           return []
        aspect_b = aspect(implementation = _impl)
        aspect_a = aspect(implementation = _impl, requires = [aspect_b])
        """);
    scratch.file("test/BUILD", "cc_binary(name = 'main_target')");

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%aspect_b", "test/defs.bzl%aspect_a"),
            "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    assertThat(configuredAspects).hasSize(2);
    assertThat(getConfiguredAspect(configuredAspects, "aspect_a")).isNotNull();
    assertThat(getConfiguredAspect(configuredAspects, "aspect_b")).isNotNull();
  }

  @Test
  public void testTopLevelAspectRequiresAspect_requireExistingAspect_failed() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(target, ctx):
           return []
        aspect_b = aspect(implementation = _impl)
        aspect_a = aspect(implementation = _impl, requires = [aspect_b])
        """);
    scratch.file("test/BUILD", "cc_binary(name = 'main_target')");
    reporter.removeHandler(failFastHandler);

    // The call to `update` does not throw an exception when "--keep_going" is passed in the
    // WithKeepGoing test suite. Otherwise, it throws ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult result =
          update(
              ImmutableList.of("test/defs.bzl%aspect_a", "test/defs.bzl%aspect_b"),
              "//test:main_target");
      assertThat(result.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () ->
              update(
                  ImmutableList.of("test/defs.bzl%aspect_a", "test/defs.bzl%aspect_b"),
                  "//test:main_target"));
    }
    assertContainsEvent(
        "aspect //test:defs.bzl%aspect_b was added before as a required"
            + " aspect of aspect //test:defs.bzl%aspect_a");
  }

  @Test
  public void testTopLevelAspectRequiresAspect_ruleAttributes() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        RequiredAspectProv = provider()
        BaseAspectProv = provider()

        def _required_aspect_impl(target, ctx):
          p_val = ['In required_aspect, p = {} on target {}'
                      .format(ctx.rule.attr.p, target.label)]
          if ctx.rule.attr.dep and RequiredAspectProv in ctx.rule.attr.dep:
            p_val += ctx.rule.attr.dep[RequiredAspectProv].p_val
          return [RequiredAspectProv(p_val = p_val)]
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        def _base_aspect_impl(target, ctx):
          p_val = []
          p_val += target[RequiredAspectProv].p_val
          p_val += ['In base_aspect, p = {} on target {}'.format(ctx.rule.attr.p, target.label)]
          if ctx.rule.attr.dep:
            p_val += ctx.rule.attr.dep[BaseAspectProv].p_val
          return [BaseAspectProv(p_val = p_val)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect],
        )

        def _rule_impl(ctx):
          pass

        my_rule = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
            'p' : attr.string(values = ['main_val', 'dep_val']),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target',
          p = 'main_val',
        )
        my_rule(
          name = 'dep_target',
          p = 'dep_val',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%base_aspect"), "//test:main_target");

    // required_aspect can only run on main_target when propagated alone since its attr_aspects is
    // empty.
    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect requiredAspect = getConfiguredAspect(configuredAspects, "required_aspect");
    assertThat(requiredAspect).isNotNull();
    StarlarkProvider.Key requiredAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "RequiredAspectProv");
    StructImpl requiredAspectProvider = (StructImpl) requiredAspect.get(requiredAspectProv);
    assertThat((Sequence<?>) requiredAspectProvider.getValue("p_val"))
        .containsExactly("In required_aspect, p = main_val on target @@//test:main_target");

    // base_aspect can run on main_target and dep_target and it can also see the providers created
    // by running required_target on them.
    ConfiguredAspect baseAspect = getConfiguredAspect(configuredAspects, "base_aspect");
    assertThat(baseAspect).isNotNull();
    StarlarkProvider.Key baseAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv");
    StructImpl baseAspectProvider = (StructImpl) baseAspect.get(baseAspectProv);
    assertThat((Sequence<?>) baseAspectProvider.getValue("p_val"))
        .containsExactly(
            "In base_aspect, p = dep_val on target @@//test:dep_target",
            "In base_aspect, p = main_val on target @@//test:main_target",
            "In required_aspect, p = dep_val on target @@//test:dep_target",
            "In required_aspect, p = main_val on target @@//test:main_target");
  }

  @Test
  public void testTopLevelAspectRequiresAspect_inheritPropagationAttributes() throws Exception {
    // base_aspect propagates over base_dep attribute and requires first_required_aspect which
    // propagates over first_dep attribute and requires second_required_aspect which propagates over
    // second_dep attribute
    scratch.file(
        "test/defs.bzl",
        """
        BaseAspectProv = provider()
        FirstRequiredAspectProv = provider()
        SecondRequiredAspectProv = provider()

        def _second_required_aspect_impl(target, ctx):
          result = []
          if getattr(ctx.rule.attr, 'second_dep'):
            result += getattr(ctx.rule.attr, 'second_dep')[SecondRequiredAspectProv].result
          result += ['second_required_aspect run on target {}'.format(target.label)]
          return [SecondRequiredAspectProv(result = result)]
        second_required_aspect = aspect(
          implementation = _second_required_aspect_impl,
          attr_aspects = ['second_dep'],
        )

        def _first_required_aspect_impl(target, ctx):
          result = []
          result += target[SecondRequiredAspectProv].result
          if getattr(ctx.rule.attr, 'first_dep'):
            result += getattr(ctx.rule.attr, 'first_dep')[FirstRequiredAspectProv].result
          result += ['first_required_aspect run on target {}'.format(target.label)]
          return [FirstRequiredAspectProv(result = result)]
        first_required_aspect = aspect(
          implementation = _first_required_aspect_impl,
          attr_aspects = ['first_dep'],
          requires = [second_required_aspect],
        )

        def _base_aspect_impl(target, ctx):
          result = []
          result += target[FirstRequiredAspectProv].result
          if getattr(ctx.rule.attr, 'base_dep'):
            result += getattr(ctx.rule.attr, 'base_dep')[BaseAspectProv].result
          result += ['base_aspect run on target {}'.format(target.label)]
          return [BaseAspectProv(result = result)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['base_dep'],
          requires = [first_required_aspect],
        )

        def _my_rule_impl(ctx):
          pass

        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'base_dep': attr.label(),
            'first_dep': attr.label(),
            'second_dep': attr.label()
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          base_dep = ':base_dep_target',
          first_dep = ':first_dep_target',
          second_dep = ':second_dep_target',
        )
        my_rule(
          name = 'base_dep_target',
        )
        my_rule(
          name = 'first_dep_target',
        )
        my_rule(
          name = 'second_dep_target',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%base_aspect"), "//test:main_target");

    // base_aspect should propagate only along its attr_aspects: 'base_dep'
    // first_required_aspect should propagate along 'base_dep' and 'first_dep'
    // second_required_aspect should propagate along 'base_dep', 'first_dep' and 'second_dep'
    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect baseAspect = getConfiguredAspect(configuredAspects, "base_aspect");
    assertThat(baseAspect).isNotNull();
    StarlarkProvider.Key baseAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv");
    StructImpl baseAspectProvider = (StructImpl) baseAspect.get(baseAspectProv);
    assertThat((Sequence<?>) baseAspectProvider.getValue("result"))
        .containsExactly(
            "second_required_aspect run on target @@//test:second_dep_target",
            "second_required_aspect run on target @@//test:main_target",
            "second_required_aspect run on target @@//test:first_dep_target",
            "second_required_aspect run on target @@//test:base_dep_target",
            "first_required_aspect run on target @@//test:first_dep_target",
            "first_required_aspect run on target @@//test:main_target",
            "first_required_aspect run on target @@//test:base_dep_target",
            "base_aspect run on target @@//test:base_dep_target",
            "base_aspect run on target @@//test:main_target");
  }

  @Test
  public void testTopLevelAspectRequiresAspect_inheritRequiredProviders() throws Exception {
    // aspect_a requires provider Prov_A and requires aspect_b which requires
    // provider Prov_B and requires aspect_c which requires provider Prov_C
    scratch.file(
        "test/defs.bzl",
        """
        Prov_A = provider()
        Prov_B = provider()
        Prov_C = provider()

        CollectorProv = provider()

        def _aspect_c_impl(target, ctx):
          collector_result = ['aspect_c run on target {} and value of Prov_C = {}'
                                        .format(target.label, target[Prov_C].val)]
          return [CollectorProv(result = collector_result)]
        aspect_c = aspect(
          implementation = _aspect_c_impl,
          required_providers = [Prov_C],
          attr_aspects = ['dep'],
        )

        def _aspect_b_impl(target, ctx):
          collector_result = []
          collector_result += ctx.rule.attr.dep[CollectorProv].result
          collector_result += ['aspect_b run on target {} and value of Prov_B = {}'
                                         .format(target.label, target[Prov_B].val)]
          return [CollectorProv(result = collector_result)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_providers = [Prov_B],
          requires = [aspect_c],
          attr_aspects = ['dep'],
        )

        def _aspect_a_impl(target, ctx):
          collector_result = []
          collector_result += ctx.rule.attr.dep[CollectorProv].result
          collector_result += ['aspect_a run on target {} and value of Prov_A = {}'
                                         .format(target.label, target[Prov_A].val)]
          return [CollectorProv(result = collector_result)]
        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          required_providers = [Prov_A],
          requires = [aspect_b],
        )

        def _my_rule_impl(ctx):
          return [Prov_A(val='main_val_a')]
        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
          provides = [Prov_A]
        )

        def _rule_with_prov_a_impl(ctx):
          return [Prov_A(val='val_a')]
        rule_with_prov_a = rule(
          implementation = _rule_with_prov_a_impl,
          attrs = {
            'dep': attr.label(),
          },
          provides = [Prov_A]
        )

        def _rule_with_prov_b_impl(ctx):
          return [Prov_B(val = 'val_b')]
        rule_with_prov_b = rule(
          implementation = _rule_with_prov_b_impl,
          attrs = {
            'dep': attr.label(),
          },
          provides = [Prov_B]
        )

        def _rule_with_prov_c_impl(ctx):
          return [Prov_C(val = 'val_c')]
        rule_with_prov_c = rule(
          implementation = _rule_with_prov_c_impl,
          provides = [Prov_C]
        )
        """);
    scratch.file(
        "test/BUILD",
        "load('//test:defs.bzl', 'my_rule', 'rule_with_prov_a', 'rule_with_prov_b',"
            + " 'rule_with_prov_c')",
        "my_rule(",
        "  name = 'main_target',",
        "  dep = ':target_with_prov_a',",
        ")",
        "rule_with_prov_a(",
        "  name = 'target_with_prov_a',",
        "  dep = ':target_with_prov_b'",
        ")",
        "rule_with_prov_b(",
        "  name = 'target_with_prov_b',",
        "  dep = ':target_with_prov_c'",
        ")",
        "rule_with_prov_c(",
        "  name = 'target_with_prov_c'",
        ")");

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%aspect_a"), "//test:main_target");

    // aspect_a should run on main_target and target_with_prov_a
    // aspect_b can reach target_with_prov_b because it inherits the required_providers of aspect_a
    // aspect_c can reach target_with_prov_c because it inherits the required_providers of aspect_a
    // and aspect_b
    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkProvider.Key collectorProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "CollectorProv");
    StructImpl collectorProvider = (StructImpl) aspectA.get(collectorProv);
    assertThat((Sequence<?>) collectorProvider.getValue("result"))
        .containsExactly(
            "aspect_c run on target @@//test:target_with_prov_c and value of Prov_C = val_c",
            "aspect_b run on target @@//test:target_with_prov_b and value of Prov_B = val_b",
            "aspect_a run on target @@//test:target_with_prov_a and value of Prov_A = val_a",
            "aspect_a run on target @@//test:main_target and value of Prov_A = main_val_a")
        .inOrder();
  }

  @Test
  public void testTopLevelAspectRequiresAspect_inspectRequiredAspectActions() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        BaseAspectProvider = provider()
        def _required_aspect_impl(target, ctx):
          f = ctx.actions.declare_file('dummy.txt')
          ctx.actions.run_shell(outputs = [f], command='echo xxx > $(location f)',
                                mnemonic='RequiredAspectAction')
          return []
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        def _base_aspect_impl(target, ctx):
          required_aspect_action = None
          for action in target.actions:
            if action.mnemonic == 'RequiredAspectAction':
              required_aspect_action = action
          if required_aspect_action:
            return [BaseAspectProvider(result = 'base_aspect can see required_aspect action')]
          else:
            return [BaseAspectProvider(result = 'base_aspect cannot see required_aspect action')]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect]
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%base_aspect"), "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect baseAspect = getConfiguredAspect(configuredAspects, "base_aspect");
    assertThat(baseAspect).isNotNull();
    StarlarkProvider.Key baseAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProvider");
    StructImpl baseAspectProvider = (StructImpl) baseAspect.get(baseAspectProv);
    assertThat(baseAspectProvider.getValue("result"))
        .isEqualTo("base_aspect can see required_aspect action");
  }

  @Test
  public void testTopLevelAspectRequiresAspect_inspectRequiredAspectGeneratedFiles()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        BaseAspectProvider = provider()
        def _required_aspect_impl(target, ctx):
          file = ctx.actions.declare_file('required_aspect_file')
          ctx.actions.write(file, 'data')
          return [OutputGroupInfo(out = [file])]
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        def _base_aspect_impl(target, ctx):
          files = ['base_aspect can see file ' + f.path.split('/')[-1]
                       for f in target[OutputGroupInfo].out.to_list()]
          return [BaseAspectProvider(my_files = files)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect]
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("test/defs.bzl%base_aspect"), "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect baseAspect = getConfiguredAspect(configuredAspects, "base_aspect");
    assertThat(baseAspect).isNotNull();
    StarlarkProvider.Key baseAspectProv =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProvider");
    StructImpl baseAspectProvider = (StructImpl) baseAspect.get(baseAspectProv);
    assertThat((Sequence<?>) baseAspectProvider.getValue("my_files"))
        .containsExactly("base_aspect can see file required_aspect_file");
  }

  @Test
  public void testTopLevelAspectRequiresAspect_withRequiredAspectProvidersSatisfied()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        prov_b = provider()
        prov_b_forwarded = provider()

        def _aspect_b_impl(target, ctx):
          result = 'aspect_b on target {} '.format(target.label)
          if prov_b in target:
            result += 'found prov_b = {}'.format(target[prov_b].val)
            return struct(aspect_b_result = result,
                          providers = [prov_b_forwarded(val = target[prov_b].val)])
          else:
            result += 'cannot find prov_b'
            return struct(aspect_b_result = result)
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_aspect_providers = [prov_b]
        )

        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {} '.format(target.label)
          if prov_a in target:
            result += 'found prov_a = {}'.format(target[prov_a].val)
          else:
            result += 'cannot find prov_a'
          if prov_b_forwarded in target:
            result += ' and found prov_b = {}'.format(target[prov_b_forwarded].val)
          else:
            result += ' but cannot find prov_b'
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          required_aspect_providers = [prov_a],
          attr_aspects = ['dep'],
          requires = [aspect_b]
        )

        def _aspect_with_prov_a_impl(target, ctx):
          return [prov_a(val = 'a1')]
        aspect_with_prov_a = aspect(
          implementation = _aspect_with_prov_a_impl,
          provides = [prov_a],
          attr_aspects = ['dep'],
        )

        def _aspect_with_prov_b_impl(target, ctx):
          return [prov_b(val = 'b1')]
        aspect_with_prov_b = aspect(
          implementation = _aspect_with_prov_b_impl,
          provides = [prov_b],
          attr_aspects = ['dep'],
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of(
                "test/defs.bzl%aspect_with_prov_a",
                "test/defs.bzl%aspect_with_prov_b", "test/defs.bzl%aspect_a"),
            "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    String aspectAResult = (String) aspectA.get("aspect_a_result");
    assertThat(aspectAResult)
        .isEqualTo(
            "aspect_a on target @@//test:main_target found prov_a = a1 and found prov_b = b1");

    ConfiguredAspect aspectB = getConfiguredAspect(configuredAspects, "aspect_b");
    assertThat(aspectB).isNotNull();
    String aspectBResult = (String) aspectB.get("aspect_b_result");
    assertThat(aspectBResult)
        .isEqualTo("aspect_b on target @@//test:main_target found prov_b = b1");
  }

  @Test
  public void testTopLevelAspectRequiresAspect_withRequiredAspectProvidersNotFound()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        prov_a = provider()
        prov_b = provider()

        def _aspect_b_impl(target, ctx):
          result = 'aspect_b on target {} '.format(target.label)
          if prov_b in target:
            result += 'found prov_b = {}'.format(target[prov_b].val)
          else:
            result += 'cannot find prov_b'
          return struct(aspect_b_result = result)
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_aspect_providers = [prov_b]
        )

        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {} '.format(target.label)
          if prov_a in target:
            result += 'found prov_a = {}'.format(target[prov_a].val)
          else:
            result += 'cannot find prov_a'
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          required_aspect_providers = [prov_a],
          attr_aspects = ['dep'],
          requires = [aspect_b]
        )

        def _aspect_with_prov_a_impl(target, ctx):
          return [prov_a(val = 'a1')]
        aspect_with_prov_a = aspect(
          implementation = _aspect_with_prov_a_impl,
          provides = [prov_a],
          attr_aspects = ['dep'],
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("test/defs.bzl%aspect_with_prov_a", "test/defs.bzl%aspect_a"),
            "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    String aspectAResult = (String) aspectA.get("aspect_a_result");
    assertThat(aspectAResult)
        .isEqualTo("aspect_a on target @@//test:main_target found prov_a = a1");

    ConfiguredAspect aspectB = getConfiguredAspect(configuredAspects, "aspect_b");
    assertThat(aspectB).isNotNull();
    String aspectBResult = (String) aspectB.get("aspect_b_result");
    assertThat(aspectBResult)
        .isEqualTo("aspect_b on target @@//test:main_target cannot find prov_b");
  }

  @Test
  public void testDependentAspectWithNonExecutableTool_doesNotCrash() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_binary(name='bin', srcs=['bin.sh'])
        foo_library(name='lib')
        """);
    scratch.file(
        "test/defs.bzl",
        "AInfo = provider()",
        "BInfo = provider()",
        "def _aspect_a(target, ctx):",
        "  return [AInfo(value=str(ctx.attr._attr.label))]",
        "aspect_a = aspect(",
        "  implementation = _aspect_a,",
        "  provides=[AInfo],",
        "  attrs = {'_attr':" + " attr.label(default=':lib')},",
        ")",
        "def _aspect_b(target, ctx):",
        "  return [BInfo(value=str(ctx.executable._attr.path.split('/')[-1]))]",
        "aspect_b = aspect(",
        "  implementation = _aspect_b,",
        "  required_aspect_providers = [AInfo],",
        "  attrs = {'_attr': attr.label(default=':bin', executable=True, cfg='exec')},",
        ")");
    scratch.file("test/bin.sh").setExecutable(true);

    AnalysisResult result =
        update(ImmutableList.of("test/defs.bzl%aspect_a", "test/defs.bzl%aspect_b"), "//test:bin");

    ConfiguredAspect aspectB =
        result.getAspectsMap().entrySet().stream()
            .filter(a -> a.getKey().getAspectName().endsWith("aspect_b"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    assertThat(aspectB).isNotNull();

    StarlarkProvider.Key provB =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BInfo");
    assertThat(((StructImpl) aspectB.get(provB)).getValue("value")).isEqualTo("bin");

    ConfiguredAspect aspectA =
        result.getAspectsMap().entrySet().stream()
            .filter(a -> a.getKey().getAspectName().endsWith("aspect_a"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    assertThat(aspectA).isNotNull();

    StarlarkProvider.Key provA =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "AInfo");
    assertThat(((StructImpl) aspectA.get(provA)).getValue("value")).isEqualTo("@@//test:lib");
  }

  @Test
  public void testTopLevelAspectsWithParameters() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and a_p = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.a_p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'a_p' : attr.string(values = ['a_p_v1', 'a_p_v2'])},
        )

        def _aspect_b_impl(target, ctx):
          result = ['aspect_b on target {}, p1 = {} and b_p = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.b_p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_b_result
          return struct(aspect_b_result = result)

        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'b_p' : attr.string(values = ['b_p_v1', 'b_p_v2'])},
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//test:defs.bzl%aspect_a", "//test:defs.bzl%aspect_b"),
            ImmutableMap.of("p1", "p1_v1", "a_p", "a_p_v1", "b_p", "b_p_v1"),
            "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkList<?> aspectAResult = (StarlarkList) aspectA.get("aspect_a_result");
    assertThat(Starlark.toIterable(aspectAResult))
        .containsExactly(
            "aspect_a on target @@//test:main_target, p1 = p1_v1 and a_p = a_p_v1",
            "aspect_a on target @@//test:dep_target_1, p1 = p1_v1 and a_p = a_p_v1",
            "aspect_a on target @@//test:dep_target_2, p1 = p1_v1 and a_p = a_p_v1");

    ConfiguredAspect aspectB = getConfiguredAspect(configuredAspects, "aspect_b");
    assertThat(aspectB).isNotNull();
    StarlarkList<?> aspectBResult = (StarlarkList) aspectB.get("aspect_b_result");
    assertThat(Starlark.toIterable(aspectBResult))
        .containsExactly(
            "aspect_b on target @@//test:main_target, p1 = p1_v1 and b_p = b_p_v1",
            "aspect_b on target @@//test:dep_target_1, p1 = p1_v1 and b_p = b_p_v1",
            "aspect_b on target @@//test:dep_target_2, p1 = p1_v1 and b_p = b_p_v1");
  }

  @Test
  public void testTopLevelAspectsWithParameters_differentAllowedValues() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and p2 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p2)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']) },
        )

        def _aspect_b_impl(target, ctx):
          result = ['aspect_b on target {}, p1 = {} and p2 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p2)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_b_result
          return struct(aspect_b_result = result)

        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v2', 'p1_v3']) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult analysisResult =
          update(
              ImmutableList.of("//test:defs.bzl%aspect_a", "//test:defs.bzl%aspect_b"),
              ImmutableMap.of("p1", "p1_v1"),
              "//test:main_target");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () ->
              update(
                  ImmutableList.of("//test:defs.bzl%aspect_a", "//test:defs.bzl%aspect_b"),
                  ImmutableMap.of("p1", "p1_v1"),
                  "//test:main_target"));
    }
    assertContainsEvent(
        "//test:defs.bzl%aspect_b: invalid value in 'p1' attribute: has to be one of 'p1_v2' or"
            + " 'p1_v3' instead of 'p1_v1'");
  }

  @Test
  public void testTopLevelAspectsWithParameters_useDefaultValue() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and p2 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p2)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2'], default = 'p1_v1'),
                    'p2' : attr.string(values = ['p2_v1', 'p2_v2'], default = 'p2_v1')},
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//test:defs.bzl%aspect_a"),
            ImmutableMap.of("p1", "p1_v2"),
            "//test:main_target");

    Map<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkList<?> aspectAResult = (StarlarkList) aspectA.get("aspect_a_result");
    assertThat(Starlark.toIterable(aspectAResult))
        .containsExactly(
            "aspect_a on target @@//test:main_target, p1 = p1_v2 and p2 = p2_v1",
            "aspect_a on target @@//test:dep_target_1, p1 = p1_v2 and p2 = p2_v1",
            "aspect_a on target @@//test:dep_target_2, p1 = p1_v2 and p2 = p2_v1");
  }

  @Test
  public void testTopLevelAspectsWithParameters_passParametersToRequiredAspect() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_b_impl(target, ctx):
          result = ['aspect_b on target {}, p1 = {} and p3 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p3)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_b_result
          return struct(aspect_b_result = result)

        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'p3' : attr.string(values = ['p3_v1', 'p3_v2', 'p3_v3'])},
        )

        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and p2 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p2)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'p2' : attr.string(values = ['p2_v1', 'p2_v2'])},
          requires = [aspect_b],
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//test:defs.bzl%aspect_a"),
            ImmutableMap.of("p1", "p1_v1", "p2", "p2_v2", "p3", "p3_v3"),
            "//test:main_target");

    ImmutableMap<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkList<?> aspectAResult = (StarlarkList) aspectA.get("aspect_a_result");
    assertThat(Starlark.toIterable(aspectAResult))
        .containsExactly(
            "aspect_a on target @@//test:main_target, p1 = p1_v1 and p2 = p2_v2",
            "aspect_a on target @@//test:dep_target_1, p1 = p1_v1 and p2 = p2_v2",
            "aspect_a on target @@//test:dep_target_2, p1 = p1_v1 and p2 = p2_v2");

    ConfiguredAspect aspectB = getConfiguredAspect(configuredAspects, "aspect_b");
    assertThat(aspectB).isNotNull();
    StarlarkList<?> aspectBResult = (StarlarkList) aspectB.get("aspect_b_result");
    assertThat(Starlark.toIterable(aspectBResult))
        .containsExactly(
            "aspect_b on target @@//test:main_target, p1 = p1_v1 and p3 = p3_v3",
            "aspect_b on target @@//test:dep_target_1, p1 = p1_v1 and p3 = p3_v3",
            "aspect_b on target @@//test:dep_target_2, p1 = p1_v1 and p3 = p3_v3");
  }

  @Test
  public void testTopLevelAspectsWithParameters_invalidParameterValue() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(values = ['p_v1', 'p_v2']) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult analysisResult =
          update(
              ImmutableList.of("//test:defs.bzl%aspect_a"),
              ImmutableMap.of("p", "p_v"),
              "//test:main_target");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () ->
              update(
                  ImmutableList.of("//test:defs.bzl%aspect_a"),
                  ImmutableMap.of("p", "p_v"),
                  "//test:main_target"));
    }
    assertContainsEvent(
        "//test:defs.bzl%aspect_a: invalid value in 'p' attribute: has to be one of 'p_v1' or"
            + " 'p_v2' instead of 'p_v'");
  }

  @Test
  public void testTopLevelAspectsWithParameters_missingMandatoryParameter() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {}'.
                                            format(target.label, ctx.attr.p1)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(mandatory = True, default = 'p1_v1',
                                       values = ['p1_v1', 'p1_v2']),
                    'p2' : attr.string(values = ['p2_v1', 'p2_v2'])},
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult analysisResult =
          update(
              ImmutableList.of("//test:defs.bzl%aspect_a"),
              ImmutableMap.of("p2", "p2_v1"),
              "//test:main_target");

      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () ->
              update(
                  ImmutableList.of("//test:defs.bzl%aspect_a"),
                  ImmutableMap.of("p2", "p2_v1"),
                  "//test:main_target"));
    }
    assertContainsEvent("Missing mandatory attribute 'p1' for aspect '//test:defs.bzl%aspect_a'");
  }

  @Test
  public void testTopLevelAspectsWithParameters_unusedParameter() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and a_p = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.a_p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'a_p' : attr.string(values = ['a_p_v1', 'a_p_v2'])},
        )

        def _aspect_b_impl(target, ctx):
          result = ['aspect_b on target {}, p1 = {} and b_p = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.b_p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_b_result
          return struct(aspect_b_result = result)

        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'b_p' : attr.string(values = ['b_p_v1', 'b_p_v2'])},
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult analysisResult =
          update(
              ImmutableList.of("//test:defs.bzl%aspect_a", "//test:defs.bzl%aspect_b"),
              ImmutableMap.of("p2", "p2_v1", "b_p", "b_p_v1"),
              "//test:main_target");

      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () ->
              update(
                  ImmutableList.of("//test:defs.bzl%aspect_a", "//test:defs.bzl%aspect_b"),
                  ImmutableMap.of("p2", "p2_v1", "b_p", "b_p_v1"),
                  "//test:main_target"));
    }
    assertContainsEvent(
        "Parameters '[p2]' are not parameters of any of the top-level aspects but they are"
            + " specified in --aspects_parameters.");
  }

  @Test
  public void testTopLevelAspectsWithParameters_invalidDefaultParameterValue() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(values = ['p_v1', 'p_v2']) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult analysisResult =
          update(ImmutableList.of("//test:defs.bzl%aspect_a"), "//test:main_target");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () -> update(ImmutableList.of("//test:defs.bzl%aspect_a"), "//test:main_target"));
    }
    assertContainsEvent(
        "//test:defs.bzl%aspect_a: invalid value in 'p' attribute: has to be one of 'p_v1' or"
            + " 'p_v2' instead of ''");
  }

  @Test
  public void testTopLevelAspectsWithParameters_noNeedForAllowedValues() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default='val') },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//test:defs.bzl%aspect_a"),
            ImmutableMap.of("p", "p_v"),
            "//test:main_target");

    ImmutableMap<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkList<?> aspectAResult = (StarlarkList) aspectA.get("aspect_a_result");
    assertThat(Starlark.toIterable(aspectAResult))
        .containsExactly(
            "aspect_a on target @@//test:main_target, p = p_v",
            "aspect_a on target @@//test:dep_target_1, p = p_v",
            "aspect_a on target @@//test:dep_target_2, p = p_v");
  }

  /**
   * Aspect parameter has to require set of values only if the aspect is used in a rule attribute.
   */
  @Test
  public void testAttrAspectParameterMissingRequiredValues() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(target, ctx):
           pass
        my_aspect = aspect(_impl,
           attrs = { 'param' : attr.string(default = 'c') }
        )
        def _rule_impl(ctx):
           pass
        r1 = rule(_rule_impl, attrs={'dep': attr.label(aspects = [my_aspect])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'r1')
        r1(name = 'main_target')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update("//test:main_target");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update("//test:main_target"));
    }
    assertContainsEvent(
        "Aspect //test:defs.bzl%my_aspect: Aspect parameter attribute 'param' must use the 'values'"
            + " restriction.");
  }

  /**
   * Aspect parameter has to require set of values only if the aspect is used in a rule attribute.
   */
  @Test
  public void testAttrRequiredAspectParameterMissingRequiredValues() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(target, ctx):
           pass
        required_aspect = aspect(_impl,
           attrs = { 'p1' : attr.string(default = 'b') }
        )
        my_aspect = aspect(_impl,
           attrs = { 'p2' : attr.string(default = 'c', values = ['c']) },
           requires = [required_aspect],
        )
        def _rule_impl(ctx):
           pass
        r1 = rule(_rule_impl, attrs={'dep': attr.label(aspects = [my_aspect])})
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'r1')
        r1(name = 'main_target')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update("//test:main_target");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update("//test:main_target"));
    }
    assertContainsEvent(
        "Aspect //test:defs.bzl%required_aspect: Aspect parameter attribute 'p1' must use the"
            + " 'values' restriction.");
  }

  @Test
  public void integerAspectParameter_mandatoryAttrNotCoveredByRule() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2], mandatory = True) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
            + "'my_attr' with type int.");
  }

  @Test
  public void integerAspectParameter_mandatoryAttrWithWrongTypeInRule() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2], mandatory = True) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr': attr.string() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
            + "'my_attr' with type int.");
  }

  @Test
  public void integerAspectParameter_attrWithoutDefaultNotCoveredByRule() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(values = [1, 2]) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
            + "'my_attr' with type int.");
  }

  @Test
  public void integerAspectParameter_attrWithoutDefaultWrongTypeInRule() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(values = [1, 2]) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr': attr.string() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
            + "'my_attr' with type int.");
  }

  @Test
  public void integerAspectParameter_missingValuesRestriction() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int() },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr' : attr.int() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectUncovered: Aspect parameter attribute 'my_attr' must use"
            + " the 'values' restriction.");
  }

  @Test
  public void integerAspectParameter_invalidDefault() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(default = 2, values = [0, 1]) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect parameter attribute 'my_attr' has a bad default value: has to be one of '0' or '1'"
            + " instead of '2'");
  }

  @Test
  public void aspectIntegerParameter_withDefaultValue() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2, 3]) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]) }
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
        )
        my_rule(name = 'dep_target')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main_target");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkList<?> ruleResult =
        (StarlarkList) getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result");
    assertThat(Starlark.toIterable(ruleResult))
        .containsExactly("my_aspect on target @@//test:dep_target, my_attr = 1");
  }

  @Test
  public void aspectIntegerParameter_valueOverwrittenByRuleDefault() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.my_aspect_result
          return [AspectInfo(my_aspect_result = result)]

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2, 3]) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]),
                      'my_attr': attr.int(default = 2) }
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
        )
        my_rule(name = 'dep_target')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main_target");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkList<?> ruleResult =
        (StarlarkList) getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result");
    assertThat(Starlark.toIterable(ruleResult))
        .containsExactly("my_aspect on target @@//test:dep_target, my_attr = 2");
  }

  @Test
  public void aspectIntegerParameter_valueOverwrittenByTargetValue() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2, 3]) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]),
                      'my_attr': attr.int(default = 2) }
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
                my_attr = 3,
        )
        my_rule(name = 'dep_target')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main_target");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkList<?> ruleResult =
        (StarlarkList) getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result");
    assertThat(Starlark.toIterable(ruleResult))
        .containsExactly("my_aspect on target @@//test:dep_target, my_attr = 3");
  }

  @Test
  public void testTopLevelAspectsWithParameters_invalidIntegerParameterValue() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.int(values = [1, 2]) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult analysisResult =
          update(
              ImmutableList.of("//test:defs.bzl%aspect_a"),
              ImmutableMap.of("p", "3"),
              "//test:main_target");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () ->
              update(
                  ImmutableList.of("//test:defs.bzl%aspect_a"),
                  ImmutableMap.of("p", "3"),
                  "//test:main_target"));
    }
    assertContainsEvent(
        "//test:defs.bzl%aspect_a: invalid value in 'p' attribute: has to be one of '1' or"
            + " '2' instead of '3'");
  }

  @Test
  public void testTopLevelAspectsWithIntegerParameter() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.int(values = [1, 2, 3]) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//test:defs.bzl%aspect_a"),
            ImmutableMap.of("p", "2"),
            "//test:main_target");

    ImmutableMap<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkList<?> aspectAResult = (StarlarkList) aspectA.get("aspect_a_result");
    assertThat(Starlark.toIterable(aspectAResult))
        .containsExactly(
            "aspect_a on target @@//test:main_target, p = 2",
            "aspect_a on target @@//test:dep_target_1, p = 2",
            "aspect_a on target @@//test:dep_target_2, p = 2");
  }

  @Test
  public void testTopLevelAspectsWithIntegerParameter_useDefaultValue() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.int(default = 1, values = [1, 2, 3]) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("//test:defs.bzl%aspect_a"), "//test:main_target");

    ImmutableMap<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkList<?> aspectAResult = (StarlarkList) aspectA.get("aspect_a_result");
    assertThat(Starlark.toIterable(aspectAResult))
        .containsExactly(
            "aspect_a on target @@//test:main_target, p = 1",
            "aspect_a on target @@//test:dep_target_1, p = 1",
            "aspect_a on target @@//test:dep_target_2, p = 1");
  }

  @Test
  public void booleanAspectParameter_mandatoryAttrNotCoveredByRule() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.bool(default = True, mandatory = True) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
            + "'my_attr' with type boolean.");
  }

  @Test
  public void booleanAspectParameter_mandatoryAttrWithWrongTypeInRule() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.bool(default = True, mandatory = True) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr': attr.string() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
            + "'my_attr' with type boolean.");
  }

  @Test
  public void booleanAspectParameter_attrWithoutDefaultNotCoveredByRule() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.bool() },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
            + "'my_attr' with type boolean.");
  }

  @Test
  public void booleanAspectParameter_attrWithoutDefaultWrongTypeInRule() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.bool() },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr': attr.string() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update(ImmutableList.of(), "//test:main"));
    }

    assertContainsEvent(
        "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
            + "'my_attr' with type boolean.");
  }

  @Test
  public void aspectBooleanParameter_withDefaultValue() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.bool(default = True) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]) }
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
        )
        my_rule(name = 'dep_target')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main_target");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkList<?> ruleResult =
        getStarlarkProvider(configuredTarget, "RuleInfo")
            .getValue("my_rule_result", StarlarkList.class);
    assertThat(Starlark.toIterable(ruleResult))
        .containsExactly("my_aspect on target @@//test:dep_target, my_attr = True");
  }

  @Test
  public void aspectBooleanParameter_valueOverwrittenByRuleDefault() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.bool(default = True) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]),
                      'my_attr': attr.bool(default = False) }
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
        )
        my_rule(name = 'dep_target')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main_target");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkList<?> ruleResult =
        (StarlarkList) getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result");
    assertThat(Starlark.toIterable(ruleResult))
        .containsExactly("my_aspect on target @@//test:dep_target, my_attr = False");
  }

  @Test
  public void aspectBooleanParameter_valueOverwrittenByTargetValue() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.bool(default = True) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]),
                      'my_attr': attr.bool(default = True) }
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
                my_attr = False,
        )
        my_rule(name = 'dep_target')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of(), "//test:main_target");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkList<?> ruleResult =
        (StarlarkList) getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result");
    assertThat(Starlark.toIterable(ruleResult))
        .containsExactly("my_aspect on target @@//test:dep_target, my_attr = False");
  }

  @Test
  public void testTopLevelAspectsWithBooleanParameter() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.bool() },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//test:defs.bzl%aspect_a"),
            ImmutableMap.of("p", "y"),
            "//test:main_target");

    ImmutableMap<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkList<?> aspectAResult = (StarlarkList) aspectA.get("aspect_a_result");
    assertThat(Starlark.toIterable(aspectAResult))
        .containsExactly(
            "aspect_a on target @@//test:main_target, p = True",
            "aspect_a on target @@//test:dep_target_1, p = True",
            "aspect_a on target @@//test:dep_target_2, p = True");
  }

  @Test
  public void testTopLevelAspectsWithBooleanParameter_useDefaultValue() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.bool(default = False) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);

    AnalysisResult analysisResult =
        update(ImmutableList.of("//test:defs.bzl%aspect_a"), "//test:main_target");

    ImmutableMap<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkList<?> aspectAResult = (StarlarkList) aspectA.get("aspect_a_result");
    assertThat(Starlark.toIterable(aspectAResult))
        .containsExactly(
            "aspect_a on target @@//test:main_target, p = False",
            "aspect_a on target @@//test:dep_target_1, p = False",
            "aspect_a on target @@//test:dep_target_2, p = False");
  }

  @Test
  public void testTopLevelAspectsWithBooleanParameter_invalidValue() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.bool() },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a ViewCreationFailedException.
    if (keepGoing()) {
      AnalysisResult analysisResult =
          update(
              ImmutableList.of("//test:defs.bzl%aspect_a"),
              ImmutableMap.of("p", "x"),
              "//test:main_target");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(
          ViewCreationFailedException.class,
          () ->
              update(
                  ImmutableList.of("//test:defs.bzl%aspect_a"),
                  ImmutableMap.of("p", "x"),
                  "//test:main_target"));
    }
    assertContainsEvent(
        "//test:defs.bzl%aspect_a: expected value of type 'bool' for attribute 'p' but got 'x'");
  }

  @Test
  public void testRuleAspectWithMandatoryParameterNotProvided() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default = 'p_v', values = ['p_v'], mandatory = True) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label(aspects = [aspect_a]) },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update("//test:main_target");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update("//test:main_target"));
    }
    assertContainsEvent(
        "Aspect //test:defs.bzl%aspect_a requires rule my_rule to specify attribute 'p' with type"
            + " string");
  }

  @Test
  public void testRuleAspectWithMandatoryParameterProvidedWrongType() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default = 'p_v', values = ['p_v'], mandatory = True) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label(aspects = [aspect_a]),
                     'p': attr.int() }
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        """);
    reporter.removeHandler(failFastHandler);

    // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
    // suite. Otherwise, it fails and throws a TargetParsingException.
    if (keepGoing()) {
      AnalysisResult analysisResult = update("//test:main_target");
      assertThat(analysisResult.hasError()).isTrue();
    } else {
      assertThrows(TargetParsingException.class, () -> update("//test:main_target"));
    }
    assertContainsEvent(
        "Aspect //test:defs.bzl%aspect_a requires rule my_rule to specify attribute 'p' with type"
            + " string");
  }

  @Test
  public void testRuleAspectWithMandatoryParameter_useRuleDefault() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {}, p = {}'.format(target.label, ctx.attr.p)
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default = 'p_v1', values = ['p_v1', 'p_v2'],
                                      mandatory = True) },
        )

        RuleInfo = provider()
        def _main_rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(aspect_a_result = ctx.attr.dep[AspectInfo].aspect_a_result)
          pass
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_a]),
            'p' : attr.string(default = 'p_v2'),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        main_rule(
          name = 'dep_target',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    String aspectAResult =
        getStarlarkProvider(configuredTarget, "RuleInfo").getValue("aspect_a_result", String.class);
    assertThat(aspectAResult).isEqualTo("aspect_a on target @@//test:dep_target, p = p_v2");
  }

  @Test
  public void testRuleAspectWithMandatoryParameterProvided() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {}, p = {}'.format(target.label, ctx.attr.p)
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default = 'p_v2', values = ['p_v1', 'p_v2'],
                                      mandatory = True) },
        )

        RuleInfo = provider()
        def _main_rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(aspect_a_result = ctx.attr.dep[AspectInfo].aspect_a_result)
          pass
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_a]),
            'p' : attr.string(mandatory = True),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'main_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
          p = 'p_v1',
        )
        main_rule(
          name = 'dep_target',
          p = 'p_v2',
        )
        """);

    AnalysisResult analysisResult = update("//test:main");

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    String aspectAResult =
        (String) getStarlarkProvider(configuredTarget, "RuleInfo").getValue("aspect_a_result");
    assertThat(aspectAResult).isEqualTo("aspect_a on target @@//test:dep_target, p = p_v1");
  }

  @Test
  public void testAspectLabelIsRepoMapped() throws Exception {
    scratch.overwriteFile("MODULE.bazel", "module(name = 'my_repo')");
    scratch.file(
        "test/aspect.bzl",
        """
        load(':rule.bzl', 'MyInfo')
        def _impl(target, ctx):
           if MyInfo not in target:
               fail('Provider identity mismatch')
           return []
        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/rule.bzl",
        """
        MyInfo = provider()
        def _impl(ctx):
            return [MyInfo()]
        my_rule = rule(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule')
        my_rule(name = 'target')
        """);

    AnalysisResult result =
        update(ImmutableList.of("@my_repo//test:aspect.bzl%MyAspect"), "//test:target");
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testAspectKeyCreatedOnlyOnceForSameBaseKeysInDiffOrder() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a_provider = provider()
        b_provider = provider()
        c_provider = provider()
        RuleInfo = provider()

        def _a_impl(target, ctx):
          result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              result.extend(dep[a_provider].value)
          result.append('aspect a on target {} aspect_ids {}'.format(target.label,
                                                                        ctx.aspect_ids))
          return [a_provider(value = result)]
        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[b_provider], [c_provider]],
        )

        def _b_impl(target, ctx):
          return [b_provider(value = ['aspect b on target {}'.format(target.label)])]
        b = aspect(
          implementation = _b_impl,
          attr_aspects = ['deps'],
          provides = [b_provider],
        )

        def _c_impl(target, ctx):
          return [c_provider(value = ['aspect c on target {}'.format(target.label)])]
        c = aspect(
          implementation = _c_impl,
          attr_aspects = ['deps'],
          provides = [c_provider]
        )

        def _r1_impl(ctx):
          result = []
          if ctx.attr.deps:
            for dep in ctx.attr.deps:
              result.extend(dep[a_provider].value)
          return RuleInfo(aspect_a_collected_result = result)
        r1 = rule(
          implementation = _r1_impl,
          attrs = {
            'deps': attr.label_list(aspects = [c, b, a]),
          },
        )

        def _r2_impl(ctx):
          pass
        r2 = rule(
          implementation = _r2_impl,
          attrs = {
            'deps': attr.label_list(aspects = [b]),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'r1', 'r2')
        r1(
          name = 't1',
        # base_keys of aspect a on t3 are [c, b]
          deps = [':t2', ':t3'],
        )
        r2(
          name = 't2',
          # aspects reaching t3 will be [b, c, b, a], after deduplicating aspects path, it will be
          # [b, c, a] and as a result the base_keys of aspect a will be [b, c]
          deps = [':t3'],
        )
        r2(
          name = 't3',
        )
        """);

    update("//test:t1");

    // Aspect a should have a single AspectKey for its application on t3 and the baseKeys in it will
    // be sorted as [b, c]
    ImmutableList<AspectKey> keysForAspectAOnT3 = getAspectKeys("//test:t3", "//test:defs.bzl%a");
    assertThat(keysForAspectAOnT3).hasSize(1);

    ImmutableList<AspectKey> baseKeys = keysForAspectAOnT3.get(0).getBaseKeys();
    assertThat(baseKeys.stream().map(k -> k.getAspectClass().getName()))
        .containsExactly("//test:defs.bzl%b", "//test:defs.bzl%c")
        .inOrder();
  }

  @Test
  public void testAspectRunsTwiceWithDiffBaseAspectsDeps() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        a_provider = provider()
        b_provider = provider()
        c_provider = provider()

        def _a_impl(target, ctx):
          result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              result.extend(dep[a_provider].value)
          if b_provider in target:
            result.append('aspect a on {} sees b_provider = {}'.format(target.label,
                                                                      target[b_provider].value))
          return [a_provider(value = result)]
        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[b_provider], [c_provider]],
        )

        def _b_impl(target, ctx):
          result = 'aspect b cannot see c_provider'
          if c_provider in target:
            result = 'aspect b can see c_provider'
          return [b_provider(value = result)]
        b = aspect(
          implementation = _b_impl,
          attr_aspects = ['deps'],
          provides = [b_provider],
          required_aspect_providers = [[c_provider]]
        )

        def _c_impl(target, ctx):
          return [c_provider(value = ['aspect c on target {}'.format(target.label)])]
        c = aspect(
          implementation = _c_impl,
          attr_aspects = ['deps'],
          provides = [c_provider]
        )

        RuleInfo = provider()
        def _r1_impl(ctx):
          result = []
          if ctx.attr.deps:
            for dep in ctx.attr.deps:
              result.extend(dep[a_provider].value)
          return RuleInfo(aspect_a_collected_result = result)
        r1 = rule(
          implementation = _r1_impl,
          attrs = {
            'deps': attr.label_list(aspects = [a]),
          },
        )

        def _r2_impl(ctx):
          pass
        r2 = rule(
          implementation = _r2_impl,
          attrs = {
            'deps': attr.label_list(aspects = [c, b]),
          },
        )

        def _r3_impl(ctx):
          pass
        r3 = rule(
          implementation = _r3_impl,
          attrs = {
            'deps': attr.label_list(aspects = [b, c]),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'r1', 'r2', 'r3')
        r1(
          name = 't1',
          # t1 propagate aspect (a) to targets (t2 and t3)
          deps = [':t2', ':t3'],
        )
        r2(
          name = 't2',
          # t2 propagates aspects (c, b) to target t4 and aspect a is propagated from the prev
          # level aspects path on t4 is [c, b, a], this means a can see b and b can see c
          deps = [':t4'],
        )
        r3(
          name = 't3',
        # t3 propagates aspects (b, c) to target t4 and aspect a is propagated from the prev level
        # aspects path on t4 is [b, c, a], this means a can see b but b cannot see c
         deps = [':t4'],
        )
        r1(
          name = 't4',
        )
        """);

    AnalysisResult analysisResult = update("//test:t1");

    // Aspect a should have 2 AspectKeys for its application on t4, one where in the basekeys b can
    // see c and the other is where b cannot see c
    ImmutableList<AspectKey> keysForAspectAOnT4 = getAspectKeys("//test:t4", "//test:defs.bzl%a");
    assertThat(keysForAspectAOnT4).hasSize(2);

    ConfiguredTarget configuredTarget =
        Iterables.getOnlyElement(analysisResult.getTargetsToBuild());
    StarlarkList<?> aspectAResult =
        (StarlarkList)
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("aspect_a_collected_result");
    assertThat(Starlark.toIterable(aspectAResult))
        .containsExactly(
            "aspect a on @@//test:t4 sees b_provider = aspect b can see c_provider",
            "aspect a on @@//test:t4 sees b_provider = aspect b cannot see c_provider");
  }

  @Test
  public void testAspectWithSameExplicitAttributeNameAsUnderlyingTarget() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _a_impl(target, ctx):
          value = 'x from aspect = {}, x from target = {}'.format(ctx.attr.x, ctx.rule.attr.x)
          return struct(aspect_result = value)
        a = aspect(
          implementation = _a_impl,
          attrs = {
            'x': attr.string(default = 'xyz')
          },
        )

        def _rule_impl(ctx):
          pass
        r1 = rule(
          implementation = _rule_impl,
          attrs = {
            'x': attr.int(default = 4)
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of("//test:defs.bzl%a"), "//test:t1");

    ImmutableMap<AspectKey, ConfiguredAspect> configuredAspects = analysisResult.getAspectsMap();
    ConfiguredAspect aspectA = getConfiguredAspect(configuredAspects, "a");
    assertThat(aspectA).isNotNull();
    String aspectAResult = (String) aspectA.get("aspect_result");
    assertThat(aspectAResult).isEqualTo("x from aspect = xyz, x from target = 4");
  }

  @Test
  public void testAspectNotDependOnTargetDeps() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _a_impl(target, ctx):
          return []
        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['dep'],
          attrs = {
            '_tool': attr.label(default = '//test:tool'),
          },
        )

        def _rule_impl(ctx):
          pass
        r1 = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
            'another_dep': attr.label(),
            '_tool': attr.label(default = '//test:tool'),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1', dep = ':t2', another_dep = 't4')
        r1(name = 't2', dep = ':t3')
        r1(name = 't3')
        r1(name = 't4')
        filegroup(name = 'tool')
        """);

    AnalysisResult analysisResult = update(ImmutableList.of("//test:defs.bzl%a"), "//test:t1");

    AspectKey key = Iterables.getOnlyElement(analysisResult.getAspectsMap().keySet());
    var aspectNode =
        skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
            .filter(n -> n.getKey().equals(key))
            .findFirst()
            .orElse(null);
    assertThat(aspectNode).isNotNull();

    ImmutableList<String> configuredTargetsDeps =
        stream(Iterables.filter(aspectNode.getDirectDeps(), ConfiguredTargetKey.class))
            .map(k -> k.getLabel().toString())
            .collect(toImmutableList());
    // aspect depends only on its target and its implicit dependencies not the dependencies of its
    // target
    assertThat(configuredTargetsDeps).containsAtLeast("//test:tool", "//test:t1");
    assertThat(configuredTargetsDeps).doesNotContain("//test:t2");
    assertThat(configuredTargetsDeps).doesNotContain("//test:t3");
    assertThat(configuredTargetsDeps).doesNotContain("//test:t4");

    ImmutableList<String> aspectsDeps =
        stream(Iterables.filter(aspectNode.getDirectDeps(), AspectKey.class))
            .map(k -> k.getLabel().toString())
            .collect(toImmutableList());
    // aspect depends on the result of its application on the target deps if it propagates to them
    assertThat(aspectsDeps).containsExactly("//test:t2");
  }

  @Test
  public void testTopLevelAspectNotDependsOnConfigeredTopLevelTarget() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _a_impl(target, ctx):
          return []
        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['dep'],
        )

        def _rule_impl(ctx):
          pass
        r1 = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1', dep = ':t2')
        r1(name = 't2', dep = ':t3')
        r1(name = 't3')
        """);

    var unused = update(ImmutableList.of("//test:defs.bzl%a"), "//test:t1");
    var topLevelAspectsNode =
        skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
            .filter(n -> n.getKey().getCanonicalName().contains("TopLevelAspectsKey"))
            .findFirst()
            .orElse(null);
    assertThat(topLevelAspectsNode).isNotNull();

    // top level aspect should not depend on any configured target.
    ImmutableList<String> configuredTargetsDeps =
        stream(Iterables.filter(topLevelAspectsNode.getDirectDeps(), ConfiguredTargetKey.class))
            .map(k -> k.getLabel().toString())
            .collect(toImmutableList());
    assertThat(configuredTargetsDeps).isEmpty();

    // top level aspect should not even depend on any build configuration.
    ImmutableList<String> buildConfiguredTargetsDeps =
        stream(Iterables.filter(topLevelAspectsNode.getDirectDeps(), BuildConfigurationKey.class))
            .map(k -> k.getCanonicalName())
            .collect(toImmutableList());
    assertThat(buildConfiguredTargetsDeps).hasSize(0);

    // top level aspect should depend on the result of aspect's application on the top level target.
    ImmutableList<String> aspectsDeps =
        stream(Iterables.filter(topLevelAspectsNode.getDirectDeps(), AspectKey.class))
            .map(k -> k.getLabel().toString())
            .collect(toImmutableList());
    assertThat(aspectsDeps).containsExactly("//test:t1");
  }

  private ImmutableList<AspectKey> getAspectKeys(String targetLabel, String aspectLabel) {
    return skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
        .filter(
            entry ->
                entry.getKey() instanceof AspectKey
                    && ((AspectKey) entry.getKey()).getAspectClass().getName().equals(aspectLabel)
                    && ((AspectKey) entry.getKey()).getLabel().toString().equals(targetLabel))
        .map(e -> (AspectKey) e.getKey())
        .collect(toImmutableList());
  }

  private ConfiguredAspect getConfiguredAspect(
      Map<AspectKey, ConfiguredAspect> aspectsMap, String aspectName) {
    for (Map.Entry<AspectKey, ConfiguredAspect> entry : aspectsMap.entrySet()) {
      AspectClass aspectClass = entry.getKey().getAspectClass();
      if (aspectClass instanceof StarlarkAspectClass starlarkAspectClass) {
        String aspectExportedName = starlarkAspectClass.getExportedName();
        if (aspectExportedName.equals(aspectName)) {
          return entry.getValue();
        }
      }
    }
    return null;
  }

  private ConfiguredAspect getConfiguredAspect(
      Map<AspectKey, ConfiguredAspect> aspectsMap, String aspectName, String targetName) {
    for (Map.Entry<AspectKey, ConfiguredAspect> entry : aspectsMap.entrySet()) {
      AspectClass aspectClass = entry.getKey().getAspectClass();
      if (aspectClass instanceof StarlarkAspectClass starlarkAspectClass) {
        String aspectExportedName = starlarkAspectClass.getExportedName();
        String target = entry.getKey().getLabel().getName();
        if (aspectExportedName.equals(aspectName) && target.equals(targetName)) {
          return entry.getValue();
        }
      }
    }
    return null;
  }

  private void exposeNativeAspectToStarlark() throws Exception {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addBzlToplevel(
        "starlark_native_aspect", TestAspects.STARLARK_NATIVE_ASPECT_WITH_PROVIDER);
    builder.addBzlToplevel(
        "parametrized_native_aspect",
        TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER);
    builder.addNativeAspectClass(TestAspects.STARLARK_NATIVE_ASPECT_WITH_PROVIDER);
    builder.addNativeAspectClass(TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER);
    builder.addRuleDefinition(TestAspects.BASE_RULE);
    builder.addRuleDefinition(TestAspects.HONEST_RULE);
    useRuleClassProvider(builder.build());
  }

  @Test
  public void starlarkDefinedAspectCodec() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        """);
    scratch.file(
        "test/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "java_library(name = 'xxx',)");

    // Runs a basic analysis to prime test/aspect.bzl in Skyframe.
    AnalysisResult unusedAnalysisResult =
        update(ImmutableList.of("test/aspect.bzl%MyAspect"), "//test:xxx");

    // Pulls MyAspect's value out of Skyframe from its BzlLoadValue.
    var aspectBzl =
        (BzlLoadValue) getDoneValue(keyForBuild(Label.parseCanonical("//test:aspect.bzl")));
    var myAspect =
        (StarlarkDefinedAspect) checkNotNull(aspectBzl.getModule().getGlobal("MyAspect"));

    var deserialized = RoundTripping.roundTripWithSkyframe(this::getDoneValue, myAspect);
    assertThat(myAspect).isSameInstanceAs(deserialized);
  }

  private SkyValue getDoneValue(SkyKey key) {
    try {
      return skyframeExecutor.getDoneSkyValueForIntrospection(key);
    } catch (SkyframeExecutor.FailureToRetrieveIntrospectedValueException e) {
      throw new AssertionError(e);
    }
  }

  /** StarlarkAspectTest with "keep going" flag */
  @RunWith(JUnit4.class)
  public static final class WithKeepGoing extends StarlarkDefinedAspectsTest {
    @Override
    protected FlagBuilder defaultFlags() {
      return super.defaultFlags().with(Flag.KEEP_GOING);
    }

    @Override
    protected boolean keepGoing() {
      return true;
    }
  }
}
