// The callee (B): an otelhttp-instrumented server. It has nothing to do for Pinpoint and only
// echoes the tracestate it received.
//
//	go run ./cmd/callee <applicationName> <port>
package main

import (
	"context"
	"fmt"
	"net/http"
	"os"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

	"github.com/pinpoint-apm/pinpoint-otel-extension/examples/go/internal/otelsetup"
)

func main() {
	app, port := os.Args[1], os.Args[2]
	ctx := context.Background()
	tp, err := otelsetup.Init(ctx, app, 1220, "127.0.0.1:9998")
	if err != nil {
		panic(err)
	}
	defer func() { _ = tp.Shutdown(ctx) }()

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ts := r.Header.Get("tracestate")
		fmt.Println("CALLEE-SAW tracestate: " + ts)
		fmt.Fprintf(w, "pong tracestate=%s\n", ts)
	})
	mux := http.NewServeMux()
	mux.Handle("/order", otelhttp.NewHandler(handler, "GET /order"))
	fmt.Println("CALLEE-UP " + port)
	if err := http.ListenAndServe("127.0.0.1:"+port, mux); err != nil {
		panic(err)
	}
}
