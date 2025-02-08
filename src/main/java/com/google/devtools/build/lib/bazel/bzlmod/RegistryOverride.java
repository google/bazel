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
 * An override specifying that the module should still come from a registry, albeit with some other
 * properties overridden (such as which registry it comes from, whether patches are applied, etc.)
 */
public sealed interface RegistryOverride extends ModuleOverride
    permits SingleVersionOverride, MultipleVersionOverride {

  /**
   * The registry that should be used instead of the default list. Can be empty if there is no
   * override on the registry to use.
   */
  String getRegistry();
}
