package route

import (
	"context"
	"io"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
)

func (r *Router) lernetTrackDNSIngress(ctx context.Context, conn io.Closer) (context.Context, func()) {
	if tracker := service.FromContext[adapter.LerNETDNSIngressTracker](r.ctx); tracker != nil {
		return tracker.TrackDNSIngress(ctx, conn)
	}
	return ctx, func() {}
}
