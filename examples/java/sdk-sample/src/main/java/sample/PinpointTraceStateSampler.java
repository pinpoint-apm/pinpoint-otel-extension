package sample;

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
import java.util.Objects;

/**
 * Wraps an existing {@link Sampler} and adds the Pinpoint
 * {@code pp=svc:<serviceName>;app:<applicationName>;type:<applicationType>} entry to the
 * trace state of the spans this process originates (CLIENT, PRODUCER, INTERNAL).
 * SERVER and CONSUMER spans are left untouched: their trace state is what arrived with the
 * request and names the caller. The delegate's sampling decision is kept as is.
 *
 * <p>Depends on the OpenTelemetry SDK API only. Copy this class into your project.</p>
 */
public final class PinpointTraceStateSampler implements Sampler {
    private static final String KEY = "pp";

    private final Sampler delegate;
    private final String ppValue;

    /**
     * @param delegate        the sampler you use today, e.g. {@code Sampler.parentBased(Sampler.alwaysOn())}
     * @param serviceName     Pinpoint service name; only a service registered on the Pinpoint side.
     *                        {@code null} to stay on the default service
     * @param applicationName Pinpoint application name; the same value as the Resource {@code service.name}
     * @param applicationType Pinpoint ServiceType code (optional); {@code null} for the collector default
     */
    public PinpointTraceStateSampler(Sampler delegate, String serviceName, String applicationName, Integer applicationType) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ppValue = buildValue(serviceName, applicationName, applicationType);
        if (this.ppValue == null) {
            throw new IllegalArgumentException("serviceName or applicationName must be provided");
        }
    }

    /** Order svc, app, type; absent parts are skipped; {@code null} when both svc and app are absent. */
    static String buildValue(String svc, String app, Integer type) {
        final boolean hasSvc = svc != null && !svc.isEmpty();
        final boolean hasApp = app != null && !app.isEmpty();
        if (!hasSvc && !hasApp) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(64);
        if (hasSvc) {
            sb.append("svc:").append(svc);
        }
        if (hasApp) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append("app:").append(app);
        }
        if (type != null) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append("type:").append(type.intValue());
        }
        return sb.toString();
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind spanKind,
                                       Attributes attributes, List<LinkData> parentLinks) {
        final SamplingResult result = delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
        if (spanKind == SpanKind.SERVER || spanKind == SpanKind.CONSUMER) {
            return result;                                   // receiving side: keep the incoming trace state
        }
        final TraceState parentTs = Span.fromContext(parentContext).getSpanContext().getTraceState();
        final TraceState delegateTs = result.getUpdatedTraceState(parentTs);   // layer on the delegate's entries (ot=th:..., ...)
        final TraceState updated = delegateTs.toBuilder().put(KEY, ppValue).build();
        if (updated.equals(delegateTs)) {
            return result;                                   // the identical pp entry is already there
        }
        return new SamplingResult() {
            @Override
            public SamplingDecision getDecision() {
                return result.getDecision();
            }

            @Override
            public Attributes getAttributes() {
                return result.getAttributes();
            }

            @Override
            public TraceState getUpdatedTraceState(TraceState ignored) {
                return updated;
            }
        };
    }

    @Override
    public String getDescription() {
        return "PinpointTraceStateSampler{delegate=" + delegate.getDescription() + ", pp=" + ppValue + "}";
    }
}
