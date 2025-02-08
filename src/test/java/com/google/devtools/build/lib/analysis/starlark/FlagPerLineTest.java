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

package com.google.devtools.build.lib.analysis.starlark;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Checks the result of each variant {@code Args.add*} call, in flag_per_line format. Note, writes
 * the
 */
@RunWith(JUnit4.class)
public class FlagPerLineTest extends BuildViewTestCase {

  // Initially empty, with "flag_per_line" format.
  private Args args;
  private final Mutability mutability = Mutability.create();
  private StarlarkThread thread;

  @Before
  public void initArgs() throws Exception {
    args = Args.newArgs(mutability, getStarlarkSemantics());
    args.setParamFileFormat("flag_per_line");
    thread = StarlarkThread.createTransient(mutability, getStarlarkSemantics());
  }

  @Test
  public void add_noname() throws Exception {
    args.addArgument("--foo", Starlark.UNBOUND, /* format= */ Starlark.NONE, thread);
    expectLines("--foo");
  }

  @Test
  public void add_name() throws Exception {
    args.addArgument("--foo", "bar", /* format= */ Starlark.NONE, thread);
    expectLines("--foo=bar");
  }

  @Test
  public void add_all_noname() throws Exception {
    args.addAll(
        /* argNameOrValue= */ "", // ignored
        /* values= */ StarlarkList.of(null, "--foo", "bar", "baz"),
        /* mapEach= */ Starlark.NONE,
        /* formatEach= */ Starlark.NONE,
        /* beforeEach= */ Starlark.NONE,
        /* omitIfEmpty= */ true, // the default
        /* uniquify= */ false,
        /* expandDirectories= */ false,
        /* terminateWith= */ Starlark.NONE,
        /* allowClosure= */ false,
        thread);
    // Absl would reject this line, but it's what we generate.
    expectLines("--foo bar baz");
  }

  @Test
  public void add_all_name() throws Exception {
    args.addAll(
        /* argNameOrValue= */ "--foo",
        /* values= */ StarlarkList.of(null, "bar", "baz"),
        /* mapEach= */ Starlark.NONE,
        /* formatEach= */ Starlark.NONE,
        /* beforeEach= */ Starlark.NONE,
        /* omitIfEmpty= */ true, // the default
        /* uniquify= */ false,
        /* expandDirectories= */ false,
        /* terminateWith= */ Starlark.NONE,
        /* allowClosure= */ false,
        thread);
    // Absl interprets this as a single value "bar baz" for the flag "--foo",
    // which is probably not what was intended.
    expectLines("--foo=bar baz");
  }

  @Test
  public void add_joined_noname() throws Exception {
    args.addJoined(
        /* argNameOrValue= */ "", // ignored
        /* values= */ StarlarkList.of(null, "--foo", "bar", "baz"),
        /* joinWith= */ ",",
        /* mapEach= */ Starlark.NONE,
        /* formatEach= */ Starlark.NONE,
        /* formatJoined= */ Starlark.NONE,
        /* omitIfEmpty= */ true, // the default
        /* uniquify= */ false,
        /* expandDirectories= */ false,
        /* allowClosure= */ false,
        thread);
    // Absl would reject this line, but it's what we generate.
    expectLines("--foo,bar,baz");
  }

  @Test
  public void add_joined_name() throws Exception {
    args.addJoined(
        /* argNameOrValue= */ "--foo",
        /* values= */ StarlarkList.of(null, "bar", "baz", "woof"),
        /* joinWith= */ ",",
        /* mapEach= */ Starlark.NONE,
        /* formatEach= */ Starlark.NONE,
        /* formatJoined= */ Starlark.NONE,
        /* omitIfEmpty= */ true,
        /* uniquify= */ false,
        /* expandDirectories= */ false,
        /* allowClosure= */ false,
        thread);
    expectLines("--foo=bar,baz,woof");
  }

  /** Tests that an add_all (empty and omitted) following two adds works. */
  @Test
  public void args_combinedOmittedAddAllAndAdd() throws Exception {
    args.addAll(
        /* argNameOrValue= */ "", // ignored
        /* values= */ StarlarkList.of(null),
        /* mapEach= */ Starlark.NONE,
        /* formatEach= */ Starlark.NONE,
        /* beforeEach= */ Starlark.NONE,
        /* omitIfEmpty= */ true, // the default
        /* uniquify= */ false,
        /* expandDirectories= */ false,
        /* terminateWith= */ Starlark.NONE,
        /* allowClosure= */ false,
        thread);
    args.addArgument("--foo1", "bar", /* format= */ Starlark.NONE, thread);
    args.addArgument("--foo2", "bar", /* format= */ Starlark.NONE, thread);

    expectLines("--foo1=bar", "--foo2=bar");
  }

  private void expectLines(String... lines) throws Exception {
    assertThat(toParamFile(args)).containsExactly((Object[]) lines).inOrder();
  }

  /** Writes out the Args using ParameterFile, returns the output broken down as lines. */
  private static ImmutableList<String> toParamFile(Args args) throws Exception {
    byte[] bytes;
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      ParameterFile.writeParameterFile(
          outputStream,
          args.build(() -> RepositoryMapping.ALWAYS_FALLBACK).arguments(),
          args.getParameterFileType());
      bytes = outputStream.toByteArray();
    }
    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
        InputStreamReader reader = new InputStreamReader(inputStream, UTF_8)) {
      return ImmutableList.copyOf(CharStreams.readLines(reader));
    }
  }
}
