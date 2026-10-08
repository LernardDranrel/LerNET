"""Apply the reviewed LerNET overlay to exactly the pinned sing-box-lx tree."""

from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(sys.argv[1]).resolve()
PIN = "8e12ec7db6a77130fcc63b7c545d3a41f895f30a"
actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=SOURCE, text=True).strip()
if actual != PIN:
    raise SystemExit(f"Refusing unknown upstream commit {actual}; expected {PIN}")
PATCH_BUFFERS: dict[str, str] = {}


def replace(relative: str, old: str, new: str) -> None:
    path = SOURCE / relative
    if relative not in PATCH_BUFFERS:
        PATCH_BUFFERS[relative] = subprocess.check_output(
            ["git", "show", f"{PIN}:{relative}"], cwd=SOURCE
        ).decode("utf-8")
    contents = PATCH_BUFFERS[relative]
    if new and new in contents:
        return
    if contents.count(old) != 1:
        raise SystemExit(f"Patch anchor changed: {relative}: {old[:90]}")
    PATCH_BUFFERS[relative] = contents.replace(old, new)
    path.write_text(PATCH_BUFFERS[relative], encoding="utf-8", newline="\n")


replace("box.go", "network             *route.NetworkManager", "network             adapter.NetworkManager")
replace("box.go", '"sync/atomic"', '"sync"\n\t"sync/atomic"')
replace("box.go", '\t"os"\n', '')
replace("box.go", "closed              atomic.Bool", "lernetCloseMu sync.Mutex\n\tlernetCloseResult error\n\tclosed              atomic.Bool")
replace("box.go", "func (s *Box) Close() error {", "func (s *Box) Close() error {\n\ts.lernetCloseMu.Lock();defer s.lernetCloseMu.Unlock()")
replace("box.go", "if !s.closed.CompareAndSwap(false, true) {\n\t\treturn os.ErrClosed\n\t}", "if !s.closed.CompareAndSwap(false, true) {\n\t\treturn s.lernetCloseResult\n\t}")
replace("box.go", "done()\n\treturn err\n}\n\nfunc (s *Box) Network()", "done()\n\ts.lernetCloseResult=err\n\treturn err\n}\n\nfunc (s *Box) Network()")
replace("box.go", "NetworkNamespaceHolderArgs []string", "NetworkNamespaceHolderArgs []string\n\t// LerNET owns one ingress/network manager across policy generations.\n\tExistingNetwork adapter.NetworkManager\n\tRouterFactory func(adapter.Router) adapter.Router\n\tBeforeInboundStart func() error")
replace("box.go", "closed              atomic.Bool", "beforeInboundStart func() error\n\tclosed              atomic.Bool")
replace("box.go", "networkManager, err := route.NewNetworkManager(ctx, logFactory.NewLogger(\"network\"), routeOptions, dnsOptions)\n\tif err != nil {\n\t\treturn nil, E.Cause(err, \"initialize network manager\")\n\t}", "var networkManager adapter.NetworkManager\n\tif options.ExistingNetwork != nil {\n\t\tnetworkManager = options.ExistingNetwork\n\t} else {\n\t\tnetworkManager, err = route.NewNetworkManager(ctx, logFactory.NewLogger(\"network\"), routeOptions, dnsOptions)\n\t\tif err != nil { return nil, E.Cause(err, \"initialize network manager\") }\n\t}")
replace("box.go", "service.MustRegister[adapter.Router](ctx, router)", "var registeredRouter adapter.Router = router\n\tif options.RouterFactory != nil { registeredRouter = options.RouterFactory(router) }\n\tservice.MustRegister[adapter.Router](ctx, registeredRouter)")
replace("box.go", "err = inboundManager.Create(\n\t\t\tctx,\n\t\t\trouter,", "err = inboundManager.Create(\n\t\t\tctx,\n\t\t\tregisteredRouter,")
replace("box.go", "if platformInterface != nil {\n\t\terr = platformInterface.Initialize(networkManager)", "if platformInterface != nil && options.ExistingNetwork == nil {\n\t\terr = platformInterface.Initialize(networkManager)")
replace("box.go", "network:             networkManager,", "network:             networkManager,\n\t\tbeforeInboundStart: options.BeforeInboundStart,")
replace("box.go", "err = adapter.Start(s.ctx, s.logger, adapter.StartStateStart, s.inbound, s.service)", "if s.beforeInboundStart != nil {\n\t\tif err = s.beforeInboundStart(); err != nil { return err }\n\t}\n\terr = adapter.Start(s.ctx, s.logger, adapter.StartStateStart, s.inbound, s.service)")

replace("adapter/inbound.go", "RouteRule     string", "LerNETProtected bool\n\tLerNETFallback string\n\tRouteRule     string")
replace("option/rule_action.go", "type RawRouteOptionsActionOptions struct {", "type RawRouteOptionsActionOptions struct {\n\tLerNETProtected bool `json:\"lernet_protected,omitempty\"`\n\tLerNETFallback string `json:\"lernet_fallback,omitempty\"`")
replace("route/rule/rule_action.go", "func newRuleActionRouteOptions(options option.RawRouteOptionsActionOptions) (RuleActionRouteOptions, error) {", "func newRuleActionRouteOptions(options option.RawRouteOptionsActionOptions) (RuleActionRouteOptions, error) {\n\tif options.LerNETFallback != \"\" && options.LerNETFallback != \"block\" && options.LerNETFallback != \"direct\" { return RuleActionRouteOptions{}, E.New(\"invalid lernet_fallback\") }\n\tif options.LerNETProtected && options.LerNETFallback == \"direct\" { return RuleActionRouteOptions{}, E.New(\"protected branch cannot fall back directly\") }")
replace("route/rule/rule_action.go", "return RuleActionRouteOptions{\n\t\tOverrideAddress:", "return RuleActionRouteOptions{\n\t\tLerNETProtected: options.LerNETProtected,\n\t\tLerNETFallback: options.LerNETFallback,\n\t\tOverrideAddress:")
replace("route/rule/rule_action.go", "type RuleActionRouteOptions struct {", "type RuleActionRouteOptions struct {\n\tLerNETProtected bool\n\tLerNETFallback string")
replace("route/route.go", "func applyRouteOptionsOverride(metadata *adapter.InboundContext, routeOptions *R.RuleActionRouteOptions) {", "func applyRouteOptionsOverride(metadata *adapter.InboundContext, routeOptions *R.RuleActionRouteOptions) {\n\tmetadata.LerNETProtected = metadata.LerNETProtected || routeOptions.LerNETProtected\n\tif routeOptions.LerNETFallback != \"\" { metadata.LerNETFallback = routeOptions.LerNETFallback }")
replace("option/route.go", "type RouteOptions struct {", "type RouteOptions struct {\n\tLerNETOwnerGuard string `json:\"lernet_owner_guard,omitempty\"`\n\tLerNETUnknownOwnerDNSSafe bool `json:\"lernet_unknown_owner_dns_safe,omitempty\"`")
replace("route/router.go", "needFindProcess   bool", "lernetOwnerGuard string\n\tlernetUnknownOwnerDNSSafe bool\n\tneedFindProcess   bool")
replace("route/router.go", "needFindProcess:      hasRule", "lernetOwnerGuard: options.LerNETOwnerGuard,\n\t\tlernetUnknownOwnerDNSSafe: options.LerNETUnknownOwnerDNSSafe,\n\t\tneedFindProcess:      options.LerNETOwnerGuard != \"\" || hasRule")
replace("route/route.go", "r.searchProcessInfo(ctx, &metadata)\n\t\tN.CloseOnHandshakeFailure(conn, onClose, r.hijackDNSStream", "r.searchProcessInfo(ctx, &metadata)\n\t\tif r.LerNETOwnerUnknown(&metadata) { return E.New(\"unknown owner blocked by protected policy\") }\n\t\tN.CloseOnHandshakeFailure(conn, onClose, r.hijackDNSStream")
replace("route/route.go", "r.searchProcessInfo(ctx, &metadata)\n\t\treturn r.hijackDNSPacket", "r.searchProcessInfo(ctx, &metadata)\n\t\tif r.LerNETOwnerUnknown(&metadata) { r.LerNETObserveDecision(ctx, metadata, nil, \"blocked\", \"attribution_unknown\"); return E.New(\"unknown owner blocked by protected policy\") }\n\t\treturn r.hijackDNSPacket")
replace("route/route.go", "if r.LerNETOwnerUnknown(&metadata) { return E.New(\"unknown owner blocked by protected policy\") }", "if r.LerNETOwnerUnknown(&metadata) { r.LerNETObserveDecision(ctx, metadata, nil, \"blocked\", \"attribution_unknown\"); return E.New(\"unknown owner blocked by protected policy\") }")

replace("route/dns.go", "func (r *Router) HijackDNSPacket(ctx context.Context, payload []byte, writer N.PacketWriter, metadata adapter.InboundContext) {", "func (r *Router) HijackDNSPacket(ctx context.Context, payload []byte, writer N.PacketWriter, metadata adapter.InboundContext) {\n\tr.HijackDNSPacketTracked(ctx, payload, writer, metadata, func(){})\n}\n\nfunc (r *Router) HijackDNSPacketTracked(ctx context.Context, payload []byte, writer N.PacketWriter, metadata adapter.InboundContext, completed func()) {\n\ttransferred := false\n\tdefer func(){ if !transferred { completed() } }()")
replace("route/dns.go", "go func() {\n\t\tdefer r.dnsHijackSem.Release(1)", "transferred = true\n\tgo func() {\n\t\tdefer r.dnsHijackSem.Release(1)")
replace("route/dns.go", "func(response *mDNS.Msg, exchangeErr error) {", "func(response *mDNS.Msg, exchangeErr error) {\n\t\t\tdefer completed()")
replace("route/dns.go", "defer completed()\n\t\t\tif exchangeErr == nil", "defer completed()\n\t\t\tif exchangeErr != nil { observed:=metadata; observed.Destination=destination; observed.Protocol=\"dns\"; if len(message.Question)>0 {observed.Domain=message.Question[0].Name}; r.LerNETObserveFailure(ctx,observed,\"dns\",exchangeErr) }\n\t\t\tif exchangeErr == nil")
replace("route/dns.go", "destination := metadata.Destination", "if r.LerNETOwnerUnknown(&metadata) { r.LerNETObserveDecision(ctx, metadata, nil, \"blocked\", \"attribution_unknown\"); return }\n\tdestination := metadata.Destination")
replace("route/dns.go", "func (r *Router) hijackDNSStream(ctx context.Context, conn net.Conn, metadata adapter.InboundContext) error {", "func (r *Router) hijackDNSStream(ctx context.Context, conn net.Conn, metadata adapter.InboundContext) error {\n\tctx,completed:=r.lernetTrackDNSIngress(ctx,conn);defer completed()\n\tif err:=ctx.Err();err!=nil{return err}")
replace("route/dns.go", "func (r *Router) hijackDNSPacket(ctx context.Context, conn N.PacketConn, packetBuffers []*N.PacketBuffer, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) error {", "func (r *Router) hijackDNSPacket(ctx context.Context, conn N.PacketConn, packetBuffers []*N.PacketBuffer, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) error {\n\tctx,completed:=r.lernetTrackDNSIngress(ctx,conn);defer completed()\n\tif err:=ctx.Err();err!=nil{N.ReleaseMultiPacketBuffer(packetBuffers);return err}")
replace("dns/router.go", "metadata.Destination = M.Socksaddr{}\n\tmetadata.QueryType", "// DNS detours never inherit a business flow's ordinary DIRECT fallback.\n\tmetadata.LerNETProtected=true\n\tmetadata.LerNETFallback=\"block\"\n\tmetadata.Destination = M.Socksaddr{}\n\tmetadata.QueryType")
replace("dns/client.go", "func (c *Client) beginExchange(ctx context.Context, transport adapter.DNSTransport, message *dns.Msg, options adapter.DNSQueryOptions, responseChecker func(response *dns.Msg) bool, allowWait bool) (*exchangeOperation, *dns.Msg, exchangeStatus, error) {", "func (c *Client) beginExchange(ctx context.Context, transport adapter.DNSTransport, message *dns.Msg, options adapter.DNSQueryOptions, responseChecker func(response *dns.Msg) bool, allowWait bool) (*exchangeOperation, *dns.Msg, exchangeStatus, error) {\n\tif responseChecker==nil && transport!=nil && ctx.Err()==nil {if response:=lernetDirectFamilyResponse(c.ctx,message,transport.Tag());response!=nil{if c.logger!=nil{c.logger.DebugContext(ctx,\"Direct IPv6 unavailable: AAAA NODATA for \" ,message.Question[0].Name)};return nil,response,exchangeDone,nil}}")
for relative in ("route/route.go", "route/dns.go"):
    PATCH_BUFFERS[relative] = PATCH_BUFFERS[relative].replace("r.LerNETOwnerUnknown(&metadata)", "r.LerNETOwnerUnknownDNS(&metadata)")
    (SOURCE / relative).write_text(PATCH_BUFFERS[relative], encoding="utf-8", newline="\n")

replace("option/dns.go", "type RemoteDNSServerOptions struct {", "type RemoteDNSServerOptions struct {\n\tLerNETSystemRoute bool `json:\"lernet_system_route,omitempty\"`")
replace("dns/transport_dialer.go", "func NewRemoteDialer(ctx context.Context, options option.RemoteDNSServerOptions) (N.Dialer, error) {", "func NewRemoteDialer(ctx context.Context, options option.RemoteDNSServerOptions) (N.Dialer, error) {\n\tif options.LerNETSystemRoute { var err error; ctx,err = dialer.LerNETSystemRouteContext(ctx); if err != nil { return nil, err } }")
# Preserve Windows' chosen DNS destination independently of DNS rule matching.
replace("adapter/inbound.go", "type InboundContext struct {", "type InboundContext struct {\n\tLerNETDNSDestination M.Socksaddr")
replace("option/dns.go", "type RawLocalDNSServerOptions struct {", "type RawLocalDNSServerOptions struct {\n\tLerNETPreserveDestination bool `json:\"lernet_preserve_destination,omitempty\"`")
replace("dns/transport/local/local.go", "transportDialer, err := dns.NewLocalDialer(ctx, options)", "if options.LerNETPreserveDestination {\n\t\tvar err error; ctx, err = lernetPreservingContext(ctx); if err != nil { return nil, err }\n\t}\n\ttransportDialer, err := dns.NewLocalDialer(ctx, options)")
replace("dns/transport/local/local.go", "return &Transport{", "transport := &Transport{")
replace("dns/transport/local/local.go", "\t}, nil\n}", "\t}\n\tif options.LerNETPreserveDestination { return newLerNETPreservingTransport(ctx, logger, transport) }\n\treturn transport, nil\n}")
replace("adapter/inbound.go", "LerNETProtected bool", "LerNETNodeIDs []string\n\tLerNETFlowObserver func(string,string)\n\tLerNETProtected bool")
replace("option/rule_action.go", "type RawRouteOptionsActionOptions struct {", "type RawRouteOptionsActionOptions struct {\n\tLerNETNodeIDs []string `json:\"lernet_node_ids,omitempty\"`")
replace("option/rule_action.go", "if *r == (RouteOptionsActionOptions{}) {", "if reflect.DeepEqual(*r, RouteOptionsActionOptions{}) {")
replace("option/rule_action.go", "type _RejectActionOptions struct {", "type _RejectActionOptions struct {\n\tLerNETNodeIDs []string `json:\"lernet_node_ids,omitempty\"`")
replace("route/rule/rule_action.go", "type RuleActionRouteOptions struct {", "type RuleActionRouteOptions struct {\n\tLerNETNodeIDs []string")
replace("route/rule/rule_action.go", "return RuleActionRouteOptions{\n\t\tLerNETProtected:", "return RuleActionRouteOptions{\n\t\tLerNETNodeIDs: append([]string(nil), options.LerNETNodeIDs...),\n\t\tLerNETProtected:")
replace("route/rule/rule_action.go", "type RuleActionReject struct {", "type RuleActionReject struct {\n\tLerNETNodeIDs []string")
# Route and DNS reject builders share this literal. Replace both explicitly.
relative = "route/rule/rule_action.go"
anchor = "Method: action.RejectOptions.Method,"
replacement = "LerNETNodeIDs: append([]string(nil), action.RejectOptions.LerNETNodeIDs...),\n\t\t\tMethod: action.RejectOptions.Method,"
if PATCH_BUFFERS[relative].count(anchor) != 2:
    raise SystemExit("Reject trace builder anchor changed")
PATCH_BUFFERS[relative] = PATCH_BUFFERS[relative].replace(anchor, replacement)
(SOURCE / relative).write_text(PATCH_BUFFERS[relative], encoding="utf-8", newline="\n")
relative = "option/rule.go"
original = subprocess.check_output(["git", "show", f"{PIN}:{relative}"], cwd=SOURCE).decode("utf-8")
if original.count("routeOptions == (RouteActionOptions{})") != 2:
    raise SystemExit("Route zero-options anchor changed")
PATCH_BUFFERS[relative] = original.replace("routeOptions == (RouteActionOptions{})", "reflect.DeepEqual(routeOptions, RouteActionOptions{})")
(SOURCE / relative).write_text(PATCH_BUFFERS[relative], encoding="utf-8", newline="\n")
replace("route/route.go", "metadata.LerNETProtected = metadata.LerNETProtected", "if len(routeOptions.LerNETNodeIDs)>0 { metadata.LerNETNodeIDs = append([]string(nil), routeOptions.LerNETNodeIDs...) }\n\tmetadata.LerNETProtected = metadata.LerNETProtected")
replace("route/route.go", "case *R.RuleActionReject:\n\t\t\tbuf.ReleaseMulti", "case *R.RuleActionReject:\n\t\t\tr.LerNETObserveDecision(ctx, metadata, selectedRule, \"blocked\", \"policy_reject\")\n\t\t\tbuf.ReleaseMulti")
replace("route/route.go", "case *R.RuleActionReject:\n\t\t\tN.ReleaseMultiPacketBuffer", "case *R.RuleActionReject:\n\t\t\tr.LerNETObserveDecision(ctx, metadata, selectedRule, \"blocked\", \"policy_reject\")\n\t\t\tN.ReleaseMultiPacketBuffer")
replace("route/route.go", "conn = tracker.RoutedConnection(ctx, conn, metadata, selectedRule, selectedOutbound)\n\t}", "conn = tracker.RoutedConnection(ctx, conn, metadata, selectedRule, selectedOutbound)\n\t\tif observer,ok:=conn.(interface{LerNETDecisionObserver()func(string,string)});ok{metadata.LerNETFlowObserver=observer.LerNETDecisionObserver()}\n\t}")
replace("route/route.go", "conn = tracker.RoutedPacketConnection(ctx, conn, metadata, selectedRule, selectedOutbound)\n\t}", "conn = tracker.RoutedPacketConnection(ctx, conn, metadata, selectedRule, selectedOutbound)\n\t\tif observer,ok:=conn.(interface{LerNETDecisionObserver()func(string,string)});ok{metadata.LerNETFlowObserver=observer.LerNETDecisionObserver()}\n\t}")
for method in ("RoutedConnection", "RoutedPacketConnection"):
    anchor = f"conn = tracker.{method}(ctx, conn, metadata, selectedRule, selectedOutbound)"
    replace("route/route.go", anchor, anchor + "\n\t\tif observer,ok:=conn.(interface{LerNETCloseObserver()func(error)});ok{ observe:=observer.LerNETCloseObserver(); previous:=onClose; onClose=N.OnceClose(func(err error){observe(err);if previous!=nil{previous(err)}}) }")
replace("protocol/tun/inbound.go", "func (t *Inbound) JudgeFlow(network uint8", "// LerNETInterfaceIdentity is read only after Start succeeded. It describes the\n// actual retained native interface, rather than a policy generation UUID.\nfunc (t *Inbound) LerNETInterfaceIdentity() string {\n\tindex := 0\n\tif actual, err := net.InterfaceByName(t.tunOptions.Name); err == nil { index = actual.Index }\n\treturn t.tunOptions.Name + \":\" + strconv.Itoa(index) + \":\" + strconv.Itoa(t.tunOptions.FileDescriptor)\n}\n\nfunc (t *Inbound) JudgeFlow(network uint8")
replace("protocol/tun/inbound.go", "func (t *Inbound) LerNETInterfaceIdentity() string {", "func(t *Inbound)LerNETPendingPacketLimits()(int,int){if limits,ok:=t.router.(interface{LerNETPendingPacketLimits()(int,int)});ok{return limits.LerNETPendingPacketLimits()};return 0,0}\n\nfunc (t *Inbound) LerNETInterfaceIdentity() string {")
replace("protocol/tun/inbound.go", "func (t *Inbound) LerNETInterfaceIdentity() string {", "func(t *Inbound)LerNETUpdateCaptureRoutes(prefixes []netip.Prefix)error{\n\tif t.tunIf==nil{return E.New(\"owned TUN unavailable\")}\n\toptions:=t.tunOptions;options.Inet4RouteAddress=nil;options.Inet6RouteAddress=nil\n\tfor _,prefix:=range prefixes{if prefix.Addr().Is4(){options.Inet4RouteAddress=append(options.Inet4RouteAddress,prefix)}else{options.Inet6RouteAddress=append(options.Inet6RouteAddress,prefix)}}\n\tvar err error;if updater,ok:=t.tunIf.(interface{LerNETUpdateCaptureRouteOptions(tun.Options)error});ok{err=updater.LerNETUpdateCaptureRouteOptions(options)}else{err=t.tunIf.UpdateRouteOptions(options)};if err!=nil{return err};t.tunOptions=options;return nil\n}\n\nfunc (t *Inbound) LerNETInterfaceIdentity() string {")
replace("protocol/tun/inbound.go", "t.tunOptions.Name = tunOptions.Name", "t.lernetRetainOpenedIdentity(tunOptions)")
replace("cmd/sing-box/main.go", "func main() {", "func main() {\n\t// Only the elevated Expert TUN service can use this WFP-trusted binary.\n\tfor _,command:=range mainCommand.Commands(){if command.Name()!=\"expert\"{mainCommand.RemoveCommand(command)}}")
replace("option/direct.go", "type _DirectOutboundOptions struct {", "type LerNETInterfaceOptions struct {\n\tGUID string `json:\"guid\"`\n\tName string `json:\"name\"`\n\tIndex int `json:\"index\"`\n}\n\ntype _DirectOutboundOptions struct {\n\tLerNETInterface *LerNETInterfaceOptions `json:\"lernet_interface,omitempty\"`")
replace("option/direct.go", "LerNETInterface *LerNETInterfaceOptions", "LerNETSystemRoute bool `json:\"lernet_system_route,omitempty\"`\n\tLerNETInterface *LerNETInterfaceOptions")
replace("protocol/direct/outbound.go", "options.UDPFragmentDefault = true", "if options.LerNETInterface!=nil {\n\t\tif options.BindInterface!=\"\"&&options.BindInterface!=options.LerNETInterface.Name || options.Inet4BindAddress!=nil || options.Inet6BindAddress!=nil || options.NetworkStrategy!=nil || len(options.NetworkType)>0 || len(options.FallbackNetworkType)>0 || options.NetNs!=\"\" {return nil,E.New(\"interface_binding_conflict\")}\n\t\tvar err error\n\t\tctx,err=dialer.LerNETInterfaceContext(ctx,*options.LerNETInterface);if err!=nil{return nil,err}\n\t\toptions.BindInterface=\"\"\n\t}\n\toptions.UDPFragmentDefault = true")
replace("protocol/direct/outbound.go", "if options.LerNETInterface!=nil {", "if options.LerNETSystemRoute {\n\t\tif options.LerNETInterface!=nil||options.BindInterface!=\"\"||options.Inet4BindAddress!=nil||options.Inet6BindAddress!=nil||options.NetNs!=\"\"||options.NetworkStrategy!=nil||len(options.NetworkType)>0||len(options.FallbackNetworkType)>0{return nil,E.New(\"interface_binding_conflict\")}\n\t\tvar err error;ctx,err=dialer.LerNETSystemRouteContext(ctx);if err!=nil{return nil,err}\n\t}\n\tif options.LerNETInterface!=nil {")
replace("common/dialer/default.go", "if options.BindInterface != \"\" {", "verifiedBinding:=service.FromContext[*lernetVerifiedBinding](ctx)\n\tif verifiedBinding!=nil {\n\t\tdialer.Control=control.Append(dialer.Control,verifiedBinding.control)\n\t\tif verifiedBinding.destinationAware{autoDetectBindFunc=verifiedBinding.control}else{listener.Control=control.Append(listener.Control,verifiedBinding.control)}\n\t}\n\tif options.BindInterface != \"\" {")
replace("common/dialer/default.go", "disableDefaultBind := options.BindInterface", "disableDefaultBind := verifiedBinding!=nil || options.BindInterface")
replace("common/dialer/default.go", "netns                  string\n\tautoDetectBindFunc     control.Func", "netns                  string\n\tlernetFixedBinding bool\n\tautoDetectBindFunc     control.Func")
replace("common/dialer/default.go", "autoDetectBindFunc:     autoDetectBindFunc,", "autoDetectBindFunc:     autoDetectBindFunc,\n\t\tlernetFixedBinding: verifiedBinding!=nil,")
replace("common/dialer/default.go", "if d.networkStrategy == nil {\n\t\tconn, err :=", "if d.networkStrategy == nil || d.lernetFixedBinding {\n\t\tconn, err :=")
replace("common/dialer/default.go", "func (d *DefaultDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {\n\tif d.networkStrategy == nil {", "func (d *DefaultDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {\n\tif d.networkStrategy == nil || d.lernetFixedBinding {")
replace("common/dialer/default.go", "func (d *DefaultDialer) DialParallelInterface(ctx context.Context, network string, address M.Socksaddr, strategy *C.NetworkStrategy, interfaceType []C.InterfaceType, fallbackInterfaceType []C.InterfaceType, fallbackDelay time.Duration) (net.Conn, error) {", "func (d *DefaultDialer) DialParallelInterface(ctx context.Context, network string, address M.Socksaddr, strategy *C.NetworkStrategy, interfaceType []C.InterfaceType, fallbackInterfaceType []C.InterfaceType, fallbackDelay time.Duration) (net.Conn, error) {\n\tif d.lernetFixedBinding{return d.DialContext(ctx,network,address)}")
replace("common/dialer/default.go", "func (d *DefaultDialer) ListenSerialInterfacePacket(ctx context.Context, destination M.Socksaddr, strategy *C.NetworkStrategy, interfaceType []C.InterfaceType, fallbackInterfaceType []C.InterfaceType, fallbackDelay time.Duration) (net.PacketConn, error) {", "func (d *DefaultDialer) ListenSerialInterfacePacket(ctx context.Context, destination M.Socksaddr, strategy *C.NetworkStrategy, interfaceType []C.InterfaceType, fallbackInterfaceType []C.InterfaceType, fallbackDelay time.Duration) (net.PacketConn, error) {\n\tif d.lernetFixedBinding{return d.ListenPacket(ctx,destination)}")

# The pinned packet stack already drops queue overflow; add an Expert-only byte
# budget as well. Ordinary clients keep the upstream queue and buffer settings.
submodule = SOURCE / "submodules/sing-tun"
subpin = "6f13ebcc131c622e3d98903de181ed21e0386fbe"
if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=submodule, text=True).strip() != subpin:
    raise SystemExit("Refusing unknown sing-tun commit")
udp = subprocess.check_output(["git", "show", f"{subpin}:udp_nat.go"], cwd=submodule).decode("utf-8")
def udp_replace(old: str, new: str, count: int = 1) -> None:
    global udp
    if udp.count(old) != count:
        raise SystemExit(f"UDP budget anchor changed: {old[:60]}")
    udp = udp.replace(old, new)
udp_replace("type UDPNat struct {", "type UDPNat struct {\n\tpendingPacketLimit int\n\tpendingByteLimit int64")
udp_replace("service := &UDPNat{", "packetLimit,byteLimit:=64,0\n\tif limits,ok:=options.Handler.(interface{LerNETPendingPacketLimits()(int,int)});ok{packets,bytes:=limits.LerNETPendingPacketLimits();if packets>0{packetLimit=packets};if bytes>0{byteLimit=bytes}}\n\tservice := &UDPNat{\n\t\tpendingPacketLimit:packetLimit,\n\t\tpendingByteLimit:int64(byteLimit),")
udp_replace("make(chan *N.PacketBuffer, 64)", "make(chan *N.PacketBuffer, s.pendingPacketLimit)")
udp_replace("type udpNatConn struct {", "type udpNatConn struct {\n\tpendingBytes atomic.Int64")
udp_replace("packet := N.NewPacketBuffer()", "size:=int64(buffer.Len())\n\tif c.service.pendingByteLimit>0&&c.pendingBytes.Add(size)>c.service.pendingByteLimit{c.pendingBytes.Add(-size);buffer.Release();c.packetAccess.RUnlock();return}\n\tif c.service.pendingByteLimit==0{c.pendingBytes.Add(size)}\n\tpacket := N.NewPacketBuffer()")
udp_replace("default:\n\t\tpacket.Buffer.Release()", "default:\n\t\tc.pendingBytes.Add(-int64(packet.Buffer.Len()))\n\t\tpacket.Buffer.Release()")
udp_replace("case p := <-c.packetChan:", "case p := <-c.packetChan:\n\t\tc.pendingBytes.Add(-int64(p.Buffer.Len()))")
udp_replace("case packet := <-c.packetChan:", "case packet := <-c.packetChan:\n\t\t\tc.pendingBytes.Add(-int64(packet.Buffer.Len()))", 3)
(submodule / "udp_nat.go").write_text(udp, encoding="utf-8", newline="\n")

# A protected Windows adapter is created and retained by the SCM guardian.
# Opening adapter metadata never acquires its creator/device lifetime handle.
def tun_replace(relative: str, old: str, new: str) -> None:
    target = submodule / relative
    key = "sing-tun/" + relative
    if key not in PATCH_BUFFERS:
        PATCH_BUFFERS[key] = subprocess.check_output(
            ["git", "show", f"{subpin}:{relative}"], cwd=submodule
        ).decode("utf-8")
    contents = PATCH_BUFFERS[key]
    if contents.count(old) != 1:
        raise SystemExit(f"Guarded TUN anchor changed: {relative}: {old[:80]}")
    PATCH_BUFFERS[key] = contents.replace(old, new)
    target.write_text(PATCH_BUFFERS[key], encoding="utf-8", newline="\n")

tun_replace("tun.go", "type Options struct {", "type Options struct {\n\tLerNETGuardedAdapter *LerNETGuardedAdapter")
replace("protocol/tun/inbound.go", "tunOptions: tun.Options{", "tunOptions: tun.Options{\n\t\t\tLerNETGuardedAdapter:service.FromContext[*tun.LerNETGuardedAdapter](ctx),")

original = subprocess.check_output(["git", "show", f"{subpin}:tun_windows.go"], cwd=submodule).decode("utf-8")
start = original.index('\tadapter, err := wintun.CreateAdapter(')
end = original.index('\tnativeTun := &NativeTun{', start)
creation = original[start:end]
replacement = '\tvar adapter *wintun.Adapter\n\tvar err error\n\tif options.LerNETGuardedAdapter!=nil {\n\t\tadapter,err=lernetOpenGuardedAdapter(options);if err!=nil{return nil,err}\n\t} else {\n' + creation.replace('adapter, err :=','adapter, err =',1) + '\t}\n'
tun_replace("tun_windows.go", creation, replacement)
tun_replace("tun_windows.go", "closeOnce   sync.Once", "closeOnce sync.Once\n\tlernetCloseResult error")
close_start=original.index("func (t *NativeTun) Close() error {")
close_end=original.index("func (t *NativeTun) UpdateRouteOptions",close_start)
new_close="""func (t *NativeTun) Close() error {
 t.closeOnce.Do(func(){
  t.lernetCloseResult=lernetCloseInOrder(t.options.LerNETGuardedAdapter,func()error{
   t.close.Store(1)
   if err:=windows.SetEvent(t.readWait);err!=nil{return err}
   t.running.Wait()
   t.session.End()
   t.adapter.Close()
   if t.fwpmSession!=0{if err:=winsys.FwpmEngineClose0(t.fwpmSession);err!=nil{return err}}
   if t.options.AutoRoute{windnsapi.FlushResolverCache()}
   return nil
  })
 })
 return t.lernetCloseResult
}

"""
tun_replace("tun_windows.go",original[close_start:close_end],new_close)
# WintunCloseAdapter is void, not BOOL; interpreting an arbitrary return register
# as success makes a completed native cleanup report a fabricated error.
tun_replace("internal/wintun/wintun_windows.go", "r1, _, e1 := syscall.SyscallN(procWintunCloseAdapter.Addr(), wintun.handle)\n\tif r1 == 0 {\n\t\terr = e1\n\t}\n\treturn", "syscall.SyscallN(procWintunCloseAdapter.Addr(), wintun.handle)\n\treturn nil")
tun_replace("internal/winsys/zsyscall_windows.go", "if r1 != 0 {\n\t\terr = errnoErr(e1)\n\t}\n\treturn\n}\n\nfunc FwpmEngineOpen0", "if r1 != 0 {\n\t\terr = syscall.Errno(r1)\n\t}\n\t_ = e1\n\treturn\n}\n\nfunc FwpmEngineOpen0")
tun_replace("tun_windows.go", "session, err := adapter.StartSession(0x800000)\n\tif err != nil {\n\t\treturn nil, err\n\t}", "session, err := adapter.StartSession(0x800000)\n\tif err != nil {\n\t\tif guard:=options.LerNETGuardedAdapter;guard!=nil{if revokeErr:=guard.BeforeClose();revokeErr!=nil{return nil,E.Errors(err,revokeErr)};adapter.Close();return nil,E.Errors(err,guard.AfterClose())}\n\t\tadapter.Close()\n\t\treturn nil, err\n\t}")
tun_replace("tun_windows.go", "session.End()\n\t\tadapter.Close()\n\t\treturn nil, err", "closeErr:=nativeTun.Close()\n\t\treturn nil,E.Errors(err,closeErr)")

overlay = ROOT / "native" / "overlay"
for original in overlay.rglob("*"):
    if original.is_file():
        destination = SOURCE / original.relative_to(overlay)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, destination)
(SOURCE / ".lernet-overlay-applied").write_text(PIN, encoding="ascii")
print(f"Applied LerNET native overlay to {actual}")

# Keep server selection before clearing destination for DNS condition matching.
for relative in ("route/dns.go", "protocol/dns/handle.go"):
    if relative not in PATCH_BUFFERS:
        PATCH_BUFFERS[relative] = subprocess.check_output(["git", "show", f"{PIN}:{relative}"], cwd=SOURCE).decode("utf-8")
    contents = PATCH_BUFFERS[relative]
    anchor = "metadata.Destination = M.Socksaddr{}"
    if anchor not in contents:
        raise SystemExit(f"DNS destination anchor changed: {relative}")
    contents = contents.replace(anchor, "metadata.LerNETDNSDestination = metadata.Destination\n\t" + anchor)
    if relative == "protocol/dns/handle.go":
        anchor = "metadataInQuery := metadata"
        # TCP inherits the captured destination; each UDP query has its own endpoint.
        first = contents.index(anchor)
        head, tail = contents[:first + len(anchor)], contents[first + len(anchor):]
        if tail.count(anchor) != 2:
            raise SystemExit("DNS packet query anchor changed")
        contents = head + tail.replace(anchor, anchor + "\n\t\t\tmetadataInQuery.LerNETDNSDestination = destination")
    PATCH_BUFFERS[relative] = contents
    (SOURCE / relative).write_text(contents, encoding="utf-8", newline="\n")
