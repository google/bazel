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

package com.google.devtools.build.lib.packages;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/**
 * AspectsList represents the list of aspects specified via --aspects command line option or
 * declared in attribute aspects list. The class is responsible for wrapping the information
 * necessary for constructing those aspects.
 */
public final class AspectsList {
  private final ImmutableList<AspectDetails<?>> aspects;

  private AspectsList(ImmutableList<AspectDetails<?>> aspects) {
    this.aspects = aspects;
  }

  public boolean hasAspects() {
    return !aspects.isEmpty();
  }

  /** Returns the list of aspects required for dependencies through this attribute. */
  public ImmutableList<Aspect> getAspects(Rule rule) {
    if (aspects.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<Aspect> builder = null;
    for (AspectDetails<?> aspect : aspects) {
      Aspect a = aspect.getAspect(rule);
      if (a != null) {
        if (builder == null) {
          builder = ImmutableList.builder();
        }
        builder.add(a);
      }
    }
    return builder == null ? ImmutableList.of() : builder.build();
  }

  public ImmutableList<AspectClass> getAspectClasses() {
    ImmutableList.Builder<AspectClass> result = ImmutableList.builder();
    for (AspectDetails<?> aspect : aspects) {
      result.add(aspect.getAspectClass());
    }
    return result.build();
  }

  /** Returns a list of Aspect objects for top level aspects. */
  public ImmutableList<Aspect> buildAspects(ImmutableMap<String, String> aspectsParameters)
      throws EvalException {
    Preconditions.checkArgument(aspectsParameters != null, "aspectsParameters cannot be null");

    ImmutableList.Builder<Aspect> aspectsList = ImmutableList.builder();
    for (AspectDetails<?> aspect : aspects) {
      aspectsList.add(aspect.getTopLevelAspect(aspectsParameters));
    }
    return aspectsList.build();
  }

  public void validateRulePropagatedAspectsParameters(RuleClass ruleClass) throws EvalException {
    for (AspectDetails<?> aspect : aspects) {
      ImmutableSet<String> requiredAspectParameters = aspect.getRequiredParameters();
      for (Attribute aspectAttribute : aspect.getAspectAttributes()) {
        String aspectAttrName = aspectAttribute.getPublicName();
        Type<?> aspectAttrType = aspectAttribute.getType();

        // When propagated from a rule, explicit aspect attributes must be of type boolean, int
        // or string. Integer and string attributes must have the `values` restriction.
        if (!aspectAttribute.isImplicit() && !aspectAttribute.isLateBound()) {
          if (aspectAttrType != Type.BOOLEAN && !aspectAttribute.checkAllowedValues()) {
            throw Starlark.errorf(
                "Aspect %s: Aspect parameter attribute '%s' must use the 'values' restriction.",
                aspect.getName(), aspectAttrName);
          }
        }

        // Required aspect parameters must be specified by the rule propagating the aspect with
        // the same parameter type.
        if (requiredAspectParameters.contains(aspectAttrName)) {
          if (!ruleClass.hasAttr(aspectAttrName, aspectAttrType)) {
            throw Starlark.errorf(
                "Aspect %s requires rule %s to specify attribute '%s' with type %s.",
                aspect.getName(), ruleClass.getName(), aspectAttrName, aspectAttrType);
          }
        }
      }
    }
  }

  /**
   * Validates top-level aspects parameters and reports error in the following cases:
   *
   * <p>If a parameter name is specified in command line but no aspect has a parameter with that
   * name.
   *
   * <p>If a mandatory aspect attribute is not given a value in the top-level parameters list.
   */
  public void validateTopLevelAspectsParameters(ImmutableMap<String, String> aspectsParameters)
      throws EvalException {
    Preconditions.checkArgument(aspectsParameters != null, "aspectsParameters cannot be null");

    ImmutableSet.Builder<String> usedParametersBuilder = ImmutableSet.builder();
    for (AspectDetails<?> aspectDetails : aspects) {
      if (aspectDetails instanceof StarlarkAspectDetails) {
        ImmutableList<Attribute> aspectAttributes =
            ((StarlarkAspectDetails) aspectDetails).aspect.getAttributes();
        for (Attribute attr : aspectAttributes) {
          if (attr.isImplicit() || attr.isLateBound()) {
            continue;
          }
          String attrName = attr.getName();
          if (aspectsParameters.containsKey(attrName)) {
            usedParametersBuilder.add(attrName);
          } else if (attr.isMandatory()) {
            throw Starlark.errorf(
                "Missing mandatory attribute '%s' for aspect '%s'.",
                attrName, aspectDetails.getName());
          }
        }
      }
    }
    ImmutableSet<String> usedParameters = usedParametersBuilder.build();
    ImmutableList<String> unusedParameters =
        aspectsParameters.keySet().stream()
            .filter(p -> !usedParameters.contains(p))
            .collect(toImmutableList());
    if (!unusedParameters.isEmpty()) {
      throw Starlark.errorf(
          "Parameters '%s' are not parameters of any of the top-level aspects but they are"
              + " specified in --aspects_parameters.",
          unusedParameters);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AspectsList aspectsList = (AspectsList) o;
    return Objects.equals(aspects, aspectsList.aspects);
  }

  @Override
  public int hashCode() {
    return aspects.hashCode();
  }

  /** Wraps the information necessary to construct an Aspect. */
  private abstract static class AspectDetails<C extends AspectClass> {
    final C aspectClass;
    final Function<Rule, AspectParameters> parametersExtractor;
    final String requiredByAspect;

    private AspectDetails(C aspectClass, Function<Rule, AspectParameters> parametersExtractor) {
      this.aspectClass = aspectClass;
      this.parametersExtractor = parametersExtractor;
      this.requiredByAspect = null;
    }

    private AspectDetails(
        C aspectClass,
        Function<Rule, AspectParameters> parametersExtractor,
        String requiredByAspect) {
      this.aspectClass = aspectClass;
      this.parametersExtractor = parametersExtractor;
      this.requiredByAspect = requiredByAspect;
    }

    public String getName() {
      return this.aspectClass.getName();
    }

    public ImmutableSet<String> getRequiredParameters() {
      return ImmutableSet.of();
    }

    public ImmutableList<Attribute> getAspectAttributes() {
      return ImmutableList.of();
    }

    protected abstract Aspect getAspect(Rule rule);

    protected abstract Aspect getTopLevelAspect(ImmutableMap<String, String> aspectParameters)
        throws EvalException;

    C getAspectClass() {
      return aspectClass;
    }
  }

  private static class NativeAspectDetails extends AspectDetails<NativeAspectClass> {
    NativeAspectDetails(
        NativeAspectClass aspectClass, Function<Rule, AspectParameters> parametersExtractor) {
      super(aspectClass, parametersExtractor);
    }

    NativeAspectDetails(
        NativeAspectClass aspectClass,
        Function<Rule, AspectParameters> parametersExtractor,
        String requiredByAspect) {
      super(aspectClass, parametersExtractor, requiredByAspect);
    }

    @Nullable
    @Override
    public Aspect getAspect(Rule rule) {
      AspectParameters params = parametersExtractor.apply(rule);
      return params == null ? null : Aspect.forNative(aspectClass, params);
    }

    @Override
    protected Aspect getTopLevelAspect(ImmutableMap<String, String> aspectParameters)
        throws EvalException {
      // Native aspects ignore their top-level parameters values for now.
      return Aspect.forNative(aspectClass, AspectParameters.EMPTY);
    }
  }

  private static class StarlarkAspectDetails extends AspectDetails<StarlarkAspectClass> {
    private final StarlarkDefinedAspect aspect;

    private StarlarkAspectDetails(StarlarkDefinedAspect aspect, String requiredByAspect) {
      super(aspect.getAspectClass(), aspect.getDefaultParametersExtractor(), requiredByAspect);
      this.aspect = aspect;
    }

    @Override
    public ImmutableSet<String> getRequiredParameters() {
      return aspect.getParamAttributes();
    }

    @Override
    public ImmutableList<Attribute> getAspectAttributes() {
      return aspect.getAttributes();
    }

    @Override
    public Aspect getAspect(Rule rule) {
      AspectParameters params = parametersExtractor.apply(rule);
      return Aspect.forStarlark(aspectClass, aspect.getDefinition(params), params);
    }

    @Override
    public Aspect getTopLevelAspect(ImmutableMap<String, String> aspectParameters)
        throws EvalException {
      AspectParameters params = aspect.extractTopLevelParameters(aspectParameters);
      return Aspect.forStarlark(aspectClass, aspect.getDefinition(params), params);
    }
  }

  /** Aspect details that just wrap a pre-existing Aspect that doesn't vary with the Rule. */
  private static class PredefinedAspectDetails extends AspectDetails<AspectClass> {
    private final Aspect aspect;

    PredefinedAspectDetails(Aspect aspect) {
      super(aspect.getAspectClass(), null);
      this.aspect = aspect;
    }

    @Override
    public Aspect getAspect(Rule rule) {
      return aspect;
    }

    @Override
    public Aspect getTopLevelAspect(ImmutableMap<String, String> aspectParameters)
        throws EvalException {
      return aspect;
    }
  }

  @SerializationConstant @VisibleForSerialization
  static final Function<Rule, AspectParameters> EMPTY_FUNCTION = input -> AspectParameters.EMPTY;

  /** A builder for AspectsList */
  public static class Builder {
    private final HashMap<String, AspectDetails<?>> aspects = new LinkedHashMap<>();

    public Builder() {}

    public Builder(AspectsList aspectsList) {
      for (AspectDetails<?> aspect : aspectsList.aspects) {
        aspects.put(aspect.getName(), aspect);
      }
    }

    public AspectsList build() {
      return new AspectsList(ImmutableList.copyOf(aspects.values()));
    }

    /**
     * Adds a native aspect with its parameters extraction function to the aspects list.
     *
     * @param aspect the native aspect to be added
     * @param evaluator function that extracts aspect parameters from rule.
     */
    public void addAspect(NativeAspectClass aspect, Function<Rule, AspectParameters> evaluator) {
      NativeAspectDetails nativeAspectDetails = new NativeAspectDetails(aspect, evaluator);
      AspectDetails<?> oldAspect =
          this.aspects.put(nativeAspectDetails.getName(), nativeAspectDetails);
      if (oldAspect != null) {
        throw new AssertionError(
            String.format("Aspect %s has already been added", oldAspect.getName()));
      }
    }

    /**
     * Adds a native aspect that does not need a parameters extractor to the aspects list.
     *
     * @param aspect the native aspect to be added
     */
    public void addAspect(NativeAspectClass aspect) {
      addAspect(aspect, EMPTY_FUNCTION);
    }

    /** Attaches this aspect and its required aspects */
    public void addAspect(StarlarkAspect starlarkAspect) throws EvalException {
      addAspect(starlarkAspect, null);
    }

    private void addAspect(StarlarkAspect starlarkAspect, @Nullable String requiredByAspect)
        throws EvalException {
      if (starlarkAspect instanceof StarlarkDefinedAspect starlarkDefinedAspect) {
        if (!starlarkDefinedAspect.isExported()) {
          throw Starlark.errorf(
              "Aspects should be top-level values in extension files that define them.");
        }

        for (StarlarkAspect requiredAspect : starlarkDefinedAspect.getRequiredAspects()) {
          addAspect(requiredAspect, starlarkDefinedAspect.getName());
        }
      }

      boolean needsToAdd = needsToBeAdded(starlarkAspect.getName(), requiredByAspect);
      if (needsToAdd) {
        final AspectDetails<?> aspectDetails;

        if (starlarkAspect instanceof StarlarkDefinedAspect) {
          aspectDetails =
              new StarlarkAspectDetails((StarlarkDefinedAspect) starlarkAspect, requiredByAspect);
        } else if (starlarkAspect instanceof StarlarkNativeAspect) {
          aspectDetails =
              new NativeAspectDetails(
                  (StarlarkNativeAspect) starlarkAspect,
                  starlarkAspect.getDefaultParametersExtractor(),
                  requiredByAspect);
        } else {
          throw new IllegalArgumentException();
        }
        this.aspects.put(starlarkAspect.getName(), aspectDetails);
      }
    }

    /** Should only be used for deserialization. */
    public void addAspect(final Aspect aspect) {
      PredefinedAspectDetails predefinedAspectDetails = new PredefinedAspectDetails(aspect);
      AspectDetails<?> oldAspect =
          this.aspects.put(predefinedAspectDetails.getName(), predefinedAspectDetails);
      if (oldAspect != null) {
        throw new AssertionError(
            String.format("Aspect %s has already been added", oldAspect.getName()));
      }
    }

    /**
     * Adds all aspect from the list.
     *
     * <p>The function is intended for extended Starlark rules, where aspect list is already built
     * and may include aspects required by other aspects.
     */
    public void addAspects(AspectsList aspectsList) throws EvalException {
      for (AspectDetails<?> aspect : aspectsList.aspects) {
        boolean needsToAdd = needsToBeAdded(aspect.getName(), aspect.requiredByAspect);
        if (needsToAdd) {
          aspects.put(aspect.getName(), aspect);
        }
      }
    }

    private boolean needsToBeAdded(String aspectName, @Nullable String requiredByAspect)
        throws EvalException {

      AspectDetails<?> oldAspect = this.aspects.get(aspectName);

      if (oldAspect != null) {
        if (requiredByAspect != null) {
          // If the aspect to be added already exists and it is required by another aspect, no need
          // to
          // add it again.
          return false;
        } else {
          // If the aspect to be added is not required by another aspect, then we should throw error
          String oldAspectBaseAspectName = oldAspect.requiredByAspect;
          if (oldAspectBaseAspectName != null) {
            throw Starlark.errorf(
                "aspect %s was added before as a required aspect of aspect %s",
                oldAspect.getName(), oldAspectBaseAspectName);
          }
          throw Starlark.errorf("aspect %s added more than once", oldAspect.getName());
        }
      }

      return true; // we need to add the new aspect
    }
  }
}
