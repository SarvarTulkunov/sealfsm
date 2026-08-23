# CORPUS_PROTOCOL.md — the binding contract for SealFSM corpus work

This document governs how examples enter the corpus, how they are evaluated, when the
analyser may change, and how results may be reported. Every agent reads this file first
and every agent is bound by it. Where an agent definition and this protocol disagree,
this protocol wins.

---

## 0. Why this exists

SealFSM makes claims of different strength, and they must never be conflated:

1. **States are recovered EXACTLY.** They come from the `permits` clause, which the
   compiler checks for exhaustiveness. A permitted `enum` contributes its constants as
   child states, and that stays exact for the same reason.
2. **Transitions are recovered APPROXIMATELY**, by intra-procedural data-flow analysis,
   and are reported with precision and recall.
3. **A transition the analysis cannot prove is recorded as UNRESOLVED.** It is never
   dropped and never fabricated. This is a soundness invariant, not a convenience.

The methodological risk this protocol controls is a specific one:

> Examples are harvested. The tool fails on them. The code is adjusted. The examples
> pass. Repeated, this measures **tool-fitting**, not generality — and the resulting
> recall figure becomes a measurement of the author's diligence rather than of the
> algorithm.

Two controls address it, and only two:

- **Role separation.** The party that harvests examples does not read the analyser. The
  party that changes the analyser does not read the examples. The party that approves a
  change neither writes code nor reads the corpus.
- **Input isolation.** A fix is authorised from an abstract description of a Spoon node
  shape, never from the failing example itself.

Both controls are enforced literally. An agent that reads outside its permitted set has
invalidated that cycle's results, and the cycle must be re-run.

---

## 1. Roles, and what each may read

| Role | May read | May write | Must never read |
|---|---|---|---|
| `researcher` | upstream projects, specifications, `CORPUS_PROTOCOL.md`, `examples/` (dev) | `examples/`, `sources.json`, `SOURCES.md` | `src/main/`, `reports/`, `FIXLOG.md` |
| `qa-tester` | `src/`, `examples/` (dev only, unless `UNSEAL-HOLDOUT`), `reports/` | `reports/<date>/`, `src/test/` | holdout content, absent `UNSEAL-HOLDOUT` |
| `reporter` | that day's run artefacts, the previous run, `examples/*/meta.json` | `reports/<date>/report.md` | example source, oracles, `src/` |
| `triage-lead` | `report.md`, `findings.md`, `results.json`, `meta.json`, `FIXLOG.md` | `reports/<date>/triage.md` | `src/main/`, example Java, oracles |
| `implementer` | `src/`, the current `triage.md`, `CORPUS_PROTOCOL.md` | `src/main/`, `FIXLOG.md` | `examples/` in any form, `run.log`, `findings.md`, `report.md` |
| `senior-advisor` | everything except holdout example content | nothing (read-only) | holdout Java, holdout oracles |

The `implementer` prohibition is the load-bearing one. It works from the abstract node
shape written into `triage.md` and from nothing else. If it could read the failing
example, nothing in this protocol would prevent a special case.

---

## 2. Repository layout

```
examples/<id>/meta.json
examples/<id>/PROVENANCE.md
examples/<id>/oracle/states.txt
examples/<id>/oracle/transitions.tsv
examples/<id>/oracle/ORACLE.md
examples/<id>/java/*.java

reports/YYYY-MM-DD/results.json      # qa-tester — the reporter's only numeric input
reports/YYYY-MM-DD/run.log           # qa-tester
reports/YYYY-MM-DD/findings.md       # qa-tester
reports/YYYY-MM-DD/summary.txt       # qa-tester
reports/YYYY-MM-DD/report.md         # reporter
reports/YYYY-MM-DD/triage.md         # triage-lead
reports/YYYY-MM-DD/advisory.md       # senior-advisor (written by the main session)

src/test/java/**/<ExampleId>Test.java

sources.json                         # generated from meta.json — never hand-edited
SOURCES.md                           # generated from meta.json — never hand-edited
FIXLOG.md                            # one line per analyser change
```

**Pre-protocol fixtures.** The directories already under `examples/` predate this
protocol: they carry `.java` files at the top level and have no `meta.json`, no
`PROVENANCE.md` and no `oracle/`. They are development fixtures of the tool, not corpus
evidence, and they do **not** count toward any figure reported under this protocol. They
are not to be retro-fitted with oracles authored from tool output — that would
manufacture exactly the leakage this document exists to prevent. A pre-protocol fixture
enters the corpus only by being re-harvested from an upstream origin under §4, oracle
first.

Every example directory keeps a **distinct Java package**. Two top-level types sharing
one package make Spoon refuse to build the model at all, and a shared output directory
lets one machine's `.dot` silently overwrite another's.

---

## 3. `meta.json`

One object per example. All fields are required. A missing field means the example is
malformed, and `qa-tester` records that as a result rather than skipping the example.

```json
{
  "id": "rfc9293-tcp-netty-connectionstate",
  "source_language": "Kotlin",
  "source_repo_url": "https://github.com/org/project",
  "source_commit": "0f4b1a9c7e2d6b83f5a1c0d4e9b7a2f6c8d13e57",
  "source_file_path": "src/main/kotlin/com/org/net/ConnectionState.kt",
  "source_lines": "41-118",
  "source_permalink": "https://github.com/org/project/blob/0f4b1a9c.../ConnectionState.kt#L41-L118",
  "source_license": "Apache-2.0",
  "source_license_url": "https://github.com/org/project/blob/0f4b1a9c.../LICENSE",
  "link_checked_at": "2026-08-23T11:04:00Z",
  "link_status": "ok",
  "archive_url": "https://web.archive.org/web/20260823110400/https://github.com/...",
  "synthesis_basis": null,
  "harvested_at": "2026-08-23T10:52:00Z",
  "harvested_in_response_to": null,
  "expected_verdict": "fsm",
  "fp_family": null,
  "idiom": "CENTRALIZED_DISPATCH",
  "commit_shape": "returned",
  "oracle_provenance": "external-spec",
  "oracle_authored_before_conversion": true,
  "structural_signature": "11|CENTRALIZED_DISPATCH|returned|1x2,3x4,7x5",
  "split": "dev",
  "added_at_tool_commit": "2d397ca"
}
```

### Field rules

- `source_commit` — a full 40-character SHA. A branch name or a tag is not a pin.
- `source_permalink` — commit-pinned, with file path and line range.
- `link_checked_at` / `link_status` — `link_status` is one of `ok`, `moved`, `dead`,
  `unchecked`. A dead link does **not** invalidate an example: `source_commit` and
  `archive_url` still pin the origin. It is reported, not acted on.
- `synthesis_basis` — **required and non-null whenever `source_repo_url` is null.** It
  names what the example was synthesised from: an RFC section, a published state
  diagram, a standard's figure. A null `source_repo_url` together with a null
  `synthesis_basis` is an example with no provenance at all, and that is **blocking**.
- `expected_verdict` — `fsm` or `not_fsm`.
- `fp_family` — one of `behavior-only`, `external-factory`, `inspect-unwrap-throw`,
  `exhaustive-fold`, `recursive-tree-builder`, `tag-dispatched-deserializer`, or `null`.
  Non-null **if and only if** `expected_verdict == not_fsm`.
- `idiom` — one of `CENTRALIZED_DISPATCH`, `POLYMORPHIC`, `FIELD_MUTATION`,
  `INSTANCEOF_DISPATCH`, `EFFECT_ADVANCE`, `MIXED`, `NOT_APPLICABLE`. `NOT_APPLICABLE`
  only for `not_fsm`.
- `commit_shape` — one of `returned`, `field-written`, `argument-passed`, `none`:
  **how the successor state is committed.** This is the real discriminator between
  designs, and stratified recall is keyed on it as much as on `idiom`. `none` only for
  `not_fsm`.
- `oracle_provenance` — one of `external-spec`, `upstream-diagram`, `upstream-docs`,
  `derived-from-source-code`, in descending order of independence.
  `derived-from-source-code` is the weak tier and its proportion is audited.
- `oracle_authored_before_conversion` — boolean. It must be `true` unless
  `oracle_provenance == derived-from-source-code`. Recording it `false` for a
  spec-sourced oracle means the procedure was not followed.
- `harvested_in_response_to` — the finding-id of the corpus request this example was
  harvested to satisfy, or `null` for an example harvested independently. **Never
  absent.** A fixture with a non-null value was found *because a fix needed it*: it is
  admissible as a generality fixture under §9, it is weaker evidence than a
  pre-existing one, and the audit reports the two separately.
- `structural_signature` — see §7.
- `split` — computed, never chosen. See §5.
- `added_at_tool_commit` — SealFSM `HEAD` at the moment the example entered the corpus.
  This is the field that makes a *pre-existing pass* distinguishable from a *fitted
  pass*, and without it the central overfitting audit cannot be performed at all.

---

## 4. Oracles

The oracle is ground truth. It is fixed **before** the Java is written and **frozen**
thereafter. No agent revises an oracle to match tool output. A disagreement between
oracle and tool is recorded by `qa-tester` as a finding of soundness class
`oracle-dispute` and resolved by a human against the original source.

### `oracle/states.txt`

One state per line. Blank lines and `#` comments are ignored. Names use the canonical
form of §6.

```
# RFC 9293 §3.3.2
Closed
Listen
SynSent
```

### `oracle/transitions.tsv`

Tab-separated, one transition per line, header row required. Blank lines and `#`
comments are ignored.

```
source	event	target	note
Closed	PassiveOpen	Listen
Listen	SegmentArrival	SynReceived	RFC 9293 fig. 5
SynSent	Timeout	Closed
Established	-	CloseWait	eventless in upstream source
```

- `event` is the literal `-` when the transition carries no event symbol.
- `note` is free text, optional, ignored by comparison.
- Duplicate rows are meaningful: transitions are compared as a **multiset**.

### `oracle/ORACLE.md`

Prose. It must state:

- what the oracle was derived from, cited precisely enough to check;
- whether it was authored before or after the Java conversion;
- every place the specification and the upstream implementation disagree. **Where they
  disagree the specification is the oracle**, the implementation's behaviour is recorded
  as a deviation, and the two are never silently reconciled;
- anything the oracle deliberately omits, and why.

An `ORACLE.md` that reads as a description of SealFSM's output — its vocabulary, its
granularity, its omissions — is evidence that the oracle was written backwards from the
tool. `senior-advisor` flags this.

---

## 5. Split policy

```
split(id) = first hex digit of sha256(id) in {0,1,2,3}  ->  holdout
            otherwise                                    ->  dev
```

Computed from the id alone. **Immutable. Never human-chosen.** An example is not renamed
to move it between splits; renaming re-rolls the split and must be disclosed.

Reference implementation:

```bash
python -c "import hashlib,sys; h=hashlib.sha256(sys.argv[1].encode()).hexdigest(); print('holdout' if h[0] in '0123' else 'dev')" "$ID"
```

### Sealing

**Holdout content is sealed.** No agent and no human reads holdout Java, holdout
oracles, or holdout per-example results until a declared freeze. Holdout `meta.json` and
holdout aggregate counts may be read — knowing that the holdout holds nine examples,
three of them `not_fsm`, leaks nothing about how to pass them.

`qa-tester` runs `split == dev` by default. It runs the holdout **only** when the
invoking instruction contains the literal token `UNSEAL-HOLDOUT`.

- The holdout is unsealed **once**. Any further unsealing is permitted but **must be
  disclosed in the thesis**, with its reason and its date.
- **Holdout failures are never fixed.** They are the result. A holdout failure that
  triggers a code change converts the holdout into a second dev set and destroys the
  only unbiased estimate the project has.

---

## 6. Canonicalisation

Applied to both oracle and tool output **before** any comparison. Raw bytes are never
compared.

**States.** A state is named by the shortest dot-separated suffix of its qualified name
that is unique among that machine's states, so uncollided states keep their bare simple
name and only genuinely colliding ones lengthen (`namecollision.Idle` against
`Legacy.Idle`; `Phase.IDLE` against `Mode.IDLE`). `$` is normalised to `.` before
splitting. Comparison is case-sensitive; leading and trailing whitespace is stripped.

**Events.** Compared as written, after whitespace normalisation. A composed symbol keeps
its `Prefix.CONSTANT` spelling. The absent event is the literal `-` on both sides.

**Serialiser output.** Before comparing DOT or SCXML: strip layout and presentation
attributes (`pos`, `width`, `height`, `color`, `style`, `peripheries`, `fontname`,
`rankdir`), normalise quoting, collapse whitespace runs, sort nodes, sort edges.

---

## 7. Deduplication

`structural_signature` = `<state count>|<idiom>|<commit_shape>|<sorted transition arity profile>`

The arity profile is the sorted multiset of out-degrees written as `<count>x<degree>`:
`1x2,3x4,7x5` means one state of out-degree 2, three of out-degree 4, seven of
out-degree 5.

- A signature is capped at **three** instances in the corpus.
- A second or third instance is kept only when it comes from a **different origin
  project** and a **different source language**. Three copies of one idiom from one
  repository measure that repository.

---

## 8. Negative quota

At least **one in four** examples carries `expected_verdict: not_fsm`: a real sealed
hierarchy, from real code, whose alternatives are *not* the states of an ongoing
process, with `fp_family` set.

Without a negative class precision is unmeasurable — a tool that reports an FSM for
every sealed hierarchy scores perfectly on a corpus containing nothing but FSMs. All six
`fp_family` values should have at least one witness. An empty family is reported as a
coverage gap, because a limitation with no witness is *asserted*, not *measured*.

---

## 9. Fix discipline

Binding on the human, implemented by `implementer`, audited by `senior-advisor`.

1. **No change may name or branch on an identifier that occurs in only one example.**
   Not a type name, not a method name, not a field name, not a package.
2. **Fixes are justified at the level of a Spoon node shape or a language idiom**, never
   at the level of an example. "A `CtSwitchExpression` whose result is assigned to a
   field of the hierarchy type" is a justification; "the door example returns the wrong
   thing" is not.
3. **Every fix requires a second, independently sourced fixture of the same node
   shape**, from a **different origin project**, already present in `dev`. Its
   `harvested_in_response_to` value is recorded in the triage entry as
   `fixture provenance: pre-existing | harvested-on-demand`.
   **A holdout example may never serve as a generality fixture.**
4. **Every fix appends exactly one line to `FIXLOG.md`:**

```
<tool-commit> | <F-code|NEW> | <trigger example-id> | <generality fixture id> | <node-shape rationale>
```

A fix with no generality fixture cited is a special case wearing an algorithm's clothes,
and `senior-advisor` reports it as such.

5. **A crash is not a soundness violation.** It admits a defensive fix only — contain the
   failure, warn through `ExtractionResult.warn()`, record the affected transitions as
   unresolved. Any *behavioural* change arising from a crash goes through the
   second-fixture rule. A completed run emitting a plausible but incorrect machine with
   **no diagnostic** is a wrong state set or a fabricated edge, never a crash.

6. **After the freeze commit is declared and the holdout is unsealed, no further fixes
   are made.** Findings from that point are recorded as measured limitations. A fix made
   after unsealing burns the holdout, and the thesis must disclose that the reported
   figures predate the change.

### Triage classes

| Class | Condition | Action |
|---|---|---|
| `FIX-REQUIRED` | soundness violation — fabricated edge, wrong state set, crash | always fixed; contradicts a thesis claim directly |
| `FIX-ELIGIBLE` | a genuine idiom or node-shape gap **and** a second independently sourced dev fixture of that shape already exists | fixed; both example ids cited |
| `DEFERRED` | a real gap with no second fixture yet | abstract corpus request issued; no code change |
| `WONTFIX` | example-specific quirk | recorded as a measured limitation for §5.8 |

An unfixed, documented, measured limitation is legitimate thesis material and bounds
recall honestly. Turning every red test green is not the objective.

---

## 10. Comparison semantics

### States — exact

Set equality after canonicalisation. **No partial credit.** An example either recovers
the state set or it does not. Reporting states as a proportion of a per-example
intersection would convert an exact claim into an approximate one, which is precisely
the conflation the thesis must not make.

### Transitions — approximate

A multiset of `(source, event, target)` triples.

- **TP** — a tool triple matching an oracle triple.
- **FP** — a tool triple with no oracle counterpart.
- **FN** — an oracle triple with no tool counterpart.

An oracle transition that is neither extracted nor covered by any unresolved edge is
recorded as `dropped_without_marker`, separately from the false-negative total. A drop
carrying an unresolved marker bounds recall honestly; a drop carrying none is the tool
presenting an incomplete machine as complete, and is classified as a soundness
violation.

### Unresolved edges — a third bucket, never merged

An unresolved edge is an **abstention**: the tool states that a transition exists here
and that it cannot prove the target.

- An unresolved edge **covering** an oracle transition (correct source, target unknown)
  is an **abstention**. It is *not* a false positive. It is also not a true positive.
- **Fabricating a concrete edge with no oracle counterpart is always a false positive**,
  however plausible it looks.
- Abstentions are excluded from the precision denominator, and every table that does so
  **states the exclusion in its caption**. Silently folding abstentions into either
  bucket is how a recall gap gets laundered into a result.

### `not_fsm` examples

The correct output is **no FSM at all**. Any emitted machine is a false positive,
whatever its contents. States and transitions are not scored for these examples.

### Degraded event matching

If Σ is not extracted for an example, `qa-tester` records `events_extracted: false` and
scores on `(source, target)` only, with `event_matching: "degraded"`. The event
component is never silently dropped, and a degraded score is reported as degraded in
every table it reaches.

---

## 11. `results.json`

`qa-tester` writes it. `reporter` reads it and **nothing else numeric**. A figure absent
from this file cannot appear in the report, and therefore cannot appear in the thesis.

```json
{
  "run": {
    "timestamp_utc": "2026-08-23T12:00:00Z",
    "tool_commit": "2d397ca",
    "git_status_porcelain": "",
    "jdk_version": "21.0.3",
    "scope": "dev",
    "holdout_unsealed": false,
    "corpus": {
      "total": 24,
      "by_split": {"dev": 17, "holdout": 7},
      "by_idiom": {"CENTRALIZED_DISPATCH": 9, "POLYMORPHIC": 6},
      "by_commit_shape": {"returned": 11, "field-written": 5},
      "by_verdict": {"fsm": 18, "not_fsm": 6},
      "by_oracle_provenance": {"external-spec": 7, "derived-from-source-code": 12}
    }
  },
  "examples": [
    {
      "id": "rfc9293-tcp-netty-connectionstate",
      "split": "dev",
      "idiom": "CENTRALIZED_DISPATCH",
      "commit_shape": "returned",
      "expected_verdict": "fsm",
      "fp_family": null,
      "added_at_tool_commit": "2d397ca",
      "link_status": "ok",
      "compiled": true,
      "exit_code": 0,
      "wall_time_ms": 812,
      "crashed": false,
      "timed_out": false,
      "machines_emitted": 1,
      "verdict_correct": true,
      "states": {"expected": 11, "actual": 11, "exact_match": true,
                 "missing": [], "extra": []},
      "transitions": {"oracle_n": 44, "tool_n": 44,
                      "tp": 44, "fp": 0, "fn": 0,
                      "unresolved_total": 0,
                      "unresolved_covering_oracle": 0,
                      "unresolved_spurious": 0},
      "events_extracted": true,
      "event_matching": "exact",
      "findings": []
    }
  ],
  "aggregate": {
    "states": {"exact": 15, "n": 17},
    "transitions": {"tp": 0, "fp": 0, "fn": 0,
                    "precision": null, "recall": null, "f1": null,
                    "abstentions_excluded_from_precision": 0},
    "negative_class": {"n": 6, "correctly_rejected": 5, "false_positives": 1}
  },
  "regression_vs": {
    "previous_run": "reports/2026-08-16",
    "previous_tool_commit": "87d8961",
    "regressed": [], "fixed": [], "unchanged_failing": []
  }
}
```

A crash, a timeout, or empty output is a **recorded result**, never a skipped example. A
skipped example is invisible in the denominator and silently improves every rate.

---

## 12. `findings.md`

One entry per **distinct defect**, grouping every example that shares one root symptom.
Fields:

```
finding-id:      short-kebab-slug
symptom:         one sentence
F-code:          F1-F8, or NEW
examples:        [id, id, ...]
idiom:           ...
commit_shape:    ...
expected:        the oracle fragment
actual:          the emitted fragment
node-shape:      the minimal reproducing Spoon node shape - abstract, no example names
soundness-class: fabricated-edge | dropped-edge | wrong-state-set | crash |
                 abstention | oracle-dispute
```

**`fabricated-edge` and `wrong-state-set` are listed first**, always. They contradict
the thesis claims directly. A dropped edge merely bounds recall; a fabricated edge or a
wrong state set says the tool reports things that are not true.

The `node-shape` field is the **only** channel from a failing example to the
`implementer`. It must be intelligible without the example: name the Spoon node kinds,
the typing relationships and the syntactic context — never the identifiers.

---

## 13. Reporting rules

- **Precision, recall and F1 are two separate objects** — one for states, one for
  transitions — and are never merged into a single headline number. They measure claims
  of different strength, and averaging them destroys the distinction the thesis rests
  on.
- **Every rate is reported with its absolute counts and N.** `0.86` alone is not a
  result.
- Where a cell has **N < 20**, give the count and **omit the percentage**.
- **Recall is stratified per `idiom` and per `commit_shape`.** A pooled figure hides
  which designs the tool actually handles.
- **`dev` and `holdout` appear in separate tables and are never pooled.**
- Abstentions appear on their own line, excluded from precision, with the exclusion
  stated in the caption.
- Missing input is reported as a **gap**. It is never interpolated and never carried
  forward from a previous run.
- The report's register is past tense, impersonal, facts only: no evaluative adjectives,
  no recommendations, no causal hypotheses. A failure is not editorialised into a
  limitation, and a success is not editorialised into a validation.

---

## 14. Invariants — the short list

1. Unresolved transitions are recorded, never dropped, never merged into another bucket.
2. States are compared by set equality. No partial credit.
3. Oracles are frozen before conversion and are never revised to match output.
4. Splits are computed from `sha256(id)` and never chosen.
5. Holdout content is read once, at a declared freeze; holdout failures are never fixed.
6. `implementer` never sees an example.
7. Every fix cites a second, independently sourced fixture from a different origin
   project.
8. Every reported figure exists in `results.json`.
