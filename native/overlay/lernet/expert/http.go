package expert

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	tun "github.com/sagernet/sing-tun"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type ControlServer struct {
	ctx       context.Context
	token     string
	mu        sync.Mutex
	operation sync.Mutex
	session   *Session
}

func NewControlServer(ctx context.Context, token string) *ControlServer {
	return &ControlServer{ctx: ctx, token: token}
}

type controlRequest struct {
	InstanceID        string                    `json:"instance_id,omitempty"`
	InterfaceID       string                    `json:"interface_id,omitempty"`
	ExpectedRevision  int64                     `json:"expected_revision,omitempty"`
	Revision          int64                     `json:"revision,omitempty"`
	Ingress           json.RawMessage           `json:"ingress,omitempty"`
	Policy            json.RawMessage           `json:"policy,omitempty"`
	Exits             json.RawMessage           `json:"exits,omitempty"`
	Tag               string                    `json:"tag,omitempty"`
	URL               string                    `json:"url,omitempty"`
	TimeoutMs         int64                     `json:"timeout_ms,omitempty"`
	UnderlayInterface string                    `json:"underlay_interface,omitempty"`
	GuardedAdapter    *tun.LerNETGuardedAdapter `json:"guarded_adapter,omitempty"`
}

func (s *ControlServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil || !net.ParseIP(host).IsLoopback() || r.Header.Get("Origin") != "" ||
		subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+s.token)) != 1 {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	if r.Method == http.MethodGet && r.URL.Path == "/lernet/v1/capabilities" {
		io.WriteString(w, Capabilities())
		return
	}
	s.mu.Lock()
	session := s.session
	s.mu.Unlock()
	if r.Method == http.MethodGet && r.URL.Path == "/lernet/v1/status" {
		if session == nil {
			io.WriteString(w, `{"running":false}`)
		} else {
			io.WriteString(w, session.Status())
		}
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"unknown_operation"}`, http.StatusNotFound)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 22*1024*1024)
	defer r.Body.Close()
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var req controlRequest
	if err = decoder.Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid_request"}`, http.StatusBadRequest)
		return
	}
	if decoder.Decode(new(any)) != io.EOF {
		http.Error(w, `{"error":"invalid_request_trailing_data"}`, http.StatusBadRequest)
		return
	}
	if r.URL.Path == "/lernet/v1/start" || r.URL.Path == "/lernet/v1/apply" {
		s.operation.Lock()
		defer s.operation.Unlock()
	}
	s.mu.Lock()
	session = s.session
	s.mu.Unlock()
	var result string
	switch r.URL.Path {
	case "/lernet/v1/start":
		if session != nil {
			err = ErrConflict
			break
		}
		var startContext context.Context
		startContext, err = guardedAdapterContext(s.ctx, req.GuardedAdapter)
		if err != nil {
			break
		}
		session, err = New(startContext, string(req.Ingress))
		if err != nil {
			err = errors.Join(err, finalizeGuardedAdapter(startContext))
			break
		}
		// Publish the session pointer before Start so shutdown can cancel a long
		// preparation immediately without waiting for the control operation lock.
		s.mu.Lock()
		s.session = session
		s.mu.Unlock()
		var ack Ack
		ack, err = session.Start(r.Context(), req.Revision, string(req.Policy), string(req.Exits))
		if err != nil {
			cleanupErr := session.Close()
			s.mu.Lock()
			if s.session == session && cleanupErr == nil {
				s.session = nil
			}
			s.mu.Unlock()
		} else {
			result = Encode(ack)
		}
	case "/lernet/v1/apply":
		if session == nil {
			err = ErrStopped
			break
		}
		var ack Ack
		ack, err = session.Apply(r.Context(), req.InstanceID, req.InterfaceID, req.ExpectedRevision, req.Revision, string(req.Policy), string(req.Exits))
		if err == nil {
			result = Encode(ack)
		}
	case "/lernet/v1/stop":
		if session == nil {
			result = `{"running":false}`
			break
		}
		err = session.CloseAt(req.InstanceID, req.InterfaceID, req.ExpectedRevision)
		if err == nil {
			s.mu.Lock()
			if s.session == session {
				s.session = nil
			}
			s.mu.Unlock()
			result = `{"running":false}`
		}
	case "/lernet/v1/wake", "/lernet/v1/sleep", "/lernet/v1/probe", "/lernet/v1/recover", "/lernet/v1/network_changed":
		if session == nil {
			err = ErrStopped
			break
		}
		switch r.URL.Path {
		case "/lernet/v1/wake":
			err = session.WakeExitAt(r.Context(), req.InstanceID, req.InterfaceID, req.ExpectedRevision, req.Tag)
			result = `{"ok":true}`
		case "/lernet/v1/sleep":
			err = session.SleepExitAt(req.InstanceID, req.InterfaceID, req.ExpectedRevision, req.Tag)
			result = `{"ok":true}`
		case "/lernet/v1/recover":
			err = session.RecoverExitAt(r.Context(), req.InstanceID, req.InterfaceID, req.ExpectedRevision, req.Tag)
			result = `{"ok":true}`
		case "/lernet/v1/probe":
			var probe ProbeResult
			probe, err = session.ProbeExitAt(r.Context(), req.InstanceID, req.InterfaceID, req.ExpectedRevision, req.Tag, req.URL, req.TimeoutMs)
			result = Encode(probe)
		case "/lernet/v1/network_changed":
			err = session.NetworkChangedToAt(req.InstanceID, req.InterfaceID, req.ExpectedRevision, req.UnderlayInterface)
			result = `{"ok":true}`
		}
	default:
		http.Error(w, `{"error":"unknown_operation"}`, http.StatusNotFound)
		return
	}
	if err != nil {
		code := http.StatusUnprocessableEntity
		if errors.Is(err, ErrConflict) {
			code = http.StatusConflict
		}
		if errors.Is(err, ErrStopped) {
			code = http.StatusGone
		}
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			code = http.StatusRequestTimeout
		}
		body := map[string]any{"error": controlError(err)}
		var detail *tun.LerNETCaptureRouteUpdateError
		if errors.As(err, &detail) {
			body["route_update_stage"] = detail.Stage
			body["win32_code"] = detail.Code
		}
		http.Error(w, Encode(body), code)
		return
	}
	io.WriteString(w, result)
}

func ErrorCode(err error) string {
	if err == nil {
		return ""
	}
	if errors.Is(err, context.Canceled) {
		return "operation_cancelled"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "operation_timeout"
	}
	if errors.Is(err, ErrStopped) {
		return "expert_session_stopped"
	}
	if errors.Is(err, ErrConflict) {
		return "expert_revision_or_identity_conflict"
	}
	if errors.Is(err, ErrSleeping) {
		return "exit_sleeping"
	}
	if errors.Is(err, ErrBusy) {
		return "exit_pending_flow_limit"
	}
	if errors.Is(err, ErrExitNetworkChanged) {
		return "exit_network_changed"
	}
	if errors.Is(err, ErrGenerationCleanup) {
		return "generation_cleanup_unconfirmed"
	}
	if errors.Is(err, ErrExitCleanup) {
		return "exit_stop_failed"
	}
	if errors.Is(err, ErrClosePending) {
		return "expert_cleanup_pending"
	}
	// No character-based sanitiser is safe: provider errors can contain a
	// lowercase password, share token or host. Only our finite protocol codes
	// cross either the HTTP or gomobile boundary.
	switch err.Error() {
	case "system_route_ipv4_unavailable", "system_route_ipv6_unavailable", "system_route_manager_unavailable", "system_route_snapshot_failed", "system_route_bind_failed", "ingress_not_ready", "ingress_ready_timeout":
		return err.Error()
	case "expert_underlay_unavailable", "expert_route_snapshot_failed", "expert_route_capture_collision", "expert_route_capture_not_proven", "tun_identity_changed", "expert_underlay_binding_required", "expert_underlay_binding_invalid", "expert_underlay_must_be_physical", "expert_ingress_capture_exclusions_unsupported", "expert_connected_network_snapshot_failed", "expert_capture_route_update_failed", "expert_requires_gvisor_stack", "expert_elevation_required":
		return err.Error()
	case "system_route_unavailable", "system_route_changed", "system_route_destination_invalid", "system_route_platform_unsupported", "guarded_adapter_invalid", "guarded_adapter_platform_unsupported", "guarded_adapter_identity_changed", "guardian_control_unavailable", "guardian_control_invalid_response", "guardian_control_rejected", "guardian_control_timeout":
		return err.Error()
	case "invalid_unknown_owner_dns_guard", "invalid_health_settings":
		return err.Error()
	case "interface_binding_invalid", "interface_binding_conflict", "interface_binding_unavailable", "interface_binding_identity_changed", "interface_binding_owned_ingress", "interface_binding_platform_unsupported":
		return err.Error()
	case "invalid_ingress_configuration", "ingress_must_own_exactly_one_tun", "ingress_prepare_failed", "policy_size_limit", "invalid_exit_manifest", "missing_direct_tag", "invalid_exit_tag", "invalid_exit_mode", "invalid_folder_tag_or_candidates", "invalid_folder_selection", "invalid_folder_limits", "invalid_folder_candidate", "preferred_exit_not_in_folder", "invalid_policy_configuration", "policy_generation_cannot_own_ingress_or_listeners", "missing_direct_outbound", "manifest_exit_not_in_policy", "manifest_folder_not_in_policy", "policy_prepare_failed", "manifest_direct_tag_not_direct", "ingress_start_failed", "tun_identity_unavailable", "policy_start_failed", "unknown_exit_tag", "exit_has_active_flows", "folder_has_no_healthy_exit", "network_changed_during_selection":
		return err.Error()
	default:
		if cause := errors.Unwrap(err); cause != nil {
			return ErrorCode(cause)
		}
		return "native_operation_failed"
	}
}

func controlError(err error) string { return ErrorCode(err) }

func (s *ControlServer) Close() error {
	s.mu.Lock()
	session := s.session
	s.mu.Unlock()
	if session != nil {
		return session.Close()
	}
	return nil
}

func ListenControl(ctx context.Context, address, token string, ready func(int)) error {
	if err := requireExpertPrivilege(); err != nil {
		return err
	}
	if err := initializeLibraries(); err != nil {
		return err
	}
	host, _, err := net.SplitHostPort(address)
	if err != nil || host != "127.0.0.1" || strings.Contains(token, "\n") || len(token) < 32 {
		return errors.New("invalid_loopback_control_configuration")
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return err
	}
	control := NewControlServer(ctx, token)
	server := &http.Server{Handler: control, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 4096, BaseContext: func(net.Listener) context.Context { return ctx }}
	portText := strings.TrimPrefix(listener.Addr().String(), "127.0.0.1:")
	port, err := strconv.Atoi(portText)
	if err != nil {
		listener.Close()
		return err
	}
	ready(port)
	go func() { <-ctx.Done(); _ = control.Close(); _ = server.Close() }()
	err = server.Serve(listener)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}
