package expert

import (
	"context"
	"errors"
	"net"
	"sort"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

type candidateHealth struct {
	latency             int64
	checked             time.Time
	failedUntil         time.Time
	transportGeneration int64
	gate                *exitGate
}
type folderSelection struct {
	done chan struct{}
	tag  string
	err  error
}
type folderGate struct {
	ctx            context.Context
	registry       *gateRegistry
	options        FolderOptions
	mu             sync.Mutex
	selected       string
	health         map[string]candidateHealth
	pending        int
	selection      *folderSelection
	healthChecking bool
	failures       int
	direct         adapter.Outbound
	networkEpoch   int64
	nextHealth     time.Time
}

func (f *folderGate) Type() string      { return "selector" }
func (f *folderGate) Tag() string       { return f.options.Tag }
func (f *folderGate) Network() []string { return []string{"tcp", "udp"} }
func (f *folderGate) Dependencies() []string {
	return append([]string(nil), f.options.CandidateTags...)
}

func (f *folderGate) choose(ctx context.Context) (*exitGate, error) {
	f.mu.Lock()
	if f.pending >= f.options.MaxPendingFlows {
		f.mu.Unlock()
		return nil, ErrBusy
	}
	f.pending++
	defer func() { f.mu.Lock(); f.pending--; f.mu.Unlock() }()
	if f.selected != "" {
		sample := f.health[f.selected]
		if sample.latency > 0 && sample.gate != nil && sample.gate.matchesGeneration(sample.transportGeneration) && time.Since(sample.checked) < time.Duration(f.options.HealthTTLms)*time.Millisecond && time.Now().After(sample.failedUntil) {
			tag := f.selected
			f.mu.Unlock()
			return f.registry.gate(tag)
		}
	}
	w := f.selection
	if w == nil {
		w = &folderSelection{done: make(chan struct{})}
		f.selection = w
		// All callers share a native selection operation, but each keeps its own
		// first-flow deadline. Selection itself has the same bounded global budget.
		selectionCtx, cancel := context.WithTimeout(f.ctx, time.Duration(f.options.FirstFlowTimeoutMs)*time.Millisecond)
		epoch := f.networkEpoch
		go func() {
			defer cancel()
			tag, err := f.selectCandidate(selectionCtx)
			f.mu.Lock()
			if epoch != f.networkEpoch {
				err = errors.New("network_changed_during_selection")
			}
			w.tag, w.err = tag, err
			if err == nil {
				f.selected = tag
				f.failures = 0
			}
			if f.selection == w {
				f.selection = nil
			}
			close(w.done)
			f.mu.Unlock()
		}()
	}
	f.mu.Unlock()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-w.done:
	}
	if w.err != nil {
		return nil, w.err
	}
	return f.registry.gate(w.tag)
}

func (f *folderGate) selectCandidate(ctx context.Context) (string, error) {
	f.mu.Lock()
	previous := f.selected
	f.mu.Unlock()
	tags := append([]string(nil), f.options.CandidateTags...)
	if !f.options.AutoSwap {
		if previous != "" {
			tags = []string{previous}
		} else if f.options.PreferredTag != "" && f.options.Selection == "preferred" {
			tags = []string{f.options.PreferredTag}
		}
	}
	preferred := f.options.PreferredTag
	if previous != "" {
		preferred = previous
	}
	sort.SliceStable(tags, func(i, j int) bool { return tags[i] == preferred && tags[j] != preferred })
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type candidateResult struct {
		tag     string
		latency *int64
	}
	results := make(chan candidateResult, len(tags))
	slots := make(chan struct{}, 4)
	for _, tag := range tags {
		tag := tag
		go func() {
			select {
			case slots <- struct{}{}:
			case <-ctx.Done():
				results <- candidateResult{tag: tag}
				return
			}
			defer func() { <-slots }()
			f.mu.Lock()
			sample := f.health[tag]
			epoch := f.networkEpoch
			f.mu.Unlock()
			if time.Now().Before(sample.failedUntil) {
				results <- candidateResult{tag: tag}
				return
			}
			gate, err := f.registry.gate(tag)
			if err != nil {
				results <- candidateResult{tag: tag}
				return
			}
			if err = gate.wake(ctx); err != nil {
				f.failedEpoch(tag, epoch)
				results <- candidateResult{tag: tag}
				return
			}
			result := probeGate(ctx, gate, f.options.ProbeURL, f.options.ProbeTimeoutMs)
			if result.HTTPSLatencyMs == nil && ctx.Err() == nil && result.Reason != "exit_transport_changed" {
				if gate.recoverAt(ctx, result.transportGeneration) == nil {
					result = probeGate(ctx, gate, f.options.ProbeURL, f.options.ProbeTimeoutMs)
				}
			}
			if result.HTTPSLatencyMs == nil {
				if result.Reason != "exit_transport_changed" {
					f.failedEpoch(tag, epoch)
				}
				results <- candidateResult{tag: tag}
				return
			}
			f.mu.Lock()
			valid := f.recordCandidateLocked(gate, result, epoch)
			f.mu.Unlock()
			if !valid {
				results <- candidateResult{tag: tag}
				return
			}
			results <- candidateResult{tag: tag, latency: result.HTTPSLatencyMs}
		}()
	}
	best := ""
	bestLatency := int64(0)
	var settle <-chan time.Time
	var settleTimer *time.Timer
	defer func() {
		if settleTimer != nil {
			settleTimer.Stop()
		}
	}()
	preferredPending := preferred != ""
	var preferredResult *candidateResult
	for remaining := len(tags); remaining > 0; remaining-- {
		select {
		case <-ctx.Done():
			if best != "" {
				return best, nil
			}
			return "", ctx.Err()
		case <-settle:
			if best != "" {
				return best, nil
			}
		case result := <-results:
			if result.tag == preferred {
				preferredPending = false
				copy := result
				preferredResult = &copy
			}
			if result.latency != nil && (best == "" || *result.latency < bestLatency) {
				best, bestLatency = result.tag, *result.latency
			}
			// Once a usable candidate answered, allow a small bounded comparison
			// window. A hanging neighbour must not consume the original flow's
			// entire connection budget after another candidate is already healthy.
			if best != "" && settle == nil {
				settleTimer = time.NewTimer(500 * time.Millisecond)
				settle = settleTimer.C
			}
			if previous != "" && result.tag == previous && result.latency != nil {
				return previous, nil
			}
			if f.options.Selection == "preferred" && !preferredPending {
				if preferredResult != nil && preferredResult.latency != nil {
					return preferredResult.tag, nil
				}
				if best != "" {
					return best, nil
				}
			}
		}
	}
	if best == "" {
		return "", errors.New("folder_has_no_healthy_exit")
	}
	return best, nil
}

// Call with f.mu held. Retain the gate lock through publication so a concurrent
// transport replacement cannot make a result describe its successor.
func (f *folderGate) recordCandidateLocked(gate *exitGate, result ProbeResult, epoch int64) bool {
	gate.mu.Lock()
	defer gate.mu.Unlock()
	if gate.closed || gate.phase != "ready" || gate.transportGeneration != result.transportGeneration || f.networkEpoch != epoch || result.HTTPSLatencyMs == nil {
		return false
	}
	f.health[gate.tag] = candidateHealth{latency: *result.HTTPSLatencyMs, checked: time.Now(), transportGeneration: result.transportGeneration, gate: gate}
	return true
}

func (f *folderGate) failed(tag string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	sample := f.health[tag]
	sample.latency = 0
	sample.failedUntil = time.Now().Add(time.Duration(f.options.CooldownMs) * time.Millisecond)
	f.health[tag] = sample
}
func (f *folderGate) failedEpoch(tag string, epoch int64) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if epoch != f.networkEpoch {
		return
	}
	sample := f.health[tag]
	sample.latency = 0
	sample.failedUntil = time.Now().Add(time.Duration(f.options.CooldownMs) * time.Millisecond)
	f.health[tag] = sample
}

func (f *folderGate) fallback(ctx context.Context) adapter.Outbound {
	m := adapter.ContextFrom(ctx)
	if m == nil || m.LerNETProtected || m.LerNETFallback != "direct" {
		return nil
	}
	return f.direct
}

func (f *folderGate) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	budget := newFlowBudget(ctx, f.ctx, time.Duration(f.options.FirstFlowTimeoutMs)*time.Millisecond)
	transferred := false
	defer func() {
		if !transferred {
			budget.close()
		}
	}()
	ctx = budget
	finish := func(conn net.Conn) (net.Conn, error) {
		if err := budget.connected(); err != nil {
			_ = conn.Close()
			return nil, err
		}
		transferred = true
		return &budgetConn{Conn: conn, budget: budget}, nil
	}
	probe, _ := ctx.Value(probeContextKey{}).(bool)
	if probe {
		f.mu.Lock()
		tag := f.selected
		f.mu.Unlock()
		if tag == "" {
			return nil, ErrSleeping
		}
		gate, err := f.registry.gate(tag)
		if err != nil {
			return nil, err
		}
		conn, err := gate.DialContext(ctx, network, destination)
		if err != nil {
			return nil, err
		}
		return finish(conn)
	}
	deadline := ctx
	var last error
	for attempt := 0; attempt < len(f.options.CandidateTags); attempt++ {
		gate, err := f.choose(deadline)
		if err != nil {
			last = err
			break
		}
		f.mu.Lock()
		epoch := f.networkEpoch
		f.mu.Unlock()
		conn, err := gate.DialContext(deadline, network, destination)
		if err == nil {
			return finish(conn)
		}
		if attempt == 0 && deadline.Err() == nil && gate.recover(deadline) == nil {
			if retried, retryErr := gate.DialContext(deadline, network, destination); retryErr == nil {
				return finish(retried)
			} else {
				err = retryErr
			}
		}
		last = err
		f.failedEpoch(gate.tag, epoch)
		if !f.options.AutoSwap || deadline.Err() != nil {
			break
		}
	}
	if direct := f.fallback(ctx); direct != nil {
		conn, dialErr := direct.DialContext(deadline, network, destination)
		if dialErr == nil {
			observeFlow(ctx, direct.Tag(), "direct_fallback")
			return finish(conn)
		}
		return conn, dialErr
	}
	return nil, last
}

func (f *folderGate) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	budget := newFlowBudget(ctx, f.ctx, time.Duration(f.options.FirstFlowTimeoutMs)*time.Millisecond)
	transferred := false
	defer func() {
		if !transferred {
			budget.close()
		}
	}()
	ctx = budget
	finish := func(conn net.PacketConn) (net.PacketConn, error) {
		if err := budget.connected(); err != nil {
			_ = conn.Close()
			return nil, err
		}
		transferred = true
		return &budgetPacketConn{PacketConn: conn, budget: budget}, nil
	}
	deadline := ctx
	var err error
	for attempt := 0; attempt < len(f.options.CandidateTags); attempt++ {
		gate, chooseErr := f.choose(deadline)
		err = chooseErr
		if err != nil {
			break
		}
		f.mu.Lock()
		epoch := f.networkEpoch
		f.mu.Unlock()
		var conn net.PacketConn
		conn, err = gate.ListenPacket(deadline, destination)
		if err == nil {
			return finish(conn)
		}
		if attempt == 0 && deadline.Err() == nil && gate.recover(deadline) == nil {
			conn, err = gate.ListenPacket(deadline, destination)
			if err == nil {
				return finish(conn)
			}
		}
		f.failedEpoch(gate.tag, epoch)
		if !f.options.AutoSwap || deadline.Err() != nil {
			break
		}
	}
	if direct := f.fallback(ctx); direct != nil {
		conn, dialErr := direct.ListenPacket(deadline, destination)
		if dialErr == nil {
			observeFlow(ctx, direct.Tag(), "direct_fallback")
			return finish(conn)
		}
		return conn, dialErr
	}
	return nil, err
}

// Background checks do not wake a cold exit or reset its business idle timer.
func (f *folderGate) checkHealth() {
	f.mu.Lock()
	tag := f.selected
	if tag == "" || f.healthChecking || time.Now().Before(f.nextHealth) {
		f.mu.Unlock()
		return
	}
	f.nextHealth = time.Now().Add(healthInterval(f.options.ProbeMinIntervalMs, f.options.ProbeMaxIntervalMs))
	f.healthChecking = true
	epoch := f.networkEpoch
	f.mu.Unlock()
	budget := f.options.ActiveProbeTimeoutMs
	if budget == 0 {
		budget = 4000
	}
	go func() {
		gate, err := f.registry.gate(tag)
		result := ProbeResult{Reason: "exit_unavailable"}
		if err == nil {
			result = probeGate(f.ctx, gate, f.options.ProbeURL, budget)
		}
		f.mu.Lock()
		if f.selected != tag || f.networkEpoch != epoch {
			f.healthChecking = false
			f.mu.Unlock()
			return
		}
		if result.HTTPSLatencyMs != nil {
			if f.recordCandidateLocked(gate, result, epoch) {
				f.failures = 0
			}
			f.healthChecking = false
			f.mu.Unlock()
			return
		}
		if result.Reason == "exit_sleeping" || result.Reason == "operation_cancelled" || result.Reason == "exit_transport_changed" {
			f.healthChecking = false
			f.mu.Unlock()
			return
		}
		if gate != nil && !gate.matchesGeneration(result.transportGeneration) {
			f.healthChecking = false
			f.mu.Unlock()
			return
		}
		threshold := f.options.FailedChecksBeforeRecovery
		if threshold == 0 {
			threshold = 2
		}
		f.failures++
		if f.failures < threshold {
			f.healthChecking = false
			f.mu.Unlock()
			return
		}
		sample := f.health[tag]
		sample.latency = 0
		sample.failedUntil = time.Now().Add(time.Duration(f.options.CooldownMs) * time.Millisecond)
		f.health[tag] = sample
		f.mu.Unlock()
		// Restart the dead pool and prove HTTPS again before reporting healthy.
		if gate != nil && gate.recoverAt(f.ctx, result.transportGeneration) == nil {
			result = probeGate(f.ctx, gate, f.options.ProbeURL, budget)
		}
		f.mu.Lock()
		f.healthChecking = false
		if f.selected == tag && f.networkEpoch == epoch && result.HTTPSLatencyMs != nil {
			if f.recordCandidateLocked(gate, result, epoch) {
				f.failures = 0
			}
		}
		f.mu.Unlock()
	}()
}

func (f *folderGate) invalidateNetwork() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.networkEpoch++
	for tag, sample := range f.health {
		sample.latency = 0
		sample.failedUntil = time.Time{}
		f.health[tag] = sample
	}
	f.failures = 0
}

func (f *folderGate) status() any {
	f.mu.Lock()
	defer f.mu.Unlock()
	return struct {
		Tag          string `json:"tag"`
		SelectedTag  string `json:"selected_tag,omitempty"`
		PendingFlows int    `json:"pending_flows"`
		Failures     int    `json:"failures"`
	}{f.options.Tag, f.selected, f.pending, f.failures}
}
