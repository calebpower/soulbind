#!/bin/sh
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
# Brings up a real forum with the real extension against a real core.
#
# Runs on the guest, where there is a container engine. Nothing here runs on the
# workstation, which has no database server and no PHP web server -- the same
# constraint that put the second storage backend in a session rather than
# locally.

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
RUN=${RUN:-$REPO/out/flarum-stack}

. "$HERE/pins.env"

DB_NAME=flarum
DB_USER=flarum
DB_PASS=flarum-test
DB_ROOT=flarum-root

CORE_PORT=${CORE_PORT:-8477}
FORUM_PORT=${FORUM_PORT:-8480}
FORUM_URL="http://127.0.0.1:${FORUM_PORT}"

NET=soulbind-forum-net
DB_C=soulbind-forum-db
WEB_C=soulbind-forum-web

log() { echo "[forum] $*"; }

cleanup() {
    status=$?
    log "tearing down"
    for c in "$WEB_C" "$DB_C"; do
        podman rm -f "$c" >/dev/null 2>&1 || true
    done
    podman network rm -f "$NET" >/dev/null 2>&1 || true
    if [ -f "$RUN/core.pid" ]; then
        kill "$(cat "$RUN/core.pid")" 2>/dev/null || true
    fi
    return $status
}
trap cleanup EXIT INT TERM

wait_for() {
    what=$1; shift
    tries=$1; shift
    i=0
    while [ "$i" -lt "$tries" ]; do
        if "$@" >/dev/null 2>&1; then
            log "$what ready after ${i}s"
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    # Loudly, and with the logs. A stack that half-came-up and was tested anyway
    # produces a failure report about soulbind describing somebody else's
    # problem.
    log "$what did not come up in ${tries}s; refusing to test against it"
    podman logs "$DB_C" 2>&1 | tail -20 || true
    podman logs "$WEB_C" 2>&1 | tail -40 || true
    [ -f "$RUN/core.log" ] && tail -30 "$RUN/core.log"
    return 1
}

# A run must not inherit anything a previous run left behind: it would be
# serving the previous run's schema and settings, and a green result would be
# about state nobody in this session created.
cleanup >/dev/null 2>&1 || true
rm -rf "$RUN"
mkdir -p "$RUN/core" "$RUN/forum"

podman network create "$NET" >/dev/null

# --- the database -----------------------------------------------------------
log "starting mariadb"
podman run -d --name "$DB_C" --network "$NET" \
    -e MARIADB_ROOT_PASSWORD="$DB_ROOT" \
    -e MARIADB_DATABASE="$DB_NAME" \
    -e MARIADB_USER="$DB_USER" \
    -e MARIADB_PASSWORD="$DB_PASS" \
    "$FLARUM_DB_IMAGE" >/dev/null

# mariadbd accepts TCP before it is ready to serve, so starting the install on
# container-start produces a flaky failure that reads as a soulbind defect.
wait_for "mariadb" 90 \
    podman exec "$DB_C" mariadb-admin ping -h 127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --silent

# --- core -------------------------------------------------------------------
log "building core"
(cd "$REPO" && ./gradlew --quiet :core:installDist)

cat > "$RUN/core/soulbind.toml" <<TOML
[server]
host = "0.0.0.0"
port = $CORE_PORT

[storage]
backend = "sqlite"
url = "jdbc:sqlite:$RUN/core/soulbind.db"

[linking]
codettlseconds = 600
TOML

CORE_CLI="$REPO/core/build/install/core/bin/core"

log "registering the forum connector"
# Exactly the capabilities the connector needs, and no more. Granting it
# everything would prove the flow works for something holding every capability,
# which is not what a deployment runs -- and it is how a missing grant stays
# hidden until somebody else's install.
FORUM_CRED=$("$CORE_CLI" register --name forum --quiet \
    --capabilities code-display,code-entry,enforcement-point \
    --config "$RUN/core/soulbind.toml")

# The harness stands in for the game side AND for an operator's tooling.
HARNESS_CRED=$("$CORE_CLI" register --name harness --quiet \
    --capabilities code-display,code-entry,config-management,enforcement-point \
    --config "$RUN/core/soulbind.toml")

if [ -z "$FORUM_CRED" ] || [ -z "$HARNESS_CRED" ]; then
    log "a credential did not come back from register; refusing to continue"
    exit 1
fi

log "starting core"
"$CORE_CLI" serve --config "$RUN/core/soulbind.toml" > "$RUN/core.log" 2>&1 &
echo $! > "$RUN/core.pid"
wait_for "core" 60 curl -sf -o /dev/null "http://127.0.0.1:${CORE_PORT}/"

echo "$FORUM_CRED" > "$RUN/forum.credential"
echo "$HARNESS_CRED" > "$RUN/harness.credential"
log "core is up on $CORE_PORT"
