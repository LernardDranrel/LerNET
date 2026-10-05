package expert

import (
	"context"
	"crypto/x509"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

// This test double only supplies a blocking provider and local handles. Admission,
// deduplicated wake, lifecycle counters, deadlines, and cleanup are production Go.
type acceptanceProvider struct {
	starts    atomic.Int32
	closes    atomic.Int32
	release   chan struct{}
	blockDial bool
}

func (p *acceptanceProvider) Type() string           { return "acceptance" }
func (p *acceptanceProvider) Tag() string            { return "exit-a" }
func (p *acceptanceProvider) Network() []string      { return []string{"tcp", "udp"} }
func (p *acceptanceProvider) Dependencies() []string { return nil }
func (p *acceptanceProvider) Start(stage adapter.StartStage) error {
	if stage == adapter.StartStateInitialize {
		p.starts.Add(1)
		<-p.release
	}
	return nil
}
func (p *acceptanceProvider) Close() error { p.closes.Add(1); return nil }
func (p *acceptanceProvider) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if p.blockDial {
		<-ctx.Done()
		return nil, ctx.Err()
	}
	a, b := net.Pipe()
	_ = b.Close()
	return a, nil
}
func (p *acceptanceProvider) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if p.blockDial {
		<-ctx.Done()
		return nil, ctx.Err()
	}
	return &acceptancePacket{}, nil
}

type acceptancePacket struct{}

func (*acceptancePacket) ReadFrom([]byte) (int, net.Addr, error)    { return 0, nil, net.ErrClosed }
func (*acceptancePacket) WriteTo(b []byte, a net.Addr) (int, error) { return len(b), nil }
func (*acceptancePacket) Close() error                              { return nil }
func (*acceptancePacket) LocalAddr() net.Addr                       { return &net.UDPAddr{} }
func (*acceptancePacket) SetDeadline(time.Time) error               { return nil }
func (*acceptancePacket) SetReadDeadline(time.Time) error           { return nil }
func (*acceptancePacket) SetWriteDeadline(time.Time) error          { return nil }

func acceptanceGate(p *acceptanceProvider) *exitGate {
	return &exitGate{ctx: context.Background(), tag: "exit-a", outboundType: "acceptance", limits: ExitOptions{
		Tag: "exit-a", Mode: "cold", IdleTimeoutMs: 1000, FirstFlowTimeoutMs: 1000, StartupTimeoutMs: 1000, MaxPendingFlows: 100,
	}, actual: p, phase: "sleeping", factory: func() (adapter.Outbound, error) { return p, nil }, lastActivity: time.Now()}
}
func acceptanceAwait(t *testing.T, what string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatal("timed out: " + what)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestAcceptanceNativeBurstOneWakeTCPAndUDP(t *testing.T) {
	p := &acceptanceProvider{release: make(chan struct{})}
	g := acceptanceGate(p)
	defer g.Close()
	result := make(chan func(), 100)
	errs := make(chan error, 100)
	var workers sync.WaitGroup
	for i := 0; i < 100; i++ {
		workers.Add(1)
		go func(udp bool) {
			defer workers.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if udp {
				c, e := g.ListenPacket(ctx, M.ParseSocksaddr("192.0.2.2:443"))
				if e != nil {
					errs <- e
					return
				}
				result <- func() { _ = c.Close() }
			} else {
				c, e := g.DialContext(ctx, "tcp", M.ParseSocksaddr("192.0.2.2:443"))
				if e != nil {
					errs <- e
					return
				}
				result <- func() { _ = c.Close() }
			}
		}(i%2 == 0)
	}
	acceptanceAwait(t, "all 100 flows queued", func() bool { return g.status().PendingFlows == 100 })
	if p.starts.Load() != 1 {
		t.Fatalf("starts=%d, want exactly 1", p.starts.Load())
	}
	close(p.release)
	workers.Wait()
	close(errs)
	for e := range errs {
		t.Errorf("flow failed: %v", e)
	}
	if len(result) != 100 {
		t.Fatalf("admitted=%d, want 100", len(result))
	}
	if g.status().PendingFlows != 0 || g.status().ActiveFlows != 100 {
		t.Fatalf("wrong admission counters: %+v", g.status())
	}
	close(result)
	for closeHandle := range result {
		closeHandle()
	}
	if g.status().ActiveFlows != 0 {
		t.Fatalf("active counters leaked: %+v", g.status())
	}
}

func TestAcceptanceNativeCanceledFlowReleasesPending(t *testing.T) {
	p := &acceptanceProvider{release: make(chan struct{})}
	g := acceptanceGate(p)
	defer g.Close()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { _, _, e := g.acquire(ctx, false); done <- e }()
	acceptanceAwait(t, "pending before cancellation", func() bool { return g.status().PendingFlows == 1 })
	cancel()
	select {
	case e := <-done:
		if !errors.Is(e, context.Canceled) {
			t.Fatalf("got %v, want canceled", e)
		}
	case <-time.After(250 * time.Millisecond):
		t.Fatal("canceled flow stayed queued")
	}
	if g.status().PendingFlows != 0 {
		t.Fatalf("pending counter leaked: %+v", g.status())
	}
	close(p.release)
}

func TestAcceptanceNativeFirstFlowDeadlineBoundsDial(t *testing.T) {
	p := &acceptanceProvider{release: make(chan struct{}), blockDial: true}
	close(p.release)
	g := acceptanceGate(p)
	defer g.Close()
	done := make(chan error, 1)
	start := time.Now()
	go func() {
		_, e := g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("192.0.2.2:443"))
		done <- e
	}()
	select {
	case e := <-done:
		if !errors.Is(e, context.DeadlineExceeded) {
			t.Fatalf("got %v, want deadline", e)
		}
		if time.Since(start) > 1500*time.Millisecond {
			t.Fatal("first-flow deadline exceeded")
		}
	case <-time.After(1600 * time.Millisecond):
		t.Fatal("native dial escaped first-flow deadline after acquire returned")
	}
}

func TestAcceptanceNativeProbeCannotWakeOrResetIdle(t *testing.T) {
	p := &acceptanceProvider{release: make(chan struct{})}
	g := acceptanceGate(p)
	defer g.Close()
	initial := g.lastActivity
	_, _, e := g.acquire(context.Background(), true)
	if !errors.Is(e, ErrSleeping) {
		t.Fatalf("sleeping probe error=%v", e)
	}
	if p.starts.Load() != 0 || g.status().PendingFlows != 0 || !g.lastActivity.Equal(initial) {
		t.Fatal("probe woke sleeping provider or touched business idle")
	}
}

func TestAcceptanceNativeFirstFlowDeadlineBoundsUDP(t *testing.T) {
	p := &acceptanceProvider{release: make(chan struct{}), blockDial: true}
	close(p.release)
	g := acceptanceGate(p)
	defer g.Close()
	done := make(chan error, 1)
	go func() { _, e := g.ListenPacket(context.Background(), M.ParseSocksaddr("192.0.2.2:443")); done <- e }()
	select {
	case e := <-done:
		if !errors.Is(e, context.DeadlineExceeded) {
			t.Fatalf("got %v, want deadline", e)
		}
	case <-time.After(1600 * time.Millisecond):
		t.Fatal("native UDP setup escaped first-flow deadline")
	}
}

func TestAcceptanceNativeClosedExitCannotReturnToFailedOrReady(t *testing.T) {
	p := &acceptanceProvider{release: make(chan struct{})}
	g := acceptanceGate(p)
	done := make(chan error, 1)
	go func() { _, _, e := g.acquire(context.Background(), false); done <- e }()
	acceptanceAwait(t, "startup entered", func() bool { return p.starts.Load() == 1 })
	_ = g.Close()
	close(p.release)
	select {
	case <-done:
	case <-time.After(250 * time.Millisecond):
		t.Fatal("closed startup did not release waiter")
	}
	if g.status().Phase != "closed" {
		t.Fatalf("late startup changed closed exit to %q", g.status().Phase)
	}
	acceptanceAwait(t, "late provider closed", func() bool { return p.closes.Load() == 1 })
}

type acceptanceHTTPSProvider struct{ *acceptanceProvider }

var acceptanceRootsOnce sync.Once

func acceptanceTrustLocalServer(t *testing.T, server *httptest.Server) {
	t.Helper()
	t.Setenv("GODEBUG", "x509usefallbackroots=1")
	acceptanceRootsOnce.Do(func() { roots := x509.NewCertPool(); roots.AddCert(server.Certificate()); x509.SetFallbackRoots(roots) })
}

func (p *acceptanceHTTPSProvider) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, destination.String())
}
func TestAcceptanceNativeFastestFolderUsesHealthyCandidateBeforeFlowExpires(t *testing.T) {
	// Trust is confined to this Go test process, never installed in Windows.
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	defer server.Close()
	acceptanceTrustLocalServer(t, server)
	healthyProvider := &acceptanceProvider{release: make(chan struct{})}
	close(healthyProvider.release)
	healthy := acceptanceGate(healthyProvider)
	healthy.tag = "healthy"
	healthy.actual = &acceptanceHTTPSProvider{healthyProvider}
	defer healthy.Close()
	slowProvider := &acceptanceProvider{release: make(chan struct{}), blockDial: true}
	close(slowProvider.release)
	slow := acceptanceGate(slowProvider)
	slow.tag = "hung"
	defer slow.Close()
	registry := &gateRegistry{gates: map[string]*exitGate{"healthy": healthy, "hung": slow}}
	folder := &folderGate{ctx: context.Background(), registry: registry, health: make(map[string]candidateHealth), options: FolderOptions{
		Tag: "folder", CandidateTags: []string{"healthy", "hung"}, Selection: "fastest", AutoSwap: true,
		ProbeURL: server.URL, ProbeTimeoutMs: 1000, FirstFlowTimeoutMs: 1000, HealthTTLms: 10000, CooldownMs: 1000, MaxPendingFlows: 100,
	}}
	start := time.Now()
	conn, err := folder.DialContext(context.Background(), "tcp", M.ParseSocksaddr(server.Listener.Addr().String()))
	if err != nil {
		t.Fatalf("healthy exit unusable because neighboring probe hung (%s): %v", time.Since(start), err)
	}
	_ = conn.Close()
	if time.Since(start) >= 1000*time.Millisecond {
		t.Fatal("no connection budget remained after selection")
	}
}

func TestAcceptanceNativePublicationRetainsOldFlowsAndStopClosesAllGenerations(t *testing.T) {
	var closedA, closedB atomic.Int32
	old := &generation{revision: 1, close: func() error { closedA.Add(1); return nil }}
	next := &generation{revision: 2, close: func() error { closedB.Add(1); return nil }}
	router := &switchRouter{}
	if e := router.publish(context.Background(), old); e != nil {
		t.Fatal(e)
	}
	releases := make([]func(), 100)
	for i := range releases {
		g, release, e := router.acquire()
		if e != nil || g != old {
			t.Fatal("old flow generation unavailable")
		}
		releases[i] = release
	}
	if e := router.publish(context.Background(), next); e != nil {
		t.Fatal(e)
	}
	if closedA.Load() != 0 {
		t.Fatal("hot publication killed existing old flows")
	}
	g, release, e := router.acquire()
	if e != nil || g != next {
		t.Fatal("new flow did not see new generation")
	}
	release()
	router.stop()
	if closedA.Load() != 1 || closedB.Load() != 1 {
		t.Fatalf("explicit Stop missed retired generation: old=%d new=%d", closedA.Load(), closedB.Load())
	}
	for _, release := range releases {
		release()
		release()
	}
	if closedA.Load() != 1 || closedB.Load() != 1 {
		t.Fatal("late flow release double closed generation")
	}
}

func TestAcceptanceNativeCanceledPublicationLeavesCurrentGeneration(t *testing.T) {
	old := &generation{revision: 1, close: func() error { return nil }}
	next := &generation{revision: 2, close: func() error { return nil }}
	router := &switchRouter{}
	if e := router.publish(context.Background(), old); e != nil {
		t.Fatal(e)
	}
	defer router.stop()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if e := router.publish(ctx, next); !errors.Is(e, context.Canceled) {
		t.Fatalf("canceled publication error=%v", e)
	}
	g, release, e := router.acquire()
	if e != nil || g != old {
		t.Fatal("canceled publication replaced active policy")
	}
	release()
}

func TestAcceptanceNativeRecoveryRecreatesTransportAndReleasesTCPAndUDP(t *testing.T) {
	oldProvider := &acceptanceProvider{release: make(chan struct{})}
	close(oldProvider.release)
	replacement := &acceptanceProvider{release: make(chan struct{})}
	close(replacement.release)
	g := acceptanceGate(oldProvider)
	defer g.Close()
	g.factory = func() (adapter.Outbound, error) { return replacement, nil }
	tcp, e := g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("192.0.2.2:443"))
	if e != nil {
		t.Fatal(e)
	}
	udp, e := g.ListenPacket(context.Background(), M.ParseSocksaddr("192.0.2.2:443"))
	if e != nil {
		t.Fatal(e)
	}
	if g.status().ActiveFlows != 2 {
		t.Fatal("initial TCP/UDP handles not accounted")
	}
	if e = g.recover(context.Background()); e != nil {
		t.Fatal(e)
	}
	if oldProvider.closes.Load() != 1 || replacement.starts.Load() != 1 {
		t.Fatalf("recovery reused dead transport: old closes=%d new starts=%d", oldProvider.closes.Load(), replacement.starts.Load())
	}
	if g.status().ActiveFlows != 0 {
		t.Fatalf("recovery left dead TCP/UDP handles active: %+v", g.status())
	}
	_ = tcp.Close()
	_ = udp.Close()
	if g.status().ActiveFlows != 0 {
		t.Fatal("late application close double-released old handles")
	}
	next, e := g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("192.0.2.2:443"))
	if e != nil {
		t.Fatal(e)
	}
	_ = next.Close()
	if replacement.starts.Load() != 1 {
		t.Fatal("new business flow restarted the already recovered transport")
	}
}

type acceptanceLateDialProvider struct {
	*acceptanceProvider
	entered  chan struct{}
	complete chan struct{}
}

func (p *acceptanceLateDialProvider) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	close(p.entered)
	<-p.complete
	a, b := net.Pipe()
	_ = b.Close()
	return a, nil
}
func TestAcceptanceNativeRecoveryCannotPublishLateOldTransportDial(t *testing.T) {
	old := &acceptanceProvider{release: make(chan struct{})}
	close(old.release)
	late := &acceptanceLateDialProvider{acceptanceProvider: old, entered: make(chan struct{}), complete: make(chan struct{})}
	replacement := &acceptanceProvider{release: make(chan struct{})}
	close(replacement.release)
	g := acceptanceGate(old)
	g.actual = late
	g.factory = func() (adapter.Outbound, error) { return replacement, nil }
	defer g.Close()
	done := make(chan error, 1)
	go func() {
		c, e := g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("192.0.2.2:443"))
		if c != nil {
			_ = c.Close()
		}
		done <- e
	}()
	<-late.entered
	if e := g.recover(context.Background()); e != nil {
		t.Fatal(e)
	}
	close(late.complete)
	select {
	case e := <-done:
		if e == nil {
			t.Fatal("dial from the retired transport was published after successful recovery")
		}
	case <-time.After(time.Second):
		t.Fatal("old pending dial not released after recovery")
	}
}

type acceptanceContextConn struct {
	net.Conn
	ctx context.Context
}

func (c *acceptanceContextConn) Write(b []byte) (int, error) {
	if e := c.ctx.Err(); e != nil {
		return 0, e
	}
	return len(b), nil
}

type acceptanceContextPacket struct {
	acceptancePacket
	ctx context.Context
}

func (c *acceptanceContextPacket) WriteTo(b []byte, a net.Addr) (int, error) {
	if e := c.ctx.Err(); e != nil {
		return 0, e
	}
	return len(b), nil
}

type acceptanceStreamingProvider struct{ *acceptanceProvider }

func (p *acceptanceStreamingProvider) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	a, b := net.Pipe()
	_ = b.Close()
	return &acceptanceContextConn{Conn: a, ctx: ctx}, nil
}
func (p *acceptanceStreamingProvider) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return &acceptanceContextPacket{ctx: ctx}, nil
}
func TestAcceptanceNativeSetupDeadlineDoesNotCancelEstablishedStreamingFlows(t *testing.T) {
	p := &acceptanceProvider{release: make(chan struct{})}
	close(p.release)
	g := acceptanceGate(p)
	g.actual = &acceptanceStreamingProvider{p}
	defer g.Close()
	tcp, e := g.DialContext(context.Background(), "tcp", M.ParseSocksaddr("192.0.2.2:443"))
	if e != nil {
		t.Fatal(e)
	}
	defer tcp.Close()
	udp, e := g.ListenPacket(context.Background(), M.ParseSocksaddr("192.0.2.2:443"))
	if e != nil {
		t.Fatal(e)
	}
	defer udp.Close()
	// The provider deliberately uses its setup context for the live stream, as
	// streaming HTTP transports do. Neither setup return nor its elapsed budget
	// may terminate an established business connection.
	time.Sleep(1100 * time.Millisecond)
	if _, e = tcp.Write([]byte("still alive")); e != nil {
		t.Fatalf("TCP streaming context canceled after successful setup: %v", e)
	}
	if _, e = udp.WriteTo([]byte("still alive"), &net.UDPAddr{}); e != nil {
		t.Fatalf("UDP streaming context canceled after successful setup: %v", e)
	}
}
