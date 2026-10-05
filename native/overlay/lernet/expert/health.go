package expert

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"net"
	"time"
)

type probeDialConn struct {
	net.Conn
	cancel    context.CancelFunc
	stopProbe func() bool
}

func (c *probeDialConn) Close() error {
	c.stopProbe()
	c.cancel()
	return c.Conn.Close()
}

func healthInterval(minimum, maximum int64) time.Duration {
	if minimum <= 0 || maximum < minimum {
		minimum, maximum = 3000, 7000
	}
	var random [8]byte
	if _, err := rand.Read(random[:]); err != nil {
		return time.Duration(minimum+(maximum-minimum)/2) * time.Millisecond
	}
	return time.Duration(minimum)*time.Millisecond + time.Duration(binary.LittleEndian.Uint64(random[:])%uint64((maximum-minimum)*int64(time.Millisecond)+1))
}

func (g *exitGate) setupBudget(probe bool) time.Duration {
	if probe {
		// probeGate's caller deadline owns active/candidate/manual timing. A
		// business cold-flow timeout must not silently shorten that setting.
		return 45 * time.Second
	}
	return time.Duration(g.limits.FirstFlowTimeoutMs) * time.Millisecond
}

func (g *exitGate) healthOptions() HealthOptions {
	if g.healthSchedule == (HealthOptions{}) {
		return defaultHealthOptions()
	}
	return g.healthSchedule
}

// A dead multiplexed transport is rebuilt independently. Closing only this
// exit's handles makes affected applications retry; unrelated exits/TUN stay.
func (g *exitGate) checkHealth() {
	g.mu.Lock()
	if g.closed || g.phase != "ready" || g.healthChecking || g.probeURL == "" || time.Now().Before(g.nextHealth) || (g.limits.Mode == "cold" && g.active == 0) {
		g.mu.Unlock()
		return
	}
	g.healthChecking = true
	schedule := g.healthOptions()
	g.nextHealth = time.Now().Add(healthInterval(schedule.ProbeMinIntervalMs, schedule.ProbeMaxIntervalMs))
	epoch := g.networkEpoch
	transportGeneration := g.transportGeneration
	g.mu.Unlock()
	go func() {
		defer func() { g.mu.Lock(); g.healthChecking = false; g.mu.Unlock() }()
		result := probeGate(g.ctx, g, g.probeURL, schedule.ActiveProbeTimeoutMs)
		g.mu.Lock()
		if g.closed || g.phase != "ready" || g.networkEpoch != epoch || g.transportGeneration != transportGeneration {
			g.mu.Unlock()
			return
		}
		if result.HTTPSLatencyMs != nil {
			g.healthFailures = 0
			g.mu.Unlock()
			return
		}
		if result.Reason == "exit_sleeping" || result.Reason == "exit_transport_changed" {
			g.mu.Unlock()
			return
		}
		if g.healthFailures < schedule.FailedChecksBeforeRecovery {
			g.mu.Unlock()
			return
		}
		g.healthFailures = 0
		g.lastError = "tunnel_health_failed"
		g.mu.Unlock()
		_ = g.recoverAt(g.ctx, transportGeneration)
		_ = probeGate(g.ctx, g, g.probeURL, schedule.ActiveProbeTimeoutMs)
	}()
}
