package expert

import (
	"context"
	"encoding/json"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

type cleanupFailureProvider struct {
	*acceptanceProvider
	failure error
}

type cleanupSessionStatus struct {
	Confirmed bool                   `json:"close_confirmed"`
	Retired   []RetiredCleanupStatus `json:"retired_cleanup_failures"`
}

func (p *cleanupFailureProvider) Close() error {
	p.closes.Add(1)
	return p.failure
}

func TestLerNETFailedProviderCleanupCannotWakeOrReplace(t *testing.T) {
	for _, operation := range []string{"sleep", "recover", "close"} {
		t.Run(operation, func(t *testing.T) {
			p := &cleanupFailureProvider{acceptanceProvider: &acceptanceProvider{release: make(chan struct{})}, failure: errors.New("private provider details")}
			gate := acceptanceGate(p.acceptanceProvider)
			gate.actual, gate.phase = p, "ready"
			providerCtx, providerCancel := context.WithCancel(context.Background())
			gate.providerCancel = providerCancel
			var factories atomic.Int32
			gate.factory = func() (adapter.Outbound, error) { factories.Add(1); return p, nil }
			var err error
			switch operation {
			case "sleep":
				err = gate.sleep(false)
			case "recover":
				err = gate.recover(context.Background())
			case "close":
				err = gate.Close()
			}
			if !errors.Is(err, ErrExitCleanup) {
				t.Fatalf("cleanup failure hidden: %v", err)
			}
			if err = gate.wake(context.Background()); err == nil {
				t.Fatal("failed cleanup woke an exit")
			}
			if err = gate.Close(); !errors.Is(err, ErrExitCleanup) {
				t.Fatalf("repeat Close manufactured drain: %v", err)
			}
			gate.mu.Lock()
			retained := len(gate.failedProviders)
			gate.mu.Unlock()
			if providerCtx.Err() != context.Canceled || factories.Load() != 0 || retained != 1 || p.closes.Load() != 1 {
				t.Fatal("lost old provider ownership or constructed replacement")
			}
		})
	}
}

func TestLerNETSessionStatusConfirmsOnlyActualLateProviderDrain(t *testing.T) {
	for _, stickyIngressFailure := range []bool{false, true} {
		p := &acceptanceProvider{release: make(chan struct{})}
		gate := acceptanceGate(p)
		ctx, cancel := context.WithCancel(context.Background())
		gate.ctx = ctx
		g := &generation{revision: 8, registry: &gateRegistry{gates: map[string]*exitGate{"exit-a": gate}}, cancel: cancel, close: gate.Close}
		mux := &switchRouter{}
		if err := mux.publish(context.Background(), g); err != nil {
			t.Fatal(err)
		}
		waiting := make(chan error, 1)
		go func() { waiting <- gate.wake(context.Background()) }()
		acceptanceAwait(t, "cold provider blocked", func() bool { return p.starts.Load() == 1 })
		// No TUN is created. This represents the already completed ingress pass;
		// real Session Close/Status must still compose its result with late gates.
		s := &Session{ctx: ctx, cancel: cancel, mux: mux, closed: true, ingressCloseFinished: true, flows: newFlowHistory(10), ack: Ack{InstanceID: "instance", InterfaceID: "tun", Revision: 8}}
		if stickyIngressFailure {
			s.ingressCloseErr = errors.New("test_ingress_cleanup_failure")
		}
		if err := s.Close(); err == nil {
			t.Fatal("Session confirmed drain before worker returned")
		}
		if err := s.CloseAt("wrong", "tun", 8); !errors.Is(err, ErrConflict) {
			t.Fatal("closed-session retry lost identity fence")
		}
		if err := s.CloseAt("instance", "tun", 8); err == nil {
			t.Fatal("closed-session retry manufactured pending drain")
		}
		readStatus := func() cleanupSessionStatus {
			var status cleanupSessionStatus
			if err := json.Unmarshal([]byte(s.Status()), &status); err != nil {
				t.Fatal(err)
			}
			return status
		}
		status := readStatus()
		if status.Confirmed || len(status.Retired) != 1 || status.Retired[0].Revision != 8 || status.Retired[0].Reason != "expert_cleanup_pending" {
			t.Fatal("pending cleanup status is not factual")
		}
		close(p.release)
		<-waiting
		acceptanceAwait(t, "late provider closed", func() bool { return p.closes.Load() == 1 && !errors.Is(gate.cleanupState(), ErrClosePending) })
		status = readStatus()
		if err := s.CloseAt("instance", "tun", 8); (err == nil) == stickyIngressFailure {
			t.Fatal("matching Close retry lost actual sticky ingress result")
		}
		if status.Confirmed == stickyIngressFailure || len(status.Retired) != 0 {
			t.Fatal("Session manufactured or permanently lost actual cleanup proof")
		}
	}
}

func TestLerNETCanceledColdStartupHasGenuineLateDrainProof(t *testing.T) {
	for _, failed := range []bool{false, true} {
		p := &cleanupFailureProvider{acceptanceProvider: &acceptanceProvider{release: make(chan struct{})}}
		if failed {
			p.failure = errors.New("test_cleanup_failure")
		}
		gate := acceptanceGate(p.acceptanceProvider)
		gate.actual = p
		ctx, cancel := context.WithCancel(context.Background())
		gate.ctx = ctx
		registry := &gateRegistry{gates: map[string]*exitGate{"exit-a": gate}}
		generation := &generation{registry: registry, cancel: cancel, close: gate.Close}
		mux := &switchRouter{}
		if err := mux.publish(context.Background(), generation); err != nil {
			t.Fatal(err)
		}
		waiting := make(chan error, 1)
		go func() { waiting <- gate.wake(context.Background()) }()
		acceptanceAwait(t, "startup blocked", func() bool { return p.starts.Load() == 1 })
		if err := mux.stop(); !errors.Is(err, ErrClosePending) {
			t.Fatalf("uncompleted worker falsely drained: %v", err)
		}
		select {
		case <-waiting:
		case <-time.After(250 * time.Millisecond):
			t.Fatal("Close did not release the first flow promptly")
		}
		close(p.release)
		acceptanceAwait(t, "provider cleanup", func() bool { return p.closes.Load() == 1 && !errors.Is(gate.cleanupState(), ErrClosePending) })
		err := mux.stop()
		mux.mu.Lock()
		retained := len(mux.all)
		mux.mu.Unlock()
		if failed {
			if !errors.Is(err, ErrExitCleanup) || retained != 1 {
				t.Fatal("failed retired cleanup ownership lost")
			}
		} else if err != nil || retained != 0 {
			t.Fatalf("completed cleanup cannot prove drain: %v", err)
		}
	}
}

func TestLerNETGenerationCloseReentryAndContextCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	mux := &switchRouter{}
	var count atomic.Int32
	g := &generation{cancel: cancel}
	g.close = func() error {
		if ctx.Err() != context.Canceled {
			t.Fatal("provider context remains alive during retirement")
		}
		count.Add(1)
		mux.release(g) // Closing a last handle may release its policy lease.
		return nil
	}
	if err := mux.publish(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	g.refs = 1
	finished := make(chan error, 1)
	go func() { finished <- mux.stop() }()
	select {
	case err := <-finished:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("generation Close deadlocked on its own last lease")
	}
	if count.Load() != 1 {
		t.Fatal("generation closed more than once")
	}
}

func TestLerNETFailedRetirementBlocksNextApplyWithoutChangingAck(t *testing.T) {
	mux := &switchRouter{}
	old := &generation{revision: 1, close: func() error { return errors.New("private_cleanup_failure") }}
	current := &generation{revision: 2, close: func() error { return nil }}
	if err := mux.publish(context.Background(), old); err != nil {
		t.Fatal(err)
	}
	if err := mux.publish(context.Background(), current); err != nil {
		t.Fatal(err)
	}
	s := &Session{ctx: context.Background(), mux: mux, running: true, ack: Ack{InstanceID: "instance", InterfaceID: "tun", Revision: 2}}
	if _, err := s.Apply(context.Background(), "instance", "tun", 2, 3, "", ""); !errors.Is(err, ErrGenerationCleanup) {
		t.Fatalf("failed retirement allowed more preparation: %v", err)
	}
	if err := mux.publish(context.Background(), &generation{revision: 3}); !errors.Is(err, ErrGenerationCleanup) {
		t.Fatal("publication fence did not reject cleanup uncertainty")
	}
	if s.ack.Revision != 2 || mux.current != current || len(mux.all) != 2 {
		t.Fatal("failed Apply changed ACK or lost old cleanup ownership")
	}
}
