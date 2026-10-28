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

// UnavailableIPv6 reports whether underlay AAAA answers should become NODATA.
// Gate is the selected resolver: only underlay-safe Direct DNS servers adapt,
// and only when the local IPv6 path is known missing. Domain Direct proof is
// not required — Expert emits ELSE→proxy as an empty catch-all, so geoip→Direct
// is never proven at DNS time, yet dns-direct still must not hand out AAAA on
// a broken Ethernet. Tunnel/protected resolvers keep their AAAA answers.
func (d *directFamilySnapshot) UnavailableIPv6(domain, server string) bool {
	_ = domain
	if d.policy == nil || !d.policy.servers[server] {
		return false
	}
	return d.snapshot().IPv6 == "unavailable"
}
