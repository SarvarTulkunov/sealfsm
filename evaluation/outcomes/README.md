# Tool outcomes

One directory per corpus entry, `<id>/`, holding what the manifest's command
wrote. That is `sealfsm-result.json` (from `--json`), the DOT/SCXML files, and
the printed summary. Record the SealFSM revision that produced it in the
manifest. Score with:

```bash
python scripts/evaluation/score.py evaluation/corpus/*.json
```
