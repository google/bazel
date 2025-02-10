// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkbuildapi.java;

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkActionFactoryApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkRuleContextApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcInfoApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ConstraintValueInfoApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** Utilities for Java compilation support in Starlark. */
@StarlarkBuiltin(
    name = "java_common",
    category = DocCategory.TOP_LEVEL_MODULE,
    doc = "Utilities for Java compilation support in Starlark.")
public interface JavaCommonApi<
        FileT extends FileApi,
        JavaInfoT extends JavaInfoApi<FileT, ?, ?>,
        ConstraintValueT extends ConstraintValueInfoApi,
        StarlarkRuleContextT extends StarlarkRuleContextApi<ConstraintValueT>,
        StarlarkActionFactoryT extends StarlarkActionFactoryApi>
    extends StarlarkValue {

  @StarlarkMethod(
      name = "merge",
      doc = "Merges the given providers into a single JavaInfo.",
      parameters = {
        @Param(
            name = "providers",
            positional = true,
            named = false,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)},
            doc = "The list of providers to merge.")
      })
  default JavaInfoT mergeJavaProviders(Sequence<?> providers /* <JavaInfoT> expected. */)
      throws EvalException {
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(
      name = "pack_sources",
      doc =
          "Packs sources and source jars into a single source jar file. "
              + "The return value is typically passed to"
              + "<p><code><a class=\"anchor\" href=\"../providers/JavaInfo.html\">"
              + "JavaInfo</a>#source_jar</code></p>."
              + "At least one of parameters output_jar or output_source_jar is required.",
      parameters = {
        @Param(name = "actions", named = true, doc = "ctx.actions"),
        @Param(
            name = "output_source_jar",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "The output source jar."),
        @Param(
            name = "sources",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc = "A list of Java source files to be packed into the source jar."),
        @Param(
            name = "source_jars",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc = "A list of source jars to be packed into the source jar."),
        @Param(
            name = "java_toolchain",
            positional = false,
            named = true,
            doc = "A JavaToolchainInfo to used to find the ijar tool."),
      })
  default FileApi packSources(
      StarlarkActionFactoryT actions,
      Object outputSourceJar,
      Sequence<?> sourceFiles, // <FileT> expected.
      Sequence<?> sourceJars, // <FileT> expected.
      Info javaToolchain)
      throws EvalException {
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(
      name = "stamp_jar",
      doc =
          "Stamps a jar with a target label for <code>add_dep</code> support. "
              + "The return value is typically passed to "
              + "<code><a class=\"anchor\" href=\"../providers/JavaInfo.html\">"
              + "JavaInfo</a>#compile_jar</code>. "
              + "Prefer to use "
              + "<code><a class=\"anchor\" href=\"#run_ijar\">run_ijar</a></code> "
              + "when possible.",
      parameters = {
        @Param(name = "actions", named = true, doc = "ctx.actions"),
        @Param(
            name = "jar",
            positional = false,
            named = true,
            doc = "The jar to run stamp_jar on."),
        @Param(
            name = "target_label",
            positional = false,
            named = true,
            doc =
                "A target label to stamp the jar with. Used for <code>add_dep</code> support. "
                    + "Typically, you would pass <code>ctx.label</code> to stamp the jar "
                    + "with the current rule's label."),
        @Param(
            name = "java_toolchain",
            positional = false,
            named = true,
            doc = "A JavaToolchainInfo to used to find the stamp_jar tool."),
      })
  default FileApi stampJar(
      StarlarkActionFactoryT actions, FileT jar, Label targetLabel, Info javaToolchain)
      throws EvalException {
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(
      name = "run_ijar",
      doc =
          "Runs ijar on a jar, stripping it of its method bodies. This helps reduce rebuilding "
              + "of dependent jars during any recompiles consisting only of simple changes to "
              + "method implementations. The return value is typically passed to "
              + "<code><a class=\"anchor\" href=\"../providers/JavaInfo.html\">"
              + "JavaInfo</a>#compile_jar</code>.",
      parameters = {
        @Param(name = "actions", named = true, doc = "ctx.actions"),
        @Param(name = "jar", positional = false, named = true, doc = "The jar to run ijar on."),
        @Param(
            name = "target_label",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Label.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "A target label to stamp the jar with. Used for <code>add_dep</code> support. "
                    + "Typically, you would pass <code>ctx.label</code> to stamp the jar "
                    + "with the current rule's label."),
        @Param(
            name = "java_toolchain",
            positional = false,
            named = true,
            doc = "A JavaToolchainInfo to used to find the ijar tool."),
      })
  default FileApi runIjar(
      StarlarkActionFactoryT actions, FileT jar, Object targetLabel, Info javaToolchain)
      throws EvalException {
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(
      name = "compile",
      doc =
          "Compiles Java source files/jars from the implementation of a Starlark rule and returns "
              + "a provider that represents the results of the compilation and can be added to "
              + "the set of providers emitted by this rule.",
      parameters = {
        @Param(name = "ctx", positional = true, named = false, doc = "The rule context."),
        @Param(
            name = "source_jars",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc =
                "A list of the jars to be compiled. At least one of source_jars or source_files"
                    + " should be specified."),
        @Param(
            name = "source_files",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc =
                "A list of the Java source files to be compiled. At least one of source_jars or "
                    + "source_files should be specified."),
        @Param(name = "output", positional = false, named = true),
        @Param(
            name = "output_source_jar",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "The output source jar. Defaults to `{output_jar}-src.jar` if unset."),
        @Param(
            name = "javac_opts",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            doc = "A list of the desired javac options."),
        @Param(
            name = "deps",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)},
            defaultValue = "[]",
            doc = "A list of dependencies."),
        @Param(
            name = "runtime_deps",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)},
            defaultValue = "[]",
            doc = "A list of runtime dependencies."),
        @Param(
            name = "exports",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = JavaInfoApi.class),
            },
            defaultValue = "[]",
            doc = "A list of exports."),
        @Param(
            name = "plugins",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = JavaPluginInfoApi.class),
              @ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)
            },
            defaultValue = "[]",
            doc = "A list of plugins."),
        @Param(
            name = "exported_plugins",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = JavaPluginInfoApi.class),
              @ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)
            },
            defaultValue = "[]",
            doc = "A list of exported plugins."),
        @Param(
            name = "native_libraries",
            positional = false,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = CcInfoApi.class)},
            named = true,
            defaultValue = "[]",
            doc = "CC native library dependencies that are needed for this library."),
        @Param(
            name = "annotation_processor_additional_inputs",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc =
                "A list of inputs that the Java compilation action will take in addition to the "
                    + "Java sources for annotation processing."),
        @Param(
            name = "annotation_processor_additional_outputs",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc =
                "A list of outputs that the Java compilation action will output in addition to "
                    + "the class jar from annotation processing."),
        @Param(
            name = "strict_deps",
            defaultValue = "'ERROR'",
            positional = false,
            named = true,
            doc =
                "A string that specifies how to handle strict deps. Possible values: 'OFF', "
                    + "'ERROR', 'WARN' and 'DEFAULT'. For more details see <a href=\""
                    + "${link user-manual#flag--strict_java_deps}\"><code>--strict_java_deps<code>"
                    + " flag</a>. By default 'ERROR'."),
        @Param(
            name = "java_toolchain",
            positional = false,
            named = true,
            doc = "A JavaToolchainInfo to be used for this compilation. Mandatory."),
        @Param(
            name = "bootclasspath",
            positional = false,
            named = true,
            defaultValue = "None",
            doc =
                "A BootClassPathInfo to be used for this compilation. If present, overrides the"
                    + " bootclasspath associated with the provided java_toolchain."),
        @Param(
            name = "sourcepath",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]"),
        @Param(
            name = "resources",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]"),
        @Param(
            name = "resource_jars",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]"),
        @Param(
            name = "classpath_resources",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]"),
        @Param(name = "neverlink", positional = false, named = true, defaultValue = "False"),
        @Param(
            name = "enable_annotation_processing",
            positional = false,
            named = true,
            defaultValue = "True",
            doc =
                "Disables annotation processing in this compilation, causing any annotation"
                    + " processors provided in plugins or in exported_plugins of deps to be"
                    + " ignored."),
        @Param(
            name = "enable_compile_jar_action",
            positional = false,
            named = true,
            defaultValue = "True",
            doc =
                "Enables header compilation or ijar creation. If set to False, it forces use of the"
                    + " full class jar in the compilation classpaths of any dependants. Doing so is"
                    + " intended for use by non-library targets such as binaries that do not have"
                    + " dependants."),
        @Param(
            name = "enable_jspecify",
            positional = false,
            named = true,
            defaultValue = "True",
            documented = false),
        @Param(
            name = "include_compilation_info",
            positional = false,
            named = true,
            defaultValue = "True",
            documented = false),
        @Param(
            name = "injecting_rule_kind",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "add_exports",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            doc = "Allow this library to access the given <module>/<package>."),
        @Param(
            name = "add_opens",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            doc = "Allow this library to reflectively access the given <module>/<package>."),
      },
      useStarlarkThread = true)
  default JavaInfoT createJavaCompileAction(
      StarlarkRuleContextT starlarkRuleContext,
      Sequence<?> sourceJars, // <FileT> expected.
      Sequence<?> sourceFiles, // <FileT> expected.
      FileT outputJar,
      Object outputSourceJar,
      Sequence<?> javacOpts, // <String> expected.
      Sequence<?> deps, // <JavaInfoT> expected.
      Sequence<?> runtimeDeps, // <JavaInfoT> expected.
      Sequence<?> exports, // <JavaInfoT> expected.
      Sequence<?> plugins, // <JavaInfoT> expected.
      Sequence<?> exportedPlugins, // <JavaInfoT> expected.
      Sequence<?> nativeLibraries, // <CcInfoT> expected.
      Sequence<?> annotationProcessorAdditionalInputs, // <FileT> expected.
      Sequence<?> annotationProcessorAdditionalOutputs, // <FileT> expected.
      String strictDepsMode,
      Info javaToolchain,
      Object bootClassPath,
      Sequence<?> sourcepathEntries, // <FileT> expected.
      Sequence<?> resources, // <FileT> expected.
      Sequence<?> resourceJars, // <FileT> expected.
      Sequence<?> classpathResources, // <FileT> expected.
      Boolean neverlink,
      Boolean enableAnnotationProcessing,
      Boolean enableCompileJarAction,
      Boolean enableJSpecify,
      boolean includeCompilationInfo,
      Object injectingRuleKind,
      Sequence<?> addExports, // <String> expected.
      Sequence<?> addOpens, // <String> expected.
      StarlarkThread thread)
      throws EvalException, InterruptedException, RuleErrorException, LabelSyntaxException {
    // this will never be invoked now that only the Starlark implementation in `@_builtins` is used.
    // this method declaration exists purely for documentation.
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(
      name = "create_header_compilation_action",
      documented = false,
      parameters = {
        @Param(name = "ctx"),
        @Param(name = "java_toolchain"),
        @Param(name = "compile_jar"),
        @Param(name = "compile_deps_proto"),
        @Param(name = "plugin_info"),
        @Param(name = "source_files"),
        @Param(name = "source_jars"),
        @Param(name = "compilation_classpath"),
        @Param(name = "direct_jars"),
        @Param(name = "bootclasspath"),
        @Param(name = "compile_time_java_deps"),
        @Param(name = "javac_opts"),
        @Param(name = "strict_deps_mode"),
        @Param(name = "target_label"),
        @Param(name = "injecting_rule_kind"),
        @Param(name = "enable_direct_classpath"),
        @Param(name = "additional_inputs"),
      })
  void createHeaderCompilationAction(
      StarlarkRuleContextT ctx,
      Info javaToolchain,
      FileT compileJar,
      FileT compileDepsProto,
      Info pluginInfo,
      Depset sourceFiles,
      Sequence<?> sourceJars,
      Depset compileTimeClasspath,
      Depset directJars,
      Object bootClassPath,
      Depset compileTimeDeps,
      Depset javacOpts,
      String strictDepsMode,
      Label targetLabel,
      Object injectingRuleKind,
      boolean enableDirectClasspath,
      Sequence<?> additionalInputs)
      throws EvalException,
          TypeException,
          RuleErrorException,
          LabelSyntaxException,
          InterruptedException;

  @StarlarkMethod(
      name = "create_compilation_action",
      documented = false,
      parameters = {
        @Param(name = "ctx"),
        @Param(name = "java_toolchain"),
        @Param(name = "output"),
        @Param(name = "manifest_proto"),
        @Param(name = "plugin_info"),
        @Param(name = "compilation_classpath"),
        @Param(name = "direct_jars"),
        @Param(name = "bootclasspath"),
        @Param(name = "javabuilder_jvm_flags"),
        @Param(name = "compile_time_java_deps"),
        @Param(name = "javac_opts"),
        @Param(name = "strict_deps_mode"),
        @Param(name = "target_label"),
        @Param(name = "deps_proto", defaultValue = "None", named = true),
        @Param(name = "gen_class", defaultValue = "None", named = true),
        @Param(name = "gen_source", defaultValue = "None", named = true),
        @Param(name = "native_header_jar", defaultValue = "None", named = true),
        @Param(name = "sources", defaultValue = "None", named = true),
        @Param(name = "source_jars", defaultValue = "[]", named = true),
        @Param(name = "resources", defaultValue = "[]", named = true),
        @Param(name = "resource_jars", defaultValue = "None", named = true),
        @Param(name = "classpath_resources", defaultValue = "[]", named = true),
        @Param(name = "sourcepath", defaultValue = "[]", named = true),
        @Param(name = "injecting_rule_kind", defaultValue = "None", named = true),
        @Param(name = "enable_jspecify", defaultValue = "True", named = true),
        @Param(name = "enable_direct_classpath", defaultValue = "True", named = true),
        @Param(name = "additional_inputs", defaultValue = "[]", named = true),
        @Param(name = "additional_outputs", defaultValue = "[]", named = true),
      })
  void createCompilationAction(
      StarlarkRuleContextT ctx,
      Info javaToolchain,
      FileT output,
      FileT manifestProto,
      Info pluginInfo,
      Depset compileTimeClasspath,
      Depset directJars,
      Object bootClassPath,
      Depset javabuilderJvmFlags,
      Depset compileTimeJavaDeps,
      Depset javacOpts,
      String strictDepsMode,
      Label targetLabel,
      Object depsProto,
      Object genClass,
      Object genSource,
      Object nativeHeader,
      Object sourceFiles,
      Sequence<?> sourceJars,
      Sequence<?> resources,
      Object resourceJars,
      Sequence<?> classpathResources,
      Sequence<?> sourcepath,
      Object injectingRuleKind,
      boolean enableJSpecify,
      boolean enableDirectClasspath,
      Sequence<?> additionalInputs,
      Sequence<?> additionalOutputs)
      throws EvalException,
          TypeException,
          RuleErrorException,
          LabelSyntaxException,
          InterruptedException;

  @StarlarkMethod(
      name = "default_javac_opts",
      // This function is experimental for now.
      documented = false,
      parameters = {
        @Param(
            name = "java_toolchain",
            positional = false,
            named = true,
            doc =
                "A JavaToolchainInfo to be used for retrieving the ijar "
                    + "tool. Only set when use_ijar is True."),
        @Param(
            name = "as_depset",
            positional = false,
            named = true,
            documented = false,
            defaultValue = "False")
      })
  default StarlarkValue getDefaultJavacOpts(Info javaToolchain, boolean asDepset) {
    // method exists solely for documentation
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(
      name = "JavaToolchainInfo",
      doc =
          "The key used to retrieve the provider that contains information about the Java "
              + "toolchain being used.",
      structField = true)
  default ProviderApi getJavaToolchainProvider() {
    // method exists purely for documentation
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(
      name = "JavaRuntimeInfo",
      doc =
          "The key used to retrieve the provider that contains information about the Java "
              + "runtime being used.",
      structField = true)
  default ProviderApi getJavaRuntimeProvider() {
    // method exists purely for documentation
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(
      name = "BootClassPathInfo",
      doc = "The provider used to supply bootclasspath information",
      structField = true)
  default ProviderApi getBootClassPathInfo() {
    // method exists solely for documentation
    throw new UnsupportedOperationException();
  }

  /** Returns target kind. */
  @StarlarkMethod(
      name = "target_kind",
      parameters = {
        @Param(name = "target", positional = true, named = false, doc = "The target."),
      },
      documented = false,
      useStarlarkThread = true)
  String getTargetKind(Object target, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "collect_native_deps_dirs",
      parameters = {@Param(name = "libraries")},
      useStarlarkThread = true,
      documented = false)
  Sequence<String> collectNativeLibsDirs(Depset libraries, StarlarkThread thread)
      throws EvalException, RuleErrorException, TypeException;

  @StarlarkMethod(
      name = "get_runtime_classpath_for_archive",
      parameters = {@Param(name = "jars"), @Param(name = "excluded_jars")},
      useStarlarkThread = true,
      documented = false)
  Depset getRuntimeClasspathForArchive(
      Depset runtimeClasspath, Depset excludedArtifacts, StarlarkThread thread)
      throws EvalException, TypeException;

  @StarlarkMethod(
      name = "check_provider_instances",
      documented = false,
      parameters = {
        @Param(name = "providers"),
        @Param(name = "what"),
        @Param(name = "provider_type")
      },
      useStarlarkThread = true)
  void checkProviderInstances(
      Sequence<?> providers, String what, ProviderApi providerType, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(name = "_google_legacy_api_enabled", documented = false, useStarlarkThread = true)
  boolean isLegacyGoogleApiEnabled(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "_check_java_toolchain_is_declared_on_rule",
      documented = false,
      parameters = {@Param(name = "actions")},
      useStarlarkThread = true)
  void checkJavaToolchainIsDeclaredOnRuleForStarlark(
      StarlarkActionFactoryT actions, StarlarkThread thread)
      throws EvalException, LabelSyntaxException;

  @StarlarkMethod(
      name = "_incompatible_java_info_merge_runtime_module_flags",
      documented = false,
      useStarlarkThread = true)
  boolean isJavaInfoMergeRuntimeModuleFlagsEnabled(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "incompatible_disable_non_executable_java_binary",
      useStarlarkThread = true,
      documented = false)
  boolean incompatibleDisableNonExecutableJavaBinary(StarlarkThread thread);

  @StarlarkMethod(
      name = "expand_java_opts",
      documented = false,
      parameters = {
        @Param(name = "ctx"),
        @Param(name = "attr"),
        @Param(name = "tokenize", named = true, positional = false),
        @Param(name = "exec_paths", named = true, positional = false, defaultValue = "False")
      })
  Sequence<?> expandJavaOpts(
      StarlarkRuleContextT ctx, String attr, boolean tokenize, boolean execPaths)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "tokenize_javacopts",
      documented = false,
      parameters = {@Param(name = "opts")})
  Sequence<?> tokenizeJavacOpts(Sequence<?> opts) throws EvalException, InterruptedException;
}
