# Corpus manifests

One JSON file per corpus entry, `<id>.json`, copied from
`../templates/manifest.template.json`. Each pins one repository to a **full commit
SHA** and records its module, source roots, Java version, classpath, the exact
SealFSM command and revision, the missing-source diagnostics, and its `split`
(`development` or `heldout`). See `../PROTOCOL.md`, step 1.

The source trees themselves are not committed here. `corpus/` at the repository
root is a working area for checkouts. Recreate a checkout from the manifest's URL
and commit.
