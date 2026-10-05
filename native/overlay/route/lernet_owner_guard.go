package route

import "github.com/sagernet/sing-box/adapter"

// DNS auto-hijack is an early native fast path. Apply the same conservative
// owner guard there as the ordinary route rules, before any query leaves.
func (r *Router) LerNETOwnerUnknown(metadata *adapter.InboundContext) bool {
	switch r.lernetOwnerGuard {
	case "":
		return false
	case "package":
		return metadata.ProcessInfo == nil || len(metadata.ProcessInfo.AndroidPackageNames) == 0
	case "process":
		return metadata.ProcessInfo == nil || metadata.ProcessInfo.ProcessPath == ""
	default:
		return true
	}
}

// Generated DNS policies can project protected app conditions onto system DNS
// requests, whose netd owner has no package name. This exception is DNS-only;
// the compiler's unknown-owner catchall must reject any unprotected query.
func (r *Router) LerNETOwnerUnknownDNS(metadata *adapter.InboundContext) bool {
	if r.lernetOwnerGuard == "package" && r.lernetUnknownOwnerDNSSafe {
		return false
	}
	return r.LerNETOwnerUnknown(metadata)
}
