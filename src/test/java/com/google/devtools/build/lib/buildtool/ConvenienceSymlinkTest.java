// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.buildtool;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.STRING;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.actions.FileWriteActionContext;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.buildtool.util.TestRuleModule;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.exec.EagerFileWriteStrategy;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ConvenienceSymlinkTest extends BuildIntegrationTestCase {

  /** test options to cause the output directory to change */
  public static final class PathTestOptions extends FragmentOptions {
    @Option(
        name = "output_directory_name",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
        defaultValue = "default")
    public String outputDirectoryName;

    @Option(
        name = "useless_option",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "default")
    public String uselessOption;
  }

  /** Test fragment. */
  @RequiresOptions(options = {PathTestOptions.class})
  public static final class PathTestConfiguration extends Fragment {
    private final String outputDirectoryName;

    public PathTestConfiguration(BuildOptions buildOptions) {
      this.outputDirectoryName = buildOptions.get(PathTestOptions.class).outputDirectoryName;
    }

    @Override
    public void processForOutputPathMnemonic(OutputDirectoriesContext ctx)
        throws Fragment.OutputDirectoriesContext.AddToMnemonicException {
      ctx.markAsExplicitInOutputPathFor("output_directory_name");
      ctx.addToMnemonic(outputDirectoryName);
    }
  }

  private static final class PathAttributeTransitionFactory
      implements TransitionFactory<AttributeTransitionData> {
    private final String newPath;

    PathAttributeTransitionFactory(String newPath) {
      this.newPath = newPath;
    }

    @Override
    public ConfigurationTransition create(AttributeTransitionData data) {
      return new PatchTransition() {
        @Override
        public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
          return ImmutableSet.of(PathTestOptions.class);
        }

        @Override
        public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
          BuildOptionsView clone = options.clone();
          clone.get(PathTestOptions.class).outputDirectoryName = newPath;
          return clone.underlying();
        }
      };
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.ATTRIBUTE;
    }
  }

  private static final class PathRuleTransitionFactory
      implements TransitionFactory<RuleTransitionData> {
    @Override
    public PatchTransition create(RuleTransitionData ruleData) {
      String newPath = NonconfigurableAttributeMapper.of(ruleData.rule()).get("path", STRING);
      return new PatchTransition() {
        @Override
        public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
          return ImmutableSet.of(PathTestOptions.class);
        }

        @Override
        public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
          BuildOptionsView clone = options.clone();
          clone.get(PathTestOptions.class).outputDirectoryName = newPath;
          return clone.underlying();
        }
      };
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.RULE;
    }
  }

  private static final class UselessOptionTransition implements PatchTransition {
    private final String newValue;

    UselessOptionTransition(String newValue) {
      this.newValue = newValue;
    }

    @Override
    public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
      return ImmutableSet.of(PathTestOptions.class);
    }

    @Override
    public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
      BuildOptionsView clone = options.clone();
      clone.get(PathTestOptions.class).uselessOption = newValue;
      return clone.underlying();
    }
  }

  private static final class UselessOptionTransitionFactory
      implements TransitionFactory<RuleTransitionData> {
    @Override
    public PatchTransition create(RuleTransitionData ruleData) {
      return new UselessOptionTransition(
          NonconfigurableAttributeMapper.of(ruleData.rule()).get("value", STRING));
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.RULE;
    }
  }

  private static final class PathTestRulesModule extends BlazeModule {
    @Override
    public void registerActionContexts(
        ModuleActionContextRegistry.Builder registryBuilder,
        CommandEnvironment env,
        BuildRequest buildRequest) {
      // we need an implementation of FileWriteActionContext to get our file writes to succeed
      registryBuilder.register(FileWriteActionContext.class, new EagerFileWriteStrategy());
      // we need something to consume FileWriteActionContext or the registration will have no effect
      registryBuilder.restrictTo(FileWriteActionContext.class, "local");
    }

    @Override
    public void initializeRuleClasses(ConfiguredRuleClassProvider.Builder builder) {
      TestRuleModule.getModule().initializeRuleClasses(builder);

      MockRule basicRule =
          () ->
              MockRule.define(
                  "basic_rule",
                  (ruleBuilder, env) ->
                      ruleBuilder
                          .add(attr("deps", LABEL_LIST).allowedFileTypes())
                          .setImplicitOutputsFunction(
                              ImplicitOutputsFunction.fromTemplates("%{name}.bin")));
      MockRule incomingTransitionRule =
          () ->
              MockRule.define(
                  "incoming_transition_rule",
                  (ruleBuilder, env) ->
                      ruleBuilder
                          .add(
                              attr("path", STRING)
                                  .mandatory()
                                  .nonconfigurable("used in transition"))
                          .add(attr("deps", LABEL_LIST).allowedFileTypes())
                          .setImplicitOutputsFunction(
                              ImplicitOutputsFunction.fromTemplates("%{name}.bin"))
                          .cfg(new PathRuleTransitionFactory()));
      MockRule incomingUnrelatedTransitionRule =
          () ->
              MockRule.define(
                  "incoming_unrelated_transition_rule",
                  (ruleBuilder, env) ->
                      ruleBuilder
                          .add(
                              attr("value", STRING)
                                  .mandatory()
                                  .nonconfigurable("used in transition"))
                          .add(attr("deps", LABEL_LIST).allowedFileTypes())
                          .setImplicitOutputsFunction(
                              ImplicitOutputsFunction.fromTemplates("%{name}.bin"))
                          .cfg(new UselessOptionTransitionFactory()));

      MockRule outgoingTransitionRule =
          () ->
              MockRule.define(
                  "outgoing_transition_rule",
                  (ruleBuilder, env) ->
                      ruleBuilder
                          .add(
                              attr("deps", LABEL_LIST)
                                  .allowedFileTypes()
                                  .cfg(
                                      new PathAttributeTransitionFactory(
                                          "set_by_outgoing_transition_rule")))
                          .setImplicitOutputsFunction(
                              ImplicitOutputsFunction.fromTemplates("%{name}.bin")));

      builder
          .addConfigurationFragment(PathTestConfiguration.class)
          .addRuleDefinition(basicRule)
          .addRuleDefinition(incomingTransitionRule)
          .addRuleDefinition(incomingUnrelatedTransitionRule)
          .addRuleDefinition(outgoingTransitionRule);
    }
  }

  @TestParameter boolean mergedAnalysisExecution;

  @Override
  protected void setupOptions() throws Exception {
    super.setupOptions();

    addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution);
  }

  @Override
  protected BlazeModule getRulesModule() {
    return new PathTestRulesModule();
  }

  private Path getExecRoot() {
    return getBlazeWorkspace().getDirectories().getExecRoot(TestConstants.WORKSPACE_NAME);
  }

  private Path getOutputPath() {
    return getBlazeWorkspace().getDirectories().getOutputPath(TestConstants.WORKSPACE_NAME);
  }

  /** Gets a mapping from the workspace-relative paths of symlinks to the paths they point to. */
  private ImmutableMap<String, Path> getConvenienceSymlinks() throws IOException {
    return getWorkspace().getDirectoryEntries().stream()
        .filter(Path::isSymbolicLink)
        .collect(
            toImmutableMap(
                (path) -> path.relativeTo(getWorkspace()).toString(),
                (path) -> {
                  try {
                    return getWorkspace().getRelative(path.readSymbolicLinkUnchecked());
                  } catch (IOException ex) {
                    throw new RuntimeException(ex);
                  }
                }));
  }

  @Test
  public void sanityCheckFilesHaveNullConfigurations() throws Exception {
    // Other tests in this file expect that files will have a null configuration.
    write("files/BUILD", "exports_files(['foo.txt', 'bar.txt'])");
    write("files/foo.txt", "This is just a test file to pretend to build.");
    write("files/bar.txt", "This is just a test file to pretend to build.");
    BuildResult result = buildTarget("//files:foo.txt", "//files:bar.txt");

    assertThat(
            result.getActualTargets().stream()
                .collect(
                    toImmutableMap(
                        target -> target.getLabel().toString(),
                        target -> Optional.ofNullable(target.getConfigurationKey()))))
        .containsExactly(
            "//files:foo.txt", Optional.empty(),
            "//files:bar.txt", Optional.empty());
  }

  @Test
  public void sanityCheckOutputDirectory() throws Exception {
    // Other tests in this file expect that changing the output_directory_name flag changes the
    // output directory of the configuration to the same value.

    // This test relies on hard-coded paths for intermediate artifacts so
    // must force output directory naming into legacy behaviors for now.
    addOptions(
        "--output_directory_name=set_by_flag",
        "--compilation_mode=fastbuild",
        "--experimental_exec_configuration_distinguisher=legacy");

    write(
        "path/BUILD",
        """
        basic_rule(name = "from_flag")

        incoming_transition_rule(
            name = "from_transition",
            path = "set_by_transition",
        )

        incoming_unrelated_transition_rule(
            name = "unrelated_transition",
            value = "whatever",
        )

        outgoing_transition_rule(name = "outgoing_transition")
        """);
    BuildResult result =
        buildTarget(
            "//path:from_flag",
            "//path:from_transition",
            "//path:unrelated_transition",
            "//path:outgoing_transition");

    assertThat(
            result.getActualTargets().stream()
                .collect(
                    toImmutableMap(
                        (target) -> target.getLabel().toString(),
                        (target) ->
                            getConfigurationFromLastBuildResult(target)
                                .getOutputDirectory(RepositoryName.MAIN)
                                .getRoot()
                                .asPath()
                                .relativeTo(getOutputPath())
                                .toString())))
        .containsExactly(
            "//path:from_flag", getTargetConfiguration().getCpu() + "-fastbuild-set_by_flag",
            "//path:from_transition",
                getTargetConfiguration().getCpu() + "-fastbuild-set_by_transition",
            "//path:unrelated_transition",
                getTargetConfiguration().getCpu() + "-fastbuild-set_by_flag-ST-040655c91309",
            "//path:outgoing_transition",
                getTargetConfiguration().getCpu() + "-fastbuild-set_by_flag");
  }

  @Test
  public void buildingNothing_unsetsSymlinks() throws Exception {
    addOptions("--symlink_prefix=nothing-", "--incompatible_skip_genfiles_symlink=false");

    Path config = getOutputBase().getRelative("some-imaginary-config");
    // put symlinks at the convenience symlinks spots to simulate a prior build
    Path binLink = getWorkspace().getChild("nothing-bin");
    binLink.createSymbolicLink(config.getChild("bin"));
    Path genfilesLink = getWorkspace().getChild("nothing-genfiles");
    genfilesLink.createSymbolicLink(config.getChild("genfiles"));
    Path testlogsLink = getWorkspace().getChild("nothing-testlogs");
    testlogsLink.createSymbolicLink(config.getChild("testlogs"));

    buildTarget();

    // there should be nothing at any of the convenience symlinks which depend on configuration -
    // the symlinks put there during the simulated prior build should have been deleted
    assertThat(binLink.exists(Symlinks.NOFOLLOW)).isTrue();
    assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isTrue();
    assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isTrue();

    // the execroot and output path symlinks should have been created because they don't depend on
    // configuration, but no other symlinks should have been created
    assertThat(getConvenienceSymlinks())
        .containsExactly(
            // notably absent: nothing-bin, nothing-genfiles, nothing-testlogs
            // these were also not created under other names
            "nothing-bin",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/bin"),
            "nothing-genfiles",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/bin"),
            "nothing-testlogs",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/testlogs"),
            "nothing-" + TestConstants.WORKSPACE_NAME,
            getExecRoot(),
            "nothing-out",
            getOutputPath());
  }

  @Test
  public void buildingOnlyTargetsWithNullConfigurations_unsetsSymlinks() throws Exception {
    addOptions("--symlink_prefix=nulled-", "--incompatible_skip_genfiles_symlink=false");

    Path config = getOutputBase().getRelative("some-imaginary-config");
    // put symlinks at the convenience symlinks spots to simulate a prior build
    Path binLink = getWorkspace().getChild("nulled-bin");
    binLink.createSymbolicLink(config.getChild("bin"));
    Path genfilesLink = getWorkspace().getChild("nulled-genfiles");
    genfilesLink.createSymbolicLink(config.getChild("genfiles"));
    Path testlogsLink = getWorkspace().getChild("nulled-testlogs");
    testlogsLink.createSymbolicLink(config.getChild("testlogs"));

    write("files/BUILD", "exports_files(['foo.txt', 'bar.txt'])");
    write("files/foo.txt", "This is just a test file to pretend to build.");
    write("files/bar.txt", "This is just a test file to pretend to build.");
    buildTarget("//files:foo.txt", "//files:bar.txt");

    // there should be nothing at any of the convenience symlinks which depend on configuration -
    // the symlinks put there during the simulated prior build should have been deleted
    assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse();

    // the execroot and output path symlinks should have been created because they don't depend on
    // configuration, but no other symlinks should have been created
    assertThat(getConvenienceSymlinks())
        .containsExactly(
            // notably absent: nulled-bin, nulled-genfiles, nulled-testlogs
            // these were also not created under other names
            "nulled-" + TestConstants.WORKSPACE_NAME, getExecRoot(), "nulled-out", getOutputPath());
  }

  @Test
  public void buildingTargetsWithDifferentOutputDirectories_unsetsSymlinksIfNoneAreTopLevel()
      throws Exception {
    addOptions("--symlink_prefix=ambiguous-", "--incompatible_skip_genfiles_symlink=false");

    Path config = getOutputPath().getRelative("some-imaginary-config");
    // put symlinks at the convenience symlinks spots to simulate a prior build
    Path binLink = getWorkspace().getChild("ambiguous-bin");
    binLink.createSymbolicLink(config.getChild("bin"));
    Path genfilesLink = getWorkspace().getChild("ambiguous-genfiles");
    genfilesLink.createSymbolicLink(config.getChild("genfiles"));
    Path testlogsLink = getWorkspace().getChild("ambiguous-testlogs");
    testlogsLink.createSymbolicLink(config.getChild("testlogs"));

    write(
        "targets/BUILD",
        """
        incoming_transition_rule(
            name = "config1",
            path = "set_from_config1",
        )

        incoming_transition_rule(
            name = "config2",
            path = "set_from_config2",
        )
        """);
    buildTarget("//targets:config1", "//targets:config2");

    // there should be nothing at any of the convenience symlinks which depend on configuration -
    // the symlinks put there during the simulated prior build should have been deleted
    assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse();

    // the execroot and output path symlinks should have been created because they don't depend on
    // configuration, but no other symlinks should have been created
    assertThat(getConvenienceSymlinks())
        .containsExactly(
            // notably absent: ambiguous-bin, ambiguous-genfiles, ambiguous-testlogs
            // these were also not created under other names
            "ambiguous-" + TestConstants.WORKSPACE_NAME,
            getExecRoot(),
            "ambiguous-out",
            getOutputPath());
  }

  @Test
  public void buildingTargetsWithDifferentOutputDirectories_setsSymlinksIfAnyAreTopLevel()
      throws Exception {
    addOptions(
        "--symlink_prefix=ambiguous-",
        "--incompatible_skip_genfiles_symlink=false",
        "--incompatible_merge_genfiles_directory=false",
        "--incompatible_skip_genfiles_symlink=false");

    Path config = getOutputPath().getRelative("some-imaginary-config");
    // put symlinks at the convenience symlinks spots to simulate a prior build
    Path binLink = getWorkspace().getChild("ambiguous-bin");
    binLink.createSymbolicLink(config.getChild("bin"));
    Path genfilesLink = getWorkspace().getChild("ambiguous-genfiles");
    genfilesLink.createSymbolicLink(config.getChild("genfiles"));
    Path testlogsLink = getWorkspace().getChild("ambiguous-testlogs");
    testlogsLink.createSymbolicLink(config.getChild("testlogs"));

    write(
        "targets/BUILD",
        """
        basic_rule(name = "default")

        incoming_transition_rule(
            name = "config1",
            path = "set_from_config1",
        )
        """);
    buildTarget("//targets:default", "//targets:config1");

    assertThat(getConvenienceSymlinks())
        .containsExactly(
            "ambiguous-bin",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/bin"),
            "ambiguous-genfiles",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/genfiles"),
            "ambiguous-testlogs",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/testlogs"),
            "ambiguous-" + TestConstants.WORKSPACE_NAME,
            getExecRoot(),
            "ambiguous-out",
            getOutputPath());
  }

  @Test
  public void buildingTargetsWithSameConfiguration_setsSymlinks() throws Exception {
    addOptions(
        "--symlink_prefix=same-",
        "--compilation_mode=fastbuild",
        "--incompatible_merge_genfiles_directory=false",
        "--incompatible_skip_genfiles_symlink=false");

    write(
        "targets/BUILD",
        """
        incoming_transition_rule(
            name = "configged1",
            path = "configured",
        )

        incoming_transition_rule(
            name = "configged2",
            path = "configured",
        )
        """);
    buildTarget("//targets:configged1", "//targets:configged2");

    assertThat(getConvenienceSymlinks())
        .containsExactly(
            "same-bin",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-configured/bin"),
            "same-genfiles",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-configured/genfiles"),
            "same-testlogs",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-configured/testlogs"),
            "same-" + TestConstants.WORKSPACE_NAME,
            getExecRoot(),
            "same-out",
            getOutputPath());
  }

  @Test
  public void buildingSameConfigurationTargetsWithDifferentConfigurationDeps_setsSymlinks()
      throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=united-",
        "--compilation_mode=fastbuild",
        "--incompatible_merge_genfiles_directory=false",
        "--incompatible_skip_genfiles_symlink=false");

    write(
        "targets/BUILD",
        """
        outgoing_transition_rule(
            name = "configged1",
            deps = [":alternate1"],
        )

        outgoing_transition_rule(
            name = "configged2",
            deps = [":alternate2"],
        )

        basic_rule(name = "alternate1")

        incoming_transition_rule(
            name = "alternate2",
            path = "alternate_transition",
        )
        """);
    buildTarget("//targets:configged1", "//targets:configged2");

    assertThat(getConvenienceSymlinks())
        .containsExactly(
            "united-bin",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/bin"),
            "united-genfiles",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/genfiles"),
            "united-testlogs",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/testlogs"),
            "united-" + TestConstants.WORKSPACE_NAME,
            getExecRoot(),
            "united-out",
            getOutputPath());
  }

  @Test
  public void differentConfigurationSameOutputDirectory_setsSymlinks() throws Exception {
    // TODO(blaze-configurability-team): Remove when `--experimental_output_directory_naming_scheme`
    //    is universally set to `diff_from_baseline`
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=unchanged-",
        "--compilation_mode=fastbuild",
        "--incompatible_merge_genfiles_directory=false",
        "--incompatible_skip_genfiles_symlink=false");

    write(
        "targets/BUILD",
        """
        basic_rule(name = "from_flag")

        incoming_unrelated_transition_rule(
            name = "configged1",
            value = "one_transition",
        )

        incoming_unrelated_transition_rule(
            name = "configged2",
            value = "alternate_transition",
        )
        """);
    buildTarget("//targets:from_flag", "//targets:configged1", "//targets:configged2");

    assertThat(getConvenienceSymlinks())
        .containsExactly(
            "unchanged-bin",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/bin"),
            "unchanged-genfiles",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/genfiles"),
            "unchanged-testlogs",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/testlogs"),
            "unchanged-" + TestConstants.WORKSPACE_NAME,
            getExecRoot(),
            "unchanged-out",
            getOutputPath());
  }

  @Test
  public void nullConfigurationWithOtherMatchingOutputDir_setsSymlinks() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=mixed-",
        "--compilation_mode=fastbuild",
        "--incompatible_merge_genfiles_directory=false",
        "--incompatible_skip_genfiles_symlink=false");

    write(
        "targets/BUILD",
        """
        exports_files(["null"])

        basic_rule(name = "configured1")

        basic_rule(name = "configured2")
        """);
    write("targets/null", "This is just a test file to pretend to build.");
    buildTarget("//targets:null", "//targets:configured1", "//targets:configured2");

    assertThat(getConvenienceSymlinks())
        .containsExactly(
            "mixed-bin",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/bin"),
            "mixed-genfiles",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/genfiles"),
            "mixed-testlogs",
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/testlogs"),
            "mixed-" + TestConstants.WORKSPACE_NAME,
            getExecRoot(),
            "mixed-out",
            getOutputPath());
  }

  @Test
  public void settingSymlinksReplacesSymlinksAlreadyPresent() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=replaced-",
        "--compilation_mode=fastbuild",
        "--incompatible_skip_genfiles_symlink=false");

    Path binLink = getWorkspace().getChild("replaced-bin");
    Path genfilesLink = getWorkspace().getChild("replaced-genfiles");
    Path testlogsLink = getWorkspace().getChild("replaced-testlogs");
    Path workspaceLink = getWorkspace().getChild("replaced-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("replaced-out");

    PathFragment original = getOutputPath().getRelative("original/destination").asFragment();
    binLink.createSymbolicLink(original);
    genfilesLink.createSymbolicLink(original);
    testlogsLink.createSymbolicLink(original);
    workspaceLink.createSymbolicLink(original);
    outLink.createSymbolicLink(original);

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    // Implicitly test for symlink-ness; readSymbolicLink would throw if they are not symlinks.
    assertThat(binLink.readSymbolicLink()).isNotEqualTo(original);
    assertThat(genfilesLink.readSymbolicLink()).isNotEqualTo(original);
    assertThat(testlogsLink.readSymbolicLink()).isNotEqualTo(original);
    assertThat(workspaceLink.readSymbolicLink()).isNotEqualTo(original);
    assertThat(outLink.readSymbolicLink()).isNotEqualTo(original);
  }

  @Test
  public void settingSymlinksCreatesSymlinksIfNotAlreadyPresent() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=created-",
        "--compilation_mode=fastbuild",
        "--incompatible_skip_genfiles_symlink=false");

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    assertThat(getWorkspace().getChild("created-bin").isSymbolicLink()).isTrue();
    assertThat(getWorkspace().getChild("created-genfiles").isSymbolicLink()).isTrue();
    assertThat(getWorkspace().getChild("created-testlogs").isSymbolicLink()).isTrue();
    assertThat(getWorkspace().getChild("created-" + TestConstants.WORKSPACE_NAME).isSymbolicLink())
        .isTrue();
    assertThat(getWorkspace().getChild("created-out").isSymbolicLink()).isTrue();
  }

  @Test
  public void genfilesLink_omittedWithIncompatibleFlag() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=prefix-",
        "--compilation_mode=fastbuild",
        "--incompatible_skip_genfiles_symlink=true");

    // Simulate leftover symlink from prior build.
    Path config = getOutputPath().getRelative("some-imaginary-config");
    Path genfilesLink = getWorkspace().getChild("prefix-genfiles");
    genfilesLink.createSymbolicLink(config.getChild("genfiles"));

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    assertThat(getWorkspace().getChild("prefix-genfiles").isSymbolicLink()).isFalse();
  }

  @Test
  public void genfilesLink_presentWithoutIncompatibleFlag() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=prefix-",
        "--compilation_mode=fastbuild",
        "--incompatible_skip_genfiles_symlink=false");

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    assertThat(getWorkspace().getChild("prefix-genfiles").isSymbolicLink()).isTrue();
  }

  @Test
  public void settingSymlinksDoesNotReplaceNormalFilesAlreadyPresent() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=blocked-",
        "--compilation_mode=fastbuild");

    Path binLink = getWorkspace().getChild("blocked-bin");
    Path genfilesLink = getWorkspace().getChild("blocked-genfiles");
    Path testlogsLink = getWorkspace().getChild("blocked-testlogs");
    Path workspaceLink = getWorkspace().getChild("blocked-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("blocked-out");

    FileSystemUtils.writeIsoLatin1(binLink, "this file is not a symlink");
    FileSystemUtils.writeIsoLatin1(genfilesLink, "this file is not a symlink");
    FileSystemUtils.writeIsoLatin1(testlogsLink, "this file is not a symlink");
    FileSystemUtils.writeIsoLatin1(workspaceLink, "this file is not a symlink");
    FileSystemUtils.writeIsoLatin1(outLink, "this file is not a symlink");

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    assertThat(binLink.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(genfilesLink.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(testlogsLink.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(workspaceLink.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(outLink.isFile(Symlinks.NOFOLLOW)).isTrue();
  }

  @Test
  public void settingSymlinksDoesNotReplaceDirectoriesAlreadyPresent() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=blocked-",
        "--compilation_mode=fastbuild");

    Path binLink = getWorkspace().getChild("blocked-bin");
    Path genfilesLink = getWorkspace().getChild("blocked-genfiles");
    Path testlogsLink = getWorkspace().getChild("blocked-testlogs");
    Path workspaceLink = getWorkspace().getChild("blocked-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("blocked-out");

    binLink.createDirectory();
    genfilesLink.createDirectory();
    testlogsLink.createDirectory();
    workspaceLink.createDirectory();
    outLink.createDirectory();

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    assertThat(binLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(genfilesLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(testlogsLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(workspaceLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(outLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
  }

  @Test
  public void settingSymlinksReplacesSymlinksEvenIfNotPointingInsideExecRoot() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=replaced-",
        "--compilation_mode=fastbuild",
        "--incompatible_merge_genfiles_directory=false",
        "--incompatible_skip_genfiles_symlink=false");

    Path binLink = getWorkspace().getChild("replaced-bin");
    Path genfilesLink = getWorkspace().getChild("replaced-genfiles");
    Path testlogsLink = getWorkspace().getChild("replaced-testlogs");
    Path workspaceLink = getWorkspace().getChild("replaced-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("replaced-out");

    Path original = getWorkspace().getRelative("/arbitrary/somewhere/else/in/the/filesystem");
    binLink.createSymbolicLink(original);
    genfilesLink.createSymbolicLink(original);
    testlogsLink.createSymbolicLink(original);
    workspaceLink.createSymbolicLink(original);
    outLink.createSymbolicLink(original);

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    // Implicitly test for symlink-ness; readSymbolicLink would throw if they are not symlinks.
    assertThat(binLink.readSymbolicLink())
        .isEqualTo(
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/bin")
                .asFragment());
    assertThat(genfilesLink.readSymbolicLink())
        .isEqualTo(
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/genfiles")
                .asFragment());
    assertThat(testlogsLink.readSymbolicLink())
        .isEqualTo(
            getOutputPath()
                .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/testlogs")
                .asFragment());
    assertThat(workspaceLink.readSymbolicLink()).isEqualTo(getExecRoot().asFragment());
    assertThat(outLink.readSymbolicLink()).isEqualTo(getOutputPath().asFragment());
  }

  @Test
  public void settingSymlinksCreatesDirectoriesIfNeeded() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=created/",
        "--compilation_mode=fastbuild",
        "--incompatible_skip_genfiles_symlink=false");

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    assertThat(getWorkspace().getChild("created").isDirectory()).isTrue();
    assertThat(getWorkspace().getRelative("created/bin").isSymbolicLink()).isTrue();
    assertThat(getWorkspace().getRelative("created/genfiles").isSymbolicLink()).isTrue();
    assertThat(getWorkspace().getRelative("created/testlogs").isSymbolicLink()).isTrue();
    assertThat(
            getWorkspace().getRelative("created/" + TestConstants.WORKSPACE_NAME).isSymbolicLink())
        .isTrue();
    assertThat(getWorkspace().getRelative("created/out").isSymbolicLink()).isTrue();
  }

  @Test
  public void settingSymlinksDoesNothingWhenParentExistsAndIsNotADirectory() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=blocked/",
        "--compilation_mode=fastbuild");

    Path parentDir = getWorkspace().getChild("blocked");
    FileSystemUtils.writeIsoLatin1(parentDir, "this file is not a directory");

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    assertThat(getWorkspace().getChild("blocked").isFile(Symlinks.NOFOLLOW)).isTrue();
  }

  @Test
  public void settingSymlinksUsesExistingOrPopulatedParentDirectoryAsNormal() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=cooperating/",
        "--compilation_mode=fastbuild",
        "--incompatible_skip_genfiles_symlink=false");
    write("target/BUILD", "basic_rule(name='target')");
    write("cooperating/original", "this file makes the directory come to life");
    buildTarget("//target:target");

    assertThat(getWorkspace().getChild("cooperating").isDirectory()).isTrue();
    assertThat(getWorkspace().getRelative("cooperating/bin").isSymbolicLink()).isTrue();
    assertThat(getWorkspace().getRelative("cooperating/genfiles").isSymbolicLink()).isTrue();
    assertThat(getWorkspace().getRelative("cooperating/testlogs").isSymbolicLink()).isTrue();
    assertThat(
            getWorkspace()
                .getRelative("cooperating/" + TestConstants.WORKSPACE_NAME)
                .isSymbolicLink())
        .isTrue();
    assertThat(getWorkspace().getRelative("cooperating/out").isSymbolicLink()).isTrue();
    assertThat(getWorkspace().getRelative("cooperating/original").isFile()).isTrue();
  }

  @Test
  public void settingSymlinksIgnoresSymlinksWithDifferentPrefix() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=new-prefix-",
        "--compilation_mode=fastbuild");
    Path binLink = getWorkspace().getChild("other-prefix-bin");
    Path genfilesLink = getWorkspace().getChild("other-prefix-genfiles");
    Path testlogsLink = getWorkspace().getChild("other-prefix-testlogs");
    Path workspaceLink = getWorkspace().getChild("other-prefix-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("other-prefix-out");

    PathFragment original = getOutputPath().getRelative("original/destination").asFragment();
    binLink.createSymbolicLink(original);
    genfilesLink.createSymbolicLink(original);
    testlogsLink.createSymbolicLink(original);
    workspaceLink.createSymbolicLink(original);
    outLink.createSymbolicLink(original);

    write("target/BUILD", "basic_rule(name='target')");
    buildTarget("//target:target");

    // Implicitly test for symlink-ness; readSymbolicLink would throw if they are not symlinks.
    assertThat(binLink.readSymbolicLink()).isEqualTo(original);
    assertThat(genfilesLink.readSymbolicLink()).isEqualTo(original);
    assertThat(testlogsLink.readSymbolicLink()).isEqualTo(original);
    assertThat(workspaceLink.readSymbolicLink()).isEqualTo(original);
    assertThat(outLink.readSymbolicLink()).isEqualTo(original);
  }

  @Test
  public void unsettingSymlinksRemovesConfigurationSymlinksIfAlreadyPresent() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=deleted-",
        "--compilation_mode=fastbuild",
        "--incompatible_skip_genfiles_symlink=false");
    Path binLink = getWorkspace().getChild("deleted-bin");
    Path genfilesLink = getWorkspace().getChild("deleted-genfiles");
    Path testlogsLink = getWorkspace().getChild("deleted-testlogs");
    Path workspaceLink = getWorkspace().getChild("deleted-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("deleted-out");

    Path config = getOutputPath().getRelative("some-imaginary-config");
    // put symlinks at the convenience symlinks spots to simulate a prior build
    binLink.createSymbolicLink(config.getChild("bin"));
    genfilesLink.createSymbolicLink(config.getChild("genfiles"));
    testlogsLink.createSymbolicLink(config.getChild("testlogs"));

    write("file/BUILD", "exports_files(['file'])");
    write("file/file", "this is just a file to pretend to build");
    buildTarget("//file:file");

    assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse();

    assertThat(workspaceLink.isSymbolicLink()).isTrue();
    assertThat(outLink.isSymbolicLink()).isTrue();
  }

  @Test
  public void unsettingSymlinksSucceedsIfNotAlreadyPresent() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=already-absent-",
        "--compilation_mode=fastbuild");
    Path binLink = getWorkspace().getChild("already-absent-bin");
    Path genfilesLink = getWorkspace().getChild("already-absent-genfiles");
    Path testlogsLink = getWorkspace().getChild("already-absent-testlogs");
    Path workspaceLink = getWorkspace().getChild("already-absent-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("already-absent-out");

    write("file/BUILD", "exports_files(['file'])");
    write("file/file", "this is just a file to pretend to build");
    buildTarget("//file:file");

    assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse();

    assertThat(workspaceLink.isSymbolicLink()).isTrue();
    assertThat(outLink.isSymbolicLink()).isTrue();
  }

  @Test
  public void unsettingSymlinksDoesNotRemoveNormalFilesAlreadyPresent() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=blocked-",
        "--compilation_mode=fastbuild");
    Path binLink = getWorkspace().getChild("blocked-bin");
    Path genfilesLink = getWorkspace().getChild("blocked-genfiles");
    Path testlogsLink = getWorkspace().getChild("blocked-testlogs");
    Path workspaceLink = getWorkspace().getChild("blocked-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("blocked-out");

    FileSystemUtils.writeIsoLatin1(binLink, "this file is not a symlink");
    FileSystemUtils.writeIsoLatin1(genfilesLink, "this file is not a symlink");
    FileSystemUtils.writeIsoLatin1(testlogsLink, "this file is not a symlink");
    FileSystemUtils.writeIsoLatin1(workspaceLink, "this file is not a symlink");
    FileSystemUtils.writeIsoLatin1(outLink, "this file is not a symlink");

    write("file/BUILD", "exports_files(['file'])");
    write("file/file", "this is just a file to pretend to build");
    buildTarget("//file:file");

    assertThat(binLink.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(genfilesLink.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(testlogsLink.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(workspaceLink.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(outLink.isFile(Symlinks.NOFOLLOW)).isTrue();
  }

  @Test
  public void unsettingSymlinksDoesNotRemoveDirectoriesAlreadyPresent() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=blocked-",
        "--compilation_mode=fastbuild");
    Path binLink = getWorkspace().getChild("blocked-bin");
    Path genfilesLink = getWorkspace().getChild("blocked-genfiles");
    Path testlogsLink = getWorkspace().getChild("blocked-testlogs");
    Path workspaceLink = getWorkspace().getChild("blocked-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("blocked-out");

    binLink.createDirectory();
    genfilesLink.createDirectory();
    testlogsLink.createDirectory();
    workspaceLink.createDirectory();
    outLink.createDirectory();

    write("file/BUILD", "exports_files(['file'])");
    write("file/file", "this is just a file to pretend to build");
    buildTarget("//file:file");

    assertThat(binLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(genfilesLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(testlogsLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(workspaceLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    assertThat(outLink.isDirectory(Symlinks.NOFOLLOW)).isTrue();
  }

  @Test
  public void unsettingSymlinksRemovesSymlinksEvenIfNotPointingInsideExecRoot() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=deleted-",
        "--compilation_mode=fastbuild",
        "--incompatible_skip_genfiles_symlink=false");
    Path binLink = getWorkspace().getChild("deleted-bin");
    Path genfilesLink = getWorkspace().getChild("deleted-genfiles");
    Path testlogsLink = getWorkspace().getChild("deleted-testlogs");
    Path workspaceLink = getWorkspace().getChild("deleted-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("deleted-out");

    Path original = getWorkspace().getRelative("/arbitrary/somewhere/else/in/the/filesystem");
    binLink.createSymbolicLink(original);
    genfilesLink.createSymbolicLink(original);
    testlogsLink.createSymbolicLink(original);
    workspaceLink.createSymbolicLink(original);
    outLink.createSymbolicLink(original);

    write("file/BUILD", "exports_files(['file'])");
    write("file/file", "this is just a file to pretend to build");
    buildTarget("//file:file");

    assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse();

    assertThat(workspaceLink.isSymbolicLink()).isTrue();
    assertThat(outLink.isSymbolicLink()).isTrue();
  }

  @Test
  public void unsettingSymlinksIgnoresSymlinksWithDifferentPrefix() throws Exception {
    addOptions(
        "--output_directory_name=from_flag",
        "--symlink_prefix=new-prefix-",
        "--compilation_mode=fastbuild");
    Path binLink = getWorkspace().getChild("other-prefix-bin");
    Path genfilesLink = getWorkspace().getChild("other-prefix-genfiles");
    Path testlogsLink = getWorkspace().getChild("other-prefix-testlogs");
    Path workspaceLink = getWorkspace().getChild("other-prefix-" + TestConstants.WORKSPACE_NAME);
    Path outLink = getWorkspace().getChild("other-prefix-out");

    PathFragment original = getOutputPath().getRelative("original/destination").asFragment();
    binLink.createSymbolicLink(original);
    genfilesLink.createSymbolicLink(original);
    testlogsLink.createSymbolicLink(original);
    workspaceLink.createSymbolicLink(original);
    outLink.createSymbolicLink(original);

    write("file/BUILD", "exports_files(['file'])");
    write("file/file", "this is just a file to pretend to build");
    buildTarget("//file:file");

    // Implicitly test for symlink-ness; readSymbolicLink would throw if they are not symlinks.
    assertThat(binLink.readSymbolicLink()).isEqualTo(original);
    assertThat(genfilesLink.readSymbolicLink()).isEqualTo(original);
    assertThat(testlogsLink.readSymbolicLink()).isEqualTo(original);
    assertThat(workspaceLink.readSymbolicLink()).isEqualTo(original);
    assertThat(outLink.readSymbolicLink()).isEqualTo(original);
  }

  @Test
  public void symlinkPrefix_specialNoCreateValue_doesNotCreateOrDeleteSymlinks() throws Exception {
    addOptions("--symlink_prefix=/");

    write("foo/BUILD", "exports_files(['bar.txt'])");
    write("foo/bar.txt", "This is just a test file to pretend to build.");

    // This will be a preexisting symlink and when --symlink_prefix=/ is used, assert that this
    // preexisting symlink still exists.
    Path binLink = getWorkspace().getChild("blaze-" + TestConstants.WORKSPACE_NAME);
    binLink.createSymbolicLink(PathFragment.create("foo/"));

    buildTarget("//foo:bar.txt");

    ImmutableMap<String, Path> symlinks = getConvenienceSymlinks();
    assertThat(symlinks).containsKey("blaze-" + TestConstants.WORKSPACE_NAME);
  }

  @Test
  public void convenienceSymlinks_ignore_leaveSymlinksUntouched() throws Exception {
    addOptions("--experimental_convenience_symlinks=ignore");

    write("foo/BUILD", "exports_files(['bar.txt'])");
    write("foo/bar.txt", "This is just a test file to pretend to build.");
    buildTarget("//foo:bar.txt");

    // This will be a preexisting symlink that will remain after the build
    Path binLink = getWorkspace().getChild("blaze-" + TestConstants.WORKSPACE_NAME);
    binLink.createSymbolicLink(PathFragment.create("foo/"));

    ImmutableMap<String, Path> symlinks = getConvenienceSymlinks();
    assertThat(symlinks).containsKey("blaze-" + TestConstants.WORKSPACE_NAME);
  }

  @Test
  public void convenienceSymlinks_normal_createSymlinks() throws Exception {
    addOptions("--symlink_prefix=test-", "--experimental_convenience_symlinks=normal");

    write("foo/BUILD", "exports_files(['bar.txt'])");
    write("foo/bar.txt", "This is just a test file to pretend to build.");
    buildTarget("//foo:bar.txt");

    ImmutableMap<String, Path> symlinks = getConvenienceSymlinks();
    assertThat(symlinks).containsKey("test-" + TestConstants.WORKSPACE_NAME);
    assertThat(symlinks).containsKey("test-out");
  }

  @Test
  public void convenienceSymlinks_clean_deletesAndDoesNotCreateSymlinks() throws Exception {
    addOptions("--symlink_prefix=test-", "--experimental_convenience_symlinks=clean");

    write("foo/BUILD", "exports_files(['bar.txt'])");
    write("foo/bar.txt", "This is just a test file to pretend to build.");

    // This will be a preexisting symlink that will be deleted after the build
    Path binLink = getWorkspace().getChild("test-" + TestConstants.WORKSPACE_NAME);
    binLink.createSymbolicLink(PathFragment.create("foo"));

    buildTarget("//foo:bar.txt");

    ImmutableMap<String, Path> symlinks = getConvenienceSymlinks();
    assertThat(symlinks).doesNotContainKey("test-" + TestConstants.WORKSPACE_NAME);
    assertThat(symlinks).doesNotContainKey("test-out");
  }
}
