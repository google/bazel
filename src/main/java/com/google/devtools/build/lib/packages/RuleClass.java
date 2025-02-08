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

package com.google.devtools.build.lib.packages;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.STRING_NO_INTERN;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.TransitionType;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.Attribute.StarlarkComputedDefaultTemplate;
import com.google.devtools.build.lib.packages.Attribute.StarlarkComputedDefaultTemplate.CannotPrecomputeDefaultsException;
import com.google.devtools.build.lib.packages.BuildType.SelectorList;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.MissingFragmentPolicy;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleFactory.AttributeValues;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkSubruleApi;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.FormatMethod;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.spelling.SpellChecker;

/**
 * Instances of RuleClass encapsulate the set of attributes of a given "class" of rule, such as
 * <code>cc_binary</code>.
 *
 * <p>This is an instance of the "meta-class" pattern for Rules: we achieve using <i>values</i> what
 * subclasses achieve using <i>types</i>. (The "Design Patterns" book doesn't include this pattern,
 * so think of it as something like a cross between a Flyweight and a State pattern. Like Flyweight,
 * we avoid repeatedly storing data that belongs to many instances. Like State, we delegate from
 * Rule to RuleClass for the specific behavior of that rule (though unlike state, a Rule object
 * never changes its RuleClass). This avoids the need to declare one Java class per class of Rule,
 * yet achieves the same behavior.)
 *
 * <p>The use of a metaclass also allows us to compute a mapping from Attributes to small integers
 * and share this between all rules of the same metaclass. This means we can save the attribute
 * dictionary for each rule instance using an array, which is much more compact than a hashtable.
 *
 * <p>Rule classes whose names start with "$" are considered "abstract"; since they are not valid
 * identifiers, they cannot be named in the build language. However, they are useful for grouping
 * related attributes which are inherited.
 *
 * <p>The exact values in this class are important. In particular:
 *
 * <ul>
 *   <li>Changing an attribute from MANDATORY to OPTIONAL creates the potential for null-pointer
 *       exceptions in code that expects a value.
 *   <li>Attributes whose names are preceded by a "$" or a ":" are "hidden", and cannot be redefined
 *       in a BUILD file. They are a useful way of adding a special dependency. By convention,
 *       attributes starting with "$" are implicit dependencies, and those starting with a ":" are
 *       late-bound implicit dependencies, i.e. dependencies that can only be resolved when the
 *       configuration is known.
 *   <li>Attributes should not be introduced into the hierarchy higher then necessary.
 *   <li>The 'deps' and 'data' attributes are treated specially by the code that builds the runfiles
 *       tree. All targets appearing in these attributes appears beneath the ".runfiles" tree; in
 *       addition, "deps" may have rule-specific semantics.
 * </ul>
 *
 * TODO(bazel-team): Consider breaking up this class in more manageable subclasses.
 */
// Non-final only for mocking in tests. Do not subclass!
@Immutable
public class RuleClass implements RuleClassData {

  /** The name attribute, present for all rules at index 0. Also defined for all symbolic macros. */
  public static final Attribute NAME_ATTRIBUTE =
      attr("name", STRING_NO_INTERN)
          .nonconfigurable("All rules have a non-customizable \"name\" attribute")
          .mandatory()
          .build();

  /**
   * Maximum attributes per RuleClass. Current value was chosen to be high enough to be considered a
   * non-breaking change for reasonable use. It was also chosen to be low enough to give significant
   * headroom before hitting limits imposed by the compact attribute value storage strategy in
   * {@link Rule}.
   */
  private static final int MAX_ATTRIBUTES = 200;

  /**
   * Maximum attribute name length. Chosen to accommodate existing and prevent extreme outliers from
   * forming - extreme values create bloat, both in memory usage and various outputs, including but
   * not limited to, query output.
   */
  private static final int MAX_ATTRIBUTE_NAME_LENGTH = 128;

  @SerializationConstant
  static final Function<? super Rule, Set<String>> NO_OPTION_REFERENCE =
      Functions.constant(ImmutableSet.of());

  public static final PathFragment THIRD_PARTY_PREFIX = PathFragment.create("third_party");
  public static final PathFragment EXPERIMENTAL_PREFIX = PathFragment.create("experimental");

  /** The attribute that declares the set of metadata labels which apply to this target. */
  public static final String APPLICABLE_METADATA_ATTR = "package_metadata";

  public static final String APPLICABLE_METADATA_ATTR_ALT = "applicable_licenses";

  public static final String DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME = "test";
  // The test exec group should not inherit any exec constraints or toolchains from the default exec
  // group since the execution platform for a test is generally independent (and often different)
  // from the execution platform of the actions that produce the test executable.
  public static final ExecGroup DEFAULT_TEST_RUNNER_EXEC_GROUP = ExecGroup.builder().build();

  /** Interface for determining whether a rule needs toolchain resolution or not. */
  @FunctionalInterface
  public interface ToolchainResolutionMode extends Serializable {
    boolean useToolchainResolution(Rule rule);

    ToolchainResolutionMode ENABLED = (unused) -> true;
    ToolchainResolutionMode DISABLED = (unused) -> false;
  }

  /** Enum to determine whether a rule class uses auto exec groups. */
  public enum AutoExecGroupsMode {
    /** The rule class does not support auto exec groups. */
    DISABLED,
    /** The rule class uses auto exec groups regardless of other settings in the configuration. */
    ENABLED,
    /**
     * The rule class uses auto exec groups if configured using the {@code _use_auto_exec_groups}
     * attribute and {@code --incompatible_auto_exec_groups} flag.
     */
    DYNAMIC;

    public boolean isEnabled(AttributeMap attributes, boolean isAllowedByConfiguration) {
      return switch (this) {
        case DISABLED -> false;
        case ENABLED -> true;
        case DYNAMIC -> {
          if (attributes.has("$use_auto_exec_groups")) {
            yield attributes.get("$use_auto_exec_groups", Type.BOOLEAN);
          } else {
            yield isAllowedByConfiguration;
          }
        }
      };
    }
  }

  /** A factory or builder class for rule implementations. */
  public interface ConfiguredTargetFactory<
      ConfiguredTargetT, ContextT, ActionConflictExceptionT extends Throwable> {
    /**
     * Returns a fully initialized configured target instance using the given context, or {@code
     * null} on certain rule errors (typically if {@code ruleContext.hasErrors()} becomes {@code
     * true} while trying to create the target).
     *
     * @throws RuleErrorException if configured target creation could not be completed due to rule
     *     errors
     * @throws ActionConflictExceptionT if there were conflicts during action registration
     */
    @Nullable
    ConfiguredTargetT create(ContextT ruleContext)
        throws InterruptedException, RuleErrorException, ActionConflictExceptionT;

    /**
     * Exception indicating that configured target creation could not be completed. General error
     * messaging should be done via {@link
     * com.google.devtools.build.lib.analysis.RuleErrorConsumer}; this exception only interrupts
     * configured target creation in cases where it can no longer continue.
     */
    final class RuleErrorException extends Exception {
      public RuleErrorException() {
        super();
      }

      public RuleErrorException(String message) {
        super(message);
      }

      public RuleErrorException(Throwable cause) {
        super(cause);
      }

      public RuleErrorException(String message, Throwable cause) {
        super(message, cause);
      }
    }
  }

  /**
   * For Bazel's constraint system: the attribute that declares the set of environments a rule
   * supports, overriding the defaults for their respective groups.
   */
  public static final String RESTRICTED_ENVIRONMENT_ATTR = "restricted_to";

  /**
   * For Bazel's constraint system: the attribute that declares the set of environments a rule
   * supports, appending them to the defaults for their respective groups.
   */
  public static final String COMPATIBLE_ENVIRONMENT_ATTR = "compatible_with";

  /**
   * For Bazel's constraint system: the attribute that declares the list of constraints that the
   * target platform must satisfy to be considered compatible.
   */
  public static final String TARGET_COMPATIBLE_WITH_ATTR = "target_compatible_with";

  /**
   * For Bazel's constraint system: the attribute that declares the list of constraints that the
   * default exec group's execution platform must satisfy to be considered compatible.
   */
  public static final String EXEC_COMPATIBLE_WITH_ATTR = "exec_compatible_with";

  /**
   * For Bazel's constraint system: the attribute that declares the list of constraints that the
   * given exec groups' execution platforms must satisfy to be considered compatible.
   */
  public static final String EXEC_GROUP_COMPATIBLE_WITH_ATTR = "exec_group_compatible_with";

  /**
   * The attribute that declares execution properties that should be added to actions created by
   * this target.
   */
  public static final String EXEC_PROPERTIES_ATTR = "exec_properties";

  /**
   * For Bazel's constraint system: the implicit attribute used to store rule class restriction
   * defaults as specified by {@link Builder#restrictedTo}.
   */
  public static final String DEFAULT_RESTRICTED_ENVIRONMENT_ATTR =
      "$" + RESTRICTED_ENVIRONMENT_ATTR;

  /**
   * For Bazel's constraint system: the implicit attribute used to store rule class compatibility
   * defaults as specified by {@link Builder#compatibleWith}.
   */
  public static final String DEFAULT_COMPATIBLE_ENVIRONMENT_ATTR =
      "$" + COMPATIBLE_ENVIRONMENT_ATTR;

  /**
   * Name of the attribute that stores all {@link
   * com.google.devtools.build.lib.rules.config.ConfigRuleClasses} labels this rule references (i.e.
   * select() keys). This is specially populated in {@link #populateRuleAttributeValues}.
   *
   * <p>This isn't technically necessary for builds: select() keys are evaluated in {@link
   * com.google.devtools.build.lib.skyframe.PrerequisiteProducer#computeConfigConditions} instead of
   * normal dependency resolution because they're needed to determine other dependencies. So there's
   * no intrinsic reason why we need an extra attribute to store them.
   *
   * <p>There are four reasons why we still create this attribute:
   *
   * <ol>
   *   <li>Collecting them once in {@link #populateRuleAttributeValues} instead of multiple times in
   *       ConfiguredTargetFunction saves extra looping over the rule's attributes.
   *   <li>Query's dependency resolution has no equivalent of {@link
   *       com.google.devtools.build.lib.skyframe.PrerequisiteProducer#computeConfigConditions} and
   *       we need to make sure its coverage remains complete.
   *   <li>Manual configuration trimming uses the normal dependency resolution process to work
   *       correctly and config_setting keys are subject to this trimming.
   *   <li>{@link Rule#useToolchainResolution(Rule) supports conditional toolchain resolution for
   *      targets with non-empty select()s. This requirement would go away if platform info was
   *      prepared for all rules regardless of toolchain needs.
   * </ol>
   *
   * <p>It should be possible to clean up these issues if we decide we don't want an artificial
   * attribute dependency. But care has to be taken to do that safely.
   */
  public static final String CONFIG_SETTING_DEPS_ATTRIBUTE = "$config_dependencies";

  /**
   * A support class to make it easier to create {@code RuleClass} instances. This class follows the
   * 'fluent builder' pattern.
   *
   * <p>The {@link #addAttribute} method will throw an exception if an attribute of that name
   * already exists. Use {@link #overrideAttribute} in that case.
   */
  public static final class Builder {
    private static final Pattern RULE_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The type of the rule class, which determines valid names and required attributes. */
    public enum RuleClassType {
      /**
       * Abstract rules are intended for rule classes that are just used to factor out common
       * attributes, and for rule classes that are used only internally. These rules cannot be
       * instantiated by a BUILD file.
       *
       * <p>The rule name must contain a '$' and {@link TargetUtils#isTestRuleName} must return
       * false for the name.
       */
      ABSTRACT {
        @Override
        public void checkName(String name) {
          Preconditions.checkArgument(
              (name.contains("$") && !TargetUtils.isTestRuleName(name)) || name.isEmpty());
        }

        @Override
        public void checkAttributes(Map<String, Attribute> attributes) {
          // No required attributes.
        }
      },

      /**
       * Invisible rule classes should contain a dollar sign so that they cannot be instantiated by
       * the user. They are different from abstract rules in that they can be instantiated at will.
       */
      INVISIBLE {
        @Override
        public void checkName(String name) {
          Preconditions.checkArgument(name.contains("$"));
        }

        @Override
        public void checkAttributes(Map<String, Attribute> attributes) {
          // No required attributes.
        }
      },

      /**
       * Normal rules are instantiable by BUILD files, possibly via a macro (symbolic or legacy), in
       * which case the rule's symbol is namespaced under {@code native}. Normal rule names must
       * therefore obey the rules for identifiers in the BUILD language. In addition, {@link
       * TargetUtils#isTestRuleName} must return false for the name.
       */
      NORMAL {
        @Override
        public void checkName(String name) {
          Preconditions.checkArgument(
              !TargetUtils.isTestRuleName(name) && RULE_NAME_PATTERN.matcher(name).matches(),
              "Invalid rule name: %s",
              name);
        }

        @Override
        public void checkAttributes(Map<String, Attribute> attributes) {
          for (Attribute attribute : REQUIRED_ATTRIBUTES_FOR_NORMAL_RULES) {
            Attribute presentAttribute = attributes.get(attribute.getName());
            Preconditions.checkState(
                presentAttribute != null,
                "Missing mandatory '%s' attribute in normal rule class.",
                attribute.getName());
            Preconditions.checkState(
                presentAttribute.getType().equals(attribute.getType()),
                "Mandatory attribute '%s' in normal rule class has incorrect type (expected"
                    + " %s).",
                attribute.getName(),
                attribute.getType());
          }
        }
      },

      /**
       * Normal rules with the additional restriction that they can only be instantiated by BUILD
       * files or legacy macros - but not symbolic macros.
       */
      BUILD_ONLY {
        @Override
        public void checkName(String name) {
          NORMAL.checkName(name);
        }

        @Override
        public void checkAttributes(Map<String, Attribute> attributes) {
          NORMAL.checkAttributes(attributes);
        }
      },

      /**
       * Workspace rules can only be instantiated from a WORKSPACE file. Their names obey the rule
       * for identifiers.
       */
      WORKSPACE {
        @Override
        public void checkName(String name) {
          Preconditions.checkArgument(RULE_NAME_PATTERN.matcher(name).matches());
        }

        @Override
        public void checkAttributes(Map<String, Attribute> attributes) {
          // No required attributes.
        }
      },

      /**
       * Test rules are instantiable by BUILD files and are handled specially when run with the
       * 'test' command. Their names must obey the rules for identifiers in the BUILD language and
       * {@link TargetUtils#isTestRuleName} must return true for the name.
       *
       * <p>In addition, test rules must contain certain attributes. See {@link
       * Builder#REQUIRED_ATTRIBUTES_FOR_TESTS}.
       */
      TEST {
        @Override
        public void checkName(String name) {
          Preconditions.checkArgument(
              TargetUtils.isTestRuleName(name) && RULE_NAME_PATTERN.matcher(name).matches());
        }

        @Override
        public void checkAttributes(Map<String, Attribute> attributes) {
          for (Attribute attribute : REQUIRED_ATTRIBUTES_FOR_TESTS) {
            Attribute presentAttribute = attributes.get(attribute.getName());
            Preconditions.checkState(
                presentAttribute != null,
                "Missing mandatory '%s' attribute in test rule class.",
                attribute.getName());
            Preconditions.checkState(
                presentAttribute.getType().equals(attribute.getType()),
                "Mandatory attribute '%s' in test rule class has incorrect type (expected %s).",
                attribute.getName(),
                attribute.getType());
          }
        }
      },

      /**
       * Placeholder rules are only instantiated when packages which refer to non-native rule
       * classes are deserialized. At this time, non-native rule classes can't be serialized. To
       * prevent crashes on deserialization, when a package containing a rule with a non-native rule
       * class is deserialized, the rule is assigned a placeholder rule class. This is compatible
       * with our limited set of package serialization use cases.
       *
       * <p>Placeholder rule class names obey the rule for identifiers.
       */
      PLACEHOLDER {
        @Override
        public void checkName(String name) {
          Preconditions.checkArgument(RULE_NAME_PATTERN.matcher(name).matches(), name);
        }

        @Override
        public void checkAttributes(Map<String, Attribute> attributes) {
          // No required attributes; this rule class cannot have the wrong set of attributes now
          // because, if it did, the rule class would have failed to build before the package
          // referring to it was serialized.
        }
      };

      /**
       * Checks whether the given name is valid for the current rule class type.
       *
       * @throws IllegalArgumentException if the name is not valid
       */
      public abstract void checkName(String name);

      /**
       * Checks whether the given set of attributes contains all the required attributes for the
       * current rule class type.
       *
       * @throws IllegalArgumentException if a required attribute is missing
       */
      protected abstract void checkAttributes(Map<String, Attribute> attributes);
    }

    /**
     * A predicate that filters rule classes based on their names.
     *
     * <p>In {@link Rule}, {@code ruleClass} refers to the string name of the rule class while
     * {@code ruleClassObject} refers to the actual instance of {@link RuleClass}. Here, {@code
     * RuleClassName} emphasizes that the underlying logic of the predicate is based only on the
     * {@code String} name. The public methods, {@link #asPredicateOfRuleClass} and {@link
     * #asPredicateOfRuleClassObject} revert to the common convention used in {@link Rule}.
     */
    @AutoCodec
    public static class RuleClassNamePredicate {

      private static final RuleClassNamePredicate UNSPECIFIED_INSTANCE =
          new RuleClassNamePredicate(ImmutableSet.of(), PredicateType.UNSPECIFIED, null);

      private final ImmutableSet<String> ruleClassNames;

      private final PredicateType predicateType;

      private final Predicate<String> ruleClassNamePredicate;
      private final Predicate<RuleClass> ruleClassPredicate;
      // if non-null, used ONLY for checking overlap
      @Nullable private final Set<?> overlappable;

      @VisibleForSerialization
      enum PredicateType {
        ONLY,
        All_EXCEPT,
        UNSPECIFIED
      }

      @VisibleForSerialization
      RuleClassNamePredicate(
          ImmutableSet<String> ruleClassNames, PredicateType predicateType, Set<?> overlappable) {
        this.ruleClassNames = ruleClassNames;
        this.predicateType = predicateType;
        this.overlappable = overlappable;

        switch (predicateType) {
          case All_EXCEPT -> {
            Predicate<String> containing = only(ruleClassNames).asPredicateOfRuleClass();
            ruleClassNamePredicate =
                new DescribedPredicate<>(Predicates.not(containing), "all but " + containing);
            ruleClassPredicate =
                new DescribedPredicate<>(
                    Predicates.compose(ruleClassNamePredicate, RuleClass::getName),
                    ruleClassNamePredicate.toString());
          }
          case ONLY -> {
            ruleClassNamePredicate =
                new DescribedPredicate<>(
                    Predicates.in(ruleClassNames), StringUtil.joinEnglishList(ruleClassNames));
            ruleClassPredicate =
                new DescribedPredicate<>(
                    Predicates.compose(ruleClassNamePredicate, RuleClass::getName),
                    ruleClassNamePredicate.toString());
          }
          case UNSPECIFIED -> {
            ruleClassNamePredicate = Predicates.alwaysTrue();
            ruleClassPredicate = Predicates.alwaysTrue();
          }
          default ->
              // This shouldn't happen normally since the constructor is private and within this
              // file.
              throw new IllegalArgumentException(
                  "Predicate type was not specified when constructing a RuleClassNamePredicate.");
        }
      }

      public static RuleClassNamePredicate only(Iterable<String> ruleClassNamesAsIterable) {
        ImmutableSet<String> ruleClassNames = ImmutableSet.copyOf(ruleClassNamesAsIterable);
        return new RuleClassNamePredicate(ruleClassNames, PredicateType.ONLY, ruleClassNames);
      }

      public static RuleClassNamePredicate only(String... ruleClasses) {
        return only(Arrays.asList(ruleClasses));
      }

      public static RuleClassNamePredicate allExcept(String... ruleClasses) {
        ImmutableSet<String> ruleClassNames = ImmutableSet.copyOf(ruleClasses);
        Preconditions.checkState(!ruleClassNames.isEmpty(), "Use unspecified() instead");
        return new RuleClassNamePredicate(ruleClassNames, PredicateType.All_EXCEPT, null);
      }

      /**
       * This is a special sentinel value which represents a "default" {@link
       * RuleClassNamePredicate} which is unspecified. Note that a call to its {@link
       * RuleClassNamePredicate#asPredicateOfRuleClass} produces {@code
       * Predicates.<RuleClass>alwaysTrue()}, which is a sentinel value for other parts of bazel.
       */
      public static RuleClassNamePredicate unspecified() {
        return UNSPECIFIED_INSTANCE;
      }

      final Predicate<String> asPredicateOfRuleClass() {
        return ruleClassNamePredicate;
      }

      final Predicate<RuleClass> asPredicateOfRuleClassObject() {
        return ruleClassPredicate;
      }

      /**
       * Determines whether two {@code RuleClassNamePredicate}s should be considered incompatible as
       * rule class predicate and rule class warning predicate.
       *
       * <p>Specifically, if both list sets of explicit rule class names to permit, those two sets
       * must be disjoint, so the restriction only applies when both predicates have been created by
       * {@link #only}.
       */
      boolean consideredOverlapping(RuleClassNamePredicate that) {
        return this.overlappable != null
            && that.overlappable != null
            && !Collections.disjoint(this.overlappable, that.overlappable);
      }

      @Override
      public int hashCode() {
        return HashCodes.hashObjects(ruleClassNames, predicateType);
      }

      @Override
      public boolean equals(Object obj) {
        // NOTE: Specifically not checking equality of ruleClassPredicate.
        // By construction, if the name predicates are equals, the rule class predicates are, too.
        return obj instanceof RuleClassNamePredicate
            && ruleClassNames.equals(((RuleClassNamePredicate) obj).ruleClassNames)
            && predicateType.equals(((RuleClassNamePredicate) obj).predicateType);
      }

      @Override
      public String toString() {
        return ruleClassNamePredicate.toString();
      }

      /** A pass-through predicate, except that an explicit {@link #toString()} is provided. */
      private static class DescribedPredicate<T> implements Predicate<T> {
        private final Predicate<T> delegate; // the actual predicate
        private final String description;

        private DescribedPredicate(Predicate<T> delegate, String description) {
          this.delegate = delegate;
          this.description = description;
        }

        @Override
        public boolean apply(T input) {
          return delegate.apply(input);
        }

        @Override
        public int hashCode() {
          return delegate.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
          return obj instanceof DescribedPredicate
              && delegate.equals(((DescribedPredicate<?>) obj).delegate);
        }

        @Override
        public String toString() {
          return description;
        }
      }
    }

    /**
     * Name of default attribute implicitly added to all Starlark RuleClasses that are {@code
     * build_setting}s.
     */
    public static final String STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME = "build_setting_default";

    static final String STARLARK_BUILD_SETTING_HELP_ATTR_NAME = "help";

    static final String BUILD_SETTING_DEFAULT_NONCONFIGURABLE =
        "Build setting defaults are referenced during analysis.";

    /** List of required attributes for normal rules, name and type. */
    static final ImmutableList<Attribute> REQUIRED_ATTRIBUTES_FOR_NORMAL_RULES =
        ImmutableList.of(attr("tags", Types.STRING_LIST).build());

    /** List of required attributes for test rules, name and type. */
    static final ImmutableList<Attribute> REQUIRED_ATTRIBUTES_FOR_TESTS =
        ImmutableList.of(
            attr("tags", Types.STRING_LIST).build(),
            attr("size", Type.STRING).build(),
            attr("timeout", Type.STRING).build(),
            attr("flaky", Type.BOOLEAN).build(),
            attr("shard_count", Type.INTEGER).build(),
            attr("local", Type.BOOLEAN).build());

    private final String name;
    private ImmutableList<StarlarkThread.CallStackEntry> callstack = ImmutableList.of();
    private final RuleClassType type;
    @Nullable private RuleClass starlarkParent = null;
    @Nullable private StarlarkFunction initializer = null;
    @Nullable private LabelConverter labelConverterForInitializer = null;

    // The extendable may take 3 value, null means that the default allowlist should be use when
    // rule is extendable in practice.
    @Nullable private Boolean extendable = null;
    @Nullable private Label extendableAllowlist = null;
    @Nullable private Label defaultExtendableAllowlist = null;
    private final boolean starlark;
    private boolean starlarkTestable = false;
    private boolean documented;
    private boolean outputsToBindir = true;
    private boolean workspaceOnly = false;
    private boolean dependencyResolutionRule = false;
    private boolean isExecutableStarlark = false;
    private boolean isAnalysisTest = false;
    private boolean hasAnalysisTestTransition = false;
    private final ImmutableList.Builder<AllowlistChecker> allowlistCheckers =
        ImmutableList.builder();
    private boolean ignoreLicenses = false;
    private ImplicitOutputsFunction implicitOutputsFunction = SafeImplicitOutputsFunction.NONE;
    private TransitionFactory<RuleTransitionData> transitionFactory = NoTransition.getFactory();
    private ConfiguredTargetFactory<?, ?, ?> configuredTargetFactory = null;
    private final AdvertisedProviderSet.Builder advertisedProviders =
        AdvertisedProviderSet.builder();
    private StarlarkCallable configuredTargetFunction = null;
    private BuildSetting buildSetting = null;

    private ImmutableList<? extends StarlarkSubruleApi> subrules = ImmutableList.of();
    private Function<? super Rule, ? extends Set<String>> optionReferenceFunction =
        NO_OPTION_REFERENCE;

    /** The following 3 fields are null iff the rule is native. */
    @Nullable private Label ruleDefinitionEnvironmentLabel;

    @Nullable private byte[] ruleDefinitionEnvironmentDigest = null;

    // TODO(b/366027483): in theory, ruleDefinitionEnvironmentLabel ought to equal
    // starlarkExtensionLabel, and we ought to get rid of one of them.
    @Nullable private Label starlarkExtensionLabel = null;

    // May be non-null only if the rule is Starlark-defined.
    @Nullable private String starlarkDocumentation = null;

    /** This field is non-null iff the rule is a Starlark repo rule. */
    @Nullable
    private ImmutableTable<RepositoryName, String, RepositoryName>
        ruleDefinitionEnvironmentRepoMappingEntries;

    private final ConfigurationFragmentPolicy.Builder configurationFragmentPolicy =
        new ConfigurationFragmentPolicy.Builder();

    private boolean supportsConstraintChecking = true;

    private final Map<String, Attribute> attributes = new LinkedHashMap<>();
    private final Set<ToolchainTypeRequirement> toolchainTypes = new LinkedHashSet<>();
    private ToolchainResolutionMode toolchainResolutionMode = ToolchainResolutionMode.ENABLED;
    private final Set<Label> executionPlatformConstraints = new LinkedHashSet<>();
    private OutputFile.Kind outputFileKind = OutputFile.Kind.FILE;
    private final Map<String, ExecGroup> execGroups = new LinkedHashMap<>();
    private AutoExecGroupsMode autoExecGroupsMode = AutoExecGroupsMode.DYNAMIC;

    /**
     * Constructs a new {@link RuleClass.Builder} using all attributes from all parent rule classes.
     * An attribute cannot exist in more than one parent.
     *
     * <p>The rule type affects the allowed names and the required attributes (see {@link
     * RuleClassType}).
     *
     * @param parents There may be either multiple native {@code RuleClassType.ABSTRACT} rules or a
     *     single Starlark rule.
     * @throws IllegalArgumentException if an attribute with the same name exists in more than one
     *     parent
     */
    public Builder(String name, RuleClassType type, boolean starlark, RuleClass... parents) {
      Preconditions.checkArgument(
          (parents.length == 1 && parents[0].isStarlark())
              || Arrays.stream(parents).allMatch(rule -> !rule.isStarlark()));
      this.name = name;
      this.starlark = starlark;
      this.type = type;
      Preconditions.checkState(starlark || type != RuleClassType.PLACEHOLDER, name);
      this.documented = type != RuleClassType.ABSTRACT;
      addAttribute(NAME_ATTRIBUTE);
      if (parents.length == 1
          && parents[0].isStarlark()
          && parents[0].getRuleClassType() != RuleClassType.ABSTRACT) {
        // the condition removes {@link StarlarkRuleClassFunctions.baseRule} and binaryBaseRule,
        // which are marked as Starlark (because of Stardoc) && abstract at the same time
        starlarkParent = parents[0];
        Preconditions.checkArgument(starlarkParent.isExtendable());
      }
      for (RuleClass parent : parents) {
        if (parent.dependencyResolutionRule) {
          dependencyResolutionRule = true;
        } else if (dependencyResolutionRule) {
          throw new IllegalArgumentException(
              "Inconsistent value of dependencyResolutionRule among parents");
        }

        configurationFragmentPolicy.includeConfigurationFragmentsFrom(
            parent.getConfigurationFragmentPolicy());
        supportsConstraintChecking = parent.supportsConstraintChecking;

        addToolchainTypes(parent.getToolchainTypes());
        addExecutionPlatformConstraints(parent.getExecutionPlatformConstraints());
        try {
          addExecGroups(parent.getExecGroups());
        } catch (DuplicateExecGroupError e) {
          throw new IllegalArgumentException(
              String.format(
                  "An execution group named '%s' is inherited multiple times with different"
                      + " requirements in %s ruleclass",
                  e.getDuplicateGroup(), name));
        }

        this.autoExecGroupsMode = parent.getAutoExecGroupsMode();

        for (Attribute attribute : parent.getAttributes()) {
          String attrName = attribute.getName();
          Preconditions.checkArgument(
              !attributes.containsKey(attrName) || attributes.get(attrName).equals(attribute),
              "Attribute %s is inherited multiple times in %s ruleclass",
              attrName,
              name);
          attributes.put(attrName, attribute);
        }

        allowlistCheckers.addAll(parent.getAllowlistCheckers());

        advertisedProviders.addParent(parent.getAdvertisedProviders());

        if (parent.getDefaultImplicitOutputsFunction() != SafeImplicitOutputsFunction.NONE) {
          if (implicitOutputsFunction != SafeImplicitOutputsFunction.NONE) {
            throw new IllegalArgumentException("Only a single parent may set implicit outputs");
          }
          implicitOutputsFunction = parent.getDefaultImplicitOutputsFunction();
        }
      }
      // TODO(bazel-team): move this testonly attribute setting to somewhere else
      // preferably to some base RuleClass implementation.
      if (this.type.equals(RuleClassType.TEST)) {
        Attribute.Builder<Boolean> testOnlyAttr =
            attr("testonly", BOOLEAN)
                .value(true)
                .nonconfigurable("policy decision: this shouldn't depend on the configuration");
        if (attributes.containsKey("testonly")) {
          override(testOnlyAttr);
        } else {
          add(testOnlyAttr);
        }
      }
    }

    /**
     * Same as {@link #build}, except for a Starlark-defined rule class; the rule class's key will
     * be derived from the Starlark file label (falling back to the rule definition environment
     * label if null) and the name.
     *
     * @param name rule class name; if the builder was initialized with an empty name, this value
     *     will override it.
     * @param starlarkExtensionLabel the label of the Starlark file where the rule class was
     *     exported. Permitted to be null only as a workaround for unexported repository rules in
     *     legacy code - see https://github.com/bazelbuild/bazel/issues/10441 and b/111199163.
     */
    public RuleClass buildStarlark(String name, @Nullable Label starlarkExtensionLabel) {
      Preconditions.checkState(starlark);
      this.starlarkExtensionLabel = starlarkExtensionLabel;
      Label keyLabel =
          this.starlarkExtensionLabel != null
              ? this.starlarkExtensionLabel
              : ruleDefinitionEnvironmentLabel;
      return build(name, keyLabel + "%" + name);
    }

    /**
     * For a native rule, checks that required attributes for test rules are present, creates the
     * {@link RuleClass} object and returns it.
     *
     * @throws IllegalStateException if any of the required attributes is missing
     */
    public RuleClass build() {
      // For built-ins, name == key
      return build(name, name);
    }

    /** Same as {@link #build} except with setting the name and key parameters. */
    private RuleClass build(String name, String key) {
      Preconditions.checkArgument(this.name.isEmpty() || this.name.equals(name));
      type.checkName(name);

      checkAttributes(name);

      Preconditions.checkState(
          (type == RuleClassType.ABSTRACT)
              == (configuredTargetFactory == null && configuredTargetFunction == null),
          "Bad combo for %s: %s %s %s",
          name,
          type,
          configuredTargetFactory,
          configuredTargetFunction);
      if (!workspaceOnly && starlark) {
        assertStarlarkRuleClassHasImplementationFunction();
        assertStarlarkRuleClassHasEnvironmentLabel();
      }
      if (type == RuleClassType.PLACEHOLDER) {
        Preconditions.checkNotNull(ruleDefinitionEnvironmentDigest, this.name);
      }

      if (buildSetting != null) {
        Type<?> type = buildSetting.getType();
        Attribute.Builder<?> defaultAttrBuilder =
            attr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, type)
                .nonconfigurable(BUILD_SETTING_DEFAULT_NONCONFIGURABLE)
                .mandatory();
        this.add(defaultAttrBuilder);

        this.add(
            attr(STARLARK_BUILD_SETTING_HELP_ATTR_NAME, Type.STRING)
                .nonconfigurable(BUILD_SETTING_DEFAULT_NONCONFIGURABLE));

        // Build setting rules should opt out of toolchain resolution, since they form part of the
        // configuration.
        this.toolchainResolutionMode(ToolchainResolutionMode.DISABLED);
      }

      if (starlark
          && (type == RuleClassType.NORMAL || type == RuleClassType.TEST)
          && outputsToBindir
          && !starlarkTestable
          && !isAnalysisTest
          && buildSetting == null) {
        if (extendable == null) { // The rule can be extended, use fallback
          extendable = true;
          extendableAllowlist = defaultExtendableAllowlist;
        }
      } else {
        // This kind of rule can't be extended
        if (Boolean.TRUE.equals(extendable) || extendableAllowlist != null) {
          throw new IllegalArgumentException("The rule cannot be extended");
        }
        extendable = false;
      }

      return new RuleClass(
          name,
          callstack,
          key,
          type,
          starlarkParent,
          initializer,
          labelConverterForInitializer,
          starlark,
          starlarkExtensionLabel,
          starlarkDocumentation,
          extendable,
          extendableAllowlist,
          starlarkTestable,
          documented,
          outputsToBindir,
          workspaceOnly,
          dependencyResolutionRule,
          isExecutableStarlark,
          isAnalysisTest,
          hasAnalysisTestTransition,
          allowlistCheckers.build(),
          ignoreLicenses,
          implicitOutputsFunction,
          transitionFactory,
          configuredTargetFactory,
          advertisedProviders.build(),
          configuredTargetFunction,
          optionReferenceFunction,
          ruleDefinitionEnvironmentLabel,
          ruleDefinitionEnvironmentDigest,
          ruleDefinitionEnvironmentRepoMappingEntries,
          configurationFragmentPolicy.build(),
          supportsConstraintChecking,
          toolchainTypes,
          toolchainResolutionMode,
          executionPlatformConstraints,
          execGroups,
          autoExecGroupsMode,
          outputFileKind,
          ImmutableList.copyOf(attributes.values()),
          buildSetting,
          subrules);
    }

    private void checkAttributes(String ruleClassName) {
      Preconditions.checkArgument(
          attributes.size() <= MAX_ATTRIBUTES,
          "Rule class %s declared too many attributes (%s > %s)",
          ruleClassName,
          attributes.size(),
          MAX_ATTRIBUTES);

      ImmutableList.Builder<String> attributesNotForDependencyResolutionBuilder =
          ImmutableList.builder();

      for (var entry : attributes.entrySet()) {
        String attributeName = entry.getKey();
        Attribute attribute = entry.getValue();

        int attributeNameLength =
            StarlarkSubruleApi.getUserDefinedNameIfSubruleAttr(subrules, attributeName)
                .map(String::length)
                .orElse(attributeName.length());

        // TODO(b/151171037): This check would make more sense at Attribute creation time, but the
        // use of unchecked exceptions in these APIs makes it brittle.
        Preconditions.checkArgument(
            attributeNameLength <= MAX_ATTRIBUTE_NAME_LENGTH,
            "Attribute %s.%s's name is too long (%s > %s)",
            ruleClassName,
            attributeName,
            attributeNameLength,
            MAX_ATTRIBUTE_NAME_LENGTH);

        if (dependencyResolutionRule) {
          if (attribute.getType().getLabelClass() == LabelClass.DEPENDENCY
              && !attribute.isForDependencyResolution()) {
            attributesNotForDependencyResolutionBuilder.add(attributeName);
          }
        }
      }

      ImmutableList<String> attributesNotForDependencyResolution =
          attributesNotForDependencyResolutionBuilder.build();
      if (!attributesNotForDependencyResolution.isEmpty()) {
        throw new IllegalStateException(
            "Rule is available for dependency resolution but some dependency attributes aren't: "
                + Joiner.on(", ").join(attributesNotForDependencyResolution));
      }

      type.checkAttributes(attributes);
    }

    private void assertStarlarkRuleClassHasImplementationFunction() {
      Preconditions.checkState(
          (type == RuleClassType.NORMAL || type == RuleClassType.TEST)
              == (configuredTargetFunction != null),
          "%s %s",
          type,
          configuredTargetFunction);
    }

    private void assertStarlarkRuleClassHasEnvironmentLabel() {
      Preconditions.checkState(
          (type == RuleClassType.NORMAL
                  || type == RuleClassType.TEST
                  || type == RuleClassType.PLACEHOLDER)
              == (ruleDefinitionEnvironmentLabel != null),
          "Concrete Starlark rule classes can't have null labels: %s %s",
          ruleDefinitionEnvironmentLabel,
          type);
    }

    @CanIgnoreReturnValue
    public Builder initializer(
        StarlarkFunction initializer, LabelConverter labelConverterForInitializer) {
      this.initializer = initializer;
      this.labelConverterForInitializer = labelConverterForInitializer;
      return this;
    }

    public void setExtendableByAllowlist(Label extendableAllowlist) {
      this.extendable = true;
      this.extendableAllowlist = extendableAllowlist;
    }

    /** Set the rule extendable or not, without an allowlist. */
    public void setExtendable(boolean extendable) {
      this.extendable = extendable;
      this.extendableAllowlist = null;
    }

    /**
     * Sets the default allowlist, which is used as a fallback, when user doesn't set extendable or
     * extendable by allowlist
     */
    public void setDefaultExtendableAllowlist(Label extendableAllowlist) {
      this.defaultExtendableAllowlist = extendableAllowlist;
    }

    /**
     * Declares that the implementation of the associated rule class requires the given fragments to
     * be present.
     *
     * <p>The value is inherited by subclasses.
     */
    @CanIgnoreReturnValue
    public Builder requiresConfigurationFragments(
        Class<? extends Fragment>... configurationFragments) {
      configurationFragmentPolicy.requiresConfigurationFragments(
          ImmutableSet.copyOf(configurationFragments));
      return this;
    }

    /**
     * Declares the configuration fragments that are required by this rule for the target
     * configuration.
     *
     * <p>In contrast to {@link #requiresConfigurationFragments(Class...)}, this method takes the
     * Starlark module names of fragments instead of their classes.
     */
    @CanIgnoreReturnValue
    public Builder requiresConfigurationFragmentsByStarlarkModuleName(
        Collection<String> configurationFragmentNames) {
      configurationFragmentPolicy.requiresConfigurationFragmentsByStarlarkBuiltinName(
          configurationFragmentNames);
      return this;
    }

    /** Sets the Starlark call stack associated with this rule class's creation. */
    @CanIgnoreReturnValue
    public Builder setCallStack(ImmutableList<StarlarkThread.CallStackEntry> callstack) {
      this.callstack = callstack;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setStarlarkTestable() {
      Preconditions.checkState(starlark, "Cannot set starlarkTestable on a non-Starlark rule");
      starlarkTestable = true;
      return this;
    }

    /**
     * Sets the policy for the case where the configuration is missing required fragment class (see
     * {@link #requiresConfigurationFragments}).
     */
    @CanIgnoreReturnValue
    public Builder setMissingFragmentPolicy(
        Class<?> fragmentClass, MissingFragmentPolicy missingFragmentPolicy) {
      configurationFragmentPolicy.setMissingFragmentPolicy(fragmentClass, missingFragmentPolicy);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setUndocumented() {
      documented = false;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setWorkspaceOnly() {
      workspaceOnly = true;
      return this;
    }

    /**
     * Determines the outputs of this rule to be created beneath the {@code genfiles} directory. By
     * default, files are created beneath the {@code bin} directory.
     *
     * <p>This property is not inherited and this method should not be called by builder of {@link
     * RuleClassType#ABSTRACT} rule class.
     *
     * @throws IllegalStateException if called for abstract rule class builder
     */
    @CanIgnoreReturnValue
    public Builder setOutputToGenfiles() {
      Preconditions.checkState(
          type != RuleClassType.ABSTRACT,
          "Setting not inherited property (output to genrules) of abstract rule class '%s'",
          name);
      this.outputsToBindir = false;
      return this;
    }

    /**
     * Sets the implicit outputs function of the rule class. The default implicit outputs function
     * is {@link SafeImplicitOutputsFunction#NONE}.
     *
     * <p>This property is not inherited and this method should not be called by builder of {@link
     * RuleClassType#ABSTRACT} rule class.
     *
     * @throws IllegalStateException if called for abstract rule class builder
     */
    @CanIgnoreReturnValue
    public Builder setImplicitOutputsFunction(ImplicitOutputsFunction implicitOutputsFunction) {
      Preconditions.checkState(
          type != RuleClassType.ABSTRACT,
          "Setting not inherited property (implicit output function) of abstract rule class '%s'",
          name);
      this.implicitOutputsFunction = implicitOutputsFunction;
      return this;
    }

    /** Applies the given transition factory to all incoming edges for this rule class. */
    @CanIgnoreReturnValue
    public Builder cfg(TransitionFactory<RuleTransitionData> transitionFactory) {
      Preconditions.checkState(
          type != RuleClassType.ABSTRACT,
          "Setting not inherited property (cfg) of abstract rule class '%s'",
          name);
      Preconditions.checkState(
          NoTransition.isInstance(this.transitionFactory), "Property cfg has already been set");
      Preconditions.checkNotNull(transitionFactory);
      Preconditions.checkArgument(
          transitionFactory.transitionType().isCompatibleWith(TransitionType.RULE));
      Preconditions.checkArgument(!transitionFactory.isSplit());
      this.transitionFactory = transitionFactory;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder factory(ConfiguredTargetFactory<?, ?, ?> factory) {
      this.configuredTargetFactory = factory;
      return this;
    }

    /**
     * State that the rule class being built always supplies the specified provider.
     *
     * <p>When computing the set of aspects required for a rule, only the providers listed here are
     * considered. The presence of a provider here means that the rule <b>must</b> implement said
     * provider.
     *
     * <p>This is here so that we can do the loading phase overestimation required for "blaze
     * query", which does not have the configured targets available.
     */
    @CanIgnoreReturnValue
    public Builder advertiseProvider(Class<?>... providers) {
      for (Class<?> provider : providers) {
        advertisedProviders.addBuiltin(provider);
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder advertiseStarlarkProvider(StarlarkProviderIdentifier... starlarkProviders) {
      for (StarlarkProviderIdentifier starlarkProviderIdentifier : starlarkProviders) {
        advertisedProviders.addStarlark(starlarkProviderIdentifier);
      }
      return this;
    }

    /**
     * Set if the rule can have any provider. This is called for the {@code alias} rule and other
     * alias-like rules such as {@code bind}.
     */
    @CanIgnoreReturnValue
    public Builder canHaveAnyProvider() {
      advertisedProviders.canHaveAnyProvider();
      return this;
    }

    /**
     * Adds an attribute to the builder.
     *
     * <p>Throws an IllegalStateException if an attribute of that name already exists.
     *
     * <p>TODO(bazel-team): stop using unchecked exceptions in this way.
     */
    @CanIgnoreReturnValue
    public Builder addAttribute(Attribute attribute) {
      Attribute prevVal = attributes.putIfAbsent(attribute.getName(), attribute);
      if (prevVal != null) {
        throw new IllegalStateException(
            String.format(
                "There is already a built-in attribute '%s' which cannot be overridden.",
                attribute.getName()));
      }
      return this;
    }

    private void overrideAttribute(Attribute attribute) {
      String attrName = attribute.getName();
      Preconditions.checkState(
          attributes.containsKey(attrName),
          "No such attribute '%s' to override in ruleclass '%s'.",
          attrName,
          name);
      Type<?> origType = attributes.get(attrName).getType();
      Type<?> newType = attribute.getType();
      Preconditions.checkState(
          origType.equals(newType),
          "The type of the new attribute '%s' is different from the original one '%s'.",
          newType,
          origType);
      attributes.put(attrName, attribute);
    }

    /**
     * Builds provided attribute and attaches it to this rule class.
     *
     * <p>Typically rule classes should only declare a handful of attributes - this expectation is
     * enforced when the instance is built.
     *
     * <p>Attribute names should be meaningful but short; overly long names are rejected at
     * instantiation.
     */
    @CanIgnoreReturnValue
    public <TYPE> Builder add(Attribute.Builder<TYPE> attr) {
      addAttribute(attr.build());
      return this;
    }

    @FormatMethod
    private static void failIf(boolean condition, String message, Object... args)
        throws EvalException {
      if (condition) {
        throw Starlark.errorf(message, args);
      }
    }

    /**
     * Overrides the attribute with the same name. This method does additional checks required for
     * overriding attributes in Starlark
     */
    @CanIgnoreReturnValue
    public Builder override(Attribute attr) throws EvalException {
      Attribute parentAttr = attributes.get(attr.getName());
      failIf(
          !parentAttr.starlarkDefined(),
          "attribute `%s`: built-in attributes cannot be overridden.",
          parentAttr.getPublicName());
      failIf(
          !parentAttr.isPublic(),
          "attribute `%s`: private attributes cannot be overridden.",
          parentAttr.getPublicName());
      failIf(
          parentAttr.getType() != BuildType.LABEL_LIST && parentAttr.getType() != BuildType.LABEL,
          "attribute `%s`: Only label types maybe be overridden.",
          parentAttr.getPublicName());
      failIf(
          parentAttr.getType() != attr.getType(),
          "attribute `%s`: Types of parent and child's attributes mismatch.",
          parentAttr.getPublicName());
      attr.failIfNotAValidOverride();

      Attribute.Builder<?> attrBuilder = copy(attr.getName());
      if (attr.getDefaultValueUnchecked() != null) {
        attrBuilder.defaultValue(attr.getDefaultValueUnchecked());
      }
      attrBuilder.addAspects(attr.getAspectsList());
      override(attrBuilder);
      return this;
    }

    /**
     * Builds attribute from the attribute builder and overrides the attribute with the same name.
     *
     * @throws IllegalArgumentException if the attribute does not override one of the same name
     */
    @CanIgnoreReturnValue
    public <TYPE> Builder override(Attribute.Builder<TYPE> attr) {
      overrideAttribute(attr.build());
      return this;
    }

    /** True if the rule class contains an attribute named {@code name}. */
    public boolean contains(String name) {
      return attributes.containsKey(name);
    }

    public Attribute getAttribute(String name) {
      return attributes.get(name);
    }

    /** Returns a list of all attributes added to this Builder so far. */
    public ImmutableList<Attribute> getAttributes() {
      return ImmutableList.copyOf(attributes.values());
    }

    /** Sets the rule implementation function. Meant for Starlark usage. */
    @CanIgnoreReturnValue
    public Builder setConfiguredTargetFunction(StarlarkCallable func) {
      this.configuredTargetFunction = func;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setBuildSetting(BuildSetting buildSetting) {
      this.buildSetting = buildSetting;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSubrules(ImmutableList<? extends StarlarkSubruleApi> subrules) {
      this.subrules = subrules;
      return this;
    }

    public ImmutableList<? extends StarlarkSubruleApi> getSubrules() {
      return subrules;
    }

    public ImmutableSet<? extends StarlarkSubruleApi> getParentSubrules() {
      ImmutableSet.Builder<StarlarkSubruleApi> builder = ImmutableSet.builder();
      RuleClass currentParent = starlarkParent;
      while (currentParent != null) {
        builder.addAll(starlarkParent.getSubrules());
        currentParent = currentParent.starlarkParent;
      }
      return builder.build();
    }

    /**
     * Sets the rule definition environment label and transitive digest. Meant for Starlark usage.
     */
    @CanIgnoreReturnValue
    public Builder setRuleDefinitionEnvironmentLabelAndDigest(Label label, byte[] digest) {
      this.ruleDefinitionEnvironmentLabel = Preconditions.checkNotNull(label, this.name);
      this.ruleDefinitionEnvironmentDigest = Preconditions.checkNotNull(digest, this.name);
      return this;
    }

    /**
     * Sets the Starlark documentation string, if one was provided, for a Starlark-defined rule
     * class. Cannot be set for a non-Starlark-defined rule class.
     */
    @CanIgnoreReturnValue
    public Builder setStarlarkDocumentation(String starlarkDocumentation) {
      Preconditions.checkState(starlark, this.name);
      this.starlarkDocumentation = Preconditions.checkNotNull(starlarkDocumentation, this.name);
      return this;
    }

    /**
     * Returns the Starlark documentation string, if one was provided, for a Starlark-defined rule
     * class.
     */
    @Nullable
    public String getStarlarkDocumentation() {
      return this.starlarkDocumentation;
    }

    public Label getRuleDefinitionEnvironmentLabel() {
      return this.ruleDefinitionEnvironmentLabel;
    }

    @CanIgnoreReturnValue
    public Builder setRuleDefinitionEnvironmentRepoMappingEntries(
        ImmutableTable<RepositoryName, String, RepositoryName> recordedRepoMappingEntries) {
      this.ruleDefinitionEnvironmentRepoMappingEntries = recordedRepoMappingEntries;
      return this;
    }

    /**
     * Removes an attribute with the same name from this rule class.
     *
     * @throws IllegalArgumentException if the attribute with this name does not exist
     */
    @CanIgnoreReturnValue
    public Builder removeAttribute(String name) {
      Preconditions.checkState(
          attributes.containsKey(name), "No such attribute '%s' to remove.", name);
      attributes.remove(name);
      return this;
    }

    /**
     * Mark the rule as "for dependency resolution". Rules so marked can only depend on other rules
     * also marked as such.
     */
    @CanIgnoreReturnValue
    public Builder setDependencyResolutionRule() {
      this.dependencyResolutionRule = true;
      return this;
    }

    /**
     * This rule class outputs a default executable for every rule with the same name as the
     * rules's. Only works for Starlark.
     */
    @CanIgnoreReturnValue
    public Builder setExecutableStarlark() {
      this.isExecutableStarlark = true;
      return this;
    }

    /** This rule class is marked as an analysis test. */
    @CanIgnoreReturnValue
    public Builder setIsAnalysisTest() {
      this.isAnalysisTest = true;
      return this;
    }

    public boolean isAnalysisTest() {
      return this.isAnalysisTest;
    }

    /**
     * This rule class has at least one attribute with an analysis test transition. (A
     * starlark-defined transition using analysis_test_transition()).
     */
    @CanIgnoreReturnValue
    public Builder setHasAnalysisTestTransition() {
      this.hasAnalysisTestTransition = true;
      return this;
    }

    /** Add an allowlistChecker to be checked as part of the rule implementation. */
    @CanIgnoreReturnValue
    public Builder addAllowlistChecker(AllowlistChecker allowlistChecker) {
      this.allowlistCheckers.add(allowlistChecker);
      return this;
    }

    /**
     * This rule class never declares a license regardless of what the rule's or package's <code>
     * licenses</code> attribute says.
     */
    // TODO(b/130286108): remove the licenses attribute completely from such rules.
    @CanIgnoreReturnValue
    public Builder setIgnoreLicenses() {
      this.ignoreLicenses = true;
      return this;
    }

    public RuleClassType getType() {
      return this.type;
    }

    /**
     * Sets the kind of output files this rule creates. DO NOT USE! This only exists to support the
     * non-open-sourced {@code fileset} rule. {@see OutputFile.Kind}.
     */
    @CanIgnoreReturnValue
    public Builder setOutputFileKind(OutputFile.Kind outputFileKind) {
      this.outputFileKind = Preconditions.checkNotNull(outputFileKind);
      return this;
    }

    /**
     * Declares that instances of this rule are compatible with the specified environments, in
     * addition to the defaults declared by their environment groups. This can be overridden by
     * rule-specific declarations. See {@link
     * com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics} for details.
     */
    @CanIgnoreReturnValue
    public Builder compatibleWith(Label... environments) {
      add(
          attr(DEFAULT_COMPATIBLE_ENVIRONMENT_ATTR, LABEL_LIST)
              .value(ImmutableList.copyOf(environments)));
      return this;
    }

    /**
     * Declares that instances of this rule are restricted to the specified environments, i.e. these
     * override the defaults declared by their environment groups. This can be overridden by
     * rule-specific declarations. See {@link
     * com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics} for details.
     *
     * <p>The input list cannot be empty.
     */
    @CanIgnoreReturnValue
    public Builder restrictedTo(Label firstEnvironment, Label... otherEnvironments) {
      ImmutableList<Label> environments =
          ImmutableList.<Label>builder().add(firstEnvironment).add(otherEnvironments).build();
      add(attr(DEFAULT_RESTRICTED_ENVIRONMENT_ATTR, LABEL_LIST).value(environments));
      return this;
    }

    /**
     * Exempts rules of this type from the constraint enforcement system. This should only be
     * applied to rules that are intrinsically incompatible with constraint checking (any
     * application of this method weakens the reach and strength of the system).
     *
     * @param reason user-informative message explaining the reason for exemption (not used)
     */
    @CanIgnoreReturnValue
    public Builder exemptFromConstraintChecking(String reason) {
      Preconditions.checkState(this.supportsConstraintChecking);
      this.supportsConstraintChecking = false;
      attributes.remove(RuleClass.COMPATIBLE_ENVIRONMENT_ATTR);
      attributes.remove(RuleClass.RESTRICTED_ENVIRONMENT_ATTR);
      attributes.remove(RuleClass.TARGET_COMPATIBLE_WITH_ATTR);
      return this;
    }

    /**
     * Causes rules of this type to implicitly reference the configuration fragments associated with
     * the options its attributes reference.
     *
     * <p>This is only intended for use by {@code config_setting} - other rules should not use this!
     */
    @CanIgnoreReturnValue
    public Builder setOptionReferenceFunctionForConfigSettingOnly(
        Function<? super Rule, ? extends Set<String>> optionReferenceFunction) {
      this.optionReferenceFunction = Preconditions.checkNotNull(optionReferenceFunction);
      return this;
    }

    /**
     * Cause rules of this type to request the specified toolchains be available via toolchain
     * resolution when a target is configured.
     */
    @CanIgnoreReturnValue
    public Builder addToolchainTypes(Iterable<ToolchainTypeRequirement> toolchainTypes) {
      Iterables.addAll(this.toolchainTypes, toolchainTypes);
      return this;
    }

    /**
     * Cause rules of this type to request the specified toolchains be available via toolchain
     * resolution when a target is configured.
     */
    @CanIgnoreReturnValue
    public Builder addToolchainTypes(ToolchainTypeRequirement... toolchainTypes) {
      return addToolchainTypes(ImmutableList.copyOf(toolchainTypes));
    }

    /**
     * Adds execution groups to this rule class. Errors out if multiple different groups with the
     * same name are added.
     */
    @CanIgnoreReturnValue
    public Builder addExecGroups(Map<String, ExecGroup> execGroups) {
      for (Map.Entry<String, ExecGroup> group : execGroups.entrySet()) {
        String name = group.getKey();
        if (this.execGroups.containsKey(name)) {
          // If trying to add a new execution group with the same name as a execution group that
          // already exists, check if they are equivalent and error out if not.
          ExecGroup existingGroup = this.execGroups.get(name);
          ExecGroup newGroup = group.getValue();
          if (!existingGroup.equals(newGroup)) {
            throw new DuplicateExecGroupError(name);
          }
        } else {
          this.execGroups.put(name, group.getValue());
        }
      }
      return this;
    }

    /** An error to help report {@link ExecGroup}s with the same name */
    static class DuplicateExecGroupError extends RuntimeException {
      private final String duplicateGroup;

      DuplicateExecGroupError(String duplicateGroup) {
        super(String.format("Multiple execution groups with the same name: '%s'.", duplicateGroup));
        this.duplicateGroup = duplicateGroup;
      }

      String getDuplicateGroup() {
        return duplicateGroup;
      }
    }

    /** Checks whether the rule class has an exec group with the given name. */
    public boolean hasExecGroup(String name) {
      return this.execGroups.containsKey(name);
    }

    /** Sets how this rule class uses auto exec groups. */
    @CanIgnoreReturnValue
    public Builder autoExecGroupsMode(AutoExecGroupsMode autoExecGroupsMode) {
      this.autoExecGroupsMode = autoExecGroupsMode;
      return this;
    }

    /**
     * Causes rules to use toolchain resolution to determine the execution platform and toolchains.
     * Rules that are part of configuring toolchains and platforms should set this to {@code
     * DISABLED}.
     */
    @CanIgnoreReturnValue
    public Builder toolchainResolutionMode(ToolchainResolutionMode mode) {
      this.toolchainResolutionMode = mode;
      return this;
    }

    /**
     * Adds additional execution platform constraints that apply for all targets from this rule.
     *
     * <p>Please note that this value is inherited by child rules.
     */
    public Builder addExecutionPlatformConstraints(Label... constraints) {
      return this.addExecutionPlatformConstraints(Lists.newArrayList(constraints));
    }

    /**
     * Adds additional execution platform constraints that apply for all targets from this rule.
     *
     * <p>Please note that this value is inherited by child rules.
     */
    @CanIgnoreReturnValue
    public Builder addExecutionPlatformConstraints(Iterable<Label> constraints) {
      Iterables.addAll(this.executionPlatformConstraints, constraints);
      return this;
    }

    /**
     * Returns an Attribute.Builder object which contains a replica of the same attribute in the
     * parent rule if exists.
     *
     * @param name the name of the attribute
     */
    public Attribute.Builder<?> copy(String name) {
      Preconditions.checkArgument(
          attributes.containsKey(name), "Attribute %s does not exist in parent rule class.", name);
      return attributes.get(name).cloneBuilder();
    }
  }

  // record containing both the common rule_class 'name' (e.g. "cc_library") as
  // well as the unique 'key' for the rule class. Key has the same value as
  // 'name' for native rules and a combination of label + name for Starlark.
  private final RuleClassId ruleClassId;
  private final ImmutableList<StarlarkThread.CallStackEntry> callstack; // of call to 'rule'

  /**
   * The kind of target represented by this RuleClass (e.g. "cc_library rule"). Note: Even though
   * there is partial duplication with the {@link RuleClass#name} field, we want to store this as a
   * separate field instead of generating it on demand in order to avoid string duplication.
   */
  private final String targetKind;

  private final RuleClassType type;
  @Nullable private final RuleClass starlarkParent;
  @Nullable private final StarlarkFunction initializer;
  @Nullable private final LabelConverter labelConverterForInitializer;
  private final boolean isStarlark;
  private final boolean extendable;
  // The following 2 fields may be non-null only if the rule is Starlark-defined.
  @Nullable private final Label starlarkExtensionLabel;
  @Nullable private final String starlarkDocumentation;
  @Nullable private final Label extendableAllowlist;
  private final boolean starlarkTestable;
  private final boolean documented;
  private final boolean outputsToBindir;
  private final boolean workspaceOnly;
  private final boolean dependencyResolutionRule;
  private final boolean isExecutableStarlark;
  private final boolean isAnalysisTest;
  private final boolean hasAnalysisTestTransition;
  private final ImmutableList<AllowlistChecker> allowlistCheckers;
  private final boolean ignoreLicenses;
  private final boolean hasAspects;

  /**
   * A (unordered) mapping from attribute names to small integers indexing into the {@code
   * attributes} array.
   */
  private final Map<String, Integer> attributeIndex;

  /**
   * All attributes of this rule class (including inherited ones) ordered by attributeIndex value.
   */
  private final ImmutableList<Attribute> attributes;

  /** Names of the non-configurable attributes of this rule class. */
  private final ImmutableList<String> nonConfigurableAttributes;

  /** The set of implicit outputs generated by a rule, expressed as a function of that rule. */
  private final ImplicitOutputsFunction implicitOutputsFunction;

  /**
   * A factory which will produce a configuration transition that should be applied on any edge of
   * the configured target graph that leads into a target of this rule class.
   */
  private final TransitionFactory<RuleTransitionData> transitionFactory;

  /** The factory that creates configured targets from this rule. */
  private final ConfiguredTargetFactory<?, ?, ?> configuredTargetFactory;

  /** The list of transitive info providers this class advertises to aspects. */
  private final AdvertisedProviderSet advertisedProviders;

  /**
   * The Starlark rule implementation of this RuleClass. Null for non Starlark executable
   * RuleClasses.
   */
  @Nullable private final StarlarkCallable configuredTargetFunction;

  /**
   * The BuildSetting associated with this rule. Null for all RuleClasses except Starlark-defined
   * rules that pass {@code build_setting} to their {@code rule()} declaration.
   */
  @Nullable private final BuildSetting buildSetting;

  /**
   * The subrules associated with this rule. Empty for all rule classes except Starlark-defined
   * rules that explicitly pass {@code subrules = [...]} to their {@code rule()} declaration
   */
  private final ImmutableSet<? extends StarlarkSubruleApi> subrules;

  /** Returns the options referenced by this rule's attributes. */
  private final Function<? super Rule, ? extends Set<String>> optionReferenceFunction;

  /**
   * The Starlark rule definition environment's label and hash code of this RuleClass. Null for non
   * Starlark executable RuleClasses.
   */
  @Nullable private final Label ruleDefinitionEnvironmentLabel;

  @Nullable private final byte[] ruleDefinitionEnvironmentDigest;

  @Nullable
  private final ImmutableTable<RepositoryName, String, RepositoryName>
      ruleDefinitionEnvironmentRepoMappingEntries;

  private final OutputFile.Kind outputFileKind;

  /**
   * The set of configuration fragments which are legal for this rule's implementation to access.
   */
  private final ConfigurationFragmentPolicy configurationFragmentPolicy;

  /**
   * Determines whether instances of this rule should be checked for constraint compatibility with
   * their dependencies and the rules that depend on them. This should be true for everything except
   * for rules that are intrinsically incompatible with the constraint system.
   */
  private final boolean supportsConstraintChecking;

  private final ImmutableSet<ToolchainTypeRequirement> toolchainTypes;
  private final ToolchainResolutionMode toolchainResolutionMode;
  private final ImmutableSet<Label> executionPlatformConstraints;
  private final ImmutableMap<String, ExecGroup> execGroups;
  private final AutoExecGroupsMode autoExecGroupsMode;

  /**
   * Constructs an instance of RuleClass whose name is 'name', attributes are 'attributes'. The
   * {@code srcsAllowedFiles} determines which types of files are allowed as parameters to the
   * "srcs" attribute; rules are always allowed. For the "deps" attribute, there are four cases:
   *
   * <ul>
   *   <li>if the parameter is a file, it is allowed if its file type is given in {@code
   *       depsAllowedFiles},
   *   <li>if the parameter is a rule and the rule class is accepted by {@code depsAllowedRules},
   *       then it is allowed,
   *   <li>if the parameter is a rule and the rule class is not accepted by {@code
   *       depsAllowedRules}, but accepted by {@code depsAllowedRulesWithWarning}, then it is
   *       allowed, but triggers a warning;
   *   <li>all other parameters trigger an error.
   * </ul>
   *
   * <p>The {@code depsAllowedRules} predicate should have a {@code toString} method which returns a
   * plain English enumeration of the allowed rule class names, if it does not allow all rule
   * classes.
   */
  @VisibleForTesting
  RuleClass(
      String name,
      ImmutableList<StarlarkThread.CallStackEntry> callstack,
      String key,
      RuleClassType type,
      RuleClass starlarkParent,
      @Nullable StarlarkFunction initializer,
      @Nullable LabelConverter labelConverterForInitializer,
      boolean isStarlark,
      @Nullable Label starlarkExtensionLabel,
      @Nullable String starlarkDocumentation,
      boolean extendable,
      @Nullable Label extendableAllowlist,
      boolean starlarkTestable,
      boolean documented,
      boolean outputsToBindir,
      boolean workspaceOnly,
      boolean dependencyResolutionRule,
      boolean isExecutableStarlark,
      boolean isAnalysisTest,
      boolean hasAnalysisTestTransition,
      ImmutableList<AllowlistChecker> allowlistCheckers,
      boolean ignoreLicenses,
      ImplicitOutputsFunction implicitOutputsFunction,
      TransitionFactory<RuleTransitionData> transitionFactory,
      ConfiguredTargetFactory<?, ?, ?> configuredTargetFactory,
      AdvertisedProviderSet advertisedProviders,
      @Nullable StarlarkCallable configuredTargetFunction,
      Function<? super Rule, ? extends Set<String>> optionReferenceFunction,
      @Nullable Label ruleDefinitionEnvironmentLabel,
      @Nullable byte[] ruleDefinitionEnvironmentDigest,
      @Nullable
          ImmutableTable<RepositoryName, String, RepositoryName>
              ruleDefinitionEnvironmentRepoMappingEntries,
      ConfigurationFragmentPolicy configurationFragmentPolicy,
      boolean supportsConstraintChecking,
      Set<ToolchainTypeRequirement> toolchainTypes,
      ToolchainResolutionMode toolchainResolutionMode,
      Set<Label> executionPlatformConstraints,
      Map<String, ExecGroup> execGroups,
      AutoExecGroupsMode autoExecGroupsMode,
      OutputFile.Kind outputFileKind,
      ImmutableList<Attribute> attributes,
      @Nullable BuildSetting buildSetting,
      ImmutableList<? extends StarlarkSubruleApi> subrules) {
    this.ruleClassId = RuleClassId.create(name, key);
    this.callstack = callstack;
    this.type = type;
    this.starlarkParent = starlarkParent;
    this.initializer = initializer;
    this.labelConverterForInitializer = labelConverterForInitializer;
    this.isStarlark = isStarlark;
    this.starlarkExtensionLabel = starlarkExtensionLabel;
    this.starlarkDocumentation = starlarkDocumentation;
    this.extendable = extendable;
    this.extendableAllowlist = extendableAllowlist;
    this.targetKind = name + Rule.targetKindSuffix();
    this.starlarkTestable = starlarkTestable;
    this.documented = documented;
    this.outputsToBindir = outputsToBindir;
    this.implicitOutputsFunction = implicitOutputsFunction;
    this.transitionFactory = transitionFactory;
    this.configuredTargetFactory = configuredTargetFactory;
    this.advertisedProviders = advertisedProviders;
    this.configuredTargetFunction = configuredTargetFunction;
    this.optionReferenceFunction = optionReferenceFunction;
    this.ruleDefinitionEnvironmentLabel = ruleDefinitionEnvironmentLabel;
    this.ruleDefinitionEnvironmentDigest = ruleDefinitionEnvironmentDigest;
    this.ruleDefinitionEnvironmentRepoMappingEntries = ruleDefinitionEnvironmentRepoMappingEntries;
    this.outputFileKind = outputFileKind;
    this.attributes = attributes;
    this.workspaceOnly = workspaceOnly;
    this.dependencyResolutionRule = dependencyResolutionRule;
    this.isExecutableStarlark = isExecutableStarlark;
    this.isAnalysisTest = isAnalysisTest;
    this.hasAnalysisTestTransition = hasAnalysisTestTransition;
    this.allowlistCheckers = allowlistCheckers;
    this.ignoreLicenses = ignoreLicenses;
    this.configurationFragmentPolicy = configurationFragmentPolicy;
    this.supportsConstraintChecking = supportsConstraintChecking;
    this.toolchainTypes = ImmutableSet.copyOf(toolchainTypes);
    this.toolchainResolutionMode = toolchainResolutionMode;
    this.executionPlatformConstraints = ImmutableSet.copyOf(executionPlatformConstraints);
    this.execGroups = ImmutableMap.copyOf(execGroups);
    this.autoExecGroupsMode = autoExecGroupsMode;
    this.buildSetting = buildSetting;
    this.subrules = ImmutableSet.copyOf(subrules);
    // Create the index and collect non-configurable attributes while doing some validation checks.
    Preconditions.checkState(
        !attributes.isEmpty() && attributes.get(0).equals(NAME_ATTRIBUTE),
        "Rule %s does not have name as its first attribute: %s",
        name,
        attributes);
    attributeIndex = Maps.newHashMapWithExpectedSize(attributes.size());
    Map<String, Attribute> publicToPrivateNames =
        Maps.newHashMapWithExpectedSize(attributes.size());
    boolean computedHasAspects = false;
    ImmutableList.Builder<String> nonConfigurableAttributes = ImmutableList.builder();
    for (int i = 0; i < attributes.size(); i++) {
      Attribute attribute = attributes.get(i);
      String publicName = attribute.getPublicName();
      Attribute conflicting = publicToPrivateNames.put(publicName, attribute);
      if (conflicting != null) {
        throw new IllegalStateException(
            String.format(
                "Rule %s: Attributes %s and %s have an identical public name: %s",
                name, attribute.getName(), conflicting.getName(), publicName));
      }
      computedHasAspects |= attribute.hasAspects();
      attributeIndex.put(attribute.getName(), i);
      if (!attribute.isConfigurable()) {
        nonConfigurableAttributes.add(attribute.getName());
      }
    }

    this.hasAspects = computedHasAspects;
    this.nonConfigurableAttributes = nonConfigurableAttributes.build();
  }

  /**
   * Returns the default function for determining the set of implicit outputs generated by a given
   * rule. If not otherwise specified, this will be the implementation used by {@link Rule}s created
   * with this {@link RuleClass}.
   *
   * <p>An implicit output is an OutputFile that automatically comes into existence when a rule of
   * this class is declared, and whose name is derived from the name of the rule.
   *
   * <p>Implicit outputs are a widely-relied upon. All ".so", and "_deploy.jar" targets referenced
   * in BUILD files are examples.
   */
  // (public for serialization)
  public ImplicitOutputsFunction getDefaultImplicitOutputsFunction() {
    return implicitOutputsFunction;
  }

  public TransitionFactory<RuleTransitionData> getTransitionFactory() {
    return transitionFactory;
  }

  public <T extends ConfiguredTargetFactory<?, ?, ?>> T getConfiguredTargetFactory(Class<T> clazz) {
    return clazz.cast(configuredTargetFactory);
  }

  /** Returns the class of rule that this RuleClass represents (e.g. "cc_library"). */
  @Override
  public String getName() {
    return ruleClassId.name();
  }

  public RuleClass getStarlarkParent() {
    return this.starlarkParent;
  }

  @Nullable
  public StarlarkFunction getInitializer() {
    return initializer;
  }

  @Nullable
  public LabelConverter getLabelConverterForInitializer() {
    return labelConverterForInitializer;
  }

  /**
   * Returns the stack of Starlark active function calls at the moment this rule class was created.
   * Entries appear outermost first, and exclude the built-in itself ('rule' or 'repository_rule').
   * Empty for non-Starlark rules.
   */
  public ImmutableList<StarlarkThread.CallStackEntry> getCallStack() {
    return callstack;
  }

  /** Returns the type of rule that this RuleClass represents. Only for use during serialization. */
  public RuleClassType getRuleClassType() {
    return type;
  }

  /** Returns a unique key. Used for profiling purposes. */
  public String getKey() {
    return ruleClassId.key();
  }

  /** Returns the record containing both the name and key. */
  public RuleClassId getRuleClassId() {
    return this.ruleClassId;
  }

  /** Returns the target kind of this class of rule (e.g. "cc_library rule"). */
  @Override
  public String getTargetKind() {
    return targetKind;
  }

  public boolean getWorkspaceOnly() {
    return workspaceOnly;
  }

  /**
   * Returns true iff the attribute 'attrName' is defined for this rule class, and has type 'type'.
   */
  public boolean hasAttr(String attrName, Type<?> type) {
    Integer index = getAttributeIndex(attrName);
    return index != null && getAttribute(index).getType() == type;
  }

  /**
   * Returns the index of the specified attribute name. Use of indices allows space-efficient
   * storage of attribute values in rules, since hashtables are not required. (The index mapping is
   * specific to each RuleClass and an attribute may have a different index in the parent
   * RuleClass.)
   *
   * <p>Returns null if the named attribute is not defined for this class of Rule.
   */
  Integer getAttributeIndex(String attrName) {
    return attributeIndex.get(attrName);
  }

  /** Returns the attribute whose index is 'attrIndex'. Fails if attrIndex is not in range. */
  Attribute getAttribute(int attrIndex) {
    return attributes.get(attrIndex);
  }

  /**
   * Returns the attribute whose name is 'attrName'; fails with NullPointerException if not found.
   */
  public Attribute getAttributeByName(String attrName) {
    Integer attrIndex =
        Preconditions.checkNotNull(
            getAttributeIndex(attrName), "Attribute %s does not exist", attrName);
    return attributes.get(attrIndex);
  }

  /** Returns the attribute whose name is {@code attrName}, or null if not found. */
  @Nullable
  public Attribute getAttributeByNameMaybe(String attrName) {
    Integer i = getAttributeIndex(attrName);
    return i == null ? null : attributes.get(i);
  }

  /** Returns the number of attributes defined for this rule class. */
  int getAttributeCount() {
    return attributeIndex.size();
  }

  /**
   * Returns an (immutable) list of all Attributes defined for this class of rule, ordered by
   * increasing index.
   */
  public ImmutableList<Attribute> getAttributes() {
    return attributes;
  }

  /** Returns set of non-configurable attribute names defined for this class of rule. */
  List<String> getNonConfigurableAttributes() {
    return nonConfigurableAttributes;
  }

  /**
   * Returns the set of advertised transitive info providers.
   *
   * <p>When computing the set of aspects required for a rule, only the providers listed here are
   * considered. The presence of a provider here does not mean that the rule <b>must</b> implement
   * said provider, merely that it <b>can</b>. After the configured target is constructed from this
   * rule, aspects will be filtered according to the set of actual providers.
   *
   * <p>This is here so that we can do the loading phase overestimation required for "blaze query",
   * which does not have the configured targets available.
   */
  @Override
  public AdvertisedProviderSet getAdvertisedProviders() {
    return advertisedProviders;
  }

  /** Returns this rule's policy for configuration fragment access. */
  public ConfigurationFragmentPolicy getConfigurationFragmentPolicy() {
    return configurationFragmentPolicy;
  }

  /** Returns true if rules of this type can be used with the constraint enforcement system. */
  public boolean supportsConstraintChecking() {
    return supportsConstraintChecking;
  }

  boolean hasAspects() {
    return hasAspects;
  }

  /**
   * Creates a new {@link Rule} {@code r} where {@code r.getPackage()} is the {@link Package}
   * associated with {@code pkgBuilder}.
   *
   * <p>The created {@link Rule} will be populated with attribute values from {@code
   * attributeValues} or the default attribute values associated with this {@link RuleClass} and
   * {@code pkgBuilder}.
   *
   * <p>The created {@link Rule} will also be populated with output files. These output files will
   * have been collected from the explicitly provided values of type {@link BuildType#OUTPUT} and
   * {@link BuildType#OUTPUT_LIST} as well as from the implicit outputs determined by this {@link
   * RuleClass} and the values in {@code attributeValues}.
   *
   * <p>This performs several validity checks. Invalid output file labels result in a thrown {@link
   * LabelSyntaxException}. Computed default attributes that fail during precomputation result in a
   * {@link CannotPrecomputeDefaultsException}. All other errors are reported on {@code
   * eventHandler}.
   */
  <T> Rule createRule(
      Package.Builder pkgBuilder,
      Label ruleLabel,
      AttributeValues<T> attributeValues,
      boolean failOnUnknownAttributes,
      List<StarlarkThread.CallStackEntry> callstack)
      throws LabelSyntaxException, InterruptedException, CannotPrecomputeDefaultsException {
    EventHandler eventHandler = pkgBuilder.getLocalEventHandler();

    Rule rule = pkgBuilder.createRule(ruleLabel, this, callstack);
    populateRuleAttributeValues(rule, pkgBuilder, attributeValues, failOnUnknownAttributes);
    checkAspectAllowedValues(rule, eventHandler);
    rule.populateOutputFiles(eventHandler, pkgBuilder.getPackageIdentifier());
    checkForDuplicateLabels(rule, eventHandler);

    checkForValidSizeAndTimeoutValues(rule, eventHandler);
    return rule;
  }

  /**
   * Same as {@link #createRule}, except without some internal checks.
   *
   * <p>Don't call this function unless you know what you're doing.
   */
  <T> Rule createRuleUnchecked(
      Package.Builder pkgBuilder,
      Label ruleLabel,
      AttributeValues<T> attributeValues,
      CallStack.Node callstack,
      ImplicitOutputsFunction implicitOutputsFunction)
      throws InterruptedException, CannotPrecomputeDefaultsException {
    Rule rule = pkgBuilder.createRule(ruleLabel, this, callstack.toLocation(), callstack.next());
    populateRuleAttributeValues(rule, pkgBuilder, attributeValues, true);
    rule.populateOutputFilesUnchecked(pkgBuilder, implicitOutputsFunction);
    return rule;
  }

  /**
   * Populates the attributes table of the new {@link Rule} with the values in the {@code
   * attributeValues} map and with default values provided by this {@link RuleClass} and the {@code
   * pkgBuilder}.
   *
   * <p>Errors are reported on {@code eventHandler}.
   */
  private <T> void populateRuleAttributeValues(
      Rule rule,
      Package.Builder pkgBuilder,
      AttributeValues<T> attributeValues,
      boolean failOnUnknownAttributes)
      throws InterruptedException, CannotPrecomputeDefaultsException {

    BitSet definedAttrIndices =
        populateDefinedRuleAttributeValues(
            rule,
            pkgBuilder.getLabelConverter(),
            attributeValues,
            failOnUnknownAttributes,
            pkgBuilder.getListInterner(),
            pkgBuilder.getLocalEventHandler(),
            pkgBuilder.simplifyUnconditionalSelectsInRuleAttrs());
    populateDefaultRuleAttributeValues(rule, pkgBuilder, definedAttrIndices);
    // Now that all attributes are bound to values, collect and store configurable attribute keys.
    populateConfigDependenciesAttribute(rule);
  }

  /**
   * Populates the attributes table of the new {@link Rule} with the values in the {@code
   * attributeValues} map.
   *
   * <p>Handles the special cases of the attribute named {@code "name"} and attributes with value
   * {@link Starlark#NONE}.
   *
   * <p>Returns a bitset {@code b} where {@code b.get(i)} is {@code true} if this method set a value
   * for the attribute with index {@code i} in this {@link RuleClass}. Errors are reported on {@code
   * eventHandler}.
   */
  private <T> BitSet populateDefinedRuleAttributeValues(
      Rule rule,
      LabelConverter labelConverter,
      AttributeValues<T> attributeValues,
      boolean failOnUnknownAttributes,
      Interner<ImmutableList<?>> listInterner,
      EventHandler eventHandler,
      boolean simplifyUnconditionalSelects) {
    BitSet definedAttrIndices = new BitSet();
    for (T attributeAccessor : attributeValues.getAttributeAccessors()) {
      String attributeName = attributeValues.getName(attributeAccessor);
      Object attributeValue = attributeValues.getValue(attributeAccessor);
      // Ignore all None values.
      if (attributeValue == Starlark.NONE && !failOnUnknownAttributes) {
        continue;
      }

      // If the user sets "applicable_liceneses", change it to the correct name.
      // TODO(aiuto): In the time frame of Bazel 9, remove this alternate spelling.
      if (attributeName.equals(APPLICABLE_METADATA_ATTR_ALT)) {
        attributeName = APPLICABLE_METADATA_ATTR;
      }

      // Check that the attribute's name belongs to a valid attribute for this rule class.
      Integer attrIndex = getAttributeIndex(attributeName);
      if (attrIndex == null) {
        rule.reportError(
            String.format(
                "%s: no such attribute '%s' in '%s' rule%s",
                rule.getLabel(),
                attributeName,
                ruleClassId.name(),
                SpellChecker.didYouMean(
                    attributeName,
                    rule.getAttributes().stream()
                        .filter(Attribute::isDocumented)
                        .map(Attribute::getName)
                        .collect(ImmutableList.toImmutableList()))),
            eventHandler);
        continue;
      }
      // Ignore all None values (after reporting an error)
      if (attributeValue == Starlark.NONE) {
        continue;
      }

      Attribute attr = getAttribute(attrIndex);

      if (attributeName.equals("licenses") && ignoreLicenses) {
        rule.setAttributeValue(attr, License.NO_LICENSE, /* explicit= */ false);
        definedAttrIndices.set(attrIndex);
        continue;
      }

      // Convert the build-lang value to a native value, if necessary.
      Object nativeAttributeValue;
      if (attributeValues.valuesAreBuildLanguageTyped()) {
        try {
          nativeAttributeValue =
              BuildType.convertFromBuildLangType(
                  rule.getRuleClass(),
                  attr,
                  attributeValue,
                  labelConverter,
                  listInterner,
                  simplifyUnconditionalSelects);
        } catch (ConversionException e) {
          rule.reportError(String.format("%s: %s", rule.getLabel(), e.getMessage()), eventHandler);
          continue;
        }
        // Ignore select({"//conditions:default": None}) values for attr types with null default.
        if (nativeAttributeValue == null) {
          continue;
        }
      } else {
        nativeAttributeValue = attributeValue;
      }

      if (attr.getName().equals("visibility")) {
        @SuppressWarnings("unchecked")
        List<Label> vis = (List<Label>) nativeAttributeValue;
        try {
          nativeAttributeValue = RuleVisibility.validateAndSimplify(vis);
        } catch (EvalException e) {
          rule.reportError(rule.getLabel() + " " + e.getMessage(), eventHandler);
        }
      }

      boolean explicit = attributeValues.isExplicitlySpecified(attributeAccessor);
      rule.setAttributeValue(attr, nativeAttributeValue, explicit);
      checkAllowedValues(rule, attr, eventHandler);
      definedAttrIndices.set(attrIndex);
    }
    return definedAttrIndices;
  }

  /**
   * Populates the attributes table of the new {@link Rule} with default values provided by this
   * {@link RuleClass} and the {@code pkgBuilder}. This will only provide values for attributes that
   * haven't already been populated, using {@code definedAttrIndices} to determine whether an
   * attribute was populated.
   *
   * <p>Errors are reported on {@code eventHandler}.
   */
  private void populateDefaultRuleAttributeValues(
      Rule rule, Package.Builder pkgBuilder, BitSet definedAttrIndices)
      throws InterruptedException, CannotPrecomputeDefaultsException {
    // Set defaults; ensure that every mandatory attribute has a value. Use the default if none
    // is specified.
    List<Attribute> attrsWithComputedDefaults = new ArrayList<>();
    int numAttributes = getAttributeCount();
    for (int attrIndex = 0; attrIndex < numAttributes; ++attrIndex) {
      if (definedAttrIndices.get(attrIndex)) {
        continue;
      }
      Attribute attr = getAttribute(attrIndex);
      if (attr.isMandatory()) {
        rule.reportError(
            String.format(
                "%s: missing value for mandatory attribute '%s' in '%s' rule",
                rule.getLabel(), attr.getName(), ruleClassId.name()),
            pkgBuilder.getLocalEventHandler());
      }

      // We must check both the name and the type of each attribute below in case a Starlark rule
      // defines a licenses or distributions attribute of another type.

      if (attr.hasComputedDefault()) {
        // Note that it is necessary to set all non-computed default values before calling
        // Attribute#getDefaultValue for computed default attributes. Computed default attributes
        // may have a condition predicate (i.e. the predicate returned by Attribute#getCondition)
        // that depends on non-computed default attribute values, and that condition predicate is
        // evaluated by the call to Attribute#getDefaultValue.
        attrsWithComputedDefaults.add(attr);

      } else if (attr.isLateBound()) {
        rule.setAttributeValue(attr, attr.getLateBoundDefault(), /* explicit= */ false);
      } else if (attr.isMaterializing()) {
        rule.setAttributeValue(attr, attr.getMaterializer(), false);
      } else if (attr.getName().equals(APPLICABLE_METADATA_ATTR)
          && attr.getType() == BuildType.LABEL_LIST) {
        // The check here is preventing against a corner case where the license()/package_info()
        // rule can get itself as applicable_metadata. This breaks the graph because there is now a
        // self-edge.
        //
        // There are two ways that I can see to resolve this. The first, what is shown here, simply
        // prunes the attribute if the source is a new-style license/metadata rule, based on what's
        // been provided publicly. This does create a tight coupling to the implementation, but this
        // is unavoidable since licenses are no longer a first-class type but we want first class
        // behavior in Bazel core.
        //
        // A different approach that would not depend on the implementation of the rule could filter
        // the list of default_applicable_metadata and not include the metadata rule if it matches
        // the name of the current rule. This obviously fixes the self-assignment rule, but the
        // resulting graph is semantically strange. The interpretation of the graph would be that
        // the metadata rule is subject to the metadata of the *other* default metadata, but not
        // itself. That looks very odd, and it's not semantically accurate.
        // As an alternate, if the self-edge is detected, why not simply drop all the
        // default_applicable_metadata attributes and avoid this oddness? That would work and
        // fix the self-edge problem, but for nodes that don't have the self-edge problem, they
        // would get all default_applicable_metadata and now the graph is inconsistent in that some
        // license() rules have applicable_metadata while others do not.
        if (rule.getRuleClassObject().isPackageMetadataRule()) {
          rule.setAttributeValue(attr, ImmutableList.of(), /* explicit= */ false);
        }

      } else if (attr.getName().equals("licenses") && attr.getType() == BuildType.LICENSE) {
        rule.setAttributeValue(
            attr,
            ignoreLicenses ? License.NO_LICENSE : pkgBuilder.getPartialPackageArgs().license(),
            /* explicit= */ false);

      } else if (attr.getName().equals("distribs") && attr.getType() == BuildType.DISTRIBUTIONS) {
        rule.setAttributeValue(attr, License.DEFAULT_DISTRIB, /* explicit= */ false);
      }
      // Don't store default values, querying materializes them at read time.
    }

    // An instance of the built-in 'test_suite' rule with an undefined or empty 'tests' attribute
    // attribute gets an '$implicit_tests' attribute, whose value is a shared per-package list of
    // all test labels, populated later.
    // TODO(blaze-rules-team): This should be in test_suite's implementation, not here.
    if (this.ruleClassId.name().equals("test_suite") && !this.isStarlark) {
      Attribute implicitTests = this.getAttributeByName("$implicit_tests");
      NonconfigurableAttributeMapper attributeMapper = NonconfigurableAttributeMapper.of(rule);
      if (implicitTests != null && attributeMapper.get("tests", BuildType.LABEL_LIST).isEmpty()) {
        boolean explicit = true; // so that it appears in query output
        rule.setAttributeValue(
            implicitTests,
            pkgBuilder.getTestSuiteImplicitTestsRef(attributeMapper.get("tags", Types.STRING_LIST)),
            explicit);
      }
    }

    // Set computed default attribute values now that all other (i.e. non-computed) default values
    // have been set.
    for (Attribute attr : attrsWithComputedDefaults) {
      // If Attribute#hasComputedDefault was true above, Attribute#getDefaultValue returns the
      // computed default function object or a Starlark computed default template. Note that we
      // cannot determine the exact value of the computed default function here because it may
      // depend on other attribute values that are configurable (i.e. they came from select({..})
      // expressions in the build language, and they require configuration data from the analysis
      // phase to be resolved). Instead, we're setting the attribute value to a reference to the
      // computed default function, or if #getDefaultValue is a Starlark computed default
      // template, setting the attribute value to a reference to the StarlarkComputedDefault
      // returned from StarlarkComputedDefaultTemplate#computePossibleValues.
      //
      // StarlarkComputedDefaultTemplate#computePossibleValues pre-computes all possible values the
      // function may evaluate to, and records them in a lookup table. By calling it here, with an
      // EventHandler, any errors that might occur during the function's evaluation can
      // be discovered and propagated here.
      Object valueToSet;
      Object defaultValue = attr.getDefaultValue(null);
      if (defaultValue instanceof StarlarkComputedDefaultTemplate template) {
        valueToSet = template.computePossibleValues(attr, rule, pkgBuilder.getLocalEventHandler());
      } else if (defaultValue instanceof ComputedDefault) {
        // Compute all possible values to verify that the ComputedDefault is well-defined. This was
        // previously done implicitly as part of visiting all labels to check for null-ness in
        // Rule.checkForNullLabels, but that was changed to skip non-label attributes to improve
        // performance.
        // TODO: b/287492305 - This is technically an illegal call to getPossibleValues as the
        // package has not yet finished loading. Do we even need this still?
        ((ComputedDefault) defaultValue).getPossibleValues(attr.getType(), rule);
        valueToSet = defaultValue;
      } else {
        valueToSet = defaultValue;
      }
      rule.setAttributeValue(attr, valueToSet, /* explicit= */ false);
    }
  }

  /**
   * Collects all labels used as keys for configurable attributes and places them into the special
   * implicit attribute that tracks them.
   */
  private static void populateConfigDependenciesAttribute(Rule rule) {
    RawAttributeMapper attributes = RawAttributeMapper.of(rule);
    Attribute configDepsAttribute =
        attributes.getAttributeDefinition(CONFIG_SETTING_DEPS_ATTRIBUTE);
    if (configDepsAttribute == null) {
      return;
    }

    LinkedHashSet<Label> configLabels = new LinkedHashSet<>();
    for (Attribute attr : rule.getAttributes()) {
      SelectorList<?> selectorList = attributes.getSelectorList(attr.getName(), attr.getType());
      if (selectorList != null) {
        configLabels.addAll(selectorList.getKeyLabels());
      }
    }

    rule.setAttributeValue(
        configDepsAttribute, ImmutableList.copyOf(configLabels), /* explicit= */ false);
  }

  /**
   * Report an error for each label that appears more than once in a LABEL_LIST attribute of the
   * given rule.
   *
   * @param rule The rule.
   * @param eventHandler The eventHandler to use to report the duplicated deps.
   */
  private static void checkForDuplicateLabels(Rule rule, EventHandler eventHandler) {
    AggregatingAttributeMapper mapper = AggregatingAttributeMapper.of(rule);
    for (Attribute attribute : rule.getAttributes()) {
      if (attribute.getType() != BuildType.LABEL_LIST) {
        continue;
      }
      Set<Label> duplicates = mapper.checkForDuplicateLabels(attribute);
      for (Label label : duplicates) {
        rule.reportError(
            String.format(
                "Label '%s' is duplicated in the '%s' attribute of rule '%s'",
                label, attribute.getName(), rule.getName()),
            eventHandler);
      }
    }
  }

  /**
   * Report an error if the rule has a timeout or size attribute that is not a legal value. These
   * attributes appear on all tests.
   *
   * @param rule the rule to check
   * @param eventHandler the eventHandler to use to report the duplicated deps
   */
  private static void checkForValidSizeAndTimeoutValues(Rule rule, EventHandler eventHandler) {
    if (rule.getRuleClassObject().hasAttr("size", Type.STRING)) {
      String size = NonconfigurableAttributeMapper.of(rule).get("size", Type.STRING);
      if (TestSize.getTestSize(size) == null) {
        rule.reportError(
            String.format("In rule '%s', size '%s' is not a valid size.", rule.getName(), size),
            eventHandler);
      }
    }
    if (rule.getRuleClassObject().hasAttr("timeout", Type.STRING)) {
      String timeout = NonconfigurableAttributeMapper.of(rule).get("timeout", Type.STRING);
      if (TestTimeout.getTestTimeout(timeout) == null) {
        rule.reportError(
            String.format(
                "In rule '%s', timeout '%s' is not a valid timeout.", rule.getName(), timeout),
            eventHandler);
      }
    }
  }

  /**
   * Verifies that the rule has a valid value for the attribute according to its allowed values.
   *
   * <p>If the value for the given attribute on the given rule is invalid, an error will be recorded
   * in the given EventHandler.
   *
   * <p>If the rule is configurable, all of its potential values are evaluated, and errors for each
   * of the invalid values are reported.
   */
  private static void checkAllowedValues(
      Rule rule, Attribute attribute, EventHandler eventHandler) {
    if (attribute.checkAllowedValues()) {
      PredicateWithMessage<Object> allowedValues = attribute.getAllowedValues();
      Iterable<?> values =
          AggregatingAttributeMapper.of(rule)
              .visitAttribute(attribute.getName(), attribute.getType());
      for (Object value : values) {
        if (!allowedValues.apply(value)) {
          rule.reportError(
              String.format(
                  "%s: invalid value in '%s' attribute: %s",
                  rule.getLabel(), attribute.getName(), allowedValues.getErrorReason(value)),
              eventHandler);
        }
      }
    }
  }

  private static void checkAspectAllowedValues(Rule rule, EventHandler eventHandler) {
    if (rule.hasAspects()) {
      for (Attribute attrOfRule : rule.getAttributes()) {
        for (Aspect aspect : attrOfRule.getAspects(rule)) {
          for (Attribute attrOfAspect : aspect.getDefinition().getAttributes().values()) {
            // By this point the AspectDefinition has been created and values assigned.
            if (attrOfAspect.checkAllowedValues()) {
              PredicateWithMessage<Object> allowedValues = attrOfAspect.getAllowedValues();
              Object value = attrOfAspect.getDefaultValue(null);
              if (!allowedValues.apply(value)) {
                if (RawAttributeMapper.of(rule).isConfigurable(attrOfAspect.getName())) {
                  rule.reportError(
                      String.format(
                          "%s: attribute '%s' has a select() and aspect %s also declares "
                              + "'%s'. Aspect attributes don't currently support select().",
                          rule.getLabel(),
                          attrOfAspect.getName(),
                          aspect.getDefinition().getName(),
                          rule.getLabel()),
                      eventHandler);
                } else {
                  rule.reportError(
                      String.format(
                          "%s: invalid value in '%s' attribute: %s",
                          rule.getLabel(),
                          attrOfAspect.getName(),
                          allowedValues.getErrorReason(value)),
                      eventHandler);
                }
              }
            }
          }
        }
      }
    }
  }

  @Override
  public String toString() {
    return ruleClassId.name();
  }

  public boolean isDocumented() {
    return documented;
  }

  /**
   * Returns true iff the outputs of this rule should be created beneath the <i>bin</i> directory,
   * false if beneath <i>genfiles</i>. For most rule classes, this is a constant, but for genrule,
   * it is a property of the individual rule instance, derived from the 'output_to_bindir'
   * attribute; see Rule.outputsToBindir().
   */
  public boolean outputsToBindir() {
    return outputsToBindir;
  }

  /** Returns this RuleClass's custom Starlark rule implementation. */
  @Nullable
  public StarlarkCallable getConfiguredTargetFunction() {
    return configuredTargetFunction;
  }

  @Nullable
  public BuildSetting getBuildSetting() {
    return buildSetting;
  }

  /** Returns a function that computes the options referenced by a rule. */
  public Function<? super Rule, ? extends Set<String>> getOptionReferenceFunction() {
    return optionReferenceFunction;
  }

  /**
   * For Starlark rule classes, returns this RuleClass's rule definition environment's label, which
   * is never null. Is null for native rules' RuleClass objects.
   *
   * <p>In certain unusual cases (for example, unexported repository rules or analysis test rule
   * classes), the values of {@link #getRuleDefinitionEnvironmentLabel()} and {@link
   * #getStarlarkExtensionLabel()} may differ.
   */
  // TODO(b/366027483): unify starlarkExtensionLabel and ruleDefinitionEnvironmentLabel.
  @Nullable
  public Label getRuleDefinitionEnvironmentLabel() {
    return ruleDefinitionEnvironmentLabel;
  }

  /**
   * Returns the digest for the RuleClass's rule definition environment, a hash of the .bzl file
   * defining the rule class and all the .bzl files it transitively loads. Null for native rules'
   * RuleClass objects.
   *
   * <p>This digest is sensitive to any changes in the declaration of the RuleClass itself,
   * including changes in the .bzl files it transitively loads, but it is not unique: all
   * RuleClasses defined within in the same .bzl file have the same digest.
   *
   * <p>To uniquely identify a rule class, we need the triple: ({@link
   * #getRuleDefinitionEnvironmentLabel()}, {@link #getRuleDefinitionEnvironmentDigest()}, {@link
   * #getName()}) The first two components are collectively known as the "rule definition
   * environment". Dependency analysis may compare these triples to detect whether a change to a
   * rule definition might have consequences for a rule instance that has not otherwise changed.
   *
   * <p>Note: this concept of rule definition environment is not related to the {@link
   * com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment} interface.
   */
  @Nullable
  public byte[] getRuleDefinitionEnvironmentDigest() {
    return ruleDefinitionEnvironmentDigest;
  }

  @Nullable
  public ImmutableTable<RepositoryName, String, RepositoryName>
      getRuleDefinitionEnvironmentRepoMappingEntries() {
    return ruleDefinitionEnvironmentRepoMappingEntries;
  }

  /** Returns true if this RuleClass is a Starlark-defined RuleClass. */
  @Override
  public boolean isStarlark() {
    return isStarlark;
  }

  /**
   * If this is a Starlark-defined rule class which had been exported, returns the label of the
   * Starlark file (typically a .bzl file, except for analysis test rule classes where it is a BUILD
   * file) where the rule definition was exported, or null otherwise.
   *
   * <p>If a Starlark rule class has been exported, the tuple (rule name, starlark extension label)
   * uniquely identifies it.
   *
   * <p>In certain unusual cases (for example, unexported repository rules or analysis test rule
   * classes), the values of {@link #getRuleDefinitionEnvironmentLabel()} and {@link
   * #getStarlarkExtensionLabel()} may differ.
   */
  // TODO(b/111199163): prohibit use of unexported repository rules.
  // TODO(b/366027483): unify starlarkExtensionLabel and ruleDefinitionEnvironmentLabel.
  @Nullable
  public Label getStarlarkExtensionLabel() {
    return starlarkExtensionLabel;
  }

  /**
   * If this is a Starlark-defined rule class which had been defined with a documentation string,
   * i.e. via {@code rule(doc = "...")}), returns that documentation string, or null otherwise.
   */
  @Nullable
  public String getStarlarkDocumentation() {
    return starlarkDocumentation;
  }

  /** Returns true if this RuleClass can be extended. */
  public boolean isExtendable() {
    return extendable;
  }

  @Nullable
  public Label getExtendableAllowlist() {
    return extendableAllowlist;
  }

  /** Returns true if this RuleClass is Starlark-defined and is subject to analysis-time tests. */
  public boolean isStarlarkTestable() {
    return starlarkTestable;
  }

  /** Returns true if rules of this class can be made available for dependency resolution. */
  @Override
  public boolean isDependencyResolutionRule() {
    return dependencyResolutionRule;
  }

  /** Returns true if this rule class outputs a default executable for every rule. */
  public boolean isExecutableStarlark() {
    return isExecutableStarlark;
  }

  /** Returns true if this rule class is an analysis test (set by analysis_test = true). */
  boolean isAnalysisTest() {
    return isAnalysisTest;
  }

  /**
   * Returns true if this rule class has at least one attribute with an analysis test transition. (A
   * starlark-defined transition using analysis_test_transition()).
   */
  boolean hasAnalysisTestTransition() {
    return hasAnalysisTestTransition;
  }

  /** Returns a list of AllowlistChecker to check. */
  public ImmutableList<AllowlistChecker> getAllowlistCheckers() {
    return allowlistCheckers;
  }

  /**
   * If true, no rule of this class ever declares a license regardless of what the rule's or
   * package's <code>licenses</code> attribute says.
   *
   * <p>This is useful for rule types that don't make sense for license checking.
   */
  boolean ignoreLicenses() {
    return ignoreLicenses;
  }

  public ImmutableSet<ToolchainTypeRequirement> getToolchainTypes() {
    return toolchainTypes;
  }

  boolean useToolchainResolution(Rule rule) {
    return this.toolchainResolutionMode.useToolchainResolution(rule);
  }

  public ImmutableSet<Label> getExecutionPlatformConstraints() {
    return executionPlatformConstraints;
  }

  public ImmutableMap<String, ExecGroup> getExecGroups() {
    return execGroups;
  }

  public AutoExecGroupsMode getAutoExecGroupsMode() {
    return autoExecGroupsMode;
  }

  OutputFile.Kind getOutputFileKind() {
    return outputFileKind;
  }

  /**
   * Returns true if this rule is a <code>license()</code> as described in
   * https://docs.google.com/document/d/1uwBuhAoBNrw8tmFs-NxlssI6VRolidGYdYqagLqHWt8/edit# or
   * similar metadata.
   *
   * <p>The intended use is to detect if this rule is of a type which would be used in <code>
   * default_package_metadata</code>, so that we don't apply it to an instanced of itself when
   * <code>applicable_metadata</code> is left unset. Doing so causes a self-referential loop. To
   * prevent that, we are overly cautious at this time, treating all rules from <code>@rules_license
   * </code> as potential metadata rules.
   *
   * <p>Most users will only use declarations from <code>@rules_license</code>. If they which to
   * create organization local rules, they must be careful to avoid loops by explicitly setting
   * <code>applicable_metadata</code> on each of the metadata targets they define, so that default
   * processing is not an issue.
   */
  public boolean isPackageMetadataRule() {
    // If it was not defined in Starlark, it can not be a new style package metadata rule.
    if (ruleDefinitionEnvironmentLabel == null) {
      return false;
    }
    if (ruleDefinitionEnvironmentLabel.getRepository().getName().equals("rules_license")) {
      // For now, we treat all rules in rules_license as potenial metadate rules.
      // In the future we should add a way to disambiguate the two. The least invasive
      // thing is to add a hidden attribute to mark metadata rules. That attribute
      // could have a default value referencing @rules_license//<something>. That style
      // of checking would allow users to apply it to their own metadata rules. We are
      // not building it today because the exact needs are not clear.
      return true;
    }
    // BEGIN-INTERNAL
    // TODO(aiuto): This is a Google-ism, remove from Bazel.
    String packageName = ruleDefinitionEnvironmentLabel.getPackageName();
    if (packageName.startsWith("tools/build_defs/license")
        || packageName.startsWith("third_party/rules_license")) {
      return true;
    }
    // END-INTERNAL
    return false;
  }

  public ImmutableSet<? extends StarlarkSubruleApi> getSubrules() {
    Preconditions.checkState(isStarlark());
    return subrules;
  }
}
