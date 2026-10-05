package expert

import (
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

const ProtocolVersion = 1

var ErrStopped = errors.New("expert_session_stopped")
var ErrConflict = errors.New("expert_revision_or_identity_conflict")
var ErrSleeping = errors.New("exit_sleeping")
var ErrBusy = errors.New("exit_pending_flow_limit")
var ErrClosePending = errors.New("expert_cleanup_pending")
var ErrExitCleanup = errors.New("exit_stop_failed")
var ErrExitNetworkChanged = errors.New("exit_network_changed")
var ErrGenerationCleanup = errors.New("generation_cleanup_unconfirmed")

type Ack struct {
	InstanceID     string `json:"instance_id"`
	InterfaceID    string `json:"interface_id"`
	Revision       int64  `json:"revision"`
	InterfaceIndex int    `json:"if_index"`
}

type Manifest struct {
	DirectTag string          `json:"direct_tag"`
	ProbeURL  string          `json:"probe_url,omitempty"`
	Exits     []ExitOptions   `json:"exits"`
	Folders   []FolderOptions `json:"folders,omitempty"`
	Health    *HealthOptions  `json:"health,omitempty"`
}

type HealthOptions struct {
	ProbeMinIntervalMs         int64 `json:"probe_min_interval_ms"`
	ProbeMaxIntervalMs         int64 `json:"probe_max_interval_ms"`
	ActiveProbeTimeoutMs       int64 `json:"active_probe_timeout_ms"`
	FailedChecksBeforeRecovery int   `json:"failed_checks_before_recovery"`
}

func defaultHealthOptions() HealthOptions { return HealthOptions{3000, 7000, 4000, 2} }

func (m Manifest) healthOptions() HealthOptions {
	if m.Health == nil {
		return defaultHealthOptions()
	}
	return *m.Health
}

func (h HealthOptions) valid() bool {
	return h.ProbeMinIntervalMs >= 1000 && h.ProbeMaxIntervalMs >= h.ProbeMinIntervalMs &&
		h.ProbeMaxIntervalMs <= 60000 && h.ActiveProbeTimeoutMs >= 1000 &&
		h.ActiveProbeTimeoutMs <= 15000 && h.FailedChecksBeforeRecovery >= 1 && h.FailedChecksBeforeRecovery <= 10
}

type ExitOptions struct {
	Tag                string `json:"tag"`
	Mode               string `json:"mode"`
	IdleTimeoutMs      int64  `json:"idle_timeout_ms"`
	FirstFlowTimeoutMs int64  `json:"first_flow_timeout_ms"`
	StartupTimeoutMs   int64  `json:"startup_timeout_ms"`
	MaxPendingFlows    int    `json:"max_pending_flows"`
}

func (m Manifest) Validate() error {
	if m.Health != nil && !m.Health.valid() {
		return errors.New("invalid_health_settings")
	}
	if m.DirectTag == "" {
		return errors.New("missing_direct_tag")
	}
	seen := make(map[string]bool)
	exitSeen := make(map[string]bool)
	for _, x := range m.Exits {
		if x.Tag == "" || x.Tag == m.DirectTag || seen[x.Tag] {
			return errors.New("invalid_exit_tag")
		}
		seen[x.Tag] = true
		exitSeen[x.Tag] = true
		if x.Mode != "warm" && x.Mode != "cold" {
			return errors.New("invalid_exit_mode")
		}
		if x.IdleTimeoutMs < 1000 || x.IdleTimeoutMs > int64((7*24*time.Hour)/time.Millisecond) ||
			x.FirstFlowTimeoutMs < 1000 || x.FirstFlowTimeoutMs > 45000 || x.StartupTimeoutMs < 1000 ||
			x.StartupTimeoutMs > 45000 || x.MaxPendingFlows < 1 || x.MaxPendingFlows > 1000 {
			return fmt.Errorf("invalid_exit_limits: %s", x.Tag)
		}
	}
	for _, f := range m.Folders {
		if f.Tag == "" || f.Tag == m.DirectTag || seen[f.Tag] || len(f.CandidateTags) == 0 || len(f.CandidateTags) > 64 {
			return errors.New("invalid_folder_tag_or_candidates")
		}
		seen[f.Tag] = true
		if f.Selection != "preferred" && f.Selection != "fastest" {
			return errors.New("invalid_folder_selection")
		}
		if f.ProbeTimeoutMs < 1000 || f.ProbeTimeoutMs > 45000 || f.FirstFlowTimeoutMs < 1000 || f.FirstFlowTimeoutMs > 45000 || f.HealthTTLms < 1000 || f.HealthTTLms > 300000 || f.CooldownMs < 0 || f.CooldownMs > 300000 || f.MaxPendingFlows < 1 || f.MaxPendingFlows > 1000 {
			return errors.New("invalid_folder_limits")
		}
		if f.ActiveProbeTimeoutMs != 0 && (f.ActiveProbeTimeoutMs < 1000 || f.ActiveProbeTimeoutMs > 45000) {
			return errors.New("invalid_folder_limits")
		}
		if (f.ProbeMinIntervalMs == 0) != (f.ProbeMaxIntervalMs == 0) || f.ProbeMinIntervalMs != 0 && (f.ProbeMinIntervalMs < 1000 || f.ProbeMaxIntervalMs < f.ProbeMinIntervalMs || f.ProbeMaxIntervalMs > 300000) {
			return errors.New("invalid_folder_limits")
		}
		if f.FailedChecksBeforeRecovery < 0 || f.FailedChecksBeforeRecovery > 10 {
			return errors.New("invalid_folder_limits")
		}
		candidateSeen := make(map[string]bool)
		for _, tag := range f.CandidateTags {
			if !exitSeen[tag] || candidateSeen[tag] || tag == f.Tag {
				return errors.New("invalid_folder_candidate")
			}
			candidateSeen[tag] = true
		}
		if f.PreferredTag != "" && !candidateSeen[f.PreferredTag] {
			return errors.New("preferred_exit_not_in_folder")
		}
	}
	return nil
}

type FolderOptions struct {
	Tag                        string   `json:"tag"`
	CandidateTags              []string `json:"candidate_tags"`
	PreferredTag               string   `json:"preferred_tag,omitempty"`
	AutoSwap                   bool     `json:"auto_swap"`
	Selection                  string   `json:"selection"`
	ProbeURL                   string   `json:"probe_url,omitempty"`
	ProbeTimeoutMs             int64    `json:"probe_timeout_ms"`
	CooldownMs                 int64    `json:"cooldown_ms"`
	HealthTTLms                int64    `json:"health_ttl_ms"`
	MaxPendingFlows            int      `json:"max_pending_flows"`
	FirstFlowTimeoutMs         int64    `json:"first_flow_timeout_ms"`
	ActiveProbeTimeoutMs       int64    `json:"active_probe_timeout_ms,omitempty"`
	ProbeMinIntervalMs         int64    `json:"probe_min_interval_ms,omitempty"`
	ProbeMaxIntervalMs         int64    `json:"probe_max_interval_ms,omitempty"`
	FailedChecksBeforeRecovery int      `json:"failed_checks_before_recovery,omitempty"`
}

func Encode(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return string(b)
}

func Capabilities() string {
	return `{"protocol_version":1,"hot_policy_apply":true,"preserves_tun":true,"independent_exit_lifecycle":true,"atomic_prepare_commit":true,"bounded_first_flow_wait":true,"destination_redirect":true,"native_health_recovery":true,"platform_crash_guard":false}`
}

type ExitStatus struct {
	Tag            string `json:"tag"`
	Phase          string `json:"phase"`
	PendingFlows   int    `json:"pending_flows"`
	ActiveFlows    int    `json:"active_flows"`
	LastActivityMs int64  `json:"last_activity_ms"`
	Reason         string `json:"reason,omitempty"`
	DrainingFlows  int    `json:"draining_flows,omitempty"`
	LatencyMs      *int64 `json:"latency_ms,omitempty"`
	LastCheckMs    int64  `json:"last_check_ms,omitempty"`
	Failures       int    `json:"failures"`
	Health         string `json:"health"`
}

type ProbeResult struct {
	HTTPSLatencyMs      *int64 `json:"https_latency_ms,omitempty"`
	Reason              string `json:"reason,omitempty"`
	transportGeneration int64
}

type RetiredCleanupStatus struct {
	Revision int64    `json:"revision"`
	Reason   string   `json:"reason"`
	ExitTags []string `json:"exit_tags"`
}
