"""Profile the no-Chinese backend files so batching is driven by what is actually there.

Distinguishes three shapes, because they need different treatment:
  - has English prose comments  -> rewrite in Chinese (the real work)
  - only trivial/no comments    -> adding prose would be filler; needs judgement, not bulk edit
  - tiny files (records/enums)  -> usually self-evident; comment only if the *why* is non-obvious
"""
import os
import re
import json

ROOT = os.path.join("backend", "src", "main", "java")
HAN = re.compile(r"[一-鿿]")
# Comment bodies: block comments and // lines.
BLOCK = re.compile(r"/\*.*?\*/", re.S)
LINE = re.compile(r"//[^\n]*")
# A comment counts as "prose" if it has >=3 alphabetic words; license/blank/`// TODO` alone doesn't.
WORD = re.compile(r"[A-Za-z]{2,}")

rows = []
for root, _d, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".java"):
            continue
        p = os.path.join(root, f)
        with open(p, encoding="utf-8", errors="ignore") as fh:
            src = fh.read()
        if HAN.search(src):
            continue  # already has Chinese, out of scope
        comments = BLOCK.findall(src) + LINE.findall(src)
        prose = [c for c in comments if len(WORD.findall(c)) >= 3]
        loc = src.count("\n") + 1
        rows.append({
            "file": p.replace("\\", "/"),
            "loc": loc,
            "commentBlocks": len(comments),
            "proseComments": len(prose),
            "proseChars": sum(len(c) for c in prose),
        })

with_prose = [r for r in rows if r["proseComments"] > 0]
no_prose = [r for r in rows if r["proseComments"] == 0]
print(json.dumps({
    "noChineseFiles": len(rows),
    "withEnglishProse": len(with_prose),
    "withoutAnyProse": len(no_prose),
    "proseCharsTotal": sum(r["proseChars"] for r in with_prose),
    "tinyNoProse(<=40 loc)": len([r for r in no_prose if r["loc"] <= 40]),
    "topByProse": sorted(with_prose, key=lambda r: -r["proseChars"])[:15],
}, ensure_ascii=False, indent=2))
