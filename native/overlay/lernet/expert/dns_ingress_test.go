package expert

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/route"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"

	mdns "github.com/miekg/dns"
)

type ingressDNSResolver struct {
	adapter.DNSRouter
	answer  string
	queries atomic.Int32
}

func (r *ingressDNSResolver) LookupReverseMapping(netip.Addr) (string, bool) { return "", false }
func (r *ingressDNSResolver) ExchangeAsync(ctx context.Context, query *mdns.Msg, _ adapter.DNSQueryOptions, callback func(*mdns.Msg, error)) {
	if err := ctx.Err(); err != nil {
		callback(nil, err)
		return
	}
	r.queries.Add(1)
	response := new(mdns.Msg)
	response.SetReply(query)
	response.Answer = []mdns.RR{&mdns.A{Hdr: mdns.RR_Header{Name: query.Question[0].Name, Rrtype: mdns.TypeA, Class: mdns.ClassINET, Ttl: 1}, A: net.ParseIP(r.answer)}}
	callback(response, nil)
}

type ingressDNSTransports struct{ adapter.DNSTransportManager }

func (ingressDNSTransports) FakeIP() adapter.FakeIPTransport { return nil }

func dnsTestGeneration(t *testing.T, mux *switchRouter, revision int64, resolver *ingressDNSResolver, reject bool) *generation {
	t.Helper()
	ctx := service.ContextWith[adapter.DNSRouter](service.ExtendContext(context.Background()), resolver)
	ctx = service.ContextWith[adapter.DNSTransportManager](ctx, ingressDNSTransports{})
	ctx = service.ContextWith[adapter.LerNETDNSIngressTracker](ctx, &generationDNSIngress{mux.dnsIngress, revision})
	router := route.NewRouter(ctx, log.NewNOPFactory(), option.RouteOptions{}, option.DNSOptions{})
	service.MustRegister[adapter.Router](ctx, router)
	action := C.RuleActionTypeHijackDNS
	if reject {
		action = C.RuleActionTypeReject
	}
	options := option.Rule{DefaultOptions: option.DefaultRule{RawDefaultRule: option.RawDefaultRule{Port: []uint16{53}}, RuleAction: option.RuleAction{Action: action}}}
	options.DefaultOptions.RejectOptions.Method = C.RuleActionRejectMethodDefault
	if err := router.Initialize([]option.Rule{options}, nil); err != nil {
		t.Fatal(err)
	}
	return &generation{revision: revision, router: router, close: func() error { return nil }}
}

type ingressDNSPacketConn struct {
	requests, replies chan []byte
	done              chan struct{}
	once              sync.Once
	closes            atomic.Int32
}

func newIngressDNSPacketConn() *ingressDNSPacketConn {
	return &ingressDNSPacketConn{requests: make(chan []byte, 2), replies: make(chan []byte, 2), done: make(chan struct{})}
}
func (c *ingressDNSPacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	select {
	case <-c.done:
		return M.Socksaddr{}, net.ErrClosed
	case payload := <-c.requests:
		_, err := buffer.Write(payload)
		return M.ParseSocksaddr("192.0.2.10:51000"), err
	}
}
func (c *ingressDNSPacketConn) WritePacket(buffer *buf.Buffer, _ M.Socksaddr) error {
	defer buffer.Release()
	payload := append([]byte(nil), buffer.Bytes()...)
	select {
	case <-c.done:
		return net.ErrClosed
	case c.replies <- payload:
		return nil
	}
}
func (c *ingressDNSPacketConn) Close() error {
	c.once.Do(func() { c.closes.Add(1); close(c.done) })
	return nil
}
func (c *ingressDNSPacketConn) LocalAddr() net.Addr              { return &net.UDPAddr{} }
func (c *ingressDNSPacketConn) SetDeadline(time.Time) error      { return nil }
func (c *ingressDNSPacketConn) SetReadDeadline(time.Time) error  { return nil }
func (c *ingressDNSPacketConn) SetWriteDeadline(time.Time) error { return nil }

type countedDNSStream struct {
	net.Conn
	once   sync.Once
	closes atomic.Int32
}

func (c *countedDNSStream) Close() error {
	var err error
	c.once.Do(func() { c.closes.Add(1); err = c.Conn.Close() })
	return err
}

type ingressDNSClient struct {
	stream     net.Conn
	packet     *ingressDNSPacketConn
	done       chan struct{}
	closeCalls *atomic.Int32
	callbacks  atomic.Int32
}

func startDNSClient(mux *switchRouter, network string) *ingressDNSClient {
	client := &ingressDNSClient{done: make(chan struct{})}
	metadata := adapter.InboundContext{Destination: M.ParseSocksaddr("192.0.2.53:53")}
	onClose := func(error) {
		if client.callbacks.Add(1) == 1 {
			close(client.done)
		}
	}
	if network == "tcp" {
		server, peer := net.Pipe()
		conn := &countedDNSStream{Conn: server}
		client.stream = peer
		client.closeCalls = &conn.closes
		go mux.RouteConnectionEx(context.Background(), conn, metadata, onClose)
	} else {
		client.packet = newIngressDNSPacketConn()
		client.closeCalls = &client.packet.closes
		go mux.RoutePacketConnectionEx(context.Background(), client.packet, metadata, onClose)
	}
	return client
}
func (c *ingressDNSClient) query() (string, error) {
	query := new(mdns.Msg)
	query.SetQuestion("policy.example.", mdns.TypeA)
	payload, _ := query.Pack()
	var response []byte
	if c.stream != nil {
		_ = c.stream.SetDeadline(time.Now().Add(time.Second))
		frame := make([]byte, 2+len(payload))
		binary.BigEndian.PutUint16(frame, uint16(len(payload)))
		copy(frame[2:], payload)
		if _, err := c.stream.Write(frame); err != nil {
			return "", err
		}
		var length uint16
		if err := binary.Read(c.stream, binary.BigEndian, &length); err != nil {
			return "", err
		}
		response = make([]byte, length)
		if _, err := io.ReadFull(c.stream, response); err != nil {
			return "", err
		}
	} else {
		select {
		case <-c.packet.done:
			return "", net.ErrClosed
		case c.packet.requests <- payload:
		}
		select {
		case <-c.packet.done:
			return "", net.ErrClosed
		case response = <-c.packet.replies:
		case <-time.After(time.Second):
			return "", errors.New("DNS test timeout")
		}
	}
	message := new(mdns.Msg)
	if err := message.Unpack(response); err != nil {
		return "", err
	}
	if len(message.Answer) != 1 {
		return "", errors.New("missing test answer")
	}
	return message.Answer[0].(*mdns.A).A.String(), nil
}
func (c *ingressDNSClient) waitClosed(t *testing.T) {
	t.Helper()
	select {
	case <-c.done:
	case <-time.After(time.Second):
		t.Fatal("DNS read loop did not drain")
	}
	if c.callbacks.Load() != 1 || c.closeCalls.Load() != 1 {
		t.Fatalf("DNS close/callback not exactly once: %d/%d", c.closeCalls.Load(), c.callbacks.Load())
	}
}

func TestLerNETPersistentDNSIngressUsesCurrentPolicyAfterPublication(t *testing.T) {
	for _, network := range []string{"tcp", "udp"} {
		for _, reject := range []bool{false, true} {
			t.Run(network+"/reject="+map[bool]string{false: "false", true: "true"}[reject], func(t *testing.T) {
				mux := &switchRouter{dnsIngress: &dnsIngressRegistry{}}
				defer mux.stop()
				oldResolver := &ingressDNSResolver{answer: "192.0.2.1"}
				nextResolver := &ingressDNSResolver{answer: "192.0.2.2"}
				old := dnsTestGeneration(t, mux, 1, oldResolver, false)
				if err := mux.publish(context.Background(), old); err != nil {
					t.Fatal(err)
				}
				oldClient := startDNSClient(mux, network)
				if answer, err := oldClient.query(); err != nil || answer != "192.0.2.1" {
					t.Fatalf("initial DNS: %s %v", answer, err)
				}
				// A real Apply preparation failure must not touch live DNS sessions.
				session := &Session{ctx: context.Background(), mux: mux, running: true, ack: Ack{InstanceID: "instance", InterfaceID: "tun", Revision: 1}}
				if _, err := session.Apply(context.Background(), "instance", "tun", 1, 2, "{}", "{invalid"); err == nil {
					t.Fatal("invalid manifest was published")
				}
				cancelled, cancel := context.WithCancel(context.Background())
				cancel()
				if err := mux.publish(cancelled, dnsTestGeneration(t, mux, 2, nextResolver, reject)); !errors.Is(err, context.Canceled) {
					t.Fatal("cancelled publication accepted")
				}
				if answer, err := oldClient.query(); err != nil || answer != "192.0.2.1" {
					t.Fatalf("failed apply interrupted old DNS: %s %v", answer, err)
				}
				// An unrelated established stream owns the same generation. DNS cleanup
				// must not retire that ordinary stream or close its real connection.
				_, releaseOrdinary, err := mux.acquireFlow()
				if err != nil {
					t.Fatal(err)
				}
				ordinary, peer := net.Pipe()
				defer ordinary.Close()
				defer peer.Close()
				if err := mux.publish(context.Background(), dnsTestGeneration(t, mux, 2, nextResolver, reject)); err != nil {
					t.Fatal(err)
				}
				oldClient.waitClosed(t)
				if _, err := oldClient.query(); err == nil {
					t.Fatal("retired DNS accepted another query")
				}
				if oldResolver.queries.Load() != 2 {
					t.Fatal("query reached retired DNS resolver")
				}
				writeDone := make(chan error, 1)
				go func() { _, err := ordinary.Write([]byte("ordinary")); writeDone <- err }()
				buffer := make([]byte, 8)
				if _, err := io.ReadFull(peer, buffer); err != nil || string(buffer) != "ordinary" {
					t.Fatal("publication broke unrelated stream")
				}
				if err := <-writeDone; err != nil {
					t.Fatal(err)
				}
				releaseOrdinary()
				currentClient := startDNSClient(mux, network)
				answer, err := currentClient.query()
				if reject {
					if err == nil || nextResolver.queries.Load() != 0 {
						t.Fatal("new Block allowed DNS")
					}
					currentClient.waitClosed(t)
				} else {
					if err != nil || answer != "192.0.2.2" {
						t.Fatalf("new resolver policy not used: %s %v", answer, err)
					}
					mux.stop()
					currentClient.waitClosed(t)
				}
				if oldClient.stream != nil {
					oldClient.stream.Close()
				}
				if currentClient.stream != nil {
					currentClient.stream.Close()
				}
			})
		}
	}
}

func TestLerNETDNSLateOldRegistrationCannotSurviveCommit(t *testing.T) {
	mux := &switchRouter{dnsIngress: &dnsIngressRegistry{}}
	if err := mux.publish(context.Background(), &generation{revision: 1, close: func() error { return nil }}); err != nil {
		t.Fatal(err)
	}
	if err := mux.publish(context.Background(), &generation{revision: 2, close: func() error { return nil }}); err != nil {
		t.Fatal(err)
	}
	conn := newIngressDNSPacketConn()
	ctx, complete := (&generationDNSIngress{mux.dnsIngress, 1}).TrackDNSIngress(context.Background(), conn)
	if ctx.Err() != context.Canceled || conn.closes.Load() != 1 {
		t.Fatal("late retired DNS registration remained live")
	}
	complete()
	complete()
	if conn.closes.Load() != 1 {
		t.Fatal("repeated completion closed connection twice")
	}
	mux.stop()
}

var _ N.PacketConn = (*ingressDNSPacketConn)(nil)

func TestLerNETDNSDrainProofWaitsForActualReadLoopCompletion(t *testing.T) {
	mux := &switchRouter{dnsIngress: &dnsIngressRegistry{}}
	if err := mux.publish(context.Background(), &generation{revision: 1, close: func() error { return nil }}); err != nil {
		t.Fatal(err)
	}
	conn := newIngressDNSPacketConn()
	_, complete := (&generationDNSIngress{mux.dnsIngress, 1}).TrackDNSIngress(context.Background(), conn)
	if err := mux.stop(); !errors.Is(err, ErrClosePending) {
		t.Fatal("Close certified DNS drain before actual read-loop completion")
	}
	if conn.closes.Load() != 1 {
		t.Fatal("Stop did not unblock the actual DNS connection")
	}
	complete()
	complete()
	if err := mux.stop(); err != nil {
		t.Fatal("completed DNS read loop still reported pending")
	}
}

func TestLerNETDNSRegistrationRacingPublicationRetiresEveryOldSession(t *testing.T) {
	mux := &switchRouter{dnsIngress: &dnsIngressRegistry{}}
	if err := mux.publish(context.Background(), &generation{revision: 1, close: func() error { return nil }}); err != nil {
		t.Fatal(err)
	}
	type tracked struct {
		ctx      context.Context
		conn     *ingressDNSPacketConn
		complete func()
	}
	results := make(chan tracked, 100)
	barrier := make(chan struct{})
	var workers sync.WaitGroup
	for range 100 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			<-barrier
			conn := newIngressDNSPacketConn()
			ctx, complete := (&generationDNSIngress{mux.dnsIngress, 1}).TrackDNSIngress(context.Background(), conn)
			results <- tracked{ctx, conn, complete}
		}()
	}
	close(barrier)
	if err := mux.publish(context.Background(), &generation{revision: 2, close: func() error { return nil }}); err != nil {
		t.Fatal(err)
	}
	workers.Wait()
	close(results)
	for session := range results {
		if session.ctx.Err() != context.Canceled || session.conn.closes.Load() != 1 {
			t.Fatal("old DNS registration escaped publication fence")
		}
		session.complete()
		session.complete()
	}
	conn := newIngressDNSPacketConn()
	ctx, complete := (&generationDNSIngress{mux.dnsIngress, 2}).TrackDNSIngress(context.Background(), conn)
	if ctx.Err() != nil || conn.closes.Load() != 0 {
		t.Fatal("publication invalidated current DNS session")
	}
	complete()
	if mux.dnsIngress.pending() {
		t.Fatal("DNS registrations did not drain")
	}
	if err := mux.stop(); err != nil {
		t.Fatal(err)
	}
}
