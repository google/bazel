// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/** Static utility methods pertaining to restricting Starlark method invocations */
// TODO(bazel-team): Maybe we can merge this utility class with some other existing allowlist
// helper? But it seems like a lot of existing allowlist machinery is geared toward allowlists on
// rule attributes rather than what .bzl you're in.
public final class BuiltinRestriction {

  /** The "default" allowlist for restricted APIs added to aid the Java to Starlark migration. */
  public static final ImmutableList<BuiltinRestriction.AllowlistEntry>
      INTERNAL_STARLARK_API_ALLOWLIST =
          ImmutableList.of(
              // Testing
              BuiltinRestriction.allowlistEntry("", "test"),
              BuiltinRestriction.allowlistEntry("", "bazel_internal/test_rules"),

              // BuildInfo
              BuiltinRestriction.allowlistEntry("", "tools/build_defs/build_info"),
              BuiltinRestriction.allowlistEntry("bazel_tools", "tools/build_defs/build_info"),

              // Android rules
              BuiltinRestriction.allowlistEntry("", "bazel_internal/test_rules/cc"),
              BuiltinRestriction.allowlistEntry("", "tools/build_defs/android"),
              BuiltinRestriction.allowlistEntry("", "third_party/bazel_rules/rules_android"),
              BuiltinRestriction.allowlistEntry("rules_android", ""),
              BuiltinRestriction.allowlistEntry("build_bazel_rules_android", ""),

              // Apple rules
              BuiltinRestriction.allowlistEntry("", "third_party/apple_crosstool"),
              BuiltinRestriction.allowlistEntry("", "third_party/bazel_rules/rules_apple"),
              BuiltinRestriction.allowlistEntry("rules_apple", ""),
              BuiltinRestriction.allowlistEntry("build_bazel_rules_apple", ""),

              // Cc rules
              BuiltinRestriction.allowlistEntry("", "third_party/bazel_rules/rules_cc"),
              BuiltinRestriction.allowlistEntry("rules_cc", ""),

              // Java rules
              BuiltinRestriction.allowlistEntry("", "third_party/bazel_rules/rules_java"),
              BuiltinRestriction.allowlistEntry("rules_java", ""),

              // Rust rules
              BuiltinRestriction.allowlistEntry(
                  "", "third_party/bazel_rules/rules_rust/rust/private"),
              BuiltinRestriction.allowlistEntry("", "third_party/crubit"),
              BuiltinRestriction.allowlistEntry("rules_rust", "rust/private"),

              // CUDA rules
              BuiltinRestriction.allowlistEntry("", "third_party/gpus/cuda"),

              // Packaging rules
              BuiltinRestriction.allowlistEntry("", "tools/build_defs/packaging"),

              // Go rules
              BuiltinRestriction.allowlistEntry("", "tools/build_defs/go"),

              // Proto rules
              BuiltinRestriction.allowlistEntry("", "third_party/protobuf"),
              BuiltinRestriction.allowlistEntry("protobuf", ""),
              BuiltinRestriction.allowlistEntry("com_google_protobuf", ""),

              // Shell rules
              BuiltinRestriction.allowlistEntry("rules_shell", ""));

  private BuiltinRestriction() {}

  /**
   * Throws {@code EvalException} if the innermost Starlark function in the given thread's call
   * stack is not defined within the builtins repository.
   *
   * @throws NullPointerException if there is no currently executing Starlark function, or the
   *     innermost Starlark function's module is not a .bzl file
   */
  public static void failIfCalledOutsideBuiltins(StarlarkThread thread) throws EvalException {
    Label currentFile = BazelModuleContext.ofInnermostBzlOrThrow(thread).label();
    if (!currentFile.getRepository().getName().equals("_builtins")) {
      throw Starlark.errorf(
          "file '%s' cannot use private @_builtins API", currentFile.getCanonicalForm());
    }
  }

  /**
   * An entry in an allowlist that can be checked using {@link #failIfCalledOutsideAllowlist} or
   * {@link #failIfModuleOutsideAllowlist}.
   */
  @AutoValue
  public abstract static class AllowlistEntry {
    abstract String apparentRepoName();

    abstract PathFragment packagePrefix();

    static AllowlistEntry create(String apparentRepoName, PathFragment packagePrefix) {
      return new AutoValue_BuiltinRestriction_AllowlistEntry(apparentRepoName, packagePrefix);
    }

    final boolean allows(Label label, RepositoryMapping repoMapping) {
      return label.getRepository().equals(repoMapping.get(apparentRepoName()))
          && label.getPackageFragment().startsWith(packagePrefix());
    }
  }

  /**
   * Creates an {@link AllowlistEntry}. This is essentially an unresolved package identifier; that
   * is, a package identifier that has an apparent repo name in place of a canonical repo name.
   */
  public static AllowlistEntry allowlistEntry(String apparentRepoName, String packagePrefix) {
    return AllowlistEntry.create(apparentRepoName, PathFragment.create(packagePrefix));
  }

  /**
   * Throws {@code EvalException} if the innermost Starlark function in the given thread's call
   * stack is not defined within either 1) the builtins repository, or 2) a package or subpackage of
   * an entry in the given allowlist.
   *
   * @throws NullPointerException if there is no currently executing Starlark function, or the
   *     innermost Starlark function's module is not a .bzl file
   */
  public static void failIfCalledOutsideAllowlist(
      StarlarkThread thread, Collection<AllowlistEntry> allowlist) throws EvalException {
    failIfModuleOutsideAllowlist(BazelModuleContext.ofInnermostBzlOrThrow(thread), allowlist);
  }

  /**
   * Throws {@code EvalException} if the call is made outside of the default allowlist or outside of
   * builtins.
   *
   * @throws NullPointerException if there is no currently executing Starlark function, or the
   *     innermost Starlark function's module is not a .bzl file
   */
  public static void failIfCalledOutsideDefaultAllowlist(StarlarkThread thread)
      throws EvalException {
    failIfCalledOutsideAllowlist(thread, INTERNAL_STARLARK_API_ALLOWLIST);
  }

  /**
   * Throws {@code EvalException} if the given {@link BazelModuleContext} is not within either 1)
   * the builtins repository, or 2) a package or subpackage of an entry in the given allowlist.
   */
  public static void failIfModuleOutsideAllowlist(
      BazelModuleContext moduleContext, Collection<AllowlistEntry> allowlist) throws EvalException {
    failIfLabelOutsideAllowlist(moduleContext.label(), moduleContext.repoMapping(), allowlist);
  }

  /**
   * Throws {@code EvalException} if the given {@link Label} is not within either 1) the builtins
   * repository, or 2) a package or subpackage of an entry in the given allowlist.
   */
  public static void failIfLabelOutsideAllowlist(
      Label label, RepositoryMapping repoMapping, Collection<AllowlistEntry> allowlist)
      throws EvalException {
    if (isNotAllowed(label, repoMapping, allowlist)) {
      throw Starlark.errorf("file '%s' cannot use private API", label.getCanonicalForm());
    }
  }

  /**
   * Returns true if the given {@link Label} is not within both 1) the builtins repository, or 2) a
   * package or subpackage of an entry in the given allowlist.
   */
  public static boolean isNotAllowed(
      Label label, RepositoryMapping repoMapping, Collection<AllowlistEntry> allowlist) {
    if (label.getRepository().getName().equals("_builtins")) {
      return false;
    }
    return allowlist.stream().noneMatch(e -> e.allows(label, repoMapping));
  }
}
