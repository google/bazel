// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.docgen.annot.GlobalMethods;
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment;
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule.DepSpec;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleThreadContext.ModuleExtensionUsageBuilder;
import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.StarlarkExportable;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Structure;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.Identifier;

/** A collection of global Starlark build API functions that apply to MODULE.bazel files. */
@GlobalMethods(environment = Environment.MODULE)
public class ModuleFileGlobals {

  /* Valid bazel compatibility argument must 1) start with (<,<=,>,>=,-);
     2) then contain a version number in form of X.X.X where X has one or two digits
  */
  private static final Pattern VALID_BAZEL_COMPATIBILITY_VERSION =
      Pattern.compile("(>|<|-|<=|>=)(\\d+\\.){2}\\d+");

  @VisibleForTesting
  static void validateModuleName(String moduleName) throws EvalException {
    if (!RepositoryName.VALID_MODULE_NAME.matcher(moduleName).matches()) {
      throw Starlark.errorf(
          "invalid module name '%s': valid names must 1) only contain lowercase letters (a-z),"
              + " digits (0-9), dots (.), hyphens (-), and underscores (_); 2) begin with a"
              + " lowercase letter; 3) end with a lowercase letter or digit.",
          moduleName);
    }
  }

  @StarlarkMethod(
      name = "module",
      doc =
          "Declares certain properties of the Bazel module represented by the current Bazel repo."
              + " These properties are either essential metadata of the module (such as the name"
              + " and version), or affect behavior of the current module and its dependents.  <p>It"
              + " should be called at most once, and if called, it must be the very first directive"
              + " in the MODULE.bazel file. It can be omitted only if this module is the root"
              + " module (as in, if it's not going to be depended on by another module).",
      parameters = {
        @Param(
            name = "name",
            doc =
                "The name of the module. Can be omitted only if this module is the root module (as"
                    + " in, if it's not going to be depended on by another module). A valid module"
                    + " name must: 1) only contain lowercase letters (a-z), digits (0-9), dots (.),"
                    + " hyphens (-), and underscores (_); 2) begin with a lowercase letter; 3) end"
                    + " with a lowercase letter or digit.",
            named = true,
            positional = false,
            defaultValue = "''"),
        @Param(
            name = "version",
            doc =
                "The version of the module. Can be omitted only if this module is the root module"
                    + " (as in, if it's not going to be depended on by another module). The version"
                    + " must be in a relaxed SemVer format; see <a"
                    + " href=\"/external/module#version_format\">the documentation</a> for more"
                    + " details.",
            named = true,
            positional = false,
            defaultValue = "''"),
        @Param(
            name = "compatibility_level",
            doc =
                "The compatibility level of the module; this should be changed every time a major"
                    + " incompatible change is introduced. This is essentially the \"major"
                    + " version\" of the module in terms of SemVer, except that it's not embedded"
                    + " in the version string itself, but exists as a separate field. Modules with"
                    + " different compatibility levels participate in version resolution as if"
                    + " they're modules with different names, but the final dependency graph cannot"
                    + " contain multiple modules with the same name but different compatibility"
                    + " levels (unless <code>multiple_version_override</code> is in effect). See <a"
                    + " href=\"/external/module#compatibility_level\">the documentation</a> for"
                    + " more details.",
            named = true,
            positional = false,
            defaultValue = "0"),
        @Param(
            name = "repo_name",
            doc =
                "The name of the repository representing this module, as seen by the module itself."
                    + " By default, the name of the repo is the name of the module. This can be"
                    + " specified to ease migration for projects that have been using a repo name"
                    + " for itself that differs from its module name.",
            named = true,
            positional = false,
            defaultValue = "''"),
        @Param(
            name = "bazel_compatibility",
            doc =
                "A list of bazel versions that allows users to declare which Bazel versions"
                    + " are compatible with this module. It does NOT affect dependency resolution,"
                    + " but bzlmod will use this information to check if your current Bazel version"
                    + " is compatible. The format of this value is a string of some constraint"
                    + " values separated by comma. Three constraints are supported: <=X.X.X: The"
                    + " Bazel version must be equal or older than X.X.X. Used when there is a known"
                    + " incompatible change in a newer version. >=X.X.X: The Bazel version must be"
                    + " equal or newer than X.X.X.Used when you depend on some features that are"
                    + " only available since X.X.X. -X.X.X: The Bazel version X.X.X is not"
                    + " compatible. Used when there is a bug in X.X.X that breaks you, but fixed in"
                    + " later versions.",
            named = true,
            positional = false,
            allowedTypes = {@ParamType(type = Iterable.class, generic1 = String.class)},
            defaultValue = "[]"),
      },
      useStarlarkThread = true)
  public void module(
      String name,
      String version,
      StarlarkInt compatibilityLevel,
      String repoName,
      Iterable<?> bazelCompatibility,
      StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "module()");
    if (context.isModuleCalled()) {
      throw Starlark.errorf("the module() directive can only be called once");
    }
    if (context.hadNonModuleCall()) {
      throw Starlark.errorf("if module() is called, it must be called before any other functions");
    }
    context.setModuleCalled();
    if (!name.isEmpty()) {
      validateModuleName(name);
    }
    if (repoName.isEmpty()) {
      repoName = name;
      context.addRepoNameUsage(name, "as the current module name", thread.getCallStack());
    } else {
      RepositoryName.validateUserProvidedRepoName(repoName);
      context.addRepoNameUsage(repoName, "as the module's own repo name", thread.getCallStack());
    }
    Version parsedVersion;
    try {
      parsedVersion = Version.parse(version);
    } catch (ParseException e) {
      throw new EvalException("Invalid version in module()", e);
    }
    context
        .getModuleBuilder()
        .setName(name)
        .setVersion(parsedVersion)
        .setCompatibilityLevel(compatibilityLevel.toInt("compatibility_level"))
        .addBazelCompatibilityValues(
            checkAllCompatibilityVersions(bazelCompatibility, "bazel_compatibility"))
        .setRepoName(repoName);
  }

  private static ImmutableList<String> checkAllAbsolutePatterns(Iterable<?> iterable, String where)
      throws EvalException {
    Sequence<String> list = Sequence.cast(iterable, String.class, where);
    for (String item : list) {
      if (!item.startsWith("//") && !item.startsWith("@")) {
        throw Starlark.errorf(
            "Expected absolute target patterns (must begin with '//' or '@') for '%s' argument, but"
                + " got '%s' as an argument",
            where, item);
      }
    }
    return list.getImmutableList();
  }

  private static ImmutableList<String> checkAllCompatibilityVersions(
      Iterable<?> iterable, String where) throws EvalException {
    Sequence<String> list = Sequence.cast(iterable, String.class, where);
    for (String version : list) {
      if (!VALID_BAZEL_COMPATIBILITY_VERSION.matcher(version).matches()) {
        throw Starlark.errorf(
            "invalid version argument '%s': valid argument must 1) start with (<,<=,>,>=,-); "
                + "2) contain a version number in form of X.X.X where X is a number",
            version);
      }
    }
    return list.getImmutableList();
  }

  @StarlarkMethod(
      name = "bazel_dep",
      doc = "Declares a direct dependency on another Bazel module.",
      parameters = {
        @Param(
            name = "name",
            doc = "The name of the module to be added as a direct dependency.",
            named = true,
            positional = false),
        @Param(
            name = "version",
            doc = "The version of the module to be added as a direct dependency.",
            named = true,
            positional = false,
            defaultValue = "''"),
        @Param(
            name = "max_compatibility_level",
            doc =
                "The maximum <code>compatibility_level</code> supported for the module to be added"
                    + " as a direct dependency. The version of the module implies the minimum"
                    + " compatibility_level supported, as well as the maximum if this attribute is"
                    + " not specified.",
            named = true,
            positional = false,
            defaultValue = "-1"),
        @Param(
            name = "repo_name",
            doc =
                "The name of the external repo representing this dependency. This is by default the"
                    + " name of the module.",
            named = true,
            positional = false,
            defaultValue = "''"),
        @Param(
            name = "dev_dependency",
            doc =
                "If true, this dependency will be ignored if the current module is not the root"
                    + " module or `--ignore_dev_dependency` is enabled.",
            named = true,
            positional = false,
            defaultValue = "False"),
      },
      useStarlarkThread = true)
  public void bazelDep(
      String name,
      String version,
      StarlarkInt maxCompatibilityLevel,
      String repoName,
      boolean devDependency,
      StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "bazel_dep()");
    context.setNonModuleCalled();
    if (repoName.isEmpty()) {
      repoName = name;
    }
    validateModuleName(name);
    Version parsedVersion;
    try {
      parsedVersion = Version.parse(version);
    } catch (ParseException e) {
      throw new EvalException("Invalid version in bazel_dep()", e);
    }
    RepositoryName.validateUserProvidedRepoName(repoName);

    if (!(context.shouldIgnoreDevDeps() && devDependency)) {
      context.addDep(
          repoName,
          DepSpec.create(
              name, parsedVersion, maxCompatibilityLevel.toInt("max_compatibility_level")));
    }

    context.addRepoNameUsage(repoName, "by a bazel_dep", thread.getCallStack());
  }

  @StarlarkMethod(
      name = "register_execution_platforms",
      doc =
          "Specifies already-defined execution platforms to be registered when this module is"
              + " selected. Should be absolute <a"
              + " href='https://bazel.build/reference/glossary#target-pattern'>target patterns</a>"
              + " (ie. beginning with either <code>@</code> or <code>//</code>). See <a"
              + " href=\"${link toolchains}\">toolchain resolution</a> for more information."
              + " Patterns that expand to multiple targets, such as <code>:all</code>, will be"
              + " registered in lexicographical order by name.",
      parameters = {
        @Param(
            name = "dev_dependency",
            doc =
                "If true, the execution platforms will not be registered if the current module is"
                    + " not the root module or `--ignore_dev_dependency` is enabled.",
            named = true,
            positional = false,
            defaultValue = "False"),
      },
      extraPositionals =
          @Param(
              name = "platform_labels",
              allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
              doc = "The target patterns to register."),
      useStarlarkThread = true)
  public void registerExecutionPlatforms(
      boolean devDependency, Sequence<?> platformLabels, StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context =
        ModuleThreadContext.fromOrFail(thread, "register_execution_platforms()");
    context.setNonModuleCalled();
    if (context.shouldIgnoreDevDeps() && devDependency) {
      return;
    }
    context
        .getModuleBuilder()
        .addExecutionPlatformsToRegister(
            checkAllAbsolutePatterns(platformLabels, "register_execution_platforms"));
  }

  @StarlarkMethod(
      name = "register_toolchains",
      doc =
          "Specifies already-defined toolchains to be registered when this module is selected."
              + " Should be absolute <a"
              + " href='https://bazel.build/reference/glossary#target-pattern'>target patterns</a>"
              + " (ie. beginning with either <code>@</code> or <code>//</code>). See <a"
              + " href=\"${link toolchains}\">toolchain resolution</a> for more information."
              + " Patterns that expand to multiple targets, such as <code>:all</code>, will be"
              + " registered in lexicographical order by target name (not the name of the toolchain"
              + " implementation).",
      parameters = {
        @Param(
            name = "dev_dependency",
            doc =
                "If true, the toolchains will not be registered if the current module is not the"
                    + " root module or `--ignore_dev_dependency` is enabled.",
            named = true,
            positional = false,
            defaultValue = "False"),
      },
      extraPositionals =
          @Param(
              name = "toolchain_labels",
              allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
              doc = "The target patterns to register."),
      useStarlarkThread = true)
  public void registerToolchains(
      boolean devDependency, Sequence<?> toolchainLabels, StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "register_toolchains()");
    context.setNonModuleCalled();
    if (context.shouldIgnoreDevDeps() && devDependency) {
      return;
    }
    ImmutableList<String> checkedToolchainLabels =
        checkAllAbsolutePatterns(toolchainLabels, "register_toolchains");
    if (thread
        .getSemantics()
        .getBool(BuildLanguageOptions.EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING)) {
      for (String label : checkedToolchainLabels) {
        if (label.contains("...")) {
          throw Starlark.errorf(
              "invalid target pattern \"%s\": register_toolchain target patterns may only refer to "
                  + "targets within a single package",
              label);
        }
      }
    }
    context.getModuleBuilder().addToolchainsToRegister(checkedToolchainLabels);
  }

  @StarlarkMethod(
      name = "use_extension",
      doc =
          "Returns a proxy object representing a module extension; its methods can be invoked to"
              + " create module extension tags.",
      parameters = {
        @Param(
            name = "extension_bzl_file",
            doc = "A label to the Starlark file defining the module extension."),
        @Param(
            name = "extension_name",
            doc =
                "The name of the module extension to use. A symbol with this name must be exported"
                    + " by the Starlark file."),
        @Param(
            name = "dev_dependency",
            doc =
                "If true, this usage of the module extension will be ignored if the current module"
                    + " is not the root module or `--ignore_dev_dependency` is enabled.",
            named = true,
            positional = false,
            defaultValue = "False"),
        @Param(
            name = "isolate",
            doc =
                "If true, this usage of the module extension will be isolated from all other "
                    + "usages, both in this and other modules. Tags created for this usage do not "
                    + "affect other usages and the repositories generated by the extension for "
                    + "this usage will be distinct from all other repositories generated by the "
                    + "extension."
                    + "<p>This parameter is currently experimental and only available with the "
                    + "flag <code>--experimental_isolated_extension_usages</code>.",
            named = true,
            positional = false,
            defaultValue = "False",
            enableOnlyWithFlag = "-experimental_isolated_extension_usages",
            valueWhenDisabled = "False"),
      },
      useStarlarkThread = true)
  public ModuleExtensionProxy useExtension(
      String rawExtensionBzlFile,
      String extensionName,
      boolean devDependency,
      boolean isolate,
      StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "use_extension()");
    context.setNonModuleCalled();

    if (!Identifier.isValid(extensionName)) {
      throw Starlark.errorf("extension name is not a valid identifier: %s", extensionName);
    }

    var proxyBuilder =
        ModuleExtensionUsage.Proxy.builder()
            .setLocation(thread.getCallerLocation())
            .setDevDependency(devDependency)
            .setContainingModuleFilePath(context.getCurrentModuleFilePath());

    String extensionBzlFile = normalizeLabelString(context.getModuleBuilder(), rawExtensionBzlFile);
    var newUsageBuilder =
        new ModuleExtensionUsageBuilder(context, extensionBzlFile, extensionName, isolate);

    if (context.shouldIgnoreDevDeps() && devDependency) {
      // This is a no-op proxy.
      return new ModuleExtensionProxy(newUsageBuilder, proxyBuilder);
    }

    // Find an existing usage builder corresponding to this extension. Isolated usages need to get
    // their own proxy.
    if (!isolate) {
      for (ModuleExtensionUsageBuilder usageBuilder : context.getExtensionUsageBuilders()) {
        if (usageBuilder.isForExtension(extensionBzlFile, extensionName)) {
          return new ModuleExtensionProxy(usageBuilder, proxyBuilder);
        }
      }
    }

    // If no such proxy exists, we can just use a new one.
    context.getExtensionUsageBuilders().add(newUsageBuilder);
    return new ModuleExtensionProxy(newUsageBuilder, proxyBuilder);
  }

  private String normalizeLabelString(InterimModule.Builder module, String rawExtensionBzlFile)
      throws EvalException {
    // Normalize the label by parsing and stringifying it with a repo mapping that preserves the
    // apparent repository name, except that a reference to the main repository via the empty
    // repo name is translated to using the module repo name. This is necessary as
    // ModuleExtensionUsages are grouped by the string value of this label, but later mapped to
    // their Label representation. If multiple strings map to the same Label, this would result in a
    // crash.
    // ownName can't change anymore as calling module() after this results in an error.
    String ownName = module.getRepoName().orElse(module.getName());
    RepositoryName ownRepoName = RepositoryName.createUnvalidated(ownName);
    ImmutableMap<String, RepositoryName> repoMapping = ImmutableMap.of();
    if (module.getKey().equals(ModuleKey.ROOT)) {
      repoMapping = ImmutableMap.of("", ownRepoName);
    }
    Label label;
    try {
      label =
          Label.parseWithPackageContext(
              rawExtensionBzlFile,
              Label.PackageContext.of(
                  PackageIdentifier.create(ownRepoName, PathFragment.EMPTY_FRAGMENT),
                  RepositoryMapping.createAllowingFallback(repoMapping)));
    } catch (LabelSyntaxException e) {
      throw Starlark.errorf("invalid label \"%s\": %s", rawExtensionBzlFile, e.getMessage());
    }
    // Skip over the leading "@" of the unambiguous form.
    return label.getUnambiguousCanonicalForm().substring(1);
  }

  private Label convertAndValidatePatchLabel(InterimModule.Builder module, String rawLabel)
      throws EvalException {
    RepositoryMapping repoMapping =
        RepositoryMapping.create(
            ImmutableMap.<String, RepositoryName>builder()
                .put("", RepositoryName.MAIN)
                .put(module.getRepoName().orElse(module.getName()), RepositoryName.MAIN)
                .buildKeepingLast(),
            RepositoryName.MAIN);
    Label label;
    try {
      label =
          Label.parseWithPackageContext(
              rawLabel, Label.PackageContext.of(PackageIdentifier.EMPTY_PACKAGE_ID, repoMapping));
    } catch (LabelSyntaxException e) {
      throw Starlark.errorf("invalid label \"%s\" in 'patches': %s", rawLabel, e.getMessage());
    }
    if (!label.getRepository().isVisible()) {
      throw Starlark.errorf(
          "invalid label in 'patches': only patches in the main repository can be applied, not from"
              + " '@%s'",
          label.getRepository().getName());
    }
    return label;
  }

  @StarlarkBuiltin(name = "module_extension_proxy", documented = false)
  static class ModuleExtensionProxy implements Structure, StarlarkExportable {
    private final ModuleExtensionUsageBuilder usageBuilder;
    private final ModuleExtensionUsage.Proxy.Builder proxyBuilder;

    ModuleExtensionProxy(
        ModuleExtensionUsageBuilder usageBuilder, ModuleExtensionUsage.Proxy.Builder proxyBuilder) {
      this.usageBuilder = usageBuilder;
      this.proxyBuilder = proxyBuilder;
      usageBuilder.addProxyBuilder(proxyBuilder);
    }

    void addImport(
        String localRepoName,
        String exportedName,
        String byWhat,
        ImmutableList<StarlarkThread.CallStackEntry> stack)
        throws EvalException {
      usageBuilder.addImport(localRepoName, exportedName, byWhat, stack);
      proxyBuilder.addImport(localRepoName, exportedName);
    }

    void addOverride(
        String overriddenRepoName,
        String overridingRepoName,
        boolean mustExist,
        ImmutableList<StarlarkThread.CallStackEntry> stack)
        throws EvalException {
      usageBuilder.addRepoOverride(overriddenRepoName, overridingRepoName, mustExist, stack);
    }

    class TagCallable implements StarlarkValue {
      final String tagName;

      TagCallable(String tagName) {
        this.tagName = tagName;
      }

      @StarlarkMethod(
          name = "call",
          selfCall = true,
          documented = false,
          extraKeywords = @Param(name = "kwargs"),
          useStarlarkThread = true)
      public void call(Dict<String, Object> kwargs, StarlarkThread thread) {
        usageBuilder.addTag(
            Tag.builder()
                .setTagName(tagName)
                .setAttributeValues(AttributeValues.create(kwargs))
                .setDevDependency(proxyBuilder.isDevDependency())
                .setLocation(thread.getCallerLocation())
                .build());
      }
    }

    @Override
    public TagCallable getValue(String tagName) throws EvalException {
      return new TagCallable(tagName);
    }

    @Override
    public ImmutableCollection<String> getFieldNames() {
      return ImmutableList.of();
    }

    @Nullable
    @Override
    public String getErrorMessageForUnknownField(String field) {
      return null;
    }

    @Override
    public boolean isExported() {
      return !proxyBuilder.getProxyName().isEmpty();
    }

    @Override
    public void export(EventHandler handler, Label bzlFileLabel, String name) {
      proxyBuilder.setProxyName(name);
    }
  }

  @StarlarkMethod(
      name = "use_repo",
      doc =
          "Imports one or more repos generated by the given module extension into the scope of the"
              + " current module.",
      parameters = {
        @Param(
            name = "extension_proxy",
            doc = "A module extension proxy object returned by a <code>use_extension</code> call."),
      },
      extraPositionals = @Param(name = "args", doc = "The names of the repos to import."),
      extraKeywords =
          @Param(
              name = "kwargs",
              doc =
                  "Specifies certain repos to import into the scope of the current module with"
                      + " different names. The keys should be the name to use in the current scope,"
                      + " whereas the values should be the original names exported by the module"
                      + " extension."),
      useStarlarkThread = true)
  public void useRepo(
      ModuleExtensionProxy extensionProxy,
      Tuple args,
      Dict<String, Object> kwargs,
      StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "use_repo()");
    context.setNonModuleCalled();
    ImmutableList<StarlarkThread.CallStackEntry> stack = thread.getCallStack();
    for (String arg : Sequence.cast(args, String.class, "args")) {
      extensionProxy.addImport(arg, arg, "by a use_repo() call", stack);
    }
    for (Map.Entry<String, String> entry :
        Dict.cast(kwargs, String.class, String.class, "kwargs").entrySet()) {
      extensionProxy.addImport(entry.getKey(), entry.getValue(), "by a use_repo() call", stack);
    }
  }

  @StarlarkMethod(
      name = "override_repo",
      doc =
          """
          Overrides one or more repos defined by the given module extension with the given repos
          visible to the current module. This is ignored if the current module is not the root
          module or `--ignore_dev_dependency` is enabled.

          <p>Use <a href="#inject_repo"><code>inject_repo</code></a> instead to add a new repo.
          """,
      parameters = {
        @Param(
            name = "extension_proxy",
            doc = "A module extension proxy object returned by a <code>use_extension</code> call."),
      },
      extraPositionals =
          @Param(
              name = "args",
              doc =
                  """
                  The repos in the extension that should be overridden with the repos of the same
                  name in the current module.\
                  """),
      extraKeywords =
          @Param(
              name = "kwargs",
              doc =
                  """
                  The overrides to apply to the repos generated by the extension, where the values
                  are the names of repos in the scope of the current module and the keys are the
                  names of the repos they will override in the extension.\
                  """),
      useStarlarkThread = true)
  public void overrideRepo(
      ModuleExtensionProxy extensionProxy,
      Tuple args,
      Dict<String, Object> kwargs,
      StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "override_repo()");
    context.setNonModuleCalled();
    if (context.shouldIgnoreDevDeps()) {
      // Ignore calls early as they may refer to repos that are dev dependencies (or this is not the
      // root module).
      return;
    }
    ImmutableList<StarlarkThread.CallStackEntry> stack = thread.getCallStack();
    for (String arg : Sequence.cast(args, String.class, "args")) {
      extensionProxy.addOverride(arg, arg, /* mustExist= */ true, stack);
    }
    for (Map.Entry<String, String> entry :
        Dict.cast(kwargs, String.class, String.class, "kwargs").entrySet()) {
      extensionProxy.addOverride(entry.getKey(), entry.getValue(), /* mustExist= */ true, stack);
    }
  }

  @StarlarkMethod(
      name = "inject_repo",
      doc =
          """
          Injects one or more new repos into the given module extension.
          This is ignored if the current module is not the root module or
          <code>--ignore_dev_dependency</code> is enabled.

          <p>Use <a href="#override_repo"><code>override_repo</code></a> instead to override an
          existing repo.\
          """,
      parameters = {
        @Param(
            name = "extension_proxy",
            doc = "A module extension proxy object returned by a <code>use_extension</code> call."),
      },
      extraPositionals =
          @Param(
              name = "args",
              doc =
                  """
                  The repos visible to the current module that should be injected into the
                  extension under the same name.\
                  """),
      extraKeywords =
          @Param(
              name = "kwargs",
              doc =
                  """
                  The new repos to inject into the extension, where the values are the names of
                  repos in the scope of the current module and the keys are the name they will be
                  visible under in the extension.\
                  """),
      useStarlarkThread = true)
  public void injectRepo(
      ModuleExtensionProxy extensionProxy,
      Tuple args,
      Dict<String, Object> kwargs,
      StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "inject_repo()");
    context.setNonModuleCalled();
    if (context.shouldIgnoreDevDeps()) {
      // Ignore calls early as they may refer to repos that are dev dependencies (or this is not the
      // root module).
      return;
    }
    ImmutableList<StarlarkThread.CallStackEntry> stack = thread.getCallStack();
    for (String arg : Sequence.cast(args, String.class, "args")) {
      extensionProxy.addOverride(arg, arg, /* mustExist= */ false, stack);
    }
    for (Map.Entry<String, String> entry :
        Dict.cast(kwargs, String.class, String.class, "kwargs").entrySet()) {
      extensionProxy.addOverride(entry.getKey(), entry.getValue(), /* mustExist= */ false, stack);
    }
  }

  @StarlarkMethod(
      name = "use_repo_rule",
      doc =
          "Returns a proxy value that can be directly invoked in the MODULE.bazel file as a"
              + " repository rule, one or more times. Repos created in such a way are only visible"
              + " to the current module, under the name declared using the <code>name</code>"
              + " attribute on the proxy. The implicit Boolean <code>dev_dependency</code>"
              + " attribute can also be used on the proxy to denote that a certain repo is only to"
              + " be created when the current module is the root module.",
      parameters = {
        @Param(
            name = "repo_rule_bzl_file",
            doc = "A label to the Starlark file defining the repo rule."),
        @Param(
            name = "repo_rule_name",
            doc =
                "The name of the repo rule to use. A symbol with this name must be exported by the"
                    + " Starlark file."),
      },
      useStarlarkThread = true)
  public RepoRuleProxy useRepoRule(String bzlFile, String ruleName, StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "use_repo_rule()");
    context.setNonModuleCalled();
    // Not a valid Starlark identifier so that it can't collide with a real extension.
    String extensionName = bzlFile + ' ' + ruleName;
    // Find or create the builder for the singular "innate" extension of this repo rule for this
    // module.
    for (ModuleExtensionUsageBuilder usageBuilder : context.getExtensionUsageBuilders()) {
      if (usageBuilder.isForExtension("//:MODULE.bazel", extensionName)) {
        return new RepoRuleProxy(usageBuilder);
      }
    }
    ModuleExtensionUsageBuilder newUsageBuilder =
        new ModuleExtensionUsageBuilder(
            context, "//:MODULE.bazel", extensionName, /* isolate= */ false);
    context.getExtensionUsageBuilders().add(newUsageBuilder);
    return new RepoRuleProxy(newUsageBuilder);
  }

  @StarlarkBuiltin(name = "repo_rule_proxy", documented = false)
  static class RepoRuleProxy implements StarlarkValue {
    private final ModuleExtensionUsageBuilder usageBuilder;

    private RepoRuleProxy(ModuleExtensionUsageBuilder usageBuilder) {
      this.usageBuilder = usageBuilder;
    }

    @StarlarkMethod(
        name = "call",
        selfCall = true,
        documented = false,
        parameters = {
          @Param(name = "name", positional = false, named = true),
          @Param(name = "dev_dependency", positional = false, named = true, defaultValue = "False")
        },
        extraKeywords = @Param(name = "kwargs"),
        useStarlarkThread = true)
    public void call(
        String name, boolean devDependency, Dict<String, Object> kwargs, StarlarkThread thread)
        throws EvalException {
      RepositoryName.validateUserProvidedRepoName(name);
      if (usageBuilder.getContext().shouldIgnoreDevDeps() && devDependency) {
        return;
      }
      kwargs.putEntry("name", name);
      ModuleExtensionProxy extensionProxy =
          new ModuleExtensionProxy(
              usageBuilder,
              ModuleExtensionUsage.Proxy.builder()
                  .setDevDependency(devDependency)
                  .setLocation(thread.getCallerLocation())
                  .setContainingModuleFilePath(
                      usageBuilder.getContext().getCurrentModuleFilePath()));
      extensionProxy.getValue("repo").call(kwargs, thread);
      extensionProxy.addImport(name, name, "by a repo rule", thread.getCallStack());
    }
  }

  @StarlarkMethod(
      name = CompiledModuleFile.INCLUDE_IDENTIFIER,
      doc =
          "Includes the contents of another MODULE.bazel-like file. Effectively,"
              + " <code>include()</code> behaves as if the included file is textually placed at the"
              + " location of the <code>include()</code> call, except that variable bindings (such"
              + " as those used for <code>use_extension</code>) are only ever visible in the file"
              + " they occur in, not in any included or including files.<p>Only the root module may"
              + " use <code>include()</code>; it is an error if a <code>bazel_dep</code>'s MODULE"
              + " file uses <code>include()</code>.<p>Only files in the main repo may be"
              + " included.<p><code>include()</code> allows you to segment the root module file"
              + " into multiple parts, to avoid having an enormous MODULE.bazel file or to better"
              + " manage access control for individual semantic segments.",
      parameters = {
        @Param(
            name = "label",
            doc =
                "The label pointing to the file to include. The label must point to a file in the"
                    + " main repo; in other words, it <strong>must<strong> start with double"
                    + " slashes (<code>//</code>). The name of the file must end with"
                    + " <code>.MODULE.bazel</code> and must not start with <code>.</code>."),
      },
      useStarlarkThread = true)
  public void include(String label, StarlarkThread thread)
      throws InterruptedException, EvalException {
    ModuleThreadContext context =
        ModuleThreadContext.fromOrFail(thread, CompiledModuleFile.INCLUDE_IDENTIFIER + "()");
    context.setNonModuleCalled();
    context.include(label, thread);
  }

  @StarlarkMethod(
      name = "single_version_override",
      doc =
          "Specifies that a dependency should still come from a registry, but its version should"
              + " be pinned, or its registry overridden, or a list of patches applied. This"
              + " directive only takes effect in the root module; in other words, if a module"
              + " is used as a dependency by others, its own overrides are ignored.",
      parameters = {
        @Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false),
        @Param(
            name = "version",
            doc =
                "Overrides the declared version of this module in the dependency graph. In other"
                    + " words, this module will be \"pinned\" to this override version. This"
                    + " attribute can be omitted if all one wants to override is the registry or"
                    + " the patches. ",
            named = true,
            positional = false,
            defaultValue = "''"),
        @Param(
            name = "registry",
            doc =
                "Overrides the registry for this module; instead of finding this module from the"
                    + " default list of registries, the given registry should be used.",
            named = true,
            positional = false,
            defaultValue = "''"),
        @Param(
            name = "patches",
            doc =
                "A list of labels pointing to patch files to apply for this module. The patch files"
                    + " must exist in the source tree of the top level project. They are applied in"
                    + " the list order."
                    + ""
                    + "<p>If a patch makes changes to the MODULE.bazel file, these changes will"
                    + " only be effective if the patch file is provided by the root module.",
            allowedTypes = {@ParamType(type = Iterable.class, generic1 = String.class)},
            named = true,
            positional = false,
            defaultValue = "[]"),
        @Param(
            name = "patch_cmds",
            doc =
                "Sequence of Bash commands to be applied on Linux/Macos after patches are applied."
                    + ""
                    + "<p>Changes to the MODULE.bazel file will not be effective.",
            allowedTypes = {@ParamType(type = Iterable.class, generic1 = String.class)},
            named = true,
            positional = false,
            defaultValue = "[]"),
        @Param(
            name = "patch_strip",
            doc = "Same as the --strip argument of Unix patch.",
            named = true,
            positional = false,
            defaultValue = "0"),
      },
      useStarlarkThread = true)
  public void singleVersionOverride(
      String moduleName,
      String version,
      String registry,
      Iterable<?> patches,
      Iterable<?> patchCmds,
      StarlarkInt patchStrip,
      StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context =
        ModuleThreadContext.fromOrFail(thread, "single_version_override()");
    context.setNonModuleCalled();
    validateModuleName(moduleName);
    Version parsedVersion;
    try {
      parsedVersion = Version.parse(version);
    } catch (ParseException e) {
      throw Starlark.errorf("Invalid version in single_version_override(): %s", version);
    }
    ImmutableList.Builder<Label> patchesBuilder = ImmutableList.builder();
    for (String patch : Sequence.cast(patches, String.class, "patches")) {
      patchesBuilder.add(convertAndValidatePatchLabel(context.getModuleBuilder(), patch));
    }
    context.addOverride(
        moduleName,
        SingleVersionOverride.create(
            parsedVersion,
            registry,
            patchesBuilder.build(),
            Sequence.cast(patchCmds, String.class, "patchCmds").getImmutableList(),
            patchStrip.toInt("single_version_override.patch_strip")));
  }

  @StarlarkMethod(
      name = "multiple_version_override",
      doc =
          "Specifies that a dependency should still come from a registry, but multiple versions of"
              + " it should be allowed to coexist. See <a"
              + " href=\"/external/module#multiple-version_override\">the documentation</a> for"
              + " more details. This"
              + " directive only takes effect in the root module; in other words, if a module"
              + " is used as a dependency by others, its own overrides are ignored.",
      parameters = {
        @Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false),
        @Param(
            name = "versions",
            doc =
                "Explicitly specifies the versions allowed to coexist. These versions must already"
                    + " be present in the dependency graph pre-selection. Dependencies on this"
                    + " module will be \"upgraded\" to the nearest higher allowed version at the"
                    + " same compatibility level, whereas dependencies that have a higher version"
                    + " than any allowed versions at the same compatibility level will cause an"
                    + " error.",
            allowedTypes = {@ParamType(type = Iterable.class, generic1 = String.class)},
            named = true,
            positional = false),
        @Param(
            name = "registry",
            doc =
                "Overrides the registry for this module; instead of finding this module from the"
                    + " default list of registries, the given registry should be used.",
            named = true,
            positional = false,
            defaultValue = "''"),
      },
      useStarlarkThread = true)
  public void multipleVersionOverride(
      String moduleName, Iterable<?> versions, String registry, StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context =
        ModuleThreadContext.fromOrFail(thread, "multiple_version_override()");
    context.setNonModuleCalled();
    validateModuleName(moduleName);
    ImmutableList.Builder<Version> parsedVersionsBuilder = new ImmutableList.Builder<>();
    try {
      for (String version : Sequence.cast(versions, String.class, "versions").getImmutableList()) {
        parsedVersionsBuilder.add(Version.parse(version));
      }
    } catch (ParseException e) {
      throw new EvalException("Invalid version in multiple_version_override()", e);
    }
    ImmutableList<Version> parsedVersions = parsedVersionsBuilder.build();
    if (parsedVersions.size() < 2) {
      throw new EvalException("multiple_version_override() must specify at least 2 versions");
    }
    context.addOverride(moduleName, MultipleVersionOverride.create(parsedVersions, registry));
  }

  @StarlarkMethod(
      name = "archive_override",
      doc =
          """
          Specifies that this dependency should come from an archive file (zip, gzip, etc) at a
          certain location, instead of from a registry. Effectively, this dependency will be
          backed by an <a href="../repo/http#http_archive"><code>http_archive</code></a> rule.

          <p>This directive only takes effect in the root module; in other words, if a module is
          used as a dependency by others, its own overrides are ignored.\
          """,
      parameters = {
        @Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false),
      },
      extraKeywords =
          @Param(
              name = "kwargs",
              doc =
                  """
                  All other arguments are forwarded to the underlying <code>http_archive</code> repo
                  rule. Note that the <code>name</code> attribute shouldn't be specified; use
                  <code>module_name</code> instead.\
                  """),
      useStarlarkThread = true)
  public void archiveOverride(String moduleName, Dict<String, Object> kwargs, StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "archive_override()");
    context.setNonModuleCalled();
    validateModuleName(moduleName);
    context.addOverride(
        moduleName,
        new NonRegistryOverride(
            new RepoSpec(ArchiveRepoSpecBuilder.HTTP_ARCHIVE, AttributeValues.create(kwargs))));
  }

  @StarlarkMethod(
      name = "git_override",
      doc =
          """
          Specifies that this dependency should come from a certain commit in a Git repository,
          instead of from a registry. Effectively, this dependency will be backed by a
          <a href="../repo/git#git_repository"><code>git_repository</code></a> rule.

          <p>This directive only takes effect in the root module; in other words, if a module is
          used as a dependency by others, its own overrides are ignored.\
          """,
      parameters = {
        @Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false),
      },
      extraKeywords =
          @Param(
              name = "kwargs",
              doc =
                  """
                  All other arguments are forwarded to the underlying <code>git_repository</code>
                  repo rule. Note that the <code>name</code> attribute shouldn't be specified; use
                  <code>module_name</code> instead.\
                  """),
      useStarlarkThread = true)
  public void gitOverride(String moduleName, Dict<String, Object> kwargs, StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "git_override()");
    context.setNonModuleCalled();
    validateModuleName(moduleName);
    context.addOverride(
        moduleName,
        new NonRegistryOverride(
            new RepoSpec(GitRepoSpecBuilder.GIT_REPOSITORY, AttributeValues.create(kwargs))));
  }

  @StarlarkMethod(
      name = "local_path_override",
      doc =
          """
          Specifies that this dependency should come from a certain directory on local disk,
          instead of from a registry. Effectively, this dependency will be backed by a
          <a href="../repo/local#local_repository"><code>local_repository</code></a> rule.

          <p>This directive only takes effect in the root module; in other words, if a module is
          used as a dependency by others, its own overrides are ignored.\
          """,
      parameters = {
        @Param(
            name = "module_name",
            doc = "The name of the Bazel module dependency to apply this override to.",
            named = true,
            positional = false),
        @Param(
            name = "path",
            doc = "The path to the directory where this module is.",
            named = true,
            positional = false),
      },
      useStarlarkThread = true)
  public void localPathOverride(String moduleName, String path, StarlarkThread thread)
      throws EvalException {
    ModuleThreadContext context = ModuleThreadContext.fromOrFail(thread, "local_path_override()");
    context.setNonModuleCalled();
    validateModuleName(moduleName);
    context.addOverride(moduleName, new NonRegistryOverride(LocalPathRepoSpecs.create(path)));
  }
}
