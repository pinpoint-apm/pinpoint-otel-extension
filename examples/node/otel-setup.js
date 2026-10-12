'use strict';
/**
 * Wires the pp sampler into the NodeSDK with OTLP/gRPC export to a Pinpoint collector and the
 * http instrumentation. Shared by caller.js and callee.js; require it first.
 *
 *   node -r ./otel-setup.js callee.js 18099     (OTEL_SERVICE_NAME names the application)
 */
const { NodeSDK } = require('@opentelemetry/sdk-node');
const { OTLPTraceExporter } = require('@opentelemetry/exporter-trace-otlp-grpc');
const { HttpInstrumentation } = require('@opentelemetry/instrumentation-http');
const { AlwaysOnSampler, ParentBasedSampler } = require('@opentelemetry/sdk-trace-base');
const { PinpointTraceStateSampler } = require('./pp-tracestate-sampler');

const applicationName = process.env.OTEL_SERVICE_NAME; // the NodeSDK puts the same value into the Resource service.name
const base = new ParentBasedSampler({ root: new AlwaysOnSampler() }); // the sampler you use today
const sampler = new PinpointTraceStateSampler(base, null, applicationName, 1220);

const sdk = new NodeSDK({
  sampler, // outermost in the chain; the NodeSDK keeps the default W3C trace context propagator
  traceExporter: new OTLPTraceExporter({ url: 'http://127.0.0.1:9998' }),
  instrumentations: [new HttpInstrumentation()],
});
sdk.start();
console.log('SAMPLER ' + sampler.toString());
process.on('SIGINT', () => sdk.shutdown().finally(() => process.exit(0)));
module.exports = { sdk };
