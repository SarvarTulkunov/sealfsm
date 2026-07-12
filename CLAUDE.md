# CLAUDE.md — SealFSM project context for Claude Code

## What this project is

SealFSM is a Java 21 static-analysis tool that extracts finite state machines from sealed class hierarchies. It is the implementation component of a master's thesis titled "Automated Extraction of Finite State Machines from Sealed Class Hierarchies in Modern Java: A Static Analysis Approach."

The tool reads Java source code via Spoon, finds sealed type hierarchies, classifies which ones are state machines (rejecting plain sum types), extracts states and transitions, and outputs the FSM as Graphviz DOT and W3C SCXML.

## The thesis distinction — never conflate these

The tool makes two claims of DIFFERENT strength. Every piece of code, output, test, and documentation must keep them separate:

1. **State enumeration is provably complete.** States come from `permits` clauses, which are compiler-checked exhaustive. This is exact.
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
  → StateMachineClassifier (FSM or sum type? distributed/centralized?)
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
- `examples/shape/` — NEGATIVE CONTROL: sealed `Shape permits Circle, Square, Triangle` with `area()` returning `double`. Must be REJECTED — no method returns the hierarchy type.

Reference output in `sample-output/` — diff after building to verify.

## Tests

- `serialize/DotSerializerTest` — pure IR/serializer (no Spoon)
- `serialize/ScxmlSerializerTest` — SCXML well-formedness + nesting (no Spoon)
- `ExtractionIntegrationTest` — full Spoon extraction over all 3 examples

## Key implementation details

### SpoonCompat (detect/SpoonCompat.java)
All version-sensitive Spoon calls live here. `isSealed()` checks `ModifierKind.SEALED` with try/catch fallback. `permittedTypes()` uses reflection for `getPermittedTypes()` with a fallback that scans the model for direct subtypes (handles implicit permits).

### StateMachineClassifier (detect/StateMachineClassifier.java)
Contains shared static helpers reused by the extractor:
- `findDistributedTransitionMethods(root)` — methods ON the hierarchy returning hierarchy type
- `findCentralizedTransitionMethods(root, model)` — methods OUTSIDE hierarchy taking+returning hierarchy type
- `hierarchyQualifiedNames(root)` — all qualified names in the hierarchy
- `hierarchyTypes(root)` — all CtType objects in the hierarchy

### TransitionExtractor (extract/TransitionExtractor.java)
Most Spoon-version-sensitive file. Pattern-matching switch case handling is fully reflective (`patternType()` uses `tryMethod` chains). Switch arms are read via `caseValueExpressions()` which handles block+yield, return, and arrow-expression forms.

### TransitionResolver (extract/TransitionResolver.java)
Takes `hierarchyQualifiedNames` AND `rootQualifiedName`. The root distinction matters: a variable typed as the abstract root (e.g. `Door current`) being returned means "stay in current state" (self-loop), while a variable typed as a concrete state (e.g. `Locked l`) resolves to that state.

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

## What to work on next (likely tasks)

- Expand the corpus: harvest GitHub for real sealed FSM hierarchies
- Add more example fixtures for edge cases (enum-based states, nested composites, multi-method distributed)
- Improve event labelling for centralized encoding
- Add sealed-Event alphabet enumeration (e.g. door example has `sealed Event permits Push, Lock, Unlock`)
- Write more targeted unit tests for `TransitionResolver` expression shapes
- Thesis writing: validation chapter, corpus results tables
