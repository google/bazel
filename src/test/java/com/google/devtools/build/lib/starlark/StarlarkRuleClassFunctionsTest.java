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

package com.google.devtools.build.lib.starlark;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.analysis.testing.ExecGroupSubject.assertThat;
import static com.google.devtools.build.lib.analysis.testing.RuleClassSubject.assertThat;
import static com.google.devtools.build.lib.analysis.testing.StarlarkDefinedAspectSubject.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.config.transitions.NoConfigTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule;
import com.google.devtools.build.lib.analysis.starlark.StarlarkConfig;
import com.google.devtools.build.lib.analysis.starlark.StarlarkGlobalsImpl;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleClassFunctions.MacroFunction;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleClassFunctions.StarlarkRuleFunction;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.TestAspects;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.BazelModuleKey;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.packages.AdvertisedProviderSet;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.MacroClass;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PredicateWithMessage;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RequiredProviders;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.StarlarkAspectClass;
import com.google.devtools.build.lib.packages.StarlarkDefinedAspect;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping;
import com.google.devtools.build.lib.starlark.util.BazelEvaluationTestCase;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.Structure;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

/** Tests for StarlarkRuleClassFunctions. */
@RunWith(TestParameterInjector.class)
public final class StarlarkRuleClassFunctionsTest extends BuildViewTestCase {

  private final BazelEvaluationTestCase ev = new BazelEvaluationTestCase();

  private StarlarkRuleContext createRuleContext(String label) throws Exception {
    return new StarlarkRuleContext(getRuleContextForStarlark(getConfiguredTarget(label)), null);
  }

  @Override
  protected void setBuildLanguageOptions(String... options) throws Exception {
    super.setBuildLanguageOptions(options); // for BuildViewTestCase
    ev.setSemantics(options); // for StarlarkThread
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addBzlToplevel(
        "parametrized_native_aspect",
        TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER);
    builder.addNativeAspectClass(TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER);
    return builder.build();
  }

  @org.junit.Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void createBuildFile() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        genrule(
            name = "foo",
            srcs = [
                "a.txt",
                "b.img",
            ],
            outs = ["c.txt"],
            cmd = "dummy_cmd",
            tools = ["t.exe"],
        )

        genrule(
            name = "bar",
            srcs = [
                ":jl",
                ":gl",
            ],
            outs = ["d.txt"],
            cmd = "dummy_cmd",
        )

        java_library(
            name = "jl",
            srcs = ["a.java"],
        )

        genrule(
            name = "gl",
            srcs = ["a.go"],
            outs = [
                "gl.a",
                "gl.gcgox",
            ],
            cmd = "touch $(OUTS)",
            output_to_bindir = 1,
        )
        """);
  }

  @Test
  public void testCannotOverrideBuiltInAttribute() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def impl(ctx):", //
        "  return",
        "r = rule(impl, attrs = {'tags': attr.string_list()})");
    ev.assertContainsError(
        "Error in rule: attribute `tags`: built-in attributes cannot be overridden.");
  }

  @Test
  public void testCannotOverrideBuiltInAttributeName() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def impl(ctx):", //
        "  return",
        "r = rule(impl, attrs = {'name': attr.string()})");
    ev.assertContainsError(
        "Error in rule: attribute `name`: built-in attributes cannot be overridden.");
  }

  @Test
  public void builtInAttributesAreNotStarlarkDefined() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def impl(ctx):", //
        "  return",
        "r = rule(impl, attrs = {'a': attr.string(), 'b': attr.label()})");
    Stream<Attribute> builtInAttributes =
        getRuleClass("r").getAttributes().stream()
            .filter(attr -> !(attr.getName().equals("a") || attr.getName().equals("b")));
    assertThat(builtInAttributes.map(Attribute::starlarkDefined)).doesNotContain(true);
  }

  @Test
  public void testImplicitArgsAttribute() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def _impl(ctx):",
        "  pass",
        "exec_rule = rule(implementation = _impl, executable = True)",
        "non_exec_rule = rule(implementation = _impl)");
    assertThat(getRuleClass("exec_rule").hasAttr("args", Types.STRING_LIST)).isTrue();
    assertThat(getRuleClass("non_exec_rule").hasAttr("args", Types.STRING_LIST)).isFalse();
  }

  /**
   * Returns a package by the given name (no leading "//"), or null upon {@link
   * NoSuchPackageException}.
   */
  @CanIgnoreReturnValue
  @Nullable
  private Package getPackage(String pkgName) throws InterruptedException {
    try {
      return getPackageManager().getPackage(reporter, PackageIdentifier.createInMainRepo(pkgName));
    } catch (NoSuchPackageException unused) {
      return null;
    }
  }

  private void assertPackageNotInError(@Nullable Package pkg) {
    assertThat(pkg).isNotNull();
    assertThat(pkg.containsErrors()).isFalse();
  }

  @Test
  public void testSymbolicMacro_failsWithoutFlag() throws Exception {
    setBuildLanguageOptions("--experimental_enable_first_class_macros=false");

    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg).isNull();
    assertContainsEvent("requires --experimental_enable_first_class_macros");
  }

  @Test
  public void testSymbolicMacro_instantiationRegistersOnPackage() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="ghi")  # alphabetized when read back
        my_macro(name="abc")
        my_macro(name="def")
        """);

    Package pkg = getPackage("pkg");
    assertThat(pkg.getMacrosById().keySet()).containsExactly("abc:1", "def:1", "ghi:1").inOrder();
    assertThat(pkg.getMacrosById().get("abc:1").getMacroClass().getName()).isEqualTo("my_macro");
  }

  @Test
  public void testSymbolicMacro_instantiationRequiresExport() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        s = struct(m = macro(implementation=_impl))
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "s")
        s.m(name="abc")
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg).isNotNull();
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent("Cannot instantiate a macro that has not been exported");
  }

  @Test
  public void testSymbolicMacro_cannotInstantiateInBzlThread() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)

        # Calling it from a function during .bzl load time is a little more interesting than
        # calling it directly at the top level, since it forces us to check thread state rather
        # than call stack state.
        def some_func():
            my_macro(name="nope")
        some_func()
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg).isNull();
    assertContainsEvent("Cannot instantiate a macro when loading a .bzl file");
  }

  @Test
  public void testSymbolicMacro_requiresNameAttribute() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro()
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg).isNotNull();
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent("missing value for mandatory attribute 'name' in 'my_macro' macro");
  }

  @Test
  public void testSymbolicMacro_prohibitsPositionalArgs() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation=_impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro("a positional arg", name = "abc")
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg).isNotNull();
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent("unexpected positional arguments");
  }

  @Test
  public void testSymbolicMacroCanAcceptAttributes() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, target_suffix):
            native.cc_library(name = name + "_" + target_suffix)
        my_macro = macro(
            implementation=_impl,
            attrs = {
                "target_suffix": attr.string(configurable=False),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            target_suffix = "xyz"
        )
        """);

    Package pkg = getPackage("pkg");
    assertPackageNotInError(pkg);
    assertThat(pkg.getTargets()).containsKey("abc_xyz");
  }

  @Test
  public void testSymbolicMacro_rejectsUnknownAttribute() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "xzz": attr.string(doc="This attr is public"),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = "UNKNOWN",
        )
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg).isNotNull();
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent("no such attribute 'xyz' in 'my_macro' macro (did you mean 'xzz'?)");
  }

  @Test
  public void testSymbolicMacro_rejectsReservedAttributeName() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "visibility": attr.string(),
            },
        )
        """);

    ev.assertContainsError("Cannot declare a macro attribute named 'visibility'");
  }

  @Test
  public void testSymbolicMacro_requiresMandatoryAttribute() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "xyz": attr.string(mandatory=True),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg).isNotNull();
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent("missing value for mandatory attribute 'xyz' in 'my_macro' macro");
  }

  @Test
  public void testSymbolicMacro_cannotOverrideImplicitAttribute() throws Exception {
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(name, visibility, _xyz):
            print("_xyz is %s" % _xyz)
        my_macro = macro(
            implementation=_impl,
            attrs = {
              "_xyz": attr.string(default="IMPLICIT")
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            _xyz = "CAN'T SET THIS",
        )
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent("cannot set value of implicit attribute '_xyz'");
  }

  @Test
  public void testSymbolicMacro_doesNotSupportComputedDefaults() throws Exception {
    ev.checkEvalErrorContains(
        "In macro attribute 'xyz': Macros do not support computed defaults or late-bound defaults",
        """
        def _impl(name, visibility, xyz): pass
        def _computed_default(): return "DEFAULT"
        my_macro = macro(
            implementation=_impl,
            attrs = {
              "xyz": attr.label(default=_computed_default)
            },
        )
        """);
  }

  @Test
  public void testSymbolicMacro_doesNotSupportLateBoundDefaults() throws Exception {
    // We need to ensure there's a fragment registered on the BazelEvaluationTestCase for
    // `configuration_field()` to retrieve.
    //
    // (Ordinarily we would use the BuildViewTestCase machinery (scratch + getPackage()) and rely on
    // the analysis mock to register the fragment. But since our expected failure occurs during
    // .bzl loading, our test machinery doesn't process the error correctly, and instead
    // getPackage() returns null and no events are emitted.)
    ev.setFragmentNameToClass(ImmutableMap.of("cpp", CppConfiguration.class));

    ev.checkEvalErrorContains(
        "In macro attribute 'xyz': Macros do not support computed defaults or late-bound defaults",
        """
        def _impl(name, visibility, xyz): pass
        _latebound_default = configuration_field(fragment = "cpp", name = "cc_toolchain")
        my_macro = macro(
            implementation=_impl,
            attrs = {
              "xyz": attr.label(default=_latebound_default)
            },
        )
        """);
  }

  @Test
  public void testSymbolicMacro_macroFunctionApi() throws Exception {
    evalAndExport(
        ev,
        """
        def _impl(name, visibility):
            pass
        exported = macro(
            implementation=_impl,
            doc = "Exported macro",
            attrs = {
                "abc": attr.int(),
                "xyz": attr.string(),
            }
        )
        s = struct(
            unexported = macro(
                implementation=_impl,
                doc = "Unexported macro",
                attrs = {
                    "abc": attr.int(),
                    "xyz": attr.string(),
                }
            ),
        )
        """);

    MacroFunction exported = (MacroFunction) ev.lookup("exported");
    MacroFunction unexported = (MacroFunction) ev.eval("s.unexported");

    assertThat(exported.getName()).isEqualTo("exported");
    assertThat(unexported.getName()).isEqualTo("unexported macro");

    assertThat(exported.isExported()).isTrue();
    assertThat(unexported.isExported()).isFalse();

    assertThat(ev.eval("repr(exported)")).isEqualTo("<macro exported>");
    assertThat(ev.eval("repr(s.unexported)")).isEqualTo("<macro>");

    assertThat(exported.getDocumentation()).hasValue("Exported macro");
    assertThat(unexported.getDocumentation()).hasValue("Unexported macro");

    assertThat(exported.getExtensionLabel()).isEqualTo(FAKE_LABEL);
    assertThat(unexported.getExtensionLabel()).isNull();

    assertThat(exported.getMacroClass().getName()).isEqualTo("exported");
    assertThat(exported.getMacroClass().getAttributes())
        .containsExactly(
            "name",
            RuleClass.NAME_ATTRIBUTE,
            "visibility",
            MacroClass.VISIBILITY_ATTRIBUTE,
            "abc",
            Attribute.attr("abc", Type.INTEGER).starlarkDefined().build(),
            "xyz",
            Attribute.attr("xyz", Type.STRING).starlarkDefined().build());
    assertThat(unexported.getMacroClass()).isNull();
  }

  private RuleClass getRuleClass(String name) throws Exception {
    return ((StarlarkRuleFunction) ev.lookup(name)).getRuleClass();
  }

  private void registerDummyStarlarkFunction() throws Exception {
    ev.exec("def impl():", "  pass");
  }

  @Test
  public void testAttrWithOnlyType() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list()");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getType()).isEqualTo(Types.STRING_LIST);
  }

  private Attribute buildAttribute(String name, String... lines) throws Exception {
    String[] strings = lines.clone();
    strings[strings.length - 1] = String.format("%s = %s", name, strings[strings.length - 1]);
    evalAndExport(ev, strings);
    StarlarkAttrModule.Descriptor lookup = (StarlarkAttrModule.Descriptor) ev.lookup(name);
    return lookup != null ? lookup.build(name) : null;
  }

  @Test
  public void testOutputListAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.output_list()");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getType()).isEqualTo(BuildType.OUTPUT_LIST);
  }

  @Test
  public void testIntListAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.int_list()");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getType()).isEqualTo(Types.INTEGER_LIST);
  }

  @Test
  public void testOutputAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.output()");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getType()).isEqualTo(BuildType.OUTPUT);
  }

  @Test
  public void testStringDictAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_dict(default = {'a': 'b'})");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getType()).isEqualTo(Types.STRING_DICT);
  }

  @Test
  public void testStringListDictAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list_dict(default = {'a': ['b', 'c']})");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getType()).isEqualTo(Types.STRING_LIST_DICT);
  }

  @Test
  public void testAttrAllowedFileTypesAnyFile() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label_list(allow_files = True)");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate()).isEqualTo(FileTypeSet.ANY_FILE);
  }

  @Test
  public void testAttrAllowedFileTypesWrongType() throws Exception {
    ev.checkEvalErrorContains(
        "got value of type 'int', want 'bool, sequence, or NoneType'",
        "attr.label_list(allow_files = 18)");
  }

  @Test
  public void testAttrNameSpecialCharactersAreForbidden() throws Exception {
    ev.setFailFast(false);
    evalAndExport(ev, "def impl(ctx): return", "r = rule(impl, attrs = {'ab$c': attr.int()})");
    ev.assertContainsError("attribute name `ab$c` is not a valid identifier");
  }

  @Test
  public void testAttrNameCannotStartWithDigit() throws Exception {
    ev.setFailFast(false);
    evalAndExport(ev, "def impl(ctx): return", "r = rule(impl, attrs = {'2_foo': attr.int()})");
    ev.assertContainsError("attribute name `2_foo` is not a valid identifier");
  }

  @Test
  public void testAttrEquality() throws Exception {
    new EqualsTester()
        .addEqualityGroup(
            buildAttribute("foo", "attr.string_list(default = [])"),
            buildAttribute("foo", "attr.string_list(default = [])"))
        .addEqualityGroup(
            buildAttribute("bar", "attr.string_list(default = [])"),
            buildAttribute("bar", "attr.string_list(default = [])"))
        .addEqualityGroup(
            buildAttribute("bar", "attr.label_list(default = [])"),
            buildAttribute("bar", "attr.label_list(default = [])"))
        .addEqualityGroup(
            buildAttribute("foo", "attr.string_list(default = ['hello'])"),
            buildAttribute("foo", "attr.string_list(default = ['hello'])"))
        .addEqualityGroup(
            buildAttribute("foo", "attr.string_list(doc = 'Blah blah blah', default = [])"),
            buildAttribute("foo", "attr.string_list(doc = 'Blah blah blah', default = [])"))
        .addEqualityGroup(
            buildAttribute("foo", "attr.string_list(mandatory = True, default = [])"),
            buildAttribute("foo", "attr.string_list(mandatory = True, default = [])"))
        .addEqualityGroup(
            buildAttribute("foo", "attr.string_list(allow_empty = False, default = [])"),
            buildAttribute("foo", "attr.string_list(allow_empty = False, default = [])"))
        .testEquals();
  }

  @Test
  public void testRuleClassTooManyAttributes() throws Exception {
    ev.setFailFast(false);

    ImmutableList.Builder<String> linesBuilder =
        ImmutableList.<String>builder()
            .add("def impl(ctx): return")
            .add("r = rule(impl, attrs = {");
    for (int i = 0; i < 200; i++) {
      linesBuilder.add("    'attr" + i + "': attr.int(),");
    }
    linesBuilder.add("})");

    evalAndExport(ev, linesBuilder.build().toArray(new String[0]));

    assertThat(ev.getEventCollector()).hasSize(1);
    Event event = ev.getEventCollector().iterator().next();
    assertThat(event.getKind()).isEqualTo(EventKind.ERROR);
    assertThat(event.getMessage()).contains("Rule class r declared too many attributes");
  }

  @Test
  public void testRuleClassTooLongAttributeName() throws Exception {
    ev.setFailFast(false);

    evalAndExport(
        ev,
        "def impl(ctx): return;",
        "r = rule(impl, attrs = { '" + "x".repeat(150) + "': attr.int() })");

    assertThat(ev.getEventCollector()).hasSize(1);
    Event event = ev.getEventCollector().iterator().next();
    assertThat(event.getKind()).isEqualTo(EventKind.ERROR);
    assertThat(event.getLocation().toString()).isEqualTo(":2:9");
    assertThat(event.getMessage())
        .matches("Attribute r\\.x{150}'s name is too long \\(150 > 128\\)");
  }

  @Test
  public void testAttrAllowedSingleFileTypesWrongType() throws Exception {
    ev.checkEvalErrorContains(
        "allow_single_file should be a boolean or a string list",
        "attr.label(allow_single_file = 18)");
  }

  @Test
  public void testAttrWithList() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label_list(allow_files = ['.xml'])");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.xml")).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.txt")).isFalse();
    assertThat(attr.isSingleArtifact()).isFalse();
  }

  @Test
  public void testAttrSingleFileWithList() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(allow_single_file = ['.xml'])");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.xml")).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.txt")).isFalse();
    assertThat(attr.isSingleArtifact()).isTrue();
  }

  @Test
  public void testRuleCannotSetConfigurableOnAttr() throws Exception {
    scratch.file("lib/BUILD");
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(ctx):
            print("xyz is %s" % ctx.attr.xyz)
        my_rule = rule(
            implementation=_impl,
            attrs = {
              "xyz": attr.string(configurable=False),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_rule")
        my_rule(
            name = "abc",
            xyz = select({"//some:condition": ":target1", "//some:other_condition": ":target2"}),
        )
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg).isNull();
    assertContainsEvent(
        "attribute 'xyz' has the 'configurable' argument set, which is not allowed in"
            + " rule definitions");
  }

  @Test
  public void testAspectCannotSetConfigurableOnAttr() throws Exception {
    scratch.file("lib/BUILD");
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(ctx):
            print("xyz is %s" % ctx.attr.xyz)
        my_aspect = aspect(
            implementation=_impl,
            attrs = {
              "xyz": attr.string(configurable=False),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_aspect")
        my_aspect(
            name = "abc",
            xyz = select({"//some:condition": ":target1", "//some:other_condition": ":target2"}),
        )
        """);

    reporter.removeHandler(failFastHandler);
    Package pkg = getPackage("pkg");
    assertThat(pkg).isNull();
    assertContainsEvent(
        "attribute 'xyz' has the 'configurable' argument set, which is not allowed in aspect"
            + " definitions");
  }

  private static StarlarkProviderIdentifier legacy(String legacyId) {
    return StarlarkProviderIdentifier.forLegacy(legacyId);
  }

  private static StarlarkProviderIdentifier declared(String exportedName) {
    return StarlarkProviderIdentifier.forKey(
        new StarlarkProvider.Key(keyForBuild(FAKE_LABEL), exportedName));
  }

  @Test
  public void testAttrWithProviders() throws Exception {
    Attribute attr =
        buildAttribute(
            "a1", //
            "b = provider()",
            "attr.label_list(allow_files = True, providers = ['a', b])");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a"), declared("b")))).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a")))).isFalse();
  }

  @Test
  public void testAttrWithProvidersOneEmpty() throws Exception {
    Attribute attr =
        buildAttribute(
            "a1",
            "b = provider()",
            "attr.label_list(allow_files = True, providers = [['a', b],[]])");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getRequiredProviders().acceptsAny()).isTrue();
  }

  @Test
  public void testAttrWithProvidersList() throws Exception {
    Attribute attr =
        buildAttribute(
            "a1",
            "b = provider()",
            "attr.label_list(allow_files = True, providers = [['a', b], ['c']])");
    assertThat(attr.starlarkDefined()).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a"), declared("b")))).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("c")))).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a")))).isFalse();
  }

  private static AdvertisedProviderSet set(StarlarkProviderIdentifier... ids) {
    AdvertisedProviderSet.Builder builder = AdvertisedProviderSet.builder();
    for (StarlarkProviderIdentifier id : ids) {
      builder.addStarlark(id);
    }
    return builder.build();
  }

  private void checkAttributeError(String expectedMessage, String... lines) throws Exception {
    ev.setFailFast(false);
    buildAttribute("fakeAttribute", lines);
    MoreAsserts.assertContainsEvent(ev.getEventCollector(), expectedMessage);
  }

  @Test
  public void testAttrWithWrongProvidersList() throws Exception {
    checkAttributeError(
        "element in 'providers' is of unexpected type. Either all elements should be providers,"
            + " or all elements should be lists of providers,"
            + " but got list with an element of type int.",
        "attr.label_list(allow_files = True,  providers = [['a', 1], ['c']])");

    checkAttributeError(
        "element in 'providers' is of unexpected type. Either all elements should be providers,"
            + " or all elements should be lists of providers,"
            + " but got an element of type string.",
        "b = provider()",
        "attr.label_list(allow_files = True,  providers = [['a', b], 'c'])");

    checkAttributeError(
        "element in 'providers' is of unexpected type. Either all elements should be providers,"
            + " or all elements should be lists of providers,"
            + " but got an element of type string.",
        "c = provider()",
        "attr.label_list(allow_files = True,  providers = [['a', b], c])");
  }

  @Test
  public void testLabelListWithAspects() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(implementation = _impl)",
        "a = attr.label_list(aspects = [my_aspect])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(aspect).isNotNull();
    assertThat(attr.build("xxx").getAspectClasses()).containsExactly(aspect.getAspectClass());
  }

  @Test
  public void testLabelWithAspects() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(implementation = _impl)",
        "a = attr.label(aspects = [my_aspect])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(aspect).isNotNull();
    assertThat(attr.build("xxx").getAspectClasses()).containsExactly(aspect.getAspectClass());
  }

  @Test
  public void testLabelListWithAspectsError() throws Exception {
    ev.setThreadOwner(keyForBuild(FAKE_LABEL));
    ev.checkEvalErrorContains(
        "at index 1 of aspects, got element of type int, want Aspect",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(implementation = _impl)",
        "attr.label_list(aspects = [my_aspect, 123])");
  }

  @Test
  public void testAttrWithAspectRequiringAspects_stackOfRequiredAspects() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_c = aspect(implementation = _impl)",
        "aspect_b = aspect(implementation = _impl, requires = [aspect_c])",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_b])",
        "a = attr.label_list(aspects = [aspect_a])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");

    StarlarkDefinedAspect aspectA = (StarlarkDefinedAspect) ev.lookup("aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkDefinedAspect aspectB = (StarlarkDefinedAspect) ev.lookup("aspect_b");
    assertThat(aspectB).isNotNull();
    StarlarkDefinedAspect aspectC = (StarlarkDefinedAspect) ev.lookup("aspect_c");
    assertThat(aspectC).isNotNull();
    List<AspectClass> expectedAspects =
        Arrays.asList(aspectA.getAspectClass(), aspectB.getAspectClass(), aspectC.getAspectClass());
    assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects);
  }

  @Test
  public void testAttrWithAspectRequiringAspects_aspectRequiredByMultipleAspects()
      throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_c = aspect(implementation = _impl)",
        "aspect_b = aspect(implementation = _impl, requires = [aspect_c])",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_c])",
        "a = attr.label_list(aspects = [aspect_a, aspect_b])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");

    StarlarkDefinedAspect aspectA = (StarlarkDefinedAspect) ev.lookup("aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkDefinedAspect aspectB = (StarlarkDefinedAspect) ev.lookup("aspect_b");
    assertThat(aspectB).isNotNull();
    StarlarkDefinedAspect aspectC = (StarlarkDefinedAspect) ev.lookup("aspect_c");
    assertThat(aspectC).isNotNull();
    List<AspectClass> expectedAspects =
        Arrays.asList(aspectA.getAspectClass(), aspectB.getAspectClass(), aspectC.getAspectClass());
    assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects);
  }

  @Test
  public void testAttrWithAspectRequiringAspects_aspectRequiredByMultipleAspects2()
      throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_d = aspect(implementation = _impl)",
        "aspect_c = aspect(implementation = _impl, requires = [aspect_d])",
        "aspect_b = aspect(implementation = _impl, requires = [aspect_d])",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_b, aspect_c])",
        "a = attr.label_list(aspects = [aspect_a])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");

    StarlarkDefinedAspect aspectA = (StarlarkDefinedAspect) ev.lookup("aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkDefinedAspect aspectB = (StarlarkDefinedAspect) ev.lookup("aspect_b");
    assertThat(aspectB).isNotNull();
    StarlarkDefinedAspect aspectC = (StarlarkDefinedAspect) ev.lookup("aspect_c");
    assertThat(aspectC).isNotNull();
    StarlarkDefinedAspect aspectD = (StarlarkDefinedAspect) ev.lookup("aspect_d");
    assertThat(aspectD).isNotNull();
    List<AspectClass> expectedAspects =
        Arrays.asList(
            aspectA.getAspectClass(),
            aspectB.getAspectClass(),
            aspectC.getAspectClass(),
            aspectD.getAspectClass());
    assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects);
  }

  @Test
  public void testAttrWithAspectRequiringAspects_requireExistingAspect_passed() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_b = aspect(implementation = _impl)",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_b])",
        "a = attr.label_list(aspects = [aspect_b, aspect_a])");
    StarlarkAttrModule.Descriptor attr = (StarlarkAttrModule.Descriptor) ev.lookup("a");

    StarlarkDefinedAspect aspectA = (StarlarkDefinedAspect) ev.lookup("aspect_a");
    assertThat(aspectA).isNotNull();
    StarlarkDefinedAspect aspectB = (StarlarkDefinedAspect) ev.lookup("aspect_b");
    assertThat(aspectB).isNotNull();
    List<AspectClass> expectedAspects =
        Arrays.asList(aspectA.getAspectClass(), aspectB.getAspectClass());
    assertThat(attr.build("xxx").getAspectClasses()).containsExactlyElementsIn(expectedAspects);
  }

  @Test
  public void testAttrWithAspectRequiringAspects_requireExistingAspect_failed() throws Exception {
    ev.setFailFast(false);

    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "aspect_b = aspect(implementation = _impl)",
        "aspect_a = aspect(implementation = _impl, requires = [aspect_b])",
        "attr.label_list(aspects = [aspect_a, aspect_b])");

    ev.assertContainsError(
        String.format(
            "aspect %s%%aspect_b was added before as a required aspect of aspect %s%%aspect_a",
            FAKE_LABEL, FAKE_LABEL));
  }

  @Test
  public void testAspectExtraDeps() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { '_extra_deps' : attr.label(default = Label('//foo/bar:baz')) }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("$extra_deps");
    assertThat(attribute.getDefaultValue(null))
        .isEqualTo(Label.parseCanonicalUnchecked("//foo/bar:baz"));
  }

  @Test
  public void testAspectParameter() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(values=['a', 'b']) }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("param");
  }

  @Test
  public void testAspectParameterWithDefaultValue() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(default = 'a', values=['a', 'b']) }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("param");
    assertThat(((String) attribute.getDefaultValueUnchecked())).isEqualTo("a");
  }

  @Test
  public void testAspectParameterBadDefaultValue() throws Exception {
    ev.checkEvalErrorContains(
        "Aspect parameter attribute 'param' has a bad default value: has to be"
            + " one of 'b' instead of 'a'",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(default = 'a', values = ['b']) }",
        ")");
  }

  @Test
  public void testAspectParameterNotRequireValues() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(default = 'val') }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("param");
    assertThat(((String) attribute.getDefaultValueUnchecked())).isEqualTo("val");
  }

  @Test
  public void testAspectParameterBadType() throws Exception {
    ev.checkEvalErrorContains(
        "Aspect parameter attribute 'param' must have type 'bool', 'int' or 'string'.",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.label(default = Label('//foo/bar:baz')) }",
        ")");
  }

  @Test
  public void testAspectParameterAndExtraDeps() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(values=['a', 'b']),",
        "             '_extra' : attr.label(default = Label('//foo/bar:baz')) }",
        ")");
    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(aspect.getAttributes()).hasSize(2);
    assertThat(aspect.getParamAttributes()).containsExactly("param");
  }

  @Test
  public void testAspectNoDefaultValueAttribute() throws Exception {
    ev.checkEvalErrorContains(
        "Aspect attribute '_extra_deps' has no default value",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { '_extra_deps' : attr.label() }",
        ")");
  }

  @Test
  public void testAspectAddToolchain() throws Exception {
    evalAndExport(
        ev,
        "def _impl(ctx): pass",
        "a1 = aspect(_impl,",
        "    toolchains=[",
        "        '//test:my_toolchain_type1',",
        "        config_common.toolchain_type('//test:my_toolchain_type2'),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
        "        config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
        "    ],",
        ")");
    StarlarkDefinedAspect a = (StarlarkDefinedAspect) ev.lookup("a1");
    assertThat(a).hasToolchainType("//test:my_toolchain_type1");
    assertThat(a).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(a).hasToolchainType("//test:my_toolchain_type2");
    assertThat(a).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(a).hasToolchainType("//test:my_toolchain_type3");
    assertThat(a).toolchainType("//test:my_toolchain_type3").isOptional();
    assertThat(a).hasToolchainType("//test:my_toolchain_type4");
    assertThat(a).toolchainType("//test:my_toolchain_type4").isMandatory();
  }

  @Test
  public void testNonLabelAttrWithProviders() throws Exception {
    ev.checkEvalErrorContains(
        "unexpected keyword argument 'providers'", "attr.string(providers = ['a'])");
  }

  private static final RuleClass.ConfiguredTargetFactory<Object, Object, Exception>
      DUMMY_CONFIGURED_TARGET_FACTORY =
          ruleContext -> {
            throw new IllegalStateException();
          };

  private static RuleClass ruleClass(String name) {
    return new RuleClass.Builder(name, RuleClassType.NORMAL, false)
        .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
        .add(Attribute.attr("tags", Types.STRING_LIST))
        .build();
  }

  @Test
  public void testAttrAllowedRuleClassesSpecificRuleClasses() throws Exception {
    Attribute attr =
        buildAttribute(
            "a", //
            "attr.label_list(allow_rules = ['java_binary'], allow_files = True)");
    assertThat(attr.getAllowedRuleClassObjectPredicate().apply(ruleClass("java_binary"))).isTrue();
    assertThat(attr.getAllowedRuleClassObjectPredicate().apply(ruleClass("genrule"))).isFalse();
  }

  @Test
  public void testAttrDefaultValue() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string(default = 'some value')");
    assertThat(attr.getDefaultValueUnchecked()).isEqualTo("some value");
  }

  @Test
  public void testLabelAttrDefaultValueAsString() throws Exception {
    Attribute sligleAttr = buildAttribute("a1", "attr.label(default = '//foo:bar')");
    assertThat(sligleAttr.getDefaultValueUnchecked())
        .isEqualTo(Label.parseCanonicalUnchecked("//foo:bar"));

    Attribute listAttr =
        buildAttribute("a2", "attr.label_list(default = ['//foo:bar', '//bar:foo'])");
    assertThat(listAttr.getDefaultValueUnchecked())
        .isEqualTo(
            ImmutableList.of(
                Label.parseCanonicalUnchecked("//foo:bar"),
                Label.parseCanonicalUnchecked("//bar:foo")));

    Attribute dictAttr =
        buildAttribute("a3", "attr.label_keyed_string_dict(default = {'//foo:bar': 'my value'})");
    assertThat(dictAttr.getDefaultValueUnchecked())
        .isEqualTo(ImmutableMap.of(Label.parseCanonicalUnchecked("//foo:bar"), "my value"));
  }

  @Test
  public void testLabelAttrDefaultValueAsStringBadValue() throws Exception {
    ev.checkEvalErrorContains(
        "invalid label '/foo:bar' in parameter 'default' of attribute 'label': "
            + "invalid package name '/foo': package names may not start with '/'",
        "attr.label(default = '/foo:bar')");

    ev.checkEvalErrorContains(
        "invalid label '/bar:foo' in element 1 of parameter 'default' of attribute "
            + "'label_list': invalid package name '/bar': package names may not start with '/'",
        "attr.label_list(default = ['//foo:bar', '/bar:foo'])");

    ev.checkEvalErrorContains(
        "invalid label '/bar:foo' in dict key element: invalid package name '/bar': "
            + "package names may not start with '/'",
        "attr.label_keyed_string_dict(default = {'//foo:bar': 'a', '/bar:foo': 'b'})");
  }

  @Test
  public void testAttrDefaultValueBadType() throws Exception {
    ev.checkEvalErrorContains("got value of type 'int', want 'string'", "attr.string(default = 1)");
  }

  @Test
  public void testAttrMandatory() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string(mandatory=True)");
    assertThat(attr.isMandatory()).isTrue();
    assertThat(attr.isNonEmpty()).isFalse();
  }

  @Test
  public void testAttrAllowEmpty() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list(allow_empty=False)");
    assertThat(attr.isNonEmpty()).isTrue();
    assertThat(attr.isMandatory()).isFalse();
  }

  @Test
  public void testAttrBadKeywordArguments() throws Exception {
    ev.checkEvalErrorContains(
        "string() got unexpected keyword argument 'bad_keyword'", "attr.string(bad_keyword = '')");
  }

  @Test
  public void testAttrCfgHostDisabled() throws Exception {
    setBuildLanguageOptions("--incompatible_disable_starlark_host_transitions");

    EvalException ex = assertThrows(EvalException.class, () -> ev.eval("attr.label(cfg = 'host')"));
    assertThat(ex).hasMessageThat().contains("Please use 'cfg = \"exec\"' instead");
  }

  @Test
  public void testAttrCfgTarget_string() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(cfg = 'target', allow_files = True)");
    assertThat(NoTransition.isInstance(attr.getTransitionFactory())).isTrue();
  }

  @Test
  public void testAttrCfgTarget_object() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(cfg = config.target(), allow_files = True)");
    assertThat(NoTransition.isInstance(attr.getTransitionFactory())).isTrue();
  }

  @Test
  public void testAttrCfgNone() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(cfg = config.none(), allow_files = True)");
    assertThat(NoConfigTransition.isInstance(attr.getTransitionFactory())).isTrue();
  }

  private void writeRuleCfgTestRule(String cfg) throws Exception {
    scratch.file(
        "rule_testing/rule.bzl",
        """
        def _impl(ctx):
            pass

        demo_rule = rule(
            implementation = _impl,
            cfg = %s,
        )
        """
            .formatted(cfg));
    scratch.file(
        "rule_testing/BUILD",
        """
        load(":rule.bzl", "demo_rule")

        demo_rule(name = "my_target")
        """);
  }

  @Test
  public void testRuleCfg_exec_string_fails() throws Exception {
    writeRuleCfgTestRule("'exec'");

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//rule_testing:my_target");

    ev.assertContainsError("`cfg` must be set to a transition object");
  }

  @Test
  public void testRuleCfg_exec_obj_fails() throws Exception {
    writeRuleCfgTestRule("config.exec()");

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//rule_testing:my_target");

    ev.assertContainsError("`cfg` must be set to a transition object");
  }

  @Test
  public void testRuleCfg_starlark() throws Exception {
    scratchStarlarkTransition();
    scratch.file(
        "rule_testing/rule.bzl",
        """
        load("//test:transitions.bzl", "parent_transition")

        def _impl(ctx):
            pass

        demo_rule = rule(
            implementation = _impl,
            cfg = parent_transition,
        )
        """);
    scratch.file(
        "rule_testing/BUILD",
        """
        load(":rule.bzl", "demo_rule")

        demo_rule(name = "my_target")
        """);

    BuildConfigurationValue configuration =
        getConfiguration(getConfiguredTarget("//rule_testing:my_target"));

    var options = configuration.getOptions().getStarlarkOptions();
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-flag")))
        .isEqualTo("parent-changed");
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-child-flag")))
        .isEqualTo("parent-child-changed-in-parent");
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:child-flag"))).isNull();
  }

  @Test
  public void incompatibleDataTransition() {
    EvalException expected =
        assertThrows(EvalException.class, () -> ev.eval("attr.label(cfg = 'data')"));
    assertThat(expected).hasMessageThat().contains("cfg must be either 'target', 'exec'");
  }

  @Test
  public void testAttrValues() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string(values = ['ab', 'cd'])");
    PredicateWithMessage<Object> predicate = attr.getAllowedValues();
    assertThat(predicate.apply("ab")).isTrue();
    assertThat(predicate.apply("xy")).isFalse();
  }

  @Test
  public void testAttrIntValues() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.int(values = [1, 2])");
    PredicateWithMessage<Object> predicate = attr.getAllowedValues();
    assertThat(predicate.apply(StarlarkInt.of(2))).isTrue();
    assertThat(predicate.apply(StarlarkInt.of(3))).isFalse();
  }

  @Test
  public void testAttrDoc(
      @TestParameter({
            "bool",
            "int",
            "int_list",
            "label",
            "label_keyed_string_dict",
            "label_list",
            "output",
            "output_list",
            "string",
            "string_dict",
            "string_list",
            "string_list_dict"
          })
          String attrType)
      throws Exception {
    Attribute documented =
        buildAttribute("documented", String.format("attr.%s(doc='foo')", attrType));
    assertThat(documented.getDoc()).isEqualTo("foo");
    Attribute documentedNeedingDedent =
        buildAttribute(
            "documented",
            String.format("attr.%s(doc='''foo\n\n    More details.\n    ''')", attrType));
    assertThat(documentedNeedingDedent.getDoc()).isEqualTo("foo\n\nMore details.");
    Attribute undocumented = buildAttribute("undocumented", String.format("attr.%s()", attrType));
    assertThat(undocumented.getDoc()).isNull();
  }

  @Test
  public void testNoAttrLicense() {
    EvalException expected = assertThrows(EvalException.class, () -> ev.eval("attr.license()"));
    assertThat(expected).hasMessageThat().contains("'attr' value has no field or method 'license'");
  }

  @Test
  public void testAttrDocValueBadType() throws Exception {
    ev.checkEvalErrorContains(
        "got value of type 'int', want 'string or NoneType'", "attr.string(doc = 1)");
  }

  @Test
  public void testRuleImplementation() throws Exception {
    evalAndExport(ev, "def impl(ctx): return None", "rule1 = rule(impl)");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("rule1")).getRuleClass();
    assertThat(c.getConfiguredTargetFunction().getName()).isEqualTo("impl");
  }

  @Test
  public void testRuleDoc() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx):",
        "    return None",
        "documented_rule = rule(impl, doc = 'My doc string')",
        "long_documented_rule = rule(",
        "    impl,",
        "    doc = '''Long doc",
        "",
        "             With details",
        "''',",
        ")",
        "undocumented_rule = rule(impl)");
    StarlarkRuleFunction documentedRule = (StarlarkRuleFunction) ev.lookup("documented_rule");
    StarlarkRuleFunction longDocumentedRule =
        (StarlarkRuleFunction) ev.lookup("long_documented_rule");
    StarlarkRuleFunction undocumentedRule = (StarlarkRuleFunction) ev.lookup("undocumented_rule");
    assertThat(documentedRule.getRuleClass().getStarlarkDocumentation()).isEqualTo("My doc string");
    assertThat(longDocumentedRule.getRuleClass().getStarlarkDocumentation())
        .isEqualTo("Long doc\n\nWith details");
    assertThat(undocumentedRule.getRuleClass().getStarlarkDocumentation()).isNull();
  }

  @Test
  public void testStarlarkLabelAndName() throws Exception {
    scratch.file(
        "p/rule_meta_constructor.bzl",
        """
        def rule_meta_constructor(impl):
            return rule(impl)
        """);
    scratch.file(
        "p/rule_definition.bzl",
        """
        load("rule_meta_constructor.bzl", "rule_meta_constructor")

        def _impl(ctx):
            pass

        original_rule_symbol = rule_meta_constructor(_impl)
        """);
    scratch.file(
        "p/rule_alias.bzl",
        """
        load("rule_definition.bzl", "original_rule_symbol")

        aliased_rule_symbol = original_rule_symbol
        """);
    scratch.file(
        "p/BUILD",
        """
        load("rule_alias.bzl", "aliased_rule_symbol")

        aliased_rule_symbol(name = "p")
        """);

    RuleClass ruleClass = createRuleContext("//p").getRuleClassUnderEvaluation();
    assertThat(ruleClass.getRuleDefinitionEnvironmentLabel())
        .isEqualTo(Label.parseCanonicalUnchecked("//p:rule_definition.bzl"));
    assertThat(ruleClass.getStarlarkExtensionLabel())
        .isEqualTo(Label.parseCanonicalUnchecked("//p:rule_definition.bzl"));
    assertThat(ruleClass.getName()).isEqualTo("original_rule_symbol");
  }

  @Test
  public void testFunctionAsAttrDefault() throws Exception {
    ev.exec("def f(): pass");

    // Late-bound attributes, which are computed during analysis as a function
    // of the configuration, are only available for attributes involving labels:
    //   attr.label
    //   attr.label_list
    //   attr.label_keyed_string_dict
    //   attr.output,
    //   attr.output_list
    // (See testRuleClassImplicitOutputFunctionDependingOnComputedAttribute
    // for a more detailed positive test.)
    evalAndExport(
        ev,
        "attr.label(default=f)",
        "attr.label_list(default=f)",
        "attr.label_keyed_string_dict(default=f)");
    // For all other attribute types, the default value may not be a function.
    //
    // (This is a regression test for github.com/bazelbuild/bazel/issues/9463.
    // The loading-phase feature of "computed attribute defaults" is not exposed
    // to Starlark; the bug was that the @StarlarkMethod
    // annotation was more permissive than the method declaration.)
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'string'", "attr.string(default=f)");
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'sequence'", "attr.string_list(default=f)");
    ev.checkEvalErrorContains("got value of type 'function', want 'int'", "attr.int(default=f)");
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'sequence'", "attr.int_list(default=f)");
    ev.checkEvalErrorContains("got value of type 'function', want 'bool'", "attr.bool(default=f)");
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'dict'", "attr.string_dict(default=f)");
    ev.checkEvalErrorContains(
        "got value of type 'function', want 'dict'", "attr.string_list_dict(default=f)");
    // Note: attr.license appears to be disabled already.
    // (see --incompatible_no_attr_license)
  }

  private static final Label FAKE_LABEL = Label.parseCanonicalUnchecked("//fake/label.bzl");

  @Test
  public void testRuleAddAttribute() throws Exception {
    evalAndExport(ev, "def impl(ctx): return None", "r1 = rule(impl, attrs={'a1': attr.string()})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.hasAttr("a1", Type.STRING)).isTrue();
  }

  private static void evalAndExport(BazelEvaluationTestCase ev, String... lines) throws Exception {
    ev.setThreadOwner(keyForBuild(FAKE_LABEL));
    ev.execAndExport(FAKE_LABEL, lines);
  }

  @Test
  public void testExportAliasedName() throws Exception {
    // When there are multiple names aliasing the same StarlarkExportable, the first one to be
    // declared should be used. Make sure we're not using lexicographical order, hash order,
    // non-deterministic order, or anything else.
    evalAndExport(
        ev,
        "def _impl(ctx): pass",
        "d = rule(implementation = _impl)",
        "a = d",
        // Having more names improves the chance that non-determinism will be caught.
        "b = d",
        "c = d",
        "e = d",
        "f = d",
        "foo = d",
        "bar = d",
        "baz = d",
        "x = d",
        "y = d",
        "z = d");
    String dName = ((StarlarkRuleFunction) ev.lookup("d")).getRuleClass().getName();
    String fooName = ((StarlarkRuleFunction) ev.lookup("foo")).getRuleClass().getName();
    assertThat(dName).isEqualTo("d");
    assertThat(fooName).isEqualTo("d");
  }

  @Test
  public void testOutputToGenfiles() throws Exception {
    evalAndExport(ev, "def impl(ctx): pass", "r1 = rule(impl, output_to_genfiles=True)");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.outputsToBindir()).isFalse();
  }

  @Test
  public void testRuleAddMultipleAttributes() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl,",
        "     attrs = {",
        "            'a1': attr.label_list(allow_files=True),",
        "            'a2': attr.int()",
        "})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.hasAttr("a1", BuildType.LABEL_LIST)).isTrue();
    assertThat(c.hasAttr("a2", Type.INTEGER)).isTrue();
  }

  @Test
  public void testRuleAttributeFlag() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl, attrs = {'a1': attr.string(mandatory=True)})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.getAttributeByName("a1").isMandatory()).isTrue();
  }

  @Test
  public void unknownRuleAttributeFlags_forbidden() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def _impl(ctx): return None",
        "r1 = rule(_impl, attrs = { 'srcs': attr.label_list(flags = ['NO-SUCH-FLAG']) })");
    ev.assertContainsError("unknown attribute flag 'NO-SUCH-FLAG'");
  }

  @Test
  public void testRuleOutputs() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None", //
        "r1 = rule(impl, outputs = {'a': 'a.txt'})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    ImplicitOutputsFunction function = c.getDefaultImplicitOutputsFunction();
    assertThat(function.getImplicitOutputs(ev.getEventHandler(), null)).containsExactly("a.txt");
  }

  @Test
  public void testRuleUnknownKeyword() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains(
        "unexpected keyword argument 'bad_keyword'", "rule(impl, bad_keyword = 'some text')");
  }

  @Test
  public void testRuleImplementationMissing() throws Exception {
    ev.checkEvalErrorContains(
        "rule() missing 1 required positional argument: implementation", "rule(attrs = {})");
  }

  @Test
  public void testRuleBadTypeForAdd() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains(
        "in call to rule(), parameter 'attrs' got value of type 'string', want 'dict'",
        "rule(impl, attrs = 'some text')");
  }

  @Test
  public void testRuleBadTypeInAdd() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains(
        "got dict<string, string> for 'attrs', want dict<string, Attribute>",
        "rule(impl, attrs = {'a1': 'some text'})");
  }

  @Test
  public void testRuleBadTypeForDoc() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains(
        "got value of type 'int', want 'string or NoneType'", "rule(impl, doc = 1)");
  }

  @Test
  public void testLabel() throws Exception {
    Object result = ev.eval("Label('//foo/foo:foo')");
    assertThat(result).isInstanceOf(Label.class);
    assertThat(result.toString()).isEqualTo("//foo/foo:foo");
  }

  @Test
  public void testLabelIdempotence() throws Exception {
    Object result = ev.eval("Label(Label('//foo/foo:foo'))");
    assertThat(result).isInstanceOf(Label.class);
    assertThat(result.toString()).isEqualTo("//foo/foo:foo");
  }

  @Test
  public void testLabelSameInstance() throws Exception {
    Object l1 = ev.eval("Label('//foo/foo:foo')");
    // Implicitly creates a new pkgContext and environment, yet labels should be the same.
    Object l2 = ev.eval("Label('//foo/foo:foo')");
    assertThat(l1).isSameInstanceAs(l2);
  }

  @Test
  public void testLabelNameAndPackage() throws Exception {
    Object result = ev.eval("Label('//foo/bar:baz').name");
    assertThat(result).isEqualTo("baz");
    // NB: implicitly creates a new pkgContext and environments, yet labels should be the same.
    result = ev.eval("Label('//foo/bar:baz').package");
    assertThat(result).isEqualTo("foo/bar");
  }

  @Test
  public void testRuleLabelDefaultValue() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None\n"
            + "r1 = rule(impl, attrs = {'a1': "
            + "attr.label(default = Label('//foo:foo'), allow_files=True)})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    Attribute a = c.getAttributeByName("a1");
    assertThat(a.getDefaultValueUnchecked()).isInstanceOf(Label.class);
    assertThat(a.getDefaultValueUnchecked().toString()).isEqualTo("//foo:foo");
  }

  @Test
  public void testIntDefaultValue() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl, attrs = {'a1': attr.int(default = 40+2)})");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    Attribute a = c.getAttributeByName("a1");
    assertThat(a.getDefaultValueUnchecked()).isEqualTo(StarlarkInt.of(42));
  }

  @Test
  public void testIntDefaultValueMustBeInt32() throws Exception {
    // This is a test of the loading phase. Move somewhere more appropriate.
    ev.checkEvalErrorContains(
        "for parameter 'default' of attribute '', got 4294967296, want value in signed 32-bit"
            + " range",
        "attr.int(default = 0x10000 * 0x10000)");
    ev.checkEvalErrorContains(
        "for element 0 of parameter 'default' of attribute '', got 4294967296, want value in"
            + " signed 32-bit range",
        "attr.int_list(default = [0x10000 * 0x10000])");
  }

  @Test
  public void testIntAttributeValueMustBeInt32() throws Exception {
    // This is a test of the loading phase. Move somewhere more appropriate.
    scratch.file(
        "p/inc.bzl",
        """
        def _impl(ctx):
            pass

        r = rule(_impl, attrs = dict(i = attr.int()))
        """);
    scratch.file(
        "p/BUILD",
        """
        load("inc.bzl", "r")

        r(
            name = "p",
            i = 0x10000 * 0x10000,
        )
        """);
    AssertionError expected = assertThrows(AssertionError.class, () -> createRuleContext("//p"));
    assertThat(expected)
        .hasMessageThat()
        .contains("for attribute 'i' of 'r', got 4294967296, want value in signed 32-bit range");
  }

  @Test
  public void testIntegerConcatTruncates() throws Exception {
    // The Type.INTEGER.concat operator, as used to resolve select(int)+select(int)
    // after rule construction, has a range of int32.
    scratch.file(
        "p/BUILD",
        """
        # -0x7fffffff + -0x7fffffff = 2
        s = select({"//conditions:default": -0x7fffffff})

        cc_test(
            name = "c",
            shard_count = s + s,
        )
        """);
    StarlarkRuleContext context = createRuleContext("//p:c");
    assertThat(context.getAttr().getValue("shard_count")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testRuleInheritsBaseRuleAttributes() throws Exception {
    evalAndExport(ev, "def impl(ctx): return None", "r1 = rule(impl)");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.hasAttr("tags", Types.STRING_LIST)).isTrue();
    assertThat(c.hasAttr("visibility", BuildType.NODEP_LABEL_LIST)).isTrue();
    assertThat(c.hasAttr("deprecation", Type.STRING)).isTrue();
    assertThat(c.hasAttr(":action_listener", BuildType.LABEL_LIST))
        .isTrue(); // required for extra actions
  }

  private void checkTextMessage(String from, String... lines) throws Exception {
    Object result = ev.eval(from);
    String expect = "";
    if (lines.length > 0) {
      expect = Joiner.on("\n").join(lines) + "\n";
    }
    assertThat(result).isEqualTo(expect);
  }

  @Test
  public void testSimpleTextMessages() throws Exception {
    checkTextMessage("proto.encode_text(struct(name='value'))", "name: \"value\"");
    checkTextMessage("proto.encode_text(struct(name=[]))"); // empty lines
    checkTextMessage("proto.encode_text(struct(name=['a', 'b']))", "name: \"a\"", "name: \"b\"");
    checkTextMessage("proto.encode_text(struct(name=123))", "name: 123");
    checkTextMessage(
        "proto.encode_text(struct(a=1.2e34, b=float('nan'), c=float('-inf'), d=float('+inf')))",
        "a: 1.2e+34",
        "b: nan",
        "c: -inf",
        // Caution! textproto requires +inf be encoded as "inf" rather than "+inf"
        "d: inf");
    checkTextMessage("proto.encode_text(struct(name=123))", "name: 123");
    checkTextMessage("proto.encode_text(struct(name=[1, 2, 3]))", "name: 1", "name: 2", "name: 3");
    checkTextMessage("proto.encode_text(struct(a=struct(b='b')))", "a {", "  b: \"b\"", "}");
    checkTextMessage(
        "proto.encode_text(struct(a=[struct(b='x'), struct(b='y')]))",
        "a {",
        "  b: \"x\"",
        "}",
        "a {",
        "  b: \"y\"",
        "}");
    checkTextMessage(
        "proto.encode_text(struct(a=struct(b=struct(c='c'))))",
        "a {",
        "  b {",
        "    c: \"c\"",
        "  }",
        "}");
    // dict to_proto tests
    checkTextMessage("proto.encode_text(struct(name={}))"); // empty lines
    checkTextMessage(
        "proto.encode_text(struct(name={'a': 'b'}))",
        "name {",
        "  key: \"a\"",
        "  value: \"b\"",
        "}");
    checkTextMessage(
        "proto.encode_text(struct(name={'c': 'd', 'a': 'b'}))",
        "name {",
        "  key: \"c\"",
        "  value: \"d\"",
        "}",
        "name {",
        "  key: \"a\"",
        "  value: \"b\"",
        "}");
    checkTextMessage(
        "proto.encode_text(struct(x=struct(y={'a': 1})))",
        "x {",
        "  y {",
        "    key: \"a\"",
        "    value: 1",
        "  }",
        "}");
    checkTextMessage(
        "proto.encode_text(struct(name={'a': struct(b=1, c=2)}))",
        "name {",
        "  key: \"a\"",
        "  value {",
        "    b: 1",
        "    c: 2",
        "  }",
        "}");
    checkTextMessage(
        "proto.encode_text(struct(name={'a': struct(b={4: 'z', 3: 'y'}, c=2)}))",
        "name {",
        "  key: \"a\"",
        "  value {",
        "    b {",
        "      key: 4",
        "      value: \"z\"",
        "    }",
        "    b {",
        "      key: 3",
        "      value: \"y\"",
        "    }",
        "    c: 2",
        "  }",
        "}");
  }

  @Test
  public void testNoneStructValue() throws Exception {
    checkTextMessage(
        "proto.encode_text(struct(a=1, b=None, nested=struct(c=2, d=None)))",
        "a: 1",
        "nested {",
        "  c: 2",
        "}");
  }

  @Test
  public void testProtoFieldsOrder() throws Exception {
    checkTextMessage(
        "proto.encode_text(struct(d=4, b=2, c=3, a=1))", "a: 1", "b: 2", "c: 3", "d: 4");
  }

  @Test
  public void testTextMessageEscapes() throws Exception {
    checkTextMessage("proto.encode_text(struct(name='a\"b'))", "name: \"a\\\"b\"");
    checkTextMessage("proto.encode_text(struct(name='a\\'b'))", "name: \"a'b\"");
    checkTextMessage("proto.encode_text(struct(name='a\\nb'))", "name: \"a\\nb\"");

    // struct(name="a\\\"b") -> name: "a\\\"b"
    checkTextMessage("proto.encode_text(struct(name='a\\\\\\\"b'))", "name: \"a\\\\\\\"b\"");
  }

  @Test
  public void testTextMessageInvalidStructure() throws Exception {
    // list in list
    ev.checkEvalErrorContains(
        "in struct field .a: at list index 0: got list, want string, int, float, bool, or struct",
        "proto.encode_text(struct(a=[['b']]))");

    // dict in list
    ev.checkEvalErrorContains(
        "in struct field .a: at list index 0: got dict, want string, int, float, bool, or struct",
        "proto.encode_text(struct(a=[{'b': 1}]))");

    // tuple as dict key
    ev.checkEvalErrorContains(
        "in struct field .a: invalid dict key: got tuple, want int or string",
        "proto.encode_text(struct(a={(1, 2): 3}))");

    // dict in dict
    ev.checkEvalErrorContains(
        "in struct field .name: in value for dict key \"a\": got dict, want string, int, float,"
            + " bool, or struct",
        "proto.encode_text(struct(name={'a': {'b': [1, 2]}}))");

    // callable in field
    ev.checkEvalErrorContains(
        "in struct field .a: got builtin_function_or_method, want string, int, float, bool, or"
            + " struct",
        "proto.encode_text(struct(a=rule))");
  }

  private void checkJson(String from, String expected) throws Exception {
    Object result = ev.eval(from);
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void testStarlarkJsonModule() throws Exception {
    checkJson("json.encode(struct(name=True))", "{\"name\":true}");
    checkJson("json.encode([1, 2])", "[1,2]"); // works for non-structs too
  }

  @Test
  public void testJsonBooleanFields() throws Exception {
    checkJson("json.encode(struct(name=True))", "{\"name\":true}");
    checkJson("json.encode(struct(name=False))", "{\"name\":false}");
  }

  @Test
  public void testJsonDictFields() throws Exception {
    checkJson("json.encode(struct(config={}))", "{\"config\":{}}");
    checkJson("json.encode(struct(config={'key': 'value'}))", "{\"config\":{\"key\":\"value\"}}");
    ev.checkEvalErrorContains(
        "in struct field .config: dict has int key, want string",
        "json.encode(struct(config={1:2}))");
    ev.checkEvalErrorContains(
        "in struct field .config: in dict key \"foo\": dict has int key, want string",
        "json.encode(struct(config={'foo':{1:2}}))");
    ev.checkEvalErrorContains(
        "in struct field .config: dict has bool key, want string",
        "json.encode(struct(config={True: False}))");
  }

  @Test
  public void testJsonEncoding() throws Exception {
    checkJson("json.encode(struct(name='value'))", "{\"name\":\"value\"}");
    checkJson("json.encode(struct(name=['a', 'b']))", "{\"name\":[\"a\",\"b\"]}");
    checkJson("json.encode(struct(name=123))", "{\"name\":123}");
    checkJson("json.encode(struct(name=[1, 2, 3]))", "{\"name\":[1,2,3]}");
    checkJson("json.encode(struct(a=struct(b='b')))", "{\"a\":{\"b\":\"b\"}}");
    checkJson(
        "json.encode(struct(a=[struct(b='x'), struct(b='y')]))",
        "{\"a\":[{\"b\":\"x\"},{\"b\":\"y\"}]}");
    checkJson("json.encode(struct(a=struct(b=struct(c='c'))))", "{\"a\":{\"b\":{\"c\":\"c\"}}}");
  }

  @Test
  public void testJsonEscapes() throws Exception {
    checkJson("json.encode(struct(name='a\"b'))", "{\"name\":\"a\\\"b\"}");
    checkJson("json.encode(struct(name='a\\'b'))", "{\"name\":\"a'b\"}");
    checkJson("json.encode(struct(name='a\\\\b'))", "{\"name\":\"a\\\\b\"}");
    checkJson("json.encode(struct(name='a\\nb'))", "{\"name\":\"a\\nb\"}");
    checkJson("json.encode(struct(name='a\\rb'))", "{\"name\":\"a\\rb\"}");
    checkJson("json.encode(struct(name='a\\tb'))", "{\"name\":\"a\\tb\"}");
  }

  @Test
  public void testJsonNestedListStructure() throws Exception {
    checkJson("json.encode(struct(a=[['b']]))", "{\"a\":[[\"b\"]]}");
  }

  @Test
  public void testJsonInvalidStructure() throws Exception {
    ev.checkEvalErrorContains(
        "in struct field .a: cannot encode builtin_function_or_method as JSON",
        "json.encode(struct(a=rule))");
  }

  @Test
  public void testLabelAttrWrongDefault() throws Exception {
    ev.checkEvalErrorContains(
        "got value of type 'int', want 'Label, string, LateBoundDefault, function, or NoneType'",
        "attr.label(default = 123)");
  }

  @Test
  public void testLabelGetRelative() throws Exception {
    assertThat(ev.eval("Label('//foo:bar').relative('baz')").toString()).isEqualTo("//foo:baz");
    assertThat(ev.eval("Label('//foo:bar').relative('//baz:qux')").toString())
        .isEqualTo("//baz:qux");
  }

  @Test
  public void testLabelGetRelativeSyntaxError() throws Exception {
    ev.checkEvalErrorContains(
        "invalid target name 'bad//syntax': target names may not contain '//' path separators",
        "Label('//foo:bar').relative('bad//syntax')");
  }

  @Test
  public void testStructCreation() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec("x = struct(a = 1, b = 2)");
    assertThat(ev.lookup("x")).isInstanceOf(Structure.class);
  }

  @Test
  public void testStructFields() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec("x = struct(a = 1, b = 2)");
    Structure x = (Structure) ev.lookup("x");
    assertThat(x.getValue("a")).isEqualTo(StarlarkInt.of(1));
    assertThat(x.getValue("b")).isEqualTo(StarlarkInt.of(2));

    // Update is prohibited.
    ev.checkEvalErrorContains(
        "struct value does not support field assignment", "x = struct(a = 1); x.a = 2");
  }

  @Test
  public void testStructEquality() throws Exception {
    assertThat((Boolean) ev.eval("struct(a = 1, b = 2) == struct(b = 2, a = 1)")).isTrue();
    assertThat((Boolean) ev.eval("struct(a = 1) == struct(a = 1, b = 2)")).isFalse();
    assertThat((Boolean) ev.eval("struct(a = 1, b = 2) == struct(a = 1)")).isFalse();
    // Compare a recursive object to itself to make sure reference equality is checked
    ev.exec("s = struct(a = 1, b = []); s.b.append(s)");
    assertThat((Boolean) ev.eval("s == s")).isTrue();
    assertThat((Boolean) ev.eval("struct(a = 1, b = 2) == struct(a = 1, b = 3)")).isFalse();
    assertThat((Boolean) ev.eval("struct(a = 1) == [1]")).isFalse();
    assertThat((Boolean) ev.eval("[1] == struct(a = 1)")).isFalse();
    assertThat((Boolean) ev.eval("struct() == struct()")).isTrue();
    assertThat((Boolean) ev.eval("struct() == struct(a = 1)")).isFalse();

    ev.exec("foo = provider(); bar = provider()");
    assertThat((Boolean) ev.eval("struct(a = 1) == foo(a = 1)")).isFalse();
    assertThat((Boolean) ev.eval("foo(a = 1) == struct(a = 1)")).isFalse();
    assertThat((Boolean) ev.eval("foo(a = 1) == bar(a = 1)")).isFalse();
    assertThat((Boolean) ev.eval("foo(a = 1) == foo(a = 1)")).isTrue();
  }

  @Test
  public void testStructIncomparability() throws Exception {
    ev.checkEvalErrorContains(
        "unsupported comparison: struct <=> struct", "struct(a = 1) < struct(a = 2)");
    ev.checkEvalErrorContains(
        "unsupported comparison: struct <=> struct", "struct(a = 1) > struct(a = 2)");
    ev.checkEvalErrorContains(
        "unsupported comparison: struct <=> struct", "struct(a = 1) <= struct(a = 2)");
    ev.checkEvalErrorContains(
        "unsupported comparison: struct <=> struct", "struct(a = 1) >= struct(a = 2)");
  }

  @Test
  public void testStructAccessingFieldsFromStarlark() throws Exception {
    ev.exec("x = struct(a = 1, b = 2)", "x1 = x.a", "x2 = x.b");
    assertThat(ev.lookup("x1")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("x2")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testStructAccessingUnknownField() throws Exception {
    ev.checkEvalErrorContains(
        "'struct' value has no field or method 'c'\n" + "Available attributes: a, b",
        "x = struct(a = 1, b = 2)",
        "y = x.c");
  }

  @Test
  public void testStructAccessingUnknownFieldWithArgs() throws Exception {
    ev.checkEvalErrorContains(
        "'struct' value has no field or method 'c'", "x = struct(a = 1, b = 2)", "y = x.c()");
  }

  @Test
  public void testStructAccessingNonFunctionFieldWithArgs() throws Exception {
    ev.checkEvalErrorContains(
        "'int' object is not callable", "x = struct(a = 1, b = 2)", "x1 = x.a(1)");
  }

  @Test
  public void testStructAccessingFunctionFieldWithArgs() throws Exception {
    ev.exec("def f(x): return x+5", "x = struct(a = f, b = 2)", "x1 = x.a(1)");
    assertThat(ev.lookup("x1")).isEqualTo(StarlarkInt.of(6));
  }

  @Test
  public void testStructPosArgs() throws Exception {
    ev.checkEvalErrorContains(
        "struct() got unexpected positional argument", "x = struct(1, b = 2)");
  }

  @Test
  public void testStructConcatenationFieldNames() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec(
        "x = struct(a = 1, b = 2)", //
        "y = struct(c = 1, d = 2)",
        "z = x + y\n");
    StructImpl z = (StructImpl) ev.lookup("z");
    assertThat(z.getFieldNames()).containsExactly("a", "b", "c", "d");
  }

  @Test
  public void testStructConcatenationFieldValues() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec(
        "x = struct(a = 1, b = 2)", //
        "y = struct(c = 1, d = 2)",
        "z = x + y\n");
    StructImpl z = (StructImpl) ev.lookup("z");
    assertThat(z.getValue("a")).isEqualTo(StarlarkInt.of(1));
    assertThat(z.getValue("b")).isEqualTo(StarlarkInt.of(2));
    assertThat(z.getValue("c")).isEqualTo(StarlarkInt.of(1));
    assertThat(z.getValue("d")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testStructConcatenationCommonFields() throws Exception {
    ev.checkEvalErrorContains(
        "cannot add struct instances with common field 'a'",
        "x = struct(a = 1, b = 2)",
        "y = struct(c = 1, a = 2)",
        "z = x + y\n");
  }

  @Test
  public void testConditionalStructConcatenation() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    ev.exec(
        "def func():",
        "  x = struct(a = 1, b = 2)",
        "  if True:",
        "    x += struct(c = 1, d = 2)",
        "  return x",
        "x = func()");
    StructImpl x = (StructImpl) ev.lookup("x");
    assertThat(x.getValue("a")).isEqualTo(StarlarkInt.of(1));
    assertThat(x.getValue("b")).isEqualTo(StarlarkInt.of(2));
    assertThat(x.getValue("c")).isEqualTo(StarlarkInt.of(1));
    assertThat(x.getValue("d")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testGetattrNoAttr() throws Exception {
    ev.checkEvalErrorContains(
        "'struct' value has no field or method 'b'\nAvailable attributes: a",
        "s = struct(a='val')",
        "getattr(s, 'b')");
  }

  @Test
  public void testGetattr() throws Exception {
    ev.exec("s = struct(a='val')", "x = getattr(s, 'a')", "y = getattr(s, 'b', 'def')");
    assertThat(ev.lookup("x")).isEqualTo("val");
    assertThat(ev.lookup("y")).isEqualTo("def");
  }

  @Test
  public void testHasattr() throws Exception {
    ev.exec(
        "s = struct(a=1)", //
        "x = hasattr(s, 'a')",
        "y = hasattr(s, 'b')\n");
    assertThat(ev.lookup("x")).isEqualTo(true);
    assertThat(ev.lookup("y")).isEqualTo(false);
  }

  @Test
  public void testStructStr() throws Exception {
    assertThat(ev.eval("str(struct(x = 2, y = 3, z = 4))"))
        .isEqualTo("struct(x = 2, y = 3, z = 4)");
  }

  @Test
  public void testStructsInSets() throws Exception {
    ev.exec("depset([struct(a='a')])");
  }

  @Test
  public void testStructsInDicts() throws Exception {
    ev.exec("d = {struct(a = 1): 'aa', struct(b = 2): 'bb'}");
    assertThat(ev.eval("d[struct(a = 1)]")).isEqualTo("aa");
    assertThat(ev.eval("d[struct(b = 2)]")).isEqualTo("bb");
    assertThat(ev.eval("str([d[k] for k in d])")).isEqualTo("[\"aa\", \"bb\"]");

    ev.checkEvalErrorContains("unhashable type: 'struct'", "{struct(a = []): 'foo'}");
  }

  @Test
  public void testStructDictMembersAreMutable() throws Exception {
    ev.exec(
        "s = struct(x = {'a' : 1})", //
        "s.x['b'] = 2\n");
    assertThat(((StructImpl) ev.lookup("s")).getValue("x"))
        .isEqualTo(ImmutableMap.of("a", StarlarkInt.of(1), "b", StarlarkInt.of(2)));
  }

  @Test
  public void testDepsetGoodCompositeItem() throws Exception {
    ev.exec("def func():", "  return depset([struct(a='a')])", "s = func()");
    ImmutableList<?> result = ((Depset) ev.lookup("s")).toList();
    assertThat(result).hasSize(1);
    assertThat(result.get(0)).isInstanceOf(StructImpl.class);
  }

  private static StructImpl makeStruct(String field, Object value) {
    return StructProvider.STRUCT.create(ImmutableMap.of(field, value), "no field '%'");
  }

  private static StructImpl makeBigStruct(@Nullable Mutability mu) {
    // struct(a=[struct(x={1:1}), ()], b=(), c={2:2})
    return StructProvider.STRUCT.create(
        ImmutableMap.of(
            "a",
                StarlarkList.<Object>of(
                    mu,
                    StructProvider.STRUCT.create(
                        ImmutableMap.of("x", dictOf(mu, 1, 1)), "no field '%s'"),
                    Tuple.of()),
            "b", Tuple.of(),
            "c", dictOf(mu, 2, 2)),
        "no field '%s'");
  }

  private static Dict<Object, Object> dictOf(@Nullable Mutability mu, int k, int v) {
    return Dict.builder().put(StarlarkInt.of(k), StarlarkInt.of(v)).build(mu);
  }

  @Test
  public void testStructMutabilityShallow() {
    assertThat(Starlark.isImmutable(makeStruct("a", StarlarkInt.of(1)))).isTrue();
  }

  private static StarlarkList<Object> makeList(@Nullable Mutability mu) {
    return StarlarkList.of(mu, StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3));
  }

  @Test
  public void testStructMutabilityDeep() {
    assertThat(Starlark.isImmutable(Tuple.of(makeList(null)))).isTrue();
    assertThat(Starlark.isImmutable(makeStruct("a", makeList(null)))).isTrue();
    assertThat(Starlark.isImmutable(makeBigStruct(null))).isTrue();

    Mutability mu = Mutability.create("test");
    assertThat(Starlark.isImmutable(Tuple.of(makeList(mu)))).isFalse();
    assertThat(Starlark.isImmutable(makeStruct("a", makeList(mu)))).isFalse();
    assertThat(Starlark.isImmutable(makeBigStruct(mu))).isFalse();
  }

  @Test
  public void declaredProviders() throws Exception {
    evalAndExport(ev, "data = provider()", "d = data(x = 1, y ='abc')", "d_x = d.x", "d_y = d.y");
    assertThat(ev.lookup("d_x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("d_y")).isEqualTo("abc");
    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");
    StructImpl data = (StructImpl) ev.lookup("d");
    assertThat(data.getProvider()).isEqualTo(dataConstructor);
    assertThat(dataConstructor.isExported()).isTrue();
    assertThat(dataConstructor.getPrintableName()).isEqualTo("data");
    assertThat(dataConstructor.getKey())
        .isEqualTo(new StarlarkProvider.Key(keyForBuild(FAKE_LABEL), "data"));
  }

  @Test
  public void declaredProviderDocumentation() throws Exception {
    evalAndExport(
        ev,
        "UndocumentedInfo = provider()",
        "DocumentedInfo = provider(doc = '''",
        "    My documented provider",
        "",
        "    Details''')",
        // Note fields below are not alphabetized
        "SchemafulWithoutDocsInfo = provider(fields = ['b', 'a'])",
        "SchemafulWithDocsInfo = provider(fields = {'b': 'Field b', 'a': 'Field\\n    a'})");

    StarlarkProvider undocumentedInfo = (StarlarkProvider) ev.lookup("UndocumentedInfo");
    StarlarkProvider documentedInfo = (StarlarkProvider) ev.lookup("DocumentedInfo");
    StarlarkProvider schemafulWithoutDocsInfo =
        (StarlarkProvider) ev.lookup("SchemafulWithoutDocsInfo");
    StarlarkProvider schemafulWithDocsInfo = (StarlarkProvider) ev.lookup("SchemafulWithDocsInfo");

    assertThat(undocumentedInfo.getDocumentation()).isEmpty();
    assertThat(documentedInfo.getDocumentation()).hasValue("My documented provider\n\nDetails");
    assertThat(schemafulWithoutDocsInfo.getSchema())
        .containsExactly("b", Optional.empty(), "a", Optional.empty());
    assertThat(schemafulWithDocsInfo.getSchema())
        .containsExactly("b", Optional.of("Field b"), "a", Optional.of("Field\na"));
  }

  @Test
  public void declaredProvidersWithInit() throws Exception {
    evalAndExport(
        ev,
        "def _data_init(x, y = 'abc'):", //
        "    return {'x': x, 'y': y}",
        "data, _new_data = provider(init = _data_init)",
        "d1 = data(x = 1)  # normal provider constructor",
        "d1_x = d1.x",
        "d1_y = d1.y",
        "d2 = data(1, 'def')  # normal provider constructor invoked with positional arguments",
        "d2_x = d2.x",
        "d2_y = d2.y",
        "d3 = _new_data(x = 2, y = 'xyz')  # raw constructor",
        "d3_x = d3.x",
        "d3_y = d3.y");

    assertThat(ev.lookup("d1_x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("d1_y")).isEqualTo("abc");
    assertThat(ev.lookup("d2_x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("d2_y")).isEqualTo("def");
    assertThat(ev.lookup("d3_x")).isEqualTo(StarlarkInt.of(2));
    assertThat(ev.lookup("d3_y")).isEqualTo("xyz");
    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");
    StarlarkCallable rawConstructor = (StarlarkCallable) ev.lookup("_new_data");
    assertThat(rawConstructor).isNotInstanceOf(Provider.class);
    assertThat(dataConstructor.getInit().getName()).isEqualTo("_data_init");

    StructImpl data1 = (StructImpl) ev.lookup("d1");
    StructImpl data2 = (StructImpl) ev.lookup("d2");
    StructImpl data3 = (StructImpl) ev.lookup("d3");
    assertThat(data1.getProvider()).isEqualTo(dataConstructor);
    assertThat(data2.getProvider()).isEqualTo(dataConstructor);
    assertThat(data3.getProvider()).isEqualTo(dataConstructor);
    assertThat(dataConstructor.isExported()).isTrue();
    assertThat(dataConstructor.getPrintableName()).isEqualTo("data");
    assertThat(dataConstructor.getKey())
        .isEqualTo(new StarlarkProvider.Key(keyForBuild(FAKE_LABEL), "data"));
  }

  @Test
  public void declaredProvidersWithFailingInit_rawConstructorSucceeds() throws Exception {
    evalAndExport(
        ev,
        "def _data_failing_init(x):", //
        "    fail('_data_failing_init fails')",
        "data, _new_data = provider(init = _data_failing_init)");

    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");

    evalAndExport(ev, "d = _new_data(x = 1)  # raw constructor");
    StructImpl data = (StructImpl) ev.lookup("d");
    assertThat(data.getProvider()).isEqualTo(dataConstructor);
  }

  @Test
  public void declaredProvidersWithFailingInit_normalConstructorFails() throws Exception {
    evalAndExport(
        ev,
        "def _data_failing_init(x):", //
        "    fail('_data_failing_init fails')",
        "data, _new_data = provider(init = _data_failing_init)");

    ev.checkEvalErrorContains("_data_failing_init fails", "d = data(x = 1)  # normal constructor");
    assertThat(ev.lookup("d")).isNull();
  }

  @Test
  public void declaredProvidersWithInitReturningInvalidType_normalConstructorFails()
      throws Exception {
    evalAndExport(
        ev,
        "def _data_invalid_init(x):", //
        "    return 'INVALID'",
        "data, _new_data = provider(init = _data_invalid_init)");

    ev.checkEvalErrorContains(
        "got string for 'return value of provider init()', want dict",
        "d = data(x = 1)  # normal constructor");
    assertThat(ev.lookup("d")).isNull();
  }

  @Test
  public void declaredProvidersWithInitReturningInvalidDict_normalConstructorFails()
      throws Exception {
    evalAndExport(
        ev,
        "def _data_invalid_init(x):", //
        "    return {('x', 'x', 'x'): x}",
        "data, _new_data = provider(init = _data_invalid_init)");

    ev.checkEvalErrorContains(
        "got dict<tuple, int> for 'return value of provider init()'",
        "d = data(x = 1)  # normal constructor");
    assertThat(ev.lookup("d")).isNull();
  }

  @Test
  public void declaredProvidersWithInitReturningUnexpectedFields_normalConstructorFails()
      throws Exception {
    evalAndExport(
        ev,
        "def _data_unexpected_fields_init(x):", //
        "    return {'x': x, 'y': x * 2}",
        "data, _new_data = provider(fields = ['x'], init = _data_unexpected_fields_init)");

    ev.checkEvalErrorContains(
        "got unexpected field 'y' in call to instantiate provider data",
        "d = data(x = 1)  # normal constructor");
    assertThat(ev.lookup("d")).isNull();
  }

  @Test
  public void declaredProvidersConcatSuccess() throws Exception {
    evalAndExport(
        ev,
        "data = provider()",
        "dx = data(x = 1)",
        "dy = data(y = 'abc')",
        "dxy = dx + dy",
        "x = dxy.x",
        "y = dxy.y");
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("y")).isEqualTo("abc");
    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");
    StructImpl dx = (StructImpl) ev.lookup("dx");
    assertThat(dx.getProvider()).isEqualTo(dataConstructor);
    StructImpl dy = (StructImpl) ev.lookup("dy");
    assertThat(dy.getProvider()).isEqualTo(dataConstructor);
  }

  @Test
  public void declaredProvidersWithInitConcatSuccess() throws Exception {
    evalAndExport(
        ev,
        "def _data_init(x):",
        "    return {'x': x}",
        "data, _new_data = provider(init = _data_init)",
        "dx = data(x = 1)  # normal constructor",
        "dy = _new_data(y = 'abc')  # raw constructor",
        "dxy = dx + dy",
        "x = dxy.x",
        "y = dxy.y");
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("y")).isEqualTo("abc");
    StarlarkProvider dataConstructor = (StarlarkProvider) ev.lookup("data");
    StructImpl dx = (StructImpl) ev.lookup("dx");
    assertThat(dx.getProvider()).isEqualTo(dataConstructor);
    StructImpl dy = (StructImpl) ev.lookup("dy");
    assertThat(dy.getProvider()).isEqualTo(dataConstructor);
  }

  @Test
  public void declaredProvidersConcatError() throws Exception {
    evalAndExport(ev, "data1 = provider()", "data2 = provider()");

    ev.checkEvalErrorContains(
        "Cannot use '+' operator on instances of different providers (data1 and data2)",
        "d1 = data1(x = 1)",
        "d2 = data2(y = 2)",
        "d = d1 + d2");
  }

  @Test
  public void declaredProvidersWithFieldsConcatSuccess() throws Exception {
    evalAndExport(
        ev,
        "data = provider(fields=['f1', 'f2'])",
        "d1 = data(f1 = 4)",
        "d2 = data(f2 = 5)",
        "d3 = d1 + d2",
        "f1 = d3.f1",
        "f2 = d3.f2");
    assertThat(ev.lookup("f1")).isEqualTo(StarlarkInt.of(4));
    assertThat(ev.lookup("f2")).isEqualTo(StarlarkInt.of(5));
  }

  @Test
  public void declaredProvidersWithFieldsConcatError() throws Exception {
    evalAndExport(ev, "data1 = provider(fields=['f1', 'f2'])", "data2 = provider(fields=['f3'])");
    ev.checkEvalErrorContains(
        "Cannot use '+' operator on instances of different providers (data1 and data2)",
        "d1 = data1(f1=1, f2=2)",
        "d2 = data2(f3=3)",
        "d = d1 + d2");
  }

  @Test
  public void declaredProvidersWithOverlappingFieldsConcatError() throws Exception {
    evalAndExport(ev, "data = provider(fields=['f1', 'f2'])");
    ev.checkEvalErrorContains(
        "cannot add struct instances with common field 'f1'",
        "d1 = data(f1 = 4)",
        "d2 = data(f1 = 5)",
        "d1 + d2");
  }

  @Test
  public void structsAsDeclaredProvidersTest() throws Exception {
    evalAndExport(ev, "data = struct(x = 1)");
    StructImpl data = (StructImpl) ev.lookup("data");
    assertThat(StructProvider.STRUCT.isExported()).isTrue();
    assertThat(data.getProvider()).isEqualTo(StructProvider.STRUCT);
    assertThat(data.getProvider().getKey()).isEqualTo(StructProvider.STRUCT.getKey());
  }

  @Test
  public void declaredProvidersDoc() throws Exception {
    evalAndExport(ev, "data1 = provider(doc='foo')");
  }

  @Test
  public void declaredProvidersBadTypeForDoc() throws Exception {
    ev.checkEvalErrorContains(
        "got value of type 'int', want 'string or NoneType'", "provider(doc = 1)");
  }

  @Test
  public void aspectAttrs() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):", //
        "   pass",
        "my_aspect = aspect(_impl, attr_aspects=['srcs', 'data'])");

    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(myAspect.getAttributeAspects()).containsExactly("srcs", "data");
    assertThat(myAspect.getDefinition(AspectParameters.EMPTY).propagateAlong("srcs")).isTrue();
    assertThat(myAspect.getDefinition(AspectParameters.EMPTY).propagateAlong("data")).isTrue();
    assertThat(myAspect.getDefinition(AspectParameters.EMPTY).propagateAlong("other")).isFalse();
  }

  @Test
  public void aspectAllAttrs() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):", //
        "   pass",
        "my_aspect = aspect(_impl, attr_aspects=['*'])");

    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(myAspect.getAttributeAspects()).containsExactly("*");
    assertThat(myAspect.getDefinition(AspectParameters.EMPTY).propagateAlong("foo")).isTrue();
  }

  @Test
  public void aspectRequiredAspectProvidersSingle() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_aspect_providers=['java', cc])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder()
                    .addStarlark(declared("cc"))
                    .addStarlark("java")
                    .build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("cc").build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredAspectProvidersAlternatives() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_aspect_providers=[['java'], [cc]])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("java").build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("prolog").build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredAspectProvidersEmpty() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl, required_aspect_providers=[])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isFalse();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
  }

  @Test
  public void aspectRequiredAspectProvidersDefault() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):", //
        "   pass",
        "my_aspect = aspect(_impl)");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isFalse();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
  }

  @Test
  public void aspectRequiredProvidersNotAllowedWithApplyToGeneratingRules() throws Exception {
    ev.checkEvalErrorContains(
        "An aspect cannot simultaneously have required providers and apply to generating rules.",
        "prov = provider()",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   required_providers = [prov],",
        "   apply_to_generating_rules = True",
        ")");
  }

  @Test
  public void aspectRequiredProvidersSingle() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_providers=['java', cc])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders();

    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder()
                    .addStarlark(declared("cc"))
                    .addStarlark("java")
                    .build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredProvidersAlternatives() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_providers=[['java'], [cc]])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders();

    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("java").build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark(declared("cc")).build()))
        .isTrue();
    assertThat(
            requiredProviders.isSatisfiedBy(
                AdvertisedProviderSet.builder().addStarlark("prolog").build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredProvidersEmpty() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl, required_providers=[])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders();

    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isTrue();
  }

  @Test
  public void aspectRequiredProvidersDefault() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):", //
        "   pass",
        "my_aspect = aspect(_impl)");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    RequiredProviders requiredProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getRequiredProviders();

    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isTrue();
  }

  @Test
  public void aspectProvides() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "y = provider()",
        "my_aspect = aspect(_impl, provides = ['x', y])");
    StarlarkDefinedAspect myAspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    AdvertisedProviderSet advertisedProviders =
        myAspect.getDefinition(AspectParameters.EMPTY).getAdvertisedProviders();
    assertThat(advertisedProviders.canHaveAnyProvider()).isFalse();
    assertThat(advertisedProviders.getStarlarkProviders())
        .containsExactly(legacy("x"), declared("y"));
  }

  @Test
  public void aspectProvidesError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "def _impl(target, ctx):",
        "   pass",
        "y = provider()",
        "my_aspect = aspect(_impl, provides = ['x', 1])");
    MoreAsserts.assertContainsEvent(
        ev.getEventCollector(),
        " Illegal argument: element in 'provides' is of unexpected type."
            + " Should be list of providers, but got item of type int. ");
  }

  @Test
  public void aspectDoc() throws Exception {
    evalAndExport(
        ev,
        "def _impl(target, ctx):", //
        "   pass",
        "documented_aspect = aspect(_impl, doc='My doc string')",
        "long_documented_aspect = aspect(",
        "    implementation = _impl,",
        "    doc='''",
        "           My doc string",
        "           ",
        "           With details''',",
        ")",
        "undocumented_aspect = aspect(_impl)");

    StarlarkDefinedAspect documentedAspect = (StarlarkDefinedAspect) ev.lookup("documented_aspect");
    assertThat(documentedAspect.getDocumentation()).hasValue("My doc string");
    StarlarkDefinedAspect longDocumentedAspect =
        (StarlarkDefinedAspect) ev.lookup("long_documented_aspect");
    assertThat(longDocumentedAspect.getDocumentation()).hasValue("My doc string\n\nWith details");
    StarlarkDefinedAspect undocumentedAspect =
        (StarlarkDefinedAspect) ev.lookup("undocumented_aspect");
    assertThat(undocumentedAspect.getDocumentation()).isEmpty();
  }

  @Test
  public void aspectBadTypeForDoc() throws Exception {
    registerDummyStarlarkFunction();
    ev.checkEvalErrorContains(
        "got value of type 'int', want 'string or NoneType'", "aspect(impl, doc = 1)");
  }

  @Test
  public void fancyExports() throws Exception {
    evalAndExport(
        ev,
        "def _impla(target, ctx): pass",
        "p, (a, p1) = [",
        "   provider(),",
        "   [ aspect(_impla),",
        "     provider() ]",
        "]");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkDefinedAspect a = (StarlarkDefinedAspect) ev.lookup("a");
    StarlarkProvider p1 = (StarlarkProvider) ev.lookup("p1");
    assertThat(p.getPrintableName()).isEqualTo("p");
    assertThat(p.getKey()).isEqualTo(new StarlarkProvider.Key(keyForBuild(FAKE_LABEL), "p"));
    assertThat(p1.getPrintableName()).isEqualTo("p1");
    assertThat(p1.getKey()).isEqualTo(new StarlarkProvider.Key(keyForBuild(FAKE_LABEL), "p1"));
    assertThat(a.getAspectClass()).isEqualTo(new StarlarkAspectClass(keyForBuild(FAKE_LABEL), "a"));
  }

  @Test
  public void multipleTopLevels() throws Exception {
    evalAndExport(
        ev,
        "p = provider()", //
        "p1 = p");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkProvider p1 = (StarlarkProvider) ev.lookup("p1");
    assertThat(p).isEqualTo(p1);
    assertThat(p.getKey()).isEqualTo(new StarlarkProvider.Key(keyForBuild(FAKE_LABEL), "p"));
    assertThat(p1.getKey()).isEqualTo(new StarlarkProvider.Key(keyForBuild(FAKE_LABEL), "p"));
  }

  @Test
  public void providerWithFields() throws Exception {
    evalAndExport(
        ev,
        "p = provider(fields = ['x', 'y'])", //
        "p1 = p(x = 1, y = 2)",
        "x = p1.x",
        "y = p1.y");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkInfo p1 = (StarlarkInfo) ev.lookup("p1");

    assertThat(p1.getProvider()).isEqualTo(p);
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void providerWithFieldsDict() throws Exception {
    evalAndExport(
        ev,
        "p = provider(fields = { 'x' : 'I am x', 'y' : 'I am y'})",
        "p1 = p(x = 1, y = 2)",
        "x = p1.x",
        "y = p1.y");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkInfo p1 = (StarlarkInfo) ev.lookup("p1");

    assertThat(p1.getProvider()).isEqualTo(p);
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void providerWithFieldsOptional() throws Exception {
    evalAndExport(
        ev,
        "p = provider(fields = ['x', 'y'])", //
        "p1 = p(y = 2)",
        "y = p1.y");
    StarlarkProvider p = (StarlarkProvider) ev.lookup("p");
    StarlarkInfo p1 = (StarlarkInfo) ev.lookup("p1");

    assertThat(p1.getProvider()).isEqualTo(p);
    assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void providerWithFieldsOptionalError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "p = provider(fields = ['x', 'y'])", //
        "p1 = p(y = 2)",
        "x = p1.x");
    MoreAsserts.assertContainsEvent(
        ev.getEventCollector(), " 'p' value has no field or method 'x'");
  }

  @Test
  public void providerWithExtraFieldsError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(ev, "p = provider(fields = ['x', 'y'])", "p1 = p(x = 1, y = 2, z = 3)");
    MoreAsserts.assertContainsEvent(
        ev.getEventCollector(), "got unexpected field 'z' in call to instantiate provider p");
  }

  @Test
  public void providerWithEmptyFieldsError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "p = provider(fields = [])", //
        "p1 = p(x = 1, y = 2, z = 3)");
    MoreAsserts.assertContainsEvent(
        ev.getEventCollector(),
        "got unexpected fields 'x', 'y', 'z' in call to instantiate provider p");
  }

  @Test
  public void providerWithDuplicateFieldsError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        ev,
        "p = provider(fields = ['a', 'b'])", //
        "p(a = 1, b = 2, **dict(b = 3))");
    MoreAsserts.assertContainsEvent(
        ev.getEventCollector(),
        "got multiple values for parameter b in call to instantiate provider p");
  }

  @Test
  public void starTheOnlyAspectArg() throws Exception {
    ev.checkEvalErrorContains(
        "'*' must be the only string in 'attr_aspects' list",
        "def _impl(target, ctx):",
        "   pass",
        "aspect(_impl, attr_aspects=['*', 'foo'])");
  }

  @Test
  public void testMandatoryConfigParameterForExecutableLabels() throws Exception {
    scratch.file(
        "third_party/foo/extension.bzl",
        """
        def _main_rule_impl(ctx):
            pass

        my_rule = rule(
            _main_rule_impl,
            attrs = {
                "exe": attr.label(executable = True, allow_files = True),
            },
        )
        """);
    scratch.file(
        "third_party/foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(
            name = "main",
            exe = ":tool.sh",
        )
        """);

    AssertionError expected =
        assertThrows(AssertionError.class, () -> createRuleContext("//third_party/foo:main"));
    assertThat(expected)
        .hasMessageThat()
        .contains("cfg parameter is mandatory when executable=True is provided.");
  }

  @Test
  public void testRuleAddToolchain() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl,",
        "    toolchains=[",
        "        '//test:my_toolchain_type1',",
        "        config_common.toolchain_type('//test:my_toolchain_type2'),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
        "        config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
        "    ],",
        ")");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c).hasToolchainType("//test:my_toolchain_type1");
    assertThat(c).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(c).hasToolchainType("//test:my_toolchain_type2");
    assertThat(c).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(c).hasToolchainType("//test:my_toolchain_type3");
    assertThat(c).toolchainType("//test:my_toolchain_type3").isOptional();
    assertThat(c).hasToolchainType("//test:my_toolchain_type4");
    assertThat(c).toolchainType("//test:my_toolchain_type4").isMandatory();
  }

  @Test
  public void testRuleAddToolchain_duplicate() throws Exception {
    evalAndExport(
        ev,
        "def impl(ctx): return None",
        "r1 = rule(impl,",
        "    toolchains=[",
        "        '//test:my_toolchain_type1',",
        "        config_common.toolchain_type('//test:my_toolchain_type1'),",
        "        config_common.toolchain_type('//test:my_toolchain_type2', mandatory = False),",
        "        config_common.toolchain_type('//test:my_toolchain_type2', mandatory = True),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory = False),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory = False),",
        "    ],",
        ")");

    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c).hasToolchainType("//test:my_toolchain_type1");
    assertThat(c).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(c).hasToolchainType("//test:my_toolchain_type2");
    assertThat(c).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(c).hasToolchainType("//test:my_toolchain_type3");
    assertThat(c).toolchainType("//test:my_toolchain_type3").isOptional();
  }

  @Test
  public void testRuleAddExecutionConstraints() throws Exception {
    registerDummyStarlarkFunction();
    evalAndExport(
        ev,
        "r1 = rule(",
        "  implementation = impl,",
        "  exec_compatible_with=['//constraint:cv1', '//constraint:cv2'],",
        ")");
    RuleClass c = ((StarlarkRuleFunction) ev.lookup("r1")).getRuleClass();
    assertThat(c.getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseCanonicalUnchecked("//constraint:cv1"),
            Label.parseCanonicalUnchecked("//constraint:cv2"));
  }

  @Test
  public void testRuleAddExecGroup() throws Exception {
    registerDummyStarlarkFunction();
    evalAndExport(
        ev,
        "plum = rule(",
        "  implementation = impl,",
        "  exec_groups = {",
        "    'group': exec_group(",
        "      toolchains=[",
        "        '//test:my_toolchain_type1',",
        "        config_common.toolchain_type('//test:my_toolchain_type2'),",
        "        config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
        "        config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
        "      ],",
        "      exec_compatible_with=['//constraint:cv1', '//constraint:cv2'],",
        "    ),",
        "  },",
        ")");
    RuleClass plum = ((StarlarkRuleFunction) ev.lookup("plum")).getRuleClass();
    assertThat(plum.getToolchainTypes()).isEmpty();
    ExecGroup execGroup = plum.getExecGroups().get("group");
    assertThat(execGroup).hasToolchainType("//test:my_toolchain_type1");
    assertThat(execGroup).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(execGroup).hasToolchainType("//test:my_toolchain_type2");
    assertThat(execGroup).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(execGroup).hasToolchainType("//test:my_toolchain_type3");
    assertThat(execGroup).toolchainType("//test:my_toolchain_type3").isOptional();
    assertThat(execGroup).hasToolchainType("//test:my_toolchain_type4");
    assertThat(execGroup).toolchainType("//test:my_toolchain_type4").isMandatory();

    assertThat(plum.getExecutionPlatformConstraints()).isEmpty();
    assertThat(execGroup).hasExecCompatibleWith("//constraint:cv1");
    assertThat(execGroup).hasExecCompatibleWith("//constraint:cv2");
  }

  @Test
  public void testRuleOrderedRequirements() throws Exception {
    registerDummyStarlarkFunction();
    evalAndExport(
        ev,
        "plum = rule(",
        "  implementation = impl,",
        "  exec_compatible_with = [",
        "    '//constraint:cv5',",
        "    '//constraint:cv4',",
        "    '//constraint:cv3',",
        "    '//constraint:cv2',",
        "    '//constraint:cv1',",
        "  ],",
        "  toolchains = [",
        "    '//test:my_toolchain_type5',",
        "    '//test:my_toolchain_type4',",
        "    '//test:my_toolchain_type3',",
        "    '//test:my_toolchain_type2',",
        "    '//test:my_toolchain_type1',",
        "  ],",
        "  exec_groups = {",
        "    'group5': exec_group(",
        "      toolchains = [",
        "        '//test:my_toolchain_type5',",
        "        '//test:my_toolchain_type4',",
        "        '//test:my_toolchain_type3',",
        "        '//test:my_toolchain_type2',",
        "        '//test:my_toolchain_type1',",
        "      ],",
        "    ),",
        "    'group4': exec_group(",
        "      exec_compatible_with = [",
        "        '//constraint:cv5',",
        "        '//constraint:cv4',",
        "        '//constraint:cv3',",
        "        '//constraint:cv2',",
        "        '//constraint:cv1',",
        "      ],",
        "    ),",
        "    'group3': exec_group(),",
        "    'group2': exec_group(),",
        "    'group1': exec_group(),",
        "  },",
        ")");
    RuleClass plum = ((StarlarkRuleFunction) ev.lookup("plum")).getRuleClass();
    assertThat(plum.getToolchainTypes().stream().map(ToolchainTypeRequirement::toolchainType))
        .containsExactly(
            Label.parseCanonicalUnchecked("//test:my_toolchain_type5"),
            Label.parseCanonicalUnchecked("//test:my_toolchain_type4"),
            Label.parseCanonicalUnchecked("//test:my_toolchain_type3"),
            Label.parseCanonicalUnchecked("//test:my_toolchain_type2"),
            Label.parseCanonicalUnchecked("//test:my_toolchain_type1"))
        .inOrder();
    assertThat(plum.getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseCanonicalUnchecked("//constraint:cv5"),
            Label.parseCanonicalUnchecked("//constraint:cv4"),
            Label.parseCanonicalUnchecked("//constraint:cv3"),
            Label.parseCanonicalUnchecked("//constraint:cv2"),
            Label.parseCanonicalUnchecked("//constraint:cv1"))
        .inOrder();
    assertThat(plum.getExecGroups().keySet())
        .containsExactly("group5", "group4", "group3", "group2", "group1")
        .inOrder();
    assertThat(plum.getExecGroups().get("group5").toolchainTypesMap().keySet())
        .containsExactly(
            Label.parseCanonicalUnchecked("//test:my_toolchain_type5"),
            Label.parseCanonicalUnchecked("//test:my_toolchain_type4"),
            Label.parseCanonicalUnchecked("//test:my_toolchain_type3"),
            Label.parseCanonicalUnchecked("//test:my_toolchain_type2"),
            Label.parseCanonicalUnchecked("//test:my_toolchain_type1"))
        .inOrder();
    assertThat(plum.getExecGroups().get("group4").execCompatibleWith())
        .containsExactly(
            Label.parseCanonicalUnchecked("//constraint:cv5"),
            Label.parseCanonicalUnchecked("//constraint:cv4"),
            Label.parseCanonicalUnchecked("//constraint:cv3"),
            Label.parseCanonicalUnchecked("//constraint:cv2"),
            Label.parseCanonicalUnchecked("//constraint:cv1"))
        .inOrder();
  }

  @Test
  public void testRuleFunctionReturnsNone() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
            pass

        foo_rule = rule(
            implementation = _impl,
            attrs = {"params": attr.string_list()},
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load(":rule.bzl", "foo_rule")

        # Custom rule should return None
        r = foo_rule(name = "foo")

        # Native rule should return None
        c = cc_library(name = "cc")

        foo_rule(
            name = "check",
            params = [
                type(r),
                type(c),
            ],
        )
        """);
    invalidatePackages();
    StarlarkRuleContext context = createRuleContext("//test:check");
    @SuppressWarnings("unchecked")
    StarlarkList<Object> params = (StarlarkList<Object>) context.getAttr().getValue("params");
    assertThat(params.get(0)).isEqualTo("NoneType");
    assertThat(params.get(1)).isEqualTo("NoneType");
  }

  @Test
  public void testTypeOfStruct() throws Exception {
    ev.exec("p = type(struct)", "s = type(struct())");

    assertThat(ev.lookup("p")).isEqualTo("Provider");
    assertThat(ev.lookup("s")).isEqualTo("struct");
  }

  @Test
  public void testCreateExecGroup() throws Exception {
    evalAndExport(
        ev,
        "group = exec_group(",
        "  toolchains=[",
        "    '//test:my_toolchain_type1',",
        "    config_common.toolchain_type('//test:my_toolchain_type2'),",
        "    config_common.toolchain_type('//test:my_toolchain_type3', mandatory=False),",
        "    config_common.toolchain_type('//test:my_toolchain_type4', mandatory=True),",
        "  ],",
        "  exec_compatible_with=['//constraint:cv1', '//constraint:cv2'],",
        ")");
    ExecGroup group = ((ExecGroup) ev.lookup("group"));
    assertThat(group).hasToolchainType("//test:my_toolchain_type1");
    assertThat(group).toolchainType("//test:my_toolchain_type1").isMandatory();
    assertThat(group).hasToolchainType("//test:my_toolchain_type2");
    assertThat(group).toolchainType("//test:my_toolchain_type2").isMandatory();
    assertThat(group).hasToolchainType("//test:my_toolchain_type3");
    assertThat(group).toolchainType("//test:my_toolchain_type3").isOptional();
    assertThat(group).hasToolchainType("//test:my_toolchain_type4");
    assertThat(group).toolchainType("//test:my_toolchain_type4").isMandatory();

    assertThat(group).hasExecCompatibleWith("//constraint:cv1");
    assertThat(group).hasExecCompatibleWith("//constraint:cv2");
  }

  @Test
  public void ruleDefinitionEnvironmentDigest_unaffectedByTargetAttrValueChange() throws Exception {
    scratch.file(
        "r/def.bzl",
        """
        Info = provider()
        def _r(ctx):
            return Info(value = ctx.attr.text)

        r = rule(implementation = _r, attrs = {"text": attr.string()})
        """);
    scratch.file(
        "r/BUILD",
        """
        load(":def.bzl", "r")

        r(
            name = "r",
            text = "old",
        )
        """);
    byte[] oldDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    scratch.deleteFile("r/BUILD");
    scratch.file(
        "r/BUILD",
        """
        load(":def.bzl", "r")

        r(
            name = "r",
            text = "new",
        )
        """);
    // Signal SkyFrame to discover changed files.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);
    byte[] newDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    assertThat(newDigest).isEqualTo(oldDigest);
  }

  @Test
  public void ruleDefinitionEnvironmentDigest_accountsForFunctionWhenCreatingRuleWithAMacro()
      throws Exception {
    scratch.file("r/create.bzl", "def create(impl): return rule(implementation=impl)");
    scratch.file(
        "r/def.bzl",
        """
        load(":create.bzl", "create")
        Info = provider()
        def f(ctx):
            return Info(value = "OLD")

        r = create(f)
        """);
    scratch.file(
        "r/BUILD",
        """
        load(":def.bzl", "r")

        r(name = "r")
        """);
    byte[] oldDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    scratch.deleteFile("r/def.bzl");
    scratch.file(
        "r/def.bzl",
        """
        load(":create.bzl", "create")
        Info = provider()
        def f(ctx):
            return Info(value = "NEW")

        r = create(f)
        """);
    // Signal SkyFrame to discover changed files.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);
    byte[] newDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    assertThat(newDigest).isNotEqualTo(oldDigest);
  }

  @Test
  public void ruleDefinitionEnvironmentDigest_accountsForAttrsWhenCreatingRuleWithMacro()
      throws Exception {
    scratch.file(
        "r/create.bzl",
        """
        Info = provider()
        def f(ctx):
            return Info(value = json.encode(ctx.attr))

        def create(attrs):
            return rule(implementation = f, attrs = attrs)
        """);
    scratch.file(
        "r/def.bzl",
        """
        load(":create.bzl", "create")

        r = create({})
        """);
    scratch.file(
        "r/BUILD",
        """
        load(":def.bzl", "r")

        r(name = "r")
        """);
    byte[] oldDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    scratch.deleteFile("r/def.bzl");
    scratch.file(
        "r/def.bzl",
        """
        load(":create.bzl", "create")

        r = create({"value": attr.string(default = "")})
        """);
    // Signal SkyFrame to discover changed files.
    skyframeExecutor.handleDiffsForTesting(NullEventHandler.INSTANCE);
    byte[] newDigest =
        createRuleContext("//r:r")
            .getRuleContext()
            .getRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentDigest();

    assertThat(newDigest).isNotEqualTo(oldDigest);
  }

  /**
   * This test is crucial for correctness of {@link RuleClass#getRuleDefinitionEnvironmentDigest}
   * since we use a dummy bzl transitive digest in that case. It is correct to do that only because
   * a rule class created by a BUILD thread cannot be instantiated.
   */
  @Test
  public void ruleClassDefinedInBuildFile_fails() throws Exception {
    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    scratch.file("r/create.bzl", "def create(impl): return rule(implementation=impl)");
    scratch.file(
        "r/def.bzl",
        """
        load(":create.bzl", "create")

        r = create({})
        """);
    scratch.file("r/impl.bzl", "def make_struct(ctx): return struct(value='hello')");
    scratch.file(
        "r/BUILD",
        """
        load(":create.bzl", "create")
        load(":impl.bzl", "make_struct")

        r = create(make_struct)

        r(name = "r")
        """);

    getConfiguredTarget("//r:r");

    ev.assertContainsError(
        "rule() can only be used during .bzl initialization (top-level evaluation)");
  }

  @Test
  public void testAttrWithAspectRequiringAspects_requiredNativeAspect_getsParamsFromBaseRules()
      throws Exception {
    scratch.file(
        "lib.bzl",
        """
        rule_prov = provider()

        def _impl(target, ctx):
            pass

        aspect_a = aspect(
            implementation = _impl,
            requires = [parametrized_native_aspect],
            attr_aspects = ["deps"],
            required_providers = [rule_prov],
        )

        def impl(ctx):
            return None

        my_rule = rule(
            impl,
            attrs = {
                "deps": attr.label_list(aspects = [aspect_a]),
                "aspect_attr": attr.string(),
            },
        )
        """);
    scratch.file(
        "BUILD", "load(':lib.bzl', 'my_rule')", "my_rule(name = 'main', aspect_attr = 'v1')");

    RuleContext ruleContext = createRuleContext("//:main").getRuleContext();

    Rule rule = ruleContext.getRule();
    Attribute attr = rule.getRuleClassObject().getAttributeByName("deps");
    ImmutableList<Aspect> aspects = attr.getAspects(rule);
    Aspect requiredNativeAspect = aspects.get(0);
    assertThat(requiredNativeAspect.getAspectClass().getName())
        .isEqualTo("ParametrizedAspectWithProvider");
    assertThat(
            requiredNativeAspect
                .getDefinition()
                .getAttributes()
                .get("aspect_attr")
                .getDefaultValueUnchecked())
        .isEqualTo("v1");
  }

  @Test
  public void initializer_allowlist() throws Exception {
    scratch.file(
        "p/b.bzl",
        """
        def initializer(**kwargs):
            return kwargs

        def impl(ctx):
            pass

        my_rule = rule(impl, initializer = initializer)
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        """);
    setBuildLanguageOptions("--noexperimental_rule_extension_api");

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//p:my_target");

    ev.assertContainsError("Non-allowlisted attempt to use initializer.");
  }

  // TODO b/298561048 - move the initializers tests below into a separate file

  /**
   * Verifies that precisely returned attributes are modified.
   *
   * <p>When an attribute is not returned it's unaffected.
   *
   * <p>It also verifies that the keyword arguments passed to the initializer are exactly the values
   * of the declared attributes.".
   */
  @Test
  @SuppressWarnings("unchecked")
  public void initializer_basic() throws Exception {
    scratch.file(
        "BUILD", //
        "filegroup(name = 'initial')",
        "filegroup(name = 'added')");
    scratch.file(
        "initializer_testing/b.bzl",
        """
        MyInfo = provider()

        def initializer(name, srcs = [], deps = []):
            return {"deps": deps + ["//:added"]}

        def impl(ctx):
            return [MyInfo(
                srcs = [s.short_path for s in ctx.files.srcs],
                deps = [str(d.label) for d in ctx.attr.deps],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
            deps = ["//:initial"],
        )
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//initializer_testing:my_target");
    StructImpl info =
        (StructImpl)
            myTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"));

    assertThat((List<String>) info.getValue("srcs")).containsExactly("initializer_testing/a.ml");
    assertThat((List<String>) info.getValue("deps")).containsExactly("@@//:initial", "@@//:added");
  }

  @Test
  public void initializer_nameUnchanged() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(name, **kwargs):
            if name != "my_target":
                fail()
            return {"name": name} | kwargs

        MyInfo = provider()

        def impl(ctx):
            pass

        my_rule = rule(impl, initializer = initializer)
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        """);

    getConfiguredTarget("//initializer_testing:my_target");

    assertNoEvents();
  }

  @Test
  public void initializer_nameChanged() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(name, **kwargs):
            return {"name": "my_new_name"}

        def impl(ctx):
            pass

        my_rule = rule(impl, initializer = initializer)
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError("Error in my_rule: Initializer can't change the name of the target");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void initializer_stringListDict() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(**kwargs):
            return {}

        MyInfo = provider()

        def impl(ctx):
            return [MyInfo(dict = ctx.attr.dict)]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "dict": attr.string_list_dict(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            dict = {"k": ["val"]},
        )
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//initializer_testing:my_target");
    StructImpl info =
        (StructImpl)
            myTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"));

    assertThat(((Map<String, List<String>>) info.getValue("dict")).keySet()).containsExactly("k");
    assertThat(((Map<String, List<String>>) info.getValue("dict")).get("k")).containsExactly("val");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void stringKeyedLabelDictWithSplitConfiguration() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":a.bzl", "a")
        a(name="a", dict={"foo_key": ":foo", "gen_key": ":gen"})

        filegroup(name="foo", srcs=["foo.txt"])
        genrule(name="gen", srcs=[], outs=["gen.txt"], cmd="exit 1")
        """);

    scratch.file(
        "a/a.bzl",
        """
        DictInfo = provider(fields=["dict"])

        def _a_impl(ctx):
            return [DictInfo(dict = ctx.split_attr.dict)]

        def _trans_impl(settings, attr):
          return {
            "fastbuild_key": {"//command_line_option:compilation_mode": "fastbuild"},
            "dbg_key": {"//command_line_option:compilation_mode": "dbg"},
          }

        trans = transition(
            implementation = _trans_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"])

        a = rule(
            implementation=_a_impl,
            attrs={"dict": attr.string_keyed_label_dict(cfg=trans)})
        """);

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    StructImpl info =
        (StructImpl)
            a.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//a:a.bzl")), "DictInfo"));
    Map<String, Map<String, ConfiguredTarget>> dict =
        (Map<String, Map<String, ConfiguredTarget>>) info.getValue("dict");
    assertThat(dict.keySet()).containsExactly("fastbuild_key", "dbg_key");
    Map<String, ConfiguredTarget> fastbuild = dict.get("fastbuild_key");
    Map<String, ConfiguredTarget> dbg = dict.get("dbg_key");
    assertThat(fastbuild.keySet()).containsExactly("foo_key", "gen_key");
    assertThat(dbg.keySet()).containsExactly("foo_key", "gen_key");

    assertThat(getFilesToBuild(fastbuild.get("foo_key")).getSingleton().getExecPathString())
        .isEqualTo("a/foo.txt");
    assertThat(getFilesToBuild(dbg.get("foo_key")).getSingleton().getExecPathString())
        .isEqualTo("a/foo.txt");
    assertThat(getFilesToBuild(fastbuild.get("gen_key")).getSingleton().getExecPathString())
        .endsWith("-fastbuild/bin/a/gen.txt");
    assertThat(getFilesToBuild(dbg.get("gen_key")).getSingleton().getExecPathString())
        .endsWith("-dbg/bin/a/gen.txt");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void stringKeyedLabelDict() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":a.bzl", "a")
        a(name="a", dict={"foo_key": ":foo", "gen_key": ":gen"})

        filegroup(name="foo", srcs=["foo.txt"])
        genrule(name="gen", srcs=[], outs=["gen.txt"], cmd="exit 1")
        """);

    scratch.file(
        "a/a.bzl",
        """
        DictInfo = provider(fields=["dict"])

        def _a_impl(ctx):
            return [DictInfo(dict = ctx.attr.dict)]

        a = rule(implementation=_a_impl, attrs={"dict": attr.string_keyed_label_dict()})
        """);

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    StructImpl info =
        (StructImpl)
            a.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//a:a.bzl")), "DictInfo"));
    Map<String, ConfiguredTarget> dict = (Map<String, ConfiguredTarget>) info.getValue("dict");
    assertThat(dict.keySet()).containsExactly("foo_key", "gen_key");
    assertThat(dict.get("foo_key").getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//a:foo"));
    assertThat(dict.get("gen_key").getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//a:gen"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void initializer_labelKeyedStringDict() throws Exception {
    scratch.file(
        "BUILD", //
        "filegroup(name = 'key')");
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(**kwargs):
            return {}

        MyInfo = provider()

        def impl(ctx):
            return [MyInfo(dict = ctx.attr.dict)]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "dict": attr.label_keyed_string_dict(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            dict = {"//:key": "val"},
        )
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//initializer_testing:my_target");
    ConfiguredTarget key = getConfiguredTarget("//:key");
    StructImpl info =
        (StructImpl)
            myTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"));

    assertThat(((Map<ConfiguredTarget, String>) info.getValue("dict")).keySet())
        .containsExactly(key);
    assertThat(((Map<ConfiguredTarget, String>) info.getValue("dict")).get(key)).isEqualTo("val");
  }

  @Test
  public void initializer_legacyAnyType() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        MyInfo = provider()

        def initializer(name, tristate = -1):
            return {"tristate": int(tristate)}

        def impl(ctx):
            return [MyInfo(tristate = ctx.attr.tristate)]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "tristate": attr.int(),
                "_legacy_any_type_attrs": attr.string_list(default = ["tristate"]),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            tristate = True,
        )
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//initializer_testing:my_target");
    StructImpl info =
        (StructImpl)
            myTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"));

    assertThat((StarlarkInt) info.getValue("tristate")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void initializer_wrongType() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        MyInfo = provider()

        def initializer(srcs = []):
            return {"srcs": ["a.ml"]}

        def impl(ctx):
            return [MyInfo(
                srcs = [s.short_path for s in ctx.files.srcs],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = "default_files",
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError(
        """
        expected value of type 'list(label)' for attribute 'srcs' of 'my_rule', but got \
        "default_files" (string)\
        """);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void initializer_withSelect() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        MyInfo = provider()

        def initializer(name, srcs = []):
            return {"srcs": srcs + ["b.ml"]}

        def impl(ctx):
            return [MyInfo(
                srcs = [s.short_path for s in ctx.files.srcs],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = select({"//conditions:default": ["a.ml"]}),
        )
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//initializer_testing:my_target");
    StructImpl info =
        (StructImpl)
            myTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"));

    assertThat((List<String>) info.getValue("srcs"))
        .containsExactly("initializer_testing/a.ml", "initializer_testing/b.ml");
  }

  @Test
  public void initializer_passThrough() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(**kwargs):
            pass

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    getConfiguredTarget("//initializer_testing:my_target");

    assertNoEvents();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void initializer_overridesAttributeDefault() throws Exception {
    scratch.file(
        "BUILD", //
        "filegroup(name = 'initializer_default')",
        "filegroup(name = 'attr_default')");
    scratch.file(
        "initializer_testing/b.bzl",
        """
        MyInfo = provider()

        def initializer(name, deps = ["//:initializer_default"]):
            return {"deps": deps}

        def impl(ctx):
            return [MyInfo(
                deps = [str(d.label) for d in ctx.attr.deps],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "deps": attr.label_list(default = ["//:attr_default"]),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//initializer_testing:my_target");
    StructImpl info =
        (StructImpl)
            myTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"));

    assertThat((List<String>) info.getValue("deps")).containsExactly("@@//:initializer_default");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void initializer_returningNoneSetsDefault() throws Exception {
    scratch.file(
        "BUILD", //
        "filegroup(name = 'initializer_default')",
        "filegroup(name = 'attr_default')");
    scratch.file(
        "initializer_testing/b.bzl",
        """
        MyInfo = provider()

        def initializer(name, deps = ["//:initializer_default"]):
            return {"deps": None}

        def impl(ctx):
            return [MyInfo(
                deps = [str(d.label) for d in ctx.attr.deps],
            )]

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "deps": attr.label_list(default = ["//:attr_default"]),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//initializer_testing:my_target");
    StructImpl info =
        (StructImpl)
            myTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing:b.bzl")), "MyInfo"));

    assertThat((List<String>) info.getValue("deps")).containsExactly("@@//:attr_default");
  }

  @Test
  public void initializer_omittedValueIsNotPassed() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        MyInfo = provider()

        def initializer(name, srcs):
            return {"srcs": srcs}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(name = "my_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    // TODO: b/298561048 - Fix error messages to match a rule without initializer
    ev.assertContainsError("initializer() missing 1 required positional argument: srcs");
  }

  @Test
  public void initializer_noneValueIsNotPassed() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        MyInfo = provider()

        def initializer(name, srcs):
            return {"srcs": srcs}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = None,
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError("initializer() missing 1 required positional argument: srcs");
  }

  @Test
  public void initializer_incorrectReturnType() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(name, srcs = []):
            return [srcs]

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError("got list for 'rule's initializer return value', want dict");
  }

  @Test
  public void initializer_incorrectReturnDicts() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(name, srcs = []):
            return {True: srcs}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError("got dict<bool, list> for 'rule's initializer return value', want dict");
  }

  @Test
  public void initializer_failsSettingBaseAttribute() throws Exception {
    // 'args' is an attribute defined for all executable rules
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(name, srcs = [], deps = []):
            return {"srcs": srcs, "deps": deps, "args": ["a"]}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            executable = True,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError("Initializer can only set Starlark defined attributes, not 'args'");
  }

  @Test
  public void initializer_failsSettingPrivateAttribute_outsideBuiltins() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(name, srcs = [], deps = []):
            return {"srcs": srcs, "_tool": ":my_tool"}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "_tool": attr.label(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        filegroup(name = "my_tool")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError("file '//initializer_testing:b.bzl' cannot use private API");
  }

  @Test
  public void initializer_settingPrivateAttribute_insideBuiltins() throws Exception {
    // Because it's hard to test something that needs to be in builtins,
    // this is also allowed in a special testing location: {@link
    // StarlarkRuleClassFunctions.ALLOWLIST_RULE_EXTENSION_API_EXPERIMENTAL}
    scratch.file("initializer_testing/builtins/BUILD", "filegroup(name='my_tool')");
    scratch.file(
        "initializer_testing/builtins/b.bzl",
        """
        def initializer(name, srcs = [], deps = []):
            return {"srcs": srcs, "_tool": ":my_tool"}

        MyInfo = provider()

        def impl(ctx):
            return MyInfo(_tool = str(ctx.attr._tool.label))

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "_tool": attr.label(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load("//initializer_testing/builtins:b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//initializer_testing:my_target");
    StructImpl info =
        (StructImpl)
            myTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing/builtins:b.bzl")),
                    "MyInfo"));

    assertThat(info.getValue("_tool").toString())
        .isEqualTo("@@//initializer_testing/builtins:my_tool");
  }

  @Test
  public void initializer_failsSettingUnknownAttr() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(name, srcs = [], deps = []):
            return {"srcs": srcs, "my_deps": deps}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError("no such attribute 'my_deps' in 'my_rule' rule (did you mean 'deps'?)");
  }

  @Test
  public void initializer_failsCreatingAnotherRule() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        analysisMock.javaSupport().getLoadStatementForRule("java_library"),
        """
        def initializer(name, srcs = [], deps = []):
            java_library(name = "jl", srcs = ["a.java"])
            return {"srcs": srcs, "deps": deps}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError(
        "Cannot instantiate a rule when loading a .bzl file. Rules may be instantiated only in a"
            + " BUILD thread.");
  }

  @Test
  public void initializer_failsWithExistingRules() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(name, srcs = [], deps = []):
            native.existing_rules()
            return {"srcs": srcs, "deps": deps}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError(
        "existing_rules() can only be used while evaluating a BUILD file (or legacy macro), a rule"
            + " finalizer, or a WORKSPACE file");
  }

  @Test
  public void initializer_withFails() throws Exception {
    scratch.file(
        "initializer_testing/b.bzl",
        """
        def initializer(name, srcs = [], deps = []):
            fail("Fail called in initializer")
            return {"srcs": srcs, "deps": deps}

        def impl(ctx):
            pass

        my_rule = rule(
            impl,
            initializer = initializer,
            attrs = {
                "srcs": attr.label_list(allow_files = ["ml"]),
                "deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "initializer_testing/BUILD",
        """
        load(":b.bzl", "my_rule")

        my_rule(
            name = "my_target",
            srcs = ["a.ml"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//initializer_testing:my_target");

    ev.assertContainsError("Fail called in initializer");
    // TODO: b/298561048 - fix that the whole package doesn't fail if possible
    ev.assertContainsError("target 'my_target' not declared in package 'initializer_testing'");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void initializer_nativeModule() throws Exception {
    scratch.overwriteFile("MODULE.bazel", "module(name = 'my_mod', version = '1.2.3')");
    scratch.file("initializer_testing/rules/BUILD");
    scratch.file(
        "initializer_testing/rules/b.bzl",
        "MyInfo = provider()",
        "def initializer(name, **kwargs):",
        "  return {'props': {",
        "    'package_relative_label': str(native.package_relative_label(':target')),",
        "  }}",
        "def impl(ctx): ",
        "  return [MyInfo(props = ctx.attr.props)]",
        "my_rule = rule(impl,",
        "  initializer = initializer,",
        "  attrs = {",
        "    'props': attr.string_dict(),",
        "  })");
    scratch.file(
        "initializer_testing/targets/BUILD",
        "load('//initializer_testing/rules:b.bzl','my_rule')",
        "my_rule(name = 'my_target')");

    invalidatePackages();

    ConfiguredTarget myTarget = getConfiguredTarget("//initializer_testing/targets:my_target");
    StructImpl info =
        (StructImpl)
            myTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//initializer_testing/rules:b.bzl")),
                    "MyInfo"));

    assertThat((Map<String, String>) info.getValue("props"))
        .containsExactly("package_relative_label", "@@//initializer_testing/targets:target");
  }

  private void scratchParentRule(String rule, String... ruleArgs) throws IOException {
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        "ParentInfo = provider()",
        "def _impl(ctx):",
        "  return [ParentInfo()]",
        rule + " = rule(",
        "  implementation = _impl,",
        "  extendable = True,",
        "  attrs = { ",
        "    'srcs': attr.label_list(allow_files = ['.parent']),",
        "    'deps': attr.label_list(providers = [ParentInfo]),",
        "  },",
        String.join("\n", ruleArgs),
        ")");
  }

  @Test
  public void extendRule_allowlist() throws Exception {
    scratchParentRule("parent_library");
    scratch.file(
        "bar/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            return ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        """);
    scratch.file("bar/a.parent");
    scratch.file(
        "bar/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            srcs = ["a.parent"],
        )
        """);
    setBuildLanguageOptions("--noexperimental_rule_extension_api");

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//bar:my_target");

    ev.assertContainsError("Non-allowlisted attempt to use extend rule APIs.");
  }

  @Test
  public void extendRule_ccBinary() throws Exception {
    mockToolsConfig.overwrite(
        "tools/allowlists/extend_rule_allowlist/BUILD",
        """
        package_group(
            name = "extend_rule_allowlist",
            packages = ["//..."],
        )
        package_group(
            name = "extend_rule_api_allowlist",
            packages = ["//..."],
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        def _impl(ctx):
            return ctx.super()

        my_binary = rule(
            implementation = _impl,
            parent = native.cc_binary,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_binary")

        my_binary(
            name = "my_target",
            srcs = ["a.cc"],
        )
        """);

    getConfiguredTarget("//extend_rule_testing:my_target");
  }

  @Test
  public void extendRule_basicUse() throws Exception {
    scratchParentRule("parent_library"); // parent has srcs and deps attribute
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        MyInfo = provider()

        def _impl(ctx):
            return ctx.super() + [MyInfo(
                srcs = ctx.files.srcs,
                deps = ctx.attr.deps,
                runtime_deps = ctx.attr.runtime_deps,
            )]

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "runtime_deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            srcs = ["a.parent"],
            runtime_deps = [":dep"],
        )

        filegroup(name = "dep")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();
    StarlarkProvider.Key myInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing:child.bzl")),
            "MyInfo");
    StarlarkInfo myInfo = (StarlarkInfo) myTarget.get(myInfoKey);

    assertNoEvents();
    assertThat(rule.getRuleClassObject().isExecutableStarlark()).isFalse();
    assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.NORMAL);
    assertThat(
            Sequence.cast(myInfo.getValue("srcs"), Artifact.class, "srcs").stream()
                .map(Artifact::getFilename))
        .containsExactly("a.parent");
    assertThat(
            Sequence.cast(myInfo.getValue("deps"), ConfiguredTarget.class, "deps").stream()
                .map(ConfiguredTarget::getLabel)
                .map(Label::getName))
        .containsExactly();
    assertThat(
            Sequence.cast(myInfo.getValue("runtime_deps"), ConfiguredTarget.class, "runtime_deps")
                .stream()
                .map(ConfiguredTarget::getLabel)
                .map(Label::getName))
        .containsExactly("dep");
    StarlarkProvider.Key parentInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing/parent:parent.bzl")),
            "ParentInfo");
    assertThat(myTarget.get(parentInfoKey)).isNotNull();
  }

  @Test
  public void extendRule_withInitializers() throws Exception {
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        ParentInfo = provider()

        # only parents attributes
        def _parent_initializer(name, srcs, deps):
            return {"deps": deps + ["//extend_rule_testing:parent_dep"]}

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            initializer = _parent_initializer,
            extendable = True,
            attrs = {
                "srcs": attr.label_list(allow_files = [".parent"]),
                "deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        ChildInfo = provider()

        def _child_initializer(name, srcs, deps, runtime_deps = []):
            return {"deps": deps + [":child_dep"], "runtime_deps": runtime_deps + [":runtime_dep"]}

        def _impl(ctx):
            return ctx.super() + [ChildInfo(
                srcs = ctx.files.srcs,
                deps = ctx.attr.deps,
                runtime_deps = ctx.attr.runtime_deps,
            )]

        child_library = rule(
            implementation = _impl,
            initializer = _child_initializer,
            parent = parent_library,
            attrs = {
                "runtime_deps": attr.label_list(),
            },
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "child_library")

        child_library(
            name = "my_target",
            srcs = ["a.parent"],
            deps = [":dep"],
        )

        filegroup(name = "dep")

        filegroup(name = "child_dep")

        filegroup(name = "parent_dep")

        filegroup(name = "runtime_dep")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();
    StarlarkProvider.Key myInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing:child.bzl")),
            "ChildInfo");
    StarlarkInfo myInfo = (StarlarkInfo) myTarget.get(myInfoKey);

    assertNoEvents();
    assertThat(rule.getRuleClassObject().isExecutableStarlark()).isFalse();
    assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.NORMAL);
    assertThat(
            Sequence.cast(myInfo.getValue("srcs"), Artifact.class, "srcs").stream()
                .map(Artifact::getFilename))
        .containsExactly("a.parent");
    assertThat(
            Sequence.cast(myInfo.getValue("deps"), ConfiguredTarget.class, "deps").stream()
                .map(ConfiguredTarget::getLabel)
                .map(Label::getName))
        .containsExactly("dep", "child_dep", "parent_dep")
        .inOrder();
    assertThat(
            Sequence.cast(myInfo.getValue("runtime_deps"), ConfiguredTarget.class, "runtime_deps")
                .stream()
                .map(ConfiguredTarget::getLabel)
                .map(Label::getName))
        .containsExactly("runtime_dep");
    StarlarkProvider.Key parentInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing/parent:parent.bzl")),
            "ParentInfo");
    assertThat(myTarget.get(parentInfoKey)).isNotNull();
  }

  @Test
  public void extendRule_superNotCalled() throws Exception {
    scratchParentRule("parent_library"); // parent has srcs and deps attribute
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            return []

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            srcs = ["a.parent"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:my_target");

    ev.assertContainsError(
        "in my_library rule //extend_rule_testing:my_target: 'super' was not called.");
  }

  @Test
  public void extendRule_superCalledTwice() throws Exception {
    scratchParentRule("parent_library"); // parent has srcs and deps attribute
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()
            ctx.super()
            return []

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            srcs = ["a.parent"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:my_target");

    ev.assertContainsError("Error in super: 'super' called the second time.");
  }

  @Test
  public void extendRule_noParent_superCalled() throws Exception {
    scratchParentRule("parent_library"); // parent has srcs and deps attribute
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        def _impl(ctx):
            ctx.super()
            return []

        my_library = rule(
            implementation = _impl,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:my_target");

    ev.assertContainsError("Error in super: Can't use 'super' call, the rule has no parent.");
  }

  @Test
  public void extendRule_extendRuleTwice() throws Exception {
    scratchParentRule("parent_library"); // parent has srcs and deps attribute
    scratch.file(
        "extend_rule_testing/first_extension.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        MyInfo1 = provider()

        def _impl(ctx):
            return ctx.super() + [MyInfo1()]

        library_extended_once = rule(
            implementation = _impl,
            parent = parent_library,
            extendable = True,
        )
        """);
    scratch.file(
        "extend_rule_testing/second_extension.bzl",
        """
        load("//extend_rule_testing:first_extension.bzl", "library_extended_once")

        MyInfo2 = provider()

        def _impl(ctx):
            return ctx.super() + [MyInfo2()]

        library_extended_twice = rule(
            implementation = _impl,
            parent = library_extended_once,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":second_extension.bzl", "library_extended_twice")

        library_extended_twice(name = "my_target")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    StarlarkProvider.Key myInfo1Key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing:first_extension.bzl")),
            "MyInfo1");
    StarlarkProvider.Key myInfo2Key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.parseCanonicalUnchecked("//extend_rule_testing:second_extension.bzl")),
            "MyInfo2");
    StarlarkProvider.Key parentInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing/parent:parent.bzl")),
            "ParentInfo");

    assertThat(myTarget.get(myInfo1Key)).isNotNull();
    assertThat(myTarget.get(myInfo2Key)).isNotNull();
    assertThat(myTarget.get(parentInfoKey)).isNotNull();
  }

  @Test
  public void extendRule_extendRuleTwice_superNotCalled() throws Exception {
    scratchParentRule("parent_library"); // parent has srcs and deps attribute
    scratch.file(
        "extend_rule_testing/first_extension.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            # <- here we didn't call ctx.super()
            return []

        library_extended_once = rule(
            implementation = _impl,
            parent = parent_library,
            extendable = True,
        )
        """);
    scratch.file(
        "extend_rule_testing/second_extension.bzl",
        """
        load("//extend_rule_testing:first_extension.bzl", "library_extended_once")

        def _impl(ctx):
            return ctx.super()

        library_extended_twice = rule(
            implementation = _impl,
            parent = library_extended_once,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":second_extension.bzl", "library_extended_twice")

        library_extended_twice(name = "my_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:my_target");

    ev.assertContainsError(
        "in library_extended_twice rule //extend_rule_testing:my_target: in library_extended_once"
            + " rule: 'super' was not called.");
  }

  @Test
  public void ctxSuper_calledFromAspect() throws Exception {
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        def _aspect_impl(target, ctx):
            ctx.super()
            return []

        my_aspect = aspect(_aspect_impl)

        def _impl(ctx):
            pass

        my_library = rule(
            implementation = _impl,
            attrs = {"deps": attr.label_list(aspects = [my_aspect])},
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            deps = [":dep"],
        )

        filegroup(name = "dep")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:my_target");

    ev.assertContainsError("Error in super: Can't use 'super' call in an aspect.");
  }

  @Test
  public void extendRule_attributeAdditionalAspects() throws Exception {
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        ParentInfo = provider()

        def _aspect_impl(ctx, target):
            return []

        parent_aspect = aspect(_aspect_impl)

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = True,
            attrs = {
                "srcs": attr.label_list(allow_files = [".parent"]),
                "deps": attr.label_list(aspects = [parent_aspect]),
                "tool": attr.label(providers = [ParentInfo]),
            },
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _aspect_impl(ctx, target):
            return []

        my_aspect = aspect(_aspect_impl)

        def _impl(ctx):
            return ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "deps": attr.label_list(aspects = [my_aspect]),
                "tool": attr.label(aspects = [my_aspect]),
            },
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(
            name = "my_target",
            deps = [":dep"],
        )

        filegroup(name = "dep")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();
    assertNoEvents();

    assertThat(rule.getRuleClassObject().isExecutableStarlark()).isFalse();
    assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.NORMAL);
    assertThat(
            rule.getRuleClassObject().getAttributeByName("deps").getAspectClasses().stream()
                .map(AspectClass::toString))
        .containsExactly(
            "//extend_rule_testing/parent:parent.bzl%parent_aspect",
            "//extend_rule_testing:child.bzl%my_aspect");
    assertThat(
            rule.getRuleClassObject().getAttributeByName("tool").getAspectClasses().stream()
                .map(AspectClass::toString))
        .containsExactly("//extend_rule_testing:child.bzl%my_aspect");
  }

  @Test
  public void testAspectWithInvalidToolchainsAspects_fails() throws Exception {
    scratch.file(
        "pkg/def.bzl",
        """
        def _aspect_impl(target, ctx):
            return []
        my_aspect = aspect(
            implementation = _aspect_impl,
            toolchains_aspects = ["@:invalid_toolchain_label"]
        )

        def _rule_impl(ctx):
            pass
        my_rule = rule(
            implementation = _rule_impl,
            attrs = {"deps": attr.label_list(aspects = [my_aspect])})
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":def.bzl", "my_rule")
        my_rule(
            name = "t1",
            deps = [":t2"],
        )
        my_rule(
            name = "t2",
        )
        """);

    reporter.removeHandler(failFastHandler);
    ConfiguredTarget target = getConfiguredTarget("//pkg:t1");

    assertThat(target).isNull();
    assertContainsEvent(
        "Error in aspect: Unable to parse label '@:invalid_toolchain_label' in attribute"
            + " 'toolchains_aspects'");
  }

  @Test
  public void testAspectWithValidToolchainsAspects_parsesCorrectly() throws Exception {
    evalAndExport(
        ev,
        """
        def _aspect_impl(target, ctx):
            return []
        my_aspect = aspect(
            implementation = _aspect_impl,
            toolchains_aspects = ["//toolchains:type1", "//toolchains:type2", "//toolchains:type2"]
        )

        """);

    StarlarkDefinedAspect aspect = (StarlarkDefinedAspect) ev.lookup("my_aspect");
    assertThat(aspect).isNotNull();
    assertThat(aspect.getToolchainsAspects()).hasSize(2);
    assertThat(aspect.getToolchainsAspects())
        .containsExactly(
            Label.parseCanonicalUnchecked("//toolchains:type1"),
            Label.parseCanonicalUnchecked("//toolchains:type2"));
  }

  @Test
  public void extendRule_overridePrivateAttribute_fails() throws Exception {
    scratch.file(
        "extend_rule_testing/parent/BUILD", //
        "filegroup(name = 'parent_tool')");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        def _impl(ctx):
            return []

        parent_library = rule(
            implementation = _impl,
            attrs = {
                "_tool": attr.label(default = ":parent_tool"),
            },
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            return ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "_tool": attr.label(default = ":child_tool"),
            },
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")

        filegroup(name = "child_tool")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:BUILD");

    ev.assertContainsError(
        "Error in rule: attribute `_tool`: private attributes cannot be overridden.");
  }

  @Test
  public void extendRule_attributeOverrideDefault() throws Exception {
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo(deps = ctx.attr.deps, tools = [ctx.attr.tool])]

        parent_library = rule(
            implementation = _impl,
            extendable = True,
            attrs = {
                "srcs": attr.label_list(allow_files = [".parent"]),
                "deps": attr.label_list(),
                "tool": attr.label(default = ":tool_parent"),
            },
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            return ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "deps": attr.label_list(default = [":dep"]),
                "tool": attr.label(default = ":tool_child"),
            },
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")

        filegroup(name = "dep")

        filegroup(name = "tool_child")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    StarlarkProvider.Key parentInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//extend_rule_testing/parent:parent.bzl")),
            "ParentInfo");
    StarlarkInfo parentInfo = (StarlarkInfo) myTarget.get(parentInfoKey);

    assertNoEvents();
    assertThat(
            Sequence.cast(parentInfo.getValue("deps"), ConfiguredTarget.class, "deps").stream()
                .map(ConfiguredTarget::getLabel)
                .map(Label::getName))
        .containsExactly("dep");
    assertThat(
            Sequence.cast(parentInfo.getValue("tools"), ConfiguredTarget.class, "tools").stream()
                .map(ConfiguredTarget::getLabel)
                .map(Label::getName))
        .containsExactly("tool_child");
  }

  @Test
  public void extendRule_attributeCollision() throws Exception {
    // TODO b/300201845 - encapsulate parents and childs private attributes
    scratchParentRule("parent_library");
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            pass

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            attrs = {
                "srcs": attr.string(),  # srcs already defined as label_list in parent
            },
        )
        """);
    scratch.file("extend_rule_testing/BUILD", "load(':child.bzl', 'my_library')");

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:BUILD");

    ev.assertContainsError(
        "Error in rule: attribute `srcs`: Types of parent and child's attributes mismatch.");
  }

  @Test
  public void extendRule_executableMatches() throws Exception {
    scratchParentRule("parent_binary", "executable = True,");
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_binary")

        MyInfo = provider()

        def _impl(ctx):
            exec = ctx.actions.declare_file("my_exec")
            ctx.actions.write(exec, "")
            ctx.super()
            return DefaultInfo(executable = exec)

        my_binary = rule(
            implementation = _impl,
            parent = parent_binary,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_binary")

        my_binary(
            name = "my_target",
            srcs = ["a.parent"],
        )
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();

    assertNoEvents();
    assertThat(rule.getRuleClassObject().isExecutableStarlark()).isTrue();
    assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.NORMAL);
  }

  @Test
  public void extendRule_testMatches() throws Exception {
    scratchParentRule("parent_test", "test = True,");
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_test")

        MyInfo = provider()

        def _impl(ctx):
            exec = ctx.actions.declare_file("my_exec")
            ctx.actions.write(exec, "")
            ctx.super()
            return DefaultInfo(executable = exec)

        my_test = rule(
            implementation = _impl,
            parent = parent_test,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_test")

        my_test(
            name = "my_target",
            srcs = ["a.parent"],
        )
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();

    assertNoEvents();
    assertThat(rule.getRuleClassObject().isExecutableStarlark()).isTrue();
    assertThat(rule.getRuleClassObject().getRuleClassType()).isEqualTo(RuleClassType.TEST);
  }

  @Test
  public void extendRule_controlledParameters_fail() throws Exception {
    BazelEvaluationTestCase ev = new BazelEvaluationTestCase("//extend_rule_testing:child.bzl");
    ev.exec(
        "def impl():", //
        "  pass");
    ev.execAndExport("parent_library = rule(impl)");

    ev.checkEvalError(
        "Omit test parameter when extending rules.",
        "rule(impl, test = False, parent = parent_library)");
    ev.checkEvalError(
        "Omit executable parameter when extending rules.",
        "rule(impl, executable = False, parent = parent_library)");
    ev.checkEvalError(
        "output_to_genfiles are not supported when extending rules (deprecated).",
        "rule(impl, output_to_genfiles = True, parent = parent_library)");
    ev.checkEvalError(
        "host_fragments are not supported when extending rules (deprecated).",
        "rule(impl, host_fragments = ['a'], parent = parent_library)");
    ev.checkEvalError(
        "_skylark_testable is not supported when extending rules.",
        "rule(impl, _skylark_testable = True, parent = parent_library)");
    ev.checkEvalError(
        "analysis_test is not supported when extending rules.",
        "rule(impl, analysis_test = True, parent = parent_library)");

    ev.update("config", new StarlarkConfig());
    ev.checkEvalError(
        "build_setting is not supported when extending rules.",
        "rule(impl, build_setting = config.int(), parent = parent_library)");
  }

  @Test
  public void extendRule_fragments_merged() throws Exception {
    scratchParentRule(
        "parent_library", //
        "fragments = ['java']");
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        MyInfo = provider()

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            fragments = ["cc"],
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();

    assertNoEvents();
    assertThat(
            rule.getRuleClassObject()
                .getConfigurationFragmentPolicy()
                .getRequiredStarlarkFragments())
        .containsExactly("java", "cc");
  }

  private String notExtendableError(String rule) {
    return String.format(
        "The rule '%s' is not extendable. Only Starlark rules not using deprecated features (like"
            + " implicit outputs, output to genfiles) may be extended. Special rules like"
            + " analysis tests or rules using build_settings cannot be extended.",
        rule);
  }

  @Test
  public void extendRule_notExtendable() throws Exception {
    BazelEvaluationTestCase ev = new BazelEvaluationTestCase("//extend_rule_testing:child.bzl");
    ev.exec(
        "def impl():", //
        "  pass");

    ev.execAndExport("parent_library = rule(impl, output_to_genfiles = True)");
    ev.checkEvalError(notExtendableError("parent_library"), "rule(impl, parent = parent_library)");

    ev.execAndExport("parent_library = rule(impl, _skylark_testable = True)");
    ev.checkEvalError(notExtendableError("parent_library"), "rule(impl, parent = parent_library)");

    ev.execAndExport("parent_test = rule(impl, analysis_test = True)");
    ev.checkEvalError(notExtendableError("parent_test"), "rule(impl, parent = parent_test)");

    ev.update("config", new StarlarkConfig());
    ev.execAndExport("parent_library = rule(impl, build_setting = config.int())");
    ev.checkEvalError(notExtendableError("parent_library"), "rule(impl, parent = parent_library)");
  }

  @Test
  public void extendRule_nativeRule_notExtendable() throws Exception {
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = native.alias,
            fragments = ["cc"],
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:my_target");

    ev.assertContainsError("Parent needs to be a Starlark rule");
  }

  @Test
  public void extendRule_extendableAllowed() throws Exception {
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = True,
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    getConfiguredTarget("//extend_rule_testing:my_target");

    assertNoEvents();
  }

  @Test
  public void extendRule_extendableDisallowed() throws Exception {
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = False,
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:my_target");

    ev.assertContainsError("The rule 'parent_library' is not extendable.");
  }

  @Test
  public void extendRule_extendableAllowlisted() throws Exception {
    scratch.file(
        "extend_rule_testing/parent/BUILD",
        """
        package_group(
            name = "allowlist",
            packages = ["//extend_rule_testing"],
        )
        """);
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = "//extend_rule_testing/parent:allowlist",
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);
    scratch.file(
        "not_on_allowlist/BUILD",
        """
        load("//extend_rule_testing:child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    getConfiguredTarget("//extend_rule_testing:my_target");
    getConfiguredTarget("//not_on_allowlist:my_target");

    assertNoEvents();
  }

  @Test
  public void extendRule_extendableAllowlistDenied() throws Exception {
    scratch.file(
        "extend_rule_testing/parent/BUILD",
        """
        package_group(
            name = "allowlist",
            packages = [],
        )
        """);
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
            extendable = "//extend_rule_testing/parent:allowlist",
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//extend_rule_testing:my_target");

    ev.assertContainsError("Non-allowlisted attempt to extend a rule.");
  }

  @Test
  public void extendRule_extendableDefault() throws Exception {
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        ParentInfo = provider()

        def _impl(ctx):
            return [ParentInfo()]

        parent_library = rule(
            implementation = _impl,
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_library")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    if (!analysisMock.isThisBazel()) {
      reporter.removeHandler(failFastHandler);
      reporter.addHandler(ev.getEventCollector());
    }

    getConfiguredTarget("//extend_rule_testing:my_target");

    if (analysisMock.isThisBazel()) {
      assertNoEvents();
    } else {
      ev.assertContainsError("Non-allowlisted attempt to extend a rule.");
    }
  }

  @Test
  public void extendRule_toolchains_merged() throws Exception {
    scratchParentRule(
        "parent_library", //
        "toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']");
    scratch.file(
        "extend_rule_testing/child.bzl",
        "load('//extend_rule_testing/parent:parent.bzl', 'parent_library')",
        "MyInfo = provider()",
        "def _impl(ctx):",
        "  ctx.super()",
        "my_library = rule(",
        "  implementation = _impl,",
        "  parent = parent_library,",
        "  toolchains = ['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "']",
        ")");
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();

    assertNoEvents();
    assertThat(
            rule.getRuleClassObject().getToolchainTypes().stream()
                .map(ToolchainTypeRequirement::toolchainType)
                .map(Label::toString))
        .containsExactly(TestConstants.JAVA_TOOLCHAIN_TYPE, TestConstants.CPP_TOOLCHAIN_TYPE);
  }

  @Test
  public void extendRule_advertisedProviders_merged() throws Exception {
    scratchParentRule(
        "parent_library", //
        "provides = [ParentInfo]");
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "ParentInfo", "parent_library")

        MyInfo = provider()

        def _impl(ctx):
            ctx.super()
            return [MyInfo(), ParentInfo()]

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            provides = [MyInfo],
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();

    assertNoEvents();
    assertThat(
            rule.getRuleClassObject().getAdvertisedProviders().getStarlarkProviders().stream()
                .map(StarlarkProviderIdentifier::getKey)
                .map(key -> ((StarlarkProvider.Key) key).getExportedName()))
        .containsExactly("MyInfo", "ParentInfo");
  }

  @Test
  public void extendRule_execCompatibleWith_merged() throws Exception {
    String constr1 = TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64";
    String constr2 = TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux";
    scratchParentRule(
        "parent_library", //
        "exec_compatible_with = ['" + constr1 + "']");
    scratch.file(
        "extend_rule_testing/child.bzl",
        "load('//extend_rule_testing/parent:parent.bzl', 'parent_library', 'ParentInfo')",
        "MyInfo = provider()",
        "def _impl(ctx):",
        "  ctx.super()",
        "  return [MyInfo(), ParentInfo()]",
        "my_library = rule(",
        "  implementation = _impl,",
        "  parent = parent_library,",
        "  exec_compatible_with = ['" + constr2 + "']",
        ")");
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();

    assertNoEvents();
    assertThat(rule.getRuleClassObject().getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseCanonicalUnchecked(constr1), Label.parseCanonicalUnchecked(constr2));
  }

  @Test
  public void extendRule_execGroups_merged() throws Exception {
    scratchParentRule(
        "parent_library", //
        "exec_groups = {'parent_exec_group': exec_group()}");
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "ParentInfo", "parent_library")

        MyInfo = provider()

        def _impl(ctx):
            ctx.super()
            return [MyInfo(), ParentInfo()]

        my_library = rule(
            implementation = _impl,
            parent = parent_library,
            exec_groups = {"child_exec_group": exec_group()},
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//extend_rule_testing:my_target");
    Rule rule = getRuleContext(myTarget).getRule();

    assertNoEvents();
    assertThat(rule.getRuleClassObject().getExecGroups().keySet())
        .containsExactly("parent_exec_group", "child_exec_group");
  }

  private void scratchStarlarkTransition() throws IOException {
    if (!TestConstants.PRODUCT_NAME.equals("bazel")) {
      scratch.overwriteFile(
          TestConstants.TOOLS_REPOSITORY_SCRATCH
              + "tools/allowlists/function_transition_allowlist/BUILD",
          """
          package_group(
              name = "function_transition_allowlist",
              packages = [
                  # Allow all packages for testing.
                  "//...",
              ],
          )
          """);
    }
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")

        string_flag(
            name = "parent-flag",
            build_setting_default = "default-parent",
        )

        string_flag(
            name = "parent-child-flag",
            build_setting_default = "default-parent-child",
        )

        string_flag(
            name = "child-flag",
            build_setting_default = "child-default",
        )
        """);
    scratch.file(
        "test/transitions.bzl",
        """
        def _parent_trans_impl(settings, attr):
            return {
                "//test:parent-flag": "parent-changed",
                "//test:parent-child-flag": "parent-child-changed-in-parent",
            }

        parent_transition = transition(
            implementation = _parent_trans_impl,
            inputs = [],
            outputs = ["//test:parent-flag", "//test:parent-child-flag"],
        )

        def _child_trans_impl(settings, attr):
            return {
                "//test:child-flag": "child-changed",
                "//test:parent-child-flag": "parent-child-changed-in-child",
            }

        child_transition = transition(
            implementation = _child_trans_impl,
            inputs = [],
            outputs = ["//test:child-flag", "//test:parent-child-flag"],
        )
        """);
  }

  @Test
  public void extendRule_cfg_fromParent() throws Exception {
    scratchStarlarkTransition();
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        load("//test:transitions.bzl", "parent_transition")

        def _impl(ctx):
            pass

        parent_rule = rule(
            implementation = _impl,
            extendable = True,
            cfg = parent_transition,
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_rule")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_rule,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    BuildConfigurationValue configuration =
        getConfiguration(getConfiguredTarget("//extend_rule_testing:my_target"));

    var options = configuration.getOptions().getStarlarkOptions();
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-flag")))
        .isEqualTo("parent-changed");
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-child-flag")))
        .isEqualTo("parent-child-changed-in-parent");
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:child-flag"))).isNull();
  }

  @Test
  public void extendRule_cfg_onChild() throws Exception {
    scratchStarlarkTransition();
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        def _impl(ctx):
            pass

        parent_rule = rule(
            implementation = _impl,
            extendable = True,
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_rule")
        load("//test:transitions.bzl", "child_transition")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_rule,
            cfg = child_transition,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    BuildConfigurationValue configuration =
        getConfiguration(getConfiguredTarget("//extend_rule_testing:my_target"));

    var options = configuration.getOptions().getStarlarkOptions();
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-flag"))).isNull();
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-child-flag")))
        .isEqualTo("parent-child-changed-in-child");
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:child-flag")))
        .isEqualTo("child-changed");
  }

  @Test
  public void extendRule_cfg_onChildAndFromParent() throws Exception {
    scratchStarlarkTransition();
    scratch.file("extend_rule_testing/parent/BUILD");
    scratch.file(
        "extend_rule_testing/parent/parent.bzl",
        """
        load("//test:transitions.bzl", "parent_transition")

        def _impl(ctx):
            pass

        parent_rule = rule(
            implementation = _impl,
            extendable = True,
            cfg = parent_transition,
        )
        """);
    scratch.file(
        "extend_rule_testing/child.bzl",
        """
        load("//extend_rule_testing/parent:parent.bzl", "parent_rule")
        load("//test:transitions.bzl", "child_transition")

        def _impl(ctx):
            ctx.super()

        my_library = rule(
            implementation = _impl,
            parent = parent_rule,
            cfg = child_transition,
        )
        """);
    scratch.file(
        "extend_rule_testing/BUILD",
        """
        load(":child.bzl", "my_library")

        my_library(name = "my_target")
        """);

    BuildConfigurationValue configuration =
        getConfiguration(getConfiguredTarget("//extend_rule_testing:my_target"));

    var options = configuration.getOptions().getStarlarkOptions();
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-flag")))
        .isEqualTo("parent-changed");
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:parent-child-flag")))
        .isEqualTo("parent-child-changed-in-parent");
    assertThat(options.get(Label.parseCanonicalUnchecked("//test:child-flag")))
        .isEqualTo("child-changed");
  }

  @Test
  public void testAnalysisTest() throws Exception {
    scratch.file(
        "p/b.bzl",
        """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro(name):
            testing.analysis_test(name = name, implementation = impl)
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        """);

    getConfiguredTarget("//p:my_test_target");

    assertNoEvents();
  }

  @Test
  public void testAnalysisTestAttrs() throws Exception {
    scratch.file(
        "p/b.bzl",
        """
        def impl(ctx):
            ctx.attr.target_under_test
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro(name):
            native.filegroup(name = "my_subject", srcs = [])
            testing.analysis_test(
                name = name,
                implementation = impl,
                attrs = {"target_under_test": attr.label_list()},
                attr_values = {"target_under_test": [":my_subject"]},
            )
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        """);

    getConfiguredTarget("//p:my_test_target");

    assertNoEvents();
  }

  /** Tests two analysis_test calls with same name. */
  @Test
  public void testAnalysisTestDuplicateName_samePackage() throws Exception {
    scratch.file(
        "p/a.bzl",
        """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro1(name):
            testing.analysis_test(name = name, implementation = impl)
        """);
    scratch.file(
        "p/b.bzl",
        """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro2(name):
            testing.analysis_test(name = name, implementation = impl)
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":a.bzl", "my_test_macro1")
        load(":b.bzl", "my_test_macro2")

        my_test_macro1(name = "my_test_target")

        my_test_macro2(name = "my_test_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//p:my_test_target");

    ev.assertContainsError(
        "Error in analysis_test: my_test_target_test rule 'my_test_target' conflicts with existing"
            + " my_test_target_test rule");
  }

  // Regression test for b/291752414 (Digest for Starlark-defined rules is wrong for analysis_test).
  @Test
  public void testAnalysisTestDuplicateName_differentAttrs_differentPackage() throws Exception {
    scratch.file("p/BUILD");
    scratch.file(
        "p/make.bzl",
        """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def make(name, additional_string_attr_name):
            testing.analysis_test(
                name = name,
                implementation = impl,
                attrs = {additional_string_attr_name: attr.string()},
                attr_values = {additional_string_attr_name: "whatever"},
            )
        """);
    scratch.file(
        "p1/BUILD",
        """
        load("//p:make.bzl", "make")

        make(
            name = "my_test_target",
            additional_string_attr_name = "p1",
        )
        """);
    scratch.file(
        "p2/BUILD",
        """
        load("//p:make.bzl", "make")

        make(
            name = "my_test_target",
            additional_string_attr_name = "p2",
        )
        """);
    scratch.file(
        "s/BUILD", //
        "test_suite(name = 'suite', tests = ['//p1:my_test_target', '//p2:my_test_target'])");

    // Confirm we can [transitively] analyze both targets together without errors.
    getConfiguredTarget("//s:suite");

    // Also confirm the definition environment digests differ for the rule classes synthesized under
    // the hood for these two targets.
    Rule p1Target =
        (Rule)
            getPackageManager()
                .getTarget(ev.getEventHandler(), Label.parseCanonical("//p1:my_test_target"));
    Rule p2Target =
        (Rule)
            getPackageManager()
                .getTarget(ev.getEventHandler(), Label.parseCanonical("//p2:my_test_target"));
    assertThat(p1Target.getRuleClassObject().getRuleDefinitionEnvironmentDigest())
        .isNotEqualTo(p2Target.getRuleClassObject().getRuleDefinitionEnvironmentDigest());
  }

  /**
   * Tests analysis_test call with a name that is not Starlark identifier (but still a good target
   * name).
   */
  @Test
  public void testAnalysisTestBadName() throws Exception {
    scratch.file(
        "p/b.bzl",
        """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro(name):
            testing.analysis_test(name = name, implementation = impl)
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my+test+target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//p:my+test+target");

    ev.assertContainsError(
        "Error in analysis_test: 'name' is limited to Starlark identifiers, got my+test+target");
  }

  @Test
  public void testAnalysisTestBadArgs() throws Exception {
    scratch.file(
        "p/b.bzl",
        """
        def impl(ctx):
            return [AnalysisTestResultInfo(
                success = True,
                message = "",
            )]

        def my_test_macro(name):
            testing.analysis_test(
                name = name,
                implementation = impl,
                attr_values = {"notthere": []},
            )
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//p:my_test_target");

    ev.assertContainsError("no such attribute 'notthere' in 'my_test_target_test' rule");
  }

  @Test
  public void testAnalysisTestErrorOnExport() throws Exception {
    scratch.file(
        "p/b.bzl",
        "def impl(ctx): ",
        "  return  [AnalysisTestResultInfo(",
        "    success = True,",
        "    message = ''",
        "  )]",
        "def my_test_macro(name):",
        "  testing.analysis_test(name = name, implementation = impl, attrs = {'name':"
            + " attr.string()})");
    scratch.file(
        "p/BUILD",
        """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//p:my_test_target");

    ev.assertContainsError(
        "Error in analysis_test: attribute `name`: built-in attributes cannot be overridden");
  }

  @Test
  public void testAnalysisTestErrorOverridingName() throws Exception {
    scratch.file(
        "p/b.bzl",
        "def impl(ctx): ",
        "  return  [AnalysisTestResultInfo(",
        "    success = True,",
        "    message = ''",
        "  )]",
        "def my_test_macro(name):",
        "  testing.analysis_test(name = name, implementation = impl, attr_values = {'name':"
            + " 'override'})");
    scratch.file(
        "p/BUILD",
        """
        load(":b.bzl", "my_test_macro")

        my_test_macro(name = "my_test_target")
        """);

    reporter.removeHandler(failFastHandler);
    reporter.addHandler(ev.getEventCollector());
    getConfiguredTarget("//p:override");

    ev.assertContainsError(
        "Error in analysis_test: 'name' cannot be set or overridden in 'attr_values'");
  }

  private Object eval(Module module, String... lines) throws Exception {
    ParserInput input = ParserInput.fromLines(lines);
    return Starlark.eval(input, FileOptions.DEFAULT, module, ev.getStarlarkThread());
  }

  @Test
  public void testLabelWithStrictVisibility() throws Exception {
    RepositoryName currentRepo = RepositoryName.createUnvalidated("module+1.2.3");
    RepositoryName otherRepo = RepositoryName.createUnvalidated("dep+4.5");
    Label bzlLabel =
        Label.create(
            PackageIdentifier.create(currentRepo, PathFragment.create("lib")), "label.bzl");
    Object clientData =
        BazelModuleContext.create(
            BazelModuleKey.createFakeModuleKeyForTesting(bzlLabel),
            RepositoryMapping.create(
                ImmutableMap.of("my_module", currentRepo, "dep", otherRepo), currentRepo),
            "lib/label.bzl",
            /* loads= */ ImmutableList.of(),
            /* bzlTransitiveDigest= */ new byte[0]);
    Module module =
        Module.withPredeclaredAndData(
            StarlarkSemantics.DEFAULT,
            StarlarkGlobalsImpl.INSTANCE.getFixedBzlToplevels(),
            clientData);

    assertThat(eval(module, "Label('//foo:bar').workspace_root"))
        .isEqualTo("external/module+1.2.3");
    assertThat(eval(module, "Label('@my_module//foo:bar').workspace_root"))
        .isEqualTo("external/module+1.2.3");
    assertThat(eval(module, "Label('@@module+1.2.3//foo:bar').workspace_root"))
        .isEqualTo("external/module+1.2.3");
    assertThat(eval(module, "Label('@dep//foo:bar').workspace_root")).isEqualTo("external/dep+4.5");
    assertThat(eval(module, "Label('@@dep+4.5//foo:bar').workspace_root"))
        .isEqualTo("external/dep+4.5");
    assertThat(eval(module, "Label('@@//foo:bar').workspace_root")).isEqualTo("");

    assertThat(eval(module, "str(Label('@@//foo:bar'))")).isEqualTo("@@//foo:bar");
    assertThat(
            assertThrows(
                EvalException.class, () -> eval(module, "Label('@//foo:bar').workspace_name")))
        .hasMessageThat()
        .isEqualTo(
            "'workspace_name' is not allowed on invalid Label @@[unknown repo '' requested from"
                + " @@module+1.2.3]//foo:bar");
    assertThat(
            assertThrows(
                EvalException.class, () -> eval(module, "Label('@//foo:bar').workspace_root")))
        .hasMessageThat()
        .isEqualTo(
            "'workspace_root' is not allowed on invalid Label @@[unknown repo '' requested from"
                + " @@module+1.2.3]//foo:bar");
  }

  @Test
  public void testPackageMetadataAttrOnAllRules() throws Exception {
    scratch.file(
        "p/b.bzl",
        """
        def _my_rule_impl(ctx):
            license_file = ctx.attr.package_metadata[0][DefaultInfo].files.to_list()[0]
            print("ctx.attr.package_metadata: %s" % license_file.path)

        my_rule = rule(_my_rule_impl)
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":b.bzl", "my_rule")

        filegroup(
            name = "licenses",
            srcs = ["LICENSE"],
        )

        my_rule(
            name = "my_target",
            package_metadata = [":licenses"],
        )
        """);

    getConfiguredTarget("//p:my_target");

    assertContainsEvent("ctx.attr.package_metadata: p/LICENSE");
  }

  @Test
  public void testLabelListComputedDefaultChecksElementTypes() throws Exception {
    scratch.file(
        "p/a.bzl",
        """
        def _compute():
            return ["this is a string"]
        my_rule = rule(lambda ctx: [], attrs = {"_a" : attr.label_list(default = _compute)})
        """);
    scratch.file(
        "p/BUILD",
        """
        load(":a.bzl", "my_rule")
        my_rule(name = "bad")
        """);

    AssertionError error = assertThrows(AssertionError.class, () -> getConfiguredTarget("//p:bad"));

    assertThat(error).hasMessageThat().contains("expected 'label', but got 'string'");
  }

  @Test
  public void starlarkRuleFunctionCodec() throws Exception {
    scratch.file("lib/BUILD");
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(ctx):
            print("xyz is %s" % ctx.attr.xyz)
        my_rule = rule(
            implementation=_impl,
            attrs = {
              "xyz": attr.string(),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_rule")
        my_rule(
            name = "abc",
            xyz = "value",
        )
        """);

    // Evaluates pkg to populate my_rule in Skyframe.
    assertThat(getPackage("pkg")).isNotNull();

    // Pulls my_rule's value out of Skyframe from its BzlLoadValue.
    BzlLoadValue.Key bzlLoadKey = keyForBuild(Label.parseCanonical("//pkg:foo.bzl"));
    var fooBzl = (BzlLoadValue) getDoneValue(bzlLoadKey);
    var myRule = (StarlarkRuleFunction) checkNotNull(fooBzl.getModule().getGlobal("my_rule"));

    var deserialized = RoundTripping.roundTripWithSkyframe(this::getDoneValue, myRule);
    assertThat(myRule).isSameInstanceAs(deserialized);
  }

  private SkyValue getDoneValue(SkyKey key) {
    try {
      return skyframeExecutor.getDoneSkyValueForIntrospection(key);
    } catch (SkyframeExecutor.FailureToRetrieveIntrospectedValueException e) {
      throw new AssertionError(e);
    }
  }
}
