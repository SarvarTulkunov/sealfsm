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

This hierarchy was also the source of **F30** (see `FIXLOG.md`). Before that fix,
its `static empty()` factory was read as a per-state transition method, and the
hierarchy was published as a one-edge machine sourced at the root interface. That
was a false positive, and it is fixed. The limitation above is what remains
once the false positive is gone.

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
