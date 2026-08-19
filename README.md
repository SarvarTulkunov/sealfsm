# SealFSM

**Automated extraction of finite state machines from sealed class hierarchies in modern Java — a static-analysis approach.**

SealFSM reads Java source, finds `sealed` type hierarchies that encode state machines, and emits the recovered FSM as **DOT** (for visualisation) and **SCXML** (for model-based testing tools).

## The core idea — and the one distinction that matters

A `sealed` interface lists its implementations in a compiler-checked `permits` clause. That makes the set of subtypes **closed and exhaustive**. SealFSM exploits this in two steps that make claims of *different strength* — keeping them separate is the whole point:

1. **State enumeration is provably complete.** Every permitted subtype is a state; the `permits` clause guarantees there are no others. This is exact, not heuristic. When the *event* type is itself a sealed hierarchy or an enum, the input alphabet Σ is recovered the same exact way (finding F4) — so both the state set Q *and* Σ come soundly from the closed-world structure.
2. **Transition extraction is approximate.** Transitions — the transition relation δ connecting states over events — are recovered by intra-procedural data-flow analysis over the transition code. This is empirical and is reported with precision/recall, never presented as complete.

The tool is built around that asymmetry. Anything it cannot resolve on the transition side is **recorded as an explicit unresolved edge** (a dashed red arrow in DOT, an XML comment in SCXML) rather than silently dropped, so gaps depress recall visibly instead of masquerading as a complete model.

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
 │    └─ FSM or plain sum type? ──────▶ rejected       │
 │              │                       (e.g. Shape)   │
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
| `detect` | `StateMachineClassifier` | Rejects plain sum types; detects encoding (distributed/centralized/mixed); exposes shared method-discovery helpers |
| `detect` | `SpoonCompat` | Isolates version-sensitive Spoon API calls (`isSealed`, `permittedTypes`) with reflective fallbacks |
| `extract` | `StateExtractor` | `permits` → `State` objects; recursive for nested sealed (composite states) |
| `extract` | `TransitionExtractor` | Orchestrates both encodings: distributed (per-state methods) and centralized (switch dispatch) |
| `extract` | `TransitionResolver` | Intra-procedural data-flow: resolves return expressions to target states (`new X()`, `this`, ternary, variable reads) |
| `model` | `State` | State IR; `id`, `qualifiedName`, `composite`, `initial`, `children` |
| `model` | `Transition` | Transition IR; first-class `resolved` flag; `from`, `to`, `event`, `guard`, `note` |
| `model` | `StateMachine` | Aggregate: states, transitions, encoding, alphabet, initial state |
| `model` | `ExtractionResult` | All machines + `Diagnostic` records (INFO/WARN) |
| `serialize` | `DotSerializer` | Graphviz DOT; composite → `subgraph cluster_*`; unresolved → dashed red edge |
| `serialize` | `ScxmlSerializer` | W3C SCXML; composite → nested `<state>`; unresolved → XML comment |
| `annotation` | `@Fsm` | Optional marker annotation to force classification; matches by simple name |
| root | `Analyzer` | Orchestrates the full pipeline + initial-state heuristics |
| root | `Main` | CLI entry point (`--src`, `--out`, `--format`, `--quiet`) |
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
| **Distributed method** (`Red.next()` on a state class) | `from` = declaring state class; `event` = method name (neutral names like `next`/`transition`/`step`/`advance`/`tick` → no label) |
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

  --src <path>     Source file or directory to analyse (repeatable, required)
  --out <dir>      Output directory (default: ./out)
  --format <fmt>   dot | scxml | both   (default: both)
  --quiet          Suppress the diagnostics listing
  -h, --help       Show help
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
| `examples/shape` | — (negative) | **rejected** as a plain sum type |

The `examples/door` case deliberately exercises the hardest patterns: type-pattern `from`-states, constructor-call targets, a guarded ternary transition, and a `return current;` self-loop.

The `examples/turnstile` case targets the control-flow walker directly: each switch arm contains an imperative `if (event instanceof …) yield new X();` followed by a fall-through `yield` of the current state. It verifies that values produced *inside* an `if` are recovered with the condition as guard, and that the guardless fall-through is guarded by the negated condition.

The `examples/gofcontext` case is the GoF State pattern: the `PortalContext` holds a state field and each state's `handle(ctx, event)` method transitions by calling `ctx.setState(new …())`. It proves the mutation encoding converges on the same FSM as the return-based `examples/door`. (It carries an `@Fsm` marker because a hierarchy whose transitions are pure field mutations is not recognised by the structural classifier — detecting that family automatically is future work.)

`sample-output/` contains the reference DOT/SCXML that the tool should produce. After building, `diff` your output against these to verify correctness.

## Tests

```bash
mvn test
```

- `serialize/DotSerializerTest` — pure IR/serializer checks; no Spoon dependency.
- `serialize/ScxmlSerializerTest` — SCXML well-formedness + composite nesting; no Spoon dependency.
- `ExtractionIntegrationTest` — full Spoon-based extraction over all three examples: verifies state sets, transition edges, guards, self-loops, initial states, and rejection of `Shape`.

## Validation methodology (for the thesis)

1. **States — exact.** For each labelled example, assert the extracted state set equals the `permits` set. Expect 100% by construction; any miss is a parser/model bug.
2. **Transitions — precision/recall.** Hand-label the true transition relation for 20–50 real hierarchies; compare against extracted **resolved** transitions. Report precision, recall, and unresolved-rate separately.
3. **Classifier — confusion matrix.** Mix real FSMs with plain sum types; report false-positive / false-negative rates.

**Corpus tip:** the scarcity of labelled real-world sealed FSMs is the main practical risk. Harvest GitHub for `sealed interface ... permits` with a self-returning method or a `T f(T, …)` function.

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
├── Analyzer.java              # pipeline orchestrator
├── Main.java                  # CLI entry point
├── DebugHarness.java          # step-by-step pipeline runner
├── DebugAst.java              # Spoon AST inspector
├── annotation/
│   └── Fsm.java               # optional @Fsm marker
├── detect/
│   ├── SealedHierarchyDetector.java
│   ├── SpoonCompat.java
│   └── StateMachineClassifier.java
├── extract/
│   ├── StateExtractor.java
│   ├── TransitionExtractor.java
│   └── TransitionResolver.java
├── model/
│   ├── State.java
│   ├── Transition.java
│   ├── StateMachine.java
│   └── ExtractionResult.java
└── serialize/
    ├── DotSerializer.java
    └── ScxmlSerializer.java
src/test/java/io/sealfsm/
├── ExtractionIntegrationTest.java
└── serialize/
    ├── DotSerializerTest.java
    └── ScxmlSerializerTest.java
examples/
├── traffic/    # distributed State pattern (TrafficLight)
├── door/       # centralized switch (Door + sealed Event)
├── turnstile/  # centralized switch with if-guarded arms + fall-through
├── localvar/   # reassigned root-typed local (reaching-definitions, F1)
├── gofcontext/ # GoF State pattern: setState field mutation (F2)
├── factory/    # inter-procedural delegate + factory helpers (F3)
├── eventalphabet/ # switch-over-event: recovers Σ + labels edges (F4)
└── shape/      # negative control (plain sum type)
sample-output/  # reference DOT/SCXML for diffing
```
