# Limitations observed on real code

_Written to be lifted into the thesis's limitations section. Every claim here was
measured with the tool, on the source revision named, rather than argued. For the
positive statement of the scope boundary, see `SCOPE.md`; this file records
transition encodings the tool meets in real projects and does not recover. It
also explains why that is a deliberate boundary rather than a defect._

---

## L1. Target-major transition tables (source state written as a precondition)

**Observed on:** Apache Kafka `4.5.0-SNAPSHOT`, module `raft`
(`corpus/kafka/raft/src/main/java/org/apache/kafka/raft/`). The run was
`java -jar target/sealfsm.jar --src corpus/kafka/raft/src/main/java`.

### The shape

Every encoding SealFSM recovers is **state-major**. Somewhere the code asks "which
state am I in right now?", and each branch of that question installs a successor:

```java
switch (state) {                                        // discriminate the CURRENT state
    case ProspectiveState p -> state = new CandidateState(...);
    case CandidateState c   -> state = new LeaderState(...);
}
```

Kafka's KRaft replica-role machine writes the same information the other way
round. It has **one method per target state**, and the permitted source states
appear only as a precondition that throws:

```java
// QuorumState.java
public void transitionToCandidate() {
    checkValidTransitionToCandidate();     // if (!isProspective()) throw ...
    durableTransitionTo(new CandidateState(...));
}
```

| Element | Where in Kafka |
|---|---|
| State hierarchy | `EpochState.java:21` (`sealed … permits LeaderState, FollowerState, UnattachedState, ResignedState, NomineeState`), `NomineeState.java:21` (`sealed … permits ProspectiveState, CandidateState`) |
| State field | `QuorumState.java:99`, `private volatile EpochState state;` |
| One method per target | `QuorumState.transitionToResigned / Unattached / Follower / Prospective / Candidate / Leader`, lines 350–725 |
| Source constraint | throw-guards such as `if (!isLeader()) throw …`, where the state test is inside a helper: `isLeader()` is `state instanceof LeaderState` (lines 838–870) |
| Commit | `durableTransitionTo` → `memoryTransitionTo` → `state = newState` (lines 729–741) |
| Trigger (the "event") | `KafkaRaftClient`, in another class, several calls away: vote responses, fetch timeouts, election timeouts |

We call this **target-major**, a third orientation beside state-major (the
recovered one) and Σ-major (a switch over the *input*, F27). The table is indexed
by where a transition goes, and where it may come from is a side condition.

### What the tool reports, and why that output is correct

`EpochState` is reported as a **Tier 3 candidate**. All 7 states are enumerated
exactly: six leaves plus the `NomineeState` composite, which is Kafka's full role
set. No transition relation is claimed, and no diagram is drawn. `NomineeState` is
offered again as a root once its parent abstains, and is likewise reported as a
2-state candidate.

This is the behaviour the three-tier design exists to produce. The exact claim
(state enumeration) holds. The approximate claim (transitions) is withheld rather
than fabricated.

### Why the relation is not recovered

It takes three properties together. Each one on its own is already outside a rule
the tool keeps deliberately:

1. **Nothing discriminates the current state.** The only `instanceof` tests on the
   state field are inside accessor predicates (`isLeader()`, `maybeLeaderState()`),
   and those fold into `boolean` or `Optional`. Every recognizer starts from a
   discrimination of the state, so there is no place to attach a source state.
2. **The source state is a precondition, not a branch.** `if (!isProspective())
   throw` says "only Prospective may reach this line". Reading it that way means
   inlining the predicate helper, then treating the rejection branch as the
   complement of the allowed source set. That is **precondition inference**, a
   different analysis from the dispatch-and-commit reading the tool performs.
3. **The trigger is inter-object.** The input that fires a transition is decided
   in `KafkaRaftClient`, and the commit is up to five calls away on a different
   object, for example `maybeTransitionForward` → `maybeTransitionToCandidate` →
   `transitionToCandidate` → `quorum.transitionToCandidate()` →
   `durableTransitionTo` → `memoryTransitionTo`. The k = 1 commit probe and the
   k = 2 successor fold are both far short of that, by design.

### Why it is out of scope rather than future work for this thesis

- **Source sets are often data-dependent.** `transitionToUnattached` rejects when
  `epoch < currentEpoch || (epoch == currentEpoch && !isProspective())`. Its
  allowed sources are *every* state when the epoch rises, and only `Prospective`
  when it is unchanged. `transitionToFollower` barely restricts its source at all.
  A faithful extraction is dominated by "from any state, under a data condition"
  edges. Those are hard to validate against a reference, and they fail in the
  direction the soundness invariant forbids: an unconstrained method silently
  becomes an edge out of every state.
- **The population is known but not yet separated.** Target-major tables belong to
  the 875 closed-type state fields that `CENSUS.md` found committed with no
  dispatch at all. That population is already recorded as a separate finding.
  How much of it is target-major specifically has **not** been measured.
- **Recovering it would not strengthen the thesis's claims.** The contribution
  is exact state enumeration, plus transition recovery over *defined* encodings
  with measured precision and recall. Adding an encoding whose ground truth is
  itself data-dependent would widen coverage at the cost of the precision figure.

### What closing it would take (not implemented)

1. Recognise a **target-major commit site**: a method that builds exactly one
   member of H and commits it through a recognised mutator. Measured:
   `MutatorRecognizer` already accepts `memoryTransitionTo(EpochState)`, but not
   `durableTransitionTo`, which only forwards to it. Most Kafka sites call the
   forwarder, so mutator recognition would have to follow one forwarding hop.
2. Inline boolean **predicate helpers** over the state field, so that `isLeader()`
   reads as `state instanceof LeaderState`.
3. Read a **throw-guard** that precedes the commit as the complement of the source
   set. F14 already derives the negated condition of a branch that cannot
   complete normally, and that is the same machinery.
4. Decide what an **unconstrained** target method means: "from every state", or
   an explicit gap. This is the precision question above, and the whole cost of
   the extension sits here.

A cheaper intermediate step, consistent with F27, would recognise step 1 alone. It
would then name those sites on the candidate channel ("commit found in
`transitionToCandidate` through `memoryTransitionTo`; current state never
discriminated"). This claims no relation. It turns the candidate's reason into a
precise, countable gap, which is also how the population could be measured.

### Suggested thesis wording

> SealFSM recovers transition relations written *state-major*: organised around a
> discrimination of the current state. A real-world counter-example is Apache
> Kafka's KRaft replica-role machine. There, the table is written *target-major*:
> one method per destination state, with the permitted source states expressed as
> throwing preconditions over predicate helpers, and triggers originating in a
> separate class. On this input the tool enumerates the complete state set (seven
> states, including one composite) and reports the hierarchy as a candidate
> without claiming a relation. Recovering target-major tables requires
> precondition inference, and in this instance the source sets are data-dependent,
> so we leave it as future work.

---

## L2. Commits through a library container (`AtomicReference`)

**Observed on:** the same run, `internals/KRaftVersionUpgrade.java`, a sealed
interface with three records (`Empty`, `Version`, `Voters`).

Its real lifecycle is in `LeaderState.java`. The state lives in an
`AtomicReference<KRaftVersionUpgrade>`, seeded with `KRaftVersionUpgrade.empty()`
(line 106), and advances Empty → Voters (`.set`, line 177), Voters → Voters
(`compareAndSet`, line 400) and Voters → Version (`compareAndSet`, line 543).

Every commit is a call into the JDK, and the tool never reads a JDK method body
(a Spoon *shadow*, F11). That is deliberate: an unread body is evidence of
nothing. So the tool cannot establish that `set`/`compareAndSet` installs a
hierarchy value, and the hierarchy is correctly **rejected**. Its own javadoc
also calls it a "sum type". Recognising atomic containers as commit channels
would mean giving specific library methods a known meaning. That is a modelling
decision, and not one the thesis takes.

**Since F36 the same boundary applies to a returned successor.** A transition
function whose result is installed only by a library container is not shown to
install anything, so its hierarchy is a provisional candidate
(`INSTALLATION_UNSHOWN`), not a machine. `examples/cancellation` is that case:
its callables are applied by `AtomicReference.accumulateAndGet`.
`examples/functionaldriver` is the same code with an in-model accumulator, and it
is a machine.

This hierarchy was also the source of **F30** (see `FIXLOG.md`). Before that fix,
its `static empty()` factory was read as a per-state transition method, and the
hierarchy was published as a one-edge machine sourced at the root interface. That
was a false positive, and it is fixed. The limitation above is what remains
once the false positive is gone.

---

## L3. A value-returning function was committed by its return type alone (CLOSED by F36)

**Status: closed** on branch `thesis-decisions` by F36, which implements thesis
Decision 4 ("converters are not FSMs"). The fix below, "a stored-back commit found
at the caller, tool-wide", is the one this section specified. What it changed and
what it costs are recorded at the end of the section. The original text is kept
because it states the problem the thesis decision answers.

**Observed on:** constructed fixtures, not yet on a harvested project:
`examples/typedhandler` (`Shape`, `Lamp`, `Length`), which F35 added, plus a
switch-spelled converter measured by hand. Unlike L1 and L2, this is a
**precision** limitation (a sum type published as a machine), not a recall one.

### The shape

A method that takes a state and returns a state is either a transition or a
conversion, and nothing in its signature or body tells which:

```java
OrderState handle(OrderState.Placed p) { return new OrderState.Validated(...); } // a transition
Shape boundingBox(Shape.Circle c)       { return new Shape.Square(2 * c.r()); } // a conversion
```

In the first, the order *moves* from Placed to Validated. In the second, the
circle still exists when the method returns, and a separate value has been
derived from it. The difference is in how the result is **used**: a transition's
result replaces the input somewhere (`this.state = handle(state)`,
`orchestrate(handle(p))`, `while (…) s = step(s)`). A conversion's result
does not.

### What the tool reports, and why

The tool never asks where a returned value goes. For every value-returning host
(a per-state `next()`, a centralized `transition(H, E)`, a typed handler) the
commit is proven by the **codomain alone**: returning the hierarchy type
*is* the `VALUE_RETURN` commit. That is how `examples/traffic` and
`examples/door` are accepted, and it is applied the same way to every
spelling:

| Source | Verdict | Correct? |
|---|---|---|
| `static Shape bounding(Shape s) { return switch (s) { case Circle c -> new Square(..); case Square q -> new Circle(..); }; }` | machine, 2/2 | **no**: a sum type |
| `Shape boundingBox(Circle c)`, one typed converter (`typedhandler.Shape`) | rejected (F35) | yes |
| `Lamp press(Dark d)`, a real two-state machine with one handler (`typedhandler.Lamp`) | rejected (F35) | **no**: the cost of F35 |
| `toFeet(Meters)` + `toMeters(Feet)` (`typedhandler.Length`) | machine, 2/2 | **no**: a sum type |

F35 is only a threshold: typed handlers are admitted only as a family that fixes
at least two distinct source states, the same "one type test is a check, not a
dispatch" rule the `instanceof`-chain recognizer applies. It removes the
commonest case (one converter) and nothing more. `Length` is pinned in
`ExtractionIntegrationTest.oneTypedHandlerIsAConversionAndAFamilyIsADispatch`
as a **wrong answer on purpose**, so that the test fails once this limitation
is closed.

### Why the threshold was chosen over the real fix

The real fix is a rule that a value-returning host is a transition only if its
result is **stored back** as the new state. Applying that rule to typed handlers
alone was considered and rejected, because it would split one converter's verdict
by spelling: the switch-spelled converter above would still be accepted by
codomain while its typed-parameter twin was rejected. A rule about what a commit
*is* has to hold at every locus or at none.

### What closing it would take (not implemented)

1. **A stored-back commit for `VALUE_RETURN`, tool-wide.** Accept a
   value-returning host only when some caller installs its result into the place
   the input came from: a hierarchy-typed field (`this.state = f(this.state)`),
   a self-re-entering driver (F34's run-to-completion rule already recognises this
   shape), or a loop variable reassigned from the call (`s = step(s)`).
2. **A caller search.** The host and its commit are usually in different methods
   (`orchestrate` → `handle`, `DoorContext.fire` → `DoorMachine.transition`), so
   this is an inter-procedural question from the callee *back to* its callers.
   The tool never asks a question in that direction today.
3. **A decision on hosts with no caller in the source set.** A library's public
   `transition(H, E)` is called only by code outside `--src`. Under a strict rule
   it would drop to a Tier 3 candidate (states exact, no relation claimed). That is
   honest, but it would move machines the corpus currently accepts. Every
   value-returning fixture (`traffic`, `door`, `http2-stream-claude`,
   `lcp_automation`, `typedhandler.OrderState`, …) would need its caller checked,
   and the documented numbers would need re-baselining.
4. **Fixtures:** `typedhandler.Length` flips to rejected and `Lamp` is
   re-examined, a switch-spelled converter is added as a control, and at least one
   machine with no in-model caller pins the Tier 3 outcome.

### How F36 closed it

`detect/dispatch/Installation` asks, for every site whose successor leaves its
host by `return` (`VALUE_RETURN`, `CARRIER_RETURN`, `POLY_CARRIER`, and a
returned `LOCAL_ACCUMULATOR`), where the returned value goes. It follows the value
through conditionals, switch-expression arms, locals, returns and arguments,
across up to six call boundaries, to one of four sinks: a write to a field
declared with the root, a write back into the variable the call read its state
from, a recognised mutator, or a run-to-completion driver's selector. The
classifier accepts only sites where the value reaches a sink. The extractor walks
only those sites, from the same report.

| Source | Before F36 | After F36 | Correct? |
|---|---|---|---|
| switch converter, result used as data (`converters.Shade`) | machine, 2/2 | **rejected as a conversion**, no candidate | yes |
| the same code, stored back (`converters.Tint`) | machine, 2/2 | machine, 2/2, `VIA_CALLER` | yes |
| the same code, never called (`converters.Hue`) | machine, 2/2 | provisional **candidate** (`INSTALLATION_UNSHOWN`) | yes: uncertain |
| per-state converter used as data (`converters.Currency`) | machine | **rejected as a conversion** | yes |
| `Shape boundingBox(Circle c)`, uncalled (`typedhandler.Shape`) | rejected (F35) | abstained, no candidate | yes |
| `Lamp press(Dark d)`, driven (`typedhandler.Lamp`) | rejected (F35) | **machine, 1/1** | yes |
| `toFeet` + `toMeters`, uncalled (`typedhandler.Length`) | machine, 2/2 | provisional **candidate** | yes: uncertain |
| two converters used as data (`typedhandler.Temperature`) | (new) | **rejected as a conversion** | yes |

Point 3 above was decided the strict way, as Decision 4 requires: missing caller
evidence is uncertainty, so a host with no caller in the source set is not a
machine. It is a provisional candidate where its sites amount to a dispatch, and
a plain abstention otherwise.

**What it cost, measured.** On the corpus, 22 value-returning machines had no
caller in the source set: their fixtures had been written without a driver.
19 of them, the ones whose purpose is extraction, were given a minimal,
unseeded store-back driver. Every one of those fixtures' `.dot` and `.scxml` files is
byte-identical to before, which is the check that the drivers changed nothing
but the evidence. Two became what the decision says they should be.
`typedhandler.OrderState` (the pipeline without its driver) is now a
provisional candidate, and `examples/cancellation` (commits through
`AtomicReference.accumulateAndGet`, i.e. L2) is too. Its F7 walk is kept on a
machine by `examples/functionaldriver`, the same callables installed in-model.

**What it cannot see.** A real machine whose store-back is outside the source
set is an abstention by design. A library's public transition function used only
by client code is the typical case, and the evaluation protocol counts such an
abstention as a missed machine. A converted value cached in a root-typed field
reads as installed, because that is the decision's own clause ("an assignment to
a state field").

### Suggested thesis wording

> A value-returning function that maps a state to a state is a transition only
> if its result replaces the current state. A conversion within a sum type has the
> same signature, and only its use separates the two. SealFSM therefore follows a
> returned successor from the function to its callers, and accepts the function
> only when the value is stored back into state storage, handed to a state
> mutator, or re-entered into a run-to-completion driver. When every caller uses
> the value as data, the hierarchy is rejected as a conversion. When no caller
> exists in the analysed source, the tool abstains, because missing caller
> evidence is uncertainty rather than proof of a conversion.

---

## Related issue, fixed: the candidate reason conflated two gaps (F32)

On this run `EpochState`'s candidate used to name its 5 discrimination sites under
one sentence: "no branch installs a hierarchy value … the exhaustive-fold guard".
That was true of `QuorumState.maybeUnattachedState / maybeLeaderState /
maybeProspectiveState`, which fold into `Optional`, and **false** of
`KafkaRaftClient.maybeTransitionForward` and `maybeHandleElectionLoss`. Those two
are genuine transition dispatches whose arms are calls committing beyond the
k = 1 probe (L1, point 3). Since F32 the reason names each group separately:

```
the state is discriminated at 5 site(s), but no commit is proven:
  at 3 site(s) the branches fold into a type outside the hierarchy (Optional) …
      [QuorumState.maybeUnattachedState, QuorumState.maybeLeaderState, QuorumState.maybeProspectiveState];
  at 2 site(s) the branches call methods, and no method body read one call deep writes a field
      of the hierarchy's root type (a commit deeper than one call is possible and is not claimed)
      [KafkaRaftClient.maybeTransitionForward, KafkaRaftClient.maybeHandleElectionLoss]
```

So the output itself now separates the recall limit (depth) from the precision
guard (fold), which is the distinction this section is about.
