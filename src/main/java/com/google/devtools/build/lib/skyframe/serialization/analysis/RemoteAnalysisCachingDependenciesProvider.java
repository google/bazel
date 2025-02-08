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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.FrontierNodeVersion;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.skyframe.SkyKey;

/**
 * An interface providing the functionalities used for analysis caching serialization and
 * deserialization.
 */
public interface RemoteAnalysisCachingDependenciesProvider {

  RemoteAnalysisCacheMode mode();

  /** Value of RemoteAnalysisCachingOptions#serializedFrontierProfile. */
  String serializedFrontierProfile();

  /** Returns true if the {@link PackageIdentifier} is in the set of active directories. */
  boolean withinActiveDirectories(PackageIdentifier pkg);

  /**
   * Returns the string distinguisher to invalidate SkyValues, in addition to the corresponding
   * SkyKey.
   */
  FrontierNodeVersion getSkyValueVersion() throws SerializationException;

  /**
   * Returns the {@link ObjectCodecs} supplier for remote analysis caching.
   *
   * <p>Calling this can be an expensive process as the codec registry will be initialized.
   */
  ObjectCodecs getObjectCodecs();

  /** Returns the {@link FingerprintValueService} implementation. */
  FingerprintValueService getFingerprintValueService();

  void recordRetrievalResult(RetrievalResult retrievalResult, SkyKey key);

  void recordSerializationException(SerializationException e);

  void setTopLevelConfigChecksum(String checksum);

  ModifiedFileSet getDiffFromEvaluatingVersion();

  /** A stub dependencies provider for when analysis caching is disabled. */
  final class DisabledDependenciesProvider implements RemoteAnalysisCachingDependenciesProvider {

    public static final DisabledDependenciesProvider INSTANCE = new DisabledDependenciesProvider();

    private DisabledDependenciesProvider() {}

    @Override
    public RemoteAnalysisCacheMode mode() {
      return RemoteAnalysisCacheMode.OFF;
    }

    @Override
    public String serializedFrontierProfile() {
      return "";
    }

    @Override
    public boolean withinActiveDirectories(PackageIdentifier pkg) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FrontierNodeVersion getSkyValueVersion() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ObjectCodecs getObjectCodecs() {
      throw new UnsupportedOperationException();
    }

    @Override
    public FingerprintValueService getFingerprintValueService() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void recordRetrievalResult(RetrievalResult retrievalResult, SkyKey key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void recordSerializationException(SerializationException e) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setTopLevelConfigChecksum(String topLevelConfigChecksum) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ModifiedFileSet getDiffFromEvaluatingVersion() {
      throw new UnsupportedOperationException();
    }
  }
}
