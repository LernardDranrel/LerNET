package local

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

func TestOriginalDNSServerKeepsOSSelectionAndRejectsIngress(t *testing.T) {
	for _, sample := range []struct {
		address string
		port    uint16
		valid   bool
	}{
		{"10.20.30.250", 53, true}, {"10.20.30.249", 53, true},
		{"127.0.0.1", 53, true}, {"2001:db8::53", 53, true},
		{"fe80::53%14", 53, true}, {"fdfe:dcba:9876::2%14", 53, false},
		{"172.19.0.2", 53, false}, {"fdfe:dcba:9876::2", 53, false},
		{"::ffff:172.19.0.2", 53, false}, {"0.0.0.0", 53, false},
		{"224.0.0.1", 53, false}, {"10.20.30.250", 443, false},
	} {
		destination := M.ParseSocksaddrHostPort(sample.address, sample.port)
		ctx := adapter.WithContext(context.Background(), &adapter.InboundContext{LerNETDNSDestination: destination})
		actual, valid := originalDNSServer(ctx)
		if valid != sample.valid || valid && actual != destination {
			t.Fatalf("%s:%d valid=%v", sample.address, sample.port, valid)
		}
	}
	if _, valid := originalDNSServer(context.Background()); valid {
		t.Fatal("missing metadata became an upstream")
	}
}

type failedDNSDialer struct {
	network     string
	destination M.Socksaddr
	failure     error
}

func (d *failedDNSDialer) DialContext(_ context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	d.network, d.destination = network, destination
	return nil, d.failure
}
func (d *failedDNSDialer) ListenPacket(_ context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	return nil, d.failure
}

func TestFailedOriginalDNSNeverFallsBackAndKeepsTCP(t *testing.T) {
	server := M.ParseSocksaddrHostPort("10.20.30.250", 53)
	for _, network := range []string{"tcp", "udp"} {
		failure := errors.New("fixture DNS unavailable")
		external := &failedDNSDialer{failure: failure}
		preserving := &lernetPreservingTransport{
			Transport: &Transport{TransportAdapter: dns.NewTransportAdapterWithLocalOptions("local", "test", option.LocalDNSServerOptions{})},
			logger:    log.NewNOPFactory().NewLogger("test"), external: external,
		}
		timeout, cancel := context.WithTimeout(context.Background(), time.Second)
		ctx := adapter.WithContext(timeout, &adapter.InboundContext{Network: network, LerNETDNSDestination: server})
		query := new(mDNS.Msg)
		query.SetQuestion("example.invalid.", mDNS.TypeA)
		response, err := preserving.Exchange(ctx, query)
		cancel()
		if response != nil || !errors.Is(err, failure) || external.destination != server || external.network != network {
			t.Fatalf("%s: response=%v err=%v dial=%s %s", network, response, err, external.network, external.destination)
		}
	}
}
