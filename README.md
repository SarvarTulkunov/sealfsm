# SealFSM

**Automated extraction of finite state machines from sealed class hierarchies in modern Java — a static-analysis approach.**

SealFSM reads Java source, finds `sealed` type hierarchies, decides which of them encode a state machine in a supported pattern, and emits each recovered FSM as **DOT** (for visualisation) and **SCXML** (for model-based testing tools). It can also write the whole run as **JSON** (`--json`), for scoring against labelled ground truth (`evaluation/PROTOCOL.md`).

## The core idea, and the distinctions that matter

A `sealed` interface lists its implementations in a compiler-checked `permits` clause. That makes the set of subtypes **closed and exhaustive**. SealFSM makes claims of *different strength*, and keeping them separate is the whole point (see `SCOPE.md`):

1. **Classification is conditional on evidence.** Locating a sealed declaration is not deciding it is a machine. A hierarchy is reported as a machine only when the code shows a supported dispatch over its states **and** shows the chosen successor becoming the current state (for a returned successor, that means a caller stores it back; thesis Decision 4). A hierarchy with plausible dispatch evidence and no established relation is a provisional **candidate**, which is not a detected FSM. Anything else is rejected. No claim is made that every real sealed FSM is recognised.
2. **State enumeration is exact over the type structure, for machines.** A machine's states come from the `permits` clauses and from the constants of permitted enums, so they are complete by construction. Counts name their level (thesis Decision 2): **direct branches** (the root's own `permits`), **atomic states** (the leaves of the expansion), and **grouping nodes** (sealed members and enums between them). When the *event* type is a sealed hierarchy or an enum, the input alphabet Σ is recovered the same way (F4).
3. **Transition extraction is approximate.** The transition relation δ is recovered by intra-procedural data-flow analysis, with a bounded inter-procedural fold. It is reported with precision and recall, never presented as complete.

Anything the tool cannot resolve on the transition side is **recorded as an explicit unresolved edge** (a dashed red arrow in DOT, an XML comment in SCXML) rather than silently dropped, so gaps depress recall visibly instead of masquerading as a complete model.

## Pipeline

```
 Java source
      │
      ▼
 Spoon parser (builds CtModel AST)
      │
      ▼
 ┌─────────────────────────────────────────────────────┐
 │  Analyzer engine                                    │
 │                                                     │
 │  SealedHierarchyDetector                            │
 │    └─ finds sealed root types (via SpoonCompat)     │
 │              │                                      │
 │              ▼                                      │
 │  StateMachineClassifier                             │
 │    ├─ no dispatch ─────────────────▶ rejected       │
 │    │                                 (e.g. Shape)   │
 │    ├─ dispatch, no commit ─────────▶ CANDIDATE      │
 │    │                                 (provisional)  │
 │    ├─ returned value never stored ─▶ CANDIDATE      │
 │    │  back (Installation, F36)       (provisional)  │
 │    ├─ returned value only used ────▶ rejected as a  │
 │    │  as data                        conversion     │
 │              │                                      │
 │              ▼                                      │
 │     ┌────────┴────────┐                             │
 │     ▼                 ▼                             │
 │  StateExtractor    TransitionExtractor              │
 │  (exact, from      (approximate, intra-             │
 │   permits)          procedural data-flow            │
 │     │               + TransitionResolver)           │
 │     └────────┬────────┘                             │
 │              ▼                                      │
 │  StateMachine IR                                    │
 │  (State, Transition, ExtractionResult)              │
 │              │                                      │
 │  Initial-state detection (heuristic)                │
 └──────────────┼──────────────────────────────────────┘
                │
       ┌────────┴────────┐
       ▼                 ▼
  DotSerializer    ScxmlSerializer
       │                 │
       ▼                 ▼
    .dot file         .scxml file
```

### Component reference

| Package | Class | Role |
|---------|-------|------|
| `detect` | `SealedHierarchyDetector` | Finds sealed root types, filters out nested sealed (those become composite states) |
| `detect` | `StateMachineClassifier` | Rejects plain sum types and established conversions; detects encoding (distributed/centralized/mixed); exposes shared method-discovery helpers |
| `detect.dispatch` | `Installation` | F36 / Decision 4: follows a returned successor to its callers; installed, uncalled, not followed, or used as data |
| `detect.dispatch` | `RunToCompletion` | F34's self-re-entering driver, one predicate shared by the extractor and `Installation` |
| `detect` | `SpoonCompat` | Isolates version-sensitive Spoon API calls (`isSealed`, `isNonSealed`, `permittedTypes`) with reflective fallbacks |
| `extract` | `StateExtractor` | `permits` → `State` nodes; recursive for nested sealed and permitted enums; reports open (`non-sealed`) branches and types reachable under two branches |
| `extract` | `TransitionExtractor` | Orchestrates both encodings: distributed (per-state methods) and centralized (switch dispatch) |
| `extract` | `TransitionResolver` | Intra-procedural data-flow: resolves return expressions to target states (`new X()`, `this`, ternary, variable reads) |
| `model` | `State` | State node; `id`, `qualifiedName`, `origin` (type or enum constant), `isAtomic`/`isGrouping`, `isOpenBranch`, `initial`, `children` |
| `model` | `Transition` | Transition IR; first-class `resolved` flag; `from`, `to`, `event`, `guard`, `note` |
| `model` | `StateMachine` | Aggregate: `directBranches()`, `atomicStates()`, `compositeNodes()`, transitions, encoding, alphabet, initial state, commit evidence |
| `model` | `Candidate` | A PROVISIONAL Tier 3 classification: provisional members, the dispatch sites, and the missing evidence (`basis`) |
| `model` | `ExtractionResult` | Machines, candidates, every examined root's `outcome`, and `Diagnostic` records (INFO/WARN) |
| `serialize` | `DotSerializer` | Graphviz DOT; composite → `subgraph cluster_*`; unresolved → dashed red edge |
| `serialize` | `ScxmlSerializer` | W3C SCXML; composite → nested `<state>`; unresolved → XML comment |
| `serialize` | `JsonResultSerializer` | The whole run for scoring (`--json`): outcomes, machines at three state levels, provisional candidates |
| `annotation` | `@Fsm` | Optional marker annotation to force classification; matches by simple name |
| root | `Analyzer` | Orchestrates the full pipeline + initial-state heuristics |
| root | `Main` | CLI entry point (`--src`, `--out`, `--format`, `--classpath`, `--quiet`, `--explain`, `--json`) |
| root | `DebugHarness` | Runs each pipeline stage individually with verbose printed output; breakpoint-friendly |
| root | `DebugAst` | Dumps the Spoon AST after parsing; `--returns` shows expression types, `--full` shows reconstructed source |

### Two transition encodings

- **Distributed** (classic State pattern): each state class has a transition method returning the hierarchy type. Example: `examples/traffic` — `Red.next()` returns `new Green()`.
- **Centralized** (single transition function): a pattern-matching `switch` over the current state. Example: `examples/door` — `DoorMachine.transition(Door current, Event event)`.
- **Mutation / GoF State** (finding F2): transitions happen by *mutating* a state field (`this.state = new Locked()`) or calling a state mutator (`ctx.setState(new Locked())`) rather than returning the next state. Recovered as a **fallback** when a hierarchy exposes no return-based transition method, so functional hierarchies are never re-mined. Example: `examples/gofcontext`. Because such a hierarchy is invisible to the structural classifier, it opts in with the `@Fsm` marker (structural detection of the GoF family is a follow-up).

## Covered transition logic cases

Transition extraction runs in two layers. The **control-flow walker** (`TransitionExtractor`) descends a method body and decides *which* expressions produce a next state and *under what guard* control reaches them. Each such expression is then handed to the **expression resolver** (`TransitionResolver`), which decides *which concrete state* it yields. Both layers are documented below; anything either layer cannot handle becomes a **recorded unresolved edge**, never a silent drop.

### Layer 1 — control-flow walker (`TransitionExtractor`)

A single recursive, guard-carrying traversal handles both encodings. It descends the control-flow structure rather than flat-scanning for `return`/`yield`, so guards and nested producers are recovered precisely.

| Control-flow shape | Handling |
|--------------------|----------|
| **Distributed method** (`Red.next()` on a state class) | `from` = declaring state class; `event` = method name only when the hierarchy spells more than one such name (one name names the function, F22) |
| **Centralized method** (`transition(State, Event)`) | `from` = matched type-pattern per switch arm; `event` = `null` (v1 scope line) |
| **Type-pattern switch arm** (`case Locked l -> …`) | Arm's matched type becomes the `from`-state |
| **Switch-over-event arm** (`case Lock l -> …` inside a `switch (event)`) | Arm's matched event becomes the transition's event label; the alphabet Σ is enumerated from the sealed/enum event type (finding F4) |
| **Guarded pattern** (`case Locked l when …`) | `when` clause becomes the transition guard |
| **Enclosing `if`** | Condition becomes the guard; its negation guards the `else`/fall-through branch |
| **Value produced inside an `if`** (`if (e instanceof Coin) yield new Unlocked();`) | Recovered with the `if` condition as guard — not dropped |
| **Guardless fall-through** (`if (cond) yield A; yield B;`) | `B` is guarded by `!(cond)` — mutually-exclusive guards threaded across sibling statements |
| **Reassigned local** (`Gate next = current; if (e) next = new Open(); yield next;`) | Flow-sensitive **reaching-definitions** over the declaring block: each reaching value is resolved under its own path guard, so the guarded target and the else self-loop are both recovered — never a blind self-loop (finding F1) |
| **Nested switch in an arm** (switch-over-event within switch-over-state) | Descended into, inheriting the arm's `from`-state |
| **Arrow-arm expression body** (`case X -> new A();`) | Resolved directly as a produced value |
| **State-field write** (`this.state = new Locked();`) | Mutation-encoding transition site (F2); the RHS is the next-state expression, resolved like a return value |
| **State mutator call** (`ctx.setState(new Locked());`) | Mutation-encoding transition site (F2); the hierarchy-typed argument is the next state; from-state = declaring state class for GoF callbacks |
| **`return` / `yield` / arrow forms** | All normalised to the same value-production path |
| **Unrecognised switch case / unknown source** | Recorded as an unresolved (or undetermined-origin) edge **plus** a diagnostic |
| **Loops, try/catch** | Intentionally not descended for value production; a variable written inside one makes the reaching-definitions pass fall back to an unresolved edge (v1 scope line) |

### Layer 2 — expression resolver (`TransitionResolver`)

Given one produced expression, resolve the concrete target state(s).

| Expression shape | Result | Example |
|-----------------|--------|---------|
| `new Green()` | Resolved to `Green` | Direct construction |
| `(Locked) current` | Resolved to `Locked` | Explicit cast to a concrete state pins the target (overrides the root-typed static type) |
| `return this` | Self-loop to `from`-state | State pattern identity |
| `return current` (root-typed variable) | Self-loop to `from`-state | Centralized `return current;` |
| `cond ? a : b` | Two guarded transitions | `(event instanceof Lock) ? new Locked() : new Open()` |
| Variable typed as concrete state | Resolved to that state | `Locked l = ...; return l;` |
| `static final X INSTANCE = new X()` | Resolved via initializer | Singleton states |
| `return helper()` / `return factory.make()` | Resolved via bounded inter-procedural summary | The callee's return values are folded into the call site (finding F3), depth-limited to k = 2 with cycle detection; a library/abstract callee or an out-of-budget target stays **unresolved** |
| Reassigned local (`next = …; return next;`) | Resolved per reaching definition | Handled upstream by the walker's reaching-definitions pass (Layer 1); only a write inside a loop/try leaves it unresolved |
| Anything else | **Unresolved** (recorded) | Raw text preserved in `note` |

## Build

Requirements: **JDK 21+** and **Maven**.

```bash
mvn package
```

The `spoon.version` property in `pom.xml` is pinned to `10.4.2` (known to support sealed types and Java 21 pattern-matching switches). Newer Spoon is fine — bump that single property. Version-sensitive code is reflective and degrades to an unresolved edge, not a compilation failure.

## Usage

### CLI

```bash
java -jar target/sealfsm.jar --src <path> [--src <path> ...] [options]

  --src <path>      Source file or directory to analyse (repeatable, required)
  --out <dir>       Output directory (default: ./out)
  --format <fmt>    dot | scxml | both   (default: both)
  --classpath <cp>  Classpath for types not in --src (analysis stays noClasspath)
  --quiet           Suppress the diagnostics listing
  --explain         Print each rejected root's predicates, and each machine's bindings
  --json            Also write <out>/sealfsm-result.json for scoring (evaluation/PROTOCOL.md)
  -h, --help        Show help
```

### Quick start with bundled examples

The one-command path — build, run every example into its own `out/<name>/`,
render every `.dot` to `.png` (requires Graphviz's `dot` on `PATH`), and save
everything that was printed to `out/results.txt`:

```powershell
scripts\build-run-render.ps1
```

Useful flags: `-Example traffic` (just one example), `-SkipBuild` (reuse the
existing jar), `-SkipTests` (faster build), `-SkipRender` (skip the PNG pass),
`-Format dot|scxml|both`, `-IncludeDiagnostics` (also capture each example's
`[INFO]`/`[WARN]` lines), `-ResultsFile <path>` (write the log somewhere else).
Run `Get-Help scripts\build-run-render.ps1 -Full`
for the complete list.

Doing it by hand, for reference:

```bash
# Build
mvn package

# Run each example into its own output directory — NOT one combined
# `--src examples --out out`. Two examples are free to declare a type
# with the same simple name (e.g. every DHCP fixture defines a
# `DhcpState`), and Main.java names output files after the machine, not
# the source directory: a flat --out silently lets the second run's
# DhcpState.dot overwrite the first's. (A same-named Java *package*
# across two example directories is worse — Spoon refuses to build the
# model at all. Every example directory uses its own package for this
# reason; keep that invariant when adding a new one.)
java -jar target/sealfsm.jar --src examples/traffic --out out/traffic
java -jar target/sealfsm.jar --src examples/door --out out/door
java -jar target/sealfsm.jar --src examples/shape --out out/shape

# Render diagrams
dot -Tpng out/traffic/TrafficLight.dot -o TrafficLight.png
dot -Tpng out/door/Door.dot -o Door.png

# Verify against reference output (--strip-trailing-cr: sample-output/ is
# checked in with CRLF: Files.writeString emits bare LF)
diff --strip-trailing-cr out/traffic/TrafficLight.scxml sample-output/TrafficLight.scxml
diff --strip-trailing-cr out/door/Door.dot sample-output/Door.dot
```

### Debugging and exploration

Two debug tools are included for understanding the pipeline:

**DebugHarness** — runs each pipeline stage individually with printed output at every step. Set breakpoints anywhere in IDEA.

```bash
# From IDEA: Run DebugHarness with program argument
mvn compile
# Then right-click DebugHarness → Run with argument: examples/traffic

# Or from terminal after mvn compile:
java -cp target/classes io.sealfsm.DebugHarness examples/traffic
java -cp target/classes io.sealfsm.DebugHarness examples/door
```

**DebugAst** — dumps the Spoon AST after parsing so you can see exactly what the analysis works with.

```bash
java -cp target/classes io.sealfsm.DebugAst examples/door              # basic dump
java -cp target/classes io.sealfsm.DebugAst examples/door --returns     # show return expressions + AST node types
java -cp target/classes io.sealfsm.DebugAst examples/traffic --full     # full Spoon-reconstructed source
```

The `--returns` flag is especially useful: it shows the exact `CtExpression` subclass for each return value, so you can predict how `TransitionResolver` will handle it (`CtConstructorCall` → resolved, `CtConditional` → guarded split, `CtInvocation` → unresolved).

**IDEA debugger tip:** set a breakpoint inside `TransitionResolver.resolve()` and run `DebugHarness` against `examples/door`. When it stops on `(event instanceof Lock) ? new Locked() : new Open()`, expand `expr` in the Variables pane — you'll see the full Spoon AST node and watch the resolver recursively split it.

## Bundled examples

| Example | Encoding | Expected result |
|---------|----------|-----------------|
| `examples/traffic` | distributed | 3 states; Red→Green→Yellow→Red; initial **Red**; 0 unresolved |
| `examples/door` | centralized | 3 states; guarded + self-loop transitions; initial **Closed** |
| `examples/turnstile` | centralized | 2 states; imperative `if`-guarded arms with fall-through self-loops |
| `examples/localvar` | centralized | 2 states; next state via a **reassigned root-typed local** — guarded edge + else self-loop, no blind self-loop (finding F1) |
| `examples/gofcontext` | mutation / GoF | 3 states; transitions via `ctx.setState(...)` field mutation; recovers the **same edge set** as `examples/door` (finding F2) |
| `examples/factory` | centralized | 3 states; arms **delegate to helper/factory methods**; resolved via bounded inter-procedural summaries, out-of-budget target stays unresolved (finding F3) |
| `examples/eventalphabet` | centralized | 3 states; nested `switch (event)` — recovers Σ = {Play, Pause, Stop, Skip} from the sealed event type and labels each edge with its event (finding F4) |
| `examples/shape` | — (negative) | **rejected** as a plain sum type; nothing discriminates it, so not even a candidate |
| `examples/voidcommit` | centralized | 4 states; the arms are bare calls and the commit is an H-typed field write **inside the callee** — recovered by the k = 1 commit-existence probe, 0/4 resolved by design (Tier 2, finding F26) |
| `examples/voidfold` | — (negative) | **rejected**; indistinguishable from `voidcommit` at the call site, differing only in that its callee writes a `String`. Reported as a **provisional candidate** listing its 4 would-be states |
| `examples/converters` | Decision 4 controls | one converter shape, varied only in what happens to its result: stored back (`Tint`, a machine), used as data (`Shade`, and the per-state `Currency`: **rejected as conversions**), never called (`Hue`, a provisional candidate) |
| `examples/typedhandler` | Decision 4 acceptance examples | `Length` (two uncalled converters) is **not** a machine; `Lamp` (one handler, driven) **is**; `Temperature` (converters used as data) is rejected; `Shape` (one uncalled converter) abstains; `OrderState` (a pipeline without its driver) is a provisional candidate |
| `examples/functionaldriver` | functional (F7) | `examples/cancellation`'s callables installed by an in-model accumulator: 2 states, 6/6. `cancellation` itself installs through `AtomicReference`, which the tool does not read, so it is a provisional candidate (L2) |

Every value-returning fixture carries a small, unseeded store-back driver (F36).
A returned successor is a transition only once a caller installs it. Without the
driver, a fixture is indistinguishable from a converter family.

The `examples/door` case deliberately exercises the hardest patterns: type-pattern `from`-states, constructor-call targets, a guarded ternary transition, and a `return current;` self-loop.

The `examples/turnstile` case targets the control-flow walker directly: each switch arm contains an imperative `if (event instanceof …) yield new X();` followed by a fall-through `yield` of the current state. It verifies that values produced *inside* an `if` are recovered with the condition as guard, and that the guardless fall-through is guarded by the negated condition.

The `examples/gofcontext` case is the GoF State pattern: the `PortalContext` holds a state field and each state's `handle(ctx, event)` method transitions by calling `ctx.setState(new …())`. It proves the mutation encoding converges on the same FSM as the return-based `examples/door`. (It carries an `@Fsm` marker because a hierarchy whose transitions are pure field mutations is not recognised by the structural classifier — detecting that family automatically is future work.)

### The outcomes

State enumeration is exact over the type structure and does **not** depend on
transition recovery, so the classifier has more positions than "machine" and
"not a machine":

| outcome | dispatch | commit | successors | reported as |
|---|---|---|---|---|
| **Tier 1** | present | established | ≥ 1 resolved | a machine, with a transition relation |
| **Tier 2** | present | established | none resolved | a machine; exact states, one unresolved edge per dispatched arm, each with a known source |
| **Tier 3** | plausible | **not** established | — | a **provisional candidate** on `ExtractionResult.candidates()`: members listed provisionally, dispatch sites and missing evidence named, no relation claimed, no `.dot`/`.scxml` |
| **conversion** | present | every caller uses the result as data | — | rejected; on neither channel (Decision 4) |
| **abstention** | none | — | — | rejected, with a diagnostic |

A candidate is an uncertain classification, never counted as a machine, and its
members are never counted as recovered states. For a returned successor,
"established" means a caller is seen storing it back (F36). The commit
requirement is the tool's precision guard; see `SCOPE.md`.

`sample-output/` contains the reference DOT/SCXML that the tool should produce. After building, `diff` your output against these to verify correctness.

## Tests

```bash
mvn test
```

- `serialize/DotSerializerTest` — pure IR/serializer checks; no Spoon dependency.
- `serialize/ScxmlSerializerTest` — SCXML well-formedness + composite nesting; no Spoon dependency.
- `serialize/JsonResultSerializerTest` — the `--json` result is valid JSON carrying outcomes, three state levels and provisional candidates.
- `ExtractionIntegrationTest` — full Spoon-based extraction over the example corpus: state sets, transition edges, guards, self-loops, initial states, rejections.
- `InstallationEvidenceTest` — thesis Decision 4: the acceptance examples (`Length`, `Lamp`), the converter-use, demonstrated-update and missing-caller controls at every locus, and the corpus-wide invariant that a conversion is published on neither channel.
- `StateLevelsTest` — thesis Decision 2: direct branches, atomic states and grouping nodes; open (`non-sealed`) branches; a type under two branches.
- `StateCompletenessTest`, `InterproceduralBindingTest`, and the `detect/`, `extract/`, `model/` unit tests.

## Validation methodology (for the thesis)

The protocol is in `evaluation/PROTOCOL.md` (thesis Decision 3). In short: pin
each repository to an exact commit; label every sealed hierarchy of the sample
**before** looking at the tool's output, including negatives such as converters
and plain data types; keep a held-out split; then score with
`scripts/evaluation/score.py`. It reports classification TP/FP/FN, abstentions
and coverage, state accuracy at each level, and transition precision and recall,
with unresolved edges as their own figure. Conditional accuracy over recognised
machines is always reported next to overall recall, never in place of it. The
bundled examples are regression tests, not evidence of accuracy on independent
projects.

**Corpus tip:** the scarcity of labelled real-world sealed FSMs is the main practical risk. Record the search procedure and report the scarcity rather than filling the corpus with generated code.

## Known limitations (v1 scope)

- **Inter-procedural targets** (`return helper();`, `return factory.make();`) are chased with bounded return-value summaries (finding F3), depth-limited to k = 2 with cycle detection and reported as a separate, precision-sensitive count in diagnostics; a library/abstract callee, a callee whose returns hide inside a switch/loop, or a target beyond the budget stays unresolved.
- **Reassigned locals** are tracked by a flow-sensitive reaching-definitions pass over straight-line + `if`/`else` code (finding F1); a variable written inside a loop, `try`, or nested switch still falls back to an unresolved edge.
- **Mutation / GoF State** transitions (field write or `setState` call) are recovered (finding F2), but only as a fallback and only once the hierarchy is classified as an FSM — a pure-mutation hierarchy currently needs the `@Fsm` marker, since detecting the GoF family structurally (without false-positiving on mutable-field sum types) is future work.
- **Event labels & alphabet Σ.** The alphabet is enumerated exactly and completely from a sealed/enum event parameter — the same closed-world trick used for states (finding F4) — and a `switch (event)` labels each edge with its matched event. Still open: attributing events carried by `instanceof`/ternary guards (as in `examples/door`, whose Σ is recovered but whose edges stay guard-labelled) and mutation-style (GoF) event labelling.
- **Initial-state detection** is heuristic (field initializer, else the unique source-only state) and is flagged when it fails.

## Project layout

```
pom.xml
src/main/java/io/sealfsm/
├── Analyzer.java, Main.java          # pipeline orchestrator, CLI
├── DebugHarness.java, DebugAst.java  # step-by-step runner, Spoon AST inspector
├── annotation/Fsm.java               # optional @Fsm marker
├── analyze/                          # guard analysis over recovered edges (F5)
├── detect/                           # classification: roots, recognizers, veto, audit
│   └── dispatch/                     # locus, commit, installation (F36), probe, call targets
├── extract/                          # StateExtractor (exact), TransitionExtractor + Resolver (approximate)
├── model/                            # State, Transition, StateMachine, Candidate, ExtractionResult, ...
└── serialize/                        # DOT, SCXML, JSON
src/test/java/io/sealfsm/             # unit + integration tests
src/test/resources/                   # unit fixtures (some deliberately do not compile)
examples/                             # the regression corpus, one package per directory
evaluation/                           # PROTOCOL.md, templates, corpus manifests, labels, outcomes
scripts/                              # build-run-render, golden capture, census, depth sweep, evaluation scorer
sample-output/                        # reference DOT/SCXML for diffing
```

`CLAUDE.md` holds the per-class map and the finding-by-finding design notes;
`FIXLOG.md` records each finding's fixture, ablation and regression gate.
