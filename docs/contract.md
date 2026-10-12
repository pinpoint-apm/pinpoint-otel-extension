# The Pinpoint `pp` tracestate contract

This page is language-neutral. It describes what a Pinpoint OTLP trace collector expects to
find in the W3C `tracestate` header, and the rules every implementation follows, whether it is
the [Java agent extension](../README.md) in this repository, the
[Java SDK sampler](java.md), or a sampler for another language.

## Why the caller writes the entry

Pinpoint draws the **caller → callee** edge on its server map from the `pp=...` entry it finds
in the trace state of the callee's SERVER span. The collector reads that entry as the span's
*parent application*. So the entry must be written by the **caller**, on the spans that leave
the caller's process, and the callee has nothing to do: every OpenTelemetry SDK exports a
SERVER span with the trace state it received.

Without the entry both applications are still stored and the call tree still joins them
through the trace id, but the server map shows the callee as called by `USER` and no edge
between the two applications.

The one place in the OpenTelemetry specification where a span's trace state can be changed at
creation time is the SDK **Sampler**: the trace state returned by `shouldSample` becomes the
trace state of the new span context, and the W3C trace context propagator then puts it on the
`tracestate` header of every outgoing request or message. Every implementation is therefore a
sampler that wraps the sampler the application already uses, keeps its decision and adds the
`pp` entry.

## Wire format

```
tracestate: pp=svc:<serviceName>;app:<applicationName>;type:<applicationType>
```

| Part | Meaning | Rules |
|---|---|---|
| key `pp` | vendor key, two lowercase letters like `dd`, `nr`, `dt`, `ot` | |
| `svc` | Pinpoint **service** name | Only a service **registered on the Pinpoint side**. Omit it to stay on Pinpoint's default service. Never derived from `service.namespace` |
| `app` | Pinpoint **application** name | Must equal the Resource `service.name` of the same process, or the process shows up as two nodes |
| `type` | numeric Pinpoint ServiceType code, e.g. `1010` for a WAS | Optional. When absent the collector uses its default for an OpenTelemetry server |

- Sub-keys are separated by `;`, sub-key and value by `:`. The order is **svc, app, type**;
  an absent part is skipped.
- When **both** `svc` and `app` are absent, **no entry is written**: `type` alone means nothing
  to the collector.
- A value containing `,` or `=` breaks the W3C `tracestate` syntax and SDKs drop such an entry
  silently. Pinpoint names (`[a-zA-Z0-9._-]`, at most 254 characters) are always safe.
- The collector ignores sub-keys it does not know, so the format can grow.

The exact values are pinned by
[`src/test/resources/pp-tracestate-contract.txt`](../src/test/resources/pp-tracestate-contract.txt)
(`contract-version: 1`). The Pinpoint collector keeps a byte-identical copy and asserts the
parsing direction against it; every implementation in this repository asserts the producing
direction against the same file.

| serviceName | applicationName | type | pp value |
|---|---|---|---|
| order-team | order-api | 1010 | `svc:order-team;app:order-api;type:1010` |
| order-team | order-api | – | `svc:order-team;app:order-api` |
| – | order-api | – | `app:order-api` |
| – | order-api | 1210 | `app:order-api;type:1210` |
| order-team | – | – | `svc:order-team` |
| order-team | – | 1010 | `svc:order-team;type:1010` |
| – | – | 1010 | (no entry) |
| – | – | – | (no entry) |

## Rules every implementation follows

1. **Never write the entry on SERVER and CONSUMER spans.** Those are the spans the collector
   reads the parent from: their trace state must be what arrived with the request or message,
   that is the caller's `pp` entry or nothing. An entry written there makes the collector record
   the receiver as its own parent and draw a self edge. Decide by span **kind** alone, not by
   whether the parent is remote: messaging instrumentations create CONSUMER spans under a local
   parent (the Kafka "receive" span with receive telemetry on), and an entry written there is
   inherited by the "process" span the collector stores.
2. **Write the entry on every other span** (CLIENT, PRODUCER, INTERNAL, root spans included).
   Every outgoing call gets its own CLIENT or PRODUCER span, and that span's trace state is what
   the propagator injects.
3. **Keep the delegate's sampling decision.** Layer the `pp` entry on the trace state the
   delegate returned (a consistent-probability sampler writes `ot=th:...`, for example), keep
   other vendors' entries and replace an existing `pp` entry. Update the trace state even on a
   DROP decision, so a later hop that samples again still carries the Pinpoint context.
4. **Place the wrapper outermost.** Wrapped inside a parent-based sampler, in its root sampler
   slot, it is never consulted for spans that have a parent, so CLIENT spans lose the entry.
5. **The W3C trace context propagator must be active.** A B3-only configuration carries no
   `tracestate`; a composite that includes `tracecontext` is fine.

## Collector side

| Component | Requirement |
|---|---|
| Pinpoint OTLP trace collector | a build that includes the `pp` tracestate parser (`PinpointTraceStateParser`) |
| `svc` on the collector side | a build that includes [pinpoint-apm/pinpoint#14416](https://github.com/pinpoint-apm/pinpoint/pull/14416): the collector resolves the sender's `pinpoint.serviceName` through its service lookup. Older collectors keep every OTLP span on the default service; with those, do not send `svc` |
| Unregistered `svc` | the collector rejects the sender's own spans (`service_not_found`) and caches the negative lookup for 10 minutes by default, so a newly registered service can take that long to be accepted |

The collector only ingests traces. Point metrics and logs exporters elsewhere or set them to
`none`.

## Implementations

| Language / runtime | Where | Form |
|---|---|---|
| Java, OpenTelemetry Java agent | [README](../README.md) | agent extension jar, no code change |
| Java, SDK configured by the application | [docs/java.md](java.md) | one `Sampler` class copied into the application, [examples/java](../examples/java) |
| Python | [docs/python.md](python.md) | reference implementation [python/](../python) (installed from the repository or copied; `OTEL_TRACES_SAMPLER=pinpoint` under auto-instrumentation, or the class in code), [examples/python](../examples/python) |
| Go, Node.js, .NET, or your own application | [docs/implementing.md](implementing.md) | implement the sampler yourself against the [conformance checklist](conformance.md) |
