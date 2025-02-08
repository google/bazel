// Copyright 2010 The Bazel Authors. All Rights Reserved.
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

package com.google.testing.junit.runner.junit4;

import com.google.testing.junit.junit4.runner.RegExTestCaseFilter;
import com.google.testing.junit.junit4.runner.SuiteTrimmingFilter;
import com.google.testing.junit.runner.internal.Stdout;
import com.google.testing.junit.runner.internal.SystemExitDetectingShutdownHook;
import com.google.testing.junit.runner.internal.junit4.CancellableRequestFactory;
import com.google.testing.junit.runner.model.TestSuiteModel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.junit.internal.runners.ErrorReportingRunner;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

/**
 * Main entry point for running JUnit4 tests.<p>
 */
public class JUnit4Runner {
  private final Request request;
  private final CancellableRequestFactory requestFactory;
  private final Supplier<TestSuiteModel> modelSupplier;
  private final PrintStream testRunnerOut;
  private final JUnit4Config config;
  private final Set<RunListener> runListeners;
  private final Set<Initializer> initializers;

  /**
   * Creates a runner.
   */
  @Inject
  JUnit4Runner(
      Request request,
      CancellableRequestFactory requestFactory,
      Supplier<TestSuiteModel> modelSupplier,
      @Stdout PrintStream testRunnerOut,
      JUnit4Config config,
      Set<RunListener> runListeners,
      Set<Initializer> initializers) {
    this.request = request;
    this.requestFactory = requestFactory;
    this.modelSupplier = modelSupplier;
    this.config = config;
    this.testRunnerOut = testRunnerOut;
    this.runListeners = runListeners;
    this.initializers = initializers;
  }

  /**
   * Runs the JUnit4 test.
   *
   * @return Result of running the test
   */
  public Result run() {
    testRunnerOut.println("JUnit4 Test Runner");
    checkJUnitRunnerApiVersion();

    for (Initializer init : initializers) {
      init.initialize();
    }

    // Sharding
    TestSuiteModel model = modelSupplier.get();
    Filter shardingFilter = model.getShardingFilter();

    Request filteredRequest = applyFilters(request, shardingFilter,
        config.getTestIncludeFilterRegexp(),
        config.getTestExcludeFilterRegexp());

    JUnitCore core = new JUnitCore();
    for (RunListener runListener : runListeners) {
      core.addListener(runListener);
    }
    if (config.getTestRunnerFailFast()) {
      core.addListener(new StopOnFailureRunListener(requestFactory));
    }

    File exitFile = getExitFile();
    exitFileActive(exitFile);
    Thread shutdownHook = SystemExitDetectingShutdownHook.newShutdownHook(testRunnerOut);
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    try {
      Request cancellableRequest = requestFactory.createRequest(filteredRequest);
      return core.run(cancellableRequest);
    } finally {
      exitFileInactive(exitFile);
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }
  }

  // Support for "premature exit files": Tests may write this to communicate
  // to the runner in case of premature exit.
  private static File getExitFile() {
    String exitFile = System.getenv("TEST_PREMATURE_EXIT_FILE");
    return exitFile == null ? null : new File(exitFile);
  }

  private static void exitFileActive(@Nullable File file) {
    if (file != null) {
      try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
        // Overwrite file content.
        outputStream.write(new byte[0]);
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException("Could not write exit file at " + file, e);
      }
    }
  }

  private void exitFileInactive(@Nullable File file) {
    if (file != null) {
      try {
        file.delete();
      } catch (Throwable t) {
        // Just print the stack trace, to avoid masking a real test failure.
        t.printStackTrace(testRunnerOut);
      }
    }
  }

  // VisibleForTesting
  TestSuiteModel getModel() {
    return modelSupplier.get();
  }

  private static Request applyFilter(Request request, Filter filter)
      throws NoTestsRemainException {
    Runner runner = request.getRunner();
    new SuiteTrimmingFilter(filter).apply(runner);
    return Request.runner(runner);
  }

  /**
   * Apply command-line and sharding filters, if appropriate.<p>
   *
   * Note that this is carefully written to avoid running into potential
   * problems with the way runners implement filtering. The JavaDoc for
   * {@link org.junit.runner.manipulation.Filterable} states that tests that
   * don't match the filter should be removed, which implies if you apply two
   * filters, you will always get an intersection of the two. Unfortunately, the
   * filtering implementation of {@link org.junit.runners.ParentRunner} does not
   * do this, and instead uses a "last applied filter wins" strategy.<p>
   *
   * We work around potential problems by ensuring that if we apply a second
   * filter, the filter is more restrictive than the first. We also assume that
   * if filtering fails, the request will have a runner that is a
   * {@link ErrorReportingRunner}. Luckily, we can cover this with tests to make
   * sure we don't break if JUnit changes in the future.
   *
   * @param request Request to filter
   * @param shardingFilter Sharding filter to use; {@link Filter#ALL} to not do sharding
   * @param testIncludeFilterRegexp String denoting a regular expression with which
   *     to filter tests.  Only test descriptions that match this regular
   *     expression will be run.  If {@code null}, tests will not be filtered.
   * @param testExcludeFilterRegexp String denoting a regular expression with which
   *     to filter tests.  Only test descriptions that do not match this regular
   *     expression will be run.  If {@code null}, tests will not be filtered.
   * @return Filtered request (may be a request that delegates to
   *         {@link ErrorReportingRunner}
   */
  private static Request applyFilters(Request request, Filter shardingFilter,
      @Nullable String testIncludeFilterRegexp, @Nullable String testExcludeFilterRegexp) {
    // Allow the user to specify a filter on the command line
    boolean allowNoTests = false;
    Filter filter = Filter.ALL;
    if (testIncludeFilterRegexp != null) {
      filter = RegExTestCaseFilter.include(testIncludeFilterRegexp);
    }

    if (testExcludeFilterRegexp != null) {
      Filter excludeFilter = RegExTestCaseFilter.exclude(testExcludeFilterRegexp);
      filter = filter.intersect(excludeFilter);
    }

    if (testIncludeFilterRegexp != null || testExcludeFilterRegexp != null) {
      try {
        request = applyFilter(request, filter);
      } catch (NoTestsRemainException e) {
        return createErrorReportingRequestForFilterError(filter);
      }

      /*
       * If you filter a sharded test to run one test, we don't want all the
       * shards but one to fail.
       */
      allowNoTests = (shardingFilter != Filter.ALL);
    }

    // Sharding
    if (shardingFilter != Filter.ALL) {
      filter = filter.intersect(shardingFilter);
    }

    if (filter != Filter.ALL) {
      try {
        request = applyFilter(request, filter);
      } catch (NoTestsRemainException e) {
        if (allowNoTests) {
          return Request.runner(new NoOpRunner());
        } else {
          return createErrorReportingRequestForFilterError(filter);
        }
      }
    }
    return request;
  }

  @SuppressWarnings({"ThrowableInstanceNeverThrown"})
  private static Request createErrorReportingRequestForFilterError(Filter filter) {
    ErrorReportingRunner runner = new ErrorReportingRunner(Filter.class, new Exception(
        String.format("No tests found matching %s", filter.describe())));
    return Request.runner(runner);
  }

  private void checkJUnitRunnerApiVersion() {
    config.getJUnitRunnerApiVersion();
  }

  static class NoOpRunner extends Runner {
    @Override
    public Description getDescription() {
      return Description.createTestDescription(getClass(), "nothingToDo");
    }

    @Override
    public void run(RunNotifier notifier) {
    }
  }

  /**
   * A simple initializer which can be used to provide additional initialization logic in custom
   * runners.
   *
   * <p>Initializers will be run in unspecified order. If an exception is thrown it will not be
   * deemed recoverable and will cause the runner to error-out.
   */
  public interface Initializer {
    void initialize();
  }

  /** RunListener that requests test execution to stop upon first failure. */
  private static class StopOnFailureRunListener extends RunListener {

    private final CancellableRequestFactory cancellableRequestFactory;

    public StopOnFailureRunListener(CancellableRequestFactory cancellableRequestFactory) {
      this.cancellableRequestFactory = cancellableRequestFactory;
    }

    @Override
    public void testFailure(Failure failure) throws Exception {
      cancellableRequestFactory.cancelRunOrderly();
    }
  }
}
