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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Converters.RangeConverter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.HashSet;
import java.util.List;

/** Command-line UI options. */
public class UiOptions extends OptionsBase {

  /** Enum to select whether color output is enabled or not. */
  public enum UseColor {
    YES,
    NO,
    AUTO
  }

  /** Enum to select whether curses output is enabled or not. */
  public enum UseCurses {
    YES,
    NO,
    AUTO
  }

  /** Converter for {@link EventKind} filters * */
  public static class EventFiltersConverter
      extends Converter.Contextless<EventFiltersConverter.EventKindFilters> {

    /** Container for an EventKind input filter. */
    public record EventKindFilters(
        ImmutableSet<EventKind> filteredEventKinds, ImmutableSet<EventKind> unfilteredEventKinds) {
      public EventKindFilters {
        requireNonNull(filteredEventKinds, "filteredEventKinds");
        requireNonNull(unfilteredEventKinds, "unfilteredEventKinds");
      }

      public static EventKindFilters from(
          ImmutableSet<EventKind> filtered, ImmutableSet<EventKind> unfiltered) {
        return new EventKindFilters(filtered, unfiltered);
      }
    }

    private final CommaSeparatedOptionListConverter commaSeparatedListConverter;
    private final EnumConverter<EventKind> eventKindConverter;

    public EventFiltersConverter() {
      this.commaSeparatedListConverter = new CommaSeparatedOptionListConverter();
      this.eventKindConverter = new EnumConverter<>(EventKind.class, "event kind") {};
    }

    @Override
    public EventKindFilters convert(String input) throws OptionsParsingException {
      if (input.isEmpty()) {
        // This method is not called to convert the default value
        // Empty list means that the user wants to filter all events
        return EventKindFilters.from(EventKind.ALL_EVENTS, ImmutableSet.of());
      }
      ImmutableList<String> filters =
          commaSeparatedListConverter.convert(input, /* conversionContext= */ null);

      HashSet<EventKind> filteredEventKinds = new HashSet<>();
      HashSet<EventKind> unfilteredEventKinds = new HashSet<>();

      for (String filter : filters) {
        if (!filter.startsWith("+") && !filter.startsWith("-")) {
          filteredEventKinds.addAll(EventKind.ALL_EVENTS);
          unfilteredEventKinds.clear();
        }
        if (!filter.isEmpty()) {
          EventKind kind =
              eventKindConverter.convert(
                  filter.replaceFirst("^[+-]", ""), /* conversionContext= */ null);
          if (filter.startsWith("-")) {
            filteredEventKinds.add(kind);
            unfilteredEventKinds.remove(kind);
          } else {
            unfilteredEventKinds.add(kind);
            filteredEventKinds.remove(kind);
          }
        }
      }
      return EventKindFilters.from(
          ImmutableSet.copyOf(filteredEventKinds), ImmutableSet.copyOf(unfilteredEventKinds));
    }

    @Override
    public String getTypeDescription() {
      return "Convert list of comma separated event kind to list of filters";
    }
  }

  /** Converter for {@link UseColor}. */
  public static class UseColorConverter extends EnumConverter<UseColor> {
    public UseColorConverter() {
      super(UseColor.class, "--color setting");
    }
  }

  /** Converter for {@link UseCurses}. */
  public static class UseCursesConverter extends EnumConverter<UseCurses> {
    public UseCursesConverter() {
      super(UseCurses.class, "--curses setting");
    }
  }

  @Option(
      name = "show_progress",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Display progress messages during a build.")
  public boolean showProgress;

  @Option(
      name = "show_progress_rate_limit",
      defaultValue = "0.2", // A nice middle ground; snappy but not too spammy in logs.
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Minimum number of seconds between progress messages in the output.")
  public double showProgressRateLimit;

  @Option(
      name = "color",
      defaultValue = "auto",
      converter = UseColorConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Use terminal controls to colorize output.")
  public UseColor useColorEnum;

  @Option(
      name = "curses",
      defaultValue = "auto",
      converter = UseCursesConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Use terminal cursor controls to minimize scrolling output.")
  public UseCurses useCursesEnum;

  @Option(
      name = "terminal_columns",
      defaultValue = "80",
      metadataTags = {OptionMetadataTag.HIDDEN},
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "A system-generated parameter which specifies the terminal width in columns.")
  public int terminalColumns;

  @Option(
      name = "isatty",
      defaultValue = "false",
      metadataTags = {OptionMetadataTag.HIDDEN},
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "A system-generated parameter which is used to notify the "
              + "server whether this client is running in a terminal. "
              + "If this is set to false, then '--color=auto' will be treated as '--color=no'. "
              + "If this is set to true, then '--color=auto' will be treated as '--color=yes'.")
  public boolean isATty;

  // This lives here (as opposed to the more logical BuildRequest.Options)
  // because the client passes it to the server *always*.  We don't want the
  // client to have to figure out when it should or shouldn't to send it.
  @Option(
      name = "emacs",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "A system-generated parameter which is true iff EMACS=t or INSIDE_EMACS is set "
              + "in the environment of the client.  This option controls certain display "
              + "features.")
  public boolean runningInEmacs;

  @Option(
      name = "show_timestamps",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Include timestamps in messages")
  public boolean showTimestamp;

  @Option(
      name = "progress_in_terminal_title",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Show the command progress in the terminal title. "
              + "Useful to see what bazel is doing when having multiple terminal tabs.")
  public boolean progressInTermTitle;

  @Option(
      name = "attempt_to_print_relative_paths",
      oldName = "experimental_ui_attempt_to_print_relative_paths",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "When printing the location part of messages, attempt to use a path relative to the "
              + "workspace directory or one of the directories specified by --package_path.")
  public boolean attemptToPrintRelativePaths;

  @Option(
      name = "experimental_ui_debug_all_events",
      defaultValue = "false",
      metadataTags = {OptionMetadataTag.HIDDEN},
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Report all events known to the Bazel UI.")
  public boolean experimentalUiDebugAllEvents;

  @Option(
      name = "ui_event_filters",
      converter = EventFiltersConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Specifies which events to show in the UI. It is possible to add or remove events "
              + "to the default ones using leading +/-, or override the default "
              + "set completely with direct assignment. The set of supported event kinds "
              + "include INFO, DEBUG, ERROR and more.",
      allowMultiple = true)
  public List<EventFiltersConverter.EventKindFilters> eventKindFilters;

  @Option(
      name = "ui_actions_shown",
      oldName = "experimental_ui_actions_shown",
      defaultValue = "8",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Number of concurrent actions shown in the detailed progress bar; each "
              + "action is shown on a separate line. The progress bar always shows "
              + "at least one one, all numbers less than 1 are mapped to 1.")
  public int uiActionsShown;

  @Option(
      name = "experimental_ui_max_stdouterr_bytes",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      defaultValue = "1048576",
      converter = MaxStdoutErrBytesConverter.class,
      help =
          "The maximum size of the stdout / stderr files that will be printed to the console. "
              + "-1 implies no limit.")
  public int maxStdoutErrBytes;

  public boolean useColor() {
    return useColorEnum == UseColor.YES || (useColorEnum == UseColor.AUTO && isATty);
  }

  public boolean useCursorControl() {
    return useCursesEnum == UseCurses.YES || (useCursesEnum == UseCurses.AUTO && isATty);
  }

  public ImmutableSet<EventKind> getFilteredEventKinds() {
    HashSet<EventKind> filtered = new HashSet<>();
    for (EventFiltersConverter.EventKindFilters filters : eventKindFilters) {
      filtered.addAll(filters.filteredEventKinds());
      filtered.removeAll(filters.unfilteredEventKinds());
    }
    return ImmutableSet.copyOf(filtered);
  }

  /** A converter for --experimental_ui_max_stdouterr_bytes. */
  public static class MaxStdoutErrBytesConverter extends RangeConverter {

    /**
     * The maximum value of the flag must be limited to ensure conversions to UTF-8 do not trigger
     * integer overflows. In JDK9+, if the message buffer contains a byte whose high bit is set, a
     * UTF-8 decoding path is taken that allocates a new byte[] buffer twice as large as the message
     * byte[] buffer.
     */
    private static final int MAX_VALUE = (Integer.MAX_VALUE - 8) >> 1;

    public MaxStdoutErrBytesConverter() {
      super(-1, (Integer.MAX_VALUE - 8) >> 1);
    }

    @Override
    public Integer convert(String input) throws OptionsParsingException {
      Integer value = super.convert(input);
      return value >= 0 ? value : MAX_VALUE;
    }
  }
}
