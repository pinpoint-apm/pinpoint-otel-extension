package sample;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/**
 * The callee (B). A JDK HttpServer that creates the SERVER span with the OTel API. It has
 * nothing to do for Pinpoint: the incoming trace state is left as it arrived.
 *
 * <pre>usage: CalleeApp manual|auto &lt;applicationName&gt; &lt;port&gt; &lt;lifetimeMs&gt;</pre>
 */
public final class CalleeApp {
    private static final TextMapGetter<Map<String, List<String>>> HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, List<String>> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, List<String>> carrier, String key) {
            List<String> values = carrier == null ? null : carrier.get(key);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    };

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String app = args[1];
        int port = Integer.parseInt(args[2]);
        long lifetimeMs = Long.parseLong(args[3]);

        OpenTelemetrySdk sdk = "manual".equals(mode)
                ? OpenTelemetrySetup.manual(app, 1220, "http://127.0.0.1:9998")
                : OpenTelemetrySetup.autoConfigured();
        System.out.println("SAMPLER " + sdk.getSdkTracerProvider().getSampler().getDescription());
        Tracer tracer = sdk.getTracer("sample-callee");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/order", exchange -> {
            // the HttpServer header map looks keys up case-insensitively
            Context parent = sdk.getPropagators().getTextMapPropagator()
                    .extract(Context.current(), exchange.getRequestHeaders(), HEADERS);
            Span span = tracer.spanBuilder("GET /order")
                    .setParent(parent)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute(AttributeKey.stringKey("http.request.method"), "GET")
                    .setAttribute(AttributeKey.stringKey("url.path"), "/order")
                    .setAttribute(AttributeKey.stringKey("http.route"), "/order")
                    .setAttribute(AttributeKey.stringKey("server.address"), "127.0.0.1")
                    .setAttribute(AttributeKey.longKey("server.port"), (long) port)
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                System.out.println("CALLEE-SAW tracestate: " + exchange.getRequestHeaders().getFirst("tracestate")
                        + " -> server span tracestate: " + span.getSpanContext().getTraceState().asMap());
                byte[] body = "ok\n".getBytes("UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                span.setAttribute(AttributeKey.longKey("http.response.status_code"), 200L);
            } finally {
                span.end();
                exchange.close();
            }
        });
        server.start();
        System.out.println("CALLEE-UP " + port);
        Thread.sleep(lifetimeMs);
        server.stop(0);
        sdk.close();
        System.out.println("CALLEE-DONE");
    }
}
