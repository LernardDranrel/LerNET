package expert

import (
	"encoding/json"
	"errors"
)

// A DNS-only owner exception is admitted only with a compiler safety prefix.
// Every unresolved-owner query must terminate at a protected exit, a local
// answer, or the exact reject catchall before reaching ordinary DNS policies.
func validateUnknownOwnerDNS(policy string, manifest Manifest) error {
	invalid := func() error { return errors.New("invalid_unknown_owner_dns_guard") }
	var root struct {
		DNS struct {
			Servers []map[string]json.RawMessage `json:"servers"`
			Rules   []map[string]json.RawMessage `json:"rules"`
		} `json:"dns"`
	}
	if json.Unmarshal([]byte(policy), &root) != nil {
		return invalid()
	}
	protectedTags := make(map[string]bool)
	for _, exit := range manifest.Exits {
		protectedTags[exit.Tag] = true
	}
	for _, folder := range manifest.Folders {
		protectedTags[folder.Tag] = true
	}
	safeServers := make(map[string]bool)
	for _, server := range root.DNS.Servers {
		tag, detour, kind := jsonString(server["tag"]), jsonString(server["detour"]), jsonString(server["type"])
		switch kind {
		case "udp", "tcp", "tls", "https", "quic", "h3":
			if protectedTags[detour] {
				safeServers[tag] = true
			}
		}
	}
	for _, rule := range root.DNS.Rules {
		if unknownPackageDNSPredicate(rule) && len(rule) == 3 && jsonString(rule["action"]) == "reject" {
			return nil
		}
		if jsonString(rule["type"]) != "logical" || jsonString(rule["mode"]) != "and" || jsonBool(rule["invert"]) {
			return invalid()
		}
		var children []map[string]json.RawMessage
		if json.Unmarshal(rule["rules"], &children) != nil || len(children) < 1 || len(children) > 2 || len(children[0]) != 2 || !unknownPackageDNSPredicate(children[0]) {
			return invalid()
		}
		if server := jsonString(rule["server"]); server != "" && !safeServers[server] {
			return invalid()
		}
		switch jsonString(rule["action"]) {
		case "reject", "predefined", "respond", "route-options":
		case "route", "evaluate":
			if !safeServers[jsonString(rule["server"])] {
				return invalid()
			}
		default:
			return invalid()
		}
	}
	return invalid()
}

func unknownPackageDNSPredicate(rule map[string]json.RawMessage) bool {
	if !jsonBool(rule["invert"]) {
		return false
	}
	var patterns []string
	if json.Unmarshal(rule["package_name_regex"], &patterns) != nil {
		return false
	}
	return len(patterns) == 1 && patterns[0] == ".+"
}
func jsonString(raw json.RawMessage) string {
	var value string
	_ = json.Unmarshal(raw, &value)
	return value
}
func jsonBool(raw json.RawMessage) bool {
	var value bool
	_ = json.Unmarshal(raw, &value)
	return value
}
