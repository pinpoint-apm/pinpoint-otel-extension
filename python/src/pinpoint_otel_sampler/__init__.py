"""Pinpoint ``pp`` tracestate sampler for the OpenTelemetry Python SDK.

Wraps the sampler the application already uses and adds
``pp=svc:<serviceName>;app:<applicationName>;type:<applicationType>`` to the trace state of
the spans this process originates (CLIENT, PRODUCER, INTERNAL). SERVER and CONSUMER spans are
left untouched: their trace state is what arrived with the request and names the caller. The
delegate's sampling decision is kept as is.

Depends on the OpenTelemetry SDK only. See docs/contract.md in the repository for the wire
format and the rules.
"""

from __future__ import annotations

import os
from typing import Dict, Optional, Sequence

from opentelemetry.context import Context
from opentelemetry.sdk.trace.sampling import (
    ALWAYS_OFF,
    ALWAYS_ON,
    ParentBased,
    Sampler,
    SamplingResult,
    TraceIdRatioBased,
)
from opentelemetry.trace import Link, SpanKind, get_current_span
from opentelemetry.trace.span import TraceState
from opentelemetry.util.types import Attributes

__all__ = ["KEY", "PinpointTraceStateSampler", "build_value", "sampler_factory"]

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
    """Sampler wrapper that writes the Pinpoint ``pp`` entry.

    :param delegate: the sampler you use today, e.g. ``ParentBased(ALWAYS_ON)``
    :param service_name: Pinpoint service name; only a service registered on the Pinpoint side.
        ``None`` to stay on the default service
    :param application_name: Pinpoint application name; the same value as the Resource ``service.name``
    :param application_type: Pinpoint ServiceType code (optional); ``None`` for the collector default
    """

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


# ---- OTEL_TRACES_SAMPLER entry point -------------------------------------------------------

_DELEGATES = {
    "always_on": lambda _: ALWAYS_ON,
    "always_off": lambda _: ALWAYS_OFF,
    "traceidratio": lambda r: TraceIdRatioBased(r),
    "parentbased_always_on": lambda _: ParentBased(ALWAYS_ON),
    "parentbased_always_off": lambda _: ParentBased(ALWAYS_OFF),
    "parentbased_traceidratio": lambda r: ParentBased(TraceIdRatioBased(r)),
}


def _resource_attributes() -> Dict[str, str]:
    attrs: Dict[str, str] = {}
    for pair in os.environ.get("OTEL_RESOURCE_ATTRIBUTES", "").split(","):
        if "=" in pair:
            key, value = pair.split("=", 1)
            attrs[key.strip()] = value.strip()
    return attrs


def sampler_factory(arg: Optional[str]) -> Sampler:
    """Entry point for ``OTEL_TRACES_SAMPLER=pinpoint``.

    ``OTEL_TRACES_SAMPLER_ARG`` names the delegate: ``parentbased_always_on`` (the default, which
    is also the SDK's default sampler), ``always_on``, ``always_off``, ``traceidratio:<ratio>``,
    ``parentbased_always_off`` or ``parentbased_traceidratio:<ratio>``.

    The identifiers come from the keys the Java extension reads as well: the application name
    from ``pinpoint.applicationName`` in ``OTEL_RESOURCE_ATTRIBUTES``, then ``OTEL_SERVICE_NAME``,
    then ``service.name``; ``pinpoint.serviceName`` and ``pinpoint.applicationType`` from
    ``OTEL_RESOURCE_ATTRIBUTES``. When nothing resolves the delegate is returned unchanged.
    """
    name, _, ratio = (arg or "").partition(":")
    name = name.strip().lower() or "parentbased_always_on"
    if name not in _DELEGATES:
        raise ValueError(f"unknown delegate sampler {name!r}; one of {sorted(_DELEGATES)}")
    delegate = _DELEGATES[name](float(ratio) if ratio else 1.0)

    attrs = _resource_attributes()
    app = attrs.get("pinpoint.applicationName") or os.environ.get("OTEL_SERVICE_NAME") or attrs.get("service.name")
    svc = attrs.get("pinpoint.serviceName")
    type_raw = attrs.get("pinpoint.applicationType")
    type_ = int(type_raw) if type_raw else None
    if not app and not svc:
        return delegate
    return PinpointTraceStateSampler(delegate, svc, app, type_)
