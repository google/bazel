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

package net.starlark.java.syntax;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.FormatMethod;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Parser is a recursive-descent parser for Starlark. */
final class Parser {

  /** Combines the parser result into a single value object. */
  static final class ParseResult {
    // Maps char offsets in the file to Locations.
    final FileLocations locs;

    /** The top-level statements of the parsed file. */
    final ImmutableList<Statement> statements;

    /** The comments from the parsed file. */
    final ImmutableList<Comment> comments;

    // Errors encountered during scanning or parsing.
    // These lists are ultimately owned by StarlarkFile.
    final List<SyntaxError> errors;

    private ParseResult(
        FileLocations locs,
        ImmutableList<Statement> statements,
        ImmutableList<Comment> comments,
        List<SyntaxError> errors) {
      this.locs = locs;
      // No need to copy here; when the object is created, the parser instance is just about to go
      // out of scope and be garbage collected.
      this.statements = Preconditions.checkNotNull(statements);
      this.comments = Preconditions.checkNotNull(comments);
      this.errors = errors;
    }
  }

  private static final EnumSet<TokenKind> STATEMENT_TERMINATOR_SET =
      EnumSet.of(TokenKind.EOF, TokenKind.NEWLINE, TokenKind.SEMI);

  private static final EnumSet<TokenKind> LIST_TERMINATOR_SET =
      EnumSet.of(TokenKind.EOF, TokenKind.RBRACKET, TokenKind.SEMI);

  private static final EnumSet<TokenKind> DICT_TERMINATOR_SET =
      EnumSet.of(TokenKind.EOF, TokenKind.RBRACE, TokenKind.SEMI);

  private static final EnumSet<TokenKind> EXPR_LIST_TERMINATOR_SET =
      EnumSet.of(
          TokenKind.EOF,
          TokenKind.NEWLINE,
          TokenKind.EQUALS,
          TokenKind.RBRACE,
          TokenKind.RBRACKET,
          TokenKind.RPAREN,
          TokenKind.SEMI);

  private static final EnumSet<TokenKind> EXPR_TERMINATOR_SET =
      EnumSet.of(
          TokenKind.COLON,
          TokenKind.COMMA,
          TokenKind.EOF,
          TokenKind.FOR,
          TokenKind.MINUS,
          TokenKind.PERCENT,
          TokenKind.PLUS,
          TokenKind.RBRACKET,
          TokenKind.RPAREN,
          TokenKind.SLASH);

  /** Current lookahead token. May be mutated by the parser. */
  private final Lexer token; // token.kind is a prettier alias for lexer.kind

  private static final boolean DEBUGGING = false;

  private final Lexer lexer;
  private final FileLocations locs;
  private final List<SyntaxError> errors;

  // TODO(adonovan): opt: compute this by subtraction.
  private static final Map<TokenKind, TokenKind> augmentedAssignments =
      new ImmutableMap.Builder<TokenKind, TokenKind>()
          .put(TokenKind.PLUS_EQUALS, TokenKind.PLUS)
          .put(TokenKind.MINUS_EQUALS, TokenKind.MINUS)
          .put(TokenKind.STAR_EQUALS, TokenKind.STAR)
          .put(TokenKind.SLASH_EQUALS, TokenKind.SLASH)
          .put(TokenKind.SLASH_SLASH_EQUALS, TokenKind.SLASH_SLASH)
          .put(TokenKind.PERCENT_EQUALS, TokenKind.PERCENT)
          .put(TokenKind.AMPERSAND_EQUALS, TokenKind.AMPERSAND)
          .put(TokenKind.CARET_EQUALS, TokenKind.CARET)
          .put(TokenKind.PIPE_EQUALS, TokenKind.PIPE)
          .put(TokenKind.GREATER_GREATER_EQUALS, TokenKind.GREATER_GREATER)
          .put(TokenKind.LESS_LESS_EQUALS, TokenKind.LESS_LESS)
          .buildOrThrow();

  /**
   * Highest precedence goes last. Based on:
   * http://docs.python.org/2/reference/expressions.html#operator-precedence
   */
  private static final List<EnumSet<TokenKind>> operatorPrecedence =
      ImmutableList.of(
          EnumSet.of(TokenKind.OR),
          EnumSet.of(TokenKind.AND),
          EnumSet.of(TokenKind.NOT),
          EnumSet.of(
              TokenKind.EQUALS_EQUALS,
              TokenKind.NOT_EQUALS,
              TokenKind.LESS,
              TokenKind.LESS_EQUALS,
              TokenKind.GREATER,
              TokenKind.GREATER_EQUALS,
              TokenKind.IN,
              TokenKind.NOT_IN),
          EnumSet.of(TokenKind.PIPE),
          EnumSet.of(TokenKind.CARET),
          EnumSet.of(TokenKind.AMPERSAND),
          EnumSet.of(TokenKind.GREATER_GREATER, TokenKind.LESS_LESS),
          EnumSet.of(TokenKind.MINUS, TokenKind.PLUS),
          EnumSet.of(TokenKind.SLASH, TokenKind.SLASH_SLASH, TokenKind.STAR, TokenKind.PERCENT));

  private int errorsCount;
  private boolean recoveryMode;  // stop reporting errors until next statement

  // Intern string literals, as some files contain many literals for the same string.
  //
  // Ideally we would move this to the lexer, where we already do interning of identifiers. However,
  // the parser has a special case optimization for concatenation of string literals, which the
  // lexer can't handle.
  private final Map<String, String> stringInterner = new HashMap<>();

  private Parser(Lexer lexer, List<SyntaxError> errors) {
    this.lexer = lexer;
    this.locs = lexer.locs;
    this.errors = errors;
    this.token = lexer;
    nextToken();
  }

  private String intern(String s) {
    String prev = stringInterner.putIfAbsent(s, s);
    return prev != null ? prev : s;
  }

  // Returns a token's string form as used in error messages.
  private static String tokenString(TokenKind kind, @Nullable Object value) {
    return kind == TokenKind.STRING
        ? "\"" + value + "\"" // TODO(adonovan): do proper quotation
        : value == null ? kind.toString() : value.toString();
  }

  // Main entry point for parsing a file.
  static ParseResult parseFile(ParserInput input, FileOptions options) {
    List<SyntaxError> errors = new ArrayList<>();
    Lexer lexer = new Lexer(input, errors, options);
    Parser parser = new Parser(lexer, errors);

    StarlarkFile.ParseProfiler profiler = Parser.profiler;
    long profileStartNanos = profiler != null ? profiler.start() : -1;
    try {
      ImmutableList<Statement> statements = parser.parseFileInput();
      return new ParseResult(lexer.locs, statements, lexer.getComments(), errors);
    } finally {
      if (profileStartNanos != -1) {
        profiler.end(profileStartNanos, input.getFile());
      }
    }
  }

  @Nullable static StarlarkFile.ParseProfiler profiler;

  // stmt = simple_stmt
  //      | def_stmt
  //      | for_stmt
  //      | if_stmt
  private void parseStatement(ImmutableList.Builder<Statement> list) {
    if (token.kind == TokenKind.DEF) {
      list.add(parseDefStatement());
    } else if (token.kind == TokenKind.IF) {
      list.add(parseIfStatement());
    } else if (token.kind == TokenKind.FOR) {
      list.add(parseForStatement());
    } else {
      parseSimpleStatement(list);
    }
  }

  /** Parses an expression, possibly followed by newline tokens. */
  static Expression parseExpression(ParserInput input, FileOptions options)
      throws SyntaxError.Exception {
    List<SyntaxError> errors = new ArrayList<>();
    Lexer lexer = new Lexer(input, errors, options);
    Parser parser = new Parser(lexer, errors);
    Expression result = null;
    try {
      result = parser.parseExpression();
      while (parser.token.kind == TokenKind.NEWLINE) {
        parser.nextToken();
      }
      parser.expect(TokenKind.EOF);
    } catch (StackOverflowError ex) {
      // See rationale at parseFile.
      parser.reportError(
          lexer.end,
          "internal error: stack overflow while parsing Starlark expression <<%s>>. Please report"
              + " the bug.\n"
              + "%s",
          new String(input.getContent()),
          Throwables.getStackTraceAsString(ex));
    }
    if (!errors.isEmpty()) {
      throw new SyntaxError.Exception(errors);
    }
    return result;
  }

  // Equivalent to 'testlist' rule in Python grammar. It can parse every kind of
  // expression. In many cases, we need to use parseTest to avoid ambiguity:
  //   e.g. fct(x, y)  vs  fct((x, y))
  //
  // A trailing comma is disallowed in an unparenthesized tuple.
  // This prevents bugs where a one-element tuple is surprisingly created:
  //   e.g. foo = f(x),
  private Expression parseExpression() {
    Expression e = parseTest();
    if (token.kind != TokenKind.COMMA) {
      return e;
    }

    // unparenthesized tuple
    List<Expression> elems = new ArrayList<>();
    elems.add(e);
    parseExprList(elems, /*trailingCommaAllowed=*/ false);
    return new ListExpression(locs, /*isTuple=*/ true, -1, elems, -1);
  }

  @FormatMethod
  private void reportError(int offset, String format, Object... args) {
    errorsCount++;
    // Limit the number of reported errors to avoid spamming output.
    if (errorsCount <= 5) {
      Location location = locs.getLocation(offset);
      errors.add(new SyntaxError(location, String.format(format, args)));
    }
  }

  private void syntaxError(String message) {
    if (!recoveryMode) {
      if (token.kind == TokenKind.INDENT) {
        reportError(token.start, "indentation error");
      } else {
        reportError(
            token.start, "syntax error at '%s': %s", tokenString(token.kind, token.value), message);
      }
      recoveryMode = true;
    }
  }

  // Consumes the current token and returns its position, like nextToken.
  // Reports a syntax error if the new token is not of the expected kind.
  private int expect(TokenKind kind) {
    if (token.kind != kind) {
      syntaxError("expected " + kind);
    }
    return nextToken();
  }

  // Like expect, but stops recovery mode if the token was expected.
  private int expectAndRecover(TokenKind kind) {
    if (token.kind != kind) {
      syntaxError("expected " + kind);
    } else {
      recoveryMode = false;
    }
    return nextToken();
  }

  // Consumes tokens past the first token belonging to terminatingTokens.
  // It returns the end offset of the terminating token.
  // TODO(adonovan): always used with makeErrorExpression. Combine and simplify.
  private int syncPast(EnumSet<TokenKind> terminatingTokens) {
    Preconditions.checkState(terminatingTokens.contains(TokenKind.EOF));
    while (!terminatingTokens.contains(token.kind)) {
      nextToken();
    }
    int end = token.end;
    // read past the synchronization token
    nextToken();
    return end;
  }

  /**
   * Consume tokens until we reach the first token that has a kind that is in
   * the set of terminatingTokens.
   * @param terminatingTokens
   * @return the end offset of the terminating token.
   */
  private int syncTo(EnumSet<TokenKind> terminatingTokens) {
    // EOF must be in the set to prevent an infinite loop
    Preconditions.checkState(terminatingTokens.contains(TokenKind.EOF));
    // read past the problematic token
    int previous = token.end;
    nextToken();
    int current = previous;
    while (!terminatingTokens.contains(token.kind)) {
      nextToken();
      previous = current;
      current = token.end;
    }
    return previous;
  }

  // Keywords that exist in Python and that we don't parse.
  private static final EnumSet<TokenKind> FORBIDDEN_KEYWORDS =
      EnumSet.of(
          TokenKind.AS,
          TokenKind.ASSERT,
          TokenKind.CLASS,
          TokenKind.DEL,
          TokenKind.EXCEPT,
          TokenKind.FINALLY,
          TokenKind.FROM,
          TokenKind.GLOBAL,
          TokenKind.IMPORT,
          TokenKind.IS,
          TokenKind.NONLOCAL,
          TokenKind.RAISE,
          TokenKind.TRY,
          TokenKind.WITH,
          TokenKind.WHILE,
          TokenKind.YIELD);

  private void checkForbiddenKeywords() {
    if (!FORBIDDEN_KEYWORDS.contains(token.kind)) {
      return;
    }
    String error;
    switch (token.kind) {
      case ASSERT: error = "'assert' not supported, use 'fail' instead"; break;
      case DEL:
        error = "'del' not supported, use '.pop()' to delete an item from a dictionary or a list";
        break;
      case IMPORT: error = "'import' not supported, use 'load' instead"; break;
      case IS: error = "'is' not supported, use '==' instead"; break;
      case RAISE: error = "'raise' not supported, use 'fail' instead"; break;
      case TRY: error = "'try' not supported, all exceptions are fatal"; break;
      case WHILE: error = "'while' not supported, use 'for' instead"; break;
      default:
        error = "keyword '" + token.kind + "' not supported";
        break;
    }
    reportError(token.start, "%s", error);
  }

  private int nextToken() {
    int prev = token.start;
    if (token.kind != TokenKind.EOF) {
      lexer.nextToken();
    }
    checkForbiddenKeywords();
    // TODO(adonovan): move this to lexer so we see the first token too.
    if (DEBUGGING) {
      System.err.print(tokenString(token.kind, token.value));
    }
    return prev;
  }

  // Returns an "Identifier" whose content is the input from start to end.
  private Identifier makeErrorExpression(int start, int end) {
    // It's tempting to define a dedicated BadExpression type,
    // but it is convenient for parseIdent to return an Identifier
    // even when it fails.
    return new Identifier(locs, lexer.bufferSlice(start, end), start);
  }


  // arg = IDENTIFIER '=' test
  //     | expr
  //     | *args
  //     | **kwargs
  private Argument parseArgument() {
    Expression expr;

    // parse **expr
    if (token.kind == TokenKind.STAR_STAR) {
      int starStarOffset = nextToken();
      expr = parseTest();
      return new Argument.StarStar(locs, starStarOffset, expr);
    }

    // parse *expr
    if (token.kind == TokenKind.STAR) {
      int starOffset = nextToken();
      expr = parseTest();
      return new Argument.Star(locs, starOffset, expr);
    }

    // IDENTIFIER  or  IDENTIFIER = test
    expr = parseTest();
    if (expr instanceof Identifier id) {
      // parse a named argument
      if (token.kind == TokenKind.EQUALS) {
        nextToken();
        Expression arg = parseTest();
        return new Argument.Keyword(locs, id, arg);
      }
    }

    // parse a positional argument
    return new Argument.Positional(locs, expr);
  }

  // arg = IDENTIFIER '=' test
  //     | IDENTIFIER
  private Parameter parseParameter() {
    // **kwargs
    if (token.kind == TokenKind.STAR_STAR) {
      int starStarOffset = nextToken();
      Identifier id = parseIdent();
      return new Parameter.StarStar(locs, starStarOffset, id);
    }

    // * or *args
    if (token.kind == TokenKind.STAR) {
      int starOffset = nextToken();
      if (token.kind == TokenKind.IDENTIFIER) {
        Identifier id = parseIdent();
        return new Parameter.Star(locs, starOffset, id);
      }
      return new Parameter.Star(locs, starOffset, null);
    }

    // name=default
    Identifier id = parseIdent();
    if (token.kind == TokenKind.EQUALS) {
      nextToken(); // TODO: save token pos?
      Expression expr = parseTest();
      return new Parameter.Optional(locs, id, expr);
    }

    // name
    return new Parameter.Mandatory(locs, id);
  }

  // call_suffix = '(' arg_list? ')'
  private Expression parseCallSuffix(Expression fn) {
    ImmutableList<Argument> args = ImmutableList.of();
    int lparenOffset = expect(TokenKind.LPAREN);
    if (token.kind != TokenKind.RPAREN) {
      args = parseArguments(); // (includes optional trailing comma)
    }
    int rparenOffset = expect(TokenKind.RPAREN);
    return new CallExpression(locs, fn, locs.getLocation(lparenOffset), args, rparenOffset);
  }

  // Parse a list of call arguments.
  //
  // arg_list = ( (arg ',')* arg ','? )?
  private ImmutableList<Argument> parseArguments() {
    boolean seenArg = false;
    ImmutableList.Builder<Argument> list = ImmutableList.builder();
    while (token.kind != TokenKind.RPAREN && token.kind != TokenKind.EOF) {
      if (seenArg) {
        // f(expr for vars in expr) -- Python generator expression?
        if (token.kind == TokenKind.FOR) {
          syntaxError("Starlark does not support Python-style generator expressions");
        }
        expect(TokenKind.COMMA);
        // If nonempty, the list may end with a comma.
        if (token.kind == TokenKind.RPAREN) {
          break;
        }
      }
      list.add(parseArgument());
      seenArg = true;
    }
    return list.build();
  }

  // selector_suffix = '.' IDENTIFIER
  private Expression parseSelectorSuffix(Expression e) {
    int dotOffset = expect(TokenKind.DOT);
    if (token.kind == TokenKind.IDENTIFIER) {
      Identifier id = parseIdent();
      return new DotExpression(locs, e, dotOffset, id);
    }

    syntaxError("expected identifier after dot");
    syncTo(EXPR_TERMINATOR_SET);
    return e;
  }

  // expr_list parses a comma-separated list of expression. It assumes that the
  // first expression was already parsed, so it starts with a comma.
  // It is used to parse tuples and list elements.
  //
  // expr_list = ( ',' expr )* ','?
  private void parseExprList(List<Expression> list, boolean trailingCommaAllowed) {
    //  terminating tokens for an expression list
    while (token.kind == TokenKind.COMMA) {
      expect(TokenKind.COMMA);
      if (EXPR_LIST_TERMINATOR_SET.contains(token.kind)) {
        if (!trailingCommaAllowed) {
          reportError(token.start, "Trailing comma is allowed only in parenthesized tuples.");
        }
        break;
      }
      list.add(parseTest());
    }
  }

  // dict_entry_list = ( (dict_entry ',')* dict_entry ','? )?
  private List<DictExpression.Entry> parseDictEntryList() {
    List<DictExpression.Entry> list = new ArrayList<>();
    // the terminating token for a dict entry list
    while (token.kind != TokenKind.RBRACE) {
      list.add(parseDictEntry());
      if (token.kind == TokenKind.COMMA) {
        nextToken();
      } else {
        break;
      }
    }
    return list;
  }

  // dict_entry = test ':' test
  private DictExpression.Entry parseDictEntry() {
    Expression key = parseTest();
    int colonOffset = expect(TokenKind.COLON);
    Expression value = parseTest();
    return new DictExpression.Entry(locs, key, colonOffset, value);
  }

  // expr = STRING
  private StringLiteral parseStringLiteral() {
    Preconditions.checkState(token.kind == TokenKind.STRING);
    StringLiteral literal =
        new StringLiteral(locs, token.start, intern((String) token.value), token.end);
    nextToken();
    if (token.kind == TokenKind.STRING) {
      reportError(token.start, "Implicit string concatenation is forbidden, use the + operator");
    }
    return literal;
  }

  //  primary = INT
  //          | FLOAT
  //          | STRING
  //          | IDENTIFIER
  //          | list_expression
  //          | '(' ')'                    // a tuple with zero elements
  //          | '(' expr ')'               // a parenthesized expression
  //          | dict_expression
  //          | '-' primary_with_suffix
  private Expression parsePrimary() {
    switch (token.kind) {
      case INT:
        {
          IntLiteral literal =
              new IntLiteral(locs, token.getRaw(), token.start, (Number) token.value);
          nextToken();
          return literal;
        }

      case FLOAT:
        {
          FloatLiteral literal =
              new FloatLiteral(locs, token.getRaw(), token.start, (double) token.value);
          nextToken();
          return literal;
        }

      case STRING:
        return parseStringLiteral();

      case IDENTIFIER:
        return parseIdent();

      case LBRACKET: // [...]
        return parseListMaker();

      case LBRACE: // {...}
        return parseDictExpression();

      case LPAREN:
        {
          int lparenOffset = nextToken();

          // empty tuple: ()
          if (token.kind == TokenKind.RPAREN) {
            int rparen = nextToken();
            return new ListExpression(
                locs, /*isTuple=*/ true, lparenOffset, ImmutableList.of(), rparen);
          }

          Expression e = parseTest();

          // parenthesized expression: (e)
          // TODO(adonovan): materialize paren expressions (for fidelity).
          if (token.kind == TokenKind.RPAREN) {
            nextToken();
            return e;
          }

          // non-empty tuple: (e,) or (e, ..., e)
          if (token.kind == TokenKind.COMMA) {
            List<Expression> elems = new ArrayList<>();
            elems.add(e);
            parseExprList(elems, /*trailingCommaAllowed=*/ true);
            int rparenOffset = expect(TokenKind.RPAREN);
            return new ListExpression(locs, /*isTuple=*/ true, lparenOffset, elems, rparenOffset);
          }

          // (expr for vars in expr) -- Python generator expression?
          if (token.kind == TokenKind.FOR) {
            syntaxError("Starlark does not support Python-style generator expressions");
          }

          expect(TokenKind.RPAREN);
          int end = syncTo(EXPR_TERMINATOR_SET);
          return makeErrorExpression(lparenOffset, end);
        }

      case MINUS:
      case PLUS:
      case TILDE:
        {
          TokenKind op = token.kind;
          int offset = nextToken();
          Expression x = parsePrimaryWithSuffix();
          return new UnaryOperatorExpression(locs, op, offset, x);
        }

      default:
        {
          int start = token.start;
          syntaxError("expected expression");
          int end = syncTo(EXPR_TERMINATOR_SET);
          return makeErrorExpression(start, end);
        }
    }
  }

  // primary_with_suffix = primary (selector_suffix | slice_suffix | call_suffix)*
  private Expression parsePrimaryWithSuffix() {
    Expression e = parsePrimary();
    while (true) {
      if (token.kind == TokenKind.DOT) {
        e = parseSelectorSuffix(e);
      } else if (token.kind == TokenKind.LBRACKET) {
        e = parseSliceSuffix(e);
      } else if (token.kind == TokenKind.LPAREN) {
        e = parseCallSuffix(e);
      } else {
        return e;
      }
    }
  }

  // slice_suffix = '[' expr? ':' expr?  ':' expr? ']'
  //              | '[' expr? ':' expr? ']'
  //              | '[' expr ']'
  private Expression parseSliceSuffix(Expression e) {
    int lbracketOffset = expect(TokenKind.LBRACKET);
    Expression start = null;
    Expression end = null;
    Expression step = null;

    if (token.kind != TokenKind.COLON) {
      start = parseExpression();

      // index x[i]
      if (token.kind == TokenKind.RBRACKET) {
        int rbracketOffset = expect(TokenKind.RBRACKET);
        return new IndexExpression(locs, e, lbracketOffset, start, rbracketOffset);
      }
    }

    // slice or substring x[i:j] or x[i:j:k]
    expect(TokenKind.COLON);
    if (token.kind != TokenKind.COLON && token.kind != TokenKind.RBRACKET) {
      end = parseTest();
    }
    if (token.kind == TokenKind.COLON) {
      expect(TokenKind.COLON);
      if (token.kind != TokenKind.RBRACKET) {
        step = parseTest();
      }
    }
    int rbracketOffset = expect(TokenKind.RBRACKET);
    return new SliceExpression(locs, e, lbracketOffset, start, end, step, rbracketOffset);
  }

  // Equivalent to 'exprlist' rule in Python grammar.
  // loop_variables = primary_with_suffix ( ',' primary_with_suffix )* ','?
  private Expression parseForLoopVariables() {
    // We cannot reuse parseExpression because it would parse the 'in' operator.
    // e.g.  "for i in e: pass"  -> we want to parse only "i" here.
    Expression e1 = parsePrimaryWithSuffix();
    if (token.kind != TokenKind.COMMA) {
      return e1;
    }

    // unparenthesized tuple
    List<Expression> elems = new ArrayList<>();
    elems.add(e1);
    while (token.kind == TokenKind.COMMA) {
      expect(TokenKind.COMMA);
      if (EXPR_LIST_TERMINATOR_SET.contains(token.kind)) {
        break;
      }
      elems.add(parsePrimaryWithSuffix());
    }
    return new ListExpression(locs, /*isTuple=*/ true, -1, elems, -1);
  }

  // comprehension_suffix = 'FOR' loop_variables 'IN' expr comprehension_suffix
  //                      | 'IF' expr comprehension_suffix
  //                      | ']' | '}'
  private Expression parseComprehensionSuffix(int loffset, Node body, TokenKind closingBracket) {
    ImmutableList.Builder<Comprehension.Clause> clauses = ImmutableList.builder();
    while (true) {
      if (token.kind == TokenKind.FOR) {
        int forOffset = nextToken();
        Expression vars = parseForLoopVariables();
        expect(TokenKind.IN);
        // The expression cannot be a ternary expression ('x if y else z') due to
        // conflicts in Python grammar ('if' is used by the comprehension).
        Expression seq = parseTest(0);
        clauses.add(new Comprehension.For(locs, forOffset, vars, seq));
      } else if (token.kind == TokenKind.IF) {
        int ifOffset = nextToken();
        // [x for x in li if 1, 2]  # parse error
        // [x for x in li if (1, 2)]  # ok
        Expression cond = parseTestNoCond();
        clauses.add(new Comprehension.If(locs, ifOffset, cond));
      } else if (token.kind == closingBracket) {
        break;
      } else {
        syntaxError("expected '" + closingBracket + "', 'for' or 'if'");
        int end = syncPast(LIST_TERMINATOR_SET);
        return makeErrorExpression(loffset, end);
      }
    }

    boolean isDict = closingBracket == TokenKind.RBRACE;
    int roffset = expect(closingBracket);
    return new Comprehension(locs, isDict, loffset, body, clauses.build(), roffset);
  }

  // list_maker = '[' ']'
  //            | '[' expr ']'
  //            | '[' expr expr_list ']'
  //            | '[' expr comprehension_suffix ']'
  private Expression parseListMaker() {
    int lbracketOffset = expect(TokenKind.LBRACKET);
    if (token.kind == TokenKind.RBRACKET) { // empty List
      int rbracketOffset = nextToken();
      return new ListExpression(
          locs, /*isTuple=*/ false, lbracketOffset, ImmutableList.of(), rbracketOffset);
    }

    Expression expression = parseTest();
    switch (token.kind) {
      case RBRACKET:
        // [e], singleton list
        {
          int rbracketOffset = nextToken();
          return new ListExpression(
              locs,
              /*isTuple=*/ false,
              lbracketOffset,
              ImmutableList.of(expression),
              rbracketOffset);
        }

      case FOR:
        // [e for x in y], list comprehension
        return parseComprehensionSuffix(lbracketOffset, expression, TokenKind.RBRACKET);

      case COMMA:
        // [e, ...], list expression
        {
          List<Expression> elems = new ArrayList<>();
          elems.add(expression);
          parseExprList(elems, /*trailingCommaAllowed=*/ true);
          if (token.kind == TokenKind.RBRACKET) {
            int rbracketOffset = nextToken();
            return new ListExpression(
                locs, /*isTuple=*/ false, lbracketOffset, elems, rbracketOffset);
          }

          expect(TokenKind.RBRACKET);
          int end = syncPast(LIST_TERMINATOR_SET);
          return makeErrorExpression(lbracketOffset, end);
        }

      default:
        {
          syntaxError("expected ',', 'for' or ']'");
          int end = syncPast(LIST_TERMINATOR_SET);
          return makeErrorExpression(lbracketOffset, end);
        }
    }
  }

  // dict_expression = '{' '}'
  //                 | '{' dict_entry_list '}'
  //                 | '{' dict_entry comprehension_suffix '}'
  private Expression parseDictExpression() {
    int lbraceOffset = expect(TokenKind.LBRACE);
    if (token.kind == TokenKind.RBRACE) { // empty Dict
      int rbraceOffset = nextToken();
      return new DictExpression(locs, lbraceOffset, ImmutableList.of(), rbraceOffset);
    }

    DictExpression.Entry entry = parseDictEntry();
    if (token.kind == TokenKind.FOR) {
      // Dict comprehension
      return parseComprehensionSuffix(lbraceOffset, entry, TokenKind.RBRACE);
    }

    List<DictExpression.Entry> entries = new ArrayList<>();
    entries.add(entry);
    if (token.kind == TokenKind.COMMA) {
      expect(TokenKind.COMMA);
      entries.addAll(parseDictEntryList());
    }
    if (token.kind == TokenKind.RBRACE) {
      int rbraceOffset = nextToken();
      return new DictExpression(locs, lbraceOffset, entries, rbraceOffset);
    }

    expect(TokenKind.RBRACE);
    int end = syncPast(DICT_TERMINATOR_SET);
    return makeErrorExpression(lbraceOffset, end);
  }

  private Identifier parseIdent() {
    if (token.kind != TokenKind.IDENTIFIER) {
      int start = token.start;
      int end = expect(TokenKind.IDENTIFIER);
      return makeErrorExpression(start, end);
    }

    String name = (String) token.value;
    int offset = nextToken();
    return new Identifier(locs, name, offset);
  }

  // binop_expression = binop_expression OP binop_expression
  //                  | parsePrimaryWithSuffix
  // This function takes care of precedence between operators (see operatorPrecedence for
  // the order), and it assumes left-to-right associativity.
  private Expression parseBinOpExpression(int prec) {
    Expression x = parseTest(prec + 1);
    // The loop is not strictly needed, but it prevents risks of stack overflow. Depth is
    // limited to number of different precedence levels (operatorPrecedence.size()).
    TokenKind lastOp = null;
    for (;;) {
      if (token.kind == TokenKind.NOT) {
        // If NOT appears when we expect a binary operator, it must be followed by IN.
        // Since the code expects every operator to be a single token, we push a NOT_IN token.
        expect(TokenKind.NOT);
        if (token.kind != TokenKind.IN) {
          syntaxError("expected 'in'");
        }
        token.kind = TokenKind.NOT_IN;
      }

      TokenKind op = token.kind;
      if (!operatorPrecedence.get(prec).contains(op)) {
        return x;
      }

      // Operator '==' and other operators of the same precedence (e.g. '<', 'in')
      // are not associative.
      if (lastOp != null && operatorPrecedence.get(prec).contains(TokenKind.EQUALS_EQUALS)) {
        reportError(
            token.start,
            "Operator '%s' is not associative with operator '%s'. Use parens.",
            lastOp,
            op);
      }

      int opOffset = nextToken();
      Expression y = parseTest(prec + 1);
      x = optimizeBinOpExpression(x, op, opOffset, y);
      lastOp = op;
    }
  }

  // Optimize binary expressions.
  // string literal + string literal can be concatenated into one string literal
  // so we don't have to do the expensive string concatenation at runtime.
  private Expression optimizeBinOpExpression(
      Expression x, TokenKind op, int opOffset, Expression y) {
    if (op == TokenKind.PLUS && x instanceof StringLiteral && y instanceof StringLiteral) {
      return new StringLiteral(
          locs,
          x.getStartOffset(),
          intern(((StringLiteral) x).getValue() + ((StringLiteral) y).getValue()),
          y.getEndOffset());
    }
    return new BinaryOperatorExpression(locs, x, op, opOffset, y);
  }

  // Parses a non-tuple expression ("test" in Python terminology).
  private Expression parseTest() {
    int start = token.start;
    if (token.kind == TokenKind.LAMBDA) {
      return parseLambda(/*allowCond=*/ true);
    }

    Expression expr = parseTest(0);
    if (token.kind == TokenKind.IF) {
      nextToken();
      Expression condition = parseTest(0);
      if (token.kind == TokenKind.ELSE) {
        nextToken();
        Expression elseClause = parseTest();
        return new ConditionalExpression(locs, expr, condition, elseClause);
      } else {
        reportError(start, "missing else clause in conditional expression or semicolon before if");
        return expr; // Try to recover from error: drop the if and the expression after it. Ouch.
      }
    }
    return expr;
  }

  private Expression parseTest(int prec) {
    if (prec >= operatorPrecedence.size()) {
      return parsePrimaryWithSuffix();
    }
    if (token.kind == TokenKind.NOT && operatorPrecedence.get(prec).contains(TokenKind.NOT)) {
      return parseNotExpression(prec);
    }
    return parseBinOpExpression(prec);
  }

  // parseLambda parses a lambda expression.
  // The allowCond flag allows the body to be an 'a if b else c' conditional.
  private LambdaExpression parseLambda(boolean allowCond) {
    int lambdaOffset = expect(TokenKind.LAMBDA);
    ImmutableList<Parameter> params = parseParameters();
    expect(TokenKind.COLON);
    Expression body = allowCond ? parseTest() : parseTestNoCond();
    return new LambdaExpression(locs, lambdaOffset, params, body);
  }

  // parseTestNoCond parses a single-component expression without
  // consuming a trailing 'if expr else expr'.
  private Expression parseTestNoCond() {
    if (token.kind == TokenKind.LAMBDA) {
      return parseLambda(/*allowCond=*/ false);
    }
    return parseTest(0);
  }

  // not_expr = 'not' expr
  private Expression parseNotExpression(int prec) {
    int notOffset = expect(TokenKind.NOT);
    Expression x = parseTest(prec);
    return new UnaryOperatorExpression(locs, TokenKind.NOT, notOffset, x);
  }

  // file_input = ('\n' | stmt)* EOF
  private ImmutableList<Statement> parseFileInput() {
    ImmutableList.Builder<Statement> list = ImmutableList.builder();
    try {
      while (token.kind != TokenKind.EOF) {
        if (token.kind == TokenKind.NEWLINE) {
          expectAndRecover(TokenKind.NEWLINE);
        } else if (recoveryMode) {
          // If there was a parse error, we want to recover here
          // before starting a new top-level statement.
          syncTo(STATEMENT_TERMINATOR_SET);
          recoveryMode = false;
        } else {
          parseStatement(list);
        }
      }
    } catch (StackOverflowError ex) {
      // JVM threads have very limited stack, and deeply nested inputs can
      // easily cause the parser to consume all available stack. It is hard
      // to anticipate all the possible recursions in the parser, especially
      // when considering error recovery. Consider a long list of dicts:
      // even if the intended parse tree has a depth of only two,
      // if each dict contains a syntax error, the parser will go into recovery
      // and may discard each dict's closing '}', turning a shallow tree
      // into a deep one (see b/157470754).
      //
      // So, for robustness, the parser treats StackOverflowError as a parse
      // error, exhorting the user to report a bug.
      reportError(
          token.end,
          "internal error: stack overflow in Starlark parser. Please report the bug and include"
              + " the text of %s.\n"
              + "%s",
          locs.file(),
          Throwables.getStackTraceAsString(ex));
    }
    return list.build();
  }

  // load '(' STRING (COMMA [IDENTIFIER EQUALS] STRING)+ COMMA? ')'
  private Statement parseLoadStatement() {
    int loadOffset = expect(TokenKind.LOAD);
    expect(TokenKind.LPAREN);
    if (token.kind != TokenKind.STRING) {
      // error: module is not a string literal.
      StringLiteral module = new StringLiteral(locs, token.start, "", token.end);
      expect(TokenKind.STRING);
      return new LoadStatement(locs, loadOffset, module, ImmutableList.of(), token.end);
    }

    StringLiteral module = parseStringLiteral();
    if (token.kind == TokenKind.RPAREN) {
      syntaxError("expected at least one symbol to load");
      return new LoadStatement(locs, loadOffset, module, ImmutableList.of(), token.end);
    }
    expect(TokenKind.COMMA);

    ImmutableList.Builder<LoadStatement.Binding> bindings = ImmutableList.builder();
    // At least one symbol is required.
    parseLoadSymbol(bindings);
    while (token.kind != TokenKind.RPAREN && token.kind != TokenKind.EOF) {
      // A trailing comma is permitted after the last symbol.
      expect(TokenKind.COMMA);
      if (token.kind == TokenKind.RPAREN) {
        break;
      }
      parseLoadSymbol(bindings);
    }

    int rparen = expect(TokenKind.RPAREN);
    return new LoadStatement(locs, loadOffset, module, bindings.build(), rparen);
  }

  /**
   * Parses the next symbol argument of a load statement and puts it into the output map.
   *
   * <p>The symbol is either "name" (STRING) or name = "declared" (IDENTIFIER EQUALS STRING). If no
   * alias is used, "name" and "declared" will be identical. "Declared" refers to the original name
   * in the Bazel file that should be loaded, while "name" will be the key of the entry in the map.
   */
  private void parseLoadSymbol(ImmutableList.Builder<LoadStatement.Binding> symbols) {
    if (token.kind != TokenKind.STRING && token.kind != TokenKind.IDENTIFIER) {
      syntaxError("expected either a literal string or an identifier");
      return;
    }

    String name = (String) token.value;
    int nameOffset = token.start + (token.kind == TokenKind.STRING ? 1 : 0);
    Identifier local = new Identifier(locs, name, nameOffset);

    Identifier original;
    if (token.kind == TokenKind.STRING) {
      // load(..., "name")
      original = local;
    } else {
      // load(..., local = "orig")
      // The name "orig" is morally an identifier but, for legacy reasons (specifically,
      // a partial implementation of Starlark embedded in a Python interpreter used by
      // tests of Blaze), it must be a quoted string literal.
      expect(TokenKind.IDENTIFIER);
      expect(TokenKind.EQUALS);
      if (token.kind != TokenKind.STRING) {
        syntaxError("expected string");
        return;
      }
      original = new Identifier(locs, (String) token.value, token.start + 1);
    }
    nextToken();
    symbols.add(new LoadStatement.Binding(local, original));
  }

  // simple_stmt = small_stmt (';' small_stmt)* ';'? NEWLINE
  private void parseSimpleStatement(ImmutableList.Builder<Statement> list) {
    list.add(parseSmallStatement());

    while (token.kind == TokenKind.SEMI) {
      nextToken();
      if (token.kind == TokenKind.NEWLINE) {
        break;
      }
      list.add(parseSmallStatement());
    }
    expectAndRecover(TokenKind.NEWLINE);
  }

  //     small_stmt = assign_stmt
  //                | expr
  //                | load_stmt
  //                | return_stmt
  //                | BREAK | CONTINUE | PASS
  //
  //     assign_stmt = expr ('=' | augassign) expr
  //
  //     augassign = '+=' | '-=' | '*=' | '/=' | '%=' | '//=' | '&=' | '|=' | '^=' |'<<=' | '>>='
  private Statement parseSmallStatement() {
    // return
    if (token.kind == TokenKind.RETURN) {
      return parseReturnStatement();
    }

    // control flow
    if (token.kind == TokenKind.BREAK
        || token.kind == TokenKind.CONTINUE
        || token.kind == TokenKind.PASS) {
      TokenKind kind = token.kind;
      int offset = nextToken();
      return new FlowStatement(locs, kind, offset);
    }

    // load
    if (token.kind == TokenKind.LOAD) {
      return parseLoadStatement();
    }

    Expression lhs = parseExpression();

    // lhs = rhs  or  lhs += rhs
    TokenKind op = augmentedAssignments.get(token.kind);
    if (token.kind == TokenKind.EQUALS || op != null) {
      int opOffset = nextToken();
      Expression rhs = parseExpression();
      // op == null for ordinary assignment. TODO(adonovan): represent as EQUALS.
      return new AssignmentStatement(locs, lhs, op, opOffset, rhs);
    } else {
      return new ExpressionStatement(locs, lhs);
    }
  }

  // if_stmt = IF expr ':' suite [ELIF expr ':' suite]* [ELSE ':' suite]?
  private IfStatement parseIfStatement() {
    int ifOffset = expect(TokenKind.IF);
    Expression cond = parseTest();
    expect(TokenKind.COLON);
    ImmutableList<Statement> body = parseSuite();
    IfStatement ifStmt = new IfStatement(locs, TokenKind.IF, ifOffset, cond, body);
    IfStatement tail = ifStmt;
    while (token.kind == TokenKind.ELIF) {
      int elifOffset = expect(TokenKind.ELIF);
      cond = parseTest();
      expect(TokenKind.COLON);
      body = parseSuite();
      IfStatement elif = new IfStatement(locs, TokenKind.ELIF, elifOffset, cond, body);
      tail.setElseBlock(ImmutableList.of(elif));
      tail = elif;
    }
    if (token.kind == TokenKind.ELSE) {
      expect(TokenKind.ELSE);
      expect(TokenKind.COLON);
      body = parseSuite();
      tail.setElseBlock(body);
    }
    return ifStmt;
  }

  // for_stmt = FOR IDENTIFIER IN expr ':' suite
  private ForStatement parseForStatement() {
    int forOffset = expect(TokenKind.FOR);
    Expression vars = parseForLoopVariables();
    expect(TokenKind.IN);
    Expression collection = parseExpression();
    expect(TokenKind.COLON);
    ImmutableList<Statement> body = parseSuite();
    return new ForStatement(locs, forOffset, vars, collection, body);
  }

  // def_stmt = DEF IDENTIFIER '(' arguments ')' ':' suite
  private DefStatement parseDefStatement() {
    int defOffset = expect(TokenKind.DEF);
    Identifier ident = parseIdent();
    expect(TokenKind.LPAREN);
    ImmutableList<Parameter> params = parseParameters();
    expect(TokenKind.RPAREN);
    expect(TokenKind.COLON);
    ImmutableList<Statement> block = parseSuite();
    return new DefStatement(locs, defOffset, ident, params, block);
  }

  // Parse a list of function parameters.
  // Validation of parameter ordering and uniqueness is the job of the Resolver.
  private ImmutableList<Parameter> parseParameters() {
    boolean hasParam = false;
    ImmutableList.Builder<Parameter> list = ImmutableList.builder();

    while (token.kind != TokenKind.RPAREN
        && token.kind != TokenKind.COLON
        && token.kind != TokenKind.EOF) {
      if (hasParam) {
        expect(TokenKind.COMMA);
        // The list may end with a comma.
        if (token.kind == TokenKind.RPAREN) {
          break;
        }
      }
      Parameter param = parseParameter();
      hasParam = true;
      list.add(param);
    }
    return list.build();
  }

  // suite is typically what follows a colon (e.g. after def or for).
  // suite = simple_stmt
  //       | NEWLINE INDENT stmt+ OUTDENT
  private ImmutableList<Statement> parseSuite() {
    ImmutableList.Builder<Statement> list = ImmutableList.builder();
    if (token.kind == TokenKind.NEWLINE) {
      expect(TokenKind.NEWLINE);
      if (token.kind != TokenKind.INDENT) {
        reportError(token.start, "expected an indented block");
        return list.build();
      }
      expect(TokenKind.INDENT);
      while (token.kind != TokenKind.OUTDENT && token.kind != TokenKind.EOF) {
        parseStatement(list);
      }
      expectAndRecover(TokenKind.OUTDENT);
    } else {
      parseSimpleStatement(list);
    }
    return list.build();
  }

  // return_stmt = RETURN [expr]
  private ReturnStatement parseReturnStatement() {
    int returnOffset = expect(TokenKind.RETURN);

    Expression result = null;
    if (!STATEMENT_TERMINATOR_SET.contains(token.kind)) {
      result = parseExpression();
    }
    return new ReturnStatement(locs, returnOffset, result);
  }
}
