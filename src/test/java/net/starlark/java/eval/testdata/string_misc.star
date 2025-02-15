# join
assert_eq("-".join(["a", "b", "c"]), "a-b-c")
assert_eq("-".join({"a": 0, "b": None, "c": True}), "a-b-c")

assert_eq("".join([(x + "*") for x in ["a", "b", "c"]]), "a*b*c*")
li = [(y + "*" + z + "|") for y in ["a", "b", "c"] for z in ["d", "e"]]
assert_eq("".join(li), "a*d|a*e|b*d|b*e|c*d|c*e|")

# lower, upper
assert_eq("Blah Blah".lower(), "blah blah")
assert_eq("ein bier".upper(), "EIN BIER")
assert_eq("".upper(), "")

# title
assert_eq("this is a very simple test".title(), "This Is A Very Simple Test")
assert_eq("Do We Keep Capital Letters?".title(), "Do We Keep Capital Letters?")
assert_eq(
    "this isn't just an ol' apostrophe test".title(),
    "This Isn'T Just An Ol' Apostrophe Test",
)
assert_eq(
    "Let us test crazy characters: _bla.exe//foo:bla(test$class)".title(),
    "Let Us Test Crazy Characters: _Bla.Exe//Foo:Bla(Test$Class)",
)
assert_eq(
    "WE HAve tO lOWERCASE soMEthING heRE, AI?".title(),
    "We Have To Lowercase Something Here, Ai?",
)
assert_eq("wh4t ab0ut s0me numb3rs".title(), "Wh4T Ab0Ut S0Me Numb3Rs")

# capitalize
assert_eq("hello world".capitalize(), "Hello world")
assert_eq("HELLO WORLD".capitalize(), "Hello world")
assert_eq("".capitalize(), "")
assert_eq("12 lower UPPER 34".capitalize(), "12 lower upper 34")

# replace
assert_eq("banana".replace("a", "e"), "benene")
assert_eq("banana".replace("a", "$()"), "b$()n$()n$()")
assert_eq("banana".replace("a", "$"), "b$n$n$")
assert_eq("banana".replace("a", "\\"), "b\\n\\n\\")
assert_eq("b$()n$()n$()".replace("$()", "$($())"), "b$($())n$($())n$($())")
assert_eq("b\\n\\n\\".replace("\\", "$()"), "b$()n$()n$()")

assert_eq("banana".replace("a", "e", 2), "benena")
assert_eq("banana".replace("a", "e", 0), "banana")

assert_eq("banana".replace("", "-"), "-b-a-n-a-n-a-")
assert_eq("banana".replace("", "-", 2), "-b-anana")
assert_eq("banana".replace("", "-", 0), "banana")

assert_eq("banana".replace("", ""), "banana")
assert_eq("banana".replace("a", ""), "bnn")
assert_eq("banana".replace("a", "", 2), "bnna")

assert_eq("banana".replace("a", "o", -2), "bonono")
assert_eq("banana".replace("a", "e", -1), "benene")
assert_eq("banana".replace("a", "e", -10), "benene")
assert_eq("banana".replace("", "-", -2), "-b-a-n-a-n-a-")

assert_fails(
    lambda: "banana".replace("a", "e", None),
    "parameter 'count' got value of type 'NoneType', want 'int'",
)

# index, rindex
assert_eq("banana".index("na"), 2)
assert_eq("abababa".index("ab", 1), 2)
assert_eq("banana".rindex("na"), 4)
assert_eq("abababa".rindex("ab", 1), 4)
assert_fails(lambda: "banana".index("foo"), "substring not found")
assert_fails(lambda: "banana".rindex("foo"), "substring not found")

# endswith
assert_eq("Apricot".endswith("cot"), True)
assert_eq("a".endswith(""), True)
assert_eq("".endswith(""), True)
assert_eq("Apricot".endswith("co"), False)
assert_eq("Apricot".endswith("co", -1), False)
assert_eq("abcd".endswith("c", -2, -1), True)
assert_eq("abcd".endswith("c", 1, 8), False)
assert_eq("abcd".endswith("d", 1, 8), True)
assert_eq("Apricot".endswith(("cot", "toc")), True)
assert_eq("Apricot".endswith(("toc", "cot")), True)
assert_eq("a".endswith(("", "")), True)
assert_eq("a".endswith(("", "a")), True)
assert_eq("a".endswith(("a", "a")), True)
assert_eq("".endswith(("a", "")), True)
assert_eq("".endswith(("", "")), True)
assert_eq("".endswith(("a", "a")), False)
assert_eq("a".endswith(("a")), True)
assert_eq("a".endswith(("a",)), True)
assert_eq("a".endswith(("b",)), False)
assert_eq("a".endswith(()), False)
assert_eq("".endswith(()), False)
assert_fails(lambda: "a".endswith(["a"]), "got .* 'list', want 'string or tuple'")
assert_fails(lambda: "1".endswith((1,)), "at index 0 of sub, got element of type int, want string")
assert_fails(lambda: "a".endswith(("1", 1)), "at index 1 of sub, got element of type int, want string")

# startswith
assert_eq("Apricot".startswith("Apr"), True)
assert_eq("Apricot".startswith("A"), True)
assert_eq("Apricot".startswith(""), True)
assert_eq("Apricot".startswith("z"), False)
assert_eq("".startswith(""), True)
assert_eq("".startswith("a"), False)
assert_eq("Apricot".startswith(("Apr", "rpA")), True)
assert_eq("Apricot".startswith(("rpA", "Apr")), True)
assert_eq("a".startswith(("", "")), True)
assert_eq("a".startswith(("", "a")), True)
assert_eq("a".startswith(("a", "a")), True)
assert_eq("".startswith(("a", "")), True)
assert_eq("".startswith(("", "")), True)
assert_eq("".startswith(("a", "a")), False)
assert_eq("a".startswith(("a")), True)
assert_eq("a".startswith(("a",)), True)
assert_eq("a".startswith(("b",)), False)
assert_eq("a".startswith(()), False)
assert_eq("".startswith(()), False)
assert_fails(lambda: "a".startswith(["a"]), "got .* 'list', want 'string or tuple'")
assert_fails(lambda: "1".startswith((1,)), "at index 0 of sub, got element of type int, want string")
assert_fails(lambda: "a".startswith(("1", 1)), "at index 1 of sub, got element of type int, want string")

# substring
assert_eq("012345678"[0:-1], "01234567")
assert_eq("012345678"[2:4], "23")
assert_eq("012345678"[-5:-3], "45")
assert_eq("012345678"[2:2], "")
assert_eq("012345678"[2:], "2345678")
assert_eq("012345678"[:3], "012")
assert_eq("012345678"[-1:], "8")
assert_eq("012345678"[:], "012345678")
assert_eq("012345678"[-1:2], "")
assert_eq("012345678"[4:2], "")

# count
assert_eq("abc".count("a"), 1)
assert_eq("abc".count("b"), 1)
assert_eq("abc".count("c"), 1)
assert_eq("abbc".count("b"), 2)
assert_eq("aba".count("a"), 2)
assert_eq("aaa".count("aa"), 1)
assert_eq("aaaa".count("aa"), 2)
assert_eq("abc".count("a", 0), 1)
assert_eq("abc".count("a", 1), 0)
assert_eq("abc".count("c", 0, 3), 1)
assert_eq("abc".count("c", 0, 2), 0)
assert_eq("abc".count("a", -1), 0)
assert_eq("abc".count("c", -1), 1)
assert_eq("abc".count("c", 0, 5), 1)
assert_eq("abc".count("c", 0, -1), 0)
assert_eq("abc".count("a", 0, -1), 1)
assert_eq("ababab".count("ab", 0, 5), 2)

# isalpha
assert_eq("".isalpha(), False)
assert_eq("abz".isalpha(), True)
assert_eq("a1".isalpha(), False)
assert_eq("a ".isalpha(), False)
assert_eq("A".isalpha(), True)
assert_eq("AbZ".isalpha(), True)

# escape sequences
assert_eq("\"", '"')

# enumerate

assert_eq(enumerate("abc".elems()), [(0, "a"), (1, "b"), (2, "c")])
assert_eq(enumerate({"a": 0, 2: 1, "ab": 3}), [(0, "a"), (1, 2), (2, "ab")])
assert_eq(enumerate({}), [])
assert_eq(enumerate([False, True, None], 42), [(42, False), (43, True), (44, None)])
assert_fails(lambda: enumerate("ab"), "type 'string' is not iterable")

# repeat
assert_eq("abc" * 3, "abcabcabc")
assert_eq(3 * "abc", "abcabcabc")
assert_eq("abc" * 0, "")
assert_eq("abc" * -1, "")
assert_fails(lambda: "abc" * (1 << 35), "got 34359738368 for repeat, want value in signed 32-bit range")
assert_fails(lambda: "abc" * (1 << 30), "excessive repeat \\(3 \\* 1073741824 characters\\)")

# removeprefix
assert_eq("Apricot".removeprefix("Apr"), "icot")
assert_eq("Apricot".removeprefix("apr"), "Apricot")
assert_eq("Apricot".removeprefix("A"), "pricot")
assert_eq("a".removeprefix(""), "a")
assert_eq("".removeprefix(""), "")
assert_eq("".removeprefix("a"), "")
assert_eq("Apricot".removeprefix("pr"), "Apricot")
assert_eq("AprApricot".removeprefix("Apr"), "Apricot")
def removeprefix_self_unmodified():
    original_string = "Apricot"
    assert_eq(original_string.removeprefix("Apr"), "icot")
    assert_eq(original_string, "Apricot")
removeprefix_self_unmodified()
assert_fails(lambda: "1234".removeprefix(1), "got value of type 'int', want 'string")

# removesuffix
assert_eq("Apricot".removesuffix("cot"), "Apri")
assert_eq("Apricot".removesuffix("Cot"), "Apricot")
assert_eq("Apricot".removesuffix("t"), "Aprico")
assert_eq("a".removesuffix(""), "a")
assert_eq("".removesuffix(""), "")
assert_eq("".removesuffix("a"), "")
assert_eq("Apricot".removesuffix("co"), "Apricot")
assert_eq("Apricotcot".removesuffix("cot"), "Apricot")
def removesuffix_self_unmodified():
    original_string = "Apricot"
    assert_eq(original_string.removesuffix("cot"), "Apri")
    assert_eq(original_string, "Apricot")
removesuffix_self_unmodified()
assert_fails(lambda: "1234".removesuffix(4), "got value of type 'int', want 'string")
