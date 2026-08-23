# CLAUDE.md — SealFSM project context for Claude Code

# Development Guidelines

Act as a **Senior Advisor**, not just an implementation assistant.

Before changing code, first critically evaluate whether a change is actually necessary. Challenge assumptions, identify weaknesses, and recommend improvements when appropriate.

## Project Goal

This project is a **Master’s thesis and research-quality open-source project**. The algorithm should reliably detect FSMs in **real-world Java projects**, not just match specific examples.

Prioritize:

* Generality over example-specific solutions.
* Semantic and structural patterns over hard-coded names or syntax.
* Correctness, robustness, and maintainability.
* Low false positives and false negatives.
* Solutions that generalize across different Java coding styles and architectures.

When a new example fails, **do not immediately add a special case**. First determine what general limitation it reveals and whether the underlying algorithm or abstraction should be improved.

Before implementing a change, consider its impact on existing cases, scalability, and research validity.

The goal is to build a **general-purpose, professional FSM detection algorithm**, not an algorithm optimized to pass individual examples.

## What this project is

SealFSM is a Java 21 static-analysis tool that extracts finite state machines from sealed class hierarchies. It is the implementation component of a master's thesis titled "Automated Extraction of Finite State Machines from Sealed Class Hierarchies in Modern Java: A Static Analysis Approach."

The tool reads Java source code via Spoon, finds sealed type hierarchies, classifies which ones are state machines (rejecting plain sum types), extracts states and transitions, and outputs the FSM as Graphviz DOT and W3C SCXML.

## The thesis distinction — never conflate these

The tool makes two claims of DIFFERENT strength. Every piece of code, output, test, and documentation must keep them separate:

1. **State enumeration is provably complete.** States come from `permits` clauses, which are compiler-checked exhaustive. This is exact. A permitted `enum` contributes its constants as child states — an enum's constants are closed in exactly the way a `permits` clause is, so this stays exact rather than becoming heuristic.
2. **Transition extraction is approximate.** Transitions are recovered via intra-procedural data-flow analysis. This is empirical and reported with precision/recall.

Unresolved transitions are FIRST-CLASS — they are recorded (dashed red in DOT, XML comment in SCXML), never silently dropped. This is a design invariant, not a convenience.

## Build and run

```bash
mvn package                    # build fat jar
mvn test                       # run all tests
mvn compile                    # compile only (for debug tools)

# One command: clean build, run every examples/<name> into its own
# out/<name>/, render every .dot to .png (needs Graphviz `dot` on PATH),
# and save everything printed to out/results.txt (-IncludeDiagnostics to
# also capture [INFO]/[WARN] lines, off by default)
scripts\build-run-render.ps1
scripts\build-run-render.ps1 -Example traffic -SkipBuild   # one example, reuse the jar

# CLI, by hand
java -jar target/sealfsm.jar --src examples/traffic --out out/traffic

# Debug tools
java -cp target/classes io.sealfsm.DebugHarness examples/traffic
java -cp target/classes io.sealfsm.DebugAst examples/door --returns

# Render diagram
dot -Tpng out/traffic/TrafficLight.dot -o TrafficLight.png
```

**Never run `--src examples --out out` as one combined invocation** (and never
give a new `examples/<name>/` fixture a Java package another example already
uses). Every example directory uses a distinct package for exactly this
reason: two top-level types sharing one package makes Spoon refuse to build
the model at all (`ModelBuildingException: The type X is already defined`),
and even past that, `Main` names output files after the *machine*, not the
source directory — a flat `--out` lets a second same-named machine (e.g. two
different `DhcpState` fixtures) silently overwrite the first's `.dot`, no
warning. `scripts\build-run-render.ps1` runs each example into its own
`out/<name>/` and sidesteps both failure modes; use it (or per-directory `--src`
calls) instead of the combined form.

## Tech stack

- Java 21 (sealed types, pattern-matching switch, records)
- Spoon 10.4.2 (static analysis AST — version is a pom.xml property, bump freely)
- Maven (build, shade plugin for fat jar)
- JUnit 5 (tests)
- No runtime dependencies beyond Spoon

## Architecture — the pipeline

```
Java source → Spoon parser → CtModel
  → SealedHierarchyDetector (finds sealed roots, filters nested)
  → StateMachineClassifier (FSM or sum type? polymorphic/centralized?)
      ↳ DispatchCommitDetector  (switch-over-H + H-typed commit; the codomain guard)
      ↳ CarrierTransitionDetector (carrier successors + sibling-vs-nested guard)
  → StateExtractor (permits → State IR, exact)
  → TransitionExtractor + TransitionResolver (data-flow → Transition IR, approximate)
  → Analyzer (orchestrates above + initial-state heuristic)
  → DotSerializer / ScxmlSerializer → .dot / .scxml
```

## Package layout

```
src/main/java/io/sealfsm/
├── Analyzer.java              # pipeline orchestrator
├── Main.java                  # CLI entry point
├── DebugHarness.java          # step-by-step pipeline runner (debug)
├── DebugAst.java              # Spoon AST inspector (debug)
├── annotation/Fsm.java        # optional @Fsm marker
├── detect/
│   ├── CarrierTransitionDetector.java # carrier successors + sibling-vs-nested guard
│   ├── DispatchCommitDetector.java    # switch-over-H + commit form; rejects foreign folds
│   ├── SealedHierarchyDetector.java   # finds sealed roots
│   ├── SpoonCompat.java               # Spoon version isolation (reflective)
│   └── StateMachineClassifier.java    # FSM vs sum type gate + shared helpers
├── extract/
│   ├── StateExtractor.java            # permits → State (recursive composites)
│   ├── TransitionExtractor.java       # both encodings; switch-arm handling
│   └── TransitionResolver.java        # expression → target state(s) data-flow
├── model/
│   ├── State.java                     # state IR (id, qualifiedName, composite, initial, terminal, children)
│   ├── StateNaming.java               # qualifiedName → id; disambiguates colliding simple names
│   ├── Transition.java                # transition IR (from, to, event, guard, resolved, note)
│   ├── StateMachine.java              # aggregate (states, transitions, encoding, alphabet)
│   ├── SuccessorForm.java             # axis 2: how the successor is SPELLED
│   ├── CommitForm.java                # axis 3: how the successor is INSTALLED
│   └── ExtractionResult.java          # machines + Diagnostic records
└── serialize/
    ├── DotSerializer.java             # Graphviz DOT output
    └── ScxmlSerializer.java           # W3C SCXML output
```

## Examples (also regression fixtures)

- `examples/traffic/` — POLYMORPHIC: sealed `TrafficLight permits Red, Green, Yellow` with `next()` methods. 3 states, 3 resolved transitions, initial=Red.
- `examples/door/` — CENTRALIZED_DISPATCH: sealed `Door permits Open, Closed, Locked` with `DoorMachine.transition(Door, Event)` pattern-matching switch. Exercises guarded ternary, `return current` self-loop, type-pattern from-states. 3 states, 5 resolved transitions, initial=Closed.
- `examples/tcp/` — POLYMORPHIC dispatch, POLY_CARRIER commit: sealed `TcpState` permits the 11 RFC 9293 states, each overriding `Transition on(Event)` where the successor is an *argument* to `Transition.to(...)` and `Transition.ignore(this)` is a self-loop. 11 states, 44 resolved transitions, initial=Closed. `tcp.Event` is Σ and must stay unrecognized.

**The commit-form family** — four fixtures holding the ENCODING fixed (one switch over the state type) and varying only where that switch is hosted and how its result is installed. Recognising only the first was the false-negative that the commit axis closes.

- `examples/http2-stream-claude/` — CENTRALIZED_DISPATCH / VALUE_RETURN: `StreamStateMachine.next(state, event)` is a static pure function outside the hierarchy, `return switch (state) { case Idle s -> fromIdle(s, event); ... }` delegating to per-state helpers. 7 states, Σ = 8 composed symbols (`Send.HEADERS`…), 20/20 resolved, `Closed` terminal. Exercises **type** patterns (`case Idle s`), F3 delegation folding, and **component dispatch** (`case Send(Signal s) -> switch (s)`).
- `examples/http2-stream-gemini/` — CENTRALIZED_DISPATCH / FIELD_MUTATION: the SAME RFC 9113 machine as `this.currentState = switch (this.currentState) {...}`. The host takes no hierarchy-typed parameter, so the signature-based recognizer never saw it. 7 states, Σ = 8 flat enum constants, 20/20 resolved, `Closed` terminal. Exercises **record** patterns (`case Idle()`) and multi-label arms.

**These two are the same automaton written twice, independently, in different idioms** — `ExtractionIntegrationTest.bothHttp2ModelsRecoverTheSameMachine` requires identical states, |Σ|, initial state and (modulo input spelling) transition relation. Two source spellings, one answer, is the strongest correctness signal in the corpus short of hand-written ground truth; keep it passing. It is also why component labels matter: while an edge carried the event *family* (`Send`) instead of the input (`Send.HEADERS`), this pair reported 18 edges against 20 and the gap looked like a recall difference between commit forms.
- `examples/barefield/` — CENTRALIZED_DISPATCH / FIELD_MUTATION with the `this.` omitted: `state = switch (state) {...}; return state;`. 3 states, 4/4, `Fired` terminal. Pins that recognition keys off the resolved TYPE of the assignment target, never the spelling.
- `examples/accumulator/` — CENTRALIZED_DISPATCH / LOCAL_ACCUMULATOR: `Phase next = switch (phase) {...}`, whose arms compute the successor by assigning a *second* local on several branches. 4 states, 4/5 (one out-of-budget delegation stays UNRESOLVED). This is the F1 trap in both directions: a fabricated unguarded self-loop from the local's declared type, or a real target lost to collapsing the branch assignments.
- `examples/valueforms/` — SUCCESSOR FORMS: sealed `Signal permits Idle, Armed, Firing, Phase` holds the encoding fixed and varies only how each successor is *written* — construction, a concrete-typed singleton, a **root-typed** singleton (resolvable only via its initializer), enum constants (`Phase` is a permitted enum), a local, `this`, and a helper call that must stay UNRESOLVED. 6 states (the enum contributes RAMP/PEAK), 9/10 resolved.
- `examples/shape/` — NEGATIVE CONTROL: sealed `Shape permits Circle, Square, Triangle` with `area()` returning `double`. Must be REJECTED — no method returns the hierarchy type.
- `examples/treebuilder/` — NEGATIVE CONTROL for the sibling-vs-nested guard, on **both** acceptance paths: sealed `Expr permits Lit, Neg, Add` with per-subtype `Rewrite simplify()` (structurally identical to `examples/tcp`) *and* `CentralRewriter.fold(Expr)`, a centralized `return switch (e)` (structurally identical to `examples/door`). Must be REJECTED either way, because `new Add(fold(a.left()), fold(a.right()))` nests H inside a bigger H.
- `examples/nonreturning/` — F9 FIXTURE **and its own negative control**: sealed `Latch permits Idle, Armed, Fired`, dispatched from `LatchMachine.step`. Two helpers both return `Latch` and both defeat the shallow `collectReturns` walk (which does not descend into a `switch`), so only the presence of a `return` separates them. `reject` has none — provably always throws, so its three arms contribute **no edge**. `escalate` hides a `return` inside a `switch` — it can produce a state the analysis cannot see, so its arm must stay **UNRESOLVED**. Dropping that one is the false-drop F9 must never cause; it is the whole risk of the rule and the reason the fixture pairs them. It also carries **F11**: `case HOLD -> Objects.requireNonNull(current)` calls a JDK method, so Spoon supplies a reflective *shadow* declaration whose body is an empty stub. That body has no `return` for the same reason it has nothing at all — it was never parsed — and F9 read the emptiness as proof and deleted the edge. The rule may only be applied to a body the analysis actually **read**. 3 states, 5 transitions, 3/5 resolved, `Fired` terminal.
- `examples/lcp_automation/` — **RFC 1661 LCP**, the densest fixture and the one F10/F11/F12 were all found on: sealed `LcpState` permits the 10 §4.1 states, dispatched by `PppLcpStateMachine.transition` delegating to ten per-state handlers. 13 events × 10 states = 130 cells, +14 guard splits = 144 arms, of which 31 `throw` → **113 transitions, 113/113 resolved**, 46 of them self-loops (the RFC writes "ignore this event here" as an explicit stay-put cell). Σ = 13. Initial = `Initial`, recovered only by the weakest heuristic — every state including `Initial` has incoming edges, so the structural rule has no candidate. **Every** edge arrives through F3 delegation folding, which makes this the hardest stress test of that path in the corpus. LLM-generated from the RFC and left unedited except for the RXR event, which was missing and was added by hand (noted in the file).
- `examples/guardforms/` — **GUARD FORMS**, the axis orthogonal to encoding / successor form / commit form: how a `when` clause is *spelled*. Two states, one dispatch, twenty arms varying only the guard expression — bare invocation, unary, binary, `&&`, `||`, boxed `Boolean`, pattern-binding read, `instanceof`, ternary, array access, negated binary, static call, call chain, library static, a **switch-expression guard**, redundant parens, deep compound, a lambda inside the guard, a `(Boolean)` cast, and a nested record pattern. 2 states, 22/22. Only 7 of the 20 take Spoon's supported `getGuard()` path; the other 13 are recovered from the arm body, and the split is invisible from the source — which is why they are enumerated rather than assumed. Three were found losing their guard *only* by writing this table out: a `Boolean`-typed guard failing a check written for the primitive, a cast (not its own node in Spoon, so it reported the invocation's `Object` type), and a switch-expression guard whose five-line text reached the DOT label with newlines intact — Graphviz accepts that, so it failed silently. `Trigger.Inner` is deliberately **not** a `Trigger`: a permitted subtype inside another's component list makes Σ recursive, and the classifier then reports the event alphabet itself as a second machine.
- `examples/plumbing/` — **F10 FIXTURE**: sealed `Conveyor permits Stopped, Running, Jammed`, dispatched twice (VALUE_RETURN and FIELD_MUTATION) with each host buried in the bookkeeping real code contains — a `requireNonNull` prelude, an in-model `identity(current)` call, an audit call, a log line, a counter bump. None installs a successor, so none may be an edge. 3 states, 6/6. The sharpest statement is `identity(current)`: in-model, hierarchy-typed and trivially summarisable, so F3 *would* fold it to a self-loop — only its statement position rules it out, which is why a hierarchy-type guard alone is not the rule.
- `examples/plumbing-mutation/` — **NEGATIVE CONTROL for F10**: `@Fsm`-marked sealed `Hopper permits Empty, Filling, Full`, committed only through `ctx.setState(...)` — an expression statement that IS the commit. Separate hierarchy because the F2 path runs only as a fallback; beside a VALUE_RETURN machine it never executes and the control would assert nothing. `drive` uses **arrow** arms of a switch *statement* and `pump` **colon** arms, encoding deliberately DISJOINT relations so the two spellings stay separately attributable. 3 states, 6/6. It reports 3 nondeterminism warnings **by design**: mutation-encoding event labelling is future work, so both hosts' edges are eventless and `Empty` really does have two indistinguishable successors *as extracted*. That is an honest reading of what was recovered, not a defect.
- `examples/foreignfold/` — NEGATIVE CONTROL for the widened centralized recognizer: sealed `Mode permits Fast, Slow, Stopped`, a driver holding a `Mode` field, and exhaustive switches over it in **both** accepted commit positions (`return switch` and field assignment) — but folding into `String`/`int`. Must be REJECTED by the commit requirement. Accepting it would report an automaton whose every state has zero transitions.
- `examples/namecollision/` — **NAME-COLLISION FIXTURE**: sealed `Link permits Idle, Legacy.Idle, Phase, Mode`, holding both legal ways two states end up with the same simple name. `Idle` and `Legacy.Idle` are a top-level type beside a nested one — legal even outside a named module, since `permits` only requires the same *package* there and `Legacy` is in it; a cross-package pair (`a.Foo`/`b.Foo`) is the other spelling and is legal inside a named module. `Phase` and `Mode` are two permitted enums that both declare `IDLE`, and since a permitted enum contributes its constants as child states, those are two distinct states spelled identically — the likelier shape by far, because nothing discourages reusing `IDLE`/`ERROR`/`NONE`. 8 states, 12/12. Keyed on simple names it reports **11/11**: the two `UPGRADE` arms encode `Idle → Legacy.Idle` and `Legacy.Idle → Idle`, which collapse to the same `(from, to, event, guard, resolved)` tuple, and the extractor's transition `LinkedHashSet` discards one. A real transition dropped with **no unresolved marker**, behind a clean-looking `n/n` — the one outcome the record-everything invariant forbids. Graphviz hides the rest: node ids are global, so a state declared inside two clusters silently becomes one node in the first, with no warning, and the SCXML emits duplicate `id`s.

Reference output in `sample-output/` — diff after building to verify.

## Tests

- `serialize/DotSerializerTest` — pure IR/serializer (no Spoon)
- `serialize/ScxmlSerializerTest` — SCXML well-formedness + nesting (no Spoon)
- `detect/CarrierTransitionDetectorTest` — carrier recognition, the sibling-vs-nested guard (accept tcp / reject treebuilder), self-loops
- `detect/DispatchCommitDetectorTest` — the commit classification, as near-identical pairs differing only in codomain (accept `state = switch(state)` / reject `label = switch(state)`)
- `extract/TransitionResolverTest` — the successor sub-procedure, expression shape by expression shape
- `model/StateNamingTest` — the id-assignment rule, on qualified names directly (no Spoon): collision-free names untouched, nested/cross-package/enum-constant collisions separated, ids always distinct
- `ExtractionIntegrationTest` — full Spoon extraction over every example

## Key implementation details

### StateNaming (model/StateNaming.java)
The single mapping from a state's qualified name to the **id** that names it everywhere downstream — DOT node ids, SCXML `id`/`target`, and both endpoints of every `Transition`.

An id used to be the bare simple name, which is **not** an identity: a `permits` clause may legally name two types sharing one (a nested type beside a top-level one in the same package; two types in different packages inside a named module; two permitted enums declaring the same constant). The consequence was not cosmetic. Two distinct edges between two distinct states became the same `(from, to, event, guard, resolved)` tuple, and the extractor's transition `LinkedHashSet` discarded one — a transition dropped with **no unresolved marker**, reported as a clean `n/n`. `examples/namecollision` is the fixture; it reports 11/11 without this and 12/12 with it.

- **The rule**: an id is the *shortest dot-separated suffix of the qualified name that is unique* among the machine's states. Uncollided states therefore keep their bare simple name, so existing output is byte-identical and only collided states lengthen: `namecollision.Idle` / `Legacy.Idle`, `a.Foo` / `b.Foo`, `Phase.IDLE` / `Mode.IDLE`. A package prefix alone is NOT the rule — the first pair shares a package, and the nest path is what separates them.
- Qualified names are **canonicalised** (`$` → `.`) before anything else. Spoon spells a nested type `Owner$Nested`, so splitting on `.` alone reads that whole tail as the simple name: every nested state in the corpus gets renamed and no nested collision is ever detected. This was found by the test suite, not by inspection.
- The naming must be built **once** and consulted by *every* producer of a state name — `StateExtractor`, `TransitionExtractor` (from-states, `dispatchedStates`), `TransitionResolver` (targets) and `Analyzer` (initial state). Disambiguating ids after the fact cannot work: by then an endpoint reads `"Idle"` and the information that would tell the two apart is gone. This is why `StateExtractor.extract` returns the naming alongside the states rather than letting callers rebuild it — a second construction of the "same" mapping is exactly how the two halves would drift.
- `StateMachine.duplicateStateIds()` is the drift check, reported by `Analyzer` as a WARN. It should always be empty; it exists because the assignment and the extractor are separate code, and a disagreement between them would otherwise be invisible in the output. A diagnostic, not a throw — a wrong diagram on someone's repository is bad, a crash is worse.
- Only STATE names go through it. `TransitionExtractor` derives event symbols, field names and variable names with `getSimpleName()` too, and those must stay untouched.

### SpoonCompat (detect/SpoonCompat.java)
All version-sensitive Spoon calls live here. `isSealed()` checks `ModifierKind.SEALED` with try/catch fallback. `permittedTypes()` uses reflection for `getPermittedTypes()` with a fallback that scans the model for direct subtypes (handles implicit permits).

### StateMachineClassifier (detect/StateMachineClassifier.java)
`centralizedReason` counts **distinct hosts**, not the sum of the two recognizers' findings. The signature-based recognizer and `DispatchCommitDetector` legitimately overlap — an `H transition(H, Event)` whose body is `return switch (current)` is seen by both — so summing them reported `examples/door`, which has exactly one transition function, as two. It keys on `declaringType#signature`, the same key the extractor dedups on, so the reported number and the walked set cannot drift apart.

Contains shared static helpers reused by the extractor:
- `findDistributedTransitionMethods(root)` — methods ON the hierarchy returning hierarchy type
- `findCentralizedTransitionMethods(root, model)` — methods OUTSIDE hierarchy taking+returning hierarchy type
- `hierarchyQualifiedNames(root)` — all qualified names in the hierarchy
- `hierarchyTypes(root)` — all CtType objects in the hierarchy

### DispatchCommitDetector (detect/DispatchCommitDetector.java)
Recognizes centralized dispatch **by the switch, not by the host's signature**. Scans every `CtSwitch`/`CtSwitchExpression` in the model, keeps those whose selector type is in H, and classifies what happens to the result.

- `find(root, model)` → `List<Producer(host, dispatch, CommitForm)>`. The `dispatch` field is the switch node itself, and the extractor walks **exactly that node**, never the host body. The field-mutation host ends with `return this.currentState;`, and walking the body would read that trailing return as a producer with no attributable source — a fictitious unresolved edge manufactured out of plumbing.
- **The commit requirement IS the precision guard**, not a check bolted on afterwards. A transition switch and an exhaustive fold are structurally indistinguishable *at the switch*; only the codomain separates them. `state = switch (state)` is accepted, `label = switch (state)` yielding `String` is not, and `return switch (state)` is accepted only when the enclosing method returns H.
- The declared TYPE of the assignment target decides, never the name — two machines in one model routinely both call their field `state`. Same reasoning as `isStateFieldWrite`.
- Selector form is free: parameter, local, `this.field`, or bare field read. Spoon resolves all four to the same `CtTypeReference`, so no per-form special-casing exists (or should be added).
- The sibling-vs-nested guard is **shared** with the carrier path via `CarrierTransitionDetector.nestsHierarchyValue`. Two recognizers with two notions of "recursive data type" would eventually disagree, and the disagreement would be a false positive.

### CarrierTransitionDetector (detect/CarrierTransitionDetector.java)
Recognizes the POLYMORPHIC_CARRIER encoding and owns the **sibling-vs-nested predicate**, the precision guard that keeps the widened recognizer from swallowing recursive data types.

- `shapeOf(method, hierarchy, rootQn)` → `PEER` / `NESTED` / `NONE`. Only *returned* (or yielded) expressions count, so the void mutation encoding (F2) stays invisible here and the two paths cannot both claim a hierarchy.
- `PEER` = the H-value is terminal: returned bare, a direct argument to a shallow carrier, or `this`. `NESTED` = the H-value takes another H-value as a construction argument (composition/tree building).
- `nestsHierarchyValue(value, hierarchy)` is the single-value form of the predicate, shared with `DispatchCommitDetector`. Only a **`CtConstructorCall`** counts as the nesting node, and that restriction is load-bearing: `case Idle s -> fromIdle(s, event)` — the ordinary way a large centralized switch is factored — puts an H value in the argument list of an H-returning *call*, which is delegation, not composition. Treating an invocation as a nesting node vetoes every machine written that way, including the ones the predicate exists to protect.
- `composesItself(root)` is a gate on **every** acceptance path, not just the carrier one — a record component of the hierarchy type synthesises an accessor that reads exactly like a per-state transition method, so a tree builder reached the distributed recognizer just as easily. An explicit `@Fsm` marker still wins over it.
- `qualifies(root)` additionally requires carrier methods on ≥2 permitted subtypes and ≥1 cross-state production.
- Discovery is by shape only — never by the name `on` or the type `Transition`. `consistentMethodName()` is a reported signal, never load-bearing.

### TransitionExtractor (extract/TransitionExtractor.java)
Most Spoon-version-sensitive file. Pattern-matching switch case handling is fully reflective (`patternType()` uses `tryMethod` chains). Switch arms are read via `caseValueExpressions()` which handles block+yield, return, and arrow-expression forms.

The carrier path (`extractPolymorphicCarrier` → `walkCarrier` → `handleCarrierValue`) runs whenever a per-state method returns a *non-hierarchy* carrier — not only when the other lists are empty, so one stray H-returning method (a helper, a record accessor) cannot silently sink every carrier edge. Methods returning the hierarchy type are skipped there; the distributed walker owns those, so the two paths never share a body. The F2 mutation fallback runs only if everything else found nothing.
- from-state = the declaring class (exact, no data-flow needed — never `<unknown>`).
- targets = hierarchy-typed *direct* arguments of the carrier call, ONE level deep. Anything else is UNRESOLVED.
- `carrierMode` disables F3 inter-procedural folding: the carrier encoding is strictly intra-procedural, so `Transition.to(helper())` is recorded unresolved rather than chased.
- `splitEventCondition` separates event tests from the data guard: `event instanceof SegmentArrival seg && seg.rst()` → event `SegmentArrival`, guard `seg.rst()`. A disjunction of pure event tests fires one edge per symbol.
- Σ expands enum members of a sealed event type into constants (`UserCall.CLOSE`), the granularity guards actually test. Labels are looked up in `eventSymbolByConstant`, built while Σ is enumerated, so an edge label and Σ cannot drift apart.
- Σ also expands an event member that **carries** an enum (`record Send(Signal signal)`) into the pair: `{Send.HEADERS, Send.PUSH_PROMISE, ...}`. One input is the pair, not the record. This is what makes `Send(HEADERS)` and a flat `SEND_HEADERS` constant yield the same |Σ| — see the two http2 fixtures. `soleEnumComponent` requires **exactly one** enum component; several is ambiguous, so Σ stays at the bare member rather than guessing which one discriminates. `composedSymbol()` is the single place the `Prefix.CONSTANT` spelling is defined, used by both Σ enumeration and edge labelling, and `componentEventNames` additionally refuses to emit a label Σ does not already contain — Σ is the authority.
- A `default` arm inside a component switch inherits the bare family label (`Send`), which reads as "that event with any component the labelled arms did not name". That is a partially-specified label and deliberately not a Σ symbol.
`extractCommitDispatch(producer)` is the widened centralized path: it enumerates Σ from the host's event parameter, then calls `walkSwitch` on the producer's dispatch node **only**. Hosts already walked by the signature-based recognizer are skipped (keyed on `declaringType#signature`, not a bare signature) so no body is walked twice.
- `caseEventNames(c)` returns **all** labels of an arm, so `case SEND_RST_STREAM, RECV_RST_STREAM ->` is two edges rather than one. It checks the **enum-constant case first, and that order is load-bearing**: a constant read carries the enum's own type, so asking `patternType` first answers "the event type" — labelling every arm of `switch (event)` with the enum's name and collapsing the whole alphabet to a single symbol.
- **Component dispatch** is the third switch kind, one level below event dispatch. When an event arm deconstructs its event (`case Send(Signal s) ->`) and its body switches on the binding, `componentEvent` carries the family prefix into that inner switch and `componentEventNames` composes `Send` + `HEADERS` → `Send.HEADERS`. Matching is by the binding's **name AND enum type together** — a record-pattern binding has no resolvable declaration in the Spoon model (`getDeclaration()` is `null`), so identity comparison is unavailable and the name alone would claim an unrelated switch reusing a one-letter variable.
- `dispatchedStates` records which states an arm (or a per-state method) selected. A state in that set with no outbound edge is genuinely absorbing; a state outside it has no edges only because none were recovered. `StateMachine.markTerminalStates` refuses to promote the latter — otherwise "terminal" would dress a recall gap as a result.
- **F12 — a guarded arm excludes the arms below it.** Arm order is part of the semantics: in `case Timeout t when g -> A;` followed by `case Timeout t -> B;`, the second runs only when `!g`, so the two can never both be enabled. Recording the fall-through arm as unguarded made `GuardAnalysis` report nondeterminism the source does not contain — 14 warnings on `examples/lcp_automation` for a deterministic automaton, and a wrong *claim* on `examples/dhcp-client-claude`, where `Requesting --AckReceived--> Bound` read as unconditional when it fires only if the duplicate-address check passed. `walkSwitch`/`walkCarrierSwitch` thread `priorExclusion(...)`, conjoining the negated guards of earlier arms **carrying the same label** — different labels are already disjoint, and negating those would bury every edge under redundant `!(event instanceof X)` clauses. The rule is confined to sibling arms of one switch; `examples/nondeterministic` overlaps by *semantics* (`coins >= 1` vs `coins > 0`, two separate assignments that nothing orders) and must still warn, which is the control that keeps this from becoming a blanket silencer.
  - Half of the same finding: Spoon 10.4.2 fills `CtCase.getGuard()` **only when the guard is a `CtBinaryOperator`**. For a bare invocation (`when r.acceptable()`), a unary (`when !r.catastrophic()`) or a parenthesised invocation it leaves the slot null and *prepends the guard expression into the arm body as a statement*. `leakedGuard(c)` recovers it, keyed on the **ARROW** case kind — exact, not a guess: JLS §14.11.1 gives an arrow arm a single expression, block or `throw`, so two statements there is always the leak. The kind check is load-bearing: a COLON arm holds a statement list, and `case X: helper(); return 1;` puts an ordinary boolean call exactly where a naive "first boolean statement is the guard" rule would swallow it.
- **F10 — an expression statement is not a produced successor.** Every call site of `walk` passes a *statement*, so its final `CtExpression` fallback only ever saw an expression statement — whose value Java discards (JLS §14.8). A discarded value cannot be a committed successor, so this is **exact, not heuristic**, and sits beside F9 on the compiler-checked side of the line. Unnarrowed it turned ordinary bookkeeping into transitions: `Objects.requireNonNull(event, ...)`, `log.debug(...)`, a counter bump — inflating the denominator and, where the expression was hierarchy-typed, risking a fabricated *resolved* edge. A statement that genuinely IS the commit is already claimed by its own branch (state-field assignment, or a recognised mutator call), so the rule is "ignore expression statements **that no commit form claims**", never "ignore expression statements" — `examples/plumbing-mutation` is the control that pins the difference. A hierarchy-type guard alone is NOT sufficient: `Objects.requireNonNull(currentState, ...)` is typed as H. The fallback is kept, narrowed to `isSwitchExpressionArm(...)`, because Spoon's arm modelling could change when the version is bumped.
  - Second half: Spoon wraps the arrow arm of a switch **statement** in a synthetic `CtYieldStatement`, though `yield` is illegal outside a switch expression. Walking it as a yield read a discarded value as a produced one, so `case X -> ctx.setState(new A())` never reached the F2 mutator branch and its edges were **lost** to `-> ?`. Any yield directly under a `CtSwitch` is synthetic and is unwrapped back to a statement; inside an arm's *block* a yield is always genuine, so this is a one-level rule.
- `throw` arms (including `default -> throw`) produce **no edge**: `CtThrow` is not a `CtExpression`, so `walk` skips it, and the thrown-exception helper is never mined for a successor. This is the D3 behaviour and it is pinned by test, not merely emergent.
- **F9 — a helper that cannot return normally is not a producer.** `default -> reject(s, e)`, where `reject` returns H but always throws, is the same rejection as `default -> throw reject(s, e)` with the `throw` moved one call deep. `neverReturnsNormally(callee)` suppresses the edge entirely, so the two spellings report the same relation. The rule is **exact, not heuristic**, and that is the only reason it is permitted to suppress: JLS §8.4.7 forbids a non-void method whose body can complete normally, and the callee's return type is already known to be in H, so a body with no `return` anywhere provably cannot yield a successor. It sits on the compiler-checked side of the thesis's epistemic line, alongside state enumeration — not on the approximate data-flow side. Suppressed arms are counted and reported as a diagnostic, so they are reclassified rather than silently dropped. The check must run **before** the `collectReturns(...).isEmpty()` test: both fire on an empty summary but they mean opposite things — "there is no target" versus "there is a target we could not see". The scan is whole-body and unfiltered, so a `return` inside a lambda counts as the method's own; that is the conservative direction (decline to suppress).
- **F11 — F9 may only judge a body it actually read.** For a method outside the source set Spoon supplies a reflective *shadow* declaration: a real signature with an empty `{ }` body. It has no `return` because it was never parsed, so the emptiness carries no information — yet `neverReturnsNormally` read it as proof and deleted the edge. That silently swallowed every library call returning H (`Objects.requireNonNull(state, ...)`, `Optional.orElse(new Closed())`, `map.getOrDefault(k, new Idle())`): a false drop with **no unresolved marker**, the one outcome the invariant forbids. `isShadow(...)` checks `CtShadowable.isShadow()` and an invalid source position, and answers "shadow" when unreadable — declining to apply F9 is the direction that cannot drop a transition.
- `otherwisePath` marks edges reached via an `else`, a `default` arm, or a statement following a conditional. Such an edge with **no event** is the state's default transition and is flagged `Transition.isOtherwise()` — rendered as `otherwise` in DOT and as a commented eventless `<transition>` in SCXML. It distinguishes "fires when nothing else does" from "no event was recovered". Currently set only by the carrier walk; `emit()` handles the flag generically, so extending it to the centralized walk is a one-line change (it would alter existing reference output, hence not done).

### TransitionResolver (extract/TransitionResolver.java)
The **successor sub-procedure** — one uniform mapping from a produced expression to permitted subtype(s), run identically under either dispatch encoding. Takes `hierarchyQualifiedNames` AND `rootQualifiedName`.

Resolution order in `fromVariable`, and *the order is load-bearing*:
1. **Enum constant** of a permitted enum → the constant (not the enum type).
2. **Initializer** → resolved recursively, cycle-guarded. Must come before the declared-type rules: `static final Signal INSTANCE = new Idle();` is declared as the abstract root, and rule 4 would otherwise call it a self-loop.
3. **Concrete declared type** → that state.
4. **Root-typed selector** → self-loop. Restricted to parameters and pattern bindings; a root-typed *local or field* is a value with its own identity, so it goes unresolved rather than fabricating a self-loop.

Each resolved candidate carries a `SuccessorForm` (CONSTRUCTION / SINGLETON_FIELD / ENUM_CONSTANT / SELF / LOCAL_VARIABLE / CAST), aggregated onto the machine.

### The three axes — keep them apart
Reporting is stratified along three independent axes. Collapsing any two of them reports the same recognizer capability twice and makes a recall gap unattributable.

| axis | question | values |
|---|---|---|
| `StateMachine.Encoding` | where does dispatch **live**? | `POLYMORPHIC`, `CENTRALIZED_DISPATCH` (+ `MIXED`) |
| `SuccessorForm` | how is the successor **spelled**? | CONSTRUCTION / SINGLETON_FIELD / ENUM_CONSTANT / SELF / LOCAL_VARIABLE / CAST |
| `CommitForm` | how is the successor **installed**? | VALUE_RETURN / FIELD_MUTATION / LOCAL_ACCUMULATOR / POLY_CARRIER |

A carrier-returning per-state method is `POLYMORPHIC` dispatch with a `POLY_CARRIER` commit — not an encoding of its own. `return switch (s)` and `this.f = switch (this.f)` are the same encoding with different commits.

### Composite states (both serializers)
A composite state is a permitted subtype that is itself sealed, **or an enum** (its constants become children). `topLevelStates()` is exactly the `permits` clause; `allStates()` is the flattened count the summary prints — so `Signal` reports 6 states for a 4-member `permits` clause, and that is correct, not a miscount.

Two things composites break if handled naively:
- **DOT**: `cluster_X` is not a node. Naming it in an edge makes Graphviz invent a second node with the same label, so the diagram shows the state twice. Edges touching a composite attach to an invisible `__anchor_X` inside the cluster and clip with `lhead`/`ltail` (needs `compound=true`). Clipping is suppressed when both ends are inside the same cluster — Graphviz warns and ignores it there.
- **SCXML**: a self-transition on a composite with a `target` *exits and re-enters*, landing on the initial child. A `stay(this)` self-loop must be emitted **without a target** (internal transition) or a machine in PEAK silently jumps to RAMP.

Verify renders with `dot -Tsvg out/X.dot -o /dev/null` — it must print no warnings.

### ScxmlSerializer (serialize/ScxmlSerializer.java)
Transitions are emitted inside the `<state>` matching their source id, so anything sourced at a **pseudo-state** needs `emitPseudoStates`:
- machine entry (`<initial>`) becomes a real `<state id="_initial">`, because the document's `initial` attribute must resolve to a declared element;
- an undetermined source (`<unknown>`/`<entry>`) becomes a trailing comment — it is a gap, not a state, and inventing a `<state>` for it would put a fiction in the model.

`scxmlId()` maps `<initial>` → `_initial`: the raw form is not a legal XML name. Every emitted `initial`/`target` must resolve to a declared `<state>` — losing an edge here would break the "unresolved transitions are never dropped" invariant silently, since DOT would still show it.

A terminal **leaf** is emitted as `<final>`, SCXML's spelling of an absorbing state; DOT gives it `peripheries=2`. A composite is never emitted as `<final>` even when nothing leaves it — `<final>` may hold no children, so doing so would delete part of the machine from the document.

### Analyzer (Analyzer.java)
Initial-state heuristics (priority order):
1. A field typed as the hierarchy initialized with `new Concrete()` (e.g. `private TrafficLight current = new Red()`)
2. The unique state with no resolved incoming edge but ≥1 outgoing edge
3. A hierarchy-typed **local** initialized with `new Concrete()`, and only when every such local in the model agrees. Deliberately last and deliberately unanimous: a local is usually a *driver's* starting point, not the machine's, so it is consulted only after both structural rules abstain and it abstains itself the moment two locals disagree. It exists because a dense automaton defeats rule 2 exactly when it is most faithful — in RFC 1661's LCP every state including `Initial` has incoming edges. When it fires, the result is reported as **weaker evidence** in a diagnostic naming what it rested on; a machine with no initial state reports a gap, whereas a wrong one is a fabricated claim.

## Coding conventions

- Java 21 features are encouraged: records, sealed types, pattern matching, `var`, text blocks.
- No Lombok, no Spring, no frameworks — this is a command-line analysis tool.
- Every Spoon API call that might differ across versions goes in `SpoonCompat` or is guarded with try/catch + reflection in `TransitionExtractor`.
- Unresolved transitions are always RECORDED, never silently dropped. This is non-negotiable.
- Diagnostics (INFO/WARN) go through `ExtractionResult.info()/warn()`, not stdout.
- The `Main` class handles stdout; analysis code is silent.
- Neutral method names (next, transition, step, advance, tick, etc.) produce `null` event labels, not the method name.

## v1 scope lines (what is deliberately left out)

These are acknowledged limitations, not bugs. Do not "fix" them without explicit instruction:

- Inter-procedural targets beyond the k = 2 budget (`return helper()`) → unresolved
- Centralized-style event labelling from method names → future work
- Multi-file scattered hierarchies → Spoon handles if given all source dirs
- Carrier arguments are descended ONE level only; a successor computed by a helper stays unresolved (deliberate, see `carrierMode`)
- **Analysis stays intra-procedural at the dispatch.** A commit the analysis cannot see inside the host method is a commit it must not claim: `DispatchCommitDetector.commitFormOf` consults only the switch's immediate syntactic context and never follows the value through a helper or into another object's field.
- **The encoding axis has exactly TWO positions** — `POLYMORPHIC` (per-state dispatch) and `CENTRALIZED_DISPATCH` (one switch over H) — plus `MIXED` for "both, or undetermined". Do not add a third. Where the switch is *hosted*, how the successor is *spelled*, and how it is *committed* are sub-dimensions: a new spelling extends `SuccessorForm`, a new installation mechanism extends `CommitForm`, and neither ever extends `Encoding`.

**Analysis must be scope-isolated.** Two machines in one model must produce exactly the edges they produce alone — `ExtractionIntegrationTest.mutationMachinesDoNotAbsorbEachOthersAssignments` pins this. The F2 mutation path is the fragile one (a void mutator gives it nothing typed to anchor on), so `isStateFieldWrite`/`isMutatorCall` use the field/parameter **type** to decide and the name only to prefilter. Never relax that back to a name-only match: two machines that both call their field `state` then swallow each other's assignments.

## What to work on next (likely tasks)

- Expand the corpus: harvest GitHub for real sealed FSM hierarchies
- Add more example fixtures for edge cases (enum-based states, nested composites, multi-method distributed)
- Narrow the root-typed-parameter self-loop rule in `TransitionResolver` to the *actual dispatch selector*. Today `isSelectorBinding` accepts **any** parameter, so a second root-typed parameter (`H step(H current, H fallback, Event e)` returning `fallback`) resolves as a proven self-loop instead of UNRESOLVED — a fabricated resolved edge, which is the one failure mode the soundness invariant forbids. Needs a fixture with two root-typed parameters.
- Reconsider `MIXED` for `Portal` / `Vend`. Both are `@Fsm`-marked mutation machines that predate the commit axis; with `CommitForm` in place, Portal is really POLYMORPHIC + FIELD_MUTATION and Vend CENTRALIZED_DISPATCH + FIELD_MUTATION. Two machines and 8 edges currently sit in a bucket labelled "undetermined" that no longer needs to be.
- Extend `otherwisePath` to the centralized walk (`emit()` already handles the flag generically; it would alter existing reference output)
- Write more targeted unit tests for `TransitionResolver` expression shapes
- Thesis writing: validation chapter, corpus results tables — the `CommitForm` axis is what the stratified recall table is keyed on
