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

package com.google.devtools.build.lib.rules.proto;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProviderWrapper;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;

/**
 * Configured target classes that implement this class can contribute .proto files to the
 * compilation of proto_library rules.
 */
@Immutable
public final class ProtoInfo {
  public static final ProtoInfoProvider PROVIDER = new ProtoInfoProvider();

  /** Provider class for {@link ProtoInfo} objects. */
  public static class ProtoInfoProvider extends StarlarkProviderWrapper<ProtoInfo> {

    public ProtoInfoProvider() {
      this(ProtoConstants.PROTO_INFO_KEY);
    }

    public ProtoInfoProvider(BzlLoadValue.Key key) {
      super(key, "ProtoInfo");
    }

    @Override
    public ProtoInfo wrap(Info value) throws RuleErrorException {
      try {
        return new ProtoInfo((StarlarkInfo) value);
      } catch (EvalException e) {
        throw new RuleErrorException(e.getMessageWithStack());
      } catch (TypeException e) {
        throw new RuleErrorException(e.getMessage());
      }
    }
  }

  private final StarlarkInfo value;
  private final NestedSet<Artifact> transitiveProtoSources;

  private ProtoInfo(StarlarkInfo value) throws EvalException, TypeException {
    this.value = value;
    transitiveProtoSources =
        value.getValue("transitive_sources", Depset.class).getSet(Artifact.class);
  }

  public NestedSet<Artifact> getTransitiveProtoSources() {
    return transitiveProtoSources;
  }

  /** The {@code .proto} source files in this {@code proto_library}'s {@code srcs}. */
  @VisibleForTesting
  public ImmutableList<Artifact> getDirectSources() throws Exception {
    return Sequence.cast(
            value.getValue("_direct_proto_sources", Sequence.class),
            Artifact.class,
            "_direct_proto_sources")
        .getImmutableList();
  }

  /** The proto source files that are used in compiling this {@code proto_library}. */
  @VisibleForTesting
  public ImmutableList<Artifact> getDirectProtoSources() throws Exception {
    return Sequence.cast(
            value.getValue("direct_sources", Sequence.class), Artifact.class, "direct_sources")
        .getImmutableList();
  }

  @VisibleForTesting
  public NestedSet<String> getTransitiveProtoSourceRoots() throws Exception {
    return value.getValue("transitive_proto_path", Depset.class).getSet(String.class);
  }

  @VisibleForTesting
  public NestedSet<Artifact> getStrictImportableProtoSourcesForDependents() throws Exception {
    return value.getValue("check_deps_sources", Depset.class).getSet(Artifact.class);
  }

  /**
   * Be careful while using this artifact - it is the parsing of the transitive set of .proto files.
   * It's possible to cause a O(n^2) behavior, where n is the length of a proto chain-graph.
   * (remember that proto-compiler reads all transitive .proto files, even when producing the
   * direct-srcs descriptor set)
   */
  @VisibleForTesting
  public Artifact getDirectDescriptorSet() throws Exception {
    return value.getValue("direct_descriptor_set", Artifact.class);
  }

  @VisibleForTesting
  public NestedSet<Artifact> getTransitiveDescriptorSets() throws Exception {
    return value.getValue("transitive_descriptor_sets", Depset.class).getSet(Artifact.class);
  }

  /**
   * Returns a set of {@code .proto} sources that may be imported by {@code proto_library} targets
   * directly depending on this {@code ProtoInfo}.
   */
  @VisibleForTesting
  public NestedSet<Artifact> getExportedSources() throws Exception {
    return value.getValue("_exported_sources", Depset.class).getSet(Artifact.class);
  }
}
