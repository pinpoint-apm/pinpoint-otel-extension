"""The callee (B): a WSGI app run under opentelemetry-instrument. The WSGI middleware creates the
SERVER span; the application has nothing to do for Pinpoint and only echoes the tracestate it saw.

    opentelemetry-instrument <venv>/bin/python callee.py 18096
"""
import sys
from wsgiref.simple_server import make_server

from opentelemetry.instrumentation.wsgi import OpenTelemetryMiddleware


def app(environ, start_response):
    tracestate = environ.get("HTTP_TRACESTATE")
    print("CALLEE-SAW tracestate:", tracestate, flush=True)
    start_response("200 OK", [("Content-Type", "text/plain")])
    return [f"pong tracestate={tracestate}\n".encode()]


port = int(sys.argv[1])
with make_server("127.0.0.1", port, OpenTelemetryMiddleware(app)) as httpd:
    print("CALLEE-UP", port, flush=True)
    httpd.serve_forever()
