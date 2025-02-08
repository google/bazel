// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.shell;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.shell.SubprocessBuilder.StreamAction;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.windows.WindowsProcesses;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

/** A subprocess factory that uses the Win32 API. */
public class WindowsSubprocessFactory implements SubprocessFactory {
  public static final WindowsSubprocessFactory INSTANCE = new WindowsSubprocessFactory();

  @Override
  public Subprocess create(SubprocessBuilder builder) throws IOException {
    List<String> argv = Lists.transform(builder.getArgv(), StringEncoding::internalToPlatform);

    // DO NOT quote argv0, createProcess will do it for us.
    String argv0 = processArgv0(argv.get(0));
    String argvRest =
        argv.size() > 1
            ? escapeArgvRest(argv.subList(1, argv.size()), argv0.equals("cmd.exe"))
            : "";
    byte[] env = convertEnvToNative(builder.getEnv());

    String cwd = null;
    if (builder.getWorkingDirectory() != null) {
      cwd = builder.getWorkingDirectory().getPath();
    }
    String stdoutPath = getRedirectPath(builder.getStdout(), builder.getStdoutFile());
    String stderrPath = getRedirectPath(builder.getStderr(), builder.getStderrFile());

    long nativeProcess =
        WindowsProcesses.createProcess(
            argv0,
            argvRest,
            env,
            cwd,
            stdoutPath,
            stderrPath,
            builder.redirectErrorStream());
    String error = WindowsProcesses.processGetLastError(nativeProcess);
    if (!error.isEmpty()) {
      WindowsProcesses.deleteProcess(nativeProcess);
      throw new IOException(error);
    }

    return new WindowsSubprocess(
        nativeProcess,
        argv0 + " " + argvRest,
        stdoutPath != null,
        stderrPath != null,
        builder.getTimeoutMillis());
  }

  private static String escapeArgvRest(List<String> argv, boolean isCmd) {
    StringBuilder result = new StringBuilder();
    boolean first = true;
    for (String arg : argv) {
      if (first) {
        first = false;
      } else {
        result.append(" ");
      }
      if (isCmd) {
        result.append(arg);
      } else {
        result.append(ShellUtils.windowsEscapeArg(arg));
      }
    }
    return result.toString();
  }

  public static String processArgv0(String argv0) {
    // Normalize the path and make it Windows-style.
    // If argv0 is at least MAX_PATH (260 chars) long, createNativeProcess calls GetShortPathNameW
    // to obtain a 8dot3 name for it (thereby support long paths in CreateProcessA), but then argv0
    // must be prefixed with "\\?\" for GetShortPathNameW to work, so it also must be an absolute,
    // normalized, Windows-style path.
    // Therefore if it's absolute, then normalize it also.
    // If it's not absolute, then it cannot be longer than MAX_PATH, since MAX_PATH also limits the
    // length of file names.
    PathFragment argv0fragment = PathFragment.create(argv0);
    return argv0fragment.isAbsolute() ? argv0fragment.getPathString().replace('/', '\\') : argv0;
  }

  @Nullable
  private static String getRedirectPath(StreamAction action, File file) {
    switch (action) {
      case DISCARD:
        return "NUL"; // That's /dev/null on Windows

      case REDIRECT:
        return file.getPath();

      case STREAM:
        return null;

      default:
        throw new IllegalStateException();
    }
  }

  /** Converts an environment map to the format expected in lpEnvironment by CreateProcess(). */
  private static byte[] convertEnvToNative(Map<String, String> envMap) {
    Map<String, String> fullEnv = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    if (envMap != null) {
      fullEnv.putAll(envMap);
      // Some versions of MSVCRT.DLL and tools require SYSTEMROOT and SYSTEMDRIVE to be set. They
      // are very common environment variables on Windows, so we add these environment variables
      // regardless of whether the caller requested it or not.
      for (String env : ImmutableList.of("SYSTEMROOT", "SYSTEMDRIVE")) {
        if (fullEnv.getOrDefault(env, null) == null) {
          String value = System.getenv(env);
          if (value != null) {
            fullEnv.put(env, value);
          }
        }
      }
    } else {
      fullEnv.putAll(System.getenv());
    }

    if (fullEnv.isEmpty()) {
      // Special case: CreateProcess() always expects the environment block to be terminated
      // with two zeros.
      return "\0".getBytes(StandardCharsets.UTF_16LE);
    }

    StringBuilder result = new StringBuilder();
    for (Map.Entry<String, String> entry : fullEnv.entrySet()) {
      if (entry.getKey().contains("=")) {
        // lpEnvironment requires no '=' in environment variable name, but on Windows,
        // System.getenv() returns environment variables like '=C:' or '=ExitCode', so it can't
        // be an error, we have ignore them here.
        continue;
      }
      result
          .append(StringEncoding.internalToPlatform(entry.getKey()))
          .append("=")
          .append(StringEncoding.internalToPlatform(entry.getValue()))
          .append("\0");
    }

    result.append("\0");
    return result.toString().getBytes(StandardCharsets.UTF_16LE);
  }
}
