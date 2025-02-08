// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.blackbox.junit;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.blackbox.bazel.BlackBoxTestEnvironmentImpl;
import com.google.devtools.build.lib.blackbox.bazel.CrossToolsSetup;
import com.google.devtools.build.lib.blackbox.bazel.CxxToolsSetup;
import com.google.devtools.build.lib.blackbox.bazel.DefaultToolsSetup;
import com.google.devtools.build.lib.blackbox.bazel.JavaToolsSetup;
import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestContext;
import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestEnvironment;
import com.google.devtools.build.lib.blackbox.framework.PathUtils;
import com.google.devtools.build.lib.blackbox.framework.ToolsSetup;
import com.google.devtools.build.lib.util.OS;
import java.nio.file.Path;
import java.util.List;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;

/**
 * Abstract base class for all JUnit integration tests for Bazel and Blaze.
 *
 * <p>Reuses {@link BlackBoxTestEnvironment} for all the test methods in the class. Initializes the
 * test environment and creates the test context for thet concrete test methods. Alternatively,
 * {@link #setUp()} method can be overridden in concrete tests, and {@link
 * #prepareEnvironment(ImmutableList)} method be called for each test method separately to let them
 * initialize the unique set of tools.
 *
 * <p>See {@link BlackBoxTestEnvironment}, {@link BlackBoxTestContext}
 */
public abstract class AbstractBlackBoxTest {
  public static final List<ToolsSetup> DEFAULT_TOOLS =
      ImmutableList.of(
          new DefaultToolsSetup(),
          new JavaToolsSetup(),
          new CxxToolsSetup(),
          new CrossToolsSetup());
  protected static final String MODULE_DOT_BAZEL = "MODULE.bazel";

  @Rule public TestName testName = new TestName();

  /**
   * Shares the common infrastructure of a test group (execution service), serves as a test context
   * factory.
   */
  private static BlackBoxTestEnvironment testEnvironment;
  /** Test context, available to the concrete test methods through a getter {@link #context()} */
  private BlackBoxTestContext context;

  @BeforeClass
  public static void beforeClass() {
    testEnvironment = new BlackBoxTestEnvironmentImpl();
  }

  @AfterClass
  public static void afterClass() {
    testEnvironment.dispose();
  }

  @Before
  public void setUp() throws Exception {
    prepareEnvironment(getAdditionalTools());
  }

  @After
  public void tearDown() throws Exception {
    if (context != null) {
      try {
        context.bazel().shutdown();
      } finally {
        Path workDir = context.getWorkDir();
        if (workDir != null) {
          PathUtils.deleteTreeWithRetry(workDir);
        }
      }
    }
  }

  /**
   * Prepares the test environment for the test method and set the test context.
   *
   * @param tools all {@link ToolsSetup} to be called during environment initialization
   * @throws Exception if any {@link ToolsSetup} call fails
   */
  protected void prepareEnvironment(ImmutableList<ToolsSetup> tools) throws Exception {
    context = testEnvironment.prepareEnvironment(testName.getMethodName(), tools);
  }

  /**
   * Getter method for test context. Concrete test methods should only use the test context, but not
   * modify it.
   *
   * @return test context
   */
  protected BlackBoxTestContext context() {
    return context;
  }

  /**
   * Concrete test can either override this method to provide the list of additional tools besides
   * {@link #DEFAULT_TOOLS} to be initialized for all test methods, or call {@link
   * #prepareEnvironment(ImmutableList)} in each test method separately, passing the list of tools
   *
   * @return the list of {@link ToolsSetup} to be called in environment initialization
   */
  protected ImmutableList<ToolsSetup> getAdditionalTools() {
    return ImmutableList.of();
  }

  /**
   * Check if we are running tests on Windows
   *
   * @return True, if we are running tests on Windows; False, if we are running tests on other
   *     platforms.
   */
  protected static boolean isWindows() {
    return OS.WINDOWS.equals(OS.getCurrent());
  }
}
