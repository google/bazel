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

package com.google.devtools.build.lib.rules.java;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.java.JavaInfo.JavaInfoInternalProvider;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaModuleFlagsProviderApi;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/**
 * Provides information about {@code --add-exports=} and {@code --add-opens=} flags for Java
 * targets.
 */
@AutoCodec
record JavaModuleFlagsProvider(NestedSet<String> addExports, NestedSet<String> addOpens)
    implements JavaInfoInternalProvider, JavaModuleFlagsProviderApi {
  JavaModuleFlagsProvider {
    requireNonNull(addExports, "addExports");
    requireNonNull(addOpens, "addOpens");
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Override
  public Depset /*String*/ getAddExports() {
    return Depset.of(String.class, addExports());
  }

  @Override
  public Depset /*String*/ getAddOpens() {
    return Depset.of(String.class, addOpens());
  }

  public static JavaModuleFlagsProvider create(
      NestedSet<String> addExports, NestedSet<String> addOpens) {
    return new JavaModuleFlagsProvider(addExports, addOpens);
  }

  public static final JavaModuleFlagsProvider EMPTY =
      create(
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.emptySet(Order.STABLE_ORDER));

  public static JavaModuleFlagsProvider create(
      List<String> addExports, List<String> addOpens, Stream<JavaModuleFlagsProvider> transitive) {
    NestedSetBuilder<String> addExportsBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<String> addOpensBuilder = NestedSetBuilder.stableOrder();
    addExportsBuilder.addAll(addExports);
    addOpensBuilder.addAll(addOpens);
    transitive.forEach(
        provider -> {
          addExportsBuilder.addTransitive(provider.addExports());
          addOpensBuilder.addTransitive(provider.addOpens());
        });
    if (addExportsBuilder.isEmpty() && addOpensBuilder.isEmpty()) {
      return EMPTY;
    }
    return create(addExportsBuilder.build(), addOpensBuilder.build());
  }

  public static JavaModuleFlagsProvider create(
      RuleContext ruleContext, Stream<JavaModuleFlagsProvider> transitive) {
    AttributeMap attributes = ruleContext.attributes();
    return create(
        attributes.getOrDefault("add_exports", STRING_LIST, ImmutableList.of()),
        attributes.getOrDefault("add_opens", STRING_LIST, ImmutableList.of()),
        transitive);
  }

  public static ImmutableList<String> toFlags(List<String> addExports, List<String> addOpens) {
    return Streams.concat(
            addExports.stream().map(x -> String.format("--add-exports=%s=ALL-UNNAMED", x)),
            addOpens.stream().map(x -> String.format("--add-opens=%s=ALL-UNNAMED", x)))
        .collect(toImmutableList());
  }

  public ImmutableList<String> toFlags() {
    return toFlags(addExports().toList(), addOpens().toList());
  }

  /**
   * Translates the {@code module_flags_info} from a {@link JavaInfo} to the native class.
   *
   * @param javaInfo a {@link JavaInfo} provider instance
   * @return a {@link JavaModuleFlagsProvider} instance or {@code null} if {@code module_flags_info}
   *     is absent or {@code None}
   * @throws EvalException if there are any errors accessing Starlark values
   * @throws TypeException if any depset values are of an incompatible type
   * @throws RuleErrorException if the {@code module_flags_info} is of an incompatible type
   */
  @Nullable
  static JavaModuleFlagsProvider fromStarlarkJavaInfo(StructImpl javaInfo)
      throws EvalException, TypeException, RuleErrorException {
    Object value = javaInfo.getValue("module_flags_info");
    if (value == null || value == Starlark.NONE) {
      return null;
    } else if (value instanceof JavaModuleFlagsProvider javaModuleFlagsProvider) {
      return javaModuleFlagsProvider;
    } else if (value instanceof StructImpl moduleFlagsInfo) {
      return JavaModuleFlagsProvider.create(
          moduleFlagsInfo.getValue("add_exports", Depset.class).toList(String.class),
          moduleFlagsInfo.getValue("add_opens", Depset.class).toList(String.class),
          Stream.empty());
    }
    throw new RuleErrorException("expected JavaModuleFlagsInfo, got: " + Starlark.type(value));
  }
}
