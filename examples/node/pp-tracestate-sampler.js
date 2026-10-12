'use strict';
/**
 * Pinpoint "pp" tracestate sampler for the OpenTelemetry JavaScript SDK.
 *
 * Wraps the sampler the application already uses, keeps its decision and adds
 * pp=svc:<serviceName>;app:<applicationName>;type:<applicationType> to the trace state of the
 * spans this process originates (CLIENT, PRODUCER, INTERNAL). SERVER and CONSUMER spans are left
 * untouched: their trace state is what arrived with the request and names the caller.
 * Depends on @opentelemetry/api, @opentelemetry/core and @opentelemetry/sdk-trace-base only.
 * See docs/contract.md in the repository for the wire format and the rules.
 */
const { SpanKind, trace } = require('@opentelemetry/api');
const { TraceState } = require('@opentelemetry/core');

const KEY = 'pp';

/** Order svc, app, type; absent parts are skipped; null when both svc and app are absent. */
function buildValue(svc, app, type) {
  const parts = [];
  if (svc) parts.push(`svc:${svc}`);
  if (app) parts.push(`app:${app}`);
  if (parts.length === 0) return null;
  if (type !== undefined && type !== null) parts.push(`type:${Math.trunc(type)}`);
  return parts.join(';');
}

class PinpointTraceStateSampler {
  /**
   * @param {import('@opentelemetry/sdk-trace-base').Sampler} delegate the sampler you use today, e.g. new ParentBasedSampler({ root: new AlwaysOnSampler() })
   * @param {string|null} serviceName Pinpoint service name; only a service registered in Pinpoint, null for the default service
   * @param {string|null} applicationName Pinpoint application name; the same value as the Resource service.name
   * @param {number|null} [applicationType] Pinpoint ServiceType code (optional)
   */
  constructor(delegate, serviceName, applicationName, applicationType) {
    if (!delegate) throw new Error('delegate is required');
    const value = buildValue(serviceName, applicationName, applicationType);
    if (value === null) throw new Error('serviceName or applicationName must be provided');
    this._delegate = delegate;
    this._value = value;
  }

  shouldSample(context, traceId, spanName, spanKind, attributes, links) {
    const result = this._delegate.shouldSample(context, traceId, spanName, spanKind, attributes, links);
    if (spanKind === SpanKind.SERVER || spanKind === SpanKind.CONSUMER) {
      return result; // receiving side: keep the incoming trace state
    }
    // The tracer uses result.traceState when set, else the parent's; layer on whichever applies
    const current = result.traceState || trace.getSpanContext(context)?.traceState || new TraceState();
    if (current.get(KEY) === this._value) return result;
    // TraceState is immutable; set() returns a new one and replaces an existing key.
    // An invalid value is rejected by TraceState and the entry is dropped silently.
    return { decision: result.decision, attributes: result.attributes, traceState: current.set(KEY, this._value) };
  }

  toString() {
    return `PinpointTraceStateSampler{delegate=${this._delegate.toString()}, pp=${this._value}}`;
  }
}

module.exports = { KEY, buildValue, PinpointTraceStateSampler };
