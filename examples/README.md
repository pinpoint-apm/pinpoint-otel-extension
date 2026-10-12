# Examples

Runnable projects behind the guides in [`docs/`](../docs). The Java projects are built on CI
([`examples.yml`](../.github/workflows/examples.yml)) and the Python package they use is tested
by [`python.yml`](../.github/workflows/python.yml), so the code blocks in the guides stay copies
of code that compiles and passes its tests. None of the Java projects depends on the extension jar
or on any Pinpoint library; each carries its own copy of the sampler class.

| Project | Guide | What it shows |
|---|---|---|
| [`java/sdk-sample`](java/sdk-sample) | [docs/java.md](../docs/java.md), *Without the Java agent* | `PinpointTraceStateSampler` plus the manual `SdkTracerProvider` and `AutoConfiguredOpenTelemetrySdk` wirings; a caller and a callee built on the OTel API alone; tests against the wire contract file |
| [`java/spring-boot-sample`](java/spring-boot-sample) | [docs/java.md](../docs/java.md), *Spring Boot starter* | `opentelemetry-spring-boot-starter` with one `AutoConfigurationCustomizerProvider` bean; `/call` (caller) and `/ping` (callee) endpoints |
| [`python`](python) | [docs/python.md](../docs/python.md), *With the package* | two scripts under `opentelemetry-instrument` with `OTEL_TRACES_SAMPLER=pinpoint`: `requests` caller and WSGI callee; the package under [`python/`](../python) does the work |

## Build

From the repository root, with JDK 17 or newer:

```sh
./mvnw -B -f examples/java/sdk-sample/pom.xml verify
./mvnw -B -f examples/java/spring-boot-sample/pom.xml package -DskipTests
```

## Run against a Pinpoint collector

Both examples export OTLP/gRPC to `http://127.0.0.1:9998`, the default port of the Pinpoint
OTLP trace collector. Start a callee, then a caller, and look for the `caller → callee` edge on
the callee's server map.

```sh
# plain SDK, manual wiring (the first argument selects manual | auto)
java -cp examples/java/sdk-sample/target/pinpoint-otel-sdk-sample-1.0.0-all.jar \
     sample.CalleeApp manual inventory-api 18092 60000
java -cp examples/java/sdk-sample/target/pinpoint-otel-sdk-sample-1.0.0-all.jar \
     sample.CallerApp manual order-api http://127.0.0.1:18092/order 10

# plain SDK, autoconfigure wiring: the SDK is configured through -Dotel.* as usual
OTEL="-Dotel.traces.exporter=otlp -Dotel.exporter.otlp.protocol=grpc -Dotel.exporter.otlp.endpoint=http://127.0.0.1:9998 -Dotel.metrics.exporter=none -Dotel.logs.exporter=none"
java $OTEL -Dotel.service.name=inventory-api -Dpinpoint.application-type=1010 \
     -cp examples/java/sdk-sample/target/pinpoint-otel-sdk-sample-1.0.0-all.jar sample.CalleeApp auto inventory-api 18093 60000
java $OTEL -Dotel.service.name=order-api -Dpinpoint.application-type=1010 \
     -cp examples/java/sdk-sample/target/pinpoint-otel-sdk-sample-1.0.0-all.jar sample.CallerApp auto order-api http://127.0.0.1:18093/order 10

# Spring Boot starter: two instances of the same jar
java -Dotel.service.name=inventory-api -Dpinpoint.application-type=1010 -Dserver.port=18095 \
     -jar examples/java/spring-boot-sample/target/pinpoint-otel-boot-sample-1.0.0.jar
java -Dotel.service.name=order-api -Dpinpoint.application-type=1010 -Dserver.port=18094 \
     -jar examples/java/spring-boot-sample/target/pinpoint-otel-boot-sample-1.0.0.jar
curl "http://127.0.0.1:18094/call?target=http://127.0.0.1:18095/ping"
```

Each process prints its sampler at startup, the callers print the `tracestate` they send, and
the callees print (or, for Spring Boot, echo) the `tracestate` they received:

```
SAMPLER PinpointTraceStateSampler{delegate=ParentBased{...}, pp=app:order-api;type:1010}
CALLER-SENT tracestate: pp=app:order-api;type:1010
CALLEE-SAW tracestate: pp=app:order-api;type:1010 -> server span tracestate: {pp=app:order-api;type:1010}
pong tracestate=pp=app:order-api;type:1010
```
