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

package com.google.devtools.build.lib.starlarkbuildapi;

import com.google.devtools.build.docgen.annot.DocCategory;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.StarlarkCallable;

/** Interface for a native or Starlark rule function. */
@StarlarkBuiltin(
    name = "rule",
    category = DocCategory.BUILTIN,
    doc =
        """
        A callable value representing the type of a native or Starlark rule (created by
        <a href="../globals/bzl.html#rule"><code>rule()</code></a>). Calling the value during
        evaluation of a package's BUILD file creates an instance of the rule and adds it to the
        package's target set. For more information, visit this page about
        <a href ="https://bazel.build/extending/rules">Rules</a>.
        """)
// Ideally, this interface should be merged with RuleFunction, but that would cause a circular
// dependency between packages and starlarkbuildapi.
public interface RuleFunctionApi extends StarlarkCallable {}
