package sample;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The caller (A). No instrumentation library: it creates the CLIENT span with the OTel API,
 * injects the context into the request headers through the propagator and calls B.
 *
 * <pre>usage: CallerApp manual|auto &lt;applicationName&gt; &lt;targetUrl&gt; &lt;count&gt;</pre>
 */
public final class CallerApp {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String app = args[1];
        String target = args[2];
        int count = Integer.parseInt(args[3]);

        OpenTelemetrySdk sdk = "manual".equals(mode)
                ? OpenTelemetrySetup.manual(app, 1220, "http://127.0.0.1:9998")
                : OpenTelemetrySetup.autoConfigured();
        System.out.println("SAMPLER " + sdk.getSdkTracerProvider().getSampler().getDescription());

        Tracer tracer = sdk.getTracer("sample-caller");
        URL url = new URL(target);
        int ok = 0;
        for (int i = 0; i < count; i++) {
            Span span = tracer.spanBuilder("GET " + url.getPath())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(AttributeKey.stringKey("http.request.method"), "GET")
                    .setAttribute(AttributeKey.stringKey("url.full"), target)
                    .setAttribute(AttributeKey.stringKey("server.address"), url.getHost())
                    .setAttribute(AttributeKey.longKey("server.port"), (long) url.getPort())
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                // traceparent + tracestate of the current context (the CLIENT span) go onto the request
                sdk.getPropagators().getTextMapPropagator().inject(
                        io.opentelemetry.context.Context.current(), conn, HttpURLConnection::setRequestProperty);
                if (i == 0) {
                    System.out.println("CALLER-SENT tracestate: " + conn.getRequestProperty("tracestate"));
                }
                int status = conn.getResponseCode();
                try (InputStream in = conn.getInputStream()) {
                    in.readAllBytes();
                }
                span.setAttribute(AttributeKey.longKey("http.response.status_code"), (long) status);
                if (status >= 400) {
                    span.setStatus(StatusCode.ERROR);
                } else {
                    ok++;
                }
            } finally {
                span.end();
            }
            Thread.sleep(300);
        }
        System.out.println("CALLER-DONE ok=" + ok + "/" + count);
        sdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
        sdk.close();
    }
}
