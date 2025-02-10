// Copyright 2018 The Bazel Authors. All Rights Reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import javax.annotation.Nullable;
import net.starlark.java.eval.Debug.ReadyToPause;
import net.starlark.java.eval.Debug.Stepping;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of debugging features of StarlarkThread. */
@RunWith(JUnit4.class)
public class StarlarkThreadDebuggingTest {

  // TODO(adonovan): rewrite these tests at a higher level.

  private static StarlarkThread newThread() {
    return StarlarkThread.createTransient(Mutability.create("test"), StarlarkSemantics.DEFAULT);
  }

  // Executes the definition of a trivial function f and returns the function value.
  private static StarlarkFunction defineFunc() throws Exception {
    return (StarlarkFunction)
        Starlark.execFile(
            ParserInput.fromLines("def f(): pass\nf"),
            FileOptions.DEFAULT,
            Module.create(),
            newThread());
  }

  @Test
  public void testListFramesEmptyStack() {
    StarlarkThread thread = newThread();
    assertThat(Debug.getCallStack(thread)).isEmpty();
    assertThat(thread.getCallStack()).isEmpty();
  }

  /**
   * A callable which captures the Starlark call stack at the time of the last call to it.
   *
   * <p>In Starlark, returns the first positional arg if supplied, or None otherwise.
   */
  private static final class StackTracer implements StarlarkCallable {
    private final String name;
    // Debug.Frame values are mutable (and are expected to mutate during the execution of a thread),
    // so we capture their formatted string form instead. (The string form also makes test failures
    // more informative.)
    @Nullable private ImmutableList<String> debugStack;
    @Nullable private ImmutableList<StarlarkThread.CallStackEntry> liteStack;

    private StackTracer(String name) {
      this.name = name;
    }

    @Nullable
    public ImmutableList<String> getDebugStack() {
      return debugStack;
    }

    @Nullable
    public String getCallerDebugFrame() {
      return debugStack != null ? debugStack.get(debugStack.size() - 2) : null;
    }

    @Nullable
    public ImmutableList<StarlarkThread.CallStackEntry> getLiteStack() {
      return liteStack;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs) {
      debugStack =
          Debug.getCallStack(thread).stream()
              .map(this::formatDebugFrame)
              .collect(toImmutableList());
      liteStack = thread.getCallStack();
      return Iterables.getFirst(args, Starlark.NONE);
    }

    private String formatDebugFrame(Debug.Frame fr) {
      return String.format(
          "%s @ %s local=%s", fr.getFunction().getName(), fr.getLocation(), fr.getLocals());
    }

    @Override
    public Location getLocation() {
      return Location.BUILTIN;
    }

    @Override
    public String toString() {
      return "<stack tracer>";
    }
  }

  @Test
  public void testListFramesFromBuiltin() throws Exception {
    // f is a built-in that captures the stack using the Debugger API.
    StackTracer f = new StackTracer("f");

    // Set up global environment.
    Module module =
        Module.withPredeclared(StarlarkSemantics.DEFAULT, ImmutableMap.of("a", 1, "b", 2, "f", f));

    // Execute a small file that calls f.
    ParserInput input =
        ParserInput.fromString(
            """
def g(a, y, z):  # shadows global a
    f()

g(4, 5, 6)
""",
            "main.star");
    Starlark.execFile(input, FileOptions.DEFAULT, module, newThread());

    assertThat(f.getDebugStack())
        .containsExactly(
            // location is paren of g(4, 5, 6) call:
            "<toplevel> @ main.star:4:2 local={}",
            // location is paren of "f()" call:
            "g @ main.star:2:6 local={a=4, y=5, z=6}",
            // location is "current PC" in f.
            "f @ <builtin> local={}")
        .inOrder();

    // Same, with "lite" stack API.
    assertThat(f.getLiteStack().toString()) // an ImmutableList<StarlarkThread.CallStackEntry>
        .isEqualTo("[<toplevel>@main.star:4:2, g@main.star:2:6, f@<builtin>]");

    // TODO(adonovan): more tests:
    // - a stack containing functions defined in different modules.
    // - changing environment at various program points within a function.
  }

  @Test
  public void comprehensionVariables() throws Exception {
    // Tracers for capturing the stack using the Debugger API.
    StackTracer f = new StackTracer("f");
    StackTracer g = new StackTracer("g");
    StackTracer h = new StackTracer("h");
    StackTracer i = new StackTracer("i");
    StackTracer j = new StackTracer("j");
    StackTracer k = new StackTracer("k");

    Module module =
        Module.withPredeclared(
            StarlarkSemantics.DEFAULT,
            ImmutableMap.of("f", f, "g", g, "h", h, "i", i, "j", j, "k", k));

    ParserInput input =
        ParserInput.fromString(
            """
def foo(x):
    x += [[j(x) for x in i(x)] + h(x) for x in f(x) if g(x)]
    return k(x)

foo([[1]])
""",
            "main.star");
    Starlark.execFile(input, FileOptions.DEFAULT, module, newThread());
    // f is in the outer comprehension's first for clause, and sees foo's local x
    assertThat(f.getCallerDebugFrame()).isEqualTo("foo @ main.star:2:49 local={x=[[1]]}");
    // g and h see the outer comprehension's x
    assertThat(g.getCallerDebugFrame()).isEqualTo("foo @ main.star:2:57 local={x=[1]}");
    assertThat(h.getCallerDebugFrame()).isEqualTo("foo @ main.star:2:35 local={x=[1]}");
    // i is in the inner comprehension's first for clause, and so sees the outer comprehension's x
    assertThat(i.getCallerDebugFrame()).isEqualTo("foo @ main.star:2:27 local={x=[1]}");
    // j sees the inner comprehension's x
    assertThat(j.getCallerDebugFrame()).isEqualTo("foo @ main.star:2:13 local={x=1}");
    // k is outside the comprehensions' scope, and sees the final value of foo's local x
    assertThat(k.getCallerDebugFrame()).isEqualTo("foo @ main.star:3:13 local={x=[[1], [1, 1]]}");
  }

  @Test
  public void testStepIntoFunction() throws Exception {
    StarlarkThread thread = newThread();

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.INTO);
    thread.push(defineFunc());

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepIntoFallsBackToStepOver() {
    // test that when stepping into, we'll fall back to stopping at the next statement in the
    // current frame
    StarlarkThread thread = newThread();

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.INTO);

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepIntoFallsBackToStepOut() throws Exception {
    // test that when stepping into, we'll fall back to stopping when exiting the current frame
    StarlarkThread thread = newThread();
    thread.push(defineFunc());

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.INTO);
    thread.pop();

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOverFunction() throws Exception {
    StarlarkThread thread = newThread();

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.OVER);
    thread.push(defineFunc());

    assertThat(predicate.test(thread)).isFalse();
    thread.pop();
    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOverFallsBackToStepOut() throws Exception {
    // test that when stepping over, we'll fall back to stopping when exiting the current frame
    StarlarkThread thread = newThread();
    thread.push(defineFunc());

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.OVER);
    thread.pop();

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOutOfInnerFrame() throws Exception {
    StarlarkThread thread = newThread();
    thread.push(defineFunc());

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.OUT);

    assertThat(predicate.test(thread)).isFalse();
    thread.pop();
    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOutOfOutermostFrame() {
    StarlarkThread thread = newThread();

    assertThat(Debug.stepControl(thread, Stepping.OUT)).isNull();
  }

  @Test
  public void testStepControlWithNoSteppingReturnsNull() {
    StarlarkThread thread = newThread();

    assertThat(Debug.stepControl(thread, Stepping.NONE)).isNull();
  }

  @Test
  public void testEvaluateVariableInScope() throws Exception {
    Module module =
        Module.withPredeclared(StarlarkSemantics.DEFAULT, ImmutableMap.of("a", StarlarkInt.of(1)));

    StarlarkThread thread = newThread();
    Object a = Starlark.execFile(ParserInput.fromLines("a"), FileOptions.DEFAULT, module, thread);
    assertThat(a).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testEvaluateVariableNotInScopeFails() {
    Module module = Module.create();

    SyntaxError.Exception e =
        assertThrows(
            SyntaxError.Exception.class,
            () ->
                Starlark.execFile(
                    ParserInput.fromLines("b"), FileOptions.DEFAULT, module, newThread()));

    assertThat(e).hasMessageThat().isEqualTo("name 'b' is not defined");
  }

  @Test
  public void testEvaluateExpressionOnVariableInScope() throws Exception {
    StarlarkThread thread = newThread();
    Module module =
        Module.withPredeclared(
            StarlarkSemantics.DEFAULT, /*predeclared=*/ ImmutableMap.of("a", "string"));

    assertThat(
            Starlark.execFile(
                ParserInput.fromLines("a.startswith('str')"), FileOptions.DEFAULT, module, thread))
        .isEqualTo(true);
    Starlark.execFile(ParserInput.fromLines("a = 1"), FileOptions.DEFAULT, module, thread);
    assertThat(Starlark.execFile(ParserInput.fromLines("a"), FileOptions.DEFAULT, module, thread))
        .isEqualTo(StarlarkInt.of(1));
  }
}
