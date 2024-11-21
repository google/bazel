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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import net.starlark.java.syntax.Location;

/**
 * An StarlarkThread represents a Starlark thread.
 *
 * <p>It holds the stack of active Starlark and built-in function calls. In addition, it may hold
 * per-thread application state (see {@link #setThreadLocal}) that passes through Starlark functions
 * but does not directly affect them, such as information about the BUILD file being loaded.
 *
 * <p>StarlarkThreads are not thread-safe: they should be confined to a single Java thread.
 *
 * <p>Every StarlarkThread has an associated {@link Mutability}, which should be created for that
 * thread, and closed once the thread's work is done. (A try-with-resources statement is handy for
 * this purpose.) Starlark values created by the thread are associated with the thread's Mutability,
 * so that when the Mutability is closed at the end of the computation, all the values created by
 * the thread become frozen. This pattern ensures that all Starlark values are frozen before they
 * are published to another thread, and thus that concurrently executing Starlark threads are free
 * from data races. Once a thread's mutability is frozen, the thread is unlikely to be useful for
 * further computation because it can no longer create mutable values. (This is occasionally
 * valuable in tests.)
 */
public final class StarlarkThread {

  /** The mutability of values created by this thread. */
  private final Mutability mutability;

  // profiler state
  //
  // The profiler field (and savedThread) are set when we first observe during a
  // push (function call entry) that the profiler is active. They are unset
  // not in the corresponding pop, but when the last frame is popped, because
  // the profiler session might start in the middle of a call and/or run beyond
  // the lifetime of this thread.
  final AtomicInteger cpuTicks = new AtomicInteger();
  @Nullable private CpuProfiler profiler;
  private StarlarkThread savedThread; // saved StarlarkThread, when profiling reentrant evaluation

  private final Map<Class<?>, Object> threadLocals = new HashMap<>();

  private final SymbolGenerator<?> symbolGenerator;

  private boolean interruptible = true;

  long steps; // count of logical computation steps executed so far
  long stepLimit = Long.MAX_VALUE; // limit on logical computation steps

  /**
   * Returns the number of Starlark computation steps executed by this thread according to a
   * small-step semantics. (Today, that means exec, eval, and assign operations executed by the
   * tree-walking evaluator, but in future will mean byte code instructions; the two are not
   * commensurable.)
   */
  public long getExecutedSteps() {
    return steps;
  }

  /**
   * Sets the maximum number of Starlark computation steps that may be executed by this thread (see
   * {@link #getExecutedSteps}). When the step counter reaches or exceeds this value, execution
   * fails with an EvalException.
   */
  public void setMaxExecutionSteps(long steps) {
    this.stepLimit = steps;
  }

  /**
   * Disables polling of the {@link java.lang.Thread#interrupted} flag during Starlark evaluation.
   */
  // TODO(adonovan): expose a public API for this if we can establish a stronger semantics. (There
  // are other ways besides polling for evaluation to be interrupted, such as calling certain
  // built-in functions.)
  void ignoreThreadInterrupts() {
    interruptible = false;
  }

  void checkInterrupt() throws InterruptedException {
    if (interruptible && Thread.interrupted()) {
      throw new InterruptedException();
    }
  }

  /**
   * setThreadLocal saves {@code value} as a thread-local variable of this Starlark thread, keyed by
   * {@code key}, so that it can later be retrieved by {@code getThreadLocal(key)}.
   */
  public <T> void setThreadLocal(Class<T> key, T value) {
    threadLocals.put(key, value);
  }

  /**
   * getThreadLocal returns the value {@code v} supplied to the most recent {@code
   * setThreadLocal(key, v)} call, or null if there was no prior call.
   */
  public <T> T getThreadLocal(Class<T> key) {
    Object v = threadLocals.get(key);
    return v == null ? null : key.cast(v);
  }

  /** A Frame records information about an active function call. */
  static final class Frame implements Debug.Frame {
    final StarlarkThread thread;
    final StarlarkCallable fn; // the called function

    @Nullable
    final Debug.Debugger dbg = Debug.debugger.get(); // the debugger, if active for this frame

    Object result = Starlark.NONE; // the operand of a Starlark return statement

    // Current PC location. Initially fn.getLocation(); for Starlark functions,
    // it is updated at key points when it may be observed: calls, breakpoints, errors.
    private Location loc;

    // Indicates that setErrorLocation has been called already and the error
    // location (loc) should not be overrwritten.
    private boolean errorLocationSet;

    // The locals of this frame, if fn is a StarlarkFunction, otherwise null.
    // Set by StarlarkFunction.fastcall. Elements may be regular Starlark
    // values, or wrapped in StarlarkFunction.Cells if shared with a nested function.
    @Nullable Object[] locals;

    private long profileStartTimeNanos; // start time nanos of walltime call profiler

    private Frame(StarlarkThread thread, StarlarkCallable fn) {
      this.thread = thread;
      this.fn = fn;
    }

    // Updates the PC location in this frame.
    void setLocation(Location loc) {
      this.loc = loc;
    }

    // Sets location only the first time it is called,
    // to ensure that the location of the innermost expression
    // is used for errors.
    // (Once we switch to a bytecode interpreter, we can afford
    // to update fr.pc before each fallible operation, but until then
    // we must materialize Locations only after the fact of failure.)
    // Sets errorLocationSet.
    void setErrorLocation(Location loc) {
      if (!errorLocationSet) {
        errorLocationSet = true;
        this.loc = loc;
      }
    }

    @Override
    public StarlarkCallable getFunction() {
      return fn;
    }

    @Override
    public Location getLocation() {
      return loc;
    }

    @Override
    public ImmutableMap<String, Object> getLocals() {
      // TODO(adonovan): provide a more efficient API.
      ImmutableMap.Builder<String, Object> env = ImmutableMap.builder();
      if (fn instanceof StarlarkFunction) {
        for (int i = 0; i < locals.length; i++) {
          Object local = locals[i];
          if (local instanceof StarlarkFunction.Cell) {
            local = ((StarlarkFunction.Cell) local).x;
          }
          if (local != null) {
            env.put(((StarlarkFunction) fn).rfn.getLocals().get(i).getName(), local);
          }
        }
      }
      return env.buildOrThrow();
    }

    @Override
    public String toString() {
      return fn.getName() + "@" + loc;
    }
  }

  /** The semantics options that affect how Starlark code is evaluated. */
  private final StarlarkSemantics semantics;

  /** Whether recursive calls are allowed (cached from semantics). */
  private final boolean allowRecursion;

  /** PrintHandler for Starlark print statements. */
  private PrintHandler printHandler = StarlarkThread::defaultPrintHandler;

  /** Loader for Starlark load statements. Null if loading is disallowed. */
  @Nullable private Loader loader = null;

  private UncheckedExceptionContext uncheckedExceptionContext = () -> "";

  /** Stack of active function calls. */
  private final ArrayList<Frame> callstack = new ArrayList<>();

  /** A hook for notifications of assignments at top level. */
  PostAssignHook postAssignHook;

  /** Pushes a function onto the call stack. */
  void push(StarlarkCallable fn) {
    Frame fr = new Frame(this, fn);
    callstack.add(fr);

    // Notify debug tools of the thread's first push.
    if (callstack.size() == 1 && Debug.threadHook != null) {
      Debug.threadHook.onPushFirst(this);
    }

    fr.loc = fn.getLocation();

    // Start wall-time call profile span.
    CallProfiler callProfiler = StarlarkThread.callProfiler;
    if (callProfiler != null) {
      fr.profileStartTimeNanos = callProfiler.start();
    }

    // Poll for newly installed CPU profiler.
    if (profiler == null) {
      this.profiler = CpuProfiler.get();
      if (profiler != null) {
        cpuTicks.set(0);
        // Associated current Java thread with this StarlarkThread.
        // (Save the previous association so we can restore it later.)
        this.savedThread = CpuProfiler.setStarlarkThread(this);
      }
    }
  }

  /** Pops a function off the call stack. */
  void pop() {
    int last = callstack.size() - 1;
    Frame fr = callstack.get(last);

    if (profiler != null) {
      int ticks = cpuTicks.getAndSet(0);
      if (ticks > 0) {
        profiler.addEvent(ticks, getDebugCallStack());
      }

      // If this is the final pop in this thread,
      // unregister it from the profiler.
      if (last == 0) {
        // Restore the previous association (in case of reentrant evaluation).
        CpuProfiler.setStarlarkThread(this.savedThread);
        this.savedThread = null;
        this.profiler = null;
      }
    }

    callstack.remove(last); // pop

    // End wall-time profile span.
    CallProfiler callProfiler = StarlarkThread.callProfiler;
    if (callProfiler != null && fr.profileStartTimeNanos >= 0) {
      callProfiler.end(fr.profileStartTimeNanos, fr.fn);
    }

    // Notify debug tools of the thread's last pop.
    if (last == 0 && Debug.threadHook != null) {
      Debug.threadHook.onPopLast(this);
    }
  }

  /** Returns the mutability for values created by this thread. */
  public Mutability mutability() {
    return mutability;
  }

  /**
   * A PrintHandler determines how a Starlark thread deals with print statements. It is invoked by
   * the built-in {@code print} function. Its default behavior is to write the message to standard
   * error, preceded by the location of the print statement, {@code thread.getCallerLocation()}.
   */
  @FunctionalInterface
  public interface PrintHandler {
    void print(StarlarkThread thread, String msg);
  }

  /** Returns the PrintHandler for Starlark print statements. */
  PrintHandler getPrintHandler() {
    return printHandler;
  }

  /** Sets the behavior of Starlark print statements executed by this thread. */
  public void setPrintHandler(PrintHandler h) {
    this.printHandler = Preconditions.checkNotNull(h);
  }

  private static void defaultPrintHandler(StarlarkThread thread, String msg) {
    System.err.println(thread.getCallerLocation() + ": " + msg);
  }

  /**
   * A Loader determines the behavior of load statements executed by this thread. It returns the
   * named module, or null if not found.
   */
  @FunctionalInterface
  public interface Loader {
    @Nullable
    Module load(String module);
  }

  /** Returns the loader for Starlark load statements. */
  Loader getLoader() {
    return loader;
  }

  /** Sets the behavior of Starlark load statements executed by this thread. */
  public void setLoader(Loader loader) {
    this.loader = Preconditions.checkNotNull(loader);
  }

  /**
   * Supplies additional context to append to the message of {@link Starlark.UncheckedEvalException}
   * or {@link Starlark.UncheckedEvalError}.
   */
  // TODO(brandjon): This seems unnecessary. Instead of implementing a hook that is mutated after
  // thread is constructed, we should be able to just attach this information at construction time.
  public interface UncheckedExceptionContext {
    String getContextForUncheckedException();
  }

  public void setUncheckedExceptionContext(UncheckedExceptionContext uncheckedExceptionContext) {
    this.uncheckedExceptionContext = Preconditions.checkNotNull(uncheckedExceptionContext);
  }

  public String getContextDescription() {
    return uncheckedExceptionContext.getContextForUncheckedException();
  }

  /** Reports whether {@code fn} has been recursively reentered within this thread. */
  boolean isRecursiveCall(StarlarkFunction fn) {
    // Find fn buried within stack. (The top of the stack is assumed to be fn.)
    for (int i = callstack.size() - 2; i >= 0; --i) {
      Frame fr = callstack.get(i);
      // We compare code, not closure values, otherwise one can defeat the
      // check by writing the Y combinator.
      if (fr.fn instanceof StarlarkFunction && ((StarlarkFunction) fr.fn).rfn.equals(fn.rfn)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the location of the program counter in the enclosing call frame. If called from within
   * a built-in function, this is the location of the call expression that called the built-in. It
   * returns BUILTIN if called with fewer than two frames (such as within a test).
   */
  public Location getCallerLocation() {
    return toplevel() ? Location.BUILTIN : frame(1).loc;
  }

  /**
   * Reports whether the call stack has less than two frames. Zero frames means an idle thread. One
   * frame means the function for the top-level statements of a file is active. More than that means
   * a function call is in progress.
   *
   * <p>Every use of this function is a hack to work around the lack of proper local vs global
   * identifier resolution at top level.
   */
  private boolean toplevel() {
    return callstack.size() < 2;
  }

  // Returns the stack frame at the specified depth. 0 means top of stack, 1 is its caller, etc.
  Frame frame(int depth) {
    return callstack.get(callstack.size() - 1 - depth);
  }

  /**
   * Creates a StarlarkThread.
   *
   * @param mu the (non-frozen) mutability of values created by this thread.
   * @param semantics the StarlarkSemantics for this thread. Note that it is generally a code smell
   *     to use {@link StarlarkSemantics#DEFAULT} if the application permits customizing the
   *     semantics (e.g. via command line flags). Usually, all Starlark evaluation contexts within
   *     the same application would use the same {@code StarlarkSemantics} instance.
   * @param contextDescription a short description of this evaluation, added as context when an
   *     exception is thrown. The empty String can be used as a default value.
   * @param symbolGenerator a supplier of deterministic, stable IDs for objects created by this
   *     thread
   */
  // TODO(bazel-team): Consider merging contextDescription into the symbolGenerator.
  public static StarlarkThread create(
      Mutability mu,
      StarlarkSemantics semantics,
      String contextDescription,
      SymbolGenerator<?> symbolGenerator) {
    return new StarlarkThread(mu, semantics, contextDescription, symbolGenerator);
  }

  /**
   * Creates a StarlarkThread with an empty {@code contextDescription} and transient {@code
   * symbolGenerator}.
   *
   * <p>See comments at {@link SymbolGenerator#createTransient} for when this is applicable.
   */
  public static StarlarkThread createTransient(Mutability mu, StarlarkSemantics semantics) {
    return new StarlarkThread(
        mu, semantics, /* contextDescription= */ "", SymbolGenerator.createTransient());
  }

  private StarlarkThread(
      Mutability mu,
      StarlarkSemantics semantics,
      String contextDescription,
      SymbolGenerator<?> symbolGenerator) {
    Preconditions.checkArgument(!mu.isFrozen());
    this.mutability = mu;
    this.semantics = semantics;
    this.allowRecursion = semantics.getBool(StarlarkSemantics.ALLOW_RECURSION);
    if (!contextDescription.isEmpty()) {
      setUncheckedExceptionContext(() -> contextDescription);
    }
    this.symbolGenerator = symbolGenerator;
  }

  /**
   * Specifies a hook function to be run after each assignment at top level.
   *
   * <p>This is a short-term hack to allow us to consolidate all StarlarkFile execution in one place
   * even while BzlLoadFunction implements the old "export" behavior, in which rules, aspects and
   * providers are "exported" as soon as they are assigned, not at the end of file execution.
   */
  public void setPostAssignHook(PostAssignHook postAssignHook) {
    this.postAssignHook = postAssignHook;
  }

  /** A hook for notifications of assignments at top level. */
  @FunctionalInterface
  public interface PostAssignHook {
    void assign(String name, Object value);
  }

  public StarlarkSemantics getSemantics() {
    return semantics;
  }

  /** Reports whether this thread is allowed to make recursive calls. */
  boolean isRecursionAllowed() {
    return allowRecursion;
  }

  // Implementation of Debug.getCallStack.
  // Intentionally obscured to steer most users to the simpler getCallStack.
  ImmutableList<Debug.Frame> getDebugCallStack() {
    return ImmutableList.copyOf(callstack);
  }

  @Nullable
  StarlarkFunction getInnermostEnclosingStarlarkFunction(int depth) {
    Preconditions.checkArgument(depth >= 0);
    for (int i = callstack.size() - 1; i >= 0; i--) {
      Debug.Frame fr = callstack.get(i);
      if (fr.getFunction() instanceof StarlarkFunction) {
        if (depth == 0) {
          return (StarlarkFunction) fr.getFunction();
        }
        depth--;
      }
    }
    return null;
  }

  /** Returns the size of the callstack. This is needed for the debugger. */
  int getCallStackSize() {
    return callstack.size();
  }

  /**
   * The value of {@link CallStackEntry#name} for the implicit function that executes the top-level
   * statements of a file.
   */
  public static final String TOP_LEVEL = "<toplevel>";

  /** Creates a new {@link CallStackEntry}. */
  public static CallStackEntry callStackEntry(String name, Location location) {
    return new CallStackEntry(name, location);
  }

  /**
   * A CallStackEntry describes the name and PC location of an active function call. See {@link
   * #getCallStack}.
   */
  @Immutable
  public static final class CallStackEntry {
    public final String name;
    public final Location location;

    private CallStackEntry(String name, Location location) {
      this.name = Preconditions.checkNotNull(name);
      this.location = Preconditions.checkNotNull(location);
    }

    @Override
    public String toString() {
      return name + "@" + location;
    }

    @Override
    public int hashCode() {
      return 31 * name.hashCode() + location.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof CallStackEntry)) {
        return false;
      }
      CallStackEntry that = (CallStackEntry) o;
      return name.equals(that.name) && location.equals(that.location);
    }
  }

  /**
   * Returns information about this thread's current stack of active function calls, outermost call
   * first. For each function, it reports its name, and the location of its current program counter.
   * The result is immutable and does not reference interpreter data structures, so it may retained
   * indefinitely and safely shared with other threads.
   */
  public ImmutableList<CallStackEntry> getCallStack() {
    ImmutableList.Builder<CallStackEntry> stack =
        ImmutableList.builderWithExpectedSize(callstack.size());
    for (Frame fr : callstack) {
      stack.add(callStackEntry(fr.fn.getName(), fr.loc));
    }
    return stack.build();
  }

  /** Sets the given throwable's stack trace to a Java-style version of {@link #getCallStack}. */
  void fillInStackTrace(Throwable throwable) {
    StackTraceElement[] trace = new StackTraceElement[callstack.size()];
    for (int i = 0; i < callstack.size(); i++) {
      Frame frame = callstack.get(i);
      trace[trace.length - i - 1] =
          new StackTraceElement(
              "<starlark>", frame.fn.getName(), frame.loc.file(), frame.loc.line());
    }
    throwable.setStackTrace(trace);
  }

  @Override
  public int hashCode() {
    throw new UnsupportedOperationException(); // avoid nondeterminism
  }

  @Override
  public boolean equals(Object that) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return String.format("<StarlarkThread%s>", mutability);
  }

  /** CallProfiler records the start and end wall times of function calls. */
  public interface CallProfiler {
    long start();

    @SuppressWarnings("GoodTime") // This code is very performance sensitive.
    void end(long startTimeNanos, StarlarkCallable fn);
  }

  /** Installs a global hook that will be notified of function calls. */
  public static void setCallProfiler(@Nullable CallProfiler p) {
    callProfiler = p;
  }

  public SymbolGenerator.Symbol<?> getNextIdentityToken() {
    return symbolGenerator.generate();
  }

  public SymbolGenerator<?> getSymbolGenerator() {
    return symbolGenerator;
  }

  Object getOwner() {
    return symbolGenerator.getOwner();
  }

  @Nullable private static CallProfiler callProfiler = null;
}
