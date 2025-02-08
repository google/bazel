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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.analysis.util.MockRuleDefaults;
import com.google.devtools.build.lib.bazel.bzlmod.NonRegistryOverride;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.starlark.java.eval.FlagGuardedValue;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.StarlarkList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for Starlark builtin injection.
 *
 * <p>Essentially these are integration tests between {@link StarlarkBuiltinsFunction}, {@link
 * BzlLoadFunction}, and the rest of package loading.
 */
@RunWith(JUnit4.class)
public class BuiltinsInjectionTest extends BuildViewTestCase {

  /** A simple dummy rule that doesn't do anything. */
  private static final MockRule OVERRIDABLE_RULE = () -> MockRule.define("overridable_rule");

  /**
   * A dummy native rule that reads a value from {@code @_builtins}.
   *
   * <p>It looks up the symbol "builtins_defined_symbol" in exported_to_java, and prints its value
   * to the event handler.
   */
  private static final MockRule SANDWICH_RULE =
      () -> MockRule.factory(SandwichFactory.class).define("sandwich_rule");

  // Must be public due to reflective construction of rule factories.
  /** Factory for SANDWICH_RULE. (Javadoc'd to pacify linter.) */
  public static class SandwichFactory extends MockRuleDefaults.DefaultConfiguredTargetFactory {
    @Override
    public ConfiguredTarget create(RuleContext ruleContext)
        throws InterruptedException, RuleErrorException, ActionConflictException {
      Object value = ruleContext.getStarlarkDefinedBuiltin("builtins_defined_symbol");
      EventHandler handler = ruleContext.getAnalysisEnvironment().getEventHandler();
      handler.handle(Event.info("builtins_defined_symbol :: " + value.toString()));
      return super.create(ruleContext);
    }
  }

  /**
   * A dummy native rule that runs {@code @_builtins}-defined code.
   *
   * <p>It looks up the function listed as "builtins_defined_logic" in exported_to_java, and calls
   * it twice on an initially empty list. It prints both return values and the final value of the
   * list. On Starlark evaluation error, it reports a rule error.
   */
  private static final MockRule SANDWICH_LOGIC_RULE =
      () -> MockRule.factory(SandwichLogicFactory.class).define("sandwich_logic_rule");

  // Must be public due to reflective construction of rule factories.
  /** Factory for SANDWICH_LOGIC_RULE. (Javadoc'd to pacify linter.) */
  public static class SandwichLogicFactory extends MockRuleDefaults.DefaultConfiguredTargetFactory {
    @Override
    public ConfiguredTarget create(RuleContext ruleContext)
        throws InterruptedException, RuleErrorException, ActionConflictException {
      Mutability mu = ruleContext.getStarlarkThread().mutability();
      Object func = ruleContext.getStarlarkDefinedBuiltin("builtins_defined_logic");
      Object arg = StarlarkList.newList(mu);
      Object return1 =
          ruleContext.callStarlarkOrThrowRuleError(
              func, /*args=*/ ImmutableList.of(arg), /*kwargs=*/ ImmutableMap.of());
      Object return2 =
          ruleContext.callStarlarkOrThrowRuleError(
              func, /*args=*/ ImmutableList.of(arg), /*kwargs=*/ ImmutableMap.of());

      EventHandler handler = ruleContext.getAnalysisEnvironment().getEventHandler();
      handler.handle(Event.info("builtins_defined_logic call 1 :: " + return1.toString()));
      handler.handle(Event.info("builtins_defined_logic call 2 :: " + return2.toString()));
      handler.handle(Event.info("final list value :: " + arg.toString()));
      return super.create(ruleContext);
    }
  }

  /**
   * A dummy native rule that passes a Starlark rule context ({@code ctx}) object to
   * {@code @_builtins}-defined code.
   *
   * <p>It looks up "builtins_rule_impl_helper" in exported_to_java, and calls it with {@code ctx}
   * as its sole arg. The rule has a "content" string attribute and "out" output label attribute.
   * The Starlark helper function is responsible for registering an action to generate the output.
   */
  private static final MockRule SANDWICH_CTX_RULE =
      () ->
          MockRule.factory(SandwichCtxFactory.class)
              .define(
                  "sandwich_ctx_rule",
                  Attribute.attr("content", Type.STRING),
                  Attribute.attr("out", BuildType.OUTPUT));

  // Must be public due to reflective construction of rule factories.
  /** Factory for SANDWICH_CTX_RULE. (Javadoc'd to pacify linter.) */
  public static class SandwichCtxFactory extends MockRuleDefaults.DefaultConfiguredTargetFactory {
    @Override
    public ConfiguredTarget create(RuleContext ruleContext)
        throws InterruptedException, RuleErrorException, ActionConflictException {
      ruleContext.initStarlarkRuleContext();
      ruleContext.callStarlarkOrThrowRuleError(
          ruleContext.getStarlarkDefinedBuiltin("builtins_rule_impl_helper"),
          /*args=*/ ImmutableList.of(ruleContext.getStarlarkRuleContext()),
          /*kwargs=*/ ImmutableMap.of());
      // Don't dispatch to super.create(), which would attempt to register an action to produce
      // "out".
      return new RuleConfiguredTargetBuilder(ruleContext)
          .setFilesToBuild(
              NestedSetBuilder.wrap(Order.STABLE_ORDER, ruleContext.getOutputArtifacts()))
          .setRunfilesSupport(null, null)
          .add(RunfilesProvider.class, RunfilesProvider.simple(Runfiles.EMPTY))
          .build();
    }
  }

  @Override
  protected Iterable<String> getDefaultsForConfiguration() {
    // Override BuildViewTestCase's behavior of setting all sorts of extra options that don't exist
    // on our minimal rule class provider.
    // We do need the host platform. Set it to something trivial.
    return ImmutableList.of(
        "--host_platform=//minimal_buildenv/platforms:default",
        "--platforms=//minimal_buildenv/platforms:default",
        // Since this file tests builtins injection, replace the standard exec transition (which is
        // in builtins) with a no-op to avoid interference.
        "--experimental_exec_config=//pkg2:dummy_exec_platforms.bzl%noop_exec_transition");
  }

  @Override
  protected AnalysisMock getAnalysisMock() {
    return new AnalysisMock.Delegate(super.getAnalysisMock()) {
      @Override
      public ImmutableMap<String, NonRegistryOverride> getBuiltinModules(
          BlazeDirectories directories) {
        return ImmutableMap.of();
      }
    };
  }

  @Override
  protected void initializeMockClient() throws IOException {
    analysisMock.setupMockClient(mockToolsConfig);
    // Provide a trivial platform definition.
    mockToolsConfig.create(
        "minimal_buildenv/platforms/BUILD", //
        "platform(name = 'default')");
    // No-op exec transition:
    scratch.overwriteFile("pkg2/BUILD", "");
    scratch.file(
        "pkg2/dummy_exec_platforms.bzl",
        """
        # Since this isn't in builtins, use `transition`, not `exec_transition`
        # This is fine, since this is a no-op and doesn't use any of the features that exec
        # transitions are allowed to use.
        noop_exec_transition = transition(
            implementation = lambda settings, attr: {
                '//command_line_option:is exec configuration': True,
            },
            inputs = [],
            outputs = ['//command_line_option:is exec configuration'],
        )
        """);
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    // Set up a bare-bones ConfiguredRuleClassProvider. Aside from being minimalistic, this heads
    // off the possibility that we somehow grow an implicit dependency on production builtins code,
    // which would break since we're overwriting --experimental_builtins_bzl_path.
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addMinimalRules(builder);
    // Add some mock symbols to override.
    builder
        .addRuleDefinition(OVERRIDABLE_RULE)
        .addRuleDefinition(SANDWICH_RULE)
        .addRuleDefinition(SANDWICH_LOGIC_RULE)
        .addRuleDefinition(SANDWICH_CTX_RULE)
        .addBzlToplevel("overridable_symbol", "original_value")
        .addBzlToplevel(
            "flag_guarded_symbol",
            // For this mock symbol, we reuse the same flag that guards the production
            // _builtins_dummy symbol.
            FlagGuardedValue.onlyWhenExperimentalFlagIsTrue(
                BuildLanguageOptions.EXPERIMENTAL_BUILTINS_DUMMY, "original_value"))
        .addStarlarkBuiltinsInternal("internal_symbol", "internal_value");
    return builder.build();
  }

  @Before
  public void setUp() throws Exception {
    setBuildLanguageOptionsWithBuiltinsStaging();
  }

  @Override
  protected List<String> getDefaultBuildLanguageOptions() throws Exception {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(super.getDefaultBuildLanguageOptions());
    // This is important for test initialization. BuildViewTestCase calls initializeSkyframeExecutor
    // which creates the top-level build configuration. That loads the Starlark exec transition from
    // a .bzl file (see --experimental_exec_config above). Without this, BzlLoadFunction tries to
    // resolve the builtins path and exports.bzl, which fails.
    //
    // Most tests override this by calling setBuildLanguageOptionsWithBuiltinsStaging()
    builder.add("--experimental_builtins_bzl_path=");
    return builder.build();
  }

  private void setBuildLanguageOptionsWithBuiltinsStaging(String... options) throws Exception {
    ArrayList<String> newOptions = new ArrayList<>();
    newOptions.add("--experimental_builtins_bzl_path=tools/builtins_staging");
    Collections.addAll(newOptions, options);
    setBuildLanguageOptions(newOptions.toArray(new String[] {}));
  }

  /**
   * Writes an exports.bzl file with the given content, in the builtins location.
   *
   * <p>See {@link StarlarkBuiltinsFunction#EXPORTS_ENTRYPOINT} for the significance of exports.bzl.
   */
  private void writeExportsBzl(String... lines) throws Exception {
    scratch.overwriteFile("tools/builtins_staging/exports.bzl", lines);
    // Since builtins have changed, we need to be sure the cache is reset to re-load them.
    invalidatePackages(/* alsoConfigs= */ false);
  }

  /**
   * Writes a pkg/dummy.bzl file with the given content. Meant to be used in conjunction with {@link
   * #writePkgBuild}.
   *
   * <p>The bzl prints a marker phrase when it finishes evaluating, and includes a dummy symbol for
   * the BUILD file to load.
   */
  private void writePkgBzl(String... lines) throws Exception {
    List<String> modifiedLines = new ArrayList<>(Arrays.asList(lines));
    modifiedLines.add("dummy_symbol = None");
    // The marker phrase might not be needed, but I don't entirely trust BuildViewTestCase.
    modifiedLines.add("print('dummy.bzl evaluation completed')");
    scratch.overwriteFile("pkg/dummy.bzl", modifiedLines.toArray(lines));
  }

  /**
   * Writes a pkg/BUILD file with the given content. Meant to be used in conjunction with {@link
   * #writePkgBzl}.
   *
   * <p>The BUILD file ensures the dummy.bzl file is loaded.
   */
  private void writePkgBuild(String... lines) throws Exception {
    List<String> modifiedLines = new ArrayList<>(Arrays.asList(lines));
    modifiedLines.add(0, "load(':dummy.bzl', 'dummy_symbol')");
    scratch.overwriteFile("pkg/BUILD", modifiedLines.toArray(lines));
  }

  /** Builds {@code //pkg} and asserts success, including that the marker print() event occurs. */
  private void buildAndAssertSuccess() throws Exception {
    Object result = getConfiguredTarget("//pkg:BUILD");
    assertContainsEvent("dummy.bzl evaluation completed");
    // On error, getConfiguredTarget sometimes returns null without emitting events; see b/26382502.
    // Though in that case it seems unlikely the above assertion would've passed.
    assertThat(result).isNotNull();
  }

  /** Builds {@code //pkg:dummy} and asserts on the absence of the marker print() event. */
  private void buildAndAssertFailure() throws Exception {
    reporter.removeHandler(failFastHandler);
    Object result = getConfiguredTarget("//pkg:BUILD");
    assertDoesNotContainEvent("dummy.bzl evaluation completed");
    assertWithMessage("Loading of //pkg succeeded unexpectedly").that(result).isNull();
  }

  @Test
  public void basicFunctionality() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {'overridable_symbol': 'new_value'}",
        "exported_rules = {'overridable_rule': 'new_rule'}",
        "exported_to_java = {}");
    writePkgBuild("print('In BUILD: overridable_rule :: %s' % overridable_rule)");
    writePkgBzl(
        "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
        "print('In bzl: overridable_rule :: %s' % native.overridable_rule)");

    buildAndAssertSuccess();
    assertContainsEvent("In bzl: overridable_symbol :: new_value");
    assertContainsEvent("In bzl: overridable_rule :: new_rule");
    assertContainsEvent("In BUILD: overridable_rule :: new_rule");
  }

  @Test
  public void injectedBzlToplevelsAreNotVisibleToBuild() throws Exception {
    // The bzl toplevel symbols aren't toplevels for BUILD files. We test that injecting them
    // doesn't somehow change that.
    writeExportsBzl(
        "exported_toplevels = {'overridable_symbol': 'new_value'}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild("overridable_symbol");
    writePkgBzl();

    buildAndAssertFailure();
    assertContainsEvent("name 'overridable_symbol' is not defined");
  }

  @Test
  public void builtinsCanLoadFromBuiltins() throws Exception {
    // Define a few files that we can load with different kinds of label syntax. In each case,
    // access the `_builtins` symbol to demonstrate that we're being loaded as a builtins bzl.
    scratch.file(
        "tools/builtins_staging/absolute.bzl",
        """
        _builtins
        a = "A"
        """);
    scratch.file(
        "tools/builtins_staging/repo_relative.bzl",
        """
        _builtins
        b = "B"
        """);
    scratch.file(
        "tools/builtins_staging/subdir/pkg_relative1.bzl",
        """
        # Do a relative load within a load, to show it's relative to the (pseudo) package, i.e. the
        # root, and not relative to the file. That is, we specify 'subdir/pkg_relative2.bzl', not
        # just 'pkg_relative2.bzl'.
        load("subdir/pkg_relative2.bzl", "c2")

        _builtins
        c = c2
        """);
    scratch.file(
        "tools/builtins_staging/subdir/pkg_relative2.bzl",
        """
        _builtins
        c2 = "C"
        """);

    // Also create a file in the main repo whose package path coincides with a file in the builtins
    // pseudo-repo, to show that we get the right one.
    scratch.file("BUILD");
    scratch.file("repo_relative.bzl");

    writeExportsBzl(
        "load('@_builtins//:absolute.bzl', 'a')",
        "load('//:repo_relative.bzl', 'b')", // default repo is @_builtins, not main repo
        "load('subdir/pkg_relative1.bzl', 'c')", // relative to (pseudo) package, which is repo root
        "exported_toplevels = {'overridable_symbol': a + b + c}",
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl("print('overridable_symbol :: %s' % overridable_symbol)");

    buildAndAssertSuccess();
    assertContainsEvent("overridable_symbol :: ABC");
  }

  @Test
  public void otherBzlsCannotLoadFromBuiltins_apparent() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl("load('@_builtins//:exports.bzl', 'exported_toplevels')");

    buildAndAssertFailure();
    assertContainsEvent("No repository visible as '@_builtins' from");
  }

  @Test
  public void otherBzlsCannotLoadFromBuiltins_canonical() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl("load('@@_builtins//:exports.bzl', 'exported_toplevels')");

    buildAndAssertFailure();
    assertContainsEvent("The repository '@@_builtins' could not be resolved");
  }

  @Test
  public void builtinsCannotLoadFromNonBuiltins() throws Exception {
    scratch.file("BUILD");
    scratch.file(
        "a_user_written.bzl", //
        "toplevels = {'overridable_symbol': 'new_value'}");
    writeExportsBzl(
        // Use @// syntax to specify the main repo. Otherwise, the load would be relative to the
        // @_builtins pseudo-repo.
        "load('@//:a_user_written.bzl', 'toplevels')",
        "exported_toplevels = toplevels",
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    buildAndAssertFailure();
    assertContainsEvent(
        "in load statement: .bzl files in @_builtins cannot load from outside of @_builtins");
  }

  @Test
  public void builtinsCannotLoadWithMisplacedColon() throws Exception {
    scratch.file(
        "tools/builtins_staging/subdir/helper.bzl", //
        "toplevels = {'overridable_symbol': 'new_value'}");
    writeExportsBzl(
        "load('//subdir:helper.bzl', 'toplevels')", // Should've been loaded as //:subdir/helper.bzl
        "exported_toplevels = toplevels",
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    buildAndAssertFailure();
    assertContainsEvent("@_builtins cannot have subpackages");
  }

  @Test
  public void errorInEvaluatingBuiltinsTransitiveDependency() throws Exception {
    // Test case with a deep Starlark error in the @_builtins pseudo-repo itself.
    // Note that BzlLoadFunctionTest and PackageLoadingFunctionTest already cover the general case
    // of a failure in retrieving the StarlarkBuiltinsValue. Here we mainly want to make sure the
    // stack trace is informative for errors that occur in dependencies of exports.bzl.
    scratch.file(
        "tools/builtins_staging/helper.bzl", //
        "toplevels = {'overridable_symbol': 1//0}  # <-- dynamic error");
    writeExportsBzl(
        "load('@_builtins//:helper.bzl', 'toplevels')",
        "exported_toplevels = toplevels",
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    buildAndAssertFailure();
    assertContainsEvent(
        "File \"/workspace/tools/builtins_staging/helper.bzl\", line 1, column 37, in <toplevel>");
    assertContainsEvent("Error: integer division by zero");

    // We assert only the parts of the message before and after the module name, since the module
    // identified by the message depends on whether or not the test environment has a prelude file.
    Event ev = assertContainsEvent("Internal error while loading Starlark builtins");
    assertThat(ev.getMessage())
        .contains(
            "Failed to load builtins sources: "
                + "at /workspace/tools/builtins_staging/exports.bzl:1:6: "
                + "initialization of module 'helper.bzl' (internal) failed");
  }

  @Test
  public void errorInProcessingExports() throws Exception {
    // Test case with an error in the symbols exported by exports.bzl, but no actual Starlark errors
    // in the builtins files themselves.
    writeExportsBzl(
        "exported_toplevels = None", // should be dict
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    buildAndAssertFailure();

    // We assert only the parts of the message before and after the module name, since the module
    // identified by the message depends on whether or not the test environment has a prelude file.
    Event ev = assertContainsEvent("Internal error while loading Starlark builtins");
    assertThat(ev.getMessage())
        .contains(
            "Failed to apply declared builtins: "
                + "got NoneType for 'exported_toplevels dict', want dict");
  }

  // TODO(#11437): Remove once disabling is not allowed.
  @Test
  public void injectionDisabledByFlag() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {'overridable_symbol': 'new_value'}",
        "exported_rules = {'overridable_rule': 'new_rule'}",
        "exported_to_java = {}");
    writePkgBuild("print('In BUILD: overridable_rule :: %s' % overridable_rule)");
    writePkgBzl(
        "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
        "print('In bzl: overridable_rule :: %s' % native.overridable_rule)");
    setBuildLanguageOptions("--experimental_builtins_bzl_path=");

    buildAndAssertSuccess();
    assertContainsEvent("In bzl: overridable_symbol :: original_value");
    assertContainsEvent("In bzl: overridable_rule :: <built-in rule overridable_rule>");
    assertContainsEvent("In BUILD: overridable_rule :: <built-in rule overridable_rule>");
  }

  // TODO(#11437): Remove once disabling is not allowed.
  @Test
  public void exportsBzlMayBeInErrorWhenInjectionIsDisabled() throws Exception {
    writeExportsBzl( //
        "PARSE ERROR");
    writePkgBuild("print('In BUILD: overridable_rule :: %s' % overridable_rule)");
    writePkgBzl(
        "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
        "print('In bzl: overridable_rule :: %s' % native.overridable_rule)");
    setBuildLanguageOptions("--experimental_builtins_bzl_path=");

    buildAndAssertSuccess();
    assertContainsEvent("In bzl: overridable_symbol :: original_value");
    assertContainsEvent("In bzl: overridable_rule :: <built-in rule overridable_rule>");
    assertContainsEvent("In BUILD: overridable_rule :: <built-in rule overridable_rule>");
  }

  @Test
  public void builtinsCanSeeOriginalNativeToplevels() throws Exception {
    writeExportsBzl(
        "print('In builtins: overridable_symbol :: %s' % _builtins.toplevel.overridable_symbol)",
        "exported_toplevels = {'overridable_symbol': 'new_value'}",
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl("print('In bzl: overridable_symbol :: %s' % overridable_symbol)");

    buildAndAssertSuccess();
    assertContainsEvent("In builtins: overridable_symbol :: original_value");
    assertContainsEvent("In bzl: overridable_symbol :: new_value");
  }

  @Test
  public void builtinsCanSeeOriginalNativeRules() throws Exception {
    writeExportsBzl(
        "print('In builtins: overridable_rule :: %s' % _builtins.native.overridable_rule)",
        "exported_toplevels = {}",
        "exported_rules = {'overridable_rule': 'new_rule'}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl("print('In bzl: overridable_rule :: %s' % native.overridable_rule)");

    buildAndAssertSuccess();
    assertContainsEvent("In builtins: overridable_rule :: <built-in rule overridable_rule>");
    assertContainsEvent("In bzl: overridable_rule :: new_rule");
  }

  @Test
  public void builtinsCanSeeBuiltinsInternalSymbol() throws Exception {
    writeExportsBzl(
        "print('internal_symbol :: %s' % _builtins.internal.internal_symbol)",
        "exported_toplevels = {}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    buildAndAssertSuccess();
    assertContainsEvent("internal_symbol :: internal_value");
  }

  @Test
  public void otherBzlsCannotSeeBuiltinsInternalSymbol() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl("internal_symbol");

    buildAndAssertFailure();
    assertContainsEvent("name 'internal_symbol' is not defined");
  }

  @Test
  public void builtinsCanSeeFlags_unset() throws Exception {
    writeExportsBzl(
        // We use a None default here, but note that that's brittle if any machinery explicitly sets
        // flags to their default values. In practice the flag's real default value should be used.
        "print('experimental_builtins_dummy :: %s' % ",
        "      _builtins.get_flag('experimental_builtins_dummy', None))",
        "exported_toplevels = {}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    buildAndAssertSuccess();
    assertContainsEvent("experimental_builtins_dummy :: None");
  }

  @Test
  public void builtinsCanSeeFlags_set() throws Exception {
    writeExportsBzl(
        "print('experimental_builtins_dummy :: %s' % ",
        "      _builtins.get_flag('experimental_builtins_dummy', None))",
        "exported_toplevels = {}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    setBuildLanguageOptionsWithBuiltinsStaging("--experimental_builtins_dummy=true");
    buildAndAssertSuccess();
    assertContainsEvent("experimental_builtins_dummy :: True");
  }

  @Test
  public void builtinsCanSeeFlags_doesNotExist() throws Exception {
    writeExportsBzl(
        // We use a None default here, but note that that's brittle if any machinery explicitly sets
        // flags to their default values. In practice the flag's real default value should be used.
        "print('experimental_does_not_exist :: %s' % ",
        "      _builtins.get_flag('experimental_does_not_exist', None))",
        "exported_toplevels = {}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    buildAndAssertSuccess();
    assertContainsEvent("experimental_does_not_exist :: None");
  }

  @Test
  public void flagGuardedSymbol_canExportEvenWhenDisabled() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {'flag_guarded_symbol': 'overridden value'}",
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    // Default value of --experimental_builtins_dummy is false.
    buildAndAssertSuccess();
  }

  @Test
  public void flagGuardedSymbol_cannotUseWhenDisabledEvenIfInjected() throws Exception {
    // Implementation note: Flag guarding is implemented at name resolution time, before builtins
    // injection is applied.
    writeExportsBzl(
        "exported_toplevels = {'flag_guarded_symbol': 'overridden value'}",
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl("print('flag_guarded_symbol :: %s' % flag_guarded_symbol)");

    buildAndAssertFailure();
    assertContainsEvent("flag_guarded_symbol is experimental");
  }

  @Test
  public void flagGuardedSymbol_injectedValueIsSeenWhenFlagIsEnabled() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {'flag_guarded_symbol': 'overridden value'}",
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl("print('flag_guarded_symbol :: %s' % flag_guarded_symbol)");

    setBuildLanguageOptionsWithBuiltinsStaging("--experimental_builtins_dummy=true");
    buildAndAssertSuccess();
    assertContainsEvent("flag_guarded_symbol :: overridden value");
  }

  @Test
  public void flagGuardedSymbol_unconditionallyAccessibleToBuiltins() throws Exception {
    writeExportsBzl(
        "print('flag_guarded_symbol :: %s' % _builtins.toplevel.flag_guarded_symbol)",
        "exported_toplevels = {}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    buildAndAssertSuccess();
    assertContainsEvent("flag_guarded_symbol :: original_value");
  }

  @Test
  public void nativeRulesCanUseSymbolsFromBuiltins() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {}",
        "exported_rules = {}",
        "exported_to_java = {'builtins_defined_symbol': 'value_from_builtins'}");
    scratch.file(
        "pkg/BUILD", //
        "sandwich_rule(name = 'sandwich')");

    getConfiguredTarget("//pkg:sandwich");
    assertContainsEvent("builtins_defined_symbol :: value_from_builtins");
  }

  @Test
  public void nativeRuleFailsToFindUnknownBuiltin() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {}", //
        "exported_rules = {}",
        "exported_to_java = {}");
    scratch.file(
        "pkg/BUILD", //
        "sandwich_rule(name = 'sandwich')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//pkg:sandwich");
    assertContainsEvent("(Internal error) No symbol named 'builtins_defined_symbol'");
  }

  // TODO(#11437): Verify whether this works for native-defined aspects as well.

  @Test
  public void nativeRulesCanCallFunctionsDefinedInBuiltins() throws Exception {
    writeExportsBzl(
        // The driver rule calls this helper twice with a list.
        "def func(arg):",
        "  print('got arg %s' % arg)",
        "  arg.append('blah')",
        "  return len(arg)",
        "exported_toplevels = {}",
        "exported_rules = {}",
        "exported_to_java = {'builtins_defined_logic': func}");
    scratch.file(
        "pkg/BUILD", //
        "sandwich_logic_rule(name = 'sandwich_logic')");

    getConfiguredTarget("//pkg:sandwich_logic");
    assertContainsEvent("got arg []");
    assertContainsEvent("builtins_defined_logic call 1 :: 1");
    assertContainsEvent("got arg [\"blah\"]");
    assertContainsEvent("builtins_defined_logic call 2 :: 2");
    assertContainsEvent("final list value :: [\"blah\", \"blah\"]");
  }

  @Test
  public void nativeRulesCanPassCtxToBuiltinsDefinedHelpers() throws Exception {
    writeExportsBzl(
        "def impl_helper(ctx):",
        "  ctx.actions.write(output=ctx.outputs.out, content=ctx.attr.content)",
        "exported_toplevels = {}",
        "exported_rules = {}",
        "exported_to_java = {'builtins_rule_impl_helper': impl_helper}");
    scratch.file(
        "pkg/BUILD", //
        "sandwich_ctx_rule(name = 'sandwich_ctx', content='foo', out='bar.txt')");

    ConfiguredTarget target = getConfiguredTarget("//pkg:sandwich_ctx");
    Artifact output = getBinArtifact("bar.txt", target);
    ActionAnalysisMetadata action = getGeneratingAction(output);
    assertThat(action).isInstanceOf(FileWriteAction.class);
    assertThat(((FileWriteAction) action).getFileContents()).isEqualTo("foo");
  }

  @Test
  public void nativeRulesCanDisplayUsefulStarlarkStackTrace() throws Exception {
    writeExportsBzl(
        // The driver rule calls this helper twice with a list. Doesn't matter, we fail immediately.
        "def func(arg):",
        "  1//0",
        "exported_toplevels = {}",
        "exported_rules = {}",
        "exported_to_java = {'builtins_defined_logic': func}");
    scratch.file(
        "pkg/BUILD", //
        "sandwich_logic_rule(name = 'sandwich_logic')");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//pkg:sandwich_logic");
    // Rule implementation uses callStarlarkOrThrowRuleError(), which includes the stack trace.
    assertContainsEvent("line 2, column 4, in func");
    assertContainsEvent("Error: integer division by zero");
  }

  // The following tests check the integration of the injection override flag with builtins
  // injection. See BazelStarlarkEnvironmentTest for more detailed unit tests about the semantics of
  // this flag.

  @Test
  public void perSymbolInjectionOverride() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {'-overridable_symbol': 'new_value'}",
        "exported_rules = {'-overridable_rule': 'new_rule'}",
        "exported_to_java = {}");
    writePkgBuild("print('In BUILD: overridable_rule :: %s' % overridable_rule)");
    writePkgBzl(
        "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
        "print('In bzl: overridable_rule :: %s' % native.overridable_rule)");

    setBuildLanguageOptionsWithBuiltinsStaging(
        "--experimental_builtins_injection_override=+overridable_symbol,+overridable_rule");
    buildAndAssertSuccess();
    assertContainsEvent("In bzl: overridable_symbol :: new_value");
    assertContainsEvent("In bzl: overridable_rule :: new_rule");
    assertContainsEvent("In BUILD: overridable_rule :: new_rule");
  }

  @Test
  public void perSymbolInjectionOverride_lastOccurrenceWins() throws Exception {
    writeExportsBzl(
        "exported_toplevels = {'-overridable_symbol': 'new_value'}",
        "exported_rules = {'-overridable_rule': 'new_rule'}",
        "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl(
        "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
        "print('In bzl: overridable_rule :: %s' % native.overridable_rule)");

    // Tests that the last use of foo determines whether it's +foo or -foo. Also tests that the flag
    // is allowMultiple, so that passing the second list doesn't just zero out the first list.
    setBuildLanguageOptionsWithBuiltinsStaging(
        "--experimental_builtins_injection_override="
            + "+overridable_rule,+overridable_symbol,-overridable_symbol",
        "--experimental_builtins_injection_override=+overridable_symbol");
    buildAndAssertSuccess();
    assertContainsEvent("In bzl: overridable_symbol :: new_value");
    assertContainsEvent("In bzl: overridable_rule :: new_rule");
  }

  @Test
  public void perSymbolInjectionOverride_invalidOverrideItem() throws Exception {
    writeExportsBzl("exported_toplevels = {}", "exported_rules = {}", "exported_to_java = {}");
    writePkgBuild();
    writePkgBzl();

    setBuildLanguageOptionsWithBuiltinsStaging("--experimental_builtins_injection_override=foo");
    buildAndAssertFailure();
    assertContainsEvent("Invalid injection override item: 'foo'");
  }

  /**
   * Tests for injection, under inlining of {@link BzlLoadFunction}.
   *
   * <p>See {@link BzlLoadFunction#computeInline} for an explanation of inlining.
   */
  @RunWith(JUnit4.class)
  public static class BuiltinsInjectionTestWithInlining extends BuiltinsInjectionTest {

    @Override
    protected boolean usesInliningBzlLoadFunction() {
      return true;
    }
  }
}
