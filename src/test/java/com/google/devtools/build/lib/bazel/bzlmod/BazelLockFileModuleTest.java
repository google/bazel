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
                oldExtensionInfos, newExtensionInfos, id -> true, /* reproducible= */ false))
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
                oldExtensionInfos, newExtensionInfos, id -> true, /* reproducible= */ false))
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
                oldExtensionInfos, newExtensionInfos, id -> true, /* reproducible= */ false))
        .isEqualTo(
            ImmutableMap.of(extensionId, ImmutableMap.of(evalFactors, nonReproducibleResult)));
  }
}
