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

package com.google.devtools.build.lib.authandtls;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Converters.DurationConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import java.net.IDN;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Common options for authentication and TLS.
 */
public class AuthAndTLSOptions extends OptionsBase {
  @Option(
    name = "google_default_credentials",
    oldName = "auth_enabled",
    defaultValue = "false",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    help =
        "Whether to use 'Google Application Default Credentials' for authentication."
            + " See https://cloud.google.com/docs/authentication for details. Disabled by default."
  )
  public boolean useGoogleDefaultCredentials;

  @Option(
    name = "google_auth_scopes",
    oldName = "auth_scope",
    defaultValue = "https://www.googleapis.com/auth/cloud-platform",
    converter = CommaSeparatedOptionListConverter.class,
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    help = "A comma-separated list of Google Cloud authentication scopes."
  )
  public List<String> googleAuthScopes;

  @Option(
    name = "google_credentials",
    oldName = "auth_credentials",
    defaultValue = "null",
    documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
    effectTags = {OptionEffectTag.UNKNOWN},
    help =
        "Specifies the file to get authentication credentials from. See "
            + "https://cloud.google.com/docs/authentication for details."
  )
  public String googleCredentials;

  @Option(
      name = "tls_certificate",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "Specify a path to a TLS certificate that is trusted to sign server certificates.")
  public String tlsCertificate;

  @Option(
      name = "tls_client_certificate",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Specify the TLS client certificate to use; you also need to provide a client key to "
              + "enable client authentication.")
  public String tlsClientCertificate;

  @Option(
      name = "tls_client_key",
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Specify the TLS client key to use; you also need to provide a client certificate to "
              + "enable client authentication.")
  public String tlsClientKey;

  @Option(
    name = "tls_authority_override",
    defaultValue = "null",
    metadataTags = {OptionMetadataTag.HIDDEN},
    documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
    effectTags = {OptionEffectTag.UNKNOWN},
    help =
        "TESTING ONLY! Can be used with a self-signed certificate to consider the specified "
            + "value a valid TLS authority."
  )
  public String tlsAuthorityOverride;

  @Option(
      name = "grpc_keepalive_time",
      defaultValue = "null",
      converter = DurationConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Configures keep-alive pings for outgoing gRPC connections. If this is set, then Bazel"
              + " sends pings after this much time of no read operations on the connection, but"
              + " only if there is at least one pending gRPC call. Times are treated as second"
              + " granularity; it is an error to set a value less than one second. By default,"
              + " keep-alive pings are disabled. You should coordinate with the service owner"
              + " before enabling this setting. For example to set a value of 30 seconds to this"
              + " flag, it should be done as this --grpc_keepalive_time=30s")
  public Duration grpcKeepaliveTime;

  @Option(
      name = "grpc_keepalive_timeout",
      defaultValue = "20s",
      converter = DurationConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Configures a keep-alive timeout for outgoing gRPC connections. If keep-alive pings are "
              + "enabled with --grpc_keepalive_time, then Bazel times out a connection if it does "
              + "not receive a ping reply after this much time. Times are treated as second "
              + "granularity; it is an error to set a value less than one second. If keep-alive "
              + "pings are disabled, then this setting is ignored.")
  public Duration grpcKeepaliveTimeout;

  @Option(
      name = "credential_helper",
      oldName = "experimental_credential_helper",
      defaultValue = "null",
      allowMultiple = true,
      converter = CredentialHelperOptionConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Configures a credential helper conforming to the <a"
              + " href=\"https://github.com/EngFlow/credential-helper-spec\">Credential Helper"
              + " Specification</a> to use for retrieving authorization credentials for  repository"
              + " fetching, remote caching and execution, and the build event service.\n\n"
              + "Credentials supplied by a helper take precedence over credentials supplied by"
              + " `--google_default_credentials`, `--google_credentials`, a `.netrc` file, or the"
              + " auth parameter to `repository_ctx.download()` and"
              + " `repository_ctx.download_and_extract()`.\n\n"
              + "May be specified multiple times to set up multiple helpers.\n\n"
              + "See https://blog.engflow.com/2023/10/09/configuring-bazels-credential-helper/ for"
              + " instructions.")
  public List<CredentialHelperOption> credentialHelpers;

  @Option(
      name = "credential_helper_timeout",
      oldName = "experimental_credential_helper_timeout",
      defaultValue = "10s",
      converter = DurationConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "Configures the timeout for a credential helper.\n\n"
              + "Credential helpers failing to respond within this timeout will fail the"
              + " invocation.")
  public Duration credentialHelperTimeout;

  @Option(
      name = "credential_helper_cache_duration",
      oldName = "experimental_credential_helper_cache_duration",
      defaultValue = "30m",
      converter = DurationConverter.class,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "How long to cache credentials for if the credential helper doesn't return an expiration"
              + " time. Changing the value of this flag clears the cache.")
  public Duration credentialHelperCacheTimeout;

  /**
   * One of the values of the `--credential_helper` flag.
   *
   * @param scope Returns the scope of the credential helper (if any).
   *     <p>The scope is a valid ASCII domain name with an optional leading '.*' wildcard.
   * @param path Returns the (unparsed) path of the credential helper.
   */
  public record CredentialHelperOption(Optional<String> scope, String path) {
    public CredentialHelperOption {
      requireNonNull(scope, "scope");
      requireNonNull(path, "path");
    }

  }

  /** A {@link Converter} for the `--credential_helper` flag. */
  public static final class CredentialHelperOptionConverter
      extends Converter.Contextless<CredentialHelperOption> {
    public static final CredentialHelperOptionConverter INSTANCE =
        new CredentialHelperOptionConverter();

    @Override
    public String getTypeDescription() {
      return "Path to a credential helper. It may be absolute, relative to the PATH environment"
          + " variable, or %workspace%-relative. The path be optionally prefixed by a scope "
          + " followed by an '='. The scope is a domain name, optionally with a single leading '*'"
          + " wildcard component. A helper applies to URIs matching its scope, with more specific"
          + " scopes preferred. If a helper has no scope, it applies to every URI.";
    }

    @Override
    public CredentialHelperOption convert(String input) throws OptionsParsingException {
      Preconditions.checkNotNull(input);

      int pos = input.indexOf('=');
      if (pos >= 0) {
        String scope = parseScope(input.substring(0, pos));
        String path = parsePath(input.substring(pos + 1));
        return new CredentialHelperOption(Optional.of(scope), path);
      }

      // `input` does not specify a scope.
      return new CredentialHelperOption(Optional.empty(), parsePath(input));
    }

    private String parseScope(String scope) throws OptionsParsingException {
      if (scope.isEmpty()) {
        throw new OptionsParsingException("Credential helper scope must not be empty");
      }
      String wildcard = "";
      String domainName = scope;
      if (scope.startsWith("*.") && scope.length() > 2) {
        wildcard = "*.";
        domainName = scope.substring(2);
      }
      try {
        // Check that the domain name is either ASCII and conforming to RFC 1122 and RFC 1123,
        // or non-ASCII and conforming to RFC 3490. In the latter case, convert it to Punycode.
        // See https://en.wikipedia.org/wiki/Punycode.
        return wildcard + IDN.toASCII(domainName, IDN.USE_STD3_ASCII_RULES);
      } catch (IllegalArgumentException e) {
        throw new OptionsParsingException(
            "Credential helper scope '"
                + scope
                + "' must be a valid domain name with an optional leading '*.' wildcard",
            e);
      }
    }

    private String parsePath(String path) throws OptionsParsingException {
      if (path.isEmpty()) {
        throw new OptionsParsingException("Credential helper path must not be empty");
      }
      return path;
    }
  }
}
