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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.AspectCollection.AspectCycleOnPathException;
import com.google.devtools.build.lib.analysis.AspectCollection.AspectDeps;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.util.Pair;
import java.util.HashMap;
import java.util.HashSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link AspectCollection}
 */
@RunWith(JUnit4.class)
public class AspectCollectionTest {
  /**
   * a3 wants a1 and a2, a1 and a2 want no one, path is a1, a2, a3.
   */
  @Test
  public void linearAspectPath1() throws Exception {
    Aspect a1 = createAspect("a1");
    Aspect a2 = createAspect("a2");
    Aspect a3 = createAspect("a3", "a1", "a2");
    AspectCollection collection = AspectCollection.create(ImmutableList.of(a1, a2, a3));
    validateAspectCollection(
        collection,
        ImmutableList.of(a1, a2, a3),
        expectDeps(a3, a1, a2),
        expectDeps(a1),
        expectDeps(a2));
  }

  /**
   * a3 wants a2, a2 wants a1, a1 wants no one, path is a1, a2, a3.
   */
  @Test
  public void linearAspectPath2() throws Exception {
    Aspect a1 = createAspect("a1");
    Aspect a2 = createAspect("a2", "a1");
    Aspect a3 = createAspect("a3", "a2");
    AspectCollection collection = AspectCollection.create(ImmutableList.of(a1, a2, a3));
    validateAspectCollection(
        collection,
        ImmutableList.of(a1, a2, a3),
        expectDeps(a3, a2),
        expectDeps(a2, a1),
        expectDeps(a1));
  }

  /**
   * a3 wants a1, a1 wants a2,  path is a1, a2, a3, so a2 comes after a1.
   */
  @Test
  public void validateOrder() throws Exception {
    Aspect a1 = createAspect("a1", "a2");
    Aspect a2 = createAspect("a2");
    Aspect a3 = createAspect("a3", "a1");
    AspectCollection collection = AspectCollection.create(ImmutableList.of(a1, a2, a3));
    validateAspectCollection(
        collection,
        ImmutableList.of(a1, a2, a3),
        expectDeps(a1),
        expectDeps(a2),
        expectDeps(a3, a1));
  }

  /**
   * a3 wants a1, a1 wants a2, a2 wants a1, path is a1, a2, a3, so a2 comes after a1.
   */
  @Test
  public void validateOrder2() throws Exception {
    Aspect a1 = createAspect("a1", "a2");
    Aspect a2 = createAspect("a2", "a1");
    Aspect a3 = createAspect("a3", "a1");
    AspectCollection collection = AspectCollection.create(ImmutableList.of(a1, a2, a3));
    validateAspectCollection(
        collection,
        ImmutableList.of(a1, a2, a3),
        expectDeps(a1),
        expectDeps(a2, a1),
        expectDeps(a3, a1));
  }

  /**
   * a3 wants itself.
   */
  @Test
  public void recursive() throws Exception {
    Aspect a1 = createAspect("a1");
    Aspect a2 = createAspect("a2");
    Aspect a3 = createAspect("a3", "a3");
    AspectCollection collection = AspectCollection.create(ImmutableList.of(a1, a2, a3));
    validateAspectCollection(
        collection, ImmutableList.of(a1, a2, a3), expectDeps(a1), expectDeps(a2), expectDeps(a3));
  }

  /** a2 wants a1, a3 wants nothing. */
  @Test
  public void threeAspects() throws Exception {
    Aspect a1 = createAspect("a1");
    Aspect a2 = createAspect("a2", "a1");
    Aspect a3 = createAspect("a3");
    AspectCollection collection = AspectCollection.create(ImmutableList.of(a1, a2, a3));
    validateAspectCollection(
        collection,
        ImmutableList.of(a1, a2, a3),
        expectDeps(a3),
        expectDeps(a2, a1),
        expectDeps(a1));
  }

  /**
   * a2 wants a1, a3 wants a1 and a2, the path is [a2, a1, a2, a3], so a2 occurs twice.
   *
   * First occurrence of a2 would not see a1, but the second would: that is an error.
   */
  @Test
  public void duplicateAspect()  throws Exception {
    Aspect a1 = createAspect("a1");
    Aspect a2 = createAspect("a2", "a1");
    Aspect a3 = createAspect("a3", "a2", "a1");
    AspectCycleOnPathException e =
        assertThrows(
            AspectCycleOnPathException.class,
            () -> AspectCollection.create(ImmutableList.of(a2, a1, a2, a3)));
    assertThat(e.getAspect()).isEqualTo(a2.getDescriptor());
    assertThat(e.getPreviousAspect()).isEqualTo(a1.getDescriptor());
  }

  /**
   * a2 wants a1, a3 wants a2, the path is [a2, a1, a2, a3], so a2 occurs twice.
   *
   * First occurrence of a2 would not see a1, but the second would: that is an error.
   */
  @Test
  public void duplicateAspect2() throws Exception {
    Aspect a1 = createAspect("a1");
    Aspect a2 = createAspect("a2", "a1");
    Aspect a3 = createAspect("a3", "a2");
    AspectCycleOnPathException e =
        assertThrows(
            AspectCycleOnPathException.class,
            () -> AspectCollection.create(ImmutableList.of(a2, a1, a2, a3)));
    assertThat(e.getAspect()).isEqualTo(a2.getDescriptor());
    assertThat(e.getPreviousAspect()).isEqualTo(a1.getDescriptor());
  }

  /**
   *  a3 wants a1 and a2, a2 does not want a1.
   *  The path is [a2, a1, a2, a3], so a2 occurs twice.
   *  Second occurrence of a2 is consistent with the first.
   */
  @Test
  public void duplicateAspect2a() throws Exception {
    Aspect a1 = createAspect("a1");
    Aspect a2 = createAspect("a2");
    Aspect a3 = createAspect("a3", "a1", "a2");

    AspectCollection collection = AspectCollection.create(ImmutableList.of(a2, a1, a2, a3));

    validateAspectCollection(
        collection,
        ImmutableList.of(a2, a1, a3),
        expectDeps(a2),
        expectDeps(a1),
        expectDeps(a3, a2, a1));
  }


  /**
   * a2 wants a1, a3 wants a1 and a2, a1 wants a2. the path is [a2, a1, a2, a3], so a2 occurs twice.
   * First occurrence of a2 does not see a1, but the second does => error.
   */
  @Test
  public void duplicateAspect3() throws Exception {
    Aspect a1 = createAspect("a1", "a2");
    Aspect a2 = createAspect("a2", "a1");
    Aspect a3 = createAspect("a3", "a1", "a2");
    AspectCycleOnPathException e =
        assertThrows(
            AspectCycleOnPathException.class,
            () -> AspectCollection.create(ImmutableList.of(a2, a1, a2, a3)));
    assertThat(e.getAspect()).isEqualTo(a2.getDescriptor());
    assertThat(e.getPreviousAspect()).isEqualTo(a1.getDescriptor());
  }

  /**
   * a2 wants a1, a3 wants a2, a1 wants a2. the path is [a2, a1, a2, a3], so a2 occurs twice.
   * First occurrence of a2 does not see a1, but the second does => error.
   * a1 disappears.
   */
  @Test
  public void duplicateAspect4() throws Exception {
    Aspect a1 = createAspect("a1", "a2");
    Aspect a2 = createAspect("a2", "a1");
    Aspect a3 = createAspect("a3", "a2");
    AspectCycleOnPathException e =
        assertThrows(
            AspectCycleOnPathException.class,
            () -> AspectCollection.create(ImmutableList.of(a2, a1, a2, a3)));
    assertThat(e.getAspect()).isEqualTo(a2.getDescriptor());
    assertThat(e.getPreviousAspect()).isEqualTo(a1.getDescriptor());
  }

  /**
   * a3 wants a2, a1 wants a2. The path is [a2, a1, a2, a3], so a2 occurs twice. First occurrence of
   * a2 is consistent with the second. a1 disappears.
   */
  @Test
  public void duplicateAspect5() throws Exception {
    Aspect a1 = createAspect("a1", "a2");
    Aspect a2 = createAspect("a2");
    Aspect a3 = createAspect("a3", "a2");
    AspectCollection collection = AspectCollection.create(ImmutableList.of(a2, a1, a2, a3));
    validateAspectCollection(
        collection,
        ImmutableList.of(a2, a1, a3),
        expectDeps(a2),
        expectDeps(a1, a2),
        expectDeps(a3, a2));
  }


  private static Pair<Aspect, ImmutableList<Aspect>> expectDeps(Aspect a, Aspect... deps) {
    return Pair.of(a, ImmutableList.copyOf(deps));
  }

  @SafeVarargs
  private static void validateAspectCollection(
      AspectCollection collection,
      ImmutableList<Aspect> expectedUsedAspects,
      Pair<Aspect, ImmutableList<Aspect>>... expectedPaths) {

    assertThat(Iterables.transform(collection.getUsedAspects(), AspectDeps::aspect))
        .containsExactlyElementsIn(Iterables.transform(expectedUsedAspects, Aspect::getDescriptor))
        .inOrder();
    validateAspectPaths(
        collection,
        ImmutableList.copyOf(expectedPaths)
    );
  }

  private static void validateAspectPaths(AspectCollection collection,
      ImmutableList<Pair<Aspect, ImmutableList<Aspect>>> expectedList) {
    HashMap<AspectDescriptor, AspectDeps> allPaths = new HashMap<>();
    for (AspectDeps aspectPath : collection.getUsedAspects()) {
      collectAndValidateAspectDeps(aspectPath, allPaths);
    }

    HashSet<AspectDescriptor> expectedKeys = new HashSet<>();

    for (Pair<Aspect, ImmutableList<Aspect>> expected : expectedList) {
      assertThat(allPaths).containsKey(expected.first.getDescriptor());
      AspectDeps aspectPath = allPaths.get(expected.first.getDescriptor());
      assertThat(Iterables.transform(aspectPath.usedAspects(), AspectDeps::aspect))
          .containsExactlyElementsIn(Iterables.transform(expected.second, Aspect::getDescriptor))
          .inOrder();
      expectedKeys.add(expected.first.getDescriptor());
    }
    assertThat(allPaths.keySet())
        .containsExactlyElementsIn(expectedKeys);
  }

  /**
   * Collects all aspect paths transitively visible from {@code aspectDeps}.
   * Validates that {@link AspectDeps} instance corresponding to a given {@link AspectDescriptor}
   * is unique.
   */
  private static void collectAndValidateAspectDeps(AspectDeps aspectDeps,
      HashMap<AspectDescriptor, AspectDeps> allDeps) {
    if (allDeps.containsKey(aspectDeps.aspect())) {
      assertWithMessage(String.format("Two different deps for aspect %s", aspectDeps.aspect()))
          .that(allDeps.get(aspectDeps.aspect()))
          .isSameInstanceAs(aspectDeps);
      return;
    }
    allDeps.put(aspectDeps.aspect(), aspectDeps);
    for (AspectDeps path : aspectDeps.usedAspects()) {
      collectAndValidateAspectDeps(path, allDeps);
    }
  }

  /**
   * Creates an aspect with a class named {@code className} advertizing a provider {@code className}
   * that requires any of providers {@code requiredAspects}.
   */
  private Aspect createAspect(final String className, String... requiredAspects) {
    ImmutableList.Builder<ImmutableSet<StarlarkProviderIdentifier>> requiredProvidersBuilder =
        ImmutableList.builder();

    for (String requiredAspect : requiredAspects) {
      requiredProvidersBuilder.add(
          ImmutableSet.of(StarlarkProviderIdentifier.forLegacy(requiredAspect)));
    }
    final ImmutableList<ImmutableSet<StarlarkProviderIdentifier>> requiredProviders =
        requiredProvidersBuilder.build();
    return Aspect.forNative(
        new NativeAspectClass() {
          @Override
          public String getName() {
            return className;
          }

          @Override
          public AspectDefinition getDefinition(AspectParameters aspectParameters) {
            return AspectDefinition.builder(this)
                .requireAspectsWithProviders(requiredProviders)
                .advertiseProvider(
                    ImmutableList.of(StarlarkProviderIdentifier.forLegacy(className)))
                .build();
          }
        });
  }


}
