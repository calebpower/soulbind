#!/bin/sh
# The control-and-mutants check for holds-group.sh.
#
#     group-selftest.sh
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
# Same discipline as mutants.txt next door, and here for the same reason: what
# gets mutated is what the stage OBSERVES, never the stage itself.
#
# THE CONTROL IS THE POINT. The `groups` stage spent a session red while the
# connector worked perfectly and the group sat in the file the stage was
# reading, because the pattern it matched could never appear in a real
# LuckPerms file. A control taken from a real run would have caught that in a
# second, on a workstation, instead of in a twenty-five-minute session.
#
# fixtures/luckperms-user.json is that control: the actual file LuckPerms wrote
# in run 24, copied out of the evidence directory unedited.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
FIXTURES=$HERE/../fixtures
HOLDS=$HERE/../holds-group.sh
GROUP=soulbind-linked
WORK=${TMPDIR:-/tmp}/soulbind-group-selftest.$$
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

fail=0
say() { echo "[group-selftest] $*"; }

# The control. A check that fails on this asserts nothing, however many mutants
# it kills.
if sh "$HOLDS" "$FIXTURES/luckperms-user.json" "$GROUP"; then
    say "control: a real LuckPerms file holding the group reads as holding it"
else
    say "FAIL control: the real file LuckPerms wrote does not read as holding"
    say "      '$GROUP'. Every mutant below would 'die' and mean nothing."
    fail=1
fi

# Each mutant edits the OBSERVATION and must read as NOT holding the group.
mutant() {
    name=$1
    program=$2
    out=$WORK/$name.json
    python3 - "$FIXTURES/luckperms-user.json" "$out" "$program" <<'PY'
import json
import sys

source, dest, program = sys.argv[1], sys.argv[2], sys.argv[3]
with open(source) as handle:
    user = json.load(handle)

if program == "no-parents":
    user["parents"] = []
elif program == "group-removed":
    user["parents"] = [p for p in user["parents"] if p.get("group") != "soulbind-linked"]
elif program == "group-misspelled":
    for p in user["parents"]:
        if p.get("group") == "soulbind-linked":
            p["group"] = "soulbind-linkd"
elif program == "parents-dropped":
    user.pop("parents", None)
elif program == "group-key-renamed":
    for p in user["parents"]:
        if p.get("group") == "soulbind-linked":
            p["name"] = p.pop("group")
elif program == "empty-object":
    user = {}
else:
    raise SystemExit("unknown mutation " + program)

with open(dest, "w") as handle:
    json.dump(user, handle, indent=2)
PY
    if sh "$HOLDS" "$out" "$GROUP"; then
        say "SURVIVED: $name -- the check still reads this as holding the group"
        fail=1
    else
        say "killed:   $name"
    fi
}

mutant no-parents        no-parents
mutant group-removed     group-removed
mutant group-misspelled  group-misspelled
mutant parents-dropped   parents-dropped
mutant group-key-renamed group-key-renamed
mutant empty-object      empty-object

# A file that is not there at all, which is what every red run before 24 saw.
if sh "$HOLDS" "$WORK/absent.json" "$GROUP"; then
    say "SURVIVED: absent-file -- a missing file read as holding the group"
    fail=1
else
    say "killed:   absent-file"
fi

if [ "$fail" -ne 0 ]; then
    say "FAILED"
    exit 1
fi
say "OK -- control green, 7 mutants dead"
