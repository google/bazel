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
package com.google.devtools.build.skyframe;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoOneOf;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.skyframe.SkyFunction.Environment.ClassToInstanceMapSkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunction.Reset;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Contains the causes describing why a node, which opted into partial reevaluation, is getting
 * reevaluated.
 *
 * <p>Accessible via {@link SkyKeyComputeState}. Nodes opting into partial reevaluation must access
 * compute states via {@link ClassToInstanceMapSkyKeyComputeState}.
 *
 * <p>A node's mailbox may be in one of three general states:
 *
 * <ol>
 *   <li>"freshly initialized",
 *   <li>containing causes for the node's partial reevaluation, or,
 *   <li>empty of such causes.
 * </ol>
 *
 * <p>See {@link Kind} for details.
 *
 * <p>The "Mailbox" naming convention comes from actor models, where concurrent processors of work
 * coordinate by sending each other messages that get stored in "mailboxes" until consumed; see
 * https://wikipedia.org/wiki/Erlang_(programming_language)#Concurrency_and_distribution_orientation
 * for discussion.
 */
public class PartialReevaluationMailbox implements SkyKeyComputeState {

  /** Will be {@code null} only before the first call to {@link #getMail()}. */
  @GuardedBy("this")
  @Nullable
  private ImmutableList.Builder<SkyKey> signaledDeps;

  @GuardedBy("this")
  private boolean other;

  /** General states that a mailbox may be in. */
  public enum Kind {
    /**
     * Represents the first time a mailbox is accessed by its node. A mailbox may also be in this
     * state because the mailbox's data was dropped due to memory pressure, or because of other
     * Skyframe nodes completing in error. A {@link SkyFunction} that observes this state should
     * (re)evaluate "from scratch"; its other {@link SkyKeyComputeState} data will be in a freshly
     * initialized state too.
     */
    FRESHLY_INITIALIZED,

    /**
     * Represents a nonempty set of causes for a node's reevaluation. See {@link Causes} for
     * details.
     */
    CAUSES,

    /**
     * Represents an empty set of causes for a node's reevaluation.
     *
     * <p>Reading from a mailbox, via {@link #getMail()}, empties it. Thereafter, it will no longer
     * be {@link #FRESHLY_INITIALIZED}, unless Skyframe drops its {@link SkyKeyComputeState}.
     * Reading empties its list of signaled dep keys and sets its {@link Causes#other} flag back to
     * {@code false}.
     *
     * <p>This empty state may be observed during a reevaluation, even from a reevaluation's first
     * read from its mailbox. When an event occurs that may cause a reevaluation (e.g., when a dep
     * completes) adding that cause (e.g., that dep's key) to a parent's mailbox can race with that
     * parent reading its mailbox if the parent is reevaluating at the same time. If such an add
     * wins the race, then the parent consumes the cause during that reevaluation. The event may
     * then schedule a subsequent reevaluation for that parent, which is necessary to handle the
     * case in which the add lost the race. If no other causes get added before the parent reads its
     * mailbox in that subsequent reevaluation, then the mailbox may be empty.
     */
    EMPTY,
  }

  /**
   * A mailbox's detailed state, including whether it was freshly initialized, and the causes it
   * contains for its node's partial reevaluation, if any.
   */
  @AutoOneOf(Kind.class)
  public abstract static class Mail {
    public abstract Kind kind();

    abstract void freshlyInitialized();

    abstract void empty();

    public abstract Causes causes();

    static Mail ofFreshlyInitialized() {
      return AutoOneOf_PartialReevaluationMailbox_Mail.freshlyInitialized();
    }

    static Mail ofEmpty() {
      return AutoOneOf_PartialReevaluationMailbox_Mail.empty();
    }

    static Mail ofCauses(Causes causes) {
      return AutoOneOf_PartialReevaluationMailbox_Mail.causes(causes);
    }
  }

  /**
   * A nonempty set of causes for a node's partial reevaluation.
   *
   * <p>A dep which a parent node previously requested and observed to not be done will have its key
   * added to that parent's mailbox after the dep completes and before the dep signals the parent.
   * {@link #signaledDeps} returns that list of keys.
   *
   * <p>Skyframe may enqueue a node for evaluation for several other reasons, such as when the node
   * declared an external dependency (via {@link SkyFunction.Environment#dependOnFuture}) that
   * completes, or when the node's {@link SkyFunction#compute} method returns a {@link Reset} value
   * and the node is restarted. In some of these cases (e.g. returning a {@link Reset} value), the
   * node's {@link SkyKeyComputeState} will be invalidated, which also drops its mailbox, and the
   * next time that mailbox is read it will return a "freshly initialized" state. But in others
   * (e.g. an external dependency completes), the node's {@link SkyKeyComputeState} is retained. In
   * any of these cases in which a node is enqueued for evaluation and its mailbox is retained, a
   * flag will be set in the node's mailbox to indicate that the node's {@link SkyFunction} should
   * try its best to make progress, by, e.g., checking whether its external dep futures have
   * completed, checking whether its previously requested deps are done, or reevaluating from
   * scratch. ({@link Causes#other}) returns the value of that flag.
   *
   * @param signaledDeps {@link SkyKey} s of previously requested deps which have completed since
   *     the last time the mailbox was read.
   * @param other Whether Skyframe enqueued a reevaluation for any other reason besides a dep
   *     completing normally, in such a way that the dep's key would be added to {@link
   *     #signaledDeps}.
   */
  public record Causes(ImmutableList<SkyKey> signaledDeps, boolean other) {
    public Causes {
      requireNonNull(signaledDeps, "signaledDeps");
    }

    static Causes create(ImmutableList<SkyKey> signaledDeps, boolean other) {
      return new Causes(signaledDeps, other);
    }
  }

  private PartialReevaluationMailbox() {}

  public static PartialReevaluationMailbox from(ClassToInstanceMapSkyKeyComputeState computeState) {
    return computeState.getInstance(
        PartialReevaluationMailbox.class, PartialReevaluationMailbox::new);
  }

  /** Used by Skyframe to record that a dep has signaled a node opting into partial reevaluation. */
  synchronized void signal(SkyKey dep) {
    if (signaledDeps != null) {
      signaledDeps.add(dep);
    }
  }

  /**
   * Used by Skyframe to record that a node opting into partial reevaluation has been enqueued for
   * evaluation in contexts where that happens for reasons other than a dep signaling it.
   */
  synchronized void enqueuedNotByDeps() {
    other = true;
  }

  /** Gets and clears the current causes for a node's partial reevaluation. */
  public Mail getMail() {
    @Nullable ImmutableList.Builder<SkyKey> signaledDeps;
    boolean other;
    ImmutableList.Builder<SkyKey> newBuilder = new ImmutableList.Builder<>();
    synchronized (this) {
      signaledDeps = this.signaledDeps;
      this.signaledDeps = newBuilder;

      other = this.other;
      this.other = false;
    }
    if (signaledDeps == null) {
      return Mail.ofFreshlyInitialized();
    }
    ImmutableList<SkyKey> signaledDepsList = signaledDeps.build();
    if (signaledDepsList.isEmpty() && !other) {
      return Mail.ofEmpty();
    }
    return Mail.ofCauses(Causes.create(signaledDepsList, other));
  }
}
