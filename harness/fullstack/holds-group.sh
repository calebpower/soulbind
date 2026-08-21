#!/bin/sh
# Does LuckPerms' own storage record this player in this group?
#
#     holds-group.sh <luckperms-user-json> <group>
#
# Exits 0 if it does, 1 if it does not, 2 if the file could not be read.
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
# ONE implementation, so the stage and its self-test cannot disagree about what
# "holds the group" means.
#
# This exists because group-check.sh grepped for `group.soulbind-linked` -- the
# PERMISSION NODE form, which is how an inherited group appears in a permissions
# listing. LuckPerms' JSON storage does not write that. It writes a `parents`
# array of `{"group": "<name>"}` objects. So the stage could never have matched
# a real file, and it ran red through a session in which the connector had
# worked perfectly and the group was sitting in the file it was reading. A check
# that cannot pass is not a strict check, it is a broken one. DECISIONS 10.27.
#
# Parsed rather than grepped. A regex over serialised JSON is a bet on
# whitespace and key order that this repository has no reason to make when a
# parser is already a dependency of five other stages here.
set -eu

FILE=$1
GROUP=$2

python3 - "$FILE" "$GROUP" <<'PY'
import json
import sys

path, group = sys.argv[1], sys.argv[2]

try:
    with open(path) as handle:
        user = json.load(handle)
except (OSError, ValueError):
    sys.exit(2)

if not isinstance(user, dict):
    sys.exit(2)

# Inheritance lives in `parents`; the primary group is a separate scalar key and
# counts too -- a player whose PRIMARY group is this one certainly holds it, and
# a check that missed that would be wrong in the direction nobody would notice.
held = set()
for parent in user.get("parents") or []:
    if isinstance(parent, dict) and parent.get("group"):
        held.add(parent["group"])
if user.get("primaryGroup"):
    held.add(user["primaryGroup"])

sys.exit(0 if group in held else 1)
PY
