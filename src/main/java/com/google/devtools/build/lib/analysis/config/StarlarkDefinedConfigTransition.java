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

package com.google.devtools.build.lib.analysis.config;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition.PATCH_TRANSITION_KEY;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.CoreOptions.ExecConfigurationDistinguisherScheme;
import com.google.devtools.build.lib.analysis.config.CoreOptions.IncludeConfigFragmentsEnum;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputDirectoryNamingScheme;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.Label.PackageContext;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigurationTransitionApi;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.TriState;
import com.google.errorprone.annotations.FormatMethod;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/**
 * Implementation of {@link ConfigurationTransitionApi}.
 *
 * <p>Represents a configuration transition across a dependency edge defined in Starlark.
 */
public abstract sealed class StarlarkDefinedConfigTransition implements ConfigurationTransitionApi {

  public static final String COMMAND_LINE_OPTION_PREFIX = "//command_line_option:";

  /**
   * The two groups of build settings that are relevant for a {@link
   * StarlarkDefinedConfigTransition}
   */
  public enum Settings {
    /** Build settings that are read by a {@link StarlarkDefinedConfigTransition} */
    INPUTS,
    /** Build settings that are written by a {@link StarlarkDefinedConfigTransition} */
    OUTPUTS,
    /** Build settings that are read and/or written by a {@link StarlarkDefinedConfigTransition } */
    INPUTS_AND_OUTPUTS
  }

  private final ImmutableMap<String, String> inputsCanonicalizedToGiven;
  private final ImmutableMap<String, String> outputsCanonicalizedToGiven;
  final Label parentLabel;
  private final Location location;
  private final Label.PackageContext packageContext;

  // The values in this cache should always be instances of StarlarkTransition, but referencing that
  // here results in a circular dependency.
  private final transient Cache<RuleTransitionData, PatchTransition> ruleTransitionCache =
      Caffeine.newBuilder().weakKeys().build();

  private StarlarkDefinedConfigTransition(
      List<String> inputs,
      List<String> outputs,
      RepositoryMapping repoMapping,
      Label parentLabel,
      Location location)
      throws EvalException {
    this.parentLabel = parentLabel;
    this.location = location;
    packageContext = Label.PackageContext.of(parentLabel.getPackageIdentifier(), repoMapping);

    this.outputsCanonicalizedToGiven =
        getCanonicalizedSettings(repoMapping, parentLabel, outputs, Settings.OUTPUTS);
    this.inputsCanonicalizedToGiven =
        getCanonicalizedSettings(repoMapping, parentLabel, inputs, Settings.INPUTS);
  }

  public final PackageContext getPackageContext() {
    return packageContext;
  }

  /** Is this transition an exec transition? */
  public abstract boolean isExecTransition();

  /**
   * Returns a build settings in canonicalized form taking into account repository remappings.
   * Native options only have one form so they are always returned unchanged (i.e.
   * //command_line_option:<option-name>).
   */
  private static String canonicalizeSetting(
      String setting, RepositoryMapping repoMapping, Label parentLabel)
      throws LabelSyntaxException {
    String canonicalizedString = setting;
    // native options
    if (setting.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
      return canonicalizedString;
    }
    canonicalizedString =
        Label.parseWithPackageContext(
                setting, PackageContext.of(parentLabel.getPackageIdentifier(), repoMapping))
            .getUnambiguousCanonicalForm();
    return canonicalizedString;
  }

  /**
   * Canonicalize the given list of settings. Return a map of their canonicalized version to the
   * form they were given in. Along the way make sure that this list of settings doesn't contain two
   * label strings that look different but canonicalize to the same target.
   *
   * @return a map of the canonicalized version of the build settings to the form the user gave
   *     them. In the case of native options, the key and value of the entry are the same -
   *     "//command_line_option:<option-name>"
   */
  private static ImmutableMap<String, String> getCanonicalizedSettings(
      RepositoryMapping repoMapping,
      Label parentLabel,
      List<String> settings,
      Settings inputsOrOutputs)
      throws EvalException {
    Map<String, String> canonicalizedToGiven = new HashMap<>();
    for (String setting : settings) {
      String canonicalizedSetting;
      try {
        canonicalizedSetting = canonicalizeSetting(setting, repoMapping, parentLabel);
      } catch (LabelSyntaxException unused) {
        throw Starlark.errorf(
            "Malformed label in transition %s parameter: '%s'", inputsOrOutputs, setting);
      }
      String previousSetting = canonicalizedToGiven.put(canonicalizedSetting, setting);
      if (previousSetting != null) {
        throw Starlark.errorf(
            "Transition declares duplicate build setting '%s' in %s (specified as '%s' and '%s')",
            canonicalizedSetting, inputsOrOutputs, setting, previousSetting);
      }
    }
    return ImmutableSortedMap.copyOf(canonicalizedToGiven);
  }

  /**
   * Returns true if this transition is for analysis testing. If true, then only attributes of rules
   * with {@code analysis_test=true} may use this transition object.
   */
  public abstract boolean isForAnalysisTesting();

  /**
   * Returns the given input option keys for this transition. Only options contained in this list
   * will be provided in the 'settings' argument given to the transition implementation function.
   */
  public final ImmutableList<String> getInputs() {
    return inputsCanonicalizedToGiven.values().asList();
  }

  public final ImmutableMap<String, String> getInputsCanonicalizedToGiven() {
    return inputsCanonicalizedToGiven;
  }

  /**
   * Returns the given output option keys for this transition. The transition implementation
   * function must return a dictionary where the options exactly match the elements of this list.
   */
  public ImmutableList<String> getOutputs() {
    return outputsCanonicalizedToGiven.values().asList();
  }

  public final ImmutableMap<String, String> getOutputsCanonicalizedToGiven() {
    return outputsCanonicalizedToGiven;
  }

  /** Returns the location of the Starlark code defining the transition. */
  public final Location getLocation() {
    return location;
  }

  /**
   * Returns a cache that can be used to ensure that this {@link StarlarkDefinedConfigTransition}
   * results in at most one {@link
   * com.google.devtools.build.lib.analysis.starlark.StarlarkTransition} instance per {@link
   * RuleTransitionData}.
   *
   * <p>The cache uses {@link Caffeine#weakKeys} to permit collection of transition objects when the
   * corresponding {@link Rule} is collectable. As a consequence, it uses identity comparison for
   * keys, but this is fine since {@link Rule} does not override {@link Object#equals}.
   *
   * <p>Profiling shows that constructing transitions and lazily computing their hash code
   * contributes real CPU cost. For a build where every target applies a transition, this produces
   * observable cost, particularly when the transition produces a noop (in which case the cost is
   * pure overhead of the transition infrastructure).
   *
   * <p>Note that the transition instance is different from the transition's use. It's normal best
   * practice to have few or even one transition invoke multiple times over multiple configured
   * targets.
   */
  public PatchTransition createRuleTransition(
      RuleTransitionData ruleData,
      Function<RuleTransitionData, ? extends PatchTransition> createTransition) {
    return this.ruleTransitionCache.get(ruleData, createTransition);
  }

  /**
   * Given a map of a subset of the "previous" build settings, returns the changed build settings as
   * a result of applying this transition.
   *
   * @param previousSettings a map representing the previous build settings
   * @param attributeMapper a map of attributes
   * @param optionInfoMap info about each option's {@link Option} type
   * @param handler handler for messages
   * @return a map of changed build setting maps; each element of the map represents a different
   *     child configuration (split transitions will have multiple elements in this map with keys
   *     provided by the transition impl, patch transitions should have a single element keyed by
   *     {@code PATCH_TRANSITION_KEY}). Each build setting map is a map from build setting to target
   *     setting value; all other build settings will remain unchanged. Returns null if errors were
   *     reported to the handler.
   * @throws InterruptedException if evaluating the transition is interrupted
   */
  @Nullable
  public abstract ImmutableMap<String, Map<String, Object>> evaluate(
      Map<String, Object> previousSettings,
      StructImpl attributeMapper,
      ImmutableMap<String, OptionInfo> optionInfoMap,
      EventHandler handler)
      throws InterruptedException;

  public static StarlarkDefinedConfigTransition newRegularTransition(
      StarlarkCallable impl,
      List<String> inputs,
      List<String> outputs,
      StarlarkSemantics semantics,
      Label parentLabel,
      Location location,
      RepositoryMapping repoMapping)
      throws EvalException {
    return new RegularTransition(
        impl, inputs, outputs, semantics, parentLabel, location, repoMapping);
  }

  public static StarlarkDefinedConfigTransition newExecTransition(
      StarlarkCallable impl,
      List<String> inputs,
      List<String> outputs,
      StarlarkSemantics semantics,
      Label parentLabel,
      Location location,
      RepositoryMapping repoMapping)
      throws EvalException {
    return new ExecTransition(impl, inputs, outputs, semantics, parentLabel, location, repoMapping);
  }

  public static StarlarkDefinedConfigTransition newAnalysisTestTransition(
      Map<String, Object> changedSettings,
      RepositoryMapping repoMapping,
      Label parentLabel,
      Location location)
      throws EvalException {
    return new AnalysisTestTransition(changedSettings, repoMapping, parentLabel, location);
  }

  private static final class AnalysisTestTransition extends StarlarkDefinedConfigTransition {
    private final Map<String, Object> changedSettings;
    private final int hashCode;

    AnalysisTestTransition(
        Map<String, Object> changedSettings,
        RepositoryMapping repoMapping,
        Label parentLabel,
        Location location)
        throws EvalException {
      super(
          /* inputs= */ ImmutableList.of(),
          ImmutableList.copyOf(changedSettings.keySet()),
          repoMapping,
          parentLabel,
          location);
      this.changedSettings = changedSettings;
      this.hashCode = HashCodes.hashObjects(getInputs(), getOutputs(), changedSettings);
    }

    @Override
    public boolean isForAnalysisTesting() {
      return true;
    }

    @Override
    public boolean isExecTransition() {
      return false;
    }

    @Override
    public ImmutableMap<String, Map<String, Object>> evaluate(
        Map<String, Object> previousSettings,
        StructImpl attributeMapper,
        ImmutableMap<String, OptionInfo> optionInfoMap,
        EventHandler eventHandler) {
      return ImmutableMap.of(PATCH_TRANSITION_KEY, changedSettings);
    }

    @Override
    public void repr(Printer printer) {
      printer.append("<analysis_test_transition object>");
    }

    @Override
    public boolean equals(Object object) {
      if (object == this) {
        return true;
      }
      if (object instanceof AnalysisTestTransition otherTransition) {
        return Objects.equals(otherTransition.getInputs(), this.getInputs())
            && Objects.equals(otherTransition.getOutputs(), this.getOutputs())
            && Objects.equals(otherTransition.changedSettings, this.changedSettings);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  /** A transition with a user-defined implementation function. */
  public static sealed class RegularTransition extends StarlarkDefinedConfigTransition {
    private final StarlarkCallable impl;
    private final StarlarkSemantics semantics;
    private final RepositoryMapping repoMapping;
    private final int hashCode;

    RegularTransition(
        StarlarkCallable impl,
        List<String> inputs,
        List<String> outputs,
        StarlarkSemantics semantics,
        Label parentLabel,
        Location location,
        RepositoryMapping repoMapping)
        throws EvalException {
      super(inputs, outputs, repoMapping, parentLabel, location);
      this.impl = impl;
      this.semantics = semantics;
      this.repoMapping = repoMapping;
      this.hashCode = HashCodes.hashObjects(getInputs(), getOutputs(), impl);
    }

    @Override
    public boolean isForAnalysisTesting() {
      return false;
    }

    @Override
    public boolean isExecTransition() {
      return false;
    }

    /** An exception for validating that a transition is properly constructed */
    private static final class UnreadableInputSettingException extends Exception {
      UnreadableInputSettingException(String unreadableSetting, Class<?> unreadableClass) {
        super(
            String.format(
                "Input build setting %s is of type %s, which is unreadable in Starlark."
                    + " Please submit a feature request.",
                unreadableSetting, unreadableClass));
      }
    }

    /**
     * Copy settings into Starlark-readable Dict.
     *
     * <p>The returned (outer) Dict will be immutable but all the underlying entries will have
     * mutability given by the entryMu param.
     *
     * @param settings map os settings to copy over * {@code optionInfoMap} info about each option's
     *     {@link Option} type
     * @param optionInfoMap info about each option's {@link Option} type
     * @param entryMu Mutability context to use when copying individual entries
     * @throws UnreadableInputSettingException when entry in build setting is not convertable (using
     *     {@link Starlark#fromJava})
     */
    private static Dict<String, Object> createBuildSettingsDict(
        Map<String, Object> settings,
        ImmutableMap<String, OptionInfo> optionInfoMap,
        Mutability entryMu)
        throws UnreadableInputSettingException {

      // Need to convert contained values into Starlark readable values.
      Dict.Builder<String, Object> builder = Dict.builder();
      for (Map.Entry<String, Object> entry : settings.entrySet()) {
        try {
          builder.put(entry.getKey(), Starlark.fromJava(entry.getValue(), entryMu));
        } catch (Starlark.InvalidStarlarkValueException e) {
          // Starlark#frromJava doesn't know how to read this value. Try again with a special
          // allowlist of types we know how to make Starlark-compatible. This is not complete. If a
          // value a) isn't Starlark-convertible and b) not special-cased here, Bazel emits a "can't
          // process this setting" error.
          builder.put(
              entry.getKey(),
              Starlark.fromJava(
                  getTransitionSafeString(entry.getKey(), entry.getValue(), optionInfoMap),
                  entryMu));
        }
      }

      // Want the 'outer' build settings dictionary to be immutable
      return builder.buildImmutable();
    }

    /**
     * Native flag types known to serialize and deserialize cleanly to strings for Starlark
     * evaluation.
     *
     * <p>This is an intentionally conservative list intended to support Starlark exec transitions
     * ({@link ExecutionTransitionFactory}).
     *
     * <p>We'd ideally represent these directly as class types instead of strings. But that would
     * add dependencies on rule-related library to this class, which breaks Bazel linking.
     */
    private static final ImmutableSet<String> SAFE_NATIVE_FLAG_TYPES =
        ImmutableSet.of(
            "AndroidManifestMerger",
            "ManifestMergerOrder",
            "ImportDepsCheckingLevel",
            "JavaClasspathMode",
            "StrictDepsMode",
            "PythonVersion",
            "OneVersionEnforcementLevel");

    /**
     * Converts a Java-native flag value to a Starlark-readable string, or throws an exception if
     * the flag's type can't be represented in Starlark.
     *
     * <p>This only kicks in for values {@link Starlark#fromJava} failed to directly convert. That
     * implies they need extra processing, which is what happens here.
     *
     * <p>This is incomplete. It only handles types we explicitly know are Starlark-convertible or
     * that handle {@link Converter#starlarkConvertible()}. Other flags emit a "can't process this
     * setting" error.
     */
    private static Object getTransitionSafeString(
        String name, Object value, ImmutableMap<String, OptionInfo> optionInfoMap)
        throws UnreadableInputSettingException {
      if (value instanceof RegexFilter) {
        // RegExFilter doesn't serialize to the same value it originally had on the command line.
        // Call toOriginalString, to do that properly.
        return Verify.verifyNotNull(((RegexFilter) value).toOriginalString());
      }
      if (value instanceof PathFragment
          || value instanceof TriState
          || value instanceof ExecConfigurationDistinguisherScheme
          || value instanceof OutputDirectoryNamingScheme
          || value instanceof OutputPathsMode
          || value instanceof IncludeConfigFragmentsEnum
          || SAFE_NATIVE_FLAG_TYPES.contains(value.getClass().getSimpleName())) {
        // Starlark#fromJava doesn't understand these Bazel-specific Java types. But their
        // toString() methods serialize cleanly.
        return value.toString();
      }
      // See if the option's converter knows how to produce to Starlark values.
      OptionDefinition optionDef =
          optionInfoMap.get(name.substring(COMMAND_LINE_OPTION_PREFIX.length())).getDefinition();
      if (!optionDef.getConverter().starlarkConvertible()) {
        throw new UnreadableInputSettingException(name, value.getClass());
      }
      if (optionDef.allowsMultiple()) {
        // allowMultiple() options are complicated (see the definition of allowMultiple() in
        // Option.java). They must be typed as a List<T>. Their converters can return either T or
        // List<T>. In the latter case, the typed value is a concatenation of all the converted
        // lists.
        //
        // Option metadata doesn't include enough information to know which version of the converter
        // it uses. Also note we can't encode this information in the converter because different
        // options may use the same converter with or without allowMultiple.
        //
        // For lack of direct support, this code infers the right logic.
        var asList = ((List<?>) value);
        // If this is an empty list, Starlark#fromJava should have handled it.
        Verify.verify(!asList.isEmpty());
        // The converter matches the option with generics. So we don't actually know how their types
        // compare at runtime. We know allowMultiple options must be typed List<T>. We assume if the
        // converter doesn't return a list, it returns a single T. Else it returns a List<T>. This
        // works as long as the option isn't a List<List<?>>. This verification check confirms that.
        Verify.verify(!(asList.get(0) instanceof List));
        return asList.stream()
            .map(o -> optionDef.getConverter().reverseForStarlark(o))
            .collect(toImmutableList());
      } else {
        // This isn't allowMultiple, so the converter is a straightforward reversal.
        return optionDef.getConverter().reverseForStarlark(value);
      }
    }

    /**
     * This method evaluates the implementation function of the transition.
     *
     * <p>In the case of a {@link
     * com.google.devtools.build.lib.analysis.config.transitions.PatchTransition}, the impl fxn
     * returns a {@link Dict} of option name strings to option value object.
     *
     * <p>In the case of {@link
     * com.google.devtools.build.lib.analysis.config.transitions.SplitTransition}, the impl fxn can
     * return either a {@link Dict} of String keys to {@link Dict} values. Or it can return a list
     * of {@link Dict}s in cases where the consumer doesn't care about differentiating between the
     * splits (i.e. accessing later via {@code ctx.split_attrs}).
     *
     * @param previousSettings a map representing the previous build settings
     * @param attrObject the attributes of the rule to which this transition is attached
     * @param optionInfoMap info about each option's {@link Option} type
     * @param handler handler for messages
     * @return a map of the changed settings. An empty map is shorthand for the transition not
     *     changing any settings ({@code return {} } is simpler than assigning every output setting
     *     to itself). A null return means an error occurred and results are unusable.
     */
    // TODO(bazel-team): integrate dict-of-dicts return type with ctx.split_attr
    @Nullable
    @Override
    public ImmutableMap<String, Map<String, Object>> evaluate(
        Map<String, Object> previousSettings,
        StructImpl attrObject,
        ImmutableMap<String, OptionInfo> optionInfoMap,
        EventHandler handler)
        throws InterruptedException {
      // Call the Starlark function.
      Object result;
      try (Mutability mu = Mutability.create("eval_transition_function")) {
        // TODO(brandjon): If the resulting values of Starlark transitions ever evolve to be
        //  complex Starlark objects like structs as opposed to the ints, strings,
        //  etc they are today then we need a real symbol generator which is used
        //  to calculate equality between instances of Starlark objects. A candidate
        //  for transition instance uniqueness is the Rule and configuration that
        //  are used as inputs to the configuration.
        StarlarkThread thread = StarlarkThread.createTransient(mu, semantics);
        thread.setPrintHandler(Event.makeDebugPrintHandler(handler));
        Dict<String, Object> previousSettingsDict =
            createBuildSettingsDict(previousSettings, optionInfoMap, mu);

        result = Starlark.positionalOnlyCall(thread, impl, previousSettingsDict, attrObject);
      } catch (UnreadableInputSettingException ex) {
        // TODO(blaze-configurability-team): Ideally, the error would happen (and thus location)
        //   at the transition() call during loading phase. Instead, error happens at the impl
        //  function call during the analysis phase.
        handler.handle(
            Event.error(
                impl.getLocation(),
                String.format("before calling %s: %s", impl.getName(), ex.getMessage())));
        return null;
      } catch (EvalException ex) {
        handler.handle(Event.error(null, ex.getMessageWithStack()));
        return null;
      }

      if (result instanceof NoneType) {
        return ImmutableMap.of();
      } else if (result instanceof Dict<?, ?> dict) {
        if (dict.isEmpty()) {
          return ImmutableMap.of();
        }
        try {
          Map<String, ?> dictOfDict =
              Dict.cast(result, String.class, Dict.class, "dictionary of options dictionaries");
          ImmutableMap.Builder<String, Map<String, Object>> builder = ImmutableMap.builder();
          for (Map.Entry<String, ?> entry : dictOfDict.entrySet()) {
            Map<String, Object> rawDict =
                Dict.cast(entry.getValue(), String.class, Object.class, "dictionary of options");
            ImmutableMap<String, Object> canonicalizedDict =
                canonicalizeTransitionOutputDict(rawDict, repoMapping, parentLabel, getOutputs());
            builder.put(entry.getKey(), canonicalizedDict);
          }
          return builder.buildOrThrow();
        } catch (ValidationException ex) {
          errorf(handler, "invalid result from transition function: %s", ex.getMessage());
          return null;
        } catch (EvalException ex) {
          // Fall through assuming the Dict#cast call didn't work as this is a single dictionary
          // not a dictionary of dictionaries.
        }
        try {
          // Try if this is a patch transition.
          Map<String, Object> rawDict =
              Dict.cast(result, String.class, Object.class, "dictionary of options");
          ImmutableMap<String, Object> canonicalizedDict =
              canonicalizeTransitionOutputDict(rawDict, repoMapping, parentLabel, getOutputs());
          return ImmutableMap.of(PATCH_TRANSITION_KEY, canonicalizedDict);
        } catch (EvalException | ValidationException ex) {
          // TODO(adonovan): explain "want dict<string, any> or dict<string, dict<string, any>>".
          errorf(handler, "invalid result from transition function: %s", ex.getMessage());
          return null;
        }

      } else if (result instanceof Sequence<?> sequence) {
        if (sequence.isEmpty()) {
          return ImmutableMap.of();
        }
        ImmutableMap.Builder<String, Map<String, Object>> builder = ImmutableMap.builder();
        try {
          int i = 0;
          for (Dict<?, ?> entry :
              Sequence.cast(result, Dict.class, "dictionary of options dictionaries")) {
            // TODO(b/146347033): Document this behavior.
            Map<String, Object> rawDict =
                Dict.cast(entry, String.class, Object.class, "dictionary of options");
            ImmutableMap<String, Object> canonicalizedDict =
                canonicalizeTransitionOutputDict(rawDict, repoMapping, parentLabel, getOutputs());
            builder.put(Integer.toString(i++), canonicalizedDict);
          }
        } catch (EvalException | ValidationException ex) {
          // TODO(adonovan): explain "want sequence of dict<string, any>".
          errorf(handler, "invalid result from transition function: %s", ex.getMessage());
          return null;
        }
        return builder.buildOrThrow();
      } else {
        errorf(
            handler,
            "transition function returned %s, want dict or list of dicts",
            Starlark.type(result));
        return null;
      }
    }

    @FormatMethod
    private void errorf(EventHandler handler, String format, Object... args) {
      handler.handle(Event.error(impl.getLocation(), String.format(format, args)));
    }

    /**
     * Validates that function outputs exactly the set of outputs it declares, as they were declared
     * (i.e. not canonicalized or in another form of the same label). More thorough checking (like
     * type checking of output values) is done elsewhere because it requires loading. see {@link
     * com.google.devtools.build.lib.analysis.starlark.StarlarkTransition#validate}
     *
     * @param returnedKeySet actual key set of dict returned by starlark transition.
     * @param declaredReturnSettings list of build settings to return as declared by the 'outputs'
     *     parameter (in their given form) to the transition definition.
     */
    private static void validateFunctionOutputsMatchesDeclaredOutputs(
        Set<String> returnedKeySet, List<String> declaredReturnSettings)
        throws ValidationException {
      if (returnedKeySet.containsAll(declaredReturnSettings)
          && returnedKeySet.size() == declaredReturnSettings.size()) {
        return;
      }

      LinkedHashSet<String> remainingOutputs = Sets.newLinkedHashSet(declaredReturnSettings);
      for (String outputKey : returnedKeySet) {
        if (!remainingOutputs.remove(outputKey)) {
          throw new ValidationException(
              String.format("transition function returned undeclared output '%s'", outputKey));
        }
      }

      if (!remainingOutputs.isEmpty()) {
        throw new ValidationException(
            String.format(
                "transition outputs [%s] were not defined by transition function",
                String.join(",", remainingOutputs)));
      }
    }

    /**
     * Given a map of build settings to their values, return a map with the same build settings but
     * in their canonicalized string form to their values.
     *
     * <p>TODO(blaze-configurability): It would be nice if this method also returned a map of the
     * canonicalized settings to given settings so that when we throw the "unrecognized returned
     * option" warning we can show the setting as the user gave it as well as in its canonicalized
     * form.
     */
    private static ImmutableMap<String, Object> canonicalizeTransitionOutputDict(
        Map<String, Object> dict,
        RepositoryMapping repoMapping,
        Label parentLabel,
        List<String> outputs)
        throws EvalException, ValidationException {
      validateFunctionOutputsMatchesDeclaredOutputs(dict.keySet(), outputs);

      Map<String, String> canonicalizedToGiven = new HashMap<>();
      ImmutableSortedMap.Builder<String, Object> canonicalizedDict =
          new ImmutableSortedMap.Builder<>(Ordering.natural());
      for (Map.Entry<String, Object> entry : dict.entrySet()) {
        String returnedSetting = entry.getKey();
        String label;
        try {
          label = canonicalizeSetting(returnedSetting, repoMapping, parentLabel);
        } catch (LabelSyntaxException unused) {
          throw Starlark.errorf(
              "Malformed label in transition return dictionary: '%s'", returnedSetting);
        }
        String previousGiven = canonicalizedToGiven.put(label, returnedSetting);
        if (previousGiven != null) {
          throw Starlark.errorf(
              "Transition implementation function returns the same option '%s' in two different"
                  + " keys: '%s' and '%s'",
              label, returnedSetting, previousGiven);
        }
        canonicalizedDict.put(label, entry.getValue());
      }
      return canonicalizedDict.buildOrThrow();
    }

    @Override
    public void repr(Printer printer) {
      printer.append("<transition object>");
    }

    @Override
    public boolean equals(Object object) {
      if (object == this) {
        return true;
      }
      if (object instanceof RegularTransition otherTransition) {
        return Objects.equals(otherTransition.getInputs(), this.getInputs())
            && Objects.equals(otherTransition.getOutputs(), this.getOutputs())
            && Objects.equals(otherTransition.impl, this.impl);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  /** A transition implementation used only for Starlark-defined exec transitions. */
  private static final class ExecTransition extends RegularTransition {
    private ExecTransition(
        StarlarkCallable impl,
        List<String> inputs,
        List<String> outputs,
        StarlarkSemantics semantics,
        Label parentLabel,
        Location location,
        RepositoryMapping repoMapping)
        throws EvalException {
      super(impl, inputs, outputs, semantics, parentLabel, location, repoMapping);
    }

    @Override
    public boolean isExecTransition() {
      return true;
    }
  }

  /** An exception for validating that a transition is properly constructed */
  public static final class ValidationException extends Exception {
    public ValidationException(String message) {
      super(message);
    }

    @FormatMethod
    public static ValidationException format(String format, Object... args) {
      return new ValidationException(String.format(format, args));
    }
  }
}
