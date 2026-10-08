//go:build !windows

package local

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
)

func newLerNETPreservingTransport(_ context.Context, _ log.ContextLogger, base *Transport) (adapter.DNSTransport, error) {
	return base, nil
}

func lernetPreservingContext(ctx context.Context) (context.Context, error) { return ctx, nil }
