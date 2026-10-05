package dns

import (
	"context"
	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"testing"
)

func TestLerNETDNSDetourCannotInheritBusinessDirectFallback(t *testing.T) {
	parent := adapter.InboundContext{LerNETFallback: "direct", LerNETNodeIDs: []string{"ordinary-branch"}}
	ctx := adapter.WithContext(context.Background(), &parent)
	r := Router{logger: log.NewNOPFactory().NewLogger("test")}
	query := new(mDNS.Msg)
	query.SetQuestion("protected.company.example.", mDNS.TypeA)
	exchange, response, err := r.prepareExchange(ctx, query)
	if err != nil || response != nil || exchange == nil {
		t.Fatalf("prepare exchange: %v", err)
	}
	dnsMetadata := adapter.ContextFrom(exchange.ctx)
	if dnsMetadata == nil || !dnsMetadata.LerNETProtected || dnsMetadata.LerNETFallback != "block" {
		t.Fatal("DNS gate could inherit ordinary DIRECT fallback")
	}
	if parent.LerNETProtected || parent.LerNETFallback != "direct" {
		t.Fatal("DNS preparation mutated parent business policy")
	}
	if len(dnsMetadata.LerNETNodeIDs) != 1 || dnsMetadata.LerNETNodeIDs[0] != "ordinary-branch" {
		t.Fatal("original context trace lost")
	}
}
