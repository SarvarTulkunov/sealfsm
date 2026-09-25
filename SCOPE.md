# The scope boundary, as a positive predicate

_A short note, written to be lifted into the thesis. It states what the tool
proves, not what it fails to do. Derived from `INVESTIGATION.md`, recorded in
`FIXLOG.md` as F26, and revised for the thesis decisions of 24 September 2026
(Decisions 1, 2 and 4; F36 and F37 in `FIXLOG.md`)._

---

SealFSM analyses the sealed roots in the supplied source. **Locating a sealed
declaration is not deciding that it is a machine** (Decision 1). For each root the
tool reaches one of four outcomes, and it makes claims of different strength
about each. Keeping them apart is the point of the tool, because conflating any
two would make a gap in the weakest one unattributable.

> For sealed hierarchies that satisfy the supported recognition patterns, SealFSM
> reports a machine and attempts to extract its transitions. For hierarchies with
> the specified candidate evidence, it reports a candidate without claiming an FSM
> transition relation. For other hierarchies, it abstains.

No claim is made that every real sealed FSM will be recognised.

## 1. Classification: conditional on evidence

A hierarchy is reported as a **machine** only when the code supplies evidence for
a supported transition encoding. That means both of the following:

- **a dispatch.** The current state is discriminated. This can be a switch or an
  `instanceof` chain over the hierarchy, a per-state override (the receiver's type
  selects the body), a typed per-state handler, or a functional callable taking
  the state;
- **a commit.** The chosen successor is shown to *become the current state*
  (§3).

A hierarchy is reported as a **candidate** only when a supported dispatch pattern
supplied plausible evidence and a transition relation could not be established. A
candidate is an uncertain classification. It is **not** a detected FSM, it is
never counted as one, and no diagram is drawn for it. Three kinds of evidence open
the channel, and each is missing something different (`Candidate.Basis`):

| basis | what was found | what is missing |
|---|---|---|
| `COMMIT_UNPROVEN` (F26, F32) | the state is discriminated | a commit: the branches fold into a foreign type, compose, or commit deeper than the probe reads |
| `SOURCE_UNATTRIBUTED` (F27) | a commit, by a switch over the input | a discrimination of the state, so no edge has a source |
| `INSTALLATION_UNSHOWN` (F36) | a value-returning dispatch producing hierarchy values | a caller installing the result as the current state; a conversion cannot be ruled out |

"Plausible" has a floor. A lone typed converter with no caller fixes one source
state, and one discriminated state is not a dispatch, so it is a plain
abstention, not a candidate. An **established conversion** (§3) is never a
candidate: Decision 4 forbids publishing a known conversion's members on either
channel. Otherwise the tool **abstains**, and says why in a diagnostic.

## 2. States: exact over the type structure, for machines

For a hierarchy classified as a machine, its states come from the `permits`
clauses, which the compiler checks, and from the constants of a permitted
`enum`, which are closed in exactly the same way. That enumeration is **complete
by construction**. It is not an analysis result; it is a property of the source.
But it is a statement *about the machine the tool reports*. A rejected sealed
data type is not counted as a recovered machine, and its members are not counted
as recovered FSM states (Decision 1).

Decision 2 separates three levels, and every count names one:

- **direct branches**: the types the root's own `permits` clause names. A
  permitted sealed subtype, a permitted enum and a `non-sealed` member each count
  once;
- **atomic states**: the leaves of the hierarchical expansion. An enum's
  constants are atomic and the enum is not; a nested sealed member contributes
  its own leaves;
- **grouping nodes**: the sealed members and enums between the two.

For `sealed interface Phase permits Idle, Speed` with `enum Speed { SLOW, FAST }`:
direct branches `{Idle, Speed}`, atomic states `{Idle, Speed.SLOW, Speed.FAST}`,
grouping node `Speed`. The exported DOT and SCXML contain the full expansion,
because a successor may name a node at any depth (`return Speed.FAST;`), but the
direct-branch count never absorbs it.

The claim is about **type branches**, not about every class an object may have at
run time. Three conditions weaken it, and each is reported where it applies:

- a `non-sealed` branch is open. Its subclasses are not states. The ones in the
  source set are named in a warning, because a transition method they declare
  is attributed to no state;
- a type reachable under two direct branches is one atomic state, and which
  branch it belongs to is not decided. The overlap is reported, the state is
  counted once, and the export draws it under the first branch that permits it
  (a drawing convention, not a claim);
- a permitted declaration missing from the source set loses its children and
  its own producers (F23).

A candidate carries the same listing **provisionally**: the would-be states
*if* the hierarchy is a machine. That is what an evaluator needs in order to check
the candidate against labels. It is never a recovered state set, and the
machine-readable output keeps it under separate keys.

## 3. Commit: proven from declared types and from where the value goes

For a dispatch over H, the tool must show that a branch's successor **becomes the
current state**. Two kinds of evidence discharge it.

**Installed at the dispatch** (`CommitEvidence.DIRECT`):

* the **assignment target**'s declared type is in H: a state field, or a local
  the host then stores;
* the callee is a **mutator**: one parameter typed with H's root, whose value is
  what the body installs into an H-typed field;
* a per-state method installs into a **context** outside H (F33);
* one callee body the analysis has read writes a field declared with H's
  **root** (the k = 1 probe, `VIA_CALLEE`, used only where the arms are bare
  calls whose value the language discards);
* the host **re-enters itself** with the successor as its next state (a
  run-to-completion driver, F34).

**Returned, and installed by a caller** (`CommitEvidence.VIA_CALLER`, F36):

A value-returning host (a per-state `next()`, a centralized `transition(H, E)`,
a typed handler, a functional callable, or a host returning a carrier of H)
proves only that a hierarchy value is **produced**. A conversion within a sum
type has the same signature and the same body shape:

```java
OrderState handle(OrderState.Placed p) { return new OrderState.Validated(...); } // a transition
Length toFeet(Length.Meters m)         { return new Length.Feet(m.v() * 3.28); } // a conversion
```

Only what happens to the result separates them (Decision 4). The tool follows the
returned value out of its host, through conditionals, switch-expression arms,
locals, returns and arguments, up to six call boundaries, to one of four sinks:

1. an assignment to a field declared with the hierarchy **root** (the probe's own
   clause, so the two cannot disagree about what a state field is);
2. an assignment back into the variable the call read its state from
   (`s = step(s)`, `current = current.next()`);
3. the argument of a recognised **mutator**;
4. the selector argument of a **run-to-completion driver**.

Where the value goes decides the verdict, and the three ways to fail are kept
apart:

| where the value goes | verdict | reported as |
|---|---|---|
| a sink | installed | a machine |
| nowhere: no call to the host exists in the source set | uncertain: missing caller evidence is not proof of a conversion | a provisional candidate, if the sites amount to a dispatch |
| somewhere not followed: a library method, a collection, a lambda, beyond the budget | uncertain | a provisional candidate, if the sites amount to a dispatch |
| every caller uses it as data: read, compared, switched over, discarded | an **established conversion** | rejected; neither channel |

This rule holds at every locus. `examples/converters` holds one converter shape
fixed and varies only the use of its result: stored back (`Tint`, a machine),
used as data (`Shade`, `Currency`, rejected), never called (`Hue`, a candidate).

The requirement is not a formality. It is the tool's precision guard, twice over. A
transition switch and an exhaustive fold are **structurally identical at the
discrimination**, and the codomain separates them. A transition function and a
converter are **structurally identical at the codomain**, and the store-back
separates them.

## 4. Successor identity: approximate, from data flow

*Which* member of H is installed is recovered by intra-procedural data-flow
analysis, with an inter-procedural fold bounded at k = 2. This is a
**value-level** judgement. It is reported with precision and recall, and anything
it cannot establish is recorded as an explicit unresolved edge, never dropped.

## Why the commit and the successor are independent limits

They read different things. The commit reads **codomains, declared types and
where a value is stored**, which are present in the source whether or not the
value flowing into them can be traced. Successor identity reads **expressions**,
which can be arbitrarily opaque, such as a map lookup on a computed key, an
arithmetic index into an array, or a value produced behind a supplier.

The consequence is a category the tool reports as a positive result rather than a
failure: a machine whose dispatch and commit are both established and **none** of
whose successors resolve. Its states are exact, and each dispatched arm is
recorded as an unresolved edge with a **known source state** (`Idle --?--> ?`).

## The outcomes

| | dispatch | commit | successors | reported as |
|---|---|---|---|---|
| **Tier 1** | present | established | at least one resolved | a machine, with a transition relation |
| **Tier 2** | present | established | none resolved | a machine; exact states, one unresolved edge per dispatched arm, each with a known source |
| **Tier 3** | plausible | not established | — | a provisional **candidate**: members listed provisionally, sites named, the missing half named, no relation claimed |
| **conversion** | present | every caller uses the result as data | — | rejected; published on neither channel |
| **abstention** | none, or only a lone uncalled converter | — | — | rejected, with a diagnostic |

The line between Tier 2 and Tier 3 is the commit requirement. Two pairs of
fixtures hold it. `examples/voidcommit` and `examples/voidfold` are
indistinguishable at the call site and differ only in the declared type of the
field their callee writes. `converters.Tint` and `converters.Hue` are the same
code and differ only in whether anything stores the result back.
