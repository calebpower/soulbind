"""Serves a recorded Plan response with Plan's own wire behaviour.

Plan's JettyResponseSender gzips every application/json response and sets
Content-Encoding: gzip on mime type alone, never looking at Accept-Encoding.
A replay server that does not do that lets `plan-check.sh` pass here and fail
against the real thing -- which is how the first run of the Plan stage came to
report "no soulbind extension" about a page that had rendered correctly.

    replay.py <player-json> <server-json> <port> [raw]

`raw` disables the gzip, so the check's `--compressed` can be shown to work in
both directions rather than only the one it was written for.
"""
import gzip
import http.server
import socketserver
import sys

PLAYER, SERVER, PORT = sys.argv[1], sys.argv[2], int(sys.argv[3])
RAW = len(sys.argv) > 4 and sys.argv[4] == "raw"


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        path = self.path.split("?")[0]
        source = {"/v1/player": PLAYER, "/v1/extensionData": SERVER}.get(path)
        if source is None:
            self.send_response(404)
            self.end_headers()
            return
        with open(source, "rb") as handle:
            body = handle.read()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        if not RAW:
            body = gzip.compress(body)
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("127.0.0.1", PORT), Handler) as httpd:
    httpd.serve_forever()
