package dialer

import "net/netip"

// These are local path capabilities, never an Internet reachability verdict.
type LerNETDirectFamilies struct {
	IPv4      string `json:"ipv4"`
	IPv6      string `json:"ipv6"`
	Source    string `json:"source"`
	Interface string `json:"interface,omitempty"`
}

func unknownDirectFamilies(source string) LerNETDirectFamilies {
	return LerNETDirectFamilies{IPv4: "unknown", IPv6: "unknown", Source: source}
}

func routedFamily(routes []lernetRouteCandidate, ipv6 bool) string {
	state := "unavailable"
	for _, route := range routes {
		if !route.up || route.owned || route.loopback || route.index <= 0 || route.guid == "" ||
			!route.prefix.IsValid() || route.prefix.Addr().Is6() != ipv6 {
			continue
		}
		if route.prefix.Bits() == 0 || route.prefix.Bits() == 1 {
			return "available"
		}
		address := route.prefix.Addr()
		if address.IsGlobalUnicast() && !address.IsLinkLocalUnicast() && !address.IsLoopback() {
			state = "limited"
		}
	}
	return state
}

func addressedFamily(addresses []netip.Prefix, ipv6 bool) string {
	for _, prefix := range addresses {
		address := prefix.Addr()
		if address.IsValid() && address.Is6() == ipv6 && address.IsGlobalUnicast() && !address.IsLinkLocalUnicast() && !address.IsLoopback() {
			// Android interface inventory exposes addresses, not the full selected
			// route table. A source address alone does not prove a default route.
			return "unknown"
		}
	}
	return "unavailable"
}
