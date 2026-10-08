package expert

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/adapter"
)

func TestInspectionFitsControlResponseBudget(t *testing.T) {
	h := newFlowHistory(500)
	payload := []byte(strings.Repeat("x", 4096))
	for i := 0; i < 500; i++ {
		f := h.add(1, adapter.InboundContext{Network: "tcp"}, nil)
		for j := 0; j < 10; j++ {
			h.observe(f, len(payload), j%2 == 0, payload, true)
		}
	}
	encoded, err := json.Marshal(h.snapshot())
	if err != nil || len(encoded) >= 1600000 {
		t.Fatalf("preview exceeds control budget: %d, %v", len(encoded), err)
	}
	t.Logf("500 previews and timelines: %d bytes (control limit 2000000)", len(encoded))
}
