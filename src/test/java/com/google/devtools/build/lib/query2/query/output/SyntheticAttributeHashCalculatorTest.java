// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.query2.query.output;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.Attribute.Discriminator;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link SyntheticAttributeHashCalculator}. */
@RunWith(TestParameterInjector.class)
public class SyntheticAttributeHashCalculatorTest extends PackageLoadingTestCase {

  @Test
  public void testComputeAttributeChangeChangesHash() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])");
    Rule ruleBefore = (Rule) getTarget("//pkg:x");

    scratch.overwriteFile("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['z'])");
    invalidatePackages();
    Rule ruleAfter = (Rule) getTarget("//pkg:x");

    String hashBefore =
        SyntheticAttributeHashCalculator.compute(
            ruleBefore,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);
    String hashAfter =
        SyntheticAttributeHashCalculator.compute(
            ruleAfter,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);

    assertThat(hashBefore).isNotEqualTo(hashAfter);
  }

  @Test
  public void testComputeLocationDoesntChangeHash() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])");
    Rule ruleBefore = (Rule) getTarget("//pkg:x");

    scratch.overwriteFile(
        "pkg/BUILD",
        """
        genrule(
            name = "rule_that_moves_x",
            outs = ["whatever"],
            cmd = "touch $@",
        )

        genrule(
            name = "x",
            outs = ["y"],
            cmd = "touch $@",
        )
        """);
    invalidatePackages();
    Rule ruleAfter = (Rule) getTarget("//pkg:x");

    String hashBefore =
        SyntheticAttributeHashCalculator.compute(
            ruleBefore,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);
    String hashAfter =
        SyntheticAttributeHashCalculator.compute(
            ruleAfter,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);

    assertThat(hashBefore).isEqualTo(hashAfter);
  }

  @Test
  public void testComputeSerializedAttributesUsedOverAvailable() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])");
    Rule rule = (Rule) getTarget("//pkg:x");

    String hashBefore =
        SyntheticAttributeHashCalculator.compute(
            rule,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);

    ImmutableMap<Attribute, Build.Attribute> serializedAttributes =
        ImmutableMap.of(
            rule.getRuleClassObject().getAttributeByName("cmd"),
            Build.Attribute.newBuilder()
                .setName("dummy")
                .setType(Discriminator.STRING)
                .setStringValue("hi")
                .build());

    String hashAfter =
        SyntheticAttributeHashCalculator.compute(
            rule,
            serializedAttributes, /*extraDataForAttrHash*/
            "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);

    assertThat(hashBefore).isNotEqualTo(hashAfter);
  }

  @Test
  public void testComputeExtraDataChangesHash() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])");
    Rule rule = (Rule) getTarget("//pkg:x");

    String hashBefore =
        SyntheticAttributeHashCalculator.compute(
            rule,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);

    String hashAfter =
        SyntheticAttributeHashCalculator.compute(
            rule,
            /* serializedAttributes= */ ImmutableMap.of(), /*extraDataForAttrHash*/
            "blahblaah",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);

    assertThat(hashBefore).isNotEqualTo(hashAfter);
  }

  @Test
  public void testComputePackageErrorStatusChangesHash() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='x', cmd='touch $@', outs=['y'])");
    Rule ruleBefore = (Rule) getTarget("//pkg:x");

    // Remove fail-fast handler, we're intentionally creating a package with errors.
    reporter.removeHandler(failFastHandler);
    scratch.overwriteFile(
        "pkg/BUILD",
        """
        genrule(
            name = "x",
            outs = ["z"],
            cmd = "touch $@",
        )

        genrule(name = "missing_attributes")
        """);
    invalidatePackages();
    Rule ruleAfter = (Rule) getTarget("//pkg:x");
    assertThat(ruleAfter.containsErrors()).isTrue();

    String hashBefore =
        SyntheticAttributeHashCalculator.compute(
            ruleBefore,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);
    String hashAfter =
        SyntheticAttributeHashCalculator.compute(
            ruleAfter,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);

    assertThat(hashBefore).isNotEqualTo(hashAfter);
  }

  @Test
  public void testComputeIncludeAttributeSourceAspectsChangesHash() throws Exception {
    scratch.file(
        "a/defs.bzl",
        """
        def _test_aspect_impl(target, ctx):
            return []

        test_aspect = aspect(
            implementation = _test_aspect_impl,
            attr_aspects = ["deps"],
            attrs = {
                "_aspect_attr_1": attr.label(default = "//a:c"),
                "_aspect_attr_2": attr.label(default = "//a:d"),
            },
        )

        def _lib_impl(ctx):
            return

        test_lib = rule(
            implementation = _lib_impl,
            attrs = {
                "deps": attr.label_list(aspects = [test_aspect]),
            },
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load("defs.bzl", "test_lib")

        test_lib(
            name = "a",
            deps = [":b"],
        )

        test_lib(name = "b")
        """);
    Rule rule = (Rule) getTarget("//a:a");

    String hashWithAttributeAspects =
        SyntheticAttributeHashCalculator.compute(
            rule,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ true,
            /* includeStarlarkRuleEnv= */ true);

    String hashWithoutAttributeAspects =
        SyntheticAttributeHashCalculator.compute(
            rule,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            /* includeStarlarkRuleEnv= */ true);
    assertThat(hashWithAttributeAspects).isNotEqualTo(hashWithoutAttributeAspects);
  }

  @Test
  public void testStarlarkRuleEnvChanges(@TestParameter boolean includeStarlarkRuleEnv)
      throws Exception {
    scratch.file(
        "a/defs.bzl",
        """
        def _lib_impl(ctx):
            return "old"

        test_lib = rule(
            implementation = _lib_impl,
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load("defs.bzl", "test_lib")

        test_lib(name = "a")
        """);
    Rule ruleBefore = (Rule) getTarget("//a:a");

    scratch.overwriteFile(
        "a/defs.bzl",
        """
        def _lib_impl(ctx):
            return "new"

        test_lib = rule(
            implementation = _lib_impl,
        )
        """);

    invalidatePackages();
    Rule ruleAfter = (Rule) getTarget("//a:a");

    String hashBefore =
        SyntheticAttributeHashCalculator.compute(
            ruleBefore,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            includeStarlarkRuleEnv);

    String hashAfter =
        SyntheticAttributeHashCalculator.compute(
            ruleAfter,
            /* serializedAttributes= */ ImmutableMap.of(),
            /* extraDataForAttrHash= */ "",
            DigestHashFunction.SHA256.getHashFunction(),
            /* includeAttributeSourceAspects= */ false,
            includeStarlarkRuleEnv);
    if (includeStarlarkRuleEnv) {
      assertThat(hashBefore).isNotEqualTo(hashAfter);
    } else {
      assertThat(hashBefore).isEqualTo(hashAfter);
    }
  }
}
