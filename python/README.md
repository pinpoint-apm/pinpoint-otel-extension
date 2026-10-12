# pinpoint-otel-sampler

An OpenTelemetry **Python** SDK sampler that adds the Pinpoint `pp=...` entry to the W3C
`tracestate` header, so a Pinpoint OTLP trace collector that receives spans from a downstream
service can draw the caller → callee edge on the server map. The Python counterpart of the Java
agent extension in the repository root; the wire format and rules are in
[`docs/contract.md`](../docs/contract.md), the guide in [`docs/python.md`](../docs/python.md).

Depends on `opentelemetry-sdk` only. It is a **reference implementation** and is not published
to a package index: install it from this repository or copy the single source file into your
project. Its tests are the executable form of [`docs/conformance.md`](../docs/conformance.md).

## With auto-instrumentation: no code change

```sh
pip install "git+https://github.com/pinpoint-apm/pinpoint-otel-extension#subdirectory=python"
export OTEL_TRACES_SAMPLER=pinpoint          # OTEL_TRACES_SAMPLER_ARG names the delegate, default parentbased_always_on
export OTEL_SERVICE_NAME=order-api
export OTEL_RESOURCE_ATTRIBUTES=pinpoint.applicationType=1010
opentelemetry-instrument <venv>/bin/python app.py
```

## In code

```python
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.sampling import ALWAYS_ON, ParentBased
from pinpoint_otel_sampler import PinpointTraceStateSampler

provider = TracerProvider(sampler=PinpointTraceStateSampler(ParentBased(ALWAYS_ON), None, "order-api", 1010))
```

## Build and test

```sh
cd python
python -m venv .venv && . .venv/bin/activate
pip install -e ".[test]"
python -m pytest
python -m build && twine check dist/*
```

The tests assert the sampler against the repository's contract file,
`../src/test/resources/pp-tracestate-contract.txt`.

Licensed under the Apache License 2.0, see the repository [LICENSE](../LICENSE).
