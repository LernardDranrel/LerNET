package tun

import (
	"encoding/binary"
	"testing"

	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func TestLerNETPendingUDPPayloadAndDatagramBounds(t *testing.T) {
	for _, size := range []int{1500, 65535} {
		c := &udpNatConn{service: &UDPNat{pendingByteLimit: 256 * 1024}, packetChan: make(chan *N.PacketBuffer, 32), doneChan: make(chan struct{})}
		for i := 0; i < 1000; i++ {
			b := buf.NewSize(size)
			binary.BigEndian.PutUint64(b.Extend(size), uint64(i))
			c.enqueue(b, M.Socksaddr{})
		}
		if len(c.packetChan) > 32 || c.pendingBytes.Load() > 256*1024 {
			t.Fatal("UDP cold queue exceeded its byte or datagram budget")
		}
		if len(c.packetChan) == 0 {
			t.Fatal("first datagrams were not retained")
		}
		retained := 32
		if size == 65535 {
			retained = 4
		}
		if len(c.packetChan) != retained {
			t.Fatalf("retained %d packets, want %d", len(c.packetChan), retained)
		}
		for i := 0; i < retained-1; i++ {
			b := buf.NewSize(size)
			_, err := c.ReadPacket(b)
			if err != nil {
				t.Fatal(err)
			}
			if binary.BigEndian.Uint64(b.Bytes()) != uint64(i) {
				t.Fatal("UDP overflow changed retained datagram order")
			}
			b.Release()
		}
		c.close()
		if len(c.packetChan) != 0 || c.pendingBytes.Load() != 0 {
			t.Fatal("close did not drain queued payloads")
		}
	}
}
