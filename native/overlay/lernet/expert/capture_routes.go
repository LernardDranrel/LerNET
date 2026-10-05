package expert

import "net/netip"

// Two child prefixes win longest-prefix selection against the connected parent
// subnet, independently of its interface metric. Local/loopback/scoped traffic
// cannot be captured by an ordinary routed TUN.
func splitCapturePrefix(prefix netip.Prefix) []netip.Prefix {
	if !prefix.IsValid() || prefix.Bits() < 2 || prefix.Bits() >= prefix.Addr().BitLen() {
		return nil
	}
	address := prefix.Addr()
	if !address.IsGlobalUnicast() || address.IsLoopback() || address.IsLinkLocalUnicast() {
		return nil
	}
	prefix = prefix.Masked()
	bit := prefix.Bits()
	if address.Is4() {
		bytes := prefix.Addr().As4()
		bytes[bit/8] |= 1 << uint(7-bit%8)
		return []netip.Prefix{netip.PrefixFrom(prefix.Addr(), bit+1), netip.PrefixFrom(netip.AddrFrom4(bytes), bit+1)}
	}
	bytes := prefix.Addr().As16()
	bytes[bit/8] |= 1 << uint(7-bit%8)
	return []netip.Prefix{netip.PrefixFrom(prefix.Addr(), bit+1), netip.PrefixFrom(netip.AddrFrom16(bytes), bit+1)}
}
