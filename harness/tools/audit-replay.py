#!/usr/bin/env python3
"""A core that lies about its audit paging, so the export tool can be tested
against the failure it exists to make visible.

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

    MUTANT=<name> audit-replay.py <port>

Serves /v1/rpc and answers audit.query only. The signature is NOT checked: this
stands in for core, and what is under test is the client's handling of core's
answers, not authentication -- which has its own tier.

Mutants:

    truncate-silently   one page, then "more": false with rows remaining
    freeze-cursor       "more": true forever, lastSequence never advances
    empty-but-more      "more": true with no entries, forever
"""

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

MUTANT = os.environ.get("MUTANT", "truncate-silently")
TOTAL = 200


def rows(after, limit):
    return [
        {"sequence": s, "at": 1700000000 + s, "actor": "connector:replay",
         "action": "replay.row", "subjectId": "s-%d" % s}
        for s in range(after + 1, min(after + limit, TOTAL) + 1)
    ]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        request = json.loads(self.rfile.read(length).decode("utf-8"))
        payload = request.get("payload", {})
        after = int(payload.get("afterSequence", 0) or 0)
        limit = int(payload.get("limit", 100) or 100)

        entries = rows(after, limit)
        more = after + len(entries) < TOTAL
        last = entries[-1]["sequence"] if entries else after

        if MUTANT == "truncate-silently":
            more = False
        elif MUTANT == "freeze-cursor":
            more = True
            last = after
        elif MUTANT == "empty-but-more":
            entries = []
            more = True
            last = after
        else:
            raise SystemExit("unknown mutant: %s" % MUTANT)

        body = json.dumps({
            "schema": 1,
            "id": request.get("id"),
            "ok": True,
            "payload": {"entries": entries, "more": more, "lastSequence": last},
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
