package tun

import (
	"errors"
	"strings"

	"github.com/sagernet/sing-tun/internal/winipcfg"
	"github.com/sagernet/sing-tun/internal/wintun"
	"golang.org/x/sys/windows"
)

func lernetOpenGuardedAdapter(options Options) (*wintun.Adapter, error) {
	expected := options.LerNETGuardedAdapter
	if expected == nil || expected.TunName != options.Name || expected.BeforeClose == nil || expected.AfterClose == nil {
		return nil, errors.New("guarded_adapter_invalid")
	}
	adapter, err := wintun.OpenAdapter(options.Name)
	if err != nil {
		return nil, errors.New("guarded_adapter_identity_changed")
	}
	luid := winipcfg.LUID(adapter.LUID())
	row, rowErr := luid.Interface()
	guid, guidErr := luid.GUID()
	expectedGUID, parseErr := windows.GUIDFromString("{" + strings.Trim(expected.GUID, "{}") + "}")
	if uint64(luid) != expected.LUID || rowErr != nil || guidErr != nil || parseErr != nil || row.InterfaceIndex != expected.IfIndex || *guid != expectedGUID {
		adapter.Close()
		return nil, errors.New("guarded_adapter_identity_changed")
	}
	return adapter, nil
}
