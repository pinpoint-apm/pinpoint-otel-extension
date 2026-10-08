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
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinpointTraceStateSamplerTest {

    private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";

    private static SamplingResult invoke(Sampler sampler) {
        return sampler.shouldSample(
                Context.root(), TRACE_ID, "name",
                SpanKind.SERVER, Attributes.empty(), Collections.emptyList());
    }

    @Test
    void shouldSample_injectsPpEntry_andPreservesDecisionAndAttributes() {
        Sampler sampler = new PinpointTraceStateSampler(
                Sampler.alwaysOn(), "my-svc", "my-app", 1010);

        SamplingResult result = invoke(sampler);

        assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        TraceState updated = result.getUpdatedTraceState(TraceState.getDefault());
        assertThat(updated.get("pp")).isEqualTo("svc:my-svc;app:my-app;type:1010");
    }

    @Test
    void shouldSample_preservesDropDecision_butStillUpdatesTraceState() {
        // Rationale: a dropped local decision still propagates downstream; the next hop
        // may re-sample, and the Pinpoint context must survive across that boundary.
        Sampler sampler = new PinpointTraceStateSampler(
                Sampler.alwaysOff(), "my-svc", "my-app", null);

        SamplingResult result = invoke(sampler);

        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        TraceState updated = result.getUpdatedTraceState(TraceState.getDefault());
        assertThat(updated.get("pp")).isEqualTo("svc:my-svc;app:my-app");
    }

    @Test
    void shouldSample_preservesExistingOtherVendorEntries() {
        // When the parent context already carried e.g. dd= / nr=, those must be retained.
        // We simulate this by hand-crafting the TraceState the wrapped sampler would have
        // observed — the production W3C propagator extracts these from incoming headers.
        TraceState parentTs = TraceState.builder()
                .put("dd", "s:1")
                .put("nr", "opaque")
                .build();
        Sampler sampler = new PinpointTraceStateSampler(
                Sampler.alwaysOn(), "my-svc", "my-app", 1010);

        TraceState updated = sampler
                .shouldSample(Context.root(), TRACE_ID, "name", SpanKind.SERVER,
                        Attributes.empty(), Collections.emptyList())
                .getUpdatedTraceState(parentTs);

        // pp= is added; dd / nr remain readable.
        assertThat(updated.get("pp")).isEqualTo("svc:my-svc;app:my-app;type:1010");
    }

    @Test
    void shouldSample_preservesDelegateTraceStateUpdates() {
        // Regression: the pp entry was layered on the *parent* trace state, silently
        // discarding entries the delegate itself wrote (e.g. a consistent-probability
        // sampler recording its ot=th:... threshold).
        Sampler otWritingDelegate = new Sampler() {
            @Override
            public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                               SpanKind spanKind, Attributes attributes,
                                               List<LinkData> parentLinks) {
                return new SamplingResult() {
                    @Override
                    public SamplingDecision getDecision() {
                        return SamplingDecision.RECORD_AND_SAMPLE;
                    }

                    @Override
                    public Attributes getAttributes() {
                        return Attributes.empty();
                    }

                    @Override
                    public TraceState getUpdatedTraceState(TraceState parentTraceState) {
                        return parentTraceState.toBuilder().put("ot", "th:8").build();
                    }
                };
            }

            @Override
            public String getDescription() {
                return "OtWritingSampler";
            }
        };
        Sampler sampler = new PinpointTraceStateSampler(otWritingDelegate, "my-svc", "my-app", null);

        TraceState updated = invoke(sampler).getUpdatedTraceState(TraceState.getDefault());

        assertThat(updated.get("ot")).isEqualTo("th:8");
        assertThat(updated.get("pp")).isEqualTo("svc:my-svc;app:my-app");
    }

    @Test
    void shouldSample_typeOmittedFromValueWhenNull() {
        Sampler sampler = new PinpointTraceStateSampler(
                Sampler.alwaysOn(), "my-svc", "my-app", null);

        SamplingResult result = invoke(sampler);

        assertThat(result.getUpdatedTraceState(TraceState.getDefault()).get("pp"))
                .isEqualTo("svc:my-svc;app:my-app");
    }

    @Test
    void constructor_requiresSvcOrApp() {
        assertThatThrownBy(() -> new PinpointTraceStateSampler(Sampler.alwaysOn(), null, null, 1010))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PinpointTraceStateSampler(Sampler.alwaysOn(), "", "", 1010))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_requiresDelegate() {
        assertThatThrownBy(() -> new PinpointTraceStateSampler(null, "svc", "app", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getDescription_includesDelegateAndPpValue() {
        Sampler sampler = new PinpointTraceStateSampler(
                Sampler.alwaysOn(), "my-svc", "my-app", 1010);

        assertThat(sampler.getDescription())
                .contains("PinpointTraceStateSampler")
                .contains(Sampler.alwaysOn().getDescription())
                .contains("svc:my-svc;app:my-app;type:1010");
    }

    // -----------------------------------------------------------------------
    // Receiving side of a remote call: the caller's tracestate must survive
    // -----------------------------------------------------------------------

    private static final String CALLER_PP = "svc:caller-team;app:caller-api;type:1010";

    /** The context the W3C propagator produces on the callee from incoming traceparent/tracestate. */
    private static Context remoteParent(TraceState traceState) {
        SpanContext remote = SpanContext.createFromRemoteParent(
                TRACE_ID, "00f067aa0ba902b7", TraceFlags.getSampled(), traceState);
        return Context.root().with(Span.wrap(remote));
    }

    /** A span of this process (e.g. the SERVER span) as the parent of a local child. */
    private static Context localParent(TraceState traceState) {
        SpanContext local = SpanContext.create(
                TRACE_ID, "00f067aa0ba902b8", TraceFlags.getSampled(), traceState);
        return Context.root().with(Span.wrap(local));
    }

    private static TraceState traceStateOf(SamplingResult result, Context parentContext) {
        return result.getUpdatedTraceState(Span.fromContext(parentContext).getSpanContext().getTraceState());
    }

    @Test
    void remoteServerSpan_keepsTheCallersPpEntry() {
        Sampler sampler = new PinpointTraceStateSampler(Sampler.alwaysOn(), "my-svc", "my-app", 1220);
        Context parent = remoteParent(TraceState.builder().put("pp", CALLER_PP).put("dd", "s:1").build());

        SamplingResult result = sampler.shouldSample(parent, TRACE_ID, "GET /order", SpanKind.SERVER,
                Attributes.empty(), Collections.emptyList());

        // The exported SERVER span carries the caller's identity, which is what the collector
        // reads to attach the parent application. Our own pp must not replace it.
        assertThat(traceStateOf(result, parent).get("pp")).isEqualTo(CALLER_PP);
        assertThat(traceStateOf(result, parent).get("dd")).isEqualTo("s:1");
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void remoteServerSpan_withoutIncomingPp_addsNothing() {
        // Caller without the extension: the collector then has no parent to record, which is
        // correct — inventing our own identity there would draw a self edge.
        Sampler sampler = new PinpointTraceStateSampler(Sampler.alwaysOn(), "my-svc", "my-app", 1220);
        Context parent = remoteParent(TraceState.getDefault());

        SamplingResult result = sampler.shouldSample(parent, TRACE_ID, "GET /order", SpanKind.SERVER,
                Attributes.empty(), Collections.emptyList());

        assertThat(traceStateOf(result, parent).get("pp")).isNull();
    }

    @Test
    void remoteConsumerSpan_keepsTheCallersPpEntry() {
        Sampler sampler = new PinpointTraceStateSampler(Sampler.alwaysOn(), "my-svc", "my-app", 1220);
        Context parent = remoteParent(TraceState.builder().put("pp", CALLER_PP).build());

        SamplingResult result = sampler.shouldSample(parent, TRACE_ID, "orders process", SpanKind.CONSUMER,
                Attributes.empty(), Collections.emptyList());

        assertThat(traceStateOf(result, parent).get("pp")).isEqualTo(CALLER_PP);
    }

    @Test
    void localClientSpan_replacesTheCallersPpWithOurOwn() {
        // The CLIENT span under our SERVER span is what the propagator injects downstream: it
        // must name this process, so the next hop records us as its parent.
        Sampler sampler = new PinpointTraceStateSampler(Sampler.alwaysOn(), "my-svc", "my-app", 1220);
        Context parent = localParent(TraceState.builder().put("pp", CALLER_PP).put("dd", "s:1").build());

        SamplingResult result = sampler.shouldSample(parent, TRACE_ID, "GET", SpanKind.CLIENT,
                Attributes.empty(), Collections.emptyList());

        assertThat(traceStateOf(result, parent).get("pp")).isEqualTo("svc:my-svc;app:my-app;type:1220");
        assertThat(traceStateOf(result, parent).get("dd")).isEqualTo("s:1");
    }

    @Test
    void localServerSpan_isNotTreatedAsRemoteEntry() {
        // A SERVER span whose parent is a span of this very process (no remote carrier) is an
        // in-process hop; it is not the receiving side of a remote call, so it gets our pp.
        Sampler sampler = new PinpointTraceStateSampler(Sampler.alwaysOn(), "my-svc", "my-app", 1220);
        Context parent = localParent(TraceState.getDefault());

        SamplingResult result = sampler.shouldSample(parent, TRACE_ID, "GET /ping", SpanKind.SERVER,
                Attributes.empty(), Collections.emptyList());

        assertThat(traceStateOf(result, parent).get("pp")).isEqualTo("svc:my-svc;app:my-app;type:1220");
    }

    @Test
    void rootProducerAndClientSpans_getOurPp() {
        Sampler sampler = new PinpointTraceStateSampler(Sampler.alwaysOn(), "my-svc", "my-app", 1220);

        for (SpanKind kind : new SpanKind[]{SpanKind.CLIENT, SpanKind.PRODUCER, SpanKind.INTERNAL}) {
            SamplingResult result = sampler.shouldSample(Context.root(), TRACE_ID, "root", kind,
                    Attributes.empty(), Collections.emptyList());
            assertThat(result.getUpdatedTraceState(TraceState.getDefault()).get("pp"))
                    .as("kind %s", kind)
                    .isEqualTo("svc:my-svc;app:my-app;type:1220");
        }
    }

    @Test
    void isRemoteEntry_matrix() {
        SpanContext remote = SpanContext.createFromRemoteParent(TRACE_ID, "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault());
        SpanContext local = SpanContext.create(TRACE_ID, "00f067aa0ba902b8", TraceFlags.getSampled(), TraceState.getDefault());

        assertThat(PinpointTraceStateSampler.isRemoteEntry(remote, SpanKind.SERVER)).isTrue();
        assertThat(PinpointTraceStateSampler.isRemoteEntry(remote, SpanKind.CONSUMER)).isTrue();
        assertThat(PinpointTraceStateSampler.isRemoteEntry(remote, SpanKind.CLIENT)).isFalse();
        assertThat(PinpointTraceStateSampler.isRemoteEntry(remote, SpanKind.PRODUCER)).isFalse();
        assertThat(PinpointTraceStateSampler.isRemoteEntry(remote, SpanKind.INTERNAL)).isFalse();
        assertThat(PinpointTraceStateSampler.isRemoteEntry(local, SpanKind.SERVER)).isFalse();
        assertThat(PinpointTraceStateSampler.isRemoteEntry(SpanContext.getInvalid(), SpanKind.SERVER)).isFalse();
    }
}
