#!/bin/sh
# The Phase 10 gate: a clean install, following only docs/install.md.
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
#     harness/install-gate.sh <evidence-dir>
#
# §14 Phase 10: "clean install from packages on a fresh Ubuntu 26.04 VM
# following only the docs, ending with a real cross-platform link -- evidence
# directory captured."
#
# Every step below is a step docs/install.md gives the operator, in the
# document's order, with the document's commands. Where the document offers a
# choice, the one taken is named in the evidence. Where this script must do
# something the document does not say, that is a defect in the document, and
# the comment on the step says so explicitly -- the point of the gate is that
# the document is sufficient, not that this script is clever.
#
# LINUX-ONLY, deliberately: it follows an Ubuntu install document, and useradd,
# systemd and /etc are the subject matter, not incidentals. It has never run on
# the workstation (FreeBSD; no systemd, no useradd) -- the pieces were
# rehearsed there individually against the real tarball (DECISIONS 10.8), and
# the first full execution is the session run. Stated rather than discovered.
set -eu

EVIDENCE=${1:?usage: install-gate.sh <evidence-dir>}
REPO=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PORT=7180

mkdir -p "$EVIDENCE"
STEP=0

step() {
    STEP=$((STEP + 1))
    echo "[gate] step $STEP: $*"
    echo "== step $STEP: $*" >> "$EVIDENCE/transcript.txt"
}

run() {
    # Every command and its output lands in the transcript, so the evidence
    # directory answers "what exactly happened" without a re-run.
    echo "\$ $*" >> "$EVIDENCE/transcript.txt"
    "$@" >> "$EVIDENCE/transcript.txt" 2>&1
}

fail() {
    echo "[gate] FAIL: $*"
    echo "FAIL: $*" >> "$EVIDENCE/transcript.txt"
    exit 1
}

# The gate is judged on a machine where these exist. A machine without them is
# not the machine the document installs onto, and skipping would let the gate
# pass without gating.
command -v systemctl > /dev/null 2>&1 || fail "no systemctl; this is not the Ubuntu host the gate runs on"
command -v useradd  > /dev/null 2>&1 || fail "no useradd; this is not the Ubuntu host the gate runs on"
[ "$(id -u)" -eq 0 ] || fail "the gate runs as root on the ephemeral guest; it writes /opt, /etc and systemd units"

# --- install.md §1: Java -----------------------------------------------------
step "Java 25 (doc §1)"
if command -v java > /dev/null 2>&1 && java -version 2>&1 | grep -qE 'version "(2[5-9]|[3-9][0-9])'; then
    echo "java already present" >> "$EVIDENCE/transcript.txt"
elif run apt-get update && run apt-get install -y openjdk-25-jre-headless; then
    echo "doc path taken: apt openjdk-25-jre-headless" >> "$EVIDENCE/transcript.txt"
else
    # The document's stated alternative: "install Temurin 25 from Adoptium
    # instead". The pinned Temurin the fullstack tier already verifies by
    # checksum IS that alternative, fetched the same way.
    echo "apt path failed; doc's Temurin alternative via fetch.sh" >> "$EVIDENCE/transcript.txt"
    run env SOULBIND_STACK_CACHE="$REPO/harness/fullstack/.cache" \
        sh "$REPO/harness/fullstack/fetch.sh"
    JDK_HOME=$(ls -d "$REPO"/harness/fullstack/.cache/jdk-*/ | head -1)
    ln -sf "$JDK_HOME/bin/java" /usr/local/bin/java
fi
run java -version

# --- install.md §2: a user and some directories ------------------------------
step "user and directories (doc §2)"
id soulbind > /dev/null 2>&1 \
    || run useradd --system --no-create-home --shell /usr/sbin/nologin soulbind
run mkdir -p /opt/soulbind /etc/soulbind /var/lib/soulbind
run chown soulbind:soulbind /var/lib/soulbind
# root:soulbind, exactly as the document says -- and the document says it
# because writing this gate caught it not saying it: 750 with root:root locks
# the service user out of its own configuration, and the failure would have
# arrived two steps later as doctor unable to read a file that looks fine.
run chown root:soulbind /etc/soulbind
run chmod 750 /etc/soulbind

# --- install.md §3: unpack core ----------------------------------------------
step "unpack the distribution (doc §3)"
TARBALL=$(ls "$REPO"/core/build/distributions/core-*.tar.gz 2>/dev/null | head -1)
[ -n "$TARBALL" ] || fail "no core-*.tar.gz under core/build/distributions -- the build verb did not produce the artifact the document installs"
run tar -xzf "$TARBALL" -C /opt/soulbind
[ -d /opt/soulbind/core ] || run sh -c 'mv /opt/soulbind/core-* /opt/soulbind/core'
for f in LICENSE NOTICE THIRD-PARTY.txt; do
    [ -f "/opt/soulbind/core/$f" ] || fail "the unpacked distribution is missing $f"
done

# --- install.md §4: configure ------------------------------------------------
step "configuration from the shipped samples (doc §4)"
run cp /opt/soulbind/core/packaging/soulbind.toml.sample /etc/soulbind/soulbind.toml
run cp /opt/soulbind/core/packaging/core.env.sample /etc/soulbind/core.env
run chown root:soulbind /etc/soulbind/soulbind.toml /etc/soulbind/core.env
run chmod 640 /etc/soulbind/soulbind.toml
run chmod 600 /etc/soulbind/core.env
# The document says "sudo editor". This stands in for the operator's edit, and
# the sample needs NO edits for the SQLite default -- which is itself part of
# what the gate checks. Nothing is changed.

# --- install.md §5: check before you start -----------------------------------
step "doctor before first start (doc §5)"
if run runuser -u soulbind -- /opt/soulbind/core/bin/core doctor --config /etc/soulbind/soulbind.toml; then
    :
else
    fail "doctor refused the configuration the document just built -- its findings are in the transcript"
fi

# --- install.md §6: install the service --------------------------------------
step "systemd unit (doc §6)"
run cp /opt/soulbind/core/packaging/soulbind-core.service /etc/systemd/system/
run systemctl daemon-reload
run systemctl enable --now soulbind-core
i=0
while [ "$i" -lt 30 ]; do
    if systemctl is-active --quiet soulbind-core; then break; fi
    i=$((i + 1)); sleep 1
done
systemctl is-active --quiet soulbind-core || {
    run systemctl status soulbind-core --no-pager || true
    run journalctl -u soulbind-core -n 80 --no-pager || true
    fail "soulbind-core did not come up under the shipped hardened unit -- the journal is in the transcript"
}
run systemctl status soulbind-core --no-pager

i=0
while [ "$i" -lt 30 ]; do
    if python3 -c "
import socket, sys
s = socket.socket(); s.settimeout(1)
sys.exit(0 if s.connect_ex(('127.0.0.1', $PORT)) == 0 else 1)" 2>/dev/null; then break; fi
    i=$((i + 1)); sleep 1
done
[ "$i" -lt 30 ] || { run journalctl -u soulbind-core -n 80 --no-pager || true; fail "the unit is active but nothing listens on $PORT"; }

# --- install.md §7: register connectors --------------------------------------
step "register the principals for the link (doc §7)"
register() {
    runuser -u soulbind -- /opt/soulbind/core/bin/core register \
        --name "$1" --quiet --capabilities "$2" \
        --config /etc/soulbind/soulbind.toml
}
GAME_CRED=$(register game-side code-display)
FORUM_CRED=$(register forum-side code-entry)
ADMIN_CRED=$(register gate-admin config-management)
echo "three principals registered; credentials held in memory only" >> "$EVIDENCE/transcript.txt"

# --- install.md Verify: the real cross-platform link -------------------------
step "a real cross-platform link, verified by asking core (doc: Verify)"
RPC="$REPO/tools/rpc.sh"
CODE=$(sh "$RPC" "http://127.0.0.1:$PORT" "$GAME_CRED" code.issue \
    '{"platformKind":"game","platformId":"gate-player","display":"gate-player"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["code"])')
[ -n "$CODE" ] || fail "code.issue returned no code"
echo "issued a code on the game side (the code itself is not evidence and is not recorded)" >> "$EVIDENCE/transcript.txt"

sh "$RPC" "http://127.0.0.1:$PORT" "$FORUM_CRED" code.redeem \
    "{\"code\":\"$CODE\",\"platformKind\":\"forum\",\"platformId\":\"gate-account\",\"display\":\"gate-account\"}" \
    > "$EVIDENCE/redeem.json" || fail "the redeem was refused: $(cat "$EVIDENCE/redeem.json" 2>/dev/null)"

# Read it back from core, exactly as the document tells the operator to:
# a connector saying "linked!" is a connector's opinion.
sh "$RPC" "http://127.0.0.1:$PORT" "$ADMIN_CRED" subject.inspect \
    '{"platformKind":"game","platformId":"gate-player"}' > "$EVIDENCE/subject.json" \
    || fail "subject.inspect was refused"
python3 - "$EVIDENCE/subject.json" <<'PY' || fail "core does not agree the link exists"
import json, sys
subject = json.load(open(sys.argv[1]))
kinds = {i.get("platformKind") for i in subject.get("identities", [])}
if not {"game", "forum"} <= kinds:
    raise SystemExit("subject holds %s, not both sides of the link" % sorted(kinds))
print("core confirms one subject holding both game and forum identities")
PY

# --- install.md: Backing up --------------------------------------------------
step "audit export, as the backup section instructs"
sh "$REPO/tools/audit-export.sh" "http://127.0.0.1:$PORT" "$ADMIN_CRED" \
    > "$EVIDENCE/audit.jsonl" 2> "$EVIDENCE/audit-export.summary" \
    || fail "the audit export failed: $(cat "$EVIDENCE/audit-export.summary")"
grep -q '"action":"connector.registered"' "$EVIDENCE/audit.jsonl" \
    || fail "the exported log does not record the registrations this gate just performed"
grep -q 'identity.linked\|code.redeemed' "$EVIDENCE/audit.jsonl" \
    || fail "the exported log does not record the link this gate just made"

# --- restart survival: the upgrade section's premise -------------------------
step "the service survives a restart with its state intact"
run systemctl restart soulbind-core
sleep 5
sh "$RPC" "http://127.0.0.1:$PORT" "$ADMIN_CRED" subject.inspect \
    '{"platformKind":"game","platformId":"gate-player"}' > "$EVIDENCE/subject-after-restart.json" \
    || fail "after a restart, the admin credential no longer works or core is not answering"
python3 -c "
import json
s = json.load(open('$EVIDENCE/subject-after-restart.json'))
kinds = {i.get('platformKind') for i in s.get('identities', [])}
assert {'game','forum'} <= kinds, 'the link did not survive the restart: %s' % kinds
" || fail "the link did not survive the restart"

echo "[gate] PASS -- clean install, hardened unit, real cross-platform link, export, restart"
echo "PASS" >> "$EVIDENCE/transcript.txt"
