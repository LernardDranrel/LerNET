package expert

import (
	"errors"
	"testing"
)

func TestNetworkWatchRecoversWhenUnderlayReturnsWhilePending(t *testing.T) {
	// Regression: Expert stayed red after Ethernet plug-in because the watcher
	// required a successful TUN-identity probe before calling NetworkChangedToAt.
	// Pending underlay loss must refresh as soon as a physical underlay exists.
	pause, refresh, stop, next := decideNetworkWatchTick(
		"down-fp", "Ethernet", "up-fp", nil,
		true, true, false,
	)
	if pause != "" || stop || !refresh || next != "down-fp" {
		t.Fatalf("pause=%q refresh=%v stop=%v next=%q", pause, refresh, stop, next)
	}
}

func TestNetworkWatchPausesWithoutUnderlay(t *testing.T) {
	pause, refresh, stop, next := decideNetworkWatchTick(
		"up-fp", "", "down-fp", nil,
		true, false, false,
	)
	if pause != "expert_underlay_unavailable" || refresh || stop || next != "down-fp" {
		t.Fatalf("pause=%q refresh=%v stop=%v next=%q", pause, refresh, stop, next)
	}
}

func TestNetworkWatchRefreshWhenCaptureBrokenEvenIfFingerprintStable(t *testing.T) {
	pause, refresh, stop, _ := decideNetworkWatchTick(
		"same", "Ethernet", "same", nil,
		true, false, true,
	)
	if pause != "" || stop || !refresh {
		t.Fatalf("pause=%q refresh=%v stop=%v", pause, refresh, stop)
	}
}

func TestNetworkWatchSkipsHealthyStableUnderlay(t *testing.T) {
	pause, refresh, stop, _ := decideNetworkWatchTick(
		"same", "Ethernet", "same", nil,
		true, false, false,
	)
	if pause != "" || refresh || stop {
		t.Fatalf("pause=%q refresh=%v stop=%v", pause, refresh, stop)
	}
}

func TestNetworkWatchSnapshotErrorPauses(t *testing.T) {
	pause, refresh, stop, next := decideNetworkWatchTick(
		"fp", "Ethernet", "fp2", errors.New("boom"),
		true, false, false,
	)
	if pause != "expert_route_snapshot_failed" || refresh || stop || next != "fp" {
		t.Fatalf("pause=%q refresh=%v stop=%v next=%q", pause, refresh, stop, next)
	}
}

func TestNetworkWatchStopsWhenNotRunning(t *testing.T) {
	pause, refresh, stop, _ := decideNetworkWatchTick(
		"fp", "Ethernet", "fp2", nil,
		false, true, false,
	)
	if pause != "" || refresh || !stop {
		t.Fatalf("pause=%q refresh=%v stop=%v", pause, refresh, stop)
	}
}
