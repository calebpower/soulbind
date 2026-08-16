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
CORE_C=soulbind-forum-core

log() { echo "[forum] $*"; }

cleanup() {
    status=$?
    log "tearing down"
    for c in "$WEB_C" "$CORE_C" "$DB_C"; do
        podman rm -f "$c" >/dev/null 2>&1 || true
    done
    podman network rm -f "$NET" >/dev/null 2>&1 || true
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
    podman logs "$CORE_C" 2>&1 | tail -30 || true
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
#
# Built AND run inside the toolchain container. The guest template ships no
# language toolchains -- .reaper.toml says so where it explains why the build
# verb is `container` -- and the first version of this harness ran gradle on the
# host and got "JAVA_HOME is not set and no 'java' command could be found".
#
# Running core in a container has a second benefit the host version could not
# have had: stopping it for the outage pass is `podman stop`, which the
# orchestrator can do reliably, rather than signalling a pid and hoping.
log "building core in the toolchain container"

podman run --rm -v "$REPO":/work -w /work \
    -e GRADLE_USER_HOME=/work/.gradle-forum-home \
    "$FLARUM_TOOLCHAIN_IMAGE" \
    ./gradlew --no-daemon --quiet --project-cache-dir /tmp/gradle-forum-cache \
    :core:installDist

mkdir -p "$RUN/core"
cat > "$RUN/core/soulbind.toml" <<TOML
[server]
host = "0.0.0.0"
port = $CORE_PORT

[storage]
backend = "sqlite"
url = "jdbc:sqlite:/state/soulbind.db"

[linking]
codettlseconds = 600
TOML

# Every core CLI call goes through the toolchain image, on the shared network,
# with the same state volume the server uses. One helper, so no call site can
# quietly use different paths from another.
core_cli() {
    podman run --rm --network "$NET" \
        -v "$REPO":/work:ro -v "$RUN/core":/state \
        "$FLARUM_TOOLCHAIN_IMAGE" \
        /work/core/build/install/core/bin/core "$@" --config /state/soulbind.toml
}

log "registering the forum connector"
# Exactly the capabilities the connector needs, and no more. Granting everything
# would prove the flow works for something holding every capability, which is
# not what a deployment runs -- and it is how a missing grant stays hidden until
# somebody else's install.
FORUM_CRED=$(core_cli register --name forum --quiet \
    --capabilities code-display,code-entry,enforcement-point)

# The harness stands in for the game side AND for an operator's tooling.
HARNESS_CRED=$(core_cli register --name harness --quiet \
    --capabilities code-display,code-entry,config-management,enforcement-point)

if [ -z "$FORUM_CRED" ] || [ -z "$HARNESS_CRED" ]; then
    log "a credential did not come back from register; refusing to continue"
    exit 1
fi
printf '%s' "$FORUM_CRED" > "$RUN/forum.credential"
printf '%s' "$HARNESS_CRED" > "$RUN/harness.credential"

start_core() {
    podman rm -f "$CORE_C" >/dev/null 2>&1 || true
    podman run -d --name "$CORE_C" --network "$NET" \
        -p "${CORE_PORT}:${CORE_PORT}" \
        -v "$REPO":/work:ro -v "$RUN/core":/state \
        "$FLARUM_TOOLCHAIN_IMAGE" \
        /work/core/build/install/core/bin/core serve --config /state/soulbind.toml \
        >/dev/null
    wait_for "core" 90 curl -sf -o /dev/null "http://127.0.0.1:${CORE_PORT}/"
}

stop_core() {
    podman stop -t 10 "$CORE_C" >/dev/null 2>&1 || true
    # Wait for the port to actually close. Stopping and immediately testing
    # races the shutdown, and a test that reached a dying core would prove
    # nothing about the outage path while looking like it had.
    i=0
    while [ "$i" -lt 30 ] && curl -sf -o /dev/null "http://127.0.0.1:${CORE_PORT}/" 2>/dev/null; do
        i=$((i + 1)); sleep 1
    done
    if curl -sf -o /dev/null "http://127.0.0.1:${CORE_PORT}/" 2>/dev/null; then
        log "core did not stop; the outage pass would test the wrong world"
        exit 1
    fi
    log "core is down"
}

log "starting core"
start_core
log "core is up on $CORE_PORT"

# --- the forum --------------------------------------------------------------
#
# Installed into a volume the web container keeps, not into the repository:
# a Flarum site is a hundred megabytes of somebody else's code plus a
# database-backed config, and none of it belongs in this tree.
log "installing flarum $FLARUM_VERSION"

mkdir -p "$RUN/forum/site"

# The site's own composer.lock is emitted to out/ on first run, the same way the
# extension's was, so the forum a test ran against can be reproduced exactly
# rather than "whatever resolved that day".
SITE_LOCK="$REPO/harness/flarum/site-composer.lock"
if [ -f "$SITE_LOCK" ]; then
    cp "$SITE_LOCK" "$RUN/forum/site/composer.lock"
    log "installing the forum from its committed lock"
else
    log "NO committed site lock; this run resolves fresh and emits one to out/"
fi

cat > "$RUN/forum/site/composer.json" <<JSON
{
    "name": "soulbind/forum-harness",
    "description": "Not a package. The forum a browser tier drives.",
    "type": "project",
    "require": {
        "flarum/core": "$FLARUM_VERSION",
        "soulbind/flarum-connector": "*"
    },
    "repositories": [
        { "type": "path", "url": "/extension", "options": { "symlink": false } }
    ],
    "minimum-stability": "stable",
    "prefer-stable": true,
    "config": {
        "allow-plugins": {
            "flarum/extension-manager-composer-plugin": false,
            "composer/package-versions-deprecated": false
        }
    }
}
JSON

podman run --rm --network "$NET" \
    -v "$RUN/forum/site":/site \
    -v "$REPO/connector-flarum":/extension:ro \
    -w /site -e COMPOSER_HOME=/tmp/composer \
    "$COMPOSER_IMAGE" composer install --no-interaction --no-progress --no-plugins

if [ ! -f "$SITE_LOCK" ]; then
    mkdir -p "$REPO/out"
    cp "$RUN/forum/site/composer.lock" "$REPO/out/site-composer.lock"
    log "site lock written to out/site-composer.lock -- review and commit it"
fi

# Flarum's own installer, driven from a file rather than interactively.
cat > "$RUN/forum/site/install.yml" <<YML
debug: false
offline: false
baseUrl: $FORUM_URL
databaseConfiguration:
  driver: mysql
  host: $DB_C
  port: 3306
  database: $DB_NAME
  username: $DB_USER
  password: $DB_PASS
  prefix: ''
adminUser:
  username: admin
  password: harness-admin-password
  passwordConfirmation: harness-admin-password
  email: admin@example.com
settings:
  forum_title: soulbind harness
YML

podman run --rm --network "$NET" \
    -v "$RUN/forum/site":/site -w /site \
    "$FLARUM_PHP_IMAGE" sh -c '
      set -e
      docker-php-ext-install pdo_mysql > /dev/null 2>&1 || true
      php flarum install --file=install.yml
    '

log "starting the forum"
# php -S, not a real web server. This is a harness: a browser tier needs a URL
# that serves Flarum, not a production topology. The router script below is what
# an nginx try_files rule would do, and is the whole difference.
cat > "$RUN/forum/site/router.php" <<'PHPR'
<?php
// Serve a real file if one exists, otherwise hand everything to Flarum's front
// controller -- exactly what `try_files $uri /index.php?$query_string` does.
$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
$file = __DIR__ . '/public' . $path;
if ($path !== '/' && is_file($file)) {
    return false;
}
require __DIR__ . '/public/index.php';
PHPR

podman run -d --name "$WEB_C" --network "$NET" \
    -p "${FORUM_PORT}:${FORUM_PORT}" \
    -v "$RUN/forum/site":/site -w /site \
    "$FLARUM_PHP_IMAGE" sh -c "
      docker-php-ext-install pdo_mysql > /dev/null 2>&1 || true
      php -S 0.0.0.0:${FORUM_PORT} router.php
    " >/dev/null

wait_for "the forum" 90 curl -sf -o /dev/null "$FORUM_URL/"

log "forum is up on $FORUM_PORT"
log "core credential for the forum is in $RUN/forum.credential"

# --- enable and configure the extension -------------------------------------
#
# Through the database, which is what the admin UI writes. A CLI command would
# be tidier if one existed for every setting; it does not, and half the values
# below are this extension's own. Writing what the UI writes keeps one code path
# rather than a second that can drift from it.
log "enabling and configuring the extension"

sql() {
    podman exec -i "$DB_C" mariadb -h 127.0.0.1 -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" -e "$1"
}

EXT_ID=soulbind-flarum-connector

sql "REPLACE INTO settings (\`key\`, \`value\`) VALUES
      ('extensions_enabled', '[\"${EXT_ID}\"]'),
      ('soulbind.core_url',       'http://${CORE_C}:${CORE_PORT}/'),
      ('soulbind.credential',     '$(cat "$RUN/forum.credential")'),
      ('soulbind.webhook_secret', '$(cat "$RUN/forum.credential")'),
      ('soulbind.fail_mode',      'closed'),
      ('soulbind.register_gate',  'forum-register'),
      ('soulbind.post_gate',      'forum-post'),
      ('soulbind.timeout_ms',     '2000');"

podman exec "$WEB_C" php flarum cache:clear >/dev/null 2>&1 || true

# --- the smoke --------------------------------------------------------------
#
# Proves the pieces are actually wired before any browser opens. A Playwright
# failure against a stack that never came up correctly is a report about the
# harness wearing the costume of a report about soulbind.
log "smoke: the forum serves and the extension is enabled"

curl -sf "$FORUM_URL/" | grep -q "soulbind harness" || {
    log "the forum did not serve its own title; something is wrong before any test ran"
    exit 1
}

ENABLED=$(sql "SELECT value FROM settings WHERE \`key\`='extensions_enabled';" | tail -1)
case "$ENABLED" in
    *"$EXT_ID"*) log "extension enabled" ;;
    *) log "the extension is NOT enabled: $ENABLED"; exit 1 ;;
esac

# The webhook endpoint must exist and must REFUSE an unsigned request. If it
# 404s, the extension's routes did not load and every gate assertion after this
# would be testing an extension that is not running -- passing for the wrong
# reason, which is worse than failing.
log "smoke: the webhook endpoint exists and refuses an unsigned delivery"
WEBHOOK_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H 'Content-Type: application/json' -d '{}' "$FORUM_URL/soulbind/webhook")
case "$WEBHOOK_STATUS" in
    400|401)
        log "webhook refused an unsigned delivery with $WEBHOOK_STATUS" ;;
    404)
        log "the webhook endpoint 404s: the extension's routes did not load"
        exit 1 ;;
    200)
        log "the webhook ACCEPTED an unsigned delivery -- signature verification is not running"
        exit 1 ;;
    *)
        log "unexpected webhook status $WEBHOOK_STATUS"
        exit 1 ;;
esac

log "stack is up and the extension is live"

# --- the browser tier -------------------------------------------------------
#
# Four passes. Every change to the WORLD -- the policy rule, whether core is
# answering -- is made HERE, and the specs only assert.
#
# That split is not tidiness. The specs run inside the browser image; core runs
# in another container and its CLI needs a JVM the browser image does not have.
# The first version had the specs shell out to core and signal its process, and
# neither could have worked from where they run. Pushing the world into the
# orchestrator is what makes each spec a plain statement about what a person
# sees.
#
# The tags are the contract between the two. An outage spec run against a live
# core would assert a fail-closed message that never appears -- so the outage
# specs assert something that CANNOT hold while core is answering, and a
# mis-tagged spec fails loudly rather than passing in the wrong world.
log "browser tier"

browser() {
    podman run --rm --network host \
        -v "$REPO/harness/flarum/browser":/suite -w /suite \
        -e FORUM_URL="$FORUM_URL" \
        -e RUN_TAG="${RUN_TAG:-r}" \
        -e CI=1 \
        -e PLAYWRIGHT_JSON_OUTPUT_NAME="/suite/results.json" \
        "$FLARUM_BROWSER_IMAGE" "$@"
}

log "installing the browser suite's dependencies"
browser npm ci --no-audit --no-fund

log "pass 1 of 4: an unlinked account is refused, in core's own words"
core_cli rule set --gate forum-register --require-kinds game \
    --default-effect deny --detail "Link a game account before registering."
browser npx playwright test --grep "@refused"
cp "$REPO/harness/flarum/browser/results.json" "$RUN/playwright-1.json" 2>/dev/null || true

log "pass 2 of 4: the rule allows, and the account is admitted"
core_cli rule set --gate forum-register --default-effect allow
browser npx playwright test --grep "@admitted"
cp "$REPO/harness/flarum/browser/results.json" "$RUN/playwright-2.json" 2>/dev/null || true

log "pass 3 of 4: core is stopped, and the gate must hold"
core_cli rule set --gate forum-register --require-kinds game --default-effect deny
stop_core
browser npx playwright test --grep "@outage"
cp "$REPO/harness/flarum/browser/results.json" "$RUN/playwright-3.json" 2>/dev/null || true

log "pass 4 of 4: core returns, and nothing needs cleaning up after it"
start_core
core_cli rule set --gate forum-register --default-effect allow
browser npx playwright test --grep "@recovery"
cp "$REPO/harness/flarum/browser/results.json" "$RUN/playwright-4.json" 2>/dev/null || true

# --- every spec must have run somewhere -------------------------------------
#
# Four --grep passes is four chances for a spec to belong to no pass at all. A
# spec nobody runs is not a gap anybody notices: playwright reports "0 tests" as
# a success, the pass prints green, and the suite looks complete.
#
# So the passes are counted against the file. If somebody adds a test with a new
# tag, or misspells an existing one, the totals disagree and this says so.
EXPECTED_SPECS=$(grep -cE "^test\(" "$REPO/harness/flarum/browser/tests/gate.spec.js")
ACTUAL_SPECS=0
for r in "$RUN"/playwright-*.json; do
    [ -f "$r" ] || continue
    n=$(grep -o '"status":"passed"' "$r" | wc -l | tr -d ' ')
    ACTUAL_SPECS=$((ACTUAL_SPECS + n))
done

if [ "$ACTUAL_SPECS" -ne "$EXPECTED_SPECS" ]; then
    log "the browser tier ran $ACTUAL_SPECS specs but the file declares $EXPECTED_SPECS."
    log "a spec that belongs to no --grep pass never runs, and playwright reports"
    log "'0 tests' as a success -- so the pass prints green and the gap is invisible."
    exit 1
fi
log "browser tier green: $ACTUAL_SPECS of $EXPECTED_SPECS specs ran"
