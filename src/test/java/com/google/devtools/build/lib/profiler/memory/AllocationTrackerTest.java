// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler.memory;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleFunction;
import com.google.devtools.build.lib.profiler.memory.AllocationTracker.RuleBytes;
import com.google.perftools.profiles.ProfileProto.Function;
import com.google.perftools.profiles.ProfileProto.Profile;
import com.google.perftools.profiles.ProfileProto.Sample;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.starlark.java.eval.Debug;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.HasBinary;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;
import net.starlark.java.syntax.TokenKind;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link AllocationTracker}. */
@RunWith(JUnit4.class)
public final class AllocationTrackerTest {

  // These tests are quite artificial as they call sampleAllocation explicitly.
  // In reality, a call could occur after any 'new' operation.

  private AllocationTracker tracker;
  private final ArrayList<Object> live = new ArrayList<>();

  // A Starlark value whose plus operator "x + 123" simulates allocation of 123 bytes.
  // (We trigger allocation with an operator not a function call so as not to change the stack.)
  private class SamplerValue implements HasBinary {
    @Override
    public Object binaryOp(TokenKind op, Object that, boolean thisLeft) throws EvalException {
      if (op == TokenKind.PLUS && thisLeft && that instanceof StarlarkInt starlarkInt) {
        int size = starlarkInt.toIntUnchecked(); // test values are small
        Object obj = new Object();
        live.add(obj); // ensure that obj outlives the test assertions
        tracker.sampleAllocation(1, "", obj, size);
        return Starlark.NONE;
      }
      return null;
    }
  }

  private static RuleClass myRuleClass() {
    RuleClass myrule = mock(RuleClass.class);
    when(myrule.getName()).thenReturn("myrule");
    when(myrule.getKey()).thenReturn("myrule");
    return myrule;
  }

  @Before
  public void setup() {
    CurrentRuleTracker.setEnabled(true);
    tracker = new AllocationTracker(1, 0);
    Debug.setThreadHook(tracker);
  }

  @After
  public void tearDown() {
    Debug.setThreadHook(null);
    CurrentRuleTracker.setEnabled(false);
  }

  @Test
  public void testMemoryProfileDuringExecution() throws Exception {
    // The nop() calls force the frame PC location to be updated.
    // It is not updated for a + operation on the assumption that
    // the stack is unobservable to an implementation of the +
    // operator... but the AllocationTracker sneaks a peek at it
    // using thread-local storage.
    // TODO(b/149023294): update this when we use a compiled representation.
    exec(
        "def nop(): pass",
        "def g():",
        "  nop(); sample + 12", // sample[0]: 12 bytes
        "def f():",
        "  g()",
        "  nop(); sample + 73", // sample[1]: 73 bytes
        "f()");

    Map<String, RuleBytes> rules = new HashMap<>();
    Map<String, RuleBytes> aspects = new HashMap<>();
    tracker.getRuleMemoryConsumption(rules, aspects);
    assertThat(rules).isEmpty();
    assertThat(aspects).isEmpty();

    Profile profile = tracker.buildMemoryProfile();
    assertThat(profile.getSampleList()).hasSize(2);
    Set<String> lines = new HashSet<>();
    for (Sample s : profile.getSampleList()) {
      lines.add(sampleToCallstack(profile, s));
    }
    assertThat(lines).contains("a.star:f:6, a.star:<toplevel>:7");
    assertThat(lines).contains("a.star:g:3, a.star:f:5, a.star:<toplevel>:7");
  }

  /** Formats a call stack as a comma-separated list of file:function:line elements. */
  private static String sampleToCallstack(Profile profile, Sample sample) {
    StringBuilder buf = new StringBuilder();
    for (long locationId : sample.getLocationIdList()) {
      com.google.perftools.profiles.ProfileProto.Location location =
          profile.getLocation((int) locationId - 1);
      assertThat(location.getLineList()).hasSize(1);
      long functionId = location.getLine(0).getFunctionId();
      long line = location.getLine(0).getLine();
      Function function = profile.getFunction((int) functionId - 1);
      long fileId = function.getFilename();
      long methodId = function.getName();
      String file = profile.getStringTable((int) fileId);
      String method = profile.getStringTable((int) methodId);
      if (buf.length() > 0) {
        buf.append(", ");
      }
      buf.append(String.format("%s:%s:%d", file, method, line));
    }
    return buf.toString();
  }

  @Test
  public void testConfiguredTargetsMemoryAllocation() throws Exception {
    CurrentRuleTracker.beginConfiguredTarget(myRuleClass());
    Object ruleAllocation0 = new Object();
    Object ruleAllocation1 = new Object();
    tracker.sampleAllocation(1, "", ruleAllocation0, 10);
    tracker.sampleAllocation(1, "", ruleAllocation1, 20);
    CurrentRuleTracker.endConfiguredTarget();

    CurrentRuleTracker.beginConfiguredAspect(() -> "aspect");
    Object aspectAllocation = new Object();
    tracker.sampleAllocation(1, "", aspectAllocation, 12);
    CurrentRuleTracker.endConfiguredAspect();

    Map<String, RuleBytes> rules = new HashMap<>();
    Map<String, RuleBytes> aspects = new HashMap<>();
    tracker.getRuleMemoryConsumption(rules, aspects);
    assertThat(rules).containsExactly("myrule", new RuleBytes("myrule").addBytes(30L));
    assertThat(aspects).containsExactly("aspect", new RuleBytes("aspect").addBytes(12L));

    Profile profile = tracker.buildMemoryProfile();
    assertThat(profile.getSampleList()).isEmpty(); // no callstacks
  }

  @Test
  public void testLoadingPhaseRuleAllocations() throws Exception {
    exec(
        "def g():", //
        "  myrule()",
        "def f():",
        "  g()",
        "f()");
    Map<String, RuleBytes> rules = new HashMap<>();
    Map<String, RuleBytes> aspects = new HashMap<>();
    tracker.getRuleMemoryConsumption(rules, aspects);
    assertThat(rules).containsExactly("myrule", new RuleBytes("myrule").addBytes(128L));
  }

  private void exec(String... lines)
      throws SyntaxError.Exception, EvalException, InterruptedException {
    ParserInput input = ParserInput.fromString(Joiner.on("\n").join(lines), "a.star");
    Module module =
        Module.withPredeclared(
            StarlarkSemantics.DEFAULT,
            ImmutableMap.of(
                "sample", new SamplerValue(),
                "myrule", new MyRuleFunction()));
    try (Mutability mu = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
      Starlark.execFile(input, FileOptions.DEFAULT, module, thread);
    }
  }

  // A fake Bazel rule. The allocation tracker reports retained memory broken down by rule class.
  private class MyRuleFunction implements RuleFunction, StarlarkCallable {
    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs) {
      Object obj = new Object();
      live.add(obj); // ensure that obj outlives the test assertions
      tracker.sampleAllocation(1, "", obj, 128);
      return Starlark.NONE;
    }

    @Override
    public String getName() {
      return "myrule";
    }

    @Override
    public RuleClass getRuleClass() {
      return myRuleClass();
    }
  }

}
