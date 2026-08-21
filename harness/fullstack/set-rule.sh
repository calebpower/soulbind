#!/bin/sh
# Writes a gate rule through the REAL admin path.
#
# Not a database insert. The rule arrives the way an operator's tooling would
# send it, so this exercises the same authorization and the same storage a
# deployment does -- and if that path breaks, this breaks with it rather than
# quietly working around it.
#
# The signing lives in tools/rpc.sh. It used to live here, and a second copy
# was about to be written for issuing codes: three canonical forms is three
# chances to drift from the one the golden vectors keep identical across two
# languages.
#
#   set-rule.sh <core> <credential> <gate> [requireLinked] [defaultEffect]
#
# The optional arguments default to what this script did when it took three,
# so its Phase 5 caller is unchanged.
set -eu

CORE=$1
CREDENTIAL=$2
GATE=$3
REQUIRE_LINKED=${4:-true}
DEFAULT_EFFECT=${5:-deny}

HERE=$(cd "$(dirname "$0")" && pwd)

# requireLinked, not a required kind: the harness links a game account to a
# second platform, and "must be linked to something" is the rule that expresses
# what the flow actually establishes.
"$HERE/../../tools/rpc.sh" "$CORE" "$CREDENTIAL" rule.set "$(cat <<JSON
{
  "gate": "$GATE",
  "requireLinked": $REQUIRE_LINKED,
  "requiredKinds": [],
  "graceSeconds": 0,
  "defaultEffect": "$DEFAULT_EFFECT"
}
JSON
)" > /dev/null

echo "rule set on $GATE: requireLinked=$REQUIRE_LINKED default=$DEFAULT_EFFECT"
