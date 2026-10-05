package expert

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strings"

	"github.com/sagernet/sing-box/option"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

func requireExpertPrivilege() error {
	if !windows.GetCurrentProcessToken().IsElevated() {
		return errors.New("expert_elevation_required")
	}
	return nil
}

func physicalUnderlay(name, ownName string) (*net.Interface, error) {
	iif, err := net.InterfaceByName(name)
	if err != nil || iif.Flags&net.FlagUp == 0 || iif.Flags&net.FlagLoopback != 0 || strings.EqualFold(iif.Name, ownName) {
		return nil, errors.New("expert_underlay_binding_invalid")
	}
	luid, err := winipcfg.LUIDFromIndex(uint32(iif.Index))
	if err != nil {
		return nil, errors.New("expert_underlay_binding_invalid")
	}
	row, err := luid.Interface()
	if err != nil || row.InterfaceAndOperStatusFlags&winipcfg.IAOSFHardwareInterface == 0 {
		return nil, errors.New("expert_underlay_must_be_physical")
	}
	return iif, nil
}

func preparePlatformIngress(ctx context.Context, options *option.Options) error {
	tun := options.Inbounds[0].Options.(*option.TunInboundOptions)
	if options.Route == nil || options.Route.DefaultInterface == "" {
		return errors.New("expert_underlay_binding_required")
	}
	_, err := physicalUnderlay(options.Route.DefaultInterface, tun.InterfaceName)
	if err != nil {
		return err
	}
	// Exclusions cause upstream prefix-set normalization to collapse our more
	// specific LAN routes. Expert captures traffic first and applies exceptions
	// as graph decisions, rather than excluding destinations from the ingress.
	if !tun.AutoRoute || len(tun.RouteExcludeAddress) > 0 || len(tun.RouteExcludeAddressSet) > 0 || len(tun.RouteAddressSet) > 0 {
		return errors.New("expert_ingress_capture_exclusions_unsupported")
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return errors.New("expert_connected_network_snapshot_failed")
	}
	defaults := []netip.Prefix{netip.MustParsePrefix("0.0.0.0/1"), netip.MustParsePrefix("128.0.0.0/1"), netip.MustParsePrefix("::/1"), netip.MustParsePrefix("8000::/1")}
	routes := append([]netip.Prefix{}, tun.RouteAddress...)
	routes = append(routes, defaults...)
	for _, iif := range interfaces {
		if iif.Flags&net.FlagUp == 0 || iif.Flags&net.FlagLoopback != 0 || isOwnedCaptureInterface(ctx, iif.Index) {
			continue
		}
		addresses, err := iif.Addrs()
		if err != nil {
			return errors.New("expert_connected_network_snapshot_failed")
		}
		for _, address := range addresses {
			prefix, err := netip.ParsePrefix(address.String())
			if err != nil {
				continue
			}
			routes = append(routes, splitCapturePrefix(prefix)...)
		}
	}
	observed, err := operationalCaptureRoutes(ctx)
	if err != nil {
		return err
	}
	for _, route := range observed {
		if route.owned {
			continue
		}
		prefixes, err := captureRoutePrefixes(route)
		if err != nil {
			return err
		}
		routes = append(routes, prefixes...)
	}
	seen := make(map[netip.Prefix]bool)
	tun.RouteAddress = nil
	for _, prefix := range routes {
		prefix = prefix.Masked()
		if !seen[prefix] {
			tun.RouteAddress = append(tun.RouteAddress, prefix)
			seen[prefix] = true
		}
	}
	return nil
}

func (s *Session) refreshPlatformUnderlay(name string) error {
	parts := strings.Split(s.ack.InterfaceID, ":")
	if len(parts) != 3 {
		return errors.New("tun_identity_unavailable")
	}
	underlay, err := physicalUnderlay(name, parts[0])
	if err != nil {
		s.underlay.Store(nil)
		return err
	}
	tunOptions := s.baseTun
	tunOptions.InterfaceName = parts[0]
	options := option.Options{Route: &option.RouteOptions{DefaultInterface: name}, Inbounds: []option.Inbound{{Type: "tun", Options: &tunOptions}}}
	if err = preparePlatformIngress(s.ctx, &options); err != nil {
		s.underlay.Store(nil)
		return err
	}
	s.underlay.Store(underlay)
	for _, inbound := range s.ingress.Inbound().Inbounds() {
		if actual, ok := inbound.(interface{ LerNETUpdateCaptureRoutes([]netip.Prefix) error }); ok {
			if err = actual.LerNETUpdateCaptureRoutes(tunOptions.RouteAddress); err != nil {
				s.underlay.Store(nil)
				return errors.New("expert_capture_route_update_failed")
			}
		}
	}
	s.capturePrefixes = append([]netip.Prefix(nil), tunOptions.RouteAddress...)
	return s.verifyPlatformCapture(s.ack.InterfaceID)
}
