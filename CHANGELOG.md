# Changelog

## Unreleased

- Standalone project for the Pinpoint `pp` tracestate extension, moved out of the Pinpoint
  repository. Depends only on the OpenTelemetry SDK (provided by the agent at runtime).
- Wire contract pinned by `pp-tracestate-contract.txt` (contract-version 1).
- `svc` is sent only when `pinpoint.serviceName` is set explicitly; the `service.namespace`
  fallback was removed. The Pinpoint collector resolves the sender's `pinpoint.serviceName`
  through its service lookup ([pinpoint-apm/pinpoint#14416](https://github.com/pinpoint-apm/pinpoint/pull/14416)),
  so the sender's own node and the parent its callees record share the same service; a
  namespace that is not a registered service would otherwise be rejected or split the node.
