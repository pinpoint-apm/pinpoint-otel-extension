# Conformance checklist

What an implementation of the [`pp` tracestate contract](contract.md) must do, in a form that
does not depend on the language. The two reference implementations in this repository pass every
row, and the last two columns name the test that pins each row, so a new implementation can copy
the cases one by one. An implementation is done when every row passes, not before.

| Reference | Tests |
|---|---|
| Java, `examples/java/sdk-sample` | [`PinpointTraceStateSamplerTest`](../examples/java/sdk-sample/src/test/java/sample/PinpointTraceStateSamplerTest.java) |
| Python, `python/` | [`tests/test_sampler.py`](../python/tests/test_sampler.py) |
| Go, `examples/go` | [`pptracestate/sampler_test.go`](../examples/go/pptracestate/sampler_test.go) |
| Node.js, `examples/node` | [`test/sampler.test.js`](../examples/node/test/sampler.test.js) |

The Java and Python test names are in the tables below; the Go and Node.js tests carry the case
ids (A1, B2, ...) in a comment above each test and the same names in their language's spelling.

## A. Value vectors

Build the value for every row of
[`src/test/resources/pp-tracestate-contract.txt`](../src/test/resources/pp-tracestate-contract.txt)
and compare. Read the file in the test rather than copying the rows: the file is the contract,
and `contract-version` on its first line tells which revision you implement.

Format of the file: columns `serviceName | applicationName | applicationType | pp value`, `-`
means absent, an empty column means present but empty (treat as absent), `<null>` in the last
column means no entry is written.

| | Java | Python |
|---|---|---|
| A1 every row of the file | `buildValueProducesEveryContractValue` | `test_build_value_follows_the_contract_file` |
| A2 constructing the sampler with neither service nor application name is an error | `rejectsConstructionWithoutServiceOrApplication` | `test_rejects_construction_without_svc_and_app` |

## B. Behaviour

Run each case through a real tracer provider with the wrapper as its sampler and an in-memory
exporter, and read `pp` off the exported span's trace state. `app:order-api;type:1010` below is
the entry of the process under test.

| Case | Setup | Expect | Java | Python |
|---|---|---|---|---|
| B1 kinds | Wrapper around `ParentBased(AlwaysOn)`. Start and end one root span of each kind: INTERNAL, CLIENT, PRODUCER, SERVER, CONSUMER | `pp` = own entry on INTERNAL, CLIENT, PRODUCER; **absent** on SERVER and CONSUMER | `clientAndInternalSpansCarryPp_serverAndConsumerSpansDoNot` | `test_client_producer_internal_carry_pp_server_consumer_do_not` |
| B2 incoming entry | Extract `traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01`, `tracestate: pp=app:front;type:1010` with the W3C propagator; start a SERVER span with that parent; under it start a CLIENT span | SERVER span `pp` = `app:front;type:1010` (the caller's, untouched); CLIENT span `pp` = own entry | `serverSpanKeepsTheCallersPp_andItsClientChildWritesOurOwn` | `test_server_keeps_the_callers_pp_and_its_client_child_writes_our_own` |
| B3 header | Wrapper with svc `order-team`, app `order-api`, no type. Make a CLIENT span current and inject with the W3C propagator into a map | `traceparent` present; `tracestate` = `pp=svc:order-team;app:order-api` | `outgoingHeadersCarryPp` | `test_outgoing_headers_carry_pp` |
| B4 delegate | Wrapper around `AlwaysOff`. Parent span context with trace state `ot=th:8, pp=app:stale`. Call `shouldSample` for a CLIENT span | decision = DROP (the delegate's); result trace state has `ot` = `th:8` and `pp` = own entry (replaced, not duplicated) | `keepsTheDelegatesDecisionAndTraceStateEntries` | `test_keeps_the_delegates_decision_and_other_entries` |
| B5 wrong placement (negative) | `ParentBased(Wrapper(AlwaysOn))`, i.e. the wrapper in the root sampler slot. Start a SERVER span, under it a CLIENT span | CLIENT span has **no** `pp`. This case documents the mistake; your wiring must put the wrapper outermost so that B1 and B2 hold | `regression_wrappedInsideParentBasedLosesPpOnChildClientSpans` | `test_regression_wrapped_inside_parentbased_loses_pp_on_child_client_spans` |
| B6 invalid value | Wrapper with app `bad,name`. Start a CLIENT span | no `pp` (the SDK drops the invalid entry); nothing throws | – (the Java `TraceStateBuilder` drops it the same way; not pinned) | `test_invalid_value_is_dropped_silently` (Go and Node.js pin it too) |

Every SDK's W3C propagator writes the span's trace state as is, so B3 follows from B1 once the
propagator is `tracecontext`; keep the case anyway, it catches a B3-only configuration (B3
propagators, no `tracestate`).

## C. Configuration (only when you add an environment or SPI entry point)

| Case | Expect | Java (agent extension) | Python |
|---|---|---|---|
| C1 keys | application name from `pinpoint.applicationName`, then `otel.service.name` / `OTEL_SERVICE_NAME`, then `service.name`; service name from `pinpoint.serviceName` only; type from `pinpoint.applicationType` | `PinpointTraceStateAutoConfigTest` | `TestFactory::test_reads_env_and_wraps_the_named_delegate`, `test_pinpoint_keys_in_resource_attributes_win` |
| C2 nothing resolves | the configured sampler is returned unchanged, startup is not affected | `AutoConfiguredSdkIntegrationTest::leavesSamplerUntouchedWhenNothingResolves` | `test_noop_when_nothing_resolves` |
| C3 wraps, does not replace | the sampler the configuration would have produced is the delegate (Java); in Python, where the entry point replaces the sampler, the delegate is named by `OTEL_TRACES_SAMPLER_ARG` and defaults to the SDK default | `AutoConfiguredSdkIntegrationTest` | `test_default_delegate_is_parentbased_always_on`, `test_unknown_delegate_is_an_error` |

## D. End to end

Not a unit test, but the final check: two processes, A calling B over HTTP, both exporting to a
Pinpoint collector. On the server map of B the edge `A → B` appears with the call count, B shows
no edge to itself, and in the call tree both applications are in one tree. The
[`examples/`](../examples) show the setup for Java, Python, Go and Node.js.
