package local

import (
	"context"
	"net/netip"
	"sync"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// Windows DNS Client has already selected the upstream (including NRPT). Keep
// that choice rather than flattening all adapter DNS addresses into one pool.
// Dialer excludes the owned ingress TUN for every destination and network epoch.
type lernetPreservingTransport struct {
	*Transport
	logger      log.ContextLogger
	external    N.Dialer
	selectionMu sync.Mutex
	lastServer  M.Socksaddr
}

func lernetPreservingContext(ctx context.Context) (context.Context, error) {
	return dialer.LerNETSystemRouteContext(ctx)
}

func newLerNETPreservingTransport(ctx context.Context, logger log.ContextLogger, base *Transport) (adapter.DNSTransport, error) {
	external, err := dialer.New(ctx, option.DialerOptions{}, false)
	if err != nil {
		return nil, err
	}
	return &lernetPreservingTransport{Transport: base, logger: logger, external: external}, nil
}

func originalDNSServer(ctx context.Context) (M.Socksaddr, bool) {
	metadata := adapter.ContextFrom(ctx)
	if metadata == nil {
		return M.Socksaddr{}, false
	}
	destination := metadata.LerNETDNSDestination
	// A missing/synthetic destination is a service lookup, not an OS-selected server.
	if !destination.Addr.IsValid() || destination.Port != 53 ||
		destination.Addr.IsUnspecified() || destination.Addr.IsMulticast() ||
		netip.MustParsePrefix("172.19.0.0/30").Contains(destination.Addr.Unmap().WithZone("")) ||
		netip.MustParsePrefix("fdfe:dcba:9876::/126").Contains(destination.Addr.WithZone("")) {
		return M.Socksaddr{}, false
	}
	return destination, true
}

func (t *lernetPreservingTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	server, original := originalDNSServer(ctx)
	if !original {
		t.configSource.Reset()
		return t.Transport.Exchange(ctx, message)
	}
	// Per-query transport avoids retaining sockets for a replaced OS resolver.
	var upstream adapter.DNSTransport
	identity := dns.NewTransportAdapterWithLocalOptions("local", t.Tag(), option.LocalDNSServerOptions{})
	if metadata := adapter.ContextFrom(ctx); metadata != nil && metadata.Network == N.NetworkTCP {
		upstream = transport.NewTCPRaw(identity, t.external, server)
	} else {
		upstream = transport.NewUDPRaw(t.logger, identity, t.external, server)
	}
	defer upstream.Close()
	t.selectionMu.Lock()
	changed := t.lastServer != server
	t.lastServer = server
	t.selectionMu.Unlock()
	if changed {
		t.logger.InfoContext(ctx, "DNS исходной сети: сервер ", server, " выбран Windows; публичная подмена отключена")
	}
	return upstream.Exchange(ctx, message)
}

func (t *lernetPreservingTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	go func() { response, err := t.Exchange(ctx, message); callback(response, err) }()
}
