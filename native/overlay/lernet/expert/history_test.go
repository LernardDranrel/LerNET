package expert

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type handshakeHistoryConn struct {
	net.Conn
	success, failure int
}

func (c *handshakeHistoryConn) HandshakeSuccess() error      { c.success++; return nil }
func (c *handshakeHistoryConn) HandshakeFailure(error) error { c.failure++; return nil }

func TestHistoryWrapperPreservesRealHandshakeWithoutEarlySuccess(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	transport := &handshakeHistoryConn{Conn: a}
	h := newFlowHistory(1)
	c := &historyConn{Conn: transport, history: h, flow: h.add(0, adapter.InboundContext{}, nil)}
	if transport.success != 0 {
		t.Fatal("early handshake success")
	}
	if err := N.ReportConnHandshakeSuccess(c, nil); err != nil || transport.success != 1 {
		t.Fatal("transport success lost")
	}
	N.CloseOnHandshakeFailure(c, nil, errors.New("system_route_ipv6_unavailable"))
	if transport.failure != 1 {
		t.Fatal("transport failure lost")
	}
	if N.UnwrapReader(c) != c || N.UnwrapWriter(c) != c {
		t.Fatal("byte accounting can be bypassed")
	}
}

func TestFlowHistoryPreservesEndpointsWhenDomainWasSniffed(t *testing.T) {
	h := newFlowHistory(2)
	h.clock = func() time.Time { return time.UnixMilli(1000) }
	f := h.add(7, adapter.InboundContext{
		Source:      M.ParseSocksaddr("[2001:db8::1]:12345"),
		Destination: M.ParseSocksaddr("192.0.2.1:443"),
		Domain:      "example.invalid", Network: "tcp", Protocol: "tls",
	}, nil)
	if f.SourceIP != "2001:db8::1" || f.SourcePort != 12345 || f.DestinationIP != "192.0.2.1" || f.DestinationPort != 443 ||
		f.Domain != "example.invalid" || f.Protocol != "tls" || f.Network != "tcp" || f.StartedMs != 1000 {
		t.Fatalf("endpoint facts lost: %+v", f)
	}
}

func TestFlowHistoryKeepsQuietActiveFlowAheadOfCompletedRecords(t *testing.T) {
	h := newFlowHistory(2)
	active := h.add(0, adapter.InboundContext{}, nil)
	completed := h.add(0, adapter.InboundContext{}, nil)
	h.close(completed)
	newFlow := h.add(0, adapter.InboundContext{}, nil)
	rows, dropped := h.snapshotStatus()
	if len(rows) != 2 || rows[0].ID != active.ID || rows[1].ID != newFlow.ID || dropped != 1 {
		t.Fatalf("active flow evicted: %+v; dropped=%d", rows, dropped)
	}
}

func TestFlowHistoryClosedDecisionCannotEvictAllActiveHistory(t *testing.T) {
	h := newFlowHistory(1)
	active := h.add(0, adapter.InboundContext{}, nil)
	tracker := &historyTracker{history: h}
	tracker.LerNETDecision(context.Background(), adapter.InboundContext{}, "blocked", "policy_reject")
	rows, dropped := h.snapshotStatus()
	if len(rows) != 1 || rows[0].ID != active.ID || dropped != 1 {
		t.Fatalf("closed decision displaced live flow: %+v; dropped=%d", rows, dropped)
	}
}

func TestFlowHistoryCloseTimestampAndCounterDirectionsAreFactual(t *testing.T) {
	h := newFlowHistory(1)
	now := int64(1000)
	h.clock = func() time.Time { return time.UnixMilli(now) }
	f := h.add(0, adapter.InboundContext{}, nil)
	now = 2000
	h.count(f, 20, true)
	h.count(f, 30, false)
	h.count(f, -1, true)
	if f.UploadBytes != 20 || f.DownloadBytes != 30 || f.UpdatedMs != 2000 || f.ClosedMs != 0 {
		t.Fatalf("incorrect counters or timestamp: %+v", f)
	}
	now = 3000
	h.close(f)
	now = 4000
	h.close(f)
	if f.ClosedMs != 3000 || f.UpdatedMs != 3000 || !f.Closed {
		t.Fatalf("close time changed: %+v", f)
	}
}

func TestFlowErrorCodesSeparateDNSDialTransferAndCleanClose(t *testing.T) {
	for _, sample := range []struct {
		err           error
		stage, reason string
	}{
		{fmt.Errorf("private-value: %w", &net.DNSError{Name: "private.invalid", IsNotFound: true}), "dns", "name_not_found"},
		{&net.OpError{Op: "dial", Err: context.DeadlineExceeded}, "dial", "timeout"},
		{&net.OpError{Op: "write", Err: errors.New("private-value")}, "transfer", "network_error"},
		{&net.OpError{Op: "dial", Err: errors.New("system_route_unavailable")}, "route", "system_route_unavailable"},
		{fmt.Errorf("private host: %w", errors.Join(context.Canceled, &net.OpError{Op: "dial", Err: errors.New("system_route_ipv6_unavailable")})), "route", "system_route_ipv6_unavailable"},
		{E.Errors(context.Canceled, fmt.Errorf("private host: %w", errors.New("system_route_ipv6_unavailable"))), "route", "system_route_ipv6_unavailable"},
		{errors.Join(io.EOF, errors.New("private failure")), "connection", "network_error"},
		{errors.Join(io.EOF, context.Canceled), "", ""},
		{io.EOF, "", ""}, {net.ErrClosed, "", ""}, {context.Canceled, "", ""},
	} {
		stage, reason := flowError(sample.err)
		if stage != sample.stage || reason != sample.reason {
			t.Fatalf("wrong error classification: %q/%q", stage, reason)
		}
	}
	h := newFlowHistory(1)
	f := h.add(0, adapter.InboundContext{}, nil)
	h.failed(f, &net.OpError{Op: "dial", Err: errors.New("password=private-value")})
	if strings.Contains(Encode(h.snapshot()), "private-value") {
		t.Fatal("raw error leaked into observation")
	}
}

func TestInspectionBoundedCopiesAndCounts(t *testing.T) {
	h := newFlowHistory(2)
	h.clock = func() time.Time { return time.UnixMilli(1234) }
	f := h.add(1, adapter.InboundContext{Network: "tcp"}, nil)
	payload := []byte(strings.Repeat("x", 2048))
	for i := 0; i < 20; i++ {
		h.observe(f, len(payload), i%2 == 0, payload, true)
	}
	payload[0] = 'z'
	rows := h.snapshot()
	view := rows[0].Inspection
	if len(view.UploadPrefix) != 512 || len(view.DownloadPrefix) != 512 || view.UploadPrefix[0] != 'x' ||
		len(view.Transfers) != 8 || view.Transfers[0].Sequence != 13 || view.TransferCount != 20 ||
		f.UploadBytes != 20480 || f.DownloadBytes != 20480 || !view.PayloadAvailable {
		t.Fatalf("invalid inspection: %+v", view)
	}
	view.UploadPrefix[0] = 'q'
	view.Transfers[0].Bytes = 1
	if f.Inspection.UploadPrefix[0] != 'x' || f.Inspection.Transfers[0].Bytes != 2048 {
		t.Fatal("snapshot alias")
	}
	h.observe(f, 0, true, nil, true)
	if f.Inspection.TransferCount != 20 {
		t.Fatal("zero transfer recorded")
	}
}
func TestInspectionPartialWritesAndKernelCounters(t *testing.T) {
	h := newFlowHistory(2)
	f := h.add(1, adapter.InboundContext{}, nil)
	h.observe(f, 3, true, []byte("abcdef"), true)
	h.count(f, 17, false)
	if string(f.Inspection.UploadPrefix) != "abc" || len(f.Inspection.DownloadPrefix) != 0 || f.DownloadBytes != 17 {
		t.Fatal("partial transfer or unavailable payload misrepresented")
	}
}
