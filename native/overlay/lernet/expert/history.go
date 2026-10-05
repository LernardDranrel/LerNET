package expert

import (
	"context"
	"net"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type Flow struct {
	ID            int64    `json:"id"`
	Revision      int64    `json:"revision"`
	StartedMs     int64    `json:"started_ms"`
	Network       string   `json:"network"`
	Source        string   `json:"source"`
	Destination   string   `json:"destination"`
	Process       string   `json:"process,omitempty"`
	ProcessID     uint32   `json:"process_id,omitempty"`
	Packages      []string `json:"packages,omitempty"`
	Outbound      string   `json:"outbound,omitempty"`
	Rule          string   `json:"rule,omitempty"`
	NodeIDs       []string `json:"node_ids,omitempty"`
	State         string   `json:"state"`
	Reason        string   `json:"reason,omitempty"`
	Closed        bool     `json:"closed"`
	UploadBytes   int64    `json:"upload_bytes"`
	DownloadBytes int64    `json:"download_bytes"`
}

type liveFlow struct {
	metadata adapter.InboundContext
	close    func() error
}
type flowHistory struct {
	mu      sync.Mutex
	nextID  int64
	limit   int
	entries []*Flow
	live    map[*Flow]liveFlow
}

func newFlowHistory(limit int) *flowHistory {
	return &flowHistory{limit: limit, live: make(map[*Flow]liveFlow)}
}
func (h *flowHistory) add(revision int64, m adapter.InboundContext, out adapter.Outbound) *Flow {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.nextID++
	f := &Flow{ID: h.nextID, Revision: revision, StartedMs: time.Now().UnixMilli(), Network: m.Network, Source: m.Source.String(), Destination: m.Destination.String(), Rule: m.RouteRule, NodeIDs: append([]string(nil), m.LerNETNodeIDs...), State: "connecting"}
	if out != nil {
		f.Outbound = out.Tag()
	}
	if m.Domain != "" {
		f.Destination = m.Domain
	}
	if m.ProcessInfo != nil {
		f.Process = m.ProcessInfo.ProcessPath
		f.ProcessID = m.ProcessInfo.ProcessID
		f.Packages = append([]string(nil), m.ProcessInfo.AndroidPackageNames...)
	}
	if len(h.entries) == h.limit {
		copy(h.entries, h.entries[1:])
		h.entries = h.entries[:h.limit-1]
	}
	h.entries = append(h.entries, f)
	return f
}
func (h *flowHistory) count(f *Flow, n int, upload bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if n > 0 && !f.Closed && f.State == "connecting" {
		f.State = "active"
	}
	if upload {
		f.UploadBytes += int64(n)
	} else {
		f.DownloadBytes += int64(n)
	}
}
func (h *flowHistory) close(f *Flow) {
	h.mu.Lock()
	f.Closed = true
	delete(h.live, f)
	if f.State != "blocked" {
		f.State = "closed"
	}
	h.mu.Unlock()
}
func (h *flowHistory) decision(f *Flow, outbound, reason string) {
	h.mu.Lock()
	if !f.Closed && f.State != "blocked" {
		f.Outbound, f.Reason, f.State = outbound, reason, "active"
	}
	h.mu.Unlock()
}
func (h *flowHistory) snapshot() []Flow {
	h.mu.Lock()
	defer h.mu.Unlock()
	result := make([]Flow, len(h.entries))
	for i, f := range h.entries {
		result[i] = *f
		result[i].Packages = append([]string(nil), f.Packages...)
		result[i].NodeIDs = append([]string(nil), f.NodeIDs...)
	}
	return result
}

type historyTracker struct {
	history  *flowHistory
	revision int64
}

func (t *historyTracker) LerNETDecision(ctx context.Context, m adapter.InboundContext, state, reason string) {
	f := t.history.add(t.revision, m, nil)
	t.history.mu.Lock()
	f.State, f.Reason, f.Closed = state, reason, true
	t.history.mu.Unlock()
}
func (t *historyTracker) RoutedConnection(ctx context.Context, conn net.Conn, m adapter.InboundContext, rule adapter.Rule, out adapter.Outbound) net.Conn {
	c := &historyConn{Conn: conn, history: t.history, flow: t.history.add(t.revision, m, out)}
	t.history.mu.Lock()
	t.history.live[c.flow] = liveFlow{m, c.Close}
	t.history.mu.Unlock()
	return c
}
func (t *historyTracker) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, m adapter.InboundContext, rule adapter.Rule, out adapter.Outbound) N.PacketConn {
	c := &historyPacketConn{PacketConn: conn, history: t.history, flow: t.history.add(t.revision, m, out)}
	t.history.mu.Lock()
	t.history.live[c.flow] = liveFlow{m, c.Close}
	t.history.mu.Unlock()
	return c
}

func (h *flowHistory) enforce(router adapter.Router) {
	filter, ok := router.(interface {
		LerNETRejectExisting(adapter.InboundContext) bool
	})
	if !ok {
		return
	}
	h.mu.Lock()
	candidates := make(map[*Flow]liveFlow, len(h.live))
	for f, live := range h.live {
		candidates[f] = live
	}
	h.mu.Unlock()
	for f, live := range candidates {
		if !filter.LerNETRejectExisting(live.metadata) {
			continue
		}
		h.mu.Lock()
		f.State, f.Reason = "blocked", "policy_updated_block"
		h.mu.Unlock()
		_ = live.close()
	}
}
func (t *historyTracker) RoutedFlow(ctx context.Context, m adapter.InboundContext, rule adapter.Rule, out adapter.Outbound) tun.FlowTracker {
	return &historyKernelFlow{history: t.history, flow: t.history.add(t.revision, m, out)}
}

type historyConn struct {
	net.Conn
	history *flowHistory
	flow    *Flow
	once    sync.Once
}

func (c *historyConn) LerNETDecisionObserver() func(string, string) {
	return func(outbound, reason string) { c.history.decision(c.flow, outbound, reason) }
}
func (c *historyConn) Read(p []byte) (int, error) {
	n, e := c.Conn.Read(p)
	c.history.count(c.flow, n, true)
	return n, e
}
func (c *historyConn) Write(p []byte) (int, error) {
	n, e := c.Conn.Write(p)
	c.history.count(c.flow, n, false)
	return n, e
}
func (c *historyConn) Close() error {
	e := c.Conn.Close()
	c.once.Do(func() { c.history.close(c.flow) })
	return e
}

type historyPacketConn struct {
	N.PacketConn
	history *flowHistory
	flow    *Flow
	once    sync.Once
}

func (c *historyPacketConn) LerNETDecisionObserver() func(string, string) {
	return func(outbound, reason string) { c.history.decision(c.flow, outbound, reason) }
}
func (c *historyPacketConn) ReadPacket(b *buf.Buffer) (M.Socksaddr, error) {
	a, e := c.PacketConn.ReadPacket(b)
	if e == nil {
		c.history.count(c.flow, b.Len(), true)
	}
	return a, e
}
func (c *historyPacketConn) WritePacket(b *buf.Buffer, a M.Socksaddr) error {
	n := b.Len()
	e := c.PacketConn.WritePacket(b, a)
	if e == nil {
		c.history.count(c.flow, n, false)
	}
	return e
}
func (c *historyPacketConn) Close() error {
	e := c.PacketConn.Close()
	c.once.Do(func() { c.history.close(c.flow) })
	return e
}

type historyKernelFlow struct {
	history *flowHistory
	flow    *Flow
}

func (f *historyKernelFlow) AttachFlow(tun.FlowHandle)     {}
func (f *historyKernelFlow) CountForward(n int)            { f.history.count(f.flow, n, true) }
func (f *historyKernelFlow) CountReverse(n int)            { f.history.count(f.flow, n, false) }
func (f *historyKernelFlow) FlowEstablished()              {}
func (f *historyKernelFlow) CloseFlow(tun.FlowCloseReason) { f.history.close(f.flow) }
