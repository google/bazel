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
package com.google.devtools.build.lib.bazel.commands;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.runtime.Command.BuildPhase.ANALYZES;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.analysis.NoBuildRequestFinishedEvent;
import com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionValue;
import com.google.devtools.build.lib.bazel.bzlmod.VendorManager;
import com.google.devtools.build.lib.bazel.commands.RepositoryFetcher.RepositoryFetcherException;
import com.google.devtools.build.lib.bazel.commands.TargetFetcher.TargetFetcherException;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.runtime.commands.TargetPatternsHelper;
import com.google.devtools.build.lib.runtime.commands.TestCommand;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.FetchCommand.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.NodeEntry;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Fetches external repositories into a specified directory.
 *
 * <p>This command is used to fetch external repositories into a specified directory. It can be used
 * to fetch all external repositories, a specific list of repositories or the repositories needed to
 * build a specific list of targets.
 *
 * <p>The command is used to create a vendor directory that can be used to build the project
 * offline.
 */
@Command(
    name = VendorCommand.NAME,
    buildPhase = ANALYZES,
    inheritsOptionsFrom = {TestCommand.class},
    options = {
      VendorOptions.class,
      PackageOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class
    },
    allowResidue = true,
    usesConfigurationOptions = true,
    help = "resource:vendor.txt",
    shortDescription =
        "Fetches external repositories into a folder specified by the flag --vendor_dir.")
public final class VendorCommand implements BlazeCommand {
  public static final String NAME = "vendor";

  private final Supplier<Map<String, String>> clientEnvironmentSupplier;
  @Nullable private VendorManager vendorManager = null;
  @Nullable private DownloadManager downloadManager;

  public VendorCommand(Supplier<Map<String, String>> clientEnvironmentSupplier) {
    this.clientEnvironmentSupplier = clientEnvironmentSupplier;
  }

  public void setDownloadManager(DownloadManager downloadManager) {
    this.downloadManager = downloadManager;
  }

  @Override
  public void editOptions(OptionsParser optionsParser) {
    TargetFetcher.injectNoBuildOption(optionsParser);
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeCommandResult invalidResult = validateOptions(env, options);
    if (invalidResult != null) {
      return invalidResult;
    }

    env.getEventBus()
        .post(
            new NoBuildEvent(
                env.getCommandName(),
                env.getCommandStartTime(),
                /* separateFinishedEvent= */ true,
                /* showProgress= */ true,
                env.getCommandId().toString()));

    // IS_VENDOR_COMMAND & VENDOR_DIR is already injected in "BazelRepositoryModule", we just need
    // to update this value for the delegator function to recognize this call is from VendorCommand
    env.getSkyframeExecutor()
        .injectExtraPrecomputedValues(
            ImmutableList.of(
                PrecomputedValue.injected(RepositoryDelegatorFunction.IS_VENDOR_COMMAND, true)));

    BlazeCommandResult result;
    VendorOptions vendorOptions = options.getOptions(VendorOptions.class);
    LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);
    Path vendorDirectory =
        env.getWorkspace().getRelative(options.getOptions(RepositoryOptions.class).vendorDirectory);
    this.vendorManager = new VendorManager(vendorDirectory);
    List<String> targets;
    try {
      targets = TargetPatternsHelper.readFrom(env, options);
    } catch (TargetPatternsHelper.TargetPatternsHelperException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }
    try {
      if (!targets.isEmpty()) {
        if (!vendorOptions.repos.isEmpty()) {
          return createFailedBlazeCommandResult(
              env.getReporter(), "Target patterns and --repo cannot both be specified");
        }
        result = vendorTargets(env, options, targets);
      } else if (!vendorOptions.repos.isEmpty()) {
        result = vendorRepos(env, threadsOption, vendorOptions.repos);
      } else {
        result = vendorAll(env, threadsOption);
      }
    } catch (InterruptedException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Vendor interrupted: " + e.getMessage());
    } catch (IOException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Error while vendoring repos: " + e.getMessage());
    }

    env.getEventBus()
        .post(
            new NoBuildRequestFinishedEvent(
                result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()));
    return result;
  }

  @Nullable
  private BlazeCommandResult validateOptions(CommandEnvironment env, OptionsParsingResult options) {
    if (options.getOptions(RepositoryOptions.class).vendorDirectory == null) {
      return createFailedBlazeCommandResult(
          env.getReporter(),
          Code.OPTIONS_INVALID,
          "You cannot run the vendor command without specifying --vendor_dir");
    }
    if (!options.getOptions(PackageOptions.class).fetch) {
      return createFailedBlazeCommandResult(
          env.getReporter(),
          Code.OPTIONS_INVALID,
          "You cannot run the vendor command with --nofetch");
    }
    return null;
  }

  private BlazeCommandResult vendorAll(
      CommandEnvironment env, LoadingPhaseThreadsOption threadsOption)
      throws InterruptedException, IOException {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setParallelism(threadsOption.threads)
            .setEventHandler(env.getReporter())
            .build();

    SkyKey fetchKey = BazelFetchAllValue.key(/* configureEnabled= */ false);
    EvaluationResult<SkyValue> evaluationResult =
        env.getSkyframeExecutor().prepareAndGet(ImmutableSet.of(fetchKey), evaluationContext);
    if (evaluationResult.hasError()) {
      Exception e = evaluationResult.getError().getException();
      return createFailedBlazeCommandResult(
          env.getReporter(),
          e != null ? e.getMessage() : "Unexpected error during fetching all external deps.");
    }

    BazelFetchAllValue fetchAllValue = (BazelFetchAllValue) evaluationResult.get(fetchKey);
    env.getReporter().handle(Event.info("Vendoring all external repositories..."));
    vendor(env, fetchAllValue.reposToVendor());
    env.getReporter().handle(Event.info("All external dependencies vendored successfully."));
    return BlazeCommandResult.success();
  }

  private BlazeCommandResult vendorRepos(
      CommandEnvironment env, LoadingPhaseThreadsOption threadsOption, List<String> repos)
      throws InterruptedException, IOException {
    ImmutableMap<RepositoryName, RepositoryDirectoryValue> repositoryNamesAndValues;
    try {
      repositoryNamesAndValues = RepositoryFetcher.fetchRepos(repos, env, threadsOption);
    } catch (RepositoryMappingResolutionException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Invalid repo name: " + e.getMessage(), e.getDetailedExitCode());
    } catch (RepositoryFetcherException e) {
      return createFailedBlazeCommandResult(env.getReporter(), e.getMessage());
    }

    // Split repos to found and not found, vendor found ones and report others
    ImmutableList.Builder<RepositoryName> reposToVendor = ImmutableList.builder();
    List<String> notFoundRepoErrors = new ArrayList<>();
    for (Entry<RepositoryName, RepositoryDirectoryValue> entry :
        repositoryNamesAndValues.entrySet()) {
      if (entry.getValue().repositoryExists()) {
        if (!entry.getValue().excludeFromVendoring()) {
          reposToVendor.add(entry.getKey());
        }
      } else {
        notFoundRepoErrors.add(entry.getValue().getErrorMsg());
      }
    }

    env.getReporter().handle(Event.info("Vendoring repositories..."));
    vendor(env, reposToVendor.build());
    if (!notFoundRepoErrors.isEmpty()) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Vendoring some repos failed with errors: " + notFoundRepoErrors);
    }
    env.getReporter().handle(Event.info("All requested repos vendored successfully."));
    return BlazeCommandResult.success();
  }

  private BlazeCommandResult vendorTargets(
      CommandEnvironment env, OptionsParsingResult options, List<String> targets)
      throws InterruptedException, IOException {
    // Call fetch which runs build to have the targets graph and configuration set
    BuildResult buildResult;
    try {
      buildResult = TargetFetcher.fetchTargets(env, options, targets);
    } catch (TargetFetcherException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), Code.QUERY_EVALUATION_ERROR, e.getMessage());
    }

    // Traverse the graph created from build to collect repos and vendor them
    ImmutableList<SkyKey> targetKeys =
        buildResult.getActualTargets().stream()
            .map(ConfiguredTarget::getLookupKey)
            .collect(toImmutableList());
    InMemoryGraph inMemoryGraph = env.getSkyframeExecutor().getEvaluator().getInMemoryGraph();
    ImmutableSet<RepositoryName> reposToVendor = collectReposFromTargets(inMemoryGraph, targetKeys);

    env.getReporter().handle(Event.info("Vendoring dependencies for targets..."));
    vendor(env, reposToVendor.asList());
    env.getReporter()
        .handle(
            Event.info(
                "All external dependencies for the requested targets vendored successfully."));
    return BlazeCommandResult.success();
  }

  private ImmutableSet<RepositoryName> collectReposFromTargets(
      InMemoryGraph inMemoryGraph, ImmutableList<SkyKey> targetKeys) throws InterruptedException {
    ImmutableSet.Builder<RepositoryName> repos = ImmutableSet.builder();
    Queue<SkyKey> nodes = new ArrayDeque<>(targetKeys);
    Set<SkyKey> visited = new HashSet<>();
    while (!nodes.isEmpty()) {
      SkyKey key = nodes.remove();
      visited.add(key);
      NodeEntry nodeEntry = inMemoryGraph.get(null, Reason.VENDOR_EXTERNAL_REPOS, key);
      if (nodeEntry.getValue() instanceof RepositoryDirectoryValue repoDirValue
          && repoDirValue.repositoryExists()
          && !repoDirValue.excludeFromVendoring()) {
        repos.add((RepositoryName) key.argument());
      }
      for (SkyKey depKey : nodeEntry.getDirectDeps()) {
        if (!visited.contains(depKey)) {
          nodes.add(depKey);
        }
      }
    }
    return repos.build();
  }

  /**
   * Copies the fetched repos from the external cache into the vendor directory, unless the repo is
   * ignored or was already vendored and up-to-date
   */
  private void vendor(CommandEnvironment env, ImmutableList<RepositoryName> reposToVendor)
      throws IOException, InterruptedException {
    Objects.requireNonNull(vendorManager);

    // 1. Vendor registry files
    BazelModuleResolutionValue moduleResolutionValue =
        (BazelModuleResolutionValue)
            env.getSkyframeExecutor()
                .getEvaluator()
                .getExistingValue(BazelModuleResolutionValue.KEY);
    ImmutableMap<String, Optional<Checksum>> registryFiles =
        Objects.requireNonNull(moduleResolutionValue).getRegistryFileHashes();

    // vendorPathToURL is a map of
    //  key: a vendor path string converted to lower case
    //  value: a URL string
    // This map is for detecting potential rare vendor path conflicts, such as:
    //  http://foo.bar.com/BCR vs http://foo.bar.com/bcr => conflict vendor paths on
    // case-insensitive system
    //  http://foo.bar.com/bcr vs http://foo.bar.com:8081/bcr => conflict vendor path because port
    // number is ignored in vendor path
    // The user has to update the Bazel registries this if such conflicts occur.
    Map<String, String> vendorPathToUrl = new HashMap<>();
    for (Entry<String, Optional<Checksum>> entry : registryFiles.entrySet()) {
      URL url = URI.create(entry.getKey()).toURL();
      if (url.getProtocol().equals("file")) {
        continue;
      }

      String outputPath = vendorManager.getVendorPathForUrl(url).getPathString();
      String outputPathLowerCase = outputPath.toLowerCase(Locale.ROOT);
      if (vendorPathToUrl.containsKey(outputPathLowerCase)) {
        String previousUrl = vendorPathToUrl.get(outputPathLowerCase);
        throw new IOException(
            String.format(
                "Vendor paths conflict detected for registry URLs:\n"
                    + "    %s => %s\n"
                    + "    %s => %s\n"
                    + "Their output paths are either the same or only differ by case, which will"
                    + " cause conflict on case insensitive file systems, please fix by changing the"
                    + " registry URLs!",
                previousUrl,
                vendorManager.getVendorPathForUrl(URI.create(previousUrl).toURL()).getPathString(),
                entry.getKey(),
                outputPath));
      }

      Optional<Checksum> checksum = entry.getValue();
      if (!vendorManager.isUrlVendored(url)
          // Only vendor a registry URL when its checksum exists, otherwise the URL should be
          // recorded as "not found" in moduleResolutionValue.getRegistryFileHashes()
          && checksum.isPresent()) {
        try {
          vendorManager.vendorRegistryUrl(
              url,
              downloadManager.downloadAndReadOneUrlForBzlmod(
                  url, env.getReporter(), clientEnvironmentSupplier.get(), checksum));
        } catch (IOException e) {
          throw new IOException(
              String.format(
                  "Failed to vendor registry URL %s at %s: %s", url, outputPath, e.getMessage()),
              e.getCause());
        }
      }

      vendorPathToUrl.put(outputPathLowerCase, entry.getKey());
    }

    // 2. Vendor repos
    Path externalPath =
        env.getDirectories()
            .getOutputBase()
            .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);
    vendorManager.vendorRepos(externalPath, reposToVendor);

    // 3. Invalidate RepositoryDirectoryValue for vendored repos.
    env.getSkyframeExecutor()
        .getEvaluator()
        .delete(
            k ->
                k.functionName().equals(SkyFunctions.REPOSITORY_DIRECTORY)
                    && reposToVendor.contains(k.argument()));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, Code fetchCommandCode, String message) {
    return createFailedBlazeCommandResult(
        reporter,
        message,
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setFetchCommand(
                    FailureDetails.FetchCommand.newBuilder().setCode(fetchCommandCode).build())
                .build()));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String errorMessage) {
    return createFailedBlazeCommandResult(
        reporter, errorMessage, InterruptedFailureDetails.detailedExitCode(errorMessage));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String message, DetailedExitCode exitCode) {
    reporter.handle(Event.error(message));
    return BlazeCommandResult.detailedExitCode(exitCode);
  }
}
