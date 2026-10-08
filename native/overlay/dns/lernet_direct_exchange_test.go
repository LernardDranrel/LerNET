package dns

import (
	"context"
	"net/netip"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

type scopedDirectPolicy struct{ unavailable bool }

func (p *scopedDirectPolicy) UnavailableIPv6(domain, server string) bool {
	return p.unavailable && domain == "example.invalid." && server == "final"
}

func TestLerNETDirectFamilyUsesSelectedResolverInSyncAndAsyncExchange(t *testing.T) {
	for _, async := range []bool{false, true} {
		policy := &scopedDirectPolicy{unavailable: true}
		ctx := service.ContextWith[adapter.LerNETDirectIPv6Policy](service.ExtendContext(context.Background()), policy)
		direct := &fakeDNSTransport{tag: "final", address: netip.MustParseAddr("2001:db8::1"), immediate: true}
		protected := &fakeDNSTransport{tag: "protected", address: netip.MustParseAddr("2001:db8::2"), immediate: true}
		router := raceTestRouter(t, direct, protected)
		router.ctx = ctx
		router.client = NewClient(ClientOptions{Context: ctx, Logger: router.logger})
		query := new(mDNS.Msg)
		query.SetQuestion("example.invalid.", mDNS.TypeAAAA)
		exchange := func() *mDNS.Msg {
			t.Helper()
			if !async {
				response, err := router.Exchange(ctx, query, adapter.DNSQueryOptions{})
				if err != nil {
					t.Fatal(err)
				}
				return response
			}
			ch := make(chan *mDNS.Msg, 1)
			errors := make(chan error, 1)
			router.ExchangeAsync(ctx, query, adapter.DNSQueryOptions{}, func(response *mDNS.Msg, err error) { errors <- err; ch <- response })
			select {
			case response := <-ch:
				if err := <-errors; err != nil {
					t.Fatal(err)
				}
				return response
			case <-time.After(time.Second):
				t.Fatal("callback stalled")
				return nil
			}
		}
		response := exchange()
		if response.Rcode != mDNS.RcodeSuccess || len(response.Answer) != 0 || direct.queryCount.Load() != 0 {
			t.Fatal("Direct AAAA was not adapted")
		}
		router.rules = raceTestRules(t, []option.DNSRule{{DefaultOptions: option.DefaultDNSRule{DNSRuleAction: option.DNSRuleAction{Action: C.RuleActionTypeReject, RejectOptions: option.RejectActionOptions{Method: C.RuleActionRejectMethodDefault}}}}})
		if response := exchange(); response.Rcode != mDNS.RcodeRefused {
			t.Fatal("family adaptation bypassed DNS rejection")
		}
		router.rules = raceTestRules(t, []option.DNSRule{{DefaultOptions: option.DefaultDNSRule{DNSRuleAction: option.DNSRuleAction{Action: C.RuleActionTypeRoute, RouteOptions: option.DNSRouteActionOptions{Server: "protected"}}}}})
		if response := exchange(); len(response.Answer) != 1 || protected.queryCount.Load() != 1 {
			t.Fatal("protected resolver lost IPv6")
		}
		router.rules = nil
		policy.unavailable = false
		if response := exchange(); len(response.Answer) != 1 || direct.queryCount.Load() != 1 {
			t.Fatal("synthetic NODATA survived network recovery in DNS cache")
		}
	}
}
