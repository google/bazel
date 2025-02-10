// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import net.starlark.java.spelling.SpellChecker;

/**
 * Validates the result of {@link SingleExtensionEvalFunction}. This is done in a separate
 * SkyFunction so that the unvalidated value can be cached, avoiding a re-evaluation of the
 * extension, even if the `use_repo` imports provided by the user are incorrect.
 */
public class SingleExtensionFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, SingleExtensionFunctionException {
    ModuleExtensionId extensionId = (ModuleExtensionId) skyKey.argument();
    SingleExtensionUsagesValue usagesValue =
        (SingleExtensionUsagesValue) env.getValue(SingleExtensionUsagesValue.key(extensionId));
    if (usagesValue == null) {
      return null;
    }
    SingleExtensionValue evalOnlyValue =
        (SingleExtensionValue) env.getValue(SingleExtensionValue.evalKey(extensionId));
    if (evalOnlyValue == null) {
      return null;
    }

    // SingleExtensionEvalFunction doesn't handle the fixup warning so that bazel mod tidy doesn't
    // show it.
    evalOnlyValue.fixup().ifPresent(fixup -> env.getListener().handle(fixup.warning()));

    // Check that all imported repos have actually been generated.
    for (ModuleExtensionUsage usage : usagesValue.getExtensionUsages().values()) {
      for (ModuleExtensionUsage.Proxy proxy : usage.getProxies()) {
        for (Entry<String, String> repoImport : proxy.getImports().entrySet()) {
          if (!evalOnlyValue.generatedRepoSpecs().containsKey(repoImport.getValue())
              && !usagesValue.getRepoOverrides().containsKey(repoImport.getValue())) {
            throw new SingleExtensionFunctionException(
                ExternalDepsException.withMessage(
                    Code.INVALID_EXTENSION_IMPORT,
                    "module extension %s does not generate repository \"%s\", yet"
                        + " it is imported as \"%s\" in the usage at %s%s",
                    extensionId,
                    repoImport.getValue(),
                    repoImport.getKey(),
                    proxy.getLocation(),
                    SpellChecker.didYouMean(
                        repoImport.getValue(), evalOnlyValue.generatedRepoSpecs().keySet())),
                Transience.PERSISTENT);
          }
        }
      }
    }

    // Check that repo overrides apply as declared.
    for (ModuleExtensionUsage usage : usagesValue.getExtensionUsages().values()) {
      for (var override : usage.getRepoOverrides().entrySet()) {
        boolean repoExists = evalOnlyValue.generatedRepoSpecs().containsKey(override.getKey());
        if (repoExists && !override.getValue().mustExist()) {
          throw new SingleExtensionFunctionException(
              ExternalDepsException.withMessage(
                  Code.INVALID_EXTENSION_IMPORT,
                  "module extension %s generates repository \"%s\", yet"
                      + " it is injected via inject_repo() at %s. Use override_repo() instead to"
                      + " override an existing repository.",
                  extensionId,
                  override.getKey(),
                  override.getValue().location()),
              Transience.PERSISTENT);
        } else if (!repoExists && override.getValue().mustExist()) {
          throw new SingleExtensionFunctionException(
              ExternalDepsException.withMessage(
                  Code.INVALID_EXTENSION_IMPORT,
                  "module extension %s does not generate repository \"%s\", yet"
                      + " it is overridden via override_repo() at %s. Use inject_repo() instead to"
                      + " inject a new repository.",
                  extensionId,
                  override.getKey(),
                  override.getValue().location()),
              Transience.PERSISTENT);
        }
      }
    }

    return evalOnlyValue;
  }

  static final class SingleExtensionFunctionException extends SkyFunctionException {

    SingleExtensionFunctionException(ExternalDepsException cause, Transience transience) {
      super(cause, transience);
    }
  }
}
