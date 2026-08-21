# Phase 1 capacity evidence index

The user approved a shortened capacity protocol on 2026-08-20: five minutes
of existing-service baseline, two minutes of warm-up, and at least four
minutes of steady empty-stack sampling. This evidence supports only a short-
window empty-stack capacity claim; it is not long-term stability evidence.

The authoritative run for PRD AC10 is **`20260821T030713Z-53877bba63ce`**. It is
the only run that satisfies every approved gate on the current `compose.yaml`
(`2dd6d1d4…`). The four earlier runs are retained as required, but none of them
can support AC10 — see the per-run reasons below.

Gate and collector changes made before the authoritative run:

- `journalctl --since` was passed an ISO-8601 `...Z` timestamp, which journalctl
  rejects. `2>&1` wrote that parse error into `kernel-after.log` and `|| true`
  swallowed the failure, so the `kernel_oom` gate searched a 48-byte error string
  and was **vacuously green in all four earlier runs**. The cutoff now uses
  `%F %T`, stderr goes to a separate `kernel-*.err`, and an unavailable journal
  writes `KERNEL_JOURNAL_UNAVAILABLE`, which fails the gate instead of passing it.
- Metaspace was never sampled as a scalar, although `validation.md` §9 requires it
  to have a real value. `jvm.memory.used|max?tag=id:Metaspace` is now recorded.
- `existing_services` compared only `(Running, RestartCount)`. Docker does not
  increment `RestartCount` when `compose up` recreates a container, so the check
  now also compares `State.StartedAt`, which was already being captured.

Runs schedule each sample against a `/proc/uptime` monotonic deadline, so
collection work is not added to the approved 15-second interval. The two runs
from before that change (`072414Z`, `073017Z`, `074544Z`) drifted above 15 s.

## Runs

| Run | Result | Reason / evidence |
| --- | --- | --- |
| `20260820T072414Z-53877bba63ce` | INVALID | A short-lived existing-container PID exited between enumeration and reading `/proc/<pid>/smaps_rollup`. The run stopped during baseline before ForgePilot started. The incomplete evidence is retained. |
| `20260820T073017Z-53877bba63ce` | FAIL | The full protocol completed and every check of the 17-check set then in force passed except an implementation-only rule requiring exactly zero swap pages. `SwapUsed` remained fixed at 22,000 KiB; three pages in and one page out were not sustained growth. That overly strict rule was corrected **onto** the approved contract (`<=64 MiB` relative growth plus no three consecutive active intervals), not loosened below it. Retained. |
| `20260820T074544Z-53877bba63ce` | superseded (recorded PASS, **cannot support AC10**) | Recorded as PASS against the 17-check set that existed at the time. It fails two gates that exist now: baseline sample gaps were 17/18/19 s and steady gaps 19/20/21 s, violating the approved 15-second cadence (PRD R7, `validation.md` §9); and it measured `compose.sha256 = 121a6e94…`, which is no longer the repository's Compose file. Its `kernel_oom` gate was also vacuously green. Retained as history only. |
| `20260820T091924Z-53877bba63ce` | INVALID | The run stopped during the privileged Docker/system preflight before baseline sampling. No capacity conclusion was produced; the incomplete evidence is retained. |
| `20260820T092008Z-53877bba63ce` | FAIL | The strict-cadence protocol completed on the current Compose file: 21 baseline samples over 300 s and 17 steady samples over 244 s, cadence exactly 15 s, all health, memory, OOM, restart, Docker-event and database checks passing. Only the implementation-only `SwapUsed`-must-be-constant condition failed: steady swap moved by 256 KiB and stayed 768 KiB above the baseline median, with no sustained activity. That condition was replaced by the approved gates. The run's verdict was never re-scored — it is retained as recorded FAIL, not silently promoted. |
| **`20260821T030713Z-53877bba63ce`** | **PASS (authoritative for AC10)** | 21 baseline samples over 300 s and 17 steady samples over 240 s, every gap exactly 15 s. `MemAvailable` minimum 2,451,524 KiB (2.338 GiB) with every steady sample above the 1 GiB floor. `SwapUsed` constant at 18,672 KiB, growth 0. 19/19 gates green, including a genuinely evaluated `kernel_oom` (2,940 bytes of real kernel journal, zero OOM markers, empty stderr) and real Metaspace values (used 70,037,552 B, max 134,217,728 B = the configured 128 MiB). `compose.sha256 = 2dd6d1d4…` matches the repository. Existing `cpa-manager-plus` and `cli-proxy-api` kept identical `Running`, `RestartCount` **and** `StartedAt`. |

## Evidence handling

Container inspect evidence is deliberately allowlisted to runtime fields:
image, created/started time, health, restart/OOM state, and resource limits.
`Config.Env` is never retained by the current runner. Earlier run files were
mechanically transformed through the same allowlist after collection so that
third-party service environment values cannot enter version control; no
capacity measurement or state field was changed.

Rendered Compose evidence is captured with `docker compose config
--no-interpolate`, so repository variable expressions are retained instead of
resolved environment values. The three runs created before this safeguard were
mechanically redacted in their YAML/JSON copies; their service graph, resource
limits, image identities, and measurements were not changed. Capacity `.log`
files are explicitly versioned despite the repository-wide runtime-log ignore
rule because they contain the raw build, lifecycle, and kernel OOM evidence.
Captured log lines are normalized only by removing trailing horizontal
whitespace so that the repository whitespace gate remains enforceable; message
content and ordering are unchanged.

The existing `cpa-manager-plus` and `cli-proxy-api` containers had identical
start times and restart counts before and after the PASS window. The user
updated those services later, at 08:21–08:22 UTC, after the PASS run ended at
08:00:10 UTC; that later update is outside the measured window.
