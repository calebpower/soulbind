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
# The toolchain, resolved from the pinned cache when there is one.
#
# On the guest these are the checksum-pinned tarballs fetch.sh unpacked; on the
# workstation, which has no pinned build published for it, they are the system
# ones and the version assertion below is what stands in for the pin. Set JAVA
# or NODE explicitly to override either.
# Whether the CALLER chose these is captured BEFORE defaulting them.
#
# The first version defaulted JAVA to "java" and then asked `[ -z "$JAVA" ]` to
# decide whether to use the pinned toolchain -- which is never empty after the
# default, so the pinned JDK would have been fetched, verified, extracted, and
# then never used. The guest would have run on a toolchain that does not exist
# there, and the failure would have read as "no JVM on the guest" all over again.
JAVA_CHOSEN=${JAVA:-}
NODE_CHOSEN=${NODE:-}
JAVA=${JAVA:-java}
NODE=${NODE:-node}
# Gradle's generated start scripts prefer JAVA_HOME over PATH, so exporting only
# PATH lets the CLI run on a different JVM from everything else here -- which is
# how this first failed, with core on 17 and the proxy on 25.
#
# An explicit JAVA now OVERRIDES an inherited JAVA_HOME. The previous version
# derived JAVA_HOME only when it was unset, so an environment already carrying
# `JAVA_HOME=/usr/local/openjdk17` -- which this workstation does -- kept it, the
# version check below passed because it tests $JAVA, and the core CLI then died
# with `class file version 69.0` because the start script used $JAVA_HOME. The
# check validated one JVM while Gradle used another. That is the same defect the
# comment above describes, surviving its own fix.
# Only derived from a JAVA that is an actual PATH. `dirname java` is ".", so a
# bare `java` would set JAVA_HOME to the parent of the working directory -- a
# real directory, so nothing would complain, and every Gradle start script would
# look for a JVM inside the repository.
case "$JAVA" in
    */*)
        if [ -n "$JAVA_CHOSEN" ] || [ -z "${JAVA_HOME:-}" ]; then
            # Checked, so a bad JAVA says what is wrong. Without this the script
            # died on `cd: /nonexistent/bin/..: No such file or directory` --
            # true, and no help at all to somebody who mistyped a path.
            if [ ! -x "$JAVA" ]; then
                echo "JAVA is set to '$JAVA', which is not an executable file." >&2
                echo "Set it to a Java 25 launcher, or unset it to use the pinned toolchain." >&2
                exit 1
            fi
            JAVA_HOME=$(cd "$(dirname "$JAVA")/.." && pwd)
            export JAVA_HOME
        fi
        ;;
esac

. "$HERE/pins.env"
log() { echo "[stack] $*"; }




# --down tears down whatever is running and stops. Without it the only way to
# stop a stack is to start another one, which is not a teardown.
if [ "${1:-}" = "--down" ]; then
    # Verified, not assumed. The first version ran `kill || true`, removed the
    # pidfile regardless, and logged "stopped pid N" whether or not anything had
    # been running -- so `down` was green with no stack at all, with a dead pid,
    # or with a JVM that ignored TERM. It also wrote a false statement into the
    # log a reader consults after a failure.
    #
    # The `|| true` that run.sh dropped had simply moved one file over.
    down_failed=0
    stopped=0
    for pidfile in "$RUN"/*.pid; do
        [ -f "$pidfile" ] || continue
        pid=$(cat "$pidfile")
        # A pidfile is untrusted input. `kill -0 -1` returns 0 on this system, so
        # a file containing `-1` reads as "alive" -- and the escalation added
        # here would then send SIGTERM and, twenty seconds later, SIGKILL to
        # every process this user owns. A one-shot `kill || true` merely failed;
        # a loop that insists is what made validation necessary.
        case "$pid" in
            "" | *[!0-9]* )
                log "pidfile $pidfile does not contain a plain pid ('$pid'); ignoring it"
                rm -f "$pidfile"
                continue ;;
        esac
        if [ "$pid" -le 1 ]; then
            log "pidfile $pidfile names pid $pid, which is not a process this harness started"
            rm -f "$pidfile"
            continue
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            log "pid $pid was not running"
            rm -f "$pidfile"
            continue
        fi
        kill "$pid" 2>/dev/null || true
        # A bounded wait, then SIGKILL. A JVM that ignores TERM is the ordinary
        # case, not an exotic one.
        i=0
        while [ "$i" -lt 20 ] && kill -0 "$pid" 2>/dev/null; do
            i=$((i + 1))
            sleep 1
        done
        if kill -0 "$pid" 2>/dev/null; then
            log "pid $pid ignored TERM after ${i}s; sending KILL"
            kill -9 "$pid" 2>/dev/null || true
            sleep 1
        fi
        if kill -0 "$pid" 2>/dev/null; then
            log "pid $pid is STILL running -- teardown did not succeed"
            down_failed=1
        else
            log "stopped pid $pid"
            stopped=$((stopped + 1))
            rm -f "$pidfile"
        fi
    done
    log "teardown stopped $stopped process(es)"
    if [ "$stopped" -eq 0 ]; then
        # Reported as a failure, not shrugged off. `down` is asked for when a
        # stack is expected to be running; finding nothing means either it was
        # never brought up or it died on its own, and both are things the run
        # should say out loud rather than record as a successful teardown.
        log "nothing was running to tear down"
        down_failed=1
    fi
    exit $down_failed
fi



# The artefacts are fetched BEFORE the toolchain is resolved or checked.
#
# fetch.sh downloads the pinned JDK and Node, and it used to run near the end of
# this script -- after the Java version check. On a machine with no system Java
# that is a deadlock: the check exits because there is no JVM, so fetch.sh never
# runs, so the JVM is never downloaded. Exactly the guest this pinning exists
# for, and it would have failed on the very first session run.
#
# fetch.sh itself needs no JVM: curl, tar and a checksum.
"$HERE/fetch.sh"

# Prefer the pinned toolchains when they are present. Checked AFTER pins.env, so
# the paths come from the pins rather than from a second copy of the versions.
if [ -x "$CACHE/jdk-$JDK_VERSION/bin/java" ] && [ -z "$JAVA_CHOSEN" ]; then
    JAVA="$CACHE/jdk-$JDK_VERSION/bin/java"
    JAVA_HOME="$CACHE/jdk-$JDK_VERSION"
    export JAVA_HOME
fi
if [ -x "$CACHE/node-$NODE_VERSION/bin/node" ] && [ -z "$NODE_CHOSEN" ]; then
    NODE="$CACHE/node-$NODE_VERSION/bin/node"
fi
# PATH follows whichever node was resolved, pinned OR caller-set.
#
# It used to be prepended only in the pinned branch, so setting NODE explicitly
# left PATH untouched -- and the smoke, which ran a bare `node`, then executed on
# whatever happened to be first on PATH rather than on the runtime the
# dependency check had just approved. That is the $JAVA versus $JAVA_HOME/bin/java
# defect this file documents three times over, reappearing on the Node axis.
case "$NODE" in
    */*)
        # Checked, as the JAVA branch is -- though NOT as early.
        #
        # JAVA is validated before pins.env and before fetch.sh; this runs after
        # the artefacts are fetched, because $NODE_VERSION comes from pins.env.
        # So a mistyped NODE pays for a JDK and Node download before being told,
        # where a mistyped JAVA is rejected immediately. Stated because the first
        # version of this comment claimed parity that does not exist.
        #
        # The check itself matters either way: without it a mistyped NODE died on
        # `cd: /nonexistent: No such file or directory`, the same unhelpful
        # failure the JAVA branch was fixed for, reproduced on the Node axis
        # because the fix was copied without the check that made it useful.
        if [ ! -x "$NODE" ]; then
            echo "NODE is set to '$NODE', which is not an executable file." >&2
            echo "Set it to a Node launcher, or unset it to use the pinned toolchain." >&2
            exit 1
        fi
        PATH="$(cd "$(dirname "$NODE")" && pwd):$PATH"
        export PATH
        ;;
esac

CORE_PORT=${CORE_PORT:-7100}
PAPER_PORT=${PAPER_PORT:-25566}
PROXY_PORT=${PROXY_PORT:-25577}

# --keep leaves the stack RUNNING on success.
#
# Without it this script is a one-shot smoke: it brings everything up, proves a
# flow, and the EXIT trap tears it all down again -- on success as much as on
# failure. run.sh was built on top assuming `up` left a stack behind, so `up`
# reported PASS on a stack that was already dead, the @pristine snapshot was
# taken on that dead stack, `journeys` could not reach core, and `down` reported
# success having killed nothing but dead pidfiles.
#
# The trap stays armed for every failure path and for INT/TERM. It is disarmed
# only at the very end, only on success, only when asked.
KEEP=0
if [ "${1:-}" = "--keep" ]; then
    KEEP=1
    shift
fi

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

# The axis name, checked BEFORE anything is downloaded or started -- same
# reasoning as the runtime check below. Discovering a typo'd backend after a
# fetch and a Gradle build wastes minutes and buries the cause.
case "${CORE_BACKEND:-sqlite}" in
    sqlite|mariadb) ;;
    *) log "unknown CORE_BACKEND '${CORE_BACKEND}' -- expected sqlite or mariadb"; exit 1 ;;
esac

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

# The JVM the START SCRIPTS will use, checked separately.
#
# $JAVA and $JAVA_HOME/bin/java are different variables and can name different
# JVMs. Checking only the first is how a run gets a clean "java 25" line in its
# log and then fails on class file version 69.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    HOME_VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')
    if [ -z "$HOME_VERSION" ] || [ "$HOME_VERSION" -lt 25 ]; then
        echo "JAVA_HOME=$JAVA_HOME points at Java ${HOME_VERSION:-unknown}, but Gradle's" >&2
        echo "start scripts use it -- so core would run on that, not on $JAVA." >&2
        exit 1
    fi
    log "java $HOME_VERSION at \$JAVA_HOME/bin/java (what the start scripts use)"
fi

rm -rf "$RUN"
mkdir -p "$RUN/paper" "$RUN/proxy/plugins" "$RUN/core"

# --- core -------------------------------------------------------------------
log "building"
# GRADLE_USER_HOME is set, and prefers reaper's declared cache.
#
# This invocation had none -- the flarum tier and the run stage both set one,
# and the build stage has since been given one too, so the claim that this was
# "the only" such invocation was itself false when written, inside the comment
# retiring a different false premise -- so on the guest it wrote to
# /root/.gradle: 835 MB on a root filesystem sitting at 72% with 2.2 GB free,
# rebuilt from scratch every session because `reset` does not reach it and
# nothing else does either. reaper's tenants.md is explicit that anything large
# belongs under $REAPER_CACHE_*, which lives on the session pool with tens of
# gigabytes, and .reaper.toml already declares `cache = ["gradle"]` -- a cache
# that nothing had ever used.
#
# Falls back to the artefact CACHE off a session, not to $RUN.
#
# $RUN is wiped by the `rm -rf "$RUN"` below on every single run, so a fallback
# there meant a workstation re-downloaded the whole Gradle distribution and every
# dependency each time -- turning the fast local loop into a slow one, which is
# precisely the loop this harness depends on for quick feedback. .cache/ is
# deliberately outside reaper state for the same reason: it holds things that are
# expensive to fetch and cheap to keep.
#
# Still not the developer's own ~/.gradle: a harness that silently adopts it
# would let a local build state leak into a result nobody can reproduce.
GRADLE_USER_HOME=${REAPER_CACHE_GRADLE:-$CACHE/gradle-home}
export GRADLE_USER_HOME
log "gradle user home: $GRADLE_USER_HOME"

# --no-daemon, like every other gradlew invocation here.
#
# Without it each run left a daemon alive for three hours holding about a
# gigabyte; two were resident at once on an 11 GB guest, and the older one was
# the sole remaining writer to the root disk this stage had just been moved off.
(cd "$REPO" && ./gradlew --no-daemon --quiet :core:installDist :connector-velocity:jar \
    :connector-discord:installDist)

# The storage axis. The gate asks for the battery green on BOTH backends in one
# session, and a stack that only ever ran core on SQLite would be answering half
# of it. Nothing else in this script changes: that the rest of the stack cannot
# tell which backend core uses is the storage seam's claim, and running the same
# flows against either is what tests it.
case "${CORE_BACKEND:-sqlite}" in
    sqlite)
        CORE_STORAGE="backend = \"sqlite\"
url = \"jdbc:sqlite:$RUN/core/soulbind.db\""
        ;;
    mariadb)
        # Supplied by the caller, because this script does not run a database
        # server -- the session harness does, and inventing a second one here
        # would be a second thing to wait for and a second thing to blame.
        : "${SOULBIND_MARIADB_URL:?set SOULBIND_MARIADB_URL for CORE_BACKEND=mariadb}"
        CORE_STORAGE="backend = \"mariadb\"
url = \"$SOULBIND_MARIADB_URL\"
user = \"${SOULBIND_MARIADB_USER:-soulbind}\"
password = \"${SOULBIND_MARIADB_PASSWORD:-}\""
        ;;
    *)
        # Unreachable: the pre-flight check above rejects anything else. Kept so
        # adding a backend there and forgetting it here fails loudly instead of
        # writing a config with an empty [storage] section.
        log "CORE_BACKEND '${CORE_BACKEND}' passed pre-flight but has no storage config here"
        exit 1 ;;
esac

cat > "$RUN/core/soulbind.toml" <<TOML
[server]
host = "127.0.0.1"
port = $CORE_PORT

[storage]
$CORE_STORAGE

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

# The chat connector gets its OWN credential and its own capabilities, because
# it is a separate principal. Sharing the harness's would prove the flow works
# for something holding every capability, which is not what a deployment runs.
CHAT_CRED=$("$CORE_CLI" register --name chat --quiet \
    --capabilities code-display,code-entry \
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
# The driver's dependencies, from a COMMITTED lock.
#
# stack.sh used to run `node smoke.js` with nothing installing mineflayer -- it
# worked only because node_modules/ happened to be present on this workstation.
# `[sync].exclude` drops node_modules/, so on the guest the smoke would have
# failed with "Cannot find module 'mineflayer'", and the tier this whole harness
# exists for would never have run there.
#
# npm ci, which honours a lock and refuses without one. Same discipline as the
# image digests and the jar checksums: a harness whose dependencies resolve
# fresh on every run is a harness whose green is about whatever npm felt like.
#
# Generate-and-emit when the lock is absent, which is the forum tier's pattern
# and exists for the same reason: npm is broken on this workstation
# (MODULE_NOT_FOUND inside npm's own tree, for any package), so the lock has to
# be produced where it will be used and copied out to be committed.
DRIVER="$REPO/harness/player-driver"
DRIVER_LOCK="$DRIVER/package-lock.json"

# VERIFY before installing, rather than installing unconditionally.
#
# `npm ci` is right on the guest, where node_modules/ is sync-excluded and the
# tree starts empty. It is not runnable on this workstation at all: npm here
# fails with MODULE_NOT_FOUND inside its own dependency tree, for any package --
# the same breakage the forum tier documents. An unconditional `npm ci` made the
# workstation run fail at a step whose work was already done.
#
# So driver-lock-check.js compares every package the lock names against what is
# installed, and an install happens only if something is missing or at the wrong
# version. That is stronger than assuming a present node_modules is correct --
# which is the unpinned resolution this block exists to prevent -- and it needs
# no npm when the tree is already right.
#
# ONE implementation, used both to decide and to explain. A second copy for the
# failure message would be a second definition of "matches", and the two would
# disagree the first time either changed.

if [ -f "$DRIVER_LOCK" ] && "$NODE" "$HERE/driver-lock-check.js" "$DRIVER" >/dev/null 2>&1; then
    log "player driver already matches its committed lock; nothing to install"
elif [ -f "$DRIVER_LOCK" ]; then
    log "player driver does not match its committed lock; installing"
    if ! (cd "$DRIVER" && npm ci --no-audit --no-fund >/dev/null 2>&1); then
        echo "" >&2
        echo "npm ci failed, and the installed player-driver tree does not match" >&2
        echo "harness/player-driver/package-lock.json. What differs:" >&2
        "$NODE" "$HERE/driver-lock-check.js" "$DRIVER" >&2 || true
        echo "" >&2
        echo "On a machine with a working npm: cd harness/player-driver && npm ci" >&2
        echo "This workstation's npm is broken -- MODULE_NOT_FOUND inside its own" >&2
        echo "dependency tree, for any package -- so the tier runs in a reaper" >&2
        echo "session, where npm works and node_modules starts empty." >&2
        exit 1
    fi
else
    log "NO committed player-driver lock; resolving fresh and emitting one to out/"
    (cd "$DRIVER" && npm install --no-audit --no-fund >/dev/null)
    mkdir -p "$REPO/out"
    cp "$DRIVER_LOCK" "$REPO/out/player-driver-package-lock.json"
    log "lock written to out/player-driver-package-lock.json -- review and commit it"
fi

log "running the player-driver smoke"
(cd "$REPO/harness/player-driver" && "$NODE" smoke.js \
    --host 127.0.0.1 --port "$PROXY_PORT" \
    --core "http://127.0.0.1:$CORE_PORT" \
    --entry-credential "$HARNESS_CRED" \
    --mc-version "$MC_PROTOCOL" \
    --kick-contains "link your account")

# --- the chat side, through the REAL connector ---------------------------
# A second link, game to chat, redeemed by the actual ChatConnector over the
# scripted surface rather than by a hand-written HTTP request. That is the
# difference between proving core works and proving this connector does.
log "linking game to chat through the real chat connector"

# Persisted so later stages drive the SAME principals this flow registered.
# A stage that minted its own would be testing a connector this deployment never
# had, and would quietly stop exercising the capability model -- each of these
# holds a different, deliberately incomplete set.
#
# Under $RUN, which is gitignored and lives in reaper state: these are
# credentials, and the only reason they are on disk at all is that the stages
# after this one are separate processes.
cat > "$RUN/core/creds.env" <<CREDS
PROXY_CRED='$PROXY_CRED'
HARNESS_CRED='$HARNESS_CRED'
CHAT_CRED='$CHAT_CRED'
CREDS
log "credentials written to $RUN/core/creds.env for later stages"

CHAT_DRIVER="$REPO/connector-discord/build/install/connector-discord/bin/scripted-driver"
CORE_URL="http://127.0.0.1:$CORE_PORT"

# A code, issued by the chat connector for a chat account.
CHAT_REPLY=$(SOULBIND_DRIVER_KIND=chat "$CHAT_DRIVER" \
    "$CORE_URL" "$CHAT_CRED" "chat-account-1" link 2>/dev/null | head -1) || {
    log "the chat connector could not issue a code"
    exit 1
}
CHAT_CODE=$(printf '%s' "$CHAT_REPLY" | sed -n 's/.*code is \([23456789BCDFGHJKMNPQRSTVWXYZ]*\).*/\1/p')

if [ -z "$CHAT_CODE" ]; then
    log "no code in the chat connector's reply: $CHAT_REPLY"
    exit 1
fi
log "chat connector issued $CHAT_CODE"

# Redeemed by the harness standing in for a third platform, so the chat account
# ends up in a subject with something else -- which is what makes the next
# assertion about /whoami meaningful.
"$HERE/redeem.sh" "$CORE_URL" "$HARNESS_CRED" "$CHAT_CODE" third "third-account-1"

# And the connector can now describe the link it just made.
WHOAMI=$(SOULBIND_DRIVER_KIND=chat "$CHAT_DRIVER" \
    "$CORE_URL" "$CHAT_CRED" "chat-account-1" whoami 2>/dev/null | head -1)

case "$WHOAMI" in
    *"linked to"*)
        log "chat connector reports the link: $WHOAMI"
        ;;
    *)
        log "the chat connector does not see the link it just made: $WHOAMI"
        exit 1
        ;;
esac

log "smoke passed"

if [ "$KEEP" -eq 1 ]; then
    # Disarmed here and nowhere else: everything above this line still tears
    # down on the way out.
    trap - EXIT
    log "stack left RUNNING (--keep): core on $CORE_PORT, paper on $PAPER_PORT, proxy on $PROXY_PORT"
fi
