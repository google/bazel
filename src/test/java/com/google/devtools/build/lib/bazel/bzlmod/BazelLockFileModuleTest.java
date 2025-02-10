// Copyright 2024 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import java.util.Optional;
import net.starlark.java.eval.Starlark;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BazelLockFileModule}. */
@RunWith(JUnit4.class)
public class BazelLockFileModuleTest {

  private ModuleExtensionId extensionId;
  private LockFileModuleExtension nonReproducibleResult;
  private LockFileModuleExtension reproducibleResult;
  private ModuleExtensionEvalFactors evalFactors;
  private ModuleExtensionEvalFactors otherEvalFactors;

  @Before
  public void setUp() throws Exception {
    extensionId =
        ModuleExtensionId.create(
            Label.parseCanonicalUnchecked("//:ext.bzl"), "ext", Optional.empty());
    nonReproducibleResult =
        LockFileModuleExtension.builder()
            .setBzlTransitiveDigest(new byte[] {1, 2, 3})
            .setUsagesDigest(new byte[] {4, 5, 6})
            .setRecordedFileInputs(ImmutableMap.of())
            .setRecordedDirentsInputs(ImmutableMap.of())
            .setEnvVariables(ImmutableMap.of())
            .setGeneratedRepoSpecs(ImmutableMap.of())
            .build();
    reproducibleResult =
        LockFileModuleExtension.builder()
            .setBzlTransitiveDigest(new byte[] {1, 2, 3})
            .setUsagesDigest(new byte[] {4, 5, 6})
            .setRecordedFileInputs(ImmutableMap.of())
            .setRecordedDirentsInputs(ImmutableMap.of())
            .setEnvVariables(ImmutableMap.of())
            .setGeneratedRepoSpecs(ImmutableMap.of())
            .setModuleExtensionMetadata(
                Optional.of(
                    ModuleExtensionMetadata.create(
                        Starlark.NONE, Starlark.NONE, /* reproducible= */ true)))
            .build();
    evalFactors = ModuleExtensionEvalFactors.create("linux", "x86_64");
    otherEvalFactors = ModuleExtensionEvalFactors.create("linux", "aarch64");
  }

  @Test
  public void combineModuleExtensionsReproducibleFactorAdded() {
    var oldExtensionInfos =
        ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, nonReproducibleResult));
    var newExtensionInfos =
        ImmutableMap.of(
            extensionId,
            new LockFileModuleExtension.WithFactors(otherEvalFactors, reproducibleResult));

    assertThat(
            BazelLockFileModule.combineModuleExtensions(
                oldExtensionInfos, newExtensionInfos, id -> true))
        .isEqualTo(oldExtensionInfos);
  }

  @Test
  public void combineModuleExtensionsFactorBecomesReproducible() {
    var oldExtensionInfos =
        ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, nonReproducibleResult));
    var newExtensionInfos =
        ImmutableMap.of(
            extensionId, new LockFileModuleExtension.WithFactors(evalFactors, reproducibleResult));

    assertThat(
            BazelLockFileModule.combineModuleExtensions(
                oldExtensionInfos, newExtensionInfos, id -> true))
        .isEmpty();
  }

  @Test
  public void combineModuleExtensionsFactorBecomesNonReproducible() {
    var oldExtensionInfos =
        ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, reproducibleResult));
    var newExtensionInfos =
        ImmutableMap.of(
            extensionId,
            new LockFileModuleExtension.WithFactors(evalFactors, nonReproducibleResult));

    assertThat(
            BazelLockFileModule.combineModuleExtensions(
                oldExtensionInfos, newExtensionInfos, id -> true))
        .isEqualTo(
            ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, nonReproducibleResult)));
  }
}
