// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.actions;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.util.ResourceFileLoader;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import javax.annotation.Nullable;

/** A template that contains text content, or alternatively throws an {@link IOException}. */
@Immutable // all subclasses are immutable
public abstract class Template {

  /** We only allow subclasses in this file. */
  private Template() {}

  /** Returns the text content of the template. */
  public abstract String getContent(ArtifactPathResolver resolver) throws IOException;

  @Nullable
  public Artifact getTemplateArtifact() {
    return null;
  }

  /**
   * Returns a string that is used for the action key. This must change if the getContent method
   * returns something different, but is not allowed to throw an exception.
   */
  protected abstract String getKey();

  @Override
  public String toString() {
    return getKey();
  }

  private static final class ErrorTemplate extends Template {
    private final IOException e;
    private final String templateName;

    ErrorTemplate(IOException e, String templateName) {
      this.e = e;
      this.templateName = templateName;
    }

    @Override
    public String getContent(ArtifactPathResolver resolver) throws IOException {
      throw new IOException(
          "failed to load resource file '" + templateName + "' due to I/O error: " + e.getMessage(),
          e);
    }

    @Override
    protected String getKey() {
      return "ERROR: " + e.getMessage();
    }
  }

  private static final class StringTemplate extends Template {
    private final String templateText;

    StringTemplate(String templateText) {
      this.templateText = templateText;
    }

    @Override
    public String getContent(ArtifactPathResolver resolver) {
      return templateText;
    }

    @Override
    protected String getKey() {
      return templateText;
    }
  }

  private static final class ArtifactTemplate extends Template {
    private final Artifact templateArtifact;

    ArtifactTemplate(Artifact templateArtifact) {
      this.templateArtifact = templateArtifact;
    }

    @Override
    public String getContent(ArtifactPathResolver resolver) throws IOException {
      Path templatePath = resolver.toPath(templateArtifact);
      try {
        // Bazel's internal encoding for strings is raw bytes as Latin-1
        return FileSystemUtils.readContent(templatePath, ISO_8859_1);
      } catch (IOException e) {
        throw new IOException(
            "failed to load template file '"
                + templatePath.getPathString()
                + "' due to I/O error: "
                + e.getMessage(),
            e);
      }
    }

    @Override
    protected String getKey() {
      // This isn't strictly necessary, because the action inputs are automatically considered.
      return "ARTIFACT: " + templateArtifact.getExecPathString();
    }

    @Override
    public Artifact getTemplateArtifact() {
      return templateArtifact;
    }
  }
  /**
   * Loads a template from the given resource. The resource is looked up relative to the given
   * class. If the resource cannot be loaded, the returned template throws an {@link IOException}
   * when {@link #getContent} is called. This makes it safe to use this method in a constant
   * initializer.
   */
  public static Template forResource(final Class<?> relativeToClass, final String templateName) {
    try {
      String content = ResourceFileLoader.loadResource(relativeToClass, templateName);
      return forString(content);
    } catch (final IOException e) {
      return new ErrorTemplate(e, templateName);
    }
  }

  /** Returns a template for the given text string. */
  public static Template forString(final String templateText) {
    return new StringTemplate(templateText);
  }

  /**
   * Returns a template that loads the given artifact. It is important that the artifact is also an
   * input for the action, or this won't work. Therefore this method is private, and you should use
   * the corresponding {@link TemplateExpansionAction} constructor.
   */
  @VisibleForTesting
  public static Template forArtifact(final Artifact templateArtifact) {
    return new ArtifactTemplate(templateArtifact);
  }
}
