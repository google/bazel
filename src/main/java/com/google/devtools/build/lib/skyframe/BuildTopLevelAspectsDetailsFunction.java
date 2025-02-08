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

package com.google.devtools.build.lib.skyframe;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.AspectCollection;
import com.google.devtools.build.lib.analysis.AspectCollection.AspectCycleOnPathException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.AspectsList;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.StarlarkAspect;
import com.google.devtools.build.lib.packages.StarlarkAspectClass;
import com.google.devtools.build.lib.packages.StarlarkDefinedAspect;
import com.google.devtools.build.lib.server.FailureDetails.Analysis.Code;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/**
 * {@link SkyFunction} to load top level aspects, build the dependency relation between them based
 * on the aspects required by the top level aspects and the aspect providers they require and
 * advertise using {@link AspectCollection}.
 *
 * <p>This is needed to compute the relationship between top-level aspects once for all top-level
 * targets in the command. The {@link SkyValue} of this function contains a list of {@link
 * AspectDetails} objects which contain the aspect descriptor and a list of the used aspects this
 * aspect depends on. Then {@link ToplevelStarlarkAspectFunction} adds the target information to
 * create {@link AspectKey}.
 */
final class BuildTopLevelAspectsDetailsFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws BuildTopLevelAspectsDetailsFunctionException, InterruptedException {

    BuildTopLevelAspectsDetailsKey topLevelAspectsDetailsKey =
        (BuildTopLevelAspectsDetailsKey) skyKey.argument();

    ImmutableList<Aspect> topLevelAspects =
        getTopLevelAspects(
            env,
            topLevelAspectsDetailsKey.getTopLevelAspectsClasses(),
            topLevelAspectsDetailsKey.getTopLevelAspectsParameters());

    if (topLevelAspects == null) {
      return null; // some aspects are not loaded
    }

    AspectCollection aspectCollection;
    try {
      aspectCollection = AspectCollection.create(topLevelAspects);
    } catch (AspectCycleOnPathException e) {
      // This exception should never happen because aspects duplicates are not allowed in top-level
      // aspects and their existence should have been caught and reported by `getTopLevelAspects()`.
      env.getListener().handle(Event.error(e.getMessage()));
      throw new BuildTopLevelAspectsDetailsFunctionException(
          new TopLevelAspectsDetailsBuildFailedException(
              e.getMessage(), Code.ASPECT_CREATION_FAILED));
    }
    return new BuildTopLevelAspectsDetailsValue(getTopLevelAspectsDetails(aspectCollection));
  }

  @Nullable
  private static StarlarkDefinedAspect loadStarlarkAspect(
      Environment env, StarlarkAspectClass aspectClass)
      throws InterruptedException, BuildTopLevelAspectsDetailsFunctionException {
    StarlarkDefinedAspect starlarkAspect;
    try {
      BzlLoadValue bzlLoadValue =
          (BzlLoadValue)
              env.getValueOrThrow(
                  AspectFunction.bzlLoadKeyForStarlarkAspect(aspectClass),
                  BzlLoadFailedException.class);
      if (bzlLoadValue == null) {
        return null;
      }
      starlarkAspect = AspectFunction.loadAspectFromBzl(aspectClass, bzlLoadValue);
    } catch (BzlLoadFailedException | AspectCreationException e) {
      env.getListener().handle(Event.error(e.getMessage()));
      throw new BuildTopLevelAspectsDetailsFunctionException(
          new TopLevelAspectsDetailsBuildFailedException(
              e.getMessage(), Code.ASPECT_CREATION_FAILED));
    }
    return starlarkAspect;
  }

  @Nullable
  private static ImmutableList<Aspect> getTopLevelAspects(
      Environment env,
      ImmutableList<AspectClass> topLevelAspectsClasses,
      ImmutableMap<String, String> topLevelAspectsParameters)
      throws InterruptedException, BuildTopLevelAspectsDetailsFunctionException {
    AspectsList.Builder builder = new AspectsList.Builder();

    for (AspectClass aspectClass : topLevelAspectsClasses) {
      if (aspectClass instanceof StarlarkAspectClass starlarkAspectClass) {
        StarlarkAspect starlarkAspect = loadStarlarkAspect(env, starlarkAspectClass);
        if (starlarkAspect == null) {
          return null;
        }
        try {
          builder.addAspect(starlarkAspect);
        } catch (EvalException e) {
          env.getListener().handle(Event.error(e.getInnermostLocation(), e.getMessageWithStack()));
          throw new BuildTopLevelAspectsDetailsFunctionException(
              new TopLevelAspectsDetailsBuildFailedException(
                  e.getMessage(), Code.ASPECT_CREATION_FAILED));
        }
      } else {
        try {
          builder.addAspect((NativeAspectClass) aspectClass);
        } catch (AssertionError e) {
          env.getListener().handle(Event.error(e.getMessage()));
          throw new BuildTopLevelAspectsDetailsFunctionException(
              new TopLevelAspectsDetailsBuildFailedException(
                  e.getMessage(), Code.ASPECT_CREATION_FAILED));
        }
      }
    }

    AspectsList aspectsList = builder.build();
    try {
        aspectsList.validateTopLevelAspectsParameters(topLevelAspectsParameters);
        return aspectsList.buildAspects(topLevelAspectsParameters);
    } catch (EvalException e) {
      env.getListener().handle(Event.error(e.getInnermostLocation(), e.getMessageWithStack()));
      throw new BuildTopLevelAspectsDetailsFunctionException(
          new TopLevelAspectsDetailsBuildFailedException(
              e.getMessage(), Code.ASPECT_CREATION_FAILED));
    }
  }

  private static Collection<AspectDetails> getTopLevelAspectsDetails(
      AspectCollection aspectCollection) {
    Map<AspectDescriptor, AspectDetails> result = new HashMap<>();
    for (AspectCollection.AspectDeps aspectDeps : aspectCollection.getUsedAspects()) {
      buildAspectDetails(aspectDeps, result);
    }
    return result.values();
  }

  private static AspectDetails buildAspectDetails(
      AspectCollection.AspectDeps aspectDeps, Map<AspectDescriptor, AspectDetails> result) {
    if (result.containsKey(aspectDeps.aspect())) {
      return result.get(aspectDeps.aspect());
    }

    ImmutableList.Builder<AspectDetails> dependentAspects = ImmutableList.builder();
    for (AspectCollection.AspectDeps path : aspectDeps.usedAspects()) {
      dependentAspects.add(buildAspectDetails(path, result));
    }

    AspectDetails aspectDetails = new AspectDetails(dependentAspects.build(), aspectDeps.aspect());
    result.put(aspectDetails.getAspectDescriptor(), aspectDetails);
    return aspectDetails;
  }

  private static final class BuildTopLevelAspectsDetailsFunctionException
      extends SkyFunctionException {
    BuildTopLevelAspectsDetailsFunctionException(TopLevelAspectsDetailsBuildFailedException cause) {
      super(cause, Transience.PERSISTENT);
    }
  }

  /**
   * Details of the top-level aspects including the {@link AspectDescriptor} and a list of the
   * aspects it depends on. This is used to build the {@link AspectKey} when combined with
   * configured target details.
   */
  static final class AspectDetails {
    private final ImmutableList<AspectDetails> usedAspects;
    private final AspectDescriptor aspectDescriptor;

    private AspectDetails(
        ImmutableList<AspectDetails> usedAspects, AspectDescriptor aspectDescriptor) {
      this.usedAspects = usedAspects;
      this.aspectDescriptor = aspectDescriptor;
    }

    public AspectDescriptor getAspectDescriptor() {
      return aspectDescriptor;
    }

    public ImmutableList<AspectDetails> getUsedAspects() {
      return usedAspects;
    }
  }

  /** {@link SkyKey} for building top-level aspects details. */
  @AutoCodec
  static final class BuildTopLevelAspectsDetailsKey implements SkyKey {
    private static final SkyKeyInterner<BuildTopLevelAspectsDetailsKey> interner =
        SkyKey.newInterner();

    private final ImmutableList<AspectClass> topLevelAspectsClasses;
    private final ImmutableMap<String, String> topLevelAspectsParameters;
    private final int hashCode;

    static BuildTopLevelAspectsDetailsKey create(
        ImmutableList<AspectClass> topLevelAspectsClasses,
        ImmutableMap<String, String> topLevelAspectsParameters) {
      return interner.intern(
          new BuildTopLevelAspectsDetailsKey(
              topLevelAspectsClasses,
              topLevelAspectsParameters,
              Objects.hashCode(topLevelAspectsClasses, topLevelAspectsParameters)));
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static BuildTopLevelAspectsDetailsKey intern(BuildTopLevelAspectsDetailsKey key) {
      return interner.intern(key);
    }

    private BuildTopLevelAspectsDetailsKey(
        ImmutableList<AspectClass> topLevelAspectsClasses,
        @Nullable ImmutableMap<String, String> topLevelAspectsParameters,
        int hashCode) {
      Preconditions.checkArgument(!topLevelAspectsClasses.isEmpty(), "No aspects");
      this.topLevelAspectsClasses = topLevelAspectsClasses;
      this.topLevelAspectsParameters = topLevelAspectsParameters;
      this.hashCode = hashCode;
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.BUILD_TOP_LEVEL_ASPECTS_DETAILS;
    }

    ImmutableList<AspectClass> getTopLevelAspectsClasses() {
      return topLevelAspectsClasses;
    }

    ImmutableMap<String, String> getTopLevelAspectsParameters() {
      return topLevelAspectsParameters;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      if (!(o instanceof BuildTopLevelAspectsDetailsKey that)) {
        return false;
      }
      return hashCode == that.hashCode
          && topLevelAspectsClasses.equals(that.topLevelAspectsClasses)
          && topLevelAspectsParameters.equals(that.topLevelAspectsParameters);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("topLevelAspectsClasses", topLevelAspectsClasses)
          .add("topLevelAspectsParameters", topLevelAspectsParameters)
          .toString();
    }

    @Override
    public SkyKeyInterner<BuildTopLevelAspectsDetailsKey> getSkyKeyInterner() {
      return interner;
    }
  }

  /**
   * {@link SkyValue} for {@code BuildTopLevelAspectsDetailsKey} wraps a list of the {@code
   * AspectDetails} of the top level aspects.
   */
  static final class BuildTopLevelAspectsDetailsValue implements SkyValue {
    private final ImmutableList<AspectDetails> aspectsDetails;

    private BuildTopLevelAspectsDetailsValue(Collection<AspectDetails> aspectsDetails) {
      this.aspectsDetails = ImmutableList.copyOf(aspectsDetails);
    }

    public ImmutableList<AspectDetails> getAspectsDetails() {
      return aspectsDetails;
    }
  }
}
