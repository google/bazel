// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.AspectContext;
import com.google.devtools.build.lib.analysis.DormantDependency;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.PrerequisiteArtifacts;
import com.google.devtools.build.lib.analysis.PrerequisitesCollection;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkAttributesCollectionApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ExecGroupCollectionApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainContextApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.syntax.Identifier;

/** Information about attributes of a rule an aspect is applied to. */
public class StarlarkAttributesCollection implements StarlarkAttributesCollectionApi {
  private final StarlarkRuleContext starlarkRuleContext;
  private final StructImpl attrObject;
  private final StructImpl executableObject;
  private final StructImpl fileObject;
  private final StructImpl filesObject;
  private final ImmutableMap<Artifact, FilesToRunProvider> executableRunfilesMap;
  private final String ruleClassName;
  private final Dict<String, String> ruleVariables;

  static final String ERROR_MESSAGE_FOR_NO_ATTR =
      "No attribute '%s' in attr. Make sure you declared a rule attribute with this name.";

  private StarlarkAttributesCollection(
      StarlarkRuleContext starlarkRuleContext,
      String ruleClassName,
      Map<String, Object> attrs,
      Map<String, Object> executables,
      Map<String, Object> singleFiles,
      Map<String, Object> files,
      ImmutableMap<Artifact, FilesToRunProvider> executableRunfilesMap,
      Dict<String, String> ruleVariables) {
    this.starlarkRuleContext = starlarkRuleContext;
    this.ruleClassName = ruleClassName;
    attrObject = StructProvider.STRUCT.create(attrs, ERROR_MESSAGE_FOR_NO_ATTR);
    executableObject =
        StructProvider.STRUCT.create(
            executables,
            "No attribute '%s' in executable. Make sure there is a label type attribute marked "
                + "as 'executable' with this name");
    fileObject =
        StructProvider.STRUCT.create(
            singleFiles,
            "No attribute '%s' in file. Make sure there is a label type attribute marked "
                + "as 'allow_single_file' with this name");
    filesObject =
        StructProvider.STRUCT.create(
            files,
            "No attribute '%s' in files. Make sure there is a label or label_list type attribute "
                + "with this name");
    this.executableRunfilesMap = executableRunfilesMap;
    this.ruleVariables = ruleVariables;
  }

  private void checkMutable(String attrName) throws EvalException {
    starlarkRuleContext.checkMutable("rule." + attrName);
  }

  @Override
  public StructImpl getAttr() throws EvalException {
    checkMutable("attr");
    return attrObject;
  }

  @Override
  public StructImpl getExecutable() throws EvalException {
    checkMutable("executable");
    return executableObject;
  }

  @Override
  public StructImpl getFile() throws EvalException {
    checkMutable("file");
    return fileObject;
  }

  @Override
  public StructImpl getFiles() throws EvalException {
    checkMutable("files");
    return filesObject;
  }

  @Override
  public String getRuleClassName() throws EvalException {
    checkMutable("kind");
    return ruleClassName;
  }

  @Override
  public ToolchainContextApi toolchains() throws EvalException {
    checkMutable("toolchains");
    if (((AspectContext) starlarkRuleContext.getRuleContext()).getBaseTargetToolchainContexts()
        == null) {
      return StarlarkToolchainContext.TOOLCHAINS_NOT_VALID;
    }
    var aspectContext = ((AspectContext) starlarkRuleContext.getRuleContext());

    return StarlarkToolchainContext.create(
        aspectContext
            .getBaseTargetToolchainContexts()
            .getDefaultToolchainContext()
            .targetDescription(),
        /* resolveToolchainDataFunc= */ aspectContext::getToolchainTarget,
        /* resolvedToolchainTypeLabels= */ aspectContext.getRequestedToolchainTypesLabels());
  }

  @Override
  public ExecGroupCollectionApi execGroups() throws EvalException {
    checkMutable("exec_groups");
    if (((AspectContext) starlarkRuleContext.getRuleContext()).getBaseTargetToolchainContexts()
        == null) {
      return StarlarkExecGroupCollection.EXEC_GROUP_COLLECTION_NOT_VALID;
    }
    // Create a thin wrapper around the toolchain collection, to expose the Starlark API.
    return StarlarkExecGroupCollection.create(
        ((AspectContext) starlarkRuleContext.getRuleContext()).getBaseTargetToolchainContexts());
  }

  public ImmutableMap<Artifact, FilesToRunProvider> getExecutableRunfilesMap() {
    return executableRunfilesMap;
  }

  @Override
  public boolean isImmutable() {
    return starlarkRuleContext.isImmutable();
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<rule collection for " + starlarkRuleContext.getRuleLabelCanonicalName() + ">");
  }

  public static Builder builder(
      StarlarkRuleContext ruleContext, PrerequisitesCollection prerequisitesCollection) {
    return new Builder(ruleContext, prerequisitesCollection);
  }

  /** A builder for {@link StarlarkAttributesCollection}. */
  public static class Builder {
    private final StarlarkRuleContext context;
    private final PrerequisitesCollection prerequisites;

    private final LinkedHashMap<String, Object> attrBuilder = new LinkedHashMap<>();
    private final LinkedHashMap<String, Object> executableBuilder = new LinkedHashMap<>();
    private final ImmutableMap.Builder<Artifact, FilesToRunProvider> executableRunfilesbuilder =
        ImmutableMap.builder();
    private final LinkedHashMap<String, Object> fileBuilder = new LinkedHashMap<>();
    private final LinkedHashMap<String, Object> filesBuilder = new LinkedHashMap<>();
    private final HashSet<Artifact> seenExecutables = new HashSet<>();
    private final Dict.Builder<String, String> ruleVariablesBuilder = new Dict.Builder<>();

    private Builder(
        StarlarkRuleContext ruleContext, PrerequisitesCollection prerequisitesCollection) {
      this.context = ruleContext;
      this.prerequisites = prerequisitesCollection;
    }

    static Dict<String, TransitiveInfoCollection> convertStringToLabelMap(
        Map<String, Label> unconfiguredValue,
        List<? extends TransitiveInfoCollection> prerequisites) {
      Map<Label, TransitiveInfoCollection> prerequisiteMap = Maps.newHashMap();
      for (TransitiveInfoCollection prereq : prerequisites) {
        prerequisiteMap.put(AliasProvider.getDependencyLabel(prereq), prereq);
      }

      Dict.Builder<String, TransitiveInfoCollection> builder = Dict.builder();
      for (var entry : unconfiguredValue.entrySet()) {
        builder.put(entry.getKey(), prerequisiteMap.get(entry.getValue()));
      }

      return builder.buildImmutable();
    }

    @Nullable
    public static Object convertAttributeValue(
        Supplier<List<? extends TransitiveInfoCollection>> prerequisiteSupplier,
        Attribute a,
        Object val) {
      Type<?> type = a.getType();
      String skyname = a.getPublicName();

      // Some legacy native attribute types do not have a valid Starlark type. Avoid exposing
      // these to Starlark.
      if (type == BuildType.DISTRIBUTIONS || type == BuildType.TRISTATE) {
        return null;
      }

      // Don't expose invalid attributes via the rule ctx.attr. Ordinarily, this case cannot happen,
      // and currently only applies to subrule attributes
      // TODO: b/293304174 - let subrules explicitly mark attributes as not-visible-to-starlark
      if (!Identifier.isValid(skyname)) {
        return null;
      }

      // Don't expose exec_group_compatible_with to Starlark. There is no reason for it to be used
      // by the rule implementation function and its type (LABEL_LIST_DICT) is not available to
      // Starlark.
      if (a.getName().equals(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR)) {
        return null;
      }

      if (type == BuildType.DORMANT_LABEL) {
        return val == null
            ? Starlark.NONE
            : new DormantDependency(BuildType.DORMANT_LABEL.cast(val));
      }

      if (type == BuildType.DORMANT_LABEL_LIST) {
        StarlarkList<DormantDependency> dormantDeps =
            StarlarkList.immutableCopyOf(
                BuildType.DORMANT_LABEL_LIST.cast(val).stream()
                    .map(DormantDependency::new)
                    .collect(toImmutableList()));
        return dormantDeps;
      }

      if (type.getLabelClass() != LabelClass.DEPENDENCY) {
        // Attribute values should be type safe
        return Attribute.valueToStarlark(val);
      }

      if (type == BuildType.LABEL && !a.getTransitionFactory().isSplit()) {
        List<? extends TransitiveInfoCollection> prerequisites = prerequisiteSupplier.get();
        return prerequisites.isEmpty() ? Starlark.NONE : prerequisites.get(0);
      } else if (type == BuildType.LABEL_LIST
          || (type == BuildType.LABEL && a.getTransitionFactory().isSplit())) {
        List<?> allPrereq = prerequisiteSupplier.get();
        return StarlarkList.immutableCopyOf(allPrereq);
      } else if (type == BuildType.LABEL_DICT_UNARY) {
        return convertStringToLabelMap(
            BuildType.LABEL_DICT_UNARY.cast(val), prerequisiteSupplier.get());
      } else if (type == BuildType.LABEL_KEYED_STRING_DICT) {
        Dict.Builder<TransitiveInfoCollection, String> builder = Dict.builder();
        Map<Label, String> original = BuildType.LABEL_KEYED_STRING_DICT.cast(val);
        List<? extends TransitiveInfoCollection> allPrereq = prerequisiteSupplier.get();
        for (TransitiveInfoCollection prereq : allPrereq) {
          builder.put(prereq, original.get(AliasProvider.getDependencyLabel(prereq)));
        }
        return builder.buildImmutable();
      } else {
        throw new IllegalArgumentException(
            "Can't transform attribute "
                + a.getName()
                + " of type "
                + type
                + " to a Starlark object");
      }
    }

    public void addAttribute(Attribute a, Object val) {
      Type<?> type = a.getType();
      String skyname = a.getPublicName();

      // The first attribute with the same name wins.
      if (attrBuilder.containsKey(skyname)) {
        return;
      }

      Object starlarkVal =
          convertAttributeValue(() -> prerequisites.getPrerequisites(a.getName()), a, val);
      if (starlarkVal == null) {
        return;
      }

      attrBuilder.put(skyname, starlarkVal);
      if (type.getLabelClass() != LabelClass.DEPENDENCY) {
        return;
      }

      NestedSet<Artifact> files = PrerequisiteArtifacts.nestedSet(prerequisites, a.getName());
      filesBuilder.put(
          skyname,
          files.isEmpty()
              ? StarlarkList.empty()
              : StarlarkList.lazyImmutable(
                  (StarlarkList.SerializableListSupplier<Artifact>) files::toList));

      if (a.isExecutable()) {
        // In a Starlark-defined rule, only LABEL type attributes (not LABEL_LIST) can have the
        // Executable flag. However, we could be here because we're creating a StarlarkRuleContext
        // for a native rule for builtins injection, in which case we may see an executable
        // LABEL_LIST. In that case omit the attribute as if it weren't executable.
        if (type == BuildType.LABEL) {
          FilesToRunProvider provider = prerequisites.getExecutablePrerequisite(a.getName());
          if (provider != null && provider.getExecutable() != null) {
            Artifact executable = provider.getExecutable();
            executableBuilder.put(skyname, executable);
            if (!seenExecutables.contains(executable)) {
              // todo(dslomov,laurentlb): In general, this is incorrect.
              // We associate the first encountered FilesToRunProvider with
              // the executable (this provider is later used to build the spawn).
              // However ideally we should associate a provider with the attribute name,
              // and pass the correct FilesToRunProvider to the spawn depending on
              // what attribute is used to access the executable.
              executableRunfilesbuilder.put(executable, provider);
              seenExecutables.add(executable);
            }
          } else {
            executableBuilder.put(skyname, Starlark.NONE);
          }
        }
      }
      if (a.isSingleArtifact()) {
        // In Starlark only label (not label list) type attributes can have the SingleArtifact flag.
        Artifact artifact = prerequisites.getPrerequisiteArtifact(a.getName());
        if (artifact != null) {
          fileBuilder.put(skyname, artifact);
        } else {
          fileBuilder.put(skyname, Starlark.NONE);
        }
      }
    }

    @CanIgnoreReturnValue
    public Builder putAllRuleVariables(Dict<String, String> var) {
      this.ruleVariablesBuilder.putAll(var);
      return this;
    }

    public StarlarkAttributesCollection build() {
      return new StarlarkAttributesCollection(
          context,
          context.getRuleContext().getRule().getRuleClass(),
          attrBuilder,
          executableBuilder,
          fileBuilder,
          filesBuilder,
          executableRunfilesbuilder.buildOrThrow(),
          ruleVariablesBuilder.buildImmutable());
    }
  }

  @Override
  public Dict<String, String> var() throws EvalException, InterruptedException {
    return this.ruleVariables;
  }
}
