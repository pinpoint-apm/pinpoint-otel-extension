# Java: connecting an OpenTelemetry application to the Pinpoint server map

Two ways, depending on how the application gets its OpenTelemetry SDK:

| Your setup | Use | Section |
|---|---|---|
| OpenTelemetry **Java agent** (`-javaagent:opentelemetry-javaagent.jar`) | the extension jar of this repository, no code change | [1. With the Java agent](#1-with-the-java-agent) |
| SDK configured **by the application** (manual `SdkTracerProvider`, `AutoConfiguredOpenTelemetrySdk`, `opentelemetry-spring-boot-starter`) | one `Sampler` class copied into the application | [2. Without the Java agent](#2-without-the-java-agent) |

Both do the same thing: wrap the sampler the application already uses and add the Pinpoint
`pp` entry to the trace state of outgoing spans, so the Pinpoint collector that receives the
callee's spans can draw the **caller → callee** edge. The format and the rules are in
[contract.md](contract.md); read it once, this page only applies it.

Every command and code block on this page is copied from a configuration that was run against
a Pinpoint collector and web, and the code blocks of section 2 are copies of
[`examples/java`](../examples/java), which CI builds and tests.

## 1. With the Java agent

### 1.1 What you need

- OpenTelemetry Java agent 2.x.
- `pinpoint-otel-extension-<version>.jar` (about 8 KB, Java 8 bytecode, depends only on the
  SDK the agent ships). Build it from this repository with `./mvnw -B verify` (JDK 11 or newer;
  the jar is `target/pinpoint-otel-extension-<version>.jar`), or take it from the Maven
  repository your organization publishes it to. The jar goes on the **agent's extension path**,
  not on the application classpath.

### 1.2 JVM options

The caller side of a Spring Boot application calling another service over `RestTemplate`:

```sh
java \
  -javaagent:<path>/opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=<path>/pinpoint-otel-extension-<version>.jar \
  -Dotel.service.name=order-api \
  -Dotel.resource.attributes=pinpoint.applicationType=1010 \
  -Dotel.traces.exporter=otlp \
  -Dotel.exporter.otlp.endpoint=http://<pinpoint-collector>:9998 \
  -Dotel.exporter.otlp.protocol=grpc \
  -Dotel.metrics.exporter=none \
  -Dotel.logs.exporter=none \
  -jar order-api.jar
```

- `otel.service.name` is enough for the application name. The extension resolves
  `pinpoint.applicationName → otel.service.name → service.name`, and the collector reads the
  Resource in the same order, so the two ends agree.
- `pinpoint.applicationType` (optional) is the Pinpoint ServiceType code the callee's
  collector uses for the parent node. Without it the collector uses its default for an
  OpenTelemetry server.
- The Pinpoint collector only takes traces, hence `none` for metrics and logs.

### 1.3 Environment variables (containers)

The same configuration as environment variables, here as a Docker Compose overlay for two
Java services of the OpenTelemetry demo. The images already set `OTEL_SERVICE_NAME`, which
becomes the application name:

```yaml
x-pp-java: &pp-java
  OTEL_JAVAAGENT_EXTENSIONS: /ext/pinpoint-otel-extension.jar
  OTEL_RESOURCE_ATTRIBUTES: pinpoint.serviceName=otel-demo,pinpoint.applicationType=1220,service.namespace=opentelemetry-demo,service.version=${IMAGE_VERSION}
services:
  ad:
    volumes: ["${PP_EXT_JAR}:/ext/pinpoint-otel-extension.jar:ro"]
    environment: *pp-java
  fraud-detection:
    volumes: ["${PP_EXT_JAR}:/ext/pinpoint-otel-extension.jar:ro"]
    environment: *pp-java
```

`OTEL_JAVAAGENT_EXTENSIONS` is the environment form of `-Dotel.javaagent.extensions`. Set
`pinpoint.serviceName` only to a service that is **registered** on the Pinpoint side (3 to 254 characters of `[a-zA-Z0-9._-]`, not one of the reserved names `DEFAULT`, `ERROR`, `UNKNOWN`, `NULL`; see
[contract.md](contract.md#collector-side)); leave it out to stay on the default service.

### 1.4 Configuration keys

| Key | Fallback | Notes |
|---|---|---|
| `pinpoint.applicationName` | `otel.service.name`, then the `service.name` resource attribute | one of the three must resolve; usually `otel.service.name` alone |
| `pinpoint.serviceName` | none, explicit only | Pinpoint service grouping; never derived from `service.namespace`; registered services only (3 to 254 characters of `[a-zA-Z0-9._-]`, not a reserved name) |
| `pinpoint.applicationType` | none | numeric ServiceType code, optional, used by the caller only |

For each key the extension tries the dedicated property (`-Dpinpoint.applicationName=...`)
first and the `OTEL_RESOURCE_ATTRIBUTES` entry of the same name second. If nothing resolves
the extension leaves the sampler alone and logs one INFO line; agent startup is not affected.

### 1.5 Checking

1. Startup log of the instrumented JVM:
   ```
   [otel.javaagent ...] [main] INFO com.navercorp.pinpoint.otel.extension.PinpointTraceStateAutoConfig - Pinpoint tracestate injection enabled: svc=null, app=order-api, type=1010
   ```
2. The callee receives `tracestate: pp=app:order-api;type:1010` on the request.
3. Pinpoint web: the edge `order-api → <callee>` on the server map, and both applications in
   one call tree.

### 1.6 Pitfalls

- **A `-Dpinpoint.applicationName=...` override only changes the outgoing trace state.** The
  process's own spans keep the Resource built from `OTEL_RESOURCE_ATTRIBUTES`, so the server map
  shows the same process as two nodes. Change `otel.service.name` / `OTEL_RESOURCE_ATTRIBUTES`
  instead.
- An unregistered `pinpoint.serviceName` gets the sender's own spans rejected
  (`service_not_found`), and the collector caches the miss for 10 minutes by default.
- Do not load this jar together with the older build of the extension that shipped inside the
  Pinpoint repository (`otlptrace-otel-extension`); the sampler would be wrapped twice.
- Loading the extension on the **callee only** draws no edge. The edge comes from the
  **caller's** injection. The callee's own extension matters when that service calls the next
  hop.

## 2. Without the Java agent

The extension jar is loaded through the agent's extension path, so an application that
configures the SDK itself cannot use it. Instead, copy the sampler class below into the
application and wrap your sampler with it. Nothing here depends on the extension jar or on a
Pinpoint library; it is OpenTelemetry SDK API only.

The code is copied from [`examples/java/sdk-sample`](../examples/java/sdk-sample) and
[`examples/java/spring-boot-sample`](../examples/java/spring-boot-sample).

### 2.1 The sampler class

[`PinpointTraceStateSampler.java`](../examples/java/sdk-sample/src/main/java/sample/PinpointTraceStateSampler.java)
(change the package):

```java
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
```

The example's test
([`PinpointTraceStateSamplerTest`](../examples/java/sdk-sample/src/test/java/sample/PinpointTraceStateSamplerTest.java))
asserts this class against the contract file, the per-kind rules, the caller's entry surviving
on a SERVER span, the injected header, the delegate's decision and entries being kept, and the
"wrapped inside ParentBased" mistake.

### 2.2 Wiring it in

#### 2.2-A Manual `SdkTracerProvider`

[`OpenTelemetrySetup.manual`](../examples/java/sdk-sample/src/main/java/sample/OpenTelemetrySetup.java):

```java
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
```

One `applicationName` value feeds both the Resource `service.name` and the `app` part of the
entry, so the two cannot drift.

If you also create the spans yourself, without an instrumentation library, make the CLIENT
span current and let the propagator write the headers
([`CallerApp`](../examples/java/sdk-sample/src/main/java/sample/CallerApp.java)):

```java
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
    int status = conn.getResponseCode();
    ...
} finally {
    span.end();
}
```

The callee only extracts the incoming context and uses it as the parent of its SERVER span
([`CalleeApp`](../examples/java/sdk-sample/src/main/java/sample/CalleeApp.java)). It never
touches the trace state:

```java
Context parent = sdk.getPropagators().getTextMapPropagator()
        .extract(Context.current(), exchange.getRequestHeaders(), HEADERS);
Span span = tracer.spanBuilder("GET /order")
        .setParent(parent)
        .setSpanKind(SpanKind.SERVER)
        .setAttribute(AttributeKey.stringKey("http.request.method"), "GET")
        .setAttribute(AttributeKey.stringKey("url.path"), "/order")
        .setAttribute(AttributeKey.stringKey("http.route"), "/order")
        .startSpan();
```

#### 2.2-B `AutoConfiguredOpenTelemetrySdk`

Exporter, endpoint and `service.name` come from the usual `otel.*` / `OTEL_*` settings; the
code only wraps the sampler. The application type is read from a custom key,
`pinpoint.application-type`
([`OpenTelemetrySetup.autoConfigured`](../examples/java/sdk-sample/src/main/java/sample/OpenTelemetrySetup.java)):

```java
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
```

Settings used with it:

```
-Dotel.service.name=order-api -Dpinpoint.application-type=1010
-Dotel.traces.exporter=otlp -Dotel.exporter.otlp.protocol=grpc -Dotel.exporter.otlp.endpoint=http://<pinpoint-collector>:9998
-Dotel.metrics.exporter=none -Dotel.logs.exporter=none
```

The autoconfigure default propagators are `tracecontext,baggage`, so nothing to set. If you
changed `otel.propagators`, keep `tracecontext` in it.

#### 2.2-C Spring Boot starter (`opentelemetry-spring-boot-starter`)

The starter applies every bean of type `AutoConfigurationCustomizerProvider` when it builds
the SDK, so one bean is all it takes
([`PinpointTraceStateConfig`](../examples/java/spring-boot-sample/src/main/java/bootsample/PinpointTraceStateConfig.java)):

```java
@Configuration
public class PinpointTraceStateConfig {

    @Bean
    AutoConfigurationCustomizerProvider pinpointTraceState() {
        return customizer -> customizer.addSamplerCustomizer((sampler, config) ->
                new PinpointTraceStateSampler(sampler, null,
                        config.getString("otel.service.name"),
                        config.getInt("pinpoint.application-type")));     // null when unset
    }
}
```

`otel.service.name` and `pinpoint.application-type` can come from `application.properties`, a
`-D` system property or an environment variable alike. The example's
[`application.properties`](../examples/java/spring-boot-sample/src/main/resources/application.properties):

```properties
# Pinpoint OTLP trace collector only takes traces
otel.traces.exporter=otlp
otel.exporter.otlp.protocol=grpc
otel.exporter.otlp.endpoint=http://127.0.0.1:9998
otel.metrics.exporter=none
otel.logs.exporter=none
otel.bsp.schedule.delay=1000
# otel.service.name / pinpoint.application-type / server.port are given per instance with -D
```

Outgoing calls through a client the starter instruments get the CLIENT span and the header
injection for free. A `RestTemplate` is instrumented when it is a **bean built from
`RestTemplateBuilder`**
([`BootSampleApplication`](../examples/java/spring-boot-sample/src/main/java/bootsample/BootSampleApplication.java)):

```java
@Bean
RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();
}
```

Run: `java -Dotel.service.name=order-api -Dpinpoint.application-type=1010 -Dserver.port=8080 -jar order-api.jar`

### 2.3 Dependencies

Plain SDK ([`sdk-sample/pom.xml`](../examples/java/sdk-sample/pom.xml)); no Pinpoint artifact:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-bom</artifactId>
            <version>1.53.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-api</artifactId></dependency>
    <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk</artifactId></dependency>
    <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-exporter-otlp</artifactId></dependency>
    <!-- only for the AutoConfiguredOpenTelemetrySdk mode -->
    <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk-extension-autoconfigure</artifactId></dependency>
</dependencies>
```

Spring Boot ([`spring-boot-sample/pom.xml`](../examples/java/spring-boot-sample/pom.xml)), with
the Spring Boot parent:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.opentelemetry.instrumentation</groupId>
            <artifactId>opentelemetry-instrumentation-bom</artifactId>
            <version>2.28.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>io.opentelemetry.instrumentation</groupId><artifactId>opentelemetry-spring-boot-starter</artifactId></dependency>
</dependencies>
```

### 2.4 Rules in short

- **`applicationName` equals the Resource `service.name`.** Otherwise the same process shows
  up as two nodes.
- **The sampler is the outermost one.** `Sampler.parentBased(new PinpointTraceStateSampler(...))`
  puts it in the root sampler slot, where it is never consulted for spans that have a parent,
  so CLIENT spans lose the entry (the example test `regression_wrappedInsideParentBasedLosesPpOnChildClientSpans`
  pins this).
- **`W3CTraceContextPropagator` is in the propagators.** B3 only carries no `tracestate`; a
  composite is fine.
- **`serviceName` only for a registered service** (3 to 254 characters of `[a-zA-Z0-9._-]`, not one of the reserved names `DEFAULT`, `ERROR`, `UNKNOWN`, `NULL`). Otherwise `service_not_found`, and the miss
  is cached for 10 minutes by default.
- Metrics and logs exporters go to `none` or to another endpoint.

### 2.5 Checking

Lines as printed by the examples:

1. The sampler description, `sdk.getSdkTracerProvider().getSampler().getDescription()`:
   ```
   SAMPLER PinpointTraceStateSampler{delegate=ParentBased{root:AlwaysOnSampler,remoteParentSampled:AlwaysOnSampler,remoteParentNotSampled:AlwaysOffSampler,localParentSampled:AlwaysOnSampler,localParentNotSampled:AlwaysOffSampler}, pp=app:order-api;type:1010}
   ```
2. The outgoing header: `CALLER-SENT tracestate: pp=app:order-api;type:1010`
3. What the callee received equals the trace state of its SERVER span, and it is the caller's
   entry, not its own:
   `CALLEE-SAW tracestate: pp=app:order-api;type:1010 -> server span tracestate: {pp=app:order-api;type:1010}`.
   The Spring Boot example echoes it from `/ping`: `pong tracestate=pp=app:order-api;type:1010`.
4. Pinpoint web: the edge `order-api → <callee>` on the callee's server map, both applications
   in one call tree.
5. To pin it in your own tests, export to an `InMemorySpanExporter` and assert
   `getSpanContext().getTraceState().get("pp")` per span kind, as the example test does.

### 2.6 Common mistakes

| Symptom | Cause |
|---|---|
| No edge, CLIENT spans carry no `pp` | the sampler is wrapped inside ParentBased |
| No edge, no `tracestate` header on the request | B3-only propagators, or an HTTP client that is not instrumented and no manual `inject` |
| Spring Boot: the sampler description does not mention `PinpointTraceStateSampler` | the bean's type is not `AutoConfigurationCustomizerProvider`, or `otel.sdk.disabled=true` |
| The same application shows as two nodes | `applicationName` differs from the Resource `service.name` |
| The sender's own spans are not stored | unregistered `serviceName`, `service_not_found`; up to 10 minutes after registering |
| The callee shows a self edge | `pp` written on SERVER or CONSUMER spans (rule 1 of the contract) |
| `pp` silently disappears | a `,` or `=` in a name (invalid `tracestate` syntax) |
