#!/usr/bin/env python3
"""Batch C dependency scan: package-level import graph for backend main sources.

Scans backend/src/main/java for imports between com.example.codereview.* packages,
aggregates edges at top-level package granularity (agent, common, review, ...),
reports edge counts and cycles. Re-runnable; used as evidence for
.trellis/tasks/08-03-r5-backend-refactor/batch-c-boundaries.md.
"""
import os
import re
import sys
from collections import defaultdict

ROOT = "/root/reposage/backend/src/main/java"
BASE = "com.example.codereview"

PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", re.M)
# Inline FQN usage without import (rare, but must not be missed)
INLINE_RE = re.compile(r"com\.example\.codereview\.[\w.]+")


def top(pkg: str) -> str:
    """Top-level package under BASE, e.g. com.example.codereview.agent.run -> agent."""
    rest = pkg[len(BASE):].lstrip(".")
    return rest.split(".")[0] if rest else "<root>"


def main() -> None:
    edges = defaultdict(int)            # (src_top, dst_top) -> import count
    edge_details = defaultdict(list)    # (src_top, dst_top) -> ["src.Class -> imported.symbol"]
    files = 0
    inline_hits = []

    for dirpath, _dirnames, filenames in os.walk(ROOT):
        for fn in sorted(filenames):
            if not fn.endswith(".java"):
                continue
            files += 1
            path = os.path.join(dirpath, fn)
            with open(path, encoding="utf-8") as f:
                text = f.read()
            m = PKG_RE.search(text)
            if not m:
                continue
            src_pkg = m.group(1)
            src_top = top(src_pkg)
            cls = fn[:-5]

            imported = set()
            for im in IMPORT_RE.findall(text):
                if im.startswith(BASE + "."):
                    imported.add(im)
                    # symbol package: strip trailing class (and member for static)
                    parts = im.split(".")
                    # walk back until segment starts lowercase => package boundary
                    while parts and parts[-1][:1].isupper():
                        parts.pop()
                    dst_pkg = ".".join(parts)
                    dst_top = top(dst_pkg)
                    if dst_top != src_top:
                        edges[(src_top, dst_top)] += 1
                        edge_details[(src_top, dst_top)].append(
                            f"{src_pkg}.{cls} -> {im}")

            # inline FQN usage not covered by an import statement
            body = IMPORT_RE.sub("", PKG_RE.sub("", text))
            for hit in INLINE_RE.findall(body):
                trimmed = hit.rstrip(".")
                if trimmed == BASE:
                    continue
                covered = any(trimmed.startswith(im) or im.startswith(trimmed)
                              for im in imported)
                if not covered and top(trimmed) != src_top:
                    inline_hits.append(f"{src_pkg}.{cls} uses inline FQN {trimmed}")

    tops = sorted({t for e in edges for t in e})

    print(f"# files scanned: {files}")
    print(f"# top-level packages with cross-package edges: {len(tops)}")
    print()
    print("## Edge list (src -> dst : import count)")
    for (s, d), c in sorted(edges.items()):
        print(f"{s} -> {d} : {c}")

    # cycle detection on top-level digraph (Tarjan SCC)
    graph = defaultdict(set)
    for (s, d) in edges:
        graph[s].add(d)

    index_counter = [0]
    stack, lowlink, index, on_stack = [], {}, {}, {}
    sccs = []

    def strongconnect(v):
        index[v] = lowlink[v] = index_counter[0]
        index_counter[0] += 1
        stack.append(v)
        on_stack[v] = True
        for w in graph[v]:
            if w not in index:
                strongconnect(w)
                lowlink[v] = min(lowlink[v], lowlink[w])
            elif on_stack.get(w):
                lowlink[v] = min(lowlink[v], index[w])
        if lowlink[v] == index[v]:
            scc = []
            while True:
                w = stack.pop()
                on_stack[w] = False
                scc.append(w)
                if w == v:
                    break
            sccs.append(scc)

    sys.setrecursionlimit(10000)
    for v in list(graph):
        if v not in index:
            strongconnect(v)

    cycles = [s for s in sccs if len(s) > 1]
    print()
    print("## Cycles (top-level SCCs with >1 node)")
    if cycles:
        for scc in cycles:
            print("CYCLE: " + " <-> ".join(sorted(scc)))
            members = set(scc)
            for (s, d), c in sorted(edges.items()):
                if s in members and d in members:
                    print(f"  {s} -> {d} : {c}")
                    for det in edge_details[(s, d)]:
                        print(f"    {det}")
    else:
        print("NONE — top-level package graph is acyclic.")

    print()
    print("## Inline FQN usages without import (cross-package)")
    if inline_hits:
        for h in inline_hits:
            print(h)
    else:
        print("NONE")

    if "--details" in sys.argv:
        print()
        print("## Full edge details")
        for (s, d), dets in sorted(edge_details.items()):
            print(f"### {s} -> {d} ({len(dets)})")
            for det in dets:
                print(f"  {det}")


if __name__ == "__main__":
    main()
