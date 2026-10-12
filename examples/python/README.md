# Python example: two auto-instrumented processes

Both processes run under `opentelemetry-instrument` with `OTEL_TRACES_SAMPLER=pinpoint`. Neither
script mentions Pinpoint; the package's entry point does the work.

```sh
python -m venv .venv && . .venv/bin/activate
pip install ../../python opentelemetry-distro opentelemetry-exporter-otlp-proto-grpc \
            opentelemetry-instrumentation-requests opentelemetry-instrumentation-wsgi requests

export OTEL_TRACES_SAMPLER=pinpoint
export OTEL_TRACES_EXPORTER=otlp OTEL_EXPORTER_OTLP_PROTOCOL=grpc OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:9998
export OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none

# B
OTEL_SERVICE_NAME=inventory-api OTEL_RESOURCE_ATTRIBUTES=pinpoint.applicationType=1010 \
  opentelemetry-instrument .venv/bin/python callee.py 18096 &
# A
OTEL_SERVICE_NAME=order-api OTEL_RESOURCE_ATTRIBUTES=pinpoint.applicationType=1010 \
  opentelemetry-instrument .venv/bin/python caller.py http://127.0.0.1:18096/order 10
```

Give `opentelemetry-instrument` the interpreter of the same virtual environment, as above. A bare
`python` is resolved through `PATH` and may be another interpreter without OpenTelemetry
installed (`Error in sitecustomize ... No module named 'opentelemetry'`).

Expected output:

```
SAMPLER PinpointTraceStateSampler{delegate=ParentBased{...}, pp=app:order-api;type:1010}
CALLER-GOT pong tracestate=pp=app:order-api;type:1010
CALLER-DONE ok=10/10
CALLEE-SAW tracestate: pp=app:order-api;type:1010
```

On the Pinpoint server map of `inventory-api` the edge `order-api → inventory-api` appears.
