# Python: connecting an OpenTelemetry application to the Pinpoint server map

Two ways, depending on how the application gets its OpenTelemetry SDK:

| Your setup | Use | Section |
|---|---|---|
| **Auto-instrumentation** (`opentelemetry-instrument`) or the SDK's environment-based configuration | the `pinpoint-otel-sampler` package plus one environment variable, no code change | [1. With the package](#1-with-the-package) |
| `TracerProvider` configured **in code** | the package's sampler class, or a copy of it, wrapped around your sampler | [2. In code](#2-in-code) |

Both do the same thing: wrap the sampler the application already uses and add the Pinpoint
`pp` entry to the trace state of outgoing spans, so the Pinpoint collector that receives the
callee's spans can draw the **caller → callee** edge. The format and the rules are in
[contract.md](contract.md); this page only applies them.

Every code block on this page is copied from [`python/`](../python) and
[`examples/python`](../examples/python), which CI tests against the contract file, and the
commands were run against a Pinpoint collector and web.

## 1. With the package

### 1.1 Install and configure

The package is not published to a package index. Install it from this repository, or copy
[`python/src/pinpoint_otel_sampler/__init__.py`](../python/src/pinpoint_otel_sampler/__init__.py)
(one file) into your own package and declare the entry point in your `pyproject.toml` as
[`python/pyproject.toml`](../python/pyproject.toml) does.

```sh
pip install "git+https://github.com/pinpoint-apm/pinpoint-otel-extension#subdirectory=python"
export OTEL_TRACES_SAMPLER=pinpoint          # OTEL_TRACES_SAMPLER_ARG names the delegate, default parentbased_always_on
export OTEL_SERVICE_NAME=order-api
export OTEL_RESOURCE_ATTRIBUTES=pinpoint.applicationType=1010
opentelemetry-instrument <venv>/bin/python app.py
```

The package registers an `opentelemetry_traces_sampler` entry point named `pinpoint`. The SDK
reads `OTEL_TRACES_SAMPLER` in `opentelemetry-instrument` and in its environment-based
configuration alike, so this works for every auto-instrumented application and for applications
that call `opentelemetry.sdk._configuration` themselves. Pin a commit or tag in the git URL
(`...pinpoint-otel-extension@<ref>#subdirectory=python`) for reproducible installs.

### 1.2 The delegate sampler

In Python, `OTEL_TRACES_SAMPLER` *replaces* the sampler rather than wrapping it, so the package
builds the sampler you would have used and wraps that. `OTEL_TRACES_SAMPLER_ARG` names it:

| `OTEL_TRACES_SAMPLER_ARG` | Delegate |
|---|---|
| unset, or `parentbased_always_on` | `ParentBased(ALWAYS_ON)`, the SDK default |
| `always_on`, `always_off` | `ALWAYS_ON`, `ALWAYS_OFF` |
| `traceidratio:0.1` | `TraceIdRatioBased(0.1)` |
| `parentbased_always_off` | `ParentBased(ALWAYS_OFF)` |
| `parentbased_traceidratio:0.1` | `ParentBased(TraceIdRatioBased(0.1))` |

Any other value is an error, which the SDK logs as "Using default sampler" and falls back to
its default without the `pp` entry. Check the sampler description (1.4).

### 1.3 Configuration keys

The same keys the Java agent extension reads:

| Key | Fallback | Notes |
|---|---|---|
| `pinpoint.applicationName` in `OTEL_RESOURCE_ATTRIBUTES` | `OTEL_SERVICE_NAME`, then `service.name` in `OTEL_RESOURCE_ATTRIBUTES` | one of the three must resolve; usually `OTEL_SERVICE_NAME` alone |
| `pinpoint.serviceName` in `OTEL_RESOURCE_ATTRIBUTES` | none, explicit only | Pinpoint service grouping; registered services only |
| `pinpoint.applicationType` in `OTEL_RESOURCE_ATTRIBUTES` | none | numeric ServiceType code, optional |

If nothing resolves the delegate is returned unchanged and no `pp` entry is written.

`pinpoint.applicationName` is the name written to the outgoing trace state; the process's own
spans still carry the Resource `service.name`. If the two differ the server map shows the same
process as two nodes, so leave `pinpoint.applicationName` out unless you have a reason.

### 1.4 Checking

As printed by [`examples/python`](../examples/python) under `opentelemetry-instrument`:

```
SAMPLER PinpointTraceStateSampler{delegate=ParentBased{root:AlwaysOnSampler,remoteParentSampled:AlwaysOnSampler,remoteParentNotSampled:AlwaysOffSampler,localParentSampled:AlwaysOnSampler,localParentNotSampled:AlwaysOffSampler}, pp=app:order-api;type:1010}
CALLER-GOT pong tracestate=pp=app:order-api;type:1010
CALLEE-SAW tracestate: pp=app:order-api;type:1010
```

1. `trace.get_tracer_provider().sampler.get_description()` starts with `PinpointTraceStateSampler{`.
2. The callee receives `tracestate: pp=app:order-api;type:1010`.
3. Pinpoint web: the edge `order-api → <callee>` on the callee's server map, both applications
   in one call tree.

### 1.5 Pitfalls

- Give `opentelemetry-instrument` the interpreter of the same virtual environment
  (`opentelemetry-instrument <venv>/bin/python app.py`). A bare `python` is resolved through
  `PATH` and may be another interpreter without OpenTelemetry installed:
  `Error in sitecustomize ... No module named 'opentelemetry'`.
- The SDK sets `service.instance.id` by default, so the Pinpoint collector gets a per-instance id
  without configuration (agent name = that UUID). Set `pinpoint.agentId` in
  `OTEL_RESOURCE_ATTRIBUTES` for a readable agent name.
- An unregistered `pinpoint.serviceName` gets the sender's own spans rejected
  (`service_not_found`), and the collector caches the miss for 10 minutes by default.
- The Pinpoint collector only takes traces: `OTEL_METRICS_EXPORTER=none`, `OTEL_LOGS_EXPORTER=none`,
  or point them elsewhere.

## 2. In code

Install the package from the repository as in 1.1 and wrap your sampler, or copy the class into
your project; it depends on `opentelemetry-sdk` only. To implement it in another language, see
[implementing.md](implementing.md) and the [conformance checklist](conformance.md).

### 2.1 The sampler class

From [`python/src/pinpoint_otel_sampler/__init__.py`](../python/src/pinpoint_otel_sampler/__init__.py):

```python
KEY = "pp"


def build_value(svc: Optional[str], app: Optional[str], type_: Optional[int]) -> Optional[str]:
    """Order svc, app, type; absent parts are skipped; ``None`` when both svc and app are absent."""
    parts = []
    if svc:
        parts.append(f"svc:{svc}")
    if app:
        parts.append(f"app:{app}")
    if not parts:
        return None
    if type_ is not None:
        parts.append(f"type:{int(type_)}")
    return ";".join(parts)


def _parent_trace_state(parent_context: Optional[Context]) -> TraceState:
    span_context = get_current_span(parent_context).get_span_context()
    if span_context is None or not span_context.is_valid or span_context.trace_state is None:
        return TraceState()
    return span_context.trace_state


class PinpointTraceStateSampler(Sampler):
    def __init__(
        self,
        delegate: Sampler,
        service_name: Optional[str],
        application_name: Optional[str],
        application_type: Optional[int] = None,
    ) -> None:
        if delegate is None:
            raise ValueError("delegate is required")
        value = build_value(service_name, application_name, application_type)
        if value is None:
            raise ValueError("service_name or application_name must be provided")
        self._delegate = delegate
        self._pp_value = value

    def should_sample(
        self,
        parent_context: Optional[Context],
        trace_id: int,
        name: str,
        kind: Optional[SpanKind] = None,
        attributes: Attributes = None,
        links: Optional[Sequence[Link]] = None,
        trace_state: Optional[TraceState] = None,
    ) -> SamplingResult:
        result = self._delegate.should_sample(parent_context, trace_id, name, kind, attributes, links, trace_state)
        if kind in (SpanKind.SERVER, SpanKind.CONSUMER):
            return result  # receiving side: keep the incoming trace state
        # The tracer does not pass trace_state; layer on the delegate's result, else the parent's
        current = result.trace_state
        if current is None:
            current = _parent_trace_state(parent_context)
        if current.get(KEY) == self._pp_value:
            return result
        updated = current.update(KEY, self._pp_value)  # upsert; add() refuses an existing key
        return SamplingResult(result.decision, result.attributes, updated)

    def get_description(self) -> str:
        return f"PinpointTraceStateSampler{{delegate={self._delegate.get_description()}, pp={self._pp_value}}}"
```

Two details of the Python SDK the class works around:

- The tracer calls `should_sample` without `trace_state`, and `SamplingResult.trace_state` is
  what becomes the new span's trace state. The built-in samplers put the parent's trace state in
  their result; when a delegate returns `None` the class reads the parent span context itself.
- `TraceState` is immutable, `add()` refuses a key that already exists, and `update()` upserts.
  Use `update()`. A value with `,` or `=` is rejected with a warning and the trace state is
  returned unchanged, so the entry silently disappears.

### 2.2 Wiring it in

```python
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.sampling import ALWAYS_ON, ParentBased
from pinpoint_otel_sampler import PinpointTraceStateSampler

sampler = PinpointTraceStateSampler(ParentBased(ALWAYS_ON), None, "order-api", 1010)  # outermost
trace.set_tracer_provider(TracerProvider(sampler=sampler))
```

The default propagator is `tracecontext,baggage`, so nothing to set; if you changed
`OTEL_PROPAGATORS`, keep `tracecontext` in it. The `application_name` must be the same value as
the Resource `service.name`.

### 2.3 Rules in short

- **`application_name` equals the Resource `service.name`.** Otherwise the same process shows
  up as two nodes.
- **The sampler is the outermost one.** `ParentBased(PinpointTraceStateSampler(...))` puts it in
  the root sampler slot, where it is never consulted for spans that have a parent, so CLIENT
  spans lose the entry (pinned by the test
  `test_regression_wrapped_inside_parentbased_loses_pp_on_child_client_spans`).
- **`tracecontext` is among the propagators.** B3 only carries no `tracestate`.
- **`service_name` only for a registered service.**

### 2.4 Testing in your project

Export to an `InMemorySpanExporter` and assert `span.context.trace_state.get("pp")` per span
kind, as [`python/tests/test_sampler.py`](../python/tests/test_sampler.py) does:

```python
def test_client_producer_internal_carry_pp_server_consumer_do_not():
    tp, exporter = provider(PinpointTraceStateSampler(ParentBased(ALWAYS_ON), None, "order-api", 1010))
    tracer = tp.get_tracer("t")
    for name, kind in [("root-internal", SpanKind.INTERNAL), ("client", SpanKind.CLIENT), ("producer", SpanKind.PRODUCER),
                       ("server", SpanKind.SERVER), ("consumer", SpanKind.CONSUMER)]:
        tracer.start_span(name, kind=kind).end()
    assert pp_by_name(exporter) == {
        "root-internal": "app:order-api;type:1010",
        "client": "app:order-api;type:1010",
        "producer": "app:order-api;type:1010",
        "server": None,
        "consumer": None,
    }
```

### 2.5 Common mistakes

| Symptom | Cause |
|---|---|
| No edge, CLIENT spans carry no `pp` | the sampler is wrapped inside `ParentBased` |
| `OTEL_TRACES_SAMPLER=pinpoint` set, description shows the default sampler | the package is not installed in the interpreter that runs, or `OTEL_TRACES_SAMPLER_ARG` names an unknown delegate ("Using default sampler" in the log) |
| `Error in sitecustomize ... No module named 'opentelemetry'` | `opentelemetry-instrument` resolved another `python` through `PATH`; give it the venv's interpreter |
| No `tracestate` header on the request | `OTEL_PROPAGATORS` without `tracecontext`, or an HTTP client that is not instrumented |
| The same application shows as two nodes | `pinpoint.applicationName` differs from the Resource `service.name` |
| The sender's own spans are not stored | unregistered `pinpoint.serviceName`, `service_not_found`; up to 10 minutes after registering |
| The callee shows a self edge | `pp` written on SERVER or CONSUMER spans (rule 1 of the contract) |
| `pp` silently disappears | a `,` or `=` in a name (invalid `tracestate` syntax) |
