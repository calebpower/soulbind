#!/bin/sh
# Does a measurement taken on ONE platform move the gate for the subject's
# identities on the OTHERS?
#
#     measures-check.sh <core-url> <harness-credential> <run-dir> <evidence-dir>
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
# WHY THIS EXISTS. 0.2.0 shipped a defect that this battery could not have
# found. `measure.report` emitted its gate transitions for the identity that was
# measured and no other, where the override operations beside it expand to every
# identity the subject holds. Effectors route on the identity reference and each
# acts only on its own platform's kind, so a measurement taken on one platform
# could never move a role on another -- which is the entire purpose of the
# feature.
#
# It was found by DEPLOYING: sixty measurements against game accounts produced
# only game-kind events, and the chat effector discarded every one, correctly.
# Nobody was ever granted the role and nothing anywhere reported a problem.
#
# After the fix landed, reverting it left this whole tier green on both axes and
# turned exactly one unit test red. The reporter is never enabled here, so the
# reporter-to-effector chain -- the thing the feature IS -- had no live coverage
# at all. That is the same shape as the defect: a part verified, a whole nobody
# drove.
#
# WHAT IT ASSERTS, and what it deliberately does not. It reads the EVENT stream
# rather than watching a role appear on a chat surface. The fullstack tier drives
# the chat connector through a CLI rather than running one with a live effector,
# so there is no chat-side role to observe here -- and the effector's half of the
# story is already covered against the scripted surface in RoleEffectorTest. What
# was broken, and what nothing was watching, is which identities core EMITS for.
# That is what this watches.
#
# The threshold is deliberately ZERO. This is not a test of arithmetic -- Tier 1
# owns the boundaries -- and a zero threshold means the transition fires the
# instant the first observation lands, whatever the harness player happened to
# play. Anything else would make the assertion depend on session data this stage
# does not control.
set -eu

CORE_URL=${1:?core url}
HARNESS_CRED=${2:?harness credential}
RUN=${3:?run directory}
EVIDENCE=${4:?evidence directory}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)

log() { echo "[measures] $*"; }

GATE=activity.measured
WINDOW=604800

PLAYER=$(cat "$RUN/core/linked-player.txt")

mkdir -p "$EVIDENCE"

rpc() {
    "$REPO/tools/rpc.sh" "$CORE_URL" "$HARNESS_CRED" "$1" "$2"
}

# A gate whose ONLY requirement is that a measure has been reported. Zero, so it
# is satisfied the moment one arrives and never before -- an absent measure is
# its own refusal, so this is a clean edge rather than a threshold race.
log "declaring $GATE, satisfied by any reported observation"
rpc rule.set "{\"gate\":\"$GATE\",\"requireLinked\":false,\"requiredKinds\":[],
    \"graceSeconds\":0,\"defaultEffect\":\"deny\",
    \"measure\":{\"name\":\"activityindex\",\"atLeast\":0,
                 \"windowSeconds\":$WINDOW,\"maxAgeSeconds\":3600}}" > "$EVIDENCE/measures-rule.json"

# The reporter sweeps on its own schedule; the harness sets it short. Waiting for
# the measurement to ARRIVE rather than sleeping a guessed interval, so a slow
# stack fails as "no measurement in 90s" and not as a mystery.
log "waiting for the reporter to report an activity index for $PLAYER"
found=0
i=0
while [ "$i" -lt 45 ]; do
    if rpc measure.get "{\"platformKind\":\"game\",\"platformId\":\"$PLAYER\"}" \
            > "$EVIDENCE/measures-get.json" 2>/dev/null \
            && grep -q '"activityindex"' "$EVIDENCE/measures-get.json"; then
        found=1
        break
    fi
    i=$((i + 1))
    sleep 2
done

if [ "$found" -ne 1 ]; then
    log "no activity measurement reached core in 90s"
    log "the reporter is configured in stack.sh under [measure]; check the proxy log"
    exit 1
fi
log "core holds an activity observation for the measured game identity"

# The whole point. Read what core emitted and ask which KINDS it named.
rpc event.subscribe '{"limit":200}' > "$EVIDENCE/measures-events.json"

python3 "$HERE/measures-verdict.py" "$EVIDENCE/measures-events.json" "$GATE"

log "OK -- one measurement, transitions on every identity of the subject"
