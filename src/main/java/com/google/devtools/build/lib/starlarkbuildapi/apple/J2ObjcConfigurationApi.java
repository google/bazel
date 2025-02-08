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

package com.google.devtools.build.lib.starlarkbuildapi.apple;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.docgen.annot.DocCategory;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.StarlarkValue;

/** A configuration fragment for j2objc. */
@StarlarkBuiltin(
    name = "j2objc",
    category = DocCategory.CONFIGURATION_FRAGMENT,
    doc = "A configuration fragment for j2Objc.")
public interface J2ObjcConfigurationApi extends StarlarkValue {

  @StarlarkMethod(
      name = "translation_flags",
      structField = true,
      doc = "The list of flags to be used when the j2objc compiler is invoked. ")
  ImmutableList<String> getTranslationFlags();
}
