package dns

import (
	"context"
	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
	"testing"
)

type directFamilyPolicy bool

func (p directFamilyPolicy) UnavailableIPv6(domain, server string) bool { return bool(p) }

func TestTransparentIPv4OnlyDNSReturnsNoDataNotNameFailure(t *testing.T) {
	ctx := service.ContextWith[adapter.LerNETDirectIPv6Policy](service.ExtendContext(context.Background()), directFamilyPolicy(true))
	query := new(mDNS.Msg)
	query.SetQuestion("example.invalid.", mDNS.TypeAAAA)
	response := lernetDirectFamilyResponse(ctx, query, "dns-direct")
	if response == nil || response.Rcode != mDNS.RcodeSuccess || len(response.Answer) != 0 || response.Id != query.Id || !response.Response {
		t.Fatalf("invalid NODATA: %+v", response)
	}
	query.SetQuestion("example.invalid.", mDNS.TypeA)
	if lernetDirectFamilyResponse(ctx, query, "dns-direct") != nil {
		t.Fatal("working IPv4 DNS changed")
	}
}

func TestMixedPolicyAndUnknownIPv6KeepOriginalAnswers(t *testing.T) {
	query := new(mDNS.Msg)
	query.SetQuestion("example.invalid.", mDNS.TypeAAAA)
	if lernetDirectFamilyResponse(context.Background(), query, "dns-direct") != nil {
		t.Fatal("mixed policy DNS changed")
	}
	ctx := service.ContextWith[adapter.LerNETDirectIPv6Policy](service.ExtendContext(context.Background()), directFamilyPolicy(false))
	if lernetDirectFamilyResponse(ctx, query, "dns-direct") != nil {
		t.Fatal("unknown or available IPv6 suppressed")
	}
}
