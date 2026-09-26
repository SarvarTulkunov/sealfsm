# Evaluation protocol: independent validation on repositories

_Implements thesis Decision 3 (decision record of 24 September 2026). It is
written so the procedure can be followed, audited and lifted into the validation
chapter. Examples written for SealFSM, generated examples and golden-output
snapshots are regression tests: they pin behaviour, and they do **not** by
themselves establish accuracy on independent projects._

---

## What is being measured

SealFSM makes claims of three different strengths. The evaluation measures each
separately and never pools them:

1. **Classification.** Is a sealed hierarchy reported as a machine, a
   provisional candidate, or not at all? This is a decision about the hierarchy,
   and it is where false positives (a sum type published as a machine) and
   false negatives (a real machine the recognizers skip) live.
2. **States**, for hierarchies reported as machines: the direct permitted
   branches, the atomic states they expand to, and the grouping nodes between
   (Decision 2). Exact by construction over the type structure, but only
   *conditional on the classification* (Decision 1).
3. **Transitions**, for hierarchies reported as machines: approximate, measured
   by precision and recall against labelled transitions. Unresolved edges are a
   third figure, never folded into either.

A **candidate** is an uncertain classification (Decisions 1 and 4). It is never
counted as a detected machine, and its provisional member list is never counted
as recovered states.

## The procedure

### Step 1: record the input exactly

One manifest per corpus entry, in `evaluation/corpus/<id>.json`, from
`evaluation/templates/manifest.template.json`. It records:

- the repository URL and the **exact commit** (a full SHA, never a branch or a tag);
- the selected module and its source roots;
- the Java version the module targets;
- the exact SealFSM command, including `--json`, and the SealFSM revision it ran at;
- the dependency or classpath setup (`--classpath`), or `none`;
- the missing-source diagnostics the run printed. These are the model-level
  `N type reference(s) did not resolve` line, and every WARN naming an
  unresolved permitted subtype or a rejection that "may be a type-resolution
  failure". If they touch a labelled hierarchy, the entry is stratified as
  **partial source** (step 5).

### Step 2: label before looking

Labels go in `evaluation/labels/<id>.json`, from
`evaluation/templates/labels.template.json`, and are written **before the tool's
output for that entry is inspected**. The labeller records the date and sets
`labelledBeforeToolOutput`. The history of the labels file is what substantiates
that flag, so it is committed before the outcome file is.

For every sealed hierarchy in the sample (every `sealed` root the selected
module declares, not just the ones that look like machines):

- `label`: `FSM`, `NON_FSM` or `UNCERTAIN`;
- `evidence`: the code that establishes the label (file and line, and what is
  there). For an FSM that means where the current state is stored and replaced;
  for a converter, where its results are used as data;
- `codingPattern`: the pattern the transitions are written in (the vocabulary
  is below), whether or not SealFSM supports it;
- for an FSM: `directBranches`, `enumChildStates`, `atomicStates`, and every
  actual `transition` (`from`, `event`, `to`), each with its evidence. The
  `event` field is **required**: a string names the input that selects the
  transition; `null` states that the transition is genuinely eventless (no input
  selects it, e.g. a handler that takes only the state it leaves, or an arm
  taken whatever the input is and that never tests it, which is labelled once
  with `null` rather than once per event). A branch reached because an event test
  FAILED is not eventless: it is one transition per remaining input (see
  *Transition identity*). A missing
  `event` field is a validation error, never read as eventless. See
  *Transition identity* below for how events are spelled and matched.

`UNCERTAIN` is for hierarchies a careful reader cannot decide. It is reported as
its own row and excluded from precision and recall. It is not a way to avoid
labelling hard cases, so record why in `notes`.

### Step 3: include negatives, keep a held-out set

The sample must contain hierarchies that are **not** machines. Examples are
converters (Decision 4), exhaustive folds, ordinary sealed data types, event
alphabets, and recursive data types. A corpus of only machines cannot measure
false positives.

Each manifest has a `split`: `development` or `heldout`. Rules may be changed in
response to `development` entries. `heldout` entries are run only after the rules
are frozen, and they are the ones the headline figures come from. If a held-out
entry prompts a rule change, it moves to `development`, a replacement is drawn,
and the move is reported.

### Step 4: score

```bash
java -jar target/sealfsm.jar --src <root> [--src ...] [--classpath ...] --json --out evaluation/outcomes/<id>
python scripts/evaluation/score.py evaluation/corpus/*.json            # all entries
python scripts/evaluation/score.py evaluation/corpus/<id>.json --json  # one entry, machine-readable
python scripts/evaluation/score.py evaluation/corpus/*.json --match state-pairs  # secondary metric only
python -m unittest discover -s scripts/evaluation -p "test_*.py"        # the scorer's own tests
```

The scorer reads each manifest, its labels, and the tool's
`sealfsm-result.json`, and reports:

| figure | definition |
|---|---|
| **TP** | labelled `FSM`, reported as a machine |
| **FN** | labelled `FSM`, not reported as a machine. Broken down into `CANDIDATE`, `ABSTAINED`, `VETOED`, `CONVERTED`, and `NOT_EXAMINED` (the root never reached the classifier) |
| **FP** | labelled `NON_FSM`, reported as a machine |
| **TN** | labelled `NON_FSM`, rejected (`ABSTAINED`, `VETOED`, `CONVERTED`, or not examined) |
| **abstentions** | reported as a `CANDIDATE`, counted per label. A candidate for an `FSM` is also an FN; a candidate for a `NON_FSM` is not an FP |
| **coverage** | TP / labelled FSMs: the share of real machines the tool reports as machines. This is overall detection recall |
| **classification precision** | TP / (TP + FP) |
| **state accuracy** | per TP: exact match of direct branches, exact match of atomic states, and their Jaccard indices |
| **transition precision** | distinct resolved transitions that are labelled transitions / distinct resolved transitions, over TP machines |
| **transition recall (conditional)** | distinct labelled transitions matched by a resolved transition / distinct labelled transitions of TP machines |
| **transition recall (overall)** | the same numerator / distinct labelled transitions of **all** labelled FSMs. A transition of a machine the tool skipped, or written in an unsupported style, counts as missed |
| **missed / extra** | per FSM: the labelled transitions no resolved edge matched, and the resolved transitions no label matched |
| **unresolved** | unresolved edges on TP machines, with how many have a known source state |

#### Transition identity

A transition is the triple **(source state, event, target state)**. The same
identity is used for the labels, for the tool's resolved edges, and for every
transition figure above (precision, both recalls, missed, extra).

- `Idle --start--> Active` and `Idle --resume--> Active` are **two** transitions.
  Finding one of them scores 1/2.
- A labelled event matches an extracted event only by **exact string equality**
  (after spelling nested types with `.` instead of `$`). Label events the way
  SealFSM spells them: the simple name of an event type tested by a type
  pattern or `instanceof` (`SegmentArrival`); an enum constant by its name
  (`SEND_HEADERS`); an enum constant carried by a sealed event member as
  `Member.CONSTANT` (`UserCall.CLOSE`, `Send.HEADERS`); a method name where the
  hierarchy's handlers are several differently named methods (`pay`, `ship`).
- A `null` (eventless) transition matches only an eventless edge, and a named
  one never matches an eventless edge. When the tool records an event test as a
  **guard** rather than as the edge's event, that edge does not match a label
  that names the event. This is intended: the event was not recovered.
- **Negated event tests.** When the source tests the input and the event type is
  closed (a sealed type or an enum), the branch taken when the test fails fires
  on the remaining inputs, so it is labelled **once per remaining input**, never
  as `!Lock` and never as one eventless transition. In `examples/door`,
  `(event instanceof Lock) ? new Locked() : new Open()` in state `Closed`, with
  `Event permits Push, Lock, Unlock`, is three transitions: `Closed --Lock-->
  Locked`, `Closed --Push--> Open`, `Closed --Unlock--> Open`. The same holds for
  the `default` arm of a switch over the event and for code after an
  `if (test) return ...;`. SealFSM reports them this way (F38). Label `null` only
  when the source never tests the input on that path (an arm taken whatever the
  input is). When the event type is **open** (a plain interface, `Object`,
  `int`), "every input except `Lock`" is not a finite set: the tool keeps such an
  edge eventless with the negated test as its guard, and a label naming concrete
  inputs there will not match it.
- Every count is over the **set** of distinct triples, per machine. A triple
  labelled twice, or extracted twice (for example under two different guards),
  counts once. The denominator of both recalls is the number of distinct
  labelled triples, never the raw number of label entries; the per-hierarchy
  row keeps the raw count as `truth_label_entries`. A labelled FSM the tool did
  not report as a machine still contributes its distinct triples to the overall
  denominator (with its state names qualified against its own labelled states).
- **Guards are not part of this metric.** Neither the labels nor the scorer
  compare guards; two edges that differ only in their guard are one triple, and
  a guard the tool gets wrong is not penalised. Guard accuracy would need its
  own labelled field and its own tested comparison, which the scorer does not
  implement.
- Unresolved edges, and edges from or to a pseudo-state (`<initial>`,
  `<unknown>`, `<entry>`), are never matched; they are counted under
  **unresolved**.

A secondary, explicitly weaker metric, `--match state-pairs`, uses the pair
(source, target) on both sides instead, ignoring events. Its numerators and
denominators are all over distinct pairs. It answers "was the state graph
recovered?", not "was the transition relation recovered?", and it must be
labelled as such wherever it is reported. `--with-events` is still accepted
and is the same as the default.

Conditional accuracy (states, transitions over TP machines) is **never** reported
as overall recall. Both are always reported side by side.

### Step 5: stratify

Every figure is also reported per **coding pattern** (from the labels) and per
**source completeness** (from the manifest: `complete` or `partial`). A pattern
SealFSM does not support shows up as a stratum with zero coverage. That is the
honest reading of it.

Two kinds of controlled experiment complement the corpus. They do not substitute
for it:

- **behaviour-preserving rewrites.** The same machine is rewritten in another
  supported pattern (a switch as an `instanceof` chain, a returned successor as
  a field write), and the extracted relation must not change. The corpus
  already carries two independently written pairs (`examples/http2-stream-*`,
  `examples/lcp_automation*`);
- **controlled behaviour changes.** One transition is changed in the source, and
  exactly the expected edge must change in the output.

## The four classification outcomes and how they are scored

| tool outcome (`outcomes[].outcome`) | meaning | scored as |
|---|---|---|
| `MACHINE` | a supported dispatch whose successor is shown to become the current state | a detection |
| `CANDIDATE` | a supported dispatch pattern supplied plausible evidence, and the relation could not be established. The candidate's `basis` says which half was missing. `INSTALLATION_UNSHOWN` means Decision 4: nothing shows a returned value becoming the current state, and a conversion cannot be told apart | an abstention |
| `ABSTAINED`, `VETOED` | no dispatch evidence, or a recursive data type | a rejection |
| `CONVERTED` | every caller of every value-returning producer uses the result as data: an established conversion. Published on neither channel (Decision 4) | a rejection |

**Remaining design choice (Decision 4), as taken.** An uncertain hierarchy (value
produced, installation not shown) is represented as a candidate with
`basis = [INSTALLATION_UNSHOWN]` when its sites amount to a dispatch. That means a
discrimination, or per-state sites fixing at least two source states. Otherwise
it is a plain abstention with a diagnostic naming the missing evidence. An
established conversion is never a candidate. The candidate's members are
reported under `provisionalAtomicMembers` and `provisionalDirectBranches`, never
under the `atomicStates` key a machine uses, so no scorer can count them as
recovered states by accident.

## Coding-pattern vocabulary for labels

Use one of these for `codingPattern`, and add a new value (and say so) rather
than forcing a fit:

| value | shape |
|---|---|
| `per-state-override` | each member overrides a method returning the next state (State pattern) |
| `per-state-carrier` | the same, returning a wrapper that carries the next state |
| `centralized-switch` | a switch over the state returns or stores the next state |
| `instanceof-chain` | the same, spelled as an `if … instanceof` chain |
| `typed-handler` | one handler per state outside the hierarchy (`handle(Placed)`) |
| `run-to-completion` | a driver re-entering itself with the successor |
| `field-mutation` | the dispatch writes the successor into a state field |
| `mutator` | the successor is handed to a setter-like method |
| `gof-context` | void per-state methods install the successor into a context |
| `functional` | a lambda or anonymous callable computes the successor |
| `event-major` | a switch over the input installs states; the state is not discriminated (F27, not supported) |
| `target-major` | one method per destination, sources as throwing preconditions (L1, not supported) |
| `library-container` | commits go through a library container such as `AtomicReference` (L2, not supported) |
| `none` | for `NON_FSM` entries |

## Corpus size and selection

No target number is set in advance. Record the search that produced the
candidates for the corpus (queries, date, hosting site), the inclusion and
exclusion criteria, and how many repositories each step kept. Real sealed-type
FSMs are scarce, and that scarcity is a finding: report it rather than filling
the corpus with generated code. `CENSUS.md` records one such measurement for
closed-type state fields across JDK 21 and 145 library jars.

## Threats to validity to report

- The labeller also wrote the tool. `labelledBeforeToolOutput` and the order of
  the labels and outcome commits are the mitigation. A second labeller on a
  sample, with agreement reported, is stronger.
- Missing sources (Step 1) thin the model. Such an entry is stratified, and its
  rejections are not read as verdicts.
- A real machine whose store-back lives outside the analysed module (a
  library's public API used by client code) is an abstention by design
  (Decision 4). Report how many FNs are of this kind.
