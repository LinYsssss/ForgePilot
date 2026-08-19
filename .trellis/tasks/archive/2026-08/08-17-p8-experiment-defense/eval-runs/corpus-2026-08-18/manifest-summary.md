# P8 Corpus Manifest Summary

- Generated: 2026-08-18
- Source: evaluation/manifest.json
- Corpus: pr-gatekeeper-eval-v1
- Schema: evaluation-manifest-v2
- Cases: 38
- Acceptance criteria: 83
- Consistency truth rows: 83
- Expected findings: 31
- Non-finding constraints: 52

## Split

| Split | Cases |
|---|---:|
| development | 26 |
| holdout | 12 |

## Language

| Language | Cases |
|---|---:|
| JAVA | 32 |
| PYTHON | 2 |
| TYPESCRIPT | 4 |

## Consistency truth

| Verdict | Rows |
|---|---:|
| AT_RISK | 31 |
| COVERED | 52 |

## Fixed run

```json
{
  "toolImage": "reposage-tools@sha256:abcdef",
  "model": "z-ai/glm-5.2",
  "promptVersion": "pr-gatekeeper-v1",
  "findingSchemaVersion": "finding-v1",
  "temperature": 0,
  "maxModelCalls": 8,
  "maxToolCalls": 32,
  "maxTokens": 24000,
  "timeoutSeconds": 900
}
```

This is a generated mirror summary; evaluation/manifest.json remains the only annotation source.
