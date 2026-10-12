package sample;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Both wirings, manual builder and autoconfigure, write pp on CLIENT spans. */
class OpenTelemetrySetupTest {

    private OpenTelemetrySdk sdk;

    @AfterEach
    void tearDown() {
        if (sdk != null) {
            sdk.close();
        }
        System.clearProperty("otel.service.name");
        System.clearProperty("otel.traces.exporter");
        System.clearProperty("otel.metrics.exporter");
        System.clearProperty("otel.logs.exporter");
        System.clearProperty("pinpoint.application-type");
    }

    @Test
    void manualSetupWritesPpOnClientSpans() {
        sdk = OpenTelemetrySetup.manual("order-api", 1010, "http://127.0.0.1:1");

        assertThat(sdk.getSdkTracerProvider().getSampler().getDescription())
                .isEqualTo("PinpointTraceStateSampler{delegate=ParentBased{root:AlwaysOnSampler,remoteParentSampled:AlwaysOnSampler,"
                        + "remoteParentNotSampled:AlwaysOffSampler,localParentSampled:AlwaysOnSampler,localParentNotSampled:AlwaysOffSampler}, "
                        + "pp=app:order-api;type:1010}");
        // the span is not ended on purpose: nothing is exported to the unreachable endpoint
        Span client = sdk.getTracer("t").spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
        assertThat(client.getSpanContext().getTraceState().get("pp")).isEqualTo("app:order-api;type:1010");
        assertThat(sdk.getPropagators().getTextMapPropagator().fields()).contains("traceparent", "tracestate");
    }

    @Test
    void autoConfiguredSetupWritesPpOnClientSpans() {
        System.setProperty("otel.service.name", "order-api");
        System.setProperty("otel.traces.exporter", "none");
        System.setProperty("otel.metrics.exporter", "none");
        System.setProperty("otel.logs.exporter", "none");
        System.setProperty("pinpoint.application-type", "1010");

        sdk = OpenTelemetrySetup.autoConfigured();

        assertThat(sdk.getSdkTracerProvider().getSampler().getDescription()).startsWith("PinpointTraceStateSampler{");
        Span client = sdk.getTracer("t").spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
        assertThat(client.getSpanContext().getTraceState().get("pp")).isEqualTo("app:order-api;type:1010");
        client.end();
    }
}
