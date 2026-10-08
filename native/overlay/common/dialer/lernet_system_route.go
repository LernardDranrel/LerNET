package dialer

import (
	"errors"
	"net/netip"
	"strconv"
)

type lernetRouteCandidate struct {
	prefix                       netip.Prefix
	index                        int
	name, guid                   string
	routeMetric, interfaceMetric uint32
	up, owned, loopback          bool
}

func selectLerNETSystemRoute(destination netip.Addr, routes []lernetRouteCandidate) (lernetRouteCandidate, error) {
	zone := destination.Zone()
	destination = destination.WithZone("")
	var selected lernetRouteCandidate
	for _, route := range routes {
		if zone != "" && zone != strconv.Itoa(route.index) && zone != route.name {
			continue
		}
		if !route.up || route.owned || route.index <= 0 || route.guid == "" || !route.prefix.IsValid() || !route.prefix.Contains(destination) {
			continue
		}
		cost := uint64(route.routeMetric) + uint64(route.interfaceMetric)
		oldCost := uint64(selected.routeMetric) + uint64(selected.interfaceMetric)
		if !selected.prefix.IsValid() || route.prefix.Bits() > selected.prefix.Bits() || route.prefix.Bits() == selected.prefix.Bits() && (cost < oldCost || cost == oldCost && route.index < selected.index) {
			selected = route
		}
	}
	if !selected.prefix.IsValid() {
		if destination.Is6() {
			return selected, errors.New("system_route_ipv6_unavailable")
		}
		return selected, errors.New("system_route_ipv4_unavailable")
	}
	return selected, nil
}
