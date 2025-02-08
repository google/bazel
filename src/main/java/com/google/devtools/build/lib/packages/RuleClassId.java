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

package com.google.devtools.build.lib.packages;

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;

/**
 * Identifier for a RuleClass object including both the common ruleClass name and a unique
 * identifier for that ruleClass to disambiguate two Starlark rule classes with the same common
 * name.
 */
@AutoCodec
public record RuleClassId(String name, String key) {
  public RuleClassId {
    requireNonNull(name, "name");
    requireNonNull(key, "key");
  }

  public static RuleClassId create(String name, String key) {
    return new RuleClassId(name, key);
  }
}
