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

import com.google.common.base.Ascii;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeCommandUtils;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InfoItem;
import com.google.devtools.build.lib.runtime.commands.proto.BazelFlagsProto;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.HelpCommand.Code;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.util.StringUtilities;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionFilterDescriptions;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParser.HelpVerbosity;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** The 'blaze help' command, which prints all available commands as well as specific help pages. */
@Command(
    name = "help",
    buildPhase = NONE,
    options = {HelpCommand.Options.class},
    allowResidue = true,
    mustRunInWorkspace = false,
    shortDescription = "Prints help for commands, or the index.",
    completion = "command|{startup_options,target-syntax,info-keys}",
    help = "resource:help.txt")
public final class HelpCommand implements BlazeCommand {
  private static final Joiner SPACE_JOINER = Joiner.on(" ");

  /**
   * Only to be used to escape the internal hard-coded help texts when outputting HTML from help,
   * which don't pose a security risk.
   */
  private static final Escaper HTML_ESCAPER = HtmlEscapers.htmlEscaper();

  public static class Options extends OptionsBase {

    @Option(
        name = "help_verbosity",
        defaultValue = "medium",
        converter = Converters.HelpVerbosityConverter.class,
        documentationCategory = OptionDocumentationCategory.LOGGING,
        effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
        help = "Select the verbosity of the help command.")
    public OptionsParser.HelpVerbosity helpVerbosity;

    @Option(
        name = "long",
        abbrev = 'l',
        defaultValue = "null",
        expansion = {"--help_verbosity=long"},
        documentationCategory = OptionDocumentationCategory.LOGGING,
        effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
        help = "Show full description of each option, instead of just its name.")
    public Void showLongFormOptions;

    @Option(
        name = "short",
        defaultValue = "null",
        expansion = {"--help_verbosity=short"},
        documentationCategory = OptionDocumentationCategory.LOGGING,
        effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
        help = "Show only the names of the options, not their types or meanings.")
    public Void showShortFormOptions;
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    env.getEventBus().post(new NoBuildEvent());

    BlazeRuntime runtime = env.getRuntime();
    OutErr outErr = env.getReporter().getOutErr();
    Options helpOptions = options.getOptions(Options.class);
    if (options.getResidue().isEmpty()) {
      emitBlazeVersionInfo(outErr, runtime.getProductName());
      emitGenericHelp(outErr, runtime);
      return BlazeCommandResult.success();
    }
    if (options.getResidue().size() != 1) {
      String message = "You must specify exactly one command";
      env.getReporter().handle(Event.error(message));
      return createFailureResult(message, Code.MISSING_ARGUMENT);
    }
    String helpSubject = options.getResidue().get(0);
    String productName = runtime.getProductName();
    // Go through the custom subjects before going through Bazel commands.
    switch (helpSubject) {
      case "startup_options":
        emitBlazeVersionInfo(outErr, runtime.getProductName());
        emitStartupOptions(outErr, helpOptions.helpVerbosity, runtime);
        return BlazeCommandResult.success();
      case "target-syntax":
        emitBlazeVersionInfo(outErr, runtime.getProductName());
        emitTargetSyntaxHelp(outErr, productName);

        return BlazeCommandResult.success();
      case "info-keys":
        emitInfoKeysHelp(env, outErr);
        return BlazeCommandResult.success();
      case "completion":
        emitCompletionHelp(runtime, outErr);
        return BlazeCommandResult.success();
      case "flags-as-proto":
        emitFlagsAsProtoHelp(runtime, outErr);
        return BlazeCommandResult.success();
      case "everything-as-html":
        new HtmlEmitter(runtime).emit(outErr);
        return BlazeCommandResult.success();
      default: // fall out
    }

    BlazeCommand command = runtime.getCommandMap().get(helpSubject);
    if (command == null) {
      String message = "'" + helpSubject + "' is not a known command";
      env.getReporter().handle(Event.error(null, message));
      return createFailureResult(message, Code.COMMAND_NOT_FOUND);
    }
    emitBlazeVersionInfo(outErr, productName);
    outErr.printOut(
        BlazeCommandUtils.getUsage(
            command.getClass(),
            helpOptions.helpVerbosity,
            runtime.getBlazeModules(),
            runtime.getRuleClassProvider(),
            productName));

    return BlazeCommandResult.success();
  }

  private static void emitBlazeVersionInfo(OutErr outErr, String productName) {
    String releaseInfo = BlazeVersionInfo.instance().getReleaseName();
    String line = String.format("[%s %s]", productName, releaseInfo);
    outErr.printOut(String.format("%80s\n", line));
  }

  private void emitStartupOptions(
      OutErr outErr, HelpVerbosity helpVerbosity, BlazeRuntime runtime) {
    outErr.printOut(
        BlazeCommandUtils.expandHelpTopic(
            "startup_options",
            "resource:startup_options.txt",
            getClass(),
            BlazeCommandUtils.getStartupOptions(runtime.getBlazeModules()),
            helpVerbosity,
            runtime.getProductName()));
  }

  private static void emitCompletionHelp(BlazeRuntime runtime, OutErr outErr) {
    Map<String, BlazeCommand> commandsByName = getSortedCommands(runtime);

    outErr.printOutLn("BAZEL_COMMAND_LIST=\"" + SPACE_JOINER.join(commandsByName.keySet()) + "\"");

    outErr.printOutLn("BAZEL_INFO_KEYS=\"");
    for (String name : InfoCommand.getHardwiredInfoItemNames(runtime.getProductName())) {
      outErr.printOutLn(name);
    }
    outErr.printOutLn("\"");

    Consumer<OptionsParser> startupOptionVisitor =
        parser -> {
          outErr.printOutLn("BAZEL_STARTUP_OPTIONS=\"");
          outErr.printOut(parser.getOptionsCompletion());
          outErr.printOutLn("\"");
        };
    CommandOptionVisitor commandOptionVisitor =
        (commandName, commandAnnotation, parser) -> {
          String varName = CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_UNDERSCORE, commandName);
          if (!Strings.isNullOrEmpty(commandAnnotation.completion())) {
            outErr.printOutLn(
                "BAZEL_COMMAND_"
                    + varName
                    + "_ARGUMENT=\""
                    + commandAnnotation.completion()
                    + "\"");
          }
          outErr.printOutLn("BAZEL_COMMAND_" + varName + "_FLAGS=\"");
          outErr.printOut(parser.getOptionsCompletion());
          outErr.printOutLn("\"");
        };

    visitAllOptions(runtime, startupOptionVisitor, commandOptionVisitor);
  }

  private static void emitFlagsAsProtoHelp(BlazeRuntime runtime, OutErr outErr) {
    Map<String, BazelFlagsProto.FlagInfo.Builder> flags = new HashMap<>();

    Predicate<OptionDefinition> allOptions = option -> true;
    BiConsumer<String, OptionDefinition> visitor =
        (commandName, option) -> {
          if (ImmutableSet.copyOf(option.getOptionMetadataTags())
              .contains(OptionMetadataTag.INTERNAL)) {
            return;
          }
          BazelFlagsProto.FlagInfo.Builder info =
              flags.computeIfAbsent(option.getOptionName(), key -> createFlagInfo(option));
          info.addCommands(commandName);
        };
    Consumer<OptionsParser> startupOptionVisitor =
        parser -> parser.visitOptions(allOptions, option -> visitor.accept("startup", option));
    CommandOptionVisitor commandOptionVisitor =
        (commandName, commandAnnotation, parser) ->
            parser.visitOptions(allOptions, option -> visitor.accept(commandName, option));

    visitAllOptions(runtime, startupOptionVisitor, commandOptionVisitor);

    BazelFlagsProto.FlagCollection.Builder collectionBuilder =
        BazelFlagsProto.FlagCollection.newBuilder();
    for (BazelFlagsProto.FlagInfo.Builder info : flags.values()) {
      collectionBuilder.addFlagInfos(info);
    }
    outErr.printOut(Base64.getEncoder().encodeToString(collectionBuilder.build().toByteArray()));
  }

  private static BazelFlagsProto.FlagInfo.Builder createFlagInfo(OptionDefinition option) {
    BazelFlagsProto.FlagInfo.Builder flagBuilder = BazelFlagsProto.FlagInfo.newBuilder();
    flagBuilder.setName(option.getOptionName());
    flagBuilder.setHasNegativeFlag(option.usesBooleanValueSyntax());
    flagBuilder.setDocumentation(option.getHelpText());
    flagBuilder.setAllowsMultiple(option.allowsMultiple());
    flagBuilder.setRequiresValue(option.requiresValue());

    List<String> optionEffectTags =
        Arrays.stream(option.getOptionEffectTags())
            .map(Enum::toString)
            .collect(Collectors.toList());
    flagBuilder.addAllEffectTags(optionEffectTags);

    List<String> optionMetadataTags =
        Arrays.stream(option.getOptionMetadataTags())
            .map(Enum::toString)
            .collect(Collectors.toList());
    flagBuilder.addAllMetadataTags(optionMetadataTags);

    if (option.getDocumentationCategory() != null) {
      flagBuilder.setDocumentationCategory(option.getDocumentationCategory().toString());
    }

    if (option.getAbbreviation() != '\0') {
      flagBuilder.setAbbreviation(String.valueOf(option.getAbbreviation()));
    }
    return flagBuilder;
  }

  private static void visitAllOptions(
      BlazeRuntime runtime,
      Consumer<OptionsParser> startupOptionVisitor,
      CommandOptionVisitor commandOptionVisitor) {
    // First startup_options
    Iterable<BlazeModule> blazeModules = runtime.getBlazeModules();
    ConfiguredRuleClassProvider ruleClassProvider = runtime.getRuleClassProvider();
    Map<String, BlazeCommand> commandsByName = getSortedCommands(runtime);

    Iterable<Class<? extends OptionsBase>> options =
        BlazeCommandUtils.getStartupOptions(blazeModules);
    startupOptionVisitor.accept(OptionsParser.builder().optionsClasses(options).build());

    for (Map.Entry<String, BlazeCommand> e : commandsByName.entrySet()) {
      BlazeCommand command = e.getValue();
      Command annotation = command.getClass().getAnnotation(Command.class);
      options = BlazeCommandUtils.getOptions(command.getClass(), blazeModules, ruleClassProvider);
      commandOptionVisitor.visit(
          e.getKey(), annotation, OptionsParser.builder().optionsClasses(options).build());
    }
  }

  private static Map<String, BlazeCommand> getSortedCommands(BlazeRuntime runtime) {
    return ImmutableSortedMap.copyOf(runtime.getCommandMap());
  }

  private void emitTargetSyntaxHelp(OutErr outErr, String productName) {
    outErr.printOut(
        BlazeCommandUtils.expandHelpTopic(
            "target-syntax",
            "resource:target-syntax.txt",
            getClass(),
            ImmutableList.of(),
            OptionsParser.HelpVerbosity.MEDIUM,
            productName));
  }

  private static void emitInfoKeysHelp(CommandEnvironment env, OutErr outErr) {
    for (InfoItem item :
        InfoCommand.getInfoItemMap(env, OptionsParser.builder().build()).values()) {
      outErr.printOut(String.format("%-23s %s\n", item.getName(), item.getDescription()));
    }
  }

  private static void emitGenericHelp(OutErr outErr, BlazeRuntime runtime) {
    outErr.printOut(
        String.format("Usage: %s <command> <options> ...\n\n", runtime.getProductName()));
    outErr.printOut("Available commands:\n");

    Map<String, BlazeCommand> commandsByName = runtime.getCommandMap();
    List<String> namesInOrder = new ArrayList<>(commandsByName.keySet());
    Collections.sort(namesInOrder);

    for (String name : namesInOrder) {
      BlazeCommand command = commandsByName.get(name);
      Command annotation = command.getClass().getAnnotation(Command.class);
      if (annotation.hidden()) {
        continue;
      }

      String shortDescription =
          annotation.shortDescription().replace("%{product}", runtime.getProductName());
      outErr.printOut(String.format("  %-19s %s\n", name, shortDescription));
    }

    outErr.printOut("\n");
    outErr.printOut("Getting more help:\n");
    outErr.printOut(String.format("  %s help <command>\n", runtime.getProductName()));
    outErr.printOut("                   Prints help and options for <command>.\n");
    outErr.printOut(String.format("  %s help startup_options\n", runtime.getProductName()));
    outErr.printOut(
        String.format(
            "                   Options for the JVM hosting %s.\n", runtime.getProductName()));
    outErr.printOut(String.format("  %s help target-syntax\n", runtime.getProductName()));
    outErr.printOut("                   Explains the syntax for specifying targets.\n");
    outErr.printOut(String.format("  %s help info-keys\n", runtime.getProductName()));
    outErr.printOut("                   Displays a list of keys used by the info command.\n");
  }

  private static final class HtmlEmitter {
    private final BlazeRuntime runtime;

    private HtmlEmitter(BlazeRuntime runtime) {
      this.runtime = runtime;
    }

    private void emit(OutErr outErr) {
      Map<String, BlazeCommand> commandsByName = getSortedCommands(runtime);
      StringBuilder result = new StringBuilder();
      result.append("<h2>Commands</h2>\n");
      result.append("<table>\n");
      for (Map.Entry<String, BlazeCommand> e : commandsByName.entrySet()) {
        BlazeCommand command = e.getValue();
        Command annotation = command.getClass().getAnnotation(Command.class);
        if (annotation.hidden()) {
          continue;
        }
        String shortDescription =
            annotation.shortDescription().replace("%{product}", runtime.getProductName());

        result.append("<tr>\n");
        result.append(
            String.format(
                "  <td><a href=\"#%s\"><code>%s</code></a></td>\n", e.getKey(), e.getKey()));
        result.append("  <td>").append(HTML_ESCAPER.escape(shortDescription)).append("</td>\n");
        result.append("</tr>\n");
      }
      result.append("</table>\n");
      result.append("\n");

      result.append("<h2>Startup Options</h2>\n");
      appendOptionsHtml(result, BlazeCommandUtils.getStartupOptions(runtime.getBlazeModules()));
      result.append("\n");

      result.append("<h2><a name=\"common_options\">Options Common to all Commands</a></h2>\n");
      appendOptionsHtml(result, BlazeCommandUtils.getCommonOptions(runtime.getBlazeModules()));
      result.append("\n");

      for (Map.Entry<String, BlazeCommand> e : commandsByName.entrySet()) {
        result.append(
            String.format(
                "<h2><a name=\"%s\">%s Options</a></h2>\n",
                e.getKey(), StringUtilities.capitalize(e.getKey())));
        BlazeCommand command = e.getValue();
        Command annotation = command.getClass().getAnnotation(Command.class);
        if (annotation.hidden()) {
          continue;
        }
        List<String> inheritedCmdNames = new ArrayList<>();
        for (Class<? extends BlazeCommand> base : annotation.inheritsOptionsFrom()) {
          String name = base.getAnnotation(Command.class).name();
          inheritedCmdNames.add(String.format("<a href=\"#%s\">%s</a>", name, name));
        }
        if (!inheritedCmdNames.isEmpty()) {
          result.append("<p>Inherits all options from ");
          result.append(StringUtil.joinEnglishList(inheritedCmdNames, "and"));
          result.append(".</p>\n\n");
        }
        Set<Class<? extends OptionsBase>> options = new HashSet<>();
        Collections.addAll(options, annotation.options());
        for (BlazeModule blazeModule : runtime.getBlazeModules()) {
          Iterables.addAll(options, blazeModule.getCommandOptions(annotation));
        }
        List<String> optionsToIgnore = appendOptionsHtml(result, options);
        result.append("\n");

        // For now, we print all the configuration options in a list after all the non-configuration
        // options.
        if (annotation.usesConfigurationOptions()) {
          options.clear();
          Collections.addAll(options, annotation.options());
          options.addAll(runtime.getRuleClassProvider().getFragmentRegistry().getOptionsClasses());
          appendOptionsHtml(result, options, optionsToIgnore);
          result.append("\n");
        }
      }

      // Describe the tags once, any mentions above should link to these descriptions.
      String productName = runtime.getProductName();
      ImmutableMap<OptionEffectTag, String> effectTagDescriptions =
          OptionFilterDescriptions.getOptionEffectTagDescription(productName);
      result.append("<h3>Option Effect Tags</h3>\n");
      result.append("<table>\n");
      for (OptionEffectTag tag : OptionEffectTag.values()) {
        String tagDescription = effectTagDescriptions.get(tag);

        result.append("<tr>\n");
        result.append(
            String.format(
                "<td id=\"effect_tag_%s\"><code>%s</code></td>\n",
                tag, Ascii.toLowerCase(tag.name())));
        result.append(String.format("<td>%s</td>\n", HTML_ESCAPER.escape(tagDescription)));
        result.append("</tr>\n");
      }
      result.append("</table>\n");

      ImmutableMap<OptionMetadataTag, String> metadataTagDescriptions =
          OptionFilterDescriptions.getOptionMetadataTagDescription(productName);
      result.append("<h3>Option Metadata Tags</h3>\n");
      result.append("<table>\n");
      for (OptionMetadataTag tag : OptionMetadataTag.values()) {
        // skip the tags that are reserved for undocumented flags.
        if (!tag.equals(OptionMetadataTag.HIDDEN) && !tag.equals(OptionMetadataTag.INTERNAL)) {
          String tagDescription = metadataTagDescriptions.get(tag);

          result.append("<tr>\n");
          result.append(
              String.format(
                  "<td id=\"metadata_tag_%s\"><code>%s</code></td>\n",
                  tag, Ascii.toLowerCase(tag.name())));
          result.append(String.format("<td>%s</td>\n", HTML_ESCAPER.escape(tagDescription)));
          result.append("</tr>\n");
        }
      }
      result.append("</table>\n");

      outErr.printOut(result.toString());
    }

    // Returns the list of appended option names.
    @CanIgnoreReturnValue
    private List<String> appendOptionsHtml(
        StringBuilder result, Iterable<Class<? extends OptionsBase>> optionsClasses) {
      return appendOptionsHtml(result, optionsClasses, ImmutableList.of());
    }

    // Returns the list of appended option names.
    @CanIgnoreReturnValue
    private List<String> appendOptionsHtml(
        StringBuilder result,
        Iterable<Class<? extends OptionsBase>> optionsClasses,
        List<String> optionsToIgnore) {
      OptionsParser parser = OptionsParser.builder().optionsClasses(optionsClasses).build();
      String productName = runtime.getProductName();
      result.append(
          parser
              .describeOptionsHtml(HTML_ESCAPER, productName, optionsToIgnore)
              .replace("%{product}", productName));

      List<String> optionNames = new ArrayList<>();
      for (List<OptionDefinition> category : parser.getOptionsSortedByCategory().values()) {
        for (OptionDefinition option : category) {
          optionNames.add(option.getOptionName());
        }
      }
      return optionNames;
    }

  }

  /** A visitor for Blaze commands and their respective command line options. */
  @FunctionalInterface
  interface CommandOptionVisitor {

    /**
     * Visits a Blaze command by providing access to its name, its meta-data and its command line
     * options (via an {@link OptionsParser} instance).
     *
     * @param commandName name of the command, e.g. "help".
     * @param commandAnnotation {@link Command} that contains addition information about the
     *     command.
     * @param parser an {@link OptionsParser} instance that provides access to all options supported
     *     by the command.
     */
    void visit(String commandName, Command commandAnnotation, OptionsParser parser);
  }

  private static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.failureDetail(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setHelpCommand(FailureDetails.HelpCommand.newBuilder().setCode(detailedCode))
            .build());
  }
}
