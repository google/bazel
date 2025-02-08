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
package com.google.devtools.build.lib.runtime;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.devtools.build.lib.buildtool.BuildResult.BuildToolLogCollection;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nullable;

/** Used when instrumentation output is written to a local file. */
final class LocalInstrumentationOutput implements InstrumentationOutput {
  private final Path path;
  private final String name;
  @Nullable private final String convenienceName;
  @Nullable private final Boolean append;
  @Nullable private final Boolean internal;
  private final boolean createParent;

  LocalInstrumentationOutput(
      String name,
      Path path,
      @Nullable String convenienceName,
      @Nullable Boolean append,
      @Nullable Boolean internal,
      boolean createParent) {
    this.name = name;
    this.path = path;
    this.convenienceName = convenienceName;
    this.append = append;
    this.internal = internal;
    this.createParent = createParent;
  }

  @Override
  public void publish(BuildToolLogCollection buildToolLogCollection) {
    buildToolLogCollection.addLocalFile(name, path);
  }

  public void makeConvenienceLink() throws IOException {
    if (convenienceName != null) {
      var link = path.getParentDirectory().getChild(convenienceName);
      link.delete();
      link.createSymbolicLink(PathFragment.create(path.getBaseName()));
    }
  }

  @Override
  public OutputStream createOutputStream() throws IOException {
    if (createParent) {
      path.getParentDirectory().createDirectoryAndParents();
    }
    if (append != null && internal != null) {
      return path.getOutputStream(append, internal);
    }
    if (append != null) {
      return path.getOutputStream(append);
    }
    return path.getOutputStream();
  }

  @Override
  public String getPathString() {
    return path.getPathString();
  }

  /** Builder for {@link LocalInstrumentationOutput}. */
  public static class Builder implements InstrumentationOutputBuilder {
    private String name;
    private Path path;
    @Nullable private String convenienceName;
    @Nullable private Boolean append;
    @Nullable private Boolean internal;
    private boolean createParent;

    @CanIgnoreReturnValue
    @Override
    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    /** Sets the path to the local {@link InstrumentationOutput}. */
    @CanIgnoreReturnValue
    public Builder setPath(Path path) {
      this.path = path;
      return this;
    }

    /**
     * Set the convenience name for the instrumentation output. A symlink at <code>name</code> will
     * be created pointing to the output when {@link
     * LocalInstrumentationOutput#makeConvenienceLink()} is called.
     */
    @CanIgnoreReturnValue
    public Builder setConvenienceName(String name) {
      this.convenienceName = name;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setAppend(@Nullable Boolean append) {
      this.append = append;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setInternal(@Nullable Boolean internal) {
      this.internal = internal;
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public Builder setCreateParent(boolean createParent) {
      this.createParent = createParent;
      return this;
    }

    @Override
    public LocalInstrumentationOutput build() {
      return new LocalInstrumentationOutput(
          checkNotNull(name, "Cannot create LocalInstrumentationOutputBuilder without name"),
          checkNotNull(path, "Cannot create LocalInstrumentationOutputBuilder without path"),
          convenienceName,
          append,
          internal,
          createParent);
    }
  }
}
