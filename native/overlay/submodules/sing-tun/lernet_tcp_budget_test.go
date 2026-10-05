//go:build with_gvisor

package tun

import (
	"testing"

	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/link/channel"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/tcp"
)

func TestLerNETLocalGVisorTCPBufferBudget(t *testing.T) {
	ep := channel.New(1, 1500, "")
	ipStack, err := newGVisorStack(ep, stack.NICOptions{}, false, true)
	if err != nil {
		t.Fatal(err)
	}
	defer ipStack.Close()
	var receive tcpip.TCPReceiveBufferSizeRangeOption
	var send tcpip.TCPSendBufferSizeRangeOption
	if err := ipStack.TransportProtocolOption(tcp.ProtocolNumber, &receive); err != nil {
		t.Fatal(err)
	}
	if err := ipStack.TransportProtocolOption(tcp.ProtocolNumber, &send); err != nil {
		t.Fatal(err)
	}
	if receive.Default != 32768 || send.Default != 32768 || receive.Max != 131072 || send.Max != 131072 {
		t.Fatalf("production local TCP payload budget changed: receive=%+v send=%+v", receive, send)
	}
}
