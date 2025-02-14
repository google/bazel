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
package com.google.devtools.build.lib.analysis;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType.ABSTRACT;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType.TEST;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.analysis.RuleContext.PrerequisiteValidator;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentClassSet;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.FragmentRegistry;
import com.google.devtools.build.lib.analysis.config.SymlinkDefinition;
import com.google.devtools.build.lib.analysis.config.transitions.ComposingTransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics;
import com.google.devtools.build.lib.analysis.constraints.RuleContextConstraintSemantics;
import com.google.devtools.build.lib.analysis.starlark.StarlarkGlobalsImpl;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.graph.Digraph;
import com.google.devtools.build.lib.graph.Node;
import com.google.devtools.build.lib.packages.BazelStarlarkEnvironment;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.RuleFactory;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.WorkspaceFactory;
import com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.OptionDefinition;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkAnnotations;
import net.starlark.java.annot.StarlarkBuiltin;

/**
 * Knows about every rule Blaze supports and the associated configuration options.
 *
 * <p>This class is initialized on server startup and the set of rules, build info factories and
 * configuration options is guaranteed not to change over the life time of the Blaze server.
 */
// This class has no subclasses except those created by the evil that is mockery.
public /*final*/ class ConfiguredRuleClassProvider
    implements RuleClassProvider, BuildConfigurationValue.GlobalStateProvider {

  /**
   * A coherent set of options, fragments, aspects and rules; each of these may declare a dependency
   * on other such sets.
   */
  public interface RuleSet {
    /** Add stuff to the configured rule class provider builder. */
    void init(ConfiguredRuleClassProvider.Builder builder);

    /** List of required modules. */
    default ImmutableList<RuleSet> requires() {
      return ImmutableList.of();
    }
  }

  /** An InMemoryFileSystem for bundled builtins .bzl files. */
  public static class BundledFileSystem extends InMemoryFileSystem {
    public BundledFileSystem() {
      super(DigestHashFunction.SHA256);
    }

    // Pretend the digest of a bundled file is uniquely determined by its name, not its contents.
    //
    // The contents bundled files are guaranteed to not change throughout the lifetime of the Bazel
    // server, we do not need to detect changes to a bundled file's contents. Not needing to worry
    // about get the actual digest and detect changes to that digest helps avoid peculiarities in
    // the interaction of InMemoryFileSystem and Skyframe. See cl/354809138 for further discussion,
    // including of possible (but unlikely) future caveats of this approach.
    //
    // On the other hand, we do need to want different bundled files to have different digests. That
    // way the bzl environment hashes for bzl rule classes defined in two different bundled files
    // are guaranteed to be different, even if their set of transitive load statements is the same.
    // This is important because it's possible for bzl rule classes defined in different files to
    // have the same name string, and various part of Blaze rely on the pair of
    // "rule class name string" and "bzl environment hash" to uniquely identify a bzl rule class.
    // See b/226379109 for details.

    @Override
    protected synchronized byte[] getFastDigest(PathFragment path) {
      return getDigest(path);
    }

    @Override
    protected synchronized byte[] getDigest(PathFragment path) {
      return getDigestFunction()
          .getHashFunction()
          .hashBytes(StringUnsafe.getInstance().getInternalStringBytes(path.getPathString()))
          .asBytes();
    }
  }

  /** Builder for {@link ConfiguredRuleClassProvider}. */
  public static final class Builder implements RuleDefinitionEnvironment {
    private final StringBuilder defaultWorkspaceFilePrefix = new StringBuilder();
    private final StringBuilder defaultWorkspaceFileSuffix = new StringBuilder();
    private Label preludeLabel;
    private String runfilesPrefix;
    private RepositoryName toolsRepository;
    @Nullable private String builtinsBzlZipResource;
    private boolean useDummyBuiltinsBzlInsteadOfResource = false;
    @Nullable private String builtinsBzlPackagePathInSource;
    private final List<Class<? extends Fragment>> configurationFragmentClasses = new ArrayList<>();
    private final List<Class<? extends FragmentOptions>> configurationOptions = new ArrayList<>();

    private final Map<String, RuleClass> ruleClassMap = new LinkedHashMap<>();
    private final Map<String, RuleDefinition> ruleDefinitionMap = new LinkedHashMap<>();
    private final Map<String, NativeAspectClass> nativeAspectClassMap = new LinkedHashMap<>();
    private final Map<Class<? extends RuleDefinition>, RuleClass> ruleMap = new LinkedHashMap<>();
    private final Digraph<Class<? extends RuleDefinition>> dependencyGraph = new Digraph<>();
    private final List<Class<? extends Fragment>> universalFragments = new ArrayList<>();
    @Nullable private TransitionFactory<RuleTransitionData> trimmingTransitionFactory = null;
    @Nullable private PatchTransition toolchainTaggedTrimmingTransition = null;
    private OptionsDiffPredicate shouldInvalidateCacheForOptionDiff =
        OptionsDiffPredicate.ALWAYS_INVALIDATE;
    private PrerequisiteValidator prerequisiteValidator;
    private final ImmutableMap.Builder<String, Object> buildFileToplevels = ImmutableMap.builder();
    private final ImmutableList.Builder<Bootstrap> starlarkBootstraps = ImmutableList.builder();
    private final ImmutableMap.Builder<String, Object> starlarkAccessibleTopLevels =
        ImmutableMap.builder();
    private final ImmutableMap.Builder<String, Object> starlarkBuiltinsInternals =
        ImmutableMap.builder();
    private final ImmutableList.Builder<SymlinkDefinition> symlinkDefinitions =
        ImmutableList.builder();
    private final Set<String> reservedActionMnemonics = new TreeSet<>();
    private Function<BuildOptions, ActionEnvironment> actionEnvironmentProvider =
        (BuildOptions options) -> ActionEnvironment.EMPTY;
    private ConstraintSemantics<RuleContext> constraintSemantics =
        new RuleContextConstraintSemantics();

    // TODO(b/192694287): Remove once we migrate all tests from the allowlist
    @Nullable private Label networkAllowlistForTests;

    @CanIgnoreReturnValue
    public Builder addWorkspaceFilePrefix(String contents) {
      defaultWorkspaceFilePrefix.append(contents);
      return this;
    }

    @CanIgnoreReturnValue
    @VisibleForTesting
    public Builder clearWorkspaceFilePrefixForTesting() {
      defaultWorkspaceFilePrefix.delete(0, defaultWorkspaceFilePrefix.length());
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addWorkspaceFileSuffix(String contents) {
      defaultWorkspaceFileSuffix.append(contents);
      return this;
    }

    @CanIgnoreReturnValue
    @VisibleForTesting
    public Builder clearWorkspaceFileSuffixForTesting() {
      defaultWorkspaceFileSuffix.delete(0, defaultWorkspaceFileSuffix.length());
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setPrelude(String preludeLabelString) {
      Preconditions.checkArgument(
          preludeLabelString.startsWith("//"),
          "Prelude label '%s' must start with '//'",
          preludeLabelString);
      try {
        // We're parsing this label as if it's in the main repository but it will actually get
        // massaged into a label in the repository where the package being loaded resides.
        this.preludeLabel = Label.parseCanonical(preludeLabelString);
      } catch (LabelSyntaxException e) {
        String errorMsg =
            String.format("Prelude label '%s' is invalid: %s", preludeLabelString, e.getMessage());
        throw new IllegalArgumentException(errorMsg);
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setRunfilesPrefix(String runfilesPrefix) {
      this.runfilesPrefix = runfilesPrefix;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setToolsRepository(RepositoryName toolsRepository) {
      this.toolsRepository = toolsRepository;
      return this;
    }

    /**
     * Sets the resource path to the builtins_bzl.zip resource.
     *
     * <p>This value is required for production uses. For uses in tests, this may be left null, but
     * the resulting rule class provider will not work with {@code
     * --experimental_builtins_bzl_path=%bundled%}. Alternatively, tests may call {@link
     * #useDummyBuiltinsBzl} if they do not rely on any native rules that may be migratable to
     * Starlark.
     */
    @CanIgnoreReturnValue
    public Builder setBuiltinsBzlZipResource(String name) {
      this.builtinsBzlZipResource = name;
      this.useDummyBuiltinsBzlInsteadOfResource = false;
      return this;
    }

    /**
     * Instructs the rule class provider to use a set of dummy builtins definitions that inject no
     * symbols.
     *
     * <p>This is only suitable for use in tests, and only when the test does not depend (even
     * implicitly) on native rules. For example, pure tests of package loading behavior may call
     * this method, but not tests that use AnalysisMock. Otherwise the test may break when a native
     * rule is migrated to Starlark via builtins injection.
     */
    @CanIgnoreReturnValue
    public Builder useDummyBuiltinsBzl() {
      this.builtinsBzlZipResource = null;
      this.useDummyBuiltinsBzlInsteadOfResource = true;
      return this;
    }

    /**
     * Sets the relative location of the builtins_bzl directory within a Bazel source tree.
     *
     * <p>This is required if the rule class provider will be used with {@code
     * --experimental_builtins_bzl_path=%workspace%}, but can be skipped in unit tests.
     */
    @CanIgnoreReturnValue
    public Builder setBuiltinsBzlPackagePathInSource(String path) {
      this.builtinsBzlPackagePathInSource = path;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setPrerequisiteValidator(PrerequisiteValidator prerequisiteValidator) {
      this.prerequisiteValidator = prerequisiteValidator;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addRuleDefinition(RuleDefinition ruleDefinition) {
      Class<? extends RuleDefinition> ruleDefinitionClass = ruleDefinition.getClass();
      ruleDefinitionMap.put(ruleDefinitionClass.getName(), ruleDefinition);
      dependencyGraph.createNode(ruleDefinitionClass);
      for (Class<? extends RuleDefinition> ancestor : ruleDefinition.getMetadata().ancestors()) {
        dependencyGraph.addEdge(ancestor, ruleDefinitionClass);
      }

      return this;
    }

    @CanIgnoreReturnValue
    public Builder addNativeAspectClass(NativeAspectClass aspectFactoryClass) {
      nativeAspectClassMap.put(aspectFactoryClass.getName(), aspectFactoryClass);
      return this;
    }

    /**
     * Adds a configuration fragment and all build options required by its fragment.
     *
     * <p>Note that configuration fragments annotated with a Starlark name must have a unique name;
     * no two different configuration fragments can share the same name.
     */
    @CanIgnoreReturnValue
    public Builder addConfigurationFragment(Class<? extends Fragment> fragmentClass) {
      configurationFragmentClasses.add(fragmentClass);
      return this;
    }

    /**
     * Adds configuration options that aren't required by configuration fragments.
     *
     * <p>If {@link #addConfigurationFragment} adds a fragment that also requires these options,
     * this method is redundant.
     */
    @CanIgnoreReturnValue
    public Builder addConfigurationOptions(Class<? extends FragmentOptions> configurationOptions) {
      this.configurationOptions.add(configurationOptions);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addUniversalConfigurationFragment(Class<? extends Fragment> fragment) {
      this.universalFragments.add(fragment);
      addConfigurationFragment(fragment);
      return this;
    }

    /**
     * Registers a new top-level symbol for BUILD files.
     *
     * <p>The symbol will also be available in BUILD-loaded .bzl files under the {@code native}
     * module.
     */
    @CanIgnoreReturnValue
    public Builder addBuildFileToplevel(String name, Object object) {
      this.buildFileToplevels.put(name, object);
      return this;
    }

    /**
     * Registers all symbols contained in the {@code Bootstrap} as top-level symbols for .bzl files.
     */
    @CanIgnoreReturnValue
    public Builder addStarlarkBootstrap(Bootstrap bootstrap) {
      this.starlarkBootstraps.add(bootstrap);
      return this;
    }

    /** Registers a new top-level symbol for .bzl files. */
    @CanIgnoreReturnValue
    public Builder addBzlToplevel(String name, Object object) {
      this.starlarkAccessibleTopLevels.put(name, object);
      return this;
    }

    /**
     * Registers a new symbol for {@code @_builtins} .bzl files, to be made available under the
     * {@code _builtins.internal} object.
     */
    @CanIgnoreReturnValue
    public Builder addStarlarkBuiltinsInternal(String name, Object object) {
      this.starlarkBuiltinsInternals.put(name, object);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addSymlinkDefinition(SymlinkDefinition symlinkDefinition) {
      this.symlinkDefinitions.add(symlinkDefinition);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addReservedActionMnemonic(String mnemonic) {
      this.reservedActionMnemonics.add(mnemonic);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setActionEnvironmentProvider(
        Function<BuildOptions, ActionEnvironment> actionEnvironmentProvider) {
      this.actionEnvironmentProvider = actionEnvironmentProvider;
      return this;
    }

    /**
     * Sets the logic that lets rules declare which environments they support and validates rules
     * don't depend on rules that aren't compatible with the same environments. Defaults to {@link
     * ConstraintSemantics}. See {@link ConstraintSemantics} for more details.
     */
    @CanIgnoreReturnValue
    public Builder setConstraintSemantics(ConstraintSemantics<RuleContext> constraintSemantics) {
      this.constraintSemantics = constraintSemantics;
      return this;
    }

    /**
     * Adds a transition factory that produces a trimming transition to be run over all targets
     * after other transitions.
     *
     * <p>Transitions are run in the order they're added.
     *
     * <p>This is a temporary measure for supporting trimming of test rules and manual trimming of
     * feature flags, and support for this transition factory will likely be removed at some point
     * in the future (whenever automatic trimming is sufficiently workable).
     */
    @CanIgnoreReturnValue
    public Builder addTrimmingTransitionFactory(TransitionFactory<RuleTransitionData> factory) {
      Preconditions.checkNotNull(factory);
      Preconditions.checkArgument(!factory.isSplit());
      if (trimmingTransitionFactory == null) {
        trimmingTransitionFactory = factory;
      } else {
        trimmingTransitionFactory =
            ComposingTransitionFactory.of(trimmingTransitionFactory, factory);
      }
      return this;
    }

    /** Sets the transition manual feature flag trimming should apply to toolchain deps. */
    @CanIgnoreReturnValue
    public Builder setToolchainTaggedTrimmingTransition(PatchTransition transition) {
      Preconditions.checkNotNull(transition);
      Preconditions.checkState(toolchainTaggedTrimmingTransition == null);
      this.toolchainTaggedTrimmingTransition = transition;
      return this;
    }

    /**
     * Overrides the transition factory run over all targets.
     *
     * @see #addTrimmingTransitionFactory(TransitionFactory)
     */
    @VisibleForTesting(/* for testing trimming transition factories without relying on prod use */ )
    public Builder overrideTrimmingTransitionFactoryForTesting(
        TransitionFactory<RuleTransitionData> factory) {
      trimmingTransitionFactory = null;
      return this.addTrimmingTransitionFactory(factory);
    }

    /**
     * Sets the predicate which determines whether the analysis cache should be invalidated for the
     * given options diff.
     */
    @CanIgnoreReturnValue
    public Builder setShouldInvalidateCacheForOptionDiff(
        OptionsDiffPredicate shouldInvalidateCacheForOptionDiff) {
      Preconditions.checkState(
          this.shouldInvalidateCacheForOptionDiff.equals(OptionsDiffPredicate.ALWAYS_INVALIDATE),
          "Cache invalidation function was already set");
      this.shouldInvalidateCacheForOptionDiff = shouldInvalidateCacheForOptionDiff;
      return this;
    }

    /**
     * Overrides the predicate which determines whether the analysis cache should be invalidated for
     * the given options diff.
     */
    @VisibleForTesting(/* for testing cache invalidation without relying on prod use */ )
    Builder overrideShouldInvalidateCacheForOptionDiffForTesting(
        OptionsDiffPredicate shouldInvalidateCacheForOptionDiff) {
      this.shouldInvalidateCacheForOptionDiff = OptionsDiffPredicate.ALWAYS_INVALIDATE;
      return this.setShouldInvalidateCacheForOptionDiff(shouldInvalidateCacheForOptionDiff);
    }

    private static RuleConfiguredTargetFactory createFactory(
        Class<? extends RuleConfiguredTargetFactory> factoryClass) {
      try {
        Constructor<? extends RuleConfiguredTargetFactory> ctor = factoryClass.getConstructor();
        return ctor.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    private void commitRuleDefinition(Class<? extends RuleDefinition> definitionClass) {
      RuleDefinition instance =
          checkNotNull(
              ruleDefinitionMap.get(definitionClass.getName()),
              "addRuleDefinition(new %s()) should be called before build()",
              definitionClass.getName());

      RuleDefinition.Metadata metadata = instance.getMetadata();
      checkArgument(
          ruleClassMap.get(metadata.name()) == null,
          "The rule %s was committed already, use another name",
          metadata.name());

      List<Class<? extends RuleDefinition>> ancestors = metadata.ancestors();

      checkArgument(
          metadata.type() == ABSTRACT
              ^ metadata.factoryClass() != RuleConfiguredTargetFactory.class);
      checkArgument(
          (metadata.type() != TEST) || ancestors.contains(BaseRuleClasses.TestBaseRule.class));

      RuleClass[] ancestorClasses = new RuleClass[ancestors.size()];
      for (int i = 0; i < ancestorClasses.length; i++) {
        ancestorClasses[i] = ruleMap.get(ancestors.get(i));
        if (ancestorClasses[i] == null) {
          // Ancestors should have been initialized by now
          throw new IllegalStateException(
              "Ancestor " + ancestors.get(i) + " of " + metadata.name() + " is not initialized");
        }
      }

      RuleConfiguredTargetFactory factory = null;
      if (metadata.type() != ABSTRACT) {
        factory = createFactory(metadata.factoryClass());
      }

      RuleClass.Builder builder =
          new RuleClass.Builder(metadata.name(), metadata.type(), false, ancestorClasses);
      builder.factory(factory);
      RuleClass ruleClass = instance.build(builder, this);
      ruleMap.put(definitionClass, ruleClass);
      ruleClassMap.put(ruleClass.getName(), ruleClass);
      ruleDefinitionMap.put(ruleClass.getName(), instance);
    }

    /**
     * Locates the builtins zip file as a Java resource, and unpacks it into the given directory.
     * Note that the builtins_bzl/ entry itself in the zip is not copied, just its children.
     */
    private static void unpackBuiltinsBzlZipResource(String builtinsResourceName, Path targetRoot) {
      ClassLoader loader = ConfiguredRuleClassProvider.class.getClassLoader();
      try (InputStream builtinsZip = loader.getResourceAsStream(builtinsResourceName)) {
        Preconditions.checkArgument(
            builtinsZip != null, "No resource with name %s", builtinsResourceName);

        try (ZipInputStream zip = new ZipInputStream(builtinsZip)) {
          for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            String entryName = entry.getName();
            Preconditions.checkArgument(entryName.startsWith("builtins_bzl/"));
            Path dest = targetRoot.getRelative(entryName.substring("builtins_bzl/".length()));

            dest.getParentDirectory().createDirectoryAndParents();
            try (OutputStream os = dest.getOutputStream()) {
              ByteStreams.copy(zip, os);
            }
          }
        }
      } catch (IOException ex) {
        throw new IllegalArgumentException(
            "Error while unpacking builtins_bzl zip resource file", ex);
      }
    }

    public ConfiguredRuleClassProvider build() {
      for (Node<Class<? extends RuleDefinition>> ruleDefinition :
          dependencyGraph.getTopologicalOrder()) {
        commitRuleDefinition(ruleDefinition.getLabel());
      }

      // Determine the bundled builtins root, if it exists.
      Root builtinsRoot;
      if (builtinsBzlZipResource == null && !useDummyBuiltinsBzlInsteadOfResource) {
        // Use of --experimental_builtins_bzl_path=%bundled% is disallowed.
        builtinsRoot = null;
      } else {
        BundledFileSystem fs = new BundledFileSystem();
        Path builtinsPath = fs.getPath("/virtual_builtins_bzl");
        if (builtinsBzlZipResource != null) {
          // Production case.
          unpackBuiltinsBzlZipResource(builtinsBzlZipResource, builtinsPath);
        } else {
          // Dummy case, use empty bundled builtins content.
          try {
            builtinsPath.createDirectoryAndParents();
            try (OutputStream os = builtinsPath.getRelative("exports.bzl").getOutputStream()) {
              String emptyExports =
                  ("exported_rules = {}\n" //
                      + "exported_toplevels = {}\n"
                      + "exported_to_java = {}\n");
              os.write(emptyExports.getBytes(UTF_8));
            }
          } catch (IOException ex) {
            throw new IllegalStateException("Failed to write dummy builtins root", ex);
          }
        }
        builtinsRoot = Root.fromPath(builtinsPath);
      }

      return new ConfiguredRuleClassProvider(
          preludeLabel,
          runfilesPrefix,
          toolsRepository,
          builtinsRoot,
          builtinsBzlPackagePathInSource,
          ImmutableMap.copyOf(ruleClassMap),
          ImmutableMap.copyOf(ruleDefinitionMap),
          ImmutableMap.copyOf(nativeAspectClassMap),
          FragmentRegistry.create(
              configurationFragmentClasses, universalFragments, configurationOptions),
          defaultWorkspaceFilePrefix.toString(),
          defaultWorkspaceFileSuffix.toString(),
          trimmingTransitionFactory,
          toolchainTaggedTrimmingTransition,
          shouldInvalidateCacheForOptionDiff,
          prerequisiteValidator,
          buildFileToplevels.buildOrThrow(),
          starlarkAccessibleTopLevels.buildOrThrow(),
          starlarkBuiltinsInternals.buildOrThrow(),
          starlarkBootstraps.build(),
          symlinkDefinitions.build(),
          ImmutableSet.copyOf(reservedActionMnemonics),
          actionEnvironmentProvider,
          constraintSemantics,
          networkAllowlistForTests);
    }

    @Override
    public RepositoryName getToolsRepository() {
      return toolsRepository;
    }

    @Override
    public Optional<Label> getNetworkAllowlistForTests() {
      return Optional.ofNullable(networkAllowlistForTests);
    }

    @CanIgnoreReturnValue
    public Builder setNetworkAllowlistForTests(Label allowlist) {
      networkAllowlistForTests = allowlist;
      return this;
    }
  }

  /** Default content that should be added at the beginning of the WORKSPACE file. */
  private final String defaultWorkspaceFilePrefix;

  /** Default content that should be added at the end of the WORKSPACE file. */
  private final String defaultWorkspaceFileSuffix;

  /** Label for the prelude file. */
  private final Label preludeLabel;

  /** The default runfiles prefix. */
  private final String runfilesPrefix;

  /** The path to the tools repository. */
  private final RepositoryName toolsRepository;

  /**
   * Where the builtins bzl files are located (if not overridden by
   * --experimental_builtins_bzl_path). Note that this lives in a separate InMemoryFileSystem.
   *
   * <p>May be null in tests, in which case --experimental_builtins_bzl_path must point to a
   * builtins root.
   */
  @Nullable private final Root bundledBuiltinsRoot;

  /**
   * The relative location of the builtins_bzl directory within a Bazel source tree.
   *
   * <p>May be null in tests, in which case --experimental_builtins_bzl_path may not be
   * "%workspace%".
   */
  @Nullable private final String builtinsBzlPackagePathInSource;

  /** Maps rule class name to the metaclass instance for that rule. */
  private final ImmutableMap<String, RuleClass> ruleClassMap;

  /** Maps rule class name to the rule definition objects. */
  private final ImmutableMap<String, RuleDefinition> ruleDefinitionMap;

  /** Maps aspect name to the aspect factory meta class. */
  private final ImmutableMap<String, NativeAspectClass> nativeAspectClassMap;

  private final FragmentRegistry fragmentRegistry;

  /** The transition factory used to produce the transition that will trim targets. */
  @Nullable private final TransitionFactory<RuleTransitionData> trimmingTransitionFactory;

  /** The transition to apply to toolchain deps for manual trimming. */
  @Nullable private final PatchTransition toolchainTaggedTrimmingTransition;

  /** The predicate used to determine whether a diff requires the cache to be invalidated. */
  private final OptionsDiffPredicate shouldInvalidateCacheForOptionDiff;

  private final PrerequisiteValidator prerequisiteValidator;

  private final BazelStarlarkEnvironment bazelStarlarkEnvironment;

  private final ImmutableList<SymlinkDefinition> symlinkDefinitions;

  private final ImmutableSet<String> reservedActionMnemonics;

  private final Function<BuildOptions, ActionEnvironment> actionEnvironmentProvider;

  private final ImmutableMap<String, Class<?>> configurationFragmentMap;

  private final ConstraintSemantics<RuleContext> constraintSemantics;

  // TODO(b/192694287): Remove once we migrate all tests from the allowlist
  @Nullable private final Label networkAllowlistForTests;

  private ConfiguredRuleClassProvider(
      Label preludeLabel,
      String runfilesPrefix,
      RepositoryName toolsRepository,
      @Nullable Root bundledBuiltinsRoot,
      @Nullable String builtinsBzlPackagePathInSource,
      ImmutableMap<String, RuleClass> ruleClassMap,
      ImmutableMap<String, RuleDefinition> ruleDefinitionMap,
      ImmutableMap<String, NativeAspectClass> nativeAspectClassMap,
      FragmentRegistry fragmentRegistry,
      String defaultWorkspaceFilePrefix,
      String defaultWorkspaceFileSuffix,
      @Nullable TransitionFactory<RuleTransitionData> trimmingTransitionFactory,
      PatchTransition toolchainTaggedTrimmingTransition,
      OptionsDiffPredicate shouldInvalidateCacheForOptionDiff,
      PrerequisiteValidator prerequisiteValidator,
      ImmutableMap<String, Object> buildFileToplevels,
      ImmutableMap<String, Object> starlarkAccessibleTopLevels,
      ImmutableMap<String, Object> starlarkBuiltinsInternals,
      ImmutableList<Bootstrap> starlarkBootstraps,
      ImmutableList<SymlinkDefinition> symlinkDefinitions,
      ImmutableSet<String> reservedActionMnemonics,
      Function<BuildOptions, ActionEnvironment> actionEnvironmentProvider,
      ConstraintSemantics<RuleContext> constraintSemantics,
      @Nullable Label networkAllowlistForTests) {
    this.preludeLabel = preludeLabel;
    this.runfilesPrefix = runfilesPrefix;
    this.toolsRepository = toolsRepository;
    this.bundledBuiltinsRoot = bundledBuiltinsRoot;
    this.builtinsBzlPackagePathInSource = builtinsBzlPackagePathInSource;
    this.ruleClassMap = ruleClassMap;
    this.ruleDefinitionMap = ruleDefinitionMap;
    this.nativeAspectClassMap = nativeAspectClassMap;
    this.fragmentRegistry = fragmentRegistry;
    this.defaultWorkspaceFilePrefix = defaultWorkspaceFilePrefix;
    this.defaultWorkspaceFileSuffix = defaultWorkspaceFileSuffix;
    this.trimmingTransitionFactory = trimmingTransitionFactory;
    this.toolchainTaggedTrimmingTransition = toolchainTaggedTrimmingTransition;
    this.shouldInvalidateCacheForOptionDiff = shouldInvalidateCacheForOptionDiff;
    this.prerequisiteValidator = prerequisiteValidator;
    this.symlinkDefinitions = symlinkDefinitions;
    this.reservedActionMnemonics = reservedActionMnemonics;
    this.actionEnvironmentProvider = actionEnvironmentProvider;
    this.configurationFragmentMap = createFragmentMap(fragmentRegistry.getAllFragments());
    this.constraintSemantics = constraintSemantics;
    this.networkAllowlistForTests = networkAllowlistForTests;

    ImmutableMap<String, Object> registeredBzlToplevels =
        createRegisteredBzlToplevels(starlarkAccessibleTopLevels, starlarkBootstraps);
    // If needed, we could allow the version to be customized by the builder e.g. for unit testing,
    // but at the moment it suffices to use the production value unconditionally.
    String version = BlazeVersionInfo.instance().getVersion();
    this.bazelStarlarkEnvironment =
        new BazelStarlarkEnvironment(
            StarlarkGlobalsImpl.INSTANCE,
            /* ruleFunctions= */ RuleFactory.buildRuleFunctions(ruleClassMap),
            buildFileToplevels,
            registeredBzlToplevels,
            /* workspaceBzlNativeBindings= */ WorkspaceFactory.createNativeModuleBindings(
                ruleClassMap, version),
            /* builtinsInternals= */ starlarkBuiltinsInternals);
  }

  public PrerequisiteValidator getPrerequisiteValidator() {
    return prerequisiteValidator;
  }

  @Override
  public Label getPreludeLabel() {
    return preludeLabel;
  }

  @Override
  public String getRunfilesPrefix() {
    return runfilesPrefix;
  }

  @Override
  public RepositoryName getToolsRepository() {
    return toolsRepository;
  }

  @Override
  @Nullable
  public Root getBundledBuiltinsRoot() {
    return bundledBuiltinsRoot;
  }

  @Override
  @Nullable
  public String getBuiltinsBzlPackagePathInSource() {
    return builtinsBzlPackagePathInSource;
  }

  @Override
  public ImmutableMap<String, RuleClass> getRuleClassMap() {
    return ruleClassMap;
  }

  @Override
  public Map<String, NativeAspectClass> getNativeAspectClassMap() {
    return nativeAspectClassMap;
  }

  @Override
  public NativeAspectClass getNativeAspectClass(String key) {
    return nativeAspectClassMap.get(key);
  }

  @Override
  public FragmentRegistry getFragmentRegistry() {
    return fragmentRegistry;
  }

  /**
   * Returns the transition factory used to produce the transition to trim targets.
   *
   * <p>This is a temporary measure for supporting manual trimming of feature flags, and support for
   * this transition factory will likely be removed at some point in the future (whenever automatic
   * trimming is sufficiently workable
   */
  @Nullable
  public TransitionFactory<RuleTransitionData> getTrimmingTransitionFactory() {
    return trimmingTransitionFactory;
  }

  /**
   * Returns the transition manual feature flag trimming should apply to toolchain deps.
   *
   * <p>See extra notes on {@link #getTrimmingTransitionFactory()}.
   */
  @Nullable
  public PatchTransition getToolchainTaggedTrimmingTransition() {
    return toolchainTaggedTrimmingTransition;
  }

  /** Returns whether the analysis cache should be invalidated for the given option diff. */
  public boolean shouldInvalidateCacheForOptionDiff(
      BuildOptions newOptions, OptionDefinition changedOption, Object oldValue, Object newValue) {
    return shouldInvalidateCacheForOptionDiff.apply(newOptions, changedOption, oldValue, newValue);
  }

  /** Returns the definition of the rule class definition with the specified name. */
  public RuleDefinition getRuleClassDefinition(String ruleClassName) {
    return ruleDefinitionMap.get(ruleClassName);
  }

  private static ImmutableMap<String, Object> createRegisteredBzlToplevels(
      ImmutableMap<String, Object> starlarkAccessibleTopLevels,
      ImmutableList<Bootstrap> bootstraps) {
    ImmutableMap.Builder<String, Object> bindings = ImmutableMap.builder();
    bindings.putAll(starlarkAccessibleTopLevels);
    for (Bootstrap bootstrap : bootstraps) {
      bootstrap.addBindingsToBuilder(bindings);
    }
    return bindings.buildOrThrow();
  }

  private static ImmutableMap<String, Class<?>> createFragmentMap(
      FragmentClassSet configurationFragments) {
    ImmutableMap.Builder<String, Class<?>> mapBuilder = ImmutableMap.builder();
    for (Class<? extends Fragment> fragmentClass : configurationFragments) {
      StarlarkBuiltin fragmentModule = StarlarkAnnotations.getStarlarkBuiltin(fragmentClass);
      if (fragmentModule != null) {
        mapBuilder.put(fragmentModule.name(), fragmentClass);
      }
    }
    return mapBuilder.buildOrThrow();
  }

  @Override
  public BazelStarlarkEnvironment getBazelStarlarkEnvironment() {
    return bazelStarlarkEnvironment;
  }

  @Override
  public String getDefaultWorkspacePrefix() {
    return defaultWorkspaceFilePrefix;
  }

  @Override
  public String getDefaultWorkspaceSuffix() {
    return defaultWorkspaceFileSuffix;
  }

  @Override
  public ImmutableMap<String, Class<?>> getConfigurationFragmentMap() {
    return configurationFragmentMap;
  }

  /**
   * Returns the symlink definitions introduced by the fragments registered with this rule class
   * provider.
   *
   * <p>This only includes definitions added by {@link Builder#addSymlinkDefinition}, not the
   * standard symlinks in {@link com.google.devtools.build.lib.buildtool.OutputDirectoryLinksUtils}.
   *
   * <p>Note: Usages of custom symlink definitions should be very rare. This feature was added to
   * implement the py2-bin / py3-bin symlinks, which have since been removed from Bazel.
   */
  // TODO(bazel-team): Delete?
  public ImmutableList<SymlinkDefinition> getSymlinkDefinitions() {
    return symlinkDefinitions;
  }

  public ConstraintSemantics<RuleContext> getConstraintSemantics() {
    return constraintSemantics;
  }

  @Override
  public Optional<Label> getNetworkAllowlistForTests() {
    return Optional.ofNullable(networkAllowlistForTests);
  }

  /** Returns a reserved set of action mnemonics. These cannot be used from a Starlark action. */
  @Override
  public ImmutableSet<String> getReservedActionMnemonics() {
    return reservedActionMnemonics;
  }

  @Override
  public ActionEnvironment getActionEnvironment(BuildOptions buildOptions) {
    return actionEnvironmentProvider.apply(buildOptions);
  }
}
