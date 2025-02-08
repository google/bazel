// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages;

import com.google.devtools.build.docgen.annot.DocCategory;
import java.util.Arrays;
import java.util.Map;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Structure;

/** Proto defines the "proto" Starlark module of utilities for protocol message processing. */
@StarlarkBuiltin(
    name = "proto",
    category = DocCategory.TOP_LEVEL_MODULE,
    doc = "A module for protocol message processing.")
public class Proto implements StarlarkValue {

  // Note: in due course this is likely to move to net.starlark.java.lib.proto.
  // Do not add functions that would not belong there!
  // Functions related to running the protocol compiler belong in proto_common.

  private Proto() {}

  public static final Proto INSTANCE = new Proto();

  @StarlarkMethod(
      name = "encode_text",
      doc =
          "Returns the struct argument's encoding as a text-format protocol message.\n"
              + "The data structure must be recursively composed of strings, ints, floats, or"
              + " bools, or structs, sequences, and dicts of these types.\n"
              + "<p>A struct is converted to a message. Fields are emitted in name order.\n"
              + "Each struct field whose value is None is ignored.\n"
              + "<p>A sequence (such as a list or tuple) is converted to a repeated field.\n"
              + "Its elements must not be sequences or dicts.\n"
              + "<p>A dict is converted to a repeated field of messages with fields named 'key' and"
              + " 'value'.\n"
              + "Entries are emitted in iteration (insertion) order.\n"
              + "The dict's keys must be strings or ints, and its values must not be sequences or"
              + " dicts.\n"
              + "Examples:<br><pre class=language-python>proto.encode_text(struct(field=123))\n"
              + "# field: 123\n\n"
              + "proto.encode_text(struct(field=True))\n"
              + "# field: true\n\n"
              + "proto.encode_text(struct(field=[1, 2, 3]))\n"
              + "# field: 1\n"
              + "# field: 2\n"
              + "# field: 3\n\n"
              + "proto.encode_text(struct(field='text', ignored_field=None))\n"
              + "# field: \"text\"\n\n"
              + "proto.encode_text(struct(field=struct(inner_field='text', ignored_field=None)))\n"
              + "# field {\n"
              + "#   inner_field: \"text\"\n"
              + "# }\n\n"
              + "proto.encode_text(struct(field=[struct(inner_field=1), struct(inner_field=2)]))\n"
              + "# field {\n"
              + "#   inner_field: 1\n"
              + "# }\n"
              + "# field {\n"
              + "#   inner_field: 2\n"
              + "# }\n\n"
              + "proto.encode_text(struct(field=struct(inner_field=struct(inner_inner_field='text'))))\n"
              + "# field {\n"
              + "#    inner_field {\n"
              + "#     inner_inner_field: \"text\"\n"
              + "#   }\n"
              + "# }\n\n"
              + "proto.encode_text(struct(foo={4: 3, 2: 1}))\n"
              + "# foo: {\n"
              + "#   key: 4\n"
              + "#   value: 3\n"
              + "# }\n"
              + "# foo: {\n"
              + "#   key: 2\n"
              + "#   value: 1\n"
              + "# }\n"
              + "</pre>",
      parameters = {@Param(name = "x")})
  public String encodeText(Structure x) throws EvalException {
    TextEncoder enc = new TextEncoder();
    enc.message(x);
    return enc.out.toString();
  }

  private static final class TextEncoder {

    private final StringBuilder out = new StringBuilder();
    private int indent = 0;

    // Encodes Structure x as a protocol message.
    private void message(Structure x) throws EvalException {
      // For determinism, sort fields.
      String[] fields = x.getFieldNames().toArray(new String[0]);
      Arrays.sort(fields);
      for (String field : fields) {
        try {
          field(field, x.getValue(field));
        } catch (EvalException ex) {
          throw Starlark.errorf("in %s field .%s: %s", Starlark.type(x), field, ex.getMessage());
        }
      }
    }

    // Encodes Structure field (name, v) as a message field
    // (a repeated field, if v is a dict or sequence.)
    private void field(String name, Object v) throws EvalException {
      // dict?
      if (v instanceof Dict) {
        Dict<?, ?> dict = (Dict<?, ?>) v;
        for (Map.Entry<?, ?> entry : dict.entrySet()) {
          Object key = entry.getKey();
          if (!(key instanceof String || key instanceof StarlarkInt)) {
            throw Starlark.errorf(
                "invalid dict key: got %s, want int or string", Starlark.type(key));
          }
          emitLine(name, " {");
          indent++;
          fieldElement("key", key); // can't fail
          try {
            fieldElement("value", entry.getValue());
          } catch (EvalException ex) {
            throw Starlark.errorf(
                "in value for dict key %s: %s", Starlark.repr(key), ex.getMessage());
          }
          indent--;
          emitLine("}");
        }
        return;
      }

      // list or tuple?
      if (v instanceof Sequence) {
        int i = 0;
        for (Object item : (Sequence<?>) v) {
          try {
            fieldElement(name, item);
          } catch (EvalException ex) {
            throw Starlark.errorf("at %s index %d: %s", Starlark.type(v), i, ex.getMessage());
          }
          i++;
        }
        return;
      }

      // non-repeated field
      if (v == Starlark.NONE) {
        return;
      }
      fieldElement(name, v);
    }

    // Emits field (name, v) as a message field, or one element of a repeated field.
    // v must be an int, float, string, bool, or Structure.
    private void fieldElement(String name, Object v) throws EvalException {
      if (v instanceof Structure) {
        emitLine(name, " {");
        indent++;
        message((Structure) v);
        indent--;
        emitLine("}");

      } else if (v instanceof String s) {
        emitLine(
            name, ": \"", s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"), "\"");

      } else if (v instanceof StarlarkInt || v instanceof Boolean) {
        emitLine(name, ": ", v.toString());
      } else if (v instanceof StarlarkFloat) {
        String s = v.toString();
        // Encoding to textproto via proto.encode_text requires "inf" for "+inf".
        if (s.equals("+inf")) {
          s = "inf";
        }
        emitLine(name, ": ", s);
      } else {
        throw Starlark.errorf("got %s, want string, int, float, bool, or struct", Starlark.type(v));
      }
    }

    // Emits items on an indented line.
    private void emitLine(String... items) {
      for (int i = 0; i < indent; i++) {
        out.append("  ");
      }
      for (String item : items) {
        out.append(item);
      }
      out.append('\n');
    }
  }
}
