#!/usr/bin/env bash
# Capture the CLI's output for every fixture directory into one tree, so a
# refactor can be checked for faithfulness by diffing two captures.
#
#   mvn package -DskipTests
#   scripts/capture-golden.sh target/golden-pre
#   ...refactor...
#   mvn package -DskipTests && scripts/capture-golden.sh target/golden-post
#   diff -r target/golden-pre target/golden-post     # must be empty
#
# One output directory per fixture, never a combined --src: two top-level types
# in one package make Spoon refuse to build the model, and Main names its files
# after the MACHINE, so a flat --out lets two same-named machines overwrite each
# other silently. Same reason scripts/build-run-render.ps1 does it this way.
#
# `.dot` and `.scxml` are the faithfulness contract. `summary.txt` is captured
# beside them (it carries the counts and the diagnostics, where an intended
# capability gain shows up first) and is normalised for the two things that
# differ between two captures of the same code: the absolute output path, and
# SLF4J's provider notice.
set -u

DEST="${1:-target/golden-pre}"
JAR="target/sealfsm.jar"

if [ ! -f "$JAR" ]; then
  echo "error: $JAR missing — run 'mvn package -DskipTests' first" >&2
  exit 1
fi

rm -rf "$DEST"
mkdir -p "$DEST"

count=0
for dir in examples/*/; do
  [ -d "$dir" ] || continue
  name="$(basename "$dir")"
  mkdir -p "$DEST/$name"
  java -jar "$JAR" --src "$dir" --out "$DEST/$name" \
      > "$DEST/$name/summary.txt" 2>&1
  count=$((count + 1))
done

python scripts/normalise-golden.py "$DEST"
echo "captured $count fixture(s) into $DEST"
