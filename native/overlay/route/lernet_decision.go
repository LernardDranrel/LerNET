package route

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	R "github.com/sagernet/sing-box/route/rule"
)

// Optional observer: ordinary sing-box clients keep the existing tracker API.
// Rejections are recorded once before closing and carry no invented byte counts.
func (r *Router) LerNETObserveDecision(ctx context.Context, m adapter.InboundContext, rule adapter.Rule, state, reason string) {
	if rule != nil {
		m.RouteRule = rule.String()
		if action, ok := rule.Action().(*R.RuleActionReject); ok {
			m.LerNETNodeIDs = append([]string(nil), action.LerNETNodeIDs...)
		}
	}
	for _, tracker := range r.trackers {
		if observer, ok := tracker.(interface {
			LerNETDecision(context.Context, adapter.InboundContext, string, string)
		}); ok {
			observer.LerNETDecision(ctx, m, state, reason)
		}
	}
}
