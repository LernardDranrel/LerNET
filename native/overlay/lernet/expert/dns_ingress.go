package expert

import (
	"context"
	"io"
	"sync"
)

// DNS exchanges use generation-specific resolver policy. A persistent DNS
// ingress therefore cannot drain on the old policy like an ordinary stream:
// each successful publication retires its connection, so the next query must
// create a new connection and acquire the current resolver policy.
type dnsIngressRegistry struct {
	mu       sync.Mutex
	revision int64
	active   bool
	closed   bool
	sessions map[*dnsIngressSession]struct{}
}

type dnsIngressSession struct {
	revision   int64
	conn       io.Closer
	cancel     context.CancelFunc
	closeOnce  sync.Once
	finishOnce sync.Once
}

type generationDNSIngress struct {
	registry *dnsIngressRegistry
	revision int64
}

func (g *generationDNSIngress) TrackDNSIngress(ctx context.Context, conn io.Closer) (context.Context, func()) {
	ctx, cancel := context.WithCancel(ctx)
	session := &dnsIngressSession{revision: g.revision, conn: conn, cancel: cancel}
	r := g.registry
	r.mu.Lock()
	allowed := r.active && !r.closed && r.revision == g.revision
	if allowed {
		if r.sessions == nil {
			r.sessions = make(map[*dnsIngressSession]struct{})
		}
		r.sessions[session] = struct{}{}
	} else {
		cancel()
	}
	r.mu.Unlock()
	if !allowed {
		session.close()
	}
	return ctx, func() {
		session.finishOnce.Do(func() {
			session.cancel()
			session.close()
			r.mu.Lock()
			delete(r.sessions, session)
			r.mu.Unlock()
		})
	}
}

func (s *dnsIngressSession) close() {
	s.closeOnce.Do(func() { _ = s.conn.Close() })
}

// Caller holds the router publication lock. Cancellation is part of commit;
// actual connection Close happens after releasing it, because the read-loop
// completion callback may release its generation through that same lock.
func (r *dnsIngressRegistry) publish(revision int64) []*dnsIngressSession {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.revision, r.active = revision, true
	var retired []*dnsIngressSession
	for session := range r.sessions {
		if session.revision != revision {
			session.cancel()
			retired = append(retired, session)
		}
	}
	return retired
}

func (r *dnsIngressRegistry) stop() []*dnsIngressSession {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.closed, r.active = true, false
	retired := make([]*dnsIngressSession, 0, len(r.sessions))
	for session := range r.sessions {
		session.cancel()
		retired = append(retired, session)
	}
	return retired
}

func (r *dnsIngressRegistry) pending() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.sessions) != 0
}

func closeDNSIngress(sessions []*dnsIngressSession) {
	for _, session := range sessions {
		session.close()
	}
}
