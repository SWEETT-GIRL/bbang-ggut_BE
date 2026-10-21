"""Disposable HTTP process for host-adapter transport tests; not a JVM attestation."""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import sys

plan = json.loads(Path(sys.argv[1]).read_text())
token = Path(sys.argv[2]).read_text().strip()
state = {"service_id": "isolated-service", **plan["runtime"], "launch_nonce": plan["launch_nonce"], "pid": os.getpid(),
         "healthy": True, "writes_enabled": False, "drained": False, "draining": False, "active_requests": 0}
memory_draining = False

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        if self.headers.get("X-Agent-Runtime-Token") != token:
            self.send_error(403); return
        admission_open = Path(plan["admission_path"]).read_text() == "OPEN\n"
        state.update(admission_open=admission_open, draining=memory_draining or not admission_open,
                     drained=memory_draining or not admission_open)
        raw = json.dumps(state).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(raw)
    def do_POST(self):
        global memory_draining
        if self.headers.get("X-Agent-Expected-Generation") != state["generation"]:
            self.send_error(409); return
        memory_draining = True
        self.do_GET()

HTTPServer(("127.0.0.1", int(sys.argv[3])), Handler).serve_forever()
