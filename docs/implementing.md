# Implementing the `pp` sampler in your own application or language

The sampler is about sixty lines in any language, and most of it is the same everywhere. This
page is for the case where neither the Java agent extension nor the Java and Python reference
code fits as is: another language, or an application whose SDK wiring you want to own. It is
written so that you can hand it, together with [contract.md](contract.md) and
[conformance.md](conformance.md), to an AI coding assistant and get a working implementation
back.

## 1. What you are building

A **sampler wrapper**. It takes the sampler the application already uses, keeps its decision,
and adds `pp=<value>` to the trace state of the spans this process originates. Nothing else:
no exporter, no processor, no change to the callee.

| Input | Where it comes from |
|---|---|
| `delegate` | the sampler you use today (usually the SDK default, parent-based always-on) |
| `applicationName` | the same value as the Resource `service.name` |
| `serviceName` | only a service registered in Pinpoint; otherwise absent |
| `applicationType` | optional numeric ServiceType code |

The value is `svc:<serviceName>;app:<applicationName>;type:<applicationType>` with absent parts
skipped, no entry when both `svc` and `app` are absent. The exact rows are in
[`pp-tracestate-contract.txt`](../src/test/resources/pp-tracestate-contract.txt).

## 2. Steps

1. **Read [contract.md](contract.md).** The five rules there are the whole specification.
2. **Find the hook** in your SDK: the sampler interface, how it returns an updated trace state,
   and how a parent-based sampler delegates (see the table in section 3).
3. **Write the class** by translating one of the references:
   Java [`PinpointTraceStateSampler.java`](../examples/java/sdk-sample/src/main/java/sample/PinpointTraceStateSampler.java),
   Python [`pinpoint_otel_sampler/__init__.py`](../python/src/pinpoint_otel_sampler/__init__.py).
   The shape is always:
   ```
   result = delegate.shouldSample(...)
   if kind is SERVER or CONSUMER: return result            # rule 1
   ts = result.traceState or parent.traceState or empty    # rule 3: layer on the delegate's
   ts = ts.put("pp", value)                                # replace an existing pp, keep others
   return result with traceState = ts                      # decision unchanged
   ```
4. **Wire it outermost**: the wrapper is the sampler you give the tracer provider, and the
   parent-based sampler is *inside* it, never the other way round (rule 4). Make sure the W3C
   trace context propagator is active (rule 5).
5. **Run the [conformance checklist](conformance.md)**: the value vectors from the contract
   file, and the behaviour cases B1 to B6 through a real tracer provider with an in-memory
   exporter. Translate the reference tests; keep the case names so a reader can map them back.
6. **Check end to end**: two processes, A calling B, exporting to a Pinpoint collector. The edge
   `A → B` on B's server map, no self edge on B.

## 3. The hook per SDK

| SDK | Sampler hook | Updated trace state | Parent trace state | Parent-based wrapper (keep it inside) | Wiring | Status |
|---|---|---|---|---|---|---|
| Java | `io.opentelemetry.sdk.trace.samplers.Sampler#shouldSample` | `SamplingResult#getUpdatedTraceState(parentTraceState)` | passed in as the argument | `Sampler.parentBased(root)` | `SdkTracerProvider.builder().setSampler(...)`; autoconfigure `addSamplerCustomizer`; Spring Boot starter `AutoConfigurationCustomizerProvider` bean; Java agent: this extension | reference, verified, [java.md](java.md) |
| Python | `opentelemetry.sdk.trace.sampling.Sampler.should_sample` | `SamplingResult(decision, attributes, trace_state)` | **not passed by the tracer**; read `get_current_span(parent_context).get_span_context().trace_state` when the delegate's result has none. `TraceState` is immutable: `update()` upserts, `add()` refuses an existing key | `ParentBased(root)` | `TracerProvider(sampler=...)`; `OTEL_TRACES_SAMPLER=<entry point>` (the entry point *replaces* the sampler, so build the delegate yourself) | reference, verified, [python.md](python.md) |
| Go | `sdktrace.Sampler.ShouldSample(SamplingParameters)` | `SamplingResult{Decision, Attributes, Tracestate}` | `trace.SpanContextFromContext(p.ParentContext).TraceState()`; `TraceState.Insert(key, value)` returns a new value and an error for invalid input | `sdktrace.ParentBased(root)` | `sdktrace.NewTracerProvider(sdktrace.WithSampler(...))`; no environment entry point for custom samplers | from the SDK API, not yet verified here |
| Node.js | `Sampler.shouldSample(context, traceId, spanName, spanKind, attributes, links)` (`@opentelemetry/sdk-trace-base`) | `SamplingResult.traceState` | `trace.getSpanContext(context)?.traceState`; `TraceState.set(key, value)` (`@opentelemetry/core`) returns a new value | `ParentBasedSampler({ root })` | `NodeSDK({ sampler })` / `NodeTracerProvider({ sampler })`; `OTEL_TRACES_SAMPLER` knows the built-ins only | from the SDK API, not yet verified here |
| .NET | `OpenTelemetry.Trace.Sampler.ShouldSample(in SamplingParameters)` | `new SamplingResult(decision, attributes, traceStateString)` | `samplingParameters.ParentContext.TraceState` (a string; append `,pp=...`, replace an existing `pp` member) | `ParentBasedSampler(root)` | `TracerProviderBuilder.SetSampler(...)`; auto-instrumentation: a plugin's `ConfigureTracerProvider` | from the SDK API, not yet verified here |

Rule 1 is the same everywhere: decide by span kind, not by whether the parent is remote.

## 4. Working with an AI coding assistant

Give the assistant the specification and the tests, not a description of them. A prompt that has
worked as a starting point:

```
Implement a Pinpoint "pp" tracestate sampler for <language / framework> in <my project>.

Specification: docs/contract.md (wire format and five rules) and
src/test/resources/pp-tracestate-contract.txt (value vectors) from
https://github.com/pinpoint-apm/pinpoint-otel-extension.
Reference implementation to translate: <examples/java/sdk-sample/.../PinpointTraceStateSampler.java
or python/src/pinpoint_otel_sampler/__init__.py>.

Requirements:
- A sampler that wraps the sampler the project uses today (<name it>) and keeps its decision.
- Wire it as the outermost sampler of the tracer provider; keep the W3C trace context propagator.
- Tests for every case in docs/conformance.md, sections A and B, through a real tracer
  provider and an in-memory exporter; read the contract file in the test instead of copying rows.
- Application name = <value>, same as the Resource service.name; no service name; type <code or none>.

Do not write the entry on SERVER or CONSUMER spans. Do not put the wrapper inside the
parent-based sampler. Finish only when all conformance cases pass.
```

Then check the result against the two mistakes assistants make most often: the wrapper placed
in the root slot of the parent-based sampler (case B5 of the checklist passes for the wrong
reason while B1 and B2 fail), and the entry written on SERVER spans "so the callee is identified
too" (B1 and B2 fail, and the collector draws a self edge).

## 5. Verifying without a collector

Switch the exporter to the SDK's console or logging exporter and look at the exported spans:
CLIENT spans show `pp=...` in their trace state, SERVER spans show only what came in. Or capture
one outgoing request and look for the `tracestate: pp=...` header. Both are enough to know the
collector will draw the edge; the collector side needs no configuration beyond a build that
understands the contract version (see [contract.md](contract.md#collector-side)).
