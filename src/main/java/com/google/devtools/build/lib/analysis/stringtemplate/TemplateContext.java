// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.stringtemplate;

/**
 * Interface to be implemented by callers of {@link TemplateExpander} which defines the expansion of
 * each template variable and function.
 */
public interface TemplateContext {

  /**
   * Returns the expansion of the specified template variable.
   *
   * @param name the variable to expand
   * @return the expansion of the variable
   * @throws ExpansionException if the given variable was not defined or there was any other error
   *     during expansion
   */
  String lookupVariable(String name) throws ExpansionException;

  /**
   * Returns the expansion of the specified template function with the given parameter.
   *
   * @param name the function name
   * @param param the function parameter
   * @return the expansion of the function applied to the parameter
   * @throws ExpansionException if the function was not defined, or if the function application
   *     failed for some reason
   */
  String lookupFunction(String name, String param) throws ExpansionException;
}