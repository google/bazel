// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LocationExpander.LocationFunction}. */
@RunWith(JUnit4.class)
public class LocationFunctionTest {

  @Test
  public void absoluteAndRelativeLabels() throws Exception {
    LocationFunction func =
        new LocationFunctionBuilder("//foo", false).add("//foo", "/exec/src/bar").build();
    assertThat(func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, null)).isEqualTo("src/bar");
    assertThat(func.apply(":foo", RepositoryMapping.ALWAYS_FALLBACK, null)).isEqualTo("src/bar");
    assertThat(func.apply("foo", RepositoryMapping.ALWAYS_FALLBACK, null)).isEqualTo("src/bar");
  }

  @Test
  public void pathUnderExecRootUsesDotSlash() throws Exception {
    LocationFunction func =
        new LocationFunctionBuilder("//foo", false).add("//foo", "/exec/bar").build();
    assertThat(func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, null)).isEqualTo("./bar");
  }

  @Test
  public void noSuchLabel() throws Exception {
    LocationFunction func = new LocationFunctionBuilder("//foo", false).build();
    IllegalStateException expected =
        assertThrows(
            IllegalStateException.class,
            () -> func.apply("//bar", RepositoryMapping.ALWAYS_FALLBACK, null));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "label '//bar:bar' in $(location) expression is not a declared prerequisite of this "
                + "rule");
  }

  @Test
  public void emptyList() throws Exception {
    LocationFunction func = new LocationFunctionBuilder("//foo", false).add("//foo").build();
    IllegalStateException expected =
        assertThrows(
            IllegalStateException.class,
            () -> func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, null));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo("label '//foo:foo' in $(location) expression expands to no files");
  }

  @Test
  public void tooMany() throws Exception {
    LocationFunction func =
        new LocationFunctionBuilder("//foo", false).add("//foo", "/exec/1", "/exec/2").build();
    IllegalStateException expected =
        assertThrows(
            IllegalStateException.class,
            () -> func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, null));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "label '//foo:foo' in $(location) expression expands to more than one file, "
                + "please use $(locations //foo:foo) instead.  Files (at most 5 shown) are: "
                + "[./1, ./2]");
  }

  @Test
  public void noSuchLabelMultiple() throws Exception {
    LocationFunction func = new LocationFunctionBuilder("//foo", true).build();
    IllegalStateException expected =
        assertThrows(
            IllegalStateException.class,
            () -> func.apply("//bar", RepositoryMapping.ALWAYS_FALLBACK, null));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "label '//bar:bar' in $(locations) expression is not a declared prerequisite of this "
                + "rule");
  }

  @Test
  public void fileWithSpace() throws Exception {
    LocationFunction func =
        new LocationFunctionBuilder("//foo", false).add("//foo", "/exec/file/with space").build();
    assertThat(func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, null))
        .isEqualTo("'file/with space'");
  }

  @Test
  public void multipleFiles() throws Exception {
    LocationFunction func = new LocationFunctionBuilder("//foo", true)
        .add("//foo", "/exec/foo/bar", "/exec/out/foo/foobar")
        .build();
    assertThat(func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, null))
        .isEqualTo("foo/bar foo/foobar");
  }

  @Test
  public void filesWithSpace() throws Exception {
    LocationFunction func = new LocationFunctionBuilder("//foo", true)
        .add("//foo", "/exec/file/with space", "/exec/file/with spaces ")
        .build();
    assertThat(func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, null))
        .isEqualTo("'file/with space' 'file/with spaces '");
  }

  @Test
  public void execPath() throws Exception {
    LocationFunction func =
        new LocationFunctionBuilder("//foo", true)
            .setPathType(LocationFunction.PathType.EXEC)
            .add("//foo", "/exec/bar", "/exec/out/foobar")
            .build();
    assertThat(func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, null))
        .isEqualTo("./bar out/foobar");
  }

  @Test
  public void execPathDirname() throws Exception {
    LocationFunction func =
        new LocationFunctionBuilder("//foo", true)
            .setPathType(LocationFunction.PathType.EXEC)
            .setDirnameOnly(true)
            .add("//foo", "/exec/bar", "/exec/out/subdir/foobar")
            .build();
    assertThat(func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, null))
        .isEqualTo(". out/subdir");
  }

  @Test
  public void rlocationPath() throws Exception {
    LocationFunction func =
        new LocationFunctionBuilder("//foo", true)
            .setPathType(LocationFunction.PathType.RLOCATION)
            .add("//foo", "/exec/bar", "/exec/out/foobar")
            .build();
    assertThat(func.apply("//foo", RepositoryMapping.ALWAYS_FALLBACK, "workspace"))
        .isEqualTo("workspace/bar workspace/foobar");
  }

  @Test
  public void locationFunctionWithMappingReplace() throws Exception {
    RepositoryName b = RepositoryName.create("b");
    ImmutableMap<String, RepositoryName> repositoryMapping = ImmutableMap.of("a", b);
    LocationFunction func =
        new LocationFunctionBuilder("//foo", false).add("@b//foo", "/exec/src/bar").build();
    assertThat(
            func.apply(
                "@a//foo", RepositoryMapping.createAllowingFallback(repositoryMapping), null))
        .isEqualTo("src/bar");
  }

  @Test
  public void locationFunctionWithMappingIgnoreRepo() throws Exception {
    RepositoryName b = RepositoryName.create("b");
    ImmutableMap<String, RepositoryName> repositoryMapping = ImmutableMap.of("a", b);
    LocationFunction func =
        new LocationFunctionBuilder("//foo", false).add("@potato//foo", "/exec/src/bar").build();
    assertThat(
            func.apply(
                "@potato//foo", RepositoryMapping.createAllowingFallback(repositoryMapping), null))
        .isEqualTo("src/bar");
  }
}

final class LocationFunctionBuilder {
  private final Label root;
  private final boolean multiple;
  private LocationFunction.PathType pathType = LocationFunction.PathType.LOCATION;
  private boolean legacyExternalRunfiles;
  private boolean dirnameOnly;
  private final Map<Label, Collection<Artifact>> labelMap = new HashMap<>();

  LocationFunctionBuilder(String rootLabel, boolean multiple) {
    this.root = Label.parseCanonicalUnchecked(rootLabel);
    this.multiple = multiple;
    this.dirnameOnly = false;
  }

  public LocationFunction build() {
    return new LocationFunction(
        root, Suppliers.ofInstance(labelMap), pathType, legacyExternalRunfiles, multiple, dirnameOnly);
  }

  @CanIgnoreReturnValue
  public LocationFunctionBuilder setPathType(LocationFunction.PathType pathType) {
    this.pathType = pathType;
    return this;
  }

  @CanIgnoreReturnValue
  public LocationFunctionBuilder setLegacyExternalRunfiles(boolean legacyExternalRunfiles) {
    this.legacyExternalRunfiles = legacyExternalRunfiles;
    return this;
  }

  @CanIgnoreReturnValue
  public LocationFunctionBuilder setDirnameOnly(boolean dirnameOnly) {
    this.dirnameOnly = dirnameOnly;
    return this;
  }

  @CanIgnoreReturnValue
  public LocationFunctionBuilder add(String label, String... paths) {
    labelMap.put(
        Label.parseCanonicalUnchecked(label),
        Arrays.stream(paths)
            .map(LocationFunctionBuilder::makeArtifact)
            .collect(Collectors.toList()));
    return this;
  }

  private static Artifact makeArtifact(String path) {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    if (path.startsWith("/exec/out")) {
      return ActionsTestUtil.createArtifact(
          ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.Output, "out"),
          fs.getPath(path));
    } else {
      return ActionsTestUtil.createArtifact(
          ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/exec"))), fs.getPath(path));
    }
  }
}
