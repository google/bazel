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

package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.StarlarkThread;

/**
 * The provider for the built-in type {@code struct}.
 *
 * <p>Its singleton instance is {@link StructProvider#STRUCT}.
 */
public final class StructProvider extends BuiltinProvider<StarlarkInfo>
    implements StructApi.StructProviderApi {

  /** Provider of "struct" instances. */
  public static final StructProvider STRUCT = new StructProvider();

  private StructProvider() {
    super("struct", StarlarkInfo.class);
  }

  /** Implementation of {@code struct(**kwargs)} function exposed to Starlark. */
  @Override
  public StructImpl createStruct(Dict<String, Object> kwargs, StarlarkThread thread) {
    return StarlarkInfo.create(this, kwargs, thread.getCallerLocation());
  }

  /**
   * Creates a struct with the given field values and message format for unknown fields.
   *
   * <p>The custom message is useful for objects that have fields but aren't exactly used as
   * providers, such as the {@code native} object, and the struct fields of {@code ctx} like {@code
   * ctx.attr}.
   */
  public StarlarkInfo create(Map<String, Object> fields, String errorMessageFormatForUnknownField) {
    return StarlarkInfoWithMessage.createWithCustomMessage(
        this, fields, errorMessageFormatForUnknownField);
  }
}
