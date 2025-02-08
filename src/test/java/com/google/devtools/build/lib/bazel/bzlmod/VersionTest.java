// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Version}. */
@RunWith(JUnit4.class)
public class VersionTest {

  @Test
  public void testEmptyBeatsEverything() throws Exception {
    assertThat(Version.parse("")).isGreaterThan(Version.parse("1.0"));
    assertThat(Version.parse("")).isGreaterThan(Version.parse("1.0+build"));
    assertThat(Version.parse("")).isGreaterThan(Version.parse("1.0-pre"));
    assertThat(Version.parse("")).isGreaterThan(Version.parse("1.0-pre+build-kek.lol"));
  }

  @Test
  public void testNormalized() throws Exception {
    assertThat(Version.parse("1.0").getNormalized()).isEqualTo("1.0");
    assertThat(Version.parse("1.0+build").getNormalized()).isEqualTo("1.0");
    assertThat(Version.parse("1.0-pre").getNormalized()).isEqualTo("1.0-pre");
    assertThat(Version.parse("1.0-pre+build-kek.lol").getNormalized()).isEqualTo("1.0-pre");
    assertThat(Version.parse("1.0+build-notpre").getNormalized()).isEqualTo("1.0");
  }

  @Test
  public void testReleaseVersion() throws Exception {
    assertThat(Version.parse("2.0")).isGreaterThan(Version.parse("1.0"));
    assertThat(Version.parse("2.0")).isGreaterThan(Version.parse("1.9"));
    assertThat(Version.parse("11.0")).isGreaterThan(Version.parse("3.0"));
    assertThat(Version.parse("1.0.1")).isGreaterThan(Version.parse("1.0"));
    assertThat(Version.parse("1.0.0")).isGreaterThan(Version.parse("1.0"));
    assertThat(Version.parse("1.0+build2")).isEqualTo(Version.parse("1.0+build3"));
    assertThat(Version.parse("1.0")).isGreaterThan(Version.parse("1.0-pre"));
    assertThat(Version.parse("1.0")).isEqualTo(Version.parse("1.0+build-notpre"));
  }

  @Test
  public void testReleaseVersionWithLetters() throws Exception {
    assertThat(Version.parse("1.0.patch.3")).isGreaterThan(Version.parse("1.0"));
    assertThat(Version.parse("1.0.patch.3")).isGreaterThan(Version.parse("1.0.patch.2"));
    assertThat(Version.parse("1.0.patch.3")).isLessThan(Version.parse("1.0.patch.10"));
    assertThat(Version.parse("1.0.patch3")).isGreaterThan(Version.parse("1.0.patch10"));
    assertThat(Version.parse("4")).isLessThan(Version.parse("a"));
    assertThat(Version.parse("abc")).isLessThan(Version.parse("abd"));
  }

  @Test
  public void testPrereleaseVersion() throws Exception {
    assertThat(Version.parse("1.0-pre")).isGreaterThan(Version.parse("1.0-are"));
    assertThat(Version.parse("1.0-3")).isGreaterThan(Version.parse("1.0-2"));
    assertThat(Version.parse("1.0-pre")).isLessThan(Version.parse("1.0-pre.foo"));
    assertThat(Version.parse("1.0-pre.3")).isGreaterThan(Version.parse("1.0-pre.2"));
    assertThat(Version.parse("1.0-pre.10")).isGreaterThan(Version.parse("1.0-pre.2"));
    assertThat(Version.parse("1.0-pre.10a")).isLessThan(Version.parse("1.0-pre.2a"));
    assertThat(Version.parse("1.0-pre.99")).isLessThan(Version.parse("1.0-pre.2a"));
    assertThat(Version.parse("1.0-pre.patch.3")).isLessThan(Version.parse("1.0-pre.patch.4"));
    assertThat(Version.parse("1.0--")).isLessThan(Version.parse("1.0----"));
    assertThat(Version.parse("2.1.1-develop.bcr.20250113215904"))
        .isGreaterThan(Version.parse("2.1.1-develop.bcr.20250113215903"));
  }

  @Test
  public void testParseException() {
    assertThrows(ParseException.class, () -> Version.parse("-abc"));
    assertThrows(ParseException.class, () -> Version.parse("1_2"));
    assertThrows(ParseException.class, () -> Version.parse("ßážëł"));
    assertThrows(ParseException.class, () -> Version.parse("1.0-pre?"));
    assertThrows(
        ParseException.class, () -> Version.parse("1.0-11111111111111111111111111111111111111111"));
    assertThrows(ParseException.class, () -> Version.parse("1.0-pre///"));
    assertThrows(ParseException.class, () -> Version.parse("1..0"));
    assertThrows(ParseException.class, () -> Version.parse("1.0-pre..erp"));
  }
}
