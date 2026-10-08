package dialer

import (
	"net/netip"
	"testing"
)

func TestLerNETSystemRoutePreservesDestinationRoutes(t *testing.T) {
	routes := []lernetRouteCandidate{
		{prefix: netip.MustParsePrefix("0.0.0.0/0"), index: 1, name: "WiFi", guid: "physical", up: true, interfaceMetric: 30},
		{prefix: netip.MustParsePrefix("0.0.0.0/0"), index: 2, name: "OtherVPN", guid: "foreign", up: true, interfaceMetric: 10},
		{prefix: netip.MustParsePrefix("10.20.0.0/16"), index: 3, name: "Corporate", guid: "corporate", up: true, interfaceMetric: 999},
		{prefix: netip.MustParsePrefix("10.20.1.0/24"), index: 4, name: "LerNET", guid: "owned", up: true, owned: true},
		{prefix: netip.MustParsePrefix("0.0.0.0/1"), index: 4, name: "LerNET", guid: "owned", up: true, owned: true},
	}
	for _, test := range []struct {
		destination string
		want        string
	}{{"10.20.1.2", "corporate"}, {"1.1.1.1", "foreign"}, {"200.1.1.1", "foreign"}} {
		got, err := selectLerNETSystemRoute(netip.MustParseAddr(test.destination), routes)
		if err != nil || got.guid != test.want {
			t.Fatalf("%s: got %v, %v", test.destination, got, err)
		}
	}
	routes[1].up = false
	got, err := selectLerNETSystemRoute(netip.MustParseAddr("1.1.1.1"), routes)
	if err != nil || got.guid != "physical" {
		t.Fatalf("physical default: %v %v", got, err)
	}
	routes[0].up = false
	if _, err = selectLerNETSystemRoute(netip.MustParseAddr("1.1.1.1"), routes); err == nil {
		t.Fatal("vanished routes silently accepted")
	}
}

func TestLerNETSystemRouteUsesCombinedMetricAndIPv6(t *testing.T) {
	routes := []lernetRouteCandidate{
		{prefix: netip.MustParsePrefix("::/0"), index: 1, name: "A", guid: "a", up: true, routeMetric: 20, interfaceMetric: 1},
		{prefix: netip.MustParsePrefix("::/0"), index: 2, name: "B", guid: "b", up: true, routeMetric: 1, interfaceMetric: 30},
		{prefix: netip.MustParsePrefix("fd00:20::/48"), index: 3, name: "Corporate", guid: "corp", up: true, routeMetric: 999},
		{prefix: netip.MustParsePrefix("::/1"), index: 4, name: "Own", guid: "owned", up: true, owned: true},
	}
	for _, test := range []struct{ destination, want string }{{"2001:db8::1", "a"}, {"fd00:20::42", "corp"}} {
		got, err := selectLerNETSystemRoute(netip.MustParseAddr(test.destination), routes)
		if err != nil || got.guid != test.want {
			t.Fatalf("%s: %v %v", test.destination, got, err)
		}
	}
}

func TestLerNETSystemRouteAllowsHostLocalRedirectWithoutOwnedRoute(t *testing.T) {
	routes := []lernetRouteCandidate{
		{prefix: netip.MustParsePrefix("127.0.0.0/8"), index: 1, name: "Loopback", guid: "loop", up: true, loopback: true},
		{prefix: netip.MustParsePrefix("127.0.0.1/32"), index: 4, name: "LerNET", guid: "own", up: true, owned: true},
	}
	got, err := selectLerNETSystemRoute(netip.MustParseAddr("127.0.0.1"), routes)
	if err != nil || got.guid != "loop" {
		t.Fatalf("local redirect %v %v", got, err)
	}
}

func TestLerNETSystemRoutePreservesIPv6DNSInterfaceScope(t *testing.T) {
	routes := []lernetRouteCandidate{
		{prefix: netip.MustParsePrefix("fe80::/64"), index: 14, name: "Ethernet", guid: "dns", up: true, interfaceMetric: 999},
		{prefix: netip.MustParsePrefix("fe80::/64"), index: 15, name: "Other", guid: "other", up: true},
	}
	for _, scope := range []string{"14", "Ethernet"} {
		got, err := selectLerNETSystemRoute(netip.MustParseAddr("fe80::53").WithZone(scope), routes)
		if err != nil || got.guid != "dns" {
			t.Fatalf("scope %s: %v %v", scope, got, err)
		}
	}
	if _, err := selectLerNETSystemRoute(netip.MustParseAddr("fe80::53%16"), routes); err == nil {
		t.Fatal("missing DNS interface fell back to another adapter")
	}
}
