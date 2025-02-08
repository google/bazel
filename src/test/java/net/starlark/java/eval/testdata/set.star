# constructor
assert_eq(type(set()), "set")
assert_eq(list(set()), [])
assert_eq(set(), set([]))
assert_eq(type(set([1, 3, 2, 3])), "set")
assert_eq(list(set([1, 3, 2, 3])), [1, 3, 2])
assert_eq(type(set("hello".elems())), "set")
assert_eq(list(set("hello".elems())), ["h", "e", "l", "o"])
assert_eq(type(set(range(3))), "set")
assert_eq(list(set(range(3))), [0, 1, 2])
assert_eq(type(set({"a": 1, "b": 2, "c": 0})), "set")
assert_eq(list(set({"a": 1, "b": 2, "c": 0})), ["a", "b", "c"])
assert_eq(type(set(set([3, 1, 2]))), "set")
assert_eq(list(set(set([3, 1, 2]))), [3, 1, 2])
assert_fails(lambda: set(1), "got value of type 'int', want a set, sequence, or dict")
assert_fails(lambda: set([1], [2]), "accepts no more than 1 positional argument")
assert_fails(lambda: set([1, 2, [3]]), "unhashable type: 'list'")

# sets are not hashable
assert_fails(lambda: set([set()]), "unhashable type: 'set'")
assert_fails(lambda: {set([1]): 1}, "unhashable type: 'set'")

# stringification
assert_eq(str(set()), "set()")
assert_eq(repr(set()), "set()")
assert_eq(json.encode(set()), "[]")
assert_eq(str(set([1, 3, 2, 3])), "set([1, 3, 2])")
assert_eq(repr(set([1, 3, 2, 3])), "set([1, 3, 2])")
assert_eq(json.encode(set([3, 2, 1])), "[3,2,1]")

# membership
assert_eq(1 in set([1, 2, 3]), True)
assert_eq(0 in set([1, 2, 3]), False)
assert_eq(None in set(), False)

# truth
assert_(not set())
assert_(set([False]))
assert_(set([1, 2, 3]))

# len
assert_eq(len(set()), 0)
assert_eq(len(set([1, 2, 3])), 3)
assert_eq(len(set("hello".elems())), 4)

# a set is equal to another set with the same elements (in any order)
# a set is *not* equal to a non-set container with the same elements
assert_eq(set() == set(), True)
assert_eq(set() == [], False)
assert_eq(set() == dict(), False)
assert_eq(set([1, 2]) == set([2, 1]), True)
assert_eq(set([1, 2]) == [1, 2], False)
assert_eq(set([1, 2]) == {1: "one", 2: "two"}, False)
assert_eq(set([1, 2]) != set([2, 3]), True)
assert_eq(set([1, 2]) != [2, 3], True)

# unsupported comparison
assert_fails(lambda: set([1]) < set([1, 2]), "unsupported comparison")
assert_fails(lambda: set([1, 2]) <= set([1, 2]), "unsupported comparison")
assert_fails(lambda: set([1, 2]) > set([1, 2]), "unsupported comparison")
assert_fails(lambda: set([1, 2]) >= set([1, 2]), "unsupported comparison")
assert_fails(lambda: sorted([set(), set([1]), set([2])]), "unsupported comparison")

# binary operations
assert_eq(set([1, 2]) | set([2, 3]), set([1, 2, 3]))
assert_eq(set([1, 2]) & set([2, 3]), set([2]))
assert_eq(set([1, 2]) - set([2, 3]), set([1]))
assert_eq(set([1, 2]) ^ set([2, 3]), set([1, 3]))

# unsupported binary operations
assert_fails(lambda: set([1]) + set([2]), "unsupported binary operation")
assert_fails(lambda: set([1, 2]) | [2, 3], "unsupported binary operation")
assert_fails(lambda: set([1, 2]) & [2, 3], "unsupported binary operation")
assert_fails(lambda: set([1, 2]) - [2, 3], "unsupported binary operation")
assert_fails(lambda: set([1, 2]) ^ [2, 3], "unsupported binary operation")

# binary inplace mutations
def pipe_equals(x, y):
    x |= y

def amp_equals(x, y):
    x &= y

def minus_equals(x, y):
    x -= y

def caret_equals(x, y):
    x ^= y

inplace_set = set([1, 2])
pipe_equals(inplace_set, set([2, 3, 4]))
assert_eq(inplace_set, set([1, 2, 3, 4]))
amp_equals(inplace_set, set([2, 3, 4, 5]))
assert_eq(inplace_set, set([2, 3, 4]))
minus_equals(inplace_set, set([1, 3]))
assert_eq(inplace_set, set([2, 4]))
caret_equals(inplace_set, set([1, 2]))
assert_eq(inplace_set, set([1, 4]))

# unsupported mutations of a frozen value
frozen_set = set([1, 2])
freeze(frozen_set)
assert_fails(lambda: pipe_equals(frozen_set, set([2, 3, 4])), "trying to mutate a frozen set value")
assert_fails(lambda: amp_equals(frozen_set, set([2, 3, 4])), "trying to mutate a frozen set value")
assert_fails(lambda: minus_equals(frozen_set, set([1, 3])), "trying to mutate a frozen set value")
assert_fails(lambda: caret_equals(frozen_set, set([1, 2])), "trying to mutate a frozen set value")

# unsupported binary inplace mutations
def always_unsupported_plus_equals(x, y):
    x += y

assert_fails(lambda: always_unsupported_plus_equals(set([1]), set([2])), "unsupported binary operation")
assert_fails(lambda: pipe_equals(set([1, 2]), [2, 3]), "unsupported binary operation")
assert_fails(lambda: amp_equals(set([1, 2]), [2, 3]), "unsupported binary operation")
assert_fails(lambda: minus_equals(set([1, 2]), [2, 3]), "unsupported binary operation")
assert_fails(lambda: caret_equals(set([1, 2]), [2, 3]), "unsupported binary operation")

# unsupported indexing
assert_fails(lambda: set([1, 2])[0], "type 'set' has no operator \\[\\]")

# add
add_set = set([1, 2, 3])
add_set.add(0)
assert_eq(list(add_set), [1, 2, 3, 0])
add_set.add(1)
assert_eq(list(add_set), [1, 2, 3, 0])
assert_fails(lambda: frozen_set.add(0), "trying to mutate a frozen set value")

# update
update_set = set([1, 2])
update_set.update([2, 3], {3: "three", 4: "four"})
assert_eq(list(update_set), [1, 2, 3, 4])
assert_fails(lambda: update_set.update(1), "got value of type 'int', want a set, sequence, or dict")
assert_fails(lambda: frozen_set.update([0]), "trying to mutate a frozen set value")

# iteration order
def iterate(s):
    elements = []
    for e in s:
        elements.append(e)
    return elements

assert_eq(iterate(set("hello world".elems())), ["h", "e", "l", "o", " ", "w", "r", "d"])
add_set_for_iter = set()
add_set_for_iter.add(3)
add_set_for_iter.add(1)
add_set_for_iter.add(2)
assert_eq(iterate(add_set_for_iter), [3, 1, 2])

# remove
remove_set = set([1, 2, 3])
remove_set.remove(2)
assert_eq(list(remove_set), [1, 3])
assert_fails(lambda: remove_set.remove(4), "not found")
assert_fails(lambda: frozen_set.remove(1), "trying to mutate a frozen set value")

# discard
discard_set = set([1, 2, 3])
discard_set.discard(2)
assert_eq(list(discard_set), [1, 3])
discard_set.discard(4)
assert_eq(list(discard_set), [1, 3])
assert_fails(lambda: frozen_set.discard(1), "trying to mutate a frozen set value")

# pop
pop_set = set("hello".elems())
assert_eq(pop_set.pop(), "h")
assert_eq(pop_set.pop(), "e")
assert_eq(pop_set.pop(), "l")
assert_eq(pop_set.pop(), "o")
assert_fails(lambda: pop_set.pop(), "set is empty")
assert_fails(lambda: frozen_set.pop(), "trying to mutate a frozen set value")

# clear
clear_set = set([1, 2, 3])
clear_set.clear()
assert_eq(clear_set, set())
assert_fails(lambda: frozen_set.clear(), "trying to mutate a frozen set value")

# issubset method allows an arbitrary sequence, set, or mapping
assert_eq(set([1, 2]).issubset([1, 2, 3]), True)
assert_eq(set([1, 2]).issubset(set([2, 3])), False)
assert_eq(set([1, 2]).issubset([2, 1]), True)
assert_fails(lambda: set([1, 2]).issubset(2), "got value of type 'int', want a set, sequence, or dict")
assert_fails(lambda: set([1, 2]).issubset([1, 2], [3]), "accepts no more than 1 positional argument")

# issuperset method allows an arbitrary sequence, set, or mapping
assert_eq(set([1, 2, 3]).issuperset([0, 1, 2, 3]), False)
assert_eq(set([1, 2, 3]).issuperset({2: "a", 3: "b"}), True)
assert_eq(set([1, 2, 3]).issuperset([3, 2, 1]), True)
assert_fails(lambda: set([1, 2]).issuperset(2), "got value of type 'int', want a set, sequence, or dict")
assert_fails(lambda: set([1, 2]).issubset([1, 2], [3]), "accepts no more than 1 positional argument")

# isdisjoint method allows an arbitrary sequence, set, or mapping
assert_eq(set([1, 2]).isdisjoint([3, 4]), True)
assert_eq(set([1, 2]).isdisjoint([2, 3]), False)
assert_eq(set([1, 2]).isdisjoint({2: "a", 3: "b"}), False)
assert_eq(set([1, 2]).isdisjoint({}), True)
assert_eq(set().isdisjoint([2, 3]), True)
assert_eq(set().isdisjoint([]), True)
assert_fails(lambda: set([1, 2]).isdisjoint(2), "got value of type 'int', want a set, sequence, or dict")
assert_fails(lambda: set([1, 2]).isdisjoint([1, 2], [3]), "accepts no more than 1 positional argument")

# union method, unlike the | operator, allows arbitrary number of arbitrary sequences, sets, or mappings
assert_eq(set([1, 2]).union([2, 3]), set([1, 2, 3]))
assert_eq(set([1, 2]).union([2, 3], {3: "three", 4: "four"}), set([1, 2, 3, 4]))
assert_fails(lambda: set([1, 2]).union(3), "got value of type 'int', want a set, sequence, or dict")

# intersection method, unlike the & operator, allows arbitrary number of arbitrary sequences, sets, or mappings
assert_eq(set([1, 2, 3]).intersection([2, 3, 4]), set([2, 3]))
assert_eq(set([1, 2, 3]).intersection([2, 3, 4, 2, 3, 4]), set([2, 3]))
assert_eq(set([1, 2, 3]).intersection([2, 3], {3: "three", 4: "four"}), set([3]))
assert_fails(lambda: set([1, 2]).intersection(3), "got value of type 'int', want a set, sequence, or dict")

# intersection_update method, unlike the &= operator, allows arbitrary number of arbitrary sequences, sets, or mappings
intersection_update_set = set([1, 2, 3])
intersection_update_set.intersection_update([2, 3, 4])
assert_eq(intersection_update_set, set([2, 3]))
intersection_update_set.intersection_update([2, 3, 4, 2, 3, 4])
assert_eq(intersection_update_set, set([2, 3]))
intersection_update_set.intersection_update([2, 3], {3: "three", 4: "four"})
assert_eq(intersection_update_set, set([3]))
assert_fails(lambda: intersection_update_set.intersection_update(3), "got value of type 'int', want a set, sequence, or dict")
assert_fails(lambda: frozen_set.intersection_update([1]), "trying to mutate a frozen set value")

# difference method, unlike the - operator, allows arbitrary number of arbitrary sequences, sets, or mappings
assert_eq(set([1, 2, 3]).difference([2]), set([1, 3]))
assert_eq(set([1, 2, 3]).difference([2, 3, 2, 3]), set([1]))
assert_eq(set([1, 2, 3]).difference([2], {3: "three", 4: "four"}), set([1]))
assert_fails(lambda: set([1, 2]).difference(2), "got value of type 'int', want a set, sequence, or dict")

# difference_update method, unlike the -= operator, allows arbitrary number of arbitrary sequences, sets, or mappings
difference_update_set = set([1, 2, 3, 4])
difference_update_set.difference_update([2])
assert_eq(difference_update_set, set([1, 3, 4]))
difference_update_set.difference_update([2, 3, 2, 3])
assert_eq(difference_update_set, set([1, 4]))
difference_update_set.difference_update([2], {3: "three", 4: "four"})
assert_eq(difference_update_set, set([1]))
assert_fails(lambda: difference_update_set.difference_update(2), "got value of type 'int', want a set, sequence, or dict")
assert_fails(lambda: frozen_set.difference_update([1]), "trying to mutate a frozen set value")

# symmetric_difference method, unlike the ^ operator, allows one arbitrary sequence, set, or mapping
assert_eq(set([1, 2, 3]).symmetric_difference([2, 3, 4]), set([1, 4]))
assert_eq(set([1, 2, 3]).symmetric_difference([2, 3, 4, 2, 3, 4]), set([1, 4]))
assert_eq(set([1, 2, 3]).symmetric_difference({0: "zero", 1: "one"}), set([2, 3, 0]))
assert_fails(lambda: set([1, 2]).symmetric_difference(2), "got value of type 'int', want a set, sequence, or dict")
assert_fails(lambda: set([1, 2]).symmetric_difference([1], [2]), "accepts no more than 1 positional argument")

# symmetric_difference_update method, unlike the ^= operator, allows one arbitrary sequence, set, or mapping
symmetric_difference_update_set = set([1, 2, 3, 4])
symmetric_difference_update_set.symmetric_difference_update([2])
assert_eq(symmetric_difference_update_set, set([1, 3, 4]))
symmetric_difference_update_set.symmetric_difference_update([2, 3, 2, 3])
assert_eq(symmetric_difference_update_set, set([1, 2, 4]))
symmetric_difference_update_set.symmetric_difference_update({0: "zero", 1: "one"})
assert_eq(symmetric_difference_update_set, set([0, 2, 4]))
assert_fails(lambda: symmetric_difference_update_set.symmetric_difference_update(2), "got value of type 'int', want a set, sequence, or dict")
assert_fails(lambda: frozen_set.symmetric_difference_update([1]), "trying to mutate a frozen set value")
