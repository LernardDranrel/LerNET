package dialer

import (
	"github.com/sagernet/sing-box/option"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
	"net"
	"testing"
)

func TestLerNETCorporateInterfaceRejectsReplacementDownAndOwned(t *testing.T) {
	guid, _ := windows.GUIDFromString("{8431FC90-0B91-4A00-9901-260ABFBA1171}")
	binding := option.LerNETInterfaceOptions{GUID: guid.String(), Name: "Corporate SSTP", Index: 17}
	actual := net.Interface{Name: binding.Name, Index: binding.Index, Flags: net.FlagUp}
	if err := validateLerNETInterface(binding, guid, actual, guid, winipcfg.IfOperStatusUp, nil); err != nil {
		t.Fatal(err)
	}
	checks := []struct {
		name   string
		mutate func(*net.Interface, *windows.GUID, *winipcfg.IfOperStatus)
		owned  []string
	}{
		{name: "same alias replaced adapter", mutate: func(_ *net.Interface, g *windows.GUID, _ *winipcfg.IfOperStatus) { g.Data1++ }},
		{name: "index reused", mutate: func(i *net.Interface, _ *windows.GUID, _ *winipcfg.IfOperStatus) { i.Index++ }},
		{name: "adapter renamed", mutate: func(i *net.Interface, _ *windows.GUID, _ *winipcfg.IfOperStatus) { i.Name = "other" }},
		{name: "adapter down", mutate: func(i *net.Interface, _ *windows.GUID, _ *winipcfg.IfOperStatus) { i.Flags = 0 }},
		{name: "provider not operational", mutate: func(_ *net.Interface, _ *windows.GUID, s *winipcfg.IfOperStatus) { *s = winipcfg.IfOperStatusDown }},
		{name: "owned TUN alias", owned: []string{binding.Name}},
	}
	for _, check := range checks {
		t.Run(check.name, func(t *testing.T) {
			i, g, s := actual, guid, winipcfg.IfOperStatusUp
			if check.mutate != nil {
				check.mutate(&i, &g, &s)
			}
			if validateLerNETInterface(binding, guid, i, g, s, check.owned) == nil {
				t.Fatal("unsafe interface binding accepted")
			}
		})
	}
}

func TestLerNETInterfaceGUIDAcceptsCanonicalImportAndBracedWindowsForm(t *testing.T) {
	canonical := "8431fc90-0b91-4a00-9901-260abfba1171"
	a, err := parseLerNETInterfaceGUID(canonical)
	if err != nil {
		t.Fatal(err)
	}
	b, err := parseLerNETInterfaceGUID("{" + canonical + "}")
	if err != nil || a != b {
		t.Fatal("cross-platform canonical GUID differs from Windows representation")
	}
	if _, err := parseLerNETInterfaceGUID("some.application.progid"); err == nil {
		t.Fatal("ProgID accepted instead of stable interface identity")
	}
}
