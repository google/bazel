// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.io.CountingOutputStream;
import com.google.common.net.InetAddresses;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A simple file-based logging handler that provides an API for getting the current log file and
 * (optionally) in addition creates a short symlink to the current log file.
 *
 * <p>The log file path is concatenated from 4 elements: the prefix (a fixed string, typically a
 * directory); the pattern (allowing some % variable substitutions); the timestamp; and the
 * extension.
 *
 * <p>The handler can be configured from the JVM command line: <code>
 *   -Djava.util.logging.config.file=/foo/bar/javalog.properties
 * </code> where the javalog.properties file might contain something like <code>
 *    handlers=com.google.devtools.build.lib.util.SimpleLogHandler
 *    com.google.devtools.build.lib.util.SimpleLogHandler.level=INFO
 *    com.google.devtools.build.lib.util.SimpleLogHandler.prefix=/foo/bar/logs/java.log
 *    com.google.devtools.build.lib.util.SimpleLogHandler.rotate_limit_bytes=1048576
 *    com.google.devtools.build.lib.util.SimpleLogHandler.total_limit_bytes=10485760
 *    com.google.devtools.build.lib.util.SimpleLogHandler.formatter=com.google.devtools.build.lib.util.SingleLineFormatter
 * </code>
 *
 * <p>The handler is thread-safe. IO operations ({@link #publish}, {@link #flush}, {@link #close})
 * and {@link #getCurrentLogFilePath} block other access to the handler until completed.
 */
public final class SimpleLogHandler extends Handler {
  /** Max number of bytes to write before rotating the log. */
  private final int rotateLimitBytes;
  /** Max number of bytes in all logs to keep before deleting oldest ones. */
  private final int totalLimitBytes;
  /** Log file extension; the current process ID by default. */
  private final String extension;
  /** True if the log file extension is not the process ID. */
  private final boolean isStaticExtension;
  /**
   * Absolute path to symbolic link to current log file, or {@code Optional#empty()} if the link
   * should not be created.
   */
  private final Optional<Path> symlinkPath;
  /** Absolute path to common base name of log files. */
  private final Path baseFilePath;
  /** Log file currently in use. */
  @GuardedBy("this")
  private final Output output = new Output();

  private static final String DEFAULT_PREFIX_STRING = "java.log";
  private static final String DEFAULT_BASE_FILE_NAME_PATTERN = ".%h.%u.log.java.";
  /** Source for timestamps in filenames; non-static for testing. */
  private final Clock clock;

  @VisibleForTesting static final String DEFAULT_TIMESTAMP_FORMAT = "yyyyMMdd-HHmmss.";
  /**
   * Timestamp format for log filenames; non-static because {@link SimpleDateFormat} is not
   * thread-safe.
   */
  @GuardedBy("this")
  private final SimpleDateFormat timestampFormat = new SimpleDateFormat(DEFAULT_TIMESTAMP_FORMAT);

  /**
   * A {@link} LogHandlerQuerier for working with {@code SimpleLogHandler} instances.
   *
   * <p>This querier is intended for situations where the logging handler is configured on the JVM
   * command line to be {@link SimpleLogHandler}, but where the code which needs to query the
   * handler does not know the handler's class or cannot import it. The command line then should in
   * addition specify {@code
   * -Dcom.google.devtools.build.lib.util.LogHandlerQuerier.class=com.google.devtools.build.lib.util.SimpleLogHandler$HandlerQuerier}
   * and an instance of {@link SimpleLogHandler.HandlerQuerier} class can then be obtained from
   * {@code LogHandlerQuerier.getInstance()}.
   */
  public static final class HandlerQuerier extends LogHandlerQuerier {
    @Override
    protected boolean canQuery(Handler handler) {
      return handler instanceof SimpleLogHandler;
    }

    @Override
    protected Optional<Path> getLogHandlerFilePath(Handler handler) {
      return ((SimpleLogHandler) handler).getCurrentLogFilePath();
    }
  }

  /** Creates a new {@link Builder}. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder class for {@link SimpleLogHandler}.
   *
   * <p>All setters are optional; if unset, values from the JVM logging configuration or (if those
   * too are unset) reasonable fallback values will be used. See individual setter documentation.
   */
  public static final class Builder {
    private String prefix;
    private String pattern;
    private String extension;
    private String symlinkName;
    private Boolean createSymlink;
    private Integer rotateLimitBytes;
    private Integer totalLimitBytes;
    private Level logLevel;
    private Formatter formatter;
    private Clock clock;

    @CanIgnoreReturnValue
    public Builder setPrefix(String prefix) {
      this.prefix = prefix;
      return this;
    }

    /**
     * Sets the pattern for the log file name. The pattern may contain the following variables:
     *
     * <ul>
     *   <li><code>%u</code> will be expanded to the username
     *   <li><code>%h</code> will be expanded to the hostname
     *   <li><code>%%</code> will be expanded to %
     * </ul>
     *
     * <p>The log file name will be constructed by appending the expanded pattern to the prefix and
     * then by appending a timestamp and the extension.
     *
     * <p>If unset, the value of "pattern" from the JVM logging configuration for {@link
     * SimpleLogHandler} will be used; and if that's unset, {@link #DEFAULT_BASE_FILE_NAME_PATTERN}
     * will be used.
     *
     * @param pattern the pattern string, possibly containing <code>%u</code>, <code>%h</code>,
     *     <code>%%</code> variables as above
     * @return this {@code Builder} object
     */
    @CanIgnoreReturnValue
    public Builder setPattern(String pattern) {
      this.pattern = pattern;
      return this;
    }

    /**
     * Sets the log file extension.
     *
     * <p>If unset, the value of "extension" from the JVM logging configuration for {@link
     * SimpleLogHandler} will be used; and if that's unset, the process ID will be used.
     *
     * @param extension log file extension
     * @return this {@code Builder} object
     */
    @CanIgnoreReturnValue
    public Builder setExtension(String extension) {
      this.extension = extension;
      return this;
    }

    /**
     * Sets the log file symlink filename.
     *
     * <p>If unset, the value of "symlink" from the JVM logging configuration for {@link
     * SimpleLogHandler} will be used; and if that's unset, the prefix will be used.
     *
     * @param symlinkName either symlink filename without a directory part, or an absolute path
     *     whose directory part matches the prefix
     * @return this {@code Builder} object
     */
    @CanIgnoreReturnValue
    public Builder setSymlinkName(String symlinkName) {
      this.symlinkName = symlinkName;
      return this;
    }

    /**
     * Sets whether symlinks to the log file should be created.
     *
     * <p>If unset, the value of "create_symlink" from the JVM logging configuration for {@link
     * SimpleLogHandler} will be used; and if that's unset, the default behavior will depend on the
     * platform: false on Windows (because by default, only administrator accounts can create
     * symbolic links there) and true on other platforms.
     *
     * @return this {@code Builder} object
     */
    @CanIgnoreReturnValue
    public Builder setCreateSymlink(boolean createSymlink) {
      this.createSymlink = createSymlink;
      return this;
    }

    /**
     * Sets the log file size limit; if unset or 0, log size is unlimited.
     *
     * <p>If unset, the value of "rotate_limit_bytes" from the JVM logging configuration for {@link
     * SimpleLogHandler} will be used; and if that's unset, the log fie size is unlimited.
     *
     * @param rotateLimitBytes maximum log file size in bytes; must be >= 0; 0 means unlimited
     * @return this {@code Builder} object
     */
    @CanIgnoreReturnValue
    public Builder setRotateLimitBytes(int rotateLimitBytes) {
      this.rotateLimitBytes = rotateLimitBytes;
      return this;
    }

    /**
     * Sets the total rotateLimitBytes for log files.
     *
     * <p>If set, when opening a new handler or rotating a log file, the handler will scan for all
     * log files with names matching the expected prefix, pattern, timestamp format, and extension,
     * and delete the oldest ones to keep the total size under rotateLimitBytes.
     *
     * <p>If unset, the value of "total_limit_bytes" from the JVM logging configuration for {@link
     * SimpleLogHandler} will be used; and if that's unset, the total log size is unlimited.
     *
     * @param totalLimitBytes maximum total log file size in bytes; must be >= 0; 0 means unlimited
     * @return this {@code Builder} object
     */
    @CanIgnoreReturnValue
    public Builder setTotalLimitBytes(int totalLimitBytes) {
      this.totalLimitBytes = totalLimitBytes;
      return this;
    }

    /**
     * Sets the minimum level at which to log records.
     *
     * <p>If unset, the level named by the "level" field in the JVM logging configuration for {@link
     * SimpleLogHandler} will be used; and if that's unset, all records are logged.
     *
     * @param logLevel minimum log level
     * @return this {@code Builder} object
     */
    @CanIgnoreReturnValue
    public Builder setLogLevel(Level logLevel) {
      this.logLevel = logLevel;
      return this;
    }

    /**
     * Sets the log formatter.
     *
     * <p>If unset, the class named by the "formatter" field in the JVM logging configuration for
     * {@link SimpleLogHandler} will be used; and if that's unset, {@link SingleLineFormatter} will
     * be used.
     *
     * @param formatter log formatter
     * @return this {@code Builder} object
     */
    @CanIgnoreReturnValue
    public Builder setFormatter(Formatter formatter) {
      this.formatter = formatter;
      return this;
    }

    /**
     * Sets the time source for timestamps in log filenames.
     *
     * <p>Intended for testing. If unset, the system clock in the system timezone will be used.
     *
     * @param clock time source for timestamps
     * @return this {@code Builder} object
     */
    @CanIgnoreReturnValue
    @VisibleForTesting
    Builder setClockForTesting(Clock clock) {
      this.clock = clock;
      return this;
    }

    /** Builds a {@link SimpleLogHandler} instance. */
    public SimpleLogHandler build() {
      return new SimpleLogHandler(
          prefix,
          pattern,
          extension,
          symlinkName,
          createSymlink,
          rotateLimitBytes,
          totalLimitBytes,
          logLevel,
          formatter,
          clock);
    }
  }

  /**
   * Constructs a log handler with all state taken from the JVM logging configuration or (as
   * fallback) the defaults; see {@link SimpleLogHandler.Builder} documentation.
   *
   * @throws IllegalArgumentException if invalid JVM logging configuration values are encountered;
   *     see {@link SimpleLogHandler.Builder} documentation
   */
  public SimpleLogHandler() {
    this(null, null, null, null, null, null, null, null, null, null);
  }

  /**
   * Constructs a log handler, falling back to the JVM logging configuration or (as last fallback)
   * the defaults for those arguments which are null; see {@link SimpleLogHandler.Builder}
   * documentation.
   *
   * @throws IllegalArgumentException if invalid non-null arguments or configured values are
   *     encountered; see {@link SimpleLogHandler.Builder} documentation
   */
  private SimpleLogHandler(
      @Nullable String prefix,
      @Nullable String pattern,
      @Nullable String extension,
      @Nullable String symlinkName,
      @Nullable Boolean createSymlink,
      @Nullable Integer rotateLimitBytes,
      @Nullable Integer totalLimit,
      @Nullable Level logLevel,
      @Nullable Formatter formatter,
      @Nullable Clock clock) {
    this.baseFilePath =
        getBaseFilePath(
            getConfiguredStringProperty(prefix, "prefix", DEFAULT_PREFIX_STRING),
            getConfiguredStringProperty(pattern, "pattern", DEFAULT_BASE_FILE_NAME_PATTERN));

    String configuredSymlinkName =
        getConfiguredStringProperty(
            symlinkName,
            "symlink",
            getConfiguredStringProperty(prefix, "prefix", DEFAULT_PREFIX_STRING));
    boolean configuredCreateSymlink =
        getConfiguredBooleanProperty(
            createSymlink, "create_symlink", OS.getCurrent() != OS.WINDOWS);
    this.symlinkPath =
        configuredCreateSymlink
            ? Optional.of(
                getSymlinkAbsolutePath(this.baseFilePath.getParent(), configuredSymlinkName))
            : Optional.empty();
    this.extension =
        getConfiguredStringProperty(
            extension, "extension", Long.toString(ProcessHandle.current().pid()));
    this.isStaticExtension = (getConfiguredStringProperty(extension, "extension", null) != null);
    this.rotateLimitBytes = getConfiguredIntProperty(rotateLimitBytes, "rotate_limit_bytes", 0);
    checkArgument(this.rotateLimitBytes >= 0, "File size limits cannot be negative");
    this.totalLimitBytes = getConfiguredIntProperty(totalLimit, "total_limit_bytes", 0);
    checkArgument(this.totalLimitBytes >= 0, "File size limits cannot be negative");
    setLevel(getConfiguredLevelProperty(logLevel, "level", Level.ALL));
    setFormatter(getConfiguredFormatterProperty(formatter, "formatter", new SingleLineFormatter()));
    if (clock != null) {
      this.clock = clock;
      this.timestampFormat.setTimeZone(TimeZone.getTimeZone(clock.getZone()));
    } else {
      this.clock = Clock.system(ZoneId.systemDefault());
    }
  }

  /**
   * Returns the absolute path of the current log file if a log file is open or {@code
   * Optional#empty()} otherwise.
   *
   * <p>Since the log file is opened lazily, this method is expected to return {@code
   * Optional#empty()} if no record has yet been published.
   */
  public synchronized Optional<Path> getCurrentLogFilePath() {
    return output.isOpen() ? Optional.of(output.getPath()) : Optional.empty();
  }

  /**
   * Returns the expected absolute path for the symbolic link to the current log file, or {@code
   * Optional#empty()} if not used.
   */
  public Optional<Path> getSymbolicLinkPath() {
    return symlinkPath;
  }

  @Override
  public boolean isLoggable(LogRecord record) {
    return record != null && super.isLoggable(record);
  }

  @Override
  public synchronized void publish(LogRecord record) {
    if (!isLoggable(record)) {
      // Silently ignore null or filtered records, matching FileHandler behavior.
      return;
    }

    // This allows us to do the I/O while not forgetting that we were interrupted.
    boolean isInterrupted = Thread.interrupted();
    try {
      String message = getFormatter().format(record);
      openOutputIfNeeded();
      output.write(message);
    } catch (Exception e) {
      reportError(null, e, ErrorManager.WRITE_FAILURE);
      // Failing to log is non-fatal. Continue to try to rotate the log if necessary, which may fix
      // the underlying IO problem with the file.
      if (e instanceof InterruptedIOException) {
        isInterrupted = true;
      }
    }

    try {
      if (rotateLimitBytes > 0) {
        output.closeIfByteCountAtleast(rotateLimitBytes);
        openOutputIfNeeded();
      }
    } catch (IOException e) {
      reportError("Failed to rotate log file", e, ErrorManager.GENERIC_FAILURE);
      if (e instanceof InterruptedIOException) {
        isInterrupted = true;
      }
    }
    if (isInterrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public synchronized void flush() {
    boolean isInterrupted = Thread.interrupted();
    if (output.isOpen()) {
      try {
        output.flush();
      } catch (IOException e) {
        reportError(null, e, ErrorManager.FLUSH_FAILURE);
        if (e instanceof InterruptedIOException) {
          isInterrupted = true;
        }
      }
    }
    if (isInterrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public synchronized void close() {
    boolean isInterrupted = Thread.interrupted();
    if (output.isOpen()) {
      try {
        output.write(getFormatter().getTail(this));
      } catch (IOException e) {
        reportError("Failed to write log tail", e, ErrorManager.WRITE_FAILURE);
        if (e instanceof InterruptedIOException) {
          isInterrupted = true;
        }
      }

      try {
        output.close();
      } catch (IOException e) {
        reportError(null, e, ErrorManager.CLOSE_FAILURE);
        if (e instanceof InterruptedIOException) {
          isInterrupted = true;
        }
      }
    }
    if (isInterrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Checks if a value is null, and if it is, falls back to the JVM logging configuration, and if
   * that too is missing, to a provided fallback value.
   *
   * @param builderValue possibly null value provided by the caller, e.g. from {@link
   *     SimpleLogHandler.Builder}
   * @param configuredName field name in the JVM logging configuration for {@link SimpleLogHandler}
   * @param parse parser for the string value from the JVM logging configuration
   * @param fallbackValue fallback to use if the {@code builderValue} is null and no value is
   *     configured in the JVM logging configuration
   * @param <T> value type
   */
  @Nullable
  private static <T> T getConfiguredProperty(
      @Nullable T builderValue,
      String configuredName,
      Function<String, T> parse,
      @Nullable T fallbackValue) {
    if (builderValue != null) {
      return builderValue;
    }

    // .properties files are read as Latin-1 by java.util.Properties, with Unicode escape sequences
    // interpreted. Since the Bazel client passes path properties as UTF-8 without escaping,
    // configuredValue already contains a string in Bazel's internal string encoding (see
    // StringEncoding).
    String configuredValue =
        LogManager.getLogManager()
            .getProperty(SimpleLogHandler.class.getName() + "." + configuredName);
    if (configuredValue != null) {
      return parse.apply(configuredValue);
    }
    return fallbackValue;
  }

  /** Matches java.logging.* configuration behavior; configured strings are trimmed. */
  private static String getConfiguredStringProperty(
      String builderValue, String configuredName, String fallbackValue) {
    return getConfiguredProperty(builderValue, configuredName, String::trim, fallbackValue);
  }

  /**
   * Matches java.logging.* configuration behavior; "true" and "1" are true, "false" and "0" are
   * false.
   *
   * @throws IllegalArgumentException if the configured boolean property cannot be parsed
   */
  private static boolean getConfiguredBooleanProperty(
      Boolean builderValue, String configuredName, boolean fallbackValue) {
    Boolean value =
        getConfiguredProperty(
            builderValue,
            configuredName,
            val -> {
              val = val.trim().toLowerCase();
              if ("true".equals(val) || "1".equals(val)) {
                return true;
              } else if ("false".equals(val) || "0".equals(val)) {
                return false;
              } else if (val.isEmpty()) {
                return null;
              }
              throw new IllegalArgumentException("Cannot parse boolean property value");
            },
            null);
    return value != null ? value : fallbackValue;
  }

  /**
   * Empty configured values are ignored and the fallback is used instead.
   *
   * @throws NumberFormatException if the configured formatter value is non-numeric
   */
  private static int getConfiguredIntProperty(
      Integer builderValue, String configuredName, int fallbackValue) {
    Integer value =
        getConfiguredProperty(
            builderValue,
            configuredName,
            val -> {
              val = val.trim();
              return !val.isEmpty() ? Integer.parseInt(val) : null;
            },
            null);
    return value != null ? value : fallbackValue;
  }

  /**
   * Empty configured values are ignored and the fallback is used instead.
   *
   * @throws IllegalArgumentException if the configured level name cannot be parsed
   */
  private static Level getConfiguredLevelProperty(
      Level builderValue, String configuredName, Level fallbackValue) {
    Level value =
        getConfiguredProperty(
            builderValue,
            configuredName,
            val -> {
              val = val.trim();
              return !val.isEmpty() ? Level.parse(val) : null;
            },
            null);
    return value != null ? value : fallbackValue;
  }

  /**
   * Empty configured values are ignored and the fallback is used instead.
   *
   * @throws IllegalArgumentException if a formatter object cannot be instantiated from the
   *     configured class name
   */
  private static Formatter getConfiguredFormatterProperty(
      Formatter builderValue, String configuredName, Formatter fallbackValue) {
    return getConfiguredProperty(
        builderValue,
        configuredName,
        val -> {
          val = val.trim();
          if (!val.isEmpty()) {
            try {
              return (Formatter)
                  ClassLoader.getSystemClassLoader()
                      .loadClass(val)
                      .getDeclaredConstructor()
                      .newInstance();
            } catch (ReflectiveOperationException e) {
              throw new IllegalArgumentException(e);
            }
          } else {
            return fallbackValue;
          }
        },
        fallbackValue);
  }

  @VisibleForTesting
  static String getLocalHostnameFirstComponent() {
    String name = NetUtil.getCachedShortHostName();
    if (!InetAddresses.isInetAddress(name)) {
      // Keep only the first component of the name.
      int firstDot = name.indexOf('.');
      if (firstDot >= 0) {
        name = name.substring(0, firstDot);
      }
    }
    return name.toLowerCase();
  }

  /**
   * Creates the log file absolute base path according to the given pattern.
   *
   * @param prefix non-null string to prepend to the base path
   * @param pattern non-null string which may include the following variables: %h will be expanded
   *     to the hostname; %u will be expanded to the username; %% will be expanded to %
   * @throws IllegalArgumentException if an unknown variable is encountered in the pattern
   */
  private static Path getBaseFilePath(String prefix, String pattern) {
    checkNotNull(prefix, "prefix");
    checkNotNull(pattern, "pattern");

    StringBuilder sb = new StringBuilder(100); // Typical name is < 100 bytes
    boolean inVar = false;
    String username = StringEncoding.platformToInternal(System.getProperty("user.name"));

    if (Strings.isNullOrEmpty(username)) {
      username = "unknown_user";
    }

    sb.append(prefix);

    for (int i = 0; i < pattern.length(); ++i) {
      char c = pattern.charAt(i);
      if (inVar) {
        inVar = false;
        switch (c) {
          case '%':
            sb.append('%');
            break;
          case 'h':
            sb.append(getLocalHostnameFirstComponent());
            break;
          case 'u':
            sb.append(username);
            break;
          default:
            throw new IllegalArgumentException("Unknown variable " + c + " in " + pattern);
        }
      } else {
        if (c == '%') {
          inVar = true;
        } else {
          sb.append(c);
        }
      }
    }

    return Path.of(StringEncoding.internalToPlatform(sb.toString())).toAbsolutePath();
  }

  /**
   * Returns the absolute path for a symlink in the specified directory.
   *
   * @throws IllegalArgumentException if the symlink includes a directory component which doesn't
   *     equal {@code logDir}
   */
  private static Path getSymlinkAbsolutePath(Path logDir, String symlink) {
    checkNotNull(symlink);
    checkArgument(!symlink.isEmpty());
    Path symlinkPath = Path.of(StringEncoding.internalToPlatform(symlink));
    if (!symlinkPath.isAbsolute()) {
      symlinkPath = logDir.resolve(symlinkPath);
    }
    checkArgument(
        symlinkPath.getParent().equals(logDir), "symlink is not a top-level file in logDir");
    return symlinkPath;
  }

  private static final class Output {
    /** Log file currently in use. */
    @Nullable private Path file;

    /** Output stream for {@link #file} which counts the number of bytes written. */
    @Nullable private CountingOutputStream stream;
    /** Writer for {@link #stream}. */
    @Nullable private OutputStreamWriter writer;

    public boolean isOpen() {
      return writer != null;
    }

    /**
     * Opens the specified file in append mode, first closing the current file if needed.
     *
     * @throws IOException if the file could not be opened
     */
    public void open(Path file) throws IOException {
      try {
        close();
        this.file = file;
        stream = new CountingOutputStream(new FileOutputStream(file.toFile(), true));
        writer = new OutputStreamWriter(stream, ISO_8859_1);
      } catch (IOException e) {
        close();
        throw e;
      }
    }

    /**
     * Returns the currently open file's path.
     *
     * @throws NullPointerException if not open
     */
    public Path getPath() {
      return file;
    }

    /**
     * Writes the string to the current file in UTF-8 encoding.
     *
     * @throws NullPointerException if not open
     * @throws IOException if an underlying IO operation failed
     */
    public void write(String string) throws IOException {
      writer.write(string);
    }

    /**
     * Flushes the current file.
     *
     * @throws NullPointerException if not open
     * @throws IOException if an underlying IO operation failed
     */
    public void flush() throws IOException {
      writer.flush();
    }

    /**
     * Closes the current file if it is open.
     *
     * @throws IOException if an underlying IO operation failed
     */
    public void close() throws IOException {
      try {
        if (isOpen()) {
          writer.close();
        }
      } finally {
        writer = null;
        stream = null;
        file = null;
      }
    }

    /**
     * Closes the current file unless the number of bytes written to it was under the specified
     * limit.
     *
     * @throws NullPointerException if not open
     * @throws IOException if an underlying IO operation failed
     */
    void closeIfByteCountAtleast(int limit) throws IOException {
      if (stream.getCount() < limit && stream.getCount() + 8192L >= limit) {
        // The writer and its internal encoder buffer output before writing to the output stream.
        // The default size of the encoder's buffer is 8192 bytes. To count the bytes in the output
        // stream accurately, we have to flush. But flushing unnecessarily harms performance; let's
        // flush only when it matters - per record and within expected buffer size from the limit.
        flush();
      }
      if (stream.getCount() >= limit) {
        close();
      }
    }
  }

  /**
   * Opens a new log file if one is not open, updating the symbolic link and deleting old logs if
   * needed.
   */
  @GuardedBy("this")
  private void openOutputIfNeeded() {
    if (!output.isOpen()) {
      // Ensure the log file's directory exists.
      checkState(baseFilePath.isAbsolute());
      baseFilePath.getParent().toFile().mkdirs();

      try {
        output.open(
            Path.of(
                baseFilePath + timestampFormat.format(Date.from(Instant.now(clock))) + extension));
        output.write(getFormatter().getHead(this));
      } catch (IOException e) {
        try {
          output.close();
        } catch (IOException eClose) {
          // Already handling a prior IO failure.
        }
        reportError("Failed to open log file", e, ErrorManager.OPEN_FAILURE);
        return;
      }

      if (totalLimitBytes > 0) {
        deleteOldLogs();
      }

      // Try to create relative symlink from currentLogFile to baseFile, but don't treat a failure
      // as fatal.
      if (symlinkPath.isPresent()) {
        try {
          checkState(symlinkPath.get().getParent().equals(output.getPath().getParent()));
          if (Files.exists(symlinkPath.get(), LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(symlinkPath.get());
          }
          Files.createSymbolicLink(symlinkPath.get(), output.getPath().getFileName());
        } catch (IOException e) {
          reportError(
              "Failed to create symbolic link to log file", e, ErrorManager.GENERIC_FAILURE);
        }
      }
    }
  }

  /**
   * Parses the absolute path of a logfile (e.g from a previous run of the program) and extracts the
   * timestamp.
   *
   * @throws ParseException if the path does not match the expected prefix, resolved pattern,
   *     timestamp format, or extension
   */
  @GuardedBy("this")
  private Date parseLogFileTimestamp(Path path) throws ParseException {
    String pathString = path.toString();
    if (!pathString.startsWith(baseFilePath.toString())) {
      throw new ParseException("Wrong prefix or pattern", 0);
    }
    ParsePosition parsePosition = new ParsePosition(baseFilePath.toString().length());
    Date timestamp = timestampFormat.parse(pathString, parsePosition);
    if (timestamp == null) {
      throw new ParseException("Wrong timestamp format", parsePosition.getErrorIndex());
    }
    if (isStaticExtension) {
      if (!pathString.substring(parsePosition.getIndex()).equals(extension)) {
        throw new ParseException("Wrong file extension", parsePosition.getIndex());
      }
    } else {
      try {
        Long.parseLong(pathString.substring(parsePosition.getIndex()));
      } catch (NumberFormatException e) {
        throw new ParseException("File extension is not a numeric PID", parsePosition.getIndex());
      }
    }
    return timestamp;
  }

  /** File path ordered by timestamp. */
  private static final class PathByTimestamp implements Comparable<PathByTimestamp> {
    private final Path path;
    private final Date timestamp;
    private final long size;

    PathByTimestamp(Path path, Date timestamp, long size) {
      this.path = path;
      this.timestamp = timestamp;
      this.size = size;
    }

    Path getPath() {
      return path;
    }

    long getSize() {
      return size;
    }

    @Override
    public int compareTo(PathByTimestamp rhs) {
      return this.timestamp.compareTo(rhs.timestamp);
    }
  }

  /**
   * Deletes the oldest log files matching the expected prefix, pattern, timestamp format, and
   * extension, to keep the total size under {@link #totalLimitBytes} (if set to non-0).
   *
   * <p>Each log file's timestamp is determined only from the filename. The current log file will
   * not be deleted.
   */
  @GuardedBy("this")
  private void deleteOldLogs() {
    checkState(baseFilePath.isAbsolute());
    PriorityQueue<PathByTimestamp> queue = new PriorityQueue<>();
    long totalSize = 0;
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(baseFilePath.getParent())) {
      for (Path path : dirStream) {
        try {
          Date timestamp = parseLogFileTimestamp(path);
          long size = Files.size(path);
          totalSize += size;
          if (!output.getPath().equals(path)) {
            queue.add(new PathByTimestamp(path, timestamp, size));
          }
        } catch (ParseException e) {
          // Ignore files which don't look like our logs.
        }
      }

      if (totalLimitBytes > 0) {
        while (totalSize > totalLimitBytes && !queue.isEmpty()) {
          PathByTimestamp entry = queue.poll();
          Files.delete(entry.getPath());
          totalSize -= entry.getSize();
        }
      }
    } catch (IOException e) {
      reportError("Failed to clean up old log files", e, ErrorManager.GENERIC_FAILURE);
    }
  }
}
