package dialer

import (
	"net/netip"
	"testing"
)

func TestDirectFamiliesExcludeOwnDownAndLinkLocalRoutes(t *testing.T) {
	routes := []lernetRouteCandidate{
		{prefix: netip.MustParsePrefix("::/1"), index: 1, guid: "own", up: true, owned: true},
		{prefix: netip.MustParsePrefix("::/0"), index: 2, guid: "down"},
		{prefix: netip.MustParsePrefix("fe80::/64"), index: 3, guid: "eth", up: true},
		{prefix: netip.MustParsePrefix("ff00::/8"), index: 3, guid: "eth", up: true},
	}
	if got := routedFamily(routes, true); got != "unavailable" {
		t.Fatal(got)
	}
	routes = append(routes, lernetRouteCandidate{prefix: netip.MustParsePrefix("fd00:20::/48"), index: 3, guid: "corp", up: true})
	if got := routedFamily(routes, true); got != "limited" {
		t.Fatal(got)
	}
	routes = append(routes, lernetRouteCandidate{prefix: netip.MustParsePrefix("::/0"), index: 3, guid: "eth", up: true})
	if got := routedFamily(routes, true); got != "available" {
		t.Fatal(got)
	}
}

func TestNoRouteErrorNamesDestinationFamily(t *testing.T) {
	for _, sample := range []struct{ address, code string }{
		{"192.0.2.1", "system_route_ipv4_unavailable"}, {"2001:db8::1", "system_route_ipv6_unavailable"},
	} {
		_, err := selectLerNETSystemRoute(netip.MustParseAddr(sample.address), nil)
		if err == nil || err.Error() != sample.code {
			t.Fatalf("%s: %v", sample.address, err)
		}
	}
}

func TestAndroidSourceAddressesDoNotProveInternetRoutes(t *testing.T) {
	addresses := []netip.Prefix{netip.MustParsePrefix("192.0.2.3/24"), netip.MustParsePrefix("fe80::1/64")}
	if addressedFamily(addresses, true) != "unavailable" || addressedFamily(addresses, false) != "unknown" {
		t.Fatal("address capability overclaimed")
	}
	addresses = append(addresses, netip.MustParsePrefix("2001:db8::1/64"))
	if addressedFamily(addresses, true) != "unknown" {
		t.Fatal("IPv6 source claimed reachability")
	}
}
