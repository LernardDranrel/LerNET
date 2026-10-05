package expert

import (
	"errors"
	"net/netip"

	"go4.org/netipx"
)

type captureRoute struct {
	prefix                     netip.Prefix
	cost                       uint64
	owned                      bool
	interfaceIdentity, nextHop string
}

// Prefixes broader than /2 also matter: a foreign VPN commonly installs /1
// splits. An exact host route can win only with a strictly better metric.
func captureRoutePrefixes(route captureRoute) ([]netip.Prefix, error) {
	prefix := route.prefix.Masked()
	if !prefix.IsValid() {
		return nil, nil
	}
	address := prefix.Addr()
	if prefix.Bits() > 1 && (!address.IsGlobalUnicast() || address.IsLoopback() || address.IsLinkLocalUnicast()) {
		return nil, nil
	}
	if prefix.Bits() == address.BitLen() {
		if route.cost == 0 {
			return nil, errors.New("expert_route_capture_collision")
		}
		return []netip.Prefix{prefix}, nil
	}
	bit := prefix.Bits()
	if address.Is4() {
		bytes := prefix.Addr().As4()
		bytes[bit/8] |= 1 << uint(7-bit%8)
		return []netip.Prefix{netip.PrefixFrom(prefix.Addr(), bit+1), netip.PrefixFrom(netip.AddrFrom4(bytes), bit+1)}, nil
	}
	bytes := prefix.Addr().As16()
	bytes[bit/8] |= 1 << uint(7-bit%8)
	return []netip.Prefix{netip.PrefixFrom(prefix.Addr(), bit+1), netip.PrefixFrom(netip.AddrFrom16(bytes), bit+1)}, nil
}

// Every remote foreign prefix needs full coverage, not a few sampled hosts.
// Nested foreign routes are audited independently, so a narrower late route
// cannot hide in the coverage of a broad default.
func verifyRouteCapture(routes []captureRoute) error {
	for _, foreign := range routes {
		if foreign.owned {
			continue
		}
		required, err := captureRoutePrefixes(foreign)
		if err != nil {
			return err
		}
		if len(required) == 0 {
			continue
		}
		var coverage netipx.IPSetBuilder
		for _, owned := range routes {
			if !owned.owned || owned.cost != 0 {
				continue
			}
			moreSpecific := owned.prefix.Bits() > foreign.prefix.Bits()
			betterEqual := owned.prefix.Bits() == foreign.prefix.Bits() && owned.cost < foreign.cost
			if (moreSpecific || betterEqual) && foreign.prefix.Contains(owned.prefix.Addr()) {
				coverage.AddPrefix(owned.prefix)
			}
		}
		set, err := coverage.IPSet()
		if err != nil || !set.ContainsPrefix(foreign.prefix) {
			return errors.New("expert_route_capture_not_proven")
		}
	}
	return nil
}
