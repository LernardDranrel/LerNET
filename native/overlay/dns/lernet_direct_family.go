package dns

import (
	"context"
	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
)

func lernetDirectFamilyResponse(ctx context.Context, message *mDNS.Msg, server string) *mDNS.Msg {
	if ctx == nil {
		return nil
	}
	policy := service.FromContext[adapter.LerNETDirectIPv6Policy](ctx)
	if policy == nil || message.Opcode != mDNS.OpcodeQuery || len(message.Question) != 1 ||
		message.Question[0].Qtype != mDNS.TypeAAAA || message.Question[0].Qclass != mDNS.ClassINET ||
		len(message.Answer) != 0 || len(message.Ns) != 0 {
		return nil
	}
	for _, extra := range message.Extra {
		opt, ok := extra.(*mDNS.OPT)
		if !ok || opt.Do() {
			return nil
		} // Keep signed/DNSSEC queries unchanged.
	}
	if !policy.UnavailableIPv6(message.Question[0].Name, server) {
		return nil
	}
	response := new(mDNS.Msg)
	response.SetReply(message)
	response.RecursionAvailable = true
	// NODATA, not NXDOMAIN: this name may have working A records. Do not cache
	// a synthetic negative answer across a network or policy change.
	return response
}
