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

# The diagnostics matter as much as the assertion. The first version of this
# looked for the proxy log at a path that did not exist, so the stage failed
# and then said nothing at all -- and the cause had to be guessed at from the
# source instead of read from the run. A failing check that cannot say why
# wastes the run it just spent.
if [ -f "$USER_FILE" ]; then
    log "the user file exists but holds no such group:"
    sed 's/^/    /' "$USER_FILE" | head -20
    cp "$USER_FILE" "$EVIDENCE/luckperms-user.json"
else
    log "LuckPerms has no file for this player. Everything under its data dir:"
    find "$RUN/proxy/plugins/luckperms" -maxdepth 3 2>&1 | sed 's/^/    /' | head -20
fi

# Three questions, in the order they fail: did LuckPerms load at all, did
# soulbind see it, and did the drain ever run?
log "did LuckPerms load?"
grep -iE 'luckperms' "$RUN/velocity.log" 2>/dev/null | head -8 | sed 's/^/    /'     || log "    (nothing about LuckPerms in the proxy log)"
log "what soulbind said about it:"
grep -iE 'soulbind|group sync|permissions plugin' "$RUN/velocity.log" 2>/dev/null     | head -12 | sed 's/^/    /' || log "    (nothing from soulbind in the proxy log)"

cp "$RUN/velocity.log" "$EVIDENCE/velocity-groups.log" 2>/dev/null || true

# And whether core emitted anything to act on, which separates "the connector
# is deaf" from "there was nothing to hear".
#
# The FIRST version of this asked audit.query, which is the wrong store: the
# audit log records operator-visible actions, and requirements-met lives in the
# event outbox. It dumped twenty audit rows, truncated them at 600 bytes, and
# answered a question nobody had asked -- so a run that failed here still could
# not say which half was broken, and the cause had to be guessed at from the
# source a second time.
#
# `after: 0` explicitly, so this reads the outbox from the beginning rather than
# from the ADMIN credential's own cursor. Subscribing never advances a cursor
# (core advances only on ack), so this observes without disturbing what the
# proxy is about to receive.
log "did core emit requirements-met?"
EVENTS=$EVIDENCE/events.json
sh "$REPO/tools/rpc.sh" "$CORE" "$ADMIN" event.subscribe '{"after":0,"limit":500}' \
    > "$EVENTS" 2>/dev/null || log "    (event.subscribe failed)"
if [ -s "$EVENTS" ]; then
    # One event per line, so grep can answer rather than a 600-byte prefix.
    MET=$(tr '{' '\n' < "$EVENTS" | grep 'subject.requirements-met' | head -8)
    if [ -n "$MET" ]; then
        log "core DID emit it -- so the event existed and the connector did not act:"
        echo "$MET" | sed 's/^/    /'
    else
        log "core emitted NO requirements-met at all. Every type in the outbox:"
        tr '{' '\n' < "$EVENTS" | sed -n 's/.*"type":"\([^"]*\)".*/\1/p' \
            | sort | uniq -c | sed 's/^/    /'
    fi
fi

# How far the PROXY's own cursor has moved. Together with the line above this
# is the whole answer: emitted and acked past means the connector saw it and
# dropped it; emitted and not acked means the connector never got a poll
# through. Subscribing as the proxy with no `after` reports that connector's
# cursor without moving it.
if [ -f "$RUN/core/creds.env" ]; then
    # shellcheck disable=SC1091
    . "$RUN/core/creds.env"
    log "the proxy connector's event cursor, and what is still queued for it:"
    sh "$REPO/tools/rpc.sh" "$CORE" "$PROXY_CRED" event.subscribe '{"limit":5}' 2>/dev/null \
        | tr '{' '\n' | sed -n 's/.*"cursor":\([0-9]*\).*/    cursor=\1/p;s/.*"type":"\([^"]*\)".*/    queued: \1/p' \
        | head -10
fi
exit 1
