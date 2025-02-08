// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.buildtool;

// For debugging, uncomment these and the call to setupLogging() below.
//
// import com.google.devtools.build.lib.blaze.BlazeRuntime;
// import java.util.logging.Level;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.events.Event;
import java.io.IOException;
import java.util.Iterator;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** A test of the semantics of the keepGoing flag: continue as much as possible after an error. */
@RunWith(JUnit4.class)
public class KeepGoingTest extends BuildIntegrationTestCase {

  @Before
  public final void addOptions() {
    addOptions("--keep_going");
  }

  private static String genrule(String name, String deps, int succeeds) {
    String out = name + ".out";
    String cmd = (succeeds != 0) ? "cp $(location in) $(location " + out + ")" : "exit 42";
    return "genrule(name='"
        + name
        + "', "
        + "           srcs=['in',"
        + deps
        + "], "
        + "           outs=['"
        + out
        + "'], "
        + "           cmd='"
        + cmd
        + "')\n";
  }

  private static final int A = 0x01;
  private static final int B = 0x02;
  private static final int C = 0x04;
  private static final int D = 0x08;
  private static final int E = 0x10;

  private static final String[] labels = {
    "//keepgoing:A", "//keepgoing:B", "//keepgoing:C", "//keepgoing:D", "//keepgoing:E"
  };

  // "mask" is a bitmask of rules that succeed.
  private void writeFiles(int mask) throws IOException {
    // A --> B --> C
    // |     +---> D
    // |
    // +---> E
    write(
        "keepgoing/BUILD",
        genrule("A", "'B','E'", mask & A)
            + genrule("B", "'C','D'", mask & B)
            + genrule("C", "", mask & C)
            + genrule("D", "", mask & D)
            + genrule("E", "", mask & E));

    write("keepgoing/in", "(input)");
  }

  // "mask" is a bitmask of rules that succeed.
  private void assertBuilt(int mask) throws Exception {
    for (int ii = 0; ii < labels.length; ii++) {
      assertOneBuilt(labels[ii], (mask & (1 << ii)) != 0);
    }
  }

  private void assertOneBuilt(String label, boolean shouldBeBuilt) throws Exception {
    Iterable<Artifact> files = getArtifacts(label);
    for (Artifact file : files) {
      boolean isActuallyBuilt = file.getPath().exists();
      if (file.getPath().exists() != shouldBeBuilt) {
        fail(
            file.prettyPrint()
                + ": shouldBeBuilt="
                + shouldBeBuilt
                + ", isActuallyBuilt="
                + isActuallyBuilt);
      }
    }
  }

  private void assertNoMoreEvents(Iterator<Event> events) {
    boolean ok = true;
    while (events.hasNext()) {
      System.err.println(events.next());
      ok = false;
    }
    assertThat(ok).isTrue();
  }

  // Build //keepgoing:A, expecting failure.  (The BuildResult instance is
  // subsequently available via getRequest() for later assertions.)
  private void buildA() throws Exception {
    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//keepgoing:A"));
    assertThat(e).hasMessageThat().isNull();
  }

  /********************************************************************
   *                                                                  *
   *                         Actual tests...                          *
   *                                                                  *
   ********************************************************************/

  @Test
  public void testKeepGoingAfterCFails() throws Exception {
    // C fails due to error (logged).
    // B fails due to failed prereqs (logged).
    // A fails due to failed prereqs (logged).
    // D and E are built.
    // Then a BuildFailedException is thrown.
    writeFiles(A | B | D | E);
    buildA();

    Iterator<Event> errors = events.errors().iterator();
    assertThat(errors.next().getMessage())
        .containsMatch("Executing genrule //keepgoing:C failed: .*Exit 42.*");
    assertNoMoreEvents(errors);

    assertBuilt(D | E);
  }

  @Test
  public void testKeepGoingAfterDFails() throws Exception {
    // D fails due to error (logged).
    // B fails due to failed prereqs (logged).
    // A fails due to failed prereqs (logged).
    // C and E are built.
    // Then a BuildFailedException is thrown.
    writeFiles(A | B | C | E);
    buildA();

    Iterator<Event> errors = events.errors().iterator();
    assertThat(errors.next().getMessage())
        .containsMatch("Executing genrule //keepgoing:D failed: .*Exit 42.*");
    assertNoMoreEvents(errors);

    assertBuilt(C | E);
  }

  @Test
  public void testKeepGoingAfterCAndDFail() throws Exception {
    // C and D fail due to error (logged).
    // B fails due to failed prereqs (logged).
    // A fails due to failed prereqs (logged).
    // E is built.
    // Then a BuildFailedException is thrown.
    writeFiles(A | B | E);
    buildA();

    // C, D events are unordered:
    Iterator<Event> errors = events.errors().iterator();
    assertThat(errors.next().getMessage())
        .containsMatch("Executing genrule //keepgoing:(C|D) failed: .*Exit 42.*");
    assertThat(errors.next().getMessage())
        .containsMatch("Executing genrule //keepgoing:(C|D) failed: .*Exit 42.*");

    assertNoMoreEvents(errors);

    assertBuilt(E);
  }

  @Test
  public void testKeepGoingAfterEFails() throws Exception {
    // E fails due to error (logged).
    // A fails due to failed prereqs (logged).
    // B,C,D  are built.
    // Then a BuildFailedException is thrown.
    writeFiles(A | B | C | D);
    buildA();

    Iterator<Event> errors = events.errors().iterator();
    assertThat(errors.next().getMessage())
        .containsMatch("Executing genrule //keepgoing:E failed: .*Exit 42.*");
    assertNoMoreEvents(errors);

    assertBuilt(B | C | D);
  }

  // Regression test for b/8826301, incremental builder does not correctly set root actions.
  // Check that keep going works on second build. Note that this test failed non-deterministically
  // in b/8826301, because it depended on HashSet iteration order.
  @Test
  public void testKeepGoingOnSecondBuild() throws Exception {
    StringBuilder buildFile = new StringBuilder();
    buildFile
        .append("genrule(name='topgen', tools=[':badgen'], outs=['top.out'], ")
        .append("cmd='touch $@')\n")
        .append("genrule(name='badgen', executable=1, srcs=['badsrc.sh'], ")
        .append("outs=['bad.out'], cmd='bash $< >  $@', tools = [");
    // Make graph large so incremental dependency checker does graph culling, if enabled.
    for (int i = 0; i < 60; i++) {
      buildFile.append("':gen" + i + "', ");
    }
    buildFile.append("])\n");
    for (int i = 0; i < 60; i++) {
      buildFile
          .append("genrule(name='gen")
          .append(i)
          .append("', " + "outs=['gen")
          .append(i)
          .append(".out'], executable=1, cmd = 'echo \"#!/bin/true\" > $@')\n");
    }
    write("keepgoing/BUILD", buildFile.toString());
    write("keepgoing/badsrc.sh", "exit 0");
    buildTarget("//keepgoing:topgen");
    write("keepgoing/badsrc.sh", "exit 42");
    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//keepgoing:topgen"));
    assertThat(e).hasMessageThat().isNull();
    Iterator<Event> errors = events.errors().iterator();
    assertThat(errors.next().getMessage())
        .containsMatch("Executing genrule //keepgoing:badgen.* failed: .*Exit 42.*");
    assertNoMoreEvents(errors);
  }

  @Test
  public void testConfigurationErrorsAreToleratedWithKeepGoing() throws Exception {
    runtimeWrapper.addOptions("--experimental_builtins_injection_override=+cc_library");
    write("a/BUILD", "cc_library(name='a', srcs=['missing.foo'])");
    write("b/BUILD", "cc_library(name='b')");

    /**
     * Regression coverage for bug 1191396: "blaze build -k exits zero if execution succeeds, even
     * if there were analysis errors".
     */
    assertBuildFailedExceptionFromBuilding(
        "command succeeded, but not all targets were analyzed", "//a", "//b");
    events.assertContainsError(
        "in srcs attribute of cc_library rule @@//a:a: source file '@@//a:missing.foo' is misplaced"
            + " here");
    events.assertContainsInfo("Build succeeded for only 1 of 2 top-level targets");

    assertSameConfiguredTarget("//b:b");
  }

  @Test
  public void testKeepGoingAfterLoadingPhaseErrors() throws Exception {
    write("a/BUILD", "cc_library(name='a')");
    write("b/BUILD", "cc_library(name='b', deps = ['//missing:lib'])");

    assertBuildFailedExceptionFromBuilding(
        "command succeeded, but not all targets were analyzed", "//a", "//b"); //
    events.assertContainsError("no such package 'missing': BUILD file not found in any of the");

    assertSameConfiguredTarget("//a:a");
    events.assertContainsInfo(" succeeded for only 1 of ");
  }

  @Test
  public void testKeepGoingAfterTargetParsingErrors() throws Exception {
    write("a/BUILD", "cc_library(name='a', xyz)");
    write("b/BUILD", "cc_library(name='b', xyz)");
    write("b/b1/BUILD", "cc_library(name='b1')");
    write("b/b2/BUILD", "cc_library(name='b2', xyz)");

    assertBuildFailedExceptionFromBuilding(
        "command succeeded, but there were errors parsing the target pattern", "b/...", "//a");
    events.assertContainsWarning("Target pattern parsing failed.");

    assertSameConfiguredTarget("//b/b1");
  }

  @Test
  public void testKeepGoingAfterSchedulingDependencyFailure() throws Exception {
    write("foo/foo.cc", "int main() { return 0; }");
    write(
        "foo/BUILD",
        """
        cc_binary(
            name = "foo",
            srcs = [
                "foo.cc",
                "gen.h",
            ],
            malloc = "system_malloc",
        )

        cc_library(
            name = "system_malloc",
            linkstatic = 1,
        )

        genrule(
            name = "gen",
            srcs = [],
            outs = ["gen.h"],
            cmd = "exit 1",
        )
        """);

    assertThrows(BuildFailedException.class, () -> buildTarget("//foo:foo"));
  }

  private void assertSameConfiguredTarget(String label) throws Exception {
    assertThat(getOnlyElement(getResult().getSuccessfulTargets()))
        .isSameInstanceAs(getConfiguredTarget(label));
  }

  @Test
  public void testKeepGoingAfterAnalysisFailure() throws Exception {
    write(
        "analysiserror/failer.bzl",
        """
        def _failer_impl(ctx):
            fail("BOOM!")

        failer = rule(implementation = _failer_impl)
        """);
    write(
        "analysiserror/BUILD",
        """
        load(":failer.bzl", "failer")

        genrule(
            name = "gen",
            srcs = [],
            outs = ["gen.h"],
            cmd = "exit 1",
        )

        # The next line has an analysis failure: the xmb_lint rule is devoid of xmb files.
        failer(name = "foo")

        cc_library(name = "bar")
        """);

    assertBuildFailedExceptionFromBuilding(
        "command succeeded, but not all targets were analyzed",
        "//analysiserror:foo",
        "//analysiserror:bar");
    events.assertContainsError("Error in fail: BOOM!");

    assertSameConfiguredTarget("//analysiserror:bar");
  }

  private void assertBuildFailedExceptionFromBuilding(String msg, String... targets) {
    BuildFailedException e = assertThrows(BuildFailedException.class, () -> buildTarget(targets));
    assertThat(e).hasMessageThat().isEqualTo(msg);
    assertThat(getResult().getSuccess()).isFalse();
  }
}
