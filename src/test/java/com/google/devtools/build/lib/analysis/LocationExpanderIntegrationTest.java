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

package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Root;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for {@link LocationExpander}. */
@RunWith(JUnit4.class)
public class LocationExpanderIntegrationTest extends BuildViewTestCase {

  @Before
  public void createFiles() throws Exception {
    // Set up a rule to test expansion in.
    scratch.file("files/fileA");
    scratch.file("files/fileB");

    scratch.file(
        "files/BUILD",
        """
        filegroup(
            name = "files",
            srcs = [
                "fileA",
                "fileB",
            ],
        )

        filegroup(
            name = "lib",
            srcs = [":files"],
        )
        """);
  }

  private LocationExpander makeExpander(String label) throws Exception {
    ConfiguredTarget target = getConfiguredTarget(label);
    RuleContext ruleContext = getRuleContext(target);
    return LocationExpander.withRunfilesPaths(ruleContext, null);
  }

  @Test
  public void testLocations() throws Exception {
    // Smoke test
    LocationExpander expander = makeExpander("//files:lib");
    String input = "foo $(locations :files) bar";
    String result = expander.expand(input);

    assertThat(result).isEqualTo("foo files/fileA files/fileB bar");
  }

  @Test
  public void testLocationAlias() throws Exception {
    scratch.file(
        "alias/BUILD",
        """
        alias(
            name = "files_alias",
            actual = "//files:files",
        )

        filegroup(
            name = "lib",
            srcs = [":files_alias"],
        )
        """);

    LocationExpander expander = makeExpander("//alias:lib");

    // Verifies expansion of $(locations) is the same for target and its alias
    String locationTarget = "foo $(locations //files:files) bar";
    String locationAlias = "foo $(locations :files_alias) bar";

    assertThat(expander.expand(locationTarget)).isEqualTo("foo files/fileA files/fileB bar");
    assertThat(expander.expand(locationAlias)).isEqualTo("foo files/fileA files/fileB bar");
  }

  @Test
  public void testLocationAliasAlias() throws Exception {
    scratch.file(
        "alias/BUILD",
        """
        alias(
            name = "files_alias",
            actual = "//files:files",
        )

        alias(
            name = "files_alias_alias",
            actual = ":files_alias",
        )

        filegroup(
            name = "lib",
            srcs = [":files_alias_alias"],
        )
        """);

    LocationExpander expander = makeExpander("//alias:lib");

    // Verifies expansion of $(locations) is the same for target and alias of its alias
    String locationTarget = "foo $(locations //files:files) bar";
    String locationAliasAlias = "foo $(locations :files_alias_alias) bar";

    assertThat(expander.expand(locationTarget)).isEqualTo("foo files/fileA files/fileB bar");
    assertThat(expander.expand(locationAliasAlias)).isEqualTo("foo files/fileA files/fileB bar");
  }

  @Test
  public void locations_spaces() throws Exception {
    scratch.file("spaces/file with space A");
    scratch.file("spaces/file with space B");
    scratch.file(
        "spaces/BUILD",
        """
        filegroup(
            name = "files",
            srcs = [
                "file with space A",
                "file with space B",
            ],
        )

        filegroup(
            name = "lib",
            srcs = [":files"],
        )
        """);

    LocationExpander expander = makeExpander("//spaces:lib");
    String input = "foo $(locations :files) bar";
    String result = expander.expand(input);

    assertThat(result).isEqualTo("foo 'spaces/file with space A' 'spaces/file with space B' bar");
  }

  @Test
  public void otherPathExpansion() throws Exception {
    scratch.file(
        "expansion/BUILD",
        """
        genrule(
            name = "foo",
            outs = ["foo.txt"],
            cmd = "never executed",
        )

        filegroup(
            name = "lib",
            srcs = [":foo"],
        )
        """);
    scratch.overwriteFile("MODULE.bazel", "module(name='workspace')");
    // Invalidate WORKSPACE to pick up the name.
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));

    LocationExpander expander = makeExpander("//expansion:lib");
    assertThat(expander.expand("foo $(execpath :foo) bar"))
        .matches("foo .*-out/.*/expansion/foo\\.txt bar");
    assertThat(expander.expand("foo $(execpaths :foo) bar"))
        .matches("foo .*-out/.*/expansion/foo\\.txt bar");
    assertThat(expander.expand("foo $(rootpath :foo) bar"))
        .matches("foo expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rootpaths :foo) bar"))
        .matches("foo expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpath :foo) bar"))
        .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpaths :foo) bar"))
        .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpath //expansion:foo) bar"))
        .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpaths //expansion:foo) bar"))
        .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpath @//expansion:foo) bar"))
        .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpaths @//expansion:foo) bar"))
        .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpath @workspace//expansion:foo) bar"))
        .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpaths @workspace//expansion:foo) bar"))
        .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar");
  }

  @Test
  public void otherPathExternalExpansionLegacyExternalRunfiles() throws Exception {
    scratch.file("expansion/BUILD", "filegroup(name='lib', srcs=['@r//p:foo'])");
    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'r')",
        "local_path_override(module_name = 'r', path = '/r')");

    // Invalidate WORKSPACE so @r can be resolved.
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));

    scratch.resolve("/foo/bar").createDirectoryAndParents();
    scratch.file("/r/MODULE.bazel", "module(name = 'r')");
    scratch.file("/r/p/BUILD", "genrule(name='foo', outs=['foo.txt'], cmd='never executed')");

    useConfiguration("--legacy_external_runfiles");
    LocationExpander expander = makeExpander("//expansion:lib");
    assertThat(expander.expand("foo $(execpath @r//p:foo) bar"))
        .matches("foo .*-out/.*/external/r\\+/p/foo\\.txt bar");
    assertThat(expander.expand("foo $(execpaths @r//p:foo) bar"))
        .matches("foo .*-out/.*/external/r\\+/p/foo\\.txt bar");
    assertThat(expander.expand("foo $(rootpath @r//p:foo) bar"))
        .matches("foo external/r\\+/p/foo.txt bar");
    assertThat(expander.expand("foo $(rootpaths @r//p:foo) bar"))
        .matches("foo external/r\\+/p/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpath @r//p:foo) bar"))
        .isEqualTo("foo r+/p/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpath @r//p:foo) bar"))
        .isEqualTo("foo r+/p/foo.txt bar");
  }

  @Test
  public void otherPathExternalExpansionNoLegacyExternalRunfiles() throws Exception {
    scratch.file("expansion/BUILD", "filegroup(name='lib', srcs=['@r//p:foo'])");
    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'r')",
        "local_path_override(module_name = 'r', path = '/r')");

    // Invalidate WORKSPACE so @r can be resolved.
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));

    scratch.resolve("/foo/bar").createDirectoryAndParents();
    scratch.file("/r/MODULE.bazel", "module(name = 'r')");
    scratch.file("/r/p/BUILD", "genrule(name='foo', outs=['foo.txt'], cmd='never executed')");

    useConfiguration("--nolegacy_external_runfiles");
    LocationExpander expander = makeExpander("//expansion:lib");
    assertThat(expander.expand("foo $(execpath @r//p:foo) bar"))
        .matches("foo .*-out/.*/external/r\\+/p/foo\\.txt bar");
    assertThat(expander.expand("foo $(execpaths @r//p:foo) bar"))
        .matches("foo .*-out/.*/external/r\\+/p/foo\\.txt bar");
    assertThat(expander.expand("foo $(rootpath @r//p:foo) bar"))
        .matches("foo ../r\\+/p/foo.txt bar");
    assertThat(expander.expand("foo $(rootpaths @r//p:foo) bar"))
        .matches("foo ../r\\+/p/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpath @r//p:foo) bar"))
        .isEqualTo("foo r+/p/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpath @r//p:foo) bar"))
        .isEqualTo("foo r+/p/foo.txt bar");
  }

  @Test
  public void otherPathExternalExpansionNoLegacyExternalRunfilesSiblingRepositoryLayout()
      throws Exception {
    scratch.file("expansion/BUILD", "filegroup(name='lib', srcs=['@r//p:foo'])");
    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'r')",
        "local_path_override(module_name = 'r', path = '/r')");

    // Invalidate WORKSPACE so @r can be resolved.
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));

    scratch.resolve("/foo/bar").createDirectoryAndParents();
    scratch.file("/r/MODULE.bazel", "module(name = 'r')");
    scratch.file("/r/p/BUILD", "genrule(name='foo', outs=['foo.txt'], cmd='never executed')");

    useConfiguration("--nolegacy_external_runfiles");
    setBuildLanguageOptions("--experimental_sibling_repository_layout");
    LocationExpander expander = makeExpander("//expansion:lib");
    assertThat(expander.expand("foo $(execpath @r//p:foo) bar"))
        .matches("foo .*-out/r\\+/.*/p/foo\\.txt bar");
    assertThat(expander.expand("foo $(execpaths @r//p:foo) bar"))
        .matches("foo .*-out/r\\+/.*/p/foo\\.txt bar");
    assertThat(expander.expand("foo $(rootpath @r//p:foo) bar"))
        .matches("foo ../r\\+/p/foo.txt bar");
    assertThat(expander.expand("foo $(rootpaths @r//p:foo) bar"))
        .matches("foo ../r\\+/p/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpath @r//p:foo) bar"))
        .isEqualTo("foo r+/p/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpaths @r//p:foo) bar"))
        .isEqualTo("foo r+/p/foo.txt bar");
  }

  @Test
  public void otherPathMultiExpansion() throws Exception {
    scratch.file(
        "expansion/BUILD",
        """
        genrule(
            name = "foo",
            outs = [
                "foo.txt",
                "bar.txt",
            ],
            cmd = "never executed",
        )

        filegroup(
            name = "lib",
            srcs = [":foo"],
        )
        """);

    LocationExpander expander = makeExpander("//expansion:lib");
    assertThat(expander.expand("foo $(execpaths :foo) bar"))
        .matches("foo .*-out/.*/expansion/bar\\.txt .*-out/.*/expansion/foo\\.txt bar");
    assertThat(expander.expand("foo $(rootpaths :foo) bar"))
        .matches("foo expansion/bar.txt expansion/foo.txt bar");
    assertThat(expander.expand("foo $(rlocationpaths :foo) bar"))
        .isEqualTo(
            "foo __main__/expansion/bar.txt __main__/expansion/foo.txt bar"
                .replace("__main__", ruleClassProvider.getRunfilesPrefix()));
  }
}
