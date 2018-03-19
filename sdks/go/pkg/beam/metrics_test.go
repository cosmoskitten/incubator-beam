package beam_test

import (
	"context"
	"regexp"
	"time"

	"github.com/apache/beam/sdks/go/pkg/beam"
	"github.com/apache/beam/sdks/go/pkg/beam/core/util/ptransform"

	"github.com/apache/beam/sdks/go/pkg/beam/core/metrics"
)

func contextWithPtransformID(id string) context.Context {
	return ptransform.SetID(context.Background(), id)
}

func dumpAndClearMetrics() {
	metrics.DumpToOut()
	metrics.Clear()
}

var (
	wordRE = regexp.MustCompile(`[a-zA-Z]+('[a-z])?`)
)

func Example_metricsDeclaredAnywhere() {

	// Metrics can be declared both inside or outside DoFns.
	outside := beam.GetCounter("example.namespace", "count")

	extractWordsDofn := func(ctx context.Context, line string, emit func(string)) {
		inside := beam.GetDistribution("example.namespace", "characters")
		for _, word := range wordRE.FindAllString(line, -1) {
			emit(word)
			outside.Inc(ctx, 1)
			inside.Update(ctx, int64(len(word)))
		}
	}
	ctx := contextWithPtransformID("s4")
	extractWordsDofn(ctx, "this has six words in it", func(string) {})
	extractWordsDofn(ctx, "this has seven words in it, see?", func(string) {})

	dumpAndClearMetrics()
	// Output: PTransformID: "s4"
	//	example.namespace.count - value: 13
	//	example.namespace.characters - count: 13 sum: 43 min: 2 max: 5
}

func Example_metricsReusable() {

	// Metrics can be used in multiple DoFns
	c := beam.GetCounter("example.reusable", "count")

	extractWordsDofn := func(ctx context.Context, line string, emit func(string)) {
		for _, word := range wordRE.FindAllString(line, -1) {
			emit(word)
			c.Inc(ctx, 1)
		}
	}

	extractRunesDofn := func(ctx context.Context, line string, emit func(rune)) {
		for _, r := range line {
			emit(r)
			c.Inc(ctx, 1)
		}
	}
	extractWordsDofn(contextWithPtransformID("s4"), "this has six words in it", func(string) {})

	extractRunesDofn(contextWithPtransformID("s7"), "seven thousand", func(rune) {})

	dumpAndClearMetrics()
	// Output: PTransformID: "s4"
	//	example.reusable.count - value: 6
	// PTransformID: "s7"
	//	example.reusable.count - value: 14
}

var ctx = context.Background()

func ExampleCounter_Inc() {
	c := beam.GetCounter("example", "size")
	c.Inc(ctx, int64(len("foobar")))
}

func ExampleCounter_Dec() {
	c := beam.GetCounter("example", "size")
	c.Dec(ctx, int64(len("foobar")))
}

func ExampleDistribution_Update() {
	t := time.Millisecond * 42
	d := beam.GetDistribution("example", "latency_micros")
	d.Update(ctx, int64(t/time.Microsecond))
}

func ExampleGauge_Set() {
	g := beam.GetGauge("example", "progress")
	g.Set(ctx, 42)
}
