package tun

import (
	"testing"

	singTun "github.com/sagernet/sing-tun"
)

func TestLerNETRetainsActualPlatformOpenedDescriptor(t *testing.T) {
	inbound := &Inbound{tunOptions: singTun.Options{Name: "requested-alias"}}
	inbound.lernetRetainOpenedIdentity(singTun.Options{Name: "unlisted-test-tun", FileDescriptor: 142})
	if inbound.tunOptions.Name != "unlisted-test-tun" || inbound.tunOptions.FileDescriptor != 142 {
		t.Fatal("platform descriptor mutation was lost after OpenInterface")
	}
	if inbound.LerNETInterfaceIdentity() != "unlisted-test-tun:0:142" {
		t.Fatal("identity omitted the retained descriptor when interface lookup fails")
	}
}
