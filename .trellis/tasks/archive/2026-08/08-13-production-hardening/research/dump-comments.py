"""Print only the English prose comment blocks of the given files, with the line they document.

Reading whole files to retranslate a handful of comments wastes a lot of context; the comment
plus the declaration it sits above is enough to translate faithfully.
"""
import re
import sys

BLOCK = re.compile(r"[ \t]*/\*\*?.*?\*/", re.S)
LINE = re.compile(r"(?:^[ \t]*//[^\n]*\n)+", re.M)
WORD = re.compile(r"[A-Za-z]{2,}")
HAN = re.compile(r"[一-鿿]")

for path in sys.argv[1:]:
    with open(path, encoding="utf-8", errors="ignore") as fh:
        src = fh.read()
    spans = [(m.start(), m.end(), m.group()) for m in BLOCK.finditer(src)]
    spans += [(m.start(), m.end(), m.group().rstrip("\n")) for m in LINE.finditer(src)]
    spans.sort()
    out = []
    for start, end, text in spans:
        if len(WORD.findall(text)) < 3 or HAN.search(text):
            continue
        nextline = src[end:].lstrip("\n").split("\n", 1)[0].strip()
        out.append((src[:start].count("\n") + 1, text.rstrip(), nextline))
    if out:
        print("=" * 12, path.replace("\\", "/"))
        for lineno, text, nxt in out:
            print(f"--- L{lineno}  >>> {nxt[:90]}")
            print(text)
