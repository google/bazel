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

package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProviderWrapper;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;

/** Marks configured targets that are able to supply message bundles to their dependents. */
@Immutable
public final class MessageBundleInfo {

  private static final String STARLARK_NAME = "MessageBundleInfo";

  /** Provider singleton constant. */
  private static final StarlarkProviderWrapper<MessageBundleInfo> PROVIDER = new Provider();

  /** Provider class for {@link MessageBundleInfo} objects. */
  private static class Provider extends StarlarkProviderWrapper<MessageBundleInfo> {
    private Provider() {
      super(
          keyForBuild(
              Label.parseCanonicalUnchecked(
                  JavaSemantics.RULES_JAVA_PROVIDER_LABELS_PREFIX
                      + "java/private:message_bundle_info.bzl")),
          STARLARK_NAME);
    }

    @Override
    public MessageBundleInfo wrap(Info value) throws RuleErrorException {
      try {
        return new MessageBundleInfo((StarlarkInfo) value);
      } catch (EvalException e) {
        throw new RuleErrorException(e);
      }
    }
  }

  private final ImmutableList<Artifact> messages;

  private MessageBundleInfo(StarlarkInfo value) throws EvalException {
    this.messages =
        Sequence.cast(value.getValue("messages"), Artifact.class, "messages").getImmutableList();
  }

  private ImmutableList<Artifact> getMessages() {
    return messages;
  }

  @Nullable
  public static ImmutableList<Artifact> getMessages(TransitiveInfoCollection info)
      throws RuleErrorException {
    MessageBundleInfo messageBundleInfo = info.get(MessageBundleInfo.PROVIDER);
    if (messageBundleInfo != null) {
      return messageBundleInfo.getMessages();
    }
    return null;
  }
}
