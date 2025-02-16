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
package com.google.devtools.build.lib.bazel.repository.downloader;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.auth.Credentials;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.authandtls.Netrc;
import com.google.devtools.build.lib.authandtls.NetrcCredentials;
import com.google.devtools.build.lib.authandtls.NetrcParser;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * Helper class for taking URLs and converting them according to an optional config specified by
 * {@link com.google.devtools.build.lib.bazel.repository.RepositoryOptions#downloaderConfig}.
 *
 * <p>The primary reason for doing this is to allow a bazel user to redirect particular URLs to
 * (eg.) local mirrors without needing to rewrite third party rulesets.
 */
public class UrlRewriter {

  private static final ImmutableSet<String> REWRITABLE_SCHEMES = ImmutableSet.of("http", "https");

  private final UrlRewriterConfig config;
  private final Function<URL, List<RewrittenURL>> rewriter;

  @VisibleForTesting
  UrlRewriter(Consumer<String> log, String filePathForErrorReporting, Reader reader)
      throws UrlRewriterParseException {
    Preconditions.checkNotNull(reader, "UrlRewriterConfig source must be set");
    this.config = new UrlRewriterConfig(filePathForErrorReporting, reader);

    this.rewriter = this::rewrite;
  }

  /**
   * Obtain a new {@code UrlRewriter} configured with the specified config file.
   *
   * @param configPath Path to the config file to use. May be null.
   * @param reporter Used for logging when URLs are rewritten.
   */
  public static UrlRewriter getDownloaderUrlRewriter(
      Path workspaceRoot, @Nullable PathFragment configPath, Reporter reporter)
      throws UrlRewriterParseException {
    Consumer<String> log = str -> reporter.handle(Event.info(str));

    // "empty" UrlRewriter shouldn't alter auth headers
    if (configPath == null || configPath.isEmpty()) {
      return new UrlRewriter(log, "", new StringReader(""));
    }

    // There have been reports (eg. https://github.com/bazelbuild/bazel/issues/22104) that
    // there are occasional errors when `configFile` can't be found, and when this happens
    // investigation suggests that the current working directory isn't the workspace root.
    Path actualConfigPath = workspaceRoot.getRelative(configPath);

    if (!actualConfigPath.exists()) {
      throw new UrlRewriterParseException(
          String.format("Unable to find downloader config file %s", configPath.getPathString()));
    }

    try (InputStream inputStream = actualConfigPath.getInputStream();
        Reader inputStreamReader = new InputStreamReader(inputStream);
        Reader reader = new BufferedReader(inputStreamReader)) {
      return new UrlRewriter(log, configPath.getPathString(), reader);
    } catch (IOException e) {
      throw new UrlRewriterParseException(e.getMessage());
    }
  }

  /**
   * Rewrites {@code urls} using the configuration provided to {@link #getDownloaderUrlRewriter}.
   * The returned list of URLs may be empty if the configuration used blocks all the input URLs.
   *
   * @param urls The input list of {@link URL}s. May be empty.
   * @return The amended lists of URLs.
   */
  public ImmutableList<RewrittenURL> amend(List<URL> urls) {
    Objects.requireNonNull(urls, "URLS to check must be set but may be empty");

    return urls.stream().map(rewriter).flatMap(Collection::stream).collect(toImmutableList());
  }

  /**
   * Updates {@code authHeaders} using the userInfo available in the provided {@code urls}. Note
   * that if the same url is present in both {@code authHeaders} and <b>download config</b> then it
   * will be overridden with the value from <b>download config</b>.
   *
   * @param urls The input list of {@link URL}s. May be empty.
   * @param authHeaders A map of the URLs and their corresponding auth tokens.
   * @return A map of the updated authentication headers.
   */
  public Map<URI, Map<String, List<String>>> updateAuthHeaders(
      List<RewrittenURL> urls,
      Map<URI, Map<String, List<String>>> authHeaders,
      Credentials netrcCreds) {
    Map<URI, Map<String, List<String>>> updatedAuthHeaders = new HashMap<>(authHeaders);

    for (RewrittenURL url : urls) {
      // if URL was not re-written by UrlRewriter in first place, we should not attach auth headers
      // to it
      if (!url.rewritten()) {
        continue;
      }

      String userInfo = url.url().getUserInfo();
      try {
        // This is the core change - if auth was set in download, but the url got rewritten. Pass auth from original url
        // to rewritten one 
        if (authHeaders.containsKey(url.original().toURI())) {
          updatedAuthHeaders.put(url.url().toURI(), authHeaders.get(url.original().toURI()));
        } else if (userInfo != null) {
            String token =
                "Basic " + Base64.getEncoder().encodeToString(userInfo.getBytes(ISO_8859_1));
            updatedAuthHeaders.put(
                url.url().toURI(), ImmutableMap.of("Authorization", ImmutableList.of(token)));
            // If the credentials extraction failed, we're letting bazel try without credentials.
        } else if (netrcCreds != null) {
            Map<String, List<String>> urlAuthHeaders =
                netrcCreds.getRequestMetadata(url.url().toURI());
            if (urlAuthHeaders == null || urlAuthHeaders.isEmpty()) {
              continue;
            }
            // there could be multiple Auth headers, take the first one
            Map.Entry<String, List<String>> firstAuthHeader =
                urlAuthHeaders.entrySet().stream().findFirst().get();
            if (firstAuthHeader.getValue() != null && !firstAuthHeader.getValue().isEmpty()) {
              updatedAuthHeaders.put(
                  url.url().toURI(),
                  ImmutableMap.of(
                      firstAuthHeader.getKey(), ImmutableList.of(firstAuthHeader.getValue().get(0))));
            }
        }
      } catch (URISyntaxException | IOException e) {
          // If the credentials extraction failed, we're letting bazel try without credentials.
      }
    }

    return ImmutableMap.copyOf(updatedAuthHeaders);
  }

  private ImmutableList<RewrittenURL> rewrite(URL url) {
    Preconditions.checkNotNull(url);

    // Cowardly refuse to rewrite non-HTTP(S) urls
    if (REWRITABLE_SCHEMES.stream()
        .noneMatch(scheme -> Ascii.equalsIgnoreCase(scheme, url.getProtocol()))) {
      return ImmutableList.of(RewrittenURL.create(url, url, false));
    }

    ImmutableList<RewrittenURL> rewrittenUrls = applyRewriteRules(url);

    ImmutableList.Builder<RewrittenURL> toReturn = ImmutableList.builder();
    // Now iterate over the URLs
    for (RewrittenURL consider : rewrittenUrls) {
      // If there's an allow entry, add it to the set to return and continue
      if (isAllowMatched(consider.url())) {
        toReturn.add(consider);
        continue;
      }

      // If there's no block that matches the domain, add it to the set to return and continue
      if (!isBlockMatched(consider.url())) {
        toReturn.add(consider);
      }
    }

    return toReturn.build();
  }

  private boolean isAllowMatched(URL url) {
    for (String host : config.getAllowList()) {
      if (isMatchingHostName(url, host)) {
        return true;
      }
    }
    return false;
  }

  private boolean isBlockMatched(URL url) {
    for (String host : config.getBlockList()) {
      // Allow a wild-card block
      if ("*".equals(host)) {
        return true;
      }

      if (isMatchingHostName(url, host)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isMatchingHostName(URL url, String host) {
    return host.equals(url.getHost()) || url.getHost().endsWith("." + host);
  }

  private ImmutableList<RewrittenURL> applyRewriteRules(URL url) {
    String withoutScheme = url.toString().substring(url.getProtocol().length() + 3);

    ImmutableSet.Builder<String> rewrittenUrls = ImmutableSet.builder();

    boolean matchMade = false;
    for (Map.Entry<Pattern, Collection<String>> entry : config.getRewrites().entrySet()) {
      Matcher matcher = entry.getKey().matcher(withoutScheme);
      if (matcher.matches()) {
        matchMade = true;

        for (String replacement : entry.getValue()) {
          rewrittenUrls.add(matcher.replaceFirst(replacement));
        }
      }
    }

    if (!matchMade) {
      return ImmutableList.of(RewrittenURL.create(url, url, false));
    }

    return rewrittenUrls.build().stream()
        .map(urlString -> prefixWithProtocol(urlString, url.getProtocol()))
        .map(plainUrl -> RewrittenURL.create(url, plainUrl, true))
        .collect(toImmutableList());
  }

  /** Prefixes url with protocol if not already prefixed by {@link #REWRITABLE_SCHEMES} */
  private static URL prefixWithProtocol(String url, String protocol) {
    try {
      for (String schemaPrefix : REWRITABLE_SCHEMES) {
        if (url.startsWith(schemaPrefix + "://")) {
          return new URL(url);
        }
      }
      return new URL(protocol + "://" + url);
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Create a new {@link Credentials} object by parsing the .netrc file with following order to
   * search it:
   *
   * <ol>
   *   <li>If environment variable $NETRC exists, use it as the path to the .netrc file
   *   <li>Fallback to $HOME/.netrc or $USERPROFILE/.netrc
   * </ol>
   *
   * @return the {@link Credentials} object or {@code null} if there is no .netrc file.
   * @throws UrlRewriterParseException in case the credentials can't be constructed.
   */
  // TODO : consider re-using RemoteModule.newCredentialsFromNetrc
  @Nullable
  public static Credentials newCredentialsFromNetrc(
      Map<String, String> clientEnv, Path workingDirectory) throws UrlRewriterParseException {
    final Optional<String> homeDir;
    if (OS.getCurrent() == OS.WINDOWS) {
      homeDir = Optional.ofNullable(clientEnv.get("USERPROFILE"));
    } else {
      homeDir = Optional.ofNullable(clientEnv.get("HOME"));
    }
    String netrcFileString =
        Optional.ofNullable(clientEnv.get("NETRC"))
            .orElseGet(() -> homeDir.map(home -> home + "/.netrc").orElse(null));
    if (netrcFileString == null) {
      return null;
    }
    Location location = Location.fromFileLineColumn(netrcFileString, 0, 0);
    // In case Bazel is not started from a valid workspace.
    if (workingDirectory == null) {
      return null;
    }
    // Using the getRelative() method ensures:
    //  - If netrcFileString is an absolute path, use as it is.
    //  - If netrcFileString is a relative path, it's resolved to an absolute path with the current
    //    working directory.
    Path netrcFile = workingDirectory.getRelative(netrcFileString);
    if (netrcFile.exists()) {
      try {
        Netrc netrc = NetrcParser.parseAndClose(netrcFile.getInputStream());
        return new NetrcCredentials(netrc);
      } catch (IOException e) {
        throw new UrlRewriterParseException(
            "Failed to parse " + netrcFile.getPathString() + ": " + e.getMessage(), location);
      }
    } else {
      return null;
    }
  }

  @Nullable
  public String getAllBlockedMessage() {
    return config.getAllBlockedMessage();
  }

  /** Holds the URL along with meta-info, such as whether URL was re-written or not. */
  @AutoValue
  public abstract static class RewrittenURL {
    static RewrittenURL create(URL original, URL url, boolean rewritten) {
      return new AutoValue_UrlRewriter_RewrittenURL(original, url, rewritten);
    }

    abstract URL original();

    abstract URL url();

    abstract boolean rewritten();
  }
}
