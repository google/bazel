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

import static java.util.concurrent.ForkJoinPool.commonPool;

import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.common.options.OptionsParsingResult;

/** A {@link BlazeModule} to store Skyframe serialization lifecycle hooks. */
public class SerializationModule extends BlazeModule {

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    if (!directories.inWorkspace()) {
      // Serialization only works when the Bazel server is invoked from a workspace.
      // Counter-example: invoking the Bazel server outside of a workspace to generate/dump
      // documentation HTML.
      return;
    }
    // This is injected as a callback instead of evaluated eagerly to avoid forcing the somewhat
    // expensive AutoRegistry.get call on clients that don't require it.
    builder.setAnalysisCodecRegistrySupplier(
        SerializationRegistrySetupHelpers.createAnalysisCodecRegistrySupplier(
            runtime.getRuleClassProvider(),
            SerializationRegistrySetupHelpers.makeReferenceConstants(
                directories,
                runtime.getRuleClassProvider(),
                directories.getWorkspace().getBaseName())));

    builder.setFingerprintValueServiceFactory(getFingerprintValueServiceFactory());
  }

  /**
   * Returns the {@link FingerprintValueService.Factory} for creating {@link
   * FingerprintValueService} instances.
   *
   * <p>Using a factory, each command invocation can instantiate a new {@link
   * FingerprintValueService} instance configurable with command options, like the URL or
   * concurrency level.
   *
   * <p>However, the default implementation is an in-memory static singleton instance unique to the
   * lifetime of the Bazel server process. This can be overridden using alternate implementations.
   */
  protected FingerprintValueService.Factory getFingerprintValueServiceFactory() {
    // Single instance for the Bazel server lifetime.
    return InMemoryFingerprintValueServiceFactory.INSTANCE;
  }

  /** A factory for creating in-memory fingerprint value services. */
  private static final class InMemoryFingerprintValueServiceFactory
      implements FingerprintValueService.Factory {
    private static final InMemoryFingerprintValueServiceFactory INSTANCE =
        new InMemoryFingerprintValueServiceFactory();

    private static final FingerprintValueService SERVICE_INSTANCE =
        new FingerprintValueService(
            commonPool(),
            // TODO: b/358347099 - use a persistent store
            FingerprintValueStore.inMemoryStore(),
            new FingerprintValueCache(FingerprintValueCache.SyncMode.NOT_LINKED),
            FingerprintValueService.NONPROD_FINGERPRINTER);

    private InMemoryFingerprintValueServiceFactory() {}

    @Override
    public FingerprintValueService create(OptionsParsingResult unused) {
      return SERVICE_INSTANCE;
    }
  }
}
