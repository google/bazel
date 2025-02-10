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

package com.google.devtools.build.lib.analysis.starlark;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition.PATCH_TRANSITION_KEY;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.ActionsProvider;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.AspectContext;
import com.google.devtools.build.lib.analysis.BashCommandConstructor;
import com.google.devtools.build.lib.analysis.CommandHelper;
import com.google.devtools.build.lib.analysis.ConfigurationMakeVariableContext;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.LocationExpander;
import com.google.devtools.build.lib.analysis.ResolvedToolchainContext;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.ShToolchain;
import com.google.devtools.build.lib.analysis.SymlinkEntry;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.FragmentCollection;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.starlark.StarlarkActionFactory.StarlarkActionContext;
import com.google.devtools.build.lib.analysis.starlark.StarlarkSubrule.SubruleContext;
import com.google.devtools.build.lib.analysis.stringtemplate.ExpansionException;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.BuildSetting;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.shell.ShellUtils;
import com.google.devtools.build.lib.shell.ShellUtils.TokenizationException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkRuleContextApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkSubruleApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainContextApi;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Dict.ImmutableKeyTrackingDict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Structure;
import net.starlark.java.eval.Tuple;

/**
 * A Starlark API for the ruleContext.
 *
 * <p>"This object becomes featureless once the rule implementation function that it was created for
 * has completed. To achieve this, the {@link #close()} should be called once the evaluation of the
 * function is completed. The method both frees memory by deleting all significant fields of the
 * object and makes it impossible to accidentally use this object where it's not supposed to be used
 * (such attempts will result in {@link EvalException}s).
 */
public final class StarlarkRuleContext
    implements StarlarkRuleContextApi<ConstraintValueInfo>, StarlarkActionContext {

  private static final String EXECUTABLE_OUTPUT_NAME = "executable";

  // This field is a copy of the info from ruleContext, stored separately so it can be accessed
  // after this object has been nullified.
  private final String ruleLabelCanonicalName;

  private final boolean isForAspect;

  private final StarlarkActionFactory actionFactory;

  // The fields below are intended to be final except that they can be cleared by calling
  // `close()` when the object becomes featureless (analogous to freezing).
  private RuleContext ruleContext;
  private FragmentCollection fragments;
  @Nullable private AspectDescriptor aspectDescriptor;

  // The current rule class under evaluation (in case of extended rule this changes to parent when
  // ctx.super is called)
  private RuleClass ruleClassUnderEvaluation;

  // Was super called in the context of current parent, it's set to false each time
  // ruleClassUnderEvaluation changes, and it's expected to be set to true when ctx.super is called.
  private boolean superCalled;

  /**
   * This variable is used to expose the state of {@link
   * RuleContext#configurationMakeVariableContext} to the user via {@code ctx.var}.
   *
   * <p>Computing this field causes a side-effect of initializing the Make var context.
   *
   * <p>Note that StarlarkRuleContext can (for pathological user-written rules) survive the analysis
   * phase and be accessed concurrently. Nonetheless, it is still safe to initialize {@code ctx.var}
   * lazily without synchronization, because {@code ctx.var} is inaccessible once {@code close()}
   * has been called.
   */
  private Dict<String, String> cachedMakeVariables = null;

  private StarlarkAttributesCollection attributesCollection;
  private StarlarkAttributesCollection ruleAttributesCollection;
  private StructImpl splitAttributes;
  private Outputs outputsObject;

  /**
   * Counter for calls to {@code ctx.resolve_command} with a command longer than {@link
   * CommandHelper#maxCommandLength}.
   *
   * <p>Such calls require generating a script. This counter ensures that each call results in
   * unique script name to avoid action conflicts.
   */
  private int resolveCommandScriptCounter = 0;

  // for temporarily freezing mutability, while evaluating a subrule this is set to the
  // corresponding subrule context, or is null otherwise
  @Nullable private SubruleContext lockedForSubruleEvaluation = null;

  /**
   * Creates a new StarlarkRuleContext wrapping ruleContext.
   *
   * <p>{@code aspectDescriptor} is the aspect for which the context is created, or <code>
   * null</code> if it is for a rule.
   */
  public StarlarkRuleContext(RuleContext ruleContext, @Nullable AspectDescriptor aspectDescriptor)
      throws RuleErrorException {
    // Init ruleContext first, we need it to obtain the StarlarkSemantics used by
    // StarlarkActionFactory (and possibly others).
    this.ruleContext = Preconditions.checkNotNull(ruleContext);
    this.actionFactory = new StarlarkActionFactory(this);
    this.ruleLabelCanonicalName = ruleContext.getLabel().getCanonicalForm();
    this.fragments = new FragmentCollection(ruleContext);
    this.aspectDescriptor = aspectDescriptor;
    this.isForAspect = aspectDescriptor != null;
    this.ruleClassUnderEvaluation = ruleContext.getRule().getRuleClassObject();

    Rule rule = ruleContext.getRule();

    if (aspectDescriptor == null) {
      Collection<Attribute> attributes =
          rule.getAttributes().stream()
              .filter(attribute -> !attribute.getName().equals("aspect_hints"))
              .collect(Collectors.toList());

      // Populate ctx.outputs.
      Outputs outputs = new Outputs(this);
      // These getters do some computational work to return a view, so ensure we only do it once.
      ImmutableListMultimap<String, OutputFile> explicitOutMap = rule.getExplicitOutputFileMap();
      ImmutableMap<String, OutputFile> implicitOutMap = rule.getStarlarkImplicitOutputFileMap();
      // Add the explicit outputs -- values of attributes of type OUTPUT or OUTPUT_LIST.
      // We must iterate over the attribute definitions, and not just the entries in the
      // explicitOutMap, because the latter omits empty output attributes, which must still
      // generate None or [] fields in the struct.
      for (Attribute a : attributes) {
        // Skip non-output attrs.
        String attrName = a.getName();
        Type<?> type = a.getType();
        if (type.getLabelClass() != LabelClass.OUTPUT) {
          continue;
        }

        // Grab all associated outputs.
        ImmutableList.Builder<Artifact> artifactsBuilder = ImmutableList.builder();
        for (OutputFile outputFile : explicitOutMap.get(attrName)) {
          artifactsBuilder.add(ruleContext.createOutputArtifact(outputFile));
        }
        StarlarkList<Artifact> artifacts = StarlarkList.immutableCopyOf(artifactsBuilder.build());

        // For singular output attributes, unwrap sole element or else use None for arity mismatch.
        if (type == BuildType.OUTPUT) {
          if (artifacts.size() == 1) {
            outputs.addOutput(attrName, Iterables.getOnlyElement(artifacts));
          } else {
            outputs.addOutput(attrName, Starlark.NONE);
          }
        } else if (type == BuildType.OUTPUT_LIST) {
          outputs.addOutput(attrName, artifacts);
        } else {
          throw ruleContext.throwWithRuleError(
              String.format("Attribute %s has unexpected output type %s", attrName, type));
        }
      }
      // Add the implicit outputs. In the case where the rule has a native-defined implicit outputs
      // function, nothing is added. Note that Rule ensures that Starlark-defined implicit output
      // keys don't conflict with output attribute names.
      // TODO(bazel-team): Also see about requiring the key to be a valid Starlark identifier.
      for (Map.Entry<String, OutputFile> e : implicitOutMap.entrySet()) {
        outputs.addOutput(e.getKey(), ruleContext.createOutputArtifact(e.getValue()));
      }

      this.outputsObject = outputs;

      // Populate ctx.attr.
      StarlarkAttributesCollection.Builder builder =
          StarlarkAttributesCollection.builder(this, ruleContext.getRulePrerequisitesCollection());
      for (Attribute attribute : attributes) {
        Object value = ruleContext.attributes().get(attribute.getName(), attribute.getType());
        builder.addAttribute(attribute, value);
      }

      this.attributesCollection = builder.build();
      this.splitAttributes = buildSplitAttributeInfo(attributes, ruleContext);
      this.ruleAttributesCollection = null;
    } else { // ASPECT
      this.outputsObject = null;
      ImmutableCollection<Attribute> attributes =
          ruleContext.getMainAspect().getDefinition().getAttributes().values();

      StarlarkAttributesCollection.Builder aspectBuilder =
          StarlarkAttributesCollection.builder(
              this, ((AspectContext) ruleContext).getMainAspectPrerequisitesCollection());
      for (Attribute attribute : attributes) {
        Object defaultValue = attribute.getDefaultValue(null);
        if (defaultValue instanceof ComputedDefault computedDefault) {
          defaultValue = computedDefault.getDefault(ruleContext.attributes());
        }
        aspectBuilder.addAttribute(attribute, defaultValue);
      }
      this.attributesCollection = aspectBuilder.build();

      this.splitAttributes = null;
      StarlarkAttributesCollection.Builder ruleBuilder =
          StarlarkAttributesCollection.builder(this, ruleContext.getRulePrerequisitesCollection());
      try {
        Dict<String, String> makeVariables =
            ((AspectContext) ruleContext)
                .getBaseTargetConfigurationMakeVariableContext()
                .collectMakeVariables()
                .buildImmutable();
        ruleBuilder.putAllRuleVariables(makeVariables);
      } catch (ExpansionException e) {
        throw ruleContext.throwWithRuleError("Exception expanding template variables", e);
      }

      for (Attribute attribute : rule.getAttributes()) {
        Object value = ruleContext.attributes().get(attribute.getName(), attribute.getType());
        ruleBuilder.addAttribute(attribute, value);
      }
      for (Aspect aspect : ruleContext.getAspects()) {
        if (aspect.equals(ruleContext.getMainAspect())) {
          // Aspect's own attributes are in <code>attributesCollection</code>.
          continue;
        }
        for (Attribute attribute : aspect.getDefinition().getAttributes().values()) {
          Object defaultValue = attribute.getDefaultValue(null);
          if (defaultValue instanceof ComputedDefault computedDefault) {
            defaultValue = computedDefault.getDefault(ruleContext.attributes());
          }
          ruleBuilder.addAttribute(attribute, defaultValue);
        }
      }

      this.ruleAttributesCollection = ruleBuilder.build();
    }
  }

  /** Returns the subrules declared by the rule or aspect represented by this context. */
  ImmutableSet<? extends StarlarkSubruleApi> getSubrules() {
    if (isForAspect()) {
      return getRuleContext().getMainAspect().getDefinition().getSubrules();
    } else {
      return getRuleClassUnderEvaluation().getSubrules();
    }
  }

  public RuleClass getRuleClassUnderEvaluation() {
    return ruleClassUnderEvaluation;
  }

  void setLockedForSubrule(@Nullable SubruleContext lockedBy) {
    this.lockedForSubruleEvaluation = lockedBy;
  }

  SubruleContext getLockedForSubrule() {
    return lockedForSubruleEvaluation;
  }

  /**
   * Represents `ctx.outputs`.
   *
   * <p>The value of its {@code ctx.outputs.executable} field is computed on-demand.
   *
   * <p>Note: There is only one {@code Outputs} object per rule context, so default (object
   * identity) equals and hashCode suffice.
   */
  // TODO(adonovan): add StarlarkBuiltin(name="ctx.outputs") annotation.
  private static class Outputs implements Structure, StarlarkValue {
    private final Map<String, Object> outputs;
    private final StarlarkRuleContext context;
    private boolean executableCreated = false;

    Outputs(StarlarkRuleContext context) {
      this.outputs = new LinkedHashMap<>();
      this.context = context;
    }

    private void addOutput(String key, Object value) throws RuleErrorException {
      Preconditions.checkState(!context.isImmutable());
      // TODO(bazel-team): We should reject outputs whose key is not an identifier. Today this is
      // allowed, and the resulting ctx.outputs value can be retrieved using getattr().
      if (outputs.containsKey(key)
          || (context.isExecutable() && EXECUTABLE_OUTPUT_NAME.equals(key))) {
        context.getRuleContext().throwWithRuleError("Multiple outputs with the same key: " + key);
      }
      outputs.put(key, value);
    }

    @Override
    public boolean isImmutable() {
      return context.isImmutable();
    }

    @Override
    public ImmutableCollection<String> getFieldNames() {
      // TODO(b/175954936): There's an NPE here when accessing dir(ctx.outputs) after rule
      // analysis has completed. Since we can't throw EvalException here, this may require that we
      // preemptively copy the fields into this object, or at least keep a "nullified" bit so we
      // know to produce an empty result here.
      ImmutableList.Builder<String> result = ImmutableList.builder();
      if (context.isExecutable() && executableCreated) {
        result.add(EXECUTABLE_OUTPUT_NAME);
      }
      result.addAll(outputs.keySet());
      return result.build();
    }

    @Nullable
    @Override
    public Object getValue(String name) throws EvalException {
      checkMutable();
      if (context.isExecutable() && EXECUTABLE_OUTPUT_NAME.equals(name)) {
        executableCreated = true;
        // createOutputArtifact() will cache the created artifact.
        return context.getRuleContext().createOutputArtifact();
      }

      return outputs.get(name);
    }

    @Nullable
    @Override
    public String getErrorMessageForUnknownField(String name) {
      return String.format(
          "No attribute '%s' in outputs. Make sure you declared a rule output with this name.",
          name);
    }

    @Override
    public void repr(Printer printer) {
      if (isImmutable()) {
        printer.append("ctx.outputs(for ");
        printer.append(context.ruleLabelCanonicalName);
        printer.append(")");
        return;
      }
      boolean first = true;
      printer.append("ctx.outputs(");
      // Sort by field name to ensure deterministic output.
      try {
        for (String field : Ordering.natural().sortedCopy(getFieldNames())) {
          if (!first) {
            printer.append(", ");
          }
          first = false;
          printer.append(field);
          printer.append(" = ");
          printer.repr(getValue(field));
        }
        printer.append(")");
      } catch (EvalException e) {
        throw new AssertionError("mutable ctx.outputs should not throw", e);
      }
    }

    private void checkMutable() throws EvalException {
      if (isImmutable()) {
        throw Starlark.errorf(
            "cannot access outputs of rule '%s' outside of its own rule implementation function",
            context.ruleLabelCanonicalName);
      }
    }
  }

  public boolean isExecutable() {
    return ruleContext.getRule().getRuleClassObject().isExecutableStarlark();
  }

  public boolean isDefaultExecutableCreated() {
    return this.outputsObject.executableCreated;
  }

  /**
   * Nullifies fields of the object when it's not supposed to be used anymore to free unused memory
   * and to make sure this object is not accessed when it's not supposed to (after the corresponding
   * rule implementation function has exited).
   *
   * <p>Does a check if parent was called.
   */
  public void close() {
    // Check super was called
    if (ruleClassUnderEvaluation.getStarlarkParent() != null && !superCalled && !isForAspect()) {
      ruleContext.ruleError("'super' was not called.");
    }

    ruleContext = null;
    fragments = null;
    aspectDescriptor = null;
    cachedMakeVariables = null;
    attributesCollection = null;
    ruleAttributesCollection = null;
    splitAttributes = null;
    outputsObject = null;
  }

  /** Returns the {@link ArtifactRoot} for newly declared artifacts for use in actions. */
  @Override
  public ArtifactRoot newFileRoot() {
    return isForAspect()
        ? getRuleContext().getBinDirectory()
        : getRuleContext().getBinOrGenfilesDirectory();
  }

  /** Throws an EvalException mentioning {@code attrName} if we've already been nullified. */
  @Override
  public void checkMutable(String attrName) throws EvalException {
    if (isImmutable()) {
      throw Starlark.errorf(
          "cannot access field or method '%s' of rule context for '%s' outside of its own rule "
              + "implementation function",
          attrName, ruleLabelCanonicalName);
    }
  }

  @Nullable
  public AspectDescriptor getAspectDescriptor() {
    return aspectDescriptor;
  }

  String getRuleLabelCanonicalName() {
    return ruleLabelCanonicalName;
  }

  private static StructImpl buildSplitAttributeInfo(
      Collection<Attribute> attributes, RuleContext ruleContext) {

    ImmutableMap.Builder<String, Object> splitAttrInfos = ImmutableMap.builder();
    for (Attribute attr : attributes) {
      if (!attr.getTransitionFactory().isSplit()) {
        continue;
      }
      Map<Optional<String>, List<ConfiguredTargetAndData>> splitPrereqs =
          ruleContext.getRulePrerequisitesCollection().getSplitPrerequisites(attr.getName());

      Map<Object, Object> splitPrereqsMap = new LinkedHashMap<>();
      for (Map.Entry<Optional<String>, List<ConfiguredTargetAndData>> splitPrereq :
          splitPrereqs.entrySet()) {

        // Skip a split with an empty dependency list.
        // TODO(jungjw): Figure out exactly which cases trigger this and see if this can be made
        // more error-proof.
        if (splitPrereq.getValue().isEmpty()) {
          continue;
        }

        Object value;
        if (attr.getType() == BuildType.LABEL) {
          Preconditions.checkState(splitPrereq.getValue().size() == 1);
          value = splitPrereq.getValue().get(0).getConfiguredTarget();
        } else if (attr.getType() == BuildType.LABEL_DICT_UNARY) {
          ImmutableList<ConfiguredTarget> prerequisites =
              splitPrereq.getValue().stream()
                  .map(ConfiguredTargetAndData::getConfiguredTarget)
                  .collect(ImmutableList.toImmutableList());

          value =
              StarlarkAttributesCollection.Builder.convertStringToLabelMap(
                  ruleContext.attributes().get(attr.getName(), BuildType.LABEL_DICT_UNARY),
                  prerequisites);
        } else {
          // BuildType.LABEL_LIST
          value =
              StarlarkList.immutableCopyOf(
                  splitPrereq.getValue().stream()
                      .map(ConfiguredTargetAndData::getConfiguredTarget)
                      .collect(toImmutableList()));
        }

        if (splitPrereq.getKey().isPresent()
            && !splitPrereq.getKey().get().equals(PATCH_TRANSITION_KEY)) {
          splitPrereqsMap.put(splitPrereq.getKey().get(), value);
        } else {
          // If the split transition is not in effect, then the key will be missing since there's
          // nothing to key on because the dependencies aren't split and getSplitPrerequisites()
          // behaves like getPrerequisites(). This also means there should be only one entry in
          // the map. Use None in Starlark to represent this.
          Preconditions.checkState(splitPrereqs.size() == 1);
          splitPrereqsMap.put(Starlark.NONE, value);
        }
      }

      splitAttrInfos.put(attr.getPublicName(), Dict.immutableCopyOf(splitPrereqsMap));
    }

    return StructProvider.STRUCT.create(
        splitAttrInfos.buildOrThrow(),
        "No attribute '%s' in split_attr."
            + "This attribute is not defined with a split configuration.");
  }

  @Override
  public boolean isImmutable() {
    return ruleContext == null || lockedForSubruleEvaluation != null;
  }

  @Override
  public void repr(Printer printer) {
    if (isForAspect) {
      printer.append("<aspect context for " + ruleLabelCanonicalName + ">");
    } else {
      printer.append("<rule context for " + ruleLabelCanonicalName + ">");
    }
  }

  /** Returns the wrapped ruleContext. */
  @Override
  public RuleContext getRuleContext() {
    return ruleContext;
  }

  @Override
  public StarlarkActionFactory actions() {
    return actionFactory;
  }

  @Override
  public Object callParent(StarlarkThread thread) throws EvalException, InterruptedException {
    checkMutable("super()");
    if (isForAspect()) {
      throw Starlark.errorf("Can't use 'super' call in an aspect.");
    }
    if (ruleClassUnderEvaluation.getStarlarkParent() == null) {
      throw Starlark.errorf("Can't use 'super' call, the rule has no parent.");
    }
    if (superCalled) {
      throw Starlark.errorf("'super' called the second time.");
    }

    RuleClass previousClassUnderEvaluation = ruleClassUnderEvaluation;
    ruleClassUnderEvaluation = ruleClassUnderEvaluation.getStarlarkParent();

    Object rawProviders = null;
    try {
      superCalled = false;
      rawProviders =
          StarlarkRuleConfiguredTargetUtil.evalRule(ruleContext, ruleClassUnderEvaluation);

    } finally {
      if (ruleClassUnderEvaluation.getStarlarkParent() != null && !superCalled) {
        ruleContext.ruleError(
            String.format(
                "in %s rule: 'super' was not called.", ruleClassUnderEvaluation.getName()));
      }
      ruleClassUnderEvaluation = previousClassUnderEvaluation;
    }

    if (rawProviders == null) {
      throw Starlark.errorf("Error evaluating parent rule.");
    }

    // Normalize the return type
    if (rawProviders instanceof Info info) {
      // Either an old-style struct or a single declared provider (not in a list)
      if (info.getProvider().getKey().equals(StructProvider.STRUCT.getKey())) {
        throw Starlark.errorf(
            "Parent rule returned struct providers. Rules returning struct providers can't be"
                + " extended.");
      }
      rawProviders = StarlarkList.of(thread.mutability(), rawProviders);
    } else if (rawProviders == Starlark.NONE) {
      rawProviders = StarlarkList.empty();
    }
    superCalled = true;

    return rawProviders;
  }

  @Override
  public StarlarkValue createdActions() throws EvalException {
    checkMutable("created_actions");
    if (ruleContext.getRule().getRuleClassObject().isStarlarkTestable()) {
      return ActionsProvider.create(ruleContext.getAnalysisEnvironment().getRegisteredActions());
    } else {
      return Starlark.NONE;
    }
  }

  @Override
  public StructImpl getAttr() throws EvalException {
    checkMutable("attr");
    return attributesCollection.getAttr();
  }

  @Override
  public StructImpl getSplitAttr() throws EvalException {
    checkMutable("split_attr");
    if (splitAttributes == null) {
      throw new EvalException("'split_attr' is available only in rule implementations");
    }
    return splitAttributes;
  }

  /** See {@link RuleContext#getExecutablePrerequisite(String)}. */
  @Override
  public StructImpl getExecutable() throws EvalException {
    checkMutable("executable");
    return attributesCollection.getExecutable();
  }

  /** See {@link RuleContext#getPrerequisiteArtifact(String)}. */
  @Override
  public StructImpl getFile() throws EvalException {
    checkMutable("file");
    return attributesCollection.getFile();
  }

  /** See {@link RuleContext#getPrerequisiteArtifacts(String)}. */
  @Override
  public StructImpl getFiles() throws EvalException {
    checkMutable("files");
    return attributesCollection.getFiles();
  }

  @Override
  public String getWorkspaceName() throws EvalException {
    checkMutable("workspace_name");
    return ruleContext.getWorkspaceName();
  }

  @Override
  public Label getLabel() throws EvalException {
    checkMutable("label");
    return ruleContext.getLabel();
  }

  @Override
  public FragmentCollection getFragments() throws EvalException {
    checkMutable("fragments");
    return fragments;
  }

  @Override
  public BuildConfigurationValue getConfiguration() throws EvalException {
    checkMutable("configuration");
    return ruleContext.getConfiguration();
  }

  @Override
  @Nullable
  public Object getBuildSettingValue() throws EvalException {
    if (ruleContext.getRule().getRuleClassObject().getBuildSetting() == null) {
      throw Starlark.errorf(
          "attempting to access 'build_setting_value' of non-build setting %s",
          ruleLabelCanonicalName);
    }
    ImmutableMap<Label, Object> starlarkFlagSettings =
        ruleContext.getConfiguration().getOptions().getStarlarkOptions();

    BuildSetting buildSetting = ruleContext.getRule().getRuleClassObject().getBuildSetting();
    if (starlarkFlagSettings.containsKey(ruleContext.getLabel())) {
      return starlarkFlagSettings.get(ruleContext.getLabel());
    } else {
      Object defaultValue =
          ruleContext
              .attributes()
              .get(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, buildSetting.getType());
      return buildSetting.allowsMultiple() ? ImmutableList.of(defaultValue) : defaultValue;
    }
  }

  @Override
  public boolean instrumentCoverage(Object targetUnchecked) throws EvalException {
    checkMutable("coverage_instrumented");
    BuildConfigurationValue config = ruleContext.getConfiguration();
    if (!config.isCodeCoverageEnabled()) {
      return false;
    }
    if (targetUnchecked == Starlark.NONE) {
      return InstrumentedFilesCollector.shouldIncludeLocalSources(
          ruleContext.getConfiguration(), ruleContext.getLabel(), ruleContext.isTestTarget());
    }
    TransitiveInfoCollection target = (TransitiveInfoCollection) targetUnchecked;
    return (target.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR) != null)
        && InstrumentedFilesCollector.shouldIncludeLocalSources(config, target);
  }

  @Override
  public ImmutableList<String> getFeatures() throws EvalException {
    checkMutable("features");
    return ImmutableList.copyOf(ruleContext.getFeatures());
  }

  @Override
  public ImmutableList<String> getDisabledFeatures() throws EvalException {
    checkMutable("disabled_features");
    return ImmutableList.copyOf(ruleContext.getDisabledFeatures());
  }

  @Override
  public ArtifactRoot getBinDirectory() throws EvalException {
    checkMutable("bin_dir");
    return getConfiguration().getBinDirectory(ruleContext.getRule().getRepository());
  }

  @Override
  public ArtifactRoot getGenfilesDirectory() throws EvalException {
    checkMutable("genfiles_dir");
    return getConfiguration().getGenfilesDirectory(ruleContext.getRule().getRepository());
  }

  @Override
  public Structure outputs() throws EvalException {
    checkMutable("outputs");
    if (outputsObject == null) {
      throw new EvalException("'outputs' is not defined");
    }
    return outputsObject;
  }

  @Override
  public StarlarkAttributesCollection rule() throws EvalException {
    checkMutable("rule");
    if (!isForAspect) {
      throw new EvalException("'rule' is only available in aspect implementations");
    }
    return ruleAttributesCollection;
  }

  @Override
  public ImmutableList<String> aspectIds() throws EvalException {
    checkMutable("aspect_ids");
    if (!isForAspect) {
      throw new EvalException("'aspect_ids' is only available in aspect implementations");
    }

    ImmutableList.Builder<String> result = ImmutableList.builder();
    for (AspectDescriptor descriptor : ruleContext.getAspectDescriptors()) {
      result.add(descriptor.getDescription());
    }
    return result.build();
  }

  @Override
  public Dict<String, String> var() throws EvalException {
    checkMutable("var");
    if (cachedMakeVariables == null) {
      Dict.Builder<String, String> vars;
      try {
        vars = ruleContext.getConfigurationMakeVariableContext().collectMakeVariables();
      } catch (ExpansionException e) {
        throw new EvalException(e.getMessage());
      }

      // When tracking required fragments, use a key-tracking dict to support lookedUpVariables().
      cachedMakeVariables =
          ruleContext.shouldIncludeRequiredConfigFragmentsProvider()
              ? vars.buildImmutableWithKeyTracking()
              : vars.buildImmutable();
    }
    return cachedMakeVariables;
  }

  /** Returns the set of variables accessed through {@code ctx.var}. */
  public ImmutableSet<String> lookedUpVariables() {
    Preconditions.checkState(ruleContext.shouldIncludeRequiredConfigFragmentsProvider(), this);
    return cachedMakeVariables == null
        ? ImmutableSet.of()
        : ((ImmutableKeyTrackingDict<String, String>) cachedMakeVariables).getAccessedKeys();
  }

  // visible for subrules
  ImmutableSet<Label> getRequestedToolchainTypeLabelsFromAutoExecGroups() {
    ToolchainCollection<ResolvedToolchainContext> toolchainContexts =
        ruleContext.getToolchainContexts();

    return toolchainContexts.getExecGroupNames().stream()
        .filter(ExecGroup::isAutomatic)
        .flatMap(
            execGroupName ->
                toolchainContexts
                    .getToolchainContext(execGroupName)
                    .requestedToolchainTypeLabels()
                    .keySet()
                    .stream())
        .collect(toImmutableSet());
  }

  @Override
  public ToolchainContextApi toolchains() throws EvalException {
    checkMutable("toolchains");

    if (ruleContext.getToolchainContext() == null) {
      return StarlarkToolchainContext.TOOLCHAINS_NOT_VALID;
    }

    if (ruleContext.useAutoExecGroups()) {
      return StarlarkToolchainContext.create(
          /* targetDescription= */ ruleContext.getToolchainContext().targetDescription(),
          /* resolveToolchainDataFunc= */ ruleContext::getToolchainInfo,
          /* resolvedToolchainTypeLabels= */ getRequestedToolchainTypeLabelsFromAutoExecGroups());
    } else {
      return StarlarkToolchainContext.create(
          /* targetDescription= */ ruleContext.getToolchainContext().targetDescription(),
          /* resolveToolchainDataFunc= */ ruleContext.getToolchainContext()::forToolchainType,
          /* resolvedToolchainTypeLabels= */ ruleContext
              .getToolchainContext()
              .requestedToolchainTypeLabels()
              .keySet());
    }
  }

  @Override
  public boolean targetPlatformHasConstraint(ConstraintValueInfo constraintValue) {
    return ruleContext.targetPlatformHasConstraint(constraintValue);
  }

  @Override
  public StarlarkExecGroupCollection execGroups() {
    // Create a thin wrapper around the toolchain collection, to expose the Starlark API.
    return StarlarkExecGroupCollection.create(ruleContext.getToolchainContexts());
  }

  @Override
  public String toString() {
    return ruleLabelCanonicalName;
  }

  @Override
  public Sequence<String> tokenize(String optionString) throws EvalException {
    checkMutable("tokenize");
    List<String> options = new ArrayList<>();
    try {
      ShellUtils.tokenize(options, optionString);
    } catch (TokenizationException e) {
      throw Starlark.errorf("%s while tokenizing '%s'", e.getMessage(), optionString);
    }
    return StarlarkList.immutableCopyOf(options);
  }

  boolean isForAspect() {
    return isForAspect;
  }

  @Override
  public boolean checkPlaceholders(String template, Sequence<?> allowedPlaceholders) // <String>
      throws EvalException {
    checkMutable("check_placeholders");
    List<String> actualPlaceHolders = new LinkedList<>();
    Set<String> allowedPlaceholderSet =
        ImmutableSet.copyOf(
            Sequence.cast(allowedPlaceholders, String.class, "allowed_placeholders"));
    ImplicitOutputsFunction.createPlaceholderSubstitutionFormatString(template, actualPlaceHolders);
    for (String placeholder : actualPlaceHolders) {
      if (!allowedPlaceholderSet.contains(placeholder)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String expandMakeVariables(
      String attributeName, String command, Dict<?, ?> additionalSubstitutions) // <String, String>
      throws EvalException {
    checkMutable("expand_make_variables");
    final Map<String, String> additionalSubstitutionsMap =
        Dict.cast(additionalSubstitutions, String.class, String.class, "additional_substitutions");
    return expandMakeVariables(attributeName, command, additionalSubstitutionsMap);
  }

  private String expandMakeVariables(
      String attributeName, String command, Map<String, String> additionalSubstitutionsMap) {
    ConfigurationMakeVariableContext makeVariableContext =
        new ConfigurationMakeVariableContext(
            ruleContext.getRule().getPackage(),
            ruleContext.getConfiguration(),
            ruleContext.getDefaultTemplateVariableProviders()) {
          @Override
          public String lookupVariable(String variableName) throws ExpansionException {
            if (additionalSubstitutionsMap.containsKey(variableName)) {
              return additionalSubstitutionsMap.get(variableName);
            } else {
              return super.lookupVariable(variableName);
            }
          }
        };
    return ruleContext.getExpander(makeVariableContext).expand(attributeName, command);
  }

  /** Returns the {@link FilesToRunProvider} corresponding to the supplied {@code executable} */
  @Override
  public FilesToRunProvider getExecutableRunfiles(Artifact executable, String what) {
    return attributesCollection.getExecutableRunfilesMap().get(executable);
  }

  /**
   * Returns true iff the supplied {@link FilesToRunProvider} is from an executable attribute of
   * this rule.
   */
  @Override
  public boolean areRunfilesFromDeps(FilesToRunProvider executable) {
    return attributesCollection.getExecutableRunfilesMap().containsValue(executable);
  }

  @Override
  public Artifact getStableWorkspaceStatus() throws InterruptedException, EvalException {
    checkMutable("info_file");
    return ruleContext.getAnalysisEnvironment().getStableWorkspaceStatusArtifact();
  }

  @Override
  public Artifact getVolatileWorkspaceStatus() throws InterruptedException, EvalException {
    checkMutable("version_file");
    return ruleContext.getAnalysisEnvironment().getVolatileWorkspaceStatusArtifact();
  }

  @Override
  public String getBuildFileRelativePath() throws EvalException {
    checkMutable("build_file_path");
    checkDeprecated("ctx.label.package + '/BUILD'", "ctx.build_file_path", getStarlarkSemantics());

    Package pkg = ruleContext.getRule().getPackage();
    return pkg.getSourceRoot().get().relativize(pkg.getBuildFile().getPath()).getPathString();
  }

  private static void checkDeprecated(String newApi, String oldApi, StarlarkSemantics semantics)
      throws EvalException {
    if (semantics.getBool(BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_BUILD_FILE_PATH)) {
      throw Starlark.errorf(
          "Use %s instead of %s.\n"
              + "Use --incompatible_stop_exporting_build_file_path=false to temporarily disable"
              + " this check.",
          newApi, oldApi);
    }
  }

  @Override
  public String expandLocation(
      String input, Sequence<?> targets, boolean shortPaths, StarlarkThread thread)
      throws EvalException {
    checkMutable("expand_location");
    try {
      ImmutableMap<Label, ImmutableCollection<Artifact>> labelMap =
          makeLabelMap(
              Sequence.cast(targets, TransitiveInfoCollection.class, "targets"),
              thread
                  .getSemantics()
                  .getBool(BuildLanguageOptions.INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE));
      LocationExpander expander;
      if (!shortPaths) {
        expander = LocationExpander.withExecPaths(ruleContext, labelMap);
      } else {
        checkPrivateAccess(thread);
        expander = LocationExpander.withRunfilesPaths(ruleContext, labelMap);
      }
      return expander.expand(input);
    } catch (IllegalStateException ise) {
      throw new EvalException(ise);
    }
  }

  private static void checkPrivateAccess(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
  }

  @Override
  public Runfiles runfiles(
      Sequence<?> files,
      Object transitiveFiles,
      Boolean collectData,
      Boolean collectDefault,
      Object symlinks,
      Object rootSymlinks,
      boolean skipConflictChecking,
      StarlarkThread thread)
      throws EvalException, TypeException {
    if (skipConflictChecking) {
      checkPrivateAccess(thread);
    }
    checkMutable("runfiles");
    Runfiles.Builder builder =
        new Runfiles.Builder(
            ruleContext.getWorkspaceName(), getConfiguration().legacyExternalRunfiles());
    boolean checkConflicts = false;
    if (Starlark.truth(collectData)) {
      builder.addRunfiles(ruleContext, RunfilesProvider.DATA_RUNFILES);
    }
    if (Starlark.truth(collectDefault)) {
      builder.addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES);
    }
    if (!files.isEmpty()) {
      Sequence<Artifact> artifacts = Sequence.cast(files, Artifact.class, "files");
      try {
        builder.addArtifacts(artifacts);
      } catch (IllegalArgumentException e) {
        throw Starlark.errorf("could not add all 'files': %s", e.getMessage());
      }
    }
    if (transitiveFiles != Starlark.NONE) {
      NestedSet<Artifact> transitiveArtifacts =
          Depset.cast(transitiveFiles, Artifact.class, "transitive_files");

      // Runfiles uses compile order. Check that the given transitive_files depset is compatible.
      if (!Order.COMPILE_ORDER.isCompatible(transitiveArtifacts.getOrder())) {
        throw Starlark.errorf(
            "order '%s' is invalid for transitive_files",
            transitiveArtifacts.getOrder().getStarlarkName());
      }
      builder.addTransitiveArtifacts(transitiveArtifacts);
    }
    if (isNonEmptyDepset(symlinks)) {
      // If Starlark code directly manipulates symlinks, activate more stringent validity checking.
      checkConflicts = true;
      builder.addSymlinks(((Depset) symlinks).getSet(SymlinkEntry.class));
    } else if (isNonEmptyDict(symlinks)) {
      checkConflicts = true;
      for (Map.Entry<String, Artifact> entry :
          Dict.cast(symlinks, String.class, Artifact.class, "symlinks").entrySet()) {
        builder.addSymlink(PathFragment.create(entry.getKey()), entry.getValue());
      }
    }
    if (isNonEmptyDepset(rootSymlinks)) {
      checkConflicts = true;
      builder.addRootSymlinks(((Depset) rootSymlinks).getSet(SymlinkEntry.class));
    } else if (isNonEmptyDict(rootSymlinks)) {
      checkConflicts = true;
      for (Map.Entry<String, Artifact> entry :
          Dict.cast(rootSymlinks, String.class, Artifact.class, "root_symlinks").entrySet()) {
        builder.addRootSymlink(PathFragment.create(entry.getKey()), entry.getValue());
      }
    }
    Runfiles runfiles = builder.build();
    if (checkConflicts && !skipConflictChecking) {
      runfiles.setConflictPolicy(Runfiles.ConflictPolicy.ERROR);
    }
    return runfiles;
  }

  private static boolean isNonEmptyDict(Object o) {
    return o instanceof Dict && !((Dict<?, ?>) o).isEmpty();
  }

  private static boolean isNonEmptyDepset(Object o) {
    return o instanceof Depset && !((Depset) o).isEmpty();
  }

  @Override
  public Tuple resolveCommand(
      String command,
      Object attributeUnchecked,
      Boolean expandLocations,
      Object makeVariablesUnchecked,
      Sequence<?> tools,
      Dict<?, ?> labelDictUnchecked,
      Dict<?, ?> executionRequirementsUnchecked,
      StarlarkThread thread)
      throws EvalException {
    checkMutable("resolve_command");
    Map<Label, Iterable<Artifact>> labelDict = checkLabelDict(labelDictUnchecked);
    // The best way to fix this probably is to convert CommandHelper to Starlark.
    CommandHelper helper =
        CommandHelper.builder(ruleContext)
            .addToolDependencies(Sequence.cast(tools, TransitiveInfoCollection.class, "tools"))
            .addLabelMap(labelDict)
            .build();
    String attribute = Type.STRING.convertOptional(attributeUnchecked, "attribute");
    if (expandLocations) {
      command =
          helper.resolveCommandAndExpandLabels(command, attribute, /*allowDataInLabel=*/ false);
    }
    if (!Starlark.isNullOrNone(makeVariablesUnchecked)) {
      Map<String, String> makeVariables =
          Types.STRING_DICT.convert(makeVariablesUnchecked, "make_variables");
      command = expandMakeVariables(attribute, command, makeVariables);
    }
    // TODO(lberki): This flattens a NestedSet.
    // However, we can't turn this into a Depset because it's an incompatible change to Starlark.
    List<Artifact> inputs = new ArrayList<>(helper.getResolvedTools().toList());

    ImmutableMap<String, String> executionRequirements =
        ImmutableMap.copyOf(
            Dict.noneableCast(
                executionRequirementsUnchecked,
                String.class,
                String.class,
                "execution_requirements"));
    // TODO(b/234923262): Take exec_group into consideration instead of using the default
    // exec_group.
    PathFragment shExecutable =
        ShToolchain.getPathForPlatform(
            ruleContext.getConfiguration(), ruleContext.getExecutionPlatform());

    BashCommandConstructor constructor =
        CommandHelper.buildBashCommandConstructor(
            executionRequirements,
            shExecutable,
            String.format(".resolve_command_%d.script.sh", resolveCommandScriptCounter++));
    List<String> argv = helper.buildCommandLine(command, inputs, constructor);
    return Tuple.triple(
        StarlarkList.copyOf(thread.mutability(), inputs),
        StarlarkList.copyOf(thread.mutability(), argv),
        StarlarkList.empty());
  }

  @Override
  public Tuple resolveTools(Sequence<?> tools) throws EvalException {
    checkMutable("resolve_tools");
    checkResolveToolsAllowed();
    CommandHelper helper =
        CommandHelper.builder(ruleContext)
            .addToolDependencies(Sequence.cast(tools, TransitiveInfoCollection.class, "tools"))
            .build();
    return Tuple.pair(Depset.of(Artifact.class, helper.getResolvedTools()), StarlarkList.empty());
  }

  private void checkResolveToolsAllowed() throws EvalException {
    if (getStarlarkSemantics()
        .getBool(BuildLanguageOptions.INCOMPATIBLE_DISALLOW_CTX_RESOLVE_TOOLS)) {
      throw Starlark.errorf(
          "Pass an executable or tools argument to ctx.actions.run or ctx.actions.run_shell"
              + " instead of calling ctx.resolve_tools.\n"
              + "Use --noincompatible_disallow_ctx_resolve_tools to temporarily disable this"
              + " check.");
    }
  }

  @Override
  public StarlarkSemantics getStarlarkSemantics() {
    return ruleContext.getAnalysisEnvironment().getStarlarkSemantics();
  }

  /**
   * Ensures the given {@link Map} has keys that have {@link Label} type and values that have either
   * {@link Iterable} or {@link Depset} type, and raises {@link EvalException} otherwise. Returns a
   * corresponding map where any sets are replaced by iterables.
   */
  // TODO(bazel-team): find a better way to typecheck this argument.
  private static Map<Label, Iterable<Artifact>> checkLabelDict(Map<?, ?> labelDict)
      throws EvalException {
    Map<Label, Iterable<Artifact>> convertedMap = new HashMap<>();
    for (Map.Entry<?, ?> entry : labelDict.entrySet()) {
      Object key = entry.getKey();
      if (!(key instanceof Label)) {
        throw Starlark.errorf("invalid key %s in 'label_dict'", Starlark.repr(key));
      }
      ImmutableList.Builder<Artifact> files = ImmutableList.builder();
      Object val = entry.getValue();
      Iterable<?> valIter;
      if (val instanceof Iterable) {
        valIter = (Iterable<?>) val;
      } else {
        throw Starlark.errorf(
            "invalid value %s in 'label_dict': expected iterable, but got '%s'",
            Starlark.repr(val), Starlark.type(val));
      }
      for (Object file : valIter) {
        if (!(file instanceof Artifact)) {
          throw Starlark.errorf("invalid value %s in 'label_dict'", Starlark.repr(val));
        }
        files.add((Artifact) file);
      }
      convertedMap.put((Label) key, files.build());
    }
    return convertedMap;
  }

  /**
   * Builds a map: Label -> List of files from the given labels
   *
   * @param knownLabels List of known labels
   * @param locationsPrefersExecutable whether to prefer an executable over a list of files that
   *     isn't a singleton when requesting a plural location function
   * @return Immutable map with immutable collections as values
   */
  public static ImmutableMap<Label, ImmutableCollection<Artifact>> makeLabelMap(
      Iterable<TransitiveInfoCollection> knownLabels, boolean locationsPrefersExecutable)
      throws EvalException {
    var targetsMap = new LinkedHashMap<Label, ImmutableCollection<Artifact>>();
    for (TransitiveInfoCollection current : knownLabels) {
      Label label = AliasProvider.getDependencyLabel(current);
      if (targetsMap.containsKey(label)) {
        throw Starlark.errorf(
            "Label %s is found more than once in 'targets' list.", Starlark.repr(label.toString()));
      }

      var filesToRunProvider = current.getProvider(FilesToRunProvider.class);
      ImmutableCollection<Artifact> expansion;
      // Only use the executable if requesting the files to build via a singleton location function
      // would fail to ensure backwards compatibility (this function used to always return the
      // files to build). The case of a plural location function is potentially breaking, but gated
      // behind locationsPrefersExecutable.
      // Avoid flattening a nested set if the first branch is taken.
      if (filesToRunProvider != null
          && filesToRunProvider.getExecutable() != null
          && locationsPrefersExecutable
          && !current.getProvider(FileProvider.class).getFilesToBuild().isSingleton()) {
        expansion = ImmutableList.of(filesToRunProvider.getExecutable());
      } else {
        expansion = current.getProvider(FileProvider.class).getFilesToBuild().toList();
      }
      targetsMap.put(label, expansion);
    }

    return ImmutableMap.copyOf(targetsMap);
  }
}
