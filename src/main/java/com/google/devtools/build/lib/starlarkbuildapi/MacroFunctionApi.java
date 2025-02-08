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

/** The interface for a Starlark symbolic macro. */
@StarlarkBuiltin(
    name = "macro",
    category = DocCategory.BUILTIN,
    doc =
        """
A callable Starlark value representing a symbolic macro; in other words, the return value of
<a href="../globals/bzl.html#macro"><code>macro()</code></a>. Invoking this value during package
construction time will instantiate the macro, and cause the macro's implementation function to be
evaluated (in a separate context, different from the context in which the macro value was invoked),
in most cases causing targets to be added to the package's target set. For more information, see
<a href="https://bazel.build/extending/macros">Macros</a>.
""")
// Ideally, this interface should be merged with MacroFunction, but that would cause a circular
// dependency between packages and starlarkbuildapi.
public interface MacroFunctionApi extends StarlarkCallable {}
