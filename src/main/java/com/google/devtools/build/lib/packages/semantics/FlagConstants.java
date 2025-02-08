// Copyright 2022 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages.semantics;

/** This file holds hardcoded flag defaults that vary between Bazel and Blaze. */
// TODO(b/254084490): This file is a temporary hack. Eliminate once we've flipped the incompatible
// flag in Blaze.
class FlagConstants {

  private FlagConstants() {}

  public static final String DEFAULT_EXPERIMENTAL_RULE_EXTENSION_API = "true";
  public static final String DEFAULT_EXPERIMENTAL_RULE_EXTENSION_API_NAME = "+experimental_rule_extension_api";

  // TODO - ilist@: once Java providers are removed, the whole line can be compressed to "@rules_java"
  public static final String DEFAULT_INCOMPATIBLE_AUTOLOAD_EXTERNALLY =
      "+@rules_python," +
      "+java_common,+JavaInfo,+JavaPluginInfo,ProguardSpecProvider," +
      "java_binary,java_import,java_library,java_plugin,java_test," +
      "java_runtime,java_toolchain,java_package_configuration," +
      "@com_google_protobuf," +
      "@rules_shell," +
      "+@rules_android";

  public static final String DEFAULT_INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX = "true";
  public static final String DEFAULT_INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX = "true";

  public static final String INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX =
      "+incompatible_package_group_has_public_syntax";
  public static final String INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX =
      "+incompatible_fix_package_group_reporoot_syntax";
}
