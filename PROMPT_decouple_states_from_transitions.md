# Task: decouple state enumeration from transition resolution

You are working on **SealFSM**, a Java 21 static analyser built on Spoon that recovers finite state machines from sealed class hierarchies. Read `README.md`, `CLAUDE.md`, `src/main/java/io/sealfsm/detect/`, `src/main/java/io/sealfsm/extract/`, and `src/test/java/io/sealfsm/ExtractionIntegrationTest.java` before writing any code.

Do not start editing until you have completed step 0 and reported your findings.

---

## Background: the claim being violated

The thesis this tool supports makes **two claims of different strength**, and their independence is its central contribution:

1. **States are exact by construction.** Every permitted subtype is a state; the `permits` clause is compiler-checked, so the state set *Q* is complete. This is not an analysis result — it is a property of the source.
2. **Transitions are approximate.** The relation *δ* is recovered by intra-procedural data flow, reported with precision and recall, and anything unprovable is recorded as an explicit unresolved edge.

**The current implementation silently couples them.** When no transition producer is recognised, the classifier abstains, the machine is never constructed, `StateExtractor` is never invoked, and the tool reports **zero states** for a hierarchy whose `permits` clause names them unambiguously. That converts claim 1 into "states are exact *when transitions resolve*", which is a materially weaker claim and one the tool does not need to make.

Concretely, the failing shape is a hierarchy with a genuine centralized dispatch over the state — the discrimination is present and exhaustive — whose arms hand the successor to a helper rather than constructing it in place:

```java
Latch next(Latch state, Signal signal) {
    return switch (state) {
        case Idle i  -> apply(i, signal);
        case Armed a -> apply(a, signal);
        case Fired f -> apply(f, signal);
    };
}
```

Today, when the helper cannot be folded, the entire hierarchy is lost — states included.

---

## The trap you must not fall into

The obvious fix is to relax the classifier: "if there is an exhaustive switch over the hierarchy, accept it as a machine." **Do not do this.** The repository already contains the negative control that this breaks — `examples/foreignfold`. There, every structural signal of a state machine is present (sealed hierarchy, a driver holding a field of that type, exhaustive switches in both accepted commit positions) and the only thing missing is that the switches fold into `String` and `int`.

Every sealed sum type in Java is eventually switched over. If discrimination alone is sufficient, `String describe(Shape s)` becomes a two-state automaton, the classifier's confusion matrix becomes meaningless, and the precision half of the evaluation is destroyed.

**The commit requirement is the whole discriminator and must not be weakened.** What must change is *where* the commit is allowed to be observed, not *whether* one is required.

---

## Step 0 — mandatory investigation, then branch

**Do not implement anything from "Design to implement" until Step 0 is complete.** Its purpose is to establish how much of the problem is a *bug in existing recognition* and how much is a *genuine missing capability*, because the two need different fixes and fixing the second while the first is present produces dead code and a misattributed finding in the thesis.

Write everything below to `INVESTIGATION.md` in the repository root as you go. It is a deliverable, not a scratchpad: the FIXLOG entry and the thesis's scope paragraph are both derived from it.

### 0.1 Build the probe inputs

Create three throwaway fixtures under `src/test/resources/probe/` (**not** under `examples/` — they are diagnostic instruments, not corpus, and must be deleted or promoted deliberately at the end of Step 0). Each is the *same* three-state sealed hierarchy with the *same* exhaustive centralized switch, differing only in what the arms hand off to:

- `probe/returnh/` — arms call a helper whose **declared return type is the hierarchy root**, with a body the resolver cannot see through.
- `probe/voidh/` — arms call a **`void`** helper whose body assigns an H-typed field.
- `probe/voidforeign/` — arms call a **`void`** helper whose body assigns a **non-H** field (`String`). Structurally identical to the previous one at the call site.

Keep them minimal and keep them in three distinct Java packages.

### 0.2 Run the instruments the repository already provides

For each probe fixture, and record the verbatim output in `INVESTIGATION.md`:

```
mvn -q compile
java -cp target/classes io.sealfsm.DebugHarness src/test/resources/probe/<name>
java -cp target/classes io.sealfsm.DebugAst    src/test/resources/probe/<name> --returns
java -jar target/sealfsm.jar --src src/test/resources/probe/<name> --out out/probe/<name> --explain
```

`--explain` is the primary evidence: it prints every classifier predicate from the branch that actually ran. `DebugAst --returns` tells you the exact `CtExpression` subclass each arm produces, which predicts what `TransitionResolver` will do with it.

### 0.3 Answer these questions with file:line references

1. **Where is the coupling?** Trace `Analyzer.analyze` from `Classification.isStateMachine() == false` to the machine never being constructed. State whether `StateExtractor` is genuinely unreachable for a rejected root, or whether states are extracted and then discarded. Quote the lines.
2. **What happens on `probe/returnh`?** In `CommitClassifier.classify`, when a switch arm's produced value is a `CtInvocation` whose declared return type is in H, is the commit recognised? Give the code path and the verdict `--explain` printed. This is the question the whole branch below turns on.
3. **What happens on `probe/voidh` and `probe/voidforeign`?** Are they distinguished at all today, or do both simply abstain? If both abstain, confirm they abstain for the *same* stated reason.
4. **Which existing fixtures currently produce zero machines?** Enumerate them from the `examples/` directory listing (do not hard-code a list), and for each state whether it is an intentional negative control or an unintended loss. Any unintended loss found here is a separate finding and must be recorded even if it is out of scope for this task.
5. **Does `--explain` distinguish "no dispatch found" from "dispatch found, commit not proven"?** If the trace collapses these into one line, the diagnostic channel cannot support the Tier 3 candidate reporting required later, and fixing it is part of this task.

### 0.4 Branch on the finding

Classify the result into exactly one of the following and record which in `INVESTIGATION.md`, with the evidence that selected it.

**Branch A — `probe/returnh` is rejected: recognition bug.**
An arm whose callee's codomain is the hierarchy root is a commit *proven by declared type alone*. Refusing it is not a scope limit, it is a defect: the tool is discarding a proof it already holds.

Do this first, before anything else:
1. Fix it in `CommitClassifier` (or wherever step 0.3 q2 located the refusal) so that a `CtInvocation` whose declared return type is in H — or is a carrier with exactly one H-typed component — counts as `VALUE_RETURN` / `CARRIER_RETURN` respectively, with the successor left **unresolved**. Commit existence and successor identity are separate questions; answer only the first.
2. Add a regression test pinning `probe/returnh` (promote it to `examples/opaquesuccessor`, which is fixture 1 in the fixture list below — the probe becomes the fixture, so nothing is written twice).
3. Re-run the full existing suite. If any golden moves, **stop and report**; a fix that changes `traffic`, `tcp`, `lcp_automation` or any rejection has done something other than what it claims.
4. Re-run all three probes and re-answer 0.3 q3. Then continue to Branch B with what remains.

**Branch B — `probe/returnh` is accepted, but `probe/voidh` is not: missing capability.**
This is the genuine gap and is what the "Design to implement" section addresses. Proceed to implement:
- the three-tier outcome (ACCEPTED / DETECTED_EMPTY / CANDIDATE),
- the k = 1 commit-existence probe,
- the `CommitEvidence` IR field,
- the `candidates()` channel,
- and the full fixture set below.

Note that Branch A, once fixed, lands here: the void-callee case is not solved by the codomain fix, because a `void` method has no codomain to read.

**Branch C — both `probe/voidh` and `probe/voidforeign` are already accepted.**
Then a false positive already exists and is more urgent than the recall gap: the tool is admitting a foreign fold behind one indirection. **Stop, report, and do not widen anything.** Narrowing comes first; propose the narrowing and wait.

**Branch D — the investigation contradicts the premises of this task.**
For example, states turn out to be extracted and discarded rather than never extracted, or the coupling sits somewhere other than classification. **Stop and report.** Do not work around a contradicted premise; the fix will be in a different place and the thesis narrative depends on naming it correctly.

### 0.5 Re-scope before implementing

After branching, restate in `INVESTIGATION.md`: given what was actually found, which parts of "Design to implement" are still required and which are now unnecessary. Implement only what remains. If Branch A alone closed the reported symptom, say so plainly — a smaller true finding is worth more to the thesis than a large speculative one.

---

## Design to implement

Apply this section **only as scoped by Step 0.5**. If Branch A closed part of the symptom, that part is already done and must not be reimplemented behind a probe. If Branch C or D was selected, do not implement any of this.

### Three outcomes, not two

Replace the binary accepted/rejected outcome with three, distinguished by **what has been proven about the commit**, never by how much of the transition relation happened to resolve.

**Tier 1 — ACCEPTED.** Dispatch present, commit proven, at least one successor resolved. Unchanged behaviour.

**Tier 2 — DETECTED_EMPTY.** Dispatch present, commit proven, **no successor resolved at all**. This is a real machine and must be emitted:
- states come from `permits`, complete, exactly as for Tier 1;
- for **every dispatched arm**, emit one unresolved transition whose `from` is the arm's matched state and whose `to` is unknown. The source state is known — it is the arm — so an empty transition list here would be a silent drop and a violation of the soundness invariant. The output must show `Idle --?--> ?`, not nothing.

**Tier 3 — CANDIDATE.** Dispatch present, commit **not** proven. Not a machine.
- must **not** appear in `ExtractionResult.machines()`;
- must appear in a separate channel — add `ExtractionResult.candidates()` — carrying the root, its complete state set from `permits`, the dispatch sites found, and the reason the commit could not be proven;
- must be reported in diagnostics with the state count, so a user asking "what are the states of this hierarchy?" gets an answer even though the tool refuses to call it a machine.

The line between Tier 2 and Tier 3 is the honest scope boundary of the thesis. Write it as a positive predicate, not as an apology.

### Widening where the commit may be observed

The commit is currently read from the **immediate syntactic context** of the dispatch only. Widen it by exactly one hop, and ask the callee a strictly weaker question than the resolver asks:

> **Commit-existence probe (k = 1).** For an arm whose produced value is a call, or an arm that is a bare call statement, open the callee's body one level and ask only: *does this body install a hierarchy value?* — that is, does it `return` with the method's codomain in H (or a carrier with exactly one H-typed component), assign to an H-typed field, assign to an H-typed local that is subsequently committed, or hand a value to a recognised mutator? Answer yes/no. **Do not attempt to identify which state.**

This is deliberately much cheaper than resolution: it reads codomains and declared types of assignment targets, not values. It must obey the existing rules without exception:

- **F11** — a body the analysis did not read may not be reasoned from. A reflective shadow (JDK method), an abstract method, or a callee outside the source set yields *unknown*, which is **not** a proof of commit. Such an arm leaves the hierarchy in Tier 3, not Tier 2.
- **F22** — nothing keys on a name. Not the callee's, not the field's, not the parameter's.
- **F9** — a callee with no `return` anywhere in a non-void method cannot complete normally; it is an undefined input and contributes no evidence of commit and no edge.
- The composition veto still runs first and still binds. A recursive data type does not become a machine by having its rewrite hidden behind a helper.
- Depth is exactly 1 for this probe. Do not reuse the k = 2 resolution budget; the two limits are independent and must be reported independently.

Concretely this separates three cases that are currently conflated:

| arm | codomain / callee body | outcome |
|---|---|---|
| `case Idle i -> apply(i, e);` where `Latch apply(...)` | codomain is in H | commit proven by codomain — no probe needed. Verify this already works; if not, it is a bug, not scope. |
| `case Idle i -> apply(i, e);` where `void apply(...)` writes `this.state = …` | probe finds an H-typed field write | commit proven via callee — Tier 2 at worst |
| `case Idle i -> note(i);` where `void note(...)` writes `this.label = m.name()` | probe finds a commit to `String` | commit **not** proven — Tier 3, never a machine |

The third row is `foreignfold` one indirection deeper. It is why the probe must ask about the *type* of what is installed and not merely whether anything is installed.

### IR changes

Record how the commit was established, per machine, as a first-class value:

```java
public enum CommitEvidence {
    /** Observed in the dispatch's own syntactic context. */
    DIRECT,
    /** Established by opening one callee body (k = 1 commit-existence probe). */
    VIA_CALLEE,
    /** Not established — the hierarchy is a candidate, not a machine. */
    UNPROVEN
}
```

Reason: pooling an observation and an inference under one label makes a later precision or recall gap unattributable — the same argument that split `MUTATOR_ARGUMENT` out of `FIELD_MUTATION`. Surface it in DOT as a graph-level comment and in SCXML as an XML comment, and expose it on `StateMachine` so the evaluation can stratify by it.

Also add, on `StateMachine`, whatever is needed to distinguish Tier 1 from Tier 2 without recomputing it (e.g. `isDetectedEmpty()` derived from `resolvedTransitionCount() == 0 && !transitions().isEmpty()`), and make sure `--explain` names the tier and the evidence.

---

## Fixtures to write

Create these under `examples/`, each in **its own Java package** (a duplicated package name across example directories makes Spoon refuse to build the model). Every fixture must compile under `javac`. Follow the existing fixture style: a class-level Javadoc stating exactly what the fixture is for, what must happen, and — for controls — what failure it is guarding against.

### The purpose of this set

These fixtures exist to make one property **falsifiable**: that state enumeration is complete regardless of how badly transition extraction does. A test that only checks fixtures where transitions succeed cannot distinguish "states are exact" from "states are exact when transitions resolve". So the set must include machines where transition recovery **fails completely and by design**, and their state sets must still be exact.

Fixtures 1, 2 and 3 are the promoted forms of the Step 0 probes (`probe/returnh`, `probe/voidh`, `probe/voidforeign`). Promote them rather than writing them again, then delete `src/test/resources/probe/`. Fixtures 4–6 are new.

### 1. `examples/opaquesuccessor` — Tier 2 through an H-returning helper

A three- or four-state hierarchy with a centralized switch whose every arm returns `pick(state, event)`, where `pick` has codomain in H but a body the resolver cannot see through — put its returns inside a construct the walker does not descend (the existing standing probe for this is `synchronized`; check `examples/nonreturning` and use whatever construct is currently outside the walker's reach, and say in the Javadoc that teaching the walker that construct must make this fixture fail loudly so a new probe is chosen).

Must produce: one machine; `states == permits`, complete; `CommitEvidence.DIRECT` (codomain proves it); `resolvedTransitionCount() == 0`; one unresolved transition **per dispatched arm**, each with a known `from`.

### 2. `examples/voidcommit` — Tier 2 through a void callee

The same machine shape, but the switch is a statement whose arms call `install(next(state, event))` or `driver.apply(state, event)`, where the callee is `void` and its body writes an H-typed field. The successor must be unrecoverable (compute it through something the resolver cannot follow, e.g. a lookup in a `Map` field, or an arithmetic index into an array).

Must produce: one machine; states complete; `CommitEvidence.VIA_CALLEE`; zero resolved transitions; one unresolved edge per arm.

### 3. `examples/voidfold` — NEGATIVE CONTROL, and the load-bearing one

Structurally **indistinguishable** from fixture 2 at the call site: same sealed hierarchy, same exhaustive centralized switch, same void callee taking the matched state. The only difference is inside the callee, which commits to a **non-H** field (`this.label = m.getClass().getSimpleName();`).

Must produce: **no machine**. One candidate, reporting the complete state set and the reason. If this fixture ever yields a machine, the probe has become "any exhaustive switch is a state machine" and the precision claim is gone.

Hold fixtures 2 and 3 as close as you can — ideally the same hierarchy shape, same method names, same arity — so nothing but the callee's committed type can be what the analysis is reacting to.

### 4. `examples/unreadablecallee` — the F11 control

Same shape as fixture 2, but the arms call a method whose declaration the analysis cannot read: an abstract method, an interface method with no implementation in the source set, or a JDK call. The empty body must be treated as **unknown**, not as evidence.

Must produce: **no machine**; a candidate with the complete state set; a diagnostic naming the unreadable callee. This is the fixture that stops the probe from turning "I could not read it" into "it commits".

### 5. `examples/opaquepolymorphic` — the same property at the other locus

A `POLYMORPHIC_OVERRIDE` hierarchy where each state's transition method returns H but computes its successor unrecoverably. Same expectations as fixture 1, at a different dispatch locus.

Reason: if only the centralized locus is covered, the property is demonstrated for one idiom and asserted for the rest. This is the second, independently-sourced generality fixture the FIXLOG requires — do not skip it, and do not derive it from fixture 1 by copy-editing.

### 6. `examples/emptycandidate` — a Tier 3 with composite states

A hierarchy whose commit is unproven **and** whose permitted subtypes include a nested sealed type and a permitted enum, so the candidate's reported state set exercises composite and enum-constant enumeration. This proves the candidate channel reports the same complete state set the machine path would have, rather than a flattened approximation of it.

---

## Tests to add

In `ExtractionIntegrationTest` (or a new `StateCompletenessTest`, if the file is already unwieldy):

1. **The property test, stated directly.** For every fixture directory in `examples/` — enumerated from the directory listing, not hard-coded — for every machine **and** every candidate produced, assert that the reported state set equals the transitive `permits` closure of its root, read independently through `SpoonCompat`. Deriving the list from the filesystem is deliberate: a hard-coded list goes stale, and the test then passes while testing a different input than it claims.
2. Fixtures 1, 2 and 5: states complete, `resolvedTransitionCount() == 0`, `transitions().size() == number of dispatched arms`, every unresolved edge has a `from` that names a declared state and none has `from == "<unknown>"`.
3. Fixtures 3, 4 and 6: `machines()` contains nothing for that root; `candidates()` contains it with the complete state set; a diagnostic explains why.
4. **Every existing golden must be unchanged.** Assert explicitly that `traffic` is still 3 states / 0 unresolved, `tcp` still 11 states with `POLY_CARRIER`, `lcp_automation` still 10 states and 113/113 resolved, `lcp_automation_chatgpt` still 111/111 with `SELF` absent from the successor-form axis, `shape` / `foreignfold` / `treebuilder` / `nestedroots.Node` / `chaindispatch.Tree` still rejected. A widening that changes any of these has changed something other than what it claims to.
5. A test asserting `CommitEvidence` is `DIRECT` for the existing fixtures and `VIA_CALLEE` only where the probe was actually needed — so the probe cannot quietly start claiming credit for machines the direct path already found.

---

## Constraints

- **The soundness invariant is not negotiable.** No transition may be dropped. Tier 2 emits unresolved edges; it does not emit an empty relation.
- **No name-based recognition anywhere**, in any new code, including the probe.
- **Do not weaken `CommitClassifier`'s existing rules.** Add a probe that runs *after* they decline; it may only add machines, never reclassify one.
- **Do not modify any frozen oracle**, and do not adjust an existing fixture's expected values to make new code pass. If an existing expectation now fails, stop and report it as a finding.
- Prefer one structural change that separates the two concerns over targeted patches at each call site.
- Keep `examples/` free of anything the implementer would need to have seen in order to make the analysis pass. If a fixture had to be shaped around the implementation, say so in the FIXLOG.

## Deliverables

1. `INVESTIGATION.md` — the Step 0 report: probe outputs, the five answers with file:line references, the selected branch with its evidence, and the Step 0.5 re-scope.
2. The implementation, as a small number of coherent commits, and only what Step 0.5 left in scope. If Branch A was taken, its fix and its regression test are a separate, earlier commit than any widening.
3. The fixtures, with Javadoc stating what each guards against, and `src/test/resources/probe/` removed.
4. The tests above, passing, with the existing suite unchanged.
5. A `FIXLOG.md` entry recording: the finding **as Step 0 established it** (a recognition bug, a missing capability, or both — not the symptom as it was reported to you), the fix, the second independently-sourced generality fixture, and — explicitly — which cells of the locus × commit product this newly reaches and which it does not.
6. A short note, suitable for lifting into the thesis, stating the resulting scope boundary as a positive predicate: what the tool proves about commit existence, what it proves about successor identity, and why those two limits are independent.
