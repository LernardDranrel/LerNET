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

func TestIfaceMoveNamesLossReturnAndSwitch(t *testing.T) {
	ethernet := ifaceMark{Index: 14, Kind: "ethernet", Up: true}
	wifi := ifaceMark{Index: 2, Kind: "wifi", Up: true}
	down := ifaceMark{Index: 14, Kind: "ethernet", Up: false}
	if ifaceMove(ethernet, ifaceMark{}) != "lost" {
		t.Fatal("ethernet disappearance")
	}
	if ifaceMove(ifaceMark{}, ethernet) != "appear" {
		t.Fatal("ethernet return")
	}
	if ifaceMove(wifi, ethernet) != "switch" {
		t.Fatal("wifi to ethernet")
	}
	if ifaceMove(ethernet, down) != "avail" {
		t.Fatal("link down on the same interface")
	}
	if ifaceMove(ethernet, ethernet) != "" {
		t.Fatal("unchanged interface")
	}
	if ethernet.token() != "ethernet:14:up" || (ifaceMark{}).token() != "none" {
		t.Fatal("token")
	}
}

func TestRefreshWhyUsesTheFactThatChanged(t *testing.T) {
	if refreshWhy("old", "new", true, true) != "fingerprint" {
		t.Fatal("fingerprint wins")
	}
	if refreshWhy("same", "same", true, true) != "capture" {
		t.Fatal("capture before pending")
	}
	if refreshWhy("same", "same", true, false) != "pending" {
		t.Fatal("pending")
	}
}

func TestHealthAttemptIsNotSuccessAndSameFallbackIsState(t *testing.T) {
	kind, outcome, code, recover := classifyHealth(false, "https_probe_timeout", 2, 2, true)
	if kind != "edge" || outcome != "attempt" || code != "https_probe_timeout" || !recover {
		t.Fatalf("attempt kind=%s outcome=%s code=%s recover=%v", kind, outcome, code, recover)
	}
	kind, outcome, _, recover = classifyHealth(true, "", 2, 2, true)
	if kind != "state" || outcome != "ok" || recover {
		t.Fatalf("ok kind=%s outcome=%s recover=%v", kind, outcome, recover)
	}
	kind, outcome, _, recover = classifyHealth(false, "https_probe_timeout", 1, 2, true)
	if outcome != "below" || recover {
		t.Fatalf("below outcome=%s recover=%v", outcome, recover)
	}
	if fallbackKind("", "", "block", "protected") != "edge" {
		t.Fatal("first fallback is a change")
	}
	if fallbackKind("block", "protected", "block", "protected") != "state" {
		t.Fatal("same fallback is a repeat")
	}
	if fallbackKind("block", "protected", "direct", "open") != "edge" {
		t.Fatal("changed fallback")
	}
	if probeHost("https://probe.example/health") != "probe.example" || probeHost("not a url") != "" {
		t.Fatal("probe host")
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
