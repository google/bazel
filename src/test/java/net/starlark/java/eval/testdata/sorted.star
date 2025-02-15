# sorted, and ordered comparison

assert_eq(sorted([]), [])

# accepts any iterable
assert_eq(sorted(()), [])
assert_eq(sorted({3: 4, 1: 2}), [1, 3])

# No error if no comparisons are needed.
assert_eq(sorted([{}]), [{}])

assert_eq(sorted([42, 123, 3]), [3, 42, 123])
assert_eq(sorted(["wiz", "foo", "bar"]), ["bar", "foo", "wiz"])
assert_eq(sorted([42, 123, 3], reverse = True), [123, 42, 3])
assert_eq(sorted(["wiz", "foo", "bar"], reverse = True), ["wiz", "foo", "bar"])
assert_eq(sorted(list({"a": 1, "b": 2})), ["a", "b"])

def f(x):
    return x[0]

pairs = [(4, 0), (3, 1), (4, 2), (2, 3), (3, 4), (1, 5), (2, 6), (3, 7)]

assert_eq(
    sorted(pairs, key = f),
    [
        (1, 5),
        (2, 3),
        (2, 6),
        (3, 1),
        (3, 4),
        (3, 7),
        (4, 0),
        (4, 2),
    ],
)

assert_eq(
    sorted(["two", "three", "four"], key = len),
    ["two", "four", "three"],
)
assert_eq(
    sorted(["two", "three", "four"], key = len, reverse = True),
    ["three", "four", "two"],
)
assert_eq(
    sorted([[1, 5], [0, 10], [4]], key = max),
    [[4], [1, 5], [0, 10]],
)
assert_eq(
    sorted([[1, 5], [0, 10], [4]], key = min, reverse = True),
    [[4], [1, 5], [0, 10]],
)
assert_eq(
    sorted([[2, 6, 1], [5, 2, 1], [1, 4, 2]], key = sorted),
    [[1, 4, 2], [5, 2, 1], [2, 6, 1]],
)

# case-insensitive sort
def lower(str):
    return str.lower()

assert_eq(sorted(["a", "B", "c"]), ["B", "a", "c"])
assert_eq(sorted(["a", "B", "c"], reverse = True), ["c", "a", "B"])
assert_eq(sorted(["a", "B", "c"], key = lower), ["a", "B", "c"])
assert_eq(sorted(["a", "B", "c"], key = lower, reverse = True), ["c", "B", "a"])

# NaN values compare greater even than +Inf.
# (By contrast, Python's 'sorted' sorts the numbers between the NaN values,
# producing a pairwise but not transitively nondescending result.)
# See for example https://bugs.python.org/issue36095.
n = float("nan")
assert_eq(
    str(sorted([7, 4, n, 19, 2, 6, 3, 87, 17, n, 5, 31, 12, 6, 4, 4, 2, n, 2, 1])),
    "[1, 2, 2, 2, 3, 4, 4, 4, 5, 6, 6, 7, 12, 17, 19, 31, 87, nan, nan, nan]",
)
assert_eq(str(sorted([7, 3, n, 1, 9])), "[1, 3, 7, 9, nan]")
assert_eq(str(sorted([7, 3, n, 1, 9], reverse = True)), "[nan, 9, 7, 3, 1]")

# The key function is called once per array element, in order
acc = []

def keyfn(k):
    acc.append(k)
    return k

assert_eq(sorted([3, 1, 4, 1, 5, 9], key = keyfn), [1, 1, 3, 4, 5, 9])
assert_eq(acc, [3, 1, 4, 1, 5, 9])

# Ordered list comparison x < y finds the first unequal elements
# x[i] != y[i] then performs an ordered comparison x[i] <=> x[j].
# Thus it is not an error if the lists contain equal elements
# that do not support ordered comparison, such as dict or None.
d1, d2 = {}, {}
assert_([d1] <= [d1])  # same object
assert_([d1] <= [d2])  # distinct objects
assert_([d1] <= [d1, d2])
assert_([None] <= [None])

# Starlark/Java Strings are ordered lexicographically by elements (chars).
# With UTF-16, this is not the same as code point order, because
# surrogates DXXX are not at the top of the 16-bit range. For example:
#  U+FFFD  � 	= [FFFD]       REPLACEMENT CHAR
#  U+1F33F 🌿	= [D83C DF3F]  HERB
# The first compares greater than the second.
assert_eq(sorted(["�", "🌿"]), ["�", "🌿"] if _utf8_byte_strings else ["🌿", "�"])

assert_(False < True)
assert_fails(lambda: False < 1, "unsupported comparison: bool <=> int")
assert_fails(lambda: [{1: None}] <= [{2: None}], "unsupported comparison: dict <=> dict")
assert_fails(lambda: None <= None, "unsupported comparison: NoneType <=> NoneType")
assert_fails(lambda: sorted(1), "got value of type 'int', want 'iterable'")
assert_fails(lambda: sorted([1, 2, None, 3]), "unsupported comparison: NoneType <=> int")
assert_fails(lambda: sorted([1, "one"]), "unsupported comparison: string <=> int")

# non-callable key
assert_fails(lambda: sorted([1, 2, 3], key = 1), "parameter 'key' got value of type 'int', want 'callable or NoneType'")

# failing key computation
assert_fails(lambda: sorted([1, 2, 3], key = lambda i: "abc".elems()[i]), "index out of range \\(index is 3, but sequence has 3 elements\\)")
