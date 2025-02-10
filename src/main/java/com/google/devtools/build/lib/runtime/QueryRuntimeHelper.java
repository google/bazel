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
package com.google.devtools.build.lib.runtime;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.devtools.build.lib.query2.common.CommonQueryOptions;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.server.FailureDetails.Query.Code;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Helper for managing the {@link OutputStream} to which query/cquery/aquery results should be
 * written.
 */
public interface QueryRuntimeHelper extends AutoCloseable {
  /**
   * Returns the {@link OutputStream} to which to write query results. This {@link
   * QueryRuntimeHelper} instance, not the caller, is responsible for closing the {@link
   * OutputStream}.
   */
  OutputStream getOutputStreamForQueryOutput();

  /**
   * Should be called after the query is successfully evaluated and the entire query output is
   * written to the {@link OutputStream} returned by {@link #getOutputStreamForQueryOutput}.
   *
   * <p>In particular, this method shouldn't be called if query evaluation fails.
   */
  void afterQueryOutputIsWritten() throws QueryRuntimeHelperException, InterruptedException;

  /** Must be called at some point near the end of the life of the query command. */
  @Override
  void close() throws QueryRuntimeHelperException;

  /** Factory for {@link QueryRuntimeHelper} instances. */
  interface Factory {
    QueryRuntimeHelper create(CommandEnvironment env, CommonQueryOptions options)
        throws QueryRuntimeHelperException;
  }

  /**
   * A {@link Factory} for {@link StdoutQueryRuntimeHelper} instances that simply wrap the given
   * {@link CommandEnvironment} instance's stdout.
   *
   * <p>This is intended to be the default {@link Factory}.
   *
   * <p>If {@code --output_file} is set, the stdout is redirected to the defined path value instead.
   */
  class StdoutQueryRuntimeHelperFactory implements Factory {
    public static final StdoutQueryRuntimeHelperFactory INSTANCE =
        new StdoutQueryRuntimeHelperFactory();

    private StdoutQueryRuntimeHelperFactory() {}

    @Override
    public QueryRuntimeHelper create(CommandEnvironment env, CommonQueryOptions options)
        throws QueryRuntimeHelperException {
      if (Strings.isNullOrEmpty(options.outputFile)) {
        return createInternal(env.getReporter().getOutErr().getOutputStream());
      } else {
        return FileQueryRuntimeHelper.create(
            env.getWorkingDirectory().getRelative(options.outputFile));
      }
    }

    public QueryRuntimeHelper createInternal(OutputStream stdoutOutputStream) {
      return new StdoutQueryRuntimeHelper(stdoutOutputStream);
    }

    /** A QueryRuntimeHelper that simply wraps a {@link OutputStream} for stdout. */
    @VisibleForTesting
    public static class StdoutQueryRuntimeHelper implements QueryRuntimeHelper {
      private final OutputStream stdoutOutputStream;

      private StdoutQueryRuntimeHelper(OutputStream stdoutOutputStream) {
        this.stdoutOutputStream = stdoutOutputStream;
      }

      @Override
      public OutputStream getOutputStreamForQueryOutput() {
        return stdoutOutputStream;
      }

      @Override
      public void afterQueryOutputIsWritten() {}

      @Override
      public void close() {}
    }

    /**
     * A {@link QueryRuntimeHelper} that wraps a {@link java.io.FileOutputStream} instead of writing
     * to standard out, for improved performance.
     */
    public static class FileQueryRuntimeHelper implements QueryRuntimeHelper {
      private final Path path;
      private final OutputStream out;

      private FileQueryRuntimeHelper(Path path) throws IOException {
        this.path = path;
        this.out = path.getOutputStream();
      }

      public static FileQueryRuntimeHelper create(Path path) throws QueryRuntimeHelperException {
        try {
          return new FileQueryRuntimeHelper(path);
        } catch (IOException e) {
          throw new QueryRuntimeHelperException(
              "Could not open query output file " + path.getPathString(),
              Code.QUERY_OUTPUT_WRITE_FAILURE,
              e);
        }
      }

      @Override
      public OutputStream getOutputStreamForQueryOutput() {
        return out;
      }

      @Override
      public void afterQueryOutputIsWritten() {}

      @Override
      public void close() throws QueryRuntimeHelperException {
        try {
          out.close();
        } catch (IOException e) {
          throw new QueryRuntimeHelperException(
              "Could not close query output file " + path, Code.QUERY_OUTPUT_WRITE_FAILURE, e);
        }
      }
    }
  }

  /** Describes what went wrong in {@link QueryRuntimeHelper}. */
  class QueryRuntimeHelperException extends Exception {

    private final Code detailedCode;

    public QueryRuntimeHelperException(String message, FailureDetails.Query.Code detailedCode) {
      super(Preconditions.checkNotNull(message));
      this.detailedCode = detailedCode;
    }

    public QueryRuntimeHelperException(
        String message, FailureDetails.Query.Code detailedCode, Throwable cause) {
      super(Preconditions.checkNotNull(message), cause);
      this.detailedCode = detailedCode;
    }

    public FailureDetail getFailureDetail() {
      return FailureDetail.newBuilder()
          .setMessage(getMessage())
          .setQuery(Query.newBuilder().setCode(detailedCode))
          .build();
    }
  }
}
