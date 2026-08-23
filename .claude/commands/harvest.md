---
description: Invoke researcher to harvest new corpus examples, optionally fulfilling outstanding corpus requests from recent triage.md files, then regenerate sources.json and SOURCES.md.
argument-hint: "[what to harvest, e.g. 'three Rust enum state machines' or 'outstanding requests']"
---

Harvest new corpus examples. Read `CORPUS_PROTOCOL.md` first; it governs what qualifies,
the oracle-before-conversion order, the negative quota, deduplication and provenance.

Harvesting is a **separate, deliberate act**. It is not part of `/cycle`, and it must not
be run as a reaction to a failing example — harvesting something that happens to fit a
just-discovered gap is the tool-fitting loop wearing a different hat.

## Steps

1. **Collect outstanding corpus requests.** Read the `DEFERRED` sections of the most
   recent `reports/*/triage.md` files and gather the abstract corpus requests in them.
   Each is a node-shape description, e.g. "a sealed switch whose result is written back
   to a field of the hierarchy type".

2. **Invoke `researcher`.** Pass:
   - the user's harvesting instruction (`$ARGUMENTS`), if any;
   - the collected corpus requests, **as node-shape descriptions only**.

   **Do not pass, quote, paraphrase or link:** the failing example a request came from,
   its id, its domain, any tool output, any finding text, any report content, or
   anything from `FIXLOG.md`. The researcher must not know what SealFSM currently
   handles — if it does, its selection is no longer independent of the tool, and the
   fixtures it produces stop being evidence.

3. **Regenerate `sources.json` and `SOURCES.md`** from the `meta.json` files.
   `researcher` does this at session end; verify it happened and that neither file was
   hand-edited. They are derived artefacts, and a hand-edited derived artefact records
   nothing.

## After the harvest

Report:

- new example ids with their splits — **for holdout ids, the id and split only, never
  the content**;
- corpus composition after the harvest: totals by language, by idiom, by
  `commit_shape`, by `expected_verdict`, by `oracle_provenance` tier;
- whether the negative quota (at least one in four `not_fsm`) still holds;
- which `fp_family` values still have no witness;
- any `structural_signature` at its cap of three.

Do **not** run `/cycle` afterwards as part of this command. Measuring the new examples is
a separate decision, made deliberately.
