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
  actual `transition` (from, to, optionally the event), each with its evidence.

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
| **transition precision** | resolved edges that are labelled transitions / resolved edges, over TP machines |
| **transition recall (conditional)** | labelled transitions matched by a resolved edge / labelled transitions of TP machines |
| **transition recall (overall)** | the same numerator / labelled transitions of **all** labelled FSMs. A transition of a machine the tool skipped, or written in an unsupported style, counts as missed |
| **unresolved** | unresolved edges on TP machines, with how many have a known source state |

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
