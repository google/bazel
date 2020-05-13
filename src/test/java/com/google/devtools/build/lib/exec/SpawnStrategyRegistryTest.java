// Copyright 2018 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec;


import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.util.FakeOwner;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.RegexFilter;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for SpawnStrategyRegistry. */
@RunWith(JUnit4.class)
public class SpawnStrategyRegistryTest {

  private static final RegexFilter ELLO_MATCHER =
      new RegexFilter(ImmutableList.of("ello"), ImmutableList.of());

  private static void noopEventHandler(Event event) {}

  @Test
  public void testRegistration() throws Exception {
    NoopStrategy strategy = new NoopStrategy("");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy, "foo")
            .setDefaultStrategies(ImmutableList.of("foo"))
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy);
  }

  @Test
  public void testMnemonicFilter() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .addMnemonicFilter("mnem", ImmutableList.of("bar", "foo"))
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", ""),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy2, strategy1);
  }

  @Test
  public void testLaterStrategyOverridesEarlier() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "foo")
            .addMnemonicFilter("mnem", ImmutableList.of("foo"))
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", ""),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy2);
  }

  @Test
  public void testDescriptionFilter() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .addDescriptionFilter(ELLO_MATCHER, ImmutableList.of("bar", "foo"))
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", "hello"),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy2, strategy1);
  }

  @Test
  public void testDescriptionHasPrecedenceOverMnemonic() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .addMnemonicFilter("mnem", ImmutableList.of("foo"))
            .addDescriptionFilter(ELLO_MATCHER, ImmutableList.of("bar"))
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", "hello"),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy2);
  }

  @Test
  public void testMultipleMnemonicFilter() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .addMnemonicFilter("mnem", ImmutableList.of("foo"))
            .addMnemonicFilter("mnem", ImmutableList.of("bar"))
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", ""),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy2);
  }

  @Test
  public void testMultipleDescriptionFilter() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .addDescriptionFilter(ELLO_MATCHER, ImmutableList.of("foo"))
            .addDescriptionFilter(
                new RegexFilter(ImmutableList.of("ll"), ImmutableList.of()),
                ImmutableList.of("bar"))
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", "hello"),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy2);
  }

  @Test
  public void testMultipleDescriptionFilterLegacy() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .addDescriptionFilter(ELLO_MATCHER, ImmutableList.of("foo"))
            .addDescriptionFilter(
                new RegexFilter(ImmutableList.of("ll"), ImmutableList.of()),
                ImmutableList.of("bar"))
            .useLegacyDescriptionFilterPrecedence()
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", "hello"),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy1);
  }

  @Test
  public void testMultipleDefaultStrategies() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    NoopStrategy strategy3 = new NoopStrategy("3");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .registerStrategy(strategy3, "baz")
            .setDefaultStrategies(ImmutableList.of("foo", "baz"))
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy1, strategy3);
  }

  @Test
  public void testDefaultStrategiesIndependentOfFilters() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    NoopStrategy strategy3 = new NoopStrategy("3");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .registerStrategy(strategy3, "baz")
            .addMnemonicFilter("mnem", ImmutableList.of("bar"))
            .setDefaultStrategies(ImmutableList.of("foo", "baz"))
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy1, strategy3);

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", ""),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy2);
  }

  @Test
  public void testImplicitDefault() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy1, strategy2);
  }

  @Test
  public void testImplicitDefaultWithDuplicateIdentifiers() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    NoopStrategy strategy3 = new NoopStrategy("3");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .registerStrategy(strategy3, "foo")
            .build();

    assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                SpawnStrategyRegistryTest::noopEventHandler))
        .containsExactly(strategy1, strategy2, strategy3);
  }

  @Test
  public void testMnemonicStrategyNotPresent() {
    NoopStrategy strategy1 = new NoopStrategy("1");
    AbruptExitException exception =
        assertThrows(
            AbruptExitException.class,
            () ->
                SpawnStrategyRegistry.builder()
                    .registerStrategy(strategy1, "foo")
                    .addMnemonicFilter("mnem", ImmutableList.of("bar", "foo"))
                    .build());

    assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo");
  }

  @Test
  public void testDescriptionStrategyNotPresent() {
    NoopStrategy strategy1 = new NoopStrategy("1");
    AbruptExitException exception =
        assertThrows(
            AbruptExitException.class,
            () ->
                SpawnStrategyRegistry.builder()
                    .registerStrategy(strategy1, "foo")
                    .addDescriptionFilter(ELLO_MATCHER, ImmutableList.of("bar", "foo"))
                    .build());

    assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo");
  }

  @Test
  public void testDefaultStrategyNotPresent() {
    NoopStrategy strategy1 = new NoopStrategy("1");
    AbruptExitException exception =
        assertThrows(
            AbruptExitException.class,
            () ->
                SpawnStrategyRegistry.builder()
                    .registerStrategy(strategy1, "foo")
                    .setDefaultStrategies(ImmutableList.of("bar"))
                    .build());

    assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo");
  }

  @Test
  public void testDynamicStrategies() throws Exception {
    NoopStrategy strategy1 = new NoopSandboxedStrategy("1");
    NoopStrategy strategy2 = new NoopSandboxedStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .addDynamicLocalStrategiesByMnemonic("mnem", ImmutableList.of("bar"))
            .addDynamicRemoteStrategiesByMnemonic("mnem", ImmutableList.of("foo"))
            .build();

    assertThat(
            strategyRegistry.getDynamicSpawnActionContexts(
                createSpawnWithMnemonicAndDescription("mnem", ""), DynamicMode.REMOTE))
        .containsExactly(strategy1);
    assertThat(
            strategyRegistry.getDynamicSpawnActionContexts(
                createSpawnWithMnemonicAndDescription("mnem", ""), DynamicMode.LOCAL))
        .containsExactly(strategy2);
  }

  @Test
  public void testDynamicStrategyNotPresent() {
    NoopStrategy strategy1 = new NoopSandboxedStrategy("1");
    AbruptExitException exception =
        assertThrows(
            AbruptExitException.class,
            () ->
                SpawnStrategyRegistry.builder()
                    .registerStrategy(strategy1, "foo")
                    .addDynamicLocalStrategiesByMnemonic("mnem", ImmutableList.of("bar"))
                    .build());

    assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo");
  }

  @Test
  public void testDynamicStrategyNotSandboxed() {
    NoopStrategy strategy1 = new NoopStrategy("1");
    AbruptExitException exception =
        assertThrows(
            AbruptExitException.class,
            () ->
                SpawnStrategyRegistry.builder()
                    .registerStrategy(strategy1, "foo")
                    .addDynamicLocalStrategiesByMnemonic("mnem", ImmutableList.of("foo"))
                    .build());

    assertThat(exception).hasMessageThat().containsMatch("sandboxed strategy");
  }

  @Test
  public void testRemoteLocalFallback() throws Exception {
    NoopAbstractStrategy strategy1 = new NoopAbstractStrategy("1");
    NoopAbstractStrategy strategy2 = new NoopAbstractStrategy("2");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "foo")
            .registerStrategy(strategy2, "bar")
            .setRemoteLocalFallbackStrategyIdentifier("bar")
            .build();

    assertThat(strategyRegistry.getRemoteLocalFallbackStrategy()).isEqualTo(strategy2);
  }

  @Test
  public void testRemoteLocalFallbackNotPresent() {
    NoopStrategy strategy1 = new NoopStrategy("1");
    AbruptExitException exception =
        assertThrows(
            AbruptExitException.class,
            () ->
                SpawnStrategyRegistry.builder()
                    .registerStrategy(strategy1, "foo")
                    .setRemoteLocalFallbackStrategyIdentifier("bar")
                    .build());

    assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo");
  }

  @Test
  public void testRemoteLocalFallbackNotRegistered() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder().registerStrategy(strategy1, "foo").build();

    assertThat(strategyRegistry.getRemoteLocalFallbackStrategy()).isNull();
  }

  @Test
  public void testNotifyUsed() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    NoopStrategy strategy3 = new NoopStrategy("3");
    NoopAbstractStrategy strategy4 = new NoopAbstractStrategy("4");
    NoopStrategy strategy5 = new NoopSandboxedStrategy("5");
    NoopStrategy strategy6 = new NoopSandboxedStrategy("6");
    NoopStrategy strategy7 = new NoopStrategy("7");
    NoopStrategy strategy8 = new NoopStrategy("8");
    NoopStrategy strategy9 = new NoopStrategy("9");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "1")
            .registerStrategy(strategy2, "2")
            .registerStrategy(strategy3, "3")
            .registerStrategy(strategy7, "4") // no notification: identifier is overridden
            .registerStrategy(strategy4, "4")
            .registerStrategy(strategy5, "5") // no notification: dynamic strategies are separate
            .registerStrategy(strategy6, "6") // no notification: dynamic strategies are separate
            .registerStrategy(strategy8, "8") // no notification: never referenced
            .registerStrategy(strategy9, "9") // no notification: reference overridden
            .addMnemonicFilter("mnem", ImmutableList.of("1"))
            .addDescriptionFilter(ELLO_MATCHER, ImmutableList.of("2"))
            .setDefaultStrategies(ImmutableList.of("9"))
            .setDefaultStrategies(ImmutableList.of("3"))
            .setRemoteLocalFallbackStrategyIdentifier("4")
            .addDynamicLocalStrategiesByMnemonic("oy", ImmutableList.of("5"))
            .addDynamicRemoteStrategiesByMnemonic("oy", ImmutableList.of("6"))
            .build();

    strategyRegistry.notifyUsed(null);

    assertThat(strategy1.usedCalled).isEqualTo(1);
    assertThat(strategy2.usedCalled).isEqualTo(1);
    assertThat(strategy3.usedCalled).isEqualTo(1);
    assertThat(strategy4.usedCalled).isEqualTo(1);

    assertThat(strategy5.usedCalled).isEqualTo(0);
    assertThat(strategy6.usedCalled).isEqualTo(0);
    assertThat(strategy7.usedCalled).isEqualTo(0);
    assertThat(strategy8.usedCalled).isEqualTo(0);
    assertThat(strategy9.usedCalled).isEqualTo(0);
  }

  @Test
  public void testNotifyUsedDynamic() throws Exception {
    NoopStrategy strategy1 = new NoopStrategy("1");
    NoopStrategy strategy2 = new NoopStrategy("2");
    NoopStrategy strategy3 = new NoopStrategy("3");
    NoopAbstractStrategy strategy4 = new NoopAbstractStrategy("4");
    NoopStrategy strategy5 = new NoopSandboxedStrategy("5");
    NoopStrategy strategy6 = new NoopSandboxedStrategy("6");
    NoopStrategy strategy7 = new NoopStrategy("7");
    SpawnStrategyRegistry strategyRegistry =
        SpawnStrategyRegistry.builder()
            .registerStrategy(strategy1, "1") // no notification: regular strategies are separate
            .registerStrategy(strategy2, "2") // no notification: regular strategies are separate
            .registerStrategy(strategy3, "3") // no notification: regular strategies are separate
            .registerStrategy(strategy4, "4") // no notification: regular strategies are separate
            .registerStrategy(strategy5, "5")
            .registerStrategy(strategy6, "6")
            .registerStrategy(strategy7, "7") // no notification: reference overridden
            .addMnemonicFilter("mnem", ImmutableList.of("1"))
            .addDescriptionFilter(ELLO_MATCHER, ImmutableList.of("2"))
            .setDefaultStrategies(ImmutableList.of("3"))
            .setRemoteLocalFallbackStrategyIdentifier("4")
            .addDynamicLocalStrategiesByMnemonic("oy", ImmutableList.of("7"))
            .addDynamicLocalStrategiesByMnemonic("oy", ImmutableList.of("5"))
            .addDynamicRemoteStrategiesByMnemonic("oy", ImmutableList.of("6"))
            .build();

    strategyRegistry.notifyUsedDynamic(null);

    assertThat(strategy1.usedCalled).isEqualTo(0);
    assertThat(strategy2.usedCalled).isEqualTo(0);
    assertThat(strategy3.usedCalled).isEqualTo(0);
    assertThat(strategy4.usedCalled).isEqualTo(0);

    assertThat(strategy5.usedCalled).isEqualTo(1);
    assertThat(strategy6.usedCalled).isEqualTo(1);

    assertThat(strategy7.usedCalled).isEqualTo(0);
  }

  private Spawn createSpawnWithMnemonicAndDescription(String mnemonic, String description) {
    return new SimpleSpawn(
        new FakeOwner(mnemonic, description),
        ImmutableList.of(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        ImmutableSet.of(),
        ResourceSet.ZERO);
  }

  private static class NoopStrategy implements SpawnStrategy {

    private final String name;
    private int usedCalled = 0;

    private NoopStrategy(String name) {
      this.name = name;
    }

    @Override
    public ImmutableList<SpawnResult> exec(
        Spawn spawn, ActionExecutionContext actionExecutionContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean canExec(Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
      return false;
    }

    @Override
    public void usedContext(ActionContext.ActionContextRegistry actionContextRegistry) {
      usedCalled++;
    }

    @Override
    public String toString() {
      return "strategy" + name;
    }
  }

  private static class NoopSandboxedStrategy extends NoopStrategy
      implements SandboxedSpawnStrategy {

    private NoopSandboxedStrategy(String name) {
      super(name);
    }

    @Override
    public ImmutableList<SpawnResult> exec(
        Spawn spawn,
        ActionExecutionContext actionExecutionContext,
        @Nullable SandboxedSpawnStrategy.StopConcurrentSpawns stopConcurrentSpawns) {
      throw new UnsupportedOperationException();
    }
  }

  private static class NoopAbstractStrategy extends AbstractSpawnStrategy {

    private final String name;
    private int usedCalled = 0;

    public NoopAbstractStrategy(String name) {
      super(null, null);
      this.name = name;
    }

    @Override
    public void usedContext(ActionContext.ActionContextRegistry actionContextRegistry) {
      usedCalled++;
    }

    @Override
    public String toString() {
      return "strategy" + name;
    }
  }
}
