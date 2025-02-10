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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcToolchainVariablesApi;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkValue;

/**
 * Configured build variables usable by the toolchain configuration.
 *
 * <p>TODO(b/32655571): Investigate cleanup once implicit iteration is not needed. Variables
 * instance could serve as a top level View used to expand all flag_groups.
 */
@Immutable
public abstract class CcToolchainVariables implements CcToolchainVariablesApi {
  /**
   * A piece of a single string value.
   *
   * <p>A single value can contain a combination of text and variables (for example "-f
   * %{var1}/%{var2}"). We split the string into chunks, where each chunk represents either a text
   * snippet, or a variable that is to be replaced.
   */
  interface StringChunk {
    /**
     * Expands this chunk.
     *
     * @param variables binding of variable names to their values for a single flag expansion.
     */
    String expand(CcToolchainVariables variables, PathMapper pathMapper) throws ExpansionException;

    String getString();
  }

  /** A plain text chunk of a string (containing no variables). */
  @Immutable
  private static final class StringLiteralChunk implements StringChunk {
    private final String text;

    StringLiteralChunk(String text) {
      this.text = text;
    }

    @Override
    public String expand(CcToolchainVariables variables, PathMapper pathMapper) {
      return text;
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof StringLiteralChunk that) {
        return text.equals(that.text);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return 31 + text.hashCode();
    }

    @Override
    public String getString() {
      return text;
    }
  }

  /** A chunk of a string value into which a variable should be expanded. */
  @Immutable
  private static final class VariableChunk implements StringChunk {
    private final String variableName;

    VariableChunk(String variableName) {
      this.variableName = variableName;
    }

    @Override
    public String expand(CcToolchainVariables variables, PathMapper pathMapper)
        throws ExpansionException {
      // We check all variables in FlagGroup.expandCommandLine.
      // If we arrive here with the variable not being available, the variable was provided, but
      // the nesting level of the NestedSequence was deeper than the nesting level of the flag
      // groups.
      return variables.getStringVariable(variableName, pathMapper);
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (this == object) {
        return true;
      }
      if (object instanceof VariableChunk that) {
        return variableName.equals(that.variableName);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(variableName);
    }

    @Override
    public String getString() {
      return "%{" + variableName + "}";
    }
  }

  /**
   * Parser for toolchain string values.
   *
   * <p>A string value contains a snippet of text supporting variable expansion. For example, a
   * string value "-f %{var1}/%{var2}" will expand the values of the variables "var1" and "var2" in
   * the corresponding places in the string.
   *
   * <p>The {@code StringValueParser} takes a string and parses it into a list of {@link
   * StringChunk} objects, where each chunk represents either a snippet of text or a variable to be
   * expanded. In the above example, the resulting chunks would be ["-f ", var1, "/", var2].
   *
   * <p>To get a literal percent character, "%%" can be used in the string.
   */
  public static class StringValueParser {

    private final String value;

    /**
     * The current position in {@value} during parsing.
     */
    private int current = 0;

    private final ImmutableList.Builder<StringChunk> chunks = ImmutableList.builder();
    private final ImmutableSet.Builder<String> usedVariables = ImmutableSet.builder();

    public StringValueParser(String value) throws EvalException {
      this.value = value;
      parse();
    }

    /** Returns the parsed chunks for this string. */
    public ImmutableList<StringChunk> getChunks() {
      return chunks.build();
    }

    /**
     * Parses the string.
     *
     * @throws EvalException if there is a parsing error.
     */
    private void parse() throws EvalException {
      while (current < value.length()) {
        if (atVariableStart()) {
          parseVariableChunk();
        } else {
          parseStringChunk();
        }
      }
    }

    /**
     * @return whether the current position is the start of a variable.
     */
    private boolean atVariableStart() {
      // We parse a variable when value starts with '%', but not '%%'.
      return value.charAt(current) == '%'
          && (current + 1 >= value.length() || value.charAt(current + 1) != '%');
    }

    /**
     * Parses a chunk of text until the next '%', which indicates either an escaped literal '%' or a
     * variable.
     */
    private void parseStringChunk() {
      int start = current;
      // We only parse string chunks starting with '%' if they also start with '%%'.
      // In that case, we want to have a single '%' in the string, so we start at the second
      // character.
      // Note that for strings like "abc%%def" this will lead to two string chunks, the first
      // referencing the subtring "abc", and a second referencing the substring "%def".
      if (value.charAt(current) == '%') {
        current = current + 1;
        start = current;
      }
      current = value.indexOf('%', current + 1);
      if (current == -1) {
        current = value.length();
      }
      String text = value.substring(start, current);
      chunks.add(new StringLiteralChunk(text));
    }

    /**
     * Parses a variable to be expanded.
     *
     * @throws EvalException if there is a parsing error.
     */
    private void parseVariableChunk() throws EvalException {
      current = current + 1;
      if (current >= value.length() || value.charAt(current) != '{') {
        abort("expected '{'");
      }
      current = current + 1;
      if (current >= value.length() || value.charAt(current) == '}') {
        abort("expected variable name");
      }
      int end = value.indexOf('}', current);
      final String name = value.substring(current, end);
      usedVariables.add(name);
      chunks.add(new VariableChunk(name));
      current = end + 1;
    }

    /**
     * @throws EvalException with the given error text, adding information about the current
     *     position in the string.
     */
    private void abort(String error) throws EvalException {
      throw Starlark.errorf(
          "Invalid toolchain configuration: %s at position %s while parsing a flag containing '%s'",
          error, current, value);
    }
  }

  /** A flag or flag group that can be expanded under a set of variables. */
  public interface Expandable {
    /**
     * Expands the current expandable under the given {@code view}, adding new flags to {@code
     * commandLine}.
     *
     * <p>The {@code variables} controls which variables are visible during the expansion and allows
     * to recursively expand nested flag groups.
     */
    void expand(
        CcToolchainVariables variables,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        List<String> commandLine)
        throws ExpansionException;
  }

  /** An empty variables instance. */
  public static final CcToolchainVariables EMPTY = builder().build();

  private static final Object NULL_MARKER = new Object();

  // Values in this cache are either VariableValue, String error message, or NULL_MARKER.
  //
  // It is initialized lazily.
  private transient volatile Map<String, Object> structuredVariableCache;

  /**
   * Retrieves a {@link StringSequence} variable named {@code variableName} from {@code variables}
   * and converts it into a list of plain strings.
   *
   * <p>Throws {@link ExpansionException} when the variable is not a {@link StringSequence}.
   */
  public static ImmutableList<String> toStringList(
      CcToolchainVariables variables, String variableName, PathMapper pathMapper)
      throws ExpansionException {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    for (VariableValue value : variables.getSequenceVariable(variableName, pathMapper)) {
      result.add(value.getStringValue(variableName, pathMapper));
    }
    return result.build();
  }

  /**
   * Gets a variable value named {@code name}. Supports accessing fields in structures (e.g.
   * 'libraries_to_link.interface_libraries')
   *
   * @throws ExpansionException when no such variable or no such field are present, or when
   *     accessing a field of non-structured variable
   */
  VariableValue getVariable(String name, PathMapper pathMapper) throws ExpansionException {
    return lookupVariable(
        name, /* throwOnMissingVariable= */ true, /* expander= */ null, pathMapper);
  }

  private VariableValue getVariable(
      String name, @Nullable ArtifactExpander expander, PathMapper pathMapper)
      throws ExpansionException {
    return lookupVariable(name, /* throwOnMissingVariable= */ true, expander, pathMapper);
  }

  /**
   * Looks up a variable named {@code name} or return a reason why the variable was not found.
   * Supports accessing fields in structures.
   */
  @Nullable
  private VariableValue lookupVariable(
      String name,
      boolean throwOnMissingVariable,
      @Nullable ArtifactExpander expander,
      PathMapper pathMapper)
      throws ExpansionException {
    VariableValue var = getNonStructuredVariable(name);
    if (var != null) {
      return var;
    }

    if (!name.contains(".")) {
      if (throwOnMissingVariable) {
        throw new ExpansionException(
            String.format(
                "Invalid toolchain configuration: Cannot find variable named '%s'.", name));
      }
      return null;
    }

    if (structuredVariableCache == null) {
      synchronized (this) {
        if (structuredVariableCache == null) {
          structuredVariableCache = Maps.newConcurrentMap();
        }
      }
    }

    Object variableOrError = structuredVariableCache.get(name);
    if (variableOrError == null) {
      try {
        VariableValue variable =
            getStructureVariable(name, throwOnMissingVariable, expander, pathMapper);
        variableOrError = variable != null ? variable : NULL_MARKER;
      } catch (ExpansionException e) {
        if (throwOnMissingVariable) {
          variableOrError = e.getMessage();
        } else {
          throw new IllegalStateException(
              "Should not happen - call to getStructuredVariable threw when asked not to.", e);
        }
      }
      structuredVariableCache.putIfAbsent(name, variableOrError);
    }

    if (variableOrError instanceof VariableValue variableValue) {
      return variableValue;
    }
    if (throwOnMissingVariable) {
      throw new ExpansionException(
          variableOrError instanceof String string
              ? string
              : String.format(
                  "Invalid toolchain configuration: Cannot find variable named '%s'.", name));
    }
    return null;
  }

  @Nullable
  private VariableValue getStructureVariable(
      String name,
      boolean throwOnMissingVariable,
      @Nullable ArtifactExpander expander,
      PathMapper pathMapper)
      throws ExpansionException {
    if (!name.contains(".")) {
      return null;
    }

    Stack<String> fieldsToAccess = new Stack<>();
    String structPath = name;
    VariableValue variable;

    do {
      fieldsToAccess.push(structPath.substring(structPath.lastIndexOf('.') + 1));
      structPath = structPath.substring(0, structPath.lastIndexOf('.'));
      variable = getNonStructuredVariable(structPath);
    } while (variable == null && structPath.contains("."));

    if (variable == null) {
      return null;
    }

    while (!fieldsToAccess.empty()) {
      String field = fieldsToAccess.pop();
      variable =
          variable.getFieldValue(structPath, field, expander, pathMapper, throwOnMissingVariable);
      if (variable == null) {
        if (throwOnMissingVariable) {
          throw new ExpansionException(
              String.format(
                  "Invalid toolchain configuration: Cannot expand variable '%s.%s': structure %s "
                      + "doesn't have a field named '%s'",
                  structPath, field, structPath, field));
        } else {
          return null;
        }
      }
    }
    return variable;
  }

  public String getStringVariable(String variableName, PathMapper pathMapper)
      throws ExpansionException {
    return getVariable(variableName, /* expander= */ null, pathMapper)
        .getStringValue(variableName, pathMapper);
  }

  public Iterable<? extends VariableValue> getSequenceVariable(
      String variableName, PathMapper pathMapper) throws ExpansionException {
    return getVariable(variableName, /* expander= */ null, pathMapper)
        .getSequenceValue(variableName, pathMapper);
  }

  public Iterable<? extends VariableValue> getSequenceVariable(
      String variableName, @Nullable ArtifactExpander expander, PathMapper pathMapper)
      throws ExpansionException {
    return getVariable(variableName, expander, pathMapper)
        .getSequenceValue(variableName, pathMapper);
  }

  /** Returns whether {@code variable} is set. */
  public boolean isAvailable(String variable) {
    return isAvailable(variable, /* expander= */ null);
  }

  boolean isAvailable(String variable, @Nullable ArtifactExpander expander) {
    try {
      // Availability doesn't depend on the path mapper.
      return lookupVariable(
              variable, /* throwOnMissingVariable= */ false, expander, PathMapper.NOOP)
          != null;
    } catch (ExpansionException e) {
      throw new IllegalStateException(
          "Should not happen - call to lookupVariable threw when asked not to.", e);
    }
  }

  abstract Set<String> getVariableKeys();

  abstract void addVariablesToMap(Map<String, Object> variablesMap);

  @Nullable
  abstract VariableValue getNonStructuredVariable(String name);

  /**
   * Value of a build variable exposed to the CROSSTOOL used for flag expansion.
   *
   * <p>{@link VariableValue} represent either primitive values or an arbitrarily deeply nested
   * recursive structures or sequences. Since there are builds with millions of values, some
   * implementations might exist only to optimize memory usage.
   *
   * <p>Implementations must be immutable and without any side-effects. They will be expanded and
   * queried multiple times.
   */
  interface VariableValue {
    /**
     * Returns string value of the variable, if the variable type can be converted to string (e.g.
     * StringValue), or throw exception if it cannot (e.g. Sequence).
     *
     * @param variableName name of the variable value at hand, for better exception message.
     */
    String getStringValue(String variableName, PathMapper pathMapper) throws ExpansionException;

    /**
     * Returns Iterable value of the variable, if the variable type can be converted to a Iterable
     * (e.g. Sequence), or throw exception if it cannot (e.g. StringValue).
     *
     * @param variableName name of the variable value at hand, for better exception message.
     */
    Iterable<? extends VariableValue> getSequenceValue(String variableName, PathMapper pathMapper)
        throws ExpansionException;

    /**
     * Returns value of the field, if the variable is of struct type or throw exception if it is not
     * or no such field exists.
     *
     * @param variableName name of the variable value at hand, for better exception message.
     */
    VariableValue getFieldValue(
        String variableName,
        String field,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        boolean throwOnMissingVariable)
        throws ExpansionException;

    @VisibleForTesting
    default VariableValue getFieldValue(String variableName, String field)
        throws ExpansionException {
      return getFieldValue(
          variableName,
          field,
          /* expander= */ null,
          PathMapper.NOOP,
          /* throwOnMissingVariable= */ true);
    }

    /** Returns true if the variable is truthy */
    boolean isTruthy();
  }

  /**
   * Adapter for {@link VariableValue} predefining error handling methods. Override {@link
   * #getVariableTypeName()}, {@link #isTruthy()}, and one of {@link #getFieldValue(String,
   * String)}, {@link VariableValue#getSequenceValue(String, PathMapper)}, or {@link
   * VariableValue#getStringValue(String, PathMapper)}, and you'll get error handling for the other
   * methods for free.
   */
  abstract static class VariableValueAdapter implements VariableValue {

    /** Returns human-readable variable type name to be used in error messages. */
    public abstract String getVariableTypeName();

    @Override
    public abstract boolean isTruthy();

    @Nullable
    @Override
    public VariableValue getFieldValue(
        String variableName,
        String field,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        boolean throwOnMissingVariable)
        throws ExpansionException {
      if (throwOnMissingVariable) {
        throw new ExpansionException(
            String.format(
                "Invalid toolchain configuration: Cannot expand variable '%s.%s': variable '%s' is "
                    + "%s, expected structure",
                variableName, field, variableName, getVariableTypeName()));
      } else {
        return null;
      }
    }

    @Override
    public String getStringValue(String variableName, PathMapper pathMapper)
        throws ExpansionException {
      throw new ExpansionException(
          String.format(
              "Invalid toolchain configuration: Cannot expand variable '%s': expected string, "
                  + "found %s",
              variableName, getVariableTypeName()));
    }

    @Override
    public Iterable<? extends VariableValue> getSequenceValue(
        String variableName, PathMapper pathMapper) throws ExpansionException {
      throw new ExpansionException(
          String.format(
              "Invalid toolchain configuration: Cannot expand variable '%s': expected sequence, "
                  + "found %s",
              variableName, getVariableTypeName()));
    }
  }

  /** Interface for VariableValue builders */
  public interface VariableValueBuilder {
    VariableValue build();
  }

  /** Builder for StringSequence. */
  public static class StringSequenceBuilder implements VariableValueBuilder {

    private final ImmutableList.Builder<String> values = ImmutableList.builder();

    /** Adds a value to the sequence. */
    @CanIgnoreReturnValue
    public StringSequenceBuilder addValue(String value) {
      values.add(value);
      return this;
    }

    /** Returns an immutable string sequence. */
    @Override
    public StringSequence build() {
      return StringSequence.of(values.build());
    }
  }

  /** Builder for Sequence. */
  public static class SequenceBuilder implements VariableValueBuilder {

    private final ImmutableList.Builder<VariableValue> values = ImmutableList.builder();

    /** Adds a value to the sequence. */
    @CanIgnoreReturnValue
    public SequenceBuilder addValue(VariableValue value) {
      values.add(value);
      return this;
    }

    /** Adds a value to the sequence. */
    @CanIgnoreReturnValue
    public SequenceBuilder addValue(VariableValueBuilder value) {
      Preconditions.checkArgument(value != null, "Cannot use null builder for a sequence value");
      values.add(value.build());
      return this;
    }

    /** Returns an immutable sequence. */
    @Override
    public Sequence build() {
      return new Sequence(values.build());
    }

    /**
     * @deprecated Only exposed to get the list into Starlark collect_libraries_to_link
     */
    @Deprecated
    public ImmutableList<VariableValue> getValues() {
      return values.build();
    }
  }

  /** Builder for StructureValue. */
  public static class StructureBuilder implements VariableValueBuilder {

    private final ImmutableMap.Builder<String, VariableValue> fields = ImmutableMap.builder();

    /** Adds a field to the structure. */
    @CanIgnoreReturnValue
    public StructureBuilder addField(String name, VariableValue value) {
      fields.put(name, value);
      return this;
    }

    /** Adds a field to the structure. */
    @CanIgnoreReturnValue
    public StructureBuilder addField(String name, VariableValueBuilder valueBuilder) {
      Preconditions.checkArgument(
          valueBuilder != null,
          "Cannot use null builder to get a field value for field '%s'",
          name);
      fields.put(name, valueBuilder.build());
      return this;
    }

    /** Adds a field to the structure. */
    @CanIgnoreReturnValue
    public StructureBuilder addField(String name, String value) {
      fields.put(name, new StringValue(value));
      return this;
    }

    /** Adds a field to the structure. */
    @CanIgnoreReturnValue
    public StructureBuilder addField(String name, ImmutableList<String> values) {
      fields.put(name, StringSequence.of(values));
      return this;
    }

    /** Returns an immutable structure. */
    @Override
    public StructureValue build() {
      return new StructureValue(fields.buildOrThrow());
    }
  }

  /**
   * A sequence of structure values. Exists as a memory optimization - a typical build can contain
   * millions of feature values, so getting rid of the overhead of {@code StructureValue} objects
   * significantly reduces memory overhead.
   */
  @Immutable
  public abstract static class LibraryToLinkValue extends VariableValueAdapter
      implements StarlarkValue {

    private static final Interner<LibraryToLinkValue> interner = BlazeInterners.newWeakInterner();

    public static final String OBJECT_FILES_FIELD_NAME = "object_files";
    public static final String NAME_FIELD_NAME = "name";
    public static final String PATH_FIELD_NAME = "path";
    public static final String TYPE_FIELD_NAME = "type";
    public static final String IS_WHOLE_ARCHIVE_FIELD_NAME = "is_whole_archive";

    private static final String LIBRARY_TO_LINK_VARIABLE_TYPE_NAME = "structure (LibraryToLink)";

    public static LibraryToLinkValue forDynamicLibrary(String name) {
      return interner.intern(new ForDynamicLibrary(name));
    }

    public static LibraryToLinkValue forVersionedDynamicLibrary(String name, String path) {
      return interner.intern(new ForVersionedDynamicLibrary(name, path));
    }

    public static LibraryToLinkValue forInterfaceLibrary(String name) {
      return interner.intern(new ForInterfaceLibrary(name));
    }

    public static LibraryToLinkValue forStaticLibrary(String name, boolean isWholeArchive) {
      return isWholeArchive
          ? interner.intern(new ForStaticLibraryWholeArchive(name))
          : interner.intern(new ForStaticLibrary(name));
    }

    public static LibraryToLinkValue forObjectFile(String name, boolean isWholeArchive) {
      return isWholeArchive
          ? interner.intern(new ForObjectFileWholeArchive(name))
          : interner.intern(new ForObjectFile(name));
    }

    public static LibraryToLinkValue forObjectFileGroup(
        ImmutableList<Artifact> objects, boolean isWholeArchive) {
      Preconditions.checkNotNull(objects);
      Preconditions.checkArgument(!objects.isEmpty());
      return isWholeArchive
          ? interner.intern(new ForObjectFileGroupWholeArchive(objects))
          : interner.intern(new ForObjectFileGroup(objects));
    }

    @Override
    @Nullable
    public VariableValue getFieldValue(
        String variableName,
        String field,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        boolean throwOnMissingVariable) {
      if (TYPE_FIELD_NAME.equals(field)) {
        return new StringValue(getTypeName());
      } else if (IS_WHOLE_ARCHIVE_FIELD_NAME.equals(field)) {
        return BooleanValue.of(getIsWholeArchive());
      }
      return null;
    }

    protected boolean getIsWholeArchive() {
      return false;
    }

    protected abstract String getTypeName();

    @Override
    public String getVariableTypeName() {
      return LIBRARY_TO_LINK_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return true;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof LibraryToLinkValue other)) {
        return false;
      }
      if (this == obj) {
        return true;
      }
      return this.getTypeName().equals(other.getTypeName())
          && getIsWholeArchive() == other.getIsWholeArchive();
    }

    @Override
    public int hashCode() {
      return Objects.hash(getTypeName(), getIsWholeArchive());
    }

    private abstract static class LibraryToLinkValueWithName extends LibraryToLinkValue {
      private final String name;

      LibraryToLinkValueWithName(String name) {
        this.name = Preconditions.checkNotNull(name);
      }

      @Override
      public VariableValue getFieldValue(
          String variableName,
          String field,
          @Nullable ArtifactExpander expander,
          PathMapper pathMapper,
          boolean throwOnMissingVariable) {
        if (NAME_FIELD_NAME.equals(field)) {
          if (pathMapper.isNoop()) {
            return new StringValue(name);
          }
          return new StringValue(
              pathMapper.map(PathFragment.createAlreadyNormalized(name)).getPathString());
        }
        return super.getFieldValue(
            variableName, field, expander, pathMapper, throwOnMissingVariable);
      }

      @Override
      public boolean equals(Object obj) {
        if (!(obj instanceof LibraryToLinkValueWithName other)) {
          return false;
        }
        if (this == obj) {
          return true;
        }
        return this.name.equals(other.name) && super.equals(other);
      }

      @Override
      public int hashCode() {
        return 31 * super.hashCode() + name.hashCode();
      }
    }

    private static final class ForDynamicLibrary extends LibraryToLinkValueWithName {
      private ForDynamicLibrary(String name) {
        super(name);
      }

      @Override
      protected String getTypeName() {
        return "dynamic_library";
      }
    }

    private static final class ForVersionedDynamicLibrary extends LibraryToLinkValueWithName {
      private final String path;

      private ForVersionedDynamicLibrary(String name, String path) {
        super(name);
        this.path = path;
      }

      @Override
      public VariableValue getFieldValue(
          String variableName,
          String field,
          @Nullable ArtifactExpander expander,
          PathMapper pathMapper,
          boolean throwOnMissingVariable) {
        if (PATH_FIELD_NAME.equals(field)) {
          return new StringValue(path);
        }
        return super.getFieldValue(
            variableName, field, expander, pathMapper, throwOnMissingVariable);
      }

      @Override
      public boolean equals(Object obj) {
        if (!(obj instanceof ForVersionedDynamicLibrary other)) {
          return false;
        }
        if (this == obj) {
          return true;
        }
        return this.path.equals(other.path) && super.equals(other);
      }

      @Override
      public int hashCode() {
        return 31 * super.hashCode() + path.hashCode();
      }

      @Override
      protected String getTypeName() {
        return "versioned_dynamic_library";
      }
    }

    private static final class ForInterfaceLibrary extends LibraryToLinkValueWithName {
      private ForInterfaceLibrary(String name) {
        super(name);
      }

      @Override
      protected String getTypeName() {
        return "interface_library";
      }
    }

    private static class ForStaticLibrary extends LibraryToLinkValueWithName {
      private ForStaticLibrary(String name) {
        super(name);
      }

      @Override
      protected String getTypeName() {
        return "static_library";
      }
    }

    private static final class ForStaticLibraryWholeArchive extends ForStaticLibrary {
      private ForStaticLibraryWholeArchive(String name) {
        super(name);
      }

      @Override
      protected boolean getIsWholeArchive() {
        return true;
      }
    }

    private static class ForObjectFile extends LibraryToLinkValueWithName {
      private ForObjectFile(String name) {
        super(name);
      }

      @Override
      protected String getTypeName() {
        return "object_file";
      }
    }

    private static final class ForObjectFileWholeArchive extends ForObjectFile {
      private ForObjectFileWholeArchive(String name) {
        super(name);
      }

      @Override
      protected boolean getIsWholeArchive() {
        return true;
      }
    }

    private static class ForObjectFileGroup extends LibraryToLinkValue {
      private final ImmutableList<Artifact> objectFiles;

      private ForObjectFileGroup(ImmutableList<Artifact> objectFiles) {
        this.objectFiles = objectFiles;
      }

      @Nullable
      @Override
      public VariableValue getFieldValue(
          String variableName,
          String field,
          @Nullable ArtifactExpander expander,
          PathMapper pathMapper,
          boolean throwOnMissingVariable) {
        if (NAME_FIELD_NAME.equals(field)) {
          return null;
        }

        if (OBJECT_FILES_FIELD_NAME.equals(field)) {
          ImmutableList.Builder<String> expandedObjectFiles = ImmutableList.builder();
          for (Artifact objectFile : objectFiles) {
            if (objectFile.isTreeArtifact() && expander != null) {
              expandedObjectFiles.addAll(
                  Collections2.transform(
                      expander.tryExpandTreeArtifact(objectFile),
                      pathMapper::getMappedExecPathString));
            } else {
              expandedObjectFiles.add(pathMapper.getMappedExecPathString(objectFile));
            }
          }
          return StringSequence.of(expandedObjectFiles.build());
        }

        return super.getFieldValue(
            variableName, field, expander, pathMapper, throwOnMissingVariable);
      }

      @Override
      protected String getTypeName() {
        return "object_file_group";
      }

      @Override
      public boolean equals(Object obj) {
        if (!(obj instanceof ForObjectFileGroup other)) {
          return false;
        }
        if (this == obj) {
          return true;
        }
        return this.objectFiles.equals(other.objectFiles) && super.equals(other);
      }

      @Override
      public int hashCode() {
        return 31 * super.hashCode() + objectFiles.hashCode();
      }
    }

    private static final class ForObjectFileGroupWholeArchive extends ForObjectFileGroup {
      private ForObjectFileGroupWholeArchive(ImmutableList<Artifact> objectFiles) {
        super(objectFiles);
      }

      @Override
      protected boolean getIsWholeArchive() {
        return true;
      }
    }
  }

  /** Sequence of arbitrary VariableValue objects. */
  @Immutable
  private static final class Sequence extends VariableValueAdapter {
    private static final String SEQUENCE_VARIABLE_TYPE_NAME = "sequence";

    private final ImmutableList<VariableValue> values;

    private Sequence(ImmutableList<VariableValue> values) {
      this.values = values;
    }

    @Override
    public ImmutableList<VariableValue> getSequenceValue(
        String variableName, PathMapper pathMapper) {
      return values;
    }

    @Override
    public String getVariableTypeName() {
      return SEQUENCE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return values.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Sequence)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Objects.equals(values, ((Sequence) other).values);
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }
  }

  /**
   * A sequence of simple string values. Exists as a memory optimization - a typical build can
   * contain millions of feature values, so getting rid of the overhead of {@code StringValue}
   * objects significantly reduces memory overhead.
   */
  @Immutable
  private static final class StringSequence extends VariableValueAdapter {
    static final Interner<StringSequence> stringSequenceInterner = BlazeInterners.newWeakInterner();
    private final ImmutableList<String> values;

    static StringSequence of(Iterable<String> values) {
      return stringSequenceInterner.intern(new StringSequence(values));
    }

    private StringSequence(Iterable<String> values) {
      ImmutableList.Builder<String> valuesBuilder = new ImmutableList.Builder<>();
      for (String value : values) {
        valuesBuilder.add(value.intern());
      }
      this.values = valuesBuilder.build();
    }

    @Override
    public ImmutableList<VariableValue> getSequenceValue(
        String variableName, PathMapper pathMapper) {
      ImmutableList.Builder<VariableValue> sequences =
          ImmutableList.builderWithExpectedSize(values.size());
      for (String value : values) {
        sequences.add(new StringValue(pathMapper.mapHeuristically(value)));
      }
      return sequences.build();
    }

    @Override
    public String getVariableTypeName() {
      return Sequence.SEQUENCE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !Iterables.isEmpty(values);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StringSequence)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Iterables.elementsEqual(values, ((StringSequence) other).values);
    }

    @Override
    public int hashCode() {
      int hash = 1;
      for (String s : values) {
        hash = 31 * hash + Objects.hashCode(s);
      }
      return hash;
    }
  }

  /**
   * A sequence of simple string values. Exists as a memory optimization - a typical build can
   * contain millions of feature values, so getting rid of the overhead of {@code StringValue}
   * objects significantly reduces memory overhead.
   *
   * <p>Because checking nested set equality is expensive, equality for these sequences is defined
   * in terms of {@link NestedSet#shallowEquals}, which can miss some value-equal nested sets.
   * Equality is never used currently (but may be needed in the future for interning during
   * deserialization), so this is acceptable.
   */
  @Immutable
  private static final class StringSetSequence extends VariableValueAdapter {
    private final NestedSet<String> values;

    private StringSetSequence(NestedSet<String> values) {
      Preconditions.checkNotNull(values);
      this.values = values;
    }

    @Override
    public ImmutableList<VariableValue> getSequenceValue(
        String variableName, PathMapper pathMapper) {
      ImmutableList<String> valuesList = values.toList();
      ImmutableList.Builder<VariableValue> sequences =
          ImmutableList.builderWithExpectedSize(valuesList.size());
      for (String value : valuesList) {
        sequences.add(new StringValue(value));
      }
      return sequences.build();
    }

    @Override
    public String getVariableTypeName() {
      return Sequence.SEQUENCE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !values.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StringSetSequence)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return values.shallowEquals(((StringSetSequence) other).values);
    }

    @Override
    public int hashCode() {
      return values.shallowHashCode();
    }
  }

  @Immutable
  private static final class PathFragmentSetSequence extends VariableValueAdapter {
    private final NestedSet<PathFragment> values;

    private PathFragmentSetSequence(NestedSet<PathFragment> values) {
      Preconditions.checkNotNull(values);
      this.values = values;
    }

    @Override
    public ImmutableList<VariableValue> getSequenceValue(
        String variableName, PathMapper pathMapper) {
      ImmutableList<PathFragment> valuesList = values.toList();
      ImmutableList.Builder<VariableValue> sequences =
          ImmutableList.builderWithExpectedSize(valuesList.size());
      for (PathFragment value : valuesList) {
        sequences.add(new StringValue(pathMapper.map(value).getSafePathString()));
      }
      return sequences.build();
    }

    @Override
    public String getVariableTypeName() {
      return Sequence.SEQUENCE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !values.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof PathFragmentSetSequence otherPathFragments)) {
        return false;
      }
      return values.shallowEquals(otherPathFragments.values);
    }

    @Override
    public int hashCode() {
      return values.shallowHashCode();
    }
  }

  @Immutable
  private static final class ArtifactSetSequence extends VariableValueAdapter {
    private final NestedSet<Artifact> values;

    private ArtifactSetSequence(NestedSet<Artifact> values) {
      Preconditions.checkNotNull(values);
      this.values = values;
    }

    @Override
    public ImmutableList<VariableValue> getSequenceValue(
        String variableName, PathMapper pathMapper) {
      ImmutableList<Artifact> valuesList = values.toList();
      ImmutableList.Builder<VariableValue> sequences =
          ImmutableList.builderWithExpectedSize(valuesList.size());
      for (Artifact value : valuesList) {
        sequences.add(new StringValue(pathMapper.getMappedExecPathString(value)));
      }
      return sequences.build();
    }

    @Override
    public String getVariableTypeName() {
      return Sequence.SEQUENCE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !values.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof ArtifactSetSequence otherArtifacts)) {
        return false;
      }
      return values.shallowEquals(otherArtifacts.values);
    }

    @Override
    public int hashCode() {
      return values.shallowHashCode();
    }
  }

  /**
   * Single structure value. Be careful not to create sequences of single structures, as the memory
   * overhead is prohibitively big.
   */
  @Immutable
  private static final class StructureValue extends VariableValueAdapter {
    private static final String STRUCTURE_VARIABLE_TYPE_NAME = "structure";

    private final ImmutableMap<String, VariableValue> value;

    private StructureValue(ImmutableMap<String, VariableValue> value) {
      this.value = value;
    }

    @Nullable
    @Override
    public VariableValue getFieldValue(
        String variableName,
        String field,
        @Nullable ArtifactExpander expander,
        PathMapper pathMapper,
        boolean throwOnMissingVariable) {
      return value.getOrDefault(field, null);
    }

    @Override
    public String getVariableTypeName() {
      return STRUCTURE_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !value.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StructureValue)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Objects.equals(value, ((StructureValue) other).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }

  /**
   * Most leaves in the variable sequence node tree are simple string values. Note that this should
   * never live outside of {@code expand}, as the object overhead is prohibitively expensive.
   */
  @Immutable
  private static final class StringValue extends VariableValueAdapter {
    private static final String STRING_VARIABLE_TYPE_NAME = "string";

    private final String value;

    StringValue(String value) {
      this.value = Preconditions.checkNotNull(value, "Cannot create StringValue from null");
    }

    @Override
    public String getStringValue(String variableName, PathMapper pathMapper) {
      return value;
    }

    @Override
    public String getVariableTypeName() {
      return STRING_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return !value.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StringValue)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Objects.equals(value, ((StringValue) other).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }

  @Immutable
  private static final class BooleanValue extends VariableValueAdapter {
    private static final BooleanValue TRUE = new BooleanValue(true);
    private static final BooleanValue FALSE = new BooleanValue(false);

    private static BooleanValue of(boolean value) {
      return value ? TRUE : FALSE;
    }

    private final boolean value;

    BooleanValue(boolean value) {
      this.value = value;
    }

    @Override
    public String getStringValue(String variableName, PathMapper pathMapper) {
      return value ? "1" : "0";
    }

    @Override
    public String getVariableTypeName() {
      return "boolean";
    }

    @Override
    public boolean isTruthy() {
      return value;
    }
  }

  /**
   * Represents leaves in the variable sequence node tree that are paths of artifacts. Note that
   * this should never live outside of {@code expand}, as the object overhead is prohibitively
   * expensive.
   */
  @Immutable
  private static final class ArtifactValue extends VariableValueAdapter {
    private static final String ARTIFACT_VARIABLE_TYPE_NAME = "artifact";

    private final Artifact value;

    ArtifactValue(Artifact value) {
      this.value = value;
    }

    @Override
    public String getStringValue(String variableName, PathMapper pathMapper) {
      return pathMapper.getMappedExecPathString(value);
    }

    @Override
    public String getVariableTypeName() {
      return ARTIFACT_VARIABLE_TYPE_NAME;
    }

    @Override
    public boolean isTruthy() {
      return true;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof ArtifactValue otherValue)) {
        return false;
      }
      return value.equals(otherValue.value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }

  public static Builder builder() {
    return new Builder(null);
  }

  public static Builder builder(@Nullable CcToolchainVariables parent) {
    return new Builder(parent);
  }

  /** Builder for {@code Variables}. */
  // TODO(b/65472725): Forbid sequences with empty string in them.
  public static class Builder {
    private final Map<String, Object> variablesMap = new LinkedHashMap<>();
    private final CcToolchainVariables parent;

    private Builder(@Nullable CcToolchainVariables parent) {
      // private to avoid class initialization deadlock between this class and its outer class
      this.parent = parent;
    }

    /** Adds a variable that expands {@code name} to {@code 0} or {@code 1}. */
    @CanIgnoreReturnValue
    public Builder addBooleanValue(String name, boolean value) {
      variablesMap.put(name, BooleanValue.of(value));
      return this;
    }

    /** Add a string variable that expands {@code name} to {@code value}. */
    @CanIgnoreReturnValue
    public Builder addStringVariable(String name, String value) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(value, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, value);
      return this;
    }

    /** Add an artifact variable that expands {@code name} to {@code value}. */
    @CanIgnoreReturnValue
    public Builder addArtifactVariable(String name, Artifact value) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(value, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, value);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder overrideArtifactVariable(String name, Artifact value) {
      Preconditions.checkNotNull(value, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, value);
      return this;
    }

    /**
     * Add an artifact or string variable that expands {@code name} to {@code value}.
     *
     * <p>Prefer {@link #addArtifactVariable} and {@link #addStringVariable}. This method is only
     * meant to support string-based Starlark API.
     */
    @CanIgnoreReturnValue
    public Builder addArtifactOrStringVariable(String name, Object value) {
      return switch (value) {
        case String s -> addStringVariable(name, s);
        case Artifact artifact -> addArtifactVariable(name, artifact);
        case null ->
            throw new IllegalArgumentException(
                "Cannot set null as a value for variable '" + name + "'");
        default ->
            throw new IllegalArgumentException("Unsupported value type: " + value.getClass());
      };
    }

    /** Overrides a variable to expands {@code name} to {@code value} instead. */
    @CanIgnoreReturnValue
    public Builder overrideStringVariable(String name, String value) {
      Preconditions.checkNotNull(value, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, value);
      return this;
    }

    /**
     * Add a sequence variable that expands {@code name} to {@code values}.
     *
     * <p>Accepts values as ImmutableSet. As ImmutableList has smaller memory footprint, we copy the
     * values into a new list.
     */
    @CanIgnoreReturnValue
    public Builder addStringSequenceVariable(String name, ImmutableSet<String> values) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(values, "Cannot set null as a value for variable '%s'", name);
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.addAll(values);
      variablesMap.put(name, StringSequence.of(builder.build()));
      return this;
    }

    /**
     * Add a sequence variable that expands {@code name} to {@code values}.
     *
     * <p>Accepts values as NestedSet. Nested set is stored directly, not cloned, not flattened.
     */
    @CanIgnoreReturnValue
    public Builder addStringSequenceVariable(String name, NestedSet<String> values) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(values, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, new StringSetSequence(values));
      return this;
    }

    /**
     * Add a sequence variable that expands {@code name} to {@code values}.
     *
     * <p>Accepts values as Iterable. The iterable is stored directly, not cloned, not iterated. Be
     * mindful of memory consumption of the particular Iterable. Prefer ImmutableList, or be sure
     * that the iterable always returns the same elements in the same order, without any side
     * effects.
     */
    @CanIgnoreReturnValue
    public Builder addStringSequenceVariable(String name, Iterable<String> values) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(values, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, StringSequence.of(values));
      return this;
    }

    /**
     * Add a sequence variable that expands {@code name} to {@link PathFragment} {@code values}.
     *
     * <p>Accepts values as NestedSet. Nested set is stored directly, not cloned, not flattened.
     */
    @CanIgnoreReturnValue
    public Builder addPathFragmentSequenceVariable(String name, NestedSet<PathFragment> values) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(values, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, new PathFragmentSetSequence(values));
      return this;
    }

    /**
     * Add a sequence variable that expands {@code name} to {@link Artifact} {@code values}.
     *
     * <p>Accepts values as NestedSet. Nested set is stored directly, not cloned, not flattened.
     */
    @CanIgnoreReturnValue
    public Builder addArtifactSequenceVariable(String name, NestedSet<Artifact> values) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(values, "Cannot set null as a value for variable '%s'", name);
      variablesMap.put(name, new ArtifactSetSequence(values));
      return this;
    }

    /**
     * Add a variable built using {@code VariableValueBuilder} api that expands {@code name} to the
     * value returned by the {@code builder}.
     */
    @CanIgnoreReturnValue
    public Builder addCustomBuiltVariable(
        String name, CcToolchainVariables.VariableValueBuilder builder) {
      checkVariableNotPresentAlready(name);
      Preconditions.checkNotNull(
          builder, "Cannot use null builder to get variable value for variable '%s'", name);
      variablesMap.put(name, builder.build());
      return this;
    }

    /** Add all string variables in a map. */
    @CanIgnoreReturnValue
    public Builder addAllStringVariables(Map<String, String> variables) {
      for (String name : variables.keySet()) {
        checkVariableNotPresentAlready(name);
      }
      variablesMap.putAll(variables);
      return this;
    }

    private void checkVariableNotPresentAlready(String name) {
      Preconditions.checkNotNull(name);
      Preconditions.checkArgument(
          !variablesMap.containsKey(name), "Cannot overwrite variable '%s'", name);
    }

    /**
     * Adds all variables to this builder. Cannot override already added variables. Does not add
     * variables defined in the {@code parent} variables.
     */
    @CanIgnoreReturnValue
    public Builder addAllNonTransitive(CcToolchainVariables variables) {
      SetView<String> intersection =
          Sets.intersection(variables.getVariableKeys(), variablesMap.keySet());
      Preconditions.checkArgument(
          intersection.isEmpty(), "Cannot overwrite existing variables: %s", intersection);
      variables.addVariablesToMap(variablesMap);
      return this;
    }

    /** @return a new {@link CcToolchainVariables} object. */
    public CcToolchainVariables build() {
      if (variablesMap.size() == 1) {
        return new SingleVariables(
            parent,
            variablesMap.keySet().iterator().next(),
            asVariableValue(variablesMap.values().iterator().next()));
      }
      return new MapVariables(parent, variablesMap);
    }
  }

  /** Wraps a raw variablesMap value into an appropriate VariableValue if necessary. */
  private static VariableValue asVariableValue(Object o) {
    return switch (o) {
      case String s -> new StringValue(s);
      case Artifact artifact -> new ArtifactValue(artifact);
      default -> (VariableValue) o;
    };
  }

  /**
   * A group of extra {@code Variable} instances, packaged as logic for adding to a {@code Builder}
   */
  public interface VariablesExtension {
    void addVariables(Builder builder);
  }

  private static final class MapVariables extends CcToolchainVariables {
    private static final Interner<ImmutableMap<String, Integer>> keyInterner =
        BlazeInterners.newWeakInterner();

    @Nullable private final CcToolchainVariables parent;

    /**
     * This is a slightly interesting data structure that's necessary to optimize for memory
     * consumption. The premise is that a lot of compilations use the exact same variable keys, just
     * with different values. Thus, it is important to store the keys separately so that they can be
     * interned while storing the values in a compact way. keyToIndex maps from a variable name to
     * the index of the corresponding value in values.
     */
    private final ImmutableMap<String, Integer> keyToIndex;

    /** The values belonging to the keys stored in keyToIndex. */
    private final ImmutableList<Object> values;

    private MapVariables(CcToolchainVariables parent, Map<String, Object> variablesMap) {
      this.parent = parent;
      ImmutableMap.Builder<String, Integer> keyBuilder = ImmutableMap.builder();
      ImmutableList.Builder<Object> valuesBuilder = ImmutableList.builder();
      int index = 0;
      for (String key : ImmutableList.sortedCopyOf(variablesMap.keySet())) {
        keyBuilder.put(key, index++);
        valuesBuilder.add(variablesMap.get(key));
      }
      this.keyToIndex = keyInterner.intern(keyBuilder.buildOrThrow());
      this.values = valuesBuilder.build();
    }

    @Override
    public boolean isImmutable() {
      return true; // immutable and Starlark-hashable
    }

    @Override
    ImmutableSet<String> getVariableKeys() {
      return keyToIndex.keySet();
    }

    @Override
    void addVariablesToMap(Map<String, Object> variablesMap) {
      for (Map.Entry<String, Integer> entry : keyToIndex.entrySet()) {
        variablesMap.put(entry.getKey(), values.get(entry.getValue()));
      }
    }

    @Nullable
    @Override
    VariableValue getNonStructuredVariable(String name) {
      if (keyToIndex.containsKey(name)) {
        return CcToolchainVariables.asVariableValue(values.get(keyToIndex.get(name)));
      }

      if (parent != null) {
        return parent.getNonStructuredVariable(name);
      }

      return null;
    }

    /**
     * NB: this compares parents using reference equality instead of logical equality.
     *
     * <p>This is a performance optimization to avoid possibly expensive recursive equality
     * expansions and suitable for comparisons needed by interning deserialized values. If full
     * logical equality is desired, it's possible to either enable full interning (at a modest CPU
     * cost) or change the parent comparison to use deep equality.
     *
     * <p>This same comment applies to {@link SingleVariables#equals}.
     */
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof MapVariables that)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      if (this.parent != that.parent) {
        return false;
      }
      return Objects.equals(this.keyToIndex, that.keyToIndex)
          && Objects.equals(this.values, that.values);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(keyToIndex, values) + System.identityHashCode(parent);
    }
  }

  static final class SingleVariables extends CcToolchainVariables {
    @Nullable private final CcToolchainVariables parent;
    private final String name;
    private final VariableValue variableValue;

    SingleVariables(CcToolchainVariables parent, String name, VariableValue variableValue) {
      this.parent = parent;
      this.name = name;
      this.variableValue = variableValue;
    }

    @Override
    ImmutableSet<String> getVariableKeys() {
      return ImmutableSet.of(name);
    }

    @Override
    void addVariablesToMap(Map<String, Object> variablesMap) {
      variablesMap.put(name, variableValue);
    }

    @Nullable
    @Override
    VariableValue getNonStructuredVariable(String name) {
      if (this.name.equals(name)) {
        return variableValue;
      }
      return parent == null ? null : parent.getNonStructuredVariable(name);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof SingleVariables that)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      if (this.parent != that.parent) {
        return false;
      }
      return Objects.equals(this.name, that.name)
          && Objects.equals(this.variableValue, that.variableValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(parent, name, variableValue);
    }
  }
}
