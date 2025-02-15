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
package com.google.devtools.build.lib.analysis.platform;

import com.google.devtools.build.lib.cmdline.Label;

/** This file holds hardcoded constants used by the platforms system. */
public final class PlatformConstants {

  private PlatformConstants() {}

  public static final Label INTERNAL_PLATFORM =
      Label.parseCanonicalUnchecked("@bazel_tools//tools:internal_platform");

  // The label of the toolchain type to add to the default "test" exec group.
  public static final Label DEFAULT_TEST_TOOLCHAIN_TYPE =
      Label.parseCanonicalUnchecked("@bazel_tools//tools/test:default_test_toolchain_type");
}
