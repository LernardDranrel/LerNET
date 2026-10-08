//go:build !windows

package dialer

import (
	"github.com/sagernet/sing-box/adapter"
	"net"
)

func LerNETDirectFamilySnapshot(manager adapter.NetworkManager) LerNETDirectFamilies {
	result := unknownDirectFamilies("platform_underlay_addresses")
	if manager == nil {
		return result
	}
	current := manager.DefaultNetworkInterface()
	if current == nil || current.Flags&net.FlagUp == 0 || len(current.Addresses) == 0 {
		return result
	}
	result.Interface = current.Name
	result.IPv4, result.IPv6 = addressedFamily(current.Addresses, false), addressedFamily(current.Addresses, true)
	return result
}
