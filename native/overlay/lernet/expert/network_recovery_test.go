package expert

import (
	"context"
	"errors"
	"github.com/sagernet/sing-box/adapter"
	"testing"
)

type recoveryRouter struct {
	adapter.Router
	resets int
}

func (r *recoveryRouter) ResetNetwork() { r.resets++ }

func TestNetworkOutageRetainsIngressAndRestoresAdmission(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	router := &recoveryRouter{}
	mux := &switchRouter{Router: router, current: &generation{revision: 7}}
	s := &Session{ctx: ctx, cancel: cancel, mux: mux, running: true, ack: Ack{InstanceID: "owned", InterfaceID: "unchanged", Revision: 7}}
	original := s.ack
	failed := errors.New("expert_capture_route_update_failed")
	if err := s.refreshNetworkLocked("Ethernet", func(string) error { return failed }); !errors.Is(err, failed) {
		t.Fatal(err)
	}
	if !s.running || s.closed || ctx.Err() != nil || s.ack != original {
		t.Fatal("outage destroyed ingress or policy identity")
	}
	if s.networkReason != "expert_capture_route_update_failed" {
		t.Fatal(s.networkReason)
	}
	// TCP, UDP and asynchronous DNS all use this same admission gate.
	if _, _, err := mux.acquireFlow(); err == nil {
		t.Fatal("flow was admitted without a proven path")
	}
	if err := s.refreshNetworkLocked("Ethernet", func(string) error { return nil }); err != nil {
		t.Fatal(err)
	}
	if s.networkReason != "" || mux.networkPaused.Load() || s.ack != original || router.resets != 1 {
		t.Fatal("recovery did not restore same TUN")
	}
	_, release, err := mux.acquireFlow()
	if err != nil {
		t.Fatal(err)
	}
	release()
	// Several consecutive losses remain recoverable, without a retry budget.
	for i := 0; i < 8; i++ {
		s.setNetworkReasonLocked("expert_underlay_unavailable")
	}
	if !s.running || s.closed || ctx.Err() != nil {
		t.Fatal("repeated outages ended session")
	}
}
