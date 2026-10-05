package tun

// This value is supplied by the authenticated desktop control boundary, never
// by an imported sing-box profile. The guardian owns the creator handle while
// NativeTun opens only existing adapter metadata.
type LerNETGuardedAdapter struct {
	PID         uint32       `json:"pid"`
	StartedAtMs int64        `json:"started_at_ms"`
	TunName     string       `json:"tun_name"`
	LUID        uint64       `json:"luid"`
	IfIndex     uint32       `json:"if_index"`
	GUID        string       `json:"guid"`
	BeforeClose func() error `json:"-"`
	AfterClose  func() error `json:"-"`
}

func lernetCloseInOrder(guard *LerNETGuardedAdapter, closeNative func() error) error {
	if guard != nil {
		if err := guard.BeforeClose(); err != nil {
			return err
		}
	}
	if err := closeNative(); err != nil {
		return err
	}
	if guard != nil {
		return guard.AfterClose()
	}
	return nil
}
