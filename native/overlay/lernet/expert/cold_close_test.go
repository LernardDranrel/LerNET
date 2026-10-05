package expert

import (
	"context"
	"errors"
	"io"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

type slowCloseProvider struct {
	*acceptanceProvider
	entered, complete chan struct{}
	failure           error
}

func (p *slowCloseProvider) Close() error {
	p.closes.Add(1)
	close(p.entered)
	<-p.complete
	return p.failure
}

func coldClosingGate(t *testing.T, failure error) (*exitGate, *slowCloseProvider, *atomic.Int32, chan error) {
	t.Helper()
	p := &slowCloseProvider{acceptanceProvider: &acceptanceProvider{release: make(chan struct{})}, entered: make(chan struct{}), complete: make(chan struct{}), failure: failure}
	g := acceptanceGate(p.acceptanceProvider)
	g.actual, g.phase = p, "ready"
	factories := new(atomic.Int32)
	g.factory = func() (adapter.Outbound, error) {
		factories.Add(1)
		ready := make(chan struct{})
		close(ready)
		return &acceptanceProvider{release: ready}, nil
	}
	done := make(chan error, 1)
	go func() { done <- g.sleep(false) }()
	select {
	case <-p.entered:
	case <-time.After(time.Second):
		t.Fatal("idle Close did not enter provider")
	}
	return g, p, factories, done
}

func TestLerNETBusinessTCPAndUDPWaitForColdCloseBeforeWake(t *testing.T) {
	g, p, factories, sleepDone := coldClosingGate(t, nil)
	defer g.Close()
	g.limits.MaxPendingFlows = 8
	results := make(chan struct {
		handle io.Closer
		err    error
	}, 8)
	for i := 0; i < 8; i++ {
		go func(udp bool) {
			var handle io.Closer
			var err error
			if udp {
				handle, err = g.ListenPacket(context.Background(), M.ParseSocksaddr("127.0.0.1:80"))
			} else {
				handle, err = g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("127.0.0.1:80"))
			}
			results <- struct {
				handle io.Closer
				err    error
			}{handle, err}
		}(i%2 == 0)
	}
	acceptanceAwait(t, "bounded close waiters", func() bool { return g.status().PendingFlows == 8 })
	if _, _, err := g.acquire(context.Background(), true); !errors.Is(err, ErrSleeping) {
		t.Fatal("health probe waited or woke a closing exit")
	}
	if _, _, err := g.acquire(context.Background(), false); !errors.Is(err, ErrBusy) {
		t.Fatal("close waiter admission ignored flow limit")
	}
	if factories.Load() != 0 || g.status().PendingFlows != 8 {
		t.Fatal("provider replaced before actual Close")
	}
	close(p.complete)
	if err := <-sleepDone; err != nil {
		t.Fatal(err)
	}
	handles := make([]io.Closer, 0, 8)
	for i := 0; i < 8; i++ {
		r := <-results
		if r.err != nil {
			t.Fatal(r.err)
		}
		handles = append(handles, r.handle)
	}
	if factories.Load() != 1 || g.status().ActiveFlows != 8 {
		t.Fatal("close waiters did not coalesce one replacement")
	}
	for _, h := range handles {
		_ = h.Close()
	}
	if g.status().ActiveFlows != 0 || g.status().PendingFlows != 0 {
		t.Fatal("close waiter flow ownership did not drain")
	}
}

func TestLerNETColdCloseWaiterFailureCancellationAndNetworkFence(t *testing.T) {
	for _, mode := range []string{"cleanup-failure", "session-close", "network-change"} {
		t.Run(mode, func(t *testing.T) {
			var failure error
			if mode == "cleanup-failure" {
				failure = errors.New("private_cleanup_details")
			}
			g, p, factories, sleepDone := coldClosingGate(t, failure)
			waiting := make(chan error, 1)
			go func() {
				_, err := g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("127.0.0.1:80"))
				waiting <- err
			}()
			acceptanceAwait(t, "business waiter", func() bool { return g.status().PendingFlows == 1 })
			switch mode {
			case "session-close":
				if err := g.Close(); !errors.Is(err, ErrClosePending) {
					t.Fatal("incomplete shutdown falsely drained")
				}
				select {
				case err := <-waiting:
					if !errors.Is(err, ErrStopped) {
						t.Fatal(err)
					}
				case <-time.After(250 * time.Millisecond):
					t.Fatal("Close did not cancel business waiter")
				}
			case "network-change":
				g.invalidateNetwork()
			}
			close(p.complete)
			<-sleepDone
			if mode != "session-close" {
				err := <-waiting
				if mode == "cleanup-failure" && !errors.Is(err, ErrExitCleanup) {
					t.Fatal(err)
				}
				if mode == "network-change" && !errors.Is(err, ErrExitNetworkChanged) {
					t.Fatal(err)
				}
			}
			if factories.Load() != 0 || g.status().PendingFlows != 0 {
				t.Fatal("failed/stale close wait constructed replacement or lost counters")
			}
			_ = g.Close()
		})
	}
}

func TestLerNETColdCloseWaiterKeepsOriginalConnectionDeadline(t *testing.T) {
	g, p, _, sleepDone := coldClosingGate(t, nil)
	defer g.Close()
	g.factory = func() (adapter.Outbound, error) {
		ready := make(chan struct{})
		close(ready)
		return &acceptanceProvider{release: ready, blockDial: true}, nil
	}
	started := time.Now()
	result := make(chan error, 1)
	go func() {
		_, err := g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("127.0.0.1:80"))
		result <- err
	}()
	acceptanceAwait(t, "timed business waiter", func() bool { return g.status().PendingFlows == 1 })
	timer := time.NewTimer(650 * time.Millisecond)
	<-timer.C
	close(p.complete)
	<-sleepDone
	select {
	case err := <-result:
		if err == nil || time.Since(started) > 1500*time.Millisecond {
			t.Fatal("idle Close reset the first-flow deadline")
		}
	case <-time.After(850 * time.Millisecond):
		t.Fatal("actual dial exceeded original first-flow budget")
	}
}
