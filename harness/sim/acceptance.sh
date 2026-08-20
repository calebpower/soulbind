#!/bin/sh
# The battery's acceptance test: revert a real fix, and see if the tier notices.
#
#     harness/sim/acceptance.sh
#
# §14's Phase 9 gate asks for "a deliberately reverted Phase-2-or-later fix
# rediscovered by a hunting run", and the methodology (§15) calls this the
# battery's own acceptance test. It is the only check that establishes the tier
# has POWER rather than coverage: a suite that has never caught anything is
# indistinguishable from a suite that cannot.
#
# The fix reverted here is real and recent. `LinkingService.redeem` reads
#
#     Subject subject = issuedSide.or(() -> redeemedSide)
#             .orElseGet(() -> identities.createSubject(clock.instant()));
#
# and mutation coverage found that the `.or(() -> redeemedSide)` could return
# empty with every test in the repository still green. The consequence is not
# cosmetic: a fresh subject is created, the new identity bound to it, and the
# existing identity left on its original subject -- so the redeem reports
# SUCCESS and the two accounts are not linked. docs/DECISIONS.md 9.5.
#
# It needs core and a database and nothing else. The simulated-user tier talks
# to core over HTTP; it has no opinion about Paper, the proxy or the forum, so
# this runs on a workstation with SQLite as readily as in a session.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
WORK=${TMPDIR:-/tmp}/soulbind-sim-acceptance.$$
PORT=${SIM_ACCEPTANCE_PORT:-7150}
TARGET="$REPO/core/src/main/java/dev/soulbind/core/identity/LinkingService.java"
BACKUP="$WORK/LinkingService.java.orig"

log() { echo "[acceptance] $*"; }

core_pid=""
restored=""

restore() {
    [ -n "$restored" ] && return 0
    restored=1
    [ -n "$core_pid" ] && kill "$core_pid" 2>/dev/null
    if [ -f "$BACKUP" ]; then
        cp "$BACKUP" "$TARGET"
        log "source restored"
        # Rebuilt on the way out, so a failed run never leaves a reverted fix
        # compiled into anybody's install directory.
        (cd "$REPO" && ./gradlew --no-daemon --quiet :core:installDist) || true
    fi
    rm -rf "$WORK"
}
trap restore EXIT INT TERM

mkdir -p "$WORK/core"

: "${JAVA:=java}"
JAVA_HOME=$(dirname "$(dirname "$JAVA")")
export JAVA_HOME

# --- 1. the fix is present, and the run is clean with it -------------------
#
# The control, and it is the half that makes the rest mean anything. A tier that
# reports a defect after the revert proves nothing unless it reported none
# before it -- otherwise the "rediscovery" is just the tier's normal noise.
log "building core with the fix in place"
(cd "$REPO" && ./gradlew --no-daemon --quiet :core:installDist :sim:installDist)
cp "$TARGET" "$BACKUP"

start_core() {
    rm -rf "$WORK/core/state"
    mkdir -p "$WORK/core/state"
    cat > "$WORK/core/soulbind.toml" <<TOML
[server]
host = "127.0.0.1"
port = $PORT

[storage]
backend = "sqlite"
url = "jdbc:sqlite:$WORK/core/state/soulbind.db"

[linking]
codettlseconds = 600
TOML
    "$REPO/core/build/install/core/bin/core" serve \
        --config "$WORK/core/soulbind.toml" > "$WORK/core.log" 2>&1 &
    core_pid=$!

    i=0
    while [ "$i" -lt 60 ]; do
        if python3 -c "
import socket, sys
s = socket.socket(); s.settimeout(1)
sys.exit(0 if s.connect_ex(('127.0.0.1', $PORT)) == 0 else 1)" 2>/dev/null; then
            return 0
        fi
        i=$((i + 1)); sleep 1
    done
    log "core never listened on $PORT"
    tail -20 "$WORK/core.log" | sed 's/^/  /'
    return 1
}

register_cast() {
    cli="$REPO/core/build/install/core/bin/core"
    : > "$WORK/core/sim-credentials"
    for who in alex sam rey; do
        cred=$("$cli" register --name "sim-$who" --quiet \
            --capabilities code-display,code-entry,enforcement-point,link-state-reader \
            --config "$WORK/core/soulbind.toml")
        printf '%s=%s\n' "$who" "$cred" >> "$WORK/core/sim-credentials"
    done
    admin=$("$cli" register --name sim-admin --quiet \
        --capabilities config-management,link-state-reader,code-entry \
        --config "$WORK/core/soulbind.toml")
    printf 'admin=%s\n' "$admin" >> "$WORK/core/sim-credentials"
    printf 'retired=%s\n' "sim-retired.deadbeef" >> "$WORK/core/sim-credentials"
}

run_sim() {
    SOULBIND_SIM_CORE_URL="http://127.0.0.1:$PORT" \
    SOULBIND_SIM_CREDENTIALS="$WORK/core/sim-credentials" \
    SOULBIND_SIM_TAG="$1" \
    JAVA_HOME="$JAVA_HOME" \
        "$REPO/harness/sim/build/install/soulbind-sim/bin/soulbind-sim" > "$2" 2>&1
}

log "control: the committed seeds against core WITH the fix"
start_core
register_cast
if run_sim "control" "$WORK/control.log"; then
    log "control clean"
else
    log "THE CONTROL FAILED. The tier reports a defect against unmodified core, so"
    log "whatever it says after the revert is not rediscovery -- it is noise."
    sed 's/^/  /' "$WORK/control.log"
    exit 1
fi
kill "$core_pid" 2>/dev/null; wait "$core_pid" 2>/dev/null || true; core_pid=""

# --- 2. revert the fix -----------------------------------------------------
log "reverting the asymmetric-link fix in LinkingService"
python3 - "$TARGET" <<'PY'
import sys
path = sys.argv[1]
source = open(path, encoding="utf-8").read()
needle = "Subject subject = issuedSide.or(() -> redeemedSide)"
if needle not in source:
    raise SystemExit(
        "the line this acceptance test reverts is not in LinkingService any more.\n"
        "That is not a failure -- it means the code moved. Point the revert at\n"
        "whatever replaced it, or choose another real fix, but do not delete the\n"
        "test: it is the only check that says this tier has power.")
open(path, "w", encoding="utf-8").write(
    source.replace(needle, "Subject subject = issuedSide.or(() -> java.util.Optional.<Subject>empty())", 1))
print("reverted")
PY
(cd "$REPO" && ./gradlew --no-daemon --quiet :core:installDist)

# --- 3. the hunt -----------------------------------------------------------
log "hunting: the same seeds against core WITHOUT the fix"
start_core
register_cast
if run_sim "hunt" "$WORK/hunt.log"; then
    log "THE TIER DID NOT REDISCOVER THE DEFECT."
    log "A redeem now reports success while leaving the accounts unlinked, and"
    log "the committed seeds ran clean over it. That is the battery failing its"
    log "own acceptance test -- §15 -- and it means a green run means less than"
    log "it appears to."
    sed 's/^/  /' "$WORK/hunt.log"
    exit 1
fi

if ! grep -q 'linkage-mirrors-model' "$WORK/hunt.log"; then
    log "the run failed, but not on linkage-mirrors-model:"
    sed 's/^/  /' "$WORK/hunt.log"
    log "a failure for the wrong reason is not rediscovery."
    exit 1
fi

log "rediscovered:"
grep -E 'seed |linkage-mirrors-model' "$WORK/hunt.log" | head -6 | sed 's/^/  /'
log "the battery passes its own acceptance test"
