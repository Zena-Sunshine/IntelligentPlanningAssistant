# Evaluation datasets

`crosswoz_external_holdout.jsonl` is deterministically sampled from unchanged,
human-authored utterances in the official CrossWOZ test split. Its provenance,
commit, license, mapping and SHA-256 are recorded in `DATASET_MANIFEST.json`.

This benchmark only covers `travel_search` and unsupported-domain `general`.
It must not be cited as an evaluation of policy, approval, planning, tool use,
or full answer quality.

The old `task_completeness_cases.json` is a development/regression set. It is
not a holdout and is not a generalization metric.

From the `agent-service` directory, verify and evaluate the frozen artifact:

```powershell
python evaluation/check_dataset_integrity.py `
  --dataset evaluation/datasets/crosswoz_external_holdout.jsonl `
  --development-set evaluation/task_completeness_cases.json `
  --sha256 393d5d70cc4458441112e71c13e4716b4327217e82df4bcb0db02e7591412a47 `
  --minimum-cases 400

$env:PYTHONPATH = "."
python evaluation/evaluate_external_router.py `
  --dataset evaluation/datasets/crosswoz_external_holdout.jsonl `
  --report ../docs/reports/agent/external-router-latest.json

python evaluation/benchmark_approval_safety.py `
  --report ../docs/reports/agent/approval-safety-latest.json

python evaluation/evaluate_structured_metrics.py `
  --dataset evaluation/datasets/product_contract_240.jsonl `
  --manifest evaluation/PRODUCT_CONTRACT_MANIFEST.json `
  --report ../docs/reports/agent/structured-metrics-latest.json
```
