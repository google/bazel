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
package com.google.devtools.build.lib.util;

import com.google.common.collect.ImmutableMap;

/**
 * Converter for memory resources, which takes an integer greater than or equal to 0, or
 * "HOST_RAM", optionally followed by [-|*]<float>.
 */
public final class RamResourceConverter extends ResourceConverter.IntegerConverter {
  public RamResourceConverter() {
    super(
        /* keywords= */ ImmutableMap.of(HOST_RAM_KEYWORD, HOST_RAM_SUPPLIER),
        /* minValue= */ 0,
        /* maxValue= */ Integer.MAX_VALUE);
  }

  @Override
  public String getTypeDescription() {
    return String.format(
        "an integer number of MBs, or \"%s\", optionally followed by [-|*]<float>.",
        HOST_RAM_KEYWORD);
  }
}
