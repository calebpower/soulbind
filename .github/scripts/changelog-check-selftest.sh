#!/bin/sh
# The control-and-mutants check for changelog-check.sh.
#
#     changelog-check-selftest.sh
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
# WHY THIS EXISTS. changelog-check.sh runs in exactly one place -- a tag push,
# on a runner -- so the only way to exercise it by tagging is to tag. That is
# the one operation this repository will not do speculatively: a pushed tag
# PUBLISHES a release, with no draft in between.
#
# So the check is a script rather than eight lines of YAML, and this runs it
# against constructed fixtures instead. Every mutant here is a changelog that is
# wrong in one specific way, and every one must be REFUSED with the right code.
#
# THE TWO CONTROLS ARE THE POINT, not padding. `named` and `empty-unreleased`
# both have to go green, because a check that refuses everything kills every
# mutant and asserts nothing -- harness/fullstack/mutation/mutants.txt records
# the run where exactly that happened. `empty-unreleased` specifically pins that
# an Unreleased HEADING is fine and only Unreleased CONTENT is not.
#
# `live` is the last case and is different in kind: it runs the check against
# this repository's real CHANGELOG.md for the version it actually last released.
# The fixtures prove the logic; this proves the logic agrees with reality --
# specifically that the heading the check looks for is the heading the file
# really writes, em dash, spacing and all.
#
# Its EXPECTATION IS DERIVED FROM THE FILE, not fixed at 0, and that is not a
# softening. Between releases `## Unreleased` legitimately has entries, and the
# check is supposed to refuse a release in that state -- so a live case pinned
# to 0 would have gone red on the very next commit that opened one, which is
# what it did. Exit 3 is reachable only after the version heading has already
# matched, so either expected value asserts the thing this case exists for.
# What neither tolerates is exit 2: the heading not being found at all.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
CHECK="$HERE/changelog-check.sh"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM

failures=0
cases=0

# run_case <name> <expected-exit> <version> [changelog]
run_case() {
  name=$1
  expected=$2
  shift 2
  cases=$((cases + 1))
  out=$("$CHECK" "$@" 2>&1) && code=0 || code=$?
  if [ "$code" -eq "$expected" ]; then
    printf '  ok    %-20s exit %d\n' "$name" "$code"
  else
    printf '  FAIL  %-20s exit %d, wanted %d\n' "$name" "$code" "$expected"
    echo "$out" | sed 's/^/          /'
    failures=$((failures + 1))
  fi
}

# ---- control: the shape a correct release has -------------------------------
cat > "$WORK/named.md" <<'MD'
# Changelog

## 0.2.0 — 2026-09-08

### Fixed

- Something that was broken.

## 0.1.9 — 2026-09-01

### Added

- Something that was not there.
MD

# ---- control: an Unreleased HEADING with nothing under it is fine -----------
cat > "$WORK/empty-unreleased.md" <<'MD'
# Changelog

## Unreleased

## 0.2.0 — 2026-09-08

### Fixed

- Something that was broken.
MD

# ---- mutant: the release nobody named ---------------------------------------
# This is 0.1.4 and 0.1.5, exactly as they shipped.
cat > "$WORK/unnamed.md" <<'MD'
# Changelog

## Unreleased

### Fixed

- Something that was broken, described but never given a version.

## 0.1.9 — 2026-09-01

### Added

- Something that was not there.
MD

# ---- mutant: named, but shipping Unreleased entries alongside ---------------
cat > "$WORK/leftovers.md" <<'MD'
# Changelog

## Unreleased

### Changed

- An entry that belongs under 0.2.0 and is not there.

## 0.2.0 — 2026-09-08

### Fixed

- Something that was broken.
MD

# ---- mutant: a heading that merely starts with the version ------------------
# 0.1.50 must not satisfy 0.1.5. A prefix match would go green here, and this
# is the likeliest shape for it to happen in: the predecessor still on top.
cat > "$WORK/prefix.md" <<'MD'
# Changelog

## 0.1.50 — 2026-09-08

### Fixed

- Something that was broken.
MD

run_case named             0 0.2.0  "$WORK/named.md"
run_case named-with-v      0 v0.2.0 "$WORK/named.md"
run_case empty-unreleased  0 0.2.0  "$WORK/empty-unreleased.md"
run_case unnamed           2 0.2.0  "$WORK/unnamed.md"
run_case leftovers         3 0.2.0  "$WORK/leftovers.md"
run_case prefix            2 0.1.5  "$WORK/prefix.md"
run_case absent-file       4 0.2.0  "$WORK/nope.md"
run_case no-version        4 ""     "$WORK/named.md"

# ---- the live control -------------------------------------------------------
# The newest version this repository has actually released, read from the file
# rather than written here, so the case cannot go stale into a green pass.
live=$(awk '/^## [0-9]/ { sub(/^## /, ""); sub(/ .*$/, ""); print; exit }' \
  "$REPO/CHANGELOG.md")
if [ -z "$live" ]; then
  echo "  FAIL  live                 CHANGELOG.md has no versioned section at all"
  failures=$((failures + 1))
  cases=$((cases + 1))
else
  # An open Unreleased section means the check must refuse (3); a clear one
  # means it must pass (0). Read the file to decide which, so this case tracks
  # the repository instead of drifting into a green pass.
  if awk '/^## Unreleased/ { u = 1; next } /^## / { u = 0 } u && $0 ~ /[^ \t]/ { found = 1 }
          END { exit !found }' "$REPO/CHANGELOG.md"; then
    run_case "live ($live, unreleased open)" 3 "$live" "$REPO/CHANGELOG.md"
  else
    run_case "live ($live, unreleased clear)" 0 "$live" "$REPO/CHANGELOG.md"
  fi
fi

echo
if [ "$failures" -ne 0 ]; then
  echo "changelog-check selftest: $failures of $cases cases FAILED" >&2
  exit 1
fi
echo "changelog-check selftest: all $cases cases pass"
