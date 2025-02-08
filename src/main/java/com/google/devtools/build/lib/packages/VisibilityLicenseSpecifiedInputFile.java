// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.cmdline.Label;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * An input file to the build system which has a specified {@link #visibility} or {@link #license}.
 */
public final class VisibilityLicenseSpecifiedInputFile extends InputFile {

  @Nullable private final RuleVisibility visibility;
  @Nullable private final License license;

  VisibilityLicenseSpecifiedInputFile(
      Package pkg,
      Label label,
      Location location,
      @Nullable RuleVisibility visibility,
      @Nullable License license) {
    super(pkg, label, location);
    this.visibility = visibility;
    this.license = license;
  }

  @Override
  public boolean isVisibilitySpecified() {
    return visibility != null;
  }

  @Override
  @Nullable
  public RuleVisibility getRawVisibility() {
    return visibility;
  }

  @Override
  public boolean isLicenseSpecified() {
    return license != null && license.isSpecified();
  }

  @Override
  public License getLicense() {
    if (license != null) {
      return license;
    } else {
      return getPackage().getPackageArgs().license();
    }
  }
}
