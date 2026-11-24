#!/usr/bin/env python3
"""从线上 fp-demo 数据库导出一组可展示、可复算的数据表。

用法:
    python3 export-live-data.py [输出目录]        # 默认写到脚本所在目录

性质与边界:
- **只读**:全部查询经 `docker exec ... psql` 执行,不写库、不改任何业务状态。
- 只用标准库,任何人拿这份脚本 + 线上库就能重算出同样的数字。
- CSV 一律 LF(仓库 .gitattributes 会把 CRLF 归一化,行尾不一致会让摘要失真)。
- 这里只导出聚合结果与 Finding 的元数据,不导出任何凭据或账号口令字段。

表清单(每张都会打印并落 CSV):
  1 versions          各提示词版本的审查数/产出量/已处理量
  2 projects          各项目的审查与 Finding 分布
  3 findings-mix      Finding 的状态、类别、类型、连续性分布
  4 noise-reduction   重复报告与去重:总条数 vs 唯一 finding_key
  5 rounds            每轮审查的 NEW/PERSISTING/SUPPRESSED
  6 lifecycle         人工动作分布(认领/确认/驳回/已修复/验证/关闭)
  7 ai-telemetry      按用途的调用量、成功率、延迟分位、token,并按修复日切分
  8 timeline          审查活动时间线
"""

from __future__ import annotations

import csv
import math
import pathlib
import subprocess
import sys

CONTAINER = "fp-demo-postgres-1"
DB_USER = "forgepilot"
DB_NAME = "forgepilot"

# 10-14 那批修复(embedding 分批、429 退避)的部署日期,用于前后切分遥测。
FIX_DATE = "2026-10-14"


def query(sql: str) -> list[list[str]]:
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME,
         "-t", "-A", "-F", "|", "-c", sql],
        check=True, capture_output=True, text=True)
    return [line.split("|") for line in result.stdout.strip().splitlines() if line.strip()]


def emit(outdir: pathlib.Path, name: str, title: str, header: list[str], sql: str,
         print_limit: int | None = 12) -> list[list[str]]:
    rows = query(sql)
    print(f"\n== {title} ==")
    print(" | ".join(header))
    for row in rows[:print_limit] if print_limit else rows:
        print(" | ".join(row))
    if print_limit and len(rows) > print_limit:
        print(f"… 其余 {len(rows) - print_limit} 行见 {name}.csv")
    with open(outdir / f"{name}.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)
    return rows


def wilson(successes: int, total: int, z: float = 1.96) -> tuple[float, float, float]:
    """Wilson 得分区间;样本量小的时候它比正态近似更诚实。"""
    if total == 0:
        return 0.0, 0.0, 0.0
    phat = successes / total
    denom = 1 + z * z / total
    centre = (phat + z * z / (2 * total)) / denom
    half = z * math.sqrt(phat * (1 - phat) / total + z * z / (4 * total * total)) / denom
    return phat, max(0.0, centre - half), min(1.0, centre + half)


def main() -> int:
    outdir = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).parent
    outdir.mkdir(parents=True, exist_ok=True)
    print(f"输出目录: {outdir}")

    emit(outdir, "versions", "各提示词版本:审查数/产出量/已处理量",
         ["version", "reviews", "first_day", "last_day", "findings", "adjudicated", "per_review"], """
         select coalesce(r.prompt_version, '(none)') as version,
                count(distinct r.id) as reviews,
                min(r.created_at)::date as first_day,
                max(r.created_at)::date as last_day,
                count(f.id) as findings,
                count(f.id) filter (where f.status <> 'OPEN') as adjudicated,
                round(count(f.id)::numeric / nullif(count(distinct r.id), 0), 2) as per_review
           from review r left join finding f on f.review_id = r.id
          group by 1 order by 1""")

    emit(outdir, "projects", "各项目:审查/PR/需求/Finding",
         ["project", "name", "status", "prs", "reviews", "requirements", "findings", "findings_review4"], """
         select p.id, p.name, p.status,
                (select count(*) from pull_request pr where pr.project_id = p.id),
                (select count(*) from review r where r.project_id = p.id),
                (select count(*) from requirement q where q.project_id = p.id),
                (select count(*) from finding f where f.project_id = p.id),
                (select count(*) from review r join finding f on f.review_id = r.id
                  where r.project_id = p.id and r.prompt_version = 'review-4')
           from project p order by p.id""")

    emit(outdir, "findings-mix", "Finding 状态/类别/类型/连续性",
         ["dimension", "value", "count"], """
         select 'status', status, count(*) from finding group by 2
     union all select 'category', category, count(*) from finding group by 2
     union all select 'type', finding_type, count(*) from finding group by 2
     union all select 'continuity', continuity, count(*) from finding group by 2
      order by 1, 3 desc""", print_limit=None)

    emit(outdir, "noise-reduction", "重复报告与去重",
         ["findings", "distinct_keys", "inherited", "repeat_reports", "repeat_share_pct", "anchored_evidence", "distinct_paths"], """
         select count(*) as findings,
                count(distinct finding_key) as distinct_keys,
                count(*) filter (where carried_from_finding_id is not null) as inherited,
                count(*) - count(distinct finding_key) as repeat_reports,
                round(100.0 * (count(*) - count(distinct finding_key)) / count(*), 1) as repeat_share_pct,
                count(*) filter (where evidence_hash <> '' and basis_hash <> '') as anchored_evidence,
                count(distinct path) as distinct_paths
           from finding""")

    emit(outdir, "rounds", "每轮审查的 NEW/PERSISTING/SUPPRESSED",
         ["project", "review", "version", "new", "persisting", "suppressed", "findings"], """
         select r.project_id, r.id, coalesce(r.prompt_version, '(none)'),
                count(*) filter (where f.continuity = 'NEW'),
                count(*) filter (where f.continuity = 'PERSISTING'),
                count(*) filter (where f.continuity = 'SUPPRESSED'),
                count(*)
           from review r join finding f on f.review_id = r.id
          group by 1, 2, 3 order by 2""")

    emit(outdir, "lifecycle", "人工动作分布",
         ["action", "events"], """
         select action, count(*) from finding_event group by 1 order by 2 desc""", print_limit=None)

    emit(outdir, "ai-telemetry", "AI 调用:量/成功率/延迟/token(按修复日切分)",
         ["use_case", "calls_before", "ok_before", "calls_after", "ok_after", "p50_before_ms", "p95_before_ms", "tokens_before"], f"""
         select use_case,
                count(*) filter (where created_at <  '{FIX_DATE}') as calls_before,
                count(*) filter (where created_at <  '{FIX_DATE}' and status = 'SUCCESS') as ok_before,
                count(*) filter (where created_at >= '{FIX_DATE}') as calls_after,
                count(*) filter (where created_at >= '{FIX_DATE}' and status = 'SUCCESS') as ok_after,
                percentile_disc(0.5) within group (order by latency_ms)
                    filter (where created_at < '{FIX_DATE}') as p50_before_ms,
                percentile_disc(0.95) within group (order by latency_ms)
                    filter (where created_at < '{FIX_DATE}') as p95_before_ms,
                sum(total_token) filter (where created_at < '{FIX_DATE}') as tokens_before
           from ai_call_log group by 1 order by 2 desc""", print_limit=None)

    emit(outdir, "timeline", "审查活动时间线",
         ["day", "reviews", "findings"], """
         select r.created_at::date as day, count(distinct r.id), count(f.id)
           from review r left join finding f on f.review_id = r.id
          group by 1 order by 1""", print_limit=None)

    adjudication = outdir / "adjudication-review4-20261017.csv"
    if adjudication.exists():
        with open(adjudication, encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        verdicts = [row["verdict"].upper() for row in rows]
        true_count = verdicts.count("TRUE")
        decisive = sum(1 for v in verdicts if v in ("TRUE", "FALSE"))
        phat, low, high = wilson(true_count, decisive)
        print("\n== adjudication (机器预标注,待人工确认) ==")
        print(f"labels={len(rows)} TRUE={true_count} FALSE={verdicts.count('FALSE')} "
              f"UNSURE={verdicts.count('UNSURE')}")
        if decisive:
            print(f"查准率(p=TRUE/(TRUE+FALSE))={phat:.3f} "
                  f"95% Wilson=[{low:.3f}, {high:.3f}]  n={decisive}")
    else:
        print(f"\n(未找到 {adjudication.name};标注完成后重跑本脚本即可输出查准率与区间)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
