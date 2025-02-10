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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.Fingerprint;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;

/**
 * Action to write a file whose contents are known at analysis time.
 *
 * <p>The output file is generally encoded as UTF-8, but by an unusual path. BUILD files and
 * directory entries, which are actually UTF-8, are misinterpreted by Bazel as Latin1, so that most
 * Strings within the build language use this unusual representation. FileWriteAction writes those
 * Strings out again as Latin1.
 *
 * <p>The contents may be lazily computed or compressed. If the object representing the contents is
 * a {@code String}, its length is greater than {@code COMPRESS_CHARS_THRESHOLD}, and compression is
 * enabled, then the gzipped bytestream of the contents will be stored in place of the string
 * itself. This compression is transparent and does not affect the output file.
 *
 * <p>Otherwise, if the object represents a lazy computation, it will not be forced until {@link
 * #getFileContents} is called. An example where this may come in handy is if the contents are the
 * concatenation of the string representations of a series of artifacts. Then the client code can
 * wrap a {@code List<Artifact>} in a {@link com.google.devtools.build.lib.util.OnDemandString},
 * which saves memory since the artifacts are shared objects whereas a string is not.
 *
 * <p>TODO(b/146554973): Change this implementation when that is addressed.
 *
 * <p>TODO(bazel-team): Choose a better name to distinguish this class from {@link
 * BinaryFileWriteAction}.
 */
@Immutable // if fileContents is immutable
public abstract class FileWriteAction extends AbstractFileWriteAction
    implements AbstractFileWriteAction.FileContentsProvider {

  /** Minimum length (in chars) for content to be eligible for compression. */
  private static final int COMPRESS_CHARS_THRESHOLD = 256;

  private final boolean makeExecutable;

  /**
   * Creates a FileWriteAction to write contents to the resulting artifact fileName in the genfiles
   * root underneath the package path.
   *
   * @param ruleContext the ruleContext that will own the action of creating this file
   * @param fileName name of the file to create
   * @param contents data to write to file
   * @param executable flags that file should be marked executable
   * @return Artifact describing the file to create
   */
  public static Artifact createFile(
      RuleContext ruleContext, String fileName, CharSequence contents, boolean executable) {
    Artifact scriptFileArtifact =
        ruleContext.getPackageRelativeArtifact(fileName, ruleContext.getGenfilesDirectory());
    ruleContext.registerAction(
        FileWriteAction.create(ruleContext, scriptFileArtifact, contents, executable));
    return scriptFileArtifact;
  }

  /**
   * Creates a new FileWriteAction instance with inputs and empty content.
   *
   * <p>This is useful for producing an artifact that, if built, will ensure that the generating
   * actions for its inputs are run. The output file is non-executable.
   *
   * @param owner the action owner
   * @param inputs the Artifacts that this Action depends on
   * @param output the Artifact that will be created by executing this Action
   */
  public static FileWriteAction createEmptyWithInputs(
      ActionOwner owner, NestedSet<Artifact> inputs, Artifact output) {
    return createInternal(owner, inputs, output, "", false, Compression.DISALLOW);
  }

  /**
   * Creates a new FileWriteAction instance with direct control over whether or not transparent
   * compression may be used.
   *
   * @param owner the action owner
   * @param output the Artifact that will be created by executing this Action
   * @param fileContents the contents to be written to the file
   * @param makeExecutable whether the output file is made executable
   * @param allowCompression whether (transparent) compression is enabled
   */
  public static FileWriteAction create(
      ActionOwner owner,
      Artifact output,
      CharSequence fileContents,
      boolean makeExecutable,
      Compression allowCompression) {
    return createInternal(
        owner,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        output,
        fileContents,
        makeExecutable,
        allowCompression);
  }

  /**
   * Creates a new FileWriteAction instance.
   *
   * <p>There are no inputs. No reference to the {@link ActionConstructionContext} will be
   * maintained.
   *
   * @param context the action construction context
   * @param output the Artifact that will be created by executing this Action
   * @param fileContents the contents to be written to the file
   * @param makeExecutable whether the output file is made executable
   */
  public static FileWriteAction create(
      ActionConstructionContext context,
      Artifact output,
      CharSequence fileContents,
      boolean makeExecutable) {
    return create(
        context.getActionOwner(), output, fileContents, makeExecutable, Compression.ALLOW);
  }

  private static FileWriteAction createInternal(
      ActionOwner owner,
      NestedSet<Artifact> inputs,
      Artifact output,
      CharSequence fileContents,
      boolean makeExecutable,
      Compression allowCompression) {
    if (allowCompression == Compression.ALLOW
        && fileContents instanceof String
        && fileContents.length() > COMPRESS_CHARS_THRESHOLD) {
      return new CompressedFileWriteAction(
          owner, inputs, output, makeExecutable, (String) fileContents);
    }
    return new RegularFileWriteAction(owner, inputs, output, makeExecutable, fileContents);
  }

  private FileWriteAction(
      ActionOwner owner,
      NestedSet<Artifact> inputs,
      Artifact primaryOutput,
      boolean makeExecutable) {
    super(owner, inputs, primaryOutput);
    this.makeExecutable = makeExecutable;
  }

  @Override
  public final String getFileContents(@Nullable EventHandler eventHandler) {
    return getFileContents();
  }

  @Override
  public boolean makeExecutable() {
    return makeExecutable;
  }

  /**
   * Returns the string contents to be written.
   *
   * <p>Note that if the string is lazily computed or compressed, calling this method will force its
   * computation or decompression. No attempt is made by FileWriteAction to cache the result.
   *
   * <p>Note that the content is a not a normal Java String. When Bazel parses BUILD files, it
   * misinterprets the bytes as Latin1, so a code point with a 3-byte UTF-8 encoding will take 3
   * chars internally. To reverse this process, you must encode this string as Latin1, giving you
   * back the correct UTF-8 encoding of the original input.
   */
  public abstract String getFileContents();

  @Override
  public final String getStarlarkContent() {
    return getFileContents();
  }

  private static final class RegularFileWriteAction extends FileWriteAction {
    private static final String GUID = "332877c7-ca9f-4731-b387-54f620408522";

    private final CharSequence fileContents;

    RegularFileWriteAction(
        ActionOwner owner,
        NestedSet<Artifact> inputs,
        Artifact primaryOutput,
        boolean makeExecutable,
        CharSequence fileContents) {
      super(owner, inputs, primaryOutput, makeExecutable);
      this.fileContents = fileContents;
    }

    @Override
    public String getFileContents() {
      return fileContents.toString();
    }

    @Override
    public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
      return out -> out.write(StringUnsafe.getInstance().getInternalStringBytes(getFileContents()));
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      fp.addString(GUID).addBoolean(makeExecutable()).addString(getFileContents());
    }
  }

  private static final class CompressedFileWriteAction extends FileWriteAction {
    private static final String GUID = "5bfba914-2251-11ee-be56-0242ac120002";
    private static final int GZIP_BYTES_BUFFER = 8192;

    private final byte[] compressedBytes;
    private final int uncompressedSize;
    private final byte coder;

    CompressedFileWriteAction(
        ActionOwner owner,
        NestedSet<Artifact> inputs,
        Artifact primaryOutput,
        boolean makeExecutable,
        String fileContents) {
      super(owner, inputs, primaryOutput, makeExecutable);

      // Grab the string's internal byte array. Calling getBytes() makes a copy, which can cause
      // memory spikes resulting in OOMs (b/290807073). Do not mutate this!
      byte[] dataToCompress = StringUnsafe.getInstance().getByteArray(fileContents);

      // Empirically, compressed sizes range from roughly 1/100 to 3/4 of the uncompressed size.
      // Presize on the small end to avoid over-allocating memory.
      ByteArrayOutputStream byteStream = new ByteArrayOutputStream(dataToCompress.length / 100);

      try (GZIPOutputStream zipStream = new GZIPOutputStream(byteStream, GZIP_BYTES_BUFFER)) {
        zipStream.write(dataToCompress);
      } catch (IOException e) {
        // This should be impossible since we're writing to a byte array.
        throw new IllegalStateException(e);
      }

      this.compressedBytes = byteStream.toByteArray();
      this.uncompressedSize = dataToCompress.length;
      this.coder = StringUnsafe.getInstance().getCoder(fileContents);
    }

    @Override
    public String getFileContents() {
      byte[] uncompressedBytes = new byte[uncompressedSize];
      try (GZIPInputStream zipStream =
          new GZIPInputStream(new ByteArrayInputStream(compressedBytes), GZIP_BYTES_BUFFER)) {
        int read;
        int totalRead = 0;
        while (totalRead < uncompressedSize
            && (read = zipStream.read(uncompressedBytes, totalRead, uncompressedSize - totalRead))
                != -1) {
          totalRead += read;
        }
        checkState(totalRead == uncompressedSize, "Corrupt byte buffer in FileWriteAction");
      } catch (IOException e) {
        // This should be impossible since we're reading from a byte array.
        throw new IllegalStateException(e);
      }

      return StringUnsafe.getInstance().newInstance(uncompressedBytes, coder);
    }

    @Override
    public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
      return out -> {
        try (GZIPInputStream gzipIn =
            new GZIPInputStream(new ByteArrayInputStream(compressedBytes), GZIP_BYTES_BUFFER)) {
          ByteStreams.copy(gzipIn, out);
        }
      };
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      fp.addString(GUID).addBoolean(makeExecutable()).addBytes(compressedBytes);
    }
  }
}
