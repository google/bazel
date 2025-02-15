// Copyright 2015 The Bazel Authors. All rights reserved.
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
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.DoNotCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of Starlark evaluation. */
// There is no clear distinction between this and EvaluationTest.
// TODO(adonovan): reorganize.
@RunWith(JUnit4.class)
public final class StarlarkEvaluationTest {

  private final EvaluationTestCase ev = new EvaluationTestCase();

  static class Bad {
    Bad () {
    }
  }

  @StarlarkMethod(name = "foobar", documented = false)
  public String foobar() {
    return "foobar";
  }

  @DoNotCall("Always throws java.lang.InterruptedException")
  @StarlarkMethod(name = "interrupted_function", documented = false)
  public NoneType interruptedFunction() throws InterruptedException {
    throw new InterruptedException();
  }

  @StarlarkMethod(name = "stackoverflow", documented = false)
  public int stackoverflow() {
    return true ? stackoverflow() : 0; // (defeat static recursion checker)
  }

  @StarlarkMethod(name = "throwoom", documented = false)
  public void throwoom() {
    throw new OutOfMemoryError("Java heap space");
  }

  @StarlarkMethod(name = "thrownpe", documented = false)
  public void thrownpe() {
    throw new NullPointerException("oops");
  }

  private static Object getattr(Object x, String name) {
    try {
      return Starlark.getattr(
          Mutability.IMMUTABLE, StarlarkSemantics.DEFAULT, x, name, Starlark.NONE);
    } catch (EvalException | InterruptedException ex) {
      throw new IllegalStateException(ex);
    }
  }

  // A trivial struct-like class with Starlark fields defined by a map.
  private static class SimpleStruct implements StarlarkValue, Structure {
    final ImmutableMap<String, Object> fields;

    SimpleStruct(ImmutableMap<String, Object> fields) {
      this.fields = fields;
    }

    @Override
    public ImmutableCollection<String> getFieldNames() {
      return fields.keySet();
    }

    @Override
    public Object getValue(String name) {
      return fields.get(name);
    }

    @Override
    public String getErrorMessageForUnknownField(String name) {
      return null;
    }

    @Override
    public void repr(Printer p) {
      // This repr function prints only the fields.
      // Any methods are still accessible through dir/getattr/hasattr.
      p.append("simplestruct(");
      String sep = "";
      for (Map.Entry<String, Object> e : fields.entrySet()) {
        p.append(sep).append(e.getKey()).append(" = ").repr(e.getValue());
        sep = ", ";
      }
      p.append(")");
    }
  }

  @StarlarkBuiltin(name = "Mock", doc = "")
  class Mock implements StarlarkValue {
    @StarlarkMethod(
        name = "MockFn",
        selfCall = true,
        documented = false,
        parameters = {
          @Param(name = "pos", positional = true),
        })
    public String selfCall(String myName) {
      return "I'm a mock named " + myName;
    }

    @StarlarkMethod(
        name = "value_of",
        parameters = {@Param(name = "str")},
        documented = false)
    public Integer valueOf(String str) {
      return Integer.valueOf(str);
    }

    @StarlarkMethod(
        name = "is_empty",
        parameters = {@Param(name = "str")},
        documented = false)
    public Boolean isEmpty(String str) {
      return str.isEmpty();
    }
    public void value() {}

    @StarlarkMethod(name = "return_bad", documented = false)
    public Bad returnBad() {
      return new Bad(); // not a legal Starlark value
    }

    @StarlarkMethod(name = "struct_field", documented = false, structField = true)
    public String structField() {
      return "a";
    }

    @StarlarkMethod(
        name = "struct_field_with_extra",
        documented = false,
        structField = true,
        useStarlarkSemantics = true)
    public String structFieldWithExtra(StarlarkSemantics sem) {
      return "struct_field_with_extra("
        + (sem != null)
        + ")";
    }

    @StarlarkMethod(name = "struct_field_callable", documented = false, structField = true)
    public Object structFieldCallable() {
      return getattr(StarlarkEvaluationTest.this, "foobar");
    }

    @StarlarkMethod(name = "interrupted_struct_field", documented = false, structField = true)
    public Object structFieldInterruptedCallable() throws InterruptedException {
      throw new InterruptedException();
    }

    @StarlarkMethod(name = "function", documented = false, structField = false)
    public String function() {
      return "a";
    }

    @SuppressWarnings("unused")
    @StarlarkMethod(
        name = "nullfunc_failing",
        parameters = {
          @Param(name = "p1"),
          @Param(name = "p2"),
        },
        documented = false,
        allowReturnNones = false)
    public StarlarkValue nullfuncFailing(String p1, StarlarkInt p2) {
      return null;
    }

    @StarlarkMethod(name = "nullfunc_working", documented = false, allowReturnNones = true)
    @Nullable
    public StarlarkValue nullfuncWorking() {
      return null;
    }

    @StarlarkMethod(name = "voidfunc", documented = false)
    public void voidfunc() {}

    @StarlarkMethod(name = "string_list", documented = false)
    public ImmutableList<String> stringList() {
      return ImmutableList.<String>of("a", "b");
    }

    @StarlarkMethod(name = "string", documented = false)
    public String string() {
      return "a";
    }

    @StarlarkMethod(name = "string_list_dict", documented = false)
    public Map<String, List<String>> stringListDict() {
      return ImmutableMap.of("a", StarlarkList.immutableOf("b", "c"));
    }

    @StarlarkMethod(
        name = "with_params",
        documented = false,
        parameters = {
          @Param(name = "pos1"),
          @Param(name = "pos2", defaultValue = "False"),
          @Param(name = "posOrNamed", defaultValue = "False", positional = true, named = true),
          @Param(name = "named", positional = false, named = true),
          @Param(name = "optionalNamed", defaultValue = "False", positional = false, named = true),
          @Param(name = "acceptsAny", defaultValue = "'a'", positional = false, named = true),
          @Param(
              name = "noneable",
              allowedTypes = {
                @ParamType(type = StarlarkInt.class),
                @ParamType(type = NoneType.class),
              },
              defaultValue = "None",
              positional = false,
              named = true),
          @Param(
              name = "multi",
              allowedTypes = {
                @ParamType(type = String.class),
                @ParamType(type = StarlarkInt.class),
                @ParamType(type = Sequence.class, generic1 = StarlarkInt.class),
                @ParamType(type = NoneType.class),
              },
              defaultValue = "None",
              positional = false,
              named = true)
        })
    public String withParams(
        StarlarkInt pos1,
        boolean pos2,
        boolean posOrNamed,
        boolean named,
        boolean optionalNamed,
        Object acceptsAny,
        Object noneable,
        Object multi) {
      return "with_params("
          + pos1
          + ", "
          + pos2
          + ", "
          + posOrNamed
          + ", "
          + named
          + ", "
          + optionalNamed
          + ", "
          + acceptsAny
          + (noneable != Starlark.NONE ? ", " + noneable : "")
          + (multi != Starlark.NONE ? ", " + multi : "")
          + ")";
    }

    @StarlarkMethod(name = "with_extra", documented = false, useStarlarkThread = true)
    public String withExtraInterpreterParams(StarlarkThread thread) {
      return "with_extra(" + thread.getCallerLocation().line() + ")";
    }

    @StarlarkMethod(
        name = "with_params_and_extra",
        documented = false,
        parameters = {
          @Param(name = "pos1"),
          @Param(name = "pos2", defaultValue = "False"),
          @Param(name = "posOrNamed", defaultValue = "False", positional = true, named = true),
          @Param(name = "named", positional = false, named = true),
          @Param(name = "optionalNamed", defaultValue = "False", positional = false, named = true),
          @Param(name = "acceptsAny", defaultValue = "'a'", positional = false, named = true),
          @Param(
              name = "noneable",
              allowedTypes = {
                @ParamType(type = StarlarkInt.class),
                @ParamType(type = NoneType.class),
              },
              defaultValue = "None",
              positional = false,
              named = true),
          @Param(
              name = "multi",
              allowedTypes = {
                @ParamType(type = String.class),
                @ParamType(type = StarlarkInt.class),
                @ParamType(type = Sequence.class, generic1 = StarlarkInt.class),
                @ParamType(type = NoneType.class),
              },
              defaultValue = "None",
              positional = false,
              named = true)
        },
        useStarlarkThread = true)
    public String withParamsAndExtraInterpreterParams(
        StarlarkInt pos1,
        boolean pos2,
        boolean posOrNamed,
        boolean named,
        boolean optionalNamed,
        Object acceptsAny,
        Object noneable,
        Object multi,
        StarlarkThread thread) {
      return "with_params_and_extra("
          + pos1
          + ", "
          + pos2
          + ", "
          + posOrNamed
          + ", "
          + named
          + ", "
          + optionalNamed
          + ", "
          + acceptsAny
          + (noneable != Starlark.NONE ? ", " + noneable : "")
          + (multi != Starlark.NONE ? ", " + multi : "")
          + ", "
          + thread.getCallerLocation().line()
          + ")";
    }

    @StarlarkMethod(
        name = "proxy_methods_object",
        doc = "Returns a struct containing all callable method objects of this mock",
        useStarlarkThread = true)
    public Structure proxyMethodsObject(StarlarkThread thread)
        throws EvalException, InterruptedException {
      ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
      for (String name : Starlark.dir(thread.mutability(), thread.getSemantics(), this)) {
        if (name.equals("interrupted_struct_field")) {
          continue; // skip, because getattr would be interrupted
        }
        Object v = Starlark.getattr(thread.mutability(), thread.getSemantics(), this, name, null);
        builder.put(name, v);
      }
      return new SimpleStruct(builder.buildOrThrow());
    }

    @StarlarkMethod(
        name = "with_args_and_thread",
        documented = false,
        parameters = {
          @Param(name = "pos1"),
          @Param(name = "pos2", defaultValue = "False"),
          @Param(name = "named", positional = false, named = true),
        },
        extraPositionals = @Param(name = "args"),
        useStarlarkThread = true)
    public String withArgsAndThread(
        StarlarkInt pos1, boolean pos2, boolean named, Sequence<?> args, StarlarkThread thread) {
      String argsString = debugPrintArgs(args, thread);
      return "with_args_and_thread("
          + pos1
          + ", "
          + pos2
          + ", "
          + named
          + ", "
          + argsString
          + ")";
    }

    @StarlarkMethod(
        name = "with_kwargs",
        documented = false,
        parameters = {
          @Param(name = "pos", defaultValue = "False"),
          @Param(name = "named", positional = false, named = true),
        },
        extraKeywords = @Param(name = "kwargs"))
    public String withKwargs(boolean pos, boolean named, Dict<String, Object> kwargs) {
      String kwargsString =
          "kwargs("
              + kwargs
                  .entrySet()
                  .stream()
                  .map(entry -> entry.getKey() + "=" + entry.getValue())
                  .collect(joining(", "))
              + ")";
      return "with_kwargs(" + pos + ", " + named + ", " + kwargsString + ")";
    }

    @StarlarkMethod(
        name = "with_args_and_kwargs",
        documented = false,
        parameters = {
          @Param(name = "foo", named = true, positional = true),
        },
        extraPositionals = @Param(name = "args"),
        extraKeywords = @Param(name = "kwargs"),
        useStarlarkThread = true)
    public String withArgsAndKwargs(
        String foo, Tuple args, Dict<String, Object> kwargs, StarlarkThread thread) {
      String argsString = debugPrintArgs(args, thread);
      String kwargsString =
          "kwargs("
              + kwargs
                  .entrySet()
                  .stream()
                  .map(entry -> entry.getKey() + "=" + entry.getValue())
                  .collect(joining(", "))
              + ")";
      return "with_args_and_kwargs(" + foo + ", " + argsString + ", " + kwargsString + ")";
    }

    @Override
    public String toString() {
      return "<mock>";
    }
  }

  private static String debugPrintArgs(Iterable<?> args, StarlarkThread thread) {
    Printer p = new Printer();
    p.append("args(");
    String sep = "";
    for (Object arg : args) {
      p.append(sep).debugPrint(arg, thread);
      sep = ", ";
    }
    return p.append(")").toString();
  }

  @StarlarkBuiltin(name = "MockInterface", doc = "")
  static interface MockInterface extends StarlarkValue {
    @StarlarkMethod(
        name = "is_empty_interface",
        parameters = {@Param(name = "str")},
        documented = false)
    public Boolean isEmptyInterface(String str);
  }

  @StarlarkBuiltin(name = "MockSubClass", doc = "")
  final class MockSubClass extends Mock implements MockInterface {
    @Override
    public Boolean isEmpty(String str) {
      return str.isEmpty();
    }
    @Override
    public Boolean isEmptyInterface(String str) {
      return str.isEmpty();
    }
  }

  @StarlarkBuiltin(name = "ParamterizedMock", doc = "")
  static interface ParameterizedApi<ObjectT> extends StarlarkValue {
    @StarlarkMethod(
        name = "method",
        documented = false,
        parameters = {
          @Param(name = "foo", named = true, positional = true),
        })
    public ObjectT method(ObjectT o);
  }

  static final class ParameterizedMock implements ParameterizedApi<String> {
    @Override
    public String method(String o) {
      return o;
    }
  }

  // Verifies that a method implementation overriding a parameterized annotated interface method
  // is still treated as Starlark-callable. Concretely, method() below should be treated as
  // callable even though its method signature isn't an *exact* match of the annotated method
  // declaration, due to the interface's method declaration being generic.
  @Test
  public void testParameterizedMock() throws Exception {
    ev.new Scenario()
        .update("mock", new ParameterizedMock())
        .setUp("result = mock.method('bar')")
        .testLookup("result", "bar");
  }

  @Test
  public void testSimpleIf() throws Exception {
    ev.new Scenario()
        .setUp("def foo():", "  a = 0", "  x = 0", "  if x: a = 5", "  return a", "a = foo()")
        .testLookup("a", StarlarkInt.of(0));
  }

  @Test
  public void testIfPass() throws Exception {
    ev.new Scenario()
        .setUp("def foo():", "  a = 1", "  x = True", "  if x: pass", "  return a", "a = foo()")
        .testLookup("a", StarlarkInt.of(1));
  }

  @Test
  public void testNestedIf() throws Exception {
    executeNestedIf(0, 0, 0);
    executeNestedIf(1, 0, 3);
    executeNestedIf(1, 1, 5);
  }

  private void executeNestedIf(int x, int y, int expected) throws Exception {
    String fun = String.format("foo%s%s", x, y);
    ev.new Scenario()
        .setUp(
            "def " + fun + "():",
            "  x = " + x,
            "  y = " + y,
            "  a = 0",
            "  b = 0",
            "  if x:",
            "    if y:",
            "      a = 2",
            "    b = 3",
            "  return a + b",
            "x = " + fun + "()")
        .testLookup("x", StarlarkInt.of(expected));
  }

  @Test
  public void testIfElse() throws Exception {
    executeIfElse("foo", "something", 2);
    executeIfElse("bar", "", 3);
  }

  private void executeIfElse(String fun, String y, int expected) throws Exception {
    ev.new Scenario()
        .setUp(
            "def " + fun + "():",
            "  y = '" + y + "'",
            "  x = 5",
            "  if x:",
            "    if y: a = 2",
            "    else: a = 3",
            "  return a",
            "z = " + fun + "()")
        .testLookup("z", StarlarkInt.of(expected));
  }

  @Test
  public void testIfElifElse_ifExecutes() throws Exception {
    execIfElifElse(1, 0, 1);
  }

  @Test
  public void testIfElifElse_elifExecutes() throws Exception {
    execIfElifElse(0, 1, 2);
  }

  @Test
  public void testIfElifElse_elseExecutes() throws Exception {
    execIfElifElse(0, 0, 3);
  }

  private void execIfElifElse(int x, int y, int v) throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  x = " + x + "",
            "  y = " + y + "",
            "  if x:",
            "    return 1",
            "  elif y:",
            "    return 2",
            "  else:",
            "    return 3",
            "v = foo()")
        .testLookup("v", StarlarkInt.of(v));
  }

  @Test
  public void testForOnList() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  s = ''",
            "  for i in ['hello', ' ', 'world']:",
            "    s = s + i",
            "  return s",
            "s = foo()")
        .testLookup("s", "hello world");
  }

  @Test
  public void testForAssignmentList() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  d = ['a', 'b', 'c']",
            "  s = ''",
            "  for i in d:",
            "    s = s + i",
            "    d = ['d', 'e', 'f']", // check that we use the old list
            "  return s",
            "s = foo()")
        .testLookup("s", "abc");
  }

  @Test
  public void testForAssignmentDict() throws Exception {
    ev.new Scenario()
        .setUp(
            "def func():",
            "  d = {'a' : 1, 'b' : 2, 'c' : 3}",
            "  s = ''",
            "  for i in d:",
            "    s = s + i",
            "    d = {'d' : 1, 'e' : 2, 'f' : 3}",
            "  return s",
            "s = func()")
        .testLookup("s", "abc");
  }

  @Test
  public void testForUpdateList() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  xs = [1, 2, 3]",
            "  for x in xs:",
            "    if x == 1:",
            "      xs.append(10)")
        .testIfErrorContains(
            "list value is temporarily immutable due to active for-loop iteration", "foo()");
  }

  @Test
  public void testForUpdateDict() throws Exception {
    ev.new Scenario()
        .setUp("def foo():", "  d = {'a': 1, 'b': 2, 'c': 3}", "  for k in d:", "    d[k] *= 2")
        .testIfErrorContains(
            "dict value is temporarily immutable due to active for-loop iteration", "foo()");
  }

  @Test
  public void testForUnlockedAfterBreak() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  xs = [1, 2]",
            "  for x in xs:",
            "    break",
            "  xs.append(3)",
            "  return xs")
        .testEval("foo()", "[1, 2, 3]");
  }

  @Test
  public void testForNestedOnSameListStillLocked() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  xs = [1, 2]",
            "  ys = []",
            "  for x1 in xs:",
            "    for x2 in xs:",
            "      ys.append(x1 * x2)",
            "    xs.append(4)",
            "  return ys")
        .testIfErrorContains(
            "list value is temporarily immutable due to active for-loop iteration", "foo()");
  }

  @Test
  public void testForNestedOnSameListUnlockedAtEnd() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  xs = [1, 2]",
            "  ys = []",
            "  for x1 in xs:",
            "    for x2 in xs:",
            "      ys.append(x1 * x2)",
            "  xs.append(4)",
            "  return ys")
        .testEval("foo()", "[1, 2, 2, 4]");
  }

  @Test
  public void testForNestedWithListCompGood() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  xs = [1, 2]",
            "  ys = []",
            "  for x in xs:",
            "    zs = [None for x in xs for y in (ys.append(x) or ys)]",
            "  return ys")
        .testEval("foo()", "[1, 2, 1, 2]");
  }
  @Test
  public void testForNestedWithListCompBad() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  xs = [1, 2, 3]",
            "  ys = []",
            "  for x in xs:",
            "    zs = [None for x in xs for y in (xs.append(x) or ys)]",
            "  return ys")
        .testIfErrorContains(
            "list value is temporarily immutable due to active for-loop iteration", "foo()");
  }

  @Test
  public void testForDeepUpdate() throws Exception {
    // Check that indirectly reachable values can still be manipulated as normal.
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  xs = [['a'], ['b'], ['c']]",
            "  ys = []",
            "  for x in xs:",
            "    for y in x:",
            "      ys.append(y)",
            "    xs[2].append(x[0])",
            "  return ys",
            "ys = foo()")
        .testLookup("ys", StarlarkList.of(null, "a", "b", "c", "a", "b"));
  }

  @Test
  public void testForNotIterable() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfErrorContains(
            "type 'int' is not iterable",
            "def func():",
            "  for i in mock.value_of('1'): a = i",
            "func()\n");
  }

  @Test
  public void testForStringNotIterable() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfErrorContains(
            "type 'string' is not iterable", "def func():", "  for i in 'abc': a = i", "func()\n");
  }

  @Test
  public void testForOnDictionary() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  d = {1: 'a', 2: 'b', 3: 'c'}",
            "  s = ''",
            "  for i in d: s = s + d[i]",
            "  return s",
            "s = foo()")
        .testLookup("s", "abc");
  }

  @Test
  public void testBadDictKey() throws Exception {
    ev.new Scenario().testIfErrorContains("unhashable type: 'list'", "{ [1, 2]: [3, 4] }");
  }

  @Test
  public void testForLoopReuseVariable() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  s = ''",
            "  for i in ['a', 'b']:",
            "    for i in ['c', 'd']: s = s + i",
            "  return s",
            "s = foo()")
        .testLookup("s", "cdcd");
  }

  @Test
  public void testForLoopMultipleVariables() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  s = ''",
            "  for [i, j] in [[1, 2], [3, 4]]:",
            "    s = s + str(i) + str(j) + '.'",
            "  return s",
            "s = foo()")
        .testLookup("s", "12.34.");
  }

  @Test
  public void testForLoopBreak() throws Exception {
    simpleFlowTest("break", 1);
  }

  @Test
  public void testForLoopContinue() throws Exception {
    simpleFlowTest("continue", 10);
  }

  @SuppressWarnings("unchecked")
  private void simpleFlowTest(String statement, int expected) throws Exception {
    ev.exec(
        "def foo():",
        "  s = 0",
        "  hit = 0",
        "  for i in range(0, 10):",
        "    s = s + 1",
        "    " + statement + "",
        "    hit = 1",
        "  return [s, hit]",
        "x = foo()");
    assertThat((Iterable<Object>) ev.lookup("x"))
        .containsExactly(StarlarkInt.of(expected), StarlarkInt.of(0))
        .inOrder();
  }

  @Test
  public void testForLoopBreakFromDeeperBlock() throws Exception {
    flowFromDeeperBlock("break", 1);
    flowFromNestedBlocks("break", 29);
  }

  @Test
  public void testForLoopContinueFromDeeperBlock() throws Exception {
    flowFromDeeperBlock("continue", 5);
    flowFromNestedBlocks("continue", 39);
  }

  private void flowFromDeeperBlock(String statement, int expected) throws Exception {
    ev.exec(
        "def foo():",
        "   s = 0",
        "   for i in range(0, 10):",
        "       if i % 2 != 0:",
        "           " + statement + "",
        "       s = s + 1",
        "   return s",
        "x = foo()");
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(expected));
  }

  private void flowFromNestedBlocks(String statement, int expected) throws Exception {
    ev.exec(
        "def foo2():",
        "   s = 0",
        "   for i in range(1, 41):",
        "       if i % 2 == 0:",
        "           if i % 3 == 0:",
        "               if i % 5 == 0:",
        "                   " + statement + "",
        "       s = s + 1",
        "   return s",
        "y = foo2()");
    assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(expected));
  }

  @Test
  public void testNestedForLoopsMultipleBreaks() throws Exception {
    nestedLoopsTest("break", 2, 6, 6);
  }

  @Test
  public void testNestedForLoopsMultipleContinues() throws Exception {
    nestedLoopsTest("continue", 4, 20, 20);
  }

  @SuppressWarnings("unchecked")
  private void nestedLoopsTest(
      String statement, int outerExpected, int firstExpected, int secondExpected) throws Exception {
    ev.exec(
        "def foo():",
        "   outer = 0",
        "   first = 0",
        "   second = 0",
        "   for i in range(0, 5):",
        "       for j in range(0, 5):",
        "           if j == 2:",
        "               " + statement + "",
        "           first = first + 1",
        "       for k in range(0, 5):",
        "           if k == 2:",
        "               " + statement + "",
        "           second = second + 1",
        "       if i == 2:",
        "           " + statement + "",
        "       outer = outer + 1",
        "   return [outer, first, second]",
        "x = foo()");
    assertThat((Iterable<Object>) ev.lookup("x"))
        .containsExactly(
            StarlarkInt.of(outerExpected),
            StarlarkInt.of(firstExpected),
            StarlarkInt.of(secondExpected))
        .inOrder();
  }

  @Test
  public void testForLoopBreakError() throws Exception {
    flowStatementInsideFunction("break");
    flowStatementAfterLoop("break");
  }

  @Test
  public void testForLoopContinueError() throws Exception {
    flowStatementInsideFunction("continue");
    flowStatementAfterLoop("continue");
  }

  // TODO(adonovan): move this and all tests that use it to ResolverTest.
  private void assertResolutionError(String expectedError, final String... lines) throws Exception {
    SyntaxError.Exception error = assertThrows(SyntaxError.Exception.class, () -> ev.exec(lines));
    assertThat(error).hasMessageThat().contains(expectedError);
  }

  private void flowStatementInsideFunction(String statement) throws Exception {
    assertResolutionError(
        statement + " statement must be inside a for loop",
        //
        "def foo():",
        "  " + statement,
        "x = foo()");
  }

  private void flowStatementAfterLoop(String statement) throws Exception {
    assertResolutionError(
        statement + " statement must be inside a for loop",
        //
        "def foo2():",
        "   for i in range(0, 3):",
        "      pass",
        "   " + statement,
        "y = foo2()");
  }

  @Test
  public void testNoneAssignment() throws Exception {
    ev.new Scenario()
        .setUp("def foo(x=None):", "  x = 1", "  x = None", "  return 2", "s = foo()")
        .testLookup("s", StarlarkInt.of(2));
  }

  @Test
  public void testReassignment() throws Exception {
    ev.exec(
        "def foo(x=None):", //
        "  x = 1",
        "  x = [1, 2]",
        "  x = 'str'",
        "  return x",
        "s = foo()");
    assertThat(ev.lookup("s")).isEqualTo("str");
  }

  @Test
  public void testJavaCalls() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.is_empty('a')")
        .testLookup("b", Boolean.FALSE);
  }

  @Test
  public void testJavaCallsOnSubClass() throws Exception {
    ev.new Scenario()
        .update("mock", new MockSubClass())
        .setUp("b = mock.is_empty('a')")
        .testLookup("b", Boolean.FALSE);
  }

  @Test
  public void testJavaCallsOnInterface() throws Exception {
    ev.new Scenario()
        .update("mock", new MockSubClass())
        .setUp("b = mock.is_empty_interface('a')")
        .testLookup("b", Boolean.FALSE);
  }

  @Test
  public void testJavaCallsNotStarlarkMethod() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfExactError("'Mock' value has no field or method 'value'", "mock.value()");
  }

  @Test
  public void testNoOperatorIndex() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfExactError("type 'Mock' has no operator [](int)", "mock[2]");
  }

  @Test
  public void testJavaCallsNoMethod() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfExactError("'Mock' value has no field or method 'bad'", "mock.bad()");
  }

  @Test
  public void testJavaCallsNoMethodErrorMsg() throws Exception {
    ev.new Scenario()
        .testIfExactError("'int' value has no field or method 'bad'", "s = (3).bad('a', 'b', 'c')");
  }

  @Test
  public void testJavaCallWithKwargs() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfExactError(
            "'Mock' value has no field or method 'isEmpty' (did you mean 'is_empty'?)",
            "mock.isEmpty(str='abc')");
  }

  @Test
  public void testStringListDictValues() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp(
            "def func(mock):",
            "  for i, v in mock.string_list_dict().items():",
            "    modified_list = v + ['extra_string']",
            "  return modified_list",
            "m = func(mock)")
        .testLookup("m", StarlarkList.of(null, "b", "c", "extra_string"));
  }

  @Test
  public void testProxyMethodsObject() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("m = mock.proxy_methods_object()", "b = m.with_params(1, True, named=True)")
        .testLookup("b", "with_params(1, true, false, true, false, a)");
  }

  /**
   * This test verifies an error is raised when a method parameter is set both positionally and
   * by name.
   */
  @Test
  public void testArgSpecifiedBothByNameAndPosition() throws Exception {
    // in with_params, 'posOrNamed' is positional parameter index 2. So by specifying both
    // posOrNamed by name and three positional parameters, there is a conflict.
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfErrorContains(
            "with_params() got multiple values for argument 'posOrNamed'",
            "mock.with_params(1, True, True, posOrNamed=True, named=True)");
  }

  @Test
  public void testTooManyPositionalArgs() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfErrorContains(
            "with_params() accepts no more than 3 positional arguments but got 4",
            "mock.with_params(1, True, True, 'toomany', named=True)");

    ev.new Scenario()
        .update("mock", new Mock())
        .testIfErrorContains(
            "with_params() accepts no more than 3 positional arguments but got 5",
            "mock.with_params(1, True, True, 'toomany', 'alsotoomany', named=True)");

    ev.new Scenario()
        .update("mock", new Mock())
        .testIfErrorContains(
            "is_empty() accepts no more than 1 positional argument but got 2",
            "mock.is_empty('a', 'b')");
  }

  @Test
  public void testJavaCallWithPositionalAndKwargs() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_params(1, True, named=True)")
        .testLookup("b", "with_params(1, true, false, true, false, a)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_params(1, True, named=True, multi=1)")
        .testLookup("b", "with_params(1, true, false, true, false, a, 1)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_params(1, True, named=True, multi='abc')")
        .testLookup("b", "with_params(1, true, false, true, false, a, abc)");

    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_params(1, True, named=True, multi=[1,2,3])")
        .testLookup("b", "with_params(1, true, false, true, false, a, [1, 2, 3])");

    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("")
        .testIfExactError(
            "with_params() missing 1 required named argument: named", "mock.with_params(1, True)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("")
        .testIfExactError(
            "with_params() missing 1 required named argument: named",
            "mock.with_params(1, True, True)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_params(1, True, True, named=True)")
        .testLookup("b", "with_params(1, true, true, true, false, a)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_params(1, True, named=True, posOrNamed=True)")
        .testLookup("b", "with_params(1, true, true, true, false, a)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_params(1, True, named=True, posOrNamed=True, optionalNamed=True)")
        .testLookup("b", "with_params(1, true, true, true, true, a)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("")
        .testIfExactError(
            "with_params() got unexpected keyword argument 'posornamed' (did you mean"
                + " 'posOrNamed'?)",
            "mock.with_params(1, True, named=True, posornamed=True)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("")
        .testIfExactError(
            "with_params() got unexpected keyword argument 'n'",
            "mock.with_params(1, True, named=True, posOrNamed=True, n=2)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("")
        .testExpression(
            "mock.with_params(1, True, True, named=True, optionalNamed=False, acceptsAny=None)",
            "with_params(1, true, true, true, false, None)");
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("")
        .testExpression(
            "mock.with_params(1, True, True, named=True, optionalNamed=False, acceptsAny=123)",
            "with_params(1, true, true, true, false, 123)");

    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("")
        .testIfExactError(
            "in call to with_params(), parameter 'multi' got value of type 'bool', "
                + "want 'string, int, sequence, or NoneType'",
            "mock.with_params(1, True, named=True, multi=False)");

    // We do not enforce list item parameter type constraints.
    // Test for this behavior.
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_params(1, True, named=True, multi=['a', 'b'])")
        .testLookup("b", "with_params(1, true, false, true, false, a, [\"a\", \"b\"])");
  }

  @Test
  public void testNoJavaCallsWithoutStarlark() throws Exception {
    ev.new Scenario()
        .testIfExactError("'int' value has no field or method 'to_string'", "s = (3).to_string()");
  }

  @Test
  public void testStructAccess() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("v = mock.struct_field")
        .testLookup("v", "a");
  }

  @Test
  public void testStructAccessAsFuncallNonCallable() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfExactError("'string' object is not callable", "v = mock.struct_field()");
  }

  @Test
  public void testSelfCall() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("v = mock('bestmock')")
        .testLookup("v", "I'm a mock named bestmock");

    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("mockfunction = mock", "v = mockfunction('bestmock')")
        .testLookup("v", "I'm a mock named bestmock");

    ev.new Scenario()
        .update("mock", new Mock())
        .testIfErrorContains(
            "in call to MockFn(), parameter 'pos' got value of type 'int', want 'string'",
            "v = mock(1)");
  }

  @Test
  public void testStructAccessAsFuncall() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("v = mock.struct_field_callable()")
        .testLookup("v", "foobar");
  }

  @Test
  public void testCallingInterruptedStructField() throws Exception {
    ev.update("mock", new Mock());
    assertThrows(InterruptedException.class, () -> ev.eval("mock.interrupted_struct_field()"));
  }

  @Test
  public void testCallingInterruptedFunction() throws Exception {
    ev.update("interrupted_function", getattr(this, "interrupted_function"));
    assertThrows(InterruptedException.class, () -> ev.eval("interrupted_function()"));
  }

  @Test
  public void testJavaFunctionWithExtraInterpreterParams() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("v = mock.with_extra()")
        .testLookup("v", "with_extra(1)");
  }

  @Test
  public void testStructFieldWithExtraInterpreterParams() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("v = mock.struct_field_with_extra")
        .testLookup("v", "struct_field_with_extra(true)");
  }

  @Test
  public void testJavaFunctionWithParamsAndExtraInterpreterParams() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_params_and_extra(1, True, named=True)")
        .testLookup("b", "with_params_and_extra(1, true, false, true, false, a, 1)");
  }

  @Test
  public void testJavaFunctionWithExtraArgsAndThread() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_args_and_thread(1, True, 'extraArg1', 'extraArg2', named=True)")
        .testLookup("b", "with_args_and_thread(1, true, true, args(extraArg1, extraArg2))");

    // Use an args list.
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp(
            "myargs = ['extraArg2']",
            "b = mock.with_args_and_thread(1, True, 'extraArg1', named=True, *myargs)")
        .testLookup("b", "with_args_and_thread(1, true, true, args(extraArg1, extraArg2))");
  }

  @Test
  public void testJavaFunctionWithExtraKwargs() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_kwargs(True, extraKey1=True, named=True, extraKey2='x')")
        .testLookup("b", "with_kwargs(true, true, kwargs(extraKey1=true, extraKey2=x))");

    // Use a kwargs dict.
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp(
            "mykwargs = {'extraKey2':'x', 'named':True}",
            "b = mock.with_kwargs(True, extraKey1=True, **mykwargs)")
        .testLookup("b", "with_kwargs(true, true, kwargs(extraKey1=true, extraKey2=x))");
  }

  @Test
  public void testJavaFunctionWithArgsAndKwargs() throws Exception {
    // Foo is used positionally
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_args_and_kwargs('foo', 'bar', 'baz', extraKey1=True, extraKey2='x')")
        .testLookup(
            "b", "with_args_and_kwargs(foo, args(bar, baz), kwargs(extraKey1=true, extraKey2=x))");

    // Use an args list and a kwargs dict
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp(
            "mykwargs = {'extraKey1':True}",
            "myargs = ['baz']",
            "b = mock.with_args_and_kwargs('foo', 'bar', extraKey2='x', *myargs, **mykwargs)")
        .testLookup(
            "b", "with_args_and_kwargs(foo, args(bar, baz), kwargs(extraKey2=x, extraKey1=true))");

    // Foo is used by name
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_args_and_kwargs(foo='foo', extraKey1=True)")
        .testLookup("b", "with_args_and_kwargs(foo, args(), kwargs(extraKey1=true))");

    // Empty args and kwargs.
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("b = mock.with_args_and_kwargs('foo')")
        .testLookup("b", "with_args_and_kwargs(foo, args(), kwargs())");
  }

  @Test
  public void testProxyMethodsObjectWithArgsAndKwargs() throws Exception {
    // Foo is used positionally
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp(
            "m = mock.proxy_methods_object()",
            "b = m.with_args_and_kwargs('foo', 'bar', 'baz', extraKey1=True, extraKey2='x')")
        .testLookup(
            "b", "with_args_and_kwargs(foo, args(bar, baz), kwargs(extraKey1=true, extraKey2=x))");

    // Use an args list and a kwargs dict
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp(
            "mykwargs = {'extraKey1':True}",
            "myargs = ['baz']",
            "m = mock.proxy_methods_object()",
            "b = m.with_args_and_kwargs('foo', 'bar', extraKey2='x', *myargs, **mykwargs)")
        .testLookup(
            "b", "with_args_and_kwargs(foo, args(bar, baz), kwargs(extraKey2=x, extraKey1=true))");

    // Foo is used by name
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp(
            "m = mock.proxy_methods_object()",
            "b = m.with_args_and_kwargs(foo='foo', extraKey1=True)")
        .testLookup("b", "with_args_and_kwargs(foo, args(), kwargs(extraKey1=true))");

    // Empty args and kwargs.
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("m = mock.proxy_methods_object()", "b = m.with_args_and_kwargs('foo')")
        .testLookup("b", "with_args_and_kwargs(foo, args(), kwargs())");
  }

  @Test
  public void testStructAccessOfMethod() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testExpression("type(mock.function)", "builtin_function_or_method");
    ev.new Scenario().update("mock", new Mock()).testExpression("mock.function()", "a");
  }

  @Test
  public void testStructAccessTypo() throws Exception {
    ev.new Scenario()
        .update("mock", new SimpleStruct(ImmutableMap.of("field", 123)))
        .testIfExactError(
            "'SimpleStruct' value has no field or method 'fild' (did you mean 'field'?)",
            "mock.fild");
  }

  @Test
  public void testStructAccessType_nonClassObject() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testIfExactError(
            "'Mock' value has no field or method 'sturct_field' (did you mean 'struct_field'?)",
            "v = mock.sturct_field");
  }

  @Test
  public void testJavaFunctionReturnsIllegalValue() throws Exception {
    ev.update("mock", new Mock());
    Starlark.UncheckedEvalException e =
        assertThrows(Starlark.UncheckedEvalException.class, () -> ev.eval("mock.return_bad()"));
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains(
            "invalid Starlark value: class net.starlark.java.eval.StarlarkEvaluationTest$Bad");
  }

  @Test
  public void testJavaFunctionReturnsNullFails() throws Exception {
    ev.update("mock", new Mock());
    RuntimeException e =
        assertThrows(RuntimeException.class, () -> ev.eval("mock.nullfunc_failing('abc', 1)"));
    assertThat(e).hasCauseThat().hasMessageThat().contains("method invocation returned null");
  }

  @Test
  public void testJavaFunctionOverflowsStack() throws Exception {
    ev.update("stackoverflow", getattr(this, "stackoverflow"));
    Starlark.UncheckedEvalError e =
        assertThrows(Starlark.UncheckedEvalError.class, () -> ev.eval("stackoverflow()"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("StackOverflowError thrown during Starlark evaluation");
    assertThat(stream(e.getStackTrace()).map(StackTraceElement::getMethodName))
        .containsExactly("stackoverflow", "<expr>")
        .inOrder();
    // The underlying exception is preserved as cause.
    assertThat(e).hasCauseThat().isInstanceOf(StackOverflowError.class);
  }

  @Test
  public void javaFunctionThrowsOom() throws Exception {
    ev.update("throwoom", getattr(this, "throwoom"));
    Starlark.UncheckedEvalError e =
        assertThrows(Starlark.UncheckedEvalError.class, () -> ev.eval("throwoom()"));
    assertThat(e).hasMessageThat().isEqualTo("OutOfMemoryError thrown during Starlark evaluation");
    assertThat(stream(e.getStackTrace()).map(StackTraceElement::getMethodName))
        .containsExactly("throwoom", "<expr>")
        .inOrder();
    // The underlying exception is preserved as cause.
    assertThat(e).hasCauseThat().isInstanceOf(OutOfMemoryError.class);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("Java heap space");
  }

  @Test
  public void testJavaFunctionThrowsNpe() throws Exception {
    ev.update("thrownpe", getattr(this, "thrownpe"));
    Starlark.UncheckedEvalException e =
        assertThrows(Starlark.UncheckedEvalException.class, () -> ev.eval("thrownpe()"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("NullPointerException thrown during Starlark evaluation");
    assertThat(stream(e.getStackTrace()).map(StackTraceElement::getMethodName))
        .containsExactly("thrownpe", "<expr>")
        .inOrder();
    // The underlying exception is preserved as cause.
    assertThat(e).hasCauseThat().isInstanceOf(NullPointerException.class);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("oops");
  }

  @Test
  public void uncheckedExceptionContextAppendedToMessage() throws Exception {
    ev.update("thrownpe", getattr(this, "thrownpe"))
        .getStarlarkThread()
        .setUncheckedExceptionContext(() -> "some extra context");
    Starlark.UncheckedEvalException e =
        assertThrows(Starlark.UncheckedEvalException.class, () -> ev.eval("thrownpe()"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("NullPointerException thrown during Starlark evaluation (some extra context)");
  }

  @Test
  public void testClassObjectAccess() throws Exception {
    ev.new Scenario()
        .update("mock", new SimpleStruct(ImmutableMap.of("field", "a")))
        .setUp("v = mock.field")
        .testLookup("v", "a");
  }

  @Test
  public void testFieldReturnsNonStarlarkValue() throws Exception {
    ev.update("s", new SimpleStruct(ImmutableMap.of("bad", new StringBuilder())));
    RuntimeException e = assertThrows(RuntimeException.class, () -> ev.eval("s.bad"));
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("invalid Starlark value: class java.lang.StringBuilder");
  }

  @Test
  public void testJavaFunctionReturnsNone() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("v = mock.nullfunc_working()")
        .testLookup("v", Starlark.NONE);
  }

  @Test
  public void testVoidJavaFunctionReturnsNone() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp("v = mock.voidfunc()")
        .testLookup("v", Starlark.NONE);
  }

  @Test
  public void testAugmentedAssignment() throws Exception {
    ev.new Scenario()
        .setUp("def f1(x):", "  x += 1", "  return x", "", "foo = f1(41)")
        .testLookup("foo", StarlarkInt.of(42));
  }

  @Test
  public void testAugmentedAssignmentHasNoSideEffects() throws Exception {
    // Check object position.
    ev.new Scenario()
        .setUp(
            "counter = [0]",
            "value = [1, 2]",
            "",
            "def f():",
            "  counter[0] = counter[0] + 1",
            "  return value",
            "",
            "f()[1] += 1") // `f()` should be called only once here
        .testLookup("counter", StarlarkList.of(null, StarlarkInt.of(1)));

    // Check key position.
    ev.new Scenario()
        .setUp(
            "counter = [0]",
            "value = [1, 2]",
            "",
            "def f():",
            "  counter[0] = counter[0] + 1",
            "  return 1",
            "",
            "value[f()] += 1") // `f()` should be called only once here
        .testLookup("counter", StarlarkList.of(null, StarlarkInt.of(1)));
  }

  @Test
  public void testInvalidAugmentedAssignment_listExpression() throws Exception {
    assertResolutionError(
        "cannot perform augmented assignment on a list or tuple expression",
        //
        "def f(a, b):",
        "  [a, b] += []",
        "f(1, 2)");
  }

  @Test
  public void testInvalidAugmentedAssignment_notAnLValue() throws Exception {
    assertResolutionError(
        "cannot assign to 'x + 1'",
        //
        "x + 1 += 2");
  }

  @Test
  public void testAssignmentEvaluationOrder() throws Exception {
    ev.new Scenario()
        .setUp(
            "ordinary = []",
            "augmented = []",
            "value = [1, 2]",
            "",
            "def f(record):",
            "  record.append('f')",
            "  return value",
            "",
            "def g(record):",
            "  record.append('g')",
            "  return value",
            "",
            "f(ordinary)[0] = g(ordinary)[1]",
            "f(augmented)[0] += g(augmented)[1]")
        .testLookup("ordinary", StarlarkList.of(null, "g", "f")) // This order is consistent
        .testLookup("augmented", StarlarkList.of(null, "f", "g")); // with Python
  }

  @Test
  public void testDictComprehensions_iterationOrder() throws Exception {
    ev.new Scenario()
        .setUp(
            "def foo():",
            "  d = {x : x for x in ['c', 'a', 'b']}",
            "  s = ''",
            "  for a in d:",
            "    s += a",
            "  return s",
            "s = foo()")
        .testLookup("s", "cab");
  }

  @Test
  public void testDotExpressionOnNonStructObject() throws Exception {
    ev.new Scenario()
        .testIfExactError(
            "'string' value has no field or method 'field' (did you mean 'find'?)",
            "x = 'a'.field");
  }

  @Test
  public void testPlusEqualsOnListMutating() throws Exception {
    ev.new Scenario()
        .setUp(
            "def func():",
            "  l1 = [1, 2]",
            "  l2 = l1",
            "  l2 += [3, 4]",
            "  return l1, l2",
            "lists = str(func())")
        .testLookup("lists", "([1, 2, 3, 4], [1, 2, 3, 4])");

    // The same but with += after an IndexExpression
    ev.new Scenario()
        .setUp(
            "def func():",
            "  l = [1, 2]",
            "  d = {0: l}",
            "  d[0] += [3, 4]",
            "  return l, d[0]",
            "lists = str(func())")
        .testLookup("lists", "([1, 2, 3, 4], [1, 2, 3, 4])");
  }

  @Test
  public void testPlusEqualsOnTuple() throws Exception {
    ev.new Scenario()
        .setUp(
            "def func():",
            "  t1 = (1, 2)",
            "  t2 = t1",
            "  t2 += (3, 4)",
            "  return t1, t2",
            "tuples = func()")
        .testLookup(
            "tuples",
            Tuple.of(
                Tuple.of(StarlarkInt.of(1), StarlarkInt.of(2)),
                Tuple.of(
                    StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4))));
  }

  @Test
  public void testPlusOnDictDeprecated() throws Exception {
    ev.new Scenario()
        .testIfErrorContains("unsupported binary operation: dict + dict", "{1: 2} + {3: 4}");
    ev.new Scenario()
        .testIfErrorContains(
            "unsupported binary operation: dict + dict",
            "def func():",
            "  d = {1: 2}",
            "  d += {3: 4}",
            "func()");
  }

  @Test
  public void testDictAssignmentAsLValue() throws Exception {
    ev.new Scenario()
        .setUp("def func():", "  d = {'a' : 1}", "  d['b'] = 2", "  return d", "d = func()")
        .testLookup("d", ImmutableMap.of("a", StarlarkInt.of(1), "b", StarlarkInt.of(2)));
  }

  @Test
  public void testNestedDictAssignmentAsLValue() throws Exception {
    ev.new Scenario()
        .setUp(
            "def func():",
            "  d = {'a' : 1}",
            "  e = {'d': d}",
            "  e['d']['b'] = 2",
            "  return e",
            "e = func()")
        .testLookup(
            "e",
            ImmutableMap.of("d", ImmutableMap.of("a", StarlarkInt.of(1), "b", StarlarkInt.of(2))));
  }

  @Test
  public void testListAssignmentAsLValue() throws Exception {
    ev.new Scenario()
        .setUp(
            "def func():",
            "  a = [1, 2]",
            "  a[1] = 3",
            "  a[-2] = 4",
            "  return a",
            "a = str(func())")
        .testLookup("a", "[4, 3]");
  }

  @Test
  public void testNestedListAssignmentAsLValue() throws Exception {
    ev.new Scenario()
        .setUp(
            "def func():",
            "  d = [1, 2]",
            "  e = [3, d]",
            "  e[1][1] = 4",
            "  return e",
            "e = str(func())")
        .testLookup("e", "[3, [1, 4]]");
  }

  @Test
  public void testDictTupleAssignmentAsLValue() throws Exception {
    ev.new Scenario()
        .setUp(
            "def func():", "  d = {'a' : 1}", "  d['b'], d['c'] = 2, 3", "  return d", "d = func()")
        .testLookup(
            "d",
            ImmutableMap.of(
                "a", StarlarkInt.of(1), "b", StarlarkInt.of(2), "c", StarlarkInt.of(3)));
  }

  @Test
  public void testDictItemPlusEqual() throws Exception {
    ev.new Scenario()
        .setUp("def func():", "  d = {'a' : 2}", "  d['a'] += 3", "  return d", "d = func()")
        .testLookup("d", ImmutableMap.of("a", StarlarkInt.of(5)));
  }

  @Test
  public void testDictAssignmentAsLValueSideEffects() throws Exception {
    ev.new Scenario()
        .setUp("def func(d):", "  d['b'] = 2", "d = {'a' : 1}", "func(d)")
        .testLookup(
            "d",
            Dict.builder()
                .put("a", StarlarkInt.of(1))
                .put("b", StarlarkInt.of(2))
                .buildImmutable());
  }

  @Test
  public void testAssignmentToListInDictSideEffect() throws Exception {
    ev.new Scenario()
        .setUp("l = [1, 2]", "d = {0: l}", "d[0].append(3)")
        .testLookup(
            "l", StarlarkList.of(null, StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3)));
  }

  @Test
  public void testUserFunctionKeywordArgs() throws Exception {
    ev.new Scenario()
        .setUp("def foo(a, b, c):", "  return a + b + c", "s = foo(1, c=2, b=3)")
        .testLookup("s", StarlarkInt.of(6));
  }

  @Test
  public void testFunctionCallOrdering() throws Exception {
    ev.new Scenario()
        .setUp("def func(): return foo() * 2", "def foo(): return 2", "x = func()")
        .testLookup("x", StarlarkInt.of(4));
  }

  @Test
  public void testFunctionCallBadOrdering() throws Exception {
    ev.new Scenario()
        .testIfErrorContains(
            "global variable 'foo' is referenced before assignment.",
            "def func(): return foo() * 2",
            "x = func()",
            "def foo(): return 2");
  }

  @Test
  public void testLocalVariableDefinedBelow() throws Exception {
    ev.new Scenario()
        .setUp(
            "def beforeEven(li):", // returns the value before the first even number
            "    for i in li:",
            "        if i % 2 == 0:",
            "            return a",
            "        else:",
            "            a = i",
            "res = beforeEven([1, 3, 4, 5])")
        .testLookup("res", StarlarkInt.of(3));
  }

  @Test
  public void testShadowisNotInitialized() throws Exception {
    ev.new Scenario()
        .testIfErrorContains(
            /* error message */ "local variable 'gl' is referenced before assignment",
            "gl = 5",
            "def foo():",
            "    if False: gl = 2",
            "    return gl",
            "res = foo()");
  }

  @Test
  public void testShadowBuiltin() throws Exception {
    ev.new Scenario()
        .testIfErrorContains(
            "global variable 'len' is referenced before assignment",
            "x = len('abc')",
            "len = 2",
            "y = x + len");
  }

  @Test
  public void testRecursionDisallowedByDefault() throws Exception {
    ev.new Scenario()
        .testIfErrorContains(
            "function 'f' called recursively",
            "def main():",
            "  f(5)",
            "def f(n):",
            "  if n > 0: g(n - 1)",
            "def g(n):",
            "  if n > 0: f(n - 1)",
            "main()");
  }

  @Test
  public void testRecursionAllowedWithOption() throws Exception {
    ParserInput input =
        ParserInput.fromLines(
            "def fac(n): return 1 if n < 2 else n * fac(n - 1)", //
            "x = fac(5)");
    Module module = Module.create();
    try (Mutability mu = Mutability.create("test")) {
      StarlarkSemantics semantics =
          StarlarkSemantics.builder().setBool(StarlarkSemantics.ALLOW_RECURSION, true).build();
      StarlarkThread thread = StarlarkThread.createTransient(mu, semantics);
      Starlark.execFile(input, FileOptions.DEFAULT, module, thread);
    }
    assertThat(module.getGlobal("x")).isEqualTo(StarlarkInt.of(120));
  }

  @Test
  // TODO(adonovan): move to ResolverTest.
  public void testTypo() throws Exception {
    assertResolutionError(
        "name 'my_variable' is not defined (did you mean 'myVariable'?)",
        //
        "myVariable = 2",
        "x = my_variable + 1");
  }

  @Test
  public void testNoneTrueFalseInStarlark() throws Exception {
    ev.new Scenario()
        .setUp("a = None", "b = True", "c = False")
        .testLookup("a", Starlark.NONE)
        .testLookup("b", Boolean.TRUE)
        .testLookup("c", Boolean.FALSE);
  }

  @Test
  public void testHasattrMethods() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp(
            "a = hasattr(mock, 'struct_field')",
            "b = hasattr(mock, 'function')",
            "c = hasattr(mock, 'is_empty')",
            "d = hasattr('str', 'replace')",
            "e = hasattr(mock, 'other')\n")
        .testLookup("a", Boolean.TRUE)
        .testLookup("b", Boolean.TRUE)
        .testLookup("c", Boolean.TRUE)
        .testLookup("d", Boolean.TRUE)
        .testLookup("e", Boolean.FALSE);
  }

  @Test
  public void testGetattrMethods() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .setUp(
            "a = str(getattr(mock, 'struct_field', 'no'))",
            "b = str(getattr(mock, 'function', 'no'))",
            "c = str(getattr(mock, 'is_empty', 'no'))",
            "d = str(getattr('str', 'replace', 'no'))",
            "e = str(getattr(mock, 'other', 'no'))\n")
        .testLookup("a", "a")
        .testLookup("b", "<built-in method function of Mock value>")
        .testLookup("c", "<built-in method is_empty of Mock value>")
        .testLookup("d", "<built-in method replace of string value>")
        .testLookup("e", "no");
  }

  @Test
  public void testListAnTupleConcatenationDoesNotWorkInStarlark() throws Exception {
    ev.new Scenario()
        .testIfExactError("unsupported binary operation: list + tuple", "[1, 2] + (3, 4)");
  }

  @Test
  public void testCannotCreateMixedListInStarlark() throws Exception {
    ev.new Scenario()
        .testExactOrder("['a', 'b', 1, 2]", "a", "b", StarlarkInt.of(1), StarlarkInt.of(2));
  }

  @Test
  public void testCannotConcatListInStarlarkWithDifferentGenericTypes() throws Exception {
    ev.new Scenario()
        .testExactOrder("[1, 2] + ['a', 'b']", StarlarkInt.of(1), StarlarkInt.of(2), "a", "b");
  }

  @Test
  public void testConcatEmptyListWithNonEmptyWorks() throws Exception {
    ev.new Scenario().testExactOrder("[] + ['a', 'b']", "a", "b");
  }

  @Test
  public void testFormatStringWithTuple() throws Exception {
    ev.new Scenario().setUp("v = '%s%s' % ('a', 1)").testLookup("v", "a1");
  }

  @Test
  public void testSingletonTuple() throws Exception {
    ev.new Scenario().testExactOrder("(1,)", StarlarkInt.of(1));
  }

  @Test
  public void testDirFindsClassObjectFields() throws Exception {
    ev.new Scenario()
        .update(
            "s", new SimpleStruct(ImmutableMap.of("a", StarlarkInt.of(1), "b", StarlarkInt.of(2))))
        .testExactOrder("dir(s)", "a", "b");
  }

  @Test
  public void testDirFindsJavaObjectStructFieldsAndMethods() throws Exception {
    ev.new Scenario()
        .update("mock", new Mock())
        .testExactOrder(
            "dir(mock)",
            "function",
            "interrupted_struct_field",
            "is_empty",
            "nullfunc_failing",
            "nullfunc_working",
            "proxy_methods_object",
            "return_bad",
            "string",
            "string_list",
            "string_list_dict",
            "struct_field",
            "struct_field_callable",
            "struct_field_with_extra",
            "value_of",
            "voidfunc",
            "with_args_and_kwargs",
            "with_args_and_thread",
            "with_extra",
            "with_kwargs",
            "with_params",
            "with_params_and_extra");
  }

  @Test
  public void testPrint() throws Exception {
    ParserInput input =
        ParserInput.fromLines(
            "print('hello')", //
            "print('a', 'b')",
            "print('a', 'b', sep='x')");
    List<String> prints = new ArrayList<>();
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      thread.setPrintHandler((unused, msg) -> prints.add(msg));
      Starlark.execFile(input, FileOptions.DEFAULT, Module.create(), thread);
    }
    assertThat(prints).containsExactly("hello", "a b", "axb").inOrder();
  }

  @Test
  public void testPrintWithTrace() throws Exception {
    ParserInput input = ParserInput.fromLines(
        "def foo(value):",
        "    if value:",
        "        print(value, with_trace = True)",
        "def bar(value):",
        "    foo(value)",
        "def baz(value):",
        "    bar(value)",
        "baz(42)"
    );
    List<String> prints = new ArrayList<>();
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = new StarlarkThread(mu, StarlarkSemantics.DEFAULT);
      thread.setPrintHandler((unused, msg) -> prints.add(msg));
      Starlark.execFile(input, FileOptions.DEFAULT, Module.create(), thread);
    }
    assertThat(prints).hasSize(1);
    assertThat(prints.get(0).split("\n")).asList().containsExactly(
        "Traceback (most recent call last):",
        "\tFile \"\", line 8, column 4, in <toplevel>",
        "\tFile \"\", line 7, column 8, in baz",
        "\tFile \"\", line 5, column 8, in bar",
        "\tFile \"\", line 3, column 14, in foo",
        "print: 42"
    ).inOrder();
  }

  @Test
  public void testPrintBadKwargs() throws Exception {
    ev.new Scenario()
        .testIfErrorContains(
            "print() got unexpected keyword argument 'end'", "print(end='x', other='y')");
  }

  @Test
  public void testConditionalExpressionAtToplevel() throws Exception {
    ev.new Scenario().setUp("x = 1 if 2 else 3").testLookup("x", StarlarkInt.of(1));
  }

  @Test
  public void testConditionalExpressionInFunction() throws Exception {
    ev.new Scenario()
        .setUp("def foo(a, b, c): return a+b if c else a-b\n")
        .testExpression("foo(23, 5, 0)", StarlarkInt.of(18));
  }

  // SimpleStructWithMethods augments SimpleStruct's fields with annotated Java methods.
  private static final class SimpleStructWithMethods extends SimpleStruct {

    // A function that returns "fromValues".
    private static final Object returnFromValues =
        new StarlarkCallable() {
          @Override
          public String getName() {
            return "returnFromValues";
          }

          @Override
          public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs) {
            return "fromValues";
          }
        };

    SimpleStructWithMethods() {
      super(
          ImmutableMap.of(
              "values_only_field",
              "fromValues",
              "values_only_method",
              returnFromValues,
              "collision_field",
              "fromValues",
              "collision_method",
              returnFromValues));
    }

    @StarlarkMethod(name = "callable_only_field", documented = false, structField = true)
    public String getCallableOnlyField() {
      return "fromStarlarkMethod";
    }

    @StarlarkMethod(name = "callable_only_method", documented = false, structField = false)
    public String getCallableOnlyMethod() {
      return "fromStarlarkMethod";
    }

    @StarlarkMethod(name = "collision_field", documented = false, structField = true)
    public String getCollisionField() {
      return "fromStarlarkMethod";
    }

    @StarlarkMethod(name = "collision_method", documented = false, structField = false)
    public String getCollisionMethod() {
      return "fromStarlarkMethod";
    }
  }

  @Test
  public void testStructFieldDefinedOnlyInValues() throws Exception {
    ev.new Scenario()
        .update("val", new SimpleStructWithMethods())
        .setUp("v = val.values_only_field")
        .testLookup("v", "fromValues");
  }

  @Test
  public void testStructMethodDefinedOnlyInValues() throws Exception {
    ev.new Scenario()
        .update("val", new SimpleStructWithMethods())
        .setUp("v = val.values_only_method()")
        .testLookup("v", "fromValues");
  }

  @Test
  public void testStructFieldDefinedOnlyInStarlarkMethod() throws Exception {
    ev.new Scenario()
        .update("val", new SimpleStructWithMethods())
        .setUp("v = val.callable_only_field")
        .testLookup("v", "fromStarlarkMethod");
  }

  @Test
  public void testStructMethodDefinedOnlyInStarlarkMethod() throws Exception {
    ev.new Scenario()
        .update("val", new SimpleStructWithMethods())
        .setUp("v = val.callable_only_method()")
        .testLookup("v", "fromStarlarkMethod");
  }

  @Test
  public void testStructMethodDefinedInValuesAndStarlarkMethod() throws Exception {
    // This test exercises the resolution of ambiguity between @StarlarkMethod-annotated
    // fields and those reported by Structure.getValue.
    ev.new Scenario()
        .update("val", new SimpleStructWithMethods())
        .setUp("v = val.collision_method()")
        .testLookup("v", "fromStarlarkMethod");
  }

  @Test
  public void testAttrNotDefined() throws Exception {
    ev.new Scenario()
        .update("s", new SimpleStructWithMethods())
        // dir shows all fields and methods
        .testEval(
            "dir(s)",
            "['callable_only_field', 'callable_only_method', 'collision_field',"
                + " 'collision_method', 'values_only_field', 'values_only_method']")
        // field-like non-existent access
        .testIfExactError(
            "'SimpleStructWithMethods' value has no field or method 'nonesuch'", "s.nonesuch")
        // method-like non-existent access (same result)
        .testIfExactError(
            "'SimpleStructWithMethods' value has no field or method 'nonesuch'", "s.nonesuch()")
        // spelling hint
        .testIfExactError(
            "'SimpleStructWithMethods' value has no field or method 'collision_metod' (did you"
                + " mean 'collision_method'?)",
            "s.collision_metod");
  }

  @Test
  public void testListComprehensionsShadowGlobalVariable() throws Exception {
    ev.exec(
        "a = 18", //
        "def foo():",
        "  b = [a for a in range(3)]",
        "  return a",
        "x = foo()");
    assertThat(ev.lookup("x")).isEqualTo(StarlarkInt.of(18));
  }

  @Test
  public void testComprehensionsAreLocal() throws Exception {
    // Regression test for https://github.com/bazelbuild/starlark/issues/92.
    ev.exec(
        "x = 1", // this global binding is not affected (even temporarily) by the comprehension
        "def f():",
        "  return x",
        "y = [f() for x in [2]][0]");
    assertThat(ev.lookup("y")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testFunctionEvaluatedBeforeArguments() throws Exception {
    // ''.nonesuch must be evaluated (and fail) before f().
    ev.new Scenario()
        .testIfErrorContains(
            "'string' value has no field or method 'nonesuch'",
            "def f(): x = 1//0",
            "''.nonesuch(f())");
  }

  @Test
  public void testAddMethodsRejectsFields() throws Exception {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Starlark.addMethods(ImmutableMap.builder(), new Mock()));
    assertThat(ex).hasMessageThat().contains("method struct_field has structField=true");
  }
}
