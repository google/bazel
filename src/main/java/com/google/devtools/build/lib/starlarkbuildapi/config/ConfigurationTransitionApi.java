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

package com.google.devtools.build.lib.starlarkbuildapi.config;

import com.google.devtools.build.docgen.annot.DocCategory;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.StarlarkValue;

/** Represents a configuration transition across a dependency edge. */
@StarlarkBuiltin(
    name = "transition",
    category = DocCategory.BUILTIN,
    doc =
        "<p>Represents a configuration transition across a dependency edge. For example, if"
            + " <code>//package:foo</code> depends on <code>//package:bar</code> with a"
            + " configuration transition, then the configuration of <code>//package:bar</code> (and"
            + " its dependencies) will be <code>//package:foo</code>'s configuration plus the"
            + " changes specified by the transition function.")
public interface ConfigurationTransitionApi extends StarlarkValue {}
