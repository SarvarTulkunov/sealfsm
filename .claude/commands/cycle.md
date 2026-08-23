---
description: Run one full evaluation cycle — qa-tester, reporter, triage-lead, implementer (if triage warrants), qa-tester regression, senior-advisor — into reports/<today>/.
---

Run one complete evaluation cycle. Read `CORPUS_PROTOCOL.md` first; it governs every
step below.

**This command must never invoke `researcher`.** Harvesting stays a separate, deliberate
act — see `/harvest`. If this loop could also produce its own examples, the cycle would
close automatically: fail, harvest something that fits, fix, pass. The gate between "an
example failed" and "the code changes" only means anything while the corpus is fixed for
the duration of the cycle.

Let `<today>` be the current date as `YYYY-MM-DD`. All artefacts go in
`reports/<today>/`. Create the directory if it does not exist.

**Halt on failure at any step.** Report what completed, what did not, and why. Do not
continue past a failed step and do not paper over a partial artefact — a cycle that ran
half way and reported a full result is worse than one that stopped.

Run these in order, each as a subagent:

1. **`qa-tester`** — scope `dev`. Do **not** pass `UNSEAL-HOLDOUT`; it must not appear
   anywhere in the instruction unless the human has explicitly declared a freeze in the
   invocation of this command. Writes `results.json`, `run.log`, `findings.md`,
   `summary.txt` into `reports/<today>/`.

2. **`reporter`** — writes `reports/<today>/report.md` from that day's artefacts, the
   previous dated run (for deltas) and `examples/*/meta.json`.

3. **`triage-lead`** — writes `reports/<today>/triage.md`, classifying every finding as
   FIX-REQUIRED, FIX-ELIGIBLE, DEFERRED or WONTFIX.

4. **`implementer`** — **only if** `triage.md` contains at least one FIX-REQUIRED or
   FIX-ELIGIBLE item. If it contains none, skip this step and say so explicitly; a cycle
   with no code change is a normal and often correct outcome.

   Pass it the FIX-REQUIRED and FIX-ELIGIBLE entries from `triage.md` and nothing else.
   **Do not quote or summarise any example, any tool output, any finding text, or any
   report content in its instruction.** It works from the abstract node shapes alone,
   and anything else you include defeats the isolation the whole pipeline is built on.

5. **`qa-tester` again** — regression run, same `dev` scope, diffing against the run
   earlier in this cycle. Skip this step if step 4 was skipped. Regressions lead the
   summary.

6. **`senior-advisor`** — read-only audit. Write its output verbatim to
   `reports/<today>/advisory.md`. The advisor produces review text; **you** write the
   file, since the advisor modifies nothing.

Close with a short status: the counts from the run, whether the implementer ran and what
it changed, any regressions, and the advisor's single verdict. Do not editorialise the
verdict and do not act on the advisory's blocking items in this command — they are input
to the next cycle.
