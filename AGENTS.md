# AGENTS.md

Guidance for AI coding agents working in, or reading from, this repository.

## What this repository is

The home of the Pinpoint `pp` tracestate contract: how an OpenTelemetry application tells a
Pinpoint OTLP trace collector who called it, so the server map draws the caller → callee edge.

| Path | Role |
|---|---|
| `docs/contract.md` | **the specification**: wire format, the five rules, collector compatibility |
| `src/test/resources/pp-tracestate-contract.txt` | the value vectors, `contract-version` on line 1. Byte-identical copy in the Pinpoint collector. Read it in tests, never copy the rows |
| `docs/conformance.md` | the checklist every implementation must pass, with the test that pins each case in Java and Python |
| `docs/implementing.md` | how to implement the sampler in another language or application, with the SDK hook per language and a prompt template |
| `src/main/java` | the Java agent extension (the shipped artifact): `PinpointTraceStateSampler`, `PinpointTraceStateAutoConfig` |
| `examples/java/sdk-sample`, `examples/java/spring-boot-sample` | Java reference for applications without the agent; the guide `docs/java.md` copies its code from here |
| `python/` | Python reference implementation and package; the guide `docs/python.md` copies its code from here |
| `examples/python` | two auto-instrumented Python processes |

## Rules that are easy to get wrong

- Never write the `pp` entry on SERVER or CONSUMER spans. Decide by span kind alone.
- The wrapper is the outermost sampler; the parent-based sampler goes inside it.
- Keep the delegate's decision and its trace state entries; replace only `pp`.
- `applicationName` must equal the Resource `service.name`.

## Working here

- The guides' code blocks are copies of files under `examples/` and `python/`. Change the file,
  run its tests, then update the guide; never edit a code block on its own.
- A change to the wire format changes `pp-tracestate-contract.txt` in this repository **and** in
  the Pinpoint collector, and bumps `contract-version`.
- Build and test: Java extension `./mvnw -B verify` (JDK 11+, Java 8 bytecode); Java examples
  `./mvnw -B -f examples/java/<name>/pom.xml verify` (JDK 17); Python
  `cd python && pip install -e ".[test]" && python -m pytest`.
- Prose and code comments are in English. Do not add Pinpoint libraries to the examples or to
  `python/`; they depend on the OpenTelemetry SDK only.
