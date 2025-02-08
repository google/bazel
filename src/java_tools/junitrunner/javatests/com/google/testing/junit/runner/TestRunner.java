// Copyright 2016 The Bazel Authors. All Rights Reserved.
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

package com.google.testing.junit.runner;

import java.util.ArrayList;
import java.util.List;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;

/**
 * A straightforward JUnit test runner that runs the test in the specified class using
 * {@link JUnitCore}.
 */
public class TestRunner {
  private static final String PACKAGE = TestRunner.class.getPackage().getName();

  private TestRunner() {}

  public static void main(String[] args) throws ClassNotFoundException {
    if (args.length == 0) {
      throw new IllegalArgumentException(
          "Must specify at least one argument (source files of the tests to run)!");
    }

    JUnitCore junitCore = new JUnitCore();
    junitCore.addListener(new TestListener());
    Request request = createRequest(args);
    Result result = junitCore.run(request);

    System.exit(result.wasSuccessful() ? 0 : 1);
  }

  private static Request createRequest(String[] filepaths) throws ClassNotFoundException {
    List<Class<?>> classes = new ArrayList<>(filepaths.length);
    for (String path : filepaths) {
      classes.add(getClass(path));
    }
    return Request.classes(classes.toArray(new Class<?>[0]));
  }

  private static Class<?> getClass(String filepath) throws ClassNotFoundException {
    String className = filepath.replace('/', '.');
    if (filepath.endsWith(".java")) {
      className = className.substring(0, className.length() - 5);
    }
    return Class.forName(PACKAGE + "." + className);
  }
}
