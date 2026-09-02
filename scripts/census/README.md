# The F27 census tools

Two parse-only passes over a Java source tree, measuring **transition-table
orientation**: is a state committed from inside a switch over the state
(state-major) or over the input (Σ-major)? The result and its threats to validity
are in `CENSUS.md`; this is how to reproduce it.

Built on `com.sun.source` — javac's own parser, no resolution — so it scales to
tens of thousands of files and every judgement it makes is one a reader could
make from the same text. `Census` classifies value-producing switches;
`Census2` classifies **state fields** (a reference-typed field assigned in ≥ 2
places) by how each is written, following calls one hop, which is the depth the
tool's own k = 1 commit probe uses.

```bash
javac -d out scripts/census/Census.java scripts/census/Census2.java

# JDK 21 (unpack lib/src.zip first)
java -cp out Census2 /path/to/jdk-src

# every -sources.jar in the local Maven repository, unpacked one dir per artifact
java -Xmx6g -cp out Census2 /path/to/libs
```

Validate before trusting: run either tool over `examples/`, where the answer is
known fixture by fixture. That is how both were checked here.
