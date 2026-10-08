package expert

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"

	"github.com/sagernet/sing-box/common/dialer"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/service"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

func (s *Session) initializePlatformIdentity() error {
	if guarded := service.FromContext[*tun.LerNETGuardedAdapter](s.ctx); guarded != nil {
		guid, err := windows.GUIDFromString("{" + strings.Trim(guarded.GUID, "{}") + "}")
		if err != nil {
			return errors.New("guarded_adapter_invalid")
		}
		s.ingressIdentity.Store(&dialer.LerNETIngressIdentity{LUID: guarded.LUID, Index: guarded.IfIndex, GUID: guid.String()})
	}
	return nil
}

func isOwnedCaptureInterface(ctx context.Context, index int) bool {
	holder := service.FromContext[*dialer.LerNETIngressIdentityHolder](ctx)
	if holder == nil || holder.Load() == nil {
		return false
	}
	luid, err := winipcfg.LUIDFromIndex(uint32(index))
	if err != nil {
		return false
	}
	guid, err := luid.GUID()
	if err != nil {
		return false
	}
	return holder.Load().Matches(uint64(luid), uint32(index), guid.String())
}

func operationalCaptureRoutes(ctx context.Context) ([]captureRoute, error) {
	holder := service.FromContext[*dialer.LerNETIngressIdentityHolder](ctx)
	var own *dialer.LerNETIngressIdentity
	if holder != nil {
		own = holder.Load()
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, errors.New("expert_route_snapshot_failed")
	}
	local := make(map[netip.Addr]bool)
	for _, iif := range interfaces {
		addresses, err := iif.Addrs()
		if err != nil {
			return nil, errors.New("expert_route_snapshot_failed")
		}
		for _, address := range addresses {
			if prefix, err := netip.ParsePrefix(address.String()); err == nil {
				local[prefix.Addr()] = true
			}
		}
	}
	var result []captureRoute
	for _, family := range []winipcfg.AddressFamily{windows.AF_INET, windows.AF_INET6} {
		rows, err := winipcfg.GetIPForwardTable2(family)
		if err != nil {
			return nil, errors.New("expert_route_snapshot_failed")
		}
		for _, row := range rows {
			info, err := row.InterfaceLUID.Interface()
			if err != nil {
				return nil, errors.New("expert_route_snapshot_failed")
			}
			if info.OperStatus != winipcfg.IfOperStatusUp || info.Type == winipcfg.IfTypeSoftwareLoopback {
				continue
			}
			guid, err := row.InterfaceLUID.GUID()
			if err != nil {
				return nil, errors.New("expert_route_snapshot_failed")
			}
			if info.InterfaceIndex != row.InterfaceIndex {
				return nil, errors.New("expert_route_snapshot_failed")
			}
			ipInfo, err := row.InterfaceLUID.IPInterface(family)
			if err != nil {
				return nil, errors.New("expert_route_snapshot_failed")
			}
			prefix := row.DestinationPrefix.Prefix().Masked()
			owned := own.Matches(uint64(row.InterfaceLUID), row.InterfaceIndex, guid.String())
			if !owned && prefix.Bits() == prefix.Addr().BitLen() && local[prefix.Addr()] {
				continue
			}
			result = append(result, captureRoute{prefix: prefix, cost: uint64(row.Metric) + uint64(ipInfo.Metric), owned: owned, interfaceIdentity: fmt.Sprintf("%d:%d:%s", row.InterfaceLUID, row.InterfaceIndex, guid.String()), nextHop: row.NextHop.Addr().String()})
		}
	}
	return result, nil
}

func (s *Session) verifyPlatformCapture(identity string) error {
	if err := s.verifyPlatformIdentity(identity); err != nil {
		return err
	}
	routes, err := operationalCaptureRoutes(s.ctx)
	if err != nil {
		return err
	}
	for _, prefix := range s.capturePrefixes {
		found := false
		for _, route := range routes {
			if route.owned && route.prefix == prefix.Masked() && route.cost == 0 {
				found = true
				break
			}
		}
		if !found {
			return errors.New("expert_route_capture_not_proven")
		}
	}
	return verifyRouteCapture(routes)
}

func (s *Session) verifyPlatformIdentity(identity string) error {
	parts := strings.Split(identity, ":")
	if len(parts) != 3 {
		return errors.New("tun_identity_unavailable")
	}
	index, err := strconv.ParseUint(parts[1], 10, 32)
	if err != nil || index == 0 {
		return errors.New("tun_identity_unavailable")
	}
	luid, err := winipcfg.LUIDFromIndex(uint32(index))
	if err != nil {
		return errors.New("tun_identity_unavailable")
	}
	guid, err := luid.GUID()
	if err != nil {
		return errors.New("tun_identity_unavailable")
	}
	info, err := luid.Interface()
	if err != nil || info.OperStatus != winipcfg.IfOperStatusUp || info.InterfaceIndex != uint32(index) {
		return errors.New("tun_identity_unavailable")
	}
	actual := &dialer.LerNETIngressIdentity{LUID: uint64(luid), Index: uint32(index), GUID: guid.String()}
	previous := s.ingressIdentity.Load()
	if previous != nil && !previous.Matches(actual.LUID, actual.Index, actual.GUID) {
		return errors.New("tun_identity_changed")
	}
	s.ingressIdentity.Store(actual)
	return nil
}

func (s *Session) checkPlatformIngress(full bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.ctx.Err() != nil {
		return ErrStopped
	}
	var err error
	if full {
		err = s.verifyPlatformCapture(s.ack.InterfaceID)
	} else {
		err = s.verifyPlatformIdentity(s.ack.InterfaceID)
	}
	if err != nil {
		// Keep routes installed and stop new admission while the watcher retries.
		s.setNetworkReasonLocked(ErrorCode(err))
	}
	return err
}
