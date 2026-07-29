# SealFSM — Transition-Extraction Findings (Work Order)

**Audience:** an AI coding agent (e.g. Claude Code) that will resolve these one at a time.
**Ordering:** highest risk first. Risk = how badly the finding corrupts the tool's output,
weighting *silent, confidently-wrong output* above *flagged gaps*.

---

## 0. Context an agent needs before touching anything

SealFSM extracts a finite state machine (FSM) from a Java `sealed` type hierarchy and
emits DOT + SCXML. It makes **two claims of different strength — do not blur them:**

1. **States are exact.** Every permitted subtype in the `permits` clause is a state; the
   clause is closed and compiler-checked, so state recall is 1 by construction. Do **not**
   change this.
2. **Transitions are approximate.** They are recovered by intra-procedural data-flow over
   the transition code and reported with precision/recall. Every finding below is in this
   half.

**Global invariant every fix MUST preserve:** anything the analyzer cannot *prove* is
recorded as an **unresolved edge** (dashed red arrow in DOT, XML comment in SCXML) — never
silently dropped, and never upgraded to a resolved edge on a guess. A fix that raises
recall by inventing edges it cannot prove is a regression, not a fix.

**Terminology (project convention — keep it consistent):**
- **algorithm** = the abstract analytical procedure (e.g. "flow-sensitive reaching-
  definitions over the finite state-value lattice").
- **mechanism** = the concrete Spoon AST-visitor that realises the algorithm in the tool.

**Files in scope:**
- `src/main/java/io/sealfsm/extract/TransitionExtractor.java` — the control-flow walker.
  Key methods: `walk`, `walkBlock`, `walkSwitch`, `handleValue`, `emit`,
  `extractDistributed`, `extractCentralized`.
- `src/main/java/io/sealfsm/extract/TransitionResolver.java` — the expression resolver.
  Key methods: `resolve`, `fromVariable`, `fromCast`, `fromTypeRef`.
- `src/test/java/io/sealfsm/ExtractionIntegrationTest.java` — integration oracle.
- `examples/` — labelled micro-benchmarks (currently: traffic, door, turnstile, shape).

**How to work through this list:**
1. Fix findings in order (F1 → F6). Do **one finding per commit/PR.**
2. With each fix, add the specified `examples/<name>/` micro-benchmark **and** an
   integration-test assertion — the fix is not done until it has an oracle.
3. Re-run `mvn test` after every change; all existing tests must still pass.
4. Report precision, recall, and unresolved-rate **separately** (never a single accuracy
   number). Some fixes raise recall but risk lowering precision — that trade-off must stay
   visible, not averaged away.

---

## F1 — Reassigned local variable emits a FALSE, confidently-resolved self-loop  ▲ CRITICAL

**Risk:** CRITICAL. This is the worst defect class: an **unsound edge reported as proven.**
The tool outputs a transition that is simply wrong, with no unresolved flag, silently
corrupting the model. Everything else on this list is a *gap*; this is a *lie*.

**Location:** `TransitionResolver.fromVariable(...)`, the branch where the variable's
declared type equals the sealed root.

**Trigger (minimal failing input):**
```java
// Door is the sealed root; Locked/Open/Closed are permitted subtypes.
static Door transition(Door current, Event event) {
    Door next = current;                       // declared type = Door (the root)
    if (event instanceof Lock) next = new Locked();
    return next;                               // TRUE targets: {Locked (guarded), current (else)}
}
```

**Current behaviour:** `resolve` sees `return next;` as a `CtVariableAccess`. `fromVariable`
reads `next`'s **declared** type, finds it is the root type, and applies the rule
"a read of a root-typed variable means *stay in the matched state*" → emits a **resolved
self-loop** `from → from`. The reassignment `next = new Locked()` is never seen, so the real
`… → Locked` edge is lost **and** a false self-loop is asserted as proven.

**Expected behaviour:** track assignments to the local along the control-flow path. The
returned value has two possible targets: `Locked` (guarded by `event instanceof Lock`) and
the incoming `current` (guarded by the negation → self-loop). Both should be resolved edges
with their guards; neither should be a blind self-loop.

**Fix (algorithm + mechanism):**
- *Algorithm:* a flow-sensitive, intra-procedural **reaching-definitions** analysis over the
  single local, tracking its set of possible state values with the path condition attached
  to each definition. The value domain is the finite set of permitted subtypes plus "the
  incoming selector" (self-loop), so the lattice is small and the analysis terminates
  immediately — no widening needed.
- *Mechanism:* in `TransitionExtractor`, before handing a `CtVariableAccess` to the resolver,
  walk the enclosing block(s) collecting `CtLocalVariable` initializers and `CtAssignment`
  writes to that variable, joined at branch points with accumulated guards. Feed each
  reaching RHS to `TransitionResolver.resolve`. If **any** reaching definition cannot be
  resolved, emit that path as an **unresolved** edge (preserve the invariant) rather than
  guessing.
- *Guardrail:* the "root-typed variable read → self-loop" shortcut in `fromVariable` is only
  valid when the variable is the **unmodified** selector parameter (e.g. `current`). Restrict
  that rule to parameters with **no assignment in the method body**; any locally reassigned
  variable must go through the reaching-definitions path above.

**Acceptance test:** add `examples/local-var/` implementing the trigger. Assert:
`Locked→Unlocked`-style guarded edge present, self-loop present **only** on the else path,
and the spurious blind self-loop is **absent**. Add to `ExtractionIntegrationTest`.

---

## F2 — Field-mutation / setter-based transitions are not recognised  ▲ HIGH

**Risk:** HIGH. Covers the entire classic **GoF State pattern** family (a `Context` holds a
`State` field; transitions happen by `this.state = new Locked()` or `context.setState(next)`).
The walker only inspects *returned/yielded* values, so these transitions are recorded as
**unresolved with the resolvable target discarded** — and become **silently missing** when
the mutation sits inside a construct F6 doesn't descend (loop/try). A huge share of real,
legacy, imperative FSMs use mutation, not return.

**Location:** `TransitionExtractor.walk(...)` — no branch for `CtAssignment` or for
setter-style `CtInvocation` statements.

**Trigger (minimal failing input):**
```java
// mutation style
void transition(Event event) {
    switch (state) {
        case Closed c -> { if (event instanceof Lock) this.state = new Locked(); }
        // ...
    }
}
// GoF callback style
class Closed implements Door {
    public void onLock(Context ctx) { ctx.setState(new Locked()); }
}
```

**Current behaviour:** `this.state = new Locked()` is a `CtAssignment` (a `CtExpression`), so
`walk` routes it to `handleValue → resolve`, which receives the **whole assignment node**,
matches none of its shapes, and emits an **unresolved** edge — throwing away the perfectly
resolvable RHS `new Locked()`. The `ctx.setState(...)` case is a `CtInvocation`, also emitted
unresolved with its argument discarded.

**Expected behaviour:** an assignment to the state field, or a call to a recognised state
mutator, is a transition-producing site; its RHS/argument is the next-state expression and
must be handed to the resolver like any return value.

**Fix (algorithm + mechanism):**
- *Algorithm:* extend the set of "next-state-producing sites" from `{return, yield, arrow}`
  to also include (a) assignment to the **state field** and (b) invocation of a **state
  mutator**. The state field is the field whose declared type is the sealed root; a mutator
  is a method whose single parameter is root-typed (recognise common names
  `setState`/`changeState`/`transitionTo`/`goTo` and, more robustly, any method that assigns
  the state field).
- *Mechanism:* add a `CtAssignment` branch to `walk`: if the LHS resolves to the state field,
  call `handleValue(assignment.getAssignment(), …)` (the RHS). Add a `CtInvocation`-as-
  statement branch: if the callee is a recognised mutator, call `handleValue` on the argument.
  In the GoF callback style, the **from-state** is the declaring state class of the method
  (same rule as distributed methods).
- *Invariant:* if the RHS/argument is itself unresolvable, still emit unresolved — but now
  with the correct *from* and the raw RHS text, not the whole statement.

**Acceptance test:** add `examples/gof-context/` with a `Context` + state field + `setState`
callbacks. Assert the same edge set the return-based `examples/door` produces, proving the
mutation and functional encodings converge on the same FSM.

---

## F3 — Inter-procedural targets (factories, delegates) left unresolved  ▲ HIGH

**Risk:** HIGH (recall), but *honest* — these are flagged unresolved today, not wrong.
Real State code delegates constantly: `return next(event);`, `return States.locked();`,
`return Locked.of();`, `return factory.create(event);`. All currently unresolved.
**Precision warning:** this is the one fix that can *introduce* spurious edges, so it needs
the tightest guardrails.

**Location:** `TransitionResolver.resolve(...)`, the `CtInvocation` branch (currently
`→ unresolved`).

**Trigger (minimal failing input):**
```java
static Door transition(Door current, Event event) {
    return lockIfRequested(current, event);    // helper returns a permitted subtype
}
static Door lockIfRequested(Door current, Event event) {
    return event instanceof Lock ? new Locked() : current;
}
```

**Current behaviour:** the call is emitted unresolved; the two real targets inside the helper
are never reached.

**Expected behaviour:** resolve the callee's possible return values (with their guards) and
substitute them at the call site — but only when it can be done soundly.

**Fix (algorithm + mechanism):**
- *Algorithm:* bounded, **k-limited inter-procedural return-value summaries.** For a callee
  that returns the hierarchy type and is available in the model, compute the set of concrete
  states its body can produce (reusing the same walker/resolver), then substitute at the call
  site. Bound recursion depth (start with k = 2) and detect cycles to guarantee termination.
- *Mechanism:* in the `CtInvocation` branch, look up the callee `CtMethod` via the model; if
  found and returning a hierarchy type, recurse the walker on its body under the current
  guard, decrementing a depth budget. If the callee is unavailable (library/abstract/depth
  exhausted), keep the **unresolved** edge.
- *Precision guardrails (mandatory):* only substitute when the callee's return targets are
  themselves fully resolved; if the callee mixes resolvable and unresolvable returns, emit
  the resolvable ones **and** an unresolved edge for the remainder. Report resolved-via-
  interprocedural edges as a **separate count** in diagnostics so their effect on
  precision/recall is auditable.

**Acceptance test:** add `examples/factory/` with the trigger. Assert both `→Locked` and the
self-loop are resolved through the helper, and that a deliberately out-of-budget / library
call in the same file stays unresolved.

---

## F4 — Event alphabet (Σ) not extracted in centralized style  ◆ MEDIUM

**Risk:** MEDIUM. Not wrong, but **incomplete by half.** A centralized transition is
`δ(state, event) → state`; today the centralized event label is `null`, so parallel edges
between the same two states collapse and the input alphabet Σ is lost. Σ is exactly what
model-based testing enumerates test sequences over, so its absence limits the tool's stated
purpose.

**Location:** `TransitionExtractor.extractCentralized(...)` (passes `event = null`) and
`walkSwitch(...)`.

**Trigger (minimal failing input):**
```java
sealed interface Event permits Lock, Unlock, Break {}   // closed, compiler-checked
static Door transition(Door current, Event event) {
    return switch (current) {
        case Closed c -> switch (event) {                // dispatch on event
            case Lock l   -> new Locked();
            case Unlock u -> new Open();
            case Break b  -> current;
        };
        // ...
    };
}
```

**Current behaviour:** all three arms produce edges with `event = null`; `Closed→Locked`,
`Closed→Open`, and the self-loop are indistinguishable by trigger.

**Expected behaviour:** recover Σ = {Lock, Unlock, Break} and label each edge with its
triggering event.

**Fix (algorithm + mechanism):**
- *Algorithm:* apply the **same closed-world trick used for states** to the event type. If
  the transition method has a second parameter typed as a sealed hierarchy or an enum, Σ is
  its permitted subtypes / enum constants — exact and complete, exactly like Q. Attribute
  each nested-switch arm (or `instanceof Event` guard) to its matched event.
- *Mechanism:* in `extractCentralized`, detect the event parameter and enumerate Σ from its
  `permits` clause / enum constants. In `walkSwitch`, when the switch selector is the event
  type, set the `event` label per arm (mirroring how state dispatch sets the `from`-state).
  Populate `StateMachine`'s alphabet field and the SCXML `event` attribute.
- *Note:* this is a genuinely strong result to surface — **both Q and Σ recovered soundly
  from sealed structure** — so make sure it shows up in the emitted alphabet, not just on
  edges.

**Acceptance test:** add `examples/event-alphabet/` with the trigger. Assert the machine's
alphabet = {Lock, Unlock, Break} and each edge carries the right event label.

---

## F5 — Guards are opaque text; nondeterminism / non-exhaustiveness undetectable  ◆ MEDIUM

**Risk:** MEDIUM. Guards are captured as raw source strings, threaded with `" && "`, and
negated by string-wrapping (`"!(…)"`). That is fine for an SCXML `cond` attribute but means
the tool **cannot detect two real defects:** overlapping guards from the same state
(**nondeterminism** → a precision problem for MBT) and gaps between guards
(**non-exhaustiveness** → a recall problem). String negation of compound conditions is also
brittle for any reasoning beyond display.

**Location:** guard handling throughout `TransitionExtractor` (`merge`, `negate`, `combine`
in the resolver) — currently pure string ops.

**Trigger (minimal failing input):**
```java
case Locked l -> {
    if (coins >= 1) yield new Open();        // guard A
    if (coins > 0)  yield new Open();         // guard B overlaps A — nondeterministic
    // no else: what happens when coins <= 0 ? — non-exhaustive
}
```

**Current behaviour:** three edges emitted with string guards; the A/B overlap and the
uncovered `coins <= 0` gap are invisible — no diagnostic, no flag.

**Expected behaviour:** keep emitting the edges, **and** raise diagnostics when guards from
the same source state overlap (possible nondeterminism) or fail to cover their domain
(possible missing transition).

**Fix (algorithm + mechanism):**
- *Algorithm:* introduce a small **guard IR** — a normalized boolean tree over atomic
  predicates — so guards can be compared. On this IR, run two checks per source state:
  pairwise satisfiability of guard conjunctions (overlap → nondeterminism) and coverage of
  the disjunction (gap → non-exhaustiveness). Full SMT is out of scope; syntactic
  normalization + simple predicate matching catches the common cases.
- *Mechanism:* parse `CtExpression` guards into the IR instead of `safeText`; keep a
  `toString()` for the SCXML `cond`. Emit `WARN` diagnostics for detected overlaps/gaps.
- *Invariant:* these are **diagnostics, not edge removals** — never drop or merge an edge on
  the basis of guard reasoning; only report.

**Acceptance test:** add `examples/nondeterministic/` with overlapping guards. Assert the
edges are still present **and** a nondeterminism WARN is raised; add a non-exhaustive variant
asserting a coverage-gap WARN.

---

## F6 — try/catch and loop bodies are not descended  ● LOW

**Risk:** LOW on its own, but it is the **silent-miss enabler** for F1/F2 (a mutation or
producer inside an undescended `try`/loop vanishes with no unresolved flag). The common real
case is `catch (…) { … Error state … }` — an error-transition that is simply absent today.

**Location:** `TransitionExtractor.walk(...)` — no branch for `CtTry`/`CtCatch` or loop nodes;
comment explicitly excludes them.

**Trigger (minimal failing input):**
```java
case Running r -> {
    try { yield step(event); }
    catch (Exception e) { yield new Failed(); }   // error transition — currently dropped
}
```

**Current behaviour:** the `try` block is not descended; both the normal `step(...)` producer
and the `catch → Failed` edge are missing, with no unresolved marker (silent miss).

**Expected behaviour:** descend the try block and each catch block; the catch block's producer
is an edge guarded by "exception" (or the caught type). Loop bodies are descended for
producers (the loop just repeats the same transitions).

**Fix (algorithm + mechanism):**
- *Algorithm:* extend the control-flow walk to cover exceptional and iterative flow: try body
  under the normal guard, each catch under an "exception" guard, loop body under the loop's
  entry condition.
- *Mechanism:* add `CtTry` and loop (`CtLoop` / `CtFor` / `CtWhile` / `CtForEach`) branches to
  `walk`, recursing into their bodies. Attach a synthetic `exception`/caught-type guard to
  catch-block producers.

**Acceptance test:** add `examples/error-handling/` with the trigger. Assert both the normal
edge and the `Running→Failed` (guard: exception) edge are recovered.

---

## Summary table

| ID | Finding | Risk | Primary effect if unfixed |
|----|---------|------|---------------------------|
| F1 | Reassigned local → false self-loop | **CRITICAL** | Unsound edge reported as proven |
| F2 | Field-mutation / setter transitions | **HIGH** | GoF family unresolved / silently missing |
| F3 | Inter-procedural targets unresolved | **HIGH** | Large recall loss (fix risks precision) |
| F4 | Event alphabet Σ not extracted | MEDIUM | Half the FSM tuple missing |
| F5 | Opaque guards | MEDIUM | Nondeterminism / gaps undetectable |
| F6 | try/catch + loops not descended | LOW | Error edges silently missing |

**Definition of done for the whole work order:** all six examples added with hand-verified
reference DOT/SCXML; `mvn test` green; precision, recall, and unresolved-rate reported
separately per finding; the "record-unresolved-never-drop, never-guess-a-resolved-edge"
invariant intact everywhere.
