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

import java.util.ArrayList;
import java.util.List;
import net.starlark.java.syntax.SyntaxError;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** A test class for functions and scoping. */
@RunWith(JUnit4.class)
public final class FunctionTest {

  private final EvaluationTestCase ev = new EvaluationTestCase();

  @Test
  public void testDef() throws Exception {
    ev.exec("def f(a, b=1, *args, c, d=2, e=3, **kwargs): pass");
    StarlarkFunction f = (StarlarkFunction) ev.lookup("f");
    assertThat(f).isNotNull();
    assertThat(f.getName()).isEqualTo("f");
    assertThat(f.getParameterNames())
        .containsExactly("a", "b", "c", "d", "e", "args", "kwargs")
        .inOrder();
    assertThat(f.getNumOrdinaryParameters()).isEqualTo(2); // a, b
    assertThat(f.getNumKeywordOnlyParameters()).isEqualTo(3); // c, d, e
    assertThat(f.hasVarargs()).isTrue();
    assertThat(f.hasKwargs()).isTrue();
    assertThat(getDefaults(f))
        .containsExactly(
            null, StarlarkInt.of(1), null, StarlarkInt.of(2), StarlarkInt.of(3), null, null)
        .inOrder();

    // same, sans varargs
    ev.exec("def g(a, b=1, *, c, d=2, e=3, **kwargs): pass");
    StarlarkFunction g = (StarlarkFunction) ev.lookup("g");
    assertThat(g.getParameterNames()).containsExactly("a", "b", "c", "d", "e", "kwargs").inOrder();
    assertThat(g.getNumOrdinaryParameters()).isEqualTo(2); // a, b
    assertThat(g.getNumKeywordOnlyParameters()).isEqualTo(3); // c, d, e
    assertThat(g.hasVarargs()).isFalse();
    assertThat(g.hasKwargs()).isTrue();
    assertThat(getDefaults(g))
        .containsExactly(null, StarlarkInt.of(1), null, StarlarkInt.of(2), StarlarkInt.of(3), null)
        .inOrder();
  }

  private static List<Object> getDefaults(StarlarkFunction fn) {
    List<Object> defaults = new ArrayList<>();
    for (int i = 0; i < fn.getParameterNames().size(); i++) {
      defaults.add(fn.getDefaultValue(i));
    }
    return defaults;
  }

  @Test
  public void testFunctionDefCallOuterFunc() throws Exception {
    List<Object> params = new ArrayList<>();
    createOuterFunction(params);
    ev.exec(
        "def func(a):", //
        "  outer_func(a)",
        "func(1)",
        "func(2)");
    assertThat(params).containsExactly(StarlarkInt.of(1), StarlarkInt.of(2)).inOrder();
  }

  private void createOuterFunction(final List<Object> params) throws Exception {
    StarlarkCallable outerFunc =
        new StarlarkCallable() {
          @Override
          public String getName() {
            return "outer_func";
          }

          @Override
          public NoneType call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
              throws EvalException {
            params.addAll(args);
            return Starlark.NONE;
          }
        };
    ev.update("outer_func", outerFunc);
  }

  @Test
  public void testFunctionDefNoEffectOutsideScope() throws Exception {
    ev.update("a", StarlarkInt.of(1));
    ev.exec(
        "def func():", //
        "  a = 2",
        "func()\n");
    assertThat(ev.lookup("a")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testFunctionDefGlobalVaribleReadInFunction() throws Exception {
    ev.exec(
        "a = 1", //
        "def func():",
        "  b = a",
        "  return b",
        "c = func()\n");
    assertThat(ev.lookup("c")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testFunctionDefLocalGlobalScope() throws Exception {
    ev.exec(
        "a = 1", //
        "def func():",
        "  a = 2",
        "  b = a",
        "  return b",
        "c = func()\n");
    assertThat(ev.lookup("c")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testFunctionDefLocalVariableReferencedBeforeAssignment() throws Exception {
    ev.checkEvalErrorContains(
        "local variable 'a' is referenced before assignment.",
        "a = 1",
        "def func():",
        "  b = a",
        "  a = 2",
        "  return b",
        "c = func()\n");
  }

  @Test
  public void testFunctionDefLocalVariableReferencedInCallBeforeAssignment() throws Exception {
    ev.checkEvalErrorContains(
        "local variable 'a' is referenced before assignment.",
        "def dummy(x):",
        "  pass",
        "a = 1",
        "def func():",
        "  dummy(a)",
        "  a = 2",
        "func()\n");
  }

  @Test
  public void testFunctionDefLocalVariableReferencedAfterAssignment() throws Exception {
    ev.exec(
        "a = 1", //
        "def func():",
        "  a = 2",
        "  b = a",
        "  a = 3",
        "  return b",
        "c = func()\n");
    assertThat(ev.lookup("c")).isEqualTo(StarlarkInt.of(2));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testStarlarkGlobalComprehensionIsAllowed() throws Exception {
    ev.exec("a = [i for i in [1, 2, 3]]\n");
    assertThat((Iterable<Object>) ev.lookup("a"))
        .containsExactly(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
        .inOrder();
  }

  @Test
  public void testFunctionReturn() throws Exception {
    ev.exec(
        "def func():", //
        "  return 2",
        "b = func()\n");
    assertThat(ev.lookup("b")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testFunctionReturnFromALoop() throws Exception {
    ev.exec(
        "def func():", //
        "  for i in [1, 2, 3, 4, 5]:",
        "    return i",
        "b = func()\n");
    assertThat(ev.lookup("b")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testFunctionExecutesProperly() throws Exception {
    ev.exec(
        "def func(a):",
        "  b = 1",
        "  if a:",
        "    b = 2",
        "  return b",
        "c = func(0)",
        "d = func(1)\n");
    assertThat(ev.lookup("c")).isEqualTo(StarlarkInt.of(1));
    assertThat(ev.lookup("d")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testFunctionCallFromFunction() throws Exception {
    final List<Object> params = new ArrayList<>();
    createOuterFunction(params);
    ev.exec(
        "def func2(a):",
        "  outer_func(a)",
        "def func1(b):",
        "  func2(b)",
        "func1(1)",
        "func1(2)\n");
    assertThat(params).containsExactly(StarlarkInt.of(1), StarlarkInt.of(2)).inOrder();
  }

  @Test
  public void testFunctionCallFromFunctionReadGlobalVar() throws Exception {
    ev.exec(
        "a = 1", //
        "def func2():",
        "  return a",
        "def func1():",
        "  return func2()",
        "b = func1()\n");
    assertThat(ev.lookup("b")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testFunctionParamCanShadowGlobalVarAfterGlobalVarIsRead() throws Exception {
    ev.exec(
        "a = 1",
        "def func2(a):",
        "  return 0",
        "def func1():",
        "  dummy = a",
        "  return func2(2)",
        "b = func1()\n");
    assertThat(ev.lookup("b")).isEqualTo(StarlarkInt.of(0));
  }

  @Test
  public void testSingleLineFunction() throws Exception {
    ev.exec(
        "def func(): return 'a'", //
        "s = func()\n");
    assertThat(ev.lookup("s")).isEqualTo("a");
  }

  @Test
  public void testFunctionReturnsDictionary() throws Exception {
    ev.exec(
        "def func(): return {'a' : 1}", //
        "d = func()",
        "a = d['a']\n");
    assertThat(ev.lookup("a")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testFunctionReturnsList() throws Exception {
    ev.exec(
        "def func(): return [1, 2, 3]", //
        "d = func()",
        "a = d[1]\n");
    assertThat(ev.lookup("a")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testFunctionNameAliasing() throws Exception {
    ev.exec(
        "def func(a):", //
        "  return a + 1",
        "alias = func",
        "r = alias(1)");
    assertThat(ev.lookup("r")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testCallingFunctionsWithMixedModeArgs() throws Exception {
    ev.exec(
        "def func(a, b, c):", //
        "  return a + b + c",
        "v = func(1, c = 2, b = 3)");
    assertThat(ev.lookup("v")).isEqualTo(StarlarkInt.of(6));
  }

  private String functionWithOptionalArgs() {
    return "def func(a, b = None, c = None):\n"
        + "  r = a + 'a'\n"
        + "  if b:\n"
        + "    r += 'b'\n"
        + "  if c:\n"
        + "    r += 'c'\n"
        + "  return r\n";
  }

  @Test
  public void testWhichOptionalArgsAreDefinedForFunctions() throws Exception {
    ev.exec(
        functionWithOptionalArgs(),
        "v1 = func('1', 1, 1)",
        "v2 = func(b = 2, a = '2', c = 2)",
        "v3 = func('3')",
        "v4 = func('4', c = 1)\n");
    assertThat(ev.lookup("v1")).isEqualTo("1abc");
    assertThat(ev.lookup("v2")).isEqualTo("2abc");
    assertThat(ev.lookup("v3")).isEqualTo("3a");
    assertThat(ev.lookup("v4")).isEqualTo("4ac");
  }

  @Test
  public void testDefaultArguments() throws Exception {
    ev.exec(
        "def func(a, b = 'b', c = 'c'):",
        "  return a + b + c",
        "v1 = func('a', 'x', 'y')",
        "v2 = func(b = 'x', a = 'a', c = 'y')",
        "v3 = func('a')",
        "v4 = func('a', c = 'y')\n");
    assertThat(ev.lookup("v1")).isEqualTo("axy");
    assertThat(ev.lookup("v2")).isEqualTo("axy");
    assertThat(ev.lookup("v3")).isEqualTo("abc");
    assertThat(ev.lookup("v4")).isEqualTo("aby");
  }

  @Test
  public void testDefaultArgumentsInsufficientArgNum() throws Exception {
    ev.checkEvalError(
        "func() missing 1 required positional argument: a",
        "def func(a, b = 'b', c = 'c'):",
        "  return a + b + c",
        "func()");
  }

  @Test
  public void testArgsIsNotIterable() throws Exception {
    ev.checkEvalError(
        "argument after * must be an iterable, not int",
        "def func1(a, b): return a + b",
        "func1('a', *42)");

    ev.checkEvalError(
        "argument after * must be an iterable, not string",
        "def func2(a, b): return a + b",
        "func2('a', *'str')");
  }

  @Test
  public void testKeywordOnly() throws Exception {
    ev.checkEvalError(
        "func() missing 1 required keyword-only argument: b", //
        "def func(a, *, b): pass",
        "func(5)");

    ev.checkEvalError(
        "func() accepts no more than 1 positional argument but got 2",
        "def func(a, *, b): pass",
        "func(5, 6)");

    ev.exec("def func(a, *, b, c = 'c'): return a + b + c");
    assertThat(ev.eval("func('a', b = 'b')")).isEqualTo("abc");
    assertThat(ev.eval("func('a', b = 'b', c = 'd')")).isEqualTo("abd");
  }

  @Test
  public void testStarArgsAndKeywordOnly() throws Exception {
    ev.checkEvalError(
        "func() missing 1 required keyword-only argument: b",
        "def func(a, *args, b): pass",
        "func(5)");

    ev.checkEvalError(
        "func() missing 1 required keyword-only argument: b",
        "def func(a, *args, b): pass",
        "func(5, 6)");

    ev.exec("def func(a, *args, b, c = 'c'): return a + str(args) + b + c");
    assertThat(ev.eval("func('a', b = 'b')")).isEqualTo("a()bc");
    assertThat(ev.eval("func('a', b = 'b', c = 'd')")).isEqualTo("a()bd");
    assertThat(ev.eval("func('a', 1, 2, b = 'b')")).isEqualTo("a(1, 2)bc");
    assertThat(ev.eval("func('a', 1, 2, b = 'b', c = 'd')")).isEqualTo("a(1, 2)bd");
  }

  @Test
  public void testCannotPassResidualsByName() throws Exception {
    ev.checkEvalError(
        "f() got unexpected keyword argument: args", "def f(*args): pass", "f(args=[])");

    ev.exec("def f(**kwargs): return kwargs");
    assertThat(Starlark.repr(ev.eval("f(kwargs=1)"))).isEqualTo("{\"kwargs\": 1}");
  }

  @Test
  public void testKeywordOnlyAfterStarArg() throws Exception {
    ev.checkEvalError(
        "func() missing 1 required keyword-only argument: c",
        "def func(a, *b, c): pass",
        "func(5)");

    ev.checkEvalError(
        "func() missing 1 required keyword-only argument: c",
        "def func(a, *b, c): pass",
        "func(5, 6, 7)");

    ev.exec("def func(a, *b, c): return a + str(b) + c");
    assertThat(ev.eval("func('a', c = 'c')")).isEqualTo("a()c");
    assertThat(ev.eval("func('a', 1, c = 'c')")).isEqualTo("a(1,)c");
    assertThat(ev.eval("func('a', 1, 2, c = 'c')")).isEqualTo("a(1, 2)c");
  }

  @Test
  public void testKwargsBadKey() throws Exception {
    ev.checkEvalError(
        "keywords must be strings, not int", //
        "def func(a, b): return a + b",
        "func('a', **{3: 1})");
  }

  @Test
  public void testKwargsIsNotDict() throws Exception {
    ev.checkEvalError(
        "argument after ** must be a dict, not int",
        "def func(a, b): return a + b",
        "func('a', **42)");
  }

  @Test
  public void testKwargsCollision() throws Exception {
    ev.checkEvalError(
        "func() got multiple values for parameter 'b'",
        "def func(a, b): return a + b",
        "func('a', 'b', **{'b': 'foo'})");
  }

  @Test
  public void testKwargsCollisionWithNamed() throws Exception {
    ev.checkEvalError(
        "func() got multiple values for parameter 'b'",
        "def func(a, b): return a + b",
        "func('a', b = 'b', **{'b': 'foo'})");
  }

  @Test
  public void testDefaultArguments2() throws Exception {
    ev.exec(
        "a = 2",
        "def foo(x=a): return x",
        "def bar():",
        "  a = 3",
        "  return foo()",
        "v = bar()\n");
    assertThat(ev.lookup("v")).isEqualTo(StarlarkInt.of(2));
  }

  @Test
  public void testMixingPositionalOptional() throws Exception {
    ev.exec(
        "def f(name, value = '', optional = ''):", //
        "  return value",
        "v = f('name', 'value')");
    assertThat(ev.lookup("v")).isEqualTo("value");
  }

  @Test
  public void testStarArg() throws Exception {
    ev.exec(
        "def f(name, value = '1', optional = '2'): return name + value + optional",
        "v1 = f(*['name', 'value'])",
        "v2 = f('0', *['name', 'value'])",
        "v3 = f('0', optional = '3', *['b'])",
        "v4 = f(name='a', *[])\n");
    assertThat(ev.lookup("v1")).isEqualTo("namevalue2");
    assertThat(ev.lookup("v2")).isEqualTo("0namevalue");
    assertThat(ev.lookup("v3")).isEqualTo("0b3");
    assertThat(ev.lookup("v4")).isEqualTo("a12");
  }

  @Test
  public void testStarParam() throws Exception {
    ev.exec(
        "def f(name, value = '1', optional = '2', *rest):",
        "  r = name + value + optional + '|'",
        "  for x in rest: r += x",
        "  return r",
        "v1 = f('a', 'b', 'c', 'd', 'e')",
        "v2 = f('a', optional='b', value='c')",
        "v3 = f('a')");
    assertThat(ev.lookup("v1")).isEqualTo("abc|de");
    assertThat(ev.lookup("v2")).isEqualTo("acb|");
    assertThat(ev.lookup("v3")).isEqualTo("a12|");
  }

  @Test
  public void testKwParam() throws Exception {
    ev.exec(
        "def foo(a, b, c=3, d=4, g=7, h=8, *args, **kwargs):\n"
            + "  return (a, b, c, d, g, h, args, kwargs)\n"
            + "v1 = foo(1, 2)\n"
            + "v2 = foo(1, h=9, i=0, *['x', 'y', 'z', 't'])\n"
            + "v3 = foo(1, i=0, *[2, 3, 4, 5, 6, 7, 8])\n"
            + "def bar(**kwargs):\n"
            + "  return kwargs\n"
            + "b1 = bar(name='foo', type='jpg', version=42).items()\n"
            + "b2 = bar()\n");

    assertThat(Starlark.repr(ev.lookup("v1"))).isEqualTo("(1, 2, 3, 4, 7, 8, (), {})");
    assertThat(Starlark.repr(ev.lookup("v2")))
        .isEqualTo("(1, \"x\", \"y\", \"z\", \"t\", 9, (), {\"i\": 0})");
    assertThat(Starlark.repr(ev.lookup("v3"))).isEqualTo("(1, 2, 3, 4, 5, 6, (7, 8), {\"i\": 0})");
    assertThat(Starlark.repr(ev.lookup("b1")))
        .isEqualTo("[(\"name\", \"foo\"), (\"type\", \"jpg\"), (\"version\", 42)]");
    assertThat(Starlark.repr(ev.lookup("b2"))).isEqualTo("{}");
  }

  @Test
  public void testTrailingCommas() throws Exception {
    // Test that trailing commas are allowed in function definitions and calls
    // even after last *args or **kwargs expressions, like python3
    ev.exec(
        "def f(*args, **kwargs): pass\n"
            + "v1 = f(1,)\n"
            + "v2 = f(*(1,2),)\n"
            + "v3 = f(a=1,)\n"
            + "v4 = f(**{\"a\": 1},)\n");

    assertThat(Starlark.repr(ev.lookup("v1"))).isEqualTo("None");
    assertThat(Starlark.repr(ev.lookup("v2"))).isEqualTo("None");
    assertThat(Starlark.repr(ev.lookup("v3"))).isEqualTo("None");
    assertThat(Starlark.repr(ev.lookup("v4"))).isEqualTo("None");
  }

  @Test
  public void testCalls() throws Exception {
    ev.exec("def f(a, b = None): return a, b");

    assertThat(Starlark.repr(ev.eval("f(1)"))).isEqualTo("(1, None)");
    assertThat(Starlark.repr(ev.eval("f(1, 2)"))).isEqualTo("(1, 2)");
    assertThat(Starlark.repr(ev.eval("f(a=1)"))).isEqualTo("(1, None)");
    assertThat(Starlark.repr(ev.eval("f(a=1, b=2)"))).isEqualTo("(1, 2)");
    assertThat(Starlark.repr(ev.eval("f(b=2, a=1)"))).isEqualTo("(1, 2)");

    ev.checkEvalError(
        "f() missing 1 required positional argument: a", //
        "f()");
    ev.checkEvalError(
        "f() accepts no more than 2 positional arguments but got 3", //
        "f(1, 2, 3)");
    ev.checkEvalError(
        "f() got unexpected keyword arguments: c, d", //
        "f(1, 2, c=3, d=4)");
    ev.checkEvalError(
        "f() missing 1 required positional argument: a", //
        "f(b=2)");
    ev.checkEvalError(
        "f() missing 1 required positional argument: a", //
        "f(b=2)");
    ev.checkEvalError(
        "f() got multiple values for parameter 'a'", //
        "f(2, a=1)");
    ev.checkEvalError(
        "f() got unexpected keyword argument: c", //
        "f(b=2, a=1, c=3)");

    ev.exec("def g(*, one, two, three): pass");
    ev.checkEvalError(
        "g() got unexpected keyword argument: tree (did you mean 'three'?)", //
        "g(tree=3)");
    ev.checkEvalError(
        "g() does not accept positional arguments, but got 3", //
        "g(1, 2 ,3)");
  }

  @Test
  public void aliasing_keepsOriginalName() throws Exception {
    ev.exec(
        """
        _y = 10

        def _f(x):
          return x * _y

        g = _f
        """);
    var f = (StarlarkFunction) ev.lookup("_f");
    assertThat(ev.lookup("g")).isSameInstanceAs(f); // "g" is an alias for "_f"

    SymbolGenerator.Symbol<?> id = f.getToken();
    assertThat(id.isGlobal()).isTrue();

    SymbolGenerator.GlobalSymbol<?> globalId = (SymbolGenerator.GlobalSymbol<?>) id;
    assertThat(globalId.getName()).isEqualTo("_f");
  }

  @Test
  public void exportedLambdas_haveGlobalIds() throws Exception {
    ev.exec(
        """
        x = lambda v: "--" + v
        y = x
        """);
    var x = (StarlarkFunction) ev.lookup("x");
    assertThat(ev.lookup("y")).isSameInstanceAs(x); // "y" is an alias for "x"

    SymbolGenerator.Symbol<?> id = x.getToken();
    assertThat(id.isGlobal()).isTrue();
    SymbolGenerator.GlobalSymbol<?> globalId = (SymbolGenerator.GlobalSymbol<?>) id;
    assertThat(globalId.getName()).isEqualTo("x");
  }

  @Test
  public void localLambdas_haveLocalIds() throws Exception {
    ev.exec(
        """
        x = (lambda v: v + 1,)
        """);
    var x = (Tuple) ev.lookup("x");
    var lambda = (StarlarkFunction) x.get(0);

    SymbolGenerator.Symbol<?> id = lambda.getToken();
    assertThat(id.isGlobal()).isFalse();
  }

  @Test
  public void innerLambdas_canBePublished() throws Exception {
    ev.exec(
        """
        x = (lambda v: v + 1,)
        y = x[0]
        """);
    var x = (Tuple) ev.lookup("x");
    var y = (StarlarkFunction) ev.lookup("y");
    assertThat(x.get(0)).isSameInstanceAs(y);

    SymbolGenerator.Symbol<?> id = y.getToken();
    assertThat(id.isGlobal()).isTrue();
    SymbolGenerator.GlobalSymbol<?> globalId = (SymbolGenerator.GlobalSymbol<?>) id;
    assertThat(globalId.getName()).isEqualTo("y");
  }

  @Test
  public void globalFunctionReassignment_fails() throws Exception {
    var thrown =
        assertThrows(
            SyntaxError.Exception.class,
            () ->
                ev.exec(
                    """
                    x = lambda v: v + 1
                    x = lambda v: v + 2
                    """));
    assertThat(thrown).hasMessageThat().contains("'x' redeclared at top level");
  }

  // Regression test for b/385394075
  @Test
  public void positionalOnlyCall_setsKeywordArgsVarargsAndKwargs() throws Exception {
    ev.exec(
        """
        def f(a, b, *args, k = 42, **kwargs):
            kwargs["mutable"] = True  # verify that **kwargs is a mutable dict
            return "k=%s args=%s kwargs=%s" % (repr(k), repr(args), repr(kwargs))
        """);
    StarlarkFunction f = (StarlarkFunction) ev.lookup("f");
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      assertThat((String) Starlark.positionalOnlyCall(thread, f, "a", "b", "c", "d"))
          .isEqualTo("k=42 args=(\"c\", \"d\") kwargs={\"mutable\": True}");
    }
  }
}
