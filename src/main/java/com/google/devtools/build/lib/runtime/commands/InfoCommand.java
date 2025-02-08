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
package com.google.devtools.build.lib.runtime.commands;

import static com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InfoItem;
import com.google.devtools.build.lib.runtime.commands.info.BlazeBinInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.BlazeGenfilesInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.BlazeTestlogsInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.BuildLanguageInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.CharacterEncodingInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.ClientEnv;
import com.google.devtools.build.lib.runtime.commands.info.CommittedHeapSizeInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.DefaultPackagePathInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.DefaultsPackageInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.ExecutionRootInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.GcCountInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.GcTimeInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.InfoItemHandler;
import com.google.devtools.build.lib.runtime.commands.info.InstallBaseInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.JavaHomeInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.JavaRuntimeInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.JavaVirtualMachineInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.LocalResourcesInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.MakeInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.MaxHeapSizeInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.OutputBaseInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.OutputPathInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.PackagePathInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.ReleaseInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.ServerLogInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.ServerPidInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.StarlarkSemanticsInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.UsedHeapSizeAfterGcInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.UsedHeapSizeInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.WorkerMetricsInfoItem;
import com.google.devtools.build.lib.runtime.commands.info.WorkspaceInfoItem;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.devtools.common.options.OptionsProvider;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Implementation of 'blaze info'. */
@Command(
    name = "info",
    buildPhase = NONE,
    allowResidue = true,
    binaryStdOut = true,
    help = "resource:info.txt",
    shortDescription = "Displays runtime info about the %{product} server.",
    options = {InfoCommand.Options.class},
    completion = "info-key",
    // We have InfoCommand inherit from {@link BuildCommand} because we want all
    // configuration defaults specified in ~/.blazerc for {@code build} to apply to
    // {@code info} too, even though it doesn't actually do a build.
    //
    // (Ideally there would be a way to make {@code info} inherit just the bare
    // minimum of relevant options from {@code build}, i.e. those that affect the
    // values it prints.  But there's no such mechanism.)
    inheritsOptionsFrom = {BuildCommand.class})
public class InfoCommand implements BlazeCommand {

  /** Options for the info command. */
  public static class Options extends OptionsBase {
    @Option(
        name = "show_make_env",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.LOGGING,
        effectTags = {OptionEffectTag.AFFECTS_OUTPUTS, OptionEffectTag.TERMINAL_OUTPUT},
        help = "Include the \"Make\" environment in the output.")
    public boolean showMakeEnvironment;
  }

  /**
   * Unchecked variant of {@link AbruptExitException}. Below, we need to throw from the Supplier
   * interface, which does not allow checked exceptions.
   */
  private static class AbruptExitRuntimeException extends RuntimeException {

    private final DetailedExitCode detailedExitCode;

    private AbruptExitRuntimeException(DetailedExitCode exitCode) {
      this.detailedExitCode = exitCode;
    }

    private DetailedExitCode getDetailedExitCode() {
      return detailedExitCode;
    }
  }

  private final Function<CommandEnvironment, InfoItemHandler> infoItemHandlerFactory;

  @VisibleForTesting
  public InfoCommand(Function<CommandEnvironment, InfoItemHandler> infoItemHandlerFactory) {
    this.infoItemHandlerFactory = infoItemHandlerFactory;
  }

  public InfoCommand() {
    this.infoItemHandlerFactory = InfoItemHandler::create;
  }

  @Override
  public BlazeCommandResult exec(
      final CommandEnvironment env, final OptionsParsingResult optionsParsingResult) {
    final BlazeRuntime runtime = env.getRuntime();
    env.getReporter().switchToAnsiAllowingHandler();
    Options infoOptions = optionsParsingResult.getOptions(Options.class);
    // Creating a BuildConfigurationValue is expensive and often unnecessary. Delay the creation
    // until
    // it is needed. We memoize so that it's cached intra-command (it's still created freshly on
    // every command since the configuration can change across commands).
    Supplier<BuildConfigurationValue> configurationSupplier =
        Suppliers.memoize(
            () -> {
              try (SilentCloseable c =
                  Profiler.instance().profile("Creating BuildConfigurationValue")) {
                // In order to be able to answer configuration-specific queries, we need to set up
                // the package path. Since info inherits all the build options, all the necessary
                // information is available here.
                ensureSyncPackageLoading(env, optionsParsingResult);
                // TODO(bazel-team): What if there are multiple configurations? [multi-config]
                BuildOptions buildOptions = runtime.createBuildOptions(optionsParsingResult);
                env.getSkyframeExecutor().setBaselineConfiguration(buildOptions, env.getReporter());
                return env.getSkyframeExecutor()
                    .getConfiguration(env.getReporter(), buildOptions, /* keepGoing= */ true);
              } catch (InvalidConfigurationException e) {
                env.getReporter().handle(Event.error(e.getMessage()));
                throw new AbruptExitRuntimeException(e.getDetailedExitCode());
              } catch (AbruptExitException e) {
                throw new AbruptExitRuntimeException(e.getDetailedExitCode());
              } catch (InterruptedException e) {
                env.getReporter().handle(Event.error("interrupted"));
                throw new AbruptExitRuntimeException(
                    InterruptedFailureDetails.detailedExitCode(
                        "command interrupted while syncing package loading"));
              }
            });

    Map<String, InfoItem> items = getInfoItemMap(env, optionsParsingResult);

    try (InfoItemHandler infoItemHandler = infoItemHandlerFactory.apply(env)) {
      if (infoOptions.showMakeEnvironment) {
        Map<String, String> makeEnv = configurationSupplier.get().getMakeEnvironment();
        for (Map.Entry<String, String> entry : makeEnv.entrySet()) {
          InfoItem item = new MakeInfoItem(entry.getKey(), entry.getValue());
          items.put(item.getName(), item);
        }
      }

      List<String> residue = optionsParsingResult.getResidue();
      env.getEventBus().post(new NoBuildEvent());
      if (!residue.isEmpty()) {
        ImmutableSet.Builder<String> unknownKeysBuilder = ImmutableSet.builder();
        for (String key : residue) {
          if (items.containsKey(key)) {
            try (SilentCloseable c = Profiler.instance().profile(key + ".infoItem")) {
              InfoItem infoItem = items.get(key);
              if (infoItem.needsSyncPackageLoading()) {
                ensureSyncPackageLoading(env, optionsParsingResult);
              }
              byte[] value = infoItem.get(configurationSupplier, env);
              infoItemHandler.addInfoItem(key, value, /* printKey= */ residue.size() > 1);
            }
          } else {
            unknownKeysBuilder.add(key);
          }
        }
        ImmutableSet<String> unknownKeys = unknownKeysBuilder.build();
        if (!unknownKeys.isEmpty()) {
          String message =
              "unknown key(s): "
                  + unknownKeys.stream()
                      .map(key -> "'" + key + "'")
                      .collect(Collectors.joining(", "));
          env.getReporter().handle(Event.error(message));
          return createFailureResult(
              message,
              ExitCode.COMMAND_LINE_ERROR,
              FailureDetails.InfoCommand.Code.KEY_NOT_RECOGNIZED);
        }
      } else { // print them all
        var unused = configurationSupplier.get(); // We'll need this later anyway
        for (InfoItem infoItem : items.values()) {
          if (infoItem.isHidden()) {
            continue;
          }
          if (infoItem.needsSyncPackageLoading()) {
            ensureSyncPackageLoading(env, optionsParsingResult);
          }
          try (SilentCloseable c = Profiler.instance().profile(infoItem.getName() + ".infoItem")) {
            infoItemHandler.addInfoItem(
                infoItem.getName(), infoItem.get(configurationSupplier, env), /* printKey= */ true);
          }
        }
      }
    } catch (AbruptExitException e) {
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    } catch (AbruptExitRuntimeException e) {
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    } catch (IOException e) {
      return createFailureResult(
          "Cannot write info block: " + e.getMessage(),
          ExitCode.LOCAL_ENVIRONMENTAL_ERROR,
          FailureDetails.InfoCommand.Code.ALL_INFO_WRITE_FAILURE);
    } catch (InterruptedException e) {
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode("info interrupted"));
    }
    return BlazeCommandResult.success();
  }

  private static void ensureSyncPackageLoading(CommandEnvironment env, OptionsProvider options)
      throws InterruptedException, AbruptExitException {
    if (!env.hasSyncedPackageLoading()) {
      env.syncPackageLoading(options);
    }
  }

  private static BlazeCommandResult createFailureResult(
      String message, ExitCode exitCode, FailureDetails.InfoCommand.Code detailedCode) {
    return BlazeCommandResult.detailedExitCode(
        DetailedExitCode.of(
            exitCode,
            FailureDetail.newBuilder()
                .setMessage(message)
                .setInfoCommand(FailureDetails.InfoCommand.newBuilder().setCode(detailedCode))
                .build()));
  }

  private static Map<String, InfoItem> getHardwiredInfoItemMap(
      OptionsParsingResult commandOptions, String productName) {
    List<InfoItem> hardwiredInfoItems =
        ImmutableList.<InfoItem>of(
            new WorkspaceInfoItem(),
            new InstallBaseInfoItem(),
            new OutputBaseInfoItem(productName),
            new ExecutionRootInfoItem(),
            new OutputPathInfoItem(),
            new ClientEnv(),
            new BlazeBinInfoItem(productName),
            new BlazeGenfilesInfoItem(productName),
            new BlazeTestlogsInfoItem(productName),
            new ReleaseInfoItem(productName),
            new ServerPidInfoItem(productName),
            new ServerLogInfoItem(productName),
            new PackagePathInfoItem(commandOptions),
            new UsedHeapSizeInfoItem(),
            new UsedHeapSizeAfterGcInfoItem(),
            new CommittedHeapSizeInfoItem(),
            new MaxHeapSizeInfoItem(),
            new GcTimeInfoItem(),
            new GcCountInfoItem(),
            new JavaRuntimeInfoItem(),
            new JavaVirtualMachineInfoItem(),
            new JavaHomeInfoItem(),
            new CharacterEncodingInfoItem(),
            new DefaultsPackageInfoItem(),
            new BuildLanguageInfoItem(),
            new DefaultPackagePathInfoItem(commandOptions),
            new StarlarkSemanticsInfoItem(commandOptions),
            new WorkerMetricsInfoItem(),
            new LocalResourcesInfoItem());
    ImmutableMap.Builder<String, InfoItem> result = new ImmutableMap.Builder<>();
    for (InfoItem item : hardwiredInfoItems) {
      result.put(item.getName(), item);
    }
    return result.buildOrThrow();
  }

  public static List<String> getHardwiredInfoItemNames(String productName) {
    ImmutableList.Builder<String> result = new ImmutableList.Builder<>();
    for (String name : InfoCommand.getHardwiredInfoItemMap(null, productName).keySet()) {
      result.add(name);
    }
    return result.build();
  }

  static Map<String, InfoItem> getInfoItemMap(
      CommandEnvironment env, OptionsParsingResult optionsParsingResult) {
    Map<String, InfoItem> items = new TreeMap<>(env.getRuntime().getInfoItems());
    items.putAll(getHardwiredInfoItemMap(optionsParsingResult, env.getRuntime().getProductName()));
    return items;
  }
}
