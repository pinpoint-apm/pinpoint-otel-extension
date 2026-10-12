# Node.js example

`pp-tracestate-sampler.js` is the sampler (one file, depends on `@opentelemetry/api`,
`@opentelemetry/core` and `@opentelemetry/sdk-trace-base`) with the conformance tests under
`test/`; `callee.js` and `caller.js` are two processes instrumented by the NodeSDK through
`otel-setup.js`.

```sh
npm install
npm test

# B, then A; both export OTLP/gRPC to 127.0.0.1:9998
OTEL_SERVICE_NAME=inventory-api node -r ./otel-setup.js callee.js 18099 &
OTEL_SERVICE_NAME=order-api node -r ./otel-setup.js caller.js http://127.0.0.1:18099/order 10
```

Expected output:

```
SAMPLER PinpointTraceStateSampler{delegate=ParentBased{root=AlwaysOnSampler, ...}, pp=app:order-api;type:1220}
CALLER-GOT pong tracestate=pp=app:order-api;type:1220
CALLER-DONE ok=10/10
CALLEE-SAW tracestate: pp=app:order-api;type:1220
```

On the Pinpoint server map of `inventory-api` the edge `order-api → inventory-api` appears.

To use the sampler in your own service, copy `pp-tracestate-sampler.js` and pass an instance as
the `sampler` of `NodeSDK` (or `NodeTracerProvider`) with your parent-based sampler as its
delegate, as `otel-setup.js` does. `OTEL_TRACES_SAMPLER` knows the built-in samplers only, so
the wiring is in code.
