# pinpoint-otel-extension

An [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
extension that adds a Pinpoint `pp=...` entry to the W3C `tracestate` header. A Pinpoint OTLP
trace collector that receives spans from a downstream service can then identify the upstream
service, application and service type, and draw the link between the two on the server map.

It depends only on the OpenTelemetry SDK that the agent already ships. The jar is about 10 KB.

The repository also holds the language-neutral description of the `pp` entry and the guides
for applications that do not run the Java agent, Java and other languages:

| | |
|---|---|
| [docs/contract.md](docs/contract.md) | the wire format and the rules every implementation follows |
| [docs/java.md](docs/java.md) | Java: with the agent (this extension) and without it (one `Sampler` class in the application, for a manual SDK, `AutoConfiguredOpenTelemetrySdk` or the Spring Boot starter) |
| [docs/python.md](docs/python.md) | Python: the reference implementation under [python/](python/), installed from this repository or copied, not published to a package index (`OTEL_TRACES_SAMPLER=pinpoint` with auto-instrumentation, no code change; or its sampler class in code) |
| [examples/go](examples/go), [examples/node](examples/node) | Go and Node.js reference samplers with the conformance tests and an instrumented caller/callee pair each |
| [docs/implementing.md](docs/implementing.md) | implementing the sampler in another language or in your own application, with an AI assistant or without |
| [docs/conformance.md](docs/conformance.md) | the checklist every implementation must pass |
| [examples/](examples/) | runnable projects the guides copy their code from, built on CI |

## How it works

At SDK startup the extension's `AutoConfigurationCustomizerProvider`
(`PinpointTraceStateAutoConfig`) wraps the configured `Sampler` with
`PinpointTraceStateSampler`. The wrapper keeps the delegate's sampling decision and adds a
`pp=svc:<svc>;app:<app>[;type:<code>]` entry to the trace state of the spans this process
originates (CLIENT, PRODUCER and INTERNAL spans). The W3C `tracestate` propagator then emits
that entry on every outgoing HTTP / gRPC request or message. SERVER and CONSUMER spans are
left untouched: their trace state is what arrived with the request or message, and its `pp`
entry, if any, names the caller — that is what the Pinpoint collector reads off the exported
span to draw the caller → callee edge.

## Usage

```sh
java \
  -javaagent:opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=/path/to/pinpoint-otel-extension-<version>.jar \
  -Dotel.service.name=order-api \
  -Dotel.resource.attributes=pinpoint.applicationType=1010 \
  -jar app.jar
```

`otel.service.name` is enough for the application name: the extension writes
`tracestate: pp=app:order-api;type:1010` on outgoing requests, and the Pinpoint collector reads
the same name off the incoming Resource for the sender's own spans, so the two ends agree. The
downstream collector parses the entry into the receiving span's parent application. The
Pinpoint-specific keys below are for the cases the standard attributes do not cover; the service
name is never derived from `service.namespace`.

### Configuration keys

| Pinpoint key (primary) | Fallback | Notes |
|---|---|---|
| `pinpoint.applicationName` | `otel.service.name`, then `service.name` (resource attribute) | At least one of these must resolve |
| `pinpoint.serviceName` | none | Pinpoint service grouping. Sent only when set explicitly, and never derived from `service.namespace`. Set it to a service that is **registered on the Pinpoint side** (`POST /api/v2/services`): the collector resolves the same attribute on the sender's own spans through its service lookup, so an unregistered name is rejected there (`service_not_found`) and the `svc` carried to the callees would point at a service that holds no node. The collector caches a negative lookup for 10 minutes by default, so a newly registered service can take that long to be accepted. Leave it unset to stay on Pinpoint's default service |
| `pinpoint.applicationType` | none | Numeric Pinpoint ServiceType code, e.g. `1010` for a WAS. Sender-only. When absent, the collector uses its default for an OpenTelemetry server |

For each value the extension tries the dedicated config property first
(`-Dpinpoint.applicationName=...`), then the `OTEL_RESOURCE_ATTRIBUTES` entry of the same name,
and only then moves to the next key in the list.

### Warning: `-Dpinpoint.*` overrides split the node

A `-Dpinpoint.applicationName=...` override changes only what goes into the outgoing
`tracestate`. The process's own spans still carry the Resource built from
`OTEL_RESOURCE_ATTRIBUTES`. If the two disagree, the server map shows the same process as two
nodes. Change `OTEL_RESOURCE_ATTRIBUTES` instead; keep the `-D` override for tests.

### Disabled mode

If neither `pinpoint.serviceName`, `pinpoint.applicationName`, `otel.service.name` nor
`service.name` resolves, the extension leaves the SDK's sampler untouched and logs one INFO line. Agent startup is not
affected.

## Without the Java agent

An application that configures the OpenTelemetry SDK itself (a manual `SdkTracerProvider`,
`AutoConfiguredOpenTelemetrySdk` or `opentelemetry-spring-boot-starter`) cannot load this jar,
which only works on the agent's extension path. It gets the same result by copying one
`Sampler` class into its own code and wrapping its sampler with it; no dependency on this
artifact is needed. See [docs/java.md](docs/java.md#2-without-the-java-agent) and the
projects under [examples/java](examples/java).

## Wire format

```
tracestate: pp=svc:<serviceName>;app:<applicationName>;type:<serviceTypeCode>
```

- Key `pp`, following the two-letter vendor convention (`dd`, `nr`, `dt`, `ot`).
- Sub-keys separated by `;`, sub-key and value by `:`.
- Any sub-key may be omitted. The collector ignores unknown sub-keys, so the format can grow.

The exact values are pinned by `src/test/resources/pp-tracestate-contract.txt`
(`contract-version: 1`). The Pinpoint collector keeps an identical copy and asserts the parsing
direction against it, and so do the examples. The rules behind the format are in
[docs/contract.md](docs/contract.md).

## Compatibility

| Component | Supported |
|---|---|
| OpenTelemetry Java agent | 2.x (the SDK the agent bundles is what the extension runs on) |
| Compiled against | OpenTelemetry SDK 1.53.0, Java 8 bytecode |
| JVM of the instrumented application | Java 8 or newer |
| Pinpoint OTLP trace collector | Builds that include the `pp` tracestate parser (`PinpointTraceStateParser`) |
| `pinpoint.serviceName` on the collector side | Builds that include [pinpoint-apm/pinpoint#14416](https://github.com/pinpoint-apm/pinpoint/pull/14416) (the collector resolves the sender's `pinpoint.serviceName` through its service lookup). Older collectors keep every OTLP span on the default service; with those, leave `pinpoint.serviceName` unset so the `svc` sub-key is not sent |

Do not load this jar together with an older build of the same extension that was shipped inside
the Pinpoint repository; two service entries would wrap the sampler twice.

## Build

Building needs JDK 11 or newer; the sources are compiled with `--release 8`.

```sh
./mvnw -B verify
```

The jar is `target/pinpoint-otel-extension-<version>.jar`; put it on the agent's extension path
(`-Dotel.javaagent.extensions=...`), not on the application classpath. The `release` profile
also attaches the sources and javadoc jars:

```sh
./mvnw -B -Prelease verify
```

## Verifying a deployment

Capture an outgoing request from the instrumented application and look for:

```
tracestate: pp=app:order-api;type:1010
```

On the Pinpoint side the receiving span then carries `parentApplicationName = order-api` and
`parentApplicationServiceType = 1010`, and the server map draws the upstream node and the edge.

## License

Apache License 2.0. See [LICENSE](LICENSE).
