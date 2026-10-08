package dialer

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/service"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

func LerNETSystemRouteContext(ctx context.Context) (context.Context, error) {
	manager := service.FromContext[adapter.NetworkManager](ctx)
	if manager == nil {
		return nil, errors.New("system_route_manager_unavailable")
	}
	guard := func(network, address string, raw syscall.RawConn) error {
		host, _, err := net.SplitHostPort(address)
		if err != nil {
			return errors.New("system_route_destination_invalid")
		}
		destination, err := netip.ParseAddr(host)
		if err != nil || destination.IsUnspecified() {
			return errors.New("system_route_destination_invalid")
		}
		selected, err := resolveLerNETSystemRoute(manager, destination)
		if err != nil {
			return err
		}
		bind := control.BindToInterface(manager.InterfaceFinder(), selected.name, selected.index)
		if err = bind(network, address, raw); err != nil {
			return errors.New("system_route_bind_failed")
		}
		current, err := resolveLerNETSystemRoute(manager, destination)
		if err != nil {
			return err
		}
		if current.guid != selected.guid || current.index != selected.index || current.name != selected.name {
			return errors.New("system_route_changed")
		}
		return nil
	}
	return service.ContextWith[*lernetVerifiedBinding](service.ExtendContext(ctx), &lernetVerifiedBinding{control: guard, destinationAware: true}), nil
}

func resolveLerNETSystemRoute(manager adapter.NetworkManager, destination netip.Addr) (lernetRouteCandidate, error) {
	holder, ok := manager.(interface{ LerNETIngressIdentity() *LerNETIngressIdentity })
	if !ok || holder.LerNETIngressIdentity() == nil {
		return lernetRouteCandidate{}, errors.New("ingress_not_ready")
	}
	own := holder.LerNETIngressIdentity()
	family := winipcfg.AddressFamily(windows.AF_INET)
	if destination.Is6() {
		family = windows.AF_INET6
	}
	rows, err := winipcfg.GetIPForwardTable2(family)
	if err != nil {
		return lernetRouteCandidate{}, errors.New("system_route_snapshot_failed")
	}
	interfaceRows := make(map[winipcfg.LUID]lernetRouteCandidate)
	var candidates []lernetRouteCandidate
	for _, row := range rows {
		prefix := row.DestinationPrefix.Prefix()
		if !prefix.Contains(destination.WithZone("")) {
			continue
		}
		candidate, cached := interfaceRows[row.InterfaceLUID]
		if !cached {
			currentLUID, err := winipcfg.LUIDFromIndex(row.InterfaceIndex)
			if err != nil || currentLUID != row.InterfaceLUID {
				return lernetRouteCandidate{}, errors.New("system_route_changed")
			}
			actual, err := net.InterfaceByIndex(int(row.InterfaceIndex))
			if err != nil {
				return lernetRouteCandidate{}, errors.New("system_route_changed")
			}
			info, err := row.InterfaceLUID.Interface()
			if err != nil || info.InterfaceIndex != row.InterfaceIndex {
				return lernetRouteCandidate{}, errors.New("system_route_changed")
			}
			guid, err := row.InterfaceLUID.GUID()
			if err != nil {
				return lernetRouteCandidate{}, errors.New("system_route_changed")
			}
			ipInfo, err := row.InterfaceLUID.IPInterface(family)
			if err != nil {
				return lernetRouteCandidate{}, errors.New("system_route_changed")
			}
			candidate = lernetRouteCandidate{index: actual.Index, name: actual.Name, guid: guid.String(), interfaceMetric: ipInfo.Metric,
				up: actual.Flags&net.FlagUp != 0 && info.OperStatus == winipcfg.IfOperStatusUp, owned: own.Matches(uint64(row.InterfaceLUID), row.InterfaceIndex, guid.String()), loopback: actual.Flags&net.FlagLoopback != 0 || info.Type == winipcfg.IfTypeSoftwareLoopback}
			interfaceRows[row.InterfaceLUID] = candidate
		}
		if candidate.index != int(row.InterfaceIndex) {
			return lernetRouteCandidate{}, errors.New("system_route_changed")
		}
		candidate.prefix, candidate.routeMetric = prefix, row.Metric
		candidates = append(candidates, candidate)
	}
	return selectLerNETSystemRoute(destination, candidates)
}
