package expert

import (
	"context"
	"errors"
	"io"
	"net"
	"path/filepath"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const previewBytesPerDirection = 512
const transferHistoryLimit = 8

// TCP transfers are reads/writes of a stream, not IP packets or application messages.
type FlowTransfer struct {
	Sequence int64 `json:"sequence"`
	AtMs     int64 `json:"at_ms"`
	Upload   bool  `json:"upload"`
	Bytes    int   `json:"bytes"`
}
type FlowInspection struct {
	UploadPrefix     []byte         `json:"upload_prefix,omitempty"`
	DownloadPrefix   []byte         `json:"download_prefix,omitempty"`
	Transfers        []FlowTransfer `json:"transfers"`
	TransferCount    int64          `json:"transfer_count"`
	PayloadAvailable bool           `json:"payload_available"`
}

type Flow struct {
	Inspection      *FlowInspection `json:"inspection,omitempty"`
	ID              int64           `json:"id"`
	Revision        int64           `json:"revision"`
	StartedMs       int64           `json:"started_ms"`
	Network         string          `json:"network"`
	Source          string          `json:"source"`
	Destination     string          `json:"destination"`
	Process         string          `json:"process,omitempty"`
	ProcessID       uint32          `json:"process_id,omitempty"`
	ProcessName     string          `json:"process_name,omitempty"`
	Packages        []string        `json:"packages,omitempty"`
	Outbound        string          `json:"outbound,omitempty"`
	Rule            string          `json:"rule,omitempty"`
	NodeIDs         []string        `json:"node_ids,omitempty"`
	State           string          `json:"state"`
	Reason          string          `json:"reason,omitempty"`
	Closed          bool            `json:"closed"`
	UploadBytes     int64           `json:"upload_bytes"`
	DownloadBytes   int64           `json:"download_bytes"`
	SourceIP        string          `json:"source_ip,omitempty"`
	SourcePort      uint16          `json:"source_port,omitempty"`
	DestinationIP   string          `json:"destination_ip,omitempty"`
	DestinationPort uint16          `json:"destination_port,omitempty"`
	Domain          string          `json:"domain,omitempty"`
	Protocol        string          `json:"protocol,omitempty"`
	GeoCountry      string          `json:"geo_country,omitempty"`
	UpdatedMs       int64           `json:"updated_ms"`
	ClosedMs        int64           `json:"closed_ms,omitempty"`
	ErrorStage      string          `json:"error_stage,omitempty"`
	ErrorReason     string          `json:"error_reason,omitempty"`
	CloseReason     string          `json:"close_reason,omitempty"`
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
	dropped int64
	clock   func() time.Time
}

func newFlowHistory(limit int) *flowHistory {
	if limit < 1 {
		panic("invalid flow history limit")
	}
	return &flowHistory{limit: limit, live: make(map[*Flow]liveFlow), clock: time.Now}
}
func (h *flowHistory) add(revision int64, m adapter.InboundContext, out adapter.Outbound) *Flow {
	return h.addRecord(revision, m, out, "connecting", "", false)
}
func (h *flowHistory) addRecord(revision int64, m adapter.InboundContext, out adapter.Outbound, state, reason string, closed bool) *Flow {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.nextID++
	f := &Flow{ID: h.nextID, Revision: revision, StartedMs: h.clock().UnixMilli(), Network: m.Network, Source: m.Source.String(), Destination: m.Destination.String(), Rule: m.RouteRule, NodeIDs: append([]string(nil), m.LerNETNodeIDs...), State: "connecting"}
	f.UpdatedMs = f.StartedMs
	f.State, f.Reason, f.Closed = state, reason, closed
	if closed {
		f.ClosedMs = f.StartedMs
	}
	f.SourcePort, f.DestinationPort = m.Source.Port, m.Destination.Port
	if m.Source.Addr.IsValid() {
		f.SourceIP = m.Source.Addr.String()
	}
	if m.Destination.Addr.IsValid() {
		f.DestinationIP = m.Destination.Addr.String()
	}
	f.Domain, f.Protocol, f.GeoCountry = m.Domain, m.Protocol, m.GeoIPCode
	if f.Domain == "" {
		f.Domain = m.Destination.Fqdn
	}
	if out != nil {
		f.Outbound = out.Tag()
	}
	if m.Domain != "" {
		f.Destination = m.Domain
	}
	if m.ProcessInfo != nil {
		f.Process = m.ProcessInfo.ProcessPath
		if f.Process != "" {
			f.ProcessName = filepath.Base(f.Process)
		}
		f.ProcessID = m.ProcessInfo.ProcessID
		f.Packages = append([]string(nil), m.ProcessInfo.AndroidPackageNames...)
	}
	if len(h.entries) == h.limit {
		// Completed records yield to live flows, even if an older live flow is quiet.
		index := -1
		for i, entry := range h.entries {
			if entry.Closed {
				index = i
				break
			}
		}
		h.dropped++
		if index < 0 {
			if f.Closed {
				return f
			}
			index = 0
		}
		copy(h.entries[index:], h.entries[index+1:])
		h.entries = h.entries[:h.limit-1]
	}
	h.entries = append(h.entries, f)
	return f
}
func (h *flowHistory) count(f *Flow, n int, upload bool) {
	h.observe(f, n, upload, nil, false)
}
func (h *flowHistory) observe(f *Flow, n int, upload bool, payload []byte, available bool) {
	if n <= 0 {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	f.UpdatedMs = h.clock().UnixMilli()
	if !f.Closed && f.State == "connecting" {
		f.State = "active"
	}
	if upload {
		f.UploadBytes += int64(n)
	} else {
		f.DownloadBytes += int64(n)
	}
	if f.Inspection == nil {
		f.Inspection = &FlowInspection{}
	}
	view := f.Inspection
	view.PayloadAvailable = view.PayloadAvailable || available
	view.TransferCount++
	if len(view.Transfers) == transferHistoryLimit {
		copy(view.Transfers, view.Transfers[1:])
		view.Transfers = view.Transfers[:transferHistoryLimit-1]
	}
	view.Transfers = append(view.Transfers, FlowTransfer{view.TransferCount, f.UpdatedMs, upload, n})
	if !available {
		return
	}
	prefix := &view.DownloadPrefix
	if upload {
		prefix = &view.UploadPrefix
	}
	remaining := previewBytesPerDirection - len(*prefix)
	if remaining <= 0 {
		return
	}
	size := min(n, len(payload), remaining)
	// Never retain a transport-owned buffer or alter the forwarded data.
	*prefix = append(*prefix, payload[:size]...)
}
func (h *flowHistory) close(f *Flow) {
	h.mu.Lock()
	if f.Closed {
		h.mu.Unlock()
		return
	}
	f.Closed = true
	f.ClosedMs = h.clock().UnixMilli()
	f.UpdatedMs = f.ClosedMs
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
		f.UpdatedMs = h.clock().UnixMilli()
	}
	h.mu.Unlock()
}
func (h *flowHistory) snapshot() []Flow {
	result, _ := h.snapshotStatus()
	return result
}
func (h *flowHistory) snapshotStatus() ([]Flow, int64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	result := make([]Flow, len(h.entries))
	for i, f := range h.entries {
		result[i] = *f
		result[i].Packages = append([]string(nil), f.Packages...)
		result[i].NodeIDs = append([]string(nil), f.NodeIDs...)
		if f.Inspection != nil {
			view := *f.Inspection
			view.UploadPrefix = append([]byte(nil), view.UploadPrefix...)
			view.DownloadPrefix = append([]byte(nil), view.DownloadPrefix...)
			view.Transfers = append([]FlowTransfer(nil), view.Transfers...)
			result[i].Inspection = &view
		}
	}
	return result, h.dropped
}

// Error codes contain no raw error text, profile data, URL or credentials.
func flowError(err error) (stage, reason string) {
	if err == nil {
		return "", ""
	}
	// Dial racing can join several failures. Inspect every cause, rather than
	// only Unwrap() error, and do not let a cancelled sibling hide a route fault.
	pending := []error{err}
	clean := true
	for budget := 64; len(pending) > 0 && budget > 0; budget-- {
		cause := pending[len(pending)-1]
		pending = pending[:len(pending)-1]
		if cause == nil {
			continue
		}
		switch cause.Error() {
		case "system_route_unavailable", "system_route_destination_invalid", "system_route_changed",
			"system_route_ipv4_unavailable", "system_route_ipv6_unavailable", "system_route_manager_unavailable",
			"system_route_snapshot_failed", "system_route_bind_failed", "ingress_not_ready", "ingress_ready_timeout",
			"interface_binding_invalid", "interface_binding_unavailable", "interface_binding_identity_changed", "interface_binding_owned_ingress":
			return "route", cause.Error()
		}
		switch wrapped := cause.(type) {
		case interface{ Unwrap() []error }:
			children := wrapped.Unwrap()
			if len(children) > 64 {
				clean = false
				children = children[:64]
			}
			pending = append(pending, children...)
		case interface{ Unwrap() error }:
			if child := wrapped.Unwrap(); child != nil {
				pending = append(pending, child)
			} else {
				clean = false
			}
		default:
			if cause != io.EOF && cause != net.ErrClosed && cause != context.Canceled {
				clean = false
			}
		}
	}
	if clean && len(pending) == 0 {
		return "", ""
	}
	var dns *net.DNSError
	if errors.As(err, &dns) {
		if dns.IsTimeout {
			return "dns", "timeout"
		}
		if dns.IsNotFound {
			return "dns", "name_not_found"
		}
		return "dns", "resolution_failed"
	}
	stage = "connection"
	var operation *net.OpError
	if errors.As(err, &operation) {
		switch operation.Op {
		case "dial":
			stage = "dial"
		case "read", "write":
			stage = "transfer"
		}
	}
	var timeout net.Error
	if errors.As(err, &timeout) && timeout.Timeout() || errors.Is(err, context.DeadlineExceeded) {
		return stage, "timeout"
	}
	return stage, "network_error"
}
func (h *flowHistory) failed(f *Flow, err error) {
	stage, reason := flowError(err)
	if reason == "" {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if f.ErrorReason == "" {
		f.ErrorStage, f.ErrorReason = stage, reason
		f.UpdatedMs = h.clock().UnixMilli()
	}
}

type historyTracker struct {
	history  *flowHistory
	revision int64
}

func (t *historyTracker) LerNETDecision(ctx context.Context, m adapter.InboundContext, state, reason string) {
	t.history.addRecord(t.revision, m, nil, state, reason, true)
}
func (t *historyTracker) LerNETFailure(ctx context.Context, m adapter.InboundContext, stage string, err error) {
	_, reason := flowError(err)
	if reason == "" {
		return
	}
	f := t.history.addRecord(t.revision, m, nil, "failed", "", true)
	t.history.mu.Lock()
	f.ErrorStage, f.ErrorReason = stage, reason
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

// Preserve transport handshake reporting without allowing copy helpers to
// bypass our byte accounting.
func (c *historyConn) Upstream() any           { return c.Conn }
func (c *historyConn) ReaderReplaceable() bool { return false }
func (c *historyConn) WriterReplaceable() bool { return false }

func (c *historyConn) LerNETDecisionObserver() func(string, string) {
	return func(outbound, reason string) { c.history.decision(c.flow, outbound, reason) }
}
func (c *historyConn) LerNETCloseObserver() func(error) {
	return func(err error) { c.history.failed(c.flow, err); c.history.close(c.flow) }
}
func (c *historyConn) Read(p []byte) (int, error) {
	n, e := c.Conn.Read(p)
	c.history.observe(c.flow, n, true, p, true)
	return n, e
}
func (c *historyConn) Write(p []byte) (int, error) {
	n, e := c.Conn.Write(p)
	c.history.observe(c.flow, n, false, p, true)
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

func (c *historyPacketConn) Upstream() any           { return c.PacketConn }
func (c *historyPacketConn) ReaderReplaceable() bool { return false }
func (c *historyPacketConn) WriterReplaceable() bool { return false }

func (c *historyPacketConn) LerNETDecisionObserver() func(string, string) {
	return func(outbound, reason string) { c.history.decision(c.flow, outbound, reason) }
}
func (c *historyPacketConn) LerNETCloseObserver() func(error) {
	return func(err error) { c.history.failed(c.flow, err); c.history.close(c.flow) }
}
func (c *historyPacketConn) ReadPacket(b *buf.Buffer) (M.Socksaddr, error) {
	a, e := c.PacketConn.ReadPacket(b)
	if e == nil {
		c.history.observe(c.flow, b.Len(), true, b.Bytes(), true)
	}
	return a, e
}
func (c *historyPacketConn) WritePacket(b *buf.Buffer, a M.Socksaddr) error {
	n := b.Len()
	// Packet writers may release b before returning. Preserve only the bounded prefix.
	c.history.mu.Lock()
	remaining := previewBytesPerDirection
	if c.flow.Inspection != nil {
		remaining -= len(c.flow.Inspection.DownloadPrefix)
	}
	c.history.mu.Unlock()
	prefix := append([]byte(nil), b.Bytes()[:min(n, remaining)]...)
	e := c.PacketConn.WritePacket(b, a)
	if e == nil {
		c.history.observe(c.flow, n, false, prefix, true)
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

func (f *historyKernelFlow) AttachFlow(tun.FlowHandle) {}
func (f *historyKernelFlow) CountForward(n int)        { f.history.count(f.flow, n, true) }
func (f *historyKernelFlow) CountReverse(n int)        { f.history.count(f.flow, n, false) }
func (f *historyKernelFlow) FlowEstablished() {
	f.history.mu.Lock()
	if !f.flow.Closed {
		f.flow.State = "active"
		f.flow.UpdatedMs = f.history.clock().UnixMilli()
	}
	f.history.mu.Unlock()
}
func (f *historyKernelFlow) CloseFlow(reason tun.FlowCloseReason) {
	f.history.mu.Lock()
	switch reason {
	case tun.FlowCloseFinished:
		f.flow.CloseReason = "finished"
	case tun.FlowCloseTimeout:
		f.flow.CloseReason = "idle_timeout"
	case tun.FlowCloseReset:
		f.flow.CloseReason = "reset"
	}
	f.history.mu.Unlock()
	f.history.close(f.flow)
}
