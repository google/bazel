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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ConditionallyThreadCompatible;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleFunction;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.monitoring.runtime.instrumentation.Sampler;
import com.google.perftools.profiles.ProfileProto.Function;
import com.google.perftools.profiles.ProfileProto.Line;
import com.google.perftools.profiles.ProfileProto.Profile;
import com.google.perftools.profiles.ProfileProto.Sample;
import com.google.perftools.profiles.ProfileProto.ValueType;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import net.starlark.java.eval.Debug;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/** Tracks allocations for memory reporting. */
@ConditionallyThreadCompatible
@SuppressWarnings("ThreadLocalUsage") // the AllocationTracker is effectively a global
public final class AllocationTracker implements Sampler, Debug.ThreadHook {

  // A mapping from Java thread to StarlarkThread.
  // Used to effect a hidden StarlarkThread parameter to sampleAllocation.
  // TODO(adonovan): opt: merge the three different ThreadLocals in use here.
  private final ThreadLocal<StarlarkThread> starlarkThread = new ThreadLocal<>();

  @Override
  public void onPushFirst(StarlarkThread thread) {
    starlarkThread.set(thread);
  }

  @Override
  public void onPopLast(StarlarkThread thread) {
    starlarkThread.remove();
  }

  private static class AllocationSample {
    @Nullable final RuleClass ruleClass; // Current rule being analysed, if any
    @Nullable final AspectClass aspectClass; // Current aspect being analysed, if any
    final ImmutableList<Frame> callstack; // Starlark callstack, if any
    final long bytes;

    AllocationSample(
        @Nullable RuleClass ruleClass,
        @Nullable AspectClass aspectClass,
        ImmutableList<Frame> callstack,
        long bytes) {
      this.ruleClass = ruleClass;
      this.aspectClass = aspectClass;
      this.callstack = callstack;
      this.bytes = bytes;
    }
  }

  private static class Frame {
    final String name;
    final Location loc;
    @Nullable final RuleFunction ruleFunction;

    Frame(String name, Location loc, @Nullable RuleFunction ruleFunction) {
      this.name = name;
      this.loc = loc;
      this.ruleFunction = ruleFunction;
    }
  }

  private final Cache<Object, AllocationSample> allocations =
      Caffeine.newBuilder().weakKeys().build();
  private final int samplePeriod;
  private final int sampleVariance;
  private boolean enabled = true;

  /**
   * Cheap wrapper class for a long. Avoids having to do two thread-local lookups per allocation.
   */
  private static final class LongValue {
    long value;
  }

  private final ThreadLocal<LongValue> currentSampleBytes = ThreadLocal.withInitial(LongValue::new);
  private final ThreadLocal<Long> nextSampleBytes = ThreadLocal.withInitial(this::getNextSample);
  private final Random random = new Random();

  AllocationTracker(int samplePeriod, int variance) {
    this.samplePeriod = samplePeriod;
    this.sampleVariance = variance;
  }

  // Called by instrumentation.recordAllocation, which is in turn called
  // by an instrumented version of the application assembled on the fly
  // by instrumentation.AllocationInstrumenter.
  // The instrumenter inserts a call to recordAllocation after every
  // memory allocation instruction in the original class.
  //
  // This function runs within 'new', so is not supposed to allocate memory;
  // see Sampler interface. In fact it allocates in nearly a dozen places.
  // TODO(adonovan): suppress reentrant calls by setting a thread-local flag.
  @Override
  @ThreadSafe
  public void sampleAllocation(int count, String desc, Object newObj, long size) {
    if (!enabled) {
      return;
    }

    @Nullable StarlarkThread thread = starlarkThread.get();

    // Calling Debug.getCallStack is a dubious operation here.
    // First it allocates memory, which breaks the Sampler contract.
    // Second, the allocation could in principle occur while the thread's
    // representation invariants are temporarily broken (that is, during
    // the call to ArrayList.add when pushing a new stack frame).
    // For now at least, the allocation done by ArrayList.add occurs before
    // the representation of the ArrayList is changed, so it is safe,
    // but this is a fragile assumption.
    ImmutableList<Debug.Frame> callstack =
        thread != null ? Debug.getCallStack(thread) : ImmutableList.of();

    RuleClass ruleClass = CurrentRuleTracker.getRule();
    AspectClass aspectClass = CurrentRuleTracker.getAspect();

    // Should we bother sampling?
    if (callstack.isEmpty() && ruleClass == null && aspectClass == null) {
      return;
    }

    // Convert the thread's stack right away to our internal form.
    // It is not safe to inspect Debug.Frame references once the thread resumes,
    // and keeping StarlarkCallable values live defeats garbage collection.
    ImmutableList.Builder<Frame> frames = ImmutableList.builderWithExpectedSize(callstack.size());
    for (Debug.Frame fr : callstack) {
      // The frame's PC location is currently not updated at every step,
      // only at function calls, so the leaf frame's line number may be
      // slightly off; see the tests.
      // TODO(b/149023294): remove comment when we move to a compiled representation.
      StarlarkCallable fn = fr.getFunction();
      frames.add(
          new Frame(
              fn.getName(),
              fr.getLocation(),
              fn instanceof RuleFunction ? (RuleFunction) fn : null));
    }

    // If we start getting stack overflows here, it's because the memory sampling
    // implementation has changed to call back into the sampling method immediately on
    // every allocation. Since thread locals can allocate, this can in this case lead
    // to infinite recursion. This method will then need to be rewritten to not
    // allocate, or at least not allocate to obtain its sample counters.
    LongValue bytesValue = currentSampleBytes.get();
    long bytes = bytesValue.value + size;
    if (bytes < nextSampleBytes.get()) {
      bytesValue.value = bytes;
      return;
    }
    bytesValue.value = 0;
    nextSampleBytes.set(getNextSample());
    allocations.put(newObj, new AllocationSample(ruleClass, aspectClass, frames.build(), bytes));
  }

  private long getNextSample() {
    return (long) samplePeriod
        + (sampleVariance > 0 ? (random.nextInt(sampleVariance * 2) - sampleVariance) : 0);
  }

  /** A pair of rule/aspect name and the bytes it consumes. */
  public static final class RuleBytes {
    private final String name;
    private long bytes;

    public RuleBytes(String name) {
      this.name = name;
    }

    /** The number of bytes total occupied by this rule or aspect class. */
    public long getBytes() {
      return bytes;
    }

    @CanIgnoreReturnValue
    public RuleBytes addBytes(long bytes) {
      this.bytes += bytes;
      return this;
    }

    @Override
    public String toString() {
      return String.format("RuleBytes(%s, %d)", name, bytes);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RuleBytes ruleBytes = (RuleBytes) o;
      return bytes == ruleBytes.bytes && Objects.equal(name, ruleBytes.name);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(name, bytes);
    }
  }

  // If the topmost stack entry is a call to a rule function, returns it.
  @Nullable
  private static RuleFunction getRule(AllocationSample sample) {
    Frame top = Iterables.getLast(sample.callstack, null);
    return top != null ? top.ruleFunction : null;
  }

  /**
   * Returns the total memory consumption for rules and aspects, keyed by {@link RuleClass#getKey}
   * or {@link AspectClass#getKey}.
   */
  public void getRuleMemoryConsumption(
      Map<String, RuleBytes> rules, Map<String, RuleBytes> aspects) {
    // Make sure we don't track our own allocations
    enabled = false;
    System.gc();

    // Get loading phase memory for rules.
    for (AllocationSample sample : allocations.asMap().values()) {
      RuleFunction rule = getRule(sample);
      if (rule != null) {
        RuleClass ruleClass = rule.getRuleClass();
        String key = ruleClass.getKey();
        RuleBytes ruleBytes = rules.computeIfAbsent(key, k -> new RuleBytes(ruleClass.getName()));
        rules.put(key, ruleBytes.addBytes(sample.bytes));
      }
    }
    // Get analysis phase memory for rules and aspects
    for (AllocationSample sample : allocations.asMap().values()) {
      if (sample.ruleClass != null) {
        String key = sample.ruleClass.getKey();
        RuleBytes ruleBytes =
            rules.computeIfAbsent(key, k -> new RuleBytes(sample.ruleClass.getName()));
        rules.put(key, ruleBytes.addBytes(sample.bytes));
      }
      if (sample.aspectClass != null) {
        String key = sample.aspectClass.getKey();
        RuleBytes ruleBytes =
            aspects.computeIfAbsent(key, k -> new RuleBytes(sample.aspectClass.getName()));
        aspects.put(key, ruleBytes.addBytes(sample.bytes));
      }
    }

    enabled = true;
  }

  /** Dumps all Starlark analysis time allocations to a pprof-compatible file. */
  public void dumpStarlarkAllocations(OutputStream outputStream) throws IOException {
    // Make sure we don't track our own allocations
    enabled = false;
    System.gc();
    Profile profile = buildMemoryProfile();
    try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
      profile.writeTo(gzipOutputStream);
      gzipOutputStream.finish();
    }
    enabled = true;
  }

  Profile buildMemoryProfile() {
    Profile.Builder profile = Profile.newBuilder();
    StringTable stringTable = new StringTable(profile);
    FunctionTable functionTable = new FunctionTable(profile, stringTable);
    LocationTable locationTable = new LocationTable(profile, functionTable);
    profile.addSampleType(
        ValueType.newBuilder()
            .setType(stringTable.get("memory"))
            .setUnit(stringTable.get("bytes"))
            .build());
    for (AllocationSample sample : allocations.asMap().values()) {
      // Skip empty callstacks
      if (sample.callstack.isEmpty()) {
        continue;
      }
      Sample.Builder b = Sample.newBuilder().addValue(sample.bytes);
      for (Frame fr : sample.callstack.reverse()) {
        b.addLocationId(locationTable.get(fr.loc.file(), fr.name, fr.loc.line()));
      }
      profile.addSample(b.build());
    }
    profile.setTimeNanos(Instant.now().getEpochSecond() * 1000000000);
    return profile.build();
  }

  private static class StringTable {
    final Profile.Builder profile;
    final Map<String, Long> table = new HashMap<>();
    long index = 0;

    StringTable(Profile.Builder profile) {
      this.profile = profile;
      get(""); // 0 is reserved for the empty string
    }

    long get(String str) {
      return table.computeIfAbsent(
          str,
          key -> {
            profile.addStringTable(key);
            return index++;
          });
    }
  }

  private static class FunctionTable {
    final Profile.Builder profile;
    final StringTable stringTable;
    final Map<String, Long> table = new HashMap<>();
    long index = 1; // 0 is reserved

    FunctionTable(Profile.Builder profile, StringTable stringTable) {
      this.profile = profile;
      this.stringTable = stringTable;
    }

    long get(String file, String function) {
      return table.computeIfAbsent(
          file + "#" + function,
          key -> {
            Function fn =
                Function.newBuilder()
                    .setId(index)
                    .setFilename(stringTable.get(file))
                    .setName(stringTable.get(function))
                    .build();
            profile.addFunction(fn);
            return index++;
          });
    }
  }

  private static class LocationTable {
    final Profile.Builder profile;
    final FunctionTable functionTable;
    final Map<String, Long> table = new HashMap<>();
    long index = 1; // 0 is reserved

    LocationTable(Profile.Builder profile, FunctionTable functionTable) {
      this.profile = profile;
      this.functionTable = functionTable;
    }

    long get(String file, String function, long line) {
      return table.computeIfAbsent(
          file + "#" + function + "#" + line,
          key -> {
            com.google.perftools.profiles.ProfileProto.Location location =
                com.google.perftools.profiles.ProfileProto.Location.newBuilder()
                    .setId(index)
                    .addLine(
                        Line.newBuilder()
                            .setFunctionId(functionTable.get(file, function))
                            .setLine(line)
                            .build())
                    .build();
            profile.addLocation(location);
            return index++;
          });
    }
  }
}
