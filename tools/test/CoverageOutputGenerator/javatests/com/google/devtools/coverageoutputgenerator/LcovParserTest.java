// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.coverageoutputgenerator;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.coverageoutputgenerator.LcovMergerTestUtils.TRACEFILE1;
import static com.google.devtools.coverageoutputgenerator.LcovMergerTestUtils.TRACEFILE2;
import static com.google.devtools.coverageoutputgenerator.LcovMergerTestUtils.assertTracefile1;
import static com.google.devtools.coverageoutputgenerator.LcovMergerTestUtils.assertTracefile2;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LcovParser}. */
@RunWith(JUnit4.class)
public class LcovParserTest {

  @Test
  public void testParseInvalidTracefile() throws IOException {
    List<SourceFileCoverage> sourceFiles =
        LcovParser.parse(new ByteArrayInputStream("Invalid lcov tracefile".getBytes(UTF_8)));
    assertThat(sourceFiles).isEmpty();
  }

  @Test
  public void testParseTracefileWithOneSourcefile() throws IOException {
    List<SourceFileCoverage> sourceFiles =
        LcovParser.parse(
            new ByteArrayInputStream(Joiner.on("\n").join(TRACEFILE1).getBytes(UTF_8)));
    assertThat(sourceFiles).hasSize(1);
    assertTracefile1(sourceFiles.get(0));
  }

  @Test
  public void testParseTracefileWithTwoSourcefiles() throws IOException {
    List<String> tracefile2ModifiedLines = new ArrayList<>();
    tracefile2ModifiedLines.addAll(TRACEFILE2);
    tracefile2ModifiedLines.set(0, "SF:BSOME_OTHER_FILE_THAT_IS_NOT_MERGED");

    List<String> tracefileLines = new ArrayList<>();
    tracefileLines.addAll(TRACEFILE1);
    tracefileLines.addAll(tracefile2ModifiedLines);

    InputStream inputStream =
        new ByteArrayInputStream(Joiner.on("\n").join(tracefileLines).getBytes(UTF_8));
    List<SourceFileCoverage> sourceFiles = LcovParser.parse(inputStream);

    assertThat(sourceFiles).hasSize(2);
    assertTracefile1(sourceFiles.get(0));
    assertTracefile2(sourceFiles.get(1));
  }

  @Test
  public void testParseTracefileWithLargeCounts() throws IOException {
    List<String> tracefile =
        ImmutableList.of(
            "SF:SOURCE_FILENAME",
            "FN:4,file1-func1",
            "FNDA:1000000000000,file1-func1",
            "FNF:1",
            "FNH:1",
            "DA:4,1000000000000",
            "DA:5,1000000000000",
            "LH:2",
            "LF:2",
            "end_of_record");

    List<SourceFileCoverage> sourceFiles =
        LcovParser.parse(new ByteArrayInputStream(Joiner.on("\n").join(tracefile).getBytes(UTF_8)));
    SourceFileCoverage sourceFile = sourceFiles.get(0);

    Map<String, Long> functions = sourceFile.getFunctionsExecution();
    assertThat(functions).containsEntry("file1-func1", 1000000000000L);

    Map<Integer, LineCoverage> lines = sourceFile.getLines();
    assertThat(lines.get(4).executionCount()).isEqualTo(1000000000000L);
    assertThat(lines.get(5).executionCount()).isEqualTo(1000000000000L);
  }

  @Test
  public void testParseBrdaBranches() throws IOException {
    List<String> traceFile =
        ImmutableList.of(
            "SF:SOURCE_FILE",
            "FN:2,func",
            "FNDA:1,func",
            "DA:2,1",
            "DA:3,1",
            "DA:4,1",
            "DA:5,1",
            "DA:6,1",
            "BRDA:6,0,0,1",
            "BRDA:6,0,1,0",
            "DA:7,13",
            "BRDA:7,0,0,12",
            "BRDA:7,0,1,1",
            "DA:8,12",
            "DA:10,1",
            "DA:12,0",
            "BRDA:12,0,0,-",
            "BRDA:12,0,1,-",
            "DA:13,0",
            "DA:14.0",
            "DA:16,0",
            "end_of_record");
    List<SourceFileCoverage> sourceFiles =
        LcovParser.parse(new ByteArrayInputStream(Joiner.on("\n").join(traceFile).getBytes(UTF_8)));
    SourceFileCoverage sourceFile = sourceFiles.get(0);

    List<BranchCoverage> branches =
        sourceFile.getAllBranches().stream().collect(Collectors.toList());
    assertThat(branches)
        .containsExactly(
            BranchCoverage.createWithBlockAndBranch(6, "0", "0", true, 1),
            BranchCoverage.createWithBlockAndBranch(6, "0", "1", true, 0),
            BranchCoverage.createWithBlockAndBranch(7, "0", "0", true, 12),
            BranchCoverage.createWithBlockAndBranch(7, "0", "1", true, 1),
            BranchCoverage.createWithBlockAndBranch(12, "0", "0", false, 0),
            BranchCoverage.createWithBlockAndBranch(12, "0", "1", false, 0));
  }

  @Test
  public void testParseBaBranches() throws IOException {
    List<String> traceFile =
        ImmutableList.of(
            "SF:SOURCE_FILE",
            "FN:2,func",
            "FNDA:1,func",
            "DA:1,5",
            "BA:2,1",
            "BA:2,2",
            "DA:3,0",
            "BA:4,0",
            "BA:4,0",
            "DA:5,0",
            "DA:6,5",
            "BA:7,2",
            "BA:7,1",
            "BA:7,2",
            "DA:8,1",
            "DA:9,0",
            "DA:10,4",
            "end_of_record");
    List<SourceFileCoverage> sourceFiles =
        LcovParser.parse(new ByteArrayInputStream(Joiner.on("\n").join(traceFile).getBytes(UTF_8)));
    SourceFileCoverage sourceFile = sourceFiles.get(0);

    List<BranchCoverage> branches =
        sourceFile.getAllBranches().stream().collect(Collectors.toList());
    assertThat(branches)
        .containsExactly(
            BranchCoverage.create(2, 1),
            BranchCoverage.create(2, 2),
            BranchCoverage.create(4, 0),
            BranchCoverage.create(4, 0),
            BranchCoverage.create(7, 2),
            BranchCoverage.create(7, 1),
            BranchCoverage.create(7, 2));
  }

  @Test
  public void testParseFnWithEnd() throws IOException {
    List<String> traceFile = ImmutableList.of("SF:SOURCE_FILE", "FN:2,3,func", "end_of_record");
    List<SourceFileCoverage> sourceFiles =
        LcovParser.parse(new ByteArrayInputStream(Joiner.on("\n").join(traceFile).getBytes(UTF_8)));
    SourceFileCoverage sourceFile = sourceFiles.get(0);

    assertThat(sourceFile.getAllLineNumbers()).containsExactly(Map.entry("func", 2));
  }
}
