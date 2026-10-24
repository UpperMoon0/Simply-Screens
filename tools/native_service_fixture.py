"""Loopback-only Authlib 2.1.28 service fixture for isolated development CI.

This models an external service, not a real account. Minecraft and Authlib remain
unmodified; the same native permission check must reject the denial control.
"""
import json
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = 'SimplyScreensCiFixture'
TARGETS = ('fabric-1.16.5', 'forge-1.16.5')


@contextmanager
def service_fixture(allowed):
    if type(allowed) is not bool:
        raise ValueError('Fixture permission must be a boolean')
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Never retain Authorization headers.

        def do_GET(self):
            if self.path in ('/privileges', '/privacy/blocklist'):
                if self.headers.get('Authorization') != 'Bearer ' + TOKEN:
                    self.send_error(401)
                    return
                requests.append(self.path)
                payload = ({'privileges': {
                    'onlineChat': {'enabled': allowed},
                    'multiplayerServer': {'enabled': allowed},
                    'multiplayerRealms': {'enabled': False}}}
                    if self.path == '/privileges' else {'blockedProfiles': []})
                data = json.dumps(payload).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            else:
                self.send_error(404)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield 'http://127.0.0.1:' + str(server.server_port), requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        if thread.is_alive():
            raise RuntimeError('Native service fixture did not stop')
