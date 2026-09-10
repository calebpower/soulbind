#!/bin/sh
# The control-and-mutants check for measures-verdict.py.
#
#     measures-selftest.sh
#
# Copyright (c) 2026 Caleb L. Power
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# WHY THIS EXISTS. The measures stage exists because a whole tier stayed green
# against a real defect. A verdict added in response to that, which nobody has
# watched reject anything, is the same mistake one level up -- so each shape it
# is supposed to refuse is fed to it here, and the CONTROL is what stops a
# verdict that refuses everything from looking rigorous.
#
# The mutant that matters is `only-measured`: it is the exact event stream the
# deployed 0.2.0 produced, sixty times over, while every test passed.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM

failures=0

# case <name> <expected-exit> <json>
case_is() {
    name=$1
    expected=$2
    printf '%s' "$3" > "$WORK/$name.json"
    out=$(python3 "$HERE/measures-verdict.py" "$WORK/$name.json" activity.measured 2>&1) && code=0 || code=$?
    if [ "$code" -eq "$expected" ]; then
        printf '  ok    %-16s exit %d\n' "$name" "$code"
    else
        printf '  FAIL  %-16s exit %d, wanted %d\n' "$name" "$code" "$expected"
        printf '%s\n' "$out" | sed 's/^/          /'
        failures=$((failures + 1))
    fi
}

ev() {
    printf '{"gate":"%s","type":"%s","identityRef":"%s"}' "$1" "$2" "$3"
}

MET=subject.requirements-met
G=activity.measured

# CONTROL: measured on game, and a sibling of another kind moved too.
case_is control 0 "{\"payload\":{\"events\":[
  $(ev $G $MET game:9f2c),$(ev $G $MET harness:third-1)]}}"

# THE DEPLOYED DEFECT: only the identity that was measured.
case_is only-measured 2 "{\"payload\":{\"events\":[$(ev $G $MET game:9f2c)]}}"

# Nothing emitted at all, though the rule is satisfied by any observation.
case_is nothing-moved 3 '{"payload":{"events":[]}}'

# Events exist, but not for the identity actually measured -- the stage would be
# asserting about somebody else.
case_is not-measured 4 "{\"payload\":{\"events\":[$(ev $G $MET harness:third-1)]}}"

# Another gate's transitions must not be mistaken for this one's. Without the
# gate filter, the control would pass on events that say nothing about measures.
case_is other-gate-ignored 3 "{\"payload\":{\"events\":[
  $(ev chat.linked $MET game:9f2c),$(ev chat.linked $MET harness:third-1)]}}"

# So must the wrong event TYPE: a lost is not a met.
case_is lost-is-not-met 3 "{\"payload\":{\"events\":[
  $(ev $G subject.requirements-lost game:9f2c),
  $(ev $G subject.requirements-lost harness:third-1)]}}"

echo
if [ "$failures" -ne 0 ]; then
    echo "measures-verdict selftest: $failures case(s) FAILED" >&2
    exit 1
fi
echo "measures-verdict selftest: all cases pass"
