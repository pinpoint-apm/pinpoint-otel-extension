/*
 * Copyright 2026 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.otel.extension;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

import java.util.List;

/**
 * Wraps an existing {@link Sampler} and adds a Pinpoint {@code pp=...} entry to the
 * tracestate of the spans this process originates. The W3CTraceContextPropagator then
 * automatically emits it on outgoing carrier headers, so a downstream Pinpoint OTLP
 * collector can recover the upstream service / application / type.
 *
 * <p>The entry names <em>this</em> process, so it belongs on the spans that leave it
 * (CLIENT, PRODUCER, and the INTERNAL / root spans in between), never on a SERVER or
 * CONSUMER span. Those are the spans the collector reads the parent application from:
 * their trace state must be what arrived with the request or message — the caller's
 * {@code pp} entry, or nothing — and never this process's identity, or the collector
 * records the receiver as its own parent and draws a self edge. The rule is by kind
 * alone, not by whether the parent is remote: messaging instrumentations create
 * CONSUMER spans under a local parent (the Kafka "receive" span with receive
 * telemetry on), and an entry written there is inherited by the "process" span the
 * collector stores. Skipping SERVER / CONSUMER spans loses nothing downstream,
 * because every outgoing call gets its own CLIENT / PRODUCER span, and that span
 * writes the entry the propagator then injects. The same rule the native Pinpoint
 * agent follows: the parent identity travels on the request and is never mixed with
 * the receiver's own.</p>
 *
 * <p>The delegate's {@link SamplingDecision} is preserved unchanged — this sampler
 * only enriches the trace state. Even on a {@code DROP} decision, the tracestate is
 * still updated so that if a subsequent hop re-samples, the Pinpoint context is
 * still carried.</p>
 */
public final class PinpointTraceStateSampler implements Sampler {

    private final Sampler delegate;
    private final String ppValue;
    private final String description;

    public PinpointTraceStateSampler(Sampler delegate, String svc, String app, Integer type) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        String value = PinpointTraceStateSpec.buildValue(svc, app, type);
        if (value == null) {
            throw new IllegalArgumentException("svc or app must be provided");
        }
        this.delegate = delegate;
        this.ppValue = value;
        this.description = "PinpointTraceStateSampler{delegate=" + delegate.getDescription()
                + ", pp=" + value + "}";
    }

    @Override
    public SamplingResult shouldSample(Context parentContext,
                                       String traceId,
                                       String name,
                                       SpanKind spanKind,
                                       Attributes attributes,
                                       List<LinkData> parentLinks) {
        final SamplingResult delegateResult = delegate.shouldSample(
                parentContext, traceId, name, spanKind, attributes, parentLinks);
        if (isEntrySpan(spanKind)) {
            // Receiving side of a call or message: leave the incoming trace state untouched.
            return delegateResult;
        }
        final TraceState parentTs = Span.fromContext(parentContext).getSpanContext().getTraceState();
        // Layer the pp entry on the delegate's updated trace state, not the parent's — a
        // delegate that writes its own entries (e.g. a consistent-probability sampler's
        // ot=th:... threshold) must not have them silently discarded here.
        final TraceState delegateTs = delegateResult.getUpdatedTraceState(parentTs);
        final TraceState updated = withPinpointEntry(delegateTs);

        // If the delegate's trace state already carries the identical pp entry, avoid
        // allocating a wrapper SamplingResult.
        if (updated.equals(delegateTs)) {
            return delegateResult;
        }

        return new SamplingResult() {
            @Override
            public SamplingDecision getDecision() {
                return delegateResult.getDecision();
            }

            @Override
            public Attributes getAttributes() {
                return delegateResult.getAttributes();
            }

            @Override
            public TraceState getUpdatedTraceState(TraceState parentTraceState) {
                return updated;
            }
        };
    }

    /**
     * A SERVER / CONSUMER span: the receiving side of a call or message, whose trace
     * state describes the caller (if anything) and never this process.
     */
    static boolean isEntrySpan(SpanKind spanKind) {
        return spanKind == SpanKind.SERVER || spanKind == SpanKind.CONSUMER;
    }

    private TraceState withPinpointEntry(TraceState current) {
        // TraceStateBuilder.put silently drops entries that violate the W3C key/value
        // syntax, so malformed inputs cannot corrupt the carrier.
        return current.toBuilder().put(PinpointTraceStateSpec.KEY, ppValue).build();
    }

    @Override
    public String getDescription() {
        return description;
    }
}
