// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithOrderConstraint;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import com.google.devtools.build.lib.runtime.proto.CommandLineOuterClass.ChunkList;
import com.google.devtools.build.lib.runtime.proto.CommandLineOuterClass.CommandLine;
import com.google.devtools.build.lib.runtime.proto.CommandLineOuterClass.CommandLineSection;
import com.google.devtools.build.lib.runtime.proto.CommandLineOuterClass.Option;
import com.google.devtools.build.lib.runtime.proto.CommandLineOuterClass.OptionList;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.util.OptionsUtils.OptionSensitivity;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionPriority;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.devtools.common.options.ParsedOptionDescription;
import com.google.devtools.common.options.proto.OptionFilters;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** A build event reporting the command line by which Bazel was invoked. */
public abstract class CommandLineEvent implements BuildEventWithOrderConstraint {

  @Override
  public Collection<BuildEventId> getChildrenEvents() {
    return ImmutableList.of();
  }

  @Override
  public Collection<BuildEventId> postedAfter() {
    return ImmutableList.of(BuildEventIdUtil.buildStartedId());
  }

  /** A CommandLineEvent that stores functions and values common to both Bazel command lines. */
  public abstract static class BazelCommandLineEvent extends CommandLineEvent {
    protected final String productName;
    protected final OptionsParsingResult activeStartupOptions;
    protected final String commandName;
    protected final OptionsParsingResult commandOptions;

    BazelCommandLineEvent(
        String productName,
        OptionsParsingResult activeStartupOptions,
        String commandName,
        OptionsParsingResult commandOptions) {
      this.productName = productName;
      this.activeStartupOptions = activeStartupOptions;
      this.commandName = commandName;
      this.commandOptions = commandOptions;
    }

    CommandLineSection getExecutableSection() {
      return CommandLineSection.newBuilder()
          .setSectionLabel("executable")
          .setChunkList(ChunkList.newBuilder().addChunk(productName))
          .build();
    }

    CommandLineSection getCommandSection() {
      return CommandLineSection.newBuilder()
          .setSectionLabel("command")
          .setChunkList(ChunkList.newBuilder().addChunk(commandName))
          .build();
    }

    /**
     * Convert an array of tags to the equivalent proto-generated enum values.
     *
     * <p>The proto type is duplicate in order to not burden the OptionsParser with the proto
     * dependency. A test guarantees that the two enum types are kept in sync with matching indices.
     */
    static List<OptionFilters.OptionEffectTag> getProtoEffectTags(OptionEffectTag[] tagArray) {
      ArrayList<OptionFilters.OptionEffectTag> effectTags = new ArrayList<>(tagArray.length);
      for (OptionEffectTag tag : tagArray) {
        effectTags.add(OptionFilters.OptionEffectTag.forNumber(tag.getValue()));
      }
      return effectTags;
    }

    /**
     * Convert an array of tags to the equivalent proto-generated enum values.
     *
     * <p>The proto type is duplicate in order to not burden the OptionsParser with the proto
     * dependency. A test guarantees that the two enum types are kept in sync with matching indices.
     */
    static List<OptionFilters.OptionMetadataTag> getProtoMetadataTags(
        OptionMetadataTag[] tagArray) {
      ArrayList<OptionFilters.OptionMetadataTag> metadataTags = new ArrayList<>(tagArray.length);
      for (OptionMetadataTag tag : tagArray) {
        metadataTags.add(OptionFilters.OptionMetadataTag.forNumber(tag.getValue()));
      }
      return metadataTags;
    }

    List<Option> getOptionListFromParsedOptionDescriptions(
        List<ParsedOptionDescription> parsedOptionDescriptions) {
      List<Option> options = new ArrayList<>();
      for (ParsedOptionDescription parsedOption : parsedOptionDescriptions) {
        options.add(
            createOption(
                parsedOption.getOptionDefinition(),
                parsedOption.getCommandLineForm(),
                parsedOption.getUnconvertedValue()));
      }
      return options;
    }

    private Option createOption(
        OptionDefinition optionDefinition, String combinedForm, @Nullable String value) {
      OptionSensitivity sensitivity = OptionsUtils.getOptionSensitivity(optionDefinition.getOptionName());

      Option.Builder option = Option.newBuilder();
      option.setCombinedForm(OptionsUtils.maybeScrubCombinedForm(sensitivity, combinedForm));
      option.setOptionName(optionDefinition.getOptionName());
      if (value != null) {
        option.setOptionValue(OptionsUtils.maybeScrubAssignment(sensitivity, value));
      }
      option.addAllEffectTags(getProtoEffectTags(optionDefinition.getOptionEffectTags()));
      option.addAllMetadataTags(getProtoMetadataTags(optionDefinition.getOptionMetadataTags()));
      return option.build();
    }

    Option createStarlarkOption(String starlarkFlag, @Nullable Object value) {
      String combinedForm = String.format("--%s=%s", starlarkFlag, value);
      Option.Builder option = Option.newBuilder();
      option.setCombinedForm(combinedForm);
      option.setOptionName(starlarkFlag);
      if (value != null) {
        option.setOptionValue(String.valueOf(value));
      }
      return option.build();
    }

    /**
     * Returns the startup option section of the command line for the startup options as the server
     * received them at its startup. Since not all client options get passed to the server as
     * startup options, this might not represent the actual list of startup options as the user
     * provided them.
     */
    CommandLineSection getActiveStartupOptions() {
      return CommandLineSection.newBuilder()
          .setSectionLabel("startup options")
          .setOptionList(
              OptionList.newBuilder()
                  .addAllOption(
                      getOptionListFromParsedOptionDescriptions(
                          activeStartupOptions.asCompleteListOfParsedOptions())))
          .build();
    }

    /**
     * Returns the final part of the command line, containing whatever was left after obtaining the
     * command and its options.
     */
    CommandLineSection getResidual() {
      // Potential further split: how the residual, if any is accepted, gets interpreted depends on
      // the command. For example, for build commands, we might want to consider separating out
      // project files, as in runtime.commands.ProjectFileSupport. To properly report this, we would
      // need to let the command customize how the residual is listed. This catch-all could serve
      // as a default in this case.
      CommandLineSection.Builder builder =
          CommandLineSection.newBuilder().setSectionLabel("residual");
      if (commandName.equals("run")
          && !commandOptions.getOptions(BuildEventProtocolOptions.class)
              .includeResidueInRunBepEvent) {
        builder.setChunkList(ChunkList.newBuilder().addChunk("REDACTED"));
      } else {
        builder.setChunkList(ChunkList.newBuilder().addAllChunk(commandOptions.getResidue()));
      }
      return builder.build();
    }
  }

  /** This reports a reassembled version of the command line as Bazel received it. */
  public static class OriginalCommandLineEvent extends BazelCommandLineEvent {
    public static final String LABEL = "original";
    private final Optional<List<Pair<String, String>>> originalStartupOptions;

    public OriginalCommandLineEvent(
        BlazeRuntime runtime,
        String commandName,
        OptionsParsingResult commandOptions,
        Optional<List<Pair<String, String>>> originalStartupOptions) {
      this(
          runtime.getProductName(),
          runtime.getStartupOptionsProvider(),
          commandName,
          commandOptions,
          originalStartupOptions);
    }

    @VisibleForTesting
    OriginalCommandLineEvent(
        String productName,
        OptionsParsingResult activeStartupOptions,
        String commandName,
        OptionsParsingResult commandOptions,
        Optional<List<Pair<String, String>>> originalStartupOptions) {
      super(productName, activeStartupOptions, commandName, commandOptions);
      this.originalStartupOptions = originalStartupOptions;
    }

    @Override
    public BuildEventId getEventId() {
      return BuildEventIdUtil.structuredCommandlineId(LABEL);
    }

    /**
     * Returns the literal command line options as received. These are not the final parsed values,
     * but are passed as is from the client, so we do not have the full OptionDefinition
     * information. In this form, only set the "combinedForm" field.
     */
    private CommandLineSection getStartupOptionSection() {
      if (originalStartupOptions.isPresent()) {
        List<Option> options = new ArrayList<>();
        for (Pair<String, String> sourceToOptionPair : originalStartupOptions.get()) {
          // Only add the options that were added by the command line.
          // TODO(b/19881919) decide the format that option source information should take and then
          // add all options, tagged with the source, instead of filtering out the rc options.
          if (sourceToOptionPair.first != null && sourceToOptionPair.first.isEmpty()) {
            options.add(
                Option.newBuilder().setCombinedForm(sourceToOptionPair.getSecond()).build());
          }
        }
        return CommandLineSection.newBuilder()
            .setSectionLabel("startup options")
            .setOptionList(OptionList.newBuilder().addAllOption(options))
            .build();
      } else {
        // If we were not provided with the startup options, fallback to reporting the active ones
        // stored by the Bazel Runtime.
        return getActiveStartupOptions();
      }
    }

    private static boolean commandLinePriority(ParsedOptionDescription parsedOptionDescription) {
      return parsedOptionDescription.getPriority().getPriorityCategory()
          == OptionPriority.PriorityCategory.COMMAND_LINE;
    }

    private CommandLineSection getExplicitCommandOptions() {
      List<ParsedOptionDescription> explicitOptions =
          commandOptions.asListOfExplicitOptions().stream()
              .filter(OriginalCommandLineEvent::commandLinePriority)
              .collect(Collectors.toList());
      List<Option> starlarkOptions =
          commandOptions
              .getExplicitStarlarkOptions(OriginalCommandLineEvent::commandLinePriority)
              .entrySet()
              .stream()
              .map(e -> createStarlarkOption(e.getKey(), e.getValue()))
              .collect(Collectors.toList());
      return CommandLineSection.newBuilder()
          .setSectionLabel("command options")
          .setOptionList(
              OptionList.newBuilder()
                  .addAllOption(getOptionListFromParsedOptionDescriptions(explicitOptions))
                  .addAllOption(starlarkOptions))
          .build();
    }

    @Override
    public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
      return GenericBuildEvent.protoChaining(this)
          .setStructuredCommandLine(
              CommandLine.newBuilder()
                  .setCommandLineLabel(LABEL)
                  .addSections(getExecutableSection())
                  .addSections(getStartupOptionSection())
                  .addSections(getCommandSection())
                  .addSections(getExplicitCommandOptions())
                  .addSections(getResidual())
                  .build())
          .build();
    }
  }

  /** This reports the canonical form of the command line. */
  public static class CanonicalCommandLineEvent extends BazelCommandLineEvent {
    public static final String LABEL = "canonical";

    public CanonicalCommandLineEvent(
        BlazeRuntime runtime, String commandName, OptionsParsingResult commandOptions) {
      this(
          runtime.getProductName(),
          runtime.getStartupOptionsProvider(),
          commandName,
          commandOptions);
    }

    @VisibleForTesting
    CanonicalCommandLineEvent(
        String productName,
        OptionsParsingResult activeStartupOptions,
        String commandName,
        OptionsParsingResult commandOptions) {
      super(productName, activeStartupOptions, commandName, commandOptions);
    }

    @Override
    public BuildEventId getEventId() {
      return BuildEventIdUtil.structuredCommandlineId(LABEL);
    }

    /**
     * Returns the effective startup options.
     *
     * <p>Since in this command line the command options include invocation policy's and rcs'
     * contents expanded fully, the list of startup options should prevent reapplication of these
     * contents.
     *
     * <p>The options parser does not understand the effect of these flags, since the relationship
     * between these startup options and the command options is not held within the options parser,
     * so instead, we add a small hack. Remove any explicit mentions of these flags, and explicitly
     * add the options that prevent Blaze from looking for the default rc files.
     */
    private CommandLineSection getCanonicalStartupOptions() {
      List<Option> unfilteredOptions = getActiveStartupOptions().getOptionList().getOptionList();
      // Create the fake ones to prevent reapplication of the original rc file contents.
      OptionsParser fakeOptions =
          OptionsParser.builder().optionsClasses(BlazeServerStartupOptions.class).build();
      try {
        fakeOptions.parse("--ignore_all_rc_files");
      } catch (OptionsParsingException e) {
        // Unless someone changes the definition of these flags, this is impossible.
        throw new IllegalStateException(e);
      }

      // Remove any instances of the applied, and add the new blocking ones.
      return CommandLineSection.newBuilder()
          .setSectionLabel("startup options")
          .setOptionList(
              OptionList.newBuilder()
                  .addAllOption(
                      unfilteredOptions.stream()
                          .filter(
                              option -> {
                                String optionName = option.getOptionName();
                                return !optionName.equals("ignore_all_rc_files")
                                    && !optionName.equals("blazerc")
                                    && !optionName.equals("master_blazerc")
                                    && !optionName.equals("bazelrc")
                                    && !optionName.equals("master_bazelrc")
                                    && !optionName.equals("invocation_policy");
                              })
                          .collect(Collectors.toList()))
                  .addAllOption(
                      getOptionListFromParsedOptionDescriptions(
                          fakeOptions.asCompleteListOfParsedOptions())))
          .build();
    }

    /** Returns the canonical command options, overridden and default values are not listed. */
    private CommandLineSection getCanonicalCommandOptions() {
      List<Option> starlarkOptions =
          commandOptions.getStarlarkOptions().entrySet().stream()
              .map(e -> createStarlarkOption(e.getKey(), e.getValue()))
              .collect(Collectors.toList());
      return CommandLineSection.newBuilder()
          .setSectionLabel("command options")
          .setOptionList(
              OptionList.newBuilder()
                  .addAllOption(
                      getOptionListFromParsedOptionDescriptions(
                          commandOptions.asListOfCanonicalOptions()))
                  .addAllOption(starlarkOptions))
          .build();
    }

    /**
     * Hash including the explicit command line options as well as the residue, e.g. the targets.
     */
    public long getExplicitCommandLineHash() {
      long hash = 0;
      for (Entry<String, Object> starlarkOption : commandOptions.getStarlarkOptions().entrySet()) {
        hash = hash * 31 + starlarkOption.toString().hashCode();
      }
      for (ParsedOptionDescription canonicalOptionDesc :
          commandOptions.asListOfCanonicalOptions()) {
        if (canonicalOptionDesc == null
            || canonicalOptionDesc.isHidden()
            || !"command line options".equals(canonicalOptionDesc.getSource())) {
          continue;
        }
        hash = hash * 31 + canonicalOptionDesc.getCanonicalForm().hashCode();
      }
      for (String r : commandOptions.getResidue()) {
        hash = hash * 31 + r.hashCode();
      }
      return hash;
    }

    @Override
    public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
      return GenericBuildEvent.protoChaining(this)
          .setStructuredCommandLine(
              CommandLine.newBuilder()
                  .setCommandLineLabel(LABEL)
                  .addSections(getExecutableSection())
                  .addSections(getCanonicalStartupOptions())
                  .addSections(getCommandSection())
                  .addSections(getCanonicalCommandOptions())
                  .addSections(getResidual())
                  .build())
          .build();
    }
  }

  /**
   * A command line that Bazel accepts via flag (yes, we see the irony there).
   *
   * <p>Permits Bazel to report command lines from the tool that invoked it, if such a tool exists.
   */
  public static final class ToolCommandLineEvent extends CommandLineEvent {
    public static final String LABEL = "tool";
    private final CommandLine commandLine;

    ToolCommandLineEvent(CommandLine commandLine) {
      this.commandLine = commandLine;
    }

    @Override
    public BuildEvent asStreamProto(BuildEventContext converters) {
      return GenericBuildEvent.protoChaining(this).setStructuredCommandLine(commandLine).build();
    }

    /**
     * The label of this command line event is always "tool," so that the BuildStartingEvent
     * correctly tracks its children. The provided command line may have its own label that will be
     * more descriptive.
     */
    @Override
    public BuildEventId getEventId() {
      return BuildEventIdUtil.structuredCommandlineId(LABEL);
    }

    /**
     * The converter for the option value. We accept the command line both in base64 encoded proto
     * form and as unstructured strings.
     */
    public static class Converter
        extends com.google.devtools.common.options.Converter.Contextless<ToolCommandLineEvent> {

      @Override
      public ToolCommandLineEvent convert(String input) throws OptionsParsingException {
        if (input.isEmpty()) {
          return new ToolCommandLineEvent(CommandLine.getDefaultInstance());
        }

        CommandLine commandLine;
        try {
          // Try decoding the input as a base64 encoded binary proto.
          commandLine = CommandLine.parseFrom(BaseEncoding.base64().decode(input));
        } catch (IllegalArgumentException e) {
          // If the value was not recognized as a base64-encoded proto, store the flag value as a
          // single string chunk.
          commandLine =
              CommandLine.newBuilder()
                  .setCommandLineLabel(LABEL)
                  .addSections(
                      CommandLineSection.newBuilder()
                          .setChunkList(ChunkList.newBuilder().addChunk(input)))
                  .build();
        } catch (InvalidProtocolBufferException e) {
          throw new OptionsParsingException(
              String.format("Malformed value of --experimental_tool_command_line: %s", input), e);
        }
        return new ToolCommandLineEvent(commandLine);
      }

      @Override
      public String getTypeDescription() {
        return "A command line, either as a simple string, or as a base64-encoded binary form of a"
            + " CommandLine proto";
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("commandLine", commandLine).toString();
    }
  }
}
