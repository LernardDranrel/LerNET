package expert

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

// A health request from a retired physical transport cannot penalize its
// replacement, even though the stable logical folder/tag/TUN did not change.
func TestAcceptanceNativeFolderDiscardsRetiredTransportProbe(t *testing.T) {
	previous := &acceptanceProvider{release: make(chan struct{}), blockDial: true}
	close(previous.release)
	replacement := &acceptanceProvider{release: make(chan struct{})}
	close(replacement.release)
	gate := acceptanceGate(previous)
	gate.limits.Mode = "warm"
	gate.factory = func() (adapter.Outbound, error) { return replacement, nil }
	defer gate.Close()
	if err := gate.wake(context.Background()); err != nil {
		t.Fatal(err)
	}
	folder := &folderGate{
		ctx:      context.Background(),
		registry: &gateRegistry{gates: map[string]*exitGate{"exit-a": gate}},
		selected: "exit-a", health: make(map[string]candidateHealth),
		options: FolderOptions{Tag: "folder", CandidateTags: []string{"exit-a"},
			ProbeURL: "https://example.invalid/", ActiveProbeTimeoutMs: 1000,
			FailedChecksBeforeRecovery: 2, ProbeMinIntervalMs: 3000, ProbeMaxIntervalMs: 7000},
	}
	folder.checkHealth()
	acceptanceAwait(t, "old provider probe entered", func() bool {
		gate.mu.Lock()
		defer gate.mu.Unlock()
		return gate.probes == 1
	})
	if err := gate.recover(context.Background()); err != nil {
		t.Fatal(err)
	}
	acceptanceAwait(t, "old probe reaction finished", func() bool {
		folder.mu.Lock()
		defer folder.mu.Unlock()
		return !folder.healthChecking
	})
	folder.mu.Lock()
	failures, sample := folder.failures, folder.health["exit-a"]
	folder.mu.Unlock()
	if failures != 0 || sample.latency != 0 || !sample.checked.IsZero() || !sample.failedUntil.IsZero() {
		t.Fatalf("retired transport probe changed replacement health: failures=%d sample=%+v", failures, sample)
	}
	if gate.status().Failures != 0 {
		t.Fatal("retired result changed gate health")
	}
}

func TestAcceptanceNativeFolderCannotPublishSampleAfterTransportReplaced(t *testing.T) {
	entered, response := make(chan struct{}), make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-response
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	acceptanceTrustLocalServer(t, server)
	previous := &acceptanceProvider{release: make(chan struct{})}
	close(previous.release)
	replacement := &acceptanceProvider{release: make(chan struct{})}
	close(replacement.release)
	gate := acceptanceGate(previous)
	gate.limits.Mode = "warm"
	gate.actual = &acceptanceHTTPSProvider{previous}
	gate.factory = func() (adapter.Outbound, error) { return replacement, nil }
	defer gate.Close()
	if err := gate.wake(context.Background()); err != nil {
		t.Fatal(err)
	}
	folder := &folderGate{
		ctx:      context.Background(),
		registry: &gateRegistry{gates: map[string]*exitGate{"exit-a": gate}},
		selected: "exit-a", health: make(map[string]candidateHealth),
		options: FolderOptions{Tag: "folder", CandidateTags: []string{"exit-a"},
			ProbeURL: server.URL, ActiveProbeTimeoutMs: 1000,
			FailedChecksBeforeRecovery: 2, ProbeMinIntervalMs: 3000, ProbeMaxIntervalMs: 7000},
	}
	folder.checkHealth()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("local HTTPS probe did not enter")
	}
	// Hold the publication boundary, allow the old transport to return an
	// actual successful response, then replace it before folder publication.
	folder.mu.Lock()
	close(response)
	acceptanceAwait(t, "old HTTPS proof completed", func() bool { return gate.status().Health == "healthy" })
	if err := gate.recover(context.Background()); err != nil {
		folder.mu.Unlock()
		t.Fatal(err)
	}
	folder.mu.Unlock()
	acceptanceAwait(t, "folder reaction completed", func() bool {
		folder.mu.Lock()
		defer folder.mu.Unlock()
		return !folder.healthChecking
	})
	folder.mu.Lock()
	sample := folder.health["exit-a"]
	folder.mu.Unlock()
	if sample.latency != 0 || !sample.checked.IsZero() {
		t.Fatalf("old healthy transport was published for an unproved replacement: %+v", sample)
	}
}
