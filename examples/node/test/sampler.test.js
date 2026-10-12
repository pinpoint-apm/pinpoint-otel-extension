'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const { context, propagation, trace, SpanKind, TraceFlags, ROOT_CONTEXT } = require('@opentelemetry/api');
const { TraceState, W3CTraceContextPropagator } = require('@opentelemetry/core');
const {
  AlwaysOffSampler, AlwaysOnSampler, BasicTracerProvider, InMemorySpanExporter, ParentBasedSampler,
  SamplingDecision, SimpleSpanProcessor,
} = require('@opentelemetry/sdk-trace-base');
const { AsyncLocalStorageContextManager } = require('@opentelemetry/context-async-hooks');

const { buildValue, PinpointTraceStateSampler } = require('../pp-tracestate-sampler');

// The wire contract file of this repository, shared with the Java extension and the collector.
const CONTRACT = path.resolve(__dirname, '../../../src/test/resources/pp-tracestate-contract.txt');

context.setGlobalContextManager(new AsyncLocalStorageContextManager().enable());
propagation.setGlobalPropagator(new W3CTraceContextPropagator());

function provider(sampler) {
  const exporter = new InMemorySpanExporter();
  const tp = new BasicTracerProvider({ sampler, spanProcessors: [new SimpleSpanProcessor(exporter)] });
  return { tracer: tp.getTracer('t'), exporter };
}

function ppByName(exporter) {
  const out = {};
  for (const span of exporter.getFinishedSpans()) out[span.name] = span.spanContext().traceState?.get('pp');
  return out;
}

const own = 'app:order-api;type:1010';

// A1
test('buildValue follows the contract file', () => {
  let cases = 0;
  for (const line of fs.readFileSync(CONTRACT, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const cols = line.split('|');
    assert.equal(cols.length, 4, line);
    const svc = cols[0] === '-' ? null : cols[0];
    const app = cols[1] === '-' ? null : cols[1];
    const type = cols[2] === '-' ? null : Number(cols[2]);
    const expected = cols[3] === '<null>' ? null : cols[3];
    assert.equal(buildValue(svc, app, type), expected, line);
    cases++;
  }
  assert.ok(cases >= 10);
});

// A2
test('rejects construction without service or application', () => {
  assert.throws(() => new PinpointTraceStateSampler(new AlwaysOnSampler(), null, null, 1010));
});

// B1
test('client, producer, internal carry pp; server, consumer do not', () => {
  const { tracer, exporter } = provider(new PinpointTraceStateSampler(new ParentBasedSampler({ root: new AlwaysOnSampler() }), null, 'order-api', 1010));
  for (const [name, kind] of [['root-internal', SpanKind.INTERNAL], ['client', SpanKind.CLIENT], ['producer', SpanKind.PRODUCER],
    ['server', SpanKind.SERVER], ['consumer', SpanKind.CONSUMER]]) {
    tracer.startSpan(name, { kind }).end();
  }
  assert.deepEqual(ppByName(exporter), { 'root-internal': own, client: own, producer: own, server: undefined, consumer: undefined });
});

// B2
test('server keeps the callers pp and its client child writes our own', () => {
  const { tracer, exporter } = provider(new PinpointTraceStateSampler(new ParentBasedSampler({ root: new AlwaysOnSampler() }), null, 'order-api', 1010));
  const parent = propagation.extract(ROOT_CONTEXT, {
    traceparent: '00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01',
    tracestate: 'pp=app:front;type:1010',
  });
  const server = tracer.startSpan('server', { kind: SpanKind.SERVER }, parent);
  context.with(trace.setSpan(parent, server), () => tracer.startSpan('client', { kind: SpanKind.CLIENT }).end());
  server.end();
  const got = ppByName(exporter);
  assert.equal(got.server, 'app:front;type:1010');
  assert.equal(got.client, own);
});

// B3
test('outgoing headers carry pp', () => {
  const { tracer } = provider(new PinpointTraceStateSampler(new ParentBasedSampler({ root: new AlwaysOnSampler() }), 'order-team', 'order-api', null));
  const client = tracer.startSpan('client', { kind: SpanKind.CLIENT });
  const headers = {};
  propagation.inject(trace.setSpan(ROOT_CONTEXT, client), headers);
  client.end();
  assert.ok(headers.traceparent);
  assert.equal(headers.tracestate, 'pp=svc:order-team;app:order-api');
});

// B4
test('keeps the delegates decision and trace state entries', () => {
  const sampler = new PinpointTraceStateSampler(new AlwaysOffSampler(), null, 'order-api', null);
  const parentContext = trace.setSpanContext(ROOT_CONTEXT, {
    traceId: '0af7651916cd43dd8448eb211c80319c', spanId: 'b7ad6b7169203331', isRemote: true,
    traceFlags: TraceFlags.SAMPLED, traceState: new TraceState('ot=th:8,pp=app:stale'),
  });
  const result = sampler.shouldSample(parentContext, '0af7651916cd43dd8448eb211c80319c', 'client', SpanKind.CLIENT, {}, []);
  assert.equal(result.decision, SamplingDecision.NOT_RECORD);
  assert.equal(result.traceState.get('ot'), 'th:8');          // other vendors' entries survive
  assert.equal(result.traceState.get('pp'), 'app:order-api'); // the same key is replaced
  assert.equal((result.traceState.serialize().match(/pp=/g) || []).length, 1);
});

// B5 (negative)
test('regression: wrapped inside ParentBased loses pp on child client spans', () => {
  const wrong = new ParentBasedSampler({ root: new PinpointTraceStateSampler(new AlwaysOnSampler(), null, 'order-api', 1010) });
  const { tracer, exporter } = provider(wrong);
  const server = tracer.startSpan('server', { kind: SpanKind.SERVER });
  context.with(trace.setSpan(ROOT_CONTEXT, server), () => tracer.startSpan('client', { kind: SpanKind.CLIENT }).end());
  server.end();
  assert.equal(ppByName(exporter).client, undefined);
});

// B6
test('invalid value is dropped silently', () => {
  const { tracer, exporter } = provider(new PinpointTraceStateSampler(new AlwaysOnSampler(), null, 'bad,name', null));
  tracer.startSpan('client', { kind: SpanKind.CLIENT }).end();
  assert.equal(ppByName(exporter).client, undefined);
});
