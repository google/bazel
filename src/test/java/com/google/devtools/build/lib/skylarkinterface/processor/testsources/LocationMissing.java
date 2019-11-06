// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skylarkinterface.processor.testsources;

import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.StarlarkThread;

/**
 * Test case for a SkylarkCallable method which does not have an appropriate StarlarkThread
 * parameter despite having useLocation set.
 */
public class LocationMissing implements SkylarkValue {

  @SkylarkCallable(
      name = "three_arg_method_missing_location",
      documented = false,
      parameters = {
        @Param(name = "one", type = String.class, named = true),
        @Param(name = "two", type = Integer.class, named = true),
      },
      useLocation = true,
      useStarlarkThread = true)
  public String threeArgMethod(String one, Integer two, String shouldBeLoc, StarlarkThread thread) {
    return "bar";
  }
}
