# Changelog

## Unreleased

## 1.0.0 (2026-10-21)

First release of the standalone extension.

- Documentation of the service name rules the Pinpoint side enforces: 3 to 254 characters of
  `[a-zA-Z0-9._-]`, and none of the reserved names `DEFAULT`, `ERROR`, `UNKNOWN`, `NULL`
  ([pinpoint-apm/pinpoint#14457](https://github.com/pinpoint-apm/pinpoint/issues/14457)).
- Documentation for applications that do not run the Java agent: `docs/contract.md` (the
  language-neutral wire format and rules) and `docs/java.md` (with and without the agent), with
  runnable projects under `examples/java` that CI builds and tests against the contract file.
- Python: a reference implementation under `python/` (an `opentelemetry_traces_sampler` entry
  point, `OTEL_TRACES_SAMPLER=pinpoint`, installable from the repository), `docs/python.md` and
  `examples/python`.
- Go and Node.js reference samplers under `examples/go` and `examples/node`, each with the
  conformance tests and an instrumented caller/callee pair, built on CI.
- `docs/conformance.md` (the checklist every implementation must pass, mapped to the Java and
  Python tests), `docs/implementing.md` (implementing the sampler in another language or
  application, with a prompt for AI coding assistants) and `AGENTS.md`.
- Standalone project for the Pinpoint `pp` tracestate extension, moved out of the Pinpoint
  repository. Depends only on the OpenTelemetry SDK (provided by the agent at runtime).
- Wire contract pinned by `pp-tracestate-contract.txt` (contract-version 1).
- `svc` is sent only when `pinpoint.serviceName` is set explicitly; the `service.namespace`
  fallback was removed. The Pinpoint collector resolves the sender's `pinpoint.serviceName`
  through its service lookup ([pinpoint-apm/pinpoint#14416](https://github.com/pinpoint-apm/pinpoint/pull/14416)),
  so the sender's own node and the parent its callees record share the same service; a
  namespace that is not a registered service would otherwise be rejected or split the node.
- The sampler no longer writes the `pp` entry on SERVER and CONSUMER spans. Those are the
  spans the collector reads the parent application from, so their trace state must be what
  arrived with the request or message (the caller's `pp`, or nothing); writing this process's
  identity there made the collector record the receiver as its own parent and draw a self
  edge. The rule is by kind, not by remote parent: Kafka receive telemetry creates the
  CONSUMER "process" span under a local "receive" span, and an entry written there was
  inherited by the stored span. CLIENT, PRODUCER and INTERNAL spans still carry this
  process's `pp`, so what the propagator injects downstream is unchanged.
