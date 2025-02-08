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
package com.google.devtools.build.lib.bazel.debug;

import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos;
import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos.ExtractEvent;
import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos.FileEvent;
import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos.OsEvent;
import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos.RenameEvent;
import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos.SymlinkEvent;
import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos.TemplateEvent;
import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos.WhichEvent;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import java.net.URL;
import java.util.List;
import java.util.Map;
import net.starlark.java.syntax.Location;

/** An event to record events happening during workspace rule resolution */
public final class WorkspaceRuleEvent implements Postable {
  WorkspaceLogProtos.WorkspaceEvent event;

  public WorkspaceLogProtos.WorkspaceEvent getLogEvent() {
    return event;
  }

  private WorkspaceRuleEvent(WorkspaceLogProtos.WorkspaceEvent event) {
    this.event = event;
  }

  /** Creates a new WorkspaceRuleEvent for an execution event. */
  public static WorkspaceRuleEvent newExecuteEvent(
      Iterable<String> args,
      Integer timeout,
      Map<String, String> commonEnvironment,
      Map<String, String> customEnvironment,
      String outputDirectory,
      boolean quiet,
      String context,
      Location location) {

    WorkspaceLogProtos.ExecuteEvent.Builder e =
        WorkspaceLogProtos.ExecuteEvent.newBuilder()
            .setTimeoutSeconds(timeout.intValue())
            .setOutputDirectory(outputDirectory)
            .setQuiet(quiet);
    if (commonEnvironment != null) {
      e = e.putAllEnvironment(commonEnvironment);
    }
    if (customEnvironment != null) {
      e = e.putAllEnvironment(customEnvironment);
    }

    for (String a : args) {
      e.addArguments(a);
    }

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setExecuteEvent(e.build());
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a download event. */
  public static WorkspaceRuleEvent newDownloadEvent(
      List<URL> urls,
      String output,
      String sha256,
      String integrity,
      Boolean executable,
      String context,
      Location location) {
    WorkspaceLogProtos.DownloadEvent.Builder e =
        WorkspaceLogProtos.DownloadEvent.newBuilder()
            .setOutput(output)
            .setSha256(sha256)
            .setIntegrity(integrity)
            .setExecutable(executable);
    for (URL u : urls) {
      e.addUrl(u.toString());
    }

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setDownloadEvent(e.build());
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for an extract event. */
  public static WorkspaceRuleEvent newExtractEvent(
      String archive,
      String output,
      String stripPrefix,
      Map<String, String> renameFiles,
      String context,
      Location location) {
    ExtractEvent e =
        WorkspaceLogProtos.ExtractEvent.newBuilder()
            .setArchive(archive)
            .setOutput(output)
            .setStripPrefix(stripPrefix)
            .putAllRenameFiles(renameFiles)
            .build();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setExtractEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a download and extract event. */
  public static WorkspaceRuleEvent newDownloadAndExtractEvent(
      List<URL> urls,
      String output,
      String sha256,
      String integrity,
      String type,
      String stripPrefix,
      Map<String, String> renameFiles,
      String context,
      Location location) {
    WorkspaceLogProtos.DownloadAndExtractEvent.Builder e =
        WorkspaceLogProtos.DownloadAndExtractEvent.newBuilder()
            .setOutput(output)
            .setSha256(sha256)
            .setIntegrity(integrity)
            .setType(type)
            .setStripPrefix(stripPrefix)
            .putAllRenameFiles(renameFiles);
    for (URL u : urls) {
      e.addUrl(u.toString());
    }

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setDownloadAndExtractEvent(e.build());
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a file event. */
  public static WorkspaceRuleEvent newFileEvent(
      String path, String content, boolean executable, String context, Location location) {
    FileEvent e =
        WorkspaceLogProtos.FileEvent.newBuilder()
            .setPath(path)
            .setContent(content)
            .setExecutable(executable)
            .build();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setFileEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a file read event. */
  public static WorkspaceRuleEvent newReadEvent(String path, String context, Location location) {
    WorkspaceLogProtos.ReadEvent e =
        WorkspaceLogProtos.ReadEvent.newBuilder().setPath(path).build();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setReadEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a file read event. */
  public static WorkspaceRuleEvent newDeleteEvent(String path, String context, Location location) {
    WorkspaceLogProtos.DeleteEvent e =
        WorkspaceLogProtos.DeleteEvent.newBuilder().setPath(path).build();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setDeleteEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a patch event. */
  public static WorkspaceRuleEvent newPatchEvent(
      String patchFile, int strip, String context, Location location) {
    WorkspaceLogProtos.PatchEvent e =
        WorkspaceLogProtos.PatchEvent.newBuilder().setPatchFile(patchFile).setStrip(strip).build();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setPatchEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for an os event. */
  public static WorkspaceRuleEvent newOsEvent(String context, Location location) {
    OsEvent e = WorkspaceLogProtos.OsEvent.getDefaultInstance();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setOsEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a rename event. */
  public static WorkspaceRuleEvent newRenameEvent(
      String src, String dst, String context, Location location) {
    RenameEvent e = WorkspaceLogProtos.RenameEvent.newBuilder().setSrc(src).setDst(dst).build();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setRenameEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a symlink event. */
  public static WorkspaceRuleEvent newSymlinkEvent(
      String from, String to, String context, Location location) {
    SymlinkEvent e =
        WorkspaceLogProtos.SymlinkEvent.newBuilder().setTarget(from).setPath(to).build();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setSymlinkEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a template event. */
  public static WorkspaceRuleEvent newTemplateEvent(
      String path,
      String template,
      Map<String, String> substitutions,
      boolean executable,
      String context,
      Location location) {
    TemplateEvent e =
        WorkspaceLogProtos.TemplateEvent.newBuilder()
            .setPath(path)
            .setTemplate(template)
            .putAllSubstitutions(substitutions)
            .setExecutable(executable)
            .build();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setTemplateEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /** Creates a new WorkspaceRuleEvent for a which event. */
  public static WorkspaceRuleEvent newWhichEvent(
      String program, String context, Location location) {
    WhichEvent e = WorkspaceLogProtos.WhichEvent.newBuilder().setProgram(program).build();

    WorkspaceLogProtos.WorkspaceEvent.Builder result =
        WorkspaceLogProtos.WorkspaceEvent.newBuilder();
    result = result.setWhichEvent(e);
    if (location != null) {
      result = result.setLocation(location.toString());
    }
    if (context != null) {
      result = result.setContext(context);
    }
    return new WorkspaceRuleEvent(result.build());
  }

  /**
   * @return a message to log for this event
   */
  public String logMessage() {
    return event.toString();
  }
}
