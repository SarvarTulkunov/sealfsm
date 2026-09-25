#!/usr/bin/env bash
# Mechanics self-test for scripts/evaluation/score.py: runs the tool with --json on
# two fixtures written FOR SealFSM, and scores them against labels taken from the
# fixtures' documented intent. It checks that the scorer reads the files and counts
# the outcomes; it is not evidence of accuracy (evaluation/PROTOCOL.md, Decision 3).
#
#   mvn package -DskipTests && bash scripts/evaluation/selftest/run.sh
set -eu
cd "$(dirname "$0")/../../.."
for fixture in converters typedhandler; do
  java -jar target/sealfsm.jar --src "examples/$fixture" --json --quiet \
      --out "target/eval-selftest/$fixture" > /dev/null
done
python scripts/evaluation/score.py \
    scripts/evaluation/selftest/converters.manifest.json \
    scripts/evaluation/selftest/typedhandler.manifest.json "$@"
