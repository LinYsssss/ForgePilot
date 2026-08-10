#!/usr/bin/env python3
"""Batch-B structure census: classes >500 lines, methods with >60 body lines.

Method detection = brace-depth scan over comment/string-stripped source:
a '{' whose preceding header (since last ';{}') contains '(...)' and does not
start with a control keyword / type declaration / lambda / array initializer
is treated as a method (constructors and static initializers with args count).
Body lines = closing-brace line - opening-brace line - 1 (lines strictly
between the braces, blank lines and comment-only lines INCLUDED).

Known precision limits (documented for the audit trail):
- multi-line signatures: header joined, method reported at '{' line;
- annotations with class-literal args on their own line may prepend noise to
  the header (harmless: keyword filter still applies);
- switch expressions with '->' arms: '->' filter drops lambda bodies, so a
  method consisting mostly of a switch-with-braces is still counted at the
  method level (arm blocks are inner braces, tracked by depth, fine);
- text blocks: handled (三引号 stripped like strings).
Cross-checked: total method count vs grep of visibility keywords is in the
same ballpark; the single >500 class matches `wc -l`.
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "backend/src/main/java")

CONTROL = re.compile(
    r"^\s*(if|else|for|while|do|switch|try|catch|finally|synchronized|return|throw|new|case|default)\b"
)
TYPE_DECL = re.compile(r"\b(class|interface|enum|record)\s+\w+")


def strip_noise(src: str) -> str:
    """Remove comments, string/char/text-block literals; keep newlines."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j == -1 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            seg = src[i : (n if j == -1 else j + 2)]
            out.append("\n" * seg.count("\n"))
            i = n if j == -1 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            seg = src[i : (n if j == -1 else j + 3)]
            out.append("\n" * seg.count("\n"))
            i = n if j == -1 else j + 3
        elif c == '"' or c == "'":
            q, j = c, i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == q:
                    break
                j += 1
            i = min(j + 1, n)
        else:
            out.append(c)
            i += 1
    return "".join(out)


def scan_file(path: Path):
    src = strip_noise(path.read_text(encoding="utf-8"))
    line = 1
    header: list[str] = []   # chars since last ; { }
    stack: list[tuple[str, int]] = []  # (kind, open_line); kind: method|block
    methods = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            header.append(" ")
        elif c == "{":
            h = "".join(header).strip()
            header = []
            kind = "block"
            if (
                "(" in h
                and ")" in h
                and "->" not in h
                and "=" not in h.split("(")[0]
                and not CONTROL.match(h)
                and not TYPE_DECL.search(h)
                and not h.endswith("]")
            ):
                kind = "method"
            stack.append((kind, line))
        elif c == "}":
            header = []
            if stack:
                kind, open_line = stack.pop()
                if kind == "method":
                    body = line - open_line - 1
                    methods.append((open_line, body))
        elif c in ";":
            header = []
        else:
            header.append(c)
        i += 1
    return methods


def main():
    files = sorted(ROOT.rglob("*.java"))
    big_classes = []
    big_methods = []
    total_methods = 0
    for f in files:
        total = sum(1 for _ in f.open())
        if total > 500:
            big_classes.append((total, f))
        for open_line, body in scan_file(f):
            total_methods += 1
            if body > 60:
                big_methods.append((body, f, open_line))
    rel = lambda p: str(p.relative_to(ROOT))
    print(f"files={len(files)} methods_detected={total_methods}")
    print(f"\n== classes >500 lines: {len(big_classes)} ==")
    for total, f in sorted(big_classes, reverse=True):
        print(f"{total:5d}  {rel(f)}")
    print(f"\n== methods >60 body lines: {len(big_methods)} ==")
    for body, f, open_line in sorted(big_methods, reverse=True):
        # recover method name from the source line(s) near open_line
        lines = f.read_text(encoding="utf-8").splitlines()
        sig = ""
        for k in range(open_line - 1, max(-1, open_line - 6), -1):
            probe = lines[k].strip()
            m = re.search(r"(\w+)\s*\(", probe)
            if m and not probe.startswith(("@", "//", "*")):
                sig = m.group(1)
                break
        print(f"{body:4d}  {rel(f)}:{open_line}  {sig}()")


if __name__ == "__main__":
    main()
