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

package com.google.devtools.build.lib.analysis;

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import net.starlark.java.syntax.Location;

/**
 * Container for some attributes of a {@link Target} that is significantly less heavyweight than an
 * actual {@link Target} for purposes of serialization. Should still not be used indiscriminately,
 * since {@link Location} can be quite heavy on its own and each of these wrapper objects costs 24
 * bytes over an existing {@link Target}.
 */
@AutoCodec
public record LabelAndLocation(Label label, Location location) {
  public LabelAndLocation {
    requireNonNull(label, "label");
    requireNonNull(location, "location");
  }

  public static LabelAndLocation of(Target target) {
    return new LabelAndLocation(target.getLabel(), target.getLocation());
  }
}
