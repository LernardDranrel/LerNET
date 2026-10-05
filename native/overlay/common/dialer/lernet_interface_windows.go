package dialer

import (
	"context"
	"errors"
	"net"
	"strings"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/service"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

func LerNETInterfaceContext(ctx context.Context, binding option.LerNETInterfaceOptions) (context.Context, error) {
	manager := service.FromContext[adapter.NetworkManager](ctx)
	guid, err := parseLerNETInterfaceGUID(binding.GUID)
	if err != nil || binding.Name == "" || binding.Index <= 0 || manager == nil {
		return nil, errors.New("interface_binding_invalid")
	}
	validate := func() error {
		actual, err := net.InterfaceByName(binding.Name)
		if err != nil || actual.Index != binding.Index || actual.Flags&net.FlagUp == 0 || actual.Flags&net.FlagLoopback != 0 {
			return errors.New("interface_binding_unavailable")
		}
		luid, err := winipcfg.LUIDFromIndex(uint32(binding.Index))
		if err != nil {
			return errors.New("interface_binding_unavailable")
		}
		actualGUID, err := luid.GUID()
		if err != nil {
			return errors.New("interface_binding_identity_changed")
		}
		row, err := luid.Interface()
		if err != nil || row.Type == winipcfg.IfTypeSoftwareLoopback {
			return errors.New("interface_binding_unavailable")
		}
		if holder := service.FromContext[*LerNETIngressIdentityHolder](ctx); holder != nil && holder.Load().Matches(uint64(luid), uint32(binding.Index), actualGUID.String()) {
			return errors.New("interface_binding_owned_ingress")
		}
		var owned []string
		if monitor := manager.InterfaceMonitor(); monitor != nil {
			owned = monitor.MyInterfaces()
		}
		return validateLerNETInterface(binding, guid, *actual, *actualGUID, row.OperStatus, owned)
	}
	// A disconnected corporate adapter is a flow-level unavailable target. Its
	// immutable definition must not prevent unrelated Expert branches starting.
	bind := control.BindToInterface(manager.InterfaceFinder(), binding.Name, binding.Index)
	guard := func(network, address string, raw syscall.RawConn) error {
		if err := validate(); err != nil {
			return err
		}
		if err := bind(network, address, raw); err != nil {
			return err
		}
		return validate()
	}
	return service.ContextWith[*lernetVerifiedBinding](service.ExtendContext(ctx), &lernetVerifiedBinding{control: guard}), nil
}

func parseLerNETInterfaceGUID(text string) (windows.GUID, error) {
	text = strings.TrimSpace(text)
	if strings.HasPrefix(text, "{") && strings.HasSuffix(text, "}") {
		text = text[1 : len(text)-1]
	}
	if len(text) != 36 {
		return windows.GUID{}, errors.New("interface_binding_invalid")
	}
	return windows.GUIDFromString("{" + text + "}")
}

func validateLerNETInterface(expected option.LerNETInterfaceOptions, expectedGUID windows.GUID, actual net.Interface, actualGUID windows.GUID, status winipcfg.IfOperStatus, owned []string) error {
	if actual.Index != expected.Index || actual.Name != expected.Name || actual.Flags&net.FlagUp == 0 || actual.Flags&net.FlagLoopback != 0 || status != winipcfg.IfOperStatusUp {
		return errors.New("interface_binding_unavailable")
	}
	if actualGUID != expectedGUID {
		return errors.New("interface_binding_identity_changed")
	}
	for _, name := range owned {
		if strings.EqualFold(name, expected.Name) {
			return errors.New("interface_binding_owned_ingress")
		}
	}
	return nil
}
