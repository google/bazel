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

package com.google.devtools.common.options;

import com.google.common.base.Ascii;
import java.util.Arrays;

/**
 * A converter superclass for converters that parse enums.
 *
 * <p>Just subclass this class, creating a zero argument constructor that calls {@link
 * #EnumConverter(Class, String)}.
 *
 * <p>This class compares the input string to the string returned by the toString() method of each
 * enum member in a case-insensitive way. Usually, this is the name of the symbol, but beware if you
 * override toString()!
 */
public abstract class EnumConverter<T extends Enum<T>> extends Converter.Contextless<T> {

  private final Class<T> enumType;
  private final String typeName;

  /**
   * Creates a new enum converter. You *must* implement a zero-argument
   * constructor that delegates to this constructor, passing in the appropriate
   * parameters.
   *
   * @param enumType The type of your enumeration; usually a class literal
   *                 like MyEnum.class
   * @param typeName The intuitive name of your enumeration, for example, the
   *                 type name for CompilationMode might be "compilation mode".
   */
  protected EnumConverter(Class<T> enumType, String typeName) {
    this.enumType = enumType;
    this.typeName = typeName;
  }

  /** Implements {@link Converter#convert(String, Object)}. */
  @Override
  public T convert(String input) throws OptionsParsingException {
    for (T value : enumType.getEnumConstants()) {
      if (Ascii.equalsIgnoreCase(value.toString(), input)) {
        return value;
      }
    }
    throw new OptionsParsingException("Not a valid " + typeName + ": '"
                                      + input + "' (should be "
                                      + getTypeDescription() + ")");
  }

  /**
   * Implements {@link #getTypeDescription()}.
   */
  @Override
  public final String getTypeDescription() {
    return Ascii.toLowerCase(
        Converters.joinEnglishList(Arrays.asList(enumType.getEnumConstants())));
  }

  public final Class<T> getEnumType() {
    return enumType;
  }
}
