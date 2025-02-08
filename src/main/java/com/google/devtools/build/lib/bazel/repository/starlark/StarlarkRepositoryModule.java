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

package com.google.devtools.build.lib.bazel.repository.starlark;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.STRING;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtension;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionEvalStarlarkThreadContext;
import com.google.devtools.build.lib.bazel.bzlmod.TagClass;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeValueSource;
import com.google.devtools.build.lib.packages.BzlInitThreadContext;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.packages.RuleFunction;
import com.google.devtools.build.lib.packages.StarlarkExportable;
import com.google.devtools.build.lib.packages.TargetRecorder.NameConflictException;
import com.google.devtools.build.lib.packages.WorkspaceFactoryHelper;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryModuleApi;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

/**
 * The Starlark module containing the definition of {@code repository_rule} function to define a
 * Starlark remote repository.
 */
public class StarlarkRepositoryModule implements RepositoryModuleApi {

  @Override
  public StarlarkCallable repositoryRule(
      StarlarkCallable implementation,
      Object attrs,
      Boolean local,
      Sequence<?> environ, // <String> expected
      Boolean configure,
      Boolean remotable,
      Object doc, // <String> or Starlark.NONE
      StarlarkThread thread)
      throws EvalException {
    // We'll set the name later, pass the empty string for now.
    RuleClass.Builder builder = new RuleClass.Builder("", RuleClassType.WORKSPACE, true);

    ImmutableList<StarlarkThread.CallStackEntry> callstack = thread.getCallStack();
    builder.setCallStack(
        callstack.subList(0, callstack.size() - 1)); // pop 'repository_rule' itself

    builder.addAttribute(attr("$original_name", STRING).defaultValue("").build());
    builder.addAttribute(attr("$local", BOOLEAN).defaultValue(local).build());
    builder.addAttribute(attr("$configure", BOOLEAN).defaultValue(configure).build());
    if (thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC)) {
      builder.addAttribute(attr("$remotable", BOOLEAN).defaultValue(remotable).build());
      BaseRuleClasses.execPropertiesAttribute(builder);
    }
    builder.addAttribute(attr("$environ", STRING_LIST).defaultValue(environ).build());
    BaseRuleClasses.commonCoreAndStarlarkAttributes(builder);
    builder.add(attr("expect_failure", STRING));
    if (attrs != Starlark.NONE) {
      for (Map.Entry<String, Descriptor> attr :
          Dict.cast(attrs, String.class, Descriptor.class, "attrs").entrySet()) {
        Descriptor attrDescriptor = attr.getValue();
        AttributeValueSource source = attrDescriptor.getValueSource();
        String attrName = source.convertToNativeName(attr.getKey());
        if (builder.contains(attrName)) {
          throw Starlark.errorf(
              "There is already a built-in attribute '%s' which cannot be overridden", attrName);
        }
        builder.addAttribute(attrDescriptor.build(attrName));
      }
    }
    builder.setConfiguredTargetFunction(implementation);
    BzlInitThreadContext bzlInitContext = BzlInitThreadContext.fromOrNull(thread);
    if (bzlInitContext != null) {
      builder.setRuleDefinitionEnvironmentLabelAndDigest(
          bzlInitContext.getBzlFile(), bzlInitContext.getTransitiveDigest());
    } else {
      // TODO: this branch is wrong, but cannot be removed until we deprecate WORKSPACE because
      //   WORKSPACE can currently call unexported repo rules (so there's potentially no
      //   BzlInitThreadContext. See
      //   https://github.com/bazelbuild/bazel/pull/21131#discussion_r1471924084 for more details.
      BazelModuleContext moduleContext = BazelModuleContext.ofInnermostBzlOrThrow(thread);
      builder.setRuleDefinitionEnvironmentLabelAndDigest(
          moduleContext.label(), moduleContext.bzlTransitiveDigest());
    }
    Label.RepoMappingRecorder repoMappingRecorder =
        thread.getThreadLocal(Label.RepoMappingRecorder.class);
    if (repoMappingRecorder != null) {
      builder.setRuleDefinitionEnvironmentRepoMappingEntries(repoMappingRecorder.recordedEntries());
    }
    builder.setWorkspaceOnly();
    return new RepositoryRuleFunction(
        builder,
        implementation,
        Starlark.toJavaOptional(doc, String.class).map(Starlark::trimDocString));
  }

  /**
   * The value returned by calling the {@code repository_rule} function in Starlark. It itself is a
   * callable value; calling it yields a {@link Rule} instance.
   */
  @StarlarkBuiltin(
      name = "repository_rule",
      category = DocCategory.BUILTIN,
      doc =
          """
A callable value that may be invoked during evaluation of the WORKSPACE file or within \
the implementation function of a module extension to instantiate and return a repository \
rule. Created by \
<a href="../globals/bzl.html#repository_rule"><code>repository_rule()</code></a>.
""")
  public static final class RepositoryRuleFunction
      implements StarlarkCallable, StarlarkExportable, RuleFunction {
    private final RuleClass.Builder builder;
    private final StarlarkCallable implementation;
    private final Optional<String> documentation;
    @Nullable private Label extensionLabel;
    @Nullable private String exportedName;

    private RepositoryRuleFunction(
        RuleClass.Builder builder,
        StarlarkCallable implementation,
        Optional<String> documentation) {
      this.builder = builder;
      this.implementation = implementation;
      this.documentation = documentation;
    }

    @Override
    public String getName() {
      return "repository_rule";
    }

    /**
     * Returns the value of the doc parameter passed to {@code repository_rule()} in Starlark, or an
     * empty Optional if a doc string was not provided.
     */
    public Optional<String> getDocumentation() {
      return documentation;
    }

    /**
     * Returns the label of the .bzl module where {@code repository_rule()} was called, or null if
     * the rule has not been exported yet.
     */
    @Nullable
    public Label getExtensionLabel() {
      return extensionLabel;
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public void export(EventHandler handler, Label extensionLabel, String exportedName) {
      this.extensionLabel = extensionLabel;
      this.exportedName = exportedName;
    }

    @Override
    public boolean isExported() {
      return extensionLabel != null;
    }

    @Override
    public void repr(Printer printer) {
      if (exportedName == null) {
        printer.append("<anonymous starlark repository rule>");
      } else {
        printer.append("<starlark repository rule " + extensionLabel + "%" + exportedName + ">");
      }
    }

    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
        throws EvalException, InterruptedException {
      if (!args.isEmpty()) {
        throw new EvalException("unexpected positional arguments");
      }
      // Decide whether we're operating in the new mode (during module extension evaluation) or in
      // legacy mode (during workspace evaluation).
      ModuleExtensionEvalStarlarkThreadContext extensionEvalContext =
          ModuleExtensionEvalStarlarkThreadContext.fromOrNull(thread);
      if (extensionEvalContext == null) {
        return createRuleLegacy(thread, kwargs);
      }
      if (!isExported()) {
        throw new EvalException("attempting to instantiate a non-exported repository rule");
      }
      extensionEvalContext.lazilyCreateRepo(thread, kwargs, getRuleClass());
      return Starlark.NONE;
    }

    private String getRuleClassName() {
      // If the function ever got exported (the common case), we take the name
      // it was exported to. Only in the not intended case of calling an unexported
      // repository function through an exported macro, we fall back, for lack of
      // alternatives, to the name in the local context.
      // TODO(b/111199163): we probably should disallow the use of non-exported
      // repository rules anyway.
      if (isExported()) {
        return exportedName;
      } else {
        // repository_rules should be subject to the same "exported" requirement
        // as package rules, but sadly we forgot to add the necessary check and
        // now many projects create and instantiate repository_rules without an
        // intervening export; see b/111199163. An incompatible flag is required.

        // The historical workaround was a fragile hack to introspect on the call
        // expression syntax, f() or x.f(), to find the name f, but we no longer
        // have access to the call expression, so now we just create an ugly
        // name from the function. See github.com/bazelbuild/bazel/issues/10441
        return "unexported_" + implementation.getName();
      }
    }

    private Object createRuleLegacy(StarlarkThread thread, Dict<String, Object> kwargs)
        throws EvalException, InterruptedException {
      String ruleClassName = getRuleClassName();
      try {
        RuleClass ruleClass = builder.buildStarlark(ruleClassName, extensionLabel);
        Package.Builder pkgBuilder =
            Package.Builder.fromOrFailAllowWorkspaceOnly(thread, "repository rules");

        // TODO(adonovan): is this cast safe? Check.
        String name = (String) kwargs.get("name");
        if (name == null) {
          throw Starlark.errorf("argument 'name' is required");
        }
        WorkspaceFactoryHelper.addMainRepoEntry(pkgBuilder, name);
        WorkspaceFactoryHelper.addRepoMappings(pkgBuilder, kwargs, name);
        // Note that RuleFactory#createAndAddRule checks that the package is a repo rule package.
        return WorkspaceFactoryHelper.createAndAddRepositoryRule(
            pkgBuilder,
            ruleClass,
            WorkspaceFactoryHelper.getFinalKwargs(kwargs),
            thread.getCallStack());
      } catch (InvalidRuleException | NameConflictException | LabelSyntaxException e) {
        throw Starlark.errorf("%s", e.getMessage());
      }
    }

    @Override
    public RuleClass getRuleClass() {
      String name = getRuleClassName();
      return builder.buildStarlark(name, extensionLabel);
    }
  }

  @Override
  public void failWithIncompatibleUseCcConfigureFromRulesCc(StarlarkThread thread)
      throws EvalException {
    if (thread
        .getSemantics()
        .getBool(BuildLanguageOptions.INCOMPATIBLE_USE_CC_CONFIGURE_FROM_RULES_CC)) {
      throw Starlark.errorf(
          "Incompatible flag "
              + "--incompatible_use_cc_configure_from_rules_cc has been flipped. Please use "
              + "cc_configure and related logic from https://github.com/bazelbuild/rules_cc. "
              + "See https://github.com/bazelbuild/bazel/issues/10134 for details and migration "
              + "instructions.");
    }
  }

  @Override
  public Object moduleExtension(
      StarlarkCallable implementation,
      Dict<?, ?> tagClasses, // Dict<String, TagClass>
      Object doc, // <String> or Starlark.NONE
      Sequence<?> environ, // <String>
      boolean osDependent,
      boolean archDependent,
      StarlarkThread thread)
      throws EvalException {
    return ModuleExtension.builder()
        .setImplementation(implementation)
        .setTagClasses(
            ImmutableMap.copyOf(Dict.cast(tagClasses, String.class, TagClass.class, "tag_classes")))
        .setDoc(Starlark.toJavaOptional(doc, String.class).map(Starlark::trimDocString))
        .setDefiningBzlFileLabel(
            BzlInitThreadContext.fromOrFail(thread, "module_extension()").getBzlFile())
        .setEnvVariables(ImmutableList.copyOf(Sequence.cast(environ, String.class, "environ")))
        .setLocation(thread.getCallerLocation())
        .setOsDependent(osDependent)
        .setArchDependent(archDependent)
        .build();
  }

  @Override
  public TagClass tagClass(
      Dict<?, ?> attrs, // Dict<String, StarlarkAttrModule.Descriptor>
      Object doc // <String> or Starlark.NONE
      ) throws EvalException {
    ImmutableList.Builder<Attribute> attrBuilder = ImmutableList.builder();
    for (Map.Entry<String, Descriptor> attr :
        Dict.cast(attrs, String.class, Descriptor.class, "attrs").entrySet()) {
      Descriptor attrDescriptor = attr.getValue();
      AttributeValueSource source = attrDescriptor.getValueSource();
      String attrName = source.convertToNativeName(attr.getKey());
      attrBuilder.add(attrDescriptor.build(attrName));
      // TODO(wyv): validate attributes. No selects, no latebound defaults, or any crazy stuff like
      //   that.
    }
    return TagClass.create(
        attrBuilder.build(),
        Starlark.toJavaOptional(doc, String.class).map(Starlark::trimDocString));
  }
}
