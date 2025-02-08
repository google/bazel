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

package net.starlark.java.lib.json;

import java.util.Arrays;
import java.util.Map;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkIterable;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Structure;

// Tests at //src/test/java/net/starlark/java/eval:testdata/json.sky

/**
 * Json defines the Starlark {@code json} module, which provides functions for encoding/decoding
 * Starlark values as JSON (https://tools.ietf.org/html/rfc8259).
 */
@StarlarkBuiltin(
    name = "json",
    category = "core.lib",
    doc = "Module json is a Starlark module of JSON-related functions.")
public final class Json implements StarlarkValue {

  private Json() {}

  /**
   * The module instance. You may wish to add this to your predeclared environment under the name
   * "json".
   */
  public static final Json INSTANCE = new Json();

  /** An interface for StarlarkValue subclasses to define their own JSON encoding. */
  public interface Encodable {
    String encodeJSON();
  }

  /**
   * Encodes a Starlark value as JSON.
   *
   * <p>An application-defined subclass of StarlarkValue may define its own JSON encoding by
   * implementing the {@link Encodable} interface. Otherwise, the encoder tests for the {@link Map},
   * {@link StarlarkIterable}, and {@link Structure} interfaces, in that order, resulting in
   * dict-like, list-like, and struct-like encoding, respectively. See the Starlark documentation
   * annotation for more detail.
   *
   * <p>Encoding any other value yields an error.
   */
  @StarlarkMethod(
      name = "encode",
      doc =
          "<p>The encode function accepts one required positional argument, which it converts to"
              + " JSON by cases:\n"
              + "<ul>\n"
              + "<li>None, True, and False are converted to 'null', 'true', and 'false',"
              + " respectively.\n"
              + "<li>An int, no matter how large, is encoded as a decimal integer. Some decoders"
              + " may not be able to decode very large integers.\n"
              + "<li>A float is encoded using a decimal point or an exponent or both, even if its"
              + " numeric value is an integer. It is an error to encode a non-finite "
              + " floating-point value.\n"
              + "<li>A string value is encoded as a JSON string literal that denotes the value. "
              + " Each unpaired surrogate is replaced by U+FFFD.\n"
              + "<li>A dict is encoded as a JSON object, in key order.  It is an error if any key"
              + " is not a string.\n"
              + "<li>A list or tuple is encoded as a JSON array.\n"
              + "<li>A struct-like value is encoded as a JSON object, in field name order.\n"
              + "</ul>\n"
              + "An application-defined type may define its own JSON encoding.\n"
              + "Encoding any other value yields an error.\n",
      parameters = {@Param(name = "x")})
  public String encode(Object x) throws EvalException {
    Encoder enc = new Encoder();
    try {
      enc.encode(x);
    } catch (StackOverflowError unused) {
      throw Starlark.errorf("nesting depth limit exceeded");
    }
    return enc.out.toString();
  }

  private static final class Encoder {

    private final StringBuilder out = new StringBuilder();

    private void encode(Object x) throws EvalException {
      if (x == Starlark.NONE) {
        out.append("null");
        return;
      }

      if (x instanceof String s) {
        appendQuoted(s);
        return;
      }

      if (x instanceof Boolean || x instanceof StarlarkInt) {
        out.append(x);
        return;
      }

      if (x instanceof StarlarkFloat fl) {
        if (!Double.isFinite(fl.toDouble())) {
          throw Starlark.errorf("cannot encode non-finite float %s", x);
        }
        out.append(x.toString()); // always contains a decimal point or exponent
        return;
      }

      if (x instanceof Encodable) {
        // Application-defined Starlark value types
        // may define their own JSON encoding.
        out.append(((Encodable) x).encodeJSON());
        return;
      }

      // e.g. dict (must have string keys)
      if (x instanceof Map<?, ?> m) {
        // Sort keys for determinism.
        Object[] keys = m.keySet().toArray();
        for (Object key : keys) {
          if (!(key instanceof String)) {
            throw Starlark.errorf(
                "%s has %s key, want string", Starlark.type(x), Starlark.type(key));
          }
        }
        Arrays.sort(keys);

        // emit object
        out.append('{');
        String sep = "";
        for (Object key : keys) {
          out.append(sep);
          sep = ",";
          appendQuoted((String) key);
          out.append(':');
          try {
            encode(m.get(key));
          } catch (EvalException ex) {
            throw Starlark.errorf(
                "in %s key %s: %s", Starlark.type(x), Starlark.repr(key), ex.getMessage());
          }
        }
        out.append('}');
        return;
      }

      // e.g. tuple, list
      if (x instanceof StarlarkIterable<?> it) {
        out.append('[');
        String sep = "";
        int i = 0;
        for (Object elem : it) {
          out.append(sep);
          sep = ",";
          try {
            encode(elem);
          } catch (EvalException ex) {
            throw Starlark.errorf("at %s index %d: %s", Starlark.type(x), i, ex.getMessage());
          }
          i++;
        }
        out.append(']');
        return;
      }

      // e.g. struct
      if (x instanceof Structure obj) {
        // Sort keys for determinism.
        String[] fields = obj.getFieldNames().toArray(new String[0]);
        Arrays.sort(fields);

        out.append('{');
        String sep = "";
        for (String field : fields) {
          out.append(sep);
          sep = ",";
          appendQuoted(field);
          out.append(":");
          try {
            Object v = obj.getValue(field); // may fail (field not defined)
            encode(v); // may fail (unexpected type)
          } catch (EvalException ex) {
            throw Starlark.errorf("in %s field .%s: %s", Starlark.type(x), field, ex.getMessage());
          }
        }
        out.append('}');
        return;
      }

      throw Starlark.errorf("cannot encode %s as JSON", Starlark.type(x));
    }

    private void appendQuoted(String s) {
      // We use String's code point iterator so that we can map
      // unpaired surrogates to U+FFFD in the output.
      // TODO(adonovan): if we ever get an isPrintable(codepoint)
      // function, use uXXXX escapes for non-printables.
      out.append('"');
      for (int i = 0, n = s.length(); i < n; ) {
        int cp = s.codePointAt(i);

        // ASCII control code?
        if (cp < 0x20) {
          switch (cp) {
            case '\b':
              out.append("\\b");
              break;
            case '\f':
              out.append("\\f");
              break;
            case '\n':
              out.append("\\n");
              break;
            case '\r':
              out.append("\\r");
              break;
            case '\t':
              out.append("\\t");
              break;
            default:
              out.append("\\u00");
              out.append(HEX[(cp >> 4) & 0xf]);
              out.append(HEX[cp & 0xf]);
          }
          i++;
          continue;
        }

        // printable ASCII (or DEL 0x7f)? (common case)
        if (cp < 0x80) {
          if (cp == '"' || cp == '\\') {
            out.append('\\');
          }
          out.append((char) cp);
          i++;
          continue;
        }

        // non-ASCII
        if (Character.MIN_SURROGATE <= cp && cp <= Character.MAX_SURROGATE) {
          cp = 0xFFFD; // unpaired surrogate
        }
        out.appendCodePoint(cp);
        i += Character.charCount(cp);
      }
      out.append('"');
    }
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /** Parses a JSON string as a Starlark value. */
  @StarlarkMethod(
      name = "decode",
      doc =
          "The decode function has one required positional parameter: a JSON string.\n"
              + "It returns the Starlark value that the string denotes.\n"
              + "<ul><li><code>\"null\"</code>, <code>\"true\"</code> and <code>\"false\"</code>"
              + " are parsed as <code>None</code>, <code>True</code>, and <code>False</code>.\n"
              + "<li>Numbers are parsed as int, or as a float if they contain a decimal point or an"
              + " exponent. Although JSON has no syntax  for non-finite values, very large values"
              + " may be decoded as infinity.\n"
              + "<li>a JSON object is parsed as a new unfrozen Starlark dict. If the same key"
              + " string occurs more than once in the object, the last value for the key is kept.\n"
              + "<li>a JSON array is parsed as new unfrozen Starlark list.\n"
              + "</ul>\n"
              + "If <code>x</code> is not a valid JSON encoding and the optional"
              + " <code>default</code> parameter is specified (including specified as"
              + " <code>None</code>), this function returns the <code>default</code> value.\n"
              + "If <code>x</code> is not a valid JSON encoding and the optional"
              + " <code>default</code> parameter is <em>not</em> specified, this function fails.",
      parameters = {
        @Param(name = "x", doc = "JSON string to decode."),
        @Param(
            name = "default",
            named = true,
            doc = "If specified, the value to return when <code>x</code> cannot be decoded.",
            defaultValue = "unbound")
      },
      useStarlarkThread = true)
  public Object decode(String x, Object defaultValue, StarlarkThread thread) throws EvalException {
    try {
      return new Decoder(thread.mutability(), x).decode();
    } catch (EvalException e) {
      if (defaultValue != Starlark.UNBOUND) {
        return defaultValue;
      } else {
        throw e;
      }
    }
  }

  private static final class Decoder {

    // The decoder necessarily makes certain representation choices
    // such as list vs tuple, struct vs dict, int vs float.
    // In principle, we could parameterize it to allow the caller to
    // control the returned types, but there's no compelling need yet.

    private final Mutability mu;
    private final String s; // the input string
    private int i = 0; // current index in s

    private Decoder(Mutability mu, String s) {
      this.mu = mu;
      this.s = s;
    }

    // decode is the entry point into the decoder.
    private Object decode() throws EvalException {
      try {
        Object x = parse();
        if (skipSpace()) {
          throw Starlark.errorf("unexpected character %s after value", quoteChar(s.charAt(i)));
        }
        return x;
      } catch (StackOverflowError unused) {
        throw Starlark.errorf("nesting depth limit exceeded");
      } catch (EvalException ex) {
        throw Starlark.errorf("at offset %d, %s", i, ex.getMessage());
      }
    }

    // parse returns the next JSON value from the input.
    // It consumes leading but not trailing whitespace.
    private Object parse() throws EvalException {
      char c = next();
      switch (c) {
        case '"':
          return parseString();

        case 'n':
          if (s.startsWith("null", i)) {
            i += "null".length();
            return Starlark.NONE;
          }
          break;

        case 't':
          if (s.startsWith("true", i)) {
            i += "true".length();
            return true;
          }
          break;

        case 'f':
          if (s.startsWith("false", i)) {
            i += "false".length();
            return false;
          }
          break;

        case '[':
          // array
          StarlarkList<Object> list = StarlarkList.newList(mu);

          i++; // '['
          c = next();
          if (c != ']') {
            while (true) {
              Object elem = parse();
              list.addElement(elem); // can't fail
              c = next();
              if (c != ',') {
                if (c != ']') {
                  throw Starlark.errorf("got %s, want ',' or ']'", quoteChar(c));
                }
                break;
              }
              i++; // ','
            }
          }
          i++; // ']'
          return list;

        case '{':
          // object
          Dict<String, Object> dict = Dict.of(mu);

          i++; // '{'
          c = next();
          if (c != '}') {
            while (true) {
              Object key = parse();
              if (!(key instanceof String)) {
                throw Starlark.errorf("got %s for object key, want string", Starlark.type(key));
              }
              c = next();
              if (c != ':') {
                throw Starlark.errorf("after object key, got %s, want ':' ", quoteChar(c));
              }
              i++; // ':'
              Object value = parse();
              dict.putEntry((String) key, value); // can't fail
              c = next();
              if (c != ',') {
                if (c != '}') {
                  throw Starlark.errorf("in object, got %s, want ',' or '}'", quoteChar(c));
                }
                break;
              }
              i++; // ','
            }
          }
          i++; // '}'
          return dict;

        default:
          // number?
          if (isdigit(c) || c == '-') {
            return parseNumber(c);
          }
          break;
      }
      throw Starlark.errorf("unexpected character %s", quoteChar(c));
    }

    private String parseString() throws EvalException {
      i++; // '"'
      StringBuilder str = new StringBuilder();
      while (i < s.length()) {
        char c = s.charAt(i);

        // end quote?
        if (c == '"') {
          i++; // skip '"'
          return str.toString();
        }

        // literal char?
        if (c != '\\') {
          // reject unescaped control codes
          if (c <= 0x1F) {
            throw Starlark.errorf("invalid character '\\x%02x' in string literal", (int) c);
          }
          i++; // consume
          str.append(c);
          continue;
        }

        // escape: uXXXX or [\/bfnrt"]
        i++; // '\\'
        if (i == s.length()) {
          throw Starlark.errorf("incomplete escape");
        }
        c = s.charAt(i);
        i++; // consume c
        switch (c) {
          case '\\':
          case '/':
          case '"':
            str.append(c);
            break;
          case 'b':
            str.append('\b');
            break;
          case 'f':
            str.append('\f');
            break;
          case 'n':
            str.append('\n');
            break;
          case 'r':
            str.append('\r');
            break;
          case 't':
            str.append('\t');
            break;
          case 'u': // \ uXXXX
            if (i + 4 >= s.length()) {
              throw Starlark.errorf("incomplete \\uXXXX escape");
            }
            int hex = 0;
            for (int j = 0; j < 4; j++) {
              c = s.charAt(i + j);
              int nybble = 0;
              if (isdigit(c)) {
                nybble = c - '0';
              } else if ('a' <= c && c <= 'f') {
                nybble = 10 + c - 'a';
              } else if ('A' <= c && c <= 'F') {
                nybble = 10 + c - 'A';
              } else {
                throw Starlark.errorf("invalid hex char %s in \\uXXXX escape", quoteChar(c));
              }
              hex = (hex << 4) | nybble;
            }
            str.append((char) hex);
            i += 4;
            break;
          default:
            throw Starlark.errorf("invalid escape '\\%s'", c);
        }
      }
      throw Starlark.errorf("unclosed string literal");
    }

    private Object parseNumber(char c) throws EvalException {
      // For now, allow any sequence of [0-9.eE+-]*.
      boolean isfloat = false; // whether digit string contains [.Ee+-] (other than leading minus)
      int j = i;
      for (j = i + 1; j < s.length(); j++) {
        c = s.charAt(j);
        if (isdigit(c)) {
          // ok
        } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
          isfloat = true;
        } else {
          break;
        }
      }

      String num = s.substring(i, j);

      int digits = i; // s[digits:j] is the digit string
      if (s.charAt(i) == '-') {
        digits++;
      }

      // Structural checks not performed by parse routines below.
      // Unlike most C-like languages,
      // JSON disallows a leading zero before a digit.
      if (digits == j // "-"
          || s.charAt(digits) == '.' // ".5"
          || s.charAt(j - 1) == '.' // "0."
          || num.contains(".e") // "5.e1"
          || (s.charAt(digits) == '0' && j - digits > 1 && isdigit(s.charAt(digits + 1)))) { // "01"
        throw Starlark.errorf("invalid number: %s", num);
      }

      i = j;

      // parse number literal
      try {
        if (isfloat) {
          double x = Double.parseDouble(num);
          return StarlarkFloat.of(x);
        } else {
          return StarlarkInt.parse(num, 10);
        }
      } catch (NumberFormatException unused) {
        throw Starlark.errorf("invalid number: %s", num);
      }
    }

    // skipSpace consumes leading spaces, and reports whether there is more input.
    private boolean skipSpace() {
      for (; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
          return true;
        }
      }
      return false;
    }

    // next consumes leading spaces and returns the first non-space.
    private char next() throws EvalException {
      if (skipSpace()) {
        return s.charAt(i);
      }
      throw Starlark.errorf("unexpected end of file");
    }
  }

  @StarlarkMethod(
      name = "indent",
      doc =
          "The indent function returns the indented form of a valid JSON-encoded string.\n"
              + "Each array element or object field appears on a new line, beginning with"
              + " the prefix string followed by one or more copies of the indent string, according"
              + " to its nesting depth.\n"
              + "The function accepts one required positional parameter, the JSON string,\n"
              + "and two optional keyword-only string parameters, prefix and indent,\n"
              + "that specify a prefix of each new line, and the unit of indentation.\n"
              + "If the input is not valid, the function may fail or return invalid output.\n",
      parameters = {
        @Param(name = "s"),
        @Param(name = "prefix", positional = false, named = true, defaultValue = "''"),
        @Param(name = "indent", positional = false, named = true, defaultValue = "'\\t'")
      })
  public String indent(String s, String prefix, String indent) throws EvalException {
    // Indentation can be efficiently implemented in a single pass, independent of encoding,
    // with no state other than a depth counter. This separation enables efficient indentation
    // of values obtained from, say, reading a file, without the need for decoding.

    Indenter in = new Indenter(prefix, indent, s);
    try {
      in.indent();
    } catch (StringIndexOutOfBoundsException unused) {
      throw Starlark.errorf("input is not valid JSON");
    }
    return in.out.toString();
  }

  @StarlarkMethod(
      name = "encode_indent",
      doc =
          "The encode_indent function is equivalent to <code>json.indent(json.encode(x),"
              + " ...)</code>. See <code>indent</code> for description of formatting parameters.",
      parameters = {
        @Param(name = "x"),
        @Param(name = "prefix", positional = false, named = true, defaultValue = "''"),
        @Param(name = "indent", positional = false, named = true, defaultValue = "'\\t'"),
      })
  public String encodeIndent(Object x, String prefix, String indent) throws EvalException {
    return indent(encode(x), prefix, indent);
  }

  private static final class Indenter {

    private final StringBuilder out = new StringBuilder();
    private final String prefix;
    private final String indent;
    private final String s; // input string
    private int i; // current index in s, possibly out of bounds

    Indenter(String prefix, String indent, String s) {
      this.prefix = prefix;
      this.indent = indent;
      this.s = s;
    }

    // Appends a single JSON value to str.
    // May throw StringIndexOutOfBoundsException.
    //
    // The current implementation is a rudimentary placeholder:
    // given invalid JSON, it produces garbage output.
    // TODO(adonovan): factor Decoder and Indenter using a
    // validating state machine, without loss of efficiency.
    // This requires different states after [, {, :, etc,
    // and a stack of open tokens.
    private void indent() throws EvalException {
      int depth = 0;

      // token loop
      do { // while (depth > 0)
        char c = next();
        int start = i;
        switch (c) {
          case '"': // string
            for (c = s.charAt(++i); c != '"'; c = s.charAt(++i)) {
              if (c == '\\') {
                c = s.charAt(++i);
                if (c == 'u') {
                  i += 4;
                }
              }
            }
            i++; // '"'
            out.append(s, start, i);
            break;

          case 'n':
            i += "null".length();
            out.append(s, start, i);
            break;

          case 't':
            i += "true".length();
            out.append(s, start, i);
            break;

          case 'f':
            i += "false".length();
            out.append(s, start, i);
            break;

          case ',':
            i++;
            out.append(',');
            newline(depth);
            break;

          case '[':
          case '{':
            i++;
            out.append(c);
            c = next();
            if (c == ']' || c == '}') {
              i++;
              out.append(c);
            } else {
              newline(++depth);
            }
            break;

          case ']':
          case '}':
            i++;
            newline(--depth);
            out.append(c);
            break;

          case ':':
            i++;
            out.append(": ");
            break;

          default:
            // number
            if (!(isdigit(c) || c == '-')) {
              throw Starlark.errorf("unexpected character %s", quoteChar(c));
            }
            while (i < s.length()) {
              c = s.charAt(++i);
              if (!(isdigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')) {
                break;
              }
            }
            out.append(s, start, i);
            break;
        }
      } while (depth > 0);
    }

    private void newline(int depth) {
      out.append('\n').append(prefix);
      for (int i = 0; i < depth; i++) {
        out.append(indent);
      }
    }

    // skipSpace consumes leading spaces, and reports whether there is more input.
    private boolean skipSpace() {
      for (; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
          return true;
        }
      }
      return false;
    }

    // next consumes leading spaces and returns the first non-space.
    private char next() throws EvalException {
      if (skipSpace()) {
        return s.charAt(i);
      }
      throw Starlark.errorf("unexpected end of file");
    }
  }

  private static boolean isdigit(char c) {
    return c >= '0' && c <= '9';
  }

  // Returns a Starlark string literal that denotes c.
  private static String quoteChar(char c) {
    return Starlark.repr("" + c);
  }
}
