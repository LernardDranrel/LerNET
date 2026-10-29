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

// UnavailableIPv6 reports whether AAAA answers should become NODATA.
// When the local IPv6 path is known missing, every selected resolver adapts —
// not only dns-direct. Expert projects non-protected ELSE→proxy as a DNS
// catch-all onto the profile/tunnel resolver, so geoip→Direct names (Yandex)
// often never hit dns-direct; AAAA from that tunnel DNS still feed a later
// Direct dial on the broken underlay. Domain Direct proof stays analysis-only.
func (d *directFamilySnapshot) UnavailableIPv6(domain, server string) bool {
	_ = domain
	_ = server
	if d.policy == nil {
		return false
	}
	return d.snapshot().IPv6 == "unavailable"
}
