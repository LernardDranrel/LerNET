package expert

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"net"
	"net/url"
	"strings"
	"time"
)

// Report only typed categories: provider error text may contain credentials.
func probeFailureCode(err error) string {
	var certificate *tls.CertificateVerificationError
	var authority x509.UnknownAuthorityError
	var invalid x509.CertificateInvalidError
	var hostname x509.HostnameError
	var dns *net.DNSError
	var network net.Error
	switch {
	case errors.As(err, &certificate), errors.As(err, &authority), errors.As(err, &invalid), errors.As(err, &hostname):
		return "https_probe_certificate_failed"
	case errors.As(err, &dns):
		return "https_probe_dns_failed"
	case errors.Is(err, context.DeadlineExceeded):
		return "https_probe_timeout"
	case errors.As(err, &network) && network.Timeout():
		return "https_probe_timeout"
	default:
		return "https_probe_failed"
	}
}

// Restarting a transport cannot repair the probe site's certificate, DNS or
// HTTP rejection. Keep these failures visible without tearing down user flows.
func probeNeedsRecovery(result ProbeResult) bool {
	if result.HTTPSLatencyMs != nil || strings.HasPrefix(result.Reason, "https_status_") {
		return false
	}
	switch result.Reason {
	case "https_probe_certificate_failed", "https_probe_dns_failed", "invalid_probe_request",
		"exit_sleeping", "operation_cancelled", "exit_transport_changed":
		return false
	default:
		return true
	}
}

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

// classifyHealth decides what one finished probe means.
// recover is true only when this failure is past the threshold: the caller
// then has to run recovery and log that result separately. An ok line is not recovery.
func classifyHealth(hasLatency bool, reason string, failures, threshold int, needsRecovery bool) (kind, outcome, code string, recover bool) {
	if hasLatency {
		return "state", "ok", "", false
	}
	if !needsRecovery {
		return "state", "skip", reason, false
	}
	if failures < threshold {
		return "state", "below", reason, false
	}
	return "edge", "attempt", reason, true
}

func probeHost(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	return parsed.Hostname()
}

func noteHealth(kind, outcome, code, stage, state, domain string, mono int64) {
	fields := make([]string, 0, 6)
	if outcome != "" {
		fields = append(fields, "outcome="+outcome)
	}
	if code != "" {
		fields = append(fields, "code="+diagToken(code))
	}
	if stage != "" {
		fields = append(fields, "stage="+stage)
	}
	if state != "" {
		fields = append(fields, "state="+state)
	}
	if domain != "" {
		fields = append(fields, "domain="+diagToken(domain))
	}
	if mono > 0 {
		fields = append(fields, "mono="+itoa64(mono))
	}
	emitDiag("health_gate", kind, fields...)
}

func noteRecovery(err error, state string) {
	if err != nil {
		noteHealth("edge", "fail", ErrorCode(err), "recover", state, "", 0)
		return
	}
	noteHealth("edge", "ok", "", "recover", state, "", 0)
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
		started := time.Now()
		result := probeGate(g.ctx, g, g.probeURL, schedule.ActiveProbeTimeoutMs)
		measured := time.Since(started).Milliseconds()
		host := probeHost(g.probeURL)
		g.mu.Lock()
		if g.closed || g.phase != "ready" || g.networkEpoch != epoch || g.transportGeneration != transportGeneration {
			g.mu.Unlock()
			noteHealth("edge", "stale", "", "https", "", host, measured)
			return
		}
		kind, outcome, code, recover := classifyHealth(result.HTTPSLatencyMs != nil, result.Reason, g.healthFailures, schedule.FailedChecksBeforeRecovery, probeNeedsRecovery(result))
		failures := g.healthFailures
		state := "f" + itoa(failures)
		if result.HTTPSLatencyMs != nil {
			latency := *result.HTTPSLatencyMs
			g.healthFailures = 0
			g.mu.Unlock()
			noteHealth(kind, outcome, code, "https", "", host, latency)
			return
		}
		if !recover {
			g.mu.Unlock()
			noteHealth(kind, outcome, code, "https", state, host, measured)
			return
		}
		g.healthFailures = 0
		g.lastError = "tunnel_health_failed"
		g.mu.Unlock()
		noteHealth(kind, outcome, code, "https", state, host, measured)
		noteRecovery(g.recoverAt(g.ctx, transportGeneration), state)
		again := probeGate(g.ctx, g, g.probeURL, schedule.ActiveProbeTimeoutMs)
		if again.HTTPSLatencyMs != nil {
			noteHealth("state", "ok", "", "https", state, host, *again.HTTPSLatencyMs)
			return
		}
		noteHealth("edge", "fail", again.Reason, "https", state, host, 0)
	}()
}
