// Copyright 2022 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.rules.genrule;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.analysis.CommandHelper;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.util.io.RecordingOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Some integration tests of genrule, including several specifically
 * addressing the treatment of very long command lines whose total
 * length in bytes exceeds CommandBasedConfiguredTarget.maxCommandLength.
 */
@RunWith(JUnit4.class)
public class GenRuleIntegrationTest extends BuildIntegrationTestCase {

  private int savedMaxCommandLength;

  @Before
  public final void setMaxCommandLength() throws Exception  {
    savedMaxCommandLength = CommandHelper.maxCommandLength;
    CommandHelper.maxCommandLength = 40;
  }

  @After
  public final void restoreMaxCommandLength() throws Exception  {
    CommandHelper.maxCommandLength = savedMaxCommandLength;
  }

  private void writeFiles() throws IOException {
    for (int i = 0; i < 10; i++) {
      write("test/input" + i + ".txt", "The number " + i);
    }
    write(
        "test/BUILD",
        """
        # Directly executed with "/bin/bash -c <command>".
        genrule(name = 'gen_small',
                  srcs = [],
                  outs = ['small'],
                  cmd = 'echo Smaller than 40 characters > $@')
        # Executed indirectly via a script file "gen_large.genrule_script.sh",
        # because command length exceeds maxCommandLength.
        genrule(name = 'gen_large',
                  srcs = [],
                  outs = ['large'],
                  cmd = 'echo Larger than 40 characters............................ > $@')
        # Also executed indirectly via a script file,
        # because command length exceeds maxCommandLength,
        # after expansion of $(SRCS).
        genrule(name = 'gen_many_inputs',
                  srcs = glob(['input*.txt']),
                  outs = ['all.txt'],
                  cmd = 'cat $(SRCS) > $@')
        # A more realistic example of indirect execution via a script file.
        # This one is carefully written to avoid overflowing fixed limits,
        # even if $(SRCS) expands to a very long string.
        genrule(name = 'gen_many_inputs2',
                  srcs = glob(['input*.txt']),
                  outs = ['all2.txt'],
                  cmd = '''
        set -x
        > $@
        {
        cat <<EOF
        $(SRCS)
        EOF
        } |
        tr ' ' '\\n' |
        while read file; do cat $$file >> $@; done
        ''')
        """);
  }

  private String getContents(Path outputFile) throws IOException {
    return FileSystemUtils.readContentToString(outputFile);
  }

  @Test
  public void testDirectExecution() throws Exception {
    writeFiles();

    buildTarget("//test:gen_small");
    OutputFileConfiguredTarget output =
        (OutputFileConfiguredTarget) getConfiguredTarget("//test:small");
    assertThat(readToString(output.getArtifact())).isEqualTo("Smaller than 40 characters\n");
  }

  @Test
  public void testSimpleIndirectExecution() throws Exception {
    writeFiles();

    buildTarget("//test:gen_large");
    OutputFileConfiguredTarget output =
        (OutputFileConfiguredTarget) getConfiguredTarget("//test:large");
    assertThat(readToString(output.getArtifact()))
        .isEqualTo("Larger than 40 characters............................\n");

    Path script = output.getArtifact().getPath().getParentDirectory().getRelative(
        "gen_large.genrule_script.sh");
    String scriptContents = getContents(script);
    assertThat(scriptContents).contains("#!/bin/bash\n");
    assertThat(scriptContents).contains("echo Larger than 40 characters");
  }

  private static String cleanNewlines(String input) {
    return input.replaceAll("\r\n", "\n");
  }

  @Test
  public void testComplicatedIndirectExecution() throws Exception {
    writeFiles();

    buildTarget("//test:gen_many_inputs");

    OutputFileConfiguredTarget output =
        (OutputFileConfiguredTarget) getConfiguredTarget("//test:all.txt");
    assertThat(cleanNewlines(readToString(output.getArtifact())))
        .isEqualTo(
            "The number 0\nThe number 1\nThe number 2\nThe number 3\nThe number 4\n"
                + "The number 5\nThe number 6\nThe number 7\nThe number 8\nThe number 9\n");

    Path script = output.getArtifact().getPath().getParentDirectory().getRelative(
        "gen_many_inputs.genrule_script.sh");
    String scriptContents = getContents(script);
    assertThat(scriptContents).contains("#!/bin/bash\n");
    assertThat(scriptContents)
        .containsMatch("cat .*/input0.txt .*/input1.txt .* .*/input9.txt > .*/all.txt");
  }

  @Test
  public void testRealisticIndirectExecution() throws Exception {
    writeFiles();

    RecordingOutErr recordingOutErr = new RecordingOutErr();
    this.outErr = recordingOutErr;

    buildTarget("//test:gen_many_inputs2");

    OutputFileConfiguredTarget output =
        (OutputFileConfiguredTarget) getConfiguredTarget("//test:all2.txt");
    assertThat(cleanNewlines(readToString(output.getArtifact())))
        .isEqualTo(
            "The number 0\nThe number 1\nThe number 2\nThe number 3\nThe number 4\n"
                + "The number 5\nThe number 6\nThe number 7\nThe number 8\nThe number 9\n");

    Path script = output.getArtifact().getPath().getParentDirectory().getRelative(
        "gen_many_inputs2.genrule_script.sh");
    String scriptContents = getContents(script);
    assertThat(scriptContents).contains("#!/bin/bash\n");
    assertThat(scriptContents).containsMatch("cat <<EOF");
    assertThat(scriptContents).containsMatch(".*/input0.txt .*/input1.txt .* .*/input9.txt");

    // Check that we didn't exceed the (supposed) maximum command-line length.
    for (String line : recordingOutErr.errAsLatin1().split("\n")) {
      if (line.startsWith("+")) {
        assertThat(line.length()).isLessThan(40);
      }
    }
  }

  @Test
  public void testToolchains_fromTemplateVariableInfo() throws Exception {
    // Write a rule that generates templated data.
    write(
        "test/template_rule.bzl",
        """
        def _impl(ctx):
            vars = ctx.attr.vars
            return [platform_common.TemplateVariableInfo(vars)]

        template_rule = rule(
            _impl,
            attrs = {
                "vars": attr.string_dict(),
            },
        )
        """);

    // Write a BUILD file that uses the data.
    write(
        "test/BUILD",
        """
        load(":template_rule.bzl", "template_rule")

        template_rule(
            name = "data",
            vars = {
                "foo": "bar",
            },
        )

        genrule(
            name = "g",
            srcs = [],
            outs = ["g.out"],
            cmd = "echo foo: $(foo) > $@",
            toolchains = [":data"],
        )
        """);

    buildTarget("//test:g");
    OutputFileConfiguredTarget output =
        (OutputFileConfiguredTarget) getConfiguredTarget("//test:g.out");
    assertThat(readToString(output.getArtifact())).isEqualTo("foo: bar\n");
  }

  @Test
  public void testToolchains_fromToolchain() throws Exception {
    // Write a toolchain rule that generates templated data.
    write(
        "test/toolchain/template_toolchain.bzl",
        """
        def _impl(ctx):
            vars = ctx.attr.vars
            return [
                platform_common.TemplateVariableInfo(vars),
                platform_common.ToolchainInfo(data = "from " + ctx.label.name),
            ]

        template_toolchain = rule(
            _impl,
            attrs = {
                "vars": attr.string_dict(),
            },
        )
        """);
    write(
        "test/toolchain/BUILD",
        """
        load(":template_toolchain.bzl", "template_toolchain")

        toolchain_type(name = "toolchain_type")

        template_toolchain(
            name = "data",
            vars = {
                "foo": "bar",
            },
        )

        toolchain(
            name = "data_impl",
            toolchain_type = ":toolchain_type",
            toolchain = ":data",
        )
        """);

    // Write a BUILD file that uses the toolchain type.
    write(
        "test/BUILD",
        """

genrule(
    name = "g",
    srcs = [],
    outs = ["g.out"],
    cmd = "echo foo: $(foo) > $@",
    toolchains = ["//test/toolchain:toolchain_type"],
)
""");

    // Make sure the toolchain is available.
    addOptions("--extra_toolchains=//test/toolchain:data_impl");
    buildTarget("//test:g");
    OutputFileConfiguredTarget output =
        (OutputFileConfiguredTarget) getConfiguredTarget("//test:g.out");
    assertThat(readToString(output.getArtifact())).isEqualTo("foo: bar\n");
  }

  @Test
  public void testToolchains_fromToolchain_noToolchainFound() throws Exception {
    // Define a toolchain type.
    write(
        "test/toolchain/BUILD",
        """
        toolchain_type(name = "toolchain_type")
        """);

    // Write a BUILD file that uses the toolchain type.
    write(
        "test/BUILD",
        """

genrule(
    name = "g",
    srcs = [],
    outs = ["g.out"],
    cmd = "echo foo: $(foo) > $@",
    toolchains = ["//test/toolchain:toolchain_type"],
)
""");

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//test:g"));
    assertContainsError("$(foo) not defined");
  }

  @Test
  public void testToolchains_fromToolchain_noToolchainFound_unused() throws Exception {
    // Define a toolchain type.
    write(
        "test/toolchain/BUILD",
        """
        toolchain_type(name = "toolchain_type")
        """);

    // Write a BUILD file that uses the toolchain type.
    write(
        "test/BUILD",
        """

genrule(
    name = "g",
    srcs = [],
    outs = ["g.out"],
    cmd = "echo no template variables used > $@",
    toolchains = ["//test/toolchain:toolchain_type"],
)
""");

    // Invoke the target, even though the toolchain isn't resolved.
    buildTarget("//test:g");
    OutputFileConfiguredTarget output =
        (OutputFileConfiguredTarget) getConfiguredTarget("//test:g.out");
    assertThat(readToString(output.getArtifact())).isEqualTo("no template variables used\n");
  }
}
