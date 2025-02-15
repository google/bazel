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

package com.google.testing.junit.runner.model;

import com.google.testing.junit.junit4.runner.DynamicTestException;
import com.google.testing.junit.runner.sharding.ShardingEnvironment;
import com.google.testing.junit.runner.sharding.ShardingFilters;
import com.google.testing.junit.runner.util.TestClock;
import com.google.testing.junit.runner.util.TestClock.TestInstant;
import com.google.testing.junit.runner.util.TestIntegrationsRunnerIntegration;
import com.google.testing.junit.runner.util.TestPropertyRunnerIntegration;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.junit.runner.Description;
import org.junit.runner.manipulation.Filter;

/**
 * Model of the tests that will be run. The model is agnostic of the particular type of test run
 * (JUnit3 or JUnit4). The test runner uses this class to build the model, and then updates the
 * model during the test run.
 *
 * <p>The leaf nodes in the model are test cases; the other nodes are test suites.
 */
public class TestSuiteModel {
  private final TestSuiteNode rootNode;
  private final Map<Description, TestCaseNode> testCaseMap;
  private final Map<Description, TestNode> testsMap;
  private final TestClock testClock;
  private final AtomicBoolean wroteXml = new AtomicBoolean(false);
  private final XmlResultWriter xmlResultWriter;
  @Nullable private final Filter shardingFilter;

  private TestSuiteModel(Builder builder) {
    rootNode = builder.rootNode;
    testsMap = builder.testsMap;
    testCaseMap = filterTestCases(builder.testsMap);
    testClock = builder.testClock;
    shardingFilter = builder.shardingFilter;
    xmlResultWriter = builder.xmlResultWriter;
  }

  // VisibleForTesting
  public List<TestNode> getTopLevelTestSuites() {
    return rootNode.getChildren();
  }

  // VisibleForTesting
  public Description getTopLevelDescription() {
    return rootNode.getDescription();
  }

  /** Gets the sharding filter to use; {@link Filter#ALL} if not sharding. */
  public Filter getShardingFilter() {
    return shardingFilter;
  }

  /**
   * Returns the test case node with the given test description.
   *
   * <p>Note that in theory this should never return {@code null}, but if it did we would not want
   * to throw a {@code NullPointerException} because JUnit4 would catch the exception and remove our
   * test listener!
   */
  private TestCaseNode getTestCase(Description description) {
    // The description shouldn't be null, but in the test runner code we avoid throwing exceptions.
    return description == null ? null : testCaseMap.get(description);
  }

  private TestNode getTest(Description description) {
    // The description shouldn't be null, but in the test runner code we avoid throwing exceptions.
    if (description == null) {
      return null;
    }

    TestNode test = testsMap.get(description);
    if (test != null) {
      return test;
    }

    final Optional<Description> matchingSuite = testsMap.keySet().stream()
            .filter(d -> d != null && d.getTestClass() != null && d.isSuite() && d.getTestClass().equals(description.getTestClass()))
            .findAny();
    if (matchingSuite.isPresent()) {
      final TestSuiteNode suite = (TestSuiteNode)testsMap.get(matchingSuite.get());
      test = new TestCaseNode(description, suite);
      suite.addTestCase((TestCaseNode)test);
      testsMap.put(description, test);
      return test;
    }

    return null;
  }

  // VisibleForTesting
  public int getNumTestCases() {
    return testCaseMap.size();
  }

  /**
   * Indicate that the test run has started. This should be called after all filtering has been
   * completed.
   *
   * @param topLevelDescription the root {@link Description} node.
   */
  public void testRunStarted(Description topLevelDescription) {
    markChildrenAsPending(topLevelDescription);
  }

  private void markChildrenAsPending(Description node) {
    if (node.isTest()) {
      testPending(node);
    } else {
      for (Description child : node.getChildren()) {
        markChildrenAsPending(child);
      }
    }
  }

  /**
   * Indicate that the test case with the given key is scheduled to start.
   *
   * @param description key for a test case
   */
  private void testPending(Description description) {
    TestCaseNode testCase = getTestCase(description);
    if (testCase != null) {
      testCase.pending();
    }
  }

  /**
   * Indicate that the test case with the given key has started.
   *
   * @param description key for a test case
   */
  public void testStarted(Description description) {
    TestCaseNode testCase = getTestCase(description);
    if (testCase != null) {
      testCase.started(now());
      TestPropertyRunnerIntegration.setTestCaseForThread(testCase);
      TestIntegrationsRunnerIntegration.setTestCaseForThread(testCase);
    }
  }

  /** Indicate that the entire test run was interrupted. */
  public void testRunInterrupted() {
    rootNode.testInterrupted(now());
  }

  /**
   * Indicate that the test case with the given key has requested that a property be written in the
   * XML.
   *
   * <p>
   *
   * @param description key for a test case
   * @param name The property name.
   * @param value The property value.
   */
  public void testEmittedProperty(Description description, String name, String value) {
    TestCaseNode testCase = getTestCase(description);
    if (testCase != null) {
      testCase.exportProperty(name, value);
    }
  }

  /**
   * Adds a failure to the test with the given key. If the specified test is suite, the failure will
   * be added to all its children.
   *
   * @param description key for a test case
   */
  public void testFailure(Description description, Throwable throwable) {
    TestNode test = getTest(description);
    if (test != null) {
      if (throwable instanceof DynamicTestException) {
        DynamicTestException dynamicFailure = (DynamicTestException) throwable;
        test.dynamicTestFailure(dynamicFailure.getTest(), dynamicFailure.getCause(), now());
      } else {
        test.testFailure(throwable, now());
      }
    } else {
      // this is a test case dynamically added by the suite runner (such as mockito)
      TestSuiteNode testSuite =
          (TestSuiteNode)
              rootNode.getChildren().stream()
                  .filter(node -> node instanceof TestSuiteNode)
                  .filter(
                      node ->
                          node.getDescription().getTestClass().equals(description.getTestClass()))
                  .findAny()
                  .orElseThrow(() -> new IllegalStateException("expected to find test suite node"));
      TestCaseNode testCase = new TestCaseNode(description, testSuite);
      testsMap.put(description, testCase);
      testCaseMap.put(description, testCase);
      testSuite.addTestCase(testCase);
      // since this is the first time we're learning of this, the timing data will be incorrect :(
      testCase.testFailure(throwable, now());
    }
  }

  /**
   * Indicates that the test case with the given key was skipped
   *
   * @param description key for a test case
   */
  public void testSkipped(Description description) {
    TestNode test = getTest(description);
    if (test != null) {
      test.testSkipped(now());
    }
  }

  /**
   * Indicates that the test case with the given key was ignored or suppressed
   *
   * @param description key for a test case
   */
  public void testSuppressed(Description description) {
    TestNode test = getTest(description);
    if (test != null) {
      test.testSuppressed(now());
    }
  }

  /** Indicate that the test case with the given description has finished. */
  public void testFinished(Description description) {
    TestCaseNode testCase = getTestCase(description);
    if (testCase != null) {
      testCase.finished(now());
    }

    /*
     * Note: we don't call TestPropertyExporter, so if any properties are
     * exported before the next test runs, they will be associated with the
     * current test.
     */
  }

  private TestInstant now() {
    return testClock.now();
  }

  /**
   * Writes the model to XML
   *
   * @param outputStream stream to output to
   * @throws IOException if the underlying writer throws an exception
   */
  public void writeAsXml(OutputStream outputStream) throws IOException {
    write(new XmlWriter(outputStream));
  }

  // VisibleForTesting
  public void write(XmlWriter writer) throws IOException {
    if (wroteXml.compareAndSet(false, true)) {
      xmlResultWriter.writeTestSuites(writer, rootNode.getResult());
    }
  }

  @Override
  public int hashCode() {
    return toString().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof TestSuiteModel)) {
      return false;
    }
    TestSuiteModel that = (TestSuiteModel) obj;

    // We only use this for testing, so using toString() is good enough
    return this.toString().equals(that.toString());
  }

  @Override
  public String toString() {
    try {
      StringWriter stringWriter = new StringWriter();
      write(XmlWriter.createForTesting(stringWriter));
      return stringWriter.toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** A builder for creating a model of a test suite. */
  public static class Builder {
    private final TestClock testClock;
    private final Map<Description, TestNode> testsMap = new ConcurrentHashMap<>();
    private final ShardingEnvironment shardingEnvironment;
    private final ShardingFilters shardingFilters;
    private final XmlResultWriter xmlResultWriter;
    private TestSuiteNode rootNode;
    private Filter shardingFilter = Filter.ALL;
    private boolean buildWasCalled = false;

    @Inject
    public Builder(
        TestClock testClock,
        ShardingFilters shardingFilters,
        ShardingEnvironment shardingEnvironment,
        XmlResultWriter xmlResultWriter) {
      this.testClock = testClock;
      this.shardingFilters = shardingFilters;
      this.shardingEnvironment = shardingEnvironment;
      this.xmlResultWriter = xmlResultWriter;
    }

    /**
     * Build a model with the given name, including the given suites. This method should be called
     * before any command line filters are applied.
     */
    public TestSuiteModel build(String suiteName, Description... topLevelSuites) {
      return build(suiteName, Collections.emptyMap(), topLevelSuites);
    }

    /**
     * Build a model with the given name, including the given suites. This method should be called
     * before any command line filters are applied.
     *
     * <p>The given {@code properties} map will be applied to the root {@link TestSuiteNode}.
     */
    public TestSuiteModel build(
        String suiteName, Map<String, String> properties, Description... topLevelSuites) {
      if (buildWasCalled) {
        throw new IllegalStateException("Builder.build() was already called");
      }
      buildWasCalled = true;
      if (shardingEnvironment.isShardingEnabled()) {
        shardingFilter = getShardingFilter(topLevelSuites);
      }
      rootNode = new TestSuiteNode(Description.createSuiteDescription(suiteName), properties);
      for (Description topLevelSuite : topLevelSuites) {
        addTestSuite(rootNode, topLevelSuite);
        rootNode.getDescription().addChild(topLevelSuite);
      }
      return new TestSuiteModel(this);
    }

    private Filter getShardingFilter(Description... topLevelSuites) {
      Collection<Description> tests = new LinkedList<>();
      for (Description suite : topLevelSuites) {
        collectTests(suite, tests);
      }
      shardingEnvironment.touchShardFile();
      return shardingFilters.createShardingFilter(tests);
    }

    private static void collectTests(Description desc, Collection<Description> tests) {
      if (desc.isTest()) {
        tests.add(desc);
      } else {
        for (Description child : desc.getChildren()) {
          collectTests(child, tests);
        }
      }
    }

    private void addTestSuite(TestSuiteNode parentSuite, Description suiteDescription) {
      TestSuiteNode suite = new TestSuiteNode(suiteDescription);
      for (Description childDesc : suiteDescription.getChildren()) {
        if (childDesc.isTest()) {
          addTestCase(suite, childDesc);
        } else {
          addTestSuite(suite, childDesc);
        }
      }
      // Empty suites are pruned when sharding.
      if (shardingFilter == Filter.ALL || !suite.getChildren().isEmpty()) {
        parentSuite.addTestSuite(suite);
        testsMap.put(suiteDescription, suite);
      }
    }

    private void addTestCase(TestSuiteNode parentSuite, Description testCaseDesc) {
      if (!testCaseDesc.isTest()) {
        throw new IllegalArgumentException();
      }
      if (!shardingFilter.shouldRun(testCaseDesc)) {
        return;
      }
      TestCaseNode testCase = new TestCaseNode(testCaseDesc, parentSuite);
      testsMap.put(testCaseDesc, testCase);
      parentSuite.addTestCase(testCase);
    }
  }

  /**
   * Converts the values of the Map from {@link TestNode} to {@link TestCaseNode} filtering out null
   * values.
   */
  private static Map<Description, TestCaseNode> filterTestCases(Map<Description, TestNode> tests) {
    Map<Description, TestCaseNode> filteredAndConvertedTests = new HashMap<>();
    for (Description key : tests.keySet()) {
      TestNode testNode = tests.get(key);
      if (testNode instanceof TestCaseNode) {
        filteredAndConvertedTests.put(key, (TestCaseNode) testNode);
      }
    }
    return filteredAndConvertedTests;
  }
}
