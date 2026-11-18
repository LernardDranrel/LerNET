package expert

// decideNetworkWatchTick chooses the Windows Expert network-watcher action.
//
// Underlay loss pauses admission without destroying TUN. When a physical underlay
// returns, refresh must run even if a prior TUN-identity probe failed — gating
// recovery on identity left Expert red/waiting while OS traffic already bypassed
// the TUN after Ethernet came back.
func decideNetworkWatchTick(
	previousFingerprint, underlayName, fingerprint string,
	snapshotErr error,
	running, pending, captureBroken bool,
) (pauseReason string, refresh bool, stop bool, nextFingerprint string) {
	if snapshotErr != nil {
		return "expert_route_snapshot_failed", false, false, previousFingerprint
	}
	if underlayName == "" {
		return "expert_underlay_unavailable", false, false, fingerprint
	}
	if !running {
		return "", false, true, previousFingerprint
	}
	if fingerprint == previousFingerprint && !pending && !captureBroken {
		return "", false, false, previousFingerprint
	}
	return "", true, false, previousFingerprint
}

// ifaceMark is the physical underlay the watcher can name without a friendly
// name, SSID or hardware address: IANA type, interface index, link up.
type ifaceMark struct {
	Index uint32
	Kind  string
	Up    bool
}

func (m ifaceMark) token() string {
	if m.Index == 0 || m.Kind == "" || m.Kind == "none" {
		return "none"
	}
	avail := "down"
	if m.Up {
		avail = "up"
	}
	return m.Kind + ":" + itoa(int(m.Index)) + ":" + avail
}

// ifaceKind maps an IANA ifType. Unknown non-zero types stay "other".
func ifaceKind(ifType uint32) string {
	switch ifType {
	case 6:
		return "ethernet"
	case 71:
		return "wifi"
	case 243, 244:
		return "cellular"
	default:
		if ifType == 0 {
			return "none"
		}
		return "other"
	}
}

// ifaceMove is empty when the named interface did not change.
// appear, lost, switch and avail are the only other results.
func ifaceMove(prev, next ifaceMark) string {
	if prev.token() == next.token() {
		return ""
	}
	switch {
	case prev.token() == "none":
		return "appear"
	case next.token() == "none":
		return "lost"
	case prev.Index != next.Index || prev.Kind != next.Kind:
		return "switch"
	default:
		return "avail"
	}
}

// refreshWhy names the fact that made a running watcher refresh.
// Call it only when decideNetworkWatchTick already returned refresh.
func refreshWhy(previousFingerprint, fingerprint string, pending, captureBroken bool) string {
	if previousFingerprint != fingerprint {
		return "fingerprint"
	}
	if captureBroken {
		return "capture"
	}
	if pending {
		return "pending"
	}
	return "unchanged"
}
