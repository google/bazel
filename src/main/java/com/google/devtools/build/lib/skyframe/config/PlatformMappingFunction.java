// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe.config;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.MissingInputFileException;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.Label.PackageContext;
import com.google.devtools.build.lib.cmdline.Label.RepoContext;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.server.FailureDetails.BuildConfiguration;
import com.google.devtools.build.lib.server.FailureDetails.BuildConfiguration.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * Function that reads the contents of a mapping file specified in {@code --platform_mappings} and
 * parses them for use in a {@link PlatformMappingValue}.
 *
 * <p>Note that this class only parses the mapping-file specific format, parsing (and validation) of
 * flags contained therein is left to the invocation of {@link PlatformMappingValue#map}.
 */
public final class PlatformMappingFunction implements SkyFunction {

  private final ImmutableSet<Class<? extends FragmentOptions>> optionsClasses;

  public PlatformMappingFunction(ImmutableSet<Class<? extends FragmentOptions>> optionsClasses) {
    this.optionsClasses = checkNotNull(optionsClasses);
  }

  @Nullable
  @Override
  public PlatformMappingValue compute(SkyKey skyKey, Environment env)
      throws PlatformMappingFunctionException, InterruptedException {
    PlatformMappingKey platformMappingKey = (PlatformMappingKey) skyKey.argument();
    PathFragment workspaceRelativeMappingPath =
        platformMappingKey.getWorkspaceRelativeMappingPath();

    RepositoryMappingValue mainRepositoryMappingValue =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN));
    if (mainRepositoryMappingValue == null) {
      return null;
    }
    RepoContext mainRepoContext =
        RepoContext.of(RepositoryName.MAIN, mainRepositoryMappingValue.repositoryMapping());

    PathPackageLocator pkgLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
    if (pkgLocator == null) {
      return null;
    }

    ImmutableList<Root> pathEntries = pkgLocator.getPathEntries();
    for (Root root : pathEntries) {
      RootedPath rootedMappingPath = RootedPath.toRootedPath(root, workspaceRelativeMappingPath);
      FileValue fileValue = (FileValue) env.getValue(FileValue.key(rootedMappingPath));
      if (fileValue == null) {
        return null;
      }

      if (!fileValue.exists()) {
        continue;
      }
      if (fileValue.isDirectory()) {
        throw new PlatformMappingFunctionException(
            new MissingInputFileException(
                createFailureDetail(
                    String.format(
                        "--platform_mappings was set to '%s' relative to the top-level workspace"
                            + " '%s' but that path refers to a directory, not a file",
                        workspaceRelativeMappingPath, root),
                    Code.PLATFORM_MAPPINGS_FILE_IS_DIRECTORY),
                Location.BUILTIN));
      }

      List<String> lines;
      try {
        lines =
            FileSystemUtils.readLines(fileValue.realRootedPath(rootedMappingPath).asPath(), UTF_8);
      } catch (IOException e) {
        throw new PlatformMappingFunctionException(e);
      }

      Mappings parsed;
      try {
        parsed = parse(env, lines, mainRepoContext);
        if (parsed == null) {
          return null;
        }
      } catch (PlatformMappingParsingException e) {
        throw new PlatformMappingFunctionException(e);
      }
      return parsed.toPlatformMappingValue(optionsClasses);
    }

    if (!platformMappingKey.wasExplicitlySetByUser()) {
      // If no flag was passed and the default mapping file does not exist treat this as if the
      // mapping file was empty rather than an error.
      return new PlatformMappingValue(ImmutableMap.of(), ImmutableMap.of(), ImmutableSet.of());
    }
    throw new PlatformMappingFunctionException(
        new MissingInputFileException(
            createFailureDetail(
                String.format(
                    "--platform_mappings was set to '%s' but no such file exists relative to the "
                        + "package path roots, '%s'",
                    workspaceRelativeMappingPath, pathEntries),
                Code.PLATFORM_MAPPINGS_FILE_NOT_FOUND),
            Location.BUILTIN));
  }

  private static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setBuildConfiguration(BuildConfiguration.newBuilder().setCode(detailedCode))
        .build();
  }

  /** Parses the given lines, returns null if not all Skyframe deps are ready. */
  @VisibleForTesting
  @Nullable
  static Mappings parse(Environment env, List<String> lines, RepoContext mainRepoContext)
      throws PlatformMappingParsingException, InterruptedException {
    PeekingIterator<String> it =
        Iterators.peekingIterator(
            lines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .iterator());

    if (!it.hasNext()) {
      return new Mappings(ImmutableMap.of(), ImmutableMap.of());
    }

    if (!it.peek().equalsIgnoreCase("platforms:") && !it.peek().equalsIgnoreCase("flags:")) {
      throw parsingException("Expected 'platforms:' or 'flags:' but got " + it.peek());
    }

    ImmutableMap<Label, ParsedFlagsValue> platformsToFlags = ImmutableMap.of();
    ImmutableMap<ParsedFlagsValue, Label> flagsToPlatforms = ImmutableMap.of();

    if (it.peek().equalsIgnoreCase("platforms:")) {
      it.next();
      platformsToFlags = readPlatformsToFlags(it, env, mainRepoContext);
      if (platformsToFlags == null) {
        return null;
      }
    }

    if (it.hasNext()) {
      String line = it.next();
      if (!line.equalsIgnoreCase("flags:")) {
        throw parsingException("Expected 'flags:' but got " + line);
      }
      flagsToPlatforms = readFlagsToPlatforms(it, env, mainRepoContext);
      if (flagsToPlatforms == null) {
        return null;
      }
    }

    if (it.hasNext()) {
      throw parsingException("Expected end of file but got " + it.next());
    }
    return new Mappings(platformsToFlags, flagsToPlatforms);
  }

  /**
   * Converts a set of native and Starlark flag settings to a {@link ParsedFlagsValue}, or returns
   * null if not all Skyframe deps are ready.
   */
  @Nullable
  private static ParsedFlagsValue parseFlags(
      ImmutableList<String> rawFlags, Environment env, RepoContext mainRepoContext)
      throws PlatformMappingParsingException, InterruptedException {
    PackageContext rootPackage = mainRepoContext.rootPackage();
    ParsedFlagsValue.Key parsedFlagsKey = ParsedFlagsValue.Key.create(rawFlags, rootPackage);
    try {
      return (ParsedFlagsValue) env.getValueOrThrow(parsedFlagsKey, OptionsParsingException.class);
    } catch (OptionsParsingException e) {
      throw new PlatformMappingParsingException(e);
    }
  }

  /**
   * Returns a parsed {@code platform -> flags setting}, or null if not all Skyframe deps are ready
   */
  @Nullable
  private static ImmutableMap<Label, ParsedFlagsValue> readPlatformsToFlags(
      PeekingIterator<String> it, Environment env, RepoContext mainRepoContext)
      throws PlatformMappingParsingException, InterruptedException {
    ImmutableMap.Builder<Label, ParsedFlagsValue> platformsToFlags = ImmutableMap.builder();
    boolean needSkyframeDeps = false;
    while (it.hasNext() && !it.peek().equalsIgnoreCase("flags:")) {
      Label platform = readPlatform(it, mainRepoContext);
      ImmutableList<String> flags = readFlags(it);
      ParsedFlagsValue parsedFlags = parseFlags(flags, env, mainRepoContext);
      if (parsedFlags == null) {
        needSkyframeDeps = true;
      } else {
        platformsToFlags.put(platform, parsedFlags);
      }
    }

    if (needSkyframeDeps) {
      return null;
    }

    try {
      return platformsToFlags.buildOrThrow();
    } catch (IllegalArgumentException e) {
      throw parsingException(
          "Got duplicate platform entries but each platform key must be unique", e);
    }
  }

  /**
   * Returns a parsed {@code flags -> platform setting}, or null if not all Skyframe deps are ready
   */
  @Nullable
  private static ImmutableMap<ParsedFlagsValue, Label> readFlagsToPlatforms(
      PeekingIterator<String> it, Environment env, RepoContext mainRepoContext)
      throws PlatformMappingParsingException, InterruptedException {
    ImmutableMap.Builder<ParsedFlagsValue, Label> flagsToPlatforms = ImmutableMap.builder();
    boolean needSkyframeDeps = false;
    while (it.hasNext() && it.peek().startsWith("--")) {
      ImmutableList<String> flags = readFlags(it);
      Label platform = readPlatform(it, mainRepoContext);

      ParsedFlagsValue parsedFlags = parseFlags(flags, env, mainRepoContext);
      if (parsedFlags == null) {
        needSkyframeDeps = true;
      } else {
        flagsToPlatforms.put(parsedFlags, platform);
      }
    }

    if (needSkyframeDeps) {
      return null;
    }

    try {
      return flagsToPlatforms.buildOrThrow();
    } catch (IllegalArgumentException e) {
      throw parsingException("Got duplicate flags entries but each flags key must be unique", e);
    }
  }

  private static Label readPlatform(PeekingIterator<String> it, RepoContext mainRepoContext)
      throws PlatformMappingParsingException {
    if (!it.hasNext()) {
      throw parsingException("Expected platform label but got end of file");
    }

    String line = it.next();
    try {
      return Label.parseWithRepoContext(line, mainRepoContext);
    } catch (LabelSyntaxException e) {
      throw parsingException("Expected platform label but got " + line, e);
    }
  }

  private static ImmutableList<String> readFlags(PeekingIterator<String> it)
      throws PlatformMappingParsingException {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    // Note: Short form flags are not supported.
    while (it.hasNext() && it.peek().startsWith("--")) {
      flags.add(it.next());
    }
    ImmutableList<String> parsedFlags = flags.build();
    if (parsedFlags.isEmpty()) {
      throw parsingException(
          it.hasNext()
              ? "Expected a standard format flag (starting with --) but got " + it.peek()
              : "Expected a flag but got end of file");
    }
    return parsedFlags;
  }

  private static PlatformMappingParsingException parsingException(String message) {
    return parsingException(message, /* cause= */ null);
  }

  private static PlatformMappingParsingException parsingException(String message, Exception cause) {
    return new PlatformMappingParsingException(message, cause);
  }

  /**
   * Simple data holder to make testing easier. Only for use internal to this file/tests thereof.
   */
  @VisibleForTesting
  static final class Mappings {
    final ImmutableMap<Label, ParsedFlagsValue> platformsToFlags;
    final ImmutableMap<ParsedFlagsValue, Label> flagsToPlatforms;

    Mappings(
        ImmutableMap<Label, ParsedFlagsValue> platformsToFlags,
        ImmutableMap<ParsedFlagsValue, Label> flagsToPlatforms) {
      this.platformsToFlags = platformsToFlags;
      this.flagsToPlatforms = flagsToPlatforms;
    }

    PlatformMappingValue toPlatformMappingValue(
        ImmutableSet<Class<? extends FragmentOptions>> optionsClasses) {
      return new PlatformMappingValue(platformsToFlags, flagsToPlatforms, optionsClasses);
    }
  }

  @VisibleForTesting
  static final class PlatformMappingFunctionException extends SkyFunctionException {

    PlatformMappingFunctionException(MissingInputFileException cause) {
      super(new PlatformMappingException(cause), Transience.PERSISTENT);
    }

    PlatformMappingFunctionException(IOException cause) {
      super(new PlatformMappingException(cause), Transience.TRANSIENT);
    }

    PlatformMappingFunctionException(PlatformMappingParsingException cause) {
      super(new PlatformMappingException(cause), Transience.PERSISTENT);
    }
  }
}
