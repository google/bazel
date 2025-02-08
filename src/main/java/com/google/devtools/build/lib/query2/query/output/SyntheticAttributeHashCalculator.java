// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.query2.query.output;

import com.google.common.base.Preconditions;
import com.google.common.flogger.GoogleLogger;
import com.google.common.hash.HashFunction;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.AttributeFormatter;
import com.google.devtools.build.lib.packages.BuildType.SelectorList;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Contains the logic for condensing the various properties of rules that contribute to their
 * "affectedness" into a simple hash value. The resulting hash may be compared across queries to
 * tell if a rule has changed in a potentially meaningful way.
 */
class SyntheticAttributeHashCalculator {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private SyntheticAttributeHashCalculator() {}

  /**
   * Returns a hash of various properties of a rule which might contribute to the rule's
   * "affectedness". This includes, but is not limited to, attribute values and error-state.
   *
   * @param rule The rule instance to calculate the hash for.
   * @param serializedAttributes Any available attribute which have already been serialized. This is
   *     an optimization to avoid re-serializing attributes internally.
   * @param extraDataForAttrHash Extra data to add to the hash.
   */
  static String compute(
      Rule rule,
      Map<Attribute, Build.Attribute> serializedAttributes,
      Object extraDataForAttrHash,
      HashFunction hashFunction,
      boolean includeAttributeSourceAspects,
      boolean includeStarlarkRuleEnv) {
    HashingOutputStream hashingOutputStream =
        new HashingOutputStream(hashFunction, ByteStreams.nullOutputStream());
    CodedOutputStream codedOut = CodedOutputStream.newInstance(hashingOutputStream);

    RuleClass ruleClass = rule.getRuleClassObject();
    if (ruleClass.isStarlark() && includeStarlarkRuleEnv) {
      try {
        codedOut.writeByteArrayNoTag(
            Preconditions.checkNotNull(ruleClass.getRuleDefinitionEnvironmentDigest(), rule));
      } catch (IOException e) {
        throw new IllegalStateException("Unexpected IO failure writing to digest stream", e);
      }
    }

    RawAttributeMapper rawAttributeMapper = RawAttributeMapper.of(rule);
    for (Attribute attr : rule.getAttributes()) {
      String attrName = attr.getName();

      if (attrName.equals("generator_location")) {
        // generator_location can be ignored for the purpose of telling if a rule has changed.
        continue;
      }

      Object valueToHash = rawAttributeMapper.getRawAttributeValue(rule, attr);

      if (valueToHash instanceof ComputedDefault computedDefault) {
        // ConfiguredDefaults need special handling to detect changes in evaluated values.
        if (!computedDefault.dependencies().isEmpty()) {
          // TODO(b/29038463): We're skipping computed defaults that depend on other configurable
          // attributes because there currently isn't a way to evaluate such a computed default;
          // there isn't *one* value it evaluates to.
          continue;
        }

        try {
          valueToHash = computedDefault.getDefault(rawAttributeMapper);
        } catch (IllegalArgumentException e) {
          // TODO(mschaller): Catching IllegalArgumentException isn't ideal. It's thrown by
          // AbstractAttributeMapper#get if the attribute's type doesn't match its value, which
          // would happen if a ComputedDefault function accessed an attribute whose value was
          // configurable. We check whether the ComputedDefault declared any configurable
          // attribute dependencies above, but someone could make a mistake and fail to declare
          // something. There's no mechanism that enforces correct declaration right now.
          // This allows us to recover from such an error by skipping an attribute, as opposed to
          // crashing.
          logger.atWarning().log(
              "Recovering from failed evaluation of ComputedDefault attribute value: %s", e);
          continue;
        }
      }

      Build.Attribute attrPb;
      if (valueToHash instanceof SelectorList<?> || !serializedAttributes.containsKey(attr)) {
        // We didn't already serialize the attribute or it's a SelectorList. Latter may
        // have been flattened while we want the full representation, so we start from scratch.
        attrPb =
            AttributeFormatter.getAttributeProto(
                attr,
                valueToHash,
                /* explicitlySpecified= */ false, // We care about value, not how it was set.
                /* encodeBooleanAndTriStateAsIntegerAndString= */ false,
                /* sourceAspect= */ null,
                includeAttributeSourceAspects,
                LabelPrinter.legacy());
      } else {
        attrPb = serializedAttributes.get(attr);
      }

      try {
        attrPb.writeTo(codedOut);
      } catch (IOException e) {
        throw new IllegalStateException("Unexpected IO failure writing to digest stream", e);
      }
    }

    try {
      // Rules can be considered changed when the containing package goes in/out of error.
      codedOut.writeBoolNoTag(rule.getPackage().containsErrors());
    } catch (IOException e) {
      throw new IllegalStateException("Unexpected IO failure writing to digest stream", e);
    }

    try {
      // Include a summary of any package-wide data that applies to this target (e.g. custom make
      // variables aka `vardef`).
      codedOut.writeStringNoTag((String) extraDataForAttrHash);
    } catch (IOException e) {
      throw new IllegalStateException("Unexpected IO failure writing to digest stream", e);
    }

    try {
      // Flush coded out to make sure all bytes make it to the underlying digest stream.
      codedOut.flush();
    } catch (IOException e) {
      throw new IllegalStateException("Unexpected flush failure", e);
    }

    return BaseEncoding.base64().encode(hashingOutputStream.hash().asBytes());
  }
}
