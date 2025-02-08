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
package com.google.devtools.build.lib.cmdline;

import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/**
 * Interface for {@link com.google.devtools.build.lib.skyframe.BzlLoadValue.Key}.
 *
 * <p>This exists to break what would otherwise be a circular dependency between {@link Label},
 * {@link BazelModuleContext} and {@link com.google.devtools.build.lib.skyframe.BzlLoadValue.Key}.
 */
public interface BazelModuleKey extends SkyKey {
  /** Absolute label of the .bzl file to be loaded. */
  Label getLabel();

  @Override
  default SkyFunctionName functionName() {
    return SkyFunctions.BZL_LOAD;
  }

  /** Creates a fake instance for testing. */
  static BazelModuleKey createFakeModuleKeyForTesting(Label label) {
    return new FakeModuleKey(label);
  }

  /** Key for {@link BazelModuleContext}s created outside of Skyframe for testing */
  static final class FakeModuleKey implements BazelModuleKey {
    private final Label label;

    private FakeModuleKey(Label label) {
      this.label = label;
    }

    @Override
    public Label getLabel() {
      return label;
    }

    @Override
    public SkyFunctionName functionName() {
      throw new UnsupportedOperationException();
    }
  }
}
