#!/bin/sh
# Exports soulbind's audit log, completely, as JSON Lines.
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
#   audit-export.sh <core-url> <admin-credential> [after-sequence]
#
# Writes one JSON object per line to stdout, oldest first, and prints a summary
# to stderr. Needs an admin credential -- reading the whole log is
# config-management, like the other administrative operations.
#
# WHY THIS IS NOT A `soulbind` VERB: the command has three verbs and stays at
# three. Everything else an operator can do is an operation reachable through
# an admin credential under the same capability table, rather than a second
# management surface whose rules drift from the first. This speaks the
# protocol; it does not touch the database.
#
# WHY IT PAGES: audit.query is bounded on purpose -- an unbounded read from an
# authenticated endpoint is a way to exhaust the server's memory -- so reading
# an unbounded log means asking repeatedly. Core says whether more remain and
# where to resume; this loops until it says no.
#
# The third argument resumes: pass the sequence this printed last time and only
# what happened since comes back. That is what makes it usable as a nightly
# archive rather than a whole-log dump each night.
#
# JSON Lines rather than one array, because an interrupted export is then still
# a readable file of whole rows up to the interruption, and because the common
# next step is a line-oriented tool.
set -eu

if [ $# -lt 2 ]; then
    echo "usage: audit-export.sh <core-url> <admin-credential> [after-sequence]" >&2
    exit 2
fi

CORE=$1
CREDENTIAL=$2
AFTER=${3:-0}
PAGE=${SOULBIND_AUDIT_PAGE:-500}

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

cursor=$AFTER
# Seeded at the starting cursor, not below it, so a core whose FIRST page fails
# to advance is caught before a duplicate page is written into the archive.
previous=$AFTER
total=0
pages=0

while :; do
    # Every page goes through the one signing implementation. A tool that
    # signed for itself would be a fourth copy of the canonical string.
    page=$("$HERE/rpc.sh" "$CORE" "$CREDENTIAL" audit.query \
        "{\"limit\":$PAGE,\"afterSequence\":$cursor}")

    # Rows to stdout, control to fd 3, so a caller redirecting stdout to a file
    # gets rows and nothing else.
    next=$(printf '%s' "$page" | python3 -c '
import json, sys
page = json.load(sys.stdin)
out = sys.stdout
for entry in page["entries"]:
    out.write(json.dumps(entry, separators=(",", ":"), sort_keys=True))
    out.write("\n")
sys.stderr.write("%d %s %d\n" % (
    len(page["entries"]), "more" if page["more"] else "end", page["lastSequence"]))
' 2>&1 >&3)

    count=$(printf '%s' "$next" | cut -d' ' -f1)
    state=$(printf '%s' "$next" | cut -d' ' -f2)
    cursor=$(printf '%s' "$next" | cut -d' ' -f3)

    total=$((total + count))
    pages=$((pages + 1))

    [ "$state" = "more" ] || break

    # A core that reports more without advancing the cursor would spin here
    # forever, rewriting the same page into the operator's archive. Hanging is
    # a worse report than failing, and a file of repeated rows is worse than
    # both -- it looks like an export.
    #
    # The empty page and the non-advancing page are separate mutants because
    # they are separate bugs: guarding only the empty one let a page of
    # twenty-five rows with a frozen cursor loop forever.
    if [ "$cursor" -le "$previous" ]; then
        echo "core reported more rows but did not advance the cursor past" \
             "$previous (page of $count rows); the export is incomplete and" \
             "stops here rather than looping" >&2
        exit 1
    fi
    previous=$cursor
done 3>&1

echo "exported $total rows in $pages request(s); resume with after-sequence $cursor" >&2
