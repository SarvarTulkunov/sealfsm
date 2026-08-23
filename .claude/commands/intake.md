Usage: `/intake <name> <spec_url> [implementation: code | file path | "generate"]`

Example: `/intake "PPP LCP automaton" https://www.rfc-editor.org/rfc/rfc1661 generate`

---

Invoke the `spec-intake` subagent with:
- name: the given name (used as a hint for a readable, stable `id` — slugify it)
- spec_url: the given URL
- implementation: the given source if supplied, otherwise instruct `spec-intake` to
  author the implementation itself from the spec

Wait for `spec-intake` to complete and report back: id, split, `provenance_class`,
`expected_verdict`, `oracle_provenance`, compile status, archive status. If it reports a
holdout result, its content is not shown — id and split only, per its own rules. Do not
ask it to elaborate on a holdout example.

If `spec-intake` reports a failure at any step (spec ambiguous beyond a defensible
reading, implementation does not compile, licence/provenance cannot be established for a
supplied implementation), stop here and surface the failure. Do not proceed into the
cycle with an incomplete example.

Once `spec-intake` reports success, **continue directly into the standard cycle**:

1. `qa-tester` — `split == dev` only, unless explicitly told to include the new example
   even if it landed in `holdout` (in which case scope stays `dev` regardless — a
   holdout example is never run outside `UNSEAL-HOLDOUT`, full stop, even on its first
   run).
2. `reporter`
3. `triage-lead`
4. `implementer`, only if `triage-lead` produced FIX-REQUIRED or FIX-ELIGIBLE items
5. `qa-tester` again, for regression
6. `senior-advisor`

**Do not invoke `researcher`** at any point in this command. That is the entire reason
this command and `/harvest` are separate: directed intake from a named source is not
autonomous discovery, and the two must not be blurred into one flow.

If the new example landed in `holdout`, note in the final summary that it was added but
not run — it stays sealed like every other holdout example until an explicit
`UNSEAL-HOLDOUT` cycle.
