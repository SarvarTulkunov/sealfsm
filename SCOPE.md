# The scope boundary, as a positive predicate

_A short note, written to be lifted into the thesis. It states what the tool
proves, not what it fails to do. Derived from `INVESTIGATION.md` and recorded in
`FIXLOG.md` as F26._

---

SealFSM makes three claims about a sealed hierarchy H, and they rest on different
evidence and carry different strength. Keeping them apart is the point of the
tool; conflating any two of them would make a gap in the weakest unattributable.

## 1. State enumeration — exact, and unconditional

Every permitted subtype of H is a state of the automaton, and a permitted `enum`
contributes its constants as child states. The `permits` clause is checked by the
compiler and an enum's constants are closed in exactly the way it is, so the state
set *Q* is **complete by construction**. This is not an analysis result; it is a
property of the source, and it holds whether or not any transition is recovered.

The tool reports *Q* for every hierarchy it examines. Where a transition producer
is recognised, *Q* is the machine's state set. Where none is — but the state is
nevertheless discriminated somewhere in the program — *Q* is reported on a
separate **candidate** channel, together with the dispatch sites found and the
reason no commit could be proven. A candidate is explicitly not a machine: no
transition relation is claimed for it and no diagram is drawn.

## 2. Commit existence — proven, from declared types

For a dispatch over H, the tool proves that a branch **installs** a value of H.
The proof is a **type-level** judgement and is made without evaluating any
expression. It is discharged by exactly one of:

* the host method's **codomain** is in H (`return switch (state) { … }`), or is a
  wrapper type with exactly one H-typed component;
* the **assignment target**'s declared type is in H — a field, or a local
  subsequently committed;
* the callee of a call is a **mutator**: one parameter typed with H's root, whose
  value is what the body installs into an H-typed field;
* one callee body the analysis has read contains a write to a field declared with
  H's **root** (the k = 1 commit-existence probe, used only where the dispatch's
  arms are bare calls whose value the language discards).

The requirement is not a formality; it is the tool's entire precision guard. A
transition switch and an exhaustive fold are **structurally identical at the
discrimination** — `switch (state)` looks the same whether its arms yield the next
state or a log string — so the codomain is the only thing that separates them.
Every sealed sum type in Java is eventually switched over; a classifier that
accepted discrimination alone would report `String describe(Shape s)` as a
three-state automaton, and its precision figure would mean nothing.

## 3. Successor identity — approximate, from data flow

*Which* member of H is installed is recovered by intra-procedural data-flow
analysis, with an inter-procedural fold bounded at k = 2. This is a
**value-level** judgement, it is reported with precision and recall, and anything
it cannot establish is recorded as an explicit unresolved edge — never dropped.

## Why (2) and (3) are independent limits

They read different things. Commit existence reads **codomains and declared
types**, which are present in the source whether or not the value flowing into
them can be traced. Successor identity reads **expressions**, which can be
arbitrarily opaque — a map lookup on a computed key, an arithmetic index into an
array, a value produced behind a supplier — while the declared type of the slot
they land in stays perfectly legible.

The consequence is a category the tool reports as a positive result rather than a
failure: a machine whose dispatch and commit are both established and **none** of
whose successors resolve. Its states are exact, and each dispatched arm is
recorded as an unresolved edge with a **known source state** — `Idle --?--> ?`,
never an empty relation, because the arm that matched is the source and losing it
would be a transition dropped behind a clean-looking score.

Because the two limits are independent, no relaxation of one implies anything
about the other. Extending the data-flow budget does not widen what counts as a
commit, and widening where a commit may be observed does not resolve a single
successor. The tool records which of the two established each machine's commit
(`DIRECT`, observed in the dispatch's own syntactic context; `VIA_CALLEE`, proven
by opening one callee body) so that a later precision or recall gap can be
attributed to the judgement that actually caused it.

## The three outcomes

| | dispatch | commit | successors | reported as |
|---|---|---|---|---|
| **Tier 1** | present | proven | at least one resolved | a machine, with a transition relation |
| **Tier 2** | present | proven | none resolved | a machine; complete states, one unresolved edge per dispatched arm, each with a known source |
| **Tier 3** | present | **not** proven | — | a **candidate**: complete states, dispatch sites named, no relation claimed |

The line between Tier 2 and Tier 3 is the boundary this note exists to state, and
it is exactly the commit requirement. It is held by a pair of fixtures that are
indistinguishable at the call site — the same sealed hierarchy, the same
exhaustive dispatch, the same method name, arity and argument — and differ only in
the declared type of the field their callee writes. One is a machine; the other is
a candidate. Nothing else about the two programs separates them, and nothing else
should.
