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
package com.google.devtools.build.lib.packages.util;

import static java.util.stream.Collectors.joining;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.cpp.CppRuleClasses;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.Pair;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A helper class to create a crosstool package containing a CROSSTOOL file, and the various rules
 * needed for a mock - use this only for configured target tests, not for execution tests.
 */
public final class Crosstool {
  private static final ImmutableList<String> CROSSTOOL_BINARIES =
      ImmutableList.of("ar", "as", "compile", "dwp", "link", "objcopy", "llvm-profdata");

  /**
   * A class that contains relevant fields from either the CROSSTOOL file or the Starlark rule
   * implementation that are needed in order to generate the BUILD file.
   */
  public static final class CcToolchainConfig {
    private final String cpu;
    private final String compiler;
    private final String toolchainIdentifier;
    private final String hostSystemName;
    private final String targetSystemName;
    private final String abiVersion;
    private final String abiLibcVersion;
    private final String targetLibc;
    private final String builtinSysroot;
    private final String ccTargetOs;
    private final ImmutableList<String> features;
    private final ImmutableList<String> actionConfigs;
    private final ImmutableList<ImmutableList<String>> artifactNamePatterns;
    private final ImmutableList<Pair<String, String>> toolPaths;
    private final ImmutableList<String> cxxBuiltinIncludeDirectories;
    private final ImmutableList<Pair<String, String>> makeVariables;
    private final ImmutableList<String> toolchainExecConstraints;
    private final ImmutableList<String> toolchainTargetConstraints;

    private CcToolchainConfig(
        String cpu,
        String compiler,
        String toolchainIdentifier,
        String hostSystemName,
        String targetSystemName,
        String abiVersion,
        String abiLibcVersion,
        String targetLibc,
        String builtinSysroot,
        String ccTargetOs,
        ImmutableList<String> features,
        ImmutableList<String> actionConfigs,
        ImmutableList<ImmutableList<String>> artifactNamePatterns,
        ImmutableList<Pair<String, String>> toolPaths,
        ImmutableList<String> cxxBuiltinIncludeDirectories,
        ImmutableList<Pair<String, String>> makeVariables,
        ImmutableList<String> toolchainExecConstraints,
        ImmutableList<String> toolchainTargetConstraints) {
      this.cpu = cpu;
      this.compiler = compiler;
      this.toolchainIdentifier = toolchainIdentifier;
      this.hostSystemName = hostSystemName;
      this.targetSystemName = targetSystemName;
      this.abiVersion = abiVersion;
      this.abiLibcVersion = abiLibcVersion;
      this.targetLibc = targetLibc;
      this.features = features;
      this.actionConfigs = actionConfigs;
      this.artifactNamePatterns = artifactNamePatterns;
      this.toolPaths = toolPaths;
      this.builtinSysroot = builtinSysroot;
      this.cxxBuiltinIncludeDirectories = cxxBuiltinIncludeDirectories;
      this.makeVariables = makeVariables;
      this.ccTargetOs = ccTargetOs;
      this.toolchainExecConstraints = toolchainExecConstraints;
      this.toolchainTargetConstraints = toolchainTargetConstraints;
    }

    public static Builder builder() {
      return new Builder();
    }

    /** A Builder for {@link CcToolchainConfig}. */
    public static class Builder {
      private ImmutableList<String> features = ImmutableList.of();
      private ImmutableList<String> actionConfigs = ImmutableList.of();
      private ImmutableList<ImmutableList<String>> artifactNamePatterns = ImmutableList.of();
      private ImmutableList<Pair<String, String>> toolPaths = ImmutableList.of();
      private String builtinSysroot = "/usr/grte/v1";
      private ImmutableList<String> cxxBuiltinIncludeDirectories = ImmutableList.of();
      private ImmutableList<Pair<String, String>> makeVariables = ImmutableList.of();
      private String ccTargetOs = "";
      private String cpu = "k8";
      private String compiler = "compiler";
      private String toolchainIdentifier = "mock-toolchain-k8";
      private String hostSystemName = "local";
      private String targetSystemName = "local";
      private String targetLibc = "local";
      private String abiVersion = "local";
      private String abiLibcVersion = "local";
      private ImmutableList<String> toolchainExecConstraints =
          ImmutableList.of(
              TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
              TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux");
      private ImmutableList<String> toolchainTargetConstraints =
          ImmutableList.of(
              TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
              TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux");

      @CanIgnoreReturnValue
      public Builder withCpu(String cpu) {
        this.cpu = cpu;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withCompiler(String compiler) {
        this.compiler = compiler;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withToolchainIdentifier(String toolchainIdentifier) {
        this.toolchainIdentifier = toolchainIdentifier;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withHostSystemName(String hostSystemName) {
        this.hostSystemName = hostSystemName;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withTargetSystemName(String targetSystemName) {
        this.targetSystemName = targetSystemName;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withTargetLibc(String targetLibc) {
        this.targetLibc = targetLibc;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withAbiVersion(String abiVersion) {
        this.abiVersion = abiVersion;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withAbiLibcVersion(String abiLibcVersion) {
        this.abiLibcVersion = abiLibcVersion;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withFeatures(String... features) {
        this.features = ImmutableList.copyOf(features);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withActionConfigs(String... actionConfigs) {
        this.actionConfigs = ImmutableList.copyOf(actionConfigs);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withArtifactNamePatterns(ImmutableList<String>... artifactNamePatterns) {
        for (ImmutableList<String> pattern : artifactNamePatterns) {
          Preconditions.checkArgument(
              pattern.size() == 3,
              "Artifact name pattern should have three attributes: category_name, prefix and"
                  + " extension");
        }
        this.artifactNamePatterns = ImmutableList.copyOf(artifactNamePatterns);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withToolPaths(Pair<String, String>... toolPaths) {
        this.toolPaths = ImmutableList.copyOf(toolPaths);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withSysroot(String sysroot) {
        this.builtinSysroot = sysroot;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withCcTargetOs(String ccTargetOs) {
        this.ccTargetOs = ccTargetOs;
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withCxxBuiltinIncludeDirectories(String... directories) {
        this.cxxBuiltinIncludeDirectories = ImmutableList.copyOf(directories);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withMakeVariables(Pair<String, String>... makeVariables) {
        this.makeVariables = ImmutableList.copyOf(makeVariables);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withToolchainExecConstraints(String... execConstraints) {
        this.toolchainExecConstraints = ImmutableList.copyOf(execConstraints);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder withToolchainTargetConstraints(String... targetConstraints) {
        this.toolchainTargetConstraints = ImmutableList.copyOf(targetConstraints);
        return this;
      }

      public CcToolchainConfig build() {
        return new CcToolchainConfig(
            cpu,
            compiler,
            toolchainIdentifier,
            hostSystemName,
            targetSystemName,
            abiVersion,
            abiLibcVersion,
            targetLibc,
            builtinSysroot,
            ccTargetOs,
            features,
            actionConfigs,
            artifactNamePatterns,
            toolPaths,
            cxxBuiltinIncludeDirectories,
            makeVariables,
            toolchainExecConstraints,
            toolchainTargetConstraints);
      }
    }

    public String getToolchainIdentifier() {
      return toolchainIdentifier;
    }

    public String getTargetCpu() {
      return cpu;
    }

    public String getCompiler() {
      return compiler;
    }

    private static String formatConstraints(String type, ImmutableList<String> constraints) {
      if (constraints.isEmpty()) {
        return "";
      }

      String output =
          constraints.stream()
              .map(constraint -> String.format("'%s',", constraint))
              .collect(joining("\n"));

      return String.format("%s_compatible_with = [\n%s\n],", type, output);
    }

    public String getToolchainExecConstraints() {
      return formatConstraints("exec", toolchainExecConstraints);
    }

    public String getToolchainTargetConstraints() {
      ImmutableList<String> constraints = this.toolchainTargetConstraints;
      if (constraints.isEmpty()) {
        if (getTargetCpu().equals("k8")) {
          // Use default constraints
          constraints =
              ImmutableList.of(
                  TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
                  TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux");
        } else if (getTargetCpu().equals("darwin_x86_64")) {
          constraints =
              ImmutableList.of(
                  TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
                  TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:macos");
        } else if (getTargetCpu().equals("darwin_arm64")) {
          constraints =
              ImmutableList.of(
                  TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64",
                  TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:macos");
        }
      }
      return formatConstraints("target", constraints);
    }

    public boolean hasStaticLinkCppRuntimesFeature() {
      return features.contains(CppRuleClasses.STATIC_LINK_CPP_RUNTIMES);
    }

    public static CcToolchainConfig getCcToolchainConfigForCpu(String cpu) {
      return new CcToolchainConfig(
          /* cpu= */ cpu,
          /* compiler= */ "mock-compiler-for-" + cpu,
          /* toolchainIdentifier= */ "mock-llvm-toolchain-for-" + cpu,
          /* hostSystemName= */ "mock-system-name-for-" + cpu,
          /* targetSystemName= */ "mock-target-system-name-for-" + cpu,
          /* abiVersion= */ "mock-abi-version-for-" + cpu,
          /* abiLibcVersion= */ "mock-abi-libc-for-" + cpu,
          /* targetLibc= */ "mock-libc-for-" + cpu,
          /* builtinSysroot= */ "",
          /* ccTargetOs= */ "",
          /* features= */ ImmutableList.of(),
          /* actionConfigs= */ ImmutableList.of(),
          /* artifactNamePatterns= */ ImmutableList.of(),
          /* toolPaths= */ ImmutableList.of(),
          /* cxxBuiltinIncludeDirectories= */ ImmutableList.of(),
          /* makeVariables= */ ImmutableList.of(),
          /* toolchainExecConstraints= */ ImmutableList.of(),
          /* toolchainTargetConstraints= */ ImmutableList.of());
    }

    public static CcToolchainConfig getDefaultCcToolchainConfig() {
      return getCcToolchainConfigForCpu("k8");
    }

    public String getCcToolchainConfigRule() {
      ImmutableList<String> featuresList =
          features.stream()
              .map(feature -> "'" + feature + "'")
              .collect(ImmutableList.toImmutableList());
      ImmutableList<String> actionConfigsList =
          actionConfigs.stream()
              .map(config -> "'" + config + "'")
              .collect(ImmutableList.toImmutableList());
      ImmutableList<String> patternsList =
          artifactNamePatterns.stream()
              .map(
                  pattern ->
                      String.format(
                          "'%s': ['%s', '%s']", pattern.get(0), pattern.get(1), pattern.get(2)))
              .collect(ImmutableList.toImmutableList());
      ImmutableList<String> toolPathsList =
          toolPaths.stream()
              .map(toolPath -> String.format("'%s': '%s'", toolPath.first, toolPath.second))
              .collect(ImmutableList.toImmutableList());
      ImmutableList<String> directoriesList =
          cxxBuiltinIncludeDirectories.stream()
              .map(directory -> "'" + directory + "'")
              .collect(ImmutableList.toImmutableList());
      ImmutableList<String> makeVariablesList =
          makeVariables.stream()
              .map(variable -> String.format("'%s': '%s'", variable.first, variable.second))
              .collect(ImmutableList.toImmutableList());

      return Joiner.on("\n")
          .join(
              "cc_toolchain_config(",
              "  name = '" + cpu + "-" + compiler + "_config',",
              "  toolchain_identifier = '" + toolchainIdentifier + "',",
              "  cpu = '" + cpu + "',",
              "  compiler = '" + compiler + "',",
              "  host_system_name = '" + hostSystemName + "',",
              "  target_system_name = '" + targetSystemName + "',",
              "  target_libc = '" + targetLibc + "',",
              "  abi_version = '" + abiVersion + "',",
              "  abi_libc_version = '" + abiLibcVersion + "',",
              String.format("  feature_names = [%s],", Joiner.on(",\n    ").join(featuresList)),
              String.format(
                  "  action_configs = [%s],", Joiner.on(",\n    ").join(actionConfigsList)),
              String.format(
                  "  artifact_name_patterns = {%s},", Joiner.on(",\n    ").join(patternsList)),
              String.format("  tool_paths = {%s},", Joiner.on(",\n    ").join(toolPathsList)),
              "  builtin_sysroot = '" + builtinSysroot + "',",
              "  cc_target_os = '" + ccTargetOs + "',",
              String.format(
                  "  cxx_builtin_include_directories = [%s],",
                  Joiner.on(",\n    ").join(directoriesList)),
              String.format(
                  "  make_variables = {%s},", Joiner.on(",\n    ").join(makeVariablesList)),
              "  )");
    }
  }

  private final MockToolsConfig config;

  private final String crosstoolTop;
  private final Label crosstoolTopLabel;
  private String ccToolchainConfigFileContents;
  private final List<String> archs;
  private boolean supportsHeaderParsing;
  private ImmutableList<CcToolchainConfig> ccToolchainConfigList = ImmutableList.of();

  Crosstool(MockToolsConfig config, String crosstoolTop, Label crosstoolTopLabel) {
    this.config = config;
    this.crosstoolTop = crosstoolTop;
    this.crosstoolTopLabel = crosstoolTopLabel;
    this.archs = new ArrayList<>();
  }

  @CanIgnoreReturnValue
  public Crosstool setCcToolchainFile(String ccToolchainConfigFileContents) {
    this.ccToolchainConfigFileContents = ccToolchainConfigFileContents;
    return this;
  }

  @CanIgnoreReturnValue
  public Crosstool setSupportedArchs(ImmutableList<String> archs) {
    this.archs.clear();
    this.archs.addAll(archs);
    return this;
  }

  @CanIgnoreReturnValue
  public Crosstool setSupportsHeaderParsing(boolean supportsHeaderParsing) {
    this.supportsHeaderParsing = supportsHeaderParsing;
    return this;
  }

  @CanIgnoreReturnValue
  public Crosstool setToolchainConfigs(ImmutableList<CcToolchainConfig> ccToolchainConfigs) {
    this.ccToolchainConfigList = ccToolchainConfigs;
    return this;
  }

  public void write() throws IOException {
    Set<String> runtimes = new HashSet<>();
    StringBuilder compilationTools = new StringBuilder();
    for (String compilationTool : CROSSTOOL_BINARIES) {
      Collection<String> archTargets = new ArrayList<>();
      for (String arch : archs) {
        archTargets.add(compilationTool + '-' + arch);
      }

      compilationTools.append(
          String.format(
              "filegroup(name = '%s', srcs = ['%s'])\n",
              compilationTool,
              Joiner.on("', '").join(archTargets)));
      for (String archTarget : archTargets) {
        compilationTools.append(
            String.format("filegroup(name = '%s', srcs = [':everything-multilib'])\n", archTarget));
      }
    }

    for (CcToolchainConfig toolchain : ccToolchainConfigList) {
      String staticRuntimeLabel =
          toolchain.hasStaticLinkCppRuntimesFeature()
              ? "mock-static-runtimes-target-for-" + toolchain.getToolchainIdentifier()
              : null;
      String dynamicRuntimeLabel =
          toolchain.hasStaticLinkCppRuntimesFeature()
              ? "mock-dynamic-runtimes-target-for-" + toolchain.getToolchainIdentifier()
              : null;
      if (staticRuntimeLabel != null) {
        runtimes.add(
            Joiner.on('\n')
                .join(
                    "filegroup(",
                    "  name = '" + staticRuntimeLabel + "',",
                    "  licenses = ['unencumbered'],",
                    "  srcs = ['libstatic-runtime-lib-source.a'])",
                    ""));
      }
      if (dynamicRuntimeLabel != null) {
        runtimes.add(
            Joiner.on('\n')
                .join(
                    "filegroup(",
                    "  name = '" + dynamicRuntimeLabel + "',",
                    "  licenses = ['unencumbered'],",
                    "  srcs = ['libdynamic-runtime-lib-source.so'])",
                    ""));
      }

      // Generate cc_toolchain target
      String suffix = toolchain.getTargetCpu() + "-" + toolchain.getCompiler();
      compilationTools.append(
          Joiner.on("\n")
              .join(
                  "toolchain(",
                  "  name = 'cc-toolchain-" + suffix + "',",
                  "  toolchain_type = '"
                      + TestConstants.TOOLS_REPOSITORY
                      + "//tools/cpp:toolchain_type',",
                  "  toolchain = ':cc-compiler-" + suffix + "',",
                  toolchain.getToolchainExecConstraints(),
                  toolchain.getToolchainTargetConstraints(),
                  ")",
                  toolchain.getCcToolchainConfigRule(),
                  "cc_toolchain(",
                  "  name = 'cc-compiler-" + suffix + "',",
                  "  toolchain_identifier = '" + toolchain.getToolchainIdentifier() + "',",
                  "  toolchain_config = ':" + suffix + "_config',",
                  "  output_licenses = ['unencumbered'],",
                  "  module_map = 'crosstool.cppmap',",
                  "  ar_files = 'ar-" + toolchain.getTargetCpu() + "',",
                  "  as_files = 'as-" + toolchain.getTargetCpu() + "',",
                  "  compiler_files = 'compile-" + toolchain.getTargetCpu() + "',",
                  "  coverage_files = 'coverage-file',",
                  "  dwp_files = 'dwp-" + toolchain.getTargetCpu() + "',",
                  "  linker_files = 'link-" + toolchain.getTargetCpu() + "',",
                  "  strip_files = ':every-file',",
                  "  objcopy_files = 'objcopy-" + toolchain.getTargetCpu() + "',",
                  "  all_files = ':every-file',",
                  "  licenses = ['unencumbered'],",
                  supportsHeaderParsing ? "    supports_header_parsing = 1," : "",
                  dynamicRuntimeLabel == null
                      ? ""
                      : "    dynamic_runtime_lib = '" + dynamicRuntimeLabel + "',",
                  staticRuntimeLabel == null
                      ? ""
                      : "    static_runtime_lib = '" + staticRuntimeLabel + "',",
                  ")",
                  ""));
    }

    String build =
        Joiner.on("\n")
            .join(
                "package(default_visibility=['//visibility:public'])",
                "licenses(['restricted'])",
                "",
                "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
                "load('"
                    + TestConstants.TOOLS_REPOSITORY
                    + "//third_party/cc_rules/macros:defs.bzl', 'cc_library', 'cc_toolchain')",
                "toolchain_type(name = 'toolchain_type')",
                "toolchain_type(name = 'test_runner_toolchain_type')",
                "cc_toolchain_alias(name = 'current_cc_toolchain')",
                "cc_toolchain_alias(",
                "    name = 'optional_current_cc_toolchain',",
                "    mandatory = False,",
                ")",
                "alias(name = 'toolchain', actual = 'everything')",
                "filegroup(name = 'everything-multilib',",
                "          srcs = glob(['mock_version/**/*'],",
                "              exclude_directories = 1),",
                "          output_licenses = ['unencumbered'])",
                "",
                String.format(
                    "filegroup(name = 'every-file', srcs = ['%s'])",
                    Joiner.on("', '").join(CROSSTOOL_BINARIES)),
                "",
                compilationTools.toString(),
                Joiner.on("\n").join(runtimes),
                "",
                "filegroup(",
                "    name = 'interface_library_builder',",
                "    srcs = ['build_interface_so'],",
                ")",
                // We add a :link_extra_lib target in case we need it.
                "cc_library(name = 'link_extra_lib', srcs = ['linkextra.cc'])",
                // We add an empty :malloc target in case we need it.
                "cc_library(name = 'malloc')",
                // Fake targets to get us through loading/analysis.
                "exports_files(['grep-includes', 'link_dynamic_library'])",
                "",
                "filegroup(",
                "    name = 'aggregate-ddi',",
                "    srcs = ['aggregate-ddi.sh'],",
                ")",
                "",
                "filegroup(",
                "    name = 'generate-modmap',",
                "    srcs = ['generate-modmap.sh'],",
                ")");

    config.create(crosstoolTop + "/mock_version/x86/bin/gcc");
    config.create(crosstoolTop + "/mock_version/x86/bin/ld");
    config.overwrite(crosstoolTop + "/BUILD", build);
    config.overwrite(crosstoolTop + "/cc_toolchain_config.bzl", ccToolchainConfigFileContents);
    config.create(crosstoolTop + "/crosstool.cppmap", "module crosstool {}");
    config.append(
        "MODULE.bazel",
        String.format(
            "register_toolchains('%s:all')",
            crosstoolTopLabel.getPackageIdentifier().getCanonicalForm()));
    // Empty files to satisfy fake targets.
    config.create(crosstoolTop + "/grep-includes");
    config.create(crosstoolTop + "/build_interface_so");
    config.create(crosstoolTop + "/link_dynamic_library");
    config.create(crosstoolTop + "/aggregate-ddi.sh");
    config.create(crosstoolTop + "/generate-modmap.sh");
  }

  public void writeOSX() throws IOException {
    // Create special lines specifying the compiler map entry for
    // each toolchain.

    // Create the test BUILD file.
    ImmutableList.Builder<String> crosstoolBuild =
        ImmutableList.<String>builder()
            .add(
                "package(default_visibility=['//visibility:public'])",
                "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
                "load('"
                    + TestConstants.TOOLS_REPOSITORY
                    + "//third_party/cc_rules/macros:defs.bzl', 'cc_library')",
                "exports_files(glob(['**']))",
                "",
                "cc_library(",
                "    name = 'custom_malloc',",
                ")",
                "",
                "filegroup(",
                "    name = 'empty',",
                "    srcs = [],",
                ")",
                "",
                "filegroup(",
                "    name = 'link',",
                "    srcs = [",
                "        'ar',",
                "        'libempty.a',",
                String.format("        '%s//tools/objc:libtool'", TestConstants.TOOLS_REPOSITORY),
                "    ],",
                ")");
    for (CcToolchainConfig toolchainConfig : ccToolchainConfigList) {
      String staticRuntimeLabel =
          toolchainConfig.hasStaticLinkCppRuntimesFeature()
              ? "mock-static-runtimes-target-for-" + toolchainConfig.getToolchainIdentifier()
              : null;
      String dynamicRuntimeLabel =
          toolchainConfig.hasStaticLinkCppRuntimesFeature()
              ? "mock-dynamic-runtimes-target-for-" + toolchainConfig.getToolchainIdentifier()
              : null;
      if (staticRuntimeLabel != null) {
        crosstoolBuild.add(
            Joiner.on('\n')
                .join(
                    "filegroup(",
                    "  name = '" + staticRuntimeLabel + "',",
                    "  licenses = ['unencumbered'],",
                    "  srcs = ['libstatic-runtime-lib-source.a'])",
                    ""));
      }
      if (dynamicRuntimeLabel != null) {
        crosstoolBuild.add(
            Joiner.on('\n')
                .join(
                    "filegroup(",
                    "  name = '" + dynamicRuntimeLabel + "',",
                    "  licenses = ['unencumbered'],",
                    "  srcs = ['libdynamic-runtime-lib-source.so'])",
                    ""));
      }

      crosstoolBuild.add(
          "cc_toolchain(",
          "    name = 'cc-compiler-" + toolchainConfig.getTargetCpu() + "',",
          "    toolchain_identifier = '" + toolchainConfig.getTargetCpu() + "',",
          "    toolchain_config = ':"
              + toolchainConfig.getTargetCpu()
              + "-"
              + toolchainConfig.getCompiler()
              + "_config',",
          "    all_files = ':empty',",
          "    ar_files = ':link',",
          "    as_files = ':empty',",
          "    compiler_files = ':empty',",
          "    coverage_files = 'coverage-file',",
          "    dwp_files = ':empty',",
          "    linker_files = ':link',",
          "    objcopy_files = ':empty',",
          "    strip_files = ':empty',",
          "    supports_param_files = 0,",
          supportsHeaderParsing ? "    supports_header_parsing = 1," : "",
          dynamicRuntimeLabel == null
              ? ""
              : "    dynamic_runtime_lib = '" + dynamicRuntimeLabel + "',",
          staticRuntimeLabel == null
              ? ""
              : "    static_runtime_lib = '" + staticRuntimeLabel + "',",
          ")",
          "toolchain(name = 'cc-toolchain-" + toolchainConfig.getTargetCpu() + "',",
          toolchainConfig.getToolchainExecConstraints(),
          toolchainConfig.getToolchainTargetConstraints(),
          "    toolchain = ':cc-compiler-" + toolchainConfig.getTargetCpu() + "',",
          "    toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type'",
          ")");
      crosstoolBuild.add(toolchainConfig.getCcToolchainConfigRule());
    }

    config.overwrite(
        MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR + "/BUILD",
        Joiner.on("\n").join(crosstoolBuild.build()));
    config.append(
        "MODULE.bazel",
        "register_toolchains('//" + MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR + ":all')");
    config.overwrite(crosstoolTop + "/cc_toolchain_config.bzl", ccToolchainConfigFileContents);
  }
}
