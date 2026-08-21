# Builds and runs core for the operator-tool smokes, on a workstation or on the
# guest, without either one needing to know which it is.
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
# Sourced, not executed:
#
#     . "$HERE/core-env.sh"
#     core_env_init "$WORK" "$PORT" "$REPO"   # picks a mode and builds core
#     CRED=$(core_cli register --name x --quiet --capabilities a,b)
#     core_serve                            # backgrounds it, waits for the port
#
# WHY THIS EXISTS: the workstation has a JDK and no podman; the reaper guest has
# podman and no JDK on the host -- `java` there lives only inside the pinned
# toolchain image. A smoke that ran gradle on the host worked on the developer's
# machine and failed the session run with "JAVA_HOME is not set and no 'java'
# command could be found", which is the failure this file exists to stop
# repeating. The tools under test are unaffected either way: they speak HTTP to
# a published port and never see which side of this the server came from.
#
# The image is the one harness/flarum/pins.env already pins by digest. A second
# pin for the same toolchain is a second thing to bump and a second thing to
# forget.

CORE_ENV_MODE=""
CORE_ENV_WORK=""
CORE_ENV_PORT=""
CORE_ENV_PID=""
CORE_ENV_CONTAINER=""

# core_env_init <work-dir> <port> <repo-root>
#
# The repo root is a PARAMETER rather than derived from $0. Deriving it worked
# for the caller it was written against and pointed one directory above the
# repository for the next one, which surfaced as "./gradlew: not found" --
# a sourced POSIX shell file has no reliable way to find its own path.
# Where the JDK actually lives.
#
# NOT `dirname $(dirname "$JAVA")` on whatever string was handed over. That
# works for an absolute path and produces "." for a bare `java` off PATH, which
# is what run 15 handed to gradle. It is also wrong for the usual Linux layout,
# where /usr/bin/java is a symlink through /etc/alternatives into the real JDK:
# two dirnames of the symlink give /usr, which has no bin/javac under it.
#
# Resolved, then CHECKED. A JAVA_HOME that does not contain a javac is not a
# JAVA_HOME, and saying so here costs one line; letting gradle discover it
# costs a stage failure that names neither this file nor the reason.
# Core is built --release 25 (build-logic/soulbind.java-25.gradle.kts), so a
# JDK below that starts core and then dies with UnsupportedClassVersionError at
# class load -- a message about "class file version 69.0" that names nothing an
# operator or a stage can act on.
CORE_ENV_MIN_JDK=25

core_env_java_home() {
    # EXPLICIT JAVA FIRST. This ordering was the other way round for exactly
    # one run and it cost a green smoke: this workstation carries an ambient
    # JAVA_HOME=/usr/local/openjdk17, which has a perfectly good javac, so a
    # caller passing JAVA=/usr/local/openjdk25/bin/java was overruled by the
    # environment it was trying to override. Core then failed to start with a
    # LinkageError two layers from the cause.
    #
    # A caller who names a JDK has made the decision. Nothing ambient outranks
    # it -- which is the same rule the build itself follows, since bare `java`
    # on this machine is 17 and every toolchain here is declared rather than
    # inherited.
    binary=${JAVA:-}
    if [ -z "$binary" ] || [ ! -x "$binary" ]; then
        binary=""
        if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
            binary=$JAVA_HOME/bin/java
        else
            binary=$(command -v javac 2>/dev/null) || binary=""
        fi
    fi
    if [ -z "$binary" ]; then
        echo "[core-env] cannot find a JDK: set JAVA to its bin/java" >&2
        return 1
    fi

    resolved=$(readlink -f "$binary" 2>/dev/null) || resolved=$binary
    [ -n "$resolved" ] || resolved=$binary
    home=$(dirname "$(dirname "$resolved")")

    if [ ! -x "$home/bin/javac" ]; then
        echo "[core-env] $home has no bin/javac, so it is a JRE or not a JDK at all." >&2
        echo "[core-env] Derived from '$binary'. Set JAVA to a JDK's bin/java." >&2
        return 1
    fi

    # Checked here rather than left to fail at class load. The version is read
    # from the JDK itself, not assumed from a path name.
    major=$("$home/bin/java" -version 2>&1 | head -1 \
        | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')
    if [ -n "$major" ] && [ "$major" -lt "$CORE_ENV_MIN_JDK" ]; then
        echo "[core-env] $home is Java $major; core is built for $CORE_ENV_MIN_JDK." >&2
        echo "[core-env] It would start and then die with UnsupportedClassVersionError." >&2
        echo "[core-env] Set JAVA to a $CORE_ENV_MIN_JDK+ JDK's bin/java." >&2
        return 1
    fi
    echo "$home"
}

core_env_init() {
    CORE_ENV_WORK=$1
    CORE_ENV_PORT=$2
    CORE_ENV_REPO=$3

    # Order matters, and run 15 is why.
    #
    # The first version asked `command -v java` first and took host mode if it
    # found one. On the guest that was true only because the CLEAN-INSTALL GATE
    # had installed a JRE the run before -- reaper rolls back the state dataset,
    # not the root disk, so the gate's apt install outlived its own run. Run 14
    # chose container mode; run 15, on the same guest, chose host, and gradle
    # died with "JAVA_HOME is set to an invalid directory: ." two stages before
    # anything it could have been blamed on.
    #
    # Two separate bugs in that one line. `java` proves a RUNTIME exists, and
    # gradle needs a toolchain to compile -- openjdk-25-jre-headless has no
    # javac. And a bare `java` from PATH is not a path, so the JAVA_HOME
    # arithmetic below produced ".".
    #
    # So: an explicitly supplied JAVA wins, because a caller naming one has
    # made the decision. Otherwise podman, because a digest-pinned image is the
    # same toolchain every time and whatever is installed on a shared guest is
    # not. Only then a JDK found on PATH, and it must be a JDK.
    if [ -n "${JAVA:-}" ] && [ -x "${JAVA:-}" ]; then
        CORE_ENV_MODE=host
    elif command -v podman > /dev/null 2>&1; then
        CORE_ENV_MODE=container
    elif command -v javac > /dev/null 2>&1; then
        CORE_ENV_MODE=host
    else
        echo "[core-env] no JDK and no podman: a JRE alone cannot build core." >&2
        echo "[core-env] set JAVA to a JDK's bin/java, or install podman." >&2
        return 1
    fi
    echo "[core-env] mode: $CORE_ENV_MODE"

    if [ "$CORE_ENV_MODE" = host ]; then
        JAVA_HOME=$(core_env_java_home) || return 1
        export JAVA_HOME
        (cd "$CORE_ENV_REPO" && ./gradlew --no-daemon --quiet :core:installDist)
    else
        # shellcheck disable=SC1090
        . "$CORE_ENV_REPO/harness/flarum/pins.env"
        CORE_ENV_IMAGE=$FLARUM_TOOLCHAIN_IMAGE
        podman run --rm -v "$CORE_ENV_REPO":/work -w /work \
            -e GRADLE_USER_HOME=/work/.gradle-tools-home \
            "$CORE_ENV_IMAGE" \
            ./gradlew --no-daemon --quiet --project-cache-dir /tmp/gradle-tools-cache \
            :core:installDist
    fi

    # The config differs by mode only in where the database file lands, because
    # in container mode the work directory is a volume at a fixed path.
    if [ "$CORE_ENV_MODE" = host ]; then
        CORE_ENV_DB="$CORE_ENV_WORK/soulbind.db"
        CORE_ENV_HOST=127.0.0.1
    else
        CORE_ENV_DB="/state/soulbind.db"
        CORE_ENV_HOST=0.0.0.0
    fi

    cat > "$CORE_ENV_WORK/soulbind.toml" <<TOML
[server]
host = "$CORE_ENV_HOST"
port = $CORE_ENV_PORT

[storage]
backend = "sqlite"
url = "jdbc:sqlite:$CORE_ENV_DB"

[linking]
codettlseconds = 600
TOML
}

# One helper for every CLI call, so no call site can quietly use different
# paths from another.
core_cli() {
    if [ "$CORE_ENV_MODE" = host ]; then
        "$CORE_ENV_REPO/core/build/install/core/bin/core" "$@" \
            --config "$CORE_ENV_WORK/soulbind.toml"
    else
        podman run --rm -v "$CORE_ENV_REPO":/work:ro -v "$CORE_ENV_WORK":/state \
            "$CORE_ENV_IMAGE" \
            /work/core/build/install/core/bin/core "$@" --config /state/soulbind.toml
    fi
}

core_serve() {
    if [ "$CORE_ENV_MODE" = host ]; then
        "$CORE_ENV_REPO/core/build/install/core/bin/core" serve \
            --config "$CORE_ENV_WORK/soulbind.toml" \
            > "$CORE_ENV_WORK/core.log" 2>&1 &
        CORE_ENV_PID=$!
    else
        CORE_ENV_CONTAINER=soulbind-tools-core-$$
        podman run -d --name "$CORE_ENV_CONTAINER" \
            -p "127.0.0.1:$CORE_ENV_PORT:$CORE_ENV_PORT" \
            -v "$CORE_ENV_REPO":/work:ro -v "$CORE_ENV_WORK":/state \
            "$CORE_ENV_IMAGE" \
            /work/core/build/install/core/bin/core serve --config /state/soulbind.toml \
            > "$CORE_ENV_WORK/core.cid" 2>&1
    fi

    i=0
    while [ "$i" -lt 60 ]; do
        if python3 -c "
import socket, sys
s = socket.socket(); s.settimeout(1)
sys.exit(0 if s.connect_ex(('127.0.0.1', $CORE_ENV_PORT)) == 0 else 1)" 2>/dev/null; then
            return 0
        fi
        i=$((i + 1)); sleep 1
    done

    echo "[core-env] core never listened on $CORE_ENV_PORT" >&2
    if [ "$CORE_ENV_MODE" = host ]; then
        tail -20 "$CORE_ENV_WORK/core.log" 2>/dev/null | sed 's/^/  /' >&2
    else
        podman logs "$CORE_ENV_CONTAINER" 2>&1 | tail -20 | sed 's/^/  /' >&2
    fi
    return 1
}

core_stop() {
    if [ -n "$CORE_ENV_PID" ]; then
        kill "$CORE_ENV_PID" 2>/dev/null
        CORE_ENV_PID=""
    fi
    if [ -n "$CORE_ENV_CONTAINER" ]; then
        podman rm -f "$CORE_ENV_CONTAINER" > /dev/null 2>&1
        CORE_ENV_CONTAINER=""
    fi
    return 0
}

core_url() {
    echo "http://127.0.0.1:$CORE_ENV_PORT"
}
