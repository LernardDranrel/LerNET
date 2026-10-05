package tun

import singTun "github.com/sagernet/sing-tun"

func (t *Inbound) lernetRetainOpenedIdentity(opened singTun.Options) {
	t.tunOptions.Name = opened.Name
	// Platform OpenInterface assigns the actual duplicated native descriptor
	// on its options argument. Android may forbid Go interface enumeration.
	t.tunOptions.FileDescriptor = opened.FileDescriptor
}
