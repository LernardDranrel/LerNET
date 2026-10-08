package expert

import (
	"context"
	"errors"
	"github.com/sagernet/sing-box/adapter"
	"testing"
)

func TestIngressReadinessOnlyReleasesAfterProof(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ready := newIngressReadiness(ctx)
	select {
	case <-ready.done:
		t.Fatal("ingress advertised ready before proof")
	default:
	}
	ready.complete()
	ready.complete()
	if err := ready.wait(context.Background()); err != nil {
		t.Fatal(err)
	}
	cancel()
	if err := ready.wait(context.Background()); !errors.Is(err, context.Canceled) {
		t.Fatalf("stopped readiness accepted: %v", err)
	}
}

func TestIngressCancelReleasesFlowBudgetWithoutRouting(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	router := &retainedRouter{}
	mux := &switchRouter{ready: newIngressReadiness(ctx)}
	if err := mux.publish(context.Background(), &generation{router: router, close: func() error { return nil }}); err != nil {
		t.Fatal(err)
	}
	if err := mux.RouteConnection(context.Background(), nil, adapter.InboundContext{}); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if mux.flows != 0 || mux.current.refs != 0 || router.tcpClose != nil {
		t.Fatal("canceled startup retained or routed a flow")
	}
	caller, stop := context.WithCancel(context.Background())
	stop()
	if err := newIngressReadiness(context.Background()).wait(caller); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
