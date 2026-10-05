package expert

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/control"
	J "github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

type Session struct {
	mu                   sync.Mutex
	stateMu              sync.Mutex
	ctx                  context.Context
	cancel               context.CancelFunc
	ingress              *box.Box
	mux                  *switchRouter
	ack                  Ack
	running              bool
	closed               bool
	closeErr             error
	closeConfirmed       bool
	stopReason           string
	ingressCloseErr      error
	ingressCloseFinished bool
	initial              *generation
	flows                *flowHistory
	underlay             atomic.Pointer[net.Interface]
	baseTun              option.TunInboundOptions
	ingressIdentity      *dialer.LerNETIngressIdentityHolder
	capturePrefixes      []netip.Prefix
}

func New(ctx context.Context, ingressJSON string) (*Session, error) {
	ctx, cancel := context.WithCancel(ctx)
	holder := &dialer.LerNETIngressIdentityHolder{}
	ctx = service.ContextWith[*dialer.LerNETIngressIdentityHolder](service.ExtendContext(ctx), holder)
	s := &Session{ctx: ctx, cancel: cancel, flows: newFlowHistory(500), ingressIdentity: holder}
	if err := s.initializePlatformIdentity(); err != nil {
		cancel()
		return nil, err
	}
	var nonce [16]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		cancel()
		return nil, err
	}
	s.ack.InstanceID = hex.EncodeToString(nonce[:])
	options, err := J.UnmarshalExtendedContext[option.Options](ctx, []byte(ingressJSON))
	if err != nil {
		cancel()
		return nil, errors.New("invalid_ingress_configuration")
	}
	if len(options.Inbounds) != 1 || options.Inbounds[0].Type != "tun" || len(options.Endpoints) > 0 || len(options.Services) > 0 || options.Experimental != nil || options.NTP != nil {
		cancel()
		return nil, errors.New("ingress_must_own_exactly_one_tun")
	}
	if tunOptions, ok := options.Inbounds[0].Options.(*option.TunInboundOptions); ok {
		s.baseTun = *tunOptions
		if tunOptions.Stack != "gvisor" {
			cancel()
			return nil, errors.New("expert_requires_gvisor_stack")
		}
		if tunOptions.UDPNATMax == 0 || tunOptions.UDPNATMax > 256 {
			tunOptions.UDPNATMax = 256
		}
	}
	if err = preparePlatformIngress(ctx, &options); err != nil {
		cancel()
		return nil, err
	}
	if tunOptions, ok := options.Inbounds[0].Options.(*option.TunInboundOptions); ok {
		s.capturePrefixes = append([]netip.Prefix(nil), tunOptions.RouteAddress...)
	}
	if err = validateGuardedIngress(ctx, &options); err != nil {
		cancel()
		return nil, err
	}
	if runtime.GOOS == "windows" {
		underlay, _ := net.InterfaceByName(options.Route.DefaultInterface)
		s.underlay.Store(underlay)
	}
	s.ingress, err = box.New(box.Options{Options: options, Context: service.ExtendContext(ctx),
		RouterFactory: func(router adapter.Router) adapter.Router {
			s.mux = &switchRouter{Router: router, dnsIngress: &dnsIngressRegistry{}}
			return s.mux
		},
		BeforeInboundStart: func() error {
			if s.initial == nil {
				return errors.New("missing_initial_policy")
			}
			prepared := s.initial
			// prepare already built/validated the box; its Start runs after the
			// ingress underlay is ready and before the TUN inbound is started.
			if err := prepared.start(); err != nil {
				return err
			}
			return s.mux.publish(s.ctx, prepared)
		},
	})
	if err != nil {
		cancel()
		return nil, errors.New("ingress_prepare_failed")
	}
	return s, nil
}

// borrowedNetwork owns no monitor, routes, fd, or platform callbacks. Its
// policy-specific DNS defaults resolve through that generation's DNS registry.
type borrowedNetwork struct {
	adapter.NetworkManager
	defaults        adapter.NetworkOptions
	underlay        *atomic.Pointer[net.Interface]
	ingressIdentity *dialer.LerNETIngressIdentityHolder
}

func (n *borrowedNetwork) LerNETIngressIdentity() *dialer.LerNETIngressIdentity {
	return n.ingressIdentity.Load()
}

func (n *borrowedNetwork) Start(adapter.StartStage) error { return nil }
func (n *borrowedNetwork) Close() error                   { return nil }
func (n *borrowedNetwork) Initialize([]adapter.RuleSet)   {}
func (n *borrowedNetwork) DefaultOptions() adapter.NetworkOptions {
	defaults := n.defaults
	if n.underlay != nil {
		defaults.BindInterface = ""
	}
	return defaults
}
func (n *borrowedNetwork) AutoDetectInterface() bool {
	if n.underlay != nil {
		return true
	}
	// Android rejects the desktop auto_detect_interface configuration switch.
	// The platform still supplies VpnService.protect for every outbound socket.
	if runtime.GOOS == "android" && n.NetworkManager.ProtectFunc() != nil {
		return true
	}
	return n.NetworkManager.AutoDetectInterface()
}
func (n *borrowedNetwork) AutoDetectInterfaceFunc() control.Func {
	if n.underlay == nil {
		return n.NetworkManager.AutoDetectInterfaceFunc()
	}
	return control.BindToInterfaceFunc(n.InterfaceFinder(), func(network, address string) (string, int, error) {
		current := n.underlay.Load()
		if current == nil {
			return "", -1, errors.New("expert_underlay_unavailable")
		}
		return current.Name, current.Index, nil
	})
}

func (s *Session) prepare(revision int64, policyJSON, manifestJSON string) (*generation, error) {
	if err := s.mux.retirementError(); err != nil {
		return nil, err
	}
	generationCtx, generationCancel := context.WithCancel(s.ctx)
	retained := false
	defer func() {
		if !retained {
			generationCancel()
		}
	}()
	if len(policyJSON) > 20*1024*1024 || len(manifestJSON) > 1024*1024 {
		return nil, errors.New("policy_size_limit")
	}
	var manifest Manifest
	if err := json.Unmarshal([]byte(manifestJSON), &manifest); err != nil {
		return nil, errors.New("invalid_exit_manifest")
	}
	if err := manifest.Validate(); err != nil {
		return nil, err
	}
	registry := &gateRegistry{OutboundRegistry: service.FromContext[adapter.OutboundRegistry](s.ctx), manifest: manifest}
	ctx := service.ContextWith[adapter.OutboundRegistry](service.ExtendContext(generationCtx), registry)
	ctx = service.ContextWith[adapter.LerNETDNSIngressTracker](ctx, &generationDNSIngress{registry: s.mux.dnsIngress, revision: revision})
	ctx = service.ContextWith[option.OutboundOptionsRegistry](ctx, registry)
	options, err := J.UnmarshalExtendedContext[option.Options](ctx, []byte(policyJSON))
	if err != nil {
		return nil, errors.New("invalid_policy_configuration")
	}
	if len(options.Inbounds) > 0 || len(options.Endpoints) > 0 || len(options.Services) > 0 || options.Experimental != nil || options.NTP != nil || len(options.NetworkNamespaces) > 0 || len(options.HTTPClients) > 0 {
		return nil, errors.New("policy_generation_cannot_own_ingress_or_listeners")
	}
	if options.Route != nil && options.Route.LerNETUnknownOwnerDNSSafe {
		if options.Route.LerNETOwnerGuard != "package" {
			return nil, errors.New("invalid_unknown_owner_dns_guard")
		}
		if err = validateUnknownOwnerDNS(policyJSON, manifest); err != nil {
			return nil, err
		}
	}
	known := make(map[string]bool)
	for _, out := range options.Outbounds {
		known[out.Tag] = true
	}
	if !known[manifest.DirectTag] {
		return nil, errors.New("missing_direct_outbound")
	}
	for _, exit := range manifest.Exits {
		if !known[exit.Tag] {
			return nil, errors.New("manifest_exit_not_in_policy")
		}
	}
	for _, folder := range manifest.Folders {
		if !known[folder.Tag] {
			return nil, errors.New("manifest_folder_not_in_policy")
		}
	}
	defaults := s.ingress.Network().DefaultOptions()
	if options.Route != nil && options.Route.DefaultDomainResolver != nil {
		defaults.DomainResolver = options.Route.DefaultDomainResolver.Server
	}
	borrowed := &borrowedNetwork{NetworkManager: s.ingress.Network(), defaults: defaults, ingressIdentity: s.ingressIdentity}
	if runtime.GOOS == "windows" {
		borrowed.underlay = &s.underlay
	}
	prepared, err := box.New(box.Options{Options: options, Context: ctx, ExistingNetwork: borrowed})
	if err != nil {
		return nil, errors.New("policy_prepare_failed")
	}
	direct, exists := prepared.Outbound().Outbound(manifest.DirectTag)
	if !exists || direct.Type() != "direct" {
		_ = prepared.Close()
		return nil, errors.New("manifest_direct_tag_not_direct")
	}
	registry.mu.Lock()
	for _, gate := range registry.gates {
		gate.direct = direct
	}
	for _, folder := range registry.folders {
		folder.direct = direct
	}
	registry.mu.Unlock()
	prepared.Router().AppendTracker(&historyTracker{history: s.flows, revision: revision})
	g := &generation{revision: revision, router: prepared.Router(), registry: registry, close: prepared.Close, start: prepared.Start, cancel: generationCancel}
	retained = true
	return g, nil
}

func (s *Session) Start(ctx context.Context, revision int64, policyJSON, manifestJSON string) (Ack, error) {
	ctx, cancel := s.operationContext(ctx)
	defer cancel()
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.running || revision < 0 {
		return Ack{}, ErrConflict
	}
	if err := s.ctx.Err(); err != nil {
		return Ack{}, err
	}
	g, err := s.prepare(revision, policyJSON, manifestJSON)
	if err != nil {
		return Ack{}, err
	}
	if err = ctx.Err(); err != nil {
		_ = s.mux.discard(g)
		return Ack{}, err
	}
	s.initial = g
	if err = s.ingress.Start(); err != nil {
		s.initial = nil
		_ = s.mux.discard(g)
		_ = s.closeIngress()
		s.stateMu.Lock()
		s.closed = true
		s.stateMu.Unlock()
		s.cancel()
		return Ack{}, errors.New("ingress_start_failed")
	}
	if err = s.ctx.Err(); err == nil {
		err = ctx.Err()
	}
	if err != nil {
		s.mux.stop()
		_ = s.closeIngress()
		s.stateMu.Lock()
		s.closed = true
		s.stateMu.Unlock()
		s.cancel()
		return Ack{}, err
	}
	var identity string
	for _, in := range s.ingress.Inbound().Inbounds() {
		if owned, ok := in.(interface{ LerNETInterfaceIdentity() string }); ok {
			identity = owned.LerNETInterfaceIdentity()
		}
	}
	parts := strings.Split(identity, ":")
	validIdentity := len(parts) == 3
	if validIdentity {
		index, indexErr := strconv.Atoi(parts[1])
		fd, fdErr := strconv.Atoi(parts[2])
		validIdentity = indexErr == nil && fdErr == nil && (index > 0 || fd > 0)
	}
	if !validIdentity {
		s.mux.stop()
		_ = s.closeIngress()
		s.stateMu.Lock()
		s.closed = true
		s.stateMu.Unlock()
		s.cancel()
		return Ack{}, errors.New("tun_identity_unavailable")
	}
	if err = s.verifyPlatformCapture(identity); err != nil {
		s.cancel()
		s.mux.stop()
		_ = s.closeIngress()
		s.stateMu.Lock()
		s.closed = true
		s.stateMu.Unlock()
		return Ack{}, err
	}
	s.stateMu.Lock()
	if err = s.ctx.Err(); err == nil {
		err = ctx.Err()
	}
	if err != nil {
		s.closed = true
		s.stateMu.Unlock()
		s.cancel()
		s.mux.stop()
		_ = s.closeIngress()
		return Ack{}, err
	}
	s.ack.InterfaceID, s.ack.Revision, s.running = identity, revision, true
	s.ack.InterfaceIndex, _ = strconv.Atoi(parts[1])
	s.stateMu.Unlock()
	s.initial = nil
	go s.idleLoop()
	s.startPlatformNetworkWatch()
	return s.ack, nil
}

func (s *Session) Apply(ctx context.Context, instanceID, interfaceID string, expectedRevision, nextRevision int64, policyJSON, manifestJSON string) (Ack, error) {
	ctx, cancel := s.operationContext(ctx)
	defer cancel()
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || !s.running || s.ack.InstanceID != instanceID || s.ack.InterfaceID != interfaceID || s.ack.Revision != expectedRevision || nextRevision <= expectedRevision {
		return Ack{}, ErrConflict
	}
	g, err := s.prepare(nextRevision, policyJSON, manifestJSON)
	if err != nil {
		return Ack{}, err
	}
	if err = g.start(); err != nil {
		_ = s.mux.discard(g)
		return Ack{}, errors.New("policy_start_failed")
	}
	// A canceled caller is checked at the publication gate. Query Status after
	// an ambiguous transport error; no caller may assume it implies rollback.
	if err = ctx.Err(); err != nil {
		_ = s.mux.discard(g)
		return Ack{}, err
	}
	if err = s.ctx.Err(); err != nil {
		_ = s.mux.discard(g)
		return Ack{}, err
	}
	s.stateMu.Lock()
	if err = s.ctx.Err(); err != nil {
		s.stateMu.Unlock()
		_ = s.mux.discard(g)
		return Ack{}, err
	}
	if err = s.mux.publish(ctx, g); err != nil {
		s.stateMu.Unlock()
		_ = s.mux.discard(g)
		return Ack{}, err
	}
	s.ack.Revision = nextRevision
	s.stateMu.Unlock()
	s.flows.enforce(g.router)
	return s.ack, nil
}

func (s *Session) Close() error {
	s.stateMu.Lock()
	s.cancel()
	s.running = false
	s.stateMu.Unlock()
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return s.refreshCleanupProofLocked()
	}
	s.stateMu.Lock()
	s.closed, s.running = true, false
	s.stateMu.Unlock()
	s.mux.stop()
	return s.closeIngress()
}

// The pinned Box remembers its completed close result, including automatic
// cleanup during failed Start. A repeated call cannot manufacture drain proof.
// Caller holds s.mu; status readers use the publication/state fence.
func (s *Session) closeIngress() error {
	_ = s.mux.stop()
	if !s.ingressCloseFinished {
		err := s.ingress.Close()
		if err == nil {
			err = finalizeGuardedAdapter(s.ctx)
		}
		s.ingressCloseErr, s.ingressCloseFinished = err, true
	}
	return s.refreshCleanupProofLocked()
}

func (s *Session) refreshCleanupProofLocked() error {
	if !s.ingressCloseFinished {
		return ErrClosePending
	}
	err := errors.Join(s.ingressCloseErr, s.mux.stop())
	s.stateMu.Lock()
	s.closeErr = err
	s.closeConfirmed = err == nil
	s.stateMu.Unlock()
	return err
}

func (s *Session) CloseAt(instanceID, interfaceID string, revision int64) error {
	s.stateMu.Lock()
	if (!s.running && !s.closed) || s.ack.InstanceID != instanceID || s.ack.InterfaceID != interfaceID || s.ack.Revision != revision {
		s.stateMu.Unlock()
		return ErrConflict
	}
	// Cancellation and the identity fence are indivisible with publication.
	s.cancel()
	s.running = false
	s.stateMu.Unlock()
	return s.Close()
}

func (s *Session) currentGateAt(tag, instance, interfaceID string, revision int64) (*exitGate, func(), error) {
	s.stateMu.Lock()
	if instance != "" && (!s.running || s.ack.InstanceID != instance || s.ack.InterfaceID != interfaceID || s.ack.Revision != revision) {
		s.stateMu.Unlock()
		return nil, nil, ErrConflict
	}
	g, release, err := s.mux.acquire()
	s.stateMu.Unlock()
	if err != nil {
		return nil, nil, err
	}
	gate, err := g.registry.gate(tag)
	if err != nil {
		release()
		return nil, nil, err
	}
	return gate, release, nil
}
func (s *Session) currentGate(tag string) (*exitGate, func(), error) {
	return s.currentGateAt(tag, "", "", 0)
}

func (s *Session) operationContext(ctx context.Context) (context.Context, func()) {
	op, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(s.ctx, cancel)
	if s.ctx.Err() != nil {
		cancel()
	}
	return op, func() { stop(); cancel() }
}
func (s *Session) WakeExit(ctx context.Context, tag string) error {
	gate, release, err := s.currentGate(tag)
	if err != nil {
		return err
	}
	defer release()
	ctx, cancel := s.operationContext(ctx)
	defer cancel()
	return gate.wake(ctx)
}
func (s *Session) SleepExit(tag string) error {
	gate, release, err := s.currentGate(tag)
	if err != nil {
		return err
	}
	defer release()
	return gate.sleep(false)
}
func (s *Session) RecoverExit(ctx context.Context, tag string) error {
	gate, release, err := s.currentGate(tag)
	if err != nil {
		return err
	}
	defer release()
	ctx, cancel := s.operationContext(ctx)
	defer cancel()
	return gate.recover(ctx)
}
func (s *Session) WakeExitAt(ctx context.Context, instance, interfaceID string, revision int64, tag string) error {
	gate, release, err := s.currentGateAt(tag, instance, interfaceID, revision)
	if err != nil {
		return err
	}
	defer release()
	ctx, cancel := s.operationContext(ctx)
	defer cancel()
	return gate.wake(ctx)
}
func (s *Session) SleepExitAt(instance, interfaceID string, revision int64, tag string) error {
	gate, release, err := s.currentGateAt(tag, instance, interfaceID, revision)
	if err != nil {
		return err
	}
	defer release()
	return gate.sleep(false)
}
func (s *Session) RecoverExitAt(ctx context.Context, instance, interfaceID string, revision int64, tag string) error {
	gate, release, err := s.currentGateAt(tag, instance, interfaceID, revision)
	if err != nil {
		return err
	}
	defer release()
	ctx, cancel := s.operationContext(ctx)
	defer cancel()
	return gate.recover(ctx)
}
func (s *Session) ProbeExitAt(ctx context.Context, instance, interfaceID string, revision int64, tag, address string, timeoutMs int64) (ProbeResult, error) {
	gate, release, err := s.currentGateAt(tag, instance, interfaceID, revision)
	if err != nil {
		return ProbeResult{}, err
	}
	defer release()
	ctx, cancel := s.operationContext(ctx)
	defer cancel()
	return probeGate(ctx, gate, address, timeoutMs), nil
}

func (s *Session) Matches(instanceID, interfaceID string, revision int64) bool {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	return s.running && s.ack.InstanceID == instanceID && s.ack.InterfaceID == interfaceID && s.ack.Revision == revision
}

func (s *Session) NetworkChanged() { s.mux.ResetNetwork() }

func (s *Session) NetworkChangedToAt(instanceID, interfaceID string, revision int64, underlay string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.Matches(instanceID, interfaceID, revision) {
		return ErrConflict
	}
	if err := s.ctx.Err(); err != nil {
		return err
	}
	if err := s.refreshPlatformUnderlay(underlay); err != nil {
		s.stateMu.Lock()
		s.stopReason = ErrorCode(err)
		s.stateMu.Unlock()
		s.failIngressLocked()
		return err
	}
	s.mux.ResetNetwork()
	return nil
}

// Caller owns s.mu. An unproved ingress cannot remain advertised as running;
// sticky cleanup errors still prevent a false descriptor-drained acknowledgment.
func (s *Session) failIngressLocked() {
	s.cancel()
	s.mux.stop()
	s.stateMu.Lock()
	s.running = false
	s.closed = true
	s.stateMu.Unlock()
	_ = s.closeIngress()
}

func (s *Session) Status() string {
	// A canceled cold provider may complete cleanup after the initial close
	// returned pending. Only real completed worker cleanup can confirm drain.
	s.mu.Lock()
	if s.closed && s.ingressCloseFinished {
		_ = s.refreshCleanupProofLocked()
	}
	s.mu.Unlock()
	s.stateMu.Lock()
	ack, running, closeConfirmed, stopReason, cleanupReason := s.ack, s.running, s.closeConfirmed, s.stopReason, ErrorCode(s.closeErr)
	var exits []ExitStatus
	var folders []any
	var retirementFailures []RetiredCleanupStatus
	current, generations, release := s.mux.snapshot()
	s.stateMu.Unlock()
	defer release()
	byTag := make(map[string]ExitStatus)
	for _, g := range generations {
		if g != current {
			continue
		}
		g.registry.mu.Lock()
		for _, gate := range g.registry.gates {
			state := gate.status()
			byTag[state.Tag] = state
		}
		for _, folder := range g.registry.folders {
			folders = append(folders, folder.status())
		}
		g.registry.mu.Unlock()
	}
	for _, g := range generations {
		if g == current {
			continue
		}
		cleanup := g.cleanupState()
		g.registry.mu.Lock()
		if cleanup != nil {
			reason := "generation_stop_failed"
			if errors.Is(cleanup, ErrClosePending) {
				reason = "expert_cleanup_pending"
			}
			if errors.Is(cleanup, ErrExitCleanup) {
				reason = "exit_stop_failed"
			}
			tags := make([]string, 0, len(g.registry.gates))
			for tag := range g.registry.gates {
				tags = append(tags, tag)
			}
			sort.Strings(tags)
			retirementFailures = append(retirementFailures, RetiredCleanupStatus{g.revision, reason, tags})
		}
		for _, gate := range g.registry.gates {
			old := gate.status()
			if old.ActiveFlows == 0 && old.PendingFlows == 0 && cleanup == nil {
				continue
			}
			state, exists := byTag[old.Tag]
			if !exists {
				state = ExitStatus{Tag: old.Tag, Phase: "draining"}
			}
			if cleanup != nil && !exists {
				state.Reason = ErrorCode(cleanup)
				if !exists {
					state.Phase = "failed"
				}
			}
			state.ActiveFlows += old.ActiveFlows
			state.PendingFlows += old.PendingFlows
			state.DrainingFlows += old.ActiveFlows
			if old.LastActivityMs > state.LastActivityMs {
				state.LastActivityMs = old.LastActivityMs
			}
			byTag[old.Tag] = state
		}
		g.registry.mu.Unlock()
	}
	for _, state := range byTag {
		exits = append(exits, state)
	}
	sort.Slice(exits, func(i, j int) bool { return exits[i].Tag < exits[j].Tag })
	sort.Slice(retirementFailures, func(i, j int) bool { return retirementFailures[i].Revision < retirementFailures[j].Revision })
	underlayName := ""
	if underlay := s.underlay.Load(); underlay != nil {
		underlayName = underlay.Name
	}
	return Encode(struct {
		Ack
		Running                bool                   `json:"running"`
		CloseConfirmed         bool                   `json:"close_confirmed"`
		CleanupReason          string                 `json:"cleanup_reason,omitempty"`
		StopReason             string                 `json:"stop_reason,omitempty"`
		RetiredCleanupFailures []RetiredCleanupStatus `json:"retired_cleanup_failures"`
		Exits                  []ExitStatus           `json:"exits"`
		Folders                []any                  `json:"folders"`
		Flows                  []Flow                 `json:"flows"`
		UnderlayInterface      string                 `json:"underlay_interface,omitempty"`
		NetworkEpoch           int64                  `json:"network_epoch"`
	}{ack, running, closeConfirmed, cleanupReason, stopReason, retirementFailures, exits, folders, s.flows.snapshot(), underlayName, s.mux.networkEpoch.Load()})
}

func (s *Session) ProbeExit(ctx context.Context, tag, address string, timeoutMs int64) ProbeResult {
	gate, release, err := s.currentGate(tag)
	if err != nil {
		return ProbeResult{Reason: "exit_unavailable"}
	}
	defer release()
	ctx, cancel := s.operationContext(ctx)
	defer cancel()
	return probeGate(ctx, gate, address, timeoutMs)
}

func probeGate(ctx context.Context, gate adapter.Outbound, address string, timeoutMs int64) (result ProbeResult) {
	u, err := url.Parse(address)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || timeoutMs < 1000 || timeoutMs > 45000 {
		return ProbeResult{Reason: "invalid_probe_request"}
	}
	if native, ok := gate.(*exitGate); ok {
		native.mu.Lock()
		epoch := native.networkEpoch
		transportGeneration := native.transportGeneration
		native.mu.Unlock()
		defer func() {
			result.transportGeneration = transportGeneration
			if result.Reason != "exit_sleeping" && result.Reason != "operation_cancelled" && !native.recordHealth(result, epoch, transportGeneration) {
				result = ProbeResult{Reason: "exit_transport_changed"}
			}
		}()
	}
	ctx, cancel := context.WithTimeout(context.WithValue(ctx, probeContextKey{}, true), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	transport := &http.Transport{Proxy: nil, DisableKeepAlives: true, DialContext: func(dialCtx context.Context, network, address string) (net.Conn, error) {
		// net/http detaches the dial from the request's deadline to reuse pooled
		// connections. A health probe owns a single connection and must retain
		// its actual active/candidate deadline through native provider setup.
		deadline, _ := ctx.Deadline()
		dialCtx, dialCancel := context.WithDeadline(dialCtx, deadline)
		stopProbe := context.AfterFunc(ctx, dialCancel)
		if ctx.Err() != nil {
			dialCancel()
		}
		conn, dialErr := gate.DialContext(dialCtx, network, M.ParseSocksaddr(address))
		if dialErr != nil {
			stopProbe()
			dialCancel()
			return nil, dialErr
		}
		return &probeDialConn{Conn: conn, cancel: dialCancel, stopProbe: stopProbe}, nil
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	req, err := http.NewRequestWithContext(ctx, "GET", address, nil)
	if err != nil {
		return ProbeResult{Reason: "invalid_probe_request"}
	}
	start := time.Now()
	response, err := client.Do(req)
	if err != nil {
		if code := ErrorCode(err); strings.HasPrefix(code, "interface_binding_") {
			return ProbeResult{Reason: code}
		}
		if errors.Is(err, ErrSleeping) {
			return ProbeResult{Reason: "exit_sleeping"}
		}
		if errors.Is(ctx.Err(), context.Canceled) {
			return ProbeResult{Reason: "operation_cancelled"}
		}
		return ProbeResult{Reason: "https_probe_failed"}
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 400 {
		return ProbeResult{Reason: fmt.Sprintf("https_status_%d", response.StatusCode)}
	}
	elapsed := time.Since(start).Milliseconds()
	if elapsed < 1 {
		elapsed = 1
	}
	return ProbeResult{HTTPSLatencyMs: &elapsed}
}

func (s *Session) idleLoop() {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.mux.mu.Lock()
			g := s.mux.current
			s.mux.mu.Unlock()
			if g == nil {
				continue
			}
			g.registry.mu.Lock()
			gates := make([]*exitGate, 0, len(g.registry.gates))
			for _, gate := range g.registry.gates {
				gates = append(gates, gate)
			}
			g.registry.mu.Unlock()
			selectedFolderTags := make(map[string]bool)
			g.registry.mu.Lock()
			for _, folder := range g.registry.folders {
				folder.mu.Lock()
				if folder.selected != "" {
					selectedFolderTags[folder.selected] = true
				}
				folder.mu.Unlock()
			}
			g.registry.mu.Unlock()
			for _, gate := range gates {
				// A slow idle close may wait on protocol cleanup. It must not
				// delay unrelated exits' health checks or folder failover.
				go func(gate *exitGate) { _ = gate.sleep(true) }(gate)
				if !selectedFolderTags[gate.tag] {
					gate.checkHealth()
				}
			}
			g.registry.mu.Lock()
			folders := make([]*folderGate, 0, len(g.registry.folders))
			for _, folder := range g.registry.folders {
				folders = append(folders, folder)
			}
			g.registry.mu.Unlock()
			for _, folder := range folders {
				folder.checkHealth()
			}
		}
	}
}
