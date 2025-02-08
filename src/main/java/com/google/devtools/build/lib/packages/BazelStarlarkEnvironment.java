// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.GuardedValue;
import net.starlark.java.eval.Starlark;

// TODO(adonovan): move skyframe.PackageFunction into lib.packages so we needn't expose this and
// the other env-building functions.
/**
 * This class encapsulates knowledge of how to set up the Starlark environment for BUILD, WORKSPACE,
 * and bzl file evaluation, including the top-level predeclared symbols, the {@code native} module,
 * and the special environment for {@code @_builtins} bzl evaluation.
 *
 * <p>The set of available symbols is determined by
 *
 * <ol>
 *   <li>Gathering a fixed set of top-level symbols that are present in all versions of Bazel. This
 *       is handled by {@link StarlarkGlobals}.
 *   <li>Gathering additional toplevels and rules registered on the {@link
 *       ConfiguredRuleClassProvider}.
 *   <li>Applying builtins injection (see {@link StarlarkBuiltinsFunction}), if applicable.
 * </ol>
 *
 * <p>The end result of (1) and (2) is constant for any given Bazel binary and is cached by an
 * instance of this class upon construction. The final environment, which takes into account
 * builtins injection, is obtained by calling methods on this class during Skyframe evaluation; the
 * result is cached in {@link StarlarkBuiltinsValue}.
 *
 * <p>There are a few exceptions where this class is not the final word on the environment:
 *
 * <ul>
 *   <li>The WORKSPACE file's environment is setup with the help of {@link
 *       WorkspaceFactory#getDefaultEnvironment}.
 *   <li>If a prelude file is in use, its bindings are added to the ones this class specifies for
 *       BUILD files. This happens in {@link PackageFunction}.
 * </ul>
 */
public final class BazelStarlarkEnvironment {

  // TODO(#11954): Eventually the BUILD and WORKSPACE bzl dialects should converge. Right now they
  // only differ on the "native" object.

  // All of the environments stored in these fields exclude the symbols in {@link
  // Starlark#UNIVERSE}, which the interpreter adds automatically.

  // Constructor param, used in this class but also re-exported to clients.
  private final StarlarkGlobals starlarkGlobals;

  // The following fields correspond to the constructor params of the same name. These include only
  // the params that are needed by injection. See the constructor for javadoc.
  private final ImmutableMap<String, ?> ruleFunctions;
  private final ImmutableMap<String, Object> registeredBzlToplevels;
  private final ImmutableMap<String, Object> workspaceBzlNativeBindings;

  /**
   * The top-level predeclared symbols, excluding {@code native}, for a .bzl file (regardless of who
   * loads it), before injection.
   */
  private final ImmutableMap<String, Object> bzlToplevelsWithoutNative;
  /** The {@code native} module fields for a BUILD-loaded bzl module, before builtins injection. */
  private final ImmutableMap<String, Object> uninjectedBuildBzlNativeBindings;
  /**
   * The top-level predeclared symbols (including {@code native}) for a BUILD-loaded bzl module,
   * before builtins injection.
   */
  private final ImmutableMap<String, Object> uninjectedBuildBzlEnv;
  /** The top-level predeclared symbols for BUILD files, before builtins injection and prelude. */
  private final ImmutableMap<String, Object> uninjectedBuildEnv;
  /**
   * The top-level predeclared symbols for a WORKSPACE-loaded bzl module, before builtins injection.
   */
  private final ImmutableMap<String, Object> uninjectedWorkspaceBzlEnv;
  /** The top-level predeclared symbols for a bzl module in the {@code @_builtins} pseudo-repo. */
  private final ImmutableMap<String, Object> builtinsBzlEnv;

  /** The top-level predeclared symbols for a MODULE.bazel file. */
  private final ImmutableMap<String, Object> moduleBazelEnv;

  /** The top-level predeclared symbols for a REPO.bazel file. */
  private final ImmutableMap<String, Object> repoBazelEnv;

  /**
   * Constructs a new {@code BazelStarlarkEnvironment} that will have complete knowledge of the
   * proper Starlark symbols available in each context, with and without injection.
   *
   * @param ruleFunctions a map from a rule class name (e.g. "java_library") to the (uninjected)
   *     Starlark callable that instantiates it
   * @param registeredBuildFileToplevels a map of additional (i.e., registered with the rule class
   *     provider) top-level symbols for BUILD files, prior to builtins injection. These symbols are
   *     also added to the {@code native} object. Does not include rules.
   * @param registeredBzlToplevels a map of additional (i.e., registered with the rule class
   *     provider) top-level symbols for .bzl files, prior to builtins injection
   * @param workspaceBzlNativeBindings entries available in the {@code native} object for
   *     WORKSPACE-loaded .bzl files
   * @param builtinsInternals a set of symbols to be made available to {@code @_builtins} .bzls
   *     under the {@code _builtins.internal} object. These symbols are not exposed to user .bzl
   *     code and do not constitute a public or stable API if not exposed through another means.
   */
  public BazelStarlarkEnvironment(
      StarlarkGlobals starlarkGlobals,
      ImmutableMap<String, ?> ruleFunctions,
      ImmutableMap<String, Object> registeredBuildFileToplevels,
      ImmutableMap<String, Object> registeredBzlToplevels,
      ImmutableMap<String, Object> workspaceBzlNativeBindings,
      ImmutableMap<String, Object> builtinsInternals) {

    this.starlarkGlobals = starlarkGlobals;
    this.ruleFunctions = ruleFunctions;
    this.registeredBzlToplevels = registeredBzlToplevels;
    this.workspaceBzlNativeBindings = workspaceBzlNativeBindings;

    this.bzlToplevelsWithoutNative =
        createBzlToplevelsWithoutNative(starlarkGlobals, registeredBzlToplevels);
    this.uninjectedBuildBzlNativeBindings =
        createUninjectedBuildBzlNativeBindings(
            starlarkGlobals, ruleFunctions, registeredBuildFileToplevels);
    this.uninjectedBuildBzlEnv =
        createUninjectedBuildBzlEnv(bzlToplevelsWithoutNative, uninjectedBuildBzlNativeBindings);
    this.uninjectedWorkspaceBzlEnv =
        createWorkspaceBzlEnv(bzlToplevelsWithoutNative, workspaceBzlNativeBindings);
    this.builtinsBzlEnv =
        createBuiltinsBzlEnv(
            starlarkGlobals,
            builtinsInternals,
            uninjectedBuildBzlNativeBindings,
            uninjectedBuildBzlEnv);
    this.uninjectedBuildEnv =
        createUninjectedBuildEnv(starlarkGlobals, ruleFunctions, registeredBuildFileToplevels);
    this.moduleBazelEnv = starlarkGlobals.getModuleToplevels();
    this.repoBazelEnv = starlarkGlobals.getRepoToplevels();
  }

  /**
   * Returns a {@link StarlarkGlobals} instance.
   *
   * <p>In practice, {@link StarlarkGlobals} is a singleton, so this accessor is really about
   * retrieving {@link StarlarkGlobalsImpl#INSTANCE} without requiring a dependency on the
   * lib/analysis/ package.
   */
  public StarlarkGlobals getStarlarkGlobals() {
    return starlarkGlobals;
  }

  /**
   * Returns the contents of the "native" object for BUILD-loaded bzls, not accounting for builtins
   * injection.
   */
  public ImmutableMap<String, Object> getUninjectedBuildBzlNativeBindings() {
    return uninjectedBuildBzlNativeBindings;
  }

  /** Returns the contents of the "native" object for WORKSPACE-loaded bzls. */
  public ImmutableMap<String, Object> getWorkspaceBzlNativeBindings() {
    return workspaceBzlNativeBindings;
  }

  /**
   * Returns the original environment for BUILD-loaded bzl files, not accounting for builtins
   * injection. Excludes symbols in {@link Starlark#UNIVERSE}.
   *
   * <p>The post-injection environment may differ from this one by what symbols a name is bound to,
   * but the set of symbols remains the same.
   */
  public ImmutableMap<String, Object> getUninjectedBuildBzlEnv() {
    return uninjectedBuildBzlEnv;
  }

  /**
   * Returns the original environment for BUILD files, not accounting for builtins injection or
   * application of the prelude. Excludes symbols in {@link Starlark#UNIVERSE}.
   *
   * <p>Applying builtins injection may update name bindings, but not add or remove them. I.e. some
   * names may refer to different symbols but the static set of names remains the same. Applying the
   * prelude file may update and add name bindings but not remove them.
   */
  public ImmutableMap<String, Object> getUninjectedBuildEnv() {
    return uninjectedBuildEnv;
  }

  /**
   * Returns the environment for WORKSPACE-loaded bzl files before builtins injection. Excludes
   * symbols in {@link Starlark#UNIVERSE}.
   */
  public ImmutableMap<String, Object> getUninjectedWorkspaceBzlEnv() {
    return uninjectedWorkspaceBzlEnv;
  }

  /**
   * Returns the environment for bzl files in the {@code @_builtins} pseudo-repository. Excludes
   * symbols in {@link Starlark#UNIVERSE}.
   */
  public ImmutableMap<String, Object> getBuiltinsBzlEnv() {
    return builtinsBzlEnv;
  }

  /** Returns the environment for MODULE.bazel files. */
  public ImmutableMap<String, Object> getModuleBazelEnv() {
    return moduleBazelEnv;
  }

  /** Returns the environment for REPO.bazel files. */
  public ImmutableMap<String, Object> getRepoBazelEnv() {
    return repoBazelEnv;
  }

  private static ImmutableMap<String, Object> createBzlToplevelsWithoutNative(
      StarlarkGlobals starlarkGlobals, Map<String, Object> registeredBzlToplevels) {
    ImmutableMap.Builder<String, Object> env = new ImmutableMap.Builder<>();
    env.putAll(starlarkGlobals.getFixedBzlToplevels());
    env.putAll(registeredBzlToplevels);
    return env.buildOrThrow();
  }

  /**
   * Produces everything that would be in the {@code native} object for BUILD-loaded bzl files if
   * builtins injection didn't happen.
   */
  private static ImmutableMap<String, Object> createUninjectedBuildBzlNativeBindings(
      StarlarkGlobals starlarkGlobals,
      Map<String, ?> ruleFunctions,
      Map<String, Object> registeredBuildFileToplevels) {
    ImmutableMap.Builder<String, Object> env = new ImmutableMap.Builder<>();
    env.putAll(starlarkGlobals.getFixedBuildFileToplevelsSharedWithNative());
    env.putAll(ruleFunctions);
    env.putAll(registeredBuildFileToplevels);
    return env.buildOrThrow();
  }

  /** Constructs a "native" module object with the given contents. */
  private static Object createNativeModule(Map<String, Object> bindings) {
    return StructProvider.STRUCT.create(bindings, "no native function or rule '%s'");
  }

  private static ImmutableMap<String, Object> createUninjectedBuildBzlEnv(
      Map<String, Object> bzlToplevelsWithoutNative,
      Map<String, Object> uninjectedBuildBzlNativeBindings) {
    ImmutableMap.Builder<String, Object> env = new ImmutableMap.Builder<>();
    env.putAll(bzlToplevelsWithoutNative);

    // Determine the "native" module.
    // TODO(#11954): Use the same "native" object for both BUILD- and WORKSPACE-loaded .bzls, and
    // just have it be a dynamic error to call the wrong thing at the wrong time. This is a breaking
    // change.
    env.put("native", createNativeModule(uninjectedBuildBzlNativeBindings));

    return env.buildOrThrow();
  }

  private static ImmutableMap<String, Object> createUninjectedBuildEnv(
      StarlarkGlobals starlarkGlobals,
      Map<String, ?> ruleFunctions,
      Map<String, Object> registeredBuildFileToplevels) {
    ImmutableMap.Builder<String, Object> env = ImmutableMap.builder();
    env.putAll(starlarkGlobals.getFixedBuildFileToplevelsSharedWithNative());
    env.putAll(starlarkGlobals.getFixedBuildFileToplevelsNotInNative());
    env.putAll(ruleFunctions);
    env.putAll(registeredBuildFileToplevels);
    return env.buildOrThrow();
  }

  private static ImmutableMap<String, Object> createWorkspaceBzlEnv(
      Map<String, Object> bzlToplevelsWithoutNative,
      Map<String, Object> workspaceBzlNativeBindings) {
    ImmutableMap.Builder<String, Object> env = new ImmutableMap.Builder<>();
    env.putAll(bzlToplevelsWithoutNative);

    // See above comments for native in BUILD bzls.
    env.put("native", createNativeModule(workspaceBzlNativeBindings));

    return env.buildOrThrow();
  }

  private static ImmutableMap<String, Object> createBuiltinsBzlEnv(
      StarlarkGlobals starlarkGlobals,
      Map<String, Object> builtinsInternals,
      Map<String, Object> uninjectedBuildBzlNativeBindings,
      Map<String, Object> uninjectedBuildBzlEnv) {
    Map<String, Object> env = new HashMap<>(starlarkGlobals.getFixedBzlToplevels());

    // For _builtins.toplevel, replace all GuardedValues with the underlying value;
    // StarlarkSemantics flags do not affect @_builtins.
    //
    // We do this because otherwise we'd need to differentiate the _builtins.toplevel object (and
    // therefore the @_builtins environment) based on StarlarkSemantics. That seems unnecessary.
    // Instead we trust @_builtins to not misuse flag-guarded features, same as native code.
    //
    // If foo is flag-guarded (either experimental or incompatible), it is unconditionally visible
    // as _builtins.toplevel.foo. It is legal to list it in exported_toplevels unconditionally, but
    // the flag still controls whether the symbol is actually visible to user code.
    Map<String, Object> unwrappedBuildBzlSymbols = new HashMap<>();
    for (Map.Entry<String, Object> entry : uninjectedBuildBzlEnv.entrySet()) {
      Object symbol = entry.getValue();
      if (symbol instanceof GuardedValue) {
        symbol = ((GuardedValue) symbol).getObject();
      }
      unwrappedBuildBzlSymbols.put(entry.getKey(), symbol);
    }

    Object builtinsModule =
        new BuiltinsInternalModule(
            createNativeModule(uninjectedBuildBzlNativeBindings),
            // createNativeModule() is good enough for the "toplevel" and "internal" objects too.
            createNativeModule(unwrappedBuildBzlSymbols),
            createNativeModule(builtinsInternals));
    Object conflictingValue = env.put("_builtins", builtinsModule);
    Preconditions.checkState(
        conflictingValue == null, "'_builtins' name is reserved for builtins injection");

    return ImmutableMap.copyOf(env);
  }

  /**
   * Throws {@link InjectionException} with an appropriate error message if the given {@code symbol}
   * is not in both {@code existingSymbols} and {@code injectableSymbols}. {@code kind} is a string
   * describing the domain of {@code symbol}.
   */
  private static void validateSymbolIsInjectable(
      String symbol, Set<String> existingSymbols, Set<String> injectableSymbols, String kind)
      throws InjectionException {
    if (!existingSymbols.contains(symbol)) {
      throw new InjectionException(
          String.format(
              "Injected %s '%s' must override an existing one by that name", kind, symbol));
    } else if (!injectableSymbols.contains(symbol)) {
      throw new InjectionException(
          String.format("Cannot override '%s' with an injected %s", symbol, kind));
    }
  }

  /** Given a string prefixed with + or -, returns that prefix character, or null otherwise. */
  @Nullable
  private static Character getKeyPrefix(String key) {
    if (key.isEmpty()) {
      return null;
    }
    char prefix = key.charAt(0);
    if (!(prefix == '+' || prefix == '-')) {
      return null;
    }
    return prefix;
  }

  /**
   * Given a string prefixed with + or -, returns the remainder of the string, or the whole string
   * otherwise.
   */
  private static String getKeySuffix(String key) {
    return getKeyPrefix(key) == null ? key : key.substring(1);
  }

  /**
   * Given a list of strings representing the +/- prefixed items in {@code
   * --experimental_builtins_injection_override}, returns a map from each item to a Boolean
   * indicating whether it last appeared with the + suffix (True) or - suffix (False).
   *
   * @throws InjectionException if an item is not prefixed with either "+" or "-"
   */
  private static Map<String, Boolean> parseInjectionOverridesList(List<String> overrides)
      throws InjectionException {
    HashMap<String, Boolean> result = new HashMap<>();
    for (String prefixedItem : overrides) {
      Character prefix = getKeyPrefix(prefixedItem);
      if (prefix == null) {
        throw new InjectionException(
            String.format("Invalid injection override item: '%s'", prefixedItem));
      }
      result.put(prefixedItem.substring(1), prefix == '+');
    }
    return result;
  }

  /**
   * Given an exports dict key, and an override map, return whether injection should be applied for
   * that key.
   */
  private static boolean injectionApplies(String key, Map<String, Boolean> overrides) {
    Character prefix = getKeyPrefix(key);
    if (prefix == null) {
      // Unprefixed; overrides don't get a say in the matter.
      return true;
    }
    Boolean override = overrides.get(key.substring(1));
    if (override == null) {
      return prefix == '+';
    } else {
      return override;
    }
  }

  /**
   * Constructs an environment for a BUILD-loaded bzl file based on the default environment, the
   * maps corresponding to the {@code exported_toplevels} and {@code exported_rules} dicts, and the
   * value of {@code --experimental_builtins_injection_override}.
   *
   * <p>Injected symbols must override an existing symbol of that name. Furthermore, the overridden
   * symbol must be one that was registered on the rule class provider (e.g., {@code CcInfo} or
   * {@code cc_library}), not a fixed symbol that's always available (e.g., {@code provider} or
   * {@code glob}). Throws InjectionException if these conditions are not met.
   *
   * <p>Whether or not injection actually occurs for a given map key depends on its prefix (if any)
   * and the prefix of its appearance (if it appears at all) in the override list; see the
   * documentation for {@code --experimental_builtins_injection_override}. Non-injected symbols must
   * still obey the above constraints.
   *
   * @see com.google.devtools.build.lib.skyframe.StarlarkBuiltinsFunction
   */
  public ImmutableMap<String, Object> createBuildBzlEnvUsingInjection(
      Map<String, Object> exportedToplevels,
      Map<String, Object> exportedRules,
      List<String> overridesList)
      throws InjectionException {
    return createBzlEnvUsingInjection(
        exportedToplevels, exportedRules, overridesList, uninjectedBuildBzlNativeBindings);
  }

  /**
   * Constructs an environment for a WORKSPACE-loaded bzl file based on the default environment, the
   * maps corresponding to the {@code exported_toplevels} and {@code exported_rules} dicts, and the
   * value of {@code --experimental_builtins_injection_override}.
   *
   * @see com.google.devtools.build.lib.skyframe.StarlarkBuiltinsFunction
   */
  public ImmutableMap<String, Object> createWorkspaceBzlEnvUsingInjection(
      Map<String, Object> exportedToplevels,
      Map<String, Object> exportedRules,
      List<String> overridesList)
      throws InjectionException {
    return createBzlEnvUsingInjection(
        exportedToplevels, exportedRules, overridesList, workspaceBzlNativeBindings);
  }

  private ImmutableMap<String, Object> createBzlEnvUsingInjection(
      Map<String, Object> exportedToplevels,
      Map<String, Object> exportedRules,
      List<String> overridesList,
      Map<String, Object> nativeBase)
      throws InjectionException {
    Map<String, Boolean> overridesMap = parseInjectionOverridesList(overridesList);

    SequencedMap<String, Object> env = new LinkedHashMap<>(bzlToplevelsWithoutNative);

    // Determine "native" bindings.
    // TODO(#11954): See above comment in createUninjectedBuildBzlEnv.
    SequencedMap<String, Object> nativeBindings = new LinkedHashMap<>(nativeBase);
    for (Map.Entry<String, Object> entry : exportedRules.entrySet()) {
      String key = entry.getKey();
      String name = getKeySuffix(key);
      validateSymbolIsInjectable(name, nativeBindings.keySet(), ruleFunctions.keySet(), "rule");
      if (injectionApplies(key, overridesMap)) {
        nativeBindings.put(name, entry.getValue());
      }
    }
    env.put("native", createNativeModule(nativeBindings));

    // Determine top-level symbols.
    for (Map.Entry<String, Object> entry : exportedToplevels.entrySet()) {
      String key = entry.getKey();
      String name = getKeySuffix(key);
      validateSymbolIsInjectable(
          name,
          Sets.union(env.keySet(), Starlark.UNIVERSE.keySet()),
          registeredBzlToplevels.keySet(),
          "top-level symbol");
      if (injectionApplies(key, overridesMap)) {
        env.put(name, entry.getValue());
      }
    }

    return ImmutableMap.copyOf(env);
  }

  /**
   * Constructs an environment for a BUILD file based on the default environment, the map
   * corresponding to the {@code exported_rules} dict, and the value of {@code
   * --experimental_builtins_injection_override}.
   *
   * <p>Injected rule symbols must override an existing native rule of that name. Only rules may be
   * overridden in this manner, not generic built-ins such as {@code package} or {@code glob}.
   * Throws InjectionException if these conditions are not met.
   *
   * <p>Whether or not injection actually occurs for a given map key depends on its prefix (if any)
   * and the prefix of its appearance (if it appears at all) in the override list; see the
   * documentation for {@code --experimental_builtins_injection_override}. Non-injected symbols must
   * still obey the above constraints.
   */
  public ImmutableMap<String, Object> createBuildEnvUsingInjection(
      Map<String, Object> exportedRules, List<String> overridesList) throws InjectionException {
    Map<String, Boolean> overridesMap = parseInjectionOverridesList(overridesList);

    SequencedMap<String, Object> env = new LinkedHashMap<>(uninjectedBuildEnv);
    for (Map.Entry<String, Object> entry : exportedRules.entrySet()) {
      String key = entry.getKey();
      String name = getKeySuffix(key);
      validateSymbolIsInjectable(
          name,
          Sets.union(env.keySet(), Starlark.UNIVERSE.keySet()),
          ruleFunctions.keySet(),
          "rule");
      if (injectionApplies(key, overridesMap)) {
        env.put(name, entry.getValue());
      }
    }
    return ImmutableMap.copyOf(env);
  }

  /** Indicates a problem performing builtins injection. */
  public static final class InjectionException extends Exception {
    InjectionException(String message) {
      super(message);
    }
  }
}
