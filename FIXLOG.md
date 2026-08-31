# FIXLOG — the dispatch/commit axis separation

One entry per stage. Each records what changed, what it was measured against,
and — where a stage claims a capability gain — which golden files moved and
which `(DispatchLocus, CommitForm)` pair became reachable.

The faithfulness instrument throughout is `scripts/capture-golden.sh`, which runs
the CLI over every fixture directory into its own output tree. `.dot` and
`.scxml` are the contract; `summary.txt` is captured beside them and is where an
intended gain shows up first.

---

## Stage 0 — independent fixes

### Already present at HEAD (verified, not re-implemented)

Three of the five stage-0 items are on `master` already, each as a numbered
finding with its own fixture and its own regression test. Re-implementing them
would have been a rewrite of working code against a spec written from an older
snapshot, so each was verified in place instead:

| stage-0 item | already at HEAD as | where |
|---|---|---|
| `alwaysTerminates` handles `CtThrow` and an all-arms-terminating `CtSwitch` | **F14** | `TransitionExtractor.alwaysTerminates` / `switchAlwaysTerminates`, fixture `examples/throwguards` |
| `isReassigned` compares declaration identity, falls back to the name, reports the fallback | **F13** | `TransitionResolver.isReassigned` + `nameOnlyReassignmentChecks`, fixture `examples/scopedlocals` |
| `detectInitialState` rule 1 requires unanimity | **F15** | `Analyzer.detectInitialState`, fixture `examples/rivalseeds` |

The existing implementations are strictly stronger than the stage-0 wording in
two places, and both differences are deliberate:

* `alwaysTerminates` does not accept *any* switch whose arms terminate. It
  additionally requires the switch to be **exhaustive** and to contain **no
  `break`** (JLS §14.22). Dropping either turns the predicate into a
  fabrication: a non-exhaustive switch falls through on an unmatched selector,
  and a `break` resumes control exactly at the fall-through this predicate
  denies. `examples/throwguards` carries a negative control for each.
* `isReassigned`'s name fallback answers **reassigned**, i.e. it costs a
  resolvable read rather than inventing an unresolvable one, and emits a
  diagnostic naming the variable.

### Unresolved-type counter — present as **F23**, and kept stratified

`TypeResolutionAudit` already collects every unresolved `CtTypeReference` in one
model-wide pass. It is reported at three severities rather than one, and I did
**not** flatten it to the requested single model-level `WARN`:

* model level, **INFO** — count plus a sample;
* per hierarchy, **WARN** — an unresolved *permitted subtype*, or an unresolved
  name sharing a simple name with a member;
* on a rejection, **WARN** — so "no transition producer found" and "I could not
  read the types" stop being the same output.

The reason is the one `Analyzer.reportModelResolution` states in place: run over
a real project with `--src src/main/java`, *every* third-party type is
unresolved and almost none of it matters. A `WARN` there is noise on every
invocation, and a severity that does not discriminate is one a reader learns to
skip — which would cost the per-hierarchy warning its whole value. Say the word
and it is a one-line change.

### Implemented in this stage

* **`--classpath <cp>` / `--cp`** — repeatable, split on the platform path
  separator, passed to `Launcher.getEnvironment().setSourceClasspath`.
  `setNoClasspath(true)` **stays on with it**: the two are not alternatives.
  noClasspath is what lets an incomplete classpath degrade into a guessed
  qualified name instead of aborting the build, and no realistic invocation
  supplies every transitive dependency. `--classpath` shrinks the set of
  references that must be guessed; F23's audit reports whatever is left.
* **`--explain`** — for every **rejected** sealed root, each classifier
  predicate and why it failed.
  * Implemented as `classify(root, model, Consumer<String> trace)`, with the
    old two-argument form delegating to it. It is a side channel on the real
    decision procedure, **not** a second procedure that re-derives the verdict
    for display: this codebase refuses two notions of one thing everywhere else
    (one `chainOf`, one commit predicate, one `StateNaming`) for the standing
    reason that the copy drifts, and a report contradicting the verdict it
    explains is worse than no report.
  * Rejections only. An acceptance already names the predicate that carried it,
    in the reason text printed for every machine; a rejection names only the
    predicate that ran last, and "no transition producer found" reads the same
    whether seven predicates were evaluated or one.
  * The carrier predicate is reported by **which conjunct failed** — no carrier
    methods at all, carrier methods on only one member, or carrier methods that
    only ever produce their own declaring type. It is one boolean to the
    classifier and three different situations to a reader.
  * Printed regardless of `--quiet`, on the same reasoning as the unread-
    declaration footnote: `--quiet` suppresses findings about the run, and this
    was explicitly asked for.

### Acceptance

`mvn test` — **167/167 green**. `diff -r target/golden-base target/golden-stage0`
— **empty**, all 40 fixtures, `.dot`, `.scxml` and `summary.txt` alike.
