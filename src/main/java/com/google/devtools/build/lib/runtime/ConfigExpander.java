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
package com.google.devtools.build.lib.runtime;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.common.options.OpaqueOptionsData;
import com.google.devtools.common.options.OptionValueDescription;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParser.ArgAndFallbackData;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.ParsedOptionDescription;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** Encapsulates logic for performing --config option expansion. */
final class ConfigExpander {

  private ConfigExpander() {}

  private static String getPlatformName() {
    switch (OS.getCurrent()) {
      case LINUX:
        return "linux";
      case DARWIN:
        return "macos";
      case WINDOWS:
        return "windows";
      case FREEBSD:
        return "freebsd";
      case OPENBSD:
        return "openbsd";
      default:
        return OS.getCurrent().getCanonicalName();
    }
  }

  /**
   * If --enable_platform_specific_config is true and the corresponding config definition exists, we
   * should enable the platform specific config.
   */
  private static boolean shouldEnablePlatformSpecificConfig(
      OptionValueDescription enablePlatformSpecificConfigDescription,
      ListMultimap<String, RcChunkOfArgs> commandToRcArgs,
      List<String> commandsToParse) {
    if (enablePlatformSpecificConfigDescription == null
        || !(boolean) enablePlatformSpecificConfigDescription.getValue()) {
      return false;
    }

    for (String commandName : commandsToParse) {
      String defaultConfigDef = commandName + ":" + getPlatformName();
      if (commandToRcArgs.containsKey(defaultConfigDef)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Expands --config options present in the requested commands using the options configuration
   * provided in commandToRcArgs.
   *
   * @param eventHandler collects any warnings encountered.
   * @param rcFileNotesConsumer collects any informational messages encountered.
   * @param optionsParser will parse the expanded --config representations.
   * @throws OptionsParsingException if a fatal problem with the configuration is encountered.
   */
  static void expandConfigOptions(
      EventHandler eventHandler,
      ListMultimap<String, RcChunkOfArgs> commandToRcArgs,
      String currentCommand,
      List<String> commandsToParse,
      Consumer<String> rcFileNotesConsumer,
      OptionsParser optionsParser,
      @Nullable OpaqueOptionsData fallbackData)
      throws OptionsParsingException {

    OptionValueDescription configValueDescription =
        optionsParser.getOptionValueDescription("config");
    if (configValueDescription != null && configValueDescription.getCanonicalInstances() != null) {
      // Find the base set of configs. This does not include the config options that might be
      // recursively included.
      ImmutableList<ParsedOptionDescription> configInstances =
          ImmutableList.copyOf(configValueDescription.getCanonicalInstances());

      // Expand the configs that are mentioned in the input. Flatten these expansions before parsing
      // them, to preserve order.
      for (ParsedOptionDescription configInstance : configInstances) {
        String configValueToExpand = (String) configInstance.getConvertedValue();
        var expansion =
            getExpansion(
                eventHandler,
                commandToRcArgs,
                commandsToParse,
                configValueToExpand,
                rcFileNotesConsumer,
                fallbackData);
        var ignoredArgs =
            optionsParser.parseArgsAsExpansionOfOption(
                configInstance,
                String.format("expanded from --config=%s", configValueToExpand),
                expansion);
        if (!ignoredArgs.isEmpty()) {
          rcFileNotesConsumer.accept(
              String.format(
                  "Ignored as unsupported by '%s': %s",
                  currentCommand, Joiner.on(' ').join(ignoredArgs)));
        }
      }
    }

    OptionValueDescription enablePlatformSpecificConfigDescription =
        optionsParser.getOptionValueDescription("enable_platform_specific_config");
    if (shouldEnablePlatformSpecificConfig(
        enablePlatformSpecificConfigDescription, commandToRcArgs, commandsToParse)) {
      var expansion =
          getExpansion(
              eventHandler,
              commandToRcArgs,
              commandsToParse,
              getPlatformName(),
              rcFileNotesConsumer,
              fallbackData);
      ParsedOptionDescription optionToExpand =
          Iterables.getOnlyElement(enablePlatformSpecificConfigDescription.getCanonicalInstances());
      var ignoredArgs =
          optionsParser.parseArgsAsExpansionOfOption(
              optionToExpand, "enabled by --enable_platform_specific_config", expansion);
      if (!ignoredArgs.isEmpty()) {
        rcFileNotesConsumer.accept(
            String.format(
                "Ignored as unsupported by '%s': %s",
                currentCommand, Joiner.on(' ').join(ignoredArgs)));
      }
    }

    // At this point, we've expanded everything, identify duplicates, if any, to warn about
    // re-application.
    CommonCommandOptions commonOptions = optionsParser.getOptions(CommonCommandOptions.class);
    if (!commonOptions.reportDuplicateOptionsAndConfigs) {
      return;
    }
    List<String> configs = commonOptions.configs;
    Set<String> configSet = new HashSet<>();
    LinkedHashSet<String> duplicateConfigs = new LinkedHashSet<>();
    for (String configValue : configs) {
      if (!configSet.add(configValue)) {
        duplicateConfigs.add(configValue);
      }
    }
    if (!duplicateConfigs.isEmpty()) {
      eventHandler.handle(
          Event.warn(
              String.format(
                  "The following configs were expanded more than once: %s. For repeatable flags, "
                      + "repeats are counted twice and may lead to unexpected behavior.",
                  duplicateConfigs)));
    }
  }

  private static List<ArgAndFallbackData> getExpansion(
      EventHandler eventHandler,
      ListMultimap<String, RcChunkOfArgs> commandToRcArgs,
      List<String> commandsToParse,
      String configToExpand,
      Consumer<String> rcFileNotesConsumer,
      @Nullable OpaqueOptionsData fallbackData)
      throws OptionsParsingException {
    LinkedHashSet<String> configAncestorSet = new LinkedHashSet<>();
    configAncestorSet.add(configToExpand);
    List<String> longestChain = new ArrayList<>();
    List<ArgAndFallbackData> finalExpansion =
        getExpansion(
            commandToRcArgs,
            commandsToParse,
            configAncestorSet,
            configToExpand,
            longestChain,
            rcFileNotesConsumer,
            fallbackData);

    // In order to prevent warning about a long chain of 13 configs at the 10, 11, 12, and 13
    // point, we identify the longest chain for this 'high-level' --config found and only warn
    // about it once. This may mean we missed a fork where each branch was independently long
    // enough to warn, but the single warning should convey the message reasonably.
    if (longestChain.size() >= 10) {
      eventHandler.handle(
          Event.warn(
              String.format(
                  "There is a recursive chain of configs %s configs long: %s. This seems "
                      + "excessive, and might be hiding errors.",
                  longestChain.size(), longestChain)));
    }
    return finalExpansion;
  }

  /**
   * @param configAncestorSet is the chain of configs that have led to this one getting expanded.
   *     This should only contain the configs that expanded, recursively, to this one, and should
   *     not contain "siblings," as it is used to detect cycles. {@code build:foo --config=bar},
   *     {@code build:bar --config=foo}, is a cycle, detected because this list will be [foo, bar]
   *     when we find another 'foo' to expand. However, {@code build:foo --config=bar}, {@code
   *     build:foo --config=bar} is not a cycle just because bar is expanded twice, and the 1st bar
   *     should not be in the parents list of the second bar.
   * @param longestChain will be populated with the longest inheritance chain of configs.
   */
  private static List<ArgAndFallbackData> getExpansion(
      ListMultimap<String, RcChunkOfArgs> commandToRcArgs,
      List<String> commandsToParse,
      LinkedHashSet<String> configAncestorSet,
      String configToExpand,
      List<String> longestChain,
      Consumer<String> rcFileNotesConsumer,
      @Nullable OpaqueOptionsData fallbackData)
      throws OptionsParsingException {
    List<ArgAndFallbackData> expansion = new ArrayList<>();
    boolean foundDefinition = false;
    // The expansion order of rc files is first by command priority, and then in the order the
    // rc files were read, respecting import statement placement.
    for (String commandToParse : commandsToParse) {
      String configDef = commandToParse + ":" + configToExpand;
      for (RcChunkOfArgs rcArgs : commandToRcArgs.get(configDef)) {
        foundDefinition = true;
        rcFileNotesConsumer.accept(
            String.format(
                "Found applicable config definition %s in file %s: %s",
                configDef, rcArgs.getRcFile(), String.join(" ", rcArgs.getArgs())));

        // For each arg in the rcARgs chunk, we first check if it is a config, and if so, expand
        // it in place. We avoid cycles by tracking the parents of this config.
        for (String arg : rcArgs.getArgs()) {
          expansion.add(
              new ArgAndFallbackData(
                  arg,
                  commandToParse.equals(BlazeOptionHandler.COMMON_PSEUDO_COMMAND)
                      ? fallbackData
                      : null));
          if (arg.length() >= 8 && arg.substring(0, 8).equals("--config")) {
            // We have a config. Because we don't want to worry about formatting,
            // we will only accept --config=value, and will not accept value on a following line.
            int charOfConfigValue = arg.indexOf('=');
            if (charOfConfigValue < 0) {
              throw new OptionsParsingException(
                  String.format(
                      "In file %s, the definition of config %s expands to another config "
                          + "that either has no value or is not in the form --config=value. For "
                          + "recursive config definitions, please do not provide the value in a "
                          + "separate token, such as in the form '--config value'.",
                      rcArgs.getRcFile(), configToExpand));
            }
            String newConfigValue = arg.substring(charOfConfigValue + 1);
            LinkedHashSet<String> extendedConfigAncestorSet =
                new LinkedHashSet<>(configAncestorSet);
            if (!extendedConfigAncestorSet.add(newConfigValue)) {
              throw new OptionsParsingException(
                  String.format(
                      "Config expansion has a cycle: config value %s expands to itself, "
                          + "see inheritance chain %s",
                      newConfigValue, extendedConfigAncestorSet));
            }
            if (extendedConfigAncestorSet.size() > longestChain.size()) {
              longestChain.clear();
              longestChain.addAll(extendedConfigAncestorSet);
            }

            expansion.addAll(
                getExpansion(
                    commandToRcArgs,
                    commandsToParse,
                    extendedConfigAncestorSet,
                    newConfigValue,
                    longestChain,
                    rcFileNotesConsumer,
                    fallbackData));
          }
        }
      }
    }

    if (!foundDefinition) {
      throw new OptionsParsingException(
          "Config value '" + configToExpand + "' is not defined in any .rc file");
    }
    return expansion;
  }
}
