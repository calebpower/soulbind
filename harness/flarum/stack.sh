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
# NOT under out/. reaper rsyncs out/ back at the end of a run, and the first
# version kept the whole Flarum site there -- so the sync raced composer writing
# vendor/ and failed with "file has vanished". out/ is for results, and results
# are small.
RUN=${RUN:-/tmp/soulbind-forum-stack}

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

# Is something listening on a port?
#
# A socket connect, NOT `curl -sf`. curl fails on any non-2xx, and core answers
# GET / with a status that is not 2xx -- so a curl readiness probe waits the full
# timeout against a core that came up fine, which is exactly what the first run
# did.
#
# Worse, the same mistake in the STOP direction reports success immediately: a
# curl that fails because core is refusing GET / is indistinguishable from a curl
# that fails because core is gone. The outage pass would then have run against a
# core that was still answering, and asserted a fail-closed message that never
# appears.
#
# Not /dev/tcp either: that is a bash feature, and this runs under the system sh.
# python3 is present on the guest and asks the same question everywhere.
port_open() {
    python3 -c "
import socket, sys
s = socket.socket()
s.settimeout(1)
sys.exit(0 if s.connect_ex(('127.0.0.1', $1)) == 0 else 1)
" 2>/dev/null
}

wait_for_port() {
    what=$1
    port=$2
    seconds=$3
    i=0
    while [ "$i" -lt "$seconds" ]; do
        if port_open "$port"; then
            log "$what is listening on $port (${i}s)"
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    # Loudly, and with the logs. A stack that half-came-up and was tested anyway
    # produces a failure report about soulbind describing somebody else's
    # problem.
    log "$what did not listen on $port within ${seconds}s; refusing to test against it"
    podman logs "$DB_C" 2>&1 | tail -20 || true
    podman logs "$CORE_C" 2>&1 | tail -30 || true
    podman logs "$WEB_C" 2>&1 | tail -40 || true
    return 1
}

wait_for_url() {
    what=$1
    url=$2
    seconds=$3
    i=0
    while [ "$i" -lt "$seconds" ]; do
        if curl -sf -o /dev/null "$url"; then
            log "$what answered $url (${i}s)"
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    log "$what did not answer $url within ${seconds}s; refusing to test against it"
    podman logs "$WEB_C" 2>&1 | tail -40 || true
    return 1
}

# A run must not inherit anything a previous run left behind: it would be
# serving the previous run's schema and settings, and a green result would be
# about state nobody in this session created.
cleanup >/dev/null 2>&1 || true
rm -rf "$RUN"
mkdir -p "$RUN/core" "$RUN/forum"

podman network create "$NET" >/dev/null

# --- pull everything first ---------------------------------------------------
#
# A digest that cannot be resolved fails CLOSED, which is right, but it failed
# three minutes in -- after MariaDB, after core, after installing Flarum and its
# 135 packages. The playwright pin was simply wrong: a digest I had written down
# from a misread header and never once pulled. It looked exactly as
# authoritative as the correct ones.
#
# Pulling up front turns that into a ten-second failure naming the image, and
# costs nothing on a run where the images are already present.
log "preflight: resolving every pinned image"
for image in "$FLARUM_DB_IMAGE" "$FLARUM_PHP_IMAGE" "$FLARUM_TOOLCHAIN_IMAGE" \
             "$COMPOSER_IMAGE" "$FLARUM_BROWSER_IMAGE"; do
    if ! podman image exists "$image" && ! podman pull --quiet "$image" >/dev/null 2>&1; then
        log "cannot resolve pinned image: $image"
        log "a digest that does not exist is not a pin, it is a typo that fails closed"
        exit 1
    fi
done
log "preflight: all five images resolve"

# --- the forum's PHP image --------------------------------------------------
#
# Built from the pinned base, with the extensions Flarum needs. Once, here,
# rather than on every container start: the first version installed pdo_mysql
# inline with `|| true` after it, so a failed install looked exactly like a
# successful one.
FORUM_IMAGE=soulbind-forum-php:harness

log "building the forum php image"
podman build --quiet \
    --build-arg "BASE=$FLARUM_PHP_IMAGE" \
    -f "$HERE/Containerfile" -t "$FORUM_IMAGE" "$HERE" >/dev/null

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
i=0
while [ "$i" -lt 90 ]; do
    podman exec "$DB_C" mariadb-admin ping -h 127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --silent >/dev/null 2>&1 && break
    i=$((i + 1)); sleep 1
done
[ "$i" -lt 90 ] || { log "mariadb did not become ready in 90s"; podman logs "$DB_C" 2>&1 | tail -20; exit 1; }
log "mariadb ready after ${i}s"

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
    wait_for_port core "$CORE_PORT" 90
}

stop_core() {
    podman stop -t 10 "$CORE_C" >/dev/null 2>&1 || true
    # Wait for the port to actually close. Stopping and immediately testing
    # races the shutdown, and a test that reached a dying core would prove
    # nothing about the outage path while looking like it had.
    i=0
    while [ "$i" -lt 30 ] && port_open "$CORE_PORT"; do
        i=$((i + 1)); sleep 1
    done
    if port_open "$CORE_PORT"; then
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
# From the SKELETON, not from flarum/core.
#
# flarum/core is a library. The `flarum` CLI, `public/index.php`, `storage/` and
# the rest of a runnable site come from the flarum/flarum skeleton, which is a
# project rather than a dependency. The first attempt required core alone and
# got "Could not open input file: flarum" -- a site with the engine and no car
# around it.
log "installing flarum $FLARUM_VERSION"

# Stage what a USER would install, not the working tree.
#
# A composer path repository copies the directory as it finds it, and the
# working tree carries a dev vendor/ (phpunit and 48 other packages), a
# .phpunit.result.cache and browser-tier leftovers. Installing that means the
# forum under test is running something no released version of this extension
# would ever be, and a vendor/ nested inside vendor/ is a class of autoload
# problem nobody should be debugging by accident.
#
# The exclusions are the release boundary, stated in one place.
EXT_SRC="$RUN/extension"
rm -rf "$EXT_SRC"
mkdir -p "$EXT_SRC"
tar -C "$REPO/connector-flarum" \
    --exclude=vendor \
    --exclude=node_modules \
    --exclude=.phpunit.result.cache \
    --exclude=composer.lock \
    -cf - . | tar -C "$EXT_SRC" -xf -

# The staged copy must actually be a Flarum extension, or the install below
# succeeds and the forum silently has no extension in it -- which is exactly the
# failure this run is chasing.
[ -f "$EXT_SRC/extend.php" ] || { log "staged extension has no extend.php"; exit 1; }
grep -q '"type": *"flarum-extension"' "$EXT_SRC/composer.json" \
    || { log "staged composer.json does not declare type=flarum-extension"; exit 1; }
log "staged the extension for install ($(find "$EXT_SRC" -type f | wc -l | tr -d ' ') files)"

SITE="$RUN/forum/site"
mkdir -p "$SITE"

composer_in_site() {
    podman run --rm --network "$NET" \
        -v "$SITE":/site \
        -v "$EXT_SRC":/extension:ro \
        -w /site -e COMPOSER_HOME=/tmp/composer \
        "$COMPOSER_IMAGE" composer "$@" --no-interaction
}

# --no-progress is NOT appended here. `composer config` rejects it outright --
# "The --no-progress option does not exist" -- so a helper that adds it to every
# subcommand works for install and require and breaks for config. Passed at the
# call sites that accept it instead.

SITE_LOCK="$REPO/harness/flarum/site-composer.lock"

podman run --rm --network "$NET" \
    -v "$RUN/forum":/parent -w /parent -e COMPOSER_HOME=/tmp/composer \
    "$COMPOSER_IMAGE" composer create-project \
    "flarum/flarum:^${FLARUM_VERSION%.*}" site --no-interaction --no-progress --no-install

if [ -f "$SITE_LOCK" ]; then
    cp "$SITE_LOCK" "$SITE/composer.lock"
    log "installing the forum from its committed lock"
else
    log "NO committed site lock; this run resolves fresh and emits one to out/"
fi

# Resolve for the PHP the SITE runs on, not the PHP composer runs on.
#
# The composer image carries its own PHP -- 8.4 -- and without being told
# otherwise it resolves a dependency tree for that. The site then starts on the
# pinned 8.3 image and dies before Flarum's first line:
#
#   Your Composer dependencies require a PHP version ">= 8.4.1".
#   You are running 8.3.33.
#
# `--ignore-platform-reqs` would silence it and ship a site whose dependencies
# genuinely do not support its runtime -- a crash moved from install time to
# whenever the first incompatible line executes.
#
# The version is READ from the image rather than written here, so re-pinning the
# php image cannot leave a stale number behind. The resolver and the runtime
# agree by construction.
SITE_PHP=$(podman run --rm "$FORUM_IMAGE" php -r 'echo PHP_VERSION;')
case "$SITE_PHP" in
    [0-9]*.[0-9]*.[0-9]*) : ;;
    *) log "could not read a PHP version from the forum image, got '$SITE_PHP'"; exit 1 ;;
esac
log "resolving for the site's PHP $SITE_PHP"
composer_in_site config platform.php "$SITE_PHP"

# The extension arrives as a path repository. `*@dev` is scoped to THIS package
# only -- it is a working tree with no version tag, so composer reads it as
# dev-main. Relaxing minimum-stability globally would let a dev release of
# Flarum or any of its dependencies in, and the forum under test would quietly
# stop being the forum people run.
composer_in_site config repositories.soulbind '{"type":"path","url":"/extension","options":{"symlink":false}}'
composer_in_site require "soulbind/flarum-connector:*@dev" --no-plugins --no-progress

# out/ carries RESULTS, and only small ones: reaper rsyncs it back, and the
# first version put the whole site under it -- so the sync raced composer
# writing vendor/ and failed with "file has vanished". The site lives outside
# out/ now, and only the lock is copied in.
if [ ! -f "$SITE_LOCK" ]; then
    mkdir -p "$REPO/out"
    cp "$SITE/composer.lock" "$REPO/out/site-composer.lock"
    log "site lock written to out/site-composer.lock -- review and commit it"
fi

# Flarum's own installer, driven from a file rather than interactively.
cat > "$SITE/install.yml" <<YML
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
    -v "$SITE":/site -w /site \
    "$FORUM_IMAGE" php flarum install --file=install.yml

log "starting the forum"
# php -S, not a real web server. This is a harness: a browser tier needs a URL
# that serves Flarum, not a production topology. The router script below is what
# an nginx try_files rule would do, and is the whole difference.
cat > "$SITE/router.php" <<'PHPR'
<?php
// The front controller, for php -S.
//
// chdir into public/ FIRST. Flarum's public/index.php does `require
// '../site.php'`, which resolves against the WORKING DIRECTORY and not against
// the file -- under a real web server the cwd is the document root, and under
// `php -S` it is wherever the server was started. Without this the site fails
// with "require(../site.php): Failed to open stream", which reads like a broken
// Flarum install and is really a broken harness.
chdir(__DIR__ . '/public');

// Serve a real file if one exists, otherwise hand everything to Flarum --
// exactly what `try_files $uri /index.php?$query_string` does.
$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
if ($path !== '/' && is_file(__DIR__ . '/public' . $path)) {
    return false;
}
require __DIR__ . '/public/index.php';
PHPR

# PHP_CLI_SERVER_WORKERS, because php -S serves ONE connection at a time.
#
# curl is happy with that: it asks for one thing and waits. A browser is not --
# it opens several keep-alive connections at once, and a single-worker server
# holds them, so the page stalls with its scripts still pending. The symptom is
# a Playwright timeout waiting for a button, which points at the selector and
# not at the server.
#
# Still not a production topology, and still not pretending to be: this is a
# harness, and the difference from nginx is stated rather than papered over.
podman run -d --name "$WEB_C" --network "$NET" \
    -p "${FORUM_PORT}:${FORUM_PORT}" \
    -e PHP_CLI_SERVER_WORKERS=8 \
    -v "$SITE":/site -w /site \
    "$FORUM_IMAGE" php -S "0.0.0.0:${FORUM_PORT}" router.php >/dev/null

wait_for_url "the forum" "$FORUM_URL/" 120

log "forum is up on $FORUM_PORT"

# A BASELINE, taken before the extension is enabled.
#
# Without it, a failure after enabling is ambiguous: it could be the extension
# breaking Flarum, or the check itself being wrong about what a working page
# looks like. Those send somebody to read entirely different code, and the
# difference costs one curl.
log "baseline: the forum renders BEFORE the extension is enabled"
BASELINE_BODY="$RUN/forum-baseline.html"
BASELINE_STATUS=$(curl -s -o "$BASELINE_BODY" -w '%{http_code}' "$FORUM_URL/" || echo 000)
if [ "$BASELINE_STATUS" != "200" ] || ! grep -q "soulbind harness" "$BASELINE_BODY"; then
    log "the forum does not render even WITHOUT the extension: HTTP $BASELINE_STATUS"
    log "so the check below would have blamed the extension for something else"
    head -40 "$BASELINE_BODY" 2>/dev/null || true
    podman logs "$WEB_C" 2>&1 | tail -40 || true
    exit 1
fi
log "baseline good"

log "core credential for the forum is in $RUN/forum.credential"

# --- enable and configure the extension -------------------------------------
#
# Through the database, which is what the admin UI writes. A CLI command would
# be tidier if one existed for every setting; it does not, and half the values
# below are this extension's own. Writing what the UI writes keeps one code path
# rather than a second that can drift from it.
log "enabling and configuring the extension"

# SQL arrives on STDIN, not through -e.
#
# The -e form put a multi-line statement containing backticks, JSON and a
# credential through two levels of shell quoting, and when it silently changed
# nothing there was no way to tell whether the statement was wrong, the quoting
# was wrong, or the write had happened and been overwritten. A heredoc has one
# level of quoting and the client reports its own errors.
sql() {
    podman exec -i "$DB_C" mariadb --batch --skip-column-names \
        -h 127.0.0.1 -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" 2>/dev/null
}

# Ask FLARUM for the extension id. Do not compute it here.
#
# Flarum strips a leading `flarum-` from the package half of the composer name,
# so `soulbind/flarum-connector` becomes `soulbind-connector` and not the
# intuitive `soulbind-flarum-connector`. Enabling the intuitive one writes a
# setting no extension answers to: composer, installed.json and extend.php all
# look perfect, the extension stays disabled, and the routes never register.
# That cost four iterations of this harness.
#
# Hardcoding the CORRECT id here would fix today and re-arm the same trap for
# whoever renames the package. The id comes from the ExtensionManager, which is
# the thing that decides.
EXT_ID=$(podman exec "$WEB_C" php -r '
    require "/site/vendor/autoload.php";
    $site = require "/site/site.php";
    $m = $site->bootApp()->getContainer()->make(Flarum\Extension\ExtensionManager::class);
    foreach ($m->getExtensions() as $id => $ext) {
        if (str_contains((string) $ext->name, "soulbind")) { echo $id; return; }
    }
' 2>/dev/null)

if [ -z "$EXT_ID" ]; then
    log "Flarum does not know about the soulbind extension at all."
    log "It was installed, so this is discovery failing rather than installation."
    podman exec "$WEB_C" php -r '
        require "/site/vendor/autoload.php";
        $site = require "/site/site.php";
        $m = $site->bootApp()->getContainer()->make(Flarum\Extension\ExtensionManager::class);
        echo "discovered: ", count($m->getExtensions()), "\n";
        foreach ($m->getExtensions() as $id => $e) { echo "  ", $id, "\n"; }
    ' 2>&1 | head -25 || true
    exit 1
fi
log "Flarum calls this extension '$EXT_ID'"

FORUM_CRED_VALUE=$(cat "$RUN/forum.credential")

sql <<SQL
REPLACE INTO settings (\`key\`, \`value\`) VALUES
  ('extensions_enabled', '["${EXT_ID}"]'),
  ('soulbind.core_url', 'http://${CORE_C}:${CORE_PORT}/'),
  ('soulbind.credential', '${FORUM_CRED_VALUE}'),
  ('soulbind.webhook_secret', '${FORUM_CRED_VALUE}'),
  ('soulbind.fail_mode', 'closed'),
  ('soulbind.register_gate', 'forum-register'),
  ('soulbind.post_gate', 'forum-post'),
  ('soulbind.timeout_ms', '2000');
SQL

# Read it back IMMEDIATELY, and print what is actually there.
#
# The previous version wrote, then checked much later, and reported only
# "the extension is NOT enabled: []" -- which does not distinguish a write that
# failed from a write that was undone in between. Checking here narrows it to
# one statement.
ENABLED=$(sql <<'SQL'
SELECT `value` FROM settings WHERE `key` = 'extensions_enabled';
SQL
)
case "$ENABLED" in
    *"$EXT_ID"*)
        log "extensions_enabled is now: $ENABLED" ;;
    *)
        log "the write did not take. extensions_enabled is: '$ENABLED'"
        log "--- every soulbind setting row, as stored ---"
        sql <<'SQL'
SELECT `key`, `value` FROM settings WHERE `key` LIKE 'soulbind%' OR `key` = 'extensions_enabled';
SQL
        exit 1 ;;
esac

# Flarum caches settings and the extension list. Without clearing, the running
# server keeps serving the state it read at boot, and every gate assertion would
# be about a forum that has not noticed the extension exists.
#
# NOT `|| true`. If the cache cannot be cleared, the tests that follow are
# meaningless, and hiding that is how a green run gets reported for a stack that
# never picked up its own configuration.
podman exec "$WEB_C" php flarum cache:clear

# Assets are compiled from the ENABLED extension set, and the set changed after
# install published them. Without this the forum serves the bundle it built
# before the extension existed.
podman exec "$WEB_C" php flarum assets:publish

# --- the smoke --------------------------------------------------------------
#
# Proves the pieces are actually wired before any browser opens. A Playwright
# failure against a stack that never came up correctly is a report about the
# harness wearing the costume of a report about soulbind.
log "smoke: the forum still serves with the extension enabled"

# Captured, not piped into grep. The first version was
# `curl -sf "$FORUM_URL/" | grep -q "soulbind harness"`, which collapses every
# possible failure -- a 500, an empty body, a redirect, a working page whose
# title is simply spelled differently -- into one message that names none of
# them. A smoke test that cannot say what went wrong sends somebody to read the
# wrong code.
SMOKE_BODY="$RUN/forum-index.html"
SMOKE_STATUS=$(curl -s -o "$SMOKE_BODY" -w '%{http_code}' "$FORUM_URL/" || echo 000)

smoke_failed() {
    log "SMOKE FAILED: $1"
    log "HTTP $SMOKE_STATUS from $FORUM_URL/ ($(wc -c < "$SMOKE_BODY" 2>/dev/null || echo 0) bytes)"
    log "--- first 40 lines of the response ---"
    head -40 "$SMOKE_BODY" 2>/dev/null || true
    log "--- what Flarum's own ExtensionManager sees ---"
    # Asking the class that actually decides, in process.
    #
    # Everything upstream now checks out: installed.json records the package with
    # type=flarum-extension, and extend.php loads and returns its extenders. Yet
    # `flarum info` lists nothing. Those cannot both be true unless Flarum is
    # looking somewhere other than where I am looking -- and no amount of
    # reasoning from outside will say where. So this asks it.
    podman exec "$WEB_C" php -r '
        require "/site/vendor/autoload.php";
        try {
            $site = require "/site/site.php";
            $app = $site->bootApp();
            $paths = $app->getContainer()->make(Flarum\Foundation\Paths::class);
            echo "vendor path Flarum uses: ", $paths->vendor, "\n";
            echo "installed.json exists there: ",
                is_file($paths->vendor . "/composer/installed.json") ? "yes" : "NO", "\n";
            $m = $app->getContainer()->make(Flarum\Extension\ExtensionManager::class);
            $all = $m->getExtensions();
            echo "extensions discovered: ", count($all), "\n";
            foreach ($all as $id => $ext) {
                echo "  ", $id, " enabled=", $m->isEnabled($id) ? "yes" : "no", "\n";
            }
        } catch (Throwable $e) {
            echo "booting Flarum threw ", get_class($e), ": ", $e->getMessage(), "\n";
            echo "  at ", $e->getFile(), ":", $e->getLine(), "\n";
        }
    ' 2>&1 | head -25 || true

    log "--- web container log ---"
    podman logs "$WEB_C" 2>&1 | tail -40 || true
    exit 1
}

[ "$SMOKE_STATUS" = "200" ] || smoke_failed "the forum did not answer 200"

# The title proves the page RENDERED, not merely that something answered. A
# Flarum error page is still HTTP 200 in some configurations.
grep -q "soulbind harness" "$SMOKE_BODY" \
    || smoke_failed "the response did not contain the forum title"

# `if`, not `grep ... && smoke_failed`. Under set -e the GOOD case -- grep
# finding nothing and returning 1 -- makes that compound return 1, and the
# script aborts on the path where everything is fine. A guard that fails when
# it passes is worse than no guard: it would have been "fixed" by deleting it.
if grep -qi "fatal error\|stack trace" "$SMOKE_BODY"; then
    smoke_failed "the page rendered a PHP error"
fi

log "the forum renders with the extension enabled"

# --- can the page's own assets load? ----------------------------------------
#
# Flarum's front page is a shell that boots a JavaScript application. If its
# bundles do not serve, the HTML still arrives, still contains the forum title,
# and still answers 200 -- so every check above passes and the browser sits
# waiting sixty seconds for a button that will never be rendered.
#
# That is what happened. Diagnosing it through a Playwright timeout costs a
# minute per attempt and points at the selector rather than at the server.
# Fetching the assets the page asks for costs a second and names the real fault.
log "smoke: the page's own assets serve"

ASSET_FAILURES=0
ASSET_COUNT=0
for url in $(grep -oE '(src|href)="[^"]*\.(js|css)[^"]*"' "$SMOKE_BODY" \
             | sed -E 's/^(src|href)="//; s/"$//' | sort -u); do
    case "$url" in
        http*) full="$url" ;;
        /*)    full="${FORUM_URL}${url}" ;;
        *)     full="${FORUM_URL}/${url}" ;;
    esac
    ASSET_COUNT=$((ASSET_COUNT + 1))
    status=$(curl -s -o /dev/null -w '%{http_code}' "$full" || echo 000)
    if [ "$status" != "200" ]; then
        log "  asset $status $full"
        ASSET_FAILURES=$((ASSET_FAILURES + 1))
    fi
done

if [ "$ASSET_COUNT" -eq 0 ]; then
    log "the page references no scripts or stylesheets at all."
    log "Flarum's front page boots a JavaScript application; a shell with no bundle"
    log "means the assets were never published, and no browser test can pass."
    head -60 "$SMOKE_BODY" || true
    exit 1
fi

if [ "$ASSET_FAILURES" -ne 0 ]; then
    log "$ASSET_FAILURES of $ASSET_COUNT assets did not serve; the SPA cannot boot"
    podman logs "$WEB_C" 2>&1 | tail -30 || true
    exit 1
fi
log "all $ASSET_COUNT referenced assets serve"



# The webhook endpoint must exist and must REFUSE an unsigned request. If it
# 404s, the extension's routes did not load and every gate assertion after this
# would be testing an extension that is not running -- passing for the wrong
# reason, which is worse than failing.
log "smoke: the webhook endpoint exists and refuses an unsigned delivery"

webhook_failed() {
    log "SMOKE FAILED: $1"
    log "--- what Flarum itself thinks is installed ---"
    # Flarum's own view, not mine. The settings table says what SHOULD be
    # enabled; this says what Flarum actually loaded, and the gap between them
    # is the whole question.
    podman exec "$WEB_C" php flarum info 2>&1 | head -30 || true

    log "--- how composer recorded the package ---"
    # Flarum discovers extensions from installed.json, filtering on
    # type=flarum-extension. If the entry is missing or the type is wrong,
    # Flarum never looks at extend.php at all -- which is a different fault from
    # extend.php failing, and lives in a different file.
    podman exec "$WEB_C" php -r '
        $f = "/site/vendor/composer/installed.json";
        $d = json_decode((string) file_get_contents($f), true);
        $packages = $d["packages"] ?? $d;
        foreach ($packages as $p) {
            if (str_contains($p["name"] ?? "", "soulbind")) {
                echo "name:    ", $p["name"], "\n";
                echo "type:    ", $p["type"] ?? "(none)", "\n";
                echo "version: ", $p["version"] ?? "(none)", "\n";
                echo "extra:   ", json_encode($p["extra"] ?? null), "\n";
                echo "install-path: ", $p["install-path"] ?? "(none)", "\n";
                exit;
            }
        }
        echo "NO soulbind package in installed.json at all\n";
    ' 2>&1 | head -20 || true

    log "--- does extend.php load, WITH flarum's autoloader ---"
    # With the site autoloader. The first version of this check ran php -r
    # without it and reported
    # "Class Flarum\Extend\ServiceProvider not found" -- which says nothing
    # about the extension and everything about the diagnostic. A diagnostic that
    # manufactures its own failure is worse than none: it is a false lead that
    # looks like evidence.
    podman exec "$WEB_C" php -r '
        require "/site/vendor/autoload.php";
        $f = "/site/vendor/soulbind/flarum-connector/extend.php";
        if (!is_file($f)) { echo "extend.php is not there\n"; exit; }
        try { $r = require $f; echo "extend.php returned ", count($r), " extenders\n"; }
        catch (Throwable $e) {
            echo "extend.php threw ", get_class($e), ": ", $e->getMessage(), "\n";
            echo "  at ", $e->getFile(), ":", $e->getLine(), "\n";
        }
    ' 2>&1 | head -20 || true

    log "--- what Flarum's own ExtensionManager sees ---"
    # Asking the class that actually decides, in process.
    #
    # Everything upstream now checks out: installed.json records the package with
    # type=flarum-extension, and extend.php loads and returns its extenders. Yet
    # `flarum info` lists nothing. Those cannot both be true unless Flarum is
    # looking somewhere other than where I am looking -- and no amount of
    # reasoning from outside will say where. So this asks it.
    podman exec "$WEB_C" php -r '
        require "/site/vendor/autoload.php";
        try {
            $site = require "/site/site.php";
            $app = $site->bootApp();
            $paths = $app->getContainer()->make(Flarum\Foundation\Paths::class);
            echo "vendor path Flarum uses: ", $paths->vendor, "\n";
            echo "installed.json exists there: ",
                is_file($paths->vendor . "/composer/installed.json") ? "yes" : "NO", "\n";
            $m = $app->getContainer()->make(Flarum\Extension\ExtensionManager::class);
            $all = $m->getExtensions();
            echo "extensions discovered: ", count($all), "\n";
            foreach ($all as $id => $ext) {
                echo "  ", $id, " enabled=", $m->isEnabled($id) ? "yes" : "no", "\n";
            }
        } catch (Throwable $e) {
            echo "booting Flarum threw ", get_class($e), ": ", $e->getMessage(), "\n";
            echo "  at ", $e->getFile(), ":", $e->getLine(), "\n";
        }
    ' 2>&1 | head -25 || true

    log "--- web container log ---"
    podman logs "$WEB_C" 2>&1 | tail -20 || true
    exit 1
}

WEBHOOK_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H 'Content-Type: application/json' -d '{}' "$FORUM_URL/soulbind/webhook")
case "$WEBHOOK_STATUS" in
    400|401)
        log "webhook refused an unsigned delivery with $WEBHOOK_STATUS" ;;
    404)
        webhook_failed "the webhook endpoint 404s: the extension's routes did not load" ;;
    200)
        webhook_failed "the webhook ACCEPTED an unsigned delivery -- signature verification is not running" ;;
    *)
        webhook_failed "unexpected webhook status $WEBHOOK_STATUS" ;;
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

# Rules arrive over the PROTOCOL, through the shared script.
#
# Core has no `rule` CLI verb, deliberately -- its own help says every other
# operation is reachable through an admin credential, "not a second management
# surface with rules that drift from the first". I wrote `core_cli rule set`
# anyway, and it told me: unknown verb: rule.
#
# harness/fullstack/set-rule.sh already did this, signed correctly, for Phase 5.
# It is now parameterised rather than copied: one implementation of the signing,
# and its three-argument form still behaves exactly as it did.
set_rule() {
    "$REPO/harness/fullstack/set-rule.sh" \
        "http://127.0.0.1:$CORE_PORT" "$HARNESS_CRED" forum-register "$1" "$2"
}

browser() {
    podman run --rm --network host \
        -v "$REPO/harness/flarum/browser":/suite -w /suite \
        -e FORUM_URL="$FORUM_URL" \
        -e RUN_TAG="${RUN_TAG:-r}" \
        -e CI=1 \
        -e PLAYWRIGHT_JSON_OUTPUT_NAME="/suite/results.json" \
        "$FLARUM_BROWSER_IMAGE" "$@"
}

# Installed from a COMMITTED lock, or a lock is emitted for review.
#
# Same discipline as the PHP tree and the image digests: a browser tier whose
# dependencies resolve fresh on every run is a tier whose green result is about
# whatever npm felt like that day. `npm ci` is the install that honours a lock
# and refuses without one -- which is how this surfaced.
#
# npm is broken on the workstation (MODULE_NOT_FOUND inside npm's own tree, for
# any package), so the lock is generated HERE, in the image that will use it,
# and copied out to be committed.
BROWSER_LOCK="$REPO/harness/flarum/browser/package-lock.json"

if [ -f "$BROWSER_LOCK" ]; then
    log "installing the browser suite from its committed lock"
    browser npm ci --no-audit --no-fund
else
    log "NO committed browser lock; generating one and emitting it to out/"
    browser npm install --package-lock-only --no-audit --no-fund
    mkdir -p "$REPO/out"
    cp "$BROWSER_LOCK" "$REPO/out/browser-package-lock.json"
    log "browser lock written to out/browser-package-lock.json -- review and commit it"
    browser npm ci --no-audit --no-fund
fi

log "pass 1 of 4: an unlinked account is refused, in core's own words"
set_rule true deny
browser npx playwright test --grep "@refused"
cp "$REPO/harness/flarum/browser/results.json" "$RUN/playwright-1.json" 2>/dev/null || true

log "pass 2 of 4: the rule allows, and the account is admitted"
set_rule false allow
browser npx playwright test --grep "@admitted"
cp "$REPO/harness/flarum/browser/results.json" "$RUN/playwright-2.json" 2>/dev/null || true

log "pass 3 of 4: core is stopped, and the gate must hold"
set_rule true deny
stop_core
browser npx playwright test --grep "@outage"
cp "$REPO/harness/flarum/browser/results.json" "$RUN/playwright-3.json" 2>/dev/null || true

log "pass 4 of 4: core returns, and nothing needs cleaning up after it"
start_core
set_rule false allow
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
