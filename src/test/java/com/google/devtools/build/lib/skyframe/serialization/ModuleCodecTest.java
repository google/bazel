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
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static com.google.devtools.build.lib.skyframe.serialization.ModuleCodec.moduleCodec;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.skyframe.serialization.testutils.TestUtils;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import javax.annotation.Nullable;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ModuleCodec}. */
@RunWith(JUnit4.class)
public class ModuleCodecTest extends BuildViewTestCase {
  @Test
  public void testDynamicCodec() throws Exception {
    Module subject1 = Module.create();

    Module subject2 =
        Module.withPredeclaredAndData(
            StarlarkSemantics.DEFAULT, ImmutableMap.of(), Label.parseCanonical("//foo:bar"));
    subject2.setGlobal("x", 1);
    subject2.setGlobal("y", 2);

    new SerializationTester(subject1, subject2)
        .makeMemoizing()
        .setVerificationFunction(ModuleCodecTest::verifyDeserialization)
        .runTestsWithoutStableSerializationCheck();
  }

  @Test
  public void testCodec() throws Exception {
    scratch.file("lib/BUILD");
    scratch.file(
        "pkg/foo.bzl",
        """
        def _impl(ctx):
            print("xyz is %s" % ctx.attr.xyz)
        my_rule = rule(
            implementation=_impl,
            attrs = {
              "xyz": attr.string(),
            },
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load(":foo.bzl", "my_rule")
        my_rule(
            name = "abc",
            xyz = "value",
        )
        """);

    // Evaluates pkg to populate pkg/foo.bzl in Skyframe.
    assertThat(getPackage("pkg")).isNotNull();

    // Pulls the module value out of Skyframe from its BzlLoadValue.
    BzlLoadValue.Key bzlLoadKey = keyForBuild(Label.parseCanonical("//pkg:foo.bzl"));
    var fooBzl = (BzlLoadValue) getDoneValue(bzlLoadKey);
    Module module = fooBzl.getModule();

    var deserialized =
        RoundTripping.roundTripWithSkyframe(
            new ObjectCodecs().withCodecOverridesForTesting(ImmutableList.of(moduleCodec())),
            FingerprintValueService.createForTesting(),
            this::getDoneValue,
            module);

    assertThat(deserialized).isSameInstanceAs(module);
  }

  @Nullable
  private Package getPackage(String pkgName) throws InterruptedException {
    try {
      return getPackageManager().getPackage(reporter, PackageIdentifier.createInMainRepo(pkgName));
    } catch (NoSuchPackageException unused) {
      return null;
    }
  }

  private SkyValue getDoneValue(SkyKey key) {
    try {
      return skyframeExecutor.getDoneSkyValueForIntrospection(key);
    } catch (SkyframeExecutor.FailureToRetrieveIntrospectedValueException e) {
      throw new AssertionError(e);
    }
  }

  private static void verifyDeserialization(Module subject, Module deserialized) {
    // Module doesn't implement proper equality.
    TestUtils.assertModulesEqual(subject, deserialized);
  }
}
