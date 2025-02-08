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

import com.google.common.collect.Lists;
import com.google.devtools.build.lib.shell.SubprocessBuilder.StreamAction;
import com.google.devtools.build.lib.util.StringEncoding;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** A subprocess factory that uses {@link java.lang.ProcessBuilder}. */
public class JavaSubprocessFactory implements SubprocessFactory {

  /** A subprocess backed by a {@link java.lang.Process}. */
  private static class JavaSubprocess implements Subprocess {
    private final Process process;
    private final long deadlineMillis;
    private final AtomicBoolean deadlineExceeded = new AtomicBoolean();

    private JavaSubprocess(Process process, long deadlineMillis) {
      this.process = process;
      this.deadlineMillis = deadlineMillis;
    }

    @Override
    public boolean destroy() {
      process.destroy();
      return true;
    }

    @Override
    public int exitValue() {
      return process.exitValue();
    }

    @Override
    public boolean finished() {
      if (deadlineMillis > 0
          && System.currentTimeMillis() > deadlineMillis
          && deadlineExceeded.compareAndSet(false, true)) {
        // We use compareAndSet here to avoid calling destroy multiple times. Note that destroy
        // returns immediately, and we don't want to wait in this method.
        process.destroy();
      }
      // this seems to be the only non-blocking call for checking liveness
      return !process.isAlive();
    }

    @Override
    public boolean isAlive() {
      return process.isAlive();
    }

    @Override
    public boolean timedout() {
      return deadlineExceeded.get();
    }

    @Override
    public void waitFor() throws InterruptedException {
      var waitTimeMillis =
          (deadlineMillis > 0) ? deadlineMillis - System.currentTimeMillis() : Long.MAX_VALUE;
      var exitedInTime = process.waitFor(waitTimeMillis, TimeUnit.MILLISECONDS);
      if (!exitedInTime && deadlineExceeded.compareAndSet(false, true)) {
        process.destroy();
        // The destroy call returns immediately, so we still need to wait for the actual exit. The
        // sole caller assumes that waitFor only exits when the process is gone (or throws).
        process.waitFor();
      }
    }

    @Override
    public OutputStream getOutputStream() {
      return process.getOutputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return process.getErrorStream();
    }

    @Override
    public InputStream getInputStream() {
      return process.getInputStream();
    }

    @Override
    public void close() {
      // java.lang.Process doesn't give us a way to clean things up other than #destroy(), which was
      // already called by this point.
    }

    @Override
    public long getProcessId() {
      return process.pid();
    }
  }

  public static final JavaSubprocessFactory INSTANCE = new JavaSubprocessFactory();
  private final ReentrantLock lock = new ReentrantLock();

  private JavaSubprocessFactory() {
    // We are a singleton
  }

  // since we are a singleton, we represent an ideal global lock for
  // process invocations, which is required due to the following race condition:

  // Linux does not provide a safe API for a multi-threaded program to fork a subprocess.
  // Consider the case where two threads both write an executable file and then try to execute
  // it. It can happen that the first thread writes its executable file, with the file
  // descriptor still being open when the second thread forks, with the fork inheriting a copy
  // of the file descriptor. Then the first thread closes the original file descriptor, and
  // proceeds to execute the file. At that point Linux sees an open file descriptor to the file
  // and returns ETXTBSY (Text file busy) as an error. This race is inherent in the fork / exec
  // duality, with fork always inheriting a copy of the file descriptor table; if there was a
  // way to fork without copying the entire file descriptor table (e.g., only copy specific
  // entries), we could avoid this race.
  //
  // I was able to reproduce this problem reliably by running significantly more threads than
  // there are CPU cores on my workstation - the more threads the more likely it happens.
  //
  // As a workaround, we use a lock around the fork.
  private Process start(ProcessBuilder builder) throws IOException {
    lock.lock();
    try {
      return builder.start();
    } catch (IOException e) {
      if (e.getMessage().contains("Failed to exec spawn helper")) {
        // Detect permanent failures due to an upgrade of the underlying JDK version,
        // see https://bugs.openjdk.org/browse/JDK-8325621.
        throw new IllegalStateException(
            "Subprocess creation has failed, the current JDK version is newer than the version"
                + " used at startup. Re-rerunning the blaze invocation should succeed.",
            e);
      }
      throw e;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Subprocess create(SubprocessBuilder params) throws IOException {
    ProcessBuilder builder = new ProcessBuilder();
    builder.command(Lists.transform(params.getArgv(), StringEncoding::internalToPlatform));
    if (params.getEnv() != null) {
      builder.environment().clear();
      params
          .getEnv()
          .forEach(
              (key, value) ->
                  builder
                      .environment()
                      .put(
                          StringEncoding.internalToPlatform(key),
                          StringEncoding.internalToPlatform(value)));
    }

    builder.redirectOutput(getRedirect(params.getStdout(), params.getStdoutFile()));
    builder.redirectError(getRedirect(params.getStderr(), params.getStderrFile()));
    builder.redirectErrorStream(params.redirectErrorStream());
    builder.directory(params.getWorkingDirectory());

    // Deadline is now + given timeout.
    long deadlineMillis =
        params.getTimeoutMillis() > 0
            ? Math.addExact(System.currentTimeMillis(), params.getTimeoutMillis())
            : 0;
    return new JavaSubprocess(start(builder), deadlineMillis);
  }

  /**
   * Returns a {@link java.lang.ProcessBuilder.Redirect} appropriate for the parameters. If a file
   * redirected to exists, deletes the file before redirecting to it.
   */
  private Redirect getRedirect(StreamAction action, File file) {
    switch (action) {
      case DISCARD:
        return Redirect.to(new File("/dev/null"));

      case REDIRECT:
        // We need to use Redirect.appendTo() here, because on older Linux kernels writes are
        // otherwise not atomic and might result in lost log messages:
        // https://lkml.org/lkml/2014/3/3/308
        if (file.exists()) {
          file.delete();
        }
        return Redirect.appendTo(file);

      case STREAM:
        return Redirect.PIPE;

      default:
        throw new IllegalStateException();
    }
  }
}
