# Go example

`pptracestate` is the sampler (one file, depends on the OpenTelemetry Go SDK only) with the
conformance tests; `cmd/callee` and `cmd/caller` are two otelhttp-instrumented processes.

```sh
go test ./...

# B, then A; both export OTLP/gRPC to 127.0.0.1:9998
go run ./cmd/callee inventory-api 18098 &
go run ./cmd/caller order-api http://127.0.0.1:18098/order 10
```

Expected output:

```
SAMPLER PinpointTraceStateSampler{delegate=ParentBased{root:AlwaysOnSampler,...}, pp=app:order-api;type:1220}
CALLER-GOT pong tracestate=pp=app:order-api;type:1220
CALLER-DONE ok=10/10
CALLEE-SAW tracestate: pp=app:order-api;type:1220
```

On the Pinpoint server map of `inventory-api` the edge `order-api → inventory-api` appears.

To use the sampler in your own module, copy `pptracestate/sampler.go` and wire it as in
`internal/otelsetup/setup.go`: `sdktrace.WithSampler(sampler)` with the parent-based sampler as
its delegate, and `propagation.TraceContext{}` among the propagators.
