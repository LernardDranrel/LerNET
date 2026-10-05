package expert

import (
	"strings"
	"testing"
)

func TestLerNETUnknownDNSPrefixRejectsUnprotectedFallthrough(t *testing.T) {
	manifest := Manifest{DirectTag: "direct", Exits: []ExitOptions{{Tag: "protected"}}}
	safe := `{"dns":{"servers":[{"type":"udp","tag":"safe","server":"1.1.1.1","detour":"protected"}],"rules":[{"type":"logical","mode":"and","rules":[{"package_name_regex":[".+"],"invert":true},{"domain_suffix":["company.example"]}],"action":"route","server":"safe"},{"package_name_regex":[".+"],"invert":true,"action":"reject"},{"action":"route","server":"bootstrap"}]}}`
	if err := validateUnknownOwnerDNS(safe, manifest); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{
		strings.Replace(safe, `"detour":"protected"`, `"detour":"direct"`, 1),
		strings.Replace(safe, `"package_name_regex":[".+"],"invert":true,"action":"reject"`, `"domain_suffix":["only.example"],"package_name_regex":[".+"],"invert":true,"action":"reject"`, 1),
		strings.Replace(safe, `"mode":"and"`, `"mode":"or"`, 1),
		strings.Replace(safe, `"action":"reject"`, `"action":"route"`, 1),
	} {
		if validateUnknownOwnerDNS(bad, manifest) == nil {
			t.Fatal("unsafe DNS prefix accepted")
		}
	}
}
