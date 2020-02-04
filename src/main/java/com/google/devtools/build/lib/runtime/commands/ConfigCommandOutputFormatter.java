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
package com.google.devtools.build.lib.runtime.commands;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.runtime.commands.ConfigCommand.ConfigurationDiffForOutput;
import com.google.devtools.build.lib.runtime.commands.ConfigCommand.ConfigurationForOutput;
import com.google.devtools.build.lib.runtime.commands.ConfigCommand.FragmentDiffForOutput;
import com.google.devtools.build.lib.runtime.commands.ConfigCommand.FragmentForOutput;
import com.google.devtools.build.lib.util.Pair;
import com.google.gson.Gson;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Formats output for {@link ConfigCommand}.
 *
 * <p>The basic contract is @link ConfigCommand} makes all important structural decisions: what data
 * gets reported, how different pieces of data relate to each other, and how data is ordered. A
 * {@link ConfigCommandOutputFormatter} then outputs this in a format-appropriate way.
 */
abstract class ConfigCommandOutputFormatter {
  protected final PrintWriter writer;

  /** Constructs a formatter that writes output to the given {@link PrintWriter}. */
  ConfigCommandOutputFormatter(PrintWriter writer) {
    this.writer = writer;
  }

  /** Outputs a list of configuration hash IDS. * */
  public abstract void writeConfigurationIDs(Iterable<String> configurationIDs);

  /** Outputs a single configuration. * */
  public abstract void writeConfiguration(ConfigurationForOutput configuration);

  /** Outputs the diff between two configurations * */
  public abstract void writeConfigurationDiff(ConfigurationDiffForOutput diff);

  /** A {@link ConfigCommandOutputFormatter} that outputs plan user-readable text. */
  static class TextOutputFormatter extends ConfigCommandOutputFormatter {
    TextOutputFormatter(PrintWriter writer) {
      super(writer);
    }

    @Override
    public void writeConfigurationIDs(Iterable<String> configurationIDs) {
      writer.println("Available configurations:");
      configurationIDs.forEach(id -> writer.println(id));
    }

    @Override
    public void writeConfiguration(ConfigurationForOutput configuration) {
      writer.println("BuildConfiguration " + configuration.configHash + ":");
      writer.println("Skyframe Key: " + configuration.skyKey);
      for (FragmentForOutput fragment : configuration.fragments) {
        writer.println("Fragment " + fragment.name + " {");
        for (Map.Entry<String, String> optionSetting : fragment.options.entrySet()) {
          writer.printf("  %s: %s", optionSetting.getKey(), optionSetting.getValue());
        }
        writer.println("}");
      }
    }

    @Override
    public void writeConfigurationDiff(ConfigurationDiffForOutput diff) {
      writer.printf(
          "Displaying diff between configs %s and %s", diff.configHash1, diff.configHash2);
      for (FragmentDiffForOutput fragmentDiff : diff.fragmentsDiff) {
        writer.println("Fragment " + fragmentDiff.name + " {");
        for (Map.Entry<String, Pair<String, String>> optionDiff :
            fragmentDiff.optionsDiff.entrySet()) {
          writer.printf(
              "  %s: %s, %s",
              optionDiff.getKey(), optionDiff.getValue().first, optionDiff.getValue().second);
        }
        writer.println("}");
      }
    }
  }

  /** A {@link ConfigCommandOutputFormatter} that outputs structured JSON. */
  static class JsonOutputFormatter extends ConfigCommandOutputFormatter {
    private final Gson gson;

    JsonOutputFormatter(PrintWriter writer) {
      super(writer);
      this.gson = new Gson();
    }

    @Override
    public void writeConfigurationIDs(Iterable<String> configurationIDs) {
      writer.println(gson.toJson(ImmutableMap.of("configuration-IDs", configurationIDs)));
    }

    @Override
    public void writeConfiguration(ConfigurationForOutput configuration) {
      writer.println(gson.toJson(configuration));
    }

    @Override
    public void writeConfigurationDiff(ConfigurationDiffForOutput diff) {
      writer.println(gson.toJson(diff));
    }
  }
}
