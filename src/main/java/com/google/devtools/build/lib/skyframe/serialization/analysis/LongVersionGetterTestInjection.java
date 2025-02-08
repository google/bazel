// Copyright 2025 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.util.TestType.isInTest;

import com.google.devtools.build.lib.versioning.LongVersionGetter;

/**
 * Allows injecting a {@link LongVersionGetter} implementation to {@link FrontierSerializer} in
 * tests.
 */
@SuppressWarnings("NonFinalStaticField")
public final class LongVersionGetterTestInjection {
  private static LongVersionGetter versionGetter = null;
  private static boolean wasAccessed = false;

  static LongVersionGetter getVersionGetterForTesting() {
    checkState(isInTest());
    wasAccessed = true;
    return checkNotNull(versionGetter, "injectVersionGetterForTesting must be called first");
  }

  public static void injectVersionGetterForTesting(LongVersionGetter versionGetter) {
    checkState(isInTest());
    LongVersionGetterTestInjection.versionGetter = versionGetter;
  }

  public static boolean wasGetterAccessed() {
    return wasAccessed;
  }

  private LongVersionGetterTestInjection() {}
}
