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

import static java.util.Comparator.naturalOrder;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.UnmodifiableIterator;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.LabelValidator;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.io.FileSymlinkException;
import com.google.devtools.build.lib.packages.Globber.BadGlobException;
import com.google.devtools.build.lib.packages.TargetRecorder.NameConflictException;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading.Code;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkNativeModuleApi;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.DetailedIOException;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkIndexable;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkIterable;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.Location;

/** The Starlark native module. */
public class StarlarkNativeModule implements StarlarkNativeModuleApi {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * This map contains all the (non-rule) functions of the native module (keyed by their symbol
   * name). These native module bindings should be added (without the 'native' module namespace) to
   * the global Starlark environment for BUILD files.
   *
   * <p>For example, the function "glob" is available under both a global symbol name {@code glob()}
   * as well as under the native module namepsace {@code native.glob()}. An entry of this map is
   * thus ("glob" : glob function).
   */
  public static final ImmutableMap<String, Object> BINDINGS_FOR_BUILD_FILES = initializeBindings();

  private static ImmutableMap<String, Object> initializeBindings() {
    ImmutableMap.Builder<String, Object> bindings = ImmutableMap.builder();
    Starlark.addMethods(bindings, new StarlarkNativeModule());
    return bindings.buildOrThrow();
  }

  @Override
  public Sequence<?> glob(
      Sequence<?> include,
      Sequence<?> exclude,
      StarlarkInt excludeDirs,
      Object allowEmptyArgument,
      StarlarkThread thread)
      throws EvalException, InterruptedException {
    Package.Builder pkgBuilder = Package.Builder.fromOrFailAllowBuildOnly(thread, "glob()");

    List<String> includes = Types.STRING_LIST.convert(include, "'glob' argument");
    List<String> excludes = Types.STRING_LIST.convert(exclude, "'glob' argument");
    Globber.Operation op =
        excludeDirs.signum() != 0 ? Globber.Operation.FILES : Globber.Operation.FILES_AND_DIRS;

    boolean allowEmpty;
    if (allowEmptyArgument == Starlark.UNBOUND) {
      allowEmpty =
          !thread.getSemantics().getBool(BuildLanguageOptions.INCOMPATIBLE_DISALLOW_EMPTY_GLOB);
    } else if (allowEmptyArgument instanceof Boolean) {
      allowEmpty = (Boolean) allowEmptyArgument;
    } else {
      throw Starlark.errorf(
          "expected boolean for argument `allow_empty`, got `%s`", allowEmptyArgument);
    }

    List<String> matches = runGlobOperation(pkgBuilder, thread, includes, excludes, op, allowEmpty);

    ArrayList<String> result = new ArrayList<>(matches.size());
    for (String match : matches) {
      if (match.charAt(0) == '@') {
        // Add explicit colon to disambiguate from external repository.
        match = ":" + match;
      }
      result.add(match);
    }
    result.sort(naturalOrder());

    return StarlarkList.copyOf(thread.mutability(), result);
  }

  /**
   * WARNING -- HACK: We're using this marker type to signify that we're in module extension eval,
   * and native.existing_rule[s] should just return nothing. We can't check for
   * ModuleExtensionEvalStarlarkThreadContext because that would cause a cyclic dependency. The
   * proper way to implement this would be to create a distinct no-op "StarlarkNativeModule" object
   * that's only used for bzlmod, but that requires a big refactor that we're not going to have time
   * for before Bazel 5.0.
   */
  // TODO(wyv): Do the proper fix described above.
  public static class ExistingRulesShouldBeNoOp {}

  // TODO(https://github.com/bazelbuild/bazel/issues/13605): implement StarlarkMapping (after we've
  // added such an interface) to allow `dict(native.existing_rule(x))`.
  private static interface DictLikeView
      extends StarlarkIndexable, StarlarkIterable<String>, Map<String, Object> {
    @Override
    public default boolean isImmutable() {
      return true;
    }

    // java.util.Map accessor.
    // For absent keys, Java callers will see null and Starlark callers will see None.
    @Override
    @StarlarkMethod(
        name = "get",
        doc = "Behaves the same as <a href=\"dict.html#get\"><code>dict.get</code></a>.",
        parameters = {
          @Param(name = "key", doc = "The key to look for."),
          @Param(
              name = "default",
              defaultValue = "None",
              named = true,
              doc = "The default value to use (instead of None) if the key is not found.")
        },
        allowReturnNones = true)
    @Nullable
    public Object getOrDefault(Object key, @Nullable Object defaultValue);

    @StarlarkMethod(
        name = "keys",
        doc =
            "Behaves like <a href=\"dict.html#keys\"><code>dict.keys</code></a>, but the returned"
                + " value is an immutable sequence.")
    public default StarlarkIterable<String> keys() {
      // TODO(https://github.com/bazelbuild/starlark/issues/203): return a sequence view which
      // supports efficient membership lookup (`"foo" in existing_rule("bar").keys()`), and
      // materializes into a list (to allow len() or lookup by integer index) only if needed. Note
      // that materialization into a list would need to be thread-safe (assuming it's possible for
      // the sequence view to be used from multiple starlark threads). For now, we return an
      // immutable list, so that migration to a sequence view is less likely to cause breakage.
      return StarlarkList.immutableCopyOf(this);
    }

    @StarlarkMethod(
        name = "values",
        doc =
            "Behaves like <a href=\"dict.html#values\"><code>dict.values</code></a>, but the"
                + " returned value is an immutable sequence.")
    // This method is named starlarkValues to avoid collision with Map#values
    // (StarlarkAnnotations.getStarlarkMethod does not support overloading).
    public default StarlarkIterable<Object> starlarkValues() {
      // TODO(https://github.com/bazelbuild/starlark/issues/203): return a sequence view; see keys()
      // for implementation concerns.
      ArrayList<Object> valueList = new ArrayList<>();
      for (String key : this) {
        valueList.add(Preconditions.checkNotNull(get(key)));
      }
      return StarlarkList.immutableCopyOf(valueList);
    }

    @StarlarkMethod(
        name = "items",
        doc =
            "Behaves like <a href=\"dict.html#items\"><code>dict.items</code></a>, but the returned"
                + " value is an immutable sequence.")
    public default StarlarkIterable<Tuple> items() {
      // TODO(https://github.com/bazelbuild/starlark/issues/203): return a sequence view; see keys()
      // for implementation concerns.
      ArrayList<Tuple> itemsList = new ArrayList<>();
      for (String key : this) {
        itemsList.add(Tuple.pair(key, Preconditions.checkNotNull(get(key))));
      }
      return StarlarkList.immutableCopyOf(itemsList);
    }

    @Override
    public default Object getIndex(StarlarkSemantics semantics, Object key) throws EvalException {
      Object val = get(key);
      if (val != null) {
        return val;
      }
      throw Starlark.errorf("key %s not found in view", Starlark.repr(key));
    }

    @Override
    public default boolean containsKey(StarlarkSemantics semantics, Object key) {
      return containsKey(key);
    }

    // java.util.Map accessors

    @Nullable
    @Override
    public default Object get(Object key) {
      return getOrDefault(key, null);
    }

    @Override
    public default boolean isEmpty() {
      return !iterator().hasNext();
    }

    @Override
    public default Set<String> keySet() {
      return ImmutableSet.copyOf(keys());
    }

    @Override
    public default Collection<Object> values() {
      return StarlarkList.immutableCopyOf(starlarkValues());
    }

    @Override
    public default Set<Map.Entry<String, Object>> entrySet() {
      ImmutableSet.Builder<Map.Entry<String, Object>> entries = new ImmutableSet.Builder<>();
      for (Tuple keyValuePair : items()) {
        entries.add(
            new AbstractMap.SimpleEntry<String, Object>(
                (String) keyValuePair.get(0), keyValuePair.get(1)));
      }
      return entries.build();
    }

    @Override
    public default boolean containsValue(@Nullable Object value) {
      for (String key : this) {
        if (Preconditions.checkNotNull(get(key)).equals(value)) {
          return true;
        }
      }
      return false;
    }

    // disallow java.util.Map mutators

    /**
     * @deprecated Not supported: immutable view.
     */
    @Deprecated
    @Override
    public default void clear() {
      throw new UnsupportedOperationException();
    }

    /**
     * @deprecated Not supported: immutable view.
     */
    @Nullable
    @Deprecated
    @Override
    public default Object put(String key, Object value) {
      throw new UnsupportedOperationException();
    }

    /**
     * @deprecated Not supported: immutable view.
     */
    @Deprecated
    @Override
    public default void putAll(Map<? extends String, ? extends Object> map) {
      throw new UnsupportedOperationException();
    }

    /**
     * @deprecated Not supported: immutable view.
     */
    @Nullable
    @Deprecated
    @Override
    public default Object remove(Object key) {
      throw new UnsupportedOperationException();
    }
  }

  // Note: Attribute values that are not representable in Starlark are treated as if they are absent
  // in the view.
  private static final class ExistingRuleView implements DictLikeView {
    private final Rule rule;

    ExistingRuleView(Rule rule) {
      this.rule = rule;
    }

    @Override
    public void repr(Printer printer) {
      printer.append("<native.ExistingRuleView for target '").append(rule.getName()).append("'>");
    }

    /**
     * Returns the starlark representation of our rule's attribute value if the attribute is
     * exportable and the value can be represented in starlark; otherwise, returns null.
     */
    @Nullable
    private Object starlarkifyAttribute(String attributeName) {
      if (!isPotentiallyExportableAttribute(rule.getRuleClassObject(), attributeName)) {
        return null;
      }
      return starlarkifyValue(null /* immutable */, rule.getAttr(attributeName), rule.getPackage());
    }

    @Override
    @Nullable // Starlark callers get None where Java callers would expect null.
    public Object getOrDefault(Object key, @Nullable Object defaultValue) {
      if (!(key instanceof String)) {
        return defaultValue;
      }
      String attributeName = (String) key;
      switch (attributeName) {
        case "name" -> {
          return rule.getName();
        }
        case "kind" -> {
          return rule.getRuleClass();
        }
        default -> {
          Object value = starlarkifyAttribute(attributeName);
          if (value != null) {
            return value;
          }
        }
      }
      return defaultValue;
    }

    @Override
    public Iterator<String> iterator() {
      return Iterators.concat(
          ImmutableList.of("name", "kind").iterator(),
          // Compared to using stream().map(...).filter(...).iterator(), this bespoke iterator
          // reduces loading time by 15% for a 4000-target package making heavy use of
          // `native.existing_rules`.
          new UnmodifiableIterator<String>() {
            private final Iterator<Attribute> attributes = rule.getAttributes().iterator();
            @Nullable private String nextRelevantAttributeName;

            private boolean isRelevant(String attributeName) {
              return switch (attributeName) {
                // pseudo-names handled specially
                case "name", "kind" -> false;
                default -> starlarkifyAttribute(attributeName) != null;
              };
            }

            private void findNextRelevantName() {
              if (nextRelevantAttributeName == null) {
                while (attributes.hasNext()) {
                  String attributeName = attributes.next().getName();
                  if (isRelevant(attributeName)) {
                    nextRelevantAttributeName = attributeName;
                    break;
                  }
                }
              }
            }

            @Override
            public boolean hasNext() {
              findNextRelevantName();
              return nextRelevantAttributeName != null;
            }

            @Override
            public String next() {
              findNextRelevantName();
              if (nextRelevantAttributeName != null) {
                String attributeName = nextRelevantAttributeName;
                nextRelevantAttributeName = null;
                return attributeName;
              } else {
                throw new NoSuchElementException();
              }
            }
          });
    }

    @Override
    public boolean containsKey(Object key) {
      return get(key) != null;
    }

    // Necessarily O(n), since we need to scan which attributes are exportable/starlakifiable. We
    // could cache the result, but the complexity of doing so does not seem to be worth (currently,
    // this method is not expected to be called).
    @Override
    public int size() {
      return Iterables.size(this);
    }
  }

  @Override
  public Object existingRule(String name, StarlarkThread thread) throws EvalException {
    if (thread.getThreadLocal(ExistingRulesShouldBeNoOp.class) != null) {
      return Starlark.NONE;
    }
    Package.Builder pkgBuilder =
        Package.Builder.fromOrFailDisallowNonFinalizerMacros(thread, "existing_rule()");
    @Nullable Rule rule = pkgBuilder.getNonFinalizerInstantiatedRule(name);
    if (rule != null) {
      return new ExistingRuleView(rule);
    } else {
      return Starlark.NONE;
    }
  }

  private static final class ExistingRulesView implements DictLikeView {
    // We take a lightweight snapshot of the rules existing in a Package.Builder to avoid exposing
    // any rules added to Package.Builder after the existing_rules() call which created this view.
    private final Map<String, Rule> rulesSnapshotView;

    ExistingRulesView(Map<String, Rule> rulesSnapshotView) {
      this.rulesSnapshotView = rulesSnapshotView;
    }

    @Override
    public void repr(Printer printer) {
      printer.append("<native.ExistingRulesView object>");
    }

    @Override
    @Nullable // Starlark callers get None where Java callers would expect null.
    public Object getOrDefault(Object key, @Nullable Object defaultValue) {
      if (!(key instanceof String)) {
        return defaultValue;
      }
      Rule rule = rulesSnapshotView.get(key);
      if (rule != null) {
        return new ExistingRuleView(rule);
      } else {
        return defaultValue;
      }
    }

    @Override
    public Iterator<String> iterator() {
      return rulesSnapshotView.keySet().iterator();
    }

    @Override
    public boolean containsKey(Object key) {
      if (!(key instanceof String)) {
        return false;
      }
      return rulesSnapshotView.containsKey(key);
    }

    @Override
    public int size() {
      return rulesSnapshotView.size();
    }
  }

  /*
    If necessary, we could allow filtering by tag (anytag, alltags), name (regexp?), kind ?
    For now, we ignore this, since users can implement it in Starlark.
  */
  @Override
  public Object existingRules(StarlarkThread thread) throws EvalException {
    if (thread.getThreadLocal(ExistingRulesShouldBeNoOp.class) != null) {
      return Dict.empty();
    }
    Package.Builder pkgBuilder =
        Package.Builder.fromOrFailDisallowNonFinalizerMacros(thread, "existing_rules()");
    return new ExistingRulesView(pkgBuilder.getRulesSnapshotView());
  }

  @Override
  public NoneType packageGroup(
      String name, Sequence<?> packagesO, Sequence<?> includesO, StarlarkThread thread)
      throws EvalException {
    Package.Builder pkgBuilder =
        Package.Builder.fromOrFailDisallowWorkspace(thread, "package_group()");

    List<String> packages =
        Types.STRING_LIST.convert(packagesO, "'package_group.packages argument'");
    List<Label> includes =
        BuildType.LABEL_LIST.convert(
            includesO, "'package_group.includes argument'", pkgBuilder.getLabelConverter());

    Location loc = thread.getCallerLocation();
    try {
      pkgBuilder.addPackageGroup(
          name,
          packages,
          includes,
          /* allowPublicPrivate= */ thread
              .getSemantics()
              .getBool(BuildLanguageOptions.INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX),
          /* repoRootMeansCurrentRepo= */ thread
              .getSemantics()
              .getBool(BuildLanguageOptions.INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX),
          // TODO(#19922): addPackageGroup should access the builder's own eventHandler directly.
          pkgBuilder.getLocalEventHandler(),
          loc);
      return Starlark.NONE;
    } catch (LabelSyntaxException e) {
      throw Starlark.errorf("package group has invalid name: %s: %s", name, e.getMessage());
    } catch (NameConflictException e) {
      throw new EvalException(e);
    }
  }

  @Override
  public NoneType exportsFiles(
      Sequence<?> srcs, Object visibilityO, Object licensesO, StarlarkThread thread)
      throws EvalException {
    Package.Builder pkgBuilder =
        Package.Builder.fromOrFailDisallowWorkspace(thread, "exports_files()");
    List<String> files = Types.STRING_LIST.convert(srcs, "'exports_files' operand");

    RuleVisibility visibility =
        Starlark.isNullOrNone(visibilityO)
            ? RuleVisibility.PUBLIC
            : RuleVisibility.parse(
                BuildType.LABEL_LIST.convert(
                    visibilityO, "'exports_files' operand", pkgBuilder.getLabelConverter()));
    MacroInstance currentMacro = pkgBuilder.currentMacro();
    if (currentMacro != null) {
      visibility = visibility.concatWithPackage(currentMacro.getDefinitionPackage());
    }

    // TODO(bazel-team): is licenses plural or singular?
    License license = BuildType.LICENSE.convertOptional(licensesO, "'exports_files' operand");

    Location loc = thread.getCallerLocation();
    for (String file : files) {
      String errorMessage = LabelValidator.validateTargetName(file);
      if (errorMessage != null) {
        throw Starlark.errorf("%s", errorMessage);
      }
      try {
        InputFile inputFile = pkgBuilder.createInputFile(file, loc);
        // TODO: #19922 - The use of identity inequality in this visibility check seems suspect,
        // since the same logical visibility may have multiple RuleVisibility instances. But it's
        // unclear why we want to support idempotent exports_files() with the same logical
        // visibility at all. With Macro-Aware Visibility, it becomes possible for two identical
        // visibility lines to declare different actual visibility values depending on context.
        if (inputFile.isVisibilitySpecified() && inputFile.getVisibility() != visibility) {
          throw Starlark.errorf(
              "visibility for exported file '%s' declared twice", inputFile.getName());
        }
        if (license != null && inputFile.isLicenseSpecified()) {
          throw Starlark.errorf(
              "licenses for exported file '%s' declared twice", inputFile.getName());
        }

        pkgBuilder.setVisibilityAndLicense(inputFile, visibility, license);
      } catch (NameConflictException e) {
        throw Starlark.errorf("%s", e.getMessage());
      }
    }
    return Starlark.NONE;
  }

  @Override
  public String packageName(StarlarkThread thread) throws EvalException {
    Package.Builder pkgBuilder =
        Package.Builder.fromOrFailDisallowWorkspace(thread, "package_name()");
    return pkgBuilder.getPackageIdentifier().getPackageFragment().getPathString();
  }

  @Override
  public String repositoryName(StarlarkThread thread) throws EvalException {
    // for legacy reasons, this is prefixed with a single '@'.
    Package.Builder.fromOrFailDisallowWorkspace(thread, "repository_name()");
    return '@' + repoName(thread);
  }

  @Override
  public String repoName(StarlarkThread thread) throws EvalException {
    Package.Builder pkgBuilder = Package.Builder.fromOrFailDisallowWorkspace(thread, "repo_name()");
    return pkgBuilder.getPackageIdentifier().getRepository().getName();
  }

  @Override
  public Label packageRelativeLabel(Object input, StarlarkThread thread) throws EvalException {
    // In an initializer, BazelStarlarkContext isn't available, just the label converter.
    LabelConverter labelConverter = thread.getThreadLocal(LabelConverter.class);
    if (labelConverter == null) {
      Package.Builder pkgBuilder =
          Package.Builder.fromOrFailDisallowWorkspace(thread, "package_relative_label()");
      labelConverter = pkgBuilder.getLabelConverter();
    }
    if (input instanceof Label inputLabel) {
      return inputLabel;
    }
    try {
      return labelConverter.convert((String) input);
    } catch (LabelSyntaxException e) {
      throw Starlark.errorf("invalid label in native.package_relative_label: %s", e.getMessage());
    }
  }

  @Override
  @Nullable
  public String moduleName(StarlarkThread thread) throws EvalException {
    Package.Builder pkgBuilder =
        Package.Builder.fromOrFailDisallowWorkspace(thread, "module_name()");
    return pkgBuilder.getAssociatedModuleName().orElse(null);
  }

  @Override
  @Nullable
  public String moduleVersion(StarlarkThread thread) throws EvalException {
    Package.Builder pkgBuilder =
        Package.Builder.fromOrFailDisallowWorkspace(thread, "module_version()");
    return pkgBuilder.getAssociatedModuleVersion().orElse(null);
  }

  /**
   * Returns true if the given attribute of a rule class is generally allowed to be exposed via
   * {@code native.existing_rule()} and {@code native.existing_rules()}.
   *
   * <p>This method makes no attempt to validate that the attribute exists in the rule class.
   *
   * <p>Even if this method returns true, the attribute may still be suppressed if it has a
   * prohibited value (e.g. is of a bad type, or is a select() that cannot be processed).
   */
  private static boolean isPotentiallyExportableAttribute(
      RuleClass ruleClass, String attributeName) {
    if (attributeName.length() == 0 || !Character.isAlphabetic(attributeName.charAt(0))) {
      // Do not expose hidden or implicit attributes.
      return false;
    }
    if (attributeName.equals("distribs")) {
      Attribute attr = ruleClass.getAttributeByName(attributeName);
      if (attr != null && attr.getType() == BuildType.DISTRIBUTIONS) {
        // "distribs" attribute (a Set<License.DistributionType> value) is not a StarlarkValue. Note
        // that we cannot check for a Set<License.DistributionType> directly because generic type
        // info is erased at runime.
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if the given value is generally allowed to be exposed via {@code
   * native.existing_rule()} and or {@code native.existing_rules()}. Returns false for null.
   *
   * <p>Even if this method returns true, the value may still be suppressed if it is a select() that
   * cannot be processed.
   */
  private static boolean isPotentiallyStarlarkifiableValue(@Nullable Object val) {
    if (val == null) {
      return false;
    }
    if (val.getClass().isAnonymousClass()) {
      // Computed defaults. They will be represented as
      // "deprecation": com.google.devtools.build.lib.analysis.BaseRuleClasses$2@6960884a,
      // Filter them until we invent something more clever.
      return false;
    }

    if (val instanceof License) {
      // License is deprecated as a Starlark type, so omit this type from Starlark values
      // to avoid exposing these objects, even though they are technically StarlarkValue.
      return false;
    }

    return true;
  }

  /**
   * Converts a target attribute value to a Starlark value for return in {@code
   * native.existing_rule()} or {@code native.existing_rules()}.
   *
   * <p>Any dict values in the result have mutability {@code mu}.
   *
   * <p>Any label values in the result which are inside {@code pkg} (the current package) are
   * rewritten using ":foo" shorthand.
   *
   * @return the value, or null if we don't want to export it to the user.
   */
  @Nullable
  private static Object starlarkifyValue(Mutability mu, Object val, Package pkg) {
    // easy cases
    if (!isPotentiallyStarlarkifiableValue(val)) {
      return null;
    }
    if (val instanceof Boolean || val instanceof String || val instanceof StarlarkInt) {
      return val;
    }

    if (val instanceof TriState) {
      return switch ((TriState) val) {
        case AUTO -> StarlarkInt.of(-1);
        case YES -> StarlarkInt.of(1);
        case NO -> StarlarkInt.of(0);
      };
    }

    if (val instanceof Label l) {
      if (l.getPackageName().equals(pkg.getName())) {
        // TODO(https://github.com/bazelbuild/bazel/issues/13828): do not ignore the repo component
        // of the label.
        return ":" + l.getName();
      }
      return l.getCanonicalForm();
    }

    if (val instanceof List) {
      List<Object> l = new ArrayList<>();
      for (Object o : (List<?>) val) {
        Object elt = starlarkifyValue(mu, o, pkg);
        if (elt == null) {
          continue;
        }
        l.add(elt);
      }

      return Tuple.copyOf(l);
    }

    if (val instanceof Map) {
      Dict.Builder<Object, Object> m = Dict.builder();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) val).entrySet()) {
        Object key = starlarkifyValue(mu, e.getKey(), pkg);
        Object mapVal = starlarkifyValue(mu, e.getValue(), pkg);

        if (key == null || mapVal == null) {
          continue;
        }

        m.put(key, mapVal);
      }
      return m.build(mu);
    }

    if (val instanceof BuildType.SelectorList) {
      List<Object> selectors = new ArrayList<>();
      for (BuildType.Selector<?> selector : ((BuildType.SelectorList<?>) val).getSelectors()) {
        Dict.Builder<Object, Object> m = Dict.builder();
        selector.forEach(
            (rawKey, rawValue) -> {
              Object key = starlarkifyValue(mu, rawKey, pkg);
              // BuildType.Selector constructor transforms `None` values of selector branches into
              // Java nulls if the selector original type's default value is null. We need to
              // reverse this transformation.
              Object mapVal =
                  rawValue == null && selector.getOriginalType().getDefaultValue() == null
                      ? Starlark.NONE
                      : starlarkifyValue(mu, rawValue, pkg);
              if (key != null && mapVal != null) {
                m.put(key, mapVal);
              }
            });
        Dict<?, ?> selectorDict = m.build(mu);
        if (!selectorDict.isEmpty()) {
          selectors.add(new SelectorValue(selectorDict, selector.getNoMatchError()));
        }
      }
      if (selectors.isEmpty()) {
        return null;
      } else {
        try {
          return SelectorList.of(selectors);
        } catch (EvalException e) {
          return null;
        }
      }
    }

    if (val instanceof StarlarkValue) {
      return val;
    }

    // Cannot represent as a Starlark value.
    return null;
  }

  @Override
  public Sequence<?> subpackages(
      Sequence<?> include, Sequence<?> exclude, boolean allowEmpty, StarlarkThread thread)
      throws EvalException, InterruptedException {
    Package.Builder pkgBuilder = Package.Builder.fromOrFailAllowBuildOnly(thread, "subpackages()");

    List<String> includes = Types.STRING_LIST.convert(include, "'subpackages' argument");
    List<String> excludes = Types.STRING_LIST.convert(exclude, "'subpackages' argument");

    List<String> matches =
        runGlobOperation(
            pkgBuilder, thread, includes, excludes, Globber.Operation.SUBPACKAGES, allowEmpty);
    if (!matches.isEmpty()) {
      try {
        matches.sort(naturalOrder());
      } catch (UnsupportedOperationException e) {
        matches = ImmutableList.sortedCopyOf(naturalOrder(), matches);
      }
    }
    return StarlarkList.copyOf(thread.mutability(), matches);
  }

  private List<String> runGlobOperation(
      Package.Builder pkgBuilder,
      StarlarkThread thread,
      List<String> includes,
      List<String> excludes,
      Globber.Operation operation,
      boolean allowEmpty)
      throws EvalException, InterruptedException {
    Semaphore cpuSemaphore = pkgBuilder.getCpuBoundSemaphore();
    try {
      if (cpuSemaphore != null) {
        // Throwing exceptions inside the try block before this release could lead to the semaphore
        // being acquired more times than it is released.
        cpuSemaphore.release();
      }
      // getGlobber() is not null because we're called from glob() and subpackages(), both of which
      // are guarded with Package.Builder.fromOrFailDisallowingWorkspace().
      Globber.Token globToken =
          pkgBuilder.getGlobber().runAsync(includes, excludes, operation, allowEmpty);
      return pkgBuilder.getGlobber().fetchUnsorted(globToken);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Exception processing includes=%s, excludes=%s)", includes, excludes);
      String errorMessage =
          String.format(
              "error globbing [%s]%s op=%s: %s",
              Joiner.on(", ").join(includes),
              excludes.isEmpty() ? "" : " - [" + Joiner.on(", ").join(excludes) + "]",
              operation,
              e.getMessage());
      Location loc = thread.getCallerLocation();
      Event error =
          switch (e) {
            case DetailedIOException detailed ->
                Package.errorWithDetailedExitCode(
                    loc, errorMessage, detailed.getDetailedExitCode());
            case FileSymlinkException symlink ->
                Package.error(loc, errorMessage, Code.EVAL_GLOBS_SYMLINK_ERROR);
            // If there are other IOExceptions that can result from user error, they should be
            // tested for here. Currently, FileNotFoundException is not one of those, because globs
            // only encounter that error in the presence of an inconsistent filesystem.
            default -> Package.error(loc, errorMessage, Code.GLOB_IO_EXCEPTION);
          };
      pkgBuilder.getLocalEventHandler().handle(error);
      pkgBuilder.setIOException(e, errorMessage, error.getProperty(DetailedExitCode.class));
      return ImmutableList.of();
    } catch (BadGlobException e) {
      throw new EvalException(e);
    } finally {
      if (cpuSemaphore != null) {
        cpuSemaphore.acquire();
      }
    }
  }
}
