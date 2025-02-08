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

package com.google.devtools.build.lib.packages;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.BazelModuleContext.LoadGraphVisitor;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.StarlarkThreadContext;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.Package.Builder.PackageSettings;
import com.google.devtools.build.lib.packages.TargetRecorder.MacroNamespaceViolationException;
import com.google.devtools.build.lib.packages.TargetRecorder.NameConflictException;
import com.google.devtools.build.lib.packages.WorkspaceFileValue.WorkspaceFileKey;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading.Code;
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator;
import net.starlark.java.syntax.Location;

/**
 * A package, which is a container of {@link Rule}s, each of which contains a dictionary of named
 * attributes.
 *
 * <p>Package instances are intended to be immutable and for all practical purposes can be treated
 * as such. Note, however, that some member variables exposed via the public interface are not
 * strictly immutable, so until their types are guaranteed immutable we're not applying the
 * {@code @Immutable} annotation here.
 *
 * <p>This class should not be extended - it's only non-final for mocking!
 *
 * <p>When changing this class, make sure to make corresponding changes to serialization!
 */
@SuppressWarnings("JavaLangClash")
public class Package {

  // TODO(bazel-team): This class and its builder are ginormous. Future refactoring work might
  // attempt to separate the concerns of:
  //   - instantiating targets/macros, adding them to the package, and accessing/indexing them
  //     afterwards
  //   - utility logical like validating names, checking for conflicts, etc.
  //   - tracking and enforcement of limits
  //   - grouping metadata based on whether it's known prior to BUILD file evaluation, prior to
  //     symbolic macro evalutaion, or at the time of final Package construction
  //   - machinery specific to external package / WORKSPACE / bzlmod

  // ==== Static fields and enums ====

  /**
   * How to enforce config_setting visibility settings.
   *
   * <p>This is a temporary setting in service of https://github.com/bazelbuild/bazel/issues/12669.
   * After enough depot cleanup, config_setting will have the same visibility enforcement as all
   * other rules.
   */
  public enum ConfigSettingVisibilityPolicy {
    /** Don't enforce visibility for any config_setting. */
    LEGACY_OFF,
    /** Honor explicit visibility settings on config_setting, else use //visibility:public. */
    DEFAULT_PUBLIC,
    /** Enforce config_setting visibility exactly the same as all other rules. */
    DEFAULT_STANDARD
  }

  /**
   * The "workspace name" of packages generated by Bzlmod to contain repo rules.
   *
   * <p>Normally, packages containing repo rules are differentiated from packages containing build
   * rules by the {@link PackageIdentifier}: The singular repo-rule-containing package is {@code
   * //external}. However, in Bzlmod, packages containing repo rules need to have meaningful {@link
   * PackageIdentifier}s, so there needs to be some other way to distinguish them from
   * build-rule-containing packages. We use the following magic string as the "workspace name" for
   * repo-rule-containing packages generated by Bzlmod.
   *
   * @see #isRepoRulePackage()
   */
  private static final String DUMMY_WORKSPACE_NAME_FOR_BZLMOD_PACKAGES = "__dummy_workspace_bzlmod";

  /** Sentinel value for package overhead being empty. */
  private static final long PACKAGE_OVERHEAD_UNSET = -1;

  // ==== General package metadata fields ====

  private final Metadata metadata;

  private final Optional<Root> sourceRoot;

  // For BUILD files, this is initialized immediately. For WORKSPACE files, it is known only after
  // Starlark evaluation of the WORKSPACE file has finished.
  private String workspaceName;

  // Can be changed during BUILD file evaluation due to exports_files() modifying its visibility.
  private InputFile buildFile;

  // Mutated during BUILD file evaluation (but not by symbolic macro evaluation).
  private PackageArgs packageArgs = PackageArgs.DEFAULT;

  // Mutated during BUILD file evaluation (but not by symbolic macro evaluation).
  private ImmutableMap<String, String> makeEnv;

  // These two fields are mutually exclusive. Which one is set depends on
  // PackageSettings#precomputeTransitiveLoads. See Package.Builder#setLoads.
  @Nullable private ImmutableList<Module> directLoads;
  @Nullable private ImmutableList<Label> transitiveLoads;

  /**
   * True iff this package's BUILD files contained lexical or grammatical errors, or experienced
   * errors during evaluation, or semantic errors during the construction of any rule.
   *
   * <p>Note: A package containing errors does not necessarily prevent a build; if all the rules
   * needed for a given build were constructed prior to the first error, the build may proceed.
   */
  private boolean containsErrors;

  /**
   * The first detailed error encountered during this package's construction and evaluation, or
   * {@code null} if there were no such errors or all its errors lacked details.
   */
  @Nullable private FailureDetail failureDetail;

  private long computationSteps;

  /**
   * A rough approximation of the memory and general accounting costs associated with a loaded
   * package. A value of -1 means it is unset. Stored as a long to take up less memory per pkg.
   */
  private long packageOverhead = PACKAGE_OVERHEAD_UNSET;

  // ==== Fields specific to external package / WORKSPACE logic ====

  /**
   * The map from each repository to that repository's remappings map. This is only used in the
   * //external package, it is an empty map for all other packages. For example, an entry of {"@foo"
   * : {"@x", "@y"}} indicates that, within repository foo, "@x" should be remapped to "@y".
   */
  private ImmutableMap<RepositoryName, ImmutableMap<String, RepositoryName>>
      externalPackageRepositoryMappings;

  private ImmutableList<TargetPattern> registeredExecutionPlatforms;
  private ImmutableList<TargetPattern> registeredToolchains;
  private OptionalInt firstWorkspaceSuffixRegisteredToolchain;

  // ==== Target and macro fields ====

  /** The collection of all targets defined in this package, indexed by name. */
  // TODO(bazel-team): Clarify what this map contains when a rule and its output both share the same
  // name.
  private ImmutableSortedMap<String, Target> targets;

  /**
   * The collection of all symbolic macro instances defined in this package, indexed by their {@link
   * MacroInstance#getId id} (not name).
   */
  // TODO(#19922): Consider enforcing that macro namespaces are "exclusive", meaning that target
  // names may only suffix a macro name when the target is created (transitively) within the macro.
  // This would be a major change that would break the (common) use case where a BUILD file
  // declares both "foo" and "foo_test".
  private ImmutableSortedMap<String, MacroInstance> macros;

  /**
   * A map from names of targets declared in a symbolic macro which violate macro naming rules, such
   * as "lib%{name}-src.jar" implicit outputs in java rules, to the name of the macro instance where
   * they were declared.
   *
   * <p>Initialized by the builder in {@link #finishInit}.
   */
  @Nullable private ImmutableMap<String, String> macroNamespaceViolatingTargets;

  /**
   * A map from names of targets declared in a symbolic macro to the (innermost) macro instance
   * where they were declared. Omits targets not declared in symbolic macros.
   *
   * <p>Null for packages produced by deserialization.
   */
  // TODO: #19922 - If this field were made serializable (currently it's not), it would subsume
  // macroNamespaceViolatingTargets, since we can just map the target to its macro and then check
  // whether it is in the macro's namespace.
  //
  // TODO: #19922 - Don't maintain this extra map of all macro-instantiated targets. We have a
  // couple options:
  //   1) Have Target store a reference to its declaring MacroInstance directly. To avoid adding a
  //      field to that class (a not insignificant cost), we can merge it with the reference to its
  //      package: If we're not in a macro, we point to the package, and if we are, we point to the
  //      innermost macro, and hop to the MacroInstance to get a reference to the Package (or parent
  //      macro).
  //   2) To support lazy macro evaluation, we'll probably need a prefix trie in Package to find the
  //      macros whose namespaces contain the requested target name. For targets that respect their
  //      macro's namespace, we could just look them up in the trie. This assumes we already know
  //      whether the target is well-named, which we wouldn't if we got rid of
  //      macroNamespaceViolatingTargets.
  @Nullable private ImmutableMap<String, MacroInstance> targetsToDeclaringMacro;

  /**
   * A map from names of targets declared in a symbolic macro to the package where the macro that
   * declared it was defined, as per {@link MacroInstance#getDefinitionPackage}. Omits targets not
   * declared in symbolic macros.
   *
   * <p>Null for packages not produced by deserialization.
   */
  @Nullable private ImmutableMap<String, PackageIdentifier> targetsToDeclaringPackage;

  // ==== Constructor ====

  /**
   * Constructs a new (incomplete) Package instance. Intended only for use by {@link
   * Package.Builder}.
   *
   * <p>Packages and Targets refer to one another. Therefore, the builder needs to have a Package
   * instance on-hand before it can associate any targets with the package. Certain Metadata fields
   * like the package's name must be known before that point, while other fields are filled in when
   * the builder calls {@link Package#finishInit}.
   */
  // TODO(#19922): Better separate fields that must be known a priori from those determined through
  // BUILD evaluation.
  private Package(Metadata metadata) {
    this.metadata = metadata;
    this.sourceRoot = computeSourceRoot(metadata);
  }

  // ==== General package metadata accessors ====

  public Metadata getMetadata() {
    return metadata;
  }

  /**
   * Returns this package's identifier.
   *
   * <p>This is a suffix of {@code getFilename().getParentDirectory()}.
   */
  public PackageIdentifier getPackageIdentifier() {
    return metadata.packageIdentifier();
  }

  /**
   * Returns the name of this package. If this build is using external repositories then this name
   * may not be unique!
   */
  public String getName() {
    return metadata.getName();
  }

  /** Like {@link #getName}, but has type {@code PathFragment}. */
  public PathFragment getNameFragment() {
    return getPackageIdentifier().getPackageFragment();
  }

  /**
   * Returns the filename of the BUILD file which defines this package. The parent directory of the
   * BUILD file is the package directory.
   */
  public RootedPath getFilename() {
    return metadata.buildFilename();
  }

  /** Returns the directory containing the package's BUILD file. */
  public Path getPackageDirectory() {
    return metadata.getPackageDirectory();
  }

  /**
   * Whether this package should contain only repo rules (returns {@code true}) or only build rules
   * (returns {@code false}).
   */
  private boolean isRepoRulePackage() {
    return metadata.isRepoRulePackage;
  }

  /**
   * Returns the map of repository reassignments for BUILD packages. This will be empty for packages
   * within the main workspace.
   */
  public RepositoryMapping getRepositoryMapping() {
    return metadata.repositoryMapping();
  }

  /**
   * How to enforce visibility on <code>config_setting</code> See {@link
   * ConfigSettingVisibilityPolicy} for details.
   *
   * <p>Null for repo rule packages.
   */
  @Nullable
  public ConfigSettingVisibilityPolicy getConfigSettingVisibilityPolicy() {
    return metadata.configSettingVisibilityPolicy();
  }

  /**
   * Returns the name of the workspace this package is in. Used as a prefix for the runfiles
   * directory. This can be set in the WORKSPACE file. This must be a valid target name.
   */
  public String getWorkspaceName() {
    return workspaceName;
  }

  /** Returns the InputFile target for this package's BUILD file. */
  public InputFile getBuildFile() {
    return buildFile;
  }

  /**
   * Returns the label of this package's BUILD file.
   *
   * <p>Typically <code>getBuildFileLabel().getName().equals("BUILD")</code> -- though not
   * necessarily: data in a subdirectory of a test package may use a different filename to avoid
   * inadvertently creating a new package.
   */
  public Label getBuildFileLabel() {
    return buildFile.getLabel();
  }

  /**
   * Returns the collection of package-level attributes set by the {@code package()} callable and
   * similar methods.
   */
  public PackageArgs getPackageArgs() {
    return packageArgs;
  }

  /**
   * Returns the "Make" environment of this package, containing package-local definitions of "Make"
   * variables.
   */
  public ImmutableMap<String, String> getMakeEnvironment() {
    return makeEnv;
  }

  /**
   * Returns the root of the source tree beneath which this package's BUILD file was found, or
   * {@link Optional#empty} if this package was derived from a WORKSPACE file.
   *
   * <p>Assumes invariant: If non-empty, {@code
   * getSourceRoot().get().getRelative(packageId.getSourceRoot()).equals(getPackageDirectory())}
   */
  public Optional<Root> getSourceRoot() {
    return sourceRoot;
  }

  /**
   * Returns a list of Starlark files transitively loaded by this package.
   *
   * <p>If transitive loads are not {@linkplain PackageSettings#precomputeTransitiveLoads
   * precomputed}, performs a traversal over the load graph to compute them.
   *
   * <p>If only the count of transitively loaded files is needed, use {@link
   * #countTransitivelyLoadedStarlarkFiles}. For a customized online visitation, use {@link
   * #visitLoadGraph}.
   */
  public ImmutableList<Label> getOrComputeTransitivelyLoadedStarlarkFiles() {
    return transitiveLoads != null ? transitiveLoads : computeTransitiveLoads(directLoads);
  }

  /**
   * Counts the number Starlark files transitively loaded by this package.
   *
   * <p>If transitive loads are not {@linkplain PackageSettings#precomputeTransitiveLoads
   * precomputed}, performs a traversal over the load graph to count them.
   */
  public int countTransitivelyLoadedStarlarkFiles() {
    if (transitiveLoads != null) {
      return transitiveLoads.size();
    }
    Set<Label> loads = new HashSet<>();
    visitLoadGraph(loads::add);
    return loads.size();
  }

  /**
   * Performs an online visitation of the load graph rooted at this package.
   *
   * <p>If transitive loads were {@linkplain PackageSettings#precomputeTransitiveLoads precomputed},
   * each file is passed to {@link LoadGraphVisitor#visit} once regardless of its return value.
   */
  public <E1 extends Exception, E2 extends Exception> void visitLoadGraph(
      LoadGraphVisitor<E1, E2> visitor) throws E1, E2 {
    if (transitiveLoads != null) {
      for (Label load : transitiveLoads) {
        visitor.visit(load);
      }
    } else {
      BazelModuleContext.visitLoadGraphRecursively(directLoads, visitor);
    }
  }

  private static ImmutableList<Label> computeTransitiveLoads(Iterable<Module> directLoads) {
    Set<Label> loads = new LinkedHashSet<>();
    BazelModuleContext.visitLoadGraphRecursively(directLoads, loads::add);
    return ImmutableList.copyOf(loads);
  }

  /**
   * Returns true if errors were encountered during evaluation of this package. (The package may be
   * incomplete and its contents should not be relied upon for critical operations. However, any
   * Rules belonging to the package are guaranteed to be intact, unless their <code>containsErrors()
   * </code> flag is set.)
   */
  public boolean containsErrors() {
    return containsErrors;
  }

  /**
   * Returns the first {@link FailureDetail} describing one of the package's errors, or {@code null}
   * if it has no errors or all its errors lack details.
   */
  @Nullable
  public FailureDetail getFailureDetail() {
    return failureDetail;
  }

  /** Returns the number of Starlark computation steps executed by this BUILD file. */
  public long getComputationSteps() {
    return computationSteps;
  }

  /** Returns package overhead as configured by the configured {@link PackageOverheadEstimator}. */
  public OptionalLong getPackageOverhead() {
    return packageOverhead == PACKAGE_OVERHEAD_UNSET
        ? OptionalLong.empty()
        : OptionalLong.of(packageOverhead);
  }

  // ==== Accessors specific to external package / WORKSPACE logic ====

  /**
   * Returns the repository mapping for the requested external repository.
   *
   * @throws UnsupportedOperationException if called from a package other than the //external
   *     package
   */
  public ImmutableMap<String, RepositoryName> getExternalPackageRepositoryMapping(
      RepositoryName repository) {
    if (!isRepoRulePackage()) {
      throw new UnsupportedOperationException(
          "Can only access the external package repository"
              + "mappings from the //external package");
    }
    return externalPackageRepositoryMappings.getOrDefault(repository, ImmutableMap.of());
  }

  /**
   * Returns the full map of repository mappings collected so far.
   *
   * @throws UnsupportedOperationException if called from a package other than the //external
   *     package
   */
  ImmutableMap<RepositoryName, ImmutableMap<String, RepositoryName>>
      getExternalPackageRepositoryMappings() {
    if (!isRepoRulePackage()) {
      throw new UnsupportedOperationException(
          "Can only access the external package repository"
              + "mappings from the //external package");
    }
    return this.externalPackageRepositoryMappings;
  }

  public ImmutableList<TargetPattern> getRegisteredExecutionPlatforms() {
    return registeredExecutionPlatforms;
  }

  public ImmutableList<TargetPattern> getRegisteredToolchains() {
    return registeredToolchains;
  }

  public ImmutableList<TargetPattern> getUserRegisteredToolchains() {
    return getRegisteredToolchains()
        .subList(
            0, firstWorkspaceSuffixRegisteredToolchain.orElse(getRegisteredToolchains().size()));
  }

  public ImmutableList<TargetPattern> getWorkspaceSuffixRegisteredToolchains() {
    return getRegisteredToolchains()
        .subList(
            firstWorkspaceSuffixRegisteredToolchain.orElse(getRegisteredToolchains().size()),
            getRegisteredToolchains().size());
  }

  OptionalInt getFirstWorkspaceSuffixRegisteredToolchain() {
    return firstWorkspaceSuffixRegisteredToolchain;
  }

  // ==== Target and macro accessors ====

  /** Returns an (immutable, ordered) view of all the targets belonging to this package. */
  public ImmutableSortedMap<String, Target> getTargets() {
    return targets;
  }

  /**
   * Returns a (read-only, ordered) iterable of all the targets belonging to this package which are
   * instances of the specified class.
   */
  public <T extends Target> Iterable<T> getTargets(Class<T> targetClass) {
    return Iterables.filter(targets.values(), targetClass);
  }

  /**
   * Returns the rule that corresponds to a particular BUILD target name. Useful for walking through
   * the dependency graph of a target. Fails if the target is not a Rule.
   */
  public Rule getRule(String targetName) {
    return (Rule) targets.get(targetName);
  }

  /**
   * Returns a map from names of targets declared in a symbolic macro which violate macro naming
   * rules, such as "lib%{name}-src.jar" implicit outputs in java rules, to the name of the macro
   * instance where they were declared.
   */
  ImmutableMap<String, String> getMacroNamespaceViolatingTargets() {
    Preconditions.checkNotNull(
        macroNamespaceViolatingTargets,
        "This method is only available after the package has been loaded.");
    return macroNamespaceViolatingTargets;
  }

  /**
   * Returns a map from names of targets declared in a symbolic macro to the package containing said
   * macro's .bzl code.
   */
  ImmutableMap<String, PackageIdentifier> getTargetsToDeclaringPackage() {
    if (targetsToDeclaringPackage != null) {
      return targetsToDeclaringPackage;
    } else {
      ImmutableMap.Builder<String, PackageIdentifier> result = ImmutableMap.builder();
      for (Map.Entry<String, MacroInstance> entry : targetsToDeclaringMacro.entrySet()) {
        result.put(entry.getKey(), entry.getValue().getDefinitionPackage());
      }
      return result.buildOrThrow();
    }
  }

  /**
   * Throws {@link MacroNamespaceViolationException} if the given target (which must be a member of
   * this package) violates macro naming rules.
   */
  public void checkMacroNamespaceCompliance(Target target) throws MacroNamespaceViolationException {
    Preconditions.checkArgument(
        this.equals(target.getPackage()), "Target must belong to this package");
    @Nullable
    String macroNamespaceViolated = getMacroNamespaceViolatingTargets().get(target.getName());
    if (macroNamespaceViolated != null) {
      throw new MacroNamespaceViolationException(
          String.format(
              "Target %s declared in symbolic macro '%s' violates macro naming rules and cannot be"
                  + " built. %s",
              target.getLabel(), macroNamespaceViolated, TargetRecorder.MACRO_NAMING_RULES));
    }
  }

  /**
   * Returns the target (a member of this package) whose name is "targetName". First rules are
   * searched, then output files, then input files. The target name must be valid, as defined by
   * {@code LabelValidator#validateTargetName}.
   *
   * @throws NoSuchTargetException if the specified target was not found.
   */
  public Target getTarget(String targetName) throws NoSuchTargetException {
    Target target = targets.get(targetName);
    if (target != null) {
      return target;
    }

    Label label;
    try {
      label = Label.create(metadata.packageIdentifier(), targetName);
    } catch (LabelSyntaxException e) {
      throw new IllegalArgumentException(targetName, e);
    }

    if (metadata.succinctTargetNotFoundErrors()) {
      throw new NoSuchTargetException(
          label, String.format("target '%s' not declared in package '%s'", targetName, getName()));
    } else {
      String alternateTargetSuggestion = getAlternateTargetSuggestion(targetName);
      throw new NoSuchTargetException(
          label,
          String.format(
              "target '%s' not declared in package '%s' defined by %s%s",
              targetName,
              getName(),
              metadata.buildFilename().asPath().getPathString(),
              alternateTargetSuggestion));
    }
  }

  private String getAlternateTargetSuggestion(String targetName) {
    // If there's a file on the disk that's not mentioned in the BUILD file,
    // produce a more informative error.  NOTE! this code path is only executed
    // on failure, which is (relatively) very rare.  In the common case no
    // stat(2) is executed.
    Path filename = metadata.getPackageDirectory().getRelative(targetName);
    if (!PathFragment.isNormalized(targetName) || "*".equals(targetName)) {
      // Don't check for file existence if the target name is not normalized
      // because the error message would be confusing and wrong. If the
      // targetName is "foo/bar/.", and there is a directory "foo/bar", it
      // doesn't mean that "//pkg:foo/bar/." is a valid label.
      // Also don't check if the target name is a single * character since
      // it's invalid on Windows.
      return "";
    } else if (filename.isDirectory()) {
      return "; however, a source directory of this name exists.  (Perhaps add "
          + "'exports_files([\""
          + targetName
          + "\"])' to "
          + getName()
          + "/BUILD, or define a "
          + "filegroup?)";
    } else if (filename.exists()) {
      return "; however, a source file of this name exists.  (Perhaps add "
          + "'exports_files([\""
          + targetName
          + "\"])' to "
          + getName()
          + "/BUILD?)";
    } else {
      return TargetSuggester.suggestTargets(targetName, targets.keySet());
    }
  }

  /**
   * Returns all symbolic macros defined in the package, indexed by {@link MacroInstance#getId id}.
   *
   * <p>Note that {@code MacroInstance}s hold just the information known at the time a macro was
   * declared, even though by the time the {@code Package} is fully constructed we already have
   * fully evaluated these macros.
   */
  public ImmutableMap<String, MacroInstance> getMacrosById() {
    return macros;
  }

  /**
   * Returns the (innermost) symbolic macro instance that declared the given target, or null if the
   * target was not created in a symbolic macro.
   *
   * <p>Throws {@link IllegalArgumentException} if the given name is not a target in this package.
   *
   * <p>For packages produced by deserialization, this information is not available and {@code
   * IllegalStateException} is thrown.
   */
  @Nullable
  public MacroInstance getDeclaringMacroForTarget(String target) {
    Preconditions.checkState(
        targetsToDeclaringMacro != null,
        "Cannot retrieve MacroInstance information from deserialized packages");
    Preconditions.checkArgument(targets.containsKey(target), "unknown target '%s'", target);
    return targetsToDeclaringMacro.get(target);
  }

  /**
   * Returns the id of the package where the (innermost) macro that declared the given target was
   * defined (as per {@link MacroInstance#getDefinitionLocation}), or null if the target was not
   * created in a symbolic macro.
   *
   * <p>The caller should interpret a null result to mean that the declaration location of the
   * target is this package.
   *
   * <p>Throws {@link IllegalArgumentException} if the given name is not a target in this package.
   */
  @Nullable
  public PackageIdentifier getDeclaringPackageForTargetIfInMacro(String target) {
    Preconditions.checkArgument(targets.containsKey(target), "unknown target '%s'", target);
    // Exactly one of targetsToDeclaringMacro and targetsToDeclaringPackage is non-null, depending
    // on whether this package was produced by deserialization.
    if (targetsToDeclaringMacro != null) {
      MacroInstance macro = targetsToDeclaringMacro.get(target);
      return macro != null ? macro.getDefinitionPackage() : null;
    } else {
      return targetsToDeclaringPackage.get(target);
    }
  }

  // ==== Initialization ====

  private static Optional<Root> computeSourceRoot(Metadata metadata) {
    if (metadata.isRepoRulePackage()) {
      return Optional.empty();
    }

    RootedPath buildFileRootedPath = metadata.buildFilename();
    Root buildFileRoot = buildFileRootedPath.getRoot();
    PathFragment pkgIdFragment = metadata.packageIdentifier().getSourceRoot();
    PathFragment pkgDirFragment = buildFileRootedPath.getRootRelativePath().getParentDirectory();

    Root sourceRoot;
    if (pkgIdFragment.equals(pkgDirFragment)) {
      // Fast path: BUILD file path and package name are the same, don't create an extra root.
      sourceRoot = buildFileRoot;
    } else {
      // TODO(bazel-team): Can this expr be simplified to just pkgDirFragment?
      PathFragment current = buildFileRootedPath.asPath().asFragment().getParentDirectory();
      for (int i = 0, len = pkgIdFragment.segmentCount(); i < len && current != null; i++) {
        current = current.getParentDirectory();
      }
      if (current == null || current.isEmpty()) {
        // This is never really expected to work. The below check should fail.
        sourceRoot = buildFileRoot;
      } else {
        // Note that current is an absolute path.
        sourceRoot = Root.fromPath(buildFileRoot.getRelative(current));
      }
    }

    Preconditions.checkArgument(
        sourceRoot.asPath() != null
            && sourceRoot.getRelative(pkgIdFragment).equals(metadata.getPackageDirectory()),
        "Invalid BUILD file name for package '%s': %s (in source %s with packageDirectory %s and"
            + " package identifier source root %s)",
        metadata.packageIdentifier(),
        metadata.buildFilename(),
        sourceRoot,
        metadata.getPackageDirectory(),
        metadata.packageIdentifier().getSourceRoot());

    return Optional.of(sourceRoot);
  }

  /**
   * Completes the initialization of this package. Only after this method may a package by shared
   * publicly.
   */
  private void finishInit(Builder builder) {
    this.containsErrors |= builder.containsErrors();
    if (directLoads == null && transitiveLoads == null) {
      Preconditions.checkState(containsErrors, "Loads not set for error-free package");
      builder.setLoads(ImmutableList.of());
    }

    this.workspaceName = builder.workspaceName;

    this.makeEnv = ImmutableMap.copyOf(builder.makeEnv);
    this.targets = ImmutableSortedMap.copyOf(builder.recorder.getTargetMap());
    this.macros = ImmutableSortedMap.copyOf(builder.recorder.getMacroMap());
    this.macroNamespaceViolatingTargets =
        ImmutableMap.copyOf(builder.recorder.getMacroNamespaceViolatingTargets());
    this.targetsToDeclaringMacro =
        builder.recorder.getTargetsToDeclaringMacro() != null
            ? ImmutableSortedMap.copyOf(builder.recorder.getTargetsToDeclaringMacro())
            : null;
    this.targetsToDeclaringPackage =
        builder.recorder.getTargetsToDeclaringPackage() != null
            ? ImmutableSortedMap.copyOf(builder.recorder.getTargetsToDeclaringPackage())
            : null;
    this.failureDetail = builder.getFailureDetail();
    this.registeredExecutionPlatforms = ImmutableList.copyOf(builder.registeredExecutionPlatforms);
    this.registeredToolchains = ImmutableList.copyOf(builder.registeredToolchains);
    this.firstWorkspaceSuffixRegisteredToolchain = builder.firstWorkspaceSuffixRegisteredToolchain;
    ImmutableMap.Builder<RepositoryName, ImmutableMap<String, RepositoryName>>
        repositoryMappingsBuilder = ImmutableMap.builder();
    if (!builder.externalPackageRepositoryMappings.isEmpty() && !builder.isRepoRulePackage()) {
      // 'repo_mapping' should only be used in the //external package, i.e. should only appear
      // in WORKSPACE files. Currently, if someone tries to use 'repo_mapping' in a BUILD rule, they
      // will get a "no such attribute" error. This check is to protect against a 'repo_mapping'
      // attribute being added to a rule in the future.
      throw new IllegalArgumentException(
          "'repo_mapping' may only be used in the //external package");
    }
    builder.externalPackageRepositoryMappings.forEach(
        (k, v) -> repositoryMappingsBuilder.put(k, ImmutableMap.copyOf(v)));
    this.externalPackageRepositoryMappings = repositoryMappingsBuilder.buildOrThrow();
    OptionalLong overheadEstimate = builder.packageOverheadEstimator.estimatePackageOverhead(this);
    this.packageOverhead = overheadEstimate.orElse(PACKAGE_OVERHEAD_UNSET);
  }

  // TODO(bazel-team): This is a mutation, but Package is supposed to be immutable. In practice it
  // seems like this is only called during Package construction (including deserialization),
  // principally via Rule#reportError. I would bet that all of the callers have access to a
  // Package.Builder, in which case they should report the bit using the builder instead. But maybe
  // it's easier to just checkState() that the Package hasn't already finished constructing.
  void setContainsErrors() {
    containsErrors = true;
  }

  // ==== Stringification / debugging ====

  @Override
  public String toString() {
    return "Package("
        + getName()
        + ")="
        + (targets != null ? getTargets(Rule.class) : "initializing...");
  }

  /**
   * Dumps the package for debugging. Do not depend on the exact format/contents of this debugging
   * output.
   */
  public void dump(PrintStream out) {
    out.println("  Package " + getName() + " (" + metadata.buildFilename().asPath() + ")");

    // Rules:
    out.println("    Rules");
    for (Rule rule : getTargets(Rule.class)) {
      out.println("      " + rule.getTargetKind() + " " + rule.getLabel());
      for (Attribute attr : rule.getAttributes()) {
        for (Object possibleValue :
            AggregatingAttributeMapper.of(rule).visitAttribute(attr.getName(), attr.getType())) {
          out.println("        " + attr.getName() + " = " + possibleValue);
        }
      }
    }

    // Files:
    out.println("    Files");
    for (FileTarget file : getTargets(FileTarget.class)) {
      out.print("      " + file.getTargetKind() + " " + file.getLabel());
      if (file instanceof OutputFile) {
        out.println(" (generated by " + ((OutputFile) file).getGeneratingRule().getLabel() + ")");
      } else {
        out.println();
      }
    }
  }

  // ==== Error reporting ====

  /**
   * Returns an error {@link Event} with {@link Location} and {@link DetailedExitCode} properties.
   */
  public static Event error(Location location, String message, Code code) {
    return errorWithDetailedExitCode(
        location,
        message,
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setPackageLoading(PackageLoading.newBuilder().setCode(code))
                .build()));
  }

  /** Similar to {@link #error} but with a custom {@link DetailedExitCode}. */
  public static Event errorWithDetailedExitCode(
      Location location, String message, DetailedExitCode detailedExitCode) {
    Event error = Event.error(location, message);
    return error.withProperty(DetailedExitCode.class, detailedExitCode);
  }

  /**
   * If {@code pkg.containsErrors()}, sends an errorful "package contains errors" {@link Event}
   * (augmented with {@code pkg.getFailureDetail()}, if present) to the given {@link EventHandler}.
   */
  public static void maybeAddPackageContainsErrorsEventToHandler(
      Package pkg, EventHandler eventHandler) {
    if (pkg.containsErrors()) {
      eventHandler.handle(
          Event.error(
              String.format(
                  "package contains errors: %s%s",
                  pkg.getNameFragment(),
                  pkg.getFailureDetail() != null
                      ? ": " + pkg.getFailureDetail().getMessage()
                      : "")));
    }
  }

  /**
   * Given a {@link FailureDetail} and target, returns a modified {@code FailureDetail} that
   * attributes its error to the target.
   *
   * <p>If the given detail is null, then a generic {@link Code#TARGET_MISSING} detail identifying
   * the target is returned.
   */
  public static FailureDetail contextualizeFailureDetailForTarget(
      @Nullable FailureDetail failureDetail, Target target) {
    String prefix =
        "Target '" + target.getLabel() + "' contains an error and its package is in error";
    if (failureDetail == null) {
      return FailureDetail.newBuilder()
          .setMessage(prefix)
          .setPackageLoading(PackageLoading.newBuilder().setCode(Code.TARGET_MISSING))
          .build();
    }
    return failureDetail.toBuilder().setMessage(prefix + ": " + failureDetail.getMessage()).build();
  }

  // ==== Builders ====

  /**
   * Returns a new {@link Builder} suitable for constructing an ordinary package (i.e. not one for
   * WORKSPACE or bzlmod).
   */
  public static Builder newPackageBuilder(
      PackageSettings packageSettings,
      PackageIdentifier id,
      RootedPath filename,
      String workspaceName,
      Optional<String> associatedModuleName,
      Optional<String> associatedModuleVersion,
      boolean noImplicitFileExport,
      boolean simplifyUnconditionalSelectsInRuleAttrs,
      RepositoryMapping repositoryMapping,
      RepositoryMapping mainRepositoryMapping,
      @Nullable Semaphore cpuBoundSemaphore,
      PackageOverheadEstimator packageOverheadEstimator,
      @Nullable ImmutableMap<Location, String> generatorMap,
      // TODO(bazel-team): See Builder() constructor comment about use of null for this param.
      @Nullable ConfigSettingVisibilityPolicy configSettingVisibilityPolicy,
      @Nullable Globber globber,
      boolean enableNameConflictChecking,
      boolean trackFullMacroInformation) {
    // Determine whether this is for a repo rule package. We shouldn't actually have to do this
    // because newPackageBuilder() is supposed to only be called for normal packages. Unfortunately
    // serialization still uses the same code path for deserializing BUILD and WORKSPACE files,
    // violating this method's contract.
    boolean isRepoRulePackage =
        id.equals(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)
            // For bzlmod packages, setWorkspaceName() is not called, so this expression doesn't
            // change during package evaluation.
            || workspaceName.equals(DUMMY_WORKSPACE_NAME_FOR_BZLMOD_PACKAGES);

    return new Builder(
        new Metadata(
            /* packageIdentifier= */ id,
            /* buildFilename= */ filename,
            /* isRepoRulePackage= */ isRepoRulePackage,
            /* repositoryMapping= */ repositoryMapping,
            /* associatedModuleName= */ associatedModuleName,
            /* associatedModuleVersion= */ associatedModuleVersion,
            /* configSettingVisibilityPolicy= */ configSettingVisibilityPolicy,
            /* succinctTargetNotFoundErrors= */ packageSettings.succinctTargetNotFoundErrors()),
        SymbolGenerator.create(id),
        packageSettings.precomputeTransitiveLoads(),
        noImplicitFileExport,
        simplifyUnconditionalSelectsInRuleAttrs,
        workspaceName,
        mainRepositoryMapping,
        cpuBoundSemaphore,
        packageOverheadEstimator,
        generatorMap,
        globber,
        enableNameConflictChecking,
        trackFullMacroInformation);
  }

  public static Builder newExternalPackageBuilder(
      PackageSettings packageSettings,
      WorkspaceFileKey workspaceFileKey,
      String workspaceName,
      RepositoryMapping mainRepoMapping,
      boolean noImplicitFileExport,
      boolean simplifyUnconditionalSelectsInRuleAttrs,
      PackageOverheadEstimator packageOverheadEstimator) {
    return new Builder(
        new Metadata(
            /* packageIdentifier= */ LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER,
            /* buildFilename= */ workspaceFileKey.getPath(),
            /* isRepoRulePackage= */ true,
            /* repositoryMapping= */ mainRepoMapping,
            /* associatedModuleName= */ Optional.empty(),
            /* associatedModuleVersion= */ Optional.empty(),
            /* configSettingVisibilityPolicy= */ null,
            /* succinctTargetNotFoundErrors= */ packageSettings.succinctTargetNotFoundErrors()),
        // The SymbolGenerator is based on workspaceFileKey rather than a package id or path,
        // in order to distinguish different chunks of the same WORKSPACE file.
        SymbolGenerator.create(workspaceFileKey),
        packageSettings.precomputeTransitiveLoads(),
        noImplicitFileExport,
        simplifyUnconditionalSelectsInRuleAttrs,
        workspaceName,
        mainRepoMapping,
        /* cpuBoundSemaphore= */ null,
        packageOverheadEstimator,
        /* generatorMap= */ null,
        /* globber= */ null,
        /* enableNameConflictChecking= */ true,
        /* trackFullMacroInformation= */ true);
  }

  public static Builder newExternalPackageBuilderForBzlmod(
      RootedPath moduleFilePath,
      boolean noImplicitFileExport,
      boolean simplifyUnconditionalSelectsInRuleAttrs,
      PackageIdentifier basePackageId,
      RepositoryMapping repoMapping) {
    return new Builder(
            new Metadata(
                /* packageIdentifier= */ basePackageId,
                /* buildFilename= */ moduleFilePath,
                /* isRepoRulePackage= */ true,
                /* repositoryMapping= */ repoMapping,
                /* associatedModuleName= */ Optional.empty(),
                /* associatedModuleVersion= */ Optional.empty(),
                /* configSettingVisibilityPolicy= */ null,
                /* succinctTargetNotFoundErrors= */ PackageSettings.DEFAULTS
                    .succinctTargetNotFoundErrors()),
            SymbolGenerator.create(basePackageId),
            PackageSettings.DEFAULTS.precomputeTransitiveLoads(),
            noImplicitFileExport,
            simplifyUnconditionalSelectsInRuleAttrs,
            /* workspaceName= */ DUMMY_WORKSPACE_NAME_FOR_BZLMOD_PACKAGES,
            /* mainRepositoryMapping= */ null,
            /* cpuBoundSemaphore= */ null,
            PackageOverheadEstimator.NOOP_ESTIMATOR,
            /* generatorMap= */ null,
            /* globber= */ null,
            /* enableNameConflictChecking= */ true,
            /* trackFullMacroInformation= */ true)
        .setLoads(ImmutableList.of());
  }

  // ==== Non-trivial nested classes ====

  /**
   * A builder for {@link Package} objects. Only intended to be used by {@link PackageFactory} and
   * {@link com.google.devtools.build.lib.skyframe.PackageFunction}.
   */
  public static class Builder extends TargetDefinitionContext {

    /**
     * A bundle of options affecting package construction, that is not specific to any particular
     * package.
     */
    public interface PackageSettings {
      /**
       * Returns whether or not extra detail should be added to {@link NoSuchTargetException}s
       * thrown from {@link #getTarget}. Useful for toning down verbosity in situations where it can
       * be less helpful.
       */
      // TODO(bazel-team): Arguably, this could be replaced by a boolean param to getTarget(), or
      // some separate action taken by the caller. But there's a lot of call sites that would need
      // updating.
      default boolean succinctTargetNotFoundErrors() {
        return false;
      }

      /**
       * Determines whether to precompute a list of transitively loaded starlark files while
       * building packages.
       *
       * <p>Typically, direct loads are stored as a {@code ImmutableList<Module>}. This is
       * sufficient to reconstruct the full load graph by recursively traversing {@link
       * BazelModuleContext#loads}. If the package is going to be serialized, however, it may make
       * more sense to precompute a flat list containing the labels of all transitively loaded bzl
       * files since {@link Module} is costly to serialize.
       *
       * <p>If this returns {@code true}, transitive loads are stored as an {@code
       * ImmutableList<Label>} and direct loads are not stored.
       */
      default boolean precomputeTransitiveLoads() {
        return false;
      }

      PackageSettings DEFAULTS = new PackageSettings() {};
    }

    private final boolean precomputeTransitiveLoads;
    private final boolean noImplicitFileExport;

    // The map from each repository to that repository's remappings map.
    // This is only used in the //external package, it is an empty map for all other packages.
    private final HashMap<RepositoryName, LinkedHashMap<String, RepositoryName>>
        externalPackageRepositoryMappings = new HashMap<>();

    /** Estimates the package overhead of this package. */
    private final PackageOverheadEstimator packageOverheadEstimator;

    // A package's FailureDetail field derives from the events on its Builder's event handler.
    // During package deserialization, those events are unavailable, because those events aren't
    // serialized [*]. Its FailureDetail value is serialized, however. During deserialization, that
    // value is assigned here, so that it can be assigned to the deserialized package.
    //
    // Likewise, during workspace part assembly, errors from parent parts should propagate to their
    // children.
    //
    // [*] Not in the context of the package, anyway. Skyframe values containing a package may
    // serialize events emitted during its construction/evaluation.
    @Nullable private FailureDetail failureDetailOverride = null;

    // The snapshot of {@link #targets} for use in rule finalizer macros. Contains all
    // non-finalizer-instantiated rule targets (i.e. all rule targets except for those instantiated
    // in a finalizer or in a macro called from a finalizer).
    //
    // Initialized by expandAllRemainingMacros() and reset to null by beforeBuild().
    @Nullable private Map<String, Rule> rulesSnapshotViewForFinalizers;

    /**
     * Ids of all symbolic macros that have been declared but not yet evaluated.
     *
     * <p>These are listed in the order they were declared. (This probably doesn't matter, but let's
     * be protective against possible non-determinism.)
     *
     * <p>Generally, ordinary symbolic macros are evaluated eagerly and not added to this set, while
     * finalizers, as well as any macros called by finalizers, always use deferred evaluation and
     * end up in here.
     */
    private final Set<String> unexpandedMacros = new LinkedHashSet<>();

    private final List<TargetPattern> registeredExecutionPlatforms = new ArrayList<>();
    private final List<TargetPattern> registeredToolchains = new ArrayList<>();

    /**
     * Tracks the index within {@link #registeredToolchains} of the first toolchain registered from
     * the WORKSPACE suffixes rather than the WORKSPACE file (if any).
     *
     * <p>This is needed to distinguish between these toolchains during resolution: toolchains
     * registered in WORKSPACE have precedence over those defined in non-root Bazel modules, which
     * in turn have precedence over those from the WORKSPACE suffixes.
     */
    private OptionalInt firstWorkspaceSuffixRegisteredToolchain = OptionalInt.empty();

    private boolean alreadyBuilt = false;

    private Builder(
        Metadata metadata,
        SymbolGenerator<?> symbolGenerator,
        boolean precomputeTransitiveLoads,
        boolean noImplicitFileExport,
        boolean simplifyUnconditionalSelectsInRuleAttrs,
        String workspaceName,
        RepositoryMapping mainRepositoryMapping,
        @Nullable Semaphore cpuBoundSemaphore,
        PackageOverheadEstimator packageOverheadEstimator,
        @Nullable ImmutableMap<Location, String> generatorMap,
        @Nullable Globber globber,
        boolean enableNameConflictChecking,
        boolean trackFullMacroInformation) {
      super(
          metadata,
          new Package(metadata),
          symbolGenerator,
          simplifyUnconditionalSelectsInRuleAttrs,
          workspaceName,
          mainRepositoryMapping,
          cpuBoundSemaphore,
          generatorMap,
          globber,
          enableNameConflictChecking,
          trackFullMacroInformation);
      this.precomputeTransitiveLoads = precomputeTransitiveLoads;
      this.noImplicitFileExport = noImplicitFileExport;
      this.packageOverheadEstimator = packageOverheadEstimator;
    }

    /** Retrieves this object from a Starlark thread. Returns null if not present. */
    @Nullable
    public static Builder fromOrNull(StarlarkThread thread) {
      StarlarkThreadContext ctx = thread.getThreadLocal(StarlarkThreadContext.class);
      return (ctx instanceof Builder) ? (Builder) ctx : null;
    }

    /**
     * Retrieves this object from a Starlark thread. If not present, throws {@code EvalException}
     * with an error message indicating that {@code what} can't be used in this Starlark
     * environment.
     *
     * <p>If {@code allowBuild} is false, this method also throws if we're currently executing a
     * BUILD file (or legacy macro called from a BUILD file).
     *
     * <p>If {@code allowFinalizers} is false, this method also throws if we're currently executing
     * a rule finalizer implementation (or a legacy macro called from within such an
     * implementation).
     *
     * <p>If {@code allowNonFinalizerSymbolicMacros} is false, this method also throws if we're
     * currently executing the implementation of a symbolic macro implementation which is not a rule
     * finalizer (or a legacy macro called from within such an implementation).
     *
     * <p>If {@code allowWorkspace} is false, this method also throws if we're currently executing a
     * WORKSPACE file (or a legacy macro called from a WORKSPACE file).
     *
     * <p>It is not allowed for all three bool params to be false.
     */
    @CanIgnoreReturnValue
    public static Builder fromOrFail(
        StarlarkThread thread,
        String what,
        boolean allowBuild,
        boolean allowFinalizers,
        boolean allowNonFinalizerSymbolicMacros,
        boolean allowWorkspace)
        throws EvalException {
      Preconditions.checkArgument(
          allowBuild || allowFinalizers || allowNonFinalizerSymbolicMacros || allowWorkspace);

      @Nullable StarlarkThreadContext ctx = thread.getThreadLocal(StarlarkThreadContext.class);
      boolean bad = false;
      if (ctx instanceof Builder builder) {
        bad |= !allowBuild && !builder.isRepoRulePackage();
        bad |= !allowFinalizers && builder.recorder.currentlyInFinalizer();
        bad |= !allowNonFinalizerSymbolicMacros && builder.recorder.currentlyInNonFinalizerMacro();
        bad |= !allowWorkspace && builder.isRepoRulePackage();
        if (!bad) {
          return builder;
        }
      }

      boolean symbolicMacrosEnabled =
          thread
              .getSemantics()
              .getBool(BuildLanguageOptions.EXPERIMENTAL_ENABLE_FIRST_CLASS_MACROS);
      ArrayList<String> allowedUses = new ArrayList<>();
      if (allowBuild) {
        // Only disambiguate as "legacy" if the alternative, symbolic macros, are enabled.
        allowedUses.add(
            String.format("a BUILD file (or %smacro)", symbolicMacrosEnabled ? "legacy " : ""));
      }
      // Even if symbolic macros are allowed, don't mention them in the error message unless they
      // are enabled.
      if (symbolicMacrosEnabled) {
        if (allowFinalizers && allowNonFinalizerSymbolicMacros) {
          allowedUses.add("a symbolic macro");
        } else if (allowFinalizers) {
          allowedUses.add("a rule finalizer");
        } else if (allowNonFinalizerSymbolicMacros) {
          allowedUses.add("a non-finalizer symbolic macro");
        }
      }
      if (allowWorkspace) {
        allowedUses.add("a WORKSPACE file");
      }
      throw Starlark.errorf(
          "%s can only be used while evaluating %s", what, StringUtil.joinEnglishList(allowedUses));
    }

    /** Convenience method for {@link #fromOrFail} that permits any context with a Builder. */
    @CanIgnoreReturnValue
    public static Builder fromOrFail(StarlarkThread thread, String what) throws EvalException {
      return fromOrFail(
          thread,
          what,
          /* allowBuild= */ true,
          /* allowFinalizers= */ true,
          /* allowNonFinalizerSymbolicMacros= */ true,
          /* allowWorkspace= */ true);
    }

    /**
     * Convenience method for {@link #fromOrFail} that permits only BUILD contexts (without symbolic
     * macros).
     */
    @CanIgnoreReturnValue
    public static Builder fromOrFailAllowBuildOnly(StarlarkThread thread, String what)
        throws EvalException {
      return fromOrFail(
          thread,
          what,
          /* allowBuild= */ true,
          /* allowFinalizers= */ false,
          /* allowNonFinalizerSymbolicMacros= */ false,
          /* allowWorkspace= */ false);
    }

    /** Convenience method for {@link #fromOrFail} that permits only WORKSPACE contexts. */
    @CanIgnoreReturnValue
    public static Builder fromOrFailAllowWorkspaceOnly(StarlarkThread thread, String what)
        throws EvalException {
      return fromOrFail(
          thread,
          what,
          /* allowBuild= */ false,
          /* allowFinalizers= */ false,
          /* allowNonFinalizerSymbolicMacros= */ false,
          /* allowWorkspace= */ true);
    }

    /**
     * Convenience method for {@link #fromOrFail} that permits BUILD or WORKSPACE or rule finalizer
     * contexts.
     */
    @CanIgnoreReturnValue
    public static Builder fromOrFailDisallowNonFinalizerMacros(StarlarkThread thread, String what)
        throws EvalException {
      return fromOrFail(
          thread,
          what,
          /* allowBuild= */ true,
          /* allowFinalizers= */ true,
          /* allowNonFinalizerSymbolicMacros= */ false,
          /* allowWorkspace= */ true);
    }

    /**
     * Convenience method for {@link #fromOrFail} that permits BUILD or symbolic macro contexts
     * (including rule finalizers).
     */
    @CanIgnoreReturnValue
    public static Builder fromOrFailDisallowWorkspace(StarlarkThread thread, String what)
        throws EvalException {
      return fromOrFail(
          thread,
          what,
          /* allowBuild= */ true,
          /* allowFinalizers= */ true,
          /* allowNonFinalizerSymbolicMacros= */ true,
          /* allowWorkspace= */ false);
    }

    /**
     * Updates the externalPackageRepositoryMappings entry for {@code repoWithin}. Adds new entry
     * from {@code localName} to {@code mappedName} in {@code repoWithin}'s map.
     *
     * @param repoWithin the RepositoryName within which the mapping should apply
     * @param localName the name that actually appears in the WORKSPACE and BUILD files in the
     *     {@code repoWithin} repository
     * @param mappedName the RepositoryName by which localName should be referenced
     */
    @CanIgnoreReturnValue
    Builder addRepositoryMappingEntry(
        RepositoryName repoWithin, String localName, RepositoryName mappedName) {
      HashMap<String, RepositoryName> mapping =
          externalPackageRepositoryMappings.computeIfAbsent(
              repoWithin, (RepositoryName k) -> new LinkedHashMap<>());
      mapping.put(localName, mappedName);
      return this;
    }

    /** Adds all the mappings from a given {@link Package}. */
    @CanIgnoreReturnValue
    Builder addRepositoryMappings(Package aPackage) {
      ImmutableMap<RepositoryName, ImmutableMap<String, RepositoryName>> repositoryMappings =
          aPackage.externalPackageRepositoryMappings;
      for (Map.Entry<RepositoryName, ImmutableMap<String, RepositoryName>> repositoryName :
          repositoryMappings.entrySet()) {
        for (Map.Entry<String, RepositoryName> repositoryNameRepositoryNameEntry :
            repositoryName.getValue().entrySet()) {
          addRepositoryMappingEntry(
              repositoryName.getKey(),
              repositoryNameRepositoryNameEntry.getKey(),
              repositoryNameRepositoryNameEntry.getValue());
        }
      }
      return this;
    }

    /**
     * Return a read-only copy of the name mapping of external repositories for a given repository.
     * Reading that mapping directly from the builder allows to also take mappings into account that
     * are only discovered while constructing the external package (e.g., the mapping of the name of
     * the main workspace to the canonical main name '@').
     */
    RepositoryMapping getRepositoryMappingFor(RepositoryName name) {
      SequencedMap<String, RepositoryName> mapping = externalPackageRepositoryMappings.get(name);
      if (mapping == null) {
        return RepositoryMapping.ALWAYS_FALLBACK;
      } else {
        return RepositoryMapping.createAllowingFallback(ImmutableMap.copyOf(mapping));
      }
    }

    @Override
    public void mergePackageArgsFrom(PackageArgs packageArgs) {
      pkg.packageArgs = pkg.packageArgs.mergeWith(packageArgs);
    }

    @Override
    public void mergePackageArgsFrom(PackageArgs.Builder builder) {
      mergePackageArgsFrom(builder.build());
    }

    @Override
    public PackageArgs getPartialPackageArgs() {
      return pkg.packageArgs;
    }

    /** Sets the number of Starlark computation steps executed by this BUILD file. */
    void setComputationSteps(long n) {
      pkg.computationSteps = n;
    }

    void setFailureDetailOverride(FailureDetail failureDetail) {
      failureDetailOverride = failureDetail;
    }

    @Nullable
    FailureDetail getFailureDetail() {
      if (failureDetailOverride != null) {
        return failureDetailOverride;
      }

      List<Event> undetailedEvents = null;
      for (Event event : localEventHandler.getEvents()) {
        if (event.getKind() != EventKind.ERROR) {
          continue;
        }
        DetailedExitCode detailedExitCode = event.getProperty(DetailedExitCode.class);
        if (detailedExitCode != null && detailedExitCode.getFailureDetail() != null) {
          return detailedExitCode.getFailureDetail();
        }
        if (containsErrors()) {
          if (undetailedEvents == null) {
            undetailedEvents = new ArrayList<>();
          }
          undetailedEvents.add(event);
        }
      }
      if (undetailedEvents != null) {
        BugReport.sendNonFatalBugReport(
            new IllegalStateException("Package has undetailed error from " + undetailedEvents));
      }
      return null;
    }

    // TODO(#19922): Require this to be set before BUILD evaluation.
    @CanIgnoreReturnValue
    public Builder setLoads(Iterable<Module> directLoads) {
      checkLoadsNotSet();
      if (precomputeTransitiveLoads) {
        pkg.transitiveLoads = computeTransitiveLoads(directLoads);
      } else {
        pkg.directLoads = ImmutableList.copyOf(directLoads);
      }
      return this;
    }

    @CanIgnoreReturnValue
    Builder setTransitiveLoadsForDeserialization(ImmutableList<Label> transitiveLoads) {
      checkLoadsNotSet();
      pkg.transitiveLoads = Preconditions.checkNotNull(transitiveLoads);
      return this;
    }

    private void checkLoadsNotSet() {
      Preconditions.checkState(
          pkg.directLoads == null, "Direct loads already set: %s", pkg.directLoads);
      Preconditions.checkState(
          pkg.transitiveLoads == null, "Transitive loads already set: %s", pkg.transitiveLoads);
    }

    public Set<Target> getTargets() {
      return recorder.getTargets();
    }

    void replaceTarget(Target newTarget) {
      Preconditions.checkArgument(
          newTarget.getPackage() == pkg, // pointer comparison since we're constructing `pkg`
          "Replacement target belongs to package '%s', expected '%s'",
          newTarget.getPackage(),
          pkg);
      recorder.replaceTarget(newTarget);
    }

    @Override
    Map<String, Rule> getRulesSnapshotView() {
      if (rulesSnapshotViewForFinalizers != null) {
        return rulesSnapshotViewForFinalizers;
      } else {
        return super.getRulesSnapshotView();
      }
    }

    @Override
    @Nullable
    Rule getNonFinalizerInstantiatedRule(String name) {
      if (rulesSnapshotViewForFinalizers != null) {
        return rulesSnapshotViewForFinalizers.get(name);
      } else {
        return super.getNonFinalizerInstantiatedRule(name);
      }
    }

    public void addRuleUnchecked(Rule rule) {
      Preconditions.checkArgument(rule.getPackage() == pkg);
      recorder.addRuleUnchecked(rule);
    }

    @Override
    public void addMacro(MacroInstance macro) throws NameConflictException {
      super.addMacro(macro);
      unexpandedMacros.add(macro.getId());
    }

    // For Package deserialization.
    void putAllMacroNamespaceViolatingTargets(Map<String, String> macroNamespaceViolatingTargets) {
      recorder.putAllMacroNamespaceViolatingTargets(macroNamespaceViolatingTargets);
    }

    void putAllTargetsToDeclaringPackage(Map<String, PackageIdentifier> targetsToDeclaringPackage) {
      recorder.putAllTargetsToDeclaringPackage(targetsToDeclaringPackage);
    }

    /**
     * Marks a symbolic macro as having finished evaluating.
     *
     * <p>This will prevent the macro from being run by {@link #expandAllRemainingMacros}.
     *
     * <p>The macro must not have previously been marked complete.
     */
    public void markMacroComplete(MacroInstance macro) {
      String id = macro.getId();
      if (!unexpandedMacros.remove(id)) {
        throw new IllegalArgumentException(
            String.format("Macro id '%s' unknown or already marked complete", id));
      }
    }

    void addRegisteredExecutionPlatforms(List<TargetPattern> platforms) {
      this.registeredExecutionPlatforms.addAll(platforms);
    }

    void addRegisteredToolchains(List<TargetPattern> toolchains, boolean forWorkspaceSuffix) {
      if (forWorkspaceSuffix && firstWorkspaceSuffixRegisteredToolchain.isEmpty()) {
        firstWorkspaceSuffixRegisteredToolchain = OptionalInt.of(registeredToolchains.size());
      }
      this.registeredToolchains.addAll(toolchains);
    }

    void setFirstWorkspaceSuffixRegisteredToolchain(
        OptionalInt firstWorkspaceSuffixRegisteredToolchain) {
      this.firstWorkspaceSuffixRegisteredToolchain = firstWorkspaceSuffixRegisteredToolchain;
    }

    /**
     * Ensures that all symbolic macros in the package have expanded.
     *
     * <p>This does not run any macro that has already been evaluated. It *does* run macros that are
     * newly discovered during the operation of this method.
     */
    public void expandAllRemainingMacros(StarlarkSemantics semantics) throws InterruptedException {
      // TODO: #19922 - Protect against unreasonable macro stack depth and large numbers of symbolic
      // macros overall, for both the eager and deferred evaluation strategies.

      // Note that this operation is idempotent for symmetry with build()/buildPartial(). Though
      // it's not entirely clear that this is necessary.

      // TODO: #19922 - Once compatibility with native.existing_rules() in legacy macros is no
      // longer a concern, we will want to support delayed expansion of non-finalizer macros before
      // the finalizer expansion step.

      // Finalizer expansion step.
      if (!unexpandedMacros.isEmpty()) {
        Preconditions.checkState(
            unexpandedMacros.stream()
                .allMatch(id -> recorder.getMacroMap().get(id).getMacroClass().isFinalizer()),
            "At the beginning of finalizer expansion, unexpandedMacros must contain only"
                + " finalizers");

        // Save a snapshot of rule targets for use by native.existing_rules() inside all finalizers.
        // We must take this snapshot before calling any finalizer because the snapshot must not
        // include any rule instantiated by a finalizer or macro called from a finalizer.
        if (rulesSnapshotViewForFinalizers == null) {
          Preconditions.checkState(
              recorder.getTargetMap() instanceof SnapshottableBiMap<?, ?>,
              "Cannot call expandAllRemainingMacros() after beforeBuild() has been called");
          rulesSnapshotViewForFinalizers = getRulesSnapshotView();
        }

        while (!unexpandedMacros.isEmpty()) { // NB: collection mutated by body
          String id = unexpandedMacros.iterator().next();
          MacroInstance macro = recorder.getMacroMap().get(id);
          MacroClass.executeMacroImplementation(macro, this, semantics);
        }
      }
    }

    /**
     * Creates and returns input files for targets that have been referenced but not explicitly
     * declared in this package.
     *
     * <p>Precisely: For each label L appearing in one or more label-typed attributes of one or more
     * declarations D (either of a target or a symbolic macro), we create an {@code InputFile} for L
     * and return it in the map (keyed by its name) if all of the following are true:
     *
     * <ol>
     *   <li>L points to within the current package.
     *   <li>The package does not otherwise declare a target or macro named L.
     *   <li>D is not itself declared inside a symbolic macro.
     * </ol>
     *
     * <p>The third condition ensures that we can know all *possible* implicitly created input files
     * without evaluating any symbolic macros. However, if the label lies within one or more
     * symbolic macro's namespaces, then we do still need to evaluate those macros to determine
     * whether or not the second condition is true, i.e. whether the label points to a target the
     * macro declares (or a submacro it clashes with), or defaults to an implicitly created input
     * file.
     */
    private static Map<String, InputFile> createAssumedInputFiles(
        Package pkg, TargetRecorder recorder, boolean noImplicitFileExport) {
      Map<String, InputFile> implicitlyCreatedInputFiles = new HashMap<>();

      for (Rule rule : recorder.getRules()) {
        if (!recorder.isRuleCreatedInMacro(rule)) {
          for (Label label : recorder.getRuleLabels(rule)) {
            maybeCreateAssumedInputFile(
                implicitlyCreatedInputFiles,
                pkg,
                recorder,
                noImplicitFileExport,
                label,
                rule.getLocation());
          }
        }
      }

      for (MacroInstance macro : recorder.getMacroMap().values()) {
        if (macro.getParent() == null) {
          macro.visitExplicitAttributeLabels(
              label ->
                  maybeCreateAssumedInputFile(
                      implicitlyCreatedInputFiles,
                      pkg,
                      recorder,
                      noImplicitFileExport,
                      label,
                      // TODO(bazel-team): We don't save a MacroInstance's location information yet,
                      // but when we do, use that here.
                      Location.BUILTIN));
        }
      }

      return implicitlyCreatedInputFiles;
    }

    /**
     * Adds an implicitly created input file to the given map if the label points within the current
     * package and there is no existing target or macro for that label.
     */
    private static void maybeCreateAssumedInputFile(
        Map<String, InputFile> implicitlyCreatedInputFiles,
        Package pkg,
        TargetRecorder recorder,
        boolean noImplicitFileExport,
        Label label,
        Location loc) {
      String name = label.getName();
      if (!label.getPackageIdentifier().equals(pkg.getPackageIdentifier())) {
        return;
      }
      if (recorder.getTargetMap().containsKey(name)
          || recorder.hasMacroWithName(name)
          || implicitlyCreatedInputFiles.containsKey(name)) {
        return;
      }

      implicitlyCreatedInputFiles.put(
          name,
          noImplicitFileExport
              ? new PrivateVisibilityInputFile(pkg, label, loc)
              : new InputFile(pkg, label, loc));
    }

    @CanIgnoreReturnValue
    private Builder beforeBuild(boolean discoverAssumedInputFiles) throws NoSuchPackageException {
      // For correct semantics, we refuse to build a package that has declared symbolic macros that
      // have not yet been expanded. (Currently finalizers are the only use case where this happens,
      // but the Package logic is agnostic to that detail.)
      //
      // Production code should be calling expandAllRemainingMacros() to guarantee that nothing is
      // left unexpanded. Tests that do not declare any symbolic macros need not make the call.
      // Package deserialization doesn't have to do it either, since we shouldn't be evaluating
      // symbolic macros on the deserialized result of an already evaluated package.
      Preconditions.checkState(
          unexpandedMacros.isEmpty(),
          "Cannot build a package with unexpanded symbolic macros; call"
              + " expandAllRemainingMacros()");

      if (ioException != null) {
        throw new NoSuchPackageException(
            getPackageIdentifier(), ioExceptionMessage, ioException, ioExceptionDetailedExitCode);
      }

      // SnapshottableBiMap does not allow removing targets (in order to efficiently track rule
      // insertion order). However, we *do* need to support removal of targets in
      // PackageFunction.handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions
      // which is called *between* calls to beforeBuild and finishBuild. We thus repoint the targets
      // map to the SnapshottableBiMap's underlying bimap and thus stop tracking insertion order.
      // After this point, snapshots of targets should no longer be used, and any further
      // getRulesSnapshotView calls will throw.
      if (recorder.getTargetMap() instanceof SnapshottableBiMap<?, ?>) {
        recorder.unwrapSnapshottableBiMap();
        rulesSnapshotViewForFinalizers = null;
      }

      // We create an InputFile corresponding to the BUILD file in Builder's constructor. However,
      // the visibility of this target may be overridden with an exports_files directive, so we wait
      // until now to obtain the current instance from the targets map.
      pkg.buildFile =
          Preconditions.checkNotNull(
              (InputFile) recorder.getTargetMap().get(buildFileLabel.getName()));

      // TODO(bazel-team): We run testSuiteImplicitTestsAccumulator here in beforeBuild(), but what
      // if one of the accumulated tests is later removed in PackageFunction, between the call to
      // buildPartial() and finishBuild(), due to a label-crossing-subpackage-boundary error? Seems
      // like that would mean a test_suite is referencing a Target that's been deleted from its
      // Package.

      // Clear tests before discovering them again in order to keep this method idempotent -
      // otherwise we may double-count tests if we're called twice due to a skyframe restart, etc.
      testSuiteImplicitTestsAccumulator.clearAccumulatedTests();
      for (Rule rule : recorder.getRules()) {
        testSuiteImplicitTestsAccumulator.processRule(rule);
      }
      // Make sure all accumulated values are sorted for determinism.
      testSuiteImplicitTestsAccumulator.sortTests();

      if (discoverAssumedInputFiles) {
        Map<String, InputFile> newInputFiles =
            createAssumedInputFiles(pkg, recorder, noImplicitFileExport);
        for (InputFile file : newInputFiles.values()) {
          recorder.addInputFileUnchecked(file);
        }
      }

      return this;
    }

    /** Intended for use by {@link com.google.devtools.build.lib.skyframe.PackageFunction} only. */
    // TODO(bazel-team): It seems like the motivation for this method (added in cl/74794332) is to
    // allow PackageFunction to delete targets that are found to violate the
    // label-crossing-subpackage-boundaries check. Is there a simpler way to express this idea that
    // doesn't make package-building a multi-stage process?
    @CanIgnoreReturnValue
    public Builder buildPartial() throws NoSuchPackageException {
      if (alreadyBuilt) {
        return this;
      }
      return beforeBuild(/* discoverAssumedInputFiles= */ true);
    }

    /** Intended for use by {@link com.google.devtools.build.lib.skyframe.PackageFunction} only. */
    public Package finishBuild() {
      if (alreadyBuilt) {
        return pkg;
      }

      // Freeze rules, compacting their attributes' representations.
      for (Rule rule : recorder.getRules()) {
        rule.freeze();
      }

      // Now all targets have been loaded, so we validate the group's member environments.
      for (EnvironmentGroup envGroup : ImmutableSet.copyOf(environmentGroups.values())) {
        List<Event> errors = envGroup.processMemberEnvironments(recorder.getTargetMap());
        if (!errors.isEmpty()) {
          Event.replayEventsOn(localEventHandler, errors);
          // TODO(bazel-team): Can't we automatically infer containsError from the presence of
          // ERRORs on our handler?
          setContainsErrors();
        }
      }

      // Build the package.
      pkg.finishInit(this);
      alreadyBuilt = true;
      return pkg;
    }

    /** Completes package construction. Idempotent. */
    // TODO(brandjon): Do we actually care about idempotence?
    public Package build() throws NoSuchPackageException {
      return build(/* discoverAssumedInputFiles= */ true);
    }

    /**
     * Constructs the package (or does nothing if it's already built) and returns it.
     *
     * @param discoverAssumedInputFiles whether to automatically add input file targets to this
     *     package for "dangling labels", i.e. labels mentioned in this package that point to an
     *     up-until-now non-existent target in this package
     */
    Package build(boolean discoverAssumedInputFiles) throws NoSuchPackageException {
      if (alreadyBuilt) {
        return pkg;
      }
      beforeBuild(discoverAssumedInputFiles);
      return finishBuild();
    }
  }

  /** A collection of data that is known before BUILD file evaluation even begins. */
  public record Metadata(
      PackageIdentifier packageIdentifier,
      RootedPath buildFilename,
      boolean isRepoRulePackage,
      RepositoryMapping repositoryMapping,
      Optional<String> associatedModuleName,
      Optional<String> associatedModuleVersion,
      @Nullable ConfigSettingVisibilityPolicy configSettingVisibilityPolicy,
      boolean succinctTargetNotFoundErrors) {

    public Metadata {
      Preconditions.checkNotNull(packageIdentifier);
      Preconditions.checkNotNull(buildFilename);
      Preconditions.checkNotNull(repositoryMapping);
      Preconditions.checkNotNull(associatedModuleName);
      Preconditions.checkNotNull(associatedModuleVersion);

      // Check for consistency between isRepoRulePackage and whether the buildFilename is a
      // WORKSPACE / MODULE.bazel file.
      String baseName = buildFilename.asPath().getBaseName();
      boolean isWorkspaceFile =
          baseName.equals(LabelConstants.WORKSPACE_DOT_BAZEL_FILE_NAME.getPathString())
              || baseName.equals(LabelConstants.WORKSPACE_FILE_NAME.getPathString());
      boolean isModuleDotBazelFile =
          baseName.equals(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME.getPathString());
      Preconditions.checkArgument(isRepoRulePackage == (isWorkspaceFile || isModuleDotBazelFile));
    }

    /** Returns the name of this package (sans repository), e.g. "foo/bar". */
    public String getName() {
      return packageIdentifier.getPackageFragment().getPathString();
    }

    /**
     * Returns the directory in which this package's BUILD file resides.
     *
     * <p>All InputFile members of the packages are located relative to this directory.
     */
    public Path getPackageDirectory() {
      return buildFilename.asPath().getParentDirectory();
    }
  }

  /** Package codec implementation. */
  @VisibleForTesting
  static final class PackageCodec implements ObjectCodec<Package> {
    @Override
    public Class<Package> getEncodedClass() {
      return Package.class;
    }

    @Override
    public void serialize(SerializationContext context, Package input, CodedOutputStream codedOut)
        throws IOException, SerializationException {
      context.checkClassExplicitlyAllowed(Package.class, input);
      PackageCodecDependencies codecDeps = context.getDependency(PackageCodecDependencies.class);
      codecDeps.getPackageSerializer().serialize(context, input, codedOut);
    }

    @Override
    public Package deserialize(DeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      PackageCodecDependencies codecDeps = context.getDependency(PackageCodecDependencies.class);
      return codecDeps.getPackageSerializer().deserialize(context, codedIn);
    }
  }
}
