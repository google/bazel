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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AutoloadSymbols;
import com.google.devtools.build.lib.packages.AutoloadSymbols.AutoloadException;
import com.google.devtools.build.lib.packages.BazelStarlarkEnvironment;
import com.google.devtools.build.lib.packages.BazelStarlarkEnvironment.InjectionException;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction.InlineCacheManager;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.skyframe.RecordingSkyFunctionEnvironment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;

// TODO(#11437): Update the design doc to change `@builtins` -> `@_builtins`.

// TODO(#11437): Add support to BzlLoadCycleReporter to pretty-print cycles involving
// @_builtins.

// TODO(#11437): Add tombstone feature: If a native symbol is a tombstone object, this signals to
// StarlarkBuiltinsFunction that the corresponding symbol *must* be defined by @_builtins.
// Furthermore, if exports.bzl also says the symbol is a tombstone, any attempt to use it results
// in failure, as if the symbol doesn't exist at all (or with a user-friendly error message saying
// to migrate by adding a load()). Combine tombstones with reading the current incompatible flags
// within @_builtins for awesomeness.

// TODO(#11437, #11954, #11983): To the extent that BUILD-loaded .bzls and WORKSPACE-loaded .bzls
// have the same environment, builtins injection should apply to both of them, not just to
// BUILD-loaded .bzls.

/**
 * A Skyframe function that evaluates the {@code @_builtins} pseudo-repository and reports the
 * values exported by {@link #EXPORTS_ENTRYPOINT}. The {@code @_builtins} pseudo-repository shares a
 * repo mapping with the {@code @bazel_tools} repository.
 *
 * <p>The process of "builtins injection" refers to evaluating this Skyfunction and applying its
 * result to {@link BzlLoadFunction}'s computation. See also the <a
 * href="https://docs.google.com/document/d/1GW7UVo1s9X0cti9OMgT3ga5ozKYUWLPk9k8c4-34rC4">design
 * doc</a>:
 *
 * <p>This function has two trivial keys, so there can only be two values in the build at a time.
 * First key/value (without autoloads) has a single dependency, on the result of evaluating the
 * exports.bzl file to a {@link BzlLoadValue}. Second key/value has dependencies on the results of
 * extenally loaded symbols and rules. For more information on the second value see {@link
 * AutoloadSymbols}.
 *
 * <p>This function supports a special "inlining" mode, similar to {@link BzlLoadFunction} (see that
 * class's javadoc and code comments). Whenever we inline {@link BzlLoadFunction} we also inline
 * {@link StarlarkBuiltinsFunction} (and {@link StarlarkBuiltinsFunction}'s calls to {@link
 * BzlLoadFunction} are then themselves inlined!). Similar to {@link BzlLoadFunction}'s inlining, we
 * cache the result of this computation, and this caching is managed by {@link
 * BzlLoadFunction.InlineCacheManager}. But since there's only a single {@link
 * StarlarkBuiltinsValue} node and we don't need to worry about that node's value changing at future
 * invocations or subsequent versions (see {@link InlineCacheManager#reset} for why), our caching
 * strategy is much simpler and we don't need to bother inlining deps of the Skyframe subgraph.
 */
public class StarlarkBuiltinsFunction implements SkyFunction {

  /**
   * The label where {@code @_builtins} symbols are exported from. (Note that this is never
   * conflated with an actual repository named "{@code @_builtins}" because 1) it is only ever
   * accessed through a special SkyKey, and 2) we disallow the user from defining a repo named
   * {@code @_builtins} to avoid confusion.)
   */
  static final Label EXPORTS_ENTRYPOINT =
      Label.parseCanonicalUnchecked("@_builtins//:exports.bzl"); // unused

  /**
   * Key for loading exports.bzl. Note that {@code keyForBuiltins} (as opposed to {@code
   * keyForBuild}) ensures we can resolve {@code @_builtins}, which is otherwise inaccessible. It
   * also prevents us from cyclically requesting StarlarkBuiltinsFunction again to evaluate
   * exports.bzl.
   */
  static final BzlLoadValue.Key EXPORTS_ENTRYPOINT_KEY =
      BzlLoadValue.keyForBuiltins(EXPORTS_ENTRYPOINT);

  // Used to obtain the injected environment.
  private final BazelStarlarkEnvironment bazelStarlarkEnvironment;

  public StarlarkBuiltinsFunction(BazelStarlarkEnvironment bazelStarlarkEnvironment) {
    this.bazelStarlarkEnvironment = bazelStarlarkEnvironment;
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws StarlarkBuiltinsFunctionException, InterruptedException {
    try {
      return computeInternal(
          env,
          bazelStarlarkEnvironment,
          ((StarlarkBuiltinsValue.Key) skyKey.argument()).isWithAutoloads(),
          /* inliningState= */ null,
          /* bzlLoadFunction= */ null);
    } catch (BuiltinsFailedException e) {
      throw new StarlarkBuiltinsFunctionException(e);
    }
  }

  /**
   * Computes this Skyfunction under inlining of {@link BzlLoadFunction}, forwarding the given
   * inlining state.
   *
   * <p>The given Skyframe environment must be a {@link RecordingSkyFunctionEnvironment}. It is
   * unwrapped before calling {@link BzlLoadFunction}'s inlining code path.
   *
   * <p>Returns null on Skyframe restart or error.
   */
  @Nullable
  public static StarlarkBuiltinsValue computeInline(
      StarlarkBuiltinsValue.Key key,
      BzlLoadFunction.InliningState inliningState,
      BazelStarlarkEnvironment bazelStarlarkEnvironment,
      BzlLoadFunction bzlLoadFunction)
      throws BuiltinsFailedException, InterruptedException {
    checkNotNull(bzlLoadFunction.inlineCacheManager);
    StarlarkBuiltinsValue cachedBuiltins = bzlLoadFunction.inlineCacheManager.builtinsRef.get();
    if (cachedBuiltins != null) {
      // See the comment in InlineCacheManager#reset for why it's sound to not inline deps of the
      // entire subgraph here.
      return cachedBuiltins;
    }

    // See BzlLoadFunction#computeInline and BzlLoadFunction.InliningState for an explanation of the
    // inlining mechanism and its invariants. For our purposes, the Skyframe environment to use
    // comes from inliningState.
    StarlarkBuiltinsValue computedBuiltins =
        computeInternal(
            inliningState.getEnvironment(),
            bazelStarlarkEnvironment,
            key.isWithAutoloads(),
            inliningState,
            bzlLoadFunction);
    if (computedBuiltins == null) {
      return null;
    }
    // There's a benign race where multiple threads may try to compute-and-cache the single builtins
    // value. Ensure the value computed by winner of that race gets used by everyone.
    bzlLoadFunction.inlineCacheManager.builtinsRef.compareAndSet(null, computedBuiltins);
    return bzlLoadFunction.inlineCacheManager.builtinsRef.get();
  }

  // bzlLoadFunction and inliningState are non-null iff using inlining code path.
  @Nullable
  private static StarlarkBuiltinsValue computeInternal(
      Environment env,
      BazelStarlarkEnvironment bazelStarlarkEnvironment,
      boolean isWithAutoloads,
      @Nullable BzlLoadFunction.InliningState inliningState,
      @Nullable BzlLoadFunction bzlLoadFunction)
      throws BuiltinsFailedException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }
    // Return the empty value if builtins injection is disabled.
    if (starlarkSemantics.get(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH).isEmpty()) {
      return StarlarkBuiltinsValue.createEmpty(starlarkSemantics);
    }
    AutoloadSymbols autoloadSymbols = AutoloadSymbols.AUTOLOAD_SYMBOLS.get(env);
    if (autoloadSymbols == null) {
      return null;
    }

    if (autoloadSymbols.isEnabled()
        && starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_BZLMOD)) {
      // We can't do autoloads where the rules are implemented (disabling them when running in
      // main repository named rules_python)
      ModuleFileValue mainModule =
          (ModuleFileValue) env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE);
      if (mainModule == null) {
        return null;
      }
      if (autoloadSymbols.autoloadsDisabledForRepo(mainModule.getModule().getName())) {
        isWithAutoloads = false;
      }
    }

    // Load exports.bzl. If we were requested using inlining, make sure to inline the call back into
    // BzlLoadFunction.
    // If requested also loads "autoloads": rules and providers from external repositories.
    BzlLoadValue exportsValue;
    try {
      if (inliningState == null) {
        exportsValue =
            (BzlLoadValue)
                env.getValueOrThrow(EXPORTS_ENTRYPOINT_KEY, BzlLoadFailedException.class);
      } else {
        exportsValue = bzlLoadFunction.computeInline(EXPORTS_ENTRYPOINT_KEY, inliningState);
      }
    } catch (BzlLoadFailedException ex) {
      throw BuiltinsFailedException.errorEvaluatingBuiltinsBzls(ex);
    }

    ImmutableMap<String, BzlLoadValue> autoBzlLoadValues;
    try {
      ImmutableMap<String, BzlLoadValue.Key> autoBzlLoadKeys =
          isWithAutoloads ? autoloadSymbols.getLoadKeys(env) : ImmutableMap.of();
      if (autoBzlLoadKeys == null) {
        return null;
      }
      ImmutableMap.Builder<String, BzlLoadValue> autoBzlLoadValuesBuilder =
          ImmutableMap.builderWithExpectedSize(autoBzlLoadKeys.size());
      if (inliningState == null) {
        SkyframeLookupResult values = env.getValuesAndExceptions(autoBzlLoadKeys.values());
        for (var symbolKeyEntry : autoBzlLoadKeys.entrySet()) {
          String symbol = symbolKeyEntry.getKey();
          BzlLoadValue value =
              (BzlLoadValue)
                  values.getOrThrow(symbolKeyEntry.getValue(), BzlLoadFailedException.class);
          if (value != null) {
            autoBzlLoadValuesBuilder.put(symbol, value);
          }
        }
      } else {
        for (var symbolKeyEntry : autoBzlLoadKeys.entrySet()) {
          String symbol = symbolKeyEntry.getKey();
          BzlLoadValue value =
              bzlLoadFunction.computeInline(symbolKeyEntry.getValue(), inliningState);
          if (value != null) {
            autoBzlLoadValuesBuilder.put(symbol, value);
          }
        }
      }
      autoBzlLoadValues = autoBzlLoadValuesBuilder.buildOrThrow();
    } catch (BzlLoadFailedException ex) {
      throw BuiltinsFailedException.errorEvaluatingAutoloadedBzls(
          ex, starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_BZLMOD));
    }
    if (env.valuesMissing()) {
      return null;
    }

    // Compute digest of exports.bzl and possibly externally loaded symbols (Bazel)
    byte[] transitiveDigest = exportsValue.getTransitiveDigest();
    if (!autoBzlLoadValues.isEmpty()) {
      Fingerprint fp = new Fingerprint();
      fp.addBytes(transitiveDigest);
      for (BzlLoadValue value : autoBzlLoadValues.values()) {
        fp.addBytes(value.getTransitiveDigest());
      }
      transitiveDigest = fp.digestAndReset();
    }

    // Apply declarations of exports.bzl to the native predeclared symbols.
    Module module = exportsValue.getModule();
    try {
      ImmutableMap<String, Object> exportedToplevels = getDict(module, "exported_toplevels");
      ImmutableMap<String, Object> exportedRules = getDict(module, "exported_rules");
      ImmutableMap<String, Object> exportedToJava = getDict(module, "exported_to_java");
      ImmutableMap<String, Object> predeclaredForBuildBzl =
          bazelStarlarkEnvironment.createBuildBzlEnvUsingInjection(
              exportedToplevels,
              exportedRules,
              starlarkSemantics.get(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE));
      ImmutableMap<String, Object> predeclaredForWorkspaceBzl =
          bazelStarlarkEnvironment.createWorkspaceBzlEnvUsingInjection(
              exportedToplevels,
              exportedRules,
              starlarkSemantics.get(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE));
      ImmutableMap<String, Object> predeclaredForBuild =
          bazelStarlarkEnvironment.createBuildEnvUsingInjection(
              exportedRules,
              starlarkSemantics.get(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE));

      // Apply declarations of externally loaded symbols to the native predeclared symbols.
      ImmutableMap<String, Object> newSymbols = autoloadSymbols.processLoads(autoBzlLoadValues);
      predeclaredForBuild =
          autoloadSymbols.modifyBuildEnv(isWithAutoloads, predeclaredForBuild, newSymbols);
      predeclaredForBuildBzl =
          autoloadSymbols.modifyBuildBzlEnv(isWithAutoloads, predeclaredForBuildBzl, newSymbols);

      return StarlarkBuiltinsValue.create(
          predeclaredForBuildBzl,
          predeclaredForWorkspaceBzl,
          predeclaredForBuild,
          exportedToJava,
          transitiveDigest,
          starlarkSemantics);
    } catch (EvalException | InjectionException ex) {
      throw BuiltinsFailedException.errorApplyingExports(ex);
    } catch (AutoloadException ex) {
      throw new BuiltinsFailedException(
          String.format("Failed to apply symbols loaded externally: %s", ex.getMessage()),
          ex,
          Transience.PERSISTENT);
    }
  }

  /**
   * Attempts to retrieve the string-keyed dict named {@code dictName} from the given {@code
   * module}.
   *
   * @return a copy of the dict mappings on success
   * @throws EvalException if the symbol isn't present or is not a dict whose keys are all strings
   */
  @Nullable
  private static ImmutableMap<String, Object> getDict(Module module, String dictName)
      throws EvalException {
    Object value = module.getGlobal(dictName);
    if (value == null) {
      throw Starlark.errorf("expected a '%s' dictionary to be defined", dictName);
    }
    return ImmutableMap.copyOf(Dict.cast(value, String.class, Object.class, dictName + " dict"));
  }

  /**
   * An exception that occurs while trying to determine the injected builtins.
   *
   * <p>This exception type typically wraps a {@link BzlLoadFailedException} and is wrapped by a
   * {@link BzlLoadFailedException} in turn.
   */
  static final class BuiltinsFailedException extends Exception {

    private final Transience transience;

    private BuiltinsFailedException(String errorMessage, Exception cause, Transience transience) {
      super(errorMessage, cause);
      this.transience = transience;
    }

    Transience getTransience() {
      return transience;
    }

    static BuiltinsFailedException errorEvaluatingBuiltinsBzls(BzlLoadFailedException cause) {
      return errorEvaluatingBuiltinsBzls(cause, cause.getTransience());
    }

    static BuiltinsFailedException errorEvaluatingBuiltinsBzls(
        Exception cause, Transience transience) {
      return new BuiltinsFailedException(
          String.format("Failed to load builtins sources: %s", cause.getMessage()),
          cause,
          transience);
    }

    static BuiltinsFailedException errorEvaluatingAutoloadedBzls(
        BzlLoadFailedException cause, boolean bzlmodEnabled) {
      String additionalMessage =
          bzlmodEnabled
              ? ""
              : " Most likely you need to upgrade the version of rules repository in the"
                  + " WORKSPACE file.";
      return new BuiltinsFailedException(
          String.format(
              "Failed to autoload external symbols: %s%s", cause.getMessage(), additionalMessage),
          cause,
          cause.getTransience());
    }

    static BuiltinsFailedException errorApplyingExports(Exception cause) {
      return new BuiltinsFailedException(
          String.format("Failed to apply declared builtins: %s", cause.getMessage()),
          cause,
          Transience.PERSISTENT);
    }
  }

  /** The exception type thrown by {@link StarlarkBuiltinsFunction}. */
  static final class StarlarkBuiltinsFunctionException extends SkyFunctionException {

    private StarlarkBuiltinsFunctionException(BuiltinsFailedException cause) {
      super(cause, cause.transience);
    }
  }
}
