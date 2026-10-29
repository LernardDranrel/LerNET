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
