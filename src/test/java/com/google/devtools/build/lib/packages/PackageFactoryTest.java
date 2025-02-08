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

package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.analysis.config.FeatureSet;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.License.LicenseType;
import com.google.devtools.build.lib.packages.PackageValidator.InvalidPackageException;
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.starlark.java.eval.Starlark;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@code PackageFactory}. Note: PackageLoadingTestCase doesn't support REPOSITORY
 * skyframe function, thus these tests cannot load external packages, {@code @repo://pkg}.
 */
@RunWith(JUnit4.class)
public final class PackageFactoryTest extends PackageLoadingTestCase {

  private Path throwOnReaddir = null;

  // Overrides FileSystem.readdir for the benefit of one test method
  // (testTransientErrorsInGlobbing) that injects a failure.
  @Override
  protected FileSystem createFileSystem() {
    return new InMemoryFileSystem(DigestHashFunction.SHA256) {
      @Override
      public Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
          throws IOException {
        if (throwOnReaddir != null && throwOnReaddir.asFragment().equals(path)) {
          throw new FileNotFoundException(path.getPathString());
        }
        return super.readdir(path, followSymlinks);
      }
    };
  }

  @Test
  public void testCreatePackage() throws Exception {
    scratch.file("pkgname/BUILD", "# empty build file ");
    Package pkg = loadPackage("pkgname");
    assertThat(pkg.getName()).isEqualTo("pkgname");
    assertThat(Sets.newHashSet(pkg.getTargets(Rule.class))).isEmpty();
  }

  @Test
  public void testBadRuleName() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("badrulename/BUILD", "cc_library(name = 3)");
    Package pkg = loadPackage("badrulename");
    assertContainsEvent("cc_library 'name' attribute must be a string");
    assertThat(pkg.containsErrors()).isTrue();
  }

  @Test
  public void testNoRuleName() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("badrulename/BUILD", "cc_library()");
    Package pkg = loadPackage("badrulename");
    assertContainsEvent("cc_library rule has no 'name' attribute");
    assertThat(pkg.containsErrors()).isTrue();
  }

  @Test
  public void testBadPackageName() throws Exception {
    // This is a "shallow" syntactic error: failure to form the
    // PackageIdentifier that is the real argument to loadPackage.
    LabelSyntaxException e =
        assertThrows(LabelSyntaxException.class, () -> loadPackage("not even a legal/.../label"));
    assertThat(e).hasMessageThat().contains("invalid package name 'not even a legal/.../label'");
  }

  @Test
  public void testColonInExportsFilesTargetName() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "googledata/cafe/BUILD", "exports_files(['houseads/house_ads:ca-aol_parenting_html'])");
    Package pkg = loadPackage("googledata/cafe");
    assertContainsEvent("target names may not contain ':'");
    assertThat(pkg.getTargets(FileTarget.class).toString())
        .doesNotContain("houseads/house_ads:ca-aol_parenting_html");
    assertThat(pkg.containsErrors()).isTrue();
  }

  @Test
  public void testExportsFilesVisibilityMustBeSequence() throws Exception {
    expectEvalError(
        "in call to exports_files(), parameter 'visibility' got value of type 'depset', want"
            + " 'sequence or NoneType'",
        "exports_files(srcs=[], visibility=depset(['notice']))");
  }

  @Test
  public void testExportsFilesLicensesMustBeSequence() throws Exception {
    expectEvalError(
        "in call to exports_files(), parameter 'licenses' got value of type 'depset', want"
            + " 'sequence or NoneType'",
        "exports_files(srcs=[], licenses=depset(['notice']))");
  }

  @Test
  public void testPackageNameWithPROTECTEDIsOk() throws Exception {
    reporter.removeHandler(failFastHandler);
    // One "PROTECTED":
    assertThat(isValidPackageName("foo/PROTECTED/bar")).isTrue();
    // Multiple "PROTECTED"s:
    assertThat(isValidPackageName("foo/PROTECTED/bar/PROTECTED/wiz")).isTrue();
  }

  private boolean isValidPackageName(String packageName) throws Exception {
    // Write a license decl just in case it's a third_party package:
    scratch.file(packageName + "/BUILD", "licenses(['notice'])");
    Package pkg = loadPackage(packageName);
    return !pkg.containsErrors();
  }

  @Test
  public void testDuplicatedDependencies() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "has_dupe/BUILD",
        """
        cc_library(name='dep')
        cc_library(name='has_dupe', deps=[':dep', ':dep'])
        """);

    Package pkg = loadPackage("has_dupe");
    assertContainsEvent(
        "Label '//has_dupe:dep' is duplicated in the 'deps' attribute of rule 'has_dupe'");
    assertThat(pkg.containsErrors()).isTrue();
    assertThat(pkg.getRule("has_dupe")).isNotNull();
    assertThat(pkg.getRule("dep")).isNotNull();
    assertThat(pkg.getRule("has_dupe").containsErrors()).isTrue();
    // All rules in an errant package are themselves errant.
    assertThat(pkg.getRule("dep").containsErrors()).isTrue();
  }

  @Test
  public void testPrefixWithinSameRule1() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "fruit/orange/BUILD", "genrule(name='orange', srcs=[], outs=['a', 'a/b'], cmd='')");
    loadPackage("fruit/orange");
    assertContainsEvent("rule 'orange' has conflicting output files 'a/b' and 'a");
  }

  @Test
  public void testPrefixWithinSameRule2() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "fruit/orange/BUILD", "genrule(name='orange', srcs=[], outs=['a/b', 'a'], cmd='')");
    loadPackage("fruit/orange");
    assertContainsEvent("rule 'orange' has conflicting output files 'a' and 'a/b");
  }

  @Test
  public void testPrefixBetweenRules1() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "fruit/kiwi/BUILD",
        """
        genrule(name='kiwi1', srcs=[], outs=['a'], cmd='')
        genrule(name='kiwi2', srcs=[], outs=['a/b'], cmd='')
        """);
    loadPackage("fruit/kiwi");
    assertContainsEvent(
        "output file 'a/b' of rule 'kiwi2' conflicts with output file 'a' of rule 'kiwi1'");
  }

  @Test
  public void testPrefixBetweenRules2() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "fruit/kiwi/BUILD",
        """
        genrule(name='kiwi1', srcs=[], outs=['a/b'], cmd='')
        genrule(name='kiwi2', srcs=[], outs=['a'], cmd='')
        """);
    loadPackage("fruit/kiwi");
    assertContainsEvent(
        "output file 'a' of rule 'kiwi2' conflicts with output file 'a/b' of rule 'kiwi1'");
  }

  @Test
  public void testPackageNameFunction() throws Exception {
    scratch.file("pina/BUILD", "cc_library(name=package_name() + '-colada')");
    Package pkg = loadPackage("pina");
    assertNoEvents();
    assertThat(pkg.containsErrors()).isFalse();
    assertThat(pkg.getRule("pina-colada")).isNotNull();
    assertThat(pkg.getRule("pina-colada").containsErrors()).isFalse();
    assertThat(Sets.newHashSet(pkg.getTargets(Rule.class)).size()).isSameInstanceAs(1);
  }

  @Test
  public void testDuplicateRuleName() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "duplicaterulename/BUILD",
        """
        filegroup(name = 'spellcheck_proto',
                 srcs = ['spellcheck.proto'])
        cc_library(name = 'spellcheck_proto')  # conflict error stops execution
        x = 1//0  # not reached
        """);
    Package pkg = loadPackage("duplicaterulename");
    assertContainsEvent(
        "cc_library rule 'spellcheck_proto' conflicts with" + " existing filegroup rule");
    assertDoesNotContainEvent("division by zero");
    assertThat(pkg.containsErrors()).isTrue();
  }

  @Test
  public void testBuildFileTargetExists() throws Exception {
    scratch.file("foo/BUILD");
    Target target = getTarget("//foo:BUILD");
    assertThat(target.getName()).isEqualTo("BUILD");
    // Test that it's memoized:
    assertThat(target.getPackage().getTarget("BUILD")).isSameInstanceAs(target);
  }

  @Test
  public void testCreationOfInputFiles() throws Exception {
    setBuildLanguageOptions("--incompatible_no_implicit_file_export");
    scratch.file(
        "foo/BUILD",
        "exports_files(['Z'], visibility=[\"//visibility:public\"],"
            + " licenses=[\"restricted\"])",
        "cc_library(name='W', deps=['X', 'Y', 'A'])",
        "cc_library(name='X', srcs=['X'])",
        "cc_library(name='Y')");
    Package pkg = loadPackage("foo");
    assertThat(pkg.containsErrors()).isFalse();

    // X is a rule with a circular self-dependency.
    assertThat(pkg.getTarget("X").getClass()).isSameInstanceAs(Rule.class);

    // Y is a rule
    assertThat(pkg.getTarget("Y").getClass()).isSameInstanceAs(Rule.class);

    // Z is an export file with specified visibility and license specified
    Target exportFileTarget = pkg.getTarget("Z");
    assertThat(exportFileTarget.getClass())
        .isSameInstanceAs(VisibilityLicenseSpecifiedInputFile.class);
    assertThat(((VisibilityLicenseSpecifiedInputFile) exportFileTarget).isVisibilitySpecified())
        .isTrue();
    assertThat(exportFileTarget.getVisibility().getDeclaredLabels())
        .containsExactly(RuleVisibility.PUBLIC_LABEL);
    assertThat(((VisibilityLicenseSpecifiedInputFile) exportFileTarget).isLicenseSpecified())
        .isTrue();
    assertThat(exportFileTarget.getLicense().getLicenseTypes())
        .containsExactly(LicenseType.RESTRICTED);

    // A is an input file with private visibility
    Target inputFileTarget = pkg.getTarget("A");
    assertThat(inputFileTarget.getClass()).isSameInstanceAs(PrivateVisibilityInputFile.class);
    assertThat(((PrivateVisibilityInputFile) inputFileTarget).isVisibilitySpecified()).isTrue();
    assertThat(inputFileTarget.getVisibility().getDeclaredLabels())
        .containsExactly(RuleVisibility.PRIVATE_LABEL);

    // B is nothing
    NoSuchTargetException e = assertThrows(NoSuchTargetException.class, () -> pkg.getTarget("B"));
    assertThat(e)
        .hasMessageThat()
        .contains("no such target '//foo:B': target 'B' not declared in package 'foo'");

    // These are the only input files: BUILD, Z
    Set<String> inputFiles = Sets.newTreeSet();
    for (InputFile inputFile : pkg.getTargets(InputFile.class)) {
      inputFiles.add(inputFile.getName());
    }
    assertThat(Lists.newArrayList(inputFiles)).containsExactly("A", "BUILD", "Z").inOrder();
  }

  @Test
  public void testDuplicateRuleIsNotAddedToPackage() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "dup/BUILD",
        """
        filegroup(name = 'dup_proto',
                      srcs  = ['dup.proto'])

        cc_library(name = 'dup_proto',
                   srcs = ['dup.pb.cc', 'dup.pb.h'])
        """);
    Package pkg = loadPackage("dup");
    assertContainsEvent("cc_library rule 'dup_proto' conflicts with existing filegroup rule");
    assertThat(pkg.containsErrors()).isTrue();

    Rule dupProto = pkg.getRule("dup_proto");
    // Check that the first rule of the given name "wins", and that each of the
    // "winning" rule's outputs is a member of the package.
    assertThat(dupProto.getRuleClass()).isEqualTo("filegroup");
    for (OutputFile out : dupProto.getOutputFiles()) {
      assertThat(pkg.getTargets(FileTarget.class)).contains(out);
    }
  }

  @Test
  public void testConflictingRuleDoesNotUpdatePackage() throws Exception {
    reporter.removeHandler(failFastHandler);

    // In this test, rule2's outputs conflict with rule1, so rule2 is rejected.
    // However, we must check that neither rule2, nor any of its inputs or
    // outputs is a member of the package, and that the conflicting output file
    // "out2" still has rule1 as its getGeneratingRule().
    scratch.file(
        "conflict/BUILD",
        """
        genrule(name = 'rule1',
                cmd = '',
                srcs = ['in1', 'in2'],
                outs = ['out1', 'out2'])
        genrule(name = 'rule2',
                cmd = '',
                srcs = ['in3', 'in4'],
                outs = ['out3', 'out2'])
        """);
    Package pkg = loadPackage("conflict");
    assertContainsEvent(
        "generated file 'out2' in rule 'rule2' "
            + "conflicts with existing generated file from rule 'rule1'");
    assertThat(pkg.containsErrors()).isTrue();

    assertThat(pkg.getRule("rule2")).isNull();

    // Ensure that rule2's "out2" didn't overwrite rule1's:
    assertThat(((OutputFile) pkg.getTarget("out2")).getGeneratingRule())
        .isSameInstanceAs(pkg.getRule("rule1"));

    // None of rule2, its inputs, or its outputs should belong to pkg:
    List<Target> found = new ArrayList<>();
    for (String targetName : ImmutableList.of("rule2", "in3", "in4", "out3")) {
      try {
        found.add(pkg.getTarget(targetName));
        // No fail() here: if there's no exception, we add the name to a list
        // and we check below that it's empty.
      } catch (NoSuchTargetException e) {
        /* good! */
      }
    }
    assertThat(found).isEmpty();
  }

  // Was: Regression test for bug "Rules declared after an error in
  // a package should be considered 'in error'".
  // Then: Regression test for bug "Why aren't ERRORS considered
  // fatal?*"
  // Now: Regression test for: execution should stop at the first EvalException;
  // all rules created prior to the exception error are marked in error.
  @Test
  public void testAllRulesInErrantPackageAreInError() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "error/BUILD",
        """
        genrule(name = 'rule1',
                cmd = ':',
                outs = ['out.1'])
        list = ['bad']
        x = 1//0  # dynamic error
        genrule(name = 'rule2',
                cmd = ':',
                outs = list)
        """);
    Package pkg = loadPackage("error");
    assertContainsEvent("division by zero");

    assertThat(pkg.containsErrors()).isTrue();

    // rule1 would be fine but is still marked as in error:
    assertThat(pkg.getRule("rule1").containsErrors()).isTrue();

    // rule2's genrule is never executed.
    Rule rule2 = pkg.getRule("rule2");
    assertThat(rule2).isNull();
  }

  @Test
  public void testHelpfulErrorForMissingExportsFiles() throws Exception {
    scratch.file("x/BUILD", "cc_library(name='x', srcs=['x.cc'])");
    scratch.file("x/x.cc");
    scratch.file("x/y.cc");
    scratch.file("x/dir/dummy");

    Package pkg = loadPackage("x");

    assertThat(pkg.getTarget("x.cc")).isNotNull(); // existing and mentioned.

    NoSuchTargetException e =
        assertThrows(NoSuchTargetException.class, () -> pkg.getTarget("y.cc"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "no such target '//x:y.cc': "
                + "target 'y.cc' not declared in package 'x' "
                + "defined by /workspace/x/BUILD; "
                + "however, a source file of this name exists.  "
                + "(Perhaps add 'exports_files([\"y.cc\"])' to x/BUILD?)");

    e = assertThrows(NoSuchTargetException.class, () -> pkg.getTarget("z.cc"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "no such target '//x:z.cc': "
                + "target 'z.cc' not declared in package 'x' "
                + "defined by /workspace/x/BUILD (did you mean x.cc?)");

    e = assertThrows(NoSuchTargetException.class, () -> pkg.getTarget("dir"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "no such target '//x:dir': target 'dir' not declared in package 'x' defined by"
                + " /workspace/x/BUILD; however, a source directory of this name exists.  (Perhaps"
                + " add 'exports_files([\"dir\"])' to x/BUILD, or define a filegroup?)");
  }

  @Test
  public void testTestSuitesImplicitlyDependOnAllRulesInPackage() throws Exception {
    scratch.file(
        "x/foo_test.bzl",
        """
        def _impl(ctx):
          pass
        foo_test = rule(implementation = _impl, test = True,
          attrs = {"srcs": attr.label_list(allow_files=True)})
        """);
    scratch.file(
        "x/BUILD",
        """
        load(':foo_test.bzl', 'foo_test')
        foo_test(name='s', srcs = ['foo.sh'])
        test_suite(name='t1')
        test_suite(name='t2', tests=[])
        test_suite(name='t3', tests=['//foo'])
        test_suite(name='t4', tests=['//foo'])
        cc_test(name='c')
        """);
    Package pkg = loadPackage("x");

    // Things to note:
    // - The '$implicit_tests' attribute is unset unless the 'tests' attribute is unset or empty.
    // - The '$implicit_tests' attribute's value for t1 and t2 is magically able to contain both s
    //    and c, even though c is instantiated after t1 and t2 are.

    assertThat(attributes(pkg.getRule("t1")).get("$implicit_tests", BuildType.LABEL_LIST))
        .containsExactlyElementsIn(
            Sets.newHashSet(Label.parseCanonical("//x:c"), Label.parseCanonical("//x:s")));
    assertThat(attributes(pkg.getRule("t2")).get("$implicit_tests", BuildType.LABEL_LIST))
        .containsExactlyElementsIn(
            Sets.newHashSet(Label.parseCanonical("//x:c"), Label.parseCanonical("//x:s")));
    assertThat(attributes(pkg.getRule("t3")).get("$implicit_tests", BuildType.LABEL_LIST))
        .isEmpty();
    assertThat(attributes(pkg.getRule("t4")).get("$implicit_tests", BuildType.LABEL_LIST))
        .isEmpty();
  }

  @Test
  public void testPackageValidationFailureRegisteredAfterLoading() throws Exception {
    scratch.file("x/BUILD", "# old");
    Package pkg = loadPackage("x");
    assertThat(pkg.containsErrors()).isFalse();

    // Install a validator.
    this.validator =
        new PackageValidator() {
          @Override
          public void validate(Package pkg2, ExtendedEventHandler eventHandler)
              throws InvalidPackageException {
            if (pkg2.getName().equals("x")) {
              eventHandler.handle(Event.warn("warning event"));
              throw new InvalidPackageException(pkg2.getPackageIdentifier(), "nope");
            }
          }
        };

    scratch.overwriteFile("x/BUILD", "# new"); // change file to cause reloading
    invalidatePackages();

    InvalidPackageException ex =
        assertThrows(InvalidPackageException.class, () -> loadPackage("x"));
    assertThat(ex).hasMessageThat().contains("no such package 'x': nope");
    assertContainsEvent("warning event");
  }

  @Test
  public void testGlobDirectoryExclusion() throws Exception {
    emptyFile("fruit/data/apple");
    emptyFile("fruit/data/pear");
    emptyFile("fruit/data/berry/black");
    emptyFile("fruit/data/berry/blue");
    scratch.file(
        "fruit/BUILD",
        """
        cc_library(name = 'yes', srcs = glob(['data/*']))
        cc_library(name = 'no',  srcs = glob(['data/*'], exclude_directories=0))
        """);
    Package pkg = loadPackage("fruit");
    assertNoEvents();
    List<Label> yesFiles = attributes(pkg.getRule("yes")).get("srcs", BuildType.LABEL_LIST);
    List<Label> noFiles = attributes(pkg.getRule("no")).get("srcs", BuildType.LABEL_LIST);

    assertThat(yesFiles)
        .containsExactly(
            Label.parseCanonical("@//fruit:data/apple"),
            Label.parseCanonical("@//fruit:data/pear"));

    assertThat(noFiles)
        .containsExactly(
            Label.parseCanonical("@//fruit:data/apple"),
            Label.parseCanonical("@//fruit:data/pear"),
            Label.parseCanonical("@//fruit:data/berry"));
  }

  // TODO(bazel-team): This is really a test for GlobCache.
  @Test
  public void testRecursiveGlob() throws Exception {
    emptyFile("rg/a.cc");
    emptyFile("rg/foo/bar.cc");
    emptyFile("rg/foo/foo.cc");
    emptyFile("rg/foo/wiz/bam.cc");
    emptyFile("rg/foo/wiz/bum.cc");
    emptyFile("rg/foo/wiz/quid/gav.cc");
    scratch.file(
        "rg/BUILD",
        """
        cc_library(name = 'ri', srcs = glob(['**/*.cc']))
        cc_library(name = 're', srcs = glob(['*.cc'], exclude=['**/*.c']))
        """);
    Package pkg = loadPackage("rg");
    assertNoEvents();

    assertGlob(
        pkg,
        ImmutableList.of(
            "BUILD",
            "a.cc",
            "foo",
            "foo/bar.cc",
            "foo/foo.cc",
            "foo/wiz",
            "foo/wiz/bam.cc",
            "foo/wiz/bum.cc",
            "foo/wiz/quid",
            "foo/wiz/quid/gav.cc"),
        "**");

    assertGlob(
        pkg,
        ImmutableList.of(
            "a.cc",
            "foo/bar.cc",
            "foo/foo.cc",
            "foo/wiz/bam.cc",
            "foo/wiz/bum.cc",
            "foo/wiz/quid/gav.cc"),
        "**/*.cc");
    assertGlob(pkg, ImmutableList.of("foo/bar.cc", "foo/wiz/bam.cc", "foo/wiz/bum.cc"), "**/b*.cc");
    assertGlob(
        pkg,
        ImmutableList.of(
            "foo/bar.cc", "foo/foo.cc", "foo/wiz/bam.cc", "foo/wiz/bum.cc", "foo/wiz/quid/gav.cc"),
        "**/*/*.cc");
    assertGlob(pkg, ImmutableList.of("foo/wiz/quid/gav.cc"), "foo/**/quid/*.cc");

    assertGlob(
        pkg,
        Collections.<String>emptyList(),
        ImmutableList.of("*.cc", "*/*.cc", "*/*/*.cc"),
        ImmutableList.of("**/*.cc"));
    assertGlob(
        pkg,
        Collections.<String>emptyList(),
        ImmutableList.of("**/*.cc"),
        ImmutableList.of("**/*.cc"));
    assertGlob(
        pkg,
        Collections.<String>emptyList(),
        ImmutableList.of("**/*.cc"),
        ImmutableList.of("*.cc", "*/*.cc", "*/*/*.cc", "*/*/*/*.cc"));
    assertGlob(
        pkg,
        Collections.<String>emptyList(),
        ImmutableList.of("**"),
        ImmutableList.of("*", "*/*", "*/*/*", "*/*/*/*"));
    assertGlob(
        pkg,
        ImmutableList.of(
            "foo/bar.cc", "foo/foo.cc", "foo/wiz/bam.cc", "foo/wiz/bum.cc", "foo/wiz/quid/gav.cc"),
        ImmutableList.of("**/*.cc"),
        ImmutableList.of("*.cc"));
    assertGlob(
        pkg,
        ImmutableList.of("a.cc", "foo/wiz/bam.cc", "foo/wiz/bum.cc", "foo/wiz/quid/gav.cc"),
        ImmutableList.of("**/*.cc"),
        ImmutableList.of("*/*.cc"));
    assertGlob(
        pkg,
        ImmutableList.of("a.cc", "foo/bar.cc", "foo/foo.cc", "foo/wiz/quid/gav.cc"),
        ImmutableList.of("**/*.cc"),
        ImmutableList.of("**/wiz/*.cc"));
  }

  @Test
  public void testTooManyArgumentsGlobErrors() throws Exception {
    reporter.removeHandler(failFastHandler);
    assertGlobFails(
        "glob(['incl'],['excl'],3,True,'extraarg')",
        "glob() accepts no more than 4 positional arguments but got 5");
  }

  @Test
  public void testGlobEnforcesListArgument() throws Exception {
    reporter.removeHandler(failFastHandler);
    assertGlobFails(
        "glob(1, exclude=2)",
        "in call to glob(), parameter 'include' got value of type 'int', want 'sequence'");
  }

  @Test
  public void testGlobEnforcesListOfStringsArguments() throws Exception {
    reporter.removeHandler(failFastHandler);
    assertGlobFails(
        "glob(['a', 'b'], exclude=['c', 42])",
        "expected value of type 'string' for element 1 of 'glob' argument, but got 42 (int)");
  }

  @Test
  public void testGlobNegativeTest() throws Exception {
    // Negative test that assertGlob does throw an error when asserting against the wrong values.
    // The AssertionError comes from FoundationTestCase.failFastHandler.
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                assertGlobMatches(
                    /*result=*/ ImmutableList.of("Wombat1.java", "This_file_doesn_t_exist.java"),
                    /*includes=*/ ImmutableList.of("W*", "subdir"),
                    /*excludes=*/ ImmutableList.<String>of(),
                    /* excludeDirs= */ true));
    assertThat(e).hasMessageThat().contains("incorrect glob result");
  }

  @Test
  public void testGlobExcludeDirectories() throws Exception {
    assertGlobMatches(
        /*result=*/ ImmutableList.of("Wombat1.java", "Wombat2.java"),
        /*includes=*/ ImmutableList.of("W*", "subdir"),
        /*excludes=*/ ImmutableList.<String>of(),
        /* excludeDirs= */ true);
  }

  @Test
  public void testGlobDoesNotExcludeDirectories() throws Exception {
    assertGlobMatches(
        /*result=*/ ImmutableList.of("Wombat1.java", "Wombat2.java", "subdir"),
        /*includes=*/ ImmutableList.of("W*", "subdir"),
        /*excludes=*/ ImmutableList.<String>of(),
        /* excludeDirs= */ false);
  }

  @Test
  public void testGlobWithEmptyExcludedList() throws Exception {
    assertGlobMatches(
        /*result=*/ ImmutableList.of("Wombat1.java", "Wombat2.java"),
        /*includes=*/ ImmutableList.of("W*"),
        /*excludes=*/ Collections.<String>emptyList(),
        /* excludeDirs= */ false);
  }

  @Test
  public void testGlobWithQuestionMarkProducesError() throws Exception {
    assertGlobProducesError("Wombat?.java", true);
  }

  @Test
  public void testGlobWithoutQuestionMarkDoesntProduceError() throws Exception {
    assertGlobProducesError("Wombat*.java", false);
  }

  @Test
  public void testGlobWithNonMatchingExcludedList() throws Exception {
    assertGlobMatches(
        /*result=*/ ImmutableList.of("Wombat1.java"),
        /*includes=*/ ImmutableList.of("W*"),
        /*excludes=*/ ImmutableList.of("*2*"),
        /* excludeDirs= */ false);
  }

  @Test
  public void testGlobWithTwoMatchingGlobExpressionsAndNonmatchingExclusion() throws Exception {
    assertGlobMatches(
        /*result=*/ ImmutableList.of("Wombat1.java", "subdir/Wombat3.java"),
        /*includes=*/ ImmutableList.of("W*", "subdir/W*"),
        /*excludes=*/ ImmutableList.of("*2*"),
        /* excludeDirs= */ false);
  }

  @Test
  public void testGlobWithSubdirMatchAndExclusion() throws Exception {
    assertGlobMatches(
        /*result=*/ ImmutableList.of("subdir/Wombat3.java"),
        /*includes=*/ ImmutableList.of("W*", "subdir/W*"),
        /*excludes=*/ ImmutableList.of("Wombat*.java"),
        /* excludeDirs= */ false);
  }

  @Test
  public void testBadCharacterInGlob() throws Exception {
    reporter.removeHandler(failFastHandler);
    assertGlobFails("glob(['?'])", "Error in glob: invalid glob pattern '?': wildcard ? forbidden");
  }

  @Test
  public void testBadExcludePattern() throws Exception {
    reporter.removeHandler(failFastHandler);
    // The 'exclude' check is currently only reached if the pattern is "complex".
    // This seems like a bug:
    //   assertGlobFails("glob(['BUILD'], ['/'])", "pattern cannot be absolute");
    assertGlobFails("glob(['BUILD'], ['/*/*'])", "pattern cannot be absolute");
  }

  @Test
  public void testGlobEscapesAt() throws Exception {
    // See lib.skyframe.PackageFunctionTest.globEscapesAt and
    // https://github.com/bazelbuild/bazel/issues/10606.
    scratch.file("p/@f.txt");
    scratch.file(
        "p/BUILD",
        """
        name = glob(['*.txt'])[0]
        name == ':@f.txt' or fail('got %s' % name)
        """); // observe
    // prepended colon
    loadPackage("p"); // no error
  }

  /**
   * Tests that a glob evaluation that encounters an I/O error throws instead of constructing a
   * package.
   */
  @Test
  public void testGlobWithIOErrors() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("pkg/BUILD", "glob(['globs/**'])");
    Path dir = scratch.dir("pkg/globs/unreadable");
    dir.setReadable(false);

    NoSuchPackageException ex =
        assertThrows(NoSuchPackageException.class, () -> loadPackage("pkg"));
    assertThat(ex)
        .hasMessageThat()
        .contains("error globbing [globs/**] op=FILES: " + dir + " (Permission denied)");
  }

  @Test
  public void testNativeModuleIsDisabled() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("pkg/BUILD", "native.cc_library(name='bar')");
    Package pkg = loadPackage("pkg");
    assertThat(pkg.containsErrors()).isTrue();
  }

  @Test
  public void testPackageGroupSpecMinimal() throws Exception {
    expectEvalSuccess("package_group(name='skin', packages=[])");
  }

  @Test
  public void testPackageGroupSpecSimple() throws Exception {
    expectEvalSuccess("package_group(name='skin', packages=['//group/abelian'])");
  }

  @Test
  public void testPackageGroupSpecEmpty() throws Exception {
    expectEvalSuccess("package_group(name='seed')");
  }

  @Test
  public void testPackageGroupSpecIncludes() throws Exception {
    expectEvalSuccess(
        "package_group(name='wine',",
        "              includes=['//wine:cabernet_sauvignon',",
        "                        '//wine:pinot_noir'])");
  }

  @Test
  public void testPackageGroupSpecBad() throws Exception {
    expectEvalError("invalid package name", "package_group(name='skin', packages=['--25:17--'])");
  }

  @Test
  public void testPackageGroupsWithSameName() throws Exception {
    expectEvalError(
        "conflicts with existing package group",
        "package_group(name='skin', packages=[])",
        "package_group(name='skin', packages=[])");
  }

  @Test
  public void testPackageGroupNamedArguments() throws Exception {
    expectEvalError(
        "package_group() got unexpected positional argument", "package_group('skin', name = 'x')");
  }

  @Test
  public void testPackageSpecMinimal() throws Exception {
    Package pkg = expectEvalSuccess("package(default_visibility=[])");
    assertThat(pkg.getPackageArgs().defaultVisibility()).isNotNull();
  }

  @Test
  public void testPackageSpecSimple() throws Exception {
    expectEvalSuccess("package(default_visibility=['//group:lie'])");
  }

  @Test
  public void testPackageSpecBad() throws Exception {
    expectEvalError("invalid target name", "package(default_visibility=[':::'])");
  }

  @Test
  public void testDoublePackageSpecification() throws Exception {
    expectEvalError(
        "can only be used once",
        "package(default_visibility=[])",
        "package(default_visibility=[])");
  }

  @Test
  public void testEmptyPackageSpecification() throws Exception {
    expectEvalError("at least one argument must be given to the 'package' function", "package()");
  }

  @Test
  public void testDefaultTestonly() throws Exception {
    Package pkg = expectEvalSuccess("package(default_testonly = 1)");
    assertThat(pkg.getPackageArgs().defaultTestOnly()).isTrue();
  }

  @Test
  public void testDefaultDeprecation() throws Exception {
    String testMessage = "OMG PONIES!";
    Package pkg = expectEvalSuccess("package(default_deprecation = \"" + testMessage + "\")");
    assertThat(pkg.getPackageArgs().defaultDeprecation()).isEqualTo(testMessage);
  }

  @Test
  public void testExportsBuildFile() throws Exception {
    Package pkg =
        expectEvalSuccess("exports_files(['BUILD'], visibility=['//visibility:private'])");
    assertThat(pkg.getTarget("BUILD")).isEqualTo(pkg.getBuildFile());
  }

  @Test
  public void testDefaultDeprecationPropagation() throws Exception {
    String msg = "I am completely operational, and all my circuits are functioning perfectly.";
    scratch.file(
        "foo/BUILD",
        "package(default_deprecation = \"" + msg + "\")",
        "filegroup(name = 'bar', srcs=['b'])");
    Rule fooRule = (Rule) getTarget("//foo:bar");
    String deprAttr =
        attributes(fooRule).get("deprecation", com.google.devtools.build.lib.packages.Type.STRING);
    assertThat(deprAttr).isEqualTo(msg);
  }

  @Test
  public void testDefaultTestonlyPropagation() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        package(default_testonly = 1)
        filegroup(name = 'foo', srcs=['b'])
        filegroup(name = 'bar', srcs=['b'], testonly = 0)
        """);
    Package pkg = loadPackage("foo");

    Rule fooRule = (Rule) pkg.getTarget("foo");
    assertThat(
            attributes(fooRule)
                .get("testonly", com.google.devtools.build.lib.packages.Type.BOOLEAN))
        .isTrue();

    Rule barRule = (Rule) pkg.getTarget("bar");
    assertThat(
            attributes(barRule)
                .get("testonly", com.google.devtools.build.lib.packages.Type.BOOLEAN))
        .isFalse();
  }

  @Test
  public void testDefaultDeprecationOverriding() throws Exception {
    String msg = "I am completely operational, and all my circuits are functioning perfectly.";
    String deceive = "OMG PONIES!";
    scratch.file(
        "foo/BUILD",
        "package(default_deprecation = \"" + deceive + "\")",
        "filegroup(name = 'bar', srcs=['b'], deprecation = \"" + msg + "\")");
    Package pkg = loadPackage("foo");

    Rule fooRule = (Rule) pkg.getTarget("bar");
    String deprAttr =
        attributes(fooRule).get("deprecation", com.google.devtools.build.lib.packages.Type.STRING);
    assertThat(deprAttr).isEqualTo(msg);
  }

  @Test
  public void testPackageFeatures() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        filegroup(name='before')
        package(features=['b', 'c'])
        filegroup(name='after')
        """);
    Package pkg = loadPackage("a");
    assertThat(pkg.getPackageArgs().features())
        .isEqualTo(FeatureSet.parse(ImmutableList.of("b", "c")));
  }

  @Test
  public void testTransientErrorsInGlobbing() throws Exception {
    Path buildFile = scratch.file("e/BUILD", "filegroup(name = 'e', srcs = glob(['*']))");
    throwOnReaddir = buildFile.getParentDirectory();
    invalidatePackages();
    reporter.removeHandler(failFastHandler);
    assertThrows(NoSuchPackageException.class, () -> loadPackage("e")); // symlink cycle

    throwOnReaddir = null;
    invalidatePackages();

    reporter.addHandler(failFastHandler);
    Package pkg = loadPackage("e"); // no error
    assertThat(pkg.containsErrors()).isFalse();
    assertThat(pkg.getRule("e")).isNotNull();
    List<?> globList = (List) pkg.getRule("e").getAttr("srcs");
    assertThat(globList).containsExactly(Label.parseCanonical("//e:BUILD"));
  }

  @Test
  public void testExportTwicePublicOK() throws Exception {
    // In theory, this could be an error, but too many existing files rely on it
    // and it is okay.
    expectEvalSuccess(
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:public\" ])",
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:public\" ])");
  }

  @Test
  public void testExportTwicePublicOK2() throws Exception {
    expectEvalSuccess(
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:private\" ])",
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:private\" ])");
  }

  @Test
  public void testExportTwiceFail() throws Exception {
    expectEvalError(
        "visibility for exported file 'a.cc' declared twice",
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:private\" ])",
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:public\" ])");
  }

  @Test
  public void testExportTwiceFail2() throws Exception {
    expectEvalError(
        "visibility for exported file 'a.cc' declared twice",
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:public\" ])",
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:private\" ])");
  }

  @Test
  public void testExportLicenseTwice() throws Exception {
    expectEvalError(
        "licenses for exported file 'a.cc' declared twice",
        "exports_files([\"a.cc\"], licenses = [\"notice\"])",
        "exports_files([\"a.cc\"], licenses = [\"notice\"])");
  }

  @Test
  public void testExportGenruleConflict() throws Exception {
    expectEvalError(
        "generated file 'a.cc' in rule 'foo' conflicts with existing source file",
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:public\" ])",
        "genrule(name = 'foo',",
        "    outs = ['a.cc'],",
        "    cmd = '')");
  }

  @Test
  public void testGenruleExportConflict() throws Exception {
    expectEvalError(
        "source file 'a.cc' conflicts with existing generated file from rule 'foo'",
        "genrule(name = 'foo',",
        "    outs = ['a.cc'],",
        "    cmd = '')",
        "exports_files([\"a.cc\"],",
        "    visibility = [ \"//visibility:public\" ])");
  }

  @Test
  public void testValidEnvironmentGroup() throws Exception {
    expectEvalSuccess(
        "environment(name = 'foo')",
        "environment_group(name='group', environments = [':foo'], defaults = [':foo'])");
  }

  @Test
  public void testIncompleteEnvironmentGroup() throws Exception {
    expectEvalError(
        "environment_group() missing 1 required named argument: defaults",
        "environment(name = 'foo')",
        "environment_group(name='group', environments = [':foo'])");
  }

  @Test
  public void testEnvironmentGroupMissingTarget() throws Exception {
    expectEvalError(
        "environment //pkg:foo does not exist",
        "environment_group(name='group', environments = [':foo'], defaults = [':foo'])");
  }

  @Test
  public void testEnvironmentGroupWrongTargetType() throws Exception {
    expectEvalError(
        "//pkg:foo is not a valid environment",
        "cc_library(name = 'foo')",
        "environment_group(name='group', environments = [':foo'], defaults = [':foo'])");
  }

  @Test
  public void testEnvironmentGroupWrongPackage() throws Exception {
    expectEvalError(
        "//foo:foo is not in the same package as group //pkg:group",
        "environment_group(name='group', environments = ['//foo'], defaults = ['//foo'])");
  }

  @Test
  public void testEnvironmentGroupInvalidDefault() throws Exception {
    expectEvalError(
        "default //pkg:bar is not a declared environment for group //pkg:group",
        "environment(name = 'foo')",
        "environment(name = 'bar')",
        "environment_group(name='group', environments = [':foo'], defaults = [':bar'])");
  }

  @Test
  public void testEnvironmentGroupDuplicateEnvironments() throws Exception {
    expectEvalError(
        "label '//pkg:foo' is duplicated in the 'environments' list of 'group'",
        "environment(name = 'foo')",
        "environment_group(name='group', environments = [':foo', ':foo'], defaults = [':foo'])");
  }

  @Test
  public void testEnvironmentGroupDuplicateDefaults() throws Exception {
    expectEvalError(
        "label '//pkg:foo' is duplicated in the 'defaults' list of 'group'",
        "environment(name = 'foo')",
        "environment_group(name='group', environments = [':foo'], defaults = [':foo', ':foo'])");
  }

  @Test
  public void testMultipleEnvironmentGroupsValidMembership() throws Exception {
    expectEvalSuccess(
        "environment(name = 'foo')",
        "environment(name = 'bar')",
        "environment_group(name='foo_group', environments = [':foo'], defaults = [':foo'])",
        "environment_group(name='bar_group', environments = [':bar'], defaults = [':bar'])");
  }

  @Test
  public void testMultipleEnvironmentGroupsConflictingMembership() throws Exception {
    expectEvalError(
        "environment //pkg:foo belongs to both //pkg:bar_group and //pkg:foo_group",
        "environment(name = 'foo')",
        "environment(name = 'bar')",
        "environment_group(name='foo_group', environments = [':foo'], defaults = [':foo'])",
        "environment_group(name='bar_group', environments = [':foo'], defaults = [':foo'])");
  }

  @Test
  public void testFulfillsReferencesWrongTargetType() throws Exception {
    expectEvalError(
        "in \"fulfills\" attribute of //pkg:foo: //pkg:bar is not a valid environment",
        "environment(name = 'foo', fulfills = [':bar'])",
        "cc_library(name = 'bar')",
        "environment_group(name='foo_group', environments = [':foo'], defaults = [])");
  }

  @Test
  public void testFulfillsNotInEnvironmentGroup() throws Exception {
    expectEvalError(
        "in \"fulfills\" attribute of //pkg:foo: //pkg:bar is not a member of this group",
        "environment(name = 'foo', fulfills = [':bar'])",
        "environment(name = 'bar')",
        "environment_group(name='foo_group', environments = [':foo'], defaults = [])");
  }

  @Test
  public void testPackageDefaultEnvironments() throws Exception {
    Package pkg =
        expectEvalSuccess(
            "package(",
            "    default_compatible_with=['//foo'],",
            "    default_restricted_to=['//bar'],",
            ")");
    assertThat(pkg.getPackageArgs().defaultCompatibleWith())
        .containsExactly(Label.parseCanonical("//foo"));
    assertThat(pkg.getPackageArgs().defaultRestrictedTo())
        .containsExactly(Label.parseCanonical("//bar"));
  }

  @Test
  public void testPackageDefaultCompatibilityDuplicates() throws Exception {
    expectEvalError(
        "duplicate label(s) in default_compatible_with: //foo:foo",
        "package(default_compatible_with=['//foo', '//bar', '//foo'])");
  }

  @Test
  public void testPackageDefaultRestrictionDuplicates() throws Exception {
    expectEvalError(
        "duplicate label(s) in default_restricted_to: //foo:foo",
        "package(default_restricted_to=['//foo', '//bar', '//foo'])");
  }

  /**
   * Defines a symbolic macro "my_macro" in //pkg:my_macro.bzl, and enables the experimental flag.
   *
   * <p>The macro does not define any targets.
   */
  private void defineEmptyMacroBzl() throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation = _impl)
        """);
  }

  @Test
  public void testSymbolicMacro_duplicateMacroNamesDisallowed() throws Exception {
    // However, note that duplicates are allowed if one is a submacro of the other.
    // See SymbolicMacroTest#submacroMayHaveSameNameAsAncestorMacros for coverage of that.
    defineEmptyMacroBzl();
    expectEvalError(
        "macro 'foo' conflicts with an existing macro (and was not created by it)",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        my_macro(name = "foo")
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndRuleClash_macroDeclaredFirst() throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "target 'foo' conflicts with an existing macro (and was not created by it)",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        cc_library(name = "foo")
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndRuleClash_ruleDeclaredFirst() throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "macro 'foo' conflicts with an existing target",
        """
        load(":my_macro.bzl", "my_macro")
        cc_library(name = "foo")
        my_macro(name = "foo")
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndOutputClash_macroDeclaredFirst() throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "target 'foo' conflicts with an existing macro (and was not created by it)",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        genrule(name = "gen", outs = ["foo"], cmd = "")
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndOutputClash_outputDeclaredFirst() throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "macro 'foo' conflicts with an existing target",
        """
        load(":my_macro.bzl", "my_macro")
        genrule(name = "gen", outs = ["foo"], cmd = "")
        my_macro(name = "foo")
        """);
  }

  @Test
  public void testSymbolicMacro_macroMayCollideWithPrefixOfOutput() throws Exception {
    // TODO(#19922): Currently we only prevent output file prefixes from colliding with other output
    // files, and don't check if they collide with other types of targets. If we become more
    // restrictive in the future, and to the extent we restrict collisions between macro names and
    // target names (i.e., exclusive prefixes), we should also ensure output prefixes can't collide
    // with macros.
    defineEmptyMacroBzl();
    expectEvalSuccess(
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        genrule(name = "gen", outs = ["foo/bar"], cmd = "")
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndEnvironmentGroupClash_macroDeclaredFirst()
      throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "target 'foo' conflicts with an existing macro (and was not created by it)",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        environment(name = "env")
        environment_group(name="foo", environments = [":env"], defaults = [":env"])
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndEnvironmentGroupClash_environmentGroupDeclaredFirst()
      throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "macro 'foo' conflicts with an existing target",
        """
        load(":my_macro.bzl", "my_macro")
        environment(name = "env")
        environment_group(name="foo", environments = [":env"], defaults = [":env"])
        my_macro(name = "foo")
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndPackageGroupClash_macroDeclaredFirst() throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "target 'foo' conflicts with an existing macro (and was not created by it)",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        package_group(name = "foo")
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndPackageGroupClash_packageGroupDeclaredFirst()
      throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "macro 'foo' conflicts with an existing target",
        """
        load(":my_macro.bzl", "my_macro")
        package_group(name = "foo")
        my_macro(name = "foo")
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndInputClash_macroDeclaredFirst() throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "target 'foo' conflicts with an existing macro (and was not created by it)",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        exports_files(["foo"])
        """);
  }

  @Test
  public void testSymbolicMacro_macroAndInputClash_inputDeclaredFirst() throws Exception {
    defineEmptyMacroBzl();
    expectEvalError(
        "macro 'foo' conflicts with an existing target",
        """
        load(":my_macro.bzl", "my_macro")
        exports_files(["foo"])
        my_macro(name = "foo")
        """);
  }

  @Test
  public void testSymbolicMacro_implicitlyCreatedInput_isCreatedEvenInsideMacroNamespace()
      throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        cc_library(
            name = "toplevel_target",
            srcs = ["foo_implicit"],
        )
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertThat(pkg.getTarget("foo_implicit")).isInstanceOf(InputFile.class);
  }

  @Test
  public void testSymbolicMacro_implicitlyCreatedInput_isNotCreatedIfMacroDeclaresTarget()
      throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(name = name + "_declared_target")
            native.cc_library(name = "illegally_named_target")
        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        cc_library(
            name = "toplevel_target",
            srcs = [
                "foo_declared_target",
                "illegally_named_target",
            ],
        )
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertThat(pkg.getTarget("foo_declared_target")).isInstanceOf(Rule.class);
    // This target doesn't lie within the macro's namespace and so can't be analyzed, but it still
    // exists and prevents input file creation. (Under a lazy macro evaluation model, we would
    // potentially create an InputFile for it but later discover a name clash if the macro is
    // evaluated.)
    // TODO: #23852 - Test behavior under lazy macro evaluation when implemented.
    assertThat(pkg.getTarget("illegally_named_target")).isInstanceOf(Rule.class);
  }

  @Test
  public void testSymbolicMacro_implicitlyCreatedInput_isNotCreatedIfMacroNameMatchesExactly()
      throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        cc_library(
            name = "toplevel_target",
            srcs = ["foo"],
        )
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertThat(pkg.getTargets()).doesNotContainKey("foo");
  }

  @Test
  public void testSymbolicMacro_implicitlyCreatedInput_isCreatedByUsageInMacroAttr()
      throws Exception {
    // A usage in a macro, provided it is top-level, is sufficient to cause an input file to be
    // implicitly created, even if that input file is not also referred to by any actual targets.
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _impl(name, visibility, src):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {"src": attr.label()},
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(
            name = "foo",
            src = "//pkg:input",
        )
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertThat(pkg.getTarget("input")).isInstanceOf(InputFile.class);
  }

  @Test
  public void testSymbolicMacro_implicitlyCreatedInput_isNotCreatedByUsageInMacroBody()
      throws Exception {
    // A usage in the body of a macro (whether the declaration is for a target or submacro), does
    // not by itself cause an input file to be implicitly created.
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _sub_impl(name, visibility):
            native.cc_library(
                name = name,
                srcs = ["//pkg:input"],
            )
        my_submacro = macro(implementation = _sub_impl)

        def _impl(name, visibility):
            native.cc_library(
                name = name,
                srcs = ["//pkg:input"],
            )
            my_submacro(name = name + "_submacro")
        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertThat(pkg.getTargets()).doesNotContainKey("input");
  }

  @Test
  public void testSymbolicMacro_deferredEvaluationExpandsTransitively() throws Exception {
    scratch.file(
        "pkg/my_macro.bzl",
        """
        def _inner_impl(name, visibility):
            native.cc_library(name = name)
        inner_macro = macro(implementation=_inner_impl, finalizer = True)

        def _middle_impl(name, visibility):
            inner_macro(name = name)
        middle_macro = macro(implementation=_middle_impl, finalizer = True)

        def _outer_impl(name, visibility):
            middle_macro(name = name)
        outer_macro = macro(implementation=_outer_impl, finalizer = True)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":my_macro.bzl", "outer_macro")
        outer_macro(name = "abc")
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertThat(pkg.getTargets()).containsKey("abc");
    assertThat(pkg.getMacrosById().keySet()).containsExactly("abc:1", "abc:2", "abc:3");
  }

  private void defineRecursiveMacro(boolean deferredEvaluation) throws Exception {
    scratch.file(
        "pkg/recursive_macro.bzl",
        String.format(
            """
            def _impl(name, visibility, height):
                if height == 0:
                    native.cc_library(name = name)
                else:
                    recursive_macro(
                        name = name + "_x",
                        height = height - 1,
                    )

            recursive_macro = macro(
                implementation = _impl,
                attrs = {
                    "height": attr.int(configurable=False),
                },
                finalizer = %s,
            )
            """,
            deferredEvaluation ? "True" : "False"));
  }

  @Test
  public void testSymbolicMacro_recursionProhibitedWithEagerEvaluation() throws Exception {
    defineRecursiveMacro(/* deferredEvaluation= */ false);
    expectEvalError(
        """
        macro 'abc_x' is a direct recursive call of 'abc'. Macro instantiation traceback (most \
        recent call last):
        \tPackage //pkg, macro 'abc' of type //pkg:recursive_macro.bzl%recursive_macro
        \tPackage //pkg, macro 'abc_x' of type //pkg:recursive_macro.bzl%recursive_macro""",
        """
        load(":recursive_macro.bzl", "recursive_macro")
        recursive_macro(
            name = "abc",
            height = 3,
        )
        """);
  }

  @Test
  public void testSymbolicMacro_recursionProhibitedWithDeferredEvaluation() throws Exception {
    defineRecursiveMacro(/* deferredEvaluation= */ true);
    expectEvalError(
        """
        macro 'abc_x' is a direct recursive call of 'abc'. Macro instantiation traceback (most \
        recent call last):
        \tPackage //pkg, macro 'abc' of type //pkg:recursive_macro.bzl%recursive_macro
        \tPackage //pkg, macro 'abc_x' of type //pkg:recursive_macro.bzl%recursive_macro""",
        """
        load(":recursive_macro.bzl", "recursive_macro")
        recursive_macro(
            name = "abc",
            height = 3,
        )
        """);
  }

  @Test
  public void testSymbolicMacro_indirectRecursionAlsoProhibited() throws Exception {
    // Define a pair of macros where A calls B calls A (and then would stop, if allowed to get that
    // far). Wrap it in a different entry point to test that the non-cyclic part is included in the
    // traceback.
    scratch.file(
        "pkg/recursive_macro.bzl",
        """
        def _A_impl(name, visibility, stop):
            if stop:
                native.cc_library(name = name)
            else:
                macro_B(name = name + "_B")

        macro_A = macro(
            implementation = _A_impl,
            attrs = {
                "stop": attr.bool(default=False, configurable=False),
            },
        )

        def _B_impl(name, visibility):
            macro_A(
                name = name + "_A",
                stop = True,
            )

        macro_B = macro(implementation = _B_impl)

        def _main_impl(name, visibility):
            macro_A(name = name)

        main_macro = macro(implementation = _main_impl)
        """);
    expectEvalError(
        """
        macro 'abc_B_A' is an indirect recursive call of 'abc'. Macro instantiation traceback \
        (most recent call last):
        \tPackage //pkg, macro 'abc' of type //pkg:recursive_macro.bzl%main_macro
        \tPackage //pkg, macro 'abc' of type //pkg:recursive_macro.bzl%macro_A
        \tPackage //pkg, macro 'abc_B' of type //pkg:recursive_macro.bzl%macro_B
        \tPackage //pkg, macro 'abc_B_A' of type //pkg:recursive_macro.bzl%macro_A""",
        """
        load(":recursive_macro.bzl", "main_macro")
        main_macro(name = "abc")
        """);
  }

  // TODO: #19922 - Add tests for graceful failure when the macro stack is too deep or there are too
  // many macros overall, for both eager and deferred evaluation.

  /**
   * Asserts that the target's {@link Target#getActualVisibility actual visibility} contains exactly
   * the given labels.
   */
  private void assertVisibilityIs(Target target, String... visibilityLabels) {
    ImmutableList.Builder<Label> labels = ImmutableList.builder();
    for (String item : visibilityLabels) {
      labels.add(Label.parseCanonicalUnchecked(item));
    }
    assertThat(target.getActualVisibility().getDeclaredLabels())
        // Values are sorted by virtue of visibility being a label_list.
        .containsExactlyElementsIn(labels.build());
  }

  private void enableMacrosAndUsePrivateVisibility() throws Exception {
    // BuildViewTestCase makes everything public by default.
    setPackageOptions("--default_visibility=private");
  }

  @Test
  public void testDeclarationVisibilityUnioning_occursBothInsideAndOutsideMacros()
      throws Exception {
    enableMacrosAndUsePrivateVisibility();
    scratch.file("lib/BUILD");
    scratch.file(
        "lib/macro.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(
                name = name,
                visibility = ["//other_pkg:__pkg__"],
            )
        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//lib:macro.bzl", "my_macro")

        cc_library(
            name = "foo",
            visibility = ["//other_pkg:__pkg__"],
        )
        my_macro(name = "bar")
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertVisibilityIs(pkg.getTarget("foo"), "//other_pkg:__pkg__", "//pkg:__pkg__");
    assertVisibilityIs(pkg.getTarget("bar"), "//other_pkg:__pkg__", "//lib:__pkg__");
  }

  @Test
  public void testDeclarationVisibilityUnioning_usesInnermostMacroLocation() throws Exception {
    enableMacrosAndUsePrivateVisibility();
    scratch.file("inner/BUILD");
    scratch.file(
        "inner/macro.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(
                name = name,
                visibility = ["//other_pkg:__pkg__"],
            )
        inner_macro = macro(implementation = _impl)
        """);
    scratch.file("outer/BUILD");
    scratch.file(
        "outer/macro.bzl",
        """
        load("//inner:macro.bzl", "inner_macro")
        def _impl(name, visibility):
            inner_macro(name = name)
        outer_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//outer:macro.bzl", "outer_macro")

        outer_macro(name = "foo")
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertVisibilityIs(pkg.getTarget("foo"), "//other_pkg:__pkg__", "//inner:__pkg__");
  }

  @Test
  public void testDeclarationVisibilityUnioning_doesNotApplyPackageDefaultVisibility()
      throws Exception {
    enableMacrosAndUsePrivateVisibility();
    scratch.file("lib/BUILD");
    scratch.file(
        "lib/macro.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(name = name)
        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//lib:macro.bzl", "my_macro")
        package(default_visibility = ["//other_pkg:__pkg__"])

        cc_library(name = "foo")
        my_macro(name = "bar")
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertVisibilityIs(pkg.getTarget("foo"), "//other_pkg:__pkg__", "//pkg:__pkg__");
    // other_pkg doesn't propagate to bar, it only has its own instantiation location.
    assertVisibilityIs(pkg.getTarget("bar"), "//lib:__pkg__");
  }

  @Test
  public void testDeclarationVisibilityUnioning_worksWithPublicPrivateAndDuplicateVisibilities()
      throws Exception {
    enableMacrosAndUsePrivateVisibility();
    scratch.file("lib/BUILD");
    scratch.file(
        "lib/macro.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(
                name = name + "_public",
                visibility = ["//visibility:public"],
            )
            native.cc_library(
                name = name + "_private",
                visibility = ["//visibility:private"],
            )
            native.cc_library(
                name = name + "_selfvisible",
                visibility = ["//lib:__pkg__"],
            )
        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//lib:macro.bzl", "my_macro")

        my_macro(name = "foo")
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertVisibilityIs(pkg.getTarget("foo_public"), "//visibility:public");
    assertVisibilityIs(pkg.getTarget("foo_private"), "//lib:__pkg__");
    // The visibility concatenation operation does not add any label that would duplicate an
    // existing one. (Note that we can't eliminate *all* possible redundancy, since the visibility
    // list's semantics depend on expanding package_groups.)
    assertVisibilityIs(pkg.getTarget("foo_selfvisible"), "//lib:__pkg__");
  }

  @Test
  public void testDeclarationVisibilityUnioning_appliesToExportsFiles() throws Exception {
    enableMacrosAndUsePrivateVisibility();
    scratch.file("lib/BUILD");
    scratch.file(
        "lib/macro.bzl",
        """
        def _impl(name, visibility):
            native.exports_files([name + "_exported"])
            native.exports_files([name + "_internal"], visibility = ["//visibility:private"])
        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//lib:macro.bzl", "my_macro")

        my_macro(name = "foo")
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertVisibilityIs(pkg.getTarget("foo_exported"), "//visibility:public");
    assertVisibilityIs(pkg.getTarget("foo_internal"), "//lib:__pkg__");
  }

  @Test
  public void testDeclarationVisibilityUnioning_hasNoEffectOnPackageGroups() throws Exception {
    enableMacrosAndUsePrivateVisibility();
    scratch.file("lib/BUILD");
    scratch.file(
        "lib/macro.bzl",
        """
        def _impl(name, visibility):
            native.package_group(name = name)
        my_macro = macro(implementation = _impl)
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//lib:macro.bzl", "my_macro")

        my_macro(name = "foo")
        """);

    Package pkg = loadPackageAndAssertSuccess("pkg");
    assertVisibilityIs(pkg.getTarget("foo"), "//visibility:public");
  }

  @Test
  public void testDeclarationVisibilityUnioning_failsGracefullyOnInvalidVisibility()
      throws Exception {
    enableMacrosAndUsePrivateVisibility();
    scratch.file("lib/BUILD");
    scratch.file(
        "lib/macro.bzl",
        """
        def _impl(name, visibility):
            native.cc_library(
                name = name,
                visibility = ["//visibility:not_a_valid_specifier"],
            )
        my_macro = macro(implementation = _impl)
        """);
    expectEvalError(
        "//pkg:foo Invalid visibility label '//visibility:not_a_valid_specifier'",
        """
        load("//lib:macro.bzl", "my_macro")

        my_macro(name = "foo")
        """);
  }

  @Test
  public void testGlobPatternExtractor() throws Exception {
    StarlarkFile file =
        StarlarkFile.parse(
            ParserInput.fromLines(
                "pattern = '*'",
                "some_variable = glob([",
                "  '**/*',",
                "  'a' + 'b',",
                "  pattern,",
                "])",
                "other_variable = glob(include = ['a'], exclude = ['b'])",
                "third_variable = glob(['c'], exclude_directories = 0)"));
    List<String> globs = new ArrayList<>();
    List<String> globsWithDirs = new ArrayList<>();
    List<String> subpackages = new ArrayList<>();
    PackageFactory.checkBuildSyntax(file, globs, globsWithDirs, subpackages, new HashMap<>());
    assertThat(globs).containsExactly("ab", "a", "**/*");
    assertThat(globsWithDirs).containsExactly("c");
    assertThat(subpackages).isEmpty();
  }

  // Tests of BUILD file dialect checks:

  @Test
  public void testDefInBuild() throws Exception {
    checkBuildDialectError(
        "def func(): pass", //
        "functions may not be defined in BUILD files");
  }

  @Test
  public void testLambdaInBuild() throws Exception {
    checkBuildDialectError(
        "lambda: None", //
        "functions may not be defined in BUILD files");
  }

  @Test
  public void testForStatementForbiddenInBuild() throws Exception {
    checkBuildDialectError(
        "for _ in []: pass", //
        "`for` statements are not allowed in BUILD files");
  }

  @Test
  public void testIfStatementForbiddenInBuild() throws Exception {
    checkBuildDialectError(
        "if False: pass", //
        "`if` statements are not allowed in BUILD files");
  }

  @Test
  public void testKwargsForbiddenInBuild() throws Exception {
    checkBuildDialectError(
        "print(**dict)", //
        "**kwargs arguments are not allowed in BUILD files");
    checkBuildDialectError(
        "len(dict(**{'a': 1}))", //
        "**kwargs arguments are not allowed in BUILD files");
  }

  @Test
  public void testArgsForbiddenInBuild() throws Exception {
    checkBuildDialectError(
        "print(*['a'])", //
        "*args arguments are not allowed in BUILD files");
  }

  // Asserts that evaluation of the specified BUILD file produces the expected error.
  // Modifies: scratch, events, packages; be careful when calling more than once per @Test!
  private void checkBuildDialectError(String content, String expectedError) throws Exception {
    eventCollector.clear();
    reporter.removeHandler(failFastHandler);
    scratch.overwriteFile("p/BUILD", content);
    invalidatePackages();
    Package pkg = loadPackage("p");
    assertContainsEvent(expectedError);
    assertThat(pkg.containsErrors()).isTrue();
  }

  private Package expectEvalSuccess(String... content) throws Exception {
    scratch.file("pkg/BUILD", content);
    Package pkg = loadPackage("pkg");
    assertThat(pkg.containsErrors()).isFalse();
    return pkg;
  }

  private void expectEvalError(String expectedError, String... content) throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("pkg/BUILD", content);
    Package pkg = loadPackage("pkg");
    assertWithMessage("Expected evaluation error, but none was not reported")
        .that(pkg.containsErrors())
        .isTrue();
    assertContainsEvent(expectedError);
  }

  private static AttributeMap attributes(Rule rule) {
    return RawAttributeMapper.of(rule);
  }

  private static void assertGlob(Package pkg, List<String> expected, String... include)
      throws Exception {
    assertGlob(pkg, expected, ImmutableList.copyOf(include), ImmutableList.of());
  }

  private static void assertGlob(
      Package pkg, List<String> expected, List<String> include, List<String> exclude)
      throws Exception {
    ExecutorService executorService = Executors.newFixedThreadPool(10);
    try {
      GlobCache globCache =
          new GlobCache(
              pkg.getFilename().asPath().getParentDirectory(),
              pkg.getPackageIdentifier(),
              IgnoredSubdirectories.EMPTY,
              // a package locator that finds no packages
              new CachingPackageLocator() {
                @Override
                public Path getBuildFileForPackage(PackageIdentifier packageName) {
                  return null;
                }

                @Override
                public String getBaseNameForLoadedPackage(PackageIdentifier packageName) {
                  return null;
                }
              },
              SyscallCache.NO_CACHE,
              executorService,
              -1,
              ThreadStateReceiver.NULL_INSTANCE);
      assertThat(globCache.globUnsorted(include, exclude, Globber.Operation.FILES_AND_DIRS, true))
          .containsExactlyElementsIn(expected);
    } finally {
      executorService.shutdownNow();
    }
  }

  private Path emptyFile(String path) {
    try {
      return scratch.file(path);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /********************************************************************
   *                                                                  *
   *              Test "glob" function in build language              *
   *                                                                  *
   ********************************************************************/
  private void assertGlobFails(String globCallExpression, String expectedError) throws Exception {
    Package pkg = buildPackageWithGlob(globCallExpression);

    assertContainsEvent(expectedError);
    assertThat(pkg.containsErrors()).isTrue();
  }

  private Package buildPackageWithGlob(String globCallExpression) throws Exception {
    scratch.deleteFile("dummypackage/BUILD");
    scratch.file("dummypackage/BUILD", "x = " + globCallExpression);
    return loadPackage("dummypackage");
  }

  /**
   * Test globbing in the context of a package, using the build language. We use the specially setup
   * "globs" test package and the files beneath it.
   *
   * @param result the expected list of filenames that match the glob
   * @param includes an include pattern for the glob
   * @param excludes an exclude pattern for the glob
   * @param excludeDirs an exclude_directories flag for the glob
   * @throws Exception if the glob doesn't match the expected result.
   */
  private void assertGlobMatches(
      List<String> result, List<String> includes, List<String> excludes, boolean excludeDirs)
      throws Exception {
    // If the glob doesn't match the expected result, BUILD execution calls fail() which
    // posts an ERROR to the fail-fast handler, throwing AssertionError.
    Package pkg =
        evaluateGlob(
            includes,
            excludes,
            excludeDirs,
            String.format(
                "(result == sorted(%s)) or fail('incorrect glob result: got %%s, want %%s' %%"
                    + " (result, sorted(%s)))",
                Starlark.repr(result), Starlark.repr(result)));
    // Execution succeeded. Assert that there were no other errors in the package.
    assertThat(pkg.containsErrors()).isFalse();
  }

  /**
   * Evaluate a glob() call against a test directory and BUILD code to process the results.
   *
   * @param includes a list of glob patterns; glob will include these files.
   * @param excludes a list of glob patterns to exclude even if previously included.
   * @param excludeDirs true if directories should be excluded from the match.
   * @param resultAssertion code in the BUILD language that can access the variable result, to which
   *     the result of the glob will be bound, and that may contain an assertion on it.
   * @throws AssertionError if any ERROR events are reported to the fail-fast handler during
   *     execution.
   */
  // TODO(adonovan): these tests would be cleaner if they did print(glob(...)) as a side effect
  // of package loading so that the caller of loadPackage can extract and return the value,
  // for @Test methods to make assertions in the usual way.
  private Package evaluateGlob(
      List<String> includes, List<String> excludes, boolean excludeDirs, String resultAssertion)
      throws Exception {
    Path globsDir = scratch.dir("globs");
    globsDir.getChild("subdir").createDirectory();
    for (String file : ImmutableList.of("Wombat1.java", "Wombat2.java", "subdir/Wombat3.java")) {
      FileSystemUtils.createEmptyFile(globsDir.getRelative(file));
    }
    scratch.file(
        "globs/BUILD",
        String.format(
            "result = glob(%s, exclude=%s, exclude_directories=%d, allow_empty = True)",
            Starlark.repr(includes), Starlark.repr(excludes), excludeDirs ? 1 : 0),
        resultAssertion);
    return loadPackage("globs");
  }

  private void assertGlobProducesError(String pattern, boolean errorExpected) throws Exception {
    reporter.removeHandler(failFastHandler);
    Package pkg = evaluateGlob(ImmutableList.of(pattern), ImmutableList.of(), false, "");
    assertThat(pkg.containsErrors()).isEqualTo(errorExpected);
    boolean foundError = false;
    for (Event event : eventCollector) {
      if (event.getMessage().contains("glob")) {
        if (!errorExpected) {
          fail("error not expected for glob pattern " + pattern + ", but got: " + event);
          return;
        }
        foundError = errorExpected;
        break;
      }
    }
    assertThat(foundError).isEqualTo(errorExpected);
  }

  private Package loadPackage(String pkgid) throws Exception {
    return getTarget("//" + pkgid + ":BUILD").getPackage();
  }

  private Package loadPackageAndAssertSuccess(String pkgid) throws Exception {
    Package pkg = loadPackage(pkgid);
    assertThat(pkg.containsErrors()).isFalse();
    return pkg;
  }
}
