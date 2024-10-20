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
package com.google.devtools.build.lib.runtime;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.exec.ExecutionOptions.TestOutputFormat;
import com.google.devtools.build.lib.exec.TestLogHelper;
import com.google.devtools.build.lib.util.LoggingUtil;
import com.google.devtools.build.lib.util.io.AnsiTerminalPrinter;
import com.google.devtools.build.lib.util.io.AnsiTerminalPrinter.Mode;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestStatus.FailedTestCasesStatus;
import com.google.devtools.build.lib.view.test.TestStatus.TestCase;
import com.google.devtools.build.lib.view.test.TestStatus.TestCase.Status;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Print test statistics in human readable form.
 */
public class TestSummaryPrinter {

  /**
   * Interface for getting the {@link String} to display to the user for a {@link Path}
   * corresponding to a test output (e.g. test log).
   */
  public interface TestLogPathFormatter {
    String getPathStringToPrint(Path path);
  }

  /** Print the cached test log to the given printer. */
  public static void printCachedOutput(
      TestSummary summary,
      TestOutputFormat testOutput,
      AnsiTerminalPrinter printer,
      TestLogPathFormatter testLogPathFormatter,
      int maxTestOutputBytes) {

    String testName = summary.getLabel().toString();
    List<String> allLogs = new ArrayList<>();
    for (Path path : summary.getFailedLogs()) {
      allLogs.add(testLogPathFormatter.getPathStringToPrint(path));
    }
    for (Path path : summary.getPassedLogs()) {
      allLogs.add(testLogPathFormatter.getPathStringToPrint(path));
    }
    printer.printLn(
        ""
            + summary.getStatusMode()
            + summary.getStatus()
            + ": "
            + Mode.DEFAULT
            + testName
            + " (see "
            + Joiner.on(' ').join(allLogs)
            + ")");
    printer.printLn(Mode.INFO + "INFO: " + Mode.DEFAULT + "From Testing " + testName);

    // Whether to output the target at all was checked by the caller.
    // Now check whether to output failing shards.
    if (TestLogHelper.shouldOutputTestLog(testOutput, false)) {
      for (Path path : summary.getFailedLogs()) {
        try {
          TestLogHelper.writeTestLog(path, testName, printer.getOutputStream(), maxTestOutputBytes);
        } catch (IOException e) {
          printer.printLn("==================== Could not read test output for " + testName);
          LoggingUtil.logToRemote(Level.WARNING, "Error while reading test log", e);
        }
      }
    }

    // And passing shards, independently.
    if (TestLogHelper.shouldOutputTestLog(testOutput, true)) {
      for (Path path : summary.getPassedLogs()) {
        try {
          TestLogHelper.writeTestLog(path, testName, printer.getOutputStream(), maxTestOutputBytes);
        } catch (Exception e) {
          printer.printLn("==================== Could not read test output for " + testName);
          LoggingUtil.logToRemote(Level.WARNING, "Error while reading test log", e);
        }
      }
    }
  }

  private static String statusString(TestSummary summary) {
    if (summary.isSkipped()) {
      // If the test was skipped then its status will be something like NO_STATUS. That's not
      // informative enough to a user. Instead, return "SKIPPED" for skipped tests.
      return "SKIPPED";
    }
    return summary.getStatus().toString().replace('_', ' ');
  }

  /**
   * Prints summary status for a single test.
   *
   * @param terminalPrinter The printer to print to
   */
  public static void print(
      TestSummary summary,
      AnsiTerminalPrinter terminalPrinter,
      TestLogPathFormatter testLogPathFormatter,
      boolean verboseSummary,
      boolean showAllTestCases,
      boolean showAllTestCasesDetailed) {
    print(
        summary,
        terminalPrinter,
        testLogPathFormatter,
        verboseSummary,
        showAllTestCases,
        showAllTestCasesDetailed,
        false,
        RepositoryMapping.ALWAYS_FALLBACK);
  }

  /**
   * Prints summary status for a single test.
   *
   * @param terminalPrinter The printer to print to
   */
  public static void print(
      TestSummary summary,
      AnsiTerminalPrinter terminalPrinter,
      TestLogPathFormatter testLogPathFormatter,
      boolean verboseSummary,
      boolean showAllTestCases,
      boolean showAllTestCasesDetailed,
      boolean withConfigurationName,
      RepositoryMapping mainRepoMapping) {
    BlazeTestStatus status = summary.getStatus();
    // Skip output for tests that failed to build.
    if ((!verboseSummary && status == BlazeTestStatus.FAILED_TO_BUILD)
        || status == BlazeTestStatus.BLAZE_HALTED_BEFORE_TESTING) {
      return;
    }
    String message = getCacheMessage(summary) + statusString(summary);
    String targetName = summary.getLabel().getDisplayForm(mainRepoMapping);
    if (withConfigurationName) {
      targetName += " (" + summary.getConfiguration().getMnemonic() + ")";
    }
    terminalPrinter.print(
        Strings.padEnd(targetName, 78 - message.length(), ' ')
            + " "
            + summary.getStatusMode()
            + message
            + Mode.DEFAULT
            + (verboseSummary ? getAttemptSummary(summary) + getTimeSummary(summary) : "")
            + "\n");

    if (showAllTestCases) {
      for (TestCase testCase : summary.getPassedTestCases()) {
        TestSummaryPrinter.printTestCase(terminalPrinter, testCase);
      }

      if (summary.getStatus() == BlazeTestStatus.FAILED) {
        if (summary.getFailedTestCasesStatus() == FailedTestCasesStatus.NOT_AVAILABLE) {
          terminalPrinter.print(
                  Mode.WARNING
                          + "    (individual test case information not available) "
                          + Mode.DEFAULT
                          + "\n");
        } else {
          for (TestCase testCase : summary.getFailedTestCases()) {
            if (testCase.getStatus() != TestCase.Status.PASSED) {
              TestSummaryPrinter.printTestCase(terminalPrinter, testCase);
            }
          }

          if (summary.getFailedTestCasesStatus() != FailedTestCasesStatus.FULL) {
            terminalPrinter.print(
                    Mode.WARNING
                            + "    (some shards did not report details, list of failed test"
                            + " cases incomplete)\n"
                            + Mode.DEFAULT);
          }
        }
      }
    } else if (showAllTestCasesDetailed) {
      List<TestCase> allTestCases = new ArrayList<>(summary.getPassedTestCases());
      allTestCases.addAll(summary.getFailedTestCases());

      Map<String, List<TestCase>> groupedTestCases = allTestCases.stream()
              .collect(Collectors.groupingBy(TestCase::getClassName));

      if (summary.getFailedTestCasesStatus() == FailedTestCasesStatus.NOT_AVAILABLE) {
        terminalPrinter.print(
                Mode.WARNING
                        + "    (individual test case information not available) "
                        + Mode.DEFAULT
                        + "\n");
      } else {
        for (Map.Entry<String, List<TestCase>> entry : groupedTestCases.entrySet()) {
          String className = entry.getKey();
          List<TestCase> testCases = entry.getValue();
          terminalPrinter.printLn(className);
          for (TestCase testCase : testCases) {
            TestSummaryPrinter.printTestCaseFormatted(terminalPrinter, testCase);
          }
        }
        if (summary.getFailedTestCasesStatus() != FailedTestCasesStatus.FULL) {
          terminalPrinter.print(
                  Mode.WARNING
                          + "    (some shards did not report details, list of failed test"
                          + " cases incomplete)\n"
                          + Mode.DEFAULT);
        }
      }
    } else {
      for (String warning : summary.getWarnings()) {
        terminalPrinter.print("  " + AnsiTerminalPrinter.Mode.WARNING + "WARNING: "
            + AnsiTerminalPrinter.Mode.DEFAULT + warning + "\n");
      }

      for (Path path : summary.getFailedLogs()) {
        if (path.exists()) {
          terminalPrinter.print("  " + testLogPathFormatter.getPathStringToPrint(path) + "\n");
        }
      }
    }
    for (Path path : summary.getCoverageFiles()) {
      // Print only non-trivial coverage files.
      try {
        if (path.exists() && path.getFileSize() > 0) {
          terminalPrinter.print("  " + testLogPathFormatter.getPathStringToPrint(path) + "\n");
        }
      } catch (IOException e) {
        LoggingUtil.logToRemote(Level.WARNING, "Error while reading coverage data file size",
            e);
      }
    }
  }

  /** Prints the result of an individual test case. */
  static void printTestCase(AnsiTerminalPrinter terminalPrinter, TestCase testCase) {
    String timeSummary = getTimeSummary(testCase);
    Mode mode = (testCase.getStatus() == Status.PASSED) ? Mode.INFO : Mode.ERROR;
    terminalPrinter.print(
      "    "
      + mode
      + Strings.padEnd(testCase.getStatus().toString(), 8, ' ')
      + Mode.DEFAULT
      + testCase.getClassName()
      + "."
      + testCase.getName()
      + timeSummary
      + "\n");
  }

  /** Prints the result of an individual test case in a formatted view. */
  static void printTestCaseFormatted(AnsiTerminalPrinter terminalPrinter, TestCase testCase) {
    String timeSummary = getTimeSummary(testCase);
    Mode mode = (testCase.getStatus() == Status.PASSED) ? Mode.INFO : Mode.ERROR;
    terminalPrinter.print(
      mode
      + ((mode == Mode.INFO) ? "  + " : "  - ")
      + Mode.DEFAULT
      + testCase.getName()
      + timeSummary
      + "\n");
  }

  private static String getTimeSummary(TestCase testCase) {
    if (testCase.hasRunDurationMillis()) {
      long runDurationMillis = testCase.getRunDurationMillis();
      String formattedTime = runDurationMillis < 100 ?
              runDurationMillis + "ms" :
              timeInSec(runDurationMillis, TimeUnit.MILLISECONDS);
      return " (" + formattedTime + ")";
    } else {
      return "";
    }
  }

  /**
   * Return the given time in seconds, to 1 decimal place,
   * i.e. "32.1s".
   */
  static String timeInSec(long time, TimeUnit unit) {
    double ms = TimeUnit.MILLISECONDS.convert(time, unit);
    return String.format(Locale.US, "%.1fs", ms / 1000.0);
  }

  static String getAttemptSummary(TestSummary summary) {
    int attempts = summary.getPassedLogs().size() + summary.getFailedLogs().size();
    if (attempts > 1) {
      // Print number of failed runs for failed tests if testing was completed.
      if (summary.getStatus() == BlazeTestStatus.FLAKY) {
        return ", failed in " + summary.getFailedLogs().size() + " out of " + attempts;
      }
      if (summary.getStatus() == BlazeTestStatus.TIMEOUT
          || summary.getStatus() == BlazeTestStatus.FAILED) {
        return " in " + summary.getFailedLogs().size() + " out of " + attempts;
      }
    }
    return "";
  }

  static String getCacheMessage(TestSummary summary) {
    if (summary.getNumCached() == 0
        || summary.getStatus() == BlazeTestStatus.INCOMPLETE
        || summary.getStatus() == BlazeTestStatus.NO_STATUS
        || summary.getStatus() == BlazeTestStatus.FAILED_TO_BUILD) {
      return ""; // either no caching, or information isn't useful
    } else if (summary.getNumCached() == summary.totalRuns()) {
      return "(cached) ";
    } else {
      return String.format(
          Locale.US, "(%d/%d cached) ", summary.getNumCached(), summary.totalRuns());
    }
  }

  static String getTimeSummary(TestSummary summary) {
    if (summary.getTestTimes().isEmpty()
        || summary.getStatus() == BlazeTestStatus.NO_STATUS
        || summary.getStatus() == BlazeTestStatus.FAILED_TO_BUILD) {
      return ""; // either no tests ran, or information isn't useful
    } else if (summary.getTestTimes().size() == 1) {
      return " in " + timeInSec(summary.getTestTimes().get(0), TimeUnit.MILLISECONDS);
    } else {
      // We previously used com.google.math for this, which added about 1 MB of deps to the total
      // size. If we re-introduce a dependency on that package, we could revert this change.
      long min = summary.getTestTimes().get(0).longValue();
      long max = min;
      long sum = 0;
      double sumOfSquares = 0.0;
      for (Long l : summary.getTestTimes()) {
        long value = l.longValue();
        min = Math.min(value, min);
        max = Math.max(value, max);
        sum += value;
        sumOfSquares += ((double) value) * (double) value;
      }
      double mean = ((double) sum) / summary.getTestTimes().size();
      double stddev = Math.sqrt((sumOfSquares - sum * mean) / summary.getTestTimes().size());
      // For sharded tests, we print the max time on the same line as
      // the test, and then print more detailed info about the
      // distribution of times on the next line.
      String maxTime = timeInSec(max, TimeUnit.MILLISECONDS);
      return String.format(
          Locale.US,
          " in %s\n  Stats over %d runs: max = %s, min = %s, avg = %s, dev = %s",
          maxTime,
          summary.getTestTimes().size(),
          maxTime,
          timeInSec(min, TimeUnit.MILLISECONDS),
          timeInSec((long) mean, TimeUnit.MILLISECONDS),
          timeInSec((long) stddev, TimeUnit.MILLISECONDS));
    }
  }
}
