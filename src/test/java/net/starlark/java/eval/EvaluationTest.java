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
package net.starlark.java.eval;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test of evaluation behavior. (Implicitly uses lexer + parser.) */
@RunWith(JUnit4.class)
public final class EvaluationTest {

  private final EvaluationTestCase ev = new EvaluationTestCase();

  @Test
  public void testExecutionStopsAtFirstError() throws Exception {
    List<String> printEvents = new ArrayList<>();
    ParserInput input = ParserInput.fromLines("print('hello'); x = 1//0; print('goodbye')");
    InterruptFunction interrupt = new InterruptFunction();
    assertThrows(EvalException.class, () -> execWithInterrupt(input, interrupt, printEvents));

    // Only expect hello, should have been an error before goodbye.
    assertThat(printEvents.toString()).isEqualTo("[hello]");
  }

  @Test
  public void testExecutionNotStartedOnInterrupt() throws Exception {
    ParserInput input = ParserInput.fromLines("print('hello')");
    List<String> printEvents = new ArrayList<>();
    Thread.currentThread().interrupt();
    InterruptFunction interrupt = new InterruptFunction();
    assertThrows(
        InterruptedException.class, () -> execWithInterrupt(input, interrupt, printEvents));

    // Execution didn't reach print.
    assertThat(printEvents).isEmpty();
  }

  @Test
  public void testForLoopAbortedOnInterrupt() throws Exception {
    ParserInput input =
        ParserInput.fromLines(
            "def f():", //
            "  for i in range(100):",
            "    interrupt(i == 5)",
            "f()");
    InterruptFunction interrupt = new InterruptFunction();
    assertThrows(
        InterruptedException.class, () -> execWithInterrupt(input, interrupt, new ArrayList<>()));

    assertThat(interrupt.callCount).isEqualTo(6);
  }

  @Test
  public void testForComprehensionAbortedOnInterrupt() throws Exception {
    ParserInput input = ParserInput.fromLines("[interrupt(i == 5) for i in range(100)]");
    InterruptFunction interrupt = new InterruptFunction();
    assertThrows(
        InterruptedException.class, () -> execWithInterrupt(input, interrupt, new ArrayList<>()));

    assertThat(interrupt.callCount).isEqualTo(6);
  }

  @Test
  public void testFunctionCallsNotStartedOnInterrupt() throws Exception {
    ParserInput input =
        ParserInput.fromLines("interrupt(False); interrupt(True); interrupt(False);");
    InterruptFunction interrupt = new InterruptFunction();
    assertThrows(
        InterruptedException.class, () -> execWithInterrupt(input, interrupt, new ArrayList<>()));

    // Third call shouldn't happen.
    assertThat(interrupt.callCount).isEqualTo(2);
  }

  private static class InterruptFunction implements StarlarkCallable {

    private int callCount = 0;

    @Override
    public String getName() {
      return "interrupt";
    }

    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs) {
      callCount++;
      if (!args.isEmpty() && Starlark.truth(args.get(0))) {
        Thread.currentThread().interrupt();
      }
      return Starlark.NONE;
    }
  }

  // Executes input, with the specified 'interrupt' predeclared built-in, gather print events in
  // printEvents.
  private static void execWithInterrupt(
      ParserInput input, InterruptFunction interrupt, List<String> printEvents) throws Exception {
    Module module =
        Module.withPredeclared(StarlarkSemantics.DEFAULT, ImmutableMap.of("interrupt", interrupt));
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      thread.setPrintHandler((_thread, msg) -> printEvents.add(msg));
      Starlark.execFile(input, FileOptions.DEFAULT, module, thread);
    } finally {
      // Reset interrupt bit in case the test failed to do so.
      Thread.interrupted();
    }
  }

  @Test
  public void testExecutionSteps() throws Exception {
    Mutability mu = Mutability.create("test");
    StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
    ParserInput input = ParserInput.fromLines("squares = [x*x for x in range(n)]");

    class C {
      long run(int n) throws SyntaxError.Exception, EvalException, InterruptedException {
        Module module =
            Module.withPredeclared(
                StarlarkSemantics.DEFAULT, ImmutableMap.of("n", StarlarkInt.of(n)));
        long steps0 = thread.getExecutedSteps();
        Starlark.execFile(input, FileOptions.DEFAULT, module, thread);
        return thread.getExecutedSteps() - steps0;
      }
    }

    // A thread records the number of computation steps.
    long steps1000 = new C().run(1000);
    long steps10000 = new C().run(10000);
    double ratio = (double) steps10000 / (double) steps1000;
    if (ratio < 9.9 || ratio > 10.1) {
      throw new AssertionError(
          String.format(
              "computation steps did not increase linearly: f(1000)=%d, f(10000)=%d, ratio=%g, want"
                  + " ~10",
              steps1000, steps10000, ratio));
    }

    // Exceeding the limit causes cancellation.
    thread.setMaxExecutionSteps(1000);
    EvalException ex = assertThrows(EvalException.class, () -> new C().run(1000));
    assertThat(ex).hasMessageThat().contains("Starlark computation cancelled: too many steps");
  }

  @Test
  public void testExprs() throws Exception {
    ev.new Scenario()
        .testExpression("'%sx' % 'foo' + 'bar1'", "fooxbar1")
        .testExpression("('%sx' % 'foo') + 'bar2'", "fooxbar2")
        .testExpression("'%sx' % ('foo' + 'bar3')", "foobar3x")
        .testExpression("123 + 456", StarlarkInt.of(579))
        .testExpression("456 - 123", StarlarkInt.of(333))
        .testExpression("8 % 3", StarlarkInt.of(2))
        .testIfErrorContains("unsupported binary operation: int % string", "3 % 'foo'")
        .testExpression("-5", StarlarkInt.of(-5))
        .testIfErrorContains("unsupported unary operation: -string", "-'foo'");
  }

  @Test
  public void testListExprs() throws Exception {
    ev.new Scenario()
        .testExactOrder("[1, 2, 3]", StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
        .testExactOrder("(1, 2, 3)", StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3));
  }

  @Test
  public void testStringFormatMultipleArgs() throws Exception {
    ev.new Scenario().testExpression("'%sY%s' % ('X', 'Z')", "XYZ");
  }

  @Test
  public void testConditionalExpressions() throws Exception {
    ev.new Scenario()
        .testExpression("1 if True else 2", StarlarkInt.of(1))
        .testExpression("1 if False else 2", StarlarkInt.of(2))
        .testExpression("1 + 2 if 3 + 4 else 5 + 6", StarlarkInt.of(3));
  }

  @Test
  public void testListComparison() throws Exception {
    ev.new Scenario()
        .testExpression("[] < [1]", true)
        .testExpression("[1] < [1, 1]", true)
        .testExpression("[1, 1] < [1, 2]", true)
        .testExpression("[1, 2] < [1, 2, 3]", true)
        .testExpression("[1, 2, 3] <= [1, 2, 3]", true)
        .testExpression("['a', 'b'] > ['a']", true)
        .testExpression("['a', 'b'] >= ['a']", true)
        .testExpression("['a', 'b'] < ['a']", false)
        .testExpression("['a', 'b'] <= ['a']", false)
        .testExpression("('a', 'b') > ('a', 'b')", false)
        .testExpression("('a', 'b') >= ('a', 'b')", true)
        .testExpression("('a', 'b') < ('a', 'b')", false)
        .testExpression("('a', 'b') <= ('a', 'b')", true)
        .testExpression("[[1, 1]] > [[1, 1], []]", false)
        .testExpression("[[1, 1]] < [[1, 1], []]", true);
  }

  @Test
  public void testSumFunction() throws Exception {
    StarlarkCallable sum =
        new StarlarkCallable() {
          @Override
          public String getName() {
            return "sum";
          }

          @Override
          public StarlarkInt call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
              throws EvalException {
            StarlarkInt sum = StarlarkInt.of(0);
            for (Object arg : args) {
              sum = StarlarkInt.add(sum, (StarlarkInt) arg);
            }
            return sum;
          }
        };

    ev.new Scenario()
        .update(sum.getName(), sum)
        .testExpression("sum(1, 2, 3, 4, 5, 6)", StarlarkInt.of(21))
        .testExpression("sum", sum)
        .testExpression("sum(a=1, b=2)", StarlarkInt.of(0));
  }

  @Test
  public void testNotCallInt() throws Exception {
    ev.new Scenario()
        .setUp("sum = 123456")
        .testLookup("sum", StarlarkInt.of(123456))
        .testIfExactError("'int' object is not callable", "sum(1, 2, 3, 4, 5, 6)")
        .testExpression("sum", StarlarkInt.of(123456));
  }

  @Test
  public void testComplexFunctionCall() throws Exception {
    ev.new Scenario()
        .setUp("functions = [min, max]", "l = [1,2]")
        .testEval("(functions[0](l), functions[1](l))", "(1, 2)");
  }

  @Test
  public void testKeywordArgs() throws Exception {
    // This function returns the map of keyword arguments passed to it.
    StarlarkCallable kwargs =
        new StarlarkCallable() {
          @Override
          public String getName() {
            return "kwargs";
          }

          @Override
          public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs) {
            return kwargs;
          }
        };

    ev.new Scenario()
        .update(kwargs.getName(), kwargs)
        .testEval(
            "kwargs(foo=1, bar='bar', wiz=[1,2,3]).items()",
            "[('foo', 1), ('bar', 'bar'), ('wiz', [1, 2, 3])]")
        .testEval(
            "kwargs(wiz=[1,2,3], bar='bar', foo=1).items()",
            "[('wiz', [1, 2, 3]), ('bar', 'bar'), ('foo', 1)]");
  }

  @Test
  public void testModulo() throws Exception {
    ev.new Scenario()
        .testExpression("6 % 2", StarlarkInt.of(0))
        .testExpression("6 % 4", StarlarkInt.of(2))
        .testExpression("3 % 6", StarlarkInt.of(3))
        .testExpression("7 % -4", StarlarkInt.of(-1))
        .testExpression("-7 % 4", StarlarkInt.of(1))
        .testExpression("-7 % -4", StarlarkInt.of(-3))
        .testIfExactError("integer modulo by zero", "5 % 0");
  }

  @Test
  public void testFloorDivision() throws Exception {
    ev.new Scenario()
        .testExpression("6 // 2", StarlarkInt.of(3))
        .testExpression("6 // 4", StarlarkInt.of(1))
        .testExpression("3 // 6", StarlarkInt.of(0))
        .testExpression("7 // -2", StarlarkInt.of(-4))
        .testExpression("-7 // 2", StarlarkInt.of(-4))
        .testExpression("-7 // -2", StarlarkInt.of(3))
        .testExpression("2147483647 // 2", StarlarkInt.of(1073741823))
        .testIfErrorContains("unsupported binary operation: string // int", "'str' // 2")
        .testIfExactError("integer division by zero", "5 // 0");
  }

  @Test
  public void testArithmeticDoesNotOverflow() throws Exception {
    ev.new Scenario()
        .testEval("2000000000 + 2000000000", "1000000000 + 1000000000 + 1000000000 + 1000000000")
        .testExpression("1234567890 * 987654321", StarlarkInt.of(1219326311126352690L))
        .testExpression(
            "1234567890 * 987654321 * 987654321",
            StarlarkInt.multiply(StarlarkInt.of(1219326311126352690L), StarlarkInt.of(987654321)))
        .testEval("- 2000000000 - 2000000000", "-1000000000 - 1000000000 - 1000000000 - 1000000000")

        // literal 2147483648 is not allowed, so we compute it
        .setUp("minint = - 2147483647 - 1")
        .testEval("-minint", "2147483647+1");
  }

  @Test
  public void testOperatorPrecedence() throws Exception {
    ev.new Scenario()
        .testExpression("2 + 3 * 4", StarlarkInt.of(14))
        .testExpression("2 + 3 // 4", StarlarkInt.of(2))
        .testExpression("2 * 3 + 4 // -2", StarlarkInt.of(4));
  }

  @Test
  public void testConcatStrings() throws Exception {
    ev.new Scenario().testExpression("'foo' + 'bar'", "foobar");
  }

  @Test
  public void testConcatLists() throws Exception {
    ev.new Scenario()
        .testExactOrder(
            "[1,2] + [3,4]",
            StarlarkInt.of(1),
            StarlarkInt.of(2),
            StarlarkInt.of(3),
            StarlarkInt.of(4))
        .testExactOrder("(1,2)", StarlarkInt.of(1), StarlarkInt.of(2))
        .testExactOrder(
            "(1,2) + (3,4)",
            StarlarkInt.of(1),
            StarlarkInt.of(2),
            StarlarkInt.of(3),
            StarlarkInt.of(4));

    // TODO(fwe): cannot be handled by current testing suite
    // list
    Object x = ev.eval("[1,2] + [3,4]");
    assertThat((Iterable<?>) x)
        .containsExactly(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4))
        .inOrder();
    assertThat(x).isInstanceOf(StarlarkList.class);
    assertThat(Starlark.isImmutable(x)).isFalse();

    // tuple
    x = ev.eval("(1,2) + (3,4)");
    assertThat((Iterable<?>) x)
        .containsExactly(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4))
        .inOrder();
    assertThat(x).isInstanceOf(Tuple.class);
    assertThat(x)
        .isEqualTo(
            Tuple.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4)));
    assertThat(Starlark.isImmutable(x)).isTrue();

    ev.checkEvalError("unsupported binary operation: tuple + list", "(1,2) + [3,4]");
  }

  @Test
  public void testListComprehensionDefinitionOrder() throws Exception {
    ev.new Scenario()
        .testIfErrorContains(
            "local variable 'y' is referenced before assignment",
            "[x for x in (1, 2) if y for y in (3, 4)]");
  }

  @Test
  public void testTupleDestructuring() throws Exception {
    ev.new Scenario()
        .setUp("a, b = 1, 2")
        .testLookup("a", StarlarkInt.of(1))
        .testLookup("b", StarlarkInt.of(2))
        .setUp("c, d = {'key1':2, 'key2':3}")
        .testLookup("c", "key1")
        .testLookup("d", "key2");
  }

  @Test
  public void testSingleTuple() throws Exception {
    ev.new Scenario().setUp("(a,) = [1]").testLookup("a", StarlarkInt.of(1));
  }

  @Test
  public void testHeterogeneousDict() throws Exception {
    ev.new Scenario()
        .setUp("d = {'str': 1, 2: 3}", "a = d['str']", "b = d[2]")
        .testLookup("a", StarlarkInt.of(1))
        .testLookup("b", StarlarkInt.of(3));
  }

  @Test
  public void testAccessDictWithATupleKey() throws Exception {
    ev.new Scenario().setUp("x = {(1, 2): 3}[1, 2]").testLookup("x", StarlarkInt.of(3));
  }

  @Test
  public void testDictWithDuplicatedKey() throws Exception {
    ev.new Scenario()
        .testIfErrorContains(
            "dictionary expression has duplicate key: \"str\"", "{'str': 1, 'x': 2, 'str': 3}");
  }

  @Test
  public void testRecursiveTupleDestructuring() throws Exception {
    ev.new Scenario()
        .setUp("((a, b), (c, d)) = [(1, 2), (3, 4)]")
        .testLookup("a", StarlarkInt.of(1))
        .testLookup("b", StarlarkInt.of(2))
        .testLookup("c", StarlarkInt.of(3))
        .testLookup("d", StarlarkInt.of(4));
  }

  @Test
  public void testListComprehensionAtTopLevel() throws Exception {
    // It is allowed to have a loop variable with the same name as a global variable.
    ev.new Scenario()
        .update("x", StarlarkInt.of(42))
        .setUp("y = [x + 1 for x in [1,2,3]]")
        .testExactOrder("y", StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4));
  }

  @Test
  public void testDictComprehensions() throws Exception {
    ev.new Scenario()
        .testExpression("{a : a for a in []}", Collections.emptyMap())
        .testExpression(
            "{b : b for b in [1, 2]}",
            ImmutableMap.of(
                StarlarkInt.of(1), StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(2)))
        .testExpression(
            "{c : 'v_' + c for c in ['a', 'b']}", ImmutableMap.of("a", "v_a", "b", "v_b"))
        .testExpression(
            "{'k_' + d : d for d in ['a', 'b']}", ImmutableMap.of("k_a", "a", "k_b", "b"))
        .testExpression(
            "{'k_' + e : 'v_' + e for e in ['a', 'b']}",
            ImmutableMap.of("k_a", "v_a", "k_b", "v_b"))
        .testExpression(
            "{x+y : x*y for x, y in [[2, 3]]}",
            ImmutableMap.of(StarlarkInt.of(5), StarlarkInt.of(6)));
  }

  @Test
  public void testDictComprehensionOnNonIterable() throws Exception {
    ev.new Scenario()
        .testIfExactErrorAtLocation("type 'int' is not iterable", 1, 17, "{k : k for k in 3}");
  }

  @Test
  public void testDictComprehension_manyClauses() throws Exception {
    ev.new Scenario()
        .testExpression(
            "{x : x * y for x in range(1, 10) if x % 2 == 0 for y in range(1, 10) if y == x}",
            ImmutableMap.of(
                StarlarkInt.of(2),
                StarlarkInt.of(4),
                StarlarkInt.of(4),
                StarlarkInt.of(16),
                StarlarkInt.of(6),
                StarlarkInt.of(36),
                StarlarkInt.of(8),
                StarlarkInt.of(64)));
  }

  @Test
  public void testDictComprehensions_multipleKey() throws Exception {
    ev.new Scenario()
        .testExpression(
            "{x : x for x in [1, 2, 1]}",
            ImmutableMap.of(
                StarlarkInt.of(1), StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(2)))
        .testExpression(
            "{y : y for y in ['ab', 'c', 'a' + 'b']}", ImmutableMap.of("ab", "ab", "c", "c"));
  }

  @Test
  public void testListConcatenation() throws Exception {
    ev.new Scenario()
        .testEval("[1, 2] + [3, 4]", "[1, 2, 3, 4]")
        .testEval("(1, 2) + (3, 4)", "(1, 2, 3, 4)")
        .testIfExactError("unsupported binary operation: list + tuple", "[1, 2] + (3, 4)")
        .testIfExactError("unsupported binary operation: tuple + list", "(1, 2) + [3, 4]");
  }

  @Test
  public void testListComprehensionFailsOnNonSequence() throws Exception {
    ev.new Scenario()
        .testIfExactErrorAtLocation("type 'int' is not iterable", 1, 17, "[x + 1 for x in 123]");
  }

  @Test
  public void testListComprehensionOnStringIsForbidden() throws Exception {
    ev.new Scenario()
        .testIfExactErrorAtLocation("type 'string' is not iterable", 1, 13, "[x for x in 'abc']");
  }

  @Test
  public void testInvalidAssignment() throws Exception {
    ev.new Scenario().testIfErrorContains("cannot assign to 'x + 1'", "x + 1 = 2");
  }

  @Test
  public void testListComprehensionOnDictionary() throws Exception {
    ev.new Scenario().testExactOrder("['var_' + n for n in {'a':1,'b':2}]", "var_a", "var_b");
  }

  @Test
  public void testListComprehensionOnDictionaryCompositeExpression() throws Exception {
    ev.new Scenario()
        .setUp("d = {1:'a',2:'b'}", "l = [d[x] for x in d]")
        .testLookup("l", StarlarkList.of(null, "a", "b"));
  }

  @Test
  public void testListComprehensionUpdate() throws Exception {
    ev.new Scenario()
        .setUp("xs = [1, 2, 3]")
        .testIfErrorContains(
            "list value is temporarily immutable due to active for-loop iteration",
            "[xs.append(4) for x in xs]");
  }

  @Test
  public void testNestedListComprehensionUpdate() throws Exception {
    ev.new Scenario()
        .setUp("xs = [1, 2, 3]")
        .testIfErrorContains(
            "list value is temporarily immutable due to active for-loop iteration",
            "[xs.append(4) for x in xs for y in xs]");
  }

  @Test
  public void testListComprehensionUpdateInClause() throws Exception {
    ev.new Scenario()
        .setUp("xs = [1, 2, 3]")
        .testIfErrorContains(
            "list value is temporarily immutable due to active for-loop iteration",
            // Use short-circuiting to produce valid output in the event
            // the exception is not raised.
            "[y for x in xs for y in (xs.append(4) or xs)]");
  }

  @Test
  public void testDictComprehensionUpdate() throws Exception {
    ev.new Scenario()
        .setUp("xs = {1:1, 2:2, 3:3}")
        .testIfErrorContains(
            "dict value is temporarily immutable due to active for-loop iteration",
            "[xs.popitem() for x in xs]");
  }

  @Test
  public void testListComprehensionScope() throws Exception {
    // Test list comprehension creates a scope, so outer variables kept unchanged
    ev.new Scenario()
        .setUp("x = 1", "l = [x * 3 for x in [2]]", "y = x")
        .testEval("y", "1")
        .testEval("l", "[6]");
  }

  @Test
  public void testInOperator() throws Exception {
    ev.new Scenario()
        .testExpression("'b' in ['a', 'b']", Boolean.TRUE)
        .testExpression("'c' in ['a', 'b']", Boolean.FALSE)
        .testExpression("'b' in ('a', 'b')", Boolean.TRUE)
        .testExpression("'c' in ('a', 'b')", Boolean.FALSE)
        .testExpression("'b' in {'a' : 1, 'b' : 2}", Boolean.TRUE)
        .testExpression("'c' in {'a' : 1, 'b' : 2}", Boolean.FALSE)
        .testExpression("1 in {'a' : 1, 'b' : 2}", Boolean.FALSE)
        .testExpression("'b' in 'abc'", Boolean.TRUE)
        .testExpression("'d' in 'abc'", Boolean.FALSE);
  }

  @Test
  public void testNotInOperator() throws Exception {
    ev.new Scenario()
        .testExpression("'b' not in ['a', 'b']", Boolean.FALSE)
        .testExpression("'c' not in ['a', 'b']", Boolean.TRUE)
        .testExpression("'b' not in ('a', 'b')", Boolean.FALSE)
        .testExpression("'c' not in ('a', 'b')", Boolean.TRUE)
        .testExpression("'b' not in {'a' : 1, 'b' : 2}", Boolean.FALSE)
        .testExpression("'c' not in {'a' : 1, 'b' : 2}", Boolean.TRUE)
        .testExpression("1 not in {'a' : 1, 'b' : 2}", Boolean.TRUE)
        .testExpression("'b' not in 'abc'", Boolean.FALSE)
        .testExpression("'d' not in 'abc'", Boolean.TRUE);
  }

  @Test
  public void testInFail() throws Exception {
    ev.new Scenario()
        .testIfErrorContains(
            "'in <string>' requires string as left operand, not 'int'", "1 in '123'")
        .testIfErrorContains("unsupported binary operation: string in int", "'a' in 1");
  }

  @Test
  public void testInCompositeForPrecedence() throws Exception {
    ev.new Scenario().testExpression("not 'a' in ['a'] or 0", StarlarkInt.of(0));
  }

  @Test
  public void testPercentOnValueWithRepr() throws Exception {
    Object obj =
        new StarlarkValue() {
          @Override
          public void repr(Printer printer) {
            printer.append("<str marker>");
          }
        };
    ev.new Scenario().update("obj", obj).testExpression("'%s' % obj", "<str marker>");
  }

  private static class Dummy implements StarlarkValue {}

  @Test
  public void testStringRepresentationsOfArbitraryObjects() throws Exception {
    String dummy = "<unknown object net.starlark.java.eval.EvaluationTest$Dummy>";
    ev.new Scenario()
        .update("dummy", new Dummy())
        .testExpression("str(dummy)", dummy)
        .testExpression("repr(dummy)", dummy)
        .testExpression("'{}'.format(dummy)", dummy)
        .testExpression("'%s' % dummy", dummy)
        .testExpression("'%r' % dummy", dummy);
  }

  @Test
  public void testPercentOnTupleOfDummyValues() throws Exception {
    Object obj =
        new StarlarkValue() {
          @Override
          public void repr(Printer printer) {
            printer.append("<str marker>");
          }
        };
    ev.new Scenario()
        .update("obj", obj)
        .testExpression("'%s %s' % (obj, obj)", "<str marker> <str marker>");
    ev.new Scenario()
        .update("unknown", new Dummy())
        .testExpression(
            "'%s %s' % (unknown, unknown)",
            "<unknown object net.starlark.java.eval.EvaluationTest$Dummy> <unknown"
                + " object net.starlark.java.eval.EvaluationTest$Dummy>");
  }

  @Test
  public void testDictKeys() throws Exception {
    ev.new Scenario().testExactOrder("{'a': 1}.keys() + ['b', 'c']", "a", "b", "c");
  }

  @Test
  public void testDictKeysTooManyArgs() throws Exception {
    ev.new Scenario()
        .testIfExactError("keys() got unexpected positional argument", "{'a': 1}.keys('abc')");
  }

  @Test
  public void testDictKeysTooManyKeyArgs() throws Exception {
    ev.new Scenario()
        .testIfExactError(
            "keys() got unexpected keyword argument 'arg'", "{'a': 1}.keys(arg='abc')");
  }

  @Test
  public void testDictKeysDuplicateKeyArgs() throws Exception {
    // f(a=1, a=2) is caught statically by the resolver.
    ev.new Scenario()
        .testIfExactError(
            "int() got multiple values for argument 'base'", "int('1', base=10, **dict(base=16))");
  }

  @Test
  public void testArgBothPosKey() throws Exception {
    ev.new Scenario()
        .testIfErrorContains(
            "int() got multiple values for argument 'base'", "int('2', 3, base=3)");
  }

  @Test
  public void testExec() throws Exception {
    ParserInput input =
        ParserInput.fromLines(
            "# a file in the build language",
            "",
            "x = [1, 2, 'foo', 4] + [1, 2, \"%s%d\" % ('foo', 1)]");
    Module module = Module.create();
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      Starlark.execFile(input, FileOptions.DEFAULT, module, thread);
    }
    assertThat(module.getGlobal("x"))
        .isEqualTo(
            StarlarkList.of(
                /*mutability=*/ null,
                StarlarkInt.of(1),
                StarlarkInt.of(2),
                "foo",
                StarlarkInt.of(4),
                StarlarkInt.of(1),
                StarlarkInt.of(2),
                "foo1"));
  }

  @Test
  public void testLoadsBindLocally() throws Exception {
    Module a = Module.create();
    Starlark.execFile(
        ParserInput.fromString("x = 1", "a.bzl"),
        FileOptions.DEFAULT,
        a,
        StarlarkThread.createTransient(Mutability.create(), StarlarkSemantics.DEFAULT));

    StarlarkThread bThread =
        StarlarkThread.createTransient(Mutability.create(), StarlarkSemantics.DEFAULT);
    bThread.setLoader(
        module -> {
          assertThat(module).isEqualTo("a.bzl");
          return a;
        });
    Module b = Module.create();
    Starlark.execFile(
        ParserInput.fromString("load('a.bzl', 'x')", "b.bzl"), FileOptions.DEFAULT, b, bThread);

    StarlarkThread cThread =
        StarlarkThread.createTransient(Mutability.create(), StarlarkSemantics.DEFAULT);
    cThread.setLoader(
        module -> {
          assertThat(module).isEqualTo("b.bzl");
          return b;
        });
    EvalException ex =
        assertThrows(
            EvalException.class,
            () ->
                Starlark.execFile(
                    ParserInput.fromString("load('b.bzl', 'x')", "c.bzl"),
                    FileOptions.DEFAULT,
                    Module.create(),
                    cThread));
    assertThat(ex).hasMessageThat().contains("file 'b.bzl' does not contain symbol 'x'");
  }

  @Test
  public void testTopLevelRebinding() throws Exception {
    FileOptions options =
        FileOptions.DEFAULT.toBuilder()
            .allowToplevelRebinding(true)
            .loadBindsGlobally(true)
            .build();

    Module m1 = Module.create();
    m1.setGlobal("x", "one");

    ParserInput input = ParserInput.fromLines("load('m1', 'x'); x = 'two'");
    Module m2 = Module.create();
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      thread.setLoader((name) -> m1);
      Starlark.execFile(input, options, m2, thread);
    }
    assertThat(m2.getGlobal("x")).isEqualTo("two");
  }

  @Test
  public void moduleWithDocString() throws Exception {
    Module module = Module.create();
    assertThat(module.getDocumentation()).isNull();
    ParserInput input =
        ParserInput.fromLines(
            "\"\"\"",
            "Module doc header", //
            "",
            "Module doc details",
            "\"\"\"",
            "",
            "\"\"\"Not module doc\"\"\"",
            "x = \"Not module doc\"",
            "def foo():",
            "  \"\"\"Not module doc\"\"\"",
            "  pass");
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      Starlark.execFile(input, FileOptions.DEFAULT, module, thread);
    }
    assertThat(module.getDocumentation()).isEqualTo("Module doc header\n\nModule doc details");
  }

  @Test
  public void moduleWithoutDocString() throws Exception {
    Module module = Module.create();
    ParserInput input =
        ParserInput.fromLines(
            "x = \"Not module doc\"", //
            "\"\"\"Not module doc\"\"\"",
            "def foo():",
            "  \"\"\"Not module doc\"\"\"",
            "  pass");
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      Starlark.execFile(input, FileOptions.DEFAULT, module, thread);
    }
    assertThat(module.getDocumentation()).isNull();
  }

  @Test
  public void moduleWithMultiplePrograms_usesFirstNonNullDocString() throws Exception {
    Module module = Module.create();
    assertThat(module.getDocumentation()).isNull();
    ParserInput inputWithoutModuleDocstring = ParserInput.fromLines("x = \"Not a module doc\"");
    ParserInput inputWithModuleDocstring1 =
        ParserInput.fromLines(
            "\"\"\"First non-null module doc\"\"\"", //
            "y = \"foo\"");
    ParserInput inputWithModuleDocstring2 =
        ParserInput.fromLines(
            "\"\"\"Second non-null module doc\"\"\"", //
            "z = \"bar\"");
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      Starlark.execFile(inputWithoutModuleDocstring, FileOptions.DEFAULT, module, thread);
      Starlark.execFile(inputWithModuleDocstring1, FileOptions.DEFAULT, module, thread);
      Starlark.execFile(inputWithModuleDocstring2, FileOptions.DEFAULT, module, thread);
    }
    assertThat(module.getDocumentation()).isEqualTo("First non-null module doc");
  }

  @Test
  public void moduleWithPresetDocstring() throws Exception {
    Module module = Module.create();
    module.setDocumentation("preset docstring");
    assertThat(module.getDocumentation()).isEqualTo("preset docstring");
    ParserInput input =
        ParserInput.fromLines(
            "\"\"\"Module doc from file\"\"\"", //
            "x = \"foo\"");
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      Starlark.execFile(input, FileOptions.DEFAULT, module, thread);
    }
    assertThat(module.getDocumentation()).isEqualTo("preset docstring");
  }
}
