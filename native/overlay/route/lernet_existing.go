package route

import (
	"github.com/sagernet/sing-box/adapter"
	R "github.com/sagernet/sing-box/route/rule"
)

// Existing flows already carry the original owner and sniffed destination.
// Re-evaluate only terminal policy decisions; do not read from their live socket
// or start another DNS/sniff operation while committing a generation.
func (r *Router) LerNETRejectExisting(metadata adapter.InboundContext) bool {
	if r.LerNETOwnerUnknown(&metadata) {
		return true
	}
	if metadata.RouteOriginalDestination.IsValid() {
		metadata.Destination = metadata.RouteOriginalDestination
	}
	for _, rule := range r.rules {
		metadata.ResetRuleCache()
		if !rule.Match(&metadata) {
			continue
		}
		switch action := rule.Action().(type) {
		case *R.RuleActionReject:
			return true
		case *R.RuleActionRoute, *R.RuleActionHijackDNS, *R.RuleActionBypass, *R.RuleActionDirect:
			return false
		case *R.RuleActionRouteOptions:
			applyRouteOptionsOverride(&metadata, action)
		}
	}
	return false
}
