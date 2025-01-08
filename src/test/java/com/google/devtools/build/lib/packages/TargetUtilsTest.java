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
package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Starlark;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link TargetUtils}
 */
@RunWith(JUnit4.class)
public class TargetUtilsTest extends PackageLoadingTestCase {

  @Test
  public void getRuleLanguage() {
    assertThat(TargetUtils.getRuleLanguage("java_binary")).isEqualTo("java");
    assertThat(TargetUtils.getRuleLanguage("foobar")).isEqualTo("foobar");
    assertThat(TargetUtils.getRuleLanguage("")).isEmpty();
  }

  @Test
  public void testFilterByTag() throws Exception {
    scratch.file(
        "tests/BUILD",
        """
        load("//test_defs:foo_binary.bzl", "foo_binary")
        foo_binary(
            name = "tag1",
            srcs = ["sh.sh"],
            tags = ["tag1"],
        )

        foo_binary(
            name = "tag2",
            srcs = ["sh.sh"],
            tags = ["tag2"],
        )

        foo_binary(
            name = "tag1b",
            srcs = ["sh.sh"],
            tags = ["tag1"],
        )
        """);

    Target tag1 = getTarget("//tests:tag1");
    Target tag2 = getTarget("//tests:tag2");
    Target  tag1b = getTarget("//tests:tag1b");

    Predicate<Target> tagFilter = TargetUtils.tagFilter(Lists.<String>newArrayList());
    assertThat(tagFilter.apply(tag1)).isTrue();
    assertThat(tagFilter.apply(tag2)).isTrue();
    assertThat(tagFilter.apply(tag1b)).isTrue();
    tagFilter = TargetUtils.tagFilter(Lists.newArrayList("tag1", "tag2"));
    assertThat(tagFilter.apply(tag1)).isTrue();
    assertThat(tagFilter.apply(tag2)).isTrue();
    assertThat(tagFilter.apply(tag1b)).isTrue();
    tagFilter = TargetUtils.tagFilter(Lists.newArrayList("tag1"));
    assertThat(tagFilter.apply(tag1)).isTrue();
    assertThat(tagFilter.apply(tag2)).isFalse();
    assertThat(tagFilter.apply(tag1b)).isTrue();
    tagFilter = TargetUtils.tagFilter(Lists.newArrayList("-tag2"));
    assertThat(tagFilter.apply(tag1)).isTrue();
    assertThat(tagFilter.apply(tag2)).isFalse();
    assertThat(tagFilter.apply(tag1b)).isTrue();
    // Applying same tag as positive and negative filter produces an empty
    // result because the negative filter is applied first and positive filter will
    // not match anything.
    tagFilter = TargetUtils.tagFilter(Lists.newArrayList("tag2", "-tag2"));
    assertThat(tagFilter.apply(tag1)).isFalse();
    assertThat(tagFilter.apply(tag2)).isFalse();
    assertThat(tagFilter.apply(tag1b)).isFalse();
    tagFilter = TargetUtils.tagFilter(Lists.newArrayList("tag2", "-tag1"));
    assertThat(tagFilter.apply(tag1)).isFalse();
    assertThat(tagFilter.apply(tag2)).isTrue();
    assertThat(tagFilter.apply(tag1b)).isFalse();
  }

  @Test
  public void testExecutionInfo() throws Exception {
    scratch.file(
        "tests/BUILD",
        """
        load("//test_defs:foo_binary.bzl", "foo_binary")
        foo_binary(
            name = "tag1",
            srcs = ["sh.sh"],
            tags = [
                "no-cache",
                "supports-workers",
            ],
        )

        foo_binary(
            name = "tag2",
            srcs = ["sh.sh"],
            tags = ["disable-local-prefetch"],
        )

        foo_binary(
            name = "tag1b",
            srcs = ["sh.sh"],
            tags = [
                "cpu:4",
                "local",
            ],
        )
        """);

    Rule tag1 = (Rule) getTarget("//tests:tag1");
    Rule tag2 = (Rule) getTarget("//tests:tag2");
    Rule tag1b = (Rule) getTarget("//tests:tag1b");

    Map<String, String> execInfo = TargetUtils.getExecutionInfo(tag1);
    assertThat(execInfo).containsExactly("supports-workers", "", "no-cache", "");
    execInfo = TargetUtils.getExecutionInfo(tag2);
    assertThat(execInfo).containsExactly("disable-local-prefetch", "");
    execInfo = TargetUtils.getExecutionInfo(tag1b);
    assertThat(execInfo).containsExactly("local", "", "cpu:4", "");
  }

  @Test
  public void testExecutionInfo_withPrefixSupports() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'with-prefix-supports', srcs=['sh.sh'], tags=['supports-workers',"
            + " 'supports-whatever', 'my-tag'])");

    Rule withSupportsPrefix = (Rule) getTarget("//tests:with-prefix-supports");

    Map<String, String> execInfo = TargetUtils.getExecutionInfo(withSupportsPrefix);
    assertThat(execInfo).containsExactly("supports-whatever", "", "supports-workers", "");
  }

  @Test
  public void testExecutionInfo_withPrefixDisable() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'with-prefix-disable', srcs=['sh.sh'], tags=['disable-local-prefetch',"
            + " 'disable-something-else', 'another-tag'])");

    Rule withDisablePrefix = (Rule) getTarget("//tests:with-prefix-disable");

    Map<String, String> execInfo = TargetUtils.getExecutionInfo(withDisablePrefix);
    assertThat(execInfo)
        .containsExactly("disable-local-prefetch", "", "disable-something-else", "");
  }

  @Test
  public void testExecutionInfo_withPrefixNo() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'with-prefix-no', srcs=['sh.sh'], tags=['no-remote-imaginary-flag',"
            + " 'no-sandbox', 'unknown'])");

    Rule withNoPrefix = (Rule) getTarget("//tests:with-prefix-no");

    Map<String, String> execInfo = TargetUtils.getExecutionInfo(withNoPrefix);
    assertThat(execInfo).containsExactly("no-remote-imaginary-flag", "", "no-sandbox", "");
  }

  @Test
  public void testExecutionInfo_withPrefixRequires() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'with-prefix-requires', srcs=['sh.sh'], tags=['requires-network',"
            + " 'requires-sunlight', 'test-only'])");

    Rule withRequiresPrefix = (Rule) getTarget("//tests:with-prefix-requires");

    Map<String, String> execInfo = TargetUtils.getExecutionInfo(withRequiresPrefix);
    assertThat(execInfo).containsExactly("requires-network", "", "requires-sunlight", "");
  }

  @Test
  public void testExecutionInfo_withPrefixBlock() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'with-prefix-block', srcs=['sh.sh'], tags=['block-some-feature',"
            + " 'block-network', 'wrong-tag'])");

    Rule withBlockPrefix = (Rule) getTarget("//tests:with-prefix-block");

    Map<String, String> execInfo = TargetUtils.getExecutionInfo(withBlockPrefix);
    assertThat(execInfo).containsExactly("block-network", "", "block-some-feature", "");
  }

  @Test
  public void testExecutionInfo_withPrefixCpu() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'with-prefix-cpu', srcs=['sh.sh'], tags=['cpu:123', 'wrong-tag'])");

    Rule withCpuPrefix = (Rule) getTarget("//tests:with-prefix-cpu");

    Map<String, String> execInfo = TargetUtils.getExecutionInfo(withCpuPrefix);
    assertThat(execInfo).containsExactly("cpu:123", "");
  }

  @Test
  public void testExecutionInfo_withPrefixTimeout() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'with-prefix-timeout', srcs=['sh.sh'], tags=['timeout:123', 'wrong-tag'])");

    Rule withCpuPrefix = (Rule) getTarget("//tests:with-prefix-timeout");

    Map<String, String> execInfo = TargetUtils.getExecutionInfo(withCpuPrefix);
    assertThat(execInfo).containsExactly("timeout:123", "");
  }

  @Test
  public void testExecutionInfo_withLocalTag() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'with-local-tag', srcs=['sh.sh'], tags=['local', 'some-tag'])");

    Rule withLocal = (Rule) getTarget("//tests:with-local-tag");

    Map<String, String> execInfo = TargetUtils.getExecutionInfo(withLocal);
    assertThat(execInfo).containsExactly("local", "");
  }

  @Test
  public void testFilteredExecutionInfo_fromUncheckedExecRequirements() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'no-tag', srcs=['sh.sh'])");

    Rule noTag = (Rule) getTarget("//tests:no-tag");

    Map<String, String> execInfo =
        TargetUtils.getFilteredExecutionInfo(
            Dict.<String, String>builder().put("supports-worker", "1").buildImmutable(),
            noTag, /* allowTagsPropagation */
            true);
    assertThat(execInfo).containsExactly("supports-worker", "1");

    execInfo =
        TargetUtils.getFilteredExecutionInfo(
            Dict.<String, String>builder()
                .put("some-custom-tag", "1")
                .put("no-cache", "1")
                .buildImmutable(),
            noTag,
            /* allowTagsPropagation */ true);
    assertThat(execInfo).containsExactly("no-cache", "1");
  }

  @Test
  public void testFilteredExecutionInfo_fromUncheckedExecRequirements_withWorkerKeyMnemonic()
      throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'no-tag', srcs=['sh.sh'])");

    Rule noTag = (Rule) getTarget("//tests:no-tag");

    Map<String, String> execInfo =
        TargetUtils.getFilteredExecutionInfo(
            Dict.<String, String>builder()
                .put("supports-workers", "1")
                .put("worker-key-mnemonic", "MyMnemonic")
                .buildImmutable(),
            noTag, /* allowTagsPropagation */
            true);
    assertThat(execInfo)
        .containsExactly("supports-workers", "1", "worker-key-mnemonic", "MyMnemonic");
  }

  @Test
  public void testFilteredExecutionInfo() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'tag1', srcs=['sh.sh'], tags=['supports-workers', 'no-cache'])");
    Rule tag1 = (Rule) getTarget("//tests:tag1");
    Dict<String, String> executionRequirementsUnchecked =
        Dict.<String, String>builder().put("no-remote", "1").buildImmutable();

    Map<String, String> execInfo =
        TargetUtils.getFilteredExecutionInfo(
            executionRequirementsUnchecked, tag1, /* allowTagsPropagation */ true);

    assertThat(execInfo).containsExactly("no-cache", "", "supports-workers", "", "no-remote", "1");
  }

  @Test
  public void testFilteredExecutionInfo_withDuplicateTags() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'tag1', srcs=['sh.sh'], tags=['supports-workers', 'no-cache'])");
    Rule tag1 = (Rule) getTarget("//tests:tag1");
    Dict<String, String> executionRequirementsUnchecked =
        Dict.<String, String>builder().put("no-cache", "1").buildImmutable();

    Map<String, String> execInfo =
        TargetUtils.getFilteredExecutionInfo(
            executionRequirementsUnchecked, tag1, /* allowTagsPropagation */ true);

    assertThat(execInfo).containsExactly("no-cache", "1", "supports-workers", "");
  }

  @Test
  public void testFilteredExecutionInfo_withNullUncheckedExecRequirements() throws Exception {
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'tag1', srcs=['sh.sh'], tags=['supports-workers', 'no-cache'])");
    Rule tag1 = (Rule) getTarget("//tests:tag1");

    Map<String, String> execInfo =
        TargetUtils.getFilteredExecutionInfo(null, tag1, /* allowTagsPropagation */ true);
    assertThat(execInfo).containsExactly("no-cache", "", "supports-workers", "");

    execInfo =
        TargetUtils.getFilteredExecutionInfo(Starlark.NONE, tag1, /* allowTagsPropagation */ true);
    assertThat(execInfo).containsExactly("no-cache", "", "supports-workers", "");
  }

  @Test
  public void testFilteredExecutionInfo_whenIncompatibleFlagDisabled() throws Exception {
    // when --incompatible_allow_tags_propagation=false
    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(name = 'tag1', srcs=['sh.sh'], tags=['supports-workers', 'no-cache'])");
    Rule tag1 = (Rule) getTarget("//tests:tag1");
    Dict<String, String> executionRequirementsUnchecked =
        Dict.<String, String>builder().put("no-remote", "1").buildImmutable();

    Map<String, String> execInfo =
        TargetUtils.getFilteredExecutionInfo(
            executionRequirementsUnchecked, tag1, /* allowTagsPropagation */ false);

    assertThat(execInfo).containsExactly("no-remote", "1");
  }

  @Test
  public void testExecutionInfoMisc() throws Exception {
    // Migrated from a removed test class that was focused on top-level build configuration.
    // TODO(anyone): remove tests here that are redundant w.r.t. the other tests in this file.
    scratch.file(
        "x/BUILD",
        """
        cc_test(
            name = "y",
            size = "small",
            srcs = ["a"],
            tags = [
                "exclusive",
                "local",
                "manual",
            ],
        )

        cc_test(
            name = "z",
            size = "small",
            srcs = ["a"],
            tags = [
                "othertag",
                "requires-feature2",
            ],
        )

        cc_test(
            name = "k",
            size = "small",
            srcs = ["a"],
            tags = ["requires-feature1"],
        )

        cc_test(
            name = "exclusive_if_local",
            size = "small",
            srcs = ["a"],
            tags = ["exclusive-if-local"],
        )

        cc_test(
            name = "exclusive_only",
            size = "small",
            srcs = ["a"],
            tags = ["exclusive"],
        )

        test_suite(
            name = "ts",
            tests = ["z"],
        )

        cc_binary(
            name = "x",
            srcs = [
                "a",
                "b",
                "c",
            ],
            defines = [
                "-Da",
                "-Db",
            ],
        )

        cc_binary(
            name = "lib1",
            srcs = [
                "a",
                "b",
                "c",
            ],
            linkshared = 1,
        )

        genrule(
            name = "gen1",
            srcs = [],
            outs = [
                "t1",
                "t2",
            ],
            cmd = "my cmd",
        )

        genrule(
            name = "gen2",
            srcs = ["liba.so"],
            outs = ["libnewa.so"],
            cmd = "my cmd",
        )
        """);
    Rule x = (Rule) getTarget("//x:x");
    assertThat(TargetUtils.isTestRule(x)).isFalse();
    Rule ts = (Rule) getTarget("//x:ts");
    assertThat(TargetUtils.isTestRule(ts)).isFalse();
    assertThat(TargetUtils.isTestOrTestSuiteRule(ts)).isTrue();
    Rule z = (Rule) getTarget("//x:z");
    assertThat(TargetUtils.isTestRule(z)).isTrue();
    assertThat(TargetUtils.isTestOrTestSuiteRule(z)).isTrue();
    assertThat(TargetUtils.isExclusiveTestRule(z)).isFalse();
    assertThat(TargetUtils.isExclusiveIfLocalTestRule(z)).isFalse();
    assertThat(TargetUtils.isLocalTestRule(z)).isFalse();
    assertThat(TargetUtils.hasManualTag(z)).isFalse();
    assertThat(TargetUtils.getExecutionInfo(z)).doesNotContainKey("requires-feature1");
    assertThat(TargetUtils.getExecutionInfo(z)).containsKey("requires-feature2");
    Rule k = (Rule) getTarget("//x:k");
    assertThat(TargetUtils.isTestRule(k)).isTrue();
    assertThat(TargetUtils.isTestOrTestSuiteRule(k)).isTrue();
    assertThat(TargetUtils.isExclusiveTestRule(k)).isFalse();
    assertThat(TargetUtils.isExclusiveIfLocalTestRule(k)).isFalse();
    assertThat(TargetUtils.isLocalTestRule(k)).isFalse();
    assertThat(TargetUtils.hasManualTag(k)).isFalse();
    assertThat(TargetUtils.getExecutionInfo(k)).containsKey("requires-feature1");
    assertThat(TargetUtils.getExecutionInfo(k)).doesNotContainKey("requires-feature2");
    Rule y = (Rule) getTarget("//x:y");
    assertThat(TargetUtils.isTestRule(y)).isTrue();
    assertThat(TargetUtils.isTestOrTestSuiteRule(y)).isTrue();
    assertThat(TargetUtils.isExclusiveTestRule(y)).isTrue();
    assertThat(TargetUtils.isExclusiveIfLocalTestRule(y)).isFalse();
    assertThat(TargetUtils.isLocalTestRule(y)).isTrue();
    assertThat(TargetUtils.hasManualTag(y)).isTrue();
    assertThat(TargetUtils.getExecutionInfo(y)).doesNotContainKey("requires-feature1");
    assertThat(TargetUtils.getExecutionInfo(y)).doesNotContainKey("requires-feature2");
    Rule exclusiveIfRunLocally = (Rule) getTarget("//x:exclusive_if_local");
    assertThat(TargetUtils.isExclusiveIfLocalTestRule(exclusiveIfRunLocally)).isTrue();
    assertThat(TargetUtils.isLocalTestRule(exclusiveIfRunLocally)).isFalse();
    assertThat(TargetUtils.isExclusiveTestRule(exclusiveIfRunLocally)).isFalse();
    Rule exclusive = (Rule) getTarget("//x:exclusive_only");
    assertThat(TargetUtils.isExclusiveTestRule(exclusive)).isTrue();
    assertThat(TargetUtils.isLocalTestRule(exclusive)).isFalse(); // LOCAL tag gets added later.
    assertThat(TargetUtils.isExclusiveIfLocalTestRule(exclusive)).isFalse();
  }
}
