// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Optional;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CppModuleMapApi;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;

/** Structure for C++ module maps. Stores the name of the module and a .cppmap artifact. */
@Immutable
public final class CppModuleMap implements CppModuleMapApi<Artifact> {
  public static final String SEPARATE_MODULE_SUFFIX = ".sep";

  // NOTE: If you add a field here, you'll likely need to update CppModuleMapAction.computeKey().
  private final Artifact artifact;
  private final String name;
  private final Optional<Artifact> umbrellaHeader;

  public CppModuleMap(Artifact artifact, String name) {
    this(artifact, Optional.absent(), name);
  }

  public CppModuleMap(Artifact artifact, Artifact umbrellaHeader, String name) {
    this(artifact, Optional.fromNullable(umbrellaHeader), name);
  }

  private CppModuleMap(Artifact artifact, Optional<Artifact> umbrellaHeader, String name) {
    this.artifact = artifact;
    this.umbrellaHeader = umbrellaHeader;
    this.name = name;
  }

  public Artifact getArtifact() {
    return artifact;
  }

  @Override
  public Artifact getArtifactForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return artifact;
  }

  public String getName() {
    return name;
  }

  /**
   * Returns an optional umbrella header artifact. The headers declared in module maps are compiled
   * using the #import directives which are incompatible with J2ObjC segmented headers. The headers
   * generated by J2ObjC need to be compiled using the #include directives. We can #include all the
   * headers in an umbrella header and then declare the umbrella header in the module map.
   */
  public Optional<Artifact> getUmbrellaHeader() {
    return umbrellaHeader;
  }

  @Override
  @Nullable
  public Artifact getUmbrellaHeaderForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    if (umbrellaHeader.isPresent()) {
      return umbrellaHeader.get();
    } else {
      return null;
    }
  }

  @Override
  public int hashCode() {
    // It would be incorrect for two CppModuleMap instances in the same build graph to have the same
    // artifact but different names or umbrella headers. Since Artifacts' hash codes are cached, use
    // only it for efficiency.
    return artifact.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof CppModuleMap) {
      CppModuleMap that = (CppModuleMap) other;
      return artifact.equals(that.artifact)
          && umbrellaHeader.equals(that.umbrellaHeader)
          && name.equals(that.name);
    }
    return false;
  }

  @Override
  public String toString() {
    return name + "@" + artifact;
  }

  /**
   * Specifies whether to generate an umbrella header.
   */
  public enum UmbrellaHeaderStrategy {
    /** Generate an umbrella header. */
    GENERATE,
    /** Do not generate an umbrella header. */
    DO_NOT_GENERATE
  }
}
