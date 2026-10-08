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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the extension the way the OpenTelemetry Java agent does: through the
 * {@code AutoConfigurationCustomizerProvider} service file, into a real autoconfigured SDK.
 * Then it starts a span and reads the {@code pp} entry back from the span's trace state.
 */
class AutoConfiguredSdkIntegrationTest {

    @Test
    void spanCarriesPpEntryWhenPinpointKeysAreSet() {
        Map<String, String> props = baseProperties();
        props.put("otel.resource.attributes",
                "pinpoint.applicationName=order-api,pinpoint.serviceName=order-team,pinpoint.applicationType=1010");

        OpenTelemetrySdk sdk = build(props);
        try {
            String description = sdk.getSdkTracerProvider().getSampler().getDescription();
            assertThat(description).startsWith("PinpointTraceStateSampler{");

            Span span = startSpan(sdk);
            assertThat(span.getSpanContext().getTraceState().get("pp"))
                    .isEqualTo("svc:order-team;app:order-api;type:1010");
            span.end();
        } finally {
            sdk.close();
        }
    }

    @Test
    void fallsBackToSemanticConventionKeys() {
        Map<String, String> props = baseProperties();
        props.put("otel.service.name", "order-api");
        // service.namespace is intentionally ignored: svc is explicit-only.
        props.put("otel.resource.attributes", "service.namespace=order-team");

        OpenTelemetrySdk sdk = build(props);
        try {
            Span span = startSpan(sdk);
            assertThat(span.getSpanContext().getTraceState().get("pp"))
                    .isEqualTo("app:order-api");
            span.end();
        } finally {
            sdk.close();
        }
    }

    @Test
    void leavesSamplerUntouchedWhenNothingResolves() {
        Map<String, String> props = baseProperties();
        // No pinpoint.* keys, no otel.service.name, no service.* resource attributes.

        OpenTelemetrySdk sdk = build(props);
        try {
            String description = sdk.getSdkTracerProvider().getSampler().getDescription();
            assertThat(description).doesNotContain("PinpointTraceStateSampler");

            Span span = startSpan(sdk);
            assertThat(span.getSpanContext().getTraceState().get("pp")).isNull();
            span.end();
        } finally {
            sdk.close();
        }
    }

    private static Map<String, String> baseProperties() {
        Map<String, String> props = new HashMap<>();
        props.put("otel.traces.exporter", "none");
        props.put("otel.metrics.exporter", "none");
        props.put("otel.logs.exporter", "none");
        return props;
    }

    private static OpenTelemetrySdk build(Map<String, String> props) {
        AutoConfiguredOpenTelemetrySdk configured = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> props)
                .build();
        return configured.getOpenTelemetrySdk();
    }

    private static Span startSpan(OpenTelemetrySdk sdk) {
        Tracer tracer = sdk.getTracer("pinpoint-otel-extension-test");
        return tracer.spanBuilder("test-span").startSpan();
    }
}
