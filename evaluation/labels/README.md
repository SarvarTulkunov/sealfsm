# Ground-truth labels

One JSON file per corpus entry, `<id>.json`, copied from
`../templates/labels.template.json`, and written **before the tool's output for
that entry is inspected** (`../PROTOCOL.md`, step 2). Commit a labels file before
its outcome file: that ordering in the history is what substantiates
`labelledBeforeToolOutput`.

This directory is empty on purpose. The labels are the researcher's
independent judgement, and they cannot be produced by the process that built the
tool.
