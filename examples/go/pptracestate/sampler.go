// Package pptracestate adds the Pinpoint "pp" tracestate entry to the spans a process originates.
//
// Sampler wraps the sampler the application already uses, keeps its decision and adds
// pp=svc:<serviceName>;app:<applicationName>;type:<applicationType> to the trace state of
// CLIENT, PRODUCER and INTERNAL spans. SERVER and CONSUMER spans are left untouched: their trace
// state is what arrived with the request and names the caller. Depends on the OpenTelemetry Go
// SDK only. See docs/contract.md in the repository for the wire format and the rules.
package pptracestate

import (
	"fmt"
	"strconv"
	"strings"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

// Key is the tracestate vendor key.
const Key = "pp"

// BuildValue builds the pp value: svc, app, type in that order, absent parts skipped. It returns
// "" when both svc and app are absent, in which case no entry is written.
func BuildValue(svc, app string, typ *int) string {
	parts := make([]string, 0, 3)
	if svc != "" {
		parts = append(parts, "svc:"+svc)
	}
	if app != "" {
		parts = append(parts, "app:"+app)
	}
	if len(parts) == 0 {
		return ""
	}
	if typ != nil {
		parts = append(parts, "type:"+strconv.Itoa(*typ))
	}
	return strings.Join(parts, ";")
}

// Sampler is the wrapper. Create it with New and give it to sdktrace.WithSampler as the
// outermost sampler; the parent-based sampler goes inside it.
type Sampler struct {
	delegate sdktrace.Sampler
	value    string
}

// New wraps delegate. serviceName is only a service registered in Pinpoint ("" to stay on the
// default service), applicationName must equal the Resource service.name, typ is the optional
// Pinpoint ServiceType code.
func New(delegate sdktrace.Sampler, serviceName, applicationName string, typ *int) (*Sampler, error) {
	if delegate == nil {
		return nil, fmt.Errorf("pptracestate: delegate is required")
	}
	value := BuildValue(serviceName, applicationName, typ)
	if value == "" {
		return nil, fmt.Errorf("pptracestate: serviceName or applicationName must be provided")
	}
	return &Sampler{delegate: delegate, value: value}, nil
}

// ShouldSample implements sdktrace.Sampler.
func (s *Sampler) ShouldSample(p sdktrace.SamplingParameters) sdktrace.SamplingResult {
	result := s.delegate.ShouldSample(p)
	if p.Kind == trace.SpanKindServer || p.Kind == trace.SpanKindConsumer {
		return result // receiving side: keep the incoming trace state
	}
	// The delegate's result carries the trace state the new span gets (the built-in samplers
	// copy the parent's). Insert replaces an existing pp entry and keeps the others.
	if result.Tracestate.Get(Key) == s.value {
		return result
	}
	updated, err := result.Tracestate.Insert(Key, s.value)
	if err != nil {
		return result // invalid value: leave the trace state unchanged, never fail the span
	}
	result.Tracestate = updated
	return result
}

// Description implements sdktrace.Sampler.
func (s *Sampler) Description() string {
	return "PinpointTraceStateSampler{delegate=" + s.delegate.Description() + ", pp=" + s.value + "}"
}
