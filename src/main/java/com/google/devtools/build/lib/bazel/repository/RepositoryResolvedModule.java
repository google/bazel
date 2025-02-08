// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.Files;
import com.google.devtools.build.lib.bazel.ResolvedEvent;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.common.options.OptionsBase;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.starlark.java.eval.Printer;

/** Module providing the collection of the resolved values for the repository rules executed. */
public final class RepositoryResolvedModule extends BlazeModule {
  public static final String EXPORTED_NAME = "resolved";

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private Map<String, Object> resolvedValues;
  private String resolvedFile;
  private ImmutableList<String> orderedNames;
  private XattrProvider xattrProvider;

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return ImmutableSet.of("sync", "fetch", "build", "query").contains(command.name())
        ? ImmutableList.of(RepositoryResolvedOptions.class)
        : ImmutableList.of();
  }

  @Override
  public void beforeCommand(CommandEnvironment env) {
    orderedNames = ImmutableList.of();
    RepositoryResolvedOptions options =
        env.getOptions().getOptions(RepositoryResolvedOptions.class);
    if (options != null && !Strings.isNullOrEmpty(options.repositoryResolvedFile)) {
      this.resolvedFile = options.repositoryResolvedFile;
      env.getEventBus().register(this);
      this.resolvedValues = new LinkedHashMap<>();
    } else {
      this.resolvedFile = null;
    }
    this.xattrProvider = env.getXattrProvider();
  }

  @Override
  public void afterCommand() {
    if (resolvedFile != null) {
      ImmutableList.Builder<Object> resultBuilder = new ImmutableList.Builder<>();
      // Fill the result builder; first all known repositories in order, then the
      // rest in the order we knew about them.
      for (String name : orderedNames) {
        if (resolvedValues.containsKey(name)) {
          resultBuilder.add(resolvedValues.get(name));
          resolvedValues.remove(name);
        }
      }
      for (Object resolved : resolvedValues.values()) {
        resultBuilder.add(resolved);
      }
      try (Writer writer = Files.newWriter(new File(resolvedFile), ISO_8859_1)) {
        writer.write(EXPORTED_NAME + " = " + new ValuePrinter().repr(resultBuilder.build()));
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("IO Error writing to file %s", resolvedFile);
      }
    }

    this.resolvedValues = null;
  }

  @Subscribe
  public void repositoryOrderEvent(RepositoryOrderEvent event) {
    orderedNames = event.getOrderedNames();
  }

  @Subscribe
  public void resolved(ResolvedEvent event) {
    if (resolvedValues != null) {
      resolvedValues.put(event.getName(), event.getResolvedInformation(xattrProvider));
    }
  }

  // An printer of repository rule attribute values that inserts properly indented lines between
  // sequence elements.
  private static final class ValuePrinter extends Printer {
    int indent = 0;

    ValuePrinter() {
      super(new StringBuilder());
    }

    @Override
    public Printer repr(Object o) {
      // In WORKSPACE files, the Label constructor is not available.
      // Fortunately, in all places where a label is needed,
      // we can pass the canonical string associated with this label.
      if (o instanceof Label label) {
        return this.repr(label.getCanonicalForm());
      }
      return super.repr(o);
    }

    @Override
    public Printer printList(Iterable<?> list, String before, String separator, String after) {
      this.append(before);
      indent++;
      // If the list is empty, do not split the presentation over several lines.
      String sep = null;
      for (Object o : list) {
        if (sep == null) { // first?
          sep = separator.trim();
        } else {
          this.append(sep);
        }
        this.append("\n").append(" ".repeat(5 * indent)).repr(o);
      }
      indent--;
      // Final indent, if nonempty.
      if (sep != null) {
        this.append("\n").append(" ".repeat(5 * indent));
      }
      return this.append(after);
    }
  }
}
