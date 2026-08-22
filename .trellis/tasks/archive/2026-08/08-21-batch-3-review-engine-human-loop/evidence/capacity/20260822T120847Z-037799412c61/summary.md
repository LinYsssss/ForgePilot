# Batch 3 maximum-budget Review capacity

- Verdict: **INVALID** (calibration run exceeded the product's 4,000,000-character canonical manifest limit)
- Host memory: 4101304320 bytes
- Production limits: backend container 768 MiB; JVM heap 384 MiB; direct 128 MiB; metaspace 128 MiB; PostgreSQL 512 MiB / pool 5
- Workload: 2 concurrent Reviews; 300 files each; stored JSONB rendering was 4,125,900 characters (about 4,124,101 canonical characters), so this run is not admissible capacity evidence
- Peak JVM heap used: 90755296 bytes
- Peak JVM direct-buffer used: 428032 bytes
- Peak Hikari active/pending: 0 / 0
- Backend/PostgreSQL OOMKilled: false
- Terminal states: COMPLETED|COMPLETED
- Provider: local OpenAI-protocol deterministic stub; production AiGateway, batching, validation, persistence and fencing were used
- Holdout/evaluation corpus: not read and not used
