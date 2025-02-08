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

package com.google.devtools.build.lib.starlarkbuildapi;

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ExecGroupCollectionApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainContextApi;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkValue;

/** Interface for a type containing information about the attributes of a rule. */
@StarlarkBuiltin(
    name = "rule_attributes",
    category = DocCategory.BUILTIN,
    doc = "Information about attributes of a rule an aspect is applied to.")
public interface StarlarkAttributesCollectionApi extends StarlarkValue {

  @StarlarkMethod(name = "attr", structField = true, doc = StarlarkRuleContextApi.ATTR_DOC)
  StructApi getAttr() throws EvalException;

  @StarlarkMethod(
      name = "executable",
      structField = true,
      doc = StarlarkRuleContextApi.EXECUTABLE_DOC)
  StructApi getExecutable() throws EvalException;

  @StarlarkMethod(name = "file", structField = true, doc = StarlarkRuleContextApi.FILE_DOC)
  StructApi getFile() throws EvalException;

  @StarlarkMethod(name = "files", structField = true, doc = StarlarkRuleContextApi.FILES_DOC)
  StructApi getFiles() throws EvalException;

  @StarlarkMethod(
      name = "kind",
      structField = true,
      doc = "The kind of a rule, such as 'cc_library'")
  String getRuleClassName() throws EvalException;

  @StarlarkMethod(
      name = "toolchains",
      structField = true,
      doc = "Toolchains for the default exec group of the rule the aspect is applied to.")
  ToolchainContextApi toolchains() throws EvalException;

  @StarlarkMethod(
      name = "exec_groups",
      structField = true,
      doc =
          "A collection of the execution groups available for the rule the aspect is applied to,"
              + " indexed by their names.")
  ExecGroupCollectionApi execGroups() throws EvalException;

  @StarlarkMethod(
      name = "var",
      structField = true,
      doc = "Dictionary (String to String) of configuration variables.")
  Dict<String, String> var() throws EvalException, InterruptedException;
}
