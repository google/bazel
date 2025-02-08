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
package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.skyframe.SkyFunctions.DIRECTORY_LISTING;

import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/** Key for {@link DirectoryListingFunction}. */
@AutoCodec
public final class DirectoryListingKey extends AbstractSkyKey<RootedPath>
    implements FileOpNodeOrFuture.FileOpNode {

  private static final SkyKeyInterner<DirectoryListingKey> interner = SkyKey.newInterner();

  public static DirectoryListingKey create(RootedPath arg) {
    return interner.intern(new DirectoryListingKey(arg));
  }

  private DirectoryListingKey(RootedPath arg) {
    super(arg);
  }

  @VisibleForSerialization
  @AutoCodec.Interner
  static DirectoryListingKey intern(DirectoryListingKey key) {
    return interner.intern(key);
  }

  @Override
  public SkyFunctionName functionName() {
    return DIRECTORY_LISTING;
  }

  @Override
  public SkyKeyInterner<DirectoryListingKey> getSkyKeyInterner() {
    return interner;
  }
}
