# ForgePilot evaluation score — DIFF_ONLY

- Contract: `forgepilot-evaluation-score-report-v1`; matching: `forgepilot-deterministic-match-v1`
- Corpus: `forgepilot-phase1-quick-v1`; case set: `phase1-quick-v1`
- Run kind: `MODEL_EVALUATION`; arm: `DIFF_ONLY`

## Finding metrics

| Metric | Value |
| --- | ---: |
| Precision | 10.00% |
| Recall | 11.11% |
| False-report rate | 90.00% |
| Miss rate | 88.89% |
| TP / FP / FN | 1 / 9 / 8 |

## Execution and usage

- Attempted 12; completed 12; failed 0; structure failures 0; not run 0.
- Requirement-violation recall: 0.00%.
- Tokens (input/output): 10768 / 5120; mean latency: 8648.25 ms.

Synthetic/reference outputs validate the scorer contract only; they are not model-quality measurements.
