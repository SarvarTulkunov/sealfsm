# examples/nestedroots — a machine declared INSIDE a hierarchy that is not one

`SealedHierarchyDetector` withholds a sealed permitted subtype from the root
list because its parent claims it as a *composite state*. That claim is only
good if the parent turns out to be a machine — and the detector runs before
classification, so it cannot know. When the parent is then rejected, the child
was lost with it and the only diagnostic named the parent.

Three hierarchies, holding the shape fixed and varying only what the parent's
rejection *means*.

| hierarchy | parent's rejection | nested sealed child | expected |
|---|---|---|---|
| `Message permits Header, Body` | ABSTAINED — nothing returns a `Message` value | `Body`, `@Fsm`-marked, mutation-encoded | **re-offered and extracted**: 3 states, 3/3 |
| `Envelope permits Stamp, Contents` | ABSTAINED | `Contents`, not a machine either | re-offered, rejected, **named in its own diagnostic** |
| `Node permits Leaf, Branch` | VETOED — compositional veto | `Branch`, a tree builder | **not re-offered** |

## Why `Body` needs the marker

Every value-producing recognizer asks "does something produce a value whose
type is in H?", and H(child) is a subset of H(parent). So *any* producer that
classifies the child positively also classifies the parent — a returning child
machine keeps its parent accepted, and is reported as a composite state rather
than lost. The parent only abstains when the child's producer returns nothing:
the F2 mutation encoding, which is exactly the case that has to opt in with a
marker. That makes this the sharp version of the defect — the author wrote
`@Fsm` on `Body` in as many words, and it was still never classified.

## Why `Node`/`Branch` is the load-bearing control

The compositional veto is judged against the hierarchy set of whichever root is
being classified, and a child's set is strictly narrower than its parent's. In
`Pair.replaceChild(Node child)`, `new Wrap(child)` puts a hierarchy value inside
another hierarchy value **as far as `Node` is concerned** — `child` is typed
`Node`. Judged against `{Branch, Pair, Wrap}` that argument is a foreign type,
the production reads as an ordinary peer, and the veto evaporates. Since
`replaceChild` returns `Branch`, the distributed recognizer then accepts it: a
tree builder reported as an automaton, one level below the veto that caught it.

That is not a prediction — classify `Branch` directly and it answers
`isFSM=true, POLYMORPHIC, 3 per-state transition method(s)`. The veto on `Node`
is the only thing keeping it out of the output.

The child arrives as a **parameter**, not as `this.left`, and that detail is
load-bearing. Spoon models the receiver of a field read as a `CtThisAccess`,
which the nesting test counts as a hierarchy value unconditionally (so
`return this;` reads as a self-loop). A `new Wrap(this.left)` would therefore
look nested in *any* hierarchy set, narrowed or not, and the control would pass
without testing anything. A parameter read carries only its declared type.

## The scope line

Only an ABSTENTION releases a nested hierarchy. A veto is a verdict about the
data type and its members inherit it; an abstention is not a verdict at all. A
machine genuinely nested inside a compositional hierarchy therefore stays lost —
a recall gap, and closing it means judging the child against its widest
enclosing hierarchy, which is a change to the veto, not to the worklist.
