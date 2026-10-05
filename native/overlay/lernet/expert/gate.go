package expert

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	M "github.com/sagernet/sing/common/metadata"
)

type gateRegistry struct {
	adapter.OutboundRegistry
	mu       sync.Mutex
	manifest Manifest
	gates    map[string]*exitGate
	folders  map[string]*folderGate
}

func (r *gateRegistry) CreateOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag, outboundType string, options any) (adapter.Outbound, error) {
	for _, folder := range r.manifest.Folders {
		if folder.Tag == tag {
			if outboundType != "selector" {
				return nil, errors.New("folder_requires_selector_type")
			}
			gate := &folderGate{ctx: ctx, registry: r, options: folder, health: make(map[string]candidateHealth)}
			schedule := r.manifest.healthOptions()
			if gate.options.ProbeMinIntervalMs == 0 {
				gate.options.ProbeMinIntervalMs, gate.options.ProbeMaxIntervalMs = schedule.ProbeMinIntervalMs, schedule.ProbeMaxIntervalMs
			}
			if gate.options.ActiveProbeTimeoutMs == 0 {
				gate.options.ActiveProbeTimeoutMs = schedule.ActiveProbeTimeoutMs
			}
			if gate.options.FailedChecksBeforeRecovery == 0 {
				gate.options.FailedChecksBeforeRecovery = schedule.FailedChecksBeforeRecovery
			}
			if gate.options.ProbeURL == "" {
				gate.options.ProbeURL = r.manifest.ProbeURL
			}
			r.mu.Lock()
			if r.folders == nil {
				r.folders = make(map[string]*folderGate)
			}
			r.folders[tag] = gate
			r.mu.Unlock()
			return gate, nil
		}
	}
	var limits *ExitOptions
	for _, x := range r.manifest.Exits {
		if x.Tag == tag {
			copy := x
			limits = &copy
			break
		}
	}
	providerCtx := ctx
	var providerCancel context.CancelFunc
	if limits != nil {
		providerCtx, providerCancel = context.WithCancel(ctx)
	}
	actual, err := r.OutboundRegistry.CreateOutbound(providerCtx, router, logger, tag, outboundType, options)
	if err != nil {
		if providerCancel != nil {
			providerCancel()
		}
		return nil, err
	}
	if limits == nil {
		return actual, nil
	}
	g := &exitGate{
		ctx: ctx, tag: tag, outboundType: outboundType, limits: *limits,
		actual: actual, phase: "sleeping", networks: append([]string(nil), actual.Network()...),
		providerCancel: providerCancel,
		dependencies:   append([]string(nil), actual.Dependencies()...),
		factory: func() (adapter.Outbound, error) {
			return r.OutboundRegistry.CreateOutbound(ctx, router, logger, tag, outboundType, options)
		},
		factoryWithContext: func(providerCtx context.Context) (adapter.Outbound, error) {
			return r.OutboundRegistry.CreateOutbound(providerCtx, router, logger, tag, outboundType, options)
		},
		lastActivity:   time.Now(),
		probeURL:       r.manifest.ProbeURL,
		healthSchedule: r.manifest.healthOptions(),
	}
	if directOptions, ok := options.(*option.DirectOutboundOptions); ok && directOptions != nil && directOptions.LerNETInterface != nil {
		// Corporate split tunnels need not reach a public health endpoint. Every
		// socket already verifies the intended adapter; public probe failure must
		// not repeatedly close otherwise working company-resource connections.
		g.probeURL = ""
	}
	r.mu.Lock()
	if r.gates == nil {
		r.gates = make(map[string]*exitGate)
	}
	r.gates[tag] = g
	r.mu.Unlock()
	return g, nil
}

func (r *gateRegistry) gate(tag string) (*exitGate, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	g := r.gates[tag]
	if g == nil {
		return nil, errors.New("unknown_exit_tag")
	}
	return g, nil
}

type exitGate struct {
	ctx                     context.Context
	tag, outboundType       string
	limits                  ExitOptions
	networks, dependencies  []string
	factory                 func() (adapter.Outbound, error)
	factoryWithContext      func(context.Context) (adapter.Outbound, error)
	providerCancel          context.CancelFunc
	mu                      sync.Mutex
	actual                  adapter.Outbound
	phase                   string
	pending, active, probes int
	startup                 *wakeAttempt
	stopAttempt             *wakeAttempt
	closed                  bool
	cleanupErr              error
	stopping                int
	failedProviders         []adapter.Outbound
	lastActivity            time.Time
	lastError               string
	direct                  adapter.Outbound
	probeURL                string
	healthSchedule          HealthOptions
	nextHealth              time.Time
	healthChecking          bool
	healthFailures          int
	lastCheck               time.Time
	latency                 *int64
	networkEpoch            int64
	transportGeneration     int64
	transportContext        context.Context
	transportCancel         context.CancelFunc
	handles                 map[io.Closer]bool
}

type wakeAttempt struct {
	done           chan struct{}
	cancel         context.CancelFunc
	err            error
	finished       bool
	providerCancel context.CancelFunc
}

// Called only with the gate lock. The first terminal outcome is immutable so
// readers released by done cannot race a late provider completion.
func (w *wakeAttempt) finish(err error) {
	if w.finished {
		return
	}
	w.err = err
	w.finished = true
	close(w.done)
}

func (g *exitGate) Type() string           { return g.outboundType }
func (g *exitGate) Tag() string            { return g.tag }
func (g *exitGate) Network() []string      { return g.networks }
func (g *exitGate) Dependencies() []string { return g.dependencies }

func (g *exitGate) Start(stage adapter.StartStage) error {
	// Cold protocols are constructed/validated at preparation, but none of their
	// Start stages run until a business flow or an explicit warm command arrives.
	if g.limits.Mode != "warm" {
		return nil
	}
	if err := adapter.LegacyStart(g.actual, stage); err != nil {
		return err
	}
	if stage == adapter.StartStateStarted {
		g.mu.Lock()
		g.phase = "ready"
		g.transportGeneration++
		g.transportContext, g.transportCancel = context.WithCancel(g.ctx)
		g.latency = nil
		g.lastCheck = time.Time{}
		g.healthFailures = 0
		g.mu.Unlock()
	}
	return nil
}

func (g *exitGate) beginWakeLocked(before func() error) *wakeAttempt {
	if g.startup != nil {
		return g.startup
	}
	ctx, cancel := context.WithTimeout(g.ctx, time.Duration(g.limits.StartupTimeoutMs)*time.Millisecond)
	w := &wakeAttempt{done: make(chan struct{}), cancel: cancel, providerCancel: g.providerCancel}
	g.startup, g.phase = w, "starting"
	if g.transportCancel != nil {
		g.transportCancel()
		g.transportCancel = nil
	}
	g.latency = nil
	g.lastCheck = time.Time{}
	g.healthFailures = 0
	actual := g.actual
	g.actual = nil
	providerCancel := g.providerCancel
	g.providerCancel = nil
	go func() {
		<-ctx.Done()
		g.mu.Lock()
		if g.startup == w && !w.finished {
			if w.providerCancel != nil {
				w.providerCancel()
			}
			if g.closed {
				w.finish(ErrStopped)
			} else {
				g.phase, g.lastError = "failed", "exit_start_timeout"
				w.finish(ctx.Err())
			}
		}
		g.mu.Unlock()
	}()
	go func() {
		var err error
		if before != nil {
			err = before()
		}
		if err == nil {
			err = ctx.Err()
		}
		if err == nil && actual == nil {
			if g.factoryWithContext != nil {
				providerCtx, newCancel := context.WithCancel(g.ctx)
				providerCancel = newCancel
				g.mu.Lock()
				w.providerCancel = newCancel
				if g.closed || ctx.Err() != nil {
					newCancel()
				}
				g.mu.Unlock()
				actual, err = g.factoryWithContext(providerCtx)
			} else {
				actual, err = g.factory()
			}
		}
		if err == nil {
			for _, stage := range adapter.ListStartStages {
				if err = ctx.Err(); err != nil {
					break
				}
				if err = adapter.LegacyStart(actual, stage); err != nil {
					break
				}
			}
		}
		if err == nil {
			err = ctx.Err()
		}
		g.mu.Lock()
		if g.closed || g.startup != w || w.finished {
			if err == nil {
				err = ErrStopped
			}
		}
		if err == nil {
			g.actual, g.phase, g.lastError = actual, "ready", ""
			g.providerCancel = providerCancel
			g.transportGeneration++
			g.transportContext, g.transportCancel = context.WithCancel(g.ctx)
			w.finish(nil)
			if g.startup == w {
				g.startup = nil
			}
			g.mu.Unlock()
			cancel()
			return
		} else if !g.closed && g.cleanupErr == nil {
			g.phase, g.lastError = "failed", "exit_start_failed"
		}
		g.mu.Unlock()
		cancel()
		if providerCancel != nil {
			providerCancel()
		}
		if err != nil && actual != nil {
			if cleanupErr := g.closeProvider(actual); cleanupErr != nil {
				err = cleanupErr
			}
		}
		g.mu.Lock()
		w.finish(err)
		if g.startup == w {
			g.startup = nil
		}
		g.mu.Unlock()
	}()
	return w
}

func (g *exitGate) acquire(ctx context.Context, probe bool) (adapter.Outbound, func(), error) {
	deadlineCtx, cancel := context.WithTimeout(ctx, time.Duration(g.limits.FirstFlowTimeoutMs)*time.Millisecond)
	defer cancel()

admit:
	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		return nil, nil, ErrStopped
	}
	if g.cleanupErr != nil {
		err := g.cleanupStateLocked()
		g.mu.Unlock()
		return nil, nil, err
	}
	if probe && g.phase != "ready" {
		g.mu.Unlock()
		return nil, nil, ErrSleeping
	}
	if g.stopping != 0 {
		w, epoch := g.stopAttempt, g.networkEpoch
		if w == nil {
			g.mu.Unlock()
			return nil, nil, ErrClosePending
		}
		if g.pending >= g.limits.MaxPendingFlows {
			g.mu.Unlock()
			return nil, nil, ErrBusy
		}
		g.pending++
		g.mu.Unlock()
		var waitErr error
		select {
		case <-deadlineCtx.Done():
			waitErr = deadlineCtx.Err()
		case <-w.done:
			waitErr = w.err
		}
		g.mu.Lock()
		g.pending--
		if waitErr == nil && g.networkEpoch != epoch {
			waitErr = ErrExitNetworkChanged
		}
		if g.closed {
			waitErr = ErrStopped
		}
		g.mu.Unlock()
		if waitErr != nil {
			return nil, nil, waitErr
		}
		// Keep the original deadline. Provider shutdown cannot grant a new
		// first-flow budget before the replacement wake and actual dial.
		goto admit
	}
	if g.phase != "ready" {
		if g.pending >= g.limits.MaxPendingFlows {
			g.mu.Unlock()
			return nil, nil, ErrBusy
		}
		g.pending++
		w := g.beginWakeLocked(nil)
		g.mu.Unlock()
		select {
		case <-deadlineCtx.Done():
			g.mu.Lock()
			g.pending--
			g.mu.Unlock()
			return nil, nil, deadlineCtx.Err()
		case <-w.done:
		}
		g.mu.Lock()
		g.pending--
		if w.err != nil {
			g.mu.Unlock()
			return nil, nil, w.err
		}
		if deadlineCtx.Err() != nil {
			g.mu.Unlock()
			return nil, nil, deadlineCtx.Err()
		}
	}
	if g.closed || g.actual == nil || g.phase != "ready" {
		g.mu.Unlock()
		return nil, nil, ErrStopped
	}
	actual := g.actual
	if probe {
		g.probes++
	} else {
		g.active++
		g.lastActivity = time.Now()
	}
	g.mu.Unlock()
	var once sync.Once
	return actual, func() {
		once.Do(func() {
			g.mu.Lock()
			if probe {
				g.probes--
			} else {
				g.active--
				g.lastActivity = time.Now()
			}
			g.mu.Unlock()
		})
	}, nil
}

type probeContextKey struct{}

func (g *exitGate) fallback(ctx context.Context) adapter.Outbound {
	metadata := adapter.ContextFrom(ctx)
	if metadata == nil || metadata.LerNETProtected || metadata.LerNETFallback != "direct" {
		return nil
	}
	return g.direct
}

func (g *exitGate) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	probe, _ := ctx.Value(probeContextKey{}).(bool)
	budget := newFlowBudget(ctx, g.ctx, g.setupBudget(probe))
	transferred := false
	defer func() {
		if !transferred {
			budget.close()
		}
	}()
	ctx = budget
	actual, release, err := g.acquire(ctx, probe)
	if err == nil {
		stopTransport, bindErr := g.bindTransport(budget, actual)
		if bindErr != nil {
			release()
			return nil, bindErr
		}
		defer func() {
			if !transferred {
				stopTransport()
			}
		}()
		var conn net.Conn
		conn, err = actual.DialContext(ctx, network, destination)
		if err == nil {
			cleanup := func() { stopTransport(); release(); budget.close() }
			wrapped := &gateConn{Conn: conn, release: cleanup, touch: g.touch, probe: probe}
			if !g.track(wrapped, cleanup, actual) {
				return nil, ErrStopped
			}
			if err = budget.connected(); err != nil {
				_ = wrapped.Close()
				return nil, err
			}
			if !probe {
				observeFlow(ctx, g.tag, "")
			}
			transferred = true
			return wrapped, nil
		}
		release()
		stopTransport()
	}
	if !probe {
		if direct := g.fallback(ctx); direct != nil {
			conn, dialErr := direct.DialContext(ctx, network, destination)
			if dialErr == nil {
				if err = budget.connected(); err != nil {
					_ = conn.Close()
					return nil, err
				}
				observeFlow(ctx, direct.Tag(), "direct_fallback")
				transferred = true
				return &budgetConn{Conn: conn, budget: budget}, nil
			}
			return conn, dialErr
		}
	}
	return nil, err
}

func (g *exitGate) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	probe, _ := ctx.Value(probeContextKey{}).(bool)
	budget := newFlowBudget(ctx, g.ctx, g.setupBudget(probe))
	transferred := false
	defer func() {
		if !transferred {
			budget.close()
		}
	}()
	ctx = budget
	actual, release, err := g.acquire(ctx, probe)
	if err == nil {
		stopTransport, bindErr := g.bindTransport(budget, actual)
		if bindErr != nil {
			release()
			return nil, bindErr
		}
		defer func() {
			if !transferred {
				stopTransport()
			}
		}()
		var conn net.PacketConn
		conn, err = actual.ListenPacket(ctx, destination)
		if err == nil {
			cleanup := func() { stopTransport(); release(); budget.close() }
			wrapped := &gatePacketConn{PacketConn: conn, release: cleanup, touch: g.touch, probe: probe}
			if !g.track(wrapped, cleanup, actual) {
				return nil, ErrStopped
			}
			if err = budget.connected(); err != nil {
				_ = wrapped.Close()
				return nil, err
			}
			if !probe {
				observeFlow(ctx, g.tag, "")
			}
			transferred = true
			return wrapped, nil
		}
		release()
		stopTransport()
	}
	if !probe {
		if direct := g.fallback(ctx); direct != nil {
			conn, dialErr := direct.ListenPacket(ctx, destination)
			if dialErr == nil {
				if err = budget.connected(); err != nil {
					_ = conn.Close()
					return nil, err
				}
				observeFlow(ctx, direct.Tag(), "direct_fallback")
				transferred = true
				return &budgetPacketConn{PacketConn: conn, budget: budget}, nil
			}
			return conn, dialErr
		}
	}
	return nil, err
}

func (g *exitGate) touch() { g.mu.Lock(); g.lastActivity = time.Now(); g.mu.Unlock() }

func (g *exitGate) matchesGeneration(generation int64) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return !g.closed && g.phase == "ready" && g.transportGeneration == generation
}

func (g *exitGate) bindTransport(b *flowBudget, actual adapter.Outbound) (func() bool, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.closed || g.phase != "ready" || g.actual != actual {
		return nil, ErrStopped
	}
	if g.transportContext == nil {
		g.transportContext, g.transportCancel = context.WithCancel(g.ctx)
	}
	return context.AfterFunc(g.transportContext, func() { b.cancel(context.Canceled) }), nil
}

func observeFlow(ctx context.Context, outbound, reason string) {
	if m := adapter.ContextFrom(ctx); m != nil && m.LerNETFlowObserver != nil {
		m.LerNETFlowObserver(outbound, reason)
	}
}

func (g *exitGate) track(handle io.Closer, release func(), actual adapter.Outbound) bool {
	wrappedRelease := func() { g.mu.Lock(); delete(g.handles, handle); g.mu.Unlock(); release() }
	switch typed := handle.(type) {
	case *gateConn:
		typed.release = wrappedRelease
	case *gatePacketConn:
		typed.release = wrappedRelease
	}
	g.mu.Lock()
	if g.handles == nil {
		g.handles = make(map[io.Closer]bool)
	}
	stale := g.closed || g.actual != actual || g.phase != "ready"
	if !stale {
		g.handles[handle] = true
	}
	g.mu.Unlock()
	if stale {
		_ = handle.Close()
	}
	return !stale
}

func (g *exitGate) recover(ctx context.Context) error {
	return g.recoverAt(ctx, -1)
}

func (g *exitGate) recoverAt(ctx context.Context, expectedGeneration int64) error {
	ctx, cancel := context.WithTimeout(ctx, time.Duration(g.limits.FirstFlowTimeoutMs)*time.Millisecond)
	defer cancel()
	stop := context.AfterFunc(g.ctx, cancel)
	defer stop()
	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		return ErrStopped
	}
	if g.cleanupErr != nil || g.stopping != 0 {
		err := g.cleanupStateLocked()
		g.mu.Unlock()
		return err
	}
	if expectedGeneration >= 0 && (g.transportGeneration != expectedGeneration || g.phase != "ready") {
		g.mu.Unlock()
		return ErrConflict
	}
	w := g.startup
	if w == nil {
		actual := g.actual
		g.actual = nil
		providerCancel := g.providerCancel
		g.providerCancel = nil
		handles := make([]io.Closer, 0, len(g.handles))
		for handle := range g.handles {
			handles = append(handles, handle)
		}
		g.lastError = "tunnel_recovering"
		w = g.beginWakeLocked(func() error {
			if providerCancel != nil {
				providerCancel()
			}
			for _, handle := range handles {
				_ = handle.Close()
			}
			return g.closeProvider(actual)
		})
	}
	g.mu.Unlock()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-w.done:
		return w.err
	}
}

func (g *exitGate) wake(ctx context.Context) error {
	actual, release, err := g.acquire(ctx, false)
	if err != nil {
		return err
	}
	_ = actual
	release()
	return nil
}

func (g *exitGate) sleep(idleOnly bool) error {
	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		return ErrStopped
	}
	if g.cleanupErr != nil || g.stopping != 0 {
		err := g.cleanupStateLocked()
		g.mu.Unlock()
		return err
	}
	if g.active != 0 || g.probes != 0 || g.pending != 0 || g.startup != nil {
		g.mu.Unlock()
		return errors.New("exit_has_active_flows")
	}
	if idleOnly && (g.limits.Mode != "cold" || time.Since(g.lastActivity) < time.Duration(g.limits.IdleTimeoutMs)*time.Millisecond) {
		g.mu.Unlock()
		return nil
	}
	actual := g.actual
	g.actual, g.phase = nil, "stopping"
	w := &wakeAttempt{done: make(chan struct{})}
	g.stopAttempt = w
	if g.providerCancel != nil {
		g.providerCancel()
		g.providerCancel = nil
	}
	g.stopping++
	if g.transportCancel != nil {
		g.transportCancel()
		g.transportCancel = nil
	}
	g.latency = nil
	g.lastCheck = time.Time{}
	g.healthFailures = 0
	g.mu.Unlock()
	err := g.closeProvider(actual)
	g.mu.Lock()
	g.stopping--
	w.finish(err)
	if g.stopAttempt == w {
		g.stopAttempt = nil
	}
	if !g.closed && err == nil {
		g.phase, g.lastError = "sleeping", ""
	}
	g.mu.Unlock()
	return err
}

// Cleanup failures retain ownership. A second provider may not be created
// while an old provider's worker/socket cleanup is unproved.
func (g *exitGate) closeProvider(actual adapter.Outbound) error {
	if actual == nil {
		return nil
	}
	if err := common.Close(actual); err != nil {
		g.mu.Lock()
		g.failedProviders = append(g.failedProviders, actual)
		g.cleanupErr, g.lastError = ErrExitCleanup, "exit_stop_failed"
		if !g.closed {
			g.phase = "failed"
		}
		g.mu.Unlock()
		return ErrExitCleanup
	}
	return nil
}

func (g *exitGate) cleanupStateLocked() error {
	if g.cleanupErr != nil {
		return g.cleanupErr
	}
	if g.startup != nil || g.stopping != 0 {
		return ErrClosePending
	}
	return nil
}

func (g *exitGate) cleanupState() error {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.cleanupStateLocked()
}

func (g *exitGate) Close() error {
	g.mu.Lock()
	if g.closed {
		err := g.cleanupStateLocked()
		g.mu.Unlock()
		return err
	}
	g.closed, g.phase = true, "closed"
	if g.stopAttempt != nil {
		g.stopAttempt.finish(ErrStopped)
	}
	if g.transportCancel != nil {
		g.transportCancel()
		g.transportCancel = nil
	}
	if g.startup != nil {
		if g.startup.providerCancel != nil {
			g.startup.providerCancel()
		}
		g.startup.cancel()
		g.startup.finish(ErrStopped)
	}
	actual := g.actual
	g.actual = nil
	if g.providerCancel != nil {
		g.providerCancel()
		g.providerCancel = nil
	}
	g.stopping++
	handles := make([]io.Closer, 0, len(g.handles))
	for handle := range g.handles {
		handles = append(handles, handle)
	}
	g.mu.Unlock()
	for _, handle := range handles {
		_ = handle.Close()
	}
	_ = g.closeProvider(actual)
	g.mu.Lock()
	g.stopping--
	err := g.cleanupStateLocked()
	g.mu.Unlock()
	return err
}

func (g *exitGate) status() ExitStatus {
	g.mu.Lock()
	defer g.mu.Unlock()
	health := "unknown"
	if g.latency != nil {
		health = "healthy"
	} else if !g.lastCheck.IsZero() {
		health = "degraded"
	}
	lastCheck := int64(0)
	if !g.lastCheck.IsZero() {
		lastCheck = g.lastCheck.UnixMilli()
	}
	var latency *int64
	if g.latency != nil {
		copy := *g.latency
		latency = &copy
	}
	return ExitStatus{Tag: g.tag, Phase: g.phase, PendingFlows: g.pending, ActiveFlows: g.active, LastActivityMs: g.lastActivity.UnixMilli(), Reason: g.lastError, LatencyMs: latency, LastCheckMs: lastCheck, Failures: g.healthFailures, Health: health}
}

func (g *exitGate) recordHealth(result ProbeResult, epoch, transportGeneration int64) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.closed || g.phase != "ready" || epoch != g.networkEpoch || transportGeneration != g.transportGeneration {
		return false
	}
	if result.Reason == "exit_sleeping" || result.Reason == "operation_cancelled" {
		return true
	}
	g.lastCheck = time.Now()
	g.latency = result.HTTPSLatencyMs
	if result.HTTPSLatencyMs != nil {
		g.healthFailures = 0
		g.lastError = ""
	} else {
		g.healthFailures++
		g.lastError = result.Reason
	}
	return true
}
func (g *exitGate) invalidateNetwork() {
	g.mu.Lock()
	g.networkEpoch++
	g.latency = nil
	g.lastCheck = time.Time{}
	g.healthFailures = 0
	g.nextHealth = time.Time{}
	g.mu.Unlock()
}

type gateConn struct {
	net.Conn
	once    sync.Once
	release func()
	touch   func()
	probe   bool
}

func (c *gateConn) Close() error { err := c.Conn.Close(); c.once.Do(c.release); return err }
func (c *gateConn) Read(p []byte) (int, error) {
	n, e := c.Conn.Read(p)
	if n > 0 && !c.probe {
		c.touch()
	}
	return n, e
}
func (c *gateConn) Write(p []byte) (int, error) {
	n, e := c.Conn.Write(p)
	if n > 0 && !c.probe {
		c.touch()
	}
	return n, e
}

type gatePacketConn struct {
	net.PacketConn
	once    sync.Once
	release func()
	touch   func()
	probe   bool
}

func (c *gatePacketConn) Close() error { err := c.PacketConn.Close(); c.once.Do(c.release); return err }
func (c *gatePacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, a, e := c.PacketConn.ReadFrom(p)
	if n > 0 && !c.probe {
		c.touch()
	}
	return n, a, e
}
func (c *gatePacketConn) WriteTo(p []byte, a net.Addr) (int, error) {
	n, e := c.PacketConn.WriteTo(p, a)
	if n > 0 && !c.probe {
		c.touch()
	}
	return n, e
}

var _ io.Closer = (*exitGate)(nil)
