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

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader.UploadContext;
import com.google.devtools.build.lib.buildtool.BuildResult.BuildToolLogCollection;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.OutputStream;
import javax.annotation.Nullable;

/**
 * Used when instrumentation output should be treated as a build event artifact so that it will be
 * uploaded to a specified location.
 */
public final class BuildEventArtifactInstrumentationOutput implements InstrumentationOutput {
  private final String name;
  private final BuildEventArtifactUploader buildEventArtifactUploader;
  @Nullable private UploadContext uploadContext;

  public BuildEventArtifactInstrumentationOutput(
      String name, BuildEventArtifactUploader buildEventArtifactUploader) {
    this.name = checkNotNull(name);
    this.buildEventArtifactUploader = checkNotNull(buildEventArtifactUploader);
  }

  @Override
  public void publish(BuildToolLogCollection buildToolLogCollection) {
    checkNotNull(uploadContext, "Cannot publish to buildToolLogCollection if upload never starts.");
    buildToolLogCollection.addUriFuture(name, uploadContext.uriFuture());
  }

  @Override
  public OutputStream createOutputStream() {
    uploadContext = buildEventArtifactUploader.startUpload(LocalFileType.LOG, null);
    return uploadContext.getOutputStream();
  }

  /** Builder for {@link BuildEventArtifactInstrumentationOutput} */
  public static class Builder implements InstrumentationOutputBuilder {
    private String name;
    private BuildEventArtifactUploader uploader;

    @CanIgnoreReturnValue
    @Override
    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setUploader(BuildEventArtifactUploader uploader) {
      this.uploader = uploader;
      return this;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This is a no-op for {@link BuildEventArtifactInstrumentationOutput} since it will never be
     * written to a local path.
     */
    @CanIgnoreReturnValue
    @Override
    public Builder setCreateParent(boolean createParent) {
      return this;
    }

    @Override
    public BuildEventArtifactInstrumentationOutput build() {
      return new BuildEventArtifactInstrumentationOutput(
          checkNotNull(name, "Cannot create BuildEventArtifactInstrumentationOutput without name"),
          checkNotNull(
              uploader,
              "Cannot create BuildEventArtifactInstrumentationOutput without bepUploader"));
    }
  }
}
