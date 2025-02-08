// Copyright 2024 The Bazel Authors. All rights reserved.
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

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Optional;

/**
 * A platform's {@link PlatformInfo} along with its parsed flags.
 *
 * @param parsedFlags Only present if the platform specifies flags.
 */
@AutoCodec
public record PlatformValue(PlatformInfo platformInfo, Optional<ParsedFlagsValue> parsedFlags)
    implements SkyValue {
  public PlatformValue {
    requireNonNull(platformInfo, "platformInfo");
    requireNonNull(parsedFlags, "parsedFlags");
  }

  static PlatformValue noFlags(PlatformInfo platformInfo) {
    return new PlatformValue(platformInfo, /* parsedFlags= */ Optional.empty());
  }

  static PlatformValue withFlags(PlatformInfo platformInfo, ParsedFlagsValue parsedFlags) {
    return new PlatformValue(platformInfo, Optional.of(parsedFlags));
  }

  public static PlatformKey key(Label platformLabel) {
    return PlatformKey.intern(new PlatformKey(platformLabel));
  }

  private static final class PlatformKey extends AbstractSkyKey<Label> {
    private static final SkyKeyInterner<PlatformKey> interner = new SkyKeyInterner<>();

    @AutoCodec.Interner
    static PlatformKey intern(PlatformKey key) {
      return interner.intern(key);
    }

    private PlatformKey(Label arg) {
      super(arg);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.PLATFORM;
    }

    @Override
    public SkyKeyInterner<PlatformKey> getSkyKeyInterner() {
      return interner;
    }
  }
}
