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
package net.starlark.java.eval;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.LinkedList;
import java.util.List;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;

/** Helper class for tests that evaluate Starlark code. */
// TODO(adonovan): simplify this class out of existence.
// Most of its callers should be using the script-based test harness in net.starlark.java.eval.
// TODO(adonovan): extended only by StarlarkFlagGuardingTest; specialize that one test instead.
class EvaluationTestCase {

  private StarlarkSemantics semantics = StarlarkSemantics.DEFAULT;
  private StarlarkThread thread = null; // created lazily by getStarlarkThread
  private Module module = null; // created lazily by getModule

  /**
   * Updates the semantics used to filter predeclared bindings, and carried by subsequently created
   * threads. Causes a new StarlarkThread and Module to be created when next needed.
   */
  private final void setSemantics(StarlarkSemantics semantics) {
    this.semantics = semantics;

    // Re-initialize the thread and module with the new semantics when needed.
    this.thread = null;
    this.module = null;
  }

  // TODO(adonovan): don't let subclasses inherit vaguely specified "helpers".
  // Separate all the tests clearly into tests of the scanner, parser, resolver,
  // and evaluation.

  /** Updates a global binding in the module. */
  // TODO(adonovan): rename setGlobal.
  @CanIgnoreReturnValue
  final EvaluationTestCase update(String varname, Object value) throws Exception {
    getModule().setGlobal(varname, value);
    return this;
  }

  /** Returns the value of a global binding in the module. */
  // TODO(adonovan): rename getGlobal.
  final Object lookup(String varname) throws Exception {
    return getModule().getGlobal(varname);
  }

  /** Joins the lines, parses them as an expression, and evaluates it. */
  final Object eval(String... lines) throws Exception {
    ParserInput input = ParserInput.fromLines(lines);
    return Starlark.eval(input, FileOptions.DEFAULT, getModule(), getStarlarkThread());
  }

  /** Joins the lines, parses them as a file, and executes it. */
  final void exec(String... lines)
      throws SyntaxError.Exception, EvalException, InterruptedException {
    ParserInput input = ParserInput.fromLines(lines);
    Starlark.execFile(input, FileOptions.DEFAULT, getModule(), getStarlarkThread());
  }

  // A hook for subclasses to alter the created module.
  // Implementations may add to the predeclared environment,
  // and return the module's client data value.
  // TODO(adonovan): only used in StarlarkFlagGuardingTest; move there.
  protected Object newModuleHook(ImmutableMap.Builder<String, Object> predeclared) {
    return null; // no client data
  }

  StarlarkThread getStarlarkThread() {
    if (this.thread == null) {
      Mutability mu = Mutability.create("test");
      this.thread =
          StarlarkThread.create(
              mu, semantics, /* contextDescription= */ "", SymbolGenerator.create("test"));
      // Sets a post-assign hook to enable global export of StarlarkFunction Symbols.
      this.thread.setPostAssignHook((unusedName, unusedValue) -> {});
    }
    return this.thread;
  }

  private Module getModule() {
    if (this.module == null) {
      ImmutableMap.Builder<String, Object> predeclared = ImmutableMap.builder();
      newModuleHook(predeclared); // see StarlarkFlagGuardingTest
      this.module = Module.withPredeclared(semantics, predeclared.buildOrThrow());
    }
    return this.module;
  }

  final void checkEvalError(String msg, String... input) throws Exception {
    try {
      exec(input);
      fail("Expected error '" + msg + "' but got no error");
    } catch (SyntaxError.Exception | EvalException e) {
      assertThat(e).hasMessageThat().isEqualTo(msg);
    }
  }

  /**
   * Verifies that a piece of Starlark code fails at the specified location with either a {@link
   * SyntaxError} or an {@link EvalException} having the specified error message.
   *
   * <p>For a {@link SyntaxError}, the location checked is the first reported error's location. For
   * an {@link EvalException}, the location checked is the location of the innermost stack frame.
   *
   * @param failingLine 1-based line where the error is expected
   * @param failingColumn 1-based column where the error is expected.
   */
  final void checkEvalErrorAtLocation(
      String msg, int failingLine, int failingColumn, String... input) throws Exception {
    try {
      exec(input);
      fail("Expected error '" + msg + "' but got no error");
    } catch (SyntaxError.Exception e) {
      assertThat(e).hasMessageThat().isEqualTo(msg);
      Location location = e.errors().get(0).location();
      assertThat(location.line()).isEqualTo(failingLine);
      assertThat(location.column()).isEqualTo(failingColumn);
    } catch (EvalException e) {
      assertThat(e).hasMessageThat().isEqualTo(msg);
      assertThat(e.getCallStack()).isNotEmpty();
      Location location = Iterables.getLast(e.getCallStack()).location;
      assertThat(location.line()).isEqualTo(failingLine);
      assertThat(location.column()).isEqualTo(failingColumn);
    }
  }

  final void checkEvalErrorContains(String msg, String... input) throws Exception {
    try {
      exec(input);
      fail("Expected error containing '" + msg + "' but got no error");
    } catch (SyntaxError.Exception | EvalException e) {
      assertThat(e).hasMessageThat().contains(msg);
    }
  }

  final void checkEvalErrorDoesNotContain(String msg, String... input) throws Exception {
    try {
      exec(input);
    } catch (SyntaxError.Exception | EvalException e) {
      assertThat(e).hasMessageThat().doesNotContain(msg);
    }
  }

  /** Encapsulates a separate test which can be executed by a Scenario. */
  private interface Testable {
    void run() throws Exception;
  }

  /**
   * A test scenario (a script of steps). Beware: Scenario is an inner class that mutates its
   * enclosing EvaluationTestCase as it executes the script.
   */
  final class Scenario {
    private final SetupActions setup = new SetupActions();
    private final StarlarkSemantics semantics;

    Scenario() {
      this(StarlarkSemantics.DEFAULT);
    }

    Scenario(StarlarkSemantics semantics) {
      this.semantics = semantics;
    }

    private void run(Testable testable) throws Exception {
      EvaluationTestCase.this.setSemantics(semantics);
      testable.run();
    }

    /** Allows the execution of several statements before each following test. */
    @CanIgnoreReturnValue
    Scenario setUp(String... lines) {
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
    Scenario update(String name, Object value) {
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
    Scenario testEval(String src, String expectedEvalString) throws Exception {
      runTest(createComparisonTestable(src, expectedEvalString, true));
      return this;
    }

    /** Evaluates an expression and compares its result to the expected object. */
    @CanIgnoreReturnValue
    Scenario testExpression(String src, Object expected) throws Exception {
      runTest(createComparisonTestable(src, expected, false));
      return this;
    }

    /** Evaluates an expression and compares its result to the ordered list of expected objects. */
    @CanIgnoreReturnValue
    Scenario testExactOrder(String src, Object... items) throws Exception {
      runTest(collectionTestable(src, items));
      return this;
    }

    /** Evaluates an expression and checks whether it fails with the expected error. */
    @CanIgnoreReturnValue
    Scenario testIfExactError(String expectedError, String... lines) throws Exception {
      runTest(errorTestable(true, expectedError, lines));
      return this;
    }

    /**
     * Evaluates an expression and checks whether it fails with the expected error at the expected
     * location.
     *
     * <p>See {@link #checkEvalErrorAtLocation} for how an error's location is determined.
     *
     * @param failingLine 1-based line where the error is expected.
     * @param failingColumn 1-based column where the error is expected.
     */
    @CanIgnoreReturnValue
    Scenario testIfExactErrorAtLocation(
        String expectedError, int failingLine, int failingColumn, String... lines)
        throws Exception {
      runTest(errorTestableAtLocation(expectedError, failingLine, failingColumn, lines));
      return this;
    }

    /** Evaluates the expression and checks whether it fails with the expected error. */
    @CanIgnoreReturnValue
    Scenario testIfErrorContains(String expectedError, String... lines) throws Exception {
      runTest(errorTestable(false, expectedError, lines));
      return this;
    }

    /** Looks up the value of the specified variable and compares it to the expected value. */
    @CanIgnoreReturnValue
    Scenario testLookup(String name, Object expected) throws Exception {
      runTest(createLookUpTestable(name, expected));
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
     * Creates a Testable that checks whether the evaluation of the given expression fails with the
     * expected evaluation error in the expected location.
     *
     * <p>See {@link #checkEvalErrorAtLocation} for how an error's location is determined.
     *
     * @param failingLine 1-based line where the error is expected.
     * @param failingColumn 1-based column where the error is expected.
     */
    private Testable errorTestableAtLocation(
        final String error, final int failingLine, final int failingColumn, final String... lines) {
      return new Testable() {
        @Override
        public void run() throws Exception {
          checkEvalErrorAtLocation(error, failingLine, failingColumn, lines);
        }
      };
    }

    /**
     * Creates a Testable that checks whether the value of the expression is a sequence containing
     * the expected elements.
     */
    private Testable collectionTestable(final String src, final Object... expected) {
      return new Testable() {
        @Override
        public void run() throws Exception {
          assertThat((Iterable<?>) eval(src)).containsExactly(expected).inOrder();
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

    /**
     * Creates a Testable that looks up the given variable and compares its value to the expected
     * value
     *
     * @param name
     * @param expected
     * @return An instance of Testable that does both lookup and comparison
     */
    private Testable createLookUpTestable(final String name, final Object expected) {
      return new Testable() {
        @Override
        public void run() throws Exception {
          assertThat(lookup(name)).isEqualTo(expected);
        }
      };
    }

    /**
     * Executes the given Testable
     * @param testable
     * @throws Exception
     */
    protected void runTest(Testable testable) throws Exception {
      run(new TestableDecorator(setup, testable));
    }
  }

  /**
   * A simple decorator that allows the execution of setup actions before running a {@code Testable}
   */
  static final class TestableDecorator implements Testable {
    private final SetupActions setup;
    private final Testable decorated;

    TestableDecorator(SetupActions setup, Testable decorated) {
      this.setup = setup;
      this.decorated = decorated;
    }

    /**
     * Executes all stored actions and updates plus the actual {@code Testable}
     */
    @Override
    public void run() throws Exception {
      setup.executeAll();
      decorated.run();
    }
  }

  /** A container for collection actions that should be executed before a test */
  private final class SetupActions {
    private List<Testable> setup;

    SetupActions() {
      setup = new LinkedList<>();
    }

    /**
     * Registers an update to a module variable to be bound before a test
     *
     * @param name
     * @param value
     */
    void registerUpdate(final String name, final Object value) {
      setup.add(
          new Testable() {
            @Override
            public void run() throws Exception {
              EvaluationTestCase.this.update(name, value);
            }
          });
    }

    /** Registers a sequence of statements for execution prior to a test. */
    void registerExec(final String... lines) {
      setup.add(
          new Testable() {
            @Override
            public void run() throws Exception {
              EvaluationTestCase.this.exec(lines);
            }
          });
    }

    /**
     * Executes all stored actions and updates
     *
     * @throws Exception
     */
    void executeAll() throws Exception {
      for (Testable testable : setup) {
        testable.run();
      }
    }
  }
}
