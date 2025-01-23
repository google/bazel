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

package com.google.devtools.build.lib.bazel.repository.starlark;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.debug.WorkspaceRuleEvent;
import com.google.devtools.build.lib.bazel.repository.DecompressorDescriptor;
import com.google.devtools.build.lib.bazel.repository.DecompressorValue;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache.KeyType;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.bazel.repository.downloader.HttpUtils;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler.FetchProgress;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput.Dirents;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput.RepoCacheFriendlyPath;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor.ExecutionResult;
import com.google.devtools.build.lib.skyframe.ActionEnvironmentFunction;
import com.google.devtools.build.lib.util.OsUtils;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.errorprone.annotations.ForOverride;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.syntax.Location;

/** A common base class for Starlark "ctx" objects related to external dependencies. */
public abstract class StarlarkBaseExternalContext implements AutoCloseable, StarlarkValue {

  /**
   * An asynchronous task run as part of fetching the repository.
   *
   * <p>The main property of such tasks is that they should under no circumstances keep running
   * after fetching the repository is finished, whether successfully or not. To this end, the {@link
   * #cancel()} method may be called to interrupt the work and {@link #close()} must be called to
   * wait for all such work to finish.
   */
  private interface AsyncTask extends SilentCloseable {
    /** Returns a user-friendly description of the task. */
    String getDescription();

    /** Returns where the task was started from. */
    Location getLocation();

    /**
     * Cancels the task, if not done yet. Returns false if the task was still in progress.
     *
     * <p>Note that the task may still be running after this method returns, the task has just got a
     * signal to interrupt. Call {@link #close()} to wait for the task to finish.
     *
     * <p>No means of error reporting is provided. Any errors should be reported by other means. The
     * only possible error reported as a consequence of calling this method is one that tells the
     * user that they didn't wait for an async task they should have waited for.
     */
    boolean cancel();

    /**
     * Waits uninterruptibly until the task is no longer running, even in case it was cancelled but
     * its underlying thread is still running.
     */
    @Override
    void close();
  }

  /** Max. length of command line args added as a profiler description. */
  private static final int MAX_PROFILE_ARGS_LEN = 512;

  protected final Path workingDirectory;
  protected final BlazeDirectories directories;
  protected final Environment env;
  protected final ImmutableMap<String, String> envVariables;
  private final StarlarkOS osObject;
  protected final DownloadManager downloadManager;
  protected final double timeoutScaling;
  @Nullable private final ProcessWrapper processWrapper;
  protected final StarlarkSemantics starlarkSemantics;
  protected final String identifyingStringForLogging;
  private final HashMap<RepoRecordedInput.File, String> recordedFileInputs = new HashMap<>();
  private final HashMap<RepoRecordedInput.Dirents, String> recordedDirentsInputs = new HashMap<>();
  private final HashSet<String> accumulatedEnvKeys = new HashSet<>();
  private final RepositoryRemoteExecutor remoteExecutor;
  private final List<AsyncTask> asyncTasks;
  private final boolean allowWatchingPathsOutsideWorkspace;
  private final ExecutorService executorService;

  private boolean wasSuccessful = false;

  protected StarlarkBaseExternalContext(
      Path workingDirectory,
      BlazeDirectories directories,
      Environment env,
      Map<String, String> envVariables,
      DownloadManager downloadManager,
      double timeoutScaling,
      @Nullable ProcessWrapper processWrapper,
      StarlarkSemantics starlarkSemantics,
      String identifyingStringForLogging,
      @Nullable RepositoryRemoteExecutor remoteExecutor,
      boolean allowWatchingPathsOutsideWorkspace) {
    this.workingDirectory = workingDirectory;
    this.directories = directories;
    this.env = env;
    this.envVariables = ImmutableMap.copyOf(envVariables);
    this.osObject = new StarlarkOS(this.envVariables);
    this.downloadManager = downloadManager;
    this.timeoutScaling = timeoutScaling;
    this.processWrapper = processWrapper;
    this.starlarkSemantics = starlarkSemantics;
    this.identifyingStringForLogging = identifyingStringForLogging;
    this.remoteExecutor = remoteExecutor;
    this.asyncTasks = new ArrayList<>();
    this.allowWatchingPathsOutsideWorkspace = allowWatchingPathsOutsideWorkspace;
    this.executorService =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                .name("downloads[" + identifyingStringForLogging + "]-", 0)
                .factory());
  }

  /**
   * Mark the evaluation using this context as otherwise successful. This is used to determine how
   * to clean up resources in {@link #close()}.
   */
  public final void markSuccessful() {
    wasSuccessful = true;
  }

  @Override
  public final void close() throws EvalException, IOException {
    // Cancel all pending async tasks.
    boolean hadPendingItems = cancelPendingAsyncTasks();
    // Wait for all (cancelled) async tasks to complete before cleaning up the working directory.
    // This is necessary because downloads may still be in progress and could end up writing to the
    // working directory during deletion, which would cause an error.
    // Note that just calling executorService.close() doesn't suffice as it considers tasks to be
    // completed immediately after they are cancelled, without waiting for their underlying thread
    // to complete.
    executorService.close();
    asyncTasks.forEach(AsyncTask::close);

    if (shouldDeleteWorkingDirectoryOnClose(wasSuccessful)) {
      workingDirectory.deleteTree();
    }
    if (hadPendingItems && wasSuccessful) {
      throw Starlark.errorf(
          "Pending asynchronous work after %s finished execution", identifyingStringForLogging);
    }
  }

  private boolean cancelPendingAsyncTasks() {
    boolean hadPendingItems = false;
    for (AsyncTask task : asyncTasks) {
      if (!task.cancel()) {
        hadPendingItems = true;
        if (wasSuccessful) {
          env.getListener()
              .handle(
                  Event.error(
                      task.getLocation(),
                      String.format(
                          "Work pending after %s finished execution: %s",
                          identifyingStringForLogging, task.getDescription())));
        }
      }
    }

    return hadPendingItems;
  }

  // There is no unregister(). We don't have that many futures in each repository and it just
  // introduces the failure mode of erroneously unregistering async work that's not done.
  protected final void registerAsyncTask(AsyncTask task) {
    asyncTasks.add(task);
  }

  @ForOverride
  protected abstract boolean shouldDeleteWorkingDirectoryOnClose(boolean successful);

  /** Returns the file digests used by this context object so far. */
  public ImmutableMap<RepoRecordedInput.File, String> getRecordedFileInputs() {
    return ImmutableMap.copyOf(recordedFileInputs);
  }

  public ImmutableMap<Dirents, String> getRecordedDirentsInputs() {
    return ImmutableMap.copyOf(recordedDirentsInputs);
  }

  public ImmutableMap<RepoRecordedInput.EnvVar, Optional<String>> getRecordedEnvVarInputs()
      throws InterruptedException {
    // getEnvVarValues doesn't return null since the Skyframe dependencies have already been
    // established by getenv calls.
    return RepoRecordedInput.EnvVar.wrap(
        ImmutableSortedMap.copyOf(RepositoryFunction.getEnvVarValues(env, accumulatedEnvKeys)));
  }

  protected void checkInOutputDirectory(String operation, StarlarkPath path)
      throws RepositoryFunctionException {
    if (!path.getPath().getPathString().startsWith(workingDirectory.getPathString())) {
      throw new RepositoryFunctionException(
          Starlark.errorf(
              "Cannot %s outside of the repository directory for path %s", operation, path),
          Transience.PERSISTENT);
    }
  }

  /**
   * From an authentication dict extract a map of headers.
   *
   * <p>Given a dict as provided as "auth" argument, compute a map specifying for each URI provided
   * which additional headers (as usual, represented as a map from Strings to Strings) should
   * additionally be added to the request. For some form of authentication, in particular basic
   * authentication, adding those headers is enough; for other forms of authentication other
   * measures might be necessary.
   */
  private static ImmutableMap<URI, Map<String, List<String>>> getAuthHeaders(
      Map<String, Dict<?, ?>> auth) throws RepositoryFunctionException, EvalException {
    ImmutableMap.Builder<URI, Map<String, List<String>>> headers = new ImmutableMap.Builder<>();
    for (Map.Entry<String, Dict<?, ?>> entry : auth.entrySet()) {
      try {
        URL url = new URL(entry.getKey());
        Dict<?, ?> authMap = entry.getValue();
        if (authMap.containsKey("type")) {
          if ("basic".equals(authMap.get("type"))) {
            if (!authMap.containsKey("login") || !authMap.containsKey("password")) {
              throw Starlark.errorf(
                  "Found request to do basic auth for %s without 'login' and 'password' being"
                      + " provided.",
                  entry.getKey());
            }
            String credentials = authMap.get("login") + ":" + authMap.get("password");
            headers.put(
                url.toURI(),
                ImmutableMap.of(
                    "Authorization",
                    ImmutableList.of(
                        "Basic "
                            + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8)))));
          } else if ("pattern".equals(authMap.get("type"))) {
            if (!authMap.containsKey("pattern")) {
              throw Starlark.errorf(
                  "Found request to do pattern auth for %s without a pattern being provided",
                  entry.getKey());
            }

            String result = (String) authMap.get("pattern");

            for (String component : Arrays.asList("password", "login")) {
              String demarcatedComponent = "<" + component + ">";

              if (result.contains(demarcatedComponent)) {
                if (!authMap.containsKey(component)) {
                  throw Starlark.errorf(
                      "Auth pattern contains %s but it was not provided in auth dict.",
                      demarcatedComponent);
                }
              } else {
                // component isn't in the pattern, ignore it
                continue;
              }

              result = result.replaceAll(demarcatedComponent, (String) authMap.get(component));
            }

            headers.put(url.toURI(), ImmutableMap.of("Authorization", ImmutableList.of(result)));
          }
        }
      } catch (MalformedURLException e) {
        throw new RepositoryFunctionException(e, Transience.PERSISTENT);
      } catch (URISyntaxException e) {
        throw new EvalException(e);
      }
    }
    return headers.buildOrThrow();
  }

  private static Map<String, Dict<?, ?>> getAuthContents(Dict<?, ?> x, String what)
      throws EvalException {
    // Dict.cast returns Dict<String, raw Dict>.
    @SuppressWarnings({"unchecked", "rawtypes"})
    Map<String, Dict<?, ?>> res = (Map) Dict.cast(x, String.class, Dict.class, what);
    return res;
  }

  private static ImmutableMap<String, List<String>> getHeaderContents(Dict<?, ?> x, String what)
      throws EvalException {
    Dict<String, Object> headersUnchecked =
        (Dict<String, Object>) Dict.cast(x, String.class, Object.class, what);
    ImmutableMap.Builder<String, List<String>> headers = new ImmutableMap.Builder<>();

    for (Map.Entry<String, Object> entry : headersUnchecked.entrySet()) {
      ImmutableList<String> headerValue;
      Object valueUnchecked = entry.getValue();
      if (valueUnchecked instanceof Sequence) {
        headerValue =
            Sequence.cast(valueUnchecked, String.class, "header values").getImmutableList();
      } else if (valueUnchecked instanceof String) {
        headerValue = ImmutableList.of(valueUnchecked.toString());
      } else {
        throw new EvalException(
            String.format(
                "%s argument must be a dict whose keys are string and whose values are either"
                    + " string or sequence of string",
                what));
      }
      headers.put(entry.getKey(), headerValue);
    }
    return headers.buildOrThrow();
  }

  private static ImmutableList<String> checkAllUrls(Iterable<?> urlList) throws EvalException {
    ImmutableList.Builder<String> result = ImmutableList.builder();

    for (Object o : urlList) {
      if (!(o instanceof String)) {
        throw Starlark.errorf(
            "Expected a string or sequence of strings for 'url' argument, but got '%s' item in the"
                + " sequence",
            Starlark.type(o));
      }
      result.add((String) o);
    }

    return result.build();
  }

  private static ImmutableList<URL> getUrls(
      Object urlOrList, boolean ensureNonEmpty, boolean checksumGiven)
      throws RepositoryFunctionException, EvalException {
    ImmutableList<String> urlStrings;
    if (urlOrList instanceof String) {
      urlStrings = ImmutableList.of((String) urlOrList);
    } else {
      urlStrings = checkAllUrls((Iterable<?>) urlOrList);
    }
    if (ensureNonEmpty && urlStrings.isEmpty()) {
      throw new RepositoryFunctionException(new IOException("urls not set"), Transience.PERSISTENT);
    }
    ImmutableList.Builder<URL> urls = ImmutableList.builder();
    for (String urlString : urlStrings) {
      URL url;
      try {
        url = new URL(urlString);
      } catch (MalformedURLException e) {
        throw new RepositoryFunctionException(
            new IOException("Bad URL: " + urlString, e), Transience.PERSISTENT);
      }
      if (!HttpUtils.isUrlSupportedByDownloader(url)) {
        throw new RepositoryFunctionException(
            new IOException("Unsupported protocol: " + url.getProtocol()), Transience.PERSISTENT);
      }
      if (!checksumGiven) {
        if (!Ascii.equalsIgnoreCase("http", url.getProtocol())) {
          urls.add(url);
        }
      } else {
        urls.add(url);
      }
    }
    ImmutableList<URL> urlsResult = urls.build();
    if (ensureNonEmpty && urlsResult.isEmpty()) {
      throw new RepositoryFunctionException(
          new IOException(
              "No URLs left after removing plain http URLs due to missing checksum."
                  + " Please provide either a checksum or an https download location."),
          Transience.PERSISTENT);
    }
    return urlsResult;
  }

  private void warnAboutChecksumError(List<URL> urls, String errorMessage) {
    // Inform the user immediately, even though the file will still be downloaded.
    // This cannot be done by a regular error event, as all regular events are recorded
    // and only shown once the execution of the repository rule is finished.
    // So we have to provide the information as update on the progress
    String url = urls.isEmpty() ? "(unknown)" : urls.get(0).toString();
    reportProgress("Will fail after download of " + url + ". " + errorMessage);
  }

  private Optional<Checksum> validateChecksum(String sha256, String integrity, List<URL> urls)
      throws RepositoryFunctionException, EvalException {
    if (!sha256.isEmpty()) {
      if (!integrity.isEmpty()) {
        throw Starlark.errorf("Expected either 'sha256' or 'integrity', but not both");
      }
      try {
        return Optional.of(Checksum.fromString(KeyType.SHA256, sha256));
      } catch (Checksum.InvalidChecksumException e) {
        warnAboutChecksumError(urls, e.getMessage());
        throw new RepositoryFunctionException(
            Starlark.errorf(
                "Checksum error in %s: %s", identifyingStringForLogging, e.getMessage()),
            Transience.PERSISTENT);
      }
    }

    if (integrity.isEmpty()) {
      return Optional.empty();
    }

    try {
      return Optional.of(Checksum.fromSubresourceIntegrity(integrity));
    } catch (Checksum.InvalidChecksumException e) {
      warnAboutChecksumError(urls, e.getMessage());
      throw new RepositoryFunctionException(
          Starlark.errorf("Checksum error in %s: %s", identifyingStringForLogging, e.getMessage()),
          Transience.PERSISTENT);
    }
  }

  private Checksum calculateChecksum(Optional<Checksum> originalChecksum, Path path)
      throws IOException, InterruptedException {
    if (originalChecksum.isPresent()) {
      // The checksum is checked on download, so if we got here, the user provided checksum is good
      return originalChecksum.get();
    }
    try {
      return Checksum.fromString(KeyType.SHA256, RepositoryCache.getChecksum(KeyType.SHA256, path));
    } catch (Checksum.InvalidChecksumException e) {
      throw new IllegalStateException(
          "Unexpected invalid checksum from internal computation of SHA-256 checksum on "
              + path.getPathString(),
          e);
    }
  }

  private StructImpl calculateDownloadResult(Optional<Checksum> checksum, Path downloadedPath)
      throws InterruptedException, RepositoryFunctionException {
    Checksum finalChecksum;
    try {
      finalChecksum = calculateChecksum(checksum, downloadedPath);
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Couldn't hash downloaded file (" + downloadedPath.getPathString() + ")", e),
          Transience.PERSISTENT);
    }

    ImmutableMap.Builder<String, Object> out = ImmutableMap.builder();
    out.put("success", true);
    out.put("integrity", finalChecksum.toSubresourceIntegrity());

    // For compatibility with older Bazel versions that don't support non-SHA256 checksums.
    if (finalChecksum.getKeyType() == KeyType.SHA256) {
      out.put("sha256", finalChecksum.toString());
    }
    return StarlarkInfo.create(StructProvider.STRUCT, out.buildOrThrow(), Location.BUILTIN);
  }

  private class PendingDownload implements StarlarkValue, AsyncTask {
    private final boolean executable;
    private final boolean allowFail;
    private final StarlarkPath outputPath;
    private final Optional<Checksum> checksum;
    private final RepositoryFunctionException checksumValidation;
    private final Future<Path> future;
    private final Phaser downloadPhaser;
    private final Location location;

    private PendingDownload(
        boolean executable,
        boolean allowFail,
        StarlarkPath outputPath,
        Optional<Checksum> checksum,
        RepositoryFunctionException checksumValidation,
        Future<Path> future,
        Phaser downloadPhaser,
        Location location) {
      this.executable = executable;
      this.allowFail = allowFail;
      this.outputPath = outputPath;
      this.checksum = checksum;
      this.checksumValidation = checksumValidation;
      this.future = future;
      this.downloadPhaser = downloadPhaser;
      this.location = location;
    }

    @Override
    public String getDescription() {
      return String.format("downloading to '%s'", outputPath);
    }

    @Override
    public Location getLocation() {
      return location;
    }

    @Override
    public boolean cancel() {
      return !future.cancel(true);
    }

    @Override
    public void close() {
      if (downloadPhaser.register() != 0) {
        // Not in the download phase, either the download completed normally or
        // it has completed after a cancellation.
        return;
      }
      try (SilentCloseable c = Profiler.instance().profile("Cancelling download " + outputPath)) {
        downloadPhaser.arriveAndAwaitAdvance();
      }
    }

    @StarlarkMethod(
        name = "wait",
        doc =
            """
            Blocks until the completion of the download and returns or throws as blocking \
            <code>download()</code> call would.
            """)
    public StructImpl await() throws InterruptedException, RepositoryFunctionException {
      return completeDownload(this);
    }

    @Override
    public void repr(Printer printer) {
      printer.append(String.format("<pending download to '%s'>", outputPath));
    }
  }

  private StructImpl completeDownload(PendingDownload pendingDownload)
      throws RepositoryFunctionException, InterruptedException {
    Path downloadedPath;
    try {
      downloadedPath = downloadManager.finalizeDownload(pendingDownload.future);
      if (pendingDownload.executable) {
        pendingDownload.outputPath.getPath().setExecutable(true);
      }
    } catch (IOException e) {
      if (pendingDownload.allowFail) {
        return StarlarkInfo.create(
            StructProvider.STRUCT, ImmutableMap.of("success", false), Location.BUILTIN);
      } else {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
    } catch (InvalidPathException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf(
              "Could not create output path %s: %s", pendingDownload.outputPath, e.getMessage()),
          Transience.PERSISTENT);
    } finally {
      pendingDownload.close();
    }
    if (pendingDownload.checksumValidation != null) {
      throw pendingDownload.checksumValidation;
    }

    return calculateDownloadResult(pendingDownload.checksum, downloadedPath);
  }

  @StarlarkMethod(
      name = "download",
      doc =
          """
Downloads a file to the output path for the provided url and returns a struct \
containing <code>success</code>, a flag which is <code>true</code> if the \
download completed successfully, and if successful, a hash of the file \
with the fields <code>sha256</code> and <code>integrity</code>. \
When <code>sha256</code> or <code>integrity</code> is user specified, setting an explicit \
<code>canonical_id</code> is highly recommended. e.g. \
<a href='/rules/lib/repo/cache#get_default_canonical_id'><code>get_default_canonical_id</code></a>
""",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "url",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Iterable.class, generic1 = String.class),
            },
            named = true,
            doc = "List of mirror URLs referencing the same file."),
        @Param(
            name = "output",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            defaultValue = "''",
            named = true,
            doc = "path to the output file, relative to the repository directory."),
        @Param(
            name = "sha256",
            defaultValue = "''",
            named = true,
            doc =
                """
                The expected SHA-256 hash of the file downloaded. \
                This must match the SHA-256 hash of the file downloaded. It is a security \
                risk to omit the SHA-256 as remote files can change. At best omitting this \
                field will make your build non-hermetic. It is optional to make development \
                easier but should be set before shipping. \
                If provided, the repository cache will first be checked for a file with the \
                given hash; a download will only be attempted if the file was not found in \
                the cache. After a successful download, the file will be added to the cache.
                """),
        @Param(
            name = "executable",
            defaultValue = "False",
            named = true,
            doc = "Set the executable flag on the created file, false by default."),
        @Param(
            name = "allow_fail",
            defaultValue = "False",
            named = true,
            doc =
                """
                If set, indicate the error in the return value \
                instead of raising an error for failed downloads.
                """),
        @Param(
            name = "canonical_id",
            defaultValue = "''",
            named = true,
            doc =
                """
                If set, restrict cache hits to those cases where the file was added to the cache \
                with the same canonical id. By default caching uses the checksum \
                (<code>sha256</code> or <code>integrity</code>).
                """),
        @Param(
            name = "auth",
            defaultValue = "{}",
            named = true,
            doc = "An optional dict specifying authentication information for some of the URLs."),
        @Param(
            name = "headers",
            defaultValue = "{}",
            named = true,
            doc = "An optional dict specifying http headers for all URLs."),
        @Param(
            name = "integrity",
            defaultValue = "''",
            named = true,
            positional = false,
            doc =
                """
                Expected checksum of the file downloaded, in Subresource Integrity format. \
                This must match the checksum of the file downloaded. It is a security \
                risk to omit the checksum as remote files can change. At best omitting this \
                field will make your build non-hermetic. It is optional to make development \
                easier but should be set before shipping. \
                If provided, the repository cache will first be checked for a file with the \
                given checksum; a download will only be attempted if the file was not found in \
                the cache. After a successful download, the file will be added to the cache.
                """),
        @Param(
            name = "block",
            defaultValue = "True",
            named = true,
            positional = false,
            doc =
                """
                If set to false, the call returns immediately and instead of the regular return \
                value, it returns a token with one single method, wait(), which blocks \
                until the download is finished and returns the usual return value or \
                throws as usual.
                """)
      })
  public Object download(
      Object url,
      Object output,
      String sha256,
      Boolean executable,
      Boolean allowFail,
      String canonicalId,
      Dict<?, ?> authUnchecked, // <String, Dict> expected
      Dict<?, ?> headersUnchecked, // <String, List<String> | String> expected
      String integrity,
      Boolean block,
      StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    PendingDownload download = null;
    ImmutableMap<URI, Map<String, List<String>>> authHeaders =
        getAuthHeaders(getAuthContents(authUnchecked, "auth"));

    ImmutableMap<String, List<String>> headers = getHeaderContents(headersUnchecked, "headers");

    ImmutableList<URL> urls =
        getUrls(
            url,
            /* ensureNonEmpty= */ !allowFail,
            /* checksumGiven= */ !Strings.isNullOrEmpty(sha256)
                || !Strings.isNullOrEmpty(integrity));
    Optional<Checksum> checksum = null;
    RepositoryFunctionException checksumValidation = null;
    try {
      checksum = validateChecksum(sha256, integrity, urls);
    } catch (RepositoryFunctionException e) {
      checksum = Optional.<Checksum>empty();
      checksumValidation = e;
    }

    StarlarkPath outputPath = getPath(output);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newDownloadEvent(
            urls,
            output.toString(),
            sha256,
            integrity,
            executable,
            identifyingStringForLogging,
            thread.getCallerLocation());
    env.getListener().post(w);

    try {
      checkInOutputDirectory("write", outputPath);
      makeDirectories(outputPath.getPath());
    } catch (IOException e) {
      Phaser downloadPhaser = new Phaser();
      download =
          new PendingDownload(
              executable,
              allowFail,
              outputPath,
              checksum,
              checksumValidation,
              Futures.immediateFailedFuture(e),
              downloadPhaser,
              thread.getCallerLocation());
    }
    if (download == null) {
      Phaser downloadPhaser = new Phaser();
      Future<Path> downloadFuture =
          downloadManager.startDownload(
              executorService,
              urls,
              headers,
              authHeaders,
              checksum,
              canonicalId,
              Optional.<String>empty(),
              outputPath.getPath(),
              allowFail ? NullEventHandler.INSTANCE : env.getListener(),
              envVariables,
              identifyingStringForLogging,
              downloadPhaser);
      download =
          new PendingDownload(
              executable,
              allowFail,
              outputPath,
              checksum,
              checksumValidation,
              downloadFuture,
              downloadPhaser,
              thread.getCallerLocation());
      registerAsyncTask(download);
    }
    if (!block) {
      return download;
    } else {
      return completeDownload(download);
    }
  }

  @StarlarkMethod(
      name = "download_and_extract",
      doc =
          """
Downloads a file to the output path for the provided url, extracts it, and returns a \
struct containing <code>success</code>, a flag which is <code>true</code> if the \
download completed successfully, and if successful, a hash of the file with the \
fields <code>sha256</code> and <code>integrity</code>. \
When <code>sha256</code> or <code>integrity</code> is user specified, setting an explicit \
<code>canonical_id</code> is highly recommended. e.g. \
<a href='/rules/lib/repo/cache#get_default_canonical_id'><code>get_default_canonical_id</code></a>
""",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "url",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Iterable.class, generic1 = String.class),
            },
            named = true,
            doc = "List of mirror URLs referencing the same file."),
        @Param(
            name = "output",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            defaultValue = "''",
            named = true,
            doc =
                """
                Path to the directory where the archive will be unpacked, \
                relative to the repository directory.
                """),
        @Param(
            name = "sha256",
            defaultValue = "''",
            named = true,
            doc =
                """
                The expected SHA-256 hash of the file downloaded. \
                This must match the SHA-256 hash of the file downloaded. It is a security \
                risk to omit the SHA-256 as remote files can change. At best omitting this \
                field will make your build non-hermetic. It is optional to make development \
                easier but should be set before shipping. \
                If provided, the repository cache will first be checked for a file with the \
                given hash; a download will only be attempted if the file was not found in \
                the cache. After a successful download, the file will be added to the \
                cache.
                """),
        @Param(
            name = "type",
            defaultValue = "''",
            named = true,
            doc =
                """
                The archive type of the downloaded file. By default, the archive type is \
                determined from the file extension of the URL. If the file has no \
                extension, you can explicitly specify either "zip", "jar", "war", \
                "aar", "nupkg", "tar", "tar.gz", "tgz", "tar.xz", "txz", ".tar.zst", \
                ".tzst", "tar.bz2", ".tbz", ".ar", or ".deb" here.
                """),
        @Param(
            name = "stripPrefix",
            defaultValue = "''",
            named = true,
            doc =
                """
                A directory prefix to strip from the extracted files.
                Many archives contain a top-level directory that contains all files in the \
                archive. Instead of needing to specify this prefix over and over in the \
                <code>build_file</code>, this field can be used to strip it from extracted \
                files.
                """),
        @Param(
            name = "allow_fail",
            defaultValue = "False",
            named = true,
            doc =
                """
                If set, indicate the error in the return value \
                instead of raising an error for failed downloads.
                """),
        @Param(
            name = "canonical_id",
            defaultValue = "''",
            named = true,
            doc =
                """
                If set, restrict cache hits to those cases where the file was added to the cache \
                with the same canonical id. By default caching uses the checksum"
                (<code>sha256</code> or <code>integrity</code>).
                """),
        @Param(
            name = "auth",
            defaultValue = "{}",
            named = true,
            doc = "An optional dict specifying authentication information for some of the URLs."),
        @Param(
            name = "headers",
            defaultValue = "{}",
            named = true,
            doc = "An optional dict specifying http headers for all URLs."),
        @Param(
            name = "integrity",
            defaultValue = "''",
            named = true,
            positional = false,
            doc =
                """
                Expected checksum of the file downloaded, in Subresource Integrity format. \
                This must match the checksum of the file downloaded. It is a security \
                risk to omit the checksum as remote files can change. At best omitting this \
                field will make your build non-hermetic. It is optional to make development \
                easier but should be set before shipping. \
                If provided, the repository cache will first be checked for a file with the \
                given checksum; a download will only be attempted if the file was not found in \
                the cache. After a successful download, the file will be added to the cache. \
                """),
        @Param(
            name = "rename_files",
            defaultValue = "{}",
            named = true,
            positional = false,
            doc =
                """
An optional dict specifying files to rename during the extraction. Archive entries \
with names exactly matching a key will be renamed to the value, prior to \
any directory prefix adjustment. This can be used to extract archives that \
contain non-Unicode filenames, or which have files that would extract to \
the same path on case-insensitive filesystems.
"""),
      })
  public StructImpl downloadAndExtract(
      Object url,
      Object output,
      String sha256,
      String type,
      String stripPrefix,
      Boolean allowFail,
      String canonicalId,
      Dict<?, ?> authUnchecked, // <String, Dict> expected
      Dict<?, ?> headersUnchecked, // <String, List<String> | String> expected
      String integrity,
      Dict<?, ?> renameFiles, // <String, String> expected
      StarlarkThread thread)
      throws RepositoryFunctionException, InterruptedException, EvalException {
    ImmutableMap<URI, Map<String, List<String>>> authHeaders =
        getAuthHeaders(getAuthContents(authUnchecked, "auth"));

    ImmutableMap<String, List<String>> headers = getHeaderContents(headersUnchecked, "headers");

    ImmutableList<URL> urls =
        getUrls(
            url,
            /*ensureNonEmpty=*/ !allowFail,
            /*checksumGiven=*/ !Strings.isNullOrEmpty(sha256) || !Strings.isNullOrEmpty(integrity));
    Optional<Checksum> checksum;
    RepositoryFunctionException checksumValidation = null;
    try {
      checksum = validateChecksum(sha256, integrity, urls);
    } catch (RepositoryFunctionException e) {
      checksum = Optional.empty();
      checksumValidation = e;
    }

    Map<String, String> renameFilesMap =
        Dict.cast(renameFiles, String.class, String.class, "rename_files");

    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newDownloadAndExtractEvent(
            urls,
            output.toString(),
            sha256,
            integrity,
            type,
            stripPrefix,
            renameFilesMap,
            identifyingStringForLogging,
            thread.getCallerLocation());

    StarlarkPath outputPath = getPath(output);
    checkInOutputDirectory("write", outputPath);
    createDirectory(outputPath.getPath());

    Path downloadedPath;
    Path downloadDirectory;
    try {
      // Download to temp directory inside the outputDirectory and delete it after extraction
      java.nio.file.Path tempDirectory =
          Files.createTempDirectory(Paths.get(outputPath.toString()), "temp");
      downloadDirectory =
          workingDirectory.getFileSystem().getPath(tempDirectory.toFile().getAbsolutePath());

      Phaser downloadPhaser = new Phaser();
      Future<Path> pendingDownload =
          downloadManager.startDownload(
              executorService,
              urls,
              headers,
              authHeaders,
              checksum,
              canonicalId,
              Optional.of(type),
              downloadDirectory,
              allowFail ? NullEventHandler.INSTANCE : env.getListener(),
              envVariables,
              identifyingStringForLogging,
              downloadPhaser);
      // Ensure that the download is cancelled if the repo rule is restarted as it runs in its own
      // executor.
      PendingDownload pendingTask =
          new PendingDownload(
              /* executable= */ false,
              allowFail,
              outputPath,
              checksum,
              checksumValidation,
              pendingDownload,
              downloadPhaser,
              thread.getCallerLocation());
      registerAsyncTask(pendingTask);
      downloadedPath = downloadManager.finalizeDownload(pendingDownload);
    } catch (IOException e) {
      env.getListener().post(w);
      if (allowFail) {
        return StarlarkInfo.create(
            StructProvider.STRUCT, ImmutableMap.of("success", false), Location.BUILTIN);
      } else {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
    }
    if (checksumValidation != null) {
      throw checksumValidation;
    }
    env.getListener().post(w);
    try (SilentCloseable c =
        Profiler.instance().profile("extracting: " + identifyingStringForLogging)) {
      env.getListener()
          .post(
              new ExtractProgress(
                  outputPath.getPath().toString(), "Extracting " + downloadedPath.getBaseName()));
      DecompressorValue.decompress(
          DecompressorDescriptor.builder()
              .setContext(identifyingStringForLogging)
              .setArchivePath(downloadedPath)
              .setDestinationPath(outputPath.getPath())
              .setPrefix(stripPrefix)
              .setRenameFiles(renameFilesMap)
              .build());
      env.getListener().post(new ExtractProgress(outputPath.getPath().toString()));
    }

    StructImpl downloadResult = calculateDownloadResult(checksum, downloadedPath);
    try {
      if (downloadDirectory.exists()) {
        downloadDirectory.deleteTree();
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Couldn't delete temporary directory ("
                  + downloadDirectory.getPathString()
                  + "): "
                  + e.getMessage(),
              e),
          Transience.TRANSIENT);
    }
    return downloadResult;
  }

  @StarlarkMethod(
      name = "extract",
      doc = "Extract an archive to the repository directory.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "archive",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            named = true,
            doc =
                "path to the archive that will be unpacked,"
                    + " relative to the repository directory."),
        @Param(
            name = "output",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            defaultValue = "''",
            named = true,
            doc =
                "path to the directory where the archive will be unpacked,"
                    + " relative to the repository directory."),
        @Param(
            name = "stripPrefix",
            defaultValue = "''",
            named = true,
            doc =
                "a directory prefix to strip from the extracted files."
                    + "\nMany archives contain a top-level directory that contains all files in the"
                    + " archive. Instead of needing to specify this prefix over and over in the"
                    + " <code>build_file</code>, this field can be used to strip it from extracted"
                    + " files."),
        @Param(
            name = "rename_files",
            defaultValue = "{}",
            named = true,
            positional = false,
            doc =
                "An optional dict specifying files to rename during the extraction. Archive entries"
                    + " with names exactly matching a key will be renamed to the value, prior to"
                    + " any directory prefix adjustment. This can be used to extract archives that"
                    + " contain non-Unicode filenames, or which have files that would extract to"
                    + " the same path on case-insensitive filesystems."),
        @Param(
            name = "watch_archive",
            defaultValue = "'auto'",
            positional = false,
            named = true,
            doc =
                "whether to <a href=\"#watch\">watch</a> the archive file. Can be the string "
                    + "'yes', 'no', or 'auto'. Passing 'yes' is equivalent to immediately invoking "
                    + "the <a href=\"#watch\"><code>watch()</code></a> method; passing 'no' does "
                    + "not attempt to watch the file; passing 'auto' will only attempt to watch "
                    + "the file when it is legal to do so (see <code>watch()</code> docs for more "
                    + "information."),
      })
  public void extract(
      Object archive,
      Object output,
      String stripPrefix,
      Dict<?, ?> renameFiles, // <String, String> expected
      String watchArchive,
      StarlarkThread thread)
      throws RepositoryFunctionException, InterruptedException, EvalException {
    StarlarkPath archivePath = getPath(archive);

    if (!archivePath.exists()) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Archive path '%s' does not exist.", archivePath), Transience.TRANSIENT);
    }
    if (archivePath.isDir()) {
      throw Starlark.errorf("attempting to extract a directory: %s", archivePath);
    }
    maybeWatch(archivePath, ShouldWatch.fromString(watchArchive));

    StarlarkPath outputPath = getPath(output);
    checkInOutputDirectory("write", outputPath);

    Map<String, String> renameFilesMap =
        Dict.cast(renameFiles, String.class, String.class, "rename_files");

    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newExtractEvent(
            archive.toString(),
            output.toString(),
            stripPrefix,
            renameFilesMap,
            identifyingStringForLogging,
            thread.getCallerLocation());
    env.getListener().post(w);

    env.getListener()
        .post(
            new ExtractProgress(
                outputPath.getPath().toString(), "Extracting " + archivePath.getBasename()));
    DecompressorValue.decompress(
        DecompressorDescriptor.builder()
            .setContext(identifyingStringForLogging)
            .setArchivePath(archivePath.getPath())
            .setDestinationPath(outputPath.getPath())
            .setPrefix(stripPrefix)
            .setRenameFiles(renameFilesMap)
            .build());
    env.getListener().post(new ExtractProgress(outputPath.getPath().toString()));
  }

  /** A progress event that reports about archive extraction. */
  protected static class ExtractProgress implements FetchProgress {
    private final String repositoryPath;
    private final String progress;
    private final boolean isFinished;

    ExtractProgress(String repositoryPath, String progress) {
      this.repositoryPath = repositoryPath;
      this.progress = progress;
      this.isFinished = false;
    }

    ExtractProgress(String repositoryPath) {
      this.repositoryPath = repositoryPath;
      this.progress = "";
      this.isFinished = true;
    }

    @Override
    public String getResourceIdentifier() {
      return repositoryPath;
    }

    @Override
    public String getProgress() {
      return progress;
    }

    @Override
    public boolean isFinished() {
      return isFinished;
    }
  }

  @StarlarkMethod(
      name = "file",
      doc = "Generates a file in the repository directory with the provided content.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "path",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "Path of the file to create, relative to the repository directory."),
        @Param(
            name = "content",
            named = true,
            defaultValue = "''",
            doc = "The content of the file to create, empty by default."),
        @Param(
            name = "executable",
            named = true,
            defaultValue = "True",
            doc = "Set the executable flag on the created file, true by default."),
        @Param(
            name = "legacy_utf8",
            named = true,
            defaultValue = "True",
            doc =
                """
                Encode file content to UTF-8, true by default. Future versions will change \
                the default and remove this parameter.
                """),
      })
  public void createFile(
      Object path, String content, Boolean executable, Boolean legacyUtf8, StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    StarlarkPath p = getPath(path);
    byte[] contentBytes;
    if (legacyUtf8) {
      contentBytes = content.getBytes(UTF_8);
    } else {
      contentBytes = content.getBytes(ISO_8859_1);
    }
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newFileEvent(
            p.toString(),
            content,
            executable,
            identifyingStringForLogging,
            thread.getCallerLocation());
    env.getListener().post(w);
    try {
      checkInOutputDirectory("write", p);
      makeDirectories(p.getPath());
      p.getPath().delete();
      try (OutputStream stream = p.getPath().getOutputStream()) {
        stream.write(contentBytes);
      }
      if (executable) {
        p.getPath().setExecutable(true);
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    } catch (InvalidPathException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Could not create %s: %s", p, e.getMessage()), Transience.PERSISTENT);
    }
  }

  // Move to a common location like net.starlark.java.eval.Starlark?
  @Nullable
  private static <T> T nullIfNone(Object object, Class<T> type) {
    return object != Starlark.NONE ? type.cast(object) : null;
  }

  @StarlarkMethod(
      name = "getenv",
      doc =
          """
          Returns the value of an environment variable <code>name</code> as a string if exists, \
          or <code>default</code> if it doesn't. \
          <p>When building incrementally, any change to the value of the variable named by \
          <code>name</code> will cause this repository to be re-fetched.
          """,
      parameters = {
        @Param(
            name = "name",
            doc = "Name of desired environment variable.",
            allowedTypes = {@ParamType(type = String.class)}),
        @Param(
            name = "default",
            doc = "Default value to return if <code>name</code> is not found.",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            defaultValue = "None")
      },
      allowReturnNones = true)
  @Nullable
  public String getEnvironmentValue(String name, Object defaultValue)
      throws InterruptedException, NeedsSkyframeRestartException {
    // Must look up via AEF, rather than solely copy from `this.envVariables`, in order to
    // establish a SkyKey dependency relationship.
    if (env.getValue(ActionEnvironmentFunction.key(name)) == null) {
      throw new NeedsSkyframeRestartException();
    }

    // However, to account for --repo_env we take the value from `this.envVariables`.
    // See https://github.com/bazelbuild/bazel/pull/20787#discussion_r1445571248 .
    String envVarValue = envVariables.get(name);
    accumulatedEnvKeys.add(name);
    return envVarValue != null ? envVarValue : nullIfNone(defaultValue, String.class);
  }

  @StarlarkMethod(
      name = "path",
      doc =
          """
          Returns a path from a string, label or path. If the path is relative, it will resolve \
          relative to the repository directory. If the path is a label, it will resolve to \
          the path of the corresponding file. Note that remote repositories are executed \
          during the analysis phase and thus cannot depends on a target result (the \
          label should point to a non-generated file). If path is a path, it will return \
          that path as is.
          """,
      parameters = {
        @Param(
            name = "path",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc =
                "<code>string</code>, <code>Label</code> or <code>path</code> from which to create"
                    + " a path from.")
      })
  public StarlarkPath getPath(Object path) throws EvalException, InterruptedException {
    return switch (path) {
      case String s -> new StarlarkPath(this, workingDirectory.getRelative(s));
      case Label label -> getPathFromLabel(label);
      case StarlarkPath starlarkPath -> starlarkPath;
      // This can never happen because we check it in the Starlark interpreter.
      default -> throw new IllegalArgumentException("expected string or label for path");
    };
  }

  @StarlarkMethod(
      name = "read",
      doc = "Reads the content of a file on the filesystem.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "path",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "Path of the file to read from."),
        @Param(
            name = "watch",
            defaultValue = "'auto'",
            positional = false,
            named = true,
            doc =
                """
                Whether to <a href="#watch">watch</a> the file. Can be the string 'yes', 'no', \
                or 'auto'. Passing 'yes' is equivalent to immediately invoking the \
                <a href="#watch"><code>watch()</code></a> method; passing 'no' does not \
                attempt to watch the file; passing 'auto' will only attempt to watch the \
                file when it is legal to do so (see <code>watch()</code> docs for more \
                information.
                """)
      })
  public String readFile(Object path, String watch, StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    StarlarkPath p = getPath(path);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newReadEvent(
            p.toString(), identifyingStringForLogging, thread.getCallerLocation());
    env.getListener().post(w);
    maybeWatch(p, ShouldWatch.fromString(watch));
    if (p.isDir()) {
      throw Starlark.errorf("attempting to read() a directory: %s", p);
    }
    try {
      return FileSystemUtils.readContent(p.getPath(), ISO_8859_1);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  /**
   * Converts a regular {@link Path} to a {@link RepoCacheFriendlyPath} based on {@link
   * ShouldWatch}. If the path shouldn't be watched for whatever reason, returns null. If it's
   * illegal to watch the path in the current context, but the user still requested a watch, throws
   * an exception.
   */
  @Nullable
  protected RepoCacheFriendlyPath toRepoCacheFriendlyPath(Path path, ShouldWatch shouldWatch)
      throws EvalException {
    if (shouldWatch == ShouldWatch.NO) {
      return null;
    }
    if (path.startsWith(workingDirectory)) {
      // The path is under the working directory. Don't watch it, as it would cause a dependency
      // cycle.
      if (shouldWatch == ShouldWatch.AUTO) {
        return null;
      }
      throw Starlark.errorf("attempted to watch path under working directory");
    }
    if (path.startsWith(directories.getWorkspace())) {
      // The file is under the workspace root.
      PathFragment relPath = path.relativeTo(directories.getWorkspace());
      return RepoCacheFriendlyPath.createInsideWorkspace(RepositoryName.MAIN, relPath);
    }
    Path outputBaseExternal =
        directories.getOutputBase().getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);
    if (path.startsWith(outputBaseExternal)) {
      PathFragment relPath = path.relativeTo(outputBaseExternal);
      if (!relPath.isEmpty()) {
        // The file is under a repo root.
        String repoName = relPath.getSegment(0);
        PathFragment repoRelPath =
            relPath.relativeTo(PathFragment.createAlreadyNormalized(repoName));
        return RepoCacheFriendlyPath.createInsideWorkspace(
            RepositoryName.createUnvalidated(repoName), repoRelPath);
      }
    }
    // The file is just under a random absolute path.
    if (!allowWatchingPathsOutsideWorkspace) {
      if (shouldWatch == ShouldWatch.AUTO) {
        return null;
      }
      throw Starlark.errorf(
          "attempted to watch path outside workspace, but it's prohibited in the current context");
    }
    return RepoCacheFriendlyPath.createOutsideWorkspace(path.asFragment());
  }

  /** Whether to watch a path. See {@link #readFile} for semantics */
  protected enum ShouldWatch {
    YES,
    NO,
    AUTO;

    static ShouldWatch fromString(String s) throws EvalException {
      switch (s) {
        case "yes":
          return YES;
        case "no":
          return NO;
        case "auto":
          return AUTO;
        default:
          throw Starlark.errorf(
              "bad value for 'watch' parameter; want 'yes', 'no', or 'auto', got %s", s);
      }
    }
  }

  protected void maybeWatch(StarlarkPath starlarkPath, ShouldWatch shouldWatch)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    RepoCacheFriendlyPath repoCacheFriendlyPath =
        toRepoCacheFriendlyPath(starlarkPath.getPath(), shouldWatch);
    if (repoCacheFriendlyPath == null) {
      return;
    }
    try {
      var recordedInput = new RepoRecordedInput.File(repoCacheFriendlyPath);
      FileValue fileValue =
          (FileValue) env.getValueOrThrow(recordedInput.getSkyKey(directories), IOException.class);
      if (fileValue == null) {
        throw new NeedsSkyframeRestartException();
      }

      recordedFileInputs.put(
          recordedInput, RepoRecordedInput.File.fileValueToMarkerValue(fileValue));
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  protected void maybeWatchDirents(Path path, ShouldWatch shouldWatch)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    RepoCacheFriendlyPath repoCacheFriendlyPath = toRepoCacheFriendlyPath(path, shouldWatch);
    if (repoCacheFriendlyPath == null) {
      return;
    }
    var recordedInput = new RepoRecordedInput.Dirents(repoCacheFriendlyPath);
    if (env.getValue(recordedInput.getSkyKey(directories)) == null) {
      throw new NeedsSkyframeRestartException();
    }
    try {
      recordedDirentsInputs.put(
          recordedInput, RepoRecordedInput.Dirents.getDirentsMarkerValue(path));
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @StarlarkMethod(
      name = "watch",
      doc =
          """
          Tells Bazel to watch for changes to the given path, whether or not it exists, or \
          whether it's a file or a directory. Any changes to the file or directory will \
          invalidate this repository or module extension, and cause it to be refetched or \
          re-evaluated next time.<p>"Changes" include changes to the contents of the file \
          (if the path is a file); if the path was a file but is now a directory, or vice \
          versa; and if the path starts or stops existing. Notably, this does <em>not</em> \
          include changes to any files under the directory if the path is a directory. For \
          that, use <a href="path.html#readdir"><code>path.readdir()</code></a> \
          instead.<p>Note that attempting to watch paths inside the repo currently being \
          fetched, or inside the working directory of the current module extension, will \
          result in an error. A module extension attempting to watch a path outside the \
          current Bazel workspace will also result in an error.
          """,
      parameters = {
        @Param(
            name = "path",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "Path of the file to watch."),
      })
  public void watchForStarlark(Object path)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    maybeWatch(getPath(path), ShouldWatch.YES);
  }

  // Create parent directories for the given path
  protected static void makeDirectories(Path path) throws IOException {
    Path parent = path.getParentDirectory();
    if (parent != null) {
      parent.createDirectoryAndParents();
    }
  }

  @StarlarkMethod(
      name = "report_progress",
      doc = "Updates the progress status for the fetching of this repository or module extension.",
      parameters = {
        @Param(
            name = "status",
            defaultValue = "''",
            allowedTypes = {@ParamType(type = String.class)},
            doc = "<code>string</code> describing the current status of the fetch progress.")
      })
  public void reportProgress(String status) {
    env.getListener()
        .post(
            new FetchProgress() {
              @Override
              public String getResourceIdentifier() {
                return identifyingStringForLogging;
              }

              @Override
              public String getProgress() {
                return status;
              }

              @Override
              public boolean isFinished() {
                return false;
              }
            });
  }

  @StarlarkMethod(
      name = "os",
      structField = true,
      doc = "A struct to access information from the system.")
  public StarlarkOS getOS() {
    // Historically this event reported the location of the ctx.os expression, but that's no longer
    // available in the interpreter API. Now we just use a dummy location, and the user must
    // manually inspect the code where this context object is used if they wish to find the
    // offending ctx.os expression.
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newOsEvent(identifyingStringForLogging, Location.BUILTIN);
    env.getListener().post(w);
    return osObject;
  }

  protected static void createDirectory(Path directory) throws RepositoryFunctionException {
    try {
      if (!directory.exists()) {
        makeDirectories(directory);
        directory.createDirectory();
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    } catch (InvalidPathException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Could not create %s: %s", directory, e.getMessage()),
          Transience.PERSISTENT);
    }
  }

  /** Whether this context supports remote execution. */
  protected abstract boolean isRemotable();

  private boolean canExecuteRemote() {
    boolean featureEnabled =
        starlarkSemantics.getBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC);
    boolean remoteExecEnabled = remoteExecutor != null;
    return featureEnabled && isRemotable() && remoteExecEnabled;
  }

  protected abstract ImmutableMap<String, String> getRemoteExecProperties() throws EvalException;

  private Map.Entry<PathFragment, Path> getRemotePathFromLabel(Label label)
      throws EvalException, InterruptedException {
    Path localPath = getPathFromLabel(label).getPath();
    PathFragment remotePath =
        label.getPackageIdentifier().getSourceRoot().getRelative(label.getName());
    return Maps.immutableEntry(remotePath, localPath);
  }

  private StarlarkExecutionResult executeRemote(
      Sequence<?> argumentsUnchecked, // <String> or <Label> expected
      int timeout,
      Map<String, String> environment,
      boolean quiet,
      String workingDirectory)
      throws EvalException, InterruptedException {
    Preconditions.checkState(canExecuteRemote());

    ImmutableSortedMap.Builder<PathFragment, Path> inputsBuilder =
        ImmutableSortedMap.naturalOrder();
    ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
    for (Object argumentUnchecked : argumentsUnchecked) {
      if (argumentUnchecked instanceof Label) {
        Label label = (Label) argumentUnchecked;
        Map.Entry<PathFragment, Path> remotePath = getRemotePathFromLabel(label);
        argumentsBuilder.add(remotePath.getKey().toString());
        inputsBuilder.put(remotePath);
      } else {
        argumentsBuilder.add(argumentUnchecked.toString());
      }
    }

    ImmutableList<String> arguments = argumentsBuilder.build();

    try (SilentCloseable c =
        Profiler.instance()
            .profile(
                ProfilerTask.STARLARK_REPOSITORY_FN, () -> profileArgsDesc("remote", arguments))) {
      ExecutionResult result =
          remoteExecutor.execute(
              arguments,
              inputsBuilder.buildOrThrow(),
              getRemoteExecProperties(),
              ImmutableMap.copyOf(environment),
              workingDirectory,
              Duration.ofSeconds(timeout));

      String stdout = new String(result.stdout(), StandardCharsets.US_ASCII);
      String stderr = new String(result.stderr(), StandardCharsets.US_ASCII);

      if (!quiet) {
        OutErr outErr = OutErr.SYSTEM_OUT_ERR;
        outErr.printOut(stdout);
        outErr.printErr(stderr);
      }

      return new StarlarkExecutionResult(result.exitCode(), stdout, stderr);
    } catch (IOException e) {
      throw Starlark.errorf("remote_execute failed: %s", e.getMessage());
    }
  }

  private void validateExecuteArguments(Sequence<?> arguments) throws EvalException {
    boolean isRemotable = isRemotable();
    for (int i = 0; i < arguments.size(); i++) {
      Object arg = arguments.get(i);
      if (isRemotable) {
        if (!(arg instanceof String || arg instanceof Label)) {
          throw Starlark.errorf("Argument %d of execute is neither a label nor a string.", i);
        }
      } else {
        if (!(arg instanceof String || arg instanceof Label || arg instanceof StarlarkPath)) {
          throw Starlark.errorf("Argument %d of execute is neither a path, label, nor string.", i);
        }
      }
    }
  }

  /** Returns the command line arguments as a string for display in the profiler. */
  private static String profileArgsDesc(String method, List<String> args) {
    StringBuilder b = new StringBuilder();
    b.append(method).append(":");

    final String sep = " ";
    for (String arg : args) {
      int appendLen = sep.length() + arg.length();
      int remainingLen = MAX_PROFILE_ARGS_LEN - b.length();

      if (appendLen <= remainingLen) {
        b.append(sep);
        b.append(arg);
      } else {
        String shortenedArg = (sep + arg).substring(0, remainingLen);
        b.append(shortenedArg);
        b.append("...");
        break;
      }
    }

    return b.toString();
  }

  @StarlarkMethod(
      name = "execute",
      doc =
          """
          Executes the command given by the list of arguments. The execution time of the command \
          is limited by <code>timeout</code> (in seconds, default 600 seconds). This method \
          returns an <code>exec_result</code> structure containing the output of the \
          command. The <code>environment</code> map can be used to override some \
          environment variables to be passed to the process.
          """,
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "arguments",
            doc =
                """
                List of arguments, the first element should be the path to the program to \
                execute.
                """),
        @Param(
            name = "timeout",
            named = true,
            defaultValue = "600",
            doc = "Maximum duration of the command in seconds (default is 600 seconds)."),
        @Param(
            name = "environment",
            defaultValue = "{}",
            named = true,
            doc = "Force some environment variables to be set to be passed to the process."),
        @Param(
            name = "quiet",
            defaultValue = "True",
            named = true,
            doc = "If stdout and stderr should be printed to the terminal."),
        @Param(
            name = "working_directory",
            defaultValue = "\"\"",
            named = true,
            doc =
                """
                Working directory for command execution.
                Can be relative to the repository root or absolute.
                The default is the repository root.
                """),
      })
  public StarlarkExecutionResult execute(
      Sequence<?> arguments, // <String> or <StarlarkPath> or <Label> expected
      StarlarkInt timeoutI,
      Dict<?, ?> uncheckedEnvironment, // <String, String> expected
      boolean quiet,
      String overrideWorkingDirectory,
      StarlarkThread thread)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    validateExecuteArguments(arguments);
    int timeout = Starlark.toInt(timeoutI, "timeout");

    Map<String, String> forceEnvVariables =
        Dict.cast(uncheckedEnvironment, String.class, String.class, "environment");

    if (canExecuteRemote()) {
      return executeRemote(arguments, timeout, forceEnvVariables, quiet, overrideWorkingDirectory);
    }

    // Execute on the local/host machine

    List<String> args = new ArrayList<>(arguments.size());
    for (Object arg : arguments) {
      if (arg instanceof Label) {
        args.add(getPathFromLabel((Label) arg).toString());
      } else {
        // String or StarlarkPath expected
        args.add(arg.toString());
      }
    }

    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newExecuteEvent(
            args,
            timeout,
            envVariables,
            forceEnvVariables,
            workingDirectory.getPathString(),
            quiet,
            identifyingStringForLogging,
            thread.getCallerLocation());
    env.getListener().post(w);
    createDirectory(workingDirectory);

    long timeoutMillis = Math.round(timeout * 1000L * timeoutScaling);
    if (processWrapper != null) {
      args =
          processWrapper
              .commandLineBuilder(args)
              .setTimeout(Duration.ofMillis(timeoutMillis))
              .build();
    }

    Path workingDirectoryPath;
    if (overrideWorkingDirectory != null && !overrideWorkingDirectory.isEmpty()) {
      workingDirectoryPath = getPath(overrideWorkingDirectory).getPath();
    } else {
      workingDirectoryPath = workingDirectory;
    }
    createDirectory(workingDirectoryPath);

    final List<String> fargs = args;
    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.STARLARK_REPOSITORY_FN, () -> profileArgsDesc("local", fargs))) {
      return StarlarkExecutionResult.builder(osObject.getEnvironmentVariables())
          .addArguments(args)
          .setDirectory(workingDirectoryPath.getPathFile())
          .addEnvironmentVariables(forceEnvVariables)
          .setTimeout(timeoutMillis)
          .setQuiet(quiet)
          .execute();
    }
  }

  @StarlarkMethod(
      name = "which",
      doc =
          """
          Returns the <code>path</code> of the corresponding program or <code>None</code> \
          if there is no such program in the path.
          """,
      allowReturnNones = true,
      useStarlarkThread = true,
      parameters = {
        @Param(name = "program", named = false, doc = "Program to find in the path."),
      })
  @Nullable
  public StarlarkPath which(String program, StarlarkThread thread) throws EvalException {
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newWhichEvent(
            program, identifyingStringForLogging, thread.getCallerLocation());
    env.getListener().post(w);
    if (program.contains("/") || program.contains("\\")) {
      throw Starlark.errorf(
          "Program argument of which() may not contain a / or a \\ ('%s' given)", program);
    }
    if (program.length() == 0) {
      throw Starlark.errorf("Program argument of which() may not be empty");
    }
    try {
      StarlarkPath commandPath = findCommandOnPath(program);
      if (commandPath != null) {
        return commandPath;
      }

      if (!program.endsWith(OsUtils.executableExtension())) {
        program += OsUtils.executableExtension();
        return findCommandOnPath(program);
      }
    } catch (IOException e) {
      // IOException when checking executable file means we cannot read the file data so
      // we cannot execute it, swallow the exception.
    }
    return null;
  }

  @Nullable
  private StarlarkPath findCommandOnPath(String program) throws IOException {
    String pathEnvVariable = envVariables.get("PATH");
    if (pathEnvVariable == null) {
      return null;
    }
    for (String p : pathEnvVariable.split(File.pathSeparator)) {
      PathFragment fragment = PathFragment.create(p);
      if (fragment.isAbsolute()) {
        // We ignore relative path as they don't mean much here (relative to where? the workspace
        // root?).
        Path path = workingDirectory.getFileSystem().getPath(fragment).getChild(program.trim());
        if (path.exists() && path.isFile(Symlinks.FOLLOW) && path.isExecutable()) {
          return new StarlarkPath(this, path);
        }
      }
    }
    return null;
  }

  // Resolve the label given by value into a file path.
  protected StarlarkPath getPathFromLabel(Label label) throws EvalException, InterruptedException {
    RootedPath rootedPath = RepositoryFunction.getRootedPathFromLabel(label, env);
    StarlarkPath starlarkPath = new StarlarkPath(this, rootedPath.asPath());
    try {
      maybeWatch(
          starlarkPath,
          starlarkSemantics.getBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL)
              ? ShouldWatch.NO
              : ShouldWatch.AUTO);
    } catch (RepositoryFunctionException e) {
      throw Starlark.errorf("%s", e.getCause().getMessage());
    }
    return starlarkPath;
  }
}
