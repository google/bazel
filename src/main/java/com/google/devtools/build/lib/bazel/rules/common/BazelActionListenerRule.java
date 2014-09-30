// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.bazel.rules.common;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.Type.LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.STRING_LIST;

import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.view.BaseRuleClasses;
import com.google.devtools.build.lib.view.BlazeRule;
import com.google.devtools.build.lib.view.RuleDefinition;
import com.google.devtools.build.lib.view.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.view.extra.ActionListener;

/**
 * Rule definition for action_listener rule.
 */
@BlazeRule(name = "action_listener",
             ancestors = { BaseRuleClasses.RuleBase.class },
             factoryClass = ActionListener.class)
public final class BazelActionListenerRule implements RuleDefinition {
  @Override
  public RuleClass build(Builder builder, RuleDefinitionEnvironment environment) {
    return builder
        .add(attr("mnemonics", STRING_LIST).mandatory())
        .add(attr("extra_actions", LABEL_LIST).mandatory()
            .allowedRuleClasses("extra_action")
            .allowedFileTypes())
        .removeAttribute("deps")
        .removeAttribute("data")
        .removeAttribute(":action_listener")
        .build();
  }
}
