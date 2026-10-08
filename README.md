# pinpoint-otel-extension

An [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
extension that adds a Pinpoint `pp=...` entry to the W3C `tracestate` header. A Pinpoint OTLP
trace collector that receives spans from a downstream service can then identify the upstream
service, application and service type, and draw the link between the two on the server map.

It depends only on the OpenTelemetry SDK that the agent already ships. The jar is about 10 KB.

## How it works

At SDK startup the extension's `AutoConfigurationCustomizerProvider`
(`PinpointTraceStateAutoConfig`) wraps the configured `Sampler` with
`PinpointTraceStateSampler`. The wrapper keeps the delegate's sampling decision and adds a
`pp=svc:<svc>;app:<app>[;type:<code>]` entry to the trace state of every span. The W3C
`tracestate` propagator then emits that entry on every outgoing HTTP / gRPC request.

## Usage

```sh
java \
  -javaagent:opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=/path/to/pinpoint-otel-extension-<version>.jar \
  -Dotel.service.name=order-api \
  -Dotel.resource.attributes=pinpoint.applicationName=order-api,pinpoint.applicationType=1010 \
  -jar app.jar
```

Set the Pinpoint identifiers once, on the standard `OTEL_RESOURCE_ATTRIBUTES`. The same keys
drive both ends:

- The Pinpoint collector reads `pinpoint.applicationName` off the incoming Resource and uses
  it as the span's own `applicationName`.
- The extension reads the same key and writes
  `tracestate: pp=app:order-api;type:1010` on outgoing requests. The downstream collector
  parses this into the receiving span's parent application.

Deployments that only set the standard `service.name` resource attribute work too; both ends
use the same fallback chain for the application name. The service name is never derived from
`service.namespace` (see the table below).

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

## Wire format

```
tracestate: pp=svc:<serviceName>;app:<applicationName>;type:<serviceTypeCode>
```

- Key `pp`, following the two-letter vendor convention (`dd`, `nr`, `dt`, `ot`).
- Sub-keys separated by `;`, sub-key and value by `:`.
- Any sub-key may be omitted. The collector ignores unknown sub-keys, so the format can grow.

The exact values are pinned by `src/test/resources/pp-tracestate-contract.txt`
(`contract-version: 1`). The Pinpoint collector keeps an identical copy and asserts the parsing
direction against it.

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

The jar is `target/pinpoint-otel-extension-<version>.jar`. The `release` profile also attaches
the sources and javadoc jars:

```sh
./mvnw -B -Prelease verify
```

## Getting the jar from a Maven repository

Once published, pull the jar into your build without putting it on the application classpath.

Maven:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions>
    <execution>
      <id>copy-pinpoint-otel-extension</id>
      <phase>package</phase>
      <goals><goal>copy</goal></goals>
      <configuration>
        <artifactItems>
          <artifactItem>
            <groupId>com.navercorp.pinpoint</groupId>
            <artifactId>pinpoint-otel-extension</artifactId>
            <version>${pinpoint-otel-extension.version}</version>
            <outputDirectory>${project.build.directory}/otel</outputDirectory>
          </artifactItem>
        </artifactItems>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Gradle:

```kotlin
val otelExtension by configurations.creating
dependencies { otelExtension("com.navercorp.pinpoint:pinpoint-otel-extension:<version>") }
tasks.register<Copy>("copyOtelExtension") {
    from(otelExtension)
    into(layout.buildDirectory.dir("otel"))
}
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
