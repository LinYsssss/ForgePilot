# Formal evaluation lock

`config.json` is the model-facing and scoring configuration for the final 26 development + 12 holdout experiment. The order is mandatory:

1. Complete and commit all product, runner, prompt/schema, scorer, alias, and contract changes.
2. Create and verify the configuration freeze. The command refuses a dirty tracked worktree and refuses to run if a corpus workspace or holdout ledger already exists.
3. Import the locked Legacy commit locally. Import is impossible without a valid freeze and writes the private corpus only to the ignored `evaluation/private-formal-corpus/` workspace.
4. Run development, then run holdout exactly once. The holdout command exclusively creates the canonical ledger before its first provider call. A provider failure remains a failed case; an interruption leaves explicit `NOT_RUN` cases and does not permit a restart.
5. Recompute split and full-corpus reports from preserved raw envelopes. Derived reports may be regenerated into a new directory without another provider call.

```bash
python3 evaluation/tools/formal_evaluation.py freeze
python3 evaluation/tools/formal_evaluation.py verify-freeze
python3 evaluation/tools/formal_evaluation.py import
python3 evaluation/tools/formal_evaluation.py run --split development
python3 evaluation/tools/formal_evaluation.py run --split holdout
python3 evaluation/tools/formal_evaluation.py report

# Deterministic rescore into a fresh location; no API key/provider call is used.
python3 evaluation/tools/formal_evaluation.py report --out-dir /tmp/forgepilot-formal-rescore
```

The model credential is read only from `OPENAI_API_KEY`. The freeze records only whether it was present; neither the key nor request authorization headers enter an artifact.
