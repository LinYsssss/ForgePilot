# Legacy scorer baseline

- Frozen source commit: `96137dd3b43e14c5e8881c99688663afd979cf4e`
- Source file: `evaluation/tools/score.py` from `LinYsssss/reposage`
- Downloaded temporary file SHA-256:
  `14c28f41c39768eedc8daab25fe97f8f8b79f8d72f2aad82704e87a644e85f6f`
- Command: `python3 /tmp/forgepilot-legacy-score-96137dd.py --selftest`
- Result: `SELFTEST OK (30 checks)`, exit code 0.

Only the frozen scorer file was read into `/tmp`; no Legacy package, runtime,
fixture set, or generated result was copied into ForgePilot. The scorer's
internal synthetic split checks did not load or execute the frozen evaluation
corpus.
