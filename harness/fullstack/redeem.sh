#!/bin/sh
# Redeems a code as some platform, through the real protocol.
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
# Stands in for a connector this stack does not run. It speaks the real wire
# format -- what it is not is a backdoor: there is no path here that writes
# state without going through core's own redeem.
#
# The signing lives in tools/rpc.sh. It used to live here too, a full copy of
# the canonical string down to the newlines, which made three copies in the
# harness of the one thing the golden vectors exist to keep identical -- and a
# copy that drifts is a harness that passes against a wire format core does not
# speak. Only fuzz-live.sh still signs for itself, and it has to: it sends
# deliberately malformed bodies that rpc.sh refuses before they reach the wire.
#
#   redeem.sh <core-url> <credential> <code> <kind> <platform-id>
set -eu

CORE=$1
CREDENTIAL=$2
CODE=$3
KIND=$4
PLATFORM_ID=$5

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

PAYLOAD=$(python3 -c '
import json, sys
print(json.dumps({
    "code": sys.argv[1],
    "platformKind": sys.argv[2],
    "platformId": sys.argv[3],
    "display": sys.argv[3],
}, separators=(",", ":")))
' "$CODE" "$KIND" "$PLATFORM_ID")

RESULT=$("$HERE/../../tools/rpc.sh" "$CORE" "$CREDENTIAL" code.redeem "$PAYLOAD")

# The count is read back from the response rather than assumed, because the
# stack scripts print this line as evidence that the redeem did something.
COUNT=$(printf '%s' "$RESULT" | python3 -c '
import json, sys
print(len(json.load(sys.stdin)["identities"]))
')

echo "redeemed as $KIND:$PLATFORM_ID, $COUNT identities"
