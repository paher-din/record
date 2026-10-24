"""Loopback-only server for the synthetic F0-C3 review package."""

from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class ReviewHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        # Browser requests are inspected by the review runner, not terminal logs.
        return


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    handler = partial(ReviewHandler, directory=str(root))
    ThreadingHTTPServer(("127.0.0.1", 8766), handler).serve_forever()
