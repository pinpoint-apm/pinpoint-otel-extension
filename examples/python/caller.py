"""The caller (A): plain requests run under opentelemetry-instrument. The requests instrumentation
creates the CLIENT span and injects the headers; the pinpoint sampler adds the pp entry.

    opentelemetry-instrument <venv>/bin/python caller.py http://127.0.0.1:18096/order 10
"""
import sys
import time

import requests
from opentelemetry import trace

target, count = sys.argv[1], int(sys.argv[2])
print("SAMPLER", trace.get_tracer_provider().sampler.get_description(), flush=True)
ok = 0
for i in range(count):
    response = requests.get(target, timeout=5)
    if i == 0:
        print("CALLER-GOT", response.text.strip(), flush=True)
    ok += response.status_code == 200
    time.sleep(0.3)
print(f"CALLER-DONE ok={ok}/{count}", flush=True)
trace.get_tracer_provider().force_flush()
