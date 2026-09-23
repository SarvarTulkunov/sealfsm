#!/usr/bin/env bash
# The inter-procedural depth sweep (FIXLOG F29): the empirical justification for
# the committed default of TransitionExtractor's depth budget.
#
#   mvn package -DskipTests
#   bash scripts/depth-sweep.sh            # k = 1 2 3 4 over examples/* and the fold fixtures
#   bash scripts/depth-sweep.sh 1 2 3 4 5  # any list of budgets
#
# Each k runs in its own JVM, because the budget is read once per JVM (the
# `sealfsm.maxInterprocDepth` system property, used for nothing else). Each fixture
# directory is its own model, exactly as scripts/capture-golden.sh does it. Output
# is tab-separated: k, axis cell, machines, transitions, resolved, unresolved; the
# TOTAL line carries the in-process analysis time in ms (model building excluded:
# it is identical at every k).
#
# Never pass corpus/: it is the holdout set, and nothing here may be tuned on it.
set -u

JAR="target/sealfsm.jar"
OUT="target/depthsweep"
if [ ! -f "$JAR" ]; then
  echo "error: $JAR missing — run 'mvn package -DskipTests' first" >&2
  exit 1
fi

SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

mkdir -p "$OUT"
javac -cp "$JAR" -d "$OUT" scripts/depthsweep/DepthSweep.java || exit 1

KS="${*:-1 2 3 4}"
for k in $KS; do
  java -Dsealfsm.maxInterprocDepth="$k" -cp "$JAR$SEP$OUT" DepthSweep \
      'examples/*' src/test/resources/bindingframes src/test/resources/foldbinding \
      2>/dev/null | grep '^k='
done
