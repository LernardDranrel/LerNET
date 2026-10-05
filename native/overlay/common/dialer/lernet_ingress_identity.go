package dialer

import "sync/atomic"

// The holder is shared by prepared generations. The actual interface identity
// becomes usable before the start acknowledgment; aliases are not ownership.
type LerNETIngressIdentity struct {
	LUID  uint64
	Index uint32
	GUID  string
}
type LerNETIngressIdentityHolder struct {
	current atomic.Pointer[LerNETIngressIdentity]
}

func (h *LerNETIngressIdentityHolder) Load() *LerNETIngressIdentity { return h.current.Load() }
func (h *LerNETIngressIdentityHolder) Store(identity *LerNETIngressIdentity) {
	h.current.Store(identity)
}
func (i *LerNETIngressIdentity) Matches(luid uint64, index uint32, guid string) bool {
	return i != nil && i.LUID == luid && i.Index == index && i.GUID == guid
}
