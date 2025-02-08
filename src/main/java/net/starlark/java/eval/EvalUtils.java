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
package net.starlark.java.eval;

import java.util.IllegalFormatException;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.syntax.TokenKind;

/** Internal declarations used by the evaluator. */
final class EvalUtils {

  private EvalUtils() {}

  static void addIterator(Object x) {
    if (x instanceof Mutability.Freezable) {
      ((Mutability.Freezable) x).updateIteratorCount(+1);
    }
  }

  static void removeIterator(Object x) {
    if (x instanceof Mutability.Freezable) {
      ((Mutability.Freezable) x).updateIteratorCount(-1);
    }
  }

  // The following functions for indexing and slicing match the behavior of Python.

  /**
   * Resolves a positive or negative index to an index in the range [0, length), or throws
   * EvalException if it is out of range. If the index is negative, it counts backward from length.
   */
  static int getSequenceIndex(int index, int length) throws EvalException {
    int actualIndex = index;
    if (actualIndex < 0) {
      actualIndex += length;
    }
    if (actualIndex < 0 || actualIndex >= length) {
      throw Starlark.errorf(
          "index out of range (index is %d, but sequence has %d elements)", index, length);
    }
    return actualIndex;
  }

  /**
   * Returns the effective index denoted by a user-supplied integer. First, if the integer is
   * negative, the length of the sequence is added to it, so an index of -1 represents the last
   * element of the sequence. Then, the integer is "clamped" into the inclusive interval [0,
   * length].
   */
  static int toIndex(int index, int length) {
    if (index < 0) {
      index += length;
    }

    if (index < 0) {
      return 0;
    } else if (index > length) {
      return length;
    } else {
      return index;
    }
  }

  /** Evaluates an eager binary operation, {@code x op y}. (Excludes AND and OR.) */
  static Object binaryOp(TokenKind op, Object x, Object y, StarlarkThread starlarkThread)
      throws EvalException {
    StarlarkSemantics semantics = starlarkThread.getSemantics();
    Mutability mu = starlarkThread.mutability();
    switch (op) {
      case PLUS:
        if (x instanceof StarlarkInt) {
          if (y instanceof StarlarkInt) {
            // int + int
            return StarlarkInt.add((StarlarkInt) x, (StarlarkInt) y);
          } else if (y instanceof StarlarkFloat) {
            // int + float
            double z = ((StarlarkInt) x).toFiniteDouble() + ((StarlarkFloat) y).toDouble();
            return StarlarkFloat.of(z);
          }

        } else if (x instanceof String) {
          if (y instanceof String) {
            // string + string
            return (String) x + (String) y;
          }

        } else if (x instanceof Tuple) {
          if (y instanceof Tuple) {
            // tuple + tuple
            return Tuple.concat((Tuple) x, (Tuple) y);
          }

        } else if (x instanceof StarlarkList) {
          if (y instanceof StarlarkList) {
            // list + list
            return StarlarkList.concat((StarlarkList<?>) x, (StarlarkList<?>) y, mu);
          }

        } else if (x instanceof StarlarkFloat) {
          double xf = ((StarlarkFloat) x).toDouble();
          if (y instanceof StarlarkFloat) {
            // float + float
            double z = xf + ((StarlarkFloat) y).toDouble();
            return StarlarkFloat.of(z);
          } else if (y instanceof StarlarkInt) {
            // float + int
            double z = xf + ((StarlarkInt) y).toFiniteDouble();
            return StarlarkFloat.of(z);
          }
        }
        break;

      case PIPE:
        if (x instanceof StarlarkInt) {
          if (y instanceof StarlarkInt) {
            // int | int
            return StarlarkInt.or((StarlarkInt) x, (StarlarkInt) y);
          }
        } else if (x instanceof Map) {
          if (y instanceof Map) {
            // map | map (usually dicts)
            return Dict.builder().putAll((Map<?, ?>) x).putAll((Map<?, ?>) y).build(mu);
          }
        } else if (x instanceof Set && y instanceof Set) {
          // set | set
          if (semantics.getBool(StarlarkSemantics.EXPERIMENTAL_ENABLE_STARLARK_SET)) {
            return StarlarkSet.empty().union(Tuple.of(x, y), starlarkThread);
          }
        }
        break;

      case AMPERSAND:
        if (x instanceof StarlarkInt && y instanceof StarlarkInt) {
          // int & int
          return StarlarkInt.and((StarlarkInt) x, (StarlarkInt) y);
        } else if (x instanceof Set && y instanceof Set) {
          // set & set
          if (semantics.getBool(StarlarkSemantics.EXPERIMENTAL_ENABLE_STARLARK_SET)) {
            StarlarkSet<?> xSet =
                x instanceof StarlarkSet ? (StarlarkSet<?>) x : StarlarkSet.checkedCopyOf(mu, x);
            return xSet.intersection(Tuple.of(y), starlarkThread);
          }
        }
        break;

      case CARET:
        if (x instanceof StarlarkInt && y instanceof StarlarkInt) {
          // int ^ int
          return StarlarkInt.xor((StarlarkInt) x, (StarlarkInt) y);
        } else if (x instanceof Set && y instanceof Set) {
          // set ^ set
          if (semantics.getBool(StarlarkSemantics.EXPERIMENTAL_ENABLE_STARLARK_SET)) {
            StarlarkSet<?> xSet =
                x instanceof StarlarkSet ? (StarlarkSet<?>) x : StarlarkSet.checkedCopyOf(mu, x);
            return xSet.symmetricDifference(y, starlarkThread);
          }
        }
        break;

      case GREATER_GREATER:
        if (x instanceof StarlarkInt && y instanceof StarlarkInt) {
          // x >> y
          return StarlarkInt.shiftRight((StarlarkInt) x, (StarlarkInt) y);
        }
        break;

      case LESS_LESS:
        if (x instanceof StarlarkInt && y instanceof StarlarkInt) {
          // x << y
          return StarlarkInt.shiftLeft((StarlarkInt) x, (StarlarkInt) y);
        }
        break;

      case MINUS:
        if (x instanceof StarlarkInt) {
          if (y instanceof StarlarkInt) {
            // int - int
            return StarlarkInt.subtract((StarlarkInt) x, (StarlarkInt) y);
          } else if (y instanceof StarlarkFloat) {
            // int - float
            double z = ((StarlarkInt) x).toFiniteDouble() - ((StarlarkFloat) y).toDouble();
            return StarlarkFloat.of(z);
          }

        } else if (x instanceof StarlarkFloat) {
          double xf = ((StarlarkFloat) x).toDouble();
          if (y instanceof StarlarkFloat) {
            // float - float
            double z = xf - ((StarlarkFloat) y).toDouble();
            return StarlarkFloat.of(z);
          } else if (y instanceof StarlarkInt) {
            // float - int
            double z = xf - ((StarlarkInt) y).toFiniteDouble();
            return StarlarkFloat.of(z);
          }
        } else if (x instanceof Set && y instanceof Set) {
          // set - set
          if (semantics.getBool(StarlarkSemantics.EXPERIMENTAL_ENABLE_STARLARK_SET)) {
            StarlarkSet<?> xSet =
                x instanceof StarlarkSet ? (StarlarkSet<?>) x : StarlarkSet.checkedCopyOf(mu, x);
            return xSet.difference(Tuple.of(y), starlarkThread);
          }
        }
        break;

      case STAR:
        if (x instanceof StarlarkInt xi) {
          if (y instanceof StarlarkInt) {
            // int * int
            return StarlarkInt.multiply(xi, (StarlarkInt) y);
          } else if (y instanceof String) {
            // int * string
            return repeatString((String) y, xi);
          } else if (y instanceof Tuple) {
            //  int * tuple
            return ((Tuple) y).repeat(xi);
          } else if (y instanceof StarlarkList) {
            // int * list
            return ((StarlarkList<?>) y).repeat(xi, mu);
          } else if (y instanceof StarlarkFloat) {
            // int * float
            double z = xi.toFiniteDouble() * ((StarlarkFloat) y).toDouble();
            return StarlarkFloat.of(z);
          }

        } else if (x instanceof String) {
          if (y instanceof StarlarkInt) {
            // string * int
            return repeatString((String) x, (StarlarkInt) y);
          }

        } else if (x instanceof Tuple) {
          if (y instanceof StarlarkInt) {
            // tuple * int
            return ((Tuple) x).repeat((StarlarkInt) y);
          }

        } else if (x instanceof StarlarkList) {
          if (y instanceof StarlarkInt) {
            // list * int
            return ((StarlarkList<?>) x).repeat((StarlarkInt) y, mu);
          }

        } else if (x instanceof StarlarkFloat) {
          double xf = ((StarlarkFloat) x).toDouble();
          if (y instanceof StarlarkFloat) {
            // float * float
            return StarlarkFloat.of(xf * ((StarlarkFloat) y).toDouble());
          } else if (y instanceof StarlarkInt) {
            // float * int
            return StarlarkFloat.of(xf * ((StarlarkInt) y).toFiniteDouble());
          }
        }
        break;

      case SLASH: // real division
        if (x instanceof StarlarkInt) {
          double xf = ((StarlarkInt) x).toFiniteDouble();
          if (y instanceof StarlarkInt) {
            // int / int
            return StarlarkFloat.div(xf, ((StarlarkInt) y).toFiniteDouble());
          } else if (y instanceof StarlarkFloat) {
            // int / float
            return StarlarkFloat.div(xf, ((StarlarkFloat) y).toDouble());
          }

        } else if (x instanceof StarlarkFloat) {
          double xf = ((StarlarkFloat) x).toDouble();
          if (y instanceof StarlarkFloat) {
            // float / float
            return StarlarkFloat.div(xf, ((StarlarkFloat) y).toDouble());
          } else if (y instanceof StarlarkInt) {
            // float / int
            return StarlarkFloat.div(xf, ((StarlarkInt) y).toFiniteDouble());
          }
        }
        break;

      case SLASH_SLASH:
        if (x instanceof StarlarkInt) {
          if (y instanceof StarlarkInt) {
            // int // int
            return StarlarkInt.floordiv((StarlarkInt) x, (StarlarkInt) y);
          } else if (y instanceof StarlarkFloat) {
            // int // float
            double xf = ((StarlarkInt) x).toFiniteDouble();
            double yf = ((StarlarkFloat) y).toDouble();
            return StarlarkFloat.floordiv(xf, yf);
          }

        } else if (x instanceof StarlarkFloat) {
          double xf = ((StarlarkFloat) x).toDouble();
          if (y instanceof StarlarkFloat) {
            // float // float
            return StarlarkFloat.floordiv(xf, ((StarlarkFloat) y).toDouble());
          } else if (y instanceof StarlarkInt) {
            // float // int
            return StarlarkFloat.floordiv(xf, ((StarlarkInt) y).toFiniteDouble());
          }
        }
        break;

      case PERCENT:
        if (x instanceof StarlarkInt) {
          if (y instanceof StarlarkInt) {
            // int % int
            return StarlarkInt.mod((StarlarkInt) x, (StarlarkInt) y);

          } else if (y instanceof StarlarkFloat) {
            // int % float
            double xf = ((StarlarkInt) x).toFiniteDouble();
            double yf = ((StarlarkFloat) y).toDouble();
            return StarlarkFloat.mod(xf, yf);
          }

        } else if (x instanceof String xs) {
          // string % any
          try {
            if (y instanceof Tuple) {
              return Starlark.formatWithList(semantics, xs, (Tuple) y);
            } else {
              return Starlark.format(semantics, xs, y);
            }
          } catch (IllegalFormatException ex) {
            throw new EvalException(ex);
          }

        } else if (x instanceof StarlarkFloat) {
          double xf = ((StarlarkFloat) x).toDouble();
          if (y instanceof StarlarkFloat) {
            // float % float
            return StarlarkFloat.mod(xf, ((StarlarkFloat) y).toDouble());
          } else if (y instanceof StarlarkInt) {
            // float % int
            return StarlarkFloat.mod(xf, ((StarlarkInt) y).toFiniteDouble());
          }
        }
        break;

      case EQUALS_EQUALS:
        return x.equals(y);

      case NOT_EQUALS:
        return !x.equals(y);

      case LESS:
        return compare(x, y) < 0;

      case LESS_EQUALS:
        return compare(x, y) <= 0;

      case GREATER:
        return compare(x, y) > 0;

      case GREATER_EQUALS:
        return compare(x, y) >= 0;

      case IN:
        if (y instanceof StarlarkMembershipTestable) {
          return ((StarlarkMembershipTestable) y).containsKey(semantics, x);
        } else if (y instanceof StarlarkIndexable.Threaded) {
          return ((StarlarkIndexable.Threaded) y).containsKey(starlarkThread, semantics, x);
        } else if (y instanceof String) {
          if (!(x instanceof String)) {
            throw Starlark.errorf(
                "'in <string>' requires string as left operand, not '%s'", Starlark.type(x));
          }
          return ((String) y).contains((String) x);
        }
        break;

      case NOT_IN:
        Object z = binaryOp(TokenKind.IN, x, y, starlarkThread);
        if (z != null) {
          return !Starlark.truth(z);
        }
        break;

      default:
        throw new AssertionError("not a binary operator: " + op);
    }

    // custom binary operator?
    if (x instanceof HasBinary) {
      Object z = ((HasBinary) x).binaryOp(op, y, true);
      if (z != null) {
        return z;
      }
    }
    if (y instanceof HasBinary) {
      Object z = ((HasBinary) y).binaryOp(op, x, false);
      if (z != null) {
        return z;
      }
    }

    throw Starlark.errorf(
        "unsupported binary operation: %s %s %s", Starlark.type(x), op, Starlark.type(y));
  }

  // Defines the behavior of the language's ordered comparison operators (< <= => >).
  private static int compare(Object x, Object y) throws EvalException {
    try {
      return Starlark.compareUnchecked(x, y);
    } catch (ClassCastException ex) {
      throw new EvalException(ex.getMessage());
    }
  }

  private static String repeatString(String s, StarlarkInt in) throws EvalException {
    int n = in.toInt("repeat");
    if (n <= 0) {
      return "";
    } else if ((long) s.length() * (long) n > Integer.MAX_VALUE) {
      // Would exceed max length of a java String.
      throw Starlark.errorf("excessive repeat (%d * %d characters)", s.length(), n);
    } else {
      return s.repeat(n);
    }
  }

  /** Evaluates a unary operation. */
  static Object unaryOp(TokenKind op, Object x) throws EvalException {
    switch (op) {
      case NOT:
        return !Starlark.truth(x);

      case MINUS:
        if (x instanceof StarlarkInt) {
          return StarlarkInt.uminus((StarlarkInt) x); // -int
        } else if (x instanceof StarlarkFloat) {
          return StarlarkFloat.of(-((StarlarkFloat) x).toDouble()); // -float
        }
        break;

      case PLUS:
        if (x instanceof StarlarkInt) {
          return x; // +int
        } else if (x instanceof StarlarkFloat) {
          return x; // +float
        }
        break;

      case TILDE:
        if (x instanceof StarlarkInt) {
          return StarlarkInt.bitnot((StarlarkInt) x); // ~int
        }
        break;

      default:
        /* fall through */
    }
    throw Starlark.errorf("unsupported unary operation: %s%s", op, Starlark.type(x));
  }

  /**
   * Returns the element of sequence or mapping {@code object} indexed by {@code key}.
   *
   * @throws EvalException if {@code object} is not a sequence or mapping.
   */
  @Nullable
  static Object index(StarlarkThread starlarkThread, Object object, Object key)
      throws EvalException {
    Mutability mu = starlarkThread.mutability();
    StarlarkSemantics semantics = starlarkThread.getSemantics();

    if (object instanceof StarlarkIndexable.Threaded) {
      return ((StarlarkIndexable.Threaded) object).getIndex(starlarkThread, semantics, key);
    } else if (object instanceof StarlarkIndexable) {
      Object result = ((StarlarkIndexable) object).getIndex(semantics, key);
      // TODO(bazel-team): We shouldn't have this fromJava call here. If it's needed at all,
      // it should go in the implementations of StarlarkIndexable#getIndex that produce non-Starlark
      // values.
      return result == null ? null : Starlark.fromJava(result, mu);
    } else if (object instanceof String string) {
      int index = Starlark.toInt(key, "string index");
      index = getSequenceIndex(index, string.length());
      return StringModule.memoizedCharToString(string.charAt(index));
    } else {
      throw Starlark.errorf(
          "type '%s' has no operator [](%s)", Starlark.type(object), Starlark.type(key));
    }
  }

  /**
   * Updates an object as if by the Starlark statement {@code object[key] = value}.
   *
   * @throws EvalException if the object is not a list or dict.
   */
  static void setIndex(Object object, Object key, Object value) throws EvalException {
    if (object instanceof Dict) {
      @SuppressWarnings("unchecked")
      Dict<Object, Object> dict = (Dict<Object, Object>) object;
      dict.putEntry(key, value);

    } else if (object instanceof StarlarkList) {
      @SuppressWarnings("unchecked")
      StarlarkList<Object> list = (StarlarkList<Object>) object;
      int index = Starlark.toInt(key, "list index");
      index = EvalUtils.getSequenceIndex(index, list.size());
      list.setElementAt(index, value);

    } else {
      throw Starlark.errorf(
          "can only assign an element in a dictionary or a list, not in a '%s'",
          Starlark.type(object));
    }
  }

  /** Updates the named field of x as if by the Starlark statement {@code x.field = value}. */
  static void setField(Object x, String field, Object value) throws EvalException {
    if (x instanceof Structure) {
      ((Structure) x).setField(field, value);
    } else {
      throw Starlark.errorf("cannot set .%s field of %s value", field, Starlark.type(x));
    }
  }
}
