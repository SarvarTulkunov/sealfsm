# ORACLE — `lcp_automation_chatgpt`

## Source

RFC 1661, *The Point-to-Point Protocol (PPP)*, W. Simpson (ed.), STD 51, July 1994.

- **Section 4. The Option Negotiation Automaton** (page 11) — the event and action
  vocabulary.
- **Section 4.1. State Transition Table** (pages 12-13) — the complete relation. The
  table is printed in two halves: states 0-5 on page 12, states 6-9 on page 13. Both
  halves are transcribed here.
- **Section 4.2. States** (pages 14-15) — consulted only for the state descriptions and
  the Stopped-state Implementation Option that footnote `[p]` refers to.
- **Section 4.3. Events** (pages 16 onward) — consulted only for the Open-event and
  RCA-event Implementation Options that footnotes `[r]` and `[x]` refer to.

Retrieved from `https://www.rfc-editor.org/rfc/rfc1661.txt`.

## Authoring order

This oracle was written **before** the implementation in `../java/` was read. The RFC
table was transcribed cell-by-cell into a generator script, and `states.txt` /
`transitions.tsv` were produced from that transcription. `oracle_provenance` is
`external-spec` and `oracle_authored_before_conversion` is `true`, and both describe what
actually happened: the only inputs to Steps 1 and 2 were the RFC text and the directory
listing of the example (file *names* only, no file contents).

The state set, the event alphabet and `transitions.tsv` were frozen at that point. The
final section of this file, "Divergences", is an append-only record written afterwards,
as Section 4 of `CORPUS_PROTOCOL.md` requires; nothing above it was revised.

## The state set

The RFC numbers its states 0-9 and names them in the table's column headings:

| # | RFC 4.1 heading | oracle name |
|---|---|---|
| 0 | Initial   | `Initial`  |
| 1 | Starting  | `Starting` |
| 2 | Closed    | `Closed`   |
| 3 | Stopped   | `Stopped`  |
| 4 | Closing   | `Closing`  |
| 5 | Stopping  | `Stopping` |
| 6 | Req-Sent  | `ReqSent`  |
| 7 | Ack-Rcvd  | `AckRcvd`  |
| 8 | Ack-Sent  | `AckSent`  |
| 9 | Opened    | `Opened`   |

The state set is exactly these ten. Only the hyphen is removed, because a hyphen cannot
occur in a Java identifier and the state name must be comparable against a type name
under Section 6 of the protocol.

**Spec-internal naming inconsistency (recorded, not resolved).** Section 4.1's table
headings read `Req-Sent` and `Ack-Rcvd`; Section 4.2's prose headings for the same two
states read `Request-Sent` and `Ack-Received`. The table is the normative artefact for
this oracle, so the table's spellings are used. This is a difference in spelling only —
the two sections describe the same ten states, and no transition depends on the choice.

## The event alphabet

Sixteen events, taken from Section 4 (page 11) and used as the table's row labels in
Section 4.1:

`Up`, `Down`, `Open`, `Close`, `TO+`, `TO-`, `RCR+`, `RCR-`, `RCA`, `RCN`, `RTR`, `RTA`,
`RUC`, `RXJ+`, `RXJ-`, `RXR`.

`transitions.tsv` uses these RFC tokens **verbatim**, including the `+` and `-` suffixes.
No Java identifier can carry `+` or `-`, so any implementation must respell them. The
oracle deliberately does not guess the respelling; it fixes the normalisation instead:

> **Event normalisation for comparison.** Case is not significant. A trailing `+` in the
> RFC token corresponds to a trailing `PLUS`/`Plus`, and a trailing `-` to a trailing
> `MINUS`/`Minus`, joined by `_` or by nothing. Thus `RCR+` is `RCR_PLUS` is `RcrPlus`,
> and `TO-` is `TO_MINUS` is `ToMinus`. No other event respelling is admitted.

If a comparison harness cannot apply this mapping, event matching for this example is
`degraded` in the sense of Section 10 and must be reported as such — it must **not** be
silenced by rewriting this file.

The RFC's own Section 4 groups several packet types under one event on purpose
("Configure-Naks and Configure-Rejects ... are not differentiated in the automaton
descriptions ... they always cause the same transitions"). The alphabet is therefore
sixteen symbols and not more; splitting `RCN` back into Nak and Rej would be an addition
to the spec, not a reading of it.

`TO+`/`TO-`, `RCR+`/`RCR-` and `RXJ+`/`RXJ-` are three pairs that a Java encoding may
plausibly represent as one symbol plus a boolean guard (counter expired, request
acceptable, reject catastrophic). The oracle takes the RFC's position that these are
**six distinct events**, because the RFC lists them as six distinct rows. An
implementation that merges each pair into a guarded symbol is encoding the same relation
in a different alphabet; that is an event-alphabet divergence, recorded under
"Divergences" below, never absorbed by editing this table.

## The transition relation

`transitions.tsv` holds all **120** transitions. The derivation is mechanical and fully
checkable:

- 16 events x 10 states = **160 cells** in Section 4.1.
- **40 cells** hold `-`, which Section 4.1 defines as "an illegal transition". These
  contribute **no edge**. They are: `Up` in states 2-9 (8 cells), `Down` in states 0-1
  (2), `TO+` and `TO-` in states 0, 1, 2, 3, 9 (5 each, 10 total), and each of `RCR+`,
  `RCR-`, `RCA`, `RCN`, `RTR`, `RTA`, `RUC`, `RXJ+`, `RXJ-`, `RXR` in states 0-1 (20).
- The remaining **120 cells** each give exactly one edge.

**67 of the 120 are self-loops.** That is not an artefact: Section 4.1 writes "this event
is ignored in this state" as an explicit stay-put cell (`Closed`/`RXR` -> `2`), and
dropping those cells would silently change the automaton from total on its legal domain
to partial. They are transitions and they are in the oracle.

Out-degrees: `Initial` 3, `Starting` 3, `Closed` 13, `Stopped` 13, `Closing` 15,
`Stopping` 15, `ReqSent` 15, `AckRcvd` 15, `AckSent` 15, `Opened` 13.
Arity profile: `2x3,3x13,5x15`.

The `note` column carries, for every row, the RFC's action list, any footnote letter, and
the raw cell text as printed, so each row can be checked against the RFC without
re-deriving it.

## What the oracle deliberately omits

- **Actions.** The RFC writes each cell as `action/new-state` (`tlu`, `tld`, `tls`,
  `tlf`, `irc`, `zrc`, `scr`, `sca`, `scn`, `str`, `sta`, `scj`, `ser`). SealFSM's
  transition IR has no action field, so actions are recorded in the `note` column as
  documentation and are **not** part of the compared triple. No two cells sharing a
  (source, event) pair exist, so this cannot merge two distinct transitions.
- **The Restart timer, Restart counter, Max-Terminate and Max-Configure** (Section 4.6).
  These are the extended state that distinguishes `TO+` from `TO-` and are outside a
  finite-state reading of Section 4.1.
- **Guards.** The oracle records no guard expressions. The `+`/`-` distinction is carried
  in the event symbol, exactly as the RFC carries it.
- **An initial state.** Section 4.1 numbers `Initial` 0 and Section 4.2 describes it as
  "the lower layer is unavailable (Down), and no Open has occurred", which is the
  automaton's starting condition. The RFC draws no entry arrow, and every state including
  `Initial` has incoming edges (`Closed`/`Down` -> 0, `Closing`/`Down` -> 0,
  `Initial`/`Close` -> 0, `Starting`/`Close` -> 0), so `Initial` cannot be recovered
  structurally. The oracle asserts `Initial` is the start state on the strength of
  Section 4.2's description and the table's numbering, but does not encode it in
  `transitions.tsv`, and no scoring depends on it.

## Ambiguities in the specification, and how they were read

Three cells carry footnote letters. All three are **Implementation Options**, that is,
the RFC's own permitted deviations from the table. In every case the oracle takes the
**table's literal cell** and records the option.

1. **`[r]` — Restart option** (`Open` in states 3, 4, 5, 9: `3r`, `5r`, `5r`, `9r`).
   Section 4.3's Open-event Implementation Option suggests that an Open command issued in
   the Opened, Closing, Stopping or Stopped states "issue a Down event, immediately
   followed by an Up event", which would route the automaton through Starting to Req-Sent
   instead of staying put. That is a suggestion about what the surrounding implementation
   may synthesise, not an alternative row of the table: the table's own cell is the
   self-loop, and the suggested behaviour is expressible as two ordinary Down/Up
   transitions the table already contains. The oracle therefore records `3r` as
   `Stopped --Open--> Stopped`, `5r` as `Closing --Open--> Stopping` and
   `Stopping --Open--> Stopping`, and `9r` as `Opened --Open--> Opened`. **This is the
   most defensible reading, and it is a reading**: an implementation that takes the
   option will show `Open` in these four states going elsewhere, and that is a
   spec-permitted divergence rather than a defect.
2. **`[p]` — Passive option** (`TO-` in states 6, 7, 8: `tlf/3p`). Section 4.2's
   Stopped-state Implementation Option says only that "the This-Layer-Finished action is
   not used" in this case. It removes an **action**, not the target. The target is `3`
   either way, so the option cannot change the relation and the oracle is unaffected.
3. **`[x]` — Crossed connection** (`RCA` and `RCN` in state 7, `RCA` and `RCN` in state
   9: `scr/6x`, `tld,scr/6x`). Section 4.3's RCA Implementation Note says such a packet
   is unlikely, is probably an implementation error, and "SHOULD be logged". It
   prescribes logging, not a different target. The oracle takes the target `6` as
   printed.

No cell of Section 4.1 is genuinely under-specified. All 160 cells are printed, and `-`
is given an explicit meaning in the section's own preamble ("The dash ('-') indicates an
illegal transition"). The only judgement calls in this oracle are the three footnotes
above and the event-respelling normalisation, and both are stated rather than buried.

## Divergences between this oracle and the supplied implementation

Recorded after the implementation was read in Step 3, per Section 4 of
`CORPUS_PROTOCOL.md`. **Where the two disagree the specification is the oracle**; the
implementation's behaviour is described, never merged in.

The implementation agrees with the oracle on the **state set** exactly: ten record types,
`Initial`, `Starting`, `Closed`, `Stopped`, `Closing`, `Stopping`, `ReqSent`, `AckRcvd`,
`AckSent`, `Opened`, named in a single `permits` clause on `sealed interface LcpState`.

It agrees on the **event alphabet** exactly: sixteen constants of `enum LcpEvent`, whose
spellings are `UP`, `DOWN`, `OPEN`, `CLOSE`, `TO_PLUS`, `TO_MINUS`, `RCR_PLUS`,
`RCR_MINUS`, `RCA`, `RCN`, `RTR`, `RTA`, `RUC`, `RXJ_PLUS`, `RXJ_MINUS`, `RXR` — exactly
the normalisation this oracle fixed above, applied to the sixteen RFC tokens. Event
matching for this example is therefore `exact`, not `degraded`.

It **disagrees on the transition relation.** The implementation encodes 111 of the
oracle's 120 transitions. Of those 111, **99 agree** with the RFC, **12 name a different
target state**, and a further **9 RFC-legal cells are absent**, implemented as a thrown
`IllegalLcpEventException` instead. All 21 were checked cell-by-cell against RFC 1661
pages 12-13.

### D1 — RFC-legal cells implemented as illegal (9 transitions absent)

`illegal(...)` unconditionally throws `IllegalLcpEventException`, so these arms produce no
successor at all.

| RFC cell (state/event) | cell as printed | RFC target | implementation |
|---|---|---|---|
| 0 Initial / `Close` | `0`       | `Initial` | throws |
| 2 Closed / `RCA`    | `sta/2`   | `Closed`  | throws |
| 2 Closed / `RCN`    | `sta/2`   | `Closed`  | throws |
| 2 Closed / `RTR`    | `sta/2`   | `Closed`  | throws |
| 2 Closed / `RTA`    | `2`       | `Closed`  | throws |
| 2 Closed / `RUC`    | `scj/2`   | `Closed`  | throws |
| 2 Closed / `RXJ+`   | `2`       | `Closed`  | throws |
| 2 Closed / `RXJ-`   | `tlf/2`   | `Closed`  | throws |
| 2 Closed / `RXR`    | `2`       | `Closed`  | throws |

The `Closed` row is the bulk of it: the RFC gives `Closed` thirteen legal events, and the
implementation admits five. `Closed` is a state in which the peer may legitimately send
anything, and §4.2 says of it that "upon reception of Configure-Request packets, a
Terminate-Ack is sent. Terminate-Acks are silently discarded to avoid creating a loop" —
`RTA` in `Closed` is specified to be silently discarded, not rejected.

### D2 — cells implemented with a different target (12 transitions)

| RFC cell (state/event) | cell as printed | RFC target | implementation target |
|---|---|---|---|
| 3 Stopped / `Close`  | `2`             | `Closed`   | `Stopped` |
| 3 Stopped / `RCR+`   | `irc,scr,sca/8` | `AckSent`  | `Stopped` |
| 3 Stopped / `RCR-`   | `irc,scr,scn/6` | `ReqSent`  | `Stopped` |
| 4 Closing / `Down`   | `0`             | `Initial`  | `Closed`  |
| 5 Stopping / `Down`  | `1`             | `Starting` | `Initial` |
| 7 AckRcvd / `TO+`    | `scr/6`         | `ReqSent`  | `AckRcvd` |
| 7 AckRcvd / `RTR`    | `sta/6`         | `ReqSent`  | `AckRcvd` |
| 7 AckRcvd / `RTA`    | `6`             | `ReqSent`  | `AckRcvd` |
| 7 AckRcvd / `RXJ+`   | `6`             | `ReqSent`  | `AckRcvd` |
| 8 AckSent / `RCR-`   | `scn/6`         | `ReqSent`  | `AckSent` |
| 8 AckSent / `RTR`    | `sta/6`         | `ReqSent`  | `AckSent` |
| 8 AckSent / `RTA`    | `8`             | `AckSent`  | `Opened`  |

Eleven of the twelve replace a cross-state edge with a self-loop; the twelfth,
`AckSent`/`RTA`, does the opposite and routes a Terminate-Ack straight into `Opened`,
a state the RFC only ever enters on `sca,tlu/9` or `irc,tlu/9`.

Three of them change what the automaton can reach:

- `Stopped`/`RCR+` -> `Stopped` instead of `AckSent`, and `Stopped`/`RCR-` -> `Stopped`
  instead of `ReqSent`, make `Stopped` absorbing on Configure-Requests. In the RFC these
  two cells are the path by which a link that is administratively open but has stopped
  negotiating is reconfigured by a peer-initiated Configure-Request.
- `Stopped`/`Close` -> `Stopped` instead of `Closed` means an administrative Close taken
  in `Stopped` never reaches `Closed`.

### D3 — differences in the action list only (not scored)

Actions are outside the compared triple (see "What the oracle deliberately omits"), so
these change no scored transition. They are recorded because they are the same class of
transcription slip as D1 and D2.

- 3 `Stopped`/`RCA` and 3 `Stopped`/`RCN`: RFC `sta/3`; the implementation emits an empty
  action list, omitting Send-Terminate-Ack.
- 7 `AckRcvd`/`TO-`: RFC prints `tlf/3p`, the same `[p]`-footnoted cell as 6 and 8. The
  implementation routes 6 and 8 through `configureTimeoutExpired()`, which honours its
  `passiveOnConfigureTimeout` flag, but hard-codes `List.of(LcpAction.TLF)` for 7. The
  passive option is therefore applied to two of the three cells the RFC marks with it.

### Where the implementation follows the oracle's readings of the footnotes

- `[r]` — the implementation does **not** take the Restart option: `Open` in `Stopped`,
  `Closing`, `Stopping` and `Opened` is written as the table prints it (`3r`, `5r`, `5r`,
  `9r`), i.e. `Stopped -> Stopped`, `Closing -> Stopping`, `Stopping -> Stopping`,
  `Opened -> Opened`. This matches the oracle's reading exactly.
- `[p]` — the implementation exposes the passive option as a constructor flag, which is
  action-level and does not alter a target; see D3 for the one cell it misses.
- `[x]` — the implementation takes the target `6` as printed in all four `x`-footnoted
  cells, and adds no logging. This matches the oracle.

### Intake consequence

The intake was **initially failed** on this evidence: the implementation was generated
from RFC 1661 for this corpus and had no design reason to deviate, so the twenty-one
differences were transcription errors rather than decisions, and scoring SealFSM against
this oracle would have capped a perfect extractor at 99/120 recall and 99/111 precision
while contributing twenty-one `oracle-dispute` findings to the aggregate.

The repository author then **authorised correcting the implementation against this
oracle**. The twenty-one cells listed in D1 and D2 and the three cells noted in D3 were
corrected in `../java/LcpAutomaton.java`; nothing else in the file changed, and this
oracle was not touched. The tables above are retained as the **record of what was
corrected** — the pre-correction artefact is recoverable from commit `805652b`, and
`805652b` plus D1/D2/D3 reconstructs it exactly.

The encoded relation now equals this oracle: 120 transitions, no missing cell, no
differing target, no edge without an oracle cell, and exactly 40 `illegal(...)` arms
standing on exactly the 40 cells the RFC prints as `-`.

**Consequence for how this example may be read.** The oracle was authored from the RFC
before any of the Java was read, and is independent of the implementation. The
implementation is **not** independent of the oracle: it was conformed to it. A
transition-extraction result on this example therefore measures whether SealFSM recovers
the relation the Java encodes, which is the intended measurement — but it is not
additional evidence that the relation is RFC 1661's, because the only check of that is
this oracle. See `../PROVENANCE.md` for the related caveat about
`examples/lcp_automation/`.

This file is **not** to be edited to match the implementation, then or in future.
