// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.common.options;

import static java.util.stream.Collectors.toList;

import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.util.Classpath;
import com.google.devtools.build.lib.util.Classpath.ClassPathException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test to make sure all {@link Option}-annotated fields in <i>Prod</i> code have an {@link
 * Option#defaultValue()} that a corresponding {@link Option#converter()} can handle.<br>
 * {@link Option}-annotated field is considered to be in <i>Prod</i> code if its declaring class and
 * all its enclosing classes do not have {@link RunWith} annotation.
 *
 * @see OptionDefinition#getDefaultValue
 */
@RunWith(Parameterized.class)
public class OptionDefaultValueConversionTest {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Parameter public OptionDefinition optionDefinitionUnderTest;

  @Test
  public void shouldConvertDefaultValue() {
    // assert
    thrown = ExpectedException.none();

    // act
    optionDefinitionUnderTest.getDefaultValue(/*conversionContext=*/ null);
  }

  @Parameters
  public static List<OptionDefinition> getAllProdOptionDefinitions() {
    try {
      Set<Class<?>> allClasses = Classpath.findClasses("com.google.devtools");

      List<OptionDefinition> optionDefinitions =
          allClasses.stream()
              // This package contains classes that reference other classes that aren't available
              // without manual setup.
              .filter(
                  c -> !c.getPackageName().equals("com.google.devtools.build.lib.profiler.memory"))
              .filter(c -> !isTestClass(c))
              .flatMap(c -> Arrays.stream(c.getFields()))
              .filter(f -> f.isAnnotationPresent(Option.class))
              .map(FieldOptionDefinition::extractOptionDefinition)
              .collect(toList());
      logger.atFine().log(
          "Found %d Option-annotated fields in Prod code", optionDefinitions.size());

      return optionDefinitions;
    } catch (ClassPathException ex) {
      throw new RuntimeException("Unable to scan classpath", ex);
    }
  }

  private static boolean isTestClass(Class<?> initialClazz) {
    Class<?> clazz = initialClazz;
    do {
      if (clazz.isAnnotationPresent(RunWith.class)) {
        logger.atFiner().log("Filtered out %s: is a Test class", initialClazz);
        return true;
      }
      clazz = clazz.getEnclosingClass();
    } while (clazz != null);

    return false;
  }
}
