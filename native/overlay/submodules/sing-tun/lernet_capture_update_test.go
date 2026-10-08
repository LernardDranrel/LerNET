package tun

import (
	"errors"
	"net/netip"
	"testing"
)

func TestLerNETCaptureUpdatePreservesUnrelatedAndUnchangedRoutes(t *testing.T) {
	old := netip.MustParsePrefix("0.0.0.0/1")
	added := netip.MustParsePrefix("10.0.0.0/17")
	service := netip.MustParsePrefix("172.19.0.0/30")
	rows := map[netip.Prefix]bool{old: true, service: true}
	err := lernetUpdateCaptureSet([]netip.Prefix{old}, []netip.Prefix{old, added, added},
		func(p netip.Prefix) error { rows[p] = true; return nil },
		func(p netip.Prefix) error { delete(rows, p); return nil })
	if err != nil || !rows[old] || !rows[added] || !rows[service] || len(rows) != 3 {
		t.Fatalf("routes were lost: %v, %v", rows, err)
	}
}

func TestLerNETCaptureUpdateDoesNotDeleteBeforeSuccessfulAdditions(t *testing.T) {
	old := netip.MustParsePrefix("10.0.0.0/17")
	next := netip.MustParsePrefix("10.0.128.0/17")
	failure := errors.New("fake add failure")
	deleted := false
	err := lernetUpdateCaptureSet([]netip.Prefix{old}, []netip.Prefix{next},
		func(netip.Prefix) error { return failure },
		func(netip.Prefix) error { deleted = true; return nil })
	if !errors.Is(err, failure) || deleted {
		t.Fatal("old capture removed before successful replacement")
	}
}

func TestLerNETCaptureUpdateDeletesOnlyObsoleteCapture(t *testing.T) {
	old := netip.MustParsePrefix("10.0.0.0/17")
	next := netip.MustParsePrefix("10.0.128.0/17")
	var operations []string
	err := lernetUpdateCaptureSet([]netip.Prefix{old, old}, []netip.Prefix{next},
		func(netip.Prefix) error { operations = append(operations, "add"); return nil },
		func(p netip.Prefix) error {
			if p != old {
				t.Fatal("unowned route deletion")
			}
			operations = append(operations, "delete")
			return nil
		})
	if err != nil || len(operations) != 2 || operations[0] != "add" || operations[1] != "delete" {
		t.Fatalf("incorrect update order: %v, %v", operations, err)
	}
}
