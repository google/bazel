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

package net.starlark.java.eval;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.errorprone.annotations.FormatMethod;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.lib.json.Json;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;

/** Script-based tests of Starlark evaluator. */
public final class ScriptTest {

  // Tests for Starlark.
  //
  // In each test file, chunks are separated by "\n---\n".
  // Each chunk is evaluated separately.
  // A comment containing
  //     ### regular expression
  // specifies an expected error on that line.
  // The part after '###', with leading/trailing spaces removed,
  // must be a valid regular expression matching the error.
  // If there is no "###", the test will succeed iff there is no error.
  //
  // Within the file, the assert_ and assert_eq functions may be used to
  // report errors without stopping the program. (They are not evaluation
  // errors that can be caught with a '###' expectation.)

  // TODO(adonovan): improve this test driver (following go.starlark.net):
  //
  // - extract support for "chunked files" into a library
  //   and reuse it for tests of lexer, parser, resolver.
  // - require that some frame of each EvalException match the file/line of the expectation.

  interface Reporter {
    void reportError(StarlarkThread thread, String message);
  }

  @StarlarkMethod(
      name = "assert_",
      documented = false,
      parameters = {
        @Param(name = "cond"),
        @Param(name = "msg", defaultValue = "'assertion failed'"),
      },
      useStarlarkThread = true)
  public Object assertStarlark(Object cond, String msg, StarlarkThread thread)
      throws EvalException {
    if (!Starlark.truth(cond)) {
      reportErrorf(thread, "assert_: %s", msg);
    }
    return Starlark.NONE;
  }

  @StarlarkMethod(
      name = "assert_eq",
      documented = false,
      parameters = {
        @Param(name = "x"),
        @Param(name = "y"),
      },
      useStarlarkThread = true)
  public Object assertEq(Object x, Object y, StarlarkThread thread) throws EvalException {
    if (!x.equals(y)) {
      reportErrorf(thread, "assert_eq: %s != %s", Starlark.repr(x), Starlark.repr(y));
    }
    return Starlark.NONE;
  }

  @StarlarkMethod(
      name = "assert_fails",
      doc = "assert_fails asserts that evaluation of f() fails with the specified error",
      parameters = {
        @Param(name = "f", doc = "the Starlark function to call"),
        @Param(
            name = "wantError",
            doc = "a regular expression matching the expected error message"),
      },
      useStarlarkThread = true)
  public Object assertFails(StarlarkCallable f, String wantError, StarlarkThread thread)
      throws EvalException, InterruptedException {
    Pattern pattern;
    try {
      pattern = Pattern.compile(wantError);
    } catch (PatternSyntaxException unused) {
      throw Starlark.errorf("invalid regexp: %s", wantError);
    }

    try {
      Starlark.call(thread, f, ImmutableList.of(), ImmutableMap.of());
      reportErrorf(thread, "evaluation succeeded unexpectedly (want error matching %s)", wantError);
    } catch (EvalException ex) {
      // Verify error matches expectation.
      String msg = ex.getMessage();
      if (!pattern.matcher(msg).find()) {
        reportErrorf(thread, "regular expression (%s) did not match error (%s)", pattern, msg);
      }
    }
    return Starlark.NONE;
  }

  @FormatMethod
  private static void reportErrorf(StarlarkThread thread, String format, Object... args) {
    thread.getThreadLocal(Reporter.class).reportError(thread, String.format(format, args));
  }

  // Constructor for simple structs, for testing.
  @StarlarkMethod(name = "struct", documented = false, extraKeywords = @Param(name = "kwargs"))
  public Struct struct(Dict<String, Object> kwargs) throws EvalException {
    return new ImmutableStruct(ImmutableMap.copyOf(kwargs));
  }

  @StarlarkMethod(
      name = "mutablestruct",
      documented = false,
      extraKeywords = @Param(name = "kwargs"))
  public Struct mutablestruct(Dict<String, Object> kwargs) throws EvalException {
    return new MutableStruct(kwargs);
  }

  @StarlarkMethod(
      name = "freeze",
      doc = "Shallow-freezes the operand. With no argument, freezes the thread.",
      parameters = {@Param(name = "x", defaultValue = "unbound")},
      useStarlarkThread = true)
  public void freeze(Object x, StarlarkThread thread) throws EvalException {
    if (x == Starlark.UNBOUND) {
      thread.mutability().close();
      return;
    }

    if (x instanceof Mutability.Freezable) {
      ((Mutability.Freezable) x).unsafeShallowFreeze();
    } else {
      throw Starlark.errorf("%s value is not freezable", Starlark.type(x));
    }
  }

  @StarlarkMethod(
      name = "int_mul_slow",
      doc = "Slow but reliable integer multiplication with round-trip to BigInteger",
      parameters = {@Param(name = "x"), @Param(name = "y")})
  public StarlarkInt intMulSlow(StarlarkInt x, StarlarkInt y) {
    return StarlarkInt.of(x.toBigInteger().multiply(y.toBigInteger()));
  }

  private static boolean ok = true;

  public static void main(String[] args) throws Exception {
    File root = new File("third_party/bazel"); // blaze
    if (!root.exists()) {
      root = new File("."); // bazel
    }
    File testdata = new File(root, "src/test/java/net/starlark/java/eval/testdata");
    for (String name : testdata.list()) {
      File file = new File(testdata, name);
      String content = Files.asCharSource(file, UTF_8).read();
      int linenum = 1;
      for (String chunk : Splitter.on("\n---\n").split(content)) {
        // prepare chunk
        StringBuilder buf = new StringBuilder();
        for (int i = 1; i < linenum; i++) {
          buf.append('\n');
        }
        buf.append(chunk);
        if (false) {
          System.err.printf("%s:%d: <<%s>>\n", file, linenum, buf);
        }

        // extract expectations: ### "regular expression"
        Map<Pattern, Integer> expectations = new HashMap<>();
        for (int i = chunk.indexOf("###"); i >= 0; i = chunk.indexOf("###", i)) {
          int j = chunk.indexOf("\n", i);
          if (j < 0) {
            j = chunk.length();
          }

          int line = linenum + newlines(chunk.substring(0, i));
          String comment = chunk.substring(i + 3, j);
          i = j;

          // Compile regular expression in comment.
          Pattern pattern;
          try {
            pattern = Pattern.compile(comment.trim());
          } catch (PatternSyntaxException ex) {
            System.err.printf("%s:%d: invalid regexp: %s\n", file, line, ex.getMessage());
            ok = false;
            continue;
          }

          if (false) {
            System.err.printf("%s:%d: expectation '%s'\n", file, line, pattern);
          }
          expectations.put(pattern, line);
        }

        // parse & execute
        ParserInput input = ParserInput.fromString(buf.toString(), file.toString());
        ImmutableMap.Builder<String, Object> predeclared = ImmutableMap.builder();
        Starlark.addMethods(predeclared, new ScriptTest()); // e.g. assert_eq
        predeclared.put("json", Json.INSTANCE);

        StarlarkSemantics semantics = StarlarkSemantics.DEFAULT;
        Module module = Module.withPredeclared(semantics, predeclared.buildOrThrow());
        try (Mutability mu = Mutability.createAllowingShallowFreeze("test")) {
          StarlarkThread thread = StarlarkThread.createTransient(mu, semantics);
          thread.setThreadLocal(Reporter.class, ScriptTest::reportError);
          Starlark.execFile(input, FileOptions.DEFAULT, module, thread);

        } catch (SyntaxError.Exception ex) {
          // parser/resolver errors
          //
          // Static errors cannot be suppressed by expectations:
          // it would be dangerous because the presence of a static
          // error prevents execution of any dynamic assertions in
          // a chunk. Tests of static errors belong in syntax/.
          for (SyntaxError err : ex.errors()) {
            System.err.println(err); // includes location
            ok = false;
          }

        } catch (EvalException ex) {
          // evaluation error
          //
          // TODO(adonovan): the old logic checks only that each error is matched
          // by at least one expectation. Instead, ensure that errors
          // and exceptions match exactly. Furthermore, look only at errors
          // whose stack has a frame with a file/line that matches the expectation.
          // This requires inspecting EvalException stack.
          // (There can be at most one dynamic error per chunk.
          // Do we even need to allow multiple expectations?)
          if (!expected(expectations, ex.getMessage())) {
            System.err.println(ex.getMessageWithStack());
            ok = false;
          }

        } catch (Throwable ex) {
          // unhandled exception (incl. InterruptedException)
          System.err.printf(
              "%s:%d: unhandled %s in this chunk: %s\n",
              file, linenum, ex.getClass().getSimpleName(), ex.getMessage());
          ex.printStackTrace();
          ok = false;
        }

        // unmatched expectations
        for (Map.Entry<Pattern, Integer> e : expectations.entrySet()) {
          System.err.printf("%s:%d: unmatched expectation: %s\n", file, e.getValue(), e.getKey());
          ok = false;
        }

        // advance line number
        linenum += newlines(chunk) + 2; // for "\n---\n"
      }
    }
    if (!ok) {
      System.exit(1);
    }
  }

  // Called by assert_ and assert_eq when the test encounters an error.
  // Does not stop the program; multiple failures may be reported in a single run.
  private static void reportError(StarlarkThread thread, String message) {
    System.err.printf("Traceback (most recent call last):\n");
    List<StarlarkThread.CallStackEntry> stack = thread.getCallStack();
    stack = stack.subList(0, stack.size() - 1); // pop the built-in function
    for (StarlarkThread.CallStackEntry fr : stack) {
      System.err.printf("%s: called from %s\n", fr.location, fr.name);
    }
    System.err.println("Error: " + message);
    ok = false;
  }

  private static boolean expected(Map<Pattern, Integer> expectations, String message) {
    for (Pattern pattern : expectations.keySet()) {
      if (pattern.matcher(message).find()) {
        expectations.remove(pattern);
        return true;
      }
    }
    return false;
  }

  private static int newlines(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        n++;
      }
    }
    return n;
  }

  // A trivial struct-like class with Starlark fields defined by a map.
  private static class Struct implements StarlarkValue, Structure {
    final Map<String, Object> fields;

    Struct(Map<String, Object> fields) {
      this.fields = fields;
    }

    @Override
    public ImmutableList<String> getFieldNames() {
      return ImmutableList.copyOf(fields.keySet());
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
      p.append(Starlark.type(this));
      p.append("(");
      String sep = "";
      for (Map.Entry<String, Object> e : fields.entrySet()) {
        p.append(sep).append(e.getKey()).append(" = ").repr(e.getValue());
        sep = ", ";
      }
      p.append(")");
    }
  }

  @StarlarkBuiltin(name = "struct")
  private static class ImmutableStruct extends Struct {
    ImmutableStruct(ImmutableMap<String, Object> fields) {
      super(fields);
    }
  }

  @StarlarkBuiltin(name = "mutablestruct")
  private static class MutableStruct extends Struct {
    MutableStruct(Dict<String, Object> fields) {
      super(fields);
    }

    @Override
    public void setField(String field, Object value) throws EvalException {
      if (value.equals("bad")) {
        throw Starlark.errorf("bad field value");
      }
      ((Dict<String, Object>) fields).putEntry(field, value);
    }
  }
}
