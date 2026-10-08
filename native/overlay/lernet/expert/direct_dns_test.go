package expert

import (
	"fmt"
	"github.com/sagernet/sing-box/common/dialer"
	"strings"
	"testing"
	"time"
)

func directDNSTestPolicy(rules, final string) string {
	return fmt.Sprintf(`{"outbounds":[{"type":"direct","tag":"direct","lernet_system_route":true},{"type":"socks","tag":"vpn"}],"route":{"final":%q,"rules":[{"action":"sniff","timeout":"300ms"},{"port":53,"action":"hijack-dns"},{"protocol":"dns","action":"hijack-dns"},%s]},"dns":{"servers":[{"type":"local","tag":"dns-direct","lernet_preserve_destination":true},{"type":"udp","tag":"dns-protected","server":"1.1.1.1","detour":"vpn"}]}}`, final, rules)
}

func TestMixedDirectDNSPreservesProxyIPv6(t *testing.T) {
	const rules = `{"rule_set":["geoip-ru"],"action":"route","outbound":"direct"},{"domain_suffix":["ip.me"],"action":"route","outbound":"vpn","lernet_protected":true},{"action":"route","outbound":"direct"}`
	plan := compileDirectDNSPolicy(directDNSTestPolicy(rules, "direct"), "direct")
	for _, name := range []string{"music-lyrics.s3-private.mds.yandex.net.", "s3-private.mds.yandex.net", "music.yandex.ru", "example.org"} {
		if !plan.direct(name, "dns-direct") {
			t.Fatalf("Direct proof missing for %s", name)
		}
		if plan.direct(name, "dns-protected") {
			t.Fatal("protected resolver adapted")
		}
	}
	for _, name := range []string{"ip.me", "www.ip.me.", "WWW.IP.ME."} {
		if plan.direct(name, "dns-direct") {
			t.Fatalf("VPN IPv6 suppressed for %s", name)
		}
	}
	for _, state := range []string{"available", "unknown", "unavailable"} {
		d := &directFamilySnapshot{policy: plan, epoch: func() int64 { return 0 }, at: time.Now(), value: dialer.LerNETDirectFamilies{IPv6: state}}
		if got := d.UnavailableIPv6("music.yandex.ru.", "dns-direct"); got != (state == "unavailable") {
			t.Fatalf("family %s: %v", state, got)
		}
		if d.UnavailableIPv6("ip.me.", "dns-direct") {
			t.Fatal("proxy inherited physical IPv6 limitation")
		}
	}
}

func TestDirectDNSProofKeepsUnknownAndUnsafeBranches(t *testing.T) {
	for _, tc := range []struct {
		name, condition, action string
		want                    bool
	}{
		{"unrelated domain", `"domain":["ip.me"]`, `"action":"route","outbound":"vpn"`, true},
		{"matching domain", `"domain_suffix":["yandex.ru"]`, `"action":"route","outbound":"vpn"`, false},
		{"unknown country direct", `"rule_set":["geoip-ru"]`, `"action":"route","outbound":"direct"`, true},
		{"unknown country VPN", `"rule_set":["geoip-ru"]`, `"action":"route","outbound":"vpn"`, false},
		{"unknown owner VPN", `"process_name":["Other.exe"]`, `"action":"route","outbound":"vpn"`, false},
		{"reject", `"domain_suffix":["yandex.ru"]`, `"action":"reject"`, false},
		{"protected direct", `"domain_suffix":["yandex.ru"]`, `"action":"route","outbound":"direct","lernet_protected":true`, false},
		{"redirect", `"domain_suffix":["yandex.ru"]`, `"action":"route","outbound":"direct","override_address":"ip.me"`, false},
		{"unknown modifier", `"future_option":true`, `"action":"route","outbound":"direct"`, false},
		{"mixed address groups", `"domain":["ip.me"],"ip_cidr":["2001:db8::/32"]`, `"action":"route","outbound":"vpn"`, false},
		{"and with unrelated domain", `"type":"logical","mode":"and","rules":[{"domain":["ip.me"]},{"rule_set":["geoip-ru"]}]`, `"action":"route","outbound":"vpn"`, true},
		{"or with unknown country", `"type":"logical","mode":"or","rules":[{"domain":["ip.me"]},{"rule_set":["geoip-ru"]}]`, `"action":"route","outbound":"vpn"`, false},
		{"inverted domain", `"domain":["ip.me"],"invert":true`, `"action":"route","outbound":"vpn"`, false},
		{"regex", `"domain_regex":["^music\\.yandex\\.ru$"]`, `"action":"route","outbound":"vpn"`, false},
		{"keyword", `"domain_keyword":["yandex"]`, `"action":"route","outbound":"vpn"`, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			rules := "{" + tc.condition + "," + tc.action + `},{"action":"route","outbound":"direct"}`
			plan := compileDirectDNSPolicy(directDNSTestPolicy(rules, "direct"), "direct")
			if plan.direct("music.yandex.ru.", "dns-direct") != tc.want {
				t.Fatal("unsafe or missing Direct proof")
			}
		})
	}
}

func TestDirectDNSProofValidatesOriginalResolverAndFinalRoute(t *testing.T) {
	plain := directDNSTestPolicy(`{"action":"route","outbound":"direct"}`, "direct")
	for _, mutation := range []string{
		strings.Replace(plain, `"lernet_system_route":true`, `"override_address":"ip.me"`, 1),
		strings.Replace(plain, `"final":"direct"`, `"final":"direct","lernet_owner_guard":"windows"`, 1),
		strings.Replace(plain, `"lernet_preserve_destination":true`, `"detour":"vpn"`, 1),
		strings.Replace(strings.Replace(plain, `"action":"route","outbound":"direct"`, `"rule_set":["geoip-ru"],"action":"route","outbound":"direct"`, 1), `"final":"direct"`, `"final":"vpn"`, 1),
	} {
		if compileDirectDNSPolicy(mutation, "direct").direct("music.yandex.ru", "dns-direct") {
			t.Fatal("invalid Direct proof accepted")
		}
	}
	custom := strings.Replace(plain, `"type":"local","tag":"dns-direct","lernet_preserve_destination":true`, `"type":"udp","tag":"dns-direct","server":"192.0.2.53","lernet_system_route":true`, 1)
	if !compileDirectDNSPolicy(custom, "direct").direct("music.yandex.ru", "dns-direct") {
		t.Fatal("intentional Direct DNS lost family adaptation")
	}
}
