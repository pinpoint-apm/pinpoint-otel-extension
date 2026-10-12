package pptracestate

import (
	"bufio"
	"context"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

// The wire contract file of this repository, shared with the Java extension and the collector.
const contractFile = "../../../src/test/resources/pp-tracestate-contract.txt"

func intp(v int) *int { return &v }

func mustNew(t *testing.T, delegate sdktrace.Sampler, svc, app string, typ *int) *Sampler {
	t.Helper()
	s, err := New(delegate, svc, app, typ)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func provider(t *testing.T, s sdktrace.Sampler) (trace.Tracer, *tracetest.InMemoryExporter) {
	t.Helper()
	exporter := tracetest.NewInMemoryExporter()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSampler(s), sdktrace.WithSyncer(exporter))
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })
	return tp.Tracer("test"), exporter
}

func ppByName(exporter *tracetest.InMemoryExporter) map[string]string {
	out := map[string]string{}
	for _, span := range exporter.GetSpans() {
		out[span.Name] = span.SpanContext.TraceState().Get(Key)
	}
	return out
}

// A1: every row of the contract file.
func TestBuildValueFollowsTheContractFile(t *testing.T) {
	f, err := os.Open(filepath.FromSlash(contractFile))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	cases := 0
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		cols := strings.Split(line, "|")
		if len(cols) != 4 {
			t.Fatalf("columns in %q", line)
		}
		col := func(raw string) string {
			if raw == "-" {
				return ""
			}
			return raw
		}
		var typ *int
		if cols[2] != "-" {
			n, err := strconv.Atoi(cols[2])
			if err != nil {
				t.Fatal(err)
			}
			typ = &n
		}
		expected := cols[3]
		if expected == "<null>" {
			expected = ""
		}
		if got := BuildValue(col(cols[0]), col(cols[1]), typ); got != expected {
			t.Errorf("%s: got %q want %q", line, got, expected)
		}
		cases++
	}
	if cases < 10 {
		t.Fatalf("only %d cases", cases)
	}
}

// A2
func TestRejectsConstructionWithoutServiceOrApplication(t *testing.T) {
	if _, err := New(sdktrace.AlwaysSample(), "", "", intp(1010)); err == nil {
		t.Fatal("expected an error")
	}
}

// B1
func TestClientProducerInternalCarryPpServerConsumerDoNot(t *testing.T) {
	tracer, exporter := provider(t, mustNew(t, sdktrace.ParentBased(sdktrace.AlwaysSample()), "", "order-api", intp(1010)))
	for name, kind := range map[string]trace.SpanKind{
		"root-internal": trace.SpanKindInternal, "client": trace.SpanKindClient, "producer": trace.SpanKindProducer,
		"server": trace.SpanKindServer, "consumer": trace.SpanKindConsumer,
	} {
		_, span := tracer.Start(context.Background(), name, trace.WithSpanKind(kind))
		span.End()
	}
	got := ppByName(exporter)
	want := map[string]string{"root-internal": "app:order-api;type:1010", "client": "app:order-api;type:1010",
		"producer": "app:order-api;type:1010", "server": "", "consumer": ""}
	for name, w := range want {
		if got[name] != w {
			t.Errorf("%s: got %q want %q", name, got[name], w)
		}
	}
}

// B2
func TestServerKeepsTheCallersPpAndItsClientChildWritesOurOwn(t *testing.T) {
	tracer, exporter := provider(t, mustNew(t, sdktrace.ParentBased(sdktrace.AlwaysSample()), "", "order-api", intp(1010)))
	carrier := propagation.MapCarrier{
		"traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
		"tracestate":  "pp=app:front;type:1010",
	}
	parent := propagation.TraceContext{}.Extract(context.Background(), carrier)
	ctx, server := tracer.Start(parent, "server", trace.WithSpanKind(trace.SpanKindServer))
	_, client := tracer.Start(ctx, "client", trace.WithSpanKind(trace.SpanKindClient))
	client.End()
	server.End()
	got := ppByName(exporter)
	if got["server"] != "app:front;type:1010" {
		t.Errorf("server: got %q", got["server"])
	}
	if got["client"] != "app:order-api;type:1010" {
		t.Errorf("client: got %q", got["client"])
	}
}

// B3
func TestOutgoingHeadersCarryPp(t *testing.T) {
	tracer, _ := provider(t, mustNew(t, sdktrace.ParentBased(sdktrace.AlwaysSample()), "order-team", "order-api", nil))
	ctx, client := tracer.Start(context.Background(), "client", trace.WithSpanKind(trace.SpanKindClient))
	carrier := propagation.MapCarrier{}
	propagation.TraceContext{}.Inject(ctx, carrier)
	client.End()
	if carrier["traceparent"] == "" {
		t.Error("no traceparent")
	}
	if carrier["tracestate"] != "pp=svc:order-team;app:order-api" {
		t.Errorf("tracestate: got %q", carrier["tracestate"])
	}
}

// B4
func TestKeepsTheDelegatesDecisionAndTraceStateEntries(t *testing.T) {
	s := mustNew(t, sdktrace.NeverSample(), "", "order-api", nil)
	ts, err := trace.ParseTraceState("ot=th:8,pp=app:stale")
	if err != nil {
		t.Fatal(err)
	}
	traceID, _ := trace.TraceIDFromHex("0af7651916cd43dd8448eb211c80319c")
	spanID, _ := trace.SpanIDFromHex("b7ad6b7169203331")
	parent := trace.ContextWithRemoteSpanContext(context.Background(), trace.NewSpanContext(trace.SpanContextConfig{
		TraceID: traceID, SpanID: spanID, TraceFlags: trace.FlagsSampled, TraceState: ts, Remote: true,
	}))
	result := s.ShouldSample(sdktrace.SamplingParameters{ParentContext: parent, TraceID: traceID, Name: "client", Kind: trace.SpanKindClient})
	if result.Decision != sdktrace.Drop {
		t.Errorf("decision: got %v want Drop", result.Decision)
	}
	if result.Tracestate.Get("ot") != "th:8" {
		t.Errorf("ot lost: %q", result.Tracestate.String())
	}
	if result.Tracestate.Get("pp") != "app:order-api" {
		t.Errorf("pp: got %q", result.Tracestate.Get("pp"))
	}
	if strings.Count(result.Tracestate.String(), "pp=") != 1 {
		t.Errorf("pp duplicated: %q", result.Tracestate.String())
	}
}

// B5 (negative): the wrapper inside ParentBased is not consulted for a span with a parent.
func TestRegressionWrappedInsideParentBasedLosesPpOnChildClientSpans(t *testing.T) {
	wrong := sdktrace.ParentBased(mustNew(t, sdktrace.AlwaysSample(), "", "order-api", intp(1010)))
	tracer, exporter := provider(t, wrong)
	ctx, server := tracer.Start(context.Background(), "server", trace.WithSpanKind(trace.SpanKindServer))
	_, client := tracer.Start(ctx, "client", trace.WithSpanKind(trace.SpanKindClient))
	client.End()
	server.End()
	if got := ppByName(exporter)["client"]; got != "" {
		t.Errorf("client: got %q want none", got)
	}
}

// B6
func TestInvalidValueIsDroppedSilently(t *testing.T) {
	tracer, exporter := provider(t, mustNew(t, sdktrace.AlwaysSample(), "", "bad,name", nil))
	_, client := tracer.Start(context.Background(), "client", trace.WithSpanKind(trace.SpanKindClient))
	client.End()
	if got := ppByName(exporter)["client"]; got != "" {
		t.Errorf("client: got %q want none", got)
	}
}
