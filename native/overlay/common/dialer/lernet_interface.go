package dialer

import "github.com/sagernet/sing/common/control"

// Private per-outbound context. No unrelated resolver inherits this binding.
type lernetVerifiedBinding struct {
	control          control.Func
	destinationAware bool
}
