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
package com.google.devtools.build.lib.skyframe;

import static java.util.Arrays.stream;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionException;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.packages.Globber;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The canonical approach to compute {@link GlobFunction} by recursively creating sub-Glob nodes
 * when handling subdirectories under a package.
 */
public final class GlobFunctionWithMultipleRecursiveFunctions extends GlobFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws GlobException, InterruptedException {
    GlobDescriptor glob = (GlobDescriptor) skyKey.argument();
    Globber.Operation globberOperation = glob.globberOperation();

    RepositoryName repositoryName = glob.getPackageId().getRepository();
    IgnoredSubdirectoriesValue ignoredSubdirectories =
        (IgnoredSubdirectoriesValue) env.getValue(IgnoredSubdirectoriesValue.key(repositoryName));
    if (env.valuesMissing()) {
      return null;
    }

    PathFragment globSubdir = glob.getSubdir();
    PathFragment dirPathFragment = glob.getPackageId().getPackageFragment().getRelative(globSubdir);

    if (ignoredSubdirectories.asIgnoredSubdirectories().matchingEntry(dirPathFragment) != null) {
      return GlobValueWithNestedSet.EMPTY;
    }

    String pattern = glob.getPattern();

    // Note that the glob's package is assumed to exist which implies that the package's BUILD file
    // exists which implies that the package's directory exists.
    if (!globSubdir.equals(PathFragment.EMPTY_FRAGMENT)) {
      PathFragment subDirFragment =
          glob.getPackageId().getPackageFragment().getRelative(globSubdir);

      PackageLookupValue globSubdirPkgLookupValue =
          (PackageLookupValue)
              env.getValue(
                  PackageLookupValue.key(PackageIdentifier.create(repositoryName, subDirFragment)));
      if (globSubdirPkgLookupValue == null) {
        return null;
      }

      if (globSubdirPkgLookupValue.packageExists()) {
        // We crossed the package boundary, that is, pkg/subdir contains a BUILD file and thus
        // defines another package, so glob expansion should not descend into
        // that subdir.
        //
        // For SUBPACKAGES, we encounter this when all remaining patterns in the glob expression
        // are `**`s. In that case we should include the subpackage's PathFragment (relative to the
        // package fragment) in the GlobValue.getMatches. Otherwise, return EMPTY.
        if (globberOperation == Globber.Operation.SUBPACKAGES
            && stream(pattern.split("/")).allMatch("**"::equals)) {
          return new GlobValueWithNestedSet(
              NestedSetBuilder.<PathFragment>stableOrder()
                  .add(subDirFragment.relativeTo(glob.getPackageId().getPackageFragment()))
                  .build());
        }
        return GlobValueWithNestedSet.EMPTY;
      } else if (globSubdirPkgLookupValue
          instanceof PackageLookupValue.IncorrectRepositoryReferencePackageLookupValue) {
        // We crossed a repository boundary, so glob expansion should not descend into that subdir.
        return GlobValueWithNestedSet.EMPTY;
      }
    }

    // Split off the first path component of the pattern.
    int slashPos = pattern.indexOf('/');
    String patternHead;
    String patternTail;
    if (slashPos == -1) {
      patternHead = pattern;
      patternTail = null;
    } else {
      // Substrings will share the backing array of the original glob string. That should be fine.
      patternHead = pattern.substring(0, slashPos);
      patternTail = pattern.substring(slashPos + 1);
    }

    boolean globMatchesBareFile = patternTail == null;

    NestedSetBuilder<PathFragment> matches = NestedSetBuilder.stableOrder();

    RootedPath dirRootedPath = RootedPath.toRootedPath(glob.getPackageRoot(), dirPathFragment);
    // Note that we have good reason to believe the directory exists: if this is the
    // top-level directory of the package, the package's existence implies the directory's
    // existence; if this is a lower-level directory in the package, then we got here from
    // previous directory listings. Filesystem operations concurrent with build could mean the
    // directory no longer exists, but DirectoryListingFunction handles that gracefully.
    SkyKey directoryListingKey = DirectoryListingValue.key(dirRootedPath);
    DirectoryListingValue listingValue = null;

    boolean patternHeadContainsGlobs = containsGlobs(patternHead);
    boolean patternHeadIsStarStar = patternHead.equals("**");
    if (patternHeadIsStarStar) {
      // "**" also matches an empty segment, so try the case where it is not present.
      if (globMatchesBareFile) {
        // Recursive globs aren't supposed to match the package's directory.
        if (globberOperation == Globber.Operation.FILES_AND_DIRS
            && !globSubdir.equals(PathFragment.EMPTY_FRAGMENT)) {
          matches.add(globSubdir);
        }
      } else {
        // Optimize away a Skyframe restart by requesting the DirectoryListingValue dep and
        // recursive GlobValue dep in a single batch.

        SkyKey keyForRecursiveGlobInCurrentDirectory =
            GlobValue.internalKey(
                glob.getPackageId(),
                glob.getPackageRoot(),
                globSubdir,
                patternTail,
                globberOperation);
        SkyframeLookupResult listingAndRecursiveGlobResult =
            env.getValuesAndExceptions(
                ImmutableList.of(keyForRecursiveGlobInCurrentDirectory, directoryListingKey));
        if (env.valuesMissing()) {
          return null;
        }
        GlobValue globValue =
            (GlobValue) listingAndRecursiveGlobResult.get(keyForRecursiveGlobInCurrentDirectory);
        if (globValue == null) {
          // has exception, will be handled later.
          return null;
        }
        Preconditions.checkState(globValue instanceof GlobValueWithNestedSet);
        matches.addTransitive(((GlobValueWithNestedSet) globValue).getMatchesInNestedSet());
        listingValue =
            (DirectoryListingValue) listingAndRecursiveGlobResult.get(directoryListingKey);
      }
    }

    if (listingValue == null) {
      listingValue = (DirectoryListingValue) env.getValue(directoryListingKey);
      if (listingValue == null) {
        return null;
      }
    }

    // Now that we have the directory listing, we do three passes over it so as to maximize
    // skyframe batching:
    // (1) Process every dirent, keeping track of values we need to request if the dirent cannot
    //     be processed with current information (symlink targets and subdirectory globs/package
    //     lookups for some subdirectories).
    // (2) Get those values and process the symlinks, keeping track of subdirectory globs/package
    //     lookups we may need to request in case the symlink's target is a directory.
    // (3) Process the necessary subdirectories.
    int direntsSize = listingValue.getDirents().size();
    Map<SkyKey, Dirent> symlinkFileMap = Maps.newHashMapWithExpectedSize(direntsSize);
    Map<SkyKey, Dirent> subdirMap = Maps.newHashMapWithExpectedSize(direntsSize);
    Map<Dirent, Object> sortedResultMap = Maps.newTreeMap();
    String subdirPattern = patternHeadIsStarStar ? glob.getPattern() : patternTail;
    // First pass: do normal files and collect SkyKeys to request for subdirectories and symlinks.
    for (Dirent dirent : listingValue.getDirents()) {
      Dirent.Type direntType = dirent.getType();
      String fileName = dirent.getName();
      boolean patternHeadMatchesDirent =
          patternHeadContainsGlobs
              ? UnixGlob.matches(patternHead, fileName, regexPatternCache)
              : patternHead.equals(fileName);
      if (!patternHeadMatchesDirent) {
        continue;
      }

      if (direntType == Dirent.Type.SYMLINK) {
        // TODO(bazel-team): Consider extracting the symlink resolution logic.
        // For symlinks, look up the corresponding FileValue. This ensures that if the symlink
        // changes and "switches types" (say, from a file to a directory), this value will be
        // invalidated. We also need the target's type to properly process the symlink.
        symlinkFileMap.put(
            FileValue.key(
                RootedPath.toRootedPath(
                    glob.getPackageRoot(), dirPathFragment.getRelative(fileName))),
            dirent);
        continue;
      }

      if (direntType == Dirent.Type.DIRECTORY) {
        SkyKey keyToRequest = getSkyKeyForSubdir(fileName, glob, subdirPattern);
        if (keyToRequest != null) {
          subdirMap.put(keyToRequest, dirent);
        }
      } else if (globMatchesBareFile && globberOperation != Globber.Operation.SUBPACKAGES) {
        sortedResultMap.put(dirent, glob.getSubdir().getRelative(fileName));
      }
    }

    Set<SkyKey> subdirAndSymlinksKeys = Sets.union(subdirMap.keySet(), symlinkFileMap.keySet());
    SkyframeLookupResult subdirAndSymlinksResult =
        env.getValuesAndExceptions(subdirAndSymlinksKeys);
    if (env.valuesMissing()) {
      return null;
    }
    Map<SkyKey, Dirent> symlinkSubdirMap = Maps.newHashMapWithExpectedSize(symlinkFileMap.size());
    // Second pass: process the symlinks and subdirectories from the first pass, and maybe
    // collect further SkyKeys if fully resolved symlink targets are themselves directories.
    // Also process any known directories.
    for (SkyKey subdirAndSymlinksKey : subdirAndSymlinksKeys) {
      if (symlinkFileMap.containsKey(subdirAndSymlinksKey)) {
        FileValue symlinkFileValue = (FileValue) subdirAndSymlinksResult.get(subdirAndSymlinksKey);
        if (symlinkFileValue == null) {
          return null;
        }
        if (!symlinkFileValue.isSymlink()) {
          throw new GlobException(
              new InconsistentFilesystemException(
                  "readdir and stat disagree about whether "
                      + ((RootedPath) subdirAndSymlinksKey.argument()).asPath()
                      + " is a symlink."),
              Transience.TRANSIENT);
        }
        if (!symlinkFileValue.exists()) {
          continue;
        }

        // This check is more strict than necessary: we raise an error if globbing traverses into
        // a directory for any reason, even though it's only necessary if that reason was the
        // resolution of a recursive glob ("**"). Fixing this would require plumbing the ancestor
        // symlink information through DirectoryListingValue.
        if (symlinkFileValue.isDirectory()
            && symlinkFileValue.unboundedAncestorSymlinkExpansionChain() != null) {
          SkyKey uniquenessKey =
              FileSymlinkInfiniteExpansionUniquenessFunction.key(
                  symlinkFileValue.unboundedAncestorSymlinkExpansionChain());
          env.getValue(uniquenessKey);
          if (env.valuesMissing()) {
            return null;
          }

          FileSymlinkInfiniteExpansionException symlinkException =
              new FileSymlinkInfiniteExpansionException(
                  symlinkFileValue.pathToUnboundedAncestorSymlinkExpansionChain(),
                  symlinkFileValue.unboundedAncestorSymlinkExpansionChain());
          throw new GlobException(symlinkException, Transience.PERSISTENT);
        }

        Dirent dirent = symlinkFileMap.get(subdirAndSymlinksKey);
        String fileName = dirent.getName();
        if (symlinkFileValue.isDirectory()) {
          SkyKey keyToRequest = getSkyKeyForSubdir(fileName, glob, subdirPattern);
          if (keyToRequest != null) {
            symlinkSubdirMap.put(keyToRequest, dirent);
          }
        } else if (globMatchesBareFile && globberOperation != Globber.Operation.SUBPACKAGES) {
          sortedResultMap.put(dirent, glob.getSubdir().getRelative(fileName));
        }
      } else {
        SkyValue value = subdirAndSymlinksResult.get(subdirAndSymlinksKey);
        if (value == null) {
          return null;
        }
        processSubdir(Map.entry(subdirAndSymlinksKey, value), subdirMap, glob, sortedResultMap);
      }
    }

    Set<SkyKey> symlinkSubdirKeys = symlinkSubdirMap.keySet();
    SkyframeLookupResult symlinkSubdirResult = env.getValuesAndExceptions(symlinkSubdirKeys);
    if (env.valuesMissing()) {
      return null;
    }
    // Third pass: do needed subdirectories of symlinked directories discovered during the second
    // pass.
    for (SkyKey symlinkSubdirKey : symlinkSubdirKeys) {
      SkyValue symlinkSubdirValue = symlinkSubdirResult.get(symlinkSubdirKey);
      if (symlinkSubdirValue == null) {
        return null;
      }
      processSubdir(
          Map.entry(symlinkSubdirKey, symlinkSubdirValue), symlinkSubdirMap, glob, sortedResultMap);
    }
    for (Map.Entry<Dirent, Object> fileMatches : sortedResultMap.entrySet()) {
      addToMatches(fileMatches.getValue(), matches);
    }

    Preconditions.checkState(!env.valuesMissing(), skyKey);

    NestedSet<PathFragment> matchesBuilt = matches.build();
    // Use the same value to represent that we did not match anything.
    if (matchesBuilt.isEmpty()) {
      return GlobValueWithNestedSet.EMPTY;
    }
    return new GlobValueWithNestedSet(matchesBuilt);
  }

  private static void processSubdir(
      Map.Entry<SkyKey, SkyValue> keyAndValue,
      Map<SkyKey, Dirent> subdirMap,
      GlobDescriptor glob,
      Map<Dirent, Object> sortedResultMap) {
    Dirent dirent = Preconditions.checkNotNull(subdirMap.get(keyAndValue.getKey()), keyAndValue);
    String fileName = dirent.getName();
    Object dirMatches = getSubdirMatchesFromSkyValue(fileName, glob, keyAndValue.getValue());
    if (dirMatches != null) {
      sortedResultMap.put(dirent, dirMatches);
    }
  }

  /** Returns true if the given pattern contains globs. */
  private static boolean containsGlobs(String pattern) {
    return pattern.contains("*") || pattern.contains("?");
  }

  @SuppressWarnings("unchecked") // cast to NestedSet<PathFragment>
  private static void addToMatches(Object toAdd, NestedSetBuilder<PathFragment> matches) {
    if (toAdd instanceof PathFragment pathFragment) {
      matches.add(pathFragment);
    } else if (toAdd instanceof NestedSet) {
      matches.addTransitive((NestedSet<PathFragment>) toAdd);
    }
    // else Not actually a valid type and ignore.
  }

  /**
   * Includes the given file/directory in the glob.
   *
   * <p>{@code fileName} must exist.
   *
   * <p>{@code isDirectory} must be true iff the file is a directory.
   *
   * <p>Returns a {@link SkyKey} for a value that is needed to compute the files that will be added
   * to {@code matches}, or {@code null} if no additional value is needed. The returned value should
   * be opaquely passed to {@link #getSubdirMatchesFromSkyValue}.
   */
  @Nullable
  private static SkyKey getSkyKeyForSubdir(
      String fileName, GlobDescriptor glob, String subdirPattern) {
    if (subdirPattern == null) {
      if (glob.globberOperation() == Globber.Operation.FILES) {
        return null;
      }

      // For FILES_AND_DIRS and SUBPACKAGES we want to maybe inspect a
      // PackageLookupValue for it.
      return PackageLookupValue.key(
          PackageIdentifier.create(
              glob.getPackageId().getRepository(),
              glob.getPackageId()
                  .getPackageFragment()
                  .getRelative(glob.getSubdir())
                  .getRelative(fileName)));
    } else {
      // There is some more pattern to match. Get the glob for the subdirectory. Note that this
      // directory may also match directly in the case of a pattern that starts with "**", but that
      // match will be found in the subdirectory glob.
      return GlobValue.internalKey(
          glob.getPackageId(),
          glob.getPackageRoot(),
          glob.getSubdir().getRelative(fileName),
          subdirPattern,
          glob.globberOperation());
    }
  }

  /**
   * Returns an Object indicating a match was found for the given fileName in the given
   * valueRequested. The Object will be one of:
   *
   * <ul>
   *   <li>{@code null} if no matches for the given parameters exists
   *   <li>{@code NestedSet<PathFragment>} if a match exists, either because we are looking for
   *       files/directories or the SkyValue is a package and we're globbing for {@link
   *       Globber.Operation#SUBPACKAGES}
   * </ul>
   *
   * <p>{@code valueRequested} must be the SkyValue whose key was returned by {@link
   * #getSkyKeyForSubdir} for these parameters.
   */
  @Nullable
  private static Object getSubdirMatchesFromSkyValue(
      String fileName, GlobDescriptor glob, SkyValue valueRequested) {
    if (valueRequested instanceof GlobValue) {
      return ((GlobValueWithNestedSet) valueRequested).getMatchesInNestedSet();
    }

    Preconditions.checkState(
        valueRequested instanceof PackageLookupValue,
        "%s is not a GlobValue or PackageLookupValue (%s %s)",
        valueRequested,
        fileName,
        glob);

    PackageLookupValue packageLookupValue = (PackageLookupValue) valueRequested;
    if (packageLookupValue
        instanceof PackageLookupValue.IncorrectRepositoryReferencePackageLookupValue) {
      // This is a separate repository, so ignore it.
      return null;
    }

    boolean isSubpackagesOp = glob.globberOperation() == Globber.Operation.SUBPACKAGES;
    boolean pkgExists = packageLookupValue.packageExists();

    if (!isSubpackagesOp && pkgExists) {
      // We're in our repo and fileName is a package. Since we're not doing SUBPACKAGES listing, we
      // do not want to add it to the results.
      return null;
    } else if (isSubpackagesOp && !pkgExists) {
      // We're in our repo and the package exists. Since we're doing SUBPACKAGES listing, we do
      // want to add fileName to the results.
      return null;
    }

    // The  fileName should be added to the results of the glob.
    return glob.getSubdir().getRelative(fileName);
  }
}
