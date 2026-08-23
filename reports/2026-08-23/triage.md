# Triage 2026-08-23
tool commit: 2d397ca5438d06b9954f7cdfacc0e7a3e74da292 (short 2d397ca)    run: reports/2026-08-23    findings: 1
freeze status: pre-freeze
classified: FIX-REQUIRED 0 / FIX-ELIGIBLE 0 / DEFERRED 1 / WONTFIX 0
cumulative WONTFIX across all runs: 0 (this is the first run under CORPUS_PROTOCOL.md; no
prior `reports/*/triage.md` exists, so the cumulative figure has no history behind it and
is not yet evidence of gating in either direction)

Inputs read: `reports/2026-08-23/report.md`, `reports/2026-08-23/findings.md`,
`reports/2026-08-23/results.json`, `examples/lcp_automation_chatgpt/meta.json` (the only
`meta.json` under `examples/`), `FIXLOG.md`, `CORPUS_PROTOCOL.md`. No `src/main/`, no
example Java, no oracle content.

`FIXLOG.md` holds zero entries. No node shape has been fixed before, so no finding this
run is a repeat of an earlier special case.

---

## 0. Two classification decisions taken explicitly, not silently

Both bear on the single finding and both are recorded here so the audit does not have to
reconstruct them.

### 0.1 The one finding is reclassified out of the soundness class, and this triage says so

`findings.md` files `centralized-carrier-nondetection` with `soundness-class:
wrong-state-set`. As a `findings.md` field that is `qa-tester`'s record and it stands;
the mapping from that record to a triage class is this document's, and here the two
diverge. The finding is triaged as an **idiom / node-shape gap**, not as a soundness
violation.

The grounds, from the recorded facts and not from any reading of the tool:

- The tool **asserted nothing**. `machines_emitted: 0`, `dot_emitted: false`,
  `scxml_emitted: false`, `states.actual: 0`, `tool_n: 0`. There is no emitted machine
  whose state set could be wrong. A wrong state set is a machine presented with states
  that are not the `permits` clause; a machine absent presents no states.
- The failure was **announced**. `diagnostics_emitted` carries one INFO line recording
  that the hierarchy was skipped and no transition producer was found, and `exit_code: 1`
  is the tool's own "no machines found" path (`crashed: false`, `timed_out: false`).
  §9.5 and this role's own rule name the *silent* plausible-but-incorrect machine as the
  thing that is a wrong state set "never a crash"; the discriminator in both is that
  nothing was said. Here something was said.
- **The 120 `dropped_without_marker` do not hide behind a clean count.** §10 classifies a
  drop with no marker as a soundness violation, and gives the reason in the same
  sentence: it is "the tool presenting an incomplete machine as complete". The letter of
  the field is met and `results.json` records it correctly; the rationale is not, because
  no machine and therefore no count was presented at all. The marker that exists is at
  hierarchy granularity (one INFO line) rather than per transition — that is the whole
  distance between the letter and the rationale here, and it is recorded as an
  observation, not acted on.
- Treating a **detection** miss as a states-exactness violation would collapse the
  discipline. The §0.1 exactness claim is a claim about where states come from — a
  compiler-checked `permits` clause — and it is exercised only once a hierarchy has been
  classified as a machine. Classifier recall is approximate in exactly the way transition
  recovery is. If every non-detection were a soundness violation, then every non-detection
  would take the "always fixed, second fixture not required" route, and §9.3 would gate
  nothing at all. That is the standing bypass this role exists to keep shut.

The measurement is unaffected and is not softened: `states.exact = 0 / n = 1` and
`fn = 120`, `dropped_without_marker = 120` stand exactly as reported. This paragraph
changes what may be *done* about the result, not what the result is.

### 0.2 If it had stayed FIX-REQUIRED, §9.3 would not have gated it — and it still could not be worked this cycle

The tension named in the task is real and resolvable inside the protocol's own text, so
it is resolved here rather than left implicit. §9's triage table makes a wrong state set
`FIX-REQUIRED` and "always fixed", while §9.3 makes every fix conditional on a second
dev fixture. `FIXLOG.md`, restating §9 as binding, carries the reconciliation:

> 3. Every fix requires a second, independently sourced fixture of the same shape, from a
>    different origin project, that also passes afterwards. **A `FIX-REQUIRED` soundness
>    fix is the sole exception** and records `soundness` in the generality-fixture column.

So **FIX-REQUIRED binds over §9.3** where it applies: a soundness claim is unconditional,
so its fix is unconditional too. That is the answer to the tension, and it is recorded so
that the next run does not have to relitigate it.

It does not apply here, for the reason in §0.1. And had it applied, the fix authorised
would still have been a **defensive** one — contain, warn, record affected transitions as
unresolved — because §9.5 confines the unconditional route to containment. The
*behavioural* change this finding actually points at is the recognition of a further
commit position, which is an ordinary idiom gap and goes through §9.3 whichever way the
soundness label falls. The two routes converge on the same answer for this finding, which
is the strongest form the resolution could take.

---

## FIX-REQUIRED

None this run.

`fp = 0` across the corpus: no fabricated edge was recorded. No emitted machine carried a
wrong state set, because no machine was emitted. No crash and no timeout
(`crashed: false`, `timed_out: false` on the one scored example), so no defensive entry
either.

## FIX-ELIGIBLE

None this run.

No finding can reach this class at the present corpus size, and the reasoning is recorded
once here because it will apply unchanged to every finding until the corpus grows.

§9.3 requires "a second, independently sourced fixture of the same node shape, from a
different origin project, **already present in `dev`**". `results.json` records
`corpus.total: 1`, `by_split: {dev: 1, holdout: 0}`. At n = 1 there is no second
protocol-conformant fixture of any shape whatsoever, so the requirement fails on
cardinality before any question of shape or origin is reached.

The 29 directories listed in `pre_protocol_fixtures_excluded` cannot supply one:

- §2 states they "are development fixtures of the tool, not corpus evidence", that they
  do not count toward any figure reported under this protocol, and that a pre-protocol
  fixture enters the corpus only by re-harvest from an upstream origin, oracle first.
- They carry no `meta.json`, so the `harvested_in_response_to` value that §9.3 obliges
  this entry to record does not exist to be recorded, and neither does an origin project
  to check "different origin" against.
- They were not compiled, not run and not scored this cycle, so none of them is known to
  exhibit any shape or to pass anything.
- Independently of all three: `meta.json` records the one corpus example's
  `synthesis_basis` as the **same specification section** underlying one of those
  pre-protocol directories, and describes it as the same automaton in a different idiom.
  Two encodings of one specification by one author are one origin, which is what §9.3's
  "different origin project" clause exists to exclude. It would be the weakest possible
  citation even if the other three objections vanished.

The rule is therefore held rather than waived. A total miss on the only scored example,
on the first run under the protocol, is precisely the moment at which waiving it would
feel most justified and would cost most: a fifth commit position added to the recogniser
on the evidence of one witness is the definition of a fitted pass, and it would be the
first line in an empty `FIXLOG.md`. The finding goes to DEFERRED.

## DEFERRED

### centralized-carrier-nondetection

node shape:
A sealed root type H with two or more permitted subtypes. A method M declared on a type
**outside** H contains a `CtSwitchExpression` whose selector's static type is H. The
switch's own value is **not** typed H: its immediate syntactic context is a `CtReturn`
(or an assignment, or a local declaration) whose target type is a carrier type C, where
C != H, C is not in H, and C declares one component/field of type H. Each arm of that
switch is a plain `CtInvocation` of a further method (delegation, not inlined) that also
returns C; inside those methods a second `CtSwitchExpression` selects on an unrelated
enum type, and its arms produce C by a `CtConstructorCall` of C taking an H-typed
expression — itself typically a `CtConstructorCall` of a permitted subtype — as a direct
constructor argument. The H-typed successor therefore never appears as the value of any
node whose type is H; it appears exactly one constructor-argument level inside the value
of a switch whose selector is H. Distinguishing feature against the four commit positions
already recognised: the switch selecting on H and the site committing an H value are in
different methods and neither, taken alone, is both selector-typed and commit-typed H.

why deferred:
Genuine gap, not a quirk — the node shape is expressible without reference to any
identifier, domain or output, and the combination it names (centralized dispatch,
successor committed as an argument to a non-hierarchy carrier) is a coherent design and
not an accident of one file. But it has exactly one witness in the corpus, and §9.3
admits no fix on one witness. No code change for this finding in this cycle. It is
carried forward: if the requested fixture arrives, this finding is re-triaged then, with
`fixture provenance: harvested-on-demand (request centralized-carrier-nondetection)`
recorded against it — admissible, and explicitly the weaker of the two tiers, because a
fixture found because it was needed is a second point on the same line rather than
independent evidence.

corpus request:

> Wanted: Java (or a JVM language whose sealed-hierarchy equivalent converts) in which a
> **sealed type hierarchy is dispatched from outside itself, and the successor value is
> handed to a wrapper rather than returned, written, or accumulated as the hierarchy type
> directly**.
>
> Concretely, the shape to look for is:
>
> 1. A sealed root type with two or more permitted subtypes, standing for the alternatives
>    of an ongoing process rather than of a data value.
> 2. A method **not** declared on any type in that hierarchy, which switches (or otherwise
>    dispatches exhaustively) on a value of the root type.
> 3. A second, distinct type — a record, a small class, a tuple — that is *not* part of the
>    hierarchy, has one component of the root type, and carries other components alongside
>    it (an effect list, an action, an output, a flag, a log entry, a timer). Call it the
>    carrier.
> 4. The dispatch's arms yield **the carrier**, not the root type. The successor is
>    constructed and passed as a **constructor or factory argument to the carrier**. At no
>    point is a value of the root type returned bare, assigned to a field of the root type,
>    or declared into a local of the root type.
>
> The essential part is (3) + (4): the successor reaching its commit point wrapped, one
> argument level in, from a dispatch hosted outside the hierarchy. Everything else may
> vary freely and variation is welcome — in particular:
>
> - whether the dispatch's arms compute the carrier inline or **delegate to per-alternative
>   helper methods** that build it (both are wanted; a delegating one is the closer match,
>   an inline one is still the same shape and is worth having as the simpler variant);
> - whether a second, inner dispatch on the input/event type sits inside those arms, or
>   the input is tested some other way;
> - whether the carrier is returned by the method, assigned, or passed on;
> - the number of alternatives, the number of inputs, and the domain.
>
> Explicitly **not** wanted, because they are already witnessed or are a different shape:
> per-alternative methods declared *on* the hierarchy that return a carrier (dispatch
> living inside the hierarchy rather than outside it); a dispatch whose arms yield the root
> type directly; and any hierarchy where the wrapper's component is the *same* hierarchy
> used to build a bigger value of it (a tree or expression node composed of sub-nodes),
> which is a recursive data type and not a process.
>
> Must come from a **different origin project** than anything currently in the corpus, and
> must not be another rendering of a specification the corpus already draws on. A version
> in a language other than Java is preferred over a second Java one from the same
> ecosystem. Oracle first, from the upstream specification or documentation where one
> exists; if the oracle can only be read off the implementation, say so and it is recorded
> in the weak provenance tier.

## WONTFIX

None this run.

Recorded deliberately, and not as a formality. Nothing was classified here because the
single finding's node shape is stated abstractly, holds no example-specific identifier,
and names a design that could plausibly recur — the test for WONTFIX is "does not
generalise", and there is no evidence for that proposition; there is merely no evidence
yet for its negation either, which is what DEFERRED is for. Filing it WONTFIX would have
converted an unmeasured gap into an asserted §5.8 limitation on one witness, which is the
mirror image of the fitting error and equally unearned. The gate this run was held at
DEFERRED, and at the declined FIX-REQUIRED in §0.1, not here.

## Out of scope

No holdout failures observed, and none could have been: `results.json` records
`scope: "dev"`, `holdout_unsealed: false`,
`invoking_instruction_contained_unseal_holdout_token: false`, and
`corpus.by_split.holdout: 0`. The holdout split was not compiled, run or scored, and at
present contains no protocol-conformant example. Nothing in this triage rests on holdout
content, and no fixture cited or requested above may later be satisfied from the holdout
(§9.3: a holdout example may never serve as a generality fixture, since citing one
requires reading it and reading it unseals it).

Two corpus-level conditions are noted as observed and out of this document's scope to
act on, since neither is a finding and neither is triable: the §8 negative quota is unmet
(`negative_class.n: 0`), and `regression_vs.previous_run` is null so no example is
classified as regressed, fixed, or unchanged-failing this run.
