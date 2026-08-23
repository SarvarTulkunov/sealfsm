# FIXLOG.md

Every change to the SealFSM analyser made in response to a corpus finding is logged
here, one line per change, appended by `implementer`. The rules are §9 of
`CORPUS_PROTOCOL.md` and are binding:

1. No change may name or branch on an identifier occurring in only one example.
2. Fixes are justified at the level of a Spoon node shape or idiom.
3. Every fix requires a second, independently sourced fixture of the same shape, from a
   different origin project, that also passes afterwards. A `FIX-REQUIRED` soundness fix
   is the sole exception and records `soundness` in the generality-fixture column.

This file is the input to the overfitting audit: `senior-advisor` compares each passing
example's `added_at_tool_commit` against the trigger column here to separate a
**pre-existing pass** from a **fitted pass**. A fix with no generality fixture cited is
reported as a special case wearing an algorithm's clothes.

Format — pipe-separated, one line per change, newest at the bottom:

```
<tool-commit> | <F-code|NEW> | <trigger example-id> | <generality fixture id> | <node-shape rationale>
```

| tool-commit | F-code | trigger example-id | generality fixture id | node-shape rationale |
|---|---|---|---|---|
