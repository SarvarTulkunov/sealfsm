# The Tier 3 candidate channel — what it actually contains

_Written to be lifted into the validation chapter, in the same spirit as
`CENSUS.md`. The question it answers is not "does the candidate channel work"
but "what is in it", and it is answered by enumerating every entry the corpus
produces, not by argument._

---

## Why this table exists

The tool makes two claims of different strength: state enumeration is exact
(`permits` is compiler-checked), transition extraction is approximate. Tier 3 is
what keeps them independent — a hierarchy the tool refuses to call a machine
still reports its complete state set.

That refusal is easy to misread. **A candidate is not "a state machine the tool
failed on".** It is a hierarchy over which *some* evidence was found and the rest
was not, and the channel deliberately takes no position on whether the thing is a
machine. So the obvious question — *how much of the candidate channel is real
recall loss?* — has to be answered by looking at all sixteen entries, because the
channel's own verdict does not answer it.

The answer is **two of seventeen**.

## Method

Enumerated from `target/golden-f32-post/*/summary.txt` over all 52 `examples/`
fixtures (`bash scripts/capture-golden.sh`), not written by hand. The "kind"
column is read off each candidate's own printed reason, which
`Analyzer.recordCandidate` composes from the evidence it actually found. Since
F32 that reason says, site by site, *which* evidence was missing:

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
- **Σ-major**: the opposite gap (F27). A hierarchy value **is** committed, but
  the state itself is discriminated nowhere, so no successor can be attributed to
  a source state.

The ground-truth column is not read from the tool. It is the documented intent of
each fixture (`CLAUDE.md`), checked against the source.

## The seventeen

| # | fixture | candidate | states | kind | what it really is | recall loss? |
|---|---|---|---|---|---|---|
| 1 | `dhcp-client-claude` | `dhcpclaude.DhcpEvent` | 8 | fold → `DhcpState` | **Σ of `DhcpState`** | no |
| 2 | `eventalphabet` | `examples.eventalphabet.Event` | 4 | fold → `Player` | **Σ of `Player`** | no |
| 3 | `ffmpeg` | `…FfmpegStatusStateMachine$FfmpegEvent` | 5 | fold → `ComponentState` | **Σ of `ComponentState`** | no |
| 4 | `gofcontext` | `examples.gofcontext.Event` | 2 | calls | **Σ of `Portal`** | no |
| 5 | `guardforms` | `guardforms.Trigger` | 20 | fold → `Signal` | **Σ of `Signal`** | no |
| 6 | `http2-stream-claude` | `http2.StreamEvent` | 2 | fold → `StreamState` | **Σ of `StreamState`** | no |
| 7 | `lcp_automation` | `lcp.LcpEvent` | 13 | fold → `LcpState` | **Σ of `LcpState`** | no |
| 8 | `websocket-claude` | `websocket.WebSocketEvent` | 4 | fold → `WebSocketState` | **Σ of `WebSocketState`** | no |
| 9 | `foreignfold` | `examples.foreignfold.Mode` | 3 | fold → `String`, `int` | negative control — folds into `String` | no |
| 10 | `voidfold` | `voidfold.Hopper` | 4 | calls | negative control — callee writes a `String` field | no |
| 11 | `emptycandidate` | `emptycandidate.Channel` | 7 | fold → `String` | negative control — folds into `String`, composite states | no |
| 12 | `chaindispatch` | `chaindispatch.Glyph` | 2 | fold → `String` | negative control — codomain, chain spelling | no |
| 13 | `chaindispatch` | `chaindispatch.Tree` | 2 | composition | negative control — composition, declares no methods | no |
| 14 | `unreadablecallee` | `unreadablecallee.Shutter` | 3 | unreadable calls **+ F11** | control — callee not in the source set; verdict *unknown*, not *no* | n/a |
| 15 | `nestedroots` | `nestedroots.Message` | 5 | calls | parent that abstained; the machine is the re-offered child `Body` | no |
| 16 | `eventmajor` | `eventmajor.Link` | 4 | **Σ-major** | **a real machine** the tool declines to report a relation for | **yes** |
| 17 | `deepcommit` | `deepcommit.Phase` | 3 | fold → `String` **and** calls | **a real machine** whose commit is two calls deep, beside a real fold | **yes** |

Every one of rows 1–8 sits in a fixture that *also* yields an accepted machine,
and is that machine's event type — verified by reading the machine list of each
fixture, not inferred from the name.

## Reading

| group | n | |
|---|---|---|
| event alphabets Σ, correctly not machines | 8 | 47% |
| documented negative controls, correctly not machines | 5 | 29% |
| parent hierarchy whose child is the machine | 1 | 6% |
| source set incomplete — verdict withheld (F11) | 1 | 6% |
| **real machine, relation not claimed** | **2** | **12%** |

**Fifteen of seventeen candidates are things that are genuinely not state
machines, or are not a verdict at all.** The channel is not a pile of failures.
Its dominant population is the event alphabet Σ — which is itself a sealed
hierarchy, is switched over exhaustively, and therefore satisfies "dispatch
present" by construction while never committing a state.

That is a result in its own right, and it is the sharpest available statement of
where the tool's line falls: **the tool can name the states of a sealed hierarchy
in every case where it can find a discrimination over it, and the commit is the
only thing separating a transition table from a fold.** Σ is what a fold over a
closed type looks like from the outside, and the codomain check is what keeps it
out of `machines()`.

The two genuine gaps fail for different reasons, and since F32 the output says
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

## Threats to validity, stated

- **The corpus is synthetic.** Every fixture is hand-written or LLM-generated
  from a specification. The proportions above are properties of the corpus, not
  of Java, and they would change under a harvest of real projects. What does
  *not* depend on the corpus is the direction of the finding: an event alphabet
  is a candidate for a structural reason (a sealed type switched over, committing
  nothing), so it will be the dominant population in any corpus of sealed FSMs.
- **Row 17 was added by F32's fixture**, so the "two of seventeen" figure
  includes a row written to exhibit the gap. Without it the corpus figure is
  one of sixteen, as before. The real-world instance is Kafka's, and it is outside
  the corpus.
- **Row 14 is not a "no".** `unreadablecallee` withholds a verdict because the
  callee was not in `--src`. Counting it as a correct rejection would overstate
  precision; counting it as recall loss would overstate the gap. It is reported
  separately for that reason, and the callee is named in the output so the
  condition is actionable.
- **The ground-truth column is intent, not an oracle.** It is the documented
  purpose of each fixture, checked against the source by reading. For rows 1–8 it
  is corroborated structurally (each is the Σ of an accepted machine in the same
  fixture); for rows 9–13 it is the fixture's stated design.
