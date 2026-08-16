#!/bin/sh
# Brings up core, Paper and Velocity, runs the player-driver smoke, tears down.
#
# Every wait is bounded and says what it was waiting for. A stack script that
# hangs gets killed by a CI timeout and takes the reason with it.
#
# Nothing here seeds state. The connector credentials are minted through
# `soulbind register`, the code is issued through /link in real chat, and the
# redeem goes through the real protocol -- because a harness that arranged its
# starting state directly would keep passing after the flow broke.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
RUN=${SOULBIND_STACK_RUN:-$HERE/run}
CACHE=${SOULBIND_STACK_CACHE:-$HERE/.cache}
JAVA=${JAVA:-java}
# Gradle's generated start scripts prefer JAVA_HOME over PATH, so exporting only
# PATH lets the CLI run on a different JVM from everything else here -- which is
# how this first failed, with core on 17 and the proxy on 25.
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(cd "$(dirname "$JAVA")/.." && pwd)
    export JAVA_HOME
fi

. "$HERE/pins.env"

CORE_PORT=${CORE_PORT:-7100}
PAPER_PORT=${PAPER_PORT:-25566}
PROXY_PORT=${PROXY_PORT:-25577}

log() { echo "[stack] $*"; }

cleanup() {
    status=$?
    log "tearing down"
    for pidfile in "$RUN"/*.pid; do
        [ -f "$pidfile" ] || continue
        pid=$(cat "$pidfile")
        kill "$pid" 2>/dev/null || true
    done
    # A grace period, then insist. A JVM that ignores SIGTERM will otherwise
    # hold the port and make the NEXT run fail for a reason that has nothing to
    # do with the next run.
    sleep 3
    for pidfile in "$RUN"/*.pid; do
        [ -f "$pidfile" ] || continue
        kill -9 "$(cat "$pidfile")" 2>/dev/null || true
    done
    exit $status
}
trap cleanup EXIT INT TERM

wait_for_port() {
    what=$1
    port=$2
    seconds=$3
    i=0
    while [ "$i" -lt "$seconds" ]; do
        # Not /dev/tcp: that is a bash feature, and this script runs under the
        # system sh on a BSD workstation as well as in a Linux guest. python3 is
        # present in both, and a socket connect is the same question everywhere.
        if python3 -c "
import socket, sys
s = socket.socket()
s.settimeout(1)
sys.exit(0 if s.connect_ex(('127.0.0.1', $port)) == 0 else 1)
" 2>/dev/null; then
            log "$what is listening on $port (${i}s)"
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    log "$what did not listen on $port within ${seconds}s"
    tail -40 "$RUN/$what.log" 2>/dev/null || true
    return 1
}

# The runtime, checked BEFORE anything is downloaded or started.
#
# core targets Java 25 and Paper's floor is 21. Without this the failure arrives
# three steps later as `UnsupportedClassVersionError: class file version 69.0`
# from a start script, which is a true statement about a confusing thing -- and
# on a host where the bare `java` is dispatched to an older install, that is the
# likely first experience.
JAVA_VERSION=$("$JAVA" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')
if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 25 ]; then
    echo "this stack needs Java 25 or newer; '$JAVA' is ${JAVA_VERSION:-unknown}." >&2
    echo "Set JAVA=/path/to/java25/bin/java, or JAVA_HOME to its home." >&2
    "$JAVA" -version >&2 2>&1 || true
    exit 1
fi
log "java $JAVA_VERSION at $JAVA"

"$HERE/fetch.sh"

rm -rf "$RUN"
mkdir -p "$RUN/paper" "$RUN/proxy/plugins" "$RUN/core"

# --- core -------------------------------------------------------------------
log "building"
(cd "$REPO" && ./gradlew --quiet :core:installDist :connector-velocity:jar)

cat > "$RUN/core/soulbind.toml" <<TOML
[server]
host = "127.0.0.1"
port = $CORE_PORT

[storage]
backend = "sqlite"
url = "jdbc:sqlite:$RUN/core/soulbind.db"

[linking]
codettlseconds = 600
TOML

CORE_CLI="$REPO/core/build/install/core/bin/core"

log "registering connectors"
# --quiet, not awk over the human report. Parsing that picked up a log line
# which also said "credential" and produced a multi-line value the HTTP client
# refused outright.
PROXY_CRED=$("$CORE_CLI" register --name proxy --quiet \
    --capabilities code-display,code-entry,enforcement-point \
    --config "$RUN/core/soulbind.toml")
# The harness stands in for a second platform AND for an operator's tooling, so
# it holds what both would: code-entry to redeem, config-management to write
# rules and overrides, enforcement-point to ask what core decides. Granting it
# everything would have hidden the capability model rather than exercising it --
# the run before this one failed here, correctly, for missing enforcement-point.
HARNESS_CRED=$("$CORE_CLI" register --name harness --quiet \
    --capabilities code-entry,config-management,enforcement-point \
    --config "$RUN/core/soulbind.toml")

if [ -z "$PROXY_CRED" ] || [ -z "$HARNESS_CRED" ]; then
    log "a credential did not come back from register; refusing to continue"
    exit 1
fi

log "starting core"
"$CORE_CLI" serve --config "$RUN/core/soulbind.toml" > "$RUN/core.log" 2>&1 &
echo $! > "$RUN/core.pid"
wait_for_port core "$CORE_PORT" 60

# --- paper ------------------------------------------------------------------
log "starting paper"
echo "eula=true" > "$RUN/paper/eula.txt"
cat > "$RUN/paper/server.properties" <<PROPS
server-port=$PAPER_PORT
online-mode=false
level-type=flat
spawn-protection=0
max-players=20
motd=soulbind harness backend
PROPS

# The backend must accept forwarded connections, or the proxy reaches it and is
# told "did you forget to enable BungeeCord in spigot.yml?" -- which is exactly
# what happened the first time a player got PAST the gate. A stack where the
# gate works and the connection afterwards does not is a stack that proves half
# of what it claims.
mkdir -p "$RUN/paper/config"
cat > "$RUN/paper/spigot.yml" <<YAML
settings:
  bungeecord: true
YAML

cat > "$RUN/paper/config/paper-global.yml" <<YAML
_version: 29
proxies:
  bungee-cord:
    online-mode: false
  velocity:
    enabled: false
YAML

(cd "$RUN/paper" && "$JAVA" -Xms512M -Xmx1G -jar "$CACHE/paper-$PAPER_VERSION-$PAPER_BUILD.jar" nogui \
    > "$RUN/paper.log" 2>&1) &
echo $! > "$RUN/paper.pid"
wait_for_port paper "$PAPER_PORT" 240

# --- velocity ---------------------------------------------------------------
log "starting velocity"
cp "$REPO/connector-velocity/build/libs"/*.jar "$RUN/proxy/plugins/"

cat > "$RUN/proxy/velocity.toml" <<TOML
config-version = "2.7"
bind = "0.0.0.0:$PROXY_PORT"
motd = "soulbind harness proxy"
show-max-players = 20
online-mode = false
player-info-forwarding-mode = "legacy"
[servers]
lobby = "127.0.0.1:$PAPER_PORT"
try = ["lobby"]
[forced-hosts]
[advanced]
# The proxy's per-address login rate limit, disabled for the harness only.
#
# It exists to stop one address hammering a proxy with connections. This harness
# IS one address making several connections in a few seconds -- an artifact of
# running every player from one machine, which no real deployment does.
#
# Disabling it here weakens nothing under test: it is a proxy DoS control, not
# part of linking, the gate, or anything this stack asserts. A deployment's
# value is its own decision and is untouched.
login-ratelimit = 0
[query]
TOML

mkdir -p "$RUN/proxy/plugins/soulbind"
cat > "$RUN/proxy/plugins/soulbind/soulbind.toml" <<TOML
[core]
url = "http://127.0.0.1:$CORE_PORT"
credential = "$PROXY_CRED"

[gate]
join = "game.join"
kickmessage = "You need to link your account before joining. Use /link to start."
failmode = "closed"
decisiontimeoutmillis = 2000

[platform]
kind = "game"
TOML

(cd "$RUN/proxy" && "$JAVA" -Xms512M -Xmx1G -jar "$CACHE/velocity-$VELOCITY_VERSION-$VELOCITY_BUILD.jar" \
    > "$RUN/velocity.log" 2>&1) &
echo $! > "$RUN/velocity.pid"
wait_for_port velocity "$PROXY_PORT" 120

# The gate must be configured BEFORE the smoke runs, or the first assertion --
# that an unlinked player is refused -- would pass or fail on whether a rule
# happened to exist yet.
log "configuring the join gate"
"$HERE/set-rule.sh" "http://127.0.0.1:$CORE_PORT" "$HARNESS_CRED" game.join

# --- the smoke --------------------------------------------------------------
log "running the player-driver smoke"
(cd "$REPO/harness/player-driver" && node smoke.js \
    --host 127.0.0.1 --port "$PROXY_PORT" \
    --core "http://127.0.0.1:$CORE_PORT" \
    --entry-credential "$HARNESS_CRED" \
    --mc-version "$MC_PROTOCOL" \
    --kick-contains "link your account")

log "smoke passed"
