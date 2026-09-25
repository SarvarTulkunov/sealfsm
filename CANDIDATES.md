# The Tier 3 candidate channel — what it actually contains

_Written to be lifted into the validation chapter, in the same spirit as
`CENSUS.md`. The question it answers is not "does the candidate channel work"
but "what is in it", and it is answered by enumerating every entry the corpus
produces, not by argument. Re-measured for the thesis decisions of 24 September
2026 (F36, F37). A candidate is now explicitly PROVISIONAL (Decision 1), a third
kind of evidence can open the channel (`INSTALLATION_UNSHOWN`, Decision 4), and
member counts are atomic states (Decision 2)._

---

## Why this table exists

The tool makes claims of different strength. State enumeration is exact over
the type structure (`permits` is compiler-checked), and transition extraction is
approximate. Tier 3 keeps them independent: a hierarchy the tool refuses to call
a machine still lists, provisionally, the members it would have as states. That
listing is never counted as recovered FSM states (Decision 1).

That refusal is easy to misread. **A candidate is not "a state machine the tool
failed on".** It is a hierarchy over which *some* evidence was found and the rest
was not, and the channel deliberately takes no position on whether the thing is a
machine. So the obvious question, *how much of the candidate channel is real
recall loss?*, has to be answered by looking at every entry, because the
channel's own verdict does not answer it.

The answer is **four of twenty-one**.

## Method

Enumerated from `target/golden-f36b/*/summary.txt` over all 57 `examples/`
fixtures (`bash scripts/capture-golden.sh`), not written by hand. The "basis"
column is the candidate's own `Candidate.basis()`. The "kind" column is read off
its printed reason, which `Analyzer.recordCandidate` composes from the evidence
it actually found and which says, site by site, *which* evidence was missing:

- **state-major**: the state IS discriminated, and no commit is proven. There are
  four sub-kinds, and before F32 all four printed the fold sentence:
  - **fold → T**: the branches produce values of a type T outside the hierarchy.
    This is the exhaustive-fold guard: a transition switch and a fold are
    identical *at* the discrimination, and only the codomain separates them.
  - **composition**: the branches install hierarchy values that nest another
    hierarchy value, and the compositional veto rejects that as building a data
    structure.
  - **calls**: the branches call methods, and no method body read one call deep
    writes a field of the hierarchy's root type. This does not claim there is
    no commit, only that none is within the probe's depth.
  - **unreadable calls**: the branches call methods whose bodies could not be
    read (F11).
- **Σ-major** (`SOURCE_UNATTRIBUTED`): the opposite gap (F27). A hierarchy value
  **is** committed, but the state itself is discriminated nowhere, so no successor
  can be attributed to a source state.
- **uninstalled** (`INSTALLATION_UNSHOWN`, F36): value-returning sites produce
  hierarchy values, and no caller in the source set is shown storing the result
  back as the current state. The sub-kinds are **no caller** and **not
  followed** (the value is handed to a library method, a collection or a lambda).
  A conversion and a state update cannot be told apart here (Decision 4).

The state-major sub-kinds all carry basis `COMMIT_UNPROVEN`.

The ground-truth column is not read from the tool. It is the documented intent of
each fixture (`CLAUDE.md`), checked against the source.

## The twenty-one

| # | fixture | candidate | atomic members | basis | kind | what it really is | recall loss? |
|---|---|---|---|---|---|---|---|
| 1 | `dhcp-client-claude` | `dhcpclaude.DhcpEvent` | 8 | COMMIT_UNPROVEN | fold → `DhcpState` | **Σ of `DhcpState`** | no |
| 2 | `eventalphabet` | `examples.eventalphabet.Event` | 4 | COMMIT_UNPROVEN | fold → `Player` | **Σ of `Player`** | no |
| 3 | `ffmpeg` | `…FfmpegStatusStateMachine$FfmpegEvent` | 5 | COMMIT_UNPROVEN | fold → `ComponentState` | **Σ of `ComponentState`** | no |
| 4 | `gofcontext` | `examples.gofcontext.Event` | 2 | COMMIT_UNPROVEN | calls | **Σ of `Portal`** | no |
| 5 | `guardforms` | `guardforms.Trigger` | 20 | COMMIT_UNPROVEN | fold → `Signal` | **Σ of `Signal`** | no |
| 6 | `http2-stream-claude` | `http2.StreamEvent` | 2 | COMMIT_UNPROVEN | fold → `StreamState` | **Σ of `StreamState`** | no |
| 7 | `lcp_automation` | `lcp.LcpEvent` | 13 | COMMIT_UNPROVEN | fold → `LcpState` | **Σ of `LcpState`** | no |
| 8 | `websocket-claude` | `websocket.WebSocketEvent` | 4 | COMMIT_UNPROVEN | fold → `WebSocketState` | **Σ of `WebSocketState`** | no |
| 9 | `foreignfold` | `examples.foreignfold.Mode` | 3 | COMMIT_UNPROVEN | fold → `String`, `int` | negative control — folds into `String` | no |
| 10 | `voidfold` | `voidfold.Hopper` | 4 | COMMIT_UNPROVEN | calls | negative control — callee writes a `String` field | no |
| 11 | `emptycandidate` | `emptycandidate.Channel` | 5 | COMMIT_UNPROVEN | fold → `String` | negative control — folds into `String`; 3 direct branches, 2 grouping nodes | no |
| 12 | `chaindispatch` | `chaindispatch.Glyph` | 2 | COMMIT_UNPROVEN | fold → `String` | negative control — codomain, chain spelling | no |
| 13 | `chaindispatch` | `chaindispatch.Tree` | 2 | COMMIT_UNPROVEN | composition | negative control — composition, declares no methods | no |
| 14 | `unreadablecallee` | `unreadablecallee.Shutter` | 3 | COMMIT_UNPROVEN | unreadable calls **+ F11** | control — callee not in the source set; verdict *unknown*, not *no* | n/a |
| 15 | `nestedroots` | `nestedroots.Message` | 4 | COMMIT_UNPROVEN | calls | parent that abstained; the machine is the re-offered child `Body` | no |
| 16 | `eventmajor` | `eventmajor.Link` | 4 | SOURCE_UNATTRIBUTED | **Σ-major** | **a real machine** the tool declines to report a relation for | **yes** |
| 17 | `deepcommit` | `deepcommit.Phase` | 3 | COMMIT_UNPROVEN | fold → `String` **and** calls | **a real machine** whose commit is two calls deep, beside a real fold | **yes** |
| 18 | `typedhandler` | `typedhandler.OrderState` | 6 | INSTALLATION_UNSHOWN | no caller | **a real pipeline** written without its driver: the decision's missing-caller case | **yes** |
| 19 | `cancellation` | `…CancellationRequests$CancellationState` | 2 | INSTALLATION_UNSHOWN | not followed (`AtomicReference.accumulateAndGet`) | **a real machine** that installs through a library container (L2) | **yes** |
| 20 | `typedhandler` | `typedhandler.Length` | 2 | INSTALLATION_UNSHOWN | no caller | two unit converters: uncertain *to the tool*, a converter by intent | no |
| 21 | `converters` | `converters.Hue` | 2 | INSTALLATION_UNSHOWN | no caller | the missing-caller control: the same code as a machine (`Tint`) and a conversion (`Shade`) | n/a |

Every one of rows 1–8 sits in a fixture that *also* yields an accepted machine,
and is that machine's event type — verified by reading the machine list of each
fixture, not inferred from the name.

**What is NOT on the channel, by design.** Established conversions
(`converters.Shade`, `converters.Currency`, `typedhandler.Temperature`) are
rejected outright: Decision 4 forbids publishing a known conversion's members on
either channel. A lone uncalled converter (`typedhandler.Shape`) is a plain
abstention, because one discriminated state is not a dispatch.

## Reading

| group | n | |
|---|---|---|
| event alphabets Σ, correctly not machines | 8 | 38% |
| documented negative controls, correctly not machines | 5 | 24% |
| parent hierarchy whose child is the machine | 1 | 5% |
| source set incomplete — verdict withheld (F11) | 1 | 5% |
| uncertain by construction (no caller, no evidence either way) | 2 | 10% |
| **real machine, relation not claimed** | **4** | **19%** |

**Seventeen of twenty-one candidates are genuinely not state machines, or are
not a verdict at all.** The channel is not a pile of failures. Its dominant
population is the event alphabet Σ, which is itself a sealed hierarchy, is
switched over exhaustively, and so satisfies "dispatch present" by construction
while never committing a state.

That is a result in its own right. It is the sharpest available statement of
where the tool's line falls: **the tool can name the would-be states of a sealed
hierarchy wherever it finds a dispatch over it, and only the commit separates a
transition table from a fold or a conversion.** The commit is the codomain, plus,
for a returned successor, its store-back. Σ is what a fold over a closed type
looks like from the outside, and the codomain check keeps it out of `machines()`.

The four genuine gaps fail for four different reasons, and the output says
which.

`eventmajor.Link` is measured rather than merely admitted: `CENSUS.md` counts 25 Σ-major against 9 state-major closed-type state
fields across JDK 21 and 145 library jars, so the shape is real and common. What
is missing is not recognition — the dispatch sites are found and named — but
*attribution*: a Σ-major arm matches no state, so sourcing its edges would mean
reasoning about what the state field held on entry, which is a flow-sensitive
inter-procedural question the tool does not ask anywhere. The honest report is
the one it gives: states exact, sites named, no relation claimed.

`deepcommit.Phase` is the documented depth bound of the commit-existence probe.
`Kiln.tick()` is a real transition table whose arms call a helper that calls a
mutator, so the commit is two calls deep, and the probe reads exactly one. It is
the shape found on Apache Kafka's KRaft (`KafkaRaftClient.maybeTransitionForward`,
whose commit lies five calls away, on another object). Before F32 it was
reported under the fold sentence, next to its genuine fold `Kiln.describe()`, so
the recall gap looked like the precision guard working. The reason now reads
"fold → String [Kiln.describe]; calls [Kiln.tick]", and the "calls" sub-kind is
worded so that it never claims a commit is absent. Of the four candidates with a
"calls" site (rows 4, 10, 15, 17), only row 17 is a machine of that hierarchy:
the sub-kind marks where a gap *may* be, not where one is.

`typedhandler.OrderState` and `cancellation` are the price of Decision 4, and they
fail for different reasons. `OrderState` has no caller at all. It is the same
pipeline as `examples/orchestrator` without its run-to-completion driver, and its
handlers are indistinguishable from `Length`'s converters. The decision says to
abstain there, and the evaluation protocol counts the abstention against the
labelled ground truth. `cancellation` has callers, but it hands its callables to
`AtomicReference.accumulateAndGet`, a library method whose body the tool never
reads. Treating that method as an installation would be a modelling decision,
and L2 declines it. Its in-model twin, `examples/functionaldriver`, is a
machine.

## Threats to validity, stated

- **The corpus is synthetic.** Every fixture is hand-written or LLM-generated
  from a specification. The proportions above are properties of the corpus, not
  of Java, and they would change under a harvest of real projects. What does
  *not* depend on the corpus is the direction of the finding: an event alphabet
  is a candidate for a structural reason (a sealed type switched over, committing
  nothing), so it will be the dominant population in any corpus of sealed FSMs.
- **Rows 17, 18, 20 and 21 were added by findings' fixtures** (F32, F35/F36), so
  the "four of twenty-one" figure includes rows written to exhibit a gap. The
  real-world instances are outside the corpus. Measuring them is what the
  evaluation protocol (`evaluation/PROTOCOL.md`) is for.
- **Member counts are atomic states** (Decision 2). Two rows changed in the count
  alone: `emptycandidate.Channel` (7 nodes, 5 atomic members) and
  `nestedroots.Message` (5 nodes, 4 atomic members).
- **Row 14 is not a "no".** `unreadablecallee` withholds a verdict because the
  callee was not in `--src`. Counting it as a correct rejection would overstate
  precision; counting it as recall loss would overstate the gap. It is reported
  separately for that reason, and the callee is named in the output so the
  condition is actionable.
- **The ground-truth column is intent, not an oracle.** It is the documented
  purpose of each fixture, checked against the source by reading. For rows 1–8 it
  is corroborated structurally (each is the Σ of an accepted machine in the same
  fixture); for rows 9–13 it is the fixture's stated design.
