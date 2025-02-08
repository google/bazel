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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.PackageValidator;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingFunction;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.SkyframeExecutorTestHelper;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DelegatingSyscallCache;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParser;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;

/**
 * This is a specialization of {@link FoundationTestCase} that's useful for implementing tests of
 * the "packages" library.
 */
public abstract class PackageLoadingTestCase extends FoundationTestCase {

  private static final int GLOBBING_THREADS = 7;

  protected LoadingMock loadingMock;
  private PackageOptions packageOptions;
  private BuildLanguageOptions buildLanguageOptions;
  protected ConfiguredRuleClassProvider ruleClassProvider;
  protected PackageFactory packageFactory;
  protected SkyframeExecutor skyframeExecutor;
  protected BlazeDirectories directories;
  protected PackageValidator validator = null;
  protected final DelegatingSyscallCache delegatingSyscallCache = new DelegatingSyscallCache();

  protected final ActionKeyContext actionKeyContext = new ActionKeyContext();

  @Before
  public final void initializeSkyframeExecutor() throws Exception {
    loadingMock = LoadingMock.get();
    packageOptions = parsePackageOptions();
    buildLanguageOptions = parseBuildLanguageOptions();
    List<RuleDefinition> extraRules = getExtraRules();
    if (!extraRules.isEmpty()) {
      ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
      TestRuleClassProvider.addStandardRules(builder);
      for (RuleDefinition def : extraRules) {
        builder.addRuleDefinition(def);
      }
      ruleClassProvider = builder.build();
    } else {
      ruleClassProvider = loadingMock.createRuleClassProvider();
    }
    directories =
        new BlazeDirectories(
            new ServerDirectories(outputBase, outputBase, outputBase),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            loadingMock.getProductName());
    packageFactory =
        loadingMock
            .getPackageFactoryBuilderForTesting(directories)
            .setExtraSkyFunctions(
                ImmutableMap.of(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(
                        ruleClassProvider.getBazelStarlarkEnvironment(),
                        directories.getWorkspace(),
                        ImmutableMap.of())))
            .setPackageValidator(
                new PackageValidator() {
                  @Override
                  public void validate(Package pkg, ExtendedEventHandler eventHandler)
                      throws InvalidPackageException {
                    if (validator != null) {
                      validator.validate(pkg, eventHandler);
                    }
                  }
                })
            .build(ruleClassProvider, fileSystem);
    delegatingSyscallCache.setDelegate(SyscallCache.NO_CACHE);
    skyframeExecutor = createSkyframeExecutor();
    setUpSkyframe();
  }

  @Before
  public void initializeMockTestingRules() throws IOException {
    MockToolsConfig mockToolsConfig = new MockToolsConfig(rootDirectory);
    mockToolsConfig.create("test_defs/BUILD");
    mockToolsConfig.create(
        "test_defs/foo_library.bzl",
        """
        def _impl(ctx):
          pass
        foo_library = rule(
          implementation = _impl,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
          },
        )
        """);
    mockToolsConfig.create(
        "test_defs/foo_binary.bzl",
        """
        def _impl(ctx):
          symlink = ctx.actions.declare_file(ctx.label.name)
          ctx.actions.symlink(output = symlink, target_file = ctx.files.srcs[0],
            is_executable = True)
          files = depset(ctx.files.srcs)
          return [DefaultInfo(files = files, executable = symlink,
             runfiles = ctx.runfiles(transitive_files = files, collect_default = True))]
        foo_binary = rule(
          implementation = _impl,
          executable = True,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
            "data": attr.label_list(allow_files=True),
          },
        )
        """);
    mockToolsConfig.create(
        "test_defs/foo_test.bzl",
        """
        def _impl(ctx):
          symlink = ctx.actions.declare_file(ctx.label.name)
          ctx.actions.symlink(output = symlink, target_file = ctx.files.srcs[0],
            is_executable = True)
          files = depset(ctx.files.srcs)
          return [DefaultInfo(files = files, executable = symlink,
             runfiles = ctx.runfiles(transitive_files = files, collect_default = True))]
        foo_test = rule(
          implementation = _impl,
          test = True,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
            "data": attr.label_list(allow_files=True),
          },
        )
        """);
  }

  @After
  public final void cleanUpInterningPools() {
    skyframeExecutor.getEvaluator().cleanupInterningPools();
  }

  /** Allows subclasses to augment the {@link RuleDefinition}s available in this test. */
  protected List<RuleDefinition> getExtraRules() {
    return ImmutableList.of();
  }

  private SkyframeExecutor createSkyframeExecutor() {
    SkyframeExecutor skyframeExecutor =
        BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
            .setPkgFactory(packageFactory)
            .setFileSystem(fileSystem)
            .setDirectories(directories)
            .setActionKeyContext(actionKeyContext)
            .setSyscallCache(delegatingSyscallCache)
            .build();
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE, Optional.empty())));
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                RepositoryDelegatorFunction.VENDOR_DIRECTORY, Optional.empty())));
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                RepositoryMappingFunction.REPOSITORY_OVERRIDES, ImmutableMap.of())));
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                ModuleFileFunction.INJECTED_REPOSITORIES, ImmutableMap.of())));
    SkyframeExecutorTestHelper.process(skyframeExecutor);
    return skyframeExecutor;
  }

  protected void setUpSkyframe(RuleVisibility defaultVisibility) {
    PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);
    packageOptions.defaultVisibility = defaultVisibility;
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = GLOBBING_THREADS;
    skyframeExecutor.preparePackageLoading(
        new PathPackageLocator(
            outputBase,
            ImmutableList.of(Root.fromPath(rootDirectory)),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY),
        packageOptions,
        buildLanguageOptions,
        UUID.randomUUID(),
        ImmutableMap.of(),
        QuiescingExecutorsImpl.forTesting(),
        new TimestampGranularityMonitor(BlazeClock.instance()));
    skyframeExecutor.setActionEnv(ImmutableMap.of());
  }

  private void setUpSkyframe() {
    PathPackageLocator pkgLocator =
        PathPackageLocator.create(
            outputBase,
            packageOptions.packagePath,
            reporter,
            rootDirectory.asFragment(),
            rootDirectory,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = GLOBBING_THREADS;
    skyframeExecutor.preparePackageLoading(
        pkgLocator,
        packageOptions,
        buildLanguageOptions,
        UUID.randomUUID(),
        ImmutableMap.of(),
        QuiescingExecutorsImpl.forTesting(),
        new TimestampGranularityMonitor(BlazeClock.instance()));
    skyframeExecutor.setActionEnv(ImmutableMap.of());
    skyframeExecutor.setDeletedPackages(ImmutableSet.copyOf(packageOptions.getDeletedPackages()));
  }

  private static PackageOptions parsePackageOptions(String... options) throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(PackageOptions.class).build();
    parser.parse("--default_visibility=public");
    parser.parse(options);
    return parser.getOptions(PackageOptions.class);
  }

  private static BuildLanguageOptions parseBuildLanguageOptions(String... options)
      throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(BuildLanguageOptions.class).build();
    parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS);
    parser.parse(options);
    return parser.getOptions(BuildLanguageOptions.class);
  }

  protected void setPackageOptions(String... options) throws Exception {
    packageOptions = parsePackageOptions(options);
    setUpSkyframe();
  }

  protected void setBuildLanguageOptions(String... options) throws Exception {
    buildLanguageOptions = parseBuildLanguageOptions(options);
    setUpSkyframe();
  }

  protected Target getTarget(String label)
      throws NoSuchPackageException,
          NoSuchTargetException,
          LabelSyntaxException,
          InterruptedException {
    return getTarget(Label.parseCanonical(label));
  }

  protected Target getTarget(Label label)
      throws NoSuchPackageException, NoSuchTargetException, InterruptedException {
    return getPackageManager().getTarget(reporter, label);
  }

  /**
   * Create and return a scratch rule.
   *
   * @param packageName the package name of the rule.
   * @param ruleName the name of the rule.
   * @param lines the text of the rule.
   * @return the rule instance for the created rule.
   */
  protected Rule scratchRule(String packageName, String ruleName, String... lines)
      throws Exception {
    scratch.file(packageName + "/BUILD", lines);
    return (Rule) getTarget("//" + packageName + ":" + ruleName);
  }

  /**
   * A Utility method that generates build file rules for tests.
   *
   * @param rule the name of the rule class.
   * @param name the name of the rule instance.
   * @param body an array of strings containing the contents of the rule.
   * @return a string containing the build file rule.
   */
  protected static String genRule(String rule, String name, String... body) {
    StringBuilder buf = new StringBuilder();
    buf.append(rule);
    buf.append("(name='");
    buf.append(name);
    buf.append("',\n");
    for (String line : body) {
      buf.append(line);
    }
    buf.append(")\n");
    return buf.toString();
  }

  /**
   * A utility function which generates the "deps" clause for a build file rule from a list of
   * targets.
   *
   * @param depTargets the list of targets.
   * @return a string containing the deps clause
   */
  protected static String deps(String... depTargets) {
    StringBuilder buf = new StringBuilder();
    buf.append("    deps=[");
    String sep = "'";
    for (String dep : depTargets) {
      buf.append(sep);
      buf.append(dep);
      buf.append("'");
      sep = ", '";
    }
    buf.append("]");
    return buf.toString();
  }

  /**
   * Utility method for tests. Converts an array of strings into a set of labels.
   *
   * @param strings the set of strings to be converted to labels.
   * @throws LabelSyntaxException if there are any syntax errors in the strings.
   */
  public static Set<Label> asLabelSet(String... strings) throws LabelSyntaxException {
    return asLabelSet(ImmutableList.copyOf(strings));
  }

  /**
   * Utility method for tests. Converts an array of strings into a set of labels.
   *
   * @param strings the set of strings to be converted to labels.
   * @throws LabelSyntaxException if there are any syntax errors in the strings.
   */
  public static Set<Label> asLabelSet(Iterable<String> strings) throws LabelSyntaxException {
    Set<Label> result = Sets.newTreeSet();
    for (String s : strings) {
      result.add(Label.parseCanonical(s));
    }
    return result;
  }

  protected PackageManager getPackageManager() {
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE, Optional.empty())));
    return skyframeExecutor.getPackageManager();
  }

  protected SkyframeExecutor getSkyframeExecutor() {
    return skyframeExecutor;
  }

  /**
   * Called after files are modified to invalidate all file-system nodes below rootDirectory. It
   * does not unconditionally invalidate PackageValue nodes; if no file-system nodes have changed,
   * packages may not be reloaded.
   */
  protected void invalidatePackages() throws InterruptedException, AbruptExitException {
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));
  }
}
