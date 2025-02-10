// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util;

import com.google.common.base.StandardSystemProperty;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.bugreport.BugReporter;
import java.lang.management.ManagementFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.ObjectName;

/**
 * A helper class to offset some items from the final heap metrics.
 *
 * <p>Context: b/311665999.
 */
public final class HeapOffsetHelper {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private HeapOffsetHelper() {}

  static boolean isWorkaroundNeeded() {
    // Verify we're using OpenJDK 21.
    return StandardSystemProperty.JAVA_VM_NAME.value().contains("OpenJDK")
        && Runtime.version().feature() >= 21;
  }

  /**
   * Workaround for the FillerArray issue with JDK21. TODO(b/311665999) Remove this ASAP.
   *
   * <p>With JDK21, an arbitrary amount of FillerArray instances is retained on the heap at the end
   * of each command, which fluctuates our heap metrics a lot. This method looks into the heap
   * histogram and gets the total #bytes attributed to the FillerArray. We'll offset that against
   * the final value to make it more stable.
   */
  @SuppressWarnings("StringSplitter")
  public static long getSizeOfFillerArrayOnHeap(
      Pattern internalJvmObjectPattern, BugReporter bugReporter) {
    long sizeInBytes = 0;

    // Verify we're using OpenJDK 21 before proceeding.
    if (!isWorkaroundNeeded()) {
      return 0;
    }

    boolean foundInternal = false;
    boolean hasManagementApi = false;
    String histogram = "";
    try {
      histogram =
          (String)
              ManagementFactory.getPlatformMBeanServer()
                  .invoke(
                      new ObjectName("com.sun.management:type=DiagnosticCommand"),
                      "gcClassHistogram",
                      new Object[] {null},
                      new String[] {"[Ljava.lang.String;"});
      hasManagementApi = true;
      for (String line : histogram.split("\n")) {
        Matcher m = internalJvmObjectPattern.matcher(line);
        // ["", <num>, <#instances>, <#bytes>, <class name>]
        if (m.find()) {
          foundInternal = true;
          sizeInBytes += Long.parseLong(line.split("\\s+")[3]);
        }
      }
    } catch (Exception e) {
      // This should already be false, but just to be sure set it again because
      // something went wrong trying to get the management API histogram.
      hasManagementApi = false;
      // Swallow all exceptions.
      logger.atWarning().withCause(e).log(
          "Failed to obtain the size of jdk.internal.vm.FillerArray");
    }

    logIfMissingFillerArray(bugReporter, foundInternal, hasManagementApi, histogram);

    if (sizeInBytes > 0) {
      logger.atInfo().log(
          "Offsetting %d bytes of jdk.internal.vm.FillerArray in the retained heap metric.",
          sizeInBytes);
    }
    return sizeInBytes;
  }

  /**
   * Logs a non-fatal bug report if the filler-array type wasn't found.
   *
   * <p>Note that this can happen if we're issuing command fast enough for the filler array to not
   * be present yet, as can happen in shell tests. For this reason logging is skipped in shell
   * tests, since otherwise bug-report logging is configured to crash in tests.
   */
  private static void logIfMissingFillerArray(
      BugReporter bugReporter, boolean foundInternal, boolean hasManagementApi, String histogram) {
    if (TestType.getTestType() == TestType.SHELL_INTEGRATION) {
      return;
    }

    if (!foundInternal && hasManagementApi) {
      bugReporter.logUnexpected(
          "Unable to identify JDK 21+ G1 GC internal 'filler' array. Reported Blaze JVM memory"
              + " metrics are volatile See b/311665999.  vm.name=%s, feature=%d histogram=%s",
          StandardSystemProperty.JAVA_VM_NAME.value(), Runtime.version().feature(), histogram);
    }
  }
}
