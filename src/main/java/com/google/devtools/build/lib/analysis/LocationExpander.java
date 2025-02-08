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

import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction.PathType;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.Label.PackageContext;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;

/**
 * Expands $(location) and $(locations) tags inside target attributes. You can specify something
 * like this in the BUILD file:
 *
 * <pre>
 * somerule(name='some name',
 *          someopt = [ '$(location //mypackage:myhelper)' ],
 *          ...)
 * </pre>
 *
 * and location will be substituted with //mypackage:myhelper executable output.
 *
 * <p>Note that this expander will always expand labels in srcs, deps, and tools attributes, with
 * data being optional.
 *
 * <p>DO NOT USE DIRECTLY! Use RuleContext.getExpander() instead.
 */
public final class LocationExpander {

  private static final boolean EXACTLY_ONE = false;
  private static final boolean ALLOW_MULTIPLE = true;

  private final RuleErrorConsumer ruleErrorConsumer;
  private final ImmutableMap<String, LocationFunction> functions;
  private final RepositoryMapping repositoryMapping;
  private final String workspaceRunfilesDirectory;

  @VisibleForTesting
  LocationExpander(
      RuleErrorConsumer ruleErrorConsumer,
      Map<String, LocationFunction> functions,
      RepositoryMapping repositoryMapping,
      String workspaceRunfilesDirectory) {
    this.ruleErrorConsumer = ruleErrorConsumer;
    this.functions = ImmutableMap.copyOf(functions);
    this.repositoryMapping = repositoryMapping;
    this.workspaceRunfilesDirectory = workspaceRunfilesDirectory;
  }

  private LocationExpander(
      RuleContext ruleContext,
      Label root,
      Supplier<Map<Label, Collection<Artifact>>> locationMap,
      boolean execPaths,
      boolean legacyExternalRunfiles,
      RepositoryMapping repositoryMapping) {
    this(
        ruleContext,
        allLocationFunctions(root, locationMap, execPaths, legacyExternalRunfiles),
        repositoryMapping,
        ruleContext.getWorkspaceName());
  }

  /**
   * Creates location expander helper bound to specific target and with default location map.
   *
   * @param ruleContext BUILD rule
   * @param labelMap A mapping of labels to build artifacts.
   * @param execPaths If true, this expander will expand $(location)/$(locations) using
   *     Artifact.getExecPath(); otherwise with Artifact.getLocationPath().
   * @param allowData If true, this expander will expand locations from the `data` attribute;
   *     otherwise it will not.
   */
  private LocationExpander(
      RuleContext ruleContext,
      @Nullable ImmutableMap<Label, ImmutableCollection<Artifact>> labelMap,
      boolean execPaths,
      boolean allowData) {
    this(
        ruleContext,
        ruleContext.getLabel(),
        // Use a memoizing supplier to avoid eagerly building the location map.
        Suppliers.memoize(
            () -> LocationExpander.buildLocationMap(ruleContext, labelMap, allowData, true)),
        execPaths,
        ruleContext.getConfiguration().legacyExternalRunfiles(),
        ruleContext.getRule().getPackage().getRepositoryMapping());
  }

  /**
   * Creates an expander that expands $(location)/$(locations) using Artifact.getLocationPath().
   *
   * <p>The expander expands $(rootpath)/$(rootpaths) using Artifact.getLocationPath(), and
   * $(execpath)/$(execpaths) using Artifact.getExecPath().
   *
   * @param ruleContext BUILD rule
   * @param labelMap A mapping of labels to build artifacts
   */
  public static LocationExpander withRunfilesPaths(
      RuleContext ruleContext,
      @Nullable ImmutableMap<Label, ImmutableCollection<Artifact>> labelMap) {
    return new LocationExpander(ruleContext, labelMap, false, false);
  }

  /**
   * Creates an expander that expands $(location)/$(locations) using Artifact.getExecPath().
   *
   * <p>The expander expands $(rootpath)/$(rootpaths) using Artifact.getLocationPath(), and
   * $(execpath)/$(execpaths) using Artifact.getExecPath().
   *
   * @param ruleContext BUILD rule
   * @param labelMap A mapping of labels to build artifacts.
   */
  public static LocationExpander withExecPaths(
      RuleContext ruleContext, ImmutableMap<Label, ImmutableCollection<Artifact>> labelMap) {
    return new LocationExpander(ruleContext, labelMap, true, false);
  }

  /**
   * Creates an expander that expands $(location)/$(locations) using Artifact.getExecPath().
   *
   * <p>The expander expands $(rootpath)/$(rootpaths) using Artifact.getLocationPath(), and
   * $(execpath)/$(execpaths) using Artifact.getExecPath().
   *
   * @param ruleContext BUILD rule
   * @param labelMap A mapping of labels to build artifacts.
   */
  public static LocationExpander withExecPathsAndData(
      RuleContext ruleContext, ImmutableMap<Label, ImmutableCollection<Artifact>> labelMap) {
    return new LocationExpander(ruleContext, labelMap, true, true);
  }

  public String expand(String input) {
    return expand(input, new RuleErrorReporter(ruleErrorConsumer));
  }

  private String expand(String value, ErrorReporter reporter) {
    int restart = 0;

    StringBuilder result = new StringBuilder(value.length());

    while (true) {
      // (1) Find '$(<fname> '.
      int start = value.indexOf("$(", restart);
      if (start == -1) {
        result.append(value.substring(restart));
        break;
      }
      int nextWhitespace = value.indexOf(' ', start);
      if (nextWhitespace == -1) {
        result.append(value, restart, start + 2);
        restart = start + 2;
        continue;
      }
      String fname = value.substring(start + 2, nextWhitespace);
      if (!functions.containsKey(fname)) {
        result.append(value, restart, start + 2);
        restart = start + 2;
        continue;
      }

      result.append(value, restart, start);

      int end = value.indexOf(')', nextWhitespace);
      if (end == -1) {
        reporter.report(
            String.format(
                "unterminated $(%s) expression", value.substring(start + 2, nextWhitespace)));
        return value;
      }

      // (2) Call appropriate function to obtain string replacement.
      String functionValue = value.substring(nextWhitespace + 1, end).trim();
      try {
        String replacement =
            functions
                .get(fname)
                .apply(functionValue, repositoryMapping, workspaceRunfilesDirectory);
        result.append(replacement);
      } catch (IllegalStateException ise) {
        reporter.report(ise.getMessage());
        return value;
      }

      restart = end + 1;
    }

    return result.toString();
  }

  /**
   * Expands attribute's location and locations tags based on the target and
   * location map.
   *
   * @param attrName  name of the attribute; only used for error reporting
   * @param attrValue initial value of the attribute
   * @return attribute value with expanded location tags or original value in
   *         case of errors
   */
  public String expandAttribute(String attrName, String attrValue) {
    return expand(attrValue, new AttributeErrorReporter(ruleErrorConsumer, attrName));
  }

  @VisibleForTesting
  static final class LocationFunction {
    enum PathType {
      LOCATION,
      EXEC,
      RLOCATION,
    }

    private static final int MAX_PATHS_SHOWN = 5;

    private final Label root;
    private final Supplier<Map<Label, Collection<Artifact>>> locationMapSupplier;
    private final PathType pathType;
    private final boolean legacyExternalRunfiles;
    private final boolean multiple;

    LocationFunction(
        Label root,
        Supplier<Map<Label, Collection<Artifact>>> locationMapSupplier,
        PathType pathType,
        boolean legacyExternalRunfiles,
        boolean multiple) {
      this.root = root;
      this.locationMapSupplier = locationMapSupplier;
      this.pathType = Preconditions.checkNotNull(pathType);
      this.legacyExternalRunfiles = legacyExternalRunfiles;
      this.multiple = multiple;
    }

    /**
     * Looks up the label-like string in the locationMap and returns the resolved path string. If
     * the label-like string begins with a repository name, the repository name may be remapped
     * using the {@code repositoryMapping}.
     *
     * @param arg The label-like string to be expanded, e.g. ":foo" or "//foo:bar"
     * @param repositoryMapping map of apparent repository names to {@code RepositoryName}s
     * @param workspaceRunfilesDirectory name of the runfiles directory corresponding to the main
     *     repository
     * @return The expanded value
     */
    public String apply(
        String arg, RepositoryMapping repositoryMapping, String workspaceRunfilesDirectory) {
      Label label;
      try {
        label =
            Label.parseWithPackageContext(
                arg, PackageContext.of(root.getPackageIdentifier(), repositoryMapping));
      } catch (LabelSyntaxException e) {
        throw new IllegalStateException(
            String.format(
                "invalid label in %s expression: %s", functionName(), e.getMessage()), e);
      }
      Set<String> paths = resolveLabel(label, workspaceRunfilesDirectory);
      return joinPaths(paths);
    }

    /** Returns all target location(s) of the given label. */
    private Set<String> resolveLabel(Label unresolved, String workspaceRunfilesDirectory)
        throws IllegalStateException {
      Collection<Artifact> artifacts = locationMapSupplier.get().get(unresolved);

      if (artifacts == null) {
        throw new IllegalStateException(
            String.format(
                "label '%s' in %s expression is not a declared prerequisite of this rule",
                unresolved, functionName()));
      }

      Set<String> paths = getPaths(artifacts, workspaceRunfilesDirectory);
      if (paths.isEmpty()) {
        throw new IllegalStateException(
            String.format(
                "label '%s' in %s expression expands to no files",
                unresolved, functionName()));
      }

      if (!multiple && paths.size() > 1) {
        throw new IllegalStateException(
            String.format(
                "label '%s' in $(location) expression expands to more than one file, "
                    + "please use $(locations %s) instead.  Files (at most %d shown) are: %s",
                unresolved,
                unresolved,
                MAX_PATHS_SHOWN,
                Iterables.limit(paths, MAX_PATHS_SHOWN)));
      }
      return paths;
    }

    /**
     * Extracts list of all executables associated with given collection of label artifacts.
     *
     * @param artifacts to get the paths of
     * @param workspaceRunfilesDirectory name of the runfiles directory corresponding to the main
     *     repository
     * @return all associated executable paths
     */
    private Set<String> getPaths(
        Collection<Artifact> artifacts, String workspaceRunfilesDirectory) {
      TreeSet<String> paths = Sets.newTreeSet();
      for (Artifact artifact : artifacts) {
        PathFragment path = getPath(artifact, workspaceRunfilesDirectory);
        if (path != null) {
          paths.add(path.getCallablePathString());
        }
      }
      return paths;
    }

    private PathFragment getPath(Artifact artifact, String workspaceRunfilesDirectory) {
      return switch (pathType) {
        case LOCATION ->
            legacyExternalRunfiles
                ? artifact.getPathForLocationExpansion()
                : artifact.getRunfilesPath();
        case EXEC -> artifact.getExecPath();
        case RLOCATION -> {
          PathFragment runfilesPath = artifact.getRunfilesPath();
          if (runfilesPath.startsWith(LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX)) {
            yield runfilesPath.relativeTo(LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX);
          } else {
            yield PathFragment.create(workspaceRunfilesDirectory).getRelative(runfilesPath);
          }
        }
      };
    }

    private String joinPaths(Collection<String> paths) {
      return paths.stream().map(ShellEscaper::escapeString).collect(joining(" "));
    }

    private String functionName() {
      return multiple ? "$(locations)" : "$(location)";
    }
  }

  static ImmutableMap<String, LocationFunction> allLocationFunctions(
      Label root,
      Supplier<Map<Label, Collection<Artifact>>> locationMap,
      boolean execPaths,
      boolean legacyExternalRunfiles) {
    return new ImmutableMap.Builder<String, LocationFunction>()
        .put(
            "location",
            new LocationFunction(
                root,
                locationMap,
                execPaths ? PathType.EXEC : PathType.LOCATION,
                legacyExternalRunfiles,
                EXACTLY_ONE))
        .put(
            "locations",
            new LocationFunction(
                root,
                locationMap,
                execPaths ? PathType.EXEC : PathType.LOCATION,
                legacyExternalRunfiles,
                ALLOW_MULTIPLE))
        .put(
            "rootpath",
            new LocationFunction(
                root, locationMap, PathType.LOCATION, legacyExternalRunfiles, EXACTLY_ONE))
        .put(
            "rootpaths",
            new LocationFunction(
                root, locationMap, PathType.LOCATION, legacyExternalRunfiles, ALLOW_MULTIPLE))
        .put(
            "execpath",
            new LocationFunction(
                root, locationMap, PathType.EXEC, legacyExternalRunfiles, EXACTLY_ONE))
        .put(
            "execpaths",
            new LocationFunction(
                root, locationMap, PathType.EXEC, legacyExternalRunfiles, ALLOW_MULTIPLE))
        .put(
            "rlocationpath",
            new LocationFunction(
                root, locationMap, PathType.RLOCATION, legacyExternalRunfiles, EXACTLY_ONE))
        .put(
            "rlocationpaths",
            new LocationFunction(
                root, locationMap, PathType.RLOCATION, legacyExternalRunfiles, ALLOW_MULTIPLE))
        .buildOrThrow();
  }

  /**
   * Extracts all possible target locations from target specification.
   *
   * @param ruleContext BUILD target object
   * @param labelMap map of labels to build artifacts
   * @return map of all possible target locations
   */
  static Map<Label, Collection<Artifact>> buildLocationMap(
      RuleContext ruleContext,
      Map<Label, ? extends Collection<Artifact>> labelMap,
      boolean allowDataAttributeEntriesInLabel,
      boolean collectSrcs) {
    Map<Label, Collection<Artifact>> locationMap = Maps.newHashMap();
    if (labelMap != null) {
      for (Map.Entry<Label, ? extends Collection<Artifact>> entry : labelMap.entrySet()) {
        mapGet(locationMap, entry.getKey()).addAll(entry.getValue());
      }
    }

    // We don't want to do this if we're processing aspect rules. It will
    // create output artifacts and unbalance the input/output state, leading
    // to an error (output artifact with no action to create its inputs).
    if (ruleContext.getMainAspect() == null) {
      // Add all destination locations.
      for (OutputFile out : ruleContext.getRule().getOutputFiles()) {
        // Not in aspect processing, so explicitly build an artifact & let it verify.
        mapGet(locationMap, out.getLabel()).add(ruleContext.createOutputArtifact(out));
      }
    }

    if (collectSrcs && ruleContext.getRule().isAttrDefined("srcs", BuildType.LABEL_LIST)) {
      for (TransitiveInfoCollection src :
          ruleContext
              .getRulePrerequisitesCollection()
              .getPrerequisitesIf("srcs", FileProvider.class)) {
        for (Label label : AliasProvider.getDependencyLabels(src)) {
          mapGet(locationMap, label)
              .addAll(src.getProvider(FileProvider.class).getFilesToBuild().toList());
        }
      }
    }

    // Add all locations associated with dependencies and tools
    List<TransitiveInfoCollection> depsDataAndTools = new ArrayList<>();
    if (ruleContext.getRule().isAttrDefined("deps", BuildType.LABEL_LIST)) {
      Iterables.addAll(
          depsDataAndTools,
          ruleContext
              .getRulePrerequisitesCollection()
              .getPrerequisitesIf("deps", FilesToRunProvider.class));
    }
    if (ruleContext.getRule().isAttrDefined("implementation_deps", BuildType.LABEL_LIST)) {
      Iterables.addAll(
          depsDataAndTools,
          ruleContext
              .getRulePrerequisitesCollection()
              .getPrerequisitesIf("implementation_deps", FilesToRunProvider.class));
    }
    if (allowDataAttributeEntriesInLabel
        && ruleContext.getRule().isAttrDefined("data", BuildType.LABEL_LIST)) {
      Iterables.addAll(
          depsDataAndTools,
          ruleContext
              .getRulePrerequisitesCollection()
              .getPrerequisitesIf("data", FilesToRunProvider.class));
    }
    if (ruleContext.getRule().isAttrDefined("tools", BuildType.LABEL_LIST)) {
      Iterables.addAll(
          depsDataAndTools,
          ruleContext
              .getRulePrerequisitesCollection()
              .getPrerequisitesIf("tools", FilesToRunProvider.class));
    }

    for (TransitiveInfoCollection dep : depsDataAndTools) {
      ImmutableList<Label> labels = AliasProvider.getDependencyLabels(dep);
      FilesToRunProvider filesToRun = dep.getProvider(FilesToRunProvider.class);
      Artifact executableArtifact = filesToRun.getExecutable();
      FileProvider fileProvider = dep.getProvider(FileProvider.class);

      // If the label has an executable artifact add that to the multimaps.
      Collection<Artifact> values =
          executableArtifact != null
              ? ImmutableList.of(executableArtifact)
              : fileProvider.getFilesToBuild().toList();

      for (Label label : labels) {
        mapGet(locationMap, label).addAll(values);
      }
    }
    return locationMap;
  }

  /**
   * Returns the value in the specified map corresponding to 'key', creating and
   * inserting an empty container if absent. We use Map not Multimap because
   * we need to distinguish the cases of "empty value" and "absent key".
   *
   * @return the value in the specified map corresponding to 'key'
   */
  private static <K, V> Collection<V> mapGet(Map<K, Collection<V>> map, K key) {
    Collection<V> values = map.get(key);
    if (values == null) {
      // We use sets not lists, because it's conceivable that the same label
      // could appear twice, in "srcs" and "deps".
      values = Sets.newHashSet();
      map.put(key, values);
    }
    return values;
  }

  private static interface ErrorReporter {
    void report(String error);
  }

  private static final class AttributeErrorReporter implements ErrorReporter {
    private final RuleErrorConsumer delegate;
    private final String attrName;

    public AttributeErrorReporter(RuleErrorConsumer delegate, String attrName) {
      this.delegate = delegate;
      this.attrName = attrName;
    }

    @Override
    public void report(String error) {
      delegate.attributeError(attrName, error);
    }
  }

  private static final class RuleErrorReporter implements ErrorReporter {
    private final RuleErrorConsumer delegate;

    public RuleErrorReporter(RuleErrorConsumer delegate) {
      this.delegate = delegate;
    }

    @Override
    public void report(String error) {
      delegate.ruleError(error);
    }
  }
}
