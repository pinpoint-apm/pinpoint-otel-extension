import io
import os
from pathlib import Path

import pytest
from opentelemetry.context import Context
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.sdk.trace.sampling import ALWAYS_OFF, ALWAYS_ON, Decision, ParentBased
from opentelemetry.trace import NonRecordingSpan, SpanContext, SpanKind, TraceFlags, set_span_in_context
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from opentelemetry.trace.span import TraceState

from pinpoint_otel_sampler import PinpointTraceStateSampler, build_value, sampler_factory

# The wire contract file of this repository, shared with the Java extension and the Pinpoint collector.
CONTRACT = Path(__file__).resolve().parents[2] / "src" / "test" / "resources" / "pp-tracestate-contract.txt"


def provider(sampler):
    exporter = InMemorySpanExporter()
    tp = TracerProvider(sampler=sampler)
    tp.add_span_processor(SimpleSpanProcessor(exporter))
    return tp, exporter


def pp_by_name(exporter):
    return {s.name: s.context.trace_state.get("pp") for s in exporter.get_finished_spans()}


def contract_rows():
    rows = []
    with io.open(CONTRACT, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            cols = line.split("|")
            assert len(cols) == 4, line
            rows.append((
                None if cols[0] == "-" else cols[0],
                None if cols[1] == "-" else cols[1],
                None if cols[2] == "-" else int(cols[2]),
                None if cols[3] == "<null>" else cols[3],
            ))
    assert len(rows) >= 10
    return rows


@pytest.mark.parametrize("svc,app,type_,expected", contract_rows())
def test_build_value_follows_the_contract_file(svc, app, type_, expected):
    assert build_value(svc, app, type_) == expected


def test_rejects_construction_without_svc_and_app():
    with pytest.raises(ValueError):
        PinpointTraceStateSampler(ALWAYS_ON, None, None, 1010)


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


def test_server_keeps_the_callers_pp_and_its_client_child_writes_our_own():
    tp, exporter = provider(PinpointTraceStateSampler(ParentBased(ALWAYS_ON), None, "order-api", 1010))
    tracer = tp.get_tracer("t")
    carrier = {"traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "tracestate": "pp=app:front;type:1010"}
    parent = TraceContextTextMapPropagator().extract(carrier)
    with tracer.start_as_current_span("server", context=parent, kind=SpanKind.SERVER):
        tracer.start_span("client", kind=SpanKind.CLIENT).end()
    got = pp_by_name(exporter)
    assert got["server"] == "app:front;type:1010"
    assert got["client"] == "app:order-api;type:1010"


def test_outgoing_headers_carry_pp():
    tp, _ = provider(PinpointTraceStateSampler(ParentBased(ALWAYS_ON), "order-team", "order-api", None))
    tracer = tp.get_tracer("t")
    headers = {}
    with tracer.start_as_current_span("client", kind=SpanKind.CLIENT):
        TraceContextTextMapPropagator().inject(headers)
    assert "traceparent" in headers
    assert headers["tracestate"] == "pp=svc:order-team;app:order-api"


def test_keeps_the_delegates_decision_and_other_entries():
    sampler = PinpointTraceStateSampler(ALWAYS_OFF, None, "order-api", None)
    parent_sc = SpanContext(0x0AF7651916CD43DD8448EB211C80319C, 0xB7AD6B7169203331, is_remote=True,
                            trace_flags=TraceFlags(TraceFlags.SAMPLED),
                            trace_state=TraceState([("ot", "th:8"), ("pp", "app:stale")]))
    ctx = set_span_in_context(NonRecordingSpan(parent_sc), Context())
    result = sampler.should_sample(ctx, parent_sc.trace_id, "client", SpanKind.CLIENT)
    assert result.decision == Decision.DROP
    assert result.trace_state.get("ot") == "th:8"         # other vendors' entries survive
    assert result.trace_state.get("pp") == "app:order-api"  # the same key is replaced


def test_regression_wrapped_inside_parentbased_loses_pp_on_child_client_spans():
    wrong = ParentBased(PinpointTraceStateSampler(ALWAYS_ON, None, "order-api", 1010))
    tp, exporter = provider(wrong)
    tracer = tp.get_tracer("t")
    with tracer.start_as_current_span("server", kind=SpanKind.SERVER):
        tracer.start_span("client", kind=SpanKind.CLIENT).end()
    assert pp_by_name(exporter)["client"] is None


def test_invalid_value_is_dropped_silently():
    tp, exporter = provider(PinpointTraceStateSampler(ALWAYS_ON, None, "bad,name", None))
    tp.get_tracer("t").start_span("client", kind=SpanKind.CLIENT).end()
    assert pp_by_name(exporter)["client"] is None


class TestFactory:
    @pytest.fixture(autouse=True)
    def clean_env(self, monkeypatch):
        for key in ("OTEL_SERVICE_NAME", "OTEL_RESOURCE_ATTRIBUTES"):
            monkeypatch.delenv(key, raising=False)

    def test_reads_env_and_wraps_the_named_delegate(self, monkeypatch):
        monkeypatch.setenv("OTEL_SERVICE_NAME", "order-api")
        monkeypatch.setenv("OTEL_RESOURCE_ATTRIBUTES", "pinpoint.applicationType=1010,deployment.environment=dev")
        sampler = sampler_factory("parentbased_traceidratio:0.5")
        assert sampler.get_description() == (
            "PinpointTraceStateSampler{delegate=ParentBased{root:TraceIdRatioBased{0.5},remoteParentSampled:AlwaysOnSampler,"
            "remoteParentNotSampled:AlwaysOffSampler,localParentSampled:AlwaysOnSampler,localParentNotSampled:AlwaysOffSampler}, "
            "pp=app:order-api;type:1010}")

    def test_pinpoint_keys_in_resource_attributes_win(self, monkeypatch):
        monkeypatch.setenv("OTEL_SERVICE_NAME", "order-api")
        monkeypatch.setenv("OTEL_RESOURCE_ATTRIBUTES", "pinpoint.applicationName=order-api-canary,pinpoint.serviceName=order-team")
        assert sampler_factory(None).get_description().endswith("pp=svc:order-team;app:order-api-canary}")

    def test_default_delegate_is_parentbased_always_on(self, monkeypatch):
        monkeypatch.setenv("OTEL_SERVICE_NAME", "order-api")
        assert sampler_factory(None).get_description().startswith("PinpointTraceStateSampler{delegate=ParentBased{root:AlwaysOnSampler")

    def test_noop_when_nothing_resolves(self):
        assert "PinpointTraceStateSampler" not in sampler_factory(None).get_description()

    def test_unknown_delegate_is_an_error(self, monkeypatch):
        monkeypatch.setenv("OTEL_SERVICE_NAME", "order-api")
        with pytest.raises(ValueError):
            sampler_factory("jaeger_remote")
