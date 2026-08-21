#!/bin/sh
# Did the proxy's group effector actually reach LuckPerms?
#
#     group-check.sh <run-dir> <evidence-dir> <core-url> <admin-credential>
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
# The stage that would have caught DECISIONS 10.23. The proxy's group effector
# had never been connected to anything -- no event drain, and a resolver that
# always returned empty -- and no tier could have noticed, because no
# permissions plugin was in the composed stack.
#
# What it asserts is deliberately the far end of the chain: a player linked
# through the REAL flow, core emitting requirements-met, the connector's drain
# picking it up, and LuckPerms holding the group. Anything short of the last
# step is what was already believed and was not true.
#
# Reads LuckPerms' own JSON storage rather than asking the proxy console: the
# file is the authority, and a console reply could be produced by a plugin that
# had not persisted anything.
set -eu

RUN=$1
EVIDENCE=$2
CORE=$3
ADMIN=$4
REPO=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
GROUP=soulbind-linked
GATE=game.join

log() { echo "[groups] $*"; }
mkdir -p "$EVIDENCE"

PLAYER_FILE="$RUN/core/linked-player.txt"
if [ ! -s "$PLAYER_FILE" ]; then
    log "FAIL no linked player recorded at $PLAYER_FILE; the up stage links one"
    exit 1
fi
PLAYER=$(tr -d ' \n' < "$PLAYER_FILE")
log "the player linked through the real flow: $PLAYER"

# The gate must actually require something, or requirements-met is never
# emitted and a pass here would mean nothing.
sh "$REPO/tools/rpc.sh" "$CORE" "$ADMIN" rule.set \
    "{\"gate\":\"$GATE\",\"requiredKinds\":[\"game\"],\"requireLinked\":true,\
\"graceSeconds\":0,\"defaultEffect\":\"deny\"}" > "$EVIDENCE/group-rule.json"
log "rule set: $GATE requires a linked game identity"

USER_FILE="$RUN/proxy/plugins/luckperms/json-storage/users/$PLAYER.json"

# The drain runs every five seconds and LuckPerms writes asynchronously, so
# this waits rather than checking once. Bounded, and the failure says which
# half is missing.
i=0
while [ "$i" -lt 40 ]; do
    if [ -f "$USER_FILE" ] && grep -q "group\.$GROUP" "$USER_FILE" 2>/dev/null; then
        cp "$USER_FILE" "$EVIDENCE/luckperms-user.json"
        log "OK LuckPerms holds group.$GROUP for $PLAYER"
        exit 0
    fi
    i=$((i + 1))
    sleep 2
done

log "FAIL LuckPerms never recorded group.$GROUP for $PLAYER after 80s"
if [ -f "$USER_FILE" ]; then
    log "the user file exists but has no such group:"
    sed 's/^/    /' "$USER_FILE" | head -20
    cp "$USER_FILE" "$EVIDENCE/luckperms-user.json"
else
    log "LuckPerms has no file for this player at all:"
    ls -la "$RUN/proxy/plugins/luckperms/json-storage/users/" 2>&1 | sed 's/^/    /' | head -10
fi
log "the connector's own view is in the proxy log:"
grep -iE 'soulbind|group sync|luckperms' "$RUN/../proxy.log" 2>/dev/null | tail -15 | sed 's/^/    /' || true
exit 1
