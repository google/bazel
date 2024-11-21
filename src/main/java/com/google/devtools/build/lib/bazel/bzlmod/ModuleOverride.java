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
//

package com.google.devtools.build.lib.bazel.bzlmod;

/**
 * Represents an "override" applied to a particular module in the dependency graph. Must be of one
 * of two categories: {@link RegistryOverride} and {@link NonRegistryOverride}. See there for
 * further details.
 */
// This interface is not named "Override" because of the Java @Override annotation.
public sealed interface ModuleOverride permits RegistryOverride, NonRegistryOverride {
  // This space intentionally left blank
}
