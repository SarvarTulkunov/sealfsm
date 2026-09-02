# Transition-table orientation in real Java — the F27 census

_The measurement that decided F27. Written to be lifted into the validation
chapter. The question it answers is not "is this shape imaginable" but "does real
production Java write transition tables transposed", and it is answered by
counting, not by argument._

---

## The question

The tool's whole recognition surface asks **where the state is discriminated**.
Every locus — `CENTRALIZED_SWITCH`, `INSTANCEOF_CHAIN`, `POLYMORPHIC_OVERRIDE`,
`FUNCTIONAL_CALLABLE` — is a spelling of *"switch on Q"*. A transition table can
equally be written the other way round:

```java
void consumeFrame(FrameType frameType) {     // switch on Σ
    switch (frameType) {
        case GOAWAY   -> connState = ConnectionHandshake.SHUTDOWN;   // commit Q
        case SETTINGS -> connState = ConnectionHandshake.ACTIVE;
    }
}
```

Both are the same relation; they differ in which axis of the table is the outer
loop. Call the first **state-major** and the second **Σ-major**. A hierarchy
dispatched only Σ-major matched no recognizer, was rejected as "no transition
producer found", and — because the Tier 3 candidate channel keys on the same
state-discrimination — reported **no states either**, though its `permits` clause
names them exactly.

Whether that gap is worth closing is an empirical question about how Java is
written, so it was measured before anything was changed.

## Corpus

| corpus | what it is | files |
|---|---|---|
| JDK 21 | `lib/src.zip`, every module | 14,723 |
| libraries | 145 `-sources.jar` from the local Maven repository — Apache HttpClient 5, Hibernate, Tomcat, Jackson, ASM, Netty transports, Flyway, HikariCP, byte-buddy, … | 26,432 |

Both are production code written by many hands over decades, and neither was
selected for containing state machines.

## Instrument

Two parse-only passes built on `com.sun.source` (javac's own parser, no
resolution), in `scratchpad/census/`. Parse-only is a deliberate constraint: it
scales to 41,000 files, and every judgement it makes is one a reader could make
from the same text.

**Level 1 — orientation of value-producing switches.** For every switch
committing ≥ 2 syntactically distinct values to *one* target of reference type R
(a field, a local, or the method's return), classify the switch as state-major
when the selector's declared type is R and Σ-major otherwise.

**Level 2 — how state fields are written.** A *state field* is a field of
reference type assigned in ≥ 2 distinct places in its own class. For each one:
is it discriminated anywhere in the class (`switch`, `==`, `instanceof`)? And is
it written from inside a state-major switch, from inside a Σ-major one, from a
method **called by** ≥ 1 arm of a Σ-major switch (one hop — the depth the tool's
own k = 1 probe uses), or from no dispatch at all?

Both passes were validated on inputs with a known answer before being trusted:
the seven hand-written probes in `scratchpad/evprobe` (6 Σ-major, all detected)
and `examples/` (5 state-major, 4 Σ-major, matching the fixtures by hand).

## Result — level 2, the FSM-relevant one

Restricted to state fields whose type is a **closed set** (an `enum` or a sealed
hierarchy), which is what makes the field range over named states at all:

| | JDK 21 | libraries | total |
|---|---|---|---|
| closed-type state fields | 339 | 570 | **909** |
| … discriminated somewhere in their class | 156 | 293 | 449 |
| … never discriminated | 183 | 277 | 460 |
| written by a **state-major** dispatch | 6 | 3 | **9** |
| written by a **Σ-major** dispatch, directly | 8 | 9 | **17** |
| written **one hop** from a Σ-major switch | 2 | 6 | **8** |
| written by **no dispatch at all** | 323 | 552 | 875 |

Two readings, and the second matters as much as the first:

1. **Σ-major outnumbers state-major, roughly 25 to 9.** The shape the tool could
   not see is the *more* common of the two it was choosing between.
2. **Both are dwarfed by "no dispatch at all" (875).** The dominant way a state
   field is written in real Java is a bare `this.state = X;` in whatever method
   handles that case. That is the F2 mutation fallback's population, and it is a
   separate finding from this one.

## Confirmed by reading the source, not only by counting

The two largest hits were opened and checked by hand:

- **`AbstractH2StreamMultiplexer`** (Apache HttpClient 5 core, HTTP/2) —
  `switch (frameType)` at line 736 over `DATA / HEADERS / CONTINUATION /
  WINDOW_UPDATE / RST_STREAM / PING / SETTINGS / PRIORITY / PUSH_PROMISE /
  GOAWAY`, whose `GOAWAY` arm commits `connState = ConnectionHandshake.SHUTDOWN`
  or `GRACEFUL_SHUTDOWN`. A connection-handshake automaton, dispatched on the
  input symbol.
- **`AbstractHttp1StreamDuplexer`** (the same library, HTTP/1.1) —
  `switch (closeMode) { case GRACEFUL -> connState = GRACEFUL_SHUTDOWN;
  case IMMEDIATE -> connState = SHUTDOWN; }`.

`examples/eventmajor` is those two shapes written over a sealed hierarchy.

## What the census also ruled OUT, and what it therefore bought

Level 1 counted 587 Σ-major commit tables in the JDK against 13 state-major —
but reading them showed the population is dominated by things that are **not**
state machines: `Pattern.atom`, `DirectMethodHandle.createFunction`,
`VectorShape.forBitSize(int)`, `JavaKind.fromPrimitiveOrVoidTypeChar(char)`,
`Opcode.getOpcodeBlock(int)`, `ToolOptions.setExpandRequires(String)`. Parsers,
factories, decoders and option handlers — a switch on a code, producing a value.

That is where the recognizer's three requirements come from, and each one is a
requirement *because* of what it excludes here:

| requirement | excludes | control |
|---|---|---|
| the selector is a **closed** type (enum or sealed) | every `switch (int opcode)` parser and bytecode reader | `eventmajor.Tone` |
| **≥ 2 arms** commit a hierarchy value | a single branch handling one special case | `eventmajor.Beat` |
| the host **holds** a hierarchy value (H-typed parameter or field) | `VectorShape.forBitSize(int)` and every other factory — Q must be an *input* for a transition function | `eventmajor.Shade` |

The commit requirement itself is unchanged from every other locus, so a Σ-major
switch folding into a `String` still yields nothing (`eventmajor.Mode`, which is
`examples/voidfold`'s guarantee restated here).

## Threats to validity, stated

- **Parse-only under-counts.** A selector whose declared type is not visible in
  the local scope is skipped rather than guessed — 1,411 switches in the JDK.
  The direction of that bias is unknown.
- **Types are matched by simple name.** Two same-named types are conflated.
- **The population is enum-typed states, not sealed ones.** Sealed hierarchies
  are a 2021 language feature and sealed FSMs barely exist in the wild yet, which
  is a limitation of the whole corpus and is acknowledged in the thesis. Table
  *orientation* is independent of how the state is typed — it is a property of
  how the dispatch is written — but carrying the result across is an inference,
  not a measurement.
- **"≥ 2 writes" is a proxy for "state field"** and admits configuration fields;
  `ToolOptions` is the clearest example, and it is one of the shapes the host-holds-Q
  requirement rejects.

## The decision this supported

Σ-major dispatch is real, is more common than the state-major form it was being
compared against, and appears in exactly the domain the thesis targets (protocol
state machines). It is therefore recognised — as evidence for the **candidate**
channel only, never for a machine, because a Σ-major arm establishes no source
state and a relation built from one would be sourced entirely at `<unknown>`.
See `FIXLOG.md`, F27.
