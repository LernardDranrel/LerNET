package tun

import (
	"net/netip"
)

// Update only the capture set. Never flush OS-created on-link or address routes.
// Install additions before deleting obsolete routes, including unchanged sets.
func lernetUpdateCaptureSet(previous, next []netip.Prefix, add, remove func(netip.Prefix) error) error {
	wanted := make(map[netip.Prefix]bool)
	for _, prefix := range next {
		prefix = prefix.Masked()
		if wanted[prefix] {
			continue
		}
		wanted[prefix] = true
		if err := add(prefix); err != nil {
			return err
		}
	}
	removed := make(map[netip.Prefix]bool)
	for _, prefix := range previous {
		prefix = prefix.Masked()
		if wanted[prefix] || removed[prefix] {
			continue
		}
		removed[prefix] = true
		if err := remove(prefix); err != nil {
			return err
		}
	}
	return nil
}

// Finite stage and numeric OS error are safe diagnostics, unlike raw provider errors.
type LerNETCaptureRouteUpdateError struct {
	Stage string
	Code  uint32
}

func (*LerNETCaptureRouteUpdateError) Error() string { return "expert_capture_route_update_failed" }
