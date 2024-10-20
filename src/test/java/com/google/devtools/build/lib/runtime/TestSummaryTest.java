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

package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.AdditionalMatchers.find;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.analysis.test.TestProvider.TestParams;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestStatus;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.util.io.AnsiTerminalPrinter;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestStatus.FailedTestCasesStatus;
import com.google.devtools.build.lib.view.test.TestStatus.TestCase;
import com.google.devtools.build.lib.view.test.TestStatus.TestCase.Status;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class TestSummaryTest {

  private static final String ANY_STRING = ".*?";
  private static final String PATH = "package";
  private static final String TARGET_NAME = "name";
  private ConfiguredTarget stubTarget;
  private static final ImmutableList<Long> SMALL_TIMING = ImmutableList.of(1L, 2L, 3L, 4L);

  private static final int CACHED = SMALL_TIMING.size();
  private static final int NOT_CACHED = 0;

  private FileSystem fs;
  private TestSummary.Builder basicBuilder;

  @Before
  public final void createFileSystem() throws Exception  {
    fs = new InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256);
    stubTarget = stubTarget();
    basicBuilder = getTemplateBuilder();
  }

  private TestSummary.Builder getTemplateBuilder() {
    BuildConfigurationValue configuration = Mockito.mock(BuildConfigurationValue.class);
    when(configuration.checksum()).thenReturn("abcdef");
    return TestSummary.newBuilder(stubTarget)
        .setConfiguration(configuration)
        .setStatus(BlazeTestStatus.PASSED)
        .setNumCached(NOT_CACHED)
        .setActionRan(true)
        .setRanRemotely(false)
        .setWasUnreportedWrongSize(false);
  }

  private List<Path> getPathList(String... names) {
    List<Path> list = new ArrayList<>();
    for (String name : names) {
      list.add(fs.getPath(name));
    }
    return list;
  }

  @Test
  public void testShouldProperlyTestLabels() throws Exception {
    ConfiguredTarget target = target("somepath", "MyTarget");
    String expectedString = ANY_STRING + "//somepath:MyTarget" + ANY_STRING;
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summaryStatus = createTestSummary(target, BlazeTestStatus.PASSED, CACHED);
    TestSummaryPrinter.print(summaryStatus, terminalPrinter, Path::getPathString, true, false, false);
    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testShouldPrintPassedStatus() throws Exception {
    String expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED + ANY_STRING;
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, NOT_CACHED);
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);

    verify(terminalPrinter).print(find(expectedString));
  }

  @Test
  public void testShouldPrintFailedStatus() throws Exception {
    String expectedString = ANY_STRING + "ERROR" + ANY_STRING + BlazeTestStatus.FAILED + ANY_STRING;
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = createTestSummary(stubTarget, BlazeTestStatus.FAILED, NOT_CACHED);

    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);

    terminalPrinter.print(find(expectedString));
  }

  private void assertShouldNotPrint(BlazeTestStatus status, boolean verboseSummary) {
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(
        createTestSummary(stubTarget, status, NOT_CACHED),
        terminalPrinter,
        Path::getPathString,
        verboseSummary,
        false,
            false);
    verify(terminalPrinter, never()).print(anyString());
  }

  @Test
  public void testShouldPrintFailedToBuildStatus() {
    String expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.FAILED_TO_BUILD;
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = createTestSummary(BlazeTestStatus.FAILED_TO_BUILD, NOT_CACHED);

    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);

    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testShouldNotPrintFailedToBuildStatus() {
    assertShouldNotPrint(BlazeTestStatus.FAILED_TO_BUILD, false);
  }

  @Test
  public void testShouldNotPrintHaltedStatus() {
    assertShouldNotPrint(BlazeTestStatus.BLAZE_HALTED_BEFORE_TESTING, true);
  }

  @Test
  public void testShouldPrintCachedStatus() throws Exception {
    String expectedString = ANY_STRING + "\\(cached" + ANY_STRING;
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, CACHED);

    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);

    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testPartialCachedStatus() throws Exception {
    String expectedString = ANY_STRING + "\\(3/4 cached" + ANY_STRING;
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, CACHED - 1);
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);
    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testIncompleteCached() throws Exception {
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummary summary = createTestSummary(stubTarget, BlazeTestStatus.INCOMPLETE, CACHED - 1);
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);
    verify(terminalPrinter).print(not(contains("cached")));
  }

  @Test
  public void testShouldPrintUncachedStatus() throws Exception {
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummary summary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, NOT_CACHED);
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);
    verify(terminalPrinter).print(not(contains("cached")));
  }

  @Test
  public void testNoTiming() throws Exception {
    String expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED;
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, NOT_CACHED);

    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);
    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testBuilder() throws Exception {
    // No need to copy if built twice in a row; no direct setters on the object.
    TestSummary summary = basicBuilder.build();
    TestSummary sameSummary = basicBuilder.build();
    assertThat(sameSummary).isSameInstanceAs(summary);

    basicBuilder.addTestTimes(ImmutableList.of(40L));

    TestSummary summaryCopy = basicBuilder.build();
    assertThat(summaryCopy.getTarget()).isEqualTo(summary.getTarget());
    assertThat(summaryCopy.getStatus()).isEqualTo(summary.getStatus());
    assertThat(summaryCopy.numCached()).isEqualTo(summary.numCached());
    assertThat(summaryCopy).isNotSameInstanceAs(summary);
    assertThat(summary.totalRuns()).isEqualTo(0);
    assertThat(summaryCopy.totalRuns()).isEqualTo(1);

    // Check that the builder can add a new warning to the copy,
    // despite the immutability of the original.
    basicBuilder.addTestTimes(ImmutableList.of(60L));

    TestSummary fiftyCached = basicBuilder.setNumCached(50).build();
    assertThat(fiftyCached.getStatus()).isEqualTo(summary.getStatus());
    assertThat(fiftyCached.numCached()).isEqualTo(50);
    assertThat(fiftyCached.totalRuns()).isEqualTo(2);

    TestSummary sixtyCached = basicBuilder.setNumCached(60).build();
    assertThat(sixtyCached.numCached()).isEqualTo(60);
    assertThat(fiftyCached.numCached()).isEqualTo(50);
  }

  @Test
  public void testAsStreamProto() throws Exception {
    TestParams testParams = mock(TestParams.class);
    when(testParams.getRuns()).thenReturn(2);
    when(testParams.getShards()).thenReturn(3);

    TestProvider testProvider = new TestProvider(testParams);
    when(stubTarget.getProvider(eq(TestProvider.class))).thenReturn(testProvider);

    PathConverter pathConverter = mock(PathConverter.class);
    when(pathConverter.apply(any(Path.class)))
        .thenAnswer(
            invocation -> "/path/to" + ((Path) invocation.getArguments()[0]).getPathString());

    BuildEventContext converters = mock(BuildEventContext.class);
    when(converters.pathConverter()).thenReturn(pathConverter);

    TestSummary summary =
        basicBuilder
            .setStatus(BlazeTestStatus.FAILED)
            .addPassedLogs(getPathList("/apple"))
            .addFailedLogs(getPathList("/pear"))
            .mergeTiming(1000, 300)
            .build();

    assertThat(summary.asStreamProto(converters).getTestSummary())
        .isEqualTo(
            BuildEventStreamProtos.TestSummary.newBuilder()
                .setOverallStatus(TestStatus.FAILED)
                .setFirstStartTimeMillis(1000)
                .setFirstStartTime(Timestamps.fromMillis(1000))
                .setLastStopTimeMillis(1300)
                .setLastStopTime(Timestamps.fromMillis(1300))
                .setTotalRunDurationMillis(300)
                .setTotalRunDuration(Durations.fromMillis(300))
                .setRunCount(2)
                .setShardCount(3)
                .addPassed(BuildEventStreamProtos.File.newBuilder().setUri("/path/to/apple"))
                .addFailed(BuildEventStreamProtos.File.newBuilder().setUri("/path/to/pear"))
                .build());
  }

  @Test
  public void testSingleTime() throws Exception {
    String expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED + ANY_STRING +
                            "in 3.4s";
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = basicBuilder.addTestTimes(ImmutableList.of(3412L)).build();
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);
    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testNoTime() throws Exception {
    // The last part matches anything not containing "in".
    String expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED + "(?!in)*";
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = basicBuilder.addTestTimes(ImmutableList.of(3412L)).build();
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, false, false, false);
    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testMultipleTimes() throws Exception {
    String expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED + ANY_STRING +
                            "\n  Stats over 3 runs: max = 3.0s, min = 1.0s, " +
                            "avg = 2.0s, dev = 0.8s";
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummary summary = basicBuilder
        .addTestTimes(ImmutableList.of(1000L, 2000L, 3000L))
        .build();
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);
    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testCoverageDataReferences() throws Exception {
    List<Path> paths = getPathList("/cov1.dat", "/cov2.dat", "/cov3.dat", "/cov4.dat");
    FileSystemUtils.writeContentAsLatin1(paths.get(1), "something");
    FileSystemUtils.writeContentAsLatin1(paths.get(3), "");
    FileSystemUtils.writeContentAsLatin1(paths.get(3), "something else");
    TestSummary summary = basicBuilder.addCoverageFiles(paths).build();

    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);
    verify(terminalPrinter).print(find(ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED));
    verify(terminalPrinter).print(find("  /cov2.dat"));
    verify(terminalPrinter).print(find("  /cov4.dat"));
  }

  @Test
  public void testFlakyAttempts() throws Exception {
    String expectedString = ANY_STRING + "WARNING" + ANY_STRING + BlazeTestStatus.FLAKY +
        ANY_STRING + ", failed in 2 out of 3";
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = basicBuilder
        .setStatus(BlazeTestStatus.FLAKY)
        .addPassedLogs(getPathList("/a"))
        .addFailedLogs(getPathList("/b", "/c"))
        .build();
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);
    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testNumberOfFailedRuns() throws Exception {
    String expectedString = ANY_STRING + "ERROR" + ANY_STRING + BlazeTestStatus.FAILED +
    ANY_STRING + "in 2 out of 3";
    AnsiTerminalPrinter terminalPrinter = Mockito.mock(AnsiTerminalPrinter.class);

    TestSummary summary = basicBuilder
        .setStatus(BlazeTestStatus.FAILED)
        .addPassedLogs(getPathList("/a"))
        .addFailedLogs(getPathList("/b", "/c"))
        .build();
    TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false, false);
    terminalPrinter.print(find(expectedString));
  }

  @Test
  public void testFileNamesNotShown() throws Exception {
    List<TestCase> emptyDetails = ImmutableList.of();
    TestSummary summary = basicBuilder
        .setStatus(BlazeTestStatus.FAILED)
        .addPassedLogs(getPathList("/apple"))
        .addFailedLogs(getPathList("/pear"))
        .addCoverageFiles(getPathList("/maracuja"))
        .addFailedTestCases(emptyDetails, FailedTestCasesStatus.FULL)
        .build();

    // Check that only //package:name is printed.
    AnsiTerminalPrinter printer = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true, false);
    verify(printer).print(contains("//package:name"));
  }

  @Test
  public void testMessageShownWhenTestCasesMissing() throws Exception {
    ImmutableList<TestCase> emptyList = ImmutableList.of();
    TestSummary summary = createTestSummaryWithDetails(
        BlazeTestStatus.FAILED, emptyList, FailedTestCasesStatus.NOT_AVAILABLE);

    AnsiTerminalPrinter printer = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true, false);
    verify(printer).print(contains("//package:name"));
    verify(printer).print(contains("not available"));
  }

  @Test
  public void testMessageShownForPartialResults() throws Exception {
    ImmutableList<TestCase> testCases =
        ImmutableList.of(newDetail("orange", TestCase.Status.FAILED, 1500L));
    TestSummary summary = createTestSummaryWithDetails(BlazeTestStatus.FAILED, testCases,
        FailedTestCasesStatus.PARTIAL);

    AnsiTerminalPrinter printer = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true, false);
    verify(printer).print(contains("//package:name"));
    verify(printer).print(find("FAILED.*orange"));
    verify(printer).print(contains("incomplete"));
  }

  private TestCase newDetail(String name,  TestCase.Status status, long duration) {
    return TestCase.newBuilder()
        .setName(name)
        .setStatus(status)
        .setRunDurationMillis(duration)
        .build();
  }

  @Test
  public void testTestCaseNamesShownWhenNeeded() throws Exception {
    TestCase detailPassed =
        newDetail("strawberry", TestCase.Status.PASSED, 1000L);
    TestCase detailFailed =
        newDetail("orange", TestCase.Status.FAILED, 1500L);

    TestSummary summaryPassed = createTestSummaryWithDetails(
        BlazeTestStatus.PASSED, Arrays.asList(detailPassed));

    TestSummary summaryFailed = createTestSummaryWithDetails(
        BlazeTestStatus.FAILED, Arrays.asList(detailPassed, detailFailed));
    assertThat(summaryFailed.getStatus()).isEqualTo(BlazeTestStatus.FAILED);

    AnsiTerminalPrinter printerPassed = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summaryPassed, printerPassed, Path::getPathString, true, true, false);
    verify(printerPassed).print(contains("//package:name"));

    AnsiTerminalPrinter printerFailed = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summaryFailed, printerFailed, Path::getPathString, true, true, false);
    verify(printerFailed).print(contains("//package:name"));
    verify(printerFailed).print(find("FAILED.*orange *\\(1\\.5"));
  }

  @Test
  public void testShowTestCaseNames() throws Exception {
    TestCase detailPassed = newDetail("strawberry", TestCase.Status.PASSED, 1000L);
    TestCase detailFailed = newDetail("orange", TestCase.Status.FAILED, 1500L);

    TestSummary summaryPassed =
        createPassedTestSummary(BlazeTestStatus.PASSED, Arrays.asList(detailPassed));

    TestSummary summaryFailed =
        createTestSummaryWithDetails(
            BlazeTestStatus.FAILED, Arrays.asList(detailPassed, detailFailed));
    assertThat(summaryFailed.getStatus()).isEqualTo(BlazeTestStatus.FAILED);

    AnsiTerminalPrinter printerPassed = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summaryPassed, printerPassed, Path::getPathString, true, true, false);
    verify(printerPassed).print(contains("//package:name"));
    verify(printerPassed).print(find("PASSED.*strawberry *\\(1\\.0"));

    AnsiTerminalPrinter printerFailed = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summaryFailed, printerFailed, Path::getPathString, true, true, false);
    verify(printerFailed).print(contains("//package:name"));
    verify(printerFailed).print(find("FAILED.*orange *\\(1\\.5"));
  }

  @Test
  public void testTestCaseNamesOrdered() throws Exception {
    TestCase[] details = {
      newDetail("apple", TestCase.Status.FAILED, 1000L),
      newDetail("banana", TestCase.Status.FAILED, 1000L),
      newDetail("cranberry", TestCase.Status.FAILED, 1000L)
    };

    // The exceedingly dumb approach: writing all the permutations down manually
    // is simply easier than any way of generating them.
    int[][] permutations = {
        { 0, 1, 2 },
        { 0, 2, 1 },
        { 1, 0, 2 },
        { 1, 2, 0 },
        { 2, 0, 1 },
        { 2, 1, 0 }
    };

    for (int[] permutation : permutations) {
      List<TestCase> permutatedDetails = new ArrayList<>();

      for (int element : permutation) {
        permutatedDetails.add(details[element]);
      }

      TestSummary summary = createTestSummaryWithDetails(BlazeTestStatus.FAILED, permutatedDetails);

      // A mock that checks the ordering of method calls
      AnsiTerminalPrinter printer = Mockito.mock(AnsiTerminalPrinter.class);
      TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true, false);
      InOrder order = Mockito.inOrder(printer);
      order.verify(printer).print(contains("//package:name"));
      order.verify(printer).print(find("FAILED.*apple"));
      order.verify(printer).print(find("FAILED.*banana"));
      order.verify(printer).print(find("FAILED.*cranberry"));
    }
  }

  @Test
  public void testCachedResultsFirstInSort() throws Exception {
    TestSummary summaryFailedCached = createTestSummary(BlazeTestStatus.FAILED, CACHED);
    TestSummary summaryFailedNotCached = createTestSummary(BlazeTestStatus.FAILED, NOT_CACHED);
    TestSummary summaryPassedCached = createTestSummary(BlazeTestStatus.PASSED, CACHED);
    TestSummary summaryPassedNotCached = createTestSummary(BlazeTestStatus.PASSED, NOT_CACHED);

    // This way we can make the test independent from the sort order of FAILEd
    // and PASSED.

    assertThat(summaryFailedCached.compareTo(summaryPassedNotCached)).isLessThan(0);
    assertThat(summaryPassedCached.compareTo(summaryFailedNotCached)).isLessThan(0);
  }

  @Test
  public void testCollectingFailedDetails() throws Exception {
    TestCase rootCase =
        TestCase.newBuilder()
            .setName("tests")
            .setRunDurationMillis(5000L)
            .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
            .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
            .build();

    TestSummary summary =
        getTemplateBuilder().collectTestCases(rootCase).setStatus(BlazeTestStatus.FAILED).build();

    AnsiTerminalPrinter printer = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true, false);
    verify(printer).print(contains("//package:name"));
    verify(printer).print(find("FAILED.*apple"));
    verify(printer).print(find("ERROR.*cherry"));
  }

  @Test
  public void testCollectingAllDetails() throws Exception {
    TestCase rootCase =
        TestCase.newBuilder()
            .setName("tests")
            .setRunDurationMillis(5000L)
            .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
            .addChild(newDetail("banana", TestCase.Status.PASSED, 1000L))
            .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
            .build();

    TestSummary summary =
        getTemplateBuilder().collectTestCases(rootCase).setStatus(BlazeTestStatus.FAILED).build();

    AnsiTerminalPrinter printer = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true, false);
    verify(printer).print(contains("//package:name"));
    verify(printer).print(find("FAILED.*apple"));
    verify(printer).print(find("PASSED.*banana"));
    verify(printer).print(find("ERROR.*cherry"));
  }

  @Test
  public void testCollectingPassedDetails() throws Exception {
    TestCase rootCase =
        TestCase.newBuilder()
            .setName("tests")
            .setRunDurationMillis(5000L)
            .addChild(newDetail("apple", TestCase.Status.PASSED, 1000L))
            .build();

    TestSummary summary =
        getTemplateBuilder().collectTestCases(rootCase).setStatus(BlazeTestStatus.PASSED).build();

    AnsiTerminalPrinter printer = Mockito.mock(AnsiTerminalPrinter.class);
    TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true, false);
    verify(printer).print(contains("//package:name"));
    verify(printer).print(find("PASSED.*apple"));
  }

  @Test
  public void countTotalTestCases() throws Exception {
    TestCase rootCase =
        TestCase.newBuilder()
            .setName("tests")
            .setRunDurationMillis(5000L)
            .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
            .addChild(newDetail("banana", TestCase.Status.PASSED, 1000L))
            .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
            .build();

    TestSummary summary =
        getTemplateBuilder().collectTestCases(rootCase).setStatus(BlazeTestStatus.FAILED).build();

    assertThat(summary.getTotalTestCases()).isEqualTo(3);
  }

  @Test
  public void countUnknownTestCases() throws Exception {
    TestSummary summary =
        getTemplateBuilder().collectTestCases(null).setStatus(BlazeTestStatus.FAILED).build();

    assertThat(summary.getTotalTestCases()).isEqualTo(1);
    assertThat(summary.getUnkownTestCases()).isEqualTo(1);
  }

  @Test
  public void countNotRunTestCases() throws Exception {
    TestCase a =
        TestCase.newBuilder()
            .addChild(
                TestCase.newBuilder().setName("A").setStatus(Status.PASSED).setRun(true).build())
            .addChild(
                TestCase.newBuilder().setName("B").setStatus(Status.PASSED).setRun(true).build())
            .addChild(
                TestCase.newBuilder().setName("C").setStatus(Status.PASSED).setRun(false).build())
            .build();
    TestSummary summary =
        getTemplateBuilder().collectTestCases(a).setStatus(BlazeTestStatus.FAILED).build();

    assertThat(summary.getTotalTestCases()).isEqualTo(2);
    assertThat(summary.getUnkownTestCases()).isEqualTo(0);
    assertThat(summary.getFailedTestCases()).isEmpty();
  }

  @Test
  public void countTotalTestCasesInNestedTree() throws Exception {
    TestCase aCase =
        TestCase.newBuilder()
            .setName("tests-1")
            .setRunDurationMillis(5000L)
            .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
            .addChild(newDetail("banana", TestCase.Status.PASSED, 1000L))
            .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
            .build();
    TestCase anotherCase =
        TestCase.newBuilder()
            .setName("tests-2")
            .setRunDurationMillis(5000L)
            .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
            .addChild(newDetail("banana", TestCase.Status.PASSED, 1000L))
            .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
            .build();

    TestCase rootCase =
        TestCase.newBuilder().setName("tests").addChild(aCase).addChild(anotherCase).build();

    TestSummary summary =
        getTemplateBuilder().collectTestCases(rootCase).setStatus(BlazeTestStatus.FAILED).build();

    assertThat(summary.getTotalTestCases()).isEqualTo(6);
  }

  private ConfiguredTarget target(String path, String targetName) throws Exception {
    ConfiguredTarget target = Mockito.mock(ConfiguredTarget.class);
    when(target.getLabel()).thenReturn(Label.create(path, targetName));
    when(target.getConfigurationChecksum()).thenReturn("abcdef");
    TestParams mockParams = Mockito.mock(TestParams.class);
    when(mockParams.getShards()).thenReturn(1);
    when(target.getProvider(TestProvider.class)).thenReturn(new TestProvider(mockParams));
    return target;
  }

  private ConfiguredTarget stubTarget() throws Exception {
    return target(PATH, TARGET_NAME);
  }

  private TestSummary createPassedTestSummary(BlazeTestStatus status, List<TestCase> details) {
    return getTemplateBuilder().setStatus(status).addPassedTestCases(details).build();
  }

  private TestSummary createTestSummaryWithDetails(BlazeTestStatus status,
      List<TestCase> details) {
    TestSummary summary = getTemplateBuilder()
        .setStatus(status)
        .addFailedTestCases(details, FailedTestCasesStatus.FULL)
        .build();
    return summary;
  }

  private TestSummary createTestSummaryWithDetails(
      BlazeTestStatus status, List<TestCase> testCaseList,
      FailedTestCasesStatus detailsStatus) {
    TestSummary summary = getTemplateBuilder()
        .setStatus(status)
        .addFailedTestCases(testCaseList, detailsStatus)
        .build();
    return summary;
  }

  private static TestSummary createTestSummary(ConfiguredTarget target, BlazeTestStatus status,
                                               int numCached) {
    ImmutableList<TestCase> emptyList = ImmutableList.of();
    return TestSummary.newBuilder(target)
        .setStatus(status)
        .setNumCached(numCached)
        .setActionRan(true)
        .setRanRemotely(false)
        .setWasUnreportedWrongSize(false)
        .addFailedTestCases(emptyList, FailedTestCasesStatus.FULL)
        .addTestTimes(SMALL_TIMING)
        .build();
  }

  private TestSummary createTestSummary(BlazeTestStatus status, int numCached) {
    TestSummary summary = getTemplateBuilder()
        .setStatus(status)
        .setNumCached(numCached)
        .addTestTimes(SMALL_TIMING)
        .build();
    return summary;
  }
}
