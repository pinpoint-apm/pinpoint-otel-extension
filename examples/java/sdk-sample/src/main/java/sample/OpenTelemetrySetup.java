package sample;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.time.Duration;
import java.util.Map;

/** The two ways of wiring the sampler into a hand-configured SDK: manual builder and autoconfigure. */
public final class OpenTelemetrySetup {
    private OpenTelemetrySetup() {
    }

    /**
     * Manual {@link SdkTracerProvider}. One applicationName value feeds both the Resource
     * {@code service.name} and the {@code app} part of the pp entry, so the two cannot drift.
     */
    public static OpenTelemetrySdk manual(String applicationName, Integer applicationType, String otlpEndpoint) {
        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), applicationName)));

        Sampler base = Sampler.parentBased(Sampler.alwaysOn());                 // the sampler you use today
        Sampler sampler = new PinpointTraceStateSampler(base, null, applicationName, applicationType);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(sampler)                                             // outermost in the chain
                .addSpanProcessor(BatchSpanProcessor.builder(
                                OtlpGrpcSpanExporter.builder().setEndpoint(otlpEndpoint).build())
                        .setScheduleDelay(Duration.ofSeconds(1))
                        .build())
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))  // W3C is required
                .build();
    }

    /**
     * {@link AutoConfiguredOpenTelemetrySdk}. Exporter, endpoint and service.name come from the
     * usual {@code otel.*} / {@code OTEL_*} settings; only the sampler is wrapped here. The
     * application type is read from a custom key, {@code pinpoint.application-type}.
     */
    public static OpenTelemetrySdk autoConfigured() {
        return AutoConfiguredOpenTelemetrySdk.builder()
                .addSamplerCustomizer((sampler, config) -> {
                    String app = config.getString("otel.service.name");
                    if (app == null) {
                        Map<String, String> resourceAttributes = config.getMap("otel.resource.attributes");
                        app = resourceAttributes.get("service.name");
                    }
                    Integer type = config.getInt("pinpoint.application-type");          // null when unset
                    return new PinpointTraceStateSampler(sampler, null, app, type);
                })
                .build()
                .getOpenTelemetrySdk();
    }
}
