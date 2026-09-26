"""Unit tests for score.py's transition identity (evaluation/PROTOCOL.md, Decision 3).

    python -m unittest discover -s scripts/evaluation -p "test_*.py" -v

Standard library only. The fixtures are in-memory label and result dicts, so these
tests pin the METRIC, independently of what the tool happens to extract.
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score  # noqa: E402

ROOT = "p.Conn"


def machine(*edges, states=("Idle", "Active", "Done")):
    """A schema-1 machine for ROOT; an edge is (from, event, to[, resolved])."""
    nodes = [{"id": s, "qualifiedName": f"p.Conn.{s}"} for s in states]
    return {
        "root": ROOT, "directBranches": nodes, "atomicStates": nodes, "compositeNodes": [],
        "transitions": [{"from": e[0], "event": e[1], "to": e[2],
                         "resolved": e[3] if len(e) > 3 else True} for e in edges],
    }


def fsm(*transitions, root=ROOT, states=("Idle", "Active", "Done")):
    """A labelled FSM; a transition is (from, event, to)."""
    return {
        "root": root, "label": "FSM", "codingPattern": "centralized-switch",
        "directBranches": list(states), "atomicStates": list(states),
        "transitions": [{"from": f, "event": e, "to": t} for f, e, t in transitions],
    }


def row(label, m, mode=score.EVENTS):
    outcomes = {ROOT: "MACHINE"} if m is not None else {ROOT: "CANDIDATE"}
    machines = {ROOT: m} if m is not None else {}
    return score.score_hierarchy(label, outcomes, machines, mode)


TWO_EVENTS = (("Idle", "start", "Active"), ("Idle", "resume", "Active"))


class EventAwareIdentity(unittest.TestCase):

    def test_two_events_on_one_state_pair_both_found(self):
        s = score.summarize([row(fsm(*TWO_EVENTS), machine(*TWO_EVENTS))])
        self.assertEqual((s["transitions_matched"], s["transitions_labelled_tp"]), (2, 2))
        self.assertEqual(s["transition_recall_conditional"], 1.0)
        self.assertEqual(s["transition_precision"], 1.0)

    def test_two_events_on_one_state_pair_one_found(self):
        r = row(fsm(*TWO_EVENTS), machine(("Idle", "start", "Active")))
        s = score.summarize([r])
        self.assertEqual((s["transitions_matched"], s["transitions_labelled_tp"]), (1, 2))
        self.assertEqual(s["transition_recall_conditional"], 0.5)
        self.assertEqual(s["transition_recall_overall"], 0.5)
        self.assertEqual(s["transition_precision"], 1.0)
        self.assertEqual(r["missed_transitions"], ["p.Conn.Idle --[resume]--> p.Conn.Active"])
        self.assertEqual(r["extra_transitions"], [])

    def test_event_mismatch_does_not_match(self):
        r = row(fsm(("Idle", "start", "Active")), machine(("Idle", "stop", "Active")))
        self.assertEqual(r["matched_edges"], 0)
        self.assertEqual(r["missed_transitions"], ["p.Conn.Idle --[start]--> p.Conn.Active"])
        self.assertEqual(r["extra_transitions"], ["p.Conn.Idle --[stop]--> p.Conn.Active"])

    def test_identical_duplicate_triples_count_once(self):
        label = fsm(("Idle", "start", "Active"), ("Idle", "start", "Active"), ("Active", "stop", "Done"))
        # the tool may also report one triple twice, e.g. under two different guards
        m = machine(("Idle", "start", "Active"), ("Idle", "start", "Active"))
        r = row(label, m)
        self.assertEqual(r["truth_label_entries"], 3)
        self.assertEqual((r["matched_edges"], r["resolved_edges"], r["truth_transitions"]), (1, 1, 2))

    def test_eventless_matches_only_eventless(self):
        eventless = fsm(("Idle", None, "Active"))
        self.assertEqual(row(eventless, machine(("Idle", None, "Active")))["matched_edges"], 1)
        self.assertEqual(row(eventless, machine(("Idle", "start", "Active")))["matched_edges"], 0)
        named = fsm(("Idle", "start", "Active"))
        self.assertEqual(row(named, machine(("Idle", None, "Active")))["matched_edges"], 0)

    def test_missed_machine_contributes_to_overall_recall(self):
        found = row(fsm(*TWO_EVENTS), machine(*TWO_EVENTS))
        missed = row(fsm(("Idle", "a", "Active"), ("Idle", "b", "Active"), ("Idle", "b", "Active"),
                         ("Active", None, "Done")), None)
        self.assertEqual(missed["class"], "FN")
        self.assertEqual(missed["truth_transitions"], 3)  # distinct triples, not 4 label entries
        self.assertEqual(len(missed["missed_transitions"]), 3)
        s = score.summarize([found, missed])
        self.assertEqual(s["transition_recall_conditional"], 1.0)
        self.assertEqual((s["transitions_matched"], s["transitions_labelled_all"]), (2, 5))
        self.assertEqual(s["transition_recall_overall"], 2 / 5)

    def test_missed_machine_dedups_across_name_spellings(self):
        label = fsm(("Idle", "a", "Active"), ("p.Conn.Idle", "a", "p.Conn.Active"),
                    states=("p.Conn.Idle", "p.Conn.Active"))
        self.assertEqual(row(label, None)["truth_transitions"], 1)

    def test_unresolved_and_pseudo_edges_are_not_matched(self):
        r = row(fsm(("Idle", "start", "Active")),
                machine(("Idle", "start", "Active", False), ("<unknown>", "start", "Active")))
        self.assertEqual((r["matched_edges"], r["resolved_edges"], r["unresolved_edges"]), (0, 0, 1))


class StatePairsMode(unittest.TestCase):

    def test_state_pairs_collapse_events_on_both_sides(self):
        r = row(fsm(*TWO_EVENTS), machine(("Idle", "start", "Active")), mode=score.STATE_PAIRS)
        self.assertEqual((r["matched_edges"], r["resolved_edges"], r["truth_transitions"]), (1, 1, 1))
        self.assertEqual(r["missed_transitions"], [])
        self.assertEqual(r["transition_identity"], score.STATE_PAIRS)

    def test_state_pairs_ignore_event_mismatch(self):
        r = row(fsm(("Idle", "start", "Active")), machine(("Idle", None, "Active")), mode=score.STATE_PAIRS)
        self.assertEqual(r["matched_edges"], 1)


class LabelValidation(unittest.TestCase):

    def test_missing_event_field_is_an_error(self):
        label = fsm(("Idle", "start", "Active"))
        del label["transitions"][0]["event"]
        with self.assertRaises(score.LabelError) as ctx:
            row(label, machine())
        self.assertIn("missing 'event' field", str(ctx.exception))
        self.assertIn("Idle -> Active", str(ctx.exception))

    def test_empty_event_string_is_an_error(self):
        with self.assertRaises(score.LabelError):
            row(fsm(("Idle", "", "Active")), machine())

    def test_missing_event_is_an_error_for_a_missed_machine_too(self):
        label = fsm(("Idle", "start", "Active"))
        del label["transitions"][0]["event"]
        with self.assertRaises(score.LabelError):
            row(label, None)

    def test_non_fsm_needs_no_transitions(self):
        r = row({"root": ROOT, "label": "NON_FSM"}, None)
        self.assertEqual(r["class"], "TN")

    def test_score_entry_reports_missing_event_as_validation_error(self):
        with tempfile.TemporaryDirectory() as d:
            label = fsm(("Idle", "start", "Active"))
            del label["transitions"][0]["event"]
            files = {
                "labels.json": {"labelledBeforeToolOutput": True, "hierarchies": [label]},
                "result.json": {"tool": "sealfsm", "schema": 1,
                                "outcomes": [{"root": ROOT, "outcome": "MACHINE"}], "machines": [machine()]},
                "manifest.json": {"id": "t", "labels": "labels.json", "outcome": "result.json"},
            }
            for name, content in files.items():
                with open(os.path.join(d, name), "w", encoding="utf-8") as fh:
                    json.dump(content, fh)
            with self.assertRaises(SystemExit) as ctx:
                score.score_entry(os.path.join(d, "manifest.json"))
            self.assertIn("invalid labels", str(ctx.exception.code))
            self.assertIn("missing 'event' field", str(ctx.exception.code))


class CommandLine(unittest.TestCase):

    def test_events_are_the_default(self):
        self.assertEqual(score.build_parser().parse_args(["m.json"]).match, score.EVENTS)

    def test_with_events_is_accepted_and_selects_the_default(self):
        args = score.build_parser().parse_args(["m.json", "--with-events"])
        self.assertTrue(args.with_events)
        self.assertEqual(args.match, score.EVENTS)

    def test_state_pairs_is_explicit(self):
        self.assertEqual(score.build_parser().parse_args(["m.json", "--match", "state-pairs"]).match,
                         score.STATE_PAIRS)


if __name__ == "__main__":
    unittest.main()
