// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/** The key for {@link RegistryFunction}. */
@AutoCodec
record RegistryKey(String url) implements SkyKey {
  RegistryKey {
    requireNonNull(url, "url");
  }

  private static final SkyKeyInterner<RegistryKey> interner = SkyKey.newInterner();

  @AutoCodec.Instantiator
  static RegistryKey create(String url) {
    return interner.intern(new RegistryKey(url));
  }

  @Override
  public SkyFunctionName functionName() {
    return SkyFunctions.REGISTRY;
  }

  @Override
  public SkyKeyInterner<RegistryKey> getSkyKeyInterner() {
    return interner;
  }
}
