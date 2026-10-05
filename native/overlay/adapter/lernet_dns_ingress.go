package adapter

import (
	"context"
	"io"
)

// LerNETDNSIngressTracker owns the lifetime of a persistent hijacked DNS
// connection. Its completion callback is called after the real read loop has
// stopped, rather than after an individual asynchronous DNS exchange.
type LerNETDNSIngressTracker interface {
	TrackDNSIngress(context.Context, io.Closer) (context.Context, func())
}
