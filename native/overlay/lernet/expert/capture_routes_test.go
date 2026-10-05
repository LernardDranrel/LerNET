package expert

import (
	"net/netip"
	"testing"
)

func TestConnectedSubnetCaptureWinsLongestPrefixSelection(t *testing.T) {
	for _, text := range []string{"192.168.1.33/24", "10.0.0.2/8", "2001:db8:abcd::123/64", "fd00::123/48"} {
		parent := netip.MustParsePrefix(text).Masked()
		children := splitCapturePrefix(netip.MustParsePrefix(text))
		if len(children) != 2 {
			t.Fatalf("connected prefix not captured: %s", text)
		}
		for _, child := range children {
			if child.Bits() != parent.Bits()+1 || !parent.Contains(child.Addr()) {
				t.Fatal("capture child does not win longest prefix within subnet")
			}
		}
		if children[0].Contains(children[1].Addr()) {
			t.Fatal("capture children overlap")
		}
	}
	for _, text := range []string{"127.0.0.1/8", "169.254.1.2/16", "fe80::123/64", "192.0.2.1/32", "::1/128"} {
		if len(splitCapturePrefix(netip.MustParsePrefix(text))) != 0 {
			t.Fatalf("scoped/local destination incorrectly captured: %s", text)
		}
	}
}
