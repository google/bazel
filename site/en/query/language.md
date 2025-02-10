Project: /_project.yaml
Book: /_book.yaml

# The Bazel Query Reference

{% include "_buttons.html" %}

This page is the reference manual for the _Bazel Query Language_ used
when you use `bazel query` to analyze build dependencies. It also
describes the output formats `bazel query` supports.

For practical use cases, see the [Bazel Query How-To](/query/guide).

## Additional query reference

In addition to `query`, which runs on the post-loading phase target graph,
Bazel includes *action graph query* and *configurable query*.

### Action graph query {:#aquery}

The action graph query (`aquery`) operates on the post-analysis Configured
Target Graph and exposes information about **Actions**, **Artifacts**, and
their relationships. `aquery` is useful when you are interested in the
properties of the Actions/Artifacts generated from the Configured Target Graph.
For example, the actual commands run and their inputs, outputs, and mnemonics.

For more details, see the [aquery reference](/query/aquery).

### Configurable query {:#cquery}

Traditional Bazel query runs on the post-loading phase target graph and
therefore has no concept of configurations and their related concepts. Notably,
it doesn't correctly resolve [select statements](/reference/be/functions#select)
and instead returns all possible resolutions of selects. However, the
configurable query environment, `cquery`, properly handles configurations but
doesn't provide all of the functionality of this original query.

For more details, see the [cquery reference](/query/cquery).


## Examples {:#examples}

How do people use `bazel query`?  Here are typical examples:

Why does the `//foo` tree depend on `//bar/baz`?
Show a path:

```
somepath(foo/..., //bar/baz:all)
```

What C++ libraries do all the `foo` tests depend on that
the `foo_bin` target does not?

```
kind("cc_library", deps(kind(".*test rule", foo/...)) except deps(//foo:foo_bin))
```

## Tokens: The lexical syntax {:#tokens}

Expressions in the query language are composed of the following
tokens:

* **Keywords**, such as `let`. Keywords are the reserved words of the
  language, and each of them is described below. The complete set
  of keywords is:

   * [`except`](#set-operations)

   * [`in`](#variables)

   * [`intersect`](#set-operations)

   * [`let`](#variables)

   * [`set`](#set)

   * [`union`](#set-operations)

* **Words**, such as "`foo/...`" or "`.*test rule`" or "`//bar/baz:all`". If a
  character sequence is "quoted" (begins and ends with a single-quote ' or
  begins and ends with a double-quote "), it is a word. If a character sequence
  is not quoted, it may still be parsed as a word. Unquoted words are sequences
  of characters drawn from the alphabet characters A-Za-z, the numerals 0-9,
  and the special characters `*/@.-_:$~[]` (asterisk, forward slash, at, period,
  hyphen, underscore, colon, dollar sign, tilde, left square brace, right square
  brace). However, unquoted words may not start with a hyphen `-` or asterisk `*`
  even though relative [target names](/concepts/labels#target-names) may start
  with those characters. As a special rule meant to simplify the handling of
  labels referring to external repositories, unquoted words that start with
  `@@` may contain `+` characters.

  Unquoted words also may not include the characters plus sign `+` or equals
  sign `=`, even though those characters are permitted in target names. When
  writing code that generates query expressions, target names should be quoted.

  Quoting _is_ necessary when writing scripts that construct Bazel query
  expressions from user-supplied values.

  ```
   //foo:bar+wiz    # WRONG: scanned as //foo:bar + wiz.
   //foo:bar=wiz    # WRONG: scanned as //foo:bar = wiz.
   "//foo:bar+wiz"  # OK.
   "//foo:bar=wiz"  # OK.
  ```

  Note that this quoting is in addition to any quoting that may be required by
  your shell, such as:

  ```posix-terminal
  bazel query ' "//foo:bar=wiz" '   # single-quotes for shell, double-quotes for Bazel.
  ```

  Keywords and operators, when quoted, are treated as ordinary words. For example, `some` is a
  keyword but "some" is a word. Both `foo` and "foo" are words.

  However, be careful when using single or double quotes in target names. When
  quoting one or more target names, use only one type of quotes (either all
  single or all double quotes).

  The following are examples of what the Java query string will be:


  ```
    'a"'a'         # WRONG: Error message: unclosed quotation.
    "a'"a"         # WRONG: Error message: unclosed quotation.
    '"a" + 'a''    # WRONG: Error message: unexpected token 'a' after query expression '"a" + '
    "'a' + "a""    # WRONG: Error message: unexpected token 'a' after query expression ''a' + '
    "a'a"          # OK.
    'a"a'          # OK.
    '"a" + "a"'    # OK
    "'a' + 'a'"    # OK
  ```

  We chose this syntax so that quote marks aren't needed in most cases. The
  (unusual) `".*test rule"` example needs quotes: it starts with a period and
  contains a space. Quoting `"cc_library"` is unnecessary but harmless.

* **Punctuation**, such as parens `()`, period `.` and comma `,`. Words
  containing punctuation (other than the exceptions listed above) must be quoted.

Whitespace characters outside of a quoted word are ignored.

## Bazel query language concepts {:#language-concepts}

The Bazel query language is a language of expressions. Every
expression evaluates to a **partially-ordered set** of targets,
or equivalently, a **graph** (DAG) of targets. This is the only
datatype.

Set and graph refer to the same datatype, but emphasize different
aspects of it, for example:

*   **Set:** The partial order of the targets is not interesting.
*   **Graph:** The partial order of targets is significant.

### Cycles in the dependency graph {:#dependency-graph-cycles}

Build dependency graphs should be acyclic.

The algorithms used by the query language are intended for use in
acyclic graphs, but are robust against cycles. The details of how
cycles are treated are not specified and should not be relied upon.

### Implicit dependencies {:#implicit-dependencies}

In addition to build dependencies that are defined explicitly in `BUILD` files,
Bazel adds additional _implicit_ dependencies to rules. Implicit dependencies
may be defined by:

- [Private attributes](/extending/rules#private_attributes_and_implicit_dependencies)
- [Toolchain requirements](/extending/toolchains#writing-rules-toolchains)

By default, `bazel query` takes implicit dependencies into account
when computing the query result. This behavior can be changed with
the `--[no]implicit_deps` option.

Note that, as query does not consider configurations, potential toolchain
**implementations** are not considered dependencies, only the
required toolchain types. See
[toolchain documentation](/extending/toolchains#writing-rules-toolchains).

### Soundness {:#soundness}

Bazel query language expressions operate over the build
dependency graph, which is the graph implicitly defined by all
rule declarations in all `BUILD` files. It is important to understand
that this graph is somewhat abstract, and does not constitute a
complete description of how to perform all the steps of a build. In
order to perform a build, a _configuration_ is required too;
see the [configurations](/docs/user-manual#configurations)
section of the User's Guide for more detail.

The result of evaluating an expression in the Bazel query language
is true _for all configurations_, which means that it may be
a conservative over-approximation, and not exactly precise. If you
use the query tool to compute the set of all source files needed
during a build, it may report more than are actually necessary
because, for example, the query tool will include all the files
needed to support message translation, even though you don't intend
to use that feature in your build.

### On the preservation of graph order {:#graph-order}

Operations preserve any ordering
constraints inherited from their subexpressions. You can think of
this as "the law of conservation of partial order". Consider an
example: if you issue a query to determine the transitive closure of
dependencies of a particular target, the resulting set is ordered
according to the dependency graph. If you filter that set to
include only the targets of `file` kind, the same
_transitive_ partial ordering relation holds between every
pair of targets in the resulting subset - even though none of
these pairs is actually directly connected in the original graph.
(There are no file-file edges in the build dependency graph).

However, while all operators _preserve_ order, some
operations, such as the [set operations](#set-operations)
don't _introduce_ any ordering constraints of their own.
Consider this expression:

```
deps(x) union y
```

The order of the final result set is guaranteed to preserve all the
ordering constraints of its subexpressions, namely, that all the
transitive dependencies of `x` are correctly ordered with
respect to each other. However, the query guarantees nothing about
the ordering of the targets in `y`, nor about the
ordering of the targets in `deps(x)` relative to those in
`y` (except for those targets in
`y` that also happen to be in `deps(x)`).

Operators that introduce ordering constraints include:
`allpaths`, `deps`, `rdeps`, `somepath`, and the target pattern wildcards
`package:*`, `dir/...`, etc.

### Sky query {:#sky-query}

_Sky Query_ is a mode of query that operates over a specified _universe scope_.

#### Special functions available only in SkyQuery

Sky Query mode has the additional query functions `allrdeps` and
`rbuildfiles`. These functions operate over the entire
universe scope (which is why they don't make sense for normal Query).

#### Specifying a universe scope

Sky Query mode is activated by passing the following two flags:
(`--universe_scope` or `--infer_universe_scope`) and
`--order_output=no`.
`--universe_scope=<target_pattern1>,...,<target_patternN>` tells query to
preload the transitive closure of the target pattern specified by the target patterns, which can
be both additive and subtractive. All queries are then evaluated in this "scope". In particular,
the [`allrdeps`](#allrdeps) and
[`rbuildfiles`](#rbuildfiles) operators only return results from this scope.
`--infer_universe_scope` tells Bazel to infer a value for `--universe_scope`
from the query expression. This inferred value is the list of unique target patterns in the
query expression, but this might not be what you want. For example:

```posix-terminal
bazel query --infer_universe_scope --order_output=no "allrdeps(//my:target)"
```

The list of unique target patterns in this query expression is `["//my:target"]`, so
Bazel treats this the same as the invocation:

```posix-terminal
bazel query --universe_scope=//my:target --order_output=no "allrdeps(//my:target)"
```

But the result of that query with `--universe_scope` is only `//my:target`;
none of the reverse dependencies of `//my:target` are in the universe, by
construction! On the other hand, consider:

```posix-terminal
bazel query --infer_universe_scope --order_output=no "tests(//a/... + b/...) intersect allrdeps(siblings(rbuildfiles(my/starlark/file.bzl)))"
```

This is a meaningful query invocation that is trying to compute the test targets in the
[`tests`](#tests) expansion of the targets under some directories that
transitively depend on targets whose definition uses a certain `.bzl` file. Here,
`--infer_universe_scope` is a convenience, especially in the case where the choice of
`--universe_scope` would otherwise require you to parse the query expression yourself.

So, for query expressions that use universe-scoped operators like
[`allrdeps`](#allrdeps) and
[`rbuildfiles`](#rbuildfiles) be sure to use
`--infer_universe_scope` only if its behavior is what you want.

Sky Query has some advantages and disadvantages compared to the default query. The main
disadvantage is that it cannot order its output according to graph order, and thus certain
[output formats](#output-formats) are forbidden. Its advantages are that it provides
two operators ([`allrdeps`](#allrdeps) and
[`rbuildfiles`](#rbuildfiles)) that are not available in the default query.
As well, Sky Query does its work by introspecting the
[Skyframe](/reference/skyframe) graph, rather than creating a new
graph, which is what the default implementation does. Thus, there are some circumstances in which
it is faster and uses less memory.

## Expressions: Syntax and semantics of the grammar {:#expressions}

This is the grammar of the Bazel query language, expressed in EBNF notation:

```none {:.devsite-disable-click-to-copy}
expr ::= {{ '<var>' }}word{{ '</var>' }}
       | let {{ '<var>' }}name{{ '</var>' }} = {{ '<var>' }}expr{{ '</var>' }} in {{ '<var>' }}expr{{ '</var>' }}
       | ({{ '<var>' }}expr{{ '</var>' }})
       | {{ '<var>' }}expr{{ '</var>' }} intersect {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} ^ {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} union {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} + {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} except {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} - {{ '<var>' }}expr{{ '</var>' }}
       | set({{ '<var>' }}word{{ '</var>' }} *)
       | {{ '<var>' }}word{{ '</var>' }} '(' {{ '<var>' }}int{{ '</var>' }} | {{ '<var>' }}word{{ '</var>' }} | {{ '<var>' }}expr{{ '</var>' }} ... ')'
```

The following sections describe each of the productions of this grammar in order.

### Target patterns {:#target-patterns}

```
expr ::= {{ '<var>' }}word{{ '</var>' }}
```

Syntactically, a _target pattern_ is just a word. It's interpreted as an
(unordered) set of targets. The simplest target pattern is a label, which
identifies a single target (file or rule). For example, the target pattern
`//foo:bar` evaluates to a set containing one element, the target, the `bar`
rule.

Target patterns generalize labels to include wildcards over packages and
targets. For example, `foo/...:all` (or just `foo/...`) is a target pattern
that evaluates to a set containing all _rules_ in every package recursively
beneath the `foo` directory; `bar/baz:all` is a target pattern that evaluates
to a set containing all the rules in the `bar/baz` package, but not its
subpackages.

Similarly, `foo/...:*` is a target pattern that evaluates to a set containing
all _targets_ (rules _and_ files) in every package recursively beneath the
`foo` directory; `bar/baz:*` evaluates to a set containing all the targets in
the `bar/baz` package, but not its subpackages.

Because the `:*` wildcard matches files as well as rules, it's often more
useful than `:all` for queries. Conversely, the `:all` wildcard (implicit in
target patterns like `foo/...`) is typically more useful for builds.

`bazel query` target patterns work the same as `bazel build` build targets do.
For more details, see [Target Patterns](/docs/user-manual#target-patterns), or
type `bazel help target-syntax`.

Target patterns may evaluate to a singleton set (in the case of a label), to a
set containing many elements (as in the case of `foo/...`, which has thousands
of elements) or to the empty set, if the target pattern matches no targets.

All nodes in the result of a target pattern expression are correctly ordered
relative to each other according to the dependency relation. So, the result of
`foo:*` is not just the set of targets in package `foo`, it is also the
_graph_ over those targets. (No guarantees are made about the relative ordering
of the result nodes against other nodes.) For more details, see the
[graph order](#graph-order) section.

### Variables {:#variables}

```none {:.devsite-disable-click-to-copy}
expr ::= let {{ '<var>' }}name{{ '</var>' }} = {{ '<var>' }}expr{{ '</var>' }}{{ '<sub>' }}1{{ '</sub>' }} in {{ '<var>' }}expr{{ '</var>' }}{{ '<sub>' }}2{{ '</sub>' }}
       | {{ '<var>' }}$name{{ '</var>' }}
```

The Bazel query language allows definitions of and references to
variables. The result of evaluation of a `let` expression is the same as
that of {{ '<var>' }}expr{{ '</var>' }}<sub>2</sub>, with all free occurrences
of variable {{ '<var>' }}name{{ '</var>' }} replaced by the value of
{{ '<var>' }}expr{{ '</var>' }}<sub>1</sub>.

For example, `let v = foo/... in allpaths($v, //common) intersect $v` is
equivalent to the `allpaths(foo/...,//common) intersect foo/...`.

An occurrence of a variable reference `name` other than in
an enclosing `let {{ '<var>' }}name{{ '</var>' }} = ...` expression is an
error. In other words, top-level query expressions cannot have free
variables.

In the above grammar productions, `name` is like _word_, but with the
additional constraint that it be a legal identifier in the C programming
language. References to the variable must be prepended with the "$" character.

Each `let` expression defines only a single variable, but you can nest them.

Both [target patterns](#target-patterns) and variable references consist of
just a single token, a word, creating a syntactic ambiguity. However, there is
no semantic ambiguity, because the subset of words that are legal variable
names is disjoint from the subset of words that are legal target patterns.

Technically speaking, `let` expressions do not increase
the expressiveness of the query language: any query expressible in
the language can also be expressed without them. However, they
improve the conciseness of many queries, and may also lead to more
efficient query evaluation.

### Parenthesized expressions {:#parenthesized-expressions}

```none {:.devsite-disable-click-to-copy}
expr ::= ({{ '<var>' }}expr{{ '</var>' }})
```

Parentheses associate subexpressions to force an order of evaluation.
A parenthesized expression evaluates to the value of its argument.

### Algebraic set operations: intersection, union, set difference {:#algebraic-set-operations}

```none {:.devsite-disable-click-to-copy}
expr ::= {{ '<var>' }}expr{{ '</var>' }} intersect {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} ^ {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} union {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} + {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} except {{ '<var>' }}expr{{ '</var>' }}
       | {{ '<var>' }}expr{{ '</var>' }} - {{ '<var>' }}expr{{ '</var>' }}
```

These three operators compute the usual set operations over their arguments.
Each operator has two forms, a nominal form, such as `intersect`, and a
symbolic form, such as `^`. Both forms are equivalent; the symbolic forms are
quicker to type. (For clarity, the rest of this page uses the nominal forms.)

For example,

```
foo/... except foo/bar/...
```

evaluates to the set of targets that match `foo/...` but not `foo/bar/...`.

You can write the same query as:

```
foo/... - foo/bar/...
```

The `intersect` (`^`) and `union` (`+`) operations are commutative (symmetric);
`except` (`-`) is asymmetric. The parser treats all three operators as
left-associative and of equal precedence, so you might want parentheses. For
example, the first two of these expressions are equivalent, but the third is not:

```
x intersect y union z
(x intersect y) union z
x intersect (y union z)
```

Important: Use parentheses where there is any danger of ambiguity in reading a
query expression.

### Read targets from an external source: set {:#set}

```none {:.devsite-disable-click-to-copy}
expr ::= set({{ '<var>' }}word{{ '</var>' }} *)
```

The `set({{ '<var>' }}a{{ '</var>' }} {{ '<var>' }}b{{ '</var>' }} {{ '<var>' }}c{{ '</var>' }} ...)`
operator computes the union of a set of zero or more
[target patterns](#target-patterns), separated by whitespace (no commas).

In conjunction with the Bourne shell's `$(...)` feature, `set()` provides a
means of saving the results of one query in a regular text file, manipulating
that text file using other programs (such as standard UNIX shell tools), and then
introducing the result back into the query tool as a value for further
processing. For example:

```posix-terminal
bazel query deps(//my:target) --output=label | grep ... | sed ... | awk ... > foo

bazel query "kind(cc_binary, set($(<foo)))"
```

In the next example,`kind(cc_library, deps(//some_dir/foo:main, 5))` is
computed by filtering on the `maxrank` values using an `awk` program.

```posix-terminal
bazel query 'deps(//some_dir/foo:main)' --output maxrank | awk '($1 < 5) { print $2;} ' > foo

bazel query "kind(cc_library, set($(<foo)))"
```

In these examples, `$(<foo)` is a shorthand for `$(cat foo)`, but shell
commands other than `cat` may be used too—such as the previous `awk` command.

Note: `set()` introduces no graph ordering constraints, so path information may
be lost when saving and reloading sets of nodes using it. For more details,
see the [graph order](#graph-order) section below.

## Functions {:#functions}

```none {:.devsite-disable-click-to-copy}
expr ::= {{ '<var>' }}word{{ '</var>' }} '(' {{ '<var>' }}int{{ '</var>' }} | {{ '<var>' }}word{{ '</var>' }} | {{ '<var>' }}expr{{ '</var>' }} ... ')'
```

The query language defines several functions. The name of the function
determines the number and type of arguments it requires. The following
functions are available:

* [`allpaths`](#somepath-allpaths)
* [`attr`](#attr)
* [`buildfiles`](#buildfiles)
* [`rbuildfiles`](#rbuildfiles)
* [`deps`](#deps)
* [`filter`](#filter)
* [`kind`](#kind)
* [`labels`](#labels)
* [`loadfiles`](#loadfiles)
* [`rdeps`](#rdeps)
* [`allrdeps`](#allrdeps)
* [`same_pkg_direct_rdeps`](#same_pkg_direct_rdeps)
* [`siblings`](#siblings)
* [`some`](#some)
* [`somepath`](#somepath-allpaths)
* [`tests`](#tests)
* [`visible`](#visible)



### Transitive closure of dependencies: deps {:#deps}

```none {:.devsite-disable-click-to-copy}
expr ::= deps({{ '<var>' }}expr{{ '</var>' }})
       | deps({{ '<var>' }}expr{{ '</var>' }}, {{ '<var>' }}depth{{ '</var>' }})
```

The `deps({{ '<var>' }}x{{ '</var>' }})` operator evaluates to the graph formed
by the transitive closure of dependencies of its argument set
{{ '<var>' }}x{{ '</var>' }}. For example, the value of `deps(//foo)` is the
dependency graph rooted at the single node `foo`, including all its
dependencies. The value of `deps(foo/...)` is the dependency graphs whose roots
are all rules in every package beneath the `foo` directory. In this context,
'dependencies' means only rule and file targets, therefore the `BUILD` and
Starlark files needed to create these targets are not included here. For that
you should use the [`buildfiles`](#buildfiles) operator.

The resulting graph is ordered according to the dependency relation. For more
details, see the section on [graph order](#graph-order).

The `deps` operator accepts an optional second argument, which is an integer
literal specifying an upper bound on the depth of the search. So
`deps(foo:*, 0)` returns all targets in the `foo` package, while
`deps(foo:*, 1)` further includes the direct prerequisites of any target in the
`foo` package, and `deps(foo:*, 2)` further includes the nodes directly
reachable from the nodes in `deps(foo:*, 1)`, and so on. (These numbers
correspond to the ranks shown in the [`minrank`](#output-ranked) output format.)
If the {{ '<var>' }}depth{{ '</var>' }} parameter is omitted, the search is
unbounded: it computes the reflexive transitive closure of prerequisites.

### Transitive closure of reverse dependencies: rdeps {:#rdeps}

```none {:.devsite-disable-click-to-copy}
expr ::= rdeps({{ '<var>' }}expr{{ '</var>' }}, {{ '<var>' }}expr{{ '</var>' }})
       | rdeps({{ '<var>' }}expr{{ '</var>' }}, {{ '<var>' }}expr{{ '</var>' }}, {{ '<var>' }}depth{{ '</var>' }})
```

The `rdeps({{ '<var>' }}u{{ '</var>' }}, {{ '<var>' }}x{{ '</var>' }})`
operator evaluates to the reverse dependencies of the argument set
{{ '<var>' }}x{{ '</var>' }} within the transitive closure of the universe set
{{ '<var>' }}u{{ '</var>' }}.

The resulting graph is ordered according to the dependency relation. See the
section on [graph order](#graph-order) for more details.

The `rdeps` operator accepts an optional third argument, which is an integer
literal specifying an upper bound on the depth of the search. The resulting
graph only includes nodes within a distance of the specified depth from any
node in the argument set. So `rdeps(//foo, //common, 1)` evaluates to all nodes
in the transitive closure of `//foo` that directly depend on `//common`. (These
numbers correspond to the ranks shown in the [`minrank`](#output-ranked) output
format.) If the {{ '<var>' }}depth{{ '</var>' }} parameter is omitted, the
search is unbounded.

### Transitive closure of all reverse dependencies: allrdeps {:#allrdeps}

```
expr ::= allrdeps({{ '<var>' }}expr{{ '</var>' }})
       | allrdeps({{ '<var>' }}expr{{ '</var>' }}, {{ '<var>' }}depth{{ '</var>' }})
```

Note: Only available with [Sky Query](#sky-query)

The `allrdeps` operator behaves just like the [`rdeps`](#rdeps)
operator, except that the "universe set" is whatever the `--universe_scope` flag
evaluated to, instead of being separately specified. Thus, if
`--universe_scope=//foo/...` was passed, then `allrdeps(//bar)` is
equivalent to `rdeps(//foo/..., //bar)`.

### Direct reverse dependencies in the same package: same_pkg_direct_rdeps {:#same_pkg_direct_rdeps}

```
expr ::= same_pkg_direct_rdeps({{ '<var>' }}expr{{ '</var>' }})
```

The `same_pkg_direct_rdeps({{ '<var>' }}x{{ '</var>' }})` operator evaluates to the full set of targets
that are in the same package as a target in the argument set, and which directly depend on it.

### Dealing with a target's package: siblings {:#siblings}

```
expr ::= siblings({{ '<var>' }}expr{{ '</var>' }})
```

The `siblings({{ '<var>' }}x{{ '</var>' }})` operator evaluates to the full set of targets that are in
the same package as a target in the argument set.

### Arbitrary choice: some {:#some}

```
expr ::= some({{ '<var>' }}expr{{ '</var>' }})
       | some({{ '<var>' }}expr{{ '</var>' }}, {{ '<var>' }}count{{ '</var> '}})
```

The `some({{ '<var>' }}x{{ '</var>' }}, {{ '<var>' }}k{{ '</var>' }})` operator
selects at most {{ '<var>' }}k{{ '</var>' }} targets arbitrarily from its
argument set {{ '<var>' }}x{{ '</var>' }}, and evaluates to a set containing
only those targets. Parameter {{ '<var>' }}k{{ '</var>' }} is optional; if
missing, the result will be a singleton set containing only one target
arbitrarily selected. If the size of argument set {{ '<var>' }}x{{ '</var>' }} is
smaller than {{ '<var>' }}k{{ '</var>' }}, the whole argument set
{{ '<var>' }}x{{ '</var>' }} will be returned.

For example, the expression `some(//foo:main union //bar:baz)` evaluates to a
singleton set containing either `//foo:main` or `//bar:baz`—though which
one is not defined. The expression `some(//foo:main union //bar:baz, 2)` or
`some(//foo:main union //bar:baz, 3)` returns both `//foo:main` and
`//bar:baz`.

If the argument is a singleton, then `some`
computes the identity function: `some(//foo:main)` is
equivalent to `//foo:main`.

It is an error if the specified argument set is empty, as in the
expression `some(//foo:main intersect //bar:baz)`.

### Path operators: somepath, allpaths {:#somepath-allpaths}

```
expr ::= somepath({{ '<var>' }}expr{{ '</var>' }}, {{ '<var>' }}expr{{ '</var>' }})
       | allpaths({{ '<var>' }}expr{{ '</var>' }}, {{ '<var>' }}expr{{ '</var>' }})
```

The `somepath({{ '<var>' }}S{{ '</var>' }}, {{ '<var>' }}E{{ '</var>' }})` and
`allpaths({{ '<var>' }}S{{ '</var>' }}, {{ '<var>' }}E{{ '</var>' }})` operators compute
paths between two sets of targets. Both queries accept two
arguments, a set {{ '<var>' }}S{{ '</var>' }} of starting points and a set
{{ '<var>' }}E{{ '</var>' }} of ending points. `somepath` returns the
graph of nodes on _some_ arbitrary path from a target in
{{ '<var>' }}S{{ '</var>' }} to a target in {{ '<var>' }}E{{ '</var>' }}; `allpaths`
returns the graph of nodes on _all_ paths from any target in
{{ '<var>' }}S{{ '</var>' }} to any target in {{ '<var>' }}E{{ '</var>' }}.

The resulting graphs are ordered according to the dependency relation.
See the section on [graph order](#graph-order) for more details.

<table>
  <tr>
    <td>
      <figure>
        <img src="/docs/images/somepath1.svg" alt="Somepath">
        <figcaption><code>somepath(S1 + S2, E)</code>, one possible result.</figcaption>
      </figure>
<!-- digraph somepath1 {
  graph [size="4,4"]
  node [label="",shape=circle];
  n1;
  n2 [fillcolor="pink",style=filled];
  n3 [fillcolor="pink",style=filled];
  n4 [fillcolor="pink",style=filled,label="E"];
  n5; n6;
  n7 [fillcolor="pink",style=filled,label="S1"];
  n8 [label="S2"];
  n9;
  n10 [fillcolor="pink",style=filled];
  n1 -> n2;
  n2 -> n3;
  n7 -> n5;
  n7 -> n2;
  n5 -> n6;
  n6 -> n4;
  n8 -> n6;
  n6 -> n9;
  n2 -> n10;
  n3 -> n10;
  n10 -> n4;
  n10 -> n11;
} -->
    </td>
    <td>
      <figure>
        <img src="/docs/images/somepath2.svg" alt="Somepath">
        <figcaption><code>somepath(S1 + S2, E)</code>, another possible result.</figcaption>
      </figure>
<!-- digraph somepath2 {
  graph [size="4,4"]
  node [label="",shape=circle];
  n1; n2; n3;
  n4 [fillcolor="pink",style=filled,label="E"];
  n5;
  n6 [fillcolor="pink",style=filled];
  n7 [label="S1"];
  n8 [fillcolor="pink",style=filled,label="S2"];
  n9; n10;
  n1 -> n2;
  n2 -> n3;
  n7 -> n5;
  n7 -> n2;
  n5 -> n6;
  n6 -> n4;
  n8 -> n6;
  n6 -> n9;
  n2 -> n10;
  n3 -> n10;
  n10 -> n4;
  n10 -> n11;
} -->
    </td>
    <td>
      <figure>
        <img src="/docs/images/allpaths.svg" alt="Allpaths">
        <figcaption><code>allpaths(S1 + S2, E)</code></figcaption>
      </figure>
<!-- digraph allpaths {
  graph [size="4,4"]
  node [label="",shape=circle];
  n1;
  n2 [fillcolor="pink",style=filled];
  n3 [fillcolor="pink",style=filled];
  n4 [fillcolor="pink",style=filled,label="E"];
  n5 [fillcolor="pink",style=filled];
  n6 [fillcolor="pink",style=filled];
  n7 [fillcolor="pink",style=filled, label="S1"];
  n8 [fillcolor="pink",style=filled, label="S2"];
  n9;
  n10 [fillcolor="pink",style=filled];
  n1 -> n2;
  n2 -> n3;
  n7 -> n5;
  n7 -> n2;
  n5 -> n6;
  n6 -> n4;
  n8 -> n6;
  n6 -> n9;
  n2 -> n10;
  n3 -> n10;
  n10 -> n4;
  n10 -> n11;
} -->
    </td>
  </tr>
</table>

### Target kind filtering: kind {:#kind}

```
expr ::= kind({{ '<var>' }}word{{ '</var>' }}, {{ '<var>' }}expr{{ '</var>' }})
```

The `kind({{ '<var>' }}pattern{{ '</var>' }}, {{ '<var>' }}input{{ '</var>' }})`
operator applies a filter to a set of targets, and discards those targets
that are not of the expected kind. The {{ '<var>' }}pattern{{ '</var>' }}
parameter specifies what kind of target to match.

For example, the kinds for the four targets defined by the `BUILD` file
(for package `p`) shown below are illustrated in the table:

<table>
  <tr>
    <th>Code</th>
    <th>Target</th>
    <th>Kind</th>
  </tr>
  <tr>
    <td rowspan="4">
      <pre>
        genrule(
            name = "a",
            srcs = ["a.in"],
            outs = ["a.out"],
            cmd = "...",
        )
      </pre>
    </td>
    <td><code>//p:a</code></td>
    <td>genrule rule</td>
  </tr>
  <tr>
    <td><code>//p:a.in</code></td>
    <td>source file</td>
  </tr>
  <tr>
    <td><code>//p:a.out</code></td>
    <td>generated file</td>
  </tr>
  <tr>
    <td><code>//p:BUILD</code></td>
    <td>source file</td>
  </tr>
</table>

Thus, `kind("cc_.* rule", foo/...)` evaluates to the set
of all `cc_library`, `cc_binary`, etc,
rule targets beneath `foo`, and `kind("source file", deps(//foo))`
evaluates to the set of all source files in the transitive closure
of dependencies of the `//foo` target.

Quotation of the {{ '<var>' }}pattern{{ '</var>' }} argument is often required
because without it, many [regular expressions](#regex), such as `source
file` and `.*_test`, are not considered words by the parser.

When matching for `package group`, targets ending in
`:all` may not yield any results. Use `:all-targets` instead.

### Target name filtering: filter {:#filter}

```
expr ::= filter({{ '<var>' }}word{{ '</var>' }}, {{ '<var>' }}expr{{ '</var>' }})
```

The `filter({{ '<var>' }}pattern{{ '</var>' }}, {{ '<var>' }}input{{ '</var>' }})`
operator applies a filter to a set of targets, and discards targets whose
labels (in absolute form) do not match the pattern; it
evaluates to a subset of its input.

The first argument, {{ '<var>' }}pattern{{ '</var>' }} is a word containing a
[regular expression](#regex) over target names. A `filter` expression
evaluates to the set containing all targets {{ '<var>' }}x{{ '</var>' }} such that
{{ '<var>' }}x{{ '</var>' }} is a member of the set {{ '<var>' }}input{{ '</var>' }} and the
label (in absolute form, such as `//foo:bar`)
of {{ '<var>' }}x{{ '</var>' }} contains an (unanchored) match
for the regular expression {{ '<var>' }}pattern{{ '</var>' }}. Since all
target names start with `//`, it may be used as an alternative
to the `^` regular expression anchor.

This operator often provides a much faster and more robust alternative to the
`intersect` operator. For example, in order to see all
`bar` dependencies of the `//foo:foo` target, one could
evaluate

```
deps(//foo) intersect //bar/...
```

This statement, however, will require parsing of all `BUILD` files in the
`bar` tree, which will be slow and prone to errors in
irrelevant `BUILD` files. An alternative would be:

```
filter(//bar, deps(//foo))
```

which would first calculate the set of `//foo` dependencies and
then would filter only targets matching the provided pattern—in other
words, targets with names containing `//bar` as a substring.

Another common use of the `filter({{ '<var>' }}pattern{{ '</var>' }},
{{ '<var>' }}expr{{ '</var>' }})` operator is to filter specific files by their
name or extension. For example,

```
filter("\.cc$", deps(//foo))
```

will provide a list of all `.cc` files used to build `//foo`.

### Rule attribute filtering: attr {:#attr}

```
expr ::= attr({{ '<var>' }}word{{ '</var>' }}, {{ '<var>' }}word{{ '</var>' }}, {{ '<var>' }}expr{{ '</var>' }})
```

The
`attr({{ '<var>' }}name{{ '</var>' }}, {{ '<var>' }}pattern{{ '</var>' }}, {{ '<var>' }}input{{ '</var>' }})`
operator applies a filter to a set of targets, and discards targets that aren't
rules, rule targets that do not have attribute {{ '<var>' }}name{{ '</var>' }}
defined or rule targets where the attribute value does not match the provided
[regular expression](#regex) {{ '<var>' }}pattern{{ '</var>' }}; it evaluates
to a subset of its input.

The first argument, {{ '<var>' }}name{{ '</var>' }} is the name of the rule
attribute that should be matched against the provided
[regular expression](#regex) pattern. The second argument,
{{ '<var>' }}pattern{{ '</var>' }} is a regular expression over the attribute
values. An `attr` expression evaluates to the set containing all targets
{{ '<var>' }}x{{ '</var>' }} such that  {{ '<var>' }}x{{ '</var>' }} is a
member of the set {{ '<var>' }}input{{ '</var>' }}, is a rule with the defined
attribute {{ '<var>' }}name{{ '</var>' }} and the attribute value contains an
(unanchored) match for the regular expression
{{ '<var>' }}pattern{{ '</var>' }}. If {{ '<var>' }}name{{ '</var>' }} is an
optional attribute and rule does not specify it explicitly then default
attribute value will be used for comparison. For example,

```
attr(linkshared, 0, deps(//foo))
```

will select all `//foo` dependencies that are allowed to have a
linkshared attribute (such as, `cc_binary` rule) and have it either
explicitly set to 0 or do not set it at all but default value is 0 (such as for
`cc_binary` rules).

List-type attributes (such as `srcs`, `data`, etc) are
converted to strings of the form `[value<sub>1</sub>, ..., value<sub>n</sub>]`,
starting with a `[` bracket, ending with a `]` bracket
and using "`, `" (comma, space) to delimit multiple values.
Labels are converted to strings by using the absolute form of the
label. For example, an attribute `deps=[":foo",
"//otherpkg:bar", "wiz"]` would be converted to the
string `[//thispkg:foo, //otherpkg:bar, //thispkg:wiz]`.
Brackets are always present, so the empty list would use string value `[]`
for matching purposes. For example,

```
attr("srcs", "\[\]", deps(//foo))
```

will select all rules among `//foo` dependencies that have an
empty `srcs` attribute, while

```
attr("data", ".{3,}", deps(//foo))
```

will select all rules among `//foo` dependencies that specify at
least one value in the `data` attribute (every label is at least
3 characters long due to the `//` and `:`).

To select all rules among `//foo` dependencies with a particular `value` in a
list-type attribute, use

```
attr("tags", "[\[ ]value[,\]]", deps(//foo))
```

This works because the character before `value` will be `[` or a space and the
character after `value` will be a comma or `]`.

To select all rules among `//foo` dependencies with a particular `key` and
`value` in a dict-type attribute, use

```
attr("some_dict_attribute", "[\{ ]key=value[,\}]", deps(//foo))
```

This would select `//foo` if `//foo` is defined as

```
some_rule(
  name = "foo",
  some_dict_attribute = {
    "key": "value",
  },
)
```

This works because the character before `key=value` will be `{` or a space and
the character after `key=value` will be a comma or `}`.

### Rule visibility filtering: visible {:#visible}

```
expr ::= visible({{ '<var>' }}expr{{ '</var>' }}, {{ '<var>' }}expr{{ '</var>' }})
```

The `visible({{ '<var>' }}predicate{{ '</var>' }}, {{ '<var>' }}input{{ '</var>' }})` operator
applies a filter to a set of targets, and discards targets without the
required visibility.

The first argument, {{ '<var>' }}predicate{{ '</var>' }}, is a set of targets that all targets
in the output must be visible to. A {{ '<var>' }}visible{{ '</var>' }} expression
evaluates to the set containing all targets {{ '<var>' }}x{{ '</var>' }} such that {{ '<var>' }}x{{ '</var>' }}
is a member of the set {{ '<var>' }}input{{ '</var>' }}, and for all targets {{ '<var>' }}y{{ '</var>' }} in
{{ '<var>' }}predicate{{ '</var>' }} {{ '<var>' }}x{{ '</var>' }} is visible to {{ '<var>' }}y{{ '</var>' }}. For example:

```
visible(//foo, //bar:*)
```

will select all targets in the package `//bar` that `//foo`
can depend on without violating visibility restrictions.

### Evaluation of rule attributes of type label: labels {:#labels}

```
expr ::= labels({{ '<var>' }}word{{ '</var>' }}, {{ '<var>' }}expr{{ '</var>' }})
```

The `labels({{ '<var>' }}attr_name{{ '</var>' }}, {{ '<var>' }}inputs{{ '</var>' }})`
operator returns the set of targets specified in the
attribute {{ '<var>' }}attr_name{{ '</var>' }} of type "label" or "list of label" in
some rule in set {{ '<var>' }}inputs{{ '</var>' }}.

For example, `labels(srcs, //foo)` returns the set of
targets appearing in the `srcs` attribute of
the `//foo` rule. If there are multiple rules
with `srcs` attributes in the {{ '<var>' }}inputs{{ '</var>' }} set, the
union of their `srcs` is returned.

### Expand and filter test_suites: tests {:#tests}

```
expr ::= tests({{ '<var>' }}expr{{ '</var>' }})
```

The `tests({{ '<var>' }}x{{ '</var>' }})` operator returns the set of all test
rules in set {{ '<var>' }}x{{ '</var>' }}, expanding any `test_suite` rules into
the set of individual tests that they refer to, and applying filtering by
`tag` and `size`.

By default, query evaluation
ignores any non-test targets in all `test_suite` rules. This can be
changed to errors with the `--strict_test_suite` option.

For example, the query `kind(test, foo:*)` lists all
the `*_test` and `test_suite` rules
in the `foo` package. All the results are (by
definition) members of the `foo` package. In contrast,
the query `tests(foo:*)` will return all of the
individual tests that would be executed by `bazel test
foo:*`: this may include tests belonging to other packages,
that are referenced directly or indirectly
via `test_suite` rules.

### Package definition files: buildfiles {:#buildfiles}

```
expr ::= buildfiles({{ '<var>' }}expr{{ '</var>' }})
```

The `buildfiles({{ '<var>' }}x{{ '</var>' }})` operator returns the set
of files that define the packages of each target in
set {{ '<var>' }}x{{ '</var>' }}; in other words, for each package, its `BUILD` file,
plus any .bzl files it references via `load`. Note that this
also returns the `BUILD` files of the packages containing these
`load`ed files.

This operator is typically used when determining what files or
packages are required to build a specified target, often in conjunction with
the [`--output package`](#output-package) option, below). For example,

```posix-terminal
bazel query 'buildfiles(deps(//foo))' --output package
```

returns the set of all packages on which `//foo` transitively depends.

Note: A naive attempt at the above query would omit
the `buildfiles` operator and use only `deps`,
but this yields an incorrect result: while the result contains the
majority of needed packages, those packages that contain only files
that are `load()`'ed will be missing.

Warning: Bazel pretends each `.bzl` file produced by
`buildfiles` has a corresponding target (for example, file `a/b.bzl` =>
target `//a:b.bzl`), but this isn't necessarily the case. Therefore,
`buildfiles` doesn't compose well with other query operators and its results can be
misleading when formatted in a structured way, such as
[`--output=xml`](#xml).

### Package definition files: rbuildfiles {:#rbuildfiles}

```
expr ::= rbuildfiles({{ '<var>' }}word{{ '</var>' }}, ...)
```

Note: Only available with [Sky Query](#sky-query).

The `rbuildfiles` operator takes a comma-separated list of path fragments and returns
the set of `BUILD` files that transitively depend on these path fragments. For instance, if
`//foo` is a package, then `rbuildfiles(foo/BUILD)` will return the
`//foo:BUILD` target. If the `foo/BUILD` file has
`load('//bar:file.bzl'...` in it, then `rbuildfiles(bar/file.bzl)` will
return the `//foo:BUILD` target, as well as the targets for any other `BUILD` files that
load `//bar:file.bzl`

The scope of the <scope>rbuildfiles</scope> operator is the universe specified by the
`--universe_scope` flag. Files that do not correspond directly to `BUILD` files and `.bzl`
files do not affect the results. For instance, source files (like `foo.cc`) are ignored,
even if they are explicitly mentioned in the `BUILD` file. Symlinks, however, are respected, so that
if `foo/BUILD` is a symlink to `bar/BUILD`, then
`rbuildfiles(bar/BUILD)` will include `//foo:BUILD` in its results.

The `rbuildfiles` operator is almost morally the inverse of the
[`buildfiles`](#buildfiles) operator. However, this moral inversion
holds more strongly in one direction: the outputs of `rbuildfiles` are just like the
inputs of `buildfiles`; the former will only contain `BUILD` file targets in packages,
and the latter may contain such targets. In the other direction, the correspondence is weaker. The
outputs of the `buildfiles` operator are targets corresponding to all packages and .`bzl`
files needed by a given input. However, the inputs of the `rbuildfiles` operator are
not those targets, but rather the path fragments that correspond to those targets.

### Package definition files: loadfiles {:#loadfiles}

```
expr ::= loadfiles({{ '<var>' }}expr{{ '</var>' }})
```

The `loadfiles({{ '<var>' }}x{{ '</var>' }})` operator returns the set of
Starlark files that are needed to load the packages of each target in
set {{ '<var>' }}x{{ '</var>' }}. In other words, for each package, it returns the
.bzl files that are referenced from its `BUILD` files.

Warning: Bazel pretends each of these .bzl files has a corresponding target
(for example, file `a/b.bzl` => target `//a:b.bzl`), but this isn't
necessarily the case. Therefore, `loadfiles` doesn't compose well with other query
operators and its results can be misleading when formatted in a structured way, such as
[`--output=xml`](#xml).

## Output formats {:#output-formats}

`bazel query` generates a graph.
You specify the content, format, and ordering by which
`bazel query` presents this graph
by means of the `--output` command-line option.

When running with [Sky Query](#sky-query), only output formats that are compatible with
unordered output are allowed. Specifically, `graph`, `minrank`, and
`maxrank` output formats are forbidden.

Some of the output formats accept additional options. The name of
each output option is prefixed with the output format to which it
applies, so `--graph:factored` applies only
when `--output=graph` is being used; it has no effect if
an output format other than `graph` is used. Similarly,
`--xml:line_numbers` applies only when `--output=xml`
is being used.

### On the ordering of results {:#results-ordering}

Although query expressions always follow the "[law of
conservation of graph order](#graph-order)", _presenting_ the results may be done
in either a dependency-ordered or unordered manner. This does **not**
influence the targets in the result set or how the query is computed. It only
affects how the results are printed to stdout. Moreover, nodes that are
equivalent in the dependency order may or may not be ordered alphabetically.
The `--order_output` flag can be used to control this behavior.
(The `--[no]order_results` flag has a subset of the functionality
of the `--order_output` flag and is deprecated.)

The default value of this flag is `auto`, which prints results in **lexicographical
order**. However, when `somepath(a,b)` is used, the results will be printed in
`deps` order instead.

When this flag is `no` and `--output` is one of
`build`, `label`, `label_kind`, `location`, `package`, `proto`, or
`xml`, the outputs will be printed in arbitrary order. **This is
generally the fastest option**. It is not supported though when
`--output` is one of `graph`, `minrank` or
`maxrank`: with these formats, Bazel always prints results
ordered by the dependency order or rank.

When this flag is `deps`, Bazel prints results in some topological order—that is,
dependents first and dependencies after. However, nodes that are unordered by the
dependency order (because there is no path from either one to the other) may be
printed in any order.

When this flag is `full`, Bazel prints nodes in a fully deterministic (total) order.
First, all nodes are sorted alphabetically. Then, each node in the list is used as the start of a
post-order depth-first search in which outgoing edges to unvisited nodes are traversed in
alphabetical order of the successor nodes. Finally, nodes are printed in the reverse of the order
in which they were visited.

Printing nodes in this order may be slower, so it should be used only when determinism is
important.

### Print the source form of targets as they would appear in BUILD {:#target-source-form}

```
--output build
```

With this option, the representation of each target is as if it were
hand-written in the BUILD language. All variables and function calls
(such as glob, macros) are expanded, which is useful for seeing the effect
of Starlark macros. Additionally, each effective rule reports a
`generator_name` and/or `generator_function`) value,
giving the name of the macro that was evaluated to produce the effective rule.

Although the output uses the same syntax as `BUILD` files, it is not
guaranteed to produce a valid `BUILD` file.

### Print the label of each target {:#print-label-target}

```
--output label
```

With this option, the set of names (or _labels_) of each target
in the resulting graph is printed, one label per line, in
topological order (unless `--noorder_results` is specified, see
[notes on the ordering of results](#result-order)).
(A topological ordering is one in which a graph
node appears earlier than all of its successors.)  Of course there
are many possible topological orderings of a graph (_reverse
postorder_ is just one); which one is chosen is not specified.

When printing the output of a `somepath` query, the order
in which the nodes are printed is the order of the path.

Caveat: in some corner cases, there may be two distinct targets with
the same label; for example, a `sh_binary` rule and its
sole (implicit) `srcs` file may both be called
`foo.sh`. If the result of a query contains both of
these targets, the output (in `label` format) will appear
to contain a duplicate. When using the `label_kind` (see
below) format, the distinction becomes clear: the two targets have
the same name, but one has kind `sh_binary rule` and the
other kind `source file`.

### Print the label and kind of each target {:#print-target-label}

```
--output label_kind
```

Like `label`, this output format prints the labels of
each target in the resulting graph, in topological order, but it
additionally precedes the label by the [_kind_](#kind) of the target.

### Print targets in protocol buffer format {:#print-target-proto}

```
--output proto
```

Prints the query output as a
[`QueryResult`](https://github.com/bazelbuild/bazel/blob/master/src/main/protobuf/build.proto)
protocol buffer.

### Print targets in length-delimited protocol buffer format {:#print-target-length-delimited-proto}

```
--output streamed_proto
```

Prints a
[length-delimited](https://protobuf.dev/programming-guides/encoding/#size-limit)
stream of
[`Target`](https://github.com/bazelbuild/bazel/blob/master/src/main/protobuf/build.proto)
protocol buffers. This is useful to _(i)_ get around
[size limitations](https://protobuf.dev/programming-guides/encoding/#size-limit)
of protocol buffers when there are too many targets to fit in a single
`QueryResult` or _(ii)_ to start processing while Bazel is still outputting.

### Print targets in text proto format {:#print-target-textproto}

```
--output textproto
```

Similar to `--output proto`, prints the
[`QueryResult`](https://github.com/bazelbuild/bazel/blob/master/src/main/protobuf/build.proto)
protocol buffer but in
[text format](https://protobuf.dev/reference/protobuf/textformat-spec/).

### Print targets in ndjson format {:#print-target-streamed-jsonproto}

```
--output streamed_jsonproto
```

Similar to `--output streamed_proto`, prints a stream of
[`Target`](https://github.com/bazelbuild/bazel/blob/master/src/main/protobuf/build.proto)
protocol buffers but in [ndjson](https://github.com/ndjson/ndjson-spec) format.

### Print the label of each target, in rank order {:#print-target-label-rank-order}

```
--output minrank --output maxrank
```

Like `label`, the `minrank`
and `maxrank` output formats print the labels of each
target in the resulting graph, but instead of appearing in
topological order, they appear in rank order, preceded by their
rank number. These are unaffected by the result ordering
`--[no]order_results` flag (see [notes on
the ordering of results](#result-order)).

There are two variants of this format: `minrank` ranks
each node by the length of the shortest path from a root node to it.
"Root" nodes (those which have no incoming edges) are of rank 0,
their successors are of rank 1, etc. (As always, edges point from a
target to its prerequisites: the targets it depends upon.)

`maxrank` ranks each node by the length of the longest
path from a root node to it. Again, "roots" have rank 0, all other
nodes have a rank which is one greater than the maximum rank of all
their predecessors.

All nodes in a cycle are considered of equal rank. (Most graphs are
acyclic, but cycles do occur
simply because `BUILD` files contain erroneous cycles.)

These output formats are useful for discovering how deep a graph is.
If used for the result of a `deps(x)`, `rdeps(x)`,
or `allpaths` query, then the rank number is equal to the
length of the shortest (with `minrank`) or longest
(with `maxrank`) path from `x` to a node in
that rank. `maxrank` can be used to determine the
longest sequence of build steps required to build a target.

Note: The ranked output of a `somepath` query is
basically meaningless because `somepath` doesn't
guarantee to return either a shortest or a longest path, and it may
include "transitive" edges from one path node to another that are
not direct edges in original graph.

For example, the graph on the left yields the outputs on the right
when `--output minrank` and `--output maxrank`
are specified, respectively.

<table>
  <tr>
    <td><img src="/docs/images/out-ranked.svg" alt="Out ranked">
    </td>
    <td>
      <pre>
      minrank

      0 //c:c
      1 //b:b
      1 //a:a
      2 //b:b.cc
      2 //a:a.cc
      </pre>
    </td>
    <td>
      <pre>
      maxrank

      0 //c:c
      1 //b:b
      2 //a:a
      2 //b:b.cc
      3 //a:a.cc
      </pre>
    </td>
  </tr>
</table>

### Print the location of each target {:#print-target-location}

```
--output location
```

Like `label_kind`, this option prints out, for each
target in the result, the target's kind and label, but it is
prefixed by a string describing the location of that target, as a
filename and line number. The format resembles the output of
`grep`. Thus, tools that can parse the latter (such as Emacs
or vi) can also use the query output to step through a series of
matches, allowing the Bazel query tool to be used as a
dependency-graph-aware "grep for BUILD files".

The location information varies by target kind (see the [kind](#kind) operator). For rules, the
location of the rule's declaration within the `BUILD` file is printed.
For source files, the location of line 1 of the actual file is
printed. For a generated file, the location of the rule that
generates it is printed. (The query tool does not have sufficient
information to find the actual location of the generated file, and
in any case, it might not exist if a build has not yet been performed.)

### Print the set of packages {:#print-package-set}

```--output package```

This option prints the name of all packages to which
some target in the result set belongs. The names are printed in
lexicographical order; duplicates are excluded. Formally, this
is a _projection_ from the set of labels (package, target) onto
packages.

Packages in external repositories are formatted as
`@repo//foo/bar` while packages in the main repository are
formatted as `foo/bar`.

In conjunction with the `deps(...)` query, this output
option can be used to find the set of packages that must be checked
out in order to build a given set of targets.

### Display a graph of the result {:#display-result-graph}

```--output graph```

This option causes the query result to be printed as a directed
graph in the popular AT&amp;T GraphViz format. Typically the
result is saved to a file, such as `.png` or `.svg`.
(If the `dot` program is not installed on your workstation, you
can install it using the command `sudo apt-get install graphviz`.)
See the example section below for a sample invocation.

This output format is particularly useful for `allpaths`,
`deps`, or `rdeps` queries, where the result
includes a _set of paths_ that cannot be easily visualized when
rendered in a linear form, such as with `--output label`.

By default, the graph is rendered in a _factored_ form. That is,
topologically-equivalent nodes are merged together into a single
node with multiple labels. This makes the graph more compact
and readable, because typical result graphs contain highly
repetitive patterns. For example, a `java_library` rule
may depend on hundreds of Java source files all generated by the
same `genrule`; in the factored graph, all these files
are represented by a single node. This behavior may be disabled
with the `--nograph:factored` option.

#### `--graph:node_limit {{ '<var>' }}n{{ '</var>' }}` {:#graph-nodelimit}

The option specifies the maximum length of the label string for a
graph node in the output. Longer labels will be truncated; -1
disables truncation. Due to the factored form in which graphs are
usually printed, the node labels may be very long. GraphViz cannot
handle labels exceeding 1024 characters, which is the default value
of this option. This option has no effect unless
`--output=graph` is being used.

#### `--[no]graph:factored` {:#graph-factored}

By default, graphs are displayed in factored form, as explained
[above](#output-graph).
When `--nograph:factored` is specified, graphs are
printed without factoring. This makes visualization using GraphViz
impractical, but the simpler format may ease processing by other
tools (such as grep). This option has no effect
unless `--output=graph` is being used.

### XML {:#xml}

```--output xml```

This option causes the resulting targets to be printed in an XML
form. The output starts with an XML header such as this

```
  <?xml version="1.0" encoding="UTF-8"?>
  <query version="2">
```

<!-- The docs should continue to document version 2 into perpetuity,
     even if we add new formats, to handle clients synced to old CLs. -->

and then continues with an XML element for each target
in the result graph, in topological order (unless
[unordered results](#result-order) are requested),
and then finishes with a terminating

```
</query>
```

Simple entries are emitted for targets of `file` kind:

```
  <source-file name='//foo:foo_main.cc' .../>
  <generated-file name='//foo:libfoo.so' .../>
```

But for rules, the XML is structured and contains definitions of all
the attributes of the rule, including those whose value was not
explicitly specified in the rule's `BUILD` file.

Additionally, the result includes `rule-input` and
`rule-output` elements so that the topology of the
dependency graph can be reconstructed without having to know that,
for example, the elements of the `srcs` attribute are
forward dependencies (prerequisites) and the contents of the
`outs` attribute are backward dependencies (consumers).

`rule-input` elements for [implicit dependencies](#implicit_deps) are suppressed if
`--noimplicit_deps` is specified.

```
  <rule class='cc_binary rule' name='//foo:foo' ...>
    <list name='srcs'>
      <label value='//foo:foo_main.cc'/>
      <label value='//foo:bar.cc'/>
      ...
    </list>
    <list name='deps'>
      <label value='//common:common'/>
      <label value='//collections:collections'/>
      ...
    </list>
    <list name='data'>
      ...
    </list>
    <int name='linkstatic' value='0'/>
    <int name='linkshared' value='0'/>
    <list name='licenses'/>
    <list name='distribs'>
      <distribution value="INTERNAL" />
    </list>
    <rule-input name="//common:common" />
    <rule-input name="//collections:collections" />
    <rule-input name="//foo:foo_main.cc" />
    <rule-input name="//foo:bar.cc" />
    ...
  </rule>
```

Every XML element for a target contains a `name`
attribute, whose value is the target's label, and
a `location` attribute, whose value is the target's
location as printed by the [`--output location`](#print-target-location).

#### `--[no]xml:line_numbers` {:#xml-linenumbers}

By default, the locations displayed in the XML output contain line numbers.
When `--noxml:line_numbers` is specified, line numbers are not printed.

#### `--[no]xml:default_values` {:#xml-defaultvalues}

By default, XML output does not include rule attribute whose value
is the default value for that kind of attribute (for example, if it
were not specified in the `BUILD` file, or the default value was
provided explicitly). This option causes such attribute values to
be included in the XML output.

### Regular expressions {:#regular-expressions}

Regular expressions in the query language use the Java regex library, so you can use the
full syntax for
[`java.util.regex.Pattern`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html){: .external}.

### Querying with external repositories {:#querying-external-repositories}

If the build depends on rules from [external repositories](/external/overview)
then query results will include these dependencies. For
example, if `//foo:bar` depends on `@other-repo//baz:lib`, then
`bazel query 'deps(//foo:bar)'` will list `@other-repo//baz:lib` as a
dependency.
