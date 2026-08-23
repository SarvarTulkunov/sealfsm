# Senior advisory — SealFSM evaluation cycle 2026-08-23

Auditor: `senior-advisor` (read-only). Nothing was modified.
Scope audited: `reports/2026-08-23/{results.json,run.log,findings.md,summary.txt,report.md,triage.md}`, `FIXLOG.md`, `examples/lcp_automation_chatgpt/**` (dev split, permitted), `src/main/` (grep only), `CORPUS_PROTOCOL.md`.
Holdout: empty (`by_split.holdout: 0`). No holdout content existed to read, and none was read.

## 0. What this cycle is, stated once

First run under the protocol. Corpus n = 1. `FIXLOG.md` empty. `src/main/` unchanged. One finding, deferred. No rate, no percentage, and no recall figure was reported anywhere. **There is therefore almost nothing in this cycle for an examiner to attack on the grounds of overclaiming** — the reporting discipline held, and I say that plainly before the criticism, because it is the honest headline. What the cycle does have is a set of governance and coverage defects that will compound silently if they are not closed before the corpus grows.

---

## 1. Corpus representativeness

### Counts, and the empty cells

| axis | occupied | empty |
|---|---|---|
| `source_language` | Java 1 | every other language 0 |
| **origin project** | **none — 0 examples harvested from any repository** | all |
| `idiom` | CENTRALIZED_DISPATCH 1 | POLYMORPHIC, FIELD_MUTATION, INSTANCEOF_DISPATCH, EFFECT_ADVANCE, MIXED, NOT_APPLICABLE — all 0 |
| `commit_shape` | argument-passed 1 | returned, field-written, none — all 0 |
| `expected_verdict` | fsm 1 | not_fsm 0 |
| `fp_family` | none | all six values 0 |
| `oracle_provenance` | external-spec 1 | upstream-diagram, upstream-docs, derived-from-source-code — 0 |
| `structural_signature` | `10\|CENTRALIZED_DISPATCH\|argument-passed\|2x3,3x13,5x15` ×1 | §7 cap of three not approached |
| `split` | dev 1 | holdout 0 |

### **[MATERIAL] The protocol corpus contains zero lines of real-world code, and no report artefact says so**

`meta.json` records `source_repo_url: null`, `source_commit: null`, `source_file_path: null`. The single corpus example is synthesised from RFC 1661 §4.1 and its Java was LLM-generated. `CLAUDE.md`'s stated project goal is that the tool "reliably detect FSMs in **real-world Java projects**." Under §2 of the protocol, the 29 pre-protocol directories that contain the only third-party-shaped code (`ffmpeg`, `websocket-claude`, `gofcontext`, `dhcp-client-*`) "are not corpus evidence." So the real-world-generality claim currently has a corpus witness count of **zero**.

`report.md` §1 lists the corpus composition faithfully but never states this. `report.md` is not permitted evaluative prose, so the omission is not the reporter's fault; the fact needs to land in a document that is permitted to state it (`triage.md` or this advisory), and it now does.

### **[MINOR] `source_language: "Java"` is a category error for a synthesised example**

There is no source language when there is no source. The field describes the language of the upstream artefact being converted; here the upstream artefact is a printed ASCII table in an RFC. Recording `Java` will, once `by_source_language` is aggregated across a larger corpus, silently count a synthesised example as a Java-origin one. Either `null` or a documented convention is needed.

### Smallest stratum behind each claim

`report.md` makes exactly three classes of claim: run metadata, raw counts, and one quoted finding. Every count rests on n = 1, and `report.md` says so in §2, §3 and §4 without hedging. **No claim in this report rests on a stratum smaller than the one it declares.** This audit item resolves favourably, and it resolves favourably only because no recall or precision figure was reported at all. The moment one is, the smallest stratum is 1 and the figure is about RFC 1661.

### **[MATERIAL] The corpus's only origin is already used twice in the repository**

RFC 1661 §4.1 backs both `lcp_automation_chatgpt` (corpus) and `lcp_automation` (pre-protocol). `triage.md`'s FIX-ELIGIBLE section spotted this unprompted and excluded the pre-protocol sibling as a would-be generality fixture on "one specification, one origin" grounds. **That is a sharp, correct and unforced catch, and it is the best single piece of judgement in the cycle.** Record it as a standing constraint: if `lcp_automation` is ever re-harvested under §4, it must not thereafter be cited as the second fixture for any fix triggered by `lcp_automation_chatgpt`, and vice versa.

---

## 2. Oracle independence

### **[FAVOURABLE — and this is the strongest artefact in the cycle] The oracle was not written backwards**

Proportion at the weak tier `derived-from-source-code`: **0 of 1**. `oracle_provenance: external-spec` with `oracle_authored_before_conversion: true` is internally consistent with §3's rule.

I applied the three backwards-writing signals and all three point the other way:

- **Vocabulary.** `ORACLE.md` and `transitions.tsv` speak RFC: `RCR+`, `TO-`, `RXJ-`, and action tokens `tlu/tld/tls/tlf/irc/zrc/scr/sca/scn/str/sta/scj/ser`. SealFSM has no concept of an action at all. An oracle written from tool output could not have produced this vocabulary.
- **Granularity.** The oracle takes RFC 1661's sixteen events as sixteen distinct symbols and explicitly *refuses* the guard-split decomposition — "An implementation that merges each pair into a guarded symbol is encoding the same relation in a different alphabet; that is an event-alphabet divergence... never absorbed by editing this table." This is a deliberate refusal to adopt the decomposition the tool's sibling fixture uses (13 events + 14 guard splits, per `CLAUDE.md`). That is the opposite of matching the tool's granularity.
- **Omissions.** Actions, timers/counters, guards and the initial state are omitted, each with a spec-internal reason. The initial-state omission — "the RFC draws no entry arrow, and every state including `Initial` has incoming edges, so `Initial` cannot be recovered structurally" — is an argument from the specification, not from what SealFSM can do.

Two further corroborations: the oracle carries **67 self-loops** it argues at length must be kept, and it carries **120 rows of which the tool recovered 0**. An oracle written backwards from output does not score its own subject zero.

I independently spot-checked roughly 35 of the 120 cells against RFC 1661 §4.1 (states 0–5 fully, plus selected rows in 6–9: `ReqSent/RCA→irc/7`, `AckRcvd/RXJ+→6`, `AckSent/RCA→irc,tlu/9`, `Opened/RTR→tld,zrc,sta/5`, `Opened/RXR→ser/9`, `Closing/RTA→tlf/2`, `Stopping/Down→1`). All matched the RFC as printed. Out-degrees sum to 120 and match the declared arity profile `2x3,3x13,5x15`. The three footnote readings (`[r]`, `[p]`, `[x]`) are each flagged as readings rather than buried, which §4 requires and which most oracles do not do.

### **[FAVOURABLE] The content check was performed, and it was a real one**

My brief warns that HTTP 200 is not a content check. `run.log` does record two bare curl-200s — which alone would be insufficient. But `PROVENANCE.md` records that all 160 cells of §4.1 were transcribed verbatim, with the raw cell text carried into the `note` column of every row of `transitions.tsv`. I verified this is true of the file. That is the strongest possible content check on a specification source: the document was not merely fetched, it was read cell by cell and the reading is preserved for re-checking. Credit it, and label it as such in the artefacts so the next audit does not have to rediscover it.

### **[MINOR] `ORACLE.md` and `results.json` assert opposite values for the same protocol field**

`ORACLE.md`: "Event matching for this example is therefore `exact`, not `degraded`."
`results.json`: `"event_matching": "degraded"`.

Both are defensible in their own frame — `ORACLE.md` is describing the implementation's alphabet, `results.json` the run — but §10 defines `event_matching` as a property of a *run*, and `ORACLE.md` has used a run-scoped protocol term to describe a source property. A reader collating the two hits a flat contradiction. The `ORACLE.md` sentence sits in the append-only Divergences section, so no freeze was violated; it needs rewording, not reverting.

---

## 3. Overfitting, leakage, and the correction — the central item

### The ratio

- Passing examples: **0**.
- Pre-existing passes: 0. Fitted passes: 0. **Ratio is 0/0 — undefined, and vacuous.**
- `added_at_tool_commit` = `2d397ca5438d…` = the run's `tool_commit`. The example entered and was scored at the same commit, and no fix followed. This is a clean *pre-existing fail*.
- `FIXLOG.md`: zero entries. §9.3's second-fixture rule, §9.4's citation rule, the pre-existing-vs-harvested-on-demand split, and the no-holdout-citation rule are all **vacuously satisfied**. Nothing here is evidence of discipline yet; it is evidence of an empty log.

### **[FAVOURABLE] No identifier leak into `src/main/`**

Grepped `src/main/` for every corpus and pre-protocol type name I could enumerate. Two hits, both in Javadoc prose:

- `src/main/java/io/sealfsm/detect/CarrierTransitionDetector.java:27` — `TcpState` in an illustrative comment.
- `src/main/java/io/sealfsm/model/StateNaming.java:181` — `p.LcpState$Initial` in an explanation of `$` canonicalisation.

Neither branches. No string literal, type check or name comparison in `src/main/` names any example type. §9.1 is met.

### **[MINOR] Standing name-vocabulary risk, pre-dating the protocol**

`src/main/java/io/sealfsm/extract/TransitionExtractor.java:98,102`:

```java
Set.of("next", "transition", "step", "advance", "nextstate", "transitionto", "tick");
Set.of("setstate", "changestate", "transitionto", "goto", "setcurrent", "become");
```

and `StateMachineClassifier.java:46` `Set.of("Fsm", "FSM", "StateMachine")`. These are English-idiom vocabularies, not single-example identifiers, and `CLAUDE.md` documents them as type-gated prefilters. Not a §9.1 violation. But they are the mechanism by which a future fix could be example-shaped while passing the identifier grep, so the audit condition should be stated as "no *new* member is added to these sets without a second-fixture citation", not merely "no example identifier appears".

### **[BLOCKING — governance] `FIXLOG.md`'s header was allowed to amend `CORPUS_PROTOCOL.md`**

`triage.md` §0.2 resolves a genuine tension in §9 — FIX-REQUIRED is "always fixed" while §9.3 makes every fix conditional on a second fixture — by quoting `FIXLOG.md`:

> "**A `FIX-REQUIRED` soundness fix is the sole exception** and records `soundness` in the generality-fixture column."

That sentence appears in `FIXLOG.md` (lines 9–11). **It does not appear anywhere in `CORPUS_PROTOCOL.md`.** §9.3 as written admits no exception, and §0 of the protocol states: "Where an agent definition and this protocol disagree, this protocol wins." `FIXLOG.md` is a log, not the protocol.

`triage.md` then records the derived rule "so that the next run does not have to relitigate it" — i.e. it is establishing precedent. The tension it resolves is real and needs resolving. But it must be resolved in `CORPUS_PROTOCOL.md`, by the human, or the corpus acquires a second, informal source of binding rules that the audit trail will not distinguish from the first. This is the exact failure mode the protocol's §0 exists to prevent, occurring on run one.

### The correction: **not leakage; a real and specific loss of independence, correctly disclosed but incompletely characterised**

The facts, verified: the frozen oracle was authored first from the RFC; the ChatGPT-generated Java disagreed with it on 21 of 120 cells plus 2 action lists; the **implementation** was edited to match the **oracle**; the oracle was not touched; D1/D2/D3 in `ORACLE.md` plus commit `805652b` reconstruct the pre-correction artefact exactly.

**[FAVOURABLE] This is not leakage.** Two independent reasons:

1. **Direction.** §14.3 forbids revising an *oracle* to match output. The edit ran the other way. `ORACLE.md`'s frozen sections are intact and the divergence tables are append-only.
2. **No tool contact.** `PROVENANCE.md` records "SealFSM was not run, and `src/main/`, `reports/` and `FIXLOG.md` were not read" during intake. The run result corroborates this behaviourally and decisively: the example scored **0 of 10 states and 0 of 120 transitions**. An example shaped toward what SealFSM can parse does not score zero. The corrected artefact was shaped toward the *RFC*, not toward the tool.

Both `PROVENANCE.md` and `ORACLE.md` state the independence loss explicitly, including the sharpest possible framing of it — "those 21 were exactly the cells on which independent agreement would have been tested." That sentence is better than most published threat-to-validity sections. Credit it.

### **[MATERIAL] The independence loss is characterised one step short of the real risk**

Both documents frame the cost as bearing on a *cross-check against `examples/lcp_automation`*. There is a second, closer risk neither names:

**The oracle and the implementation now share a single reading of RFC 1661 pages 12–13, by a single agent.** The oracle is that agent's transcription; the corrected implementation is that agent's correction *toward its own transcription*. If a cell was mis-transcribed, both artefacts now carry the same error, and **no comparison inside this corpus can detect it** — because the comparison that would have (the 21 disagreeing cells, produced by a genuinely independent second reader) was consumed by the correction itself.

Before the correction, the corpus held a real independent check: 99 cells on which two independent readings of the RFC agreed. That is meaningful evidence about the oracle's fidelity and it should be *claimed*, since the reconstructibility from `805652b`+D1/D2/D3 preserves it. After the correction, the residual independent evidence for the other 21 cells is zero, and my own spot-check of ~35 cells (which found no error) is corroborating but not exhaustive.

This must reach §5.8 of the thesis in the sharper form, not the softer one.

### **[MATERIAL] `provenance_class: private-attested` is an out-of-schema field carrying a judgement I assess as wrong-leaning**

Two separate problems:

1. **`provenance_class` is not a `CORPUS_PROTOCOL.md` field.** §3 enumerates the required fields; this is not among them. `PROVENANCE.md` reasons about "the vocabulary offers only two values, `private-attested` and `llm-generated`" — a vocabulary the binding document does not define. The intake agent invented a field and then debated a choice within an undefined value space. It also added `synthesis_basis_url` and `synthesis_basis_archive_url` (both benign and useful, but likewise undefined).
2. **On the substance, `private-attested` is the wrong of the two.** No person authored this Java: an LLM generated it and an agent corrected it. A person authorised the correction. "Private-attested" ordinarily means a human attests to code they wrote or own; using it here makes the corpus's single most important weakness — LLM-generated example content, subsequently conformed to its own oracle — legible **only in prose**. Structured fields are what aggregates get built from; the moment any run computes a `by_provenance_class` breakdown, this example is counted alongside human-supplied code.

Mitigations, and they are real: `synthesis_basis` in `meta.json` names ChatGPT, names the 21+2 corrections, and records that the oracle predates them; the intake agent flagged the judgement as debatable rather than burying it; and nothing currently aggregates on the field. So this is material, not blocking — but it has a cheap and checkable remedy (item 4 below).

### **[MATERIAL] No *protocol* field records that the implementation was conformed to its oracle, so the fact cannot propagate**

This is the operational consequence of the point above and it is the more serious half. Per §1, `reporter` may not read example source or oracles; per §11 it reads `results.json` "and nothing else numeric." `results.json` contains no field recording the conformance. Therefore:

- `report.md` does not mention it — correctly, because the reporter could not know.
- `summary.txt`, `findings.md` and `triage.md` do not mention it.
- **The single most important qualification on the corpus's only example exists solely in two files that the reporting chain is structurally forbidden to read.**

Today the cost is zero because no claim was made. The moment this example contributes to a recall figure, that figure travels into a thesis table with the qualification stripped off by the protocol's own role separation. The fix is structural: the qualification must live in `meta.json` and be copied into `results.json` per example.

---

## 4. Triage discipline

### Distribution over time

One run. `classified: FIX-REQUIRED 0 / FIX-ELIGIBLE 0 / DEFERRED 1 / WONTFIX 0`; `cumulative WONTFIX: 0`.

**[FAVOURABLE]** A zero cumulative WONTFIX over one finding is not evidence of an ungated loop, and `triage.md`'s header says exactly that, unprompted: "the cumulative figure has no history behind it and is not yet evidence of gating in either direction." That is the correct statement of what the number can and cannot support, and it pre-empts the audit item. Likewise the WONTFIX section explains at length why nothing was filed there and why filing it would have been "the mirror image of the fitting error" — an unmeasured gap converted into an asserted §5.8 limitation on one witness. This is calibrated reasoning in both directions.

### Crash classification

**[FAVOURABLE]** No entry is classified `crash`, and none should be. Exit code 1 is `Main`'s deliberate "zero machines found" path (`run.log` line 37, `crashed: false`, stderr carries only SLF4J warnings). Filing this as `crash` would have been the misfiling my brief warns about — it would have downgraded a detection failure into a containment problem and, worse, would have taken the one route §9.5 leaves ungated. It was not filed that way. No `scope: behavioural` crash entry exists; `FIXLOG.md` is empty, so there is nothing to cross-check.

### The reclassification — audited directly, as instructed

`qa-tester` filed `soundness-class: wrong-state-set`. `triage-lead` triaged it as an idiom gap and routed it to DEFERRED.

**Where the reasoning holds.** Three of the four grounds are sound and I endorse them:

- The tool asserted nothing. `machines_emitted: 0`, `states.actual: 0`, `tool_n: 0`. There is no emitted machine whose state set could be wrong.
- The distinction between **classifier recall** and **state-enumeration exactness** is correct and important. The §0.1 exactness claim is conditional on a hierarchy having been classified as a machine; it says nothing about which hierarchies get classified. Collapsing the two would misattribute a recall gap to the one claim the thesis makes as *exact*, which is the reverse of the usual error but is still an error.
- The bypass analysis is right: if every non-detection were FIX-REQUIRED, every non-detection would take the unconditional route and §9.3 would gate nothing.

**Where it does not hold, and this is the load-bearing objection.**

### **[BLOCKING] `triage-lead` read past the explicit text of §10, unilaterally**

§10 states, without qualification:

> "An oracle transition that is neither extracted nor covered by any unresolved edge is recorded as `dropped_without_marker`... a drop carrying none is the tool presenting an incomplete machine as complete, **and is classified as a soundness violation**."

`results.json` records `dropped_without_marker: 120`. `triage.md` §0.1 concedes "The letter of the field is met and `results.json` records it correctly; the rationale is not."

Whether or not the rationale argument is right — and I think it substantially is — **`triage-lead` is not authorised to decide that.** §0 makes the protocol binding over agent judgement; §14 lists the invariants as invariants. A role whose permitted output is a classification has, in this document, disapplied an explicit classification rule of the protocol on its own reasoning, and recorded the reasoning as precedent for future runs. That is a governance breach independent of the merits, and it is the second instance in one cycle (the first is §3's FIXLOG amendment) of the protocol being effectively edited by a document that is not the protocol.

The correct disposition: the human amends §10 and §12, and the amendment then binds. Until it does, `dropped_without_marker > 0` is a soundness violation by the protocol's text.

### **[MATERIAL] §12's soundness-class vocabulary has no value for whole-hierarchy non-detection, and that is the actual root cause of the dispute**

§12 offers: `fabricated-edge | dropped-edge | wrong-state-set | crash | abstention | oracle-dispute`. **None of these is "the tool recognised no machine here."** `qa-tester` picked the least-bad available label; `triage-lead` correctly observed it does not fit; both were right within a vocabulary that does not cover the case. §9's triage table inherits the hole — there is no row for a detection miss.

This will recur on **every** future non-detection, and each recurrence will be re-argued in prose, per run, by whichever triage agent is on duty. It must be fixed in the protocol once.

### **[MATERIAL] The incentive structure the argument creates is not safe as stated, and needs an explicit counterweight**

`triage.md` is correct that "a tool emitting nothing and saying so asserts nothing untrue." But that argument is fully general and permanent: it exempts *every* future non-detection from the unconditional route, forever. Composed with §9.3 (no fix without a second dev fixture), the resulting standing rule is:

> The analyser may never be extended to a class of machines it fails to detect until two independently sourced corpus witnesses exist.

As anti-overfitting discipline that is defensible and possibly correct. The hazard is the mirror image and is not named anywhere in the cycle: **it rewards under-detection.** A tool that abstains on everything accrues zero soundness findings, zero FIX-REQUIRED entries, an empty `FIXLOG.md`, and can truthfully report "no fabricated edges and no soundness violations observed." `report.md` §6 already contains the sentence "No fabricated edges (FP) were recorded this run: `results.json` reports FP = 0 across the corpus" — accurate, but on a run with zero output it carries no precision information whatsoever, and read out of context it reads favourably.

The counterweight must be structural, not exhortative: non-detection has to be reported with the same prominence as a fabricated edge, and any statement of the form "no soundness violations" must be forbidden from appearing without the detection rate adjoined to it.

### **[MATERIAL] The diagnostic the whole reclassification rests on misdescribes the cause**

The argument's discriminator is "something was said." What was said, in full:

```
[INFO] lcpchatgpt.LcpState: skipped - no transition producer found (may be event/? type or unresolved dispatch)
```

Both offered causes are false. `LcpState` is not an event type — it is the sealed root, with ten permitted record subtypes. The dispatch is not "unresolved" — `transitionFor` is a complete, exhaustive `switch` over `LcpState`; it is *unrecognised*, which is a different thing. The diagnostic also fires at INFO, at hierarchy granularity, in a stdout block a user may not read, on a run that wrote no files.

The abstention *was* announced, so the reclassification survives on its own terms. But a diagnostic doing this much load-bearing work — it is the sole thing standing between "honest abstention" and "silent incomplete answer" — should not state two causes that are both wrong. If the discriminator between a soundness violation and an idiom gap is the diagnostic, the diagnostic's accuracy becomes a soundness-adjacent property.

### Freeze

`freeze status: pre-freeze`, correctly declared. `FIXLOG.md` has no entries, so §9.6 is vacuously satisfied. No post-freeze checks apply.

---

## 5. Claims versus data

**[FAVOURABLE] I traced every figure in `report.md` back to `results.json` and found no exceedance.** Checked: timestamp, both commit forms, the porcelain summary, JDK, Maven, scope, `holdout_unsealed`, corpus totals and all five breakdowns, the 29-count and its full id list, `0/1` states exact, `0/0/120`, `dropped_without_marker: 120`, `unresolved_total: 0`, negative class `0/0/0`, `wall_time_ms: 1922`, `exit_code: 1`, `crashed: false`, `machines_emitted: 0`, `events_extracted: false`, the missing-state list, `link_status`. All present. No number in `report.md` is absent from `results.json`.

- **Exactness.** States are compared by set equality (`exact_match: false`, `missing` lists all 10, `extra: []`). No intersection, no F1 over states, no partial credit anywhere. §10 and §14.2 met.
- **Unresolved edges laundering failures.** `unresolved_total: 0`. Abstentions are on their own line in `report.md` §2 with the exclusion stated, and `results.json` adds the correct note that the exclusion is "vacuous, not omitted." **Critically, `dropped_without_marker` and `fn` are kept as separate fields with an explicit note that their numeric coincidence at 120 is an artefact of `unresolved_total: 0`.** That is precisely the distinction §10 exists to preserve, and it was preserved under the one condition where collapsing them would have been invisible. Credit.
- **Dropped edges carrying no unresolved marker.** 120 of 120. This is the finding of the cycle and it is recorded at full size in four artefacts. Not laundered.
- **Pooling.** No dev/holdout pooling is possible (holdout is empty), and the report keeps them in separate rows regardless.
- **Register.** Past tense, impersonal, no adjectives, no recommendations, no causal hypotheses beyond what `findings.md` recorded and attributed. §13 met.

### **[MINOR] The reporter performed two filesystem checks outside its numeric input set**

`report.md` §1: "A filesystem check at report time confirmed exactly one `meta.json` exists under `examples/`." §5: "A directory listing of `reports/` at report time found only `reports/2026-08-23/`." Neither produced a figure absent from `results.json`; both corroborated one that was present. This is verification, not invention, and it is in the good direction. It is nonetheless an input the role's permitted-reads row does not list, and if the practice becomes routine the boundary erodes. Note it, permit it explicitly, or drop it.

### **[MATERIAL] The `node-shape` field — the only channel to `implementer` — describes a shape the fixture does not have**

This is the most consequential technical defect in the cycle, because §12 makes `node-shape` the sole permitted channel from a failing example to the party that writes code, and `implementer` has no way to detect that the description is wrong.

`findings.md` states the arms of the inner switch "construct C via `new C(new ConcreteH(), otherArgs)` — an H-typed `CtConstructorCall` passed as a **direct constructor argument** to C." `triage.md`'s DEFERRED node shape repeats it: "its arms produce C by a `CtConstructorCall` of C taking an H-typed expression... as a direct constructor argument."

The fixture does not do that. `examples/lcp_automation_chatgpt/java/LcpAutomaton.java`:

```java
case UP -> transition(new Closed(), List.of());          // arm value is a CtInvocation, not a CtConstructorCall
...
private LcpTransition transition(LcpState state, List<LcpAction> actions) {
    return new LcpTransition(state, actions);            // the CtConstructorCall lives here;
}                                                        // its H argument is a PARAMETER, not a permitted subtype
```

The arm value is a `CtInvocation` of a private helper returning C. The `CtConstructorCall` of C is one method deeper, and at that point the H-typed argument is a `CtParameter`, not a construction of a permitted subtype. There is **one more inter-procedural hop** than either document records. An `implementer` who implemented exactly the described shape — "H-typed constructor call as a direct argument to a constructor of C" — would not recover a single edge of this example.

Worse, three cells go deeper still:

```java
case TO_MINUS -> configureTimeoutExpired();   // in fromReqSent, fromAckRcvd, fromAckSent
...
private LcpTransition configureTimeoutExpired() {
    return transition(new Stopped(), passiveOnConfigureTimeout ? List.of() : List.of(LcpAction.TLF));
}
```

Here the successor is **two calls** from the arm and the arm carries no argument at all. Oracle rows `ReqSent/TO- → Stopped`, `AckRcvd/TO- → Stopped`, `AckSent/TO- → Stopped` sit behind this. Neither `findings.md` nor `triage.md` records this sub-shape, and it is at or past the documented k = 2 inter-procedural budget. A fix built to the recorded shape will leave a 3-edge residual that will surface next cycle as a *new* finding rather than a known remainder.

And a third omission, this one a fabrication hazard: 40 arms read `illegal(new Closed(), event)`, where the H-typed argument is the **current** state passed for an error message, not a successor. A rule of the form "an H-typed argument to a carrier-returning call is a successor" would fabricate exactly 40 self-loops on precisely the 40 cells RFC 1661 prints as `-`. The existing F9 rule (`neverReturnsNormally`) should suppress them, and `PROVENANCE.md` records that `illegal(...)` was deliberately preserved for that reason — but the node-shape channel, which is all `implementer` sees, does not mention the hazard at all.

### **[MATERIAL] The corpus request excludes the shape it is asking for**

`triage.md`'s DEFERRED corpus request, item 4:

> "At no point is a value of the root type returned bare, assigned to a field of the root type, or declared into a local of the root type."

`LcpAutomaton` does exactly that:

```java
private LcpState state = new Initial();
...
public LcpTransition on(LcpEvent event) {
    LcpTransition transition = transitionFor(state, event);
    state = transition.state();                 // assigned to a field of the root type
    return transition;
}
```

A `researcher` applying that exclusion literally would reject a candidate shaped like the fixture the request exists to find a sibling for. `PROVENANCE.md` correctly reasons that this field write "reads the carrier back out and does not itself spell a successor" — but that nuance is absent from the request, and `researcher` does not read `PROVENANCE.md` of an existing example as part of harvesting. This will cost a harvest cycle, and a harvest cycle is currently the project's scarcest resource.

---

## 6. Edge-case and negative coverage

### **[BLOCKING for the evaluation chapter] The negative class is empty; precision is unmeasurable and no precision figure can be reported**

`negative_class: {n: 0, correctly_rejected: 0, false_positives: 0}`. §8's one-in-four quota is unmet at the corpus level. `results.json`, `summary.txt`, `report.md` and `triage.md` all record it as a coverage gap and none computes a rate. **That handling is exactly right.**

What the precision figure can support: **nothing.** `fp = 0` this run is an artefact of zero output, not a precision measurement. State that explicitly wherever `fp = 0` appears, and never carry it forward.

All six `fp_family` values are empty: `behavior-only`, `external-factory`, `inspect-unwrap-throw`, `exhaustive-fold`, `recursive-tree-builder`, `tag-dispatched-deserializer`. Every one of the tool's false-positive-avoidance behaviours is therefore **asserted, not measured** under the protocol — including `exhaustive-fold` and `recursive-tree-builder`, which `CLAUDE.md` describes as the precision guards the whole classifier design turns on (`foreignfold`, `treebuilder`, `shape`, `plumbing-mutation` are all pre-protocol and so not corpus evidence).

### **[BLOCKING for the evaluation chapter] Every F-code limitation is currently unwitnessed under the protocol**

My brief asks which of F1–F8 has no corpus witness. Under the protocol: **all of them, plus F9–F12.** The single corpus finding is `F-code: NEW`. Every F1–F12 claim documented at length in `CLAUDE.md` rests entirely on the 29 pre-protocol fixtures, which §2 declares "are not corpus evidence" and "do not count toward any figure reported under this protocol."

The consequence is stark and should be stated to the author without softening: **the entire empirical base currently described in `CLAUDE.md` sits outside the protocol.** F9's never-returns rule, F10's expression-statement rule, F11's shadow-body rule, F12's arm-ordering rule, the name-collision result (`11/11` vs `12/12`), the two-HTTP/2-spellings cross-check — none of it is measured evidence under the regime the thesis will describe. Each is a plausible, well-argued engineering claim; none has a protocol witness. The thesis must be explicit about which of its stated limitations are *measured* and which are *asserted*, and today the answer is that all of them are asserted.

Secondary: §12 specifies "F-code: F1-F8" while the project uses F1–F12. Minor drift between protocol and implementation; fix one or the other.

---

## 7. Provenance completeness

- **`source_permalink: null`.** Permitted, and correctly so: `source_repo_url` is null but `synthesis_basis` is non-null ("RFC 1661 section 4.1... pages 12-13"). §3's blocking condition — both null — is **not** met. **Not blocking.**
- **`source_commit: null`.** Same basis; there is no repository to pin. `archive_url` pins the specification text and returned HTTP 200 on re-fetch.
- **Content check: performed and recorded** — see §2 above. The 160-cell transcription with raw cell text in the `note` column is a stronger check than the protocol requires. Favourable.
- **`link_status: ok`**, re-verified at run time on both the live and archived URLs.

### **[MATERIAL] No licence determination exists for the specification the corpus derives from**

`source_license: null`, `source_license_url: null`. `PROVENANCE.md` says "none to clear; the code was commissioned by the repository author." That is correct **for the Java**. It does not address the oracle: `transitions.tsv` redistributes RFC 1661 §4.1's cell text verbatim in 120 `note` fields, and `states.txt` redistributes its state names. RFC 1661 (July 1994) predates the IETF Trust Legal Provisions and carries the ISOC copyright notice with its derivative-works restriction alongside the unlimited-distribution grant.

I judge the practical risk negligible — transcribing a normative table into a research data file is standard and universally tolerated. The defect is that **no determination was made or recorded at all**, and a thesis appendix will need the line. Cheap to close.

**No blocking provenance defect was found.** The example is traceable to a specific origin, at a specific section, with an archived copy and a preserved cell-level reading.

---

## 8. Statistical honesty

**[FAVOURABLE] This is the cycle's second-strongest area.** Checked against every §13 rule and the protocol's N < 20 line:

- **No percentage appears anywhere** in `results.json`, `report.md` or `summary.txt`. At n = 1 every percentage would have been a 0% or 100% carrying the authority of a rate. None was written.
- **Precision, recall and F1 are `null`** and no substitute is computed. `results.json` says so; `report.md` repeats it without softening.
- **Stratification was refused, with a reason.** `results.json`'s aggregate note: "a single-example corpus produces a number that reads as a stratified result but is not one." `report.md` §3 declines to build the table §13 nominally requires and explains why. **Refusing to produce a required table because the table would mislead, and saying so, is the correct call** — it is the harder of the two options and it was taken.
- **Nothing carried forward.** `regression_vs.previous_run: null`, with `regressed`/`fixed`/`unchanged_failing` recorded as "empty by definition, not by result." That phrase is exactly the distinction that gets lost in practice.
- **Denominator check.** Corpus total 1; scored set 1; exclusions 29, each named by id in `results.json`, `run.log`, `summary.txt` and `report.md`. I cross-checked the 29 ids against the `examples/` directory listing and the glob for `meta.json`: **exactly one `meta.json` exists, 1 + 29 = 30 directories, no example appears in neither the scored set nor the explicit exclusion list.** No silent exclusion.
- **The failing test was left failing.** `run.log` lines 74–83; `LcpAutomationChatgptTest.statesAndTransitionsMatchTheFrozenOracle` fails, "not disabled, not weakened, per section 9." This is the single most common place a corpus run gets quietly laundered, and it was not.

### **[MINOR] The run executed against a dirty tree, and `tool_commit` therefore does not identify the tree that ran**

`git_status_porcelain` is non-empty. I read the full porcelain block: it contains only `examples/lcp_automation_chatgpt/*` renames and additions, the `LcpDemo.java` deletion, `.claude/`, `CORPUS_PROTOCOL.md` and `FIXLOG.md`. **No `src/` entry appears**, so the analyser tree was in fact exactly `2d397ca`. The disclosure is verbatim and the note explains what was and was not captured. Handled about as well as running dirty can be handled. Future runs should either be clean or record a digest of `src/main/` so the equivalence does not have to be re-derived by hand.

### **[MINOR] The split assignment is asserted, not independently verified**

`PROVENANCE.md` states `sha256("lcp_automation_chatgpt") = a7c3460842f92be2637e015e27ee97996e0ecbbedaf4a5ed8cdacbd58d979c7d`, first digit `a`, therefore `dev`. I cannot execute code and so cannot confirm it. §5 makes the split "immutable, never human-chosen"; the corpus's only example landing in the split that permits it to be read is the convenient outcome, and convenient outcomes on unverifiable computations are exactly what an examiner recomputes. The cost of checking is one command.

---

## 9. Threats to validity the evidence forces, and that no artefact currently states

Phrased for direct insertion into §5.8. Each is specific enough to be checked.

1. **The protocol corpus contains no real-world code.** At the 2026-08-23 cycle, n = 1, `source_repo_url: null`, `source_commit: null`. Every claim about detection in real-world Java projects rests on zero corpus witnesses. The 29 pre-protocol fixtures are excluded from corpus evidence by §2 of the corpus protocol.

2. **The corpus's only example was corrected to match its own oracle, and oracle and implementation now share one reading of the specification.** Twenty-one transition cells and two action lists in `lcp_automation_chatgpt` were edited to conform to a frozen RFC 1661 oracle authored by the same agent. A transcription error in that reading is now present in both artefacts and is undetectable by any comparison internal to the corpus. The 21 cells on which two independent readings disagreed — the only cells where independent agreement was testable — were consumed by the correction. Pre-correction agreement on the other 99 cells remains available as independent corroboration and is reconstructible from commit `805652b` plus tables D1/D2/D3 in `oracle/ORACLE.md`.

3. **The example's Java was LLM-generated (ChatGPT), and the `provenance_class` field records it as `private-attested`.** No human authored the implementation.

4. **Precision is unmeasured and unmeasurable at this corpus size.** `negative_class.n = 0`; the §8 one-in-four negative quota is unmet; all six `fp_family` values have zero witnesses. The `fp = 0` recorded on 2026-08-23 is an artefact of the tool emitting nothing, not a precision result.

5. **Every stated limitation F1–F12 is asserted, not measured, under the corpus protocol.** All rest on pre-protocol fixtures that §2 excludes from corpus evidence. The thesis must mark each limitation as measured or asserted, and today all are asserted.

6. **Classifier recall is a separate and currently unmeasured quantity from transition recall.** SealFSM recognised 0 of 1 corpus hierarchies as a machine. Because a non-detected hierarchy contributes no transitions, transition recall as reported is conditional on detection, and detection has one witness with a negative outcome. Any recall figure must state the detection rate alongside it.

7. **The recall figure for `commit_shape: argument-passed` rests on one example, from one specification, in one idiom, generated by one LLM and corrected by one agent.** The same holds for `CENTRALIZED_DISPATCH`. Neither is a stratum; each is an example.

8. **Non-detection is currently exempt from the unconditional-fix route, which creates a standing incentive toward abstention.** A triage decision on 2026-08-23 established that a tool emitting no machine and emitting a diagnostic commits no soundness violation. Composed with the second-fixture rule, the analyser cannot be extended to any undetected design until two independent witnesses exist. The reported absence of soundness violations must therefore always be read together with the detection rate.

---

## VERDICT

# READY FOR INTERNAL ITERATION

The claims made in this cycle are counts, they are accurate, they are traceable to `results.json`, and they are hedged correctly at n = 1. Nothing in the corpus or the method invalidates them, so this is not NOT READY. But no figure this cycle produced is reportable in a thesis: n = 1, zero negatives, zero real-world origins, zero measured F-code limitations, precision unmeasurable, and a detection rate of 0 of 1. The holdout is empty and dev work has barely started, so unsealing is not in view.

The reporting discipline (§5, §8) and the oracle construction (§2) are both genuinely strong and should not be re-engineered. The two governance breaches (items 1 and 2 below) are the things that must be closed before the next cycle's output can be relied on, because both establish precedents that will be invisible one run at a time.

---

## Blocking items

Each is phrased so the next review can determine mechanically whether it was met.

1. **`CORPUS_PROTOCOL.md` §9.3 contains, in its own text, an explicit statement of whether a `FIX-REQUIRED` soundness fix is exempt from the second-fixture requirement.** `FIXLOG.md` no longer states any rule not present in `CORPUS_PROTOCOL.md`; the sentence at `FIXLOG.md` lines 9–11 either matches protocol text verbatim or is removed. No `triage.md` cites `FIXLOG.md` as the source of a binding rule.

2. **`CORPUS_PROTOCOL.md` §12 defines a soundness class for whole-hierarchy non-detection (e.g. `not-detected`), and §9's triage table contains a row specifying its route.** §10's `dropped_without_marker` clause states explicitly whether it applies when `machines_emitted == 0`. No future `triage.md` reclassifies a `findings.md` soundness class on its own reasoning; where it disagrees, it files an `oracle-dispute`-style escalation to the human instead.

3. **`results.json` carries, per example, a field recording whether the example's implementation was conformed to its oracle** (e.g. `implementation_conformed_to_oracle: true|false`), sourced from `meta.json`, and `report.md` reproduces it in the per-example table. Verifiable by grepping `results.json` for the field and `report.md` for the column.

4. **`meta.json` for `lcp_automation_chatgpt` records `provenance_class` with a value defined in `CORPUS_PROTOCOL.md` §3**, or the field is removed. If retained, §3 enumerates its permitted values and states which applies to LLM-generated-then-corrected content. `synthesis_basis_url` and `synthesis_basis_archive_url` are likewise either added to §3 or removed.

5. **The `node-shape` field of `centralized-carrier-nondetection` in `findings.md`, and the DEFERRED node shape in `triage.md`, describe the arm value as a `CtInvocation` of a helper returning C** (not a direct `CtConstructorCall` of C), record the second-level `configureTimeoutExpired()`-style zero-argument delegation as a sub-shape, and record that an H-typed argument to a never-returning helper is not a successor. Verifiable by reading the two fields against `examples/lcp_automation_chatgpt/java/LcpAutomaton.java` lines 43–223.

6. **The corpus request in `triage.md` no longer excludes candidates in which the carrier's H component is written back to a field of the root type.** Item 4 of the request is amended so that a candidate structurally identical to `LcpAutomaton.on(...)` would be accepted, not rejected.

7. **`negative_class.n >= 1` and at least one `fp_family` value has a witness in `dev` before any precision figure appears in any `report.md`.** Until then, every artefact reporting `fp = 0` states in the same sentence that it is an artefact of zero emitted machines and is not a precision measurement.

8. **`sha256("lcp_automation_chatgpt")` is recomputed independently of the intake agent and the result recorded in `reports/<date>/run.log`,** confirming `a7c3460842f92be2637e015e27ee97996e0ecbbedaf4a5ed8cdacbd58d979c7d` and `split: dev`.

9. **`sources.json` and `SOURCES.md` exist and are generated from `examples/*/meta.json`,** as §2's layout requires. Neither currently exists.

10. **`meta.json` records a licence determination for the specification the oracle derives from** — either `source_license` / `source_license_url` populated for RFC 1661, or a documented statement in `PROVENANCE.md` of the basis on which its §4.1 cell text is redistributed verbatim in `oracle/transitions.tsv`.

11. **The INFO diagnostic emitted on non-detection no longer asserts causes that are false for the case at hand.** Specifically, `"may be event/? type or unresolved dispatch"` is replaced by a message that does not claim the hierarchy is an event type or that the dispatch is unresolved when neither holds. Checkable against `run.log` on the next run of `lcp_automation_chatgpt`.

12. **`report.md` states the detection rate (machines emitted / machines expected) adjacent to every statement about false positives or soundness violations.** No artefact contains a sentence of the form "no soundness violations were recorded" without the detection rate in the same paragraph.
