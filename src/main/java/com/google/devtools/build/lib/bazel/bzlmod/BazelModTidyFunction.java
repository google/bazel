// Copyright 2022 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/**
 * Computes all information required for the {@code bazel mod tidy} command, which in particular
 * requires evaluating all module extensions used by the root module.
 */
public class BazelModTidyFunction implements SkyFunction {

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, SkyFunctionException {
    RootModuleFileValue rootModuleFileValue =
        (RootModuleFileValue) env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    if (rootModuleFileValue == null) {
      return null;
    }
    BazelDepGraphValue depGraphValue = (BazelDepGraphValue) env.getValue(BazelDepGraphValue.KEY);
    if (depGraphValue == null) {
      return null;
    }
    RepositoryMappingValue bazelToolsRepoMapping =
        (RepositoryMappingValue)
            env.getValue(RepositoryMappingValue.key(RepositoryName.BAZEL_TOOLS));
    if (bazelToolsRepoMapping == null) {
      return null;
    }
    Label buildozerLabel;
    try {
      buildozerLabel =
          Label.parseWithRepoContext(
              // This label always has the ".exe" extension, even on Unix, to get a single static
              // label that works on all platforms.
              "@buildozer_binary//:buildozer.exe",
              Label.RepoContext.of(
                  RepositoryName.BAZEL_TOOLS, bazelToolsRepoMapping.repositoryMapping()));
    } catch (LabelSyntaxException e) {
      throw new IllegalStateException(e);
    }
    RootedPath buildozer;
    try {
      buildozer = RepositoryFunction.getRootedPathFromLabel(buildozerLabel, env);
    } catch (NeedsSkyframeRestartException e) {
      return null;
    } catch (EvalException e) {
      throw new IllegalStateException(e);
    }

    ImmutableSet<SkyKey> extensionsUsedByRootModule =
        depGraphValue.getExtensionUsagesTable().column(ModuleKey.ROOT).keySet().stream()
            // Use the eval-only key to avoid errors caused by incorrect imports - we can fix them.
            .map(SingleExtensionValue::evalKey)
            .collect(toImmutableSet());
    SkyframeLookupResult result = env.getValuesAndExceptions(extensionsUsedByRootModule);
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableList.Builder<RootModuleFileFixup> fixups = ImmutableList.builder();
    ImmutableList.Builder<ExternalDepsException> errors = ImmutableList.builder();
    for (SkyKey extension : extensionsUsedByRootModule) {
      SkyValue value;
      try {
        value = result.getOrThrow(extension, ExternalDepsException.class);
      } catch (ExternalDepsException e) {
        // This extension failed, but we can still tidy up other extensions in keep going mode.
        errors.add(e);
        env.getListener().handle(Event.error(e.getMessage()));
        continue;
      }
      if (value == null) {
        return null;
      }
      if (result.get(extension) instanceof SingleExtensionValue evalValue) {
        evalValue.fixup().ifPresent(fixups::add);
      }
    }

    return BazelModTidyValue.create(
        fixups.build(),
        buildozer.asPath(),
        rootModuleFileValue.getModuleFilePaths(),
        errors.build());
  }
}
