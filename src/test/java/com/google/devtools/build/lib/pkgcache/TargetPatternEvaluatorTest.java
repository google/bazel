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
package com.google.devtools.build.lib.pkgcache;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.util.Arrays;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TargetPatternPreloader}. */
@RunWith(JUnit4.class)
public class TargetPatternEvaluatorTest extends AbstractTargetPatternEvaluatorTest {
  private PathFragment fooOffset;

  private Set<Label> rulesBeneathFoo;
  private Set<Label> rulesInFoo;
  private Set<Label> targetsInFoo;
  private Set<Label> targetsInFooBar;
  private Set<Label> targetsBeneathFoo;
  private Set<Label> targetsInOtherrules;

  @Before
  public final void createFiles() throws Exception {
    // TODO(ulfjack): Also disable the implicit C++ outputs in Google's internal version.
    boolean hasImplicitCcOutputs =
        ruleClassProvider.getRuleClassMap().get("cc_library").getDefaultImplicitOutputsFunction()
            != SafeImplicitOutputsFunction.NONE;

    scratch.file("BUILD", "filegroup(name = 'fg', srcs = glob(['*.cc']))");
    scratch.file("foo.cc");

    scratch.file(
        "foo/BUILD",
        """
        cc_library(
            name = "foo1",
            srcs = ["foo1.cc"],
            hdrs = ["foo1.h"],
        )

        exports_files(["baz/bang"])
        """);
    scratch.file(
        "foo/bar/BUILD",
        """
        cc_library(
            name = "bar1",
            alwayslink = 1,
        )

        cc_library(name = "bar2")

        exports_files([
            "wiz/bang",
            "wiz/all",
            "baz",
            "baz/bang",
            "undeclared.h",
        ])
        """);

    // 'filegroup' and 'test_suite' are rules, but 'exports_files' is not.
    scratch.file(
        "otherrules/BUILD",
        """
        test_suite(name = "suite1")

        filegroup(
            name = "group",
            srcs = ["suite/somefile"],
        )

        exports_files(["suite/somefile"])

        cc_library(
            name = "wiz",
            linkstatic = 1,
        )
        """);
    scratch.file("nosuchpkg/subdir/empty", "");

    Path foo = scratch.dir("foo");
    fooOffset = foo.relativeTo(rootDirectory);

    rulesBeneathFoo = labels("//foo:foo1", "//foo/bar:bar1", "//foo/bar:bar2");
    rulesInFoo = labels("//foo:foo1");

    targetsInFoo =
        labels(
            "//foo:foo1",
            "//foo:foo1",
            "//foo:foo1.cc",
            "//foo:foo1.h",
            "//foo:BUILD",
            "//foo:baz/bang");
    if (hasImplicitCcOutputs) {
      targetsInFoo.addAll(labels("//foo:libfoo1.a", "//foo:libfoo1.so"));
    }
    targetsInFooBar =
        labels(
            "//foo/bar:bar1",
            "//foo/bar:bar2",
            "//foo/bar:BUILD",
            "//foo/bar:wiz/bang",
            "//foo/bar:wiz/all",
            "//foo/bar:baz",
            "//foo/bar:baz/bang",
            "//foo/bar:undeclared.h");
    if (hasImplicitCcOutputs) {
      targetsInFooBar.addAll(labels("//foo/bar:libbar1.lo", "//foo/bar:libbar2.a"));
    }
    targetsBeneathFoo = Sets.newHashSet();
    targetsBeneathFoo.addAll(targetsInFoo);
    targetsBeneathFoo.addAll(targetsInFooBar);

    targetsInOtherrules =
        labels(
            "//otherrules:group",
            "//otherrules:wiz",
            "//otherrules:suite1",
            "//otherrules:BUILD",
            "//otherrules:suite/somefile",
            "//otherrules:wiz",
            "//otherrules:suite1");
    if (hasImplicitCcOutputs) {
      targetsInOtherrules.addAll(labels("//otherrules:libwiz.a"));
    }
  }

  private void invalidate(String file) throws InterruptedException, AbruptExitException {
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter,
        ModifiedFileSet.builder().modify(PathFragment.create(file)).build(),
        Root.fromPath(rootDirectory));
  }

  private void invalidate(ModifiedFileSet modifiedFileSet)
      throws InterruptedException, AbruptExitException {
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, modifiedFileSet, Root.fromPath(rootDirectory));
  }

  private void setDeletedPackages(Set<PackageIdentifier> deletedPackages) {
    skyframeExecutor.setDeletedPackages(deletedPackages);
  }

  private Set<Label> parseList(String... patterns)
      throws TargetParsingException, InterruptedException {
    return targetsToLabels(
        getFailFast(
            parseTargetPatternList(parser, parsingListener, Arrays.asList(patterns), false)));
  }

  private Set<Label> parseListKeepGoingExpectFailure(String... patterns)
      throws TargetParsingException, InterruptedException {
    ResolvedTargets<Target> result =
        parseTargetPatternList(parser, parsingListener, Arrays.asList(patterns), true);
    return targetsToLabels(result.getTargets());
  }

  private Set<Label> parseListRelative(String... patterns)
      throws TargetParsingException, InterruptedException {
    return targetsToLabels(
        getFailFast(
            parseTargetPatternList(
                fooOffset, parser, parsingListener, Arrays.asList(patterns), false)));
  }

  private static Set<Target> getFailFast(ResolvedTargets<Target> result) {
    assertThat(result.hasError()).isFalse();
    return result.getTargets();
  }

  private void expectError(
      PathFragment offset, TargetPatternPreloader parser, String expectedError, String target)
      throws InterruptedException {
    TargetParsingException e =
        assertThrows(
            "target='" + target + "', expected error: " + expectedError,
            TargetParsingException.class,
            () ->
                parseTargetPatternList(
                    offset, parser, parsingListener, ImmutableList.of(target), false));
    assertThat(e).hasMessageThat().contains(expectedError);
  }

  private void expectError(String expectedError, String target) throws InterruptedException {
    expectError(PathFragment.EMPTY_FRAGMENT, parser, expectedError, target);
  }

  private Label parseIndividualTarget(String targetLabel) throws Exception {
    return Iterables.getOnlyElement(
            getFailFast(
                parseTargetPatternList(
                    parser, parsingListener, ImmutableList.of(targetLabel), false)))
        .getLabel();
  }

  @Test
  public void testModifiedBuildFile() throws Exception {
    assertThat(parseList("foo:all")).containsExactlyElementsIn(rulesInFoo);
    assertNoEvents();

    scratch.overwriteFile(
        "foo/BUILD",
        """
        cc_library(
            name = "foo1",
            srcs = ["foo1.cc"],
            hdrs = ["foo1.h"],
        )

        cc_library(
            name = "foo2",
            srcs = ["foo1.cc"],
            hdrs = ["foo1.h"],
        )
        """);
    invalidate("foo/BUILD");
    assertThat(parseList("foo:all")).containsExactlyElementsIn(labels("//foo:foo1", "//foo:foo2"));
  }

  /**
   * Test that the relative path label parsing behaves as stated in the target-syntax documentation.
   */
  @Test
  public void testRelativePathLabel() throws Exception {
    scratch.file("sub/BUILD", "exports_files(['dir2/dir2'])");
    scratch.file("sub/dir/BUILD", "exports_files(['dir2'])");
    scratch.file("sub/dir/dir/BUILD", "exports_files(['dir'])");
    // sub/dir/dir is a package
    assertThat(parseIndividualTarget("sub/dir/dir").toString()).isEqualTo("//sub/dir/dir:dir");
    // sub/dir is a package but not sub/dir/dir2
    assertThat(parseIndividualTarget("sub/dir/dir2").toString()).isEqualTo("//sub/dir:dir2");
    // sub is a package but not sub/dir2
    assertThat(parseIndividualTarget("sub/dir2/dir2").toString()).isEqualTo("//sub:dir2/dir2");
  }

  /** Regression test for a bug. */
  @Test
  public void testDotDotDotDoesntMatchDeletedPackages() throws Exception {
    scratch.file("x/y/BUILD", "cc_library(name='y')");
    scratch.file("x/z/BUILD", "cc_library(name='z')");
    setDeletedPackages(Sets.newHashSet(PackageIdentifier.createInMainRepo("x/y")));
    assertThat(parseList("x/...")).isEqualTo(Sets.newHashSet(Label.parseCanonical("//x/z")));
  }

  @Test
  public void testDotDotDotDoesntMatchDeletedPackagesRelative() throws Exception {
    scratch.file("x/y/BUILD", "cc_library(name='y')");
    scratch.file("x/z/BUILD", "cc_library(name='z')");
    setDeletedPackages(Sets.newHashSet(PackageIdentifier.createInMainRepo("x/y")));

    assertThat(
            targetsToLabels(
                getFailFast(
                    parseTargetPatternList(
                        PathFragment.create("x"),
                        parser,
                        parsingListener,
                        ImmutableList.of("..."),
                        false))))
        .isEqualTo(Sets.newHashSet(Label.parseCanonical("//x/z")));
  }

  @Test
  public void testDeletedPackagesIncrementality() throws Exception {
    scratch.file("x/y/BUILD", "cc_library(name='y')");
    scratch.file("x/z/BUILD", "cc_library(name='z')");

    assertThat(parseList("x/..."))
        .containsExactly(Label.parseCanonical("//x/y"), Label.parseCanonical("//x/z"));

    setDeletedPackages(Sets.newHashSet(PackageIdentifier.createInMainRepo("x/y")));
    assertThat(parseList("x/...")).containsExactly(Label.parseCanonical("//x/z"));

    setDeletedPackages(ImmutableSet.<PackageIdentifier>of());
    assertThat(parseList("x/..."))
        .containsExactly(Label.parseCanonical("//x/y"), Label.parseCanonical("//x/z"));
  }

  @Test
  public void testSequenceOfTargetPatterns_union() throws Exception {
    // No prefix negation operator => union.  Order is not significant.
    assertThat(parseList("foo/...", "foo/bar/...")).containsExactlyElementsIn(rulesBeneathFoo);
    assertThat(parseList("foo/bar/...", "foo/...")).containsExactlyElementsIn(rulesBeneathFoo);
  }

  @Test
  public void testSequenceOfTargetPatterns_setDifference() throws Exception {
    // Prefix negation operator => set difference.  Order is significant.
    assertThat(parseList("foo/...", "-foo/bar/...")).containsExactlyElementsIn(rulesInFoo);
    assertThat(parseList("-foo/bar/...", "foo/...")).containsExactlyElementsIn(rulesBeneathFoo);
  }

  @Test
  public void testSequenceOfTargetPatterns_setDifferenceRelative() throws Exception {
    // Prefix negation operator => set difference.  Order is significant.
    assertThat(parseListRelative("...", "-bar/...")).containsExactlyElementsIn(rulesInFoo);
    assertThat(parseListRelative("-bar/...", "...")).containsExactlyElementsIn(rulesBeneathFoo);
  }

  /** Regression test for bug: "Bogus 'helpful' error message" */
  @Test
  public void testHelpfulMessageForDirectoryWhichIsASubdirectoryOfAPackage() throws Exception {
    scratch.file("bar/BUILD");
    scratch.file("bar/quux/somefile");
    expectError(
        "no such target '//bar:quux': target 'quux' not declared in package 'bar' defined by "
            + "/workspace/bar/BUILD; however, a source directory of this name exists.  (Perhaps "
            + "add 'exports_files([\"quux\"])' to bar/BUILD, or define a filegroup?)",
        "bar/quux");
  }

  @Test
  public void testKeepGoingPartiallyBadPackage() throws Exception {
    scratch.file(
        "x/y/BUILD",
        """
        filegroup(name = "a")

        # dynamic error
        x = 1 // 0

        filegroup(name = "b")
        """);

    reporter.removeHandler(failFastHandler);
    Pair<Set<Label>, Boolean> result = parseListKeepGoing("//x/...");

    assertContainsEvent("division by zero");
    // Execution stops at the first error,
    // Subsequent rule statements are not executed,
    // But thanks to --keep_going, we learn about the ones before the error.
    assertThat(result.first).containsExactly(Label.parseCanonical("//x/y:a"));
    assertThat(result.second).isFalse();
  }

  @Test
  public void testKeepGoingMissingRecursiveDirectory() throws Exception {
    assertKeepGoing(
        rulesBeneathFoo,
        "Skipping 'nosuchpkg/...': no targets found beneath 'nosuchpkg'",
        "nosuchpkg/...",
        "foo/...");
    eventCollector.clear();
    assertKeepGoing(
        rulesBeneathFoo,
        "Skipping 'nosuchdirectory/...': no targets found beneath 'nosuchdirectory'",
        "nosuchdirectory/...",
        "foo/...");
  }

  @Test
  public void testKeepGoingMissingTarget() throws Exception {
    assertKeepGoing(
        rulesBeneathFoo,
        "Skipping '//otherrules:missing_target': no such target "
            + "'//otherrules:missing_target': target 'missing_target' not declared in "
            + "package 'otherrules'",
        "//otherrules:missing_target",
        "foo/...");
  }

  @Test
  public void testKeepGoingOnAllRulesBeneath() throws Exception {
    scratch.file("foo/bar/bad/BUILD", "invalid build file");

    reporter.removeHandler(failFastHandler);
    Pair<Set<Label>, Boolean> result = parseListKeepGoing("foo/...");
    assertThat(result.first).containsExactlyElementsIn(rulesBeneathFoo);
    assertContainsEvent("syntax error at 'build'");

    reporter.addHandler(failFastHandler);

    // Even though there was a loading error in the package, parsing the target pattern was
    // successful.
    assertThat(result.second).isFalse();
  }

  @Test
  public void noKeepGoingOnAllRulesBeneath() throws Exception {
    scratch.file("foo/bar/bad/BUILD", "invalid build file");

    reporter.removeHandler(failFastHandler);
    TargetParsingException e =
        assertThrows(TargetParsingException.class, () -> parseList("foo/..."));
    assertThat(e.getDetailedExitCode())
        .isEqualTo(
            DetailedExitCode.of(
                FailureDetails.FailureDetail.newBuilder()
                    .setMessage(
                        "Error evaluating 'foo/...': error loading package 'foo/bar/bad': Package"
                            + " 'foo/bar/bad' contains errors")
                    .setTargetPatterns(
                        FailureDetails.TargetPatterns.newBuilder()
                            .setCode(FailureDetails.TargetPatterns.Code.PACKAGE_NOT_FOUND))
                    .build()));
  }

  @Test
  public void noKeepGoingOnAllRulesBeneathForMultipleBrokenPackages() throws Exception {
    // This test uses two broken packages beneath the "foo/..." target pattern because the Skyframe
    // state for each package will be different during Skyframe error bubbling. Specifically, the
    // first broken package encountered during standard Skyframe evaluation will have an associated
    // PackageError node, but any subsequent broken package won't. This may affect error bubbling
    // behavior (and did, in b/211901614).
    scratch.file("foo/bar/bad1/BUILD", "invalid build file");
    scratch.file("foo/bar/bad2/BUILD", "invalid build file");

    reporter.removeHandler(failFastHandler);
    TargetParsingException e =
        assertThrows(TargetParsingException.class, () -> parseList("foo/..."));
    assertThat(e.getDetailedExitCode())
        .isIn(
            ImmutableList.of(1, 2).stream()
                .map(
                    i ->
                        DetailedExitCode.of(
                            FailureDetails.FailureDetail.newBuilder()
                                .setMessage(
                                    String.format(
                                        "Error evaluating 'foo/...': error loading package"
                                            + " 'foo/bar/bad%d': Package 'foo/bar/bad%d' contains"
                                            + " errors",
                                        i, i))
                                .setTargetPatterns(
                                    FailureDetails.TargetPatterns.newBuilder()
                                        .setCode(
                                            FailureDetails.TargetPatterns.Code.PACKAGE_NOT_FOUND))
                                .build()))
                .collect(toImmutableList()));
  }

  @Test
  public void testKeepGoingBadFilenameTarget() throws Exception {
    assertKeepGoing(
        rulesBeneathFoo,
        "no such target '//:bad/filename/target'",
        "bad/filename/target",
        "foo/...");
  }

  @Test
  public void testMoreThanOneBadPatternFailFast() throws Exception {
    TargetParsingException e =
        assertThrows(
            TargetParsingException.class,
            () ->
                parseTargetPatternList(
                    parser,
                    parsingListener,
                    ImmutableList.of("bad/filename/target", "other/bad/filename/target"),
                    /*keepGoing=*/ false));
    assertThat(e).hasMessageThat().contains("no such target");
  }

  @Test
  public void testMentioningBuildFile() throws Exception {
    ResolvedTargets<Target> result =
        parseTargetPatternList(parser, parsingListener, Arrays.asList("foo/bar/BUILD"), false);

    assertThat(result.hasError()).isFalse();
    assertThat(result.getTargets()).hasSize(1);

    Label label = Iterables.getOnlyElement(result.getTargets()).getLabel();
    assertThat(label.getName()).isEqualTo("BUILD");
    assertThat(label.getPackageName()).isEqualTo("foo/bar");
  }

  /**
   * Regression test for bug: '"Target pattern parsing failed. Continuing anyway" appears, even
   * without --keep_going'
   */
  @Test
  public void testLoadingErrorsAreNotParsingErrors() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "loading/BUILD",
        """
        cc_library(
            name = "y",
            deps = ["a"],
        )

        cc_library(
            name = "a",
            deps = ["b"],
        )

        cc_library(
            name = "b",
            deps = ["c"],
        )

        genrule(
            name = "c",
            cmd = "",
        )
        """);

    Pair<Set<Label>, Boolean> result = parseListKeepGoing("//loading:y");
    assertThat(result.first).containsExactly(Label.parseCanonical("//loading:y"));
    assertContainsEvent("missing value for mandatory attribute");
    assertThat(result.second).isFalse();
  }

  private void assertKeepGoing(Set<Label> expectedLabels, String expectedEvent, String... toParse)
      throws Exception {
    reporter.removeHandler(failFastHandler);
    assertThat(parseListKeepGoingExpectFailure(toParse)).containsExactlyElementsIn(expectedLabels);
    assertContainsEvent(expectedEvent);
    reporter.addHandler(failFastHandler);
  }

  @Test
  public void testAddedPkg() throws Exception {
    invalidate(ModifiedFileSet.EVERYTHING_MODIFIED);
    scratch.dir("h/i/j/k/BUILD");
    scratch.file("h/BUILD", "filegroup(name='h')");
    assertThat(parseList("//h/...")).containsExactlyElementsIn(labels("//h"));

    scratch.file("h/i/j/BUILD", "filegroup(name='j')");

    // Modifications not yet known.
    assertThat(parseList("//h/...")).containsExactlyElementsIn(labels("//h"));

    ModifiedFileSet modifiedFileSet =
        ModifiedFileSet.builder().modify(PathFragment.create("h/i/j/BUILD")).build();
    invalidate(modifiedFileSet);

    assertThat(parseList("//h/..."))
        .containsExactly(Label.parseCanonical("//h/i/j:j"), Label.parseCanonical("//h"));
  }

  @Test
  public void testAddedFilesAndDotDotDot() throws Exception {
    invalidate(ModifiedFileSet.EVERYTHING_MODIFIED);
    reporter.removeHandler(failFastHandler);
    scratch.dir("h");
    assertThrows(TargetParsingException.class, () -> parseList("//h/..."));

    scratch.file("h/i/j/k/BUILD", "filegroup(name='l')");
    ModifiedFileSet modifiedFileSet =
        ModifiedFileSet.builder()
            .modify(PathFragment.create("h"))
            .modify(PathFragment.create("h/i"))
            .modify(PathFragment.create("h/i/j"))
            .modify(PathFragment.create("h/i/j/k"))
            .modify(PathFragment.create("h/i/j/k/BUILD"))
            .build();
    invalidate(modifiedFileSet);
    reporter.addHandler(failFastHandler);
    Set<Label> nonEmptyResult = parseList("//h/...");
    assertThat(nonEmptyResult).containsExactly(Label.parseCanonical("//h/i/j/k:l"));
  }

  @Test
  public void testBrokenSymlinkRepaired() throws Exception {
    reporter.removeHandler(failFastHandler);
    Path tuv = scratch.dir("t/u/v");
    tuv.getChild("BUILD").createSymbolicLink(PathFragment.create("../../BUILD"));

    assertThrows(TargetParsingException.class, () -> parseList("//t/..."));

    scratch.file("t/BUILD", "filegroup(name='t')");
    ModifiedFileSet modifiedFileSet =
        ModifiedFileSet.builder().modify(PathFragment.create("t/BUILD")).build();

    invalidate(modifiedFileSet);
    reporter.addHandler(failFastHandler);
    Set<Label> result = parseList("//t/...");

    assertThat(result)
        .containsExactly(Label.parseCanonical("//t:t"), Label.parseCanonical("//t/u/v:t"));
  }

  @Test
  public void testInfiniteTreeFromSymlinks() throws Exception {
    reporter.removeHandler(failFastHandler);
    Path ab = scratch.dir("a/b");
    ab.getChild("c").createSymbolicLink(PathFragment.create("../b"));
    scratch.file("a/b/BUILD", "filegroup(name='g')");
    ResolvedTargets<Target> result =
        parseTargetPatternList(parser, parsingListener, ImmutableList.of("//a/b/..."), true);
    assertThat(targetsToLabels(result.getTargets()))
        .containsExactly(Label.parseCanonical("//a/b:g"));
  }

  @Test
  public void testSymlinkCycle() throws Exception {
    reporter.removeHandler(failFastHandler);
    Path ab = scratch.dir("a/b");
    ab.getChild("c").createSymbolicLink(PathFragment.create("c"));
    scratch.file("a/b/BUILD", "filegroup(name='g')");
    ResolvedTargets<Target> result =
        parseTargetPatternList(parser, parsingListener, ImmutableList.of("//a/b/..."), true);
    assertThat(targetsToLabels(result.getTargets())).contains(Label.parseCanonical("//a/b:g"));
  }

  @Test
  public void testPerDirectorySymlinkTraversalOptOut() throws Exception {
    scratch.dir("from-b");
    scratch.file("from-b/BUILD", "filegroup(name = 'from-b')");
    scratch.dir("from-c");
    scratch.file("from-c/BUILD", "filegroup(name = 'from-c')");
    Path ab = scratch.dir("a/b");
    ab.getChild("symlink").createSymbolicLink(PathFragment.create("../../from-b"));
    scratch.dir("a/b/not-a-symlink");
    scratch.file("a/b/not-a-symlink/BUILD", "filegroup(name = 'not-a-symlink')");
    scratch.file(
        "a/b/DONT_FOLLOW_SYMLINKS_WHEN_TRAVERSING_THIS_DIRECTORY_VIA_A_RECURSIVE_TARGET_PATTERN");
    Path ac = scratch.dir("a/c");
    ac.getChild("symlink").createSymbolicLink(PathFragment.create("../../from-c"));
    ResolvedTargets<Target> result =
        parseTargetPatternList(parser, parsingListener, ImmutableList.of("//a/..."), true);
    assertThat(targetsToLabels(result.getTargets()))
        .containsExactly(
            Label.parseCanonical("//a/c/symlink:from-c"),
            Label.parseCanonical("//a/b/not-a-symlink:not-a-symlink"));
  }

  @Test
  public void testDoesNotRecurseIntoSymlinksToOutputBase() throws Exception {
    Path outputBaseBuildFile = outputBase.getRelative("execroot/workspace/test/BUILD");
    scratch.file(outputBaseBuildFile.getPathString(), "filegroup(name='c')");
    PathFragment targetFragment = outputBase.asFragment().getRelative("execroot/workspace/test");
    Path d = scratch.dir("d");
    d.getChild("c").createSymbolicLink(targetFragment);
    rootDirectory.getChild("convenience").createSymbolicLink(targetFragment);
    Set<Label> result = parseList("//...");
    assertThat(result).doesNotContain(Label.parseCanonical("//convenience:c"));
    assertThat(result).doesNotContain(Label.parseCanonical("//d/c:c"));
  }

  @Test
  public void testTopLevelPackage_relative_buildFile() throws Exception {
    Set<Label> result = parseList("BUILD");
    assertThat(result).containsExactly(Label.parseCanonical("//:BUILD"));
  }

  @Test
  public void testTopLevelPackage_relative_declaredTarget() throws Exception {
    Set<Label> result = parseList("fg");
    assertThat(result).containsExactly(Label.parseCanonical("//:fg"));
  }

  @Test
  public void testTopLevelPackage_relative_all() throws Exception {
    expectError("no such target '//:all'", "all");
  }

  @Test
  public void testTopLevelPackage_relative_colonAll() throws Exception {
    Set<Label> result = parseList(":all");
    assertThat(result).containsExactly(Label.parseCanonical("//:fg"));
  }

  @Test
  public void testTopLevelPackage_relative_inputFile() throws Exception {
    Set<Label> result = parseList("foo.cc");
    assertThat(result).containsExactly(Label.parseCanonical("//:foo.cc"));
  }

  @Test
  public void testTopLevelPackage_relative_inputFile_noSuchInputFile() throws Exception {
    expectError("no such target '//:nope.cc'", "nope.cc");
  }

  @Test
  public void testTopLevelPackage_absolute_buildFile() throws Exception {
    Set<Label> result = parseList("//:BUILD");
    assertThat(result).containsExactly(Label.parseCanonical("//:BUILD"));
  }

  @Test
  public void testTopLevelPackage_absolute_declaredTarget() throws Exception {
    Set<Label> result = parseList("//:fg");
    assertThat(result).containsExactly(Label.parseCanonical("//:fg"));
  }

  @Test
  public void testTopLevelPackage_absolute_all() throws Exception {
    Set<Label> result = parseList("//:all");
    assertThat(result).containsExactly(Label.parseCanonical("//:fg"));
  }

  @Test
  public void testTopLevelPackage_absolute_inputFile() throws Exception {
    Set<Label> result = parseList("//:foo.cc");
    assertThat(result).containsExactly(Label.parseCanonical("//:foo.cc"));
  }

  @Test
  public void testTopLevelPackage_absolute_inputFile_noSuchInputFile() throws Exception {
    expectError("no such target '//:nope.cc'", "//:nope.cc");
  }
}
