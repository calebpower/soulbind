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

PLAYER=$(cat "$RUN/core/linked-player.txt")

# ---------------------------------------------------------------------------
# THE MEASURE NAME AND WINDOW, READ FROM THE CONNECTOR'S OWN CONFIG.
#
# Not restated here, and that is the whole point. This stage used to declare a
# rule for a 604800s window while stack.sh configured the reporter for
# 1814400s. Core matches the two EXACTLY -- PolicyEngine returns
# MEASURE_WINDOW_MISMATCH when they differ -- so the rule could never be
# satisfied, and the stage would have failed with "the measurement moved
# NOTHING" the moment it had a roster to work with.
#
# 604800 was right when the connector reported a seven-day playtime sum. The
# activity-index change made the reported window three weeks and left this
# constant behind. A second place that states the window is a second place that
# can disagree with the first, so there is now one: the reporter's.
#
# tomllib, not sed: migrate-check.sh has the long version of why hand-rolled
# TOML parsing is a false economy, and it was right.
PLAN_CONFIG="$RUN/proxy/plugins/soulbind-plan/soulbind-plan.toml"
[ -f "$PLAN_CONFIG" ] || { log "no connector config at $PLAN_CONFIG"; exit 1; }

read_measure() {
    python3 -c '
import sys, tomllib
with open(sys.argv[1], "rb") as fh:
    cfg = tomllib.load(fh)
measure = cfg.get("measure") or {}
value = measure.get(sys.argv[2])
if value is None:
    sys.exit("no measure." + sys.argv[2] + " in " + sys.argv[1])
print(value)
' "$PLAN_CONFIG" "$1"
}

MEASURE_NAME=$(read_measure name)
WINDOW=$(read_measure windowseconds)
log "the reporter is configured for '$MEASURE_NAME' over ${WINDOW}s; the rule will ask for exactly that"

mkdir -p "$EVIDENCE"

rpc() {
    "$REPO/tools/rpc.sh" "$CORE_URL" "$HARNESS_CRED" "$1" "$2"
}

# ---------------------------------------------------------------------------
# THE SESSION THIS STAGE MEASURES, supplied rather than played.
#
# The reporter's roster is "everyone with a session ending in the window", read
# from the dashboard's own tables. This harness runs the dashboard ON THE PROXY
# ONLY -- stack.sh installs it into proxy/plugins and Paper gets no plugins at
# all -- and a proxy-side dashboard records no sessions. Backends do. The estate
# runs it in both places, five backends writing the sessions the proxy's copy
# reads, which is why the roster is never empty there and was always empty here.
#
# So the first run of this stage failed with "no activity measurement reached
# core in 90s", and it was RIGHT to: the roster was empty, an empty roster is an
# answer, and the reporter correctly reported nothing. The stage's premise --
# that a session would exist -- was the false part.
#
# THIS FAKES NOTHING THE STAGE ASSERTS ON. The defect this stage exists to catch
# lived in core's `measure.report`, which emitted transitions for the one
# identity measured instead of every identity the subject holds. Everything from
# the roster read onwards -- the index the dashboard computes from this row, the
# report to core, what core stores, and which identities it emits for -- runs
# below, untouched, and is soulbind's code. A third party recording a session is
# not.
#
# WHAT THE SEED BUYS, precisely: a NON-EMPTY ROSTER. Nothing more. The reporter
# reports on the players the roster names, so with no session there is no
# roster, no report, and no measurement for the rule to be satisfied by.
#
# WHAT IT DOES NOT BUY, measured rather than assumed: a non-zero index. The
# dashboard computes 0 from this row, and it computed 0 with a per-server
# registration row added beside it too -- that was tried, changed nothing, and
# was removed rather than left in place with a rationale the evidence
# contradicts. Its index is assembled from a player container, and on an install
# with no game server behind it there is evidently nothing there to assemble
# from. Chasing that further is chasing a third party's internals.
#
# THAT IS TOLERABLE HERE, and the reason is the threshold. This gate asks for
# `atLeast: 0`, so a zero index satisfies it, and the assertion below is about
# WHICH IDENTITIES core emitted for -- never about the magnitude. Tier 1 owns
# boundaries. A zero that ARRIVED is also not the same as nothing arriving: the
# source turns an unreadable index into an absence, so a measurement reaching
# core at all is proof the index path ran and returned a finite number.
#
# THREE HOURS, ended five minutes ago, is therefore chosen for one property
# only: it sits comfortably inside the reported window, so the roster query --
# which bounds on session_end -- cannot fail to see it, and no clock skew or
# slow stage can push it outside.
if [ -z "${SOULBIND_PLAN_DB_HOST:-}" ]; then
    log "no dashboard database coordinates; this stage runs on the axis that has one"
    exit 1
fi
if [ -z "${JAVA:-}" ] || [ ! -x "$JAVA" ]; then
    log "no JAVA; run.sh resolves the toolchain before this stage and did not"
    exit 1
fi

LIB="$REPO/core/build/install/core/lib"
[ -d "$LIB" ] || { log "core is not installed at $LIB -- run the up stage first"; exit 1; }

PLAN_URL="jdbc:mariadb://${SOULBIND_PLAN_DB_HOST}:${SOULBIND_PLAN_DB_PORT:-3306}/${SOULBIND_PLAN_DB_NAME:-plan}"

log "seeding one finished session for $PLAYER, because a proxy-side dashboard records none"
if ! "$JAVA" --enable-native-access=ALL-UNNAMED \
        -cp "$LIB/*" "$HERE/PlanSessionFixture.java" \
        "$PLAN_URL" "${SOULBIND_PLAN_DB_USER:-root}" "${SOULBIND_PLAN_DB_PASSWORD:-}" \
        "$PLAYER" 10800 300 > "$EVIDENCE/measures-session.txt" 2>&1; then
    log "the session fixture failed, so the roster would have stayed empty:"
    sed 's/^/    /' "$EVIDENCE/measures-session.txt" >&2
    exit 1
fi
cat "$EVIDENCE/measures-session.txt"

# A gate whose ONLY requirement is that a measure has been reported. Zero, so it
# is satisfied the moment one arrives and never before -- an absent measure is
# its own refusal, so this is a clean edge rather than a threshold race.
log "declaring $GATE, satisfied by any reported observation"
rpc rule.set "{\"gate\":\"$GATE\",\"requireLinked\":false,\"requiredKinds\":[],
    \"graceSeconds\":0,\"defaultEffect\":\"deny\",
    \"measure\":{\"name\":\"$MEASURE_NAME\",\"atLeast\":0,
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
            && grep -q "\"$MEASURE_NAME\"" "$EVIDENCE/measures-get.json"; then
        found=1
        break
    fi
    i=$((i + 1))
    sleep 2
done

if [ "$found" -ne 1 ]; then
    log "no activity measurement reached core in 90s"
    log "what core held is in $EVIDENCE/measures-get.json; the session this stage"
    log "seeded is in $EVIDENCE/measures-session.txt"
    log "the reporter is configured in stack.sh under [measure]; check the proxy log"
    exit 1
fi
log "core holds an activity observation for the measured game identity"

# The whole point. Read what core emitted and ask which KINDS it named.
rpc event.subscribe '{"limit":200}' > "$EVIDENCE/measures-events.json"

python3 "$HERE/measures-verdict.py" "$EVIDENCE/measures-events.json" "$GATE"

log "OK -- one measurement, transitions on every identity of the subject"
