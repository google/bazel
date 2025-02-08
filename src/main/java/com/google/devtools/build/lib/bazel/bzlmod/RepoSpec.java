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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;

/**
 * Contains information about a repo definition, including the ID of the underlying repo rule, and
 * all its attributes (except for the name).
 *
 * @param repoRuleId The repo rule backing this repo.
 * @param attributes All attribute values provided to the repo rule, except for <code>name</code>.
 */
@AutoCodec
@GenerateTypeAdapter
public record RepoSpec(RepoRuleId repoRuleId, AttributeValues attributes) {}
