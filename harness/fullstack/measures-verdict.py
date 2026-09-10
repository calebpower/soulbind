#!/usr/bin/env python3
"""Which identity kinds did core move for this gate?

    measures-verdict.py <events.json> <gate>

Its own file rather than a heredoc inside measures-check.sh, so that
measures-selftest.sh can drive it against fixtures. A verdict nobody has watched
reject anything is a verdict nobody knows the shape of -- and this one exists
because a whole tier once passed against a defect.

Copyright (c) 2026 Caleb L. Power

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""
import json
import sys

EXIT_ONLY_MEASURED = 2
EXIT_NOTHING_MOVED = 3
EXIT_NOT_MEASURED = 4


def main():
    payload = json.load(open(sys.argv[1], encoding="utf-8"))
    gate = sys.argv[2]
    events = payload.get("payload", payload).get("events", [])

    kinds = set()
    for event in events:
        if event.get("type") != "subject.requirements-met" or event.get("gate") != gate:
            continue
        ref = event.get("identityRef") or ""
        if ":" in ref:
            kinds.add(ref.split(":", 1)[0])

    print("[measures] requirements-met on %s named kinds: %s"
          % (gate, ", ".join(sorted(kinds)) if kinds else "(none)"))

    if not kinds:
        print("[measures] the measurement moved NOTHING. The rule is satisfied by any "
              "observation and one arrived, so core emitted no transition at all.",
              file=sys.stderr)
        return EXIT_NOTHING_MOVED

    if "game" not in kinds:
        print("[measures] no event for the identity that was actually measured, so this "
              "stage is not testing what it thinks it is.", file=sys.stderr)
        return EXIT_NOT_MEASURED

    others = kinds - {"game"}
    if not others:
        print("[measures] ONLY the measured identity moved. A measurement on one platform "
              "cannot move a role on another, which is the defect 0.2.1 fixed and the "
              "reason this stage exists. Expected an event for a sibling identity of the "
              "same subject; core named 'game' and nothing else.", file=sys.stderr)
        return EXIT_ONLY_MEASURED

    print("[measures] a game-side measurement moved the subject's %s identity too"
          % ", ".join(sorted(others)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
