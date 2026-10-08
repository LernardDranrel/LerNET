package dialer

import (
	"net"

	"github.com/sagernet/sing-box/adapter"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

func LerNETDirectFamilySnapshot(manager adapter.NetworkManager) LerNETDirectFamilies {
	result := unknownDirectFamilies("windows_routes")
	holder, ok := manager.(interface{ LerNETIngressIdentity() *LerNETIngressIdentity })
	if !ok || holder.LerNETIngressIdentity() == nil {
		return result
	}
	own := holder.LerNETIngressIdentity()
	for _, family := range []winipcfg.AddressFamily{windows.AF_INET, windows.AF_INET6} {
		rows, err := winipcfg.GetIPForwardTable2(family)
		if err != nil {
			continue
		}
		var candidates []lernetRouteCandidate
		complete := true
		for _, row := range rows {
			info, err := row.InterfaceLUID.Interface()
			if err != nil {
				complete = false
				break
			}
			if info.OperStatus != winipcfg.IfOperStatusUp {
				continue
			}
			guid, err := row.InterfaceLUID.GUID()
			if err != nil || info.InterfaceIndex != row.InterfaceIndex {
				complete = false
				break
			}
			candidates = append(candidates, lernetRouteCandidate{prefix: row.DestinationPrefix.Prefix(), index: int(row.InterfaceIndex),
				guid: guid.String(), up: true, owned: own.Matches(uint64(row.InterfaceLUID), row.InterfaceIndex, guid.String()),
				loopback: info.Type == winipcfg.IfTypeSoftwareLoopback})
		}
		if !complete {
			continue
		}
		if family == windows.AF_INET {
			result.IPv4 = routedFamily(candidates, false)
		} else {
			result.IPv6 = routedFamily(candidates, true)
		}
	}
	if current := manager.DefaultNetworkInterface(); current != nil && current.Flags&net.FlagUp != 0 {
		result.Interface = current.Name
	}
	return result
}
