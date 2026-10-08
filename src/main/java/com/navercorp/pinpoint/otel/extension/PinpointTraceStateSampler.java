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
import io.opentelemetry.api.trace.SpanContext;
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
 * (CLIENT, PRODUCER, and the INTERNAL / root spans in between), never on the span
 * that receives a remote call. A SERVER or CONSUMER span whose parent is remote keeps
 * the trace state exactly as the propagator extracted it: the {@code pp} entry in it,
 * if any, names the caller, and that is what the collector reads off the exported
 * span to draw the caller &rarr; callee edge. Overwriting it with this process's own
 * identity would make the collector record the callee as its own parent. The same
 * rule the native Pinpoint agent follows: the parent identity travels on the request
 * and is never mixed with the receiver's own.</p>
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
        final SpanContext parentSpanContext = Span.fromContext(parentContext).getSpanContext();
        if (isRemoteEntry(parentSpanContext, spanKind)) {
            // Receiving side of a remote call: leave the caller's trace state untouched.
            return delegateResult;
        }
        final TraceState parentTs = parentSpanContext.getTraceState();
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
     * A SERVER / CONSUMER span created from a context the propagator extracted from a
     * remote carrier. Its trace state describes the caller, not this process.
     */
    static boolean isRemoteEntry(SpanContext parentSpanContext, SpanKind spanKind) {
        if (!parentSpanContext.isRemote()) {
            return false;
        }
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
