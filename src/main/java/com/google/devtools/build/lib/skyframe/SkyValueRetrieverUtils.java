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
package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.NoCachedData.NO_CACHED_DATA;

import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.DefaultDependOnFutureShim;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalResult;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.SerializableSkyKeyComputeState;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingDependenciesProvider;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.function.Supplier;

/**
 * A wrapper around {@link SkyValueRetriever} to handle Bazel-on-Skyframe specific logic, metrics
 * gathering, and error handling.
 */
public final class SkyValueRetrieverUtils {

  public static RetrievalResult maybeFetchSkyValueRemotely(
      SkyKey key,
      Environment env,
      RemoteAnalysisCachingDependenciesProvider analysisCachingDeps,
      Supplier<? extends SerializableSkyKeyComputeState> stateSupplier)
      throws InterruptedException {
    if (analysisCachingDeps.mode() != RemoteAnalysisCacheMode.DOWNLOAD) {
      return NO_CACHED_DATA;
    }

    Label label =
        switch (key) {
          case ActionLookupKey alk -> alk.getLabel();
          case ActionLookupData ald -> ald.getLabel();
          case Artifact artifact -> artifact.getOwnerLabel();
          default -> throw new IllegalStateException("unexpected key: " + key);
        };

    if (analysisCachingDeps.withinActiveDirectories(label.getPackageIdentifier())) {
      return NO_CACHED_DATA;
    }

    RetrievalResult retrievalResult;
    try {
      SerializableSkyKeyComputeState state = env.getState(stateSupplier);
      retrievalResult =
          SkyValueRetriever.tryRetrieve(
              env,
              new DefaultDependOnFutureShim(env),
              analysisCachingDeps.getObjectCodecs(),
              analysisCachingDeps.getFingerprintValueService(),
              key,
              state,
              /* frontierNodeVersion= */ analysisCachingDeps.getSkyValueVersion());
    } catch (SerializationException e) {
      // Don't crash the build if deserialization failed. Gracefully fallback to local evaluation.
      analysisCachingDeps.recordSerializationException(e);
      return NO_CACHED_DATA;
    }
    analysisCachingDeps.recordRetrievalResult(retrievalResult, key);
    return retrievalResult;
  }

  private SkyValueRetrieverUtils() {}
}
