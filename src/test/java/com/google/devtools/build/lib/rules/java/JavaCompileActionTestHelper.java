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

package com.google.devtools.build.lib.rules.java;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.devtools.build.buildjar.OptionsParser;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.StrictDepsMode;
import java.util.List;
import java.util.Set;

// TODO(djasper): Investigate removing this class and moving the functions to JavaCompileAction.
/**
 * A collection of utilities for extracting the values of command-line options passed to JavaBuilder
 * from a Java compilation action , for testing.
 */
public final class JavaCompileActionTestHelper {

  public static final String JAVA_LANGUAGE_VERSION_FOR_TESTING = "21";

  public static Set<String> getDirectJars(JavaCompileAction javac) throws Exception {
    return getOptions(javac).directJars();
  }

  public static List<String> getProcessorNames(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getProcessorNames();
  }

  public static List<String> getProcessorPath(JavaCompileAction javac) throws Exception {
    return getProcessorpath(javac);
  }

  public static List<String> getProcessorpath(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getProcessorPath();
  }

  public static List<String> getJavacOpts(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getJavacOpts();
  }

  public static List<String> getSourceFiles(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getSourceFiles();
  }

  public static List<String> getSourceJars(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getSourceJars();
  }

  public static StrictDepsMode getStrictJavaDepsMode(JavaCompileAction javac) throws Exception {
    String strictJavaDeps = getOptions(javac).getStrictJavaDeps();
    return strictJavaDeps != null ? StrictDepsMode.valueOf(strictJavaDeps) : StrictDepsMode.OFF;
  }

  public static List<String> getClasspath(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getClassPath();
  }

  public static Set<String> getCompileTimeDependencyArtifacts(JavaCompileAction javac)
      throws Exception {
    return getOptions(javac).getDepsArtifacts();
  }

  public static String getFixDepsTool(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getFixDepsTool();
  }

  public static List<String> getBootClassPath(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getBootClassPath();
  }

  public static String getSystem(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getSystem();
  }

  public static List<String> getSourcePathEntries(JavaCompileAction javac) throws Exception {
    return getOptions(javac).getSourcePath();
  }

  /** Returns the JavaBuilder command line, up to the main class or deploy jar. */
  public static List<String> getJavacCommand(JavaCompileAction action) throws Exception {
    List<String> args = action.getCommandLines().allArguments();
    return args.subList(0, mainClassIndex(args));
  }

  /** Returns the JavaBuilder options. */
  public static List<String> getJavacArguments(JavaCompileAction action) throws Exception {
    List<String> args = action.getCommandLines().allArguments();
    return args.subList(mainClassIndex(args), args.size());
  }

  // Find the index of the last argument of the JavaBuilder command, and before the first option
  // that is passed to JavaBuilder.
  private static int mainClassIndex(List<String> args) {
    for (int idx = 0; idx < args.size(); idx++) {
      String arg = args.get(idx);
      if (arg.equals("-jar")) {
        return idx + 2;
      }
      if (arg.contains("JavaBuilder") && !arg.endsWith(".jar")) {
        return idx + 1;
      }
    }
    throw new IllegalStateException(args.toString());
  }

  private static OptionsParser getOptions(JavaCompileAction javac) throws Exception {
    checkArgument(
        javac.getMnemonic().equals("Javac"),
        "expected a Javac action, was %s",
        javac.getMnemonic());
    return new OptionsParser(getJavacArguments(javac));
  }

  private JavaCompileActionTestHelper() {}
}
