// Copyright 2019 The Bazel Authors. All rights reserved.
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
package net.starlark.java.syntax;

import java.util.List;

/** A pretty-printer for Starlark syntax trees. */
final class NodePrinter {

  private final StringBuilder buf;
  private int indent;

  NodePrinter(StringBuilder buf) {
    this.buf = buf;
  }

  // Constructor exposed to legacy tests.
  // TODO(adonovan): rewrite the tests not to care about the indent parameter.
  NodePrinter(StringBuilder buf, int indent) {
    this.buf = buf;
    this.indent = indent;
  }

  // Main entry point for an arbitrary node.
  // Called by Node.prettyPrint.
  void printNode(Node n) {
    if (n instanceof Expression) {
      printExpr((Expression) n);

    } else if (n instanceof Statement) {
      printStmt((Statement) n);

    } else if (n instanceof StarlarkFile file) {
      // Only statements are printed, not comments.
      for (Statement stmt : file.getStatements()) {
        printStmt(stmt);
      }

    } else if (n instanceof Comment comment) {
      // We can't really print comments in the right place anyway,
      // due to how their relative order is lost in the representation
      // of StarlarkFile. So don't bother word-wrapping and just print
      // it on a single line.
      printIndent();
      buf.append(comment.getText());

    } else if (n instanceof Argument) {
      printArgument((Argument) n);

    } else if (n instanceof Parameter) {
      printParameter((Parameter) n);

    } else if (n instanceof DictExpression.Entry) {
      printDictEntry((DictExpression.Entry) n);

    } else {
      throw new IllegalArgumentException("unexpected: " + n.getClass());
    }
  }

  private void printSuite(List<Statement> statements) {
    // A suite is non-empty; pass statements are explicit.
    indent++;
    for (Statement stmt : statements) {
      printStmt(stmt);
    }
    indent--;
  }

  private void printIndent() {
    for (int i = 0; i < indent; i++) {
      buf.append("  ");
    }
  }

  private void printArgument(Argument arg) {
    if (arg instanceof Argument.Positional) {
      // nop
    } else if (arg instanceof Argument.Keyword) {
      buf.append(((Argument.Keyword) arg).getIdentifier().getName());
      buf.append(" = ");
    } else if (arg instanceof Argument.Star) {
      buf.append('*');
    } else if (arg instanceof Argument.StarStar) {
      buf.append("**");
    }
    printExpr(arg.getValue());
  }

  private void printParameter(Parameter param) {
    if (param instanceof Parameter.Mandatory) {
      buf.append(param.getName());
    } else if (param instanceof Parameter.Optional) {
      buf.append(param.getName());
      buf.append('=');
      printExpr(param.getDefaultValue());
    } else if (param instanceof Parameter.Star) {
      buf.append('*');
      if (param.getName() != null) {
        buf.append(param.getName());
      }
    } else if (param instanceof Parameter.StarStar) {
      buf.append("**");
      buf.append(param.getName());
    }
  }

  private void printDictEntry(DictExpression.Entry e) {
    printExpr(e.getKey());
    buf.append(": ");
    printExpr(e.getValue());
  }

  // Appends "def f(a, ..., z):" to the buf.
  // Also used by DefStatement.toString.
  void printDefSignature(DefStatement def) {
    buf.append("def ");
    printExpr(def.getIdentifier());
    buf.append('(');
    String sep = "";
    for (Parameter param : def.getParameters()) {
      buf.append(sep);
      printParameter(param);
      sep = ", ";
    }
    buf.append("):");
  }

  private void printStmt(Statement s) {
    printIndent();

    switch (s.kind()) {
      case ASSIGNMENT:
        {
          AssignmentStatement stmt = (AssignmentStatement) s;
          printExpr(stmt.getLHS());
          buf.append(' ');
          if (stmt.isAugmented()) {
            buf.append(stmt.getOperator());
          }
          buf.append("= ");
          printExpr(stmt.getRHS());
          buf.append('\n');
          break;
        }

      case EXPRESSION:
        {
          ExpressionStatement stmt = (ExpressionStatement) s;
          printExpr(stmt.getExpression());
          buf.append('\n');
          break;
        }

      case FLOW:
        {
          FlowStatement stmt = (FlowStatement) s;
          buf.append(stmt.getFlowKind()).append('\n');
          break;
        }

      case FOR:
        {
          ForStatement stmt = (ForStatement) s;
          buf.append("for ");
          printExpr(stmt.getVars());
          buf.append(" in ");
          printExpr(stmt.getCollection());
          buf.append(":\n");
          printSuite(stmt.getBody());
          break;
        }

      case DEF:
        {
          DefStatement stmt = (DefStatement) s;
          printDefSignature(stmt);
          buf.append('\n');
          printSuite(stmt.getBody());
          break;
        }

      case IF:
        {
          IfStatement stmt = (IfStatement) s;
          buf.append(stmt.isElif() ? "elif " : "if ");
          printExpr(stmt.getCondition());
          buf.append(":\n");
          printSuite(stmt.getThenBlock());
          List<Statement> elseBlock = stmt.getElseBlock();
          if (elseBlock != null) {
            if (elseBlock.size() == 1
                && elseBlock.get(0) instanceof IfStatement
                && ((IfStatement) elseBlock.get(0)).isElif()) {
              printStmt(elseBlock.get(0));
            } else {
              printIndent();
              buf.append("else:\n");
              printSuite(elseBlock);
            }
          }
          break;
        }

      case LOAD:
        {
          LoadStatement stmt = (LoadStatement) s;
          buf.append("load(");
          printExpr(stmt.getImport());
          for (LoadStatement.Binding binding : stmt.getBindings()) {
            buf.append(", ");
            Identifier local = binding.getLocalName();
            String origName = binding.getOriginalName().getName();
            if (origName.equals(local.getName())) {
              buf.append('"');
              printExpr(local);
              buf.append('"');
            } else {
              printExpr(local);
              buf.append("=\"");
              buf.append(origName);
              buf.append('"');
            }
          }
          buf.append(")\n");
          break;
        }

      case RETURN:
        {
          ReturnStatement stmt = (ReturnStatement) s;
          buf.append("return");
          if (stmt.getResult() != null) {
            buf.append(' ');
            printExpr(stmt.getResult());
          }
          buf.append('\n');
          break;
        }
    }
  }

  private void printExpr(Expression expr) {
    switch (expr.kind()) {
      case BINARY_OPERATOR:
        {
          BinaryOperatorExpression binop = (BinaryOperatorExpression) expr;
          // TODO(bazel-team): retain parentheses in the syntax tree so we needn't
          // conservatively emit them here.
          buf.append('(');
          printExpr(binop.getX());
          buf.append(' ');
          buf.append(binop.getOperator());
          buf.append(' ');
          printExpr(binop.getY());
          buf.append(')');
          break;
        }

      case COMPREHENSION:
        {
          Comprehension comp = (Comprehension) expr;
          buf.append(comp.isDict() ? '{' : '[');
          printNode(comp.getBody()); // Expression or DictExpression.Entry
          for (Comprehension.Clause clause : comp.getClauses()) {
            buf.append(' ');
            if (clause instanceof Comprehension.For forClause) {
              buf.append("for ");
              printExpr(forClause.getVars());
              buf.append(" in ");
              printExpr(forClause.getIterable());
            } else {
              Comprehension.If ifClause = (Comprehension.If) clause;
              buf.append("if ");
              printExpr(ifClause.getCondition());
            }
          }
          buf.append(comp.isDict() ? '}' : ']');
          break;
        }

      case CONDITIONAL:
        {
          ConditionalExpression cond = (ConditionalExpression) expr;
          printExpr(cond.getThenCase());
          buf.append(" if ");
          printExpr(cond.getCondition());
          buf.append(" else ");
          printExpr(cond.getElseCase());
          break;
        }

      case DICT_EXPR:
        {
          DictExpression dictexpr = (DictExpression) expr;
          buf.append("{");
          String sep = "";
          for (DictExpression.Entry entry : dictexpr.getEntries()) {
            buf.append(sep);
            printDictEntry(entry);
            sep = ", ";
          }
          buf.append("}");
          break;
        }

      case DOT:
        {
          DotExpression dot = (DotExpression) expr;
          printExpr(dot.getObject());
          buf.append('.');
          printExpr(dot.getField());
          break;
        }

      case CALL:
        {
          CallExpression call = (CallExpression) expr;
          printExpr(call.getFunction());
          buf.append('(');
          String sep = "";
          for (Argument arg : call.getArguments()) {
            buf.append(sep);
            printArgument(arg);
            sep = ", ";
          }
          buf.append(')');
          break;
        }

      case IDENTIFIER:
        buf.append(((Identifier) expr).getName());
        break;

      case INDEX:
        {
          IndexExpression index = (IndexExpression) expr;
          printExpr(index.getObject());
          buf.append('[');
          printExpr(index.getKey());
          buf.append(']');
          break;
        }

      case INT_LITERAL:
        {
          buf.append(((IntLiteral) expr).getValue());
          break;
        }

      case FLOAT_LITERAL:
        {
          buf.append(((FloatLiteral) expr).getValue());
          break;
        }

      case LAMBDA:
        {
          LambdaExpression lambda = (LambdaExpression) expr;
          buf.append("lambda");
          String sep = " ";
          for (Parameter param : lambda.getParameters()) {
            buf.append(sep);
            sep = ", ";
            printParameter(param);
          }
          buf.append(": ");
          printExpr(lambda.getBody());
          break;
        }

      case LIST_EXPR:
        {
          ListExpression list = (ListExpression) expr;
          buf.append(list.isTuple() ? '(' : '[');
          String sep = "";
          for (Expression e : list.getElements()) {
            buf.append(sep);
            printExpr(e);
            sep = ", ";
          }
          if (list.isTuple() && list.getElements().size() == 1) {
            buf.append(',');
          }
          buf.append(list.isTuple() ? ')' : ']');
          break;
        }

      case SLICE:
        {
          SliceExpression slice = (SliceExpression) expr;
          printExpr(slice.getObject());
          buf.append('[');
          // The first separator colon is unconditional.
          // The second separator appears only if step is printed.
          if (slice.getStart() != null) {
            printExpr(slice.getStart());
          }
          buf.append(':');
          if (slice.getStop() != null) {
            printExpr(slice.getStop());
          }
          if (slice.getStep() != null) {
            buf.append(':');
            printExpr(slice.getStep());
          }
          buf.append(']');
          break;
        }

      case STRING_LITERAL:
        {
          StringLiteral literal = (StringLiteral) expr;
          String value = literal.getValue();

          // TODO(adonovan): record the raw text of string (and integer) literals
          // so that we can use the syntax tree for source modification tools.
          // However, that may come with a memory cost until we start compiling
          // (at which point the cost is only transient).
          // For now, just simulate the behavior of repr(str).
          buf.append('"');
          for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
              case '"':
                buf.append("\\\"");
                break;
              case '\\':
                buf.append("\\\\");
                break;
              case '\r':
                buf.append("\\r");
                break;
              case '\n':
                buf.append("\\n");
                break;
              case '\t':
                buf.append("\\t");
                break;
              default:
                // The Starlark spec (and lexer) are far from complete here,
                // and it's hard to come up with a clean semantics for
                // string escapes that serves Java (UTF-16) and Go (UTF-8).
                // Clearly string literals should not contain non-printable
                // characters. For now we'll continue to pretend that all
                // non-printables are < 32, but this obviously false.
                if (c < 32) {
                  buf.append(String.format("\\x%02x", (int) c));
                } else {
                  buf.append(c);
                }
            }
          }
          buf.append('"');
          break;
        }

      case UNARY_OPERATOR:
        {
          UnaryOperatorExpression unop = (UnaryOperatorExpression) expr;
          // TODO(bazel-team): retain parentheses in the syntax tree so we needn't
          // conservatively emit them here.
          buf.append(unop.getOperator() == TokenKind.NOT ? "not " : unop.getOperator().toString());
          buf.append('(');
          printExpr(unop.getX());
          buf.append(')');
          break;
        }
    }
  }
}
