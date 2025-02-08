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
package com.google.devtools.build.lib.skyframe.serialization;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactCodecs;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.OutputDirectories.OutputDirectory;
import com.google.devtools.build.lib.analysis.configuredtargets.EnvironmentGroupConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.InputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.MergedConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.PackageGroupConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.DeferredNestedSetCodec;
import com.google.devtools.build.lib.packages.BazelStarlarkEnvironment;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.rules.AliasConfiguredTarget;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.RemoteConfiguredTargetValue;
import com.google.devtools.build.lib.vfs.Root;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.reflect.Constructor;
import java.util.function.Supplier;
import net.starlark.java.eval.Starlark;
import net.starlark.java.syntax.Location;

/**
 * Helpers for setting up the serialization registry (e.g. explicit codecs and constants).
 *
 * <p>The vast majority of codecs are automatically registered (see {@link AutoRegistry} and {@link
 * CodecScanner}). This class provides methods to register additional codecs and constants,
 * depending on the usage context.
 */
public final class SerializationRegistrySetupHelpers {

  private SerializationRegistrySetupHelpers() {}

  private static final ImmutableList<String> OUTPUT_PATHS =
      ImmutableList.of("k8-opt", "k8-fastbuild", "k8-debug");

  public static ImmutableList<Object> makeReferenceConstants(
      BlazeDirectories directories,
      ConfiguredRuleClassProvider ruleClassProvider,
      String workspaceName) {
    ImmutableList.Builder<Object> referenceConstants =
        ImmutableList.builder()
            .add(directories)
            .add(directories.getExecRootBase().getFileSystem())
            .add(directories.getBuildDataDirectory(workspaceName))
            .add(ruleClassProvider.getFragmentRegistry().getAllFragments())
            // Commonly referenced if --trim_test_configuration is enabled.
            .add(
                ruleClassProvider
                    .getFragmentRegistry()
                    .getAllFragments()
                    .trim(TestConfiguration.class));

    Root virtualRoot = directories.getVirtualSourceRoot();
    if (virtualRoot != null) {
      referenceConstants.add(ArtifactRoot.asSourceRoot(virtualRoot));
    }

    // The builtins bzl root (if it exists) lives on a separate InMemoryFileSystem.
    Root builtinsRoot = ruleClassProvider.getBundledBuiltinsRoot();
    if (builtinsRoot != null) {
      referenceConstants.add(builtinsRoot);
    }

    for (OutputDirectory outputDirectory : OutputDirectory.values()) {
      for (String outputPath : OUTPUT_PATHS) {
        referenceConstants.add(outputDirectory.getRoot(outputPath, directories, workspaceName));
      }
    }
    return referenceConstants.build();
  }

  @CanIgnoreReturnValue
  public static ObjectCodecRegistry.Builder addStarlarkFunctionality(
      ObjectCodecRegistry.Builder builder, ConfiguredRuleClassProvider ruleClassProvider) {
    BazelStarlarkEnvironment starlarkEnv = ruleClassProvider.getBazelStarlarkEnvironment();
    builder
        .addReferenceConstant(StructProvider.STRUCT)
        .addReferenceConstant(Starlark.NONE)
        .addReferenceConstant(Location.BUILTIN)
        .addReferenceConstants(
            ImmutableSortedMap.copyOf(starlarkEnv.getUninjectedBuildBzlEnv()).values());

    // Make reference constants for all the native module's potential elements, so that something
    // like myvar = native.cc_test in a .bzl file doesn't cause problems (otherwise we'd have to
    // know how to serialize native.cc_test).
    //
    // Some of these elements may be overridden to Starlark values by builtins injection; see
    // StarlarkBuiltinsFunction. The native module object itself is not registered because it is
    // constructed during builtins injection.
    //
    // TODO(b/111564291): how do we get access to all other Starlark built-ins (ones in
    // apple_common, for instance) and register those? Currently most of those objects are fairly
    // simple to serialize, but that may change in the future. Also be mindful of whether
    // StarlarkSemantics (i.e., incompatible/experimental flags) can affect the bindings we see
    // here. [brandjon: May be able to use the new method
    // BazelStarlarkEnvironment#getUninjectedBuildBzlEnv.]
    builder
        .addReferenceConstants(
            ImmutableSortedMap.copyOf(starlarkEnv.getUninjectedBuildBzlNativeBindings()).values())
        .addReferenceConstants(
            ImmutableSortedMap.copyOf(starlarkEnv.getWorkspaceBzlNativeBindings()).values());

    return builder;
  }

  public static ImmutableList<ObjectCodec<?>> analysisCachingCodecs() {
    return AnalysisCachingCodecsHolder.INSTANCE;
  }

  /**
   * Holder to ensure codecs are not loaded unless {@link #analysisCachingCodecs} is called.
   *
   * <p>This class is loaded on-demand, which is especially important for
   * bazel_bootstrap_distfile_test, where AutoCodec doesn't exist. This is fine for the test,
   * because it doesn't actually use the codecs. See <a
   * href="https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom">Initialization on
   * demand idiom</a>.
   */
  private static class AnalysisCachingCodecsHolder {
    private static final ImmutableList<Class<?>> CONFIGURED_TARGET_CLASSES =
        ImmutableList.of(
            EnvironmentGroupConfiguredTarget.class,
            InputFileConfiguredTarget.class,
            MergedConfiguredTarget.class,
            OutputFileConfiguredTarget.class,
            PackageGroupConfiguredTarget.class,
            RuleConfiguredTarget.class,
            AliasConfiguredTarget.class);

    private static final ImmutableList<ObjectCodec<?>> INSTANCE;

    static {
      var builder =
          ImmutableList.<ObjectCodec<?>>builder()
              .add(ArrayCodec.forComponentType(Artifact.class))
              .add(new DeferredNestedSetCodec())
              .add(new ValueSharingAdapter<>(Label.deferredCodec()))
              .add(ModuleCodec.moduleCodec())
              .add(new ValueSharingAdapter<>(PackageIdentifier.deferredCodec()))
              .add(ConfiguredTargetKey.valueSharingCodec())
              .add(TransitiveInfoProviderMapImpl.valueSharingCodec())
              .add(RemoteConfiguredTargetValue.codec())
              .add(BuildOptions.valueSharingCodec())
              .addAll(ArtifactCodecs.VALUE_SHARING_CODECS);

      for (Class<?> configuredTargetClass : CONFIGURED_TARGET_CLASSES) {
        try {
          // Looks up the AutoCodec implementations with reflection. Since the autocodec-plugin is
          // not marked with generates_api = True (to avoid build time impact) the actual AutoCodec
          // classes are not visible as imports. The dependency on the respective ConfiguredTarget
          // class ensures that the required target dependency exists. The corresponding AutoCodec
          // class will be in the same jar file.
          Constructor<?> autoCodecConstructor =
              Class.forName(configuredTargetClass.getName() + "_AutoCodec")
                  .getDeclaredConstructor();
          autoCodecConstructor.setAccessible(true);
          builder.add(
              new ValueSharingAdapter<>(
                  (DeferredObjectCodec<?>) autoCodecConstructor.newInstance()));
        } catch (ReflectiveOperationException e) {
          throw new ExceptionInInitializerError(e);
        }
      }
      INSTANCE = builder.build();
    }
  }

  /** Initializes an {@link ObjectCodecRegistry} for analysis serialization. */
  public static Supplier<ObjectCodecRegistry> createAnalysisCodecRegistrySupplier(
      ConfiguredRuleClassProvider ruleClassProvider,
      ImmutableList<Object> additionalReferenceConstants) {
    return () -> {
      ObjectCodecRegistry.Builder builder =
          AutoRegistry.get()
              .getBuilder()
              .addReferenceConstants(additionalReferenceConstants)
              .computeChecksum(false);
      builder = addStarlarkFunctionality(builder, ruleClassProvider);
      analysisCachingCodecs().forEach(builder::add);
      return builder.build();
    };
  }
}
