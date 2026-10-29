package expert

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
	"time"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

// Include DNS/gateway/MAC changes even when IP/index stay unchanged. Only
// hardware interfaces select the underlay. Remote routes on all operational
// adapters also participate, so PPP resource-prefix-only changes are detected.
//
// Prefer a hardware interface that still has a default route. When Ethernet
// comes back Up before DHCP installs 0.0.0.0/0 (or defaults only exist on TUN),
// still select an Up physical adapter so NetworkChangedToAt can rebind and
// reinstall capture — matching WindowsPhysicalNetwork's PowerShell selection.
func physicalNetworkSnapshot(ctx context.Context) (string, string, error) {
	adapters, err := winipcfg.GetAdaptersAddresses(windows.AF_UNSPEC, winipcfg.GAAFlagIncludeGateways)
	if err != nil {
		return "", "", err
	}
	physical := make(map[winipcfg.LUID]*winipcfg.IPAdapterAddresses)
	var facts []string
	for _, adapter := range adapters {
		row, err := adapter.LUID.Interface()
		if err != nil || row.InterfaceAndOperStatusFlags&winipcfg.IAOSFHardwareInterface == 0 {
			continue
		}
		physical[adapter.LUID] = adapter
		facts = append(facts, fmt.Sprintf("adapter:%d:%s:%d:%x", adapter.LUID, adapter.FriendlyName(), adapter.OperStatus, adapter.PhysicalAddress()))
		for address := adapter.FirstUnicastAddress; address != nil; address = address.Next {
			facts = append(facts, fmt.Sprintf("ip:%d:%s:%d", adapter.LUID, address.Address.IP(), address.OnLinkPrefixLength))
		}
		for address := adapter.FirstDNSServerAddress; address != nil; address = address.Next {
			facts = append(facts, fmt.Sprintf("dns:%d:%s", adapter.LUID, address.Address.IP()))
		}
		for address := adapter.FirstGatewayAddress; address != nil; address = address.Next {
			facts = append(facts, fmt.Sprintf("gateway:%d:%s", adapter.LUID, address.Address.IP()))
		}
	}
	selected := ""
	best := uint64(^uint32(0)) * 2
	for _, family := range []winipcfg.AddressFamily{windows.AF_INET, windows.AF_INET6} {
		routes, err := winipcfg.GetIPForwardTable2(family)
		if err != nil {
			return "", "", err
		}
		for _, route := range routes {
			if route.DestinationPrefix.PrefixLength != 0 {
				continue
			}
			adapter := physical[route.InterfaceLUID]
			if adapter == nil || adapter.OperStatus != winipcfg.IfOperStatusUp {
				continue
			}
			metric := uint64(route.Metric)
			iif, err := route.InterfaceLUID.IPInterface(family)
			if err != nil {
				continue
			}
			metric += uint64(iif.Metric)
			facts = append(facts, fmt.Sprintf("default:%d:%s:%d", route.InterfaceLUID, route.NextHop.Addr(), metric))
			if metric < best {
				best = metric
				selected = adapter.FriendlyName()
			}
		}
		if selected != "" {
			break
		}
	}
	if selected == "" {
		var fallbackIndex uint32 = ^uint32(0)
		for _, adapter := range physical {
			if adapter.OperStatus != winipcfg.IfOperStatusUp {
				continue
			}
			if adapter.IfIndex > 0 && adapter.IfIndex < fallbackIndex {
				fallbackIndex = adapter.IfIndex
				selected = adapter.FriendlyName()
			}
		}
		if selected != "" {
			facts = append(facts, fmt.Sprintf("fallback-underlay:%s:%d", selected, fallbackIndex))
		}
	}
	routes, err := operationalCaptureRoutes(ctx)
	if err != nil {
		return "", "", err
	}
	for _, route := range routes {
		if !route.owned {
			facts = append(facts, fmt.Sprintf("route:%s:%s:%s:%d", route.interfaceIdentity, route.prefix, route.nextHop, route.cost))
		}
	}
	sort.Strings(facts)
	digest := sha256.Sum256([]byte(strings.Join(facts, "\n")))
	return selected, hex.EncodeToString(digest[:]), nil
}

func (s *Session) startPlatformNetworkWatch() {
	_, previous, _ := physicalNetworkSnapshot(s.ctx)
	go func() {
		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-s.ctx.Done():
				return
			case <-ticker.C:
				// Failed reads/refreshes pause egress; they never destroy ingress.
				name, fingerprint, err := physicalNetworkSnapshot(s.ctx)
				s.stateMu.Lock()
				ack, running, pending := s.ack, s.running, s.networkReason != ""
				s.stateMu.Unlock()
				captureBroken := false
				if err == nil && name != "" && running && fingerprint == previous && !pending {
					captureBroken = s.checkPlatformIngress(true) != nil
				}
				pauseReason, refresh, stop, nextFingerprint := decideNetworkWatchTick(
					previous, name, fingerprint, err, running, pending, captureBroken,
				)
				if stop {
					return
				}
				if pauseReason != "" {
					s.mu.Lock()
					s.setNetworkReasonLocked(pauseReason)
					if pauseReason == "expert_underlay_unavailable" && fingerprint != previous {
						s.mux.ResetNetwork()
					}
					s.mu.Unlock()
					previous = nextFingerprint
					continue
				}
				if !refresh {
					continue
				}
				// Repair returning interfaces' new routes before testing capture.
				// Do not require a prior TUN-identity probe: that gate left Expert
				// waiting while Ethernet traffic already bypassed the TUN.
				if s.NetworkChangedToAt(ack.InstanceID, ack.InterfaceID, ack.Revision, name) == nil {
					previous = fingerprint
				}
			}
		}
	}()
}
