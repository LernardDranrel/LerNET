package expert

import (
	"context"
	"crypto/x509"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
)

type probeCertificateStore struct {
	adapter.CertificateStore
	pool *x509.CertPool
}

func (s probeCertificateStore) Pool() *x509.CertPool { return s.pool }

func probeTLSGate(t *testing.T, pool *x509.CertPool) *exitGate {
	t.Helper()
	base := &acceptanceProvider{release: make(chan struct{})}
	close(base.release)
	gate := acceptanceGate(base)
	gate.ctx = service.ContextWith[adapter.CertificateStore](context.Background(), probeCertificateStore{pool: pool})
	gate.actual = &acceptanceHTTPSProvider{base}
	gate.limits.Mode = "warm"
	t.Cleanup(func() { _ = gate.Close() })
	if err := gate.wake(context.Background()); err != nil {
		t.Fatal(err)
	}
	return gate
}

func TestLerNETProbeUsesGenerationCertificateStore(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer server.Close()
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	gate := probeTLSGate(t, roots)
	// The manual caller deliberately has no certificate store. The store must
	// come from the gate's generation, just as Android's platform pool does.
	result := probeGate(context.Background(), gate, server.URL, 1500)
	if result.HTTPSLatencyMs == nil || result.Reason != "" {
		t.Fatalf("generation trust was ignored: %+v", result)
	}
	if gate.status().Health != "healthy" {
		t.Fatal("successful TLS evidence did not reach exit health")
	}
	// An explicit empty store must reject even a certificate present in the
	// process fallback roots used by other tests. Never bypass verification.
	untrusted := probeTLSGate(t, x509.NewCertPool())
	result = probeGate(context.Background(), untrusted, server.URL, 1500)
	if result.HTTPSLatencyMs != nil || result.Reason != "https_probe_certificate_failed" {
		t.Fatalf("untrusted TLS certificate not rejected distinctly: %+v", result)
	}
}

func TestLerNETProbeEndpointFailurePreservesPhysicalTransport(t *testing.T) {
	for _, trusted := range []bool{true, false} {
		t.Run(map[bool]string{true: "http_rejection", false: "certificate"}[trusted], func(t *testing.T) {
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(403) }))
			defer server.Close()
			roots := x509.NewCertPool()
			if trusted {
				roots.AddCert(server.Certificate())
			}
			gate := probeTLSGate(t, roots)
			gate.probeURL = server.URL
			gate.healthSchedule = HealthOptions{1000, 1000, 1500, 1}
			generation := gate.transportGeneration
			for attempt := 0; attempt < 2; attempt++ {
				gate.mu.Lock()
				gate.nextHealth = time.Time{}
				gate.mu.Unlock()
				gate.checkHealth()
				acceptanceAwait(t, "endpoint check", func() bool { gate.mu.Lock(); defer gate.mu.Unlock(); return !gate.healthChecking })
			}
			if !gate.matchesGeneration(generation) || gate.status().Health != "degraded" {
				t.Fatal("probe endpoint failure rebuilt the provider or was hidden")
			}
			folder := &folderGate{ctx: context.Background(), registry: &gateRegistry{gates: map[string]*exitGate{"exit-a": gate}},
				selected: "exit-a", health: make(map[string]candidateHealth), options: FolderOptions{
					CandidateTags: []string{"exit-a"}, ProbeURL: server.URL, ProbeTimeoutMs: 1500,
					ActiveProbeTimeoutMs: 1500, FailedChecksBeforeRecovery: 1,
				}}
			folder.checkHealth()
			acceptanceAwait(t, "folder endpoint check", func() bool { folder.mu.Lock(); defer folder.mu.Unlock(); return !folder.healthChecking })
			if !gate.matchesGeneration(generation) {
				t.Fatal("folder heartbeat rebuilt provider on endpoint failure")
			}
			if _, err := folder.selectCandidate(context.Background()); err == nil {
				t.Fatal("failed endpoint became a healthy candidate")
			}
			if !gate.matchesGeneration(generation) {
				t.Fatal("candidate selection rebuilt provider on endpoint failure")
			}
		})
	}
}

func TestLerNETProbeFailureCategoriesDoNotExposeProviderText(t *testing.T) {
	for _, test := range []struct {
		err  error
		want string
	}{
		{&net.DNSError{Err: "secret-provider-value", Name: "private.example"}, "https_probe_dns_failed"},
		{context.DeadlineExceeded, "https_probe_timeout"},
		{errors.New("secret-provider-value"), "https_probe_failed"},
	} {
		wrapped := &url.Error{Op: "Get", URL: "https://private.example/secret", Err: test.err}
		if got := probeFailureCode(wrapped); got != test.want {
			t.Fatalf("got %q, want %q", got, test.want)
		}
	}
}
