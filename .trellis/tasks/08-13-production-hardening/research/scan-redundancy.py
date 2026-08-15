"""Backend redundancy scan: find main-source classes nothing references.

Reference counting is deliberately crude and over-inclusive (plain identifier match
anywhere outside the defining file, including comments, YAML and test sources) because
a false "unused" here means deleting live code. Anything with a single hit is kept.
"""
import os
import re
import json
from collections import defaultdict

MAIN = os.path.join("backend", "src", "main", "java")
SEARCH_ROOTS = [
    os.path.join("backend", "src", "main"),
    os.path.join("backend", "src", "test"),
]

# name -> defining file
defs = {}
for root, _dirs, files in os.walk(MAIN):
    for f in files:
        if f.endswith(".java"):
            name = f[:-5]
            if name == "package-info":
                continue
            defs.setdefault(name, os.path.join(root, f))

# Collect every searchable file once.
corpus = []
for sr in SEARCH_ROOTS:
    for root, _dirs, files in os.walk(sr):
        for f in files:
            if f.endswith((".java", ".yml", ".yaml", ".properties", ".sql", ".xml", ".json")):
                p = os.path.join(root, f)
                try:
                    with open(p, encoding="utf-8", errors="ignore") as fh:
                        corpus.append((p, fh.read()))
                except OSError:
                    pass

hits = defaultdict(lambda: {"main": 0, "test": 0, "files": []})
for name, defpath in defs.items():
    pat = re.compile(r"\b" + re.escape(name) + r"\b")
    for path, text in corpus:
        if os.path.abspath(path) == os.path.abspath(defpath):
            continue
        n = len(pat.findall(text))
        if n:
            bucket = "test" if (os.sep + "test" + os.sep) in path else "main"
            hits[name][bucket] += n
            if len(hits[name]["files"]) < 6:
                hits[name]["files"].append(path.replace("\\", "/"))

dead, test_only = [], []
for name, defpath in sorted(defs.items()):
    h = hits[name]
    if h["main"] == 0 and h["test"] == 0:
        dead.append({"name": name, "file": defpath.replace("\\", "/")})
    elif h["main"] == 0:
        test_only.append({"name": name, "file": defpath.replace("\\", "/"),
                          "testRefs": h["test"], "where": h["files"]})

print(json.dumps({
    "totalMainClasses": len(defs),
    "zeroReferences": dead,
    "testOnlyReferences": test_only,
}, ensure_ascii=False, indent=2))
