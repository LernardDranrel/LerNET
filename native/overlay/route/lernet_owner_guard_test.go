package route

import (
	"github.com/sagernet/sing-box/adapter"
	"testing"
)

func TestLerNETUnknownOwnerDNSExceptionNeverDisablesTrafficGuard(t *testing.T) {
	unknown := adapter.InboundContext{}
	r := Router{lernetOwnerGuard: "package", lernetUnknownOwnerDNSSafe: true}
	if !r.LerNETOwnerUnknown(&unknown) {
		t.Fatal("ordinary unknown-owner traffic escaped")
	}
	if r.LerNETOwnerUnknownDNS(&unknown) {
		t.Fatal("compiled protected system DNS projection unreachable")
	}
	r.lernetUnknownOwnerDNSSafe = false
	if !r.LerNETOwnerUnknownDNS(&unknown) {
		t.Fatal("DNS exception enabled without generated safety contract")
	}
	r.lernetOwnerGuard = "process"
	r.lernetUnknownOwnerDNSSafe = true
	if !r.LerNETOwnerUnknownDNS(&unknown) {
		t.Fatal("Android DNS exception relaxed Windows process guard")
	}
}
