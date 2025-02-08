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

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import java.util.Optional;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.syntax.Location;

/**
 * A module extension object, which can be used to perform arbitrary logic in order to create repos.
 *
 * @param definingBzlFileLabel The .bzl file where the module extension object was originally
 *     defined.
 *     <p>Note that if the extension object was then loaded and re-exported by a different .bzl file
 *     before being used in a MODULE.bazel file, the output of this function may differ from the
 *     corresponding ModuleExtensionUsage#getExtensionBzlFile and ModuleExtensionId#getBzlFileLabel.
 */
public record ModuleExtension(
    StarlarkCallable implementation,
    ImmutableMap<String, TagClass> tagClasses,
    Optional<String> doc,
    Label definingBzlFileLabel,
    Location location,
    ImmutableList<String> envVariables,
    boolean osDependent,
    boolean archDependent)
    implements StarlarkValue {
  public ModuleExtension {
    requireNonNull(implementation, "implementation");
    requireNonNull(tagClasses, "tagClasses");
    requireNonNull(doc, "doc");
    requireNonNull(definingBzlFileLabel, "definingBzlFileLabel");
    requireNonNull(location, "location");
    requireNonNull(envVariables, "envVariables");
  }

  public static Builder builder() {
    return new AutoBuilder_ModuleExtension_Builder();
  }

  /** Builder for {@link ModuleExtension}. */
  @AutoBuilder
  public abstract static class Builder {
    public abstract Builder setDoc(Optional<String> value);

    public abstract Builder setDefiningBzlFileLabel(Label value);

    public abstract Builder setLocation(Location value);

    public abstract Builder setImplementation(StarlarkCallable value);

    public abstract Builder setTagClasses(ImmutableMap<String, TagClass> value);

    public abstract Builder setEnvVariables(ImmutableList<String> value);

    public abstract Builder setOsDependent(boolean osDependent);

    public abstract Builder setArchDependent(boolean archDependent);

    public abstract ModuleExtension build();
  }
}
