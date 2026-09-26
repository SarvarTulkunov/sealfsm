"""Score SealFSM results against labels made before the run.

Implements the metrics of evaluation/PROTOCOL.md (thesis Decision 3). The inputs
are one or more corpus manifests (evaluation/corpus/<id>.json). Each names its
labels file and the tool's --json output. The scorer reports classification
outcomes, state accuracy at the levels Decision 2 separates, and transition
precision and recall, with unresolved edges as their own figure. It also
stratifies every figure by coding pattern and by source completeness.

What a transition IS for scoring (the default, --match events):
  the triple (source state, event, target state). A string event names an input;
  a null event is a genuinely eventless transition and matches only an eventless
  edge. `Idle --start--> Active` and `Idle --resume--> Active` are two transitions.
  Ground truth, extracted edges, precision, both recalls, and the missed and extra
  lists all use this one identity, and every count is over the SET of distinct
  triples: a triple labelled (or extracted) twice counts once. Guards are NOT part
  of the identity: two edges differing only in their guard are one triple.

--match state-pairs is a secondary, explicitly weaker metric over (source, target)
pairs, with the event ignored on both sides. --with-events is kept for
compatibility and selects the default.

Every labelled FSM transition must carry an explicit "event" field (a string, or
null for an eventless transition). A missing field is a validation error.

    python scripts/evaluation/score.py evaluation/corpus/*.json
    python scripts/evaluation/score.py evaluation/corpus/kafka-raft.json --json
    python scripts/evaluation/score.py MANIFEST... --match state-pairs

Standard library only.
"""
import argparse
import json
import os
import sys
from collections import Counter, defaultdict

PSEUDO = {"<initial>", "<unknown>", "<entry>"}

EVENTS = "events"
STATE_PAIRS = "state-pairs"
MATCH_MODES = (EVENTS, STATE_PAIRS)


def canon(name):
    """Qualified names are compared with nested types spelled with '.', never '$'."""
    return None if name is None else name.replace("$", ".")


def load(path, base):
    """A path from a manifest: relative to the working directory (the repository
    root, per PROTOCOL.md), or else to the manifest's own directory."""
    candidates = [path] if os.path.isabs(path) else [path, os.path.join(base, path)]
    for full in candidates:
        if os.path.exists(full):
            with open(full, encoding="utf-8") as fh:
                return json.load(fh)
    raise SystemExit(f"cannot find {path} (looked in the working directory and in {base})")


def repo_root(manifest_path):
    """The manifest's own directory, the fallback base for its relative paths."""
    return os.path.dirname(os.path.abspath(manifest_path))


class Nodes:
    """A machine's state nodes, for mapping a labelled name onto the tool's identity."""

    def __init__(self, machine):
        self.by_id = {}
        self.qualified = set()
        for key in ("directBranches", "atomicStates", "compositeNodes"):
            for s in machine.get(key, []):
                qn = canon(s["qualifiedName"])
                self.by_id[s["id"]] = qn
                self.qualified.add(qn)

    def qualify(self, name):
        """A labelled state name as the tool's qualified name, or the name itself if none matches."""
        c = canon(name)
        if c in self.qualified:
            return c
        if name in self.by_id:
            return self.by_id[name]
        hits = sorted(q for q in self.qualified if q.endswith("." + c))
        return hits[0] if len(hits) == 1 else c


class LabelNodes:
    """A labelled FSM's own state names. Used to qualify transition endpoints when the
    tool reported no machine to map them onto, so that a missed machine contributes
    the same set of distinct transitions to overall recall that the labeller wrote."""

    def __init__(self, hierarchy):
        self.qualified = {canon(n) for key in ("directBranches", "atomicStates")
                          for n in hierarchy.get(key, [])}
        for enum, constants in hierarchy.get("enumChildStates", {}).items():
            self.qualified |= {canon(enum) + "." + c for c in constants}

    def qualify(self, name):
        c = canon(name)
        if c in self.qualified:
            return c
        hits = sorted(q for q in self.qualified if q.endswith("." + c))
        return hits[0] if len(hits) == 1 else c


class LabelError(ValueError):
    """A labels file that does not state what the metric needs."""


def validate_transitions(hierarchy, where):
    """Every labelled FSM transition names `from`, `to`, and an explicit `event`
    (a non-empty string, or null for a genuinely eventless transition)."""
    for i, t in enumerate(hierarchy.get("transitions", [])):
        at = f"{where}: hierarchy {hierarchy.get('root')!r}, transition #{i + 1}"
        if not isinstance(t, dict):
            raise LabelError(f"{at}: a transition must be a JSON object")
        for field in ("from", "to"):
            if not isinstance(t.get(field), str) or not t[field]:
                raise LabelError(f"{at}: missing or empty {field!r}")
        if "event" not in t:
            raise LabelError(f"{at} ({t['from']} -> {t['to']}): missing 'event' field. Give the event's "
                             f"name, or write \"event\": null if the transition is genuinely eventless")
        if t["event"] is not None and (not isinstance(t["event"], str) or not t["event"]):
            raise LabelError(f"{at} ({t['from']} -> {t['to']}): 'event' must be a non-empty string or null")


def transition_key(source, event, target, mode):
    """The one identity of a transition, used on BOTH sides of every comparison.

    events:      (source, event, target); event None means eventless.
    state-pairs: (source, target); the event is ignored on both sides.
    """
    if mode == EVENTS:
        return (source, canon(event), target)
    if mode == STATE_PAIRS:
        return (source, target)
    raise ValueError(f"unknown match mode {mode!r}")


def show_key(key):
    """A transition key as text, for the missed and extra lists."""
    if len(key) == 2:
        return f"{key[0]} --> {key[1]}"
    source, event, target = key
    return f"{source} --[{'eventless' if event is None else event}]--> {target}"


def truth_set(hierarchy, nodes, mode):
    """The distinct labelled transitions, keyed over `nodes`' state identities."""
    return {transition_key(nodes.qualify(t["from"]), t["event"], nodes.qualify(t["to"]), mode)
            for t in hierarchy.get("transitions", [])}


def score_transitions(hierarchy, machine, mode):
    """Transition figures for one labelled FSM. `machine` is the tool's machine, or
    None when the tool reported none: then nothing matches and every distinct
    labelled transition is missed."""
    entries = len(hierarchy.get("transitions", []))
    if machine is None:
        truth = truth_set(hierarchy, LabelNodes(hierarchy), mode)
        return {"truth_transitions": len(truth), "truth_label_entries": entries, "matched_edges": 0,
                "missed_transitions": sorted(map(show_key, truth))}

    nodes = Nodes(machine)
    truth = truth_set(hierarchy, nodes, mode)
    resolved = set()
    entry_edges = unresolved = unresolved_known = 0
    for t in machine["transitions"]:
        if t["from"] in PSEUDO:
            entry_edges += 1
            if not t["resolved"]:
                unresolved += 1
            continue
        if not t["resolved"]:
            unresolved += 1
            unresolved_known += 1
            continue
        if t["to"] in PSEUDO:
            continue
        resolved.add(transition_key(nodes.by_id.get(t["from"], canon(t["from"])), t.get("event"),
                                    nodes.by_id.get(t["to"], canon(t["to"])), mode))
    matched = resolved & truth
    return {
        "truth_transitions": len(truth), "truth_label_entries": entries,
        "resolved_edges": len(resolved), "matched_edges": len(matched),
        "unresolved_edges": unresolved, "unresolved_with_known_source": unresolved_known,
        "entry_edges": entry_edges,
        "missed_transitions": sorted(map(show_key, truth - resolved)),
        "extra_transitions": sorted(map(show_key, resolved - truth)),
    }


def jaccard(a, b):
    return 1.0 if not a and not b else len(a & b) / len(a | b)


def score_states(hierarchy, machine):
    nodes = Nodes(machine)
    direct_t = {nodes.qualify(n) for n in hierarchy.get("directBranches", [])}
    direct_s = {canon(s["qualifiedName"]) for s in machine["directBranches"]}
    atomic_t = {nodes.qualify(n) for n in hierarchy.get("atomicStates", [])}
    for enum, constants in hierarchy.get("enumChildStates", {}).items():
        atomic_t |= {nodes.qualify(canon(enum) + "." + c) for c in constants}
    atomic_s = {canon(s["qualifiedName"]) for s in machine["atomicStates"]}
    return {
        "direct_exact": direct_t == direct_s, "direct_jaccard": jaccard(direct_t, direct_s),
        "atomic_exact": atomic_t == atomic_s, "atomic_jaccard": jaccard(atomic_t, atomic_s),
    }


def score_hierarchy(h, outcomes, machines, mode=EVENTS, entry="", split="development",
                    completeness="complete", where="labels"):
    """One labelled hierarchy scored against the tool's outcomes and machines (both keyed by root)."""
    root = canon(h["root"])
    outcome = outcomes.get(root, "NOT_EXAMINED")
    label = h["label"]
    row = {
        "entry": entry, "split": split,
        "root": root, "label": label, "outcome": outcome,
        "pattern": h.get("codingPattern", "unspecified"), "completeness": completeness,
        "transition_identity": mode,
    }
    if label == "FSM":
        row["class"] = "TP" if outcome == "MACHINE" else "FN"
    elif label == "NON_FSM":
        row["class"] = "FP" if outcome == "MACHINE" else "TN"
    else:
        row["class"] = "UNCERTAIN"
    row["abstention"] = outcome == "CANDIDATE"

    if label == "FSM":
        validate_transitions(h, where)
        machine = machines[root] if outcome == "MACHINE" else None
        row.update(score_transitions(h, machine, mode))
        if machine is not None:
            row.update(score_states(h, machine))
    return row


def score_entry(manifest_path, mode=EVENTS):
    base = repo_root(manifest_path)
    manifest = load(manifest_path, base)
    labels = load(manifest["labels"], base)
    result = load(manifest["outcome"], base)
    if result.get("tool") != "sealfsm" or result.get("schema") != 1:
        raise SystemExit(f"{manifest_path}: outcome file is not a schema-1 sealfsm result")
    if not labels.get("labelledBeforeToolOutput", False):
        print(f"warning: {manifest['id']}: labels are not marked as made before the tool output",
              file=sys.stderr)

    outcomes = {canon(o["root"]): o["outcome"] for o in result["outcomes"]}
    machines = {canon(m["root"]): m for m in result["machines"]}
    completeness = manifest.get("sourceCompleteness", "complete")
    try:
        return [score_hierarchy(h, outcomes, machines, mode, entry=manifest["id"],
                                split=manifest.get("split", "development"),
                                completeness=completeness, where=manifest["labels"])
                for h in labels["hierarchies"]]
    except LabelError as e:
        raise SystemExit(f"invalid labels: {e}")


def summarize(rows):
    cls = Counter(r["class"] for r in rows)
    fn_by = Counter(r["outcome"] for r in rows if r["class"] == "FN")
    abst = Counter(r["label"] for r in rows if r["abstention"])
    tp_rows = [r for r in rows if r["class"] == "TP"]
    fsm_rows = [r for r in rows if r["label"] == "FSM"]
    resolved = sum(r.get("resolved_edges", 0) for r in tp_rows)
    matched = sum(r.get("matched_edges", 0) for r in tp_rows)
    truth_tp = sum(r.get("truth_transitions", 0) for r in tp_rows)
    truth_all = sum(r.get("truth_transitions", 0) for r in fsm_rows)
    modes = sorted({r["transition_identity"] for r in rows if "transition_identity" in r})

    def ratio(a, b):
        return None if b == 0 else a / b

    return {
        "transition_identity": modes[0] if len(modes) == 1 else modes,
        "hierarchies": len(rows),
        "TP": cls["TP"], "FN": cls["FN"], "FP": cls["FP"], "TN": cls["TN"], "UNCERTAIN": cls["UNCERTAIN"],
        "FN_by_outcome": dict(fn_by),
        "abstentions_by_label": dict(abst),
        "coverage": ratio(cls["TP"], len(fsm_rows)),
        "classification_precision": ratio(cls["TP"], cls["TP"] + cls["FP"]),
        "direct_branches_exact": ratio(sum(r["direct_exact"] for r in tp_rows), len(tp_rows)),
        "atomic_states_exact": ratio(sum(r["atomic_exact"] for r in tp_rows), len(tp_rows)),
        "transition_precision": ratio(matched, resolved),
        "transition_recall_conditional": ratio(matched, truth_tp),
        "transition_recall_overall": ratio(matched, truth_all),
        "transitions_matched": matched,
        "transitions_resolved_tp": resolved,
        "transitions_labelled_tp": truth_tp,
        "transitions_labelled_all": truth_all,
        "unresolved_edges": sum(r.get("unresolved_edges", 0) for r in tp_rows),
        "unresolved_with_known_source": sum(r.get("unresolved_with_known_source", 0) for r in tp_rows),
    }


def describe(mode):
    if mode == EVENTS:
        return "(from, event, to); null event = eventless; guards ignored; counted over distinct triples"
    return "(from, to) STATE PAIRS ONLY, a secondary metric; events and guards ignored; distinct pairs"


def fmt(v):
    if v is None:
        return "n/a"
    if isinstance(v, float):
        return f"{v:.3f}"
    return str(v)


def build_parser():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifests", nargs="+")
    ap.add_argument("--json", action="store_true", help="print the scores as JSON")
    ap.add_argument("--match", choices=MATCH_MODES, default=EVENTS,
                    help="transition identity. 'events' (default): (from, event, to), a null event "
                         "matching only an eventless edge. 'state-pairs': a secondary, weaker metric over "
                         "(from, to), events ignored on both sides. Guards are never part of either")
    ap.add_argument("--with-events", action="store_true",
                    help="kept for compatibility: the same as --match events, now the default")
    ap.add_argument("--split", choices=["development", "heldout"], help="score one split only")
    return ap


def main(argv=None):
    ap = build_parser()
    args = ap.parse_args(argv)
    if args.with_events and args.match != EVENTS:
        ap.error("--with-events contradicts --match state-pairs")

    rows = []
    for m in args.manifests:
        rows.extend(score_entry(m, args.match))
    if args.split:
        rows = [r for r in rows if r["split"] == args.split]

    report = {"overall": summarize(rows), "by_pattern": {}, "by_completeness": {}, "rows": rows}
    by_pattern = defaultdict(list)
    by_completeness = defaultdict(list)
    for r in rows:
        by_pattern[r["pattern"]].append(r)
        by_completeness[r["completeness"]].append(r)
    report["by_pattern"] = {k: summarize(v) for k, v in sorted(by_pattern.items())}
    report["by_completeness"] = {k: summarize(v) for k, v in sorted(by_completeness.items())}

    if args.json:
        json.dump(report, sys.stdout, indent=2)
        print()
        return

    print(f"Transition identity: {describe(args.match)}")
    print()
    print("Per hierarchy")
    print(f"  {'entry':<18} {'root':<52} {'label':<9} {'outcome':<12} {'class':<9} edges")
    for r in rows:
        if "resolved_edges" in r:
            edges = (f"{r['matched_edges']}/{r['resolved_edges']} resolved matched, "
                     f"{r['matched_edges']}/{r['truth_transitions']} labelled found, "
                     f"{r['unresolved_edges']} unresolved")
        elif "truth_transitions" in r:
            edges = f"0/{r['truth_transitions']} labelled found (no machine reported)"
        else:
            edges = ""
        print(f"  {r['entry']:<18} {r['root'][-52:]:<52} {r['label']:<9} {r['outcome']:<12} "
              f"{r['class']:<9} {edges}")
    for title, groups in (("Overall", {"all": rows}), ("By coding pattern", by_pattern),
                          ("By source completeness", by_completeness)):
        print()
        print(title)
        for name, group in sorted(groups.items()):
            s = summarize(group)
            print(f"  {name}: TP={s['TP']} FN={s['FN']} {s['FN_by_outcome']} FP={s['FP']} TN={s['TN']} "
                  f"uncertain={s['UNCERTAIN']} abstentions={s['abstentions_by_label']}")
            print(f"    coverage={fmt(s['coverage'])} precision={fmt(s['classification_precision'])} "
                  f"direct-exact={fmt(s['direct_branches_exact'])} atomic-exact={fmt(s['atomic_states_exact'])}")
            print(f"    transitions: precision={fmt(s['transition_precision'])} "
                  f"({s['transitions_matched']}/{s['transitions_resolved_tp']}) "
                  f"recall(conditional)={fmt(s['transition_recall_conditional'])} "
                  f"({s['transitions_matched']}/{s['transitions_labelled_tp']}) "
                  f"recall(overall)={fmt(s['transition_recall_overall'])} "
                  f"({s['transitions_matched']}/{s['transitions_labelled_all']}) "
                  f"unresolved={s['unresolved_edges']} ({s['unresolved_with_known_source']} with a known source)")


if __name__ == "__main__":
    main()
