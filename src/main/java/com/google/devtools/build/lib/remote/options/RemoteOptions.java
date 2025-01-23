// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.remote.options;

import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.Platform.Property;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.remote.Scrubber;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution.Code;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Converters.AssignmentConverter;
import com.google.devtools.common.options.Converters.BooleanConverter;
import com.google.devtools.common.options.Converters.ByteSizeConverter;
import com.google.devtools.common.options.Converters.DurationConverter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;

/** Options for remote execution and distributed caching for Bazel only. */
public final class RemoteOptions extends CommonRemoteOptions {

  @Option(
      name = "remote_proxy",
      oldName = "remote_cache_proxy",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Connect to the remote cache through a proxy. Currently this flag can only be used to "
              + "configure a Unix domain socket (unix:/path/to/socket).")
  public String remoteProxy;

  @Option(
      name = "remote_max_connections",
      defaultValue = "100",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "Limit the max number of concurrent connections to remote cache/executor. By default the"
              + " value is 100. Setting this to 0 means no limitation.\n"
              + "For HTTP remote cache, one TCP connection could handle one request at one time, so"
              + " Bazel could make up to --remote_max_connections concurrent requests.\n"
              + "For gRPC remote cache/executor, one gRPC channel could usually handle 100+"
              + " concurrent requests, so Bazel could make around `--remote_max_connections * 100`"
              + " concurrent requests.")
  public int remoteMaxConnections;

  @Option(
      name = "remote_executor",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "HOST or HOST:PORT of a remote execution endpoint. The supported schemas are grpc, "
              + "grpcs (grpc with TLS enabled) and unix (local UNIX sockets). If no schema is "
              + "provided Bazel will default to grpcs. Specify grpc:// or unix: schema to "
              + "disable TLS.")
  public String remoteExecutor;

  @Option(
      name = "experimental_remote_execution_keepalive",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Whether to use keepalive for remote execution calls.")
  public boolean remoteExecutionKeepalive;

  @Option(
      name = "experimental_remote_capture_corrupted_outputs",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      converter = OptionsUtils.PathFragmentConverter.class,
      help = "A path to a directory where the corrupted outputs will be captured to.")
  public PathFragment remoteCaptureCorruptedOutputs;

  @Option(
      name = "experimental_remote_cache_async",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If true, remote cache I/O will happen in the background instead of taking place as the"
              + " part of a spawn.")
  public boolean remoteCacheAsync;

  @Option(
      name = "remote_cache",
      oldName = "remote_http_cache",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "A URI of a caching endpoint. The supported schemas are http, https, grpc, grpcs "
              + "(grpc with TLS enabled) and unix (local UNIX sockets). If no schema is provided "
              + "Bazel will default to grpcs. Specify grpc://, http:// or unix: schema to disable "
              + "TLS. See https://bazel.build/remote/caching")
  public String remoteCache;

  @Option(
      name = "experimental_remote_downloader",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "A Remote Asset API endpoint URI, to be used as a remote download proxy. The supported"
              + " schemas are grpc, grpcs (grpc with TLS enabled) and unix (local UNIX sockets). If"
              + " no schema is provided Bazel will default to grpcs. See: "
              + "https://github.com/bazelbuild/remote-apis/blob/master/build/bazel/remote/asset/v1/remote_asset.proto")
  public String remoteDownloader;

  @Option(
      name = "experimental_remote_downloader_local_fallback",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Whether to fall back to the local downloader if remote downloader fails.")
  public boolean remoteDownloaderLocalFallback;

  @Option(
      name = "remote_header",
      converter = Converters.AssignmentConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Specify a header that will be included in requests: --remote_header=Name=Value. "
              + "Multiple headers can be passed by specifying the flag multiple times. Multiple "
              + "values for the same name will be converted to a comma-separated list.",
      allowMultiple = true)
  public List<Entry<String, String>> remoteHeaders;

  @Option(
      name = "remote_cache_header",
      converter = Converters.AssignmentConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Specify a header that will be included in cache requests: "
              + "--remote_cache_header=Name=Value. "
              + "Multiple headers can be passed by specifying the flag multiple times. Multiple "
              + "values for the same name will be converted to a comma-separated list.",
      allowMultiple = true)
  public List<Entry<String, String>> remoteCacheHeaders;

  @Option(
      name = "remote_exec_header",
      converter = Converters.AssignmentConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Specify a header that will be included in execution requests: "
              + "--remote_exec_header=Name=Value. "
              + "Multiple headers can be passed by specifying the flag multiple times. Multiple "
              + "values for the same name will be converted to a comma-separated list.",
      allowMultiple = true)
  public List<Entry<String, String>> remoteExecHeaders;

  @Option(
      name = "remote_downloader_header",
      converter = Converters.AssignmentConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Specify a header that will be included in remote downloader requests: "
              + "--remote_downloader_header=Name=Value. "
              + "Multiple headers can be passed by specifying the flag multiple times. Multiple "
              + "values for the same name will be converted to a comma-separated list.",
      allowMultiple = true)
  public List<Entry<String, String>> remoteDownloaderHeaders;

  @Option(
      name = "remote_timeout",
      defaultValue = "60s",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      converter = RemoteDurationConverter.class,
      help =
          "The maximum amount of time to wait for remote execution and cache calls. For the REST"
              + " cache, this is both the connect and the read timeout. Following units can be"
              + " used: Days (d), hours (h), minutes (m), seconds (s), and milliseconds (ms). If"
              + " the unit is omitted, the value is interpreted as seconds.")
  public Duration remoteTimeout;

  @Option(
      name = "remote_bytestream_uri_prefix",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The hostname and instance name to be used in bytestream:// URIs that are written into "
              + "build event streams. This option can be set when builds are performed using a "
              + "proxy, which causes the values of --remote_executor and --remote_instance_name "
              + "to no longer correspond to the canonical name of the remote execution service. "
              + "When not set, it will default to \"${hostname}/${instance_name}\".")
  public String remoteBytestreamUriPrefix;

  @Option(
      name = "remote_accept_cached",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Whether to accept remotely cached action results.")
  public boolean remoteAcceptCached;

  @Option(
      name = "experimental_remote_require_cached",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If set to true, enforce that all actions that can run remotely are cached, or else "
              + "fail the build. This is useful to troubleshoot non-determinism issues as it "
              + "allows checking whether actions that should be cached are actually cached "
              + "without spuriously injecting new results into the cache.")
  public boolean remoteRequireCached;

  @Option(
      name = "remote_local_fallback",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Whether to fall back to standalone local execution strategy if remote execution fails.")
  public boolean remoteLocalFallback;

  @Deprecated
  @Option(
      name = "remote_local_fallback_strategy",
      defaultValue = "local",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "No-op, deprecated. See https://github.com/bazelbuild/bazel/issues/7480 for details.")
  public String remoteLocalFallbackStrategy;

  @Option(
      name = "remote_upload_local_results",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Whether to upload locally executed action results to the remote cache if the remote "
              + "cache supports it and the user is authorized to do so.")
  public boolean remoteUploadLocalResults;

  @Deprecated
  @Option(
      name = "incompatible_remote_build_event_upload_respect_no_cache",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      deprecationWarning =
          "--incompatible_remote_build_event_upload_respect_no_cache has been deprecated in favor"
              + " of --remote_build_event_upload=minimal.",
      help = "Deprecated. No-op. Use --remote_build_event_upload=minimal instead.")
  public boolean incompatibleRemoteBuildEventUploadRespectNoCache;

  @Option(
      name = "remote_build_event_upload",
      oldName = "experimental_remote_build_event_upload",
      defaultValue = "minimal",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      converter = RemoteBuildEventUploadModeConverter.class,
      help =
          "If set to 'all', all local outputs referenced by BEP are uploaded to remote cache.\n"
              + "If set to 'minimal', local outputs referenced by BEP are not uploaded to the"
              + " remote cache, except for files that are important to the consumers of BEP (e.g."
              + " test logs and timing profile). bytestream:// scheme is always used for the uri of"
              + " files even if they are missing from remote cache.\n"
              + "Default to 'minimal'.")
  public RemoteBuildEventUploadMode remoteBuildEventUploadMode;

  /** Build event upload mode flag parser */
  public static class RemoteBuildEventUploadModeConverter
      extends EnumConverter<RemoteBuildEventUploadMode> {
    public RemoteBuildEventUploadModeConverter() {
      super(RemoteBuildEventUploadMode.class, "remote build event upload");
    }
  }

  @Option(
      name = "incompatible_remote_results_ignore_disk",
      defaultValue = "true",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "No-op")
  public boolean incompatibleRemoteResultsIgnoreDisk;

  @Option(
      name = "incompatible_remote_output_paths_relative_to_input_root",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set to true, output paths are relative to input root instead of working directory.")
  public boolean incompatibleRemoteOutputPathsRelativeToInputRoot;

  @Option(
      name = "remote_instance_name",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Value to pass as instance_name in the remote execution API.")
  public String remoteInstanceName;

  @Option(
      name = "remote_retries",
      oldName = "experimental_remote_retry_max_attempts",
      defaultValue = "5",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The maximum number of attempts to retry a transient error. "
              + "If set to 0, retries are disabled.")
  public int remoteMaxRetryAttempts;

  // TODO(https://github.com/bazelbuild/bazel/issues/18319): Remove once the Remote Build
  // protocol can distinguish between abrupt worker termination and action termination.
  @Option(
      name = "snowflake_remote_exit_signals_are_transient_errors",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Consider abrupt terminations of remote non-test actions as transient failures. "
              + "This can be used to retry actions that fail due to their remote worker "
              + "crashing instead of failing the build. Note that this cannot differentiate "
              + "between those failures and legitimate action crashes so, if you enable this, "
              + "you should also set --remote_retries to non-zero or enable local fallback "
              + "to avoid getting into an infinite retry loop.")
  public boolean remoteExitSignalsAreTransientErrors;

  @Option(
      name = "remote_retry_max_delay",
      defaultValue = "5s",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      converter = RemoteDurationConverter.class,
      help =
          "The maximum backoff delay between remote retry attempts. Following units can be used:"
              + " Days (d), hours (h), minutes (m), seconds (s), and milliseconds (ms). If"
              + " the unit is omitted, the value is interpreted as seconds.")
  public Duration remoteRetryMaxDelay;

  @Option(
      name = "disk_cache",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      converter = OptionsUtils.PathFragmentConverter.class,
      help =
          "A path to a directory where Bazel can read and write actions and action outputs. "
              + "If the directory does not exist, it will be created.")
  public PathFragment diskCache;

  @Option(
      name = "experimental_disk_cache_gc_idle_delay",
      defaultValue = "5m",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      converter = DurationConverter.class,
      help =
          "How long the server must remain idle before a garbage collection of the disk cache"
              + " occurs. To specify the garbage collection policy, set"
              + " --experimental_disk_cache_gc_max_size and/or"
              + " --experimental_disk_cache_gc_max_age.")
  public Duration diskCacheGcIdleDelay;

  @Option(
      name = "experimental_disk_cache_gc_max_size",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      converter = ByteSizeConverter.class,
      help =
          "If set to a positive value, the disk cache will be periodically garbage collected to"
              + " stay under this size. If set in conjunction with"
              + " --experimental_disk_cache_gc_max_age, both criteria are applied. Garbage"
              + " collection occurrs in the background once the server has become idle, as"
              + " determined by the --experimental_disk_cache_gc_idle_delay flag.")
  public long diskCacheGcMaxSize;

  @Option(
      name = "experimental_disk_cache_gc_max_age",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      converter = DurationConverter.class,
      help =
          "If set to a positive value, the disk cache will be periodically garbage collected to"
              + " remove entries older than this age. If set in conjunction with"
              + " --experimental_disk_cache_gc_max_size, both criteria are applied. Garbage"
              + " collection occurrs in the background once the server has become idle, as"
              + " determined by the --experimental_disk_cache_gc_idle_delay flag.")
  public Duration diskCacheGcMaxAge;

  @Option(
      name = "experimental_guard_against_concurrent_changes",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Turn this off to disable checking the ctime of input files of an action before "
              + "uploading it to a remote cache. There may be cases where the Linux kernel delays "
              + "writing of files, which could cause false positives.")
  public boolean experimentalGuardAgainstConcurrentChanges;

  @Option(
      name = "remote_grpc_log",
      oldName = "experimental_remote_grpc_log",
      defaultValue = "null",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      converter = OptionsUtils.PathFragmentConverter.class,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If specified, a path to a file to log gRPC call related details. This log consists of a"
              + " sequence of serialized "
              + "com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry "
              + "protobufs with each message prefixed by a varint denoting the size of the"
              + " following serialized protobuf message, as performed by the method "
              + "LogEntry.writeDelimitedTo(OutputStream).")
  public PathFragment remoteGrpcLog;

  @Option(
      name = "incompatible_remote_symlinks",
      defaultValue = "true",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set to true, Bazel will always upload symlinks as such to a remote or disk cache."
              + " Otherwise, non-dangling relative symlinks (and only those) will be uploaded as"
              + " the file or directory they point to.")
  public boolean incompatibleRemoteSymlinks;

  @Option(
      name = "incompatible_remote_dangling_symlinks",
      defaultValue = "true",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If set to true, symlinks uploaded to a remote or disk cache are allowed to dangle.")
  public boolean incompatibleRemoteDanglingSymlinks;

  @Option(
      name = "remote_cache_compression",
      oldName = "experimental_remote_cache_compression",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If enabled, compress/decompress cache blobs with zstd when their size is at least"
              + " --experimental_remote_cache_compression_threshold.")
  public boolean cacheCompression;

  @Option(
      name = "experimental_remote_cache_compression_threshold",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The minimum blob size required to compress/decompress with zstd. Ineffectual unless"
              + " --remote_cache_compression is set.")
  public int cacheCompressionThreshold;

  @Option(
      name = "build_event_upload_max_threads",
      defaultValue = "100",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "The number of threads used to do build event uploads. Capped at 1000.")
  public int buildEventUploadMaxThreads;

  @Option(
      name = "remote_download_outputs",
      oldName = "experimental_remote_download_outputs",
      defaultValue = "toplevel",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      converter = RemoteOutputsStrategyConverter.class,
      help =
          "If set to 'minimal' doesn't download any remote build outputs to the local machine, "
              + "except the ones required by local actions. If set to 'toplevel' behaves like"
              + "'minimal' except that it also downloads outputs of top level targets to the local "
              + "machine. Both options can significantly reduce build times if network bandwidth "
              + "is a bottleneck.")
  public RemoteOutputsMode remoteOutputsMode;

  /** Outputs strategy flag parser */
  public static class RemoteOutputsStrategyConverter extends EnumConverter<RemoteOutputsMode> {
    public RemoteOutputsStrategyConverter() {
      super(RemoteOutputsMode.class, "download remote outputs");
    }
  }

  @Option(
      name = "remote_download_minimal",
      oldName = "experimental_remote_download_minimal",
      defaultValue = "null",
      expansion = {"--remote_download_outputs=minimal"},
      category = "remote",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Does not download any remote build outputs to the local machine. This flag is an alias"
              + " for --remote_download_outputs=minimal.")
  public Void remoteOutputsMinimal;

  @Option(
      name = "remote_download_toplevel",
      oldName = "experimental_remote_download_toplevel",
      defaultValue = "null",
      expansion = {"--remote_download_outputs=toplevel"},
      category = "remote",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Only downloads remote outputs of top level targets to the local machine. This flag is an"
              + " alias for --remote_download_outputs=toplevel.")
  public Void remoteOutputsToplevel;

  @Option(
      name = "remote_download_all",
      defaultValue = "null",
      expansion = {"--remote_download_outputs=all"},
      category = "remote",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Downloads all remote outputs to the local machine. This flag is an alias for"
              + " --remote_download_outputs=all.")
  public Void remoteOutputsAll;

  @Option(
      name = "remote_result_cache_priority",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The relative priority of remote actions to be stored in remote cache. "
              + "The semantics of the particular priority values are server-dependent.")
  public int remoteResultCachePriority;

  @Option(
      name = "remote_execution_priority",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The relative priority of actions to be executed remotely. "
              + "The semantics of the particular priority values are server-dependent.")
  public int remoteExecutionPriority;

  @Option(
      name = "remote_default_platform_properties",
      oldName = "host_platform_remote_properties_override",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      deprecationWarning =
          "--remote_default_platform_properties has been deprecated in favor of"
              + " --remote_default_exec_properties.",
      help =
          "Set the default platform properties to be set for the remote execution API, "
              + "if the execution platform does not already set remote_execution_properties. "
              + "This value will also be used if the host platform is selected as the execution "
              + "platform for remote execution.")
  public String remoteDefaultPlatformProperties;

  @Option(
      name = "remote_default_exec_properties",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      converter = AssignmentConverter.class,
      allowMultiple = true,
      help =
          "Set the default exec properties to be used as the remote execution platform "
              + "if an execution platform does not already set exec_properties.")
  public List<Map.Entry<String, String>> remoteDefaultExecProperties;

  @Option(
      name = "remote_verify_downloads",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If set to true, Bazel will compute the hash sum of all remote downloads and "
              + " discard the remotely cached values if they don't match the expected value.")
  public boolean remoteVerifyDownloads;

  @Option(
      name = "experimental_remote_merkle_tree_cache",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If set to true, Merkle tree calculations will be memoized to improve the remote cache "
              + "hit checking speed. The memory foot print of the cache is controlled by "
              + "--experimental_remote_merkle_tree_cache_size.")
  public boolean remoteMerkleTreeCache;

  @Option(
      name = "experimental_remote_merkle_tree_cache_size",
      defaultValue = "1000",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The number of Merkle trees to memoize to improve the remote cache hit checking speed. "
              + "Even though the cache is automatically pruned according to Java's handling of "
              + "soft references, out-of-memory errors can occur if set too high. If set to 0 "
              + " the cache size is unlimited. Optimal value varies depending on project's size. "
              + "Default to 1000.")
  public long remoteMerkleTreeCacheSize;

  @Option(
      name = "remote_download_symlink_template",
      defaultValue = "",
      category = "remote",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "Instead of downloading remote build outputs to the local machine, create symbolic "
              + "links. The target of the symbolic links can be specified in the form of a "
              + "template string. This template string may contain {hash} and {size_bytes} that "
              + "expand to the hash of the object and the size in bytes, respectively. "
              + "These symbolic links may, for example, point to a FUSE file system "
              + "that loads objects from the CAS on demand.")
  public String remoteDownloadSymlinkTemplate;

  @Option(
      name = "bep_maximum_open_remote_upload_files",
      defaultValue = "-1",
      documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Maximum number of open files allowed during BEP artifact upload.")
  public int maximumOpenFiles;

  @Option(
      name = "remote_print_execution_messages",
      defaultValue = "failure",
      converter = ExecutionMessagePrintMode.Converter.class,
      category = "remote",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Choose when to print remote execution messages. Valid values are `failure`, "
              + "to print only on failures, `success` to print only on successes and "
              + "`all` to print always.")
  public ExecutionMessagePrintMode remotePrintExecutionMessages;

  @Option(
      name = "incompatible_remote_downloader_send_all_headers",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "Whether to send all values of a multi-valued header to the remote downloader instead of"
              + " just the first.")
  public boolean remoteDownloaderSendAllHeaders;

  @Option(
      name = "experimental_remote_mark_tool_inputs",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If set to true, Bazel will mark inputs as tool inputs for the remote executor. This "
              + "can be used to implement remote persistent workers.")
  public boolean markToolInputs;

  @Option(
      name = "experimental_remote_discard_merkle_trees",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If set to true, discard in-memory copies of the input root's Merkle tree and associated "
              + "input mappings during calls to GetActionResult() and Execute(). This reduces "
              + "memory usage significantly, but does require Bazel to recompute them upon remote "
              + "cache misses and retries.")
  public boolean remoteDiscardMerkleTrees;

  @Option(
      name = "experimental_circuit_breaker_strategy",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      defaultValue = "null",
      effectTags = {OptionEffectTag.EXECUTION},
      converter = CircuitBreakerStrategy.Converter.class,
      help =
          "Specifies the strategy for the circuit breaker to use. Available strategies are"
              + " \"failure\". On invalid value for the option the behavior same as the option is"
              + " not set.")
  public CircuitBreakerStrategy circuitBreakerStrategy;

  @Option(
      name = "experimental_remote_failure_rate_threshold",
      defaultValue = "10",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.EXECUTION},
      converter = Converters.PercentageConverter.class,
      help =
          "Sets the allowed number of failure rate in percentage for a specific time window after"
              + " which it stops calling to the remote cache/executor. By default the value is 10."
              + " Setting this to 0 means no limitation.")
  public int remoteFailureRateThreshold;

  @Option(
      name = "experimental_remote_failure_window_interval",
      defaultValue = "60s",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.EXECUTION},
      converter = RemoteDurationConverter.class,
      help =
          "The interval in which the failure rate of the remote requests are computed. On zero or"
              + " negative value the failure duration is computed the whole duration of the"
              + " execution.Following units can be used: Days (d), hours (h), minutes (m), seconds"
              + " (s), and milliseconds (ms). If the unit is omitted, the value is interpreted as"
              + " seconds.")
  public Duration remoteFailureWindowInterval;

  @Option(
      name = "experimental_remote_cache_lease_extension",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If set to true, Bazel will extend the lease for outputs of remote actions during the"
              + " build by sending `FindMissingBlobs` calls periodically to remote cache. The"
              + " frequency is based on the value of `--experimental_remote_cache_ttl`.")
  public boolean remoteCacheLeaseExtension;

  @Option(
      name = "experimental_remote_scrubbing_config",
      converter = ScrubberConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Enables remote cache key scrubbing with the supplied configuration file, which must be a"
              + " protocol buffer in text format (see"
              + " src/main/protobuf/remote_scrubbing.proto).\n\n"
              + "This feature is intended to facilitate sharing a remote/disk cache between actions"
              + " executing on different platforms but targeting the same platform. It should be"
              + " used with extreme care, as improper settings may cause accidental sharing of"
              + " cache entries and result in incorrect builds.\n\n"
              + "Scrubbing does not affect how an action is executed, only how its remote/disk"
              + " cache key is computed for the purpose of retrieving or storing an action result."
              + " Scrubbed actions are incompatible with remote execution, and will always be"
              + " executed locally instead.\n\n"
              + "Modifying the scrubbing configuration does not invalidate outputs present in the"
              + " local filesystem or internal caches; a clean build is required to reexecute"
              + " affected actions.\n\n"
              + "In order to successfully use this feature, you likely want to set a custom"
              + " --host_platform together with --experimental_platform_in_output_dir (to normalize"
              + " output prefixes) and --incompatible_strict_action_env (to normalize environment"
              + " variables).")
  public Scrubber scrubber;

  @Option(
      name = "experimental_throttle_remote_action_building",
      defaultValue = "true",
      converter = BooleanConverter.class,
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      metadataTags = OptionMetadataTag.EXPERIMENTAL,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "Whether to throttle the building of remote action to avoid OOM. Defaults to true.\n\n"
              + "This is a temporary flag to allow users switch off the behaviour. Once Bazel is"
              + " smart enough about the RAM/CPU usages, this flag will be removed.")
  public boolean throttleRemoteActionBuilding;

  @Option(
      name = "experimental_remote_output_service",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "HOST or HOST:PORT of a remote output service endpoint. The supported schemas are grpc, "
              + "grpcs (grpc with TLS enabled) and unix (local UNIX sockets). If no schema is "
              + "provided Bazel will default to grpcs. Specify grpc:// or unix: schema to "
              + "disable TLS.")
  public String remoteOutputService;

  @Option(
      name = "experimental_remote_output_service_output_path_prefix",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.REMOTE,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "The path under which the contents of output directories managed by the"
              + " --experimental_remote_output_service are placed. The actual output directory used"
              + " by a build will be a descendant of this path and determined by the output"
              + " service.")
  public String remoteOutputServiceOutputPathPrefix;

  private static final class ScrubberConverter extends Converter.Contextless<Scrubber> {

    @Override
    public Scrubber convert(String path) throws OptionsParsingException {
      try {
        return Scrubber.parse(path);
      } catch (Scrubber.ConfigParseException e) {
        throw new OptionsParsingException("Failed to parse ScrubbingConfig: " + e.getMessage(), e);
      }
    }

    @Override
    public String getTypeDescription() {
      return "Converts to a Scrubber";
    }
  }

  // The below options are not configurable by users, only tests.
  // This is part of the effort to reduce the overall number of flags.

  /** The maximum size of an outbound message sent via a gRPC channel. */
  public int maxOutboundMessageSize = 1024 * 1024;

  /** Returns {@code true} if remote cache or disk cache is enabled. */
  public boolean isRemoteCacheEnabled() {
    return !Strings.isNullOrEmpty(remoteCache)
        || !(diskCache == null || diskCache.isEmpty())
        || isRemoteExecutionEnabled();
  }

  /** Returns {@code true} if remote execution is enabled. */
  public boolean isRemoteExecutionEnabled() {
    return !Strings.isNullOrEmpty(remoteExecutor);
  }

  /**
   * Returns the default exec properties specified by the user or an empty map if nothing was
   * specified. Use this method instead of directly accessing the fields.
   */
  public SortedMap<String, String> getRemoteDefaultExecProperties() throws UserExecException {
    boolean hasExecProperties = !remoteDefaultExecProperties.isEmpty();
    boolean hasPlatformProperties = !remoteDefaultPlatformProperties.isEmpty();

    if (hasExecProperties && hasPlatformProperties) {
      throw new UserExecException(
          createFailureDetail(
              "Setting both --remote_default_platform_properties and "
                  + "--remote_default_exec_properties is not allowed. Prefer setting "
                  + "--remote_default_exec_properties.",
              Code.INVALID_EXEC_AND_PLATFORM_PROPERTIES));
    }

    if (hasExecProperties) {
      return ImmutableSortedMap.copyOf(remoteDefaultExecProperties);
    }
    if (hasPlatformProperties) {
      // Try and use the provided default value.
      final Platform platform;
      try {
        Platform.Builder builder = Platform.newBuilder();
        TextFormat.getParser().merge(remoteDefaultPlatformProperties, builder);
        platform = builder.build();
      } catch (ParseException e) {
        String message =
            "Failed to parse --remote_default_platform_properties "
                + remoteDefaultPlatformProperties;
        throw new UserExecException(
            e, createFailureDetail(message, Code.REMOTE_DEFAULT_PLATFORM_PROPERTIES_PARSE_FAILURE));
      }

      ImmutableSortedMap.Builder<String, String> builder = ImmutableSortedMap.naturalOrder();
      for (Property property : platform.getPropertiesList()) {
        builder.put(property.getName(), property.getValue());
      }
      return builder.buildOrThrow();
    }

    return ImmutableSortedMap.of();
  }

  private static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setRemoteExecution(RemoteExecution.newBuilder().setCode(detailedCode))
        .build();
  }

  /** An enum for specifying different modes for printing remote execution messages. */
  public enum ExecutionMessagePrintMode {
    FAILURE, // Print execution messages only on failure
    SUCCESS, // Print execution messages only on success
    ALL; // Print execution messages always

    /** Converts to {@link ExecutionMessagePrintMode}. */
    public static class Converter extends EnumConverter<ExecutionMessagePrintMode> {
      public Converter() {
        super(ExecutionMessagePrintMode.class, "execution message print mode");
      }
    }

    public boolean shouldPrintMessages(boolean success) {
      return ((!success && this == ExecutionMessagePrintMode.FAILURE)
          || (success && this == ExecutionMessagePrintMode.SUCCESS)
          || this == ExecutionMessagePrintMode.ALL);
    }
  }

  /** An enum for specifying different strategy for circuit breaker. */
  public enum CircuitBreakerStrategy {
    FAILURE;

    /** Converts to {@link CircuitBreakerStrategy}. */
    public static class Converter extends EnumConverter<CircuitBreakerStrategy> {
      public Converter() {
        super(CircuitBreakerStrategy.class, "CircuitBreaker strategy");
      }
    }
  }
}
