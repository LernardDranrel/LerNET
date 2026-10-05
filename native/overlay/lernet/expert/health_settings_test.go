package expert

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
)

type healthTestRegistry struct {
	adapter.OutboundRegistry
	provider adapter.Outbound
}

func (r healthTestRegistry) CreateOutbound(context.Context, adapter.Router, log.ContextLogger, string, string, any) (adapter.Outbound, error) {
	return r.provider, nil
}

func TestLerNETGlobalHealthManifestValidatesAndCopiesSchedule(t *testing.T) {
	chosen := HealthOptions{11000, 13000, 1500, 3}
	manifest := Manifest{DirectTag: "direct", Health: &chosen}
	if err := manifest.Validate(); err != nil {
		t.Fatal(err)
	}
	for range 100 {
		interval := healthInterval(chosen.ProbeMinIntervalMs, chosen.ProbeMaxIntervalMs)
		if interval < 11*time.Second || interval > 13*time.Second {
			t.Fatal("random heartbeat outside persisted user bounds")
		}
	}
	for _, invalid := range []HealthOptions{{999, 13000, 1500, 3}, {11000, 10999, 1500, 3}, {11000, 60001, 1500, 3}, {11000, 13000, 999, 3}, {11000, 13000, 15001, 3}, {11000, 13000, 1500, 0}, {11000, 13000, 1500, 11}} {
		manifest.Health = &invalid
		if manifest.Validate() == nil {
			t.Fatalf("invalid health settings accepted: %+v", invalid)
		}
	}
	manifest.Health = &chosen
	p := &acceptanceProvider{release: make(chan struct{})}
	close(p.release)
	manifest.Exits = []ExitOptions{{Tag: "exit-a", Mode: "warm", IdleTimeoutMs: 1000, FirstFlowTimeoutMs: 1000, StartupTimeoutMs: 1000, MaxPendingFlows: 10}}
	registry := &gateRegistry{OutboundRegistry: healthTestRegistry{provider: p}, manifest: manifest}
	actual, err := registry.CreateOutbound(context.Background(), nil, log.NewNOPFactory().Logger(), "exit-a", "acceptance", nil)
	if err != nil {
		t.Fatal(err)
	}
	gate := actual.(*exitGate)
	defer gate.Close()
	if gate.healthOptions() != chosen {
		t.Fatal("physical gate lost persisted schedule")
	}
	chosen.ProbeMinIntervalMs = 5000
	if gate.healthOptions().ProbeMinIntervalMs != 11000 {
		t.Fatal("generation health settings remained mutable")
	}
	registry.manifest.Folders = []FolderOptions{{Tag: "inherited"}, {Tag: "explicit", ProbeMinIntervalMs: 2000, ProbeMaxIntervalMs: 2500, ActiveProbeTimeoutMs: 1100, FailedChecksBeforeRecovery: 4}}
	inherited, err := registry.CreateOutbound(context.Background(), nil, log.NewNOPFactory().Logger(), "inherited", "selector", nil)
	if err != nil {
		t.Fatal(err)
	}
	if inherited.(*folderGate).options.ProbeMinIntervalMs != chosen.ProbeMinIntervalMs || inherited.(*folderGate).options.ActiveProbeTimeoutMs != 1500 || inherited.(*folderGate).options.FailedChecksBeforeRecovery != 3 {
		t.Fatal("folder omitted fields did not inherit global health")
	}
	explicit, err := registry.CreateOutbound(context.Background(), nil, log.NewNOPFactory().Logger(), "explicit", "selector", nil)
	if err != nil {
		t.Fatal(err)
	}
	if explicit.(*folderGate).options.ProbeMinIntervalMs != 2000 || explicit.(*folderGate).options.ActiveProbeTimeoutMs != 1100 || explicit.(*folderGate).options.FailedChecksBeforeRecovery != 4 {
		t.Fatal("global health overwrote explicit folder settings")
	}
}

type scheduledHealthProvider struct {
	*acceptanceProvider
	deadlines chan time.Duration
	calls     *atomic.Int32
}

func (p *scheduledHealthProvider) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	p.calls.Add(1)
	deadline, ok := ctx.Deadline()
	if !ok {
		return nil, errors.New("probe deadline missing")
	}
	p.deadlines <- time.Until(deadline)
	return nil, errors.New("simulated unhealthy transport")
}

func TestLerNETPhysicalHealthUsesConfiguredTimeoutAndRecoveryThreshold(t *testing.T) {
	base := &acceptanceProvider{release: make(chan struct{})}
	close(base.release)
	deadlines := make(chan time.Duration, 10)
	calls := new(atomic.Int32)
	provider := &scheduledHealthProvider{base, deadlines, calls}
	gate := acceptanceGate(base)
	gate.actual = provider
	gate.limits.Mode = "warm"
	gate.probeURL = "https://health.example.invalid/"
	gate.healthSchedule = HealthOptions{11000, 13000, 1500, 3}
	var replacements atomic.Int32
	gate.factory = func() (adapter.Outbound, error) {
		replacements.Add(1)
		ready := &acceptanceProvider{release: make(chan struct{})}
		close(ready.release)
		return &scheduledHealthProvider{ready, deadlines, calls}, nil
	}
	defer gate.Close()
	if err := gate.wake(context.Background()); err != nil {
		t.Fatal(err)
	}
	idleBefore := gate.lastActivity
	for failure := 1; failure <= 3; failure++ {
		gate.mu.Lock()
		gate.nextHealth = time.Time{}
		gate.mu.Unlock()
		before := time.Now()
		gate.checkHealth()
		acceptanceAwait(t, "configured physical health completion", func() bool { gate.mu.Lock(); defer gate.mu.Unlock(); return !gate.healthChecking })
		gate.mu.Lock()
		interval := gate.nextHealth.Sub(before)
		idleAfter := gate.lastActivity
		gate.mu.Unlock()
		if interval < 11*time.Second || interval > 13*time.Second+100*time.Millisecond {
			t.Fatal("physical heartbeat ignored configured interval")
		}
		if failure < 3 && !idleAfter.Equal(idleBefore) {
			t.Fatal("health probe reset business idle timer")
		}
		if failure < 3 && replacements.Load() != 0 {
			t.Fatal("physical recovery used hardcoded lower threshold")
		}
	}
	if replacements.Load() != 1 || base.closes.Load() != 1 || calls.Load() != 4 {
		t.Fatalf("configured threshold/recovery proof mismatch: replacements=%d closes=%d calls=%d", replacements.Load(), base.closes.Load(), calls.Load())
	}
	close(deadlines)
	for remaining := range deadlines {
		if remaining <= 1100*time.Millisecond || remaining > 1500*time.Millisecond {
			t.Fatalf("active probe timeout was overridden by cold first-flow budget: %s", remaining)
		}
	}
}

func TestLerNETInvalidHealthApplyRetainsExistingPolicyAndSchedule(t *testing.T) {
	schedule := HealthOptions{11000, 13000, 1500, 3}
	gate := &exitGate{healthSchedule: schedule}
	current := &generation{revision: 1, registry: &gateRegistry{gates: map[string]*exitGate{"exit-a": gate}}, close: func() error { return nil }}
	mux := &switchRouter{}
	if err := mux.publish(context.Background(), current); err != nil {
		t.Fatal(err)
	}
	session := &Session{ctx: context.Background(), mux: mux, running: true, ack: Ack{InstanceID: "owner", InterfaceID: "tun", Revision: 1}}
	manifest := `{"direct_tag":"direct","health":{"probe_min_interval_ms":11000,"probe_max_interval_ms":1000,"active_probe_timeout_ms":1500,"failed_checks_before_recovery":3}}`
	if _, err := session.Apply(context.Background(), "owner", "tun", 1, 2, `{}`, manifest); ErrorCode(err) != "invalid_health_settings" {
		t.Fatalf("wrong invalid health failure: %v", err)
	}
	if mux.current != current || session.ack.Revision != 1 || gate.healthOptions() != schedule || current.retired {
		t.Fatal("failed health apply changed active policy/schedule")
	}
	mux.stop()
}

type blockingHealthProvider struct {
	*acceptanceProvider
	entered, finished chan struct{}
}

func (p *blockingHealthProvider) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	close(p.entered)
	<-ctx.Done()
	close(p.finished)
	return nil, ctx.Err()
}

func TestLerNETHealthDeadlineAndCancellationDrainActualProviderDial(t *testing.T) {
	for _, earlyCancel := range []bool{false, true} {
		t.Run(map[bool]string{false: "deadline", true: "cancel"}[earlyCancel], func(t *testing.T) {
			base := &acceptanceProvider{release: make(chan struct{})}
			close(base.release)
			provider := &blockingHealthProvider{base, make(chan struct{}), make(chan struct{})}
			gate := acceptanceGate(base)
			gate.actual, gate.limits.Mode = provider, "warm"
			defer gate.Close()
			if err := gate.wake(context.Background()); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan ProbeResult, 1)
			started := time.Now()
			go func() { done <- probeGate(ctx, gate, "https://health.example.invalid/", 1500) }()
			select {
			case <-provider.entered:
			case <-time.After(time.Second):
				t.Fatal("actual probe dial did not start")
			}
			if earlyCancel {
				cancel()
			}
			select {
			case result := <-done:
				if result.HTTPSLatencyMs != nil {
					t.Fatal("blocked provider reported healthy")
				}
			case <-time.After(2 * time.Second):
				t.Fatal("probe ignored configured deadline/cancellation")
			}
			if !earlyCancel && time.Since(started) < 1400*time.Millisecond {
				t.Fatal("business first-flow timeout silently shortened active probe")
			}
			select {
			case <-provider.finished:
			case <-time.After(time.Second):
				t.Fatal("HTTP request returned while actual provider dial remained unbounded")
			}
			acceptanceAwait(t, "probe admission released", func() bool { gate.mu.Lock(); defer gate.mu.Unlock(); return gate.probes == 0 })
		})
	}
}
