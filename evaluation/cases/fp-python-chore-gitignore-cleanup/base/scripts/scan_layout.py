"""扫描仓库目录结构，生成 scripts/reports/layout-report.json。

用法: python3 scripts/scan_layout.py [根目录]
报告是随时可重建的生成物，不应提交入库。
"""

import json
import sys
from pathlib import Path

REPORT_PATH = Path(__file__).parent / "reports" / "layout-report.json"
SKIP_DIRS = {".git", "__pycache__", "node_modules", "dist"}


def scan(root: Path) -> dict:
    modules = []
    for child in sorted(root.iterdir()):
        if not child.is_dir() or child.name in SKIP_DIRS:
            continue
        file_count = sum(1 for p in child.rglob("*") if p.is_file())
        modules.append({"name": child.name, "files": file_count})
    return {"root": root.name, "modules": modules}


def main() -> None:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    report = scan(root)
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"layout report written: {REPORT_PATH}")


if __name__ == "__main__":
    main()
