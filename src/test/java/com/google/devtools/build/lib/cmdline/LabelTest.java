// Copyright 2015 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.cmdline;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Tables;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.cmdline.Label.PackageContext;
import com.google.devtools.build.lib.cmdline.Label.RepoContext;
import com.google.devtools.build.lib.cmdline.Label.RepoMappingRecorder;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.regex.Pattern;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Label}. */
@RunWith(JUnit4.class)
public class LabelTest {

  private static final String INVALID_TARGET_NAME = "invalid target name";
  private static final String INVALID_PACKAGE_NAME = "invalid package name";

  @Test
  public void testAbsolute() throws Exception {
    {
      Label l = Label.parseCanonical("//foo/bar:baz");
      assertThat(l.getPackageName()).isEqualTo("foo/bar");
      assertThat(l.getName()).isEqualTo("baz");
    }
    {
      Label l = Label.parseCanonical("//foo/bar");
      assertThat(l.getPackageName()).isEqualTo("foo/bar");
      assertThat(l.getName()).isEqualTo("bar");
    }
    {
      Label l = Label.parseCanonical("//:bar");
      assertThat(l.getPackageName()).isEmpty();
      assertThat(l.getName()).isEqualTo("bar");
    }
    {
      Label l = Label.parseCanonical("@foo");
      assertThat(l.getRepository().getName()).isEqualTo("foo");
      assertThat(l.getPackageName()).isEmpty();
      assertThat(l.getName()).isEqualTo("foo");
    }
    {
      Label l = Label.parseCanonical("@foo//bar");
      assertThat(l.getRepository().getName()).isEqualTo("foo");
      assertThat(l.getPackageName()).isEqualTo("bar");
      assertThat(l.getName()).isEqualTo("bar");
    }
    {
      Label l = Label.parseCanonical("@@foo//bar");
      assertThat(l.getRepository().getName()).isEqualTo("foo");
      assertThat(l.getPackageName()).isEqualTo("bar");
      assertThat(l.getName()).isEqualTo("bar");
    }
    {
      Label l = Label.parseCanonical("//@foo");
      assertThat(l.getRepository()).isEqualTo(RepositoryName.MAIN);
      assertThat(l.getPackageName()).isEqualTo("@foo");
      assertThat(l.getName()).isEqualTo("@foo");
    }
    {
      Label l = Label.parseCanonical("//xyz/@foo:abc");
      assertThat(l.getRepository()).isEqualTo(RepositoryName.MAIN);
      assertThat(l.getPackageName()).isEqualTo("xyz/@foo");
      assertThat(l.getName()).isEqualTo("abc");
    }
  }

  @Test
  public void parseWithRepoContext() throws Exception {
    RepositoryName foo = RepositoryName.createUnvalidated("foo");
    RepositoryName bar = RepositoryName.createUnvalidated("bar");
    RepositoryName quux = RepositoryName.createUnvalidated("quux");
    RepoContext repoContext =
        RepoContext.of(foo, RepositoryMapping.create(ImmutableMap.of("bar", quux), foo));
    {
      Label l = Label.parseWithRepoContext("//lol:kek", repoContext);
      assertThat(l.getRepository()).isEqualTo(foo);
      assertThat(l.getPackageName()).isEqualTo("lol");
      assertThat(l.getName()).isEqualTo("kek");
    }
    {
      Label l = Label.parseWithRepoContext("@bar//lol:kek", repoContext);
      assertThat(l.getRepository()).isEqualTo(quux);
      assertThat(l.getPackageName()).isEqualTo("lol");
      assertThat(l.getName()).isEqualTo("kek");
    }
    {
      Label l = Label.parseWithRepoContext("@@bar//lol:kek", repoContext);
      assertThat(l.getRepository()).isEqualTo(bar);
      assertThat(l.getPackageName()).isEqualTo("lol");
      assertThat(l.getName()).isEqualTo("kek");
    }
    {
      Label l = Label.parseWithRepoContext("@quux//lol:kek", repoContext);
      assertThat(l.getRepository()).isEqualTo(quux.toNonVisible(foo));
      assertThat(l.getPackageName()).isEqualTo("lol");
      assertThat(l.getName()).isEqualTo("kek");
    }
  }

  @Test
  public void parseWithPackageContext() throws Exception {
    RepositoryName foo = RepositoryName.createUnvalidated("foo");
    RepositoryName bar = RepositoryName.createUnvalidated("bar");
    RepositoryName quux = RepositoryName.createUnvalidated("quux");
    PackageContext packageContext =
        PackageContext.of(
            PackageIdentifier.create(foo, PathFragment.create("hah")),
            RepositoryMapping.create(ImmutableMap.of("bar", quux), foo));
    {
      Label l = Label.parseWithPackageContext(":kek", packageContext);
      assertThat(l.getRepository()).isEqualTo(foo);
      assertThat(l.getPackageName()).isEqualTo("hah");
      assertThat(l.getName()).isEqualTo("kek");
    }
    {
      Label l = Label.parseWithPackageContext("//lol:kek", packageContext);
      assertThat(l.getRepository()).isEqualTo(foo);
      assertThat(l.getPackageName()).isEqualTo("lol");
      assertThat(l.getName()).isEqualTo("kek");
    }
    {
      Label l = Label.parseWithPackageContext("@bar//lol:kek", packageContext);
      assertThat(l.getRepository()).isEqualTo(quux);
      assertThat(l.getPackageName()).isEqualTo("lol");
      assertThat(l.getName()).isEqualTo("kek");
    }
    {
      Label l = Label.parseWithPackageContext("@@bar//lol:kek", packageContext);
      assertThat(l.getRepository()).isEqualTo(bar);
      assertThat(l.getPackageName()).isEqualTo("lol");
      assertThat(l.getName()).isEqualTo("kek");
    }
    {
      Label l = Label.parseWithPackageContext("@quux//lol:kek", packageContext);
      assertThat(l.getRepository()).isEqualTo(quux.toNonVisible(foo));
      assertThat(l.getPackageName()).isEqualTo("lol");
      assertThat(l.getName()).isEqualTo("kek");
    }
  }

  @Test
  public void testFactory() throws Exception {
    Label l = Label.create("foo/bar", "quux");
    assertThat(l.getPackageName()).isEqualTo("foo/bar");
    assertThat(l.getName()).isEqualTo("quux");
  }

  @Test
  public void testIdentities() throws Exception {

    Label l1 = Label.parseCanonical("//foo/bar:baz");
    Label l2 = Label.parseCanonical("//foo/bar:baz");
    Label l3 = Label.parseCanonical("//foo/bar:quux");

    new EqualsTester().addEqualityGroup(l1, l2).addEqualityGroup(l3).testEquals();
  }

  @Test
  public void testToString() throws Exception {
    {
      String s = "@//foo/bar:baz";
      Label l = Label.parseCanonical(s);
      assertThat(l.toString()).isEqualTo("//foo/bar:baz");
    }
    {
      Label l = Label.parseCanonical("//foo/bar");
      assertThat(l.toString()).isEqualTo("//foo/bar:bar");
    }
    {
      Label l = Label.parseCanonical("@foo");
      assertThat(l.toString()).isEqualTo("@@foo//:foo");
    }
  }

  @Test
  public void testDotDot() throws Exception {
    Label.parseCanonical("//foo/bar:baz..gif");
  }

  /**
   * Asserts that creating a label throws a SyntaxException.
   *
   * @param label the label to create.
   */
  private static void assertSyntaxError(String expectedError, String label) {
    LabelSyntaxException e =
        assertThrows(
            "Label '" + label + "' did not contain a syntax error, but was expected to",
            LabelSyntaxException.class,
            () -> Label.parseCanonical(label));
    assertThat(e).hasMessageThat().containsMatch(Pattern.quote(expectedError));
  }

  @Test
  public void testBadCharacters() throws Exception {
    assertSyntaxError("target names may not contain ':'", "//foo:bar:baz");
    assertSyntaxError("target names may not contain ':'", "//foo:bar:");
    assertSyntaxError("target names may not contain ':'", "//foo/bar::");
  }

  @Test
  public void testUplevelReferences() throws Exception {
    assertSyntaxError(INVALID_PACKAGE_NAME, "//foo/bar/..:baz");
    assertSyntaxError(INVALID_PACKAGE_NAME, "//foo/../baz:baz");
    assertSyntaxError(INVALID_PACKAGE_NAME, "//../bar/baz:baz");
    assertSyntaxError(INVALID_PACKAGE_NAME, "//..:foo");
    assertSyntaxError(INVALID_TARGET_NAME, "//foo:bar/../baz");
    assertSyntaxError(INVALID_TARGET_NAME, "//foo:../bar/baz");
    assertSyntaxError(INVALID_TARGET_NAME, "//foo:bar/baz/..");
    assertSyntaxError(INVALID_TARGET_NAME, "//foo:..");
  }

  @Test
  public void testDotAsAPathSegment() throws Exception {
    assertSyntaxError(INVALID_PACKAGE_NAME, "//foo/bar/.:baz");
    assertSyntaxError(INVALID_PACKAGE_NAME, "//foo/./baz:baz");
    assertSyntaxError(INVALID_PACKAGE_NAME, "//./bar/baz:baz");
    assertSyntaxError(INVALID_TARGET_NAME, "//foo:bar/./baz");
    assertSyntaxError(INVALID_TARGET_NAME, "//foo:./bar/baz");
    // TODO(bazel-team): enable when we have removed the "Workaround" in Label
    // that rewrites broken Labels by removing the trailing '.'
    // assertSyntaxError(INVALID_PACKAGE_NAME,
    //                  "//foo:bar/baz/.");
    // assertSyntaxError(INVALID_PACKAGE_NAME,
    //                  "//foo:.");
  }

  @Test
  public void testTrailingDotSegment() throws Exception {
    assertThat(Label.parseCanonical("//foo:dir")).isEqualTo(Label.parseCanonical("//foo:dir/."));
  }

  @Test
  public void testSomeOtherBadLabels() throws Exception {
    assertSyntaxError("package names may not end with '/'", "//foo/:bar");
    assertSyntaxError("package names may not start with '/'", "///p:foo");
    assertSyntaxError("package names may not contain '//' path separators", "//a//b:foo");
  }

  @Test
  public void testSomeGoodLabels() throws Exception {
    Label.parseCanonical("//foo:..bar");
    Label.parseCanonical("//Foo:..bar");
    Label.parseCanonical("//-Foo:..bar");
    Label.parseCanonical("//00:..bar");
    Label.parseCanonical("//package:foo+bar");
    Label.parseCanonical("//package:foo_bar");
    Label.parseCanonical("//package:foo=bar");
    Label.parseCanonical("//package:foo-bar");
    Label.parseCanonical("//package:foo.bar");
    Label.parseCanonical("//package:foo@bar");
    Label.parseCanonical("//package:foo~bar");
    Label.parseCanonical("//$( ):$( )");
  }

  @Test
  public void testDoubleSlashPathSeparator() throws Exception {
    assertSyntaxError("package names may not contain '//' path separators", "//foo//bar:baz");
    assertSyntaxError("target names may not contain '//' path separator", "//foo:bar//baz");
  }

  @Test
  public void testNonPrintableCharacters() throws Exception {
    assertSyntaxError(
        "target names may not contain non-printable characters: '\\x02'", "//foo:..\002bar");
  }

  /** Make sure that control characters - such as CR - are escaped on output. */
  @Test
  public void testInvalidLineEndings() throws Exception {
    assertSyntaxError(
        "invalid target name '..bar\\r': " + "target names may not end with carriage returns",
        "//foo:..bar\r");
  }

  @Test
  public void testEmptyName() throws Exception {
    assertSyntaxError("invalid target name '': empty target name", "//foo/bar:");
  }

  @Test
  public void testRepoLabel() throws Exception {
    Label label = Label.parseCanonical("@foo//bar/baz:bat/boo");
    assertThat(label.toString()).isEqualTo("@@foo//bar/baz:bat/boo");
  }

  @Test
  public void testNoRepo() throws Exception {
    Label label = Label.parseCanonical("//bar/baz:bat/boo");
    assertThat(label.toString()).isEqualTo("//bar/baz:bat/boo");
  }

  @Test
  public void testInvalidRepo() throws Exception {
    LabelSyntaxException e =
        assertThrows(
            LabelSyntaxException.class, () -> Label.parseCanonical("foo//bar/baz:bat/boo"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "invalid package name 'foo//bar/baz': package names may not contain '//' path"
                + " separators");
  }

  @Test
  public void testInvalidRepoWithColon() throws Exception {
    LabelSyntaxException e =
        assertThrows(LabelSyntaxException.class, () -> Label.parseCanonical("@foo:xyz"));
    assertThat(e)
        .hasMessageThat()
        .containsMatch("invalid repository name 'foo:xyz': repo names may contain only");
  }

  @Test
  public void testGetWorkspaceRoot() throws Exception {
    Label label = Label.parseCanonical("//bar/baz");
    assertThat(label.getWorkspaceRootForStarlarkOnly(StarlarkSemantics.DEFAULT)).isEmpty();
    label = Label.parseCanonical("@repo//bar/baz");
    assertThat(label.getWorkspaceRootForStarlarkOnly(StarlarkSemantics.DEFAULT))
        .isEqualTo("external/repo");
  }

  @Test
  public void testGetContainingDirectory() {
    assertThat(Label.getContainingDirectory(Label.parseCanonicalUnchecked("//a:b")))
        .isEqualTo(PathFragment.create("a"));
    assertThat(Label.getContainingDirectory(Label.parseCanonicalUnchecked("//a/b:c")))
        .isEqualTo(PathFragment.create("a/b"));
    assertThat(Label.getContainingDirectory(Label.parseCanonicalUnchecked("//a:b/c")))
        .isEqualTo(PathFragment.create("a/b"));
    assertThat(Label.getContainingDirectory(Label.parseCanonicalUnchecked("//a/b/c")))
        .isEqualTo(PathFragment.create("a/b/c"));
  }

  @Test
  public void testWorkspaceName() throws Exception {
    assertThat(Label.parseCanonical("@foo//bar:baz").getWorkspaceName()).isEqualTo("foo");
    assertThat(Label.parseCanonical("//bar:baz").getWorkspaceName()).isEmpty();
    assertThat(Label.parseCanonical("@//bar:baz").getWorkspaceName()).isEmpty();
  }

  @Test
  public void testUnambiguousCanonicalForm() throws Exception {
    assertThat(Label.parseCanonical("//foo/bar:baz").getUnambiguousCanonicalForm())
        .isEqualTo("@@//foo/bar:baz");
    assertThat(Label.parseCanonical("@foo//bar:baz").getUnambiguousCanonicalForm())
        .isEqualTo("@@foo//bar:baz");
    assertThat(
            Label.create(
                    PackageIdentifier.create(
                        RepositoryName.create("foo").toNonVisible(RepositoryName.create("bar")),
                        PathFragment.create("baz")),
                    "quux")
                .getUnambiguousCanonicalForm())
        .isEqualTo("@@[unknown repo 'foo' requested from @@bar]//baz:quux");
  }

  private static String displayFormFor(String rawLabel, RepositoryMapping repositoryMapping)
      throws Exception {
    return Label.parseCanonical(rawLabel).getDisplayForm(repositoryMapping);
  }

  @Test
  public void testDisplayForm() throws Exception {
    RepositoryName canonicalName = RepositoryName.create("canonical");
    RepositoryMapping repositoryMapping =
        RepositoryMapping.create(
            ImmutableMap.of("", RepositoryName.MAIN, "local", canonicalName), RepositoryName.MAIN);

    assertThat(displayFormFor("//foo/bar:bar", repositoryMapping)).isEqualTo("//foo/bar:bar");
    assertThat(displayFormFor("//foo/bar:baz", repositoryMapping)).isEqualTo("//foo/bar:baz");

    assertThat(displayFormFor("@canonical//bar:bar", repositoryMapping))
        .isEqualTo("@local//bar:bar");
    assertThat(displayFormFor("@canonical//bar:baz", repositoryMapping))
        .isEqualTo("@local//bar:baz");
    assertThat(displayFormFor("@canonical//:canonical", repositoryMapping))
        .isEqualTo("@local//:canonical");
    assertThat(displayFormFor("@canonical//:local", repositoryMapping)).isEqualTo("@local//:local");

    assertThat(displayFormFor("@other//bar:bar", repositoryMapping)).isEqualTo("@@other//bar:bar");
    assertThat(displayFormFor("@other//bar:baz", repositoryMapping)).isEqualTo("@@other//bar:baz");
    assertThat(displayFormFor("@other//:other", repositoryMapping)).isEqualTo("@@other//:other");
    assertThat(displayFormFor("@@other", repositoryMapping)).isEqualTo("@@other//:other");

    assertThat(
            Label.parseWithRepoContext(
                    "@bad//abc", RepoContext.of(RepositoryName.MAIN, repositoryMapping))
                .getDisplayForm(repositoryMapping))
        .isEqualTo("@@[unknown repo 'bad' requested from @@]//abc:abc");

    assertThat(displayFormFor("@unremapped//:unremapped", RepositoryMapping.ALWAYS_FALLBACK))
        .isEqualTo("@unremapped//:unremapped");
    assertThat(displayFormFor("@unremapped", RepositoryMapping.ALWAYS_FALLBACK))
        .isEqualTo("@unremapped//:unremapped");
  }

  @Test
  public void testDisplayFormNullMapping() throws Exception {
    assertThat(displayFormFor("//foo/bar:bar", null)).isEqualTo("//foo/bar:bar");
    assertThat(displayFormFor("@@foo//bar:bar", null)).isEqualTo("@@foo//bar:bar");
  }

  private static String shorthandDisplayFormFor(
      String rawLabel, RepositoryMapping repositoryMapping) throws Exception {
    return Label.parseCanonical(rawLabel).getShorthandDisplayForm(repositoryMapping);
  }

  @Test
  public void testShorthandDisplayForm() throws Exception {
    RepositoryName canonicalName = RepositoryName.create("canonical");
    RepositoryMapping repositoryMapping =
        RepositoryMapping.create(
            ImmutableMap.of("", RepositoryName.MAIN, "local", canonicalName), RepositoryName.MAIN);

    assertThat(shorthandDisplayFormFor("//foo/bar:bar", repositoryMapping)).isEqualTo("//foo/bar");
    assertThat(shorthandDisplayFormFor("//foo/bar:baz", repositoryMapping))
        .isEqualTo("//foo/bar:baz");

    assertThat(shorthandDisplayFormFor("@canonical//bar:bar", repositoryMapping))
        .isEqualTo("@local//bar");
    assertThat(shorthandDisplayFormFor("@canonical//bar:baz", repositoryMapping))
        .isEqualTo("@local//bar:baz");
    assertThat(shorthandDisplayFormFor("@canonical//:canonical", repositoryMapping))
        .isEqualTo("@local//:canonical");
    assertThat(shorthandDisplayFormFor("@canonical//:local", repositoryMapping))
        .isEqualTo("@local");

    assertThat(shorthandDisplayFormFor("@other//bar:bar", repositoryMapping))
        .isEqualTo("@@other//bar");
    assertThat(shorthandDisplayFormFor("@other//bar:baz", repositoryMapping))
        .isEqualTo("@@other//bar:baz");
    assertThat(shorthandDisplayFormFor("@other//:other", repositoryMapping)).isEqualTo("@@other");
    assertThat(shorthandDisplayFormFor("@@other", repositoryMapping)).isEqualTo("@@other");

    assertThat(
            Label.parseWithRepoContext(
                    "@bad//abc", RepoContext.of(RepositoryName.MAIN, repositoryMapping))
                .getShorthandDisplayForm(repositoryMapping))
        .isEqualTo("@@[unknown repo 'bad' requested from @@]//abc");

    assertThat(
            shorthandDisplayFormFor("@unremapped//:unremapped", RepositoryMapping.ALWAYS_FALLBACK))
        .isEqualTo("@unremapped");
    assertThat(shorthandDisplayFormFor("@unremapped", RepositoryMapping.ALWAYS_FALLBACK))
        .isEqualTo("@unremapped");
  }

  @Test
  public void starlarkStrAndRepr() throws Exception {
    Label label = Label.parseCanonical("//x");
    assertThat(Starlark.str(label, StarlarkSemantics.DEFAULT)).isEqualTo("@@//x:x");
    assertThat(Starlark.repr(label)).isEqualTo("Label(\"//x:x\")");

    label = Label.parseCanonical("@hello//x");
    assertThat(Starlark.str(label, StarlarkSemantics.DEFAULT)).isEqualTo("@@hello//x:x");
    assertThat(Starlark.repr(label)).isEqualTo("Label(\"@@hello//x:x\")");
  }

  @Test
  public void starlarkStr_canonicalLabelLiteral() throws Exception {
    StarlarkSemantics semantics = StarlarkSemantics.DEFAULT;
    assertThat(Starlark.str(Label.parseCanonical("//x"), semantics)).isEqualTo("@@//x:x");
    assertThat(Starlark.str(Label.parseCanonical("@x//y"), semantics)).isEqualTo("@@x//y:y");
  }

  @Test
  public void starlarkStr_ambiguousAndCanonicalLabelLiteral() throws Exception {
    StarlarkSemantics semantics =
        StarlarkSemantics.builder()
            .setBool(BuildLanguageOptions.INCOMPATIBLE_UNAMBIGUOUS_LABEL_STRINGIFICATION, false)
            .build();
    assertThat(Starlark.str(Label.parseCanonical("//x"), semantics)).isEqualTo("//x:x");
    assertThat(Starlark.str(Label.parseCanonical("@x//y"), semantics)).isEqualTo("@@x//y:y");
  }

  @Test
  public void testCodec() throws Exception {
    new SerializationTester(
            Label.parseCanonical("//foo/bar:baz"),
            Label.parseCanonical("@foo"),
            Label.parseCanonical("@@foo//bar"),
            Label.parseCanonical("//xyz/@foo:abc"))
        .setVerificationFunction(
            (original, deserialized) -> assertThat(original).isSameInstanceAs(deserialized))
        .runTests();
  }

  @Test
  public void parseWithPackageContext_recordingRepoMapping() throws Exception {
    RepositoryName foo = RepositoryName.createUnvalidated("foo");
    RepositoryName bar = RepositoryName.createUnvalidated("bar");
    RepositoryName quux = RepositoryName.createUnvalidated("quux");
    PackageContext fooPackageContext =
        PackageContext.of(
            PackageIdentifier.create(foo, PathFragment.create("hah")),
            RepositoryMapping.create(ImmutableMap.of("bar", quux, "quux", bar), foo));
    PackageContext barPackageContext =
        PackageContext.of(
            PackageIdentifier.create(bar, PathFragment.EMPTY_FRAGMENT),
            RepositoryMapping.create(ImmutableMap.of("foo", quux, "quux", foo), bar));

    RepoMappingRecorder recorder = new RepoMappingRecorder();
    // Not recorded: no repo part
    var unused = Label.parseWithPackageContext("//foo/bar", fooPackageContext, recorder);
    // Recorded: <foo, bar, quux>
    unused = Label.parseWithPackageContext("@bar//foo/bar", fooPackageContext, recorder);
    // Recorded: <bar, quux, foo>
    unused = Label.parseWithPackageContext("@quux//foo/bar", barPackageContext, recorder);
    // Recorded: <bar, foo, quux>
    unused = Label.parseWithPackageContext("@foo//foo/bar", barPackageContext, recorder);
    // Not recorded: canonical repo name
    unused = Label.parseWithPackageContext("@@quux//foo/bar", fooPackageContext, recorder);

    // Recorded entries are sorted by row and then column
    assertThat(recorder.recordedEntries().cellSet())
        .containsExactly(
            Tables.immutableCell(bar, "foo", quux),
            Tables.immutableCell(bar, "quux", foo),
            Tables.immutableCell(foo, "bar", quux))
        .inOrder();
  }
}
