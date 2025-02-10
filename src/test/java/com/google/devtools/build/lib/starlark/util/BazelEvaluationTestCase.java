// Copyright 2006 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlark.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.starlark.StarlarkConfig;
import com.google.devtools.build.lib.analysis.starlark.StarlarkGlobalsImpl;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.BazelModuleKey;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.util.EventCollectionApparatus;
import com.google.devtools.build.lib.packages.BzlInitThreadContext;
import com.google.devtools.build.lib.packages.StarlarkExportable;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.rules.config.ConfigGlobalLibrary;
import com.google.devtools.build.lib.rules.config.ConfigStarlarkCommon;
import com.google.devtools.build.lib.rules.platform.PlatformCommon;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/** BazelEvaluationTestCase is a helper class for tests of Bazel loading-phase evaluation. */
// TODO(adonovan): this helper class might be somewhat handy for testing core Starlark, but its
// widespread use in tests of Bazel features greatly hinders the improvement of Bazel's loading
// phase. The existence of tests based on this class forces Bazel to continue support scenarios in
// which the test creates the environment, the threads, and so on, when these should be
// implementation details of the loading phase. Instead, the lib.packages should present an API in
// which the client provides files, flags, and arguments like a command-line tool, and all our tests
// should be ported to use that API.
public final class BazelEvaluationTestCase {

  private static final String DEFAULT_LABEL = "//test:label";
  private final EventCollectionApparatus eventCollectionApparatus =
      new EventCollectionApparatus(EventKind.ALL_EVENTS);

  private final Label label;

  private StarlarkSemantics semantics = StarlarkSemantics.DEFAULT;
  private StarlarkThread thread = null; // created lazily by getStarlarkThread
  private Module module = null; // created lazily by getModule

  private ImmutableMap<String, Class<?>> fragmentNameToClass = ImmutableMap.of();

  private Object threadOwner = "test";

  public BazelEvaluationTestCase() {
    this(DEFAULT_LABEL);
  }

  public BazelEvaluationTestCase(String label) {
    this.label = Label.parseCanonicalUnchecked(label);
  }

  /**
   * Parses the semantics flags and updates the semantics used to filter predeclared bindings, and
   * carried by subsequently created threads. Causes a new StarlarkThread and Module to be created
   * when next needed.
   */
  public final void setSemantics(String... options) throws OptionsParsingException {
    this.semantics =
        Options.parse(BuildLanguageOptions.class, options).getOptions().toStarlarkSemantics();

    // Re-initialize the thread and module with the new semantics when needed.
    this.thread = null;
    this.module = null;
  }

  public ExtendedEventHandler getEventHandler() {
    return eventCollectionApparatus.reporter();
  }

  /** Updates a global binding in the module. */
  // TODO(adonovan): rename setGlobal.
  @CanIgnoreReturnValue
  public BazelEvaluationTestCase update(String varname, Object value) throws Exception {
    getModule().setGlobal(varname, value);
    return this;
  }

  /** Returns the value of a global binding in the module. */
  // TODO(adonovan): rename getGlobal.
  public Object lookup(String varname) throws Exception {
    return getModule().getGlobal(varname);
  }

  /** Joins the lines, parses them as an expression, and evaluates it. */
  public final Object eval(String... lines) throws Exception {
    ParserInput input = ParserInput.fromLines(lines);
    return Starlark.eval(input, FileOptions.DEFAULT, getModule(), getStarlarkThread());
  }

  /** Joins the lines, parses them as a file, and executes it. */
  public final void exec(String... lines)
      throws SyntaxError.Exception, EvalException, InterruptedException {
    ParserInput input = ParserInput.fromLines(lines);
    Starlark.execFile(input, FileOptions.DEFAULT, getModule(), getStarlarkThread());
  }

  /**
   * Joins the lines, parses them as a file with the given label, executes it and exports all {@link
   * StarlarkExportable}s.
   */
  public final void execAndExport(Label label, String... lines) throws Exception {
    ParserInput input = ParserInput.fromLines(lines);
    Module module = getModule();
    StarlarkFile file = StarlarkFile.parse(input);
    Program prog = Program.compileFile(file, module);
    BzlLoadFunction.execAndExport(prog, label, getEventHandler(), module, getStarlarkThread());
  }

  /**
   * Joins the lines, parses them as a file, executes it and exports all {@link
   * StarlarkExportable}s.
   */
  public final void execAndExport(String... lines) throws Exception {
    execAndExport(this.label, lines);
  }

  private void newThread(StarlarkThread thread) {
    // This StarlarkThread has no PackageContext, so attempts to create a rule will fail.
    // Rule creation is tested by StarlarkIntegrationTest.

    // This is a poor approximation to the thread that Blaze would create
    // for testing rule implementation functions. It has phase LOADING, for example.
    // TODO(adonovan): stop creating threads in tests. This is the responsibility of the
    // production code. Tests should provide only files and commands.
    new BzlInitThreadContext(
            label,
            /* transitiveDigest= */ new byte[0], // dummy value for tests
            TestConstants.TOOLS_REPOSITORY,
            /* networkAllowlistForTests= */ Optional.empty(),
            fragmentNameToClass,
            /* mainRepoMapping= */ null)
        .storeInThread(thread);
  }

  /**
   * Allows for subclasses to inject custom fragments into the environment.
   *
   * <p>Must be called prior to any evaluation calls.
   */
  public void setFragmentNameToClass(ImmutableMap<String, Class<?>> fragmentNameToClass) {
    Preconditions.checkState(this.thread == null, "Call this method before getStarlarkThread()");
    this.fragmentNameToClass = fragmentNameToClass;
  }

  private Object newModule(ImmutableMap.Builder<String, Object> predeclared) {
    predeclared.putAll(StarlarkGlobalsImpl.INSTANCE.getFixedBzlToplevels());
    predeclared.put("platform_common", new PlatformCommon());
    predeclared.put("config_common", new ConfigStarlarkCommon());
    predeclared.put("config", new StarlarkConfig());
    Starlark.addMethods(predeclared, new ConfigGlobalLibrary());

    // Return the module's client data. (This one uses dummy values for tests.)
    return BazelModuleContext.create(
        BazelModuleKey.createFakeModuleKeyForTesting(label),
        RepositoryMapping.ALWAYS_FALLBACK,
        "test/label.bzl",
        /* loads= */ ImmutableList.of(),
        /* bzlTransitiveDigest= */ new byte[0]);
  }

  /** Sets a thread owner, for cases where the default value of {@code "test"} doesn't work. */
  public void setThreadOwner(Object owner) {
    this.threadOwner = owner;
  }

  public StarlarkThread getStarlarkThread() {
    if (this.thread == null) {
      Mutability mu = Mutability.create("test");
      StarlarkThread thread =
          StarlarkThread.create(mu, semantics, "test", SymbolGenerator.create(threadOwner));
      thread.setPrintHandler(Event.makeDebugPrintHandler(getEventHandler()));
      newThread(thread);
      this.thread = thread;
    }
    return this.thread;
  }

  public Module getModule() {
    if (this.module == null) {
      ImmutableMap.Builder<String, Object> predeclared = ImmutableMap.builder();
      Object clientData = newModule(predeclared);
      Module module =
          Module.withPredeclaredAndData(semantics, predeclared.buildOrThrow(), clientData);
      this.module = module;
    }
    return this.module;
  }

  public void checkEvalError(String msg, String... input) throws Exception {
    try {
      exec(input);
      fail("Expected error '" + msg + "' but got no error");
    } catch (SyntaxError.Exception | EvalException | EventCollectionApparatus.FailFastException e) {
      assertThat(e).hasMessageThat().isEqualTo(msg);
    }
  }

  public void checkEvalErrorContains(String msg, String... input) throws Exception {
    try {
      exec(input);
      fail("Expected error containing '" + msg + "' but got no error");
    } catch (SyntaxError.Exception | EvalException | EventCollectionApparatus.FailFastException e) {
      assertThat(e).hasMessageThat().contains(msg);
    }
  }

  // Forward relevant methods to the EventCollectionApparatus
  @CanIgnoreReturnValue
  public BazelEvaluationTestCase setFailFast(boolean failFast) {
    eventCollectionApparatus.setFailFast(failFast);
    return this;
  }

  public EventCollector getEventCollector() {
    return eventCollectionApparatus.collector();
  }

  public Event assertContainsError(String expectedMessage) {
    return eventCollectionApparatus.assertContainsError(expectedMessage);
  }

  /** Encapsulates a separate test which can be executed by a Scenario. */
  protected interface Testable {
    void run() throws Exception;
  }

  /**
   * A test scenario (a script of steps). Beware: Scenario is an inner class that mutates its
   * enclosing BazelEvaluationTestCase as it executes the script.
   */
  public final class Scenario {
    private final SetupActions setup = new SetupActions();
    private final String[] starlarkOptions;

    public Scenario(String... starlarkOptions) {
      this.starlarkOptions = starlarkOptions;
    }

    private void run(Testable testable) throws Exception {
      setSemantics(starlarkOptions);
      testable.run();
    }

    /** Allows the execution of several statements before each following test. */
    @CanIgnoreReturnValue
    public Scenario setUp(String... lines) {
      setup.registerExec(lines);
      return this;
    }

    /**
     * Allows the update of the specified variable before each following test
     *
     * @param name The name of the variable that should be updated
     * @param value The new value of the variable
     * @return This {@code Scenario}
     */
    @CanIgnoreReturnValue
    public Scenario update(String name, Object value) {
      setup.registerUpdate(name, value);
      return this;
    }

    /**
     * Evaluates two expressions and asserts that their results are equal.
     *
     * @param src The source expression to be evaluated
     * @param expectedEvalString The expression of the expected result
     * @return This {@code Scenario}
     * @throws Exception
     */
    @CanIgnoreReturnValue
    public Scenario testEval(String src, String expectedEvalString) throws Exception {
      runTest(createComparisonTestable(src, expectedEvalString, true));
      return this;
    }

    /** Evaluates an expression and compares its result to the expected object. */
    @CanIgnoreReturnValue
    public Scenario testExpression(String src, Object expected) throws Exception {
      runTest(createComparisonTestable(src, expected, false));
      return this;
    }

    /** Evaluates an expression and checks whether it fails with the expected error. */
    @CanIgnoreReturnValue
    public Scenario testIfExactError(String expectedError, String... lines) throws Exception {
      runTest(errorTestable(true, expectedError, lines));
      return this;
    }

    /** Evaluates the expression and checks whether it fails with the expected error. */
    @CanIgnoreReturnValue
    public Scenario testIfErrorContains(String expectedError, String... lines) throws Exception {
      runTest(errorTestable(false, expectedError, lines));
      return this;
    }

    /**
     * Creates a Testable that checks whether the evaluation of the given expression fails with the
     * expected error.
     *
     * @param exactMatch whether the error message must be identical to the expected error.
     */
    private Testable errorTestable(
        final boolean exactMatch, final String error, final String... lines) {
      return new Testable() {
        @Override
        public void run() throws Exception {
          if (exactMatch) {
            checkEvalError(error, lines);
          } else {
            checkEvalErrorContains(error, lines);
          }
        }
      };
    }

    /**
     * Creates a testable that compares the value of the expression to a specified result.
     *
     * @param src The expression to be evaluated
     * @param expected Either the expected object or an expression whose evaluation leads to the
     *     expected object
     * @param expectedIsExpression Signals whether {@code expected} is an object or an expression
     * @return An instance of Testable that runs the comparison
     */
    private Testable createComparisonTestable(
        final String src, final Object expected, final boolean expectedIsExpression) {
      return new Testable() {
        @Override
        public void run() throws Exception {
          Object actual = eval(src);
          Object realExpected = expected;

          // We could also print the actual object and compare the string to the expected
          // expression, but then the order of elements would matter.
          if (expectedIsExpression) {
            realExpected = eval((String) expected);
          }

          assertThat(actual).isEqualTo(realExpected);
        }
      };
    }

    /** Executes the given Testable */
    void runTest(Testable testable) throws Exception {
      run(new TestableDecorator(setup, testable));
    }
  }

  /**
   * A simple decorator that allows the execution of setup actions before running a {@code Testable}
   */
  static class TestableDecorator implements Testable {
    private final SetupActions setup;
    private final Testable decorated;

    public TestableDecorator(SetupActions setup, Testable decorated) {
      this.setup = setup;
      this.decorated = decorated;
    }

    /** Executes all stored actions and updates plus the actual {@code Testable} */
    @Override
    public void run() throws Exception {
      setup.executeAll();
      decorated.run();
    }
  }

  /** A container for collection actions that should be executed before a test */
  class SetupActions {
    private final List<Testable> setup;

    public SetupActions() {
      setup = new ArrayList<>();
    }

    /**
     * Registers an update to a module variable to be bound before a test
     *
     * @param name
     */
    public void registerUpdate(final String name, final Object value) {
      setup.add(
          new Testable() {
            @Override
            public void run() throws Exception {
              BazelEvaluationTestCase.this.update(name, value);
            }
          });
    }

    /** Registers a sequence of statements for execution prior to a test. */
    public void registerExec(final String... lines) {
      setup.add(
          new Testable() {
            @Override
            public void run() throws Exception {
              BazelEvaluationTestCase.this.exec(lines);
            }
          });
    }

    /** Executes all stored actions and updates */
    public void executeAll() throws Exception {
      for (Testable testable : setup) {
        testable.run();
      }
    }
  }
}
