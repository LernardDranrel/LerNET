package expert

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	N "github.com/sagernet/sing/common/network"
)

// The ingress object is never replaced. Each new flow acquires one generation
// while publication holds the same lock. Retired generations close only once
// their last stream, datagram session, or asynchronous DNS request releases it.
type switchRouter struct {
	adapter.Router
	mu            sync.Mutex
	current       *generation
	all           map[*generation]bool
	closed        bool
	flows         int
	dnsIngress    *dnsIngressRegistry
	networkEpoch  atomic.Int64
	networkPaused atomic.Bool
	ready         *ingressReadiness
}

func (r *switchRouter) LerNETPendingPacketLimits() (int, int) { return 32, 256 * 1024 }

func (r *switchRouter) acquireFlow() (*generation, func(), error) {
	r.mu.Lock()
	if r.networkPaused.Load() {
		r.mu.Unlock()
		return nil, nil, errors.New("expert_underlay_unavailable")
	}
	if r.closed || r.current == nil {
		r.mu.Unlock()
		return nil, nil, ErrStopped
	}
	if r.flows >= 512 {
		r.mu.Unlock()
		return nil, nil, ErrBusy
	}
	g := r.current
	g.refs++
	r.flows++
	r.mu.Unlock()
	var once sync.Once
	return g, func() { once.Do(func() { r.mu.Lock(); r.flows--; r.mu.Unlock(); r.release(g) }) }, nil
}

type generation struct {
	revision      int64
	router        adapter.Router
	close         func() error
	start         func() error
	refs          int
	retired       bool
	registry      *gateRegistry
	cancel        context.CancelFunc
	closeMu       sync.Mutex
	closeErr      error
	disposed      bool
	closeFinished bool
}

func (g *generation) dispose() error {
	g.closeMu.Lock()
	if g.disposed {
		g.closeMu.Unlock()
		return g.cleanupState()
	}
	g.disposed, g.closeErr = true, ErrClosePending
	g.closeMu.Unlock()
	if g.cancel != nil {
		g.cancel()
	}
	err := g.close()
	g.closeMu.Lock()
	g.closeErr, g.closeFinished = err, true
	g.closeMu.Unlock()
	return g.cleanupState()
}

func onlyCleanupPending(err error) bool {
	if err == ErrClosePending {
		return true
	}
	switch wrapped := err.(type) {
	case interface{ Unwrap() []error }:
		children := wrapped.Unwrap()
		if len(children) == 0 {
			return false
		}
		for _, child := range children {
			if !onlyCleanupPending(child) {
				return false
			}
		}
		return true
	case interface{ Unwrap() error }:
		return onlyCleanupPending(wrapped.Unwrap())
	default:
		return false
	}
}

func (g *generation) cleanupState() error {
	g.closeMu.Lock()
	err, disposed, finished := g.closeErr, g.disposed, g.closeFinished
	g.closeMu.Unlock()
	if !disposed {
		return nil
	}
	if !finished {
		return ErrClosePending
	}
	if !onlyCleanupPending(err) || g.registry == nil {
		return err
	}
	g.registry.mu.Lock()
	defer g.registry.mu.Unlock()
	for _, gate := range g.registry.gates {
		if failure := gate.cleanupState(); failure != nil {
			return failure
		}
	}
	// Box's one completed pass returned only a pending cold startup. All such
	// workers have now closed their real providers, with no other close failure.
	return nil
}

func (r *switchRouter) disposeGeneration(g *generation) error {
	err := g.dispose()
	if err == nil {
		r.mu.Lock()
		delete(r.all, g)
		r.mu.Unlock()
	}
	return err
}

func (r *switchRouter) discard(g *generation) error {
	r.mu.Lock()
	if r.all == nil {
		r.all = make(map[*generation]bool)
	}
	g.retired = true
	r.all[g] = true
	r.mu.Unlock()
	return r.disposeGeneration(g)
}

func (r *switchRouter) acquire() (*generation, func(), error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed || r.current == nil {
		return nil, nil, ErrStopped
	}
	g := r.current
	g.refs++
	var once sync.Once
	return g, func() { once.Do(func() { r.release(g) }) }, nil
}

func (r *switchRouter) release(g *generation) {
	r.mu.Lock()
	g.refs--
	closeNow := g.retired && g.refs == 0
	r.mu.Unlock()
	if closeNow {
		_ = r.disposeGeneration(g)
	}
}

func (r *switchRouter) snapshot() (*generation, []*generation, func()) {
	r.mu.Lock()
	current := r.current
	generations := make([]*generation, 0, len(r.all))
	for g := range r.all {
		g.refs++
		generations = append(generations, g)
	}
	r.mu.Unlock()
	var once sync.Once
	return current, generations, func() {
		once.Do(func() {
			for _, g := range generations {
				r.release(g)
			}
		})
	}
}

func (r *switchRouter) publish(ctx context.Context, g *generation) error {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return ErrStopped
	}
	if err := r.retirementErrorLocked(); err != nil {
		r.mu.Unlock()
		return err
	}
	if err := ctx.Err(); err != nil {
		r.mu.Unlock()
		return err
	}
	old := r.current
	if r.dnsIngress == nil {
		r.dnsIngress = &dnsIngressRegistry{}
	}
	retiredDNS := r.dnsIngress.publish(g.revision)
	r.current = g
	if r.all == nil {
		r.all = make(map[*generation]bool)
	}
	r.all[g] = true
	closeNow := false
	if old != nil {
		old.retired = true
		closeNow = old.refs == 0
	}
	r.mu.Unlock()
	closeDNSIngress(retiredDNS)
	if closeNow {
		_ = r.disposeGeneration(old)
	}
	return nil
}

func (r *switchRouter) retirementError() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.retirementErrorLocked()
}

// A live draining generation may keep its business flows. A completed close
// that cannot prove cleanup must not accumulate more zero-flow generations.
func (r *switchRouter) retirementErrorLocked() error {
	for g := range r.all {
		if g.registry != nil {
			g.registry.mu.Lock()
			failed := false
			for _, gate := range g.registry.gates {
				gate.mu.Lock()
				failed = failed || gate.cleanupErr != nil
				gate.mu.Unlock()
			}
			g.registry.mu.Unlock()
			if failed {
				return ErrGenerationCleanup
			}
		}
		g.closeMu.Lock()
		disposed := g.disposed
		g.closeMu.Unlock()
		if !g.retired || !disposed {
			continue
		}
		if g.cleanupState() != nil {
			return ErrGenerationCleanup
		}
		if g.refs == 0 {
			delete(r.all, g)
		}
	}
	return nil
}

func (r *switchRouter) stop() error {
	r.mu.Lock()
	r.closed = true
	remaining := make([]*generation, 0, len(r.all))
	for g := range r.all {
		g.retired = true
		remaining = append(remaining, g)
	}
	r.current = nil
	var retiredDNS []*dnsIngressSession
	if r.dnsIngress != nil {
		retiredDNS = r.dnsIngress.stop()
	}
	r.mu.Unlock()
	closeDNSIngress(retiredDNS)
	// Explicit stop closes all remaining flows; hot apply does not.
	var failures []error
	for _, g := range remaining {
		failures = append(failures, r.disposeGeneration(g))
	}
	if r.dnsIngress != nil && r.dnsIngress.pending() {
		failures = append(failures, ErrClosePending)
	}
	return errors.Join(failures...)
}

func (r *switchRouter) ResetNetwork() {
	r.networkEpoch.Add(1)
	r.Router.ResetNetwork()
	r.mu.Lock()
	generations := make([]*generation, 0, len(r.all))
	for g := range r.all {
		generations = append(generations, g)
	}
	r.mu.Unlock()
	for _, g := range generations {
		g.router.ResetNetwork()
		g.registry.mu.Lock()
		for _, gate := range g.registry.gates {
			gate.invalidateNetwork()
		}
		for _, f := range g.registry.folders {
			f.invalidateNetwork()
		}
		g.registry.mu.Unlock()
	}
}

func (r *switchRouter) RouteConnectionEx(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	g, release, err := r.acquireFlow()
	if err == nil {
		err = r.ready.wait(ctx)
	}
	if err != nil {
		if release != nil {
			release()
		}
		N.CloseOnHandshakeFailure(conn, onClose, err)
		return
	}
	g.router.RouteConnectionEx(ctx, conn, metadata, N.OnceClose(func(err error) {
		release()
		if onClose != nil {
			onClose(err)
		}
	}))
}

func (r *switchRouter) RoutePacketConnectionEx(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	g, release, err := r.acquireFlow()
	if err == nil {
		err = r.ready.wait(ctx)
	}
	if err != nil {
		if release != nil {
			release()
		}
		N.CloseOnHandshakeFailure(conn, onClose, err)
		return
	}
	g.router.RoutePacketConnectionEx(ctx, conn, metadata, N.OnceClose(func(err error) {
		release()
		if onClose != nil {
			onClose(err)
		}
	}))
}

func (r *switchRouter) RouteConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext) error {
	g, release, err := r.acquireFlow()
	if err != nil {
		return err
	}
	defer release()
	if err = r.ready.wait(ctx); err != nil {
		return err
	}
	return g.router.RouteConnection(ctx, conn, metadata)
}

func (r *switchRouter) RoutePacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext) error {
	g, release, err := r.acquireFlow()
	if err != nil {
		return err
	}
	defer release()
	if err = r.ready.wait(ctx); err != nil {
		return err
	}
	return g.router.RoutePacketConnection(ctx, conn, metadata)
}

func (r *switchRouter) PreMatch(metadata adapter.InboundContext, firstPacket []byte) adapter.PreMatchResult {
	// Never hand a mutable exit to a kernel bypass/flow path. Both TCP and UDP
	// enter the owned userspace stack and acquire a policy generation there.
	return adapter.PreMatchResult{Action: adapter.PreMatchContinue}
}

func (r *switchRouter) HijackDNSPacket(ctx context.Context, payload []byte, writer N.PacketWriter, metadata adapter.InboundContext) {
	g, release, err := r.acquireFlow()
	if err != nil {
		return
	}
	if err = r.ready.wait(ctx); err != nil {
		release()
		return
	}
	// Native router invokes Release after ExchangeAsync has completed, including
	// malformed, rejected, overloaded, or canceled requests.
	if dispatcher, ok := g.router.(interface {
		HijackDNSPacketTracked(context.Context, []byte, N.PacketWriter, adapter.InboundContext, func())
	}); ok {
		dispatcher.HijackDNSPacketTracked(ctx, payload, writer, metadata, release)
	} else {
		release()
		panic(errors.New("missing_tracked_dns_dispatch"))
	}
}
