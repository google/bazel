// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.platform;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainTypeInfoApi;
import com.google.devtools.build.lib.util.HashCodes;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.eval.Printer;

/** A provider that supplies information about a specific toolchain type. */
@Immutable
public class ToolchainTypeInfo extends NativeInfo implements ToolchainTypeInfoApi {
  /** Name used in Starlark for accessing this provider. */
  public static final String STARLARK_NAME = "ToolchainTypeInfo";

  /** Provider singleton constant. */
  public static final BuiltinProvider<ToolchainTypeInfo> PROVIDER =
      new BuiltinProvider<ToolchainTypeInfo>(STARLARK_NAME, ToolchainTypeInfo.class) {};

  private final Label typeLabel;
  @Nullable private final String noneFoundError;

  public static ToolchainTypeInfo create(Label typeLabel, @Nullable String noneFoundError) {
    return new ToolchainTypeInfo(typeLabel, noneFoundError);
  }

  @VisibleForTesting
  public static ToolchainTypeInfo create(Label typeLabel) {
    return new ToolchainTypeInfo(typeLabel, /* noneFoundError= */ null);
  }

  private ToolchainTypeInfo(Label typeLabel, String noneFoundError) {
    this.typeLabel = typeLabel;
    this.noneFoundError = noneFoundError;
  }

  @Override
  public BuiltinProvider<ToolchainTypeInfo> getProvider() {
    return PROVIDER;
  }

  @Override
  public Label typeLabel() {
    return typeLabel;
  }

  @Nullable
  public String noneFoundError() {
    return noneFoundError;
  }

  @Override
  public void repr(Printer printer) {
    printer.append(String.format("ToolchainTypeInfo(%s)", typeLabel));
  }

  @Override
  public int hashCode() {
    return HashCodes.hashObjects(typeLabel, noneFoundError);
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof ToolchainTypeInfo otherToolchainTypeInfo)) {
      return false;
    }

    return Objects.equals(typeLabel, otherToolchainTypeInfo.typeLabel)
        && Objects.equals(noneFoundError, otherToolchainTypeInfo.noneFoundError);
  }
}
