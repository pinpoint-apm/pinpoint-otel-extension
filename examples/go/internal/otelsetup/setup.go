// Package otelsetup wires the pp sampler into a tracer provider that exports OTLP/gRPC to a
// Pinpoint collector. Shared by the caller and callee commands.
package otelsetup

import (
	"context"
	"fmt"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"

	"github.com/pinpoint-apm/pinpoint-otel-extension/examples/go/pptracestate"
)

// Init sets the global tracer provider and propagator. One applicationName value feeds both the
// Resource service.name and the app part of the pp entry.
func Init(ctx context.Context, applicationName string, applicationType int, endpoint string) (*sdktrace.TracerProvider, error) {
	exporter, err := otlptracegrpc.New(ctx, otlptracegrpc.WithEndpoint(endpoint), otlptracegrpc.WithInsecure())
	if err != nil {
		return nil, err
	}
	res, err := resource.Merge(resource.Default(), resource.NewSchemaless(attribute.String("service.name", applicationName)))
	if err != nil {
		return nil, err
	}

	base := sdktrace.ParentBased(sdktrace.AlwaysSample()) // the sampler you use today
	sampler, err := pptracestate.New(base, "", applicationName, &applicationType)
	if err != nil {
		return nil, err
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sampler), // outermost in the chain
		sdktrace.WithBatcher(exporter),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{})) // W3C is required
	fmt.Println("SAMPLER " + sampler.Description())
	return tp, nil
}
