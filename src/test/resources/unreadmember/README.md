# `unreadmember/` — inputs that deliberately do not compile

Three variants of one two-state hierarchy, holding everything fixed except **how
the file that produces the successor refers to the state whose declaration is
missing**. `Amber.java` is never present in any of them: that absence *is* the
condition under test, and it cannot be encoded in `examples/`, because every
example there is compiled by `javac` as part of being a fixture.

They exist because membership recovery (see the *Membership recovery* section of
CLAUDE.md) admits the qualified name Spoon guessed for an unresolved `permits`
reference. Whether that is safe depends entirely on whether Spoon can guess the
same name for a *use site* that denotes something else — which is a question
about Spoon's name resolution under `noClasspath`, not about SealFSM, and so is
measured here rather than argued.

| variant | how `Red` names the successor | what Spoon guesses | recovery |
|---|---|---|---|
| `samepkg/` | bare `new Amber()`, same package | `samepkg.Amber` | **admitted** — matches the permits spelling |
| `explicitimport/` | `import ext.Amber;` then `new Amber()` | `ext.Amber` | **declined** — the import is honoured, names differ |
| `wildcardimport/` | `import ext.*;` then `new Amber()` | bare `Amber`, no package | **declined** — degraded guess matches nothing |

`explicitimport/` is the **fabrication control**, and the sharp one. Since
`permits` requires the same package outside a named module, the only way a simple
name there can denote a foreign type is an explicit single-type import — so if
Spoon ignored that import and guessed the current package, recovery would publish
a **resolved** edge to a type that is not the state. It does not. Delete the
import and the same file becomes `samepkg/`, which is why the two are held one
line apart.

`wildcardimport/` is the opposite error and is a genuine, recorded **recall gap**:
per JLS §7.5.2 a same-package type shadows a wildcard import, so `Amber` really
does denote the permitted state and the edge is real — but Spoon degrades the
guess to a bare simple name, nothing matches, and the edge stays unresolved. It is
kept so that the limitation is measured rather than discovered later as a
surprise.

The directory names double as the package names, so each variant directory is its
own source root.
