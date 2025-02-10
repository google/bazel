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

package com.google.devtools.build.lib.starlarkdocextract;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static com.google.devtools.build.lib.starlarkdocextract.ModuleInfoExtractor.IMPLICIT_MACRO_ATTRIBUTES;
import static com.google.devtools.build.lib.starlarkdocextract.RuleInfoExtractor.IMPLICIT_RULE_ATTRIBUTES;
import static com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_KWARGS;
import static com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_ORDINARY;
import static com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_VARARGS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.starlark.util.BazelEvaluationTestCase;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AspectInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeType;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionDeprecationInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionReturnInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.MacroInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ModuleInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.OriginKey;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ProviderFieldInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ProviderInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ProviderNameGroup;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.RuleInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.StarlarkFunctionInfo;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.starlark.java.eval.Module;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ModuleInfoExtractorTest {

  private String fakeLabelString = null; // set by exec()

  private Module exec(String... lines) throws Exception {
    return execWithOptions(ImmutableList.of(), lines);
  }

  private Module execWithOptions(ImmutableList<String> options, String... lines) throws Exception {
    BazelEvaluationTestCase ev = new BazelEvaluationTestCase();
    ev.setSemantics(options.toArray(new String[0]));
    Module module = ev.getModule();
    Label fakeLabel = BazelModuleContext.of(module).label();
    ev.setThreadOwner(keyForBuild(fakeLabel));
    fakeLabelString = fakeLabel.getCanonicalForm();
    ParserInput input = ParserInput.fromLines(lines);
    StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);
    Program program = Program.compileFile(file, module);
    BzlLoadFunction.execAndExport(
        program, fakeLabel, ev.getEventHandler(), module, ev.getStarlarkThread());
    return ev.getModule();
  }

  private static ModuleInfoExtractor getExtractor() {
    RepositoryMapping repositoryMapping = RepositoryMapping.ALWAYS_FALLBACK;
    return new ModuleInfoExtractor(
        name -> true, new LabelRenderer(repositoryMapping, Optional.empty()));
  }

  private static ModuleInfoExtractor getExtractor(Predicate<String> isWantedQualifiedName) {
    RepositoryMapping repositoryMapping = RepositoryMapping.ALWAYS_FALLBACK;
    return new ModuleInfoExtractor(
        isWantedQualifiedName, new LabelRenderer(repositoryMapping, Optional.empty()));
  }

  private static ModuleInfoExtractor getExtractor(
      RepositoryMapping repositoryMapping, String mainRepoName) {
    return new ModuleInfoExtractor(
        name -> true, new LabelRenderer(repositoryMapping, Optional.of(mainRepoName)));
  }

  @Test
  public void moduleDocstring() throws Exception {
    Module moduleWithDocstring = exec("'''This is my docstring'''", "foo = 1");
    assertThat(getExtractor().extractFrom(moduleWithDocstring).getModuleDocstring())
        .isEqualTo("This is my docstring");

    Module moduleWithoutDocstring = exec("foo = 1");
    assertThat(getExtractor().extractFrom(moduleWithoutDocstring).getModuleDocstring()).isEmpty();
  }

  @Test
  public void extractOnlyWantedLoadablePublicNames() throws Exception {
    Module module =
        exec(
            """
            def loadable_unwanted():
                pass

            def loadable_wanted():
                pass

            def _nonloadable():
                pass

            def _nonloadable_matches_wanted_predicate():
                pass

            def _f():
                pass

            def _g():
                pass

            def _h():
                pass

            namespace = struct(
                public_field_wanted = _f,
                public_field_unwanted = _g,
                _hidden_field_matches_wanted_predicate = _h,
            )
            """);

    ModuleInfo moduleInfo = getExtractor(name -> name.contains("_wanted")).extractFrom(module);
    assertThat(moduleInfo.getFuncInfoList().stream().map(StarlarkFunctionInfo::getFunctionName))
        .containsExactly("loadable_wanted", "namespace.public_field_wanted");
  }

  @Test
  public void namespacedEntities() throws Exception {
    Module module =
        exec(
            """
            def _my_func(**kwargs):
                pass

            _my_binary = rule(implementation = _my_func)
            _my_aspect = aspect(implementation = _my_func)
            _MyInfo = provider()
            name = struct(
                spaced = struct(
                    my_func = _my_func,
                    my_binary = _my_binary,
                    my_aspect = _my_aspect,
                    MyInfo = _MyInfo,
                ),
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getFuncInfoList().stream().map(StarlarkFunctionInfo::getFunctionName))
        .containsExactly("name.spaced.my_func");
    assertThat(
            moduleInfo.getFuncInfoList().stream()
                .map(StarlarkFunctionInfo::getOriginKey)
                .map(OriginKey::getName))
        .containsExactly("_my_func");

    assertThat(moduleInfo.getRuleInfoList().stream().map(RuleInfo::getRuleName))
        .containsExactly("name.spaced.my_binary");
    assertThat(
            moduleInfo.getRuleInfoList().stream()
                .map(RuleInfo::getOriginKey)
                .map(OriginKey::getName))
        .containsExactly("_my_binary");

    assertThat(moduleInfo.getAspectInfoList().stream().map(AspectInfo::getAspectName))
        .containsExactly("name.spaced.my_aspect");
    assertThat(
            moduleInfo.getAspectInfoList().stream()
                .map(AspectInfo::getOriginKey)
                .map(OriginKey::getName))
        .containsExactly("_my_aspect");

    assertThat(moduleInfo.getProviderInfoList().stream().map(ProviderInfo::getProviderName))
        .containsExactly("name.spaced.MyInfo");
    assertThat(
            moduleInfo.getProviderInfoList().stream()
                .map(ProviderInfo::getOriginKey)
                .map(OriginKey::getName))
        .containsExactly("_MyInfo");
  }

  @Test
  public void isWantedQualifiedName_appliesToQualifiedNamePrefixes() throws Exception {
    Module module =
        exec(
            """
            def _f():
                pass

            def _g():
                pass

            def _h():
                pass

            def _i():
                pass

            def _j():
                pass

            foo = struct(
                bar = struct(
                    f = _f,
                ),
                baz = struct(
                    g = _g,
                ),
                h = _h,
            )
            baz = struct(
                qux = struct(
                    i = _i,
                ),
                j = _j,
            )
            """);

    ModuleInfo moduleInfo =
        getExtractor(name -> name.equals("foo.bar") || name.equals("baz")).extractFrom(module);
    assertThat(moduleInfo.getFuncInfoList().stream().map(StarlarkFunctionInfo::getFunctionName))
        .containsExactly("foo.bar.f", "baz.qux.i", "baz.j");
  }

  @Test
  public void functionDocstring() throws Exception {
    Module module =
        exec(
            """
            def with_detailed_docstring():
                '''My function

                This function does things.
                '''
                pass

            def with_one_line_docstring():
                '''My function'''
                pass

            def without_docstring():
                pass
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getFuncInfoList())
        .containsExactly(
            StarlarkFunctionInfo.newBuilder()
                .setFunctionName("with_detailed_docstring")
                .setDocString("My function\n\nThis function does things.")
                .setOriginKey(
                    OriginKey.newBuilder()
                        .setName("with_detailed_docstring")
                        .setFile(fakeLabelString))
                .build(),
            StarlarkFunctionInfo.newBuilder()
                .setFunctionName("with_one_line_docstring")
                .setDocString("My function")
                .setOriginKey(
                    OriginKey.newBuilder()
                        .setName("with_one_line_docstring")
                        .setFile(fakeLabelString))
                .build(),
            StarlarkFunctionInfo.newBuilder()
                .setFunctionName("without_docstring")
                .setOriginKey(
                    OriginKey.newBuilder().setName("without_docstring").setFile(fakeLabelString))
                .build());
  }

  @Test
  public void functionParams() throws Exception {
    Module module =
        exec(
            """
            def my_func(documented, undocumented, has_default = {"foo": "bar"}, *args, **kwargs):
                '''My function

                Args:
                  documented: Documented param
                '''
                pass
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getFuncInfoList().get(0).getParameterList())
        .containsExactly(
            FunctionParamInfo.newBuilder()
                .setName("documented")
                .setRole(PARAM_ROLE_ORDINARY)
                .setDocString("Documented param")
                .setMandatory(true)
                .build(),
            FunctionParamInfo.newBuilder()
                .setName("undocumented")
                .setRole(PARAM_ROLE_ORDINARY)
                .setMandatory(true)
                .build(),
            FunctionParamInfo.newBuilder()
                .setName("has_default")
                .setRole(PARAM_ROLE_ORDINARY)
                .setDefaultValue("{\"foo\": \"bar\"}")
                .build(),
            FunctionParamInfo.newBuilder().setName("args").setRole(PARAM_ROLE_VARARGS).build(),
            FunctionParamInfo.newBuilder().setName("kwargs").setRole(PARAM_ROLE_KWARGS).build())
        .inOrder();
  }

  @Test
  public void functionReturn() throws Exception {
    Module module =
        exec(
            """
            def with_return():
                '''My doc

                Returns:
                  None
                '''
                return None

            def without_return():
                '''My doc'''
                pass
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getFuncInfoList())
        .ignoringFields(StarlarkFunctionInfo.ORIGIN_KEY_FIELD_NUMBER)
        .containsExactly(
            StarlarkFunctionInfo.newBuilder()
                .setFunctionName("with_return")
                .setDocString("My doc")
                .setReturn(FunctionReturnInfo.newBuilder().setDocString("None").build())
                .build(),
            StarlarkFunctionInfo.newBuilder()
                .setFunctionName("without_return")
                .setDocString("My doc")
                .build());
  }

  @Test
  public void functionDeprecated() throws Exception {
    Module module =
        exec(
            """
            def with_deprecated():
                '''My doc

                Deprecated:
                  This is deprecated
                '''
                pass

            def without_deprecated():
                '''My doc'''
                pass
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getFuncInfoList())
        .ignoringFields(StarlarkFunctionInfo.ORIGIN_KEY_FIELD_NUMBER)
        .containsExactly(
            StarlarkFunctionInfo.newBuilder()
                .setFunctionName("with_deprecated")
                .setDocString("My doc")
                .setDeprecated(
                    FunctionDeprecationInfo.newBuilder().setDocString("This is deprecated").build())
                .build(),
            StarlarkFunctionInfo.newBuilder()
                .setFunctionName("without_deprecated")
                .setDocString("My doc")
                .build());
  }

  @Test
  public void unexportedLambdaFunction() throws Exception {
    Module module =
        exec(
            """
            s = struct(
                lambda_function = lambda x: x * 2,
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getFuncInfoList())
        .containsExactly(
            StarlarkFunctionInfo.newBuilder()
                // Note that origin key name is unset
                .setOriginKey(OriginKey.newBuilder().setFile(fakeLabelString))
                .setFunctionName("s.lambda_function")
                .addParameter(
                    FunctionParamInfo.newBuilder()
                        .setName("x")
                        .setRole(PARAM_ROLE_ORDINARY)
                        .setMandatory(true))
                .build());
  }

  @Test
  public void unexportedGeneratedFunction() throws Exception {
    Module module =
        exec(
            """
            def _multiply_by(y):
                def multiply(x):
                    '''Multiplies x by constant y'''
                    return x * y
                return multiply

            s = struct(
                generated = _multiply_by(2),
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getFuncInfoList())
        .containsExactly(
            StarlarkFunctionInfo.newBuilder()
                // Note that origin key name is unset
                .setOriginKey(OriginKey.newBuilder().setFile(fakeLabelString))
                .setFunctionName("s.generated")
                .setDocString("Multiplies x by constant y")
                .addParameter(
                    FunctionParamInfo.newBuilder()
                        .setName("x")
                        .setRole(PARAM_ROLE_ORDINARY)
                        .setMandatory(true))
                .build());
  }

  @Test
  public void providerDocstring() throws Exception {
    Module module =
        exec(
            """
            DocumentedInfo = provider(doc = "My doc")
            UndocumentedInfo = provider()
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getProviderInfoList())
        .containsExactly(
            ProviderInfo.newBuilder()
                .setProviderName("DocumentedInfo")
                .setDocString("My doc")
                .setOriginKey(
                    OriginKey.newBuilder().setName("DocumentedInfo").setFile(fakeLabelString))
                .build(),
            ProviderInfo.newBuilder()
                .setProviderName("UndocumentedInfo")
                .setOriginKey(
                    OriginKey.newBuilder().setName("UndocumentedInfo").setFile(fakeLabelString))
                .build());
  }

  @Test
  public void providerFields() throws Exception {
    Module module =
        exec(
            // Note fields below are not alphabetized
            """
            DocumentedInfo = provider(fields = {"c": "C", "a": "A", "b": "B", "_hidden": "Hidden"})
            UndocumentedInfo = provider(fields = ["c", "a", "b", "_hidden"])
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getProviderInfoList())
        .ignoringFields(ProviderInfo.ORIGIN_KEY_FIELD_NUMBER)
        .containsExactly(
            ProviderInfo.newBuilder()
                .setProviderName("DocumentedInfo")
                .addFieldInfo(ProviderFieldInfo.newBuilder().setName("c").setDocString("C"))
                .addFieldInfo(ProviderFieldInfo.newBuilder().setName("a").setDocString("A"))
                .addFieldInfo(ProviderFieldInfo.newBuilder().setName("b").setDocString("B"))
                .build(),
            ProviderInfo.newBuilder()
                .setProviderName("UndocumentedInfo")
                .addFieldInfo(ProviderFieldInfo.newBuilder().setName("c"))
                .addFieldInfo(ProviderFieldInfo.newBuilder().setName("a"))
                .addFieldInfo(ProviderFieldInfo.newBuilder().setName("b"))
                .build());
  }

  @Test
  public void providerInit() throws Exception {
    Module module =
        exec(
            """
            def _my_info_init(x_value, y_value = 0):
                '''MyInfo constructor

                Args:
                    x_value: my x value
                    y_value: my y value
                '''
                return {"x": x_value, "y": y_value}

            _MyInfo, _new_my_info = provider(
                doc = '''My provider''',
                fields = ["x", "y"],
                init = _my_info_init,
            )

            namespace = struct(
                MyInfo = _MyInfo,
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getProviderInfoList())
        .containsExactly(
            ProviderInfo.newBuilder()
                .setProviderName("namespace.MyInfo")
                .setDocString("My provider")
                .addFieldInfo(ProviderFieldInfo.newBuilder().setName("x"))
                .addFieldInfo(ProviderFieldInfo.newBuilder().setName("y"))
                .setInit(
                    StarlarkFunctionInfo.newBuilder()
                        .setFunctionName("namespace.MyInfo")
                        .setDocString("MyInfo constructor")
                        .addParameter(
                            FunctionParamInfo.newBuilder()
                                .setName("x_value")
                                .setRole(PARAM_ROLE_ORDINARY)
                                .setDocString("my x value")
                                .setMandatory(true)
                                .build())
                        .addParameter(
                            FunctionParamInfo.newBuilder()
                                .setName("y_value")
                                .setRole(PARAM_ROLE_ORDINARY)
                                .setDocString("my y value")
                                .setDefaultValue("0")
                                .build())
                        .setOriginKey(
                            OriginKey.newBuilder()
                                .setName("_my_info_init")
                                .setFile(fakeLabelString)))
                .setOriginKey(OriginKey.newBuilder().setName("_MyInfo").setFile(fakeLabelString))
                .build());
  }

  @Test
  public void unexportedProvider_notDocumented() throws Exception {
    Module module =
        exec(
            """
            s = struct(
                MyUnexportedInfo = provider(),
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getProviderInfoList()).isEmpty();
  }

  @Test
  public void ruleDocstring() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(ctx):
                pass

            documented_lib = rule(doc = "My doc", implementation = _my_impl)
            undocumented_lib = rule(implementation = _my_impl)
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getRuleInfoList())
        .ignoringFields(RuleInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
        .containsExactly(
            RuleInfo.newBuilder()
                .setRuleName("documented_lib")
                .setDocString("My doc")
                .setOriginKey(
                    OriginKey.newBuilder().setName("documented_lib").setFile(fakeLabelString))
                .build(),
            RuleInfo.newBuilder()
                .setRuleName("undocumented_lib")
                .setOriginKey(
                    OriginKey.newBuilder().setName("undocumented_lib").setFile(fakeLabelString))
                .build());
  }

  @Test
  public void ruleAdvertisedProviders() throws Exception {
    Module module =
        exec(
            """
            MyInfo = provider()

            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                provides = [MyInfo, DefaultInfo, "LegacyStructInfo"],
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getRuleInfoList())
        .ignoringFields(RuleInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
        .containsExactly(
            RuleInfo.newBuilder()
                .setRuleName("my_lib")
                .setOriginKey(OriginKey.newBuilder().setName("my_lib").setFile(fakeLabelString))
                .setAdvertisedProviders(
                    ProviderNameGroup.newBuilder()
                        .addProviderName("MyInfo")
                        .addProviderName("DefaultInfo")
                        .addProviderName("LegacyStructInfo")
                        .addOriginKey(
                            OriginKey.newBuilder().setName("MyInfo").setFile(fakeLabelString))
                        .addOriginKey(
                            OriginKey.newBuilder().setName("DefaultInfo").setFile("<native>"))
                        .addOriginKey(OriginKey.newBuilder().setName("LegacyStructInfo")))
                .build());
  }

  @Test
  public void ruleTest() throws Exception {
    Module module =
        exec(
            """
            MyInfo = provider()

            def _my_impl(ctx):
                pass

            my_test = rule(
                implementation = _my_impl,
                test = True,
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getRuleInfoList())
        .ignoringFields(RuleInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
        .containsExactly(
            RuleInfo.newBuilder()
                .setRuleName("my_test")
                .setOriginKey(OriginKey.newBuilder().setName("my_test").setFile(fakeLabelString))
                .setTest(true)
                .setExecutable(true)
                .build());
  }

  @Test
  public void ruleExecutable() throws Exception {
    Module module =
        exec(
            """
            MyInfo = provider()

            def _my_impl(ctx):
                pass

            my_binary = rule(
                implementation = _my_impl,
                executable = True,
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getRuleInfoList())
        .ignoringFields(RuleInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
        .containsExactly(
            RuleInfo.newBuilder()
                .setRuleName("my_binary")
                .setOriginKey(OriginKey.newBuilder().setName("my_binary").setFile(fakeLabelString))
                .setExecutable(true)
                .build());
  }

  @Test
  public void ruleAttributes() throws Exception {
    Module module =
        execWithOptions(
            // TODO(https://github.com/bazelbuild/bazel/issues/6420): attr.license() is deprecated,
            // and will eventually be removed from Bazel.
            ImmutableList.of("--noincompatible_no_attr_license"),
            """
            MyInfo1 = provider()
            MyInfo2 = provider()
            MyInfo3 = provider()

            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "a": attr.string(doc = "My doc", default = "foo"),
                    "b": attr.string(mandatory = True),
                    "c": attr.label(providers = [MyInfo1, MyInfo2]),
                    "d": attr.label(providers = [[MyInfo1, MyInfo2], [MyInfo3]]),
                    "_e": attr.string(doc = "Hidden attribute"),
                    "deprecated_license": attr.license(),
                },
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getRuleInfoList().get(0).getAttributeList())
        .containsExactlyElementsIn(
            ImmutableList.builder()
                .addAll(IMPLICIT_RULE_ATTRIBUTES.values())
                .add(
                    AttributeInfo.newBuilder()
                        .setName("a")
                        .setType(AttributeType.STRING)
                        .setDocString("My doc")
                        .setDefaultValue("\"foo\"")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("b")
                        .setType(AttributeType.STRING)
                        .setMandatory(true)
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("c")
                        .setType(AttributeType.LABEL)
                        .setDefaultValue("None")
                        .addProviderNameGroup(
                            ProviderNameGroup.newBuilder()
                                .addProviderName("MyInfo1")
                                .addProviderName("MyInfo2")
                                .addOriginKey(
                                    OriginKey.newBuilder()
                                        .setName("MyInfo1")
                                        .setFile(fakeLabelString))
                                .addOriginKey(
                                    OriginKey.newBuilder()
                                        .setName("MyInfo2")
                                        .setFile(fakeLabelString)))
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("d")
                        .setType(AttributeType.LABEL)
                        .setDefaultValue("None")
                        .addProviderNameGroup(
                            ProviderNameGroup.newBuilder()
                                .addProviderName("MyInfo1")
                                .addProviderName("MyInfo2")
                                .addOriginKey(
                                    OriginKey.newBuilder()
                                        .setName("MyInfo1")
                                        .setFile(fakeLabelString))
                                .addOriginKey(
                                    OriginKey.newBuilder()
                                        .setName("MyInfo2")
                                        .setFile(fakeLabelString)))
                        .addProviderNameGroup(
                            ProviderNameGroup.newBuilder()
                                .addProviderName("MyInfo3")
                                .addOriginKey(
                                    OriginKey.newBuilder()
                                        .setName("MyInfo3")
                                        .setFile(fakeLabelString)))
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("deprecated_license")
                        .setType(AttributeType.STRING_LIST)
                        .setDefaultValue("[\"none\"]")
                        .setNonconfigurable(true)
                        .build())
                .build());
  }

  @Test
  public void attributeOrder() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "foo": attr.int(),
                    "bar": attr.int(),
                    "baz": attr.int(),
                },
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(
            moduleInfo.getRuleInfoList().get(0).getAttributeList().stream()
                .map(AttributeInfo::getName))
        .containsExactly("name", "foo", "bar", "baz")
        .inOrder();
  }

  @Test
  public void attributeTypes() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "a": attr.int(),
                    "b": attr.label(),
                    "c": attr.string(),
                    "d": attr.string_list(),
                    "e": attr.int_list(),
                    "f": attr.label_list(),
                    "g": attr.bool(),
                    "h": attr.label_keyed_string_dict(),
                    "i": attr.string_dict(),
                    "j": attr.string_list_dict(),
                    "k": attr.output(),
                    "l": attr.output_list(),
                },
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getRuleInfoList().get(0).getAttributeList())
        .containsExactlyElementsIn(
            ImmutableList.builder()
                .addAll(IMPLICIT_RULE_ATTRIBUTES.values())
                .add(
                    AttributeInfo.newBuilder()
                        .setName("a")
                        .setType(AttributeType.INT)
                        .setDefaultValue("0")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("b")
                        .setType(AttributeType.LABEL)
                        .setDefaultValue("None")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("c")
                        .setType(AttributeType.STRING)
                        .setDefaultValue("\"\"")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("d")
                        .setType(AttributeType.STRING_LIST)
                        .setDefaultValue("[]")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("e")
                        .setType(AttributeType.INT_LIST)
                        .setDefaultValue("[]")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("f")
                        .setType(AttributeType.LABEL_LIST)
                        .setDefaultValue("[]")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("g")
                        .setType(AttributeType.BOOLEAN)
                        .setDefaultValue("False")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("h")
                        .setType(AttributeType.LABEL_STRING_DICT)
                        .setDefaultValue("{}")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("i")
                        .setType(AttributeType.STRING_DICT)
                        .setDefaultValue("{}")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("j")
                        .setType(AttributeType.STRING_LIST_DICT)
                        .setDefaultValue("{}")
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("k")
                        .setType(AttributeType.OUTPUT)
                        .setDefaultValue("None")
                        .setNonconfigurable(true)
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("l")
                        .setType(AttributeType.OUTPUT_LIST)
                        .setDefaultValue("[]")
                        .setNonconfigurable(true)
                        .build())
                .build());
  }

  @Test
  public void unexportedRule_notDocumented() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(ctx):
                pass

            s = struct(
                my_rule = rule(
                    doc = "Unexported rule",
                    implementation = _my_impl,
                )
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getRuleInfoList()).isEmpty();
  }

  @Test
  public void macroDocstring() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(name, visibility):
                pass

            documented_macro = macro(
                doc = "My doc",
                implementation = _my_impl,
            )
            undocumented_macro = macro(
                implementation = _my_impl,
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getMacroInfoList())
        .containsExactly(
            MacroInfo.newBuilder()
                .setMacroName("documented_macro")
                .setDocString("My doc")
                .setOriginKey(
                    OriginKey.newBuilder().setName("documented_macro").setFile(fakeLabelString))
                .addAllAttribute(IMPLICIT_MACRO_ATTRIBUTES.values())
                .build(),
            MacroInfo.newBuilder()
                .setMacroName("undocumented_macro")
                .setOriginKey(
                    OriginKey.newBuilder().setName("undocumented_macro").setFile(fakeLabelString))
                .addAllAttribute(IMPLICIT_MACRO_ATTRIBUTES.values())
                .build());
  }

  @Test
  public void macroFinalizer() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(name, visibility):
                pass

            my_finalizer = macro(
                doc = "My finalizer",
                implementation = _my_impl,
                finalizer = True,
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getMacroInfoList())
        .containsExactly(
            MacroInfo.newBuilder()
                .setMacroName("my_finalizer")
                .setDocString("My finalizer")
                .setOriginKey(
                    OriginKey.newBuilder().setName("my_finalizer").setFile(fakeLabelString))
                .addAllAttribute(IMPLICIT_MACRO_ATTRIBUTES.values())
                .setFinalizer(true)
                .build());
  }

  @Test
  public void macroAttributes() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(name):
                pass

            my_macro = macro(
                attrs = {
                    "some_attr": attr.label(mandatory = True),
                    "another_attr": attr.int(doc = "An integer", default = 42),
                    "_implicit_attr": attr.string(default = "IMPLICIT"),
                },
                implementation = _my_impl,
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getMacroInfoList().get(0).getAttributeList())
        .containsExactlyElementsIn(
            ImmutableList.builder()
                .addAll(IMPLICIT_MACRO_ATTRIBUTES.values())
                .add(
                    AttributeInfo.newBuilder()
                        .setName("some_attr")
                        .setType(AttributeType.LABEL)
                        .setMandatory(true)
                        .build(),
                    AttributeInfo.newBuilder()
                        .setName("another_attr")
                        .setType(AttributeType.INT)
                        .setDocString("An integer")
                        .setDefaultValue("42")
                        .build())
                .build());
  }

  @Test
  public void macroInheritedAttributes() throws Exception {
    Module module =
        exec(
            """
def _my_rule_impl(ctx):
    pass

_my_rule = rule(
    implementation = _my_rule_impl,
    attrs = {
       "srcs": attr.label_list(doc = "My rule sources"),
    },
)

def _my_macro_impl(name, visibility, srcs, **kwargs):
    _my_rule(name = name, visibility = visibility, srcs = srcs, **kwargs)

my_macro = macro(
    inherit_attrs = _my_rule,
    implementation = _my_macro_impl,
)
""");
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    List<AttributeInfo> attributes = moduleInfo.getMacroInfoList().get(0).getAttributeList();
    assertThat(attributes.get(0)).isEqualTo(IMPLICIT_MACRO_ATTRIBUTES.get("name"));
    assertThat(attributes.get(1)).isEqualTo(IMPLICIT_MACRO_ATTRIBUTES.get("visibility"));
    // Starlark-defined inherited attribute
    assertThat(attributes)
        .contains(
            AttributeInfo.newBuilder()
                .setName("srcs")
                .setType(AttributeType.LABEL_LIST)
                .setDocString("My rule sources")
                .setDefaultValue("None") // Default value of inherited attributes is always None
                .build());
    // Native inherited attributes may not be documented, so ignore doc string for them.
    assertThat(attributes)
        .ignoringFields(AttributeInfo.DOC_STRING_FIELD_NUMBER)
        .contains(
            AttributeInfo.newBuilder()
                .setName("tags")
                .setType(AttributeType.STRING_LIST)
                .setDefaultValue("None") // Default value of inherited attributes is always None
                .setNonconfigurable(true)
                .setNativelyDefined(true)
                .build());
  }

  @Test
  public void unexportedMacro_notDocumented() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(name):
                pass

            s = struct(
                my_macro = macro(
                    doc = "Unexported macro",
                    implementation = _my_impl,
                )
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getMacroInfoList()).isEmpty();
  }

  @Test
  public void providerNameGroups_useFirstDocumentableProviderName() throws Exception {
    Module module =
        exec(
            """
            _MyInfo = provider()

            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "foo": attr.label(providers = [_MyInfo]),
                },
                provides = [_MyInfo],
            )
            namespace1 = struct(_MyUndocumentedInfo = _MyInfo)
            namespace2 = struct(MyInfoB = _MyInfo, MyInfoA = _MyInfo)
            namespace3 = struct(MyInfo = _MyInfo)
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getRuleInfoList().get(0).getAdvertisedProviders().getProviderName(0))
        // Struct fields are extracted in field name alphabetical order, so namespace2.MyInfoA
        // (despite being declared after namespace2.MyInfoB) wins.
        .isEqualTo("namespace2.MyInfoA");
    assertThat(
            moduleInfo
                .getRuleInfoList()
                .get(0)
                .getAttribute(1) // 0 is the implicit name attribute
                .getProviderNameGroup(0)
                .getProviderName(0))
        .isEqualTo("namespace2.MyInfoA");
    assertThat(moduleInfo.getProviderInfoList().stream().map(ProviderInfo::getProviderName))
        .containsExactly("namespace2.MyInfoA", "namespace2.MyInfoB", "namespace3.MyInfo");
    // TODO(arostovtsev): instead of producing a separate ProviderInfo message per each alias, add a
    // repeated alias name field, and produce a single ProviderInfo message listing its aliases.
  }

  @Test
  public void labelStringification() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(ctx):
                pass

            my_lib = rule(
                implementation = _my_impl,
                attrs = {
                    "label": attr.label(default = "//test:foo"),
                    "label_list": attr.label_list(
                        default = ["//x", "@canonical//y", "@canonical//y:z"],
                    ),
                    "label_keyed_string_dict": attr.label_keyed_string_dict(
                        default = {"//x": "label_in_main", "@canonical//y": "label_in_dep"},
                    ),
                },
            )
            """);
    RepositoryName canonicalName = RepositoryName.create("canonical");
    RepositoryMapping repositoryMapping =
        RepositoryMapping.create(ImmutableMap.of("local", canonicalName), RepositoryName.MAIN);
    ModuleInfo moduleInfo = getExtractor(repositoryMapping, "my_repo").extractFrom(module);
    assertThat(
            moduleInfo.getRuleInfoList().get(0).getAttributeList().stream()
                .filter(attr -> !IMPLICIT_RULE_ATTRIBUTES.containsKey(attr.getName()))
                .map(AttributeInfo::getDefaultValue))
        .containsExactly(
            "\"@my_repo//test:foo\"",
            "[\"@my_repo//x\", \"@local//y\", \"@local//y:z\"]",
            "{\"@my_repo//x\": \"label_in_main\", \"@local//y\": \"label_in_dep\"}");
  }

  @Test
  public void aspectDocstring() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(target, ctx):
                pass

            documented_aspect = aspect(doc = "My doc", implementation = _my_impl)
            undocumented_aspect = aspect(implementation = _my_impl)
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getAspectInfoList())
        .ignoringFields(AspectInfo.ATTRIBUTE_FIELD_NUMBER) // ignore implicit attributes
        .containsExactly(
            AspectInfo.newBuilder()
                .setAspectName("documented_aspect")
                .setDocString("My doc")
                .setOriginKey(
                    OriginKey.newBuilder().setName("documented_aspect").setFile(fakeLabelString))
                .build(),
            AspectInfo.newBuilder()
                .setAspectName("undocumented_aspect")
                .setOriginKey(
                    OriginKey.newBuilder().setName("undocumented_aspect").setFile(fakeLabelString))
                .build());
  }

  @Test
  public void aspectAttributes() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(target, ctx):
                pass

            my_aspect = aspect(
                implementation = _my_impl,
                attr_aspects = ["deps", "srcs", "_private"],
                attrs = {
                    "a": attr.string(doc = "My doc", default = "foo"),
                    "b": attr.string(mandatory = True),
                    "_c": attr.string(doc = "Hidden attribute"),
                },
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getAspectInfoList())
        .containsExactly(
            AspectInfo.newBuilder()
                .setAspectName("my_aspect")
                .setOriginKey(OriginKey.newBuilder().setName("my_aspect").setFile(fakeLabelString))
                .addAspectAttribute("deps")
                .addAspectAttribute("srcs")
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
                        .setMandatory(true)
                        .build())
                .build());
  }

  @Test
  public void unexportedAspect_notDocumented() throws Exception {
    Module module =
        exec(
            """
            def _my_impl(target, ctx):
                pass

            s = struct(
                my_aspect = aspect(
                    doc = "Unexported aspect",
                    implementation = _my_impl,
                )
            )
            """);
    ModuleInfo moduleInfo = getExtractor().extractFrom(module);
    assertThat(moduleInfo.getAspectInfoList()).isEmpty();
  }
}
