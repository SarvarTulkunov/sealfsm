# CLAUDE.md — SealFSM project context for Claude Code

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

# CLI
java -jar target/sealfsm.jar --src examples/traffic --out out/traffic
java -jar target/sealfsm.jar --src examples --out out/all --format both

# Debug tools
java -cp target/classes io.sealfsm.DebugHarness examples/traffic
java -cp target/classes io.sealfsm.DebugAst examples/door --returns

# Render diagram
dot -Tpng out/TrafficLight.dot -o TrafficLight.png
```

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
  → StateMachineClassifier (FSM or sum type? distributed/centralized/carrier?)
      ↳ CarrierTransitionDetector (carrier encoding + sibling-vs-nested guard)
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
│   ├── CarrierTransitionDetector.java # carrier encoding + sibling-vs-nested guard
│   ├── SealedHierarchyDetector.java   # finds sealed roots
│   ├── SpoonCompat.java               # Spoon version isolation (reflective)
│   └── StateMachineClassifier.java    # FSM vs sum type gate + shared helpers
├── extract/
│   ├── StateExtractor.java            # permits → State (recursive composites)
│   ├── TransitionExtractor.java       # both encodings; switch-arm handling
│   └── TransitionResolver.java        # expression → target state(s) data-flow
├── model/
│   ├── State.java                     # state IR (id, qualifiedName, composite, initial, children)
│   ├── Transition.java                # transition IR (from, to, event, guard, resolved, note)
│   ├── StateMachine.java              # aggregate (states, transitions, encoding, alphabet)
│   └── ExtractionResult.java          # machines + Diagnostic records
└── serialize/
    ├── DotSerializer.java             # Graphviz DOT output
    └── ScxmlSerializer.java           # W3C SCXML output
```

## Examples (also regression fixtures)

- `examples/traffic/` — DISTRIBUTED: sealed `TrafficLight permits Red, Green, Yellow` with `next()` methods. 3 states, 3 resolved transitions, initial=Red.
- `examples/door/` — CENTRALIZED: sealed `Door permits Open, Closed, Locked` with `DoorMachine.transition(Door, Event)` pattern-matching switch. Exercises guarded ternary, `return current` self-loop, type-pattern from-states. 3 states, 5 resolved transitions, initial=Closed.
- `examples/tcp/` — DISTRIBUTED dispatch, carrier successor: sealed `TcpState` permits the 11 RFC 9293 states, each overriding `Transition on(Event)` where the successor is an *argument* to `Transition.to(...)` and `Transition.ignore(this)` is a self-loop. 11 states, 44 resolved transitions, initial=Closed. `tcp.Event` is Σ and must stay unrecognized.
- `examples/valueforms/` — SUCCESSOR FORMS: sealed `Signal permits Idle, Armed, Firing, Phase` holds the encoding fixed and varies only how each successor is *written* — construction, a concrete-typed singleton, a **root-typed** singleton (resolvable only via its initializer), enum constants (`Phase` is a permitted enum), a local, `this`, and a helper call that must stay UNRESOLVED. 6 states (the enum contributes RAMP/PEAK), 9/10 resolved.
- `examples/shape/` — NEGATIVE CONTROL: sealed `Shape permits Circle, Square, Triangle` with `area()` returning `double`. Must be REJECTED — no method returns the hierarchy type.
- `examples/treebuilder/` — NEGATIVE CONTROL for the carrier recognizer: sealed `Expr permits Lit, Neg, Add`, each overriding `Rewrite simplify()` — structurally identical to `examples/tcp` (consistently-named method, non-hierarchy carrier wrapping H values). Must be REJECTED by the sibling-vs-nested guard, because `new Add(l.result(), r.result())` nests H inside a bigger H.

Reference output in `sample-output/` — diff after building to verify. Note `Door.dot`/`Door.scxml` there predate the `examples.door` package move, so their guard text is unqualified; edges and structure still match.

## Tests

- `serialize/DotSerializerTest` — pure IR/serializer (no Spoon)
- `serialize/ScxmlSerializerTest` — SCXML well-formedness + nesting (no Spoon)
- `detect/CarrierTransitionDetectorTest` — carrier recognition, the sibling-vs-nested guard (accept tcp / reject treebuilder), self-loops
- `extract/TransitionResolverTest` — the successor sub-procedure, expression shape by expression shape
- `ExtractionIntegrationTest` — full Spoon extraction over every example

## Key implementation details

### SpoonCompat (detect/SpoonCompat.java)
All version-sensitive Spoon calls live here. `isSealed()` checks `ModifierKind.SEALED` with try/catch fallback. `permittedTypes()` uses reflection for `getPermittedTypes()` with a fallback that scans the model for direct subtypes (handles implicit permits).

### StateMachineClassifier (detect/StateMachineClassifier.java)
Contains shared static helpers reused by the extractor:
- `findDistributedTransitionMethods(root)` — methods ON the hierarchy returning hierarchy type
- `findCentralizedTransitionMethods(root, model)` — methods OUTSIDE hierarchy taking+returning hierarchy type
- `hierarchyQualifiedNames(root)` — all qualified names in the hierarchy
- `hierarchyTypes(root)` — all CtType objects in the hierarchy

### CarrierTransitionDetector (detect/CarrierTransitionDetector.java)
Recognizes the POLYMORPHIC_CARRIER encoding and owns the **sibling-vs-nested predicate**, the precision guard that keeps the widened recognizer from swallowing recursive data types.

- `shapeOf(method, hierarchy, rootQn)` → `PEER` / `NESTED` / `NONE`. Only *returned* (or yielded) expressions count, so the void mutation encoding (F2) stays invisible here and the two paths cannot both claim a hierarchy.
- `PEER` = the H-value is terminal: returned bare, a direct argument to a shallow carrier, or `this`. `NESTED` = the H-value takes another H-value as a construction argument (composition/tree building).
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
- `otherwisePath` marks edges reached via an `else`, a `default` arm, or a statement following a conditional. Such an edge with **no event** is the state's default transition and is flagged `Transition.isOtherwise()` — rendered as `otherwise` in DOT and as a commented eventless `<transition>` in SCXML. It distinguishes "fires when nothing else does" from "no event was recovered". Currently set only by the carrier walk; `emit()` handles the flag generically, so extending it to the centralized walk is a one-line change (it would alter existing reference output, hence not done).

### TransitionResolver (extract/TransitionResolver.java)
The **successor sub-procedure** — one uniform mapping from a produced expression to permitted subtype(s), run identically under either dispatch encoding. Takes `hierarchyQualifiedNames` AND `rootQualifiedName`.

Resolution order in `fromVariable`, and *the order is load-bearing*:
1. **Enum constant** of a permitted enum → the constant (not the enum type).
2. **Initializer** → resolved recursively, cycle-guarded. Must come before the declared-type rules: `static final Signal INSTANCE = new Idle();` is declared as the abstract root, and rule 4 would otherwise call it a self-loop.
3. **Concrete declared type** → that state.
4. **Root-typed selector** → self-loop. Restricted to parameters and pattern bindings; a root-typed *local or field* is a value with its own identity, so it goes unresolved rather than fabricating a self-loop.

Each resolved candidate carries a `SuccessorForm` (CONSTRUCTION / SINGLETON_FIELD / ENUM_CONSTANT / SELF / LOCAL_VARIABLE / CAST), aggregated onto the machine. This is the axis **orthogonal to `Encoding`**: encoding = where dispatch lives (two positions), form = how the successor is spelled. A carrier-returning per-state method is DISTRIBUTED dispatch, not an encoding of its own.

### Analyzer (Analyzer.java)
Initial-state heuristics (priority order):
1. A field typed as the hierarchy initialized with `new Concrete()` (e.g. `private TrafficLight current = new Red()`)
2. The unique state with no resolved incoming edge but ≥1 outgoing edge

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

- Inter-procedural targets (`return helper()`) → unresolved
- Reassigned locals → unresolved (only declaration-site initializers resolve)
- Centralized-style event labelling → future work
- Independent sealed-Event alphabet enumeration → future work
- Multi-file scattered hierarchies → Spoon handles if given all source dirs
- Carrier arguments are descended ONE level only; a successor computed by a helper stays unresolved (deliberate, see `carrierMode`)
- **The encoding axis has exactly TWO positions** — DISTRIBUTED (polymorphic per-state dispatch) and CENTRALIZED (a single switch) — plus MIXED for "both, or undetermined". Do not add a third. How the successor is *written* is the separate `SuccessorForm` axis; a new spelling extends that enum, never `Encoding`.
- `Encoding.DISTRIBUTED` is kept as the name for polymorphic per-state dispatch rather than renaming to `POLYMORPHIC_DISPATCH`, to keep committed reference output and thesis prose stable. The two terms mean the same position on the axis.
- `findMutationMethods` (F2) matches state fields and mutators by *name* across the whole model, so analysing `examples/` as one source dir bleeds `Vend`'s guards into `Portal`. Per-example runs are unaffected; pre-existing.

## What to work on next (likely tasks)

- Expand the corpus: harvest GitHub for real sealed FSM hierarchies
- Add more example fixtures for edge cases (enum-based states, nested composites, multi-method distributed)
- Improve event labelling for centralized encoding
- Add sealed-Event alphabet enumeration (e.g. door example has `sealed Event permits Push, Lock, Unlock`)
- Write more targeted unit tests for `TransitionResolver` expression shapes
- Thesis writing: validation chapter, corpus results tables
