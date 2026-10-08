package expert

import (
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	"sync"
	"time"
)

type directFamilySnapshot struct {
	policy    *directDNSPolicy
	mu        sync.Mutex
	manager   adapter.NetworkManager
	epoch     func() int64
	lastEpoch int64
	at        time.Time
	value     dialer.LerNETDirectFamilies
}

func (d *directFamilySnapshot) snapshot() dialer.LerNETDirectFamilies {
	d.mu.Lock()
	defer d.mu.Unlock()
	epoch := d.epoch()
	if d.at.IsZero() || epoch != d.lastEpoch || time.Since(d.at) >= 250*time.Millisecond {
		d.value = dialer.LerNETDirectFamilySnapshot(d.manager)
		d.at, d.lastEpoch = time.Now(), epoch
	}
	return d.value
}

func (d *directFamilySnapshot) UnavailableIPv6(domain, server string) bool {
	return d.policy.direct(domain, server) && d.snapshot().IPv6 == "unavailable"
}
