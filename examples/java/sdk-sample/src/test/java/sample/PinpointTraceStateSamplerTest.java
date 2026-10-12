package sample;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinpointTraceStateSamplerTest {

    /** The wire contract file of this repository (src/test/resources), mapped in as a test resource by the pom. */
    private static final String CONTRACT_FILE = "/pp-tracestate-contract.txt";

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private SdkTracerProvider tracerProvider;

    @AfterEach
    void tearDown() {
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }

    @Test
    void buildValueProducesEveryContractValue() throws IOException {
        int cases = 0;
        for (String line : readContractLines()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\\|", -1);
            assertThat(cols).as("columns in '%s'", line).hasSize(4);
            String svc = cols[0].equals("-") ? null : cols[0];
            String app = cols[1].equals("-") ? null : cols[1];
            Integer type = cols[2].equals("-") ? null : Integer.valueOf(cols[2]);
            String expected = cols[3].equals("<null>") ? null : cols[3];

            assertThat(PinpointTraceStateSampler.buildValue(svc, app, type))
                    .as("pp value for svc=%s app=%s type=%s", svc, app, type).isEqualTo(expected);
            cases++;
        }
        assertThat(cases).isGreaterThanOrEqualTo(10);
    }

    @Test
    void rejectsConstructionWithoutServiceOrApplication() {
        assertThatThrownBy(() -> new PinpointTraceStateSampler(Sampler.alwaysOn(), null, null, 1010))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clientAndInternalSpansCarryPp_serverAndConsumerSpansDoNot() {
        Tracer tracer = tracer(new PinpointTraceStateSampler(Sampler.parentBased(Sampler.alwaysOn()), null, "order-api", 1010));

        tracer.spanBuilder("root-internal").startSpan().end();
        tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan().end();
        tracer.spanBuilder("producer").setSpanKind(SpanKind.PRODUCER).startSpan().end();
        tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan().end();
        tracer.spanBuilder("consumer").setSpanKind(SpanKind.CONSUMER).startSpan().end();

        Map<String, String> ppBySpan = new HashMap<>();
        for (SpanData span : exporter.getFinishedSpanItems()) {
            ppBySpan.put(span.getName(), span.getSpanContext().getTraceState().get("pp"));
        }
        assertThat(ppBySpan).containsEntry("root-internal", "app:order-api;type:1010")
                .containsEntry("client", "app:order-api;type:1010")
                .containsEntry("producer", "app:order-api;type:1010")
                .containsEntry("server", null)
                .containsEntry("consumer", null);
    }

    @Test
    void serverSpanKeepsTheCallersPp_andItsClientChildWritesOurOwn() {
        Tracer tracer = tracer(new PinpointTraceStateSampler(Sampler.parentBased(Sampler.alwaysOn()), null, "order-api", 1010));

        // what the caller ("front") sent, extracted the way an HTTP server instrumentation does
        Map<String, String> incoming = new HashMap<>();
        incoming.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        incoming.put("tracestate", "pp=app:front;type:1010");
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), incoming, new MapGetter());

        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).setParent(parent).startSpan();
        try (Scope ignored = server.makeCurrent()) {
            tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan().end();
        } finally {
            server.end();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        assertThat(find(spans, "server").getSpanContext().getTraceState().get("pp")).isEqualTo("app:front;type:1010");
        assertThat(find(spans, "client").getSpanContext().getTraceState().get("pp")).isEqualTo("app:order-api;type:1010");
    }

    @Test
    void outgoingHeadersCarryPp() {
        Tracer tracer = tracer(new PinpointTraceStateSampler(Sampler.parentBased(Sampler.alwaysOn()), "order-team", "order-api", null));

        Span client = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
        Map<String, String> headers = new HashMap<>();
        try (Scope ignored = client.makeCurrent()) {
            W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, Map::put);
        } finally {
            client.end();
        }
        assertThat(headers).containsKey("traceparent");
        assertThat(headers.get("tracestate")).isEqualTo("pp=svc:order-team;app:order-api");
    }

    @Test
    void keepsTheDelegatesDecisionAndTraceStateEntries() {
        Sampler delegate = Sampler.alwaysOff();
        Sampler sampler = new PinpointTraceStateSampler(delegate, null, "order-api", null);

        SpanContext parentContext = SpanContext.create("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.builder().put("ot", "th:8").put("pp", "app:stale").build());
        Context parent = Context.root().with(Span.wrap(parentContext));

        io.opentelemetry.sdk.trace.samplers.SamplingResult result = sampler.shouldSample(
                parent, parentContext.getTraceId(), "client", SpanKind.CLIENT,
                io.opentelemetry.api.common.Attributes.empty(), List.of());

        assertThat(result.getDecision()).isEqualTo(io.opentelemetry.sdk.trace.samplers.SamplingDecision.DROP);
        TraceState updated = result.getUpdatedTraceState(parentContext.getTraceState());
        assertThat(updated.get("ot")).isEqualTo("th:8");           // other vendors' entries survive
        assertThat(updated.get("pp")).isEqualTo("app:order-api");  // the same key is replaced
    }

    /**
     * The common mistake: placed inside ParentBased (in the root sampler slot) the wrapper is
     * never consulted for spans that have a parent, so CLIENT spans lose the pp entry.
     */
    @Test
    void regression_wrappedInsideParentBasedLosesPpOnChildClientSpans() {
        Sampler wrong = Sampler.parentBased(new PinpointTraceStateSampler(Sampler.alwaysOn(), null, "order-api", 1010));
        Tracer tracer = tracer(wrong);

        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope ignored = server.makeCurrent()) {
            tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan().end();
        } finally {
            server.end();
        }

        assertThat(find(exporter.getFinishedSpanItems(), "client").getSpanContext().getTraceState().get("pp")).isNull();
    }

    private Tracer tracer(Sampler sampler) {
        tracerProvider = SdkTracerProvider.builder()
                .setSampler(sampler)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return tracerProvider.get("test");
    }

    private static SpanData find(List<SpanData> spans, String name) {
        return spans.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> readContractLines() throws IOException {
        InputStream in = PinpointTraceStateSamplerTest.class.getResourceAsStream(CONTRACT_FILE);
        assertThat(in).as("contract file %s", CONTRACT_FILE).isNotNull();
        List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static final class MapGetter implements io.opentelemetry.context.propagation.TextMapGetter<Map<String, String>> {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    }
}
