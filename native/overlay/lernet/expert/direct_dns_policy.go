package expert

import (
	"encoding/json"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	R "github.com/sagernet/sing-box/route/rule"
)

type dnsMatch uint8

const (
	dnsNo dnsMatch = iota
	dnsUnknown
	dnsYes
)

type directDNSRule struct {
	match  func(string) dnsMatch
	direct bool
}

// This is a proof of Direct routing, not a second traffic router. Only domain
// predicates can be known before DNS replies. All other predicates stay unknown;
// every possible first matching terminal action must be unprotected Direct.
type directDNSPolicy struct {
	rules       []directDNSRule
	finalDirect bool
	servers     map[string]bool
}

func compileDirectDNSPolicy(raw, directTag string) *directDNSPolicy {
	var root struct {
		Outbounds []map[string]json.RawMessage `json:"outbounds"`
		Route     struct {
			Final string                       `json:"final"`
			Guard string                       `json:"lernet_owner_guard"`
			Rules []map[string]json.RawMessage `json:"rules"`
		} `json:"route"`
		DNS struct {
			Servers []map[string]json.RawMessage `json:"servers"`
		} `json:"dns"`
	}
	if json.Unmarshal([]byte(raw), &root) != nil || root.Route.Guard != "" {
		return nil
	}
	verified := false
	for _, outbound := range root.Outbounds {
		if jsonString(outbound["tag"]) != directTag {
			continue
		}
		verified = jsonString(outbound["type"]) == "direct"
		for key, value := range outbound {
			if key != "type" && key != "tag" && !(key == "lernet_system_route" && string(value) == "true") {
				verified = false
			}
		}
	}
	if !verified {
		return nil
	}
	plan := &directDNSPolicy{finalDirect: root.Route.Final == directTag, servers: make(map[string]bool)}
	for _, server := range root.DNS.Servers {
		// Adapt only the original-network or explicit Direct resolver. A protected
		// resolver, predefined answer and proxy DNS transport keep their semantics.
		kind := jsonString(server["type"])
		safe := kind == "local" || (kind == "udp" && string(server["lernet_system_route"]) == "true")
		for key := range server {
			if key != "type" && key != "tag" && key != "lernet_preserve_destination" &&
				!(kind == "udp" && (key == "server" || key == "server_port" || key == "lernet_system_route")) {
				safe = false
			}
		}
		if safe {
			plan.servers[jsonString(server["tag"])] = true
		}
	}
	for _, rule := range root.Route.Rules {
		action := jsonString(rule["action"])
		if action == "sniff" {
			if len(rule) > 2 || (len(rule) == 2 && rule["timeout"] == nil) {
				return nil
			}
			continue
		}
		if action == "hijack-dns" {
			if len(rule) != 2 || (string(rule["port"]) != "53" && jsonString(rule["protocol"]) != "dns") {
				return nil
			}
			continue
		}
		condition := make(map[string]json.RawMessage)
		safe := action == "route" && jsonString(rule["outbound"]) == directTag && string(rule["lernet_protected"]) != "true"
		for key, value := range rule {
			switch key {
			case "action", "outbound", "lernet_protected", "lernet_node_ids", "lernet_fallback":
			case "override_address", "override_port":
				safe = false
			default:
				condition[key] = value
				if !knownDNSProofCondition(key) {
					safe = false
				}
			}
		}
		plan.rules = append(plan.rules, directDNSRule{match: domainDNSPredicate(condition, 0), direct: safe})
	}
	return plan
}

func (p *directDNSPolicy) direct(name, server string) bool {
	if p == nil || !p.servers[server] || name == "" {
		return false
	}
	name = strings.ToLower(strings.TrimSuffix(name, "."))
	for _, rule := range p.rules {
		match := rule.match(name)
		if match == dnsNo {
			continue
		}
		if !rule.direct {
			return false
		}
		if match == dnsYes {
			return true
		}
	}
	return p.finalDirect
}

func knownDNSProofCondition(key string) bool {
	switch key {
	case "type", "mode", "rules", "invert", "domain", "domain_suffix", "domain_keyword", "domain_regex",
		"ip_cidr", "ip_is_private", "rule_set", "ip_version", "network", "protocol", "port", "port_range",
		"source_ip_cidr", "source_ip_is_private", "source_port", "source_port_range", "process_name",
		"process_path", "process_path_regex", "package_name", "package_name_regex", "inbound", "auth_user", "user", "user_id":
		return true
	}
	return false
}

func domainDNSPredicate(raw map[string]json.RawMessage, depth int) func(string) dnsMatch {
	unknown := func(string) dnsMatch { return dnsUnknown }
	if depth > 16 {
		return unknown
	}
	var invert bool
	if raw["invert"] != nil && json.Unmarshal(raw["invert"], &invert) != nil {
		return unknown
	}
	wrap := func(test func(string) dnsMatch) func(string) dnsMatch {
		return func(name string) dnsMatch {
			value := test(name)
			if invert && value != dnsUnknown {
				return dnsYes - value
			}
			return value
		}
	}
	if jsonString(raw["type"]) == "logical" {
		for key := range raw {
			if key != "type" && key != "mode" && key != "rules" && key != "invert" {
				return unknown
			}
		}
		mode := jsonString(raw["mode"])
		var children []map[string]json.RawMessage
		if (mode != "and" && mode != "or") || json.Unmarshal(raw["rules"], &children) != nil || len(children) == 0 {
			return unknown
		}
		tests := make([]func(string) dnsMatch, len(children))
		for i, child := range children {
			tests[i] = domainDNSPredicate(child, depth+1)
		}
		return wrap(func(name string) dnsMatch {
			result := dnsYes
			if mode == "or" {
				result = dnsNo
			}
			for _, test := range tests {
				value := test(name)
				if mode == "and" && value == dnsNo {
					return dnsNo
				}
				if mode == "or" && value == dnsYes {
					return dnsYes
				}
				if value == dnsUnknown {
					result = dnsUnknown
				}
			}
			return result
		})
	}
	// Do not split mixed native leaf predicates into an assumed AND. Native
	// destination address items have grouped OR semantics. Keeping the whole
	// leaf unknown is conservative for both current and future rule-set content.
	for key := range raw {
		if key != "invert" && key != "domain" && key != "domain_suffix" && key != "domain_keyword" && key != "domain_regex" {
			return unknown
		}
	}
	values := make(map[string][]string)
	for _, key := range []string{"domain", "domain_suffix", "domain_keyword", "domain_regex"} {
		if raw[key] == nil {
			continue
		}
		var list []string
		if json.Unmarshal(raw[key], &list) != nil {
			var single string
			if json.Unmarshal(raw[key], &single) != nil {
				return unknown
			}
			list = []string{single}
		}
		values[key] = list
	}
	var items []R.RuleItem
	if len(values["domain"])+len(values["domain_suffix"]) > 0 {
		item, err := R.NewDomainItem(values["domain"], values["domain_suffix"])
		if err != nil {
			return unknown
		}
		items = append(items, item)
	}
	if len(values["domain_keyword"]) > 0 {
		items = append(items, R.NewDomainKeywordItem(values["domain_keyword"]))
	}
	if len(values["domain_regex"]) > 0 {
		item, err := R.NewDomainRegexItem(values["domain_regex"])
		if err != nil {
			return unknown
		}
		items = append(items, item)
	}
	return wrap(func(name string) dnsMatch {
		if len(items) == 0 {
			return dnsYes
		}
		metadata := adapter.InboundContext{Domain: name}
		for _, item := range items {
			if item.Match(&metadata) {
				return dnsYes
			}
		}
		return dnsNo
	})
}
