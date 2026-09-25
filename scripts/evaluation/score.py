"""Score SealFSM results against labels made before the run.

Implements the metrics of evaluation/PROTOCOL.md (thesis Decision 3). The inputs
are one or more corpus manifests (evaluation/corpus/<id>.json). Each names its
labels file and the tool's --json output. The scorer reports classification
outcomes, state accuracy at the levels Decision 2 separates, and transition
precision and recall, with unresolved edges as their own figure. It also
stratifies every figure by coding pattern and by source completeness.

    python scripts/evaluation/score.py evaluation/corpus/*.json
    python scripts/evaluation/score.py evaluation/corpus/kafka-raft.json --json
    python scripts/evaluation/score.py MANIFEST... --with-events   # match (from, event, to)

Standard library only.
"""
import argparse
import json
import os
import sys
from collections import Counter, defaultdict

PSEUDO = {"<initial>", "<unknown>", "<entry>"}


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


def jaccard(a, b):
    return 1.0 if not a and not b else len(a & b) / len(a | b)


def score_entry(manifest_path, with_events):
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
    rows = []
    for h in labels["hierarchies"]:
        root = canon(h["root"])
        outcome = outcomes.get(root, "NOT_EXAMINED")
        label = h["label"]
        row = {
            "entry": manifest["id"], "split": manifest.get("split", "development"),
            "root": root, "label": label, "outcome": outcome,
            "pattern": h.get("codingPattern", "unspecified"), "completeness": completeness,
        }
        if label == "FSM":
            row["class"] = "TP" if outcome == "MACHINE" else "FN"
        elif label == "NON_FSM":
            row["class"] = "FP" if outcome == "MACHINE" else "TN"
        else:
            row["class"] = "UNCERTAIN"
        row["abstention"] = outcome == "CANDIDATE"

        if label == "FSM":
            truth_raw = h.get("transitions", [])
            row["truth_transitions"] = len(truth_raw)
            if outcome == "MACHINE":
                m = machines[root]
                nodes = Nodes(m)
                key = (lambda f, e, t: (f, e, t)) if with_events else (lambda f, e, t: (f, t))
                truth = {key(nodes.qualify(t["from"]), t.get("event"), nodes.qualify(t["to"]))
                         for t in truth_raw}
                resolved = set()
                entry_edges = unresolved = unresolved_known = 0
                for t in m["transitions"]:
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
                    resolved.add(key(nodes.by_id.get(t["from"], canon(t["from"])), t.get("event"),
                                     nodes.by_id.get(t["to"], canon(t["to"]))))
                matched = resolved & truth
                direct_t = {nodes.qualify(n) for n in h.get("directBranches", [])}
                direct_s = {canon(s["qualifiedName"]) for s in m["directBranches"]}
                atomic_t = {nodes.qualify(n) for n in h.get("atomicStates", [])}
                for enum, constants in h.get("enumChildStates", {}).items():
                    atomic_t |= {nodes.qualify(canon(enum) + "." + c) for c in constants}
                atomic_s = {canon(s["qualifiedName"]) for s in m["atomicStates"]}
                row.update({
                    "resolved_edges": len(resolved), "matched_edges": len(matched),
                    "unresolved_edges": unresolved, "unresolved_with_known_source": unresolved_known,
                    "entry_edges": entry_edges,
                    "direct_exact": direct_t == direct_s, "direct_jaccard": jaccard(direct_t, direct_s),
                    "atomic_exact": atomic_t == atomic_s, "atomic_jaccard": jaccard(atomic_t, atomic_s),
                    "missed_transitions": sorted(map(str, truth - resolved)),
                    "unlabelled_edges": sorted(map(str, resolved - truth)),
                })
            else:
                row["matched_edges"] = 0
        rows.append(row)
    return rows


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

    def ratio(a, b):
        return None if b == 0 else a / b

    return {
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
        "unresolved_edges": sum(r.get("unresolved_edges", 0) for r in tp_rows),
        "unresolved_with_known_source": sum(r.get("unresolved_with_known_source", 0) for r in tp_rows),
    }


def fmt(v):
    if v is None:
        return "n/a"
    if isinstance(v, float):
        return f"{v:.3f}"
    return str(v)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifests", nargs="+")
    ap.add_argument("--json", action="store_true", help="print the scores as JSON")
    ap.add_argument("--with-events", action="store_true", help="match transitions on (from, event, to)")
    ap.add_argument("--split", choices=["development", "heldout"], help="score one split only")
    args = ap.parse_args()

    rows = []
    for m in args.manifests:
        rows.extend(score_entry(m, args.with_events))
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

    print("Per hierarchy")
    print(f"  {'entry':<18} {'root':<52} {'label':<9} {'outcome':<12} {'class':<9} edges")
    for r in rows:
        edges = (f"{r['matched_edges']}/{r['resolved_edges']} resolved matched, "
                 f"{r['matched_edges']}/{r['truth_transitions']} labelled found, "
                 f"{r['unresolved_edges']} unresolved") if "resolved_edges" in r else ""
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
                  f"recall(conditional)={fmt(s['transition_recall_conditional'])} "
                  f"recall(overall)={fmt(s['transition_recall_overall'])} "
                  f"unresolved={s['unresolved_edges']} ({s['unresolved_with_known_source']} with a known source)")


if __name__ == "__main__":
    main()
