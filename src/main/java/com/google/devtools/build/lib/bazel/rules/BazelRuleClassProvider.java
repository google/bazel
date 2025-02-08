// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.rules;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.RuleSet;
import com.google.devtools.build.lib.analysis.PackageSpecificationProvider;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.ShellConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.bazel.BazelConfiguration;
import com.google.devtools.build.lib.bazel.repository.LocalConfigPlatformRule;
import com.google.devtools.build.lib.bazel.rules.python.BazelPyBuiltins;
import com.google.devtools.build.lib.bazel.rules.python.BazelPythonConfiguration;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.PackageCallable;
import com.google.devtools.build.lib.rules.android.AndroidConfiguration;
import com.google.devtools.build.lib.rules.android.AndroidStarlarkCommon;
import com.google.devtools.build.lib.rules.android.BazelAndroidConfiguration;
import com.google.devtools.build.lib.rules.config.ConfigRules;
import com.google.devtools.build.lib.rules.core.CoreRules;
import com.google.devtools.build.lib.rules.cpp.CcStarlarkInternal;
import com.google.devtools.build.lib.rules.objc.ObjcStarlarkInternal;
import com.google.devtools.build.lib.rules.platform.PlatformRules;
import com.google.devtools.build.lib.rules.proto.BazelProtoCommon;
import com.google.devtools.build.lib.rules.proto.ProtoConfiguration;
import com.google.devtools.build.lib.rules.python.PythonConfiguration;
import com.google.devtools.build.lib.rules.repository.CoreWorkspaceRules;
import com.google.devtools.build.lib.rules.repository.NewLocalRepositoryRule;
import com.google.devtools.build.lib.rules.test.TestingSupportRules;
import com.google.devtools.build.lib.starlarkbuildapi.android.AndroidBootstrap;
import com.google.devtools.build.lib.starlarkbuildapi.core.ContextGuardedValue;
import com.google.devtools.build.lib.starlarkbuildapi.python.PyBootstrap;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.ResourceFileLoader;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.eval.Starlark;

/** A rule class provider implementing the rules Bazel knows. */
public class BazelRuleClassProvider {
  /** Command-line options. */
  public static class StrictActionEnvOptions extends FragmentOptions {
    @Option(
        name = "incompatible_strict_action_env",
        oldName = "experimental_strict_action_env",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help =
            "If true, Bazel uses an environment with a static value for PATH and does not "
                + "inherit LD_LIBRARY_PATH. Use --action_env=ENV_VARIABLE if you want to "
                + "inherit specific environment variables from the client, but note that doing so "
                + "can prevent cross-user caching if a shared cache is used.")
    public boolean useStrictActionEnv;
  }

  private static final PathFragment FALLBACK_SHELL = PathFragment.create("/bin/bash");

  public static final ImmutableMap<OS, PathFragment> SHELL_EXECUTABLE =
      ImmutableMap.<OS, PathFragment>builder()
          .put(OS.WINDOWS, PathFragment.create("c:/msys64/usr/bin/bash.exe"))
          .put(OS.FREEBSD, PathFragment.create("/usr/local/bin/bash"))
          .put(OS.OPENBSD, PathFragment.create("/usr/local/bin/bash"))
          .put(OS.UNKNOWN, FALLBACK_SHELL)
          .buildOrThrow();

  /**
   * {@link BuildConfigurationFunction} constructs {@link BuildOptions} out of the options required
   * by the registered fragments. We create and register this fragment exclusively to ensure {@link
   * StrictActionEnvOptions} is always available.
   */
  @RequiresOptions(options = {StrictActionEnvOptions.class})
  public static class StrictActionEnvConfiguration extends Fragment {
    public StrictActionEnvConfiguration(BuildOptions buildOptions) {}
  }

  @Nullable
  public static PathFragment getDefaultPathFromOptions(ShellConfiguration.Options options) {
    if (options.shellExecutable != null) {
      return options.shellExecutable;
    }

    // Honor BAZEL_SH env variable for backwards compatibility.
    String path = System.getenv("BAZEL_SH");
    if (path != null) {
      return PathFragment.create(path);
    }
    return null;
  }

  @VisibleForTesting
  static PathFragment getShellExecutableForOs(OS os, ShellConfiguration.Options options) {
    // TODO(ulfjack): instead of using the OS Bazel runs on, we need to use the exec platform,
    // which may be different for remote execution. For now, this can be overridden with
    // --shell_executable, so at least there's a workaround.
    return getDefaultPathFromOptions(options) != null
        ? getDefaultPathFromOptions(options)
        : SHELL_EXECUTABLE.getOrDefault(os, FALLBACK_SHELL);
  }

  public static final Function<BuildOptions, ActionEnvironment> SHELL_ACTION_ENV =
      (BuildOptions options) -> {
        if (options.hasNoConfig()) {
          return ActionEnvironment.EMPTY;
        }
        boolean strictActionEnv = options.get(StrictActionEnvOptions.class).useStrictActionEnv;
        OS os = OS.getCurrent();
        // TODO(ulfjack): instead of using the OS Bazel runs on, we need to use the exec platform,
        // which may be different for remote execution. For now, this can be overridden with
        // --shell_executable, so at least there's a workaround.
        PathFragment shellExecutable =
            getShellExecutableForOs(os, options.get(ShellConfiguration.Options.class));

        TreeMap<String, String> env = new TreeMap<>();

        // All entries in the builder that have a value of null inherit the value from the client
        // environment, which is only known at execution time - we don't want to bake the client env
        // into the configuration since any change to the configuration requires rerunning the full
        // analysis phase.
        if (!strictActionEnv) {
          env.put("LD_LIBRARY_PATH", null);
        }

        if (strictActionEnv) {
          env.put("PATH", pathOrDefault(os, null, shellExecutable));
        } else if (os == OS.WINDOWS) {
          // TODO(ulfjack): We want to add the MSYS root to the PATH, but that prevents us from
          // inheriting PATH from the client environment. For now we use System.getenv even though
          // that is incorrect. We should enable strict_action_env by default and then remove this
          // code, but that change may break Windows users who are relying on the MSYS root being in
          // the PATH.
          env.put("PATH", pathOrDefault(os, System.getenv("PATH"), shellExecutable));
        } else {
          // The previous implementation used System.getenv (which uses the server's environment),
          // and fell back to a hard-coded "/bin:/usr/bin" if PATH was not set.
          env.put("PATH", null);
        }

        // Shell environment variables specified via options take precedence over the
        // ones inherited from the fragments. In the long run, these fragments will
        // be replaced by appropriate default rc files anyway.
        for (Map.Entry<String, String> entry : options.get(CoreOptions.class).actionEnvironment) {
          env.put(entry.getKey(), entry.getValue());
        }

        if (!BuildConfigurationValue.runfilesEnabled(options.get(CoreOptions.class))) {
          // Setting this environment variable is for telling the binary running
          // in a Bazel action when to use runfiles library or runfiles tree.
          // The downside is that it will discard cache for all actions once
          // --enable_runfiles changes, but this also prevents wrong caching result if a binary
          // behaves differently with and without runfiles tree.
          env.put("RUNFILES_MANIFEST_ONLY", "1");
        }

        return ActionEnvironment.split(env);
      };

  /** Convenience wrapper around {@link #setup} that returns a final ConfiguredRuleClassProvider. */
  // Used by the build encyclopedia generator.
  public static ConfiguredRuleClassProvider create() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    setup(builder);
    return builder.build();
  }

  /** Adds this class's definitions to a builder. */
  public static void setup(ConfiguredRuleClassProvider.Builder builder) {
    builder.setToolsRepository(RepositoryName.BAZEL_TOOLS);
    builder.setBuiltinsBzlZipResource(
        ResourceFileLoader.resolveResource(BazelRuleClassProvider.class, "builtins_bzl.zip"));
    builder.setBuiltinsBzlPackagePathInSource("src/main/starlark/builtins_bzl");

    for (RuleSet ruleSet : RULE_SETS) {
      ruleSet.init(builder);
    }
  }

  public static final RuleSet BAZEL_SETUP =
      new RuleSet() {
        @Override
        public void init(ConfiguredRuleClassProvider.Builder builder) {
          ShellConfiguration.injectShellExecutableFinder(
              BazelRuleClassProvider::getDefaultPathFromOptions, SHELL_EXECUTABLE);
          builder
              .setPrelude("//tools/build_rules:prelude_bazel")
              .setRunfilesPrefix("_main")
              .setPrerequisiteValidator(new BazelPrerequisiteValidator())
              .setActionEnvironmentProvider(SHELL_ACTION_ENV)
              .addUniversalConfigurationFragment(ShellConfiguration.class)
              .addUniversalConfigurationFragment(PlatformConfiguration.class)
              .addUniversalConfigurationFragment(BazelConfiguration.class)
              .addUniversalConfigurationFragment(StrictActionEnvConfiguration.class)
              .addConfigurationOptions(CoreOptions.class);

          builder.addStarlarkBuiltinsInternal(
              ObjcStarlarkInternal.NAME, new ObjcStarlarkInternal());
          builder.addStarlarkBuiltinsInternal(CcStarlarkInternal.NAME, new CcStarlarkInternal());

          // Add the package() function.
          // TODO(bazel-team): Factor this into a group of similar BUILD definitions, or add a more
          // convenient way of obtaining a BuiltinFunction than addMethods().
          ImmutableMap.Builder<String, Object> symbols = ImmutableMap.builder();
          Starlark.addMethods(symbols, PackageCallable.INSTANCE);
          for (Map.Entry<String, Object> entry : symbols.buildOrThrow().entrySet()) {
            builder.addBuildFileToplevel(entry.getKey(), entry.getValue());
          }
        }
      };

  public static final RuleSet PROTO_RULES =
      new RuleSet() {
        @Override
        public void init(ConfiguredRuleClassProvider.Builder builder) {
          builder.addConfigurationFragment(ProtoConfiguration.class);
          builder.addBzlToplevel("proto_common_do_not_use", BazelProtoCommon.INSTANCE);
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CoreRules.INSTANCE);
        }
      };

  public static final RuleSet ANDROID_RULES =
      new RuleSet() {
        @Override
        public void init(ConfiguredRuleClassProvider.Builder builder) {

          builder.addConfigurationFragment(AndroidConfiguration.class);
          builder.addConfigurationFragment(BazelAndroidConfiguration.class);

          AndroidBootstrap bootstrap = new AndroidBootstrap(new AndroidStarlarkCommon());
          builder.addStarlarkBootstrap(bootstrap);

          try {
            builder.addWorkspaceFileSuffix(
                ResourceFileLoader.loadResource(JavaRules.class, "coverage.WORKSPACE"));
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CoreRules.INSTANCE, CcRules.INSTANCE, JavaRules.INSTANCE);
        }
      };

  public static final RuleSet PYTHON_RULES =
      new RuleSet() {
        @Override
        public void init(ConfiguredRuleClassProvider.Builder builder) {
          builder.addConfigurationFragment(PythonConfiguration.class);
          builder.addConfigurationFragment(BazelPythonConfiguration.class);

          // This symbol is overridden by exports.bzl
          builder.addBzlToplevel(
              "py_internal",
              ContextGuardedValue.onlyInAllowedRepos(
                  Starlark.NONE, PyBootstrap.allowedRepositories));
          builder.addStarlarkBuiltinsInternal(BazelPyBuiltins.NAME, new BazelPyBuiltins());

          try {
            builder.addWorkspaceFileSuffix(
                ResourceFileLoader.loadResource(
                    BazelPythonConfiguration.class, "python.WORKSPACE"));
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CoreRules.INSTANCE, CcRules.INSTANCE);
        }
      };

  public static final RuleSet VARIOUS_WORKSPACE_RULES =
      new RuleSet() {
        @Override
        public void init(ConfiguredRuleClassProvider.Builder builder) {
          // TODO(ulfjack): Split this up by conceptual units.
          builder.addRuleDefinition(new NewLocalRepositoryRule());
          builder.addRuleDefinition(new LocalConfigPlatformRule());

          try {
            builder.addWorkspaceFileSuffix(
                ResourceFileLoader.loadResource(
                    LocalConfigPlatformRule.class, "local_config_platform.WORKSPACE"));
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CoreRules.INSTANCE, CoreWorkspaceRules.INSTANCE);
        }
      };

  static final RuleSet PACKAGING_RULES =
      new RuleSet() {
        @Override
        public void init(ConfiguredRuleClassProvider.Builder builder) {
          builder.addBzlToplevel("PackageSpecificationInfo", PackageSpecificationProvider.PROVIDER);
        }
      };

  private static final ImmutableSet<RuleSet> RULE_SETS =
      ImmutableSet.of(
          BAZEL_SETUP,
          CoreRules.INSTANCE,
          CoreWorkspaceRules.INSTANCE,
          GenericRules.INSTANCE,
          ConfigRules.INSTANCE,
          PlatformRules.INSTANCE,
          PROTO_RULES,
          CcRules.INSTANCE,
          JavaRules.INSTANCE,
          ANDROID_RULES,
          PYTHON_RULES,
          ObjcRules.INSTANCE,
          TestingSupportRules.INSTANCE,
          VARIOUS_WORKSPACE_RULES,
          PACKAGING_RULES,
          // This rule set is a little special: it needs to depend on every configuration fragment
          // that has Make variables, so we put it last.
          ToolchainRules.INSTANCE);

  @VisibleForTesting
  public static String pathOrDefault(OS os, @Nullable String path, @Nullable PathFragment sh) {
    // TODO(ulfjack): The default PATH should be set from the exec platform, which may be different
    // from the local machine. For now, this can be overridden with --action_env=PATH=<value>, so
    // at least there's a workaround.
    if (os != OS.WINDOWS) {
      // The default used to be "/bin:/usr/bin". However, on Mac the Python 3 interpreter, if it is
      // installed at all, tends to be under /usr/local/bin. The autodetecting Python toolchain
      // searches PATH for "python3", so if we don't include this directory then we can't run PY3
      // targets with this toolchain if strict action environment is on.
      //
      // Note that --action_env does not propagate to the exec config, so it is not a viable
      // workaround when a genrule is itself built in the exec config (e.g. nested genrules). See
      // #8536.
      return "/bin:/usr/bin:/usr/local/bin";
    }

    String newPath = "";
    // Attempt to compute the MSYS root (the real Windows path of "/") from `sh`.
    if (sh != null && sh.getParentDirectory() != null) {
      newPath = sh.getParentDirectory().getPathString();
      if (sh.getParentDirectory().endsWith(PathFragment.create("usr/bin"))) {
        newPath +=
            ";" + sh.getParentDirectory().getParentDirectory().replaceName("bin").getPathString();
      } else if (sh.getParentDirectory().endsWith(PathFragment.create("bin"))) {
        newPath +=
            ";" + sh.getParentDirectory().replaceName("usr").getRelative("bin").getPathString();
      }
      newPath = newPath.replace('/', '\\');
    }
    // On Windows, the following dirs should always be available in PATH:
    //   C:\Windows
    //   C:\Windows\System32
    //   C:\Windows\System32\WindowsPowerShell\v1.0
    // They are similar to /bin:/usr/bin, which makes the basic tools on the platform available.
    String systemRoot = System.getenv("SYSTEMROOT");
    if (Strings.isNullOrEmpty(systemRoot)) {
      systemRoot = "C:\\Windows";
    }
    newPath += ";" + systemRoot;
    newPath += ";" + systemRoot + "\\System32";
    newPath += ";" + systemRoot + "\\System32\\WindowsPowerShell\\v1.0";
    if (path != null) {
      newPath += ";" + path;
    }
    return newPath;
  }
}
