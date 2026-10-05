package expert

import (
	"context"
	"errors"
	"github.com/sagernet/sing-box/adapter"
	N "github.com/sagernet/sing/common/network"
	"net"
	"sync/atomic"
	"testing"
)

type retainedRouter struct {
	adapter.Router
	tcpClose, udpClose N.CloseHandlerFunc
	metadata           adapter.InboundContext
}

func (r *retainedRouter) RouteConnectionEx(_ context.Context, _ net.Conn, m adapter.InboundContext, close N.CloseHandlerFunc) {
	r.tcpClose = close
	r.metadata = m
}
func (r *retainedRouter) RoutePacketConnectionEx(_ context.Context, _ N.PacketConn, m adapter.InboundContext, close N.CloseHandlerFunc) {
	r.udpClose = close
	r.metadata = m
}

func TestPublicationRetainsUnrelatedTCPAndUDPGeneration(t *testing.T) {
	var oldClosed, newClosed atomic.Int32
	oldRouter := &retainedRouter{}
	old := &generation{revision: 1, router: oldRouter, close: func() error { oldClosed.Add(1); return nil }}
	next := &generation{revision: 2, close: func() error { newClosed.Add(1); return nil }}
	mux := &switchRouter{}
	if err := mux.publish(context.Background(), old); err != nil {
		t.Fatal(err)
	}
	metadata := adapter.InboundContext{User: "original-owner", LerNETNodeIDs: []string{"device", "profile"}, LerNETProtected: true}
	mux.RouteConnectionEx(context.Background(), nil, metadata, nil)
	mux.RoutePacketConnectionEx(context.Background(), nil, metadata, nil)
	if err := mux.publish(context.Background(), next); err != nil {
		t.Fatal(err)
	}
	if oldClosed.Load() != 0 {
		t.Fatal("publication closed established flows")
	}
	if oldRouter.metadata.User != "original-owner" || !oldRouter.metadata.LerNETProtected {
		t.Fatal("child tree lost original metadata")
	}
	oldRouter.tcpClose(nil)
	if oldClosed.Load() != 0 {
		t.Fatal("generation closed before UDP drained")
	}
	oldRouter.udpClose(nil)
	oldRouter.udpClose(nil)
	if oldClosed.Load() != 1 {
		t.Fatal("retired generation must close exactly once")
	}
	mux.stop()
	mux.stop()
	if newClosed.Load() != 1 {
		t.Fatal("stop must close current generation exactly once")
	}
}

func TestCancelledPublicationLeavesCurrentRevision(t *testing.T) {
	mux := &switchRouter{}
	current := &generation{revision: 10, close: func() error { return nil }}
	if err := mux.publish(context.Background(), current); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := mux.publish(ctx, &generation{revision: 11}); !errors.Is(err, context.Canceled) {
		t.Fatalf("wrong cancellation: %v", err)
	}
	if mux.current != current || current.retired {
		t.Fatal("canceled prepare changed published generation")
	}
	mux.stop()
}

func TestControlErrorsNeverExposeProviderCredentials(t *testing.T) {
	for _, raw := range []string{"invalid password abc", "dial example.org failed", "token privatekeyabc", "invalid_exit_limits: passwordabc"} {
		if ErrorCode(errors.New(raw)) != "native_operation_failed" {
			t.Fatal("provider text crossed protocol boundary")
		}
	}
	if ErrorCode(ErrConflict) != "expert_revision_or_identity_conflict" {
		t.Fatal("known conflict lost stable code")
	}
}

type ownerRejectRouter struct{ adapter.Router }

func (ownerRejectRouter) LerNETRejectExisting(m adapter.InboundContext) bool {
	return m.User == "blocked-owner"
}
func TestNewBlockTerminatesOnlyMatchingExistingFlows(t *testing.T) {
	h := newFlowHistory(10)
	var deniedClosed, otherClosed atomic.Int32
	deniedMeta := adapter.InboundContext{User: "blocked-owner"}
	otherMeta := adapter.InboundContext{User: "unrelated-owner"}
	denied := h.add(1, deniedMeta, nil)
	other := h.add(1, otherMeta, nil)
	h.live[denied] = liveFlow{deniedMeta, func() error { deniedClosed.Add(1); h.close(denied); return nil }}
	h.live[other] = liveFlow{otherMeta, func() error { otherClosed.Add(1); h.close(other); return nil }}
	h.enforce(ownerRejectRouter{})
	if deniedClosed.Load() != 1 || otherClosed.Load() != 0 || denied.State != "blocked" || !denied.Closed {
		t.Fatal("new reject must terminate only its original matching flow")
	}
}
