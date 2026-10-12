// The caller (A): plain net/http through otelhttp.NewTransport, which creates the CLIENT span and
// injects the headers; the pp sampler adds the entry.
//
//	go run ./cmd/caller <applicationName> <targetUrl> <count>
package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

	"github.com/pinpoint-apm/pinpoint-otel-extension/examples/go/internal/otelsetup"
)

func main() {
	app, target := os.Args[1], os.Args[2]
	count, _ := strconv.Atoi(os.Args[3])
	ctx := context.Background()
	tp, err := otelsetup.Init(ctx, app, 1220, "127.0.0.1:9998")
	if err != nil {
		panic(err)
	}

	client := &http.Client{Transport: otelhttp.NewTransport(http.DefaultTransport)}
	ok := 0
	for i := 0; i < count; i++ {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
		resp, err := client.Do(req)
		if err != nil {
			panic(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if i == 0 {
			fmt.Print("CALLER-GOT " + string(body))
		}
		if resp.StatusCode == 200 {
			ok++
		}
		time.Sleep(300 * time.Millisecond)
	}
	fmt.Printf("CALLER-DONE ok=%d/%d\n", ok, count)
	_ = tp.ForceFlush(ctx)
	_ = tp.Shutdown(ctx)
}
