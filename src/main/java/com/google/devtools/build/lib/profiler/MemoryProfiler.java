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

package com.google.devtools.build.lib.profiler;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.util.HeapOffsetHelper;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Blaze memory profiler.
 *
 * <p>At each call to {@code profile} performs garbage collection and stores heap and non-heap
 * memory usage in an external file.
 *
 * <p><em>Heap memory</em> is the runtime data area from which memory for all class instances and
 * arrays is allocated. <em>Non-heap memory</em> includes the method area and memory required for
 * the internal processing or optimization of the JVM. It stores per-class structures such as a
 * runtime constant pool, field and method data, and the code for methods and constructors. The Java
 * Native Interface (JNI) code or the native library of an application and the JVM implementation
 * allocate memory from the <em>native heap</em>.
 *
 * <p>The script in /devtools/blaze/scripts/blaze-memchart.sh can be used for post processing.
 */
public final class MemoryProfiler {

  private static final MemoryProfiler INSTANCE = new MemoryProfiler();

  public static MemoryProfiler instance() {
    return INSTANCE;
  }

  private PrintStream memoryProfile;
  private ProfilePhase currentPhase;
  private long heapUsedMemoryAtFinish;
  @Nullable private MemoryProfileStableHeapParameters memoryProfileStableHeapParameters;
  private Pattern internalJvmObjectPattern;

  public synchronized void setStableMemoryParameters(
      MemoryProfileStableHeapParameters memoryProfileStableHeapParameters,
      Pattern internalJvmObjectPattern) {
    this.memoryProfileStableHeapParameters = memoryProfileStableHeapParameters;
    this.internalJvmObjectPattern = internalJvmObjectPattern;
  }

  public synchronized void start(OutputStream out) {
    this.memoryProfile = (out == null) ? null : new PrintStream(out);
    this.currentPhase = ProfilePhase.INIT;
    heapUsedMemoryAtFinish = 0;
  }

  public synchronized void stop() {
    if (memoryProfile != null) {
      memoryProfile.close();
      memoryProfile = null;
    }
    heapUsedMemoryAtFinish = 0;
  }

  public synchronized long getHeapUsedMemoryAtFinish() {
    return heapUsedMemoryAtFinish;
  }

  public synchronized void markPhase(ProfilePhase nextPhase) throws InterruptedException {
    if (memoryProfile != null) {
      MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
      HeapAndNonHeap memoryUsages =
          prepareBeanAndGetLocalMinUsage(
              nextPhase, bean, (duration) -> Thread.sleep(duration.toMillis()));
      String name = currentPhase.description;
      MemoryUsage memoryUsage = memoryUsages.heap();
      var usedMemory = memoryUsage.getUsed();
      // TODO(b/311665999) Remove the subtraction of FillerArray once we figure out an alternative.
      if (nextPhase == ProfilePhase.FINISH) {
        usedMemory -=
            HeapOffsetHelper.getSizeOfFillerArrayOnHeap(
                internalJvmObjectPattern, BugReporter.defaultInstance());
        heapUsedMemoryAtFinish = usedMemory;
      }
      memoryProfile.println(name + ":heap:init:" + memoryUsage.getInit());
      memoryProfile.println(name + ":heap:used:" + usedMemory);
      memoryProfile.println(name + ":heap:commited:" + memoryUsage.getCommitted());
      memoryProfile.println(name + ":heap:max:" + memoryUsage.getMax());

      memoryUsage = memoryUsages.nonHeap();
      memoryProfile.println(name + ":non-heap:init:" + memoryUsage.getInit());
      memoryProfile.println(name + ":non-heap:used:" + memoryUsage.getUsed());
      memoryProfile.println(name + ":non-heap:commited:" + memoryUsage.getCommitted());
      memoryProfile.println(name + ":non-heap:max:" + memoryUsage.getMax());
      currentPhase = nextPhase;
    }
  }

  @VisibleForTesting
  synchronized HeapAndNonHeap prepareBeanAndGetLocalMinUsage(
      ProfilePhase nextPhase, MemoryMXBean bean, Sleeper sleeper) throws InterruptedException {
    bean.gc();
    MemoryUsage minHeapUsed = bean.getHeapMemoryUsage();
    MemoryUsage minNonHeapUsed = bean.getNonHeapMemoryUsage();

    if (nextPhase == ProfilePhase.FINISH && memoryProfileStableHeapParameters != null) {
      for (int j = 0; j < memoryProfileStableHeapParameters.gcSpecs.size(); j++) {
        Pair<Integer, Duration> spec = memoryProfileStableHeapParameters.gcSpecs.get(j);

        int numTimesToDoGc = spec.first;
        Duration timeToSleepBetweenGcs = spec.second;

        for (int i = 0; i < numTimesToDoGc; i++) {
          // We want to skip the first cycle for the first spec, since we ran a
          // GC at the top of this function, but all the rest should get their
          // proper runs
          if (j == 0 && i == 0) {
            continue;
          }

          sleeper.sleep(timeToSleepBetweenGcs);
          bean.gc();
          MemoryUsage currentHeapUsed = bean.getHeapMemoryUsage();
          if (currentHeapUsed.getUsed() < minHeapUsed.getUsed()) {
            minHeapUsed = currentHeapUsed;
            minNonHeapUsed = bean.getNonHeapMemoryUsage();
          }
        }
      }
    }
    return HeapAndNonHeap.create(minHeapUsed, minNonHeapUsed);
  }

  /**
   * Parameters that control how {@code MemoryProfiler} tries to get a stable heap at the end of the
   * build.
   */
  public static class MemoryProfileStableHeapParameters {
    private final ArrayList<Pair<Integer, Duration>> gcSpecs;

    private MemoryProfileStableHeapParameters(ArrayList<Pair<Integer, Duration>> gcSpecs) {
      this.gcSpecs = gcSpecs;
    }

    /** Converter for {@code MemoryProfileStableHeapParameters} option. */
    public static class Converter
        extends com.google.devtools.common.options.Converter.Contextless<
            MemoryProfileStableHeapParameters> {
      private static final Splitter SPLITTER = Splitter.on(',');

      @Override
      public MemoryProfileStableHeapParameters convert(String input)
          throws OptionsParsingException {
        List<String> values = SPLITTER.splitToList(input);

        if (values.size() % 2 != 0) {
          throw new OptionsParsingException(
              "Expected even number of comma-separated integer values");
        }

        ArrayList<Pair<Integer, Duration>> gcSpecs = new ArrayList<>(values.size() / 2);

        try {
          for (int i = 0; i < values.size(); i += 2) {
            int numTimesToDoGc = Integer.parseInt(values.get(i));
            int numSecondsToSleepBetweenGcs = Integer.parseInt(values.get(i + 1));

            if (numTimesToDoGc <= 0) {
              throw new OptionsParsingException("Number of times to GC must be positive");
            }
            if (numSecondsToSleepBetweenGcs < 0) {
              throw new OptionsParsingException(
                  "Number of seconds to sleep between GC's must be non-negative");
            }
            gcSpecs.add(Pair.of(numTimesToDoGc, Duration.ofSeconds(numSecondsToSleepBetweenGcs)));
          }

          return new MemoryProfileStableHeapParameters(gcSpecs);
        } catch (NumberFormatException | NoSuchElementException nfe) {
          throw new OptionsParsingException(
              "Expected even number of comma-separated integer values, could not parse integer in"
                  + " list",
              nfe);
        }
      }

      @Override
      public String getTypeDescription() {
        return "integers, separated by a comma expected in pairs";
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("gcSpecs", gcSpecs).toString();
    }
  }

  @VisibleForTesting
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  @VisibleForTesting
  record HeapAndNonHeap(MemoryUsage heap, MemoryUsage nonHeap) {
    HeapAndNonHeap {
      requireNonNull(heap, "heap");
      requireNonNull(nonHeap, "nonHeap");
    }

    static HeapAndNonHeap create(MemoryUsage heap, MemoryUsage nonHeap) {
      return new HeapAndNonHeap(heap, nonHeap);
    }
  }
}
