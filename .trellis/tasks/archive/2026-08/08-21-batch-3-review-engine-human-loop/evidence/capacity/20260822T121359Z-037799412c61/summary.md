# Batch 3 maximum-budget Review capacity

- Verdict: **PASS**
- Host memory: 4101304320 bytes
- Production limits: backend container 768 MiB; JVM heap 384 MiB; direct 128 MiB; metaspace 128 MiB; PostgreSQL 512 MiB / pool 5
- Workload: 2 concurrent Reviews; 300 files each; canonical manifest 3989101 characters each (required range 3,900,000..4,000,000); no truncation accepted
- Peak JVM heap used: 83167816 bytes
- Peak JVM direct-buffer used: 428032 bytes
- Peak Hikari active/pending: 1 / 0
- Backend/PostgreSQL OOMKilled: false
- Terminal states: COMPLETED|COMPLETED
- Provider: local OpenAI-protocol deterministic stub; production AiGateway, batching, validation, persistence and fencing were used
- Holdout/evaluation corpus: not read and not used
